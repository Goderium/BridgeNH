"""Fresh tool discovery and guarded calls over already registered MCP entrypoints.

This does not claim that the host refreshed its native tool list. The supervised
worker resolves calls before its normal validation, recovery and dispatch path.
"""
from copy import deepcopy
import json

from .backend_transport import fingerprint
from .bridge import BridgeError

MAX_CATALOG_REPLY = 262144
DENIED_TARGETS = {"mcb_sequence"}  # No recursive envelopes or workflow nesting.


def current_catalog():
    from .application.descriptions import tool_descriptions
    rows = [tool.model_dump(mode="json", by_alias=True, exclude_none=True) for tool in tool_descriptions()]
    return rows, fingerprint(rows)


def call_guards(configuration, revision):
    binding = getattr(configuration, "conversation_binding", None)
    if not binding or binding["instancePath"] is None:
        return None
    return {"expectedConversationId": binding["conversationId"],
            "expectedBindingId": binding["bindingId"], "expectedBindingGeneration": binding["generation"],
            "expectedCatalogFingerprint": revision}


def catalog_hint(configuration):
    _, revision = current_catalog()
    return {"version": 1, "catalogTool": "mcb_help", "catalogArguments": {"topic": "tools"},
            "callTool": "mcb_sequence", "callProperty": "call", "callShape": {"tool": "<listed tool name>", "args": {}},
            "callGuards": call_guards(configuration, revision), "catalogFingerprint": revision,
            "scope": "current worker business tools; refresh catalog after backend reload",
            "hostNativeToolList": "host controlled; compatibility calls do not require its refresh",
            "instructions": "Read mcb_help(topic=tools, tool=<name>) for the current exact input schema. Merge callGuards with call:{tool:<name>,args:<validated arguments>} and send to mcb_sequence. Normal validation, permissions, takeover, state and no-replay guards still apply. Management tools stay direct. Never retry an uncertain action."}


def describe_catalog(configuration, arguments):
    rows, revision = current_catalog()
    expected = arguments.get("expectedCatalogFingerprint")
    if expected is not None and expected != revision:
        raise BridgeError("TOOL_CATALOG_CHANGED", "Tool definitions changed; read the current catalog before another call.", outcome_unknown=False)
    if arguments.get("offset", 0) and expected is None:
        raise BridgeError("TOOL_CATALOG_REVISION_REQUIRED", "Bind subsequent catalog pages to the first page's fingerprint.", outcome_unknown=False)
    requested = arguments.get("tool")
    if requested is not None:
        found = next((row for row in rows if row["name"] == requested), None)
        if found is None:
            raise BridgeError("UNKNOWN_TOOL", "No such tool in this worker's current public catalog.", outcome_unknown=False)
        entries = [{**deepcopy(found), "compatibilityCallable": found["name"] not in DENIED_TARGETS}]
        total, offset, more, full = 1, 0, False, True
    else:
        query = arguments.get("query", "").casefold()
        filtered = [row for row in rows if query in row["name"].casefold()]
        offset, limit, total = arguments.get("offset", 0), arguments.get("limit", 20), len(filtered)
        entries = [{"name": row["name"], "description": row["description"][:320],
                    "descriptionTruncated": len(row["description"]) > 320,
                    "annotations": deepcopy(row["annotations"]),
                    "compatibilityCallable": row["name"] not in DENIED_TARGETS}
                   for row in filtered[offset:offset + limit]]
        more, full = offset + len(entries) < total, False
    result = {"topic": "tools", "source": "current_loaded_worker_catalog", "catalogFingerprint": revision,
              "entries": entries, "total": total, "offset": offset,
              "nextOffset": offset + len(entries) if more else None, "hasMore": more,
              "completeSchemas": full, "gameCalls": 0, "invocation": catalog_hint(configuration)}
    if len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > MAX_CATALOG_REPLY:
        raise BridgeError("TOOL_CATALOG_RESPONSE_TOO_LARGE", "The complete tool schema exceeds the bounded reply budget; it was not truncated.", outcome_unknown=False)
    return result


def resolve_call(configuration, arguments):
    from .tools import TOOL_BY_NAME
    from .validation import validate_tool_arguments
    validate_tool_arguments("mcb_sequence", arguments)
    binding = getattr(configuration, "conversation_binding", None)
    if not binding or binding["instancePath"] is None:
        raise BridgeError("CLIENT_NOT_BOUND", "Bind this conversation before calling a tool.", outcome_unknown=False)
    if arguments["expectedConversationId"] != binding["conversationId"]:
        raise BridgeError("CONVERSATION_MISMATCH", "The call belongs to a different conversation.", outcome_unknown=False)
    if (arguments["expectedBindingId"] != binding["bindingId"]
            or arguments["expectedBindingGeneration"] != binding["generation"]):
        raise BridgeError("BINDING_GENERATION_CHANGED", "The selected client binding changed; no tool was dispatched.", outcome_unknown=False)
    _, revision = current_catalog()
    if arguments["expectedCatalogFingerprint"] != revision:
        raise BridgeError("TOOL_CATALOG_CHANGED", "Tool definitions changed; inspect the current catalog before another call.", outcome_unknown=False)
    call = arguments["call"]
    name = call["tool"]
    if name in DENIED_TARGETS:
        raise BridgeError("RECURSIVE_TOOL_CALL_REFUSED", "Use ordinary sequence steps directly; envelopes cannot call mcb_sequence.", outcome_unknown=False)
    if name not in TOOL_BY_NAME:
        raise BridgeError("UNKNOWN_TOOL", "No such tool in the current worker catalog; management tools must be called directly.", outcome_unknown=False)
    validate_tool_arguments(name, call["args"])
    return name, deepcopy(call["args"]), {"via": "mcb_sequence.call", "tool": name,
        "catalogFingerprint": revision, "bindingId": binding["bindingId"], "conversationId": binding["conversationId"]}


def annotate_result(result, invocation):
    from mcp import types
    value = {**result.structuredContent, "toolInvocation": invocation}
    result.structuredContent = value
    result.content = [types.TextContent(type="text", text=json.dumps(value, ensure_ascii=False, allow_nan=False)),
                      *[part for part in result.content if not isinstance(part, types.TextContent)]]
    return result
