"""Settle an observed client-close/server-container lag before one navigation."""
import time
import anyio
from .bridge import BridgeError, request_context
from .workflows import _guard
from .recipe_tasks import _RecipeTask
from .knowledge import operation_phase, record_wait_sample


async def closed_container_before_navigation(client, arguments, health):
    if (health.get("profile") != "developer" or health.get("readiness", {}).get("localIntegratedServer") is not True
            or type(health.get("guiNativeWaitVersion")) is not int or health["guiNativeWaitVersion"] != 1):
        return None
    credentials = client.configuration.read()
    session, world = arguments.get("expectedSessionId"), arguments.get("expectedWorld")
    if not isinstance(session, str) or not session or not isinstance(world, str) or not world:
        return None  # Native request validation retains the legacy missing-binding refusal.
    started = time.monotonic()
    deadline = started + min(1.5, arguments.get("timeout_ms", 6000) / 1000)
    probes = 0

    async def snapshot():
        nonlocal probes
        with request_context(None), operation_phase("navigation_container_close"):
            value = await client.call("client.snapshot", {}, expected_credentials=credentials)
        probes += 1
        _guard(value, session, "developer", world, True)
        _RecipeTask.require_live_player(value)
        return value

    first = await snapshot()
    control = first.get("serverControl", {})
    server, local = control.get("openContainer", {}), control.get("clientContainer", {})
    if (first.get("screenOpen") is not False or control.get("available") is not True
            or local.get("isInventoryContainer") is not True or local.get("windowId") != 0
            or server.get("isInventoryContainer") is not False or type(server.get("windowId")) is not int or server["windowId"] <= 0):
        return None
    actor, dimension, old_window = control.get("playerId"), first.get("world", {}).get("dimension"), server["windowId"]
    if not isinstance(actor, str) or not actor or type(dimension) is not int or arguments.get("dimension", dimension) != dimension:
        raise BridgeError("navigation_container_context_unavailable", "The observed close lag has no matching actor/dimension binding.", original_action_executed=False, outcome_unknown=False)
    while True:
        if time.monotonic() >= deadline:
            raise BridgeError("navigation_container_close_pending", "The client GUI closed but its server container is still pending; no navigation was dispatched.", original_action_executed=False, outcome_unknown=False)
        record_wait_sample()
        await anyio.sleep(.1)
        current = await snapshot()
        owner = current.get("serverControl", {})
        local, server = owner.get("clientContainer", {}), owner.get("openContainer", {})
        if (current.get("screenOpen") is not False or current.get("world", {}).get("dimension") != dimension
                or owner.get("available") is not True or owner.get("playerId") != actor
                or local.get("isInventoryContainer") is not True or local.get("windowId") != 0):
            raise BridgeError("navigation_container_context_changed", "Actor/world/client-container changed during the read-only close wait; no navigation was dispatched.", original_action_executed=False, outcome_unknown=False)
        if server.get("isInventoryContainer") is True and type(server.get("windowId")) is int and server["windowId"] == 0:
            return {"version": 1, "source": "fresh_native_server_control_snapshots", "readOnly": True, "probes": probes,
                    "elapsedMillis": round((time.monotonic() - started) * 1000, 3), "serverContainerClosed": True,
                    "navigationDispatchedBeforeConfirmation": False, "replayAllowed": False}
        if server.get("isInventoryContainer") is not False or server.get("windowId") != old_window:
            raise BridgeError("navigation_container_context_changed", "A different server container appeared during the close wait; no navigation was dispatched.", original_action_executed=False, outcome_unknown=False)
