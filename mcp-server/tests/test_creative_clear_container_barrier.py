"""Creative preparation must acknowledge real container closure before /clear."""

import copy
import importlib.util
import os
from pathlib import Path
import sys
import time

import pytest

import minecraft_codex_bridge

if os.environ.get("MCB_CLEAR_BARRIER_STAGE"):
    source = Path(os.environ["MCB_CLEAR_BARRIER_STAGE"])
    name = "minecraft_codex_bridge.recipe_tasks"
    spec = importlib.util.spec_from_file_location(name, source)
    staged = importlib.util.module_from_spec(spec)
    sys.modules[name] = staged
    spec.loader.exec_module(staged)
    minecraft_codex_bridge.recipe_tasks = staged

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, run_recipe_once
from minecraft_codex_bridge.workflows import run_workflow
from test_recipe_tasks import MATERIAL, declaration, native_bridge, stack, writes


ACTUAL_UUID = "94ca5cb4-942a-4f50-8848-bcc68c453758"


def prepare(client, state, monkeypatch, *, fault=None):
    state.update(open="input", window=7, control_sample=0, close_seen=False, close_samples=0,
        server_inventory=False, server_window=7, controls_seen=[], inventory_after_give=0)
    task = _RecipeTask(client, declaration(generateMaterials=True), run_workflow)
    task.capabilities = state["caps"]
    original = client.call

    async def observed(operation, args, **kwargs):
        if operation == "chat.send" and args["message"].startswith(("/clear", "/give")):
            assert state["server_inventory"] is True, "Commands must wait for the real server to close its old container"
        result = await original(operation, args, **kwargs)
        if fault == "unknown_clear" and operation == "chat.send" and args["message"].startswith("/clear"):
            raise BridgeError("connection_lost", "The submitted clear did not retain a completion receipt.", outcome_unknown=True)
        if operation == "gui.close":
            state["close_seen"] = True
        elif operation == "client.snapshot":
            if fault != "same_sample":
                state["control_sample"] += 1
            else:
                state["control_sample"] = 7
            if state["close_seen"]:
                state["close_samples"] += 1
                if fault != "server_open" and state["close_samples"] >= 3:
                    state["server_inventory"], state["server_window"] = True, 0
            client_container = {"windowId": state["window"], "isInventoryContainer": state["open"] is None}
            control = {"available": True, "source": "integrated_server_tick", "sessionId": "session",
                "worldName": "test-world", "dimension": 180, "sampleId": state["control_sample"],
                "ageMillis": 0, "playerId": ACTUAL_UUID,
                "openContainer": {"windowId": state["server_window"], "isInventoryContainer": state["server_inventory"]},
                "clientContainer": client_container, "selectionSynchronized": True, "selectedHotbarSlot": 0}
            if fault == "stale_control": control["ageMillis"] = 1001
            if state["close_seen"]:
                if fault == "client_missing": control.pop("clientContainer")
                elif fault == "client_open": control["clientContainer"]["isInventoryContainer"] = False
                elif fault == "window_mismatch": control["clientContainer"]["windowId"] = 7
                elif fault == "different_player": control["playerId"] = "another-actual-uuid"
            if fault != "control_missing":
                result["serverControl"] = control
            else:
                result.pop("serverControl", None)
            state["controls_seen"].append(copy.deepcopy(control))
        elif operation == "inventory.inspect":
            result["slots"].extend({"index": index, "item": None} for index in range(36, 41))
            result["totalSlots"] = 41
            if fault == "partial_inventory": result["truncated"] = True
            if fault == "ghost_after_clear" and state["material"] == 0:
                result["slots"][9]["item"] = stack(MATERIAL, 1)
            if state["generated"] and fault == "delayed_pickup":
                state["inventory_after_give"] += 1
                if state["inventory_after_give"] <= 2:
                    for row in result["slots"]:
                        row["item"] = None
        return result

    monkeypatch.setattr(client, "call", observed)
    return task


def command_trace(state):
    return [row["args"]["message"] for row in state["calls"] if row["operation"] == "chat.send"]


async def test_creative_clear_waits_for_actual_close_ack_then_whole_inventory_empty(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch)
    await task.clear_player_inventory()
    assert task.inventory_clear_confirmed is True
    assert task.phases[-1]["clearedSlotCoverage"] == 41
    mutations = writes(state)
    assert [row["operation"] for row in mutations] == ["gui.close", "chat.send"]
    assert command_trace(state) == ["/clear TestPlayer"]
    assert state["close_samples"] >= 3
    ready = next(control for control in state["controls_seen"] if control["openContainer"]["isInventoryContainer"])
    assert ready["sampleId"] > state["controls_seen"][1]["sampleId"]
    assert ready["clientContainer"] == ready["openContainer"] == {"windowId": 0, "isInventoryContainer": True}
    assert task.survival_control_player_id == ACTUAL_UUID
    receipt = task.inventory_clear_receipt
    assert receipt["status"] == "confirmed" and receipt["containerAckConfirmed"] is True
    assert receipt["acknowledgementSampleId"] > receipt["baselineSampleId"]
    assert receipt["serverWindowId"] == receipt["clientWindowId"] == 0
    assert receipt["playerId"] == ACTUAL_UUID and receipt["replayAllowed"] is False
    assert task.phases[-1]["inventoryClearReceipt"] == receipt


async def test_whole_supply_still_waits_actual_pickup_and_never_replays_grant(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch, fault="delayed_pickup")
    await task.clear_player_inventory()
    await task.ensure_materials([(MATERIAL, 8)])
    assert command_trace(state) == ["/clear TestPlayer", "/give TestPlayer minecraft:planks 8 0"]
    assert state["inventory_after_give"] >= 3 and state["material"] == 8
    assert task.material_supply_confirmed is True


@pytest.mark.parametrize("fault", ["control_missing", "stale_control", "same_sample", "server_open",
    "client_missing", "client_open", "window_mismatch", "different_player", "partial_inventory"])
async def test_missing_stale_or_wrong_container_ack_stops_before_clear(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch, fault=fault)
    task.deadline = time.monotonic() + .15
    with pytest.raises((BridgeError, TimeoutError)):
        await task.clear_player_inventory()
    assert command_trace(state) == []
    assert task.inventory_clear_confirmed is False
    if task.inventory_clear_receipt is not None:
        assert task.inventory_clear_receipt["clearConfirmed"] is False
        assert task.inventory_clear_receipt["replayAllowed"] is False
    if fault in ("control_missing", "stale_control", "partial_inventory"):
        assert writes(state) == []
    else:
        assert [row["operation"] for row in writes(state)] == ["gui.close"]


async def test_nonempty_cursor_prevents_close_and_clear(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch)
    state["cursor"] = stack(MATERIAL, 1)
    with pytest.raises(BridgeError):
        await task.clear_player_inventory()
    assert writes(state) == [] and state["cursor"]["count"] == 1


async def test_stale_client_ghost_after_one_clear_never_replays_clear(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch, fault="ghost_after_clear")
    task.deadline = time.monotonic() + .20
    with pytest.raises((BridgeError, TimeoutError)):
        await task.clear_player_inventory()
    assert command_trace(state) == ["/clear TestPlayer"]
    assert task.inventory_clear_confirmed is False
    assert state["server_inventory"] is True and state["material"] == 0
    assert task.inventory_clear_receipt["containerAckConfirmed"] is True
    assert task.inventory_clear_receipt["clearCommandDispatched"] is True
    assert task.inventory_clear_receipt["replayAllowed"] is False


async def test_unknown_clear_receipt_is_reported_without_replay(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch, fault="unknown_clear")
    with pytest.raises(BridgeError):
        await task.clear_player_inventory()
    assert command_trace(state) == ["/clear TestPlayer"]
    assert task.inventory_clear_confirmed is False
    receipt = task.inventory_clear_receipt
    assert receipt["status"] == "outcome_unknown" and receipt["outcomeUnknown"] is True
    assert receipt["clearCommandDispatched"] is True and receipt["clearConfirmed"] is False
    assert receipt["replayAllowed"] is False
    assert task.phases[-1]["inventoryClearReceipt"] == receipt


async def test_compact_high_level_result_retains_clear_ack_and_no_replay_feedback(native_bridge, monkeypatch):
    client, state = native_bridge
    prepare(client, state, monkeypatch)
    import anyio
    report = await run_recipe_once(client, declaration(generateMaterials=True,
        inventoryPreparation="clear", includeDetails=False), anyio.Lock(), runner=run_workflow)
    assert report["status"] == "completed", report
    assert "phases" not in report
    receipt = report["inventoryPreparation"]["clearReceipt"]
    assert receipt["containerAckConfirmed"] is True and receipt["clearConfirmed"] is True
    assert receipt["acknowledgementSampleId"] > receipt["baselineSampleId"]
    assert receipt["replayAllowed"] is False and receipt["outcomeUnknown"] is False


@pytest.mark.parametrize("actual_survival,generation", [(True, True), (False, False)])
async def test_clear_authorization_still_refuses_before_any_read(native_bridge, actual_survival, generation):
    client, state = native_bridge
    task = _RecipeTask(client, declaration(actualSurvival=actual_survival, generateMaterials=generation), run_workflow)
    before = len(state["calls"])
    with pytest.raises(BridgeError) as failure:
        await task.clear_player_inventory()
    assert failure.value.details["reason"] == "inventory_clear_not_authorized"
    assert len(state["calls"]) == before


async def test_generated_close_plan_reuses_survival_rules_once_without_flight_or_teleport(native_bridge, monkeypatch):
    client, state = native_bridge
    task = prepare(client, state, monkeypatch)
    original = task.survival_server_barriers
    recorded = []

    def shared(steps):
        result = original(steps)
        recorded.append(result)
        return result

    monkeypatch.setattr(task, "survival_server_barriers", shared)
    await task.clear_player_inventory()
    assert len(recorded) == 1
    assert [step["tool"] for step in recorded[0] if step["kind"] == "action"] == ["mcb_close_gui"]
    assert any(step.get("condition", {}).get("path") == "serverControl.sampleId" for step in recorded[0])
    assert any(step.get("condition", {}).get("path") == "serverControl.clientContainer.isInventoryContainer" for step in recorded[0])
    assert not any(row["operation"] in ("player.flight", "dev.teleport", "gui.open_inventory") for row in writes(state))
