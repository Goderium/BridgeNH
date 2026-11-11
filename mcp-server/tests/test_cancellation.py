import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration

TOKEN = "cancellation-secret-session-token"


@pytest.mark.parametrize("failure", ["unreachable", "redirect", "unauthorized", "malformed", "wrong-id", "oversized"])
async def test_cancel_without_valid_ack_keeps_action_outcome_uncertain(failure):
    calls = []

    async def respond(request):
        body = json.loads(request.content)
        calls.append((request.url.path, body))
        if request.url.path == "/v1/cancel":
            assert request.headers["Authorization"] == "Bearer " + TOKEN
            if failure == "unreachable":
                raise httpx.ConnectError("unsafe " + TOKEN, request=request)
            if failure == "redirect":
                return httpx.Response(302, headers={"Location": "http://example.com"})
            if failure == "unauthorized":
                return httpx.Response(401)
            if failure == "malformed":
                return httpx.Response(200, content=b"garbage", headers={"Content-Type": "application/json"})
            if failure == "oversized":
                return httpx.Response(200, content=b" " * 4097, headers={"Content-Type": "application/json"})
            return httpx.Response(200, json={"requestId": "different-request", "ok": True, "data": {"status": "cancelled"}})
        await asyncio.sleep(1)
        raise AssertionError("Timed-out action should not return")

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": TOKEN}), timeout=0.02, transport=httpx.MockTransport(respond))
    try:
        # Direct transport tests avoid a health probe so the timed-out call is the mutation.
        with pytest.raises(BridgeError) as raised:
            await client.call("player.hotbar", {"slot": 2})
        error = raised.value
        assert error.code == "BRIDGE_TIMEOUT"
        assert error.details["outcome_unknown"] is True
        assert error.details["cancellation_status"] == "unconfirmed"
        assert calls[0][0] == "/v1/call" and calls[1][0] == "/v1/cancel"
        assert calls[0][1]["requestId"] == calls[1][1]["requestId"]
        assert len(calls) == 2
        assert TOKEN not in str(error.as_dict())
    finally:
        await client.aclose()


async def test_inflight_cancel_is_shielded_and_uses_original_credentials_after_rotation():
    env = {"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": TOKEN}
    entered = asyncio.Event()
    calls = []

    async def respond(request):
        body = json.loads(request.content)
        calls.append((str(request.url), request.headers["Authorization"], body))
        if request.url.path == "/v1/cancel":
            await asyncio.sleep(0.03)  # Cancellation must not immediately abort this cleanup.
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"status": "cancelled"}})
        entered.set()
        await asyncio.sleep(10)
        raise AssertionError("Cancelled mutation should not return")

    client = BridgeClient(Configuration(env), transport=httpx.MockTransport(respond))
    try:
        task = asyncio.create_task(client.call("player.hotbar", {"slot": 1}))
        await entered.wait()
        env.update(MCB_URL="http://127.0.0.1:25672", MCB_TOKEN="rotated-session-token")
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert len(calls) == 2
        assert calls[1][0] == "http://127.0.0.1:25671/v1/cancel"
        assert calls[1][1] == "Bearer " + TOKEN
        assert calls[0][2]["requestId"] == calls[1][2]["requestId"]
    finally:
        await client.aclose()
