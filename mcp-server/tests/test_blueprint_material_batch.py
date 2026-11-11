"""All native grants must be observed together before actual hotbar binding."""

import copy
import json
import time

import httpx
import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.workflows import run_workflow


@pytest.fixture
async def supply_batch():
    arguments = native.declaration(inventoryPreparation="clear", supportMode="command", generateMaterials=True, timeout_ms=1000)
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    materials = {0: {"item": native.BLUEPRINT.copy(), "count": 1}, 1: {"item": native.CONTROLLER_ITEM.copy(), "count": 1},
        **{slot: {"item": native.PORT_ITEMS[role].copy(), "count": 1} for role, slot in native.PORT_SLOTS.items()}}
    state = {"calls": [], "items": {index: None for index in range(41)}, "grants": 0, "afterReads": 0,
        "delayedFirst": False, "pending": None, "fault": None, "context": True}

    def snapshot():
        state["server_samples"] = state.get("server_samples", 0) + 1
        return {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": native.BACKGROUND,
            "serverControl": {"available": True, "source": "integrated_server_tick", "sessionId": "session",
                "worldName": "new-world", "worldFolder": "new-world", "dimension": 0,
                "sampleId": state["server_samples"], "ageMillis": 0, "playerId": "fixture-player-uuid",
                "openContainer": {"isInventoryContainer": True, "windowId": 0},
                "clientContainer": {"isInventoryContainer": True, "windowId": 0}},
            "world": {"worldName": "new-world", "dimension": 0}, "player": {"name": "TestPlayer", "health": 20}}

    def put(item):
        index = next(index for index in range(36) if state["items"][index] is None)
        state["items"][index] = item

    async def respond(request):
        body = json.loads(request.content)
        operation, args = body["operation"], body["args"]
        state["calls"].append(copy.deepcopy(body))
        if operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "profile": "developer", "background": native.BACKGROUND,
                "capabilities": ["client.snapshot", "client.background", "inventory.inspect", "chat.send", "gui.close"]}
            if state["context"]:
                data["contextRpc"] = {"version": 1, "reads": ["bridge.health", "inventory.inspect"], "actions": []}
        elif operation == "client.snapshot":
            data = snapshot()
        elif operation == "gui.close":
            data = {"status": "submitted"}
        elif operation == "inventory.inspect":
            if state["grants"] == 6:
                state["afterReads"] += 1
                if state["pending"] is not None and state["afterReads"] == 2:
                    put(state["pending"])
                    state["pending"] = None
            data = {"slots": [{"index": index, "item": copy.deepcopy(item)} for index, item in state["items"].items()],
                "totalSlots": 41, "truncated": False, "cursorItem": None}
            if state["grants"] == 6:
                fault = state["fault"]
                if fault == "partial":
                    data["truncated"] = True
                elif fault == "armor":
                    data["slots"].pop()
                    data["totalSlots"] = 40
                elif fault == "cursor":
                    data["cursorItem"] = native.stack(native.BLUEPRINT)
                elif fault == "cursor_missing":
                    data.pop("cursorItem")
                elif fault == "boolean_count":
                    data["slots"][0]["item"]["count"] = True
                elif fault == "tagged":
                    data["slots"][0]["item"]["hasTag"] = True
                elif fault == "extra":
                    data["slots"][40]["item"] = native.stack({"registryName": "minecraft:stone", "metadata": 0})
                elif fault == "duplicate":
                    data["slots"][7]["item"] = copy.deepcopy(data["slots"][0]["item"])
                elif fault == "missing":
                    data["slots"][0]["item"] = None
                elif fault == "storage":
                    data["slots"][9]["item"], data["slots"][0]["item"] = data["slots"][0]["item"], None
        elif operation == "chat.send":
            words = args["message"].split()
            if words[0] == "/clear":
                assert words == ["/clear", "TestPlayer"]
                state["items"] = {index: None for index in range(41)}
            else:
                assert words[:2] == ["/give", "TestPlayer"]
                state["grants"] += 1
                item = native.stack({"registryName": words[2], "metadata": int(words[4])}, int(words[3]))
                if state["delayedFirst"] and state["grants"] == 1:
                    state["pending"] = item
                else:
                    put(item)
            data = {"status": "submitted"}
        else:
            raise AssertionError(operation)
        envelope = {"requestId": body["requestId"], "ok": True, "data": data}
        if "includeContext" in body:
            assert state["context"] and operation in ("bridge.health", "inventory.inspect")
            evidence = snapshot()
            if state["grants"] == 6 and operation == "inventory.inspect" and state["fault"] == "context_dimension":
                evidence["world"]["dimension"] = 1
            envelope["context"] = {"version": 1, "operation": operation, "phase": "after", "snapshot": evidence}
            if state["grants"] == 6 and operation == "inventory.inspect" and state["fault"] == "context_missing":
                envelope.pop("context")
        return httpx.Response(200, json=envelope)

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "batch-test"}),
        transport=httpx.MockTransport(respond))
    task = _ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    task.capabilities = ["client.snapshot", "client.background", "inventory.inspect", "chat.send", "gui.close"]
    task.approach_health = {"contextRpc": {"version": 1, "reads": ["bridge.health", "inventory.inspect"], "actions": []}}
    task.materials, task.controller_slot = materials, 1
    try:
        yield task, state
    finally:
        await client.aclose()


def give_calls(state):
    return [call for call in state["calls"] if call["operation"] == "chat.send" and call["args"]["message"].startswith("/give ")]


async def test_six_native_grants_use_one_complete_batch_read_and_keep_each_fresh_write_guard(supply_batch):
    task, state = supply_batch
    await task.prepare_cleared_inventory()
    assert state["grants"] == task.material_grants_dispatched == 6 and state["afterReads"] == 1
    assert task.inventory_clear_confirmed and len(task.materials) == 6 and 0 <= task.empty_slot <= 8
    grants = [index for index, call in enumerate(state["calls"]) if call in give_calls(state)]
    assert len(grants) == 6
    assert all(state["calls"][index - 1]["operation"] == "client.snapshot" for index in grants)
    assert not any(call["operation"] == "inventory.inspect" for call in state["calls"][grants[0]:grants[-1]])
    assert task.phases[-1]["completeBatchVerified"] and task.phases[-1]["completeSlotCoverage"] == 41
    assert state["calls"][-1]["includeContext"]["requireBackground"] is True


async def test_last_grant_pickup_does_not_prove_earlier_native_drops_were_received(supply_batch):
    task, state = supply_batch
    state["delayedFirst"] = True
    await task.prepare_cleared_inventory()
    assert state["afterReads"] == task.phases[-1]["attempts"] == 2
    assert len(give_calls(state)) == 6 and state["pending"] is None
    assert task.args["blueprintHotbarSlot"] == 5 and task.controller_slot == 4


@pytest.mark.parametrize("fault,reason", [("partial", "inventory_coverage_incomplete"),
    ("armor", "supplied_inventory_coverage_unavailable"), ("cursor", "carried_stack_not_empty"),
    ("cursor_missing", "carried_stack_not_empty"), ("context_dimension", "dimension_mismatch")])
async def test_unknown_batch_coverage_cursor_or_context_stops_without_replaying_grants(supply_batch, fault, reason):
    task, state = supply_batch
    state["fault"] = fault
    with pytest.raises(BridgeError) as caught:
        await task.prepare_cleared_inventory()
    assert caught.value.details["reason"] == reason
    assert len(give_calls(state)) == task.material_grants_dispatched == 6 and state["afterReads"] == 1
    assert task.inventory_clear_confirmed and task.phases[-1]["status"] == "failed"


async def test_missing_advertised_batch_context_does_not_add_fallback_snapshot_or_repeat_grants(supply_batch):
    task, state = supply_batch
    state["fault"] = "context_missing"
    with pytest.raises(BridgeError) as caught:
        await task.prepare_cleared_inventory()
    assert caught.value.code == "MALFORMED_RESPONSE"
    assert len(give_calls(state)) == 6 and state["calls"][-1]["operation"] == "inventory.inspect"


@pytest.mark.parametrize("fault", ["boolean_count", "tagged", "extra", "duplicate", "missing", "storage"])
async def test_incomplete_or_wrong_native_batch_times_out_with_grant_receipts_and_no_replay(supply_batch, fault):
    task, state = supply_batch
    state["fault"] = fault
    with pytest.raises(BridgeError):
        await task.prepare_cleared_inventory()
    assert len(give_calls(state)) == task.material_grants_dispatched == 6
    assert task.inventory_clear_confirmed and task.phases[-1]["status"] == "failed"
    supply = next(phase for phase in task.phases if phase["phase"] == "supply_build_materials")
    assert supply["acknowledgedActions"] == supply["dispatchedActions"] == 6
    assert task.last_action["operation"] == "chat.send" and task.last_action["dispatched"]


async def test_legacy_batch_wait_retains_fresh_snapshot_guard_without_advertised_context(supply_batch):
    task, state = supply_batch
    state["context"], task.approach_health = False, {}
    await task.prepare_cleared_inventory()
    assert len(give_calls(state)) == 6 and state["afterReads"] == 1
    assert state["calls"][-1]["operation"] == "client.snapshot" and task.phases[-1]["completeBatchVerified"]
