"""R15 typed DTO regression; HTTP transport is entirely offline.

Observed final coordinates/flight below come from the post-failure public snapshot
in round-15-first-686-pose-guard-v1.json (SHA256
38901a1e2383f06f104e6c56b5e3c6f76cc8429c6b0fd4fecb01368877f356052).
Player/session/world labels are synthetic; geometry is retained.
Intermediate frames are synthetic: the archive has no failure-time snapshot.
"""
import copy
import itertools
import json
import time

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.construction_tasks import _ApproachSteps, _ConstructionTask
from minecraft_codex_bridge.recipe_tasks import _action
from minecraft_codex_bridge.workflows import _WorkflowSnapshotChecks, _workflow_discovery, json_equal, run_workflow

import test_construction_tasks as native
import test_construction_native_pose as pose


ACTUAL_SNAPSHOT = {
    "sessionId": "00000000-0000-4000-8000-000000000002", "profile": "developer",
    "worldLoaded": True, "screenOpen": False,
    "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False},
    "world": {"dimension": 180, "worldName": "ExampleWorld", "worldFolder": "ExampleWorld"},
    "player": {"name": "ExamplePlayer", "x": -599.5, "feetY": 2.0,
        "y": 3.6200000047683716, "eyeY": 3.6200000047683716, "z": -186.5,
        "yaw": 0.0, "pitch": 4.570887, "flying": True, "allowFlying": True,
        "selectedHotbarSlot": 0,
        "serverFlight": {"available": True, "flying": True, "allowFlying": True,
            "creative": True, "onGround": False, "dimension": 180, "worldTick": 5316043,
            "ageMillis": 22.2241, "source": "integrated_server_tick"}},
}
ACTUAL_CONTROLLER = {"x": -600, "y": 3, "z": -185}
FLIGHT_PATHS = ("player.serverFlight.available", "player.serverFlight.flying",
    "player.serverFlight.allowFlying", "player.serverFlight.dimension", "player.flying")


def with_feet(snapshot, feet):
    result = copy.deepcopy(snapshot)
    # The archived coordinates remain unchanged above; this translated offline
    # test actor has synthetic positive health, not archived live health proof.
    result["player"]["health"] = 20
    result["player"].update(x=float(feet["x"]), feetY=float(feet["y"]), z=float(feet["z"]),
        y=float(feet["y"]) + 1.6200000047683716, eyeY=float(feet["y"]) + 1.6200000047683716)
    return result


@pytest.fixture
async def actual_pose_task():
    initial = with_feet(ACTUAL_SNAPSHOT, {"x": -595.5, "y": 5.0, "z": -182.5})
    state = {"snapshot": initial, "calls": [], "frames": [], "order": None, "drift": None,
        "flight_fault": None, "teleports": 0, "placements": 0, "wait_snapshots": []}
    configuration = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "offline-pose-sync"})

    async def respond(request):
        body = json.loads(request.content)
        operation, arguments = body["operation"], body["args"]
        state["calls"].append(body)
        if operation == "client.snapshot":
            if state["frames"]:
                state["snapshot"] = state["frames"].pop(0)
            data = copy.deepcopy(state["snapshot"])
            if state["teleports"]:
                if state["flight_fault"] is not None:
                    path, value = state["flight_fault"]
                    owner = data
                    parts = path.split(".")
                    for part in parts[:-1]:
                        owner = owner[part]
                    owner[parts[-1]] = value
                state["wait_snapshots"].append(copy.deepcopy(data))
        elif operation == "world.spatial":
            data = {"source": "integrated_server", "dimension": 180, "hasMore": False,
                "byteLimited": False, "nextOffset": None,
                "positions": [{**point, "loaded": True, "occupiable": True, "liquid": False,
                    "hazard": False, "entityCoveragePartial": False} for point in arguments["positions"]]}
        elif operation == "dev.teleport":
            state["teleports"] += 1
            assert arguments["requireFlying"] is True
            destination = {axis: arguments[axis] for axis in ("x", "y", "z")}
            if state["order"] is None:
                state["snapshot"] = with_feet(ACTUAL_SNAPSHOT, destination)
            else:
                feet = {"x": state["snapshot"]["player"]["x"],
                    "y": state["snapshot"]["player"]["feetY"], "z": state["snapshot"]["player"]["z"]}
                state["frames"] = [with_feet(ACTUAL_SNAPSHOT, feet)]
                for axis in state["order"]:
                    feet[axis] = destination[axis]
                    state["frames"].append(with_feet(ACTUAL_SNAPSHOT, feet))
                if state["drift"]:
                    final = state["frames"][-1]
                    axis = state["drift"]
                    feet[axis] += .25
                    state["frames"][-1] = with_feet(final, feet)
            data = {"status": "submitted"}
        elif operation == "player.place_block":
            state["placements"] += 1
            data = {"status": "submitted"}
        else:
            raise AssertionError("Unexpected offline pose operation " + operation)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(configuration, timeout=2, transport=httpx.MockTransport(respond))
    arguments = {"dimension": 180, "controller": ACTUAL_CONTROLLER.copy(),
        "controllerItem": {"registryName": "gregtech:gt.blockmachines", "metadata": 686},
        "expectedSessionId": ACTUAL_SNAPSHOT["sessionId"], "expectedWorld": "ExampleWorld", "timeout_ms": 1500}
    task = _ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials,
        {"protocolVersion": 1, "sessionId": ACTUAL_SNAPSHOT["sessionId"], "profile": "developer",
            "capabilities": ["client.snapshot", "client.background", "world.spatial", "dev.teleport", "player.place_block"]})
    task._workflow_background_required = True
    task.placement_plan = {"geometry": {"geometryMode": "native_visits",
        "alignment": {"direction": "NORTH", "rotation": "normal", "flip": "none"}}}
    try:
        yield task, state
    finally:
        await client.aclose()


def test_r15_actual_float_coordinates_equal_exact_prescribed_integer_height():
    actual = ACTUAL_SNAPSHOT["player"]
    assert type(actual["feetY"]) is float and type(ACTUAL_CONTROLLER["y"] - 1) is int
    assert json_equal(actual["feetY"], ACTUAL_CONTROLLER["y"] - 1)
    assert json_equal({"x": actual["x"], "feetY": actual["feetY"], "z": actual["z"]},
        {"x": -599.5, "feetY": 2, "z": -186.5})
    assert not json_equal(True, 1) and not json_equal(False, 0)


async def test_actual_typed_final_snapshot_passes_the_existing_exact_pose_guard(actual_pose_task):
    task, state = actual_pose_task
    constraint = task.controller_pose_constraint()
    await task.prepare_controller_pose(constraint)
    assert constraint["stancePrepared"] and state["teleports"] == 1
    assert state["snapshot"]["player"]["feetY"] == 2.0
    assert type(state["snapshot"]["player"]["feetY"]) is float


@pytest.mark.parametrize("order", list(itertools.permutations(("x", "y", "z"))))
async def test_delayed_actual_double_axes_wait_for_one_complete_destination_snapshot(actual_pose_task, order):
    task, state = actual_pose_task
    state["order"] = order
    constraint = task.controller_pose_constraint()
    await task.prepare_controller_pose(constraint)
    assert constraint["stancePrepared"] and state["teleports"] == 1 and state["placements"] == 0
    expected = {"x": -599.5, "feetY": 2, "z": -186.5}
    assert len(state["wait_snapshots"]) >= 4
    assert all(not all(snapshot["player"][axis] == value for axis, value in expected.items())
        for snapshot in state["wait_snapshots"][:3])
    assert all(state["wait_snapshots"][3]["player"][axis] == value for axis, value in expected.items())
    assert state["frames"] == []


@pytest.mark.parametrize("path,value", [(path, 181 if path.endswith("dimension") else False) for path in FLIGHT_PATHS])
async def test_every_flight_guard_still_refuses_during_destination_wait(actual_pose_task, path, value):
    task, state = actual_pose_task
    state["order"] = ("x", "y", "z")
    state["flight_fault"] = (path, value)
    with pytest.raises(BridgeError, match="fresh step guard") as error:
        await task.prepare_controller_pose(task.controller_pose_constraint())
    assert error.value.code == "ASSERTION_FAILED"
    assert state["teleports"] == 1 and state["placements"] == 0
    assert len(state["wait_snapshots"]) == 1


@pytest.mark.parametrize("axis", ("x", "y", "z"))
async def test_incomplete_destination_times_out_without_teleport_or_click_replay(actual_pose_task, axis):
    task, state = actual_pose_task
    task.deadline = time.monotonic() + .45
    state["order"] = ("x", "y", "z")
    state["drift"] = axis
    with pytest.raises(BridgeError) as error:
        await task.prepare_controller_pose(task.controller_pose_constraint())
    assert error.value.code in {"WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"}
    assert state["teleports"] == 1 and state["placements"] == 0


@pytest.mark.parametrize("axis", ("x", "y", "z"))
async def test_exact_actual_double_pose_boundary_still_refuses_each_before_click_axis(actual_pose_task, axis):
    task, state = actual_pose_task
    constraint = task.controller_pose_constraint()
    await task.prepare_controller_pose(constraint)
    player = state["snapshot"]["player"]
    player["feetY" if axis == "y" else axis] += .25
    steps = _ApproachSteps(_WorkflowSnapshotChecks(task._workflow_discovery,
        (("place", task.controller_pose_fields(constraint["feet"])),)))
    assert len(steps.snapshot_checks.rules[0][1]) == 8
    steps.append(_action("place", "mcb_place_block", {**ACTUAL_CONTROLLER,
        "hotbarSlot": 0, "expectedWorld": "ExampleWorld", "dimension": 180}))
    with pytest.raises(BridgeError, match="fresh step guard") as error:
        await task.workflow("controller_click_boundary", steps)
    assert error.value.code == "ASSERTION_FAILED"
    assert state["teleports"] == 1 and state["placements"] == 0


@pytest.mark.parametrize("order", [("x", "y", "z"), ("z", "y", "x")])
async def test_complete_construction_retains_exact_click_guard_after_delayed_pose(native_construction, monkeypatch, order):
    client, state = native_construction
    pose.configure(client, state, monkeypatch, native_planner=True)
    state["position_sync_order"] = order
    result = await native.invoke(client, pose.declaration())
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["placement"]["controllerPlacementPose"]["actualClickDirectionVerified"]
    assert state["hint_count"] == state["construct_count"] == 1


@pytest.mark.parametrize("restoration", [False, True])
async def test_primary_native_pose_guard_error_and_receipt_survive_later_cleanup_approach_refusal(native_construction, monkeypatch, restoration):
    client, state = native_construction
    pose.configure(client, state, monkeypatch, native_planner=True)
    original_call = client.call
    state["pose_teleported"] = state["primary_guard_failed"] = False

    async def call(operation, arguments, **options):
        data = await original_call(operation, arguments, **options)
        if operation == "dev.teleport":
            state["pose_teleported"] = True
        elif operation == "client.snapshot" and state["pose_teleported"] and not state["primary_guard_failed"]:
            data["player"]["serverFlight"]["flying"] = False
            state["primary_guard_failed"] = True
        return data

    cleanup_error = BridgeError("APPROACH_TARGET_IDENTITY_MISMATCH",
        "Client and server approach block identities do not agree.")
    cleanup_calls = []

    async def refused_cleanup(task):
        cleanup_calls.append(task.phase)
        raise cleanup_error

    monkeypatch.setattr(client, "call", call)
    monkeypatch.setattr(_ConstructionTask, "restore" if restoration else "cleanup_supports", refused_cleanup)
    result = await native.invoke(client, pose.declaration(restoreInventory=restoration))
    assert state["primary_guard_failed"] and result["status"] == "failed", result
    assert result["failedPhase"] == "controller_pose_prepare"
    assert result["error"]["code"] == "ASSERTION_FAILED"
    assert result["error"]["message"] == "The fresh step guard did not retain the required flight and complete pose."
    assert result["error"]["outcome_unknown"] is False
    receipt = result["lastActionReceipt"]
    assert receipt["operation"] == "dev.teleport" and receipt["dispatched"] is True
    assert receipt["outcomeUnknown"] is False and isinstance(receipt["requestId"], str)
    assert result["cleanup"]["error" if restoration else "supportError"] == cleanup_error.as_dict()
    assert len(cleanup_calls) == 1 and not state["placements"]
    assert state["hint_count"] == state["construct_count"] == 0
    assert sum(row["operation"] == "dev.teleport" for row in state["calls"]) == 1


native_construction = native.native_construction
