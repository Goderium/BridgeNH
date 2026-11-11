"""Retained ghost switches require the public backing index, owner and native GUI proof."""

import copy
import json

import httpx
import pytest

import test_recipe_tasks as native
from test_ghost_recipe_tasks import arguments, ghost_machine
from test_recipe_input_discovery import public_bindings
from test_single_runtime_api import runtime_proof
from minecraft_codex_bridge.recipe_tasks import CIRCUIT_SUPPORT_API


native_bridge = native.native_bridge


def configuration_actions(state):
    return [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("widgetId") == state["ghost_widget"]["widgetId"]]


def no_supply(state):
    assert not any(call["operation"] in {"chat.send", "gui.slot_click"}
        or call["operation"] == "gui.widget_action" and call["args"].get("selected") == 1
        for call in state["calls"])
    assert state["input_material"] == state["output"] == 0


def target(state, value):
    native.CATALYST["metadata"] = value
    state["recipe"]["items"]["inputs"]["entries"][1]["stack"]["metadata"] = value


@pytest.fixture
async def switch_machine(ghost_machine, monkeypatch):
    client, state = ghost_machine
    owner, gui_binding = public_bindings(state, mappings={0: 5, 1: 7})
    world_binding = state["world_observation_hook"]
    state.update(circuit_public_index=4, circuit_server_index=4)
    state["ghost_widget"]["supportedActions"] = ["activate", "set_value"]

    def bind_world(data, args):
        world_binding(data, args)
        slots = data["inventory"]["slots"]
        circuits = [slot for slot in slots if isinstance(slot["item"], dict)
            and slot["item"].get("registryName") == native.CATALYST["registryName"]]
        configuration = state.get("server_configuration", state["ghost_widget"]["circuitConfig"]["value"])
        for slot in circuits:
            slot["item"]["metadata"] = configuration
            slot["item"]["hasTag"] = state.get("circuit_tagged", False)
        if len(circuits) == 1:
            item = circuits[0]["item"]
            circuits[0]["item"] = None
            slots[state["circuit_server_index"]]["item"] = item
        if state.get("server_fault"):
            state["server_fault"](data)

    def bind_gui(data):
        gui_binding(data)
        for slot in data.get("slots", []):
            if slot["slot"] == 1:
                slot.update(machineInventoryRole="other", consumedInput=False)
        if state.get("gui_fault"):
            state["gui_fault"](data)

    def bind_runtime(value):
        proof = value["singleBlockRecipeMachine"]
        proof["configurationCircuit"] = {"apiClass": CIRCUIT_SUPPORT_API, "source": "public_runtime_api",
            "available": True, "allowSelectCircuit": True, "inventoryIndex": state["circuit_public_index"],
            "owner": copy.deepcopy(proof["owner"])}
        if state.get("runtime_fault"):
            state["runtime_fault"](value)

    state["world_observation_hook"] = bind_world
    state["gui_observation_hook"] = bind_gui
    runtime_proof(client, monkeypatch, mutate=bind_runtime)
    yield client, state, owner


@pytest.mark.parametrize("index", [3, 4, 8])
async def test_same_placed_machine_one_two_two_keeps_public_index_and_never_transfers_a_circuit(switch_machine, index):
    client, state, owner = switch_machine
    state.update(circuit_public_index=index, circuit_server_index=index)
    first = await native.invoke(client, arguments())
    assert first["status"] == "completed" and not configuration_actions(state), first
    state["output"] = 0  # Model the parent's separate native output reclamation.
    target(state, 2)
    switched = await native.invoke(client, arguments())
    assert switched["status"] == "completed", switched
    assert [(call["args"]["action"], call["args"].get("value")) for call in configuration_actions(state)] == [("set_value", 2)]
    assert switched["acceptance"]["configuredGhostCircuit"] == {"inventoryIndex": index, "configuration": 2,
        "item": native.CATALYST, "count": 0, "physicalCircuitTransferred": False, "configurationMutated": True}
    old = next(phase for phase in switched["phases"] if phase["phase"] == "configured_ghost_previous_preflight")
    assert old["inventoryIndex"] == index and old["configuration"] == 1 and old["stableServerReads"] == 2
    assert old["owner"] == {key: owner[key] for key in old["owner"]} and old["backingApi"] == CIRCUIT_SUPPORT_API
    state["output"] = 0
    repeated = await native.invoke(client, arguments())
    assert repeated["status"] == "completed", repeated
    assert repeated["acceptance"]["configuredGhostCircuit"]["inventoryIndex"] == index
    assert repeated["acceptance"]["configuredGhostCircuit"]["configurationMutated"] is False
    assert len(configuration_actions(state)) == 1
    assert state["input_circuit"] == 0 and state["circuit"] == 3 and state["cursor"] is None
    assert not any(call["operation"] == "chat.send" for call in state["calls"])
    assert all(call["args"]["slot"] == 0 for call in state["calls"]
        if call["operation"] == "gui.slot_click" and call["args"]["slot"] < 2)


@pytest.mark.parametrize("exact_selection,old,new", [(True, 7, 2), (False, 1, 2), (False, 1, 4)])
async def test_native_selection_or_verified_forward_cycle_uses_only_required_actions(switch_machine, exact_selection, old, new):
    client, state, _ = switch_machine
    state["ghost_widget"]["circuitConfig"].update(value=old, noConfiguration=False)
    state["ghost_widget"]["supportedActions"] = ["set_value"] if exact_selection else ["activate"]
    target(state, new)
    result = await native.invoke(client, arguments())
    assert result["status"] == "completed", result
    actions = configuration_actions(state)
    assert len(actions) == (1 if exact_selection else new - old)
    assert all(call["args"]["action"] == ("set_value" if exact_selection else "activate") for call in actions)


async def test_old_ghost_is_proven_before_player_clear_or_finite_supply(switch_machine):
    client, state, _ = switch_machine
    target(state, 2)
    state.update(material=0, circuit=0)
    result = await native.invoke(client, arguments(generateMaterials=True, inventoryPreparation="clear"))
    assert result["status"] == "completed", result
    chats = [(index, call["args"]["message"]) for index, call in enumerate(state["calls"]) if call["operation"] == "chat.send"]
    assert chats and chats[0][1] == "/clear TestPlayer"
    first_widgets = next(index for index, call in enumerate(state["calls"])
        if call["operation"] == "gui.widgets" and "semantic" not in call["args"])
    assert first_widgets < chats[0][0]
    assert sum(call["operation"] == "world.inspect" for call in state["calls"][first_widgets + 1:chats[0][0]]) >= 2
    assert result["materialGrantsDispatched"] == 1
    assert not any("gt.integrated_circuit" in text for _, text in chats)


@pytest.mark.parametrize("fault,reason", [
    ("ordinary_zero", "configured_ghost_previous_inventory_unavailable"),
    ("tagged", "configured_ghost_previous_inventory_unavailable"),
    ("physical", "configured_ghost_previous_inventory_unavailable"),
    ("duplicate", "configured_ghost_previous_inventory_unavailable"),
    ("getter_mismatch", "configured_ghost_configuration_mismatch"),
    ("wrong_gui_owner", "configured_ghost_gui_owner_unavailable"),
    ("wrong_server_owner", "input_gui_owner_changed"),
])
async def test_unproven_old_circuit_is_rejected_before_clear_or_supply(switch_machine, fault, reason):
    client, state, _ = switch_machine
    target(state, 2)
    state.update(material=0, circuit=0)
    if fault == "ordinary_zero":
        state["circuit_server_index"] = 5
    elif fault == "tagged":
        state["circuit_tagged"] = True
    elif fault == "physical":
        state["ghost_inventory_count"] = 1
    elif fault == "duplicate":
        state["duplicate_ghost_inventory"] = True
    elif fault == "getter_mismatch":
        state["server_configuration"] = 1
        state["ghost_widget"]["circuitConfig"]["value"] = 2
    elif fault == "wrong_gui_owner":
        def wrong_gui(data):
            for slot in data.get("slots", []):
                if "machineInventoryOwner" in slot:
                    slot["machineInventoryOwner"]["metaTileId"] += 1
        state["gui_fault"] = wrong_gui
    else:
        def changed_owner(data):
            if any(call["operation"] == "gui.widgets" for call in state["calls"]):
                data["gregtech"]["metaTileId"] += 1
        state["server_fault"] = changed_owner
    result = await native.invoke(client, arguments(generateMaterials=True, inventoryPreparation="clear"))
    assert result["status"] in {"unavailable", "failed"} and result["reason"] == reason, result
    assert not configuration_actions(state)
    no_supply(state)


@pytest.mark.parametrize("fault", ["missing", "private_api", "wrong_source", "unavailable", "disabled",
    "wrong_owner", "boolean_index", "string_index", "negative_index", "outside_inventory"])
async def test_public_backing_index_and_selection_permission_cannot_be_inferred(switch_machine, fault):
    client, state, _ = switch_machine
    target(state, 2)
    def bad_runtime(value):
        circuit = value["singleBlockRecipeMachine"]["configurationCircuit"]
        if fault == "missing":
            value["singleBlockRecipeMachine"].pop("configurationCircuit")
        elif fault == "private_api":
            circuit["apiClass"] = "Private.guess"
        elif fault == "wrong_source":
            circuit["source"] = "native_gui_inventory_index"
        elif fault in {"unavailable", "disabled"}:
            circuit["available" if fault == "unavailable" else "allowSelectCircuit"] = False
        elif fault == "wrong_owner":
            circuit["owner"]["x"] += 1
        else:
            circuit["inventoryIndex"] = {"boolean_index": True, "string_index": "4", "negative_index": -1,
                "outside_inventory": 999}[fault]
    state["runtime_fault"] = bad_runtime
    result = await native.invoke(client, arguments(generateMaterials=True, inventoryPreparation="clear"))
    assert result["status"] == "unavailable", result
    assert result["reason"] == ("configured_ghost_backing_api_changed" if fault == "outside_inventory"
        else "configured_ghost_backing_api_unavailable")
    assert native.writes(state) == []
    no_supply(state)


async def test_later_ghost_widget_page_tree_error_cannot_be_hidden_by_early_valid_ghost(switch_machine, monkeypatch):
    client, state, _ = switch_machine
    target(state, 2)
    original = client.call
    pages = []
    async def paged(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "gui.widgets" and "semantic" not in args:
            offset = args.get("offset", 0)
            pages.append(offset)
            value.update(offset=offset, visited=2, actionableOnly=True, rawScanned=1, nextOffset=offset + 1)
            value["widgets"] = [copy.deepcopy(state["ghost_widget"])] if offset == 0 else []
            value.update(hasMore=offset == 0, partial=offset == 0, byteLimited=False)
            if offset:
                value["coverage"] = {"treeError": "later_public_child_failed"}
        return value
    monkeypatch.setattr(client, "call", paged)
    result = await native.invoke(client, arguments(generateMaterials=True, inventoryPreparation="clear"))
    assert result["status"] == "failed" and result["reason"] == "configured_ghost_widget_coverage_unavailable", result
    assert pages == [0, 1] and not configuration_actions(state)
    no_supply(state)


@pytest.mark.parametrize("fault,reason", [
    ("index", "configured_ghost_inventory_changed"),
    ("owner", "input_gui_owner_changed"),
    ("public_index", "configured_ghost_backing_api_changed"),
])
async def test_switched_target_must_keep_same_server_owner_and_public_backing_index(switch_machine, fault, reason):
    client, state, _ = switch_machine
    target(state, 2)
    def after_switch(data):
        if not configuration_actions(state):
            return
        if fault == "index":
            data["inventory"]["slots"][3]["item"] = data["inventory"]["slots"][4]["item"]
            data["inventory"]["slots"][4]["item"] = None
        elif fault == "owner":
            data["gregtech"]["metaTileId"] += 1
    def after_public(value):
        if fault == "public_index" and configuration_actions(state):
            value["singleBlockRecipeMachine"]["configurationCircuit"]["inventoryIndex"] = 3
    state["server_fault"] = after_switch
    state["runtime_fault"] = after_public
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert len(configuration_actions(state)) == 1
    no_supply(state)


@pytest.mark.parametrize("fault", ["disconnect", "timeout"])
async def test_uncertain_native_switch_is_never_replayed_or_followed_by_supply(switch_machine, monkeypatch, fault):
    client, state, _ = switch_machine
    target(state, 2)
    state.update(material=0, circuit=0)
    if fault == "disconnect":
        state["ghost_native_uncertain"] = True
    else:
        transport = client.http._transport
        original = transport.handler
        async def response_lost(request):
            response = await original(request)
            body = json.loads(request.content)
            if body.get("operation") == "gui.widget_action" and body["args"].get("widgetId") == state["ghost_widget"]["widgetId"]:
                raise httpx.ReadTimeout("Response lost after native configuration dispatch", request=request)
            return response
        monkeypatch.setattr(transport, "handler", response_lost)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert result["lastActionReceipt"]["operation"] == "gui.widget_action"
    assert len(configuration_actions(state)) == 1
    if fault == "timeout":
        assert any(call["operation"] == "__cancel__" for call in state["calls"])
    assert state["ghost_widget"]["circuitConfig"]["value"] == 2
    no_supply(state)
