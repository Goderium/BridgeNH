import json
import os
import pytest
import httpx

from minecraft_codex_bridge import runtime_discovery
from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration


def write_runtime(root, name, port, stamp):
    runtime = root / name / ".minecraft" / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": f"http://127.0.0.1:{port}", "token": name}), encoding="utf-8")
    os.utime(runtime, (stamp, stamp))
    return runtime


def test_auto_discovery_selects_newest_live_runtime(tmp_path, monkeypatch):
    write_runtime(tmp_path, "old-client", 25671, 1)
    newest = write_runtime(tmp_path, "new-client", 25672, 2)
    monkeypatch.setattr(runtime_discovery, "_port_is_open", lambda url, timeout=0.12: True)
    configuration = Configuration({"MCB_INSTANCE_ROOT": str(tmp_path)})
    assert configuration.runtime_path() == newest
    assert configuration.connection_info()["instancePath"].endswith("new-client")


def test_explicit_runtime_remains_authoritative(tmp_path):
    runtime = write_runtime(tmp_path, "pinned", 25671, 1)
    assert Configuration({"MCB_RUNTIME_FILE": str(runtime)}).runtime_path() == runtime


def test_auto_discovery_reports_missing_live_runtime(tmp_path, monkeypatch):
    write_runtime(tmp_path, "stopped", 25671, 1)
    monkeypatch.setattr(runtime_discovery, "_port_is_open", lambda url, timeout=0.12: False)
    try:
        Configuration({"MCB_INSTANCE_ROOT": str(tmp_path)}).read()
    except BridgeError as error:
        assert error.code == "RUNTIME_DISCOVERY_FAILED"
        assert "stopped" in error.message
    else:
        raise AssertionError("missing live runtime was accepted")


def test_discovery_pins_instance_and_never_falls_back(tmp_path, monkeypatch):
    pinned = write_runtime(tmp_path, "first", 25671, 1)
    monkeypatch.setattr(runtime_discovery, "_port_is_open", lambda url, timeout=0.12: True)
    config = Configuration({"MCB_INSTANCE_ROOT": str(tmp_path)})
    assert config.read().token == "first"
    write_runtime(tmp_path, "other", 25672, 2)
    assert config.runtime_path() == pinned
    assert config.connection_info()["selection"] == "discovered_then_pinned"
    pinned.unlink()
    with pytest.raises(BridgeError): config.read()


def test_pinned_rotation_and_origin(tmp_path):
    runtime = write_runtime(tmp_path, "first", 25671, 1)
    config = Configuration({"MCB_INSTANCE_PATH": str(runtime.parents[3])})
    old = config.read()
    payload = {"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25672", "token": "new", "instancePath": str(runtime.parents[3]), "sessionId": "new-session"}
    runtime.write_text(json.dumps(payload), encoding="utf-8")
    assert config.read() != old and config.read().session_id == "new-session"
    payload["instancePath"] = str(tmp_path / "foreign")
    runtime.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(BridgeError) as e: config.read()
    assert e.value.code == "INSTANCE_MISMATCH"


async def test_wrong_live_identity_blocks_action(tmp_path):
    runtime = write_runtime(tmp_path, "first", 25671, 1)
    payload = json.loads(runtime.read_text(encoding="utf-8"))
    payload.update(instancePath=str(runtime.parents[3]), sessionId="expected")
    runtime.write_text(json.dumps(payload), encoding="utf-8")
    calls = []
    def respond(request):
        body = json.loads(request.content); calls.append(body["operation"])
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"protocolVersion": 1, "capabilities": ["player.hotbar"], "instancePath": payload["instancePath"], "sessionId": "foreign"}})
    client = BridgeClient(Configuration({"MCB_RUNTIME_FILE": str(runtime)}), transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BridgeError) as e: await client.invoke("player.hotbar", {"slot": 0})
        assert e.value.code == "INSTANCE_MISMATCH" and calls == ["bridge.health"]
    finally: await client.aclose()


async def test_rotation_during_action_preflight_blocks_action(tmp_path):
    runtime = write_runtime(tmp_path, "first", 25671, 1)
    calls = []
    def respond(request):
        body = json.loads(request.content); calls.append(body["operation"])
        payload = json.loads(runtime.read_text(encoding="utf-8"))
        payload["baseUrl"] = "http://127.0.0.1:25672"
        runtime.write_text(json.dumps(payload), encoding="utf-8")
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"protocolVersion": 1, "capabilities": ["player.hotbar"]}})
    client = BridgeClient(Configuration({"MCB_RUNTIME_FILE": str(runtime)}), transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BridgeError) as e: await client.invoke("player.hotbar", {"slot": 0})
        assert e.value.code == "SESSION_CHANGED" and calls == ["bridge.health"]
    finally: await client.aclose()
