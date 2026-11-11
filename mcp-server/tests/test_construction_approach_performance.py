"""Approach fusion checks current flight and all XYZ without replaying writes."""

import copy
import dataclasses
import itertools

import anyio
import jsonschema
import pytest

from minecraft_codex_bridge.bridge import BridgeError, CONTEXT_ACTIONS, CONTEXT_READS
from minecraft_codex_bridge.construction_tasks import run_blueprint_build
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import (
    _WorkflowSnapshotChecks, _workflow_discovery, condition_matches, run_workflow,
)
from test_construction_context_performance import task_from, operations
from test_construction_segment_coalescing import context_construction
from test_construction_tasks import native_construction, CONTROLLER, declaration, lathe_position_sync_task
from test_guarded_rpc import guarded_bridge, action, read, plan
from test_native_approach_tasks import construction_task, install_planner, planned_sync_task


def install_guarded_planner(client, state, *, mutate=None, inventory_context=True):
    install_planner(client, state, mutate=mutate)
    original = client.call
    async def call(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "bridge.health":
            value["contextRpc"] = {"version": 1,
                "reads": sorted(CONTEXT_READS) if inventory_context else ["player.plan_approach"],
                "actions": sorted(CONTEXT_ACTIONS)}
        return value
    client.call = call


async def seal(task):
    health = await task.call("bridge.health", {})
    task.approach_health = health
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health)
    # This fixture starts with independently confirmed active protection.
    task._workflow_background_required = True


async def test_full_native_planner_construction_keeps_ports_and_final_snapshot(context_construction):
    client, state = context_construction
    receipts = []
    def record(value):
        receipts.append({"index": len(state["calls"]) - 1, "move": value["selected"]["moveRequired"],
            "screenOpen": value["current"]["screenOpen"]})
    install_guarded_planner(client, state, mutate=record)
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "completed" and result["formationVerified"]
    assert len(state["placements"]) == 6 and state["construct_count"] == 1
    assert {call["args"]["intent"] for call in state["calls"] if call["operation"] == "player.plan_approach"} == {"use", "attack", "place_block"}
    assert operations(state)[-1] == "inventory.inspect"
    assert state["calls"][-1]["includeContext"]["requireBackground"] is True
    for receipt in receipts:
        if not receipt["screenOpen"]:
            next_call = state["calls"][receipt["index"] + 1]
            if receipt["move"]:
                assert next_call["operation"] == "client.snapshot"
                next_index = receipt["index"] + 2
                if receipt is receipts[0] and operations(state)[next_index] == "client.snapshot":
                    # No earlier write established the task's protection latch.
                    next_index += 1
                assert operations(state)[next_index] == "dev.teleport"
            else:
                assert next_call["operation"] == "inventory.inspect" and "includeContext" in next_call


@pytest.mark.parametrize("order", list(itertools.permutations(("x", "y", "z"))))
async def test_one_compound_wait_requires_every_axis_from_the_same_fresh_snapshot(context_construction, order):
    client, state = context_construction
    install_guarded_planner(client, state)
    task = planned_sync_task("construction", client, state, order)
    await seal(task)
    state["calls"].clear()
    _, _, steps = await task.planned_approach(task.args["controller"], intent="place_block", hotbar_slot=1,
        allowed_sides=(0, 1, 2, 3, 4, 5))
    waits = [step for step in steps if step.get("kind") == "wait"]
    assert len(waits) == 1 and waits[0]["condition"]["operator"] == "fields_equal"
    assert set(waits[0]["condition"]["value"]) == {"x", "feetY", "z"}
    # Run the actual full placement from a new, equally fresh plan.
    await task.place(task.args["controller"], 1, "place")
    assert state["position"] == state["last_teleport_destination"] and not state["position_frames"]
    assert len(state["placements"]) == 1
    assert operations(state).count("dev.teleport") == operations(state).count("player.place_block") == 1


@pytest.mark.parametrize("axis", ["x", "y", "z"])
async def test_compound_wait_rejects_a_permanently_drifting_axis_without_replay(context_construction, axis):
    client, state = context_construction
    install_guarded_planner(client, state)
    task = planned_sync_task("construction", client, state, ("y", "z", "x"), timeout_ms=300)
    await seal(task)
    state["position_sync_drift"] = axis
    with pytest.raises(BridgeError) as error:
        await task.place(task.args["controller"], 1, "place")
    assert error.value.code in {"WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"}
    assert operations(state).count("dev.teleport") == 1 and not state["placements"]
    assert not any(call["operation"] in {"player.hotbar", "player.place_block", "player.interact"} for call in state["calls"])


async def test_compound_wait_also_requires_the_initially_unchanged_axis(context_construction):
    client, state = context_construction
    install_guarded_planner(client, state)
    task = lathe_position_sync_task(client, state, ("x", "y", "z"), x_already_matches=True, timeout_ms=300)
    await seal(task)
    state["position_sync_drift"] = "x"
    with pytest.raises(BridgeError) as error:
        await task.place(task.args["controller"], 1, "place")
    assert error.value.code in {"WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"}
    assert operations(state).count("dev.teleport") == 1 and not state["placements"]


async def test_flight_loss_after_one_teleport_stops_at_fresh_compound_wait(context_construction, monkeypatch):
    client, state = context_construction
    install_guarded_planner(client, state)
    task = planned_sync_task("construction", client, state, ("x", "y", "z"))
    await seal(task)
    original = client.call
    async def landed(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "dev.teleport":
            state["flying"] = False
        return value
    monkeypatch.setattr(client, "call", landed)
    with pytest.raises(BridgeError) as error:
        await task.place(task.args["controller"], 1, "place")
    assert error.value.code == "ASSERTION_FAILED"
    assert operations(state).count("dev.teleport") == 1 and not state["placements"]


async def test_pose_drift_after_full_xyz_wait_is_checked_again_on_fresh_inventory(context_construction, monkeypatch):
    client, state = context_construction
    install_guarded_planner(client, state)
    task = planned_sync_task("construction", client, state, ("x", "y", "z"))
    await seal(task)
    original = client.call
    async def changed(operation, args, **options):
        value = await original(operation, args, **options)
        destination = state.get("last_teleport_destination")
        if operation == "client.snapshot" and destination is not None and all(value["player"]["feetY" if axis == "y" else axis]
                == destination[axis] for axis in ("x", "y", "z")):
            state["position"]["z"] += 1
        return value
    monkeypatch.setattr(client, "call", changed)
    with pytest.raises(BridgeError) as error:
        await task.place(task.args["controller"], 1, "place")
    assert error.value.code == "ASSERTION_FAILED"
    assert operations(state).count("dev.teleport") == 1 and not state["placements"]
    assert operations(state)[-1] == "inventory.inspect"


@pytest.mark.parametrize("moving", [False, True])
@pytest.mark.parametrize("fault", ["client_flight", "server_flight", "server_dimension", "pose"])
async def test_fresh_existing_guard_rechecks_flight_and_no_move_pose(context_construction, monkeypatch, moving, fault):
    client, state = context_construction
    if not moving:
        state["position"] = {"x": CONTROLLER["x"] + .5, "y": 2, "z": CONTROLLER["z"] - 1.5}
    install_guarded_planner(client, state)
    task = construction_task(client, state)
    await seal(task)
    original = client.call
    planned = False
    async def changed(operation, args, **options):
        nonlocal planned
        value = await original(operation, args, **options)
        if operation == "player.plan_approach":
            assert value["selected"]["moveRequired"] is moving
            planned = True
        elif planned and operation == ("client.snapshot" if moving else "inventory.inspect"):
            snapshot = value if moving else value.context["snapshot"]
            if fault == "client_flight":
                snapshot["player"]["flying"] = False
            elif fault == "server_flight":
                snapshot["player"]["serverFlight"]["flying"] = False
            elif fault == "server_dimension":
                snapshot["player"]["serverFlight"]["dimension"] = True
            else:
                snapshot["player"]["z"] += 1
        return value
    monkeypatch.setattr(client, "call", changed)
    state["calls"].clear()
    with pytest.raises(BridgeError) as error:
        await task.place({**CONTROLLER, "y": 2}, 6, "place")
    assert error.value.code == "ASSERTION_FAILED"
    assert not any(call["operation"] in {"dev.teleport", "player.hotbar", "player.place_block", "player.interact"} for call in state["calls"])
    assert operations(state) == ["player.plan_approach", "client.snapshot" if moving else "inventory.inspect"]


@pytest.mark.parametrize("reason", ["open_gui", "missing_inventory_context", "helper_without_preflight"])
async def test_unmerged_paths_keep_standalone_flight_and_original_runner_contract(context_construction, reason):
    client, state = context_construction
    if reason == "open_gui":
        state["gui"] = "controller"
    install_guarded_planner(client, state, inventory_context=reason != "missing_inventory_context")
    task = construction_task(client, state)
    if reason != "helper_without_preflight":
        await seal(task)
    _, _, steps = await task.planned_approach({**CONTROLLER, "y": 2}, intent="place_block", hotbar_slot=6,
        allowed_sides=(0, 1, 2, 3, 4, 5))
    assert any(step["id"] == "flight" and step["tool"] == "mcb_snapshot" for step in steps)
    if reason == "open_gui":
        assert steps[0]["id"] == "close" and steps[1]["id"] == "flight"
    await task.place({**CONTROLLER, "y": 2}, 6, "place")
    assert len(state["placements"]) == 1 and operations(state).count("dev.teleport") == 1


async def test_gui_close_is_followed_by_its_own_fresh_flight_check(context_construction, monkeypatch):
    client, state = context_construction
    state["gui"] = "controller"
    install_guarded_planner(client, state)
    task = construction_task(client, state)
    await seal(task)
    original = client.call
    async def landed(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "gui.close":
            state["flying"] = False
        return value
    monkeypatch.setattr(client, "call", landed)
    state["calls"].clear()
    with pytest.raises(BridgeError) as error:
        await task.place({**CONTROLLER, "y": 2}, 6, "place")
    assert error.value.code == "ASSERTION_FAILED"
    assert operations(state) == ["player.plan_approach", "gui.close", "client.snapshot"]
    assert not state["placements"]


async def test_cached_first_noncontext_action_uses_one_mandatory_before_snapshot_for_entry(guarded_bridge):
    guarded_bridge[1]["supported"]["actions"].remove("chat.send")
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    await task.workflow("ordinary", [{"id": "chat", "kind": "action", "tool": "mcb_chat", "args": {"message": "test"}}])
    assert operations(state) == ["client.snapshot", "chat.send", "client.snapshot"]


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_first_noncontext_before_guard_refuses_drift_before_dispatch(guarded_bridge, fault, code):
    guarded_bridge[1]["supported"]["actions"].remove("chat.send")
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    if fault == "background":
        state["background"]["enabled"] = False
    else:
        state[fault] = "changed"
    with pytest.raises(BridgeError) as error:
        await task.workflow("ordinary", [{"id": "chat", "kind": "action", "tool": "mcb_chat", "args": {"message": "test"}}])
    assert error.value.code == code and operations(state) == ["client.snapshot"]


async def test_private_snapshot_rules_cannot_be_rebound_to_other_discovery(guarded_bridge):
    task, state, _ = await task_from(guarded_bridge)
    rules = _WorkflowSnapshotChecks(dataclasses.replace(task._workflow_discovery), (("read", (("player.flying", True),)),))
    before = len(state["calls"])
    report = await run_workflow(task.tracked_client, plan(read()), anyio.Lock(), _discovery=task._workflow_discovery,
        _require_background=True, _snapshot_checks=rules)
    assert report["status"] == "failed" and report["error"]["code"] == "INVALID_ARGUMENTS"
    assert len(state["calls"]) == before


@pytest.mark.parametrize("rules", [(), ("malformed",), (("read", ()),),
    (("read", (("player.x", float("nan")),)),),
    (("read", (("player.x", 1), ("player.x", 1))),),
    (("not_declared", (("player.flying", True),)),)])
async def test_private_snapshot_rules_reject_unknown_shapes_before_rpc(guarded_bridge, rules):
    task, state, _ = await task_from(guarded_bridge)
    before = len(state["calls"])
    report = await run_workflow(task.tracked_client, plan(read()), anyio.Lock(), _discovery=task._workflow_discovery,
        _require_background=True, _snapshot_checks=_WorkflowSnapshotChecks(task._workflow_discovery, rules))
    assert report["status"] == "failed" and report["error"]["code"] == "INVALID_ARGUMENTS"
    assert len(state["calls"]) == before


@pytest.mark.parametrize("actual", [{"x": 1, "feetY": 2, "z": 3}, {"x": 1., "feetY": 2., "z": 3.}])
def test_fields_equal_accepts_one_complete_finite_xyz_object(actual):
    condition = {"path": "player", "operator": "fields_equal", "value": {"x": 1, "feetY": 2, "z": 3}}
    assert condition_matches({"player": actual}, condition)


@pytest.mark.parametrize("axis", ["x", "feetY", "z"])
def test_fields_equal_never_accepts_only_one_or_two_matching_coordinates(axis):
    current = {"x": 1, "feetY": 2, "z": 3}
    current[axis] += 1
    assert not condition_matches({"player": current},
        {"path": "player", "operator": "fields_equal", "value": {"x": 1, "feetY": 2, "z": 3}})


@pytest.mark.parametrize("actual", [None, [], {}, {"x": 1, "feetY": 2}, {"x": True, "feetY": 2, "z": 3},
    {"x": "1", "feetY": 2, "z": 3}, {"x": float("nan"), "feetY": 2, "z": 3},
    {"x": 1, "feetY": float("inf"), "z": 3}])
def test_fields_equal_refuses_unknown_malformed_and_nonfinite_xyz(actual):
    with pytest.raises(BridgeError) as error:
        condition_matches({"player": actual}, {"path": "player", "operator": "fields_equal", "value": {"x": 1, "feetY": 2, "z": 3}})
    assert error.value.code == "CONDITION_TYPE"


@pytest.mark.parametrize("fault", ["empty", "oversized", "nested", "value_from"])
def test_fields_equal_schema_is_bounded_to_literal_scalar_fields(fault):
    condition = {"path": "player", "operator": "fields_equal", "value": {"x": 1, "feetY": 2, "z": 3}}
    if fault == "empty":
        condition["value"] = {}
    elif fault == "oversized":
        condition["value"] = {"field" + str(index): index for index in range(9)}
    elif fault == "nested":
        condition["value"]["x"] = {"value": 1}
    else:
        condition["valueFrom"] = {"step": "earlier", "path": "player"}
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(plan({"id": "synced", "kind": "wait", "tool": "mcb_snapshot", "condition": condition}))
