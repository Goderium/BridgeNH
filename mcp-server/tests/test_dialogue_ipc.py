import asyncio
import json
import struct
import uuid
import pytest
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.dialogue_host import AppToolsHost
from minecraft_codex_bridge.dialogue_ipc import DesktopSteering, MAX_FRAME


class Desktop:
    """Framed in-memory peer: tests the wire adapter without sending real input."""
    def __init__(self, native, monkeypatch, fault=None, stage="thread-follower-steer-turn"):
        self.reader = asyncio.StreamReader()
        self.owner, self.client, self.turn = [str(uuid.uuid4()) for _ in range(3)]
        self.requests, self.other = [], []
        self.fault, self.stage, self.closed = fault, stage, False
        async def open_peer():
            return self.reader, self
        monkeypatch.setattr(native, "_open", open_peer)

    def write(self, raw):
        size = struct.unpack("<I", raw[:4])[0]
        assert len(raw) == size + 4
        message = json.loads(raw[4:])
        if message["type"] != "request":
            self.other.append(message)
            return
        self.requests.append(message)
        method = message["method"]
        reply = {"type": "response", "requestId": message["requestId"], "method": method,
                 "resultType": "success", "handledByClientId": self.owner}
        if method == "initialize":
            reply["result"] = {"clientId": self.client}
        elif method == "thread-owner-discovery":
            reply["result"] = {"supportsUntrustedAppInput": True}
        elif method == "thread-follower-read-model-settings":
            reply["result"] = {"settings": {"resumeState": "resumed"}}
        elif method == "thread-follower-steer-turn":
            reply["result"] = {"result": {"turnId": self.turn}}
        else:
            pytest.fail("Unexpected mutation or unsupported method: " + method)
        if method == self.stage and self.fault:
            reply = self.fault(reply, self)
        if reply is not None:
            self.feed(reply)

    def feed(self, row):
        raw = json.dumps(row, ensure_ascii=False).encode()
        frame = struct.pack("<I", len(raw)) + raw
        # Frame and Unicode boundaries can split arbitrarily.
        for i in range(0, len(frame), 7):
            self.reader.feed_data(frame[i:i+7])

    async def drain(self):
        pass

    def close(self):
        self.closed = True


@pytest.mark.asyncio
async def test_native_wire_selects_exact_local_owner_and_returns_accepted_turn(monkeypatch):
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch)
    message_id = str(uuid.uuid4())
    result = await native.steer(message_id, "玩家引导 Unicode / 内容")
    assert result == {"mode": "steer", "turnId": desktop.turn, "transport": "desktop-ipc"}
    init, discovery, steer = desktop.requests
    assert init["params"] == {"clientType": "mcb"}
    assert discovery["params"] == {"hostId": "local", "conversationId": native.thread_id}
    assert steer["method"] == "thread-follower-steer-turn"
    assert steer["targetClientId"] == desktop.owner and steer["sourceClientId"] == desktop.client
    assert steer["version"] == 2 and steer["hostId"] == "local"
    params = steer["params"]
    assert params["conversationId"] == native.thread_id
    assert params["clientUserMessageId"] == message_id
    assert params["input"] == [{"type": "text", "text": "玩家引导 Unicode / 内容", "text_elements": []}]
    assert params["restoreMessage"]["text"] == params["input"][0]["text"]
    assert not {"toolOutput", "serviceTier", "model", "expectedTurnId", "additionalContext"} & params.keys()
    assert not {"workspaceRoots", "collaborationMode", "permissions"} & params["restoreMessage"]["context"].keys()
    assert desktop.closed


@pytest.mark.asyncio
async def test_probe_is_read_only_and_does_not_claim_ownership(monkeypatch):
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch)
    await native.probe()
    assert [r["method"] for r in desktop.requests] == ["initialize", "thread-owner-discovery", "thread-follower-read-model-settings"]
    assert desktop.requests[-1]["params"] == {"conversationId": native.thread_id}
    assert desktop.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["no-client-found", "request-version-mismatch", "no-handler-for-request", "idle"])
async def test_known_native_rejection_does_not_start_or_retry(monkeypatch, failure):
    native = DesktopSteering(str(uuid.uuid4()))
    error = f"Cannot steer conversation {native.thread_id} because its active turn already ended" if failure == "idle" else failure
    desktop = Desktop(native, monkeypatch, lambda row, _: {**row, "resultType": "error", "error": error})
    with pytest.raises(BridgeError) as caught:
        await native.steer(str(uuid.uuid4()), "guide")
    assert caught.value.code == "DIALOGUE_HOST_REJECTED"
    assert len(desktop.requests) == 3 and desktop.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["request-timeout", "client-disconnected", "thread-follower-steer-turn-timeout", "unrecognized"])
async def test_timeout_or_unknown_host_failure_never_claims_no_delivery(monkeypatch, failure):
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch, lambda row, _: {**row, "resultType": "error", "error": failure})
    with pytest.raises(BridgeError) as caught:
        await native.steer(str(uuid.uuid4()), "guide")
    assert caught.value.code == "DIALOGUE_HOST_UNCERTAIN"
    assert len(desktop.requests) == 3 and desktop.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", ["owner", "method", "turn", "turn-type", "result-type", "array", "eof", "zero", "oversized", "bad-json"])
async def test_lost_or_invalid_native_acknowledgement_is_uncertain(monkeypatch, fault):
    def corrupt(row, peer):
        if fault == "owner": return {**row, "handledByClientId": str(uuid.uuid4())}
        if fault == "method": return {**row, "method": "thread-follower-start-turn"}
        if fault == "turn": return {**row, "result": {"result": {"turnId": ""}}}
        if fault == "turn-type": return {**row, "result": {"result": {"turnId": []}}}
        if fault == "result-type": return {**row, "result": []}
        if fault == "array": return []
        if fault == "eof": peer.reader.feed_eof()
        elif fault in {"zero", "oversized"}: peer.reader.feed_data(struct.pack("<I", 0 if fault == "zero" else MAX_FRAME+1))
        else: peer.reader.feed_data(struct.pack("<I", 1)+b"{")
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch, corrupt)
    with pytest.raises(BridgeError) as caught:
        await native.steer(str(uuid.uuid4()), "guide")
    assert caught.value.code == "DIALOGUE_HOST_UNCERTAIN"
    assert len(desktop.requests) == 3 and desktop.closed


@pytest.mark.asyncio
async def test_no_owner_or_missing_input_support_refuses_before_submission(monkeypatch):
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch, lambda row, _: {**row, "result": {}}, "thread-owner-discovery")
    with pytest.raises(BridgeError) as caught:
        await native.steer(str(uuid.uuid4()), "guide")
    assert caught.value.code == "DIALOGUE_STEER_UNAVAILABLE"
    assert len(desktop.requests) == 2


@pytest.mark.asyncio
async def test_discovery_and_requests_to_relay_are_refused(monkeypatch):
    def extras(row, peer):
        peer.feed({"type": "client-discovery-request", "requestId": "discovery", "request": {"method": "thread-owner-discovery"}})
        peer.feed({"type": "request", "requestId": "incoming", "method": "thread-follower-start-turn"})
        peer.feed({"type": "broadcast", "method": "unrelated", "params": {}})
        return row
    native = DesktopSteering(str(uuid.uuid4()))
    desktop = Desktop(native, monkeypatch, extras)
    await native.steer(str(uuid.uuid4()), "guide")
    assert desktop.other == [
        {"type": "client-discovery-response", "requestId": "discovery", "response": {"canHandle": False}},
        {"type": "response", "requestId": "incoming", "resultType": "error", "error": "no-handler-for-request"}]


@pytest.mark.asyncio
async def test_host_routes_guidance_only_to_native_transport(monkeypatch):
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4()))
    desktop = Desktop(host.native, monkeypatch)
    host.native_ready = True
    async def no_app_tools(*args): pytest.fail("steer must never use queued App Tools input")
    monkeypatch.setattr(host, "call", no_app_tools)
    row = {"id": str(uuid.uuid4()), "playerUuid": str(uuid.uuid4()), "playerName": "Alice", "permissionGroup": "user", "mode": "steer", "text": "请继续", "replyRef": "ab12cd34ef"}
    result = await host.send(row)
    assert result["turnId"] == desktop.turn
    text = desktop.requests[-1]["params"]["input"][0]["text"]
    assert text == "[MCB 玩家消息 · Alice · 用户 · 引导 · 回复 #ab12cd34ef]\n请继续"
    assert row["id"] not in text and row["playerUuid"] not in text
    assert desktop.requests[-1]["params"]["clientUserMessageId"] == row["id"]


@pytest.mark.asyncio
@pytest.mark.parametrize("group,label", [("admin", "管理员"), ("agent", "AGENT"), ("console", "服务器后台"), ("unexpected", "未验证权限")])
async def test_role_is_a_bounded_server_attribute(monkeypatch, group, label):
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4()))
    desktop = Desktop(host.native, monkeypatch)
    host.native_ready = True
    row = {"id": str(uuid.uuid4()), "playerName": "Example", "permissionGroup": group, "mode": "steer", "text": "body", "replyRef": "ab12cd34ef"}
    await host.send(row)
    assert f" · {label} · " in desktop.requests[-1]["params"]["input"][0]["text"]


@pytest.mark.asyncio
@pytest.mark.parametrize("available", [True, False])
async def test_host_capabilities_require_native_probe(monkeypatch, available):
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4()))
    async def catalog(*args):
        return {"tools": [{"name": name, "namespace": "codex_app"} for name in ("list_threads", "send_message_to_thread")]}
    async def status(): return "active"
    monkeypatch.setattr(host, "_request", catalog)
    monkeypatch.setattr(host, "status", status)
    Desktop(host.native, monkeypatch)
    if not available:
        async def closed(): raise OSError("not listening")
        monkeypatch.setattr(host.native, "_open", closed)
    await host.verify()
    assert host.capabilities()["say"] is True
    assert host.capabilities()["steer"] is available
