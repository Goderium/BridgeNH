"""Offline generic from-zero native placement, first charge and full recipe recovery."""

import copy
import uuid

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_native_approach_tasks as planner
from test_native_navigation import actual_current_receipt
from test_single_recipe_tasks import single_machine
from minecraft_codex_bridge.bridge import BridgeError, response_context
from minecraft_codex_bridge.recipe_tasks import COMMON_BASIC_MACHINE_API, _RecipeTask
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


def declaration(state, **changes):
    return multi.arguments(catalysts=0, actualSurvival=True, preflightControls=True,
        inventoryPreparation="preserve", generateMaterials=False,
        singleBlockPlacement={"item": {"registryName": "gregtech:gt.blockmachines", "metadata": state["single_meta_id"]},
            "hotbarSlot": 3, "powerReadyTimeout_ms": 1000}, **changes)


@pytest.fixture
def from_zero(single_machine, monkeypatch):
    client, state = single_machine
    multi.vector_transport(client, state, monkeypatch, catalysts=0, baseline=False)
    point = native.MACHINE
    state.update(placed=False, place_fault=None, charge_fault=None, charge_reads=0, charge_zero_reads=3,
        selected=0, control_sample=0, flying=False, source_sync_reads=0, source_sync_delay=0,
        position={"x": point["x"] + .5, "y": point["y"], "z": point["z"] - 1.5},
        single_meta_class="example.AnyPublicBasicMachineSubclass")
    state["single_world"]["gregtech"]["metaClass"] = state["single_meta_class"]
    state["player_items"][3] = native.stack({"registryName": "gregtech:gt.blockmachines", "metadata": state["single_meta_id"]}, 2)
    state["items"] = state["player_items"]
    state["blocks"] = {(point["x"], point["y"] - 1, point["z"]): {"registryName": "minecraft:stone", "metadata": 0}}
    original = client.call

    def server_inventory():
        slots = [{"index": index, "item": copy.deepcopy(state["player_items"].get(index))} for index in range(41)]
        slots[40]["item"] = native.stack({"registryName": "example:armor", "metadata": 7}, 1)
        for row in slots:
            if row["item"] is not None:
                row["item"]["nbtIdentity"] = "null"
        return {"source": "integrated_server", "inventorySource": "actual_player_inventory", "complete": True,
            "totalSlots": 41, "truncated": False, "cursorItem": None, "slots": slots}

    def add_control(result):
        state["control_sample"] += 1
        container = {"windowId": state["window"], "isInventoryContainer": state["open"] is None}
        result["serverControl"] = {"available": True, "source": "integrated_server_tick", "sessionId": "session",
            "worldName": "test-world", "worldFolder": "test-world", "dimension": 180,
            "sampleId": state["control_sample"], "ageMillis": 0, "playerId": "actual-player",
            "openContainer": container.copy(), "clientContainer": container.copy(),
            "selectedHotbarSlot": state["selected"], "selectionSynchronized": True}
        result["player"].update(creative=False, selectedHotbarSlot=state["selected"])
        result["player"]["serverFlight"].update(creative=False, flying=False)

    async def composed(operation, args, **options):
        if operation == "player.plan_approach":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            result = planner.composite_receipt(state, args)
            result["serverFlight"].update(creative=False, flying=False, onGround=True)
            context = response_context(result)["snapshot"]
            add_control(context)
            for block in (result["target"], result["selected"]["attachment"], result["selected"]["serverRay"], result["selected"]["clientRay"]):
                if isinstance(block.get("gregtech"), dict):
                    block["gregtech"].update(metaTileId=state["single_meta_id"], metaClass=state["single_meta_class"])
            if args.get("currentOnly") is True:
                actual_current_receipt(result, state)
            return result
        if operation == "player.place_block":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id=str(uuid.uuid4()))
            before = server_inventory()
            if state["place_fault"] in ("local_rejected", "malformed_checkpoint"):
                known = {"nativeInvoked": True, "accepted": False, "localAcceptanceKnown": True,
                    "actualSurvival": True, "serverConfirmed": False, "serverConsumptionConfirmed": False,
                    "outcomeUnknown": True, "automaticReplayAllowed": False, "lastCheckpoint": "local_place_rejected",
                    "serverBefore": before, "serverAfter": copy.deepcopy(before),
                    "clientPlacementBodyProof": {"version": 1, "wholeTargetCube": True, "overlapsTarget": False},
                    "serverPlacementBodyProof": {"version": 1, "wholeTargetCube": True, "overlapsTarget": False}}
                checkpoint = {"stage": "local_place_rejected", "failureCheckpoint": "local_place_rejected",
                    "nativeSubmitted": True, "callsStarted": 1, "callsCompleted": 1, "callbackInFlight": False,
                    "partial": True, "callbackPreemptible": False, "automaticReplayAllowed": False,
                    "batches": [], "knownReceipt": known, "outcome_unknown": True}
                if state["place_fault"] == "malformed_checkpoint":
                    checkpoint["callsStarted"] = "1"
                # A custom runner must propagate uncertainty from the error even if its trace lacks it.
                raise BridgeError("native_refused", "One local native placement was rejected; packet effects remain unknown.", **checkpoint)
            state["source_before"] = copy.deepcopy(state["player_items"][3])
            state["player_items"][3]["count"] -= 1
            if state["player_items"][3]["count"] == 0:
                state["player_items"][3] = None
            state.update(placed=True, allowed=True, selected=3)
            state["blocks"][tuple(point[axis] for axis in ("x", "y", "z"))] = {
                "registryName": "gregtech:gt.blockmachines", "itemMetadata": state["single_meta_id"], "metadata": 0}
            if state["place_fault"] == "unknown":
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("BRIDGE_UNAVAILABLE", "Native placement outcome is unknown.", outcome_unknown=True)
            target = {**point, "dimension": 180, "source": "integrated_server", "registryName": "gregtech:gt.blockmachines",
                "metadata": 0, "gregtech": {"metaTileId": state["single_meta_id"], "metaClass": state["single_meta_class"]}}
            result = {"nativeInvoked": True, "actualSurvival": True, "serverConfirmed": True,
                "serverConsumptionConfirmed": True, "serverSourceConsumption": 1,
                "serverBefore": before, "serverAfter": server_inventory(), "serverTarget": target,
                "outcomeUnknown": False, "attachment": {"x": point["x"], "y": point["y"] - 1, "z": point["z"], "side": 1}}
            fault = state["place_fault"]
            if fault == "identity": result["serverTarget"]["gregtech"]["metaTileId"] += 1
            elif fault == "block_metadata_only": result["serverTarget"].pop("gregtech")
            elif fault == "unrelated_nbt": result["serverAfter"]["slots"][40]["item"]["nbtIdentity"] = "{changed:1}"
            elif fault == "two_items":
                result["serverBefore"]["slots"][3]["item"]["count"] += 1
                result["serverSourceConsumption"] = 2
            elif fault == "missing_slot":
                result["serverAfter"]["slots"].pop()
                result["serverAfter"]["totalSlots"] = 40
            elif fault == "missing_confirmed": result.pop("serverConfirmed")
            elif fault == "native_unknown": result["outcomeUnknown"] = True
            return result
        if operation == "world.inspect" and not state["placed"]:
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            return {**args, "source": "integrated_server", "registryName": "minecraft:air", "metadata": 0}
        result = await original(operation, args, **options)
        if operation == "bridge.health":
            result["capabilities"] = list(dict.fromkeys(result["capabilities"] + ["player.place_block", "player.plan_approach"]))
            result.update(nativeApproachPlanning=True, approachCurrentOnly=True, approachPlanningIntents=["place_block", "use", "attack"],
                contextRpc={"version": 1, "reads": ["player.plan_approach"], "actions": []})
        elif operation == "client.snapshot":
            add_control(result)
        elif operation == "inventory.inspect":
            result["slots"].extend({"index": index, "item": native.stack({"registryName": "example:armor", "metadata": 7}, 1)
                if index == 40 else None} for index in range(36, 41))
            result["totalSlots"] = 41
            if state["placed"]:
                state["source_sync_reads"] += 1
                if state["source_sync_reads"] <= state["source_sync_delay"]:
                    result["slots"][3]["item"] = copy.deepcopy(state["source_before"])
        elif operation == "player.hotbar":
            state["selected"] = args["slot"]
        elif operation == "machine.inspect":
            result["sessionId"] = "session"
            result["singleBlockRecipeMachine"] = {"schemaVersion": 1, "apiClass": COMMON_BASIC_MACHINE_API,
                "source": "public_runtime_api", "runtimeMember": True,
                "owner": {key: result[key] for key in ("metaTileId", "metaClass", "x", "y", "z", "dimension")}}
            state["charge_reads"] += 1
            stored = 0 if state["charge_reads"] <= state["charge_zero_reads"] else 32
            if state["charge_fault"] == "zero": stored = 0
            elif state["charge_fault"] == "missing": stored = None
            elif state["charge_fault"] == "owner" and state["charge_reads"] > 3:
                result["metaTileId"] += 1
            result["state"]["storedEU"] = stored
        elif operation == "gui.widgets":
            for widget in result.get("widgets", []):
                if widget.get("semantic") == "power_switch":
                    widget["selected"] = int(state["allowed"])
                    if "overlay" in widget:
                        widget["overlay"] = {"location": "gregtech:textures/gui/overlay_button/power_switch_"
                            + ("on" if state["allowed"] else "off") + ".png"}
        return result

    monkeypatch.setattr(client, "call", composed)
    return client, state


@pytest.mark.parametrize("count,details", [(1, False), (2, False), (2, True)])
async def test_generic_from_zero_recovers_all_products_and_uses_post_placement_inventory_baseline(from_zero, count, details):
    client, state = from_zero
    state["player_items"][3]["count"] = count
    state["source_sync_delay"] = 2
    result = await native.invoke(client, declaration(state, includeDetails=details))
    assert result["status"] == "completed", result
    placement = result["singleBlockPlacement"]
    assert placement["placementConfirmed"] and placement["commonBasicMachineAPIVerified"] and placement["clientSourceSynchronized"]
    assert placement["serverTarget"]["metadata"] == 0 and placement["serverTarget"]["gregtech"]["metaTileId"] == state["single_meta_id"]
    assert placement["deductions"][0]["count"] == 1 and placement["sourceCountAfter"] == count - 1
    assert placement["initialCharge"]["observations"] >= 2 and placement["initialCharge"]["storedEU"] > 0
    assert "not guaranteed" in placement["initialCharge"]["scope"]
    assert result["acceptance"]["completeRecovery"] and result["acceptance"]["playerInventoryConserved"]
    assert len(result["acceptance"]["retrievedItems"]) == 2
    assert len([row for row in state["calls"] if row["operation"] == "player.place_block"]) == 1
    assert state["vector_power_count"] == 1 and state["vector_retrieval_count"] == 2
    assert ("serverBefore" in placement["nativeReceipt"]) is details
    assert result["timings_ms"]["preflight"] >= placement["initialCharge"]["elapsed_ms"]


@pytest.mark.parametrize("fault", ["unknown", "identity", "block_metadata_only", "unrelated_nbt", "two_items",
    "missing_slot", "missing_confirmed", "native_unknown"])
async def test_placement_failure_keeps_dedicated_receipt_and_never_places_or_feeds_again(from_zero, fault):
    client, state = from_zero
    state["place_fault"] = fault
    result = await native.invoke(client, declaration(state))
    assert result["status"] == "failed", result
    assert result["singleBlockPlacement"]["placementDispatched"] is True
    assert result["singleBlockPlacement"]["placementConfirmed"] is False
    assert result["singleBlockPlacement"]["actionReceipt"]["operation"] == "player.place_block"
    assert result["outcomeUnknown"] is True
    assert len([row for row in state["calls"] if row["operation"] == "player.place_block"]) == 1
    assert state["queries"] == [] and state["vector_power_count"] == 0


@pytest.mark.parametrize("fault,reason", [("zero", "single_placement_charge_timeout"),
    ("missing", "single_placement_charge_unavailable"), ("owner", "component_controller_identity_changed")])
async def test_first_charge_is_bounded_and_refuses_unknown_owner_without_activation(from_zero, fault, reason):
    client, state = from_zero
    state["charge_fault"] = fault
    args = declaration(state)
    args["singleBlockPlacement"]["powerReadyTimeout_ms"] = 50 if fault == "zero" else 1000
    result = await native.invoke(client, args)
    assert result["status"] == "failed" and result["reason"] == reason, result
    placement = result["singleBlockPlacement"]
    assert placement["placementConfirmed"] is True and "serverBefore" in placement["nativeReceipt"]
    assert placement["initialCharge"]["ready"] is False and placement["initialCharge"]["elapsed_ms"] < 1000
    assert state["queries"] == [] and state["vector_power_count"] == 0


async def test_later_unknown_feed_does_not_discard_confirmed_placement(from_zero, monkeypatch):
    client, state = from_zero
    original = client.call
    async def uncertain(operation, args, **options):
        if operation == "gui.slot_click":
            trace = options.get("trace")
            if trace is not None:
                trace.update(dispatched=True, request_id="unknown-feed", outcome_unknown=True)
            raise BridgeError("BRIDGE_UNAVAILABLE", "Unknown feed.", outcome_unknown=True)
        return await original(operation, args, **options)
    monkeypatch.setattr(client, "call", uncertain)
    result = await native.invoke(client, declaration(state))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    assert result["lastActionReceipt"]["operation"] == "gui.slot_click"
    assert result["singleBlockPlacement"]["placementConfirmed"] is True
    assert result["singleBlockPlacement"]["actionReceipt"]["operation"] == "player.place_block"
    assert "serverBefore" in result["singleBlockPlacement"]["nativeReceipt"]


@pytest.mark.parametrize("change", [{"actualSurvival": False}, {"generateMaterials": True},
    {"inventoryPreparation": "clear"}, {"preflightControls": False}, {"recipeMode": "invalid"}])
def test_explicit_scope_is_rejected_by_schema(from_zero, change):
    _, state = from_zero
    args = declaration(state)
    args.update(change)
    assert not tool_validator("mcb_recipe_once").is_valid(args)


async def test_different_coordinates_refused_before_any_calls(from_zero):
    client, state = from_zero
    args = declaration(state)
    args["input"] = {**args["machine"], "x": args["machine"]["x"] + 1}
    task = _RecipeTask(client, args, run_workflow)
    with pytest.raises(BridgeError) as caught:
        await task.preflight()
    assert caught.value.details["reason"] == "single_placement_scope_unsupported"
    assert state["calls"] == []


@pytest.mark.parametrize("value", [0, 10_001, True])
def test_charge_timeout_schema_is_finite(from_zero, value):
    _, state = from_zero
    args = declaration(state)
    args["singleBlockPlacement"]["powerReadyTimeout_ms"] = value
    assert not tool_validator("mcb_recipe_once").is_valid(args)


@pytest.mark.parametrize("fault", ["source_identity", "source_missing", "cursor", "occupied"])
async def test_source_and_air_preflight_prevents_any_mutation(from_zero, monkeypatch, fault):
    client, state = from_zero
    args = declaration(state)
    if fault == "source_identity": state["player_items"][3]["metadata"] += 1
    elif fault == "source_missing": state["player_items"][3] = None
    elif fault == "cursor": state["cursor"] = native.stack(native.MATERIAL, 1)
    else:
        original = client.call
        async def occupied(operation, values, **options):
            result = await original(operation, values, **options)
            if operation == "world.inspect" and not state["placed"]:
                result["registryName"] = "minecraft:stone"
            return result
        monkeypatch.setattr(client, "call", occupied)
    result = await native.invoke(client, args)
    assert result["status"] == "unavailable", result
    assert not native.writes(state)
    assert state["queries"] == []


@pytest.mark.parametrize("details", [False, True])
async def test_local_rejection_keeps_complete_native_failure_without_replay_or_later_feed(from_zero, details):
    client, state = from_zero
    state["place_fault"] = "local_rejected"
    before = copy.deepcopy(state["player_items"])
    result = await native.invoke(client, declaration(state, includeDetails=details))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    placement = result["singleBlockPlacement"]
    assert placement["placementDispatched"] and not placement["placementConfirmed"]
    assert placement["nativeReceipt"]["accepted"] is False
    assert placement["nativeReceipt"]["lastCheckpoint"] == "local_place_rejected"
    assert placement["nativeReceipt"]["serverBefore"] == placement["nativeReceipt"]["serverAfter"]
    assert placement["nativeReceipt"]["serverPlacementBodyProof"]["wholeTargetCube"] is True
    assert placement["nativeFailureCheckpoint"]["callsCompleted"] == 1
    assert placement["nativeFailureCheckpoint"]["automaticReplayAllowed"] is False
    assert placement["actionReceipt"]["outcomeUnknown"] is True
    assert state["player_items"] == before and state["placed"] is False
    assert len([row for row in state["calls"] if row["operation"] == "player.place_block"]) == 1
    assert state["queries"] == [] and state["vector_power_count"] == 0
    assert "initialCharge" not in placement


async def test_custom_runner_malformed_placement_checkpoint_remains_unknown_and_is_not_retained(from_zero):
    client, state = from_zero
    state["place_fault"] = "malformed_checkpoint"
    result = await native.invoke(client, declaration(state))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True, result
    placement = result["singleBlockPlacement"]
    assert placement["placementDispatched"] and not placement["placementConfirmed"]
    assert "nativeReceipt" not in placement and "nativeFailureCheckpoint" not in placement
    assert len([row for row in state["calls"] if row["operation"] == "player.place_block"]) == 1
    assert state["queries"] == [] and state["vector_power_count"] == 0
