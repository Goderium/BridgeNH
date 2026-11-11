import copy
import json

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeError, CONTEXT_ACTIONS, CONTEXT_READS
from minecraft_codex_bridge.construction_tasks import run_blueprint_build
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import (
    _PLAYER_INVENTORY_CONTRACT, _PLAYER_INVENTORY_LIMITS, _guard,
    _inspect_many_player_inventory_available, _validate_player_inventory_observation,
    _workflow_discovery, run_workflow,
)
from test_construction_tasks import BACKGROUND, declaration, native_construction


POINT = {"x": -44, "y": 3, "z": 125}
PLAYER_ID = "550e8400-e29b-41d4-a716-446655440000"


def combined_args():
    return {"positions": [POINT.copy()], "dimension": 0, "compact": False, "includePlayerInventory": True,
        "playerInventoryByteBudget": 131_072, "observationByteBudget": 262_144}


def advertised():
    return {"worldInspectManyPlayerInventory": True,
        "worldInspectManyPlayerInventoryContract": {**_PLAYER_INVENTORY_CONTRACT,
            "requiredContext": "expectedWorld and requireBackground=true, in addition to the existing session/profile guard",
            "budgets": "Required playerInventoryByteBudget and observationByteBudget.", "coverage": "Full fresh current inventory and owner points."},
        "limits": _PLAYER_INVENTORY_LIMITS.copy()}


def add_composite(data, actual_snapshot, inventory, args):
    inventory.update(name="container.inventory", complete=True, source="client", inventorySource="current_player_inventory",
        playerInventoryBindingVerified=True, playerId=PLAYER_ID, sessionId="session", clientTicks=10, dimension=0, worldTick=75)
    for row in inventory["slots"]:
        if row["item"] is not None:
            row["item"].setdefault("displayName", "实际物品")
    if inventory["cursorItem"] is not None:
        inventory["cursorItem"].setdefault("displayName", "游标物品")
    for row in data["results"]:
        if row.get("ok") and "inventory" in row["data"]:
            row["data"]["inventory"].setdefault("name", "Actual owner inventory")
    data["playerInventory"] = inventory
    data["playerInventoryObservation"] = {"version": 1, "phase": "after", "nonAtomic": True,
        "ownerReadBeforeInventory": True, "inventoryComplete": True, "inventoryBytes": json_size(inventory),
        "playerInventoryByteBudget": args["playerInventoryByteBudget"], "observationByteBudget": args["observationByteBudget"],
        "actualObservationBytes": 0, "workBudgetNanos": 4_000_000,
        "coverage": "Full fresh current-player client inventory and unmodified full integrated-server owner points."}
    evidence = {"version": 1, "operation": "world.inspect_many", "phase": "after", "snapshot": actual_snapshot}
    for _ in range(8):
        data["playerInventoryObservation"]["actualObservationBytes"] = json_size({"data": data, "context": evidence})
    return evidence


def observation(total=41):
    snapshot = {"sessionId": "session", "profile": "developer", "worldLoaded": True, "clientTicks": 10,
        "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0, "time": 123},
        "background": BACKGROUND.copy(), "player": {"playerId": PLAYER_ID, "name": "TestPlayer", "health": 20, "selectedHotbarSlot": 7}}
    inventory = {"slots": [{"index": index, "item": None} for index in range(total)], "totalSlots": total,
        "truncated": False, "selectedHotbarSlot": 7, "cursorItem": None}
    data = {"source": "integrated_server", "dimension": 0, "results": [{"ok": True,
        "data": {**POINT, "source": "integrated_server", "dimension": 0, "registryName": "minecraft:air", "metadata": 0}}]}
    evidence = add_composite(data, snapshot, inventory, combined_args())
    return data, evidence


@pytest.mark.parametrize("total", [9, 41, 64, 256])
def test_all_current_inventory_slots_are_accepted_without_fixed_40_slot_assumption(total):
    data, evidence = observation(total)
    _validate_player_inventory_observation(data, combined_args(), evidence)
    assert data["playerInventory"]["name"] != evidence["snapshot"]["player"]["name"]
    assert data["playerInventory"]["worldTick"] != evidence["snapshot"]["world"]["time"]


def break_receipt(data, evidence, fault):
    inventory = data["playerInventory"]
    metadata = data["playerInventoryObservation"]
    if fault == "missing_inventory":
        data.pop("playerInventory")
    elif fault == "missing_metadata":
        data.pop("playerInventoryObservation")
    elif fault == "partial_slots":
        inventory["slots"].pop()
    elif fault == "truncated":
        inventory["truncated"] = True
    elif fault == "duplicate_index":
        inventory["slots"][-1]["index"] = 0
    elif fault == "bool_index":
        inventory["slots"][0]["index"] = False
    elif fault == "index_hole":
        inventory["slots"][-1]["index"] = 42
    elif fault == "missing_item":
        inventory["slots"][0].pop("item")
    elif fault == "bad_item":
        inventory["slots"][0]["item"] = {"registryName": "minecraft:stone", "count": 1, "metadata": 0, "hasTag": False}
    elif fault == "missing_cursor":
        inventory.pop("cursorItem")
    elif fault == "bad_cursor":
        inventory["cursorItem"] = "unknown"
    elif fault == "unknown_binding":
        inventory["playerInventoryBindingVerified"] = False
    elif fault == "wrong_player":
        inventory["playerId"] = "other"
    elif fault == "wrong_session":
        inventory["sessionId"] = "other"
    elif fault == "wrong_ticks":
        inventory["clientTicks"] += 1
    elif fault == "bool_ticks":
        inventory["clientTicks"] = True
    elif fault == "wrong_hotbar":
        inventory["selectedHotbarSlot"] = 0
    elif fault == "wrong_dimension":
        inventory["dimension"] = 1
    elif fault == "wrong_world_dimension":
        evidence["snapshot"]["world"]["dimension"] = 1
    elif fault == "bad_world_tick":
        inventory["worldTick"] = True
    elif fault == "bad_source":
        data["source"] = "client"
    elif fault == "compact":
        data["compact"] = True
    elif fault == "missing_owner":
        data["results"] = []
    elif fault == "owner_error":
        data["results"][0] = {"ok": False, "error": {"code": "chunk_unloaded"}}
    elif fault == "wrong_owner":
        data["results"][0]["data"]["x"] += 1
    elif fault == "bool_owner_dimension":
        data["results"][0]["data"]["dimension"] = False
    elif fault == "owner_inventory_partial":
        data["results"][0]["data"]["inventory"] = {"name": "owner", "slots": [], "totalSlots": 1, "truncated": False}
    elif fault == "owner_tanks_partial":
        data["results"][0]["data"]["fluids"] = {"tanks": [], "totalTanks": 1, "truncated": False}
    elif fault == "owner_tanks_truncated":
        data["results"][0]["data"]["fluids"] = {"tanks": [], "totalTanks": 0, "truncated": True}
    elif fault == "bad_work_budget":
        metadata["workBudgetNanos"] = 40_000_000
    elif fault == "bad_phase":
        metadata["phase"] = "before"
    elif fault == "bad_nonatomic":
        metadata["nonAtomic"] = False
    elif fault == "invented_budget":
        metadata["playerInventoryByteBudget"] += 1
    elif fault == "inventory_overflow":
        metadata["inventoryBytes"] = 131_073
    elif fault == "observation_overflow":
        metadata["actualObservationBytes"] = 262_145
    else:
        raise AssertionError(fault)


FAULTS = ["missing_inventory", "missing_metadata", "partial_slots", "truncated", "duplicate_index", "bool_index",
    "index_hole", "missing_item", "bad_item", "missing_cursor", "bad_cursor", "unknown_binding", "wrong_player",
    "wrong_session", "wrong_ticks", "bool_ticks", "wrong_hotbar", "wrong_dimension", "wrong_world_dimension", "bad_world_tick",
    "bad_source", "compact", "missing_owner", "owner_error", "wrong_owner", "bool_owner_dimension", "owner_inventory_partial",
    "owner_tanks_partial", "owner_tanks_truncated", "bad_work_budget", "bad_phase", "bad_nonatomic", "invented_budget",
    "inventory_overflow", "observation_overflow"]


@pytest.mark.parametrize("fault", FAULTS)
def test_malformed_full_composite_is_refused_without_partial_acceptance(fault):
    data, evidence = observation()
    break_receipt(data, evidence, fault)
    with pytest.raises(BridgeError) as error:
        _validate_player_inventory_observation(data, combined_args(), evidence)
    assert error.value.code == "MALFORMED_RESPONSE" and error.value.details["outcome_unknown"] is False


@pytest.fixture
def combined_construction(native_construction, monkeypatch):
    client, state = native_construction
    original = client.http._transport.handle_async_request

    def snapshot():
        position = state["position"]
        return {"sessionId": "session", "profile": "developer", "worldLoaded": True, "clientTicks": 10,
            "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0},
            "background": BACKGROUND.copy(), "screenOpen": state["gui"] is not None,
            "player": {"name": "TestPlayer", "health": state.get("health", 20), "playerId": PLAYER_ID, **position, "feetY": position["y"], "eyeY": position["y"] + 1.62,
                "flying": state["flying"], "selectedHotbarSlot": state["selected"],
                "serverFlight": {"available": True, "allowFlying": True, "flying": state["flying"], "dimension": 0}}}

    async def respond(request):
        body = json.loads(request.content)
        context, operation, args = body.get("includeContext"), body.get("operation"), body.get("args", {})
        before = snapshot() if context is not None and operation in CONTEXT_ACTIONS else None
        response = await original(request)
        envelope = json.loads(await response.aread())
        if envelope.get("ok") is True:
            if operation == "bridge.health":
                envelope["data"].update(advertised())
                envelope["data"]["contextRpc"] = {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}
                if state.get("health_mutation"):
                    state["health_mutation"](envelope["data"])
            if context is not None:
                actual = snapshot() if before is None else before
                _guard(actual, context["expectedSessionId"], context["expectedProfile"], context["expectedWorld"], context["requireBackground"])
                evidence = {"version": 1, "operation": operation, "phase": "after" if before is None else "before", "snapshot": actual}
                if args.get("includePlayerInventory"):
                    assert context["requireBackground"] is True and context["expectedWorld"] == "new-world"
                    inventory = {"slots": [{"index": index, "item": copy.deepcopy(state["items"].get(index))} for index in range(41)],
                        "cursorItem": copy.deepcopy(state["cursor"]), "selectedHotbarSlot": state["selected"], "totalSlots": 41, "truncated": False}
                    evidence = add_composite(envelope["data"], actual, inventory, args)
                    if state.get("composite_mutation"):
                        state["composite_mutation"](envelope["data"], evidence)
                envelope["context"] = evidence
                state.setdefault("guarded_responses", {})[body["requestId"]] = {
                    "data": copy.deepcopy(envelope["data"]), "context": copy.deepcopy(evidence)}
        return httpx.Response(response.status_code, json=envelope)

    monkeypatch.setattr(client.http, "_transport", httpx.MockTransport(respond))
    return client, state


async def test_pure_composite_read_establishes_required_guard_without_dummy_action(combined_construction):
    client, state = combined_construction
    result = await run_workflow(client, {"expectedSessionId": "session", "expectedWorld": "new-world",
        "steps": [{"id": "both", "kind": "read", "tool": "mcb_world_inspect_many", "args": combined_args()}]}, anyio.Lock())
    assert result["status"] == "completed" and result["dispatchedActions"] == 0
    calls = [row for row in state["calls"] if row["args"].get("includePlayerInventory")]
    assert len(calls) == 1 and calls[0]["includeContext"]["requireBackground"] is True
    assert not any(row["operation"] in ("player.hotbar", "player.place_block", "inventory.inspect") for row in state["calls"])


async def test_full_construction_composites_remove_two_inventory_rpcs_per_placement(combined_construction):
    client, state = combined_construction
    arguments = declaration()
    result = await run_blueprint_build(client, arguments, anyio.Lock())
    assert result["status"] == "completed" and result["formationVerified"]
    assert state["construct_count"] == 1 and len(state["placements"]) == 6
    calls = state["calls"]
    composites = [call for call in calls if call["args"].get("includePlayerInventory")]
    assert len(composites) == 2 * len(state["placements"])
    for index, call in enumerate(calls):
        if call["operation"] == "player.place_block":
            previous = calls[index - 1]
            assert previous["operation"] == "world.inspect_many"
            assert previous["args"].get("includePlayerInventory") is True
            expected_guard = {"version": 1, "expectedSessionId": arguments["expectedSessionId"],
                "expectedProfile": "developer", "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
            assert previous["includeContext"] == call["includeContext"] == expected_guard
            observed = state["guarded_responses"][previous["requestId"]]
            _validate_player_inventory_observation(observed["data"], previous["args"], observed["context"])
            native_context = state["guarded_responses"][call["requestId"]]["context"]
            assert native_context == {"version": 1, "operation": "player.place_block", "phase": "before",
                "snapshot": observed["context"]["snapshot"]}
            _guard(native_context["snapshot"], arguments["expectedSessionId"], "developer", arguments["expectedWorld"], True)
            assert calls[index + 1]["operation"] == "world.inspect_many"
            assert calls[index + 1]["args"].get("includePlayerInventory") is True
            following = state["guarded_responses"][calls[index + 1]["requestId"]]
            _validate_player_inventory_observation(following["data"], calls[index + 1]["args"], following["context"])
            assert calls[index + 2]["operation"] != "inventory.inspect"
    assert sum(call["operation"] == "bridge.health" for call in calls) == 1
    assert calls[-1]["operation"] == "inventory.inspect"


@pytest.mark.parametrize("fault", FAULTS)
async def test_malformed_composite_blocks_later_action_and_never_falls_back(combined_construction, fault):
    client, state = combined_construction
    state["composite_mutation"] = lambda data, evidence: break_receipt(data, evidence, fault)
    steps = [{"id": "both", "kind": "read", "tool": "mcb_world_inspect_many", "args": combined_args()},
        {"id": "blocked", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 1}}]
    result = await run_workflow(client, {"expectedSessionId": "session", "expectedWorld": "new-world", "steps": steps}, anyio.Lock())
    assert result["status"] == "failed" and result["error"]["code"] == "MALFORMED_RESPONSE"
    assert not result["outcomeUnknown"] and result["dispatchedActions"] == 0
    assert result["steps"][1]["status"] == "not_executed"
    assert sum(call["args"].get("includePlayerInventory") is True for call in state["calls"]) == 1
    assert not any(call["operation"] in ("inventory.inspect", "player.hotbar", "player.place_block") for call in state["calls"])


@pytest.mark.parametrize("fault", ["missing_inventory", "wrong_player", "owner_tanks_partial", "observation_overflow"])
async def test_bad_postplace_composite_keeps_one_dispatch_and_no_replay(combined_construction, fault):
    client, state = combined_construction
    def mutate(data, evidence):
        if state["placements"]:
            break_receipt(data, evidence, fault)
    state["composite_mutation"] = mutate
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "failed" and result["reason"] == "MALFORMED_RESPONSE"
    assert len(state["placements"]) == 1 and state["construct_count"] == 0
    failed = next(phase for phase in result["failedNativeWorkflows"] if phase.get("status") == "failed")
    assert failed["outcomeUnknown"] is False


@pytest.mark.parametrize("malformed", ["1", 1, None, {}])
def test_capability_boolean_never_coerces(malformed):
    health = {"profile": "developer", **advertised(), "contextRpc": {"version": 1, "reads": ["world.inspect_many"], "actions": []}}
    health["worldInspectManyPlayerInventory"] = malformed
    with pytest.raises(BridgeError):
        _inspect_many_player_inventory_available(health)


@pytest.mark.parametrize("key,value", [("version", True), ("compact", True), ("guardedOnly", False), ("phase", "before")])
def test_advertised_contract_malformed_is_not_capability_absence(key, value):
    health = {"profile": "developer", **advertised(), "contextRpc": {"version": 1, "reads": ["world.inspect_many"], "actions": []}}
    health["worldInspectManyPlayerInventoryContract"][key] = value
    with pytest.raises(BridgeError):
        _inspect_many_player_inventory_available(health)


@pytest.mark.parametrize("flag", ["worldScanBounds", "worldBlockPublicMaterial", "worldInspectManyCompact", "worldScanMinecraftAir"])
async def test_placement_flags_sealed_immutably_and_malformed_flags_refused(combined_construction, flag):
    client, _ = combined_construction
    health = await client.call("bridge.health", {})
    health[flag] = True
    seal = _workflow_discovery(client, client.configuration.read(), health)
    health[flag] = False
    health["worldInspectManyPlayerInventoryContract"]["phase"] = "before"
    bound = seal.bind(client, client.configuration.read(), "session")
    assert bound[flag] is True and bound["worldInspectManyPlayerInventoryContract"]["phase"] == "after"
    health[flag] = "true"
    with pytest.raises(BridgeError):
        _workflow_discovery(client, client.configuration.read(), health)


@pytest.mark.parametrize("changes", [{"playerInventoryByteBudget": None}, {"observationByteBudget": None}, {"compact": True},
    {"playerInventoryByteBudget": 1023}, {"playerInventoryByteBudget": 131073}, {"observationByteBudget": 8191},
    {"observationByteBudget": 1048577}, {"includePlayerInventory": False}, {"includePlayerInventory": "true"}])
def test_schema_requires_explicit_typed_budgets_and_full_mode(changes):
    args = {**combined_args(), **changes}
    if any(value is None for value in changes.values()):
        args = {key: value for key, value in args.items() if value is not None}
    import jsonschema
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_world_inspect_many").validate(args)


def test_plain_and_compact_inspect_many_schema_remain_available():
    validator = tool_validator("mcb_world_inspect_many")
    validator.validate({"positions": [POINT.copy()]})
    validator.validate({"positions": [POINT.copy()] * 128, "compact": True})
    validator.validate(combined_args())
