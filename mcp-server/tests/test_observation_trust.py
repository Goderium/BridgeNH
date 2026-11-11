"""Frozen capture provenance, real-use receipts and failure recovery; mock transport only."""

import asyncio
from copy import deepcopy

import anyio
import pytest
from jsonschema.exceptions import ValidationError

from minecraft_codex_bridge.agent_instructions import AGENT_INSTRUCTIONS
from minecraft_codex_bridge.bridge import BridgeError, _ContextData
from minecraft_codex_bridge.interaction_approach import _recovery_observation
from minecraft_codex_bridge.interaction_samples import _reported_metrics, run_interaction_samples
from minecraft_codex_bridge.operation_policy import route
from minecraft_codex_bridge.server import _capture_page_valid, machine_capture_pages
from minecraft_codex_bridge.validation import tool_validator


BIND = {"expectedSessionId": "session", "expectedWorld": "Debug"}
BACKGROUND = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}


def page(offset=0, total=3):
    return {"captureId": "frozen", "sessionId": "session", "worldFolder": "Debug", "dimension": 180,
            "worldTick": 123, "endWorldTick": 123, "capturedAtMillis": 1000,
            "targetCount": 1, "targets": [{"x": 0, "y": 64, "z": 0}], "totalEntries": total,
            "source": "integrated_server_frozen_capture", "frozenAcrossPages": True,
            "sameWorldTick": True, "sameServerCallback": True, "captureTruncated": False,
            "entries": [{"valueSnbt": "9223372036854775807L"}], "offset": offset,
            "nextOffset": offset + 1, "hasMore": offset + 1 < total, "complete": offset + 1 == total}


class Pages:
    def __init__(self, fault=None):
        self.calls, self.fault = [], fault

    async def invoke(self, operation, args):
        return await self.call(operation, args)

    async def call(self, operation, args):
        self.calls.append((operation, deepcopy(args)))
        result = page(args.get("offset", 0))
        if len(self.calls) > 1 and self.fault:
            result.update(self.fault)
        return result


async def test_frozen_pages_merge_only_same_capture_and_do_not_recreate():
    client = Pages()
    result = await machine_capture_pages(client, {**BIND, "positions": [{"x": 0, "y": 64, "z": 0}]})
    assert result["complete"] and result["queryPages"] == result["retainedPages"] == 3
    assert len(result["entries"]) == 3 and all(r["valueSnbt"] == "9223372036854775807L" for r in result["entries"])
    assert all(args["captureId"] == "frozen" and "positions" not in args for _, args in client.calls[1:])


@pytest.mark.parametrize("fault", [{"captureId": "other"}, {"worldTick": 124, "endWorldTick": 124},
    {"dimension": 0}, {"totalEntries": 4}, {"targets": [{"x": 99}]}, {"capturedAtMillis": 1001},
    {"offset": True}, {"nextOffset": 1}, {"captureTruncated": True}, {"source": "waila"}])
async def test_bad_continuation_never_combines_or_recaptures(fault):
    client = Pages(fault)
    with pytest.raises(BridgeError) as caught:
        await machine_capture_pages(client, {**BIND, "positions": [{"x": 0, "y": 64, "z": 0}]})
    assert caught.value.code == "CAPTURE_CONTEXT_CHANGED" and len(client.calls) == 2
    assert caught.value.details["retainedCapture"]["entries"] == page()["entries"]


@pytest.mark.parametrize("fault", [{"dimension": True}, {"worldTick": True}, {"endWorldTick": 124},
    {"worldFolder": ""}, {"sessionId": ""}, {"offset": False}, {"targetCount": True}, {"targets": []}])
def test_malformed_initial_page_is_never_trusted(fault):
    assert not _capture_page_valid({**page(), **fault}, 0)
    assert not _capture_page_valid(None, 0)


async def test_page_budget_preserves_usable_continuation():
    client = Pages()
    result = await machine_capture_pages(client, {**BIND, "captureId": "frozen", "maxPages": 1})
    assert result["hasMore"] and result["nextOffset"] == 1 and len(client.calls) == 1


@pytest.mark.parametrize("args", [{**BIND}, {**BIND, "positions": [], "includeNbt": True},
    {**BIND, "captureId": "f", "includeNbt": True}, {**BIND, "captureId": "f", "positions": []},
    {**BIND, "positions": [{"x": 0, "y": 64, "z": 0}], "offset": 1}])
def test_capture_schema_rejects_ambiguous_requests(args):
    with pytest.raises(ValidationError):
        tool_validator("mcb_machine_capture").validate(args)


@pytest.mark.parametrize("remote", [False, True])
def test_public_catalog_is_independent_of_machine_authority(remote):
    health = {"profile": "player", "readiness": {"remoteServerReady": remote, "localIntegratedServer": not remote},
              "recipeQueryAccess": {"version": 1, "publicCatalog": True,
                                    "localMachineBackend": False, "remoteMachineBackend": False}}
    assert route("recipes.query", {"map": "visible"}, health)[0] == "recipes.query"
    with pytest.raises(BridgeError, match="Machine/backend"):
        route("recipes.query", {"mode": "backend_match", "x": 0, "y": 64, "z": 0}, health)


def test_teleport_session_schema_and_bounded_instructions():
    tool_validator("mcb_teleport").validate({**BIND, "x": 0.5, "y": 65, "z": 0.5})
    assert len(AGENT_INSTRUCTIONS.encode("utf-8")) <= 4096


class Samples:
    def __init__(self, fault=None):
        self.calls, self.uses, self.messages, self.fault = [], 0, 0, fault

    def snapshot(self):
        return {"sessionId": "session", "profile": "developer", "worldLoaded": True,
                "world": {"worldName": "Debug", "dimension": 180}, "screen": "",
                "background": BACKGROUND, "player": {"health": 20},
                "serverControl": {"available": True, "playerId": "actor"}}

    async def invoke(self, operation, args):
        assert operation == "bridge.health"
        self.calls.append(operation)
        return {"sessionId": "session", "profile": "developer", "readiness": {"localIntegratedServer": True},
                "interactionHeldBindingVersion": 1, "interactionViewBinding": True,
                "interactionBlockTargeting": True, "background": BACKGROUND,
                "contextRpc": {"reads": ["client.messages"], "actions": ["player.interact"]}}

    async def call(self, operation, args):
        self.calls.append(operation)
        phase, snapshot = "after", self.snapshot()
        if operation == "inventory.inspect":
            data = {"playerId": "actor", "selectedHotbarSlot": 2,
                    "slots": [{"index": 2, "item": {"registryName": "gregtech:instrument", "metadata": 7}}]}
        elif operation == "player.interact":
            assert args["expectedHeld"] == {"slot": 2, "registryName": "gregtech:instrument", "metadata": 7}
            self.uses += 1
            if self.fault == "cancel":
                raise asyncio.CancelledError()
            if self.fault == "held" and self.uses == 2:
                raise BridgeError("held_item_changed", "Changed before use", outcome_unknown=False)
            data, phase = {"accepted": True, "guiOpen": self.fault == "gui"}, "before"
        else:
            assert operation == "client.messages"
            self.messages += 1
            data = {"latestSeq": self.uses, "nextSince": self.uses, "messages": [],
                    "historyGap": self.fault == "gap" and self.uses > 0}
            if self.fault == "dimension" and self.uses:
                snapshot["world"]["dimension"] = 0
        return _ContextData(data, {"phase": phase, "snapshot": snapshot})


async def sample(client, **updates):
    return await run_interaction_samples(client, {**BIND, "x": 0, "y": 64, "z": 0,
        "count": 2, "interval_ms": 100, "timeout_ms": 2000, **updates}, anyio.Lock(),
        require_capability=lambda *_: None, full_background=lambda value: value == BACKGROUND)


async def test_sampling_performs_exact_new_uses_with_incremental_reads():
    client = Samples()
    result = await sample(client)
    assert result["completedActions"] == client.uses == 2 and result["messagesComplete"]
    assert client.calls.count("bridge.health") == 1 and client.messages == 3
    assert all(row["actionWallMillis"] >= 0 for row in result["samples"])


@pytest.mark.parametrize("fault,code,completed", [("gui", "SAMPLING_GUI_OPENED", 1),
    ("gap", "SAMPLING_MESSAGE_GAP", 1), ("dimension", "SAMPLING_CONTEXT_CHANGED", 1),
    ("held", "held_item_changed", 1)])
async def test_sampling_stops_without_replaying_receipted_actions(fault, code, completed):
    client = Samples(fault)
    with pytest.raises(BridgeError) as caught:
        await sample(client)
    assert caught.value.code == code
    assert caught.value.details["receipt"]["completedActions"] == completed
    assert len(caught.value.details["receipt"]["samples"]) == completed
    assert client.uses <= 2


async def test_sampling_cancellation_never_continues_remaining_uses():
    client = Samples("cancel")
    with pytest.raises(asyncio.CancelledError):
        await sample(client)
    assert client.uses == 1


async def test_sampling_rejects_impossible_budget_before_any_read_or_action():
    client = Samples()
    with pytest.raises(BridgeError, match="Requested waits"):
        await sample(client, timeout_ms=400)
    assert client.calls == []


def test_instrument_metrics_require_explicit_trusted_units_not_number_positions():
    assert _reported_metrics({"seq": 3, "text": "average: 90 ns worst: 200 ns over 25 ticks"})["averageNanos"] == 90
    assert not _reported_metrics({"seq": 4, "text": "平均 90 tick, 25 ns, 最坏 200 ns"})["available"]
    assert not _reported_metrics({"seq": 5, "text": "average: 90 ns worst: 200 ns over 25 ticks", "textTruncated": True})["available"]


async def test_recovery_failure_cannot_mask_original_action_error():
    result = await _recovery_observation(object(), {**BIND, "dimension": 180})
    assert not result["available"] and result["errorType"] == "AttributeError"
