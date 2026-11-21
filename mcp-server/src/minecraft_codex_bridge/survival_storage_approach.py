"""Bring a storage endpoint into normal reach without interacting with it."""

from .application.control import invoke_control
from .bridge import BridgeError, request_context
from .operation_policy import movement_arguments


async def close_storage_screen(client, order):
    snapshot = await client.invoke("client.snapshot", {})
    target = snapshot.get("lifecycleTarget") or {}
    if (snapshot.get("sessionId") != order["expectedSessionId"] or target.get("expectedWorld") != order["expectedWorld"]
            or (snapshot.get("world") or {}).get("dimension") != order["dimension"]
            or (snapshot.get("player") or {}).get("uuid") != order["identity"]["actor"]):
        raise BridgeError("ORDER_CONTEXT_CHANGED", "The active actor or world changed before closing the container.")
    if not snapshot.get("screen"):
        order.pop("storageScreenId", None)
        return
    current = snapshot.get("screenId")
    if not current or current != order.get("storageScreenId"):
        raise BridgeError("screen_in_use", "Another screen is open. Settle it before continuing the order.")
    guard = {"version": 1, "expectedSessionId": order["expectedSessionId"], "expectedWorld": order["expectedWorld"],
             "expectedDimension": order["dimension"], "expectedProfile": snapshot["profile"],
             "requireBackground": True, "requireIdleInput": True}
    with request_context(guard):
        await invoke_control(client, "gui.close", {"screenId": current})
    order.pop("storageScreenId", None)


async def approach_storage(client, order, endpoint):
    health = await client.invoke("bridge.health", {})
    if health.get("sessionId") != order["expectedSessionId"]:
        raise BridgeError("ORDER_CONTEXT_CHANGED", "The game session changed before storage approach.")
    point = {key: endpoint[key] for key in ("x", "y", "z")}
    dimension = order["dimension"]
    remote = order["identity"]["server"] is not None
    binding = {"dimension": dimension, "expectedSessionId": order["expectedSessionId"],
               "expectedWorld": order["identity"]["folder"]}
    if remote:
        from .remote_walk import require_binding
        connection = require_binding(binding, health)
        read = await client.invoke("server.observe", {"kind": "blocks", "positions": [point],
                                   "dimension": dimension, "expectedSessionId": binding["expectedSessionId"],
                                   "expectedConnectionId": connection})
        blocks = read.get("blocks", [])
        if (read.get("complete") is not True or read.get("source") != "remote_server_tick"
                or read.get("connectionId") != connection or read.get("sessionId") != binding["expectedSessionId"]
                or read.get("dimension") != dimension or len(blocks) != 1 or blocks[0].get("loaded") is not True):
            raise BridgeError("endpoint_unavailable", "The loaded destination block could not be identified.")
        block = blocks[0]
        binding["expectedConnectionId"] = connection
    else:
        block = await client.invoke("world.inspect", {**point, "dimension": dimension})
    if (any(block.get(key) != value for key, value in point.items()) or not block.get("registryName")
            or block["registryName"] == "minecraft:air" or type(block.get("metadata")) is not int):
        raise BridgeError("endpoint_unavailable", "No observed storage block at the requested position.")
    target = {**point, "targetIdentity": {key: block[key] for key in ("registryName", "metadata")}}
    if "side" in endpoint:
        target["allowedSides"] = [endpoint["side"]]
    nav = {**binding, "requireSurvivalActor": True, "goal": {"type": "interaction", "targets": [target]},
           "segmented": True, "timeout_ms": 15000}
    if remote:
        # invoke_control binds player.remote_walk to the authenticated connection.
        nav["strategy"] = "walking"
    else:
        nav = await movement_arguments(client, "mcb_navigate", nav)
    await close_storage_screen(client, order)
    try:
        result = await invoke_control(client, "player.navigate", nav)
    except BridgeError as error:
        if error.code.lower() in {"route_unavailable", "target_unreachable", "no_path"} and not error.details.get("outcome_unknown"):
            raise BridgeError("unreachable", "No authorized route reaches the storage endpoint.") from None
        raise
    if (result.get("arrivalConfirmed") is not True or result.get("status") != ("arrived" if remote else "completed")
            or result.get("sessionId") != binding["expectedSessionId"] or result.get("dimension") != dimension):
        raise BridgeError("arrival_unconfirmed", "Check the actual actor position before resuming this order.")
