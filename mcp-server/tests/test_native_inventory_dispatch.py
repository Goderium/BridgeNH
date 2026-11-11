"""Survival native clicks retain dispatch/unknown even before a workflow returns."""
import json

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.workflows import run_workflow


@pytest.mark.parametrize("failure", [None, "disconnect", "cancel"])
@pytest.mark.parametrize("window,slot,matches", [(0, 36, True), (0, 9, True), (1, 36, False), (0, 37, False)])
async def test_native_slot_dispatch_and_uncertainty_bind_exact_window_and_actual_slots(failure, window, slot, matches):
    calls = []
    async def respond(request):
        body = json.loads(request.content)
        if request.url.path == "/v1/cancel":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"status": "already_running"}})
        assert body["operation"] == "gui.slot_click"
        calls.append(body)
        if failure == "disconnect": raise httpx.ReadError("Unknown dispatched native pickup", request=request)
        if failure == "cancel": await anyio.sleep_forever()
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"status": "submitted"}})
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "dispatch-fixture"})
    client = BridgeClient(config, transport=httpx.MockTransport(respond))
    try:
        task = _RecipeTask(client, {"actualSurvival": True}, run_workflow)
        record = {"nativeWindowId": 0, "sourceNativeContainerSlotId": 36, "destinationNativeContainerSlotId": 9,
            "movementDispatched": False, "movementConfirmed": False}
        task.current_inventory_move = record
        args = {"windowId": window, "slot": slot, "mode": 0, "button": 0}
        if failure == "disconnect":
            with pytest.raises(BridgeError): await task.tracked_client.call("gui.slot_click", args)
        elif failure == "cancel":
            with pytest.raises(TimeoutError):
                with anyio.fail_after(.05): await task.tracked_client.call("gui.slot_click", args)
        else:
            await task.tracked_client.call("gui.slot_click", args)
        assert len(calls) == 1 and record["movementConfirmed"] is False
        assert record["movementDispatched"] is matches
        assert (record.get("outcomeUnknown") is True) is (matches and failure is not None)
        if matches: assert record["lastRequestId"] == calls[0]["requestId"]
    finally:
        await client.aclose()
