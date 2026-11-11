"""Real mocked transport exercises recipe discovery reuse and live guard failures."""

import dataclasses
import json

import pytest

from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, run_recipe_once
from minecraft_codex_bridge.workflows import _workflow_discovery, run_workflow
from test_guarded_rpc import action, guarded_bridge, read
from test_recipe_tasks import declaration, native_bridge


def operations(state):
    return [call["operation"] for call in state["calls"]]


async def discovered_task(bridge):
    adapter, state = bridge
    task = _RecipeTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world",
        "dimension": 0, "timeout_ms": 5000}, run_workflow)
    health = await task.call("bridge.health", {})
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health)
    return task, state, health


async def test_complete_native_recipe_discovers_once_and_keeps_real_acceptance(native_bridge):
    import anyio
    client, state = native_bridge
    result = await run_recipe_once(client, declaration(), anyio.Lock(), runner=run_workflow)
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputDelta"] == 1
    assert result["acceptance"]["consumed"] == 8
    assert result["acceptance"]["catalystRetained"] is True
    assert state["output"] == 5 and state["input_material"] == 0 and state["input_circuit"] == 1
    assert not state["allowed"] and not state["core"]
    assert operations(state).count("bridge.health") == 1
    assert len(state["queries"]) == 1
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0]
    assert result["timings_ms"]["total"] == result["taskElapsed_ms"]
    print("offline_complete_recipe=" + json.dumps({"healthCalls": 1, "rpcCount": len(state["calls"]),
        "outputDelta": 1, "consumed": 8, "nativePowerActions": 2}))


async def test_recipe_discovery_is_immutable_and_keeps_each_action_before_and_final_after(guarded_bridge):
    task, state, health = await discovered_task(guarded_bridge)
    health["capabilities"].clear()
    health["contextRpc"]["actions"].append("client.exit")
    health.update(sessionId="changed", profile="player", background={"enabled": False})
    with pytest.raises(dataclasses.FrozenInstanceError):
        task._workflow_discovery.session = "changed"
    await task.workflow("first", [action()])
    await task.workflow("next", [read(), action("again")])
    assert task._workflow_background_required and state["actions"] == 2
    assert operations(state) == ["bridge.health", "client.snapshot", "player.hotbar", "client.snapshot",
        "inventory.inspect", "player.hotbar", "client.snapshot"]
    wrapped = [call for call in state["calls"] if "includeContext" in call]
    assert [call["operation"] for call in wrapped] == ["player.hotbar", "inventory.inspect", "player.hotbar"]
    assert all(call["includeContext"]["requireBackground"] is True for call in wrapped)


@pytest.mark.parametrize("first_kind", ["read", "action"])
@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_recipe_segments_refuse_current_context_drift_before_native_mutation(guarded_bridge, first_kind, fault, code):
    task, state, _ = await discovered_task(guarded_bridge)
    await task.workflow("first", [action()])
    if fault == "background":
        state["background"]["enabled"] = False
    else:
        state[fault] = "changed"
    with pytest.raises(BridgeError) as error:
        await task.workflow("later", [read() if first_kind == "read" else action("second"), action("never")])
    assert error.value.code == code and state["actions"] == 1
    assert operations(state).count("bridge.health") == 1
    assert "client.background" not in operations(state)


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_recipe_finish_is_fresh_after_last_native_action(guarded_bridge, fault, code):
    task, state, _ = await discovered_task(guarded_bridge)
    await task.workflow("first", [action()])
    def drift(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_action"] = drift
    with pytest.raises(BridgeError) as error:
        await task.workflow("last", [action("last")])
    assert error.value.code == code and state["actions"] == 2
    assert operations(state)[-1] == "client.snapshot"
    assert operations(state).count("bridge.health") == 1


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_bad_recipe_after_receipt_blocks_later_action_without_fallback(guarded_bridge, fault):
    task, state, _ = await discovered_task(guarded_bridge)
    await task.workflow("first", [action()])
    state["bad_context"] = fault
    before = len(state["calls"])
    with pytest.raises(BridgeError) as error:
        await task.workflow("malformed", [read(), action("never")])
    assert error.value.code == "MALFORMED_RESPONSE" and state["actions"] == 1
    assert operations(state)[before:] == ["inventory.inspect"]


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_unknown_recipe_action_keeps_one_receipt_and_never_replays(guarded_bridge, fault):
    task, state, _ = await discovered_task(guarded_bridge)
    await task.workflow("first", [action()])
    state["bad_context"] = fault
    with pytest.raises(BridgeError) as error:
        await task.workflow("uncertain", [action("once"), action("never")])
    assert error.value.code == "MALFORMED_RESPONSE" and error.value.details["outcome_unknown"] is True
    assert state["actions"] == 2 and operations(state).count("player.hotbar") == 2
    assert task.last_action["outcomeUnknown"] is True
    assert operations(state).count("bridge.health") == 1


async def test_failed_takeover_segment_keeps_protection_required_for_emergency_cleanup(guarded_bridge):
    task, state, _ = await discovered_task(guarded_bridge)
    state["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    state["bad_context"] = "missing"
    with pytest.raises(BridgeError):
        await task.workflow("uncertain_first", [action("once")])
    assert task._workflow_background_required and state["actions"] == 1
    assert operations(state).count("client.background") == 1
    state["bad_context"] = None
    state["background"]["enabled"] = False
    with pytest.raises(BridgeError) as error:
        await task.workflow("declared_cleanup", [action("stop")], emergency=True)
    assert error.value.code == "TAKEOVER_LOST" and state["actions"] == 1
    assert operations(state).count("client.background") == 1
    assert operations(state).count("bridge.health") == 1


@pytest.mark.parametrize("change", ["client", "configuration", "url", "token", "expected_session"])
async def test_recipe_discovery_rejects_identity_or_credentials_change_before_any_rpc(guarded_bridge, change):
    task, state, _ = await discovered_task(guarded_bridge)
    if change == "client":
        task.tracked_client = guarded_bridge[0].client
    elif change == "configuration":
        task.tracked_client.configuration = Configuration(dict(task.client.configuration.environ))
    elif change == "expected_session":
        task.args["expectedSessionId"] = "changed"
    else:
        task.client.configuration.environ["MCB_URL" if change == "url" else "MCB_TOKEN"] = "http://127.0.0.1:25672" if change == "url" else "rotated"
    before = len(state["calls"])
    with pytest.raises(BridgeError) as error:
        await task.workflow("blocked", [action()])
    assert error.value.code == "SESSION_CHANGED" and len(state["calls"]) == before and state["actions"] == 0


@pytest.mark.parametrize("supported", [None, {"version": True}, {"version": 1, "reads": "all", "actions": []}])
async def test_unsupported_context_keeps_live_legacy_snapshots(guarded_bridge, supported):
    adapter, state = guarded_bridge
    state["supported"] = supported
    task, state, _ = await discovered_task(guarded_bridge)
    await task.workflow("first", [read(), action()])
    assert state["actions"] == 1 and not any("includeContext" in call for call in state["calls"])
    assert operations(state).count("client.snapshot") == 4
    assert operations(state).count("bridge.health") == 1


async def test_custom_recipe_runner_keeps_three_argument_contract(guarded_bridge):
    adapter, _ = guarded_bridge
    calls = []
    async def custom(client, arguments, lock):
        calls.append(arguments)
        return {"status": "completed", "steps": [], "sideEffectsPossible": False}
    task = _RecipeTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world"}, custom)
    await task.workflow("custom", [read()])
    assert not task._native_workflow_runner and task._workflow_discovery is None
    assert len(calls) == 1 and "_discovery" not in calls[0]


async def test_recipe_helper_before_preflight_retains_ordinary_discovery(guarded_bridge):
    adapter, state = guarded_bridge
    task = _RecipeTask(adapter.client, {"expectedSessionId": "session", "expectedWorld": "world"}, run_workflow)
    await task.workflow("first", [action()])
    await task.workflow("next", [action("again")])
    assert task._workflow_discovery is None and not task._workflow_background_required
    assert operations(state).count("bridge.health") == 4 and state["actions"] == 2
