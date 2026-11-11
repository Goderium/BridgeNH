"""Exact native gesture parameters, once-only routing and strict branch validation."""
import pytest
from jsonschema import Draft202012Validator
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge, operations


@pytest.mark.parametrize("tool,args", [
    ("mcb_nei_item_action", {"screenId": "s", "action": "ghost_pick", "neiItemId": "nei-item:1"}),
    ("mcb_gui_widget_action", {"screenId": "s", "action": "activate", "widgetId": "widget:1", "button": 1}),
    ("mcb_gui_widget_action", {"screenId": "s", "action": "activate", "widgetId": "widget:1", "button": 2}),
    ("mcb_slot_click", {"windowId": 12, "slot": 123, "button": 5, "mode": 2}),
    ("mcb_slot_click", {"windowId": 12, "slot": 123, "button": 5, "mode": 2, "swapStrategy": "native"}),
    ("mcb_slot_click", {"windowId": 12, "slot": 123, "button": 5, "mode": 2, "swapStrategy": "pickup"}),
])
async def test_native_gestures_dispatch_exactly_once(bridge, tool, args):
    adapter, state = bridge
    result = await adapter.call_tool(tool, args)
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", TOOL_BY_NAME[tool].operation]
    assert state["calls"][-1]["args"] == args


@pytest.mark.parametrize("tool,args", [
    ("mcb_nei_item_action", {"screenId": "s", "action": "ghost_pick", "neiItemId": "nei-item:1", "count": 1}),
    ("mcb_nei_item_action", {"screenId": "s", "action": "ghost_pick", "registryName": "minecraft:stone"}),
    ("mcb_gui_widget_action", {"screenId": "s", "action": "activate", "widgetId": "widget:1", "button": 3}),
    ("mcb_gui_widget_action", {"screenId": "s", "action": "activate", "widgetId": "widget:1", "button": True}),
    ("mcb_gui_widget_action", {"screenId": "s", "action": "recheck_structure", "widgetId": "widget:1", "button": 1}),
    ("mcb_slot_click", {"windowId": 1, "slot": 2, "swapStrategy": "pickup"}),
    ("mcb_slot_click", {"windowId": 1, "slot": 2, "mode": 0, "swapStrategy": "pickup"}),
    ("mcb_slot_click", {"windowId": 1, "slot": 2, "mode": 2, "swapStrategy": "invented"}),
])
def test_gesture_branches_reject_mixed_or_untrusted_arguments(tool, args):
    schema = TOOL_BY_NAME[tool].schema
    Draft202012Validator.check_schema(schema)
    assert not Draft202012Validator(schema).is_valid(args)
