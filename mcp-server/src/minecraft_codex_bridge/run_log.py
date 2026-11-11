"""Instance-local agent receipts and issue summaries, never raw game DTOs.

All I/O is off the event loop. A single nonblocking OS lock serializes independent
stdio/serial adapters. Persistence failure never changes or retries a game action.
"""
from __future__ import annotations

from contextlib import contextmanager, closing
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import uuid
import zipfile

from .bridge import BridgeError
from .knowledge import Capture, GOALS, METHOD_VALUES, _facts, _identifier


FOLDER = "agent-runtime-logs"
DEFAULTS = {"enabled": True, "normalMaxMiB": 64, "normalMaxDays": 30, "slowGoalMs": 10000}
SEGMENT_BYTES = 4 * 1024 * 1024
TAIL_BYTES = 1024 * 1024
ISSUE_BYTES = 256 * 1024 * 1024
EXPORT_BYTES = 64 * 1024 * 1024
APP_ID = 0x4D43424C
SEGMENT = re.compile(r"operations-[0-9]{20}-[a-f0-9]{12}\.jsonl\Z")
EXPORT = re.compile(r"report-[0-9]{20}-[a-f0-9]{12}\.zip\Z")
SENSITIVE = re.compile(r"(?i)\b(?:bearer|authorization|password|passwd|secret|credentials|token|(?:access|refresh|auth|session)[ _-]?token|api[ _-]?key)\b")
PERSONAL = re.compile(r"(?i)(?:[a-z]:[\\/]|/home/|/Users/)[^\s\"<>]+|\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b|https?://[^\s<>]+|\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
FAILURES = {"failed", "error", "timeout", "cancelled", "blocked", "unsupported"}


def encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def stamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def text(value, token="", maximum=2048):
    if not isinstance(value, str):
        return ""
    # Redact before truncating so a cut cannot remove the sensitive marker.
    value = value.replace(token, "[redacted]") if token else value
    lines = ["[redacted sensitive line]" if SENSITIVE.search(line) else PERSONAL.sub("[private]", line)
        for line in value[:16384].splitlines()[:64]]
    return "\n".join(lines)[:maximum]


def _owned(path: Path, parent: Path):
    if path.resolve() != parent.resolve() / path.name:
        raise BridgeError("RUN_LOG_PATH_REFUSED", "Agent log paths must not redirect outside the selected client.")
    return path


class RunLog:
    def __init__(self, configuration):
        self.configuration = configuration
        self.gate = threading.Lock()

    def root(self):
        if self.configuration is None:
            raise BridgeError("RUN_LOG_CONFIG_REQUIRED", "Agent logs require a pinned client runtime file.")
        runtime = self.configuration.runtime_path()
        if runtime is None or [part.casefold() for part in runtime.parts[-4:]] != [".minecraft", "config", "codexbridge", "runtime.json"]:
            raise BridgeError("RUN_LOG_CONFIG_REQUIRED", "Agent logs require the selected .minecraft/config/codexbridge/runtime.json path.")
        game = runtime.absolute().parents[2].resolve(strict=True)
        if runtime.absolute().resolve() != game / "config" / "codexbridge" / "runtime.json":
            raise BridgeError("RUN_LOG_PATH_REFUSED", "The selected runtime path may not redirect outside its game directory.")
        root = _owned(game / FOLDER, game)
        root.mkdir(exist_ok=True)
        for name in ("normal", "issues", "exports"):
            _owned(root / name, root).mkdir(exist_ok=True)
        return root

    @contextmanager
    def locked(self):
        if not self.gate.acquire(blocking=False):
            raise BridgeError("RUN_LOG_BUSY", "Another local journal operation is active; game result is preserved.")
        try:
            root = self.root()
            with _owned(root / ".lock", root).open("a+b") as handle:
                if handle.seek(0, 2) == 0:
                    handle.write(b"0")
                    handle.flush()
                handle.seek(0)
                try:
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError:
                    raise BridgeError("RUN_LOG_BUSY", "Another process owns the client journal; game result is preserved.") from None
                try:
                    yield root
                finally:
                    handle.seek(0)
                    if os.name == "nt":
                        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(handle, fcntl.LOCK_UN)
        finally:
            self.gate.release()

    def settings(self, root):
        path = _owned(root / "settings.json", root)
        settings = dict(DEFAULTS)
        if path.exists():
            if path.stat().st_size > 4096:
                raise ValueError("oversized journal settings")
            value = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(value, dict) or set(value) - set(DEFAULTS):
                raise ValueError("unknown journal settings")
            for key, item in value.items():
                if key == "enabled":
                    valid = type(item) is bool
                else:
                    low, high = {"normalMaxMiB": (4, 1024), "normalMaxDays": (1, 365), "slowGoalMs": (100, 3600000)}[key]
                    valid = type(item) is int and low <= item <= high
                if not valid:
                    raise ValueError("invalid journal setting")
            settings.update(value)
        return settings

    def files(self, root, category, pattern):
        directory = _owned(root / category, root)
        return sorted((_owned(p, directory) for p in directory.iterdir() if pattern.fullmatch(p.name) and p.is_file()), key=lambda p: p.name)

    def prune(self, root, settings, incoming=0):
        files = self.files(root, "normal", SEGMENT)
        cutoff = time.time() - settings["normalMaxDays"] * 86400
        # Delete only journal-owned rotated files, never game logs or issue reports.
        for p in list(files):
            if p.stat().st_mtime < cutoff:
                p.unlink()
                files.remove(p)
        total = sum(p.stat().st_size for p in files)
        while files and total + incoming > settings["normalMaxMiB"] * 1024 * 1024:
            p = files.pop(0)
            total -= p.stat().st_size
            p.unlink()
        return files

    def append(self, root, settings, row):
        raw = (encoded(row) + "\n").encode("utf-8")
        if len(raw) > 16384:
            raise ValueError("journal row exceeded fixed projection budget")
        files = self.prune(root, settings, len(raw))
        path = None
        if files and 0 < files[-1].stat().st_size <= SEGMENT_BYTES - len(raw):
            with files[-1].open("rb") as tail:
                tail.seek(-1, 2)
                if tail.read(1) == b"\n":
                    path = files[-1]
        # Keep interrupted evidence intact; a partial row must not swallow the
        # next valid receipt when another adapter resumes after a crash.
        if path is None:
            # Persist append order across adapters, including equal clock ticks and clock rollback.
            sequence = max(time.time_ns(), int(files[-1].name.split("-")[1]) + 1 if files else 0)
            path = root / "normal" / ("operations-" + f"{sequence:020d}" + "-" + uuid.uuid4().hex[:12] + ".jsonl")
        with path.open("ab") as stream:
            stream.write(raw)

    def issues(self, root):
        directory = _owned(root / "issues", root)
        path = _owned(directory / "issues.sqlite3", directory)
        for suffix in ("-journal", "-wal", "-shm"):
            _owned(Path(str(path) + suffix), directory)
        db = sqlite3.connect(path, timeout=0.025)
        try:
            db.execute("PRAGMA busy_timeout=25")
            application = db.execute("PRAGMA application_id").fetchone()[0]
            if application not in (0, APP_ID) or (application == 0 and db.execute("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1").fetchone()):
                raise ValueError("not an agent issue database")
            db.execute("PRAGMA journal_mode=DELETE")
            db.execute("PRAGMA synchronous=NORMAL")
            db.execute(f"PRAGMA max_page_count={ISSUE_BYTES // db.execute('PRAGMA page_size').fetchone()[0]}")
            db.execute("""CREATE TABLE IF NOT EXISTS issues (
                id TEXT PRIMARY KEY, category TEXT NOT NULL, state TEXT NOT NULL,
                first_at TEXT NOT NULL, last_at TEXT NOT NULL, occurrences INTEGER NOT NULL,
                automatic TEXT NOT NULL, note TEXT NOT NULL)""")
            db.execute(f"PRAGMA application_id={APP_ID}")
            db.commit()
            return db
        except BaseException:
            db.close()
            raise

    def token(self):
        try:
            return self.configuration.read().token
        except (BridgeError, OSError):
            return ""

    def finish(self, capture: Capture, arguments, payload, timing):
        with self.locked() as root:
            settings = self.settings(root)
            if not settings["enabled"]:
                return {"recorded": False, "reason": "disabled"}
            # Never write another instance's observation into the pinned journal.
            if capture.instance and Path(capture.instance).resolve() != root.parent.parent:
                return {"recorded": False, "reason": "instance_mismatch"}
            data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
            error = payload.get("error") if isinstance(payload.get("error"), dict) else {}
            duration = timing.get("adapterElapsedMs", 0)
            duration = round(duration, 3) if type(duration) in (int, float) and math.isfinite(duration) and duration >= 0 else 0
            facts = _facts(data)
            nested_errors = []
            outcome_surfaces = [data, error]
            # Fixed workflow surfaces only, never recurse through inventories/NBT/DTOs.
            for key in ("acceptance", "failure", "error", "recovery"):
                value = data.get(key)
                if isinstance(value, dict):
                    facts[key] = _facts(value)
                    outcome_surfaces.append(value)
                    if key in {"failure", "error"} and value:
                        nested_errors.append(value)
            failed = payload.get("ok") is False or bool(error) or str(data.get("status")) in FAILURES or bool(nested_errors)
            unknown = any(v.get(k) is True for v in outcome_surfaces for k in ("outcomeUnknown", "outcome_unknown", "sideEffectsPossible"))
            partial = data.get("partial") is True
            slow = capture.name in GOALS and duration > settings["slowGoalMs"]
            reason = "failure" if failed else "outcome_unknown" if unknown else "partial_result" if partial else "slow_goal" if slow else None
            from .tools import TOOL_BY_NAME
            routine = None
            if payload.get("ok") is True and not error and not unknown and not capture.inconsistent:
                native = data.get("native")
                if (capture.name == "mcb_ae_order" and data.get("status") == "native_pending"
                    and data.get("resumable") is True and data.get("replayAllowed") is False
                    and isinstance(native, dict) and native.get("state") == "running"
                    and isinstance(arguments.get("requestId"), str) and arguments["requestId"]
                    and native.get("requestId") == arguments["requestId"]
                    and native.get("source") == "integrated_server" and native.get("replayAllowed") is False
                    and not any(native.get(key) is True for key in ("outcomeUnknown", "outcome_unknown", "sideEffectsPossible"))
                    and len(nested_errors) == 1 and isinstance(data.get("error"), dict) and data["error"].get("code") == "AE_GOAL_TIMEOUT"):
                    routine = "native_order_still_running"
                spec = TOOL_BY_NAME.get(capture.name)
                if (not failed and partial and spec is not None and spec.read_only
                    and data.get("hasMore") is True and type(data.get("nextOffset")) is int
                    and type(arguments.get("offset", 0)) is int and data["nextOffset"] > arguments.get("offset", 0)):
                    routine = "bounded_read_page"
            if routine:
                reason = None  # Retain the receipt; expected continuation is not a new issue.
            row = {"version": 1, "id": capture.call_id, "at": stamp(), "source": "mcp_adapter",
                "tool": capture.name if capture.name in TOOL_BY_NAME else "unknown", "ok": payload.get("ok") is True,
                "elapsedMs": duration, "facts": facts,
                "method": {k: arguments[k] for k, values in METHOD_VALUES.items() if isinstance(arguments.get(k), str) and arguments[k] in values},
                "context": {"environment": capture.environment, "profile": capture.profile, "dimension": capture.dimension,
                    "sessionDigest": hashlib.sha256(capture.session.encode()).hexdigest()[:16] if capture.session else None,
                    "versions": capture.versions, "consistent": not capture.inconsistent},
                "operations": [{"operation": _identifier(v.get("operation")), **_facts(v),
                    "phase": _identifier(v.get("phase")), "method": {key: v["method"][key] for key, choices in METHOD_VALUES.items()
                        if isinstance(v.get("method"), dict) and isinstance(v["method"].get(key), str) and v["method"][key] in choices}}
                    for v in capture.trace[:24]],
                "correlation": {"callId": capture.call_id, "parentCallId": capture.parent_call_id, "goalDigest": capture.goal_digest},
                "waitSamples": capture.wait_samples,
                "phases": [{"phase": name, "observedBridgeCalls": count} for name, count in capture.phases.items()],
                "phasesLimited": capture.phases_limited,
                "methods": [{"operation": operation, "action": action, "observedCalls": count}
                    for (operation, action), count in capture.methods.items()],
                "methodsLimited": capture.methods_limited,
                "measurement": {key: timing[key] for key in ("bridgeCalls", "bridgeResponseBytes", "bridgeResponseMeasuredCalls",
                    "bridgeRoundTripSumMs", "bridgeRoundTripMaxMs") if type(timing.get(key)) in (int, float)
                    and math.isfinite(timing[key]) and timing[key] >= 0},
                "resultJsonBytes": len(encoded(payload).encode("utf-8")),
                "resultByteScope": "structured result before timing/journal/knowledge wrappers; no result content retained"}
            row["operationsTruncated"] = capture.observations > len(row["operations"])
            if routine:
                row["observationKind"] = routine
            if capture.name == "mcb_health":
                executing = data.get("executingBridge")
                if isinstance(executing, dict) and re.fullmatch(r"[a-f0-9]{64}", str(executing.get("buildFingerprint", ""))):
                    row["context"]["buildFingerprint"] = executing["buildFingerprint"]
            issue_id = None
            if reason:
                diagnostic = error or (nested_errors[0] if nested_errors else {})
                row["attention"] = {"reason": reason, "code": _identifier(diagnostic.get("code")),
                    "message": text(diagnostic.get("message"), self.token(), 1024), "outcomeUnknown": unknown}
                receipt = diagnostic.get("receipt")
                phases = receipt.get("phases") if isinstance(receipt, dict) else None
                last_phase = phases[-1] if isinstance(phases, list) and phases and isinstance(phases[-1], dict) else {}
                if last_phase.get("phase") in {"shared_stand_plan", "native_route_and_move", "current_recheck", "native_use", "native_gui_acceptance"}:
                    row["attention"]["phase"] = last_phase["phase"]
                planning = diagnostic.get("planning")
                if isinstance(planning, dict) and planning.get("stopReason") in {"tick_budget", "ray_limit", "no_candidate", "client_face_unverified"}:
                    row["attention"]["planningStopReason"] = planning["stopReason"]
                category = "error" if failed or unknown else "optimization"
                # Build/version changes form different incidents; no machine-specific adapters.
                signature = [row["tool"], category, reason, row["attention"]["code"], facts.get("stage"), capture.versions]
                if "phase" in row["attention"] or "planningStopReason" in row["attention"]:
                    signature.append({key: row["attention"][key] for key in ("phase", "planningStopReason") if key in row["attention"]})
                issue_id = hashlib.sha256(encoded(signature).encode()).hexdigest()
                row["issueId"] = issue_id
            while row["operations"] and len(encoded(row).encode("utf-8")) > 12000:
                row["operations"].pop(0)
                row["operationsTruncated"] = True
            # Preserve the ordinary receipt even if issue storage is full/unavailable.
            self.append(root, settings, row)
            result = {"recorded": True, "eventId": row["id"]}
            if issue_id:
                with closing(self.issues(root)) as db, db:
                    db.execute("""INSERT INTO issues VALUES (?,?, 'open',?,?,1,?, '{}')
                        ON CONFLICT(id) DO UPDATE SET state=CASE WHEN issues.state='deferred' THEN 'deferred' ELSE 'open' END, last_at=excluded.last_at,
                        occurrences=issues.occurrences+1, automatic=excluded.automatic""",
                        (issue_id, category, row["at"], row["at"], encoded(row)))
                    saved_note, saved_state = db.execute("SELECT note,state FROM issues WHERE id=?", (issue_id,)).fetchone()
                    note = json.loads(saved_note)
                    parent = note.get("resolvedWith")
                    if saved_state == "open" and parent and parent != issue_id:
                        # Only a new actual incident can reopen the explicitly linked finding.
                        db.execute("UPDATE issues SET state='open',last_at=? WHERE id=? AND state='resolved'", (row["at"], parent))
                result.update(issueId=issue_id, category=category, nextTool="mcb_run_log")
            if capture.name == "mcb_health":
                result.update(self.status(root, settings))
            return result

    def status(self, root, settings):
        files = self.prune(root, settings)
        path = root / "issues" / "issues.sqlite3"
        counts = []
        triage = {"openUnreviewed": 0, "openReviewed": 0, "deferred": 0, "resolved": 0}
        if path.exists():
            with closing(self.issues(root)) as db:
                counts = [{"category": a, "state": b, "count": c} for a, b, c in db.execute("SELECT category,state,count(*) FROM issues GROUP BY category,state")]
                for state, reviewed, count in db.execute("SELECT state,note!='{}',count(*) FROM issues GROUP BY state,note!='{}'"):
                    key = ("openReviewed" if reviewed else "openUnreviewed") if state == "open" else state
                    if key in triage:
                        triage[key] += count
        return {"root": str(root), "settings": settings, "normalBytes": sum(p.stat().st_size for p in files),
            "normalFiles": len(files), "issues": counts, "issueStorageMaxMiB": ISSUE_BYTES // (1024 * 1024),
            "triage": triage, "triageMeaning": "Open automatic entries are review candidates, not confirmed current bugs. Deferred is explicitly postponed; resolved retains history and can recur.",
            "issueRetention": "Unresolved summaries are never evicted. Repeats merge; full issue storage is reported explicitly.",
            "scope": "MCP/serial Adapter calls and agent-reported findings, not full Minecraft/OS logs or agent private reasoning.",
            "nextTool": "mcb_run_log", "actions": ["read", "report", "resolve", "defer", "export", "configure"],
            "historyOnly": True, "automaticReplayAllowed": False}

    def read_normal(self, root, args):
        rows, scanned, stopped = [], 0, False
        for path in reversed(self.files(root, "normal", SEGMENT)):
            size = path.stat().st_size
            take = min(size, TAIL_BYTES - scanned)
            with path.open("rb") as stream:
                stream.seek(size - take)
                raw = stream.read(take)
            scanned += take
            if take < size:
                raw = raw.partition(b"\n")[2]
                stopped = True
            for line in reversed(raw.splitlines()):
                try:
                    value = json.loads(line)
                except (ValueError, UnicodeError):
                    continue  # interrupted append is never reported as a valid receipt
                if not isinstance(value, dict):
                    continue
                if args.get("tool") and value.get("tool") != args["tool"]:
                    continue
                if args.get("before") and value.get("at", "") >= args["before"]:
                    continue
                rows.append(value)
                if len(rows) >= args.get("limit", 20):
                    return {"entries": rows, "hasMore": True, "scanLimited": stopped, "order": "newest_first"}
            if scanned >= TAIL_BYTES:
                break
        return {"entries": rows, "hasMore": stopped or scanned >= TAIL_BYTES, "scanLimited": stopped or scanned >= TAIL_BYTES, "order": "newest_first"}

    def read_issues(self, root, args):
        with closing(self.issues(root)) as db:
            where, values = [], []
            for key, column in (("id", "id"), ("category", "category"), ("state", "state")):
                if args.get(key):
                    where.append(column + "=?")
                    values.append(args[key])
            if args.get("reviewStatus") in {"unreviewed", "reviewed"}:
                where.append("note='{}'" if args["reviewStatus"] == "unreviewed" else "note!='{}'")
            limit, offset = args.get("limit", 20), args.get("offset", 0)
            sql = "SELECT id,category,state,first_at,last_at,occurrences,automatic,note FROM issues"
            if where:
                sql += " WHERE " + " AND ".join(where)
            rows = db.execute(sql + " ORDER BY last_at DESC,id LIMIT ? OFFSET ?", [*values, limit + 1, offset]).fetchall()
        entries = [{"id": a, "category": b, "state": c, "firstAt": d, "lastAt": e, "occurrences": f,
            "automatic": json.loads(g), "agentSummary": json.loads(h)} for a,b,c,d,e,f,g,h in rows[:limit]]
        details = args.get("includeDetails", False)
        for entry in entries:
            entry["reviewStatus"] = "reviewed" if entry["agentSummary"] else "unreviewed"
            if not details:
                entry["automatic"] = {key: value for key, value in entry["automatic"].items()
                    if key in {"version", "id", "at", "tool", "ok", "elapsedMs", "facts", "attention", "method"}}
        return {"entries": entries, "offset": offset, "nextOffset": offset + len(entries), "hasMore": len(rows) > limit,
            "nonAtomic": True, "detailsIncluded": details}

    def command(self, args):
        with self.locked() as root:
            settings = self.settings(root)
            action = args.get("action", "status")
            if action == "status":
                return self.status(root, settings)
            if action == "configure":
                settings.update({k: args[k] for k in DEFAULTS if k in args})
                path = _owned(root / "settings.json", root)
                temp = _owned(root / "settings.json.tmp", root)
                temp.write_text(encoded(settings), encoding="utf-8")
                os.replace(temp, path)
                return self.status(root, settings)
            if action == "read":
                return {"historyOnly": True, **(self.read_normal(root, args) if args.get("category") == "normal" else self.read_issues(root, args))}
            if action in {"report", "resolve", "defer"}:
                token = self.token()
                note = {k: text(args[k], token, 240 if k == "title" else 2048) for k in
                    ("title", "observed", "expected", "reproduction", "suggestion", "resolution", "reason", "resumeWhen") if k in args}
                if "eventId" in args:
                    note["eventId"] = args["eventId"]
                now = stamp()
                state = {"resolve": "resolved", "defer": "deferred"}.get(action, "open")
                related = args.get("relatedIds", [])
                with closing(self.issues(root)) as db, db:
                    identity = args.get("id")
                    if identity:
                        identities = [identity, *related]
                        if len(set(identities)) != len(identities):
                            raise BridgeError("INVALID_ARGUMENTS", "Related issue IDs must be distinct from the primary issue.")
                        existing = {key: value for key, value in db.execute(
                            "SELECT id,note FROM issues WHERE id IN (" + ",".join("?" for _ in identities) + ")", identities)}
                        if len(existing) != len(identities):
                            raise BridgeError("RUN_LOG_ISSUE_NOT_FOUND", "Every exact related issue must exist before any resolution is saved.")
                        note = {**json.loads(existing[identity]), **note}
                        if action == "resolve":
                            note.update(resolvedAt=now, resolutionKind=args.get("resolutionKind", "fixed"))
                            if related:
                                note["relatedIssueIds"] = list(related)
                            for key in related:
                                child = {**json.loads(existing[key]), "resolution": note["resolution"],
                                    "resolutionKind": note["resolutionKind"], "resolvedAt": now, "resolvedWith": identity}
                                db.execute("UPDATE issues SET note=?,state='resolved',last_at=? WHERE id=?", (encoded(child), now, key))
                        elif action == "defer":
                            note["deferredAt"] = now
                        db.execute("UPDATE issues SET note=?,state=?,last_at=? WHERE id=?",
                            (encoded(note), state, now, identity))
                    else:
                        identity = hashlib.sha256(encoded(["agent_report", args["category"], note]).encode()).hexdigest()
                        db.execute("""INSERT INTO issues VALUES (?,?,'open',?,?,1,'{}',?) ON CONFLICT(id) DO UPDATE SET
                            occurrences=issues.occurrences+1, state='open', last_at=excluded.last_at""",
                            (identity, args["category"], now, now, encoded(note)))
                return {"id": identity, "state": state, "saved": True, "relatedIds": related,
                    "source": "agent_report", "authority": "Reported observation/hypothesis, never game-state proof."}
            if action == "export":
                issues = self.read_issues(root, {k: v for k,v in args.items() if k in {"id", "category", "state"}} | {"limit": 100, "includeDetails": True})
                normal = self.read_normal(root, {"limit": 100})
                report = {"version": 1, "createdAt": stamp(), "settings": settings, "issues": issues, "normal": normal,
                    "coverage": "At most 100 selected issues and 100 recent MCP receipts; inspect hasMore/scanLimited. No images, NBT, chat, credentials or raw arguments.",
                    "automaticReplayAllowed": False, "historyOnly": True}
                data = encoded(report).encode("utf-8")
                if len(data) > 4 * 1024 * 1024:
                    raise BridgeError("RUN_LOG_EXPORT_LIMIT", "Select fewer issues for this bounded report export.")
                heading = ["# Agent runtime problem report", "", "Historical receipts and agent observations; not live game evidence.", ""]
                for issue in issues["entries"]:
                    note, auto = issue["agentSummary"], issue["automatic"]
                    heading += [f"## {issue['category']}: {note.get('title') or auto.get('tool', 'Reported issue')}",
                        f"ID: {issue['id']}; state: {issue['state']}; occurrences: {issue['occurrences']}",
                        "", *[f"{k}: {v}" for k,v in note.items()],
                        "", "Automatic receipt: " + encoded(auto), ""]
                path = root / "exports" / ("report-" + f"{time.time_ns():020d}" + "-" + uuid.uuid4().hex[:12] + ".zip")
                try:
                    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                        archive.writestr("report.md", "\n".join(heading))
                        archive.writestr("report.json", data)
                except BaseException:
                    path.unlink(missing_ok=True)
                    raise
                files = self.files(root, "exports", EXPORT)
                total = sum(p.stat().st_size for p in files)
                while files and total > EXPORT_BYTES:
                    p = files.pop(0)
                    total -= p.stat().st_size
                    p.unlink()
                return {"path": str(path), "bytes": path.stat().st_size, "issueCount": len(issues["entries"]),
                    "issuesPartial": issues["hasMore"], "normalPartial": normal["hasMore"], "uploaded": False}
            raise BridgeError("INVALID_ARGUMENTS", "Unknown journal action.")
