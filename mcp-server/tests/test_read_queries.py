"""New observations are single, bounded calls and never acquire mouse control."""

import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, TOOL_BY_NAME, TOOLS

POSITION = {"x": 0, "y": 64, "z": 0}
BOUNDS = {"minX": -2, "maxX": 2, "minY": 0, "maxY": 255, "minZ": -1, "maxZ": 1}
DIRECTIONAL = {"facing": "north", "forward": 3, "backward": 1, "left": 2, "right": 4, "up": 5, "down": 6}


@pytest.fixture
async def bridge():
    state = {"calls": [], "capabilities": [tool.operation for tool in TOOLS if tool.operation], "data": {}, "error": None, "slow": None}

    async def respond(request):
        body = json.loads(request.content)
        state["calls"].append({"operation": "__cancel__" if request.url.path == "/v1/cancel" else body["operation"], **body})
        if request.url.path == "/v1/cancel":
            data = {"status": "already_running"}
        elif body["operation"] == "bridge.health":
            # No actual background protection exists in this fixture.
            data = {"protocolVersion": 1, "profile": "developer", "capabilities": state["capabilities"],
                    "recipeQueryAccess": {"version": 1, "publicCatalog": True,
                                          "localMachineBackend": True, "remoteMachineBackend": False}}
        else:
            if state["slow"] == body["operation"]:
                await asyncio.sleep(10)
            if state["error"]:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": {"code": state["error"], "message": "Query refused."}})
            data = state["data"].get(body["operation"], {"operation": body["operation"], "args": body["args"]})
            if callable(data):
                data = data(body["args"])
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "query-test-token"}), timeout=0.03, transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def operations(state):
    return [call["operation"] for call in state["calls"]]


@pytest.mark.parametrize("name,args", [
    ("mcb_world_scan", {}),
    ("mcb_world_scan", {**POSITION, "radius": 0}),
    ("mcb_world_scan", {**POSITION, "radius": 128, "limit": 512}),
    ("mcb_world_scan", {**POSITION, "dimension": -1, "radiusChunks": 8, "verticalRadiusChunks": 8}),
    ("mcb_world_scan", {"radiusChunks": 2}),
    ("mcb_world_scan", {"verticalRadiusChunks": 3}),
    ("mcb_world_scan", {"cursor": "opaque", "limit": 1}),
    ("mcb_world_scan", {"bounds": BOUNDS}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "minX": -128, "maxX": 128}, "dimension": 180, "limit": 512}),
    ("mcb_world_scan", {"directional": DIRECTIONAL}),
    *[("mcb_world_scan", {**POSITION, "directional": {**DIRECTIONAL, "facing": facing}}) for facing in ("north", "east", "south", "west")],
    ("mcb_scan_config", {}),
    ("mcb_scan_config", {"radiusChunks": 3}),
    ("mcb_scan_config", {"verticalRadiusChunks": 4}),
    ("mcb_scan_config", {"radiusChunks": 8, "verticalRadiusChunks": 8}),
    ("mcb_scan_config", {"cacheRadiusChunks": 32, "cacheVerticalRadiusChunks": 16, "cacheMaxMiB": 16384}),
    ("mcb_scan_config", {"cacheMaxMiB": 64}),
    ("mcb_world_structure", POSITION),
    ("mcb_world_structure", {**POSITION, "runtimeOffset": 0}),
    ("mcb_world_structure", {**POSITION, "floorBlockY": 2}),
    ("mcb_world_structure", {**POSITION, "captureId": "native-capture", "runtimeOffset": 128, "limit": 16}),
    ("mcb_world_structure", {**POSITION, "captureId": "native-capture", "runtimeOffset": 2049}),
    ("mcb_world_structure", {**POSITION, "captureId": "native-capture", "runtimeOffset": 16384}),
    ("mcb_world_structure", {**POSITION, "dimension": -1, "piece": "main", "variant": 64, "offsetA": -2048, "offsetB": 2048, "offsetC": 0, "offset": 100_000, "limit": 128, "query": "chamber"}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0}),
    ("mcb_gui_tooltip", {"screenId": "screen", "x": 10, "y": 20, "advanced": True}),
    ("mcb_nei", {"screenId": "screen"}),
    ("mcb_nei", {"screenId": "screen", "offset": 100_000, "limit": 16, "includeTooltips": True, "advanced": False}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler"}),
    ("mcb_recipe_query", {**POSITION, "dimension": 180, "input": {"source": "inventory", "slot": 35}, "limit": 16}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "output": {"source": "registry", "registryName": "minecraft:chest", "metadata": 0}}),
    ("mcb_recipe_query", {"cursor": "opaque", "dimension": 180, "limit": 1}),
])
async def test_valid_queries_forward_only_explicit_arguments_without_takeover(bridge, name, args):
    adapter, state = bridge
    result = await adapter.call_tool(name, args)
    operation = TOOL_BY_NAME[name].operation
    assert not result.isError
    assert operations(state) == ["bridge.health", operation]
    assert state["calls"][1]["args"] == args
    assert result.structuredContent["data"] == {"operation": operation, "args": args}


@pytest.mark.parametrize("name,args", [
    ("mcb_world_scan", {"x": 0}), ("mcb_world_scan", {"x": 0, "y": 64}),
    ("mcb_world_scan", {**POSITION, "x": 30_000_000}),
    ("mcb_world_scan", {"radius": 1, "radiusChunks": 1}),
    ("mcb_world_scan", {"radius": 1, "verticalRadiusChunks": 1}),
    ("mcb_world_scan", {"cursor": "opaque", **POSITION}),
    ("mcb_world_scan", {"cursor": "opaque", "dimension": 0}),
    ("mcb_world_scan", {"cursor": "opaque", "radiusChunks": 1}),
    ("mcb_world_scan", {"cursor": "opaque", "radius": 1}),
    ("mcb_world_scan", {"cursor": ""}), ("mcb_world_scan", {"cursor": "a" * 129}),
    ("mcb_world_scan", {"radiusChunks": 0}), ("mcb_world_scan", {"verticalRadiusChunks": 9}),
    ("mcb_world_scan", {"radius": -1}), ("mcb_world_scan", {"radius": 129}),
    ("mcb_world_scan", {"limit": 513}), ("mcb_world_scan", {"limit": True}),
    ("mcb_world_scan", {"radiusChunks": True}), ("mcb_world_scan", {"budget": 999999}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "minX": 3}}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "minX": -135, "maxX": 128}}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "maxY": 256}}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "maxZ": 30_000_000}}),
    ("mcb_world_scan", {"bounds": {**BOUNDS, "minY": True}}),
    ("mcb_world_scan", {"bounds": {"minX": 0}}),
    *[("mcb_world_scan", {"bounds": BOUNDS, **extra}) for extra in (POSITION, {"radius": 0}, {"radiusChunks": 1}, {"directional": DIRECTIONAL}, {"cursor": "cursor"})],
    *[("mcb_world_scan", {"directional": DIRECTIONAL, **extra}) for extra in ({"radius": 0}, {"radiusChunks": 1}, {"verticalRadiusChunks": 1}, {"cursor": "cursor"}, {"x": 0})],
    *[("mcb_world_scan", {"directional": {**DIRECTIONAL, **extra}}) for extra in ({"facing": "up"}, {"up": 129}, {"down": -1}, {"left": True}, {"extra": 0})],
    ("mcb_world_scan", {"directional": {"facing": "north"}}),
    *[("mcb_world_scan", {**POSITION, **position, "directional": DIRECTIONAL}) for position in ({"y": 2}, {"y": 253}, {"x": -30_000_000}, {"x": 29_999_999}, {"z": -30_000_000}, {"z": 29_999_999})],
    ("mcb_scan_config", {"radiusChunks": 0}), ("mcb_scan_config", {"verticalRadiusChunks": 9}),
    ("mcb_scan_config", {"radiusChunks": True}), ("mcb_scan_config", {"persist": True}),
    *[("mcb_scan_config", args) for args in ({"cacheRadiusChunks": 0}, {"cacheRadiusChunks": 33}, {"cacheVerticalRadiusChunks": 33}, {"cacheMaxMiB": 63}, {"cacheMaxMiB": 16385}, {"cacheMaxMiB": True}, {"cacheMaxMiB": "2048"}, {"cacheRadiusChunks": 2, "cacheMaxMiB": 63})],
    ("mcb_world_structure", {}), ("mcb_world_structure", {**POSITION, "offsetA": 0}),
    ("mcb_world_structure", {**POSITION, "offsetB": 0, "offsetC": 0}),
    ("mcb_world_structure", {**POSITION, "offsetA": 0, "offsetB": 0, "offsetC": 0}),
    ("mcb_world_structure", {**POSITION, "offsetA": 2049, "offsetB": 0, "offsetC": 0}),
    ("mcb_world_structure", {**POSITION, "variant": 0}), ("mcb_world_structure", {**POSITION, "variant": 65}),
    ("mcb_world_structure", {**POSITION, "offset": -1}), ("mcb_world_structure", {**POSITION, "limit": 129}),
    ("mcb_world_structure", {**POSITION, "query": "x" * 129}),
    ("mcb_world_structure", {**POSITION, "runtimeOffset": 1}),
    ("mcb_world_structure", {**POSITION, "captureId": "", "runtimeOffset": 0}),
    ("mcb_world_structure", {**POSITION, "captureId": "native-capture", "runtimeOffset": 16385}),
    ("mcb_world_structure", {**POSITION, "floorBlockY": 255}),
    ("mcb_gui_tooltip", {"screenId": "screen"}),
    ("mcb_gui_tooltip", {"slot": 0}), ("mcb_gui_tooltip", {"screenId": "", "slot": 0}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0, "x": 1, "y": 2}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0, "x": 1}),
    ("mcb_gui_tooltip", {"screenId": "screen", "x": 1}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": -1}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": True}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 2_147_483_648}),
    ("mcb_gui_tooltip", {"screenId": "screen", "x": 1, "y": -1}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0, "advanced": 1}),
    ("mcb_nei", {}), ("mcb_nei", {"screenId": ""}),
    ("mcb_nei", {"screenId": "screen", "limit": 0}),
    ("mcb_nei", {"screenId": "screen", "limit": 17}),
    ("mcb_nei", {"screenId": "screen", "offset": 100_001}),
    ("mcb_nei", {"screenId": "screen", "includeTooltips": 1}),
    ("mcb_nei", {"screenId": "screen", "advanced": 1}),
    ("mcb_nei", {"screenId": "screen", "registryName": "minecraft:stone"}),
    ("mcb_recipe_query", {}), ("mcb_recipe_query", {"x": 0, "y": 64}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", **POSITION}),
    ("mcb_recipe_query", {"cursor": "opaque", "map": "gt.recipe.assembler"}),
    ("mcb_recipe_query", {"cursor": "opaque", "input": {"source": "inventory", "slot": 0}}),
    ("mcb_recipe_query", {"cursor": "x" * 65}),
    ("mcb_recipe_query", {"map": ""}), ("mcb_recipe_query", {"map": "gt.recipe.assembler", "limit": 17}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "maxPages": 17}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "maxPages": True}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "input": {"source": "inventory", "slot": True}}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "input": {"source": "inventory", "slot": 0, "registryName": "minecraft:stone"}}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler", "output": {"source": "registry", "registryName": "minecraft:stone", "slot": 0}}),
])
async def test_invalid_query_arguments_are_rejected_before_http(bridge, name, args):
    adapter, state = bridge
    result = await adapter.call_tool(name, args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("name", ["mcb_scan_config", "mcb_world_structure", "mcb_gui_tooltip", "mcb_nei"])
async def test_missing_new_capability_does_not_invoke_another_observation(bridge, name):
    adapter, state = bridge
    state["capabilities"] = ["world.scan", "gui.inspect"]
    args = POSITION if name == "mcb_world_structure" else {"screenId": "screen", "slot": 0} if name == "mcb_gui_tooltip" else {"screenId": "screen"} if name == "mcb_nei" else {}
    result = await adapter.call_tool(name, args)
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("name,args,data", [
    ("mcb_world_scan", {}, {"complete": False, "nextCursor": "next", "blocks": [], "coverage": {"examined": 0, "air": 0, "unloaded": 0}, "page": {"startOffset": 0, "endOffsetExclusive": 0}, "visitedSpans": [], "stopReason": "tick_budget", "nonAtomic": True}),
    ("mcb_world_structure", POSITION, {"piece": "main", "positions": [], "nextOffset": 2048, "hasMore": True, "coverage": {"geometry": "StructureLib", "validation": "not_checked"}, "budget": {"actualElapsedMicros": 5000, "softBudgetExceeded": True}}),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0}, {"source": "ItemStack.getTooltip", "tooltip": ["NBT-aware live stack"], "modifiers": {"shift": False, "ctrl": True, "alt": False, "source": "current client"}, "coverage": {"richTooltips": False}}),
    ("mcb_nei", {"screenId": "screen"}, {"handler": {"class": "CurrentHandler", "id": "current", "name": "Current recipes"}, "recipes": [{"recipeIndex": 42, "inputs": [], "outputs": [{"item": {"registryName": "minecraft:stone", "count": 2, "metadata": 0, "hasTag": True}}], "other": []}], "nextOffset": 1, "hasMore": True, "coverage": {"extraDrawnText": "unavailable"}, "nonAtomic": True}),
    ("mcb_nei", {"screenId": "screen", "includeTooltips": True}, {"recipeCount": 100, "visibleRecipeIndices": [42, 77], "visibleRecipeCount": 2, "visibleIndicesTruncated": False, "recipes": [{"pageOffset": 0, "recipeIndex": 42, "inputs": [{"chance": 10000, "item": {"registryName": "minecraft:stone", "count": 2, "metadata": 0, "hasTag": True, "tooltip": ["Current actual stack"]}, "alternatives": [], "alternativesTotal": 40, "alternativesTruncated": True}], "outputs": [], "other": [], "positionsVisited": 1, "positionsTruncated": True, "softBudgetPartial": True}], "offset": 0, "nextOffset": 1, "hasMore": True, "modifiers": {"shift": False, "ctrl": True, "alt": False, "source": "current physical keyboard state"}, "limits": {"recipes": 16, "positionsPerRecipe": 64, "alternativesPerPosition": 32, "visibleRefs": 256, "resultBytes": 131072}, "budget": {"actualElapsedMicros": 5000, "softBudgetExceeded": True}, "byteLimited": False, "nonAtomic": True}),
    ("mcb_recipe_query", {"map": "gt.recipe.assembler"}, {"recipes": [], "partial": True, "complete": False, "hasMore": True, "nextCursor": "next", "filterIncomplete": True, "coverage": {"executionValidated": False, "metadataUnknown": 1}}),
])
async def test_budget_coverage_and_live_tooltip_data_are_returned_without_fallback_or_more_pages(bridge, name, args, data):
    adapter, state = bridge
    operation = TOOL_BY_NAME[name].operation
    state["data"][operation] = data
    result = await adapter.call_tool(name, args)
    assert not result.isError and result.structuredContent["data"] == data
    assert operations(state) == ["bridge.health", operation]
    assert "client.screenshot" not in operations(state)


@pytest.mark.parametrize("name,args,code", [
    ("mcb_world_scan", {"cursor": "expired"}, "stale_cursor"),
    ("mcb_world_structure", POSITION, "permission_denied"),
    ("mcb_gui_tooltip", {"screenId": "old-screen", "slot": 0}, "stale_screen"),
    ("mcb_nei", {"screenId": "screen"}, "unsupported"),
    ("mcb_recipe_query", {"cursor": "expired"}, "stale_query"),
])
async def test_runtime_query_rejections_are_preserved_without_retry_or_screenshot(bridge, name, args, code):
    adapter, state = bridge
    state["error"] = code
    result = await adapter.call_tool(name, args)
    assert result.isError and result.structuredContent["error"] == {"code": code, "message": "Query refused."}
    assert operations(state) == ["bridge.health", TOOL_BY_NAME[name].operation]


@pytest.mark.parametrize("name,args,unknown", [
    ("mcb_world_scan", {"cursor": "opaque"}, False),
    ("mcb_world_structure", POSITION, False),
    ("mcb_gui_tooltip", {"screenId": "screen", "slot": 0}, False),
    ("mcb_nei", {"screenId": "screen"}, False),
    ("mcb_recipe_query", {"cursor": "opaque"}, False),
    ("mcb_scan_config", {"radiusChunks": 2}, True),
    ("mcb_scan_config", {"cacheMaxMiB": 4096}, True),
])
async def test_read_timeouts_and_configuration_mutation_keep_distinct_outcome_semantics(bridge, name, args, unknown):
    adapter, state = bridge
    operation = TOOL_BY_NAME[name].operation
    state["slow"] = operation
    result = await adapter.call_tool(name, args)
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == "BRIDGE_TIMEOUT"
    assert error["outcome_unknown"] is unknown
    assert operations(state) == ["bridge.health", operation, "__cancel__"]
    assert state["calls"][1]["requestId"] == state["calls"][2]["requestId"] == error["request_id"]


async def test_legacy_explicit_block_radius_is_still_forwarded_without_new_defaults(bridge):
    adapter, state = bridge
    state["capabilities"] = ["world.scan"]
    args = {**POSITION, "radius": 8, "limit": 5}
    assert not (await adapter.call_tool("mcb_world_scan", args)).isError
    assert state["calls"][1]["args"] == args
    state["error"] = "invalid_args"
    result = await adapter.call_tool("mcb_world_scan", {})
    assert result.isError and result.structuredContent["error"]["code"] == "invalid_args"
    assert operations(state) == ["bridge.health", "world.scan"] * 2
    assert state["calls"][-1]["args"] == {}


def test_query_metadata_keeps_nonrepeatable_scan_cursors_out_of_wait_for():
    # Timeout outcome reporting relies on this independent transport classification.
    public_reads = {tool.operation for tool in TOOLS if tool.operation and tool.read_only}
    # Status shares player.task with cancellation, so transport conservatively treats
    # the operation as mutable; the status tool itself still preserves control.
    # Recording now selects native live data or bounded offline history in its handler.
    assert TOOL_BY_NAME["mcb_recording"].operation is None
    assert public_reads - {"player.task"} == READ_OPERATIONS - {"player.plan_approach", "client.recording"}
    assert TOOL_BY_NAME["mcb_task_status"].read_only
    assert not TOOL_BY_NAME["mcb_task_cancel"].read_only
    assert "player.plan_approach" in READ_OPERATIONS
    assert all(tool.operation != "player.plan_approach" for tool in TOOLS)
    assert all(not tool.takeover for tool in TOOLS if tool.read_only)
    assert len({tool.name for tool in TOOLS}) == len(TOOLS)
    duplicate_operations = {operation for operation in public_reads
                            if sum(tool.operation == operation for tool in TOOLS) > 1}
    assert duplicate_operations == {"player.task"}
    assert len({tool.operation for tool in TOOLS if tool.operation}) == len([tool for tool in TOOLS if tool.operation]) - 1
    for name in ("mcb_world_scan", "mcb_world_structure", "mcb_gui_tooltip", "mcb_nei"):
        tool = TOOL_BY_NAME[name]
        assert tool.read_only and not tool.takeover and tool.operation in READ_OPERATIONS
    config = TOOL_BY_NAME["mcb_scan_config"]
    assert not config.read_only and not config.takeover and config.operation not in READ_OPERATIONS
    assert "mcb_world_scan" not in OBSERVATION_NAMES
    assert "mcb_recipe_query" not in OBSERVATION_NAMES
    assert "mcb_scan_config" not in OBSERVATION_NAMES
    assert {"mcb_world_structure", "mcb_gui_tooltip", "mcb_nei"}.issubset(OBSERVATION_NAMES)


async def test_wait_for_cannot_consume_a_scan_cursor(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_wait_for", {"tool": "mcb_world_scan", "args": {"cursor": "opaque"}, "condition": {"path": "complete", "operator": "equals", "value": True}})
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


async def test_wait_for_cannot_consume_a_recipe_cursor(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_wait_for", {"tool": "mcb_recipe_query", "args": {"cursor": "opaque"},
        "condition": {"path": "complete", "operator": "equals", "value": True}})
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


async def test_requested_recipe_pages_stop_at_first_match_and_preserve_continuation_context(bridge):
    adapter, state = bridge
    pages = iter([
        {"sessionId": "current", "dimension": 180, "recipes": [], "hasMore": True, "nextCursor": "one", "complete": False},
        {"sessionId": "current", "dimension": 180, "recipes": [], "hasMore": True, "nextCursor": "two", "complete": False},
        {"sessionId": "current", "dimension": 180, "recipes": [{"rawEUt": 4}], "hasMore": True, "nextCursor": "three", "complete": False},
    ])
    state["data"]["recipes.query"] = lambda args: next(pages)
    result = await adapter.call_tool("mcb_recipe_query", {"map": "gt.recipe.assembler", "limit": 1, "maxPages": 16})
    assert not result.isError
    assert result.structuredContent["data"]["queryPages"] == 3
    assert result.structuredContent["data"]["nextCursor"] == "three"
    assert result.structuredContent["data"]["complete"] is False
    assert operations(state) == ["bridge.health", "recipes.query", "recipes.query", "recipes.query"]
    assert [call["args"] for call in state["calls"][1:]] == [
        {"map": "gt.recipe.assembler", "limit": 1},
        {"cursor": "one", "dimension": 180, "limit": 1},
        {"cursor": "two", "dimension": 180, "limit": 1},
    ]


async def test_recipe_page_cap_keeps_empty_partial_result_without_absence_inference(bridge):
    adapter, state = bridge
    state["data"]["recipes.query"] = {"sessionId": "current", "dimension": 0, "recipes": [],
        "hasMore": True, "nextCursor": "next", "partial": True, "complete": False, "filterIncomplete": True}
    result = await adapter.call_tool("mcb_recipe_query", {"map": "gt.recipe.assembler", "maxPages": 2})
    data = result.structuredContent["data"]
    assert not result.isError and data["queryPages"] == 2
    assert data["recipes"] == [] and data["hasMore"] and data["partial"] and data["filterIncomplete"]
    assert not data["complete"]
    assert operations(state) == ["bridge.health", "recipes.query", "recipes.query"]


async def test_recipe_pagination_stops_when_session_changes(bridge):
    adapter, state = bridge
    pages = iter([
        {"sessionId": "before", "dimension": 0, "recipes": [], "hasMore": True, "nextCursor": "one"},
        {"sessionId": "after", "dimension": 0, "recipes": [], "hasMore": True, "nextCursor": "two"},
    ])
    state["data"]["recipes.query"] = lambda args: next(pages)
    result = await adapter.call_tool("mcb_recipe_query", {"map": "gt.recipe.assembler", "maxPages": 8})
    assert result.isError and result.structuredContent["error"]["code"] == "SESSION_CHANGED"
    assert operations(state) == ["bridge.health", "recipes.query", "recipes.query"]
