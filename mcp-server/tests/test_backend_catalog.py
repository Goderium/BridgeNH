"""Public catalog changes travel through real worker swaps on one MCP session."""
from copy import deepcopy
import json

import pytest
from mcp import types
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.backend_catalog import catalog_change, validate_contract
from minecraft_codex_bridge.backend_supervisor import Supervisor
from minecraft_codex_bridge.backend_transport import BackendError
from minecraft_codex_bridge.frontend import make_frontend
from test_backend_supervisor import copy_package, isolated_environment, supervisor


async def test_added_removed_and_changed_tools_rediscovered_on_same_connection(tmp_path):
    root = copy_package(tmp_path)
    native = root / "catalog" / "native.py"
    complete = native.read_text(encoding="utf-8")
    native.write_text(complete + '\nNATIVE_TOOLS[:] = [t for t in NATIVE_TOOLS if t.name not in {"mcb_window", "mcb_window_action"}]\n', encoding="utf-8")
    instance = Supervisor(root, environment=isolated_environment(tmp_path), snapshot_directory=tmp_path)
    await instance.start()
    assert instance.state == "running", instance.status()
    server = make_frontend(instance)
    notifications = []
    async def observe(message):
        if isinstance(message, types.ServerNotification):
            notifications.append(message.root.method)
    try:
        async with create_connected_server_and_client_session(server, message_handler=observe) as session:
            initialized = await session.initialize()
            assert initialized.capabilities.tools.listChanged
            assert initialized.capabilities.resources.listChanged
            before = await session.list_tools()
            assert "mcb_window" not in {tool.name for tool in before.tools}
            original = instance.status()
            native.write_text(complete, encoding="utf-8")
            args = {"requestId": "add-window", "expectedGeneration": 1}
            restarted = await session.call_tool("mcb_backend_restart", args)
            data = restarted.structuredContent["data"]
            assert not restarted.isError and not data["catalogReconnectRequired"]
            assert data["catalogUpdate"]["tools"]["added"] == ["mcb_window", "mcb_window_action"]
            assert data["frontendPid"] == original["frontendPid"] and data["workerPid"] != original["workerPid"]
            # Calling a new tool before tools/list reaches its new worker validator.
            refused = await session.call_tool("mcb_window_action", {})
            assert refused.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
            after = await session.list_tools()
            assert {"mcb_window", "mcb_window_action"} <= {tool.name for tool in after.tools}
            duplicate = await session.call_tool("mcb_backend_restart", args)
            assert duplicate.structuredContent == restarted.structuredContent
            assert instance.generation == 2

            native.write_text(complete + '\nNATIVE_TOOLS[:] = [t for t in NATIVE_TOOLS if t.name != "mcb_window_action"]\n', encoding="utf-8")
            gui = root / "catalog" / "gui.py"
            with gui.open("a", encoding="utf-8") as stream:
                stream.write('\nfrom dataclasses import replace as _fixture_replace\nT_MCB_WINDOW = _fixture_replace(T_MCB_WINDOW, description="Updated window schema", schema=obj({"probe": {"type": "boolean"}}, ["probe"]))\n')
            second = await session.call_tool("mcb_backend_restart", {"requestId": "remove-and-edit", "expectedGeneration": 2})
            change = second.structuredContent["data"]["catalogUpdate"]["tools"]
            assert change["removed"] == ["mcb_window_action"]
            assert set(change["updated"]) == {"mcb_window", "mcb_sequence"}
            # No intervening tools/list: stale SDK cache must not retain the old schema.
            assert (await server._get_cached_tool_definition("mcb_window")).inputSchema["required"] == ["probe"]
            assert await server._get_cached_tool_definition("mcb_window_action") is None
            refused = await session.call_tool("mcb_window", {})
            assert refused.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
            removed = await session.call_tool("mcb_window_action", {})
            assert removed.structuredContent["error"]["code"] == "UNKNOWN_TOOL"
            final = {tool.name: tool for tool in (await session.list_tools()).tools}
            assert "mcb_window_action" not in final
            assert final["mcb_window"].description == "Updated window schema"
            status = (await session.call_tool("mcb_backend_status", {})).structuredContent["data"]
            assert status["catalogGenerations"] == {"tools": 3, "resources": 1}
            assert status["catalogNotification"]["lastListedGenerations"]["tools"] == 3
            assert status["catalogNotification"]["clientCatalogRefresh"] == "not_observable"
            assert notifications == ["notifications/tools/list_changed"] * 2
    finally:
        await instance.close()


async def test_resource_changes_notify_only_resource_catalog(supervisor):
    notifications = []
    async def observe(message):
        if isinstance(message, types.ServerNotification):
            notifications.append(message.root.method)
    async with create_connected_server_and_client_session(make_frontend(supervisor), message_handler=observe) as session:
        before = await session.list_resources()
        path = supervisor.root / "application" / "descriptions.py"
        path.write_text(path.read_text(encoding="utf-8").replace('description="Fixed local guide', 'description="Updated local guide'), encoding="utf-8")
        reply = await session.call_tool("mcb_backend_restart", {"requestId": "resources", "expectedGeneration": 1})
        assert not reply.isError
        assert not reply.structuredContent["data"]["catalogUpdate"]["tools"]["changed"]
        after = await session.list_resources()
        assert after.resources[0].description != before.resources[0].description
        guide = await session.read_resource(after.resources[0].uri)
        assert json.loads(guide.contents[0].text)
        assert notifications == ["notifications/resources/list_changed"]


async def test_notification_failure_retries_without_restarting_or_claiming_client_refresh(supervisor, monkeypatch):
    server = make_frontend(supervisor)
    async with create_connected_server_and_client_session(server) as session:
        await session.list_tools()
        connection = next(iter(server.catalog_sessions))
        send = connection.send_tool_list_changed
        async def unavailable():
            raise RuntimeError("fixture transport interruption")
        monkeypatch.setattr(connection, "send_tool_list_changed", unavailable)
        path = supervisor.root / "application" / "descriptions.py"
        path.write_text(path.read_text(encoding="utf-8").replace("description=tool.description +", 'description="updated " + tool.description +'), encoding="utf-8")
        args = {"requestId": "notify-failed", "expectedGeneration": 1}
        reply = await session.call_tool("mcb_backend_restart", args)
        assert not reply.isError and supervisor.generation == 2
        assert server.notifications_failed == 1 and server.notifications_sent == 0
        monkeypatch.setattr(connection, "send_tool_list_changed", send)
        status = (await session.call_tool("mcb_backend_status", {})).structuredContent["data"]["catalogNotification"]
        assert status["notificationsSent"] == 1 and not status["pending"]
        assert status["lastListedGenerations"]["tools"] == 1
        duplicate = await session.call_tool("mcb_backend_restart", args)
        assert duplicate.structuredContent == reply.structuredContent
        assert supervisor.generation == 2 and server.notifications_sent == 1


async def test_state_handoff_failure_does_not_publish_catalog(supervisor, monkeypatch):
    previous = deepcopy(supervisor.contract)
    original = supervisor.candidate
    async def candidate():
        child = await original()
        rpc = child.rpc
        async def refuse_state(method, params=None):
            if method == "restore_state":
                raise BackendError("BACKEND_STATE_INVALID")
            return await rpc(method, params)
        child.rpc = refuse_state
        return child
    monkeypatch.setattr(supervisor, "candidate", candidate)
    server = make_frontend(supervisor)
    async with create_connected_server_and_client_session(server) as session:
        before = await session.list_tools()
        path = supervisor.root / "application" / "descriptions.py"
        path.write_text(path.read_text(encoding="utf-8").replace("description=tool.description +", 'description="updated " + tool.description +'), encoding="utf-8")
        reply = await session.call_tool("mcb_backend_restart", {"requestId": "handoff", "expectedGeneration": 1})
        assert reply.structuredContent["error"]["code"] == "BACKEND_STATE_INVALID"
        assert supervisor.contract == previous and supervisor.generation == 1
        assert (await session.list_tools()).tools == before.tools
        assert server.notifications_sent == 0


@pytest.mark.parametrize("kind", ["schema", "reserved", "duplicate", "resource", "bounds"])
def test_invalid_contract_is_rejected(kind):
    contract = {"tools": [{"name": "fixture", "inputSchema": {"type": "object"}}], "resources": [], "instructions": "fixture"}
    if kind == "schema":
        contract["tools"][0]["inputSchema"] = {"type": "invalid"}
    elif kind == "reserved":
        contract["tools"][0]["name"] = "mcb_backend_restart"
    elif kind == "duplicate":
        contract["tools"] *= 2
    elif kind == "resource":
        contract["resources"] = [{"name": "invalid", "uri": "not a URI"}]
    else:
        contract["instructions"] = "x" * (2 * 1024 * 1024)
    with pytest.raises(BackendError):
        validate_contract(contract)


def test_recovery_without_previous_catalog_does_not_invent_a_fingerprint():
    change = catalog_change(None, {"tools": [], "resources": [], "instructions": "fixture"})
    assert change["previousFingerprint"] is None
    assert len(change["fingerprint"]) == 64
