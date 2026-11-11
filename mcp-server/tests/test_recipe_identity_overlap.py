"""Shared item identities cannot substitute retained catalysts for produced output."""

import copy

import pytest

import test_recipe_tasks as native
from test_single_recipe_tasks import single_machine, arguments
from test_recipe_optimization import backend_transport
from minecraft_codex_bridge.recipe_tasks import _Unavailable, supported_recipe

native_bridge = native.native_bridge


def catalyst_output_overlap(candidate):
    candidate = copy.deepcopy(candidate)
    entries = candidate["items"]["inputs"]["entries"]
    entries[:] = [entry for entry in entries if not entry["nonConsumedCatalyst"]]
    entries.append({"index": len(entries), "stack": native.stack(native.PRODUCT, 0),
        "chance": 10000, "nonConsumedCatalyst": True})
    candidate["items"]["inputs"]["total"] = len(entries)
    return candidate


@pytest.mark.parametrize("overlap", ["material", "catalyst"])
def test_shared_candidate_rejects_identity_overlap_during_recipe_selection(overlap):
    candidate = native.recipe(wildcard=False)
    coordinate = {"x": 0, "y": 2, "z": 0}
    request = native.declaration(machine=coordinate, input=coordinate, output=coordinate)
    if overlap == "material":
        candidate["items"]["outputs"]["entries"][0]["stack"] = native.stack(native.MATERIAL, 1)
        request["outputItem"] = native.MATERIAL
    else:
        candidate = catalyst_output_overlap(candidate)
    with pytest.raises(_Unavailable) as failure:
        supported_recipe(candidate, request)
    assert failure.value.details["reason"] == "shared_inventory_recipe_identity_overlap"


@pytest.mark.parametrize("retained", [False, True])
@pytest.mark.parametrize("backend", [False, True])
async def test_shared_catalyst_output_overlap_refuses_before_grants_or_transfers(single_machine, monkeypatch, retained, backend):
    client, state = single_machine
    monkeypatch.setitem(native.__dict__, "CATALYST", native.PRODUCT.copy())
    state["recipe"] = catalyst_output_overlap(state["recipe"])
    state["input_circuit"] = int(retained)
    request = arguments(generateMaterials=True)
    if backend:
        backend_transport(client, monkeypatch)
        request.update(recipeLookup="backend_match", backendInputs=[
            {"source": "registry", **native.MATERIAL, "count": 1},
            {"source": "registry", **native.PRODUCT, "count": 1}])
    result = await native.invoke(client, request)
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "shared_inventory_recipe_identity_overlap"
    assert native.writes(state) == [] and result["sideEffectsPossible"] is False
    assert result["materialGrantsDispatched"] == result["executionsCompleted"] == 0
    assert state["input_circuit"] == int(retained) and state["output"] == 4
    assert len(state["queries"]) == 1 and "acceptance" not in result


@pytest.mark.parametrize("later_page", [False, True])
async def test_catalog_skips_shared_overlap_for_later_supported_candidate(single_machine, later_page):
    client, state = single_machine
    invalid = catalyst_output_overlap(state["recipe"])
    query_count = 0

    async def query_reader(tracked, query):
        nonlocal query_count
        data = await tracked.call("recipes.query", query)
        query_count += 1
        if later_page and query_count == 1:
            data.update(recipes=[invalid], hasMore=True, nextCursor="next-one-use", complete=False)
        else:
            data.update(recipes=[state["recipe"]] if later_page else [invalid, state["recipe"]],
                hasMore=False, nextCursor=None, complete=True)
        return data

    result = await native.invoke(client, arguments(), query_reader=query_reader)
    assert result["status"] == "completed" and result["acceptance"]["outputDelta"] == 4, result
    assert result["acceptance"]["catalystRetained"] is False and state["output"] == 8
    assert query_count == len(state["queries"]) == (2 if later_page else 1)
    selection = next(phase for phase in result["phases"] if phase["phase"] == "recipe_query")
    assert selection["rejectedReasons"] == ["shared_inventory_recipe_identity_overlap"]


async def test_separate_registered_ports_keep_catalyst_output_identity_accounting_independent(native_bridge, monkeypatch):
    client, state = native_bridge
    monkeypatch.setitem(native.__dict__, "CATALYST", native.PRODUCT.copy())
    state["recipe"] = catalyst_output_overlap(state["recipe"])
    result = await native.invoke(client)
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputDelta"] == 1 and result["acceptance"]["consumed"] == 8
    assert result["acceptance"]["catalystRetained"] is True
    assert state["input_circuit"] == 1 and state["output"] == 5 and state["input_material"] == 0
