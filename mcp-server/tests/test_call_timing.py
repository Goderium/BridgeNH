"""Timing stays per-call during concurrent reads and does not change RPC outcomes."""

import asyncio
import json

import httpx

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.call_timing import CallTiming, record_rpc, timing_scope
from minecraft_codex_bridge.server import Adapter


async def test_concurrent_adapter_calls_keep_rpc_counts_and_receipts_separate():
    token = "private-timing-test-token"

    async def respond(request):
        body = json.loads(request.content)
        await asyncio.sleep(0.001)
        if body["operation"] == "bridge.health":
            data = {"protocolVersion": 1, "capabilities": ["bridge.health", "client.snapshot"]}
        else:
            data = {"counter": 17, "complete": False}
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": token}),
                          transport=httpx.MockTransport(respond))
    try:
        adapter = Adapter(client)
        health, snapshot = await asyncio.gather(adapter.call_tool("mcb_health", {}), adapter.call_tool("mcb_snapshot", {}))
        assert health.structuredContent["mcpTiming"]["bridgeCalls"] == 1
        assert snapshot.structuredContent["mcpTiming"]["bridgeCalls"] == 2
        assert snapshot.structuredContent["data"] == {"counter": 17, "complete": False}
        assert not snapshot.isError
        for result in (health, snapshot):
            assert json.loads(result.content[0].text) == result.structuredContent
            timing = result.structuredContent["mcpTiming"]
            assert timing["bridgeRoundTripSumMs"] > 0
            assert timing["persisted"] is False
            assert timing["externalPlanningAndApprovalMeasured"] is False
            assert timing["durationsAdditive"] is False
            assert token not in json.dumps(timing)
    finally:
        await client.aclose()


async def test_invalid_arguments_keep_failure_and_do_not_dispatch_rpc():
    calls = []

    async def respond(request):
        calls.append(request)
        raise AssertionError("invalid arguments must not dispatch")

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "secret"}),
                          transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_snapshot", {"unsupported": True})
        assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
        assert result.structuredContent["mcpTiming"]["bridgeCalls"] == 0
        assert calls == []
    finally:
        await client.aclose()


async def test_server_refusal_has_timing_without_replaying_the_call():
    operations = []

    async def respond(request):
        body = json.loads(request.content)
        operations.append(body["operation"])
        if body["operation"] == "bridge.health":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
                "data": {"protocolVersion": 1, "capabilities": ["bridge.health", "client.snapshot"]}})
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
            "error": {"code": "busy", "message": "Owning queue is busy."}})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "secret"}),
                          transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_snapshot", {})
        assert result.isError and result.structuredContent["error"]["code"] == "busy"
        assert operations == ["bridge.health", "client.snapshot"]
        assert result.structuredContent["mcpTiming"]["bridgeCalls"] == 2
    finally:
        await client.aclose()


def test_scope_cleanup_bounds_and_untrusted_operation_strings():
    with timing_scope() as outer:
        record_rpc("bridge.health", 1_000_000)
        with timing_scope() as inner:
            record_rpc("gui.inspect", 2_000_000)
        record_rpc("bridge.health", 1_000_000)
    record_rpc("gui.inspect", 999_000_000)
    assert outer.report()["bridgeCalls"] == 2
    assert inner.report()["bridgeCalls"] == 1
    bounded = CallTiming()
    bounded.rpc("invalid/auth/secret=token", 1)
    for i in range(40):
        bounded.rpc("read." + "a" * (i // 26 + 1) + chr(97 + i % 26), 1)
    report = bounded.report()
    assert len(report["bridgeByOperation"]) == 32 and report["operationSummaryLimited"]
    assert "secret=token" not in json.dumps(report)
