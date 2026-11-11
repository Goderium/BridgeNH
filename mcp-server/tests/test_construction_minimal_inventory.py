"""Minimal preparation retains only exact, fully observed declared build supplies."""

import copy

import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.construction_tasks import BLUEPRINT_BUILD_SCHEMA

native_construction = native.native_construction


def minimal_setup(state, *, generate=True, **changes):
    state["items"][0]["count"] = 1
    state["items"][6] = None
    arguments = native.declaration(**changes)
    arguments.pop("inventoryPreparation")
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    if generate:
        arguments["generateMaterials"] = True
        arguments["declaredSupplies"] = [{"hotbarSlot": 0, "item": native.BLUEPRINT.copy(), "count": 1},
            {"hotbarSlot": 1, "item": native.CONTROLLER_ITEM.copy(), "count": 1},
            *[{"hotbarSlot": slot, "item": native.PORT_ITEMS[role].copy(), "count": 1}
                for role, slot in native.PORT_SLOTS.items()]]
    return arguments


def commands(state):
    return [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"]


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("explicit", [False, True])
async def test_exact_minimal_supplies_keep_declared_bindings_without_clear_or_grants(native_construction, order, explicit):
    client, state = native_construction
    arguments = minimal_setup(state, constructionOrder=order)
    if explicit:
        arguments["inventoryPreparation"] = "minimal"
    original = copy.deepcopy(state["items"])
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["inventoryPreparationMode"] == "minimal" and result["alreadyMinimalVerified"] is True
    assert result["inventoryClearConfirmed"] is result["generatedMaterials"] is False
    assert result["materialGrantsDispatched"] == 0 and state["inventory_activations"] == []
    assert state["items"] == original
    assert not any(message.startswith(("/clear", "/give")) for message in commands(state))
    assert result["supportPreparationMode"] == "command" and result["cleanup"]["allConfirmedSupportsRemoved"]
    placements = [entry for entry in state["placements"] if entry["item"]["metadata"] in
        [native.CONTROLLER_ITEM["metadata"], *[item["metadata"] for item in native.PORT_ITEMS.values()]]]
    assert len(placements) == 5


@pytest.mark.parametrize("difference", ["extra_storage", "extra_armor", "tagged_extra", "unused_support",
    "controller_count", "port_count", "controller_storage", "duplicate_blueprint"])
async def test_known_inventory_differences_use_existing_clear_and_finite_supply_path(native_construction, difference):
    client, state = native_construction
    arguments = minimal_setup(state)
    if difference == "extra_storage":
        state["items"][9] = native.stack({"registryName": "minecraft:stone", "metadata": 0})
    elif difference == "extra_armor":
        state["items"][40] = native.stack({"registryName": "minecraft:iron_helmet", "metadata": 0})
    elif difference == "tagged_extra":
        state["items"][36] = {**native.stack({"registryName": "minecraft:stone", "metadata": 0}), "hasTag": True}
    elif difference == "unused_support":
        state["items"][6] = native.stack(native.SUPPORT)
    elif difference == "controller_count":
        state["items"][1]["count"] = 2
    elif difference == "port_count":
        state["items"][2]["count"] = 2
    elif difference == "controller_storage":
        state["items"][9], state["items"][1] = state["items"][1], None
    else:
        state["items"][9] = native.stack(native.BLUEPRINT)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["inventoryPreparationMode"] == "minimal" and result["alreadyMinimalVerified"] is False
    assert result["inventoryClearConfirmed"] and result["materialGrantsDispatched"] == 6
    assert commands(state).count("/clear TestPlayer") == 1
    assert sum(message.startswith("/give") for message in commands(state)) == 6
    assert all(state["items"][index] is None for index in range(9, 41))


@pytest.mark.parametrize("difference", ["binding", "count", "tag", "identity", "bool_count", "missing_tag"])
async def test_minimal_uses_fresh_inventory_after_preflight_before_skipping_preparation(native_construction, difference):
    client, state = native_construction
    arguments = minimal_setup(state)

    def change_fresh(data):
        if sum(call["operation"] == "inventory.inspect" for call in state["calls"]) != 2:
            return
        source = data["slots"][2]
        if difference == "binding":
            data["slots"][4]["item"], source["item"] = source["item"], None
        elif difference == "count":
            source["item"]["count"] = 2
        elif difference == "tag":
            source["item"]["hasTag"] = True
        elif difference == "identity":
            source["item"]["metadata"] += 1
        elif difference == "bool_count":
            source["item"]["count"] = True
        else:
            source["item"].pop("hasTag")

    state["inventory_observation_hook"] = change_fresh
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["alreadyMinimalVerified"] is False and result["inventoryClearConfirmed"]
    assert commands(state).count("/clear TestPlayer") == 1


@pytest.mark.parametrize("fault,reason", [("truncated", "inventory_coverage_incomplete"),
    ("missing_armor", "minimal_inventory_coverage_unavailable"), ("wrong_index", "minimal_inventory_coverage_unavailable"),
    ("extra_index", "minimal_inventory_coverage_unavailable"), ("duplicate_index", "inventory_slot_identity_unavailable"),
    ("missing_item", "inventory_slot_identity_unavailable"), ("cursor_missing", "carried_stack_not_empty"),
    ("cursor_nonempty", "carried_stack_not_empty")])
async def test_unknown_complete_coverage_or_cursor_cannot_skip_or_clear_inventory(native_construction, fault, reason):
    client, state = native_construction
    arguments = minimal_setup(state)

    def change_fresh(data):
        if sum(call["operation"] == "inventory.inspect" for call in state["calls"]) != 2:
            return
        if fault == "truncated":
            data["truncated"] = True
        elif fault == "missing_armor":
            data["slots"] = data["slots"][:36]
            data["totalSlots"] = 36
        elif fault == "wrong_index":
            data["slots"][-1]["index"] = 41
        elif fault == "extra_index":
            data["slots"].append({"index": 41, "item": None})
            data["totalSlots"] = 42
        elif fault == "duplicate_index":
            data["slots"][-1]["index"] = 0
        elif fault == "missing_item":
            data["slots"][-1].pop("item")
        elif fault == "cursor_missing":
            data.pop("cursorItem")
        else:
            data["cursorItem"] = native.stack(native.BLUEPRINT)

    state["inventory_observation_hook"] = change_fresh
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == reason, result
    assert result["alreadyMinimalVerified"] is result["inventoryClearConfirmed"] is False
    assert result["materialGrantsDispatched"] == 0 and not native.writes(state)


async def test_explicit_clear_always_clears_even_when_inventory_is_already_minimal(native_construction):
    client, state = native_construction
    arguments = minimal_setup(state)
    arguments["inventoryPreparation"] = "clear"
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["inventoryPreparationMode"] == "clear" and result["inventoryClearConfirmed"]
    assert result["alreadyMinimalVerified"] is False and result["materialGrantsDispatched"] == 6
    assert commands(state).count("/clear TestPlayer") == 1


@pytest.mark.parametrize("mode,restore", [("preserve", False), ("minimal", True)])
async def test_preserve_and_requested_restoration_keep_native_inventory_path(native_construction, mode, restore):
    client, state = native_construction
    original = copy.deepcopy(state["items"])
    result = await native.invoke(client, native.declaration(inventoryPreparation=mode, restoreInventory=restore))
    assert result["status"] == "completed", result
    assert result["inventoryPreparationMode"] == "preserve" and result["alreadyMinimalVerified"] is False
    assert result["inventoryClearConfirmed"] is result["generatedMaterials"] is False
    assert state["items"] == original and not commands(state)
    assert result["cleanup"]["inventoryRestored"] is restore


@pytest.mark.parametrize("count", [1, 64, 65])
async def test_minimal_keeps_existing_finite_blueprint_supply_budget(native_construction, count):
    client, state = native_construction
    arguments = minimal_setup(state)
    state["items"][0]["count"] = count
    arguments["declaredSupplies"][0]["count"] = count
    if count > 64:
        from minecraft_codex_bridge.bridge import BridgeError
        with pytest.raises(BridgeError) as failure:
            await native.invoke(client, arguments)
        assert failure.value.code == "INVALID_ARGUMENTS" and not native.writes(state)
        return
    result = await native.invoke(client, arguments)
    if count <= 64:
        assert result["status"] == "completed" and result["alreadyMinimalVerified"], result
    else:
        assert result["status"] == "unavailable" and result["reason"] == "material_command_budget", result
        assert result["alreadyMinimalVerified"] is False and not native.writes(state)


@pytest.mark.parametrize("fault,reason", [("tag", "item_nbt_or_identity_unavailable"),
    ("missing", "item_nbt_or_identity_unavailable"), ("zero", "declared_material_empty"),
    ("boolean", "quantity_unavailable")])
async def test_minimal_does_not_supply_unproven_initial_declared_materials(native_construction, fault, reason):
    client, state = native_construction
    arguments = minimal_setup(state, generate=False)
    if fault == "tag":
        state["items"][2]["hasTag"] = True
    elif fault == "missing":
        state["items"][2] = None
    elif fault == "zero":
        state["items"][2]["count"] = 0
    else:
        state["items"][2]["count"] = True
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == reason, result
    assert result["alreadyMinimalVerified"] is False and not native.writes(state)


def test_schema_exposes_preserve_default_and_keeps_explicit_creative_paths():
    schema = BLUEPRINT_BUILD_SCHEMA["properties"]["inventoryPreparation"]
    assert schema["default"] == "preserve" and schema["enum"] == ["minimal", "clear", "preserve"]
