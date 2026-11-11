"""Empty native query results retain the requested product/material evidence."""
import copy
import json
from pathlib import Path

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, supported_recipe
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


def recorded_query():
    return json.loads((Path(__file__).parent / "fixtures/vacuum-filtered-query.json").read_text(encoding="utf-8"))["data"]


@pytest.mark.parametrize("include_details", [False, True])
async def test_empty_query_reports_requested_product_filter_and_observed_complete_receipt_without_native_action(native_bridge, include_details):
    client, state = native_bridge
    calls = []
    wrong_product = {"registryName": "gregtech:gt.blockmachines", "metadata": 81}
    material = {"registryName": "gregtech:gt.metaitem.01", "metadata": 12316}
    async def query_reader(tracked, args):
        calls.append(copy.deepcopy(args))
        return {"dimension": 180, "filterIncomplete": False, "recipes": [], "complete": True, "hasMore": False}
    result = await native.invoke(client, native.declaration(outputItem=wrong_product, material=material,
        includeDetails=include_details, generateMaterials=True), query_reader=query_reader)
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    error = result["error"]
    assert error["rejectedRecipeCandidates"] == [] and error["observedCandidateCount"] == 0
    assert error["queryRequested"] == {"machine": {**native.MACHINE, "dimension": 180},
        "input": {"source": "registry", **material}, "output": {"source": "registry", **wrong_product}}
    assert error["lastQueryPage"] == {"page": 1, "observedCandidateCount": 0, "complete": True, "hasMore": False}
    assert "query returned no candidates" in error["message"] and not error["message"].endswith(": ")
    assert len(calls) == 1 and calls[0]["output"] == {"source": "registry", **wrong_product}
    assert native.writes(state) == [] and result["materialGrantsDispatched"] == 0
    if include_details:
        query = next(phase for phase in result["phases"] if phase["phase"] == "recipe_query")
        assert query["queryRequested"] == error["queryRequested"] and query["lastQueryPage"] == error["lastQueryPage"]
    else:
        assert "phases" not in result


async def test_empty_bounded_pages_do_not_claim_complete_absence_or_lose_original_filter_on_cursor(native_bridge):
    client, state = native_bridge
    calls = []
    async def query_reader(tracked, args):
        calls.append(copy.deepcopy(args))
        return {"dimension": 180, "filterIncomplete": False, "recipes": [], "complete": False,
            "hasMore": True, "nextCursor": "bounded-synthetic-cursor"}
    args = native.declaration(maxRecipePages=2, includeDetails=False)
    args.pop("material")
    result = await native.invoke(client, args, query_reader=query_reader)
    error = result["error"]
    assert error["queryRequested"]["input"] is None
    assert error["queryRequested"]["output"] == {"source": "registry", **native.PRODUCT}
    assert error["lastQueryPage"] == {"page": 2, "observedCandidateCount": 0, "complete": False, "hasMore": True}
    assert error["observedCandidateCount"] == 0 and "complete absence" not in error["message"]
    assert calls[1] == {"cursor": "bounded-synthetic-cursor", "dimension": 180, "limit": 16}
    assert native.writes(state) == []


async def test_unknown_complete_flag_is_reported_unknown_not_inferred_true(native_bridge):
    client, state = native_bridge
    async def query_reader(tracked, args):
        return {"dimension": 180, "filterIncomplete": False, "recipes": [], "hasMore": False}
    result = await native.invoke(client, query_reader=query_reader)
    assert result["error"]["lastQueryPage"]["complete"] is None
    assert result["error"]["lastQueryPage"]["hasMore"] is False and native.writes(state) == []


async def test_recorded_multiblock_raw_recipe_remains_supported_and_filters_are_unchanged(native_bridge):
    client, state = native_bridge
    data = recorded_query()
    output = {"registryName": "gregtech:gt.metaitem.01", "metadata": 11316}
    material = {"registryName": "gregtech:gt.metaitem.01", "metadata": 12316}
    point = {key: data["machine"][key] for key in ("x", "y", "z")}
    args = native.declaration(expectedSessionId=data["sessionId"], machine=point,
        input={"x": point["x"], "y": point["y"] - 1, "z": point["z"]},
        output={"x": point["x"] - 1, "y": point["y"] - 1, "z": point["z"]}, outputItem=output, material=material)
    queries = []
    async def query_reader(tracked, query):
        queries.append(copy.deepcopy(query))
        return data
    task = _RecipeTask(client, args, run_workflow, query_reader)
    task.machine_class = data["machine"]["metaClass"]
    selected = await task.select_recipe()
    assert not task.single_inventory and selected == supported_recipe(data["recipes"][0], args)
    assert selected.raw_eut == 1920 and selected.raw_duration_ticks == 357
    assert queries == [{**point, "dimension": 180, "output": {"source": "registry", **output},
        "input": {"source": "registry", **material}, "limit": 16}]
    assert state["calls"] == []
