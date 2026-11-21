"""A real frozen worker handoff and recovery bookkeeping for cooperative action chains."""

from copy import deepcopy
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from threading import Thread

import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.backend_supervisor import Supervisor
from minecraft_codex_bridge.backend_worker import Worker
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.frontend import make_frontend
from test_action_chain_workflow import BOUND, DIM, EAT, Game, SESSION, TOOL, ok
from test_backend_supervisor import copy_package, revision
from test_conversation_binding import environment


def args(action, state=None, **extra):
    ref = {"chainId": state["chainId"], "expectedRevision": state["revision"]} if state else {}
    return {"action": action, **ref, **extra}


@pytest.fixture
def game_instance(tmp_path):
    game = Game()
    instance = tmp_path / "instances" / "chain-reload-fixture"
    runtime = instance / ".minecraft" / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    (instance / "instance.cfg").write_text("[General]\nInstanceType=OneSix\nname=chain-reload-fixture\n", encoding="utf-8")
    token = "chain-fixture-local-credential"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            if self.headers.get("Authorization") != "Bearer " + token:
                self.send_error(401)
                return
            body = self.rfile.read(int(self.headers["Content-Length"]))
            request = httpx.Request("POST", "http://fixture.invalid", content=body)
            envelope = game.rpc(request).json()
            if json.loads(body)["operation"] == "bridge.health":
                envelope["data"]["instancePath"] = str(instance.resolve())
            response = json.dumps(envelope).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        runtime.write_text(json.dumps({"protocolVersion": 1, "instancePath": str(instance.resolve()),
            "sessionId": SESSION, "baseUrl": f"http://127.0.0.1:{server.server_port}", "token": token}), encoding="utf-8")
        yield instance, game
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


async def test_same_mcp_binding_reloads_frozen_code_and_resumes_open_queue_only_on_advance(tmp_path, game_instance):
    instance, game = game_instance
    root = copy_package(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"version": 1, "activeProfile": "fixture",
                                  "profiles": {"fixture": {"instancePath": str(instance)}}}), encoding="utf-8")
    supervisor = Supervisor(root, environment=environment(tmp_path, config), snapshot_directory=tmp_path)
    try:
        await supervisor.start()
        assert supervisor.state == "running"
        async with create_connected_server_and_client_session(make_frontend(supervisor), raise_exceptions=True) as session:
            bound = ok(await session.call_tool("mcb_connection", {"action": "bind", "prismRoot": str(tmp_path),
                "instanceName": instance.name, "expectedConversationId": "chat-one", "expectedBindingGeneration": 0,
                "requestId": "bind-chain-fixture"}))
            assert bound["sessionIdAtBind"] == SESSION
            connection = deepcopy(supervisor.connection)
            current = ok(await session.call_tool("mcb_action_chain", args("open", **BOUND, dimension=DIM, segments=[EAT, TOOL])))
            current = ok(await session.call_tool("mcb_action_chain", args("advance", current)))
            assert current["completed"] == ["eat"] and current["pending"] == ["pickaxe"]
            old = supervisor.status()
            calls = list(game.calls)
            revision(root, "after-open-chain")
            old_code = await session.call_tool("mcb_action_chain", args("status"))
            assert "testRevision" not in old_code.structuredContent and ok(old_code) == current
            restarted = ok(await session.call_tool("mcb_backend_restart", {
                "requestId": "reload-open-chain", "expectedGeneration": old["generation"]}))
            assert restarted["workerPid"] != old["workerPid"] and restarted["frontendPid"] == old["frontendPid"]
            assert restarted["connectionPreserved"] and restarted["generation"] == old["generation"] + 1
            assert supervisor.connection == connection and game.calls == calls
            for _ in range(2):
                restored = await session.call_tool("mcb_action_chain", args("status"))
                assert restored.structuredContent["testRevision"] == "after-open-chain" and ok(restored) == current
            assert game.calls == calls
            done = ok(await session.call_tool("mcb_action_chain", args("advance", current)))
            assert done["status"] == "completed" and done["completed"] == ["eat", "pickaxe"]
            assert [rpc["operation"] for rpc in game.calls if rpc["operation"] in {"survival.food", "player.hotbar"}] == [
                "survival.food", "player.hotbar"]
    finally:
        await supervisor.close()


@pytest.mark.parametrize("unknown", [False, True])
async def test_blocked_chain_status_cancel_and_advance_use_chain_state_without_reclassifying_history(tmp_path, unknown):
    game = Game()
    if unknown:
        game.conflicting_receipt = {"status": "active", "taskId": "stale-before"}
    else:
        game.acquire_before = "survival.food"
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "chain-recovery-local-rpc-token",
        "MCB_KNOWLEDGE_MODE": "off", "MCB_KNOWLEDGE_PATH": str(tmp_path / "kb.sqlite3")}),
        transport=httpx.MockTransport(game.rpc))
    worker = Worker(client)
    try:
        await worker.dispatch("activate", {})

        async def call(action, state=None, **extra):
            return (await worker.dispatch("call_tool", {"name": "mcb_action_chain",
                "arguments": args(action, state, **extra)}))["structuredContent"]

        current = (await call("open", **BOUND, dimension=DIM, segments=[EAT, TOOL]))["data"]
        failed = await call("advance", current)
        assert not failed["ok"] and "data" in failed, failed
        blocked = failed["data"]
        assert blocked["status"] == "blocked" and blocked["last"]["report"]["outcomeUnknown"] is unknown
        records = deepcopy(worker.failed_actions)
        assert [entry["tool"] for entry in records] == (["mcb_action_chain"] if unknown else [])
        if records:
            assert records[0]["arguments"] == args("advance", current)
        calls = list(game.calls)
        checkpoint = await worker.dispatch("prepare_restart", {})
        worker = Worker(client)
        restored = await worker.dispatch("restore_state", checkpoint)
        assert restored["stateFingerprint"] == checkpoint["stateFingerprint"]
        await worker.dispatch("activate", {})
        for _ in range(2):
            status = await call("status")
            assert status["ok"] and status["data"] == blocked
        rejected = await call("advance", blocked)
        assert not rejected["ok"] and rejected["error"]["code"] == "CHAIN_NOT_READY"
        cancelled = await call("cancel", blocked)
        assert cancelled["ok"] and cancelled["data"]["status"] == "cancelled"
        assert cancelled["data"]["pending"] == [] and cancelled["data"]["last"] == blocked["last"]
        assert (await call("status"))["data"] == cancelled["data"]
        assert worker.failed_actions == records and game.calls == calls
    finally:
        await client.aclose()
