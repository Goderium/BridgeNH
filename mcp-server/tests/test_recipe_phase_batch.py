"""Offline full-contract composite reads; no live latency or atomicity claim."""
import copy

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_fluid_recipe_tasks as fluid
import test_shared_basic_fluid_tasks as shared
from test_single_recipe_tasks import single_machine
from test_ghost_recipe_tasks import ghost_machine
from test_inspect_many_player_inventory import advertised, break_receipt, PLAYER_ID
from minecraft_codex_bridge.bridge import (
    BridgeError, _ContextData, _REQUEST_CONTEXT, request_context, response_context,
)
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, _Unavailable
from minecraft_codex_bridge.workflows import run_workflow, _workflow_discovery

native_bridge = native.native_bridge


def complete_items(value):
    if isinstance(value, dict):
        if all(key in value for key in ("registryName", "metadata", "count", "hasTag")):
            value.setdefault("displayName", "Exact public item")
        for child in value.values():
            complete_items(child)
    elif isinstance(value, list):
        for child in value:
            complete_items(child)


def composite(owners, player, snapshot, args):
    complete_items(owners)
    complete_items(player)
    snapshot.setdefault("clientTicks", 10)
    snapshot["player"].update(playerId=PLAYER_ID, selectedHotbarSlot=7)
    player.update(name="container.inventory", complete=True, source="client",
        inventorySource="current_player_inventory", playerInventoryBindingVerified=True,
        playerId=PLAYER_ID, sessionId=snapshot["sessionId"], clientTicks=snapshot["clientTicks"],
        dimension=args["dimension"], worldTick=75, selectedHotbarSlot=7)
    for owner in owners:
        # Older narrow task mocks omit full public block metadata; the enabled
        # composite mock explicitly supplies the actual typed field contract.
        owner.setdefault("metadata", 0)
        if "inventory" in owner:
            owner["inventory"].setdefault("name", "Complete real owner")
        if "fluids" in owner and "namedTanks" in owner["fluids"]:
            # The named-task mock deliberately poisons legacy indices. This
            # separate full-contract fixture publishes complete indexed tanks
            # as well as the original complete named evidence.
            legacy = []
            for index, tank in enumerate(owner["fluids"]["namedTanks"]["tanks"]):
                entry = {"index": index, "capacity": tank["capacity"]}
                if not tank["empty"]:
                    entry.update(fluid=tank["fluid"]["fluid"], amount=tank["fluid"]["amount"])
                legacy.append(entry)
            owner["fluids"].update(tanks=legacy, totalTanks=len(legacy), truncated=False)
    data = {"source": "integrated_server", "dimension": args["dimension"],
        "results": [{"ok": True, "data": owner} for owner in owners], "playerInventory": player,
        "playerInventoryObservation": {"version": 1, "phase": "after", "nonAtomic": True,
            "ownerReadBeforeInventory": True, "inventoryComplete": True,
            "inventoryBytes": json_size(player), "playerInventoryByteBudget": args["playerInventoryByteBudget"],
            "observationByteBudget": args["observationByteBudget"], "actualObservationBytes": 0,
            "workBudgetNanos": 4_000_000, "coverage": "Complete current inventory and exact full server owners."}}
    evidence = {"version": 1, "operation": "world.inspect_many", "phase": "after", "snapshot": snapshot}
    for _ in range(8):
        data["playerInventoryObservation"]["actualObservationBytes"] = json_size({"data": data, "context": evidence})
    return _ContextData(data, evidence)


def health():
    return {"protocolVersion": 1, "sessionId": "session", "profile": "developer",
        "capabilities": ["world.inspect_many"], **advertised(),
        "background": native.PROTECTED.copy(),
        "contextRpc": {"version": 1, "reads": ["world.inspect_many"], "actions": []}}


def unit_task(client):
    task = _RecipeTask(client, native.declaration(), run_workflow)
    task.approach_health = health()
    return task


def unit_receipt(args):
    owners = [{**point, "dimension": args["dimension"], "source": "integrated_server",
        "registryName": "minecraft:air", "metadata": 0, "extraPublicEvidence": {"untouched": True}}
        for point in args["positions"]]
    player = {"totalSlots": 41, "truncated": False,
        "slots": [{"index": index, "item": None} for index in range(41)], "cursorItem": None}
    snapshot = {"sessionId": "session", "profile": "developer", "worldLoaded": True,
        "world": {"worldName": "test-world", "worldFolder": "test-world", "dimension": 180},
        "background": native.PROTECTED.copy(), "player": {"name": "TestPlayer", "health": 20}}
    return composite(owners, player, snapshot, args)


async def test_one_guarded_rpc_retains_exact_raw_views_order_dedup_and_full_context(native_bridge, monkeypatch):
    client, state = native_bridge
    task = unit_task(client)
    received = []

    async def call(operation, args, **options):
        assert operation == "world.inspect_many"
        assert _REQUEST_CONTEXT.get() == {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
            "expectedWorld": "test-world", "requireBackground": True}
        assert "includeContext" not in args
        result = unit_receipt(args)
        received.append((copy.deepcopy(args), result, copy.deepcopy(dict(result)), copy.deepcopy(response_context(result))))
        return result

    monkeypatch.setattr(client, "call", call)
    positions = [task.position("input"), task.position("output"), task.position("input")]
    owners, player = await task.read_owner_phase(positions)
    args, raw, before, before_context = received[0]
    assert len(received) == 1 and len(args["positions"]) == 2
    assert args["compact"] is False and args["includePlayerInventory"] is True
    assert args["playerInventoryByteBudget"] == 131_072 and args["observationByteBudget"] == 1_048_576
    assert owners[0] is owners[2] is raw["results"][0]["data"]
    assert owners[1] is raw["results"][1]["data"] and player is raw["playerInventory"]
    assert dict(raw) == before and response_context(raw) == before_context
    assert len(player["slots"]) == 41 and all(row["item"] is None for row in player["slots"])
    assert _REQUEST_CONTEXT.get() is None


@pytest.mark.parametrize("initial", [None, False])
async def test_initial_unprotected_phase_keeps_old_chain_until_a_trusted_workflow_takeover(native_bridge, monkeypatch, initial):
    client, state = native_bridge
    task = unit_task(client)
    if initial is None:
        task.approach_health.pop("background")
    else:
        task.approach_health["background"]["enabled"] = False
    calls = []

    async def call(operation, args, **options):
        calls.append(operation)
        return unit_receipt(args)

    monkeypatch.setattr(client, "call", call)
    assert await task.read_owner_phase([task.position("input")]) is None and calls == []
    # This is the existing trusted run_workflow receipt marker, not an action
    # invented by the optimization or a cached owner/player observation.
    task._workflow_background_required = True
    assert await task.read_owner_phase([task.position("input")]) is not None
    assert calls == ["world.inspect_many"]


async def test_unprotected_initial_hint_cannot_hide_malformed_advertised_contract(native_bridge):
    client, state = native_bridge
    task = unit_task(client)
    task.approach_health["background"]["enabled"] = False
    task.approach_health["worldInspectManyPlayerInventoryContract"]["phase"] = "before"
    with pytest.raises(BridgeError, match="contract"):
        await task.read_owner_phase([task.position("input")])
    assert not state["calls"]


async def test_more_than_sixteen_legitimate_distinct_owners_keeps_original_chain_without_chunks(native_bridge, monkeypatch):
    client, state = native_bridge
    task = unit_task(client)

    async def forbidden(*args, **kwargs):
        raise AssertionError("A large phase must not dispatch a partial batch or multiple observation epochs.")

    monkeypatch.setattr(client, "call", forbidden)
    positions = [{"x": index, "y": 21, "z": 20, "dimension": 180} for index in range(18)]
    assert await task.read_owner_phase(positions) is None


async def test_background_released_after_good_phase_rejects_instead_of_silently_falling_back(native_bridge, monkeypatch):
    client, state = native_bridge
    task = unit_task(client)
    calls = []

    async def call(operation, args, **options):
        calls.append(operation)
        raw = unit_receipt(args)
        if len(calls) == 2:
            response_context(raw)["snapshot"]["background"]["enabled"] = False
        return raw

    monkeypatch.setattr(client, "call", call)
    assert await task.read_owner_phase([task.position("input")]) is not None
    with pytest.raises(BridgeError) as caught:
        await task.read_owner_phase([task.position("input")])
    assert caught.value.code == "TAKEOVER_LOST" and calls == ["world.inspect_many", "world.inspect_many"]


@pytest.mark.parametrize("flag", [None, False])
async def test_old_bridge_fallback_does_not_dispatch_speculative_batch(native_bridge, monkeypatch, flag):
    client, state = native_bridge
    task = unit_task(client)
    task.approach_health.pop("worldInspectManyPlayerInventory")
    if flag is not None:
        task.approach_health["worldInspectManyPlayerInventory"] = flag

    async def forbidden(*args, **kwargs):
        raise AssertionError("Capability fallback must not issue any RPC.")

    monkeypatch.setattr(client, "call", forbidden)
    assert await task.read_owner_phase([task.position("input")]) is None


@pytest.mark.parametrize("fault", ["flag_type", "contract_phase", "limits", "context_capability", "operation_capability"])
async def test_advertised_malformed_contract_refuses_before_any_read(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task = unit_task(client)
    if fault == "flag_type": task.approach_health["worldInspectManyPlayerInventory"] = 1
    elif fault == "contract_phase": task.approach_health["worldInspectManyPlayerInventoryContract"]["phase"] = "before"
    elif fault == "limits": task.approach_health["limits"].pop("inspectManyPlayerInventorySlots")
    elif fault == "context_capability": task.approach_health["contextRpc"]["reads"] = []
    else: task.approach_health["capabilities"] = []

    async def forbidden(*args, **kwargs):
        raise AssertionError("Malformed advertisement must fail before dispatch.")

    monkeypatch.setattr(client, "call", forbidden)
    with pytest.raises((BridgeError, _Unavailable)):
        await task.read_owner_phase([task.position("input")])


@pytest.mark.parametrize("fault", [
    "missing_inventory", "missing_metadata", "partial_slots", "truncated", "duplicate_index", "bool_index",
    "index_hole", "missing_item", "bad_item", "missing_cursor", "bad_cursor", "unknown_binding", "wrong_player",
    "wrong_session", "wrong_ticks", "bool_ticks", "wrong_hotbar", "wrong_dimension", "wrong_world_dimension",
    "bad_world_tick", "bad_source", "compact", "missing_owner", "owner_error", "wrong_owner",
    "bool_owner_dimension", "owner_inventory_partial", "owner_tanks_partial", "owner_tanks_truncated",
    "bad_work_budget", "bad_phase", "bad_nonatomic", "invented_budget", "inventory_overflow",
    "observation_overflow", "missing_context", "context_before", "context_operation", "context_version_bool",
    "context_extra", "context_session", "context_profile", "context_world", "background_lost", "owner_order",
])
async def test_full_receipt_guard_failures_never_fallback_or_replay(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task = unit_task(client)
    calls = []

    async def call(operation, args, **options):
        calls.append(operation)
        assert operation == "world.inspect_many"
        raw = unit_receipt(args)
        evidence = response_context(raw)
        if fault == "missing_context": return dict(raw)
        elif fault == "context_before": evidence["phase"] = "before"
        elif fault == "context_operation": evidence["operation"] = "world.inspect"
        elif fault == "context_version_bool": evidence["version"] = True
        elif fault == "context_extra": evidence["oldSnapshot"] = {}
        elif fault == "context_session": evidence["snapshot"]["sessionId"] = "other"
        elif fault == "context_profile": evidence["snapshot"]["profile"] = "player"
        elif fault == "context_world": evidence["snapshot"]["world"]["worldName"] = evidence["snapshot"]["world"]["worldFolder"] = "other"
        elif fault == "background_lost": evidence["snapshot"]["background"]["enabled"] = False
        elif fault == "owner_order": raw["results"].reverse()
        elif fault == "observation_overflow": raw["playerInventoryObservation"]["actualObservationBytes"] = 1_048_577
        else: break_receipt(raw, evidence, fault)
        return raw

    monkeypatch.setattr(client, "call", call)
    with pytest.raises((BridgeError, _Unavailable)):
        await task.read_owner_phase([task.position("input"), task.position("output")])
    assert calls == ["world.inspect_many"] and not task.side_effects
    assert _REQUEST_CONTEXT.get() is None


async def test_discovery_binding_and_actual_after_epoch_are_rechecked_each_call(native_bridge, monkeypatch):
    client, state = native_bridge
    task = unit_task(client)
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health())
    calls = []

    async def call(operation, args, **options):
        raw = unit_receipt(args)
        raw["results"][0]["data"]["metadata"] = len(calls)
        response_context(raw)["snapshot"]["clientTicks"] += len(calls)
        raw["playerInventory"]["clientTicks"] += len(calls)
        calls.append(raw)
        return raw

    monkeypatch.setattr(client, "call", call)
    first = await task.read_owner_phase([task.position("input")])
    second = await task.read_owner_phase([task.position("input")])
    assert len(calls) == 2 and first[0][0]["metadata"] == 0 and second[0][0]["metadata"] == 1
    assert first[1]["clientTicks"] + 1 == second[1]["clientTicks"]
    assert first[0][0] is not second[0][0]
    task.args["expectedSessionId"] = "changed"
    with pytest.raises(BridgeError, match="discovery"):
        await task.read_owner_phase([task.position("input")])
    assert len(calls) == 2


def enable_composite(client, state, monkeypatch, *, hook=None, initial_background=True):
    """One mock public receipt; original handler calls only model its non-atomic collection."""
    original = client.call
    state["wire_calls"] = []
    state["composite_receipts"] = []

    async def call(operation, args, **options):
        state["wire_calls"].append({"operation": operation, "args": copy.deepcopy(args),
            "powerCount": state["vector_power_count"], "fluidActions": list(state.get("fluid_actions", []))})
        if operation == "world.inspect_many":
            assert _REQUEST_CONTEXT.get()["requireBackground"] is True
            # These mock collection calls model server/client internals, not
            # guarded transport operations nested beneath the real batch RPC.
            with request_context(None):
                owners = [copy.deepcopy(await original("world.inspect", {**point, "dimension": args["dimension"]}))
                    for point in args["positions"]]
                player = copy.deepcopy(await original("inventory.inspect", {}))
                snapshot = copy.deepcopy(await original("client.snapshot", {}))
            snapshot["clientTicks"] = len(state["composite_receipts"]) + 10
            raw = composite(owners, player, snapshot, args)
            if hook is not None: hook(raw, state, args)
            state["composite_receipts"].append(raw)
            return raw
        data = await original(operation, args, **options)
        if operation == "bridge.health":
            data.update(advertised(), contextRpc={"version": 1, "reads": ["world.inspect_many"], "actions": []})
            if not initial_background:
                data["background"]["enabled"] = False
        complete_items(data)
        return data

    monkeypatch.setattr(client, "call", call)


async def test_unprotected_start_reaches_existing_workflow_takeover_before_enabling_batches(native_bridge, monkeypatch):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, retained=False, baseline=False)
    enable_composite(client, state, monkeypatch, initial_background=False)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] == "completed", result
    first = next(index for index, row in enumerate(state["wire_calls"]) if row["operation"] == "world.inspect_many")
    prior = state["wire_calls"][:first]
    assert sum(row["operation"] == "world.inspect" for row in prior) >= 2
    assert any(row["operation"] == "player.interact" for row in prior)
    assert len(state["composite_receipts"]) > 0 and state["vector_power_count"] == 1


@pytest.mark.parametrize("fluid_only,partial", [(False, False), (False, True), (True, False), (True, True)])
async def test_full_composite_multi_fluid_path_conserves_resources_and_phase_boundaries(native_bridge, monkeypatch, fluid_only, partial):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch, fluid_only=fluid_only, partial=partial)
    enable_composite(client, state, monkeypatch)
    result = await native.invoke(client, fluid.declaration(fluid_only=fluid_only, partial=partial))
    assert result["status"] == "completed", result
    assert result["executionsCompleted"] == 1 and state["vector_power_count"] == 1
    assert state["fluid_actions"] == ["fluid_input", "fluid_output"] and state["cursor"] is None
    batches = [row for row in state["wire_calls"] if row["operation"] == "world.inspect_many"]
    assert len(batches[0]["args"]["positions"]) == 2
    stop = next(row for row in batches if row["powerCount"] == 1 and row["fluidActions"] == ["fluid_input"])
    assert len(stop["args"]["positions"]) == (2 if fluid_only else 4)
    final = batches[-1]
    assert final["args"]["positions"] == [fluid.FLUID_INPUT] if fluid_only else final["args"]["positions"] == [native.INPUT, fluid.FLUID_INPUT]
    # The later output final read occurs after first-row native GUI work, never from the terminal batch.
    final_index = max(index for index, row in enumerate(state["wire_calls"]) if row is final)
    later = state["wire_calls"][final_index + 1:]
    assert next(row for row in later if row["operation"] == "world.inspect")["args"] == {**fluid.FLUID_OUTPUT, "dimension": 180}
    assert any(row["operation"] == "player.interact" for row in later[:next(index for index, row in enumerate(later)
        if row["operation"] == "world.inspect")])
    # Every native fluid activation retains a second separate cursor read and server acceptance after a fresh batch.
    for index, row in enumerate(state["wire_calls"]):
        if row["operation"] == "gui.widget_action" and row["args"].get("action") == "activate":
            after = [entry for entry in state["wire_calls"][index + 1:] if entry["operation"] != "client.snapshot"]
            assert [entry["operation"] for entry in after[:3]] == ["world.inspect_many", "inventory.inspect", "world.inspect"]
    assert all(response_context(raw)["phase"] == "after" and raw["playerInventoryObservation"]["nonAtomic"] is True
        for raw in state["composite_receipts"])


@pytest.mark.parametrize("executions", [1, 2])
async def test_item_vector_multiple_execution_retrieval_has_fresh_owner_player_receipts(native_bridge, monkeypatch, executions):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, retained=False, baseline=True)
    enable_composite(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments(executions=executions))
    assert result["status"] == "completed", result
    assert state["vector_power_count"] == executions and state["cursor"] is None
    assert len(state["composite_receipts"]) >= 4 + 4 * executions
    assert all(len(raw["playerInventory"]["slots"]) == 36 for raw in state["composite_receipts"])


async def run_shared_batch(machine_fixture, monkeypatch, *, ghost=False):
    client, state = machine_fixture
    shared.transport(client, state, monkeypatch)
    enable_composite(client, state, monkeypatch)
    result = await native.invoke(client, shared.declaration(ghost=ghost))
    assert result["status"] == "completed", result
    assert all(len(raw["results"]) == 1 for raw in state["composite_receipts"])
    if ghost:
        first = next(index for index, row in enumerate(state["wire_calls"]) if row["operation"] == "world.inspect_many")
        assert any(row["operation"] == "world.inspect" and row["args"]["x"] == native.MACHINE["x"]
            for row in state["wire_calls"][:first])


async def test_shared_named_tanks_deduplicate_only_within_current_phase(single_machine, monkeypatch):
    await run_shared_batch(single_machine, monkeypatch)


async def test_shared_ghost_named_tanks_keep_original_prepare_reads(ghost_machine, monkeypatch):
    await run_shared_batch(ghost_machine, monkeypatch, ghost=True)


@pytest.mark.parametrize("fault,reason", [
    ("retained_second_input", "input_or_catalyst_acceptance_failed"),
    ("lost_catalyst", "input_or_catalyst_acceptance_failed"),
    ("extra_output", "multi_output_delta_mismatch"),
    ("lost_player_item", "multi_retrieval_conservation_failed"),
    ("owner_changed", "input_gui_owner_changed"),
    ("output_owner_changed", "multi_output_owner_changed"),
    ("unknown_feed", "BRIDGE_UNAVAILABLE"),
    ("unknown_retrieval", "BRIDGE_UNAVAILABLE"),
])
async def test_composite_item_path_retains_existing_conservation_and_no_replay_failures(native_bridge, monkeypatch, fault, reason):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, fault=fault)
    enable_composite(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert result["reason"] == reason, result
    assert state["vector_power_count"] <= 1


@pytest.mark.parametrize("fault,reason", [
    ("owner_drift", "registered_fluid_owner_changed"),
    ("residual_input", "fluid_tank_conservation_failed"),
    ("lost_unrelated", "fluid_player_conservation_failed"),
    ("unknown_pickup", "BRIDGE_UNAVAILABLE"),
    ("unknown_activate", "BRIDGE_UNAVAILABLE"),
    ("unknown_return", "BRIDGE_UNAVAILABLE"),
])
async def test_composite_fluid_path_keeps_every_owner_tank_player_and_unknown_action_guard(native_bridge, monkeypatch, fault, reason):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch, fault=fault)
    enable_composite(client, state, monkeypatch)
    result = await native.invoke(client, fluid.declaration())
    assert result["status"] != "completed", result
    assert result["reason"] == reason, result
    assert state["fluid_actions"].count("fluid_input") <= 1 and state["fluid_actions"].count("fluid_output") <= 1
    assert state["vector_power_count"] <= 1


@pytest.mark.parametrize("fault", ["missing_context", "wrong_player", "owner_error", "background_lost"])
async def test_advertised_batch_failure_stops_before_first_native_pickup_without_old_read_retry(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch, retained=False, baseline=False)

    def hook(raw, state, args):
        if fault == "missing_context": raw.context = None
        elif fault == "background_lost": raw.context["snapshot"]["background"]["enabled"] = False
        else: break_receipt(raw, raw.context, fault)

    enable_composite(client, state, monkeypatch, hook=hook)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert state["vector_power_count"] == 0 and state["cursor"] is None
    assert len(state["composite_receipts"]) == 1
    assert not any(row["operation"] in {"gui.slot_click", "gui.widget_action", "player.interact"} for row in state["wire_calls"])
