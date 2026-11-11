"""Explicit endpoint compatibility; mocked arrival remains distinct from native route proof."""

import copy

import pytest

import test_native_navigation as navigation
from minecraft_codex_bridge.bridge import BridgeError

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction


def loaded_start(state, dimension):
    target = navigation.construction.CONTROLLER if dimension == 0 else navigation.recipes.INPUT
    state["position"] = {"x": target["x"] - 6.5, "y": target["y"], "z": target["z"] + .5}
    if dimension == 0:
        state["blocks"][navigation.construction.key(target)] = {
            "registryName": navigation.construction.CONTROLLER_ITEM["registryName"],
            "itemMetadata": navigation.construction.CONTROLLER_ITEM["metadata"]}


def install_filter(client, state, monkeypatch, dimension, *, fault=None):
    loaded_start(state, dimension)
    navigation.install_survival_navigation(client, state, monkeypatch, dimension)
    original = client.call

    async def observed(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation != "player.plan_approach" or "movementStrategy" not in args:
            return result
        strategy = args["movementStrategy"]
        if strategy == "walking" and not args.get("currentOnly"):
            filtered = copy.deepcopy(state)
            target_y, current_y = args["y"], state["position"]["y"]
            filtered["blocked_feet_heights"] = [y for y in (target_y - 1, target_y, target_y - 2, target_y + 1)
                if abs(y - current_y) > .0001]
            result = navigation.planner.composite_receipt(filtered, args)
            result["serverFlight"].update(creative=False, flying=False, onGround=True)
            navigation.survival_snapshot(result.context["snapshot"], state, dimension)
        result.update(movementStrategy=strategy, movementStrategyVersion=1,
            movementStrategyScope="endpoint_compatibility_only")
        result["coverage"]["strategyIncompatibleCandidates"] = 0
        if not args.get("currentOnly"):
            if fault in ("absence", "unknown_absence", "incomplete_absence"):
                result.update(status="no_candidate", stopReason="no_candidate", complete=True, selected=None)
                slots = result["coverage"]["candidateSlots"]
                result["coverage"].update(candidatesExamined=slots, candidateAttempts=slots,
                    serverPages=1, maxServerPages=4, maxCandidateAttempts=36, maxClientRays=27,
                    serverRays=0, clientRays=0, unknownChecks=0, strategyIncompatibleCandidates=slots - 1)
                if fault == "unknown_absence": result["coverage"]["unknownChecks"] = 1
                if fault == "incomplete_absence": result["coverage"]["candidatesExamined"] = slots - 1
            elif fault == "echo": result["movementStrategy"] = "ability_flight"
            elif fault == "version_bool": result["movementStrategyVersion"] = True
            elif fault == "scope": result["movementStrategyScope"] = "route_confirmed"
            elif fault == "count_bool": result["coverage"]["strategyIncompatibleCandidates"] = True
            elif fault == "count_negative": result["coverage"]["strategyIncompatibleCandidates"] = -1
            elif fault == "count_excess": result["coverage"]["strategyIncompatibleCandidates"] = 34
            elif fault == "body": result["selected"]["clientBody"]["occupiable"] = False
            elif fault == "support": result["selected"]["serverBody"]["canStand"] = False
            elif fault == "ray": result["selected"]["serverRay"]["verified"] = False
            elif fault == "partial": result["partial"] = True
            elif fault == "height": result["selected"]["feet"]["y"] += .001
        return result

    monkeypatch.setattr(client, "call", observed)


@pytest.mark.parametrize("kind", ["recipe", "construction"])
@pytest.mark.parametrize("strategy", ["walking", "ability_flight", "developer_teleport"])
async def test_shared_resolver_passes_only_explicit_filter_and_keeps_arrival_recheck(
        native_bridge, native_construction, monkeypatch, kind, strategy):
    client, state = native_construction if kind == "construction" else native_bridge
    dimension = 0 if kind == "construction" else 180
    install_filter(client, state, monkeypatch, dimension)
    task = navigation.make_task(kind, client, state, strategy)
    task.approach_health["approachMovementStrategyVersion"] = 1
    await navigation.approach(kind, task)
    calls = state["planner_calls"]
    assert [row.get("currentOnly", False) for row in calls] == [True, False, True]
    assert "movementStrategy" not in calls[0]
    assert [row["movementStrategy"] for row in calls[1:]] == [strategy, strategy]
    assert len(state["navigation_calls"]) == 1 and state["navigation_calls"][0]["strategy"] == strategy
    receipt = task.navigation_receipts[-1]
    assert receipt["arrivalConfirmed"] is True and receipt["replayAllowed"] is receipt["fallbackUsed"] is False


@pytest.mark.parametrize("fault", ["echo", "version_bool", "scope", "count_bool", "count_negative", "count_excess",
    "body", "support", "ray", "partial", "height", "unknown_absence", "incomplete_absence"])
async def test_malformed_or_incomplete_filter_never_moves(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    install_filter(client, state, monkeypatch, 180, fault=fault)
    task = navigation.make_task("recipe", client, state, "walking")
    task.approach_health["approachMovementStrategyVersion"] = 1
    with pytest.raises(BridgeError): await navigation.approach("recipe", task)
    assert len(state["planner_calls"]) == 2 and not state["navigation_calls"]
    assert not navigation.recipes.writes(state)


async def test_complete_known_flat_absence_is_specific_refusal_and_preserves_receipt(native_bridge, monkeypatch):
    client, state = native_bridge
    install_filter(client, state, monkeypatch, 180, fault="absence")
    task = navigation.make_task("recipe", client, state, "walking")
    task.approach_health["approachMovementStrategyVersion"] = 1
    with pytest.raises(BridgeError) as failure: await navigation.approach("recipe", task)
    assert failure.value.details["reason"] == "native_approach_no_compatible_candidate"
    receipt = failure.value.details["approachReceipt"]
    assert receipt["complete"] is True and receipt["selected"] is None
    assert receipt["coverage"]["candidatesExamined"] == receipt["coverage"]["candidateSlots"]
    assert len(state["planner_calls"]) == 2 and not state["navigation_calls"]
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("version", [True, 1.0, "1", 2, None])
async def test_invalid_advertised_filter_never_downgrades_or_searches(native_bridge, monkeypatch, version):
    client, state = native_bridge
    install_filter(client, state, monkeypatch, 180)
    task = navigation.make_task("recipe", client, state, "walking")
    task.approach_health["approachMovementStrategyVersion"] = version
    with pytest.raises(BridgeError) as failure: await navigation.approach("recipe", task)
    assert failure.value.details["reason"] == "native_approach_strategy_unavailable"
    assert len(state["planner_calls"]) == 1 and not state["navigation_calls"]


@pytest.mark.parametrize("delta,allowed", [(0, True), (.000099, True), (.000101, False), (-.000101, False), (2, False)])
async def test_legacy_guard_independently_precedes_close_and_movement(native_bridge, monkeypatch, delta, allowed):
    client, state = native_bridge
    loaded_start(state, 180)
    navigation.install_survival_navigation(client, state, monkeypatch, 180)
    task = navigation.make_task("recipe", client, state, "walking")
    original = task.native_approach

    async def checked(target, **kwargs):
        result = await original(target, **kwargs)
        if not kwargs.get("current_only"):
            result["selected"]["feet"]["y"] = result["current"]["y"] + delta
            result["selected"]["moveRequired"] = True
            result["current"]["screenOpen"] = True
        return result

    monkeypatch.setattr(task, "native_approach", checked)
    if allowed:
        await navigation.approach("recipe", task)
        assert len(state["navigation_calls"]) == 1
    else:
        with pytest.raises(BridgeError) as failure: await navigation.approach("recipe", task)
        assert failure.value.details["reason"] == "native_approach_strategy_incompatible"
        assert not state["navigation_calls"] and not navigation.recipes.writes(state)
    assert all("movementStrategy" not in row for row in state["planner_calls"])


async def test_ready_current_needs_no_strategy_capability_or_extra_search(native_bridge, monkeypatch):
    client, state = native_bridge
    install_filter(client, state, monkeypatch, 180)
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .55, "y": target["y"] - 1, "z": target["z"] - 1.47}
    state["current_ready"] = True
    task = navigation.make_task("recipe", client, state, "walking")
    task.approach_health["approachMovementStrategyVersion"] = "unsupported"
    await navigation.approach("recipe", task)
    assert len(state["planner_calls"]) == 1 and not state["navigation_calls"]
