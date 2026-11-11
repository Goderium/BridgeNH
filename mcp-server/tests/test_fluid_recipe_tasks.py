"""Offline real-fixture semantics and transport model; mock outputs are not live evidence."""

import copy
import json
from pathlib import Path
import uuid

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.fluid_recipe_tasks import container_semantics, tank_observation, fluid_widget
from minecraft_codex_bridge.recipe_tasks import _Unavailable
from minecraft_codex_bridge.validation import tool_validator


native_bridge = native.native_bridge
# Minimal exact retained-field regression fixtures; complete live DTOs stay in local temporary evidence.
FIXTURES = Path(__file__).parent / "fixtures"
PROOF = json.loads((FIXTURES / "fluid-container-native-input-regression.json").read_text(encoding="utf-8"))
PARTIAL_PROOF = json.loads((FIXTURES / "fluid-container-native-partial-output-regression.json").read_text(encoding="utf-8"))
PARTIAL = PARTIAL_PROOF["actualFluidExtraction"]
FLUID_INPUT = {"x": 30, "y": 21, "z": 20}
FLUID_OUTPUT = {"x": 31, "y": 21, "z": 20}
FILLED = {key: PROOF["actualFilledStackTooltip"]["item"][key] for key in ("registryName", "metadata", "count", "hasTag")}
EMPTY = {"registryName": "IC2:itemCellEmpty", "metadata": 0, "count": 3, "hasTag": False}


def declaration(*, fluid_only=False, partial=False, **extra):
    args = multi.arguments(consumed=1, outputs=1, catalysts=1,
        fluidContainerInputs=[{"owner": FLUID_INPUT, "playerInventoryIndex": 0, "count": 3, "fluid": "hydrogen", "amount": 3000}],
        fluidContainerOutputs=[{"owner": FLUID_OUTPUT, "playerInventoryIndex": 1, "count": 1 if partial else 2,
                                "fluid": "molten.titanium" if partial else "water", "amount": 144 if partial else 2000}],
        timeout_ms=5000, inventoryPreparation="preserve")
    if fluid_only:
        for key in ("material", "outputItem"):
            args.pop(key, None)
        args.update(input=FLUID_INPUT, output=FLUID_OUTPUT, backendInputs=[], outputItems=[])
    args.update(extra)
    return args


def transport(client, state, monkeypatch, *, fluid_only=False, tagged=False, partial=False, fault=None):
    multi.vector_transport(client, state, monkeypatch, consumed=1, outputs=1, catalysts=0 if fluid_only else 1,
                           retained=False, baseline=False)
    if fluid_only:
        state["recipe"]["items"] = {"inputs": native.group([]), "outputs": native.group([])}
        state["player_items"][9] = None
    state["recipe"]["fluids"] = {
        "inputs": native.group([{"stack": {"fluid": "hydrogen", "amount": 3000, "hasTag": False}, "chance": 10000}]),
        "outputs": native.group([{"stack": {"fluid": "molten.titanium" if partial else "water", "amount": 144 if partial else 2000, "hasTag": False}, "chance": 10000}])}
    state["player_items"][0] = {**FILLED, "hasTag": tagged}
    state["player_items"][1] = copy.deepcopy(PARTIAL["actualEmptyTooltip"]["item"]) if partial else {**EMPTY, "count": 2, "hasTag": tagged}
    state["tanks"] = {"fluid_input": {"index": 0, "capacity": 128000}, "fluid_output": {"index": 0, "capacity": 128000}}
    state["fluid_actions"] = []
    original = client.call

    def owner(name):
        return {**(FLUID_INPUT if name == "fluid_input" else FLUID_OUTPUT), "dimension": 180,
                "registryName": "gregtech:gt.blockmachines", "metaTileId": 54 if name == "fluid_input" else 60,
                "metaClass": "gregtech.api.metatileentity.implementations.MTEHatchInput" if name == "fluid_input"
                    else "gregtech.api.metatileentity.implementations.MTEHatchOutput"}

    def controls(name):
        widget = copy.deepcopy(PROOF["widget"])
        tank = state["tanks"][name]
        widget["fluidSlot"]["fluid"] = {"available": True, "empty": "fluid" not in tank,
            "value": {"fluid": tank["fluid"], "amount": tank["amount"], "hasTag": False} if "fluid" in tank else None}
        if fault == "phantom":
            widget["fluidSlot"]["phantom"]["value"] = True
        widgets = [widget, copy.deepcopy(widget)] if fault == "ambiguous_widget" else [widget]
        if fault == "widget_changed_after_pickup" and state["cursor"] is not None:
            widgets.append({**copy.deepcopy(widget), "widgetId": "widget:new_unbound_fluid_slot"})
        return {"screenId": "screen:" + name, "widgets": widgets,
                "hasMore": False, "partial": False, "scanTruncated": False, "byteLimited": False}

    async def call(operation, args, **options):
        name = state["open"]
        if operation in {"gui.tooltip", "gui.widgets", "gui.widget_action", "gui.slot_click"} and name in state["tanks"]:
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
        if operation == "world.inspect" and args["x"] in {FLUID_INPUT["x"], FLUID_OUTPUT["x"]}:
            target = "fluid_input" if args["x"] == FLUID_INPUT["x"] else "fluid_output"
            value = owner(target)
            result = {**{k: v for k, v in value.items() if k not in ("metaTileId", "metaClass")}, "source": "integrated_server",
                      "gregtech": {k: value[k] for k in ("metaTileId", "metaClass")},
                      "inventory": copy.deepcopy(PROOF["before"]["inventory"]),
                      "fluids": {"totalTanks": 1, "truncated": False, "tanks": [copy.deepcopy(state["tanks"][target])]}}
            if fault == "owner_drift" and state["fluid_actions"]:
                result["gregtech"]["metaTileId"] += 1
            if fault == "tank_partial":
                result["fluids"]["truncated"] = True
            if fault == "residual_input" and state["vector_power_count"] and target == "fluid_input":
                result["fluids"]["tanks"][0].update(fluid="hydrogen", amount=1)
            return result
        if operation == "gui.inspect" and name in state["tanks"]:
            slots = [{"slot": 0, "item": None}, {"slot": 1, "item": None}]
            for index, item in state["player_items"].items():
                slots.append({"slot": index + 2, "item": copy.deepcopy(item), "playerInventoryBindingVerified": True,
                              "machineInventoryBindingVerified": False,
                              "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot",
                              "inventorySource": "current_player_inventory", "playerInventoryIndex": index})
            if fault == "duplicate_player_binding":
                slots.append({**slots[2], "slot": 77})
            return {"screenId": "screen:" + name, "windowId": state["window"], "totalSlots": len(slots), "truncated": False, "slots": slots}
        if operation == "gui.widgets" and name in state["tanks"]:
            return controls(name)
        if operation == "gui.tooltip" and name in state["tanks"]:
            item = state["player_items"][args["slot"] - 2]
            if partial and args["slot"] - 2 == 1:
                tooltip = copy.deepcopy(PARTIAL["actualFilledTooltip" if "fluid_output" in state["fluid_actions"] else "actualEmptyTooltip"])
                tooltip.update(screenId=args["screenId"], slot=args["slot"], item=copy.deepcopy(item))
                return tooltip
            empty = item["registryName"] == EMPTY["registryName"]
            fluid = None if empty else {"fluidName": "hydrogen" if item["metadata"] == FILLED["metadata"] else "water", "amount": 1000, "hasTag": False}
            # Returned-shell and water semantics are explicit mock data, absent from the retained live supplement.
            semantics = {"schemaVersion": 1, "source": "public_fluid_container_registry", "scope": "observed_stack_copy_public_api_read",
                         "itemHasTag": item["hasTag"], "probeMutatedCopy": empty, "available": True, "empty": empty,
                         "capacity": {"available": True, "value": 1000}, "fluid": fluid}
            if empty and "containerFluid" in args:
                semantics["capacity"].update(fluidName=args["containerFluid"],
                    source="public_fluid_container_registry_getContainerCapacity")
            if fault == "bad_return_tooltip" and name == "fluid_input" and empty:
                semantics.update(empty=False, fluid={"fluidName": "hydrogen", "amount": 1000, "hasTag": False})
            if fault == "wrong_source_fluid" and name == "fluid_input" and not empty:
                semantics["fluid"]["fluidName"] = "oxygen"
            return {"screenId": args["screenId"], "slot": args["slot"], "item": copy.deepcopy(item), "fluidContainer": semantics}
        if operation == "gui.slot_click" and name in state["tanks"]:
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id=str(uuid.uuid4()))
            index = args["slot"] - 2
            pickup = state["cursor"] is None
            if pickup:
                state["cursor"] = state["player_items"][index]
                state["player_items"][index] = None
            else:
                assert state["player_items"][index] is None
                state["player_items"][index] = state["cursor"]
                state["cursor"] = None
            if fault == "unknown_pickup" and pickup or fault == "unknown_return" and not pickup:
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("BRIDGE_UNAVAILABLE", "Actual native container movement outcome unknown.", outcome_unknown=True)
            return {"submitted": True}
        if operation == "gui.widget_action" and name in state["tanks"]:
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id=str(uuid.uuid4()))
            assert args["action"] == "activate"
            state["fluid_actions"].append(name)
            if name == "fluid_input":
                state["tanks"][name].update(fluid="hydrogen", amount=3000)
                state["cursor"] = {**EMPTY, "hasTag": tagged}
            else:
                state["tanks"][name] = {"index": 0, "capacity": 128000}
                state["cursor"] = copy.deepcopy(PARTIAL["actualFilledTooltip"]["item"]) if partial else {"registryName": "example:water_cell", "metadata": 0, "count": 2, "hasTag": tagged}
            if fault == "lost_unrelated" and name == "fluid_input":
                state["player_items"][9] = None
            if fault == "unknown_activate":
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("BRIDGE_UNAVAILABLE", "Single native fluid activate outcome unknown.", outcome_unknown=True)
            return {"submitted": True}
        result = await original(operation, args, **options)
        if operation == "bridge.health":
            result["capabilities"].append("gui.tooltip")
        if operation == "machine.inspect":
            components = result["components"]
            if fluid_only:
                components["entries"] = [entry for entry in components["entries"] if entry["role"] not in ("item_input", "item_output")]
                for role in components["roles"]:
                    if role["role"] in ("item_input", "item_output"):
                        role["count"] = 0
            for role in components["roles"]:
                if role["role"] == "fluid_input":
                    role["count"] = 1
            components["roles"].append({"role": "fluid_output", "count": 1})
            components["entries"].extend({**owner(name), "role": name, "valid": True} for name in ("fluid_input", "fluid_output"))
            for index, entry in enumerate(components["entries"]):
                entry["index"] = index
            components.update(total=len(components["entries"]), nextOffset=len(components["entries"]))
        if operation == "recipes.query":
            result["query"]["fluids"] = [{**item, "hasTag": False} for item in args.get("fluidInputs", [])]
            if "output" not in args:
                result["validation"].update(outputChecked=False, outputMatch=None)
        if operation == "player.interact" and args["x"] in {FLUID_INPUT["x"], FLUID_OUTPUT["x"]}:
            state["open"] = "fluid_input" if args["x"] == FLUID_INPUT["x"] else "fluid_output"
        if operation == "gui.widget_action" and args.get("selected") == 1:
            state["tanks"]["fluid_input"] = {"index": 0, "capacity": 128000}
            state["tanks"]["fluid_output"].update(fluid="molten.titanium" if partial else "water", amount=144 if partial else 2000)
        return result

    monkeypatch.setattr(client, "call", call)


def test_retained_actual_three_cell_stack_tooltip_one_tank_and_native_widget_are_accepted():
    assert PROOF["sourceSha256"] == "7f824464b6721808a470f59e0bca37da6a1056057278c06d0b0ac67d3c74428d"
    semantics = container_semantics(PROOF["actualFilledStackTooltip"], FILLED)
    assert FILLED["count"] * semantics["fluid"]["amount"] == 3000
    tank = tank_observation(PROOF["before"])
    assert fluid_widget({"widgets": [PROOF["widget"]]}, tank, fill=True)["widgetId"] == "widget:5025"
    assert PROOF["samples"][0]["tank"] == PROOF["samples"][1]["tank"]
    assert PROOF["samples"][0]["cursor"]["registryName"] != PROOF["samples"][1]["cursor"]["registryName"]


def test_retained_actual_tagged_partial_cell_proves_144mb_with_1000_capacity():
    assert PARTIAL_PROOF["sourceSha256"] == "d8639cf8dcd3c3ead73fdbc87603594372343035a87b9b9230d55b7032ef0bb9"
    empty = container_semantics(PARTIAL["actualEmptyTooltip"], PARTIAL["actualEmptyTooltip"]["item"])
    filled = container_semantics(PARTIAL["actualFilledTooltip"], PARTIAL["actualFilledTooltip"]["item"])
    assert empty["empty"] is True and empty["capacity"]["value"] == filled["capacity"]["value"] == 1000
    assert filled["fluid"] == {"fluidName": "molten.titanium", "amount": 144, "hasTag": False}
    assert PARTIAL["actualFilledTooltip"]["item"]["hasTag"] is True
    assert tank_observation(PARTIAL["ownerBefore"])["amount"] == 144
    assert tank_observation(PARTIAL["tankAfter"])["amount"] == 0


@pytest.mark.parametrize("fluid_only", [False, True])
async def test_native_partial_actual_tagged_output_cell_uses_144_actual_mb_without_invented_capacity(native_bridge, monkeypatch, fluid_only):
    client, state = native_bridge
    transport(client, state, monkeypatch, fluid_only=fluid_only, partial=True)
    result = await native.invoke(client, declaration(fluid_only=fluid_only, partial=True))
    assert result["status"] == "completed", result
    receipt = result["acceptance"]["fluidResources"]["containerTransfers"][1]
    assert receipt["count"] == 1 and receipt["amount"] == 144 and receipt["returnedStack"]["hasTag"] is True
    assert receipt["returnedSemantics"]["fluid"]["amount"] == 144 and receipt["returnedSemantics"]["capacity"]["value"] == 1000
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"]


@pytest.mark.parametrize("fluid_only,tagged", [(False, False), (False, True), (True, False), (True, True)])
async def test_explicit_actual_stacks_use_one_native_activate_each_and_complete_recovery(native_bridge, monkeypatch, fluid_only, tagged):
    client, state = native_bridge
    transport(client, state, monkeypatch, fluid_only=fluid_only, tagged=tagged)
    result = await native.invoke(client, declaration(fluid_only=fluid_only))
    assert result["status"] == "completed", result
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"]
    assert result["acceptance"]["completeRecovery"] is True
    assert result["acceptance"]["fluidResources"]["allDeclaredTanksRecovered"] is True
    assert result["acceptance"]["fluidResources"]["hiddenNbtIdentityClaimed"] is False
    assert len(state["queries"]) == 1 and state["queries"][0]["fluidInputs"] == [{"fluid": "hydrogen", "amount": 3000}]
    assert ("output" in state["queries"][0]) is (not fluid_only)
    assert state["player_items"][0] == {**EMPTY, "hasTag": tagged}
    assert state["player_items"][1]["count"] == 2 and state["player_items"][1]["hasTag"] is tagged
    assert state["cursor"] is None and all("fluid" not in tank for tank in state["tanks"].values())
    assert not any(call["operation"] == "chat.send" for call in state["calls"])


@pytest.mark.parametrize("fault,reason", [
    ("phantom", "fluid_slot_permission_unavailable"), ("ambiguous_widget", "fluid_slot_binding_unavailable"),
    ("duplicate_player_binding", "fluid_container_player_binding_unavailable"), ("tank_partial", "fluid_tank_coverage_unavailable"),
    ("wrong_source_fluid", "fluid_container_amount_mismatch"), ("owner_drift", "registered_fluid_owner_changed"),
    ("bad_return_tooltip", "fluid_container_direction_mismatch"), ("lost_unrelated", "fluid_player_conservation_failed"),
    ("residual_input", "fluid_tank_conservation_failed"),
    ("widget_changed_after_pickup", "ASSERTION_FAILED"),
])
async def test_incomplete_or_changed_actual_resource_proofs_stop_without_replay(native_bridge, monkeypatch, fault, reason):
    client, state = native_bridge
    transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and result["reason"] == reason, result
    assert state["fluid_actions"].count("fluid_input") <= 1 and state["vector_power_count"] <= 1


@pytest.mark.parametrize("fault", ["unknown_pickup", "unknown_activate", "unknown_return"])
async def test_unknown_container_action_keeps_original_receipt_and_never_replays(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert result["lastActionReceipt"]["operation"] == ("gui.widget_action" if fault == "unknown_activate" else "gui.slot_click")
    assert state["vector_power_count"] == 0 and len(state["fluid_actions"]) <= 1
    assert len([call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 2]) == (2 if fault == "unknown_return" else 1)
    assert result["resourceDiagnostics"]["available"] is True


def test_fluid_only_schema_retains_legacy_nonempty_item_requirements():
    validator = tool_validator("mcb_recipe_once")
    assert validator.is_valid(declaration(fluid_only=True))
    assert validator.is_valid(native.declaration())
    assert not validator.is_valid({**native.declaration(recipeLookup="backend_match"), "backendInputs": []})
    assert not validator.is_valid(declaration(executions=2))
    assert not validator.is_valid(declaration(inventoryPreparation="clear"))


@pytest.mark.parametrize("mutate", [
    lambda value: value["fluidContainer"].update(source="guessed_registry"),
    lambda value: value["fluidContainer"].update(itemHasTag=True),
    lambda value: value["fluidContainer"]["fluid"].update(hasTag=True),
    lambda value: value["item"].update(count=2),
    lambda value: value["fluidContainer"]["capacity"].update(value=True),
])
def test_actual_source_semantics_never_use_guessed_identity_or_missing_tag_proof(mutate):
    tooltip = copy.deepcopy(PROOF["actualFilledStackTooltip"])
    mutate(tooltip)
    with pytest.raises(_Unavailable):
        container_semantics(tooltip, FILLED)
