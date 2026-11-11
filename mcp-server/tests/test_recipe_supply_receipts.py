"""Native supply receipts count attempts and retain transport uncertainty without inferring inventory success."""

import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


@pytest.mark.parametrize("attempted,accepted,complete,unknown", [(3, 3, True, False), (2, 1, False, True), (0, 0, False, False), (8, 8, True, False)])
async def test_native_supply_records_typed_command_attempts_and_bounded_receipt(native_bridge, monkeypatch, attempted, accepted, complete, unknown):
    client, _ = native_bridge
    async def supply(operation, args, **options):
        assert operation == "inventory.supply"
        options["trace"].update(dispatched=True, request_id="supply-request", outcome_unknown=False)
        return {"requestedCount": 3, "commandsAttempted": attempted, "commandsAccepted": accepted,
            "complete": complete, "outcomeUnknown": unknown, "inventory": {"unrelated": "not copied"}}
    monkeypatch.setattr(client, "call", supply)
    task = _RecipeTask(client, native.declaration(), run_workflow)
    result = await task.tracked_client.call("inventory.supply", {"items": [{"count": 1}] * 3})
    assert task.material_grants_dispatched == attempted and task.material_grants_count_known
    assert task.last_action["outcomeUnknown"] is unknown
    assert task.last_action["nativeSupply"] == {"requestedCount": 3, "commandsAttempted": attempted,
        "commandsAccepted": accepted, "complete": complete, "outcomeUnknown": unknown}
    assert result["complete"] is complete and task.final_inventory_verified is False


@pytest.mark.parametrize("unknown", [False, True])
async def test_native_supply_without_receipt_keeps_dispatch_uncertainty_and_never_calls_again(native_bridge, monkeypatch, unknown):
    client, _ = native_bridge
    calls = []
    async def unavailable(operation, args, **options):
        calls.append(operation)
        options["trace"].update(dispatched=True, request_id="supply-request", outcome_unknown=unknown)
        raise BridgeError("NATIVE_SUPPLY_UNAVAILABLE", "No receipt.", outcome_unknown=unknown)
    monkeypatch.setattr(client, "call", unavailable)
    task = _RecipeTask(client, native.declaration(), run_workflow)
    with pytest.raises(BridgeError):
        await task.tracked_client.call("inventory.supply", {"items": [{"count": 1}] * 3})
    assert calls == ["inventory.supply"] and task.material_grants_dispatched == (3 if unknown else 0)
    assert task.last_action["outcomeUnknown"] is unknown
    assert task.material_grants_count_known is (not unknown)
    if unknown:
        assert task.last_action["nativeSupply"]["attemptedCountKnown"] is False
        assert "commandsAttempted" not in task.last_action["nativeSupply"]
    else:
        assert "nativeSupply" not in task.last_action


@pytest.mark.parametrize("attempted", [None, False, "2", -1, 9])
async def test_malformed_attempt_count_is_unknown_without_quantity_coercion(native_bridge, monkeypatch, attempted):
    client, _ = native_bridge
    async def supply(operation, args, **options):
        options["trace"].update(dispatched=True, request_id="supply-request", outcome_unknown=False)
        return {"commandsAttempted": attempted, "complete": False, "outcomeUnknown": True}
    monkeypatch.setattr(client, "call", supply)
    task = _RecipeTask(client, native.declaration(), run_workflow)
    await task.tracked_client.call("inventory.supply", {"items": [{"count": 1}] * 3})
    assert task.material_grants_dispatched == 0 and task.material_grants_count_known is False
    assert task.last_action["outcomeUnknown"] is True and task.final_inventory_verified is False
