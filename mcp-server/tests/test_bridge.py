import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, MAX_RESPONSE_BYTES, strict_json
from minecraft_codex_bridge.server import Adapter, condition_matches, wait_for
from minecraft_codex_bridge.tools import TOOLS

TOKEN = "test-session-token-which-must-never-be-printed"
CAPABILITIES = [tool.operation for tool in TOOLS if tool.operation]


def configuration(**updates):
    return Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": TOKEN, **updates})


def success(request, data):
    body = json.loads(request.content)
    return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})


def health(request):
    return success(request, {"protocolVersion": 1, "capabilities": CAPABILITIES, "profile": "player", "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}})


@pytest.mark.parametrize("url", [
    "https://127.0.0.1:25671", "http://localhost:25671", "http://127.0.0.2:25671",
    "http://example.com", "http://127.0.0.1/path", "http://127.0.0.1?token=secret",
    "http://user:password@127.0.0.1", "http://127.0.0.1#fragment", "http://127.0.0.1:99999",
    "http://127.0.0.1:0", "http://127.0.0.1\n", "http://127.0.0.1:abc",
])
def test_rejects_nonlocal_or_ambiguous_url(url):
    with pytest.raises(BridgeError, match="127.0.0.1"):
        configuration(MCB_URL=url).read()


@pytest.mark.parametrize("token", ["", "secret\nheader", "with space", "中文", "x" * 513])
def test_rejects_invalid_token(token):
    with pytest.raises(BridgeError):
        configuration(MCB_TOKEN=token).read()


def test_runtime_rotation_and_precedence(tmp_path):
    runtime = tmp_path / "runtime.json"
    env = {"MCB_RUNTIME_FILE": str(runtime), "MCB_URL": "http://example.com", "MCB_TOKEN": "ignored"}
    config = Configuration(env)
    with pytest.raises(BridgeError):
        config.read()
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671", "token": TOKEN}), encoding="utf-8")
    assert config.read().token == TOKEN
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25672", "token": "new-session-token"}), encoding="utf-8")
    assert config.read().base_url.endswith("25672")
    assert config.read().token == "new-session-token"
    assert TOKEN not in repr(config.read())


@pytest.mark.parametrize("raw", [b'{"key":1,"key":2}', b'{"value":NaN}', b'{"value":Infinity}', b'{"value":1e999}', b'\xff'])
def test_strict_json_rejects_malformed(raw):
    with pytest.raises((ValueError, UnicodeError)):
        strict_json(raw)


@pytest.mark.parametrize("status,code", [(401, "AUTH_FAILED"), (403, "AUTH_FAILED"), (500, "HTTP_ERROR"), (404, "HTTP_ERROR"), (302, "REDIRECT_REFUSED")])
async def test_http_failure_and_no_redirect(status, code):
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(status, headers={"Location": "http://example.com"}, content=TOKEN.encode())

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_health", {})
        assert result.isError
        assert result.structuredContent["error"]["code"] == code
        assert TOKEN not in result.model_dump_json()
        assert len(requests) == 1
    finally:
        await client.aclose()


@pytest.mark.parametrize("mode", ["wrong-id", "not-bool", "not-object", "non-json", "wrong-content-type", "duplicate-key", "too-large"])
async def test_malformed_response_and_size_limit(mode):
    def respond(request):
        body = json.loads(request.content)
        envelope = {"requestId": body["requestId"], "ok": True, "data": {}}
        if mode == "wrong-id":
            envelope["requestId"] = "another-request"
        if mode == "not-bool":
            envelope["ok"] = 1
        if mode == "not-object":
            envelope["data"] = []
        if mode == "non-json":
            return httpx.Response(200, content=b"not JSON", headers={"Content-Type": "application/json"})
        if mode == "wrong-content-type":
            return httpx.Response(200, content=b"{}", headers={"Content-Type": "text/plain"})
        if mode == "duplicate-key":
            return httpx.Response(200, content=b'{"ok":true,"ok":false}', headers={"Content-Type": "application/json"})
        if mode == "too-large":
            return httpx.Response(200, content=b" " * (MAX_RESPONSE_BYTES + 1), headers={"Content-Type": "application/json"})
        return httpx.Response(200, json=envelope)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BridgeError) as error:
            await client.call("bridge.health", {})
        assert error.value.code == ("RESPONSE_TOO_LARGE" if mode == "too-large" else "MALFORMED_RESPONSE")
    finally:
        await client.aclose()


async def test_unsupported_does_not_call_action():
    requests = []

    def respond(request):
        requests.append(json.loads(request.content)["operation"])
        return success(request, {"protocolVersion": 1, "capabilities": ["bridge.health"]})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_hotbar", {"slot": 1})
        assert result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
        assert requests == ["bridge.health"]
    finally:
        await client.aclose()


@pytest.mark.parametrize("name,args,operation", [
    ("mcb_hotbar", {"slot": 1}, "player.hotbar"),
    ("mcb_machine_work", {"x": 0, "y": 64, "z": 0, "expectedWorld": "test", "expectedMetaTileId": 1, "working": False}, "machine.set_working"),
    ("mcb_teleport", {"x": 0.5, "y": 64, "z": 0.5, "expectedWorld": "test"}, "dev.teleport"),
    ("mcb_load_world", {"folder": "test", "expectedSessionId": "session"}, "client.load_world"),
    ("mcb_leave_world", {"expectedWorld": "test", "expectedSessionId": "session"}, "client.leave_world"),
    ("mcb_exit", {"expectedSessionId": "session"}, "client.exit"),
    ("mcb_background", {"enabled": True}, "client.background"),
    ("mcb_mouse", {"enabled": True}, "client.mouse"),
    ("mcb_unfocused", {"enabled": True}, "client.unfocused"),
])
async def test_timeout_does_not_retry_mutation(name, args, operation):
    requests = []

    async def respond(request):
        if request.url.path == "/v1/cancel":
            return success(request, {"status": "already_running"})
        operation = json.loads(request.content)["operation"]
        requests.append(operation)
        if operation == "bridge.health":
            return health(request)
        await asyncio.sleep(1)
        return success(request, {})

    client = BridgeClient(configuration(), timeout=0.02, transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool(name, args)
        assert result.structuredContent["error"]["code"] == "BRIDGE_TIMEOUT"
        assert result.structuredContent["error"]["outcome_unknown"] is True
        assert requests == ["bridge.health", operation]
    finally:
        await client.aclose()


async def test_connection_failure():
    def respond(request):
        raise httpx.ConnectError("unsafe details " + TOKEN, request=request)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_snapshot", {})
        assert result.structuredContent["error"]["code"] == "BRIDGE_UNAVAILABLE"
        assert TOKEN not in result.model_dump_json()
    finally:
        await client.aclose()


@pytest.mark.parametrize("cancel_status,uncertain", [("cancelled", False), ("already_running", True), ("not_found", True)])
async def test_disconnected_action_cancels_original_request_without_replay(cancel_status, uncertain):
    calls = []
    trace = {}

    def respond(request):
        body = json.loads(request.content)
        calls.append((request.url.path, body))
        if request.url.path == "/v1/cancel":
            return success(request, {"status": cancel_status})
        raise httpx.RemoteProtocolError("response closed " + TOKEN, request=request)

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BridgeError) as failed:
            await client.call("player.navigate", {}, trace=trace)
        assert failed.value.code == "BRIDGE_UNAVAILABLE"
        assert trace["cancellation_status"] == cancel_status
        assert trace["outcome_unknown"] is uncertain
        assert len(calls) == 2
        assert calls[0][0] == "/v1/call" and calls[1][0] == "/v1/cancel"
        assert calls[0][1]["requestId"] == calls[1][1]["requestId"]
        assert TOKEN not in str(failed.value)
    finally:
        await client.aclose()


async def test_mod_error_and_response_token_redaction():
    def respond(request):
        body = json.loads(request.content)
        if body["operation"] == "bridge.health":
            return health(request)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": {"code": "PROFILE_DENIED", "message": "must select developer " + TOKEN}})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_chat", {"message": "/give player stone"})
        assert result.structuredContent["error"]["code"] == "PROFILE_DENIED"
        assert TOKEN not in result.model_dump_json()
    finally:
        await client.aclose()


async def test_unsupported_ae_coordinate_click_never_retries_or_falls_back():
    requests = []

    def respond(request):
        body = json.loads(request.content)
        requests.append(body["operation"])
        if body["operation"] == "bridge.health":
            return health(request)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": {"code": "UNSUPPORTED_SCREEN", "message": "AE2 cached-hover virtual grid requires a dedicated adapter."}})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool("mcb_gui_click", {"screenId": "ae-screen", "x": 50, "y": 50})
        assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_SCREEN"
        assert requests == ["bridge.health", "gui.click"]
    finally:
        await client.aclose()


@pytest.mark.parametrize("name,args", [
    ("mcb_hotbar", {"slot": True}), ("mcb_hotbar", {"slot": 9}), ("mcb_snapshot", {"unexpected": 1}),
    ("mcb_look", {"yaw": float("nan"), "pitch": 0}), ("mcb_world_scan", {"x": 0, "y": 64, "z": 0, "radius": 129}),
    ("mcb_look", {"yaw": 360_001, "pitch": 0}),
    ("mcb_interact", {"action": "use", "x": 0}),
    ("mcb_interact", {"action": "use", "restoreView": True}),
    ("mcb_interact", {"action": "attack", "x": 0, "y": 64, "z": 0, "sneaking": False}),
    ("mcb_interact", {"action": "use", "x": 0, "y": 64, "z": 0, "restoreView": "true"}), ("mcb_chat", {"message": "first\nsecond"}),
    ("mcb_input", {"keys": ["forward", "forward"], "ticks": 5}), ("mcb_input", {"keys": ["unknown"], "ticks": 5}),
    ("mcb_slot_click", {"windowId": 1, "slot": 0, "mode": 0, "button": 2}),
    ("mcb_slot_click", {"windowId": 1, "slot": 0, "mode": 5, "button": 0}),
    ("mcb_slot_click", {"windowId": 1, "slot": 0, "mode": 3}),
    ("mcb_gui_click", {"x": 1, "y": 1}), ("mcb_gui_click", {"screenId": "", "x": 1, "y": 1}),
    ("mcb_gui_key", {"screenId": "screen", "keyCode": 99}),
    ("mcb_gui_key", {"screenId": "screen", "keyCode": 0}),
    ("mcb_gui_key", {"screenId": "screen", "keyCode": 1, "character": "a"}),
    ("mcb_gui_key", {"screenId": "screen", "keyCode": 0, "character": "\n"}),
    ("mcb_gui_key", {"screenId": "screen", "keyCode": 0, "character": "\U0001F600"}),
    ("mcb_wait_for", {"tool": "mcb_chat", "condition": {"path": "screen", "operator": "exists"}}),
    ("mcb_wait_for", {"condition": {"path": "screen", "operator": "equals"}}),
    ("mcb_wait_for", {"condition": {"path": "screen", "operator": "exists", "value": "yes"}}),
    ("mcb_diagnostics", {"extra": 1}),
    ("mcb_mods", {"query": "x" * 129}), ("mcb_mods", {"offset": 10_001}),
    ("mcb_messages", {"since": -1}), ("mcb_messages", {"since": True}),
    ("mcb_items", {"offset": 100_001}), ("mcb_items", {"limit": 129}),
    ("mcb_item", {}), ("mcb_item", {"registryName": ""}),
    ("mcb_item", {"registryName": "minecraft:stone", "metadata": -1}),
    ("mcb_item_variants", {"registryName": "x" * 257}),
    ("mcb_world_inspect_many", {"positions": []}),
    ("mcb_world_inspect_many", {"positions": [{"x": 0, "y": 64, "z": 0}] * 17}),
    ("mcb_world_inspect_many", {"positions": [{"x": 0, "y": 256, "z": 0}]}),
    ("mcb_world_tiles", {"x": 0, "y": 64, "z": 0, "radius": 65}),
    ("mcb_machine_work", {"x": 0, "y": 64, "z": 0, "expectedMetaTileId": 1, "working": True}),
    ("mcb_machine_work", {"x": 0, "y": 64, "z": 0, "expectedWorld": "test", "expectedMetaTileId": 1, "working": 1}),
    ("mcb_machine_work", {"x": 0, "y": 64, "z": 0, "expectedWorld": "test", "expectedMetaTileId": 32_768, "working": True}),
    ("mcb_teleport", {"x": 0, "y": 64, "z": 0}),
    ("mcb_teleport", {"x": 0, "y": 64, "z": 0, "expectedWorld": ""}),
    ("mcb_teleport", {"x": float("inf"), "y": 64, "z": 0, "expectedWorld": "test"}),
    ("mcb_logs", {"path": "anything"}), ("mcb_logs", {"level": "INFO"}),
    ("mcb_logs", {"limit": 0}),
    ("mcb_capture", {"include_screenshot": 1}), ("mcb_capture", {"positions": [{"x": 0, "y": 64}]}),
    ("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "duration_ms": 30_001}),
    ("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "interval_ms": 199}),
    ("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "fields": []}),
    ("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "fields": ["gregtech.progress"] * 2}),
    ("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "fields": ["gregtech..progress"]}),
    ("mcb_wait_for", {"tool": "mcb_capture", "condition": {"path": "screen", "operator": "exists"}}),
    ("mcb_wait_for", {"tool": "mcb_machine_watch", "condition": {"path": "screen", "operator": "exists"}}),
    ("mcb_worlds", {"limit": 129}),
    ("mcb_load_world", {"folder": "test"}), ("mcb_load_world", {"folder": "../test", "expectedSessionId": "session"}),
    ("mcb_load_world", {"folder": "..", "expectedSessionId": "session"}),
    ("mcb_leave_world", {"expectedWorld": "test"}),
    ("mcb_exit", {}), ("mcb_exit", {"expectedSessionId": ""}),
    ("mcb_background", {"enabled": 1}), ("mcb_launch", {"command": "anything"}),
    ("mcb_mouse", {}), ("mcb_mouse", {"enabled": 1}), ("mcb_mouse", {"enabled": False, "extra": 1}),
    ("mcb_unfocused", {}), ("mcb_unfocused", {"enabled": "true"}), ("mcb_unfocused", {"enabled": True, "extra": 1}),
])
async def test_invalid_arguments_never_touch_bridge(name, args):
    def respond(request):
        pytest.fail("Invalid arguments reached HTTP bridge")

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await Adapter(client).call_tool(name, args)
        assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    finally:
        await client.aclose()


@pytest.mark.parametrize("observation,condition,expected", [
    ({"player": {"health": 20}}, {"path": "player.health", "operator": "equals", "value": 20}, True),
    ({"value": True}, {"path": "value", "operator": "equals", "value": 1}, False),
    ({"slots": [{"name": "stone"}]}, {"path": "slots.0.name", "operator": "contains", "value": "sto"}, True),
    ({"value": [True, 2]}, {"path": "value", "operator": "contains", "value": 1}, False),
    ({"value": {"active": True}}, {"path": "value", "operator": "contains", "value": "active"}, True),
    ({"value": None}, {"path": "value", "operator": "exists"}, True),
    ({}, {"path": "value", "operator": "exists", "value": False}, True),
    ({}, {"path": "value", "operator": "equals", "value": None}, False),
])
def test_condition_semantics(observation, condition, expected):
    assert condition_matches(observation, condition) is expected


async def test_wait_success_and_timeout_return_last_observation():
    count = 0

    def respond(request):
        nonlocal count
        if json.loads(request.content)["operation"] == "bridge.health":
            return health(request)
        count += 1
        return success(request, {"counter": count})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        result = await wait_for(client, {"condition": {"path": "counter", "operator": "equals", "value": 2}, "interval_ms": 100, "timeout_ms": 1_000})
        assert result["matched"] and result["attempts"] == 2
        timed_out = await Adapter(client).call_tool("mcb_wait_for", {"condition": {"path": "counter", "operator": "equals", "value": -1}, "interval_ms": 100, "timeout_ms": 150})
        assert timed_out.isError
        assert timed_out.structuredContent["error"]["code"] == "WAIT_TIMEOUT"
        assert timed_out.structuredContent["error"]["observation"]["counter"] >= 3
    finally:
        await client.aclose()


async def test_wait_cancellation_stops_polls():
    calls = []

    def respond(request):
        calls.append(json.loads(request.content)["operation"])
        return health(request) if calls[-1] == "bridge.health" else success(request, {"active": False})

    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    try:
        task = asyncio.create_task(wait_for(client, {"condition": {"path": "active", "operator": "equals", "value": True}, "interval_ms": 100, "timeout_ms": 60_000}))
        await asyncio.sleep(0.02)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        count = len(calls)
        await asyncio.sleep(0.15)
        assert len(calls) == count == 2
    finally:
        await client.aclose()


async def test_runtime_rotation_changes_request_url_and_authorization(tmp_path):
    runtime = tmp_path / "runtime.json"
    calls = []

    def write(port, token):
        runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": f"http://127.0.0.1:{port}", "token": token}), encoding="utf-8")

    def respond(request):
        calls.append((str(request.url), request.headers["Authorization"]))
        return health(request)

    write(25671, TOKEN)
    client = BridgeClient(Configuration({"MCB_RUNTIME_FILE": str(runtime)}), transport=httpx.MockTransport(respond))
    try:
        await client.invoke("bridge.health", {})
        write(25672, "rotated-session-token")
        await client.invoke("bridge.health", {})
        assert calls == [("http://127.0.0.1:25671/v1/call", "Bearer " + TOKEN), ("http://127.0.0.1:25672/v1/call", "Bearer rotated-session-token")]
    finally:
        await client.aclose()
