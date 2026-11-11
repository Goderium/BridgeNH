"""Opt-in controller stopping over real transport/native workflows, offline only."""
import copy
import json

import httpx
import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import CONTEXT_ACTIONS, CONTEXT_READS
from minecraft_codex_bridge.construction_tasks import run_blueprint_build
from minecraft_codex_bridge.validation import tool_validator

native_construction = native.native_construction
CLASS = "Public.TaskOwned.Controller"
POWER_ID = "public:semantic:power"


def snapshot(state):
    return {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": native.BACKGROUND,
        "screenOpen": state["gui"] is not None, "screenId": "screen:" + str(state["gui"]),
        "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0},
        "player": {"name": "TestPlayer", "health": state.get("health", 20), **state["position"], "feetY": state["position"]["y"],
            "eyeY": state["position"]["y"] + 1.62, "flying": state["flying"], "selectedHotbarSlot": state["selected"],
            "serverFlight": {"available": True, "allowFlying": True, "flying": state["flying"], "dimension": 0}}}


def stopping_world(client, state, monkeypatch, *, fault=None, context=True):
    original = client.http._transport.handle_async_request
    state.update(stop_allowed=True, stop_writes=0, post_stop_reads=0, stop_preflight_seen=False)

    async def respond(request):
        body = json.loads(request.content)
        operation, args = body["operation"], body.get("args", {})
        before = snapshot(state)
        if operation == "gui.widget_action" and args.get("widgetId") == POWER_ID:
            state["calls"].append({**body, "operation": operation})
            if state["gui"] != "machine" or args.get("screenId") != "screen:machine":
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": "GUI_CHANGED", "message": "Native screen object no longer matches."}})
            assert args["action"] == "set_selected" and args["selected"] == 0
            state["stop_writes"] += 1
            if fault != "stop_unconfirmed":
                state["stop_allowed"] = False
            if fault == "uncertain_power":
                raise httpx.ReadError("one native stop outcome unknown", request=request)
            envelope = {"requestId": body["requestId"], "ok": True, "data": {"screenId": args["screenId"],
                "widgetId": POWER_ID, "action": args["action"], "submitted": True}}
        else:
            response = await original(request)
            envelope = json.loads(await response.aread())
        data = envelope.get("data", {})
        if operation == "bridge.health" and context:
            data["contextRpc"] = {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}
        if operation == "machine.inspect" and native.key(args) == native.key(state["controller"]):
            if state["built"] and state["gui"] == "machine" and not state["rechecks"]:
                state["stop_preflight_seen"] = True
            data.update(metaClass=CLASS, state={"active": False, "allowedToWork": state["stop_allowed"],
                "progressTicks": 0, "maxProgressTicks": 0})
            data["components"]["softBudgetPartial"] = False
            data["components"]["modularHatches"] = {"available": False,
                "reason": "public_modular_interface_unavailable_or_not_implemented"}
            data["components"]["unavailableRoles"].append({"role": "modular_hatch",
                "reason": "public_modular_interface_unavailable_or_not_implemented"})
            if state["rechecks"]:
                state["post_stop_reads"] += 1
                if fault == "transient_stop" and state["post_stop_reads"] == 1:
                    data["state"]["allowedToWork"] = True
                elif fault == "post_class":
                    data["metaClass"] = CLASS + ".Changed"
                elif fault == "progress_missing":
                    data["state"].pop("progressTicks")
                elif fault == "active_type":
                    data["state"]["active"] = 0
                elif fault == "component_partial":
                    data["components"]["softBudgetPartial"] = True
                elif fault == "component_omitted":
                    data["components"]["entries"].pop()
                elif fault == "component_counts":
                    data["components"]["roles"][0]["count"] += 1
                elif fault == "core_unavailable":
                    data["components"]["unavailableRoles"].append({"role": "execution_core"})
                elif fault == "modular_missing":
                    data["components"].pop("modularHatches")
                elif fault == "modular_reason_missing":
                    data["components"]["modularHatches"].pop("reason")
                elif fault == "modular_count_mismatch":
                    data["components"]["modularHatches"].update(available=True, registeredCount=1)
                elif fault in ("core_working", "core_unbound", "core_missing", "core_stopped"):
                    core = {"role": "execution_core", "valid": True, **state["controller"], "dimension": 0}
                    if fault != "core_missing":
                        core["executionCore"] = {"working": fault == "core_working", "mainMachine": {
                            "controllerIdentityMatches": fault != "core_unbound"}}
                    data["components"]["entries"].append(core)
                    data["components"]["roles"].append({"role": "execution_core", "count": 1})
                    data["components"]["total"] += 1
                    data["components"]["nextOffset"] += 1
            elif fault in ("pre_x", "pre_dimension", "pre_id", "pre_class_missing"):
                if fault == "pre_x": data["x"] += 1
                if fault == "pre_dimension": data["dimension"] += 1
                if fault == "pre_id": data["metaTileId"] += 1
                if fault == "pre_class_missing": data.pop("metaClass")
        if operation == "gui.widgets" and state["gui"] == "machine":
            power = {"widgetId": POWER_ID, "kind": "choice", "semantic": "power_switch", "enabled": True,
                "valid": True, "supportedActions": ["set_selected"], "selected": int(state["stop_allowed"]),
                "optionCount": 2, "semanticSource": "GT shared public tooltip/texture resource; current screen object identity required for mutation"}
            if fault == "selection_mismatch": power["selected"] = 1 - power["selected"]
            if fault == "power_disabled": power["enabled"] = False
            if fault == "power_semantic": power["semanticSource"] = "private class guess"
            if fault != "power_missing": data["widgets"].append(power)
            if fault == "power_ambiguous": data["widgets"].append({**power, "widgetId": POWER_ID + ":2"})
            if fault == "controls_partial": data["scanTruncated"] = True
            if fault == "screen_drift": state["gui"] = "foreign"
        if state["stop_preflight_seen"] and not state["rechecks"]:
            if operation == "inventory.inspect":
                if fault == "cursor_changed": data["cursorItem"] = native.stack(native.CASING)
                if fault == "inventory_partial": data["truncated"] = True
                if fault == "hand_changed":
                    next(slot for slot in data["slots"] if slot["index"] == state["selected"])["item"] = native.stack(native.CASING)
            if operation == "gui.inspect" and fault == "window_drift": data["windowId"] += 1
        if operation == "gui.widget_action" and args.get("action") == "recheck_structure" and fault == "uncertain_recheck":
            raise httpx.ReadError("one recheck outcome unknown", request=request)
        if "includeContext" in body:
            envelope["context"] = {"version": 1, "operation": operation,
                "phase": "before" if operation in CONTEXT_ACTIONS else "after",
                "snapshot": before if operation in CONTEXT_ACTIONS else snapshot(state)}
            if state["stop_writes"] and operation == "machine.inspect" and fault == "after_context_missing":
                envelope.pop("context")
        return httpx.Response(200, json=envelope)

    monkeypatch.setattr(client.http._transport, "handle_async_request", respond)


async def invoke(client, **changes):
    import anyio
    return await run_blueprint_build(client, native.declaration(stopOnComplete=True, **changes), anyio.Lock())


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("context", [True, False])
async def test_native_stop_and_formation_share_one_gui_and_complete_read(native_construction, monkeypatch, order, context):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, context=context)
    result = await invoke(client, constructionOrder=order)
    assert result["status"] == "completed", result
    stop = result["acceptance"]["stopAcceptance"]
    assert stop["confirmed"] and stop["componentCoverageComplete"] and stop["sameControllerGui"]
    assert stop["progressTicks"] == stop["maxProgressTicks"] == 0 and state["stop_writes"] == state["rechecks"] == 1
    assert state["post_stop_reads"] == 1 and state["gui"] == "machine" and state["cursor"] is None
    accept_index = next(i for i,c in enumerate(state["calls"]) if c["operation"] == "gui.widget_action" and c["args"].get("widgetId") == POWER_ID)
    ending = state["calls"][accept_index:]
    assert sum(c["operation"] == "player.interact" for c in ending) == 0
    assert sum(c["operation"] == "machine.inspect" and native.key(c["args"]) == native.key(state["controller"]) for c in ending) == 1
    assert {entry["role"] for entry in result["acceptance"]["registeredRoles"]} == set(native.PORT_ITEMS)
    if context:
        assert all(c.get("includeContext", {}).get("requireBackground") is True
            for c in ending if c["operation"] == "gui.widget_action" or c["operation"] == "machine.inspect"
                and native.key(c["args"]) == native.key(state["controller"]))


@pytest.mark.parametrize("explicit", [False, None])
async def test_default_has_no_new_stop_reads_or_writes(native_construction, monkeypatch, explicit):
    client, state = native_construction
    stopping_world(client, state, monkeypatch)
    import anyio
    args = native.declaration(**({"stopOnComplete": explicit} if explicit is not None else {}))
    result = await run_blueprint_build(client, args, anyio.Lock())
    assert result["status"] == "completed", result
    assert state["stop_writes"] == 0 and state["stop_allowed"] is True
    assert "stopAcceptance" not in result["acceptance"]
    assert not any(phase["phase"] == "controller_stop_preflight" for phase in result.get("phases", []))
    assert state["post_stop_reads"] == 1 and not state["stop_preflight_seen"]


@pytest.mark.parametrize("fault", ["power_missing", "power_ambiguous", "selection_mismatch", "power_disabled", "power_semantic",
    "controls_partial", "pre_x", "pre_dimension", "pre_id", "pre_class_missing", "cursor_changed", "inventory_partial", "hand_changed", "window_drift"])
async def test_missing_changed_or_ambiguous_proof_refuses_before_native_stop(native_construction, monkeypatch, fault):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault=fault)
    result = await invoke(client)
    assert result["status"] == "failed", result
    assert state["stop_writes"] == state["rechecks"] == 0


@pytest.mark.parametrize("fault", ["post_class", "progress_missing", "active_type", "component_partial", "component_omitted",
    "component_counts", "core_unavailable", "core_working", "core_unbound", "core_missing", "after_context_missing",
    "modular_missing", "modular_reason_missing", "modular_count_mismatch"])
async def test_complete_stopped_acceptance_never_infers_omitted_or_changed_state(native_construction, monkeypatch, fault):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault=fault)
    result = await invoke(client)
    assert result["status"] == "failed", result
    assert state["stop_writes"] == state["rechecks"] == 1 and not result["formationVerified"]


@pytest.mark.parametrize("fault", ["uncertain_power", "uncertain_recheck", "screen_drift"])
async def test_uncertain_dispatch_or_native_screen_drift_never_reopens_or_replays(native_construction, monkeypatch, fault):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault=fault)
    result = await invoke(client)
    assert result["status"] == "failed", result
    powers = [c for c in state["calls"] if c["operation"] == "gui.widget_action" and c["args"].get("widgetId") == POWER_ID]
    assert len(powers) == 1
    assert state["rechecks"] == int(fault == "uncertain_recheck")
    after = state["calls"][state["calls"].index(powers[0]) + 1:]
    assert not any(c["operation"] == "player.interact" for c in after)


async def test_transient_native_synchronization_polls_only_complete_reads(native_construction, monkeypatch):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault="transient_stop")
    result = await invoke(client)
    assert result["status"] == "completed", result
    assert state["stop_writes"] == state["rechecks"] == 1 and state["post_stop_reads"] == 2


async def test_stopped_exposed_cores_are_retained_in_compact_success(native_construction, monkeypatch):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault="core_stopped")
    result = await invoke(client, includeDetails=False)
    assert result["status"] == "completed", result
    assert result["acceptance"]["stopAcceptance"]["exposedExecutionCores"][0]["role"] == "execution_core"
    assert result["completeRulesKnown"] is False and result["fullStructureChecked"] is False


async def test_unconfirmed_native_stop_is_bounded_and_not_replayed(native_construction, monkeypatch):
    client, state = native_construction
    stopping_world(client, state, monkeypatch, fault="stop_unconfirmed")
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == "construction_stop_unconfirmed", result
    assert state["stop_writes"] == state["rechecks"] == 1 and state["post_stop_reads"] < 40


@pytest.mark.parametrize("value", ["true", 1, None])
def test_opt_in_requires_an_actual_boolean(value):
    assert list(tool_validator("mcb_blueprint_build").iter_errors(native.declaration(stopOnComplete=value)))
