"""Transport-level finite item vectors, native retrieval and conservation failures."""

import copy
import uuid

import pytest

import test_recipe_tasks as native
from test_multiblock_backend_tasks import observed_backend
from test_single_recipe_tasks import single_machine
from test_ghost_recipe_tasks import ghost_machine
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.multi_recipe_tasks import supported_multi_recipe
from minecraft_codex_bridge.recipe_tasks import MACHINE_SLOT_BINDING, _Unavailable
from minecraft_codex_bridge.validation import tool_validator


native_bridge = native.native_bridge
SECOND_INPUT = {"registryName": "minecraft:iron_ingot", "metadata": 0}
SECOND_OUTPUT = {"registryName": "minecraft:stick", "metadata": 0}
SECOND_CATALYST = {"registryName": "minecraft:diamond", "metadata": 0}


def candidate(*, consumed=2, outputs=2, catalysts=1):
    value = native.recipe(circuit=False, wildcard=False)
    inputs = [{"stack": native.stack(native.MATERIAL, 8), "chance": 10000, "nonConsumedCatalyst": False}]
    if consumed == 2:
        inputs.append({"stack": native.stack(SECOND_INPUT, 2), "chance": 10000, "nonConsumedCatalyst": False})
    if catalysts:
        inputs.append({"stack": native.stack(native.CATALYST, 0), "chance": 10000, "nonConsumedCatalyst": True})
    if catalysts == 2:
        inputs.append({"stack": native.stack(SECOND_CATALYST, 0), "chance": 10000, "nonConsumedCatalyst": True})
    products = [{"stack": native.stack(native.PRODUCT, 1), "chance": 10000}]
    if outputs == 2:
        products.append({"stack": native.stack(SECOND_OUTPUT, 3), "chance": 10000})
    value["items"] = {"inputs": native.group(inputs), "outputs": native.group(products)}
    value.update(candidateOrdinal=0, candidateSource="public_backend_find")
    return value


def arguments(*, consumed=2, outputs=2, catalysts=1, **extra):
    args = native.declaration(recipeMode="public_multi", recipeLookup="backend_match",
                              outputItems=[native.PRODUCT] + ([SECOND_OUTPUT] if outputs == 2 else []),
                              backendInputs=[{"source": "registry", **native.MATERIAL, "count": 8}]
                                + ([{"source": "registry", **SECOND_INPUT, "count": 2}] if consumed == 2 else [])
                                + ([{"source": "registry", **native.CATALYST, "count": 1}] if catalysts else [])
                                + ([{"source": "registry", **SECOND_CATALYST, "count": 1}] if catalysts == 2 else []))
    args.pop("inputGuiSlot")
    args.pop("catalystGuiSlot")
    args.update(extra)
    return args


def vector_transport(client, state, monkeypatch, *, consumed=2, outputs=2, catalysts=1, baseline=True, retained=True, fault=None):
    original = client.call
    state["recipe"] = candidate(consumed=consumed, outputs=outputs, catalysts=catalysts)
    state["player_items"] = {index: None for index in range(36)}
    state["player_items"][9] = native.stack(native.MATERIAL, 32)
    if consumed == 2:
        state["player_items"][10] = native.stack(SECOND_INPUT, 8)
    if catalysts and not retained:
        state["player_items"][11] = native.stack(native.CATALYST, 1)
    if catalysts == 2:
        state["player_items"][12] = native.stack(SECOND_CATALYST, 1)
    state["vector_input"] = {index: None for index in range(8)}
    shared = state.get("single_machine", False)
    ghost = state.get("configured_ghost_machine", False)
    if catalysts and retained:
        state["vector_input"][4 if ghost else 0] = native.stack(native.CATALYST, 0 if ghost else 1)
    state["vector_output"] = state["vector_input"] if shared else {index: None for index in range(4)}
    if baseline:
        state["vector_output"][6 if shared else 0] = native.stack(native.PRODUCT, 4)
        if outputs == 2:
            state["vector_output"][7 if shared else 1] = native.stack(SECOND_OUTPUT, 5)
    state["vector_power_count"] = 0
    state["vector_retrieval_count"] = 0

    def owner(name):
        return {**(native.INPUT if name == "input" else native.OUTPUT), "dimension": 180,
                "registryName": "gregtech:gt.blockmachines", "metaTileId": state["single_meta_id"] if shared else 77 if name == "input" else 78,
                "metaClass": state["single_meta_class"] if shared else "example.PublicInputBus" if name == "input" else "example.PublicOutputBus"}

    def inventory(items, *, player=False):
        value = {"totalSlots": len(items), "truncated": False,
                 "slots": [{"index": index, "item": copy.deepcopy(item)} for index, item in items.items()]}
        if player:
            value["cursorItem"] = copy.deepcopy(state["cursor"])
        return value

    async def vector(operation, args, **options):
        if operation in {"inventory.inspect", "gui.slot_click", "gui.inspect"} or operation == "world.inspect" and args["x"] in {native.INPUT["x"], native.OUTPUT["x"]}:
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
        if operation == "inventory.inspect":
            return inventory(state["player_items"], player=True)
        if operation == "world.inspect" and args["x"] in {native.INPUT["x"], native.OUTPUT["x"]}:
            name = "input" if args["x"] == native.INPUT["x"] else "output"
            actual_owner = owner(name)
            data = {**{key: value for key, value in actual_owner.items() if key not in {"metaTileId", "metaClass"}},
                    "source": "integrated_server", "gregtech": {key: actual_owner[key] for key in ("metaTileId", "metaClass")},
                    "inventory": inventory(state["vector_" + name]), "fluids": {"totalTanks": 0, "truncated": False, "tanks": []}}
            if fault == "partial_output" and name == "output":
                data["inventory"]["truncated"] = True
            if fault == "owner_changed" and name == "input" and state["vector_power_count"]:
                data["gregtech"]["metaTileId"] += 1
            if fault == "output_owner_changed" and name == "output" and state["vector_power_count"]:
                data["gregtech"]["metaTileId"] += 1
            if fault == "existing_tank" and name == "output":
                data["fluids"] = {"totalTanks": 1, "truncated": False,
                                  "tanks": [{"index": 0, "capacity": 8000, "fluid": "water", "amount": 1000}]}
            return data
        if operation == "gui.inspect":
            name = state["open"] if state["open"] in {"input", "output"} else "input"
            actual_owner = owner(name)
            slots = []
            for index, item in state["vector_" + name].items():
                slots.append({"slot": index, "inventoryIndex": index, "machineInventoryIndex": index,
                              "machineInventoryOwner": actual_owner, "machineInventoryBindingVerified": True,
                              "machineInventoryBinding": MACHINE_SLOT_BINDING, "playerInventoryBindingVerified": False,
                              "machineInventoryRole": "consumed_input" if name == "input" else "output",
                              "consumedInput": name == "input", "item": copy.deepcopy(item)})
            for index, item in state["player_items"].items():
                slots.append({"slot": 80 + index, "inventoryIndex": index, "playerInventoryIndex": index,
                              "playerInventoryBindingVerified": True, "machineInventoryBindingVerified": False,
                              "inventorySource": "current_player_inventory",
                              "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot", "item": copy.deepcopy(item)})
            if fault == "duplicate_input_binding" and name == "input":
                slots.append({**slots[1 if catalysts and retained else 0], "slot": 70})
            if fault == "missing_output_binding" and name == "output":
                for slot in slots:
                    slot["machineInventoryBindingVerified"] = False
            return {"screenId": "screen:" + str(state["open"]), "windowId": state["window"],
                    "totalSlots": len(slots), "truncated": False, "slots": slots}
        if operation == "gui.slot_click":
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id=str(uuid.uuid4()))
            name = state["open"]
            if shared:
                name = "input"
            index = args["slot"] - 80 if args["slot"] >= 80 else args["slot"]
            items = state["player_items"] if args["slot"] >= 80 else state["vector_" + name]
            cursor = state["cursor"]
            if cursor is None:
                assert items[index] is not None
                state["cursor"] = items[index]
                items[index] = None
                if fault == "unknown_catalyst_recovery" and state["vector_power_count"] and name == "input" and args["slot"] < 80:
                    if trace is not None:
                        trace["outcome_unknown"] = True
                    raise BridgeError("BRIDGE_UNAVAILABLE", "Native physical catalyst recovery outcome unknown.", outcome_unknown=True)
                if fault == "unknown_feed" and name == "input" and args["slot"] >= 80:
                    if trace is not None:
                        trace["outcome_unknown"] = True
                    raise BridgeError("BRIDGE_UNAVAILABLE", "Native material pickup outcome unknown.", outcome_unknown=True)
                if name == "output" or shared and index in (6, 7) and args["slot"] < 80:
                    state["vector_retrieval_count"] += 1
                    if fault == "unknown_retrieval":
                        if trace is not None:
                            trace["outcome_unknown"] = True
                        raise BridgeError("BRIDGE_UNAVAILABLE", "Native product pickup outcome unknown.", outcome_unknown=True)
            else:
                count = cursor["count"] if args["button"] == 0 else 1
                assert items[index] is None or all(items[index][key] == cursor[key] for key in ("registryName", "metadata", "hasTag"))
                if items[index] is None:
                    items[index] = {**cursor, "count": 0}
                items[index]["count"] += count
                cursor["count"] -= count
                if cursor["count"] == 0:
                    state["cursor"] = None
                if name == "output" and fault == "lost_player_item":
                    state["player_items"][10] = None
            return {"submitted": True, "screenOpen": True, "screenKind": "modularui"}
        data = await original(operation, args, **options)
        if operation == "player.interact" and not shared and args["x"] == native.OUTPUT["x"]:
            state["open"] = "output"
        if operation == "gui.widget_action" and args.get("selected") == 1:
            state["vector_power_count"] += 1
            for entry in state["recipe"]["items"]["inputs"]["entries"]:
                identity = {key: entry["stack"][key] for key in ("registryName", "metadata")}
                source = next(item for item in state["vector_input"].values() if item is not None
                              and all(item[key] == value for key, value in identity.items()))
                amount = entry["stack"]["count"]
                if amount and fault != "retained_second_input":
                    source["count"] -= amount
                elif amount and identity != SECOND_INPUT:
                    source["count"] -= amount
                elif not amount and fault == "lost_catalyst":
                    source["count"] = 0
            for index, item in list(state["vector_input"].items()):
                if item is not None and item["count"] == 0 and not (ghost and index == 4):
                    state["vector_input"][index] = None
            for ordinal, entry in enumerate(state["recipe"]["items"]["outputs"]["entries"]):
                item = copy.deepcopy(entry["stack"])
                destination = ordinal + 6 if shared else ordinal
                if state["vector_output"][destination] is not None:
                    item["count"] += state["vector_output"][destination]["count"]
                state["vector_output"][destination] = item
            if shared:
                state.update(active=False, single_progress=0, single_max_progress=0, single_pending=0)
            if fault == "extra_output":
                state["vector_output"][3] = native.stack({"registryName": "minecraft:stone", "metadata": 0}, 1)
            if fault == "excess_second_output":
                state["vector_output"][1]["count"] += 1
        return data

    monkeypatch.setattr(client, "call", vector)
    if shared:
        async def single_backend(operation, args, **options):
            data = await vector(operation, args, **options)
            if operation == "recipes.query":
                data["recipes"][0].pop("categoryRecipeIndex", None)
                data["recipes"][0].update(candidateOrdinal=0, candidateSource="public_backend_find", category=None)
                data.update(mode="backend_match", lookupSource="public_backend_find", lookupComplete=True,
                            registeredCatalogComplete=False, executionValidated=False, hasMore=False, nextCursor=None,
                            matched=True, candidateValidated=True)
            return data
        monkeypatch.setattr(client, "call", single_backend)
    else:
        observed_backend(client, monkeypatch)


@pytest.mark.parametrize("consumed,outputs,catalysts,retained,baseline,executions", [
    (2, 1, 1, True, False, 1), (1, 2, 0, True, False, 1),
    (2, 2, 1, True, True, 1), (2, 2, 2, False, True, 2),
])
async def test_finite_multi_items_use_first_backend_native_feeds_all_deltas_and_conserved_retrieval(native_bridge, monkeypatch, consumed, outputs, catalysts, retained, baseline, executions):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, consumed=consumed, outputs=outputs, catalysts=catalysts, retained=retained, baseline=baseline)
    result = await native.invoke(client, arguments(consumed=consumed, outputs=outputs, catalysts=catalysts, executions=executions))
    assert result["status"] == "completed", result
    assert result["executionsCompleted"] == executions
    assert len(state["queries"]) == 1 and state["queries"][0]["mode"] == "backend_match"
    assert len(result["acceptance"]["consumedItems"]) == consumed
    assert len(result["acceptance"]["outputDeltas"]) == outputs
    assert result["acceptance"]["outputDeltas"] == result["acceptance"]["retrievedItems"]
    assert result["acceptance"]["catalystsRetained"] == result["acceptance"]["preservedCatalystBaseline"]
    assert result["acceptance"]["playerInventoryConserved"] is True
    assert state["vector_power_count"] == executions and state["vector_retrieval_count"] == outputs * executions
    assert state["cursor"] is None and state["allowed"] is state["active"] is state["core"] is False
    assert len([entry for entry in result["nativeRetrievals"] if entry["resource"] == "product"]) == outputs * executions
    assert len(result["acceptance"]["recoveredCatalysts"]) == (catalysts if not retained else max(0, catalysts - 1))
    assert not any(call["operation"] == "chat.send" for call in state["calls"])


@pytest.mark.parametrize("fault,reason", [
    ("retained_second_input", "input_or_catalyst_acceptance_failed"),
    ("lost_catalyst", "input_or_catalyst_acceptance_failed"),
    ("extra_output", "multi_output_delta_mismatch"),
    ("excess_second_output", "ITEM_DELTA_CHANGED"),
    ("partial_output", "inventory_coverage_incomplete"),
    ("duplicate_input_binding", "input_gui_binding_ambiguous"),
    ("missing_output_binding", "multi_output_binding_unavailable"),
    ("lost_player_item", "multi_retrieval_conservation_failed"),
    ("owner_changed", "input_gui_owner_changed"),
    ("output_owner_changed", "multi_output_owner_changed"),
    ("existing_tank", "single_machine_fluid_isolation_unavailable"),
])
async def test_each_real_input_output_binding_and_player_item_is_required(native_bridge, monkeypatch, fault, reason):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, arguments())
    assert result["status"] != "completed" and result["reason"] == reason, result
    assert state["vector_power_count"] <= 1
    assert len(state["queries"]) == 1
    assert result["rolledBack"] is False


async def test_unknown_native_retrieval_keeps_actual_cursor_no_pickup_or_power_replay(native_bridge, monkeypatch):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, fault="unknown_retrieval")
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert state["vector_retrieval_count"] == state["vector_power_count"] == 1
    assert state["cursor"]["registryName"] == native.PRODUCT["registryName"]
    assert result["lastActionReceipt"]["operation"] == "gui.slot_click"


async def test_unknown_native_feed_preserves_actual_material_cursor_and_does_not_power_or_replay(native_bridge, monkeypatch):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, fault="unknown_feed")
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert state["vector_power_count"] == state["vector_retrieval_count"] == 0
    assert state["cursor"] == native.stack(native.MATERIAL, 32)
    assert len([call for call in state["calls"] if call["operation"] == "gui.slot_click"]) == 1


async def test_incomplete_product_declaration_never_selects_an_alternate_backend_recipe_or_dispatches(native_bridge, monkeypatch):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch)
    result = await native.invoke(client, arguments(outputItems=[native.PRODUCT]))
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    assert len(state["queries"]) == 1
    assert not native.writes(state)
    assert state["vector_power_count"] == state["vector_retrieval_count"] == 0


@pytest.mark.parametrize("source_count", [32, 33])
async def test_large_finite_item_feed_uses_exact_whole_stack_or_refuses_before_native_pickup(native_bridge, monkeypatch, source_count):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, consumed=1, catalysts=0, baseline=False)
    state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"] = 32
    state["player_items"][9]["count"] = source_count
    request = arguments(consumed=1, catalysts=0)
    request["backendInputs"][0]["count"] = 32
    result = await native.invoke(client, request)
    if source_count == 32:
        assert result["status"] == "completed", result
        assert result["acceptance"]["consumedItems"][0]["count"] == 32
        feed = next(phase for phase in result["phases"] if phase["phase"] == "transfer_material")
        assert feed["dispatchedActions"] == 2  # Whole source pickup and deposit.
    else:
        assert result["status"] == "unavailable" and result["reason"] == "exact_transfer_budget", result
        assert not native.writes(state)


@pytest.mark.parametrize("executions", [1, 2])
async def test_common_single_inventory_native_multiple_products_preserve_baseline_and_stop(single_machine, monkeypatch, executions):
    client, state = single_machine
    vector_transport(client, state, monkeypatch, catalysts=0)
    result = await native.invoke(client, arguments(catalysts=0, executions=executions))
    assert result["status"] == "completed", result
    assert result["machineScope"]["commonBasicMachineAPIVerified"] is True
    assert result["acceptance"]["registeredCoreStopCoverage"] == "not_applicable_single_block"
    assert result["acceptance"]["allRegisteredCoresStopClaimed"] is False
    assert state["vector_input"][6]["count"] == 4 and state["vector_input"][7]["count"] == 5
    assert state["cursor"] is None and state["single_progress"] == state["single_max_progress"] == 0


async def test_multi_product_configured_ghost_retains_actual_zero_count_entry_without_physical_circuit_move(ghost_machine, monkeypatch):
    client, state = ghost_machine
    vector_transport(client, state, monkeypatch, consumed=1, catalysts=1)
    result = await native.invoke(client, arguments(consumed=1, catalystMode="configured_ghost"))
    assert result["status"] == "completed", result
    assert state["vector_input"][4] == native.stack(native.CATALYST, 0)
    assert not any(item["catalyst"] for item in result["nativeFeedBindings"])
    assert not any(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 4 for call in state["calls"])


@pytest.mark.parametrize("mutation,reason", [
    (lambda value: value["items"]["outputs"]["entries"][1].update(chance=5000), "probability_recipe_unsupported"),
    (lambda value: value["items"]["inputs"]["entries"][1]["stack"].update(hasTag=True), "item_nbt_or_identity_unavailable"),
    (lambda value: value["items"]["outputs"]["entries"][1]["stack"].update(registryName=native.PRODUCT["registryName"]), "multi_recipe_identity_overlap"),
    (lambda value: value.update(metadataUnknown=True), "recipe_conditions_unsupported"),
    (lambda value: value["fluids"].update(inputs=native.group([{"stack": {"fluidName": "hydrogen", "amount": 1000, "hasTag": False}}])), "probability_recipe_unsupported"),
])
def test_expansion_preserves_complete_raw_conditions_chance_and_tag_refusals(mutation, reason):
    value = candidate()
    mutation(value)
    with pytest.raises(_Unavailable) as caught:
        supported_multi_recipe(value, arguments())
    assert caught.value.details["reason"] == reason


def test_opt_in_schema_keeps_legacy_declarations_and_refuses_partial_output_set():
    validator = tool_validator("mcb_recipe_once")
    validator.validate(native.declaration())
    validator.validate(arguments())
    bad = arguments()
    bad.pop("recipeMode")
    assert not validator.is_valid(bad)
    bad = arguments(recipeLookup="catalog")
    assert not validator.is_valid(bad)
    with pytest.raises(_Unavailable) as caught:
        supported_multi_recipe(candidate(), {**arguments(), "outputItems": [native.PRODUCT]})
    assert caught.value.details["reason"] == "multi_output_recipe_mismatch"


@pytest.mark.parametrize("recover_existing", [False, True])
async def test_complete_recovery_returns_new_tools_and_preserves_preexisting_baseline_by_default(native_bridge, monkeypatch, recover_existing):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, catalysts=2, retained=True, baseline=False)
    result = await native.invoke(client, arguments(catalysts=2, recoverExistingCatalysts=recover_existing))
    assert result["status"] == "completed", result
    recovered = result["acceptance"]["recoveredCatalysts"]
    assert recovered == ([native.CATALYST, SECOND_CATALYST] if recover_existing else [SECOND_CATALYST])
    assert result["acceptance"]["completeRecovery"] is True
    assert native.CATALYST in result["acceptance"]["preservedCatalystBaseline"] if not recover_existing else not result["acceptance"]["preservedCatalystBaseline"]
    assert sum(item["count"] for item in state["player_items"].values() if item and item["registryName"] == SECOND_CATALYST["registryName"]) == 1


async def test_unknown_new_physical_catalyst_recovery_preserves_cursor_and_original_receipt(native_bridge, monkeypatch):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, consumed=1, outputs=1, catalysts=1, retained=False,
                     baseline=False, fault="unknown_catalyst_recovery")
    result = await native.invoke(client, arguments(consumed=1, outputs=1))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert result["lastActionReceipt"]["operation"] == "gui.slot_click"
    assert state["cursor"] == native.stack(native.CATALYST, 1)
    assert state["vector_power_count"] == state["vector_retrieval_count"] == 1
    # Source 0 is used once for the catalyst deposit, once for product pickup,
    # and once for the uncertain catalyst recovery pickup; none is replayed.
    assert len([call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 0]) == 3


async def test_new_physical_catalyst_recovery_uses_fresh_actual_owner_index_after_native_rearrangement(native_bridge, monkeypatch):
    client, state = native_bridge
    vector_transport(client, state, monkeypatch, consumed=1, outputs=1, retained=False, baseline=False)
    original = client.call
    async def rearrange(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.widget_action" and args.get("selected") == 1:
            state["vector_input"][3] = state["vector_input"][0]
            state["vector_input"][0] = None
        return result
    monkeypatch.setattr(client, "call", rearrange)
    result = await native.invoke(client, arguments(consumed=1, outputs=1))
    assert result["status"] == "completed", result
    recovered = [entry for entry in result["nativeRetrievals"] if entry["resource"] == "physical_catalyst"]
    assert len(recovered) == 1 and recovered[0]["sourceGuiSlot"] == 3
