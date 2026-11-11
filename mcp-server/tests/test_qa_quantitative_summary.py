from copy import deepcopy
from types import SimpleNamespace
import json
import time

import pytest

from minecraft_codex_bridge import qa_tasks as qa


ORE, CATALYST, IRON, GOLD = ("test|fixture:" + name for name in ("ore", "circuit", "iron", "gold"))
RUN_ID = "8a5bd7ea-11ab-4348-adeb-678a490359c1"
EVIDENCE_ID = "a4816c51-5ea4-47f5-898e-b460fd9b1126"
SELECTOR = "test.finite:quantities"


def item(name, count, nbt="{}"):
    return {"registryName": name, "metadata": 0, "nbt": nbt, "count": count}


def fluid(name, amount):
    return {"name": name, "nbt": "{}", "amount": amount}


def item_quantity(name, count, nbt="{}"):
    return item(name, str(count), nbt)


def fluid_quantity(name, amount):
    return fluid(name, str(amount))


def position(x, z=1):
    return {"x": x, "y": 4, "z": z}


def inventory_binding(binding, x, resources):
    return {"binding": binding, "position": position(x), "complete": True, "totalSlots": 4,
        "slots": [{"slot": index, **(resources[index] if index < len(resources) else {"count": 0})}
                  for index in range(4)]}


def digital_binding(resources, nbt):
    channels = []
    for index, name in enumerate((CATALYST, IRON, GOLD)):
        quantity = resources.get(name, 0)
        physical = 2 if name == IRON and quantity else 0
        channels.append({"channel": index, "position": position(index + 2), "complete": True,
            "worldTileIdentityVerified": True, "metaTileIdentityVerified": True,
            "registeredName": "gt.blockmachines.super.chest.tier.01",
            "runtimeClass": "gregtech.common.tileentities.storage.MTESuperChest",
            "voidOverflow": False, "filterDisabled": False, "itemSinkApi": qa.DIGITAL_CHEST_SINK_API,
            "displayProjectionExcludedFromAccounting": True, "capacity": 100000,
            "configuredType": item(name, 1, nbt), "bulk": item(name, quantity - physical, nbt),
            "physicalSlots": [{"slot": 0, "stackLimit": 64, "count": 0},
                {"slot": 1, "stackLimit": 64, **item(name, physical, nbt)}],
            "displayProjection": {"slot": 2, "stackLimit": 64, **item(name, quantity, nbt)}})
    return {"binding": "item_receiver", "position": position(2), "complete": True,
        "representation": "digital_chest_channels", "totalChannels": 3, "channels": channels}


def tank_binding(binding, x, resource=None):
    return {"binding": binding, "position": position(x, 2), "complete": True, "totalTanks": 1,
        "tanks": [{"tank": 0, "capacity": 1000, **(resource or {"amount": 0})}]}


def accepted_fixture(*, details=False, digital=False, nbt="{}", fluids=True, max_bytes=65536):
    calls = []

    async def forbidden_call(operation, args, **kwargs):
        calls.append(operation)
        raise AssertionError("Summary projection must not issue bridge RPCs")

    client = SimpleNamespace(configuration=SimpleNamespace(read=lambda: ("fixture",)), call=forbidden_call)
    args = {"expectedSessionId": "fixture-session", "expectedWorld": "fixture-world", "selector": SELECTOR,
        "requireFullFlow": True, "includeDetails": details, "max_return_bytes": max_bytes}
    task = qa._QaTask(client, args, None, time.monotonic())
    task.dimension, task.provider_class = 180, qa.PRIVATE_PROVIDER
    task.plan = {"allocationOrigin": position(0), "reservedBounds": {
        "minX": 0, "minY": 0, "minZ": 0, "maxX": 15, "maxY": 15, "maxZ": 15}}
    task.client.ticket = {"runId": RUN_ID, "selector": SELECTOR, "selectedTests": 1,
        "selectedIds": [SELECTOR], "selectedIdsTruncated": False}
    native = {"status": "PASS", "complete": True, "exitCode": 0, "selectedTests": 1, "passed": 1,
        **{key: 0 for key in qa.COUNT_FIELDS}}
    task.terminal = {"runId": RUN_ID, "freshSinceStart": True, "nativeBatchActive": False,
        "selectionMatches": True, "selectedTests": 1, "selectedIds": [SELECTOR],
        "selectedIdsTruncated": False, "state": "completed", "nativeReport": native}
    task.cases, task.reports_complete = [{"id": SELECTOR, "status": "PASSED"}], True
    task.flow_before = {"schemaVersion": 1, "caseId": SELECTOR, "available": False,
        "dimension": 180, "providerClass": qa.PRIVATE_PROVIDER, "sessionId": "fixture-session"}
    names = ["method_entry", "receiver_prepared", "registered_native_machine_placed",
        "machine_prepared_and_freshly_checked", "finite_inputs_supplied", "processing_start_observed",
        "production_complete_and_stopped", "api_receiver_recovery_complete",
        "normal_world_stability_verified", "complete"]
    ticks = list(range(8)) + [92, 93]
    evidence = {"available": True, "schemaVersion": 1, "evidenceId": EVIDENCE_ID,
        "caseId": SELECTOR, "dimension": 180, "origin": position(0), "providerClass": qa.PRIVATE_PROVIDER,
        "sessionId": "fixture-session", "runId": RUN_ID, "executionMode": "normal_world",
        "retrievalScope": "api_receiver", "recoveryOperation": "public_inventory_and_fluid_api",
        "methodEntryThroughRecoveryIncluded": True, "fixturePreparationIncluded": False,
        "state": "completed", "cleanupCompleted": True, "elapsedNanos": 94000000,
        "worldTicks": 93, "cleanupElapsedNanos": 95000000, "cleanupWorldTick": 94,
        "loadedFixtureBounds": {**task.plan["reservedBounds"], "coverage": "all_present_GT_tiles_in_fixture_bounds", "chunkCount": 1},
        "activeTiles": [{**position(1), "startTimer": 100, "endTimer": 101}],
        "stages": [{"name": name, "worldTick": tick, "elapsedNanos": tick * 1000000} for name, tick in zip(names, ticks)],
        "declaredRecipe": {"consumedItems": [item(ORE, 4, nbt), item(ORE, 3, nbt)],
            "physicalCatalysts": [item(CATALYST, 1, nbt)],
            "itemOutputs": [item(IRON, 2, nbt), item(IRON, 3, nbt), item(GOLD, 11, nbt)],
            "fluidInputs": [fluid("water", 1000)] if fluids else [],
            "fluidOutputs": [fluid("distilledwater", 1000)] if fluids else []}, "snapshots": []}
    for stage in ("prepared", "supplied", "produced", "recovered", "stable"):
        source = ([item(ORE, 7, nbt), item(CATALYST, 1, nbt)] if stage == "supplied" else
                  [item(CATALYST, 1, nbt), item(IRON, 5, nbt), item(GOLD, 11, nbt)] if stage == "produced" else [])
        receiver = [item(CATALYST, 1, nbt), item(IRON, 5, nbt), item(GOLD, 11, nbt)] if stage in ("recovered", "stable") else []
        item_receiver = digital_binding({row["registryName"]: row["count"] for row in receiver}, nbt) if digital else inventory_binding("item_receiver", 2, receiver)
        input_fluid = fluid("water", 1000) if fluids and stage == "supplied" else fluid("distilledwater", 1000) if fluids and stage == "produced" else None
        output_fluid = fluid("distilledwater", 1000) if fluids and stage in ("recovered", "stable") else None
        evidence["snapshots"].append({"stage": stage,
            "items": [inventory_binding("item_source", 1, source), item_receiver],
            "fluids": [tank_binding("fluid_source", 5, input_fluid), tank_binding("fluid_receiver", 6, output_fluid)]})
    return task, evidence, calls


@pytest.mark.parametrize("details", [False, True])
def test_completed_summary_retains_exact_all_resource_quantities_with_compact_default(details):
    task, evidence, calls = accepted_fixture(details=details)
    task.verify_flow(evidence)
    task.accept()
    result = task.result()
    assert result["status"] == "completed" and result["fullFlowVerified"] is True
    summary = result["quantitativeSummary"]
    assert summary["quantityEncoding"] == "decimal_integer"
    assert summary["consumedItems"] == [item_quantity(ORE, 7)]
    assert summary["physicalCatalysts"] == [item_quantity(CATALYST, 1)]
    assert summary["itemOutputs"] == [item_quantity(GOLD, 11), item_quantity(IRON, 5)]
    assert summary["receiverItems"] == [item_quantity(CATALYST, 1), item_quantity(GOLD, 11), item_quantity(IRON, 5)]
    assert summary["fluidInputs"] == [fluid_quantity("water", 1000)]
    assert summary["fluidOutputs"] == summary["receiverFluids"] == [fluid_quantity("distilledwater", 1000)]
    assert summary["stableTicks"] == 85
    assert ("observation" in result["fullFlowEvidence"]) is details
    assert not calls and task.client.calls == 0


def test_multi_channel_receiver_counts_actual_bulk_plus_physical_without_display_projection():
    task, evidence, _ = accepted_fixture(digital=True, fluids=False)
    task.verify_flow(evidence)
    task.accept()
    result = task.result()
    assert result["fullFlowVerified"] is True
    assert result["quantitativeSummary"]["receiverItems"] == [item_quantity(CATALYST, 1), item_quantity(GOLD, 11), item_quantity(IRON, 5)]
    assert result["quantitativeSummary"]["itemOutputs"] == [item_quantity(GOLD, 11), item_quantity(IRON, 5)]
    assert result["quantitativeSummary"]["fluidInputs"] == result["quantitativeSummary"]["fluidOutputs"] == result["quantitativeSummary"]["receiverFluids"] == []


def test_verified_projection_does_not_retain_mutable_counter_or_observation_rows():
    task, evidence, _ = accepted_fixture(digital=True)
    task.verify_flow(evidence)
    accepted = deepcopy(task.quantitative_summary)
    evidence["declaredRecipe"]["consumedItems"][0]["count"] = 999
    evidence["snapshots"][-2]["items"][-1]["channels"][1]["bulk"]["count"] = 999
    assert task.result()["quantitativeSummary"] == accepted


@pytest.mark.parametrize("fault", ["receiver_quantity", "native_failed", "stability", "unavailable"])
def test_invalid_or_unavailable_flow_has_no_successful_summary(fault):
    task, evidence, _ = accepted_fixture(digital=True)
    if fault == "receiver_quantity":
        for snapshot in evidence["snapshots"][-2:]:
            snapshot["items"][-1]["channels"][1]["bulk"]["count"] += 1
    elif fault == "native_failed":
        task.terminal["nativeReport"]["status"] = "FAIL"
    elif fault == "stability":
        evidence["stages"][-2]["worldTick"] = 91
    else:
        evidence = {"available": False}
    task.verify_flow(evidence)
    assert task.full_flow["verified"] is False and task.quantitative_summary is None
    with pytest.raises(qa._QaFailure) as failure:
        task.accept()
    result = task.result(failure.value)
    assert result["status"] == "failed" and "quantitativeSummary" not in result


def test_handled_failure_after_verified_flow_does_not_publish_summary():
    task, evidence, _ = accepted_fixture()
    task.verify_flow(evidence)
    assert task.full_flow["verified"] is True
    assert "quantitativeSummary" not in task.result(qa._QaFailure("qa_fixture_failure", "Later acceptance failed."))


def test_final_whole_clock_failure_removes_previously_added_summary(monkeypatch):
    task, evidence, _ = accepted_fixture()
    task.verify_flow(evidence)
    task.started = 100
    reads = iter([101, 111])
    with monkeypatch.context() as patch:
        patch.setattr(qa.time, "monotonic", lambda: next(reads))
        result = task.result()
    assert result["status"] == "failed" and result["error"]["code"] == "qa_whole_budget_exceeded"
    assert "quantitativeSummary" not in result


def test_large_exact_nbt_summary_is_omitted_whole_with_reason_under_existing_output_bound():
    task, evidence, _ = accepted_fixture(nbt="x" * 7000, max_bytes=16384)
    task.verify_flow(evidence)
    task.accept()
    result = task.result()
    assert result["status"] == "completed" and result["fullFlowVerified"] is True
    assert "quantitativeSummary" not in result and result["outputOmitted"] is True
    assert result["quantitativeSummaryOmittedReason"] == "complete_exact_summary_exceeds_max_return_bytes"
    assert "quantitativeSummary" in result["omittedFields"]
    assert qa.json_size(result) <= 16384


def test_unavailable_later_verification_cannot_reuse_prior_summary():
    task, evidence, _ = accepted_fixture()
    task.verify_flow(evidence)
    assert task.quantitative_summary is not None
    task.verify_flow({"available": False})
    assert task.quantitative_summary is None and "quantitativeSummary" not in task.result()


def test_long_receiver_counts_remain_exact_decimal_strings_after_json_roundtrip():
    task, evidence, _ = accepted_fixture(digital=True)
    quantities = {IRON: 2**53 + 1, GOLD: qa.LONG_MAX}
    evidence["declaredRecipe"]["itemOutputs"] = [item(IRON, quantities[IRON]), item(GOLD, quantities[GOLD])]
    evidence["declaredRecipe"]["fluidInputs"] = [fluid("water", 2**53 + 1)]
    evidence["declaredRecipe"]["fluidOutputs"] = [fluid("distilledwater", qa.LONG_MAX)]
    for checkpoint in evidence["snapshots"]:
        for row in checkpoint["items"][0]["slots"]:
            if checkpoint["stage"] == "produced" and row.get("registryName") in quantities:
                row["count"] = quantities[row["registryName"]]
        for channel in checkpoint["items"][-1]["channels"]:
            channel["capacity"] = qa.LONG_MAX
            name = channel["configuredType"]["registryName"]
            if checkpoint["stage"] in ("recovered", "stable") and name in quantities:
                physical = channel["physicalSlots"][1]["count"]
                channel["bulk"]["count"] = quantities[name] - physical
                channel["displayProjection"]["count"] = min(quantities[name], 64)
        for binding in checkpoint["fluids"]:
            for row in binding["tanks"]:
                row["capacity"] = qa.LONG_MAX
                if row["amount"]:
                    row["amount"] = 2**53 + 1 if row["name"] == "water" else qa.LONG_MAX
    task.verify_flow(evidence)
    task.accept()
    result = task.result()
    assert result["status"] == "completed" and result["fullFlowVerified"] is True
    summary = json.loads(json.dumps(result))["quantitativeSummary"]
    assert summary["quantityEncoding"] == "decimal_integer"
    assert summary["itemOutputs"] == [item_quantity(GOLD, qa.LONG_MAX), item_quantity(IRON, 2**53 + 1)]
    received = {row["registryName"]: row["count"] for row in summary["receiverItems"]}
    assert received[IRON] == "9007199254740993" and received[GOLD] == "9223372036854775807"
    assert all(type(row["count"]) is str and type(row["metadata"]) is int for row in summary["receiverItems"])
    assert summary["fluidInputs"] == [fluid_quantity("water", 2**53 + 1)]
    assert summary["fluidOutputs"] == summary["receiverFluids"] == [fluid_quantity("distilledwater", qa.LONG_MAX)]
    assert type(summary["receiverFluids"][0]["amount"]) is str
