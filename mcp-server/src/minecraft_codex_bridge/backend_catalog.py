"""Validate a worker's public contract before committing its code and catalog."""
from __future__ import annotations

import json

import jsonschema
from mcp import types

from .backend_transport import BackendError, fingerprint

MAX_CATALOG_BYTES = 2 * 1024 * 1024
MAX_TOOLS = 512
MAX_RESOURCES = 128
MANAGEMENT_TOOLS = {"mcb_backend_status", "mcb_backend_restart", "mcb_clients", "mcb_connection"}


def validate_contract(contract):
    try:
        if (not isinstance(contract, dict) or set(contract) != {"tools", "resources", "instructions"}
                or not isinstance(contract["tools"], list) or len(contract["tools"]) > MAX_TOOLS
                or not isinstance(contract["resources"], list) or len(contract["resources"]) > MAX_RESOURCES
                or not isinstance(contract["instructions"], (str, type(None)))
                or len(json.dumps(contract, ensure_ascii=False, allow_nan=False).encode("utf-8")) > MAX_CATALOG_BYTES):
            raise ValueError("Invalid contract shape or bounds")
        names, uris = set(), set()
        for entry in contract["tools"]:
            tool = types.Tool.model_validate(entry)
            if tool.name in names or tool.name in MANAGEMENT_TOOLS:
                raise BackendError("BACKEND_RESERVED_TOOL_CONFLICT")
            if not tool.name or len(tool.name) > 128:
                raise ValueError("Invalid tool")
            names.add(tool.name)
            jsonschema.Draft202012Validator.check_schema(tool.inputSchema)
            if tool.outputSchema is not None:
                jsonschema.Draft202012Validator.check_schema(tool.outputSchema)
        for entry in contract["resources"]:
            resource = types.Resource.model_validate(entry)
            uri = str(resource.uri)
            if uri in uris:
                raise ValueError("Duplicate resource")
            uris.add(uri)
    except BackendError:
        raise
    except (ValueError, TypeError, KeyError, jsonschema.SchemaError):
        raise BackendError("BACKEND_CATALOG_INVALID") from None


def catalog_change(previous, following):
    previous_fingerprint = fingerprint(previous) if previous is not None else None
    previous = previous or {"tools": [], "resources": []}
    result = {}
    for kind, key in (("tools", "name"), ("resources", "uri")):
        old = {entry[key]: entry for entry in previous[kind]}
        new = {entry[key]: entry for entry in following[kind]}
        result[kind] = {
            "changed": previous[kind] != following[kind],
            "added": sorted(new.keys() - old.keys()), "removed": sorted(old.keys() - new.keys()),
            "updated": sorted(name for name in old.keys() & new.keys() if old[name] != new[name]),
        }
    result["previousFingerprint"] = previous_fingerprint
    result["fingerprint"] = fingerprint(following)
    return result
