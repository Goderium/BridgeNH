"""Real reported legal sets in synthetic masks; no runtime geometry or timing claim."""
import copy

import pytest

from minecraft_codex_bridge.construction_tasks import ROLE_KEYS
import test_construction_tasks as native
from test_port_operability import geometry_task

native_construction = native.native_construction
ROLES = ("item_input", "item_output", "maintenance", "energy", "muffler")


def reported_legal_set(shape):
    # Only these role/group candidates were reported by s84. Treating their union
    # as complete visits is a synthetic test mask, never a reconstruction of it.
    if shape == "small_cube":
        points = [(x, y, z) for x in (-802, -800) for y in (4, 5, 6) for z in (-44, -43, -42)]
        points += [(-801, 4, z) for z in (-43, -42)] + [(-801, 5, -42)]
        points += [(-801, 6, z) for z in (-44, -43, -42)]
        return {"x": -801, "y": 5, "z": -44}, 0, points
    points = [(x, y, z) for x in (-809, -804) for y in (4, 5) for z in (-29, -28, -27)]
    points += [(x, 6, -28) for x in (-809, -804)]
    points += [(x, 4, -28) for x in (-808, -807, -806, -805)]
    points += [(-805, 5, z) for z in (-28, -27)]
    return {"x": -805, "y": 5, "z": -29}, 1, points


async def synthetic_reported_task(client, shape, *, reverse=False, translation=(0, 0, 0), flying=False):
    controller, group, cells = reported_legal_set(shape)
    controller = {axis: controller[axis] + translation[index] for index, axis in enumerate(("x", "y", "z"))}
    positions = [{**{axis: point[index] + translation[index] for index, axis in enumerate(("x", "y", "z"))},
        "group": group} for point in cells]
    if reverse:
        positions.reverse()
    task, groups, _ = geometry_task(client, controller, positions, floor=3 + translation[1])
    groups[group] = {**groups[0], "id": group, "descriptionKeys": list(ROLE_KEYS.values()),
        "candidateScope": "Reported public legal set in a synthetic complete visit mask"}
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": native.PORT_SLOTS.get(role, 7)} for role in ROLES]
    if flying:
        task.position_snapshot = {"profile": "developer", "sessionId": task.args["expectedSessionId"],
            "world": {"dimension": task.dimension}, "player": {"flying": True,
                "serverFlight": {"available": True, "flying": True, "allowFlying": True, "dimension": task.dimension}}}
    await task.decide_ports(positions, groups)
    return task, positions


@pytest.mark.parametrize("shape,count", [("small_cube", 24), ("wide_low_front", 20)])
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("translation", [(0, 0, 0), (2000, 20, -3000)])
async def test_reported_candidates_rank_compact_frequent_roles_and_low_muffler_without_native_claim(
        native_construction, shape, count, reverse, translation):
    client, state = native_construction
    task, positions = await synthetic_reported_task(client, shape, reverse=reverse, translation=translation)
    original = copy.deepcopy(task.survival_port_candidates)
    windows = task.rank_survival_port_layout(original, set())
    preferred = {ROLES[index]: rows[0][1] for index, rows in windows.items()}
    frequent = [preferred[role] for role in ("item_input", "item_output", "energy")]
    horizontal_diameter = max(abs(left["x"] - right["x"]) + abs(left["z"] - right["z"])
        for left in frequent for right in frequent)
    assert horizontal_diameter <= 2
    assert preferred["muffler"]["y"] == 4 + translation[1]
    assert preferred["energy"]["y"] == 5 + translation[1]
    assert all(preferred[role]["y"] == 4 + translation[1] for role in ("item_input", "item_output"))
    assert len({native.key(point) for point in preferred.values()}) == len(ROLES)
    assert all(len(rows) == count and rows[0][1] in positions for rows in windows.values())
    assert windows == task.rank_survival_port_layout(original, set())
    assert task.survival_port_candidates == original and not task.selected_ports and not state["calls"]
    diagnostic = task.position_selection_policy["geometryFaceRanking"]["jointLayout"]
    assert diagnostic["completePreferredAssignment"] is True
    assert all(row["candidateWindowComplete"] and row["publicCandidateCount"] == count for row in diagnostic["roles"])
    assert all(diagnostic[key] is False for key in ("nativeReachabilityVerified", "laterGuiAccessVerified", "semanticRoleVerified"))


async def test_installed_frequent_role_keeps_remaining_public_choices_near_it_and_retains_casing(native_construction):
    client, state = native_construction
    task, positions = await synthetic_reported_task(client, "wide_low_front")
    installed = next(point for point in positions if native.key(point) == (-804, 4, -29))
    task.selected_ports = [{"position": {axis: installed[axis] for axis in ("x", "y", "z")},
        "role": "item_input", "group": 1, "placementConfirmed": True}]
    windows = task.rank_survival_port_layout({index: rows for index, rows in task.survival_port_candidates.items() if index},
        {native.key(installed)})
    assert all(rows[0][1]["x"] >= -806 for index, rows in windows.items() if ROLES[index] in ("item_output", "energy"))
    assert len({native.key(rows[0][1]) for rows in windows.values()}) == 4
    assert all(len(rows) == 19 and all(row[1]["group"] == 1 and native.key(row[1]) != native.key(installed)
        for row in rows) for rows in windows.values())
    assert len(task.selected_ports) + len(windows) < task.survival_group_counts[1] and not state["calls"]


async def test_observed_flight_muffler_retains_controller_height_preference(native_construction):
    client, state = native_construction
    task, _ = await synthetic_reported_task(client, "wide_low_front", flying=True)
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert task.position_selection_policy["movementContext"] == "Observed developer flight"
    assert task.position_selection_policy["preferredOperatingY"] == 5
    assert windows[4][0][1]["y"] == 5
    assert len({native.key(rows[0][1]) for rows in windows.values()}) == 5 and not state["calls"]


async def test_observed_flight_all_roles_prefer_controller_height_with_enough_exposed_cells(native_construction):
    client, state = native_construction
    positions = [{"x": x, "y": y, "z": 0, "group": 0} for x in range(1, 7) for y in (4, 5)]
    task, groups, _ = geometry_task(client, {"x": 0, "y": 5, "z": 0}, positions, floor=3)
    task.position_snapshot = {"profile": "developer", "sessionId": task.args["expectedSessionId"],
        "world": {"dimension": task.dimension}, "player": {"flying": True,
            "serverFlight": {"available": True, "flying": True, "allowFlying": True, "dimension": task.dimension}}}
    task.args["requestedPorts"] = [{"role": role, "hotbarSlot": native.PORT_SLOTS.get(role, 7)} for role in ROLES]
    await task.decide_ports(positions, groups)
    windows = task.rank_survival_port_layout(task.survival_port_candidates, set())
    assert all(rows[0][1]["y"] == 5 for rows in windows.values())
    assert len({native.key(rows[0][1]) for rows in windows.values()}) == 5 and not state["calls"]
