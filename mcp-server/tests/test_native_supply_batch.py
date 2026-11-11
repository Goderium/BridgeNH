"""Native batching retains real inventory verification and never replays partial grants."""
import pytest

import test_construction_tasks as native
from test_construction_declared_goals import declared_setup

native_construction = native.native_construction


async def test_native_batch_supply_is_one_action_then_real_formation(native_construction):
    client, state = native_construction
    args = declared_setup(state)
    state["caps"].append("inventory.supply")
    result = await native.invoke(client, args)
    assert result["status"] == "completed" and result["formationVerified"], result
    batches = [call for call in state["calls"] if call["operation"] == "inventory.supply"]
    assert len(batches) == 1 and len(batches[0]["args"]["items"]) == 6
    assert not any(call["operation"] == "chat.send" and call["args"]["message"].startswith("/give") for call in state["calls"])
    assert result["materialSupplyMode"] == "native_server_give_commands"


@pytest.mark.parametrize("unknown", [False, True])
async def test_partial_native_supply_stops_without_replay_or_construction(native_construction, unknown):
    client, state = native_construction
    args = declared_setup(state)
    state["caps"].append("inventory.supply")
    state["supply_failure_at"], state["supply_unknown"] = 1, unknown
    result = await native.invoke(client, args)
    assert result["status"] != "completed", result
    assert len([call for call in state["calls"] if call["operation"] == "inventory.supply"]) == 1
    assert not any(call["operation"] == "chat.send" and call["args"]["message"].startswith("/give") for call in state["calls"])
    assert state["construct_count"] == 0 and not state["placements"]


async def test_native_receipts_never_replace_inventory_acceptance(native_construction):
    client, state = native_construction
    args = declared_setup(state, timeout_ms=1000)
    state["caps"].append("inventory.supply")
    state["supply_wrong_inventory"] = True
    result = await native.invoke(client, args)
    assert result["status"] != "completed" and not state["placements"], result


async def test_old_client_preserves_existing_chat_supply(native_construction):
    client, state = native_construction
    args = declared_setup(state)
    result = await native.invoke(client, args)
    assert result["status"] == "completed", result
    assert not any(call["operation"] == "inventory.supply" for call in state["calls"])
    assert len([call for call in state["calls"] if call["operation"] == "chat.send" and call["args"]["message"].startswith("/give")]) == 6


async def test_exact_existing_supplies_need_no_batch(native_construction):
    client, state = native_construction
    args = declared_setup(state, empty=False)
    state["caps"].append("inventory.supply")
    result = await native.invoke(client, args)
    assert result["status"] == "completed" and result["alreadyMinimalVerified"], result
    assert not any(call["operation"] == "inventory.supply" for call in state["calls"])
