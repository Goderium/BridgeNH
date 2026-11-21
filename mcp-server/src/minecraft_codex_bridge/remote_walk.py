"""Bind a standalone ground walk to the currently authenticated remote player."""
from math import hypot, isfinite

from .bridge import BridgeError


def _refuse(code, reason):
    raise BridgeError(code, reason, original_action_executed=False, outcome_unknown=False, automaticFallback=False)


def require_binding(request, health):
    bridge = health.get("serverBridge") or {}
    server = bridge.get("server") or {}
    policy = health.get("operationPolicy") or {}
    effective = policy.get("effective") or {}
    if (bridge.get("ready") is not True or type(server.get("groundWalkVersion")) is not int
            or server["groundWalkVersion"] != 1):
        _refuse("REMOTE_OPERATION_UNSUPPORTED", "Remote walking needs a ready operator connection advertising groundWalkVersion=1 on both ends.")
    if server.get("operatorReads") is not True:
        _refuse("PERMISSION_DENIED", "Ground observations require server operator level 2 for the connected player.")
    if (policy.get("available") is not True or type(effective.get("remoteGroundWalkVersion")) is not int
            or effective["remoteGroundWalkVersion"] != 1 or effective.get("machineRead") is not True):
        mode = (policy.get("selected") or {}).get("mode", "unknown")
        _refuse("POLICY_DENIED", f"The bound agent client's current server policy ({mode}) lacks authorized ground observations. Inspect mcb_policy status for this connection.")
    if effective.get("movementImplementation") != "walking":
        _refuse("REMOTE_MOVEMENT_UNSUPPORTED", "This executor requires a grounded actor and the effective walking strategy.")
    if (not isinstance(bridge.get("connectionId"), str) or not 1 <= len(bridge["connectionId"]) <= 128 or not health.get("sessionId") or health["sessionId"] != request.get("expectedSessionId")
            or type(bridge.get("dimension")) is not int or request.get("dimension") != bridge["dimension"]):
        _refuse("REMOTE_READ_CONTEXT_CHANGED", "The requested session or dimension differs from the current server connection.")
    world = server.get("world")
    if isinstance(world, dict) and request.get("expectedWorld") not in (world.get("worldName"), world.get("worldFolder")):
        _refuse("REMOTE_READ_CONTEXT_CHANGED", "For walking use serverBridge.server.world.worldName or worldFolder from the current handshake.")
    if ("expectedConnectionId" in request and request["expectedConnectionId"] != bridge["connectionId"]):
        _refuse("REMOTE_READ_CONTEXT_CHANGED", "The connection changed before remote movement takeover.")
    return bridge["connectionId"]


def arguments(request, health, *, approach=False):
    connection = require_binding(request, health)
    native = {key: request[key] for key in ("dimension", "expectedSessionId", "expectedWorld")}
    native.update(expectedConnectionId=connection, timeout_ms=request.get("timeout_ms", 30000))
    if request.get("requireSurvivalActor"):
        native["requireSurvivalActor"] = True
    if request.get("segmented"):
        native["segmented"] = True
    unsupported = ("waypoints", "lookAt", "arrivalTargets", "openGui")
    if any(key in request for key in unsupported) or request.get("finishFlying") or request.get("strategy", "walking") != "walking":
        _refuse("REMOTE_ARGUMENT_UNSUPPORTED", "This walk supports one position or one observed block. Explicit waypoints, final view constraints, flight and synchronized GUI acceptance require another executor.")
    goal = request.get("goal", {})
    if approach or goal.get("type") == "interaction":
        targets = request["targets"] if approach else goal["targets"]
        if len(targets) != 1:
            _refuse("REMOTE_ARGUMENT_UNSUPPORTED", "Remote approach accepts one observed block at a time.")
        target = targets[0]
        identity = target.get("targetIdentity") or {}
        if (set(target) - {"x", "y", "z", "allowedSides", "targetIdentity"}
                or set(identity) != {"metadata", "registryName"} or not 0 <= identity["metadata"] <= 15):
            _refuse("REMOTE_ARGUMENT_UNSUPPORTED", "Supply one observed whole block with registryName and block metadata (0..15), and optionally allowedSides.")
        native["block"] = {**identity, **{key: target[key] for key in ("x", "y", "z", "allowedSides") if key in target}}
        if approach:
            native.update(preview=not request.get("move", True), interact=request.get("interact", False))
            version = health["operationPolicy"]["effective"].get("clientGroundPlanVersion")
            if native["preview"] and type(version) is int and version == 1:
                native["clientPreview"] = True
    elif not goal or goal.get("type") == "point":
        native["destination"] = {axis: (goal or request)[axis] for axis in ("x", "y", "z")}
    else:
        _refuse("REMOTE_ARGUMENT_UNSUPPORTED", "The remote executor accepts an exact point or a single block interaction goal.")
    return native


def _point(value):
    return isinstance(value, dict) and all(type(value.get(axis)) in (int, float) and isfinite(value[axis])
                                           for axis in ("x", "y", "z"))


def _same_context(value, binding, source="remote_server_tick"):
    return (isinstance(value, dict) and value.get("source") == source
            and value.get("sessionId") == binding["expectedSessionId"]
            and value.get("connectionId") == binding["expectedConnectionId"]
            and type(value.get("dimension")) is int and value["dimension"] == binding["dimension"])


def _reached(feet, target):
    return abs(feet["y"] - target["y"]) <= .12 and hypot(feet["x"] - target["x"], feet["z"] - target["z"]) <= .18


def _use_receipt(click, block, arrival):
    if not isinstance(click, dict) or type(click.get("accepted")) is not bool or click.get("airUseFallback") is not False:
        return False
    target = click.get("blockTarget")
    return (isinstance(target, dict) and all(type(target.get(axis)) is int and target[axis] == block[axis] for axis in ("x", "y", "z"))
            and type(target.get("side")) is int and target["side"] in range(6) and target["side"] == arrival.get("side"))


def receipt(data, binding, *, details=False):
    preview = binding.get("preview", False)
    waypoints = data.get("waypoints")
    client_preview = preview and binding.get("clientPreview") is True
    source = "client_world_tick" if client_preview else "remote_server_tick"
    valid = (_same_context(data, binding, source) and isinstance(waypoints, list) and 1 <= len(waypoints) <= 96
             and all(_point(position) for position in waypoints)
             and (not binding.get("segmented") or data.get("segmented") is True))
    if client_preview:
        valid = (valid and data.get("plannerSource") == source and type(data.get("serverProbes")) is int
                 and data["serverProbes"] == 0)
    if preview:
        valid = valid and data.get("status") == "planned" and data.get("movementDispatched") is False
    else:
        arrival = data.get("serverArrival")
        client = data.get("clientArrival")
        valid = (valid and data.get("status") == "arrived" and data.get("arrivalConfirmed") is True
                 and data.get("serverConfirmed") is True and _same_context(arrival, binding)
                 and arrival.get("reached") is True and _point(arrival.get("feet"))
                 and _reached(arrival["feet"], waypoints[-1]) and _point(client) and _reached(client, waypoints[-1])
                 and hypot(*(client[axis] - arrival["feet"][axis] for axis in ("x", "y", "z"))) <= .10)
        if "destination" in binding:
            valid = valid and _reached(arrival["feet"], binding["destination"]) and _reached(client, binding["destination"])
        if "block" in binding:
            valid = (valid and arrival.get("blockVisible") is True and type(arrival.get("side")) is int
                     and arrival["side"] in binding["block"].get("allowedSides", range(6)))
        if binding.get("interact"):
            valid = valid and _use_receipt(data.get("interaction"), binding["block"], arrival)
    if not valid:
        raise BridgeError("REMOTE_WALK_RECEIPT_INVALID", "Inspect the current pose and interaction before further actions. The native walk receipt did not match its request.",
                          outcome_unknown=not preview)
    if details or preview:
        return data
    return {key: value for key, value in data.items() if key != "waypoints"} | {"waypointCount": len(waypoints)}
