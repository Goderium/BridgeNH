"""Preview details preserve player-visible provenance, bounded pages and native identities."""
from copy import deepcopy

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import TOOL_BY_NAME, TOOLS
from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN, CONTEXT, VIEW
from test_workflows import bridge, operations


DETAIL = {**SCREEN, "section": "detail"}
SCENE_POSITION = {"x": -33554432, "y": -2048, "z": 33554431}
BLOCK_TARGETS = ("preview_block", "preview_hint_text", "preview_textures")
LIST_TARGETS = ("preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_textures")
TEXT_TARGETS = ("preview_hint_text", "preview_candidate_tooltip_text", "preview_candidate_nbt")


def selectors(target):
    return {**({"groupIndex": 2147483647} if target.startswith("preview_candidate_") and target != "preview_candidate_groups" else {}),
        **({"alternativeIndex": 2147483647} if target in ("preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt") else {}),
        **({"lineIndex": 2147483647} if target == "preview_candidate_tooltip_text" else {})}


@pytest.mark.parametrize("args", [
    *[{**DETAIL, "target": target, "previewBlockSource": source} for target in BLOCK_TARGETS for source in ("selected", "hover")],
    *[{**DETAIL, "target": target, "previewBlockSource": "position", "previewPosition": SCENE_POSITION} for target in BLOCK_TARGETS],
    *[{**DETAIL, "target": target, **selectors(target)} for target in (*LIST_TARGETS, *TEXT_TARGETS)],
    *[{**CONTEXT, "section": "detail", "target": target, **selectors(target), "offset": 2147483647, "limit": 64}
        for target in LIST_TARGETS],
    *[{**CONTEXT, "section": "detail", "target": target, **selectors(target), "offset": 2048, "limit": 8192}
        for target in TEXT_TARGETS],
    {**DETAIL, "target": "preview_block", "offset": 0, "limit": 1},
    {**DETAIL, "target": "preview_textures", "textureSide": 0},
    {**DETAIL, "target": "preview_textures", "textureSide": 5, "previewBlockSource": "position", "previewPosition": {"x": 0, "y": 2047, "z": 0}},
    *[{**DETAIL, "target": target, **selectors(target), "advanced": flag}
        for target in ("preview_candidate_tooltip_lines", "preview_candidate_tooltip_text") for flag in (False, True)],
])
async def test_selected_preview_details_forward_exactly_once_without_input_or_autopagination(bridge, args):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("args", [
    {**DETAIL, "target": "preview_block", "limit": 2},
    {**CONTEXT, "section": "detail", "target": "preview_block", "offset": 1},
    *[{**DETAIL, "target": target, "previewBlockSource": "position"} for target in BLOCK_TARGETS],
    *[{**DETAIL, "target": target, "previewPosition": {"x": 0, "y": 64, "z": 0}} for target in BLOCK_TARGETS],
    *[{**DETAIL, "target": target, "previewBlockSource": source, "previewPosition": {"x": 0, "y": 64, "z": 0}}
        for target in BLOCK_TARGETS for source in ("selected", "hover")],
    *[{**DETAIL, "target": "preview_block", "previewBlockSource": "position", "previewPosition": position}
        for position in ({"x": 0, "z": 0}, {"x": True, "y": 64, "z": 0}, {"x": 0, "y": 64.5, "z": 0},
            {"x": -33554433, "y": 64, "z": 0}, {"x": 0, "y": -2049, "z": 0}, {"x": 0, "y": 2048, "z": 0},
            {"x": 0, "y": 64, "z": 33554432}, {"x": 0, "y": 64, "z": 0, "dimension": 180})],
    *[{**DETAIL, "target": target, **selectors(target), "limit": 65} for target in LIST_TARGETS],
    *[{**DETAIL, "target": target, **selectors(target), "limit": 8193} for target in TEXT_TARGETS],
    *[{**DETAIL, "target": target, **selectors(target), "offset": 1} for target in (*LIST_TARGETS, *TEXT_TARGETS)],
    *[{**DETAIL, "target": target, **selectors(target), field: value} for target in BLOCK_TARGETS
        for field, value in (("groupIndex", 0), ("alternativeIndex", 0), ("lineIndex", 0), ("advanced", True), ("includeTooltips", True))],
    *[{**DETAIL, "target": target, **selectors(target), "previewBlockSource": "hover"}
        for target in ("preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt")],
    *[{**DETAIL, "target": target, **selectors(target), "previewPosition": {"x": 0, "y": 64, "z": 0}}
        for target in ("preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt")],
    *[{**DETAIL, "target": "preview_textures", "textureSide": value} for value in (-1, 6, True, "0")],
    {**DETAIL, "target": "preview_hint_text", "textureSide": 0},
    *[{**DETAIL, "target": target} for target in ("preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt")],
    *[{**DETAIL, "target": target, "groupIndex": 0} for target in ("preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt")],
    {**DETAIL, "target": "preview_candidate_tooltip_text", "groupIndex": 0, "alternativeIndex": 0},
    *[{**DETAIL, "target": target, **selectors(target), "advanced": True} for target in ("preview_candidate_groups", "preview_candidate_items", "preview_candidate_nbt")],
    *[{**DETAIL, "target": target, **selectors(target), "includeTooltips": True}
        for target in ("preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt")],
    *[{**DETAIL, "target": "preview_candidate_items", "groupIndex": value} for value in (-1, True, 2147483648, "0")],
    {**DETAIL, "target": "preview_candidate_tooltip_lines", "groupIndex": 0, "alternativeIndex": True},
    {**DETAIL, "target": "preview_candidate_tooltip_text", "groupIndex": 0, "alternativeIndex": 0, "lineIndex": -1},
])
async def test_preview_detail_branch_errors_are_refused_before_any_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


@pytest.mark.parametrize("target", ["preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_text", "preview_textures"])
async def test_changed_context_has_no_source_read_fallback_or_silent_token_refresh(bridge, target):
    adapter, state = bridge
    state["action_error"] = {"code": "stale_context", "message": "Selected block, resource or language changed."}
    args = {**CONTEXT, "section": "detail", "target": target, **selectors(target), "offset": 1}
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "stale_context"
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("target", TEXT_TARGETS)
async def test_full_candidate_or_hint_text_preserves_unicode_offset_unit_and_bounded_page(bridge, target):
    adapter, state = bridge
    text = '§e允许仓室: 雪🚀; NBT={value:"exact"}\n' * 80
    data = {**deepcopy(VIEW), "section": "detail", "offset": 2048, "limit": 2048,
        "nextOffset": 4096, "data": {"target": target, "unit": "codepoints", "offsetUnit": "unicode_codepoints", "text": text,
            "coordinateSpace": "preview_scene", "worldPositionsAvailable": False}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**CONTEXT, "section": "detail", "target": target,
        **selectors(target), "offset": 2048, "limit": 2048})
    assert result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "nei.view"]
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.text"]})
    assert compact["values"]["data.text"] == text
    assert compact["metadata"]["data.offsetUnit"] == "unicode_codepoints"
    assert compact["metadata"]["contextToken"] == CONTEXT["contextToken"]
    assert compact["metadata"]["nextOffset"] == 4096
    assert compact["metadata"]["data.worldPositionsAvailable"] is False


async def test_texture_face_and_layer_data_remain_exact_without_images_or_visual_inference(bridge):
    adapter, state = bridge
    entries = [{"side": 5, "layerIndex": 0, "textureClass": "native.Layer",
        "resource": "gregtech:blocks/casing", "uv": {"minU": 0.25, "maxU": 0.5, "minV": 0.5, "maxV": 0.75},
        "colors": [255, 64, 0, 255], "complete": False,
        "coverage": {"visibleInformation": "animated overlay frame and custom renderer geometry",
            "reason": "Native texture descriptor contains icon/layer data; emitted geometry is not stored as a public model."}}]
    data = {**deepcopy(VIEW), "section": "detail", "offset": 0, "data": {"target": "preview_textures",
        "coordinateSpace": "preview_scene", "entries": entries, "worldPositionsAvailable": False}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**DETAIL, "target": "preview_textures", "textureSide": 5})
    assert result.structuredContent["data"] == data
    assert all(content.type == "text" for content in result.content)
    assert operations(state) == ["bridge.health", "nei.view"]
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    assert compact["values"]["data.entries"] == entries
    assert compact["metadata"]["data.entries.0.coverage.reason"] == entries[0]["coverage"]["reason"]
    assert compact["metadata"]["data.entries.0.complete"] is False


@pytest.mark.parametrize("args", [
    {**DETAIL, "target": "hover_tooltip_lines"}, {**DETAIL, "target": "hover_hotkeys", "limit": 64},
    {**DETAIL, "target": "hover_tooltip_text", "lineIndex": 2147483647, "limit": 8192},
    {**CONTEXT, "section": "detail", "target": "hover_tooltip_lines", "offset": 2147483647, "limit": 64},
    {**CONTEXT, "section": "detail", "target": "hover_tooltip_text", "lineIndex": 0, "offset": 2048, "limit": 8192},
    *[{**DETAIL, "target": target, **selectors(target), "modifiers": modifiers, "advanced": True}
        for target in ("preview_candidate_tooltip_lines", "preview_candidate_tooltip_text")
        for modifiers in ({"shift": True}, {"control": False}, {"alt": True}, {"shift": False, "control": True, "alt": False})],
    *[{**DETAIL, "target": target, "recipeIndex": 0, **({"lineIndex": 0} if target == "tooltip_text" else {}),
        "modifiers": {"shift": True, "control": False, "alt": False}}
        for target in ("tooltip_lines", "tooltip_text")],
    *[{**DETAIL, "target": "tooltip_lines", "stackSource": source, "itemIndex": 0,
        **({"panel": "bookmarks"} if source == "panel" else {}), "modifiers": {"shift": False}}
        for source in ("panel", "catalyst", "tree_item", "tree_stat")],
])
async def test_hover_or_explicit_tooltip_modifiers_are_typed_and_dispatched_only_once(bridge, args):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("args", [
    *[{**DETAIL, "target": target, field: value} for target in ("hover_tooltip_lines", "hover_hotkeys")
        for field, value in (("limit", 65), ("lineIndex", 0), ("advanced", True), ("modifiers", {"shift": True}),
            ("x", 0), ("y", 0), ("includeTooltips", True), ("previewBlockSource", "hover"), ("offset", 1))],
    {**DETAIL, "target": "hover_tooltip_text"},
    *[{**DETAIL, "target": "hover_tooltip_text", "lineIndex": 0, field: value}
        for field, value in (("limit", 8193), ("lineIndex", True), ("lineIndex", -1), ("lineIndex", 2147483648),
            ("advanced", False), ("modifiers", {"shift": False}), ("x", 0), ("offset", 1))],
    *[{**DETAIL, "target": "tooltip_lines", "recipeIndex": 0, "modifiers": value}
        for value in ({}, True, None, [], {"shift": 1}, {"shift": "true"}, {"ctrl": True},
            {"shift": False, "control": False, "alt": False, "super": False})],
    *[{**DETAIL, "target": target, **selectors(target), "modifiers": {"shift": True}}
        for target in ("preview_candidate_items", "preview_candidate_groups", "preview_candidate_nbt", "preview_hint_text", "preview_textures")],
    {**DETAIL, "target": "nbt", "recipeIndex": 0, "modifiers": {"shift": True}},
    {**DETAIL, "target": "rendered_text", "modifiers": {"shift": True}},
    {**DETAIL, "target": "rendered_text_value", "itemIndex": 0, "modifiers": {"shift": True}},
    {**SCREEN, "section": "rendered", "modifiers": {"shift": True}},
    {**SCREEN, "section": "tooltip", "x": 0, "y": 0, "modifiers": {"shift": True}},
    {**SCREEN, "section": "recipe", "recipeIndex": 0, "includeTooltips": True, "modifiers": {"shift": True}},
])
async def test_physical_frame_reads_and_inapplicable_targets_cannot_request_virtual_redraw(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


async def test_in_memory_sequence_binds_preview_position_candidate_indices_and_tooltip_modifiers(bridge):
    adapter, state = bridge
    first = {**deepcopy(VIEW), "section": "preview", "data": {"position": {"x": -1, "y": 65, "z": 2},
        "groupIndex": 7, "alternativeIndex": 13, "modifiers": {"shift": True, "control": False, "alt": False}}}
    state["data"]["nei.view"] = deepcopy(first)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "steps": [
        {"id": "preview", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "preview"}},
        {"id": "block", "kind": "read", "tool": "mcb_nei_view",
            "args": {**SCREEN, "section": "detail", "target": "preview_block", "previewBlockSource": "position"},
            "bind": {"contextToken": {"step": "preview", "path": "contextToken"},
                "previewPosition": {"step": "preview", "path": "data.position"}}},
        {"id": "tooltip", "kind": "read", "tool": "mcb_nei_view",
            "args": {**SCREEN, "section": "detail", "target": "preview_candidate_tooltip_lines"},
            "bind": {"contextToken": {"step": "preview", "path": "contextToken"},
                "groupIndex": {"step": "preview", "path": "data.groupIndex"},
                "alternativeIndex": {"step": "preview", "path": "data.alternativeIndex"},
                "modifiers": {"step": "preview", "path": "data.modifiers"}}},
    ]})
    assert not result.isError and result.structuredContent["data"]["completedSteps"] == 3
    calls = [call["args"] for call in state["calls"] if call["operation"] == "nei.view"]
    assert calls[1]["previewPosition"] == first["data"]["position"]
    assert calls[2]["groupIndex"] == 7 and calls[2]["alternativeIndex"] == 13
    assert calls[2]["modifiers"] == first["data"]["modifiers"]
    assert "client.background" not in operations(state)


async def test_sdk_preserves_existing_tools_and_advertises_native_player_information_requests(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    server = make_server(adapter.client)
    async with create_connected_server_and_client_session(server) as session:
        listed = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert set(listed) == {spec.name for spec in TOOLS}
        view = listed["mcb_nei_view"]
        assert view.annotations.readOnlyHint and view.annotations.idempotentHint
        assert view.inputSchema["properties"]["modifiers"]["additionalProperties"] is False
        assert "preview_textures" in view.inputSchema["properties"]["target"]["enum"]
        assert "hover_tooltip_text" in view.inputSchema["properties"]["target"]["enum"]
        calls = [
            {**DETAIL, "target": "preview_block", "previewBlockSource": "position", "previewPosition": {"x": 0, "y": 64, "z": 0}},
            {**DETAIL, "target": "preview_candidate_tooltip_lines", "groupIndex": 0, "alternativeIndex": 0,
                "modifiers": {"shift": True, "control": False, "alt": False}},
            {**DETAIL, "target": "hover_tooltip_text", "lineIndex": 0, "limit": 8192},
            {**DETAIL, "target": "preview_textures", "textureSide": 5},
        ]
        for args in calls:
            result = await session.call_tool("mcb_nei_view", args)
            assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"] * len(calls)
    assert state["background"]["enabled"] is False
    assert TOOL_BY_NAME["mcb_nei_preview_control"].takeover


@pytest.mark.parametrize("hook_available", [True, False])
async def test_explicit_false_modifier_and_missing_keyboard_hook_are_preserved_without_inferred_tooltip(bridge, hook_available, monkeypatch):
    # Field-preservation assertions must not depend on a scheduler pause inside
    # the separately tested production metadata traversal deadline.
    monkeypatch.setattr("minecraft_codex_bridge.workflows.MAX_METADATA_MILLIS", 1000)
    adapter, state = bridge
    context = {"requestedModifiers": {"shift": False, "control": True, "alt": False},
        "physicalModifiers": {"shift": True, "ctrl": False, "alt": False, "source": "physical_keyboard"},
        "effectiveModifiers": {"shift": False, "control": True, "alt": False} if hook_available else {"shift": True, "control": False, "alt": False},
        "virtualModifiers": hook_available, "support": "scoped_keyboard_isKeyDown" if hook_available else "unsupported",
        "hookObserved": hook_available, "hookTarget": "org.lwjglx.input.Keyboard.isKeyDown",
        "limitations": ["Direct buffer reads/third-party key caches/native modifier APIs not intercepted"]}
    data = {**deepcopy(VIEW), "section": "detail", "status": "partial" if hook_available else "unsupported",
        "data": {"target": "preview_candidate_tooltip_lines", "modifierContext": context,
            "available": hook_available, "entries": [{"lineIndex": 0, "text": "native Ctrl tooltip"}] if hook_available else [],
            "coverage": {"visibleInformation": "Shift/Ctrl expanded item tooltip", "complete": False,
                "reason": "Other modifier APIs are not intercepted" if hook_available else "Requested override differs from physical state and the Keyboard hook was not observed"}}}
    state["data"]["nei.view"] = data
    args = {**DETAIL, "target": "preview_candidate_tooltip_lines", "groupIndex": 0, "alternativeIndex": 0,
        "modifiers": context["requestedModifiers"]}
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == data
    assert state["calls"][-1]["args"]["modifiers"]["shift"] is False
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    for modifier in ("shift", "control", "alt"):
        assert compact["metadata"]["data.modifierContext.requestedModifiers." + modifier] is context["requestedModifiers"][modifier]
        assert compact["metadata"]["data.modifierContext.effectiveModifiers." + modifier] is context["effectiveModifiers"][modifier]
    assert compact["metadata"]["data.modifierContext.physicalModifiers.ctrl"] is False
    assert compact["metadata"]["data.modifierContext.hookObserved"] is hook_available
    assert compact["metadata"]["data.modifierContext.support"] == context["support"]
    assert compact["metadata"]["data.modifierContext.limitations"] == context["limitations"]
    assert compact["metadata"]["data.coverage.visibleInformation"] == data["data"]["coverage"]["visibleInformation"]
    assert compact["metadata"]["data.coverage.reason"] == data["data"]["coverage"]["reason"]
    assert compact["metadata"]["data.coverage.complete"] is False
    assert compact["values"]["data.entries"] == data["data"]["entries"]
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("target", ["hover_tooltip_lines", "hover_tooltip_text", "hover_hotkeys"])
async def test_missing_completed_hover_frame_remains_explicit_not_present_without_pointer_input(bridge, target):
    adapter, state = bridge
    missing = ["No completed tooltip frame for the current screen"]
    hover = {"source": "native_tooltip_observer", "frameId": 0, "frameAgeMillis": 0,
        "mouseX": 0, "mouseY": 0, "tooltipPhase": "not_present", "observerRegistered": True,
        "callbackCalls": 0, "originalTooltipLines": 0, "capturedTooltipLines": 0, "capturedCodepoints": 0,
        "captureLimited": False, "missingCapabilities": missing,
        "physicalModifiers": {"shift": False, "ctrl": False, "alt": False, "source": "physical_keyboard"},
        "entries": []}
    data = {**deepcopy(VIEW), "section": "detail", "status": "not_present", "offset": 0, "total": 0, "nextOffset": 0, "hasMore": False,
        "data": {"target": target, **hover}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**DETAIL, "target": target,
        **({"lineIndex": 0} if target == "hover_tooltip_text" else {})})
    assert result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for field in ("tooltipPhase", "observerRegistered", "callbackCalls", "originalTooltipLines", "capturedTooltipLines", "captureLimited", "mouseX", "mouseY"):
        assert compact["metadata"]["data." + field] == hover[field]
    assert compact["metadata"]["data.missingCapabilities"] == missing
    assert compact["metadata"]["status"] == "not_present" and compact["metadata"]["complete"] is False
    assert operations(state) == ["bridge.health", "nei.view"]
    assert not state["background"]["enabled"]


@pytest.mark.parametrize("value", [
    ["bounded reason"], [], ["x"] * 32,
])
def test_compact_diagnostic_string_lists_remain_small_and_exact(value):
    compact = project({"data": {"missingCapabilities": value}}, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert compact["metadata"]["data.missingCapabilities"] == value
    assert compact["coverageSummary"]["metadataOmitted"] is False


@pytest.mark.parametrize("value", [
    ["x"] * 33, ["x" * 2049], ["雪" * 2048] * 2, [{"nested": "not a flat bounded reason"}],
])
def test_large_or_nested_diagnostic_lists_report_omission_instead_of_expanding_them(value):
    compact = project({"data": {"limitations": value}}, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert "data.limitations" not in compact["metadata"]
    assert compact["coverageSummary"]["metadataOmitted"] is True


@pytest.mark.parametrize("target", ["preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines",
    "preview_candidate_tooltip_text", "preview_candidate_nbt"])
async def test_unselected_candidate_cache_retains_native_absence_and_diagnostics_without_a_scan(bridge, target, monkeypatch):
    # Test field preservation without scheduler pauses consuming the production time budget.
    monkeypatch.setattr("minecraft_codex_bridge.workflows.MAX_METADATA_MILLIS", 1000)
    adapter, state = bridge
    details = {"target": target, "unit": "entries", "resourceContext": "current resource reload and language",
        "coordinateFrame": "preview_scene", "candidateRulesCoverage": "native selected position cache only",
        "candidateSourcePosition": None, "candidateCacheMatchesSelection": False,
        "candidateSource": "native selected position cache", "candidateStatus": "no_selected_block", "entries": []}
    data = {**deepcopy(VIEW), "section": "detail", "status": "not_present", "total": 0, "nextOffset": 0,
        "hasMore": False, "complete": False, "reason": "No selected preview block; native candidate cache is unavailable", "data": details}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**DETAIL, "target": target, **selectors(target)})
    assert not result.isError and result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    for field in ("resourceContext", "coordinateFrame", "candidateRulesCoverage", "candidateCacheMatchesSelection", "candidateSource", "candidateStatus"):
        assert compact["metadata"]["data." + field] == details[field]
    assert compact["metadata"]["status"] == "not_present"
    assert compact["metadata"]["reason"] == data["reason"]
    assert compact["metadata"]["complete"] is False
    assert compact["values"]["data.entries"] == []
    assert operations(state) == ["bridge.health", "nei.view"]
    assert not state["background"]["enabled"]


def test_texture_model_limit_and_runtime_getter_diagnostics_survive_compact_entry_selection(monkeypatch):
    monkeypatch.setattr("minecraft_codex_bridge.workflows.MAX_METADATA_MILLIS", 1000)
    details = {"target": "preview_textures", "textureReadSource": "native getter descriptors", "textureSide": 5,
        "textureGraphicsCoverage": "CTM world renderer, TESR geometry and shader/light pixels are not evaluated",
        "resourceContext": "current resource reload and language", "maximumTraversedNodes": 512, "maximumTextureEntries": 256,
        "textureRootSource": "native GT texture array", "textureModelComplete": False, "textureModelLimitReason": "node_budget",
        "unresolvedTextureEntries": 1, "pixelDataStored": False,
        "tileContext": {"frontFacing": 2, "active": True, "colorization": -1, "getterNameStatus": "resolved", "source": "native getters"},
        "entries": [{"side": 5, "layerPath": "0.1", "textureClass": "native.Layer", "status": "unsupported",
            "reason": "Custom geometry has no native texture descriptor", "passesComplete": False, "ctmStatus": "not_evaluated",
            "baseIcon": {"iconName": "native:block", "minU": 0.25, "maxU": 0.5,
                "derivedSourceResource": "native:textures/blocks/block.png", "derivedSourceStatus": "icon_name_convention"}}]}
    compact = project({"data": details}, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    for field in ("textureReadSource", "textureGraphicsCoverage", "resourceContext", "textureSide", "maximumTraversedNodes",
            "maximumTextureEntries", "textureRootSource", "textureModelComplete", "textureModelLimitReason", "unresolvedTextureEntries", "pixelDataStored"):
        assert compact["metadata"]["data." + field] == details[field]
    for field in ("frontFacing", "active", "colorization", "getterNameStatus", "source"):
        assert compact["metadata"]["data.tileContext." + field] == details["tileContext"][field]
    assert compact["metadata"]["data.entries.0.passesComplete"] is False
    assert compact["metadata"]["data.entries.0.ctmStatus"] == "not_evaluated"
    assert compact["metadata"]["data.entries.0.baseIcon.derivedSourceStatus"] == "icon_name_convention"
    assert compact["values"]["data.entries"] == details["entries"]
