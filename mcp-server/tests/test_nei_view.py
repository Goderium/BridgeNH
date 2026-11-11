"""NEI UI reads retain live context, bounded coverage and actual stack data."""
from copy import deepcopy

import pytest

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, SEQUENCE_READ_NAMES, TOOL_BY_NAME
from minecraft_codex_bridge.workflows import project
from test_workflows import bridge, operations


SCREEN = {"screenId": "actual-screen"}
CONTEXT = {**SCREEN, "contextToken": "nei-session-screen-state-token"}
VIEW = {
    **CONTEXT, "section": "panel", "status": "partial", "offset": 16, "limit": 16,
    "total": 35, "nextOffset": 32, "hasMore": True, "complete": False,
    "byteLimited": False, "softBudgetPartial": True, "nonAtomic": True,
    "coverage": {"sourceScope": "backend_loaded", "renderOnlyText": "unavailable", "complete": False},
    "limits": {"resultBytes": 131072}, "budget": {"softBudgetExceeded": True},
    "data": {"entries": [{"index": 16, "quantity": "9223372036854775807",
        "item": {"registryName": "mod:actual_item", "metadata": 7, "hasTag": True,
            "nbt": '{actual:"NBT identity"}', "tooltip": ["Actual stack", "Amount: 9223372036854775807"]}}]},
}


@pytest.mark.parametrize("args", [
    SCREEN, {**CONTEXT, "section": "summary", "limit": 1},
    {**SCREEN, "section": "handlers"}, {**CONTEXT, "section": "handlers", "offset": 100000, "limit": 64},
    {**SCREEN, "section": "recipes"},
    {**CONTEXT, "section": "recipes", "scope": "handler", "handlerIndex": 100000, "offset": 16, "limit": 16, "includeTooltips": True, "advanced": True},
    {**SCREEN, "section": "recipes", "scope": "visible", "handlerIndex": 0},
    {**SCREEN, "section": "recipe", "recipeIndex": 2147483647, "handlerIndex": 100000, "limit": 1, "includeTooltips": False},
    *[{**SCREEN, "section": "panel", "panel": panel, "scope": "all", "limit": 64, "advanced": True}
      for panel in ("item", "history", "craftables", "bookmarks")],
    {**CONTEXT, "section": "panel", "scope": "visible", "offset": 64, "includeTooltips": True},
    {**SCREEN, "section": "settings"}, {**SCREEN, "section": "tree", "groupId": 2147483647}, {**SCREEN, "section": "preview"},
    {**CONTEXT, "section": "tooltip", "x": 0, "y": 32767, "includeTooltips": True, "advanced": True},
    {**CONTEXT, "section": "coverage", "offset": 32, "limit": 32},
])
async def test_nei_view_forwards_one_exact_bounded_request_without_takeover_or_pagination(bridge, args):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_nei_view", args)
    assert not result.isError and result.structuredContent["data"] == VIEW
    assert operations(state) == ["bridge.health", "nei.view"]
    assert state["calls"][-1]["args"] == args
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("args", [
    {}, {"screenId": ""}, {"screenId": True}, {**SCREEN, "section": "unknown"},
    {**SCREEN, "contextToken": ""}, {**SCREEN, "contextToken": "x" * 129},
    {**SCREEN, "offset": -1}, {**SCREEN, "offset": True}, {**SCREEN, "limit": True},
    {**SCREEN, "limit": 0}, {**SCREEN, "limit": 2}, {**SCREEN, "includeTooltips": False},
    {**SCREEN, "handlerIndex": 0}, {**SCREEN, "scope": "visible"}, {**SCREEN, "maxPages": 16},
    {**SCREEN, "section": "recipes", "offset": 1},
    {**CONTEXT, "section": "handlers", "offset": 2147483648},
    {**SCREEN, "section": "handlers", "limit": 65},
    {**SCREEN, "section": "handlers", "includeTooltips": False},
    {**SCREEN, "section": "recipes", "limit": 17},
    {**SCREEN, "section": "recipes", "scope": "all"},
    {**SCREEN, "section": "recipes", "recipeIndex": 0},
    {**SCREEN, "section": "recipes", "advanced": 1},
    {**SCREEN, "section": "recipe"}, {**SCREEN, "section": "recipe", "recipeIndex": True},
    {**SCREEN, "section": "recipe", "recipeIndex": 2147483648},
    {**SCREEN, "section": "panel", "scope": "handler"},
    {**SCREEN, "section": "panel", "panel": "unknown"},
    {**SCREEN, "section": "panel", "handlerIndex": 0},
    {**SCREEN, "section": "settings", "advanced": False},
    {**SCREEN, "section": "tree", "groupId": -1},
    {**SCREEN, "section": "tree", "groupId": True},
    {**SCREEN, "section": "tooltip", "x": 0},
    {**SCREEN, "section": "tooltip", "x": 0, "y": 32768},
    {**SCREEN, "section": "tooltip", "x": True, "y": 0},
    {**SCREEN, "section": "coverage", "limit": 33},
    *[{**CONTEXT, "section": section, "offset": 1, **extra} for section, extra in
      (("summary", {}), ("recipe", {"recipeIndex": 0}), ("settings", {}),
       ("tree", {}), ("preview", {}), ("tooltip", {"x": 0, "y": 0}))],
    *[{**SCREEN, "section": section, "x": 0, "y": 0} for section in ("summary", "handlers", "recipes", "recipe", "panel", "settings", "tree", "preview", "coverage")],
])
async def test_nei_view_rejects_invalid_or_cross_section_parameters_before_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_view", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


def test_nei_view_is_repeatable_observation_and_preserves_existing_nei_control_flags():
    spec = TOOL_BY_NAME["mcb_nei_view"]
    assert spec.operation == "nei.view" and spec.read_only and not spec.takeover
    assert spec.idempotent is not False and "nei.view" in READ_OPERATIONS
    assert "mcb_nei_view" in OBSERVATION_NAMES and "mcb_nei_view" in SEQUENCE_READ_NAMES
    assert "mcb_nei_view" in TOOL_BY_NAME["mcb_wait_for"].schema["properties"]["tool"]["enum"]
    for name in ("mcb_nei", "mcb_gui_tooltip"):
        assert TOOL_BY_NAME[name].read_only and not TOOL_BY_NAME[name].takeover
    for name in ("mcb_nei_open", "mcb_nei_navigate"):
        assert not TOOL_BY_NAME[name].read_only and TOOL_BY_NAME[name].takeover


@pytest.mark.parametrize("status", ["readable", "partial", "unsupported", "not_present"])
async def test_nei_view_retains_explicit_coverage_and_empty_partial_pages(bridge, status):
    adapter, state = bridge
    data = {**deepcopy(VIEW), "status": status, "data": {"entries": []}}
    state["data"]["nei.view"] = data
    result = await adapter.call_tool("mcb_nei_view", {**CONTEXT, "section": "panel", "offset": 16})
    assert not result.isError and result.structuredContent["data"] == data
    assert result.structuredContent["data"]["complete"] is False
    assert operations(state) == ["bridge.health", "nei.view"]


async def test_missing_nei_view_capability_has_no_legacy_fallback(bridge):
    adapter, state = bridge
    state["caps"].remove("nei.view")
    result = await adapter.call_tool("mcb_nei_view", SCREEN)
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"]


async def test_stale_nei_context_is_returned_without_reopen_or_retry(bridge):
    adapter, state = bridge
    state["action_error"] = {"code": "stale_context", "message": "Current NEI state differs from the bound token."}
    result = await adapter.call_tool("mcb_nei_view", {**CONTEXT, "section": "recipes", "offset": 16})
    assert result.isError and result.structuredContent["error"]["code"] == "stale_context"
    assert operations(state) == ["bridge.health", "nei.view"]


async def test_timed_out_nei_read_cancels_only_its_original_request_and_is_known_read_only(bridge):
    adapter, state = bridge
    state["slow"] = "nei.view"
    state["cancel_status"] = "already_running"
    result = await adapter.call_tool("mcb_nei_view", SCREEN)
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == "BRIDGE_TIMEOUT" and error["outcome_unknown"] is False
    assert operations(state) == ["bridge.health", "nei.view", "__cancel__"]
    assert state["calls"][1]["requestId"] == state["calls"][2]["requestId"] == error["request_id"]


async def test_nei_view_poll_uses_only_the_requested_repeatable_page(bridge):
    adapter, state = bridge
    observations = iter([{**deepcopy(VIEW), "complete": False}, {**deepcopy(VIEW), "complete": True}])
    state["data"]["nei.view"] = lambda: next(observations)
    args = {**CONTEXT, "section": "panel", "offset": 16, "limit": 16}
    result = await adapter.call_tool("mcb_wait_for", {"tool": "mcb_nei_view", "args": args,
        "condition": {"path": "complete", "operator": "equals", "value": True}, "interval_ms": 100, "timeout_ms": 1000})
    assert not result.isError and result.structuredContent["data"]["attempts"] == 2
    assert operations(state) == ["bridge.health", "nei.view"] * 2
    assert all(call["args"] == args for call in state["calls"] if call["operation"] == "nei.view")


async def test_observe_preserves_exact_nei_identity_long_tooltip_and_completeness_metadata(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_observe", {"views": [{"id": "nei", "tool": "mcb_nei_view",
        "args": {**CONTEXT, "section": "panel", "offset": 16}}]})
    observed = result.structuredContent["data"]["steps"][0]["observation"]
    assert not result.isError and observed["values"]["data"] == VIEW["data"]
    assert observed["values"]["coverage"] == VIEW["coverage"]
    assert observed["metadata"]["contextToken"] == CONTEXT["contextToken"]
    assert observed["metadata"]["nextOffset"] == 32 and observed["metadata"]["complete"] is False
    assert observed["coverageSummary"]["partial"] and observed["coverageSummary"]["pagination"]
    assert operations(state) == ["bridge.health", "nei.view", "bridge.health"]


async def test_sequence_binds_raw_nei_context_once_before_one_detail_page(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = deepcopy(VIEW)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "steps": [
        {"id": "summary", "kind": "read", "tool": "mcb_nei_view", "args": SCREEN},
        {"id": "detail", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "recipes", "offset": 16},
            "bind": {"contextToken": {"step": "summary", "path": "contextToken"}}},
    ]})
    assert not result.isError
    assert [call["args"] for call in state["calls"] if call["operation"] == "nei.view"] == [
        SCREEN, {**CONTEXT, "section": "recipes", "offset": 16}]
    assert "client.background" not in operations(state)


def test_explicit_nei_field_projection_retains_context_and_page_metadata():
    observed = project(VIEW, {"kind": "read", "tool": "mcb_nei_view", "fields": ["data.entries"]})
    assert observed["values"]["data.entries"] == VIEW["data"]["entries"]
    assert observed["metadata"]["contextToken"] == CONTEXT["contextToken"]
    assert observed["metadata"]["section"] == "panel" and observed["metadata"]["hasMore"] is True
