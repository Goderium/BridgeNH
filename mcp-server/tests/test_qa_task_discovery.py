"""Task-local QA discovery and same-observation completion keep live guards."""

from copy import deepcopy
import dataclasses
import time

import anyio
import pytest

from minecraft_codex_bridge import qa_tasks as qa
from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.workflows import _workflow_discovery, run_workflow
from test_guarded_rpc import guarded_bridge, action, plan, read, PROTECTED
from test_qa_quantitative_summary import accepted_fixture, EVIDENCE_ID


SELECTOR = "test.finite:one"
RUN_ID = "71bf6d2a-0743-49b4-bcbe-79d04f596e34"


def snapshot_read(identifier="snapshot"):
    return read(identifier, "mcb_snapshot")


async def discovery(client):
    health = await client.call("bridge.health", {})
    return _workflow_discovery(client, client.configuration.read(), health)


async def test_last_actual_standalone_snapshot_is_a_valid_boundary_without_repeat(guarded_bridge):
    adapter, state = guarded_bridge
    sealed = await discovery(adapter.client)
    result = await run_workflow(adapter.client, plan(snapshot_read()), anyio.Lock(), _discovery=sealed,
                                _require_background=True)
    assert result["status"] == "completed"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "client.snapshot"]


async def test_ordinary_read_after_snapshot_still_gets_its_own_live_guard(guarded_bridge):
    adapter, state = guarded_bridge
    sealed = await discovery(adapter.client)
    # QA reads are absent from the fixed AFTER-context whitelist.
    step = {"id": "qa", "kind": "read", "tool": "mcb_qa_catalog", "args": {"section": "capabilities"}}
    result = await run_workflow(adapter.client, plan(snapshot_read(), step), anyio.Lock(), _discovery=sealed,
                                _require_background=True)
    assert result["status"] == "completed"
    assert [call["operation"] for call in state["calls"]] == [
        "bridge.health", "client.snapshot", "qa.catalog", "client.snapshot"]


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_every_later_action_rpc_invalidates_the_previous_snapshot_boundary(guarded_bridge, fault, code):
    adapter, state = guarded_bridge
    sealed = await discovery(adapter.client)
    def drift(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_action"] = drift
    result = await run_workflow(adapter.client, plan(snapshot_read(), action()), anyio.Lock(), _discovery=sealed,
                                _require_background=True)
    assert result["status"] == "failed" and result["error"]["code"] == code
    assert state["actions"] == 1
    assert [call["operation"] for call in state["calls"]][-1] == "client.snapshot"
    assert sum(call["operation"] == "client.snapshot" for call in state["calls"]) == 2


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_later_read_rpc_does_not_reuse_the_previous_snapshot_on_drift(guarded_bridge, fault, code):
    adapter, state = guarded_bridge
    sealed = await discovery(adapter.client)
    state["supported"] = None
    def drift(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_read"] = drift
    step = {"id": "qa", "kind": "read", "tool": "mcb_qa_catalog", "args": {"section": "capabilities"}}
    result = await run_workflow(adapter.client, plan(snapshot_read(), step), anyio.Lock(), _discovery=sealed,
                                _require_background=True)
    assert result["status"] == "failed" and result["error"]["code"] == code
    assert state["actions"] == 0 and state["calls"][-1]["operation"] == "client.snapshot"


class QaClient:
    """In-memory transport fixture; all dynamic fields come from current state."""

    def __init__(self, statuses=()):
        self.configuration = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "qa-unit-test-token"})
        self.operations = []
        self.state = {"session": "session", "profile": "developer", "world": "world", "dimension": 0,
            "background": deepcopy(PROTECTED), "ageMillis": 0}
        self.statuses = iter(statuses)
        self.health = {"protocolVersion": 1, "sessionId": "session", "profile": "developer",
            "worldScanMinecraftAir": True, "background": deepcopy(PROTECTED), "capabilities": [
                "bridge.health", "client.snapshot", "client.background", "inventory.inspect", "qa.catalog",
                "qa.command", "qa.run", "qa.status", "world.scan"]}
        self.after_status = None

    async def call(self, operation, args, **kwargs):
        if "expected_credentials" in kwargs:
            assert kwargs["expected_credentials"] == self.configuration.read()
        self.operations.append((operation, deepcopy(args)))
        if operation == "bridge.health":
            return deepcopy(self.health)
        if operation == "client.snapshot":
            return {"sessionId": self.state["session"], "profile": self.state["profile"], "worldLoaded": True,
                "paused": False, "world": {"worldName": self.state["world"], "worldFolder": self.state["world"],
                    "dimension": self.state["dimension"], "singleplayer": True},
                "background": deepcopy(self.state["background"]), "player": {"serverFlight": {
                    "available": True, "source": "integrated_server_tick", "dimension": self.state["dimension"],
                    "ageMillis": self.state["ageMillis"], "worldTick": 100}}}
        if operation == "qa.status":
            value = deepcopy(next(self.statuses))
            if self.after_status:
                self.after_status(self)
            return value
        if operation in ("qa.run", "qa.command"):
            return status(True, True, state="running")
        if operation == "inventory.inspect":
            return {"slots": [{"index": index, "item": None} for index in range(41)], "truncated": False,
                    "totalSlots": 41, "cursorItem": None}
        if operation == "qa.catalog":
            if args["section"] == "capabilities":
                return {"runnerConfig": {"available": True, "partial": False, "gridOriginX": 0,
                    "gridOriginY": 64, "gridOriginZ": 0, "mode": "interactive", "turboMultiplier": 1,
                    "turboActive": False, "nativeBatchActive": False}}
            if args["section"] == "tests":
                return {"available": True, "hasMore": False, "entries": [{"id": SELECTOR, "template": "fixture",
                    "parameterized": False, "skippedAtDiscovery": False}]}
            return {"publicFixturePlan": None}
        raise AssertionError(operation)


def status(fresh=True, active=False, **updates):
    return {"runId": RUN_ID, "selector": SELECTOR, "selectedTests": 1, "selectedIds": [SELECTOR],
        "selectedIdsTruncated": False, "freshSinceStart": fresh, "nativeBatchActive": active,
        "selectionMatches": True, "state": "completed", **updates}


async def task_with_discovery(statuses=()):
    client = QaClient(statuses)
    task = qa._QaTask(client, {"expectedSessionId": "session", "expectedWorld": "world", "selector": SELECTOR},
        run_workflow, time.monotonic())
    task.dimension = 0
    task.client.ticket = status()
    health = await task.client.call("bridge.health", {})
    task._workflow_discovery = _workflow_discovery(task.client, task.client.credentials, health)
    return task, client


async def test_real_complete_first_preflight_bootstraps_discovery_before_later_plan_validation():
    client = QaClient()
    task = qa._QaTask(client, {"expectedSessionId": "session", "expectedWorld": "world", "selector": SELECTOR},
        run_workflow, time.monotonic())
    with pytest.raises(qa._QaFailure) as failure:
        await task.prepare()
    assert failure.value.code == "qa_fixture_plan_unavailable"
    assert task._workflow_discovery is not None
    assert sum(op == "bridge.health" for op, _ in client.operations) == 2
    assert sum(op == "qa.catalog" for op, _ in client.operations) == 3
    assert task.phases[0]["phase"] == "qa_preflight" and task.phases[0]["status"] == "completed"


@pytest.mark.parametrize("reason", ["mod_not_loaded", "mode_off", "public_api_unavailable"])
async def test_finite_task_without_qa_never_submits_or_promotes_missing_evidence_to_pass(reason):
    client = QaClient()
    original = client.call

    async def unavailable(operation, args, **kwargs):
        if operation == "qa.catalog":
            client.operations.append((operation, deepcopy(args)))
            return {"available": False, "reason": reason}
        return await original(operation, args, **kwargs)

    client.call = unavailable
    result = await qa.run_qa_test_once(client, {"expectedSessionId": "session", "expectedWorld": "world",
        "selector": SELECTOR, "requireFullFlow": True}, anyio.Lock())
    assert result["status"] == "unsupported"
    assert result["testPassed"] is False and result["fullFlowVerified"] is False
    assert result["runSubmissions"] == 0 and result["sideEffectsPossible"] is False
    assert result["outcomeUnknown"] is False and result["runId"] is None
    assert not any(operation in ("qa.run", "qa.command", "qa.cancel", "client.background", "world.scan")
                   for operation, _ in client.operations)
    # The optional test did not leave the normal client unusable.
    assert (await client.call("client.snapshot", {}))["worldLoaded"] is True


async def test_qa_sealed_discovery_is_immutable_and_reused_without_live_snapshot_reuse():
    task, client = await task_with_discovery()
    client.health["capabilities"].clear()
    client.health["profile"] = "player"
    with pytest.raises(dataclasses.FrozenInstanceError):
        task._workflow_discovery.session = "other"
    await task.workflow("first", [snapshot_read()])
    await task.discover_scan()
    await task.discover_scan()
    assert [op for op, _ in client.operations] == ["bridge.health", "client.snapshot", "client.snapshot", "client.snapshot"]


@pytest.mark.parametrize("fault", ["session", "profile", "world", "dimension", "ageMillis"])
async def test_cached_qa_scan_still_refuses_current_context_or_server_sample_drift(fault):
    task, client = await task_with_discovery()
    client.state[fault] = 180 if fault == "dimension" else 1001 if fault == "ageMillis" else "changed"
    with pytest.raises(qa._QaFailure) as failure:
        await task.discover_scan()
    assert failure.value.code == "qa_context_changed"
    assert [op for op, _ in client.operations] == ["bridge.health", "client.snapshot"]


@pytest.mark.parametrize("key,value", [("MCB_TOKEN", "changed-token"), ("MCB_URL", "http://127.0.0.1:25672")])
async def test_cached_qa_credentials_rotation_blocks_before_any_rpc(key, value):
    task, client = await task_with_discovery()
    client.configuration.environ[key] = value
    with pytest.raises(BridgeError) as failure:
        await task.discover_scan()
    assert failure.value.code == "SESSION_CHANGED"
    assert len(client.operations) == 1


async def test_custom_qa_runner_keeps_three_argument_contract_and_no_new_discovery():
    calls = []
    async def custom(client, arguments, lock):
        calls.append(arguments)
        return {"status": "completed"}
    client = QaClient()
    task = qa._QaTask(client, {"expectedSessionId": "session", "expectedWorld": "world", "selector": SELECTOR},
        custom, time.monotonic())
    await task.workflow("custom", [snapshot_read()])
    assert not task._native_workflow_runner and task._workflow_discovery is None
    assert len(calls) == 1 and not client.operations


async def test_completion_needs_fresh_and_inactive_on_the_same_guarded_observation(monkeypatch):
    task, client = await task_with_discovery([status(False, False), status(True, True), status(True, False)])
    sleeps = []
    async def pause(seconds):
        sleeps.append(seconds)
    monkeypatch.setattr(qa.anyio, "sleep", pause)
    await task.wait_completion(4, 434)
    assert sleeps == [.434, .434]
    assert [op for op, _ in client.operations] == ["bridge.health"] + ["qa.status", "client.snapshot"] * 3
    assert all(args["runId"] == RUN_ID for op, args in client.operations if op == "qa.status")
    assert task.phases[-1]["status"] == "completed"


@pytest.mark.parametrize("updates", [{"runId": "different"}, {"freshSinceStart": 1}, {"nativeBatchActive": 0},
    {"selectionMatches": False}, {"state": "running"}, {"selectedIds": ["test.finite:other"]}])
async def test_terminal_drift_or_untyped_status_cannot_complete(updates):
    task, client = await task_with_discovery([status(**updates)])
    with pytest.raises(qa._QaFailure):
        await task.wait_completion(2, 100)
    assert sum(op == "qa.status" for op, _ in client.operations) == 1
    assert task.phases[-1]["status"] == "failed"


@pytest.mark.parametrize("wrong_session", ["another-session", None, 1])
async def test_status_session_drift_is_rejected_immediately_without_later_reads(wrong_session):
    task, client = await task_with_discovery([status(sessionId=wrong_session)])
    with pytest.raises(BridgeError) as failure:
        await task.wait_completion(2, 100)
    assert failure.value.code == "SESSION_CHANGED"
    assert [op for op, _ in client.operations] == ["bridge.health", "qa.status"]
    assert task.phases[-1]["phase"] == "qa_run_and_wait"


@pytest.mark.parametrize("fault", ["session", "profile", "world", "dimension", "ageMillis", "background"])
async def test_even_terminal_status_still_needs_a_fresh_valid_after_snapshot(fault):
    task, client = await task_with_discovery([status()])
    def drift(current):
        if fault == "background":
            current.state["background"]["enabled"] = False
        else:
            current.state[fault] = 180 if fault == "dimension" else 1001 if fault == "ageMillis" else "changed"
    client.after_status = drift
    with pytest.raises((qa._QaFailure, BridgeError)):
        await task.wait_completion(2, 100)
    assert [op for op, _ in client.operations] == ["bridge.health", "qa.status", "client.snapshot"]
    assert task.phases[-1]["status"] == "failed"


async def test_one_submission_and_no_duplicate_terminal_poll_after_combined_wait():
    task, client = await task_with_discovery([status()])
    await task.execute()
    assert task.client.action_attempted is True
    assert sum(op == "qa.command" for op, _ in client.operations) == 1
    assert task.polling["statusPolls"] == 1
    assert task.terminal == status()
    assert task.client.request_reserve == 0


async def test_completion_preserves_reserved_after_requests_without_replaying_run(monkeypatch):
    task, client = await task_with_discovery([status(False, True), status(False, True)])
    task.client.maximum = 5
    task.client.request_reserve = 2
    async def pause(seconds):
        pass
    monkeypatch.setattr(qa.anyio, "sleep", pause)
    with pytest.raises(qa._QaFailure) as failure:
        await task.wait_completion(4, 100)
    assert failure.value.code == "qa_request_budget_reserved"
    assert task.client.calls == 3 and task.client.maximum - task.client.calls == 2
    assert sum(op == "qa.status" for op, _ in client.operations) == 1
    assert not task.client.action_attempted


@pytest.mark.parametrize("fault", ["reused_evidence_uuid", "wrong_run_ticket", "only_84_stable_ticks"])
def test_full_flow_acceptance_still_requires_new_uuid_exact_run_and_85_actual_ticks(fault):
    task, evidence, _ = accepted_fixture()
    if fault == "reused_evidence_uuid":
        task.flow_before["evidenceId"] = EVIDENCE_ID
    elif fault == "wrong_run_ticket":
        evidence["runId"] = RUN_ID
    else:
        evidence["stages"][-2]["worldTick"] -= 1
    task.verify_flow(evidence)
    assert not task.full_flow["verified"]
    with pytest.raises(qa._QaFailure):
        task.accept()
    assert task.quantitative_summary is None


@pytest.mark.parametrize("receipt", ["prepare_denial", "controlled_denial", "unknown"])
async def test_submission_denial_is_distinct_from_unknown_and_never_replayed(receipt):
    task, client = await task_with_discovery([status()])
    task.client.ticket = None  # Submission has not produced an owning ticket yet.
    async def refused(operation, args, **kwargs):
        assert operation == "qa.run"
        if receipt == "controlled_denial":
            return {"ok": False, "submitted": False, "sideEffectsPossible": False, "outcomeUnknown": False,
                    "errorCode": "qa_policy_denied", "error": "Unreviewed callback"}
        if receipt == "prepare_denial":
            raise BridgeError("policy_denied", "QA off", original_action_executed=False, outcome_unknown=False)
        raise BridgeError("timeout", "No result")
    client.call = refused
    with pytest.raises(BridgeError):
        await task.client.call("qa.run", {"selector": SELECTOR, "expectedWorld": "world"})
    result = task.result(qa._QaFailure("test_failure", "test"))
    assert result["submissionRejected"] is (receipt != "unknown")
    assert result["sideEffectsPossible"] is (receipt == "unknown")
    assert result["outcomeUnknown"] is (receipt == "unknown")
    assert result["runSubmissions"] == (1 if receipt == "unknown" else 0)
    with pytest.raises(qa._QaFailure, match="at most once"):
        await task.client.call("qa.run", {"selector": SELECTOR, "expectedWorld": "world"})
