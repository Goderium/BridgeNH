"""Player-to-chat relay owned by MCB. One helper per instance, no extra MCP registration.

The helper survives a compatible backend reload and sleeps between bounded polls.
It never starts another Codex process and never retries a dispatched player input.
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
from .bridge import BridgeClient, BridgeError, Configuration
from .dialogue_host import AppToolsHost, HOST_RECOVERY, STEER_UNAVAILABLE
from .dialogue_log import DialogueLog

RECONNECT_SECONDS = 60
CONNECTION_ERRORS = {"world_unavailable", "stale_world", "qa_context_changed", "unsupported",
                     "UNSUPPORTED_CAPABILITY", "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE", "qa_outcome_unknown",
                     "timeout", "permission_denied"}


@dataclass(frozen=True)
class DialogueConnection:
    session: str
    connection: str
    player: str
    server_run: str | None

    @classmethod
    def observe(cls, health):
        bridge = health.get("serverBridge", {})
        server = bridge.get("server", {})
        connection = bridge.get("connectionId")
        if bridge.get("ready") is not True:
            raise BridgeError("world_unavailable", "Waiting for the selected client's server handshake.")
        if (not connection or connection != server.get("connectionId") or not server.get("playerUuid")
                or not health.get("sessionId")):
            raise BridgeError("DIALOGUE_IDENTITY_UNCONFIRMED", "Cannot verify the game session, server connection and account.")
        return cls(health["sessionId"], connection, server["playerUuid"], server.get("serverRunId"))

    def check(self, current):
        if self.session != current.session or self.player != current.player:
            raise BridgeError("DIALOGUE_TARGET_CHANGED", "The Minecraft client session or server-verified player account changed.")
        if self.server_run != current.server_run or (self.connection != current.connection and not self.server_run):
            raise BridgeError("DIALOGUE_TARGET_CHANGED", "The original server run could not be verified for this connection.")

def state_path(configuration):
    if configuration.conversation_binding is None and (
            not configuration.environ.get("MCB_INSTANCE_PATH") and
            configuration.environ.get("MCB_RUNTIME_FILE", "").strip().casefold() in {"", "auto", "discover"}):
        raise BridgeError("DIALOGUE_BINDING_REQUIRED", "Set an explicit instance runtime for dialogue in this chat.")
    runtime = configuration.runtime_path()
    if runtime is None:
        raise BridgeError("DIALOGUE_BINDING_REQUIRED", "Dialogue requires a pinned instance runtime file.")
    root = Path(configuration.environ.get("LOCALAPPDATA", str(Path.home() / ".local/share"))) / "MinecraftCodexBridge" / "dialogue"
    root.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(str(runtime.resolve()).casefold().encode()).hexdigest()[:24]
    return root / (key + ".json")


def read_state(path):
    try:
        with path.open("rb") as stream:
            raw = stream.read(8193)
        if len(raw) > 8192:
            raise ValueError()
        state = json.loads(raw)
        return state if isinstance(state, dict) else {}
    except (OSError, ValueError):
        return {}


def write_state(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False), encoding="utf8")
    temporary.replace(path)


@contextmanager
def instance_lock(path):
    with path.with_suffix(".lock").open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0"); lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise BridgeError("DIALOGUE_ALREADY_RUNNING", "An instance dialogue relay is already running.") from None
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


class Relay:
    def __init__(self, client, host, lease, session, journal=None, connection=None, stop_file=None):
        self.client, self.host, self.lease, self.session = client, host, lease, session
        self.journal = journal
        self.last_delivery = None
        self.connection = connection
        self.stop_file = stop_file

    def stopping(self):
        return self.stop_file is not None and self.stop_file.exists()

    async def native(self, action, **args):
        if self.connection:
            args["expectedConnectionId"] = self.connection
        return await self.client.invoke("agent.dialogue", {"action": action, "leaseId": self.lease,
                                                          "expectedSessionId": self.session, **args})

    async def receipt(self, message, state, text):
        result = await self.native("receipt", messageId=message["id"], state=state, text=text)
        if self.journal is not None:
            self.journal.record(message["id"], state, connectionId=result.get("connectionId"))
        return result

    async def step(self):
        inbox = await self.native("poll")
        rows = inbox.get("messages", [])
        if not rows:
            return
        if not self.host.capabilities().get("steer", False):
            for row in rows:
                if row["mode"] == "steer":
                    await self.receipt(row, "failed", STEER_UNAVAILABLE)
            rows = [row for row in rows if row["mode"] != "steer"]
        if not rows:
            return
        # One compact host query for the remaining supported input batch.
        observed_state = state = await self.host.status()
        for row in rows:
            if self.stopping():
                return
            if row["mode"] == "say" and state == "active":
                continue
            if row["mode"] == "steer" and observed_state != "active":
                await self.receipt(row, "failed", "当前没有运行中的任务；请用 /mcb say 发送普通消息。")
                continue
            if self.journal is not None:
                try:
                    ref = self.journal.register(row, self.host.reply_channel)
                except BridgeError:
                    await self.receipt(row, "failed", "无法保存消息关联记录, 消息未提交到 Agent。")
                    break
                row = {**row, "replyRef": ref}
            await self.receipt(row, "dispatching", "正在提交到已绑定的聊天。")
            if self.stopping():
                return
            try:
                delivery = await self.host.send(row)
            except BridgeError as error:
                # A timeout/disconnect may follow actual submission. Never change it back to queued.
                rejected = (error.code in {"DIALOGUE_HOST_REJECTED", "DIALOGUE_STEER_UNAVAILABLE"}
                            or error.code == "DIALOGUE_HOST_UNAVAILABLE" and error.details.get("sent") is False)
                if self.journal is not None:
                    self.journal.record(row["id"], "host_rejected" if rejected else "host_uncertain", errorCode=error.code)
                await self.receipt(row, "failed" if rejected else "unknown",
                                   "宿主未接受消息; 不转为普通排队, 不自动重发." if rejected else "送达结果不确定; 不会自动重发.")
                if error.details.get("hostReason") == "app_tools_request_failed":
                    raise
                break
            else:
                self.last_delivery = {"messageId": row["id"], **delivery}
                if self.journal is not None:
                    self.last_delivery["replyTo"] = row["replyRef"]
                    self.journal.record(row["id"], "host_accepted", **delivery)
                text = (f"原生引导已被活动回合接受 (turnId={delivery['turnId']}); 等待处理回执."
                        if row["mode"] == "steer" else "已送入 Agent 对话; 等待处理回执.")
                await self.receipt(row, "delivered", text)
                state = "active"

    async def recover(self, original, thread):
        health = await self.client.invoke("bridge.health", {})
        if health.get("sessionId") != self.session:
            raise BridgeError("DIALOGUE_TARGET_CHANGED", "The Minecraft client session changed.")
        current = DialogueConnection.observe(health)
        original.check(current)
        if health["serverBridge"]["server"].get("operatorReads") is not True:
            raise BridgeError("world_unavailable", "Waiting for the original server's live operator handshake.")
        if self.stopping():
            return
        await self.host.verify()
        if self.stopping():
            return
        # Only status can renew an existing lease. Bind could acquire an empty mailbox.
        previous = self.connection
        self.connection = current.connection
        try:
            status = await self.native("status")
            if status.get("enabled") is not True or status.get("leaseId") != self.lease or status.get("threadId") != thread:
                raise BridgeError("DIALOGUE_LEASE_LOST", "The server no longer confirms this chat's original lease.")
        except BridgeError as error:
            self.connection = previous
            if error.code == "stale_session":
                raise BridgeError("DIALOGUE_LEASE_LOST", "The original server lease expired or changed.") from None
            raise

    async def run(self, path, state, original):
        deadline = None
        while not self.stopping():
            state["heartbeat"] = time.time()
            write_state(path, state)
            if deadline is not None:
                if time.monotonic() >= deadline:
                    raise BridgeError("DIALOGUE_RECONNECT_TIMEOUT", "The original server lease could not be confirmed within 60 seconds.")
                try:
                    await self.recover(original, state["threadId"])
                except BridgeError as error:
                    if error.code not in CONNECTION_ERRORS - {"permission_denied"} and error.code != "busy":
                        raise
                else:
                    state.update(status="running", connectionId=self.connection, hostCapabilities=self.host.capabilities())
                    state.pop("lastError", None)
                    deadline = None
            else:
                try:
                    await self.step()
                except BridgeError as error:
                    if error.code in CONNECTION_ERRORS:
                        deadline = time.monotonic() + RECONNECT_SECONDS
                        state.update(status="reconnecting", lastError=error.code)
                        write_state(path, state)
                    elif error.code != "busy":
                        raise
                if self.last_delivery is not None:
                    state["lastDelivery"] = self.last_delivery
            await asyncio.sleep(1)


def relay_running(path, state):
    if state.get("status") not in {"running", "reconnecting"}:
        return False
    try:
        with instance_lock(path):
            return False
    except BridgeError as error:
        if error.code == "DIALOGUE_ALREADY_RUNNING":
            return True
        raise


def relay_command():
    # A supervised worker runs the base interpreter with explicit venv sites.
    # Preserve those already-loaded search paths instead of losing dependencies.
    bootstrap = "import json,runpy,sys;sys.path[:]=json.loads(sys.argv[1]);sys.argv=['mcb-dialogue','serve'];runpy.run_module('minecraft_codex_bridge.dialogue',run_name='__main__')"
    executable = getattr(sys, "_base_executable", sys.executable) if os.name == "nt" else sys.executable
    return [executable, "-I", "-B", "-c", bootstrap, json.dumps(sys.path)]


def stop_path(path, lease):
    return path.with_suffix("." + hashlib.sha256(lease.encode()).hexdigest()[:24] + ".stop")


def recovery_hint(code):
    if code in {"DIALOGUE_HOST_UNAVAILABLE", "DIALOGUE_HOST_REJECTED", "DIALOGUE_HOST_UNCERTAIN", "DIALOGUE_THREAD_UNAVAILABLE"}:
        return HOST_RECOVERY
    if code in {"DIALOGUE_HELPER_EXITED", "DIALOGUE_RECONNECT_TIMEOUT", "DIALOGUE_TARGET_CHANGED",
                "DIALOGUE_IDENTITY_UNCONFIRMED", "DIALOGUE_LEASE_LOST", "stale_session"}:
        return ("Check mcb_connection and mcb_health in the original chat. Confirm the intended account and server, "
                "then call mcb_dialogue start explicitly. A previous lease may need up to 60 seconds to expire.")
    return None


async def manage(client, arguments):
    path = state_path(client.configuration)
    action = arguments.get("action", "status")
    state = read_state(path)
    binding = getattr(client.configuration, "conversation_binding", None)
    thread = binding["conversationId"] if binding is not None else os.environ.get("CODEX_THREAD_ID", "")
    binding_id = binding.get("bindingId") if binding is not None else None
    if action == "status":
        running = relay_running(path, state)
        status = state.get("status", "stopped")
        if status in {"running", "reconnecting"} and not running:
            status = "stopped"
            state["lastError"] = "DIALOGUE_HELPER_EXITED"
        elif running and time.time() - state.get("heartbeat", 0) >= 60:
            status = "unresponsive"
        elif status == "running":
            status = "ready"
        if running and state.get("leaseId") and stop_path(path, state["leaseId"]).exists():
            status = "stopping"
        result = {**{k: v for k, v in state.items() if k in {"lastError", "hostCapabilities", "replyChannel", "hostReason"}},
                  "running": running, "status": status}
        hint = recovery_hint(state.get("lastError"))
        if hint:
            result["nextStep"] = hint
        if running and status == "reconnecting":
            result["nextStep"] = "Return to the original server and account within the current lease, or call mcb_dialogue stop to cancel."
        if status == "unresponsive":
            result["nextStep"] = "The helper still holds the instance lock. Call mcb_dialogue stop in the owning chat and check status."
        if not os.environ.get("CODEX_APP_TOOLS_PIPE_PATH"):
            result["callerHost"] = {"appToolsPipeConfigured": False, "nextStep": recovery_hint("DIALOGUE_HOST_UNAVAILABLE")}
        if isinstance(state.get("lastDelivery"), dict):
            result["lastDelivery"] = {k: v for k, v in state["lastDelivery"].items() if k in {"replyTo", "mode"}}
        return result
    if action == "start":
        channel = arguments.get("replyChannel", "private")
        if channel not in {"private", "server"}:
            raise BridgeError("INVALID_ARGUMENT", "replyChannel must be private or server.")
        if relay_running(path, state):
            if state.get("threadId") != thread or "bindingId" in state and state["bindingId"] != binding_id:
                raise BridgeError("DIALOGUE_ALREADY_RUNNING", "This client already has a helper for another chat or frontend binding.")
            if "replyChannel" in arguments and state.get("replyChannel", "private") != channel:
                raise BridgeError("DIALOGUE_ALREADY_RUNNING", "Stop the current relay before changing its default reply channel.")
            return await manage(client, {"action": "status"})
        host = AppToolsHost(os.environ.get("CODEX_APP_TOOLS_PIPE_PATH", ""), thread)
        await host.verify()
        health = await client.invoke("bridge.health", {})
        if "agent.dialogue" not in health.get("capabilities", []):
            raise BridgeError("DIALOGUE_MOD_REQUIRED", "Install the updated MCB client/server jar before enabling dialogue.")
        target = DialogueConnection.observe(health)
        env = dict(os.environ)
        env.update(dict(client.configuration.environ))
        env["MCB_RUNTIME_FILE"] = str(client.configuration.runtime_path().resolve())
        env["CODEX_THREAD_ID"] = thread
        env["MCB_DIALOGUE_REPLY_CHANNEL"] = channel
        env["MCB_DIALOGUE_START_SESSION"] = target.session
        env["MCB_DIALOGUE_START_CONNECTION"] = target.connection
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1])
        process = subprocess.Popen(relay_command(),
            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0, start_new_session=os.name != "nt")
        for _ in range(60):
            await asyncio.sleep(.25)
            state = read_state(path)
            if state.get("pid") == process.pid:
                if state.get("status") in {"running", "reconnecting"}:
                    return await manage(client, {"action": "status"})
                if state.get("status") == "failed":
                    code = state.get("lastError", "DIALOGUE_START_FAILED")
                    raise BridgeError(code, recovery_hint(code) or "Dialogue relay failed to start. Inspect mcb_dialogue status.",
                                      **({"hostReason": state["hostReason"]} if state.get("hostReason") else {}))
            if process.poll() is not None:
                raise BridgeError("DIALOGUE_START_FAILED", "Relay process exited before readiness.", exitCode=process.returncode)
        raise BridgeError("DIALOGUE_START_UNCONFIRMED", "Relay startup is not confirmed. Check status before starting again.")
    if state.get("threadId") != thread or not state.get("leaseId") or state.get("status") not in {"running", "reconnecting"}:
        raise BridgeError("DIALOGUE_NOT_OWNED", "This chat does not own an active relay for the selected instance.")
    if action == "stop" and "bindingId" in state:
        if not relay_running(path, state):
            return {"stopping": False, "status": "stopped"}
        # Only the matching helper sees this marker, including while Minecraft is offline.
        stop_path(path, state["leaseId"]).touch()
        return {"stopping": True}
    if "bindingId" in state and (binding_id != state["bindingId"] or not relay_running(path, state)):
        raise BridgeError("DIALOGUE_NOT_OWNED", "This frontend binding does not own a live helper. Check mcb_dialogue status.")
    if state.get("status") == "reconnecting" or stop_path(path, state["leaseId"]).exists():
        raise BridgeError("DIALOGUE_NOT_READY", "The relay is reconnecting or stopping. Check mcb_dialogue status.")
    journal = DialogueLog(path, state)
    relay = Relay(client, None, state["leaseId"], state["sessionId"], journal, state.get("connectionId"))
    if action == "stop":
        # Helpers started before local cancellation support observe server-side revocation.
        await relay.native("close")
        return {"stopping": True}
    if action == "reply":
        if bool(arguments.get("replyTo")) == bool(arguments.get("messageId")):
            raise BridgeError("INVALID_ARGUMENT", "Supply exactly one replyTo or legacy messageId.")
        message_id = journal.resolve(arguments["replyTo"]) if arguments.get("replyTo") else str(uuid.UUID(arguments["messageId"]))
        text = arguments["text"]
        channel = arguments.get("channel", state.get("replyChannel", "private"))
        if channel == "private":
            await relay.receipt({"id": message_id}, "replied", text)
            return {"channel": "private", "replied": True}
        if channel != "server":
            raise BridgeError("INVALID_ARGUMENT", "channel must be private or server.")
        # Claim in the authoritative mailbox before the native packet. A timeout,
        # concurrent call or backend reload cannot cause a second public send.
        if not isinstance(text, str) or not text.strip() or len(text.encode("utf-16-le")) // 2 > 100 or text.strip().startswith("/") or any(ord(c) < 32 or 127 <= ord(c) <= 159 or c == "§" for c in text):
            raise BridgeError("INVALID_ARGUMENT", "Server replies require 1..100 UTF-16 characters of plain single-line chat, not a command.")
        text = text.strip()
        claim = await relay.native("prepare_chat_reply", messageId=message_id, text=text)
        if claim.get("claimed") is not True:
            journal.record(message_id, "duplicate_suppressed", channel=channel)
            return {"channel": "server", "duplicateSuppressed": True, "submission": "previous_claim_not_replayed", "executionConfirmed": False}
        journal.record(message_id, "public_reply_claimed", channel=channel)
        try:
            sent = await client.invoke("chat.send", {"message": text, "expectedSessionId": state["sessionId"],
                                                    "expectedConnectionId": claim["connectionId"]})
            if sent.get("sent") is not True or sent.get("source") != "native_player_chat_packet":
                raise BridgeError("DIALOGUE_CHAT_UNCERTAIN", "Native chat submission was not confirmed; do not resend.")
            await relay.receipt({"id": message_id}, "replied", text)
        except (BridgeError, KeyError):
            journal.record(message_id, "public_reply_uncertain", channel=channel)
            # Leave the durable-in-session claim in place. Native dispatch may
            # already have happened; neither failures nor retries resend it.
            raise BridgeError("DIALOGUE_CHAT_UNCERTAIN", "Public reply outcome is uncertain; inspect native chat, never resend automatically.") from None
        return {"channel": "server", "submitted": True, "source": "native_player_chat_packet", "executionConfirmed": False}
    raise BridgeError("INVALID_ARGUMENT", "Unknown dialogue action.")


async def serve():
    client = BridgeClient()
    path = state_path(client.configuration)
    binding = client.configuration.conversation_binding or {}
    state = {"pid": os.getpid(), "threadId": os.environ.get("CODEX_THREAD_ID", ""), "status": "starting", "heartbeat": time.time(),
             "bindingId": binding.get("bindingId"),
             "replyChannel": os.environ.get("MCB_DIALOGUE_REPLY_CHANNEL", "private"),
             "leaseId": str(uuid.uuid4())}
    relay = None
    stop_file = stop_path(path, state["leaseId"])
    try:
        with instance_lock(path):
            write_state(path, state)
            try:
                host = AppToolsHost(os.environ.get("CODEX_APP_TOOLS_PIPE_PATH", ""), state["threadId"], state["replyChannel"])
                await host.verify()
                state["hostCapabilities"] = host.capabilities()
                health = await client.invoke("bridge.health", {})
                target = DialogueConnection.observe(health)
                if (target.session != os.environ.get("MCB_DIALOGUE_START_SESSION")
                        or target.connection != os.environ.get("MCB_DIALOGUE_START_CONNECTION")):
                    raise BridgeError("DIALOGUE_TARGET_CHANGED", "The selected connection changed while starting the helper.")
                state.update(sessionId=target.session, connectionId=target.connection)
                relay = Relay(client, host, state["leaseId"], target.session, DialogueLog(path, state), target.connection, stop_file)
                await relay.native("bind", threadId=state["threadId"])
                state["status"] = "running"
                await relay.run(path, state, target)
                state["status"] = "stopped"
                state.pop("lastError", None)
            except (BridgeError, OSError, ValueError, KeyError) as error:
                state["status"] = "stopped" if isinstance(error, BridgeError) and error.code == "stale_session" else "failed"
                state["lastError"] = error.code if isinstance(error, BridgeError) else "DIALOGUE_RELAY_ERROR"
                if isinstance(error, BridgeError) and error.details.get("hostReason"):
                    state["hostReason"] = error.details["hostReason"]
            finally:
                if relay is not None:
                    try:
                        await relay.native("close")
                    except BridgeError:
                        pass
                write_state(path, state)
                stop_file.unlink(missing_ok=True)
    finally:
        await client.aclose()


async def cli():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status", "reply", "serve"])
    parser.add_argument("--message-id")
    parser.add_argument("--reply-to")
    parser.add_argument("--text")
    parser.add_argument("--channel", choices=["private", "server"])
    args = parser.parse_args()
    if args.action == "serve":
        await serve(); return
    client = BridgeClient()
    try:
        options = {"action": args.action, "messageId": args.message_id, "replyTo": args.reply_to, "text": args.text}
        if args.channel is not None:
            options["replyChannel" if args.action == "start" else "channel"] = args.channel
        print(json.dumps(await manage(client, options), ensure_ascii=False))
    finally:
        await client.aclose()


if __name__ == "__main__":
    asyncio.run(cli())
