"""Finite observed effects stop on an impossible delta, never repeat actions."""

from decimal import Decimal

import pytest

import test_workflows as workflow
from minecraft_codex_bridge.workflows import quantity_delta_evidence


bridge = workflow.bridge


def quantity_wait(expected, *, timeout_ms=1000):
    step = workflow.item_delta_wait()
    step["condition"]["value"] = expected
    step["timeout_ms"] = timeout_ms
    return step


@pytest.mark.parametrize("initial,expected,observed", [
    (10, 1, 9), (10, 1, 12), (10, -2, 11), (10, -2, 7), (10, 0, 11), (10, 0, 9),
])
async def test_wrong_direction_or_excess_finite_delta_blocks_later_action_immediately(bridge, initial, expected, observed):
    adapter, state = bridge
    observations = iter([workflow.counted_inventory(initial), workflow.counted_inventory(observed)])
    state["data"]["world.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", workflow.plan(
        workflow.read("baseline", tool="mcb_world_inspect", args=workflow.POSITION),
        workflow.action(), quantity_wait(expected), workflow.action("later")))
    report = result.structuredContent["data"]
    assert result.isError and report["error"]["code"] == "ITEM_DELTA_CHANGED"
    assert report["outcomeUnknown"] is False and report["sideEffectsPossible"] is True
    assert workflow.operations(state).count("player.hotbar") == 1
    assert workflow.operations(state).count("world.inspect") == 2
    failed = report["steps"][2]
    assert failed["status"] == "failed" and failed["attempts"] == 1 and failed["matched"] is False
    assert failed["quantityDelta"]["baseline"] == str(initial)
    assert failed["quantityDelta"]["current"] == str(observed)
    assert failed["quantityDelta"]["delta"] == str(expected)
    assert failed["quantityDelta"]["expectedDelta"] == str(expected)
    assert failed["quantityDelta"]["observedDelta"] == str(observed - initial)
    assert 0 <= failed["elapsed_ms"] <= report["elapsed_ms"]
    assert report["steps"][3]["status"] == "not_executed"


@pytest.mark.parametrize("expected,partial,final", [(2, 11, 12), (-2, 9, 8)])
async def test_partial_signed_delta_polls_until_exact_effect_and_keeps_one_native_action(bridge, expected, partial, final):
    adapter, state = bridge
    observations = iter([workflow.counted_inventory(count) for count in (10, 10, partial, final)])
    state["data"]["world.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", workflow.plan(
        workflow.read("baseline", tool="mcb_world_inspect", args=workflow.POSITION),
        workflow.action(), quantity_wait(expected)))
    report = result.structuredContent["data"]
    assert not result.isError and report["status"] == "completed"
    assert report["steps"][2]["attempts"] == 3 and report["steps"][2]["matched"] is True
    assert report["steps"][2]["quantityDelta"]["current"] == str(final)
    assert report["steps"][2]["quantityDelta"]["expectedDelta"] == str(expected)
    assert report["steps"][2]["quantityDelta"]["observedDelta"] == str(expected)
    assert workflow.operations(state).count("player.hotbar") == 1
    assert workflow.operations(state).count("world.inspect") == 4


@pytest.mark.parametrize("progress", [0, 1])
async def test_incomplete_production_timeout_retains_last_real_quantity_and_failed_phase_duration(bridge, progress):
    adapter, state = bridge
    reads = 0

    def observed():
        nonlocal reads
        reads += 1
        return workflow.counted_inventory(10 if reads == 1 else 10 + progress)

    state["data"]["world.inspect"] = observed
    result = await adapter.call_tool("mcb_sequence", workflow.plan(
        workflow.read("baseline", tool="mcb_world_inspect", args=workflow.POSITION),
        workflow.action(), quantity_wait(2, timeout_ms=150), workflow.action("later"), timeout_ms=1000))
    report = result.structuredContent["data"]
    assert result.isError and report["error"]["code"] == "WAIT_TIMEOUT"
    failed = report["steps"][2]
    assert failed["attempts"] >= 2 and failed["attempts"] == report["error"]["attempts"]
    assert failed["matched"] is False and failed["elapsed_ms"] >= 100
    assert failed["quantityDelta"] == {"baseline": "10", "current": str(10 + progress), "delta": "2",
                                       "expectedDelta": "2", "observedDelta": str(progress),
                                       "match": {"item.registryName": "minecraft:chest", "item.metadata": 0}}
    assert workflow.operations(state).count("player.hotbar") == 1
    assert report["steps"][3]["status"] == "not_executed"


@pytest.mark.parametrize("previous_valid_poll", [False, True])
@pytest.mark.parametrize("fault", ["incomplete", "missing"])
async def test_invalid_current_inventory_does_not_fabricate_quantity_evidence(bridge, previous_valid_poll, fault):
    adapter, state = bridge
    values = [workflow.counted_inventory(10)]
    if previous_valid_poll:
        values.append(workflow.counted_inventory(11))
    values.append(workflow.counted_inventory(999, truncated=True) if fault == "incomplete" else {})
    observations = iter(values)
    state["data"]["world.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", workflow.plan(
        workflow.read("baseline", tool="mcb_world_inspect", args=workflow.POSITION),
        workflow.action(), quantity_wait(2), workflow.action("later")))
    report = result.structuredContent["data"]
    assert result.isError and report["error"]["code"] == ("INCOMPLETE_OBSERVATION" if fault == "incomplete" else "CONDITION_TYPE")
    failed = report["steps"][2]
    if previous_valid_poll:
        assert failed["quantityDelta"]["current"] == "11"
        assert failed["quantityDelta"]["expectedDelta"] == "2"
        assert failed["quantityDelta"]["observedDelta"] == "1"
    else:
        assert "quantityDelta" not in failed
    assert workflow.operations(state).count("player.hotbar") == 1
    assert report["steps"][3]["status"] == "not_executed"


@pytest.mark.parametrize("expected", [2, -2])
async def test_item_quantity_evidence_preserves_exact_integer_strings_above_double_precision(bridge, expected):
    adapter, state = bridge
    initial = 2**53 + 1
    final = initial + expected
    observations = iter([workflow.counted_inventory(str(initial)), workflow.counted_inventory(str(final))])
    state["data"]["world.inspect"] = lambda: next(observations)
    result = await adapter.call_tool("mcb_sequence", workflow.plan(
        workflow.read("baseline", tool="mcb_world_inspect", args=workflow.POSITION),
        workflow.action(), quantity_wait(str(expected))))
    report = result.structuredContent["data"]
    assert not result.isError and report["status"] == "completed"
    assert report["steps"][2]["quantityDelta"] == {
        "baseline": str(initial), "current": str(final), "delta": str(expected),
        "expectedDelta": str(expected), "observedDelta": str(expected),
        "match": {"item.registryName": "minecraft:chest", "item.metadata": 0}}


def test_quantity_diagnostic_subtraction_retains_all_decimal_places():
    condition = {**quantity_wait(1)["condition"],
                 "_baselineCount": Decimal("0.123456789012345678901234567890123456789")}
    evidence = quantity_delta_evidence(workflow.counted_inventory("1"), condition)
    assert evidence["delta"] == evidence["expectedDelta"] == "1"
    assert evidence["observedDelta"] == "0.876543210987654321098765432109876543211"
