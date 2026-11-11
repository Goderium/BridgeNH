"""Actual MCP initialize/list/call over the SDK's public memory transport."""
from contextlib import asynccontextmanager
from datetime import timedelta

from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import make_server
import test_stdio as stdio_suite
from test_stdio import http_bridge


@asynccontextmanager
async def memory_session_for(url, tmp_path, extra_env=None, *, state=None):
    client = BridgeClient(Configuration({"MCB_URL": url, "MCB_TOKEN": stdio_suite.TOKEN, **(extra_env or {})}))
    try:
        async with create_connected_server_and_client_session(
            make_server(client), read_timeout_seconds=timedelta(seconds=10), raise_exceptions=True,
        ) as session:
            yield session
    finally:
        await client.aclose()


async def test_memory_sdk_lists_current_catalog_and_calls_every_declared_example(http_bridge, tmp_path, monkeypatch):
    monkeypatch.setattr(stdio_suite, "session_for", memory_session_for)
    await stdio_suite.test_actual_mcp_initialize_list_and_call_all_tools(http_bridge, tmp_path, supervised=False)
