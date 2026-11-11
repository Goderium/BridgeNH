"""Actual transport preserves one complete native placement failure; no mutation is retried."""

import json

import httpx
import pytest

from test_bridge import TOKEN, configuration
from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, placement_failure_fields
from test_guarded_rpc import guarded_bridge, plan
from minecraft_codex_bridge.survival_checkpoints import survival_failure_checkpoint


async def test_workflow_retains_placement_checkpoint_without_a_second_dispatch(guarded_bridge, monkeypatch):
    adapter, state = guarded_bridge
    original = adapter.client.http._transport.handle_async_request
    async def respond(request):
        body = json.loads(request.content)
        if body.get("operation") == "player.place_block":
            state['calls'].append(body)
            return httpx.Response(200, json={'requestId':body['requestId'],'ok':False,'error':failure()})
        return await original(request)
    monkeypatch.setattr(adapter.client.http, '_transport', httpx.MockTransport(respond))
    result = await adapter.call_tool('mcb_sequence', plan({'id':'place','kind':'action','tool':'mcb_place_block',
        'args':{'x':1,'y':2,'z':3,'hotbarSlot':0,'dimension':0,'expectedWorld':'world'}}))
    checkpoint = result.structuredContent['data']['placementCheckpoint']
    assert checkpoint['operation']=='player.place_block' and checkpoint['payloadComplete'] is True
    assert checkpoint['knownReceipt']['accepted'] is False
    assert checkpoint['knownReceipt']['serverBefore']==failure()['knownReceipt']['serverBefore']
    assert len([r for r in state['calls'] if r['operation']=='player.place_block'])==1


async def test_goal_task_retains_complete_placement_checkpoint_without_replay(guarded_bridge, monkeypatch):
    import test_actual_survival_workflows as actual
    adapter, state = guarded_bridge
    original = adapter.client.http._transport.handle_async_request
    async def respond(request):
        body = json.loads(request.content)
        if body.get("operation") == "player.place_block":
            state['calls'].append(body)
            return httpx.Response(200, json={'requestId':body['requestId'],'ok':False,'error':failure()})
        return await original(request)
    monkeypatch.setattr(adapter.client.http, '_transport', httpx.MockTransport(respond))
    task = actual.task(adapter.client)
    task.actual_survival = False  # Exercise the shared failure projection, independently of survival setup.
    step = {'id':'place','kind':'action','tool':'mcb_place_block',
        'args':{'x':1,'y':2,'z':3,'hotbarSlot':0,'dimension':0,'expectedWorld':'world'}}
    with pytest.raises(BridgeError) as caught:
        await task.workflow('permanent_frontier_seed', [step])
    checkpoint = caught.value.details['placementCheckpoint']
    assert checkpoint['operation'] == 'player.place_block' and checkpoint['payloadComplete'] is True
    assert checkpoint['knownReceipt']['serverBefore'] == failure()['knownReceipt']['serverBefore']
    assert checkpoint['nativeSubmitted'] is True and checkpoint['automaticReplayAllowed'] is False
    assert task.phases[-1]['placementCheckpoint'] == checkpoint
    assert caught.value.details['outcome_unknown'] is True
    assert len([r for r in state['calls'] if r['operation']=='player.place_block']) == 1


async def test_goal_task_retains_full_navigation_diagnostic_without_replay(guarded_bridge, monkeypatch):
    import test_actual_survival_workflows as actual
    adapter, state = guarded_bridge
    original = adapter.client.http._transport.handle_async_request
    message = 'Recorded bounded native route diagnostic: ' + 'x' * 900
    async def respond(request):
        body = json.loads(request.content)
        if body.get('operation') == 'player.navigate':
            state['calls'].append(body)
            return httpx.Response(200,json={'requestId':body['requestId'],'ok':False,
                'error':{'code':'unsupported','message':message,'outcome_unknown':False}})
        return await original(request)
    monkeypatch.setattr(adapter.client.http,'_transport',httpx.MockTransport(respond))
    task = actual.task(adapter.client)
    task.actual_survival = False
    with pytest.raises(BridgeError) as caught:
        await task.workflow('survival_frontier_jump_approach',[{'id':'navigate','kind':'action','tool':'mcb_navigate',
            'args':{'x':1,'y':2,'z':3,'dimension':0,'expectedSessionId':'session','expectedWorld':'world','strategy':'walking'}}])
    diagnostic = caught.value.details['navigationFailureDiagnostics']
    assert diagnostic['complete'] and diagnostic['message'] == message and diagnostic['replayAllowed'] is False
    assert task.phases[-1]['navigationFailureDiagnostics'] == diagnostic
    assert len([row for row in state['calls'] if row['operation']=='player.navigate']) == 1


def test_placement_checkpoint_budget_reports_omission_instead_of_partial_inventory():
    result = survival_failure_checkpoint(failure(), 2048, operation='player.place_block')
    assert result['payloadComplete'] is False and 'knownReceipt' not in result


def failure():
    return {"code": "native_refused", "message": "Local rejection " + TOKEN,
        "stage": "local_place_rejected", "failureCheckpoint": "local_place_rejected",
        "nativeSubmitted": True, "callsStarted": 1, "callsCompleted": 1, "callbackInFlight": False,
        "partial": True, "outcome_unknown": True, "callbackPreemptible": False, "automaticReplayAllowed": False,
        "batches": [], "knownReceipt": {"nativeInvoked": True, "accepted": False, "localAcceptanceKnown": True,
            "serverConfirmed": False, "outcomeUnknown": True, "automaticReplayAllowed": False,
            "lastCheckpoint": "local_place_rejected", "serverBefore": {"slots": [{"index": 0, "item": None}]},
            "serverPlacementBodyProof": {"version": 1, "wholeTargetCube": True, "overlapsTarget": False},
            "completion": TOKEN}}


async def test_transport_preserves_complete_redacted_placement_failure_and_trace_unknown():
    requests = []
    def respond(request):
        requests.append(request)
        body = json.loads(request.content)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": failure()})
    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with pytest.raises(BridgeError) as caught:
            await client.call("player.place_block", {}, trace=trace)
        error = caught.value
        assert error.code == "native_refused" and error.details["outcome_unknown"] is True
        assert error.details["knownReceipt"]["accepted"] is False
        assert error.details["knownReceipt"]["serverPlacementBodyProof"]["wholeTargetCube"] is True
        assert error.details["callsStarted"] == error.details["callsCompleted"] == 1
        assert error.details["automaticReplayAllowed"] is False
        assert TOKEN not in json.dumps(error.as_dict())
        assert trace["outcome_unknown"] is True and len(requests) == 1
    finally:
        await client.aclose()


@pytest.mark.parametrize("code", ["out_of_reach", "face_not_visible", "unsupported", "timeout"])
async def test_preflight_failure_is_known_unsubmitted_and_preserves_original_code(code):
    checkpoint = failure()
    checkpoint.update(code=code, stage="prepare", failureCheckpoint="prepare", nativeSubmitted=False,
        callsStarted=0, callsCompleted=0, callbackInFlight=False, partial=False, outcome_unknown=False, knownReceipt={})
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json={"requestId": json.loads(request.content)["requestId"], "ok": False, "error": checkpoint})
    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with pytest.raises(BridgeError) as caught:
            await client.call("player.place_block", {}, trace=trace)
        assert caught.value.code == code
        assert caught.value.details["outcome_unknown"] is False and trace["outcome_unknown"] is False
        assert caught.value.details["nativeSubmitted"] is False and len(calls) == 1
    finally:
        await client.aclose()


@pytest.mark.parametrize("change", [
    {"callsStarted": "1"}, {"callsStarted": True}, {"callsCompleted": 2}, {"automaticReplayAllowed": True},
    {"callbackPreemptible": True}, {"callbackInFlight": True}, {"nativeSubmitted": False},
    {"outcome_unknown": False}, {"partial": False}, {"stage": "x" * 129}, {"knownReceipt": []},
    {"batches": [{}] * 33}, {"knownReceipt": {"text": "x" * 65537}},
])
async def test_transport_rejects_malformed_native_checkpoint_as_unknown_without_replay(change):
    checkpoint = failure(); checkpoint.update(change)
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json={"requestId": json.loads(request.content)["requestId"], "ok": False, "error": checkpoint})
    client = BridgeClient(configuration(), transport=httpx.MockTransport(respond))
    trace = {}
    try:
        with pytest.raises(BridgeError) as caught:
            await client.call("player.place_block", {}, trace=trace)
        assert caught.value.code == "MALFORMED_RESPONSE"
        assert caught.value.details["outcome_unknown"] is True and trace["outcome_unknown"] is True
        assert len(calls) == 1
    finally:
        await client.aclose()


@pytest.mark.parametrize("missing", ["knownReceipt", "callsCompleted", "automaticReplayAllowed", "outcome_unknown"])
def test_partial_failure_dto_is_not_treated_as_complete(missing):
    details = failure(); details.pop(missing)
    with pytest.raises(ValueError):
        placement_failure_fields(details)


def test_bounded_validator_rejects_nonfinite_deep_and_nonjson_custom_runner_evidence():
    for value in (float("nan"), float("inf"), object()):
        details = failure(); details["knownReceipt"] = {"invalid": value}
        with pytest.raises(ValueError): placement_failure_fields(details)
    details = failure(); nested = {}
    details["knownReceipt"] = nested
    for _ in range(25):
        nested["next"] = {}; nested = nested["next"]
    with pytest.raises(ValueError): placement_failure_fields(details)
    original = failure(); original["knownReceipt"]["accepted"] = None
    assert placement_failure_fields(original)["knownReceipt"]["accepted"] is None
    assert placement_failure_fields({"code": "permission_denied", "message": "No native submission."}) == {}
