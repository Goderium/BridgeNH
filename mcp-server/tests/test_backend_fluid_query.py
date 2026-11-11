"""Backend fluid declarations are bounded, exact and never replayed as catalog queries."""
import copy

import pytest

from test_read_queries import bridge, operations
from test_recipe_optimization import arguments as once_arguments


POINT = {"x": 0, "y": 64, "z": 0, "dimension": 180}
ITEM = {"source": "registry", "registryName": "Railcraft:fluid.creosote.cell", "metadata": 0, "count": 1}
FLUID = {"fluid": "creosote", "amount": 1000}


def declaration(**extra):
    return {"mode": "backend_match", **POINT, **extra}


@pytest.mark.parametrize("args", [
    declaration(inputs=[ITEM]),
    declaration(inputs=[ITEM], fluidInputs=[]),
    declaration(fluidInputs=[FLUID]),
    declaration(inputs=[], fluidInputs=[FLUID]),
    declaration(inputs=[ITEM], fluidInputs=[FLUID]),
    declaration(fluidInputs=[{"fluid": "Observed.Case_Sensitive-Fluid", "amount": 2_147_483_647}]),
    declaration(inputs=[ITEM] * 8, fluidInputs=[FLUID] * 8),
    declaration(fluidInputs=[FLUID], fluids=[], specialSlot=None),
])
async def test_backend_templates_forward_once_unchanged_without_takeover(bridge, args):
    adapter, state = bridge
    original = copy.deepcopy(args)
    result = await adapter.call_tool("mcb_recipe_query", args)
    assert not result.isError, result
    assert operations(state) == ["bridge.health", "recipes.query"]
    assert state["calls"][1]["args"] == original == args
    assert result.structuredContent["data"]["args"] == original
    assert "client.background" not in operations(state)


@pytest.mark.parametrize("args", [
    declaration(),
    declaration(inputs=[]),
    declaration(fluidInputs=[]),
    declaration(inputs=[], fluidInputs=[]),
    declaration(inputs=None, fluidInputs=[FLUID]),
    declaration(inputs=[ITEM], fluidInputs=None),
    declaration(inputs="items", fluidInputs=[FLUID]),
    declaration(inputs=[ITEM], fluidInputs="creosote"),
    declaration(inputs=[ITEM] * 9, fluidInputs=[FLUID]),
    declaration(inputs=[ITEM], fluidInputs=[FLUID] * 9),
    declaration(inputs=[{**ITEM, "count": 0}], fluidInputs=[FLUID]),
    declaration(inputs=[{**ITEM, "count": 65}], fluidInputs=[FLUID]),
    declaration(inputs=[{**ITEM, "count": True}], fluidInputs=[FLUID]),
    declaration(inputs=[{**ITEM, "nbt": {}}], fluidInputs=[FLUID]),
    declaration(fluidInputs=[{"amount": 1000}]),
    declaration(fluidInputs=[{"fluid": "creosote"}]),
    declaration(fluidInputs=[{**FLUID, "fluid": ""}]),
    declaration(fluidInputs=[{**FLUID, "fluid": "x" * 129}]),
    declaration(fluidInputs=[{**FLUID, "fluid": None}]),
    declaration(fluidInputs=[{**FLUID, "fluid": True}]),
    declaration(fluidInputs=[{**FLUID, "nbt": {}}]),
    declaration(fluidInputs=[{**FLUID, "hasTag": False}]),
    declaration(fluidInputs=[{**FLUID, "source": "registry"}]),
    declaration(fluidInputs=[{**FLUID, "metadata": 0}]),
    declaration(fluidInputs=[{**FLUID, "registryName": "minecraft:water"}]),
    declaration(fluidInputs=[{**FLUID, "fluidName": "creosote"}]),
    declaration(fluidInputs=[{**FLUID, "amount": 0}]),
    declaration(fluidInputs=[{**FLUID, "amount": -1}]),
    declaration(fluidInputs=[{**FLUID, "amount": 2_147_483_648}]),
    declaration(fluidInputs=[{**FLUID, "amount": True}]),
    declaration(fluidInputs=[{**FLUID, "amount": 1000.5}]),
    declaration(fluidInputs=[{**FLUID, "amount": "1000"}]),
    declaration(fluidInputs=[{**FLUID, "amount": None}]),
    declaration(fluidInputs=[{**FLUID, "amount": float("inf")}]),
    declaration(fluidInputs=[{**FLUID, "amount": float("nan")}]),
    declaration(fluidInputs=[FLUID], fluids=[FLUID]),
    declaration(fluidInputs=[FLUID], fluids=None),
    declaration(fluidInputs=[FLUID], specialSlot={}),
    declaration(fluidInputs=[FLUID], map="gt.recipe.fluidcanner"),
    declaration(fluidInputs=[FLUID], input={"source": "registry", "registryName": "minecraft:bucket"}),
    declaration(fluidInputs=[FLUID], limit=1),
    declaration(fluidInputs=[FLUID], maxPages=2),
    declaration(fluidInputs=[FLUID], cursor="next"),
    declaration(fluidInputs=[FLUID], unknown=True),
    {**POINT, "fluidInputs": [FLUID]},
    {"map": "gt.recipe.fluidcanner", "fluidInputs": [FLUID]},
    {"map": "gt.recipe.fluidcanner", "fluidInputs": []},
    {"cursor": "next", "fluidInputs": [FLUID]},
    {"cursor": "next", "fluidInputs": []},
    {**declaration(fluidInputs=[FLUID]), "mode": "catalog"},
])
async def test_invalid_or_other_mode_fluid_fields_fail_before_transport(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_recipe_query", args)
    assert result.isError
    assert result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


@pytest.mark.parametrize("code", ["not_found", "invalid_args", "unsupported", "stale_query"])
async def test_native_fluid_unknown_or_old_bridge_rejection_preserves_error_without_fallback(bridge, code):
    adapter, state = bridge
    state["error"] = code
    result = await adapter.call_tool("mcb_recipe_query", declaration(fluidInputs=[FLUID]))
    assert result.isError
    assert result.structuredContent["error"]["code"] == code
    assert operations(state) == ["bridge.health", "recipes.query"]
    assert state["calls"][1]["args"] == declaration(fluidInputs=[FLUID])


async def test_exact_fluid_raw_receipt_is_preserved_without_coercion_or_execution_claim(bridge):
    adapter, state = bridge
    fluids = [{"fluid": "creosote", "amount": 1000}, {"fluid": "water", "amount": 2_147_483_647}]
    observed = {"mode": "backend_match", "source": "public_backend_find", "matched": False,
        "candidateValidated": False, "lookupComplete": True, "registeredCatalogComplete": False,
        "executionValidated": False, "query": {"inputs": [], "fluids": [{**f, "hasTag": False} for f in fluids]},
        "recipes": [], "hasMore": False, "nextCursor": None, "complete": False, "partial": True}
    state["data"]["recipes.query"] = copy.deepcopy(observed)
    result = await adapter.call_tool("mcb_recipe_query", declaration(fluidInputs=fluids))
    assert not result.isError
    assert result.structuredContent["data"] == observed
    assert operations(state) == ["bridge.health", "recipes.query"]


@pytest.mark.parametrize("extra", [{"fluidInputs": [FLUID]}, {"fluidInputs": []}, {"backendInputs": []}])
async def test_item_recipe_wrapper_scope_remains_closed(bridge, extra):
    adapter, state = bridge
    args = once_arguments(recipeLookup="backend_match", backendInputs=[ITEM])
    result = await adapter.call_tool("mcb_recipe_once", {**args, **extra})
    assert result.isError
    assert result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []
