"""Offline bounded native waits: fresh predicates, capability routing and unchanged conservation."""
import copy

import jsonschema
import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_fluid_recipe_tasks as fluid
import test_shared_basic_fluid_tasks as shared
import test_recipe_phase_batch as batch
from test_single_recipe_tasks import single_machine
from test_ghost_recipe_tasks import ghost_machine
from minecraft_codex_bridge import native_wait
from minecraft_codex_bridge.bridge import BridgeError, response_context
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import _workflow_discovery

native_bridge = native.native_bridge


def receipt(*, matched=True, timed_out=False):
    return {"version": 1, "source": "normal_server_end_ticks", "readOnly": True, "submitted": False,
        "nonAtomic": True, "quantitiesMonotonic": True, "matched": matched, "partial": False, "timedOut": timed_out,
        "finalOwnersFresh": True, "attempts": 3, "firstWorldTick": 70, "lastWorldTick": 72,
        "elapsedMillis": 100, "tickWorkBudgetNanos": 4_000_000, "callbackPreemptible": False}


def arguments():
    return {"positions": [{"x": 10, "y": 19, "z": 20}], "dimension": 180, "compact": False,
        "includePlayerInventory": True, "playerInventoryByteBudget": 131_072, "observationByteBudget": 1_048_576,
        "nativeWait": {"version": 1, "timeoutMillis": 6_500,
            "owners": [{"registryName": "gregtech:gt.blockmachines", "metadata": 81,
                "tileClass": "fixture.ActualTile", "metaTileId": 10001, "metaClass": "fixture.ActualMeta"}],
            "conditions": [{"owner": 0, "kind": "item_delta", "item": native.PRODUCT.copy(), "baseline": 4, "delta": 2},
                {"owner": 0, "kind": "fluid_match", "tankKind": "forge", "tankIndex": 1, "fluid": "water", "amount": 100},
                {"owner": 0, "kind": "state_equal", "fields": {"active": False, "allowedToWork": False}}]}}


def observation(args, *, products=2, water=100):
    owner = {**args["positions"][0], "dimension": args["dimension"], "source": "integrated_server",
        **args["nativeWait"]["owners"][0]}
    owner["gregtech"] = {key: owner.pop(key) for key in ("metaTileId", "metaClass")}
    owner["gregtech"].update(active=False, allowedToWork=False)
    owner["inventory"] = {"totalSlots": 2, "truncated": False, "slots": [
        {"index": 0, "item": native.stack(native.PRODUCT, 4 + products)}, {"index": 1, "item": None}]}
    owner["fluids"] = {"totalTanks": 2, "truncated": False, "tanks": [
        {"index": 0, "capacity": 1000}, {"index": 1, "capacity": 1000, **({"fluid": "water", "amount": water} if water else {})}]}
    return {"results": [{"ok": True, "data": owner}], "nativeWait": receipt(matched=products == 2 and water == 100,
        timed_out=products != 2 or water != 100)}


def rescale(raw):
    for _ in range(8):
        raw["playerInventoryObservation"]["actualObservationBytes"] = json_size({"data": raw, "context": response_context(raw)})


def enable_native(client, state, monkeypatch, *, version=1, fault=None, mutation=None):
    def hook(raw, current, args):
        for row, point in zip(raw["results"], args["positions"]):
            owner = row["data"]
            owner.update(**point, dimension=args["dimension"], source="integrated_server")
            owner.setdefault("registryName", "gregtech:gt.blockmachines")
            owner.setdefault("tileClass", "fixture.ActualTile")
            if point == native.MACHINE:
                owner.setdefault("gregtech", {}).update(metaTileId=current.get("single_meta_id", 10001),
                    metaClass=current.get("single_meta_class", "example.PublicMultiblock"),
                    active=current["active"], allowedToWork=current["allowed"])
        if "nativeWait" in args:
            raw["nativeWait"] = receipt()
            if fault == "partial": raw["nativeWait"]["partial"] = True
            if fault == "late": raw["nativeWait"]["elapsedMillis"] = 8_001
            if fault == "old_readiness":
                condition = next(row for row in args["nativeWait"]["conditions"] if row["kind"] == "item_delta")
                items = raw["results"][condition["owner"]]["data"]["inventory"]["slots"]
                item = next(row["item"] for row in items if row["item"] and row["item"]["registryName"] == condition["item"]["registryName"])
                item["count"] -= 1
            if fault == "owner": raw["results"][0]["data"]["metadata"] += 1
            if fault == "stop_active" and any(row["kind"] == "state_equal" for row in args["nativeWait"]["conditions"]):
                raw["results"][0]["data"]["gregtech"]["active"] = True
            if mutation is not None: mutation(raw, current, args)
        rescale(raw)
    batch.enable_composite(client, state, monkeypatch, hook=hook)
    original = client.call
    async def call(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "world.inspect" and "inventory" in value:
            # Both real server operations use block() -> JsonData.inventory().
            # Keep the ordinary fixture as complete as its composite owner DTO.
            value["inventory"].setdefault("name", "Complete real owner")
        if operation == "bridge.health" and version is not None:
            value["nativeObservationWaitVersion"] = version
        return value
    monkeypatch.setattr(client, "call", call)


def test_schema_binds_only_fixed_public_read_conditions():
    validator = tool_validator("mcb_world_inspect_many")
    validator.validate(arguments())
    for change in ("expression", "compact", "no_inventory", "gt_pair", "budget", "condition", "timeout", "version"):
        args = arguments()
        if change == "expression": args["nativeWait"]["expression"] = "owner.tick()"
        if change == "compact": args["compact"] = True
        if change == "no_inventory": args["includePlayerInventory"] = False
        if change == "gt_pair": args["nativeWait"]["owners"][0].pop("metaClass")
        if change == "budget": args.pop("observationByteBudget")
        if change == "condition": args["nativeWait"]["conditions"][0]["method"] = "getOutput"
        if change == "timeout": args["nativeWait"]["timeoutMillis"] = 6_501
        if change == "version": args["nativeWait"]["version"] = True
        with pytest.raises(jsonschema.ValidationError): validator.validate(args)


@pytest.mark.parametrize("field,value", [("partial", True), ("finalOwnersFresh", False), ("quantitiesMonotonic", False),
    ("readOnly", False), ("submitted", True), ("callbackPreemptible", True), ("elapsedMillis", 8001),
    ("elapsedMillis", float("nan")), ("tickWorkBudgetNanos", 8_000_000), ("attempts", True),
    ("firstWorldTick", 90), ("timedOut", True), ("version", True)])
def test_late_partial_stale_or_untyped_receipt_never_proves_readiness(field, value):
    args = arguments(); data = observation(args); data["nativeWait"][field] = value
    with pytest.raises(BridgeError): native_wait.validate(data, args)


def test_all_outputs_and_public_stop_match_fresh_together_and_never_decrease_between_windows():
    args = arguments(); previous = {}
    partial = observation(args, products=1, water=50)
    assert native_wait.validate(partial, args, previous)["matched"] is False
    diminished = observation(args, products=0, water=50)
    with pytest.raises(BridgeError, match="decreased"): native_wait.validate(diminished, args, previous)
    complete = observation(args)
    assert native_wait.validate(complete, args, previous)["matched"] is True
    complete["results"][0]["data"]["gregtech"]["active"] = True
    with pytest.raises(BridgeError, match="final fresh"): native_wait.validate(complete, args)


@pytest.mark.parametrize("defect", ["metadata", "tileClass", "metaTileId", "slot", "tank", "item_excess", "fluid_excess", "wrong_fluid", "missing_product"])
def test_identity_and_complete_coverage_cannot_be_substituted(defect):
    args = arguments(); data = observation(args); owner = data["results"][0]["data"]
    if defect == "metadata": owner["metadata"] = 10001
    if defect == "tileClass": owner["tileClass"] = "replacement.Tile"
    if defect == "metaTileId": owner["gregtech"]["metaTileId"] = 10002
    if defect == "slot": owner["inventory"]["slots"][1]["index"] = 0
    if defect == "tank": owner["fluids"]["truncated"] = True
    if defect == "item_excess": owner["inventory"]["slots"][0]["item"]["count"] = 7
    if defect == "fluid_excess": owner["fluids"]["tanks"][1]["amount"] = 101
    if defect == "wrong_fluid": owner["fluids"]["tanks"][1]["fluid"] = "lava"
    if defect == "missing_product": owner["inventory"]["slots"][0]["item"]["count"] = 5
    with pytest.raises(BridgeError): native_wait.validate(data, args)


@pytest.mark.parametrize("defect", ["role", "owner", "tagged", "partial", "capacity"])
def test_complete_named_pair_is_required_even_without_a_named_fluid_predicate(defect):
    args = arguments(); data = observation(args); owner = data["results"][0]["data"]
    named = copy.deepcopy(shared.JAVA_FIXTURE["namedTanks"])
    named["owner"] = {key: owner[key] for key in ("x", "y", "z", "dimension", "registryName")}
    named["owner"].update({key: owner["gregtech"][key] for key in ("metaTileId", "metaClass")})
    for tank in named["tanks"]:
        tank.update(empty=True, fluid=None, capacity=1000)
    owner["fluids"]["namedTanks"] = named
    assert native_wait.validate(data, args)["matched"] is True
    if defect == "role": named["tanks"][1]["role"] = named["tanks"][0]["role"]
    if defect == "owner": named["owner"]["metaTileId"] += 1
    if defect == "tagged": named["tanks"][0].update(empty=False, fluid={"fluid": "hydrogen", "amount": 1, "hasTag": True})
    if defect == "partial": named["truncated"] = True
    if defect == "capacity": named["tanks"][0]["capacity"] = -1
    with pytest.raises(BridgeError):
        native_wait.validate(data, args)


@pytest.mark.parametrize("version", [None, False, True, 0, 2])
async def test_missing_boolean_or_unknown_capability_keeps_existing_route(native_bridge, monkeypatch, version):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    enable_native(client, state, monkeypatch, version=version)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] == "completed", result
    assert not any("nativeWait" in row["args"] for row in state["wire_calls"])


@pytest.mark.parametrize("executions", [1, 2])
async def test_full_native_item_vectors_keep_separate_production_and_stop_and_fresh_retrieval(native_bridge, monkeypatch, executions):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, retained=False)
    enable_native(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments(executions=executions))
    assert result["status"] == "completed", result
    waits = [row for row in state["wire_calls"] if "nativeWait" in row["args"]]
    assert len(waits) == executions * 2
    for product, stop in zip(waits[::2], waits[1::2]):
        assert len(product["args"]["nativeWait"]["conditions"]) == 2
        assert stop["args"]["nativeWait"]["conditions"][:-1] == product["args"]["nativeWait"]["conditions"]
        assert stop["args"]["nativeWait"]["conditions"][-1]["fields"] == {"active": False, "allowedToWork": False}
        start = state["wire_calls"].index(product); end = state["wire_calls"].index(stop)
        assert any(row["operation"] == "gui.widget_action" and row["args"].get("selected") == 0 for row in state["wire_calls"][start + 1:end])
    metrics = [phase["nativeWaitMetrics"] for phase in result["phases"] if "nativeWaitMetrics" in phase]
    assert len(metrics) == executions and all(len(phase) == 2 for phase in metrics)
    assert all(row["httpWindows"] == 1 and row["serverAttempts"] == 3 and row["nativeElapsedMillis"] == 100 for phase in metrics for row in phase)
    assert result["acceptance"]["playerInventoryConserved"] is True and result["acceptance"]["outputDeltas"] == result["acceptance"]["retrievedItems"]
    assert state["vector_power_count"] == executions and state["cursor"] is None and not state["core"]


@pytest.mark.parametrize("fault", ["partial", "late", "old_readiness", "owner", "stop_active"])
async def test_unknown_partial_or_stale_native_read_never_replays_power_or_retrieves(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    enable_native(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert state["vector_power_count"] == 1 and state["vector_retrieval_count"] == 0
    metrics = [phase["nativeWaitMetrics"] for phase in result["phases"] if "nativeWaitMetrics" in phase]
    assert metrics and any(row["httpWindows"] == 1 for phase in metrics for row in phase)


@pytest.mark.parametrize("fault", ["retained_second_input", "lost_catalyst", "extra_output", "lost_player_item", "unknown_feed", "unknown_retrieval"])
async def test_native_wait_preserves_existing_final_conservation_and_unknown_action_rules(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, fault=fault)
    enable_native(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert state["vector_power_count"] <= 1
    if fault.startswith("unknown_"): assert result["outcomeUnknown"] is True


async def test_decrease_between_native_http_windows_is_terminal_without_replaying_power(native_bridge, monkeypatch):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    windows = 0
    def mutation(raw, _, args):
        nonlocal windows
        windows += 1
        condition = next(row for row in args["nativeWait"]["conditions"] if row["kind"] == "item_delta" and row["delta"] == 3)
        slots = raw["results"][condition["owner"]]["data"]["inventory"]["slots"]
        item = next(row["item"] for row in slots if row["item"] and row["item"]["registryName"] == condition["item"]["registryName"])
        item["count"] = condition["baseline"] + (2 if windows == 1 else 1)
        raw["nativeWait"].update(matched=False, timedOut=True)
    enable_native(client, state, monkeypatch, mutation=mutation)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed" and result["reason"] == "NATIVE_WAIT_RECEIPT_UNAVAILABLE", result
    assert windows == 2 and state["vector_power_count"] == 1 and state["vector_retrieval_count"] == 0
    metrics = next(phase["nativeWaitMetrics"] for phase in result["phases"] if "nativeWaitMetrics" in phase)
    assert metrics[0]["httpWindows"] == 2 and metrics[0]["serverAttempts"] == 6


@pytest.mark.parametrize("fluid_only,partial", [(False, False), (False, True), (True, False), (True, True)])
async def test_native_item_and_fluid_products_share_one_wait_and_final_conservation(native_bridge, monkeypatch, fluid_only, partial):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch, fluid_only=fluid_only, partial=partial)
    enable_native(client, state, monkeypatch)
    result = await native.invoke(client, fluid.declaration(fluid_only=fluid_only, partial=partial))
    assert result["status"] == "completed", result
    waits = [row for row in state["wire_calls"] if "nativeWait" in row["args"]]
    assert len(waits) == 2 and len(waits[0]["args"]["nativeWait"]["conditions"]) == (1 if fluid_only else 2)
    assert any(row["kind"] == "fluid_match" for row in waits[0]["args"]["nativeWait"]["conditions"])
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"] and state["cursor"] is None


async def check_shared(fixture, monkeypatch, *, ghost):
    client, state = fixture
    shared.transport(client, state, monkeypatch)
    enable_native(client, state, monkeypatch)
    result = await native.invoke(client, shared.declaration(ghost=ghost))
    assert result["status"] == "completed", result
    waits = [row for row in state["wire_calls"] if "nativeWait" in row["args"]]
    assert len(waits) == 2 and all(len(row["args"]["positions"]) == 1 for row in waits)
    assert any(row["tankKind"] == "named_output" for row in waits[0]["args"]["nativeWait"]["conditions"] if row["kind"] == "fluid_match")
    assert state["fluid_activations"] == ["input", "output"] and state["cursor"] is None


async def test_shared_named_owners_keep_full_pair(single_machine, monkeypatch):
    await check_shared(single_machine, monkeypatch, ghost=False)


async def test_shared_named_owners_keep_configured_ghost_semantics(ghost_machine, monkeypatch):
    await check_shared(ghost_machine, monkeypatch, ghost=True)


async def test_discovery_preserves_exact_native_version_only(native_bridge):
    client, _ = native_bridge
    for version, expected in [(1, 1), (True, 0), (2, 0)]:
        health = batch.health(); health["nativeObservationWaitVersion"] = version
        discovery = _workflow_discovery(client, client.configuration.read(), health)
        assert discovery.bind(client, client.configuration.read(), "session")["nativeObservationWaitVersion"] == expected
