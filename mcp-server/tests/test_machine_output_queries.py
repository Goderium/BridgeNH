"""Bounded local provider queries keep exact identity and long quantities through MCP."""

import copy
import json

import httpx
import jsonschema
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.validation import tool_validator


POSITION = {"x": 12, "y": 64, "z": -7, "dimension": 180}
ITEM = {"kind": "item", "registryName": "minecraft:iron_ingot", "metadata": 0}
FLUID = {"kind": "fluid", "fluidName": "molten.iron"}
OWNER = {**POSITION, "metaTileId": 84, "metaClass": "example.PublicOutputProvider"}


@pytest.mark.parametrize("changes", [
    {}, {"componentOffset": 100_000, "componentLimit": 128},
    {"outputQueries": [ITEM]}, {"outputQueries": [FLUID]},
    {"outputQueries": [ITEM, FLUID]},
    {"outputQueries": [{**ITEM, "metadata": 32_766}] * 8},
    {"outputQueries": [{"kind": "fluid", "fluidName": "mod:fluid_with/dots.and-hyphens"}]},
    {"outputQueries": [{"kind": "fluid", "fluidName": "f" * 128}]},
    {"outputQueries": [{**ITEM, "registryName": "i" * 256}]},
])
def test_machine_output_query_schema_preserves_old_and_bounded_public_requests(changes):
    tool_validator("mcb_machine_inspect").validate({**POSITION, **copy.deepcopy(changes)})


INVALID_QUERIES = [
    None, {}, "fluid", [], [ITEM] * 9, [None], [[]], [{}],
    [{"kind": "unknown", "registryName": "minecraft:iron_ingot", "metadata": 0}],
    [{"registryName": "minecraft:iron_ingot", "metadata": 0}],
    [{"kind": "item", "registryName": "minecraft:iron_ingot"}],
    [{"kind": "item", "metadata": 0}],
    [{**ITEM, "registryName": ""}], [{**ITEM, "registryName": "i" * 257}],
    [{**ITEM, "registryName": 1}], [{**ITEM, "metadata": -1}], [{**ITEM, "metadata": 32_767}],
    [{**ITEM, "metadata": True}], [{**ITEM, "metadata": "0"}], [{**ITEM, "metadata": 0.5}],
    [{**ITEM, "fluidName": "water"}], [{**ITEM, "hasTag": False}], [{**ITEM, "nbt": {}}], [{**ITEM, "count": 1}],
    [{"kind": "fluid"}], [{**FLUID, "fluidName": ""}], [{**FLUID, "fluidName": "f" * 129}],
    [{**FLUID, "fluidName": None}], [{**FLUID, "fluidName": True}],
    [{**FLUID, "registryName": "minecraft:iron_ingot"}], [{**FLUID, "metadata": 0}],
    [{**FLUID, "hasTag": False}], [{**FLUID, "amount": 1}],
    [{"kind": "fluid", "registryName": "water", "metadata": 0}],
    [{"kind": "item", "fluidName": "water"}],
]


@pytest.mark.parametrize("queries", INVALID_QUERIES)
async def test_bad_overbudget_and_cross_kind_output_queries_refuse_before_http(queries):
    arguments = {**POSITION, "outputQueries": copy.deepcopy(queries)}
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_machine_inspect").validate(arguments)
    calls = []

    def forbidden(request):
        calls.append(request)
        raise AssertionError("An invalid output query must not reach the bridge.")

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "schema-test-token"}),
        transport=httpx.MockTransport(forbidden))
    try:
        result = await Adapter(client).call_tool("mcb_machine_inspect", arguments)
    finally:
        await client.aclose()
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert calls == []


@pytest.mark.parametrize("queries", [None, [ITEM, FLUID], [ITEM] * 8])
@pytest.mark.parametrize("complete", [False, True])
async def test_output_provider_contract_forwards_exact_queries_identity_owner_and_long_amount(queries, complete):
    calls = []
    arguments = {**POSITION, "componentLimit": 1}
    data = {"sessionId": "session", **OWNER, "outputStorage": {"available": True,
        "cachedAmount": "9223372036854775807", "cacheCapacity": "9223372036854775807"}}
    if queries is not None:
        arguments["outputQueries"] = copy.deepcopy(queries)
        entries = []
        for index, query in enumerate(queries):
            entry = {"queryIndex": index, "kind": query["kind"], "available": complete or index == 0}
            if entry["available"]:
                key = "item" if query["kind"] == "item" else "fluid"
                entry[key] = {**{name: value for name, value in query.items() if name != "kind"}, "hasTag": False}
                entry["cachedAmount"] = "9223372036854775807" if index == 0 else "0"
            else:
                entry["reason"] = "public_cached_amount_unavailable"
            entries.append(entry)
        data["outputStorage"]["resourceQuery"] = {"schemaVersion": 1, "source": "public_provider_cached_amount",
            "scope": "local_output_provider_cache_only", "requestedCount": len(queries), "complete": complete,
            "entries": entries, "owner": copy.deepcopy(OWNER)}

    def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        value = {"protocolVersion": 1, "capabilities": ["machine.inspect"]} if body["operation"] == "bridge.health" else data
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": copy.deepcopy(value)})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "schema-test-token"}),
        transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_machine_inspect", arguments)
    finally:
        await client.aclose()
    assert not result.isError, result
    assert [call["operation"] for call in calls] == ["bridge.health", "machine.inspect"]
    assert calls[-1]["args"] == arguments
    observed = result.structuredContent["data"]
    assert observed == data
    assert observed["outputStorage"]["cachedAmount"] == "9223372036854775807"
    if queries is None:
        assert "resourceQuery" not in observed["outputStorage"]
    else:
        query = observed["outputStorage"]["resourceQuery"]
        assert query["owner"] == OWNER and query["complete"] is complete
        assert query["entries"][0]["cachedAmount"] == "9223372036854775807"
        if not complete:
            assert all("cachedAmount" not in entry and "item" not in entry and "fluid" not in entry
                for entry in query["entries"] if entry["available"] is False)
