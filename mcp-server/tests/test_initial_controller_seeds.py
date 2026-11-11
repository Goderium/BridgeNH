"""Finite per-layer initial columns, exact movement guards and once-only failures."""

import time

import pytest

from minecraft_codex_bridge import construction_tasks as construction
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import (
    BLUEPRINT, CASING, CONTROLLER, CONTROLLER_ITEM, PORT_ITEMS, declaration,
    key, native_construction, stack, writes,
)
from test_survival_structure_seeds import install_survival_preflight, install_finite_native_receipts
from test_tagged_construction_context import tagged_bridge

FRAME = {"registryName": "example:frame", "metadata": 0}
SHEET = {"registryName": "example:sheet", "metadata": 81}


@pytest.fixture
def tagged_seed_bridge(tagged_bridge):
    """Reuse guarded full-NBT reads with one unrelated tagged cell."""
    client, state = tagged_bridge
    state["items"][0]["hasTag"] = False
    state["items"][40] = None
    state["nbt"] = {13: "{actual_cell_tag:1}"}
    return client, state


def arguments(rows=None, **changes):
    value = declaration(actualSurvival=True, controller={**CONTROLLER, "y": 4},
        initialControllerSeeds=rows or [
            {"offsetY": -2, "item": FRAME.copy(), "playerInventoryIndex": 9},
            {"offsetY": -1, "item": SHEET.copy(), "playerInventoryIndex": 10}], **changes)
    value.pop("supportItem")
    value.pop("supportHotbarSlot")
    return value


def instance(client, state, monkeypatch, args=None):
    task = construction._ConstructionTask(client, args or arguments(), run_workflow, started=time.monotonic())
    install_survival_preflight(task, client, state, monkeypatch)
    state["items"][9] = stack(FRAME, 2)
    state["items"][10] = stack(SHEET, 2)
    state["controller"] = task.args["controller"].copy()
    return task


def native_moves(state, monkeypatch, *, fault=None):
    calls = []

    async def move(self, record, screen, source, destination, phase, *, close_after=False):
        calls.append((phase, source, destination))
        record["movementDispatched"] = True
        state["items"][destination], state["items"][source] = state["items"][source], None
        if fault == "unrelated_count":
            state["items"][0]["count"] -= 1
        elif fault == "cursor":
            state["cursor"] = stack(CASING)
        elif fault == "source_tag":
            state["items"][destination]["hasTag"] = True
        elif fault == "unknown":
            raise BridgeError("timeout", "Uncertain native callback", outcome_unknown=True)

    monkeypatch.setattr(_RecipeTask, "move_native_player_stack", move)
    async def screen(self, phase):
        return "native-player-screen", {index: {"item": state["items"][index]} for index in range(36)}
    monkeypatch.setattr(construction._ConstructionTask, "open_creative_player_inventory", screen)
    workflow = construction._ConstructionTask.workflow
    async def guarded_workflow(self, phase, steps):
        if phase == "inventory_close":
            return {"status": "completed", "steps": []}
        return await workflow(self, phase, steps)
    monkeypatch.setattr(construction._ConstructionTask, "workflow", guarded_workflow)
    return calls


def exact_candidates(task, points, items, monkeypatch, *, fault=None):
    task.capture = {"captureId": "fresh-exact-column"}
    groups = {0: {"id": 0, "descriptionKeys": ["public.column"]}}
    actual = [{**point, "group": 0} for point in points]

    async def read(requested):
        for point, item in zip(requested, items):
            candidate = {**item, "hasTag": False, "nbtIdentity": "", "blockHasTileEntity": False,
                "blockIdentity": item.copy()}
            if fault == "wrong_xyz_item" and point["y"] == points[0]["y"]:
                candidate = {**candidate, **items[-1], "blockIdentity": items[-1].copy()}
            task.survival_position_candidates[key(point)] = {"complete": fault != "partial",
                "detail": {"suggestedCandidates": [candidate]}}

    monkeypatch.setattr(task, "read_position_candidates", read)
    return actual, groups


@pytest.mark.parametrize("change", [
    {"actualSurvival": False}, {"supportItem": CASING, "supportHotbarSlot": 6},
    {"initialControllerSeeds": []},
    {"initialControllerSeeds": [{"offsetY": 0, "item": FRAME, "playerInventoryIndex": 9}]},
    {"initialControllerSeeds": [{"offsetY": True, "item": FRAME, "playerInventoryIndex": 9}]},
    {"initialControllerSeeds": [{"offsetY": -1, "item": FRAME, "playerInventoryIndex": 36}]},
    {"initialControllerSeeds": [{"offsetY": -1, "item": FRAME, "playerInventoryIndex": 9, "count": 1}]},
])
def test_new_schema_refuses_unbounded_mixed_or_nonsurvival_declarations(change):
    assert list(tool_validator("mcb_blueprint_build").iter_errors({**arguments(), **change}))


def test_new_schema_accepts_mixed_backpack_and_same_source_layers():
    assert not list(tool_validator("mcb_blueprint_build").iter_errors(arguments()))
    rows = [{"offsetY": offset, "item": FRAME, "playerInventoryIndex": 9} for offset in (-2, -1)]
    assert not list(tool_validator("mcb_blueprint_build").iter_errors(arguments(rows)))


@pytest.mark.parametrize("fault,reason", [
    ("missing", "initial_seed_column_incomplete"), ("duplicate", "initial_seed_column_incomplete"),
    ("wrong_item", "initial_seed_source_changed"), ("source_tag", "initial_seed_source_changed"),
    ("source_nbt", "initial_seed_inventory_unavailable"), ("insufficient", "initial_seed_material_insufficient"),
    ("overlap", "initial_seed_source_overlap"), ("cursor", "player_inventory_unavailable"),
    ("tall", "initial_seed_column_incomplete"), ("legacy", "initial_seeds_support_conflict"),
])
async def test_preflight_rejects_before_any_write(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    if fault == "missing": task.args["initialControllerSeeds"].pop()
    elif fault == "duplicate": task.args["initialControllerSeeds"][1]["offsetY"] = -2
    elif fault == "wrong_item": state["items"][9]["metadata"] = 1
    elif fault == "source_tag": state["items"][9]["hasTag"] = True
    elif fault == "source_nbt": state["items"][9]["nbtIdentity"] = "null"
    elif fault == "insufficient":
        task.args["initialControllerSeeds"][1].update(item=FRAME.copy(), playerInventoryIndex=9)
        state["items"][9]["count"] = 1
    elif fault == "overlap": task.args["initialControllerSeeds"][0].update(item=BLUEPRINT.copy(), playerInventoryIndex=0)
    elif fault == "cursor": state["cursor"] = stack(CASING)
    elif fault == "tall": task.args["controller"]["y"] = 6
    elif fault == "legacy": task.args.update(supportItem=CASING.copy(), supportHotbarSlot=6)
    with pytest.raises(BridgeError) as error:
        await task.preflight()
    assert error.value.details["reason"] == reason
    assert writes(state) == [] and task.supports == []


async def test_mixed_column_bottom_up_native_once_metadata_preserved_and_public_xyz(tagged_seed_bridge, monkeypatch):
    client, state = tagged_seed_bridge
    task = instance(client, state, monkeypatch)
    state["items"][13] = {**stack({"registryName": "IC2:itemFluidCell", "metadata": 0}), "hasTag": True}
    await task.preflight()
    assert task.floor == 1 and [point["y"] for point in task.survival_seed_plan] == [2, 3]
    moves = native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    assert [row[0] for row in places] == ["survival_structure_seed_2", "survival_structure_seed_3", "controller_place"]
    assert len(moves) == 4 and [record["count"] for record in task.material_moves] == [1, 1]
    assert state["items"][9]["count"] == state["items"][10]["count"] == 1
    assert state["items"][13]["hasTag"] is True
    assert len(task.survival_material_ledger) == 3 and state["items"][1] is None
    assert all(record["publicValidatedBeforePlacement"] is False for record in task.supports)
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, SHEET], monkeypatch)
    await task.bind_survival_seeds(points, groups)
    assert all(record["publicGroupMatched"] is True and record["refunded"] is False for record in task.supports)
    assert state["blocks"][key(task.survival_seed_plan[1])]["metadata"] == 81
    assert all(move["unrelatedTaggedFullNbtProven"] is False for move in task.material_moves)
    await task.cleanup_supports()
    assert all("cleanupAttempted" not in record for record in task.supports)


@pytest.mark.parametrize("fault", ["wrong_xyz_item", "partial"])
async def test_posthint_mismatch_retains_real_seed_without_replay(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, SHEET], monkeypatch, fault=fault)
    with pytest.raises(BridgeError) as error:
        await task.bind_survival_seeds(points, groups)
    assert error.value.details["reason"] == "survival_structure_seed_public_mismatch"
    with pytest.raises(BridgeError) as error:
        await task.place_controller()
    assert error.value.details["reason"] == "initial_seeds_already_attempted"
    assert len(places) == 3 and all(record["refunded"] is False for record in task.supports)


def six_ports(state):
    roles = ["item_input", "item_output", "maintenance", "energy", "fluid_input", "fluid_output"]
    items = {**PORT_ITEMS, "fluid_input": {"registryName": "gregtech:gt.blockmachines", "metadata": 93},
        "fluid_output": {"registryName": "gregtech:gt.blockmachines", "metadata": 63}}
    state["items"][0] = stack(CONTROLLER_ITEM)
    for index, role in enumerate(roles, 1): state["items"][index] = stack(items[role])
    state["items"][7] = stack(BLUEPRINT, 16)
    return [{"role": role, "hotbarSlot": index, **({"ordinaryOutputItem": items[role]} if role == "item_output" else {})}
        for index, role in enumerate(roles, 1)]


async def test_six_ports_full_hotbar_source8_rebinds_borrow_and_restores_only_remainder(tagged_seed_bridge, monkeypatch):
    client, state = tagged_seed_bridge
    rows = [{"offsetY": offset, "item": CASING.copy(), "playerInventoryIndex": 8} for offset in (-2, -1)]
    task = instance(client, state, monkeypatch, arguments(rows, blueprintHotbarSlot=7))
    task.args["requestedPorts"] = six_ports(state)
    state["items"][8] = stack(CASING, 42)
    state["items"][13] = {**stack({"registryName": "IC2:itemFluidCell", "metadata": 0}), "hasTag": True}
    await task.preflight()
    moves = native_moves(state, monkeypatch)
    await task.prepare_inventory()
    reservation = task.borrowed_inventory[0]
    assert reservation["hotbarIndex"] == 8 and task.initial_seed_sources[8]["sourceIndex"] == reservation["storageIndex"]
    assert state["items"][0]["count"] == 1 and task.controller_slot == 0
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    assert len(places) == 3 and len(moves) == 5 and reservation["count"] == 40
    assert reservation["nativeConsumedCount"] == 2 and reservation["survivalMaterialLedgerIndices"] == [0, 1]
    assert state["items"][0] is None and state["items"][8] is None
    # A subsequent native shell receipt provides full server NBT for every
    # unrelated slot, while the fresh client DTO still omits that tagged NBT.
    task.survival_inventory[13]["item"]["nbtIdentity"] = "{actual_cell_tag:1}"
    screen, _ = await task.open_creative_player_inventory("test_restore")
    reservation["restorationAttempted"] = True
    await task.move_native_player_stack(reservation, screen, reservation["storageIndex"], 8, "test_reservation_return")
    reservation.update(restorationConfirmed=True, status="restored")
    assert state["items"][8]["count"] == 40 and state["items"][13]["hasTag"] is True
    points, groups = exact_candidates(task, task.survival_seed_plan, [CASING, CASING], monkeypatch)
    await task.bind_survival_seeds(points, groups)
    assert all(record["sourceReservationLedgerIndex"] == 0 for record in task.supports)


async def test_backpack_controller_is_retrieved_only_after_seed_sources_release(native_construction, monkeypatch):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    state["items"][14], state["items"][1] = state["items"][1], None
    await task.preflight()
    moves = native_moves(state, monkeypatch)
    await task.prepare_inventory()
    assert task.controller_slot == 14 and moves == [] and state["items"][14] is not None
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    assert moves[-1][0] == "initial_controller_retrieve"
    assert len(places) == 3 and state["items"][14] is None


@pytest.mark.parametrize("fault", ["unrelated_count", "cursor", "source_tag", "unknown"])
async def test_uncertain_or_changed_native_borrow_is_never_replayed(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    await task.prepare_inventory()
    calls = native_moves(state, monkeypatch, fault=fault)
    with pytest.raises(BridgeError):
        await task.place_controller()
    assert len(calls) == 1 and task.material_moves[0]["outcomeUnknown"] is True
    with pytest.raises(BridgeError) as error:
        await task.place_controller()
    assert error.value.details["reason"] == "initial_seeds_already_attempted" and len(calls) == 1
    with pytest.raises(BridgeError):
        await task.return_controller_stack("refused_restoration", close_after=True)
    assert len(calls) == 1 and task.survival_material_ledger == []


async def test_second_seed_native_unknown_stops_before_controller_and_keeps_first(native_construction, monkeypatch):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    workflow = task.workflow

    async def uncertain(phase, steps):
        if phase == "survival_structure_seed_3":
            state["items"][task._initial_seed_borrow["temporaryIndex"]]["count"] -= 1
            task.side_effects = True
            raise BridgeError("timeout", "Unknown second seed placement", outcome_unknown=True)
        return await workflow(phase, steps)

    monkeypatch.setattr(task, "workflow", uncertain)
    with pytest.raises(BridgeError): await task.place_controller()
    with pytest.raises(BridgeError): await task.place_controller()
    assert len(places) == 1 and len(task.survival_material_ledger) == 1
    assert task.supports[0]["placementConfirmed"] is True and task.supports[1]["placementAttempted"] is True
    assert state["items"][1]["count"] == 1 and state["blocks"][key(task.survival_seed_plan[0])] == FRAME
    assert task.supports[0]["refunded"] is False


async def test_native_receipt_rejects_unrelated_hidden_tag_nbt_drift(tagged_seed_bridge, monkeypatch):
    client, state = tagged_seed_bridge
    task = instance(client, state, monkeypatch)
    state["items"][13] = {**stack({"registryName": "IC2:itemFluidCell", "metadata": 0}), "hasTag": True}
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()

    def drift(receipt):
        receipt["serverBefore"]["slots"][13]["item"]["nbtIdentity"] = "{fluid:before}"
        receipt["serverAfter"]["slots"][13]["item"]["nbtIdentity"] = "{fluid:after}"

    places = install_finite_native_receipts(task, state, monkeypatch, mutate=drift)
    with pytest.raises(BridgeError): await task.place_controller()
    assert len(places) == 1 and task.survival_material_ledger == []
    assert state["items"][1]["count"] == 1


async def test_controller_top_unreachable_retains_both_confirmed_seeds_without_fallback(native_construction, monkeypatch):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    proved = task.supported_placement_approach

    async def reachable(point, slot, record):
        if point == task.args["controller"]:
            raise BridgeError("no_candidate", "No grounded native body/ray can reach this top face", outcome_unknown=False)
        return await proved(point, slot, record)

    monkeypatch.setattr(task, "supported_placement_approach", reachable)
    with pytest.raises(BridgeError) as error:
        await task.place_controller()
    assert error.value.code == "no_candidate" and len(places) == 2
    assert len(task.survival_material_ledger) == 2 and state["items"][1]["count"] == 1
    assert all(record["placementConfirmed"] is True and record["refunded"] is False for record in task.supports)
    await task.cleanup_supports()
    assert all("cleanupAttempted" not in record for record in task.supports)


async def test_full_hotbar_without_empty_backpack_space_refuses_before_first_move(native_construction, monkeypatch):
    client, state = native_construction
    rows = [{"offsetY": offset, "item": CASING.copy(), "playerInventoryIndex": 8} for offset in (-2, -1)]
    task = instance(client, state, monkeypatch, arguments(rows, blueprintHotbarSlot=7))
    task.args["requestedPorts"] = six_ports(state)
    state["items"][8] = stack(CASING, 42)
    for index in range(9, 36):
        state["items"][index] = stack({"registryName": "example:stored", "metadata": index})
    await task.preflight()
    calls = native_moves(state, monkeypatch)
    with pytest.raises(BridgeError) as error:
        await task.prepare_inventory()
    assert error.value.details["reason"] == "inventory_reservation_unavailable"
    assert calls == [] and task.supports == [] and state["items"][0]["count"] == 1


@pytest.mark.parametrize("fault", ["declared_index", "offset", "move_index_bool", "move_unconfirmed", "source_nbt"])
async def test_public_binding_cannot_adopt_forged_source_or_borrow_record(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = instance(client, state, monkeypatch)
    await task.preflight()
    native_moves(state, monkeypatch)
    await task.prepare_inventory()
    places = install_finite_native_receipts(task, state, monkeypatch)
    await task.place_controller()
    record = task.supports[0]
    if fault == "declared_index": record["declaredPlayerInventoryIndex"] = 0
    elif fault == "offset": record["offsetY"] = -1
    elif fault == "move_index_bool": record["sourceMoveLedgerIndex"] = False
    elif fault == "move_unconfirmed": task.material_moves[0]["movementConfirmed"] = False
    elif fault == "source_nbt": record["sourceNbtIdentity"] = "null"
    points, groups = exact_candidates(task, task.survival_seed_plan, [FRAME, SHEET], monkeypatch)
    with pytest.raises(BridgeError) as error:
        await task.bind_survival_seeds(points, groups)
    assert error.value.details["reason"] == "survival_structure_seed_unproven"
    assert len(places) == 3 and record["refunded"] is False
