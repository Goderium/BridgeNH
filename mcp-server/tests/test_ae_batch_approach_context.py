"""Batch discovery removes metadata RPCs, never the current native pose/target guards."""
from types import SimpleNamespace

import anyio
import pytest

from minecraft_codex_bridge import ae_tasks
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.interaction_approach import run_interaction_approach
from minecraft_codex_bridge.workflows import _workflow_discovery
from test_shared_interaction_approach import Client, args


class BatchClient(Client):
    def __init__(self):
        super().__init__()
        self.credentials = "runtime-1"
        self.configuration = SimpleNamespace(read=lambda: self.credentials)
        self.screen = ""
        self.closed = 0
        self.change = None

    def snapshot(self):
        value = super().snapshot()
        value.update(screen=self.screen, screenOpen=bool(self.screen),
                     background={"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False})
        if self.change:
            self.change(value)
        return value

    async def control(self, client, operation, payload):
        if operation == "gui.close":
            self.closed += 1
            self.screen = ""
            return {"closed": True}
        return await super().control(client, operation, payload)

    async def call(self, operation, payload, *, expected_credentials):
        assert expected_credentials == self.credentials
        return await self.invoke(operation, payload)

    async def discover(self):
        health = await self.invoke("bridge.health", {})
        health.update(protocolVersion=1, profile="developer", interactionBuildEnvelopeVersion=1)
        health["capabilities"].append("client.snapshot")
        discovery = _workflow_discovery(self, self.credentials, health)
        self.calls.clear()
        return discovery, health


async def approach(client, discovery):
    return await run_interaction_approach(client, args(), anyio.Lock(), control=client.control,
                                         _discovery=discovery, _close_gui=True)


async def test_batch_reuses_only_sealed_capabilities_and_still_observes_each_target():
    client = BatchClient()
    discovery, health = await client.discover()
    health["approachSharedTargetsVersion"] = 0
    health["capabilities"].clear()
    for _ in range(3):
        result = await approach(client, discovery)
        assert result["status"] == "completed"
    operations = [operation for operation, _ in client.calls]
    assert operations == ["client.snapshot", "player.plan_approach"] * 3
    assert client.closed == 0


async def test_a_gui_opened_between_parts_is_closed_once_using_fresh_state():
    client = BatchClient()
    discovery, _ = await client.discover()
    await approach(client, discovery)
    client.screen = "appeng.client.gui.GuiTerminal"
    await approach(client, discovery)
    await approach(client, discovery)
    assert client.closed == 1
    assert [op for op, _ in client.calls].count("client.snapshot") == 4
    assert not client.moves and not client.uses


@pytest.mark.parametrize("fault,code", [
    ("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"), ("world", "WORLD_CHANGED"),
    ("background", "TAKEOVER_LOST"), ("dimension", "DIMENSION_CHANGED"),
    ("credentials", "SESSION_CHANGED"),
])
async def test_cached_capabilities_never_allow_a_stale_batch_context(fault, code):
    client = BatchClient()
    discovery, _ = await client.discover()
    await approach(client, discovery)
    client.calls.clear()
    client.screen = "SomeGui"
    if fault == "credentials":
        client.credentials = "runtime-2"
    else:
        def drift(value):
            if fault == "session": value["sessionId"] = "new-session"
            elif fault == "profile": value["profile"] = "player"
            elif fault == "world": value["world"]["worldName"] = "Other"
            elif fault == "dimension": value["world"]["dimension"] = 0
            else: value["background"]["enabled"] = False
        client.change = drift
    with pytest.raises(BridgeError) as error:
        await approach(client, discovery)
    assert error.value.code == code
    assert not client.closed and not client.moves and not client.uses
    assert all(op == "client.snapshot" for op, _ in client.calls)


async def test_install_delegates_conditional_close_and_discovery_to_shared_approach(monkeypatch):
    task = ae_tasks.AeTask(None, args(), None)
    task.discovery = object()
    calls = []
    async def step(*args, **kwargs): pytest.fail("Closed placement must not add a per-part close workflow.")
    async def shared(client, arguments, lock, **kwargs):
        calls.append(kwargs)
        return {"status": "completed"}
    monkeypatch.setattr(task, "step", step)
    monkeypatch.setattr(ae_tasks, "run_interaction_approach", shared)
    for target in task.args["targets"]:
        await task.approach(target, open_gui=False)
    assert len(calls) == 2
    assert all(call["_discovery"] is task.discovery and call["_close_gui"] is True for call in calls)
