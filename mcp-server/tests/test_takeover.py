"""Control preparation uses current game state and cannot dispatch after failed takeover."""

import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOLS
from expanded_examples import ACTION_EXAMPLES
from qa_examples import ACTION_QA

CONTROL_EXAMPLES = {
    "mcb_look": ("player.look", {"yaw": 10, "pitch": 5}),
    "mcb_hotbar": ("player.hotbar", {"slot": 2}),
    "mcb_input": ("player.input", {"keys": ["forward"], "ticks": 2}),
    "mcb_flight_check": ("player.flight_check", {"action": "inspect", "expectedSessionId": "first-session", "expectedWorld": "test", "dimension": 0}),
    "mcb_interact": ("player.interact", {"action": "use"}),
    "mcb_place_block": ("player.place_block", {"x": 0, "y": 64, "z": 0, "hotbarSlot": 1, "expectedWorld": "test"}),
    "mcb_slot_click": ("gui.slot_click", {"windowId": 1, "slot": 2}),
    "mcb_open_inventory": ("gui.open_inventory", {}),
    "mcb_open_pause_menu": ("gui.open_pause_menu", {}),
    "mcb_gui_click": ("gui.click", {"screenId": "screen", "x": 20, "y": 30}),
    "mcb_gui_key": ("gui.key", {"screenId": "screen", "keyCode": 1}),
    "mcb_close_gui": ("gui.close", {}),
    "mcb_chat": ("chat.send", {"message": "test"}),
    "mcb_supply_items": ("inventory.supply", {"expectedSessionId": "first-session", "expectedWorld": "test",
        "items": [{"registryName": "IC2:itemFluidCell", "metadata": 0, "count": 1}]}),
    "mcb_machine_work": ("machine.set_working", {"x": 0, "y": 64, "z": 0, "expectedWorld": "test", "expectedMetaTileId": 1, "working": False}),
    "mcb_teleport": ("dev.teleport", {"x": 0.5, "y": 64, "z": 0.5, "expectedWorld": "test"}),
    "mcb_load_world": ("client.load_world", {"folder": "test", "expectedSessionId": "session"}),
    "mcb_connect_server": ("client.connect", {"host": "127.0.0.1", "port": 25565, "expectedSessionId": "session"}),
    "mcb_leave_world": ("client.leave_world", {"expectedWorld": "test", "expectedSessionId": "session"}),
    "mcb_exit": ("client.exit", {"expectedSessionId": "session"}),
}
CONTROL_EXAMPLES.update({name: (next(tool.operation for tool in TOOLS if tool.name == name), args) for name, args in ACTION_EXAMPLES.items()})
CONTROL_EXAMPLES.update({name: (next(tool.operation for tool in TOOLS if tool.name == name), args) for name, args in ACTION_QA.items()})
PROTECTED = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
MANUAL = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}


@pytest.fixture
async def bridge():
    state = {
        "calls": [], "background": MANUAL.copy(), "protection": PROTECTED.copy(),
        "capabilities": [tool.operation for tool in TOOLS if tool.operation],
        "protocol_version": 1, "session_id": "first-session", "health_error": None,
        "background_error": None, "action_error": None, "slow_background": False,
        "cancel_status": "cancelled", "entered_background": asyncio.Event(),
    }

    async def respond(request):
        body = json.loads(request.content)
        state["calls"].append({**body, "path": request.url.path})
        error = None
        if request.url.path == "/v1/cancel":
            data = {"status": state["cancel_status"]}
        elif body["operation"] == "bridge.health":
            error = state["health_error"]
            data = {"protocolVersion": state["protocol_version"], "capabilities": state["capabilities"], "sessionId": state["session_id"], "background": state["background"]}
        elif body["operation"] == "client.background":
            state["entered_background"].set()
            if state["slow_background"]:
                await asyncio.sleep(10)
            error = state["background_error"]
            data = state["protection"]
            if not error:
                state["background"] = data.copy()
        else:
            error = state["action_error"]
            data = {"operation": body["operation"], "args": body["args"]}
        envelope = {"requestId": body["requestId"], "ok": error is None}
        envelope["error" if error else "data"] = {"code": error, "message": "Request refused."} if error else data
        return httpx.Response(200, json=envelope)

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "takeover-test-token"}), timeout=0.04, transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def operations(state):
    return [call.get("operation", "__cancel__") for call in state["calls"]]


def test_only_the_designated_control_tools_select_automatic_takeover():
    assert len(TOOLS) == len({tool.name for tool in TOOLS})
    # These entries require their guarded native receipt transports and are
    # covered by their focused suites rather than bare action examples below.
    assert {tool.name for tool in TOOLS if tool.takeover} == set(CONTROL_EXAMPLES) | {"mcb_survival_build", "mcb_transfer_whole_stack", "mcb_window_action"}
    assert all(not tool.read_only for tool in TOOLS if tool.takeover)
    assert all(not tool.takeover for tool in TOOLS if tool.operation is None)
    preflight = next(tool for tool in TOOLS if tool.name == "mcb_placement_preflight")
    assert preflight.read_only is True and preflight.takeover is False
    assert preflight.operation is None and preflight.idempotent is False


@pytest.mark.parametrize("name", CONTROL_EXAMPLES)
async def test_each_control_is_protected_before_dispatch(bridge, name):
    adapter, state = bridge
    operation, arguments = CONTROL_EXAMPLES[name]
    result = await adapter.call_tool(name, arguments)
    if name == "mcb_place_block":
        # The generic mock has no authoritative native context. Ordinary
        # placement must now reject that missing proof after one dispatch.
        assert result.isError
        assert result.structuredContent["error"]["code"] == "MALFORMED_RESPONSE"
        assert result.structuredContent["error"]["outcome_unknown"] is True
        assert operations(state) == ["bridge.health", "client.background", operation]
        assert state["calls"][1]["args"] == {"enabled": True}
        assert state["calls"][2]["args"] == arguments
        assert state["calls"][2]["includeContext"] == {
            "version": 1, "expectedSessionId": "first-session", "expectedProfile": "developer",
            "expectedWorld": "test", "requireBackground": True}
        return
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", operation]
    assert state["calls"][1]["args"] == {"enabled": True}
    assert state["calls"][2]["args"] == arguments
    assert result.structuredContent["data"] == {"operation": operation, "args": arguments}


async def test_current_protection_avoids_repeated_toggle_but_release_and_new_session_are_observed(bridge):
    adapter, state = bridge
    state["background"] = PROTECTED.copy()
    for _ in range(2):
        assert not (await adapter.call_tool("mcb_hotbar", {"slot": 1})).isError
    assert operations(state) == ["bridge.health", "player.hotbar"] * 2
    # The game-side /mcb release can happen outside this MCP process.
    state["background"] = MANUAL.copy()
    assert not (await adapter.call_tool("mcb_hotbar", {"slot": 1})).isError
    assert operations(state)[-3:] == ["bridge.health", "client.background", "player.hotbar"]
    state["session_id"] = "second-session"
    state["background"] = MANUAL.copy()
    assert not (await adapter.call_tool("mcb_hotbar", {"slot": 1})).isError
    assert operations(state)[-3:] == ["bridge.health", "client.background", "player.hotbar"]


@pytest.mark.parametrize("state_data", [
    {}, {"enabled": True},
    {**PROTECTED, "enabled": 1}, {**PROTECTED, "helperInstalled": 1},
    {**PROTECTED, "helperInstalled": False}, {**PROTECTED, "pauseOnLostFocus": 0},
    {**PROTECTED, "pauseOnLostFocus": True}, {**PROTECTED, "pauseOnLostFocus": None},
])
async def test_incomplete_actual_health_protection_is_reestablished(bridge, state_data):
    adapter, state = bridge
    state["background"] = state_data
    result = await adapter.call_tool("mcb_hotbar", {"slot": 1})
    assert not result.isError
    assert operations(state) == ["bridge.health", "client.background", "player.hotbar"]


@pytest.mark.parametrize("state_data", [
    {}, {"enabled": True},
    {**PROTECTED, "enabled": 1}, {**PROTECTED, "helperInstalled": 1},
    {**PROTECTED, "helperInstalled": False}, {**PROTECTED, "pauseOnLostFocus": 0},
    {**PROTECTED, "pauseOnLostFocus": True}, {**PROTECTED, "pauseOnLostFocus": None},
])
async def test_unconfirmed_takeover_never_dispatches_original_action(bridge, state_data):
    adapter, state = bridge
    state["protection"] = state_data
    result = await adapter.call_tool("mcb_hotbar", {"slot": 1})
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == "TAKEOVER_NOT_CONFIRMED"
    assert error["stage"] == "takeover" and error["original_action_executed"] is False
    assert error["blocked_operation"] == "player.hotbar"
    assert operations(state) == ["bridge.health", "client.background"]


@pytest.mark.parametrize("capabilities,code", [
    (["client.background", "client.snapshot"], "UNSUPPORTED_CAPABILITY"),
    (["player.hotbar", "client.snapshot"], "UNSUPPORTED_CAPABILITY"),
    ({"player.hotbar": True, "client.background": False}, "UNSUPPORTED_CAPABILITY"),
    ({"player.hotbar": 1, "client.background": True}, "MALFORMED_RESPONSE"),
    (["player.hotbar", 1], "MALFORMED_RESPONSE"), (None, "MALFORMED_RESPONSE"),
])
async def test_missing_or_malformed_capability_blocks_takeover_and_control(bridge, capabilities, code):
    adapter, state = bridge
    state["capabilities"] = capabilities
    result = await adapter.call_tool("mcb_hotbar", {"slot": 1})
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == code
    assert error["original_action_executed"] is False
    assert operations(state) == ["bridge.health"]


async def test_dictionary_capabilities_are_supported(bridge):
    adapter, state = bridge
    state["capabilities"] = {"player.hotbar": True, "client.background": True}
    assert not (await adapter.call_tool("mcb_hotbar", {"slot": 1})).isError
    assert operations(state) == ["bridge.health", "client.background", "player.hotbar"]


@pytest.mark.parametrize("mode,code", [("health-error", "AUTH_FAILED"), ("protocol", "PROTOCOL_VERSION"), ("mouse-grabbed", "mouse_grabbed")])
async def test_takeover_errors_preserve_the_cause_and_block_original(bridge, mode, code):
    adapter, state = bridge
    if mode == "health-error":
        state["health_error"] = code
    elif mode == "protocol":
        state["protocol_version"] = 2
    else:
        state["background_error"] = code
    result = await adapter.call_tool("mcb_hotbar", {"slot": 1})
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == code
    assert error["stage"] == "takeover" and error["original_action_executed"] is False
    assert "player.hotbar" not in operations(state)
    assert operations(state).count("client.background") == (1 if mode == "mouse-grabbed" else 0)


@pytest.mark.parametrize("name,args,operation", [
    ("mcb_mouse", {"enabled": True}, "client.mouse"),
    ("mcb_unfocused", {"enabled": False}, "client.unfocused"),
    ("mcb_background", {"enabled": False}, "client.background"),
    ("mcb_policy", {"action": "status"}, "bridge.policy"),
    ("mcb_snapshot", {}, "client.snapshot"),
])
async def test_manual_switches_profile_and_read_do_not_request_automatic_takeover(bridge, name, args, operation):
    adapter, state = bridge
    assert not (await adapter.call_tool(name, args)).isError
    assert operations(state) == ["bridge.health", operation]
    assert state["calls"][-1]["args"] == args


async def test_legacy_mod_reads_work_but_control_requires_background_capability(bridge):
    adapter, state = bridge
    state["capabilities"] = ["client.snapshot", "player.hotbar"]
    assert not (await adapter.call_tool("mcb_snapshot", {})).isError
    assert (await adapter.call_tool("mcb_hotbar", {"slot": 1})).isError
    assert operations(state) == ["bridge.health", "client.snapshot", "bridge.health"]


@pytest.mark.parametrize("status,unknown", [("cancelled", False), ("already_running", True), ("not_found", True)])
async def test_takeover_timeout_cancels_only_the_original_background_request(bridge, status, unknown):
    adapter, state = bridge
    state["slow_background"] = True
    state["cancel_status"] = status
    result = await adapter.call_tool("mcb_hotbar", {"slot": 1})
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == "BRIDGE_TIMEOUT"
    assert error["stage"] == "takeover" and error["original_action_executed"] is False
    assert error["cancellation_status"] == status and error["outcome_unknown"] is unknown
    assert operations(state) == ["bridge.health", "client.background", "__cancel__"]
    assert state["calls"][1]["requestId"] == state["calls"][2]["requestId"] == error["request_id"]


async def test_cancellation_during_takeover_never_dispatches_the_original_action(bridge):
    adapter, state = bridge
    adapter.client.timeout = 5
    state["slow_background"] = True
    task = asyncio.create_task(adapter.call_tool("mcb_hotbar", {"slot": 1}))
    await asyncio.wait_for(state["entered_background"].wait(), timeout=1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert operations(state) == ["bridge.health", "client.background", "__cancel__"]
    assert state["calls"][1]["requestId"] == state["calls"][2]["requestId"]


async def test_original_action_error_is_not_mislabeled_as_failed_preparation(bridge):
    adapter, state = bridge
    state["action_error"] = "stale_screen"
    result = await adapter.call_tool("mcb_gui_key", {"screenId": "screen", "keyCode": 1})
    assert result.isError
    assert result.structuredContent["error"] == {"code": "stale_screen", "message": "Request refused."}
    assert operations(state) == ["bridge.health", "client.background", "gui.key"]
