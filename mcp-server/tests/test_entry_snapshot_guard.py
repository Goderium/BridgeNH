"""The first declared live snapshot can guard entry; takeover still needs its own guard."""

import pytest

from test_guarded_rpc import guarded_bridge, plan, action, read, PROTECTED


def snapshot_step(kind):
    step = {"id": "flight", "kind": kind, "tool": "mcb_snapshot", "fields": ["player.flying"]}
    if kind == "wait":
        step.update(condition={"path": "player.flying", "operator": "equals", "value": True}, interval_ms=50)
    return step


@pytest.mark.parametrize("kind", ["read", "wait"])
@pytest.mark.parametrize("guarded", [False, True])
async def test_declared_first_snapshot_serves_entry_without_losing_native_or_final_guards(guarded_bridge, kind, guarded):
    adapter, state = guarded_bridge
    if not guarded:
        state["supported"] = None
    result = await adapter.call_tool("mcb_sequence", plan(snapshot_step(kind), action()))
    data = result.structuredContent["data"]
    assert data["status"] == "completed" and data["acknowledgedActions"] == 1
    assert data["steps"][0]["observation"]["values"]["player.flying"] is True
    operations = [call["operation"] for call in state["calls"]]
    assert operations.count("client.snapshot") == (1 if guarded else 3)
    assert operations.count("bridge.health") == 2 and state["actions"] == 1
    assert operations.index("client.snapshot") < operations.index("player.hotbar")
    assert data["steps"][1]["observation"]["values"]["serverConfirmed"] is False


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
@pytest.mark.parametrize("kind", ["read", "wait"])
async def test_first_snapshot_rejects_live_context_drift_before_any_native_action(guarded_bridge, monkeypatch, fault, code, kind):
    adapter, state = guarded_bridge
    original_call = adapter.client.call
    async def changed_after_health(operation, arguments, **options):
        value = await original_call(operation, arguments, **options)
        if operation == "bridge.health":
            if fault == "background":
                state["background"]["enabled"] = False
            else:
                state[fault] = "changed"
        return value
    monkeypatch.setattr(adapter.client, "call", changed_after_health)
    result = await adapter.call_tool("mcb_sequence", plan(snapshot_step(kind), action()))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["error"]["code"] == code
    assert data["steps"][0]["status"] == "failed" and data["steps"][1]["status"] == "not_executed"
    assert state["actions"] == 0 and data["dispatchedActions"] == 0 and not data["outcomeUnknown"]
    assert not any(call["operation"] in ("player.hotbar", "client.background") for call in state["calls"])


async def test_unprotected_entry_keeps_guard_before_establishing_takeover(guarded_bridge):
    adapter, state = guarded_bridge
    state["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    result = await adapter.call_tool("mcb_sequence", plan(snapshot_step("read"), action()))
    assert result.structuredContent["data"]["status"] == "completed"
    operations = [call["operation"] for call in state["calls"]]
    assert operations[:3] == ["bridge.health", "client.snapshot", "client.background"]
    assert operations.count("client.snapshot") == 2 and operations.count("client.background") == 1
    assert state["background"] == PROTECTED and state["actions"] == 1


async def test_world_drift_blocks_takeover_before_declared_snapshot(guarded_bridge, monkeypatch):
    adapter, state = guarded_bridge
    state["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    original_call = adapter.client.call
    async def changed_after_health(operation, arguments, **options):
        value = await original_call(operation, arguments, **options)
        if operation == "bridge.health":
            state["world"] = "changed"
        return value
    monkeypatch.setattr(adapter.client, "call", changed_after_health)
    result = await adapter.call_tool("mcb_sequence", plan(snapshot_step("read"), action()))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["error"]["code"] == "WORLD_CHANGED"
    assert all(step["status"] == "not_executed" for step in data["steps"])
    assert state["actions"] == 0 and not data["sideEffectsPossible"]
    assert not any(call["operation"] == "client.background" for call in state["calls"])


@pytest.mark.parametrize("first", [read(), action()])
async def test_first_advertised_context_rpc_serves_entry_with_its_own_live_guard(guarded_bridge, first):
    adapter, state = guarded_bridge
    result = await adapter.call_tool("mcb_sequence", plan(first))
    assert result.structuredContent["data"]["status"] == "completed"
    operations = [call["operation"] for call in state["calls"]]
    assert operations[:2] == ["bridge.health", "inventory.inspect" if first["kind"] == "read" else "player.hotbar"]
    assert operations.count("client.snapshot") == 0


async def test_read_only_first_snapshot_still_checks_expected_world_without_takeover(guarded_bridge):
    adapter, state = guarded_bridge
    state["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    result = await adapter.call_tool("mcb_sequence", plan(snapshot_step("read")))
    data = result.structuredContent["data"]
    assert data["status"] == "completed" and state["actions"] == 0
    assert [call["operation"] for call in state["calls"]].count("client.snapshot") == 1
    assert not any(call["operation"] == "client.background" for call in state["calls"])
