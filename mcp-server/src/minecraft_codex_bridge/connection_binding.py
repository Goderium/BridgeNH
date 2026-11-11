"""Private, credential-free conversation binding passed to supervised workers."""
from copy import deepcopy
import json
import os
from pathlib import Path

from .backend_transport import BackendError, fingerprint

BINDING_ENV = "MCB_CHAT_BINDING"


def client_id(path):
    return fingerprint(os.path.normcase(str(Path(path).resolve())))


def conversation_id(value):
    return value if isinstance(value, str) and 1 <= len(value) <= 128 and all(33 <= ord(c) < 127 for c in value) else None


def initial_binding(environ):
    owner = conversation_id(environ.get("CODEX_THREAD_ID"))
    return {"version": 1, "conversationId": owner, "ownerSource": "CODEX_THREAD_ID" if owner else None, "generation": 0,
            "bindingId": None, "clientId": None, "instancePath": None,
            "profiles": [], "prismExe": None, "prismRoot": None, "instanceName": None, "sessionIdAtBind": None}


def read_binding(environ):
    raw = environ.get(BINDING_ENV)
    if raw is None:
        return None  # Explicit CLI/embedded adapters retain their existing target contract.
    try:
        if len(raw) > 8192:
            raise ValueError()
        value = json.loads(raw)
        expected = set(initial_binding({}))
        if (not isinstance(value, dict) or set(value) != expected or type(value["version"]) is not int or value["version"] != 1
                or type(value["generation"]) is not int or value["generation"] < 0
                or not isinstance(value["profiles"], list)
                or any(not isinstance(p, str) or len(p) > 64 for p in value["profiles"])):
            raise ValueError()
        owner = value["conversationId"]
        host_owner = initial_binding(environ)["conversationId"]
        if (owner is not None and conversation_id(owner) is None
                or host_owner is not None and owner != host_owner
                or owner is None and value["ownerSource"] is not None
                or owner is not None and value["ownerSource"] not in {"CODEX_THREAD_ID", "explicit_bind_request"}
                or value["ownerSource"] == "CODEX_THREAD_ID" and owner != host_owner):
            raise ValueError()
        path = value["instancePath"]
        if path is None:
            if any(value[key] is not None for key in ("clientId", "bindingId", "prismExe", "prismRoot", "instanceName", "sessionIdAtBind")) or value["profiles"]:
                raise ValueError()
        elif (not isinstance(path, str) or len(path) > 2048 or not Path(path).is_absolute()
                or value["conversationId"] is None or value["clientId"] != client_id(path)
                or value["prismExe"] is not None and
                (not isinstance(value["prismExe"], str) or not Path(value["prismExe"]).is_absolute())
                or not isinstance(value["bindingId"], str) or not 1 <= len(value["bindingId"]) <= 128
                or value["sessionIdAtBind"] is not None and
                (not isinstance(value["sessionIdAtBind"], str) or not 1 <= len(value["sessionIdAtBind"]) <= 128)):
            raise ValueError()
        if (value['prismRoot'] is not None and (not isinstance(value['prismRoot'], str)
                or len(value['prismRoot']) > 2048 or not Path(value['prismRoot']).is_absolute())
                or value['instanceName'] is not None and (not isinstance(value['instanceName'], str)
                or not 1 <= len(value['instanceName']) <= 256)):
            raise ValueError()
        return value
    except (ValueError, TypeError, KeyError):
        raise BackendError("CONVERSATION_BINDING_INVALID") from None


def binding_environment(environ, binding):
    return {**environ, BINDING_ENV: json.dumps(binding, ensure_ascii=False, separators=(",", ":"))}


def binding_status(binding):
    return {**deepcopy(binding), "bound": binding["instancePath"] is not None,
            "bindingGeneration": binding["generation"],
            "scope": "conversation_owned_stdio_connection", "defaultBinding": False,
            "ownerMeaning": "host environment when available; otherwise explicitly declared by the chat on first bind, not independent authentication",
            "liveStatus": "not_observed", "sessionIdAtBindMeaning": "historical bind probe; use mcb_health for current session",
            "persistence": "this MCP frontend lifetime; worker reload preserves it; a new frontend requires explicit binding"}
