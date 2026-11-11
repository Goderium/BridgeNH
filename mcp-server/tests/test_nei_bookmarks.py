"""Bookmark cleanup keeps exact scope, retention and once-only native dispatch."""
import pytest
from jsonschema import Draft202012Validator
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.bridge import READ_OPERATIONS
from test_expanded_operations import bridge, operations


async def test_read_never_takes_over(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_bookmarks", {"screenId": "current-screen"})
    assert not result.isError
    assert operations(state) == ["bridge.health", "nei.bookmarks"]
    assert "nei.bookmarks" in READ_OPERATIONS


@pytest.mark.parametrize("action", [
    {"action": "cleanup", "taskId": "example-task"},
    {"action": "remove", "entryIds": ["current-entry"]},
    {"action": "remove_recipe", "entryIds": ["current-entry"], "releaseRetained": True},
    {"action": "remove_group", "groupId": 1},
    {"action": "retain", "entryIds": ["current-entry"], "taskId": "example-task", "retention": "future"},
    {"action": "namespace_new"}, {"action": "namespace_select", "namespaceId": "current-namespace"},
    {"action": "group_rows", "startRow": 0, "endRow": 1},
    {"action": "group_mode", "groupId": 1, "collapsed": True},
    {"action": "move_item", "entryId": "one", "targetEntryId": "two"},
    {"action": "move_item", "entryId": "one", "targetRow": 0, "groupId": 0},
    {"action": "move_group", "groupId": 1, "targetRow": 0},
    {"action": "move_group", "groupId": 1, "targetRow": 0, "sourceNamespaceId": "other-page"},
    {"action": "recipe_collapse", "entryId": "one", "collapsed": True},
    {"action": "quantity", "scope": "group", "groupId": 1, "delta": -1},
])
async def test_actions_submit_exact_scope_once(bridge, action):
    adapter, state = bridge
    args = {"screenId": "current-screen", "contextToken": "current-context", **action}
    result = await adapter.call_tool("mcb_nei_bookmark_action", args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "nei.bookmark_action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("bad", [
    {"action": "cleanup"}, {"action": "cleanup", "taskId": "task", "releaseRetained": True},
    {"action": "remove", "entryIds": []}, {"action": "remove", "entryIds": ["one", "one"]},
    {"action": "remove", "entryIds": [str(i) for i in range(513)]},
    {"action": "retain", "entryIds": ["one"], "taskId": "task", "retention": "guess"},
    {"action": "move_item", "entryId": "one", "targetEntryId": "two", "targetRow": 2},
    {"action": "quantity", "scope": "item", "groupId": 1, "delta": 1},
    {"action": "group_mode", "groupId": 1},
])
async def test_invalid_cleanup_scope_does_not_touch_client(bridge, bad):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_bookmark_action", {"screenId": "current-screen", "contextToken": "context", **bad})
    assert result.isError and not state["calls"]


async def test_partial_native_cleanup_is_never_retried(bridge):
    adapter, state = bridge
    data = {"status": "partial", "outcomeUnknown": True, "automaticReplayAllowed": False}
    state["data"]["nei.bookmark_action"] = data
    result = await adapter.call_tool("mcb_nei_bookmark_action", {"screenId": "current-screen", "contextToken": "context", "action": "cleanup", "taskId": "task"})
    assert result.structuredContent["data"] == data
    assert operations(state).count("nei.bookmark_action") == 1


def test_recipe_tracking_does_not_require_new_behavior_for_old_calls():
    validator = Draft202012Validator(TOOL_BY_NAME["mcb_nei_navigate"].schema)
    base = {"screenId": "current-screen", "action": "bookmark", "index": 0}
    assert not list(validator.iter_errors(base))
    assert not list(validator.iter_errors({**base, "taskId": "task", "retention": "temporary"}))
    assert list(validator.iter_errors({**base, "retention": "temporary"}))
