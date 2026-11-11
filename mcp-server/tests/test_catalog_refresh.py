"""The same SDK connection receives a real list-change notification and new validation."""
from copy import deepcopy
from dataclasses import replace
import os
from pathlib import Path

import anyio
import pytest
from mcp import types
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.catalog_refresh import ToolCatalogRefresh, declaration_source, MAX_BACKEND_FILES
import minecraft_codex_bridge.catalog_refresh as catalog_module
from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import TOOLS, TOOL_BY_NAME
from minecraft_codex_bridge.validation import tool_validator
from test_workflows import bridge, operations


@pytest.fixture
def catalog_files(tmp_path):
    complete = declaration_source()
    source = tmp_path / "tools.py"
    source.write_bytes(complete)
    backend = tmp_path / "backend.py"
    backend.write_text("backend = 1\n", encoding="utf-8")
    original = list(TOOLS)
    try:
        yield source, backend, complete
    finally:
        TOOLS[:] = original
        TOOL_BY_NAME.clear()
        TOOL_BY_NAME.update({tool.name: tool for tool in original})
        tool_validator.cache_clear()


def old_catalog(source, complete):
    marker = b'"preview_candidate_visuals", "item_visuals", "preview_textures"'
    assert complete.count(marker) == 1
    source.write_bytes(complete.replace(marker, b'"preview_candidate_visuals", "preview_textures"'))
    original = TOOL_BY_NAME["mcb_nei_view"]
    schema = deepcopy(original.schema)
    schema["properties"]["target"]["enum"].remove("item_visuals")
    previous = replace(original, schema=schema)
    TOOLS[TOOLS.index(original)] = previous
    TOOL_BY_NAME[original.name] = previous
    tool_validator.cache_clear()


@pytest.mark.parametrize("trigger", ["watch", "health", "list"])
async def test_existing_sdk_session_not_new_process_updates_catalog_and_validator(bridge, catalog_files, trigger):
    adapter, state = bridge
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend], poll_interval=0.02 if trigger == "watch" else 10)
    server = make_server(adapter.client, catalog=catalog)
    changed = anyio.Event()
    notifications = []
    async def observe(message):
        if isinstance(message, types.ServerNotification) and isinstance(message.root, types.ToolListChangedNotification):
            notifications.append(message.root.method)
            changed.set()
    args = {"screenId": "screen", "section": "detail", "target": "item_visuals", "recipeIndex": 0}
    async with create_connected_server_and_client_session(server, message_handler=observe) as session:
        assert server.create_initialization_options().capabilities.tools.listChanged is True
        before = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert set(before) == set(TOOL_BY_NAME)
        assert "item_visuals" not in before["mcb_nei_view"].inputSchema["properties"]["target"]["enum"]
        refused = await session.call_tool("mcb_nei_view", args)
        assert refused.isError and refused.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
        assert "nei.view" not in operations(state)
        fingerprint = catalog.fingerprint
        source.write_bytes(complete)
        if trigger == "health":
            health = await session.call_tool("mcb_health", {})
            identity = health.structuredContent["data"]["mcpAdapter"]
            assert identity["pid"] == os.getpid() and identity["sourceStatus"] == "current"
        elif trigger == "list":
            await session.list_tools()
        with anyio.fail_after(5):
            await changed.wait()
        assert catalog.generation == 1 and catalog.fingerprint != fingerprint
        after = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert set(after) == set(before)
        assert "item_visuals" in after["mcb_nei_view"].inputSchema["properties"]["target"]["enum"]
        accepted = await session.call_tool("mcb_nei_view", args)
        assert not accepted.isError and operations(state).count("nei.view") == 1
        health = await session.call_tool("mcb_health", {})
        identity = health.structuredContent["data"]["mcpAdapter"]
        assert identity["pid"] == os.getpid() and identity["catalogGeneration"] == 1
        assert identity["schemaFingerprint"] == catalog.fingerprint
        assert identity["notificationsSent"] == 1
        assert identity["clientCatalogRefresh"] == "not_observable"
        assert notifications == ["notifications/tools/list_changed"]
        assert state["background"]["enabled"] is False


@pytest.mark.parametrize("change,reason", [
    (lambda data: data.replace(b'ToolSpec("mcb_gui_pointer", "gui.pointer"', b'ToolSpec("mcb_gui_pointer", "gui.key"'), "tool_dispatch_or_permissions_changed"),
    (lambda data: data.replace(b'"gui.close", "gui.pointer"', b'"gui.close"'), "tool_dispatch_or_permissions_changed"),
    (lambda data: data + b'\nTOOLS.append(ToolSpec("foreign", "gui.key", "unapproved", obj()))\n', "tool_topology_changed"),
    (lambda data: data.replace(b'"maximum": high}', b'"maximum": high + 1}'), "schema_builder_code_changed"),
    (lambda data: data + b'\nimport os\n', "schema_builder_code_changed"),
    (lambda data: data + b'\nprint("unexpected stdout")\n', "schema_builder_code_changed"),
    (lambda data: data + b'\ninteger = dataclass\n', "schema_builder_code_changed"),
    (lambda data: data + b'\ninvalid syntax =', "schema_source_unavailable_or_invalid"),
    (lambda data: b'x' * 262145, "source_size"),
])
async def test_dispatch_permissions_code_and_invalid_updates_leave_catalog_untouched(catalog_files, change, reason):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    previous = list(TOOLS)
    fingerprint = catalog.fingerprint
    following = change(complete)
    assert following != complete
    source.write_bytes(following)
    assert not await catalog.refresh()
    assert TOOLS == previous and TOOL_BY_NAME == {tool.name: tool for tool in previous}
    assert catalog.fingerprint == fingerprint and catalog.generation == 0
    assert catalog.source_status == "restart_required" and catalog.reason == reason
    assert catalog.notifications_sent == 0


async def test_backend_code_change_requires_restart_even_when_new_schema_is_valid(catalog_files):
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    backend.write_text("backend = 2\n", encoding="utf-8")
    source.write_bytes(complete)
    assert not await catalog.refresh()
    assert catalog.source_status == "restart_required" and catalog.reason == "backend_code_changed"
    assert "item_visuals" not in TOOL_BY_NAME["mcb_nei_view"].schema["properties"]["target"]["enum"]
    # Reverting a file cannot make a running process's old imported backend verified.
    backend.write_text("backend = 1\n", encoding="utf-8")
    assert not await catalog.refresh()
    assert catalog.reason == "backend_code_changed" and catalog.source_status == "restart_required"


async def test_comment_only_update_emits_no_notification_and_keeps_fingerprint(catalog_files):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    fingerprint = catalog.fingerprint
    source.write_bytes(complete + b'\n# unchanged schemas and descriptions\n')
    assert await catalog.refresh()
    assert catalog.fingerprint == fingerprint and catalog.generation == 0 and catalog.notifications_sent == 0


async def test_registered_current_recipient_reports_notification_not_needed(catalog_files):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    class Current:
        async def send_tool_list_changed(self):
            pytest.fail("An unchanged schema must not emit a catalog notification.")
    recipient = Current()
    catalog.remember_session(recipient)
    source.write_bytes(complete + b'\n# unchanged declaration\n')
    assert await catalog.refresh()
    assert catalog.notification_status == "not_needed"


async def test_schema_publication_waits_for_single_existing_action_executor(catalog_files):
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    lock = anyio.Lock()
    finished = anyio.Event()
    async def update():
        assert await catalog.refresh(lock)
        finished.set()
    async with lock:
        source.write_bytes(complete)
        async with anyio.create_task_group() as group:
            group.start_soon(update)
            await anyio.sleep(0.05)
            assert not finished.is_set()
            assert "item_visuals" not in TOOL_BY_NAME["mcb_nei_view"].schema["properties"]["target"]["enum"]
            lock.release()
            with anyio.fail_after(5):
                await finished.wait()
            await lock.acquire()


async def test_closed_notification_recipient_never_becomes_verified_client_refresh(catalog_files):
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    class Closed:
        async def send_tool_list_changed(self):
            raise anyio.ClosedResourceError
    recipient = Closed()
    catalog.remember_session(recipient)
    source.write_bytes(complete)
    assert await catalog.refresh()
    assert catalog.notification_status == "failed" and catalog.notifications_sent == 0
    assert catalog.diagnostics()["clientCatalogRefresh"] == "not_observable"


async def test_schema_refresh_uses_static_declarations_without_executing_a_module(catalog_files, monkeypatch):
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    def forbidden(*args, **kwargs):
        pytest.fail("Schema refresh must not execute or evaluate changed Python source.")
    monkeypatch.setattr(catalog_module, "exec", forbidden, raising=False)
    monkeypatch.setattr(catalog_module, "eval", forbidden, raising=False)
    monkeypatch.setattr(catalog_module, "compile", forbidden, raising=False)
    source.write_bytes(complete)
    assert await catalog.refresh()
    assert "item_visuals" in TOOL_BY_NAME["mcb_nei_view"].schema["properties"]["target"]["enum"]


@pytest.mark.parametrize("suffix", [
    b'\nNEI_DETAIL_BRANCHES.append(integer.__globals__)\n',
    b'\nNEI_DETAIL_BRANCHES.append(TOOLS.__class__)\n',
    b'\nwhile True:\n    pass\n',
])
async def test_unknown_syntax_or_object_introspection_never_executes_or_publishes(catalog_files, suffix):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    previous = list(TOOLS)
    source.write_bytes(complete + suffix)
    assert not await catalog.refresh()
    assert catalog.reason == "schema_declaration_expression" and catalog.source_status == "restart_required"
    assert TOOLS == previous and catalog.generation == 0


@pytest.mark.parametrize("suffix", [
    b'\nextra = ' + b'[' * 80 + b'0' + b']' * 80 + b'\n',
    # Unary nodes exceed the AST budget inside the separate source-byte budget.
    b'\nextra = [' + b'-0,' * 11000 + b']\n',
    b'\nextra = [' + b'0,' * 8193 + b']\n',
], ids=["depth", "nodes", "collection_entries"])
async def test_depth_nodes_and_collection_budgets_leave_old_catalog_active(catalog_files, suffix):
    source, backend, _ = catalog_files
    # Isolate each parser budget from the growing production tool descriptions.
    # Refresh must retain the actual current catalog without interpreting this
    # deliberately incomplete replacement once its declaration budget is hit.
    complete = b'TOOLS = []\n'
    source.write_bytes(complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    previous = list(TOOLS)
    assert len(complete + suffix) < catalog_module.MAX_SOURCE_BYTES
    source.write_bytes(complete + suffix)
    assert not await catalog.refresh()
    assert catalog.reason == "schema_declaration_budget" and catalog.source_status == "restart_required"
    assert TOOLS == previous and catalog.generation == 0


async def test_declaration_step_and_serialized_catalog_budgets_refuse_without_publication(catalog_files, monkeypatch):
    source, backend, complete = catalog_files
    old_catalog(source, complete)
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    source.write_bytes(complete)
    with monkeypatch.context() as scoped:
        scoped.setattr(catalog_module, "MAX_DECLARATION_STEPS", 100)
        assert not await catalog.refresh() and catalog.reason == "schema_declaration_budget"
    assert "item_visuals" not in TOOL_BY_NAME["mcb_nei_view"].schema["properties"]["target"]["enum"]
    source.write_bytes(complete + b'\n# another fixed-source change\n')
    with monkeypatch.context() as scoped:
        scoped.setattr(catalog_module, "MAX_CATALOG_BYTES", 100)
        assert not await catalog.refresh() and catalog.reason == "catalog_size"
    assert catalog.generation == 0


def test_backend_file_count_per_file_and_total_bytes_are_bounded_before_loading(catalog_files, monkeypatch):
    source, backend, complete = catalog_files
    with pytest.raises(ValueError, match="schema_declaration_budget"):
        ToolCatalogRefresh(source_path=source, backend_paths=[backend] * (MAX_BACKEND_FILES + 1))
    with monkeypatch.context() as scoped:
        scoped.setattr(catalog_module, "MAX_TOTAL_BACKEND_BYTES", 1)
        with pytest.raises(ValueError, match="schema_declaration_budget"):
            ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    backend.write_bytes(b'#' * (catalog_module.MAX_BACKEND_BYTES + 1))
    with pytest.raises(ValueError, match="schema_declaration_budget"):
        ToolCatalogRefresh(source_path=source, backend_paths=[backend])


@pytest.mark.parametrize("change", ["deleted", "oversized"])
async def test_missing_or_oversized_backend_file_requires_restart_without_unbounded_read(catalog_files, change):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    if change == "deleted":
        backend.unlink()
    else:
        backend.write_bytes(b'#' * (catalog_module.MAX_BACKEND_BYTES + 1))
    assert not await catalog.refresh()
    assert catalog.reason == "backend_code_changed" and catalog.source_status == "restart_required"


async def test_loaded_builder_global_change_requires_restart_instead_of_mixed_schema(catalog_files):
    source, backend, complete = catalog_files
    catalog = ToolCatalogRefresh(source_path=source, backend_paths=[backend])
    changed = complete.replace(b'SCREEN_ID = {"type": "string", "minLength": 1, "maxLength": 128}',
        b'SCREEN_ID = {"type": "string", "minLength": 1, "maxLength": 129}')
    assert changed != complete
    source.write_bytes(changed)
    previous = list(TOOLS)
    assert not await catalog.refresh()
    assert catalog.reason == "schema_builder_code_changed" and TOOLS == previous
