"""Current-source coordination and native preparation contracts; no live game."""

import asyncio
import time

import anyio
import pytest

import test_construction_tasks as construction
import test_construction_declared_goals as declared
import test_recipe_tasks as recipes
import test_workflows as workflows
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import condition_matches

bridge = workflows.bridge
native_construction = construction.native_construction
native_bridge = recipes.native_bridge


async def test_independent_observe_progresses_while_write_queue_is_held_and_writes_still_wait(bridge):
    adapter, state = bridge
    async with adapter.workflow_lock:
        with anyio.fail_after(.3):
            result = await adapter.call_tool("mcb_observe", {"views": [{"id": "snapshot", "tool": "mcb_snapshot"}]})
        assert not result.isError and result.structuredContent["data"]["nonAtomic"] is True
        pending = asyncio.create_task(adapter.call_tool("mcb_hotbar", {"slot": 2}))
        await asyncio.sleep(.02)
        assert not pending.done() and "player.hotbar" not in workflows.operations(state)
    assert not (await pending).isError
    assert workflows.operations(state).count("player.hotbar") == 1


async def test_large_field_omission_keeps_small_observed_identity_and_long_quantity(bridge):
    adapter, state = bridge
    state["data"]["gui.inspect"] = {"screenId": "live-screen", "amount": "9223372036854775807", "identityNbt": "NBT" * 10000}
    result = await adapter.call_tool("mcb_observe", {"max_return_bytes": 16384,
        "views": [{"id": "gui", "tool": "mcb_gui", "fields": ["screenId", "amount", "identityNbt"]}]})
    data = result.structuredContent["data"]
    observation = data["steps"][0]["observation"]
    assert json_size(data) <= 16384 and observation["omittedFields"] == ["identityNbt"]
    assert observation["values"] == {"screenId": "live-screen", "amount": "9223372036854775807"}
    assert observation["fieldsOmitted"] and not observation["omitted"]


def test_inventory_empty_requires_complete_unique_real_slots_including_armor():
    condition = {"path": "slots", "operator": "inventory_empty", "value": True}
    data = {"slots": [{"index": index, "item": None} for index in range(41)], "totalSlots": 41, "truncated": False}
    assert condition_matches(data, condition)
    data["slots"][-1]["item"] = {"registryName": "minecraft:helmet", "count": 1, "hasTag": True}
    assert not condition_matches(data, condition)
    data["truncated"] = True
    with pytest.raises(BridgeError, match="complete"):
        condition_matches(data, condition)
    data["truncated"] = False
    data["slots"][-1]["index"] = 0
    with pytest.raises(BridgeError, match="unique"):
        condition_matches(data, condition)


def test_bundled_validator_reuse_keeps_invalid_arguments_rejected():
    first = tool_validator("mcb_hotbar")
    assert first is tool_validator("mcb_hotbar")
    first.validate({"slot": 8})
    assert not first.is_valid({"slot": True}) and not first.is_valid({"slot": 9})


@pytest.mark.parametrize("height", [3, 40])
async def test_explicit_creative_minimal_clears_declared_supplies_and_uses_one_keep_support(native_construction, height):
    client, state = native_construction
    state["controller"] = {**construction.CONTROLLER, "y": height}
    arguments = declared.declared_setup(state, empty=False, controller=state["controller"])
    state["items"][9] = construction.stack({"registryName": "minecraft:stone", "metadata": 0})
    arguments.pop("inventoryPreparation")
    result = await construction.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["inventoryPreparationMode"] == "minimal" and result["inventoryClearConfirmed"]
    assert result["alreadyMinimalVerified"] is False
    assert result["generatedMaterials"] and result["materialGrantsDispatched"] == 6
    messages = [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"]
    assert messages[0] == "/clear TestPlayer" and messages.count("/clear TestPlayer") == 1
    assert f"/setblock -44 {height - 1} 125 minecraft:stone 0 keep" in messages
    assert f"/setblock -44 {height - 1} 125 minecraft:air 0 replace" in messages
    assert not any(message.startswith("//") for message in messages)
    assert state["inventory_activations"] == [] and result["cleanup"]["allConfirmedSupportsRemoved"]
    assert result["fullStructureChecked"] is False


@pytest.mark.parametrize("refused", [False, True])
async def test_multiblock_support_uses_extend_and_always_clears_selection(native_construction, refused):
    client, state = native_construction
    state["controller"] = {**construction.CONTROLLER, "y": 5}
    state["fail_worldedit"] = refused
    arguments = declared.declared_setup(state, empty=False, controller=state["controller"], supportMode="command_column")
    arguments["inventoryPreparation"] = "clear"
    result = await construction.invoke(client, arguments)
    assert (result["status"] == "completed") is not refused, result
    messages = [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"]
    assert "//sel extend" in messages and "//pos1 -44,2,125" in messages and "//pos2 -44,4,125" in messages
    assert state["selection"] is None and messages[messages.index("//replace air stone") + 1] == "//sel"
    assert result["cleanup"]["worldEditSelection"]["acknowledged"] is True
    if refused:
        assert result["reason"] == "unsupported" and not state["construct_count"]


async def test_generated_recipe_defaults_to_one_clear_then_finite_grant(native_bridge):
    client, state = native_bridge
    arguments = recipes.declaration(generateMaterials=True)
    arguments.pop("inventoryPreparation")
    result = await recipes.invoke(client, arguments)
    assert result["status"] == "completed", result
    messages = [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"]
    assert messages == ["/clear TestPlayer", "/give TestPlayer minecraft:planks 8 0"]
    assert result["materialGrantsDispatched"] == 1 and result["generatedMaterials"]
    assert result["inventoryPreparation"]["mode"] == "clear" and result["inventoryPreparation"]["clearConfirmed"]
    assert not result["inventoryPreparation"]["borrowed"] and result["acceptance"]["consumed"] == 8


async def test_selection_clear_confirmation_uses_fresh_filtered_messages_after_baseline(native_construction):
    client, state = native_construction
    state["caps"].append("client.messages")
    state["controller"] = {**construction.CONTROLLER, "y": 5}
    arguments = construction.declaration(controller=state["controller"],
        inventoryPreparation="clear", supportMode="command_column", generateMaterials=True,
        declaredSupplies=[{"hotbarSlot": index, "item": {key: value for key, value in item.items()
            if key in ("registryName", "metadata")}, "count": item["count"]}
            for index, item in state["items"].items() if item is not None and index != 6])
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    result = await construction.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["cleanup"]["worldEditSelection"]["confirmed"]
    polls = [call["args"] for call in state["calls"] if call["operation"] == "client.messages"]
    assert polls[-1]["since"] == state["message_seq"] - 1 and polls[-1]["query"] == "Selection cleared."


async def test_real_recipe_and_requested_restoration_keep_preserve_route(native_bridge):
    client, state = native_bridge
    arguments = recipes.declaration(generateMaterials=False, inventoryPreparation="clear")
    result = await recipes.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["inventoryPreparation"]["mode"] == "preserve" and not result["generatedMaterials"]
    assert not any(call["operation"] == "chat.send" for call in state["calls"])


async def test_recipe_deadline_bounds_write_queue_wait_without_releasing_another_owner(native_bridge):
    client, state = native_bridge
    lock = anyio.Lock()
    async with lock:
        with anyio.fail_after(1.5):
            pending = asyncio.create_task(recipes.run_recipe_once(client, recipes.declaration(timeout_ms=1000), lock,
                runner=recipes.run_workflow))
            result = await pending
        assert lock.locked() and not state["calls"]
    assert result["status"] == "unavailable" and result["reason"] == "task_timeout"
    assert 900 <= result["taskElapsed_ms"] < 1400


async def test_native_port_placement_reuses_authoritative_wait_with_complete_empty_contents(native_construction):
    client, state = native_construction
    task = construction._ConstructionTask(client, construction.declaration(), construction.run_workflow, started=time.monotonic())
    task.capabilities = state["caps"]
    task.materials[2] = {"item": construction.PORT_ITEMS["item_input"], "count": 1}
    point = {**construction.CONTROLLER, "y": 2}
    observed = await task.place(point, 2, "port_place")
    assert observed["source"] == "integrated_server" and observed["dimension"] == 0
    assert observed["gregtech"]["metaTileId"] == construction.PORT_ITEMS["item_input"]["metadata"]
    assert len(observed["inventory"]["slots"]) == observed["inventory"]["totalSlots"] == 4
    assert all(slot["item"] is None for slot in observed["inventory"]["slots"])
    assert task.port_empty_contents(observed, "item_input")["inventoryAllEmpty"]
    assert len([call for call in state["calls"] if call["operation"] == "world.inspect_many"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "world.inspect"]) == 2
    assert len(state["placements"]) == 1 and not state["digs"]


@pytest.mark.parametrize("fault", ["client_source", "wrong_dimension", "wrong_coordinate", "wrong_identity"])
async def test_native_wait_reuse_still_rejects_wrong_authority_or_placed_identity(native_construction, monkeypatch, fault):
    client, state = native_construction
    actual_call = client.call

    async def corrupt(operation, arguments, **options):
        result = await actual_call(operation, arguments, **options)
        if operation == "world.inspect" and state["placements"]:
            if fault == "client_source":
                result["source"] = "client"
            elif fault == "wrong_dimension":
                result["dimension"] += 1
            elif fault == "wrong_coordinate":
                result["x"] += 1
            else:
                result["gregtech"]["metaTileId"] += 1
        return result

    monkeypatch.setattr(client, "call", corrupt)
    task = construction._ConstructionTask(client, construction.declaration(), construction.run_workflow, started=time.monotonic())
    task.capabilities = state["caps"]
    task.materials[2] = {"item": construction.PORT_ITEMS["item_input"], "count": 1}
    with pytest.raises(BridgeError) as caught:
        await task.place({**construction.CONTROLLER, "y": 2}, 2, "port_place")
    assert caught.value.details["reason"] == ("native_placed_identity_mismatch" if fault == "wrong_identity"
        else "server_block_page_unavailable")
    assert len(state["placements"]) == 1 and not state["digs"]


async def test_owned_dig_reuses_initial_identity_and_authoritative_air_wait_without_extra_reads(native_construction):
    client, state = native_construction
    task = construction._ConstructionTask(client, construction.declaration(), construction.run_workflow, started=time.monotonic())
    task.empty_slot = 7
    point = {**construction.CONTROLLER, "y": 2}
    state["blocks"][construction.key(point)] = construction.CASING.copy()
    before = await task.block(point)
    await task.dig_owned(point, construction.CASING, "owned_dig", before=before)
    assert len([call for call in state["calls"] if call["operation"] == "world.inspect_many"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "world.inspect"]) == 2
    assert len(state["digs"]) == 1 and not state["placements"]
