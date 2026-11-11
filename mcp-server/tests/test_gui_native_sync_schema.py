"""Bounded standalone GUI acceptance shares the native approach contract."""
import pytest
from jsonschema import Draft202012Validator
from minecraft_codex_bridge.tools import TOOLS

SCHEMA = next(t.schema for t in TOOLS if t.name == "mcb_gui")

@pytest.mark.parametrize("args", [{}, {"waitFor": {}}, {"waitFor": {"timeoutMillis": 6500,
    "conditions": [{"path": "slots.0.item.count", "operator": "equals", "value": 1}]}}])
def test_bounded_native_wait(args):
    Draft202012Validator(SCHEMA).validate(args)

@pytest.mark.parametrize("args", [{"waitFor": {"timeoutMillis": 6501}},
    {"waitFor": {"conditions": [{"path": "screenOpen", "operator": "equals", "value": True}]}},
    {"waitFor": {"conditions": [{"path": "visibleText.0", "operator": "contains", "value": 1}]}},
    {"waitFor": {"retryClick": True}}])
def test_unbounded_replay_or_placeholder_is_rejected(args):
    assert list(Draft202012Validator(SCHEMA).iter_errors(args))



from copy import deepcopy
from types import SimpleNamespace
from minecraft_codex_bridge.bridge import _ContextData
from minecraft_codex_bridge.server import Adapter

class NativeClient:
    configuration = SimpleNamespace(environ={})
    def __init__(self, *, local=True, matched=True, drift=False, malformed=False):
        self.calls = []
        self.snapshot = {"sessionId": "sync-session", "profile": "developer", "worldLoaded": True,
            "world": {"worldName": "sync-world", "worldFolder": "sync-world", "dimension": 180, "singleplayer": local},
            "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}, "player": {"health": 20}}
        self.matched, self.drift, self.malformed = matched, drift, malformed
    async def invoke(self, operation, arguments):
        self.calls.append((operation, arguments))
        if operation == "client.snapshot": return deepcopy(self.snapshot)
        value = {"guiWait": {"matched": self.matched, "version": 1, "timedOut": not self.matched, "source": "normal_client_end_ticks"},
            "containerSynchronization": {"fullNativeSlotsEqual": True, "fullPhantomSlotsEqual": True,
                "fullPhantomFluidSlotsEqual": True, "phantomRegistryComplete": True}}
        if self.malformed: return value
        current = deepcopy(self.snapshot)
        if self.drift: current["world"]["dimension"] = 181
        return _ContextData(value, {"version": 1, "phase": "after", "operation": operation, "snapshot": current})

@pytest.mark.asyncio
@pytest.mark.parametrize("matched", [True, False])
async def test_one_native_wait_never_replays_gui_actions(matched):
    client = NativeClient(matched=matched)
    result = await Adapter(client)._call_tool("mcb_gui", {"waitFor": {}})
    assert result.structuredContent["ok"] is matched
    assert [op for op, args in client.calls] == ["client.snapshot", "gui.inspect"]
    if not matched: assert result.structuredContent["error"]["replayAllowed"] is False

@pytest.mark.asyncio
@pytest.mark.parametrize("options,code,calls", [({"local": False}, "unsupported", 1),
    ({"drift": True}, "gui_context_changed", 2), ({"malformed": True}, "MALFORMED_RESPONSE", 2)])
async def test_local_connection_and_fresh_receipt_guards(options, code, calls):
    client = NativeClient(**options)
    result = await Adapter(client)._call_tool("mcb_gui", {"waitFor": {}})
    assert result.structuredContent["error"]["code"] == code
    assert len(client.calls) == calls


@pytest.mark.asyncio
@pytest.mark.parametrize("changed", [False, True])
async def test_remote_gui_wait_uses_same_native_after_contract_and_connection(changed):
    class RemoteClient(NativeClient):
        async def invoke(self, operation, arguments):
            value = await super().invoke(operation, arguments)
            if operation == "gui.inspect":
                value["containerSynchronization"].update(source="client_and_remote_server_owner_threads", connectionId="connection")
                if changed:
                    from minecraft_codex_bridge.bridge import response_context
                    response_context(value)["snapshot"]["serverBridge"]["connectionId"] = "reconnected"
            return value
    client = RemoteClient(local=False)
    client.snapshot["serverBridge"] = {"ready": True, "connectionId": "connection", "dimension": 180,
        "server": {"operatorReads": True, "guiNativeWaitVersion": 1}}
    result = await Adapter(client)._call_tool("mcb_gui", {"waitFor": {}})
    assert result.structuredContent["ok"] is (not changed)
    if changed: assert result.structuredContent["error"]["code"] == "gui_context_changed"
    assert [op for op, args in client.calls] == ["client.snapshot", "gui.inspect"]


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", [None, "version", "verified", "count", "stock"])
async def test_phantom_display_proof_is_explicit_and_never_relaxes_owned_stock(fault):
    class DisplayClient(NativeClient):
        async def invoke(self, operation, arguments):
            value = await super().invoke(operation, arguments)
            if operation == "gui.inspect":
                sync = value["containerSynchronization"]
                sync.update(fullPhantomSlotsEqual=False, phantomDisplayInitializationVersion=1,
                    phantomDisplayInitializationVerified=True, phantomConfigurationConfirmed=True,
                    phantomDisplayInitializedCount=1, phantomItemSlotCount=2)
                if fault == "version": sync["phantomDisplayInitializationVersion"] = True
                if fault == "verified": sync["phantomDisplayInitializationVerified"] = False
                if fault == "count": sync["phantomDisplayInitializedCount"] = 3
                if fault == "stock": sync["fullNativeSlotsEqual"] = False
            return value
    client = DisplayClient()
    result = await Adapter(client)._call_tool("mcb_gui", {"waitFor": {}})
    assert result.structuredContent["ok"] is (fault is None)
    assert [op for op, _ in client.calls] == ["client.snapshot", "gui.inspect"]


@pytest.mark.parametrize("wait,reason", [
    ({"diagnostic": {"screenObserved": False}}, "screen_not_observed"),
    ({"diagnostic": {"screenObserved": True, "container": {"reason": "phantom_contents_pending"}}}, "phantom_contents_pending"),
    ({"observationReady": True, "unmetConditions": [{"path": "slots.0.item.count", "actual": 1, "value": 2}]}, "declared_conditions_pending"),
    ({"observationReady": True, "unmetConditions": []}, "acceptance_deadline_expired"),
])
def test_wait_failure_exposes_specific_condition_without_claiming_action_failure(wait, reason):
    from minecraft_codex_bridge.gui_acceptance import wait_failure
    error = wait_failure({"guiWait": wait})
    assert error["reason"] == reason and reason in error["message"]
    assert error["guiWait"] == wait and error["replayAllowed"] is False
