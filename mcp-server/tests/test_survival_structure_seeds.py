"""Directed offline proofs for permanent casing seeds and grounded port frontier."""

import copy
import importlib.util
import os
from pathlib import Path
import sys
import time

import pytest

from minecraft_codex_bridge import construction_tasks as production
from minecraft_codex_bridge.bridge import BridgeError, response_context
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import (
    CASING, CONTROLLER, CONTROLLER_ITEM, PORT_ITEMS, SUPPORT,
    declaration, key, native_construction, stack, writes,
)
from test_native_approach_tasks import composite_receipt


construction = production
if os.environ.get("MCB_SUPPORT_SEED_STAGE"):
    source = Path(os.environ["MCB_SUPPORT_SEED_STAGE"])
    name = "minecraft_codex_bridge._survival_structure_seeds_stage"
    spec = importlib.util.spec_from_file_location(name, source)
    construction = importlib.util.module_from_spec(spec)
    sys.modules[name] = construction
    spec.loader.exec_module(construction)


def task(client, **changes):
    return construction._ConstructionTask(client, declaration(
        actualSurvival=True, supportItem=CASING.copy(), **changes),
        run_workflow, started=time.monotonic())


def control(instance):
    return {"available": True, "source": "integrated_server_tick", "sessionId": "session",
        "worldName": instance.args["expectedWorld"], "dimension": 0, "sampleId": 7,
        "ageMillis": 0, "playerId": "actual-player"}


def install_survival_preflight(instance, client, state, monkeypatch, *, count=3):
    state["flying"] = False
    state["items"][6] = stack(CASING, count)
    state["caps"].append("structure.survival_build")
    monkeypatch.setattr(instance, "native_approach_available", lambda intent: True)
    original = instance.call

    async def observed(operation, args):
        result = await original(operation, args)
        if operation == "inventory.inspect":
            result["playerId"] = "actual-player"
        elif operation == "client.snapshot":
            result["player"]["creative"] = False
            result["player"]["serverFlight"]["creative"] = False
            result["serverControl"] = control(instance)
        return result

    monkeypatch.setattr(instance, "call", observed)


@pytest.mark.parametrize("gap", [1, 2, 3])
async def test_original_terrain_precedes_bounded_finite_seed_plan(native_construction, monkeypatch, gap):
    client, state = native_construction
    point = {**CONTROLLER, "y": 2 + gap}
    instance = task(client, controller=point)
    install_survival_preflight(instance, client, state, monkeypatch, count=gap)

    await instance.preflight()

    assert instance.floor == 1
    assert instance.survival_seed_plan == [{**point, "y": y} for y in range(2, 2 + gap)]
    pages = [row["args"]["positions"] for row in state["calls"] if row["operation"] == "world.inspect_many"]
    assert pages[0] == [point] + [{**point, "y": y} for y in range(1 + gap, -1, -1)]
    assert writes(state) == [] and instance.supports == []


@pytest.mark.parametrize("case,reason", [
    ("no_explicit_support", "survival_controller_support_unavailable"),
    ("insufficient", "survival_structure_seed_unavailable"),
    ("tall", "survival_structure_seed_unavailable"),
    ("wrong_source", "support_identity_mismatch"),
])
async def test_seed_preflight_refuses_without_material_or_scope(native_construction, monkeypatch, case, reason):
    client, state = native_construction
    instance = task(client, controller={**CONTROLLER, "y": 6 if case == "tall" else 4})
    install_survival_preflight(instance, client, state, monkeypatch, count=1 if case == "insufficient" else 3)
    if case == "no_explicit_support":
        instance.args.pop("supportItem")
        instance.args.pop("supportHotbarSlot")
        instance.survival_seed_mode = False
    elif case == "wrong_source":
        state["items"][6] = stack(SUPPORT, 3)

    with pytest.raises(BridgeError) as failure:
        await instance.preflight()

    assert failure.value.details["reason"] == reason
    assert writes(state) == [] and instance.supports == []


async def test_creative_and_existing_adjacent_survival_keep_seed_plan_empty(native_construction, monkeypatch):
    client, state = native_construction
    creative = construction._ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    await creative.preflight()
    assert creative.survival_seed_mode is False and creative.survival_seed_plan == []
    instance = task(client)
    install_survival_preflight(instance, client, state, monkeypatch)
    state["blocks"][key({**CONTROLLER, "x": CONTROLLER["x"] + 1})] = CASING.copy()
    await instance.preflight()
    assert instance.floor == 1 and instance.survival_seed_plan == []
    assert writes(state) == []


def seed_record(instance, state):
    point = {**CONTROLLER, "y": 2, "group": 0}
    position = {axis: point[axis] for axis in ("x", "y", "z")}
    instance.floor = 1
    instance.capture = {"captureId": "actual-capture", "capturedFacing": "NORTH_NORMAL_NONE"}
    instance.survival_seed_plan = [position.copy()]
    record = {"position": position, "permanentStructureSeed": True, "placementConfirmed": True,
        "serverConsumptionConfirmed": True, "nativePlaceBlockUsed": True, "hasTile": False,
        "block": CASING.copy(), "sourceItem": CASING.copy(), "sourceHotbarSlot": 6,
        "originalTerrainFloor": 1, "survivalMaterialLedgerIndex": 0, "refunded": False,
        "publicGroupMatched": False}
    instance.supports = [record]
    instance.survival_material_ledger = [{"position": position.copy(), "phase": "survival_structure_seed_2",
        "deductions": [{**CASING, "playerInventoryIndex": 6, "count": 1,
            "sourceCountBefore": 2, "sourceCountAfter": 1, "nbtIdentity": ""}]}]
    state["blocks"][key(position)] = CASING.copy()
    groups = {0: {"id": 0, "descriptionKeys": list(construction.ROLE_KEYS.values()),
        "suggestedCandidates": [stack(CASING)], "candidateScope": "actual public group"}}
    async def exact_public_positions(points):
        for actual in points:
            group = groups[actual["group"]]
            instance.survival_position_candidates[key(actual)] = {"complete": not group.get("candidatesTruncated", False),
                "detail": {"suggestedCandidates": [{**item, "nbtIdentity": "", "blockHasTileEntity": False,
                    "blockIdentity": {name: item[name] for name in ("registryName", "metadata")}} for item in group["suggestedCandidates"]]}}
    instance.read_position_candidates = exact_public_positions
    return point, record, groups


async def test_seed_requires_public_capture_and_retains_real_material_proof(native_construction):
    client, state = native_construction
    instance = task(client)
    point, record, groups = seed_record(instance, state)

    await instance.bind_survival_seeds([point], groups)

    assert record["publicGroupMatched"] is True and record["group"] == 0
    assert record["captureId"] == "actual-capture" and record["originalTerrainFloor"] == 1
    assert record["retained"] is True and record["refunded"] is False
    assert "formationVerified" not in record
    assert instance.survival_seed_positions == {key(point)}
    assert writes(state) == []


@pytest.mark.parametrize("fault", [
    "flags_only", "no_native", "unknown", "tile", "plan", "source_slot", "terrain_floor",
    "missing_capture", "phase", "ledger_slot", "count", "count_bool", "before", "after",
    "nbt", "noncanonical_empty_nbt", "identity", "public_identity", "tagged_candidate", "truncated_description",
    "truncated_candidates", "fresh_changed", "fresh_tile",
])
async def test_seed_binding_rejects_hash_flags_mismatch_or_foreign_data(native_construction, fault):
    client, state = native_construction
    instance = task(client)
    point, record, groups = seed_record(instance, state)
    positions = [point]
    deduction = instance.survival_material_ledger[0]["deductions"][0]
    if fault == "flags_only": record.pop("survivalMaterialLedgerIndex")
    elif fault == "no_native": record["nativePlaceBlockUsed"] = False
    elif fault == "unknown": record["outcomeUnknown"] = True
    elif fault == "tile": record["hasTile"] = True
    elif fault == "plan": instance.survival_seed_plan = []
    elif fault == "source_slot": record["sourceHotbarSlot"] = 5
    elif fault == "terrain_floor": record["originalTerrainFloor"] = 2
    elif fault == "missing_capture": positions = []
    elif fault == "phase": instance.survival_material_ledger[0]["phase"] = "foreign_placement"
    elif fault == "ledger_slot": deduction["playerInventoryIndex"] = 5
    elif fault == "count": deduction["count"] = 2
    elif fault == "count_bool": deduction["count"] = True
    elif fault == "before": deduction["sourceCountBefore"] = 3
    elif fault == "after": deduction["sourceCountAfter"] = -1
    elif fault == "nbt": deduction["nbtIdentity"] = "{foreign:1}"
    elif fault == "noncanonical_empty_nbt": deduction["nbtIdentity"] = "null"
    elif fault == "identity": deduction["metadata"] += 1
    elif fault == "public_identity": groups[0]["suggestedCandidates"][0]["registryName"] = "minecraft:stone"
    elif fault == "tagged_candidate": groups[0]["suggestedCandidates"][0]["hasTag"] = True
    elif fault == "truncated_description": groups[0]["descriptionTruncated"] = True
    elif fault == "truncated_candidates": groups[0]["candidatesTruncated"] = True
    elif fault == "fresh_changed": state["blocks"][key(point)] = SUPPORT.copy()
    elif fault == "fresh_tile": state["blocks"][key(point)]["tileClass"] = "Foreign.Tile"

    with pytest.raises(BridgeError):
        await instance.bind_survival_seeds(positions, groups)

    assert writes(state) == []
    assert record["refunded"] is False


@pytest.mark.parametrize("compact", [False, True])
async def test_volume_counts_owned_seed_separately_and_resumes_compact_prefix(native_construction, monkeypatch, compact):
    client, state = native_construction
    instance = task(client)
    point, record, groups = seed_record(instance, state)
    positions = [point] + [{"x": CONTROLLER["x"] + x, "y": 2, "z": CONTROLLER["z"], "group": 0}
        for x in range(1, 132)]
    await instance.bind_survival_seeds(positions, groups)
    instance.compact_inspection = compact
    original = instance.call
    seen = []

    async def compact_read(operation, args):
        if operation != "world.inspect_many" or not args.get("compact"):
            return await original(operation, args)
        selected = args["positions"][:37]
        seen.extend(selected)
        full = await original(operation, {**args, "positions": selected, "compact": False})
        partial = len(selected) < len(args["positions"])
        return {**full, "worldTick": 100, "nonAtomic": True, "requestedCount": len(args["positions"]),
            "processedCount": len(selected), "nextOffset": len(selected), "partial": partial,
            "complete": not partial, "byteLimited": partial, "budgetLimited": False,
            "results": [{**row, "requestIndex": index, "data": {**row["data"], "loaded": True}}
                for index, row in enumerate(full["results"])]}

    monkeypatch.setattr(instance, "call", compact_read)
    await instance.verify_clear_volume(positions)

    assert instance.volume_inspection["serverVerifiedAirPositions"] == 131
    assert instance.volume_inspection["serverVerifiedOwnedSeedPositions"] == 1
    if compact:
        assert [key(value) for value in seen] == [key(value) for value in positions]
        assert instance.volume_inspection["requests"] == 5
    assert writes(state) == [] and record["refunded"] is False


@pytest.mark.parametrize("case", ["foreign", "seed_changed", "seed_tile"])
async def test_other_nonair_or_modified_seed_never_becomes_clear_volume(native_construction, case):
    client, state = native_construction
    instance = task(client)
    point, _, groups = seed_record(instance, state)
    await instance.bind_survival_seeds([point], groups)
    other = {**point, "x": point["x"] + 1}
    state["blocks"][key(other)] = SUPPORT.copy() if case == "foreign" else {"registryName": "minecraft:air", "metadata": 0}
    if case == "seed_changed": state["blocks"][key(point)] = SUPPORT.copy()
    elif case == "seed_tile": state["blocks"][key(point)]["tileClass"] = "Foreign.Tile"
    with pytest.raises(BridgeError):
        await instance.verify_clear_volume([point, other])
    assert writes(state) == []


async def test_permanent_seed_excluded_from_deferred_port_candidates(native_construction):
    client, state = native_construction
    instance = task(client)
    point, _, groups = seed_record(instance, state)
    positions = [point] + [{**point, "x": point["x"] + x} for x in range(1, 6)]
    instance.bounds = {"worldXYZ": {"min": {axis: min(p[axis] for p in positions) for axis in ("x", "y", "z")},
        "max": {axis: max(p[axis] for p in positions) for axis in ("x", "y", "z")}}}
    instance.materials = {slot: {"item": item.copy(), "count": 1}
        for role, slot in ((p["role"], p["hotbarSlot"]) for p in instance.args["requestedPorts"])
        for item in [PORT_ITEMS[role]]}
    await instance.decide_ports(positions, groups)
    assert instance.selected_ports == []
    assert all(key(candidate[1]) != key(point) for choices in instance.survival_port_candidates.values() for candidate in choices)
    assert writes(state) == []


def prepare_frontier(instance, state, ports):
    instance.args["requestedPorts"] = [{"role": role, "hotbarSlot": slot} for role, slot, _ in ports]
    instance.materials = {slot: {"item": PORT_ITEMS[role].copy(), "count": 1} for role, slot, _ in ports}
    instance.capture = {"captureId": "actual-capture"}
    instance.survival_port_groups = {0: {"id": 0, "suggestedCandidates": [stack(CASING)]}}
    instance.survival_group_counts = {0: 20}
    instance.survival_port_candidates = {index: [((i,), {**point, "group": 0}, [stack(CASING)], True, True)
        for i, point in enumerate(points)] for index, (_, _, points) in enumerate(ports)}
    instance.survival_capture_positions = {key(row[1]) for candidates in instance.survival_port_candidates.values() for row in candidates}
    state["blocks"][key(CONTROLLER)] = {**CONTROLLER_ITEM, "itemMetadata": CONTROLLER_ITEM["metadata"]}
    instance._placed_controller_observation = {**CONTROLLER, "source": "integrated_server", "dimension": 0,
        "registryName": CONTROLLER_ITEM["registryName"], "metadata": 0,
        "gregtech": {"metaTileId": CONTROLLER_ITEM["metadata"]}}


def install_frontier_places(instance, state, monkeypatch):
    trace = []

    async def placed(point, slot, phase, record):
        assert instance._survival_frontier_binding
        assert any(key(attachment) in state["blocks"] or attachment["y"] <= 1
            for attachment, _ in instance._survival_frontier_binding)
        item = instance.materials[slot]["item"]
        state["blocks"][key(point)] = {**item, "itemMetadata": item["metadata"]}
        result = {**point, "registryName": item["registryName"], "metadata": 0,
            "gregtech": {"metaTileId": item["metadata"]}}
        record.update(placementConfirmed=True, block={"registryName": item["registryName"], "metadata": 0})
        trace.append((slot, key(point)))
        return result

    async def verified(phase, *, preserved):
        assert all(record.get("placementConfirmed") is True for record in instance.selected_ports)

    monkeypatch.setattr(instance, "place", placed)
    monkeypatch.setattr(instance, "port_empty_contents", lambda placed, role: {"empty": True})
    monkeypatch.setattr(instance, "verify_declared_ports", verified)
    return trace


async def test_frontier_reorders_roles_using_only_actually_installed_port(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    first = {**CONTROLLER, "x": CONTROLLER["x"] + 2}
    grounded = {**first, "y": 2}
    prepare_frontier(instance, state, [("energy", 3, [first]), ("item_input", 2, [grounded])])
    trace = install_frontier_places(instance, state, monkeypatch)
    probes = []

    async def proved(point, slot, attachments):
        probes.append((slot, key(point), [key(a) for a, _ in attachments]))
        return True

    monkeypatch.setattr(instance, "probe_survival_frontier", proved)
    await instance.install_ports_before_shell()
    assert trace == [(2, key(grounded)), (3, key(first))]
    assert key(grounded) in probes[-1][2]
    assert instance._survival_frontier_binding is None
    assert [port["role"] for port in instance.selected_ports] == ["item_input", "energy"]


async def test_frontier_skips_unproved_candidate_and_selects_alternative(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + x, "y": 2} for x in (1, 2)]
    prepare_frontier(instance, state, [("item_input", 2, points)])
    trace = install_frontier_places(instance, state, monkeypatch)
    attempts = []

    async def proved(point, slot, attachments):
        attempts.append(key(point))
        return key(point) == key(points[1])

    monkeypatch.setattr(instance, "probe_survival_frontier", proved)
    await instance.install_ports_before_shell()
    assert attempts == [key(point) for point in points]
    assert trace == [(2, key(points[1]))]


async def test_frontier_never_adopts_foreign_side_block_or_wrong_gt_port(native_construction):
    client, state = native_construction
    instance = task(client)
    target = {**CONTROLLER, "x": CONTROLLER["x"] + 2}
    installed = {**target, "x": target["x"] - 1}
    prepare_frontier(instance, state, [("item_input", 2, [target])])
    state["blocks"][key(installed)] = {**PORT_ITEMS["energy"], "itemMetadata": PORT_ITEMS["energy"]["metadata"]}
    instance.selected_ports = [{"position": installed, "placementConfirmed": True,
        "placementBeforeNativeShell": True, "block": {"registryName": "gregtech:gt.blockmachines", "metadata": 0},
        "item": PORT_ITEMS["item_input"].copy()}]
    assert await instance.survival_frontier_attachments(target) == []
    state["blocks"][key(installed)] = SUPPORT.copy()
    assert await instance.survival_frontier_attachments(target) == []
    assert writes(state) == []


async def test_unreachable_frontier_is_bounded_without_any_native_mutation(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + x, "y": 2} for x in range(1, 41)]
    prepare_frontier(instance, state, [("item_input", 2, points)])
    probes = []

    async def unproved(point, slot, attachments):
        probes.append(key(point))
        return False

    monkeypatch.setattr(instance, "probe_survival_frontier", unproved)
    with pytest.raises(BridgeError) as failure:
        await instance.install_ports_before_shell()
    assert failure.value.details["reason"] == "survival_port_frontier_unavailable"
    assert len(probes) == 32 and instance.selected_ports == []
    assert writes(state) == []


def frontier_receipt(instance, state, point, slot):
    state["flying"] = False
    args = {**instance.point(point), "expectedWorld": instance.args["expectedWorld"],
        "intent": "place_block", "pattern": "surround", "allowedSides": [1], "hotbarSlot": slot}
    result = composite_receipt(state, args)
    result["serverFlight"]["creative"] = False
    snapshot = response_context(result)["snapshot"]
    snapshot["player"]["creative"] = False
    snapshot["player"]["serverFlight"]["creative"] = False
    snapshot["serverControl"] = control(instance)
    return result


@pytest.mark.parametrize("status", ["no_candidate", "client_space_unverified", "client_face_unverified", "unknown", "partial"])
async def test_unknown_native_probe_is_read_only_and_not_placed(native_construction, monkeypatch, status):
    client, state = native_construction
    instance = task(client)
    monkeypatch.setattr(instance, "current_native_approach", None, raising=False)
    point = {**CONTROLLER, "y": 2, "x": CONTROLLER["x"] + 1}
    result = frontier_receipt(instance, state, point, 2)
    result.update(status=status, selected=None)
    calls = []

    async def observed(operation, args):
        calls.append((operation, args))
        return result

    monkeypatch.setattr(instance, "call", observed)
    assert await instance.probe_survival_frontier(point, 2, [({**point, "y": 1}, 1)]) is False
    assert calls[0][0] == "player.plan_approach" and len(calls) == 1
    assert calls[0][1]["requireFlying"] is False and calls[0][1]["allowedSides"] == [1]
    assert instance.survival_frontier_observations[-1]["placementDispatched"] is False
    assert writes(state) == []


@pytest.mark.parametrize("fault", [None, "server_body", "client_body", "ray", "source_count",
    "context_version", "dimension_bool", "submitted", "stale_control"])
async def test_ready_frontier_needs_full_native_ground_reach_source_and_epoch(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    monkeypatch.setattr(instance, "current_native_approach", None, raising=False)
    point = {**CONTROLLER, "y": 2, "x": CONTROLLER["x"] + 1}
    instance.materials[2] = {"item": PORT_ITEMS["item_input"].copy(), "count": 1}
    result = frontier_receipt(instance, state, point, 2)
    if fault == "server_body": result["selected"]["serverBody"]["canStand"] = False
    elif fault == "client_body": result["selected"]["clientBody"]["canStand"] = False
    elif fault == "ray": result["selected"]["clientRay"]["verified"] = False
    elif fault == "source_count": result["current"]["sourceItem"]["count"] = 2
    elif fault == "context_version": result.context["version"] = True
    elif fault == "dimension_bool": result["dimension"] = False
    elif fault == "submitted": result["submitted"] = True
    elif fault == "stale_control": result.context["snapshot"]["serverControl"]["ageMillis"] = 1001

    async def observed(operation, args):
        assert operation == "player.plan_approach"
        return result

    monkeypatch.setattr(instance, "call", observed)
    if fault is None:
        assert await instance.probe_survival_frontier(point, 2, [({**point, "y": 1}, 1)]) is True
        assert instance.survival_frontier_observations[-1]["usable"] is True
    else:
        with pytest.raises(BridgeError):
            await instance.probe_survival_frontier(point, 2, [({**point, "y": 1}, 1)])
    assert writes(state) == []


async def test_shared_current_frontier_helper_refuses_missing_current_only_capability(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    if not callable(getattr(instance, "current_native_approach", None)):
        pytest.skip("The legacy base does not expose the optional current-only helper")
    assert instance.approach_health is None

    async def unexpected(operation, args):
        pytest.fail("Missing advertised current-only proof must stop before a planning read")

    monkeypatch.setattr(instance, "call", unexpected)
    with pytest.raises(BridgeError) as failure:
        await instance.probe_survival_frontier(CONTROLLER, 2, [({**CONTROLLER, "y": 2}, 1)])
    assert failure.value.details["reason"] == "actual_current_approach_unavailable"
    assert instance.survival_frontier_native_reads == 0 and writes(state) == []


async def test_native_probe_budget_refuses_before_another_read(native_construction, monkeypatch):
    client, _ = native_construction
    instance = task(client)
    instance.survival_frontier_observations = [{} for _ in range(64)]

    async def unexpected(operation, args):
        pytest.fail("Budget exhaustion must not issue another read or action")

    monkeypatch.setattr(instance, "call", unexpected)
    with pytest.raises(BridgeError) as failure:
        await instance.probe_survival_frontier(CONTROLLER, 2, [({**CONTROLLER, "y": 2}, 1)])
    assert failure.value.details["reason"] == "survival_port_frontier_budget"


async def test_seed_preserved_through_shell_formation_and_failure_cleanup(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    point, record, groups = seed_record(instance, state)
    await instance.bind_survival_seeds([point], groups)
    await instance.verify_survival_seed_blocks("seed_after_shell")
    assert "formationVerified" not in record
    instance.formation_verified = True
    await instance.verify_survival_seed_blocks("seed_after_formation")
    assert record["formationVerified"] is True and record["lastServerVerification"] == "seed_after_formation"
    record["placementAttempted"] = True

    async def unexpected(*args, **kwargs):
        pytest.fail("Permanent structure seed may not be cleaned, dug, dropped or refunded")

    monkeypatch.setattr(instance, "dig_owned", unexpected)
    await instance.cleanup_supports()
    assert state["blocks"][key(point)] == CASING
    assert record["refunded"] is False and "cleanupAttempted" not in record
    assert writes(state) == []


async def test_default_survival_port_install_enters_generic_frontier_without_explicit_support(native_construction, monkeypatch):
    client, _ = native_construction
    arguments = declaration(actualSurvival=True)
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    instance = construction._ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    calls = []

    async def frontier():
        calls.append("generic_frontier")

    monkeypatch.setattr(instance, "install_survival_frontier_ports", frontier)
    await instance.install_ports_before_shell()
    assert instance.survival_seed_mode is True and calls == ["generic_frontier"]


def server_inventory_from_state(state):
    rows = []
    for index in range(41):
        item = copy.deepcopy(state["items"][index])
        if item is not None:
            item["nbtIdentity"] = state.get("nbt", {}).get(index, "")
        rows.append({"index": index, "item": item})
    return {"source": "integrated_server", "inventorySource": "actual_player_inventory",
        "complete": True, "totalSlots": 41, "truncated": False, "cursorItem": None, "slots": rows}


def install_finite_native_receipts(instance, state, monkeypatch, *, mutate=None, different_attachment=False):
    """Exercise the task's native receipt accounting with finite authoritative DTOs."""
    from minecraft_codex_bridge.placement_attachment import CONTRACT
    instance.capabilities = state["caps"]
    instance.approach_health = {**(instance.approach_health or {}), "placementAttachmentBinding": CONTRACT.copy(),
        "contextRpc": {"version": 1,
            "reads": (instance.approach_health or {}).get("contextRpc", {}).get("reads", []),
            "actions": ["player.place_block"]}}
    monkeypatch.setattr(instance, "native_approach_available", lambda intent: True)
    calls = []

    async def approach(point, slot, record):
        target, side = instance._survival_frontier_binding[0] if instance._survival_frontier_binding else ({**point, "y": point["y"] - 1}, 1)
        raw = state["blocks"].get(key(target), SUPPORT)
        selected = {**target, "side": side, "dimension": instance.dimension, "source": "integrated_server",
            "registryName": raw["registryName"], "metadata": raw["metadata"]}
        if raw["registryName"] == "gregtech:gt.blockmachines":
            selected.update(metadata=0, gregtech={"metaTileId": raw.get("itemMetadata", raw["metadata"]), "metaClass": "Fixture.PublicMeta"})
        instance._planned_placement_attachment = selected
        return {axis: target[axis] for axis in ("x", "y", "z")}, side, []

    async def native_workflow(phase, steps):
        actions = [step for step in steps if step.get("tool") == "mcb_place_block"]
        assert len(actions) == 1 and actions[0]["args"]["replace"] is False
        action = actions[0]
        point = {axis: action["args"][axis] for axis in ("x", "y", "z")}
        slot = action["args"]["hotbarSlot"]
        before = server_inventory_from_state(state)
        item = copy.deepcopy(state["items"][slot])
        assert item is not None and item["count"] > 0
        state["items"][slot] = {**item, "count": item["count"] - 1} if item["count"] > 1 else None
        raw = {"registryName": item["registryName"], "metadata": item["metadata"]}
        if item["registryName"] == "gregtech:gt.blockmachines":
            raw["itemMetadata"] = item["metadata"]
        state["blocks"][key(point)] = raw
        observed = {**point, "source": "integrated_server", "dimension": 0, **raw}
        if item["registryName"] == "gregtech:gt.blockmachines":
            observed.update(metadata=0, gregtech={"metaTileId": item["metadata"]}, tileClass="Actual.Native.Tile")
        binding = action["args"].get("attachmentBinding")
        attachment = {key: binding[key] for key in ("x", "y", "z", "side")} if binding else {**point, "y": point["y"] - 1, "side": 1}
        if different_attachment:
            attachment = {**point, "x": point["x"] - 1, "side": 5}
        placed = {"actualSurvival": True, "serverConfirmed": True, "serverConsumptionConfirmed": True,
            "serverSourceConsumption": 1, "serverBefore": before, "serverAfter": server_inventory_from_state(state),
            "serverTarget": observed, "nativeInvoked": True, "attachment": attachment}
        if binding:
            placed.update(attachmentBinding=copy.deepcopy(binding), attachmentBindingVerified=True,
                clientAttachmentBindingVerified=True, serverAttachmentBindingVerified=True, allPlayerSlots=41, fullNbtConservation=True)
        if mutate is not None:
            mutate(placed)
        instance.side_effects = True
        calls.append((phase, point, slot))
        return {"status": "completed", "steps": [
            {"id": "place", "observation": {"values": placed}},
            {"id": "placed", "observation": {"values": observed}},
            {"id": "material_after", "observation": {"values": server_inventory_from_state(state)}}]}

    monkeypatch.setattr(instance, "supported_placement_approach", approach)
    monkeypatch.setattr(instance, "workflow", native_workflow)
    return calls


async def test_finite_native_seed_retains_exact_ledger_index_and_last_source_slot_empty(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    state["items"][6] = stack(CASING)
    instance.materials[6] = {"item": CASING.copy(), "count": 1}
    calls = install_finite_native_receipts(instance, state, monkeypatch)
    point = {**CONTROLLER, "y": 2}
    record = {"position": point.copy(), "permanentStructureSeed": True}
    await instance.place(point, 6, "survival_structure_seed_2", record)
    assert calls == [("survival_structure_seed_2", point, 6)]
    assert record["nativePlaceBlockUsed"] is True and record["placementConfirmed"] is True
    assert record["serverConsumptionConfirmed"] is True and record["survivalMaterialLedgerIndex"] == 0
    assert instance.materials[6]["count"] == 0 and state["items"][6] is None
    assert instance.survival_material_ledger[0]["deductions"] == [{**CASING, "playerInventoryIndex": 6,
        "count": 1, "sourceCountBefore": 1, "sourceCountAfter": 0, "nbtIdentity": ""}]


@pytest.mark.parametrize("fault", ["missing_server_confirmation", "decrement_two", "wrong_slot",
    "unrelated_inventory", "nbt", "wrong_target", "source_flag_only"])
async def test_native_seed_consumption_requires_complete_real_deltas(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    state["items"][6] = stack(CASING, 2)
    instance.materials[6] = {"item": CASING.copy(), "count": 2}

    def corrupt(receipt):
        before, after = receipt["serverBefore"], receipt["serverAfter"]
        if fault == "missing_server_confirmation": receipt["serverConfirmed"] = False
        elif fault == "decrement_two": after["slots"][6]["item"] = None
        elif fault == "wrong_slot":
            before["slots"][4]["item"], before["slots"][6]["item"] = before["slots"][6]["item"], None
            after["slots"][4]["item"], after["slots"][6]["item"] = after["slots"][6]["item"], None
        elif fault == "unrelated_inventory": after["slots"][0]["item"]["count"] -= 1
        elif fault == "nbt": after["slots"][6]["item"]["nbtIdentity"] = "{different:1}"
        elif fault == "wrong_target": receipt["serverTarget"]["x"] += 1
        elif fault == "source_flag_only": receipt.pop("serverBefore")

    calls = install_finite_native_receipts(instance, state, monkeypatch, mutate=corrupt)
    record = {"position": {**CONTROLLER, "y": 2}, "permanentStructureSeed": True}
    with pytest.raises(BridgeError):
        await instance.place(record["position"], 6, "survival_structure_seed_2", record)
    assert len(calls) == 1 and instance.side_effects is True
    assert instance.survival_material_ledger == []
    assert record.get("placementConfirmed") is not True and record.get("serverConsumptionConfirmed") is not True


async def test_actual_attachment_change_keeps_known_mutation_and_stops_once(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    state["items"][6] = stack(CASING, 2)
    instance.materials[6] = {"item": CASING.copy(), "count": 2}
    point = {**CONTROLLER, "y": 2}
    instance._survival_frontier_binding = [({**point, "y": 1}, 1)]
    calls = install_finite_native_receipts(instance, state, monkeypatch, different_attachment=True)
    record = {"position": point.copy(), "frontierVerified": True}
    with pytest.raises(BridgeError) as failure:
        await instance.place(point, 6, "port_before_shell_0", record)
    assert failure.value.details["reason"] == "survival_port_frontier_changed"
    assert len(calls) == 1 and instance.side_effects is True
    assert record["placementConfirmed"] is True and record["serverConsumptionConfirmed"] is True
    assert record["frontierVerified"] is False and record["frontierRejectedAfterNative"] is True
    assert state["items"][6]["count"] == instance.materials[6]["count"] == 1
    assert len(instance.survival_material_ledger) == 1 and state["blocks"][key(point)] == CASING


async def test_seed_and_controller_native_placements_preserve_original_terrain_floor(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    install_survival_preflight(instance, client, state, monkeypatch, count=1)
    await instance.preflight()
    assert instance.floor == 1 and len(instance.survival_seed_plan) == 1
    calls = install_finite_native_receipts(instance, state, monkeypatch)
    await instance.place_controller()
    assert [row[0] for row in calls] == ["survival_structure_seed_2", "controller_place"]
    assert instance.floor == 1 and instance.supports[0]["originalTerrainFloor"] == 1
    assert instance.supports[0]["permanentStructureSeed"] is True
    assert state["items"][6] is None and state["items"][1] is None
    assert state["blocks"][key({**CONTROLLER, "y": 2})] == CASING
    assert "cleanupAttempted" not in instance.supports[0]
    assert len(instance.survival_material_ledger) == 2
