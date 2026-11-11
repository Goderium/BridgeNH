"""Actual tank controls remain unique when a public phantom lock is present."""

import copy
import json
from pathlib import Path

import pytest

import test_fluid_recipe_tasks as fluid
import test_shared_basic_fluid_tasks as shared
from test_single_recipe_tasks import single_machine
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.fluid_recipe_tasks import fluid_widget, fluid_widget_selector, named_tanks, tank_observation
from minecraft_codex_bridge.workflows import condition_matches

native_bridge = fluid.native_bridge
R16 = json.loads((Path(__file__).parent / "fixtures/r16-810-public-fluid-slot-controls.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("index", [0, 1])
def test_exact_retained_r16_810_server_tank_and_mixed_output_controls(index):
    tank = tank_observation(R16["actualOutputOwnerServerRecords"][index]["result"])
    data = R16["guiWidgetRecords"][0]["result"]
    assert R16["sourceArtifactSha256"] == "914e0a7d05310f3ba54a958e6629ebacf4fd9ec87c613bef20943b28e2283018"
    assert tank == {"index": 0, "capacity": 8000, "fluid": None, "amount": 0}
    assert fluid_widget(data, tank, fill=False)["widgetId"] == "widget:4339"
    assert condition_matches(data, {"path": "widgets", "operator": "matches_one", "value": fluid_widget_selector(tank, fill=False)})


def ghost_lock(widget):
    result = copy.deepcopy(widget)
    result.update(widgetId="lock:" + widget["widgetId"], **{"class": "gregtech.common.modularui2.widget.FluidLockSlotWidget"})
    result["fluidSlot"].update(phantom={"available": True, "value": True}, capacity={"available": True, "value": 0})
    return result


def controls(*, named=False, fill=False):
    if named:
        position = {key: shared.JAVA_FIXTURE["namedTanks"]["owner"][key] for key in ("x", "y", "z", "dimension")}
        tank = named_tanks(shared.observation(shared.JAVA_FIXTURE["namedTanks"]), position)["output"]
        widget = {"widgetId": "actual", "class": shared.FLUID_SLOT_CLASS, "semantic": "fluid_slot", "kind": "fluid_slot",
            "valid": True, "enabled": True, "supportedActions": ["activate"], "fluidSlot": copy.deepcopy(shared.JAVA_FIXTURE["fluidSlot"])}
        widget["fluidSlot"]["canFill"] = {"available": True, "value": fill}
        widget["fluidSlot"]["canDrain"] = {"available": True, "value": not fill}
    else:
        widget = copy.deepcopy(fluid.PROOF["widget"])
        widget["fluidSlot"]["capacity"]["value"] = 8000
        tank = {"index": 0, "capacity": 8000, "fluid": None, "amount": 0}
    return tank, widget


@pytest.mark.parametrize("named", [False, True])
@pytest.mark.parametrize("fill", [False, True])
@pytest.mark.parametrize("reverse", [False, True])
def test_exact_public_qualification_selects_real_control_with_zero_capacity_phantom_lock(named, fill, reverse):
    tank, real = controls(named=named, fill=fill)
    widgets = [real, ghost_lock(real)]
    if reverse:
        widgets.reverse()
    assert fluid_widget({"widgets": widgets}, tank, fill=fill)["widgetId"] == real["widgetId"]
    selector = fluid_widget_selector(tank, fill=fill)
    assert condition_matches({"widgets": widgets}, {"path": "widgets", "operator": "matches_one", "value": selector})


@pytest.mark.parametrize("fault", ["phantom", "phantom_unknown", "permission", "permission_unknown", "zero_capacity", "float_capacity", "capacity_unknown", "wrong_capacity"])
def test_unproven_or_wrong_actual_operation_never_becomes_an_eligible_candidate(fault):
    tank, real = controls()
    lock = ghost_lock(real)
    value = real["fluidSlot"]
    if fault == "phantom":
        value["phantom"]["value"] = True
    elif fault == "phantom_unknown":
        value["phantom"]["available"] = False
    elif fault == "permission":
        value["canDrain"]["value"] = False
    elif fault == "permission_unknown":
        value["canDrain"]["available"] = False
    elif fault == "zero_capacity":
        value["capacity"]["value"] = 0
    elif fault == "float_capacity":
        value["capacity"]["value"] = 8000.0
    elif fault == "capacity_unknown":
        value["capacity"]["available"] = False
    else:
        value["capacity"]["value"] = 16000
    with pytest.raises(BridgeError):
        fluid_widget({"widgets": [real, lock]}, tank, fill=False)


@pytest.mark.parametrize("named", [False, True])
def test_two_actual_qualified_controls_remain_ambiguous_even_if_one_class_changes(named):
    tank, real = controls(named=named)
    extra = copy.deepcopy(real)
    extra.update(widgetId="other-real", **{"class": "different.PublicControl"})
    with pytest.raises(BridgeError) as error:
        fluid_widget({"widgets": [real, ghost_lock(real), extra]}, tank, fill=False)
    assert error.value.details["reason"] == "fluid_slot_binding_unavailable"
    assert not condition_matches({"widgets": [real, extra]}, {"path": "widgets", "operator": "matches_one",
        "value": fluid_widget_selector(tank, fill=False)})


def add_locks(client, state, monkeypatch, *, reverse=False, fault=None):
    original = client.call
    async def call(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "gui.widgets":
            real = [widget for widget in result["widgets"] if widget.get("semantic") == "fluid_slot"]
            locks = [ghost_lock(widget) for widget in real]
            if fault == "duplicate_after_pickup" and state["cursor"] is not None:
                locks[0] = copy.deepcopy(real[0])
                locks[0]["widgetId"] = "new-actual-after-pickup"
            elif fault == "phantom_after_pickup" and state["cursor"] is not None:
                real[0]["fluidSlot"]["phantom"]["value"] = True
            result["widgets"] = locks + result["widgets"] if reverse else result["widgets"] + locks
        return result
    monkeypatch.setattr(client, "call", call)


@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("partial", [False, True])
async def test_registered_output_hatch_with_lock_completes_exact_fill_and_drain_once(native_bridge, monkeypatch, reverse, partial):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch, partial=partial)
    add_locks(client, state, monkeypatch, reverse=reverse)
    result = await fluid.native.invoke(client, fluid.declaration(partial=partial))
    assert result["status"] == "completed", result
    assert result["acceptance"]["completeRecovery"] is True
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"]
    assert state["cursor"] is None and all("fluid" not in tank for tank in state["tanks"].values())
    if partial:
        receipt = result["acceptance"]["fluidResources"]["containerTransfers"][1]
        assert receipt["amount"] == receipt["returnedSemantics"]["fluid"]["amount"] == 144


@pytest.mark.parametrize("reverse", [False, True])
async def test_shared_named_roles_keep_exact_binding_and_both_tanks_with_locks(single_machine, monkeypatch, reverse):
    client, state = single_machine
    shared.transport(client, state, monkeypatch, partial=True)
    add_locks(client, state, monkeypatch, reverse=reverse)
    result = await shared.multi.native.invoke(client, shared.declaration(partial=True))
    assert result["status"] == "completed", result
    assert state["fluid_activations"] == ["input", "output"]
    assert state["named_state"] == {"input": (None, 0), "output": (None, 0)}
    assert result["acceptance"]["completeRecovery"] is True and state["cursor"] is None


@pytest.mark.parametrize("fault", ["duplicate_after_pickup", "phantom_after_pickup"])
async def test_fresh_before_activation_qualifications_reject_drift_without_activate_or_replay(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch)
    add_locks(client, state, monkeypatch, fault=fault)
    result = await fluid.native.invoke(client, fluid.declaration())
    assert result["status"] != "completed" and result["reason"] == "ASSERTION_FAILED", result
    assert state["fluid_actions"] == []
    pickups = [call for call in state["calls"] if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 2]
    assert len(pickups) == 1


async def test_mixed_controls_preserve_unknown_native_activation_receipt_without_replay(native_bridge, monkeypatch):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch, fault="unknown_activate")
    add_locks(client, state, monkeypatch)
    result = await fluid.native.invoke(client, fluid.declaration())
    assert result["status"] != "completed" and result["lastActionReceipt"]["operation"] == "gui.widget_action", result
    assert state["fluid_actions"] == ["fluid_input"] and result["lastActionReceipt"]["outcomeUnknown"] is True
