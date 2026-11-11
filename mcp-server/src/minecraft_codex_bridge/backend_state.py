"""Versioned lossless worker handoff; game acceptance belongs to game tools."""
from copy import deepcopy

from .backend_transport import BackendError, MAX_STATE, encode, fingerprint

STATE_VERSION = 2
MAX_FAILED_ACTIONS = 64
ORDER_PHASES = {"new", "preparing", "preview_pending", "native_pending", "collecting", "take_pending", "delivery_sync",
                "start_unknown", "take_unknown", "completed", "blocked", "canceled"}
STATE_KEYS = {"version", "orders", "mutationSeen", "uncertain", "failedActions",
              "movementFailures", "reconciledMovements"}


def action_result(value):
    # These are Adapter annotations, appended after the current action returns.
    return deepcopy({key: child for key, child in value.items()
        if key not in {"localKnowledge", "agentRunLog", "mcpTiming", "feedback"}}) if isinstance(value, dict) else value


def replay_key(name, arguments):
    # Operational budgets, formatting and a new order ID cannot authorize replay.
    return fingerprint({"tool": name, "arguments": {key: value for key, value in arguments.items()
        if key not in {"timeout_ms", "includeDetails", "requestId"}}})


def validate_state(state, validate_order):
    if (not isinstance(state, dict) or set(state) != STATE_KEYS
            or type(state["version"]) is not int or state["version"] != STATE_VERSION
            or type(state["mutationSeen"]) is not bool or type(state["uncertain"]) is not bool
            or type(state["reconciledMovements"]) is not int or state["reconciledMovements"] < 0
            or any(not isinstance(state[key], list) or len(state[key]) > 64
                   for key in ("orders", "failedActions", "movementFailures"))):
        raise BackendError("BACKEND_STATE_INVALID")
    if len(encode(state)) > MAX_STATE:
        raise BackendError("BACKEND_STATE_TOO_LARGE")
    restored = {}
    for record in state["orders"]:
        if not isinstance(record, dict) or not isinstance(record.get("phase"), str) or record["phase"] not in ORDER_PHASES:
            raise BackendError("BACKEND_STATE_INVALID")
        args = record.get("args")
        if not isinstance(args, dict) or args.get("action") != "start":
            raise BackendError("BACKEND_STATE_INVALID")
        validate_order("mcb_ae_order", args)
        key = (args["expectedSessionId"], args["requestId"])
        if key in restored:
            raise BackendError("BACKEND_STATE_INVALID")
        restored[key] = deepcopy(record)
    for record in state["failedActions"]:
        if (not isinstance(record, dict) or set(record) != {"tool", "arguments", "result"}
                or not isinstance(record["tool"], str) or not 1 <= len(record["tool"]) <= 128
                or not isinstance(record["arguments"], dict) or not isinstance(record["result"], dict)):
            raise BackendError("BACKEND_STATE_INVALID")
    for record in state["movementFailures"]:
        if (not isinstance(record, dict) or set(record) != {"tool", "errorCode", "expectedSessionId",
                "expectedWorld", "dimension", "playerId", "sampleId"}
                or any(not isinstance(record[key], str) or not record[key]
                       for key in ("tool", "errorCode", "expectedSessionId", "expectedWorld", "playerId"))
                or type(record["dimension"]) is not int or type(record["sampleId"]) is not int or record["sampleId"] < 0):
            raise BackendError("BACKEND_STATE_INVALID")
    return restored
