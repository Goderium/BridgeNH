"""Consume the versioned native server AFTER without inventing empty public storage."""
import copy
import json

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_survival_structure_seeds as seeds
from test_construction_tasks import CASING, CONTROLLER, PORT_ITEMS, key, native_construction, stack


def wrapper(task, target, *, tile=False):
    data = {**copy.deepcopy(target), "loaded": True, "worldTick": 100}
    coverage = {"tile": "present" if tile else "absent", "inventory": "observed" if tile else "not_exposed",
        "fluids": "observed" if tile else "not_exposed", "gregtech": "identity_observed" if tile else "not_exposed",
        "identity": "observed", "ownerUnchanged": True, "budgetLimited": False,
        "byteLimit": 49152, "inventorySlotLimit": 256, "fluidTankLimit": 32, "softBudgetMicros": 4000, "elapsedMicros": 125}
    if tile:
        data["tileClass"] = "Fixture.PublicTile"
        data["inventory"] = {"totalSlots": 0, "truncated": False, "slots": []}
        data["fluids"] = {"totalTanks": 1, "truncated": False, "tanks": [{"index": 0, "capacity": 16000}]}
        coverage.update(totalSlots=0, observedSlots=0, totalTanks=1, observedTanks=1)
    coverage["dataBytes"] = len(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    return {"version": 1, "source": "integrated_server", "sessionId": task.args["expectedSessionId"],
        "playerId": "actual-player", "worldName": task.args["expectedWorld"], "dimension": task.dimension,
        "phase": "native_place_server_after", "worldTick": 100, "target": {axis: target[axis] for axis in ("x", "y", "z")},
        "loaded": True, "complete": True, "partial": False, "data": data, "coverage": coverage}


def prepare(client, state, monkeypatch, *, tile=False, old=False, change=None):
    task = seeds.task(client)
    task.survival_player_id = task.survival_control_player_id = "actual-player"
    task.position_snapshot = None
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 2, "y": 2}
    item = PORT_ITEMS["item_input"] if tile else CASING
    state["items"][6] = stack(item, 2)
    task.materials[6] = {"item": item.copy(), "count": 2}
    placements = seeds.install_finite_native_receipts(task, state, monkeypatch)
    if not old:
        task.approach_health["placementAfterObservationVersion"] = 1
    original_workflow, original_call = task.workflow, task.call
    plans, reads = [], []

    async def workflow(phase, steps):
        plans.append(copy.deepcopy(steps))
        if not any(step.get("tool") == "mcb_place_block" for step in steps):
            assert all(step.get("kind") != "action" for step in steps)
            reads.append("read_only_fallback")
            observed = {**point, "source": "integrated_server", "dimension": task.dimension,
                "registryName": item["registryName"], "metadata": 0 if tile else item["metadata"]}
            if tile:
                observed.update(gregtech={"metaTileId": item["metadata"], "metaClass": "Fixture.PublicMeta"}, tileClass="Fixture.PublicTile",
                    inventory={"totalSlots": 0, "truncated": False, "slots": []},
                    fluids={"totalTanks": 1, "truncated": False, "tanks": [{"index": 0, "capacity": 16000}]})
            return {"steps": [{"id": "placed", "observation": {"values": observed}},
                {"id": "material_after", "observation": {"values": seeds.server_inventory_from_state(state)}}]}
        report = await original_workflow(phase, steps)
        native = report["steps"][0]["observation"]["values"]
        # The original constructor has no player/session/dimension/tick fields
        # inside serverAfter, and no player/session/tick inside serverTarget.
        for name in ("serverBefore", "serverAfter"):
            for field in ("playerId", "sessionId", "dimension", "worldTick"):
                native[name].pop(field, None)
        for field in ("playerId", "sessionId", "worldTick"):
            native["serverTarget"].pop(field, None)
        if tile:
            native["serverTarget"]["gregtech"]["metaClass"] = "Fixture.PublicMeta"
            native["serverTarget"].pop("tileClass", None)
        observation = wrapper(task, native["serverTarget"], tile=tile)
        if change is not None:
            change(observation)
        if observation is not None and not observation.get("omittedFixtureField"):
            native["serverPlacementObservation"] = observation
        requested = {step["id"] for step in steps}
        report["steps"] = [step for step in report["steps"] if step["id"] in requested]
        return report

    async def call(operation, args):
        reads.append(operation)
        return await original_call(operation, args)

    task.workflow, task.call = workflow, call
    return task, point, placements, plans, reads


@pytest.mark.parametrize("tile", [False, True])
async def test_complete_actual_after_uses_original_all41_deduction_and_real_target_without_extra_reads(native_construction, monkeypatch, tile):
    client, state = native_construction
    task, point, placements, plans, reads = prepare(client, state, monkeypatch, tile=tile)
    record = {}
    observed = await task.place(point, 6, "after", record)
    assert len(placements) == 1 and not reads
    assert "serverPlacementObservation" in next(step for step in plans[0] if step["id"] == "place")["fields"]
    assert all(step["id"] not in ("placed", "material_after") for step in plans[0])
    assert any(step["id"] == "source_exact" for step in plans[0]) and any(step["id"] == "target_air" for step in plans[0])
    assert record["serverConsumptionConfirmed"] and record["placementConfirmed"] and task.materials[6]["count"] == 1
    assert len(task.survival_material_ledger) == 1 and task.survival_material_ledger[0]["deductions"][0]["count"] == 1
    if tile:
        assert task.port_empty_contents(observed, "item_input")["totalInventorySlots"] == 0
    else:
        assert "inventory" not in observed and "fluids" not in observed


@pytest.mark.parametrize("absence", ["old_health", "old_field", "partial", "unloaded", "null_loaded", "getter_error", "budget"])
async def test_absent_or_explicit_partial_after_runs_only_read_fallback_and_never_replays(native_construction, monkeypatch, absence):
    client, state = native_construction
    def change(observation):
        if absence == "old_field": observation["omittedFixtureField"] = True
        elif absence != "old_health":
            observation.update(complete=False, partial=True)
            observation.pop("data", None)
            if absence == "unloaded": observation["loaded"] = False
            elif absence == "null_loaded": observation["loaded"] = None
            elif absence == "getter_error": observation["error"] = {"code": "getter_error", "message": "Unavailable actual getter"}
            elif absence == "budget": observation["coverage"]["budgetLimited"] = True
    task, point, placements, plans, reads = prepare(client, state, monkeypatch, old=absence == "old_health", change=change)
    await task.place(point, 6, "fallback")
    assert len(placements) == 1 and task.materials[6]["count"] == 1 and len(task.survival_material_ledger) == 1
    assert sum(step.get("tool") == "mcb_place_block" for steps in plans for step in steps) == 1
    if absence != "old_health":
        assert reads.count("read_only_fallback") == 1 and len(plans) == 2
    else:
        assert any(step["id"] == "placed" for step in plans[0])


@pytest.mark.parametrize("fault", ["version_bool", "session", "player", "world", "dimension_bool", "target_bool", "target_xyz", "tick_bool",
    "data_tick", "phase", "data_identity", "false_loaded", "coverage_unknown", "coverage_owner", "inventory_partial", "inventory_duplicate",
    "invented_plain_storage", "tank_partial", "tank_capacity", "gt_class", "budget", "byte_bound", "complete_partial"])
async def test_malformed_claimed_complete_after_retains_actual_deduction_and_stops_zero_replay(native_construction, monkeypatch, fault):
    client, state = native_construction
    tile = fault in ("inventory_partial", "inventory_duplicate", "tank_partial", "tank_capacity", "gt_class")
    def change(value):
        if fault == "version_bool": value["version"] = True
        elif fault == "session": value["sessionId"] = "foreign"
        elif fault == "player": value["playerId"] = "foreign"
        elif fault == "world": value["worldName"] = "foreign"
        elif fault == "dimension_bool": value["dimension"] = False
        elif fault == "target_bool": value["target"]["y"] = True
        elif fault == "target_xyz": value["target"]["x"] += 1
        elif fault == "tick_bool": value["worldTick"] = True
        elif fault == "data_tick": value["data"]["worldTick"] += 1
        elif fault == "phase": value["phase"] = "before"
        elif fault == "data_identity": value["data"]["metadata"] += 1
        elif fault == "false_loaded": value["loaded"] = False
        elif fault == "coverage_unknown": value["coverage"]["inventory"] = "unknown"
        elif fault == "coverage_owner": value["coverage"]["ownerUnchanged"] = False
        elif fault == "inventory_partial": value["data"]["inventory"]["truncated"] = True
        elif fault == "inventory_duplicate":
            value["data"]["inventory"].update(totalSlots=2, slots=[{"index": 0, "item": None}, {"index": 0, "item": None}])
            value["coverage"].update(totalSlots=2, observedSlots=2)
        elif fault == "invented_plain_storage": value["data"]["inventory"] = {"totalSlots": 0, "slots": [], "truncated": False}
        elif fault == "tank_partial": value["data"]["fluids"]["tanks"] = []
        elif fault == "tank_capacity": value["data"]["fluids"]["tanks"][0]["capacity"] = True
        elif fault == "gt_class": value["data"]["gregtech"]["metaClass"] = "foreign"
        elif fault == "budget": value["coverage"]["budgetLimited"] = True
        elif fault == "byte_bound": value["coverage"]["dataBytes"] = 49153
        else: value["partial"] = True
    task, point, placements, plans, reads = prepare(client, state, monkeypatch, tile=tile, change=change)
    record = {}
    with pytest.raises(BridgeError): await task.place(point, 6, "malformed_after", record)
    assert len(placements) == 1 and not reads and len(plans) == 1
    assert key(point) in state["blocks"] and state["items"][6]["count"] == task.materials[6]["count"] == 1
    assert record["serverConsumptionConfirmed"] is True and record["survivalMaterialLedgerIndex"] == 0
    assert task.survival_material_ledger[0]["deductions"][0]["count"] == 1
