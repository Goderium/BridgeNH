"""Already placed seed acceptance uses real tile presence, not type declarations."""

import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from test_construction_tasks import CASING, key, native_construction, stack, writes
from test_survival_structure_seeds import seed_record, task
from test_survival_frontier_connections import install_reads, setup


def prepared_seed(client, state, declaration=True, *, item=None, corrupt=None):
    instance = task(client)
    point, record, groups = seed_record(instance, state)
    if item is not None:
        instance.args["supportItem"] = item.copy()
        record["block"], record["sourceItem"] = item.copy(), item.copy()
        instance.survival_material_ledger[0]["deductions"][0].update(item)
        groups[0]["suggestedCandidates"] = [stack(item)]
        state["blocks"][key(point)] = item.copy()
    original = instance.read_position_candidates

    async def declared(points):
        await original(points)
        for current in points:
            candidates = instance.survival_position_candidates[key(current)]["detail"]["suggestedCandidates"]
            for candidate in candidates:
                if declaration == "missing":
                    candidate.pop("blockHasTileEntity")
                else:
                    candidate["blockHasTileEntity"] = declaration
                if corrupt is not None:
                    corrupt(candidate, instance.survival_position_candidates[key(current)])

    instance.read_position_candidates = declared
    return instance, point, record, groups


@pytest.mark.parametrize("declaration", [False, True])
@pytest.mark.parametrize("item", [CASING, {"registryName": "fixture:tile_capable_casing", "metadata": 7}])
async def test_real_ack_deduction_identity_and_fresh_no_tile_accept_either_typed_declaration(native_construction, declaration, item):
    client, state = native_construction
    instance, point, record, groups = prepared_seed(client, state, declaration, item=item)
    await instance.bind_survival_seeds([point], groups)
    assert record["publicGroupMatched"] is True and record["retained"] is True and record["refunded"] is False
    assert record["block"] == item and record["hasTile"] is False
    assert record["publicBlockTileDeclaration"]["blockHasTileEntity"] is declaration
    assert "does not establish an actual tile" in record["publicBlockTileDeclaration"]["scope"]
    assert record["seedTilePresence"]["present"] is False
    assert record["seedTilePresence"]["source"] == "fresh_integrated_server_complete_world_inspect"
    assert "formationVerified" not in record and instance.formation_verified is False
    assert instance.survival_seed_positions == {key(point)} and writes(state) == []
    reads = [call for call in state["calls"] if call["operation"] == "world.inspect_many"]
    assert len(reads) == 2 and all(call["args"]["positions"] == [record["position"]] for call in reads)


@pytest.mark.parametrize("declaration", ["missing", None, 0, 1, "true", "false", [], {}])
async def test_unknown_or_untyped_public_declaration_cannot_bind_seed(native_construction, declaration):
    client, state = native_construction
    instance, point, record, groups = prepared_seed(client, state, declaration)
    with pytest.raises(BridgeError) as caught:
        await instance.bind_survival_seeds([point], groups)
    assert caught.value.details["reason"] == "survival_structure_seed_public_mismatch"
    assert record["publicGroupMatched"] is False and "seedTilePresence" not in record and writes(state) == []


@pytest.mark.parametrize("declaration", [False, True])
@pytest.mark.parametrize("fault", ["observed_tile", "recorded_tile", "identity_drift"])
async def test_actual_tile_or_changed_world_is_rejected_before_public_binding(native_construction, declaration, fault):
    client, state = native_construction
    instance, point, record, groups = prepared_seed(client, state, declaration)
    if fault == "observed_tile":
        state["blocks"][key(point)]["tileClass"] = "fixture.ActualUnsafeTile"
    elif fault == "recorded_tile":
        record["hasTile"] = True
    else:
        state["blocks"][key(point)]["metadata"] += 1
    with pytest.raises(BridgeError) as caught:
        await instance.bind_survival_seeds([point], groups)
    assert caught.value.details["reason"] == ("survival_structure_seed_unproven" if fault == "recorded_tile" else "survival_structure_seed_changed")
    assert record["publicGroupMatched"] is False and instance.survival_seed_positions == set()
    assert "seedTilePresence" not in record and writes(state) == []


@pytest.mark.parametrize("fault", ["no_ack", "unknown", "missing_ledger", "wrong_deduction", "deduction_nbt", "public_nbt",
    "public_identity", "public_block_identity", "incomplete", "truncated_description"])
async def test_declared_tile_capacity_does_not_bypass_existing_ownership_material_or_public_proof(native_construction, fault):
    client, state = native_construction

    def corrupt(candidate, scoped):
        if fault == "public_nbt":
            candidate["nbtIdentity"] = "{unexpected:1}"
        elif fault == "public_identity":
            candidate["metadata"] += 1
        elif fault == "public_block_identity":
            candidate["blockIdentity"]["metadata"] += 1
        elif fault == "incomplete":
            scoped["complete"] = False

    instance, point, record, groups = prepared_seed(client, state, True, corrupt=corrupt)
    if fault == "no_ack":
        record["serverConsumptionConfirmed"] = False
    elif fault == "unknown":
        record["outcomeUnknown"] = True
    elif fault == "missing_ledger":
        record.pop("survivalMaterialLedgerIndex")
    elif fault == "wrong_deduction":
        instance.survival_material_ledger[0]["deductions"][0]["count"] = 2
    elif fault == "deduction_nbt":
        instance.survival_material_ledger[0]["deductions"][0]["nbtIdentity"] = "{unexpected:1}"
    elif fault == "truncated_description":
        groups[0]["descriptionTruncated"] = True
    with pytest.raises(BridgeError):
        await instance.bind_survival_seeds([point], groups)
    assert record["publicGroupMatched"] is False and "seedTilePresence" not in record and writes(state) == []


async def test_tile_appearing_after_binding_is_still_rejected_on_later_seed_recheck(native_construction):
    client, state = native_construction
    instance, point, record, groups = prepared_seed(client, state, True)
    await instance.bind_survival_seeds([point], groups)
    state["blocks"][key(point)]["tileClass"] = "fixture.LaterUnsafeTile"
    with pytest.raises(BridgeError) as caught:
        await instance.verify_survival_seed_blocks("seed_after_shell")
    assert caught.value.details["reason"] == "survival_structure_seed_changed"
    assert "formationVerified" not in record and writes(state) == []


@pytest.mark.parametrize("declaration", [True, "missing", None, 1, "false"])
async def test_unplaced_frontier_still_refuses_declared_or_unknown_tile_capacity(native_construction, monkeypatch, declaration):
    client, state = native_construction
    instance = task(client)
    points = setup(instance, state)
    install_reads(instance, state, monkeypatch)
    await instance.read_position_candidates(points)
    candidate = instance.survival_position_candidates[key(points[0])]["detail"]["suggestedCandidates"][0]
    if declaration == "missing":
        candidate.pop("blockHasTileEntity")
    else:
        candidate["blockHasTileEntity"] = declaration
    before = copy.deepcopy(state["blocks"])
    choices = instance.frontier_seed_choices(await instance.frontier_inventory(), points)
    assert key(points[0]) not in choices and instance.supports == [] and state["blocks"] == before
    assert writes(state) == []
