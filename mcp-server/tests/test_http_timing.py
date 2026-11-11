"""Optional HTTP timing cannot change or replay original action/context outcomes."""
import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, request_context, response_context
from minecraft_codex_bridge.call_timing import SERVER_FIELDS, CallTiming, parse_timing_headers, timing_scope
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.workflows import project


def headers(**changes):
    values = {key: 0 for key in SERVER_FIELDS}
    values.update(httpWallNs=200, submittedTickets=3, startedTickets=2, finishedTickets=1,
                  cancelledBeforeTickets=1, queueNs=11, callbackWallNs=13, queueAwaitCalls=2,
                  queueAwaitWallNs=70, nativeAwaitCalls=1, nativeAwaitWallNs=20)
    values.update(changes)
    return httpx.Headers({"X-MCB-Timing-Version": "1", **{
        "X-MCB-Timing-" + name: str(values[key]) for key, name in SERVER_FIELDS.items()}})


def test_full_header_counts_keep_unfinished_callback_unmeasured():
    parsed = parse_timing_headers(headers())
    assert parsed["status"] == "measured"
    report = CallTiming()
    report.rpc("world.inspect", 1000, parsed)
    report.rpc("bridge.health", 1000)
    report.rpc("gui.inspect", 1000, {"status": "malformed"})
    result = report.report()["serverTiming"]
    assert (result["measuredCalls"], result["unavailableCalls"], result["malformedCalls"]) == (1, 1, 1)
    assert result["incompleteTickets"] == 1
    assert result["queueMeasuredTickets"] == 2 and result["callbackMeasuredTickets"] == 1
    assert result["sums"]["callbackWallNs"] == 13
    assert not result["cpuTimeMeasured"] and not result["httpResidualClassified"]
    assert "overlaps" in result["queueAwaitWallIncludes"]


@pytest.mark.parametrize("mode", ["partial", "duplicate", "unsupported", "unknown", "negative", "leading_zero",
    "decimal", "overflow", "finished", "started", "cancelled", "queue_without_start", "callback_without_finish",
    "await_without_call", "native_without_call"])
def test_malformed_optional_headers_only_mark_telemetry(mode):
    value = headers()
    if mode == "partial": del value["X-MCB-Timing-Queue-Ns"]
    elif mode == "duplicate": value = httpx.Headers(list(value.multi_items()) + [("X-MCB-Timing-Version", "1")])
    elif mode == "unsupported": value["X-MCB-Timing-Version"] = "2"
    elif mode == "unknown": value["X-MCB-Timing-Other"] = "0"
    elif mode in {"negative", "leading_zero", "decimal", "overflow"}:
        value["X-MCB-Timing-Http-Wall-Ns"] = {"negative": "-1", "leading_zero": "01", "decimal": "1.0", "overflow": str(2**63)}[mode]
    else:
        changes = {"finished": {"finishedTickets": 3}, "started": {"startedTickets": 4},
            "cancelled": {"cancelledBeforeTickets": 2}, "queue_without_start": {"submittedTickets": 0, "startedTickets": 0, "finishedTickets": 0, "cancelledBeforeTickets": 0},
            "callback_without_finish": {"finishedTickets": 0}, "await_without_call": {"queueAwaitCalls": 0},
            "native_without_call": {"nativeAwaitCalls": 0}}[mode]
        value = headers(**changes)
    assert parse_timing_headers(value) == {"status": "malformed"}
    assert parse_timing_headers(httpx.Headers({"Content-Type": "application/json"})) == {"status": "unavailable"}


@pytest.mark.parametrize("mode", ["full", "missing", "malformed"])
@pytest.mark.parametrize("refused", [False, True])
async def test_original_action_success_or_refusal_preserved_without_replay(mode, refused):
    calls = []
    def respond(request):
        body = json.loads(request.content); calls.append(body)
        envelope = {"requestId": body["requestId"], "ok": not refused}
        envelope.update({"error": {"code": "permission_denied", "message": "Refused.", "outcome_unknown": False}} if refused
                        else {"data": {"nativeSubmitted": True, "count": 17, "complete": False}})
        telemetry = headers() if mode == "full" else {"X-MCB-Timing-Version": "2"} if mode == "malformed" else {}
        return httpx.Response(200, json=envelope, headers=telemetry)
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "secret"}), transport=httpx.MockTransport(respond))
    try:
        with timing_scope() as timing:
            if refused:
                with pytest.raises(BridgeError) as failure: await client.call("player.look", {"yaw": 1})
                assert failure.value.code == "permission_denied" and failure.value.details["outcome_unknown"] is False
            else:
                assert await client.call("player.look", {"yaw": 1}) == {"nativeSubmitted": True, "count": 17, "complete": False}
        assert len(calls) == 1
        assert timing.report()["serverTiming"][{"full": "measuredCalls", "missing": "unavailableCalls", "malformed": "malformedCalls"}[mode]] == 1
    finally: await client.aclose()


async def test_headers_do_not_relax_strict_json_or_guarded_context_envelopes():
    requests = []
    def respond(request):
        body = json.loads(request.content); requests.append(body)
        result = {"requestId": body["requestId"], "ok": True, "data": {"count": 9}}
        if "includeContext" in body:
            result["context"] = {"version": 1, "operation": body["operation"], "phase": "after", "snapshot": {"sessionId": "current"}}
        else: result["newUnexpectedField"] = 1
        return httpx.Response(200, json=result, headers=headers())
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "secret"}), transport=httpx.MockTransport(respond))
    try:
        with request_context({"version": 1}):
            data = await client.call("inventory.inspect", {})
        assert data == {"count": 9} and response_context(data)["snapshot"] == {"sessionId": "current"}
        with pytest.raises(BridgeError) as failure: await client.call("inventory.inspect", {})
        assert failure.value.code == "MALFORMED_RESPONSE" and len(requests) == 2
    finally: await client.aclose()


async def test_concurrent_adapter_server_aggregates_are_separate():
    async def respond(request):
        body = json.loads(request.content)
        await asyncio.sleep(0)
        health = body["operation"] == "bridge.health"
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
            "data": {"protocolVersion": 1, "capabilities": ["client.snapshot"]} if health else {"count": 4}},
            headers=headers(httpWallNs=10 if health else 90))
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "secret"}), transport=httpx.MockTransport(respond))
    try:
        adapter = Adapter(client)
        health, snapshot = await asyncio.gather(adapter.call_tool("mcb_health", {}), adapter.call_tool("mcb_snapshot", {}))
        assert health.structuredContent["mcpTiming"]["serverTiming"]["sums"]["httpWallNs"] == 10
        assert snapshot.structuredContent["mcpTiming"]["serverTiming"]["sums"]["httpWallNs"] == 100
    finally: await client.aclose()


def test_compact_workflow_health_preserves_same_readiness_observation():
    readiness = {"version": 1, "stage": "lifecycle_pending", "worldReady": False, "nextTool": "mcb_health", "retryAction": False}
    data = {"sessionId": "current", "profile": "player", "clientTicks": 17, "background": {}, "readiness": readiness}
    projected = project(data, {"kind": "read", "tool": "mcb_health"})
    assert projected["values"]["readiness"] == readiness
    assert projected["values"]["sessionId"] == "current"
