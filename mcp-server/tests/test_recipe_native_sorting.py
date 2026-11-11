"""Native input compaction may change stacks without changing public slot ownership."""

import copy

import pytest

import test_recipe_input_discovery as discovery
import test_recipe_tasks as native


native_bridge = native.native_bridge


def sorting_transport(client, state, monkeypatch, *, owner_changed=False, stale_gui=False):
    discovery.public_bindings(state)
    state.update(input_circuit=0, circuit=1, native_compacted=False, catalyst_destination_observed=False)
    original = client.call

    def machine_items():
        items = {index: None for index in range(4)}
        if state["input_circuit"]:
            items[0 if state["native_compacted"] else 1] = native.stack(native.CATALYST, state["input_circuit"])
        if state["input_material"]:
            items[1 if state["native_compacted"] else 0] = native.stack(native.MATERIAL, state["input_material"])
        return items

    async def sorted_input(operation, args, **options):
        cursor = copy.deepcopy(state["cursor"])
        before_material, before_catalyst = state["input_material"], state["input_circuit"]
        value = await original(operation, args, **options)
        if operation == "gui.slot_click" and args["slot"] in range(4) and cursor is not None:
            amount = cursor["count"] if args["button"] == 0 else 1
            material = cursor["registryName"] == native.MATERIAL["registryName"]
            state["input_material"] = before_material + (amount if material else 0)
            state["input_circuit"] = before_catalyst + (0 if material else amount)
        if operation == "world.inspect" and all(args[axis] == native.INPUT[axis] for axis in ("x", "y", "z")):
            indexed = machine_items()
            for slot in value["inventory"]["slots"]:
                slot["item"] = indexed[slot["index"]]
            if owner_changed and state["native_compacted"]:
                value["gregtech"]["metaTileId"] += 1
        if operation == "gui.inspect" and state["open"] == "input":
            indexed = machine_items()
            if stale_gui and state["native_compacted"] and not state.get("compaction_gui_observed"):
                indexed[0], indexed[1] = indexed[1], indexed[0]
                state["compaction_gui_observed"] = True
            for slot in value.get("slots", []):
                if slot["slot"] in range(4):
                    slot["item"] = indexed[slot["slot"]]
            if not state["native_compacted"] and indexed[1] is not None:
                state["catalyst_destination_observed"] = True
        if operation == "inventory.inspect" and state["catalyst_destination_observed"] and state["cursor"] is None:
            state["native_compacted"] = True
        return value

    monkeypatch.setattr(client, "call", sorted_input)


@pytest.mark.parametrize("executions", [1, 2])
@pytest.mark.parametrize("stale_gui", [False, True])
async def test_automatic_material_destination_is_rediscovered_after_native_catalyst_compaction(native_bridge, monkeypatch, executions, stale_gui):
    client, state = native_bridge
    sorting_transport(client, state, monkeypatch, stale_gui=stale_gui)
    result = await native.invoke(client, discovery.automatic_arguments(catalystGuiSlot=1, executions=executions))
    assert result["status"] == "completed", result
    assert state["native_compacted"] is True
    assert result["inputGuiSlot"] == 1 and result["inputGuiBinding"]["inventoryIndex"] == 1
    assert result["catalystGuiSlot"] == 1
    rediscovery = [phase for phase in result["phases"] if phase["phase"] == "input_gui_rediscovery"]
    assert len(rediscovery) == 1 and rediscovery[0]["previousGuiSlot"] == rediscovery[0]["previousInventoryIndex"] == 0
    assert result["acceptance"]["consumed"] == 8 * executions and result["acceptance"]["catalystRetained"] is True
    assert state["input_circuit"] == 1 and state["input_material"] == 0
    assert state["material"] == 32 - 8 * executions and state["circuit"] == 0 and state["cursor"] is None
    assert result["materialGrantsDispatched"] == 0 and not any(call["operation"] == "chat.send" for call in state["calls"])
    clicks = [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"]
    assert sum(click["slot"] == 81 for click in clicks) == 1
    assert sum(click["slot"] == 80 and click["button"] == 0 for click in clicks) == executions * 2
    assert sum(click["slot"] == 1 for click in clicks) == 8 * executions + 1
    assert not any(click["slot"] == 0 for click in clicks)


async def test_explicit_material_destination_is_not_migrated_after_native_compaction(native_bridge, monkeypatch):
    client, state = native_bridge
    sorting_transport(client, state, monkeypatch)
    result = await native.invoke(client, native.declaration(inputGuiSlot=0, catalystGuiSlot=1))
    assert result["status"] == "failed" and result["reason"] == "declared_destination_not_empty", result
    assert result["inputGuiSlot"] == 0 and result["inputGuiBinding"] is None
    assert state["input_circuit"] == 1 and state["input_material"] == 0 and state["material"] == 32
    assert state["cursor"] is None
    assert not any(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 80 for call in state["calls"])
    assert not any(call["operation"] == "gui.widget_action" for call in state["calls"])


async def test_fresh_server_owner_change_after_native_compaction_blocks_material_pickup(native_bridge, monkeypatch):
    client, state = native_bridge
    sorting_transport(client, state, monkeypatch, owner_changed=True)
    result = await native.invoke(client, discovery.automatic_arguments(catalystGuiSlot=1))
    assert result["status"] == "failed" and result["reason"] == "input_gui_owner_changed", result
    assert state["input_circuit"] == 1 and state["input_material"] == 0 and state["material"] == 32
    assert state["cursor"] is None
    assert not any(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 80 for call in state["calls"])
    assert not any(call["operation"] == "gui.widget_action" for call in state["calls"])
