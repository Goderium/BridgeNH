"""Public zero-count catalysts retain one actual item without machine/item adapters."""
import copy
import json
from pathlib import Path

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.recipe_tasks import _Unavailable, power_widget, supported_recipe

native_bridge = native.native_bridge


def recorded_cases():
    return json.loads((Path(__file__).parent / "fixtures/physical-catalyst-public-dto.json").read_text(encoding="utf-8"))


@pytest.fixture(params=["mold", "die"])
async def physical_machine(native_bridge, monkeypatch, request):
    client, state = native_bridge
    case = recorded_cases()[request.param]
    recipe = copy.deepcopy(case["query"])
    reference = json.loads((Path(__file__).parent / "fixtures/macerator-public-full.json").read_text(encoding="utf-8"))
    world, gui = copy.deepcopy(reference["world"]["data"]), copy.deepcopy(reference["gui"]["data"])
    identity = lambda item: {key: item[key] for key in ("registryName", "metadata")}
    consumed = recipe["items"]["inputs"]["entries"][0]["stack"]
    catalyst = recipe["items"]["inputs"]["entries"][1]["stack"]
    product = recipe["items"]["outputs"]["entries"][0]["stack"]
    point = case.get("position", {"x": -6, "y": 2, "z": 144})
    for name in ("MACHINE", "INPUT", "OUTPUT"):
        monkeypatch.setitem(native.__dict__, name, point.copy())
    for name, item in (("MATERIAL", consumed), ("CATALYST", catalyst), ("PRODUCT", product)):
        monkeypatch.setitem(native.__dict__, name, identity(item))
    # Samples preserve raw recipes and projected native nonempty stacks. Full
    # empty-slot coverage, actor state and these caller-observed GUI bindings
    # are synthetic mock setup; production still requires actual complete DTOs.
    world["inventory"] = {"slots": [{"index": i, "item": None} for i in range(9)], "totalSlots": 9, "truncated": False}
    for slot, index in zip(gui["slots"][:7], (0, 1, 5, 6, 7, 2, 3)):
        slot["inventoryIndex"] = index
    power = copy.deepcopy(power_widget(gui["controls"], False, allow_public_semantic=True))
    world["gregtech"]["metaTileId"] = 10760 if request.param == "mold" else 1337
    state.update(single_machine=True, single_world=world, single_gui=gui,
        single_meta_id=world["gregtech"]["metaTileId"], single_meta_class=world["gregtech"]["metaClass"],
        recipe=recipe, input_circuit=0, circuit=1, output=0, query_complete=True,
        controls={"widgets": [power]}, single_power_widget_id=power["widgetId"],
        single_catalyst_inventory_index=6, single_output_inventory_index=7)
    yield client, state


def arguments(**changes):
    return native.declaration(**{"inputGuiSlot": 2, "catalystGuiSlot": 3, **changes})


async def test_non_circuit_physical_catalyst_moves_one_real_item_and_retains_actual_identity(physical_machine):
    client, state = physical_machine
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "completed", result
    assert result["recipe"]["catalyst"] == native.CATALYST and native.CATALYST["registryName"] != "gregtech:gt.integrated_circuit"
    assert result["acceptance"]["catalystRetained"] and result["acceptance"]["consumed"] == 1
    assert result["acceptance"]["outputDelta"] == state["recipe"]["items"]["outputs"]["entries"][0]["stack"]["count"]
    assert state["material"] == 31 and state["circuit"] == 0 and state["input_circuit"] == 1
    assert state["input_material"] == 0 and state["cursor"] is None
    assert not state["allowed"] and not state["active"] and state["single_progress"] == state["single_max_progress"] == 0
    assert result["machineScope"]["declaredGuiSlots"] == [{"guiSlot": 2, "inventoryIndex": 5}, {"guiSlot": 3, "inventoryIndex": 6}]
    assert "configuredGhostCircuitRetained" not in result["acceptance"]
    assert len([call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 3]) == 1
    assert not any(call["operation"] == "chat.send" for call in state["calls"])


async def test_existing_non_circuit_catalyst_survives_two_executions_without_another_transfer(physical_machine):
    client, state = physical_machine
    state.update(input_circuit=1, circuit=0)
    result = await native.invoke(client, arguments(executions=2))
    assert result["status"] == "completed" and result["executionsCompleted"] == 2, result
    assert result["acceptance"]["consumed"] == 2 and state["input_circuit"] == 1 and state["input_material"] == 0
    assert state["output"] == result["acceptance"]["outputDelta"]
    assert not any(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 3 for call in state["calls"])


async def test_generated_physical_catalyst_is_one_finite_real_registry_item(physical_machine):
    client, state = physical_machine
    state["circuit"] = 0
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["status"] == "completed" and result["materialGrantsDispatched"] == 1, result
    assert state["input_circuit"] == 1 and state["circuit"] == 0
    assert [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"] == [
        f"/give TestPlayer {native.CATALYST['registryName']} 1 {native.CATALYST['metadata']}"]


@pytest.mark.parametrize("problem", ["bad_input_delta", "bad_catalyst"])
async def test_output_never_proves_completion_without_real_consumption_and_physical_catalyst_retention(physical_machine, problem):
    client, state = physical_machine
    state[problem] = True
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == "input_or_catalyst_acceptance_failed", result
    assert result["emergencyStop"]["serverStopConfirmed"] and not state["allowed"] and result["rolledBack"] is False


async def test_two_existing_physical_catalysts_are_refused_before_material_or_gui_mutation(physical_machine):
    client, state = physical_machine
    state["input_circuit"] = 2
    result = await native.invoke(client, arguments())
    assert result["status"] == "unavailable" and result["reason"] == "catalyst_quantity_unsupported", result
    assert native.writes(state) == []


async def test_non_circuit_destination_must_be_distinct_from_the_real_material_slot(physical_machine):
    client, state = physical_machine
    result = await native.invoke(client, arguments(catalystGuiSlot=2))
    assert result["status"] == "unavailable" and native.writes(state) == [], result
    assert result["reason"] == "no_observed_supported_recipe"
    assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "catalyst_destination_required"


async def test_uncertain_power_never_replays_the_already_staged_physical_catalyst(physical_machine):
    client, state = physical_machine
    state["power_error"] = "disconnect"
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["outcomeUnknown"] and result["emergencyStop"]["serverStopConfirmed"], result
    assert state["input_circuit"] == 1 and state["input_material"] == 1 and state["output"] == 0
    assert len([call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 3]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "gui.widget_action"]) == 1


async def test_generic_physical_catalyst_preserves_existing_multiblock_path(native_bridge, monkeypatch):
    client, state = native_bridge
    catalyst = recorded_cases()["mold"]["query"]["items"]["inputs"]["entries"][1]["stack"]
    monkeypatch.setitem(native.__dict__, "CATALYST", {key: catalyst[key] for key in ("registryName", "metadata")})
    state.update(recipe=native.recipe(wildcard=False), input_circuit=0, circuit=1)
    result = await native.invoke(client, native.declaration())
    assert result["status"] == "completed" and result["acceptance"]["catalystRetained"], result
    assert state["input_circuit"] == 1 and state["input_material"] == 0 and state["output"] == 5 and not state["allowed"]


@pytest.mark.parametrize("name", ["mold", "die"])
def test_recorded_physical_recipe_uses_raw_nonconsumed_identity_and_actual_retained_item(name):
    case = recorded_cases()[name]
    product = case["query"]["items"]["outputs"]["entries"][0]["stack"]
    raw = case["query"]["items"]["inputs"]["entries"][1]["stack"]
    selected = supported_recipe(case["query"], {"outputItem": {key: product[key] for key in ("registryName", "metadata")},
        "inputGuiSlot": 2, "catalystGuiSlot": 3})
    assert selected.catalyst == {key: raw[key] for key in ("registryName", "metadata")} and raw["count"] == 0
    native_frames = case["native"] if name == "mold" else case["data"]
    final = next(frame for frame in native_frames if frame["id"] == "stopped")
    inventory = final.get("data", final.get("values"))["inventory"]
    retained = next(slot["item"] for slot in inventory["slots"] if slot["index"] == 6)
    assert retained["count"] == 1 and all(retained[key] == raw[key] for key in ("registryName", "metadata"))


@pytest.mark.parametrize("problem,reason", [("tagged", "item_nbt_or_identity_unavailable"),
    ("wildcard", "catalyst_type_unsupported"), ("marker", "catalyst_semantics_unavailable"),
    ("extra_catalyst", "complex_recipe_unsupported")])
def test_physical_catalyst_requires_one_complete_concrete_untagged_raw_zero_count(problem, reason):
    recipe = copy.deepcopy(recorded_cases()["mold"]["query"])
    group = recipe["items"]["inputs"]
    if problem == "tagged":
        group["entries"][1]["stack"]["hasTag"] = True
    elif problem == "wildcard":
        group["entries"][1]["stack"]["metadata"] = 32767
    elif problem == "marker":
        group["entries"][1]["nonConsumedCatalyst"] = False
    else:
        group["entries"].append(copy.deepcopy(group["entries"][1]))
        group["total"] = 3
    with pytest.raises(_Unavailable) as error:
        supported_recipe(recipe, {"outputItem": {"registryName": "gregtech:gt.metaitem.01", "metadata": 9951},
            "inputGuiSlot": 2, "catalystGuiSlot": 3})
    assert error.value.details["reason"] == reason


@pytest.mark.parametrize("name", ["mold", "die"])
def test_configured_ghost_still_refuses_every_non_integrated_circuit_catalyst(name):
    recipe = recorded_cases()[name]["query"]
    output = recipe["items"]["outputs"]["entries"][0]["stack"]
    with pytest.raises(_Unavailable) as error:
        supported_recipe(recipe, {"outputItem": {key: output[key] for key in ("registryName", "metadata")},
            "inputGuiSlot": 2, "catalystMode": "configured_ghost"})
    assert error.value.details["reason"] == "configured_ghost_catalyst_type_unsupported"
