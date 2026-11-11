"""Native navigation integration contracts; fixtures do not represent a live movement benchmark."""

import copy
import os
from pathlib import Path
import sys
import time

import pytest

import minecraft_codex_bridge

if os.environ.get("MCB_NAVIGATION_STAGE"):
    minecraft_codex_bridge.__path__.insert(0, str(Path(os.environ["MCB_NAVIGATION_STAGE"]).resolve()))

from minecraft_codex_bridge.bridge import BridgeError, CONTEXT_ACTIONS, READ_OPERATIONS, _ContextData
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow
import test_construction_tasks as construction
import test_native_approach_tasks as planner
import test_recipe_tasks as recipes

native_construction = construction.native_construction
native_bridge = recipes.native_bridge
UUID = "94ca5cb4-942a-4f50-8848-bcc68c453758"


def control(state, dimension):
    position = state["position"]
    return {"available": True, "source": "integrated_server_tick", "sessionId": "session", "playerId": UUID,
        "worldName": "new-world" if dimension == 0 else "test-world", "worldFolder": "new-world" if dimension == 0 else "test-world", "dimension": dimension, "sampleId": state["sample"],
        "ageMillis": 0, "creative": False, "flying": False, "allowFlying": True, "onGround": True,
        "openContainer": {"windowId": 0, "isInventoryContainer": True},
        "clientContainer": {"windowId": 0, "isInventoryContainer": True},
        "pose": {**position, "feetY": position["y"], "yaw": 0, "pitch": 0, "onGround": True, "flying": False, "allowFlying": True}}


def survival_snapshot(snapshot, state, dimension):
    snapshot["player"].update(creative=False, flying=False, onGround=True, allowFlying=True)
    snapshot["player"]["serverFlight"].update(creative=False, flying=False, onGround=True, allowFlying=True)
    snapshot["serverControl"] = control(state, dimension)
    return snapshot


def actual_current_receipt(receipt, state):
    current = receipt["current"]
    feet = {axis: current[axis] for axis in ("x", "y", "z")}
    selected = receipt["selected"]
    selected.update(feet=feet, eye={**feet, "y": feet["y"] + current["eyeOffset"]}, moveRequired=False)
    bounds = {"minX": feet["x"] - current["width"] / 2, "minY": feet["y"] + .00001,
        "minZ": feet["z"] - current["width"] / 2, "maxX": feet["x"] + current["width"] / 2,
        "maxY": feet["y"] + current["height"], "maxZ": feet["z"] + current["width"] / 2}
    for phase in ("server", "client"):
        selected[phase + "Body"]["body"] = bounds.copy()
    receipt["coverage"].update(candidateSlots=1, candidatesExamined=1, serverRays=1, maxClientRays=27,
        serverPages=1, maxServerPages=4, candidateAttempts=1, maxCandidateAttempts=36)
    receipt.update(currentOnly=True)
    return receipt


def no_current_candidate(receipt):
    receipt.update(status="no_candidate", stopReason="no_candidate", selected=None, complete=True)
    receipt["coverage"].update(serverRays=0, clientRays=0, unknownChecks=0)
    return receipt


def install_survival_navigation(client, state, monkeypatch, dimension, *, fault=None):
    state.update(flying=False, sample=10, navigation_calls=[], planner_calls=[])
    # Flat walking starts at the proven endpoint level; ACK faults remain independent.
    state["position"]["y"] = (construction.CONTROLLER if dimension == 0 else recipes.INPUT)["y"] - 1
    if "player.plan_approach" not in state["caps"]:
        state["caps"].append("player.plan_approach")
    if "player.navigate" not in state["caps"]:
        state["caps"].append("player.navigate")
    original = client.call

    async def observed(operation, args, **kwargs):
        if operation == "player.plan_approach":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            state["planner_calls"].append(copy.deepcopy(args))
            result = planner.composite_receipt(state, args)
            result["serverFlight"].update(creative=False, flying=False, onGround=True)
            survival_snapshot(result.context["snapshot"], state, dimension)
            if args.get("currentOnly") is True:
                actual_current_receipt(result, state)
                if not state["navigation_calls"] and not state.get("current_ready"):
                    no_current_candidate(result)
                elif fault == "final_ray":
                    result["selected"]["serverRay"]["verified"] = False
            return result
        if operation == "player.navigate":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            state["navigation_calls"].append(copy.deepcopy(args))
            if fault == "unknown":
                raise BridgeError("connection_lost", "Native movement completion is unknown.", outcome_unknown=True)
            if fault == "unsupported":
                raise BridgeError("unsupported", "Native route has an unsupported obstacle.")
            baseline = state["sample"]
            state["position"] = {axis: args[axis] + (.04 if axis == "x" else 0) for axis in ("x", "y", "z")}
            state["sample"] += 2
            ack = control(state, dimension)
            client_pose = {**state["position"], "feetY": state["position"]["y"], "creative": False, "flying": False,
                "allowFlying": True, "onGround": True}
            result = {"status": "completed", "strategy": args["strategy"], "arrivalConfirmed": True,
                "mutationDispatched": True, "replayAllowed": False, "fallbackUsed": False, "outcomeUnknown": False,
                "playerId": UUID, "serverPoseAck": ack, "actualClientPose": client_pose, "baselineSampleId": baseline,
                "elapsedMillis": 450, "flightTransitConfirmed": args["strategy"] == "ability_flight", "scope": "Native developer navigation only."}
            if fault == "same_sample": ack["sampleId"] = baseline
            elif fault == "wrong_uuid": ack["playerId"] = "another-real-player"
            elif fault == "wrong_session": ack["sessionId"] = "another-session"
            elif fault == "wrong_dimension": ack["dimension"] += 1
            elif fault == "wrong_world": ack["worldName"] = ack["worldFolder"] = "another-world"
            elif fault == "stale": ack["ageMillis"] = 1001
            elif fault == "server_flying": ack["pose"]["flying"] = True
            elif fault == "client_flying": client_pose["flying"] = True
            elif fault == "server_airborne": ack["pose"]["onGround"] = False
            elif fault == "client_airborne": client_pose["onGround"] = False
            elif fault == "server_drift": ack["pose"]["x"] += 1
            elif fault == "client_drift": client_pose["z"] += 1
            elif fault == "strategy": result["strategy"] = "developer_teleport"
            elif fault == "fallback": result["fallbackUsed"] = True
            elif fault == "replay": result["replayAllowed"] = True
            elif fault == "missing_flight": result["flightTransitConfirmed"] = False
            elif fault == "missing_ack": result.pop("serverPoseAck")
            elif fault == "client_creative": client_pose["creative"] = True
            return result
        result = await original(operation, args, **kwargs)
        if operation == "client.snapshot":
            state["sample"] += 1
            survival_snapshot(result, state, dimension)
        if operation == "bridge.health":
            result.update(nativeApproachPlanning=True, approachPlanningIntents=["place_block", "use", "attack"],
                nativeNavigation=True, approachCurrentOnly=True,
                contextRpc={"version": 1, "reads": [], "actions": []})
        return result

    monkeypatch.setattr(client, "call", observed)


def make_task(kind, client, state, strategy=None):
    args = construction.declaration(actualSurvival=True) if kind == "construction" else recipes.declaration(actualSurvival=True)
    if strategy is not None:
        args["movementStrategy"] = strategy
    task = _ConstructionTask(client, args, run_workflow, started=time.monotonic()) if kind == "construction" else _RecipeTask(client, args, run_workflow)
    task.dimension = 0 if kind == "construction" else 180
    task.capabilities = state["caps"]
    task.approach_health = {"nativeApproachPlanning": True, "approachPlanningIntents": ["place_block", "use", "attack"],
        "nativeNavigation": True, "approachCurrentOnly": True,
        "contextRpc": {"version": 1, "reads": ["player.plan_approach"], "actions": []}}
    task.survival_control_player_id = UUID
    return task


async def approach(kind, task):
    if kind == "construction":
        return await task.planned_approach(task.args["controller"])
    return await task.approach_plan("input")


@pytest.mark.parametrize("kind", ["construction", "recipe"])
@pytest.mark.parametrize("strategy", [None, "walking", "ability_flight", "developer_teleport"])
async def test_both_task_entrances_preselect_one_native_strategy_then_recheck_actual_pose(native_construction, native_bridge, monkeypatch, kind, strategy):
    client, state = native_construction if kind == "construction" else native_bridge
    if kind == "construction":
        state["blocks"][construction.key(construction.CONTROLLER)] = {"registryName": construction.CONTROLLER_ITEM["registryName"], "itemMetadata": construction.CONTROLLER_ITEM["metadata"]}
        state["position"] = {"x": construction.CONTROLLER["x"] - 6.5, "y": construction.CONTROLLER["y"], "z": construction.CONTROLLER["z"] + .5}
    install_survival_navigation(client, state, monkeypatch, 0 if kind == "construction" else 180)
    task = make_task(kind, client, state, strategy)
    await approach(kind, task)
    assert len(state["navigation_calls"]) == 1
    assert state["navigation_calls"][0]["strategy"] == (strategy or "walking")
    assert state["navigation_calls"][0]["finishFlying"] is False
    assert len(state["planner_calls"]) == 3 and [row.get("currentOnly", False) for row in state["planner_calls"]] == [True, False, True]
    assert task.navigation_receipts[0]["actualServerFeet"]["x"] == state["position"]["x"]
    assert task.approach_summary()["navigation"] == task.navigation_receipts
    assert not any(row["operation"] in ("chat.send", "dev.teleport", "player.flight", "player.input") for row in state["calls"])


@pytest.mark.parametrize("fault", ["same_sample", "wrong_uuid", "wrong_session", "wrong_dimension", "wrong_world", "stale",
    "server_flying", "client_flying", "server_airborne", "client_airborne", "server_drift", "client_drift", "strategy", "fallback", "replay", "missing_flight", "missing_ack", "client_creative", "unknown", "unsupported"])
async def test_bad_or_unknown_navigation_stops_without_second_move_or_replan(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    install_survival_navigation(client, state, monkeypatch, 180, fault=fault)
    task = make_task("recipe", client, state, "ability_flight" if fault == "missing_flight" else "walking")
    with pytest.raises(BridgeError):
        await approach("recipe", task)
    assert len(state["navigation_calls"]) == 1 and len(state["planner_calls"]) == 2
    assert not any(row["operation"] in ("chat.send", "dev.teleport", "player.flight", "player.interact", "player.place_block") for row in state["calls"])


async def test_failed_actual_pose_ray_never_uses_previous_synthetic_plan(native_bridge, monkeypatch):
    client, state = native_bridge
    install_survival_navigation(client, state, monkeypatch, 180, fault="final_ray")
    task = make_task("recipe", client, state)
    with pytest.raises(BridgeError):
        await approach("recipe", task)
    assert len(state["navigation_calls"]) == 1 and len(state["planner_calls"]) == 3
    assert not any(row["operation"] == "player.interact" for row in state["calls"])


@pytest.mark.parametrize("missing", ["nativeNavigation", "approachCurrentOnly", "operation"])
async def test_missing_native_capability_refuses_before_any_movement(native_bridge, monkeypatch, missing):
    client, state = native_bridge
    install_survival_navigation(client, state, monkeypatch, 180)
    task = make_task("recipe", client, state)
    if missing == "operation": task.capabilities.remove("player.navigate")
    else: task.approach_health.pop(missing)
    with pytest.raises(BridgeError): await approach("recipe", task)
    assert not state["navigation_calls"] and len(state["planner_calls"]) == (0 if missing == "approachCurrentOnly" else 2)


@pytest.mark.parametrize("fault", [None, "unexpected_mode", "moved", "ray", "body", "slots"])
async def test_current_only_requires_actual_continuous_stance_and_complete_native_proof(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    install_survival_navigation(client, state, monkeypatch, 180)
    state["position"] = {"x": recipes.INPUT["x"] + .55, "y": recipes.INPUT["y"] - 1, "z": recipes.INPUT["z"] - 1.5}
    task = make_task("recipe", client, state)
    args = {**recipes.INPUT, "dimension": 180, "expectedWorld": "test-world", "intent": "use", "pattern": "cardinal", "requireFlying": False, "allowedSides": [2, 3, 4, 5]}
    receipt = planner.composite_receipt(state, args)
    receipt["serverFlight"].update(creative=False, flying=False, onGround=True)
    survival_snapshot(receipt.context["snapshot"], state, 180)
    actual_current_receipt(receipt, state)
    if fault == "unexpected_mode": receipt["currentOnly"] = False
    elif fault == "moved": receipt["selected"]["moveRequired"] = True
    elif fault == "ray": receipt["selected"]["clientRay"]["verified"] = False
    elif fault == "body": receipt["selected"]["serverBody"]["occupiable"] = False
    elif fault == "slots": receipt["coverage"]["candidateSlots"] = 13
    options = dict(intent="use", pattern="cardinal", allowed_sides=(2, 3, 4, 5), hotbar_slot=None, target_identity=None, current_only=True)
    if fault is None:
        task.validate_native_approach(receipt, recipes.INPUT, **options)
        assert receipt["selected"]["feet"]["x"] == state["position"]["x"]
        with pytest.raises(BridgeError): task.validate_native_approach(receipt, recipes.INPUT, **{**options, "current_only": False})
    else:
        with pytest.raises(BridgeError): task.validate_native_approach(receipt, recipes.INPUT, **options)


def test_navigation_tool_is_mutating_bounded_and_preserves_existing_input_teleport():
    spec = TOOL_BY_NAME["mcb_navigate"]
    assert spec.operation == "player.navigate" and spec.takeover is True and not spec.read_only
    assert spec.operation in CONTEXT_ACTIONS and spec.operation not in READ_OPERATIONS
    base = {"x": 4.5, "y": 2, "z": 2.5, "dimension": 0, "expectedWorld": "test-world", "expectedSessionId": "session"}
    for strategy in ("walking", "ability_flight", "developer_teleport"):
        assert list(tool_validator(spec.name).iter_errors({**base, "strategy": strategy}))
    assert list(tool_validator(spec.name).iter_errors({**base, "strategy": "auto"}))
    assert list(tool_validator(spec.name).iter_errors({**base, "timeout_ms": 6501}))
    assert list(tool_validator(spec.name).iter_errors({**base, "waypoints": [{"x": 1, "y": 2, "z": 1}] * 3}))
    assert TOOL_BY_NAME["mcb_input"].operation == "player.input"
    assert "creative alone is insufficient" in TOOL_BY_NAME["mcb_teleport"].description
    assert "normal tp command permission" in TOOL_BY_NAME["mcb_teleport"].description


@pytest.mark.parametrize("kind", ["construction", "recipe"])
async def test_fresh_current_stance_reuses_continuous_position_without_navigation(native_construction, native_bridge, monkeypatch, kind):
    client, state = native_construction if kind == "construction" else native_bridge
    target = construction.CONTROLLER if kind == "construction" else recipes.INPUT
    if kind == "construction":
        state["blocks"][construction.key(target)] = {"registryName": construction.CONTROLLER_ITEM["registryName"],
            "itemMetadata": construction.CONTROLLER_ITEM["metadata"]}
    state.update(position={"x": target["x"] + .55, "y": target["y"] - 1, "z": target["z"] - 1.47}, current_ready=True)
    install_survival_navigation(client, state, monkeypatch, 0 if kind == "construction" else 180)
    task = make_task(kind, client, state)
    result = await task.resolved_native_approach(target, pattern="surround" if kind == "construction" else "cardinal")
    assert result["selected"]["feet"] == state["position"] and result["selected"]["moveRequired"] is False
    assert len(state["planner_calls"]) == 1 and state["planner_calls"][0]["currentOnly"] is True
    assert not state["navigation_calls"] and not construction.writes(state)
    assert task.approach_summary()["selectedPlans"] == 1 and task.approach_summary()["knownCurrentAbsences"] == 0
    state["position"]["x"] += .02
    newer = await task.resolved_native_approach(target, pattern="surround" if kind == "construction" else "cardinal")
    assert newer["selected"]["feet"] == state["position"] and newer["selected"]["feet"] != result["selected"]["feet"]
    assert len(state["planner_calls"]) == 2 and not state["navigation_calls"]


@pytest.mark.parametrize("fault", ["unknown", "partial", "incomplete", "continuation", "selected", "missing_selected",
    "wrong_mode", "stop_reason", "missing_guard", "bool_examined", "missing_budget", "rays", "client_ray", "actor",
    "stale", "session", "dimension", "target", "target_identity", "pose", "view", "slot", "item_guard", "source_guard",
    "cursor_guard", "flight", "world", "background", "health", "error"])
async def test_current_absence_with_unknown_stale_or_missing_guards_never_searches_or_moves(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    install_survival_navigation(client, state, monkeypatch, 180)
    original = client.call

    async def invalid(operation, args, **options):
        value = await original(operation, args, **options)
        if operation != "player.plan_approach":
            return value
        if fault == "error": raise BridgeError("timeout", "Current planning result unavailable.")
        coverage, current, snapshot = value["coverage"], value["current"], value.context["snapshot"]
        if fault == "unknown": coverage["unknownChecks"] = 1
        elif fault == "partial": value["partial"] = True
        elif fault == "incomplete": value["complete"] = False
        elif fault == "continuation": value["nextOffset"] = 0
        elif fault == "selected": value["selected"] = {}
        elif fault == "missing_selected": value.pop("selected")
        elif fault == "wrong_mode": value["currentOnly"] = False
        elif fault == "stop_reason": value["stopReason"] = "tick_budget"
        elif fault == "missing_guard": value.pop("nextOffset")
        elif fault == "bool_examined": coverage["candidatesExamined"] = True
        elif fault == "missing_budget": coverage.pop("maxServerPages")
        elif fault == "rays": coverage["serverRays"] = 7
        elif fault == "client_ray": coverage["clientRays"] = 1
        elif fault == "actor": snapshot["serverControl"]["playerId"] = "changed-player"
        elif fault == "stale": value["serverFlight"]["ageMillis"] = 1001
        elif fault == "session": value["sessionId"] = "other-session"
        elif fault == "dimension": value["dimension"] = 0
        elif fault == "target": value["target"]["x"] += 1
        elif fault == "target_identity": value["target"].pop("gregtech")
        elif fault == "pose": snapshot["player"]["x"] += .01
        elif fault == "view": snapshot["player"]["yaw"] += 1
        elif fault == "slot": snapshot["player"]["selectedHotbarSlot"] = 8
        elif fault == "item_guard": current.pop("item")
        elif fault == "source_guard": current.pop("sourceItem")
        elif fault == "cursor_guard": current.pop("cursorEmpty")
        elif fault == "flight": snapshot["player"]["serverFlight"]["flying"] = True
        elif fault == "world": snapshot["world"]["worldName"] = snapshot["world"]["worldFolder"] = "other-world"
        elif fault == "background": snapshot["background"]["enabled"] = False
        else: snapshot["player"]["health"] = 0
        return value

    monkeypatch.setattr(client, "call", invalid)
    task = make_task("recipe", client, state)
    with pytest.raises(BridgeError):
        await approach("recipe", task)
    assert len(state["planner_calls"]) == 1 and not state["navigation_calls"]
    assert not recipes.writes(state)
