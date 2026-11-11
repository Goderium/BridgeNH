"""Nested NEI collections and text have explicit, bounded continuation reads."""
from copy import deepcopy

import pytest

from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN, CONTEXT, VIEW
from test_workflows import bridge, operations


DETAIL = {**SCREEN, "section": "detail"}


@pytest.mark.parametrize("args", [
    {**DETAIL, "target": "positions", "recipeIndex": 2147483647, "handlerIndex": 2147483647, "positionKind": "outputs"},
    *[{**DETAIL, "target": target, "recipeIndex": 42, "positionIndex": 2147483647, "positionKind": "other", "limit": 64}
      for target in ("alternatives", "badges")],
    {**DETAIL, "target": "metadata", "recipeIndex": 42},
    {**DETAIL, "target": "metadata_value", "recipeIndex": 42, "metadataIndex": 2147483647, "valuePath": [], "limit": 8192},
    {**DETAIL, "target": "metadata_value", "recipeIndex": 42, "metadataIndex": 0, "valuePath": [2147483647] * 8},
    *[{**DETAIL, "target": target, "recipeIndex": 42, "positionIndex": 0, "alternativeIndex": 2147483647,
        "tooltipSource": source, **({"lineIndex": 2147483647, "limit": 8192} if target == "tooltip_text" else {})}
      for target in ("tooltip_lines", "tooltip_text") for source in ("item", "position", "handler")],
    {**DETAIL, "target": "nbt", "recipeIndex": 42, "limit": 8192},
    *[{**DETAIL, "target": target, "stackSource": source, "itemIndex": 2147483647,
        **({"panel": "bookmarks"} if source == "panel" else {}),
        **({"lineIndex": 0} if target == "tooltip_text" else {}), "includeTooltips": False, "advanced": True}
      for target in ("tooltip_lines", "tooltip_text", "nbt") for source in ("panel", "catalyst", "tree_item", "tree_stat")],
    *[{**DETAIL, "target": "widgets", "widgetSource": source}
      for source in ("layout", "recipe", "tree", "tree_stats", "options")],
    *[{**DETAIL, "target": target} for target in ("catalysts", "subsets", "tree_items", "tree_roots", "tree_edges", "tree_stats", "preview_blocks", "preview_controls")],
    {**DETAIL, "target": "bookmark_permutations", "itemIndex": 2147483647},
    {**DETAIL, "target": "settings"},
    {**DETAIL, "target": "settings", "settingSource": "world", "configPath": "current.existing.path", "limit": 64},
    {**DETAIL, "target": "settings", "settingSource": "global", "configPath": ""},
    {**DETAIL, "target": "subset_items", "subsetPath": "Current.Existing.Subset", "limit": 64},
    {**DETAIL, "target": "tooltip_lines", "recipeIndex": 42, "tooltipSource": "badge", "badgeIndex": 2147483647},
    {**DETAIL, "target": "tooltip_text", "recipeIndex": 42, "tooltipSource": "badge", "badgeIndex": 0, "lineIndex": 1, "limit": 8192},
    {**CONTEXT, "section": "detail", "target": "positions", "recipeIndex": 42, "offset": 2147483647, "limit": 64},
    {**CONTEXT, "section": "detail", "target": "nbt", "stackSource": "panel", "itemIndex": 100001, "offset": 100001, "limit": 8192},
    {**CONTEXT, "section": "panel", "scope": "all", "offset": 2147483647, "limit": 64},
])
async def test_detail_continuation_arguments_are_typed_and_forwarded_once(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("args", [
    DETAIL, {**DETAIL, "target": "unknown"},
    {**DETAIL, "target": "positions"}, {**DETAIL, "target": "positions", "recipeIndex": 0, "limit": 65},
    {**DETAIL, "target": "positions", "recipeIndex": 0, "positionIndex": 0},
    {**DETAIL, "target": "alternatives", "recipeIndex": 0},
    {**DETAIL, "target": "badges", "positionIndex": 0},
    {**DETAIL, "target": "badges", "recipeIndex": 0, "positionIndex": 0, "alternativeIndex": 0},
    {**DETAIL, "target": "metadata", "recipeIndex": 0, "positionKind": "inputs"},
    {**DETAIL, "target": "metadata_value", "recipeIndex": 0},
    {**DETAIL, "target": "metadata_value", "recipeIndex": 0, "metadataIndex": 0, "limit": 8193},
    *[{**DETAIL, "target": "metadata_value", "recipeIndex": 0, "metadataIndex": 0, "valuePath": value}
      for value in ([0] * 9, [True], [-1], [2147483648], ["0"], "0")],
    {**DETAIL, "target": "tooltip_lines"}, {**DETAIL, "target": "tooltip_text", "recipeIndex": 0},
    {**DETAIL, "target": "tooltip_lines", "recipeIndex": 0, "lineIndex": 0},
    {**DETAIL, "target": "nbt", "recipeIndex": 0, "tooltipSource": "item"},
    {**DETAIL, "target": "nbt", "recipeIndex": 0, "lineIndex": 0},
    {**DETAIL, "target": "nbt", "recipeIndex": 0, "limit": 8193},
    {**DETAIL, "target": "nbt", "stackSource": "panel"},
    {**DETAIL, "target": "nbt", "stackSource": "panel", "itemIndex": 0, "recipeIndex": 0},
    *[{**DETAIL, "target": "tooltip_lines", "stackSource": source, "itemIndex": 0, "tooltipSource": "handler"}
      for source in ("panel", "catalyst", "tree_item", "tree_stat")],
    {**DETAIL, "target": "tooltip_lines", "stackSource": "catalyst", "itemIndex": 0, "panel": "item"},
    {**DETAIL, "target": "widgets", "widgetSource": "unknown"},
    {**DETAIL, "target": "widgets", "recipeIndex": 0},
    {**DETAIL, "target": "preview_controls", "widgetSource": "layout"},
    {**DETAIL, "target": "preview_blocks", "groupId": 0},
    {**DETAIL, "target": "catalysts", "itemIndex": 0},
    {**DETAIL, "target": "bookmark_permutations"},
    {**DETAIL, "target": "settings", "settingSource": "unknown"},
    {**DETAIL, "target": "settings", "configPath": "x" * 513},
    {**DETAIL, "target": "settings", "configPath": 0},
    {**DETAIL, "target": "settings", "recipeIndex": 0},
    {**DETAIL, "target": "settings", "limit": 65},
    {**DETAIL, "target": "subset_items"},
    {**DETAIL, "target": "subset_items", "subsetPath": ""},
    {**DETAIL, "target": "subset_items", "subsetPath": "x" * 513},
    {**DETAIL, "target": "subsets", "subsetPath": "existing"},
    {**DETAIL, "target": "tooltip_lines", "recipeIndex": 0, "tooltipSource": "badge"},
    {**DETAIL, "target": "tooltip_lines", "recipeIndex": 0, "badgeIndex": 0},
    {**DETAIL, "target": "tooltip_lines", "recipeIndex": 0, "tooltipSource": "item", "badgeIndex": 0},
    {**DETAIL, "target": "tooltip_lines", "stackSource": "panel", "itemIndex": 0, "tooltipSource": "badge", "badgeIndex": 0},
    {**DETAIL, "target": "nbt", "recipeIndex": 0, "badgeIndex": 0},
    *[{**DETAIL, "target": "positions", "recipeIndex": 0, field: value}
      for field, value in (("handlerIndex", 2147483648), ("recipeIndex", True), ("positionKind", "unknown"), ("offset", True))],
    *[{**DETAIL, "target": "catalysts", field: value}
      for field, value in (("x", 0), ("y", 0), ("scope", "all"), ("groupId", 0), ("limit", 65), ("advanced", 1), ("offset", 1))],
])
async def test_detail_rejects_unbounded_or_inapplicable_selectors_before_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("target", ["nbt", "tooltip_text", "metadata_value"])
async def test_large_text_page_keeps_exact_characters_token_and_continuation_without_autodrain(bridge, target):
    adapter, state = bridge
    text = 'Exact NBT "unicode": 雪, 🚀;\n' * 100
    data = {**deepcopy(VIEW), "section": "detail", "offset": 2048, "limit": 2048,
        "total": 9000, "nextOffset": 4096, "data": {"target": target, "unit": "characters", "offsetUnit": "unicode_codepoints", "text": text}}
    state["data"]["nei.view"] = data
    selectors = {"metadataIndex": 0} if target == "metadata_value" else {"lineIndex": 0} if target == "tooltip_text" else {}
    result = await adapter.call_tool("mcb_nei_view", {**CONTEXT, "section": "detail", "target": target,
        "recipeIndex": 42, "offset": 2048, "limit": 2048, **selectors})
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "nei.view"]
    observed = project(data, {"tool": "mcb_nei_view", "kind": "read", "fields": ["data.text"]})
    assert observed["values"]["data.text"] == text and observed["metadata"]["data.unit"] == "characters"
    assert observed["metadata"]["data.offsetUnit"] == "unicode_codepoints"
    assert observed["metadata"]["contextToken"] == CONTEXT["contextToken"] and observed["metadata"]["nextOffset"] == 4096


async def test_metadata_array_reports_its_actual_bounded_entry_limit(bridge):
    adapter, state = bridge
    data = {**deepcopy(VIEW), "section": "detail", "limit": 64,
        "data": {"target": "metadata_value", "unit": "entries", "entries": ["exact"] * 64}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**DETAIL, "target": "metadata_value", "recipeIndex": 0,
        "metadataIndex": 0, "limit": 8192})
    assert not result.isError and result.structuredContent["data"] == data
    assert result.structuredContent["data"]["limit"] == 64
    assert operations(state) == ["bridge.health", "nei.view"]


async def test_sequence_binds_native_recipe_and_nested_next_offset_without_agent_round_trip(bridge):
    adapter, state = bridge
    first = {**deepcopy(VIEW), "data": {"recipeIndex": 2147483647}, "nextOffset": 100001}
    replies = iter([first, deepcopy(VIEW)])
    state["data"]["nei.view"] = lambda: next(replies)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "steps": [
        {"id": "first", "kind": "read", "tool": "mcb_nei_view", "args": SCREEN},
        {"id": "tail", "kind": "read", "tool": "mcb_nei_view", "args": {**DETAIL, "target": "positions"},
            "bind": {"contextToken": {"step": "first", "path": "contextToken"},
                "recipeIndex": {"step": "first", "path": "data.recipeIndex"},
                "offset": {"step": "first", "path": "nextOffset"}}},
    ]})
    assert not result.isError
    views = [call for call in state["calls"] if call["operation"] == "nei.view"]
    assert len(views) == 2 and views[1]["args"] == {**DETAIL, "target": "positions", "contextToken": CONTEXT["contextToken"],
        "recipeIndex": 2147483647, "offset": 100001}
    assert "client.background" not in operations(state)
