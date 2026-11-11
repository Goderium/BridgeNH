"""Only a known returned hint work-budget loss may request one fresh read-only hint."""
import copy
import time
import pytest
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import native_construction, declaration, BLUEPRINT

async def setup(client, state, monkeypatch, fault=None):
    task = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    task.floor = 1
    task.materials = {0: {"item": BLUEPRINT.copy(), "count": 16}}
    original = task.call
    calls, hints = [], []
    async def read(operation, args):
        assert operation == "world.structure"
        value = await original(operation, args)
        rt = value["runtimeObservation"]
        rt.update(source="client_native_blueprint_hints", nativeInvocationReturned=True,
            controller={**task.args["controller"], "dimension": 0, "metaClass": "Public.Controller",
                "facing": "NORTH_NORMAL_NONE", "mode": 0}, serverFacing="NORTH_NORMAL_NONE",
            trigger={**BLUEPRINT, "count": 16, "channelData": ""}, detailErrors=0,
            observedVisits=rt["capturedPositions"], captureId="fresh" if hints else "cold")
        if not hints or fault == "second_loss":
            rt.update(capturedVisitsComplete=False, eventCaptureTruncated=True,
                observedVisits=rt["capturedPositions"] + 1,
                truncation={"workBudget": True, "positions": False, "groups": False, "bytes": False, "cleanupFailed": False})
        if not hints:
            if fault == "unknown": rt["nativeInvocationReturned"] = False
            if fault == "context": rt["contextMatches"] = False
            if fault == "descriptor": rt["detailErrors"] = 1
            if fault == "bytes": rt["truncation"]["bytes"] = True
            if fault == "trigger": rt["trigger"]["count"] = 1
            if fault == "owner": rt["controller"]["x"] += 1
        elif fault == "changed_mode": rt["capturedMode"] = 1
        elif fault == "same_capture": rt["captureId"] = "cold"
        calls.append(copy.deepcopy(args))
        return value
    async def hint(*, build):
        assert build is False
        hints.append(True)
        if fault == "unknown_use": raise BridgeError("UNKNOWN", "unknown submitted hint", outcome_unknown=True)
    monkeypatch.setattr(task, "call", read)
    monkeypatch.setattr(task, "blueprint_use", hint)
    return task, calls, hints

async def test_one_new_capture_discards_old_coordinates_and_pages_normally(native_construction, monkeypatch):
    client, state = native_construction
    state["runtime_page_size"] = 4
    task, calls, hints = await setup(client, state, monkeypatch)
    positions, _ = await task.collect_hints()
    assert len(hints) == 1 and len(positions) == 26
    assert [r["runtimeOffset"] for r in calls] == [0, 0, 4, 8, 12, 16, 20, 24]
    assert all(r["captureId"] == "fresh" for r in calls[2:])
    assert task.hint_capture_refresh["completed"] is True
    assert task.hint_capture_refresh["discardedCaptureId"] == "cold"

@pytest.mark.parametrize("fault", ["unknown", "context", "descriptor", "bytes", "trigger", "owner"])
async def test_other_losses_never_request_another_hint(native_construction, monkeypatch, fault):
    client, state = native_construction
    task, calls, hints = await setup(client, state, monkeypatch, fault)
    with pytest.raises(BridgeError): await task.collect_hints()
    assert not hints and len(calls) == 1 and task.hint_capture_refresh is None

@pytest.mark.parametrize("fault", ["second_loss", "changed_mode", "same_capture", "unknown_use"])
async def test_second_loss_or_changed_context_stops_without_further_use(native_construction, monkeypatch, fault):
    client, state = native_construction
    task, calls, hints = await setup(client, state, monkeypatch, fault)
    with pytest.raises(BridgeError): await task.collect_hints()
    assert len(hints) == 1 and len(calls) <= 2
    assert task.hint_capture_refresh["completed"] is False
