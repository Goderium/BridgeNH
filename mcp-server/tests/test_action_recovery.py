"""Only explicit, freshly verified placement resets release one replay guard."""
from copy import deepcopy
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.action_recovery import action_id, manage, reconciled
from minecraft_codex_bridge.bridge import BridgeError, _ContextData
from minecraft_codex_bridge.validation import validate_tool_arguments
from test_backend_supervisor import mock_worker

ARGS = dict(x=2, y=4, z=3, hotbarSlot=0, expectedWorld="fixture-world", dimension=0)
GUARDS = dict(expectedSessionId="new-session", expectedWorld="fixture-world", dimension=0)


def record():
    return dict(tool="mcb_place_block", arguments=deepcopy(ARGS), result=dict(ok=True, data=dict(
        source="client_native", playerMode="creative", nativeInvoked=True, submitted=True,
        attackSubmitted=False, airUseFallback=False, replaceRequested=False, expectedSourceConsumption=0,
        sourceCountBefore=1, sourceCountAfter=1, sessionId="old-session", dimension=0,
        targetBefore=dict(registryName="minecraft:air"), target=dict(x=2, y=4, z=3),
        attachment=dict(x=2, y=3, z=3, side=1), outcomeUnknown=True)))


def snapshot():
    return dict(sessionId="new-session", profile="developer", worldLoaded=True,
        world=dict(worldFolder="fixture-world", worldName="Fixture world", dimension=0, singleplayer=True),
        player=dict(health=20, creative=True), serverControl=dict(available=True, source="integrated_server_tick",
            sessionId="new-session", dimension=0, playerId="fixture-player", ageMillis=1),
        inputTask=dict(status="idle", owner="none", ownedKeyCount=0), inputKeys=[], inputTicksRemaining=0)


def configure(worker, monkeypatch):
    state = dict(calls=[], snapshot=snapshot(), occupied=False, mismatch=False, support=True,
        item=dict(registryName="fixture:controller", metadata=1, count=1, hasTag=True, nbtIdentity='{fixture:1b}'))
    async def call(operation, args, **kwargs):
        state["calls"].append((operation, deepcopy(args)))
        if operation == "baubles.inspect":
            item = deepcopy(state["item"])
            if state.get("server_nbt") is not None and args["source"] == "server":
                item["nbtIdentity"] = state["server_nbt"]
            if state["mismatch"] and args["source"] == "server":
                item["nbtIdentity"] = '{fixture:2b}'
            data = dict(supported=True, readOnly=True, source="client" if args["source"] == "client" else "integrated_server",
                sessionId="new-session", playerId="fixture-player", dimension=0, creative=True,
                worldFolder="fixture-world", cursorItem=None, playerInventory=dict(complete=True, truncated=False,
                    totalSlots=41, slots=[dict(index=i, item=item if i == 0 else None) for i in range(41)]))
        elif operation == "world.inspect_many":
            data = dict(source="integrated_server", dimension=0, results=[dict(ok=True, data=dict(
                **point, source="integrated_server", dimension=0, metadata=0,
                registryName=("fixture:block" if state["occupied"] else "minecraft:air") if i == 0
                    else ("fixture:support" if state["support"] else "minecraft:air"))) for i, point in enumerate(args["positions"])])
        else:
            pytest.fail("Recovery dispatched a mutation or an unbounded operation: " + operation)
        return _ContextData(data, dict(version=1, operation=operation, phase="after", snapshot=deepcopy(state["snapshot"])))
    monkeypatch.setattr(worker.client.configuration, "read", lambda: SimpleNamespace(session_id="new-session"))
    monkeypatch.setattr(worker.client, "call", call)
    worker.failed_actions = [record()]
    worker.uncertain = True
    return state


async def test_explicit_cross_session_reset_survives_handoff_without_replaying(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    original = deepcopy(worker.failed_actions[0])
    args = {**GUARDS, "actionId": action_id(original)}
    try:
        assert worker.replay_blocked("mcb_place_block", ARGS)
        listed = await manage(worker, {"action": "list"})
        assert listed["total"] == 1 and not state["calls"]
        inspected = await manage(worker, {**args, "action": "inspect"})
        assert inspected["guardStillActive"] and worker.replay_blocked("mcb_place_block", ARGS)
        evidence = inspected["evidence"]
        assert evidence["originalSessionId"] == "old-session" and evidence["sessionId"] == "new-session"
        request = {**args, "action": "reconcile", "evidenceFingerprint": evidence["evidenceFingerprint"],
            "acknowledgement": "allow_new_placement_after_verified_reset"}
        validate_tool_arguments("mcb_action_recovery", request)
        result = await manage(worker, request)
        assert result["status"] == "reconciled" and result["gameMutationDispatched"] is False
        assert not worker.replay_blocked("mcb_place_block", ARGS)
        assert worker.failed_actions[0]["result"]["data"] == original["result"]["data"]
        assert action_id(worker.failed_actions[0]) == args["actionId"]
        checkpoint = await worker.checkpoint()
        other = mock_worker(tmp_path)
        try:
            await other.dispatch("restore_state", checkpoint)
            assert not other.replay_blocked("mcb_place_block", ARGS)
            assert other.failed_actions[0] == worker.failed_actions[0]
        finally:
            await other.client.aclose()
        count = len(state["calls"])
        assert (await manage(worker, request))["status"] == "already_reconciled"
        assert len(state["calls"]) == count == 6
        worker.remember_unknown("mcb_place_block", ARGS, deepcopy(original["result"]))
        assert worker.replay_blocked("mcb_place_block", ARGS)
        assert action_id(worker.failed_actions[-1]) != args["actionId"]
        assert (await manage(worker, request))["status"] == "already_reconciled"
        assert worker.replay_blocked("mcb_place_block", ARGS)
        worker.remember_unknown("mcb_place_block", ARGS, {"ok": False, "error": {"outcomeUnknown": True}})
        assert worker.replay_blocked("mcb_place_block", ARGS)
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("change", ["occupied", "mismatch", "support", "session", "world", "folder", "dimension", "actor", "input", "creative", "partial"])
async def test_incomplete_or_changed_native_evidence_keeps_original_guard(tmp_path, monkeypatch, change):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    if change in ("occupied", "mismatch"):
        state[change] = True
    elif change == "support":
        state[change] = False
    elif change == "session":
        state["snapshot"]["sessionId"] = "another-session"
    elif change == "world":
        state["snapshot"]["world"].update(worldFolder="another", worldName="another")
    elif change == "folder":
        state["snapshot"]["world"].update(worldFolder="another", worldName="fixture-world")
    elif change == "dimension":
        state["snapshot"]["world"]["dimension"] = 1
    elif change == "actor":
        state["snapshot"]["serverControl"]["playerId"] = "another-player"
    elif change == "input":
        state["snapshot"]["inputTask"]["status"] = "running"
    elif change == "creative":
        state["snapshot"]["player"]["creative"] = False
    else:
        state["item"].pop("nbtIdentity")
    try:
        with pytest.raises(BridgeError):
            await manage(worker, {**GUARDS, "action": "inspect", "actionId": action_id(worker.failed_actions[0])})
        assert worker.replay_blocked("mcb_place_block", ARGS) and not reconciled(worker.failed_actions[0])
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("change", ["transport", "survival", "source_loss", "other_tool", "replacement"])
async def test_unsupported_original_effects_are_refused_without_game_reads(tmp_path, monkeypatch, change):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    row = worker.failed_actions[0]
    if change == "transport":
        row["result"] = dict(ok=False, error=dict(code="HTTP_ERROR", outcome_unknown=True))
    elif change == "other_tool":
        row["tool"] = "mcb_interact"
    else:
        key, value = {"survival": ("playerMode", "survival"), "source_loss": ("sourceCountAfter", 0),
            "replacement": ("replaceRequested", True)}[change]
        row["result"]["data"][key] = value
    try:
        with pytest.raises(BridgeError, match="."):
            await manage(worker, {**GUARDS, "action": "inspect", "actionId": action_id(row)})
        assert state["calls"] == [] and not reconciled(row)
    finally:
        await worker.client.aclose()


async def test_fingerprint_change_requires_new_inspection(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    args = {**GUARDS, "actionId": action_id(worker.failed_actions[0])}
    try:
        inspected = await manage(worker, {**args, "action": "inspect"})
        state["item"]["nbtIdentity"] = '{fixture:2b}'
        with pytest.raises(BridgeError, match="State changed"):
            await manage(worker, {**args, "action": "reconcile", "evidenceFingerprint": inspected["evidence"]["evidenceFingerprint"],
                "acknowledgement": "allow_new_placement_after_verified_reset"})
        assert worker.replay_blocked("mcb_place_block", ARGS)
    finally:
        await worker.client.aclose()


@pytest.mark.parametrize("extra", [{}, {"acknowledgement": "yes"}, {"acknowledgement": True}])
def test_reconciliation_requires_explicit_supported_acknowledgement(extra):
    with pytest.raises(BridgeError):
        validate_tool_arguments("mcb_action_recovery", {**GUARDS, "action": "reconcile", "actionId": "a" * 64,
            "evidenceFingerprint": "b" * 64, **extra})


async def test_public_worker_route_validates_and_releases_only_verified_action(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    await worker.dispatch("activate", {})
    async def call(args):
        return (await worker.dispatch("call_tool", dict(name="mcb_action_recovery", arguments=args)))["structuredContent"]
    try:
        listing = await call(dict(action="list"))
        assert listing["ok"] and state["calls"] == []
        args = dict(**GUARDS, actionId=listing["data"]["actions"][0]["actionId"])
        inspected = await call(dict(**args, action="inspect"))
        assert inspected["ok"] and inspected["data"]["guardStillActive"]
        request = dict(**args, action="reconcile", evidenceFingerprint=inspected["data"]["evidence"]["evidenceFingerprint"])
        invalid = await call(request)
        assert invalid["error"]["code"] == "INVALID_ARGUMENTS" and len(state["calls"]) == 3
        recovered = await call(dict(**request, acknowledgement="allow_new_placement_after_verified_reset"))
        assert recovered["ok"] and not recovered["data"]["gameMutationDispatched"]
        assert not worker.replay_blocked("mcb_place_block", ARGS)
        assert len(state["calls"]) == 6 and len(worker.failed_actions) == 1
    finally:
        await worker.client.aclose()


async def test_compound_order_does_not_mask_values_or_change_evidence_fingerprint(tmp_path, monkeypatch):
    worker = mock_worker(tmp_path)
    state = configure(worker, monkeypatch)
    state["item"]["nbtIdentity"] = '{display:{Name:"Fixture"},items:[0:{id:2s,count:1b}],flag:1b}'
    state["server_nbt"] = '{flag:1b,items:[0:{count:1b,id:2s}],display:{Name:"Fixture"}}'
    args = dict(**GUARDS, actionId=action_id(worker.failed_actions[0]), action="inspect")
    try:
        first = await manage(worker, args)
        state["server_nbt"] = state["item"]["nbtIdentity"]
        second = await manage(worker, args)
        assert first["evidence"]["evidenceFingerprint"] == second["evidence"]["evidenceFingerprint"]
        assert first["evidence"]["sourceItem"]["nbtIdentity"] != second["evidence"]["sourceItem"]["nbtIdentity"]
        state["server_nbt"] = state["server_nbt"].replace("count:1b", "count:1s")
        with pytest.raises(BridgeError, match="inventories disagree"):
            await manage(worker, args)
        assert worker.replay_blocked("mcb_place_block", ARGS)
    finally:
        await worker.client.aclose()
