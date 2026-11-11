"""Offline finite bootstrap and generic future-body guards; no live physics claims."""

import copy

import anyio
import pytest

from minecraft_codex_bridge import construction_tasks as construction
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.validation import execution_validator
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import BLUEPRINT, CONTROLLER, key, native_construction, stack, writes
from test_initial_controller_seeds import (
    FRAME, SHEET, arguments, exact_candidates, instance, native_moves,
)
from test_survival_structure_seeds import control, install_finite_native_receipts


def bootstrap_arguments(**changes):
    if changes.get("movementStrategy", "walking") == "walking":
        changes.setdefault("controllerFacing", "NORTH")
    return arguments(bootstrapAdjacentSeed={"offsetX": 1, "offsetY": -2, "offsetZ": 0,
        "item": FRAME.copy(), "playerInventoryIndex": 11}, **changes)


def bootstrap_task(client, state, monkeypatch, **changes):
    task = instance(client, state, monkeypatch, bootstrap_arguments(**changes))
    state["items"][11] = stack(FRAME, 2)
    if "player.navigate" not in state["caps"]:
        state["caps"].append("player.navigate")
    original = task.call

    async def call(operation, args):
        result = await original(operation, args)
        if operation == "bridge.health":
            result.update(nativeNavigation=True, nativeWalkingStepVersion=1, approachBuildEnvelopeVersion=1, survivalBuildEnvelopeVersion=1,
                placementFacingVersion=1)
        elif operation == "world.structure":
            result.update(controller={**task.args["controller"], "dimension": task.dimension},
                alignment={"direction": state.get("controller_facing", "NORTH"), "rotation": "normal", "flip": "none"})
        return result

    monkeypatch.setattr(task, "call", call)
    return task


def body_at(destination):
    return {"minX": destination["x"] - .3, "minY": destination["y"], "minZ": destination["z"] - .3,
        "maxX": destination["x"] + .3, "maxY": destination["y"] + 1.8, "maxZ": destination["z"] + .3}


def navigation_receipt(task, destination, strategy, phase, sample):
    pose = {"x": destination["x"], "feetY": destination["y"], "z": destination["z"],
        "creative": False, "onGround": True, "flying": False}
    ack = {**control(task), "sampleId": sample, "creative": False, "pose": pose.copy()}
    result = {"status": "completed", "strategy": strategy, "arrivalConfirmed": True,
        "replayAllowed": False, "fallbackUsed": False, "outcomeUnknown": False,
        "playerId": "actual-player", "serverPoseAck": ack, "actualClientPose": pose.copy(),
        "baselineSampleId": sample - 1, "mutationDispatched": True, "elapsedMillis": 40}
    if strategy == "walking_step":
        dy = 1 if phase == "bootstrap_jump_up" else -1
        result["stepReceipt"] = {"version": 1, "deltaY": dy,
            **dict.fromkeys(("clientEnvelopeProven", "serverEnvelopeProven", "clientStartSupportProven",
                "serverStartSupportProven", "clientLandingSupportProven", "serverLandingSupportProven",
                "clientAirObserved", "serverAirObserved", "groundAirGroundConfirmed", "keysReleased",
                "ownerClearConfirmed", "noAbilityOrMotionWrites"), True),
            **dict.fromkeys(("partial", "outcomeUnknown", "fallbackUsed", "replayAllowed"), False),
            "requestedJumpEdges": int(dy > 0), "nativeJumpEdges": int(dy > 0),
            "actualClientBody": body_at(destination), "actualServerBody": body_at(destination)}
    elif strategy == "ability_flight":
        result["flightTransitConfirmed"] = True
    return result


def install_navigation(task, state, monkeypatch, *, fault=None, mutate=None):
    native_workflow = task.workflow
    calls = []

    async def workflow(phase, steps):
        moves = [step for step in steps if step.get("tool") == "mcb_navigate"]
        if not moves:
            return await native_workflow(phase, steps)
        assert len(moves) == len(steps) == 1
        args = moves[0]["args"]
        destination = {axis: args[axis] for axis in ("x", "y", "z")}
        calls.append((phase, args["strategy"], destination))
        if phase == fault:
            raise BridgeError("timeout", "Offline unknown native key dispatch", outcome_unknown=True)
        value = navigation_receipt(task, destination, args["strategy"], phase, 100 + len(calls))
        if mutate is not None:
            mutate(phase, value)
        state["position"] = dict(destination)
        return {"status": "completed", "steps": [{"id": "navigate", "observation": {"values": value}}]}

    monkeypatch.setattr(task, "workflow", workflow)
    return calls


async def prepared(task, state, monkeypatch, **navigation_options):
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    def placement_receipt(receipt):
        constraint = task._controller_placement_pose
        if constraint is not None and constraint.get("groundedBootstrap"):
            front = construction._FRONT[constraint["direction"]]
            declaration = task.bootstrap_adjacent
            point = task.args["controller"]
            eye = {"x": state["position"]["x"], "y": state["position"]["y"] + 1.62, "z": state["position"]["z"]}
            hit = {"x": point["x"] + .5 + .499 * (declaration["offsetX"] - front[0]),
                "y": point["y"], "z": point["z"] + .5 + .499 * (declaration["offsetZ"] - front[2])}
            constraint["eye"] = eye
            receipt.update(expectedPlacementFacing=constraint["direction"], clientPlacementFacingVerified=True,
                serverPlacementFacingVerified=True, endingView=construction._placement_aim(eye, hit))
            receipt["attachment"].update({"hit" + axis.upper(): value for axis, value in hit.items()})
    places = install_finite_native_receipts(task, state, monkeypatch, mutate=placement_receipt)
    moves = install_navigation(task, state, monkeypatch, **navigation_options)
    return places, moves


async def test_long_bootstrap_reuses_native_transit_window_without_extending_task(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch, timeout_ms=30000)
    _, moves = await prepared(task, state, monkeypatch)
    task.approach_health['nativeNavigationSegmentsVersion'] = 1
    deadline = task.deadline
    current = state['position']
    destination = {**current, 'x':current['x']+15, 'y':current['y']+1}
    await task.bootstrap_navigation(destination, 'walking', 'survival_frontier_jump_approach')
    evidence = task.bootstrap_evidence['navigation'][-1]
    assert evidence['navigationWindow'] == {'timeout_ms':15000,'segmented':True}
    assert evidence['completed'] and len(moves) == 1 and task.deadline == deadline
    for capability in (None, True, 2):
        task.approach_health['nativeNavigationSegmentsVersion'] = capability
        assert task.native_navigation_window('walking', 15) == {'timeout_ms':6000}
    task.approach_health['nativeNavigationSegmentsVersion'] = 1
    assert task.native_navigation_window('walking', 2) == {'timeout_ms':6000}
    assert task.native_navigation_window('walking_step', 15) == {'timeout_ms':6000}
    task.deadline = anyio.current_time()+3
    assert 0 < task.native_navigation_window('walking',15)['timeout_ms'] <= 3000


@pytest.mark.parametrize("fault,reason", [("missing_facing", "controller_placement_facing_unavailable"),
    ("missing_capability", "controller_placement_facing_unavailable"),
    ("boolean_capability", "controller_placement_facing_unavailable"),
    ("parallel_seed", "controller_placement_stance_unsupported")])
async def test_facing_preflight_stops_before_consuming_any_seed(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    if fault == "missing_facing":
        task.args.pop("controllerFacing")
        assert list(execution_validator("mcb_blueprint_build").iter_errors(task.args))
    elif fault == "parallel_seed": task.args["controllerFacing"] = "EAST"
    else:
        original = task.call
        async def call(operation, args):
            result = await original(operation, args)
            if operation == "bridge.health":
                if fault == "missing_capability": result.pop("placementFacingVersion")
                else: result["placementFacingVersion"] = True
            return result
        monkeypatch.setattr(task, "call", call)
    with pytest.raises(BridgeError) as error: await task.preflight()
    assert error.value.details["reason"] == reason and task.supports == [] and writes(state) == []


async def test_grounded_constraint_binds_actual_stance_with_existing_eight_field_guard(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    _, moves = await prepared(task, state, monkeypatch)
    await task.place_controller()
    pose = task.bootstrap_evidence["controllerPlacementPose"]
    assert pose["actualClickDirectionVerified"] and pose["freshControllerAlignmentVerified"]
    assert pose["teleportAttempted"] is False and moves[-1][1] == "walking"
    assert len(task.controller_pose_fields(pose["feet"])) == 8
    assert task.controller_pose_fields(pose["feet"])[1:3] == (
        ("player.serverFlight.flying", False), ("player.serverFlight.creative", False))


@pytest.mark.parametrize("fault", [None, "client", "server", "direction"])
async def test_grounded_native_facing_proof_handles_float_retrace_without_weakening_direction(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    places, _ = await prepared(task, state, monkeypatch)
    original = task.workflow
    async def workflow(phase, steps):
        result = await original(phase, steps)
        if phase == "controller_place":
            value = next(row["observation"]["values"] for row in result["steps"] if row["id"] == "place")
            value["endingView"]["pitch"] += .003
            if fault in ("client", "server"): value[fault + "PlacementFacingVerified"] = False
            if fault == "direction": value["endingView"]["yaw"] += 90
        return result
    monkeypatch.setattr(task, "workflow", workflow)
    if fault:
        with pytest.raises(BridgeError): await task.place_controller()
    else:
        await task.place_controller()
        assert task.bootstrap_evidence["controllerPlacementPose"]["reconstructedAimMatches"] is False
        assert task.bootstrap_evidence["controllerPlacementPose"]["freshControllerAlignmentVerified"] is True
    assert len(places) == 4


async def test_actual_wrong_controller_facing_stops_before_hints_and_retains_known_consumption(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    places, moves = await prepared(task, state, monkeypatch)
    state["controller_facing"] = "SOUTH"
    with pytest.raises(BridgeError) as error: await task.place_controller()
    assert error.value.details["reason"] == "controller_placement_alignment_changed"
    assert len(places) == 4 and len(task.survival_material_ledger) == 4 and len(moves) == 3
    assert task.bootstrap_down_attempted is False and state["hint_count"] == 0
    with pytest.raises(BridgeError): await task.place_controller()
    assert len(places) == 4


@pytest.mark.parametrize("offset", [(0, 0), (1, 1), (-1, -1)])
def test_adjacent_schema_rejects_zero_and_diagonal(offset):
    args = bootstrap_arguments()
    args["bootstrapAdjacentSeed"].update(offsetX=offset[0], offsetZ=offset[1])
    assert list(execution_validator("mcb_blueprint_build").iter_errors(args))


@pytest.mark.parametrize("strategy", ["walking", "ability_flight"])
def test_adjacent_schema_accepts_only_explicit_cardinal_bounded_strategy(strategy):
    assert not list(execution_validator("mcb_blueprint_build").iter_errors(bootstrap_arguments(movementStrategy=strategy)))
    assert list(execution_validator("mcb_blueprint_build").iter_errors(bootstrap_arguments(movementStrategy="developer_teleport")))


@pytest.mark.parametrize("fault,reason", [
    ("missing_step", "native_step_unavailable"), ("missing_envelope", "native_build_body_exclusion_unavailable"),
    ("insufficient", "initial_seed_material_insufficient"), ("tagged", "initial_seed_source_changed"),
    ("nonair", "bootstrap_target_not_air"), ("exit_conflict", "bootstrap_exit_conflict"),
])
async def test_bootstrap_preflight_fails_before_placement(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    original = task.call
    if fault in ("missing_step", "missing_envelope"):
        async def call(operation, args):
            result = await original(operation, args)
            if operation == "bridge.health":
                result.pop("nativeWalkingStepVersion" if fault == "missing_step" else "approachBuildEnvelopeVersion")
            return result
        monkeypatch.setattr(task, "call", call)
    elif fault == "insufficient":
        task.bootstrap_adjacent["playerInventoryIndex"] = 9
        state["items"][9]["count"] = 1
    elif fault == "tagged": state["items"][11]["hasTag"] = True
    elif fault == "nonair": state["blocks"][key({**task.args["controller"], "x": CONTROLLER["x"] + 1, "y": 2})] = FRAME.copy()
    elif fault == "exit_conflict":
        task.bootstrap_adjacent["exitPosition"] = {"x": 20, "y": 2, "z": 20}
        task.args["shellExitPosition"] = {"x": 21, "y": 2, "z": 20}
    with pytest.raises(BridgeError) as error:
        await task.preflight()
    assert error.value.details["reason"] == reason
    assert task.supports == [] and writes(state) == []


async def test_finite_a_c_jump_b_controller_and_public_binding_precede_descent(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    places, moves = await prepared(task, state, monkeypatch)
    await task.place_controller()
    assert [row[0] for row in places] == ["survival_structure_seed_2", "survival_structure_seed_adjacent",
        "survival_structure_seed_3", "controller_place"]
    assert [row[0] for row in moves] == ["bootstrap_takeoff", "bootstrap_jump_up", "controller_pose_prepare"]
    assert [key(row[1]) for row in places[:3]] == [key(point) for point in task.survival_seed_plan]
    assert all(state["items"][index]["count"] == 1 for index in (9, 10, 11))
    assert [row["phase"] for row in task.survival_material_ledger] == [row[0] for row in places]
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, FRAME, SHEET], monkeypatch)
    original_volume = task.verify_clear_volume

    async def after_descent(positions):
        assert task.bootstrap_down_attempted and all(row["publicGroupMatched"] for row in task.supports)
        raise BridgeError("offline_after_descent", "Offline boundary after production binding and descent")

    monkeypatch.setattr(task, "verify_clear_volume", after_descent)
    with pytest.raises(BridgeError, match="Offline boundary"):
        await task.decide_ports(points, groups)
    assert moves[-1][0:2] == ("bootstrap_step_down", "walking_step")
    assert moves[-1][2] == task.bootstrap_ground()
    assert moves[-1][2]["y"] == 2 and task.floor == 1
    assert task.bootstrap_evidence["navigation"][-1]["receipt"]["stepReceipt"]["nativeJumpEdges"] == 0
    monkeypatch.setattr(task, "verify_clear_volume", original_volume)
    await task.cleanup_supports()
    assert all(row.get("cleanupAttempted") is None and row["refunded"] is False for row in task.supports)


async def test_unknown_jump_retains_a_c_and_never_places_b_controller_or_replays(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    places, moves = await prepared(task, state, monkeypatch, fault="bootstrap_jump_up")
    with pytest.raises(BridgeError): await task.place_controller()
    with pytest.raises(BridgeError): await task.place_controller()
    with pytest.raises(BridgeError): await task.bootstrap_up()
    assert [row[0] for row in places] == ["survival_structure_seed_2", "survival_structure_seed_adjacent"]
    assert len(moves) == 2 and task.bootstrap_up_attempted and not task.bootstrap_down_attempted
    assert task.bootstrap_evidence["navigation"][-1]["outcomeUnknown"] is True
    assert task.bootstrap_evidence["navigation"][-1]["replayAllowed"] is False
    assert state["items"][10]["count"] == 2 and state["items"][1]["count"] == 1
    assert all(state["blocks"][key(row["position"])] == FRAME and not row["refunded"] for row in task.supports)


async def test_wrong_exact_adjacent_public_candidate_retains_blocks_and_stops_before_descent(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    _, moves = await prepared(task, state, monkeypatch)
    await task.place_controller()
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, SHEET, SHEET], monkeypatch)
    with pytest.raises(BridgeError) as error:
        await task.decide_ports(points, groups)
    assert error.value.details["reason"] == "survival_structure_seed_public_mismatch"
    assert len(moves) == 3 and task.bootstrap_down_attempted is False
    assert all(state["blocks"][key(row["position"])]["registryName"] == row["sourceItem"]["registryName"] for row in task.supports)


@pytest.mark.parametrize("field", ["serverAirObserved", "clientAirObserved", "serverLandingSupportProven",
    "clientStartSupportProven", "keysReleased", "ownerClearConfirmed", "noAbilityOrMotionWrites"])
async def test_step_requires_each_native_proof_and_never_repeats(native_construction, monkeypatch, field):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    def mutate(phase, receipt):
        if phase == "bootstrap_jump_up": receipt["stepReceipt"][field] = False
    places, moves = await prepared(task, state, monkeypatch, mutate=mutate)
    with pytest.raises(BridgeError) as error: await task.place_controller()
    assert error.value.details["reason"] == "bootstrap_step_proof_unavailable"
    with pytest.raises(BridgeError): await task.place_controller()
    assert len(places) == 2 and len(moves) == 2


@pytest.mark.parametrize("fault,reason", [("delta", "bootstrap_step_proof_unavailable"),
    ("jump_twice", "bootstrap_step_proof_unavailable"), ("body", "bootstrap_native_body_unavailable"),
    ("ack", "bootstrap_grounded_ack_unavailable"), ("creative", "bootstrap_grounded_ack_unavailable"),
    ("flying", "bootstrap_grounded_ack_unavailable"), ("unknown", "bootstrap_arrival_unavailable")])
async def test_step_rejects_tampered_full_body_delta_and_newer_ground_ack(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    def mutate(phase, receipt):
        if phase != "bootstrap_jump_up": return
        if fault == "delta": receipt["stepReceipt"]["deltaY"] = -1
        elif fault == "jump_twice": receipt["stepReceipt"]["nativeJumpEdges"] = 2
        elif fault == "body": receipt["stepReceipt"]["actualServerBody"]["maxX"] += 1
        elif fault == "ack": receipt["serverPoseAck"]["sampleId"] = receipt["baselineSampleId"]
        elif fault == "creative": receipt["actualClientPose"]["creative"] = True
        elif fault == "flying": receipt["serverPoseAck"]["pose"]["flying"] = True
        elif fault == "unknown": receipt["outcomeUnknown"] = True
    places, moves = await prepared(task, state, monkeypatch, mutate=mutate)
    with pytest.raises(BridgeError) as error: await task.place_controller()
    assert error.value.details["reason"] == reason and len(places) == len(moves) == 2


async def test_explicit_ability_flight_uses_only_requested_flight_and_no_step(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch, movementStrategy="ability_flight")
    _, moves = await prepared(task, state, monkeypatch)
    await task.place_controller()
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, FRAME, SHEET], monkeypatch)
    await task.bind_survival_seeds(points, groups)
    await task.bootstrap_down()
    assert [row[0:2] for row in moves] == [("bootstrap_flight_up", "ability_flight"), ("bootstrap_flight_down", "ability_flight")]
    with pytest.raises(BridgeError): await task.bootstrap_down()
    assert len(moves) == 2


async def test_unknown_descent_after_public_binding_is_not_replayed(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    _, moves = await prepared(task, state, monkeypatch, fault="bootstrap_step_down")
    await task.place_controller()
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, FRAME, SHEET], monkeypatch)
    with pytest.raises(BridgeError): await task.decide_ports(points, groups)
    with pytest.raises(BridgeError): await task.decide_ports(points, groups)
    assert task.bootstrap_down_attempted and len(moves) == 5
    assert all(row["publicGroupMatched"] is True and row["refunded"] is False for row in task.supports)
    assert task.bootstrap_evidence["navigation"][-1]["outcomeUnknown"] is True


async def test_explicit_flight_without_actual_transit_never_advances_upper_column(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch, movementStrategy="ability_flight")
    def mutate(phase, receipt):
        receipt["flightTransitConfirmed"] = False
    places, moves = await prepared(task, state, monkeypatch, mutate=mutate)
    with pytest.raises(BridgeError) as error: await task.place_controller()
    assert error.value.details["reason"] == "bootstrap_flight_transit_unavailable"
    assert len(places) == 2 and len(moves) == 1


async def test_controller_failure_phase_and_cleanup_distinguish_retained_permanent_seeds(native_construction, monkeypatch):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    places, _ = await prepared(task, state, monkeypatch)
    original_place = task.place
    async def place(point, slot, phase, record):
        if phase == "controller_place": raise BridgeError("no_candidate", "Offline controller ray absence")
        return await original_place(point, slot, phase, record)
    async def execute():
        await task.place_controller()
        pytest.fail("The controller fixture deliberately has no native candidate")
    monkeypatch.setattr(task, "place", place)
    monkeypatch.setattr(task, "execute", execute)
    monkeypatch.setattr(construction, "_ConstructionTask", lambda *args, **kwargs: task)
    result = await construction.run_blueprint_build(client, task.args, anyio.Lock(), runner=run_workflow)
    assert result["failedPhase"] == "controller_placement" and len(places) == 3
    assert result["cleanup"]["allConfirmedSupportsRemoved"] is False
    assert result["cleanup"]["allConfirmedTemporarySupportsRemoved"] is True
    assert result["cleanup"]["permanentSeedsRemoved"] is False
    assert len(result["cleanup"]["permanentStructureSeeds"]) == 3
    assert all("cleanupAttempted" not in row for row in task.supports)


def shell_plan(destination):
    box = body_at(destination)
    return {"status": "ready", "currentOnly": True, "selected": {"feet": dict(destination), "moveRequired": False,
        "clientBody": {"body": copy.deepcopy(box)}, "serverBody": {"body": copy.deepcopy(box)}}}


@pytest.mark.parametrize("fault", [None, "client_inside", "server_inside", "partial_body", "missing_version"])
async def test_ordinary_survival_shell_has_same_actual_full_body_exclusion(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    assert task.bootstrap_adjacent is None
    task.capture = {"captureId": "fresh-ordinary-capture", "capturedFacing": "NORTH_NORMAL_NONE"}
    controller = task.args["controller"]
    task.bounds = {"worldXYZ": {"min": {**controller, "y": 2}, "max": {**controller, "x": controller["x"] + 2}}}
    task.approach_health = {"approachBuildEnvelopeVersion": 1, "survivalBuildEnvelopeVersion": 1}
    envelope = task.shell_envelope()
    destination = {"x": controller["x"] + .5, "y": 2, "z": controller["z"] - .5}
    plan = shell_plan(destination)
    if fault in ("client_inside", "server_inside"):
        plan["selected"][fault.split("_", 1)[0] + "Body"]["body"] = body_at({"x": controller["x"] + .5, "y": 2, "z": controller["z"] + .5})
    elif fault == "partial_body": plan["selected"]["clientBody"]["body"].pop("maxY")
    elif fault == "missing_version": task.approach_health.pop("survivalBuildEnvelopeVersion")
    calls = []
    async def resolved(target, **kwargs):
        calls.append((target, kwargs))
        return plan
    monkeypatch.setattr(task, "resolved_native_approach", resolved)
    if fault:
        with pytest.raises(BridgeError) as error: await task.ensure_shell_exit()
        assert error.value.details["reason"] == ("native_build_body_exclusion_unavailable" if fault == "missing_version" else "shell_actor_body_overlap")
        assert task.shell_exit_evidence == []
    else:
        assert await task.ensure_shell_exit() is plan
        assert calls == [(controller, {"allowed_sides": (2,), "excluded_build_envelope": envelope})]
        assert task.shell_exit_evidence[0]["excludedBuildEnvelope"] == envelope
    assert writes(state) == []


async def test_no_bootstrap_keeps_original_two_layer_column_without_step(native_construction, monkeypatch):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    assert [row[0] for row in places] == ["survival_structure_seed_2", "survival_structure_seed_3", "controller_place"]
    assert task.bootstrap_evidence["navigation"] == [] and task.bootstrap_up_attempted is False


async def test_shell_exit_drift_before_native_submission_stops_with_zero_callbacks(native_construction, monkeypatch):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    controller = task.args["controller"]
    task.capture = {"captureId": "fresh-drift-capture", "capturedFacing": "NORTH_NORMAL_NONE"}
    task.bounds = {"worldXYZ": {"min": dict(controller), "max": dict(controller)}}
    task.approach_health = {"approachBuildEnvelopeVersion": 1, "survivalBuildEnvelopeVersion": 1}
    task.materials[0] = {"item": BLUEPRINT.copy(), "count": 1}
    outside = shell_plan({"x": controller["x"] + .5, "y": 2, "z": controller["z"] - .5})
    reads = []
    async def resolved(target, **kwargs):
        reads.append("outside_exit")
        return outside
    async def planned(target, **kwargs):
        reads.append("planned_front")
        return controller, 2, []
    async def drifted(target, **kwargs):
        reads.append("fresh_current")
        assert kwargs["excluded_build_envelope"] == task.shell_envelope()
        return {"status": "no_candidate", "currentOnly": True, "selected": None}
    async def no_callback(*args, **kwargs):
        pytest.fail("Current body/face absence cannot dispatch a native shell")
    monkeypatch.setattr(task, "resolved_native_approach", resolved)
    monkeypatch.setattr(task, "planned_approach", planned)
    monkeypatch.setattr(task, "current_native_approach", drifted)
    monkeypatch.setattr(task, "workflow", no_callback)
    with pytest.raises(BridgeError) as error: await task.blueprint_use(build=True)
    assert error.value.details["reason"] == "shell_current_body_unavailable"
    assert reads == ["outside_exit", "planned_front", "fresh_current"]
    assert len(task.shell_exit_evidence) == 1 and task.survival_build_receipt is None and writes(state) == []


@pytest.mark.parametrize("fault", [None, "direction", "rotation", "flip"])
async def test_grounded_layout_source_must_match_declared_native_facing(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = bootstrap_task(client, state, monkeypatch)
    await task.preflight()
    alignment = {"direction": "NORTH", "rotation": "normal", "flip": "none"}
    if fault: alignment[fault] = {"direction": "UP", "rotation": "clockwise", "flip": "horizontal"}[fault]
    task.placement_plan = {"geometry": {"alignment": alignment}}
    if fault:
        with pytest.raises(BridgeError): task.controller_pose_constraint()
    else:
        assert task.controller_pose_constraint()["groundedBootstrap"]
        assert task.controller_pose_constraint()["alignment"] == alignment
    assert not writes(state)
