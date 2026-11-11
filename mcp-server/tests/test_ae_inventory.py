"""AE hand preparation uses real guarded inventory moves, without recipe execution."""
import time

import pytest

from minecraft_codex_bridge.ae_inventory import AeHandPreparation
from minecraft_codex_bridge.ae_tasks import AeTask
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import invoke_control
from test_recipe_tasks import native_bridge, stack
from test_tagged_recipe_context import tagged_transport
from test_ae_goals import orders, RESUME


COMMON = {"expectedSessionId": "session", "expectedWorld": "test-world", "dimension": 180}


async def task_for(client):
    task = AeTask(client, COMMON, invoke_control)
    task.health = await client.invoke("bridge.health", {})
    return task


async def test_full_hotbar_moves_one_whole_stack_once_and_retains_it(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    task = await task_for(client)
    task.record = {}
    await task.empty_hand()
    assert state["player_items"][0] is None
    assert state["player_items"][10] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    report, = task.hand_preparations
    assert report["status"] == "completed" and report["retentionConfirmed"] and report["cursorEmpty"]
    move, = report["moves"]
    assert move["movementConfirmed"] and move["retentionConfirmed"] and move["status"] == "retained"
    assert task.record["handPreparation"] == [report]
    clicks = [c["args"]["widgetId"] for c in state["calls"] if c["operation"] == "gui.widget_action"
              and c["args"]["widgetId"].startswith("creative:slot:")]
    assert clicks == ["creative:slot:0", "creative:slot:10"]
    assert not any(c["operation"] in {"dev.teleport", "chat.send", "player.interact", "inventory.supply"}
                   for c in state["calls"])
    assert state["open"] is None


async def test_available_empty_hand_does_not_open_or_move_inventory(native_bridge):
    client, state = native_bridge
    task = await task_for(client)
    await task.empty_hand()
    assert task.hand_preparations == []
    assert not any(c["operation"] in {"gui.open_inventory", "gui.widget_action", "gui.slot_click"}
                   for c in state["calls"])


@pytest.mark.parametrize("missing", ["space", "mapping", "capability"])
async def test_preparation_refuses_before_any_slot_pickup_without_native_prerequisites(native_bridge, missing):
    client, state = native_bridge
    state["full_hotbar"] = True
    if missing == "space":
        state["storage_full"] = True
    elif missing == "mapping":
        state["creative_mapping"] = False
    else:
        state["caps"].remove("gui.open_inventory")
    task = await task_for(client)
    with pytest.raises(BridgeError):
        await task.empty_hand()
    assert task.hand_preparations[0]["status"] == "failed"
    assert not any(c["operation"] == "gui.slot_click" or c["operation"] == "gui.widget_action"
                   and c["args"]["widgetId"].startswith("creative:slot:") for c in state["calls"])


async def test_uncertain_pickup_retains_exact_pending_move_without_replay(native_bridge):
    client, state = native_bridge
    state["full_hotbar"], state["creative_error"] = True, "disconnect"
    task = await task_for(client)
    task.record = {}
    with pytest.raises(BridgeError):
        await task.empty_hand()
    report, = task.record["handPreparation"]
    move, = report["moves"]
    assert move["movementDispatched"] and move["outcomeUnknown"] and not move["movementConfirmed"]
    assert report["replayAllowed"] is False and not report["retentionConfirmed"]
    assert len([c for c in state["calls"] if c["operation"] == "gui.widget_action"
                and c["args"]["widgetId"].startswith("creative:slot:")]) == 1
    assert state["cursor"] is not None


@pytest.mark.parametrize("creative,flying", [(False, False), (False, True), (True, False), (True, True)])
async def test_hand_helper_inherits_deadline_and_preserves_actual_posture(native_bridge, creative, flying):
    client, state = native_bridge
    state.update(creative=creative, flying=flying)
    parent = await task_for(client)
    parent.deadline = time.monotonic() + .2
    snapshot = await client.invoke("client.snapshot", {})
    helper = AeHandPreparation(parent, snapshot)
    assert helper.deadline == parent.deadline and helper.actual_survival is (not creative)
    assert helper.require_flight(snapshot) == pytest.approx(1.62)
    snapshot["player"]["serverFlight"]["creative"] = not creative
    with pytest.raises(BridgeError):
        helper.require_flight(snapshot)


@pytest.mark.parametrize("fault", [None, "server_nbt", "missing_nbt", "partial"])
async def test_unrelated_tagged_context_is_verified_before_and_after_native_move(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    state["full_hotbar"] = True
    await client.invoke("inventory.inspect", {})
    tagged_transport(client, state, monkeypatch)
    state["baubles_fault"] = fault
    task = await task_for(client)
    if fault:
        with pytest.raises(BridgeError):
            await task.empty_hand()
        assert state["context_actions"] == []
        assert not task.hand_preparations[0]["retentionConfirmed"]
    else:
        await task.empty_hand()
        report, = task.hand_preparations
        assert report["taggedContextFullNbtVerified"] and report["retentionConfirmed"]
        assert report["moves"][0]["hotbarIndex"] == 1  # Tagged blueprint in 0 remains untouched.
        assert state["player_items"][0]["hasTag"] and state["player_items"][40]["hasTag"]
        assert state["baubles_reads"] >= 6 and state["cursor"] is None


async def test_order_resume_keeps_preparation_receipt_without_replaying_it(orders):
    state, run = orders
    state["native_state"] = "running"
    assert (await run())["resumable"]
    record, = state["records"].values()
    preparation = [{"status": "completed", "moves": [{"hotbarIndex": 0, "storageIndex": 20,
        "movementConfirmed": True, "retentionConfirmed": True}], "replayAllowed": False}]
    record["handPreparation"] = preparation
    state["native_state"] = "crafted"
    result = await run(RESUME)
    assert result["status"] == "completed" and result["handPreparation"] == preparation
    assert result["handPreparation"] is not preparation
    assert state["native_calls"].count("start") == 1 and state["native_calls"].count("take") == 1
