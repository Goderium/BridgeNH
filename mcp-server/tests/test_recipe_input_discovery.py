"""Optional input slots require public actual owner/handler/index/role proof."""

import copy

import pytest

import test_recipe_tasks as native
import test_single_recipe_tasks as single
from minecraft_codex_bridge.recipe_tasks import MACHINE_SLOT_BINDING, RECIPE_ONCE_SCHEMA, _Unavailable, supported_recipe

native_bridge = native.native_bridge
single_machine = single.single_machine


def automatic_arguments(**changes):
    arguments = native.declaration(**changes)
    arguments.pop("inputGuiSlot")
    return arguments


def public_bindings(state, *, mappings=None):
    shared = state.get("single_machine", False)
    owner = {**native.INPUT, "dimension": 180, "registryName": "gregtech:gt.blockmachines",
        "metaTileId": state["single_meta_id"] if shared else 77,
        "metaClass": state["single_meta_class"] if shared else "gregtech.api.metatileentity.implementations.MTEHatchInputBus"}
    mappings = ({2: 5} if shared else {0: 0, 1: 1, 3: 2}) if mappings is None else mappings

    def world_binding(data, args):
        if all(args[axis] == native.INPUT[axis] for axis in ("x", "y", "z")):
            data.update(source="integrated_server", **{key: owner[key] for key in ("x", "y", "z", "dimension", "registryName")})
            data["gregtech"] = {**data.get("gregtech", {}), "metaTileId": owner["metaTileId"], "metaClass": owner["metaClass"]}

    def gui_binding(data):
        if state["open"] != "input":
            return
        for slot in data.get("slots", []):
            slot["machineInventoryBindingVerified"] = False
            if slot["slot"] in mappings:
                slot.update(machineInventoryBindingVerified=True, machineInventoryBinding=MACHINE_SLOT_BINDING,
                    machineInventoryIndex=mappings[slot["slot"]], machineInventoryOwner=copy.deepcopy(owner),
                    machineInventoryRole="consumed_input", consumedInput=True, playerInventoryBindingVerified=False)

    state["world_observation_hook"] = world_binding
    state["gui_observation_hook"] = gui_binding
    return owner, gui_binding


@pytest.mark.parametrize("details", [False, True])
async def test_input_bus_chooses_lowest_empty_public_index_and_keeps_one_native_input_open(native_bridge, details):
    client, state = native_bridge
    owner, _ = public_bindings(state)
    result = await native.invoke(client, automatic_arguments(includeDetails=details))
    assert result["status"] == "completed", result
    assert result["inputGuiSlot"] == 1 and result["inputGuiSlotSource"] == "public_machine_input_binding"
    assert result["inputGuiBinding"] == {"guiSlot": 1, "inventoryIndex": 1, "owner": owner,
        "binding": MACHINE_SLOT_BINDING, "role": "consumed_input", "selection": "lowest_empty_consumed_input_index"}
    assert result["acceptance"]["consumed"] == 8 and result["acceptance"]["outputDelta"] == 1
    assert result["materialGenerationRequested"] is result["generatedMaterials"] is False
    assert not any(call["operation"] == "chat.send" for call in state["calls"])
    opens = [call for call in state["calls"] if call["operation"] == "player.interact"
        and all(call["args"][axis] == native.INPUT[axis] for axis in ("x", "y", "z"))]
    assert len(opens) == 1 and state["cursor"] is None
    assert ("phases" in result) is details


async def test_synthetic_public_basic_machine_binding_uses_handler_index_instead_of_shadow_local_index(single_machine):
    # The injected publisher tests the common public DTO contract; the recorded
    # MUI1 layout itself cannot produce this owner proof in current GT .183.
    client, state = single_machine
    owner, bind = public_bindings(state)

    def shadow_index(data):
        bind(data)
        for slot in data.get("slots", []):
            if slot["slot"] == 2:
                slot["inventoryIndex"] = 999

    state["gui_observation_hook"] = shadow_index
    result = await native.invoke(client, automatic_arguments(includeDetails=False))
    assert result["status"] == "completed", result
    assert result["inputGuiSlot"] == 2 and result["inputGuiBinding"]["inventoryIndex"] == 5
    assert result["inputGuiBinding"]["owner"] == owner
    assert result["machineScope"]["inputRoleAutomaticallyDiscovered"] is True
    assert result["machineScope"]["declaredGuiSlots"] == [{"guiSlot": 2, "inventoryIndex": 5}]
    assert result["acceptance"]["inputRoleAutomaticallyDiscovered"] is True
    assert result["acceptance"]["consumed"] == 1 and result["acceptance"]["outputDelta"] == 4


async def test_recorded_mui1_single_machine_has_no_automatic_owner_binding_and_keeps_explicit_path(single_machine):
    client, state = single_machine
    public_bindings(state, mappings={})
    unavailable = await native.invoke(client, automatic_arguments())
    assert unavailable["available"] is False and unavailable["reason"] == "input_gui_binding_unavailable", unavailable
    assert unavailable["inputGuiBinding"] is None
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
    result = await native.invoke(client, single.arguments())
    assert result["status"] == "completed" and result["inputGuiSlotSource"] == "caller", result


async def test_optional_material_slot_keeps_explicit_physical_catalyst_destination_separate(native_bridge):
    client, state = native_bridge
    public_bindings(state)
    state["input_circuit"], state["circuit"] = 0, 1
    result = await native.invoke(client, automatic_arguments())
    assert result["status"] == "completed" and result["inputGuiSlot"] == 1, result
    assert result["acceptance"]["catalystRetained"] and state["input_circuit"] == 1
    assert state["input_material"] == 0 and state["cursor"] is None


async def test_automatic_binding_preserves_sixteen_right_click_transfer_budget(single_machine):
    client, state = single_machine
    public_bindings(state)
    state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"] = 16
    result = await native.invoke(client, automatic_arguments())
    assert result["status"] == "completed" and result["acceptance"]["consumed"] == 16, result
    assert sum(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 2
        and call["args"]["button"] == 1 for call in state["calls"]) == 16


@pytest.mark.parametrize("fault", ["unverified", "missing_binding", "wrong_binding", "wrong_owner", "owner_boolean",
    "missing_owner", "player", "unknown_role", "insertion_disabled", "missing_index", "boolean_index", "outside_index"])
async def test_missing_or_untrusted_public_mapping_never_pickups_grants_or_powers(native_bridge, fault):
    client, state = native_bridge
    _, bind = public_bindings(state, mappings={1: 1})

    def unsupported(data):
        bind(data)
        if state["open"] != "input" or not data.get("slots"):
            return
        destination = data["slots"][1]
        if fault == "unverified":
            destination["machineInventoryBindingVerified"] = False
        elif fault == "missing_binding":
            destination.pop("machineInventoryBinding")
        elif fault == "wrong_binding":
            destination["machineInventoryBinding"] = "class_offset_guess"
        elif fault == "wrong_owner":
            destination["machineInventoryOwner"]["x"] += 1
        elif fault == "owner_boolean":
            destination["machineInventoryOwner"]["dimension"] = True
        elif fault == "missing_owner":
            destination.pop("machineInventoryOwner")
        elif fault == "player":
            destination["playerInventoryBindingVerified"] = True
        elif fault == "unknown_role":
            destination["machineInventoryRole"] = "unavailable"
        elif fault == "insertion_disabled":
            destination["consumedInput"] = False
        elif fault == "missing_index":
            destination.pop("machineInventoryIndex")
        elif fault == "boolean_index":
            destination["machineInventoryIndex"] = True
        else:
            destination["machineInventoryIndex"] = 999

    state["gui_observation_hook"] = unsupported
    result = await native.invoke(client, automatic_arguments(generateMaterials=True))
    assert result["available"] is False and result["reason"] == "input_gui_binding_unavailable", result
    assert result["inputGuiSlotSource"] == "automatic_unresolved" and result["inputGuiBinding"] is None
    assert result["materialGrantsDispatched"] == 0
    assert not any(call["operation"] in ("gui.slot_click", "gui.widget_action", "machine.set_working") for call in state["calls"])


@pytest.mark.parametrize("mirror_role", ["consumed_input", "other"])
async def test_duplicate_actual_index_is_rejected_even_when_native_slot_numbers_are_distinct(native_bridge, mirror_role):
    client, state = native_bridge
    _, bind = public_bindings(state, mappings={1: 1, 3: 1})

    def mirrored(data):
        bind(data)
        if state["open"] == "input" and data.get("slots"):
            data["slots"][3].update(machineInventoryRole=mirror_role, consumedInput=mirror_role == "consumed_input")

    state["gui_observation_hook"] = mirrored
    result = await native.invoke(client, automatic_arguments())
    assert result["reason"] == "input_gui_binding_ambiguous" and result["available"] is False, result
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])


@pytest.mark.parametrize("phase", ["source_sync", "immediately_before_pickup"])
@pytest.mark.parametrize("drift", ["owner", "index", "role", "native_slot", "insertion", "coverage"])
async def test_public_binding_drift_is_rechecked_before_native_pickup_without_replay(native_bridge, phase, drift):
    client, state = native_bridge
    _, bind = public_bindings(state, mappings={1: 1})
    input_reads = 0

    def drifted(data):
        nonlocal input_reads
        bind(data)
        if state["open"] != "input" or not data.get("slots"):
            return
        input_reads += 1
        # Native open, fresh own-GUI reuse, source synchronization, then the
        # transfer workflow's immediate pre-pickup read are separate observations.
        if input_reads != (3 if phase == "source_sync" else 4):
            return
        slot = data["slots"][1]
        if drift == "owner":
            slot["machineInventoryOwner"]["metaTileId"] += 1
        elif drift == "index":
            slot["machineInventoryIndex"] = 2
        elif drift == "role":
            slot["machineInventoryRole"] = "other"
        elif drift == "native_slot":
            slot["slot"] = 101
        elif drift == "insertion":
            slot["consumedInput"] = False
        else:
            data["truncated"] = True

    state["gui_observation_hook"] = drifted
    result = await native.invoke(client, automatic_arguments())
    assert result["available"] is False, result
    assert result["reason"] in ("input_gui_binding_changed", "ASSERTION_FAILED", "inventory_coverage_incomplete", "declared_destination_not_empty"), result
    assert result["inputGuiBinding"]["inventoryIndex"] == 1
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
    assert not state["allowed"] and state["cursor"] is None


@pytest.mark.parametrize("fault", ["source", "coordinate", "dimension", "registry", "meta_id", "meta_class"])
async def test_automatic_mapping_requires_actual_authoritative_server_owner_before_writes(native_bridge, fault):
    client, state = native_bridge
    public_bindings(state)
    bind_world = state["world_observation_hook"]

    def bad_world(data, args):
        bind_world(data, args)
        if all(args[axis] == native.INPUT[axis] for axis in ("x", "y", "z")):
            if fault == "source":
                data["source"] = "client"
            elif fault == "coordinate":
                data["x"] += 1
            elif fault == "dimension":
                data["dimension"] = True
            elif fault == "registry":
                data["registryName"] = "unknown"
            elif fault == "meta_id":
                data["gregtech"]["metaTileId"] = True
            else:
                data["gregtech"].pop("metaClass")

    state["world_observation_hook"] = bad_world
    result = await native.invoke(client, automatic_arguments())
    assert result["status"] == "unavailable" and result["reason"] == "input_gui_owner_unavailable", result
    assert not native.writes(state)


async def test_explicit_input_slot_remains_supported_without_new_public_binding_data(native_bridge):
    client, state = native_bridge
    result = await native.invoke(client, native.declaration())
    assert result["status"] == "completed" and result["inputGuiSlot"] == 1, result
    assert result["inputGuiSlotSource"] == "caller" and result["inputGuiBinding"] is None


def test_optional_physical_catalyst_is_deferred_to_actual_gui_binding_and_generation_remains_false():
    arguments = automatic_arguments()
    assert supported_recipe(native.recipe(), arguments).catalyst == native.CATALYST
    arguments.pop("catalystGuiSlot")
    assert supported_recipe(native.recipe(), arguments).catalyst == native.CATALYST
    assert "inputGuiSlot" not in RECIPE_ONCE_SCHEMA["required"]
    supply_schema = RECIPE_ONCE_SCHEMA["properties"]["generateMaterials"]
    assert supply_schema["type"] == "boolean" and supply_schema["default"] is False
    assert "fresh matching client/server inventory-container acknowledgement" in supply_schema["description"]
