"""Native NEI modifiers/bookmarks/overlay stay screen-bound and once-only."""
import pytest
from jsonschema import Draft202012Validator
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge, operations


@pytest.mark.parametrize("args", [
    {"action": "pause", "index": 7},
    {"action": "pause", "index": 7, "ttl_ms": 60000},
    {"action": "resume"},
    {"action": "bookmark", "index": 7, "saveCount": True},
    {"action": "overlay", "index": 7, "shift": False},
])
async def test_native_actions_are_bound_and_submitted_once(bridge, args):
    adapter, state = bridge
    value = {"screenId": "current-nei", **args}
    result = await adapter.call_tool("mcb_nei_navigate", value)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "nei.navigate"]
    assert state["calls"][-1]["args"] == value


@pytest.mark.parametrize("args", [
    {"action": "pause"}, {"action": "pause", "index": 0, "ttl_ms": 60001},
    {"action": "pause", "index": 0, "shift": True},
    {"action": "resume", "index": 0}, {"action": "bookmark", "index": 0, "shift": True},
    {"action": "overlay", "index": 0, "saveCount": True},
])
def test_native_actions_reject_mixed_selectors(args):
    schema = TOOL_BY_NAME["mcb_nei_navigate"].schema
    Draft202012Validator.check_schema(schema)
    assert list(Draft202012Validator(schema).iter_errors({"screenId": "nei", **args}))
