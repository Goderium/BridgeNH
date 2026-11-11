"""Synthetic legal layouts and native fixture admissions; no live reach or timing claim."""
import copy
import json

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import ROLE_KEYS, _ConstructionTask
from minecraft_codex_bridge.task_reports import compact_blueprint_report
import test_construction_tasks as native
import test_frontier_plan_handoff as handoff
import test_native_navigation as navigation
import test_survival_structure_seeds as seeds
from test_port_operability import geometry_task

native_construction = native.native_construction


@pytest.mark.parametrize("reverse", [False, True])
async def test_joint_layout_concentrates_five_distinct_front_ports_and_excludes_permanent_seed(native_construction, reverse):
    client, state = native_construction
    controller = {"x": 0, "y": 3, "z": 0}
    seed = {"x": 0, "y": 2, "z": 0, "group": 0}
    positions = [{"x": x, "y": y, "z": 0, "group": 0} for x in (-4, -3, -2, 1, 2, 3) for y in (2, 3)] + [seed]
    if reverse:
        positions.reverse()
    task, groups, _ = geometry_task(client, controller, positions)
    roles = ("item_input", "item_output", "maintenance", "energy", "muffler")
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": native.PORT_SLOTS.get(role, 7)} for role in roles]
    task.survival_seed_positions = {native.key(seed)}
    await task.decide_ports(positions, groups)
    original = copy.deepcopy(task.survival_port_candidates)
    windows = task.rank_survival_port_layout(original, task.survival_seed_positions)
    preferred = [window[0][1] for window in windows.values()]
    assert len({native.key(point) for point in preferred}) == 5
    assert all(point["x"] > 0 and point["z"] == 0 for point in preferred)
    assert max(point["x"] for point in preferred) - min(point["x"] for point in preferred) == 2
    assert [point["y"] for point in preferred] == [2, 2, 3, 3, 2]
    assert all(native.key(seed) != native.key(candidate[1]) for window in windows.values() for candidate in window)
    assert task.survival_port_candidates == original  # No provisional selection consumes or reserves a cell.
    assert windows == task.rank_survival_port_layout(original, task.survival_seed_positions)
    diagnostic = task.position_selection_policy["geometryFaceRanking"]["jointLayout"]
    assert diagnostic["completePreferredAssignment"] is True
    assert all(row["candidateWindowComplete"] and row["publicCandidateCount"] == 12 for row in diagnostic["roles"])
    assert all(diagnostic[key] is False for key in ("nativeReachabilityVerified", "laterGuiAccessVerified", "semanticRoleVerified"))
    assert not task.selected_ports and not state["calls"]
    installed = preferred[0]
    task.selected_ports = [{"position": {axis: installed[axis] for axis in ("x", "y", "z")}, "group": 0, "placementConfirmed": True}]
    remaining = task.rank_survival_port_layout({index: rows for index, rows in original.items() if index != 0},
        task.survival_seed_positions | {native.key(installed)})
    assert len({native.key(rows[0][1]) for rows in remaining.values()}) == 4
    assert all(native.key(row[1]) != native.key(installed) for rows in remaining.values() for row in rows)
    assert task.position_selection_policy["geometryFaceRanking"]["jointLayout"]["roles"] == diagnostic["roles"]
    assert not state["calls"]


async def test_joint_layout_retains_exact_chamber_binding_and_exposed_group_front_ahead_of_controller_plane(native_construction):
    client, state = native_construction
    controller = {"x": 0, "y": 3, "z": 0}
    positions = [{"x": x, "y": 2, "z": z, "group": group}
        for x, z, group in ((1, 0, 0), (2, 0, 0), (10, -2, 1), (10, 0, 1), (11, 0, 1))]
    task, groups, _ = geometry_task(client, controller, positions)
    roles = ("item_input", "item_output")
    for identifier, role in enumerate(roles):
        descriptions = [ROLE_KEYS[role]]
        groups[identifier] = {"id": identifier, "descriptionKeys": descriptions, "suggestedCandidates": [native.stack(native.CASING)],
            "candidateScope": "Synthetic exact chamber", "roleSelection": {"available": True,
                "binding": "capture_scoped_public_definition_element_group", "captureId": task.capture["captureId"],
                "groupId": identifier, "descriptionKeys": descriptions, "semanticRoleVerified": False}}
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": native.PORT_SLOTS[role],
        "groupSelector": {"descriptionKeys": [ROLE_KEYS[role]], "occurrence": 0}} for role in roles]
    await task.decide_ports(positions, groups)
    assert task.survival_port_candidates[1][0][1]["z"] == 0  # The v15 controller-plane preference.
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert windows[1][0][1] == {"x": 10, "y": 2, "z": -2, "group": 1}
    assert all(candidate[1]["group"] == index for index, candidates in windows.items() for candidate in candidates)
    assert [row["selectedGroup"] for row in task.position_selection_policy["geometryFaceRanking"]["jointLayout"]["roles"]] == [0, 1]
    assert not state["calls"]


async def test_layout_windows_and_matching_are_bounded_without_cartesian_or_native_search(native_construction, monkeypatch):
    client, state = native_construction
    positions = [{"x": x, "y": 2, "z": 0, "group": 0} for x in range(1, 41)]
    task, groups, _ = geometry_task(client, {"x": 0, "y": 3, "z": 0}, positions)
    roles = ("item_input", "item_output", "maintenance", "energy", "muffler", "fluid_input")
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": index + 2} for index, role in enumerate(roles)]
    await task.decide_ports(positions, groups)
    original = task.match_port_layout
    calls = []
    def bounded(windows, costs, capacities):
        assert len(windows) == 6 and all(len(window) == 32 for window in windows.values())
        assert len(costs) == 6 * 32 and capacities[0] == 39
        calls.append(1)
        return original(windows, costs, capacities)
    monkeypatch.setattr(task, "match_port_layout", bounded)
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert len(calls) == 16 and len({native.key(window[0][1]) for window in windows.values()}) == 6
    diagnostic = task.position_selection_policy["geometryFaceRanking"]["jointLayout"]
    assert diagnostic["anchorsConsidered"] == 16
    assert all(row["publicCandidateCount"] == 40 and row["candidateWindowComplete"] is False for row in diagnostic["roles"])
    assert len(diagnostic["candidatePositions"]) == 32
    assert all(type(point[axis]) is int for point in diagnostic["candidatePositions"] for axis in ("x", "y", "z", "group"))
    assert all(len(row["candidateIndices"]) == 32 and max(row["candidateIndices"]) < 32 for row in diagnostic["roles"])
    compact = compact_blueprint_report({"positionSelectionPolicy": task.position_selection_policy})
    assert compact["positionSelectionPolicy"]["geometryFaceRanking"]["jointLayout"] == diagnostic
    assert len(json.dumps(compact)) < 10000
    assert not state["calls"]


def test_matching_reassigns_earlier_flexible_role_and_honors_group_capacity():
    cells = [{"x": x, "y": 2, "z": 0, "group": 0} for x in (1, 2)]
    candidate = lambda point: ((), point, [], True, True)
    windows = {0: [candidate(point) for point in cells], 1: [candidate(cells[0])]}
    costs = {(index, native.key(row[1])): offset for index, rows in windows.items() for offset, row in enumerate(rows)}
    assert _ConstructionTask.match_port_layout(windows, costs, {0: 2}) == {0: native.key(cells[1]), 1: native.key(cells[0])}
    assert _ConstructionTask.match_port_layout(windows, costs, {0: 1}) is None


async def test_impossible_complete_assignment_keeps_legal_side_candidates_and_no_native_claim(native_construction):
    client, state = native_construction
    positions = [{"x": 1, "y": 2, "z": 0, "group": 0}, {"x": 2, "y": 2, "z": 0, "group": 0}]
    task, groups, _ = geometry_task(client, {"x": 0, "y": 3, "z": 0}, positions)
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": native.PORT_SLOTS[role]} for role in ("item_input", "item_output")]
    await task.decide_ports(positions, groups)
    task.port_operating_faces = {native.key(point): [5] for point in positions}
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert all(len(window) == 2 for window in windows.values())
    assert task.position_selection_policy["geometryFaceRanking"]["jointLayout"]["completePreferredAssignment"] is False
    assert all(row["preferredPosition"] is None for row in task.position_selection_policy["geometryFaceRanking"]["jointLayout"]["roles"])
    assert not state["calls"]


def enable_frontier_geometry(task):
    positions = list({native.key(row[1]): row[1] for rows in task.survival_port_candidates.values() for row in rows}.values())
    task.capture.update(capturedFacing="NORTH_NORMAL_NONE", capturedVisitsComplete=True,
        eventCaptureTruncated=False, capturedPositions=len(positions))
    task.floor = 1
    task.prepare_port_operating_geometry(positions)
    task.position_selection_policy = {"movementContext": "Ground or unconfirmed flight", "lowestOperatingY": 2, "preferredOperatingY": 3}


async def test_current_only_cross_role_admission_precedes_first_roles_move_search(native_construction, monkeypatch):
    client, state = native_construction
    task = seeds.task(client)
    far = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 4, "y": 2}
    near = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 1, "y": 2}
    seeds.prepare_frontier(task, state, [("item_input", 2, [far]), ("item_output", 4, [near])])
    enable_frontier_geometry(task)
    placements = seeds.install_frontier_places(task, state, monkeypatch)
    admissions = []
    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((slot, current_only, len(placements)))
        return native.key(point) == native.key(near) or not current_only
    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert placements == [(4, native.key(near)), (2, native.key(far))]
    assert admissions == [(4, True, 0), (2, True, 1), (2, False, 1)]
    assert task._survival_frontier_binding is None
    assert task.position_selection_policy["geometryFaceRanking"]["jointLayoutLast"]["roles"][0]["portIndex"] == 0


async def test_current_only_native_probe_does_not_search_and_consumes_last_global_read(native_construction, monkeypatch):
    client, state = native_construction
    task, point, owned, side = handoff.prepare(client, state, monkeypatch, moving=True)
    task.survival_frontier_native_reads = 63
    assert await task.probe_survival_frontier(point, 6, [(owned, side)], current_only=True) is False
    assert len(state["planner_calls"]) == 1 and state["planner_calls"][0]["currentOnly"] is True
    assert task.survival_frontier_native_reads == 64 and task._frontier_plan_handoff is None
    assert task.survival_frontier_observations[-1]["currentOnlyProbe"] is True
    with pytest.raises(BridgeError) as failure:
        await task.probe_survival_frontier(point, 6, [(owned, side)], current_only=True)
    assert failure.value.details["reason"] == "survival_port_frontier_budget"
    assert len(state["planner_calls"]) == 1 and not state["navigation_calls"]
    assert not navigation.construction.writes(state)


@pytest.mark.parametrize("status", ["unknown", "partial", "client_face_unverified"])
async def test_unknown_current_admission_never_enables_move_search(native_construction, monkeypatch, status):
    client, state = native_construction
    task, point, owned, side = handoff.prepare(client, state, monkeypatch)
    async def incomplete(*args, **kwargs):
        return {"status": status}
    monkeypatch.setattr(task, "current_native_approach", incomplete)
    with pytest.raises(BridgeError) as failure:
        await task.probe_survival_frontier(point, 6, [(owned, side)], current_only=True)
    assert failure.value.details["reason"] == "survival_port_frontier_current_unavailable"
    assert task._frontier_plan_handoff is None and not state["planner_calls"] and not state["navigation_calls"]
    assert not navigation.construction.writes(state)


async def test_later_current_proof_retains_one_use_handoff_and_real_material_deduction(native_construction, monkeypatch):
    client, state = native_construction
    task, near, owned, side = handoff.prepare(client, state, monkeypatch)
    calls = seeds.install_finite_native_receipts(task, state, monkeypatch)
    monkeypatch.setattr(task, "supported_placement_approach", _ConstructionTask.supported_placement_approach.__get__(task))
    far = {**near, "x": near["x"] + 8}
    far_owned = {**far, "x": far["x"] + 1}
    state["blocks"][native.key(far_owned)] = native.CASING.copy()
    state["current_ready"] = False
    assert await task.probe_survival_frontier(far, 6, [(far_owned, 4)], current_only=True) is False
    state["current_ready"] = True
    assert await task.probe_survival_frontier(near, 6, [(owned, side)], current_only=True)
    assert task._frontier_plan_handoff["rpcEpoch"] == task.tracked_client.rpc_epoch
    record = {}
    await task.place(near, 6, "later_current", record)
    assert len(state["planner_calls"]) == 2 and all(args["currentOnly"] for args in state["planner_calls"])
    assert len(calls) == 1 and record["serverConsumptionConfirmed"] and task._frontier_plan_handoff is None
    assert task.survival_material_ledger[0]["deductions"][0]["sourceCountBefore"] == 2
    assert task.survival_material_ledger[0]["deductions"][0]["sourceCountAfter"] == 1
    assert state["items"][6]["count"] == 1 and not state["navigation_calls"]
