"""Legacy in-process MCP endpoint; business execution lives in application/."""
from __future__ import annotations

import json
import logging
import sys
from typing import Any
import anyio
from mcp import types
from mcp.server.lowlevel import Server
from mcp.server.lowlevel.helper_types import ReadResourceContents
from mcp.server.stdio import stdio_server
from mcp.shared.exceptions import McpError
from .bridge import BridgeClient, BridgeError
from .guides import GUIDE_RESOURCES, read_help
from .catalog_refresh import CatalogServer, ToolCatalogRefresh
from .agent_instructions import AGENT_INSTRUCTIONS
# Keep the existing import surface for local runners and integrations.
# Business modules import application services directly, never the MCP server.
from .application.adapter import Adapter
from .application.control import _require_capability, _full_background, invoke_control
from .application.reads import _json_equal, condition_matches, wait_for, invoke_current_world_read, recipe_pages, machine_capture_pages, _capture_page_valid
from .application.results import _result, screenshot_result, waila_result, health_result
from .application.descriptions import tool_descriptions, resource_descriptions
from .validation import tool_validator, validate_tool_arguments as _validate
from ._version import __version__


def make_server(client: BridgeClient, *, catalog: ToolCatalogRefresh | None = None) -> Server:
    adapter = Adapter(client)
    catalog = catalog or ToolCatalogRefresh()
    server = CatalogServer("minecraft-codex-bridge", version=__version__, lifespan=catalog.lifespan, instructions=AGENT_INSTRUCTIONS)
    server.catalog_workflow_lock = adapter.workflow_lock

    async def refresh_catalog() -> None:
        try:
            catalog.remember_session(server.request_context.session)
        except LookupError:
            pass
        await catalog.refresh(adapter.workflow_lock)

    @server.list_tools()
    async def list_tools() -> list[types.Tool]:
        await refresh_catalog()
        return tool_descriptions()

    @server.call_tool(validate_input=False)
    async def call_tool(name: str, arguments: dict[str, Any]) -> types.CallToolResult:
        await refresh_catalog()
        result = await adapter.call_tool(name, arguments)
        if name == "mcb_health":
            return health_result(result, client, catalog.diagnostics())
        return result

    @server.list_resources()
    async def list_resources() -> list[types.Resource]:
        return resource_descriptions()

    @server.read_resource()
    async def read_resource(uri) -> list[ReadResourceContents]:
        topic = GUIDE_RESOURCES.get(str(uri))
        if topic is None:
            raise McpError(types.ErrorData(code=types.INVALID_PARAMS, message="Unknown agent guide resource; only the listed fixed resources are supported."))
        try:
            data = await anyio.to_thread.run_sync(read_help, {"topic": topic}, abandon_on_cancel=True)
        except BridgeError as error:
            raise McpError(types.ErrorData(code=types.INVALID_PARAMS, message=error.message, data={"code": error.code, "topic": topic})) from None
        return [ReadResourceContents(content=json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")), mime_type="application/json")]

    return server


async def run() -> None:
    client = BridgeClient()
    try:
        server = make_server(client)
        async with stdio_server() as (read, write):
            await server.run(read, write, server.create_initialization_options())
    finally:
        await client.aclose()


def main() -> None:
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr, format="%(levelname)s: %(message)s")
    try:
        anyio.run(run)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
