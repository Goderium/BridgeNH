"""Retained ghost acceptance drains the current open GUI before one native close."""
import copy

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.bridge import BridgeError
from test_ghost_recipe_tasks import arguments, ghost_machine

native_bridge = native.native_bridge


def acceptance_pages(client, state, monkeypatch, *, first_limit="partial", fault=None):
    original = client.call
    reads = []

    async def observed(operation, args, **options):
        accepting = state["output"] > 0 and state["input_material"] == 0 and not state["allowed"]
        data = await original(operation, args, **options)
        if operation == "gui.widgets" and "semantic" not in args and accepting:
            assert state["open"] is not None and args["screenId"] == data["screenId"]
            offset = args.get("offset", 0)
            assert offset in (0, 56)
            reads.append({"offset": offset, "callIndex": len(state["calls"]) - 1,
                "screenId": data["screenId"], "window": state["window"]})
            rows = data["widgets"]
            ghost = copy.deepcopy(next(row for row in rows if row.get("semantic") == "ghost_circuit"))
            following = 56 if offset == 0 else 70
            data.update(offset=offset, nextOffset=following, visited=70, rawScanned=following - offset,
                actionableOnly=True, hasMore=offset == 0, partial=offset == 0 and first_limit == "partial",
                byteLimited=offset == 0 and first_limit == "byteLimited", scanTruncated=False,
                widgets=[row for row in rows if row.get("semantic") != "ghost_circuit"] if offset == 0 else [ghost])
            if fault == "first_tree_error" and offset == 0 or fault == "tail_tree_error" and offset == 56:
                data["coverage"] = {"treeError": "unknown_public_tree_read_failure"}
            if fault == "terminal_partial" and offset == 56:
                data["partial"] = True
            if fault == "configuration_changed" and offset == 56:
                data["widgets"][0]["circuitConfig"]["value"] = 2
        elif operation == "gui.close" and accepting and fault == "close_uncertain":
            raise BridgeError("CONNECTION_ERROR", "Native close outcome uncertain.", outcome_unknown=True)
        return data

    monkeypatch.setattr(client, "call", observed)
    return reads


@pytest.mark.parametrize("first_limit", ["partial", "byteLimited"])
@pytest.mark.parametrize("executions", [1, 2])
async def test_retained_ghost_on_final_widget_page_is_verified_before_native_close(
    ghost_machine, monkeypatch, first_limit, executions
):
    client, state = ghost_machine
    reads = acceptance_pages(client, state, monkeypatch, first_limit=first_limit)
    result = await native.invoke(client, arguments(executions=executions))
    assert result["status"] == "completed", result
    assert [read["offset"] for read in reads] == [0, 56] * executions
    for first, tail in zip(reads[::2], reads[1::2]):
        assert first["screenId"] == tail["screenId"] and first["window"] == tail["window"] > 0
        assert not any(call["operation"] in {"gui.close", "gui.widget_action", "gui.slot_click"}
            for call in state["calls"][first["callIndex"] + 1:tail["callIndex"]])
        assert next(call for call in state["calls"][tail["callIndex"] + 1:]
            if call["operation"] not in native.READ_OPERATIONS)["operation"] == "gui.close"
    assert state["output"] == 2 * executions and state["input_material"] == state["input_circuit"] == 0
    assert result["acceptance"]["configuredGhostCircuit"]["count"] == 0
    assert result["acceptance"]["configuredGhostCircuitRetained"] and result["acceptance"]["controllerStopped"]
    assert state["open"] is None and state["cursor"] is None and not state["allowed"]
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0] * executions
    assert not any(call["operation"] in {"chat.send", "machine.set_working"} for call in state["calls"])


@pytest.mark.parametrize("fault,reason,offsets", [
    ("first_tree_error", "configured_ghost_widget_coverage_unavailable", [0]),
    ("tail_tree_error", "configured_ghost_widget_coverage_unavailable", [0, 56]),
    ("terminal_partial", "configured_ghost_widget_coverage_unavailable", [0, 56]),
    ("configuration_changed", "configured_ghost_configuration_mismatch", [0, 56]),
])
async def test_incomplete_or_changed_ghost_suffix_refuses_then_closes_and_stops_without_replay(
    ghost_machine, monkeypatch, fault, reason, offsets
):
    client, state = ghost_machine
    reads = acceptance_pages(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert result["failedPhase"] == "configured_ghost_acceptance_1"
    assert [read["offset"] for read in reads] == offsets
    after_read = state["calls"][reads[-1]["callIndex"] + 1:]
    assert len([call for call in after_read if call["operation"] == "gui.close"]) == 1
    assert len([call for call in after_read if call["operation"] == "machine.set_working"]) == 1
    assert result["emergencyStop"]["serverStopConfirmed"] and result["rolledBack"] is False
    assert "acceptance" not in result and result["executionsCompleted"] == 0
    assert state["output"] == 2 and state["input_material"] == state["input_circuit"] == 0
    assert state["open"] is None and not state["allowed"]
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0]
    assert not any(call["operation"] in {"gui.slot_click", "chat.send", "gui.widget_action"} for call in after_read)


async def test_uncertain_post_acceptance_close_is_attempted_once_and_never_replays_power_or_feed(
    ghost_machine, monkeypatch
):
    client, state = ghost_machine
    reads = acceptance_pages(client, state, monkeypatch, fault="close_uncertain")
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["outcomeUnknown"], result
    assert result["failedPhase"] == "close_ghost_acceptance_1"
    assert [read["offset"] for read in reads] == [0, 56]
    after_read = state["calls"][reads[-1]["callIndex"] + 1:]
    assert len([call for call in after_read if call["operation"] == "gui.close"]) == 1
    assert result["emergencyStop"]["serverStopConfirmed"] and "acceptance" not in result
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0]
    assert not any(call["operation"] in {"gui.slot_click", "chat.send", "gui.widget_action"} for call in after_read)
