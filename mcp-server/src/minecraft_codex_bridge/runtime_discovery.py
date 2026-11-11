"""Discover the live Codex Bridge runtime without binding to one Prism instance."""

from __future__ import annotations

import json
import os
import socket
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class RuntimeCandidate:
    path: Path
    instance_path: Path
    base_url: str
    token: str
    modified_ns: int


DEFAULT_PRISM_ROOTS = (
    Path(r"%LOCALAPPDATA%\PrismLauncher"),
    Path(r"%APPDATA%\PrismLauncher"),
)
MAX_RUNTIME_CANDIDATES = 64


def _expand(path: Path) -> Path:
    return Path(os.path.expandvars(str(path))).expanduser()


def _valid_runtime_path(path: Path) -> bool:
    return [part.casefold() for part in path.parts[-4:]] == [
        ".minecraft", "config", "codexbridge", "runtime.json"
    ]


def _candidate_from_path(path: Path) -> RuntimeCandidate | None:
    try:
        resolved = path.resolve(strict=True)
        if not resolved.is_file() or not _valid_runtime_path(resolved):
            return None
        raw = resolved.read_bytes()
        if len(raw) > 8192:
            return None
        config = json.loads(raw.decode("utf-8"))
        if not isinstance(config, dict) or config.get("protocolVersion") != 1:
            return None
        base_url, token = config.get("baseUrl"), config.get("token")
        if not isinstance(base_url, str) or not isinstance(token, str):
            return None
        parsed = urlsplit(base_url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path not in ("", "/"):
            return None
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            return None
        if not 1 <= len(token) <= 512 or any(ord(char) < 33 or ord(char) > 126 for char in token):
            return None
        # runtime.json is exactly instance/.minecraft/config/codexbridge/runtime.json.
        instance = resolved.parents[3]
        return RuntimeCandidate(resolved, instance, base_url.rstrip("/"), token, resolved.stat().st_mtime_ns)
    except (OSError, ValueError, TypeError, UnicodeError, json.JSONDecodeError):
        return None


def _roots(environ: dict[str, str]) -> list[Path]:
    roots: list[Path] = []
    configured_root = False
    for key in ("MCB_INSTANCE_ROOT", "MCB_PRISM_ROOT"):
        value = environ.get(key)
        if value:
            configured_root = True
            root = _expand(Path(value))
            roots.append(root / "instances" if key == "MCB_PRISM_ROOT" else root)
    instance = environ.get("MCB_INSTANCE_PATH")
    if instance:
        path = _expand(Path(instance))
        roots.append(path.parent)
    prism_exe = environ.get("MCB_PRISM_EXE")
    if prism_exe:
        configured_root = True
        roots.append(_expand(Path(prism_exe)).parent / "instances")
    if not configured_root:
        for root in DEFAULT_PRISM_ROOTS:
            roots.append(_expand(root) / "instances")
    result: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        key = str(root.resolve()).casefold() if root.exists() else str(root.absolute()).casefold()
        if key not in seen:
            seen.add(key)
            result.append(root)
    return result


def discover_runtime_candidates(environ: dict[str, str]) -> list[RuntimeCandidate]:
    """Return valid runtime files, newest first, without reading arbitrary directories."""
    paths: dict[str, Path] = {}
    for root in _roots(environ):
        try:
            for instance in root.iterdir():
                if not instance.is_dir():
                    continue
                runtime = instance / ".minecraft" / "config" / "codexbridge" / "runtime.json"
                key = str(runtime).casefold()
                if key not in paths:
                    paths[key] = runtime
                if len(paths) >= MAX_RUNTIME_CANDIDATES:
                    break
        except OSError:
            continue
    candidates = [candidate for path in paths.values() if (candidate := _candidate_from_path(path)) is not None]
    return sorted(candidates, key=lambda candidate: (candidate.modified_ns, str(candidate.path).casefold()), reverse=True)


def _port_is_open(base_url: str, timeout: float = 0.12) -> bool:
    parsed = urlsplit(base_url)
    try:
        with socket.create_connection((parsed.hostname, parsed.port or 80), timeout=timeout):
            return True
    except (OSError, TypeError, ValueError):
        return False


def select_runtime(environ: dict[str, str]) -> RuntimeCandidate:
    candidates = discover_runtime_candidates(environ)
    live = [candidate for candidate in candidates if _port_is_open(candidate.base_url)]
    if not live:
        locations = ", ".join(str(candidate.path) for candidate in candidates[:8]) or "none"
        raise RuntimeDiscoveryError(
            "No live Codex Bridge runtime was found. Set MCB_RUNTIME_FILE or MCB_INSTANCE_PATH explicitly; "
            f"discovered runtime files: {locations}."
        )
    # The mod replaces runtime.json atomically at every client launch. Newest live
    # runtime therefore identifies the most recently selected client without a
    # hard-coded instance name. An explicit path always bypasses this selection.
    return live[0]


class RuntimeDiscoveryError(Exception):
    pass
