"""Goal declarations retain authoritative inventory, group and volume acceptance."""

import copy
import time

import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import _ConstructionTask, _GROUP_SELECTION_BINDING
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow

native_construction = native.native_construction


def declared_setup(state, *, empty=True, **changes):
    arguments = native.declaration(inventoryPreparation="minimal", generateMaterials=True, **changes)
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    arguments["declaredSupplies"] = [{"hotbarSlot": 0, "item": native.BLUEPRINT.copy(), "count": 1},
        *[{"hotbarSlot": slot, "item": native.PORT_ITEMS[role].copy(), "count": 1}
            for role, slot in native.PORT_SLOTS.items()],
        {"hotbarSlot": 1, "item": native.CONTROLLER_ITEM.copy(), "count": 1}]
    if empty:
        state["items"] = {index: None for index in range(41)}
    else:
        state["items"][0]["count"] = 1
        state["items"][6] = None
    return arguments


def observe_hook(monkeypatch, client, hook):
    original = client.call

    async def wrapped(operation, arguments, **kwargs):
        result = await original(operation, arguments, **kwargs)
        hook(operation, arguments, result)
        return result

    monkeypatch.setattr(client, "call", wrapped)


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_empty_inventory_declared_goals_supply_and_form_inside_one_task(native_construction, order):
    client, state = native_construction
    arguments = declared_setup(state, constructionOrder=order)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["materialGenerationRequested"] is result["declaredSuppliesVerified"] is True
    assert result["inventoryClearConfirmed"] is result["generatedMaterials"] is True
    clear_receipt = result["inventoryClearReceipt"]
    assert clear_receipt["status"] == "confirmed" and clear_receipt["containerAckConfirmed"] is True
    assert clear_receipt["clearCommandDispatched"] is clear_receipt["clearConfirmed"] is True
    assert clear_receipt["acknowledgementSampleId"] > clear_receipt["baselineSampleId"]
    assert clear_receipt["serverWindowId"] == clear_receipt["clientWindowId"] == 0
    assert clear_receipt["replayAllowed"] is False and clear_receipt["outcomeUnknown"] is False
    assert result["materialGrantsDispatched"] == 6
    assert len(result["acceptance"]["registeredRoles"]) == 4
    grants = [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"
        and call["args"]["message"].startswith("/give")]
    assert len(grants) == 6 and all(message.split()[3] == "1" for message in grants)


async def test_declared_minimal_goal_reuses_actual_exact_inventory_without_grants(native_construction):
    client, state = native_construction
    arguments = declared_setup(state, empty=False)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["declaredSuppliesVerified"], result
    assert result["alreadyMinimalVerified"] is True and result["materialGrantsDispatched"] == 0
    assert result["inventoryClearConfirmed"] is False


@pytest.mark.parametrize("fault,reason", [
    ("authorization", "declared_supply_generation_not_authorized"),
    ("missing", "declared_supplies_required"),
    ("preserve", "declared_supply_preservation_conflict"),
    ("restore", "declared_supply_preservation_conflict"),
    ("controller_missing", "declared_controller_supply_unavailable"),
    ("controller_count", "declared_controller_supply_unavailable"),
    ("extra", "declared_supply_scope_mismatch"),
    ("duplicate_index", "declared_supply_ambiguous"),
    ("duplicate_identity", "declared_supply_ambiguous"),
    ("port_count", "declared_port_supply_quantity"),
    ("wrong_output", "ordinary_output_identity_mismatch"),
])
async def test_invalid_declared_supply_refuses_before_any_native_write(native_construction, fault, reason):
    client, state = native_construction
    arguments = declared_setup(state)
    supplies = arguments["declaredSupplies"]
    if fault == "authorization":
        arguments["generateMaterials"] = False
    elif fault == "missing":
        arguments.pop("declaredSupplies")
    elif fault == "preserve":
        arguments["inventoryPreparation"] = "preserve"
    elif fault == "restore":
        arguments["restoreInventory"] = True
    elif fault == "controller_missing":
        supplies[-1]["item"] = native.SUPPORT.copy()
    elif fault == "controller_count":
        supplies[-1]["count"] = 2
    elif fault == "extra":
        supplies.append({"hotbarSlot": 4, "item": native.SUPPORT.copy(), "count": 1})
    elif fault == "duplicate_index":
        supplies[-1]["hotbarSlot"] = 0
    elif fault == "duplicate_identity":
        supplies[1]["item"] = native.BLUEPRINT.copy()
    elif fault == "port_count":
        supplies[1]["count"] = 2
    else:
        next(supply for supply in supplies if supply["hotbarSlot"] == native.PORT_SLOTS["item_output"])["item"] = native.SUPPORT.copy()
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == reason, result
    assert native.writes(state) == []


@pytest.mark.parametrize("fault", ["cursor_missing", "cursor_nonempty", "missing_armor", "truncated", "registry_identity", "boolean_metadata", "stack_limit", "boolean_stack_limit"])
async def test_declared_supply_requires_actual_complete_inventory_and_registry_limits(native_construction, monkeypatch, fault):
    client, state = native_construction
    arguments = declared_setup(state)

    def hook(operation, args, data):
        if operation == "inventory.inspect":
            if fault == "cursor_missing":
                data.pop("cursorItem")
            elif fault == "cursor_nonempty":
                data["cursorItem"] = native.stack(native.SUPPORT)
            elif fault == "missing_armor":
                data["slots"].pop()
                data["totalSlots"] -= 1
            elif fault == "truncated":
                data["truncated"] = True
        elif operation == "registry.item":
            if fault == "registry_identity":
                data["metadata"] += 1
            elif fault == "boolean_metadata":
                data["metadata"] = False
            elif fault == "stack_limit":
                data["stackLimit"] = 0
            elif fault == "boolean_stack_limit":
                data["stackLimit"] = True

    observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable", result
    assert native.writes(state) == []


def add_group_selection(runtime):
    for group in runtime["groups"]:
        group["roleSelection"] = {"available": True, "binding": _GROUP_SELECTION_BINDING,
            "captureId": runtime["captureId"], "groupId": group["id"],
            "descriptionKeys": copy.deepcopy(group["descriptionKeys"]), "semanticRoleVerified": False,
            "scope": "Public definition element identity within this capture."}


async def test_same_role_declarations_bind_two_public_groups_and_register_each_exact_port(native_construction, monkeypatch):
    client, state = native_construction
    state["items"][4] = native.stack(native.PORT_ITEMS["item_input"])
    arguments = native.declaration()
    keys = list(native.ROLE_KEYS.values())
    first = next(port for port in arguments["requestedPorts"] if port["role"] == "item_input")
    first.update(id="caller_seed", groupSelector={"descriptionKeys": keys, "occurrence": 0})
    arguments["requestedPorts"].append({"role": "item_input", "id": "caller_ordinary", "hotbarSlot": 4,
        "groupSelector": {"descriptionKeys": keys, "occurrence": 1}})

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        second = copy.deepcopy(next(group for group in runtime["groups"] if group["id"] == 0))
        second["id"] = 2
        runtime["groups"].append(second)
        for point in runtime["positions"]:
            if point["group"] == 0 and point["z"] == native.CONTROLLER["z"] + 2:
                point["group"] = 2
        add_group_selection(runtime)

    observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    ports = {port["id"]: port for port in result["selectedPorts"] if "id" in port}
    assert ports["caller_seed"]["group"] == 0 and ports["caller_ordinary"]["group"] == 2
    assert ports["caller_seed"]["position"] != ports["caller_ordinary"]["position"]
    accepted = {entry["id"]: entry for entry in result["acceptance"]["registeredRoles"] if "id" in entry}
    assert set(accepted) == {"caller_seed", "caller_ordinary"}
    assert all(entry["registered"] and entry["groupSelection"]["semanticRoleVerified"] is False for entry in accepted.values())


def small_input_group(monkeypatch, client, count, *, remove_default_input=True, fault=None):
    keys = [native.ROLE_KEYS["item_input"], "caller.public.small.group"]

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        baseline = next(group for group in runtime["groups"] if group["id"] == 0)
        selected = copy.deepcopy(baseline)
        selected.update(id=2, descriptionKeys=keys.copy(), capturedVisits=count)
        if remove_default_input:
            baseline["descriptionKeys"].remove(native.ROLE_KEYS["item_input"])
        targets = sorted([point for point in runtime["positions"] if point["group"] == 0],
            key=lambda point: (point["z"], point["y"], point["x"]))[:count]
        for point in targets:
            point["group"] = 2
        baseline["capturedVisits"] -= count
        runtime["groups"].append(selected)
        add_group_selection(runtime)
        if fault == "binding":
            selected["roleSelection"]["captureId"] = "stale-capture"
        elif fault == "incomplete":
            runtime["capturedVisitsComplete"] = False
        elif fault == "duplicate_position":
            runtime["positions"][-1] = copy.deepcopy(runtime["positions"][0])

    observe_hook(monkeypatch, client, hook)
    return {"descriptionKeys": keys, "occurrence": 0}


def explicit_input_arguments(state, selector, count, **changes):
    arguments = native.declaration(supportMode="command", **changes)
    arguments.pop("supportItem")
    arguments.pop("supportHotbarSlot")
    state["items"][6] = None
    first = next(port for port in arguments["requestedPorts"] if port["role"] == "item_input")
    first.update(id="caller_input_0", groupSelector=copy.deepcopy(selector))
    for index, slot in enumerate((4, 7)[:count - 1], start=1):
        state["items"][slot] = native.stack(native.PORT_ITEMS["item_input"])
        arguments["requestedPorts"].append({"role": "item_input", "hotbarSlot": slot,
            "id": "caller_input_" + str(index), "groupSelector": copy.deepcopy(selector)})
    return arguments


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_explicit_complete_small_group_can_replace_every_distinct_cell_and_register_each_id(native_construction, monkeypatch, count, order):
    client, state = native_construction
    selector = small_input_group(monkeypatch, client, count)
    arguments = explicit_input_arguments(state, selector, count, constructionOrder=order)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    ports = [port for port in result["selectedPorts"] if port["role"] == "item_input"]
    assert len(ports) == len({native.key(port["position"]) for port in ports}) == count
    assert all(port["group"] == 2 and port["placementConfirmed"] for port in ports)
    assert all(port["groupSelection"]["semanticRoleVerified"] is False for port in ports)
    accepted = [entry for entry in result["acceptance"]["registeredRoles"] if entry["role"] == "item_input"]
    assert {entry["id"] for entry in accepted} == {"caller_input_" + str(index) for index in range(count)}
    assert {native.key(entry["position"]) for entry in accepted} == {native.key(port["position"]) for port in ports}
    assert all(entry["registered"] and entry["valid"] for entry in accepted)
    assert state["hint_count"] == state["construct_count"] == state["rechecks"] == 1


async def test_default_singleton_group_retains_conservative_baseline_before_shell(native_construction, monkeypatch):
    client, state = native_construction
    small_input_group(monkeypatch, client, 1)
    result = await native.invoke(client, native.declaration(supportMode="command"))
    assert result["status"] == "failed" and result["reason"] == "baseline_casing_retention_unavailable", result
    assert state["construct_count"] == state["rechecks"] == 0


async def test_explicit_two_cell_group_refuses_third_port_before_shell(native_construction, monkeypatch):
    client, state = native_construction
    selector = small_input_group(monkeypatch, client, 2)
    result = await native.invoke(client, explicit_input_arguments(state, selector, 3))
    assert result["status"] == "failed" and result["reason"] == "public_port_category_unavailable", result
    assert state["construct_count"] == state["rechecks"] == 0


@pytest.mark.parametrize("fault,reason", [("binding", "public_port_group_binding_unavailable"),
    ("incomplete", "native_hint_capture_incomplete"), ("duplicate_position", "native_capture_coverage_incomplete")])
async def test_small_group_needs_complete_unique_current_capture_before_shell(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    selector = small_input_group(monkeypatch, client, 1, fault=fault)
    result = await native.invoke(client, explicit_input_arguments(state, selector, 1))
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert state["construct_count"] == state["rechecks"] == 0


async def test_explicit_singleton_cannot_replace_actual_registered_acceptance(native_construction, monkeypatch):
    client, state = native_construction
    state["missing_registered_role"] = "item_input"
    selector = small_input_group(monkeypatch, client, 1)
    result = await native.invoke(client, explicit_input_arguments(state, selector, 1))
    assert result["status"] == "failed" and result["reason"] == "registered_role_not_accepted", result
    assert state["formed"] is True and result["formationVerified"] is False
    assert result["acceptance"]["structureFormed"] is True
    registration = result["acceptance"]["registrationAcceptance"]
    assert registration["complete"] is False and registration["componentCoverageComplete"] is True
    assert registration["unacceptedPort"] == {"id": "caller_input_0", "role": "item_input",
        "position": next(port["position"] for port in result["selectedPorts"] if port["id"] == "caller_input_0")}
    assert registration["reason"] == "registered_role_not_accepted" and registration["matchingRegisteredEntries"] == 0
    assert "specialized" in registration["scope"] and result["acceptance"]["registeredRoles"] == []
    assert state["rechecks"] == 1


@pytest.mark.parametrize("fault", ["role_without_id", "role_without_selector", "duplicate_id", "two_energy_tiers"])
async def test_repeated_role_declaration_refuses_ambiguity_before_placement(native_construction, fault):
    client, state = native_construction
    arguments = native.declaration()
    first = arguments["requestedPorts"][0]
    first.update(id="first", groupSelector={"descriptionKeys": list(native.ROLE_KEYS.values()), "occurrence": 0})
    repeated = {**copy.deepcopy(first), "id": "second", "hotbarSlot": 4}
    if fault == "role_without_id":
        repeated.pop("id")
    elif fault == "role_without_selector":
        repeated.pop("groupSelector")
    elif fault == "duplicate_id":
        repeated["id"] = "first"
    else:
        first["role"] = repeated["role"] = "energy"
        arguments["energyTier"] = 6
    arguments["requestedPorts"].append(repeated)
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable", result
    assert native.writes(state) == []


@pytest.mark.parametrize("fault", ["missing", "unavailable", "binding", "capture", "group", "semantic", "keys", "truncated", "occurrence", "exact_keys",
    "capture_incomplete", "capture_truncated", "capture_complete_missing", "capture_truncated_missing"])
async def test_public_group_selector_refuses_unproven_or_stale_binding(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(), run_workflow, started=time.monotonic())
    task.capture = {"captureId": state["capture_id"], "capturedVisitsComplete": True, "eventCaptureTruncated": False}
    group = {"id": 10, "descriptionKeys": [native.ROLE_KEYS["item_input"], "caller.public.category"],
        "suggestedCandidates": [native.stack(native.CASING)]}
    runtime = {"captureId": state["capture_id"], "groups": [group]}
    add_group_selection(runtime)
    port = {"role": "item_input", "id": "caller", "hotbarSlot": 2,
        "groupSelector": {"descriptionKeys": copy.deepcopy(group["descriptionKeys"]), "occurrence": 0}}
    binding = group["roleSelection"]
    if fault == "missing":
        group.pop("roleSelection")
    elif fault == "unavailable":
        binding["available"] = False
    elif fault == "binding":
        binding["binding"] = "class_only"
    elif fault == "capture":
        binding["captureId"] = "other"
    elif fault == "group":
        binding["groupId"] = 11
    elif fault == "semantic":
        binding["semanticRoleVerified"] = True
    elif fault == "keys":
        binding["descriptionKeys"] = []
    elif fault == "truncated":
        group["descriptionTruncated"] = True
    elif fault == "occurrence":
        port["groupSelector"]["occurrence"] = 1
    elif fault == "exact_keys":
        port["groupSelector"]["descriptionKeys"].pop()
    elif fault == "capture_incomplete":
        task.capture["capturedVisitsComplete"] = False
    elif fault == "capture_truncated":
        task.capture["eventCaptureTruncated"] = True
    elif fault == "capture_complete_missing":
        task.capture.pop("capturedVisitsComplete")
    else:
        task.capture.pop("eventCaptureTruncated")
    with pytest.raises(BridgeError, match="group|capture|description"):
        task.select_port_group(port, {10: group})
    assert native.writes(state) == []


def compact_hook(operation, args, data, *, page_size=128):
    if operation == "bridge.health":
        data["worldInspectManyCompact"] = True
    elif operation == "world.inspect_many" and args.get("compact") is True:
        count = min(len(args["positions"]), page_size)
        data["results"] = data["results"][:count]
        for index, row in enumerate(data["results"]):
            row["requestIndex"] = index
            row["data"]["loaded"] = True
        data.update(requestedCount=len(args["positions"]), processedCount=count, nextOffset=count,
            complete=count == len(args["positions"]), partial=count != len(args["positions"]),
            byteLimited=False, budgetLimited=count != len(args["positions"]), worldTick=1, nonAtomic=True)


@pytest.mark.parametrize("page_size,requests", [(128, 57), (64, 114), (1, 7259)])
async def test_large_volume_compact_reads_cover_all_ordered_prefixes(native_construction, monkeypatch, page_size, requests):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(timeout_ms=60_000), run_workflow, started=time.monotonic())
    task.compact_inspection = True
    positions = [{"x": index, "y": 10, "z": 150} for index in range(7259)]
    observe_hook(monkeypatch, client, lambda op, args, data: compact_hook(op, args, data, page_size=page_size))
    await task.verify_clear_volume(positions)
    assert task.volume_inspection["serverVerifiedAirPositions"] == 7259
    assert task.volume_inspection["requests"] == requests
    seen = [row for call in state["calls"] if call["operation"] == "world.inspect_many" for row in call["args"]["positions"][:page_size]]
    assert seen == positions
    assert native.writes(state) == []


@pytest.mark.parametrize("fault", ["source", "dimension", "world_tick", "non_atomic", "request_count", "bool_count", "next", "complete", "partial", "no_reason", "no_progress",
    "missing_row", "request_index", "unknown", "unloaded", "point_source", "point_dimension", "point_xyz", "identity", "occupied"])
async def test_compact_volume_refuses_missing_or_inconsistent_server_coverage(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(), run_workflow, started=time.monotonic())
    task.compact_inspection = True

    def hook(operation, args, data):
        compact_hook(operation, args, data, page_size=1)
        if operation != "world.inspect_many":
            return
        if fault == "source":
            data["source"] = "client"
        elif fault == "dimension":
            data["dimension"] = 1
        elif fault == "world_tick":
            data["worldTick"] = True
        elif fault == "non_atomic":
            data["nonAtomic"] = False
        elif fault == "request_count":
            data["requestedCount"] -= 1
        elif fault == "bool_count":
            data["processedCount"] = True
        elif fault == "next":
            data["nextOffset"] += 1
        elif fault == "complete":
            data["complete"] = True
        elif fault == "partial":
            data["partial"] = False
        elif fault == "no_reason":
            data["budgetLimited"] = False
        elif fault == "no_progress":
            data["processedCount"] = data["nextOffset"] = 0
            data["results"] = []
        elif fault == "missing_row":
            data["results"] = []
        else:
            row = data["results"][0]
            if fault == "request_index":
                row["requestIndex"] = 1
            elif fault == "unknown":
                row["ok"] = False
            elif fault == "unloaded":
                row["data"]["loaded"] = False
            elif fault == "point_source":
                row["data"]["source"] = "client"
            elif fault == "point_dimension":
                row["data"]["dimension"] = 1
            elif fault == "point_xyz":
                row["data"]["x"] += 1
            elif fault == "identity":
                row["data"].pop("metadata")
            else:
                row["data"]["registryName"] = "minecraft:chest"

    observe_hook(monkeypatch, client, hook)
    with pytest.raises(BridgeError):
        await task.verify_clear_volume([{"x": 10, "y": 10, "z": 10}, {"x": 11, "y": 10, "z": 10}])
    assert len(state["calls"]) == 1 and native.writes(state) == []


async def test_native_builder_uses_compact_volume_without_weakening_final_acceptance(native_construction, monkeypatch):
    client, state = native_construction
    arguments = declared_setup(state)
    observe_hook(monkeypatch, client, compact_hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["volumeInspection"] == {"mode": "compact_128", "requestedPositions": 26,
        "requests": 1, "serverVerifiedAirPositions": 26, "nonAtomic": True}
    assert state["rechecks"] == 1 and len(result["acceptance"]["registeredRoles"]) == 4


@pytest.mark.parametrize("page_size", [128, 17, 1])
async def test_large_native_capture_accepts_soft_budget_pages_with_exact_raw_coverage(native_construction, monkeypatch, page_size):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(timeout_ms=60_000), run_workflow, started=time.monotonic())
    task.floor = 1
    total = 7259 if page_size != 1 else 129

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        offset = args["runtimeOffset"]
        end = min(total, offset + page_size)
        group = {
            "id": 0, "descriptionKeys": list(native.ROLE_KEYS.values()), "suggestedCandidates": [native.stack(native.CASING)]}
        # One immutable public group is returned on every synthetic page.
        runtime.update(capturedPositions=total, observedVisits=total, runtimeOffset=offset, nextRuntimeOffset=end, hasMore=end < total,
            positions=[{"x": index, "y": 10, "z": 150, "group": 0} for index in range(offset, end)],
            groups=[group], uncapturedVisits=0, captureLossDetected=False, captureContinuationAvailable=False)

    observe_hook(monkeypatch, client, hook)
    positions, groups = await task.collect_hints()
    assert len(positions) == total and len({native.key(point) for point in positions}) == total
    assert set(groups) == {0} and native.writes(state) == []


@pytest.mark.parametrize("fault", ["loss", "uncaptured", "continuation", "false_complete", "truncated", "missing_loss_field", "visit_count"])
async def test_large_capture_paging_never_repairs_lost_visits(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(), run_workflow, started=time.monotonic())
    task.floor = 1

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        runtime.update(observedVisits=runtime["capturedPositions"], uncapturedVisits=0, captureLossDetected=False, captureContinuationAvailable=False)
        if fault == "loss":
            runtime["captureLossDetected"] = True
        elif fault == "uncaptured":
            runtime["uncapturedVisits"] = 1
        elif fault == "continuation":
            runtime["captureContinuationAvailable"] = True
        elif fault == "false_complete":
            runtime["capturedVisitsComplete"] = False
        elif fault == "truncated":
            runtime["eventCaptureTruncated"] = True
        elif fault == "missing_loss_field":
            runtime.pop("uncapturedVisits")
        else:
            runtime["observedVisits"] += 1

    observe_hook(monkeypatch, client, hook)
    with pytest.raises(BridgeError):
        await task.collect_hints()
    assert len(state["calls"]) == 1 and native.writes(state) == []


@pytest.mark.parametrize("compact,count,valid", [(None, 16, True), (False, 16, True), (None, 17, False),
    (False, 17, False), (True, 128, True), (True, 129, False), (True, 0, False)])
def test_compact_batch_schema_keeps_legacy_and_explicit_finite_limits(compact, count, valid):
    arguments = {"positions": [{"x": index, "y": 10, "z": 10} for index in range(count)]}
    if compact is not None:
        arguments["compact"] = compact
    accepted = not bool(list(tool_validator("mcb_world_inspect_many").iter_errors(arguments)))
    assert accepted is valid


@pytest.mark.parametrize("fault", ["count", "slot", "tag", "too_many", "selector_duplicate", "selector_empty", "occurrence", "boolean_occurrence"])
def test_declared_goal_schema_rejects_unbounded_supply_or_selector(fault):
    state = {"items": {}}
    arguments = declared_setup(state)
    if fault == "count":
        arguments["declaredSupplies"][0]["count"] = 65
    elif fault == "slot":
        arguments["declaredSupplies"][0]["hotbarSlot"] = 9
    elif fault == "tag":
        arguments["declaredSupplies"][0]["item"]["hasTag"] = False
    elif fault == "too_many":
        arguments["declaredSupplies"] = [arguments["declaredSupplies"][0]] * 9
    else:
        selector = {"descriptionKeys": [native.ROLE_KEYS["item_input"]], "occurrence": 0}
        arguments["requestedPorts"][0]["groupSelector"] = selector
        if fault == "selector_duplicate":
            selector["descriptionKeys"] *= 2
        elif fault == "selector_empty":
            selector["descriptionKeys"] = []
        elif fault == "occurrence":
            selector["occurrence"] = -1
        else:
            selector["occurrence"] = True
    assert list(tool_validator("mcb_blueprint_build").iter_errors(arguments))


async def test_declared_duplicate_identity_across_different_roles_never_merges_grants(native_construction):
    client, state = native_construction
    arguments = declared_setup(state)
    output = next(port for port in arguments["requestedPorts"] if port["role"] == "item_output")
    output["ordinaryOutputItem"] = native.PORT_ITEMS["item_input"].copy()
    next(supply for supply in arguments["declaredSupplies"] if supply["hotbarSlot"] == output["hotbarSlot"])["item"] = native.PORT_ITEMS["item_input"].copy()
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == "declared_supply_ambiguous", result
    assert native.writes(state) == []


async def test_capture_retention_has_independent_memory_bound(native_construction, monkeypatch):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(), run_workflow, started=time.monotonic())
    task.floor = 1

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        offset = args["runtimeOffset"]
        runtime = data["runtimeObservation"]
        runtime.update(capturedPositions=9, observedVisits=9, runtimeOffset=offset, nextRuntimeOffset=offset + 1,
            hasMore=offset < 8, uncapturedVisits=0, captureLossDetected=False, captureContinuationAvailable=False,
            positions=[{"x": offset, "y": 10, "z": 150, "group": offset}],
            groups=[{"id": offset, "descriptionKeys": [], "suggestedCandidates": [{"publicNote": "x" * 500_000}]}])

    observe_hook(monkeypatch, client, hook)
    with pytest.raises(BridgeError, match="memory bound"):
        await task.collect_hints()
    assert len(state["calls"]) == 9 and native.writes(state) == []


@pytest.mark.parametrize("fault", ["position_limit", "boolean_offset", "boolean_group", "mixed_keys"])
async def test_capture_rejects_unbounded_or_malformed_raw_identity(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = _ConstructionTask(client, native.declaration(), run_workflow, started=time.monotonic())
    task.floor = 1

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        if fault == "position_limit":
            runtime["capturedPositions"] = 16_385
        elif fault == "boolean_offset":
            runtime["runtimeOffset"] = False
        elif fault == "boolean_group":
            runtime["groups"][0]["id"] = False
        else:
            runtime["groups"][0]["descriptionKeys"].append(123)

    observe_hook(monkeypatch, client, hook)
    with pytest.raises(BridgeError):
        await task.collect_hints()
    assert len(state["calls"]) == 1 and native.writes(state) == []
