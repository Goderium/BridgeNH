"""Offline consumer proof from sanitized historical R20 DTOs, not live RPCs."""
import asyncio
import copy
import gzip
import hashlib
import json
import os
import time
from pathlib import Path

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, _ContextData, request_context
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.workflows import run_workflow

pytestmark = pytest.mark.anyio
OP = "player.replace_owned_block"
CONTRACT = {"version": 1, "guardedOnly": True, "developerOnly": True, "oldTilePolicy": "absent",
    "ownership": "caller task provenance; bridge verifies exact current identity, not ownership inferred from registry",
    "fullPlayerSlotConservation": True}

@pytest.fixture
def anyio_backend():
    return "asyncio"

def _actual_r20_capture_path():
    configured = os.environ.get("MCB_TEST_R20_CAPTURE")
    if configured is None:
        return Path(__file__).resolve().parent / "fixtures" / "round-20-810-timing-compact-v1.json.gz"
    path = Path(configured)
    if not path.is_absolute():
        raise ValueError("MCB_TEST_R20_CAPTURE must be an absolute path")
    return path


def test_actual_r20_capture_path_uses_bundled_default(monkeypatch):
    monkeypatch.delenv("MCB_TEST_R20_CAPTURE", raising=False)
    expected = Path(__file__).resolve().parent / "fixtures" / "round-20-810-timing-compact-v1.json.gz"
    assert _actual_r20_capture_path() == expected


def test_actual_r20_capture_path_uses_absolute_override(monkeypatch):
    expected = Path(__file__).resolve().parent / "explicit-original-capture.json"
    monkeypatch.setenv("MCB_TEST_R20_CAPTURE", str(expected))
    assert _actual_r20_capture_path() == expected


@pytest.mark.parametrize("configured", ["", "relative-capture.json"])
def test_actual_r20_capture_path_rejects_nonabsolute_override(monkeypatch, configured):
    monkeypatch.setenv("MCB_TEST_R20_CAPTURE", configured)
    with pytest.raises(ValueError, match="MCB_TEST_R20_CAPTURE must be an absolute path"):
        _actual_r20_capture_path()


@pytest.fixture(scope="session")
def actual_r20():
    path = _actual_r20_capture_path()
    blob = path.read_bytes()
    if path.suffix == ".gz":
        blob = gzip.decompress(blob)
    assert hashlib.sha256(blob).hexdigest() == "e3dfe4f792f266ab129ec98e505bb8f8b014bcecd572e214f4c074bed177a0fd"
    stored = json.loads(blob)
    pool = stored["pool"]
    def decode(value):
        if isinstance(value, dict):
            if set(value) == {"$r14_exact_ref"}:
                return decode(pool[value["$r14_exact_ref"]])
            return {key: decode(child) for key, child in value.items()}
        if isinstance(value, list):
            return [decode(child) for child in value]
        return value
    return decode(stored["payload"])

@pytest.fixture
def consumer(actual_r20):
    return make_consumer(actual_r20)

def make_consumer(actual_r20, port_index=0):
    rows = {row["actualRpcIndex"]: row for row in actual_r20["actualWholeCaseCalls"]}
    contexts = {row["actualRpcIndex"]: row["responseContext"] for row in actual_r20["actualWholeCaseResponseContexts"]}
    old_index, attack_index, dig_index, plan_index, air_index, place_index, final_index = [
        (1350, 1353, 1357, 1359, 1360, 1361, 1362), (1363, 1364, 1368, 1370, 1371, 1372, 1373),
        (1374, 1375, 1379, 1381, 1386, 1387, 1388), (1389, 1390, 1394, 1396, 1397, 1398, 1399),
        (1400, 1401, 1405, 1407, 1408, 1409, 1410), (1411, 1412, 1420, 1422, 1423, 1424, 1425)][port_index]
    arguments = copy.deepcopy(actual_r20["case"]["construction"]["arguments"])
    point = {axis: rows[plan_index]["arguments"][axis] for axis in ("x", "y", "z")}
    slot = rows[plan_index]["arguments"]["hotbarSlot"]
    source = copy.deepcopy(rows[air_index]["result"]["playerInventory"]["slots"][slot]["item"])
    old = {key: rows[attack_index]["arguments"]["targetIdentity"][key] for key in ("registryName", "metadata")}
    before = copy.deepcopy(rows[air_index]["result"])
    before["results"][0]["data"] = copy.deepcopy(rows[old_index]["result"]["results"][0]["data"])
    samples = {"beforeDig": {"data": before, "context": copy.deepcopy(contexts[air_index])},
        "afterDig": {"data": copy.deepcopy(rows[air_index]["result"]), "context": copy.deepcopy(contexts[air_index])},
        "afterPlace": {"data": copy.deepcopy(rows[final_index]["result"]), "context": copy.deepcopy(contexts[final_index])}}
    for phase, context_index in (("beforeDig", dig_index),):
        context = copy.deepcopy(contexts[context_index])
        context.update(operation="world.inspect_many", phase="after")
        context["snapshot"]["player"]["playerId"] = samples[phase]["data"]["playerInventory"]["playerId"]
        samples[phase]["context"] = context
        samples[phase]["data"]["playerInventory"]["clientTicks"] = context["snapshot"]["clientTicks"]
    server = {}
    for phase, sample in samples.items():
        data = sample["data"]
        data["playerInventoryObservation"]["observationByteBudget"] = 1_048_576
        snapshot = sample["context"]["snapshot"]
        player = snapshot["player"]
        data["results"][0]["data"]["worldTick"] = 100
        server[phase] = {"source": "integrated_server", "dimension": arguments["dimension"], "worldTick": 100,
            "playerId": data["playerInventory"]["playerId"], "inventory": copy.deepcopy(data["playerInventory"]),
            "selectedHotbarSlot": data["playerInventory"]["selectedHotbarSlot"], "creative": True, "flying": True,
            "allowFlying": True, "feet": {"x": player["x"], "y": player["feetY"], "z": player["z"]}}
    receipt = {"version": 1, "operation": OP, "status": "completed", "stage": "completed", "nonAtomic": True,
        "nativeDigSubmitted": True, "nativePlaceSubmitted": True, "digConfirmed": True, "placeConfirmed": True,
        "replacementValidated": True, "observations": samples,
        "attackPlan": {"data": copy.deepcopy(rows[attack_index]["result"]), "context": copy.deepcopy(contexts[attack_index])},
        "placePlan": {"data": copy.deepcopy(rows[plan_index]["result"]), "context": copy.deepcopy(contexts[plan_index])},
        "nativeDig": copy.deepcopy(rows[dig_index]["result"]), "nativePlace": copy.deepcopy(rows[place_index]["result"]),
        "moves": [], "serverPlayers": server,
        "inventoryConservation": {"scope": "all_actual_player_slots", "actualClientSlots": 41, "actualServerSlots": 41,
            "clientItemsPreserved": True, "serverItemsPreserved": True, "nbtPreserved": True, "cursorEmpty": True,
            "creativeSourceCountUnchanged": True}}
    # R21 requires a fresh post-move ready planner. R20 kept only its pre-move
    # plan, so synthesize that new epoch from the real selected-body/ray proof.
    # These DTO-derived mock receipts do not represent a live R21 execution.
    for key, phase, teleport_index, snapshot_index in (("attackPlan", "before_dig", 1414, 1416),
            ("placePlan", "before_place", 1383, 1385)):
        ready = receipt[key]
        if ready["data"]["selected"]["moveRequired"]:
            original = copy.deepcopy(ready)
            selected = ready["data"]["selected"]
            current = ready["data"]["current"]
            current.update(selected["feet"], feetY=selected["feet"]["y"], eyeY=selected["eye"]["y"])
            selected["moveRequired"] = False
            ready["context"]["snapshot"]["player"].update(x=current["x"], feetY=current["y"],
                y=current["eyeY"], eyeY=current["eyeY"], z=current["z"])
            ready["context"]["snapshot"]["clientTicks"] = rows[snapshot_index]["result"]["clientTicks"]
            receipt["moves"].append({"phase": phase, "confirmed": True, "selectedFeet": copy.deepcopy(selected["feet"]),
                "plan": original, "nativeTeleport": copy.deepcopy(rows[teleport_index]["result"]),
                "afterSnapshot": copy.deepcopy(rows[snapshot_index]["result"])})
    outer = copy.deepcopy(contexts[attack_index])
    outer.update(operation=OP, phase="before")
    calls = []
    health = copy.deepcopy(rows[1283]["result"])
    health.update(nativeOwnedBlockReplacement=True, nativeOwnedBlockReplacementContract=copy.deepcopy(CONTRACT))
    if isinstance(health["capabilities"], list):
        health["capabilities"].append(OP)
    else:
        health["capabilities"][OP] = True
    health["contextRpc"]["actions"].append(OP)
    state = {"receipt": receipt, "outer": outer, "calls": calls, "failure": None,
        "health": health, "before": copy.deepcopy(rows[old_index]["result"]), "transport_fault": None,
        "cancel_status": "already_running", "cancel_calls": []}
    async def respond(request):
        body = json.loads(request.content)
        if request.url.path == "/v1/cancel":
            state["cancel_calls"].append(body)
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
                "data": {"status": state["cancel_status"]}})
        calls.append(body)
        operation = body["operation"]
        if operation == "bridge.health":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": state["health"]})
        if operation == OP:
            if state["transport_fault"] == "timeout": raise httpx.ReadTimeout("offline timeout", request=request)
            if state["transport_fault"] == "connection": raise httpx.ConnectError("offline loss", request=request)
            if state["transport_fault"] == "cancel": raise asyncio.CancelledError()
            if state["failure"] is not None:
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": state["failure"]})
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
                "data": state["receipt"], "context": state["outer"]})
        if operation == "world.inspect_many":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": state["before"]})
        raise AssertionError("Unexpected offline RPC: " + operation)
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "offline-test-token"}),
        transport=httpx.MockTransport(respond))
    task = _ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    task.credentials = client.configuration.read()
    task.dimension = arguments["dimension"]
    task.capabilities = health["capabilities"]
    task.approach_health = health
    task.empty_slot = 8
    task.materials = {slot: {"item": {key: source[key] for key in ("registryName", "metadata")}, "count": source["count"]}}
    task.selected_ports = [{"position": point, "hotbarSlot": slot, "item": task.materials[slot]["item"].copy(),
        "suggestedCandidates": [old], "supplyBinding": {"sourceHotbarSlot": slot, "sourceStackCount": source["count"]}}]
    return task, state

async def test_actual_dto_complete_replacement_preserves_source_remap_and_gt_identity(consumer):
    task, state = consumer
    await task.replace_ports()
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]
    arguments = state["calls"][1]["args"]
    assert arguments["hotbarSlot"] == 1 and arguments["expectedSourceItem"]["metadata"] == 74
    assert arguments["expectedPlacedIdentity"] == {"registryName": "gregtech:gt.blockmachines", "metaTileId": 74}
    assert arguments["pattern"] == "surround"
    assert state["calls"][1]["includeContext"]["requireBackground"] is True
    port = task.selected_ports[0]
    assert port["digAttempted"] and port["digConfirmed"] and port["placementAttempted"] and port["placementConfirmed"]
    assert port["block"]["metadata"] == 1 and port["supplyBinding"]["sourceIdentityAndCountVerified"]
    assert len(task.approach_observations) == 2 and task.side_effects

@pytest.mark.parametrize("advertised", [None, False])
async def test_absent_capability_uses_exact_legacy_before_new_rpc(consumer, advertised):
    task, state = consumer
    task.approach_health["nativeOwnedBlockReplacement"] = advertised
    legacy = []
    async def dig(*args, **kwargs): legacy.append("dig")
    async def place(*args, **kwargs): legacy.append("place")
    task.dig_owned, task.place = dig, place
    await task.replace_ports()
    assert legacy == ["dig", "place"]
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many"]

@pytest.mark.parametrize("fault", ["advertised_int", "version_bool", "missing_contract", "ownership", "full_slots", "operation", "context_action", "combined"])
async def test_bad_advertised_contract_refuses_before_any_rpc(consumer, fault):
    task, state = consumer
    health = task.approach_health
    if fault == "advertised_int": health["nativeOwnedBlockReplacement"] = 1
    elif fault == "version_bool": health["nativeOwnedBlockReplacementContract"]["version"] = True
    elif fault == "missing_contract": health.pop("nativeOwnedBlockReplacementContract")
    elif fault == "ownership": health["nativeOwnedBlockReplacementContract"]["ownership"] = "registry implies owned"
    elif fault == "full_slots": health["nativeOwnedBlockReplacementContract"]["fullPlayerSlotConservation"] = False
    elif fault == "operation": health["capabilities"].remove(OP)
    elif fault == "context_action": health["contextRpc"]["actions"].remove(OP)
    elif fault == "combined": health["worldInspectManyPlayerInventory"] = False
    with pytest.raises(BridgeError): await task.replace_ports()
    assert not state["calls"]

@pytest.mark.parametrize("phase", ["beforeDig", "afterDig", "afterPlace"])
@pytest.mark.parametrize("fault", ["context_phase", "context_operation", "context_version", "context_session", "context_background", "cursor", "coverage", "unrelated", "empty_hand", "server_inventory", "server_pose", "server_selection", "server_cursor", "client_flight"])
async def test_epoch_corruption_never_replays_or_claims_no_effect(consumer, phase, fault):
    task, state = consumer
    sample = state["receipt"]["observations"][phase]
    if fault.startswith("context_"):
        context = sample["context"]
        if fault == "context_phase": context["phase"] = "before"
        elif fault == "context_operation": context["operation"] = "inventory.inspect"
        elif fault == "context_version": context["version"] = True
        elif fault == "context_session": context["snapshot"]["sessionId"] = "other-session"
        elif fault == "context_background": context["snapshot"]["background"]["enabled"] = False
    elif fault == "cursor": sample["data"]["playerInventory"]["cursorItem"] = copy.deepcopy(sample["data"]["playerInventory"]["slots"][1]["item"])
    elif fault == "coverage": sample["data"]["playerInventory"]["slots"].pop()
    elif fault in ("unrelated", "empty_hand"):
        index = 20 if fault == "unrelated" else task.empty_slot
        item = copy.deepcopy(sample["data"]["playerInventory"]["slots"][1]["item"])
        sample["data"]["playerInventory"]["slots"][index]["item"] = item
        state["receipt"]["serverPlayers"][phase]["inventory"]["slots"][index]["item"] = copy.deepcopy(item)
    elif fault == "server_inventory": state["receipt"]["serverPlayers"][phase]["inventory"]["slots"][1]["item"]["count"] += 1
    elif fault == "server_pose": state["receipt"]["serverPlayers"][phase]["feet"]["x"] += .001
    elif fault == "server_selection": state["receipt"]["serverPlayers"][phase]["inventory"]["selectedHotbarSlot"] = 7
    elif fault == "server_cursor": state["receipt"]["serverPlayers"][phase]["inventory"]["cursorItem"] = {"count": 1}
    else: sample["context"]["snapshot"]["player"]["flying"] = False
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True
    assert task.selected_ports[0]["outcomeUnknown"] and task.last_action["outcomeUnknown"]
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]

@pytest.mark.parametrize("fault", ["nbt", "slot_count", "client", "server", "source", "scope", "missing", "int_true"])
async def test_full_native_itemstack_proof_is_required(consumer, fault):
    task, state = consumer
    proof = state["receipt"]["inventoryConservation"]
    if fault == "nbt": proof["nbtPreserved"] = False
    elif fault == "slot_count": proof["actualServerSlots"] = 40
    elif fault == "client": proof["clientItemsPreserved"] = False
    elif fault == "server": proof["serverItemsPreserved"] = False
    elif fault == "source": proof["creativeSourceCountUnchanged"] = False
    elif fault == "scope": proof["scope"] = "source_only"
    elif fault == "missing": proof.pop("nbtPreserved")
    else: proof["nbtPreserved"] = 1
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True
    assert len(state["calls"]) == 2

@pytest.mark.parametrize("plan", ["attackPlan", "placePlan"])
@pytest.mark.parametrize("fault", ["context", "identity", "flight", "body", "pattern", "missing"])
async def test_existing_planner_validator_is_reused(consumer, plan, fault):
    task, state = consumer
    bundle = state["receipt"][plan]
    if fault == "context": bundle["context"]["phase"] = "before"
    elif fault == "identity": bundle["data"]["target"]["metadata"] = 3
    elif fault == "flight": bundle["data"]["serverFlight"]["flying"] = False
    elif fault == "body": bundle["data"]["selected"]["serverBody"]["occupiable"] = False
    elif fault == "pattern": bundle["data"]["pattern"] = "cardinal"
    else: bundle.pop("context")
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("fault", ["gt", "gt_bool", "selected_bool", "target_bool", "target_float", "claimed_execution", "attachment", "owner", "old_tile", "after_dig", "move_missing_proof", "extra"])
async def test_native_identity_and_epoch_acceptance_are_not_weakened(consumer, fault):
    task, state = consumer
    receipt = state["receipt"]
    if fault == "gt": receipt["observations"]["afterPlace"]["data"]["results"][0]["data"]["gregtech"]["metaTileId"] = 54
    elif fault == "gt_bool":
        task.materials[1]["item"]["metadata"] = 1
        receipt["observations"]["afterPlace"]["data"]["results"][0]["data"]["gregtech"]["metaTileId"] = True
    elif fault == "selected_bool": receipt["nativePlace"]["selectedHotbarSlot"] = True
    elif fault == "target_bool": receipt["nativePlace"]["target"]["x"] = False
    elif fault == "target_float": receipt["nativePlace"]["target"]["x"] = float(receipt["nativePlace"]["target"]["x"])
    elif fault == "claimed_execution": receipt["nativePlace"]["executionValidated"] = True
    elif fault == "attachment": receipt["nativePlace"]["attachment"]["x"] += 1
    elif fault == "owner": receipt["observations"]["afterPlace"]["data"]["results"][0]["data"]["x"] += 1
    elif fault == "old_tile": receipt["observations"]["beforeDig"]["data"]["results"][0]["data"]["tileClass"] = "foreign.Tile"
    elif fault == "after_dig": receipt["observations"]["afterDig"]["data"]["results"][0]["data"]["registryName"] = "minecraft:stone"
    elif fault == "move_missing_proof": receipt["moves"] = [{"phase": "before_place", "confirmed": True}]
    else: receipt["staleCachedObservation"] = {}
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2
    if fault == "gt_bool": assert raised.value.message.startswith("The final real block/GT identity")

@pytest.mark.parametrize("stage,dig,place,dig_ok,place_ok,unknown", [
    ("prepare", False, False, False, False, False),
    ("native_dig", True, False, False, False, True),
    ("place_plan", True, False, True, False, True),
    ("after_place", True, True, True, False, True),
    ("final_observation", True, True, True, True, True)])
async def test_partial_failure_retains_only_known_checkpoints_and_never_falls_back(consumer, stage, dig, place, dig_ok, place_ok, unknown):
    task, state = consumer
    state["failure"] = {"code": "native_failed", "message": "offline-test-token must be redacted", "stage": stage,
        "nativeDigSubmitted": dig, "nativePlaceSubmitted": place, "digConfirmed": dig_ok, "placeConfirmed": place_ok,
        "outcome_unknown": unknown}
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert "offline-test-token" not in raised.value.message
    port = task.selected_ports[0]
    assert (port["digAttempted"], port["placementAttempted"], port["digConfirmed"], port["placementConfirmed"]) == (dig, place, dig_ok, place_ok)
    assert port["outcomeUnknown"] is unknown and task.last_action["outcomeUnknown"] is unknown
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]

@pytest.mark.parametrize("fault", ["stage", "long_stage", "bool", "missing", "contradiction", "false_unknown"])
async def test_endpoint_error_fields_are_strict_and_uncertain_on_malformed(consumer, fault):
    task, state = consumer
    failure = {"code": "native_failed", "message": "failure", "stage": "after_dig", "nativeDigSubmitted": True,
        "nativePlaceSubmitted": False, "digConfirmed": True, "placeConfirmed": False, "outcome_unknown": True}
    if fault == "stage": failure["stage"] = "arbitrary"
    elif fault == "long_stage": failure["stage"] = "x" * 10000
    elif fault == "bool": failure["nativeDigSubmitted"] = 1
    elif fault == "missing": failure.pop("digConfirmed")
    elif fault == "contradiction": failure["nativeDigSubmitted"] = False
    else: failure["outcome_unknown"] = False
    state["failure"] = failure
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.code == "MALFORMED_RESPONSE" and raised.value.details["outcome_unknown"] is True
    assert len(state["calls"]) == 2

@pytest.mark.parametrize("code", ["SESSION_CHANGED", "CONTEXT_GUARD_REJECTED", "INVALID_ARGUMENTS"])
async def test_standard_entry_error_has_no_invented_native_checkpoints_or_fallback(consumer, code):
    task, state = consumer
    state["failure"] = {"code": code, "message": "entry rejected", "outcome_unknown": False}
    with pytest.raises(BridgeError) as raised:
        await task.replace_ports()
    assert raised.value.code == code and raised.value.details == {"outcome_unknown": False}
    port = task.selected_ports[0]
    assert not port["outcomeUnknown"] and port["nativeReplacementFailure"] == {"outcome_unknown": False}
    assert not any(key in port for key in ("digAttempted", "placementAttempted", "digConfirmed", "placementConfirmed"))
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]

@pytest.mark.parametrize("fault", ["registry", "metadata", "count", "tag"])
async def test_plan_source_allocation_drift_stops_without_replay(consumer, fault):
    task, state = consumer
    item = state["receipt"]["placePlan"]["data"]["current"]["sourceItem"]
    if fault == "registry": item["registryName"] = "minecraft:stone"
    elif fault == "metadata": item["metadata"] += 1
    elif fault == "count": item["count"] += 1
    else: item["hasTag"] = True
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]

async def test_runtime_credentials_drift_blocks_native_dispatch(consumer):
    task, state = consumer
    original = task.block
    async def rotate(point):
        block = await original(point)
        task.client.configuration.environ["MCB_TOKEN"] = "rotated-test-token"
        return block
    task.block = rotate
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.code == "SESSION_CHANGED" and raised.value.details["outcome_unknown"] is False
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many"]
    assert not task.side_effects

async def test_before_dig_must_select_exact_empty_hand_on_both_sides(consumer):
    task, state = consumer
    epoch = state["receipt"]["observations"]["beforeDig"]
    epoch["data"]["playerInventory"]["selectedHotbarSlot"] = 1
    epoch["context"]["snapshot"]["player"]["selectedHotbarSlot"] = 1
    state["receipt"]["serverPlayers"]["beforeDig"]["selectedHotbarSlot"] = 1
    state["receipt"]["serverPlayers"]["beforeDig"]["inventory"]["selectedHotbarSlot"] = 1
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.message.startswith("Digging must retain the exact empty hand")
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("fault,status,unknown", [
    ("timeout", "cancelled", False), ("timeout", "already_running", True),
    ("timeout", "not_found", True), ("connection", "already_running", True),
    ("cancel", "cancelled", False), ("cancel", "already_running", True)])
async def test_transport_failure_and_cancel_never_replay(consumer, fault, status, unknown):
    task, state = consumer
    state.update(transport_fault=fault, cancel_status=status)
    exception = asyncio.CancelledError if fault == "cancel" else BridgeError
    with pytest.raises(exception): await task.replace_ports()
    assert task.selected_ports[0]["outcomeUnknown"] is unknown and task.last_action["outcomeUnknown"] is unknown
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]
    assert state["cancel_calls"] == [{"requestId": state["calls"][-1]["requestId"]}]

async def test_multiport_sequence_stops_at_first_native_failure(consumer):
    task, state = consumer
    next_port = copy.deepcopy(task.selected_ports[0])
    next_port["position"]["x"] += 1
    task.selected_ports.append(next_port)
    state["failure"] = {"code": "place_failed", "message": "stop", "stage": "native_place",
        "nativeDigSubmitted": True, "nativePlaceSubmitted": False, "digConfirmed": True,
        "placeConfirmed": False, "outcome_unknown": True}
    with pytest.raises(BridgeError): await task.replace_ports()
    assert [row["operation"] for row in state["calls"]] == ["world.inspect_many", OP]
    assert not any(key in next_port for key in ("constructedBaseline", "nativeReplacementAttempted", "placementAttempted"))

@pytest.mark.parametrize("port_index,phase", [(2, "before_place"), (5, "before_dig")])
async def test_complete_original_move_proofs_are_consumed(actual_r20, port_index, phase):
    task, state = make_consumer(actual_r20, port_index)
    assert [entry["phase"] for entry in state["receipt"]["moves"]] == [phase]
    await task.replace_ports()
    assert task.selected_ports[0]["placementConfirmed"] and len(state["calls"]) == 2

@pytest.mark.parametrize("fault", ["teleport_missing", "teleport_destination", "snapshot_destination", "plan_context", "source_count", "duplicate_phase", "ready_epoch"])
async def test_move_epoch_corruption_stops_after_single_native_rpc(actual_r20, fault):
    task, state = make_consumer(actual_r20, 2)
    move = state["receipt"]["moves"][0]
    if fault == "teleport_missing": move["nativeTeleport"] = {"claimed": True}
    elif fault == "teleport_destination": move["nativeTeleport"]["after"]["x"] += .01
    elif fault == "snapshot_destination": move["afterSnapshot"]["player"]["x"] += .01
    elif fault == "plan_context": move["plan"]["context"]["operation"] = "world.inspect_many"
    elif fault == "source_count": move["plan"]["data"]["current"]["sourceItem"]["count"] += 1
    elif fault == "duplicate_phase": state["receipt"]["moves"].append(copy.deepcopy(move))
    else: state["receipt"]["placePlan"]["context"]["snapshot"]["clientTicks"] = 0
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("field", ["selectedFeet", "afterSnapshot"])
async def test_equal_boolean_move_y_cannot_replace_validated_numeric_one(actual_r20, field):
    task, state = make_consumer(actual_r20, 5)
    def lower_geometry(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"y", "feetY", "eyeY", "minY", "maxY", "hitY"} and type(child) in (int, float):
                    value[key] = child - 1
                else:
                    lower_geometry(child)
        elif isinstance(value, list):
            for child in value: lower_geometry(child)
    for value in (state["receipt"], state["outer"], state["before"], task.selected_ports[0]["position"]):
        lower_geometry(value)
    port = task.selected_ports[0]
    material = task.materials[port["hotbarSlot"]]
    expected = {"registryName": material["item"]["registryName"], "metaTileId": material["item"]["metadata"]}
    # First prove the whole translated DTO remains valid through the actual
    # validators, then corrupt only selectedFeet, preserving numeric plan feet.
    task.validate_owned_replacement(_ContextData(state["receipt"], state["outer"]), port["position"],
        port["hotbarSlot"], port["suggestedCandidates"][0], expected)
    move = state["receipt"]["moves"][0]
    assert move["plan"]["data"]["selected"]["feet"]["y"] == 1
    assert type(move["plan"]["data"]["selected"]["feet"]["y"]) in (int, float)
    if field == "selectedFeet":
        move["selectedFeet"]["y"] = True
        assert move["selectedFeet"] == move["plan"]["data"]["selected"]["feet"]
    else:
        move["afterSnapshot"]["player"]["feetY"] = True
        assert move["afterSnapshot"]["player"]["feetY"] == move["plan"]["data"]["selected"]["feet"]["y"]
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    expected_message = "Move-selected feet need exact XYZ keys" if field == "selectedFeet" else "The native move must end at the exact current selected feet"
    assert raised.value.message.startswith(expected_message)
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("phase", ["beforeDig", "afterDig", "afterPlace"])
@pytest.mark.parametrize("fault", ["count_bool", "metadata_float", "metadata_bool", "tag_int", "cursor_missing", "complete_false", "name_missing", "name_number"])
async def test_server_public_inventory_requires_complete_typed_item_dtos(consumer, phase, fault):
    task, state = consumer
    if fault == "metadata_bool":
        # Preserve one legitimate metadata-zero unrelated item identically in
        # every client/server epoch, then change only one server type to bool.
        item = {"registryName": "minecraft:stone", "displayName": "Stone", "metadata": 0, "count": 1, "hasTag": False}
        for epoch in ("beforeDig", "afterDig", "afterPlace"):
            state["receipt"]["observations"][epoch]["data"]["playerInventory"]["slots"][20]["item"] = copy.deepcopy(item)
            state["receipt"]["serverPlayers"][epoch]["inventory"]["slots"][20]["item"] = copy.deepcopy(item)
    port = task.selected_ports[0]
    material = task.materials[port["hotbarSlot"]]
    expected = {"registryName": material["item"]["registryName"], "metaTileId": material["item"]["metadata"]}
    task.validate_owned_replacement(_ContextData(state["receipt"], state["outer"]), port["position"],
        port["hotbarSlot"], port["suggestedCandidates"][0], expected)
    inventory = state["receipt"]["serverPlayers"][phase]["inventory"]
    item = inventory["slots"][1]["item"]
    if fault == "count_bool":
        assert item["count"] == 1
        item["count"] = True
    elif fault == "metadata_float": item["metadata"] = float(item["metadata"])
    elif fault == "metadata_bool": inventory["slots"][20]["item"]["metadata"] = False
    elif fault == "tag_int": item["hasTag"] = 0
    elif fault == "cursor_missing": inventory.pop("cursorItem")
    elif fault == "complete_false": inventory["complete"] = False
    elif fault == "name_missing": inventory.pop("name")
    else: inventory["name"] = 1
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("location", ["outer", "beforeDig", "afterDig", "afterPlace", "attackPlan", "placePlan",
    "digMovePlan", "digMoveAfter", "placeMovePlan", "placeMoveAfter"])
@pytest.mark.parametrize("fault", ["world_bool", "flight_bool", "tick_bool", "tick_negative", "tick_missing"])
async def test_all_retained_snapshots_require_typed_dimensions_and_client_epochs(actual_r20, location, fault):
    port_index = 2 if location.startswith("placeMove") else 5 if location.startswith("digMove") else 0
    task, state = make_consumer(actual_r20, port_index)
    if fault in ("world_bool", "flight_bool"):
        def move_to_dimension_zero(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    if key == "dimension" and type(child) is int: value[key] = 0
                    else: move_to_dimension_zero(child)
            elif isinstance(value, list):
                for child in value: move_to_dimension_zero(child)
        for value in (state["receipt"], state["outer"], state["before"], task.args):
            move_to_dimension_zero(value)
        task.dimension = 0
    port = task.selected_ports[0]
    material = task.materials[port["hotbarSlot"]]
    expected = {"registryName": material["item"]["registryName"], "metaTileId": material["item"]["metadata"]}
    task.validate_owned_replacement(_ContextData(state["receipt"], state["outer"]), port["position"],
        port["hotbarSlot"], port["suggestedCandidates"][0], expected)
    if location == "outer": snapshot = state["outer"]["snapshot"]
    elif location in ("beforeDig", "afterDig", "afterPlace"):
        snapshot = state["receipt"]["observations"][location]["context"]["snapshot"]
    elif location in ("attackPlan", "placePlan"): snapshot = state["receipt"][location]["context"]["snapshot"]
    else:
        move = state["receipt"]["moves"][0]
        snapshot = move["plan"]["context"]["snapshot"] if location.endswith("Plan") else move["afterSnapshot"]
    if fault == "world_bool":
        assert snapshot["world"]["dimension"] == 0
        snapshot["world"]["dimension"] = False
    elif fault == "flight_bool":
        assert snapshot["player"]["serverFlight"]["dimension"] == 0
        snapshot["player"]["serverFlight"]["dimension"] = False
    elif fault == "tick_bool": snapshot["clientTicks"] = True
    elif fault == "tick_negative": snapshot["clientTicks"] = -1
    else: snapshot.pop("clientTicks")
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.message.startswith("Every retained snapshot needs its exact typed")
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("port_index", [2, 5])
@pytest.mark.parametrize("fault", ["plan_before_prior", "plan_after_destination", "destination_before_plan", "destination_after_ready"])
async def test_original_move_and_destination_epochs_bind_to_prior_and_ready_proofs(actual_r20, port_index, fault):
    task, state = make_consumer(actual_r20, port_index)
    port = task.selected_ports[0]
    material = task.materials[port["hotbarSlot"]]
    expected = {"registryName": material["item"]["registryName"], "metaTileId": material["item"]["metadata"]}
    task.validate_owned_replacement(_ContextData(state["receipt"], state["outer"]), port["position"],
        port["hotbarSlot"], port["suggestedCandidates"][0], expected)
    move = state["receipt"]["moves"][0]
    original = move["plan"]["context"]["snapshot"]
    destination = move["afterSnapshot"]
    key = "attackPlan" if move["phase"] == "before_dig" else "placePlan"
    lower = state["outer"]["snapshot"]["clientTicks"] if key == "attackPlan" else state["receipt"]["observations"]["beforeDig"]["context"]["snapshot"]["clientTicks"]
    ready = state["receipt"][key]["context"]["snapshot"]["clientTicks"]
    if fault == "plan_before_prior": original["clientTicks"] = lower - 1
    elif fault == "plan_after_destination": original["clientTicks"] = destination["clientTicks"] + 1
    elif fault == "destination_before_plan": destination["clientTicks"] = original["clientTicks"] - 1
    else: destination["clientTicks"] = ready + 1
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.message.startswith("Every original move plan and fresh destination")
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

@pytest.mark.parametrize("phase", ["beforeDig", "afterDig", "afterPlace"])
@pytest.mark.parametrize("fault", ["missing", "bool", "drift"])
async def test_original_owner_server_epoch_requires_typed_same_callback_binding(consumer, phase, fault):
    task, state = consumer
    port = task.selected_ports[0]
    material = task.materials[port["hotbarSlot"]]
    expected = {"registryName": material["item"]["registryName"], "metaTileId": material["item"]["metadata"]}
    task.validate_owned_replacement(_ContextData(state["receipt"], state["outer"]), port["position"],
        port["hotbarSlot"], port["suggestedCandidates"][0], expected)
    owner = state["receipt"]["observations"][phase]["data"]["results"][0]["data"]
    if fault == "missing": owner.pop("worldTick")
    elif fault == "bool": owner["worldTick"] = True
    else: owner["worldTick"] -= 1
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.message.startswith("Each original owner and authoritative player")
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2

async def test_all_six_actual_source_allocations_keep_order_and_individual_receipts(actual_r20):
    cases = [make_consumer(actual_r20, index) for index in range(6)]
    task = cases[0][0]
    task.materials = {slot: material for other, _ in cases for slot, material in other.materials.items()}
    task.selected_ports = [other.selected_ports[0] for other, _ in cases]
    calls = []
    async def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        args = body["args"]
        point = args["positions"][0] if body["operation"] == "world.inspect_many" else {axis: args[axis] for axis in ("x", "y", "z")}
        other, state = next((other, state) for other, state in cases if other.selected_ports[0]["position"] == point)
        if body["operation"] == OP:
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True,
                "data": state["receipt"], "context": state["outer"]})
        assert body["operation"] == "world.inspect_many"
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": state["before"]})
    task.client = BridgeClient(task.client.configuration, transport=httpx.MockTransport(respond))
    await task.replace_ports()
    assert [row["operation"] for row in calls] == ["world.inspect_many", OP] * 6
    native = [row for row in calls if row["operation"] == OP]
    assert [row["args"]["hotbarSlot"] for row in native] == [1, 2, 3, 4, 5, 6]
    assert all(port["placementConfirmed"] and port["supplyBinding"]["sourceIdentityAndCountVerified"] for port in task.selected_ports)
    assert all(row["args"]["expectedPlacedIdentity"]["metaTileId"] == task.materials[row["args"]["hotbarSlot"]]["item"]["metadata"] for row in native)

async def test_old_endpoint_error_semantics_are_unchanged():
    async def respond(request):
        body = json.loads(request.content)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
            "error": {"code": "old_error", "message": "old", "outcome_unknown": False,
                "stage": "arbitrary", "nativeDigSubmitted": "not-a-bool"}})
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "test"}), transport=httpx.MockTransport(respond))
    with pytest.raises(BridgeError) as raised: await client.call("inventory.supply", {})
    assert raised.value.code == "old_error" and raised.value.details == {"outcome_unknown": False}

@pytest.mark.parametrize("receipt", ["nativeDig", "nativePlace"])
async def test_native_false_cannot_be_promoted_to_completed_replacement(consumer, receipt):
    task, state = consumer
    state["receipt"][receipt]["accepted"] = False
    with pytest.raises(BridgeError) as raised: await task.replace_ports()
    assert raised.value.details["outcome_unknown"] is True and len(state["calls"]) == 2
    assert not task.selected_ports[0].get("placementConfirmed")
