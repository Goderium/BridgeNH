"""One-use planner delivery; fixtures prove routing/accounting, not live latency."""
import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError, response_context
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
import test_native_navigation as navigation
import test_survival_flight_posture as posture
import test_survival_structure_seeds as seeds
from test_construction_tasks import CASING, CONTROLLER, key, native_construction, stack


def prepare(client, state, monkeypatch, *, moving=False, flying=False):
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 2, "y": 3 if moving else 2}
    owned, side = ({**point, "y": point["y"] - 1}, 1) if moving else ({**point, "x": point["x"] + 1}, 4)
    state["blocks"][key(owned)] = CASING.copy()
    state["items"][6] = stack(CASING, 2)
    state["position"] = {"x": point["x"] - (6.5 if moving else .5), "y": point["y"] - 1 if moving else 2,
        "z": point["z"] + .5}
    if flying:
        task = posture.flight_task("construction", client, state, monkeypatch)
    else:
        navigation.install_survival_navigation(client, state, monkeypatch, 0)
        task = navigation.make_task("construction", client, state)
    state["current_ready"] = not moving
    task.materials[6] = {"item": CASING.copy(), "count": 2}
    task._survival_frontier_binding = [(owned, side)]
    return task, point, owned, side


async def test_ready_probe_delivers_one_plan_into_real_bound_placement_and_ledger(native_construction, monkeypatch):
    client, state = native_construction
    task, point, owned, side = prepare(client, state, monkeypatch)
    calls = seeds.install_finite_native_receipts(task, state, monkeypatch)
    monkeypatch.setattr(task, "supported_placement_approach", _ConstructionTask.supported_placement_approach.__get__(task))
    assert await task.probe_survival_frontier(point, 6, [(owned, side)]) is True
    original_plan = task._frontier_plan_handoff["plan"]
    assert response_context(original_plan)["phase"] == "after"
    record = {}
    await task.place(point, 6, "handoff", record)
    assert len(state["planner_calls"]) == 1 and state["planner_calls"][0]["currentOnly"] is True
    assert len(calls) == 1 and record["placementConfirmed"] and record["serverConsumptionConfirmed"]
    assert task._planned_placement_attachment["side"] == side and task._frontier_plan_handoff is None
    assert task.survival_material_ledger[0]["deductions"] == [{**CASING, "playerInventoryIndex": 6,
        "count": 1, "sourceCountBefore": 2, "sourceCountAfter": 1, "nbtIdentity": ""}]
    assert state["items"][6]["count"] == 1
    # A second action cannot consume the same receipt, even within its lifetime.
    with pytest.raises(BridgeError):
        await task.place(point, 6, "second")
    assert len(state["planner_calls"]) == 2 and len(calls) == 1


@pytest.mark.parametrize("intervening", ["read", "mutation", "failed_call", "expired"])
async def test_intervening_transport_or_expiry_requests_fresh_plan_before_any_place(native_construction, monkeypatch, intervening):
    client, state = native_construction
    task, point, owned, side = prepare(client, state, monkeypatch)
    calls = seeds.install_finite_native_receipts(task, state, monkeypatch)
    monkeypatch.setattr(task, "supported_placement_approach", _ConstructionTask.supported_placement_approach.__get__(task))
    assert await task.probe_survival_frontier(point, 6, [(owned, side)])
    if intervening == "expired":
        task._frontier_plan_handoff["expires"] = 0
    elif intervening == "failed_call":
        original = client.call
        async def failed(operation, args, **kwargs):
            if operation == "inventory.inspect":
                raise BridgeError("connection_lost", "An intervening transport result is unknown.", outcome_unknown=True)
            return await original(operation, args, **kwargs)
        monkeypatch.setattr(client, "call", failed)
        with pytest.raises(BridgeError):
            await task.tracked_client.call("inventory.inspect", {})
        monkeypatch.setattr(client, "call", original)
    else:
        await task.tracked_client.call("inventory.inspect" if intervening == "read" else "player.hotbar", {} if intervening == "read" else {"slot": 0})
    await task.place(point, 6, "fresh")
    assert len(state["planner_calls"]) == 2 and len(calls) == 1
    assert task._frontier_plan_handoff is None


@pytest.mark.parametrize("fault", ["point", "slot", "sides", "source", "context", "owner", "ray"])
async def test_malformed_immediate_handoff_refuses_zero_mutation(native_construction, monkeypatch, fault):
    client, state = native_construction
    task, point, owned, side = prepare(client, state, monkeypatch)
    calls = seeds.install_finite_native_receipts(task, state, monkeypatch)
    monkeypatch.setattr(task, "supported_placement_approach", _ConstructionTask.supported_placement_approach.__get__(task))
    assert await task.probe_survival_frontier(point, 6, [(owned, side)])
    token = task._frontier_plan_handoff
    if fault == "point": token["point"]["x"] += 1
    elif fault == "slot": token["slot"] = 5
    elif fault == "sides": token["sides"] = (5,)
    elif fault == "source": token["plan"]["current"]["sourceItem"]["count"] = 3
    elif fault == "context": response_context(token["plan"])["snapshot"]["serverControl"]["ageMillis"] = 1001
    elif fault == "owner": token["plan"]["sessionId"] = "foreign"
    else: token["plan"]["selected"]["clientRay"]["verified"] = False
    with pytest.raises(BridgeError):
        await task.place(point, 6, "refused")
    assert len(state["planner_calls"]) == 1 and not calls and key(point) not in state["blocks"]
    assert state["items"][6]["count"] == 2 and task._frontier_plan_handoff is None
    assert task.phase == "refused"


@pytest.mark.parametrize("flying", [False, True])
async def test_provisional_search_retains_real_navigation_and_actual_arrived_recheck(native_construction, monkeypatch, flying):
    client, state = native_construction
    task, point, owned, side = prepare(client, state, monkeypatch, moving=True, flying=flying)
    assert await task.probe_survival_frontier(point, 6, [(owned, side)])
    provisional = copy.deepcopy(task._frontier_plan_handoff["plan"])
    assert provisional["selected"]["moveRequired"] is True
    await task.planned_approach(point, intent="place_block", allowed_sides=(side,), hotbar_slot=6, prepared_plan=provisional)
    assert [args.get("currentOnly", False) for args in state["planner_calls"]] == [True, False, True]
    assert len(state["navigation_calls"]) == 1
    assert task.position_snapshot["player"]["x"] == state["position"]["x"]
    assert task.position_snapshot["player"]["x"] != provisional["selected"]["feet"]["x"]
    if flying:
        assert all(args["movementStrategy"] == "ability_flight" and args["operationPosture"] == "flying" for args in state["planner_calls"])
        assert state["navigation_calls"][0]["finishFlying"] is True
    assert not any(row["operation"] in ("dev.teleport", "chat.send", "player.flight", "player.place_block") for row in state["calls"])


@pytest.mark.parametrize("invalidate", [False, True])
async def test_same_pass_absence_skips_only_duplicate_current_read(native_construction, monkeypatch, invalidate):
    client, state = native_construction
    task, point, owned, side = prepare(client, state, monkeypatch, moving=True)
    task._frontier_current_absences = {}
    assert not await task.probe_survival_frontier(point, 6, [(owned, side)], current_only=True)
    if invalidate:
        task._frontier_current_absences = None
    assert await task.probe_survival_frontier(point, 6, [(owned, side)])
    expected = [True, True, False] if invalidate else [True, False]
    assert [args.get("currentOnly", False) for args in state["planner_calls"]] == expected
    assert task.survival_frontier_native_reads == len(expected)
    assert task.survival_frontier_observations[-1]["duplicateCurrentReadAvoided"] is (not invalidate)
    provisional = task._frontier_plan_handoff["plan"]
    assert provisional["selected"]["moveRequired"] is True
    await task.planned_approach(point, intent="place_block", allowed_sides=(side,), hotbar_slot=6, prepared_plan=provisional)
    assert len(state["navigation_calls"]) == 1
    assert state["planner_calls"][-1]["currentOnly"] is True
    assert not any(row["operation"] == "player.place_block" for row in state["calls"])


async def test_walking_frontier_search_rejects_first_incompatible_candidate_and_selects_next_public_point(native_construction, monkeypatch):
    client, state = native_construction
    task = seeds.task(client)
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + distance, "y": 2} for distance in (1, 2)]
    seeds.prepare_frontier(task, state, [("item_input", 2, points)])
    state["position"]["y"] = 2
    state["items"][2] = stack(task.materials[2]["item"])
    task.args["movementStrategy"] = "walking"
    task.approach_health = {"approachMovementStrategyVersion": 1}
    placements = seeds.install_frontier_places(task, state, monkeypatch)
    monkeypatch.setattr(task, "current_native_approach", None)
    original = task.call
    plans = []
    async def observed(operation, args):
        if operation != "player.plan_approach":
            return await original(operation, args)
        plans.append(copy.deepcopy(args))
        assert args["movementStrategy"] == "walking" and "operationPosture" not in args
        point = {axis: args[axis] for axis in ("x", "y", "z")}
        result = seeds.frontier_receipt(task, state, point, 2)
        result.update(movementStrategy="walking", movementStrategyVersion=1, movementStrategyScope="endpoint_compatibility_only")
        result["coverage"]["strategyIncompatibleCandidates"] = 0
        if point == points[0]:
            result.update(status="no_candidate", selected=None, complete=True, stopReason="no_candidate")
        return result
    monkeypatch.setattr(task, "call", observed)
    await task.install_survival_frontier_ports()
    assert [args["x"] for args in plans] == [point["x"] for point in points]
    assert placements == [(2, key(points[1]))]
    assert [sample["usable"] for sample in task.survival_frontier_observations] == [False, True]
