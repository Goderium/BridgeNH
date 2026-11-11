"""Bounded serial JSON-lines fallback using the original long-lived Adapter; no action retries."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import sys

from .bridge import BridgeClient, BridgeError, Configuration, MAX_REQUEST_BYTES, strict_json
from .application.adapter import Adapter
from .agent_instructions import AGENT_INSTRUCTIONS

PROTOCOL = "mcb-adapter-session-v1"
MAX_OUTPUT_BYTES = 32 * 1024 * 1024
MAX_ID = 9223372036854775807


def startup_metadata(runtime_file: Path | None):
    root = Path(__file__).resolve().parent
    paths = []
    for path in root.rglob("*.py"):
        if len(paths) >= 512:
            raise ValueError("Package source fingerprint exceeds its bounded startup inventory.")
        paths.append(path)
    paths.sort()
    digest = hashlib.sha256()
    total = 0
    for path in paths:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative + b"\0")
        file_digest = hashlib.sha256()
        with path.open("rb") as stream:
            while chunk := stream.read(65536):
                total += len(chunk)
                if total > 32 * 1024 * 1024:
                    raise ValueError("Package source fingerprint exceeds its bounded startup bytes.")
                file_digest.update(chunk)
        digest.update(file_digest.digest())
    return {"type": "ready", "protocol": PROTOCOL, "adapterReady": True,
        "clientReadiness": "not_observed", "worldReadiness": "not_observed", "packageRoot": str(root),
        "packageSourceSha256": digest.hexdigest(), "packageSourceFiles": len(paths),
        "sourceFingerprintScope": "package_source_tree_at_startup; no reload or per-request scan",
        "pythonExecutable": str(Path(sys.executable).resolve()), "runtimeFile": str(runtime_file.resolve()) if runtime_file else None,
        "sequential": True, "maxRequestBytes": MAX_REQUEST_BYTES, "maxOutputBytes": MAX_OUTPUT_BYTES,
        "requestIdPolicy": "strictly_increasing_integer_1_to_signed_long_max; accepted_ids_never_reused",
        "credentialsCached": False, "automaticActionRetries": False, "instructions": AGENT_INSTRUCTIONS}


def _encoded(value):
    output = bytearray()
    encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    for fragment in encoder.iterencode(value):
        chunk = fragment.encode("utf-8")
        if len(output) + len(chunk) + 1 > MAX_OUTPUT_BYTES:
            raise ValueError("Bounded session result exceeded output capacity.")
        output.extend(chunk)
    output.extend(b"\n")
    return output


def _emit(stream, value):
    stream.write(_encoded(value))
    stream.flush()


def _error(code, *, identifier=None, dispatched=False, adapter_error=None):
    value = {"type": "session_error", "id": identifier, "error": {"code": code,
        "adapterDispatched": dispatched, "outcomeUnknown": dispatched, "retryAllowed": False}}
    if adapter_error is not None:
        value["error"]["adapterIsError"] = adapter_error
    return value


async def run_session(source, target, configuration, *, client=None, metadata=None):
    """Only one request is read/dispatched at a time; EOF always closes the original HTTP client."""
    try:
        runtime_file = configuration.runtime_path()
    except BridgeError:
        # A serial adapter can start before Minecraft has written runtime.json;
        # the first actual tool call reports the bounded connection error.
        runtime_file = None
    if runtime_file is not None and not runtime_file.is_absolute():
        raise ValueError("The session runtime path must be absolute.")
    active = client or BridgeClient(configuration)
    last_id = 0
    try:
        adapter = Adapter(active)
        _emit(target, metadata if metadata is not None else startup_metadata(runtime_file))
        while True:
            raw = source.readline(MAX_REQUEST_BYTES + 1)
            if not raw:
                return 0
            if len(raw) > MAX_REQUEST_BYTES:
                # Terminate instead of waiting forever to drain an unbounded producer. This request was not dispatched.
                _emit(target, _error("SESSION_REQUEST_TOO_LARGE"))
                return 2
            try:
                request = strict_json(raw.decode("utf-8", errors="strict").encode("utf-8"))
                if not isinstance(request, dict) or set(request) != {"id", "tool", "arguments"}:
                    raise ValueError("Invalid session envelope")
                identifier = request["id"]
                if type(identifier) is not int or not 1 <= identifier <= MAX_ID:
                    raise ValueError("Invalid session id")
                if not isinstance(request["tool"], str) or not 1 <= len(request["tool"]) <= 128 or not isinstance(request["arguments"], dict):
                    raise ValueError("Invalid session tool envelope")
            except (ValueError, UnicodeError, TypeError, RecursionError):
                _emit(target, _error("SESSION_INVALID_REQUEST"))
                continue
            if identifier <= last_id:
                _emit(target, _error("SESSION_REQUEST_ID_REUSED", identifier=identifier))
                continue
            # Consume the ID before invoking the original Adapter, including schema refusals and unknown outcomes.
            last_id = identifier
            reply = await adapter.call_tool(request["tool"], request["arguments"])
            try:
                _emit(target, {"type": "result", "id": identifier,
                    "result": reply.model_dump(mode="json", by_alias=True, exclude_none=True)})
            except (ValueError, TypeError, RecursionError):
                _emit(target, _error("SESSION_RESULT_UNAVAILABLE", identifier=identifier, dispatched=True, adapter_error=reply.isError))
    finally:
        await active.aclose()


def main():
    parser = argparse.ArgumentParser(description="Serial original-Adapter JSON-lines fallback; no retries or source reload.")
    parser.add_argument("--runtime-file", default=os.environ.get("MCB_RUNTIME_FILE"), help="Runtime-file path; omit to auto-discover the newest live local Prism instance.")
    options = parser.parse_args()
    environment = dict(os.environ)
    if options.runtime_file:
        environment["MCB_RUNTIME_FILE"] = str(Path(options.runtime_file).resolve())
    else:
        environment.pop("MCB_RUNTIME_FILE", None)
    configuration = Configuration(environment)
    # No platform default text decoding: requests and replies are explicit UTF-8 bytes.
    code = asyncio.run(run_session(sys.stdin.buffer, sys.stdout.buffer, configuration))
    raise SystemExit(code)


if __name__ == "__main__":
    main()
