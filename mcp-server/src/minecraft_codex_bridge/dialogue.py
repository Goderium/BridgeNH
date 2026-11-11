"""Player-to-chat relay owned by MCB. One helper per instance, no extra MCP registration.

The helper survives a compatible backend reload and sleeps between bounded polls.
It never starts another Codex process and never retries a dispatched player input.
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
from .bridge import BridgeClient, BridgeError, Configuration
from .dialogue_host import AppToolsHost, STEER_UNAVAILABLE
from .dialogue_log import DialogueLog


def state_path(configuration):
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
    def __init__(self, client, host, lease, session, journal=None):
        self.client, self.host, self.lease, self.session = client, host, lease, session
        self.journal = journal
        self.last_delivery = None

    async def native(self, action, **args):
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
            try:
                delivery = await self.host.send(row)
            except BridgeError as error:
                # A timeout/disconnect may follow actual submission. Never change it back to queued.
                rejected = error.code in {"DIALOGUE_HOST_REJECTED", "DIALOGUE_STEER_UNAVAILABLE"}
                if self.journal is not None:
                    self.journal.record(row["id"], "host_rejected" if rejected else "host_uncertain", errorCode=error.code)
                await self.receipt(row, "failed" if rejected else "unknown",
                                   "宿主未接受消息; 不转为普通排队, 不自动重发." if rejected else "送达结果不确定; 不会自动重发.")
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


def relay_running(path, state):
    if state.get("status") != "running" or time.time() - state.get("heartbeat", 0) >= 60:
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


async def manage(client, arguments):
    path = state_path(client.configuration)
    action = arguments.get("action", "status")
    state = read_state(path)
    binding = getattr(client.configuration, "conversation_binding", None)
    thread = binding["conversationId"] if binding is not None else os.environ.get("CODEX_THREAD_ID", "")
    if action == "status":
        running = relay_running(path, state)
        status = state.get("status", "stopped")
        if status == "running":
            status = "ready" if running else "stopped"
        result = {**{k: v for k, v in state.items() if k in {"lastError", "hostCapabilities", "replyChannel"}},
                  "running": running, "status": status}
        if isinstance(state.get("lastDelivery"), dict):
            result["lastDelivery"] = {k: v for k, v in state["lastDelivery"].items() if k in {"replyTo", "mode"}}
        return result
    if action == "start":
        channel = arguments.get("replyChannel", "private")
        if channel not in {"private", "server"}:
            raise BridgeError("INVALID_ARGUMENT", "replyChannel must be private or server.")
        host = AppToolsHost(os.environ.get("CODEX_APP_TOOLS_PIPE_PATH", ""), thread)
        await host.verify()
        health = await client.invoke("bridge.health", {})
        if "agent.dialogue" not in health.get("capabilities", []):
            raise BridgeError("DIALOGUE_MOD_REQUIRED", "Install the updated MCB client/server jar before enabling dialogue.")
        if relay_running(path, state):
            if state.get("threadId") != thread:
                raise BridgeError("DIALOGUE_ALREADY_RUNNING", "This client is already bound to another chat.")
            if "replyChannel" in arguments and state.get("replyChannel", "private") != channel:
                raise BridgeError("DIALOGUE_ALREADY_RUNNING", "Stop the current relay before changing its default reply channel.")
            return await manage(client, {"action": "status"})
        env = dict(os.environ)
        env.update(dict(client.configuration.environ))
        env["MCB_RUNTIME_FILE"] = str(client.configuration.runtime_path().resolve())
        env["CODEX_THREAD_ID"] = thread
        env["MCB_DIALOGUE_REPLY_CHANNEL"] = channel
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1])
        process = subprocess.Popen(relay_command(),
            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0, start_new_session=os.name != "nt")
        for _ in range(60):
            await asyncio.sleep(.25)
            state = read_state(path)
            if state.get("pid") == process.pid:
                if state.get("status") == "running":
                    return await manage(client, {"action": "status"})
                if state.get("status") == "failed":
                    raise BridgeError(state.get("lastError", "DIALOGUE_START_FAILED"), "Dialogue relay failed to start; inspect status.")
            if process.poll() is not None:
                raise BridgeError("DIALOGUE_START_FAILED", "Relay process exited before readiness.", exitCode=process.returncode)
        raise BridgeError("DIALOGUE_START_UNCONFIRMED", "Relay startup is not confirmed. Check status before starting again.")
    if state.get("threadId") != thread or not state.get("leaseId") or state.get("status") != "running":
        raise BridgeError("DIALOGUE_NOT_OWNED", "This chat does not own an active relay for the selected instance.")
    journal = DialogueLog(path, state)
    relay = Relay(client, None, state["leaseId"], state["sessionId"], journal)
    if action == "stop":
        # Server lease revocation stops the helper at its next poll; do not kill a guessed PID.
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
    state = {"pid": os.getpid(), "threadId": os.environ["CODEX_THREAD_ID"], "status": "starting", "heartbeat": time.time(),
             "replyChannel": os.environ.get("MCB_DIALOGUE_REPLY_CHANNEL", "private"),
             "leaseId": str(uuid.uuid4())}
    relay = None
    try:
        with instance_lock(path):
            write_state(path, state)
            try:
                host = AppToolsHost(os.environ.get("CODEX_APP_TOOLS_PIPE_PATH", ""), state["threadId"], state["replyChannel"])
                await host.verify()
                state["hostCapabilities"] = host.capabilities()
                health = await client.invoke("bridge.health", {})
                state["sessionId"] = health["sessionId"]
                relay = Relay(client, host, state["leaseId"], state["sessionId"], DialogueLog(path, state))
                await relay.native("bind", threadId=state["threadId"])
                state["status"] = "running"
                while True:
                    state["heartbeat"] = time.time(); write_state(path, state)
                    try:
                        await relay.step()
                        if relay.last_delivery is not None:
                            state["lastDelivery"] = relay.last_delivery
                    except BridgeError as error:
                        if error.code != "busy":
                            raise
                    await asyncio.sleep(1)
            except (BridgeError, OSError, ValueError, KeyError) as error:
                state["status"] = "stopped" if isinstance(error, BridgeError) and error.code == "stale_session" else "failed"
                state["lastError"] = error.code if isinstance(error, BridgeError) else "DIALOGUE_RELAY_ERROR"
            finally:
                if relay is not None:
                    try:
                        await relay.native("close")
                    except BridgeError:
                        pass
                write_state(path, state)
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
