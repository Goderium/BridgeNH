"""Native inventory visuals keep exact ItemStack selectors and renderer gaps."""
from copy import deepcopy

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import TOOLS
from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN, CONTEXT, VIEW
from test_workflows import bridge, operations


CANDIDATE = {**SCREEN, "section": "detail", "target": "preview_candidate_visuals", "groupIndex": 0, "alternativeIndex": 0}
ITEM = {**SCREEN, "section": "detail", "target": "item_visuals", "recipeIndex": 0}


@pytest.mark.parametrize("args", [
    CANDIDATE, {**CANDIDATE, "groupIndex": 2147483647, "alternativeIndex": 2147483647, "limit": 64},
    {**CANDIDATE, **CONTEXT, "offset": 2147483647, "limit": 64},
    ITEM, {**ITEM, "stackSource": "recipe", "handlerIndex": 2, "positionKind": "outputs", "positionIndex": 4, "alternativeIndex": 7},
    {**ITEM, **CONTEXT, "offset": 1, "limit": 64},
    *[{**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": "panel", "panel": panel, "itemIndex": 0}
        for panel in ("item", "history", "craftables", "bookmarks")],
    *[{**CONTEXT, "section": "detail", "target": "item_visuals", "stackSource": source, "itemIndex": 2147483647,
        "offset": 64, "limit": 64} for source in ("panel", "catalyst", "tree_item", "tree_stat")],
])
async def test_inventory_visuals_use_the_exact_observed_stack_and_one_bounded_read(bridge, args):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("args", [
    *[{**base, key: value} for base in (CANDIDATE, ITEM) for key, value in (
        ("limit", 65), ("limit", 0), ("limit", True), ("offset", 1), ("advanced", False), ("includeTooltips", False),
        ("modifiers", {"shift": False}), ("textureSide", 0), ("previewBlockSource", "selected"),
        ("previewPosition", {"x": 0, "y": 0, "z": 0}), ("tooltipSource", "item"), ("lineIndex", 0), ("x", 0), ("y", 0))],
    {key: value for key, value in CANDIDATE.items() if key != "groupIndex"},
    {key: value for key, value in CANDIDATE.items() if key != "alternativeIndex"},
    *[{**CANDIDATE, key: value} for key in ("groupIndex", "alternativeIndex") for value in (-1, True, "0", 2147483648)],
    *[{**CANDIDATE, key: value} for key, value in (("stackSource", "recipe"), ("recipeIndex", 0), ("itemIndex", 0), ("panel", "item"))],
    {**SCREEN, "section": "detail", "target": "item_visuals"},
    {**ITEM, "itemIndex": 0}, {**ITEM, "groupIndex": 0}, {**ITEM, "panel": "item"},
    *[{**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": source}
        for source in ("panel", "catalyst", "tree_item", "tree_stat")],
    *[{**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": source, "itemIndex": 0, key: value}
        for source in ("panel", "catalyst", "tree_item", "tree_stat")
        for key, value in (("recipeIndex", 0), ("handlerIndex", 0), ("positionKind", "inputs"), ("positionIndex", 0), ("alternativeIndex", 0), ("groupIndex", 0))],
    *[{**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": source, "itemIndex": 0, "panel": "item"}
        for source in ("catalyst", "tree_item", "tree_stat")],
    {**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": "tree", "itemIndex": 0},
    *[{**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": "panel", "itemIndex": value}
        for value in (-1, True, "0", 2147483648)],
])
async def test_visual_selector_branch_conflicts_cannot_read_or_mutate_the_client(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


@pytest.mark.parametrize("args", [CANDIDATE, ITEM])
async def test_inventory_visual_continuations_refuse_stale_resource_or_stack_context_without_fallback(bridge, args):
    adapter, state = bridge
    state["action_error"] = {"code": "stale_context", "message": "Inventory stack identity or resource model changed"}
    requested = {**args, **CONTEXT, "offset": 16, "limit": 16}
    result = await adapter.call_tool("mcb_nei_view", requested)
    assert result.isError and result.structuredContent["error"]["code"] == "stale_context"
    assert state["calls"][-1]["args"] == requested
    assert operations(state) == ["bridge.health", "nei.view"]


@pytest.mark.parametrize("args", [CANDIDATE, ITEM])
async def test_native_inventory_layers_icons_and_custom_renderer_gap_remain_exact(bridge, args, monkeypatch):
    monkeypatch.setattr("minecraft_codex_bridge.workflows.MAX_METADATA_MILLIS", 1000)
    adapter, state = bridge
    entries = [{"side": 5, "layerPath": "0.1", "textureClass": "native.Texture", "status": "available",
        "rgba": [255, 64, 0, 255], "glow": True, "baseIcon": {"iconName": "gt:machine雪🚀",
            "minU": 0.25, "maxU": 0.5, "minV": 0.5, "maxV": 0.75}, "atlasResource": "textures/atlas/blocks.png"},
        {"renderPass": 1, "spriteNumber": 1, "atlasResource": "textures/atlas/items.png", "colorRGB": 16777215,
            "glint": False, "iconSource": "native Item.getIcon", "status": "available",
            "icon": {"iconName": "mod:overlay", "derivedSourceResource": "mod:textures/items/overlay.png",
                "derivedSourceStatus": "icon_name_convention"}}]
    gap = "Forge IItemRenderer exposes rendering callbacks, but no public arbitrary geometry, texture or shader model; renderItem was not invoked"
    details = {"target": args["target"], "unit": "entries", "resourceContext": "current resource reload and language",
        "visualSource": "native ItemStack inventory getters", "itemRendererClass": "mod.CustomItemRenderer",
        "itemRendererStatus": "custom_geometry_unavailable", "itemVisualKind": "native_inventory_layers",
        "inventoryTextureSource": "IMetaTileEntity.getInventoryTextures", "inventoryRenderContext": "inventory",
        "inventoryModelStatus": "available", "visualCoverage": gap, "textureModelComplete": False,
        "textureModelLimitReason": "custom_item_renderer_geometry", "unresolvedTextureEntries": 0,
        "pixelDataStored": False, "entries": entries}
    data = {**deepcopy(VIEW), "section": "detail", "data": details}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == data
    assert all(content.type == "text" for content in result.content)
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    assert compact["values"]["data.entries"] == entries
    for key in ("visualSource", "itemRendererClass", "itemRendererStatus", "itemVisualKind", "inventoryTextureSource",
            "inventoryRenderContext", "inventoryModelStatus", "visualCoverage", "resourceContext", "textureModelComplete",
            "textureModelLimitReason", "unresolvedTextureEntries", "pixelDataStored"):
        assert compact["metadata"]["data." + key] == details[key]
    assert compact["metadata"]["data.entries.1.icon.derivedSourceStatus"] == "icon_name_convention"
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["background"]["enabled"] is False


async def test_missing_candidate_selection_cannot_be_interpreted_as_no_visuals(bridge):
    adapter, state = bridge
    data = {**deepcopy(VIEW), "section": "detail", "status": "not_present", "total": 0, "hasMore": False,
        "data": {"target": "preview_candidate_visuals", "candidateStatus": "no_selected_block", "entries": [],
            "reason": "No selected preview block; native candidate inventory model unavailable"}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", CANDIDATE)
    assert not result.isError and result.structuredContent["data"] == data
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    assert compact["metadata"]["status"] == "not_present"
    assert compact["metadata"]["data.candidateStatus"] == "no_selected_block"
    assert compact["metadata"]["data.reason"] == data["data"]["reason"]
    assert operations(state) == ["bridge.health", "nei.view"]


async def test_in_memory_sequence_binds_real_candidate_and_recipe_stack_indices(bridge):
    adapter, state = bridge
    first = {**deepcopy(VIEW), "data": {"groupIndex": 3, "alternativeIndex": 7, "recipeIndex": 2, "positionIndex": 4}}
    state["data"]["nei.view"] = deepcopy(first)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "steps": [
        {"id": "view", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "preview"}},
        {"id": "candidate", "kind": "read", "tool": "mcb_nei_view",
            "args": {**SCREEN, "section": "detail", "target": "preview_candidate_visuals"},
            "bind": {key: {"step": "view", "path": "data." + key} for key in ("groupIndex", "alternativeIndex")}},
        {"id": "item", "kind": "read", "tool": "mcb_nei_view",
            "args": {**SCREEN, "section": "detail", "target": "item_visuals", "positionKind": "outputs"},
            "bind": {key: {"step": "view", "path": "data." + key} for key in ("recipeIndex", "positionIndex", "alternativeIndex")}},
    ]})
    assert not result.isError and result.structuredContent["data"]["completedSteps"] == 3
    calls = [call["args"] for call in state["calls"] if call["operation"] == "nei.view"]
    assert calls[1]["groupIndex"] == 3 and calls[1]["alternativeIndex"] == 7
    assert calls[2]["recipeIndex"] == 2 and calls[2]["positionIndex"] == 4 and calls[2]["alternativeIndex"] == 7
    assert "client.background" not in operations(state)


async def test_sdk_lists_and_calls_both_visual_targets_with_existing_tools_and_logical_pointer(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    async with create_connected_server_and_client_session(make_server(adapter.client)) as session:
        listed = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert set(listed) == {spec.name for spec in TOOLS}
        view = listed["mcb_nei_view"]
        assert view.annotations.readOnlyHint and view.annotations.idempotentHint
        assert {"preview_candidate_visuals", "item_visuals"}.issubset(view.inputSchema["properties"]["target"]["enum"])
        for args in (CANDIDATE, ITEM, {**SCREEN, "section": "detail", "target": "item_visuals", "stackSource": "panel", "itemIndex": 0}):
            result = await session.call_tool("mcb_nei_view", args)
            assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"] * 3
    assert state["background"]["enabled"] is False
