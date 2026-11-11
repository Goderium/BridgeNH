"""Permanent fallback uses one original Adapter/client; no request is replayed."""
import base64
import io
import json

import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge import adapter_session
from minecraft_codex_bridge.agent_instructions import AGENT_INSTRUCTIONS
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.validation import tool_validator
from test_images import RAW


def line(identifier, tool="mcb_health", arguments=None):
    return json.dumps({"id": identifier, "tool": tool, "arguments": arguments or {}}).encode() + b"\n"


def setup_client(tmp_path, respond):
    runtime = tmp_path / "runtime.json"
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671", "token": "first-secret"}), encoding="utf-8")
    config = Configuration({"MCB_RUNTIME_FILE": str(runtime.resolve())})
    return runtime, config, BridgeClient(config, transport=httpx.MockTransport(respond))


def decoded(target): return [json.loads(value) for value in target.getvalue().splitlines()]


async def test_serial_original_adapter_runtime_rotation_one_fingerprint_and_cache(tmp_path, monkeypatch):
    calls = []
    def respond(request):
        body = json.loads(request.content); calls.append((body, request.headers["Authorization"]))
        runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25672", "token": "rotated-secret"}), encoding="utf-8")
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
            "data": {"protocolVersion": 1, "sessionId": len(calls), "capabilities": []}})
    runtime, config, client = setup_client(tmp_path, respond)
    metadata_calls = []
    original = adapter_session.startup_metadata
    def metadata(path): metadata_calls.append(path); return original(path)
    monkeypatch.setattr(adapter_session, "startup_metadata", metadata)
    tool_validator.cache_clear()
    output = io.BytesIO()
    assert await adapter_session.run_session(io.BytesIO(line(1) + line(2)), output, config, client=client) == 0
    ready, first, second = decoded(output)
    assert ready["instructions"] == AGENT_INSTRUCTIONS == make_server(client).instructions
    assert ready["runtimeFile"] == str(runtime.resolve()) and len(metadata_calls) == 1
    assert ready["packageSourceFiles"] > 1 and len(ready["packageSourceSha256"]) == 64
    assert (first["id"], second["id"]) == (1, 2)
    assert calls[0][1] == "Bearer first-secret" and calls[1][1] == "Bearer rotated-secret"
    assert len(calls) == 2 and calls[1][0]["operation"] == "bridge.health"
    assert tool_validator.cache_info().misses == 1 and tool_validator.cache_info().hits == 1
    assert client.http.is_closed
    assert "first-secret" not in output.getvalue().decode() and "rotated-secret" not in output.getvalue().decode()


async def test_ids_consumed_after_original_validation_refusal_unknown_tool_and_unknown_outcome(tmp_path):
    calls = []
    def respond(request):
        calls.append(request)
        raise httpx.ReadTimeout("fixture")
    _, config, client = setup_client(tmp_path, respond)
    source = line(1, "mcb_snapshot", {"unexpected": True}) + line(1) + line(2, "unknown") + line(2) + line(3) + line(3)
    output = io.BytesIO()
    await adapter_session.run_session(io.BytesIO(source), output, config, client=client)
    replies = decoded(output)[1:]
    assert replies[0]["result"]["isError"] and replies[0]["result"]["structuredContent"]["error"]["code"] == "INVALID_ARGUMENTS"
    assert replies[2]["result"]["structuredContent"]["error"]["code"] == "UNKNOWN_TOOL"
    assert replies[4]["result"]["structuredContent"]["error"]["code"] == "BRIDGE_TIMEOUT"
    assert all(replies[index]["error"]["code"] == "SESSION_REQUEST_ID_REUSED" for index in (1, 3, 5))
    assert [request.url.path for request in calls] == ["/v1/call", "/v1/cancel"]
    assert client.http.is_closed


@pytest.mark.parametrize("raw", [b'\xff\n', b'{"id":1,"id":2,"tool":"mcb_health","arguments":{}}\n',
    b'{"id":1,"tool":"mcb_health","arguments":{"v":NaN}}\n', line(True), line(0), line(2**63),
    b'{"id":1,"tool":"mcb_health","arguments":{},"extra":1}\n'])
async def test_invalid_utf8_json_envelope_ids_undispatched_then_valid_request(tmp_path, raw):
    calls = []
    def respond(request):
        calls.append(request); body = json.loads(request.content)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"protocolVersion": 1}})
    _, config, client = setup_client(tmp_path, respond)
    output = io.BytesIO()
    await adapter_session.run_session(io.BytesIO(raw + line(1)), output, config, client=client)
    _, invalid, valid = decoded(output)
    assert invalid["error"] == {"code": "SESSION_INVALID_REQUEST", "adapterDispatched": False, "outcomeUnknown": False, "retryAllowed": False}
    assert valid["type"] == "result" and len(calls) == 1


async def test_oversize_producer_terminates_undispatched_and_closes(tmp_path):
    def respond(request): raise AssertionError("must not dispatch")
    _, config, client = setup_client(tmp_path, respond)
    output = io.BytesIO()
    assert await adapter_session.run_session(io.BytesIO(b"x" * (adapter_session.MAX_REQUEST_BYTES + 2) + line(1)), output, config, client=client) == 2
    assert decoded(output)[1]["error"]["code"] == "SESSION_REQUEST_TOO_LARGE" and client.http.is_closed


async def test_original_native_media_and_error_classification_preserved(tmp_path):
    operations = []
    def respond(request):
        body = json.loads(request.content); operations.append(body["operation"])
        data = {"protocolVersion": 1, "capabilities": ["client.screenshot"]} if body["operation"] == "bridge.health" else {
            "mimeType": "image/png", "width": 1, "height": 1, "base64": base64.b64encode(RAW).decode()}
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})
    _, config, client = setup_client(tmp_path, respond)
    output = io.BytesIO()
    await adapter_session.run_session(io.BytesIO(line(1, "mcb_screenshot") + line(2, "unknown")), output, config, client=client)
    _, screenshot, failure = decoded(output)
    result = screenshot["result"]
    assert result["isError"] is False and result["content"][1]["type"] == "image"
    assert result["content"][1]["data"] == base64.b64encode(RAW).decode()
    assert json.loads(result["content"][0]["text"]) == result["structuredContent"]
    assert failure["result"]["isError"] is True
    assert operations == ["bridge.health", "client.screenshot"]


async def test_output_limit_after_original_dispatch_never_replays_or_reuses_id(tmp_path, monkeypatch):
    calls = []
    def respond(request):
        body = json.loads(request.content); calls.append(body)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
            "data": {"protocolVersion": 1, "large": "x" * 3000}})
    _, config, client = setup_client(tmp_path, respond)
    monkeypatch.setattr(adapter_session, "MAX_OUTPUT_BYTES", 1000)
    output = io.BytesIO()
    await adapter_session.run_session(io.BytesIO(line(1) + line(1)), output, config, client=client, metadata={"type": "ready"})
    _, unavailable, reused = decoded(output)
    assert unavailable["error"] == {"code": "SESSION_RESULT_UNAVAILABLE", "adapterDispatched": True,
        "outcomeUnknown": True, "retryAllowed": False, "adapterIsError": False}
    assert reused["error"]["code"] == "SESSION_REQUEST_ID_REUSED" and len(calls) == 1


async def test_eof_without_request_closes_client_and_ready_does_no_rpc(tmp_path):
    def respond(request): raise AssertionError("ready must not call bridge")
    _, config, client = setup_client(tmp_path, respond)
    output = io.BytesIO()
    assert await adapter_session.run_session(io.BytesIO(), output, config, client=client) == 0
    replies = decoded(output)
    assert len(replies) == 1 and client.http.is_closed
    assert replies[0]["adapterReady"] is True
    assert replies[0]["clientReadiness"] == "not_observed" and replies[0]["worldReadiness"] == "not_observed"


async def test_actual_mcp_initialize_and_fallback_ready_share_fixed_instructions(tmp_path):
    def respond(request): raise AssertionError("initialization has no game RPC")
    runtime, _, client = setup_client(tmp_path, respond)
    try:
        async with create_connected_server_and_client_session(make_server(client), raise_exceptions=True) as session:
            initialized = await session.initialize()
            assert initialized.instructions == adapter_session.startup_metadata(runtime)["instructions"] == AGENT_INSTRUCTIONS
            assert "mcb_health once" in initialized.instructions and "built-in acceptance" in initialized.instructions
    finally: await client.aclose()
