"""Declared workflow contracts over the real HTTP transport, including cancellation."""

import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOL_BY_NAME, TOOLS, SEQUENCE_READ_NAMES
from minecraft_codex_bridge.workflows import condition_matches, project, _metadata

PROTECTED = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
MANUAL = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
POSITION = {"x": 25, "y": 19, "z": 144}


@pytest.fixture
async def bridge():
    state = {"calls": [], "session": "session", "profile": "developer", "world": "test-world", "background": MANUAL.copy(),
             "caps": [spec.operation for spec in TOOLS if spec.operation], "data": {}, "slow": None,
             "after_action": None, "action_error": None, "entered": asyncio.Event(), "cancel_status": "cancelled", "counter": 0,
             "health_flags": {}}
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "workflow-test-token"})
    async def respond(request):
        body = json.loads(request.content)
        operation = "__cancel__" if request.url.path == "/v1/cancel" else body["operation"]
        state["calls"].append({**body, "operation": operation})
        if operation == "__cancel__":
            data = {"status": state["cancel_status"]}
        elif operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": state["session"], "profile": state["profile"], "capabilities": state["caps"], "background": state["background"].copy(), **state["health_flags"]}
        elif operation == "client.snapshot":
            data = {"sessionId": state["session"], "profile": state["profile"], "worldLoaded": True,
                    "world": {"worldName": state["world"], "worldFolder": state["world"]}, "background": state["background"].copy(), "player": {"health": 20}}
        elif operation == "client.background":
            state["background"] = PROTECTED.copy()
            data = state["background"]
        else:
            state["entered"].set()
            if state["slow"] == operation:
                await asyncio.sleep(10)
            if state["action_error"] and operation not in {"gui.inspect", "inventory.inspect", "machine.inspect"}:
                if state["action_error"] == "malformed":
                    return httpx.Response(200, json={"ok": True, "data": {}})
                if state["action_error"] == "disconnect":
                    raise httpx.ReadError("fixture disconnected", request=request)
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": state["action_error"]})
            data = state["data"].get(operation, {"status": "submitted", "args": body["args"]})
            if callable(data):
                data = data()
            if state["after_action"] and operation.startswith(("player.", "gui.key", "machine.set")):
                state["after_action"](config)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})
    client = BridgeClient(config, timeout=0.3, transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def plan(*steps, **bounds):
    return {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": list(steps), **bounds}


def read(identifier="before", tool="mcb_gui", **options):
    return {"id": identifier, "kind": "read", "tool": tool, **options}


def action(identifier="act", tool="mcb_hotbar", args=None, **options):
    return {"id": identifier, "kind": "action", "tool": tool, "args": args if args is not None else {"slot": 1}, **options}


def operations(state):
    return [call["operation"] for call in state["calls"]]


async def test_dynamic_gui_binding_uses_raw_result_and_takes_over_only_once(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "actual-screen", "windowId": 7, "slots": [{"slot": 0, "item": None}, {"slot": 91, "item": {"registryName": "mod:item", "nbt": "exact-NBT"}}], "coverage": {"unsupported": False}}
    result = await adapter.call_tool("mcb_sequence", plan(read(fields=["slots"]),
        action(tool="mcb_gui_key", args={"keyCode": 1}, bind={"screenId": {"step": "before", "path": "screenId"}}),
        action("second", tool="mcb_slot_click", args={"slot": 91}, bind={"windowId": {"step": "before", "path": "windowId"}})))
    data = result.structuredContent["data"]
    assert not result.isError and data["completedSteps"] == 3 and data["acknowledgedActions"] == 2
    assert operations(state).count("client.background") == 1
    assert operations(state).count("bridge.health") == 2
    assert [call["args"] for call in state["calls"] if call["operation"] == "gui.key"] == [{"keyCode": 1, "screenId": "actual-screen"}]
    projected = data["steps"][0]["observation"]
    assert projected["values"]["slots"][0]["slot"] == 91 and projected["emptySlotFiltering"][0]["omittedEmpty"] == 1
    assert "screenId" not in projected["values"]  # Binding comes from raw, not projection.


def test_nested_inventory_compacts_output_without_changing_raw_quantitative_baseline():
    data = {"inventory": {"totalSlots": 65, "truncated": False, "slots":
        [{"index": index, "item": {"registryName": "minecraft:chest", "count": 6} if index == 23 else None} for index in range(65)]}}
    compact = project(data, {"kind": "read", "tool": "mcb_world_inspect", "fields": ["inventory"]})
    assert compact["values"]["inventory"] == {"totalSlots": 65, "truncated": False,
        "slots": [{"index": 23, "item": {"registryName": "minecraft:chest", "count": 6}}]}
    assert compact["emptySlotFiltering"] == [{"path": "inventory.slots", "originalEntries": 65, "omittedEmpty": 64, "indicesPreserved": True}]
    assert len(data["inventory"]["slots"]) == 65
    assert project(data, {"fields": ["inventory"], "omitEmptySlots": False})["values"]["inventory"] == data["inventory"]


@pytest.mark.parametrize("bad", [
    action("bad", args={"slot": 9}), read("bad", tool="mcb_hotbar"), action("bad", tool="mcb_gui", args={}),
    {"id": "bad", "kind": "wait", "tool": "mcb_input", "args": {"keys": ["use"], "ticks": 1}, "condition": {"path": "status", "operator": "exists"}},
    action("bad", tool="mcb_gui_key", args={"keyCode": 1}, bind={"screenId": {"step": "future", "path": "screenId"}}),
    action("bad", tool="mcb_gui_key", args={"keyCode": 1, "screenId": "literal"}, bind={"screenId": {"step": "first", "path": "screenId"}}),
    action("bad", tool="mcb_ae_action", args={"screenId": "s", "action": "pattern_clear"}, bind={"action": {"step": "first", "path": "status"}}),
    {"id": "bad", "kind": "assert", "source": "first", "condition": {"path": "status", "operator": "gt", "value": True}},
    {"id": "first", "kind": "read", "tool": "mcb_gui"},
])
async def test_invalid_later_step_blocks_the_entire_plan_before_http(bridge, bad):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(action("first"), bad))
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


async def test_missing_or_invalid_bound_identity_stops_after_completed_reads(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": None}
    result = await adapter.call_tool("mcb_sequence", plan(read(), action(tool="mcb_gui_key", args={"keyCode": 1}, bind={"screenId": {"step": "before", "path": "screenId"}}), action("later")))
    data = result.structuredContent["data"]
    assert result.isError and [step["status"] for step in data["steps"]] == ["completed", "failed", "not_executed"]
    assert data["error"]["code"] == "INVALID_ARGUMENTS" and "gui.key" not in operations(state)
    state["data"]["gui.inspect"] = {}
    result = await adapter.call_tool("mcb_sequence", plan(read(), action(tool="mcb_gui_key", args={"keyCode": 1}, bind={"screenId": {"step": "before", "path": "screenId"}})))
    assert result.structuredContent["data"]["error"]["code"] == "MISSING_BINDING"


async def test_wait_compares_fresh_counter_to_raw_baseline_and_does_not_repeat_actions(bridge):
    adapter, state = bridge
    def machine():
        state["counter"] += 1
        return {"metaTileId": 19056, "processing": {"recipesDone": 9_007_199_254_740_993 + state["counter"]}, "coverage": {"machineSpecific": "partial"}}
    state["data"]["machine.inspect"] = machine
    steps = [read(tool="mcb_machine_inspect", args=POSITION, fields=[]),
        {"id": "identity", "kind": "assert", "source": "before", "condition": {"path": "metaTileId", "operator": "equals", "value": 19056}},
        action(args={"slot": 2}), {"id": "increased", "kind": "wait", "tool": "mcb_machine_inspect", "args": POSITION,
        "condition": {"path": "processing.recipesDone", "operator": "gt", "valueFrom": {"step": "before", "path": "processing.recipesDone"}},
        "interval_ms": 50, "fields": ["processing.recipesDone"]}]
    result = await adapter.call_tool("mcb_sequence", plan(*steps))
    assert not result.isError
    assert result.structuredContent["data"]["steps"][-1]["observation"]["values"]["processing.recipesDone"] == 9_007_199_254_740_995
    assert operations(state).count("player.hotbar") == 1


@pytest.mark.parametrize("operator,left,right,matched", [("not_equals", True, 1, True), ("equals", True, 1, False),
    ("gt", "9223372036854775807", "9007199254740993", True), ("gte", 2, 2, True), ("lt", -3, 0, True), ("lte", 0, 0, True)])
def test_conditions_preserve_json_types_and_decimal_precision(operator, left, right, matched):
    assert condition_matches({"value": left}, {"path": "value", "operator": operator, "value": right}) is matched
    assert condition_matches({"value": None}, {"path": "value", "operator": "exists"})
    assert not condition_matches({}, {"path": "value", "operator": "not_equals", "value": None})


@pytest.mark.parametrize("copies,code", [(0, "MISSING_SELECTION"), (1, None), (2, "AMBIGUOUS_SELECTION")])
async def test_unique_live_selection_binds_real_slot_without_model_round_trip(bridge, copies, code):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"windowId": 7, "slots": [{"slot": 103, "item": {"registryName": "mod:item", "metadata": 3, "count": 8}}] * copies}
    result = await adapter.call_tool("mcb_sequence", plan(read(), action(tool="mcb_slot_click", args={"mode": 1, "button": 0}, bind={
        "windowId": {"step": "before", "path": "windowId"},
        "slot": {"step": "before", "path": "slots", "match": {"item.registryName": "mod:item", "item.metadata": 3, "item.count": 8}, "field": "slot"}})))
    if code:
        assert result.isError and result.structuredContent["data"]["error"]["code"] == code
        assert "gui.slot_click" not in operations(state)
    else:
        assert not result.isError
        assert [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"] == [{"windowId": 7, "slot": 103, "mode": 1, "button": 0}]


async def test_delayed_inventory_wait_and_bind_avoid_another_model_turn(bridge):
    adapter, state = bridge
    reads = 0
    def inventory():
        nonlocal reads
        reads += 1
        return {"windowId": 7, "slots": [] if reads == 1 else [
            {"slot": 103, "item": {"registryName": "mod:item", "metadata": 3, "count": 8}}]}
    state["data"]["gui.inspect"] = inventory
    match = {"item.registryName": "mod:item", "item.metadata": 3, "item.count": 8}
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "synced", "kind": "wait", "tool": "mcb_gui", "condition": {
            "path": "slots", "operator": "matches_one", "value": match}, "interval_ms": 50, "timeout_ms": 500},
        action(tool="mcb_slot_click", args={"button": 0, "mode": 0}, bind={
            "windowId": {"step": "synced", "path": "windowId"},
            "slot": {"step": "synced", "path": "slots", "match": match, "field": "slot"}})))
    assert not result.isError and reads == 2
    assert [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"] == [
        {"windowId": 7, "slot": 103, "button": 0, "mode": 0}]


@pytest.mark.parametrize("entries,expected", [
    ([], False), ([{"id": 1}], True), ([{"id": 1}, {"id": 1}], False),
    ([{"id": True}], False), ([{"other": 1}], False)])
def test_live_match_requires_one_exact_identity(entries, expected):
    assert condition_matches({"slots": entries}, {
        "path": "slots", "operator": "matches_one", "value": {"id": 1}}) is expected


@pytest.mark.parametrize("entries,expected", [([], True), ([{"id": 2}], True), ([{"id": 1}], False),
    ([{"id": 1}, {"id": 1}], False), ([{"id": True}], True)])
def test_consumed_item_check_uses_identity_after_native_slot_sorting(entries, expected):
    assert condition_matches({"slots": entries}, {
        "path": "slots", "operator": "matches_none", "value": {"id": 1}}) is expected


@pytest.mark.parametrize("cause", ["session", "world", "profile", "release", "runtime"])
async def test_session_world_profile_release_and_rotated_runtime_block_next_action(bridge, cause):
    adapter, state = bridge
    def change(config):
        if cause == "runtime":
            config.environ["MCB_TOKEN"] = "rotated-token"
        elif cause == "release":
            state["background"] = MANUAL.copy()
        else:
            state[cause] = "different"
    state["after_action"] = change
    result = await adapter.call_tool("mcb_sequence", plan(action("first"), action("second")))
    data = result.structuredContent["data"]
    assert result.isError and data["completedSteps"] == 1 and data["acknowledgedActions"] == 1
    assert operations(state).count("player.hotbar") == 1 and operations(state).count("client.background") == 1
    assert data["steps"][1]["status"] == "failed" and not data["outcomeUnknown"]
    assert "rotated-token" not in json.dumps(result.structuredContent)


@pytest.mark.parametrize("error,unknown,cancel_status", [("malformed", True, "cancelled"),
    ("disconnect", True, "already_running"), ("disconnect", False, "cancelled"),
    ({"code": "timeout", "message": "running", "outcome_unknown": True}, True, "cancelled"),
    ({"code": "stale_screen", "message": "refused"}, False, "cancelled")])
async def test_uncertain_action_failures_are_not_retried_and_do_not_continue(bridge, error, unknown, cancel_status):
    adapter, state = bridge
    state["action_error"] = error
    state["cancel_status"] = cancel_status
    result = await adapter.call_tool("mcb_sequence", plan(action(), action("later")))
    data = result.structuredContent["data"]
    assert result.isError and data["outcomeUnknown"] is unknown and data["rolledBack"] is False
    assert data["dispatchedActions"] == 1 and data["steps"][1]["status"] == "not_executed"
    assert operations(state).count("player.hotbar") == 1
    feedback_ids = {item["id"] for item in result.structuredContent["feedback"]["items"]}
    assert ("mcb.unknown_effects" in feedback_ids) is unknown


@pytest.mark.parametrize("cancel_status,unknown", [("cancelled", False), ("already_running", True), ("not_found", True)])
async def test_total_deadline_cancels_current_action_and_returns_partial_status(bridge, cancel_status, unknown):
    adapter, state = bridge
    state.update(slow="player.hotbar", cancel_status=cancel_status)
    result = await adapter.call_tool("mcb_sequence", plan(action(), action("later"), timeout_ms=30))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "WORKFLOW_TIMEOUT" and data["outcomeUnknown"] is unknown
    assert data["failedIndex"] == 0 and data["steps"][1]["status"] == "not_executed"
    calls = [call for call in state["calls"] if call["operation"] in {"player.hotbar", "__cancel__"}]
    assert len(calls) == 2 and calls[0]["requestId"] == calls[1]["requestId"]


@pytest.mark.parametrize("kind", ["read", "wait", "action"])
async def test_external_cancellation_propagates_and_never_dispatches_later_steps(bridge, kind):
    adapter, state = bridge
    first = action() if kind == "action" else read(tool="mcb_gui")
    if kind == "wait":
        first.update(kind="wait", condition={"path": "ready", "operator": "equals", "value": True})
    state["slow"] = "player.hotbar" if kind == "action" else "gui.inspect"
    task = asyncio.create_task(adapter.call_tool("mcb_sequence", plan(first, action("later"))))
    await asyncio.wait_for(state["entered"].wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert operations(state).count("__cancel__") == 1
    assert operations(state).count("player.hotbar") == (1 if kind == "action" else 0)


async def test_wait_timeout_preserves_last_partial_observation_and_leaves_stop_action_unexecuted(bridge):
    adapter, state = bridge
    state["data"]["machine.inspect"] = {"metaTileId": 19056, "ready": False, "hasMore": True, "nextOffset": 32, "coverage": {"partial": True}}
    wait = {"id": "wait", "kind": "wait", "tool": "mcb_machine_inspect", "args": POSITION, "timeout_ms": 30,
            "condition": {"path": "ready", "operator": "equals", "value": True}, "fields": ["ready"]}
    result = await adapter.call_tool("mcb_sequence", plan(action(), wait, action("stop")))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "WAIT_TIMEOUT" and data["acknowledgedActions"] == 1
    assert data["steps"][2]["status"] == "not_executed"
    assert data["steps"][1]["observation"]["metadata"]["nextOffset"] == 32


def stop_cleanup(**args):
    return [{"id": "safety_stop", "tool": "mcb_machine_work", "args": {
        **POSITION, "expectedWorld": "test-world", "expectedMetaTileId": 19056, "working": False, **args}}]


@pytest.mark.parametrize("wait_timeout", [False, True])
async def test_declared_stop_cleanup_runs_after_success_and_wait_timeout(bridge, wait_timeout):
    adapter, state = bridge
    state["data"]["machine.inspect"] = {"ready": not wait_timeout}
    wait = {"id": "wait", "kind": "wait", "tool": "mcb_machine_inspect", "args": POSITION,
        "timeout_ms": 30, "condition": {"path": "ready", "operator": "equals", "value": True}}
    result = await adapter.call_tool("mcb_sequence", plan(action(), wait, cleanup=stop_cleanup()))
    data = result.structuredContent["data"]
    assert result.isError is wait_timeout
    assert data["cleanupStatus"] == "completed" and data["cleanup"][0]["status"] == "completed"
    assert data["acknowledgedActions"] == data["dispatchedActions"] == 2
    assert operations(state).count("machine.set_working") == 1
    assert [call["args"]["working"] for call in state["calls"] if call["operation"] == "machine.set_working"] == [False]


async def test_total_deadline_has_separate_bounded_cleanup_budget(bridge):
    adapter, state = bridge
    state.update(slow="player.hotbar", cancel_status="already_running")
    result = await adapter.call_tool("mcb_sequence", plan(action(), timeout_ms=30, cleanup=stop_cleanup()))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "WORKFLOW_TIMEOUT"
    assert data["outcomeUnknown"] and data["cleanupStatus"] == "completed"
    assert operations(state).count("player.hotbar") == operations(state).count("machine.set_working") == 1


@pytest.mark.parametrize("cause", ["session", "world", "profile", "release", "runtime"])
async def test_cleanup_never_bypasses_changed_context_or_reacquires_takeover(bridge, cause):
    adapter, state = bridge
    def changed(config):
        if cause == "runtime":
            config.environ["MCB_TOKEN"] = "rotated-token"
        elif cause == "release":
            state["background"] = MANUAL.copy()
        else:
            state[cause] = "different"
    state["after_action"] = changed
    result = await adapter.call_tool("mcb_sequence", plan(action(), cleanup=stop_cleanup()))
    data = result.structuredContent["data"]
    assert result.isError and data["cleanupStatus"] == "failed"
    assert "machine.set_working" not in operations(state)
    assert operations(state).count("client.background") == 1


@pytest.mark.parametrize("args", [{"working": True}, {"expectedWorld": "different"}])
async def test_invalid_cleanup_prevalidates_before_any_http(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(action(), cleanup=stop_cleanup(**args)))
    assert result.isError and not state["calls"]


async def test_cleanup_is_not_run_after_preflight_refusal(bridge):
    adapter, state = bridge
    state["caps"].remove("player.hotbar")
    result = await adapter.call_tool("mcb_sequence", plan(action(), cleanup=stop_cleanup()))
    data = result.structuredContent["data"]
    assert result.isError and data["cleanupStatus"] == "not_needed"
    assert data["cleanup"][0]["status"] == "not_executed" and "machine.set_working" not in operations(state)


async def test_cleanup_can_be_armed_only_after_the_declared_start_action(bridge):
    adapter, state = bridge
    cleanup = stop_cleanup()
    cleanup[0]["afterStep"] = "start"
    state["data"]["gui.inspect"] = {"ready": False}
    steps = [action("position"), {"id": "ready", "kind": "assert", "source": "position", "condition": {"path": "ready", "operator": "equals", "value": True}}, action("start")]
    result = await adapter.call_tool("mcb_sequence", plan(*steps, cleanup=cleanup))
    assert result.isError and result.structuredContent["data"]["cleanupStatus"] == "not_needed"
    assert "machine.set_working" not in operations(state)
    result = await adapter.call_tool("mcb_sequence", plan(action("start"), cleanup=cleanup))
    assert not result.isError and result.structuredContent["data"]["cleanupStatus"] == "completed"


async def test_cleanup_timeout_is_reported_without_retry_or_false_success(bridge):
    adapter, state = bridge
    state["slow"] = "machine.set_working"
    result = await adapter.call_tool("mcb_sequence", plan(action(), cleanup=stop_cleanup(), cleanup_timeout_ms=100))
    data = result.structuredContent["data"]
    assert result.isError and data["cleanupStatus"] == "failed"
    assert data["error"]["code"] == "CLEANUP_TIMEOUT" and data["cleanup"][0]["status"] == "failed"
    assert operations(state).count("machine.set_working") == 1


async def test_observe_is_read_only_single_capability_discovery_and_exact_typed_projection(bridge):
    adapter, state = bridge
    identity = {"resourceId": "actual-resource", "amount": "9223372036854775807", "identityNbt": "NBT" * 1000, "identityTruncated": False}
    state["data"]["ae.inspect"] = {"entries": [identity], "hasMore": True, "nextOffset": 128, "coverage": {"partial": True}, "byteLimited": False}
    result = await adapter.call_tool("mcb_observe", {"views": [{"id": "ae", "tool": "mcb_ae", "args": {"screenId": "actual"}, "fields": ["entries", "absent"]}]})
    projected = result.structuredContent["data"]["steps"][0]["observation"]
    assert not result.isError and projected["values"]["entries"] == [identity] and projected["missingFields"] == ["absent"]
    assert projected["metadata"]["nextOffset"] == 128 and projected["coverageSummary"]["partial"]
    assert operations(state) == ["bridge.health", "ae.inspect", "bridge.health"]
    assert state["background"] == MANUAL


async def test_return_budget_omits_whole_payload_never_truncates_nbt_or_hides_action_count(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "actual", "nbt": "identity" * 10000, "hasMore": True, "nextOffset": 100, "byteLimited": True}
    result = await adapter.call_tool("mcb_sequence", plan(read(fields=["nbt"]), action(), max_return_bytes=16384))
    data = result.structuredContent["data"]
    assert not result.isError and json_size(data) <= 16384 and data["outputOmitted"]
    assert data["steps"][0]["observation"]["omitted"] and data["steps"][0]["observation"]["coverageSummary"]["partial"]
    assert data["acknowledgedActions"] == 1 and data["steps"][1]["status"] == "completed"


def test_new_tools_are_not_recursive_wait_or_takeover_operations():
    assert len(TOOLS) == len(TOOL_BY_NAME)
    assert "mcb_recipe_once" not in SEQUENCE_READ_NAMES
    assert TOOL_BY_NAME["mcb_recipe_once"].operation is None
    assert "mcb_blueprint_build" not in SEQUENCE_READ_NAMES
    assert TOOL_BY_NAME["mcb_blueprint_build"].operation is None
    assert "mcb_placement_preflight" not in SEQUENCE_READ_NAMES
    assert "mcb_placement_preflight" not in TOOL_BY_NAME["mcb_wait_for"].schema["properties"]["tool"]["enum"]
    preflight = TOOL_BY_NAME["mcb_placement_preflight"]
    assert preflight.operation is None and preflight.read_only is True
    assert preflight.takeover is False and preflight.idempotent is False
    assert TOOL_BY_NAME["mcb_sequence"].read_only is False and TOOL_BY_NAME["mcb_sequence"].idempotent is False
    assert TOOL_BY_NAME["mcb_observe"].read_only is True


def test_action_projection_keeps_receipt_without_resending_large_gui_tree():
    data = {"submitted": True, "screenId": "screen", "screenKind": "creative_inventory",
        "before": {"tooltip": ["large"] * 1000}, "slots": [{"slot": 1, "item": {"registryName": "mod:item"}}],
        "coverage": {"partial": True}}
    compact = project(data, {"kind": "action", "tool": "mcb_gui_widget_action"})
    assert compact["values"] == {"submitted": True, "screenId": "screen", "screenKind": "creative_inventory"}
    assert compact["coverageSummary"]["partial"]
    detailed = project(data, {"kind": "action", "tool": "mcb_gui_widget_action", "fields": ["before", "slots"]})
    assert detailed["values"]["before"] == data["before"]
    assert detailed["values"]["slots"] == data["slots"]


def counted_inventory(count, slot=0, truncated=False):
    slots = [{"slot": index, "item": None} for index in range(4)]
    if count:
        slots[slot]["item"] = {"registryName": "minecraft:chest", "metadata": 0, "count": count}
    return {"inventory": {"totalSlots": 4, "slots": slots, "truncated": truncated}}


def item_delta_wait():
    return {"id": "produced", "kind": "wait", "tool": "mcb_world_inspect", "args": POSITION,
        "condition": {"path": "inventory.slots", "operator": "item_count_delta", "value": 1,
            "match": {"item.registryName": "minecraft:chest", "item.metadata": 0},
            "baseline": {"step": "baseline", "path": "inventory.slots"}},
        "timeout_ms": 1000, "interval_ms": 50}


@pytest.mark.parametrize("initial", [0, 2, 9_007_199_254_740_995])
async def test_item_delta_wait_counts_real_output_after_sorting_without_an_agent_baseline_roundtrip(bridge, initial):
    adapter, state = bridge
    observations = iter([counted_inventory(initial), counted_inventory(initial, 2), counted_inventory(initial + 1, 1)])
    state["data"]["world.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", plan(read("baseline", tool="mcb_world_inspect", args=POSITION),
        action(), item_delta_wait()))
    data = result.structuredContent["data"]
    assert not result.isError and data["completedSteps"] == 3
    assert data["steps"][2]["attempts"] == 2
    assert data["steps"][2]["quantityDelta"] == {"baseline": str(initial), "current": str(initial + 1),
        "delta": "1", "expectedDelta": "1", "observedDelta": "1",
        "match": {"item.registryName": "minecraft:chest", "item.metadata": 0}}
    assert operations(state).count("world.inspect") == 3
    assert data["acknowledgedActions"] == 1


@pytest.mark.parametrize("invalid", [counted_inventory(2, truncated=True),
    {"inventory": {"totalSlots": 4, "slots": [{"slot": 0, "item": None}], "truncated": False}}])
async def test_item_delta_incomplete_baseline_blocks_later_actions(bridge, invalid):
    adapter, state = bridge
    state["data"]["world.inspect"] = invalid
    result = await adapter.call_tool("mcb_sequence", plan(read("baseline", tool="mcb_world_inspect", args=POSITION),
        action(), item_delta_wait()))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "INCOMPLETE_OBSERVATION"
    assert data["dispatchedActions"] == 0 and "player.hotbar" not in operations(state)


async def test_item_delta_forward_reference_rejected_before_http(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(item_delta_wait(),
        read("baseline", tool="mcb_world_inspect", args=POSITION)))
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("tool,data,expected", [
    ("mcb_inventory", {"selectedHotbarSlot": 2, "cursorItem": None, "slots": []}, "selectedHotbarSlot"),
    ("mcb_gui", {"screenId": "s", "creativeInventory": {"inventoryTabWidgetId": "tab"}}, "creativeInventory"),
    ("mcb_gui_widgets", {"screenId": "s", "widgets": [{"widgetId": "w"}]}, "widgets"),
    ("mcb_machine_inspect", {"processing": {"structureFormed": True}}, "processing.structureFormed"),
    ("mcb_world_inspect", {"registryName": "minecraft:air"}, "registryName"),
    ("mcb_item", {"registryName": "mod:item", "tooltip": ["actual"]}, "registryName"),
    ("mcb_item_variants", {"items": [{"metadata": 1}]}, "items"),
    ("mcb_nei", {"actualField": 42}, "actualField"),
])
def test_default_projection_uses_actual_dto_names_or_bounded_root_fallback(tool, data, expected):
    assert expected in project(data, {"kind": "read", "tool": tool})["values"]


def test_assertion_does_not_repeat_entire_source_observation_by_default():
    data = {"state": {"active": False}, "largeRecipe": ["x" * 4096] * 16}
    assert project(data, {"kind": "assert"})["values"] == {}
    assert project(data, {"kind": "assert", "fields": ["state.active"]})["values"] == {"state.active": False}


@pytest.mark.parametrize("available", [False, True])
def test_structure_projection_preserves_discovery_and_unknown_world_location(available):
    definition = {"pieceDiscoveryAvailable": available,
                  "pieceDiscoveryReason": "Public interfaces do not expose controller offsets"}
    pieces = [{"name": "piece", "offsets": None, "worldPositionsAvailable": False}]
    data = {"mode": "definition_discovery", "definition": definition, "pieces": pieces,
            "positions": [], "offsets": None, "coordinateSpace": "piece_origin",
            "worldPositionsAvailable": False, "piecesTotal": 1 if available else None,
            "coverage": {"pieceOffsets": "Unavailable from public interfaces"},
            "budget": {"softBudgetMicros": 4000, "softBudgetExceeded": False}}
    observed = project(data, {"kind": "read", "tool": "mcb_world_structure"})["values"]
    assert observed["definition"] == definition and observed["pieces"] == pieces
    assert observed["offsets"] is None and observed["worldPositionsAvailable"] is False
    assert observed["coordinateSpace"] == "piece_origin" and observed["positions"] == []
    assert observed["piecesTotal"] == data["piecesTotal"]
    assert observed["coverage"] == data["coverage"] and observed["budget"] == data["budget"]


def test_machine_projection_keeps_component_identity_and_unavailable_roles():
    components = {"roles": [{"role": "execution_core", "count": None}],
                  "entries": [{"metaTileId": 18845, "x": 25, "y": 27, "z": 144,
                               "quantity": "9223372036854775807"}],
                  "unavailableRoles": [{"role": "upgrade", "reason": "No public getter"}],
                  "offset": 0, "nextOffset": 1, "hasMore": True}
    coverage = {"modules": "Registered modules only; complete physical layout unavailable"}
    observed = project({"components": components, "coverage": coverage}, {"kind": "read", "tool": "mcb_machine_inspect"})
    assert observed["values"]["components"] == components
    assert observed["values"]["coverage"] == coverage
    assert observed["metadata"]["components.nextOffset"] == 1
    assert observed["coverageSummary"]["pagination"]


def test_machine_projection_keeps_standalone_public_module_data():
    core = {"idle": True, "working": False, "eut": "9223372036854775807",
            "needProgressingTimeTicks": 17,
            "mainMachine": {"available": True, "x": 25, "y": 19, "z": 144}}
    observed = project({"moduleType": "EXECUTION_CORE", "executionCore": core},
                       {"kind": "read", "tool": "mcb_machine_inspect"})["values"]
    assert observed["moduleType"] == "EXECUTION_CORE"
    assert observed["executionCore"] == core
    assert "controllerIdentityMatches" not in observed["executionCore"]["mainMachine"]


def test_small_metadata_progresses_when_soft_deadline_expires(monkeypatch):
    times = iter([0.0] + [100.0] * 100)
    monkeypatch.setattr("minecraft_codex_bridge.workflows.time.monotonic", lambda: next(times))
    metadata, summary = _metadata({"components": {"entries": [{"id": "core"}], "nextOffset": 1, "hasMore": True}})
    assert metadata["components.nextOffset"] == 1
    assert metadata["components.hasMore"] is True
    assert summary["pagination"] is True


@pytest.mark.parametrize("expired", [False, True])
def test_metadata_deadline_reports_omitted_fields_after_minimum_progress(monkeypatch, expired):
    from minecraft_codex_bridge import workflows

    times = iter([0.0] + [100.0 if expired else 0.0] * 1000)
    monkeypatch.setattr(workflows, "time", SimpleNamespace(monotonic=lambda: next(times)))
    data = {"hasMore": False, "entries": [{"source": "native_tooltip_observer"}] * 128}
    metadata, summary = _metadata(data)
    assert metadata["hasMore"] is False
    assert metadata["entries.0.source"] == "native_tooltip_observer"
    assert ("entries.127.source" not in metadata) is expired
    assert summary["metadataOmitted"] is expired
    assert summary["unknownOrUnloaded"] is expired


def test_public_descriptions_and_piece_bounds_are_in_default_observations():
    text = {"available": True, "entries": ["Input bus"], "truncated": False}
    values = project({"structureDescription": text, "machineDescription": text, "structureTooltip": text},
                     {"kind": "read", "tool": "mcb_machine_inspect"})["values"]
    assert values == {"structureDescription": text, "machineDescription": text, "structureTooltip": text}
    bounds = {"complete": False, "partial": True, "scannedEntries": 2048}
    observed = project({"pieceBounds": bounds}, {"kind": "read", "tool": "mcb_world_structure"})
    assert observed["values"]["pieceBounds"] == bounds
    assert observed["coverageSummary"]["partial"] is True


def test_metadata_traversal_is_bounded_and_pagination_does_not_mistake_end_offset():
    metadata, summary = _metadata({"hasMore": False, "nextOffset": 4, "entries": list(range(20_000))})
    assert not summary["pagination"] and summary["metadataOmitted"]
    assert metadata["nextOffset"] == 4


def test_repeated_false_control_flags_are_summarized_but_true_flags_and_root_flags_remain():
    metadata, summary = _metadata({"truncated": False, "widgets": [
        {"childIdsTruncated": False}, {"childIdsTruncated": True}, {"nbtTruncated": False}]})
    assert metadata["truncated"] is False and metadata["widgets.1.childIdsTruncated"] is True
    assert "widgets.0.childIdsTruncated" not in metadata and "widgets.2.nbtTruncated" not in metadata
    assert summary["partial"] and summary["falseFlagsSummarized"] and not summary["metadataOmitted"]


@pytest.mark.parametrize("kind", ["read", "wait"])
async def test_world_changes_during_observation_block_later_steps(bridge, kind):
    adapter, state = bridge
    def changed():
        state["world"] = "different"
        return {"ready": True}
    state["data"]["gui.inspect"] = changed
    step = read()
    if kind == "wait":
        step.update(kind="wait", condition={"path": "ready", "operator": "equals", "value": True})
    result = await adapter.call_tool("mcb_sequence", plan(step, action("later")))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "WORLD_CHANGED"
    assert "player.hotbar" not in operations(state)


async def test_expected_world_observe_rejects_changed_world(bridge):
    adapter, state = bridge
    def changed():
        state["world"] = "different"
        return {"screenId": "new"}
    state["data"]["gui.inspect"] = changed
    result = await adapter.call_tool("mcb_observe", {"expectedWorld": "test-world", "views": [
        {"id": "gui", "tool": "mcb_gui"}, {"id": "later", "tool": "mcb_inventory"}]})
    assert result.isError and result.structuredContent["data"]["error"]["code"] == "WORLD_CHANGED"
    assert "inventory.inspect" not in operations(state)


async def test_minimum_return_budget_preserves_all_32_action_receipts(bridge):
    adapter, state = bridge
    state["data"]["player.hotbar"] = {"huge": "x" * 60000, "coverage": {f"partial{i}": True for i in range(256)}}
    result = await adapter.call_tool("mcb_sequence", plan(*[action(f"action_{i:024d}") for i in range(32)], max_return_bytes=16384))
    data = result.structuredContent["data"]
    assert not result.isError and json_size(data) <= 16384
    assert data["acknowledgedActions"] == 32 and data["outputOmitted"]
    assert len(data["steps"]) == 32 and all(step["status"] == "completed" and step["requestId"] for step in data["steps"])


async def test_world_changes_after_last_action_fail_completion_boundary(bridge):
    adapter, state = bridge
    state["after_action"] = lambda config: state.update(world="different")
    result = await adapter.call_tool("mcb_sequence", plan(action()))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "WORLD_CHANGED"
    assert data["steps"][0]["status"] == "completed" and data["acknowledgedActions"] == 1


async def test_old_mod_blocks_scoped_sneak_before_takeover_or_action(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(action(tool="mcb_interact", args={"action": "use", "sneaking": True})))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"]
    assert not data["dispatchedActions"]


async def test_scoped_sneak_individual_tool_requires_new_mod(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_interact", {"action": "use", "sneaking": True})
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert "player.interact" not in operations(state) and "client.background" not in operations(state)


@pytest.mark.parametrize("health_flags", [{}, {"interactionBlockTargeting": False}])
@pytest.mark.parametrize("kind", ["standalone", "workflow"])
@pytest.mark.parametrize("interaction", ["use", "attack"])
async def test_old_mod_blocks_explicit_block_before_takeover_or_any_action(bridge, health_flags, kind, interaction):
    adapter, state = bridge
    state["health_flags"] = health_flags
    args = {"action": interaction, **POSITION}
    if kind == "workflow":
        result = await adapter.call_tool("mcb_sequence", plan(action("earlier"), action(tool="mcb_interact", args=args)))
        data = result.structuredContent["data"]
        error = data["error"]
        assert data["completedSteps"] == data["dispatchedActions"] == data["acknowledgedActions"] == 0
        assert all(step["status"] == "not_executed" for step in data["steps"])
        assert not data["takeoverEstablished"] and not data["outcomeUnknown"]
    else:
        result = await adapter.call_tool("mcb_interact", args)
        error = result.structuredContent["error"]
        assert error["blocked_operation"] == "player.interact" and error["original_action_executed"] is False
    assert result.isError and error["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"]
    assert state["background"] == MANUAL


@pytest.mark.parametrize("health_flags", [{}, {"interactionBlockTargeting": False}])
@pytest.mark.parametrize("bound_keys", [("x",), ("y",), ("z",), ("x", "y", "z")])
async def test_old_mod_blocks_bound_block_coordinates_during_plan_preflight(bridge, health_flags, bound_keys):
    adapter, state = bridge
    state["health_flags"] = health_flags
    args = {"action": "use", **{key: value for key, value in POSITION.items() if key not in bound_keys}}
    bindings = {key: {"step": "before", "path": key} for key in bound_keys}
    result = await adapter.call_tool("mcb_sequence", plan(read(tool="mcb_world_inspect", args=POSITION),
        action(tool="mcb_interact", args=args, bind=bindings)))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert data["completedSteps"] == data["dispatchedActions"] == 0
    assert all(step["status"] == "not_executed" for step in data["steps"])
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("kind", ["standalone", "workflow"])
@pytest.mark.parametrize("interaction", ["use", "attack"])
async def test_new_mod_allows_explicit_block_after_confirmed_takeover(bridge, kind, interaction):
    adapter, state = bridge
    state["health_flags"] = {"interactionBlockTargeting": True, "interactionViewBinding": True,
        "interactionAttackViewBinding": True}
    args = {"action": interaction, **POSITION, "side": 1}
    if kind == "workflow":
        result = await adapter.call_tool("mcb_sequence", plan(action(tool="mcb_interact", args=args)))
        data = result.structuredContent["data"]
        assert data["completedSteps"] == data["acknowledgedActions"] == 1
    else:
        result = await adapter.call_tool("mcb_interact", args)
    assert not result.isError
    calls = [call for call in state["calls"] if call["operation"] == "player.interact"]
    assert len(calls) == 1 and calls[0]["args"] == args
    assert operations(state).count("client.background") == 1
    assert operations(state).index("client.background") < operations(state).index("player.interact")


@pytest.mark.parametrize("bound_keys", [("x",), ("y",), ("z",), ("x", "y", "z")])
async def test_new_mod_allows_block_coordinates_bound_from_unprojected_result(bridge, bound_keys):
    adapter, state = bridge
    state["health_flags"] = {"interactionBlockTargeting": True, "interactionViewBinding": True}
    state["data"]["world.inspect"] = {**POSITION, "registryName": "minecraft:stone"}
    args = {"action": "use", **{key: value for key, value in POSITION.items() if key not in bound_keys}}
    bindings = {key: {"step": "before", "path": key} for key in bound_keys}
    result = await adapter.call_tool("mcb_sequence", plan(read(tool="mcb_world_inspect", args=POSITION, fields=["registryName"]),
        action(tool="mcb_interact", args=args, bind=bindings)))
    data = result.structuredContent["data"]
    assert not result.isError and data["completedSteps"] == 2 and data["acknowledgedActions"] == 1
    assert data["steps"][0]["observation"]["values"] == {"registryName": "minecraft:stone"}
    calls = [call for call in state["calls"] if call["operation"] == "player.interact"]
    assert len(calls) == 1 and calls[0]["args"] == {"action": "use", **POSITION}
    assert operations(state).count("client.background") == 1


@pytest.mark.parametrize("paused", [False, True])
def test_default_projection_preserves_pause_and_interaction_capabilities(paused):
    snapshot = project({"paused": paused}, {"kind": "read", "tool": "mcb_snapshot"})
    assert snapshot["values"]["paused"] is paused
    health = {"interactionSneaking": True, "interactionBlockTargeting": True, "interactionViewBinding": True, "serverPauseGuard": True}
    assert project(health, {"kind": "read", "tool": "mcb_health"})["values"] == health


@pytest.mark.parametrize("tool,args,code", [
    ("mcb_machine_work", {**POSITION, "expectedWorld": "test-world", "expectedMetaTileId": 1, "working": False}, "world_paused"),
    ("mcb_interact", {"action": "use", **POSITION}, "blocked"),
])
async def test_definite_interaction_refusals_are_not_unknown_retried_or_continued(bridge, tool, args, code):
    adapter, state = bridge
    state["health_flags"] = {"interactionBlockTargeting": True, "interactionViewBinding": True}
    state["action_error"] = {"code": code, "message": "Refused before game action."}
    result = await adapter.call_tool("mcb_sequence", plan(action(tool=tool, args=args), action("later")))
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == code
    assert data["outcomeUnknown"] is False and data["steps"][0]["outcomeUnknown"] is False
    assert data["dispatchedActions"] == 1 and data["acknowledgedActions"] == 0
    assert [step["status"] for step in data["steps"]] == ["failed", "not_executed"]
    assert operations(state).count(TOOL_BY_NAME[tool].operation) == 1
    assert "player.hotbar" not in operations(state) and "__cancel__" not in operations(state)


@pytest.mark.parametrize("kind", ["standalone", "workflow"])
@pytest.mark.parametrize("restore", [None, False, True])
@pytest.mark.parametrize("interaction", ["use", "attack"])
async def test_view_binding_refuses_old_mod_before_actions(bridge, kind, restore, interaction):
    adapter, state = bridge
    state["health_flags"] = {"interactionBlockTargeting": True}
    args = {"action": interaction, **POSITION}
    if restore is not None:
        args["restoreView"] = restore
    if kind == "workflow":
        result = await adapter.call_tool("mcb_sequence", plan(action("earlier"), action(tool="mcb_interact", args=args)))
        error = result.structuredContent["data"]["error"]
        assert result.structuredContent["data"]["dispatchedActions"] == 0
    else:
        result = await adapter.call_tool("mcb_interact", args)
        error = result.structuredContent["error"]
    assert result.isError and error["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("restore", [False, True])
@pytest.mark.parametrize("interaction", ["use", "attack"])
async def test_view_binding_passes_explicit_option_without_dropping_it(bridge, restore, interaction):
    adapter, state = bridge
    state["health_flags"] = {"interactionBlockTargeting": True, "interactionViewBinding": True, "interactionAttackViewBinding": True}
    args = {"action": interaction, **POSITION, "restoreView": restore}
    result = await adapter.call_tool("mcb_interact", args)
    assert not result.isError
    assert state["calls"][-1]["args"] == args


async def test_typed_gui_wait_uses_current_identity_after_an_unrelated_screen(bridge):
    adapter, state = bridge
    observations = iter([
        {"screenOpen": True, "screenKind": "inventory", "screenId": "previous-screen"},
        {"screenOpen": True, "screenKind": "modularui", "screenId": "current-screen"},
    ])
    state["data"]["gui.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "opened", "kind": "wait", "tool": "mcb_gui", "args": {},
         "condition": {"path": "screenKind", "operator": "equals", "value": "modularui"},
         "interval_ms": 50},
        action(tool="mcb_gui_key", args={"keyCode": 1},
               bind={"screenId": {"step": "opened", "path": "screenId"}})))
    assert not result.isError
    steps = result.structuredContent["data"]["steps"]
    assert steps[0]["attempts"] == 2
    assert steps[0]["observation"]["values"]["screenKind"] == "modularui"
    calls = [call for call in state["calls"] if call["operation"] == "gui.key"]
    assert len(calls) == 1 and calls[0]["args"]["screenId"] == "current-screen"


@pytest.mark.parametrize("size,budget,complete", [(2000,65536,True),(4000,16384,False)])
async def test_navigation_failure_retains_bounded_complete_diagnostic_without_replay(bridge,size,budget,complete):
    adapter,state=bridge
    message="Actual walking current_body is blocked. " + ("x" if complete else "\U0001f642")*size + " END_DIAGNOSTIC"
    state["action_error"]={"code":"unsupported","message":message}
    args={**POSITION,"expectedWorld":"test-world","expectedSessionId":"session","dimension":0,"strategy":"walking"}
    result=await adapter.call_tool("mcb_sequence",plan(action(tool="mcb_navigate",args=args),action("later"),max_return_bytes=budget))
    d=result.structuredContent["data"]
    assert result.isError and operations(state).count("player.navigate")==1 and "player.hotbar" not in operations(state)
    diagnostic=d["navigationFailureDiagnostics"]
    assert diagnostic["complete"] is complete
    if complete: assert diagnostic["message"]==message and diagnostic["replayAllowed"] is False
    else: assert diagnostic["omitted"] is True and "message" not in diagnostic
    assert len(d["error"]["message"])==512 and json_size(d)<=budget
