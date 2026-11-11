"""Evidence-backed MCB operating lessons, not game state or executable policy.

Automatic distillation is deterministic and bounded. Free-text lessons retain
their agent-reported provenance; repetition cannot prove a proposed root cause.
"""
from __future__ import annotations

import hashlib
import json
import time

MAX_LESSONS = 2000
SCAN_LIMIT = 256
TTL = 180 * 86400
ACCEPTED = frozenset({"recipe_task_acceptance", "qa_full_flow_acceptance", "structure_task_acceptance",
    "interaction_arrival_only", "declared_gui_acceptance_only"})
FIXED = (
    {"id": "mcb.interaction_goal", "tools": ("mcb_approach", "mcb_interact"),
     "title": "Let the client own the interaction approach",
     "when": "A standalone block/part interaction needs movement or a reachable aim point.",
     "instruction": "Use mcb_approach(interact=true) with the observed target identity and allowed sides. Reuse an in-reach stand; let the client compute geometry, route and view. Existing goal workflows already own this step.",
     "verification": "Require settled dual arrival and the declared interaction/GUI acceptance. A ray hit or use submission alone is insufficient.",
     "source": "interaction_approach.py; operation_policy.py; mcb_approach contract"},
    {"id": "mcb.nei_ghost", "tools": ("mcb_nei_item_action", "mcb_ae"),
     "title": "Verify synchronized ghost configuration and release the ghost cursor",
     "when": "Marking an item/fluid template in a supported configuration slot.",
     "instruction": "Prefer NEI ghosts, especially in survival. Use native supported marking, then release a remaining ghost with nei.item_action release. Do not treat a drag icon as stored configuration or use give/take in survival.",
     "verification": "Read the synchronized target configuration and prove that real inventory was neither gained nor consumed. A ghost template is not supplied material.",
     "source": "mcb_nei_item_action and mcb_ae contracts; agent_instructions.py"},
    {"id": "mcb.uncertain_result", "tools": (),
     "title": "Inspect uncertain effects before another mutation",
     "when": "A mutation timed out or reports unknown/partial effects.",
     "instruction": "Inspect fresh authoritative state. Do not replay the mutation or consumed cursor. Only an explicit method-specific pre-execution refusal permits an independently authorized fallback.",
     "verification": "Bind current session, world, profile, dimension and exact resources; retain unknown if authoritative coverage is incomplete.",
     "source": "bridge.py; operation_policy.py; agent_instructions.py"},
)


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def fixed_lessons(tool, *, unknown=False):
    return [{**{key: value for key, value in rule.items() if key != "tools"},
        "origin": "builtin", "state": "shipped_guidance", "automaticReplayAllowed": False}
        for rule in FIXED if tool in rule["tools"] or unknown and rule["id"] == "mcb.uncertain_result"]


def _records(db, binding=None, tool=None):
    where, args = ["last_at>=?"], [time.time() - TTL]
    for key, value in (("binding", binding), ("tool", tool)):
        if value is not None:
            where.append(key + "=?")
            args.append(value)
    rows = db.execute("SELECT id,binding,tool,method,status,evidence,occurrences,witnesses,last_at,"
        "elapsed_sum,elapsed_min,elapsed_max,summary FROM experiences WHERE " + " AND ".join(where) +
        " ORDER BY last_at DESC,id LIMIT ?", [*args, SCAN_LIMIT + 1]).fetchall()
    return [{"id": r[0], "binding": r[1], "tool": r[2], "method": json.loads(r[3]), "status": r[4],
        "evidence": r[5], "occurrences": r[6], "sessions": json.loads(r[7]), "lastAt": r[8],
        "sumMs": r[9], "minMs": r[10], "maxMs": r[11], "summary": json.loads(r[12])}
        for r in rows[:SCAN_LIMIT]], len(rows) > SCAN_LIMIT


def _unknown(record):
    facts = record["summary"].get("facts", {})
    return record["evidence"] == "uncertain" or any(value.get(key) is True
        for value in (facts, facts.get("failure", {}), facts.get("receipt", {}))
        for key in ("outcomeUnknown", "outcome_unknown"))


def automatic_lessons(db, *, binding=None, tool=None):
    records, partial = _records(db, binding, tool)
    groups, mixed = {}, set()
    for record in records:
        method_key = _hash([record["binding"], record["tool"], record["method"]])
        facts = record["summary"].get("facts", {})
        failure = facts.get("failure", {})
        code = failure.get("code") or facts.get("errorCode") or facts.get("code")
        if _unknown(record):
            kind, code = "uncertain_effects", code or "unknown"
        elif record["evidence"] == "observed_failure":
            kind, code = "failure_pattern", code or "unspecified"
        elif record["evidence"] in ACCEPTED:
            kind, code = "accepted_method", record["evidence"]
        else:
            continue  # scheduled/returned/planned is never successful execution
        mixed.add((method_key, kind))
        identity = _hash([method_key, kind, code])
        group = groups.setdefault(identity, {"id": identity, "origin": "automatic", "kind": kind,
            "binding": record["binding"], "tool": record["tool"], "method": record["method"],
            "signal": code, "evidenceIds": [], "occurrences": 0, "sessions": set(), "lastAt": 0,
            "sumMs": 0, "minMs": record["minMs"], "maxMs": 0, "methodKey": method_key})
        group["evidenceIds"].append(record["id"])
        group["occurrences"] += record["occurrences"]
        group["sessions"].update(record["sessions"])
        group["lastAt"] = max(group["lastAt"], record["lastAt"])
        group["sumMs"] += record["sumMs"]
        group["minMs"] = min(group["minMs"], record["minMs"])
        group["maxMs"] = max(group["maxMs"], record["maxMs"])
    lessons = []
    for group in groups.values():
        if group["occurrences"] < 2:
            continue
        corroborated = group["occurrences"] >= 3 and len(group["sessions"]) >= 2
        competing = group["kind"] == "accepted_method" and any((group["methodKey"], kind) in mixed
            for kind in ("failure_pattern", "uncertain_effects"))
        state = "mixed_outcomes" if competing else "corroborated_pattern" if corroborated else "observed_pattern"
        if group["kind"] == "accepted_method":
            instruction = "This recorded method projection has historical acceptance at the stated scope. Check current target, mode and resource preconditions; reuse the goal interface only if its current contract covers the task. Do not replay old actions."
            verification = "Require fresh acceptance at the same scope; interaction/GUI acceptance never proves production."
        elif group["kind"] == "uncertain_effects":
            instruction = FIXED[2]["instruction"]
            verification = FIXED[2]["verification"]
        else:
            instruction = "Check current structured diagnostics and preconditions before another attempt. Repetition establishes this failure pattern, not its root cause or a working recovery."
            verification = "A recovery needs its own fresh acceptance; failure-free return alone is insufficient."
        lessons.append({key: group[key] for key in ("id", "origin", "kind", "binding", "tool", "method", "signal", "occurrences", "lastAt")} |
            {"state": state, "independentSessions": len(group["sessions"]), "evidenceIds": group["evidenceIds"][:8],
             "evidenceIdsTruncated": len(group["evidenceIds"]) > 8,
             "when": {"tool": group["tool"], "method": group["method"], "observedSignal": group["signal"]},
             "conditionsNeedLiveCheck": True,
             "instruction": instruction, "verification": verification,
             "timing": {"meanMs": round(group["sumMs"] / group["occurrences"], 3), "minMs": group["minMs"], "maxMs": group["maxMs"],
                 "scope": "adapter only; not model/approval/CPU time; no causal method ranking"},
             "promotionCandidate": corroborated and not competing and group["kind"] != "uncertain_effects",
             "requiresLiveRevalidation": True, "automaticReplayAllowed": False})
    return sorted(lessons, key=lambda v: (v["promotionCandidate"], v["lastAt"]), reverse=True), partial


def _sources(db, ids):
    if not ids:
        return []
    rows = db.execute("SELECT id,binding,tool,evidence,occurrences,witnesses,method FROM experiences WHERE id IN (" +
        ",".join("?" for _ in ids) + ") AND last_at>=?", [*ids, time.time() - TTL]).fetchall()
    return [{"id": a, "binding": b, "tool": c, "evidence": d, "occurrences": e, "sessions": json.loads(f),
        "method": json.loads(g)} for a,b,c,d,e,f,g in rows]


def _note_view(db, row):
    identity, binding, tool, kind, retired, updated, raw = row
    note = json.loads(raw)
    sources = _sources(db, note["evidenceIds"])
    counters = _sources(db, note.get("counterexampleIds", []))
    complete = len(sources) == len(note["evidenceIds"]) and len(counters) == len(note.get("counterexampleIds", []))
    sessions = set(session for source in sources for session in source["sessions"])
    occurrences = sum(source["occurrences"] for source in sources)
    state = "retired" if retired else "disputed" if counters else "needs_revalidation" if not complete else "agent_reported"
    accepted = all(source["evidence"] in ACCEPTED for source in sources) if sources else False
    methods = {json.dumps(source["method"], sort_keys=True): source["method"] for source in sources}
    return {"id": identity, "binding": binding, "tool": tool, "kind": kind, "lastAt": updated,
        "origin": "agent_reported", **note, "state": state, "evidenceComplete": complete,
        "sourceMethods": list(methods.values()), "conditionsNeedLiveCheck": True,
        "independentSessions": len(sessions), "occurrences": occurrences,
        "acceptanceEvidence": accepted, "interpretationVerified": False,
        "promotionCandidate": state == "agent_reported" and accepted and len(sessions) >= 2 and occurrences >= 3,
        "requiresLiveRevalidation": True, "automaticReplayAllowed": False}


def learn(db, context, args, token=""):
    from .bridge import BridgeError
    if context is None:
        raise BridgeError("KNOWLEDGE_CONTEXT_REQUIRED", "Observe the current compatible client context before recording an operating lesson.")
    from .tools import TOOL_BY_NAME
    if args["tool"] not in TOOL_BY_NAME or args["tool"] in {"mcb_knowledge", "mcb_run_log"}:
        raise BridgeError("INVALID_ARGUMENTS", "An operating lesson must target an installed game-operation tool.")
    sources = _sources(db, args["evidenceIds"])
    counters = _sources(db, args.get("counterexampleIds", []))
    if len(sources) != len(args["evidenceIds"]) or len(counters) != len(args.get("counterexampleIds", [])) or any(
        value["binding"] != context["id"] or value["tool"] != args["tool"] for value in [*sources, *counters]):
        raise BridgeError("KNOWLEDGE_EVIDENCE_MISMATCH", "Every evidence ID must be fresh, present and match this client context and tool.")
    if set(args["evidenceIds"]) & set(args.get("counterexampleIds", [])):
        raise BridgeError("INVALID_ARGUMENTS", "Supporting and counterexample records must be distinct.")
    from .run_log import text
    note = {key: text(args[key], token, 240 if key == "title" else 768)
        for key in ("title", "when", "instruction", "verification")}
    if not all(value.strip() for value in note.values()):
        raise BridgeError("INVALID_ARGUMENTS", "Operating lessons require a title, conditions, instruction and verification.")
    identity = _hash([context["id"], args["tool"], args["kind"], note])
    old = db.execute("SELECT summary FROM operation_lessons WHERE id=?", (identity,)).fetchone()
    if old is None and db.execute("SELECT COUNT(*) FROM operation_lessons").fetchone()[0] >= MAX_LESSONS:
        raise BridgeError("KNOWLEDGE_LESSON_LIMIT", "The local operating-lesson limit is full; no game action was performed.")
    previous = json.loads(old[0]) if old else {}
    for key in ("evidenceIds", "counterexampleIds"):
        note[key] = list(dict.fromkeys([*previous.get(key, []), *args.get(key, [])]))
        if len(note[key]) > 8:
            raise BridgeError("KNOWLEDGE_EVIDENCE_LIMIT", "A lesson retains at most eight supporting and eight counterexample IDs.")
    if set(note["evidenceIds"]) & set(note["counterexampleIds"]):
        raise BridgeError("INVALID_ARGUMENTS", "An existing support record cannot also be its own counterexample.")
    db.execute("INSERT INTO operation_lessons VALUES (?,?,?,?,0,?,?) ON CONFLICT(id) DO UPDATE SET last_at=excluded.last_at,summary=excluded.summary",
        (identity, context["id"], args["tool"], args["kind"], time.time(), json.dumps(note, ensure_ascii=False, separators=(",", ":"))))
    db.commit()
    return {"saved": True, "lesson": _note_view(db, db.execute("SELECT * FROM operation_lessons WHERE id=?", (identity,)).fetchone()),
        "automaticPolicyPromotion": False, "sourceCodeChanged": False}


def lessons(db, context, *, tool=None, limit=3, review=False, unknown=False, identity=None, method=None):
    if context is None and not review:
        fixed = fixed_lessons(tool, unknown=unknown)
        return {"available": bool(fixed), "historyAvailable": False,
            "reason": "current_compatible_context_not_observed", "lessons": [], "builtin": fixed}
    binding = None if review else context["id"]
    automatic, partial = automatic_lessons(db, binding=binding, tool=tool)
    if identity:
        automatic = [item for item in automatic if item["id"] == identity]
    where, values = ([] if identity else ["retired=0"]), []
    if identity:
        where.append("id=?")
        values.append(identity)
    for key, value in (("binding", binding), ("tool", tool)):
        if value is not None:
            where.append(key + "=?")
            values.append(value)
    rows = db.execute("SELECT * FROM operation_lessons WHERE " + " AND ".join(where) +
        " ORDER BY last_at DESC,id LIMIT 33", values).fetchall()
    notes = [_note_view(db, row) for row in rows[:32]]
    selected = sorted([*automatic, *notes], key=lambda v: (v["promotionCandidate"], v["lastAt"]), reverse=True)
    if not review and not identity:
        selected = [value for value in selected if value["state"] not in {"disputed", "needs_revalidation"}]
    if method is not None:
        selected = [value for value in selected if value.get("method") == method or method in value.get("sourceMethods", [])]
    result = []
    for item in selected[:limit]:
        result.append({**item, "appliesToCurrentBinding": context is not None and item["binding"] == context["id"],
            "promotion": {"target": "mcp_workflow_or_tool_guidance", "needsSourceReviewAndRegressionTests": True,
                "needsCrossEnvironmentVerification": True, "automaticCodeChange": False}})
    return {"available": True, "lessons": result, "builtin": fixed_lessons(tool, unknown=unknown),
        "hasMore": len(selected) > limit, "scanLimited": partial or len(rows) > 32,
        "recordsInspectedMax": SCAN_LIMIT, "scope": "offline_review_only" if review else "current_compatible_binding",
        "automaticPolicyPromotion": False, "automaticReplayAllowed": False}


def compact(result):
    """Call receipts carry actionable advice; explicit queries retain full sources."""
    keys = {"id", "origin", "kind", "state", "when", "instruction", "verification", "method", "sourceMethods",
        "occurrences", "independentSessions", "promotionCandidate", "conditionsNeedLiveCheck"}
    return {key: result[key] for key in ("available", "hasMore", "scanLimited", "scope") if key in result} | {
        "lessons": [{key: value for key, value in item.items() if key in keys} for item in result.get("lessons", [])],
        "builtin": [{key: value for key, value in item.items() if key in keys} for item in result.get("builtin", [])],
        "historicalOnly": True, "automaticReplayAllowed": False}
