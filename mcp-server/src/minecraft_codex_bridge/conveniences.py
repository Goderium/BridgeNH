"""Bounded local log reads and non-atomic diagnostic collections."""

from __future__ import annotations

import hashlib
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import anyio

from .bridge import BridgeClient, BridgeError, Configuration, redact
from .known_issues import recognize as recognize_known_issues

MAX_LOG_TAIL_BYTES = 65_536
MAX_LOG_TEXT_BYTES = 49_152
MAX_CAPTURE_BYTES = 262_144
CAPTURE_TIMEOUT_SECONDS = 30.0
MAX_OBSERVATION_BYTES = 65_536
MAX_WATCH_BYTES = 131_072
MAX_WATCH_VALUE_BYTES = 2_048
DEFAULT_WATCH_FIELDS = [
    "gregtech.progress", "gregtech.maxProgress", "gregtech.active",
    "gregtech.allowedToWork", "gregtech.storedEU", "gregtech.signedCalculatedEUt",
    "gregtech.recipeResultMessage",
]
SENSITIVE_LOG = re.compile(
    r"(?i)(?:\bbearer\s+|\bauthorization\b|\bauth(?:entication)?\b|\bcredentials\b|\b(?:access|refresh|session|auth|id)[ _-]?token\b|"
    r"\btoken\b|\bpassword\b|\bpasswd\b|\bsecret\b|\bapi[ _-]?key\b|"
    r"\blaunch(?:er)?[ _-]?(?:auth|credentials)\b|--(?:accessToken|session|username)\b|\bsession[ _-]?id\b)"
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def json_size(value: Any) -> int:
    return len(json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8"))


def json_sha256(value: Any) -> str:
    """Fingerprint the complete finite JSON value without dropping fields or array entries."""
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True,
        separators=(",", ":")).encode("utf-8")).hexdigest()


def clipped_text(value: str, limit: int) -> str:
    return value.encode("utf-8")[:limit].decode("utf-8", errors="ignore")


def bounded_value(value: Any, maximum: int = MAX_WATCH_VALUE_BYTES) -> Any:
    size = json_size(value)
    if size <= maximum:
        return value
    result = {"truncated": True, "type": type(value).__name__, "serializedBytes": size}
    if isinstance(value, str):
        result["preview"] = clipped_text(value, min(512, maximum // 2))
    elif isinstance(value, (dict, list)):
        result["entries"] = len(value)
    return result


def path_get(observation: Any, path: str) -> tuple[bool, Any]:
    current = observation
    for segment in path.split("."):
        if isinstance(current, dict) and segment in current:
            current = current[segment]
        elif isinstance(current, list) and segment.isdigit() and int(segment) < len(current):
            current = current[int(segment)]
        else:
            return False, None
    return True, current


def read_logs(configuration: Configuration, arguments: dict[str, Any]) -> dict[str, Any]:
    """Read a fixed latest/client-FML log in the validated runtime, using a bounded tail."""
    runtime_path = configuration.runtime_path()
    if runtime_path is None:
        raise BridgeError("LOG_CONFIG_REQUIRED", "Log reads require a runtime file or an auto-discovered Prism instance.")
    runtime_path = runtime_path.absolute()
    if [part.lower() for part in runtime_path.parts[-4:]] != [".minecraft", "config", "codexbridge", "runtime.json"]:
        raise BridgeError("LOG_CONFIG_REQUIRED", "The runtime path must end in .minecraft/config/codexbridge/runtime.json.")
    offline = False
    try:
        token = configuration.read().token
    except BridgeError as error:
        if error.code != "CONFIGURATION_ERROR" or runtime_path.exists() or runtime_path.is_symlink():
            raise
        from .launcher import diagnostic_configuration
        # An absent credential file is expected after normal client shutdown.
        # Offline reads still require the exact verified portable Prism instance.
        settings = diagnostic_configuration(configuration)
        try:
            game = runtime_path.parents[2].resolve(strict=True)
        except OSError:
            raise BridgeError("LOG_UNAVAILABLE", "The selected fixed game directory is missing or cannot be read.") from None
        if settings.instance_path / ".minecraft" != game:
            raise BridgeError("LOG_PATH_REFUSED", "Offline logs must belong to the exact configured Prism instance.")
        token, offline = "", True
    try:
        game_root = runtime_path.parents[2].resolve(strict=True)
        expected_runtime = game_root / "config" / "codexbridge" / "runtime.json"
        if runtime_path.resolve(strict=not offline) != expected_runtime:
            raise BridgeError("LOG_PATH_REFUSED", "The runtime file resolves outside the expected fixed game configuration path.")
        filename = {"latest": "latest.log", "fml-client-latest": "fml-client-latest.log"}.get(arguments.get("log", "latest"))
        if filename is None:
            raise BridgeError("LOG_PATH_REFUSED", "Only latest or fml-client-latest may be selected.")
        log_path = game_root / "logs" / filename
        if log_path.resolve(strict=True) != log_path:
            raise BridgeError("LOG_PATH_REFUSED", "The fixed log path may not redirect to another file or directory.")
        with log_path.open("rb") as stream:
            stream.seek(0, 2)
            length = stream.tell()
            start = max(0, length - MAX_LOG_TAIL_BYTES)
            stream.seek(start)
            raw = stream.read(MAX_LOG_TAIL_BYTES)
            bytes_read = len(raw)
    except BridgeError:
        raise
    except OSError:
        raise BridgeError("LOG_UNAVAILABLE", "The selected fixed game log is missing or cannot be read.") from None
    if start:
        # Never emit the tail of a partially read sensitive line whose marker was outside the tail.
        _, separator, raw = raw.partition(b"\n")
        if not separator:
            raw = b""
    level = arguments.get("level", "WARN")
    original_query = arguments.get("query", "")
    query = original_query.casefold()
    matches = []
    diagnostic_lines = []
    redacted_count = 0
    for line in raw.decode("utf-8", errors="replace").splitlines():
        selected_level = (level == "ANY" or bool(re.search(r"\b(?:WARN|ERROR|FATAL)\b" if level == "WARN" else r"\b(?:ERROR|FATAL)\b", line, re.IGNORECASE)))
        if SENSITIVE_LOG.search(line) or token and token in line:
            redacted_count += int(selected_level)
            line = "[redacted sensitive log line]"
        line = redact(line, token) if token else line
        diagnostic_lines.append(line)
        if not selected_level:
            continue
        if query and query not in line.casefold():
            continue
        matches.append(clipped_text(line, 2_048))
    limit = arguments.get("limit", 128)
    selected = []
    text_bytes = 0
    for line in reversed(matches[-limit:]):
        length = len(line.encode("utf-8"))
        if text_bytes + length > MAX_LOG_TEXT_BYTES:
            break
        selected.append(line)
        text_bytes += length
    selected.reverse()
    result = {
        "source": "logs/" + filename, "level": level, "query": redact(original_query, token) if token else original_query, "lines": selected,
        "returned": len(selected), "bytesRead": bytes_read,
        "tailLimitBytes": MAX_LOG_TAIL_BYTES, "redactedLines": redacted_count,
        "truncated": start > 0 or len(selected) < len(matches),
        "runtimeAvailable": not offline,
        "bindingSource": "validated_portable_Prism_instance" if offline else "validated_runtime_file",
    }
    known = recognize_known_issues(diagnostic_lines)
    if known:
        result["knownIssues"] = known
    return result


async def capture(client: BridgeClient, arguments: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
    report: dict[str, Any] = {
        "atomic": False, "startedAt": utc_now(), "observations": {}, "errors": [],
        "sessionId": None, "profile": None, "sessionIdEnd": None, "profileEnd": None,
        "sessionConsistent": False, "origin": {"world": None, "player": None}, "window": {},
    }
    screenshot = None

    async def collect(name: str, operation: str, args: dict[str, Any] | None = None) -> dict[str, Any] | None:
        try:
            value = await client.invoke(operation, args or {})
            if json_size(value) > MAX_OBSERVATION_BYTES:
                raise BridgeError("OBSERVATION_TOO_LARGE", "This observation exceeds the capture's 64 KiB per-observation limit.")
            report["observations"][name] = {"ok": True, "data": value, "observedAt": utc_now()}
            return value
        except BridgeError as error:
            report["observations"][name] = {"ok": False, "error": error.as_dict(), "observedAt": utc_now()}
            report["errors"].append({"observation": name, **error.as_dict()})
            return None

    try:
        with anyio.fail_after(CAPTURE_TIMEOUT_SECONDS):
            beginning = await collect("healthBegin", "bridge.health")
            report.update(sessionId=bounded_value((beginning or {}).get("sessionId"), 512), profile=bounded_value((beginning or {}).get("profile"), 512))
            snapshot = await collect("snapshot", "client.snapshot")
            report["origin"] = {"world": bounded_value((snapshot or {}).get("world"), 4_096), "player": bounded_value((snapshot or {}).get("player"), 4_096)}
            gui = await collect("gui", "gui.inspect")
            report["window"] = {key: bounded_value((gui or {}).get(key), 512) for key in ("screenId", "windowId", "screen", "width", "height")}
            if snapshot and snapshot.get("worldLoaded", bool(snapshot.get("world"))):
                await collect("inventory", "inventory.inspect")
            else:
                report["observations"]["inventory"] = {"skipped": True, "reason": "No loaded world was observed."}
            await collect("diagnostics", "client.diagnostics")
            positions = arguments.get("positions", [])
            if positions:
                args = {"positions": positions}
                if "dimension" in arguments:
                    args["dimension"] = arguments["dimension"]
                await collect("positions", "world.inspect_many", args)
            if arguments.get("include_screenshot", False):
                try:
                    screenshot = await client.invoke("client.screenshot", {})
                except BridgeError as error:
                    report["errors"].append({"observation": "screenshot", **error.as_dict()})
                    report["observations"]["screenshot"] = {"ok": False, "error": error.as_dict()}
            ending = await collect("healthEnd", "bridge.health")
            report.update({
                "sessionIdEnd": bounded_value((ending or {}).get("sessionId"), 512), "profileEnd": bounded_value((ending or {}).get("profile"), 512),
                "sessionConsistent": bool(beginning and ending and isinstance(beginning.get("sessionId"), str) and beginning["sessionId"] and beginning["sessionId"] == ending.get("sessionId")),
            })
    except TimeoutError:
        report["errors"].append({"observation": "capture", "code": "CAPTURE_TIMEOUT", "message": "The 30-second bounded collection expired; existing observations are partial."})
    report["endedAt"] = utc_now()
    report["partial"] = bool(report["errors"])
    # Bound the complete JSON bundle without including the separately returned single PNG.
    if json_size(report) > MAX_CAPTURE_BYTES - 2_048:
        for name, observation in list(report["observations"].items()):
            if json_size(report) <= MAX_CAPTURE_BYTES - 2_048:
                break
            if observation.get("ok"):
                error = {"code": "CAPTURE_SIZE_LIMIT", "message": "Observation omitted to keep the total capture below 256 KiB."}
                report["observations"][name] = {"ok": False, "error": error}
                report["errors"].append({"observation": name, **error})
                report["partial"] = True
    return report, screenshot


async def machine_watch(client: BridgeClient, arguments: dict[str, Any]) -> dict[str, Any]:
    fields = arguments.get("fields", DEFAULT_WATCH_FIELDS)
    duration = arguments.get("duration_ms", 3_000) / 1000
    interval = arguments.get("interval_ms", 1000) / 1000
    position = {key: arguments[key] for key in ("x", "y", "z", "dimension") if key in arguments}
    start = time.monotonic()
    report: dict[str, Any] = {"startedAt": utc_now(), "position": position, "fields": fields, "samples": [], "truncated": False, "reason": "duration_elapsed"}
    try:
        with anyio.fail_after(duration):
            while len(report["samples"]) < 64:
                observation = await client.invoke("world.inspect", position)
                projected = {}
                for field in fields:
                    exists, value = path_get(observation, field)
                    projected[field] = {"exists": exists}
                    if exists:
                        projected[field]["value"] = bounded_value(value)
                sample = {"elapsed_ms": int((time.monotonic() - start) * 1000), "observedAt": utc_now(), "source": bounded_value(observation.get("source"), 256), "values": projected}
                if json_size(report) + json_size(sample) > MAX_WATCH_BYTES - 4_096:
                    report.update(truncated=True, reason="report_size_limit")
                    break
                report["samples"].append(sample)
                await anyio.sleep(interval)
            else:
                report.update(truncated=True, reason="sample_limit")
    except TimeoutError:
        pass
    except BridgeError as error:
        report.update(error=error.as_dict(), reason="bridge_error")
    report.update(endedAt=utc_now(), elapsed_ms=int((time.monotonic() - start) * 1000), sampleCount=len(report["samples"]))
    return report
