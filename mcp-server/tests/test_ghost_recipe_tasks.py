"""Configured virtual circuits use recorded public getters, never physical transfer."""
import copy
import json
from pathlib import Path

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.recipe_tasks import configured_ghost_widget, supported_recipe, _Unavailable

native_bridge = native.native_bridge


def recorded_wire():
    return json.loads((Path(__file__).parent / "fixtures/wire-public-dto.json").read_text(encoding="utf-8"))


@pytest.fixture
async def ghost_machine(native_bridge, monkeypatch):
    client, state = native_bridge
    recorded = recorded_wire()
    recipe = copy.deepcopy(recorded["query"]["data"]["recipes"][0])
    world = copy.deepcopy(next(entry["values"] for entry in recorded["world"] if entry["id"] == "baseline"))
    coordinate = {axis: recorded["query"]["data"]["machine"][axis] for axis in ("x", "y", "z")}
    identity = lambda item: {key: item[key] for key in ("registryName", "metadata")}
    material = identity(recipe["items"]["inputs"]["entries"][0]["stack"])
    catalyst = identity(recipe["items"]["inputs"]["entries"][1]["stack"])
    product = identity(recipe["items"]["outputs"]["entries"][0]["stack"])
    for name in ("MACHINE", "INPUT", "OUTPUT"):
        monkeypatch.setitem(native.__dict__, name, coordinate.copy())
    for name, value in (("MATERIAL", material), ("CATALYST", catalyst), ("PRODUCT", product)):
        monkeypatch.setitem(native.__dict__, name, value)
    # The recorded observation filtered empty slots. Explicit empty entries and
    # the material/output GUI bindings below are synthetic transport fixtures,
    # not claims that the projected sample proved full coverage or GUI roles.
    world["inventory"]["slots"] = [{"index": index, "item": None} for index in range(world["inventory"]["totalSlots"])]
    world["inventory"]["slots"][4]["item"] = native.stack(catalyst, 0)
    gui_slots = [{"slot": 0, "inventoryIndex": 5, "item": None}, {"slot": 1, "inventoryIndex": 7, "item": None}]
    gui_slots += [{"slot": index + 2, "inventoryIndex": index, "item": None} for index in range(36)]
    gui = {"screenId": "screen:input", "windowId": 1, "slots": gui_slots, "totalSlots": len(gui_slots), "truncated": False}
    synchronized = next(entry["values"]["widgets"] for entry in recorded["circuit"] if entry["id"] == "sync")
    power = copy.deepcopy(next(widget for widget in synchronized if widget.get("semantic") == "power_switch"))
    ghost = copy.deepcopy(next(widget for widget in synchronized if widget.get("semantic") == "ghost_circuit"))
    state.update(single_machine=True, configured_ghost_machine=True, single_world=world, single_gui=gui,
        single_meta_id=world["gregtech"]["metaTileId"], single_meta_class=world["gregtech"]["metaClass"], recipe=recipe,
        input_circuit=0, circuit=3, query_complete=True, output=0, controls={"widgets": [power]},
        single_power_widget_id=power["widgetId"], ghost_widget=ghost, single_gui_machine_slot_count=2,
        single_output_inventory_index=7, single_input_gui_slot=0)
    yield client, state


def arguments(**changes):
    args = native.declaration(inputGuiSlot=0, catalystMode="configured_ghost", **changes)
    args.pop("catalystGuiSlot")
    return args


def physical_transfers(state):
    return [call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] < 2]


@pytest.mark.parametrize("include_details", [False, True])
@pytest.mark.parametrize("executions", [1, 2])
async def test_recorded_public_configured_ghost_retains_virtual_circuit_without_borrow_or_click(ghost_machine, include_details, executions):
    client, state = ghost_machine
    result = await native.invoke(client, arguments(includeDetails=include_details, executions=executions))
    assert result["status"] == "completed", result
    acceptance = result["acceptance"]
    assert acceptance["catalystMode"] == "configured_ghost" and acceptance["configuredGhostCircuitRetained"]
    assert acceptance["configuredGhostCircuit"] == {"inventoryIndex": 4, "configuration": 1,
        "item": native.CATALYST, "count": 0, "physicalCircuitTransferred": False, "configurationMutated": False}
    assert acceptance["consumed"] == executions and acceptance["outputDelta"] == 2 * executions
    assert acceptance["catalystRetained"] and acceptance["controllerStopped"]
    assert state["material"] == 32 - executions and state["output"] == 2 * executions and state["input_material"] == 0
    assert state["input_circuit"] == 0 and state["circuit"] == 3 and state["cursor"] is None
    assert not state["allowed"] and not state["active"] and state["single_progress"] == state["single_max_progress"] == 0
    assert all(call["args"]["slot"] == 0 for call in physical_transfers(state))
    assert all(call["args"].get("widgetId") != state["ghost_widget"]["widgetId"] for call in state["calls"] if call["operation"] == "gui.widget_action")
    assert not any(call["operation"] == "chat.send" for call in state["calls"])
    assert result["machineScope"]["declaredGuiSlots"] == [{"guiSlot": 0, "inventoryIndex": 5}]
    assert result["acceptance"]["allRegisteredCoresStopClaimed"] is False


async def test_configured_ghost_generates_only_the_finite_real_material(ghost_machine):
    client, state = ghost_machine
    state["material"] = 0
    state["circuit"] = 0
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["status"] == "completed", result
    assert result["materialGrantsDispatched"] == 1 and state["output"] == 2 and state["input_circuit"] == 0
    assert [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"] == [
        "/give TestPlayer bartworks:gt.bwMetaGeneratedingot 1 10099"]


async def test_configured_ghost_confirms_server_inventory_twice_after_gui_read_before_power(ghost_machine):
    client, state = ghost_machine
    result = await native.invoke(client, arguments())
    assert result["status"] == "completed", result
    phases = [phase for phase in result["phases"] if phase["phase"].startswith("configured_ghost_")
        and "stableServerReads" in phase]
    assert len(phases) == 2
    assert all(phase["serverConfigurationConfirmed"] and phase["stableServerReads"] == 2 for phase in phases)
    calls = state["calls"]
    ghost_reads = [index for index, call in enumerate(calls) if call["operation"] == "gui.widgets"
        and "semantic" not in call["args"]]
    for index in ghost_reads[:2]:
        assert all(call["operation"] == "world.inspect" and call["args"] == {**native.MACHINE, "dimension": 180}
            for call in calls[index + 1:index + 3])


@pytest.mark.parametrize("problem", ["configuration_drift", "machine_replaced"])
async def test_configured_ghost_early_gui_value_cannot_hide_second_server_read_drift(
    ghost_machine, monkeypatch, problem
):
    client, state = ghost_machine
    call = client.call
    ghost_seen = False
    server_reads = 0

    async def changed_server(operation, arguments, **options):
        nonlocal ghost_seen, server_reads
        result = await call(operation, arguments, **options)
        if operation == "gui.widgets" and "semantic" not in arguments:
            ghost_seen = True
        elif operation == "world.inspect" and ghost_seen:
            server_reads += 1
            if server_reads == 2:
                result = copy.deepcopy(result)
                if problem == "configuration_drift":
                    result["inventory"]["slots"][4]["item"]["metadata"] = 2
                else:
                    result["gregtech"]["metaTileId"] += 1
        return result

    monkeypatch.setattr(client, "call", changed_server)
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed", result
    assert result["reason"] == ("configured_ghost_inventory_unavailable" if problem == "configuration_drift"
        else "shared_inventory_machine_identity_changed")
    assert server_reads == 2 and state["input_material"] == state["output"] == 0
    assert not any(call["operation"] in {"chat.send", "gui.slot_click", "gui.widget_action"} for call in state["calls"])


@pytest.mark.parametrize("problem,reason", [
    ("missing_ghost_inventory", "configured_ghost_inventory_unavailable"),
    ("duplicate_ghost_inventory", "configured_ghost_inventory_unavailable"),
    ("physical_count", "configured_ghost_inventory_unavailable"),
    ("unknown_machine", "single_machine_api_unavailable"),
])
async def test_configured_ghost_requires_actual_unique_zero_count_shared_inventory_before_mutations(ghost_machine, problem, reason):
    client, state = ghost_machine
    if problem == "physical_count":
        state["ghost_inventory_count"] = 1
    elif problem == "unknown_machine":
        state["single_meta_class"] = "example.UnknownBasicMachineSubclass"
    else:
        state[problem] = True
    result = await native.invoke(client, arguments())
    if problem == "missing_ghost_inventory":
        assert result["status"] == "failed" and result["reason"] == "configured_ghost_configuration_mismatch", result
        assert not any(call["operation"] in {"chat.send", "gui.slot_click", "gui.widget_action"} for call in state["calls"])
    else:
        assert result["status"] == "unavailable" and result["reason"] == reason, result
        assert native.writes(state) == []


@pytest.mark.parametrize("problem,reason", [
    ("stale_receipt", "configured_ghost_configuration_mismatch"),
    ("missing_ghost_widget", "configured_ghost_widget_ambiguous"),
    ("duplicate_ghost_widget", "configured_ghost_widget_ambiguous"),
    ("ghost_widgets_more", "configured_ghost_widget_coverage_unavailable"),
    ("ghost_widgets_partial", "configured_ghost_widget_coverage_unavailable"),
    ("unknown_getter", "configured_ghost_state_unavailable"),
    ("wrong_widget_api", "configured_ghost_widget_api_unavailable"),
    ("unavailable_getter", "configured_ghost_state_unavailable"),
])
async def test_configured_ghost_rejects_unknown_or_unsynchronized_public_widget_before_material_grants_and_feeding(ghost_machine, problem, reason):
    client, state = ghost_machine
    state["material"] = 0
    if problem == "stale_receipt":
        state["ghost_widget"]["circuitConfig"] = copy.deepcopy(next(entry["values"]["afterCircuitConfig"]
            for entry in recorded_wire()["circuit"] if entry["id"] == "click"))
    elif problem == "unknown_getter":
        state["ghost_widget"]["circuitConfig"]["api"] = "Private.guess()"
    elif problem == "wrong_widget_api":
        state["ghost_widget"]["class"] = "example.UnknownGhostSubclass"
    elif problem == "unavailable_getter":
        state["ghost_widget"]["circuitConfig"]["available"] = False
    else:
        state[problem] = True
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert not any(call["operation"] in {"chat.send", "gui.slot_click", "gui.widget_action"} for call in state["calls"])
    assert state["input_material"] == state["output"] == 0 and not state["allowed"]


@pytest.mark.parametrize("problem,reason", [
    ("configuration_changed", "configured_ghost_configuration_mismatch"),
    ("backing_index_changed", "configured_ghost_inventory_changed"),
    ("bad_input_delta", "input_or_catalyst_acceptance_failed"),
])
async def test_configured_ghost_requires_real_consumption_and_retained_public_configuration_after_output(ghost_machine, problem, reason):
    client, state = ghost_machine
    state[{"configuration_changed": "ghost_after_configuration", "backing_index_changed": "ghost_inventory_after_index",
        "bad_input_delta": "bad_input_delta"}[problem]] = {"configuration_changed": 2, "backing_index_changed": 3, "bad_input_delta": True}[problem]
    result = await native.invoke(client, arguments(includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert result["emergencyStop"]["serverStopConfirmed"] and result["rolledBack"] is False
    assert state["output"] == 2 and not state["allowed"]
    assert result["remainingInputs"]["scope"] == "shared_machine_inventory"


async def test_configured_ghost_material_destination_cannot_point_at_virtual_backing_slot(ghost_machine):
    client, state = ghost_machine
    state["single_gui"]["slots"][0]["inventoryIndex"] = 4
    result = await native.invoke(client, arguments())
    assert result["reason"] == "configured_ghost_destination_overlap", result
    assert not any(call["operation"] in {"gui.slot_click", "chat.send", "gui.widget_action"} for call in state["calls"])


async def test_configured_ghost_unknown_power_is_not_replayed_and_does_not_configure_or_transfer_circuit(ghost_machine):
    client, state = ghost_machine
    state["power_error"] = "disconnect"
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["outcomeUnknown"] and result["emergencyStop"]["serverStopConfirmed"], result
    assert len([call for call in state["calls"] if call["operation"] == "gui.widget_action"]) == 1
    assert state["input_material"] == 1 and state["output"] == 0 and state["input_circuit"] == 0


async def test_configured_ghost_post_power_off_read_failure_never_claims_retained_configuration_or_replays(ghost_machine):
    client, state = ghost_machine
    state["ghost_after_read_error"] = True
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and not result["outcomeUnknown"] and result["emergencyStop"]["serverStopConfirmed"], result
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0]
    assert state["output"] == 2 and state["input_material"] == 0 and not state["allowed"]
    assert "acceptance" not in result


async def test_configured_ghost_cannot_be_requested_for_distinct_multiblock_ports(native_bridge):
    client, state = native_bridge
    args = native.declaration(catalystMode="configured_ghost")
    args.pop("catalystGuiSlot")
    result = await native.invoke(client, args)
    assert result["reason"] == "configured_ghost_machine_required" and state["calls"] == []


async def test_configured_ghost_forbids_any_physical_catalyst_destination(ghost_machine):
    client, state = ghost_machine
    args = arguments()
    args["catalystGuiSlot"] = 1
    result = await native.invoke(client, args)
    assert result["reason"] == "configured_ghost_physical_destination_forbidden" and state["calls"] == []


def test_recorded_ghost_recipe_selects_raw_zero_count_without_a_physical_destination():
    recorded = recorded_wire()
    recipe = recorded["query"]["data"]["recipes"][0]
    selected = supported_recipe(recipe, {"catalystMode": "configured_ghost", "inputGuiSlot": 0,
        "outputItem": {"registryName": "gregtech:gt.blockmachines", "metadata": 32749}})
    assert selected.catalyst == {"registryName": "gregtech:gt.integrated_circuit", "metadata": 1}
    assert selected.material_count == 1 and selected.output_count == 2


@pytest.mark.parametrize("value", [True, "1", None, -1, 2])
def test_public_ghost_getter_does_not_accept_non_integer_or_mismatched_configuration(value):
    recorded = recorded_wire()
    ghost = copy.deepcopy(next(widget for widget in next(entry["values"]["widgets"] for entry in recorded["circuit"]
        if entry["id"] == "sync") if widget.get("semantic") == "ghost_circuit"))
    ghost["circuitConfig"]["value"] = value
    with pytest.raises(_Unavailable) as error:
        configured_ghost_widget({"widgets": [ghost], "hasMore": False, "partial": False}, 1)
    assert error.value.details["reason"] == "configured_ghost_configuration_mismatch"
