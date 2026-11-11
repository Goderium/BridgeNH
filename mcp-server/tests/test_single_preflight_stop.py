"""Fresh enabled BasicMachine preflight may only disable an idle machine once."""

import anyio
import pytest

import test_recipe_tasks as native
from test_single_recipe_tasks import arguments, single_machine
from minecraft_codex_bridge.bridge import BridgeError

native_bridge = native.native_bridge


def enabled_controls(state):
    state["allowed"] = True
    widget = state["controls"]["widgets"][0]
    widget["selected"] = 1
    if "overlay" in widget:
        widget["overlay"]["current"]["location"] = "gregtech:textures/gui/overlay_button/power_switch_on.png"


def power_calls(state):
    return [call for call in state["calls"] if call["operation"] == "gui.widget_action"
            and call["args"].get("action") == "set_selected"]


async def test_enabled_idle_single_preflight_disables_before_query_and_feed(single_machine, monkeypatch):
    client, state = single_machine
    enabled_controls(state)
    original = client.call

    async def synchronized_controls(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.widget_action" and args.get("selected") == 0:
            if not state["queries"]:
                await anyio.sleep(.025)
            widget = state["controls"]["widgets"][0]
            widget["selected"] = 0
            if "overlay" in widget:
                widget["overlay"]["current"]["location"] = "gregtech:textures/gui/overlay_button/power_switch_off.png"
        return result

    monkeypatch.setattr(client, "call", synchronized_controls)
    result = await native.invoke(client, arguments(preflightControls=True))
    assert result["status"] == "completed", result
    assert [call["args"]["selected"] for call in power_calls(state)] == [0, 1, 0]
    first_off = state["calls"].index(power_calls(state)[0])
    query = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "recipes.query")
    feed = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.slot_click")
    assert first_off < query < feed
    assert any(call["operation"] == "machine.inspect" for call in state["calls"][first_off + 1:query])
    assert result["acceptance"]["consumed"] == 1 and result["acceptance"]["controllerStopped"]
    assert result["timings_ms"]["preflight"] >= 10


@pytest.mark.parametrize("flag,problem", [(False, "enabled"), (True, "active"),
    (True, "progress"), (True, "maximum"), (True, "missing"), (True, "typed")])
async def test_single_initial_preflight_rejects_unsafe_or_implicit_stop(single_machine, flag, problem):
    client, state = single_machine
    enabled_controls(state)
    if problem == "active":
        state["active"] = True
    elif problem == "progress":
        state["single_progress"] = 1
    elif problem == "maximum":
        state["single_max_progress"] = 1
    elif problem == "missing":
        state["single_missing_progress"] = True
    elif problem == "typed":
        state["single_progress"] = False
    result = await native.invoke(client, arguments(preflightControls=flag))
    assert result["status"] == "unavailable" and result["reason"] == "stopped_single_machine_required", result
    assert native.writes(state) == [] and state["queries"] == []


@pytest.mark.parametrize("problem", ["progress", "identity", "uncertain"])
async def test_single_preflight_stop_requires_fresh_proof_and_never_replays(single_machine, monkeypatch, problem):
    client, state = single_machine
    enabled_controls(state)
    original = client.call

    async def changed_after_off(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.widget_action" and args.get("selected") == 0:
            if problem == "progress":
                state["single_progress"] = 1
            elif problem == "identity":
                state["single_meta_id"] += 1
            else:
                options["trace"]["outcome_unknown"] = True
                raise BridgeError("TRANSPORT_ERROR", "Native power-off outcome uncertain.", outcome_unknown=True)
        return result

    monkeypatch.setattr(client, "call", changed_after_off)
    result = await native.invoke(client, arguments(preflightControls=True))
    assert result["status"] == "failed", result
    assert [call["args"]["selected"] for call in power_calls(state)] == [0]
    assert state["queries"] == [] and state["input_material"] == 0
    assert not any(call["operation"] in {"chat.send", "gui.slot_click", "machine.set_working"} for call in state["calls"])
    if problem == "uncertain":
        assert result["outcomeUnknown"] and result["lastActionReceipt"]["operation"] == "gui.widget_action"
    else:
        assert result["reason"] == ("stopped_single_machine_required" if problem == "progress"
                                    else "component_controller_identity_changed")
