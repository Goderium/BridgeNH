"""Native steering through the running Windows desktop's versioned local IPC.

Only discovers the bound chat's owner, probes it, and steers it. Never starts a
router/server/turn, registers ownership, subscribes to history, or falls back to
queued input. The desktop owner supplies expectedTurnId to turn/steer.
"""
from __future__ import annotations

import asyncio
import json
import os
import struct
import time
import uuid
import anyio
from .bridge import BridgeError

MAX_FRAME = 8 * 1024 * 1024
PIPE = r"\\.\pipe\codex-ipc"
VERSIONS = {"initialize": 0, "thread-owner-discovery": 1,
            "thread-follower-read-model-settings": 2, "thread-follower-steer-turn": 2}


def _uuid(value):
    if not isinstance(value, str):
        raise ValueError("Expected UUID text")
    return str(uuid.UUID(value))


class _Peer:
    def __init__(self, reader, writer, thread_id):
        self.reader, self.writer, self.thread_id = reader, writer, thread_id
        self.client_id = "initializing-client"
        self.submitted = False

    async def write(self, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf8")
        if len(raw) > MAX_FRAME:
            raise ValueError("Oversized IPC request")
        if payload.get("method") == "thread-follower-steer-turn":
            self.submitted = True
        self.writer.write(struct.pack("<I", len(raw)) + raw)
        await self.writer.drain()

    async def request(self, method, params, target=None):
        request_id = str(uuid.uuid4())
        payload = {"type": "request", "requestId": request_id, "sourceClientId": self.client_id,
                   "version": VERSIONS[method], "method": method, "params": params, "timeoutMs": 4000}
        if method.startswith("thread-follower-"):
            payload["hostId"] = "local"
        if target:
            payload["targetClientId"] = target
        await self.write(payload)
        for _ in range(128):
            size = struct.unpack("<I", await self.reader.readexactly(4))[0]
            if not 0 < size <= MAX_FRAME:
                raise ValueError("Invalid IPC frame")
            row = json.loads(await self.reader.readexactly(size))
            if not isinstance(row, dict):
                raise ValueError("Expected IPC object")
            if row.get("type") == "client-discovery-request":
                await self.write({"type": "client-discovery-response", "requestId": row["requestId"],
                                  "response": {"canHandle": False}})
            elif row.get("type") == "request":
                await self.write({"type": "response", "requestId": row["requestId"],
                                  "resultType": "error", "error": "no-handler-for-request"})
            if row.get("type") != "response" or row.get("requestId") != request_id:
                continue
            if row.get("resultType") != "success":
                error = row.get("error")
                rejected = error in {"no-client-found", "request-version-mismatch", "no-handler-for-request",
                                     "no active turn to steer",
                                     f"Cannot steer conversation {self.thread_id} because its active turn already ended",
                                     f"Conversation {self.thread_id} is not being streamed."}
                code = "DIALOGUE_HOST_REJECTED" if self.submitted and rejected else (
                    "DIALOGUE_HOST_UNCERTAIN" if self.submitted else "DIALOGUE_STEER_UNAVAILABLE")
                raise BridgeError(code, "Native steering was not confirmed; no queued fallback or automatic resend.")
            if row.get("method") != method or target and row.get("handledByClientId") != target:
                raise ValueError("IPC acknowledgement identity mismatch")
            if not isinstance(row.get("result"), dict):
                raise ValueError("Expected IPC result object")
            return row
        raise ValueError("Too many unrelated IPC frames")


class DesktopSteering:
    def __init__(self, thread_id):
        self.thread_id = _uuid(thread_id)

    async def _open(self):
        if os.name != "nt":
            raise BridgeError("DIALOGUE_STEER_UNAVAILABLE", "Native desktop steering is currently verified on Windows only.")
        reader = asyncio.StreamReader(limit=MAX_FRAME)
        protocol = asyncio.StreamReaderProtocol(reader)
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_pipe_connection(lambda: protocol, PIPE)
        return reader, asyncio.StreamWriter(transport, protocol, reader, loop)

    async def probe(self):
        await self._run()

    async def steer(self, message_id, prompt):
        message_id = _uuid(message_id)
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 16384:
            raise BridgeError("INVALID_ARGUMENT", "Invalid bounded steering input.")
        return await self._run(message_id, prompt)

    async def _run(self, message_id=None, prompt=None):
        peer, writer = None, None
        try:
            with anyio.fail_after(12):
                reader, writer = await self._open()
                peer = _Peer(reader, writer, self.thread_id)
                initialized = await peer.request("initialize", {"clientType": "mcb"})
                peer.client_id = _uuid(initialized["result"]["clientId"])
                owner = await peer.request("thread-owner-discovery", {"hostId": "local", "conversationId": self.thread_id})
                owner_id = _uuid(owner["handledByClientId"])
                if owner["result"].get("supportsUntrustedAppInput") is not True:
                    raise BridgeError("DIALOGUE_STEER_UNAVAILABLE", "Desktop owner does not support attributed app input.")
                if prompt is None:
                    result = await peer.request("thread-follower-read-model-settings", {"conversationId": self.thread_id}, owner_id)
                    if not isinstance(result["result"].get("settings"), dict):
                        raise ValueError("Chat owner is not ready")
                    return
                params = {
                    "conversationId": self.thread_id,
                    "input": [{"type": "text", "text": prompt, "text_elements": []}],
                    "clientUserMessageId": message_id, "attachments": [],
                    "restoreMessage": {"id": message_id, "text": prompt, "createdAt": int(time.time() * 1000),
                                       "context": {"prompt": prompt, "addedFiles": [], "fileAttachments": [],
                                                   "ideContext": None, "imageAttachments": []}},
                }
                # No toolOutput: the desktop deliberately routes that to turn/start.
                # No settings, permissions, fallback start, or model overrides.
                accepted = await peer.request("thread-follower-steer-turn", params, owner_id)
                turn_id = _uuid(accepted["result"]["result"]["turnId"])
                return {"mode": "steer", "turnId": turn_id, "transport": "desktop-ipc"}
        except (OSError, TimeoutError, ValueError, KeyError, TypeError, UnicodeError, asyncio.IncompleteReadError):
            raise BridgeError("DIALOGUE_HOST_UNCERTAIN" if peer and peer.submitted else "DIALOGUE_STEER_UNAVAILABLE",
                              "Native desktop IPC failed; no queued fallback or automatic resend.") from None
        finally:
            if writer is not None:
                writer.close()
