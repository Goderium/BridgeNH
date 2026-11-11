"""Transport contract for the internal, read-only native approach planner.

These mock HTTP tests exercise framing and fresh context, independently of
construction's movement and inventory state machine or Java's native rays.
"""

import asyncio
import copy
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import (
    BridgeClient, BridgeError, Configuration, Credentials, request_context,
)


OPERATION = "player.plan_approach"
TOKEN = "approach-contract-token-do-not-print"
CONTEXT = {
    "version": 1,
    "expectedSessionId": "approach-session",
    "expectedProfile": "developer",
    "expectedWorld": "Approach Test",
    "requireBackground": True,
}


def configuration():
    return Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": TOKEN})


def fresh_context(operation=OPERATION):
    return {
        "version": 1, "operation": operation, "phase": "after",
        "snapshot": {
            "sessionId": CONTEXT["expectedSessionId"], "profile": "developer",
            "worldLoaded": True,
            "world": {"worldName": CONTEXT["expectedWorld"], "dimension": 180},
            "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False},
            "player": {"x": 0.5, "feetY": 64, "z": 0.5, "selectedHotbarSlot": 3},
        },
    }


def response(request, *, data=None, context=None, error=None):
    body = json.loads(request.content)
    envelope = {"requestId": body["requestId"], "ok": error is None}
    if error is not None:
        envelope["error"] = error
    else:
        envelope["data"] = {} if data is None else data
        if context is not None:
            envelope["context"] = context
    return httpx.Response(200, json=envelope)


@pytest.mark.asyncio
async def test_planner_is_guarded_read_and_retains_after_snapshot_without_an_action():
    calls = []
    context = fresh_context()
    evidence = {"available": False, "reason": "no_native_visible_face", "nonAtomic": True}

    def respond(request):
        calls.append(json.loads(request.content))
        assert request.url.path == "/v1/call"
        return response(request, data=evidence, context=context)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with request_context(copy.deepcopy(CONTEXT)):
            data = await client.call(OPERATION, {}, trace=trace)
        assert data == evidence
        assert data.context == context
        assert data.context["snapshot"]["player"] == context["snapshot"]["player"]
        assert len(calls) == 1 and calls[0]["operation"] == OPERATION
        assert calls[0]["includeContext"] == CONTEXT
        assert trace["dispatched"] is True
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", ["missing", "before", "wrong-operation", "boolean-version", "missing-snapshot", "extra"])
async def test_planner_malformed_context_stops_as_read_without_action_fallback(fault):
    calls = []

    def respond(request):
        calls.append(json.loads(request.content))
        context = fresh_context()
        if fault == "missing":
            context = None
        elif fault == "before":
            context["phase"] = "before"
        elif fault == "wrong-operation":
            context["operation"] = "player.interact"
        elif fault == "boolean-version":
            context["version"] = True
        elif fault == "missing-snapshot":
            context.pop("snapshot")
        else:
            context["retry"] = True
        return response(request, data={"available": True}, context=context)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with request_context(CONTEXT), pytest.raises(BridgeError) as caught:
            await client.call(OPERATION, {}, trace=trace)
        assert caught.value.code == "MALFORMED_RESPONSE"
        assert caught.value.details["outcome_unknown"] is False
        assert trace["outcome_unknown"] is False
        assert [call["operation"] for call in calls] == [OPERATION]
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("cancellation_status", ["cancelled", "already_running", "not_found"])
async def test_planner_timeout_cancels_original_request_without_replay_or_unknown_mutation(cancellation_status):
    calls = []

    async def respond(request):
        body = json.loads(request.content)
        calls.append((request.url.path, body))
        if request.url.path == "/v1/cancel":
            return response(request, data={"status": cancellation_status})
        await asyncio.sleep(1)
        return response(request, data={}, context=fresh_context())

    client = BridgeClient(configuration(), timeout=0.02, transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with request_context(CONTEXT), pytest.raises(BridgeError) as caught:
            await client.call(OPERATION, {}, trace=trace)
        assert caught.value.code == "BRIDGE_TIMEOUT"
        assert caught.value.details["outcome_unknown"] is False
        assert trace["outcome_unknown"] is False
        assert trace["cancellation_status"] == cancellation_status
        assert [path for path, _ in calls] == ["/v1/call", "/v1/cancel"]
        assert calls[0][1]["operation"] == OPERATION
        assert calls[1][1] == {"requestId": calls[0][1]["requestId"]}
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_planner_connection_failure_is_read_only_and_does_not_retry():
    calls = []

    def respond(request):
        calls.append(json.loads(request.content))
        raise httpx.ConnectError("connection error " + TOKEN, request=request)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        with request_context(CONTEXT), pytest.raises(BridgeError) as caught:
            await client.call(OPERATION, {})
        assert caught.value.code == "BRIDGE_UNAVAILABLE"
        assert caught.value.details["outcome_unknown"] is False
        assert TOKEN not in str(caught.value.as_dict())
        assert len(calls) == 1 and calls[0]["operation"] == OPERATION
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_rotated_runtime_credentials_refuse_planner_before_http():
    def respond(request):
        pytest.fail("Stale source credentials dispatched an approach read")

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        old_credentials = Credentials("http://127.0.0.1:25672", "old-approach-token")
        with request_context(CONTEXT), pytest.raises(BridgeError) as caught:
            await client.call(OPERATION, {}, expected_credentials=old_credentials)
        assert caught.value.code == "SESSION_CHANGED"
        assert caught.value.details["outcome_unknown"] is False
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["permission_denied", "stale_world", "timeout", "chunk_unloaded"])
async def test_advertised_planner_error_retains_failure_without_a_second_read_or_action(code):
    calls = []

    def respond(request):
        calls.append(json.loads(request.content))
        return response(request, error={"code": code, "message": "Planner refused " + TOKEN, "outcome_unknown": False})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        with request_context(CONTEXT), pytest.raises(BridgeError) as caught:
            await client.call(OPERATION, {})
        assert caught.value.code == code
        assert caught.value.details["outcome_unknown"] is False
        assert TOKEN not in str(caught.value.as_dict())
        assert [call["operation"] for call in calls] == [OPERATION]
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_scoped_planner_context_does_not_leak_to_following_plain_read():
    calls = []

    def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        return response(request, context=fresh_context() if body["operation"] == OPERATION else None)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        with request_context(CONTEXT):
            await client.call(OPERATION, {})
        await client.call("client.snapshot", {})
        assert calls[0]["includeContext"] == CONTEXT
        assert "includeContext" not in calls[1]
        assert [call["operation"] for call in calls] == [OPERATION, "client.snapshot"]
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_inflight_runtime_rotation_cancels_only_original_planner_session():
    environment = {"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": TOKEN}
    config = Configuration(environment)
    original = config.read()
    calls = []

    async def respond(request):
        body = json.loads(request.content)
        calls.append((str(request.url), request.headers["Authorization"], body))
        if request.url.path == "/v1/cancel":
            return response(request, data={"status": "already_running"})
        environment.update(MCB_URL="http://127.0.0.1:25672", MCB_TOKEN="rotated-approach-token")
        await asyncio.sleep(1)
        return response(request, context=fresh_context())

    client = BridgeClient(config, timeout=0.02, transport=httpx.MockTransport(respond))
    try:
        with request_context(CONTEXT), pytest.raises(BridgeError) as timeout:
            await client.call(OPERATION, {}, expected_credentials=original)
        assert timeout.value.code == "BRIDGE_TIMEOUT"
        assert timeout.value.details["outcome_unknown"] is False
        assert [url for url, _, _ in calls] == [
            "http://127.0.0.1:25671/v1/call", "http://127.0.0.1:25671/v1/cancel",
        ]
        assert [authorization for _, authorization, _ in calls] == ["Bearer " + TOKEN] * 2
        assert calls[1][2] == {"requestId": calls[0][2]["requestId"]}
        with request_context(CONTEXT), pytest.raises(BridgeError) as changed:
            await client.call(OPERATION, {}, expected_credentials=original)
        assert changed.value.code == "SESSION_CHANGED" and len(calls) == 2
    finally:
        await client.aclose()
