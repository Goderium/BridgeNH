"""Actionable bundled-schema hints without dispatch or reflecting caller secrets."""
import copy
import json
from dataclasses import replace
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import tool_validator, validate_tool_arguments
from test_workflows import bridge, plan, read, action


@pytest.fixture(autouse=True)
def fresh_validator_cache():
    yield
    tool_validator.cache_clear()


def invalid(name, args):
    with pytest.raises(BridgeError) as failed:
        validate_tool_arguments(name, args)
    assert failed.value.code == "INVALID_ARGUMENTS"
    return failed.value.as_dict()


def fixture_tool(monkeypatch, schema):
    monkeypatch.setitem(TOOL_BY_NAME, "fixture_schema_feedback", replace(
        TOOL_BY_NAME["mcb_help"], name="fixture_schema_feedback", schema=schema))
    return "fixture_schema_feedback"


def test_required_feedback_lists_all_missing_fields_and_current_allowed_keys():
    result = invalid("mcb_gui_widget_action", {})
    assert {"screenId", "widgetId", "action"} <= set(result["missing_keys"])
    assert {"screenId", "widgetId", "action", "text", "value"} <= set(result["allowed_keys"])
    assert result["missing_keys_complete"] and result["allowed_keys_complete"]
    assert result["argument_path"] == [] and result["tool"] == "mcb_gui_widget_action"


def test_unknown_fields_report_allowed_schema_names_without_echoing_names_or_values():
    result = invalid("mcb_hotbar", {"slot": 1, "secret-field-name": "secret-token-value"})
    assert result["rule"] == "additionalProperties" and result["allowed_keys"] == ["slot"]
    assert result["additional_keys_allowed"] is False
    assert "secret-field-name" not in json.dumps(result) and "secret-token-value" not in json.dumps(result)


@pytest.mark.parametrize("value,rule", [(9, "maximum"), (-1, "minimum"), (True, "type"), ("secret-token-value", "type")])
def test_type_and_range_feedback_retains_schema_limits_without_input_values(value, rule):
    result = invalid("mcb_hotbar", {"slot": value})
    assert result["argument_path"] == ["slot"] and result["rule"] == rule
    assert result["expected_type"] == "integer" and result["minimum"] == 0 and result["maximum"] == 8
    assert "secret-token-value" not in json.dumps(result)


def test_enum_feedback_uses_only_actual_bundled_choices():
    result = invalid("mcb_help", {"topic": "secret-token-value"})
    assert set(result["allowed_values"]) == {"overview", "capabilities", "maintenance", "operations", "nei", "ae", "workspace", "testing", "tools"}
    assert result["allowed_values_complete"] and "secret-token-value" not in json.dumps(result)


def test_discriminated_action_reports_only_fields_allowed_for_that_action():
    result = invalid("mcb_gui_widget_action", {"screenId": "s", "widgetId": "w", "action": "activate", "text": "secret-token-value"})
    assert result["selected_alternative"]["discriminator"] == "action"
    assert set(result["allowed_keys"]) == {"screenId", "action", "widgetId", "button"}
    assert result["additional_keys_allowed"] is False and "secret-token-value" not in json.dumps(result)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_nonfinite_arguments_still_fail_before_schema_or_transport(value):
    assert "finite JSON" in invalid("mcb_hotbar", {"slot": value})["message"]


def test_dynamic_mapping_key_cannot_leak_through_argument_path(monkeypatch):
    name = fixture_tool(monkeypatch, {"type": "object", "properties": {
        "items": {"type": "object", "additionalProperties": {"type": "integer"}}}})
    result = invalid(name, {"items": {"secret-dynamic-key": "secret-value"}})
    assert result["argument_path"] == ["items", "*"]
    assert "secret" not in json.dumps(result)


def test_large_declared_key_catalog_is_bounded_and_marked_incomplete(monkeypatch):
    properties = {f"property{index}": {"type": "string"} for index in range(200)}
    name = fixture_tool(monkeypatch, {"type": "object", "properties": properties, "required": list(properties)})
    result = invalid(name, {})
    assert len(result["allowed_keys"]) == len(result["missing_keys"]) == 64
    assert not result["allowed_keys_complete"] and not result["missing_keys_complete"]
    assert len(json.dumps(result).encode()) < 4096


def test_unicode_schema_feedback_has_a_total_utf8_byte_bound(monkeypatch):
    properties = {"字段" * 44 + str(index): {"type": "string"} for index in range(200)}
    name = fixture_tool(monkeypatch, {"type": "object", "properties": properties, "required": list(properties)})
    result = invalid(name, {})
    assert len(json.dumps(result, ensure_ascii=False).encode("utf8")) <= 8192
    assert result["missing_keys"] and not result["missing_keys_complete"] and not result["allowed_keys_complete"]


def test_union_alternatives_do_not_merge_incompatible_required_fields(monkeypatch):
    name = fixture_tool(monkeypatch, {"oneOf": [
        {"type": "object", "properties": {"position": {"type": "integer"}}, "required": ["position"]},
        {"type": "object", "properties": {"captureId": {"type": "string"}}, "required": ["captureId"]}]})
    result = invalid(name, {})
    assert result["rule"] == "oneOf" and "missing_keys" not in result
    assert {tuple(cause["missing_keys"]) for cause in result["alternatives"]} == {("position",), ("captureId",)}
    assert result["alternatives_complete"]


def test_deep_union_budget_never_claims_complete_alternatives(monkeypatch):
    schema = {"type": "integer"}
    for _ in range(24):
        schema = {"anyOf": [schema]}
    name = fixture_tool(monkeypatch, schema)
    result = invalid(name, "secret-value")
    assert not result["alternatives_complete"] and len(result["alternatives"]) <= 4
    assert "secret-value" not in json.dumps(result)


async def test_invalid_standalone_action_has_hints_and_zero_game_or_takeover_calls(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_gui_widget_action", {"screenId": "s", "action": "activate"})
    assert result.isError and result.structuredContent["error"]["missing_keys"] == ["widgetId"]
    assert state["calls"] == [] and result.structuredContent["mcpTiming"]["bridgeCalls"] == 0


async def test_invalid_later_workflow_step_has_index_and_blocks_earlier_reads(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(read("before", tool="mcb_snapshot"),
        action("bad", tool="mcb_gui_widget_action", args={"screenId": "s", "action": "activate"})))
    error = result.structuredContent["error"]
    assert error["tool"] == "mcb_gui_widget_action" and error["step_index"] == 1 and error["missing_keys"] == ["widgetId"]
    assert state["calls"] == []


def test_feedback_does_not_mutate_cached_schema():
    original = copy.deepcopy(tool_validator("mcb_hotbar").schema)
    invalid("mcb_hotbar", {})
    assert tool_validator("mcb_hotbar").schema == original
    _validate("mcb_hotbar", {"slot": 8})


@pytest.mark.parametrize("transport", ["stdio", "in-process"])
@pytest.mark.parametrize("tool,args,code", [
    ("mcb_gui_widget_action", '{"screenId":"s","action":"activate"}', "INVALID_ARGUMENTS"),
    ("mcb_not_a_tool", '{}', "UNKNOWN_TOOL"),
    ("mcb_hotbar", '{"slot":NaN}', "INVALID_ARGUMENTS"),
    ("mcb_hotbar", '["secret-value"]', "INVALID_ARGUMENTS"),
    ("mcb_hotbar", '{"secret-value":', "INVALID_ARGUMENTS"),
])
async def test_cli_preflight_refuses_before_opening_either_transport(monkeypatch, capsys, transport, tool, args, code):
    from minecraft_codex_bridge import tool_cli as cli
    async def forbidden(*_):
        raise AssertionError("No child server or in-process transport may be created")
    monkeypatch.setattr(cli, "call_stdio", forbidden)
    monkeypatch.setattr(cli, "call_in_process", forbidden)
    options = SimpleNamespace(tool=tool, args=args, transport=transport, runtime="absent", image=None, output=None)
    assert await cli.main(options) == 1
    result = json.loads(capsys.readouterr().out)
    assert result["error"]["code"] == code and result["preflight"]["transportStarted"] is False
    assert result["preflight"]["gameCalls"] == 0 and "secret-value" not in json.dumps(result)
