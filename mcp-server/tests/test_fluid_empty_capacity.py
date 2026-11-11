"""Exact retained empty-shell evidence plus synthetic native transport regressions."""

import copy
import json
from pathlib import Path

import pytest

import test_recipe_tasks as native
import test_fluid_recipe_tasks as isolated
import test_shared_basic_fluid_tasks as shared
import test_read_queries as read_queries
from test_single_recipe_tasks import single_machine
from minecraft_codex_bridge.fluid_recipe_tasks import (EMPTY_CAPACITY_REASON, REGISTRY_CAPACITY_QUERY_SOURCE,
    FluidRecipeResources, container_semantics, public_stack)
from minecraft_codex_bridge.recipe_tasks import _Unavailable
from minecraft_codex_bridge.validation import tool_validator

native_bridge = native.native_bridge
bridge = read_queries.bridge
FIXTURE = json.loads((Path(__file__).parent / "fixtures/r17-435-returned-empty-container.json").read_text(encoding="utf-8"))
EXACT_TOOLTIP = FIXTURE["exactEmptyContainerTooltip"]["data"]
SECOND_FIXTURE = json.loads((Path(__file__).parent / "fixtures/r17-810-returned-empty-container.json").read_text(encoding="utf-8"))
JAVA_FIXTURE = json.loads((Path(__file__).parent / "fixtures/native-registry-empty-capacity-schema.json").read_text(encoding="utf-8"))


def semantics_tooltip(*, empty=True, source="public_fluid_container_registry", capacity=250, fluid="water", amount=250, count=1):
    item = {"registryName": "example:empty" if empty else "example:filled", "metadata": 7, "count": count, "hasTag": True}
    return {"item": item, "fluidContainer": {"schemaVersion": 1, "source": source,
        "scope": "observed_stack_copy_public_api_read", "itemHasTag": True, "probeMutatedCopy": False,
        "available": True, "empty": empty, "fluid": None if empty else {"fluidName": fluid, "amount": amount, "hasTag": False},
        "capacity": {"available": True, "value": capacity}}}


def registry_output_tooltip(capacity=250, *, fluid="water", count=1):
    tooltip = semantics_tooltip(capacity=capacity, count=count)
    tooltip["fluidContainer"]["capacity"].update(fluidName=fluid, source=REGISTRY_CAPACITY_QUERY_SOURCE)
    return tooltip


def check(row, semantics, *, initial):
    FluidRecipeResources.check_semantics(None, row, semantics, initial=initial)


def test_exact_r17_unknown_returned_shell_is_preserved_without_capacity_invention():
    assert FIXTURE["sourceArtifactSha256"] == "15e7c3b2fe69ceeb1a62df3e7d8b627814c7e14cae8f5323f229202cd5e027ff"
    assert FIXTURE["protectedRetainedState"]["meta"] == 435
    assert FIXTURE["exactFailedTaskResourceDiagnostics"]["cursorItem"] is None
    before = copy.deepcopy(EXACT_TOOLTIP)
    with pytest.raises(_Unavailable) as refused:
        container_semantics(EXACT_TOOLTIP, EXACT_TOOLTIP["item"])
    assert refused.value.details["reason"] == "fluid_container_capacity_unavailable"
    result = container_semantics(EXACT_TOOLTIP, EXACT_TOOLTIP["item"], allow_unknown_empty_capacity=True)
    assert result == EXACT_TOOLTIP["fluidContainer"]
    assert result["capacity"] == {"available": False, "reason": EMPTY_CAPACITY_REASON}
    row = {"direction": "input", "initialSemantics": {"capacity": {"available": True, "value": 1000}}}
    check(row, result, initial=False)
    result["capacity"]["reason"] = "modified returned copy"
    assert EXACT_TOOLTIP == before


def test_exact_r17_810_three_hydrogen_cells_return_known_empty_shell_and_modern_output_stays_actual():
    assert SECOND_FIXTURE["sourceArtifactSha256"] == "9d33dcaac1ef69023c0cee1503d4005f476b4385ef7ae24a13e37d9d38b9d705"
    assert SECOND_FIXTURE["exactReturnedInputTooltip"]["actualRpcIndex"] == 1752
    initial = SECOND_FIXTURE["exactInitialInputTooltip"]["call"]["result"]
    actual_output = SECOND_FIXTURE["exactInitialOutputTooltip"]["call"]["result"]
    returned = SECOND_FIXTURE["exactReturnedInputTooltip"]["call"]["result"]
    args = SECOND_FIXTURE["actualRecipeArguments"]
    input_row = {**args["fluidContainerInputs"][0], "direction": "input"}
    input_row["initialSemantics"] = container_semantics(initial, initial["item"])
    check(input_row, input_row["initialSemantics"], initial=True)
    semantics = container_semantics(returned, returned["item"], allow_unknown_empty_capacity=True)
    check(input_row, semantics, initial=False)
    assert public_stack(returned["item"]) == {"registryName": "IC2:itemCellEmpty", "metadata": 0, "count": 3, "hasTag": False}
    assert semantics == returned["fluidContainer"] and "value" not in semantics["capacity"]
    with pytest.raises(_Unavailable): container_semantics(returned, returned["item"])
    output_row = {**args["fluidContainerOutputs"][0], "direction": "output"}
    actual = container_semantics(actual_output, actual_output["item"])
    check(output_row, actual, initial=True)
    assert actual["source"] == "public_container_item_api" and actual["empty"] is True
    assert "source" not in actual["capacity"] and "fluidName" not in actual["capacity"]


@pytest.mark.parametrize("key", ["first", "other"])
def test_native_java_registry_query_dto_passes_exact_python_declared_fluid_proof(key):
    value = JAVA_FIXTURE[key]
    tooltip = semantics_tooltip(capacity=value["capacity"]["value"])
    tooltip["fluidContainer"] = copy.deepcopy(value)
    accepted = container_semantics(tooltip, tooltip["item"])
    row = {"direction": "output", "fluid": value["capacity"]["fluidName"], "count": 1,
           "amount": value["capacity"]["value"]}
    check(row, accepted, initial=True)
    assert accepted == value
    assert JAVA_FIXTURE["registryClassUnmodified"] is True


@pytest.mark.parametrize("allow_unknown", [False, True])
def test_native_java_zero_tuple_unknown_dto_never_uses_input_return_exception(allow_unknown):
    tooltip = semantics_tooltip()
    tooltip["fluidContainer"] = copy.deepcopy(JAVA_FIXTURE["zeroTuple"])
    assert tooltip["fluidContainer"]["empty"] is True and tooltip["fluidContainer"]["fluid"] is None
    with pytest.raises(_Unavailable) as refused:
        container_semantics(tooltip, tooltip["item"], allow_unknown_empty_capacity=allow_unknown)
    assert refused.value.details["reason"] == "fluid_container_capacity_unavailable"


def test_native_java_filled_registry_dto_retains_actual_contents_and_non_query_shape():
    value = JAVA_FIXTURE["filledUnchanged"]
    tooltip = semantics_tooltip(empty=False)
    tooltip["item"]["hasTag"] = False
    tooltip["fluidContainer"] = copy.deepcopy(value)
    accepted = container_semantics(tooltip, tooltip["item"])
    row = {"direction": "input", "fluid": value["fluid"]["fluidName"], "count": 1, "amount": value["fluid"]["amount"]}
    check(row, accepted, initial=True)
    assert accepted == value and set(accepted["capacity"]) == {"available", "value"}


@pytest.mark.parametrize("path,value", [
    (("fluidContainer", "source"), "public_container_item_api"),
    (("fluidContainer", "available"), False), (("fluidContainer", "empty"), False),
    (("fluidContainer", "fluid"), {}), (("fluidContainer", "fluid"), {"fluidName": "creosote", "amount": 1, "hasTag": False}),
    (("fluidContainer", "capacity", "reason"), "some_other_unknown"),
    (("fluidContainer", "capacity", "available"), 0), (("fluidContainer", "capacity", "available"), True),
    (("fluidContainer", "capacity", "value"), None), (("fluidContainer", "capacity", "value"), 1000),
    (("fluidContainer", "scope"), "registry_template"), (("fluidContainer", "schemaVersion"), True),
    (("fluidContainer", "itemHasTag"), True), (("fluidContainer", "probeMutatedCopy"), "false"),
    (("item", "count"), 2),
])
def test_returned_empty_exception_does_not_accept_other_unavailable_or_untyped_semantics(path, value):
    tooltip = copy.deepcopy(EXACT_TOOLTIP)
    target = tooltip
    for key in path[:-1]: target = target[key]
    target[path[-1]] = value
    with pytest.raises(_Unavailable):
        container_semantics(tooltip, EXACT_TOOLTIP["item"], allow_unknown_empty_capacity=True)


@pytest.mark.parametrize("key", ["fluid", "capacity", "empty"])
def test_returned_empty_exception_needs_explicit_complete_public_fields(key):
    tooltip = copy.deepcopy(EXACT_TOOLTIP)
    tooltip["fluidContainer"].pop(key)
    with pytest.raises(_Unavailable):
        container_semantics(tooltip, EXACT_TOOLTIP["item"], allow_unknown_empty_capacity=True)


@pytest.mark.parametrize("empty,source", [(False, "public_fluid_container_registry"), (False, "public_container_item_api"),
                                         (True, "public_container_item_api")])
def test_existing_actual_positive_capacity_semantics_remain_unchanged(empty, source):
    tooltip = semantics_tooltip(empty=empty, source=source)
    assert container_semantics(tooltip, tooltip["item"]) == tooltip["fluidContainer"]
    assert container_semantics(tooltip, tooltip["item"], allow_unknown_empty_capacity=True) == tooltip["fluidContainer"]
    assert "fluidName" not in tooltip["fluidContainer"]["capacity"]
    if empty:
        check({"direction": "output", "count": 1, "amount": 144, "fluid": "water"}, tooltip["fluidContainer"], initial=True)


@pytest.mark.parametrize("capacity,count,amount", [(250, 1, 144), (144, 1, 144), (2000, 1, 2000),
                                                  (250, 2, 500), (144, 3, 432), (2000, 2, 4000)])
def test_declared_output_fluid_uses_actual_queried_capacity_and_whole_stack_arithmetic(capacity, count, amount):
    tooltip = registry_output_tooltip(capacity, count=count)
    semantics = container_semantics(tooltip, tooltip["item"])
    check({"direction": "output", "fluid": "water", "count": count, "amount": amount}, semantics, initial=True)
    assert semantics["capacity"]["value"] == capacity


@pytest.mark.parametrize("count,amount", [(1, 251), (2, 144), (2, 499), (2, 501)])
def test_initial_output_capacity_never_permits_partial_multi_container_stacks(count, amount):
    tooltip = registry_output_tooltip(count=count)
    semantics = container_semantics(tooltip, tooltip["item"])
    with pytest.raises(_Unavailable) as refused:
        check({"direction": "output", "fluid": "water", "count": count, "amount": amount}, semantics, initial=True)
    assert refused.value.details["reason"] == "fluid_container_amount_mismatch"


@pytest.mark.parametrize("changes", [{"source": "some_other_getter"}, {"source": None}, {"fluidName": "oxygen"},
                                     {"fluidName": None}, {"fluidName": "Water"}, {"fluidName": "water "}])
def test_registry_output_requires_exact_public_query_source_and_declared_runtime_fluid(changes):
    tooltip = registry_output_tooltip()
    tooltip["fluidContainer"]["capacity"].update(changes)
    semantics = container_semantics(tooltip, tooltip["item"])
    with pytest.raises(_Unavailable) as refused:
        check({"direction": "output", "fluid": "water", "count": 1, "amount": 144}, semantics, initial=True)
    assert refused.value.details["reason"] == "fluid_container_capacity_query_unavailable"


@pytest.mark.parametrize("key", ["source", "fluidName"])
def test_registry_output_without_query_provenance_refuses_positive_capacity(key):
    tooltip = registry_output_tooltip()
    tooltip["fluidContainer"]["capacity"].pop(key)
    with pytest.raises(_Unavailable):
        check({"direction": "output", "fluid": "water", "count": 1, "amount": 144},
              container_semantics(tooltip, tooltip["item"]), initial=True)


@pytest.mark.parametrize("capacity", [False, 0, -1, 250.0, 2_147_483_648, None])
def test_queried_registry_capacity_must_remain_positive_finite_typed(capacity):
    tooltip = registry_output_tooltip(capacity)
    with pytest.raises(_Unavailable):
        container_semantics(tooltip, tooltip["item"])


@pytest.mark.parametrize("direction", ["input", "output"])
def test_available_transformed_capacity_still_must_equal_initial_capacity(direction):
    tooltip = semantics_tooltip(empty=direction == "input", capacity=250)
    row = {"direction": direction, "fluid": "water", "count": 1, "amount": 250,
           "initialSemantics": {"capacity": {"available": True, "value": 2000}}}
    with pytest.raises(_Unavailable) as refused:
        check(row, container_semantics(tooltip, tooltip["item"]), initial=False)
    assert refused.value.details["reason"] == "fluid_container_capacity_changed"


def test_unknown_empty_return_does_not_permit_filled_input_or_returned_output():
    value = container_semantics(EXACT_TOOLTIP, EXACT_TOOLTIP["item"], allow_unknown_empty_capacity=True)
    with pytest.raises(_Unavailable): check({"direction": "input"}, value, initial=True)
    with pytest.raises(_Unavailable): check({"direction": "output"}, value, initial=False)


@pytest.mark.parametrize("destination", [{"slot": 4}, {"x": 14, "y": 20}])
def test_tooltip_capacity_query_is_optional_and_preserves_slot_or_coordinate_schema(destination):
    validator = tool_validator("mcb_gui_tooltip")
    base = {"screenId": "screen:actual", "advanced": False, **destination}
    assert validator.is_valid(base)
    assert validator.is_valid({**base, "containerFluid": "creosote"})
    assert validator.is_valid({**base, "containerFluid": "x" * 128})
    assert not validator.is_valid({**base, "containerFluid": ""})
    assert not validator.is_valid({**base, "containerFluid": "x" * 129})
    assert not validator.is_valid({**base, "containerFluid": None})
    assert not validator.is_valid({**base, "containerFluid": 1000})
    assert not validator.is_valid({**base, "containerFluid": "water", "capacity": 1000})


@pytest.mark.parametrize("destination", [{"slot": 4}, {"x": 14, "y": 20}])
async def test_tooltip_query_reaches_one_actual_read_and_preserves_unavailable_capacity_without_fallback(bridge, destination):
    adapter, state = bridge
    args = {"screenId": "screen:actual", "advanced": False, "containerFluid": "creosote", **destination}
    state["data"]["gui.tooltip"] = copy.deepcopy(EXACT_TOOLTIP)
    result = await adapter.call_tool("mcb_gui_tooltip", args)
    assert not result.isError and result.structuredContent["data"] == EXACT_TOOLTIP
    assert read_queries.operations(state) == ["bridge.health", "gui.tooltip"]
    assert state["calls"][-1]["args"] == args
    assert result.structuredContent["data"]["fluidContainer"]["capacity"] == {"available": False, "reason": EMPTY_CAPACITY_REASON}


def wrapper(client, state, monkeypatch, *, shared_owner, fault=None):
    original = client.call
    state["capacity_tooltips"] = []
    async def call(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.tooltip":
            index = args["slot"] - (80 if shared_owner else 2)
            state["capacity_tooltips"].append({"args": copy.deepcopy(args), "index": index,
                                               "empty": result["fluidContainer"]["empty"]})
            value = result["fluidContainer"]
            if index == 0 and value["empty"]:
                value["capacity"] = copy.deepcopy(EXACT_TOOLTIP["fluidContainer"]["capacity"])
                if fault == "wrong_return_reason": value["capacity"]["reason"] = "public_api_failure"
                if fault == "foreign_return_fluid": value["fluid"] = {"fluidName": "oxygen", "amount": 1, "hasTag": False}
                if fault == "wrong_final_reason" and len([read for read in state["capacity_tooltips"] if read["index"] == 0 and read["empty"]]) == 2:
                    value["capacity"]["reason"] = "public_api_failure"
            if index == 1 and value["empty"] and fault in {"query_unavailable", "query_mismatch", "query_missing", "query_zero"}:
                if fault == "query_unavailable": value["capacity"] = {"available": False, "reason": "declared_registry_tuple_unavailable"}
                if fault == "query_mismatch": value["capacity"]["fluidName"] = "oxygen"
                if fault == "query_missing": value["capacity"].pop("source")
                if fault == "query_zero": value["capacity"]["value"] = 0
            if index == 1 and value["empty"] and fault == "query_changed_after_prepare" and len([read for read in state["capacity_tooltips"] if read["index"] == 1 and read["empty"]]) == 2:
                value["capacity"]["fluidName"] = "oxygen"
        return result
    monkeypatch.setattr(client, "call", call)


@pytest.mark.parametrize("tagged,fluid_only", [(False, False), (True, False), (False, True)])
async def test_isolated_whole_native_flow_preserves_unknown_empty_input_shell_and_complete_conservation(native_bridge, monkeypatch, tagged, fluid_only):
    client, state = native_bridge
    isolated.transport(client, state, monkeypatch, tagged=tagged, fluid_only=fluid_only)
    wrapper(client, state, monkeypatch, shared_owner=False)
    result = await native.invoke(client, isolated.declaration(fluid_only=fluid_only))
    assert result["status"] == "completed", result
    receipt = result["acceptance"]["fluidResources"]["containerTransfers"][0]
    assert receipt["returnedSemantics"]["capacity"] == EXACT_TOOLTIP["fluidContainer"]["capacity"]
    assert receipt["nativeActivations"] == 1 and receipt["unaffectedActualPlayerSlotsPreserved"]
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"]
    assert state["cursor"] is None and all("fluid" not in tank for tank in state["tanks"].values())
    assert state["player_items"][0] == {**isolated.EMPTY, "hasTag": tagged}
    assert result["acceptance"]["completeRecovery"] and result["acceptance"]["playerInventoryConserved"]
    for read in state["capacity_tooltips"]:
        assert ("containerFluid" in read["args"]) is (read["index"] == 1 and read["empty"])
        if "containerFluid" in read["args"]: assert read["args"]["containerFluid"] == "water"
    assert len([read for read in state["capacity_tooltips"] if read["index"] == 0 and read["empty"]]) == 2
    assert len([read for read in state["capacity_tooltips"] if read["index"] == 1 and read["empty"]]) == 2


@pytest.mark.parametrize("partial", [False, True])
async def test_shared_native_flow_preserves_unknown_shell_other_named_tank_and_machine_items(single_machine, monkeypatch, partial):
    client, state = single_machine
    shared.transport(client, state, monkeypatch, partial=partial)
    wrapper(client, state, monkeypatch, shared_owner=True)
    result = await native.invoke(client, shared.declaration(partial=partial))
    assert result["status"] == "completed", result
    receipts = result["acceptance"]["fluidResources"]["containerTransfers"]
    assert receipts[0]["returnedSemantics"]["capacity"] == EXACT_TOOLTIP["fluidContainer"]["capacity"]
    assert all(row["otherNamedTankAndMachineItemsPreserved"] for row in receipts)
    assert state["fluid_activations"] == ["input", "output"] and state["cursor"] is None
    assert state["named_state"] == {"input": (None, 0), "output": (None, 0)}
    assert all(item is None for item in state["vector_input"].values())
    assert result["acceptance"]["completeRecovery"] and result["acceptance"]["playerInventoryConserved"]
    for read in state["capacity_tooltips"]:
        assert ("containerFluid" in read["args"]) is (read["index"] == 1 and read["empty"])
    assert len([read for read in state["capacity_tooltips"] if read["index"] == 0 and read["empty"]]) == 2
    assert len([read for read in state["capacity_tooltips"] if read["index"] == 1 and read["empty"]]) == 2


@pytest.mark.parametrize("fault", ["query_unavailable", "query_mismatch", "query_missing", "query_zero"])
async def test_output_missing_or_wrong_capacity_evidence_stops_before_any_native_pickup_or_activation(single_machine, monkeypatch, fault):
    client, state = single_machine
    shared.transport(client, state, monkeypatch)
    wrapper(client, state, monkeypatch, shared_owner=True, fault=fault)
    result = await native.invoke(client, shared.declaration())
    assert result["status"] != "completed", result
    assert result["reason"] in {"fluid_container_capacity_unavailable", "fluid_container_capacity_query_unavailable"}
    assert state["fluid_activations"] == [] and state["vector_power_count"] == 0 and state["cursor"] is None
    assert not any(read["operation"] == "gui.slot_click" for read in state["calls"])


@pytest.mark.parametrize("fault", ["wrong_return_reason", "foreign_return_fluid"])
async def test_unknown_return_exception_does_not_continue_when_original_empty_proof_is_wrong(single_machine, monkeypatch, fault):
    client, state = single_machine
    shared.transport(client, state, monkeypatch)
    wrapper(client, state, monkeypatch, shared_owner=True, fault=fault)
    result = await native.invoke(client, shared.declaration())
    assert result["status"] != "completed" and result["reason"] == "fluid_container_capacity_unavailable", result
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 0
    assert state["cursor"] is None and result["resourceDiagnostics"]["available"]


async def test_output_capacity_query_is_freshly_revalidated_before_native_output_pickup(single_machine, monkeypatch):
    client, state = single_machine
    shared.transport(client, state, monkeypatch)
    wrapper(client, state, monkeypatch, shared_owner=True, fault="query_changed_after_prepare")
    result = await native.invoke(client, shared.declaration())
    assert result["status"] != "completed" and result["reason"] == "fluid_container_capacity_query_unavailable", result
    assert state["fluid_activations"] == ["input"] and state["vector_power_count"] == 1
    assert state["cursor"] is None and state["player_items"][1]["registryName"] == "IC2:itemCellEmpty"
    assert not any(read["operation"] == "gui.slot_click" and read["args"]["slot"] == 81 for read in state["calls"])


async def test_final_input_shell_still_requires_fresh_explicit_known_empty_proof(single_machine, monkeypatch):
    client, state = single_machine
    shared.transport(client, state, monkeypatch)
    wrapper(client, state, monkeypatch, shared_owner=True, fault="wrong_final_reason")
    result = await native.invoke(client, shared.declaration())
    assert result["status"] != "completed" and result["reason"] == "fluid_container_capacity_unavailable", result
    assert state["fluid_activations"] == ["input", "output"] and state["vector_power_count"] == 1
    assert state["cursor"] is None and state["named_state"] == {"input": (None, 0), "output": (None, 0)}
