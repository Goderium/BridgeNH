"""Move one conserved amount through the actor's reachable survival storage."""
from copy import deepcopy
import json
from uuid import uuid4
import anyio

from .bridge import BridgeError, request_context, response_context
from .catalog.common import integer, obj

ENDPOINT_SCHEMA = {
    **obj({"kind": {"enum": ["chest", "drawer", "me"]}, "x": integer(-30000000, 30000000),
           "y": integer(0, 255), "z": integer(-30000000, 30000000),
           "side": integer(0, 5), "drawer": integer(0, 3)}, ["kind", "x", "y", "z"]),
    "allOf": [{"if": {"properties": {"kind": {"const": "drawer"}}}, "then": {"required": ["drawer"]},
               "else": {"not": {"required": ["drawer"]}}},
              {"if": {"properties": {"kind": {"const": "me"}}}, "then": {"required": ["side"]}}]}


def _key(endpoint):
    return json.dumps(endpoint, sort_keys=True, separators=(",", ":"))


def _stop(reason, *, uncertain=False):
    return {"moved": 0, "blocked": True, "uncertain": uncertain, "reason": reason}


def _require(value, message):
    if not value:
        raise BridgeError("storage_evidence_missing", message, outcome_unknown=False)


async def _context(client, order):
    h = await client.invoke("bridge.health", {})
    identity = order["identity"]
    _require(h.get("sessionId") == order["expectedSessionId"] and h.get("profile") in ("player", "developer"),
             "The original client binding must still be active.")
    args = {"expectedSessionId": order["expectedSessionId"], "worldFolder": identity["folder"],
            "dimension": order["dimension"]}
    if identity["server"] is not None:
        server = h.get("serverBridge") or {}
        _require(server.get("ready") is True and server.get("connectionId"), "The bound server bridge must be ready.")
        args["expectedConnectionId"] = server["connectionId"]
    guard = {"version": 1, "expectedSessionId": args["expectedSessionId"], "expectedWorld": order["expectedWorld"],
             "expectedProfile": h["profile"], "expectedDimension": order["dimension"], "requireBackground": True}
    return args, guard


async def _call(client, op, args, guard):
    from .application.control import invoke_control
    write = op in ("survival.storage_open", "survival.storage_move")
    with request_context({**guard, "requireBackground": write, **({"requireIdleInput": True} if write else {})}):
        return await (invoke_control(client, op, args) if write else client.invoke(op, args))


async def _inspect(client, order, endpoint, direction):
    from .survival_storage_approach import approach_storage, close_storage_screen
    context, guard = await _context(client, order)
    args = {**context, "endpoint": endpoint, "item": order["item"], "direction": direction}
    result = await _call(client, "survival.storage_count", args, guard)
    if result.get("reason") == "unreachable":
        await approach_storage(client, order, endpoint)
        result = await _call(client, "survival.storage_count", args, guard)
    if result.get("reason") == "endpoint_not_open":
        await close_storage_screen(client, order)
        from .application.control import invoke_control
        original, empty = result.get("selectedSlot"), result.get("emptyHotbarSlot")
        select = result.get("held") is True
        if select:
            if type(empty) is not int or empty not in range(9) or type(original) is not int or original not in range(9):
                raise BridgeError("empty_hand_required", "Opening this storage needs one empty hotbar slot.")
            with request_context({**guard, "requireIdleInput": True}):
                await invoke_control(client, "player.hotbar", {"slot": empty})
        try:
            if select or result.get("windowClosed") is not True:
                for attempt in range(8):
                    ready = await _call(client, "survival.storage_count", args, guard)
                    if (ready.get("reason") == "endpoint_not_open" and ready.get("windowClosed") is True
                            and ready.get("held") is False and (not select or ready.get("selectedSlot") == empty)):
                        break
                    if attempt < 7:
                        await anyio.sleep(.1)
                else:
                    raise BridgeError("window_or_hand_unconfirmed", "Server has not confirmed the normal window and empty hand.")
            opened = await _call(client, "survival.storage_open", args, guard)
            if opened.get("reason") not in ("opened", "already_open"):
                raise BridgeError(opened.get("reason") or "endpoint_unavailable", "The normal block interaction did not open the selected storage.")
            # A server GUI-open acknowledgement can precede its client window by a tick.
            for attempt in range(8):
                result = await _call(client, "survival.storage_count", args, guard)
                if _screen(result):
                    break
                if attempt < 7:
                    await anyio.sleep(.1)
        finally:
            if select:
                with request_context({**guard, "requireIdleInput": True}):
                    await invoke_control(client, "player.hotbar", {"slot": original})
    if result.get("reason"):
        raise BridgeError(result["reason"], "Unable to observe the requested survival storage.", outcome_unknown=False)
    _require(result.get("source") == "server_survival_storage" and isinstance(result.get("storageKey"), str)
             and result.get("actorId") == order["identity"]["actor"] and isinstance(result.get("observationId"), str)
             and all(type(result.get(k)) is int and result[k] >= 0 for k in ("endpointCount", "playerCount")),
             "The server did not identify the actual storage, player and their counts.")
    order["storageScreenId"] = _screen(result)
    _require(order["storageScreenId"], "The authenticated server window did not synchronize to the client.")
    order.setdefault("storageKeys", {})[_key(endpoint)] = result["storageKey"]
    return args, guard, result


def _screen(result):
    context = response_context(result) or {}
    snapshot = context.get("snapshot") or {}
    if (context.get("phase") == "after" and snapshot.get("screen") and type(result.get("windowId")) is int
            and result.get("screenId") and snapshot.get("screenId") == result["screenId"]):
        return snapshot.get("screenId")
    return None


async def storage_count(client, order, endpoint):
    _, _, observed = await _inspect(client, order, endpoint, "deposit")
    return observed["endpointCount"]


def _confirmed(result, intent):
    if (result.get("source") != "server_survival_storage" or result.get("intentId") != intent["intentId"]
            or result.get("actorId") != intent["actorId"] or result.get("storageKey") != intent["storageKey"]
            or result.get("direction") != intent["direction"] or result.get("item") != intent["item"]):
        return _stop("reconcile_required", uncertain=True)
    if result.get("notSubmitted") is True and isinstance(result.get("reason"), str):
        refusal = _stop(result["reason"])
        if type(result.get("endpointBefore")) is int and result["endpointBefore"] >= 0:
            refusal["endpointAfter"] = result["endpointBefore"]
        if result["reason"] == "target_already_met":
            refusal["blocked"] = False
        return {k: v for k, v in refusal.items() if k != "uncertain"}
    if (result.get("endpointBefore") != intent["endpointCount"] or result.get("playerBefore") != intent["playerCount"]
            or result.get("conserved") is not True or result.get("cursorEmpty") is not True):
        return _stop("reconcile_required", uncertain=True)
    moved = result.get("moved")
    endpoint = result.get("endpointAfter")
    player = result.get("playerAfter")
    sign = 1 if intent["direction"] == "deposit" else -1
    if (type(moved) is not int or not 0 <= moved <= intent["limit"] or type(endpoint) is not int or type(player) is not int
            or endpoint != intent["endpointCount"] + sign * moved or player != intent["playerCount"] - sign * moved):
        return _stop("reconcile_required", uncertain=True)
    return {"moved": moved, "endpointAfter": endpoint, "reason": result["reason"], "blocked": moved == 0
            and result["reason"] != "target_already_met"}


async def storage_step(client, order, endpoint, direction, limit, checkpoint):
    _require(direction in ("withdraw", "deposit") and type(limit) is int and limit > 0, "Expected a positive transfer limit.")
    try:
        args, guard, observed = await _inspect(client, order, endpoint, direction)
    except BridgeError as error:
        return _stop(error.code)
    if direction == "withdraw" and observed["storageKey"] == order["storageKeys"].get(_key(order["destination"])):
        return _stop("source_is_destination")
    target = None
    if direction == "deposit" and order["mode"] == "replenish":
        target = order["quantity"]
        limit = min(limit, max(0, target - observed["endpointCount"]))
        if not limit:
            return {"moved": 0, "reason": "target_already_met", "endpointAfter": observed["endpointCount"]}
    source = observed["playerCount"] if direction == "deposit" else observed["endpointCount"]
    if not source:
        return _stop("source_empty" if direction == "withdraw" else "inventory_empty")
    limit = min(limit, source, 64)
    intent = {"intentId": str(uuid4()), "observationId": observed["observationId"], "endpoint": deepcopy(endpoint),
              "item": deepcopy(order["item"]), "direction": direction, "limit": limit,
              **{k: observed[k] for k in ("storageKey", "actorId", "endpointCount", "playerCount")}}
    checkpoint(intent)
    try:
        result = await _call(client, "survival.storage_move", {
            **args, "intentId": intent["intentId"], "observationId": intent["observationId"], "limit": limit,
            "actorId": intent["actorId"], "storageKey": intent["storageKey"],
            **({"target": str(target)} if target is not None else {})}, guard)
        return _confirmed(result, intent)
    except BridgeError as error:
        if error.details.get("original_action_executed") is False:
            return _stop(error.code)
        return _stop("reconcile_required", uncertain=True)


async def reconcile_storage(client, order):
    intent = order["pending"]
    if intent.get("actorId") != order["identity"]["actor"]:
        return _stop("identity_changed", uncertain=True)
    args, guard = await _context(client, order)
    try:
        result = await _call(client, "survival.storage_receipt", {**args, "intentId": intent["intentId"]}, guard)
        return _confirmed(result, intent)
    except BridgeError:
        return _stop("reconcile_required", uncertain=True)
