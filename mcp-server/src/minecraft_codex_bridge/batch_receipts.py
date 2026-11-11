"""Portable batch progress, with fresh evidence required before resuming writes."""
from .bridge import BridgeError
from .conveniences import json_sha256


def unknown_outcome(value):
    if isinstance(value, dict):
        return value.get("outcomeUnknown") is True or value.get("outcome_unknown") is True \
            or any(unknown_outcome(child) for child in value.values())
    return isinstance(value, list) and any(unknown_outcome(child) for child in value)


def install_fingerprint(arguments):
    return json_sha256({key: arguments[key] for key in ("expectedSessionId", "expectedWorld", "dimension", "targets")})


def install_checkpoint(arguments, completed, failed, error=None):
    uncertain = [failed] if failed is not None and unknown_outcome(error) else []
    return {"version": 1, "planFingerprint": install_fingerprint(arguments),
        "completedIndices": sorted(completed), "uncertainIndices": uncertain,
        "pendingIndices": [i for i in range(len(arguments["targets"])) if i not in completed and i not in uncertain]}


def validate_install_resume(arguments):
    from .placement_attachment import VECTORS
    cells = set()
    for declaration in arguments["targets"]:
        attachment = declaration["attachment"]
        vector = (0, 0, 0) if declaration.get("placement") == "host" else VECTORS[attachment["allowedSides"][0]]
        point = tuple(attachment[axis] + vector[i] for i, axis in enumerate("xyz"))
        if not -29999996 <= point[0] <= 29999996 or not 0 <= point[1] <= 255 or not -29999996 <= point[2] <= 29999996:
            raise BridgeError("INVALID_ARGUMENTS", "An AE destination lies outside the supported world bounds.")
        cell = (*point, declaration["expectedPart"]["side"])
        if cell in cells:
            raise BridgeError("AE_DUPLICATE_TARGET", "Two declarations address the same destination part slot; no target was installed.")
        cells.add(cell)
    checkpoint = arguments.get("resume")
    if checkpoint is None:
        return set()
    groups = [checkpoint[key] for key in ("completedIndices", "uncertainIndices", "pendingIndices")]
    values = [index for group in groups for index in group]
    if checkpoint["planFingerprint"] != install_fingerprint(arguments) or len(values) != len(set(values)) \
            or sorted(values) != list(range(len(arguments["targets"]))):
        raise BridgeError("BATCH_CHECKPOINT_MISMATCH", "Resume requires the same session/world/dimension/target plan and a complete disjoint checkpoint.")
    if checkpoint["uncertainIndices"]:
        raise BridgeError("BATCH_OUTCOME_UNKNOWN", "An uncertain native action cannot be resumed automatically. Inspect its settled outcome before planning further writes.")
    return set(checkpoint["completedIndices"])


def sequence_receipts(steps, report):
    """An acknowledged mutation is only accepted by its declared later condition."""
    accepted, required = {}, {}
    for step, entry in zip(steps, report["steps"]):
        for action in step.get("accepts", []):
            required.setdefault(action, []).append(step["id"])
        if step["kind"] in ("wait", "assert") and entry.get("status") == "completed" and entry.get("matched") is True:
            for action in step.get("accepts", []):
                accepted.setdefault(action, []).append(step["id"])
    result = []
    for step, entry in zip(steps, report["steps"]):
        if step["kind"] != "action":
            continue
        verified = bool(required.get(step["id"])) and accepted.get(step["id"]) == required[step["id"]]
        state = "condition_verified" if verified else "outcome_unknown" if entry.get("outcomeUnknown") \
            else "acknowledged" if entry.get("status") == "completed" else "needs_reconciliation" if entry.get("dispatched") \
            else "not_dispatched"
        result.append({"id": step["id"], "state": state, "acceptedBy": accepted.get(step["id"], [])})
    return {"version": 1, "actions": result,
        "unexecutedStepIds": [entry["id"] for entry in report["steps"] if entry.get("status") == "not_executed"],
        "effectAcceptanceComplete": all(row["state"] == "condition_verified" for row in result),
        "scope": "Caller-declared conditions at their observation time, not final post-cleanup state; no inference from submission, counters or cached GUI identifiers.",
        "automaticReplayAllowed": False}
