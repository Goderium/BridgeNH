"""Window observation and mutations retain real identities, evidence and no-replay boundaries."""
import pytest

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_expanded_operations import bridge, operations


async def test_window_read_preserves_independent_native_and_game_modes_without_takeover(bridge):
    adapter, state = bridge
    evidence = {"clientFullscreen": True, "fullscreenPreference": False,
                "window": {"mode": "borderless_fullscreen", "maximized": False,
                           "nativeFullscreen": False, "gameFullscreen": True}}
    state["data"]["client.window"] = evidence
    result = await adapter.call_tool("mcb_window", {})
    assert not result.isError and result.structuredContent["data"] == evidence
    assert operations(state) == ["bridge.health", "client.window"]
    assert "client.window" in READ_OPERATIONS and "client.window_action" not in READ_OPERATIONS
    assert not state["protected"]


@pytest.mark.parametrize("action", ["maximize", "restore", "windowed", "minimize", "resize"])
async def test_window_mutation_requires_takeover_and_preserves_unconfirmed_receipt(bridge, action):
    adapter, state = bridge
    evidence = {"submitted": True, "confirmed": False, "nativeSyncCompleted": False,
                "after": {"mode": "windowed", "maximized": False}}
    state["data"]["client.window_action"] = evidence
    args = {"expectedSessionId": "expanded-session", "expectedWindowId": "abc", "expectedMode": "windowed", "action": action}
    if action == "resize": args.update(width=1280, height=720)
    result = await adapter.call_tool("mcb_window_action", args)
    assert result.structuredContent["data"] == evidence
    writes = [c for c in state["calls"] if c["operation"] == "client.window_action"]
    assert len(writes) == 1 and writes[0]["args"] == args
    assert state["protected"]
    assert TOOL_BY_NAME["mcb_window_action"].idempotent is False


@pytest.mark.parametrize("missing", ["expectedSessionId", "expectedWindowId", "expectedMode", "action"])
async def test_window_incomplete_identity_is_rejected_before_native_actions(bridge, missing):
    adapter, state = bridge
    args = {"expectedSessionId": "expanded-session", "expectedWindowId": "abc", "expectedMode": "windowed", "action": "maximize"}
    del args[missing]
    result = await adapter.call_tool("mcb_window_action", args)
    assert result.isError and "client.window_action" not in operations(state)


async def test_window_native_refusal_is_not_replayed_or_replaced_with_fullscreen(bridge):
    adapter, state = bridge
    state["error"] = "window_changed"
    result = await adapter.call_tool("mcb_window_action", {"expectedSessionId": "expanded-session", "expectedWindowId": "abc", "expectedMode": "windowed", "action": "maximize"})
    assert result.isError
    assert operations(state).count("client.window_action") == 1
    assert "gui.key" not in operations(state)


@pytest.mark.parametrize("dimensions", [{}, {"width": 1100}, {"width": 255, "height": 650},
                         {"width": 1100, "height": 8193}, {"width": 1100.5, "height": 650}])
async def test_invalid_resize_is_rejected_before_takeover(bridge, dimensions):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_window_action", {
        "expectedSessionId": "expanded-session", "expectedWindowId": "abc",
        "expectedMode": "windowed", "action": "resize", **dimensions})
    assert result.isError and "client.window_action" not in operations(state)
    assert not state["protected"]


async def test_dimensions_do_not_silently_change_another_window_action(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_window_action", {
        "expectedSessionId": "expanded-session", "expectedWindowId": "abc",
        "expectedMode": "windowed", "action": "restore", "width": 1100, "height": 650})
    assert result.isError and not state["protected"]
