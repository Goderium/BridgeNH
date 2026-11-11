"""Existing finite recipe feeds accept one pinned queried local provider delta plus real stopped inputs."""

import copy

import pytest

import test_recipe_tasks as native
from test_provider_counts import OWNER, observation
from minecraft_codex_bridge.provider_counts import LONG_MAX

native_bridge = native.native_bridge


@pytest.fixture
async def provider_bridge(native_bridge, monkeypatch):
    client, state = native_bridge
    owner = {**OWNER, **native.OUTPUT}
    state.update(provider_base=0, provider_queries=[], provider_hook=None, provider_inventory_hook=None)
    original = client.call

    async def actual_provider(operation, args, **options):
        data = await original(operation, args, **options)
        if operation == "machine.inspect":
            if "outputQueries" in args:
                state["provider_queries"].append(copy.deepcopy(args))
                assert args["outputQueries"] == [{"kind": "item", **native.PRODUCT}]
                data = observation(state["provider_base"] + state["output"] - 4)
                data.update(owner)
                query = data["outputStorage"]["resourceQuery"]
                query["owner"] = owner.copy()
                query["entries"][0]["item"] = {**native.PRODUCT, "hasTag": False}
                if state["provider_hook"]:
                    state["provider_hook"](data, state)
            else:
                for entry in data["components"]["entries"]:
                    if entry["role"] == "item_output":
                        entry.update(owner)
        if operation == "world.inspect" and args == {**native.OUTPUT, "dimension": 180}:
            data.update(source="integrated_server", **native.OUTPUT, dimension=180,
                registryName="gregtech:gt.blockmachines", gregtech={"metaTileId": owner["metaTileId"], "metaClass": owner["metaClass"]},
                inventory={"slots": [{"index": 0, "item": None}], "totalSlots": 1, "truncated": False})
            if state["provider_inventory_hook"]:
                state["provider_inventory_hook"](data, state)
        return data

    monkeypatch.setattr(client, "call", actual_provider)
    yield client, state


def declaration(**changes):
    return native.declaration(outputMode="provider_cache", **changes)


def power_ons(state):
    return [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("selected") == 1]


@pytest.mark.parametrize("baseline,executions", [(0, 1), (123, 2), (2**53 + 1, 2), (LONG_MAX - 1, 1)])
async def test_existing_recipe_once_combines_exact_local_product_delta_with_real_inputs_and_stop(provider_bridge, baseline, executions):
    client, state = provider_bridge
    state["provider_base"] = baseline
    result = await native.invoke(client, declaration(executions=executions))
    assert result["status"] == "completed", result
    acceptance = result["acceptance"]
    assert acceptance["outputDelta"] == executions and acceptance["consumed"] == 8 * executions
    assert acceptance["catalystRetained"] and acceptance["controllerStopped"] and acceptance["exposedRegisteredCoresStopped"]
    assert acceptance["outputMode"] == "provider_cache" and acceptance["outputScope"] == "local_output_provider_cache_only"
    assert acceptance["outputBaseline"] == str(baseline) and acceptance["outputFinal"] == str(baseline + executions)
    assert acceptance["outputOwner"] == {**OWNER, **native.OUTPUT} and acceptance["outputSessionId"] == "session"
    assert acceptance["queriedIdentity"] == {"kind": "item", **native.PRODUCT, "hasTag": False}
    assert acceptance["queriedIdentitySubsetComplete"] and acceptance["outputInventoryEmpty"]
    assert "offline" not in acceptance and "networkInventory" not in acceptance
    assert len(power_ons(state)) == executions and len(state["queries"]) == 1
    assert state["input_material"] == 0 and state["input_circuit"] == 1 and state["cursor"] is None
    assert result["inventoryPreparation"]["finalInventoryVerified"]


@pytest.mark.parametrize("baseline,executions", [(LONG_MAX, 1), (LONG_MAX - 1, 2)])
async def test_all_output_overflow_refuses_before_any_material_grant_or_feed(provider_bridge, baseline, executions):
    client, state = provider_bridge
    state["provider_base"] = baseline
    result = await native.invoke(client, declaration(executions=executions, generateMaterials=True))
    assert result["reason"] == "provider_output_quantity_overflow", result
    assert native.writes(state) == [] and result["materialGrantsDispatched"] == 0 and state["input_material"] == 0


@pytest.mark.parametrize("fault", ["missing", "unavailable", "incomplete", "quantity", "item", "owner", "session"])
async def test_bad_initial_provider_evidence_never_generates_or_feeds(provider_bridge, fault):
    client, state = provider_bridge
    def fail(data, _):
        query = data["outputStorage"]["resourceQuery"]
        entry = query["entries"][0]
        if fault == "missing":
            data["outputStorage"].pop("resourceQuery")
        elif fault == "unavailable":
            entry["available"] = False
            entry.pop("cachedAmount")
        elif fault == "incomplete":
            query["complete"] = False
        elif fault == "quantity":
            entry["cachedAmount"] = -1
        elif fault == "item":
            entry["item"]["registryName"] = "minecraft:gold_ingot"
        elif fault == "owner":
            query["owner"]["metaTileId"] += 1
        else:
            data["sessionId"] = "replacement"
    state["provider_hook"] = fail
    result = await native.invoke(client, declaration(generateMaterials=True))
    assert result["status"] != "completed" and native.writes(state) == [], result
    assert result["materialGrantsDispatched"] == 0 and len(state["provider_queries"]) == 1


@pytest.mark.parametrize("cell", ["present", "incomplete"])
async def test_actual_empty_cell_inventory_is_required_before_provider_acceptance(provider_bridge, cell):
    client, state = provider_bridge
    def fail(data, _):
        if cell == "present":
            data["inventory"]["slots"][0]["item"] = native.stack({"registryName": "appliedenergistics2:cell", "metadata": 0}, 1)
        else:
            data["inventory"]["truncated"] = True
    state["provider_inventory_hook"] = fail
    result = await native.invoke(client, declaration(generateMaterials=True))
    assert result["status"] != "completed" and native.writes(state) == [] and state["provider_queries"] == [], result


@pytest.mark.parametrize("when", [2, 3, 4])
async def test_baseline_drift_before_feed_or_power_never_replays_or_powers(provider_bridge, when):
    client, state = provider_bridge
    def drift(data, state):
        if len(state["provider_queries"]) == when:
            data["outputStorage"]["resourceQuery"]["entries"][0]["cachedAmount"] = "1"
    state["provider_hook"] = drift
    result = await native.invoke(client, declaration())
    assert result["status"] != "completed" and power_ons(state) == [], result
    assert len(state["queries"]) == 1 and result["materialGrantsDispatched"] == 0
    assert state["input_material"] == (0 if when == 2 else 8)
    if when > 2:
        assert "remainingInputs" in result


@pytest.mark.parametrize("fault", ["owner", "decrease", "overshoot", "unavailable"])
async def test_proof_loss_or_bad_growth_after_power_cannot_complete_or_start_second_execution(provider_bridge, fault):
    client, state = provider_bridge
    state["provider_base"] = 10
    def fail(data, state):
        if state["entered_power"].is_set():
            query = data["outputStorage"]["resourceQuery"]
            if fault == "owner":
                data["metaClass"] = query["owner"]["metaClass"] = "replacement.Provider"
            elif fault == "decrease":
                query["entries"][0]["cachedAmount"] = "9"
            elif fault == "overshoot":
                query["entries"][0]["cachedAmount"] = "12"
            else:
                query["entries"][0]["available"] = False
    state["provider_hook"] = fail
    result = await native.invoke(client, declaration(executions=2))
    assert result["status"] != "completed" and result["executionsCompleted"] == 0, result
    assert len(power_ons(state)) == 1 and len(state["queries"]) == 1
    assert state["input_material"] == 0 and state["cursor"] is None


@pytest.mark.parametrize("fault", ["bad_input_delta", "bad_catalyst"])
async def test_correct_product_cache_growth_does_not_replace_actual_input_or_catalyst_acceptance(provider_bridge, fault):
    client, state = provider_bridge
    state[fault] = True
    result = await native.invoke(client, declaration())
    assert result["reason"] == "input_or_catalyst_acceptance_failed" and result["executionsCompleted"] == 0, result
    assert len(power_ons(state)) == 1 and len(state["queries"]) == 1


async def test_wrong_product_aggregate_growth_without_queried_growth_is_not_production(provider_bridge):
    client, state = provider_bridge
    def wrong_product(data, state):
        data["outputStorage"]["cachedAmount"] = str(999999 + state["output"])
        data["outputStorage"]["resourceQuery"]["entries"][0]["cachedAmount"] = "0"
    state["provider_hook"] = wrong_product
    result = await native.invoke(client, declaration(timeout_ms=1000))
    assert result["status"] != "completed" and result["executionsCompleted"] == 0 and len(power_ons(state)) == 1, result


async def test_final_flush_after_initial_delta_and_core_stop_cannot_be_accepted(provider_bridge):
    client, state = provider_bridge
    def final_flush(data, state):
        if len(state["provider_queries"]) >= 6:
            data["outputStorage"]["resourceQuery"]["entries"][0]["cachedAmount"] = "0"
    state["provider_hook"] = final_flush
    result = await native.invoke(client, declaration())
    assert result["reason"] == "provider_final_delta_mismatch" and result["executionsCompleted"] == 0, result
    assert len(power_ons(state)) == 1


async def test_provider_loss_after_first_accepted_execution_blocks_next_feed_and_preserves_player_material(provider_bridge):
    client, state = provider_bridge
    def lost(data, state):
        if len(state["provider_queries"]) >= 7:
            data["outputStorage"]["resourceQuery"]["complete"] = False
    state["provider_hook"] = lost
    result = await native.invoke(client, declaration(executions=2))
    assert result["status"] != "completed" and result["executionsCompleted"] == 1, result
    assert len(power_ons(state)) == 1 and state["input_material"] == 0
    assert state["material"] == 24 and state["cursor"] is None and result["materialGrantsDispatched"] == 0


async def test_provider_cache_does_not_enable_single_inventory_or_backend_scope(native_bridge):
    client, state = native_bridge
    result = await native.invoke(client, declaration(input=native.MACHINE, output=native.MACHINE))
    assert result["reason"] == "provider_machine_scope_unsupported" and native.writes(state) == [] and state["queries"] == []
