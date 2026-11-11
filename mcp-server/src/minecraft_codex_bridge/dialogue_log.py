"""Bounded local dialogue metadata journal and process-independent reply routing.

Only identifiers, attribution and delivery states are stored here. No credentials,
player text, agent replies or private reasoning are copied into this journal.
"""
from __future__ import annotations

from contextlib import contextmanager
import json
import re
import sqlite3
import time
import uuid
from .bridge import BridgeError

MAX_MESSAGES = 256
MAX_EVENTS = 2048
REF = re.compile(r"[0-9a-f]{10}")
BINDING = ("threadId", "sessionId", "leaseId")


class DialogueLog:
    def __init__(self, state_path, binding):
        self.path = state_path.with_suffix(".dialogue-log.sqlite3")
        self.binding = tuple(binding.get(k, "") for k in BINDING)

    @contextmanager
    def connect(self):
        connection = None
        try:
            connection = sqlite3.connect(self.path, timeout=1)
            connection.execute("PRAGMA auto_vacuum=FULL")
            connection.execute("PRAGMA max_page_count=2048")
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("CREATE TABLE IF NOT EXISTS messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT UNIQUE NOT NULL, message_id TEXT NOT NULL, thread_id TEXT NOT NULL, session_id TEXT NOT NULL, lease_id TEXT NOT NULL, metadata TEXT NOT NULL, UNIQUE(message_id, thread_id, session_id, lease_id))")
            connection.execute("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT NOT NULL REFERENCES messages(ref) ON DELETE CASCADE, at REAL NOT NULL, stage TEXT NOT NULL, metadata TEXT NOT NULL)")
            with connection:
                yield connection
        except (OSError, sqlite3.Error):
            raise BridgeError("DIALOGUE_LOG_UNAVAILABLE", "Dialogue routing log is unavailable; no guessed reply target was selected.") from None
        finally:
            if connection is not None:
                connection.close()

    def register(self, message, channel):
        if not all(self.binding):
            raise BridgeError("DIALOGUE_LOG_UNAVAILABLE", "Dialogue log requires an exact binding.")
        with self.connect() as db:
            found = db.execute("SELECT ref FROM messages WHERE message_id=? AND thread_id=? AND session_id=? AND lease_id=?", (message["id"], *self.binding)).fetchone()
            if found:
                return found[0]
            ref = uuid.uuid4().hex[:10]
            while db.execute("SELECT 1 FROM messages WHERE ref=?", (ref,)).fetchone():
                ref = uuid.uuid4().hex[:10]
            metadata = {k: message[k] for k in ("id", "playerUuid", "playerName", "mode")}
            metadata["permissionGroup"] = message.get("permissionGroup", "unknown")
            metadata["senderKind"] = message.get("senderKind", "player")
            metadata.update(dict(zip(BINDING, self.binding)), replyTo=ref, replyChannel=channel)
            db.execute("INSERT INTO messages(ref,message_id,thread_id,session_id,lease_id,metadata) VALUES(?,?,?,?,?,?)", (ref, message["id"], *self.binding, json.dumps(metadata, ensure_ascii=False)))
            self._event(db, ref, "registered", {})
            db.execute("DELETE FROM messages WHERE seq NOT IN (SELECT seq FROM messages ORDER BY seq DESC LIMIT ?)", (MAX_MESSAGES,))
            return ref

    def resolve(self, ref):
        if not isinstance(ref, str) or REF.fullmatch(ref) is None:
            raise BridgeError("INVALID_ARGUMENT", "replyTo must be the short reference from the MCB player message.")
        with self.connect() as db:
            row = db.execute("SELECT message_id FROM messages WHERE ref=? AND thread_id=? AND session_id=? AND lease_id=?", (ref, *self.binding)).fetchone()
        if row is None:
            raise BridgeError("DIALOGUE_REPLY_NOT_FOUND", "Reply reference is expired or belongs to another binding; no message was sent.")
        return row[0]

    def record(self, message_id, stage, **details):
        # A post-dispatch logging failure must never replay or invalidate a send.
        try:
            with self.connect() as db:
                row = db.execute("SELECT ref FROM messages WHERE message_id=? AND thread_id=? AND session_id=? AND lease_id=?", (message_id, *self.binding)).fetchone()
                if row is None:
                    return False
                allowed = {k: details[k] for k in ("turnId", "transport", "mode", "channel", "errorCode", "connectionId") if k in details}
                self._event(db, row[0], stage, allowed)
                return True
        except BridgeError:
            return False

    @staticmethod
    def _event(db, ref, stage, metadata):
        db.execute("INSERT INTO events(ref,at,stage,metadata) VALUES(?,?,?,?)", (ref, time.time(), stage, json.dumps(metadata, ensure_ascii=False)))
        db.execute("DELETE FROM events WHERE seq NOT IN (SELECT seq FROM events ORDER BY seq DESC LIMIT ?)", (MAX_EVENTS,))
