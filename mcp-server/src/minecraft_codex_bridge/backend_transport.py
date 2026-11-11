"""Frozen supervisor/worker wire contract. Never import game or Adapter modules here."""
from __future__ import annotations

import hashlib
from importlib.metadata import version
import json
import sys

PROTOCOL = "mcb-supervised-worker-v1"
MAX_FRAME = 32 * 1024 * 1024
MAX_STATE = 4 * 1024 * 1024


def runtime_versions():
    return {"python": sys.version, **{name: version(name) for name in ("mcp", "httpx", "jsonschema", "anyio", "pydantic")}}


class BackendError(Exception):
    def __init__(self, code, *, outcome_unknown=False):
        super().__init__(code)
        self.code = code
        self.outcome_unknown = outcome_unknown


def encode(value):
    data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8") + b"\n"
    if len(data) > MAX_FRAME:
        raise BackendError("BACKEND_FRAME_TOO_LARGE")
    return data


def decode(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def constant(value):
        raise ValueError("non-finite JSON")

    if not raw or len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
        raise BackendError("BACKEND_INVALID_FRAME")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, UnicodeError, RecursionError):
        raise BackendError("BACKEND_INVALID_FRAME") from None


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()
