"""Exact public position candidates, finite backpack sources and permanent generic connections."""

import copy
import json

import pytest
from jsonschema import Draft202012Validator

from minecraft_codex_bridge.bridge import BridgeError, _ContextData, _REQUEST_CONTEXT, request_context
from minecraft_codex_bridge.construction_tasks import _MAX_FRONTIER_CANDIDATE_BYTES, _shortest_seed_path
from minecraft_codex_bridge.tools import STRUCTURE_SCHEMA
from test_construction_tasks import CASING, CONTROLLER, CONTROLLER_ITEM, PORT_ITEMS, key, native_construction, stack
from test_survival_structure_seeds import task, server_inventory_from_state, install_finite_native_receipts, frontier_receipt, control


ELECTRIC = {**CASING, "metadata": 4}
PIPE = {**CASING, "metadata": 5}


def candidate(item):
    return {**stack(item), "nbtIdentity": "", "blockIdentity": item.copy(), "blockHasTileEntity": False}


def setup(instance, state):
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + x, "group": 0 if x == 1 else 1} for x in (1, 2, 3)]
    instance.floor = 1
    instance.survival_player_id = "actual-player"
    instance.capture = {"captureId": "exact-capture", "capturedFacing": "NORTH_NORMAL_NONE", "capturedMode": 0,
        "trigger": {"registryName": "structurelib:constructableTrigger", "metadata": 0, "count": 16, "channelData": ""}}
    instance.survival_capture_points = {key(point): point for point in points}
    instance.survival_capture_positions = set(instance.survival_capture_points)
    instance.args["requestedPorts"] = [{"role": "item_input", "hotbarSlot": 2}]
    instance.survival_port_groups = {group: {"id": group, "descriptionKeys": ["GT5U.MBTT.InputBus"],
        "suggestedCandidates": [candidate(ELECTRIC)], "candidateScope": "Only the representative; intentionally wrong for group 1"} for group in (0, 1)}
    instance.survival_group_counts = {0: 3, 1: 4}
    instance.survival_port_candidates = {0: [((0,), points[-1], [candidate(PIPE)], True, True)]}
    state["items"][1] = None
    state["items"][6] = None
    state["items"][9], state["items"][10] = stack(ELECTRIC), stack(PIPE)
    state["blocks"][key(CONTROLLER)] = {**CONTROLLER_ITEM, "itemMetadata": CONTROLLER_ITEM["metadata"]}
    instance._placed_controller_observation = {**CONTROLLER, "source": "integrated_server", "dimension": 0,
        "registryName": CONTROLLER_ITEM["registryName"], "metadata": 0, "gregtech": {"metaTileId": CONTROLLER_ITEM["metadata"]}}
    instance.materials[2] = {"item": PORT_ITEMS["item_input"].copy(), "count": 1}
    instance.survival_inventory = server_inventory_from_state(state)["slots"]
    return points


def candidate_receipt(instance, requested, offset=0, prefix=None):
    rows = []
    end = len(requested) if prefix is None else min(len(requested), offset + prefix)
    for index in range(offset, end):
        point = requested[index]
        rows.append({**point, "requestIndex": index, "status": "known_suggestions", "complete": True,
            "publicElementBound": True, "candidateCount": 1, "detail": {"loaded": True,
                "candidateCoverage": "public_stack_suggestions", "suggestedCandidates": [candidate(ELECTRIC if point["group"] == 0 else PIPE)]}})
    return {"version": 1, "source": "client_public_structure_element_getter", "captureId": instance.capture["captureId"],
        "sessionId": "session", "playerId": "actual-player", "dimension": 0, **CONTROLLER,
        "capturedFacing": instance.capture["capturedFacing"], "capturedMode": instance.capture["capturedMode"], "trigger": copy.deepcopy(instance.capture["trigger"]),
        "ownerBound": True, "captureMembershipVerified": True, "readOnly": True, "ageMillis": 1,
        "fullStructureChecked": False, "completeRulesKnown": False, "formationVerified": False,
        "requestedCount": len(requested), "candidateOffset": offset, "processedCount": end - offset,
        "nextCandidateOffset": end, "complete": end == len(requested), "partial": end != len(requested),
        "byteLimited": False, "budgetLimited": end != len(requested), "positions": rows}


def install_reads(instance, state, monkeypatch, *, corrupt=None, prefix=None, context_fault=None):
    reads = []
    original = instance.call

    async def observed(operation, args):
        if operation == "world.structure":
            assert _REQUEST_CONTEXT.get() == {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
                "expectedWorld": instance.args["expectedWorld"], "requireBackground": True}
            reads.append(copy.deepcopy(args))
            result = {"source": "integrated_server", "sessionId": "session", "dimension": 0}
            if "candidatePositions" in args:
                result["candidateObservation"] = candidate_receipt(instance, args["candidatePositions"], args.get("candidateOffset", 0), prefix)
                if corrupt:
                    corrupt(result["candidateObservation"])
            if args.get("includeSurvivalInventory"):
                inventory = server_inventory_from_state(state)
                inventory.update(playerId="actual-player", sessionId="session", dimension=0)
                result["serverPlayerInventory"] = inventory
            state["flying"] = False
            with request_context(None):
                snapshot = await original("client.snapshot", {})
            snapshot["player"]["creative"] = False
            snapshot["player"]["serverFlight"]["creative"] = False
            snapshot["serverControl"] = control(instance)
            context = {"version": 1, "operation": "world.structure", "phase": "after", "snapshot": snapshot}
            if context_fault == "missing": return result
            elif context_fault == "version": context["version"] = True
            elif context_fault == "operation": context["operation"] = "world.inspect"
            elif context_fault == "phase": context["phase"] = "before"
            elif context_fault == "snapshot": context["snapshot"] = None
            elif context_fault == "control": context["snapshot"]["serverControl"]["ageMillis"] = 1001
            return _ContextData(result, context)
        if operation == "inventory.inspect":
            return server_inventory_from_state(state)
        return await original(operation, args)

    monkeypatch.setattr(instance, "call", observed)
    return reads


def install_moves(instance, state, monkeypatch, *, unrelated=False):
    moves = []

    async def opened(phase):
        return "actual-native-screen", {}

    async def moved(record, screen, source, destination, phase, *, close_after=False):
        assert state["items"][destination] is None and screen == "actual-native-screen"
        moves.append((source, destination, phase))
        state["items"][destination], state["items"][source] = state["items"][source], None
        if unrelated:
            state["items"][0]["count"] -= 1
        record.update(movementDispatched=True, movementConfirmed=True)

    monkeypatch.setattr(instance, "open_creative_player_inventory", opened)
    monkeypatch.setattr(instance, "move_native_player_stack", moved)
    return moves


async def test_two_groups_connect_with_their_own_candidates_and_real_backpack_deductions(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    reads = install_reads(instance, state, monkeypatch)
    moves = install_moves(instance, state, monkeypatch)
    placements = install_finite_native_receipts(instance, state, monkeypatch)

    async def proved(point, slot, attachments):
        assert attachments and all(key(target) in state["blocks"] for target, _ in attachments)
        return True

    async def verified(phase, *, preserved):
        assert len(instance.selected_ports) == 1 and instance.selected_ports[0]["placementConfirmed"]

    monkeypatch.setattr(instance, "probe_survival_frontier", proved)
    monkeypatch.setattr(instance, "verify_declared_ports", verified)
    monkeypatch.setattr(instance, "port_empty_contents", lambda block, role: {"empty": True})
    # Native attachment DTO must report the real owned side rather than a fabricated floor.
    original_workflow = instance.workflow

    async def actual_attachment(phase, steps):
        result = await original_workflow(phase, steps)
        receipt = result["steps"][0]["observation"]["values"]
        point = receipt["serverTarget"]
        receipt["attachment"] = {"x": point["x"] - 1, "y": point["y"], "z": point["z"], "side": 5}
        return result

    monkeypatch.setattr(instance, "workflow", actual_attachment)
    await instance.install_survival_frontier_ports()

    assert [row[1] for row in placements] == [{axis: point[axis] for axis in ("x", "y", "z")} for point in points]
    assert [record["sourceItem"] for record in instance.supports] == [ELECTRIC, PIPE]
    assert [record["group"] for record in instance.supports] == [0, 1]
    assert all(record["publicValidatedBeforePlacement"] and record["publicGroupMatched"] and record["retained"] for record in instance.supports)
    assert all(record["refunded"] is False and "formationVerified" not in record for record in instance.supports)
    assert state["items"][9] is None and state["items"][10] is None
    assert moves == [(9, 1, "seed_source_retrieve"), (10, 1, "seed_source_retrieve")]
    assert instance.survival_auto_seed_count == 2 and len(instance.survival_material_ledger) == 3
    assert [row["deductions"][0]["metadata"] for row in instance.survival_material_ledger[:2]] == [4, 5]
    assert [sample["pathLength"] for sample in instance.survival_seed_planning] == [2, 1]
    assert any("candidatePositions" in args for args in reads)


async def test_candidate_pages_resume_exact_prefix_and_do_not_repeat_previous_coordinates(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    reads = install_reads(instance, state, monkeypatch, prefix=1)
    await instance.read_position_candidates(points)
    assert [row["candidateOffset"] for row in reads] == [0, 1, 2]
    assert set(instance.survival_position_candidates) == {key(point) for point in points}
    assert instance.survival_position_candidates[key(points[1])]["detail"]["suggestedCandidates"][0]["metadata"] == 5


@pytest.mark.parametrize("ttl,age,captured,accepted", [
    (120_000, 36_000, 120_000, True), (120_000, 120_000, 120_000, True),
    (120_000, 120_001, 120_000, False), (120_001, 36_000, 120_001, False),
    (True, 1, True, False), (120_000, 36_000, 30_000, False),
    (30_000, 30_001, 30_000, False),
])
async def test_candidate_retention_matches_bounded_capture_contract(native_construction, monkeypatch, ttl, age, captured, accepted):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    instance.capture["limits"] = {"ttlMillis": captured}
    def lifetime(data):
        data.update(ttlMillis=ttl, ageMillis=age)
    install_reads(instance, state, monkeypatch, corrupt=lifetime)
    if accepted:
        await instance.read_position_candidates(points)
        assert len(instance.survival_position_candidates) == len(points)
    else:
        with pytest.raises(BridgeError):
            await instance.read_position_candidates(points)
        assert instance.survival_position_candidates == {}


async def test_exact_candidate_byte_budget_rejects_before_retaining_a_row(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    install_reads(instance, state, monkeypatch)
    row = candidate_receipt(instance, points[:1])["positions"][0]
    row_bytes = len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    instance.survival_position_candidate_bytes = _MAX_FRONTIER_CANDIDATE_BYTES - row_bytes + 1
    with pytest.raises(BridgeError) as failure:
        await instance.read_position_candidates(points[:1])
    assert failure.value.details["reason"] == "survival_seed_candidate_byte_budget"
    assert instance.survival_position_candidates == {}
    assert instance.survival_position_candidate_bytes == _MAX_FRONTIER_CANDIDATE_BYTES - row_bytes + 1
    assert instance.supports == [] and instance.survival_material_ledger == []


async def test_exact_candidate_fresh_rereads_consume_the_cumulative_byte_budget(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    reads = install_reads(instance, state, monkeypatch)
    row = candidate_receipt(instance, points[:1])["positions"][0]
    row_bytes = len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    instance.survival_position_candidate_bytes = _MAX_FRONTIER_CANDIDATE_BYTES - row_bytes
    await instance.read_position_candidates(points[:1])
    assert instance.survival_position_candidate_bytes == _MAX_FRONTIER_CANDIDATE_BYTES
    await instance.read_position_candidates(points[:1])
    assert len(reads) == 1
    instance.survival_position_candidates.pop(key(points[0]))
    with pytest.raises(BridgeError) as failure:
        await instance.read_position_candidates(points[:1])
    assert failure.value.details["reason"] == "survival_seed_candidate_byte_budget"
    assert instance.survival_position_candidate_bytes == _MAX_FRONTIER_CANDIDATE_BYTES and len(reads) == 2


@pytest.mark.parametrize("context_fault", ["missing", "version", "operation", "phase", "snapshot", "control"])
@pytest.mark.parametrize("read", ["candidates", "inventory"])
async def test_candidate_and_server_inventory_reads_require_their_own_guarded_after_context(native_construction, monkeypatch, context_fault, read):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    install_reads(instance, state, monkeypatch, context_fault=context_fault)
    with pytest.raises(BridgeError):
        if read == "candidates":
            await instance.read_position_candidates(points)
        else:
            await instance.frontier_inventory()
    assert instance.survival_position_candidates == {} and instance.supports == []
    assert instance.survival_material_ledger == []


async def test_optional_current_helper_read_counts_and_normal_reach_forwarding(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    point = {**CONTROLLER, "y": 2, "x": CONTROLLER["x"] + 1}
    instance.materials[2] = {"item": PORT_ITEMS["item_input"].copy(), "count": 1}
    ready = frontier_receipt(instance, state, point, 2)
    calls, validation = [], []

    async def observed(operation, args):
        calls.append((operation, args.copy()))
        return ready

    async def current(target, **options):
        assert target == point and options["allowed_sides"] == (1,)
        await instance.call("player.plan_approach", {"currentOnly": True, "maxDistance": 3})
        await instance.call("player.plan_approach", {"currentOnly": True, "useNormalReach": True, "maxDistance": 4.5})
        return {"status": "no_candidate", "useNormalReach": True, "current": {"maxDistance": 4.5}}

    def validated(result, target, **options):
        validation.append(options)

    monkeypatch.setattr(instance, "call", observed)
    monkeypatch.setattr(instance, "current_native_approach", current, raising=False)
    monkeypatch.setattr(instance, "validate_native_approach", validated)
    assert await instance.probe_survival_frontier(point, 2, [({**point, "y": 1}, 1)])
    assert instance.call is observed and instance.survival_frontier_native_reads == 3
    assert calls[-1][1]["useNormalReach"] is True and calls[-1][1]["maxDistance"] == 4.5
    assert validation[0]["use_normal_reach"] is True and validation[0]["max_distance"] == 4.5
    assert instance.survival_frontier_observations[-1]["nativeReadsSoFar"] == 3


async def test_optional_current_helper_cannot_overrun_native_read_budget(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    point = {**CONTROLLER, "y": 2, "x": CONTROLLER["x"] + 1}
    instance.survival_frontier_native_reads = 63
    calls = []

    async def observed(operation, args):
        calls.append(operation)
        return {}

    async def current(target, **options):
        await instance.call("player.plan_approach", {"currentOnly": True})
        await instance.call("player.plan_approach", {"currentOnly": True, "useNormalReach": True})
        pytest.fail("The second helper read must stop before dispatch")

    monkeypatch.setattr(instance, "call", observed)
    monkeypatch.setattr(instance, "current_native_approach", current, raising=False)
    with pytest.raises(BridgeError) as failure:
        await instance.probe_survival_frontier(point, 2, [({**point, "y": 1}, 1)])
    assert failure.value.details["reason"] == "survival_port_frontier_budget"
    assert instance.call is observed and calls == ["player.plan_approach"]
    assert instance.survival_frontier_native_reads == 64 and instance.supports == []


@pytest.mark.parametrize("fault", ["owner", "capture", "session", "player", "dimension_bool", "xyz", "group", "index", "trigger", "facing", "age", "prefix", "flags", "truncated", "unloaded"])
async def test_partial_stale_or_wrong_position_candidate_data_never_drives_mutation(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)

    def corrupt(data):
        if fault == "owner": data["ownerBound"] = False
        elif fault == "capture": data["captureId"] = "other"
        elif fault == "session": data["sessionId"] = "other"
        elif fault == "player": data["playerId"] = "other"
        elif fault == "dimension_bool": data["dimension"] = False
        elif fault == "xyz": data["positions"][0]["x"] += 1
        elif fault == "group": data["positions"][0]["group"] = 7
        elif fault == "index": data["positions"][0]["requestIndex"] = 1
        elif fault == "trigger": data["trigger"]["count"] += 1
        elif fault == "facing": data["capturedFacing"] = "SOUTH_NORMAL_NONE"
        elif fault == "age": data["ageMillis"] = 30_001
        elif fault == "prefix": data["processedCount"] = 0
        elif fault == "flags": data["formationVerified"] = True
        elif fault == "truncated": data["positions"][0]["detail"]["candidatesTruncated"] = True
        elif fault == "unloaded": data["positions"][0]["detail"]["loaded"] = False

    install_reads(instance, state, monkeypatch, corrupt=corrupt)
    with pytest.raises(BridgeError):
        await instance.read_position_candidates(points)
    assert instance.supports == [] and instance.survival_material_ledger == []


@pytest.mark.parametrize("fault", ["tagged", "nbt", "tile", "air", "different_block", "zero", "unknown", "empty", "no_source", "reserved_source"])
async def test_unavailable_casing_or_finite_source_cannot_be_a_graph_node(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    install_reads(instance, state, monkeypatch)
    await instance.read_position_candidates(points)
    row = instance.survival_position_candidates[key(points[0])]
    value = row["detail"]["suggestedCandidates"][0]
    if fault == "tagged": value["hasTag"] = True
    elif fault == "nbt": value["nbtIdentity"] = "{unexpected:1}"
    elif fault == "tile": value["blockHasTileEntity"] = True
    elif fault == "air": value["registryName"] = "minecraft:air"
    elif fault == "different_block": value["blockIdentity"]["metadata"] += 1
    elif fault == "zero": value["count"] = 0
    elif fault == "unknown": row["complete"] = False
    elif fault == "empty": row["detail"]["suggestedCandidates"] = []
    elif fault == "no_source": state["items"][9] = None
    elif fault == "reserved_source": state["items"][2], state["items"][9] = state["items"][9], None
    choices = instance.frontier_seed_choices(await instance.frontier_inventory(), points)
    assert key(points[0]) not in choices
    assert instance.supports == []


async def test_backpack_movement_requires_full_unrelated_server_nbt_conservation(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    install_reads(instance, state, monkeypatch)
    moves = install_moves(instance, state, monkeypatch, unrelated=True)
    await instance.read_position_candidates(points)
    choice = instance.frontier_seed_choices(await instance.frontier_inventory(), points)[key(points[0])][0]
    with pytest.raises(BridgeError) as failure:
        await instance.prepare_frontier_seed_source(choice)
    assert failure.value.details["reason"] == "survival_seed_move_unconfirmed" and len(moves) == 1
    assert instance.supports == [] and instance.survival_material_ledger == []


def test_shortest_connection_accounts_for_real_quantity_and_bounded_cost():
    points = {(x, 4, 0): {"x": x, "y": 4, "z": 0, "group": x % 2} for x in (1, 2, 3)}
    choices = {position: [{"sourceIndex": 9 if position[0] % 2 else 10}] for position in points}
    path = _shortest_seed_path(points, choices, {(0, 4, 0)}, {(4, 4, 0)}, {9: 2, 10: 1}, 3)
    assert [step["position"]["x"] for step in path] == [1, 2, 3]
    assert _shortest_seed_path(points, choices, {(0, 4, 0)}, {(4, 4, 0)}, {9: 1, 10: 1}, 3) is None
    assert _shortest_seed_path(points, choices, {(0, 4, 0)}, {(4, 4, 0)}, {9: 2, 10: 1}, 2) is None


async def test_public_candidate_change_is_refused_before_the_seed_or_inventory_move(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    setup(instance, state)
    calls = 0

    def changed(data):
        nonlocal calls
        calls += 1
        if calls == 2:
            data["positions"][0]["detail"]["suggestedCandidates"][0] = candidate(PIPE)

    install_reads(instance, state, monkeypatch, corrupt=changed)
    moves = install_moves(instance, state, monkeypatch)
    with pytest.raises(BridgeError) as failure:
        await instance.extend_survival_frontier(instance.survival_port_candidates, set())
    assert failure.value.details["reason"] == "survival_seed_position_candidate_changed"
    assert moves == [] and instance.supports == [] and instance.survival_material_ledger == []


async def test_exhausted_automatic_seed_budget_performs_no_read_move_or_placement(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    setup(instance, state)
    instance.survival_auto_seed_count = 8

    async def unexpected(*args, **kwargs):
        pytest.fail("Exhausted seed budget must stop before reads or mutations")

    monkeypatch.setattr(instance, "call", unexpected)
    assert await instance.extend_survival_frontier(instance.survival_port_candidates, set()) is False


async def test_remaining_backpack_source_returns_without_refunding_the_placed_casing(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    state["items"][9] = stack(ELECTRIC, 2)
    install_reads(instance, state, monkeypatch)
    moves = install_moves(instance, state, monkeypatch)
    await instance.read_position_candidates(points)
    choice = instance.frontier_seed_choices(await instance.frontier_inventory(), points)[key(points[0])][0]
    slot = await instance.prepare_frontier_seed_source(choice)
    placements = install_finite_native_receipts(instance, state, monkeypatch)
    record = {"position": {axis: points[0][axis] for axis in ("x", "y", "z")}, "permanentStructureSeed": True}
    await instance.place(record["position"], slot, "actual_finite_seed", record)
    await instance.release_frontier_seed_source()
    assert state["items"][9]["count"] == 1 and state["items"][slot] is None
    assert state["blocks"][key(points[0])] == ELECTRIC and len(placements) == 1
    assert moves == [(9, slot, "seed_source_retrieve"), (slot, 9, "seed_source_return")]
    assert instance._frontier_seed_borrow is None and instance.material_moves[-1]["restorationConfirmed"]


def test_public_position_schema_requires_exact_capture_and_excludes_inferred_piece_selectors():
    validator = Draft202012Validator(STRUCTURE_SCHEMA)
    valid = {**CONTROLLER, "captureId": "exact-capture", "candidatePositions": [{**CONTROLLER, "group": 0}]}
    validator.validate(valid)
    for invalid in ({key: value for key, value in valid.items() if key != "captureId"}, {**valid, "piece": "main"},
        {**valid, "candidatePositions": [{**CONTROLLER, "group": 0}] * 17}, {**valid, "candidateOffset": 16}):
        assert list(validator.iter_errors(invalid))
