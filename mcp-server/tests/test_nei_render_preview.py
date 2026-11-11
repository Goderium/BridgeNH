"""Bounded captured text and native preview controls keep separate read/write contracts."""
from copy import deepcopy

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, SEQUENCE_ACTION_NAMES, TOOL_BY_NAME, TOOLS
from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN, CONTEXT, VIEW
from test_workflows import bridge, operations


DETAIL = {**SCREEN, "section": "detail"}
CONTROL = {**CONTEXT, "action": "set_channel", "channel": "glass", "value": 4}
RENDER = {**deepcopy(VIEW), "section": "rendered", "offset": 0, "limit": 1,
    "data": {"frameId": 915, "frameAgeMillis": 25, "observerActive": True,
        "coordinateSpace": "font_draw_arguments", "screenPositionStatus": "unmapped", "visibilityStatus": "draw_commands",
        "droppedTextDraws": 3, "boundedFramePartial": True, "captureBudgetNanos": 2000000,
        "captureDurationNanos": 1700000, "captureBudgetLimited": False, "observerErrors": 0,
        "textDrawsTotal": 18, "capturedCodepoints": 116, "filteredNonNeiDraws": 3, "unscopedDrawsAfterBudget": 0,
        "coverage": {"customFonts": "unknown", "textureText": "unknown", "complete": False}}}
RECEIPT = {"schemaVersion": 1, "screenId": SCREEN["screenId"], "action": "set_channel",
    "status": "applied", "source": "native_gui_slider", "nativeCallback": True,
    "channel": "glass", "previousValue": 1, "value": 4, "requiresViewRefresh": True,
    "previewAfter": {"blockCount": 47, "complete": False, "worldPositionsAvailable": False}}


@pytest.mark.parametrize("args", [
    {**SCREEN, "section": "rendered"}, {**CONTEXT, "section": "rendered", "offset": 0, "limit": 1},
    {**DETAIL, "target": "rendered_text"}, {**DETAIL, "target": "rendered_text", "limit": 64},
    {**CONTEXT, "section": "detail", "target": "rendered_text", "offset": 2147483647, "limit": 64},
    {**DETAIL, "target": "rendered_text_value", "itemIndex": 0},
    {**DETAIL, "target": "rendered_text_value", "itemIndex": 2147483647, "limit": 8192},
    {**CONTEXT, "section": "detail", "target": "rendered_text_value", "itemIndex": 17, "offset": 2048, "limit": 1},
])
async def test_rendered_requests_remain_one_read_without_input_capture_or_autodrain(bridge, args):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(RENDER)
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == RENDER
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args
    assert not state["background"]["enabled"]


@pytest.mark.parametrize("args", [
    *[{**CONTEXT, "section": "rendered", field: value} for field, value in
        (("offset", 1), ("limit", 2), ("limit", True), ("target", "rendered_text"),
         ("itemIndex", 0), ("handlerIndex", 0), ("includeTooltips", True), ("advanced", False),
         ("x", 0), ("y", 0), ("maxPages", 2), ("frameId", 1))],
    {**DETAIL, "target": "rendered_text", "limit": 65},
    {**DETAIL, "target": "rendered_text", "offset": 1},
    {**DETAIL, "target": "rendered_text_value"},
    {**DETAIL, "target": "rendered_text_value", "itemIndex": 0, "limit": 8193},
    {**DETAIL, "target": "rendered_text_value", "itemIndex": 0, "offset": 1},
    *[{**DETAIL, "target": "rendered_text_value", "itemIndex": value} for value in (-1, True, 2147483648, "0")],
    *[{**DETAIL, "target": target, **({"itemIndex": 0} if target == "rendered_text_value" else {}), field: value}
        for target in ("rendered_text", "rendered_text_value") for field, value in
        (("recipeIndex", 0), ("stackSource", "panel"), ("includeTooltips", False), ("advanced", True),
         ("tooltipSource", "handler"), ("lineIndex", 0), ("widgetSource", "recipe"), ("channel", "glass"))],
    {**DETAIL, "target": "rendered_text", "itemIndex": 0},
])
async def test_render_capture_rejects_unbounded_or_inapplicable_arguments_before_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


async def test_captured_text_continuation_preserves_frame_unicode_and_projection_provenance(bridge):
    adapter, state = bridge
    text = "§a频道等级: 雪🚀\n" * 100
    data = {**deepcopy(RENDER), "section": "detail", "offset": 2048, "limit": 2048,
        "nextOffset": 4096, "data": {"target": "rendered_text_value", "itemIndex": 17,
            "frameId": 915, "unit": "codepoints", "offsetUnit": "unicode_codepoints", "text": text,
            "coordinateSpace": "font_draw_arguments", "screenPositionStatus": "unmapped",
            "visibilityStatus": "draw_commands", "boundedFramePartial": True}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**CONTEXT, "section": "detail", "target": "rendered_text_value",
        "itemIndex": 17, "offset": 2048, "limit": 2048})
    assert result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "nei.view"]
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.text"]})
    assert compact["values"]["data.text"] == text
    for key, value in {"data.frameId": 915, "data.coordinateSpace": "font_draw_arguments",
        "data.offsetUnit": "unicode_codepoints", "contextToken": CONTEXT["contextToken"], "nextOffset": 4096}.items():
        assert compact["metadata"][key] == value
    assert compact["coverageSummary"]["partial"]


@pytest.mark.parametrize("status", ["readable", "partial", "unsupported", "not_present", "capture_pending"])
async def test_capture_status_frame_age_unknowns_and_budgets_are_never_upgraded(bridge, status):
    adapter, state = bridge
    data = {**deepcopy(RENDER), "status": status}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**SCREEN, "section": "rendered"})
    assert result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for field in ("frameId", "frameAgeMillis", "observerActive", "droppedTextDraws", "captureBudgetNanos", "captureDurationNanos", "captureBudgetLimited", "observerErrors",
        "textDrawsTotal", "capturedCodepoints", "filteredNonNeiDraws", "unscopedDrawsAfterBudget", "screenPositionStatus", "visibilityStatus"):
        assert compact["metadata"]["data." + field] == data["data"][field]
    assert compact["metadata"]["data.coverage.complete"] is False
    assert compact["metadata"]["limits.resultBytes"] == 131072
    assert compact["coverageSummary"]["partial"]


async def test_scene_coordinates_and_controller_relative_positions_are_not_world_positions(bridge):
    adapter, state = bridge
    entry = {"index": 0, "position": {"x": -2, "y": 65, "z": 3},
        "controllerRelativePosition": {"x": -2, "y": 1, "z": 3}, "coordinateSpace": "preview_scene",
        "registryName": "minecraft:stone", "metadata": 0}
    data = {**deepcopy(VIEW), "section": "detail", "data": {"target": "preview_blocks",
        "coordinateSpace": "preview_scene", "worldPositionsAvailable": False,
        "controllerAnchor": {"x": 0, "y": 64, "z": 0}, "entries": [entry]}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**DETAIL, "target": "preview_blocks"})
    assert result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    assert compact["values"]["data.entries"][0] == entry
    assert compact["metadata"]["data.worldPositionsAvailable"] is False
    assert compact["metadata"]["data.coordinateSpace"] == "preview_scene"


@pytest.mark.parametrize("args", [
    CONTROL, {**CONTROL, "value": 0}, {**CONTROL, "value": 2147483647, "channel": "x" * 128},
    *[{**CONTEXT, "action": "set_tier", "value": value} for value in (1, 2147483647)],
    *[{**CONTEXT, "action": "set_layer", "value": value} for value in (-1, 0, 2147483647)],
    *[{**CONTEXT, "action": "press_control", "controlIndex": value} for value in (0, 63)],
    {**CONTEXT, "action": "set_camera", "rotationYaw": -89.9, "rotationPitch": 0, "zoom": 3},
    {**CONTEXT, "action": "set_camera", "rotationYaw": 89.9, "rotationPitch": 360, "zoom": 999,
        "center": {"x": -33554432, "y": 64.5, "z": 33554431}},
    {**CONTEXT, "action": "select_block", "position": {"x": -33554432, "y": -2048, "z": 33554431}},
    {**CONTEXT, "action": "select_block", "position": {"x": 0, "y": 2047, "z": 0}},
    {**CONTEXT, "action": "clear_selection"},
])
async def test_native_preview_controls_use_one_typed_dispatch_after_takeover(bridge, args):
    adapter, state = bridge
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_nei_preview_control", args)
    assert not result.isError and result.structuredContent["data"] == RECEIPT
    assert operations(state) == ["bridge.health", "client.background", "nei.preview_control"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("args", [
    {}, CONTEXT, {"screenId": SCREEN["screenId"], "action": "set_layer", "value": 0},
    {**CONTROL, "screenId": ""}, {**CONTROL, "contextToken": ""}, {**CONTROL, "contextToken": "x" * 129},
    {**CONTROL, "action": "unknown"}, {**CONTROL, "channel": ""}, {**CONTROL, "channel": "x" * 129},
    *[{**CONTROL, "value": value} for value in (-1, 2147483648, True, 1.5, "1")],
    {**CONTEXT, "action": "set_channel", "value": 1}, {**CONTEXT, "action": "set_channel", "channel": "glass"},
    *[{**CONTEXT, "action": "set_tier", "value": value} for value in (-1, 0, 2147483648, True)],
    *[{**CONTEXT, "action": "set_layer", "value": value} for value in (-2, 2147483648, False)],
    *[{**CONTEXT, "action": "press_control", "controlIndex": value} for value in (-1, 64, True, "1")],
    *[{**CONTEXT, "action": action} for action in ("set_tier", "set_layer", "press_control")],
    *[{**CONTROL, field: value} for field, value in (("controlIndex", 0), ("x", 1), ("y", 1), ("screen_id", "screen"),
        ("context_token", "context"), ("offset", 0), ("tier", 1), ("maxPages", 1), ("allowFallback", True))],
    *[{**CONTEXT, "action": action, "value": 2, "channel": "glass"} for action in ("set_tier", "set_layer")],
    {**CONTEXT, "action": "press_control", "controlIndex": 0, "value": 1},
    {**CONTEXT, "action": "press_control", "controlIndex": 0, "channel": "glass"},
    *[{**CONTEXT, "action": "set_camera", "rotationYaw": 0, "rotationPitch": 0, "zoom": 10, field: value}
        for field, value in (("rotationYaw", -90), ("rotationYaw", 90), ("rotationYaw", True),
            ("rotationPitch", -1), ("rotationPitch", 361), ("zoom", 2.9), ("zoom", 999.1), ("zoom", True),
            ("zoom", float("nan")), ("rotationPitch", float("inf")),
            ("center", {"x": 0, "z": 0}), ("center", {"x": 0, "y": False, "z": 0}),
            ("center", {"x": float("nan"), "y": 64, "z": 0}),
            ("center", {"x": 33554432, "y": 64, "z": 0}), ("value", 1), ("position", {"x": 0, "y": 0, "z": 0}))],
    {**CONTEXT, "action": "set_camera", "rotationYaw": 0, "rotationPitch": 0},
    *[{**CONTEXT, "action": "select_block", "position": position} for position in (
        {"x": 0, "z": 0}, {"x": True, "y": 64, "z": 0}, {"x": 0.5, "y": 64, "z": 0},
        {"x": 0, "y": 2048, "z": 0}, {"x": 0, "y": -2049, "z": 0},
        {"x": 0, "y": 64, "z": 33554432}, {"x": 0, "y": 64, "z": 0, "dimension": 180})],
    {**CONTEXT, "action": "select_block"},
    *[{**CONTEXT, "action": "clear_selection", field: value} for field, value in (("value", 0),
        ("controlIndex", 1), ("position", {"x": 0, "y": 64, "z": 0}))],
])
async def test_control_branch_validation_prevents_any_network_or_takeover(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_preview_control", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("code", ["stale_screen", "stale_context", "unsupported", "invalid_args"])
async def test_native_control_refusal_has_no_retry_fallback_or_read_afterward(bridge, code):
    adapter, state = bridge
    state["action_error"] = {"code": code, "message": "Current native context refused."}
    result = await adapter.call_tool("mcb_nei_preview_control", CONTROL)
    assert result.isError and result.structuredContent["error"]["code"] == code
    assert operations(state) == ["bridge.health", "client.background", "nei.preview_control"]


async def test_unadvertised_control_does_not_attempt_legacy_gui_input(bridge):
    adapter, state = bridge
    state["caps"].remove("nei.preview_control")
    result = await adapter.call_tool("mcb_nei_preview_control", CONTROL)
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert "nei.preview_control" not in operations(state)
    assert not any(op in operations(state) for op in ("gui.click", "gui.key", "player.input"))


async def test_control_timeout_cancels_once_without_replaying_or_claiming_callback_completion(bridge):
    adapter, state = bridge
    state["slow"] = "nei.preview_control"
    result = await adapter.call_tool("mcb_nei_preview_control", CONTROL)
    assert result.isError
    assert operations(state).count("nei.preview_control") == 1 and operations(state).count("__cancel__") == 1
    assert result.structuredContent["error"]["code"] in ("BRIDGE_TIMEOUT", "UNKNOWN_OUTCOME")


async def test_one_in_memory_sequence_binds_preview_context_then_verifies_fresh_read(bridge):
    adapter, state = bridge
    first = {**deepcopy(VIEW), "section": "preview", "data": {"channels": {"entries": [{"channel": "glass", "value": 1, "maximum": 4}]}}}
    after = {**deepcopy(first), "contextToken": "fresh-after-native-callback",
        "data": {"channels": {"entries": [{"channel": "glass", "value": 4, "maximum": 4}]}}}
    replies = iter([first, after])
    state["data"]["nei.view"] = lambda: next(replies)
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "before", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "preview"}},
        {"id": "channel", "kind": "action", "tool": "mcb_nei_preview_control",
            "args": {"action": "set_channel", "value": 4}, "bind": {
                "screenId": {"step": "before", "path": "screenId"},
                "contextToken": {"step": "before", "path": "contextToken"},
                "channel": {"step": "before", "path": "data.channels.entries.0.channel"}}},
        {"id": "after", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "preview"}},
        {"id": "accepted", "kind": "assert", "source": "after",
            "condition": {"path": "data.channels.entries.0.value", "operator": "equals", "value": 4}},
    ]})
    assert not result.isError and result.structuredContent["data"]["completedSteps"] == 4
    dispatched = [call for call in state["calls"] if call["operation"] == "nei.preview_control"]
    assert len(dispatched) == 1 and dispatched[0]["args"] == CONTROL
    view_calls = [call for call in state["calls"] if call["operation"] == "nei.view"]
    assert len(view_calls) == 2 and "contextToken" not in view_calls[-1]["args"]
    compact_receipt = result.structuredContent["data"]["steps"][1]["observation"]["values"]
    assert compact_receipt["requiresViewRefresh"] is True and compact_receipt["previewAfter"]["complete"] is False
    assert "serverConfirmed" not in compact_receipt


async def test_rendered_observe_projects_bounded_frame_without_write_operations(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(RENDER)
    result = await adapter.call_tool("mcb_observe", {"views": [{"id": "text", "tool": "mcb_nei_view",
        "args": {**SCREEN, "section": "rendered"}, "fields": ["data.frameId"]}], "expectedSessionId": "session"})
    assert not result.isError
    assert "client.background" not in operations(state) and "nei.preview_control" not in operations(state)


@pytest.mark.parametrize("action,args,bindings,observed", [
    ("set_channel", {"channel": "glass"}, {"value": "data.value"}, {"value": 4}),
    ("set_tier", {}, {"value": "data.value"}, {"value": 4}),
    ("set_layer", {}, {"value": "data.value"}, {"value": -1}),
    ("set_camera", {}, {key: "data." + key for key in ("rotationYaw", "rotationPitch", "zoom", "center")},
        {"rotationYaw": -15.5, "rotationPitch": 270, "zoom": 10, "center": {"x": 0, "y": 64.5, "z": -3}}),
    ("select_block", {}, {"position": "data.position"}, {"position": {"x": -1, "y": 65, "z": 2}}),
])
async def test_native_control_typed_binding_can_reuse_live_values_and_scene_positions(bridge, action, args, bindings, observed):
    adapter, state = bridge
    state["data"]["nei.view"] = {**deepcopy(VIEW), "data": deepcopy(observed)}
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "before", "kind": "read", "tool": "mcb_nei_view", "args": SCREEN},
        {"id": "change", "kind": "action", "tool": "mcb_nei_preview_control", "args": {"action": action, **args},
            "bind": {"screenId": {"step": "before", "path": "screenId"},
                "contextToken": {"step": "before", "path": "contextToken"},
                **{key: {"step": "before", "path": path} for key, path in bindings.items()}}},
    ]})
    assert not result.isError
    call = next(call for call in state["calls"] if call["operation"] == "nei.preview_control")
    assert call["args"] == {**CONTEXT, "action": action, **args, **observed}


@pytest.mark.parametrize("action,key,value", [
    ("set_tier", "value", 0), ("set_channel", "value", -1), ("set_layer", "value", -2),
    ("select_block", "position", {"x": 0, "z": 0}),
    ("select_block", "position", {"x": 0, "y": True, "z": 0}),
])
async def test_invalid_live_bound_native_values_stop_after_observation_before_native_dispatch(bridge, action, key, value):
    adapter, state = bridge
    state["data"]["nei.view"] = {**deepcopy(VIEW), "data": {"selectedValue": value}}
    args = {**CONTEXT, "action": action, **({"channel": "glass"} if action == "set_channel" else {})}
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "before", "kind": "read", "tool": "mcb_nei_view", "args": SCREEN},
        {"id": "change", "kind": "action", "tool": "mcb_nei_preview_control", "args": args,
            "bind": {key: {"step": "before", "path": "data.selectedValue"}}},
    ]})
    assert result.isError and result.structuredContent["data"]["error"]["code"] == "INVALID_ARGUMENTS"
    assert "nei.preview_control" not in operations(state)
    assert operations(state).count("client.background") == 1


async def test_sdk_lists_read_and_action_annotations_and_calls_the_new_operation(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(RENDER)
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    server = make_server(adapter.client)
    async with create_connected_server_and_client_session(server) as session:
        listed = {spec.name: spec for spec in (await session.list_tools()).tools}
        assert set(listed) == {spec.name for spec in TOOLS}
        read, control = listed["mcb_nei_view"], listed["mcb_nei_preview_control"]
        assert read.annotations.readOnlyHint and read.annotations.idempotentHint
        assert not control.annotations.readOnlyHint and not control.annotations.destructiveHint
        assert not control.annotations.idempotentHint
        assert "rendered" in read.inputSchema["properties"]["section"]["enum"]
        assert control.inputSchema["required"] == ["screenId", "contextToken", "action"]
        result = await session.call_tool("mcb_nei_view", {**SCREEN, "section": "rendered"})
        assert not result.isError and result.structuredContent["data"] == RENDER
        result = await session.call_tool("mcb_nei_preview_control", CONTROL)
        assert not result.isError and result.structuredContent["data"] == RECEIPT
    assert operations(state).count("nei.preview_control") == 1


def test_control_is_not_a_read_wait_or_observation_but_remains_a_scriptable_sequence_action():
    spec = TOOL_BY_NAME["mcb_nei_preview_control"]
    assert spec.operation == "nei.preview_control" and spec.takeover and spec.idempotent is False
    assert not spec.read_only and spec.name in SEQUENCE_ACTION_NAMES and spec.name not in OBSERVATION_NAMES
    assert spec.operation not in READ_OPERATIONS
    assert spec.name not in TOOL_BY_NAME["mcb_wait_for"].schema["properties"]["tool"]["enum"]
    assert TOOL_BY_NAME["mcb_nei_view"].read_only and not TOOL_BY_NAME["mcb_nei_view"].takeover


def test_button_receipt_projection_keeps_native_callback_and_unverified_effect_separate():
    data = {"screenId": SCREEN["screenId"], "action": "press_control", "controlIndex": 2,
        "status": "callback_completed", "nativeCallback": True, "source": "native_button",
        "requiresViewRefresh": True, "currentScreenChanged": True, "effectStatus": "requires_readback",
        "callbackMillis": 0.75, "previewAfterSource": "callback_model",
        "previewAfter": {"blockCount": 47, "complete": False, "worldPositionsAvailable": False}}
    compact = project(data, {"kind": "action", "tool": "mcb_nei_preview_control"})
    assert compact["values"] == data
    assert compact["values"]["effectStatus"] == "requires_readback"
    assert "serverConfirmed" not in compact["values"]


@pytest.mark.parametrize("preserved", [True, False])
def test_native_preview_receipt_keeps_context_preservation_in_compact_and_explicit_projection(preserved):
    data = {**deepcopy(RECEIPT), "contextToken": CONTEXT["contextToken"], "contextPreserved": preserved,
        "requiresViewRefresh": not preserved, "status": "unchanged" if preserved else "applied"}
    compact = project(data, {"kind": "action", "tool": "mcb_nei_preview_control"})
    assert compact["values"]["contextPreserved"] is preserved
    assert compact["values"]["requiresViewRefresh"] is not preserved
    assert compact["values"]["contextToken"] == CONTEXT["contextToken"]
    requested = project(data, {"kind": "action", "tool": "mcb_nei_preview_control", "fields": ["status"]})
    assert requested["values"] == {"status": data["status"]}
    assert requested["metadata"]["contextPreserved"] is preserved
    assert requested["metadata"]["requiresViewRefresh"] is not preserved


@pytest.mark.parametrize("empty", [True, False])
async def test_render_capture_hook_diagnostics_survive_compact_projection_without_claiming_completeness(bridge, empty):
    adapter, state = bridge
    diagnostics = {"captureStatus": "empty" if empty else "captured", "emptyCapture": empty,
        "terminalHookCalls": 0 if empty else 45, "privateLineHookCalls": 0 if empty else 90,
        "shadowPassSkips": 0 if empty else 45, "terminalAncestorSkips": 0 if empty else 11,
        "emptyTextInputs": 0 if empty else 2, "recordingAttempts": 0 if empty else 45,
        "terminalHooksLifetime": 9007199254740993, "privateLineHooksLifetime": 9007199254740994}
    data = {**deepcopy(RENDER), "data": {**deepcopy(RENDER["data"]), **diagnostics}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**SCREEN, "section": "rendered"})
    assert not result.isError and result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert compact["values"] == {}
    for field, value in diagnostics.items():
        assert compact["metadata"]["data." + field] == value
        assert type(compact["metadata"]["data." + field]) is type(value)
    assert compact["metadata"]["contextToken"] == CONTEXT["contextToken"]
    assert compact["metadata"]["data.frameId"] == RENDER["data"]["frameId"]
    assert compact["metadata"]["data.captureBudgetNanos"] == 2000000
    assert compact["metadata"]["data.captureDurationNanos"] == 1700000
    assert compact["metadata"]["data.coverage.complete"] is False
    assert compact["coverageSummary"]["partial"] is True
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["background"]["enabled"] is False
