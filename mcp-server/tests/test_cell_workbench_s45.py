"""Current synchronized CellWorkbench schema and dispatch tests; no live client."""

import pytest
from jsonschema import Draft202012Validator

from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge, operations


BASE = {"screenId": "screen:workbench", "action": "cell_config_set", "slot": 0}
VALID = [
    {**BASE, "resourceId": "ae:1"},
    {**BASE, "playerSlot": 0},
    {**BASE, "playerSlot": 35},
    {**BASE, "cursor": True},
    {**BASE, "slot": 62, "cursor": True},
    {"screenId": "screen:workbench", "action": "cell_config_clear", "slot": 62},
]
INVALID = [
    *[{**BASE, "slot": slot, "cursor": True} for slot in [-1, 63, 1023, True, 0.5, "0"]],
    *[{**BASE, "playerSlot": index} for index in [-1, 36, True, "0"]],
    *[{**BASE, "cursor": flag} for flag in [False, 1, "true", None]],
    BASE,
    *[{**BASE, **sources} for sources in [
        {"resourceId": "ae:1", "playerSlot": 0},
        {"resourceId": "ae:1", "cursor": True},
        {"playerSlot": 0, "cursor": True},
        {"resourceId": "ae:1", "playerSlot": 0, "cursor": True},
    ]],
    *[{**BASE, "cursor": True, **extra} for extra in [
        {"amount": "1"}, {"clear": True}, {"side": "input"},
        {"registryName": "minecraft:stone"}, {"nbt": "{}"},
    ]],
    {"action": "cell_config_set", "slot": 0, "cursor": True},
    {**BASE, "resourceId": ""},
    *[{"screenId": "screen:workbench", "action": "cell_config_clear", "slot": 62, **extra}
      for extra in [{"resourceId": "ae:1"}, {"playerSlot": 0}, {"cursor": True}, {"amount": "1"}, {"clear": True}]],
]


def test_cell_schemas_are_valid_and_keep_existing_pattern_boundaries():
    for name in ("mcb_ae", "mcb_ae_action"):
        Draft202012Validator.check_schema(TOOL_BY_NAME[name].schema)
    validator = Draft202012Validator(TOOL_BY_NAME["mcb_ae_action"].schema)
    assert validator.is_valid({"screenId": "pattern", "action": "pattern_set", "side": "input",
                               "slot": 63, "playerSlot": 0, "amount": "1"})
    assert not validator.is_valid({"screenId": "pattern", "action": "pattern_set", "side": "input",
                                   "slot": 0, "cursor": True, "amount": "1"})


@pytest.mark.parametrize("args", INVALID)
async def test_rejected_cell_arguments_never_reach_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_ae_action", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


@pytest.mark.parametrize("args", VALID)
async def test_cell_operation_dispatches_exactly_once_with_pending_receipt(bridge, args):
    adapter, state = bridge
    pending = {"submitted": True, "slot": args["slot"], "serverAcceptance": "pending"}
    state["data"]["ae.action"] = pending
    result = await adapter.call_tool("mcb_ae_action", args)
    assert not result.isError and result.structuredContent["data"] == pending
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("action", ("cell_config_set", "cell_config_clear"))
async def test_native_refusal_does_not_retry_the_cell_mutation(bridge, action):
    adapter, state = bridge
    state["error"] = "invalid_state"
    args = {"screenId": "screen:workbench", "action": action, "slot": 0}
    if action == "cell_config_set":
        args["cursor"] = True
    result = await adapter.call_tool("mcb_ae_action", args)
    assert result.isError and result.structuredContent["error"]["code"] == "invalid_state"
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]


@pytest.mark.parametrize("scope", (None, "cell_config"))
async def test_cell_read_keeps_null_entries_and_continuation_without_takeover(bridge, scope):
    adapter, state = bridge
    data = {"scope": "cell_config", "entries": [{"slot": 0, "resource": None}],
            "nextOffset": 1, "hasMore": True, "state": {"haveCell": False, "acceptedType": None}}
    state["data"]["ae.inspect"] = data
    args = {"screenId": "screen:workbench", "offset": 0, "limit": 1}
    if scope:
        args["scope"] = scope
    result = await adapter.call_tool("mcb_ae", args)
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", "ae.inspect"]
