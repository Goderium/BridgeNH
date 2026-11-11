"""Task-local capability reuse removes RPCs while retaining fresh native guards."""

import dataclasses
import time

import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.construction_tasks import _ConstructionTask, run_blueprint_build
from minecraft_codex_bridge.workflows import _workflow_discovery, run_workflow
from test_guarded_rpc import guarded_bridge, action, read, plan, PROTECTED
from test_construction_tasks import native_construction, declaration


def operations(state):
    return [call["operation"] for call in state["calls"]]


async def task_from(bridge):
    adapter, state = bridge
    task = _ConstructionTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world",
        "dimension": 0, "timeout_ms": 5000}, run_workflow, started=time.monotonic())
    health = await task.call("bridge.health", {})
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health)
    return task, state, health


@pytest.mark.parametrize("kind", ["read", "wait", "action"])
async def test_default_workflow_merges_first_context_guard_but_still_discovers_each_run(guarded_bridge, kind):
    adapter, state = guarded_bridge
    first = action() if kind == "action" else read()
    if kind == "wait":
        first.update(kind="wait", condition={"path": "selectedHotbarSlot", "operator": "equals", "value": 1})
    for _ in range(2):
        report = await run_workflow(adapter.client, plan(first), anyio.Lock())
        assert report["status"] == "completed"
    assert operations(state).count("bridge.health") == 4
    assert operations(state).count("client.snapshot") == 0


@pytest.mark.parametrize("first_kind", ["read", "action"])
@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_context_entry_drift_blocks_native_mutation_without_replay(guarded_bridge, monkeypatch, first_kind, fault, code):
    adapter, state = guarded_bridge
    original = adapter.client.call
    async def drift(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "bridge.health":
            if fault == "background":
                state["background"]["enabled"] = False
            else:
                state[fault] = "changed"
        return result
    monkeypatch.setattr(adapter.client, "call", drift)
    first = read() if first_kind == "read" else action("first")
    report = await run_workflow(adapter.client, plan(first, action("later")), anyio.Lock())
    assert report["status"] == "failed" and report["error"]["code"] == code
    assert state["actions"] == 0 and not report["outcomeUnknown"]
    assert report["steps"][1]["status"] == "not_executed"
    assert "client.background" not in operations(state)
    assert operations(state).count("player.hotbar") == (1 if first_kind == "action" else 0)


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_bad_first_after_context_cannot_fall_back_or_allow_a_write(guarded_bridge, fault):
    adapter, state = guarded_bridge
    state["bad_context"] = fault
    report = await run_workflow(adapter.client, plan(read(), action()), anyio.Lock())
    assert report["status"] == "failed" and report["error"]["code"] == "MALFORMED_RESPONSE"
    assert state["actions"] == 0 and report["steps"][1]["status"] == "not_executed"
    assert operations(state).count("inventory.inspect") == 1
    assert "client.snapshot" not in operations(state)


async def test_construction_reuses_immutable_discovery_and_checks_fresh_final_snapshots(guarded_bridge):
    task, state, health = await task_from(guarded_bridge)
    # Mutating the input health object cannot expand the sealed whitelist or change pinned expectations.
    health["capabilities"].clear()
    health["contextRpc"]["actions"].append("client.exit")
    health.update(sessionId="different", profile="player", background={"enabled": False})
    with pytest.raises(dataclasses.FrozenInstanceError):
        task._workflow_discovery.session = "different"
    await task.workflow("first", [action()])
    await task.workflow("second", [read(), action("again")])
    assert task._workflow_background_required is True and state["actions"] == 2
    assert operations(state) == ["bridge.health", "client.snapshot", "player.hotbar", "client.snapshot",
        "inventory.inspect", "player.hotbar", "client.snapshot"]
    assert all(call["includeContext"]["requireBackground"] is True for call in state["calls"] if "includeContext" in call)


async def test_task_establishes_background_once_then_refuses_loss_even_on_read_segments(guarded_bridge):
    task, state, _ = await task_from(guarded_bridge)
    state["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    await task.workflow("first", [action()])
    assert task._workflow_background_required and operations(state).count("client.background") == 1
    state["background"]["enabled"] = False
    with pytest.raises(BridgeError) as error:
        await task.workflow("later_read", [read()])
    assert error.value.code == "TAKEOVER_LOST"
    assert operations(state).count("client.background") == 1 and state["actions"] == 1


@pytest.mark.parametrize("first_kind", ["read", "action"])
@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_cached_segments_reject_context_drift_without_new_health_or_takeover(guarded_bridge, first_kind, fault, code):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("first", [action()])
    if fault == "background":
        state["background"]["enabled"] = False
    else:
        state[fault] = "changed"
    with pytest.raises(BridgeError) as error:
        await task.workflow("next", [read() if first_kind == "read" else action("second"), action("later")])
    assert error.value.code == code and state["actions"] == 1
    assert operations(state).count("bridge.health") == 1
    assert "client.background" not in operations(state)


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_cached_finish_keeps_a_fresh_guard_after_the_last_before_action(guarded_bridge, fault, code):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("first", [action()])
    def drift(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_action"] = drift
    with pytest.raises(BridgeError) as error:
        await task.workflow("next", [action("last")])
    assert error.value.code == code and state["actions"] == 2
    assert operations(state)[-1] == "client.snapshot" and operations(state).count("bridge.health") == 1


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_cached_unknown_write_context_is_recorded_once_and_never_replayed(guarded_bridge, fault):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("first", [action()])
    state["bad_context"] = fault
    with pytest.raises(BridgeError) as error:
        await task.workflow("uncertain", [action("second"), action("later")])
    assert error.value.code == "MALFORMED_RESPONSE" and error.value.details["outcome_unknown"] is True
    assert state["actions"] == 2 and operations(state).count("player.hotbar") == 2
    assert task.last_action["outcomeUnknown"] is True
    assert operations(state).count("bridge.health") == 1


@pytest.mark.parametrize("supported", [None, {"version": True},
    {"version": 1, "reads": "all", "actions": []},
    {"version": 1, "reads": ["client.exit", "client.snapshot"], "actions": ["player.teleport"]}])
async def test_cache_never_expands_or_invents_context_support(guarded_bridge, supported):
    adapter, state = guarded_bridge
    state["supported"] = supported
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("first", [read(), action()])
    assert state["actions"] == 1 and not any("includeContext" in call for call in state["calls"])
    assert operations(state).count("client.snapshot") == 4


@pytest.mark.parametrize("change", ["client", "configuration", "url", "token", "expected_session"])
async def test_private_discovery_rejects_changed_client_runtime_and_session_before_rpc(guarded_bridge, change):
    task, state, _ = await task_from(guarded_bridge)
    discovered = task._workflow_discovery
    client = task.tracked_client
    args = plan(action())
    if change == "client":
        client = guarded_bridge[0].client
    elif change == "configuration":
        client.configuration = Configuration(dict(client.configuration.environ))
    elif change == "expected_session":
        args["expectedSessionId"] = "changed"
    else:
        client.configuration.environ["MCB_URL" if change == "url" else "MCB_TOKEN"] = "http://127.0.0.1:25672" if change == "url" else "rotated"
    before = len(state["calls"])
    report = await run_workflow(client, args, anyio.Lock(), _discovery=discovered, _require_background=True)
    assert report["status"] == "failed" and report["error"]["code"] == "SESSION_CHANGED"
    assert len(state["calls"]) == before and state["actions"] == 0


async def test_cached_unsupported_operation_is_refused_before_any_native_action(guarded_bridge):
    task, state, health = await task_from(guarded_bridge)
    health["capabilities"].remove("player.hotbar")
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health)
    with pytest.raises(BridgeError) as error:
        await task.workflow("missing", [action()])
    assert error.value.code == "UNSUPPORTED_CAPABILITY" and state["actions"] == 0
    assert operations(state) == ["bridge.health"]


async def test_custom_construction_runner_keeps_its_original_three_argument_contract(guarded_bridge):
    adapter, _ = guarded_bridge
    calls = []
    async def custom(client, args, lock):
        calls.append(args)
        return {"status": "completed", "steps": [], "sideEffectsPossible": False}
    task = _ConstructionTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world"}, custom, started=time.monotonic())
    assert not task._native_workflow_runner and task._workflow_discovery is None
    await task.workflow("custom", [read()])
    assert len(calls) == 1 and "_discovery" not in calls[0]


async def test_native_runner_without_construction_preflight_keeps_ordinary_discovery(guarded_bridge):
    adapter, state = guarded_bridge
    task = _ConstructionTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world",
        "timeout_ms": 5000}, run_workflow, started=time.monotonic())
    await task.workflow("first", [action()])
    await task.workflow("second", [action("again")])
    assert task._workflow_discovery is None and task._workflow_background_required is False
    assert state["actions"] == 2 and operations(state).count("bridge.health") == 4


async def test_default_native_construction_keeps_formation_and_uses_one_health_discovery(native_construction):
    client, state = native_construction
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "completed" and result["formationVerified"] and state["formed"]
    assert state["construct_count"] == 1 and state["rechecks"] > 0
    assert operations(state).count("bridge.health") == 1
