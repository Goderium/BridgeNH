"""Portable, credential-free machine configuration shared by runtime and setup."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys

from .prism_instances import PrismInstanceError, resolve_prism_instance

LOCAL_NAME = ".mcb.local.json"
MAX_CONFIG_BYTES = 131072
PROFILE_NAME = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}\Z")
TARGET_ENV = ("MCB_INSTANCE_PATH", "MCB_RUNTIME_FILE", "MCB_URL", "MCB_TOKEN",
              "MCB_INSTANCE_ROOT", "MCB_PRISM_ROOT")


class LocalConfigError(ValueError):
    pass


def source_root() -> Path | None:
    root = Path(__file__).resolve().parents[3]
    return root if (root / "build.gradle").is_file() and (root / "mcp-server/pyproject.toml").is_file() else None


def user_config_path(environ=None) -> Path:
    env = os.environ if environ is None else environ
    home = Path(env.get("USERPROFILE") or env.get("HOME") or Path.home())
    if os.name == "nt":
        base = Path(env.get("APPDATA") or home / "AppData/Roaming")
    elif sys.platform == "darwin":
        base = home / "Library/Application Support"
    else:
        base = Path(env.get("XDG_CONFIG_HOME") or home / ".config")
    return base / "MinecraftCodexBridge/config.json"


def config_path(environ=None, *, root=None) -> Path:
    env = os.environ if environ is None else environ
    if env.get("MCB_CONFIG_FILE"):
        return Path(env["MCB_CONFIG_FILE"]).expanduser().resolve()
    checkout = Path(root) if root is not None else source_root()
    if checkout is not None and (checkout / LOCAL_NAME).is_file():
        return checkout / LOCAL_NAME
    return user_config_path(env)


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise LocalConfigError("Duplicate configuration key: " + key)
        result[key] = value
    return result


def read_config(path: Path) -> dict:
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAX_CONFIG_BYTES + 1)
        if len(raw) > MAX_CONFIG_BYTES:
            raise LocalConfigError("Configuration is too large.")
        data = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique)
        if not isinstance(data, dict) or type(data.get("version")) is not int or data["version"] != 1:
            raise LocalConfigError("Expected configuration version 1.")
        profiles = data.get("profiles")
        if not isinstance(profiles, dict) or not profiles:
            raise LocalConfigError("Configuration needs at least one profile.")
        for name, profile in profiles.items():
            if not PROFILE_NAME.fullmatch(name) or not isinstance(profile, dict):
                raise LocalConfigError("Invalid profile name or object.")
            if set(profile) - {"prismRoot", "instanceName", "instancePath", "prismExe"}:
                raise LocalConfigError("Profile supports prismRoot and instanceName, or legacy instancePath, with optional prismExe; credentials belong in runtime.json.")
            if (('prismRoot' in profile) != ('instanceName' in profile)
                    or 'prismRoot' in profile and 'instancePath' in profile):
                raise LocalConfigError("Specify prismRoot and instanceName together, without a legacy instancePath selector.")
            for key, value in profile.items():
                if not isinstance(value, str) or not value.strip() or "\x00" in value:
                    raise LocalConfigError("Invalid profile path: " + key)
        if data.get("activeProfile") is not None and data["activeProfile"] not in profiles:
            raise LocalConfigError("activeProfile must name an existing profile.")
        for section in ("build", "installation"):
            if section in data and not isinstance(data[section], dict):
                raise LocalConfigError(section + " must be an object.")
        return data
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise LocalConfigError("Cannot read configuration: " + str(path)) from error


def resolve_path(value: str, config: Path) -> Path:
    path = Path(value).expanduser()
    return (path if path.is_absolute() else config.parent / path).resolve()


def selected_profile(path: Path, name=None) -> tuple[dict, str, dict]:
    data = read_config(path)
    name = name or data.get("activeProfile")
    if name is None:
        raise LocalConfigError("Select an explicit MCB profile; no activeProfile is configured.")
    if name not in data["profiles"]:
        raise LocalConfigError("Unknown MCB profile: " + str(name))
    profile = resolved_profile(data["profiles"][name], path)
    if "instancePath" not in profile:
        raise LocalConfigError("Selected profile needs prismRoot and instanceName; run the installer to fill them in.")
    return data, name, profile


def resolved_profile(profile: dict, path: Path) -> dict:
    if profile.get('prismRoot'):
        try:
            selected = resolve_prism_instance(resolve_path(profile['prismRoot'], path), profile['instanceName'])
        except PrismInstanceError as error:
            raise LocalConfigError(str(error)) from None
        result = {key: selected[key] for key in ('prismRoot', 'instanceName', 'instancePath', 'displayName')}
        if selected['prismExe']:
            result['prismExe'] = selected['prismExe']
        if profile.get('prismExe'):
            result['prismExe'] = str(resolve_path(profile['prismExe'], path))
        return result
    return {key: str(resolve_path(value, path)) for key, value in profile.items()}


def configured_environment(environ=None, *, auto=True) -> dict[str, str]:
    env = dict(os.environ if environ is None else environ)
    explicit = bool(env.get("MCB_CONFIG_FILE") or env.get("MCB_PROFILE"))
    # Legacy explicit connection settings remain authoritative and isolated from local defaults.
    if not explicit and (not auto or any(env.get(key) for key in TARGET_ENV)):
        return env
    path = config_path(env)
    if not path.is_file() and not explicit:
        return env
    _, name, profile = selected_profile(path, env.get("MCB_PROFILE"))
    env["MCB_CONFIG_FILE"] = str(path.resolve())
    env["MCB_PROFILE"] = name
    if not any(env.get(key) for key in TARGET_ENV):
        env["MCB_INSTANCE_PATH"] = profile["instancePath"]
        if profile.get("prismExe"):
            env.setdefault("MCB_PRISM_EXE", profile["prismExe"])
    elif env.get("MCB_INSTANCE_PATH") and resolve_path(env["MCB_INSTANCE_PATH"], path) == Path(profile["instancePath"]):
        if profile.get("prismExe"):
            env.setdefault("MCB_PRISM_EXE", profile["prismExe"])
    return env
