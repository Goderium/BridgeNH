import copy
import sys
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.command import invoke, parser, project_result
from minecraft_codex_bridge import command


class Adapter:
    def __init__(self, values):
        self.values = iter(values)
        self.calls = []

    async def call_tool(self, tool, args):
        self.calls.append((tool, args))
        return SimpleNamespace(structuredContent=next(self.values))


@pytest.mark.asyncio
async def test_finite_delegates_once_with_required_full_flow_and_exact_origin():
    options = parser().parse_args(["qa-test-once", "exact:test", "--session", "s", "--world", "world", "--origin", "-32", "4", "9"])
    adapter = Adapter([{"ok": False, "data": {"status": "failed", "outcomeUnknown": True}}])
    result = await invoke(adapter, options)
    assert adapter.calls == [("mcb_qa_test_once", {"selector": "exact:test", "expectedSessionId": "s", "expectedWorld": "world", "requireFullFlow": True, "includeDetails": False, "origin": {"x": -32, "y": 4, "z": 9}})]
    assert result["data"]["outcomeUnknown"] is True


@pytest.mark.asyncio
async def test_clear_refuses_changed_session_before_any_clear_submission():
    adapter = Adapter([{"ok": True, "data": {"sessionId": "different"}}])
    options = parser().parse_args(["qa-clear", "--session", "s", "--world", "world"])
    assert (await invoke(adapter, options))["error"]["code"] == "SESSION_CHANGED"
    assert adapter.calls == [("mcb_qa_catalog", {"section": "capabilities"})]


@pytest.mark.asyncio
async def test_clear_keeps_native_world_guard_and_does_not_retry_failed_control():
    failure = {"ok": False, "error": {"code": "BUSY"}}
    adapter = Adapter([{"ok": True, "data": {"sessionId": "s", "source": "tstqa_public_instance_adapter", "available": True, "dimension": 180}}, failure])
    options = parser().parse_args(["qa-clear", "--session", "s", "--world", "world"])
    assert await invoke(adapter, options) == failure
    assert adapter.calls == [("mcb_qa_catalog", {"section": "capabilities"}), ("mcb_qa_command", {"command": "clearall", "expectedWorld": "world"})]


@pytest.mark.asyncio
@pytest.mark.parametrize("changed", [{"dimension": 0}, {"dimension": True}, {"source": "native_global"}, {"available": False}])
async def test_clear_refuses_native_global_or_unverified_routes(changed):
    current = {"sessionId": "s", "source": "tstqa_public_instance_adapter", "available": True, "dimension": 180, **changed}
    adapter = Adapter([{"ok": True, "data": current}])
    options = parser().parse_args(["qa-clear", "--session", "s", "--world", "world"])
    assert (await invoke(adapter, options))["error"]["code"] == "PRIVATE_QA_REQUIRED"
    assert len(adapter.calls) == 1


def test_compact_projection_preserves_decimal_identity_scope_and_budget_result():
    source = {"ok": True, "data": {"status": "completed", "fullFlowVerified": True, "testPassed": True, "completeWithin10s": False, "timingScope": "adapter_entry_through_acceptance", "quantitativeSummary": {"quantityEncoding": "decimal_integer", "scope": "actual_api_receiver", "receiverItems": [{"registryName": "mod:item", "metadata": 3, "nbt": "tag", "count": "9007199254740993"}], "stableTicks": 85}, "diagnosticDetail": "omitted"}}
    original = copy.deepcopy(source)
    result = project_result(source)
    assert result["data"]["quantitativeSummary"] == source["data"]["quantitativeSummary"]
    assert result["data"]["completeWithin10s"] is False
    assert "diagnosticDetail" not in result["data"]
    assert source == original
    assert project_result(source, True) is source


@pytest.mark.parametrize("data", [{"status": "failed", "fullFlowVerified": True, "quantitativeSummary": {}}, {"status": "completed", "fullFlowVerified": False, "quantitativeSummary": {}}, {"status": "completed", "fullFlowVerified": True}])
def test_projection_does_not_manufacture_success_or_drop_incomplete_evidence(data):
    payload = {"ok": True, "data": data}
    assert project_result(payload) is payload


def test_command_has_no_unbounded_plan_or_execution_file_options():
    with pytest.raises(SystemExit):
        parser().parse_args(["qa-test-once", "exact:test", "--session", "s", "--world", "world", "--plan", "file.json"])


def test_brief_retains_exact_large_quantities_nbt_and_catalyst_receipt_without_mutation():
    item = {"registryName": "mod:item", "metadata": 3, "nbt": '{name:"测试"}', "count": "9007199254740993"}
    summary = {"scope": "actual_api_receivers", "quantityEncoding": "decimal_integer", "stableTicks": 85,
               "consumedItems": [item], "physicalCatalysts": [item], "receiverItems": [item],
               "fluidInputs": [], "receiverFluids": [{"name": "fluid", "nbt": "tag", "amount": "9007199254740995"}]}
    source = {"ok": True, "data": {"status": "completed", "fullFlowVerified": True, "quantitativeSummary": summary}}
    original = copy.deepcopy(source)
    projected = project_result(source, brief=True)["data"]["quantitativeSummary"]
    assert projected["receiverItems"] == [["mod:item", 3, '{name:"测试"}', "9007199254740993"]]
    assert projected["physicalCatalysts"] == projected["receiverItems"]
    assert projected["receiverFluids"] == [["fluid", "tag", "9007199254740995"]]
    assert source == original
    assert project_result(source, details=True, brief=True) is source


def test_incomplete_brief_receipt_preserves_original_payload():
    source = {"ok": True, "data": {"status": "completed", "fullFlowVerified": True, "quantitativeSummary": {}}}
    assert project_result(source, brief=True) is source


@pytest.mark.asyncio
@pytest.mark.parametrize("verified", [False, True])
async def test_debug_command_delegates_once_and_keeps_infinite_scope(monkeypatch, verified):
    calls = []
    data = {"status": "observation_ready", "observationVerified": verified, "runStillActive": True,
            "finitePass": False, "testPassed": False, "fullFlowVerified": False,
            "remainingLifecycle": ["stop", "drain", "85_real_ticks"], "observationSummary": {"count": "9007199254740993"}}

    async def observe(client, args, lock):
        calls.append((client, args, lock))
        return data

    monkeypatch.setitem(sys.modules, "minecraft_codex_bridge.qa_debug_tasks", SimpleNamespace(run_qa_debug_observe=observe))
    adapter = SimpleNamespace(client=object(), workflow_lock=object())
    options = parser().parse_args(["qa-debug-observe", "exact:debug", "--session", "s", "--world", "world", "--origin", "-32", "4", "9"])
    result = await invoke(adapter, options)
    assert calls == [(adapter.client, {"selector": "exact:debug", "expectedSessionId": "s", "expectedWorld": "world",
                                      "includeDetails": False, "origin": {"x": -32, "y": 4, "z": 9}}, adapter.workflow_lock)]
    assert result["ok"] is verified
    assert result["data"]["finitePass"] is False
    assert result["data"]["fullFlowVerified"] is False
    assert result["data"]["remainingLifecycle"] == ["stop", "drain", "85_real_ticks"]
    assert result["data"]["observationSummary"] == {"count": "9007199254740993"}


@pytest.mark.asyncio
@pytest.mark.parametrize("raises", [False, True])
async def test_execute_closes_client_after_failure_without_retry(monkeypatch, raises):
    class Client:
        closed = False

        async def aclose(self):
            self.closed = True

    client = Client()
    calls = []

    async def failed(adapter, options):
        calls.append(options.selector)
        if raises:
            raise RuntimeError("unknown outcome")
        return {"ok": False, "error": {"code": "OUTCOME_UNKNOWN"}}

    monkeypatch.setattr(command, "BridgeClient", lambda configuration: client)
    monkeypatch.setattr(command, "Adapter", lambda value: value)
    monkeypatch.setattr(command, "invoke", failed)
    options = parser().parse_args(["qa-test-once", "exact:test", "--session", "s", "--world", "world"])
    if raises:
        with pytest.raises(RuntimeError):
            await command.execute(options)
    else:
        payload = await command.execute(options)
        assert payload["ok"] is False
        assert payload["commandTiming"]["sdkReadUtcMillis"] >= payload["commandTiming"]["sdkStartUtcMillis"]
        assert payload["commandTiming"]["sdkElapsed_ms"] >= 0
    assert calls == ["exact:test"]
    assert client.closed
