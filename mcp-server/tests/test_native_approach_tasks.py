"""Optional composite planning retains real native actions and fails closed."""

import copy
import math
import time

import pytest

from minecraft_codex_bridge.bridge import BridgeError, READ_OPERATIONS, _ContextData
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import (
    BACKGROUND, CONTROLLER, CONTROLLER_ITEM, SUPPORT, declaration as construction_declaration,
    invoke as construct, key, lathe_position_sync_task, native_construction,
)
from test_recipe_tasks import (
    INPUT, MACHINE, invoke as execute_recipe, native_bridge, recipe_position_sync_task,
)


VECTORS = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}


def observed_block(state, point, dimension):
    if "blocks" in state:
        identity = copy.deepcopy(state["blocks"].get(key(point)))
        if identity is None:
            identity = {"registryName": "minecraft:bedrock" if point["y"] == 0 else "minecraft:grass" if point["y"] == 1 else "minecraft:air", "metadata": 0}
        if identity["registryName"] == "gregtech:gt.blockmachines":
            identity.update(metadata=0, gregtech={"metaTileId": identity.pop("itemMetadata"), "metaClass": "example.PublicMultiblock"})
    else:
        identity = {"registryName": "gregtech:gt.blockmachines", "metadata": 0,
            "gregtech": {"metaTileId": 10001 if point == MACHINE else 77 if point == INPUT else 81, "metaClass": "example.PublicMultiblock"}}
    return {**point, **identity, "source": "integrated_server", "dimension": dimension, "worldTick": 100}


def composite_receipt(state, args):
    target = {axis: args[axis] for axis in ("x", "y", "z")}
    dimension, pattern = args["dimension"], args["pattern"]
    current = copy.deepcopy(state["position"])
    screen_open = state.get("gui", state.get("open")) is not None
    slot = state.get("selected", 0)
    items = state.get("items", {})
    current.update(feetY=current["y"], eyeY=current["y"] + 1.62, eyeOffset=1.62, width=.6, height=1.8,
        normalReach=5, maxDistance=3, selectedHotbarSlot=slot, requestedHotbarSlot=args.get("hotbarSlot", slot),
        clientFlying=state["flying"], screenOpen=screen_open, sneaking=False, cursorEmpty=state["cursor"] is None,
        screenId="controller-screen" if screen_open else "", yaw=0, pitch=0,
        captureWorldTick=100, recheckWorldTick=100, item=copy.deepcopy(items.get(slot)),
        sourceItem=copy.deepcopy(items.get(args.get("hotbarSlot", slot))))
    snapshot = {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": copy.deepcopy(BACKGROUND),
        "screenOpen": screen_open, "screenId": current["screenId"],
        "world": {"worldName": args["expectedWorld"], "worldFolder": args["expectedWorld"], "dimension": dimension},
        "player": {"health": state.get("health", 20), "x": current["x"], "feetY": current["y"], "z": current["z"], "eyeY": current["eyeY"],
            "selectedHotbarSlot": slot, "flying": state["flying"], "yaw": 0, "pitch": 0,
            "serverFlight": {"available": True, "flying": state["flying"], "allowFlying": True, "dimension": dimension}}}
    flight = {"available": True, "creative": True, "flying": state["flying"], "allowFlying": True,
        "dimension": dimension, "worldTick": 100, "ageMillis": 0, "source": "integrated_server", "onGround": False}
    attachments = []
    if args["intent"] == "place_block":
        for side in args["allowedSides"]:
            vector = VECTORS[side]
            point = {axis: target[axis] - vector[index] for index, axis in enumerate(("x", "y", "z"))}
            block = observed_block(state, point, dimension)
            if 0 <= point["y"] <= 255 and block["registryName"] not in ("minecraft:air", "minecraft:water", "minecraft:lava"):
                attachments.append({**block, "side": side})
    else:
        attachments = [{**observed_block(state, target, dimension), "side": side} for side in args["allowedSides"]]
    heights = (target["y"] - 1, target["y"], target["y"] - 2, target["y"] + 1) if pattern == "surround" else \
        (target["y"] - 1, target["y"], target["y"] + 1)
    offsets = ((0, -2), (0, 2), (-2, 0), (2, 0), (-2, -2), (2, -2), (-2, 2), (2, 2)) if pattern == "surround" else \
        ((0, -2), (0, 2), (-2, 0), (2, 0))
    feet = [{axis: current[axis] for axis in ("x", "y", "z")}] + [
        {"x": target["x"] + .5 + dx, "y": y, "z": target["z"] + .5 + dz}
        for y in heights if 0 <= y <= (253 if pattern == "surround" else 254) for dx, dz in offsets]
    selected = None
    for index, point in enumerate(feet):
        aligned = all(abs(point[axis] - math.floor(point[axis]) - (.5 if axis != "y" else 0)) < .0001 for axis in ("x", "y", "z"))
        if not aligned:
            continue
        if "blocks" in state and (point["y"] < 2 or any(observed_block(state,
                {"x": math.floor(point["x"]), "y": point["y"] + dy, "z": math.floor(point["z"])}, dimension)["registryName"] != "minecraft:air" for dy in (0, 1))):
            continue
        if state.get("all_body_blocked") or point["y"] in state.get("blocked_feet_heights", []):
            continue
        eye = {**point, "y": point["y"] + 1.62}
        for attachment in attachments:
            vector = VECTORS[attachment["side"]]
            if pattern == "cardinal" and index and not (point["x"] == target["x"] + .5 + 2 * vector[0]
                    and point["z"] == target["z"] + .5 + 2 * vector[2]):
                continue
            face = {axis: attachment[axis] + .5 + .499 * vector[index] for index, axis in enumerate(("x", "y", "z"))}
            hit = {axis: attachment[axis] + (.5 if not vector[index] else 1 if vector[index] == 1 else 0)
                for index, axis in enumerate(("x", "y", "z"))}
            if sum((eye[axis] - face[axis]) * vector[index] for index, axis in enumerate(("x", "y", "z"))) <= .001 \
                    or sum((eye[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) > 9:
                continue
            bounds = {"minX": point["x"] - .3, "minY": point["y"] + .00001, "minZ": point["z"] - .3,
                "maxX": point["x"] + .3, "maxY": point["y"] + 1.8, "maxZ": point["z"] + .3}
            body = {"body": bounds, "loaded": True, "status": "observed", "occupiable": True, "canStand": True,
                "supported": False, "collisionCount": 0, "entityCoveragePartial": False, "liquid": False, "hazard": False,
                "dimension": dimension, "worldTick": 100}
            ray = {"native": True, "verified": True, "status": "observed", "block": {axis: attachment[axis] for axis in ("x", "y", "z")},
                "side": attachment["side"], "hit": hit, "registryName": attachment["registryName"], "metadata": attachment["metadata"],
                "dimension": dimension, "worldTick": 100}
            if "gregtech" in attachment:
                ray["gregtech"] = attachment["gregtech"]
            selected = {"feet": point, "eye": eye, "attachment": attachment, "hit": hit, "aim": {"yaw": 0, "pitch": 0},
                "moveRequired": any(point[axis] != current[axis] for axis in ("x", "y", "z")), "unknownChecks": 0,
                "serverBody": {**body, "source": "integrated_server"}, "clientBody": {**body, "source": "client"},
                "serverRay": {**ray, "source": "integrated_server"}, "clientRay": {**ray, "source": "client"}}
            break
        if selected:
            break
    assert selected is not None, "The fixture needs a real clear approach candidate"
    result = {"source": "client_server_client", "sessionId": "session", "dimension": dimension, "expectedWorld": args["expectedWorld"],
        "intent": args["intent"], "pattern": pattern, "readOnly": True, "submitted": False, "nonAtomic": True,
        "target": observed_block(state, target, dimension), "serverFlight": flight, "current": current, "selected": selected,
        "status": "ready", "partial": False, "complete": False, "nextOffset": None, "stopReason": "first_verified_candidate",
        "coverage": {"candidateSlots": 33 if pattern == "surround" else 13, "offset": 0, "candidatesExamined": index + 1,
            "serverRays": 1, "clientRays": 1, "unknownChecks": state.get("earlier_unknown", 0), "normalDistanceBound": 3,
            "maxCandidates": 33, "maxServerRays": 198, "workNanosPerPhase": 4_000_000}, "clientWorldTick": 100}
    return _ContextData(copy.deepcopy(result), {"version": 1, "operation": "player.plan_approach", "phase": "after", "snapshot": snapshot})


def install_planner(client, state, *, mutate=None, error=None, capability_format="list"):
    if isinstance(state["caps"], list):
        state["caps"].append("player.plan_approach")
        if capability_format == "dict":
            state["caps"] = dict.fromkeys(state["caps"], True)
    original = client.call

    async def call(operation, args, **options):
        if operation == "player.plan_approach":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            if error is not None:
                raise error
            result = composite_receipt(state, args)
            if mutate is not None:
                mutate(result)
            return result
        result = await original(operation, args, **options)
        if operation == "bridge.health":
            result.update(nativeApproachPlanning=True, approachPlanningIntents=["place_block", "use", "attack"],
                contextRpc={"version": 1, "reads": ["player.plan_approach"], "actions": []})
        return result

    client.call = call


def mutation_trace(state):
    return [(call["operation"], call.get("args")) for call in state["calls"] if call["operation"] not in READ_OPERATIONS]


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("capability_format", ["list", "dict"])
async def test_composite_builder_keeps_real_formation_registered_roles_and_single_native_actions(native_construction, order, capability_format):
    client, state = native_construction
    install_planner(client, state, capability_format=capability_format)
    result = await construct(client, construction_declaration(constructionOrder=order, restoreInventory=False, includeDetails=False))
    assert result["status"] == "completed", result
    assert result["formationVerified"] is True and result["acceptance"]["registrationAcceptance"]["complete"] is True
    assert state["construct_count"] == state["rechecks"] == 1
    plans = [call for call in state["calls"] if call["operation"] == "player.plan_approach"]
    assert {call["args"]["intent"] for call in plans} == {"place_block", "use", "attack"}
    first_open = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact")
    assert not any(call["operation"] == "world.spatial" for call in state["calls"][:first_open])
    assert not any(call["operation"] == "world.spatial" and call["args"]["mode"] == "positions" for call in state["calls"])
    assert result["approachPlanning"]["selectedPlans"] == len(plans)
    assert result["approachPlanning"]["searchComplete"] is False and "remain unknown" in result["approachPlanning"]["scope"]


async def test_composite_recipe_keeps_real_native_gui_deltas_and_explicit_slot_without_extra_owner_read(native_bridge):
    client, state = native_bridge
    install_planner(client, state)
    result = await execute_recipe(client)
    assert result["status"] == "completed", result
    plans = [call for call in state["calls"] if call["operation"] == "player.plan_approach"]
    assert plans and all(call["args"]["pattern"] == "cardinal" and "targetIdentity" not in call["args"] for call in plans)
    assert result["executionsCompleted"] == 1 and result["acceptance"]["consumed"] == 8 and result["acceptance"]["outputDelta"] == 1
    first_open = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact")
    assert not any(call["operation"] == "world.spatial" for call in state["calls"][:first_open])
    assert not any(call["operation"] == "world.spatial" and call["args"]["mode"] == "positions" for call in state["calls"])


def construction_task(client, state):
    task = _ConstructionTask(client, construction_declaration(), run_workflow, started=time.monotonic())
    task.capabilities = state["caps"]
    task.approach_health = {"nativeApproachPlanning": True, "approachPlanningIntents": ["place_block", "use", "attack"],
        "contextRpc": {"version": 1, "reads": ["player.plan_approach"], "actions": []}}
    task.materials[6] = {"item": SUPPORT, "count": 64}
    task.empty_slot = 7
    return task


async def test_composite_place_replaces_four_planning_reads_but_preserves_native_mutation_trace(native_construction):
    client, state = native_construction
    task = construction_task(client, state)
    task.capabilities = [cap for cap in state["caps"] if cap != "player.plan_approach"]
    target = {"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}
    initial = copy.deepcopy(state)
    await task.place(target, 6, "legacy")
    old_calls = copy.deepcopy(state["calls"])
    old_mutations = mutation_trace(state)
    state.clear()
    state.update(initial)
    install_planner(client, state)
    task = construction_task(client, state)
    await task.place(target, 6, "composite")
    assert mutation_trace(state) == old_mutations
    old_first = next(index for index, call in enumerate(old_calls) if call["operation"] not in READ_OPERATIONS)
    new_first = next(index for index, call in enumerate(state["calls"]) if call["operation"] not in READ_OPERATIONS)
    old_plan = [call for call in old_calls[:old_first] if call["operation"] in ("world.inspect_many", "client.snapshot", "world.spatial")]
    new_plan = [call for call in state["calls"][:new_first] if call["operation"] == "player.plan_approach"]
    assert [call["operation"] for call in old_plan[:4]] == ["world.inspect_many", "client.snapshot", "world.spatial", "world.spatial"]
    assert len(new_plan) == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1


@pytest.mark.parametrize("mutation", [
    lambda value: value.update(status="partial", partial=True, nextOffset=1),
    lambda value: value.update(status="unknown"),
    lambda value: value.pop("nextOffset"),
    lambda value: value.update(source="client"),
    lambda value: value.update(dimension=False),
    lambda value: value.update(sessionId="other"),
    lambda value: value.update(expectedWorld="other"),
    lambda value: value["target"].update(registryName="minecraft:stone"),
    lambda value: value["serverFlight"].update(flying=False),
    lambda value: value["serverFlight"].update(ageMillis=1001),
    lambda value: value["current"].update(eyeOffset=float("nan")),
    lambda value: value["current"].update(normalReach=1),
    lambda value: value["current"].update(yaw=5),
    lambda value: value["current"].update(screenId="replaced-screen"),
    lambda value: value["selected"].update(moveRequired=False),
    lambda value: value["selected"].update(unknownChecks=1),
    lambda value: value["selected"].pop("attachment"),
    lambda value: value["selected"]["feet"].update(y=1),
    lambda value: value["selected"]["serverBody"].update(loaded=False),
    lambda value: value["selected"]["clientBody"].update(entityCoveragePartial=True),
    lambda value: value["selected"]["serverBody"]["body"].update(minX=0),
    lambda value: value["selected"]["clientRay"].update(verified=False),
    lambda value: value["selected"]["serverRay"].update(side=5),
    lambda value: value["selected"]["serverRay"].update(worldTick=99),
    lambda value: value["selected"]["clientBody"].update(worldTick=99),
    lambda value: value["selected"]["clientRay"]["block"].update(x=float(value["selected"]["clientRay"]["block"]["x"])),
    lambda value: value["coverage"].update(clientRays=2),
    lambda value: value["coverage"].update(serverRays=199),
    lambda value: value["coverage"].update(workNanosPerPhase=8_000_000),
    lambda value: value.context["snapshot"]["player"].update(x=0),
    lambda value: value.context["snapshot"]["background"].update(enabled=False),
])
async def test_advertised_bad_planner_receipt_stops_before_any_mutation_or_legacy_read(native_construction, mutation):
    client, state = native_construction
    install_planner(client, state, mutate=mutation)
    task = construction_task(client, state)
    with pytest.raises(BridgeError) as failure:
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert "approachReceipt" in failure.value.details or failure.value.details.get("reason") == "session_changed"
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]
    assert task.side_effects is False and not mutation_trace(state)


async def test_unknown_earlier_candidates_remain_unknown_while_selected_proof_can_be_used(native_construction):
    client, state = native_construction
    state["earlier_unknown"] = 5
    install_planner(client, state)
    task = construction_task(client, state)
    await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    summary = task.approach_summary()
    assert summary["earlierUnknownChecks"] == 5 and summary["selectedUnknownChecks"] == 0 and summary["searchComplete"] is False


async def test_real_fractional_flight_age_is_accepted_without_weakening_freshness(native_construction):
    client, state = native_construction
    install_planner(client, state, mutate=lambda value: value["serverFlight"].update(ageMillis=.375))
    task = construction_task(client, state)
    await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert task.approach_summary()["selectedPlans"] == 1


@pytest.mark.parametrize("error", [BridgeError("REFUSED", "readonly refusal", outcome_unknown=False),
    BridgeError("BRIDGE_TIMEOUT", "readonly timeout", outcome_unknown=False)])
async def test_advertised_planner_transport_failure_has_one_dispatch_and_no_legacy_fallback(native_construction, error):
    client, state = native_construction
    install_planner(client, state, error=error)
    task = construction_task(client, state)
    with pytest.raises(BridgeError) as failure:
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert failure.value is error and task.side_effects is False
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]


async def test_composite_gt_target_keeps_raw_block_metadata_separate_from_meta_tile_id(native_construction):
    client, state = native_construction
    state["blocks"][key(CONTROLLER)] = {"registryName": CONTROLLER_ITEM["registryName"], "itemMetadata": CONTROLLER_ITEM["metadata"]}
    install_planner(client, state)
    task = construction_task(client, state)
    _, _, _ = await task.approach([(CONTROLLER, side) for side in (2, 3, 4, 5)])
    args = state["calls"][0]["args"]
    assert args["targetIdentity"] == {"registryName": CONTROLLER_ITEM["registryName"], "metaTileId": CONTROLLER_ITEM["metadata"]}
    assert "metadata" not in args["targetIdentity"]


async def test_composite_open_gui_retains_closed_step_and_fresh_no_move_pose_guard(native_construction):
    client, state = native_construction
    state["blocks"][key(CONTROLLER)] = {"registryName": CONTROLLER_ITEM["registryName"], "itemMetadata": CONTROLLER_ITEM["metadata"]}
    state.update(gui="controller", position={"x": CONTROLLER["x"] + .5, "y": CONTROLLER["y"], "z": CONTROLLER["z"] - 1.5})
    install_planner(client, state)
    task = construction_task(client, state)
    _, _, steps = await task.approach([(CONTROLLER, side) for side in (2, 3, 4, 5)])
    assert steps[0]["tool"] == "mcb_close_gui" and not any(step.get("tool") == "mcb_teleport" for step in steps)
    assert {step["id"] for step in steps if step["id"].startswith("same_")} == {"same_x", "same_y", "same_z"}


async def test_advertised_malformed_intent_contract_cannot_silently_select_legacy(native_construction):
    client, state = native_construction
    install_planner(client, state)
    task = construction_task(client, state)
    task.approach_health["approachPlanningIntents"] = ["use"]
    with pytest.raises(BridgeError, match="advertised approach planner"):
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert not state["calls"]


def planned_sync_task(kind, client, state, order, *, timeout_ms=5000):
    if kind == "construction":
        task = lathe_position_sync_task(client, state, order, x_already_matches=False, timeout_ms=timeout_ms)
    else:
        task = recipe_position_sync_task(client, state, order, x_already_matches=False, timeout_ms=timeout_ms)
    task.capabilities = state["caps"]
    task.approach_health = {"nativeApproachPlanning": True, "approachPlanningIntents": ["place_block", "use", "attack"],
        "contextRpc": {"version": 1, "reads": ["player.plan_approach"], "actions": []}}
    return task


async def run_sync_task(kind, task):
    return await task.place(task.args["controller"], 1, "controller_place") if kind == "construction" else await task.open_gui("input")


@pytest.mark.parametrize("kind", ["construction", "recipe"])
@pytest.mark.parametrize("order", [("x", "y", "z"), ("z", "x", "y"), ("y", "z", "x")])
async def test_composite_plan_retains_fresh_xyz_sync_before_one_native_action(native_construction, native_bridge, kind, order):
    client, state = native_construction if kind == "construction" else native_bridge
    install_planner(client, state)
    task = planned_sync_task(kind, client, state, order)
    await run_sync_task(kind, task)
    assert state["position"] == state["last_teleport_destination"] and not state["position_frames"]
    assert len([call for call in state["calls"] if call["operation"] == "player.plan_approach"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1
    action = "player.place_block" if kind == "construction" else "player.interact"
    assert len([call for call in state["calls"] if call["operation"] == action]) == 1


@pytest.mark.parametrize("kind", ["construction", "recipe"])
@pytest.mark.parametrize("axis", ["y", "z"])
async def test_composite_plan_cannot_use_different_final_xyz_snapshots_or_replay(native_construction, native_bridge, kind, axis):
    client, state = native_construction if kind == "construction" else native_bridge
    install_planner(client, state)
    task = planned_sync_task(kind, client, state, ("y", "z", "x"))
    state["position_sync_drift"] = axis
    with pytest.raises(BridgeError) as failure:
        await run_sync_task(kind, task)
    assert failure.value.code == "ASSERTION_FAILED"
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1
    assert not any(call["operation"] in ("player.place_block", "player.interact", "gui.slot_click") for call in state["calls"])


@pytest.mark.parametrize("kind", ["construction", "recipe"])
async def test_fresh_action_flight_drift_after_composite_plan_stops_before_movement(native_construction, native_bridge, kind):
    client, state = native_construction if kind == "construction" else native_bridge
    install_planner(client, state, mutate=lambda value: state.update(flying=False))
    task = planned_sync_task(kind, client, state, ("y", "z", "x"))
    with pytest.raises(BridgeError) as failure:
        await run_sync_task(kind, task)
    assert failure.value.code == "ASSERTION_FAILED"
    assert not any(call["operation"] in ("dev.teleport", "player.hotbar", "player.place_block", "player.interact", "gui.slot_click") for call in state["calls"])


async def test_native_placement_refusal_after_composite_plan_never_selects_legacy_mutation(native_construction):
    client, state = native_construction
    state["native_face_refusal"] = "CLIENT_FACE_UNAVAILABLE"
    install_planner(client, state)
    task = construction_task(client, state)
    with pytest.raises(BridgeError) as failure:
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert failure.value.code == "CLIENT_FACE_UNAVAILABLE"
    assert len([call for call in state["calls"] if call["operation"] == "player.plan_approach"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    assert not any(call["operation"] == "player.interact" for call in state["calls"])


@pytest.mark.parametrize("pattern", ["surround", "cardinal"])
@pytest.mark.parametrize("axis,delta", [("x", .00001), ("y", -.00001)])
async def test_near_current_fixed_slot_can_correct_its_center_with_exact_movement_flag(native_construction, pattern, axis, delta):
    client, state = native_construction
    state["blocks"][key(CONTROLLER)] = {"registryName": CONTROLLER_ITEM["registryName"], "itemMetadata": CONTROLLER_ITEM["metadata"]}
    state["position"] = {"x": CONTROLLER["x"] + 1.5, "y": CONTROLLER["y"], "z": CONTROLLER["z"] + .5}

    def corrected(value):
        value["current"][axis] += delta
        if axis == "y":
            for field in ("feetY", "eyeY"):
                value["current"][field] += delta
                value.context["snapshot"]["player"][field] += delta
        else:
            value.context["snapshot"]["player"][axis] += delta
        value["selected"]["moveRequired"] = True

    install_planner(client, state, mutate=corrected)
    task = _RecipeTask(client, construction_declaration(), run_workflow)
    result = await task.native_approach(CONTROLLER, pattern=pattern)
    assert result["selected"]["moveRequired"] is True
    assert result["selected"]["feet"]["x"] == CONTROLLER["x"] + 1.5
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]


async def test_selected_body_height_cannot_cross_world_top_even_with_typed_clear_flags(native_construction):
    client, state = native_construction
    target = {**CONTROLLER, "y": 255}
    state["blocks"][key(target)] = {"registryName": CONTROLLER_ITEM["registryName"], "itemMetadata": CONTROLLER_ITEM["metadata"]}
    state["position"] = {"x": target["x"] + .5, "y": 254, "z": target["z"] - 1.5}

    def tall(value):
        value["current"]["height"] = 3
        for field in ("serverBody", "clientBody"):
            value["selected"][field]["body"]["maxY"] = 257

    install_planner(client, state, mutate=tall)
    task = _RecipeTask(client, construction_declaration(), run_workflow)
    with pytest.raises(BridgeError, match="legal standing range"):
        await task.native_approach(target, pattern="cardinal")
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]


def continuation_receipt(value, **changes):
    value["coverage"].update(candidatesExamined=1, serverPages=2, maxServerPages=4,
        candidateAttempts=2, maxCandidateAttempts=36, serverRays=7, unknownChecks=1, **changes)


@pytest.mark.parametrize("pages,examined,attempts,rays", [
    (1, 1, 1, 6),
    (2, 1, 2, 7),
    (4, 1, 4, 24),
    (4, 33, 36, 198),
])
async def test_same_rpc_continuation_counts_repeated_candidates_without_repeating_mutation(native_construction, pages, examined, attempts, rays):
    client, state = native_construction

    def repeat(value):
        continuation_receipt(value)
        value["coverage"].update(serverPages=pages, candidatesExamined=examined, candidateAttempts=attempts, serverRays=rays)

    install_planner(client, state, mutate=repeat)
    task = construction_task(client, state)
    await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert len([call for call in state["calls"] if call["operation"] == "player.plan_approach"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    assert not any(call["operation"] == "player.interact" for call in state["calls"])
    coverage = task.approach_observations[0]["coverage"]
    assert coverage["serverPages"] == pages and coverage["candidateAttempts"] == attempts
    assert task.approach_summary()["earlierUnknownChecks"] == 1 and task.approach_summary()["selectedUnknownChecks"] == 0


@pytest.mark.parametrize("field,bad", [
    ("serverPages", 0), ("serverPages", 5), ("serverPages", True), ("serverPages", 2.0), ("serverPages", "2"), ("serverPages", None),
    ("maxServerPages", 3), ("maxServerPages", 5), ("maxServerPages", True), ("maxServerPages", 4.0), ("maxServerPages", "4"), ("maxServerPages", None),
    ("candidateAttempts", 0), ("candidateAttempts", 3), ("candidateAttempts", True), ("candidateAttempts", 2.0), ("candidateAttempts", "2"), ("candidateAttempts", None),
    ("maxCandidateAttempts", 35), ("maxCandidateAttempts", 37), ("maxCandidateAttempts", True), ("maxCandidateAttempts", 36.0), ("maxCandidateAttempts", "36"), ("maxCandidateAttempts", None),
    ("candidatesExamined", 3), ("candidatesExamined", True), ("candidatesExamined", 1.0),
    ("serverRays", 13), ("serverRays", 199),
])
async def test_invalid_continuation_budget_is_rejected_before_any_mutation_or_legacy_read(native_construction, field, bad):
    client, state = native_construction

    def invalid(value):
        continuation_receipt(value)
        value["coverage"][field] = bad

    install_planner(client, state, mutate=invalid)
    task = construction_task(client, state)
    with pytest.raises(BridgeError) as failure:
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert failure.value.details["reason"] == "native_approach_receipt_unavailable"
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]
    assert task.side_effects is False and not mutation_trace(state)


@pytest.mark.parametrize("missing", ["serverPages", "maxServerPages", "candidateAttempts", "maxCandidateAttempts"])
async def test_partial_continuation_contract_cannot_be_treated_as_a_legacy_receipt(native_construction, missing):
    client, state = native_construction

    def incomplete(value):
        continuation_receipt(value)
        value["coverage"].pop(missing)

    install_planner(client, state, mutate=incomplete)
    task = construction_task(client, state)
    with pytest.raises(BridgeError):
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]
    assert not mutation_trace(state)


@pytest.mark.parametrize("rays,accepted", [(6, True), (7, False)])
async def test_legacy_receipt_without_continuation_fields_retains_unique_candidate_ray_bound(native_construction, rays, accepted):
    client, state = native_construction

    def legacy(value):
        value["coverage"].update(candidatesExamined=1, serverRays=rays)
        assert not {"serverPages", "maxServerPages", "candidateAttempts", "maxCandidateAttempts"}.intersection(value["coverage"])

    install_planner(client, state, mutate=legacy)
    task = construction_task(client, state)
    if accepted:
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
        assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    else:
        with pytest.raises(BridgeError):
            await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
        assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]


async def test_partial_after_bounded_continuation_is_still_rejected_without_fallback(native_construction):
    client, state = native_construction

    def exhausted(value):
        continuation_receipt(value)
        value["coverage"].update(serverPages=4, candidateAttempts=4)
        value.update(status="partial", partial=True, nextOffset=0, stopReason="budget")

    install_planner(client, state, mutate=exhausted)
    task = construction_task(client, state)
    with pytest.raises(BridgeError):
        await task.place({"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"]}, 6, "place")
    assert [call["operation"] for call in state["calls"]] == ["player.plan_approach"]
    assert task.side_effects is False and not mutation_trace(state)
