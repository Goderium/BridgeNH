"""Offline protocol tests; no client, world, QA fixture or production mutation."""

import copy
import time

import pytest

import test_construction_tasks as construction
import test_native_approach_tasks as planner
import test_recipe_tasks as recipes
import test_multi_recipe_tasks as multi
from minecraft_codex_bridge.bridge import BridgeError, response_context
from minecraft_codex_bridge.construction_tasks import _ConstructionTask, _survival_deductions
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.server import make_server
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import project, run_workflow

native_construction = construction.native_construction
native_bridge = recipes.native_bridge


def test_mcp_initialize_instructions_match_actual_survival_and_preserve_defaults(native_construction):
    client, _ = native_construction
    instructions = make_server(client).create_initialization_options().instructions
    assert "generateMaterials=false" in instructions and "without clear or grant" in instructions
    assert 0 < len(instructions.encode("utf-8")) <= 4096
    assert "grounded native controls" in instructions and "nativeDone do not prove formation/production" in instructions
    assert "from-zero outer time including preparation, waits and recovery" in instructions
    assert "ports_first" in TOOL_BY_NAME["mcb_blueprint_build"].schema["properties"]["constructionOrder"]["description"]
    assert "survivalConstruct" in TOOL_BY_NAME["mcb_survival_build"].description
    assert "public_multi" in TOOL_BY_NAME["mcb_recipe_once"].description
    assert "With generateMaterials=true" in TOOL_BY_NAME["mcb_recipe_once"].description
    assert "defaults to clearing" not in instructions


def task(client, **changes):
    return _ConstructionTask(client, construction.declaration(**changes), run_workflow, started=time.monotonic())


def install_shell_geometry(instance, receipt=None):
    """Accounting fixtures provide separate explicit outside native body observations."""
    target = instance.args["controller"]
    instance.capture = {"captureId": "offline-accounting-capture", "capturedFacing": "NORTH_NORMAL_NONE"}
    instance.bounds = {"worldXYZ": {"min": dict(target), "max": dict(target)}}
    instance.approach_health = {"approachBuildEnvelopeVersion": 1, "survivalBuildEnvelopeVersion": 1}
    feet = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - .5}
    box = {"minX": feet["x"] - .3, "minY": feet["y"] + .00001, "minZ": feet["z"] - .3,
        "maxX": feet["x"] + .3, "maxY": feet["y"] + 1.8, "maxZ": feet["z"] + .3}
    plan = {"status": "ready", "currentOnly": True, "selected": {"feet": feet, "moveRequired": False,
        "attachment": {**target, "side": 2}, "clientBody": {"body": copy.deepcopy(box)}, "serverBody": {"body": copy.deepcopy(box)}}}
    async def current(actual, **kwargs):
        assert actual == target and kwargs == {"allowed_sides": (2,), "excluded_build_envelope": instance.shell_envelope()}
        return copy.deepcopy(plan)
    async def planned(actual, **kwargs):
        await current(actual, **kwargs)
        return target, 2, []
    instance.resolved_native_approach = instance.current_native_approach = current
    instance.planned_approach = planned
    if receipt is not None:
        for side in ("client", "server"):
            receipt[side + "BuildBodyProof"] = {"version": 1, "source": side, "playerId": "actual-player", "dimension": instance.dimension,
                "actualNativeBody": copy.deepcopy(box), "outside": True, "formationProven": False,
                "excludedBuildEnvelope": {key: bound + int(key.startswith("max")) for key, bound in instance.shell_envelope().items()}}


def server_inventory(count=1):
    slots = [{"index": index, "item": None} for index in range(41)]
    slots[2]["item"] = {"registryName": "example:material", "metadata": 9,
        "count": count, "hasTag": False, "nbtIdentity": "null"}
    slots[40]["item"] = {"registryName": "example:armor", "metadata": 0,
        "count": 1, "hasTag": True, "nbtIdentity": "{immutable:1}"}
    return {"source": "integrated_server", "inventorySource": "actual_player_inventory", "complete": True,
        "totalSlots": 41, "truncated": False, "cursorItem": None, "slots": slots}


@pytest.mark.parametrize("count", [1, 2, 64])
def test_authoritative_one_item_deduction_accepts_final_empty_slot(count):
    before = server_inventory(count)
    after = copy.deepcopy(before)
    if count == 1:
        after["slots"][2]["item"] = None
    else:
        after["slots"][2]["item"]["count"] -= 1
    result = _survival_deductions(before, after)
    assert result == [{"playerInventoryIndex": 2, "registryName": "example:material", "metadata": 9,
        "count": 1, "sourceCountBefore": count, "sourceCountAfter": count - 1, "nbtIdentity": "null"}]


@pytest.mark.parametrize("fault", ["client_source", "truncated", "missing_slot", "duplicate_slot", "cursor",
    "missing_nbt", "unrelated_nbt", "changed_identity", "count_increase", "zero_count", "bool_count", "new_item"])
def test_incomplete_or_unexplained_authoritative_inventory_changes_refused(fault):
    before, after = server_inventory(2), server_inventory(1)
    if fault == "client_source": after["source"] = "client"
    elif fault == "truncated": after["truncated"] = True
    elif fault == "missing_slot": after["slots"].pop(); after["totalSlots"] = 40
    elif fault == "duplicate_slot": after["slots"][-1]["index"] = 0
    elif fault == "cursor": after["cursorItem"] = copy.deepcopy(before["slots"][2]["item"])
    elif fault == "missing_nbt": after["slots"][40]["item"].pop("nbtIdentity")
    elif fault == "unrelated_nbt": after["slots"][40]["item"]["nbtIdentity"] = "{tampered:1}"
    elif fault == "changed_identity": after["slots"][2]["item"]["metadata"] = 8
    elif fault == "count_increase": after["slots"][2]["item"]["count"] = 3
    elif fault == "zero_count": after["slots"][2]["item"]["count"] = 0
    elif fault == "bool_count": after["slots"][2]["item"]["count"] = True
    else: after["slots"][3]["item"] = copy.deepcopy(before["slots"][2]["item"])
    with pytest.raises(BridgeError):
        _survival_deductions(before, after)


@pytest.mark.parametrize("preparation", [None, "minimal", "preserve"])
def test_false_generation_enforces_preserve_without_command_support(native_construction, preparation):
    client, _ = native_construction
    arguments = construction.declaration()
    if preparation is None: arguments.pop("inventoryPreparation")
    else: arguments["inventoryPreparation"] = preparation
    instance = _ConstructionTask(client, arguments, run_workflow, started=time.monotonic())
    assert instance.inventory_preparation_mode == "preserve" and instance.support_mode == "player"


@pytest.mark.parametrize("method", ["prepare_cleared_inventory", "clear_player_inventory"])
async def test_false_generation_rejected_before_clear_helper_reads_or_writes(native_construction, method):
    client, state = native_construction
    instance = task(client)
    baseline = len(state["calls"])
    with pytest.raises(BridgeError) as failure:
        await getattr(instance, method)()
    assert failure.value.details["reason"] == "inventory_clear_not_authorized"
    assert len(state["calls"]) == baseline


@pytest.mark.parametrize("change,reason", [({"generateMaterials": True}, "survival_material_generation_forbidden"),
    ({"inventoryPreparation": "clear"}, "inventory_clear_not_authorized"),
    ({"constructionOrder": "shell_first"}, "survival_construction_order_unsupported"),
    ({"supportMode": "command"}, "survival_construction_order_unsupported"),
    ({"placement": {}}, "survival_placement_calibration_unsupported")])
async def test_survival_unsafe_preparation_refused_before_any_calls(native_construction, change, reason):
    client, state = native_construction
    instance = task(client, actualSurvival=True, **change)
    baseline = len(state["calls"])
    with pytest.raises(BridgeError) as failure:
        await instance.preflight()
    assert failure.value.details["reason"] == reason
    assert len(state["calls"]) == baseline


def test_actual_survival_defaults_to_literal_air_ports_first(native_construction):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    assert instance.construction_order == "ports_first"
    assert instance.inventory_preparation_mode == "preserve" and instance.support_mode == "player"


async def test_remaining_material_count_zero_is_actual_empty(native_construction):
    client, state = native_construction
    instance = task(client, actualSurvival=True)
    instance.materials[2] = {"item": copy.deepcopy(construction.PORT_ITEMS["item_input"]), "count": 1}
    instance.survival_inventory = [{"index": index, "item": copy.deepcopy(state["items"].get(index))} for index in range(41)]
    state["items"][2] = None
    await instance.verify_material(2, consumed=1)
    assert instance.materials[2]["count"] == 0 and instance.survival_inventory[2]["item"] is None


@pytest.mark.parametrize("fault", [None, "client_creative", "server_creative", "client_flying", "server_flying", "server_stale"])
def test_survival_requires_both_real_modes(native_construction, fault):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    snapshot = {"player": {"creative": False, "flying": False, "eyeY": 3.62, "feetY": 2,
        "serverFlight": {"available": True, "creative": False, "flying": False, "dimension": 0}}}
    if fault == "client_creative": snapshot["player"]["creative"] = True
    elif fault == "server_creative": snapshot["player"]["serverFlight"]["creative"] = True
    elif fault == "client_flying": snapshot["player"]["flying"] = True
    elif fault == "server_flying": snapshot["player"]["serverFlight"]["flying"] = True
    elif fault == "server_stale": snapshot["player"]["serverFlight"]["available"] = False
    if fault is None: assert instance.require_flight(snapshot) == pytest.approx(1.62)
    else:
        with pytest.raises(BridgeError): instance.require_flight(snapshot)


@pytest.mark.parametrize("fault", [None, "unsupported_server_body", "unsupported_client_body", "creative"])
def test_grounded_native_receipt_requires_can_stand_on_both_sides(native_construction, fault):
    client, state = native_construction
    instance = task(client, actualSurvival=True)
    state["flying"] = False
    state["blocks"][construction.key(construction.CONTROLLER)] = {
        "registryName": "gregtech:gt.blockmachines", "metadata": 0,
        "itemMetadata": construction.CONTROLLER_ITEM["metadata"]}
    args = {**construction.CONTROLLER, "dimension": 0, "expectedWorld": instance.args["expectedWorld"], "intent": "use",
        "pattern": "surround", "allowedSides": [2, 3, 4, 5]}
    receipt = planner.composite_receipt(state, args)
    receipt["serverFlight"]["creative"] = False
    snapshot = response_context(receipt)["snapshot"]
    snapshot["player"]["creative"] = False
    snapshot["player"]["serverFlight"]["creative"] = False
    if fault == "unsupported_server_body": receipt["selected"]["serverBody"]["canStand"] = False
    elif fault == "unsupported_client_body": receipt["selected"]["clientBody"]["canStand"] = False
    elif fault == "creative": receipt["serverFlight"]["creative"] = True
    if fault is None:
        instance.validate_native_approach(receipt, construction.CONTROLLER, intent="use", pattern="surround",
            allowed_sides=(2, 3, 4, 5), hotbar_slot=None, target_identity=None)
    else:
        with pytest.raises(BridgeError):
            instance.validate_native_approach(receipt, construction.CONTROLLER, intent="use", pattern="surround",
                allowed_sides=(2, 3, 4, 5), hotbar_slot=None, target_identity=None)


def ordinary_gui():
    slots = [{"slot": index + 9, "nativeContainerSlotId": index + 9, "playerInventoryIndex": index,
        "playerInventoryBindingVerified": True, "inventorySource": "current_player_inventory",
        "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot", "item": None} for index in range(36)]
    return {"screenId": "current", "screen": "net.minecraft.client.gui.inventory.GuiInventory", "windowId": 0,
        "playerOpenWindowId": 0, "containerSource": "current_client_gui", "openContainerMatches": True,
        "totalSlots": len(slots), "truncated": False, "slots": slots}


@pytest.mark.parametrize("fault", [None, "missing_binding", "native_duplicate", "wrapper_binding", "screen", "truncated"])
async def test_survival_uses_only_unique_actual_player_slot_bindings(native_construction, fault):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    gui = ordinary_gui()
    if fault == "missing_binding": gui["slots"][0]["playerInventoryBindingVerified"] = False
    elif fault == "native_duplicate": gui["slots"][1]["slot"] = gui["slots"][1]["nativeContainerSlotId"] = 9
    elif fault == "wrapper_binding": gui["slots"][0]["inventoryBinding"] = "creative_wrapper_index"
    elif fault == "screen": gui["screen"] = "net.minecraft.client.gui.inventory.GuiContainerCreative"
    elif fault == "truncated": gui["truncated"] = True
    async def observe(*args, **kwargs): return gui
    instance.call = observe
    if fault is None:
        mapped = await instance.creative_player_widgets("current")
        assert set(mapped) == set(range(36)) and mapped[0]["nativeContainerSlotId"] == 9
    else:
        with pytest.raises(BridgeError): await instance.creative_player_widgets("current")


def test_survival_batch_schema_is_finite_and_forbids_generation():
    args = {"expectedSessionId": "session", "expectedWorld": "Test", "dimension": 0,
        "x": 0, "y": 2, "z": 0, "blueprintHotbarSlot": 0, "side": 2,
        "elementBudget": 65536, "maxBatches": 32, "timeout_ms": 8000}
    validator = tool_validator("mcb_survival_build")
    validator.validate(args)
    for field, value in [("elementBudget", 65537), ("maxBatches", 33), ("timeout_ms", 8001), ("generateMaterials", True)]:
        assert list(validator.iter_errors({**args, field: value}))


def test_action_receipt_projection_keeps_empty_authoritative_slots():
    value = server_inventory()
    step = {"kind": "action", "fields": ["playerInventoryAfter"], "omitEmptySlots": False}
    assert project({"playerInventoryAfter": value}, step)["values"]["playerInventoryAfter"] == value


async def test_survival_recipe_requires_complete_public_multi_recovery(native_construction):
    client, state = native_construction
    instance = _RecipeTask(client, recipes.declaration(actualSurvival=True), run_workflow)
    baseline = len(state["calls"])
    with pytest.raises(BridgeError) as failure: await instance.preflight()
    assert failure.value.details["reason"] == "survival_complete_recovery_required"
    assert len(state["calls"]) == baseline


@pytest.mark.parametrize("fault", [None, "large_budget", "oversized_budget", "no_progress", "unsupported", "partial", "unknown", "accounting", "generation",
    "clear", "actor", "session", "blueprint", "batch_accounting", "native_return", "unsupported_return", "committed_delta", "player_delta", "batch_cancellation",
    "body_inside", "body_actor", "body_envelope", "body_missing", "body_version_bool",
    "tail", "tail_unknown", "tail_delta", "tail_inflight"])
async def test_one_bounded_native_batch_conserves_materials_or_stops_without_replay(native_construction, fault, monkeypatch):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    if fault in ("large_budget", "oversized_budget"):
        instance.args["survivalBuild"] = {"elementBudget": 65536}
    instance.survival_player_id = "actual-player"
    instance.materials[0] = {"item": construction.BLUEPRINT.copy(), "count": 1}
    before, after = server_inventory(2), server_inventory(1)
    blueprint = {**construction.BLUEPRINT, "count": 1, "hasTag": False, "nbtIdentity": "null"}
    before["slots"][0]["item"] = copy.deepcopy(blueprint)
    after["slots"][0]["item"] = copy.deepcopy(blueprint)
    material = {**before["slots"][2]["item"], "count": 1}
    receipt = {"operation": "structure.survival_build", "status": "native_done", "actualSurvival": True,
        "sessionId": instance.args["expectedSessionId"], "expectedWorld": instance.args["expectedWorld"], "dimension": 0,
        "controller": {**instance.args["controller"], "dimension": 0, "registryName": construction.CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": construction.CONTROLLER_ITEM["metadata"], "metaClass": "example.PublicMultiblock"}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0,
            "creative": False, "actualSurvival": True, "flying": False, "selectedHotbarSlot": 0}, "blueprint": blueprint,
        "nativeDone": True, "materialAccountingComplete": True, "formationVerified": False, "partial": False,
        "outcomeUnknown": False, "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after,
        "batches": [{"nativeReturn": -1, "requestedBudget": 8, "configuredCooldownMillis": 200,
            "accountingComplete": True, "committedItems": [{"item": copy.deepcopy(material), "amount": 1}],
            "playerDeductions": [{"item": copy.deepcopy(material), "amount": 1}]}]}
    if fault in ("no_progress", "unsupported"): receipt["status"] = fault
    elif fault == "large_budget": receipt["batches"][0]["requestedBudget"] = 65536
    elif fault == "oversized_budget": receipt["batches"][0]["requestedBudget"] = 65537
    elif fault == "partial": receipt["partial"] = True
    elif fault == "unknown": receipt["outcomeUnknown"] = True
    elif fault == "accounting": receipt["materialAccountingComplete"] = False
    elif fault == "generation": receipt["generationPerformed"] = True
    elif fault == "clear": receipt["inventoryCleared"] = True
    elif fault == "actor": receipt["actor"]["creative"] = True
    elif fault == "session": receipt["sessionId"] = "other-session"
    elif fault == "blueprint": receipt["blueprint"]["count"] = 2
    elif fault == "batch_accounting": receipt["batches"][0]["accountingComplete"] = False
    elif fault == "native_return": receipt["batches"][0]["nativeReturn"] = 0
    elif fault == "unsupported_return": receipt["batches"][0]["nativeReturn"] = -2
    elif fault == "committed_delta": receipt["batches"][0]["committedItems"][0]["amount"] = 2
    elif fault == "player_delta": receipt["batches"][0]["playerDeductions"][0]["amount"] = 2
    elif fault == "batch_cancellation":
        first = copy.deepcopy(receipt["batches"][0])
        first.update(nativeReturn=1, playerDeductions=[])
        receipt["batches"][0]["committedItems"] = []
        receipt["batches"].insert(0, first)
    calls = []
    tail_calls = []
    if fault and fault.startswith("tail"):
        instance.args["survivalBuild"] = {"finishAirTail": True}
        receipt.update(status="no_progress", nativeDone=False, partial=True, callsStarted=2, callsCompleted=2)
        receipt["batches"][0]["nativeReturn"] = 1
        receipt["batches"].append({"nativeReturn": 0, "requestedBudget": 8, "configuredCooldownMillis": 200,
            "accountingComplete": True, "committedItems": [], "playerDeductions": []})
        if fault == "tail_unknown": receipt["outcomeUnknown"] = True
        if fault == "tail_delta": receipt["batches"][0]["committedItems"][0]["amount"] = 2
        if fault == "tail_inflight": receipt["callsStarted"] = 3
        async def tail(current):
            assert current is instance
            assert current.survival_material_ledger[0]["deductions"][0]["count"] == 1
            assert current.survival_inventory[2]["item"]["count"] == 1
            tail_calls.append(current)
        monkeypatch.setattr("minecraft_codex_bridge.construction_tail.finish_air_tail", tail)
    async def approach(*args, **kwargs): return instance.args["controller"], 2, []
    async def workflow(phase, steps, **kwargs):
        actions = [row for row in steps if row.get("tool") == "mcb_survival_build"]
        assert len(actions) == 1
        calls.append(actions[0])
        return {"steps": [{"id": "build", "observation": project(receipt, actions[0])}]}
    async def inventory(*args, **kwargs): return after
    instance.approach, instance.workflow, instance.call = approach, workflow, inventory
    install_shell_geometry(instance, receipt)
    if fault == "body_inside":
        target = instance.args["controller"]
        receipt["clientBuildBodyProof"]["actualNativeBody"] = {
            "minX": target["x"] + .2, "maxX": target["x"] + .8, "minY": target["y"], "maxY": target["y"] + 1.8,
            "minZ": target["z"] + .2, "maxZ": target["z"] + .8}
    elif fault == "body_actor": receipt["serverBuildBodyProof"]["playerId"] = "foreign-player"
    elif fault == "body_envelope": receipt["serverBuildBodyProof"]["excludedBuildEnvelope"]["maxX"] += 1
    elif fault == "body_missing": receipt.pop("clientBuildBodyProof")
    elif fault == "body_version_bool": receipt["serverBuildBodyProof"]["version"] = True
    if fault in (None, "large_budget", "tail"):
        await instance.blueprint_use(build=True)
        assert instance.survival_material_ledger[0]["deductions"][0]["count"] == 1
        assert instance.survival_material_ledger[0]["nativeDoneIsNotFormation"] is True
    else:
        with pytest.raises(BridgeError): await instance.blueprint_use(build=True)
    assert len(calls) == 1 and calls[0]["args"]["maxBatches"] == 32
    assert len(tail_calls) == (1 if fault == "tail" else 0)
    if fault and fault.startswith("body_"):
        assert instance.survival_material_ledger == []
    assert calls[0]["args"]["excludedBuildEnvelope"] == instance.shell_envelope()
    assert not any(name in calls[0]["args"] for name in ("generateMaterials", "clear", "fly"))


def test_gui_close_and_hotbar_wait_for_new_server_sample_without_fixed_delay(native_construction):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    steps = instance.survival_server_barriers([
        {"id": "close", "kind": "action", "tool": "mcb_close_gui", "args": {}},
        {"id": "select", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 3}},
        {"id": "use", "kind": "action", "tool": "mcb_interact", "args": {"action": "use"}}])
    fresh = [row for row in steps if row.get("kind") == "wait" and row["condition"]["path"] == "serverControl.sampleId"]
    assert len(fresh) == 2 and all(row["condition"]["operator"] == "gt" and "valueFrom" in row["condition"] for row in fresh)
    ready = [row for row in steps if row.get("kind") == "wait" and row["condition"]["path"] == "serverControl"]
    assert ready[0]["condition"]["value"]["openContainer.isInventoryContainer"] is True
    assert ready[1]["condition"]["value"]["selectedHotbarSlot"] == 3
    assert all(row["fields"] == ["serverControl"] for row in steps if row.get("tool") == "mcb_snapshot")
    synchronization_asserts = {row["id"]: row for row in steps if row.get("kind") == "assert"}
    assert synchronization_asserts["close_sync_client"]["condition"]["path"] == "serverControl.clientContainer.isInventoryContainer"
    assert synchronization_asserts["close_sync_window"]["condition"]["path"] == "serverControl.clientContainer.windowId"
    assert synchronization_asserts["select_sync_selection"]["condition"]["path"] == "serverControl.selectionSynchronized"
    assert not any(row.get("kind") == "sleep" for row in steps)
    assert steps[-1]["id"] == "use"


@pytest.mark.parametrize("consumed", [0, 11, 44])
@pytest.mark.parametrize("fault", [None, "unrelated_nbt", "record_count", "record_identity", "final_count", "final_unrelated"])
async def test_native_shell_updates_borrowed_material_expectation_from_exact_slot_ledger(native_construction, consumed, fault):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    instance.survival_player_id = "actual-player"
    instance.materials[0] = {"item": construction.BLUEPRINT.copy(), "count": 1}
    before = server_inventory(44)
    before["slots"][9]["item"], before["slots"][2]["item"] = before["slots"][2]["item"], None
    blueprint = {**construction.BLUEPRINT, "count": 1, "hasTag": False, "nbtIdentity": "null"}
    before["slots"][0]["item"] = copy.deepcopy(blueprint)
    after = copy.deepcopy(before)
    after["slots"][9]["item"] = None if consumed == 44 else {**after["slots"][9]["item"], "count": 44 - consumed}
    instance.survival_inventory = copy.deepcopy(before["slots"])
    borrowed = {"hotbarIndex": 6, "storageIndex": 9, "item": {"registryName": "example:material", "metadata": 9},
        "count": 44, "movementDispatched": True, "movementConfirmed": True, "status": "borrowed"}
    instance.borrowed_inventory.append(borrowed)
    rows = [{"item": {**before["slots"][9]["item"], "count": 1}, "amount": consumed}] if consumed else []
    receipt = {"operation": "structure.survival_build", "status": "native_done", "actualSurvival": True,
        "sessionId": instance.args["expectedSessionId"], "expectedWorld": instance.args["expectedWorld"], "dimension": 0,
        "controller": {**instance.args["controller"], "dimension": 0, "registryName": construction.CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": construction.CONTROLLER_ITEM["metadata"], "metaClass": "example.PublicMultiblock"}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0,
            "creative": False, "actualSurvival": True, "flying": False, "selectedHotbarSlot": 0}, "blueprint": blueprint,
        "nativeDone": True, "materialAccountingComplete": True, "formationVerified": False, "partial": False,
        "outcomeUnknown": False, "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after,
        "batches": [{"nativeReturn": -1, "requestedBudget": 64, "configuredCooldownMillis": 300,
            "accountingComplete": True, "committedItems": copy.deepcopy(rows), "playerDeductions": copy.deepcopy(rows)}]}
    if fault == "unrelated_nbt": after["slots"][40]["item"]["nbtIdentity"] = "{tampered:1}"
    elif fault == "record_count": borrowed["count"] = 43
    elif fault == "record_identity": borrowed["item"]["metadata"] = 8
    calls = []

    async def approach(*args, **kwargs): return instance.args["controller"], 2, []
    async def workflow(phase, steps, **kwargs):
        action = next(row for row in steps if row.get("tool") == "mcb_survival_build")
        calls.append(action)
        return {"steps": [{"id": "build", "observation": project(receipt, action)}]}
    async def inventory(*args, **kwargs): return after
    instance.approach, instance.workflow, instance.call = approach, workflow, inventory
    install_shell_geometry(instance, receipt)
    if fault == "unrelated_nbt" or consumed and fault in ("record_count", "record_identity"):
        with pytest.raises(BridgeError): await instance.blueprint_use(build=True)
    else:
        await instance.blueprint_use(build=True)
        if fault == "final_count":
            after["slots"][9]["item"] = {**before["slots"][9]["item"], "count": max(1, 45 - consumed)}
        elif fault == "final_unrelated":
            after["slots"][40]["item"]["metadata"] = 1
        if fault:
            with pytest.raises(BridgeError): await instance.verify_retained_inventory()
        else:
            await instance.verify_retained_inventory()
            assert borrowed["count"] == 44 - consumed and instance.retained_inventory_verified
            assert borrowed.get("nativeConsumedCount", 0) == consumed
            if consumed: assert borrowed["movedCount"] == 44 and borrowed["survivalMaterialLedgerIndices"] == [0]
            if consumed == 44: assert borrowed["restorationConfirmed"] is True and borrowed["status"] == "consumed"
            else: assert borrowed["retentionConfirmed"] is True and not borrowed.get("restorationConfirmed")
            assert after["slots"][40]["item"] == before["slots"][40]["item"] and after["cursorItem"] is None
            restored_counts = []
            async def nothing(*args, **kwargs): return None
            async def open_inventory(*args, **kwargs): return "screen:inventory", {}
            async def return_remaining(record, screen_id, source, destination, phase, **kwargs):
                assert screen_id == "screen:inventory" and (source, destination) == (9, 6)
                assert record["count"] == after["slots"][source]["item"]["count"] == 44 - consumed
                restored_counts.append(record["count"])
                after["slots"][destination]["item"] = after["slots"][source]["item"]
                after["slots"][source]["item"] = None
                instance.survival_inventory = copy.deepcopy(after["slots"])
            instance.return_controller_stack = instance.cleanup_supports = nothing
            instance.open_creative_player_inventory = open_inventory
            instance.move_native_player_stack = return_remaining
            await instance.restore()
            assert restored_counts == ([] if consumed == 44 else [44 - consumed])
            assert instance.inventory_restored and borrowed["restorationConfirmed"] is True
            assert borrowed.get("nativeConsumedCount", 0) == consumed
    assert len(calls) == 1


@pytest.fixture
def nested_server_control_bridge(native_bridge, monkeypatch):
    """Mirror the live nested DTO; never invent root-level synchronization fields."""
    client, state = native_bridge
    original = client.call
    state.update(open="input", window=7, control_sample=0, control_selected=0, control_fault=None,
        control_observations=[])

    async def observed(operation, args, **kwargs):
        result = await original(operation, args, **kwargs)
        if operation == "player.hotbar":
            state["control_selected"] = args["slot"]
        if operation == "client.snapshot":
            state["control_sample"] += 1
            container = {"windowId": state["window"], "isInventoryContainer": state["open"] is None}
            control = {"available": True, "source": "integrated_server_tick", "sessionId": "session",
                "worldName": "test-world", "worldFolder": "test-world", "dimension": 180,
                "sampleId": state["control_sample"], "ageMillis": 0, "playerId": "actual-player",
                "openContainer": container.copy(), "clientContainer": container.copy(),
                "selectedHotbarSlot": state["control_selected"], "selectionSynchronized": True}
            fault = state["control_fault"]
            if state["open"] is None:
                if fault == "client_missing": control.pop("clientContainer")
                elif fault == "client_not_inventory": control["clientContainer"]["isInventoryContainer"] = False
                elif fault == "client_window": control["clientContainer"]["windowId"] = 7
            if state["control_selected"] == 3:
                if fault == "selection_false": control["selectionSynchronized"] = False
                elif fault == "selection_missing": control.pop("selectionSynchronized")
            result["serverControl"] = control
            assert "clientContainer" not in result and "selectionSynchronized" not in result
            state["control_observations"].append(copy.deepcopy(result))
        return result

    monkeypatch.setattr(client, "call", observed)
    return client, state


async def test_survival_close_and_hotbar_execute_with_actual_nested_server_control(nested_server_control_bridge):
    client, state = nested_server_control_bridge
    instance = _RecipeTask(client, recipes.declaration(actualSurvival=True), run_workflow)
    report = await instance.workflow("nested_container_contract", [
        {"id": "close", "kind": "action", "tool": "mcb_close_gui", "args": {}},
        {"id": "select", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 3}}])
    assert report["status"] == "completed" and report["acknowledgedActions"] == 2, report
    assert state["open"] is None and state["control_selected"] == 3
    assert [row["operation"] for row in recipes.writes(state)] == ["gui.close", "player.hotbar"]
    steps = {row["id"]: row for row in report["steps"]}
    for prefix in ("close", "select"):
        before = steps[prefix + "_sync_before"]["observation"]["values"]["serverControl"]
        ready = steps[prefix + "_sync_ready"]["observation"]["values"]["serverControl"]
        assert ready["sampleId"] > before["sampleId"]
        assert all(steps[prefix + suffix]["matched"] for suffix in
            (("_sync_current", "_sync_client", "_sync_window") if prefix == "close" else
             ("_sync_current", "_sync_selection")))


@pytest.mark.parametrize("tool,fault", [("mcb_close_gui", "client_missing"),
    ("mcb_close_gui", "client_not_inventory"), ("mcb_close_gui", "client_window"),
    ("mcb_hotbar", "selection_false"), ("mcb_hotbar", "selection_missing")])
async def test_nested_sync_failure_stops_after_single_native_action(nested_server_control_bridge, tool, fault):
    client, state = nested_server_control_bridge
    state["control_fault"] = fault
    instance = _RecipeTask(client, recipes.declaration(actualSurvival=True), run_workflow)
    with pytest.raises(BridgeError) as failure:
        await instance.workflow("nested_container_failure", [
            {"id": "mutation", "kind": "action", "tool": tool, "args": {"slot": 3} if tool == "mcb_hotbar" else {}},
            {"id": "later", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 4}}])
    assert failure.value.code == "ASSERTION_FAILED"
    assert len(recipes.writes(state)) == 1
    assert state["control_selected"] != 4
    assert state["control_observations"]


@pytest.mark.parametrize("fault", [None, "cache_stale", "session", "player_missing", "dimension", "source"])
def test_server_control_gate_requires_fresh_actual_epoch(native_construction, fault):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    control = {"available": True, "source": "integrated_server_tick", "sessionId": instance.args["expectedSessionId"],
        "worldName": instance.args["expectedWorld"], "dimension": 0, "sampleId": 7, "ageMillis": 0, "playerId": "actual-player"}
    if fault == "cache_stale": control["ageMillis"] = 1001
    elif fault == "session": control["sessionId"] = "other-session"
    elif fault == "player_missing": control.pop("playerId")
    elif fault == "dimension": control["dimension"] = 1
    elif fault == "source": control["source"] = "client"
    if fault is None: instance.require_survival_server_control({"serverControl": control})
    else:
        with pytest.raises(BridgeError): instance.require_survival_server_control({"serverControl": control})


def test_server_control_keeps_actual_player_uuid_without_claiming_respawn_entity(native_construction):
    client, _ = native_construction
    instance = task(client, actualSurvival=True)
    control = {"available": True, "source": "integrated_server_tick", "sessionId": instance.args["expectedSessionId"],
        "worldName": instance.args["expectedWorld"], "dimension": 0, "sampleId": 7, "ageMillis": 0, "playerId": "actual-player"}
    instance.require_survival_server_control({"serverControl": control})
    control.update(sampleId=8, playerId="other-player")
    with pytest.raises(BridgeError) as failure: instance.require_survival_server_control({"serverControl": control})
    assert failure.value.details["reason"] == "survival_player_identity_mismatch"


@pytest.mark.parametrize("fault", [None, 0, -1, True, float("nan"), float("inf"), "missing", "dead_screen", "dead_entity"])
def test_live_player_gate_never_accepts_death_era_inventory(fault):
    snapshot = {"player": {"health": 20}, "screen": ""}
    if fault == "dead_screen": snapshot["screen"] = "net.minecraft.client.gui.GuiGameOver"
    elif fault == "dead_entity": snapshot["player"]["isDead"] = True
    elif fault == "missing": snapshot["player"].pop("health")
    elif fault is not None: snapshot["player"]["health"] = fault
    if fault is None:
        _RecipeTask.require_live_player(snapshot)
    else:
        with pytest.raises(BridgeError) as failure: _RecipeTask.require_live_player(snapshot)
        assert failure.value.details["reason"] == "live_player_required"


@pytest.mark.parametrize("method", ["preflight", "prepare_cleared_inventory", "clear_player_inventory", "workflow"])
async def test_dead_current_player_refused_before_native_writes(native_construction, method):
    client, state = native_construction
    state["health"] = 0
    instance = task(client, **({"actualSurvival": True} if method == "workflow" else {"generateMaterials": True}))
    instance.capabilities = state["caps"]
    with pytest.raises(BridgeError) as failure:
        if method == "workflow":
            await instance.workflow("test_mutation", [{"id": "clear", "kind": "action", "tool": "mcb_chat",
                "args": {"message": "/clear TestPlayer"}}])
        else:
            await getattr(instance, method)()
    assert failure.value.details["reason"] == "live_player_required"
    assert not construction.writes(state)


async def test_native_construction_control_phases_fit_existing_plan_budget(native_construction, monkeypatch):
    client, _ = native_construction
    original = _RecipeTask.workflow
    plans = []
    async def measured(instance, phase, steps, **kwargs):
        plans.append((phase, len(instance.survival_server_barriers(steps))))
        return await original(instance, phase, steps, **kwargs)
    monkeypatch.setattr(_RecipeTask, "workflow", measured)
    result = await construction.invoke(client, construction.declaration(constructionOrder="ports_first"))
    assert result["status"] == "completed", result
    assert plans and all(count <= 32 for _, count in plans), plans


async def test_public_multi_control_phases_fit_existing_plan_budget(native_bridge, monkeypatch):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, retained=False)
    original = _RecipeTask.workflow
    plans = []
    async def measured(instance, phase, steps, **kwargs):
        plans.append((phase, len(instance.survival_server_barriers(steps))))
        return await original(instance, phase, steps, **kwargs)
    monkeypatch.setattr(_RecipeTask, "workflow", measured)
    result = await recipes.invoke(client, multi.arguments())
    assert result["status"] == "completed", result
    assert plans and all(count <= 32 for _, count in plans), plans
