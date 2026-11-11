import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.workflows import _validate_player_inventory_observation
from test_inspect_many_player_inventory import add_composite, combined_args, observation


def complete_item(count):
    return {"registryName": "gregtech:gt.integrated_circuit", "metadata": 24,
        "count": count, "hasTag": False, "displayName": "Integrated Circuit"}


def owner_observation():
    data, evidence = observation()
    owner = data["results"][0]["data"]
    owner.update(registryName="gregtech:gt.blockmachines", metadata=0)
    owner["inventory"] = {"name": "Actual owner inventory", "totalSlots": 4, "truncated": False,
        "slots": [{"index": 0, "item": complete_item(2)}, {"index": 1, "item": None},
            {"index": 2, "item": complete_item(0)}, {"index": 3, "item": complete_item(1)}]}
    evidence = add_composite(data, evidence["snapshot"], data["playerInventory"], combined_args())
    return data, evidence


def test_zero_count_server_owner_stack_and_all_rows_are_preserved():
    data, evidence = owner_observation()
    original_data, original_evidence = copy.deepcopy(data), copy.deepcopy(evidence)

    _validate_player_inventory_observation(data, combined_args(), evidence)

    assert data == original_data and evidence == original_evidence
    assert data["results"][0]["data"]["inventory"]["slots"][2]["item"] == complete_item(0)


@pytest.mark.parametrize("group,count", [("owner", -1), ("player", 0), ("cursor", 0),
    ("owner", False), ("owner", 2_147_483_648)])
def test_owner_zero_exception_does_not_accept_invalid_counts_elsewhere(group, count):
    data, evidence = owner_observation()
    if group == "owner":
        data["results"][0]["data"]["inventory"]["slots"][2]["item"]["count"] = count
    elif group == "player":
        data["playerInventory"]["slots"][0]["item"] = complete_item(count)
    else:
        data["playerInventory"]["cursorItem"] = complete_item(count)
    evidence = add_composite(data, evidence["snapshot"], data["playerInventory"], combined_args())

    with pytest.raises(BridgeError) as error:
        _validate_player_inventory_observation(data, combined_args(), evidence)

    assert error.value.code == "MALFORMED_RESPONSE"
    assert error.value.details["outcome_unknown"] is False


@pytest.mark.parametrize("group", ["player", "cursor"])
def test_positive_player_and_cursor_items_remain_valid_with_owner_zero(group):
    data, evidence = owner_observation()
    if group == "player":
        data["playerInventory"]["slots"][0]["item"] = complete_item(1)
    else:
        data["playerInventory"]["cursorItem"] = complete_item(1)
    evidence = add_composite(data, evidence["snapshot"], data["playerInventory"], combined_args())
    original_data = copy.deepcopy(data)

    _validate_player_inventory_observation(data, combined_args(), evidence)

    assert data == original_data
