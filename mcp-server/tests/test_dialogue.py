import json
import time
import uuid
import os
import subprocess
from pathlib import Path

import pytest
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.dialogue import Relay, instance_lock, read_state, write_state, relay_running, relay_command, state_path, manage
from minecraft_codex_bridge.dialogue_host import AppToolsHost, STEER_UNAVAILABLE
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.bridge import Configuration
from minecraft_codex_bridge.validation import validate_tool_arguments
from minecraft_codex_bridge.dialogue_log import DialogueLog


class Client:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []

    async def invoke(self, operation, args):
        assert operation == "agent.dialogue"
        self.calls.append(args)
        if args["action"] == "poll":
            return {"messages": self.rows}
        return {}


class Host:
    def __init__(self, state="active", failure=None, native=False):
        self.state, self.failure, self.sent = state, failure, []
        self.reads = 0
        self.native = native
        self.turn_id = str(uuid.uuid4())
        self.reply_channel = "server"

    def capabilities(self):
        return {"say": True, "steer": self.native}

    async def status(self):
        self.reads += 1
        return self.state

    async def send(self, row):
        self.sent.append(row)
        if self.failure:
            raise BridgeError(self.failure, "fixture")
        return {"mode": row["mode"], **({"turnId": self.turn_id} if row["mode"] == "steer" else {})}


def row(mode):
    return {"id": str(uuid.uuid4()), "mode": mode, "text": "保留中文 Case / 空格", "playerUuid": str(uuid.uuid4()), "playerName": "Alice", "replyRef": "ab12cd34ef"}


@pytest.mark.asyncio
async def test_active_normal_waits_and_unsupported_guidance_is_not_sent():
    normal, guide = row("say"), row("steer")
    client, host = Client([normal, guide]), Host()
    await Relay(client, host, "lease", "session").step()
    assert host.sent == []
    assert host.reads == 1
    assert [c.get("state") for c in client.calls[1:]] == ["failed"]
    assert client.calls[-1]["messageId"] == guide["id"]
    assert client.calls[-1]["text"] == STEER_UNAVAILABLE
    assert all(c["expectedSessionId"] == "session" and c["leaseId"] == "lease" for c in client.calls)


@pytest.mark.asyncio
async def test_native_guidance_passes_queued_normal_and_keeps_turn_receipt():
    normal, guide = row("say"), row("steer")
    client, host = Client([normal, guide]), Host(native=True)
    relay = Relay(client, host, "lease", "session")
    await relay.step()
    assert host.sent == [guide]
    assert [c.get("state") for c in client.calls[1:]] == ["dispatching", "delivered"]
    assert host.turn_id in client.calls[-1]["text"]
    assert relay.last_delivery == {"messageId": guide["id"], "mode": "steer", "turnId": host.turn_id}


@pytest.mark.asyncio
async def test_native_guidance_when_idle_never_starts_new_turn():
    client, host = Client([row("steer")]), Host("idle", native=True)
    await Relay(client, host, "lease", "session").step()
    assert not host.sent
    assert client.calls[-1]["state"] == "failed"


@pytest.mark.asyncio
async def test_idle_say_does_not_make_same_batch_steer_target_a_new_turn():
    normal, guide = row("say"), row("steer")
    client, host = Client([normal, guide]), Host("idle", native=True)
    await Relay(client, host, "lease", "session").step()
    assert host.sent == [normal]
    assert client.calls[-1]["messageId"] == guide["id"]
    assert client.calls[-1]["state"] == "failed"


@pytest.mark.asyncio
async def test_idle_starts_only_one_normal_turn():
    first, second = row("say"), row("say")
    client, host = Client([first, second]), Host("idle")
    await Relay(client, host, "lease", "session").step()
    assert host.sent == [first]


@pytest.mark.asyncio
@pytest.mark.parametrize("state", ["idle", "active", "notLoaded"])
async def test_unsupported_guidance_never_queries_or_sends_to_host(state):
    client, host = Client([row("steer")]), Host(state)
    await Relay(client, host, "lease", "session").step()
    assert not host.sent
    assert host.reads == 0
    assert client.calls[-1]["state"] == "failed"
    assert client.calls[-1]["text"] == STEER_UNAVAILABLE


@pytest.mark.asyncio
@pytest.mark.parametrize("guidance_first", [True, False])
async def test_unsupported_guidance_does_not_block_normal_input(guidance_first):
    guide, normal = row("steer"), row("say")
    client = Client([guide, normal] if guidance_first else [normal, guide])
    host = Host("idle")
    await Relay(client, host, "lease", "session").step()
    assert host.sent == [normal]
    assert [(c["messageId"], c["state"]) for c in client.calls[1:]] == [
        (guide["id"], "failed"), (normal["id"], "dispatching"), (normal["id"], "delivered")]


@pytest.mark.asyncio
@pytest.mark.parametrize("failure,expected", [("DIALOGUE_HOST_UNCERTAIN", "unknown"), ("DIALOGUE_HOST_REJECTED", "failed"), ("DIALOGUE_STEER_UNAVAILABLE", "failed")])
@pytest.mark.parametrize("mode", ["say", "steer"])
async def test_dispatch_failure_never_requeues_or_retries(failure, expected, mode):
    client, host = Client([row(mode)]), Host("idle" if mode == "say" else "active", failure=failure, native=True)
    await Relay(client, host, "lease", "session").step()
    assert len(host.sent) == 1
    assert client.calls[-1]["state"] == expected


@pytest.mark.asyncio
async def test_empty_poll_never_queries_host():
    client, host = Client([]), Host()
    await Relay(client, host, "lease", "session").step()
    assert host.reads == 0


def test_process_lock_and_atomic_bounded_state(tmp_path):
    path = tmp_path / "state.json"
    with instance_lock(path):
        with pytest.raises(BridgeError, match="already running"):
            with instance_lock(path):
                pass
        write_state(path, {"pid": 7, "status": "running"})
        assert read_state(path)["pid"] == 7
    with instance_lock(path):
        pass
    path.write_bytes(b"x" * 8193)
    assert read_state(path) == {}


def test_stale_running_file_does_not_certify_liveness(tmp_path):
    path = tmp_path / "state.json"
    state = {"status": "running", "heartbeat": time.time()}
    assert not relay_running(path, state)
    with instance_lock(path):
        assert relay_running(path, state)
        assert not relay_running(path, {**state, "heartbeat": time.time() - 61})


def test_isolated_helper_retains_dependencies_and_reports_startup_failure(tmp_path):
    env = {**os.environ, "LOCALAPPDATA": str(tmp_path), "CODEX_THREAD_ID": str(uuid.uuid4()),
           "CODEX_APP_TOOLS_PIPE_PATH": "", "MCB_RUNTIME_FILE": str(tmp_path / "runtime.json")}
    result = subprocess.run(relay_command(), env=env, capture_output=True, timeout=15,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    assert result.returncode == 0, result.stderr.decode(errors="replace")[:1000]
    state = read_state(state_path(Configuration(env)))
    assert state["status"] == "failed"
    assert state["lastError"] == "DIALOGUE_HOST_UNAVAILABLE"


def test_dialogue_schema_requires_correlated_reply():
    validate_tool_arguments("mcb_dialogue", {"action": "reply", "messageId": str(uuid.uuid4()), "text": "收到"})
    assert TOOL_BY_NAME["mcb_dialogue"].read_only is False
    for args in ({"action": "reply", "text": "oops"}, {"action": "start", "threadId": "another"}, {"action": "run", "command": "anything"}):
        with pytest.raises(BridgeError):
            validate_tool_arguments("mcb_dialogue", args)


@pytest.mark.asyncio
async def test_host_keeps_player_identity_and_exact_chat(monkeypatch):
    thread = str(uuid.uuid4())
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", thread)
    calls = []
    async def request(method, params):
        calls.append((method, params))
        return {"success": True, "contentItems": [{"type": "inputText", "text": json.dumps({"threadId": thread})}]}
    monkeypatch.setattr(host, "_request", request)
    message = row("say")
    await host.send(message)
    params = calls[0][1]
    assert params["tool"] == "send_message_to_thread" and params["threadId"] == thread
    prompt = params["arguments"]["prompt"]
    assert prompt == f"[MCB 玩家消息 · Alice · 未验证权限 · 普通消息 · 回复 #ab12cd34ef]\n{message['text']}"
    assert message["id"] not in prompt and message["playerUuid"] not in prompt and thread not in prompt
    assert "model" not in params["arguments"]


@pytest.mark.asyncio
async def test_host_cannot_fall_back_from_native_steer_to_followup(monkeypatch):
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4()))
    async def request(*args):
        pytest.fail("Unsupported steering must not make any host request")
    monkeypatch.setattr(host, "_request", request)
    assert host.capabilities()["steer"] is False
    with pytest.raises(BridgeError) as error:
        await host.send(row("steer"))
    assert error.value.code == "DIALOGUE_STEER_UNAVAILABLE"


@pytest.mark.asyncio
async def test_status_reports_running_helpers_actual_capabilities(tmp_path):
    client = Client([])
    client.configuration = Configuration({"LOCALAPPDATA": str(tmp_path), "MCB_RUNTIME_FILE": str(tmp_path / "runtime.json")})
    path = state_path(client.configuration)
    caps = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4())).capabilities()
    state = {"status": "running", "heartbeat": time.time(), "hostCapabilities": caps}
    write_state(path, state)
    with instance_lock(path):
        result = await manage(client, {"action": "status"})
    assert result["running"] is True
    assert result["hostCapabilities"] == {"say": True, "steer": False, "steerReason": "DIALOGUE_STEER_UNAVAILABLE"}


@pytest.mark.asyncio
async def test_status_does_not_infer_capabilities_for_old_helper(tmp_path):
    client = Client([])
    client.configuration = Configuration({"LOCALAPPDATA": str(tmp_path), "MCB_RUNTIME_FILE": str(tmp_path / "runtime.json")})
    path = state_path(client.configuration)
    write_state(path, {"status": "running", "heartbeat": time.time()})
    with instance_lock(path):
        result = await manage(client, {"action": "status"})
    assert "hostCapabilities" not in result


@pytest.mark.asyncio
async def test_host_refuses_ack_for_another_chat(monkeypatch):
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", str(uuid.uuid4()))
    async def call(*args):
        return {"threadId": str(uuid.uuid4())}
    monkeypatch.setattr(host, "call", call)
    with pytest.raises(BridgeError, match="bound chat"):
        await host.send(row("say"))


@pytest.mark.asyncio
async def test_host_status_ignores_other_chats_and_remote_hosts(monkeypatch):
    thread = str(uuid.uuid4())
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", thread)
    async def call(*args):
        return {"threads": [{"id": thread, "status": "active", "kind": "codex", "hostId": "remote"}]}
    monkeypatch.setattr(host, "call", call)
    with pytest.raises(BridgeError):
        await host.status()


class PublicReplyClient:
    def __init__(self, tmp_path, fail=False):
        self.configuration = Configuration({"LOCALAPPDATA": str(tmp_path), "MCB_RUNTIME_FILE": str(tmp_path / "runtime.json")})
        self.calls, self.claimed, self.fail = [], False, fail

    async def invoke(self, operation, args):
        self.calls.append((operation, args))
        if operation == "chat.send":
            if self.fail:
                raise BridgeError("timeout", "packet outcome unknown")
            return {"sent": True, "source": "native_player_chat_packet"}
        assert operation == "agent.dialogue"
        if args["action"] == "prepare_chat_reply":
            claimed = not self.claimed
            self.claimed = True
            return {"claimed": claimed, "connectionId": "bound-connection"}
        return {}


def public_reply_fixture(tmp_path, monkeypatch, fail=False):
    client = PublicReplyClient(tmp_path, fail)
    thread = str(uuid.uuid4())
    monkeypatch.setenv("CODEX_THREAD_ID", thread)
    write_state(state_path(client.configuration), {"threadId": thread, "leaseId": "lease", "sessionId": "session",
                "status": "running", "replyChannel": "server"})
    return client, {"action": "reply", "messageId": str(uuid.uuid4()), "text": "你好, Alice"}


@pytest.mark.asyncio
async def test_public_reply_claims_then_sends_once_with_exact_binding(tmp_path, monkeypatch):
    client, args = public_reply_fixture(tmp_path, monkeypatch)
    result = await manage(client, args)
    assert result["submitted"] is True and result["executionConfirmed"] is False
    assert [op for op, _ in client.calls] == ["agent.dialogue", "chat.send", "agent.dialogue"]
    assert client.calls[1][1] == {"message": args["text"], "expectedSessionId": "session", "expectedConnectionId": "bound-connection"}
    assert client.calls[-1][1]["state"] == "replied"
    assert (await manage(client, args))["duplicateSuppressed"] is True
    assert len([op for op, _ in client.calls if op == "chat.send"]) == 1


@pytest.mark.asyncio
async def test_public_reply_timeout_never_replays(tmp_path, monkeypatch):
    client, args = public_reply_fixture(tmp_path, monkeypatch, fail=True)
    with pytest.raises(BridgeError) as failure:
        await manage(client, args)
    assert failure.value.code == "DIALOGUE_CHAT_UNCERTAIN"
    assert (await manage(client, args))["duplicateSuppressed"] is True
    assert len([op for op, _ in client.calls if op == "chat.send"]) == 1
    assert not any(a.get("state") == "replied" for _, a in client.calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("text", ["/stop", "  /op Bob", "", "a\nb", "a\x00b", "§cred", "a" * 101, "😀" * 51])
async def test_public_reply_rejects_commands_controls_and_native_overflow(tmp_path, monkeypatch, text):
    client, args = public_reply_fixture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError):
        await manage(client, {**args, "text": text})
    assert client.calls == []


@pytest.mark.asyncio
async def test_private_override_keeps_long_reply_private(tmp_path, monkeypatch):
    client, args = public_reply_fixture(tmp_path, monkeypatch)
    await manage(client, {**args, "text": "a" * 1000, "channel": "private"})
    assert len(client.calls) == 1 and client.calls[0][1]["state"] == "replied"


@pytest.mark.asyncio
async def test_public_prompt_identifies_sender_and_public_reply_boundaries(monkeypatch):
    thread = str(uuid.uuid4())
    host = AppToolsHost(r"\\.\pipe\codex-app-tools-fixture", thread, "server")
    calls = []
    async def call(name, args):
        calls.append((name, args))
        return {"threadId": thread}
    monkeypatch.setattr(host, "call", call)
    message = row("say")
    await host.send(message)
    prompt = calls[0][1]["prompt"]
    assert prompt.split("\n", 1)[1] == message["text"]
    assert "Alice" in prompt and "#ab12cd34ef" in prompt
    assert message["id"] not in prompt and message["playerUuid"] not in prompt
    assert "delegated input" in TOOL_BY_NAME["mcb_dialogue"].description


def test_dialogue_public_channel_schema():
    validate_tool_arguments("mcb_dialogue", {"action": "start", "replyChannel": "server"})
    validate_tool_arguments("mcb_dialogue", {"action": "reply", "messageId": str(uuid.uuid4()), "channel": "server", "text": "hello"})
    with pytest.raises(BridgeError):
        validate_tool_arguments("mcb_dialogue", {"action": "start", "replyChannel": "everyone-auto-input"})


def test_dialogue_short_reply_schema_requires_exact_one_identifier():
    validate_tool_arguments("mcb_dialogue", {"action": "reply", "replyTo": "ab12cd34ef", "text": "hello"})
    for extra in ({"replyTo": "bad"}, {"replyTo": "ab12cd34ef", "messageId": str(uuid.uuid4())}):
        with pytest.raises(BridgeError):
            validate_tool_arguments("mcb_dialogue", {"action": "reply", "text": "hello", **extra})


@pytest.mark.asyncio
async def test_short_reply_uses_persisted_exact_binding_and_hides_ids(tmp_path, monkeypatch):
    client, args = public_reply_fixture(tmp_path, monkeypatch)
    path = state_path(client.configuration)
    state = read_state(path)
    incoming = {**row("say"), "id": args["messageId"]}
    ref = DialogueLog(path, state).register(incoming, "server")
    result = await manage(client, {"action": "reply", "replyTo": ref, "text": "hello"})
    assert result["submitted"]
    assert client.calls[0][1]["messageId"] == incoming["id"]
    assert incoming["id"] not in json.dumps(result) and state["threadId"] not in json.dumps(result)
    client.calls.clear()
    write_state(path, {**state, "leaseId": "another-lease"})
    with pytest.raises(BridgeError) as failure:
        await manage(client, {"action": "reply", "replyTo": ref, "text": "hello"})
    assert failure.value.code == "DIALOGUE_REPLY_NOT_FOUND" and client.calls == []


@pytest.mark.asyncio
async def test_status_omits_full_binding_and_turn_ids(tmp_path, monkeypatch):
    client, _ = public_reply_fixture(tmp_path, monkeypatch)
    path = state_path(client.configuration)
    state = {**read_state(path), "pid": 123, "lastDelivery": {"replyTo": "ab12cd34ef", "mode": "steer", "turnId": str(uuid.uuid4()), "messageId": str(uuid.uuid4())}}
    write_state(path, state)
    result = await manage(client, {"action": "status"})
    assert result["lastDelivery"] == {"replyTo": "ab12cd34ef", "mode": "steer"}
    assert not {"threadId", "sessionId", "leaseId", "pid"} & result.keys()
    for value in (state["threadId"], state["lastDelivery"]["turnId"], state["lastDelivery"]["messageId"]):
        assert value not in json.dumps(result)


@pytest.mark.asyncio
async def test_relay_registers_before_dispatch_and_logs_native_turn(tmp_path):
    incoming = row("steer")
    client, host = Client([incoming]), Host(native=True)
    journal = DialogueLog(tmp_path / "state.json", {"threadId": "thread", "sessionId": "session", "leaseId": "lease"})
    relay = Relay(client, host, "lease", "session", journal)
    await relay.step()
    ref = host.sent[0]["replyRef"]
    assert journal.resolve(ref) == incoming["id"]
    with journal.connect() as db:
        events = db.execute("SELECT stage,metadata FROM events ORDER BY seq").fetchall()
    assert [stage for stage, _ in events] == ["registered", "dispatching", "host_accepted", "delivered"]
    assert json.loads(events[2][1])["turnId"] == host.turn_id
    assert relay.last_delivery["replyTo"] == ref


@pytest.mark.asyncio
async def test_no_host_input_if_routing_log_cannot_be_saved(tmp_path, monkeypatch):
    client, host = Client([row("steer")]), Host(native=True)
    journal = DialogueLog(tmp_path / "state.json", {"threadId": "thread", "sessionId": "session", "leaseId": "lease"})
    def unavailable(*args): raise BridgeError("DIALOGUE_LOG_UNAVAILABLE", "fixture")
    monkeypatch.setattr(journal, "register", unavailable)
    await Relay(client, host, "lease", "session", journal).step()
    assert host.sent == [] and client.calls[-1]["state"] == "failed"
