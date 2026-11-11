"""Complete raw JSON source guards stay bounded without extra native observations."""
import copy
import math
import json
from pathlib import Path

import jsonschema
import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.conveniences import json_sha256, json_size
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.tools import WORKFLOW_CONDITION_SCHEMA
from minecraft_codex_bridge.workflows import MAX_PLAN_BYTES, condition_matches, run_workflow, validate_plan
from test_workflows import action, bridge, operations, plan, read

ACTUAL = json.loads((Path(__file__).parent / "fixtures/r18-810-workflow-source-plan-size.json").read_text(encoding="utf-8"))


def actual_digest_plan():
    args = copy.deepcopy(ACTUAL["reconstructedPlan"])
    guard = next(step for step in args["steps"] if step["id"] == "complete_source_slots")
    guard["condition"].update(operator="json_sha256_equals", value=json_sha256(guard["condition"]["value"]))
    return args


def condition(value, *, path="slots"):
    return {"path": path, "operator": "json_sha256_equals", "value": json_sha256(value)}


def assertion(value):
    return {"id": "complete_source_slots", "kind": "assert", "source": "before", "condition": condition(value), "fields": []}


def raw_slots():
    return [{"slot": 80 + index, "playerInventoryIndex": index,
             "playerInventoryBindingVerified": True, "machineInventoryBindingVerified": False,
             "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot",
             "inventorySource": "current_player_inventory", "item": {
                 "registryName": "IC2:itemCellEmpty", "metadata": 0, "count": 3 if index == 0 else 1,
                 "hasTag": False} if index < 2 else None,
             "publicDetails": {"tooltip": "完整原始证据" * 800, "limit": 64}}
            for index in range(6)]


@pytest.mark.parametrize("bad", [None, 0, [], {}, "a" * 63, "a" * 65, "g" * 64, "A" * 64, "a" * 63 + "\n"])
def test_digest_schema_rejects_wrong_type_length_alphabet_or_case_before_any_execution(bad):
    args = plan(read(), assertion([]))
    args["steps"][1]["condition"]["value"] = bad
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(args)
    with pytest.raises(BridgeError) as invalid:
        condition_matches({"slots": []}, args["steps"][1]["condition"])
    assert invalid.value.code == "CONDITION_TYPE"


@pytest.mark.parametrize("forbidden,value", [
    ("valueFrom", {"step": "before", "path": "slots"}),
    ("baseline", {"step": "before", "path": "slots"}),
    ("match", {"slot": 80}),
])
def test_digest_schema_refuses_other_condition_binding_or_selection_semantics(forbidden, value):
    args = plan(read(), assertion([]))
    args["steps"][1]["condition"][forbidden] = value
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(args)


def test_digest_schema_requires_literal_hash_and_missing_path_never_matches():
    args = plan(read(), assertion([]))
    del args["steps"][1]["condition"]["value"]
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(args)
    assert not condition_matches({}, condition([]))
    assert not condition_matches({"slots": None}, condition([]))


def test_complete_digest_canonicalizes_only_object_key_order_without_mutating_evidence():
    original = [{"b": {"z": "中文", "a": False}, "a": [None, 1, 2.0]}]
    reordered = [{"a": [None, 1, 2.0], "b": {"a": False, "z": "中文"}}]
    retained = copy.deepcopy(original)
    assert json_sha256(original) == json_sha256(reordered)
    assert condition_matches({"slots": reordered}, condition(original))
    assert original == retained


@pytest.mark.parametrize("changed", [
    {"count": True}, {"count": 1.0}, {"count": "1"}, {"count": None},
    {"flag": 0}, {"item": {}}, {"extra": None}, {},
])
def test_digest_detects_typed_value_missing_field_or_added_field_drift(changed):
    baseline = {"count": 1, "flag": False, "item": None}
    current = {**baseline, **changed} if changed else {"count": 1, "flag": False}
    assert not condition_matches({"slots": current}, condition(baseline))


@pytest.mark.parametrize("change", ["order", "new_candidate", "removed_empty", "protected_binding", "item_tag", "optional_field"])
def test_digest_covers_every_array_entry_order_and_raw_field(change):
    baseline = raw_slots()
    current = copy.deepcopy(baseline)
    if change == "order": current.reverse()
    if change == "new_candidate": current[2]["item"] = copy.deepcopy(current[1]["item"])
    if change == "removed_empty": current.pop()
    if change == "protected_binding": current[0]["playerInventoryIndex"] = 3
    if change == "item_tag": current[0]["item"]["hasTag"] = 0
    if change == "optional_field": current[3]["publicDetails"]["limit"] = 63
    assert not condition_matches({"slots": current}, condition(baseline))


@pytest.mark.parametrize("non_json", [math.nan, math.inf, -math.inf, {1, 2}, {"broken": math.nan}])
def test_digest_refuses_non_finite_or_non_json_raw_value(non_json):
    with pytest.raises(BridgeError) as invalid:
        condition_matches({"slots": non_json}, condition([]))
    assert invalid.value.code == "CONDITION_TYPE"


def test_large_complete_raw_array_uses_small_literal_fingerprint_without_raising_plan_limit():
    slots = raw_slots()
    original = plan(read(fields=["slots"], omitEmptySlots=False), assertion(slots), action())
    old = copy.deepcopy(original)
    old["steps"][1]["condition"].update(operator="equals", value=slots)
    assert json_size(old) > MAX_PLAN_BYTES == 65536
    with pytest.raises(BridgeError) as oversized:
        validate_plan(old, False)
    assert oversized.value.code == "PLAN_TOO_LARGE"
    tool_validator("mcb_sequence").validate(original)
    assert validate_plan(original, False) == original["steps"]
    assert json_size(original) < 1024 and len(original["steps"]) == len(old["steps"]) == 3


@pytest.mark.parametrize("drift", [False, True])
async def test_native_workflow_hash_checks_same_complete_raw_read_and_preserves_report_before_any_action(bridge, drift):
    adapter, state = bridge
    baseline = raw_slots()
    current = copy.deepcopy(baseline)
    if drift: current[2]["item"] = copy.deepcopy(current[1]["item"])
    retained = copy.deepcopy(current)
    state["data"]["gui.inspect"] = {"screenId": "actual-screen", "windowId": 7,
        "slots": current, "totalSlots": len(current), "truncated": False}
    args = plan(read(fields=["slots", "totalSlots", "truncated"], omitEmptySlots=False),
                assertion(baseline), action(), max_return_bytes=262144)
    result = await adapter.call_tool("mcb_sequence", args)
    data = result.structuredContent["data"]
    assert operations(state).count("gui.inspect") == 1
    assert data["steps"][0]["observation"]["values"]["slots"] == retained
    assert current == retained
    assert ("player.hotbar" in operations(state)) is not drift
    if drift:
        assert result.isError and data["error"]["code"] == "ASSERTION_FAILED"
        assert data["failedIndex"] == 1 and data["dispatchedActions"] == 0
    else:
        assert not result.isError and data["status"] == "completed" and data["completedSteps"] == 3


def test_exact_r18_original_frozen_builder_plan_refuses_67849_bytes_and_digest_keeps_same_sixteen_steps_at_3542_bytes():
    assert ACTUAL["sourceArtifactSha256"] == "e6c9f95219f20884cfcbfb18915b4f36679996ea41bd75a441b648f8fbc9ddd4"
    assert ACTUAL["actualReportedReason"] == "PLAN_TOO_LARGE" and ACTUAL["actualFailedPhase"] == "transfer_material"
    assert ACTUAL["actualExecutionsCompleted"] == 0
    assert ACTUAL["reconstructionContract"]["notAnActualCapturedPlan"] is True
    old = ACTUAL["reconstructedPlan"]
    assert json_size(old) == ACTUAL["byteDiagnostics"]["reconstructedWholePlanBytes"] == 67849
    guard = next(step for step in old["steps"] if step["id"] == "complete_source_slots")
    assert len(guard["condition"]["value"]) == 61 and json_size(guard["condition"]["value"]) == 64385
    with pytest.raises(BridgeError) as oversized:
        validate_plan(old, False)
    assert oversized.value.code == "PLAN_TOO_LARGE"
    new = actual_digest_plan()
    assert json_size(new) == 3542 and MAX_PLAN_BYTES == 65536
    assert len(new["steps"]) == len(old["steps"]) == 16
    assert [step for step in new["steps"] if step["id"] != "complete_source_slots"] == [
        step for step in old["steps"] if step["id"] != "complete_source_slots"]
    new_guard = next(step for step in new["steps"] if step["id"] == "complete_source_slots")
    jsonschema.Draft202012Validator(WORKFLOW_CONDITION_SCHEMA).validate(new_guard["condition"])
    validate_plan(new, False)


@pytest.mark.parametrize("drift", [False, True])
async def test_exact_actual_810_raw_slots_replay_only_offline_original_native_item_plan_and_refuse_new_candidate_before_pickup(bridge, drift):
    adapter, state = bridge
    args = actual_digest_plan()
    state["session"], state["world"] = args["expectedSessionId"], args["expectedWorld"]
    old_guard = next(step for step in ACTUAL["reconstructedPlan"]["steps"] if step["id"] == "complete_source_slots")
    slots = copy.deepcopy(old_guard["condition"]["value"])
    source_id = next(step for step in args["steps"] if step["id"] == "pickup")["args"]["slot"]
    destination_id = next(step for step in args["steps"] if step["id"] == "place_stack")["args"]["slot"]
    selected = next(slot for slot in slots if slot["slot"] == source_id)
    destination = next(slot for slot in slots if slot["slot"] == destination_id)
    if drift:
        extra = next(slot for slot in slots if slot.get("playerInventoryBindingVerified") is True
                     and type(slot.get("playerInventoryIndex")) is int and slot["item"] is None)
        extra["item"] = copy.deepcopy(selected["item"])
    retained = copy.deepcopy(slots)
    state["data"]["gui.inspect"] = lambda: {**ACTUAL["actualSourceGuiContext"], "slots": slots}
    state["cursor"] = None
    state["data"]["inventory.inspect"] = lambda: {"cursorItem": state["cursor"]}
    def click():
        clicked = state["calls"][-1]["args"]["slot"]
        if clicked == source_id:
            assert state["cursor"] is None
            state["cursor"] = copy.deepcopy(selected["item"])
            selected["item"] = None
        else:
            assert clicked == destination_id and destination["item"] is None and state["cursor"] is not None
            destination["item"] = state["cursor"]
            state["cursor"] = None
        return {"status": "submitted", "submitted": True}
    state["data"]["gui.slot_click"] = click
    data = await run_workflow(adapter.client, args, adapter.workflow_lock)
    assert data["steps"][0]["observation"]["values"]["slots"] == retained
    if drift:
        assert data["status"] == "failed" and data["error"]["code"] == "ASSERTION_FAILED" and data["failedIndex"] == 3
        assert operations(state).count("gui.inspect") == 1 and operations(state).count("gui.slot_click") == 0
        assert slots == retained and state["cursor"] is None
    else:
        assert data["status"] == "completed" and data["completedSteps"] == 16
        assert operations(state).count("gui.inspect") == 2 and operations(state).count("gui.slot_click") == 2
        assert selected["item"] is None and destination["item"] is not None and state["cursor"] is None
