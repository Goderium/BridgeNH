"""Public MCP tool and resource descriptions from the canonical catalog."""
from __future__ import annotations

from mcp import types
from ..tools import TOOLS
from ..guides import GUIDE_RESOURCES


def tool_descriptions() -> list[types.Tool]:
    return [types.Tool(
        name=tool.name, description=tool.description + (" MCP automatically ensures full background takeover before this control operation; a failed takeover blocks the operation." if tool.takeover and tool.name != "mcb_gui_pointer" else ""), inputSchema=tool.schema,
        annotations=types.ToolAnnotations(readOnlyHint=tool.read_only, destructiveHint=tool.destructive, idempotentHint=tool.read_only if tool.idempotent is None else tool.idempotent, openWorldHint=False),
    ) for tool in TOOLS]


def resource_descriptions() -> list[types.Resource]:
    return [types.Resource(
        uri=uri, name=f"Minecraft 1.7.10 GTNH agent guide: {topic}",
        description="Fixed local guide with Markdown, source and version applicability. Also available through mcb_help.",
        mimeType="application/json",
    ) for uri, topic in GUIDE_RESOURCES.items()]
