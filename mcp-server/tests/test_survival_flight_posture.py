"""Isolated protocol/accounting fixtures only; none represent native flight or live placement."""
import copy

import pytest
from jsonschema import ValidationError

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.validation import execution_validator
import test_native_navigation as navigation
import test_native_approach_tasks as planner
import test_construction_tasks as construction
import test_recipe_tasks as recipes
import test_actual_survival_workflows as accounting
import test_survival_bootstrap as bootstrap
import test_survival_structure_seeds as seeds

native_construction = construction.native_construction
native_bridge = recipes.native_bridge


def airborne(snapshot, state):
    flying = state.get("posture_flying", True)
    snapshot["player"].update(creative=False, flying=flying, allowFlying=True, onGround=not flying)
    snapshot["player"]["serverFlight"].update(creative=False, flying=flying, allowFlying=True, onGround=not flying)
    control = snapshot["serverControl"]
    control.update(creative=False, flying=flying, allowFlying=True, onGround=not flying)
    control["pose"].update(flying=flying, allowFlying=True, onGround=not flying)
    return snapshot


def flight_task(kind, client, state, monkeypatch, *, fault=None):
    dimension = 0 if kind == "construction" else 180
    navigation.install_survival_navigation(client, state, monkeypatch, dimension)
    state["posture_flying"] = True
    original = client.call

    async def observed(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation == "client.snapshot":
            airborne(result, state)
            if fault is not None:
                fault(result)
        elif operation == "player.plan_approach":
            if args.get("operationPosture") == "flying":
                result.update(operationPosture="flying", operationPostureVersion=1, candidateMode="actual_current_y_hover",
                    movementStrategy="ability_flight", movementStrategyVersion=1, movementStrategyScope="endpoint_compatibility_only")
                result["coverage"]["strategyIncompatibleCandidates"] = 0
                if not args.get("currentOnly"):
                    result["coverage"].update(candidateSlots=9 if args["pattern"] == "surround" else 5,
                        candidatesExamined=2, candidateAttempts=2, serverPages=1, maxServerPages=4, maxCandidateAttempts=36)
                    if result.get("selected") is not None:
                        selected = result["selected"]
                        selected["feet"]["y"] = result["current"]["y"]
                        selected["eye"]["y"] = result["current"]["y"] + result["current"]["eyeOffset"]
                        for side in ("server", "client"):
                            selected[side + "Body"]["body"].update(minY=selected["feet"]["y"] + .00001,
                                maxY=selected["feet"]["y"] + result["current"]["height"])
            result["current"]["clientFlying"] = True
            result["serverFlight"].update(creative=False, flying=True, allowFlying=True, onGround=False)
            airborne(result.context["snapshot"], state)
            if result.get("selected") is not None:
                for side in ("server", "client"):
                    result["selected"][side + "Body"]["canStand"] = False
            if fault is not None:
                fault(result.context["snapshot"])
        elif operation == "player.navigate":
            state["posture_flying"] = args["finishFlying"]
            result["actualClientPose"].update(flying=args["finishFlying"], allowFlying=True, onGround=not args["finishFlying"])
            result["serverPoseAck"].update(flying=args["finishFlying"], allowFlying=True, onGround=not args["finishFlying"])
            result["serverPoseAck"]["pose"].update(flying=args["finishFlying"], allowFlying=True, onGround=not args["finishFlying"])
        elif operation == "bridge.health":
            result["survivalFlightPostureVersion"] = 1
            result.update(approachMovementStrategyVersion=1, approachOperationPostureVersion=1)
        return result

    monkeypatch.setattr(client, "call", observed)
    task = navigation.make_task(kind, client, state, "ability_flight")
    task.args["survivalOperationPosture"] = "ability_flight"
    task.approach_health["survivalFlightPostureVersion"] = 1
    task.approach_health.update(approachMovementStrategyVersion=1, approachOperationPostureVersion=1)
    return task


@pytest.mark.parametrize("tool,kind", [("mcb_blueprint_build", "construction"), ("mcb_recipe_once", "recipe")])
@pytest.mark.parametrize("finish", [None, True, False])
def test_explicit_task_posture_schema(tool, kind, finish):
    args = construction.declaration() if kind == "construction" else recipes.declaration()
    args.update(actualSurvival=True, movementStrategy="ability_flight", survivalOperationPosture="ability_flight")
    if finish is not None:
        args["finishFlying"] = finish
    if finish is False:
        args["finalLandingPosition"] = {"x": .51, "y": 63.125, "z": -1.45}
    execution_validator(tool).validate(args)


@pytest.mark.parametrize("changes", [
    {"actualSurvival": False}, {"movementStrategy": "walking"}, {"movementStrategy": "developer_teleport"},
    {"survivalOperationPosture": "grounded", "finishFlying": True}, {"finishFlying": False},
    {"finishFlying": True, "finalLandingPosition": {"x": .5, "y": 64, "z": .5}},
    {"finishFlying": False, "finalLandingPosition": {"x": .5, "y": 64}},
    {"finishFlying": False, "finalLandingPosition": {"x": .5, "y": 254, "z": .5}},
])
def test_declared_flight_never_changes_strategy_or_infers_landing(changes):
    args = construction.declaration(actualSurvival=True, movementStrategy="ability_flight", survivalOperationPosture="ability_flight")
    args.update(changes)
    with pytest.raises(ValidationError):
        execution_validator("mcb_blueprint_build").validate(args)


@pytest.mark.parametrize("kind", ["construction", "recipe"])
async def test_actual_airborne_current_or_navigation_retains_full_native_proofs(native_construction, native_bridge, monkeypatch, kind):
    client, state = native_construction if kind == "construction" else native_bridge
    if kind == "construction":
        state["blocks"][construction.key(construction.CONTROLLER)] = {"registryName": "gregtech:gt.blockmachines", "metadata": 0,
            "itemMetadata": construction.CONTROLLER_ITEM["metadata"]}
    task = flight_task(kind, client, state, monkeypatch)
    await navigation.approach(kind, task)
    assert len(state["navigation_calls"]) == 1
    assert state["navigation_calls"][0]["strategy"] == "ability_flight"
    assert state["navigation_calls"][0]["finishFlying"] is True
    assert all(row["requireFlying"] is True for row in state["planner_calls"])
    assert state["planner_calls"][-1]["currentOnly"] is True
    assert task.navigation_receipts[-1]["actualClientFlying"] is task.navigation_receipts[-1]["actualServerFlying"] is True
    assert not any(row["operation"] in ("dev.teleport", "chat.send", "player.flight") for row in state["calls"])


@pytest.mark.parametrize("part,key,value", [
    ("player", "creative", True), ("player", "allowFlying", False), ("player", "flying", False),
    ("flight", "creative", True), ("flight", "allowFlying", False), ("flight", "flying", False),
    ("control", "playerId", "different-actor"), ("control", "sessionId", "different-session"),
    ("control", "allowFlying", False), ("control", "flying", False), ("control", "ageMillis", 1001),
    ("pose", "flying", False), ("pose", "allowFlying", False),
])
async def test_permission_actor_mode_or_actual_flight_loss_refuses_before_move(native_bridge, monkeypatch, part, key, value):
    client, state = native_bridge
    def fault(snapshot):
        target = {"player": snapshot["player"], "flight": snapshot["player"]["serverFlight"],
            "control": snapshot["serverControl"], "pose": snapshot["serverControl"]["pose"]}[part]
        target[key] = value
    task = flight_task("recipe", client, state, monkeypatch, fault=fault)
    state["current_ready"] = True
    with pytest.raises(BridgeError):
        await task.approach_plan("input")
    assert not state["navigation_calls"]
    assert not any(row["operation"] not in ("player.plan_approach", "client.snapshot") for row in state["calls"])


@pytest.mark.parametrize("fault", ["server_body", "client_body", "partial", "liquid", "hazard", "ray", "reach", "source"])
async def test_airborne_still_refuses_partial_body_native_ray_reach_or_source(native_construction, monkeypatch, fault):
    client, state = native_construction
    state["blocks"][construction.key(construction.CONTROLLER)] = {"registryName": "gregtech:gt.blockmachines", "metadata": 0,
        "itemMetadata": construction.CONTROLLER_ITEM["metadata"]}
    task = flight_task("construction", client, state, monkeypatch)
    state["current_ready"] = True
    original = client.call
    async def corrupt(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation == "player.plan_approach":
            chosen = result["selected"]
            if fault == "server_body": chosen["serverBody"]["occupiable"] = False
            elif fault == "client_body": chosen["clientBody"]["collisionCount"] = 1
            elif fault == "partial": chosen["serverBody"]["entityCoveragePartial"] = True
            elif fault == "liquid": chosen["clientBody"]["liquid"] = True
            elif fault == "hazard": chosen["serverBody"]["hazard"] = True
            elif fault == "ray": chosen["serverRay"]["verified"] = False
            elif fault == "reach": result["current"]["maxDistance"] = 32
            elif fault == "source": result.context["snapshot"]["player"]["selectedHotbarSlot"] = 8
        return result
    monkeypatch.setattr(client, "call", corrupt)
    with pytest.raises(BridgeError):
        await task.planned_approach(task.args["controller"])
    assert not state["navigation_calls"]


@pytest.mark.parametrize("side", range(6))
async def test_six_actual_faces_reuse_the_same_target_window_and_fresh_ray(native_bridge, monkeypatch, side):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    task.args["input"] = {"x": 0, "y": 64, "z": 0}
    state["position"] = {"x": .5, "y": 64, "z": -1.5}
    snapshot = await client.call("client.snapshot", {})
    gui = {"screenId": "gui-real", "windowId": 7, "totalSlots": 1, "truncated": False, "slots": [{"slot": 0, "item": None}]}
    task.opened_gui = {"target": "input", "screenId": "gui-real", "windowId": 7, "side": side}
    rays = []
    async def read(*args, **kwargs): return gui, snapshot
    async def call(operation, args):
        assert operation == "world.spatial"
        rays.append(args)
        return {"status": "observed", "hit": {"type": "block", "block": task.args["input"], "side": side}}
    monkeypatch.setattr(task, "read_with_snapshot", read)
    monkeypatch.setattr(task, "call", call)
    assert await task.reuse_gui("input") == gui
    vector = planner.VECTORS[side]
    assert rays[0]["to"] == {axis: task.args["input"][axis] + .5 + .499 * vector[i] for i, axis in enumerate(("x", "y", "z"))}


@pytest.mark.parametrize("finish", [True, False])
async def test_final_posture_is_actual_and_landing_occurs_only_once_at_explicit_continuous_xyz(native_bridge, monkeypatch, finish):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    task.args["finishFlying"] = finish
    if not finish:
        task.args["finalLandingPosition"] = {"x": .54, "y": 64, "z": -1.5}
    task.validate_survival_posture_declaration()
    await task.finish_survival_posture()
    assert task.final_posture["confirmed"] is True and task.final_posture["flying"] is finish
    assert task.final_posture["actualClientPose"]["flying"] is finish
    assert task.final_posture["serverPoseAck"]["pose"]["flying"] is finish
    assert len(state["navigation_calls"]) == int(not finish)
    if not finish:
        move = state["navigation_calls"][0]
        assert move["finishFlying"] is False
        assert {axis: move[axis] for axis in ("x", "y", "z")} == task.args["finalLandingPosition"]
        with pytest.raises(BridgeError):
            await task.finish_survival_posture()
        assert len(state["navigation_calls"]) == 1


@pytest.mark.parametrize("fault", ["flying", "permission", "different_actor", "partial", "wrong_slot", "blueprint"])
def test_airborne_shell_receipt_still_binds_actor_selected_blueprint_and_actual_permission(native_construction, fault):
    client, _ = native_construction
    task = accounting.task(client, actualSurvival=True, movementStrategy="ability_flight", survivalOperationPosture="ability_flight")
    task.survival_player_id = "actual-player"
    slot = task.args["blueprintHotbarSlot"]
    blueprint = {**construction.BLUEPRINT, "count": 1, "hasTag": False, "nbtIdentity": "blueprint"}
    task.materials[slot] = {"item": copy.deepcopy(blueprint), "count": 1}
    receipt = {"operation": "structure.survival_build", "actualSurvival": True, "sessionId": "session", "expectedWorld": "new-world", "dimension": 0,
        "controller": {**construction.CONTROLLER, "dimension": 0, "registryName": task.args["controllerItem"]["registryName"],
            "gregtech": {"metaTileId": task.args["controllerItem"]["metadata"]}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0, "creative": False,
            "actualSurvival": True, "flying": True, "allowFlying": True, "selectedHotbarSlot": slot}, "blueprint": blueprint}
    task.validate_survival_build_context(receipt, slot)
    if fault == "flying": receipt["actor"]["flying"] = False
    elif fault == "permission": receipt["actor"]["allowFlying"] = False
    elif fault == "different_actor": receipt["actor"]["playerId"] = "another"
    elif fault == "partial": receipt["actualSurvival"] = None
    elif fault == "wrong_slot": receipt["actor"]["selectedHotbarSlot"] = (slot + 1) % 9
    elif fault == "blueprint": receipt["blueprint"]["count"] = 2
    with pytest.raises(BridgeError): task.validate_survival_build_context(receipt, slot)


@pytest.mark.parametrize("fault", [None, "unrelated_tag", "missing_slot", "cursor"])
def test_opt_in_never_weakens_exact_all41_native_placement_accounting(native_construction, fault):
    client, _ = native_construction
    task = accounting.task(client, actualSurvival=True, movementStrategy="ability_flight", survivalOperationPosture="ability_flight")
    before = accounting.server_inventory(2)
    after = copy.deepcopy(before)
    after["slots"][2]["item"]["count"] = 1
    if fault == "unrelated_tag": after["slots"][40]["item"]["nbtIdentity"] = "{changed:1}"
    elif fault == "missing_slot": after["slots"].pop()
    elif fault == "cursor": after["cursorItem"] = copy.deepcopy(before["slots"][2]["item"])
    from minecraft_codex_bridge.construction_tasks import _survival_deductions
    if fault is None:
        assert _survival_deductions(before, after)[0]["count"] == 1
    else:
        with pytest.raises(BridgeError): _survival_deductions(before, after)


@pytest.mark.parametrize("extra", [False, True])
async def test_airborne_native_placement_requires_exactly_one_actual_source_decrement(native_construction, monkeypatch, extra):
    client, state = native_construction
    task = seeds.task(client)
    task.args.update(movementStrategy="ability_flight", survivalOperationPosture="ability_flight")
    state["items"][6] = seeds.stack(seeds.CASING, 2)
    task.materials[6] = {"item": seeds.CASING.copy(), "count": 2}
    def mutate(receipt):
        if extra:
            receipt["serverAfter"]["slots"][6]["item"] = None
    seeds.install_finite_native_receipts(task, state, monkeypatch, mutate=mutate)
    point = {**construction.CONTROLLER, "y": 2}
    record = {"position": point.copy(), "permanentStructureSeed": True}
    if extra:
        with pytest.raises(BridgeError) as error:
            await task.place(point, 6, "survival_structure_seed_2", record)
        assert error.value.details["reason"] == "survival_server_material_delta_unconfirmed"
        assert record.get("serverConsumptionConfirmed") is not True
    else:
        await task.place(point, 6, "survival_structure_seed_2", record)
        assert record["serverConsumptionConfirmed"] is True
        assert task.survival_material_ledger[0]["deductions"][0]["count"] == 1


async def test_two_targets_share_the_exact_current_hover_with_fresh_top_face_proofs(native_bridge, monkeypatch):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    task.args["input"] = {"x": 0, "y": 64, "z": 0}
    task.args["output"] = {"x": 1, "y": 64, "z": 0}
    state["position"] = {"x": .5, "y": 66, "z": .5}
    state["current_ready"] = True
    for name in ("input", "output"):
        side, _, moved = await task.approach_plan(name)
        assert side == 1 and moved is False
    assert not state["navigation_calls"]
    assert len(state["planner_calls"]) == 2
    assert all(row["currentOnly"] is True and row["allowedSides"] == list(range(6)) for row in state["planner_calls"])
    assert [{axis: row[axis] for axis in ("x", "y", "z")} for row in state["planner_calls"]] == [task.args["input"], task.args["output"]]


@pytest.mark.parametrize("fault", ["window", "screen", "permission", "actor", "ray_block", "ray_face", "ray_partial"])
async def test_six_face_gui_reuse_refuses_identity_permission_or_visibility_changes(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task = flight_task("recipe", client, state, monkeypatch)
    target = {"x": 0, "y": 64, "z": 0}
    task.args["input"] = target
    state["position"] = {"x": .5, "y": 64, "z": -1.5}
    snapshot = await client.call("client.snapshot", {})
    gui = {"screenId": "gui-real", "windowId": 7, "totalSlots": 1, "truncated": False, "slots": [{"slot": 0, "item": None}]}
    task.opened_gui = {"target": "input", "screenId": "gui-real", "windowId": 7, "side": 1}
    if fault == "window": gui["windowId"] = 8
    elif fault == "screen": gui["screenId"] = "different-screen"
    elif fault == "permission": snapshot["player"]["serverFlight"]["allowFlying"] = False
    elif fault == "actor": snapshot["serverControl"]["playerId"] = "different-actor"
    reads = []
    async def read(*args, **kwargs): return gui, snapshot
    async def call(operation, args):
        reads.append(operation)
        return {"status": "partial" if fault == "ray_partial" else "observed", "hit": {"type": "block",
            "block": {**target, "x": 1} if fault == "ray_block" else target, "side": 2 if fault == "ray_face" else 1}}
    monkeypatch.setattr(task, "read_with_snapshot", read)
    monkeypatch.setattr(task, "call", call)
    if fault in ("window", "screen"):
        assert await task.reuse_gui("input") is None
        assert reads == []
    else:
        with pytest.raises(BridgeError): await task.reuse_gui("input")
        assert all(operation == "world.spatial" for operation in reads)
