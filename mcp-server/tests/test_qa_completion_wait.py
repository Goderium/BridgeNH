"""Optional private completion waiting preserves actual state and acceptance."""

import time

import pytest

from minecraft_codex_bridge import qa_tasks as qa
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_qa_task_discovery import task_with_discovery, status


def private_capabilities(**updates):
    return {"available": True, "nativeRunnerContract": qa.PRIVATE_CONTRACT, "normalWorldTicks": True,
        "ownsHqaNativeRunner": False, "usesPublicInstanceLifecycle": True, "privateDimension": True,
        "fixturePlanAvailable": True, "preClear": False, "minimumClearY": 2,
        "clearRequiresOwnedUnchangedFixture": True, "active": False, "mode": "normal_world", "turbo": False,
        "defaultOrigin": {"x": 0, "y": 3, "z": 0}, "boundedStatusWait": True, "maxStatusWaitMillis": 8000,
        "statusWaitPrivateOnly": True, "runnerConfig": {"source": qa.PRIVATE_SOURCE, "mode": "normal_world",
            "turbo": False, "active": False, "maxSelectedTests": 10, "defaultOrigin": {"x": 0, "y": 3, "z": 0}},
        **updates}


def private_status(**updates):
    return status(source=qa.PRIVATE_SOURCE, nativeRunnerContract=qa.PRIVATE_CONTRACT,
        schemaVersion=1, dimension=180, providerClass=qa.PRIVATE_PROVIDER, sessionId="session", owned=False,
        nativeRunOwned=False, **updates)


async def private_task(statuses):
    task, client = await task_with_discovery(statuses)
    task.dimension, task.provider_class = 180, qa.PRIVATE_PROVIDER
    client.state["dimension"] = 180
    task.config(private_capabilities())
    return task, client


@pytest.mark.parametrize("arguments", [{}, {"runId": "actual"}, {"waitMillis": 0},
    {"runId": "actual", "waitMillis": 1}, {"runId": "actual", "waitMillis": 8000}])
def test_wait_schema_accepts_legacy_and_explicit_bounded_same_run_reads(arguments):
    _validate("mcb_qa_status", arguments)
    tool = TOOL_BY_NAME["mcb_qa_status"]
    assert tool.operation == "qa.status" and tool.read_only is True and tool.takeover is False and tool.idempotent is None


@pytest.mark.parametrize("arguments", [{"waitMillis": 1}, {"waitMillis": 8000},
    {"runId": "actual", "waitMillis": -1}, {"runId": "actual", "waitMillis": 8001},
    {"runId": "actual", "waitMillis": True}, {"runId": "actual", "waitMillis": 1.5},
    {"runId": "actual", "waitMillis": "8000"}])
def test_wait_schema_rejects_unbound_untyped_or_excessive_wait(arguments):
    with pytest.raises(BridgeError) as failure:
        _validate("mcb_qa_status", arguments)
    assert failure.value.code == "INVALID_ARGUMENTS"


async def test_supported_private_completion_uses_one_bounded_status_then_fresh_snapshot():
    task, client = await private_task([private_status(statusWait={"supported": True, "completed": True})])
    await task.wait_completion(4, 434)
    statuses = [args for op, args in client.operations if op == "qa.status"]
    assert len(statuses) == 1 and statuses[0]["waitMillis"] == 7500
    assert client.operations[-1][0] == "client.snapshot" and task.phases[-1]["status"] == "completed"


@pytest.mark.parametrize("updates", [{"boundedStatusWait": False}, {"boundedStatusWait": 1},
    {"maxStatusWaitMillis": None}, {"maxStatusWaitMillis": True}, {"maxStatusWaitMillis": 0},
    {"maxStatusWaitMillis": 8001}, {"statusWaitPrivateOnly": False}, {"statusWaitPrivateOnly": 1}])
async def test_absent_or_malformed_optional_capability_preserves_legacy_status_arguments(updates):
    task, _ = await private_task([])
    task.config(private_capabilities(**updates))
    assert task._status_wait_max_ms == 0 and set(task.status_arguments()) == {"runId"}


async def test_overworld_does_not_inherit_private_wait_support_even_if_flag_is_set():
    task, _ = await task_with_discovery([])
    task._status_wait_max_ms = 8000
    assert set(task.status_arguments()) == {"runId"}


async def test_advertised_smaller_limit_and_after_read_time_reserve_bound_each_request():
    task, _ = await private_task([])
    task.config(private_capabilities(maxStatusWaitMillis=2000))
    assert task.status_arguments()["waitMillis"] == 2000
    task.deadline = time.monotonic() + 1.7
    assert 0 < task.status_arguments()["waitMillis"] <= 200
    task.deadline = time.monotonic() + 1.2
    assert set(task.status_arguments()) == {"runId"}


async def test_status_wait_receipt_cannot_substitute_for_fresh_inactive_native_state(monkeypatch):
    task, client = await private_task([
        private_status(freshSinceStart=False, nativeBatchActive=False, statusWait={"completed": True}),
        private_status(statusWait={"completed": True})])
    sleeps = []
    async def pause(seconds):
        sleeps.append(seconds)
    monkeypatch.setattr(qa.anyio, "sleep", pause)
    await task.wait_completion(4, 434)
    assert sleeps == [.434]
    assert sum(op == "qa.status" for op, _ in client.operations) == 2
    assert sum(op == "client.snapshot" for op, _ in client.operations) == 2
    assert not task.client.action_attempted


async def test_timed_out_wait_retains_the_same_ticket_and_finite_rpc_allowance(monkeypatch):
    task, client = await private_task([
        private_status(freshSinceStart=True, nativeBatchActive=True, state="running",
            statusWait={"completed": False, "timedOut": True}), private_status()])
    async def pause(seconds):
        pass
    monkeypatch.setattr(qa.anyio, "sleep", pause)
    await task.wait_completion(2, 434)
    queries = [args for op, args in client.operations if op == "qa.status"]
    assert len(queries) == 2 and queries[0]["runId"] == queries[1]["runId"]
    assert all(0 < row["waitMillis"] <= 7500 for row in queries)
    assert task.client.calls == 5 and not task.client.action_attempted
