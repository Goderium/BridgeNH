"""Bound-chat adapters to the running Codex desktop, not a second agent/server.

Uses the same framed tools/list + tools/call protocol as the bundled app-tools
plugin for queued input and reads, and native desktop IPC for active steering.
Host changes fail closed; no UI injection or app installation changes.
"""
from __future__ import annotations

import asyncio
import json
import os
import struct
import uuid
import anyio
from .bridge import BridgeError
from .dialogue_ipc import DesktopSteering
from .dialogue_log import REF

MAX_FRAME = 8 * 1024 * 1024
ALLOWED = {"list_threads", "send_message_to_thread"}
STEER_UNAVAILABLE = "当前 Codex 宿主未提供原生引导入口; 消息未发送, 也未转为普通排队."


class AppToolsHost:
    def capabilities(self):
        return {"say": True, "steer": self.native_ready,
                **({"steerTransport": "desktop-ipc"} if self.native_ready else {"steerReason": "DIALOGUE_STEER_UNAVAILABLE"})}

    def __init__(self, pipe: str, thread_id: str, reply_channel: str = "private"):
        self.pipe = pipe
        if reply_channel not in {"private", "server"}:
            raise BridgeError("INVALID_ARGUMENT", "Unknown reply channel.")
        self.reply_channel = reply_channel
        try:
            self.thread_id = str(uuid.UUID(thread_id))
        except (ValueError, TypeError, AttributeError):
            raise BridgeError("DIALOGUE_HOST_UNAVAILABLE", "Start dialogue from the intended Codex chat; its thread ID is missing.") from None
        if not pipe or os.name == "nt" and not pipe.startswith("\\\\.\\pipe\\codex-app-tools-"):
            raise BridgeError("DIALOGUE_HOST_UNAVAILABLE", "Codex App Tools pipe is unavailable in this process.")
        self.native = DesktopSteering(self.thread_id)
        self.native_ready = False

    async def _request(self, method, params):
        reader = asyncio.StreamReader(limit=MAX_FRAME)
        loop = asyncio.get_running_loop()
        writer = None
        sent = False
        try:
            with anyio.fail_after(20):
                if os.name == "nt":
                    protocol = asyncio.StreamReaderProtocol(reader)
                    transport, _ = await loop.create_pipe_connection(lambda: protocol, self.pipe)
                    writer = asyncio.StreamWriter(transport, protocol, reader, loop)
                else:
                    reader, writer = await asyncio.open_unix_connection(self.pipe, limit=MAX_FRAME)
                raw = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, ensure_ascii=False).encode()
                if len(raw) > MAX_FRAME:
                    raise ValueError("Request too large")
                sent = True
                writer.write(struct.pack("<I", len(raw)) + raw)
                await writer.drain()
                size = struct.unpack("<I", await reader.readexactly(4))[0]
                if not 1 <= size <= MAX_FRAME:
                    raise ValueError("Invalid host frame")
                result = json.loads(await reader.readexactly(size))
                if result.get("id") != 1 or result.get("jsonrpc") != "2.0" or "error" in result:
                    raise ValueError("Host rejected request")
                return result["result"]
        except (OSError, TimeoutError, ValueError, KeyError, asyncio.IncompleteReadError):
            raise BridgeError("DIALOGUE_HOST_UNCERTAIN" if sent and method == "tools/call" else "DIALOGUE_HOST_UNAVAILABLE",
                              "Codex App Tools request failed; no automatic resend.", sent=sent) from None
        finally:
            if writer is not None:
                writer.close()

    async def verify(self):
        catalog = await self._request("tools/list", {"threadStartKind": "all"})
        available = {t["name"] for t in catalog.get("tools", []) if t.get("namespace") == "codex_app"}
        if not ALLOWED <= available:
            raise BridgeError("DIALOGUE_HOST_UNAVAILABLE", "The host does not expose the required App Tools.")
        await self.status()
        self.native_ready = False
        try:
            await self.native.probe()
        except BridgeError:
            # Queued messages remain available when the optional native IPC is absent.
            pass
        else:
            self.native_ready = True

    async def call(self, name, arguments):
        if name not in ALLOWED:
            raise ValueError("Unsupported dialogue host tool")
        result = await self._request("tools/call", {
            "arguments": arguments, "callerSource": "codex", "namespace": "codex_app",
            "threadId": self.thread_id, "tool": name,
            "callId": "mcb-dialogue-" + str(uuid.uuid4()), "turnId": "mcb-dialogue",
        })
        if result.get("success") is not True:
            raise BridgeError("DIALOGUE_HOST_REJECTED", "Codex rejected the message; it was not reported as delivered.")
        try:
            return json.loads(next(c["text"] for c in result["contentItems"] if c["type"] == "inputText"))
        except (KeyError, ValueError, StopIteration):
            raise BridgeError("DIALOGUE_HOST_UNCERTAIN", "Unrecognized host acknowledgement; do not resend.") from None

    async def status(self):
        # Compact list, not full transcripts/reasoning. Only queried while input is queued.
        result = await self.call("list_threads", {"limit": 50})
        for thread in result.get("pinnedThreads", []) + result.get("threads", []):
            if thread.get("id") == self.thread_id and thread.get("kind") == "codex" and thread.get("hostId") == "local":
                state = thread.get("status")
                if state in {"active", "idle", "notLoaded"}:
                    return state
                break
        raise BridgeError("DIALOGUE_THREAD_UNAVAILABLE", "Bound local Codex chat is not available; no other chat was selected.")

    async def send(self, message):
        if message["mode"] == "steer" and not self.native_ready:
            raise BridgeError("DIALOGUE_STEER_UNAVAILABLE", STEER_UNAVAILABLE)
        if message["mode"] not in {"say", "steer"}:
            raise BridgeError("INVALID_ARGUMENT", "Unknown dialogue message mode.")
        ref = message.get("replyRef", "")
        if not isinstance(ref, str) or REF.fullmatch(ref) is None:
            raise BridgeError("DIALOGUE_LOG_UNAVAILABLE", "Player message needs a recorded reply reference before dispatch.")
        name = "".join(c for c in message["playerName"] if c.isalnum() or c == "_")[:32] or "玩家"
        mode = "引导" if message["mode"] == "steer" else "普通消息"
        role = {"guest": "访客", "user": "用户", "admin": "管理员", "agent": "AGENT", "console": "服务器后台"}.get(message.get("permissionGroup"), "未验证权限")
        prompt = f"[MCB 玩家消息 · {name} · {role} · {mode} · 回复 #{ref}]\n{message['text']}"
        if message["mode"] == "steer":
            return await self.native.steer(message["id"], prompt)
        result = await self.call("send_message_to_thread", {"threadId": self.thread_id, "prompt": prompt})
        if result.get("threadId") != self.thread_id:
            raise BridgeError("DIALOGUE_HOST_UNCERTAIN", "Host acknowledgement did not match the bound chat.")
        return {"mode": "say", "transport": "app-tools"}
