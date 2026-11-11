"""Explicit bounded natural-frame capture stays separate from passive paged reads."""
from copy import deepcopy

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import TOOLS
from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN, CONTEXT, VIEW
from test_workflows import bridge, operations


TARGETS = ("preview_native_model", "preview_ctm_context", "preview_render_state", "preview_render_geometry")
POSITION = {"x": -2, "y": 65, "z": 1}
ARM = {**CONTEXT, "action": "preview_render_capture", "previewBlockSource": "position", "previewPosition": POSITION}
RECEIPT = {**CONTEXT, "action": "preview_render_capture", "status": "pending", "captureId": "capture-7",
    "captureStatus": "pending", "ownerPosition": POSITION, "pixelDataStored": False, "graphicsComplete": False}
DIAGNOSTICS = {"captureId": "capture-7", "captureStatus": "captured", "naturalFramesObserved": 1,
    "ownerPosition": POSITION, "position": POSITION, "observerNanos": 3700000, "maximumObserverNanos": 4000000,
    "maximumVertices": 2048, "maximumEntries": 256, "captureLimited": True,
    "pixelDataStored": False, "graphicsComplete": False, "source": "native_natural_frame",
    "gaps": ["submitted_vertices_are_not_final_pixels", "unobserved_shader_geometry"]}


@pytest.mark.parametrize("target", TARGETS)
@pytest.mark.parametrize("selectors", [{}, {"previewBlockSource": "selected"}, {"previewBlockSource": "hover"},
    {"previewBlockSource": "position", "previewPosition": POSITION},
    {"previewBlockSource": "position", "previewPosition": {"x": -33554432, "y": -2048, "z": 33554431}}])
async def test_each_natural_detail_pages_one_passive_read_without_arm_or_takeover(bridge, target, selectors):
    adapter, state = bridge
    data = {**deepcopy(VIEW), "section": "detail", "data": {**deepcopy(DIAGNOSTICS), "target": target,
        "entries": [{"index": 16, "nativeDescriptor": "observed_value"}]}}
    state["data"]["nei.view"] = data
    args = {**CONTEXT, "section": "detail", "target": target, "offset": 16, "limit": 64, **selectors}
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args and state["background"]["enabled"] is False


@pytest.mark.parametrize("target", TARGETS)
@pytest.mark.parametrize("invalid", [
    {"limit": 65}, {"limit": True}, {"offset": 1}, {"previewBlockSource": "unknown"},
    {"previewBlockSource": "position"}, {"previewPosition": POSITION},
    {"previewBlockSource": "hover", "previewPosition": POSITION},
    {"previewBlockSource": "position", "previewPosition": {"x": 0, "z": 0}},
    {"previewBlockSource": "position", "previewPosition": {"x": 0, "y": True, "z": 0}},
    {"textureSide": 0}, {"advanced": False}, {"includeTooltips": False}, {"modifiers": {"shift": False}},
    {"captureId": "capture-7"}, {"groupIndex": 0}, {"recipeIndex": 0}, {"itemIndex": 0},
])
async def test_render_detail_cross_selectors_and_unbounded_pages_fail_before_http(bridge, target, invalid):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", {**SCREEN, "section": "detail", "target": target, **invalid})
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("selectors", [{}, {"previewBlockSource": "selected"}, {"previewBlockSource": "hover"},
    {"previewBlockSource": "position", "previewPosition": POSITION}])
async def test_capture_arm_is_exact_guarded_existing_control_and_pending_is_not_completion(bridge, selectors):
    adapter, state = bridge
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    args = {**CONTEXT, "action": "preview_render_capture", **selectors}
    result = await adapter.call_tool("mcb_nei_preview_control", args)
    assert not result.isError and result.structuredContent["data"] == RECEIPT
    assert operations(state) == ["bridge.health", "client.background", "nei.preview_control"]
    assert state["calls"][-1]["args"] == args
    compact = project(RECEIPT, {"kind": "action", "tool": "mcb_nei_preview_control"})
    assert compact["values"]["captureStatus"] == "pending" and compact["values"]["captureId"] == "capture-7"
    assert compact["values"]["ownerPosition"] == POSITION
    assert compact["values"]["pixelDataStored"] is False and compact["values"]["graphicsComplete"] is False
    assert "frameId" not in compact["values"] and "serverConfirmed" not in compact["values"]


@pytest.mark.parametrize("invalid", [
    {"contextToken": ""}, {"previewBlockSource": "position", "previewPosition": None},
    {"previewBlockSource": "position", "previewPosition": {"x": 0, "z": 0}},
    {"previewBlockSource": "position", "previewPosition": {"x": False, "y": 64, "z": 0}},
    {"previewBlockSource": "position", "previewPosition": {"x": 0, "y": 2048, "z": 0}},
    {"previewBlockSource": "hover", "previewPosition": POSITION}, {"previewPosition": POSITION},
    {"previewBlockSource": "unknown"}, {"channel": "glass"}, {"value": 4}, {"controlIndex": 0},
    {"position": POSITION}, {"rotationYaw": 0}, {"textureSide": 0}, {"limit": 1},
    {"captureId": "capture-7"}, {"forceDraw": True}, {"rendererClass": "unexpected"},
])
async def test_capture_arm_refuses_cross_actions_and_renderer_requests_before_http(bridge, invalid):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_preview_control", {**CONTEXT, "action": "preview_render_capture", **invalid})
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("code", ["stale_screen", "stale_context", "unsupported", "invalid_args"])
async def test_native_capture_refusal_never_falls_back_to_draw_script_or_screenshot(bridge, code):
    adapter, state = bridge
    state["action_error"] = {"code": code, "message": "Current native capture refused."}
    result = await adapter.call_tool("mcb_nei_preview_control", ARM)
    assert result.isError and result.structuredContent["error"]["code"] == code
    assert operations(state) == ["bridge.health", "client.background", "nei.preview_control"]


@pytest.mark.parametrize("status", ["pending", "unavailable", "captured", "partial"])
def test_empty_projection_retains_native_snapshot_limits_gaps_and_preview_owner(status):
    data = {**deepcopy(VIEW), "section": "detail", "data": {**deepcopy(DIAGNOSTICS), "captureStatus": status,
        "target": "preview_render_geometry", "entries": [{"vertexCount": 2048}]}}
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert compact["values"] == {}
    for key, value in DIAGNOSTICS.items():
        assert compact["metadata"]["data." + key] == (status if key == "captureStatus" else value)
    assert compact["metadata"]["contextToken"] == CONTEXT["contextToken"]
    assert compact["metadata"]["nextOffset"] == 32 and compact["metadata"]["data.graphicsComplete"] is False


@pytest.mark.parametrize("position", [{"x": 0, "z": 0}, {"x": True, "y": 0, "z": 0},
    {"x": 0, "y": 0, "z": 0, "unbounded": list(range(10000))}])
def test_malformed_or_large_owner_metadata_is_omitted_instead_of_inferred(position):
    data = {"data": {"ownerPosition": position, "graphicsComplete": False}}
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert "data.ownerPosition" not in compact["metadata"]
    assert compact["coverageSummary"]["metadataOmitted"] is True
    assert compact["metadata"]["data.graphicsComplete"] is False


async def test_sequence_can_bind_observed_scene_position_then_arm_and_read_real_snapshot(bridge):
    adapter, state = bridge
    replies = iter([{**deepcopy(VIEW), "section": "preview", "data": {"selectedPosition": POSITION}},
        {**deepcopy(VIEW), "section": "detail", "data": {**deepcopy(DIAGNOSTICS), "target": "preview_render_geometry", "entries": []}}])
    state["data"]["nei.view"] = lambda: next(replies)
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "before", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "preview"}},
        {"id": "arm", "kind": "action", "tool": "mcb_nei_preview_control",
            "args": {"action": "preview_render_capture", "previewBlockSource": "position"},
            "bind": {"screenId": {"step": "before", "path": "screenId"},
                "contextToken": {"step": "before", "path": "contextToken"},
                "previewPosition": {"step": "before", "path": "data.selectedPosition"}}},
        {"id": "after", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "detail",
            "target": "preview_render_geometry", "previewBlockSource": "position", "previewPosition": POSITION}},
    ]})
    assert not result.isError and result.structuredContent["data"]["completedSteps"] == 3
    calls = [call for call in state["calls"] if call["operation"] == "nei.preview_control"]
    assert len(calls) == 1 and calls[0]["args"] == ARM
    assert operations(state).count("nei.view") == 2
    assert result.structuredContent["data"]["steps"][1]["observation"]["values"]["captureStatus"] == "pending"


async def test_actual_sdk_exposes_four_targets_and_explicit_arm_under_existing_control(bridge):
    adapter, state = bridge
    state["data"]["nei.preview_control"] = deepcopy(RECEIPT)
    async with create_connected_server_and_client_session(make_server(adapter.client)) as session:
        tools = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert set(tools) == {spec.name for spec in TOOLS}
        view, control = tools["mcb_nei_view"], tools["mcb_nei_preview_control"]
        assert set(TARGETS).issubset(view.inputSchema["properties"]["target"]["enum"])
        assert "preview_render_capture" in control.inputSchema["properties"]["action"]["enum"]
        result = await session.call_tool("mcb_nei_preview_control", ARM)
        assert not result.isError and result.structuredContent["data"]["captureStatus"] == "pending"
    assert operations(state).count("nei.preview_control") == 1


def test_existing_item_block_inventory_diagnostics_survive_metadata_only_projection():
    diagnostics = {"blockRenderType": 0, "blockRenderPass": 1, "nativeBlockInventory3d": True,
        "itemTintColorRGB": 16777215, "blockRenderColorRGB": 16777215}
    compact = project({"data": {"entries": [{**diagnostics}]}},
        {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for key, value in diagnostics.items():
        assert compact["metadata"]["data.entries.0." + key] == value


@pytest.mark.parametrize("hook", ["RenderBlocks.owner", "Tessellator.draw", "TileEntityRendererDispatcher.owner",
    "CTMUtils.getBlockIcon", "TesrBatchRenderer.queue", "GLStateManager.draw"])
def test_hook_entry_diagnostics_do_not_turn_unobserved_frames_into_unregistered_hook_claims(hook):
    diagnostic = {"everObservedInNaturalExecution": True, "observedInRequestedNaturalFrame": False,
        "meaning": "Actual hook entry; false is not evidence of missing registration."}
    compact = project({"data": {"hookDiagnostics": {hook: diagnostic}, "graphicsComplete": False}},
        {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for key, value in diagnostic.items():
        assert compact["metadata"]["data.hookDiagnostics." + hook + "." + key] == value
    assert compact["metadata"]["data.graphicsComplete"] is False


def test_native_arm_refreshed_context_is_reusable_and_not_capture_completion_evidence():
    receipt = {**deepcopy(RECEIPT), "contextToken": "new-native-capture-context", "contextRefreshed": True,
        "requiresViewRefresh": False, "position": POSITION}
    compact = project(receipt, {"kind": "action", "tool": "mcb_nei_preview_control"})
    assert compact["values"] == receipt
    selected = project(receipt, {"kind": "action", "tool": "mcb_nei_preview_control", "fields": ["status"]})
    assert selected["metadata"]["contextToken"] == "new-native-capture-context"
    assert selected["metadata"]["contextRefreshed"] is True
    assert selected["metadata"]["requiresViewRefresh"] is False
    assert selected["metadata"]["captureStatus"] == "pending"
