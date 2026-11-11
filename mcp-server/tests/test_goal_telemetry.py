"""Bounded goal metrics retain neither arbitrary arguments nor inventory/NBT."""
import json

from minecraft_codex_bridge.call_timing import CallTiming
from minecraft_codex_bridge.knowledge import capture_scope, observe_response, operation_phase, record_wait_sample
from test_run_log import setup


def test_goal_correlation_nested_calls_and_methods_survive_trace_cap(tmp_path):
    journal, _ = setup(tmp_path)
    args = {"requestId": "private-goal-request", "expectedSessionId": "private-session", "action": "start"}
    with capture_scope("mcb_ae_order", args) as outer:
        with operation_phase("ae_order_wait"):
            for _ in range(35):
                record_wait_sample()
                observe_response("ae.order", {"state": "running"}, None, None, None,
                                 {"action": "status", "secret": "never-stored"})
        with capture_scope("mcb_snapshot", {}) as child:
            assert child.parent_call_id == outer.call_id
        observe_response("ae.order", {"state": "completed"}, None, None, None, {"action": "take"})
        result = journal.finish(outer, args, {"ok": True, "data": {"status": "completed", "inventory": "never-stored"}},
                                {"bridgeCalls": 36, "bridgeResponseBytes": 1234, "bridgeResponseMeasuredCalls": 36})
    with capture_scope("mcb_ae_order", {**args, "action": "resume"}) as resumed:
        assert resumed.goal_digest == outer.goal_digest and resumed.call_id != outer.call_id
    row = journal.command({"action": "read", "category": "normal"})["entries"][0]
    assert row["id"] == result["eventId"] == outer.call_id
    assert row["waitSamples"] == 35 and row["operationsTruncated"]
    assert row["phases"] == [{"phase": "ae_order_wait", "observedBridgeCalls": 35}]
    assert row["methods"][-1] == {"operation": "ae.order", "action": "take", "observedCalls": 1}
    assert row["measurement"]["bridgeResponseBytes"] == 1234
    assert not any(value in json.dumps(row) for value in ["never-stored", "private-goal-request", "private-session"])


def test_phase_and_method_storage_stay_bounded():
    with capture_scope("mcb_sequence", {}) as capture:
        for index in range(100):
            with operation_phase("phase_" + str(index)):
                observe_response("example.operation" + str(index), {}, None, None, None, {"action": "start"})
        observe_response("ae.action", {}, None, None, None, {"action": "private-user-text"})
    assert len(capture.phases) == len(capture.methods) == 32
    assert capture.phases_limited and capture.methods_limited and len(capture.trace) == 24


def test_same_error_is_separated_by_observed_approach_phase(tmp_path):
    journal, _ = setup(tmp_path)
    issues = []
    for phase in ("current_recheck", "native_use"):
        with capture_scope("mcb_approach", {}) as capture:
            issues.append(journal.finish(capture, {}, {"ok": False, "error": {"code": "target_unverified",
                "receipt": {"phases": [{"phase": phase}]} }}, {})["issueId"])
    assert len(set(issues)) == 2


def test_http_response_byte_count_distinguishes_unmeasured_calls():
    timing = CallTiming()
    timing.rpc("bridge.health", 1, response_bytes=123)
    timing.rpc("client.snapshot", 1)
    report = timing.report()
    assert report["bridgeResponseBytes"] == 123 and report["bridgeResponseMeasuredCalls"] == 1
    assert report["bridgeCalls"] == 2
