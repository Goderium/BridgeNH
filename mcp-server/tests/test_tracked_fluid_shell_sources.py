"""Exact retained source ambiguity and synthetic native ordinary-source guards."""
import copy
import json
from pathlib import Path
from types import SimpleNamespace

import anyio
import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_shared_basic_fluid_tasks as shared
import test_fluid_empty_capacity as capacity
from test_single_recipe_tasks import single_machine
from minecraft_codex_bridge.conveniences import json_sha256
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, _Unavailable, inventory_slots, unique_source, run_recipe_once
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge
FIXTURE = json.loads((Path(__file__).parent / "fixtures/r17-435-tracked-shell-source-ambiguity.json").read_text(encoding="utf-8"))
EMPTY = {"registryName": "IC2:itemCellEmpty", "metadata": 0}


def view(slots, protected, *, gui=False):
    proxy = SimpleNamespace(protected_player_indices=lambda: protected)
    return _RecipeTask.ordinary_source_slots(proxy, slots, gui=gui)


def test_exact_retained_435_only_ordinary_source_is_selected_without_touching_raw_player_evidence():
    player = FIXTURE["actualFailurePlayer"]
    before = copy.deepcopy(player)
    slots = inventory_slots(player, "")
    assert FIXTURE["sourceArtifactSha256"] == "787ea1f4b46a3b678795c12fdd538a28921b1edbe50d578eb609f2c8deb2fbc8"
    result = FIXTURE["actualRecipeResult"]["data"]
    assert result["reason"] == "material_source_missing_or_ambiguous" and result["executionsCompleted"] == 0
    with pytest.raises(_Unavailable): unique_source(slots, EMPTY, 1)
    protected = {row["playerInventoryIndex"] for row in FIXTURE["actualRecipeArguments"]["fluidContainerInputs"]}
    selected = unique_source(view(slots, protected), EMPTY, 1)
    assert protected == {0} and selected["index"] == 1 and selected["item"]["count"] == 1
    assert player == before and len(slots) == 41


def test_no_protected_sources_retains_original_candidate_array_and_true_ambiguity():
    slots = inventory_slots(FIXTURE["actualFailurePlayer"], "")
    assert view(slots, set()) is slots
    with pytest.raises(_Unavailable): unique_source(view(slots, set()), EMPTY, 1)


@pytest.mark.parametrize("protected", [{0}, {0, 9}])
def test_multiple_untracked_stacks_are_still_ambiguous(protected):
    slots = copy.deepcopy(inventory_slots(FIXTURE["actualFailurePlayer"], ""))
    slots[2]["item"] = native.stack(EMPTY, 1)
    with pytest.raises(_Unavailable): unique_source(view(slots, protected), EMPTY, 1)


@pytest.mark.parametrize("changes", [
    {"playerInventoryBindingVerified": False}, {"machineInventoryBindingVerified": True},
    {"inventoryBinding": "guessed"}, {"inventorySource": "some_other_inventory"}, {"playerInventoryIndex": 0.0},
])
def test_gui_candidate_exclusion_requires_exact_public_verified_typed_binding(changes):
    raw = [{"slot": 415, "playerInventoryIndex": 0, "playerInventoryBindingVerified": True,
            "machineInventoryBindingVerified": False, "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot",
            "inventorySource": "current_player_inventory", "item": native.stack(EMPTY, 1)},
           {"slot": 902, "item": native.stack(EMPTY, 1)}]
    assert unique_source(view(raw, {0}, gui=True), EMPTY, 1)["slot"] == 902
    raw[0].update(changes)
    before = copy.deepcopy(raw)
    with pytest.raises(_Unavailable): unique_source(view(raw, {0}, gui=True), EMPTY, 1)
    assert raw == before


def shared_flow(client, state, monkeypatch):
    monkeypatch.setattr(native, "MATERIAL", EMPTY)
    shared.transport(client, state, monkeypatch, output_fluid=False)
    state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"] = 1
    state["recipe"]["fluids"]["inputs"]["entries"][0]["stack"]["amount"] = 1000
    state["player_items"][0]["count"] = 1
    state["player_items"][0]["hasTag"] = False
    state["player_items"][1] = native.stack(EMPTY, 1)
    state["player_items"][9] = None
    original = client.call
    async def call(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.widget_action" and args.get("widgetId") == "named:input":
            state["named_state"]["input"] = ("hydrogen", 1000)
            state["cursor"]["count"] = 1
            state["cursor"]["hasTag"] = False
        return result
    monkeypatch.setattr(client, "call", call)
    capacity.wrapper(client, state, monkeypatch, shared_owner=True)
    args = shared.declaration(output_fluid=False)
    args["fluidContainerInputs"][0].update(count=1, amount=1000)
    args["backendInputs"][0]["count"] = 1
    return args


async def invoke(client, state, args, *, fault=None):
    original = client.call
    state["ordinary_transfer_plans"] = []
    async def call(operation, values, **options):
        result = await original(operation, values, **options)
        if operation == "gui.inspect" and state.get("ordinary_boundary"):
            rows = result["slots"]
            state.setdefault("ordinary_source_raw", copy.deepcopy(rows))
            tracked = next(slot for slot in rows if slot.get("playerInventoryIndex") == 0)
            ordinary = next(slot for slot in rows if slot.get("playerInventoryIndex") == 1)
            if fault == "new_candidate":
                next(slot for slot in rows if slot.get("playerInventoryIndex") == 2)["item"] = native.stack(EMPTY, 1)
            if fault == "source_binding_drift": ordinary["playerInventoryIndex"] = 2
            if fault == "protected_binding_drift": tracked["playerInventoryIndex"] = 3
            if fault == "protected_item_drift": tracked["item"]["count"] = 2
        return result
    client.call = call
    async def runner(tracked, arguments, lock):
        ids = {step["id"] for step in arguments["steps"]}
        if "complete_source_slots" in ids and state["fluid_activations"]:
            state["ordinary_transfer_plans"].append(copy.deepcopy(arguments))
            state["ordinary_boundary"] = True
        return await run_workflow(tracked, arguments, lock)
    try:
        return await run_recipe_once(client, args, anyio.Lock(), runner=runner)
    finally:
        client.call = original


async def test_same_identity_shell_and_ordinary_cell_flow_preserves_shell_and_accepts_all_owner_player_deltas(single_machine, monkeypatch):
    client, state = single_machine
    args = shared_flow(client, state, monkeypatch)
    result = await invoke(client, state, args)
    assert result["status"] == "completed", result
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 1
    assert state["player_items"][0] == {**EMPTY, "count": 1, "hasTag": False}
    assert state["player_items"][1] is None and state["cursor"] is None
    assert state["named_state"] == {"input": (None, 0), "output": (None, 0)}
    assert all(item is None for item in state["vector_input"].values())
    assert result["acceptance"]["completeRecovery"] and result["acceptance"]["playerInventoryConserved"]
    plans = state["ordinary_transfer_plans"]
    assert len(plans) == 1
    before = next(step for step in plans[0]["steps"] if step["id"] == "before")
    assert before["tool"] == "mcb_gui"
    guard = next(step for step in plans[0]["steps"] if step["id"] == "complete_source_slots")
    actual = state["ordinary_source_raw"]
    assert guard["condition"]["operator"] == "json_sha256_equals"
    assert guard["condition"]["value"] == json_sha256(actual)
    assert [slot["playerInventoryIndex"] for slot in actual if isinstance(slot.get("item"), dict)
            and slot["item"].get("registryName") == EMPTY["registryName"]] == [0, 1]
    assert next(step for step in plans[0]["steps"] if step["id"] == "pickup")["args"]["slot"] == 81
    assert sum(step.get("tool") == "mcb_gui" for step in plans[0]["steps"]) == 2


@pytest.mark.parametrize("fault", ["new_candidate", "source_binding_drift", "protected_binding_drift", "protected_item_drift"])
async def test_new_candidate_or_any_bound_entry_drift_at_fresh_before_stops_before_ordinary_pickup(single_machine, monkeypatch, fault):
    client, state = single_machine
    args = shared_flow(client, state, monkeypatch)
    result = await invoke(client, state, args, fault=fault)
    assert result["status"] != "completed" and result["error"]["code"] == "ASSERTION_FAILED", result
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 0 and state["cursor"] is None
    assert not any(row["operation"] == "gui.slot_click" and row["args"]["slot"] == 81 for row in state["calls"])


async def test_true_duplicate_untracked_sources_refuse_before_fluid_activation(single_machine, monkeypatch):
    client, state = single_machine
    args = shared_flow(client, state, monkeypatch)
    state["player_items"][2] = native.stack(EMPTY, 1)
    result = await invoke(client, state, args)
    assert result["reason"] == "material_source_missing_or_ambiguous", result
    assert state["fluid_activations"] == [] and state["vector_power_count"] == 0


@pytest.mark.parametrize("fault", ["missing_tracked_binding", "duplicate_tracked_binding", "protected_player_drift"])
async def test_current_tracked_shell_needs_unique_public_gui_binding_and_conserved_actual_player_stack(single_machine, monkeypatch, fault):
    client, state = single_machine
    args = shared_flow(client, state, monkeypatch)
    original = client.call
    async def call(operation, values, **options):
        result = await original(operation, values, **options)
        if operation == "gui.tooltip" and result["fluidContainer"]["empty"]:
            state["returned_tooltip_seen"] = True
        if operation == "player.interact" and state.get("returned_tooltip_seen"):
            state["ordinary_open_seen"] = True
        if state.get("ordinary_open_seen"):
            if operation == "inventory.inspect" and fault == "protected_player_drift":
                result["slots"][0]["item"]["count"] = 2
            if operation == "gui.inspect":
                tracked = next(slot for slot in result["slots"] if slot.get("playerInventoryIndex") == 0)
                if fault == "missing_tracked_binding": tracked["playerInventoryBindingVerified"] = False
                if fault == "duplicate_tracked_binding":
                    result["slots"].append({**copy.deepcopy(tracked), "slot": max(slot["slot"] for slot in result["slots"]) + 1})
                    result["totalSlots"] += 1
        return result
    monkeypatch.setattr(client, "call", call)
    result = await invoke(client, state, args)
    assert result["status"] != "completed", result
    assert result["reason"] in {"fluid_container_player_binding_unavailable", "fluid_container_player_conservation_failed"}, result
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 0
    assert not any(row["operation"] == "gui.slot_click" and row["args"]["slot"] == 81 for row in state["calls"])


async def test_protected_partial_sixteen_item_feed_keeps_complete_evidence_and_fits_existing_thirty_two_step_budget(single_machine, monkeypatch):
    client, state = single_machine
    args = shared_flow(client, state, monkeypatch)
    state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"] = 16
    state["player_items"][1]["count"] = 17
    args["backendInputs"][0]["count"] = 16
    result = await invoke(client, state, args)
    assert result["status"] == "completed", result
    assert state["player_items"][0] == {**EMPTY, "count": 1, "hasTag": False}
    assert state["player_items"][1] == native.stack(EMPTY, 1)
    plan = state["ordinary_transfer_plans"][0]
    assert len(plan["steps"]) == 32
    assert len([step for step in plan["steps"] if step["id"].startswith("place_")]) == 16
    assert sum(step.get("tool") == "mcb_gui" for step in plan["steps"]) == 2
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 1


@pytest.mark.parametrize("ordinary_count,expected_missing", [(1, 0), (None, 1)])
async def test_material_preparation_count_uses_untracked_candidates_without_rewriting_player_dto(ordinary_count, expected_missing):
    player = copy.deepcopy(FIXTURE["actualFailurePlayer"])
    player["slots"][1]["item"] = native.stack(EMPTY, ordinary_count) if ordinary_count is not None else None
    raw = copy.deepcopy(player)
    async def call(operation, args):
        assert operation == "inventory.inspect" and args == {}
        return player
    async def snapshot():
        return {"player": {"name": "TestPlayer"}}
    proxy = SimpleNamespace(call=call, snapshot=snapshot, args={"generateMaterials": True},
                            protected_player_indices=lambda: {0})
    proxy.ordinary_source_slots = lambda slots: _RecipeTask.ordinary_source_slots(proxy, slots)
    missing = await _RecipeTask.ensure_materials(proxy, [(EMPTY, 1)], grant=False)
    assert missing == expected_missing and player == raw


async def test_no_fluid_multi_flow_keeps_existing_native_transfer_plan_without_protected_snapshot_guard(single_machine, monkeypatch):
    client, state = single_machine
    multi.vector_transport(client, state, monkeypatch, consumed=1, outputs=1, catalysts=1)
    plans = []
    async def runner(tracked, arguments, lock):
        plans.append(copy.deepcopy(arguments))
        return await run_workflow(tracked, arguments, lock)
    result = await run_recipe_once(client, multi.arguments(consumed=1, outputs=1, catalysts=1,
        input=native.MACHINE.copy(), output=native.MACHINE.copy()), anyio.Lock(), runner=runner)
    assert result["status"] == "completed", result
    assert all(step["id"] != "complete_source_slots" for plan in plans for step in plan["steps"])
