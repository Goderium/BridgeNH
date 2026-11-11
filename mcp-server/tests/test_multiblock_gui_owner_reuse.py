"""Fresh owner/window reuse while preserving both native clicks and fixed full AFTER."""
import copy
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.bridge import BridgeError, _ContextData
from minecraft_codex_bridge.multi_recipe_tasks import _MultiRecipeTask
from minecraft_codex_bridge.workflows import run_workflow
from minecraft_codex_bridge.whole_stack_transfer import OPERATION
import test_gui_reuse_actual_reach as reuse
import test_native_whole_stack_transfer as transfer

native_bridge = reuse.native_bridge


async def setup(client, state, monkeypatch):
    base, _, _ = await reuse.reuse_task(client, state, monkeypatch, distance=2.4)
    _, snapshot = await base.read_with_snapshot("gui.inspect")
    task = _MultiRecipeTask(client, copy.deepcopy(base.args), run_workflow)
    task.dimension, task.capabilities = base.dimension, base.capabilities
    task.approach_health = copy.deepcopy(base.approach_health)
    task.approach_health.update({key: copy.deepcopy(value) for key, value in transfer.setup()[0].approach_health.items() if key != "contextRpc"})
    task.approach_health["contextRpc"]["actions"].append(OPERATION)
    task.approach_health["contextRpc"]["reads"].append("world.inspect_many")
    task.registered_multiblock_verified = True
    task.survival_control_player_id = base.survival_control_player_id
    task.args["output"] = copy.deepcopy(task.args["input"])
    task.args["input"] = copy.deepcopy(task.args["machine"])
    owner = {**task.position("output"), "registryName": "gregtech:gt.blockmachines",
        "metaTileId": 77, "metaClass": "example.PublicMultiblock"}
    task.multi_output_owner = copy.deepcopy(owner)
    task.input_inventory_owner = None
    products = [{"registryName": "minecraft:redstone", "metadata": 0, "count": 1, "hasTag": False},
        {"registryName": "minecraft:gold_ingot", "metadata": 0, "count": 1, "hasTag": False}]
    task.recipe = SimpleNamespace(output_items=products)
    machine = [{"index": index, "item": copy.deepcopy(item)} for index, item in enumerate(products)]
    player = {"cursorItem": None, "selectedHotbarSlot": snapshot["player"]["selectedHotbarSlot"], "totalSlots": 41,
        "truncated": False, "slots": [{"index": index, "item": None} for index in range(41)]}
    player["slots"][20]["item"] = {"registryName": "minecraft:stone", "metadata": 3, "count": 5,
        "hasTag": True, "nbtIdentity": "{preserved:1b}"}
    events = []

    def gui():
        rows = [{"slot": index, "nativeContainerSlotId": index, "machineInventoryIndex": index,
            "machineInventoryBindingVerified": True, "playerInventoryBindingVerified": False,
            "machineInventoryBinding": "public_native_slot_actual_machine_handler_identity",
            "machineInventoryOwner": copy.deepcopy(owner), "item": copy.deepcopy(row["item"])} for index, row in enumerate(machine)]
        rows.extend({"slot": 2 + offset, "nativeContainerSlotId": 2 + offset, "playerInventoryIndex": index,
            "playerInventoryBindingVerified": True, "machineInventoryBindingVerified": False,
            "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot", "inventorySource": "current_player_inventory",
            "item": copy.deepcopy(player["slots"][index]["item"])} for offset, index in enumerate((9, 10)))
        data = {"screenId": "controller-screen", "windowId": 7, "slots": rows, "totalSlots": len(rows), "truncated": False,
            "wholeStackTransfer": {**task.approach_health["wholeStackTransfer"], "supported": True, "owner": copy.deepcopy(owner)}}
        return _ContextData(data, {"version": 1, "operation": "gui.inspect", "phase": "after", "snapshot": copy.deepcopy(snapshot)})

    task.opened_gui = {"target": "output", "screenId": "controller-screen", "windowId": 7, "side": 2,
        "nativeOwner": copy.deepcopy(owner), "nativeBindings": task.native_gui_bindings(gui(), owner)}
    original = task.call

    async def call(operation, args):
        if operation == "inventory.inspect":
            events.append("player_read")
            return copy.deepcopy(player)
        if operation == OPERATION:
            events.append("native_before")
            before = copy.deepcopy(player)
            source = machine[args["machineInventoryIndex"]]
            destination = player["slots"][args["playerInventoryIndex"]]
            assert source["item"] == args["expectedItem"] and destination["item"] is None
            player["cursorItem"], source["item"] = source["item"], None
            events.append("real_pickup_server_ack")
            assert source["item"] is None and player["cursorItem"] == args["expectedItem"]
            destination["item"], player["cursorItem"] = player["cursorItem"], None
            events.append("real_place_server_ack")
            assert player["cursorItem"] is None and destination["item"] == args["expectedItem"]
            assert all(before["slots"][index] == player["slots"][index] for index in range(41) if index != args["playerInventoryIndex"])
            result = transfer.receipt(args)
            result.update(screenId="controller-screen", windowId=7, count=1, allMachineSlots=2,
                selectedHotbarSlot=player["selectedHotbarSlot"])
            return _ContextData(result, {"version": 1, "operation": OPERATION, "phase": "before", "snapshot": copy.deepcopy(snapshot)})
        return await original(operation, args)

    async def read(operation, *args, **kwargs):
        assert operation == "gui.inspect"
        events.append("fresh_gui")
        value = gui()
        return value, value.context["snapshot"]

    async def owner_phase(positions):
        assert positions == [task.position("output")]
        events.append("full_owner_player_after")
        observed = {**owner, "source": "integrated_server", "metadata": 0,
            "gregtech": {key: owner[key] for key in ("metaTileId", "metaClass")},
            "inventory": {"totalSlots": 2, "truncated": False, "slots": copy.deepcopy(machine)}}
        return [observed], copy.deepcopy(player)

    async def snapshot_read(): return copy.deepcopy(snapshot)
    async def validate_player(value):
        assert len(value["slots"]) == 41 and value["slots"][20]["item"]["nbtIdentity"] == "{preserved:1b}"
    async def no_workflow(*args, **kwargs): raise AssertionError("The same supported actual output window needs no reopen or legacy clicks")
    task.call, task.read_with_snapshot, task.read_owner_phase = call, read, owner_phase
    task.snapshot, task.workflow = snapshot_read, no_workflow
    task.validate_single_tanks = lambda value: None
    task.fluid_resources = SimpleNamespace(player_indices={20}, validate_player=validate_player)
    return task, gui, products, player, machine, events


async def test_two_products_reuse_exact_owner_window_with_fresh_native_proofs_and_all41_after(native_bridge, monkeypatch):
    client, state = native_bridge
    task, _, products, player, machine, events = await setup(client, state, monkeypatch)
    for ordinal, item in enumerate(products):
        await task.retrieve_product(item, 1, ordinal)
    assert len(state["planner_calls"]) == 2 and all(args["currentOnly"] is True and args["useNormalReach"] is True for args in state["planner_calls"])
    assert all(args["targetIdentity"] == {key: task.multi_output_owner[key] for key in ("registryName", "metaTileId", "metaClass")} for args in state["planner_calls"])
    assert events.count("fresh_gui") == events.count("native_before") == events.count("real_pickup_server_ack") == events.count("real_place_server_ack") == 2
    assert events.count("full_owner_player_after") == 4 and len(task.retrievals) == 2
    assert len(player["slots"]) == 41 and player["cursorItem"] is None and all(row["item"] is None for row in machine)
    assert player["slots"][20]["item"]["nbtIdentity"] == "{preserved:1b}"
    assert [phase["phase"] for phase in task.phases if phase["phase"].startswith("reuse_")] == ["reuse_output", "reuse_output"]


async def test_successful_normal_open_records_exact_owner_and_unique_native_mapping(native_bridge, monkeypatch):
    client, state = native_bridge
    task, gui, _, _, _, events = await setup(client, state, monkeypatch)
    task.opened_gui = None
    workflows = []
    async def approach(name):
        assert name == "output"
        return 2, [], False
    async def workflow(phase, steps):
        workflows.extend(copy.deepcopy(steps))
        task.phases.append({"phase": phase, "status": "completed"})
        return {"steps": [{"id": "gui", "observation": {"values": copy.deepcopy(gui())}}]}
    task.approach_plan, task.workflow = approach, workflow
    result = await task.open_gui("output")
    assert sum(step.get("tool") == "mcb_interact" for step in workflows) == 1
    assert task.opened_gui["nativeOwner"] == task.multi_output_owner
    assert task.opened_gui["nativeBindings"] == task.native_gui_bindings(result, task.multi_output_owner)
    assert events == ["player_read"] and not state["planner_calls"]


@pytest.mark.parametrize("fault", ["window_owner", "slot_owner", "bool_owner", "duplicate_machine", "duplicate_player", "native_id", "mapping", "context"])
async def test_owner_binding_or_guard_change_refuses_before_any_pickup(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task, gui, _, player, _, events = await setup(client, state, monkeypatch)
    current = gui()
    if fault == "window_owner": current["wholeStackTransfer"]["owner"]["metaTileId"] += 1
    elif fault == "slot_owner": current["slots"][0]["machineInventoryOwner"]["x"] += 1
    elif fault == "bool_owner": current["wholeStackTransfer"]["owner"]["metaTileId"] = True
    elif fault == "duplicate_machine": current["slots"][1]["machineInventoryIndex"] = 0
    elif fault == "duplicate_player": current["slots"][3]["playerInventoryIndex"] = 9
    elif fault == "native_id": current["slots"][0]["nativeContainerSlotId"] = 12
    elif fault == "mapping": current["slots"][1]["machineInventoryIndex"] = 3
    else: current.context["phase"] = "before"
    async def read(*args, **kwargs): return current, current.context["snapshot"]
    task.read_with_snapshot = read
    with pytest.raises(BridgeError): await task.reuse_gui("output")
    assert not state["planner_calls"] and not events and player["cursorItem"] is None


@pytest.mark.parametrize("absence", ["unsupported_window", "old_window", "old_capability", "no_owner", "new_screen", "new_window"])
async def test_missing_supported_window_owner_or_open_binding_selects_old_entry_before_mutation(native_bridge, monkeypatch, absence):
    client, state = native_bridge
    task, gui, _, _, _, events = await setup(client, state, monkeypatch)
    current = gui()
    if absence == "unsupported_window": current["wholeStackTransfer"]["supported"] = False
    elif absence == "old_window": current.pop("wholeStackTransfer")
    elif absence == "old_capability": task.approach_health.pop("wholeStackTransfer")
    elif absence == "no_owner": task.opened_gui = None
    elif absence == "new_screen": current["screenId"] = "another-real-screen"
    else: current["windowId"] = 8
    async def read(*args, **kwargs): return current, current.context["snapshot"]
    task.read_with_snapshot = read
    assert await task.reuse_gui("output") is None
    assert not state["planner_calls"] and not events
