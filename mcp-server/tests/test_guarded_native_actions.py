import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError, request_context
from test_guarded_rpc import guarded_bridge, directive, plan, read
from test_construction_segment_coalescing import task_from


CASES = [
    ("mcb_place_block", "player.place_block", {"x": 1, "y": 2, "z": 3, "hotbarSlot": 0, "expectedWorld": "world"}),
    ("mcb_look", "player.look", {"yaw": 0, "pitch": 0}),
    ("mcb_flight", "player.flight", {"action": "set", "flying": True, "expectedWorld": "world"}),
    ("mcb_chat", "chat.send", {"message": "/clear TestPlayer"}),
]


@pytest.mark.parametrize("tool,operation,args", CASES)
async def test_native_control_fuses_two_http_guard_reads_without_changing_receipt(guarded_bridge, tool, operation, args):
    adapter, state = guarded_bridge
    steps = plan({"id": "act", "kind": "action", "tool": tool, "args": args}, read("after"))
    state["supported"]["actions"].remove(operation)
    legacy = await adapter.call_tool("mcb_sequence", steps)
    legacy_calls = copy.deepcopy(state["calls"])
    assert legacy.structuredContent["data"]["status"] == "completed"
    assert state["actions"] == 1
    state["calls"].clear()
    state["supported"]["actions"].append(operation)
    current = await adapter.call_tool("mcb_sequence", steps)
    data = current.structuredContent["data"]
    assert data["status"] == "completed"
    assert data["acknowledgedActions"] == data["dispatchedActions"] == 1
    assert state["actions"] == 2
    assert len(legacy_calls) == len(state["calls"]) + 2
    native = [call for call in state["calls"] if call["operation"] == operation]
    assert len(native) == 1 and native[0]["includeContext"] == directive()
    assert not any(call["operation"] == "client.snapshot" for call in state["calls"])


@pytest.mark.parametrize("tool,operation,args", CASES)
async def test_current_context_failure_precedes_native_callback(guarded_bridge, tool, operation, args):
    adapter, state = guarded_bridge
    state["profile"] = "player"
    with request_context(directive()), pytest.raises(BridgeError):
        await adapter.client.call(operation, args)
    assert state["actions"] == 0
    assert len(state["calls"]) == 1


@pytest.mark.parametrize("tool,operation,args", CASES)
async def test_missing_native_context_reports_unknown_and_never_replays(guarded_bridge, tool, operation, args):
    adapter, state = guarded_bridge
    state["bad_context"] = "missing"
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "act", "kind": "action", "tool": tool, "args": args}, read("after")))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["outcomeUnknown"] is True
    assert state["actions"] == 1
    assert len([call for call in state["calls"] if call["operation"] == operation]) == 1
    assert not any(call["operation"] == "inventory.inspect" for call in state["calls"])


@pytest.mark.parametrize("tool,operation,args", CASES)
async def test_context_phase_stays_before_even_if_native_action_changes_world(guarded_bridge, tool, operation, args):
    adapter, state = guarded_bridge
    state["after_action"] = lambda actual: actual.update(world="changed")
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "act", "kind": "action", "tool": tool, "args": args}, read("after")))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["outcomeUnknown"] is False
    assert data["acknowledgedActions"] == 1 and state["actions"] == 1
    assert len([call for call in state["calls"] if call["operation"] == operation]) == 1


@pytest.mark.parametrize("tool,operation,args", CASES)
async def test_terminal_native_before_guard_does_not_reuse_an_earlier_after_guard(guarded_bridge, tool, operation, args):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [{"id": "first", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 1}}])
    state["calls"].clear()
    await task.workflow("native_tail", [read("before"), {"id": "act", "kind": "action", "tool": tool, "args": args}])
    assert [call["operation"] for call in state["calls"]] == ["inventory.inspect", operation, "client.snapshot"]
    assert state["calls"][1]["includeContext"] == directive()
