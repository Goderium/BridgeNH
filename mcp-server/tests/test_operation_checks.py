"""Read-only shortcuts retain native boundaries, drift failures and exact data."""
import json

import pytest

from minecraft_codex_bridge import operation_checks
from minecraft_codex_bridge.guides import read_help
from minecraft_codex_bridge.server import Adapter, _validate
from minecraft_codex_bridge.workflows import validate_plan
from test_workflows import bridge, operations


TARGET = {"x": 2, "y": 64, "z": 3, "dimension": -1}


def args(kind, **options):
    return {"kind": kind, "expectedSessionId": "session",
        **({"expectedWorld": "test-world", "target": TARGET} if kind in {"machine", "ae_buffers"} else {}), **options}


@pytest.mark.parametrize("kind", list(operation_checks.GUIDANCE))
def test_generated_plans_validate_and_cannot_dispatch_actions(kind):
    arguments = args(kind)
    _validate("mcb_operation_check", arguments)
    generated = operation_checks.plan(arguments)
    _validate("mcb_sequence", generated)
    steps = validate_plan(generated, False)
    assert all(step["kind"] in {"read", "assert"} for step in steps)
    assert "cleanup" not in generated
    assert len(json.dumps(operation_checks.GUIDANCE[kind]).encode()) < 8192 - 1024


class NoGame:
    async def invoke(self, *a, **kw):
        pytest.fail("Invalid selectors must be rejected before any game call")


@pytest.mark.parametrize("arguments", [
    {"kind": "machine", "expectedSessionId": "session"},
    args("machine", target={"x": 2, "y": 64, "z": 3}),
    args("machine", scope="cpu"), args("ae", target=TARGET), args("nei", scope="cpu"),
    args("ae", scope="unknown"), args("ae", timeout_ms=30001),
    args("ae", action="job_cancel"), args("machine", target={**TARGET, "includeAe": True}),
    {"kind": "nei"}, args("unknown"), args("ae_buffers", expectedWorld=""),
])
async def test_invalid_scope_crossings_fail_before_discovery(arguments):
    result = await Adapter(NoGame()).call_tool("mcb_operation_check", arguments)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"


async def test_ae_binds_fresh_screen_preserves_pending_and_exact_counts(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "fresh-screen", "windowId": 7, "screenKind": "ae"}
    state["data"]["ae.inspect"] = {"screenId": "fresh-screen", "scope": "preview",
        "state": {"requestedPending": True, "startReady": False}, "entries": [
            {"resourceId": "exact-resource", "amount": "9223372036854775807", "identityNbt": "{tag:exact}"}],
        "hasMore": True, "nextOffset": 16, "coverage": {"pending": True}}
    result = await adapter.call_tool("mcb_operation_check", args("ae", scope="preview"))
    assert not result.isError
    data = result.structuredContent["data"]
    assert data["productionVerified"] is False and data["completionScope"] == "read_only_observations"
    ae_calls = [c for c in state["calls"] if c["operation"] == "ae.inspect"]
    assert [c["args"] for c in ae_calls] == [{"scope": "preview", "screenId": "fresh-screen", "offset": 0, "limit": 16}]
    rendered = json.dumps(data)
    assert "9223372036854775807" in rendered and "exact-resource" in rendered and "{tag:exact}" in rendered
    assert data["steps"][1]["observation"]["metadata"]["hasMore"] is True
    assert data["dispatchedActions"] == 0 and not data["takeoverEstablished"]
    assert "client.background" not in operations(state)


async def test_nei_pins_screen_and_context_without_auto_paging(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "screen"}
    state["data"]["nei.view"] = {"contextToken": "fresh-token", "data": {"entries": []}, "hasMore": True}
    result = await adapter.call_tool("mcb_operation_check", args("nei"))
    assert not result.isError
    calls = [c["args"] for c in state["calls"] if c["operation"] == "nei.view"]
    assert calls == [{"screenId": "screen", "section": "summary", "limit": 1},
        {"screenId": "screen", "contextToken": "fresh-token", "section": "recipes", "scope": "visible", "offset": 0, "limit": 4}]
    assert "client.background" not in operations(state)


async def test_missing_nei_context_stops_before_recipe_page(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "screen"}
    state["data"]["nei.view"] = {"data": {"entries": []}}
    result = await adapter.call_tool("mcb_operation_check", args("nei"))
    assert result.isError
    data = result.structuredContent["data"]
    assert data["completedSteps"] == 2 and data["failedIndex"] == 2
    assert operations(state).count("nei.view") == 1
    assert data["productionVerified"] is False


@pytest.mark.parametrize("kind,operation", [("machine", "machine.inspect"), ("ae_buffers", "world.inspect")])
async def test_explicit_owner_and_dimension_are_preserved(bridge, kind, operation):
    adapter, state = bridge
    state["health_flags"]["aeWorldReadVersion"] = 1
    state["data"][operation] = {**TARGET, "metaTileId": 123, "state": {"active": True, "allowedToWork": False},
        "shutdownReason": {"id": "power_loss"}, "checkRecipeResult": {"id": "failure"}, "modes": {"index": 2},
        "ae": {"pending": [{"amount": "9223372036854775807"}]},
        "coverage": {"scope": "loaded owner only"}}
    result = await adapter.call_tool("mcb_operation_check", args(kind))
    assert not result.isError
    calls = [c for c in state["calls"] if c["operation"] == operation]
    assert len(calls) == 1
    assert calls[0]["args"] == {**TARGET, **({"includeAe": True} if kind == "ae_buffers" else {})}
    observed = result.structuredContent["data"]["steps"][-1]["observation"]["values"]
    assert observed["dimension"] == -1
    if kind == "machine":
        assert observed["shutdownReason"] == {"id": "power_loss"} and observed["modes"] == {"index": 2}
    else:
        assert observed["ae"]["pending"][0]["amount"] == "9223372036854775807"
    assert not any(c["operation"] in {"machine.set_working", "ae.action", "client.background"} for c in state["calls"])


async def test_capability_failure_does_not_fallback_to_another_domain(bridge):
    adapter, state = bridge
    state["caps"].remove("machine.inspect")
    result = await adapter.call_tool("mcb_operation_check", args("machine"))
    assert result.isError
    assert "world.inspect" not in operations(state) and "machine.inspect" not in operations(state)


async def test_legacy_session_drift_cannot_return_success(bridge):
    adapter, state = bridge
    def changed():
        state["session"] = "replacement-session"
        return {"screenId": "old-screen"}
    state["data"]["gui.inspect"] = changed
    result = await adapter.call_tool("mcb_operation_check", args("ae"))
    assert result.isError
    assert result.structuredContent["data"]["dispatchedActions"] == 0


async def test_native_after_guard_stops_before_bound_ae_read(bridge, monkeypatch):
    from minecraft_codex_bridge.bridge import _ContextData
    adapter, state = bridge
    state["health_flags"]["contextRpc"] = {"version": 1, "reads": ["gui.inspect", "ae.inspect"], "actions": []}
    original = adapter.client.call
    async def changed(operation, arguments, **kwargs):
        value = await original(operation, arguments, **kwargs)
        if operation == "gui.inspect":
            return _ContextData({"screenId": "old-screen"}, {"version": 1, "operation": operation, "phase": "after",
                "snapshot": {"sessionId": "replacement-session", "profile": "developer", "worldLoaded": True,
                    "world": {"worldFolder": "test-world"}}})
        return value
    monkeypatch.setattr(adapter.client, "call", changed)
    result = await adapter.call_tool("mcb_operation_check", args("ae"))
    assert result.isError and "ae.inspect" not in operations(state)


@pytest.mark.parametrize("profile,version", [("developer", None), ("developer", 0), ("player", 1)])
async def test_ae_buffers_requires_advertised_capability_and_authority(bridge, profile, version):
    adapter, state = bridge
    state["profile"] = profile
    if version is not None:
        state["health_flags"]["aeWorldReadVersion"] = version
    result = await adapter.call_tool("mcb_operation_check", args("ae_buffers"))
    assert result.isError and "world.inspect" not in operations(state)


def test_operations_guide_is_packaged_bounded_and_independent_of_local_kb():
    result = read_help({"topic": "operations"})
    assert "5.09.54.205" in result["markdown"] and "ae_buffers" in result["markdown"]
    assert len(json.dumps(result, ensure_ascii=False).encode()) < 32768


def test_native_feedback_uses_exact_tool_and_state_surfaces():
    from minecraft_codex_bridge.feedback import explain
    disabled = {"ok": True, "data": {"requestedWorking": False, "after": False}}
    result = explain("mcb_machine_work", disabled, read_only=False)
    assert result["items"][0]["id"] == "mcb.gt_disabled"
    assert explain("mcb_inventory", disabled, read_only=True) is None
    pending = {"ok": True, "data": {"scope": "preview", "state": {"requestedPending": True}}}
    assert explain("mcb_ae", pending, read_only=True)["items"][0]["id"] == "mcb.ae_preview_pending"
    assert explain("mcb_nei_view", pending, read_only=True) is None
    assert explain("mcb_ae", {"ok": True, "data": {"scope": "preview", "nbt": pending["data"]}}, read_only=True) is None


async def test_large_values_are_omitted_whole_within_budget(bridge):
    adapter, state = bridge
    state["data"]["machine.inspect"] = {**TARGET, "metaTileId": 123,
        "processing": {"largeExactNBT": "x" * 80000}, "state": {"storedEU": "9223372036854775807"}}
    result = await adapter.call_tool("mcb_operation_check", args("machine"))
    assert not result.isError
    data = result.structuredContent["data"]
    assert len(json.dumps(data, ensure_ascii=False).encode()) < 65536
    observed = data["steps"][0]["observation"]
    assert "processing" in observed["omittedFields"]
    assert observed["values"]["state"]["storedEU"] == "9223372036854775807"
    assert data["productionVerified"] is False


async def test_timeout_does_not_repeat_or_continue_reads(bridge):
    adapter, state = bridge
    state["slow"] = "gui.inspect"
    result = await adapter.call_tool("mcb_operation_check", args("ae", timeout_ms=20))
    assert result.isError
    assert operations(state).count("gui.inspect") <= 1 and "ae.inspect" not in operations(state)
    assert result.structuredContent["data"]["dispatchedActions"] == 0


async def test_operations_help_endpoint_works_without_client():
    result = await Adapter(NoGame()).call_tool("mcb_help", {"topic": "operations"})
    assert not result.isError and result.structuredContent["data"]["topic"] == "operations"
