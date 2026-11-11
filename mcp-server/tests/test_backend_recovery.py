"""Movement diagnostics stay intact across reload; unknown actions never replay."""
from copy import deepcopy
import json

import httpx
import pytest
from mcp.types import CallToolResult

from minecraft_codex_bridge import backend_recovery, backend_worker
from minecraft_codex_bridge.backend_transport import BackendError
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from test_ae_goals import START

ARGS = dict(expectedSessionId="session", expectedWorld="world", dimension=0, strategy="ability_flight", move=True)


def observation(sample=10):
    return dict(available=True, source="fresh_client_snapshot_after_failure", automaticReplayAllowed=False,
        profile="developer", sessionId="session", world=dict(worldName="world", worldFolder="folder", dimension=0),
        player=dict(health=20, x=1., feetY=4., z=2., motionX=0., motionY=-.0784, motionZ=0.,
            onGround=True, nativeMoveForward=0., nativeMoveStrafing=0.),
        serverControl=dict(available=True, source="integrated_server_tick", sessionId="session", dimension=0,
            playerId="actor", worldName="world", worldFolder="folder", sampleId=sample, ageMillis=5.,
            pose=dict(x=1., feetY=4., z=2., motionX=0., motionY=-.0784, motionZ=0., onGround=True)),
        inputTask=dict(operation="player.task", sessionId="session", status="idle", owner="none", ownedKeyCount=0),
        inputKeys=[], inputTicksRemaining=0, inputDiagnostics=dict(logicalOwnedKeyCount=0), bridgeOwnedKeysReleased=True)


def failure():
    return dict(ok=False, error=dict(code="timeout", outcome_unknown=True, replayAllowed=False,
        receipt=dict(status="failed", errorCode="timeout", sessionId="session", expectedWorld="world", dimension=0,
            strategy="ability_flight", movementDispatched=True, interactionDispatched=False, plansPersisted=False,
            recoveryObservation=observation())))


def make_worker(tmp_path, monkeypatch):
    runtime = tmp_path / "runtime.json"
    runtime.write_text(json.dumps(dict(protocolVersion=1, baseUrl="http://127.0.0.1:25671", token="fixture")), encoding="utf-8")
    current = observation(11)
    state = dict(calls=[], result=failure(), health=dict(protocolVersion=1, sessionId="session", profile="developer",
        capabilities=["player.task", "qa.status", "client.snapshot"],
        readiness=dict(lifecyclePending=False, worldLoaded=True, playerId="actor", world=deepcopy(current["world"]))),
        snapshot=current, task=deepcopy(current["inputTask"]), qa=dict(nativeBatchActive=False))
    def respond(request):
        body = json.loads(request.content)
        op = body["operation"]
        state["calls"].append(op)
        if state.get("snapshot_error") and op == "client.snapshot":
            return httpx.Response(200, json=dict(requestId=body["requestId"], ok=False,
                error=dict(code="snapshot_unavailable", message="fixture read failure", outcome_unknown=False)))
        value = {"bridge.health": state["health"], "player.task": state["task"],
            "qa.status": state["qa"], "client.snapshot": state["snapshot"]}[op]
        return httpx.Response(200, json=dict(requestId=body["requestId"], ok=True, data=value))
    worker = backend_worker.Worker(BridgeClient(Configuration(dict(MCB_RUNTIME_FILE=str(runtime))), transport=httpx.MockTransport(respond)))
    async def result(name, args):
        return CallToolResult(content=[], structuredContent=deepcopy(state["result"]), isError=True)
    monkeypatch.setattr(worker.adapter, "call_tool", result)
    worker.active = True
    return worker, state


async def submit(worker, name="mcb_approach", args=None):
    return await worker.dispatch("call_tool", dict(name=name, arguments=ARGS if args is None else args))


@pytest.mark.parametrize("interact", [False, True])
async def test_timeout_before_use_reconciles_once_without_replaying_or_changing_result(tmp_path, monkeypatch, interact):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        result = await submit(worker, args={**ARGS, "interact": interact})
        assert result["structuredContent"] == state["result"] and result["isError"]
        assert not worker.uncertain and len(worker.movement_failures) == 1
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert worker.prepared and checkpoint["state"]["movementFailures"] == worker.movement_failures
        assert len(worker.movement_failures) == 1 and worker.reconciled_movements == 0
        assert state["calls"] == []
        await worker.dispatch("activate", {})
        state["calls"].clear()
        assert (await worker.dispatch("prepare_restart", {}))["state"]["reconciledMovements"] == 0
        assert "client.snapshot" not in state["calls"]
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("path,value", [
    (("data",), {"outcomeUnknown": True}),
    (("error", "cause"), {"code": "HTTP_ERROR", "outcome_unknown": True}),
    (("error", "replayAllowed"), True),
    (("error", "code"), "HTTP_ERROR"), (("error", "outcome_unknown"), 1),
    (("error", "receipt", "interactionDispatched"), True), (("error", "receipt", "interactionDispatched"), None),
    (("error", "receipt", "movementDispatched"), False), (("error", "receipt", "plansPersisted"), True),
    (("error", "receipt", "sessionId"), "other"), (("error", "receipt", "dimension"), False),
    (("error", "receipt", "recoveryObservation", "available"), False),
    (("error", "receipt", "recoveryObservation", "bridgeOwnedKeysReleased"), False),
    (("error", "receipt", "recoveryObservation", "inputTask", "ownedKeyCount"), False),
    (("error", "receipt", "recoveryObservation", "serverControl", "playerId"), ""),
    (("error", "receipt", "recoveryObservation", "serverControl", "ageMillis"), float("inf")),
    (("error", "receipt", "recoveryObservation", "inputKeys"), ["forward"]),
])
def test_incomplete_or_foreign_original_receipts_are_not_recoverable(path, value):
    result = failure()
    target = result
    for field in path[:-1]: target = target[field]
    target[path[-1]] = value
    assert backend_recovery.movement_failure("mcb_approach", ARGS, result) is None


@pytest.mark.parametrize("tool", ["mcb_ae_order", "mcb_blueprint_build", "mcb_place_block", "mcb_slot_click", "mcb_navigate", "mcb_chat"])
async def test_other_actions_cannot_borrow_a_nested_movement_receipt(tmp_path, monkeypatch, tool):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker, tool)
        assert worker.uncertain and not worker.movement_failures
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert checkpoint["state"]["uncertain"] is True
        assert state["calls"] == []
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("path,value", [
    (("sessionId",), "other"), (("world", "dimension"), 1), (("world", "dimension"), False),
    (("world", "worldName"), "other"), (("serverControl", "playerId"), "other"),
    (("serverControl", "sampleId"), 10), (("serverControl", "sampleId"), True),
    (("serverControl", "ageMillis"), 1001), (("serverControl", "source"), "cached"),
    (("serverControl", "sessionId"), "other"), (("inputTask", "owner"), "another"),
    (("inputDiagnostics", "logicalOwnedKeyCount"), 1), (("inputTicksRemaining",), 1),
    (("inputKeys",), ["forward"]), (("player", "nativeMoveForward"), 1),
    (("player", "motionX"), .02), (("serverControl", "pose", "motionZ"), .02),
    (("player", "onGround"), False), (("serverControl", "pose", "x"), 3.),
    (("player", "health"), 0), (("serverControl", "pose"), None),
])
async def test_fresh_proof_must_match_and_be_settled_failure_keeps_record(tmp_path, monkeypatch, path, value):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker)
        valid = deepcopy(state["snapshot"])
        target = state["snapshot"]
        for field in path[:-1]: target = target[field]
        target[path[-1]] = value
        binding = dict(offline=False, worldLoaded=True, sessionId="session", playerId="actor", world=state["health"]["readiness"]["world"])
        with pytest.raises(BackendError, match="BACKEND_MOVEMENT_RECOVERY_UNCONFIRMED"):
            await backend_recovery.reconcile_movements(worker.client, worker.movement_failures, binding)
        assert len(worker.movement_failures) == 1
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert checkpoint["state"]["movementFailures"] == worker.movement_failures
        state["snapshot"] = valid
        assert await backend_recovery.reconcile_movements(worker.client, worker.movement_failures, binding) == 1
        assert set(state["calls"]) <= {"bridge.health", "client.snapshot"}
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("path,value", [(("sessionId",), "other"), (("readiness", "playerId"), "other"),
    (("readiness", "worldLoaded"), False), (("readiness", "world", "dimension"), 1)])
async def test_current_binding_cannot_reconcile_another_world_or_actor(tmp_path, monkeypatch, path, value):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker)
        target = state["health"]
        for field in path[:-1]: target = target[field]
        target[path[-1]] = value
        await worker.dispatch("prepare_restart", {})
        assert len(worker.movement_failures) == 1 and state["calls"] == []
    finally:
        await worker.client.aclose()


async def test_unknown_write_or_pending_order_is_not_cleared_by_idle_movement(tmp_path, monkeypatch):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker)
        worker.adapter.ae_order_records[("session", "order")] = dict(phase="take_unknown", args=deepcopy(START))
        assert (await worker.dispatch("prepare_restart", {}))["state"]["orders"][0]["phase"] == "take_unknown"
        await worker.dispatch("activate", {})
        worker.adapter.ae_order_records.clear()
        await submit(worker, "mcb_slot_click")
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert checkpoint["state"]["uncertain"] is True
        assert state["calls"] == [] and len(worker.movement_failures) == 1
    finally:
        await worker.client.aclose()


async def test_bounded_records_and_unavailable_observation_do_not_block_reload(tmp_path, monkeypatch):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        for n in range(backend_recovery.MAX_MOVEMENT_FAILURES):
            await submit(worker, args={**ARGS, "testDistinctAction": n})
        state["snapshot_error"] = True
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert len(checkpoint["state"]["failedActions"]) == 64 and state["calls"] == []
        await worker.dispatch("activate", {})
        with pytest.raises(BackendError, match="BACKEND_ACTION_RECORD_CAPACITY"):
            await submit(worker, args={**ARGS, "testDistinctAction": 65})
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("marker", [{"outcome_unknown": True}, {"outcomeUnknown": True}, {"code": "HTTP_ERROR"}])
@pytest.mark.parametrize("location", ["history", "data", "error", "nested_localKnowledge"])
async def test_history_annotation_cannot_poison_or_hide_current_outcomes(tmp_path, monkeypatch, marker, location):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        state["result"] = dict(ok=True, data=dict(status="completed"),
            localKnowledge=dict(historicalOnly=True, hints=[dict(failure=deepcopy(marker))]))
        if location in {"data", "error"}:
            state["result"][location] = dict(receipt=deepcopy(marker))
        elif location == "nested_localKnowledge":
            state["result"]["data"]["localKnowledge"] = deepcopy(marker)
        await submit(worker)
        if location == "history":
            assert not worker.uncertain and not worker.movement_failures
            assert (await worker.dispatch("prepare_restart", {}))["state"]["reconciledMovements"] == 0
        else:
            assert worker.uncertain
            assert (await worker.dispatch("prepare_restart", {}))["state"]["uncertain"] is True
    finally:
        await worker.client.aclose()


async def test_historical_success_does_not_clear_an_existing_unknown_write(tmp_path, monkeypatch):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker, "mcb_slot_click")
        state["result"] = dict(ok=True, data=dict(status="completed"), localKnowledge=dict(historicalOnly=True))
        await submit(worker)
        checkpoint = await worker.dispatch("prepare_restart", {})
        assert checkpoint["state"]["uncertain"] is True
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("name,historical_success,order_phase", [("mcb_approach", False, "completed"), ("mcb_slot_click", False, "completed"), ("mcb_approach", True, "completed"), ("mcb_approach", False, "preparing"), ("mcb_slot_click", False, "native_pending"), ("mcb_slot_click", False, "take_unknown")])
async def test_real_worker_switch_preserves_movement_and_unknown_write(tmp_path, name, historical_success, order_phase):
    from mcp.shared.memory import create_connected_server_and_client_session
    from minecraft_codex_bridge.backend_supervisor import Supervisor
    from minecraft_codex_bridge.frontend import make_frontend
    from test_backend_supervisor import copy_package, isolated_environment, revision

    root = copy_package(tmp_path)
    runtime = tmp_path / "fixture-runtime.json"
    runtime.write_text(json.dumps(dict(protocolVersion=1, baseUrl="http://127.0.0.1:25671", token="fixture")), encoding="utf-8")
    current = observation(11)
    native = {"bridge.health": dict(protocolVersion=1, sessionId="session", profile="developer",
        capabilities=["player.task", "qa.status"], readiness=dict(lifecyclePending=False, worldLoaded=True,
            playerId="actor", world=current["world"])), "player.task": current["inputTask"],
        "qa.status": dict(nativeBatchActive=False), "client.snapshot": current}
    # Test-only dependencies in the frozen copy. No installed runtime or game
    # connection is used; management and actual child-process switching are real.
    (root / "_test_restart_fixture.py").write_text(
        'import json, httpx\nfrom .bridge import BridgeClient, Configuration\n'
        f'DATA = {native!r}\n'
        'def respond(request):\n'
        '    body = json.loads(request.content)\n'
        '    assert body["operation"] in DATA, "game action is forbidden"\n'
        '    return httpx.Response(200, json=dict(requestId=body["requestId"], ok=True, data=DATA[body["operation"]]))\n'
        'def make_client():\n'
        f'    return BridgeClient(Configuration({{"MCB_RUNTIME_FILE": {str(runtime)!r}}}), transport=httpx.MockTransport(respond))\n',
        encoding="utf-8")
    worker_file = root / "backend_worker.py"
    worker_file.write_text(worker_file.read_text(encoding="utf-8").replace(
        '    client = client or BridgeClient(Configuration(dict(os.environ)))',
        '    from ._test_restart_fixture import make_client\n    client = client or make_client()', 1), encoding="utf-8")
    server_file = root / "application" / "adapter.py"
    entry = '    async def call_tool(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:\n'
    source = server_file.read_text(encoding="utf-8")
    assert source.count(entry) == 1
    terminal = dict(phase=order_phase, args=deepcopy(START), native=dict(state="completed", craftingId="once"), lastError=dict(code="fixture_history", outcome_unknown=True))
    action_result = dict(ok=True, data=dict(status="completed", movement=dict(arrivalConfirmed=True)),
        localKnowledge=dict(historicalOnly=True, hints=[dict(failure=dict(code="timeout", outcome_unknown=True))])) if historical_success else failure()
    server_file.write_text(source.replace(entry, entry +
        f'        if name == {name!r}:\n'
        f'            self.ae_order_records[("session", "archived")] = {terminal!r}\n'
        f'            return _result({action_result!r}, error={not historical_success!r})\n', 1), encoding="utf-8")
    supervisor = Supervisor(root, environment=isolated_environment(tmp_path), snapshot_directory=tmp_path)
    await supervisor.start()
    try:
        assert supervisor.state == "running"
        before = supervisor.status()
        async with create_connected_server_and_client_session(make_frontend(supervisor), raise_exceptions=True) as session:
            tools = (await session.list_tools()).tools
            args = ARGS if name == "mcb_approach" else dict(windowId=0, slot=1, button=0, mode=0)
            failed = await session.call_tool(name, args)
            if historical_success:
                assert not failed.isError and failed.structuredContent == action_result
            else:
                assert failed.isError and failed.structuredContent["error"]["outcome_unknown"]
            revision(root, "after-recovery")
            args = dict(requestId="recovery", expectedGeneration=1)
            switched = await session.call_tool("mcb_backend_restart", args)
            assert not switched.isError, switched
            data = switched.structuredContent["data"]
            assert data["frontendPid"] == before["frontendPid"] and data["workerPid"] != before["workerPid"]
            assert data["restoredOrders"] == 1 and data["connectionPreserved"] and data["generation"] == 2
            assert data["restoredFailedActions"] == (0 if historical_success else 1)
            assert data["gameReadiness"] == "not_observed"
            assert (await session.call_tool("fixture_unknown", {})).structuredContent["testRevision"] == "after-recovery"
            assert (await session.call_tool("mcb_backend_restart", args)).structuredContent == switched.structuredContent
            if not historical_success:
                repeat = await session.call_tool(name, ARGS if name == "mcb_approach" else dict(windowId=0, slot=1, button=0, mode=0))
                assert repeat.isError and repeat.structuredContent["error"]["code"] == "BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION"
            again = await session.call_tool("mcb_backend_restart", dict(requestId="second-transfer", expectedGeneration=2))
            assert not again.isError and again.structuredContent["data"]["generation"] == 3
            assert again.structuredContent["data"]["stateFingerprint"] == data["stateFingerprint"]
            assert (await session.list_tools()).tools == tools
    finally:
        await supervisor.close()
