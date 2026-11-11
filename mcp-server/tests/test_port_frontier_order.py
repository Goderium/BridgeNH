"""Bounded synthetic frontier decisions; native acceptance stays in the existing guard tests."""
import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_construction_tasks as native
import test_survival_structure_seeds as seeds
from test_shared_port_layout import enable_frontier_geometry

native_construction = native.native_construction


def frontier(client, state, monkeypatch, ports):
    task = seeds.task(client)
    seeds.prepare_frontier(task, state, ports)
    enable_frontier_geometry(task)
    placements = seeds.install_frontier_places(task, state, monkeypatch)
    return task, placements


@pytest.mark.parametrize("reverse", [False, True])
async def test_later_assigned_support_is_placed_before_earlier_fallback_steals_its_cell(native_construction, monkeypatch, reverse):
    client, state = native_construction
    lower = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 2, "y": 2}
    upper = {**lower, "y": 3}
    options = [upper, lower]
    if reverse:
        options.reverse()
    task, placements = frontier(client, state, monkeypatch,
        [("item_output", 4, options), ("energy", 3, [lower])])
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert windows[0][0][1] == {**upper, "group": 0}
    assert windows[1][0][1] == {**lower, "group": 0}
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((slot, native.key(point), current_only, [native.key(a) for a, _ in attachments]))
        return True

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert placements == [(3, native.key(lower)), (4, native.key(upper))]
    assert [row[:3] for row in admissions] == [(3, native.key(lower), True), (4, native.key(upper), True)]
    assert native.key(lower) in admissions[-1][3]
    assert all(port["group"] == 0 for port in task.selected_ports)
    assert task._survival_frontier_binding is None


async def test_attached_later_assignment_precedes_earlier_current_ready_fallback(native_construction, monkeypatch):
    client, state = native_construction
    first = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 1, "y": 2}
    second = {**first, "x": first["x"] + 1}
    task, placements = frontier(client, state, monkeypatch,
        [("item_input", 2, [first, second]), ("energy", 3, [second])])
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((slot, native.key(point), current_only, len(placements)))
        return native.key(point) == native.key(second) or not current_only

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert placements == [(3, native.key(second)), (2, native.key(first))]
    assert admissions[:2] == [(2, native.key(first), True, 0), (3, native.key(second), True, 0)]
    assert not any(slot == 2 and point == native.key(second) for slot, point, _, _ in admissions)


async def test_known_absence_of_preferred_cell_does_not_force_movement_past_current_ready_alternative(native_construction, monkeypatch):
    client, state = native_construction
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + offset, "y": 2} for offset in (1, 2)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, points)])
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((native.key(point), current_only))
        return native.key(point) == native.key(points[1])

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert placements == [(2, native.key(points[1]))]
    assert admissions == [(native.key(point), True) for point in points]
    assert not state.get("navigation_calls")


async def test_known_absence_is_reobserved_after_real_world_frontier_changes(native_construction, monkeypatch):
    client, state = native_construction
    first = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 1, "y": 2}
    second = {**first, "x": first["x"] + 1}
    task, placements = frontier(client, state, monkeypatch,
        [("item_input", 2, [first]), ("energy", 3, [second])])
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((slot, current_only, len(placements)))
        return slot == 3 or native.key(second) in state["blocks"]

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert placements == [(3, native.key(second)), (2, native.key(first))]
    assert admissions == [(2, True, 0), (3, True, 0), (2, True, 1)]


async def test_extra_current_coverage_is_two_attached_cells_per_role_and_window_stays_32(native_construction, monkeypatch):
    client, state = native_construction
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + offset, "y": 2} for offset in range(1, 41)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, points)])
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        admissions.append((native.key(point), current_only))
        return False

    async def no_seeds(pending, used):
        return False

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    monkeypatch.setattr(task, "extend_survival_frontier", no_seeds)
    with pytest.raises(BridgeError) as failure:
        await task.install_survival_frontier_ports()
    assert failure.value.details["reason"] == "survival_port_frontier_unavailable"
    assert [point for point, current in admissions if current] == [native.key(point) for point in points[:2]]
    assert len([point for point, current in admissions if not current]) == 32
    assert not placements and not task.selected_ports
    assert not native.writes(state)


async def test_extra_current_coverage_stops_to_preserve_two_ordinary_planning_reads(native_construction, monkeypatch):
    client, state = native_construction
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + offset, "y": 2} for offset in (1, 2)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, points)])
    task.survival_frontier_native_reads = 61
    admissions = []

    async def proved(point, slot, attachments, *, current_only=False):
        task.survival_frontier_native_reads += 1 if current_only else 2
        admissions.append((native.key(point), current_only, task.survival_frontier_native_reads))
        return not current_only

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert admissions == [(native.key(points[0]), True, 62), (native.key(points[0]), False, 64)]
    assert placements == [(2, native.key(points[0]))]


@pytest.mark.parametrize("fixed", [False, True])
@pytest.mark.parametrize("status", ["unknown", "partial", "client_face_unverified"])
async def test_incomplete_current_observation_refuses_before_alternative_or_move_search(native_construction, monkeypatch, status, fixed):
    client, state = native_construction
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + offset, "y": 2} for offset in (1, 2)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, points)])
    if fixed:
        points = points[:1]
        task.survival_port_candidates[0] = task.survival_port_candidates[0][:1]
        task.args["requestedPorts"][0]["relativePosition"] = {
            axis: points[0][axis] - task.args["controller"][axis] for axis in ("x", "y", "z")}
    reads = []

    async def incomplete(target, **options):
        reads.append(native.key(target))
        return {"status": status}

    monkeypatch.setattr(task, "current_native_approach", incomplete)
    with pytest.raises(BridgeError) as failure:
        await task.install_survival_frontier_ports()
    assert failure.value.details["reason"] == "survival_port_frontier_current_unavailable"
    assert reads == [native.key(points[0])]
    assert not placements and not task.selected_ports
    assert not native.writes(state)


async def test_fixed_occluded_long_line_reuses_ordinary_current_then_move_probe_within_budget(native_construction, monkeypatch):
    client, state = native_construction
    # All positions are nearby enough that distance deferral cannot remove the
    # repeated negative cross-role scans observed on the real assembly line.
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + x, "y": 2, "z": native.CONTROLLER["z"] + z}
        for x in range(1, 7) for z in range(4)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, [point]) for point in points])
    task.survival_group_counts[0] = 32
    for port, point in zip(task.args["requestedPorts"], points):
        port["relativePosition"] = {axis: point[axis] - task.args["controller"][axis] for axis in ("x", "y", "z")}
    reads = []

    async def proved(point, slot, attachments, *, current_only=False):
        assert not current_only  # The ordinary probe owns the fresh current read.
        assert attachments
        task.survival_frontier_native_reads += 2  # Current no_candidate, then ready relocation.
        reads.append((native.key(point), len(placements)))
        state["position"] = {"x": point["x"] + .5, "y": 2, "z": point["z"] - 1.5}
        return True

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert len(placements) == len(reads) == 24
    assert task.survival_frontier_native_reads == 48
    assert {point for _, point in placements} == {native.key(point) for point in points}
    assert [count for _, count in reads] == list(range(24))


async def test_long_line_defers_distant_current_probes_without_expanding_native_budget(native_construction, monkeypatch):
    client, state = native_construction
    points = [{**native.CONTROLLER, "x": native.CONTROLLER["x"] + offset, "y": 2} for offset in range(1, 25)]
    task, placements = frontier(client, state, monkeypatch, [("item_input", 2, [point]) for point in points])
    task.survival_group_counts[0] = 32
    state["position"] = {"x": points[-1]["x"] + .5, "y": 2, "z": points[-1]["z"] - 1.5}
    reads = []

    async def proved(point, slot, attachments, *, current_only=False):
        eye = {**state["position"], "y": state["position"]["y"] + 1.62}
        geometry = {"eye": eye, "reach": 3}
        before = task._frontier_current_geometry
        distance = sum(max(point[axis] - eye[axis], 0, eye[axis] - point[axis] - 1) ** 2 for axis in ("x", "y", "z"))
        # After a real current read supplied geometry in this pass, distant
        # assigned cells must be deferred, not probed or admitted as ready.
        if current_only and before is not None:
            assert distance <= 9 + 1e-6
        task._frontier_current_geometry = geometry
        task.survival_frontier_native_reads += 1 if current_only else 2
        reads.append((native.key(point), current_only))
        if not current_only:
            state["position"] = {"x": point["x"] + .5, "y": 2, "z": point["z"] - 1.5}
        return distance <= 9 or not current_only

    monkeypatch.setattr(task, "probe_survival_frontier", proved)
    await task.install_survival_frontier_ports()
    assert len(placements) == 24 and placements[0] == (2, native.key(points[-1]))
    assert {point for _, point in placements} == {native.key(point) for point in points}
    assert task.survival_frontier_native_reads < 64
    assert any(not current_only for _, current_only in reads)
