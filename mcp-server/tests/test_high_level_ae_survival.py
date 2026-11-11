"""Offline high-level AE shell binding/conservation; never contact a game client."""

import copy
import json
import time

import httpx
import pytest

import test_construction_tasks as construction
from test_actual_survival_workflows import install_shell_geometry, native_construction, server_inventory
from test_ae_material_source import SOURCE
from test_guarded_rpc import directive, guarded_bridge
from test_survival_structure_seeds import CASING, install_finite_native_receipts
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import (
    _ConstructionTask, _ae_source_readiness, _ae_survival_ledger, _survival_inventory_entries,
)
from minecraft_codex_bridge.material_sources import AE_MATERIAL_SOURCE_SCHEMA
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.task_reports import compact_blueprint_report
from minecraft_codex_bridge.workflows import project, run_workflow


def declaration(mode="finite"):
    return construction.declaration(actualSurvival=True,
        survivalBuild={"elementBudget": 65_536, "materialSource": {**SOURCE, "mode": mode}})


def server_control(arguments):
    return {"available": True, "source": "integrated_server_tick", "sessionId": arguments["expectedSessionId"],
        "dimension": arguments["dimension"], "worldName": arguments["expectedWorld"],
        "worldFolder": arguments["expectedWorld"], "sampleId": 1, "ageMillis": 0, "playerId": "actual-player"}


def source_observation(arguments, entity_id=42):
    source = arguments["survivalBuild"]["materialSource"]
    creative = source["mode"] == "creative_infinite"
    return {"source": "integrated_server", "operation": "ae.material_source",
        "sessionId": arguments["expectedSessionId"], "dimension": arguments["dimension"],
        **{key: source[key] for key in ("x", "y", "z", "side", "selector")},
        "supported": True, "bindingVerified": True, "sourceMode": source["mode"],
        "sourceScope": "bound_cell_container", "wholeGridProviderScopeProven": False,
        "generationPerformed": False, "materialsExtracted": False, "accountingVerified": False,
        "nodeActive": True, "actionableNodeActive": True, "networkPowered": True, "extractPermission": True,
        "nativeExtractPermissionChecked": True, "bindingValidatedAtExtraction": False,
        "nativeExtractionCalls": 0, "nativeEnergyItemAllowance": 65_536, "energyProbeItems": 65_536,
        "materialSource": copy.deepcopy(source), "cellCount": 1, "gridId": source["expectedGridId"],
        "actorUuid": "actual-player", "actorEntityId": entity_id,
        "actorBinding": "same_actual_authoritative_entity_and_world_objects",
        "anchorClass": "appeng.tile.storage.TileDrive", "handlerRestrictionsPreserved": True,
        "cells": [{"cellIndex": 0, "handlerClass": "appeng.me.storage.MEInventoryHandler",
            "backingClass": "appeng.me.storage.CreativeCellInventory" if creative else "appeng.me.storage.ItemCellInventory",
            "creative": creative, "physicalCell": {"registryName": "appliedenergistics2:item.ItemMultiMaterial",
                "metadata": 60 if creative else 35, "hasTag": True,
                "identityBinding": "same_actual_cell_stack_object; storage NBT not serialized"}}]}


def build_receipt(arguments, *, amount=1, backing=2 ** 53 + 7, empty_final=True):
    source = source_observation(arguments)
    binding = _ae_source_readiness(source, arguments["survivalBuild"]["materialSource"], 0, "actual-player", "session")
    inventory = server_inventory(2)
    inventory["selectedHotbarSlot"] = arguments["blueprintHotbarSlot"]
    blueprint = {**construction.BLUEPRINT, "count": 1, "hasTag": False, "nbtIdentity": ""}
    inventory["slots"][arguments["blueprintHotbarSlot"]]["item"] = blueprint
    material = {"registryName": "example:shell", "metadata": 4, "count": 1,
        "hasTag": True, "nbtIdentity": "{completeNativeIdentity:1}"}
    creative = arguments["survivalBuild"]["materialSource"]["mode"] == "creative_infinite"
    after = backing if creative else backing - amount
    batch = {**copy.deepcopy(binding), "sourceType": "ae_cells", "sourceMode": source["sourceMode"],
        "sourceScope": "bound_cell_container", "completeBackingIdentity": True, "externalSourceScopeProven": True,
        "wholeGridProviderScopeProven": False, "nativeExtractionCalls": 1,
        "accountingComplete": True, "playerInventoryUnchanged": True, "bindingValidatedAtExtraction": True,
        "materialsObserved": True, "finiteDecrementVerified": not creative, "supplyDidNotDecrease": creative,
        "automaticReplayAllowed": False, "outcomeUnknown": False,
        "committedItems": [{"item": copy.deepcopy(material), "amount": amount}],
        "backingLedger": [{"cellIndex": 0, "item": copy.deepcopy(material), "backingBefore": backing,
            "backingAfter": after, "backingBeforeExact": str(backing), "backingAfterExact": str(after),
            "actualExtracted": amount, "verifiedExtractions": 1, "quantityType": "long"}],
        "nativeReturn": 1 if empty_final else -1, "requestedBudget": 65_536, "configuredCooldownMillis": 200}
    batches = [batch]
    if empty_final:
        done = copy.deepcopy(batch)
        done.update(nativeReturn=-1, nativeExtractionCalls=0, materialsObserved=False, bindingValidatedAtExtraction=False,
            finiteDecrementVerified=False, supplyDidNotDecrease=False, committedItems=[], backingLedger=[])
        batches.append(done)
    return {"operation": "structure.survival_build", "status": "native_done", "actualSurvival": True,
        "materialSourceType": "ae_cells", "sessionId": arguments["expectedSessionId"],
        "expectedWorld": arguments["expectedWorld"], "dimension": 0,
        "controller": {**arguments["controller"], "dimension": 0,
            "registryName": construction.CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": construction.CONTROLLER_ITEM["metadata"], "metaClass": "example.PublicMultiblock"}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0,
            "creative": False, "actualSurvival": True, "flying": False, "selectedHotbarSlot": arguments["blueprintHotbarSlot"]},
        "blueprint": copy.deepcopy(blueprint), "nativeDone": True, "materialAccountingComplete": True,
        "formationVerified": False, "partial": False, "outcomeUnknown": False, "generationPerformed": False,
        "inventoryCleared": False, "playerInventoryBefore": copy.deepcopy(inventory),
        "playerInventoryAfter": copy.deepcopy(inventory), "batches": batches}, binding


def validate(arguments, receipt, binding):
    return _ae_survival_ledger(receipt, arguments["survivalBuild"]["materialSource"], binding, 0, "actual-player", 0,
        _survival_inventory_entries(receipt["playerInventoryBefore"]))


def test_high_and_low_sources_share_exact_schema_without_circular_import():
    lower = TOOL_BY_NAME["mcb_survival_build"].schema["properties"]["materialSource"]
    higher = TOOL_BY_NAME["mcb_blueprint_build"].schema["properties"]["survivalBuild"]["properties"]["materialSource"]
    assert lower is higher is AE_MATERIAL_SOURCE_SCHEMA
    description = TOOL_BY_NAME["mcb_blueprint_build"].schema["properties"]["survivalBuild"]["description"]
    assert "only to the native shell" in description and "controller, declared ports and permanent casing seeds" in description
    assert "creative unchanged supply are distinct" in description


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
@pytest.mark.parametrize("empty_final", [False, True])
def test_exact_large_counts_and_empty_done_batch_keep_separate_mode_proofs(mode, empty_final):
    arguments = declaration(mode)
    receipt, binding = build_receipt(arguments, empty_final=empty_final)
    ledger = validate(arguments, receipt, binding)
    assert ledger["actualExtracted"] == 1 and ledger["playerInventoryUnchanged"] is True
    assert ledger["finiteConservationVerified"] is (mode == "finite")
    assert ledger["creativeSupplyUnchanged"] is (mode == "creative_infinite")
    assert ledger["wholeGridProviderScopeProven"] is False and ledger["nativeDoneIsNotFormation"] is True
    assert ledger["deductions"] == [] and ledger["backingLedger"][0]["backingBeforeExact"] == str(2 ** 53 + 7)


@pytest.mark.parametrize("fault", ["mode", "grid", "uuid", "entity", "dimension", "anchor", "cells", "mixed",
    "partial", "unknown", "incomplete", "scope", "replay", "whole_grid", "player_deductions", "player_flag",
    "player_nbt", "missing_slot", "player_selected", "player_missing_nbt", "backing_delta", "missing_backing",
    "rounded_backing", "missing_exact", "leading_zero", "saturated", "negative", "bool_amount", "zero_amount",
    "wrong_nbt", "wrong_tag", "wrong_cell", "duplicate_row", "duplicate_committed", "committed_amount",
    "missing_calls", "wrong_calls", "missing_binding_flag", "wrong_finite_proof", "wrong_creative_proof",
    "zero_extractions", "wrong_quantity_type", "failure_observation", "all_empty"])
@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
def test_incomplete_changed_or_fabricated_ae_conservation_is_refused(mode, fault):
    arguments = declaration(mode)
    receipt, binding = build_receipt(arguments)
    batch = receipt["batches"][0]
    row = batch["backingLedger"][0]
    if fault == "mode": batch["sourceMode"] = "creative_infinite" if mode == "finite" else "finite"
    elif fault == "grid": batch["gridId"] = "different-grid"
    elif fault == "uuid": batch["actorUuid"] = "different-player"
    elif fault == "entity": batch["actorEntityId"] += 1
    elif fault == "dimension": batch["dimension"] = True
    elif fault == "anchor": batch["x"] += 1
    elif fault == "cells": batch["cells"][0]["physicalCell"]["metadata"] += 1
    elif fault == "mixed": batch["cells"][0]["creative"] = mode != "creative_infinite"
    elif fault == "partial": batch["partial"] = True
    elif fault == "unknown": batch["outcomeUnknown"] = True
    elif fault == "incomplete": batch["completeBackingIdentity"] = False
    elif fault == "scope": batch["sourceScope"] = "whole_network"
    elif fault == "replay": batch["automaticReplayAllowed"] = True
    elif fault == "whole_grid": batch["wholeGridProviderScopeProven"] = True
    elif fault == "player_deductions": batch["playerDeductions"] = []
    elif fault == "player_flag": batch["playerInventoryUnchanged"] = False
    elif fault == "player_nbt": receipt["playerInventoryAfter"]["slots"][40]["item"]["nbtIdentity"] = "{changed:1}"
    elif fault == "missing_slot": receipt["playerInventoryAfter"]["slots"].pop()
    elif fault == "player_selected": receipt["playerInventoryAfter"]["selectedHotbarSlot"] = True
    elif fault == "player_missing_nbt": receipt["playerInventoryAfter"]["slots"][40]["item"].pop("nbtIdentity")
    elif fault == "backing_delta": row["backingAfter"] -= 1; row["backingAfterExact"] = str(row["backingAfter"])
    elif fault == "missing_backing": batch.pop("backingLedger")
    elif fault == "rounded_backing": row["backingBefore"] = float(row["backingBefore"])
    elif fault == "missing_exact": row.pop("backingBeforeExact")
    elif fault == "leading_zero": row["backingBeforeExact"] = "0" + row["backingBeforeExact"]
    elif fault == "saturated": row.update(backingBefore=2 ** 63 - 1, backingBeforeExact=str(2 ** 63 - 1))
    elif fault == "negative": row.update(backingBefore=-1, backingBeforeExact="-1")
    elif fault == "bool_amount": row["actualExtracted"] = True
    elif fault == "zero_amount": row["actualExtracted"] = 0
    elif fault == "wrong_nbt": row["item"]["nbtIdentity"] = "{other:1}"
    elif fault == "wrong_tag": row["item"]["hasTag"] = False
    elif fault == "wrong_cell": row["cellIndex"] = 1
    elif fault == "duplicate_row": batch["backingLedger"].append(copy.deepcopy(row))
    elif fault == "duplicate_committed": batch["committedItems"].append(copy.deepcopy(batch["committedItems"][0]))
    elif fault == "committed_amount": batch["committedItems"][0]["amount"] += 1
    elif fault == "missing_calls": batch.pop("nativeExtractionCalls")
    elif fault == "wrong_calls": batch["nativeExtractionCalls"] = 2
    elif fault == "missing_binding_flag": batch.pop("bindingValidatedAtExtraction")
    elif fault == "wrong_finite_proof": batch["finiteDecrementVerified"] = mode != "finite"
    elif fault == "wrong_creative_proof": batch["supplyDidNotDecrease"] = mode != "creative_infinite"
    elif fault == "zero_extractions": row["verifiedExtractions"] = 0
    elif fault == "wrong_quantity_type": row["quantityType"] = "double"
    elif fault == "failure_observation": batch["failureObservation"] = {"actualExtracted": 1}
    else: receipt["batches"] = [receipt["batches"][-1]]
    with pytest.raises(BridgeError): validate(arguments, receipt, binding)


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
def test_each_cell_and_batch_must_conserve_independently_without_cancellation(mode):
    arguments = declaration(mode)
    receipt, binding = build_receipt(arguments, amount=2)
    first = receipt["batches"][0]
    second = copy.deepcopy(first)
    old = first["backingLedger"][0]["backingAfter"]
    new = old - 2 if mode == "finite" else old
    second["backingLedger"][0].update(backingBefore=old, backingAfter=new,
        backingBeforeExact=str(old), backingAfterExact=str(new))
    receipt["batches"].insert(1, second)
    assert validate(arguments, receipt, binding)["actualExtracted"] == 4
    second["backingLedger"][0]["backingBefore"] += 1
    second["backingLedger"][0]["backingAfter"] += 1
    second["backingLedger"][0]["backingBeforeExact"] = str(second["backingLedger"][0]["backingBefore"])
    second["backingLedger"][0]["backingAfterExact"] = str(second["backingLedger"][0]["backingAfter"])
    with pytest.raises(BridgeError): validate(arguments, receipt, binding)


async def test_native_player_seed_receipt_remains_separate_from_client_summary(native_construction, monkeypatch):
    client, state = native_construction
    instance = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    state["items"][6] = construction.stack(CASING, 2)
    state["items"][40] = {"registryName": "example:armor", "metadata": 0, "count": 1, "hasTag": True}
    instance.materials[6] = {"item": CASING.copy(), "count": 2}
    instance.survival_inventory = [{"index": index, "item": copy.deepcopy(state["items"].get(index))} for index in range(41)]
    def native_nbt(receipt):
        for name in ("serverBefore", "serverAfter"):
            receipt[name]["slots"][40]["item"]["nbtIdentity"] = "{completeNativeArmor:1}"
    install_finite_native_receipts(instance, state, monkeypatch, mutate=native_nbt)
    point = {**instance.args["controller"], "y": 2}
    record = {"position": point, "permanentStructureSeed": True}
    await instance.place(point, 6, "permanent_seed", record)
    assert instance.survival_ae_player_inventory[40]["item"]["nbtIdentity"] == "{completeNativeArmor:1}"
    assert "nbtIdentity" not in instance.survival_inventory[40]["item"]
    player_ledger = instance.survival_material_ledger[0]
    assert player_ledger["sourceType"] == "actual_player_inventory"
    assert player_ledger["sourceScope"] == "controller_ports_permanent_seeds"
    assert player_ledger["deductions"][0]["count"] == 1
    assert record["survivalMaterialLedgerIndex"] == 0


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
def test_compact_result_retains_explicit_source_scope_and_distinct_proofs(mode):
    arguments = declaration(mode)
    receipt, binding = build_receipt(arguments)
    ledger = validate(arguments, receipt, binding)
    result = {"status": "completed", "survivalNativeBuild": receipt, "survivalMaterialLedger": [ledger],
        "survivalMaterialSources": {"nativeShell": {"declaration": arguments["survivalBuild"]["materialSource"],
            "scope": "bound_cell_container", "wholeGridProviderScopeProven": False}}}
    compact = compact_blueprint_report(result)
    assert compact["survivalMaterialLedger"][0]["finiteConservationVerified"] is (mode == "finite")
    assert compact["survivalMaterialLedger"][0]["creativeSupplyUnchanged"] is (mode == "creative_infinite")
    assert compact["survivalMaterialSources"]["nativeShell"]["wholeGridProviderScopeProven"] is False
    assert compact["survivalNativeBuild"]["batches"][0]["backingLedger"][0]["backingBeforeExact"] == str(2 ** 53 + 7)


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
@pytest.mark.parametrize("fault", [None, "offline", "permission", "mixed", "wrong_grid", "respawn", "partial", "unknown", "owner", "material_type"])
async def test_high_level_source_read_passthrough_and_no_callback_replay(native_construction, mode, fault):
    client, _ = native_construction
    arguments = declaration(mode)
    instance = _ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    instance.survival_player_id = "actual-player"
    instance.materials[0] = {"item": construction.BLUEPRINT.copy(), "count": 1}
    receipt, binding = build_receipt(arguments)
    instance.survival_ae_player_inventory = _survival_inventory_entries(receipt["playerInventoryBefore"])
    instance.survival_ae_binding = copy.deepcopy(binding)
    source = source_observation(arguments)
    if fault == "offline": source["networkPowered"] = False
    elif fault == "permission": source["extractPermission"] = False
    elif fault == "mixed": source["sourceMode"] = "mixed"
    elif fault == "wrong_grid": source["gridId"] = "wrong-grid"
    elif fault == "respawn": source["actorEntityId"] += 1
    elif fault in ("partial", "unknown"): receipt["partial" if fault == "partial" else "outcomeUnknown"] = True
    elif fault == "owner": receipt["controller"]["gregtech"]["metaTileId"] += 1
    elif fault == "material_type": receipt["materialSourceType"] = "actual_player_fromPlayer"
    calls = []
    async def approach(*args, **kwargs): return arguments["controller"], 2, []
    async def workflow(phase, steps, **kwargs):
        calls.extend(copy.deepcopy(steps))
        return {"steps": [{"id": step["id"], "observation": project(
            source if step.get("tool") == "mcb_ae_material_source" else receipt, step)}
            for step in steps if step.get("tool") in ("mcb_ae_material_source", "mcb_survival_build")]}
    async def inventory(*args, **kwargs): return receipt["playerInventoryAfter"]
    async def snapshot(): return {"serverControl": server_control(arguments)}
    instance.approach, instance.workflow, instance.call = approach, workflow, inventory
    instance.snapshot = snapshot
    install_shell_geometry(instance, receipt)
    if fault is None:
        await instance.blueprint_use(build=True)
        assert instance.survival_material_ledger[-1]["sourceType"] == "ae_cells"
        assert instance.survival_build_receipt["materialSourceType"] == "ae_cells"
    else:
        with pytest.raises(BridgeError): await instance.blueprint_use(build=True)
    sources = [step for step in calls if step.get("tool") == "mcb_ae_material_source"]
    builds = [step for step in calls if step.get("tool") == "mcb_survival_build"]
    assert len(sources) == 1 and sources[0]["args"] == {"expectedSessionId": "session", "expectedWorld": arguments["expectedWorld"],
        "dimension": 0, **{key: SOURCE[key] for key in ("x", "y", "z", "side", "selector")}, "energyProbeItems": 65_536}
    assert len(builds) == (0 if fault in ("offline", "permission", "mixed", "wrong_grid", "respawn") else 1)
    if builds:
        assert builds[0]["args"]["materialSource"] == arguments["survivalBuild"]["materialSource"]
        assert builds[0]["omitEmptySlots"] is False and "materialSourceType" in builds[0]["fields"]
        baseline = len(calls)
        with pytest.raises(BridgeError) as failure: await instance.blueprint_use(build=True)
        assert failure.value.details["reason"] == "survival_ae_build_already_attempted" and len(calls) == baseline
    assert instance.survival_ae_readiness[0]["extractionProven"] is False


@pytest.mark.parametrize("fault", [None, "missing", "wrong_phase", "wrong_operation", "wrong_version", "dead", "profile", "world", "session"])
async def test_internal_high_level_source_read_owns_fresh_after_guard(guarded_bridge, monkeypatch, fault):
    adapter, state = guarded_bridge
    arguments = declaration()
    arguments["expectedWorld"] = "world"
    instance = _ConstructionTask(adapter.client, arguments, run_workflow, started=time.monotonic())
    instance.survival_player_id = "actual-player"
    observed = source_observation(arguments)
    original = adapter.client.http._transport.handle_async_request
    async def respond(request):
        response = await original(request)
        body = json.loads(request.content)
        if body.get("operation") not in ("ae.material_source", "client.snapshot"): return response
        envelope = json.loads(response.content)
        if envelope["ok"]:
            if body["operation"] == "ae.material_source": envelope["data"] = copy.deepcopy(observed)
            else: envelope["data"]["serverControl"] = server_control(arguments)
        return httpx.Response(200, json=envelope)
    monkeypatch.setattr(adapter.client.http._transport, "handle_async_request", respond)
    if fault == "dead": state["health"] = 0
    elif fault in ("profile", "world", "session"): state[fault] = "other"
    else: state["bad_context"] = fault
    if fault is None:
        await instance.read_survival_ae_source("preflight", [])
        assert instance.survival_ae_binding["actorEntityId"] == 42
    else:
        with pytest.raises(BridgeError): await instance.read_survival_ae_source("preflight", [])
    assert state["actions"] == 0
    assert not any(row["operation"] == "structure.survival_build" for row in state["calls"])
    source_calls = [row for row in state["calls"] if row["operation"] == "ae.material_source"]
    assert len(source_calls) <= 1
    if source_calls: assert source_calls[0]["includeContext"] == directive()


@pytest.mark.parametrize("read_capability", [False, True])
async def test_ae_preflight_needs_real_source_and_guard_capabilities_before_writes(native_construction, read_capability):
    client, _ = native_construction
    instance = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    calls = []
    async def call(operation, args):
        calls.append(operation)
        assert operation == "bridge.health"
        return {"protocolVersion": 1, "sessionId": "session", "profile": "developer",
            "capabilities": ["structure.survival_build"] + (["ae.material_source"] if read_capability else [])}
    instance.call = call
    with pytest.raises(BridgeError) as failure: await instance.preflight()
    assert failure.value.details["reason"] == "survival_ae_source_read_unavailable"
    assert calls == ["bridge.health"]
