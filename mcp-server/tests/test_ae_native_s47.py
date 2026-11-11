"""Normal AE crafting output schema, once-only dispatch and explicit pending state."""

import pytest
from jsonschema import Draft202012Validator

from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge, operations


BASE = {"screenId": "screen:crafting", "action": "crafting_output"}
VALID = [BASE, *[{**BASE, "mode": mode, "slot": 14}
                for mode in ("single", "stack", "inventory")]]
INVALID = [
    *[{**BASE, "mode": mode} for mode in ("all", "half", "CRAFT_ITEM", "", None, 1)],
    *[{**BASE, "slot": slot} for slot in (-1, 1024, True, 0.5, "14")],
    *[{**BASE, **extra} for extra in ({"amount": "1"}, {"batches": 1}, {"resourceId": "ae:1"},
                                    {"playerSlot": 0}, {"autoStart": True}, {"cursor": True})],
    {"action": "crafting_output"},
]


def test_crafting_output_schema_and_sequence_are_valid():
    for name in ("mcb_ae_action", "mcb_sequence"):
        Draft202012Validator.check_schema(TOOL_BY_NAME[name].schema)
    validator = Draft202012Validator(TOOL_BY_NAME["mcb_ae_action"].schema)
    assert all(validator.is_valid(args) for args in VALID)
    assert not any(validator.is_valid(args) for args in INVALID)
    assert validator.is_valid({"screenId": "amount", "action": "craft_request", "amount": "2"})


@pytest.mark.parametrize("args", VALID)
async def test_output_submits_exactly_one_native_mutation_with_unconfirmed_receipt(bridge, args):
    adapter, state = bridge
    receipt = {"submitted": True, "serverConfirmed": False, "slot": 14,
               "nativeAction": "CRAFT_ITEM", "maxOutputCount": "2"}
    state["data"]["ae.action"] = receipt
    result = await adapter.call_tool("mcb_ae_action", args)
    assert not result.isError and result.structuredContent["data"] == receipt
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("args", INVALID)
async def test_wrong_output_parameters_never_reach_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_ae_action", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


async def test_native_output_refusal_is_not_retried(bridge):
    adapter, state = bridge
    state["error"] = "stale_slot"
    result = await adapter.call_tool("mcb_ae_action", BASE)
    assert result.isError and result.structuredContent["error"]["code"] == "stale_slot"
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]


async def test_pending_preview_remains_a_read_and_later_becomes_ready_without_request(bridge):
    adapter, state = bridge
    args = {"screenId": "screen:preview", "scope": "preview"}
    pending = {"state": {"requested": None, "requestedPending": True, "startReady": False},
               "entries": []}
    state["data"]["ae.inspect"] = pending
    result = await adapter.call_tool("mcb_ae", args)
    assert not result.isError and result.structuredContent["data"] == pending
    ready = {"state": {"requested": {"amount": "2"}, "requestedPending": False,
                       "startReady": True}, "entries": []}
    state["data"]["ae.inspect"] = ready
    result = await adapter.call_tool("mcb_ae", args)
    assert not result.isError and result.structuredContent["data"] == ready
    assert operations(state) == ["bridge.health", "ae.inspect", "bridge.health", "ae.inspect"]
    assert not state["protected"]


@pytest.mark.parametrize("action", ("cell_config_set", "cell_config_clear"))
async def test_cell_flush_receipt_stays_unconfirmed_and_is_not_a_second_mutation(bridge, action):
    adapter, state = bridge
    args = {"screenId": "screen:workbench", "action": action, "slot": 0}
    if action == "cell_config_set":
        args["cursor"] = True
    receipt = {"submitted": True, "syncFlushInvoked": True, "serverConfirmed": False,
               "serverAcceptance": "pending"}
    state["data"]["ae.action"] = receipt
    result = await adapter.call_tool("mcb_ae_action", args)
    assert not result.isError and result.structuredContent["data"] == receipt
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]
