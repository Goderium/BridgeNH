"""Explicit offline named public API fixtures, not live client evidence."""
import copy
import json
from pathlib import Path
import uuid

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
from test_single_recipe_tasks import single_machine
from test_ghost_recipe_tasks import ghost_machine
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.fluid_recipe_tasks import (FLUID_SLOT_CLASS, NAMED_TANK_API, NAMED_TANK_BINDING,
    NAMED_TANK_GETTERS, fluid_widget, named_tanks)

native_bridge = native.native_bridge
JAVA_FIXTURE = json.loads((Path(__file__).parent / "fixtures/shared-basic-named-tanks-mock.json").read_text(encoding="utf-8"))


def observation(named):
    owner = named["owner"]
    return {**{key: value for key, value in owner.items() if key not in ("metaTileId", "metaClass")},
        "source": "integrated_server", "gregtech": {key: owner[key] for key in ("metaTileId", "metaClass")},
        "fluids": {"namedTanks": copy.deepcopy(named)}}


def declaration(*, ghost=False, input_fluid=True, output_fluid=True, partial=False):
    result = multi.arguments(consumed=1, outputs=1, catalysts=1, input=native.MACHINE.copy(), output=native.MACHINE.copy(),
        fluidContainerInputs=[{"owner": native.MACHINE.copy(), "playerInventoryIndex": 0, "count": 3,
                              "fluid": "hydrogen", "amount": 3000}] if input_fluid else [],
        fluidContainerOutputs=[{"owner": native.MACHINE.copy(), "playerInventoryIndex": 1, "count": 1 if partial else 2,
                               "fluid": "water", "amount": 144 if partial else 2000}] if output_fluid else [],
        timeout_ms=5000, inventoryPreparation="preserve", catalystMode="configured_ghost" if ghost else "physical")
    if not input_fluid: result.pop("fluidContainerInputs")
    if not output_fluid: result.pop("fluidContainerOutputs")
    return result


def transport(client, state, monkeypatch, *, input_fluid=True, output_fluid=True, partial=False, fault=None, reversed_tanks=False):
    multi.vector_transport(client, state, monkeypatch, consumed=1, outputs=1, catalysts=1, retained=False, baseline=False)
    if state.get("configured_ghost_machine"):
        state["vector_input"][4] = native.stack(native.CATALYST, 0)
        state["player_items"][11] = None
    state["recipe"]["fluids"] = {
        "inputs": native.group([{"stack": {"fluid": "hydrogen", "amount": 3000, "hasTag": False}, "chance": 10000}] if input_fluid else []),
        "outputs": native.group([{"stack": {"fluid": "water", "amount": 144 if partial else 2000, "hasTag": False}, "chance": 10000}] if output_fluid else [])}
    state["player_items"][0] = {"registryName": "example:hydrogen_cell", "metadata": 0, "count": 3, "hasTag": True} if input_fluid else None
    state["player_items"][1] = {"registryName": "IC2:itemCellEmpty", "metadata": 0, "count": 1 if partial else 2, "hasTag": True} if output_fluid else None
    state["named_state"] = {"input": (None, 0), "output": (None, 0)}
    state["fluid_activations"] = []
    original = client.call

    def owner():
        return {**native.MACHINE, "dimension": 180, "registryName": "gregtech:gt.blockmachines",
            "metaTileId": state["single_meta_id"], "metaClass": state["single_meta_class"]}

    def named():
        result = copy.deepcopy(JAVA_FIXTURE["namedTanks"])
        result["owner"] = owner()
        result["tanks"] = []
        for role in ("input", "output"):
            fluid, amount = state["named_state"][role]
            capacity = 32000 if role == "input" else 64000
            if role == "input" and not input_fluid or role == "output" and not output_fluid:
                capacity = 0
            result["tanks"].append({"role": role, "publicGetter": NAMED_TANK_GETTERS[role], "capacity": capacity,
                "empty": amount == 0, "fluid": None if not amount else {"fluid": fluid, "amount": amount, "hasTag": False}})
        if reversed_tanks:
            result["tanks"].reverse()
        if fault == "unavailable":
            return {"schemaVersion": 1, "source": "integrated_server", "available": False, "reason": "public_named_tanks_unavailable"}
        if fault == "partial": result["truncated"] = True
        if fault == "owner": result["owner"]["metaTileId"] += 1
        if fault == "duplicate_role": result["tanks"][1] = copy.deepcopy(result["tanks"][0])
        if fault == "getter": result["tanks"][0]["publicGetter"] = "getFluidOutputTank"
        if fault == "initial_foreign": result["tanks"][0].update(empty=False, fluid={"fluid": "oxygen", "amount": 1, "hasTag": False})
        if fault == "residual_input" and state["vector_power_count"]:
            next(tank for tank in result["tanks"] if tank["role"] == "input").update(empty=False, fluid={"fluid": "hydrogen", "amount": 1, "hasTag": False})
        return result

    def widget(role):
        tank = next(tank for tank in named()["tanks"] if tank["role"] == role)
        value = copy.deepcopy(JAVA_FIXTURE["fluidSlot"])
        value.update(capacity={"available": True, "value": tank["capacity"]},
            fluid={"available": True, "empty": tank["empty"], "value": copy.deepcopy(tank["fluid"])},
            canFill={"available": True, "value": role == "input"}, canDrain={"available": True, "value": role == "output"})
        value["machineTankBinding"].update(owner=owner(), role=role, publicGetter=NAMED_TANK_GETTERS[role])
        if fault == "widget_owner": value["machineTankBinding"]["owner"]["x"] += 1
        if fault == "widget_getter": value["machineTankBinding"]["publicGetter"] = "getFluidOutputTank" if role == "input" else "getFluidTank"
        if fault == "widget_unavailable": value["machineTankBinding"] = {"schemaVersion": 1, "available": False, "reason": "public_widget_tank_identity_changed"}
        if fault == "phantom": value["phantom"]["value"] = True
        if fault == "binding_changed_after_pickup" and state["cursor"] is not None:
            value["machineTankBinding"]["owner"]["metaTileId"] += 1
        return {"widgetId": "named:" + role, "class": FLUID_SLOT_CLASS, "semantic": "fluid_slot", "kind": "fluid_slot",
            "enabled": True, "valid": True, "supportedActions": ["activate"], "fluidSlot": value}

    async def call(operation, args, **options):
        if operation == "gui.tooltip":
            index = args["slot"] - 80
            item = state["player_items"][index]
            empty = item["registryName"] == "IC2:itemCellEmpty"
            fluid = None if empty else {"fluidName": "hydrogen" if item["registryName"] == "example:hydrogen_cell" else "water",
                                       "amount": 144 if partial and index == 1 else 1000, "hasTag": False}
            capacity = {"available": True, "value": 1000}
            if empty and "containerFluid" in args:
                capacity.update(fluidName=args["containerFluid"], source="public_fluid_container_registry_getContainerCapacity")
            return {"screenId": args["screenId"], "slot": args["slot"], "item": copy.deepcopy(item),
                "fluidContainer": {"schemaVersion": 1, "source": "public_fluid_container_registry", "scope": "observed_stack_copy_public_api_read",
                    "itemHasTag": item["hasTag"], "probeMutatedCopy": empty, "available": True, "empty": empty,
                    "capacity": capacity, "fluid": fluid}}
        if operation == "gui.widget_action" and args.get("widgetId", "").startswith("named:"):
            role = args["widgetId"].split(":", 1)[1]
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id=str(uuid.uuid4()))
            assert args["action"] == "activate"
            state["fluid_activations"].append(role)
            if role == "input":
                state["named_state"]["input"] = ("hydrogen", 3000)
                state["cursor"] = {"registryName": "IC2:itemCellEmpty", "metadata": 0, "count": 3, "hasTag": True}
            else:
                state["named_state"]["output"] = (None, 0)
                state["cursor"] = {"registryName": "example:water_cell", "metadata": 0, "count": 1 if partial else 2, "hasTag": True}
            if fault == "other_tank_mutated": state["named_state"]["output" if role == "input" else "input"] = ("oxygen", 1)
            if fault == "machine_item_lost": state["vector_input"][0] = None
            if fault == "unknown_activate":
                if trace is not None: trace["outcome_unknown"] = True
                raise BridgeError("BRIDGE_UNAVAILABLE", "One native named fluid activation has unknown outcome.", outcome_unknown=True)
            return {"submitted": True}
        data = await original(operation, args, **options)
        if operation == "bridge.health": data["capabilities"].append("gui.tooltip")
        if operation == "world.inspect":
            data["fluids"]["namedTanks"] = named()
            # Deliberately meaningless legacy order/indices; only named evidence is consumed.
            data["fluids"].update(totalTanks=2, truncated=False, tanks=[{"index": 92}, {"index": 17}])
            if fault == "inventory_partial_after_pickup" and state["cursor"] is not None:
                data["inventory"]["truncated"] = True
        if operation == "gui.inspect":
            for slot in data["slots"]:
                if slot.get("playerInventoryBindingVerified") is True: slot["machineInventoryBindingVerified"] = False
        if operation == "gui.widgets":
            extra = [widget(role) for role in ("input", "output")]
            if fault == "duplicate_widget": extra.append(copy.deepcopy(extra[0]))
            data["widgets"].extend(extra)
        if operation == "recipes.query":
            data["query"] = {"inputs": [{key: row[key] for key in ("registryName", "metadata", "count")} | {"hasTag": False}
                                        for row in args["inputs"]],
                "fluids": [{**row, "hasTag": False} for row in args.get("fluidInputs", [])],
                "specialSlot": None, "caching": False, "ignoreStackSizes": False}
            data["validation"] = {"inputMatch": True, "enabled": True, "notFake": True, "voltageCompatible": True,
                "outputUnknown": False, "outputChecked": True, "outputMatch": True}
        if operation == "gui.widget_action" and args.get("selected") == 1:
            state["named_state"]["input"] = (None, 0)
            if output_fluid: state["named_state"]["output"] = ("water", 144 if partial else 2000)
        return data

    monkeypatch.setattr(client, "call", call)


def test_java_mock_named_tank_and_widget_contract_bind_without_legacy_index():
    named = JAVA_FIXTURE["namedTanks"]
    position = {key: named["owner"][key] for key in ("x", "y", "z", "dimension")}
    tanks = named_tanks(observation(named), position)
    widget = {"widgetId": "actual:output", "class": FLUID_SLOT_CLASS, "semantic": "fluid_slot", "kind": "fluid_slot",
              "enabled": True, "valid": True, "supportedActions": ["activate"], "fluidSlot": JAVA_FIXTURE["fluidSlot"]}
    assert fluid_widget({"widgets": [widget]}, tanks["output"], fill=False)["widgetId"] == "actual:output"
    assert tanks["output"]["amount"] == 144 and "index" not in tanks["output"]


@pytest.mark.parametrize("path,value", [
    (("schemaVersion",), True), (("totalTanks",), 2.0), (("truncated",), True),
    (("owner", "x"), -259), (("owner", "dimension"), 180.0), (("owner", "metaClass"), "changed.Owner"),
    (("tanks", 0, "capacity"), 32000.0), (("tanks", 0, "capacity"), -1),
    (("tanks", 0, "empty"), 1), (("tanks", 0, "fluid"), {"fluid": "water", "amount": 1, "hasTag": False}),
    (("tanks", 1, "fluid", "amount"), 144.0), (("tanks", 1, "fluid", "hasTag"), True),
    (("tanks", 1, "fluid", "amount"), 64001), (("tanks", 1, "publicGetter"), "getFluidTank")])
def test_named_server_pair_refuses_incomplete_or_untyped_values(path, value):
    named = copy.deepcopy(JAVA_FIXTURE["namedTanks"])
    target = named
    for part in path[:-1]: target = target[part]
    target[path[-1]] = value
    position = {key: JAVA_FIXTURE["namedTanks"]["owner"][key] for key in ("x", "y", "z", "dimension")}
    data = observation(named)
    # Keep the actual server block owner distinct from a malformed named binding.
    correct_owner = JAVA_FIXTURE["namedTanks"]["owner"]
    data.update({key: correct_owner[key] for key in ("x", "y", "z", "dimension")})
    data["gregtech"] = {key: correct_owner[key] for key in ("metaTileId", "metaClass")}
    with pytest.raises(BridgeError): named_tanks(data, position)


@pytest.mark.parametrize("input_fluid,output_fluid,partial,reversed_tanks", [
    (True, True, False, False), (True, True, True, True), (False, True, True, False), (True, False, False, True)])
async def test_shared_public_multi_recovers_exact_typed_named_tanks_and_actual_containers(single_machine, monkeypatch, record_property, input_fluid, output_fluid, partial, reversed_tanks):
    client, state = single_machine
    transport(client, state, monkeypatch, input_fluid=input_fluid, output_fluid=output_fluid, partial=partial, reversed_tanks=reversed_tanks)
    result = await native.invoke(client, declaration(input_fluid=input_fluid, output_fluid=output_fluid, partial=partial))
    assert result["status"] == "completed" and result["acceptance"]["completeRecovery"], result
    assert state["fluid_activations"] == (["input"] if input_fluid else []) + (["output"] if output_fluid else [])
    assert state["named_state"] == {"input": (None, 0), "output": (None, 0)} and state["cursor"] is None
    assert all(item is None for item in state["vector_input"].values())
    receipts = result["acceptance"]["fluidResources"]["containerTransfers"]
    assert all(row["tankBinding"] == NAMED_TANK_BINDING and row["otherNamedTankAndMachineItemsPreserved"] for row in receipts)
    assert result["acceptance"]["catalystsRetained"] == [] and len(result["acceptance"]["recoveredCatalysts"]) == 1
    record_property("offlineNativeGuiOpens", sum(row["operation"] == "player.interact" for row in state["calls"]))
    record_property("offlineNativeGuiCloses", sum(row["operation"] == "gui.close" for row in state["calls"]))
    record_property("offlineFluidActivations", len(state["fluid_activations"]))


async def test_shared_named_fluids_preserve_configured_ghost_and_retrieve_all_products(ghost_machine, monkeypatch):
    client, state = ghost_machine
    transport(client, state, monkeypatch, partial=True)
    result = await native.invoke(client, declaration(ghost=True, partial=True))
    assert result["status"] == "completed", result
    assert result["acceptance"]["completeRecovery"] and result["acceptance"]["catalystsRetained"] == [native.CATALYST]
    assert state["vector_input"][4] == native.stack(native.CATALYST, 0)
    assert state["fluid_activations"] == ["input", "output"] and state["cursor"] is None


@pytest.mark.parametrize("fault", ["unavailable", "partial", "owner", "duplicate_role", "getter", "initial_foreign",
    "widget_owner", "widget_getter", "widget_unavailable", "phantom", "duplicate_widget"])
async def test_missing_or_ambiguous_named_server_or_widget_evidence_refuses_before_activation(single_machine, monkeypatch, fault):
    client, state = single_machine
    transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed", result
    assert state["fluid_activations"] == [] and state["vector_power_count"] == 0


@pytest.mark.parametrize("fault", ["binding_changed_after_pickup", "inventory_partial_after_pickup", "other_tank_mutated", "machine_item_lost", "unknown_activate", "residual_input"])
async def test_post_preflight_drift_or_unknown_native_outcome_never_replays_or_claims_complete_recovery(single_machine, monkeypatch, fault):
    client, state = single_machine
    transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed", result
    assert state["fluid_activations"].count("input") <= 1 and state["fluid_activations"].count("output") <= 1
    if fault in ("binding_changed_after_pickup", "inventory_partial_after_pickup"): assert state["fluid_activations"] == []
    if fault == "unknown_activate": assert result["error"].get("outcome_unknown") is True
