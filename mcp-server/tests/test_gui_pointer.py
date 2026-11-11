"""Logical hover is a guarded action; clearing preserves the user's input mode."""
from copy import deepcopy

import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, SEQUENCE_ACTION_NAMES, TOOL_BY_NAME, TOOLS
from minecraft_codex_bridge.workflows import project
from test_nei_view import SCREEN
from test_workflows import bridge, operations, MANUAL, PROTECTED


SET = {**SCREEN, "action": "set", "x": 7, "y": 8}
CLEAR = {**SCREEN, "action": "clear"}
RECEIPT = {**SCREEN, "status": "armed", "generation": 17,
    "target": {"source": "recipe", "x": 29, "y": 36, "recipeIndex": 2,
        "positionKind": "inputs", "positionIndex": 1, "observedRectangle": {"x": 21, "y": 28, "width": 16, "height": 16}},
    "acceptance": "logical_pointer_only_requires_natural_frame_readback",
    "pointer": {"source": "agent_logical_gui", "generation": 17, "remainingTTL": 2000, "currentScreenBound": True}}


@pytest.mark.parametrize("args", [
    SET, {**SET, "source": "coordinates", "x": 0, "y": 32767, "ttl_ms": 100},
    {**SET, "ttl_ms": 5000}, {**SCREEN, "action": "set", "source": "recipe", "recipeIndex": 0},
    *[{**SCREEN, "action": "set", "source": "recipe", "recipeIndex": 2147483647,
        "positionKind": kind, "positionIndex": 2147483647, "ttl_ms": 100} for kind in ("inputs", "outputs", "other")],
    *[{**SCREEN, "action": "set", "source": "slot", "slot": slot} for slot in (0, 4095)],
])
async def test_exact_logical_selector_dispatches_once_after_takeover_without_os_input(bridge, args):
    adapter, state = bridge
    state["data"]["gui.pointer"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_gui_pointer", args)
    assert not result.isError and result.structuredContent["data"] == RECEIPT
    assert operations(state) == ["bridge.health", "client.background", "gui.pointer"]
    assert state["calls"][-1]["args"] == args
    assert state["background"] == PROTECTED


@pytest.mark.parametrize("initial", [MANUAL, PROTECTED])
async def test_clear_direct_preserves_mouse_and_unfocused_choices(bridge, initial):
    adapter, state = bridge
    state["background"] = initial.copy()
    state["data"]["gui.pointer"] = {**SCREEN, "status": "cleared", "generation": 18}
    result = await adapter.call_tool("mcb_gui_pointer", CLEAR)
    assert not result.isError and result.structuredContent["data"]["status"] == "cleared"
    assert operations(state) == ["bridge.health", "gui.pointer"]
    assert state["background"] == initial


@pytest.mark.parametrize("args", [
    {}, SCREEN, {**SET, "action": "unknown"}, {**SET, "screenId": ""}, {**SET, "screenId": True},
    {**SET, "source": "unknown"}, {**SCREEN, "action": "set", "x": 0},
    *[{**SET, field: value} for field in ("x", "y") for value in (-1, 32768, True, 0.5, "0")],
    *[{**SET, "ttl_ms": value} for value in (99, 5001, True, "100")],
    *[{**CLEAR, field: value} for field, value in (("source", "coordinates"), ("x", 0), ("y", 0),
        ("ttl_ms", 100), ("recipeIndex", 0), ("positionKind", "inputs"), ("positionIndex", 0), ("slot", 0))],
    *[{**SET, field: value} for field, value in (("recipeIndex", 0), ("positionKind", "inputs"),
        ("positionIndex", 0), ("slot", 0), ("contextToken", "token"), ("allowFallback", True))],
    {**SCREEN, "action": "set", "recipeIndex": 0},
    {**SCREEN, "action": "set", "source": "recipe"},
    *[{**SCREEN, "action": "set", "source": "recipe", "recipeIndex": value} for value in (-1, 2147483648, True, "0")],
    *[{**SCREEN, "action": "set", "source": "recipe", "recipeIndex": 0, field: value}
      for field, value in (("x", 0), ("y", 0), ("slot", 0), ("positionKind", "bad"),
        ("positionIndex", -1), ("positionIndex", True), ("positionIndex", 2147483648))],
    {**SCREEN, "action": "set", "slot": 0}, {**SCREEN, "action": "set", "source": "slot"},
    *[{**SCREEN, "action": "set", "source": "slot", "slot": value} for value in (-999, -1, 4096, True, "0")],
    *[{**SCREEN, "action": "set", "source": "slot", "slot": 0, field: value} for field, value in
        (("x", 0), ("y", 0), ("recipeIndex", 0), ("positionKind", "inputs"), ("positionIndex", 0))],
])
async def test_invalid_or_crossed_selectors_cause_no_http_takeover_or_fallback(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_gui_pointer", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("code", ["stale_screen", "unsupported", "invalid_args"])
@pytest.mark.parametrize("args", [SET, {**SCREEN, "action": "set", "source": "recipe", "recipeIndex": 0}, CLEAR])
async def test_native_refusal_never_moves_physical_mouse_or_retries_coordinates(bridge, code, args):
    adapter, state = bridge
    state["action_error"] = {"code": code, "message": "Native logical target refused."}
    result = await adapter.call_tool("mcb_gui_pointer", args)
    assert result.isError and result.structuredContent["error"]["code"] == code
    assert operations(state).count("gui.pointer") == 1
    assert not any(op in operations(state) for op in ("gui.click", "gui.key", "player.input", "nei.view"))


async def test_missing_capability_blocks_set_before_takeover(bridge):
    adapter, state = bridge
    state["caps"].remove("gui.pointer")
    result = await adapter.call_tool("mcb_gui_pointer", SET)
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert operations(state) == ["bridge.health"] and state["background"] == MANUAL


@pytest.mark.parametrize("action", ["set", "clear"])
async def test_sequence_clear_preserves_manual_mode_and_set_protects_before_action(bridge, action):
    adapter, state = bridge
    state["data"]["gui.pointer"] = deepcopy(RECEIPT)
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "point", "kind": "action", "tool": "mcb_gui_pointer", "args": SET if action == "set" else CLEAR},
    ]})
    assert not result.isError
    report = result.structuredContent["data"]
    assert report["acknowledgedActions"] == 1 and report["completedSteps"] == 1
    assert operations(state).count("gui.pointer") == 1
    assert operations(state).count("client.background") == (1 if action == "set" else 0)
    assert state["background"] == (PROTECTED if action == "set" else MANUAL)
    assert report["steps"][0]["observation"]["values"]["acceptance"] == RECEIPT["acceptance"]
    assert "serverConfirmed" not in report["steps"][0]["observation"]["values"]


async def test_one_sequence_accepts_logical_target_then_checks_actual_completed_hover_contents(bridge):
    adapter, state = bridge
    state["data"]["gui.pointer"] = deepcopy(RECEIPT)
    state["data"]["nei.view"] = {**SCREEN, "section": "detail", "status": "readable", "complete": True,
        "data": {"frameId": 918, "cursorSource": "agent_logical_gui", "pointerGeneration": 17,
            "naturalDrawInvoked": True, "nativeTooltipGate": "visible_positioned_stack",
            "entries": [{"text": "Actual native hover text"}], "pixelDataStored": False}}
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "point", "kind": "action", "tool": "mcb_gui_pointer",
            "args": {**SCREEN, "action": "set", "source": "recipe", "recipeIndex": 2, "positionIndex": 1}},
        {"id": "hover", "kind": "read", "tool": "mcb_nei_view", "args": {**SCREEN, "section": "detail", "target": "hover_tooltip_lines"}},
        {"id": "accepted", "kind": "assert", "source": "hover",
            "condition": {"path": "data.pointerGeneration", "operator": "equals", "valueFrom": {"step": "point", "path": "generation"}}},
    ]})
    assert not result.isError and result.structuredContent["data"]["completedSteps"] == 3
    assert operations(state).count("gui.pointer") == operations(state).count("nei.view") == 1
    hover = result.structuredContent["data"]["steps"][1]["observation"]
    assert hover["metadata"]["data.cursorSource"] == "agent_logical_gui"
    assert hover["metadata"]["data.pointerGeneration"] == 17
    assert hover["metadata"]["data.naturalDrawInvoked"] is True
    assert hover["metadata"]["data.nativeTooltipGate"] == "visible_positioned_stack"


def test_compact_pointer_state_is_screen_bound_acceptance_without_hover_evidence():
    compact = project(deepcopy(RECEIPT), {"kind": "action", "tool": "mcb_gui_pointer"})
    assert compact["values"]["target"] == RECEIPT["target"]
    assert compact["values"]["pointer"] == RECEIPT["pointer"]
    explicit = project(deepcopy(RECEIPT), {"kind": "action", "tool": "mcb_gui_pointer", "fields": ["status"]})
    assert explicit["metadata"]["pointer.generation"] == 17
    assert explicit["metadata"]["pointer.remainingTTL"] == 2000
    assert explicit["metadata"]["pointer.currentScreenBound"] is True
    assert "frameId" not in explicit["metadata"]


def test_actual_nested_hover_gate_keeps_sampled_predicate_distinct_from_forced_decision():
    gate = {"provenance": "sampled_native_public_predicate", "forced": False, "decisionObserved": False,
        "status": "sampled_public_predicate", "sampledShouldShowTooltip": True}
    compact = project({"data": {"cursorSource": "agent_logical_gui", "pointerGeneration": 17,
        "nativeTooltipGate": gate, "naturalDrawInvoked": True}},
        {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for key, value in gate.items():
        assert compact["metadata"]["data.nativeTooltipGate." + key] == value
    assert compact["metadata"]["data.cursorSource"] == "agent_logical_gui"
    assert compact["metadata"]["data.naturalDrawInvoked"] is True


def test_native_scaled_physical_coordinates_stay_distinct_from_logical_hover_coordinates():
    data = {"data": {"cursorSource": "agent_logical_gui", "pointerGeneration": 17, "mouseX": 29, "mouseY": 36,
        "physicalMouseX": -1, "physicalMouseY": 4, "physicalCoordinateSource": "entity_renderer_pre_scaled_arguments"}}
    compact = project(data, {"kind": "read", "tool": "mcb_nei_view", "fields": []})
    for key, value in data["data"].items():
        assert compact["metadata"]["data." + key] == value


async def test_actual_sdk_lists_pointer_as_action_and_calls_clear_without_takeover(bridge):
    adapter, state = bridge
    state["data"]["gui.pointer"] = {**SCREEN, "status": "cleared", "generation": 18}
    async with create_connected_server_and_client_session(make_server(adapter.client)) as session:
        listed = {spec.name: spec for spec in (await session.list_tools()).tools}
        assert set(listed) == {spec.name for spec in TOOLS}
        pointer = listed["mcb_gui_pointer"]
        assert not pointer.annotations.readOnlyHint and not pointer.annotations.destructiveHint
        assert not pointer.annotations.idempotentHint
        assert "set automatically establishes" in pointer.description
        assert "MCP automatically ensures full background takeover before this control operation" not in pointer.description
        result = await session.call_tool("mcb_gui_pointer", CLEAR)
        assert not result.isError and result.structuredContent["data"]["status"] == "cleared"
    assert state["background"] == MANUAL and operations(state) == ["bridge.health", "gui.pointer"]


def test_pointer_is_sequence_action_never_read_observation_or_wait():
    spec = TOOL_BY_NAME["mcb_gui_pointer"]
    assert spec.operation == "gui.pointer" and spec.takeover and not spec.read_only and spec.idempotent is False
    assert spec.name in SEQUENCE_ACTION_NAMES and spec.name not in OBSERVATION_NAMES
    assert spec.operation not in READ_OPERATIONS
    assert spec.name not in TOOL_BY_NAME["mcb_wait_for"].schema["properties"]["tool"]["enum"]
