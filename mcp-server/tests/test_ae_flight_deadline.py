import pytest

from minecraft_codex_bridge import ae_tasks
from minecraft_codex_bridge.bridge import BridgeError


@pytest.mark.parametrize("strategy,remaining,expected", [
    ("ability_flight", 58000, 58000), ("ability_flight", 70000, 60000),
    ("ability_flight", 1200, 1200), ("walking", 58000, 58000),
    ("walking", 70000, 60000), ("developer_teleport", 1200, 1200),
    ("developer_teleport", 58000, 15000), ("walking", 1200, 1200),
])
@pytest.mark.parametrize("open_gui", [False, True])
async def test_goal_native_travel_reuses_remaining_budget_once(monkeypatch, strategy, remaining, expected, open_gui):
    task = ae_tasks.AeTask(None, dict(expectedSessionId="s", expectedWorld="w", dimension=0, strategy=strategy), None)
    calls = []
    async def step(*args, **kwargs): calls.append(args[0])
    async def empty(): calls.append("empty")
    async def approach(client, args, lock, **kwargs):
        calls.append(args)
        return dict(status="completed", interactionEffectConfirmed=True)
    monkeypatch.setattr(task, "step", step)
    monkeypatch.setattr(task, "empty_hand", empty)
    monkeypatch.setattr(task, "remaining", lambda: calls.append("budget") or remaining)
    monkeypatch.setattr(ae_tasks, "run_interaction_approach", approach)
    await task.approach({"x": 1}, open_gui=open_gui)
    movements = [c for c in calls if isinstance(c, dict)]
    assert len(movements) == 1 and movements[0]["timeout_ms"] == expected
    assert movements[0]["strategy"] == strategy
    assert ("openGui" in movements[0]) == open_gui
    if open_gui:
        assert calls.index("budget") > calls.index("empty")


async def test_failed_flight_is_not_replayed(monkeypatch):
    task = ae_tasks.AeTask(None, dict(expectedSessionId="s", expectedWorld="w", dimension=0, strategy="ability_flight"), None)
    calls = []
    async def step(*args, **kwargs): pass
    async def approach(*args, **kwargs):
        calls.append(1)
        return dict(status="failed")
    monkeypatch.setattr(task, "step", step)
    monkeypatch.setattr(ae_tasks, "run_interaction_approach", approach)
    with pytest.raises(BridgeError, match="Actual approach"):
        await task.approach({"x": 1}, open_gui=False)
    assert calls == [1]
