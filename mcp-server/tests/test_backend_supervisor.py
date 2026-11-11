"""Real worker processes and MCP sessions, isolated from the installed game/MCP."""
from copy import deepcopy
from datetime import timedelta
import json
import os
from pathlib import Path
import sys

import anyio
import httpx
import pytest
from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge import backend_worker
from minecraft_codex_bridge.backend_supervisor import Supervisor, source_files
from minecraft_codex_bridge.backend_transport import BackendError, decode
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.frontend import make_frontend
from test_ae_goals import START

PACKAGE = Path(backend_worker.__file__).parent


def isolated_environment(tmp_path):
    return {**os.environ, "MCB_RUNTIME_FILE": str(tmp_path / "no-game" / "runtime.json"),
            "MCB_KNOWLEDGE_MODE": "off", "MCB_KNOWLEDGE_PATH": str(tmp_path / "knowledge.sqlite3")}


def copy_package(tmp_path):
    root = tmp_path / "src" / "minecraft_codex_bridge"
    for name, data in source_files(PACKAGE).items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return root


def revision(root, value):
    path = root / "application" / "results.py"
    source = path.read_text(encoding="utf-8")
    marker = '    content: list = ['
    source = source.replace(marker, f'    data = {{**data, "testRevision": "{value}"}}\n' + marker, 1)
    path.write_text(source, encoding="utf-8")


@pytest.fixture
async def supervisor(tmp_path):
    root = copy_package(tmp_path)
    revision(root, "first")
    instance = Supervisor(root, environment=isolated_environment(tmp_path), snapshot_directory=tmp_path)
    await instance.start()
    assert instance.state == "running", instance.status()
    try:
        yield instance
    finally:
        await instance.close()


async def unknown(supervisor):
    return await supervisor.call("call_tool", {"name": "fixture_unknown", "arguments": {}})


@pytest.mark.skipif(os.name != "nt", reason="Windows short-path aliases")
async def test_worker_start_and_reload_accept_windows_short_snapshot_path(tmp_path):
    import ctypes

    directory = tmp_path / "worker snapshots with a long directory name"
    directory.mkdir()
    get_short_path = ctypes.WinDLL("kernel32", use_last_error=True).GetShortPathNameW
    get_short_path.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint]
    get_short_path.restype = ctypes.c_uint
    length = get_short_path(str(directory), None, 0)
    assert length, ctypes.get_last_error()
    buffer = ctypes.create_unicode_buffer(length)
    assert get_short_path(str(directory), buffer, length), ctypes.get_last_error()
    alias = Path(buffer.value)
    if alias == directory.resolve():
        pytest.skip("The test volume does not provide an 8.3 directory alias")
    assert alias.resolve() == directory.resolve()

    supervisor = Supervisor(environment=isolated_environment(tmp_path), snapshot_directory=alias)
    try:
        await supervisor.start()
        assert supervisor.state == "running", supervisor.status()
        first_pid = supervisor.status()["workerPid"]
        assert (await unknown(supervisor))["structuredContent"]["error"]["code"] == "UNKNOWN_TOOL"
        restarted = await supervisor.restart({"requestId": "short-path-reload", "expectedGeneration": 1})
        assert restarted["ok"], restarted
        assert supervisor.generation == 2 and supervisor.status()["workerPid"] != first_pid
        assert (await unknown(supervisor))["structuredContent"]["error"]["code"] == "UNKNOWN_TOOL"
    finally:
        await supervisor.close()


async def test_two_reloads_same_mcp_session_and_frontend_new_code_new_worker(supervisor):
    original = supervisor.status()
    pids = {original["workerPid"]}
    async with create_connected_server_and_client_session(make_frontend(supervisor), raise_exceptions=True) as session:
        initialized = await session.initialize()
        tools = await session.list_tools()
        assert initialized.serverInfo.name == "minecraft-codex-bridge"
        assert {"mcb_backend_restart", "mcb_backend_status", "mcb_ae_order"} <= {t.name for t in tools.tools}
        first = await session.call_tool("fixture_unknown", {})
        assert first.isError and first.structuredContent["testRevision"] == "first"
        for number, tag in enumerate(("second", "third"), 2):
            revision(supervisor.root, tag)
            args = {"requestId": tag, "expectedGeneration": number - 1}
            restarted = await session.call_tool("mcb_backend_restart", args)
            assert not restarted.isError, restarted
            data = restarted.structuredContent["data"]
            assert data["frontendPid"] == original["frontendPid"]
            assert data["generation"] == number and data["workerPid"] not in pids
            assert data["gameReadiness"] == "not_observed" and data["connectionPreserved"]
            pids.add(data["workerPid"])
            reply = await session.call_tool("fixture_unknown", {})
            assert reply.structuredContent["testRevision"] == tag
            duplicate = await session.call_tool("mcb_backend_restart", args)
            assert duplicate.structuredContent == restarted.structuredContent
            assert supervisor.generation == number
        resources = await session.list_resources()
        guide = await session.read_resource(resources.resources[0].uri)
        assert guide.contents and json.loads(guide.contents[0].text)
        assert (await session.list_tools()).tools == tools.tools


@pytest.mark.parametrize("kind,code", [("syntax", "BACKEND_INVALID_FRAME"),
    ("catalog", "BACKEND_CATALOG_INVALID"), ("instructions", "BACKEND_INSTRUCTIONS_RECONNECT_REQUIRED"),
    ("frontend", "BACKEND_FRONTEND_RECONNECT_REQUIRED"), ("version", "BACKEND_FRONTEND_RECONNECT_REQUIRED"),
    ("prism", "BACKEND_FRONTEND_RECONNECT_REQUIRED"),
    ("fingerprint", "BACKEND_SOURCE_FINGERPRINT_CHANGED")])
async def test_failed_candidate_preserves_original_worker_and_code(supervisor, kind, code):
    before = supervisor.status()
    args = {"requestId": kind, "expectedGeneration": 1}
    if kind == "syntax":
        with (supervisor.root / "application" / "adapter.py").open("a", encoding="utf-8") as stream:
            stream.write("\nTHIS IS INVALID PYTHON !\n")
    elif kind == "catalog":
        path = supervisor.root / "application" / "descriptions.py"
        path.write_text(path.read_text(encoding="utf-8").replace("inputSchema=tool.schema", 'inputSchema={"type": "invalid-type"}'), encoding="utf-8")
    elif kind == "instructions":
        with (supervisor.root / "agent_instructions.py").open("a", encoding="utf-8") as stream:
            stream.write('\nAGENT_INSTRUCTIONS += " changed initialization instructions"\n')
    elif kind in {"frontend", "version", "prism"}:
        with (supervisor.root / {"frontend": "frontend.py", "version": "_version.py", "prism": "prism_instances.py"}[kind]).open("a", encoding="utf-8") as stream:
            stream.write("\n# changed stable frontend\n")
    else:
        args["expectedSourceFingerprint"] = "0" * 64
    failed = await supervisor.restart(args)
    assert failed["error"]["code"] == code
    assert supervisor.status()["workerPid"] == before["workerPid"]
    assert supervisor.state == "running" and supervisor.generation == 1
    assert (await unknown(supervisor))["structuredContent"]["testRevision"] == "first"


async def test_busy_generation_and_id_conflicts_do_not_restart(supervisor):
    args = {"requestId": "once", "expectedGeneration": 1}
    async with supervisor.gate:
        failed = await supervisor.restart(args)
        assert failed["error"]["code"] == "BACKEND_BUSY_NOT_DISPATCHED"
        with pytest.raises(BackendError, match="BACKEND_BUSY_NOT_DISPATCHED"):
            await unknown(supervisor)
    assert (await supervisor.restart({**args, "expectedGeneration": 2}))["error"]["code"] == "BACKEND_GENERATION_CHANGED"
    assert (await supervisor.restart(args))["error"]["code"] == "BACKEND_RESTART_ID_CONFLICT"
    assert supervisor.generation == 1


async def test_worker_crash_never_automatically_replays_or_restarts(supervisor):
    supervisor.child.process.kill()
    await supervisor.child.process.wait()
    with pytest.raises(BackendError, match="BACKEND_CALL_OUTCOME_UNKNOWN"):
        await unknown(supervisor)
    reply = await supervisor.restart({"requestId": "after-crash", "expectedGeneration": 1})
    assert reply["error"]["code"] == "BACKEND_RECONNECT_REQUIRED"
    assert supervisor.generation == 1 and supervisor.status()["state"] == "faulted"


async def test_failure_after_retirement_stays_closed_across_repeated_recovery_requests(supervisor, monkeypatch):
    original = supervisor.candidate
    async def candidate():
        child = await original()
        rpc = child.rpc
        async def fail_activation(method, params=None):
            if method == "activate":
                raise BackendError("FIXTURE_ACTIVATION_FAILURE")
            return await rpc(method, params)
        child.rpc = fail_activation
        return child
    monkeypatch.setattr(supervisor, "candidate", candidate)
    reply = await supervisor.restart({"requestId": "retirement", "expectedGeneration": 1})
    assert reply["error"]["code"] == "FIXTURE_ACTIVATION_FAILURE"
    for request in ("next", "again"):
        reply = await supervisor.restart({"requestId": request, "expectedGeneration": 1})
        assert reply["error"]["code"] == "BACKEND_RECONNECT_REQUIRED"
        assert supervisor.state == "faulted" and supervisor.child is None
    with pytest.raises(BackendError, match="BACKEND_UNAVAILABLE_NOT_DISPATCHED"):
        await unknown(supervisor)


async def test_dependency_version_change_refuses_before_retiring_original_worker(supervisor):
    pid = supervisor.child.process.pid
    supervisor.runtime = {**supervisor.runtime, "mcp": "fixture-other-version"}
    reply = await supervisor.restart({"requestId": "dependencies", "expectedGeneration": 1})
    assert reply["error"]["code"] == "BACKEND_DEPENDENCIES_RECONNECT_REQUIRED"
    assert supervisor.child.process.pid == pid
    assert (await unknown(supervisor))["structuredContent"]["testRevision"] == "first"


def mock_worker(tmp_path, *, active=False, qa_active=False, session="game-session", capture=None):
    runtime = tmp_path / "runtime.json"
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671", "token": "fixture-secret"}), encoding="utf-8")
    config = Configuration({"MCB_RUNTIME_FILE": str(runtime), "MCB_KNOWLEDGE_MODE": "off",
                            "MCB_KNOWLEDGE_PATH": str(tmp_path / "knowledge.sqlite3")})
    def respond(request):
        body = json.loads(request.content)
        operation = body["operation"]
        if capture is not None:
            capture.append(operation)
        data = {"bridge.health": {"protocolVersion": 1, "capabilities": ["player.task", "qa.status"], "sessionId": session, "profile": "developer", "readiness": {
                    "lifecyclePending": False, "worldLoaded": True, "playerId": "player",
                    "world": {"worldName": "fixture", "dimension": 0}}},
                "player.task": {"status": "active" if active else "idle", "ownedKeyCount": int(active)},
                "qa.status": {"nativeBatchActive": qa_active}}
        assert operation in data, operation
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data[operation]})
    return backend_worker.Worker(BridgeClient(config, transport=httpx.MockTransport(respond)))


@pytest.mark.parametrize("phase", ["new", "preparing", "preview_pending", "native_pending", "take_pending", "delivery_sync", "start_unknown", "take_unknown"])
async def test_pending_order_migrates_without_game_calls(tmp_path, phase):
    worker = mock_worker(tmp_path)
    worker.adapter.ae_order_records[(START["expectedSessionId"], START["requestId"])] = {"phase": phase, "args": deepcopy(START)}
    try:
        checkpoint = await worker.dispatch("prepare_restart", {})
        new = mock_worker(tmp_path)
        try:
            await new.dispatch("restore_state", checkpoint)
            assert new.adapter.ae_order_records == worker.adapter.ae_order_records
        finally:
            await new.client.aclose()
    finally:
        await worker.client.aclose()


async def test_completed_receipt_migrates_and_duplicate_order_cannot_replay(tmp_path, monkeypatch):
    from minecraft_codex_bridge import ae_orders
    async def initialize(self):
        pass
    async def unexpected(self):
        pytest.fail("completed order was dispatched again")
    monkeypatch.setattr(ae_orders.OrderTask, "initialize", initialize)
    monkeypatch.setattr(ae_orders.OrderTask, "execute", unexpected)
    old, new = mock_worker(tmp_path), mock_worker(tmp_path)
    record = {"phase": "completed", "args": deepcopy(START), "native": {"state": "completed", "craftingId": "once"}}
    old.adapter.ae_order_records[(START["expectedSessionId"], START["requestId"])] = record
    try:
        checkpoint = await old.dispatch("prepare_restart", {})
        restored = await new.dispatch("restore_state", checkpoint)
        assert restored["restoredOrders"] == 1
        result = await ae_orders.run_order(new.client, START, new.adapter.workflow_lock, None, new.adapter.ae_order_records)
        assert result["status"] == "completed" and result["native"]["craftingId"] == "once"
        with pytest.raises(Exception, match="different goal"):
            await ae_orders.run_order(new.client, {**START, "amount": START["amount"] + 1}, new.adapter.workflow_lock, None, new.adapter.ae_order_records)
    finally:
        await old.client.aclose()
        await new.client.aclose()


@pytest.mark.parametrize("active,qa_active,code", [(True, False, "BACKEND_INPUT_ACTIVE"),
                                                 (False, True, "BACKEND_QA_ACTIVE_OR_UNCONFIRMED")])
async def test_server_owned_actions_do_not_gate_python_handoff(tmp_path, active, qa_active, code):
    calls = []
    worker = mock_worker(tmp_path, active=active, qa_active=qa_active, capture=calls)
    try:
        await worker.dispatch("prepare_restart", {})
        assert calls == []
    finally:
        await worker.client.aclose()


async def test_changed_game_session_does_not_change_connection_handoff(tmp_path):
    old = mock_worker(tmp_path)
    new = mock_worker(tmp_path, session="different-session")
    try:
        checkpoint = await old.dispatch("prepare_restart", {})
        await new.dispatch("restore_state", checkpoint)
        assert new.restored
    finally:
        await old.client.aclose()
        await new.client.aclose()


async def test_unknown_mutation_and_candidate_cannot_dispatch(tmp_path):
    worker = mock_worker(tmp_path)
    try:
        with pytest.raises(BackendError, match="BACKEND_NOT_ACTIVE"):
            await worker.dispatch("call_tool", {"name": "mcb_health", "arguments": {}})
        worker.uncertain = True
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert checkpoint["state"]["uncertain"] is True
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("raw", [b'{"id":1,"id":2}\n', b'{"value":NaN}\n', b'\xff\n', b'{}'])
def test_wire_rejects_ambiguous_or_incomplete_json(raw):
    with pytest.raises(BackendError):
        decode(raw)


async def test_real_stdio_entrypoint_reload_and_continue_without_business_imports_in_parent(tmp_path):
    environment = isolated_environment(tmp_path)
    # The normal run.py entry is exercised, not an additional MCP registration or desktop hook.
    params = StdioServerParameters(command=sys.executable, args=[str(PACKAGE.parents[1] / "run.py")], env=environment)
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=55)) as session:
            initialized = await session.initialize()
            assert initialized.serverInfo.name == "minecraft-codex-bridge"
            before = (await session.call_tool("mcb_backend_status", {})).structuredContent["data"]
            reply = await session.call_tool("mcb_backend_restart", {"requestId": "real-stdio", "expectedGeneration": 1})
            assert not reply.isError, reply
            after = (await session.call_tool("mcb_backend_status", {})).structuredContent["data"]
            assert after["frontendPid"] == before["frontendPid"] and after["workerPid"] != before["workerPid"]
            failure = await session.call_tool("mcb_backend_restart", {"requestId": "bad", "expectedGeneration": True})
            assert failure.isError and failure.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
            assert (await session.call_tool("fixture_unknown", {})).structuredContent["error"]["code"] == "UNKNOWN_TOOL"
    code = "import sys;from minecraft_codex_bridge import frontend;assert not any(k in sys.modules for k in ('minecraft_codex_bridge.server','minecraft_codex_bridge.tools','minecraft_codex_bridge.bridge'))"
    completed = await anyio.run_process([sys.executable, "-c", code], env=environment)
    assert completed.returncode == 0
