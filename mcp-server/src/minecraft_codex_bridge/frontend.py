"""The sole public MCP endpoint. Business modules load only inside its child."""
from __future__ import annotations

import json
import logging
import sys
from weakref import WeakKeyDictionary

import anyio
import jsonschema
from mcp import types
from mcp.server.lowlevel import NotificationOptions, Server
from mcp.server.lowlevel.helper_types import ReadResourceContents
from mcp.server.stdio import stdio_server
from mcp.shared.exceptions import McpError

from .backend_supervisor import Supervisor, error_result
from .backend_transport import BackendError
from .backend_connections import CLIENTS_SCHEMA, CONNECTION_SCHEMA
from ._version import __version__

RESTART_SCHEMA = {"type": "object", "properties": {
    "requestId": {"type": "string", "minLength": 1, "maxLength": 128},
    "expectedGeneration": {"type": "integer", "minimum": 0},
    "expectedSourceFingerprint": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
    "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 45000, "default": 30000}},
    "required": ["requestId", "expectedGeneration"], "additionalProperties": False}
STATUS_SCHEMA = {"type": "object", "properties": {}, "additionalProperties": False}


def management_tools():
    return [types.Tool(name="mcb_backend_status", description="Read this MCB connection's frontend/worker PID, generation and restart state. No game call or restart.",
                inputSchema=STATUS_SCHEMA, annotations=types.ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)),
            types.Tool(name="mcb_backend_restart", description="Reload Python business code and its tool/resource catalog inside this single MCB service while keeping the MCP connection and current conversation alive. Requires no active worker call and a complete verified state handoff. Preserves all AE order phases and unresolved action records; game failure, pending orders and offline game state do not block reload. No game RPC during reload, no automatic resume or replay, and no Codex or Minecraft restart. Tool additions, removals and schema changes publish only after activation and emit MCP list_changed; client rediscovery depends on host support. Subsequent game actions retain their guards. Stable frontend, initialization instructions and dependency changes require MCP reconnection. Reuse requestId only to retrieve the same restart result; after a refusal use a new ID for a new attempt.",
                inputSchema=RESTART_SCHEMA, annotations=types.ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False)),
            types.Tool(name="mcb_clients", description="List Minecraft clients without binding or controlling them. Supply prismRoot to list only instances in that explicit Prism data directory; results include instanceName (folder ID), displayName and clientId. Without prismRoot, list configured/discovered inventory. Profiles and launch defaults never select a client for this conversation. runtimeFilePresent is not online proof; binding checks authenticated live health.",
                inputSchema=CLIENTS_SCHEMA, annotations=types.ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)),
            types.Tool(name="mcb_connection", description="Read this conversation's actual binding (action=status, default), bind using separate prismRoot and instanceName fields, or unbind. prismRoot is the Prism data directory; instanceName is its instance display name or folder name. Missing/ambiguous names never select another client. Alternatively use a clientId from mcb_clients; do not mix selectors. New MCP frontends start unbound. bind/unbind require a fresh requestId, expectedBindingGeneration, and this chat's actual ID as expectedConversationId (obtain CODEX_THREAD_ID from the current chat execution environment). Never invent an ID or copy another chat's owner. If MCP startup omits CODEX_THREAD_ID, the first successful bind declares and pins this separate stdio connection's owner; later mismatches are refused even after unbind. Binding authenticates live health before replacing the old worker. Pending orders, uncertain actions and an owned dialogue relay block switching; reconcile them on the original client first. Client switches get fresh workflow/cache state with no replay or inherited orders. Same-instance restarts rotate credentials; backend reload preserves binding; frontend reconnection starts unbound. Does not edit shared config or launch/restart Minecraft or Codex.",
                inputSchema=CONNECTION_SCHEMA, annotations=types.ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False))]


def result(value):
    return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(value, ensure_ascii=False))],
                                structuredContent=value, isError=value.get("ok") is False)


class CatalogServer(Server):
    def __init__(self, supervisor):
        super().__init__("minecraft-codex-bridge", version=__version__, instructions=(supervisor.contract or {}).get("instructions"))
        self.supervisor = supervisor
        self.catalog_sessions = WeakKeyDictionary()
        self.notify_gate = anyio.Lock()
        self.notifications_sent = 0
        self.notifications_failed = 0

    def create_initialization_options(self, notification_options=None, experimental_capabilities=None):
        return super().create_initialization_options(
            notification_options=NotificationOptions(tools_changed=True, resources_changed=True),
            experimental_capabilities=experimental_capabilities)

    def remember_session(self, listed=None):
        session = self.request_context.session
        state = self.catalog_sessions.setdefault(session, {
            "delivered": dict(self.supervisor.catalog_generations), "listed": {"tools": None, "resources": None}})
        if listed is not None:
            generation = self.supervisor.catalog_generations[listed]
            state["delivered"][listed] = generation
            state["listed"][listed] = generation

    def current_tools(self):
        return [types.Tool.model_validate(tool) for tool in (self.supervisor.contract or {}).get("tools", [])] + management_tools()

    async def _get_cached_tool_definition(self, tool_name):
        # The SDK normally keeps existing definitions until tools/list. Calls must use
        # the committed catalog even while the client is still rediscovering it.
        return next((tool for tool in self.current_tools() if tool.name == tool_name), None)

    async def notify_catalog(self):
        async with self.notify_gate:
            for session, state in list(self.catalog_sessions.items()):
                for kind in ("tools", "resources"):
                    generation = self.supervisor.catalog_generations[kind]
                    if state["delivered"][kind] >= generation:
                        continue
                    try:
                        with anyio.fail_after(2):
                            if kind == "tools":
                                await session.send_tool_list_changed()
                            else:
                                await session.send_resource_list_changed()
                        state["delivered"][kind] = max(state["delivered"][kind], generation)
                        self.notifications_sent += 1
                    except Exception:
                        # Publication already committed. Retry notification on a later
                        # status/restart request; never roll back or repeat the restart.
                        self.notifications_failed += 1

    def catalog_status(self):
        state = self.catalog_sessions.get(self.request_context.session)
        return {"notificationsSent": self.notifications_sent, "notificationsFailed": self.notifications_failed,
                "pending": any(state["delivered"][kind] < generation for kind, generation in self.supervisor.catalog_generations.items()) if state else False,
                "lastListedGenerations": dict(state["listed"]) if state else None,
                "clientCatalogRefresh": "not_observable"}


def make_frontend(supervisor):
    server = CatalogServer(supervisor)

    @server.list_tools()
    async def list_tools():
        server.remember_session("tools")
        return server.current_tools()

    @server.call_tool(validate_input=False)
    async def call_tool(name, arguments):
        server.remember_session()
        try:
            schemas = {"mcb_backend_status": STATUS_SCHEMA, "mcb_backend_restart": RESTART_SCHEMA,
                       "mcb_clients": CLIENTS_SCHEMA, "mcb_connection": CONNECTION_SCHEMA}
            if name in schemas:
                try:
                    jsonschema.Draft202012Validator(schemas[name]).validate(arguments)
                except jsonschema.ValidationError:
                    return result(error_result("INVALID_ARGUMENTS"))
                if name == "mcb_backend_status":
                    await server.notify_catalog()
                    return result({"ok": True, "data": {**supervisor.status(), "catalogNotification": server.catalog_status()}})
                if name == "mcb_clients":
                    return result({"ok": True, "data": await supervisor.call("list_clients", arguments)})
                if name == "mcb_connection":
                    if arguments.get("action", "status") == "status":
                        return result({"ok": True, "data": supervisor.connection_status()})
                    return result(await supervisor.change_connection(arguments))
                reply = await supervisor.restart(arguments)
                await server.notify_catalog()
                return result(reply)
            reply = await supervisor.call("call_tool", {"name": name, "arguments": arguments})
            return types.CallToolResult.model_validate(reply)
        except BackendError as error:
            return result(error_result(error.code, unknown=error.outcome_unknown))

    @server.list_resources()
    async def list_resources():
        server.remember_session("resources")
        return [types.Resource.model_validate(resource) for resource in (supervisor.contract or {}).get("resources", [])]

    @server.read_resource()
    async def read_resource(uri):
        server.remember_session()
        if str(uri) not in {entry["uri"] for entry in (supervisor.contract or {}).get("resources", [])}:
            raise McpError(types.ErrorData(code=types.INVALID_PARAMS, message="Unknown agent guide resource; only the listed fixed resources are supported."))
        data = await supervisor.call("read_resource", {"uri": str(uri)})
        return [ReadResourceContents(content=json.dumps(data, ensure_ascii=False), mime_type="application/json")]

    return server


async def run():
    supervisor = Supervisor()
    try:
        await supervisor.start()
        server = make_frontend(supervisor)
        async with stdio_server() as (read, write):
            await server.run(read, write, server.create_initialization_options())
    finally:
        await supervisor.close()


def main():
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr, format="%(levelname)s: %(message)s")
    try:
        anyio.run(run)
    except KeyboardInterrupt:
        pass
