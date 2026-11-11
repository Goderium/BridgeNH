"""Compound observations and their authoritative acceptance evidence."""
from __future__ import annotations

from mcp import types
from ...bridge import BridgeError, request_context, response_context
from ...conveniences import capture, machine_watch
from ...workflows import _guard
from ...recipe_tasks import _RecipeTask
from ...interaction_samples import run_interaction_samples
from ...gui_acceptance import phantom_items_confirmed, wait_failure
from ..results import _result, screenshot_result
from ..control import _require_capability, _full_background
from ..reads import wait_for, recipe_pages, machine_capture_pages

async def handle_gui(adapter, name, arguments, started_at):
    if "waitFor" not in arguments:
        return await adapter.invoke_native_tool(name, arguments)
    async with adapter.workflow_lock:
        snapshot = await adapter.client.invoke("client.snapshot", {})
        session = snapshot.get("sessionId")
        world = snapshot.get("world", {}).get("worldFolder")
        remote = snapshot.get("world", {}).get("singleplayer") is False
        bridge = snapshot.get("serverBridge", {})
        if snapshot.get("profile") != "developer" or (snapshot.get("world", {}).get("singleplayer") is not True and
                not (remote and bridge.get("ready") is True and bridge.get("connectionId") and
                     bridge.get("server", {}).get("guiNativeWaitVersion") == 1 and bridge.get("server", {}).get("operatorReads") is True)):
            raise BridgeError("unsupported", "Native GUI waiting needs an owned developer world or an authenticated server advertising GUI synchronization.", outcome_unknown=False)
        _guard(snapshot, session, "developer", world, True)
        _RecipeTask.require_live_player(snapshot)
        context = {"version": 1, "expectedSessionId": session, "expectedProfile": "developer",
            "expectedWorld": world, "requireBackground": True}
        with request_context(context):
            data = await adapter.client.invoke("gui.inspect", arguments)
        evidence = response_context(data)
        if not isinstance(evidence, dict) or type(evidence.get("version")) is not int or evidence.get("version") != 1 or evidence.get("operation") != "gui.inspect" or evidence.get("phase") != "after" or not isinstance(evidence.get("snapshot"), dict):
            raise BridgeError("MALFORMED_RESPONSE", "GUI waiting requires its fresh authoritative AFTER context.", outcome_unknown=False)
        current = evidence["snapshot"]
        _guard(current, session, "developer", world, True)
        _RecipeTask.require_live_player(current)
        if current.get("world", {}).get("dimension") != snapshot.get("world", {}).get("dimension"):
            raise BridgeError("gui_context_changed", "GUI observation dimension changed.", outcome_unknown=False)
        if remote and (current.get("serverBridge", {}).get("ready") is not True or
                current.get("serverBridge", {}).get("connectionId") != bridge["connectionId"]):
            raise BridgeError("gui_context_changed", "GUI observation server connection changed.", outcome_unknown=False)
        if data.get("guiWait", {}).get("matched") is not True:
            return _result({"ok": False, "error": wait_failure(data), "data": data}, error=True)
        receipt, synchronization = data.get("guiWait", {}), data.get("containerSynchronization", {})
        if remote and (synchronization.get("connectionId") != bridge["connectionId"] or
                synchronization.get("source") != "client_and_remote_server_owner_threads"):
            raise BridgeError("MALFORMED_RESPONSE", "Remote GUI synchronization requires its exact server connection evidence.", outcome_unknown=False)
        if type(receipt.get("version")) is not int or receipt.get("version") != 1 or receipt.get("timedOut") is not False or receipt.get("source") != "normal_client_end_ticks" or synchronization.get("fullNativeSlotsEqual") is not True or not phantom_items_confirmed(synchronization) or synchronization.get("fullPhantomFluidSlotsEqual") is not True or synchronization.get("phantomRegistryComplete") is not True:
            raise BridgeError("MALFORMED_RESPONSE", "GUI success requires the complete native synchronization receipt.", outcome_unknown=False)
        return _result({"ok": True, "data": data})


async def handle_wait_for(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await wait_for(adapter.client, arguments)})


async def handle_recipe_query(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await recipe_pages(adapter.client, arguments)})


async def handle_machine_capture(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await machine_capture_pages(adapter.client, arguments)})


async def handle_interact_sample(adapter, name, arguments, started_at):
    data = await run_interaction_samples(adapter.client, arguments, adapter.workflow_lock,
        require_capability=_require_capability, full_background=_full_background)
    return _result({"ok": True, "data": data})


async def handle_ae_material_source(adapter, name, arguments, started_at):
    context = {"version": 1, "expectedSessionId": arguments["expectedSessionId"], "expectedProfile": "developer",
        "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
    with request_context(context):
        data = await adapter.client.call("ae.material_source", arguments)
    evidence = response_context(data)
    if not isinstance(evidence, dict) or evidence.get("phase") != "after" or not isinstance(evidence.get("snapshot"), dict):
        raise BridgeError("MALFORMED_RESPONSE", "AE material-source reads require their fresh authoritative AFTER guard.", outcome_unknown=False)
    _guard(evidence["snapshot"], context["expectedSessionId"], "developer", context["expectedWorld"], True)
    _RecipeTask.require_live_player(evidence["snapshot"])
    return _result({"ok": True, "data": data})


async def handle_capture(adapter, name, arguments, started_at):
    data, screenshot = await capture(adapter.client, arguments)
    image = None
    if screenshot is not None:
        try:
            rendered = screenshot_result(screenshot)
            image = next(content for content in rendered.content if isinstance(content, types.ImageContent))
            data["observations"]["screenshot"] = {"ok": True, "data": rendered.structuredContent["data"]}
        except BridgeError as error:
            data["errors"].append({"observation": "screenshot", **error.as_dict()})
            data["observations"]["screenshot"] = {"ok": False, "error": error.as_dict()}
            data["partial"] = True
    return _result({"ok": True, "data": data}, image=image)


async def handle_machine_watch(adapter, name, arguments, started_at):
    data = await machine_watch(adapter.client, arguments)
    if "error" in data:
        error = data.pop("error")
        return _result({"ok": False, "error": error, "data": data}, error=True)
    return _result({"ok": True, "data": data})
