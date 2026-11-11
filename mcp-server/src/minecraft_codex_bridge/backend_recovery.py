"""Read-only reconciliation of ended movement, never permission to replay it."""
from __future__ import annotations

import math

from .backend_transport import BackendError
from .interaction_approach import _recovery_observation

MAX_MOVEMENT_FAILURES = 64


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def released_observation(value, expected):
    """Require the existing native recovery receipt, not a summary flag alone."""
    if not isinstance(value, dict):
        return False
    world, control = value.get("world"), value.get("serverControl")
    task, diagnostics, player = value.get("inputTask"), value.get("inputDiagnostics"), value.get("player")
    if not all(isinstance(row, dict) for row in (world, control, task, diagnostics, player)):
        return False
    return (
        value.get("available") is True and value.get("source") == "fresh_client_snapshot_after_failure"
        and value.get("automaticReplayAllowed") is False and value.get("profile") == "developer"
        and value.get("sessionId") == expected["expectedSessionId"]
        and type(world.get("dimension")) is int and world["dimension"] == expected["dimension"]
        and expected["expectedWorld"] in (world.get("worldName"), world.get("worldFolder"))
        and control.get("available") is True and control.get("source") == "integrated_server_tick"
        and control.get("sessionId") == expected["expectedSessionId"]
        and type(control.get("dimension")) is int and control["dimension"] == expected["dimension"]
        and expected["expectedWorld"] in (control.get("worldName"), control.get("worldFolder"))
        and isinstance(control.get("playerId"), str) and bool(control["playerId"])
        and type(control.get("sampleId")) is int and control["sampleId"] >= 0
        and number(control.get("ageMillis")) and 0 <= control["ageMillis"] <= 1000
        and number(player.get("health")) and player["health"] > 0
        and value.get("bridgeOwnedKeysReleased") is True and value.get("inputKeys") == []
        and type(value.get("inputTicksRemaining")) is int and value["inputTicksRemaining"] == 0
        and task.get("operation") == "player.task" and task.get("sessionId") == expected["expectedSessionId"]
        and task.get("status") == "idle" and task.get("owner") == "none"
        and type(task.get("ownedKeyCount")) is int and task["ownedKeyCount"] == 0
        and type(diagnostics.get("logicalOwnedKeyCount")) is int and diagnostics["logicalOwnedKeyCount"] == 0
    )


def movement_failure(name, arguments, result):
    """Only a typed approach timeout before any use can enter reconciliation.

    Standalone navigation's textual timeout diagnostics, transport failures,
    canceled RPCs and nested build/order receipts are deliberately insufficient.
    """
    if name != "mcb_approach" or not isinstance(result, dict) or result.get("ok") is not False or result.get("data") is not None:
        return None
    if arguments.get("strategy", "walking") not in ("walking", "ability_flight") or arguments.get("move", True) is not True:
        return None
    if (not all(isinstance(arguments.get(key), str) and arguments[key] for key in ("expectedSessionId", "expectedWorld"))
            or type(arguments.get("dimension")) is not int):
        return None
    error = result.get("error")
    if (not isinstance(error, dict) or error.get("code") not in ("timeout", "APPROACH_TIMEOUT")
            or error.get("outcome_unknown") is not True or error.get("replayAllowed") is not False):
        return None
    cause = error.get("cause")
    if cause is not None and (not isinstance(cause, dict) or cause.get("code") not in ("timeout", "APPROACH_TIMEOUT")):
        return None
    receipt = error.get("receipt")
    if (not isinstance(receipt, dict) or receipt.get("status") != "failed"
            or receipt.get("errorCode") != error["code"] or receipt.get("movementDispatched") is not True
            or receipt.get("interactionDispatched") is not False or receipt.get("plansPersisted") is not False
            or receipt.get("sessionId") != arguments["expectedSessionId"]
            or receipt.get("expectedWorld") != arguments["expectedWorld"]
            or type(receipt.get("dimension")) is not int or receipt["dimension"] != arguments["dimension"]
            or receipt.get("strategy") != arguments.get("strategy", "walking")):
        return None
    observation = receipt.get("recoveryObservation")
    if not released_observation(observation, arguments):
        return None
    control = observation["serverControl"]
    return {"tool": name, "errorCode": error["code"], **{key: arguments[key] for key in
        ("expectedSessionId", "expectedWorld", "dimension")}, "playerId": control["playerId"],
        "sampleId": control["sampleId"]}


def settled(observation):
    player, control = observation["player"], observation["serverControl"]
    pose = control.get("pose")
    if not isinstance(pose, dict):
        return False
    if any(not number(player.get(key)) or player[key] != 0 for key in ("nativeMoveForward", "nativeMoveStrafing")):
        return False
    for owner in (player, pose):
        if any(not number(owner.get(key)) or abs(owner[key]) > .01 for key in ("motionX", "motionZ")):
            return False
        if not number(owner.get("motionY")) or (owner.get("onGround") is not True and abs(owner["motionY"]) > .01):
            return False
    return all(number(player.get(key)) and number(pose.get(key)) and abs(player[key] - pose[key]) <= .18
        for key in ("x", "feetY", "z"))


async def reconcile_movements(client, records, binding):
    """One existing bounded snapshot read, no cancel/stop/move/GUI operation."""
    if not records:
        return 0
    world = binding.get("world")
    if binding.get("offline") is not False or binding.get("worldLoaded") is not True or not isinstance(world, dict):
        raise BackendError("BACKEND_MOVEMENT_BINDING_CHANGED", outcome_unknown=True)
    for record in records:
        if (binding.get("sessionId") != record["expectedSessionId"] or binding.get("playerId") != record["playerId"]
                or type(world.get("dimension")) is not int or world["dimension"] != record["dimension"]
                or record["expectedWorld"] not in (world.get("worldName"), world.get("worldFolder"))):
            raise BackendError("BACKEND_MOVEMENT_BINDING_CHANGED", outcome_unknown=True)
    current = await _recovery_observation(client, records[0])
    if (not all(released_observation(current, record)
            and current["serverControl"]["playerId"] == record["playerId"]
            and current["serverControl"]["sampleId"] > record["sampleId"] for record in records)
            or not settled(current)):
        raise BackendError("BACKEND_MOVEMENT_RECOVERY_UNCONFIRMED", outcome_unknown=True)
    return len(records)
