"""Versioned native hover endpoint contracts; fixtures never stand in for live flight."""
import pytest

import test_native_navigation as navigation
import test_survival_flight_posture as posture
from minecraft_codex_bridge.bridge import BridgeError

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction


def flight_task(kind, client, state, monkeypatch):
    if kind == "construction":
        target = navigation.construction.CONTROLLER
        state["blocks"][navigation.construction.key(target)] = {
            "registryName": navigation.construction.CONTROLLER_ITEM["registryName"],
            "itemMetadata": navigation.construction.CONTROLLER_ITEM["metadata"]}
    task = posture.flight_task(kind, client, state, monkeypatch)
    state["position"]["y"] += .25865824594294
    return task


@pytest.mark.parametrize("kind", ["recipe", "construction"])
async def test_noninteger_hover_preserves_actual_height_and_final_recheck(native_bridge, native_construction, monkeypatch, kind):
    client, state = native_construction if kind == "construction" else native_bridge
    task = flight_task(kind, client, state, monkeypatch)
    start_y = state["position"]["y"]
    await navigation.approach(kind, task)
    assert len(state["navigation_calls"]) == 1
    assert state["navigation_calls"][0]["y"] == start_y
    assert state["navigation_calls"][0]["finishFlying"] is True
    assert [row.get("currentOnly", False) for row in state["planner_calls"]] == [True, False, True]
    assert all(row["operationPosture"] == "flying" and row["movementStrategy"] == "ability_flight"
        and row["requireFlying"] is True for row in state["planner_calls"])
    assert task.navigation_receipts[-1]["arrivalConfirmed"] is True


@pytest.mark.parametrize("fault", ["echo", "version", "mode", "slots", "height", "body", "ray", "permission", "grounded", "unknown_absence", "incomplete_absence"])
async def test_hover_corruption_never_closes_or_moves(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    original = client.call

    async def corrupt(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation == "player.plan_approach" and not args.get("currentOnly"):
            if fault == "echo": result["operationPosture"] = "grounded"
            elif fault == "version": result["operationPostureVersion"] = True
            elif fault == "mode": result["candidateMode"] = "grounded_fixed_y"
            elif fault == "slots": result["coverage"]["candidateSlots"] = 13
            elif fault == "height": result["selected"]["feet"]["y"] = args["y"] - 1
            elif fault == "body": result["selected"]["serverBody"]["occupiable"] = False
            elif fault == "ray": result["selected"]["clientRay"]["verified"] = False
            elif fault == "permission": result.context["snapshot"]["serverControl"]["pose"]["allowFlying"] = False
            elif fault == "grounded": result.context["snapshot"]["player"]["onGround"] = True
            else:
                result.update(status="no_candidate", stopReason="no_candidate", selected=None, complete=True)
                slots = result["coverage"]["candidateSlots"]
                result["coverage"].update(candidatesExamined=slots, candidateAttempts=slots,
                    maxClientRays=27, serverRays=0, clientRays=0, unknownChecks=int(fault == "unknown_absence"))
                if fault == "incomplete_absence": result["coverage"]["candidatesExamined"] = slots - 1
        return result

    monkeypatch.setattr(client, "call", corrupt)
    with pytest.raises(BridgeError): await navigation.approach("recipe", task)
    assert not state["navigation_calls"]
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("version", [None, True, 1.0, "1", 2])
async def test_hover_capability_cannot_downgrade_to_grounded(native_bridge, monkeypatch, version):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    task.approach_health["approachOperationPostureVersion"] = version
    with pytest.raises(BridgeError) as failure: await navigation.approach("recipe", task)
    assert failure.value.details["reason"] == "native_approach_posture_unavailable"
    assert not state["planner_calls"] and not state["navigation_calls"]


async def test_complete_hover_absence_refuses_with_full_receipt(native_bridge, monkeypatch):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    original = client.call

    async def absent(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation == "player.plan_approach" and not args.get("currentOnly"):
            result.update(status="no_candidate", stopReason="no_candidate", selected=None, complete=True)
            result["coverage"].update(candidatesExamined=5, candidateAttempts=5, maxClientRays=27,
                serverRays=0, clientRays=0, unknownChecks=0)
        return result

    monkeypatch.setattr(client, "call", absent)
    with pytest.raises(BridgeError) as failure: await navigation.approach("recipe", task)
    assert failure.value.details["reason"] == "native_approach_no_compatible_candidate"
    assert failure.value.details["approachReceipt"]["candidateMode"] == "actual_current_y_hover"
    assert not state["navigation_calls"]
