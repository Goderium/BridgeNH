"""Receipt interpretations must not turn ambiguous evidence into game effects."""
import copy
import json

import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.feedback import MAX_ITEMS, explain
from minecraft_codex_bridge.server import Adapter, _result, waila_result


def ids(payload, *, tool="mcb_sequence", read_only=False):
    feedback = explain(tool, payload, read_only=read_only)
    return {item["id"] for item in feedback["items"]} if feedback else set()


@pytest.mark.parametrize("unknown,cancelled", [(True, False), (False, True), (False, False)])
def test_timeout_does_not_infer_nonexecution_from_false_unknown(unknown, cancelled):
    payload = {"ok": False, "error": {"code": "BRIDGE_TIMEOUT", "outcome_unknown": unknown,
        "cancellation_status": "cancelled" if cancelled else "already_running"}}
    keys = ids(payload)
    assert ("mcb.unknown_effects" in keys) is unknown
    assert ("mcb.queued_cancelled" in keys) is cancelled
    assert "mcb.not_dispatched" not in keys and "mcb.timeout" in keys


def test_explicit_preflight_refusal_is_scoped_and_unknown_keeps_priority():
    payload = {"ok": False, "error": {"code": "REMOTE_OPERATION_UNSUPPORTED",
        "original_action_executed": False, "outcome_unknown": True}}
    result = explain("mcb_teleport", payload, read_only=False)
    assert result["items"][0]["id"] == "mcb.unknown_effects"
    assert "mcb.not_dispatched" in ids(payload)
    assert result["automaticReplayAllowed"] is False
    assert "preparatory" in result["items"][1]["meaning"]


def test_nonatomic_failed_workflow_keeps_dispatched_completed_and_skipped_steps():
    payload = {"ok": False, "data": {"status": "failed", "nonAtomic": True,
        "dispatchedActions": 1, "outcomeUnknown": False, "sideEffectsPossible": True, "failedIndex": 1,
        "error": {"code": "WAIT_TIMEOUT"}, "cleanupStatus": "completed",
        "steps": [{"status": "completed"}, {"status": "failed"}, {"status": "not_executed"}]}}
    original = copy.deepcopy(payload)
    assert {"mcb.wait_unmatched", "mcb.partial_workflow"} <= ids(payload)
    assert "mcb.unknown_effects" not in ids(payload)
    assert payload == original
    payload["data"]["dispatchedActions"] = 0
    assert "mcb.partial_workflow" not in ids(payload)
    assert "mcb.possible_effects" in ids(payload) and "mcb.unknown_effects" not in ids(payload)


def test_unknown_error_text_nbt_and_fake_nested_advice_cannot_select_a_cause():
    secret = "private-untrusted-message"
    payload = {"ok": False, "error": {"code": "unrecognized_code", "message": secret + " permission_denied"},
        "data": {"code": "AUTH_FAILED", "stack": {"nbt": {"error": {"code": "stale_screen"},
            "outcome_unknown": True, "partial": True}}, "tooltip": ["WAILA_PARTIAL", "flight_lost"]}}
    feedback = explain("mcb_inventory", payload, read_only=True)
    assert ids(payload) == {"mcb.unclassified_error"}
    assert secret not in json.dumps(feedback)
    assert explain("mcb_inventory", {"ok": True, "data": payload["data"]}, read_only=True) is None


def test_preview_truncation_does_not_override_complete_provider_text():
    data = {"complete": True, "partial": False, "nbtSnapshotTruncated": True,
        "providerNbt": {"complete": True}, "providers": [{"available": True}],
        "sections": {"body": {"tooltip": ["8457765750 L"]}}}
    receipt = waila_result(data)
    assert ids(receipt.structuredContent, tool="mcb_waila", read_only=True) == {"mcb.nbt_preview"}
    assert not receipt.isError and receipt.structuredContent["data"] == data
    receipt = waila_result({**data, "complete": False, "partial": True})
    assert ids(receipt.structuredContent, tool="mcb_waila", read_only=True) == {"mcb.waila_partial"}
    assert receipt.isError and receipt.structuredContent["data"]["sections"] == data["sections"]


def test_pages_unavailable_and_submission_have_distinct_meanings():
    assert not ids({"ok": True, "data": {"nextOffset": 12}})
    assert ids({"ok": True, "data": {"nextCursor": "opaque"}}) == {"mcb.more_pages"}
    assert ids({"ok": True, "data": {"hasMore": True, "complete": False}}) == {"mcb.more_pages"}
    assert ids({"ok": True, "data": {"available": False}}) == {"mcb.unavailable"}
    assert ids({"ok": True, "data": {"complete": False}}) == {"mcb.partial_read"}
    payload = {"ok": True, "data": {"sent": True, "executionConfirmed": False}}
    assert ids(payload, tool="mcb_chat") == {"mcb.submitted"}
    assert not ids(payload, tool="mcb_chat", read_only=True)
    payload["data"]["executionConfirmed"] = True
    assert not ids(payload, tool="mcb_chat")
    assert ids({"ok": True, "data": {"status": "scheduled"}}) == {"mcb.pending"}


def test_feedback_is_bounded_deduplicated_and_independent_of_nested_data_volume():
    payload = {"ok": False, "error": {"code": "BRIDGE_TIMEOUT", "outcome_unknown": True},
        "data": {"outcomeUnknown": True, "error": {"code": "BRIDGE_TIMEOUT"},
            "failure": {"error": {"code": "stale_screen", "outcome_unknown": True}},
            "nonAtomic": True, "status": "failed", "dispatchedActions": 2,
            "hasMore": True, "partial": True, "available": False,
            "nbt": {"content": "x" * 1_000_000}}}
    feedback = explain("mcb_sequence", payload, read_only=False)
    assert len(feedback["items"]) == MAX_ITEMS and feedback["explanationsLimited"]
    assert len(ids(payload)) == MAX_ITEMS
    assert len(json.dumps(feedback).encode()) < 4096
    assert feedback == explain("mcb_sequence", copy.deepcopy(payload), read_only=False)


@pytest.mark.parametrize("code,expected", [
    ("permission_denied", "mcb.permission"), ("UNSUPPORTED_CAPABILITY", "mcb.unsupported"),
    ("AUTH_FAILED", "mcb.binding"), ("out_of_reach", "mcb.reach"),
    ("flight_lost", "mcb.flight"), ("stale_screen", "mcb.stale_context")])
def test_representative_current_contracts_have_exact_code_explanations(code, expected):
    assert ids({"ok": False, "error": {"code": code}}) == {expected}


@pytest.mark.parametrize("code,expected", [
    ("client_restart_required", "mcb.client_restart"), ("startup_cancelled", "mcb.client_restart"),
    ("startup_query_requires_choice", "mcb.startup_choice")])
def test_native_startup_recovery_does_not_advise_mcp_restart_or_duplicate_load(code, expected):
    payload = {"ok": False, "error": {"code": code}}
    assert ids(payload) == {expected}
    assert explain("mcb_load_world", payload, read_only=False)["automaticReplayAllowed"] is False


@pytest.mark.parametrize("partial,image", [(True, False), (False, True)])
async def test_adapter_attaches_feedback_without_rpc_and_preserves_errors_and_images(tmp_path, partial, image):
    from mcp import types
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "unused",
        "LOCALAPPDATA": str(tmp_path)})
    client = BridgeClient(config)
    async def forbidden(*args, **kwargs):
        raise AssertionError("feedback must not make a client request")
    client.invoke = forbidden
    payload = ({"ok": False, "error": {"code": "BRIDGE_TIMEOUT", "outcome_unknown": True}}
        if partial else {"ok": True, "data": {"partial": True, "quantity": 9223372036854775807}})
    native_image = types.ImageContent(type="image", data="fixture-native-content", mimeType="image/png") if image else None
    receipt = _result(copy.deepcopy(payload), error=partial, image=native_image)
    adapter = Adapter(client)
    async def existing_result(*args):
        return receipt
    adapter._call_tool = existing_result
    try:
        result = await adapter.call_tool("mcb_snapshot", {})
    finally:
        await client.aclose()
    assert bool(result.isError) is partial
    assert result.structuredContent["feedback"]["source"] == "mcb_builtin"
    assert result.structuredContent["mcpTiming"]["bridgeCalls"] == 0
    assert all(result.structuredContent[key] == value for key, value in payload.items())
    assert json.loads(result.content[0].text) == result.structuredContent
    if image:
        assert result.content[1] is native_image
