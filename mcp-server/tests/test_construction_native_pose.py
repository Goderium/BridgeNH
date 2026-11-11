"""Source-facing native placement over real offline HTTP/native workflows."""
import json

import pytest

import test_construction_tasks as native
import test_native_approach_tasks as planner
import test_native_visit_placement as visits
from minecraft_codex_bridge.construction_tasks import _placement_aim, _normal_gt_placement_facing

native_construction = native.native_construction
FRONT = {"NORTH": (0, -1), "EAST": (1, 0), "SOUTH": (0, 1), "WEST": (-1, 0)}


def configure(client, state, monkeypatch, direction="NORTH", *, native_planner=False, native_place=True, fault=None):
    world = visits.NativeWorld()
    world.alignment["direction"] = direction
    if fault == "rotation": world.alignment["rotation"] = "clockwise"
    if fault == "flip": world.alignment["flip"] = "horizontal"
    if fault == "vertical": world.alignment["direction"] = "UP"
    if not native_place:
        state["caps"].remove("player.place_block")
    if native_planner:
        def selected_aim(receipt):
            selected = receipt["selected"]
            selected["aim"] = _placement_aim(selected["eye"], selected["hit"])
            if receipt["intent"] == "place_block" and native.key(receipt["target"]) == native.key(state["controller"]):
                if fault == "planner_move": selected["moveRequired"] = True
                if fault == "planner_aim": selected["aim"]["yaw"] += 90
                if fault == "planner_hit_missing": selected.pop("hit")
        planner.install_planner(client, state, mutate=selected_aim)
    original = visits.builder_wrapper(client, world, state, None)

    async def call(operation, args, **options):
        data = await original(operation, args, **options)
        if operation == "world.spatial":
            if args["mode"] == "positions" and len(args["positions"]) == 1:
                if fault == "body": data["positions"][0]["occupiable"] = False
                if fault == "body_partial": data["positions"][0]["entityCoveragePartial"] = True
                if fault == "body_page": data["hasMore"] = True
            elif args["mode"] == "raycast" and not any(row["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"] for row in state["placements"]):
                if fault == "ray_point": data["hit"].pop("point", None)
                if fault == "ray_partial": data["entityCoveragePartial"] = True
                if fault == "ray_owner": data["dimension"] += 1
        if operation in ("player.place_block", "player.interact") and data.get("endingView") is not None \
                and not state["hint_count"] and state["placements"]:
            if fault in ("click_facing", "legacy_click_facing"):
                data["endingView"]["yaw"] += 180
                state["controller_direction"] = _normal_gt_placement_facing(data["endingView"])
            if fault == "click_view_missing": data.pop("endingView")
            if fault == "click_hit_missing" and operation == "player.place_block": data["attachment"].pop("hitX")
        if operation == "world.structure" and args["x"] != visits.placement.SOURCE["x"] and not state["hint_count"]:
            if fault == "new_alignment": data["alignment"]["direction"] = "SOUTH" if direction != "SOUTH" else "NORTH"
            if fault == "new_owner": data["controller"]["dimension"] += 1
            if fault == "new_class": data["controller"]["metaClass"] += ".Changed"
        return data
    monkeypatch.setattr(client, "call", call)
    return world


def declaration(**changes):
    return native.declaration(placement={"geometryMode": "native_visits", "geometrySource": visits.placement.SOURCE.copy(),
        "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}, supportMode="command", **changes)


@pytest.mark.parametrize("direction", FRONT)
@pytest.mark.parametrize("native_planner,native_place", [(False, False), (False, True), (True, True)])
async def test_actual_click_binds_each_source_direction_and_fresh_alignment_before_hints(native_construction, monkeypatch, direction, native_planner, native_place):
    client, state = native_construction
    configure(client, state, monkeypatch, direction, native_planner=native_planner, native_place=native_place)
    result = await native.invoke(client, declaration())
    assert result["status"] == "completed" and result["formationVerified"], result
    proof = result["placement"]["controllerPlacementPose"]
    target, front = result["placement"]["proposedController"], FRONT[direction]
    assert proof["feet"] == {"x": target["x"] + .5 + 2 * front[0], "y": target["y"] - 1, "z": target["z"] + .5 + 2 * front[1]}
    assert proof["actualClickDirectionVerified"] and proof["freshControllerAlignmentVerified"]
    assert _normal_gt_placement_facing(proof["selectedAim"]) == _normal_gt_placement_facing(proof["actualEndingView"]) == direction
    assert proof["noPlacementPlannerMovement"] and proof["nativePlaceBlockUsed"] is native_place
    assert len([row for row in state["placements"] if row["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"]]) == 1
    assert state["hint_count"] == state["construct_count"] == 1
    calls = state["calls"]
    pose_index = next(index for index, row in enumerate(calls) if row["operation"] == "dev.teleport"
        and all(row["args"][axis] == proof["feet"][axis] for axis in visits.AXES))
    owner_index = next(index for index, row in enumerate(calls) if row["operation"] == "world.structure"
        and native.key(row["args"]) == native.key(target))
    hint_index = next(index for index, row in enumerate(calls) if row["operation"] == "player.interact"
        and native.key(row["args"]) == native.key(target) and row["args"].get("sneaking") is False)
    assert pose_index < owner_index < hint_index


@pytest.mark.parametrize("fault", ["body", "body_partial", "body_page", "ray_point", "ray_partial", "ray_owner"])
async def test_missing_actual_legacy_pose_evidence_refuses_controller_write(native_construction, monkeypatch, fault):
    client, state = native_construction
    configure(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and not state["placements"], result
    assert state["hint_count"] == state["construct_count"] == 0


@pytest.mark.parametrize("fault", ["planner_move", "planner_aim", "planner_hit_missing"])
async def test_native_planner_never_moves_or_guesses_aim_for_constrained_placement(native_construction, monkeypatch, fault):
    client, state = native_construction
    configure(client, state, monkeypatch, native_planner=True, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and not state["placements"], result
    assert state["hint_count"] == state["construct_count"] == 0
    assert sum(row["operation"] == "dev.teleport" for row in state["calls"]) == 1


@pytest.mark.parametrize("fault,native_place", [("click_facing", True), ("legacy_click_facing", False),
    ("click_view_missing", True), ("click_hit_missing", True), ("new_alignment", True), ("new_owner", True), ("new_class", True)])
async def test_actual_click_or_new_controller_drift_blocks_all_blueprint_actions_without_replay(native_construction, monkeypatch, fault, native_place):
    client, state = native_construction
    configure(client, state, monkeypatch, native_place=native_place, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and not result["formationVerified"], result
    assert len(state["placements"]) == 1 and state["hint_count"] == state["construct_count"] == 0
    assert not any(phase["phase"] == "blueprint_hints" for phase in result.get("phases", []))


@pytest.mark.parametrize("fault", ["rotation", "flip", "vertical"])
async def test_other_source_alignments_are_unavailable_before_target_writes(native_construction, monkeypatch, fault):
    client, state = native_construction
    configure(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["reason"] == "controller_placement_alignment_unsupported", result
    assert not state["placements"] and state["hint_count"] == state["construct_count"] == 0
    assert not any(row["operation"] == "chat.send" for row in state["calls"])


@pytest.mark.parametrize("axis", ["x", "y", "z"])
@pytest.mark.parametrize("native_planner", [False, True])
async def test_each_actual_before_click_pose_axis_has_fresh_native_guard(native_construction, monkeypatch, axis, native_planner):
    client, state = native_construction
    configure(client, state, monkeypatch, native_planner=native_planner)
    original = client.http._transport.handle_async_request
    state["guard_drifted"] = False

    async def respond(request):
        body = json.loads(request.content)
        if body["operation"] == "client.snapshot" and not state["placements"]:
            previous = state["calls"][-1] if state["calls"] else {}
            if previous.get("operation") == "world.inspect" and native.key(previous["args"]) == native.key(state["controller"]):
                state["position"][axis] += .25
                state["guard_drifted"] = True
        return await original(request)
    monkeypatch.setattr(client.http._transport, "handle_async_request", respond)
    result = await native.invoke(client, declaration())
    assert state["guard_drifted"] and result["status"] != "completed", result
    assert not state["placements"] and state["hint_count"] == state["construct_count"] == 0


async def test_uncertain_controller_click_is_not_replayed_or_followed_by_hints(native_construction, monkeypatch):
    client, state = native_construction
    configure(client, state, monkeypatch)
    state["disconnect_place"] = True
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and any(phase["outcomeUnknown"] for phase in result["failedNativeWorkflows"]), result
    assert len(state["placements"]) == 1 and state["hint_count"] == state["construct_count"] == 0


@pytest.mark.parametrize("direction", FRONT)
async def test_pose_binding_and_stop_share_the_same_complete_construction(native_construction, monkeypatch, direction):
    from test_construction_native_stop import stopping_world, invoke
    client, state = native_construction
    stopping_world(client, state, monkeypatch)
    configure(client, state, monkeypatch, direction, native_planner=True)
    result = await invoke(client, placement=declaration()["placement"], supportMode="command")
    assert result["status"] == "completed", result
    assert result["placement"]["controllerPlacementPose"]["freshControllerAlignmentVerified"]
    assert result["acceptance"]["stopAcceptance"]["confirmed"] and state["stop_writes"] == state["rechecks"] == 1
