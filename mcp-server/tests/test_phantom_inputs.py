"""Debug templates use native phantom controls, never ordinary inventory transfer."""
from copy import deepcopy

import pytest

from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_ACTION_NAMES
from test_workflows import bridge, operations

BASE = {"screenId": "live-screen", "widgetId": "widget:17"}


@pytest.mark.parametrize("source", [
    {"registryName": "minecraft:iron_ingot"},
    {"registryName": "gregtech:gt.metaitem.01", "metadata": 300, "count": 2147483647},
    {"playerSlot": 0}, {"playerSlot": 35, "count": 1},
    {"fluidName": "water"}, {"fluidName": "molten.iron", "amount": 2147483647},
    {"neiItemId": "nei-item:observed"},
])
async def test_ghost_drag_forwards_one_native_action_without_supply_or_slot_click(bridge, source):
    adapter, state = bridge
    args = {**BASE, "action": "ghost_drag", "source": source}
    receipt = {"submitted": True, "serverConfirmed": False,
               "phantomAction": {"accepted": True, "configurationOnly": True, "inventoryConsumed": False}}
    state["data"]["gui.widget_action"] = deepcopy(receipt)
    result = await adapter.call_tool("mcb_gui_widget_action", args)
    assert not result.isError and result.structuredContent["data"] == receipt
    assert operations(state) == ["bridge.health", "client.background", "gui.widget_action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("button", [0, 1, 2])
async def test_native_phantom_mouse_button_is_not_an_os_click(bridge, button):
    adapter, state = bridge
    args = {**BASE, "action": "phantom_click", "button": button}
    result = await adapter.call_tool("mcb_gui_widget_action", args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "gui.widget_action"]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("extra", [
    {"action": "ghost_drag"},
    {"action": "ghost_drag", "source": {}},
    {"action": "ghost_drag", "source": {"registryName": "minecraft:iron_ingot", "playerSlot": 0}},
    {"action": "ghost_drag", "source": {"registryName": "minecraft:iron_ingot", "nbt": "{}"}},
    {"action": "ghost_drag", "source": {"registryName": "minecraft:iron_ingot", "metadata": 32767}},
    {"action": "ghost_drag", "source": {"registryName": "minecraft:iron_ingot", "count": 0}},
    {"action": "ghost_drag", "source": {"playerSlot": 36}},
    {"action": "ghost_drag", "source": {"playerSlot": True}},
    {"action": "ghost_drag", "source": {"fluidName": "water", "amount": 0}},
    {"action": "ghost_drag", "source": {"fluidName": "water", "count": 1}},
    {"action": "ghost_drag", "source": {"fluidName": ""}},
    {"action": "ghost_drag", "source": {"fluidName": "water"}, "button": 0},
    {"action": "activate", "source": {"playerSlot": 0}},
    {"action": "phantom_click"}, {"action": "phantom_click", "button": 3},
    {"action": "phantom_click", "button": 0, "source": {"playerSlot": 0}},
])
async def test_invalid_sources_never_take_control_or_dispatch(bridge, extra):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_gui_widget_action", {**BASE, **extra})
    assert result.isError and not state["calls"]


async def test_native_refusal_is_preserved_and_never_replayed(bridge):
    adapter, state = bridge
    state["data"]["gui.widget_action"] = {"submitted": False, "serverConfirmed": False,
                                           "phantomAction": {"accepted": False}}
    result = await adapter.call_tool("mcb_gui_widget_action", {**BASE, "action": "ghost_drag", "source": {"playerSlot": 0}})
    assert not result.isError
    assert result.structuredContent["data"]["submitted"] is False
    assert operations(state).count("gui.widget_action") == 1


def test_actions_are_discoverable_in_existing_native_tool_and_sequence():
    spec = TOOL_BY_NAME["mcb_gui_widget_action"]
    assert {"ghost_drag", "phantom_click"} <= set(spec.schema["properties"]["action"]["enum"])
    assert "mcb_gui_widget_action" in SEQUENCE_ACTION_NAMES
    assert "finite-mode" in spec.description and "full NBT" in spec.description
