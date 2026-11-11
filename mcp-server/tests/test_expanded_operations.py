"""Structured extensions preserve live identity, bounded coverage and action outcomes."""

import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, TOOL_BY_NAME
from expanded_examples import ACTION_EXAMPLES, READ_EXAMPLES


@pytest.fixture
async def bridge():
    state = {"calls": [], "data": {}, "error": None, "slow": None, "background_error": None, "protected": False}

    async def respond(request):
        body = json.loads(request.content)
        operation = "__cancel__" if request.url.path == "/v1/cancel" else body["operation"]
        state["calls"].append({**body, "operation": operation})
        if operation == "__cancel__":
            data = {"status": "already_running"}
        elif operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "expanded-session", "profile": "player",
                    "operationPolicy": {"available": True, "effective": {"movementImplementation": "walking"}},
                    "capabilities": [tool.operation for tool in TOOL_BY_NAME.values() if tool.operation],
                    "background": {"enabled": state["protected"], "helperInstalled": state["protected"], "pauseOnLostFocus": not state["protected"]}}
        elif operation == "client.background":
            if state["background_error"]:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": state["background_error"], "message": "Takeover refused."}})
            state["protected"] = True
            data = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
        else:
            if state["slow"] == operation:
                await asyncio.sleep(10)
            if state["error"]:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": state["error"], "message": "Named operation refused."}})
            data = state["data"].get(operation, {"operation": operation, "args": body["args"]})
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "expanded-test-token"}),
                          timeout=0.04, transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def operations(state):
    return [call["operation"] for call in state["calls"]]


@pytest.mark.parametrize("name", READ_EXAMPLES)
async def test_expanded_read_preserves_partial_long_identity_without_takeover_or_fallback(bridge, name):
    adapter, state = bridge
    operation = TOOL_BY_NAME[name].operation
    data = {"source": "current-client", "items": [{"registryName": "gregtech:gt.blockmachines", "metadata": 123,
            "quantity": 9_223_372_036_854_775_807, "hasTag": True}], "coverage": {"unsupported": "explicit reason"},
            "offset": 0, "nextOffset": 17, "hasMore": True, "partial": True,
            "budget": {"actualElapsedMicros": 5001, "softBudgetExceeded": True}}
    state["data"][operation] = data
    result = await adapter.call_tool(name, READ_EXAMPLES[name])
    assert result.structuredContent["data"] == ({**data, "complete": False} if name == "mcb_waila" else data)
    assert bool(result.isError) is (name == "mcb_waila")
    assert operations(state) == ["bridge.health", operation]
    assert not state["protected"]
    assert TOOL_BY_NAME[name].read_only and not TOOL_BY_NAME[name].takeover
    assert operation in READ_OPERATIONS


@pytest.mark.parametrize("reason", ["failed_provider", "line_limit", "budget", "explicit_partial"])
async def test_waila_incomplete_text_is_not_top_level_success_and_preserves_evidence(bridge, reason):
    adapter, state = bridge
    data = {"sections": {"body": {"tooltip": ["harvestability"], "providerBudgetPartial": False}},
        "nbtSnapshotTruncated": True, "providerNbt": {"available": True, "complete": True},
        "providers": [{"class": "fixture.Provider", "available": True, "truncated": False}]}
    if reason == "failed_provider":
        data["providers"][0].update(available=False, errorDetails=[{"type": "BridgeException", "code": "waila_nbt_limit"}])
    elif reason == "line_limit":
        data["providers"][0]["truncated"] = True
    elif reason == "budget":
        data["sections"]["body"]["providerBudgetPartial"] = True
    else:
        data["complete"] = False
    state["data"]["waila.inspect"] = data
    result = await adapter.call_tool("mcb_waila", {})
    assert result.isError and result.structuredContent["ok"] is False
    assert result.structuredContent["error"]["code"] == "WAILA_PARTIAL"
    assert result.structuredContent["data"]["sections"] == data["sections"]
    assert result.structuredContent["data"]["providers"] == data["providers"]
    assert operations(state) == ["bridge.health", "waila.inspect"]


async def test_waila_preview_truncation_alone_does_not_fail_complete_provider_text(bridge):
    adapter, state = bridge
    data = {"complete": True, "partial": False, "nbtSnapshotTruncated": True,
        "providerNbt": {"available": True, "complete": True},
        "providers": [{"available": True, "truncated": False}],
        "sections": {"body": {"tooltip": ["8457765750 L", "actual body"], "providerBudgetPartial": False}}}
    state["data"]["waila.inspect"] = data
    result = await adapter.call_tool("mcb_waila", {"includeNbt": True})
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "waila.inspect"]


@pytest.mark.parametrize("name", ACTION_EXAMPLES)
async def test_expanded_named_action_acquires_takeover_and_is_never_silently_retried(bridge, name):
    adapter, state = bridge
    operation = TOOL_BY_NAME[name].operation
    state["error"] = "stale_target"
    result = await adapter.call_tool(name, ACTION_EXAMPLES[name])
    assert result.isError and result.structuredContent["error"]["code"] == "stale_target"
    assert operations(state) == ["bridge.health", "client.background", operation]
    assert not TOOL_BY_NAME[name].read_only and TOOL_BY_NAME[name].takeover
    assert name not in OBSERVATION_NAMES


@pytest.mark.parametrize("name", ACTION_EXAMPLES)
async def test_expanded_named_action_does_not_dispatch_after_failed_takeover(bridge, name):
    adapter, state = bridge
    state["background_error"] = "mouse_grabbed"
    result = await adapter.call_tool(name, ACTION_EXAMPLES[name])
    assert result.isError and result.structuredContent["error"]["original_action_executed"] is False
    assert operations(state) == ["bridge.health", "client.background"]


@pytest.mark.parametrize("name", list(READ_EXAMPLES) + list(ACTION_EXAMPLES))
async def test_expanded_operation_timeout_cancels_original_request_once(bridge, name):
    adapter, state = bridge
    state["protected"] = True
    state["slow"] = TOOL_BY_NAME[name].operation
    result = await adapter.call_tool(name, (READ_EXAMPLES | ACTION_EXAMPLES)[name])
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == "BRIDGE_TIMEOUT"
    assert error["outcome_unknown"] is (name in ACTION_EXAMPLES)
    assert operations(state) == ["bridge.health", state["slow"], "__cancel__"]
    assert state["calls"][1]["requestId"] == state["calls"][2]["requestId"] == error["request_id"]


@pytest.mark.parametrize("name,args", [
    ("mcb_entities", {"x": -30_000_000, "y": 255, "z": 30_000_000, "dimension": -1, "radiusChunks": 8, "verticalRadiusChunks": 1, "offset": 100_000, "limit": 128}),
    ("mcb_spatial", {"mode": "positions", "positions": [{"x": 0, "y": 64, "z": 0}], "width": 4, "height": 8, "offset": 127, "limit": 128}),
    ("mcb_spatial", {"mode": "raycast", "from": {"x": 0, "y": -4096, "z": 0}, "to": {"x": 0, "y": -4095, "z": 0}, "includeEntities": False}),
    ("mcb_bindings", {"query": "NEI", "offset": 100_000, "limit": 128}),
    ("mcb_state_diff", {"sections": ["player", "messages"], "limit": 1}),
    ("mcb_state_diff", {"baselineId": "baseline-1", "offset": 10, "limit": 128}),
    ("mcb_ae", {"screenId": "screen", "scope": "interfaces", "offset": 100_000, "limit": 128}),
    ("mcb_machine_inspect", {"x": 0, "y": 64, "z": 0, "dimension": -1, "componentOffset": 100_000, "componentLimit": 128}),
    ("mcb_waila", {"includeNbt": True, "advanced": True}),
])
async def test_expanded_read_selectors_forward_only_explicit_arguments(bridge, name, args):
    adapter, state = bridge
    result = await adapter.call_tool(name, args)
    assert not result.isError
    assert operations(state) == ["bridge.health", TOOL_BY_NAME[name].operation]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("name,args", [
    ("mcb_nei_open", {"screenId": "", "mode": "usage", "source": "registry", "registryName": "minecraft:stone", "metadata": 32767}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "crafting", "source": "container", "slot": 2_147_483_647}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "crafting", "source": "recipe", "recipeIndex": 42, "group": "other", "position": 63, "alternative": 31}),
    *[("mcb_nei_navigate", {"screenId": "screen", "action": action, "index": 100_000}) for action in ("handler", "page")],
    *[("mcb_gui_widget_action", {"screenId": "screen", "widgetId": "widget", "action": action, **fields}) for action, fields in [
        ("set_text", {"text": "", "commit": False}), ("set_value", {"value": -0.25}), ("set_selected", {"selected": 2_147_483_647}), ("focus", {"focused": False}),
    ]],
    *[("mcb_ae_action", {"screenId": "screen", "action": action, **fields}) for action, fields in [
        ("terminal_setting", {"setting": "VIEW_MODE", "value": "ALL"}),
        ("terminal_type_filter", {"type": "fluid", "enabled": False}),
        ("resource_take", {"resourceId": "resource", "mode": "inventory"}), ("resource_deposit", {"mode": "single"}),
        *[("resource_container", {"resourceId": "resource", "mode": mode}) for mode in ("fill_single", "fill_all", "drain_single", "drain_all")],
        ("craft_open", {"resourceId": "resource"}),
        ("craft_request", {"amount": "9223372036854775807", "mode": "STANDARD", "lite": False, "autoStart": False, "follow": True}),
        ("pattern_set", {"side": "input", "slot": 1023, "resourceId": "resource", "amount": "9223372036854775807"}),
        ("pattern_set", {"side": "output", "slot": 1, "playerSlot": 35, "amount": "1"}),
        ("pattern_set", {"side": "input", "slot": 0, "clear": True}),
        ("pattern_mode", {"field": "inverted", "value": True}), ("pattern_multiply", {"factor": -8}), ("pattern_page", {"page": 127}),
        ("pattern_encode", {"move": True, "wholeStack": True}), ("pattern_clear", {}), ("preview_start", {"follow": True}),
        ("cpu_select", {"serial": -1}), ("job_cancel", {"expectedCpuSerial": 0}), ("job_pause", {"expectedCpuSerial": 2_147_483_647, "paused": True}),
    ]],
])
async def test_all_named_actions_are_callable_without_coordinate_or_os_input(bridge, name, args):
    adapter, state = bridge
    result = await adapter.call_tool(name, args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", TOOL_BY_NAME[name].operation]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("name,args", [
    ("mcb_entities", {"x": 1}), ("mcb_entities", {"radiusChunks": 0}), ("mcb_entities", {"verticalRadiusChunks": 9}),
    ("mcb_bindings", {"limit": 129}), ("mcb_waila", {"includeNbt": 1}),
    ("mcb_machine_inspect", {"x": 0, "y": 64, "z": 0, "componentLimit": 129}),
    ("mcb_spatial", {}), ("mcb_spatial", {"mode": "positions", "positions": []}),
    ("mcb_spatial", {"mode": "positions", "positions": [{"x": 0, "y": 64, "z": 0}], "includeEntities": True}),
    ("mcb_spatial", {"mode": "raycast", "from": {"x": 0, "y": 64, "z": 0}}),
    ("mcb_spatial", {"mode": "raycast", "from": {"x": 0, "y": 64, "z": 0}, "to": {"x": 1, "y": 64, "z": 0}, "distance": 1}),
    ("mcb_spatial", {"mode": "raycast", "width": 1}), ("mcb_spatial", {"mode": "raycast", "distance": 129}),
    ("mcb_state_diff", {"sections": []}), ("mcb_state_diff", {"sections": ["gui", "gui"]}),
    ("mcb_state_diff", {"baselineId": "baseline", "sections": ["gui"]}), ("mcb_state_diff", {"baselineId": ""}),
    ("mcb_gui_widgets", {}), ("mcb_ae", {"screenId": "screen", "scope": "network"}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "usage", "source": "registry"}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "usage", "source": "inventory", "slot": 256}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "usage", "source": "container", "slot": 0, "registryName": "minecraft:stone"}),
    ("mcb_nei_open", {"screenId": "screen", "mode": "usage", "source": "recipe", "recipeIndex": 42, "alternative": 32}),
    ("mcb_nei_navigate", {"screenId": "screen", "action": "back", "index": 0}),
    ("mcb_nei_navigate", {"screenId": "screen", "action": "page"}),
    ("mcb_gui_widget_action", {"screenId": "screen", "widgetId": "widget", "action": "activate", "value": 1}),
    ("mcb_gui_widget_action", {"screenId": "screen", "widgetId": "widget", "action": "set_text", "text": "x", "commit": 1}),
    ("mcb_gui_widget_action", {"screenId": "screen", "widgetId": "widget", "action": "set_selected", "selected": True}),
    ("mcb_gui_widget_action", {"screenId": "screen", "widgetId": "widget", "action": "set_value", "value": float("nan")}),
    *[("mcb_ae_action", {"screenId": "screen", "action": "craft_request", "amount": value}) for value in [
        1, "0", "-1", "01", "1.0", "1e3", "1\n", " 1", "9223372036854775808", "9999999999999999999",
    ]],
    ("mcb_ae_action", {"screenId": "screen", "action": "resource_take", "resourceId": "resource", "mode": "all"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_set", "side": "input", "slot": 0, "clear": True, "amount": "1"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_set", "side": "input", "slot": 0, "resourceId": "resource", "playerSlot": 1, "amount": "1"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_set", "side": "input", "slot": 0, "resourceId": "resource"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_encode", "wholeStack": True}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_encode", "wholeStack": True, "move": False}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_mode", "field": "inverted", "value": 1}),
    ("mcb_ae_action", {"screenId": "screen", "action": "terminal_search", "text": "x", "field": "crafting"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "terminal_setting", "setting": "VIEW_MODE", "value": True}),
    ("mcb_ae_action", {"screenId": "screen", "action": "terminal_type_filter", "type": "fluid", "enabled": 1}),
    ("mcb_ae_action", {"screenId": "screen", "action": "terminal_type_filter", "type": "fluid"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "resource_container", "resourceId": "resource"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "resource_container", "resourceId": "resource", "mode": "single"}),
    ("mcb_ae_action", {"screenId": "screen", "action": "pattern_clear", "resourceId": "resource"}),
])
async def test_cross_action_fields_and_invalid_selectors_are_rejected_before_http(bridge, name, args):
    adapter, state = bridge
    result = await adapter.call_tool(name, args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


async def test_state_diff_baseline_nodes_are_explicit_and_not_pollable(bridge):
    adapter, state = bridge
    state["data"]["state.diff"] = {"baselineId": "baseline", "created": False, "baselineNodeCount": 1200,
        "changes": [], "offset": 0, "nextOffset": 512, "hasMore": True, "nonAtomic": True}
    result = await adapter.call_tool("mcb_state_diff", {"baselineId": "baseline"})
    assert not result.isError and result.structuredContent["data"]["nextOffset"] == 512
    assert operations(state) == ["bridge.health", "state.diff"]
    invalid = await adapter.call_tool("mcb_wait_for", {"tool": "mcb_state_diff", "condition": {"path": "changes", "operator": "exists"}})
    assert invalid.isError and invalid.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert "mcb_state_diff" not in OBSERVATION_NAMES
    assert TOOL_BY_NAME["mcb_state_diff"].idempotent is False


async def test_ae_action_reuses_live_resource_id_despite_truncated_nbt_and_long_amount(bridge):
    adapter, state = bridge
    entry = {"resourceId": "canonical-live-resource", "type": "fluid", "amount": "9223372036854775807",
             "requestable": "9007199254740993", "identityNbt": "bounded preview", "identityTruncated": True,
             "item": {"registryName": "ae2fc:fluid_display", "metadata": 0}}
    state["data"]["ae.inspect"] = {"screenId": "mock-screen", "entries": [entry], "nextOffset": 9, "hasMore": True}
    observed = await adapter.call_tool("mcb_ae", {"screenId": "mock-screen"})
    actual = observed.structuredContent["data"]["entries"][0]
    assert actual == entry and isinstance(actual["amount"], str)
    result = await adapter.call_tool("mcb_ae_action", {"screenId": "mock-screen", "action": "craft_open", "resourceId": actual["resourceId"]})
    assert not result.isError
    assert operations(state) == ["bridge.health", "ae.inspect", "bridge.health", "client.background", "ae.action"]
    assert state["calls"][-1]["args"] == {"screenId": "mock-screen", "action": "craft_open", "resourceId": "canonical-live-resource"}


async def test_nei_raw_and_display_recipe_context_are_returned_without_machine_recalculation(bridge):
    adapter, state = bridge
    gt = {"available": True, "rawEUt": 2048, "rawDurationTicks": 400, "chanceDenominator": 10000,
          "items": {"outputs": {"entries": [{"index": 0, "stack": {"registryName": "minecraft:stone", "count": 0}, "chance": 1000}], "total": 1, "truncated": False}},
          "fluids": {"inputs": {"entries": [], "total": 2, "truncated": True}},
          "metadata": [{"keyClass": "TierKey", "identifier": "tier", "valueClass": "Integer", "value": 3}],
          "displayOverclock": {"available": True, "eut": 8192, "durationTicks": 200, "source": "NEI display context, not actual machine"},
          "softBudgetPartial": True, "byteLimited": False}
    data = {"recipes": [{"recipeIndex": 42, "gregTech": gt}], "navigation": {"handlerIndex": 1, "pageIndex": 2,
            "handlers": [{"index": 1, "id": "current", "recipeCount": 500}], "nextOffset": 3, "hasMore": True},
            "nextOffset": 1, "hasMore": True, "nonAtomic": True}
    state["data"]["nei.inspect"] = data
    result = await adapter.call_tool("mcb_nei", {"screenId": "mock-screen"})
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "nei.inspect"]
