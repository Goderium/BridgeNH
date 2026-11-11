"""A reload transfers unresolved work; it neither accepts nor replays it."""
from copy import deepcopy

import pytest
import anyio

from minecraft_codex_bridge import ae_orders, backend_state
from minecraft_codex_bridge.backend_transport import BackendError, fingerprint
from test_ae_goals import START
from test_backend_recovery import make_worker, submit, failure
from test_backend_supervisor import mock_worker


@pytest.mark.parametrize("phase", sorted(backend_state.ORDER_PHASES))
async def test_lossless_offline_roundtrip_all_order_phases_and_unknown_results(tmp_path, monkeypatch, phase):
    old, observed = make_worker(tmp_path, monkeypatch)
    new = mock_worker(tmp_path)
    args = dict(windowId=0, slot=1, button=0, mode=0)
    record = dict(phase=phase, args=deepcopy(START), resource=dict(identityNbt="exact NBT"),
        preview=dict(state=dict(requested=dict(amount="1"))),
        handPreparation=[dict(status="failed", replayAllowed=False, moves=[dict(hotbarIndex=0,
            storageIndex=20, movementDispatched=True, movementConfirmed=False, outcomeUnknown=True)])],
        native=dict(state="running", craftingId="original-native-id"), lastError=dict(code="unsupported", outcome_unknown=True))
    old.adapter.ae_order_records[(START["expectedSessionId"], START["requestId"])] = deepcopy(record)
    try:
        await submit(old, "mcb_slot_click", args)
        (tmp_path / "runtime.json").unlink()
        before = await old.dispatch("prepare_restart", {})
        restored = await new.dispatch("restore_state", before)
        assert restored["stateFingerprint"] == before["stateFingerprint"]
        assert new.adapter.ae_order_records == old.adapter.ae_order_records
        assert new.failed_actions == old.failed_actions and new.uncertain
        assert new.failed_actions[0]["result"] == failure()
        assert (await new.checkpoint())["state"] == before["state"]
        assert observed["calls"] == []
        # Neither restore nor activation contacts a disconnected game.
        await new.dispatch("activate", {})
        replay = await new.dispatch("call_tool", dict(name="mcb_slot_click", arguments=args))
        assert replay["structuredContent"]["error"]["code"] == "BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION"
    finally:
        await old.client.aclose()
        await new.client.aclose()


@pytest.mark.parametrize("alteration", ["phase", "duplicate", "version", "boolean", "missing", "receipt", "oversize", "fingerprint"])
async def test_invalid_or_incomplete_handoff_does_not_commit_candidate_state(tmp_path, alteration):
    old, new = mock_worker(tmp_path), mock_worker(tmp_path)
    old.adapter.ae_order_records[(START["expectedSessionId"], START["requestId"])] = dict(phase="preparing", args=deepcopy(START))
    try:
        checkpoint = await old.checkpoint()
        state = checkpoint["state"]
        if alteration == "phase": state["orders"][0]["phase"] = []
        elif alteration == "duplicate": state["orders"] *= 2
        elif alteration == "version": state["version"] = 1
        elif alteration == "boolean": state["uncertain"] = 1
        elif alteration == "missing": del state["failedActions"]
        elif alteration == "receipt": state["failedActions"] = [dict(tool="mcb_slot_click", arguments={}, result=None)]
        elif alteration == "oversize": state["orders"][0]["evidence"] = "x" * (4 * 1024 * 1024)
        if alteration != "fingerprint": checkpoint["stateFingerprint"] = fingerprint(state)
        else: checkpoint["stateFingerprint"] = "0" * 64
        with pytest.raises(BackendError):
            await new.dispatch("restore_state", checkpoint)
        assert not new.restored and new.adapter.ae_order_records == {} and new.failed_actions == []
    finally:
        await old.client.aclose()
        await new.client.aclose()


async def test_connection_change_refused_without_exposing_credentials(tmp_path):
    old, new = mock_worker(tmp_path), mock_worker(tmp_path)
    try:
        checkpoint = await old.checkpoint()
        new.client.configuration.environ["MCB_RUNTIME_FILE"] = str(tmp_path / "another-instance" / "runtime.json")
        with pytest.raises(BackendError, match="BACKEND_CONNECTION_CHANGED"):
            await new.dispatch("restore_state", checkpoint)
        assert not new.restored and "fixture-secret" not in str(checkpoint)
    finally:
        await old.client.aclose()
        await new.client.aclose()


async def test_new_order_id_or_budget_does_not_authorize_matching_unknown_action(tmp_path, monkeypatch):
    worker, state = make_worker(tmp_path, monkeypatch)
    try:
        await submit(worker, "mcb_ae_order", START)
        result = await submit(worker, "mcb_ae_order", {**START, "requestId": "another-id", "timeout_ms": 60000})
        assert result["structuredContent"]["error"]["code"] == "BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION"
        state["result"] = dict(ok=True, data={})
        assert (await submit(worker, "mcb_inventory", {}))["structuredContent"]["ok"]
        assert (await submit(worker, "mcb_hotbar", {"slot": 8}))["structuredContent"]["ok"]
        assert len(worker.failed_actions) == 1
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("phase", ["preparing", "start_unknown", "take_unknown"])
async def test_resumed_uncertain_order_keeps_error_and_does_not_execute(tmp_path, monkeypatch, phase):
    old, new = mock_worker(tmp_path), mock_worker(tmp_path)
    error = dict(code="unsupported", outcome_unknown=True, receipt=dict(interactionDispatched=False))
    old.adapter.ae_order_records[(START["expectedSessionId"], START["requestId"])] = dict(
        phase=phase, args=deepcopy(START), lastError=error)
    async def initialize(self): pass
    async def unexpected(self): pytest.fail("uncertain order replayed")
    monkeypatch.setattr(ae_orders.OrderTask, "initialize", initialize)
    monkeypatch.setattr(ae_orders.OrderTask, "execute", unexpected)
    try:
        await new.dispatch("restore_state", await old.checkpoint())
        args = {key: START[key] for key in ("requestId", "expectedSessionId", "expectedWorld", "dimension")}
        result = await ae_orders.run_order(new.client, {**args, "action": "resume"}, new.adapter.workflow_lock, None, new.adapter.ae_order_records)
        assert result["status"] == phase and result["error"] == error
        assert result["dispatchedActionAttempts"] == 0 and not result["resumable"]
    finally:
        await old.client.aclose()
        await new.client.aclose()


async def test_failed_handoff_ack_keeps_old_worker_alive(supervisor, monkeypatch):
    original = supervisor.candidate
    async def candidate():
        child = await original()
        rpc = child.rpc
        async def corrupt_ack(method, params=None):
            result = await rpc(method, params)
            if method == "restore_state": result["stateFingerprint"] = "0" * 64
            return result
        child.rpc = corrupt_ack
        return child
    monkeypatch.setattr(supervisor, "candidate", candidate)
    before = supervisor.status()
    reply = await supervisor.restart(dict(requestId="bad-ack", expectedGeneration=1))
    assert reply["error"]["code"] == "BACKEND_STATE_HANDOFF_UNCONFIRMED"
    assert supervisor.state == "running" and supervisor.status()["workerPid"] == before["workerPid"]


@pytest.mark.parametrize("kind", ["cancel", "exception"])
@pytest.mark.parametrize("compatibility", [False, True])
async def test_process_cancellation_and_exception_records_survive_reload(tmp_path, kind, games, compatibility):
    from minecraft_codex_bridge.backend_supervisor import Supervisor
    from test_backend_supervisor import copy_package
    from test_conversation_binding import environment, bind_args
    root = copy_package(tmp_path)
    path = root / "application" / "adapter.py"
    source = path.read_text(encoding="utf-8")
    entry = '    async def call_tool(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:\n'
    # Isolated fixture returns before any real Adapter or game operation.
    body = '            await anyio.sleep(20)\n' if kind == "cancel" else '            raise RuntimeError("fixture unknown outcome")\n'
    path.write_text(source.replace(entry, entry + '        if name == "mcb_chat":\n' + body, 1), encoding="utf-8")
    supervisor = Supervisor(root, environment=environment(tmp_path, games[2]), snapshot_directory=tmp_path)
    args = dict(name="mcb_chat", arguments=dict(message="fixture-only"))
    await supervisor.start()
    try:
        assert (await supervisor.change_connection(bind_args(supervisor, games[0])))['ok']
        direct = deepcopy(args)
        if compatibility:
            catalog = await supervisor.call('call_tool', {'name': 'mcb_help', 'arguments': {'topic': 'tools', 'tool': 'mcb_chat'}})
            guards = catalog['structuredContent']['data']['invocation']['callGuards']
            args = {'name': 'mcb_sequence', 'arguments': {**guards,
                'call': {'tool': direct['name'], 'args': direct['arguments']}}}
        if kind == "cancel":
            with anyio.move_on_after(.2) as scope:
                await supervisor.call("call_tool", args)
            assert scope.cancel_called
        else:
            with pytest.raises(BackendError, match="BACKEND_EXECUTION_UNKNOWN"):
                await supervisor.call("call_tool", args)
        assert not supervisor.child.broken
        result = await supervisor.restart(dict(requestId=kind, expectedGeneration=2))
        assert result["ok"] and result["data"]["restoredFailedActions"] == 1
        repeat = await supervisor.call("call_tool", args)
        assert repeat["structuredContent"]["error"]["code"] == "BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION"
        direct_repeat = await supervisor.call('call_tool', direct)
        assert direct_repeat['structuredContent']['error']['code'] == 'BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION'
        again = await supervisor.restart(dict(requestId=kind + "-again", expectedGeneration=3))
        assert again["ok"] and again["data"]["stateFingerprint"] == result["data"]["stateFingerprint"]
        assert games[0].operations == ['bridge.health']  # Only explicit binding may probe the fixture.
    finally:
        await supervisor.close()


# Reuse the real-process fixture, not a second installed MCP registration.
from test_backend_supervisor import supervisor
from test_conversation_binding import games
