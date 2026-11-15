"""One-call GUI chains retain synchronization and stop after incomplete actions."""

import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.workflows import validate_plan
from test_workflows import bridge, plan, action, read, operations, PROTECTED


async def test_inline_after_waits_without_repeating_the_action_and_binds_next_target(bridge):
    adapter, state = bridge
    count = 0

    def observation():
        nonlocal count
        count += 1
        return {"screenId": "next-screen", "windowId": 7, "ready": count >= 3}

    state["data"]["gui.inspect"] = observation
    result = await adapter.call_tool("mcb_sequence", plan(
        action("open", "mcb_gui_key", {"screenId": "old-screen", "keyCode": 1}, after={
            "id": "opened", "tool": "mcb_gui", "condition": {"path": "ready", "operator": "equals", "value": True},
            "interval_ms": 50}),
        action("next", "mcb_gui_key", {"keyCode": 1}, bind={"screenId": {"step": "opened", "path": "screenId"}})))
    data = result.structuredContent["data"]
    assert not result.isError and data["declaredSteps"] == 2 and data["expandedSteps"] == 3
    assert [r["id"] for r in data["steps"]] == ["open", "opened", "next"]
    assert data["steps"][1]["attempts"] == 3
    assert [c["args"]["screenId"] for c in state["calls"] if c["operation"] == "gui.key"] == ["old-screen", "next-screen"]


async def test_after_read_returns_fresh_widgets_in_the_same_call(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "next", "controls": {"widgets": [{"widgetId": "w", "selected": 2}]}}
    result = await adapter.call_tool("mcb_sequence", plan(action(after={"id": "after", "tool": "mcb_gui", "fields": ["screenId", "controls"]})))
    assert not result.isError
    assert result.structuredContent["data"]["steps"][1]["observation"]["values"]["controls"]["widgets"][0]["selected"] == 2
    assert operations(state).count("player.hotbar") == 1


@pytest.mark.parametrize("receipt", [
    {"status": "partial"}, {"localSelectionMatched": False}, {"accepted": False},
    {"partial": True}, {"status": "not_confirmed"}, {"outcomeUnknown": True},
    {"phantomAction": {"accepted": False}},
])
async def test_incomplete_action_stops_before_after_read_or_next_mutation(bridge, receipt):
    adapter, state = bridge
    state["data"]["gui.widget_action"] = {**receipt, "after": {"selected": 1}, "submitted": True}
    result = await adapter.call_tool("mcb_sequence", plan(
        action("choice", "mcb_gui_widget_action", {"screenId": "s", "widgetId": "w", "action": "activate"},
               after={"id": "settled", "tool": "mcb_gui"}), action("later")))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "ACTION_NOT_CONFIRMED"
    assert data["acknowledgedActions"] == data["dispatchedActions"] == 1
    assert data["outcomeUnknown"] is (receipt.get("outcomeUnknown") is True)
    assert data["steps"][0]["observation"]["values"]["after"] == {"selected": 1}
    assert all(s["status"] == "not_executed" for s in data["steps"][1:])
    assert "player.hotbar" not in operations(state) and "gui.inspect" not in operations(state)


async def test_inline_wait_timeout_preserves_single_action_and_blocks_followup(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"ready": False}
    result = await adapter.call_tool("mcb_sequence", plan(action("a", after={"id": "wait", "tool": "mcb_gui",
        "condition": {"path": "ready", "operator": "equals", "value": True}, "timeout_ms": 60, "interval_ms": 50}), action("b")))
    assert result.isError and result.structuredContent["data"]["error"]["code"] == "WAIT_TIMEOUT"
    assert operations(state).count("player.hotbar") == 1


@pytest.mark.parametrize("steps", [
    [action("a", after={"id": "a", "tool": "mcb_gui"})],
    [action("a", after={"id": "b", "tool": "mcb_hotbar", "args": {"slot": 1}})],
    [action("a", after={"id": "b", "tool": "mcb_gui_widgets", "bind": {"screenId": {"step": "future", "path": "screenId"}}})],
    [action(f"a{i}", after={"id": f"r{i}", "tool": "mcb_gui"}) for i in range(17)],
])
async def test_whole_expanded_plan_refuses_before_game_calls(bridge, steps):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(*steps))
    assert result.isError and state["calls"] == []


def test_inline_acceptance_expands_to_existing_wait_contract():
    steps = validate_plan(plan(action("a", after={"id": "seen", "tool": "mcb_gui", "condition": {
        "path": "screenId", "operator": "exists"}})), False)
    assert steps[1]["kind"] == "wait" and steps[1]["accepts"] == ["a"]


async def test_confirmed_noop_does_not_block_the_next_action(bridge):
    adapter, state = bridge
    state["data"]["gui.widget_action"] = {"submitted": False, "localSelectionMatched": True, "after": {"selected": 2}}
    result = await adapter.call_tool("mcb_sequence", plan(
        action("noop", "mcb_gui_widget_action", {"screenId": "s", "widgetId": "w", "action": "set_selected", "selected": 2}), action("next")))
    assert not result.isError and operations(state).count("player.hotbar") == 1
    assert result.structuredContent["data"]["steps"][0]["observation"]["values"]["localSelectionMatched"] is True


@pytest.fixture
async def menu_bridge():
    state = {"calls": [], "screen": "menu", "safe": True, "version": 1, "actions": 0, "drift": False}
    def snapshot():
        return {"sessionId": "s", "profile": "player", "worldLoaded": False, "unloadedMenu": state["safe"],
                "screenId": state["screen"], "background": PROTECTED}
    reads, actions = ["bridge.health", "gui.inspect", "gui.widgets"], ["gui.widget_action", "gui.key"]
    async def respond(request):
        body = json.loads(request.content)
        state["calls"].append(body)
        op = body["operation"]
        before = snapshot()
        if op == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "s", "profile": "player", "background": PROTECTED,
                    "capabilities": reads + actions + ["client.snapshot", "client.background"],
                    "contextRpc": {"version": 1, "unloadedMenuVersion": state["version"], "reads": reads, "actions": actions}}
        elif op == "client.snapshot":
            data = snapshot()
        elif op in actions:
            assert body["includeContext"]["menuOnly"] is True
            if not state["safe"]:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": "MENU_CONTEXT_CHANGED", "message": "Menu left", "outcome_unknown": False}})
            state["actions"] += 1
            state["screen"] = "next-menu"
            if state["drift"]:
                state["safe"] = False
            data = {"submitted": True, "after": {"text": "observed"}}
        else:
            data = {"screenId": state["screen"], "widgets": [{"widgetId": "next-widget", "text": "Continue"}]}
        result = {"requestId": body["requestId"], "ok": True, "data": data}
        if "includeContext" in body:
            result["context"] = {"version": 1, "operation": op, "phase": "before" if op in actions else "after",
                                 "snapshot": before if op in actions else snapshot()}
        return httpx.Response(200, json=result)
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "menu-test"}), transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def menu_plan():
    return {"expectedSessionId": "s", "menuScreenId": "menu", "steps": [
        action("first", "mcb_gui_widget_action", {"screenId": "menu", "widgetId": "w", "action": "activate"},
            after={"id": "next", "tool": "mcb_gui", "fields": ["screenId", "widgets"]}),
        action("second", "mcb_gui_key", {"keyCode": 1}, bind={"screenId": {"step": "next", "path": "screenId"}})]}


async def test_unloaded_menu_navigation_with_after_read_uses_guarded_native_actions(menu_bridge):
    adapter, state = menu_bridge
    result = await adapter.call_tool("mcb_sequence", menu_plan())
    assert not result.isError and state["actions"] == 2
    assert result.structuredContent["data"]["scope"] == "unloaded_menu"
    assert result.structuredContent["data"]["steps"][0]["observation"]["values"]["after"] == {"text": "observed"}
    assert [c["args"]["screenId"] for c in state["calls"] if c["operation"] in {"gui.key", "gui.widget_action"}] == ["menu", "next-menu"]


@pytest.mark.parametrize("change,expected", [({"screen": "other"}, "stale_screen"), ({"safe": False}, "MENU_CONTEXT_CHANGED"),
    ({"version": 0}, "UNSUPPORTED_CAPABILITY"), ({"version": True}, "UNSUPPORTED_CAPABILITY")])
async def test_menu_entry_failure_dispatches_no_action(menu_bridge, change, expected):
    adapter, state = menu_bridge
    state.update(change)
    result = await adapter.call_tool("mcb_sequence", menu_plan())
    assert result.isError and result.structuredContent["data"]["error"]["code"] == expected
    assert state["actions"] == 0


async def test_menu_change_after_first_action_blocks_remaining_steps(menu_bridge):
    adapter, state = menu_bridge
    state["drift"] = True
    result = await adapter.call_tool("mcb_sequence", menu_plan())
    assert result.isError and state["actions"] == 1
    assert result.structuredContent["data"]["error"]["code"] == "MENU_CONTEXT_CHANGED"


async def test_menu_mode_cannot_dispatch_world_or_inventory_actions(menu_bridge):
    adapter, state = menu_bridge
    request = menu_plan()
    request["steps"][0] = action("inventory", "mcb_slot_click", {"windowId": 0, "slot": 1})
    result = await adapter.call_tool("mcb_sequence", request)
    assert result.isError and state["calls"] == []
