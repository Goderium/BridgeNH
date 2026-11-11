"""Common single-block API tasks use recorded public layouts and actual native deltas."""

import copy
import json
from pathlib import Path

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.recipe_tasks import _Unavailable, power_widget, supported_recipe


native_bridge = native.native_bridge


@pytest.fixture
async def single_machine(native_bridge, monkeypatch):
    client, state = native_bridge
    recorded = json.loads((Path(__file__).parent / "fixtures" / "macerator-public-full.json").read_text(encoding="utf-8"))
    world, gui = recorded["world"]["data"], recorded["gui"]["data"]
    candidate = next(recipe for recipe in recorded["recipe"]["data"]["recipes"]
                     if recipe["items"]["inputs"]["entries"][0]["stack"]["registryName"] == "minecraft:pumpkin")
    identity = lambda item: {key: item[key] for key in ("registryName", "metadata")}
    material = identity(candidate["items"]["inputs"]["entries"][0]["stack"])
    product = identity(candidate["items"]["outputs"]["entries"][0]["stack"])
    coordinate = {axis: world[axis] for axis in ("x", "y", "z")}
    for name in ("MACHINE", "INPUT", "OUTPUT"):
        monkeypatch.setitem(native.__dict__, name, coordinate.copy())
    monkeypatch.setitem(native.__dict__, "MATERIAL", material)
    monkeypatch.setitem(native.__dict__, "PRODUCT", product)
    power = power_widget(gui["controls"], False, allow_public_semantic=True)
    state.update(single_machine=True, single_world=world, single_gui=gui, single_meta_id=world["gregtech"]["metaTileId"],
                 single_meta_class=world["gregtech"]["metaClass"], recipe=candidate, input_circuit=0, query_complete=True,
                 controls={"widgets": [copy.deepcopy(power)]}, single_power_widget_id=power["widgetId"])
    yield client, state


def arguments(**changes):
    return native.declaration(inputGuiSlot=2, **changes)


@pytest.mark.parametrize("include_details", [False, True])
async def test_recorded_common_single_machine_uses_shared_inventory_and_declared_gui_binding(single_machine, include_details):
    client, state = single_machine
    result = await native.invoke(client, arguments(includeDetails=include_details))
    assert result["status"] == "completed", result
    scope, acceptance = result["machineScope"], result["acceptance"]
    assert scope["kind"] == "single_block" and scope["commonBasicMachineAPIVerified"] is True
    assert scope["declaredGuiSlots"] == [{"guiSlot": 2, "inventoryIndex": 5}]
    assert scope["structureFormationClaimed"] is scope["registeredCoreStopClaimed"] is scope["inputRoleAutomaticallyDiscovered"] is False
    assert acceptance["inventoryScope"] == "complete_shared_machine_inventory"
    assert acceptance["consumed"] == 1 and acceptance["outputDelta"] == 4
    assert acceptance["controllerStopped"] is True and acceptance["catalystRetained"] is False
    assert acceptance["structureFormationClaimed"] is acceptance["allRegisteredCoresStopClaimed"] is False
    assert "exposedRegisteredCoresStopped" not in acceptance and "structureFormed" not in acceptance
    assert acceptance["registeredCoreStopCoverage"] == "not_applicable_single_block"
    assert state["material"] == 31 and state["input_material"] == 0 and state["output"] == 8
    assert not state["allowed"] and not state["active"] and state["single_progress"] == state["single_max_progress"] == 0
    assert state["cursor"] is None and result["inventoryPreparation"]["finalCursorEmpty"] is True
    assert result["inventoryRestorationRequested"] is False and result["retainedInventoryMoves"] == []
    power_calls = [(index, call) for index, call in enumerate(state["calls"])
                   if call["operation"] == "gui.widget_action" and call["args"].get("action") == "set_selected"]
    assert [call["args"]["selected"] for _, call in power_calls] == [1, 0]
    assert sum(call["operation"] == "world.inspect" for call in state["calls"][power_calls[0][0] + 1:power_calls[1][0]]) == 2
    assert not any(call["operation"] == "machine.set_working" for call in state["calls"])
    clicks = [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"]
    assert [click["slot"] for click in clicks] == [16, 2, 16]
    assert len(state["queries"]) == 1 and state["queries"][0]["output"]["registryName"] == "minecraft:pumpkin_seeds"


async def test_single_machine_two_finite_executions_preserve_existing_output_baseline(single_machine):
    client, state = single_machine
    result = await native.invoke(client, arguments(executions=2))
    assert result["status"] == "completed" and result["executionsCompleted"] == 2, result
    assert result["acceptance"]["consumed"] == 2 and result["acceptance"]["outputDelta"] == 8
    assert state["material"] == 30 and state["input_material"] == 0 and state["output"] == 12
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"
            and call["args"].get("action") == "set_selected"] == [1, 0, 1, 0]


async def test_single_machine_default_keeps_native_empty_hand_preparation_after_acceptance(single_machine):
    client, state = single_machine
    state["full_hotbar"] = True
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "completed", result
    assert result["inventoryRestorationRequested"] is False and result["pendingInventoryRestoration"] == []
    assert len(result["retainedInventoryMoves"]) == 1 and result["retainedInventoryMoves"][0]["retentionConfirmed"] is True
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is False
    assert state["player_items"][0] is None and state["player_items"][10]["registryName"] == "minecraft:stone"
    assert sum(call["operation"] == "gui.open_inventory" for call in state["calls"]) == 1


async def test_single_machine_explicit_material_generation_still_uses_one_finite_native_grant(single_machine):
    client, state = single_machine
    state["material"] = 0
    result = await native.invoke(client, arguments(generateMaterials=True, includeDetails=False))
    assert result["status"] == "completed" and result["materialGrantsDispatched"] == 1, result
    assert state["material"] == state["input_material"] == 0 and state["output"] == 8 and state["cursor"] is None
    grant = next(call for call in state["calls"] if call["operation"] == "chat.send")
    assert grant["args"]["message"] == "/give TestPlayer minecraft:pumpkin 1 0"


@pytest.mark.parametrize("problem,reason", [
    ("unknown_api", "single_machine_api_unavailable"), ("missing_progress", "stopped_single_machine_required"),
    ("unfinished_progress", "stopped_single_machine_required"), ("enabled", "stopped_single_machine_required"),
    ("foreign_inventory", "input_not_isolated"), ("partial_inventory", "inventory_coverage_incomplete"),
    ("existing_fluid", "single_machine_fluid_isolation_unavailable"), ("missing_tanks", "single_machine_fluid_isolation_unavailable"),
    ("dynamic_map", "UNSUPPORTED"), ("identity_overlap", "no_observed_supported_recipe"),
])
async def test_single_machine_unknown_api_data_map_or_isolation_never_leads_to_native_mutations(single_machine, problem, reason):
    client, state = single_machine
    request = arguments()
    if problem == "unknown_api":
        state["single_meta_class"] = "example.UnknownMachine"
    elif problem == "missing_progress":
        state["single_missing_progress"] = True
    elif problem == "unfinished_progress":
        state["single_progress"] = 1
    elif problem == "enabled":
        state["allowed"] = True
    elif problem == "foreign_inventory":
        state["single_foreign_item"] = True
    elif problem == "partial_inventory":
        state["single_partial_inventory"] = True
    elif problem == "existing_fluid":
        state["single_nonempty_tank"] = True
    elif problem == "missing_tanks":
        state["single_missing_tanks"] = True
    elif problem == "dynamic_map":
        state["single_map_unsupported"] = True
    else:
        state["recipe"]["items"]["outputs"]["entries"][0]["stack"] = native.stack(native.MATERIAL, 1)
        request["outputItem"] = native.MATERIAL
    result = await native.invoke(client, request)
    assert result["status"] == "unavailable" and result["reason"] == reason, result
    assert native.writes(state) == [] and result["sideEffectsPossible"] is False
    assert result["machineScope"]["structureFormationClaimed"] is False
    if problem == "identity_overlap":
        assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "shared_inventory_recipe_identity_overlap"


@pytest.mark.parametrize("problem,reason", [
    ("invalid_gui_index", "single_machine_gui_declaration_unavailable"),
    ("changed_gui_mapping", "single_machine_gui_declaration_changed"),
    ("changed_machine", "component_controller_identity_changed"),
])
async def test_single_machine_fresh_declared_gui_and_api_identity_are_verified_before_unsafe_power(single_machine, problem, reason):
    client, state = single_machine
    state[{"invalid_gui_index": "single_invalid_gui_index", "changed_gui_mapping": "single_gui_mapping_changed",
           "changed_machine": "changed_class_after_feed"}[problem]] = True
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert not any(call["operation"] == "gui.widget_action" and call["args"].get("action") == "set_selected" for call in state["calls"])
    if problem != "changed_machine":
        assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
        assert state["material"] == 32 and state["input_material"] == 0
    else:
        assert state["material"] == 31 and state["input_material"] == 1


async def test_single_machine_unknown_power_is_not_replayed_and_uses_original_identity_stop_guard(single_machine):
    client, state = single_machine
    state["power_error"] = "disconnect"
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True and result["failedPhase"] == "execute_1", result
    assert result["lastActionReceipt"]["operation"] == "gui.widget_action" and result["lastActionReceipt"]["outcomeUnknown"] is True
    assert result["emergencyStop"]["serverStopConfirmed"] is True and not state["allowed"]
    assert result["remainingInputs"]["available"] is True and result["remainingInputs"]["scope"] == "shared_machine_inventory"
    assert state["input_material"] == 1 and state["output"] == 4
    power = [call for call in state["calls"] if call["operation"] == "gui.widget_action" and call["args"].get("action") == "set_selected"]
    stops = [call for call in state["calls"] if call["operation"] == "machine.set_working"]
    assert len(power) == len(stops) == 1 and stops[0]["args"]["expectedMetaTileId"] == state["single_meta_id"]


@pytest.mark.parametrize("problem", ["bad_input_delta", "bad_catalyst"])
async def test_single_machine_output_is_not_acceptance_without_consumption_and_catalyst(single_machine, problem):
    client, state = single_machine
    state["recipe"]["items"]["inputs"]["entries"].append({"index": 1, "stack": native.stack(native.CATALYST, 0),
        "chance": 10000, "nonConsumedCatalyst": True})
    state["recipe"]["items"]["inputs"]["total"] = 2
    state["input_circuit"] = 1
    state[problem] = True
    result = await native.invoke(client, arguments(catalystGuiSlot=0, includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == "input_or_catalyst_acceptance_failed", result
    assert state["output"] == 8 and result["emergencyStop"]["serverStopConfirmed"] is True
    assert not state["allowed"] and result["rolledBack"] is False


async def test_single_machine_success_retains_the_actual_non_consumed_catalyst(single_machine):
    client, state = single_machine
    state["recipe"]["items"]["inputs"]["entries"].append({"index": 1, "stack": native.stack(native.CATALYST, 0),
        "chance": 10000, "nonConsumedCatalyst": True})
    state["recipe"]["items"]["inputs"]["total"] = 2
    state["input_circuit"] = 1
    result = await native.invoke(client, arguments(catalystGuiSlot=0, includeDetails=False))
    assert result["status"] == "completed" and result["acceptance"]["catalystRetained"] is True, result
    assert state["input_circuit"] == 1 and state["input_material"] == 0 and state["output"] == 8


async def test_single_machine_unknown_new_shared_inventory_contents_cannot_pass_acceptance(single_machine):
    client, state = single_machine
    state["single_foreign_after_execution"] = True
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == "shared_inventory_acceptance_failed", result
    assert state["output"] == 8 and state["input_material"] == 0 and state["single_foreign_item"] is True
    assert result["emergencyStop"]["serverStopConfirmed"] is True and not state["allowed"]


async def test_single_machine_real_final_cursor_must_be_empty_even_after_recipe_acceptance(single_machine):
    client, state = single_machine
    state["cursor_after_execution"] = True
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == "retained_inventory_cursor_not_empty", result
    assert result["recipeAcceptanceCompleted"] is True and result["acceptance"]["outputDelta"] == 4
    assert result["inventoryPreparation"]["finalCursorEmpty"] is False and result["inventoryPreparation"]["finalInventoryVerified"] is False
    assert state["output"] == 8 and state["input_material"] == 0 and not state["allowed"]


def test_recorded_recipe_requires_no_per_machine_recipe_adaptation():
    recorded = json.loads((Path(__file__).parent / "fixtures" / "macerator-public-full.json").read_text(encoding="utf-8"))
    candidate = next(recipe for recipe in recorded["recipe"]["data"]["recipes"]
                     if recipe["items"]["inputs"]["entries"][0]["stack"]["registryName"] == "minecraft:pumpkin")
    selected = supported_recipe(candidate, {"material": {"registryName": "minecraft:pumpkin", "metadata": 0},
                                           "outputItem": {"registryName": "minecraft:pumpkin_seeds", "metadata": 0}})
    assert selected.material_count == 1 and selected.output_count == 4 and selected.raw_eut == 2 and selected.raw_duration_ticks == 400


@pytest.mark.parametrize("problem", ["no_source", "unknown_source", "conflicting_overlay", "state_mismatch"])
def test_recorded_shared_gt_toggle_requires_explicit_public_semantics_and_authoritative_state(problem):
    recorded = json.loads((Path(__file__).parent / "fixtures" / "macerator-public-full.json").read_text(encoding="utf-8"))
    widget = copy.deepcopy(next(widget for widget in recorded["gui"]["data"]["controls"]["widgets"]
                                if widget.get("semantic") == "power_switch"))
    assert power_widget({"widgets": [widget]}, False, allow_public_semantic=True)["widgetId"] == widget["widgetId"]
    if problem == "no_source":
        del widget["semanticSource"]
    elif problem == "unknown_source":
        widget["semanticSource"] = "unverified tooltip guess"
    elif problem == "conflicting_overlay":
        widget["overlay"] = {"current": {"location": "gregtech:textures/gui/overlay_button/power_switch_on.png"}}
    else:
        widget["selected"] = 1
    with pytest.raises(_Unavailable) as error:
        power_widget({"widgets": [widget]}, False, allow_public_semantic=True)
    assert error.value.details["reason"] == ("power_state_not_synchronized" if problem == "state_mismatch" else "power_semantics_unverified")
