"""Screen-bound menu selections retain one dispatch and actual partial outcomes."""

import jsonschema
import pytest

from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge


def request(selections=None):
    return {"screenId": "current-menu", "action": "set_selected_many", "selections": selections if selections is not None else [
        {"widgetId": "current-mode", "selected": 2},
        {"widgetId": "current-options-page", "selected": 1},
        {"widgetId": "current-world-type", "selected": 1},
    ]}


def test_menu_batch_is_a_bounded_named_widget_action_without_world_credentials():
    spec = TOOL_BY_NAME["mcb_gui_widget_action"]
    jsonschema.validate(request(), spec.schema)
    assert spec.operation == "gui.widget_action" and spec.takeover and not spec.read_only


@pytest.mark.parametrize("selections", [
    [], [{"widgetId": "choice", "selected": 1}] * 9,
    [{"widgetId": "choice"}], [{"selected": 1}],
    [{"widgetId": "choice", "selected": True}],
    [{"widgetId": "choice", "selected": -1}],
    [{"widgetId": "choice", "selected": 1, "action": "activate"}],
])
def test_menu_batch_refuses_unbounded_or_executable_entries(selections):
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(request(selections), TOOL_BY_NAME["mcb_gui_widget_action"].schema)


@pytest.mark.parametrize("field,value", [("widgetId", "extra"), ("selected", 1), ("expectedWorld", "world")])
def test_menu_batch_does_not_mix_single_choice_or_world_arguments(field, value):
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({**request(), field: value}, TOOL_BY_NAME["mcb_gui_widget_action"].schema)


@pytest.mark.parametrize("completed", [True, False])
async def test_menu_selection_dispatches_once_and_preserves_readback_without_retry(bridge, completed):
    adapter, state = bridge
    outcome = {"status": "completed" if completed else "partial", "localSelectionMatched": completed,
               "afterState": {"gameMode": "creative", "worldType": "flat" if completed else "default"},
               "worldCreationRequested": False, "serverConfirmed": False, "outcomeUnknown": False}
    state["data"]["gui.widget_action"] = outcome
    result = await adapter.call_tool("mcb_gui_widget_action", request())
    assert result.structuredContent["data"] == outcome
    calls = [call for call in state["calls"] if call["operation"] == "gui.widget_action"]
    assert len(calls) == 1 and calls[0]["args"] == request()
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "client.background", "gui.widget_action"]
