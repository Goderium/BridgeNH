"""Complete public geometry sorts ports without claiming later access or exhaust."""

import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.task_reports import compact_blueprint_report
import test_construction_tasks as native
from test_survival_structure_seeds import install_frontier_places, prepare_frontier, task as survival_task

native_construction = native.native_construction


def geometry_task(client, controller, positions, *, floor=1):
    instance, groups, verified = native.ranking_task(client, controller, positions, floor=floor)
    instance.actual_survival = True
    instance.survival_seed_mode = True
    instance.capture["capturedPositions"] = len(positions)

    async def already_validated_seeds(current, categories):
        assert current is positions and categories is groups

    instance.bind_survival_seeds = already_validated_seeds
    return instance, groups, verified


@pytest.mark.parametrize("height,sides", [(2, [1, 2, 3, 4, 5]), (4, [0, 1, 2, 3, 4, 5])])
async def test_single_future_cube_has_six_axis_faces_except_the_known_terrain_floor(native_construction, height, sides):
    client, state = native_construction
    positions = [{"x": 0, "y": height, "z": 0, "group": 0}]
    instance, _, _ = geometry_task(client, {"x": 9, "y": 9, "z": 9}, positions)
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_faces[native.key(positions[0])] == sides
    assert instance.port_operating_geometry["capturedVisitCount"] == 1
    assert instance.port_operating_geometry["maskedCellCount"] == 2
    assert instance.port_operating_geometry["wholeProspectiveCellCubes"] is True
    assert not state["calls"]


async def test_complete_mask_blocks_internal_faces_even_for_public_air_visits(native_construction):
    client, state = native_construction
    positions = [{"x": x, "y": y, "z": z, "group": 1 if (x, y, z) == (0, 3, 1) else 0}
        for x in (-1, 0, 1) for y in (2, 3, 4) for z in (0, 1, 2)]
    instance, _, _ = geometry_task(client, {"x": 9, "y": 9, "z": 9}, positions)
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_faces[(0, 3, 1)] == []
    assert instance.port_operating_faces[(-1, 2, 0)] == [2, 4]
    assert instance.port_operating_faces[(-1, 4, 0)] == [1, 2, 4]
    assert instance.port_operating_faces[(0, 3, 0)] == [2]
    assert "air semantics are not inferred" in instance.port_operating_geometry["scope"]
    assert "pollution exhaust remain unverified" in instance.port_operating_geometry["unknown"]
    assert not state["calls"]


async def test_actual_controller_cell_closes_a_corridor_missing_from_native_visits(native_construction):
    client, state = native_construction
    positions = [{"x": 0, "y": 4, "z": 1, "group": 0}]
    instance, _, _ = geometry_task(client, {"x": 0, "y": 4, "z": 0}, positions)
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_faces[(0, 4, 1)] == [0, 1, 3, 4, 5]
    assert not state["calls"]


async def test_distant_masked_cube_blocks_a_straight_corridor_despite_clear_adjacent_cells(native_construction):
    client, state = native_construction
    positions = [{"x": 0, "y": 4, "z": z, "group": 0} for z in (0, -4)]
    instance, _, _ = geometry_task(client, {"x": 9, "y": 9, "z": 9}, positions)
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_faces[(0, 4, 0)] == [0, 1, 3, 4, 5]
    assert instance.port_operating_faces[(0, 4, -4)] == [0, 1, 2, 4, 5]
    assert not state["calls"]


@pytest.mark.parametrize("fault", ["incomplete", "truncated", "missing_count", "wrong_count", "boolean_count", "creative"])
async def test_unconfirmed_capture_resets_geometry_instead_of_claiming_closure(native_construction, fault):
    client, state = native_construction
    positions = [{"x": 0, "y": 4, "z": 0, "group": 0}]
    instance, _, _ = geometry_task(client, {"x": 9, "y": 9, "z": 9}, positions)
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_geometry is not None
    if fault == "incomplete":
        instance.capture["capturedVisitsComplete"] = False
    elif fault == "truncated":
        instance.capture["eventCaptureTruncated"] = True
    elif fault == "missing_count":
        del instance.capture["capturedPositions"]
    elif fault == "wrong_count":
        instance.capture["capturedPositions"] = 2
    elif fault == "boolean_count":
        instance.capture["capturedPositions"] = True
    else:
        instance.actual_survival = False
    instance.prepare_port_operating_geometry(positions)
    assert instance.port_operating_geometry is None and instance.port_operating_faces == {}
    assert not state["calls"]


async def test_duplicate_native_xyz_cannot_prove_future_closure(native_construction):
    client, state = native_construction
    positions = [{"x": 0, "y": 4, "z": 0, "group": 0}] * 2
    instance, _, _ = geometry_task(client, {"x": 9, "y": 9, "z": 9}, positions)
    with pytest.raises(BridgeError) as caught:
        instance.prepare_port_operating_geometry(positions)
    assert caught.value.details["reason"] == "public_port_face_geometry_incomplete"
    assert instance.port_operating_geometry is None and not state["calls"]


@pytest.mark.parametrize("reverse", [False, True])
async def test_exposed_front_port_precedes_nearer_height_without_filtering_internal_public_candidate(native_construction, reverse):
    client, state = native_construction
    controller = {"x": 0, "y": 3, "z": 0}
    permitted = {(1, 3, 0), (2, 3, 1), (2, 5, 0)}
    positions = [{"x": x, "y": y, "z": z, "group": 0 if (x, y, z) in permitted else 1}
        for x in range(-2, 3) for y in range(2, 6) for z in range(-2, 3) if (x, y, z) != native.key(controller)]
    if reverse:
        positions.reverse()
    instance, groups, verified = geometry_task(client, controller, positions)
    instance.args["requestedPorts"] = [{"role": "energy", "hotbarSlot": native.PORT_SLOTS["energy"]}]
    await instance.decide_ports(positions, groups)
    choices = instance.survival_port_candidates[0]
    assert [native.key(choice[1]) for choice in choices] == [(2, 5, 0), (2, 3, 1), (1, 3, 0)]
    assert instance.port_operating_faces[(1, 3, 0)] == []
    assert all(choice[1]["group"] == 0 for choice in choices) and len(choices) == 3
    assert instance.selected_ports == [] and verified == [positions] and not state["calls"]
    assert instance.position_selection_policy["order"].startswith("Conservative horizontal future face, captured front plane")
    port = instance.select_port_candidate(0, choices[0], groups, len(permitted))
    evidence = port["futureOperatingFaces"]
    assert evidence["sides"] == [1, 5]
    assert all(evidence[key] is False for key in ("nativeReachabilityVerified", "externalClearanceVerified",
        "pollutionExhaustVerified", "laterGuiAccessVerified"))


async def test_missing_complete_capture_keeps_existing_height_sorting_without_new_geometry_claim(native_construction):
    client, state = native_construction
    positions = [{"x": 2, "y": y, "z": z, "group": 0} for y, z in ((5, 0), (3, 1), (2, 2))]
    instance, groups, _ = geometry_task(client, {"x": 0, "y": 3, "z": 0}, positions)
    del instance.capture["capturedPositions"]
    instance.args["requestedPorts"] = [{"role": "energy", "hotbarSlot": native.PORT_SLOTS["energy"]}]
    await instance.decide_ports(positions, groups)
    assert native.key(instance.survival_port_candidates[0][0][1]) == (2, 3, 1)
    assert instance.port_operating_geometry is None
    assert "geometryFaceRanking" not in instance.position_selection_policy and not state["calls"]


async def test_survival_energy_configuration_runs_after_confirmed_placement_and_binding_clear_before_next_port(native_construction, monkeypatch):
    client, state = native_construction
    instance = survival_task(client, energyTier=6)
    energy = {**native.CONTROLLER, "x": native.CONTROLLER["x"] + 1, "y": 2}
    maintenance = {**energy, "x": energy["x"] + 1}
    prepare_frontier(instance, state, [("energy", 3, [energy]), ("maintenance", 5, [maintenance])])
    trace = install_frontier_places(instance, state, monkeypatch)
    configured = []

    async def proved(point, slot, attachments):
        return True

    async def configure(*, before_shell=False):
        assert before_shell is True and instance._survival_frontier_binding is None
        assert len(instance.selected_ports) == 1 and instance.selected_ports[0]["placementConfirmed"] is True
        assert trace == [(3, native.key(energy))]
        configured.append(copy.deepcopy(instance.selected_ports[0]["position"]))

    monkeypatch.setattr(instance, "probe_survival_frontier", proved)
    monkeypatch.setattr(instance, "configure_energy_tier", configure)
    await instance.install_ports_before_shell()
    assert configured == [energy]
    assert trace == [(3, native.key(energy)), (5, native.key(maintenance))]
    assert instance._survival_frontier_binding is None


async def test_compact_success_preserves_sorting_only_false_flags_and_pre_shell_confirmation(native_construction):
    client, _ = native_construction
    positions = [{"x": x, "y": 4, "z": 0, "group": 0} for x in (1, 2)]
    instance, groups, _ = geometry_task(client, {"x": 0, "y": 4, "z": 0}, positions)
    instance.args["requestedPorts"] = [{"role": "energy", "hotbarSlot": native.PORT_SLOTS["energy"]}]
    await instance.decide_ports(positions, groups)
    port = instance.select_port_candidate(0, instance.survival_port_candidates[0][0], groups, 2)
    energy = {"configuredBeforeNativeShell": True, "configurationReplayAllowed": False,
        "preservedAfterNativeShell": True, "afterShellVerification": "fresh_same_identity_public_voltage_read",
        "laterGuiAccessVerified": False, "widgetPageCoverage": {"before": {"pages": 1}}}
    result = {"status": "completed", "formationVerified": True, "selectedPorts": [port],
        "positionSelectionPolicy": instance.position_selection_policy, "energyConfiguration": energy}
    original = copy.deepcopy(result)
    report = compact_blueprint_report(result)
    assert report["selectedPorts"][0]["futureOperatingFaces"] == port["futureOperatingFaces"]
    assert report["positionSelectionPolicy"]["geometryFaceRanking"] == instance.port_operating_geometry
    assert report["energyConfiguration"] == {key: value for key, value in energy.items() if key != "widgetPageCoverage"}
    assert report["selectedPorts"][0]["futureOperatingFaces"]["pollutionExhaustVerified"] is False
    assert report["energyConfiguration"]["laterGuiAccessVerified"] is False
    assert report["formationVerified"] is True and result == original
