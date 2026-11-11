"""Finite frontier reads and completed native batch evidence, without a client."""

import copy
import json

import httpx
import pytest

import test_actual_survival_workflows as actual
from test_construction_tasks import CONTROLLER, CONTROLLER_ITEM, PORT_ITEMS, key, native_construction, writes
from test_survival_structure_seeds import task, prepare_frontier, install_frontier_places
from test_guarded_rpc import guarded_bridge
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.survival_checkpoints import survival_failure_checkpoint, survival_failure_fields


def compact_reads(instance, monkeypatch, *, page_size=128, fault=None):
    original = instance.call
    calls = []
    instance.compact_inspection = True

    async def call(operation, args):
        calls.append((operation, copy.deepcopy(args)))
        if operation != "world.inspect_many" or args.get("compact") is not True:
            return await original(operation, args)
        points = args["positions"][:page_size]
        page = await original(operation, {**args, "positions": points})
        for index, row in enumerate(page["results"]):
            data = row["data"]
            row["data"] = {name: data[name] for name in ("source", "dimension", "x", "y", "z", "registryName", "metadata", "tileClass") if name in data}
            row["data"]["loaded"] = True
            row["requestIndex"] = index
        count = len(points)
        page.update(compact=True, worldTick=100, nonAtomic=True, requestedCount=len(args["positions"]),
            processedCount=count, nextOffset=count, complete=count == len(args["positions"]),
            partial=count != len(args["positions"]), byteLimited=False, budgetLimited=count != len(args["positions"]))
        if fault:
            fault(page)
        return page

    monkeypatch.setattr(instance, "call", call)
    return calls


async def unavailable(*args):
    return False


async def test_32_disjoint_candidate_neighbour_union_uses_two_bounded_reads(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + 100 + 3 * index, "y": 2} for index in range(40)]
    prepare_frontier(instance, state, [("item_input", 2, points)])
    calls = compact_reads(instance, monkeypatch)
    probes = []

    async def probe(point, slot, attachments):
        probes.append(key(point))
        return False

    monkeypatch.setattr(instance, "probe_survival_frontier", probe)
    monkeypatch.setattr(instance, "extend_survival_frontier", unavailable)
    with pytest.raises(BridgeError):
        await instance.install_survival_frontier_ports()
    pages = [args["positions"] for operation, args in calls if operation == "world.inspect_many"]
    assert list(map(len, pages)) == [128, 96]
    assert len({key(point) for page in pages for point in page}) == 224
    assert probes == [key(point) for point in points[:32]] and writes(state) == []


async def test_soft_prefix_continuations_read_the_exact_remaining_suffix(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 100, "y": 2}
    prepare_frontier(instance, state, [("item_input", 2, [point])])
    calls = compact_reads(instance, monkeypatch, page_size=2)
    observed = {}
    await instance.frontier_pass_blocks([point, point], observed)
    pages = [args["positions"] for _, args in calls]
    assert list(map(len, pages)) == [7, 5, 3, 1]
    assert pages[1] == pages[0][2:] and pages[2] == pages[0][4:] and pages[3] == pages[0][6:]
    assert len(observed) == 7 and await instance.survival_frontier_attachments(point, observed=observed)


@pytest.mark.parametrize("fault", ["count_bool", "zero", "offset", "source", "tick_bool", "loaded", "xyz", "row_order", "row_bool", "missing", "unlimited_partial"])
async def test_malformed_compact_prefix_never_supplies_air_or_a_mutation(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 100, "y": 2}

    def broken(page):
        if fault == "count_bool": page["processedCount"] = True
        elif fault == "zero": page["processedCount"] = page["nextOffset"] = 0; page["results"] = []
        elif fault == "offset": page["nextOffset"] -= 1
        elif fault == "source": page["source"] = "client"
        elif fault == "tick_bool": page["worldTick"] = True
        elif fault == "loaded": page["results"][0]["data"]["loaded"] = False
        elif fault == "xyz": page["results"][0]["data"]["x"] += 1
        elif fault == "row_order": page["results"][0]["requestIndex"] = 2
        elif fault == "row_bool": page["results"][0]["requestIndex"] = False
        elif fault == "missing": page["results"].pop()
        elif fault == "unlimited_partial":
            page.update(processedCount=1, nextOffset=1, results=page["results"][:1], complete=False, partial=True)

    compact_reads(instance, monkeypatch, fault=broken)
    with pytest.raises(BridgeError):
        await instance.frontier_pass_blocks([point], {})
    assert writes(state) == []


async def test_explicit_point_error_is_unknown_and_cannot_be_used_as_air(native_construction, monkeypatch):
    client, _ = native_construction
    instance = task(client)
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 100, "y": 2}

    def broken(page):
        page["results"][0] = {"requestIndex": 0, "ok": False, "error": {"code": "chunk_unloaded"}}

    compact_reads(instance, monkeypatch, fault=broken)
    observed = {}
    await instance.frontier_pass_blocks([point], observed)
    assert observed[key(point)] is None
    with pytest.raises(BridgeError):
        await instance.survival_frontier_attachments(point, observed=observed)


async def test_unused_nonair_candidate_does_not_replace_earlier_valid_choice(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    points = [{**CONTROLLER, "x": CONTROLLER["x"] + 100 + 3 * index, "y": 2} for index in range(2)]
    prepare_frontier(instance, state, [("item_input", 2, points)])
    state["blocks"][key(points[1])] = {"registryName": "minecraft:stone", "metadata": 0}
    trace = install_frontier_places(instance, state, monkeypatch)
    compact_reads(instance, monkeypatch)
    async def ready(*args): return True
    monkeypatch.setattr(instance, "probe_survival_frontier", ready)
    await instance.install_survival_frontier_ports()
    assert trace == [(2, key(points[0]))]


@pytest.mark.parametrize("wrong_gt", [False, True])
async def test_gt_anchor_gets_one_full_identity_read_per_pass(native_construction, monkeypatch, wrong_gt):
    client, state = native_construction
    instance = task(client)
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 1}
    prepare_frontier(instance, state, [("item_input", 2, [point])])
    if wrong_gt:
        state["blocks"][key(CONTROLLER)]["itemMetadata"] = PORT_ITEMS["energy"]["metadata"]
    calls = compact_reads(instance, monkeypatch)
    observed = {}
    await instance.frontier_pass_blocks([point], observed)
    first = await instance.survival_frontier_attachments(point, observed=observed)
    second = await instance.survival_frontier_attachments(point, observed=observed)
    assert first == second and bool(first) is not wrong_gt
    full = [args for operation, args in calls if operation == "world.inspect_many" and not args.get("compact")]
    assert len(full) == 1 and full[0]["positions"] == [CONTROLLER]


async def test_new_placement_rebuilds_read_pass_and_can_adopt_only_the_real_port(native_construction, monkeypatch):
    client, state = native_construction
    instance = task(client)
    upper = {**CONTROLLER, "x": CONTROLLER["x"] + 2}
    lower = {**upper, "y": 2}
    prepare_frontier(instance, state, [("energy", 3, [upper]), ("item_input", 2, [lower])])
    trace = install_frontier_places(instance, state, monkeypatch)
    calls = compact_reads(instance, monkeypatch)
    async def ready(*args): return True
    monkeypatch.setattr(instance, "probe_survival_frontier", ready)
    await instance.install_survival_frontier_ports()
    assert trace == [(2, key(lower)), (3, key(upper))]
    compact = [args for operation, args in calls if args.get("compact")]
    assert key(upper) in [key(point) for point in compact[0]["positions"]]
    assert key(upper) in [key(point) for point in compact[-1]["positions"]]
    assert any(args["positions"] == [lower] for _, args in calls if not args.get("compact"))


def native_failure(instance):
    before, after = actual.server_inventory(2), actual.server_inventory(1)
    blueprint = {**actual.construction.BLUEPRINT, "count": 1, "hasTag": False, "nbtIdentity": "null"}
    before["slots"][0]["item"] = copy.deepcopy(blueprint)
    after["slots"][0]["item"] = copy.deepcopy(blueprint)
    material = {**before["slots"][2]["item"], "count": 1}
    known = {"operation": "structure.survival_build", "actualSurvival": True,
        "sessionId": instance.args["expectedSessionId"], "expectedWorld": instance.args["expectedWorld"], "dimension": 0,
        "controller": {**instance.args["controller"], "dimension": 0, "registryName": CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": CONTROLLER_ITEM["metadata"]}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0,
            "creative": False, "actualSurvival": True, "flying": False, "selectedHotbarSlot": 0}, "blueprint": blueprint,
        "materialAccountingComplete": True, "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after, "lastNativeReturn": 7}
    return {"stage": "native_batch", "failureCheckpoint": "before_next_batch", "nativeSubmitted": True,
        "callsStarted": 1, "callsCompleted": 1, "callbackInFlight": False, "partial": True, "outcome_unknown": True,
        "callbackPreemptible": False, "automaticReplayAllowed": False,
        "batches": [{"nativeReturn": 7, "requestedBudget": 8, "configuredCooldownMillis": 200,
            "accountingComplete": True, "committedItems": [{"item": copy.deepcopy(material), "amount": 1}],
            "playerDeductions": [{"item": copy.deepcopy(material), "amount": 1}]}], "knownReceipt": known}


@pytest.mark.parametrize("fault", [None, "inflight", "missing_batch", "mismatch", "actor", "source", "accounting", "positive_done", "payload_omitted"])
async def test_failed_shell_keeps_original_failure_and_only_accounts_complete_known_batches(native_construction, monkeypatch, fault):
    client, _ = native_construction
    instance = actual.task(client, actualSurvival=True)
    instance.survival_player_id = "actual-player"
    instance.materials[0] = {"item": actual.construction.BLUEPRINT.copy(), "count": 1}
    details = native_failure(instance)
    actual.install_shell_geometry(instance, details["knownReceipt"])
    if fault == "inflight": details.update(callsStarted=2, callbackInFlight=True)
    elif fault == "missing_batch": details["callsCompleted"] = details["callsStarted"] = 2
    elif fault == "mismatch": details["batches"][0]["committedItems"][0]["amount"] = 2
    elif fault == "actor": details["knownReceipt"]["actor"]["playerId"] = "wrong"
    elif fault == "source": details["knownReceipt"]["playerInventoryAfter"]["source"] = "client"
    elif fault == "accounting": details["batches"][0]["accountingComplete"] = False
    elif fault == "positive_done": details["knownReceipt"]["nativeDone"] = True
    checkpoint = survival_failure_checkpoint(details, 262_144)
    if fault == "payload_omitted": checkpoint = survival_failure_checkpoint(details, 2048)
    error = BridgeError("face_not_visible", "The next refresh cannot see the controller.", outcome_unknown=True,
        survivalBuildCheckpoint=checkpoint)
    calls = []
    async def approach(*args, **kwargs): return instance.args["controller"], 2, []
    async def workflow(phase, steps, **kwargs):
        calls.append(steps)
        raise error
    monkeypatch.setattr(instance, "approach", approach)
    monkeypatch.setattr(instance, "workflow", workflow)
    with pytest.raises(BridgeError) as raised:
        await instance.blueprint_use(build=True)
    assert raised.value is error and len(calls) == 1
    receipt = instance.survival_build_receipt
    assert receipt["status"] == "failed" and receipt["outcomeUnknown"] is True
    assert receipt["nativeDone"] is False and receipt["automaticReplayAllowed"] is False
    confirmed = fault in (None, "positive_done")
    assert (receipt["completedBatchAccounting"] == "confirmed") is confirmed
    assert bool(instance.survival_material_ledger) is confirmed
    if confirmed:
        assert instance.survival_material_ledger[0]["deductions"][0]["count"] == 1
        assert instance.survival_material_ledger[0]["completedBatchCheckpoint"] is True


@pytest.mark.parametrize("field,value", [("failureCheckpoint", True), ("callsCompleted", True), ("automaticReplayAllowed", True), ("knownReceipt", []), ("batches", [{}] * 33)])
def test_checkpoint_schema_refuses_malformed_stage_counts_and_replay(field, value):
    with pytest.raises(ValueError):
        survival_failure_fields({field: value})


@pytest.mark.parametrize("maximum", [262_144, 2048])
def test_both_complete_native_body_proofs_are_retained_or_wholly_unavailable(maximum):
    proof = {"version": 1, "source": "client", "playerId": "actual-player", "dimension": 0,
        "actualNativeBody": {"minX": -44.3, "minY": 4.0, "minZ": 122.7, "maxX": -43.7, "maxY": 5.8, "maxZ": 123.3},
        "excludedBuildEnvelope": {"minX": -46, "minY": 5, "minZ": 125, "maxX": -41, "maxY": 10, "maxZ": 130},
        "outside": True, "formationProven": False}
    details = {"stage": "native_batch", "callsStarted": 1, "callsCompleted": 1, "callbackInFlight": False,
        "knownReceipt": {"clientBuildBodyProof": proof, "serverBuildBodyProof": {**copy.deepcopy(proof), "source": "server"}}}
    original = copy.deepcopy(details)
    checkpoint = survival_failure_checkpoint(details, maximum)
    assert details == original
    if maximum == 2048:
        assert checkpoint["payloadComplete"] is False and "knownReceipt" not in checkpoint
    else:
        assert checkpoint["payloadComplete"] is True and checkpoint["knownReceipt"] == original["knownReceipt"]
        checkpoint["knownReceipt"]["clientBuildBodyProof"]["actualNativeBody"]["minX"] = 0
        assert details == original


async def test_transport_workflow_and_task_preserve_one_failure_checkpoint_without_replay(guarded_bridge, monkeypatch):
    adapter, state = guarded_bridge
    instance = actual.task(adapter.client, actualSurvival=True)
    details = native_failure(instance)
    actual.install_shell_geometry(instance, details["knownReceipt"])
    original = adapter.client.http._transport.handle_async_request
    async def failure(request):
        body = json.loads(request.content)
        if body.get("operation") == "structure.survival_build":
            state["calls"].append(body)
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                "error": {"code": "face_not_visible", "message": "Completed batch; next face hidden.", **details}})
        return await original(request)
    monkeypatch.setattr(adapter.client.http, "_transport", httpx.MockTransport(failure))
    instance.actual_survival = False  # Test the shared workflow error projection without unrelated survival setup.
    step = {"id": "build", "kind": "action", "tool": "mcb_survival_build", "args": {
        "expectedSessionId": "session", "expectedWorld": "world", "dimension": 0, "x": 1, "y": 2, "z": 3,
        "blueprintHotbarSlot": 0, "side": 2}}
    with pytest.raises(BridgeError) as raised:
        await instance.workflow("survival_blueprint_construct", [step])
    checkpoint = raised.value.details["survivalBuildCheckpoint"]
    assert checkpoint["failureCheckpoint"] == "before_next_batch" and checkpoint["callsCompleted"] == 1
    assert checkpoint["knownReceipt"]["playerInventoryAfter"] == details["knownReceipt"]["playerInventoryAfter"]
    for side in ("client", "server"):
        assert checkpoint["knownReceipt"][side + "BuildBodyProof"] == details["knownReceipt"][side + "BuildBodyProof"]
    assert instance.phases[-1]["survivalBuildCheckpoint"] == checkpoint
    assert raised.value.details["outcome_unknown"] is True
    assert len([row for row in state["calls"] if row["operation"] == "structure.survival_build"]) == 1
