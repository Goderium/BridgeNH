"""Durable survival orders in the pinned instance, isolated by conversation."""

from contextlib import contextmanager
from hashlib import sha256
import json
import os
import sqlite3

from .bridge import BridgeError
from .run_log import _owned


class OrderStore:
    def __init__(self, journal):
        root = journal.root()
        self.directory = _owned(root / "survival-orders", root)
        self.directory.mkdir(exist_ok=True)
        binding = getattr(journal.configuration, "conversation_binding", None)
        if binding is not None and not (binding.get("instancePath") and binding.get("conversationId")):
            raise BridgeError("CLIENT_NOT_BOUND", "Bind this conversation to the intended instance first.")
        owner = "chat:" + binding["conversationId"] if binding else "direct-runtime"
        self.owner = sha256(owner.encode()).hexdigest()
        self.database = _owned(self.directory / "orders.sqlite3", self.directory)
        for suffix in ("-journal", "-shm", "-wal"):
            _owned(self.directory / (self.database.name + suffix), self.directory)

    @contextmanager
    def exclusive(self):
        # SQLite transactions below commit before native dispatch. The independent
        # OS lock serializes operators across workers and is released on process exit.
        path = _owned(self.directory / "advance.lock", self.directory)
        with path.open("a+b") as handle:
            if os.name == "nt":
                import msvcrt
                if handle.seek(0, 2) == 0:
                    handle.write(b"0")
                    handle.flush()
                handle.seek(0)
                try:
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                except OSError:
                    raise BridgeError("ORDER_BUSY", "Another order is advancing in this instance.") from None
            else:
                import fcntl
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise BridgeError("ORDER_BUSY", "Another order is advancing in this instance.") from None
            yield

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database, timeout=0.15)
        try:
            with db:
                db.execute("CREATE TABLE IF NOT EXISTS orders (owner TEXT, id TEXT, revision INTEGER, "
                           "record TEXT NOT NULL, PRIMARY KEY(owner, id))")
                yield db
        finally:
            db.close()

    def load(self, order_id):
        with self.connect() as db:
            row = db.execute("SELECT record FROM orders WHERE owner=? AND id=?", (self.owner, order_id)).fetchone()
        return json.loads(row[0]) if row else None

    def save(self, order, *, create=False):
        revision = order["revision"] + 1
        record = json.dumps({**order, "revision": revision}, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        if len(record.encode("utf-8")) > 196608:
            raise BridgeError("ORDER_TOO_LARGE", "The complete order exceeds the local journal limit.")
        with self.connect() as db:
            if create:
                db.execute("INSERT INTO orders VALUES (?, ?, ?, ?)", (self.owner, order["orderId"], revision, record))
            else:
                row = db.execute("UPDATE orders SET revision=?, record=? WHERE owner=? AND id=? AND revision=?",
                                 (revision, record, self.owner, order["orderId"], order["revision"]))
                if row.rowcount != 1:
                    raise BridgeError("ORDER_CHANGED", "Read the current revision before continuing.")
        order["revision"] = revision
