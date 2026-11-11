"""Repeated public port goals use exact finite sources without collapsing targets."""

import copy

import pytest

import test_construction_tasks as native
import test_construction_declared_goals as goals

native_construction = native.native_construction


def repeated_setup(monkeypatch, client, state, *, existing=False, **changes):
    arguments = goals.declared_setup(state, empty=not existing)
    arguments.update(changes)
    keys = list(native.ROLE_KEYS.values())
    first = next(port for port in arguments["requestedPorts"] if port["role"] == "item_input")
    first.update(id="first_chamber", groupSelector={"descriptionKeys": keys, "occurrence": 0})
    arguments["requestedPorts"].insert(1, {"role": "item_input", "id": "second_chamber", "hotbarSlot": 4,
        "groupSelector": {"descriptionKeys": keys, "occurrence": 1}})
    arguments["declaredSupplies"].append({"hotbarSlot": 4, "item": native.PORT_ITEMS["item_input"].copy(), "count": 1})
    if existing:
        state["items"][4] = native.stack(native.PORT_ITEMS["item_input"])

    def hook(operation, args, data):
        if operation != "world.structure":
            return
        runtime = data["runtimeObservation"]
        second = copy.deepcopy(next(group for group in runtime["groups"] if group["id"] == 0))
        second["id"] = 2
        runtime["groups"].append(second)
        for point in runtime["positions"]:
            if point["group"] == 0 and point["x"] == native.CONTROLLER["x"] + 1:
                point["group"] = 2
        goals.add_group_selection(runtime)

    goals.observe_hook(monkeypatch, client, hook)
    return arguments


def input_placements(state):
    return [entry for entry in state["placements"] if entry["item"]["registryName"] == native.PORT_ITEMS["item_input"]["registryName"]
        and entry["item"]["metadata"] == native.PORT_ITEMS["item_input"]["metadata"]]


def supply_calls(state):
    return [call for call in state["calls"] if call["operation"] == "inventory.supply"
        or call["operation"] == "chat.send" and call["args"]["message"].startswith("/give")]


@pytest.mark.parametrize("batch", [False, True])
@pytest.mark.parametrize("mode", ["minimal", "clear"])
@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_same_item_two_chambers_each_place_once_and_register_from_exact_aggregate_supply(native_construction, monkeypatch, batch, mode, order):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state, inventoryPreparation=mode, constructionOrder=order, includeDetails=False)
    if batch:
        state["caps"].append("inventory.supply")
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    ports = {port["id"]: port for port in result["selectedPorts"] if port.get("role") == "item_input"}
    assert set(ports) == {"first_chamber", "second_chamber"}
    assert len({native.key(port["position"]) for port in ports.values()}) == 2
    assert {port["groupSelection"]["groupId"] for port in ports.values()} == {0, 2}
    assert {port["groupSelection"]["selector"]["occurrence"] for port in ports.values()} == {0, 1}
    assert {port["supplyBinding"]["declaredHotbarSlot"] for port in ports.values()} == {2, 4}
    assert {port["supplyBinding"]["sourceHotbarSlot"] for port in ports.values()} == {1}
    assert {port["supplyBinding"]["allocationIndex"] for port in ports.values()} == {0, 1}
    for port in ports.values():
        binding = port["supplyBinding"]
        assert binding["sourceStackCount"] == 2 and binding["allocatedCount"] == 1
        assert binding["mode"] == "aggregate_exact_port_supply"
        assert binding["placementAttempted"] is binding["placementConfirmed"] is binding["sourceIdentityAndCountVerified"] is True
    accepted = {entry["id"]: entry for entry in result["acceptance"]["registeredRoles"] if "id" in entry}
    assert set(accepted) == set(ports) and all(entry["registered"] for entry in accepted.values())
    assert {native.key(entry["position"]) for entry in accepted.values()} == {native.key(port["position"]) for port in ports.values()}
    placements = input_placements(state)
    assert len(placements) == 2 and {placement["position"] for placement in placements} == {native.key(port["position"]) for port in ports.values()}
    assert all(placement["item"]["count"] == 2 for placement in placements)
    assert state["items"][1] == native.stack(native.PORT_ITEMS["item_input"], 2)
    if batch:
        supplies = supply_calls(state)
        assert len(supplies) == 1
        inputs = [item for item in supplies[0]["args"]["items"] if item["metadata"] == 77]
        assert inputs == [{**native.PORT_ITEMS["item_input"], "count": 2}]
    else:
        assert len(supply_calls(state)) == 6
        assert [call["args"]["message"] for call in supply_calls(state) if call["args"]["message"].endswith(" 77")] == [
            "/give TestPlayer gregtech:gt.blockmachines 2 77"]
    assert state["construct_count"] == state["rechecks"] == 1


@pytest.mark.parametrize("generated", [False, True])
async def test_minimal_existing_separate_same_item_sources_keep_each_declared_hotbar(native_construction, monkeypatch, generated):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state, existing=True)
    if not generated:
        arguments.pop("generateMaterials")
        arguments.pop("declaredSupplies")
        arguments.update(supportMode="player", supportItem=native.SUPPORT.copy(), supportHotbarSlot=6)
        state["items"][6] = native.stack(native.SUPPORT, 3)
    original = copy.deepcopy(state["items"])
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["alreadyMinimalVerified"] is generated and not result["inventoryClearConfirmed"] and not supply_calls(state)
    assert state["items"] == original
    ports = [port for port in result["selectedPorts"] if port["role"] == "item_input"]
    assert {port["hotbarSlot"] for port in ports} == {2, 4}
    if generated:
        assert all(port["supplyBinding"]["sourceHotbarSlot"] == port["supplyBinding"]["declaredHotbarSlot"] == port["hotbarSlot"] for port in ports)
        assert all(port["supplyBinding"]["sourceStackCount"] == 1 and port["supplyBinding"]["mode"] == "independent_declared_hotbar" for port in ports)
    else:
        assert result["inventoryPreparationMode"] == "preserve" and all("supplyBinding" not in port for port in ports)
    assert len(input_placements(state)) == 2


@pytest.mark.parametrize("fault", ["missing_id", "missing_selector", "duplicate_id", "port_quantity", "aggregate_limit"])
async def test_repeated_goals_require_distinct_bindings_and_sufficient_aggregate_limit_before_any_write(native_construction, monkeypatch, fault):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state)
    second = next(port for port in arguments["requestedPorts"] if port.get("id") == "second_chamber")
    if fault == "missing_id":
        second.pop("id")
    elif fault == "missing_selector":
        second.pop("groupSelector")
    elif fault == "duplicate_id":
        second["id"] = "first_chamber"
    elif fault == "port_quantity":
        arguments["declaredSupplies"][-1]["count"] = 2
    else:
        def hook(operation, args, data):
            if operation == "registry.item" and args == native.PORT_ITEMS["item_input"]:
                data["stackLimit"] = 1
        goals.observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable", result
    assert result["reason"] == ("declared_supply_registry_unavailable" if fault == "aggregate_limit"
        else "declared_port_supply_quantity" if fault == "port_quantity" else "declaration_overlap")
    assert native.writes(state) == []


@pytest.mark.parametrize("fault", ["missing", "excess", "extra_stack", "wrong_identity"])
async def test_incomplete_or_excess_actual_aggregate_inventory_never_places_or_replays_grants(native_construction, monkeypatch, fault):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state, timeout_ms=1000)
    state["caps"].append("inventory.supply")

    def hook(operation, args, data):
        if operation != "inventory.supply":
            return
        source = state["items"][1]
        if fault == "missing":
            source["count"] = 1
        elif fault == "excess":
            source["count"] = 3
        elif fault == "extra_stack":
            state["items"][8] = native.stack(native.PORT_ITEMS["item_input"])
        else:
            source["metadata"] += 1

    goals.observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "failed" and result["reason"] in {"supplied_hotbar_binding_unavailable", "task_deadline"}, result
    assert result["failedPhase"] == "verify_supplied_inventory"
    assert len(supply_calls(state)) == 1 and not state["placements"]
    assert state["construct_count"] == state["rechecks"] == 0


@pytest.mark.parametrize("unknown_port", [1, 2])
@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_uncertain_native_placement_for_each_aggregate_allocation_is_never_replayed(native_construction, monkeypatch, unknown_port, order):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state, constructionOrder=order)
    original = client.call

    async def wrapped(operation, args, **kwargs):
        if operation == "player.place_block":
            source = state["items"][args["hotbarSlot"]]
            if source is not None and source["metadata"] == native.PORT_ITEMS["item_input"]["metadata"]:
                if len(input_placements(state)) + 1 == unknown_port:
                    state["disconnect_place"] = True
        return await original(operation, args, **kwargs)

    monkeypatch.setattr(client, "call", wrapped)
    result = await native.invoke(client, arguments)
    assert result["status"] == "failed" and result["error"]["outcome_unknown"] is True, result
    placements = input_placements(state)
    assert len(placements) == unknown_port and len({entry["position"] for entry in placements}) == unknown_port
    ports = [port for port in result["selectedPorts"] if port["role"] == "item_input"]
    assert sum(port["supplyBinding"].get("placementAttempted", False) for port in ports) == unknown_port
    assert sum(port["supplyBinding"].get("placementConfirmed", False) for port in ports) == unknown_port - 1
    assert len(supply_calls(state)) == 6 and state["rechecks"] == 0
    assert not any(call["operation"] == "player.interact" and call["args"]["action"] == "use"
        and call["args"].get("sneaking") and native.key(call["args"]) in {entry["position"] for entry in placements}
        for call in state["calls"])


async def test_source_quantity_changed_between_ports_stops_before_second_native_placement(native_construction, monkeypatch):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state, constructionOrder="ports_first")

    def hook(operation, args, data):
        if operation == "world.inspect" and len(input_placements(state)) == 1 and data.get("gregtech", {}).get("metaTileId") == 77:
            # The first wait observed the actual new port; its required source reread must now reject.
            state["items"][1]["count"] = 1

    goals.observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, arguments)
    assert result["status"] == "failed" and result["reason"] == "creative_material_changed", result
    assert len(input_placements(state)) == 1 and state["construct_count"] == state["rechecks"] == 0


async def test_unknown_aggregate_supply_receipt_never_replays_grants_or_places(native_construction, monkeypatch):
    client, state = native_construction
    arguments = repeated_setup(monkeypatch, client, state)
    state["caps"].append("inventory.supply")
    state["supply_unknown"] = True
    result = await native.invoke(client, arguments)
    assert result["status"] == "failed" and result["error"]["code"] == "ASSERTION_FAILED", result
    assert len(supply_calls(state)) == 1 and not state["placements"]
    assert state["construct_count"] == state["rechecks"] == 0
