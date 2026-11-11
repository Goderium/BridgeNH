"""Optional context envelopes retain the native operation name and fresh guards."""

import asyncio
import copy
import json
from collections import Counter

import anyio

import httpx
import pytest

from minecraft_codex_bridge.bridge import (
    BridgeClient, BridgeError, Configuration, CONTEXT_ACTIONS, CONTEXT_READS,
    request_context, response_context,
)
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOLS
from minecraft_codex_bridge.workflows import _guard


PROTECTED = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}


def capabilities():
    return {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}


def directive():
    return {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer", "expectedWorld": "world", "requireBackground": True}


@pytest.fixture
async def guarded_bridge():
    state = {"calls": [], "actions": 0, "reads": 0, "session": "session", "profile": "developer", "world": "world",
        "background": PROTECTED.copy(), "supported": capabilities(), "bad_context": None, "after_action": None,
        "after_read": None, "slow": False, "disconnect": False, "cancel": "already_running"}
    def snapshot():
        return {"sessionId": state["session"], "profile": state["profile"], "worldLoaded": True,
            "world": {"worldName": state["world"], "worldFolder": state["world"], "dimension": 0},
            "background": copy.deepcopy(state["background"]), "player": {"health": state.get("health", 20), "x": 1.5, "feetY": 2, "z": 3.5, "flying": True}}
    async def respond(request):
        body = json.loads(request.content)
        op = "__cancel__" if request.url.path == "/v1/cancel" else body["operation"]
        state["calls"].append({**body, "operation": op})
        if op == "__cancel__":
            data = {"status": state["cancel"]}
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})
        context = body.get("includeContext")
        before = snapshot() if context is not None and op in CONTEXT_ACTIONS else None
        if before is not None:
            try:
                _guard(before, context["expectedSessionId"], context["expectedProfile"], context.get("expectedWorld"), context["requireBackground"])
            except BridgeError as error:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": error.code, "message": error.message, "outcome_unknown": False}})
        if op == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": state["session"], "profile": state["profile"],
                "background": copy.deepcopy(state["background"]), "capabilities": [tool.operation for tool in TOOLS if tool.operation]}
            if state["supported"] is not None:
                data["contextRpc"] = state["supported"]
        elif op == "client.snapshot":
            data = snapshot()
        elif op == "client.background":
            state["background"] = PROTECTED.copy()
            data = copy.deepcopy(state["background"])
        elif op in CONTEXT_ACTIONS:
            state["actions"] += 1
            if state["slow"]:
                await asyncio.sleep(5)
            if state["disconnect"]:
                raise httpx.ReadError("native response lost", request=request)
            data = {"status": "submitted", "submitted": True, "serverConfirmed": False}
            if state["after_action"]:
                state["after_action"](state)
        else:
            state["reads"] += 1
            data = {"selectedHotbarSlot": 1, "cursorItem": None, "slots": [], "totalSlots": 0, "truncated": False,
                "source": "integrated_server", "dimension": 0, "registryName": "minecraft:air", "metadata": 0,
                "screenId": "actual", "windowId": 0}
            if state["after_read"]:
                state["after_read"](state)
        envelope = {"requestId": body["requestId"], "ok": True, "data": data}
        if context is not None:
            actual = before if before is not None else snapshot()
            try:
                _guard(actual, context["expectedSessionId"], context["expectedProfile"], context.get("expectedWorld"), context["requireBackground"])
            except BridgeError as error:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": error.code, "message": error.message, "outcome_unknown": False}})
            evidence = {"version": 1, "operation": op, "phase": "before" if op in CONTEXT_ACTIONS else "after", "snapshot": actual}
            fault = state["bad_context"]
            if fault == "wrong_phase":
                evidence["phase"] = "after" if op in CONTEXT_ACTIONS else "before"
            if fault == "wrong_operation":
                evidence["operation"] = "client.exit"
            if fault == "wrong_version":
                evidence["version"] = True
            if fault == "not_snapshot":
                evidence["snapshot"] = []
            if fault == "extra":
                evidence["private"] = "unrequested"
            if fault != "missing":
                envelope["context"] = evidence
        return httpx.Response(200, json=envelope)
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "guarded-test-token"}), timeout=.15, transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def plan(*steps):
    return {"expectedSessionId": "session", "expectedWorld": "world", "steps": list(steps)}


def action(identifier="act"):
    return {"id": identifier, "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 1}}


def read(identifier="read", tool="mcb_inventory"):
    return {"id": identifier, "kind": "read", "tool": tool, "args": {"x": 1, "y": 2, "z": 3} if tool == "mcb_world_inspect" else {}}


async def test_survival_build_transport_uses_one_current_guarded_action(guarded_bridge):
    adapter, state = guarded_bridge
    step = {"id": "build", "kind": "action", "tool": "mcb_survival_build", "args": {
        "expectedSessionId": "session", "expectedWorld": "world", "dimension": 0,
        "x": 1, "y": 2, "z": 3, "blueprintHotbarSlot": 0, "side": 2}}
    result = await adapter.call_tool("mcb_sequence", plan(step))
    data = result.structuredContent["data"]
    assert data["status"] == "completed" and data["acknowledgedActions"] == 1
    dispatched = [call for call in state["calls"] if call["operation"] == "structure.survival_build"]
    assert len(dispatched) == 1 and dispatched[0]["includeContext"] == directive()
    assert state["actions"] == 1 and data["steps"][0]["observation"]["values"]["serverConfirmed"] is False


@pytest.mark.parametrize("fault", [None, "missing", "wrong_phase", "dead"])
async def test_public_survival_build_entry_uses_explicit_before_guard_without_replay(guarded_bridge, fault):
    adapter, state = guarded_bridge
    if fault == "dead": state["health"] = 0
    else: state["bad_context"] = fault
    result = await adapter.call_tool("mcb_survival_build", {"expectedSessionId": "session", "expectedWorld": "world",
        "dimension": 0, "x": 1, "y": 2, "z": 3, "blueprintHotbarSlot": 0, "side": 2})
    dispatched = [call for call in state["calls"] if call["operation"] == "structure.survival_build"]
    if fault == "dead":
        assert not dispatched and state["actions"] == 0 and result.isError
        assert result.structuredContent["error"]["reason"] == "live_player_required"
        assert result.structuredContent["error"]["outcome_unknown"] is False
        return
    assert len(dispatched) == 1 and dispatched[0]["includeContext"] == directive() and state["actions"] == 1
    if fault is None:
        assert result.structuredContent["ok"] is True and result.structuredContent["data"]["serverConfirmed"] is False
        assert "nativeDone" not in result.structuredContent["data"]
    else:
        assert result.isError and result.structuredContent["error"]["outcome_unknown"] is True


@pytest.mark.parametrize("fault", [None, "plain_guard", "bool_count", "oversized_batches", "wrong_receipt", "replay", "completed_exceeds_started"])
async def test_survival_failure_preserves_bounded_known_native_receipt_or_refuses_malformed(guarded_bridge, monkeypatch, fault):
    adapter, state = guarded_bridge
    original = adapter.client.http._transport.handle_async_request
    error = {"code": "native_build_failed", "message": "Offline staged callback failure.", "stage": "native_callback",
        "nativeSubmitted": True, "callsStarted": 1, "callsCompleted": 0, "callbackInFlight": False,
        "partial": True, "outcome_unknown": True, "callbackPreemptible": False, "automaticReplayAllowed": False,
        "batches": [], "knownReceipt": {"playerInventoryAfter": {"source": "integrated_server", "truncated": False}}}
    if fault == "plain_guard": error = {"code": "invalid_request", "message": "No staged receipt.", "outcome_unknown": False}
    elif fault == "bool_count": error["callsStarted"] = True
    elif fault == "oversized_batches": error["batches"] = [{}] * 33
    elif fault == "wrong_receipt": error["knownReceipt"] = []
    elif fault == "replay": error["automaticReplayAllowed"] = True
    elif fault == "completed_exceeds_started": error["callsCompleted"] = 2
    async def failure(request):
        body = json.loads(request.content)
        if body.get("operation") == "structure.survival_build":
            state["calls"].append({**body, "operation": "structure.survival_build"})
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": error})
        return await original(request)
    monkeypatch.setattr(adapter.client.http, "_transport", httpx.MockTransport(failure))
    result = await adapter.call_tool("mcb_survival_build", {"expectedSessionId": "session", "expectedWorld": "world",
        "dimension": 0, "x": 1, "y": 2, "z": 3, "blueprintHotbarSlot": 0, "side": 2})
    details = result.structuredContent["error"]
    assert result.isError and len([call for call in state["calls"] if call["operation"] == "structure.survival_build"]) == 1
    if fault is None:
        assert details["knownReceipt"] == error["knownReceipt"] and details["callsStarted"] == 1
        assert details["partial"] is True and details["automaticReplayAllowed"] is False
    elif fault == "plain_guard":
        assert details["code"] == "invalid_request" and details["outcome_unknown"] is False and "knownReceipt" not in details
    else:
        assert details["code"] == "MALFORMED_RESPONSE" and details["outcome_unknown"] is True


async def test_optional_rpc_reduces_roundtrips_without_changing_receipts_or_accepting_actions(guarded_bridge):
    adapter, state = guarded_bridge
    result = await adapter.call_tool("mcb_sequence", plan(read(), action(), read("after")))
    data = result.structuredContent["data"]
    assert data["status"] == "completed" and data["acknowledgedActions"] == data["dispatchedActions"] == 1
    assert state["actions"] == 1 and state["reads"] == 2
    assert [call["operation"] for call in state["calls"]].count("client.snapshot") == 0
    wrapped = [call for call in state["calls"] if "includeContext" in call]
    assert [call["operation"] for call in wrapped] == ["inventory.inspect", "player.hotbar", "inventory.inspect", "bridge.health"]
    assert all(call["includeContext"] == directive() for call in wrapped)
    assert data["steps"][1]["completion"].startswith("acknowledged;")
    assert data["steps"][1]["observation"]["values"]["serverConfirmed"] is False
    assert "context" not in data["steps"][1]["observation"]["values"]


@pytest.mark.parametrize("supported", [None, {"version": 2}, {"version": True}, {"version": 1, "reads": "all", "actions": []}])
async def test_absent_unsupported_or_malformed_capability_retains_legacy_guards(guarded_bridge, supported):
    adapter, state = guarded_bridge
    state["supported"] = supported
    result = await adapter.call_tool("mcb_sequence", plan(read(), action()))
    assert result.structuredContent["data"]["status"] == "completed" and state["actions"] == 1
    assert not any("includeContext" in call for call in state["calls"])
    assert [call["operation"] for call in state["calls"]].count("client.snapshot") == 4


@pytest.mark.parametrize("fault", ["session", "profile", "world", "background"])
async def test_changed_context_refuses_later_action_before_native_execution(guarded_bridge, fault):
    adapter, state = guarded_bridge
    def changed(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_action"] = changed
    result = await adapter.call_tool("mcb_sequence", plan(action("one"), action("two")))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["steps"][0]["status"] == "completed"
    assert data["steps"][1]["status"] == "failed" and not data["outcomeUnknown"]
    assert state["actions"] == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.hotbar"]) == 2


async def test_server_read_uses_fresh_post_read_context_and_blocks_following_action(guarded_bridge):
    adapter, state = guarded_bridge
    state["after_read"] = lambda value: value.update(world="changed")
    result = await adapter.call_tool("mcb_sequence", plan(read(tool="mcb_world_inspect"), action()))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["error"]["code"] == "WORLD_CHANGED"
    assert state["reads"] == 1 and state["actions"] == 0
    assert data["steps"][1]["status"] == "not_executed"


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_bad_action_context_is_unknown_and_never_falls_back_or_replays(guarded_bridge, fault):
    adapter, state = guarded_bridge
    state["bad_context"] = fault
    result = await adapter.call_tool("mcb_sequence", plan(action("one"), action("two")))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["outcomeUnknown"]
    assert data["error"]["code"] == "MALFORMED_RESPONSE" and state["actions"] == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.hotbar"]) == 1
    state["bad_context"] = None
    await adapter.client.call("gui.inspect", {})
    assert "includeContext" not in state["calls"][-1]


@pytest.mark.parametrize("mode", ["slow", "disconnect"])
async def test_guarded_action_transport_failure_keeps_unknown_without_retry(guarded_bridge, mode):
    adapter, state = guarded_bridge
    state[mode] = True
    result = await adapter.call_tool("mcb_sequence", plan(action("one"), action("two")))
    data = result.structuredContent["data"]
    assert data["status"] == "failed" and data["outcomeUnknown"] and state["actions"] == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.hotbar"]) == 1


async def test_unlisted_server_mutation_retains_the_old_fresh_guard(guarded_bridge):
    adapter, state = guarded_bridge
    step = {"id": "move", "kind": "action", "tool": "mcb_teleport", "args": {"x": 1.5, "y": 2, "z": 3.5, "dimension": 0, "expectedWorld": "world", "requireFlying": True}}
    result = await adapter.call_tool("mcb_sequence", plan(step))
    assert result.structuredContent["data"]["status"] == "completed"
    call = next(call for call in state["calls"] if call["operation"] == "dev.teleport")
    assert "includeContext" not in call
    assert [call["operation"] for call in state["calls"]].count("client.snapshot") == 2


@pytest.mark.parametrize("operation", ["client.exit", "client.screenshot", "bridge.profile", "qa.run", "dev.teleport", "bridge.guarded_call"])
async def test_transport_context_scope_cannot_wrap_outside_fixed_whitelist(guarded_bridge, operation):
    adapter, state = guarded_bridge
    with request_context(directive()):
        with pytest.raises(BridgeError) as caught:
            await adapter.client.call(operation, {})
    assert caught.value.code == "INVALID_ARGUMENTS" and not state["calls"]


async def test_context_data_is_separate_from_native_dictionary(guarded_bridge):
    adapter, state = guarded_bridge
    with request_context(directive()):
        value = await adapter.client.call("inventory.inspect", {})
    assert "context" not in value and response_context(value)["phase"] == "after"
    assert isinstance(value, dict) and value["cursorItem"] is None


def _stable(value):
    if isinstance(value, dict):
        return {key: _stable(item) for key, item in value.items() if key not in ("lastRequestId", "requestId")}
    if isinstance(value, list):
        return [_stable(item) for item in value]
    return value


async def construction_variant(enabled, order, restore, monkeypatch):
    # Reuse the existing native HTTP/slot/cursor/world fixture. The wrapper
    # models only the new envelope; all original operation bodies still run.
    from test_construction_tasks import (
        native_construction, fill_hotbar_with_storage_controller, declare_muffler,
        CONTROLLER, run_blueprint_build, run_workflow, writes,
    )
    generator = native_construction.__wrapped__()
    client, state = await anext(generator)
    try:
        fill_hotbar_with_storage_controller(state)
        state["items"][26], state["items"][9] = state["items"][9], None
        state["controller_original_index"] = 26
        controller = {**CONTROLLER, "y": 4}
        state["controller"] = controller
        args = declare_muffler(state, monkeypatch, controller=controller, constructionOrder=order, restoreInventory=restore)
        if enabled:
            original_handler = client.http._transport.handler
            def snapshot():
                position = copy.deepcopy(state["position"])
                return {"sessionId": "session", "profile": "developer", "worldLoaded": True,
                    "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0},
                    "background": PROTECTED.copy(), "screenOpen": state["gui"] is not None,
                    "player": {"health": state.get("health", 20), **position, "feetY": position["y"], "eyeY": position["y"] + 1.62,
                        "selectedHotbarSlot": state["selected"], "flying": state["flying"],
                        "serverFlight": {"available": True, "allowFlying": True, "flying": state["flying"], "dimension": 0}}}
            async def wrapper(request):
                body = json.loads(request.content)
                op, context = body.get("operation"), body.get("includeContext")
                before = snapshot() if context is not None and op in CONTEXT_ACTIONS else None
                if before is not None:
                    _guard(before, context["expectedSessionId"], context["expectedProfile"], context.get("expectedWorld"), context["requireBackground"])
                response = await original_handler(request)
                envelope = response.json()
                if envelope.get("ok") is True:
                    if op == "bridge.health":
                        envelope["data"]["contextRpc"] = capabilities()
                    if context is not None:
                        actual = before if before is not None else snapshot()
                        _guard(actual, context["expectedSessionId"], context["expectedProfile"], context.get("expectedWorld"), context["requireBackground"])
                        envelope["context"] = {"version": 1, "operation": op, "phase": "before" if op in CONTEXT_ACTIONS else "after", "snapshot": actual}
                return httpx.Response(response.status_code, json=envelope)
            client.http._transport = httpx.MockTransport(wrapper)
        result = await run_blueprint_build(client, args, anyio.Lock(), runner=run_workflow)
        assert result["status"] == "completed", result
        return {"totalCalls": len(state["calls"]), "callCounts": dict(Counter(call["operation"] for call in state["calls"])),
            "nativeActions": [{"operation": call["operation"], "args": call.get("args", {})} for call in writes(state)],
            "state": _stable({"acceptance": result["acceptance"], "cleanup": result["cleanup"], "moves": result["inventoryMoves"],
                "retained": result["retainedInventoryMoves"], "pending": result["pendingInventoryRestoration"],
                "selectedPorts": result["selectedPorts"], "gui": state["gui"], "items": state["items"],
                "selected": state["selected"], "cursor": state["cursor"], "blocks": {str(key): item for key, item in sorted(state["blocks"].items())}})}
    finally:
        await generator.aclose()


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("restore", [False, True])
async def test_context_rpc_keeps_native_inventory_receipts_and_complete_builder_poststate(order, restore, monkeypatch):
    legacy = await construction_variant(False, order, restore, monkeypatch)
    guarded = await construction_variant(True, order, restore, monkeypatch)
    assert guarded["totalCalls"] < legacy["totalCalls"]
    assert guarded["nativeActions"] == legacy["nativeActions"]
    assert guarded["state"] == legacy["state"]
    assert guarded["callCounts"]["bridge.health"] == legacy["callCounts"]["bridge.health"]
