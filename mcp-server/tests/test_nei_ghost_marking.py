"""NEI selection and ghost marking stay semantic, nonconsuming and once-only."""

import pytest
from jsonschema import Draft202012Validator

from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_ACTION_NAMES
from test_expanded_operations import bridge, operations


SOURCES = [
    {"registryName": "eternalsingularity:eternal_singularity"},
    {"registryName": "gregtech:gt.metaitem.01", "metadata": 3},
    {"neiItemId": "nei-item:observed"},
    {"playerSlot": 35},
    {"fluidName": "water", "amount": 64000},
]


@pytest.mark.parametrize("source", SOURCES)
async def test_cell_ghost_source_uses_one_native_delta_without_real_generation(bridge, source):
    adapter, state = bridge
    args = {"screenId": "cell", "action": "cell_config_set", "slot": 45, "source": source}
    state["data"]["ae.action"] = {"configurationOnly": True, "inventoryConsumed": False,
                                  "syncFlushInvoked": True, "serverConfirmed": False}
    result = await adapter.call_tool("mcb_ae_action", args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "ae.action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("extra", [
    {"cursor": True}, {"playerSlot": 0}, {"resourceId": "ae:1"}, {"amount": "1"},
])
async def test_ghost_source_rejects_ambiguous_or_quantity_branches_before_dispatch(bridge, extra):
    adapter, state = bridge
    args = {"screenId": "cell", "action": "cell_config_set", "slot": 45,
            "source": {"neiItemId": "nei-item:1"}, **extra}
    result = await adapter.call_tool("mcb_ae_action", args)
    assert result.isError and state["calls"] == []


@pytest.mark.parametrize("source", [
    {}, {"neiItemId": ""}, {"neiItemId": "nei:1", "registryName": "minecraft:stone"},
    {"neiItemId": "nei:1", "nbt": "{}"}, {"fluidName": "water", "count": 1},
])
async def test_untrusted_ghost_identity_cannot_reconstruct_nbt_or_mix_sources(bridge, source):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_ae_action", {
        "screenId": "cell", "action": "cell_config_set", "slot": 45, "source": source})
    assert result.isError and state["calls"] == []


@pytest.mark.parametrize("args", [
    {"screenId": "inventory", "action": "search", "text": "永恒奇点"},
    {"screenId": "inventory", "action": "take", "neiItemId": "nei-item:1"},
    {"screenId": "inventory", "action": "take", "neiItemId": "nei-item:1", "count": 1},
    {"screenId": "workbench", "action": "release"},
])
async def test_nei_item_action_is_once_only_and_uses_native_takeover(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_nei_item_action", args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "nei.item_action"]
    assert state["calls"][-1]["args"] == args


async def test_survival_or_disabled_native_acquisition_refusal_is_never_retried(bridge):
    adapter, state = bridge
    state["error"] = "permission_denied"
    result = await adapter.call_tool("mcb_nei_item_action", {
        "screenId": "inventory", "action": "take", "neiItemId": "nei-item:1"})
    assert result.isError
    assert operations(state) == ["bridge.health", "client.background", "nei.item_action"]


def test_strict_nei_action_schema_and_sequence_support():
    schema = TOOL_BY_NAME["mcb_nei_item_action"].schema
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    for args in [
        {"screenId": "inv", "action": "search", "text": "x", "neiItemId": "nei:1"},
        {"screenId": "inv", "action": "take", "registryName": "minecraft:stone"},
        {"screenId": "inv", "action": "take", "neiItemId": "nei:1", "count": 0},
        {"screenId": "inv", "action": "take", "neiItemId": "nei:1", "count": True},
        {"screenId": "inv", "action": "take", "neiItemId": "nei:1", "count": 65},
        {"screenId": "inv", "action": "release", "text": "x"},
        {"screenId": "inv", "action": "release", "count": 1},
        {"screenId": "inv", "action": "release", "neiItemId": "nei:1"},
    ]:
        assert not validator.is_valid(args)
    assert "mcb_nei_item_action" in SEQUENCE_ACTION_NAMES
    assert TOOL_BY_NAME["mcb_nei_item_action"].destructive
    assert validator.is_valid({"screenId": "inv", "action": "release"})


def test_marking_guidance_requires_ghosts_and_keeps_startup_compact():
    from minecraft_codex_bridge.agent_instructions import AGENT_INSTRUCTIONS
    assert "never give/take in survival" in AGENT_INSTRUCTIONS
    assert "Clean temporary real markers" in AGENT_INSTRUCTIONS
    assert "nei.item_action release" in AGENT_INSTRUCTIONS
    assert len(AGENT_INSTRUCTIONS.encode("utf8")) <= 4096
