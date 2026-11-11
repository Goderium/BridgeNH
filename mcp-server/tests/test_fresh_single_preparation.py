"""New idle machines need actual blank-ghost and voltage evidence before feeding."""

import copy

import pytest

import test_recipe_tasks as native
from test_single_recipe_tasks import single_machine, arguments as single_arguments
from test_ghost_recipe_tasks import ghost_machine, arguments as ghost_arguments

native_bridge = native.native_bridge


@pytest.mark.parametrize("target", [1, 7])
async def test_fresh_blank_ghost_normal_clicks_confirm_each_value_and_server_inventory(ghost_machine, target):
    client, state = ghost_machine
    native.CATALYST["metadata"] = target
    state["recipe"]["items"]["inputs"]["entries"][1]["stack"]["metadata"] = target
    state["missing_ghost_inventory"] = True
    state["ghost_widget"]["circuitConfig"].update(value=-1, noConfiguration=True)
    result = await native.invoke(client, ghost_arguments())
    assert result["status"] == "completed", result
    ghost_actions = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("widgetId") == state["ghost_widget"]["widgetId"]]
    assert len(ghost_actions) == target and all(call["args"]["action"] == "activate" for call in ghost_actions)
    assert result["acceptance"]["configuredGhostCircuit"]["configurationMutated"] is True
    assert result["acceptance"]["configuredGhostCircuit"]["configuration"] == target
    assert result["acceptance"]["configuredGhostCircuit"]["count"] == 0
    assert state["circuit"] == 3 and state["input_circuit"] == 0
    assert all(call["args"]["slot"] != 4 for call in state["calls"] if call["operation"] == "gui.slot_click")
    preflight = next(phase for phase in result["phases"] if phase["phase"] == "configured_ghost_preflight")
    assert preflight["stableServerReads"] == 2 and preflight["serverConfigurationConfirmed"] is True


@pytest.mark.parametrize("problem", ["unknown_api", "nonblank", "uncertain", "ignored"])
async def test_fresh_blank_ghost_refuses_unknown_or_unconfirmed_native_sync_without_feed(ghost_machine, problem):
    client, state = ghost_machine
    state["missing_ghost_inventory"] = True
    state["ghost_widget"]["circuitConfig"].update(value=-1, noConfiguration=True)
    if problem == "unknown_api":
        state["ghost_widget"]["circuitConfig"]["api"] = "Unknown.guess()"
    elif problem == "nonblank":
        state["ghost_widget"]["circuitConfig"].update(value=2, noConfiguration=False)
    else:
        state["ghost_native_" + problem] = True
    result = await native.invoke(client, ghost_arguments(timeout_ms=1000))
    assert result["status"] == "failed", result
    ghost_actions = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("widgetId") == state["ghost_widget"]["widgetId"]]
    assert len(ghost_actions) == (1 if problem in ("uncertain", "ignored") else 0)
    assert not any(call["operation"] == "gui.slot_click" or call["operation"] == "chat.send"
        or call["operation"] == "gui.widget_action" and call["args"].get("selected") == 1 for call in state["calls"])
    if problem == "uncertain":
        assert result["outcomeUnknown"] is True and result["lastActionReceipt"]["operation"] == "gui.widget_action"


@pytest.mark.parametrize("voltage", [None, False, 0, -1, {"available": False}])
async def test_basic_voltage_requires_real_typed_positive_public_evidence(single_machine, voltage):
    client, state = single_machine
    state["single_input_voltage"] = voltage
    result = await native.invoke(client, single_arguments())
    assert result["status"] == "unavailable" and result["reason"] == "single_machine_input_voltage_unavailable", result
    assert native.writes(state) == []


async def test_over_voltage_candidate_is_rejected_before_preparation_and_feed(single_machine):
    client, state = single_machine
    state["recipe"]["rawEUt"] = 8193
    result = await native.invoke(client, single_arguments())
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "single_machine_recipe_voltage_exceeded"
    assert native.writes(state) == []


async def test_public_multi_amp_map_retains_native_voltage_semantics(single_machine):
    client, state = single_machine
    state["single_map_amperage"] = 4
    state["recipe"]["rawEUt"] = 32000
    result = await native.invoke(client, single_arguments())
    assert result["status"] == "completed", result


async def test_over_voltage_continuation_selects_later_real_eligible_candidate(single_machine, monkeypatch):
    client, state = single_machine
    original, pages = client.call, 0

    async def paged(operation, args, **options):
        nonlocal pages
        result = await original(operation, args, **options)
        if operation == "recipes.query":
            pages += 1
            if pages == 1:
                result = copy.deepcopy(result)
                result["recipes"][0]["rawEUt"] = 8193
                result.update(hasMore=True, complete=False, nextCursor="fresh-eligible-page")
        return result

    monkeypatch.setattr(client, "call", paged)
    result = await native.invoke(client, single_arguments())
    assert result["status"] == "completed" and pages == 2, result
    decision = next(phase for phase in result["phases"] if phase["phase"] == "recipe_query")
    assert decision["rejectedReasons"] == ["single_machine_recipe_voltage_exceeded"]


async def test_execution_failure_retains_bounded_selected_raw_recipe_and_preflight_voltage(single_machine):
    client, state = single_machine
    state["power_error"] = "disconnect"
    result = await native.invoke(client, single_arguments(includeDetails=False))
    assert result["status"] == "failed", result
    selected = result["selectedRecipe"]
    assert selected["rawEUt"] == state["recipe"]["rawEUt"]
    assert selected["rawDurationTicks"] == state["recipe"]["rawDurationTicks"]
    assert selected["material"] == native.MATERIAL and selected["output"] == native.PRODUCT
    assert selected["voltageEvidence"]["actualInputVoltage"] == 8192
    assert selected["voltageEvidence"]["mapAmperage"] == 1
    assert selected["candidatePage"] == 1 and selected["machine"] == {**native.MACHINE, "dimension": 180}
