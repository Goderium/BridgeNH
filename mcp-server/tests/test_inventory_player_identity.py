"""Inventory UUID contract: missing or another player never authorizes survival."""

import time

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import CONTROLLER, declaration, native_construction, writes


ACTUAL_UUID = "94ca5cb4-942a-4f50-8848-bcc68c453758"


def instance(client):
    args = declaration(actualSurvival=True, controller={**CONTROLLER, "y": 2})
    args.pop("supportItem")
    args.pop("supportHotbarSlot")
    return _ConstructionTask(client, args, run_workflow, started=time.monotonic())


@pytest.mark.parametrize("inventory_uuid,reason", [
    (ACTUAL_UUID, None),
    (None, "survival_player_identity_unavailable"),
    ("", "survival_player_identity_unavailable"),
    (False, "survival_player_identity_unavailable"),
    ("TestPlayer", "survival_player_identity_mismatch"),
    ("94ca5cb4-942a-4f50-8848-bcc68c453759", "survival_player_identity_mismatch"),
])
async def test_original_inventory_uuid_matches_actual_server_control_before_any_write(
    native_construction, monkeypatch, inventory_uuid, reason
):
    client, state = native_construction
    task = instance(client)
    state["flying"] = False
    state["caps"].append("structure.survival_build")
    monkeypatch.setattr(task, "native_approach_available", lambda intent: True)
    original = task.call

    async def actual_dto(operation, args):
        result = await original(operation, args)
        if operation == "inventory.inspect" and inventory_uuid is not None:
            result["playerId"] = inventory_uuid
        elif operation == "client.snapshot":
            result["player"]["creative"] = False
            result["player"]["serverFlight"]["creative"] = False
            result["serverControl"] = {"available": True, "source": "integrated_server_tick",
                "sessionId": "session", "worldName": "new-world", "dimension": 0,
                "sampleId": 7, "ageMillis": 0, "playerId": ACTUAL_UUID}
        return result

    monkeypatch.setattr(task, "call", actual_dto)
    if reason is None:
        await task.preflight()
        assert task.survival_player_id == task.survival_control_player_id == ACTUAL_UUID
        assert len(task.survival_inventory) == 41
        assert task.floor == 1
    else:
        with pytest.raises(BridgeError) as failure:
            await task.preflight()
        assert failure.value.details["reason"] == reason
    assert writes(state) == []
