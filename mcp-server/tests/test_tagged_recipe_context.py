"""Finite recipe context uses real task/workflow paths and fresh complete NBT views."""

import copy
import time

import anyio
import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_fluid_recipe_tasks as fluids
import test_recipe_provider_cache as providers
import test_single_block_placement_recipe as placement
from minecraft_codex_bridge.bridge import (
    CONTEXT_ACTIONS, CONTEXT_READS, BridgeError, _ContextData, _REQUEST_CONTEXT, request_context, response_context,
)
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, run_recipe_once
from minecraft_codex_bridge.workflows import run_workflow
from test_inspect_many_player_inventory import advertised
from test_native_whole_stack_transfer import setup as whole_setup
from test_single_recipe_tasks import single_machine
from test_ghost_recipe_tasks import ghost_machine


native_bridge = native.native_bridge
provider_bridge = providers.provider_bridge
from_zero = placement.from_zero
NBT = '{channels:{glass:5},unrelated:{name:"preserved"}}'


def tagged_transport(client, state, monkeypatch, *, whole=False, base_call=None):
    original = client.call
    state["player_items"].update({index: None for index in range(36, 41)})
    state["player_items"][0] = {"registryName": "example:any_blueprint", "metadata": 32767, "count": 1, "hasTag": True}
    state["player_items"][40] = {"registryName": "unrelated:armor", "metadata": 2147483647, "count": 1, "hasTag": True}
    state.update(nbt={0: NBT, 40: '{equipment:{upgrade:1}}'}, selected=1, baubles_reads=0,
        baubles_fault=None, context_hook=None, operation_hook=None, context_actions=[], workflow_sizes=[])

    def public_inventory():
        return {"totalSlots": 41, "truncated": False, "cursorItem": copy.deepcopy(state["cursor"]),
            "selectedHotbarSlot": state["selected"], "slots": [
                {"index": index, "item": copy.deepcopy(state["player_items"][index])} for index in range(41)]}

    def full_inventory():
        value = public_inventory()
        value["complete"] = True
        for row in value["slots"]:
            if row["item"] is not None:
                row["item"]["nbtIdentity"] = state["nbt"].get(row["index"], "")
        return value

    async def snapshot():
        with request_context(None):
            value = await original("client.snapshot", {})
        value = copy.deepcopy(value)
        value["clientTicks"] = value["serverControl"]["sampleId"]
        state["context_player_id"] = value["serverControl"]["playerId"]
        value["player"].update(creative=not state.get("actual_survival", False), selectedHotbarSlot=state["selected"], playerId=state["context_player_id"])
        value["player"]["serverFlight"]["creative"] = value["player"]["creative"]
        value["serverControl"].update(selectedHotbarSlot=state["selected"], selectionSynchronized=True)
        return value

    async def call(operation, args, **options):
        context = copy.deepcopy(_REQUEST_CONTEXT.get())
        before = await snapshot() if context is not None and operation in CONTEXT_ACTIONS else None
        placement_before = full_inventory() if operation == "player.place_block" else None
        if operation in CONTEXT_ACTIONS:
            state["context_actions"].append({"operation": operation, "args": copy.deepcopy(args)})
        if operation == "client.snapshot":
            return await snapshot()
        if operation == "inventory.inspect":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            data = public_inventory()
        elif operation == "baubles.inspect":
            state["baubles_reads"] += 1
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args), "includeContext": context})
            assert args == {"source": args["source"], "includePlayerInventory": True, "limit": 1}
            data = {"supported": True, "readOnly": True, "source": "integrated_server" if args["source"] == "server" else "client",
                "sessionId": "session", "worldFolder": "test-world", "playerId": state["context_player_id"], "dimension": 180,
                "creative": not state.get("actual_survival", False), "flying": state["flying"], "cursorItem": copy.deepcopy(state["cursor"]),
                "complete": False, "partial": True, "hasMore": True, "totalSlots": 7, "slots": [],
                "playerInventory": full_inventory()}
            fault = state["baubles_fault"]
            if fault == "unsupported": data["supported"] = False
            elif fault == "actor": data["playerId"] = "other-player"
            elif fault == "world": data["worldFolder"] = "other-world"
            elif fault == "dimension": data["dimension"] = 0
            elif fault == "creative": data["creative"] = False
            elif fault == "flying": data["flying"] = False
            elif fault == "cursor": data["cursorItem"] = native.stack(native.MATERIAL, 1)
            elif fault == "read_only": data["readOnly"] = False
            elif fault == "partial": data["playerInventory"]["complete"] = False
            elif fault == "truncated": data["playerInventory"]["truncated"] = True
            elif fault == "missing_slot": data["playerInventory"]["slots"].pop()
            elif fault == "duplicate_slot": data["playerInventory"]["slots"][-1]["index"] = 0
            elif fault == "bool_count": data["playerInventory"]["slots"][0]["item"]["count"] = True
            elif fault == "missing_nbt": data["playerInventory"]["slots"][0]["item"].pop("nbtIdentity")
            elif fault == "empty_nbt": data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = ""
            elif fault == "untagged_nbt": data["playerInventory"]["slots"][9]["item"]["nbtIdentity"] = "{hidden:1}"
            elif fault == "server_nbt" and args["source"] == "server": data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = "{changed:1}"
            if state["context_hook"]:
                state["context_hook"](data, args)
        elif operation == "world.inspect_many" and whole:
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args), "includeContext": context})
            with request_context(None):
                owners = [await original("world.inspect", {**point, "dimension": 180}) for point in args["positions"]]
            for owner in owners:
                owner.setdefault("metadata", 0)
                owner.get("inventory", {}).setdefault("name", "Actual owner inventory")
                for row in owner.get("inventory", {}).get("slots", []):
                    if row["item"] is not None:
                        row["item"]["displayName"] = "Actual item"
            data = {"source": "integrated_server", "dimension": 180, "results": [
                {"ok": True, "data": owner} for owner in owners]}
            inventory = public_inventory()
            for row in inventory["slots"]:
                if row["item"] is not None:
                    row["item"]["displayName"] = "Actual item"
            actual = await snapshot()
            inventory.update(name="container.inventory", complete=True, source="client", inventorySource="current_player_inventory",
                playerInventoryBindingVerified=True, playerId=state["context_player_id"], sessionId="session", clientTicks=actual["clientTicks"], dimension=180, worldTick=75)
            data["playerInventory"] = inventory
            data["playerInventoryObservation"] = {"version": 1, "phase": "after", "nonAtomic": True,
                "ownerReadBeforeInventory": True, "inventoryComplete": True, "inventoryBytes": json_size(inventory),
                "playerInventoryByteBudget": args["playerInventoryByteBudget"], "observationByteBudget": args["observationByteBudget"],
                "actualObservationBytes": 0, "workBudgetNanos": 4000000, "coverage": "Full fresh player and exact public owner points"}
            evidence = {"version": 1, "operation": operation, "phase": "after", "snapshot": actual}
            for _ in range(8):
                data["playerInventoryObservation"]["actualObservationBytes"] = json_size({"data": data, "context": evidence})
            return _ContextData(data, evidence)
        elif operation == "gui.transfer_whole_stack" and whole:
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args), "includeContext": context})
            direction = args["direction"]
            owner_name = "input" if args["owner"]["x"] == native.INPUT["x"] else "output"
            player_index, machine_index = args["playerInventoryIndex"], args["machineInventoryIndex"]
            source_items, source_index, destination_items, destination_index = (
                (state["player_items"], player_index, state["vector_" + owner_name], machine_index)
                if direction == "player_to_machine" else (state["vector_" + owner_name], machine_index, state["player_items"], player_index))
            assert destination_items[destination_index] is None and source_items[source_index] == args["expectedItem"]
            destination_items[destination_index], source_items[source_index] = source_items[source_index], None
            data = {"version": 1, "operation": operation, "status": "completed", "sessionId": "session",
                **{key: copy.deepcopy(args[key]) for key in ("screenId", "windowId", "direction", "owner", "sourceSlot", "destinationSlot", "playerInventoryIndex", "machineInventoryIndex")},
                "count": args["expectedItem"]["count"], "clicksDispatched": 2, "clicksCompleted": 2,
                "serverPickupConfirmed": True, "serverPlaceConfirmed": True, "serverConfirmed": True,
                "allPlayerSlots": 41, "allMachineSlots": len(state["vector_" + owner_name]), "fullNbtConservation": True,
                "unrelatedSlotsPreserved": True, "selectedHotbarSlot": state["selected"], "cursorEmpty": True,
                "outcomeUnknown": False, "automaticReplayAllowed": False, "clientCursor": None, "serverCursor": None, "elapsedMillis": 1.0}
        else:
            with request_context(None):
                provider = base_call if operation == "gui.inspect" and state["open"] == "creative" and base_call is not None else original
                data = await provider(operation, args, **options)
            data = copy.deepcopy(data)
            if operation == "bridge.health":
                data["contextRpc"] = {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}
                if "baubles.inspect" not in data["capabilities"]:
                    data["capabilities"].append("baubles.inspect")
                if whole:
                    data.update(advertised())
                    data["wholeStackTransfer"] = copy.deepcopy(whole_setup()[0].approach_health["wholeStackTransfer"])
            elif operation == "gui.inspect" and whole and state["open"] in {"input", "output"}:
                for row in data["slots"]:
                    row["nativeContainerSlotId"] = row["slot"]
                    if row["item"] is not None:
                        row["item"]["displayName"] = "Actual item"
                owner = next(row["machineInventoryOwner"] for row in data["slots"] if row.get("machineInventoryBindingVerified"))
                data["wholeStackTransfer"] = {**copy.deepcopy(whole_setup()[0].approach_health["wholeStackTransfer"]), "supported": True, "owner": owner}
            elif operation == "player.hotbar":
                state["selected"] = args["slot"]
            elif operation == "player.place_block" and placement_before is not None:
                data["serverBefore"], data["serverAfter"] = placement_before, full_inventory()
                for inventory in (data["serverBefore"], data["serverAfter"]):
                    inventory.update(source="integrated_server", inventorySource="actual_player_inventory")
            if operation == "gui.inspect" and len(data.get("slots", [])) == 100:
                for slot_id, player_index in ((80, state["material_index"]), (81, state["circuit_index"])):
                    data["slots"][slot_id].update(playerInventoryIndex=player_index,
                        playerInventoryBindingVerified=True, machineInventoryBindingVerified=False,
                        inventoryBinding="public_isHere_unique_actual_player_inventory_slot", inventorySource="current_player_inventory")
        if state["operation_hook"]:
            state["operation_hook"](operation, args, data)
        if context is not None:
            if operation == "player.plan_approach" and response_context(data) is not None:
                return data
            evidence = {"version": 1, "operation": operation, "phase": "before" if before is not None else "after",
                "snapshot": before if before is not None else await snapshot()}
            if operation == "baubles.inspect":
                if state["baubles_fault"] == "missing_context": return data
                if state["baubles_fault"] == "wrong_phase": evidence["phase"] = "before"
                if state["baubles_fault"] == "bool_version": evidence["version"] = True
            return _ContextData(data, evidence)
        return data

    monkeypatch.setattr(client, "call", call)
    return public_inventory, full_inventory


def finite_setup(client, state, monkeypatch, *, whole=False, retained=True, baseline=False, consumed=1, outputs=1, catalysts=1):
    base = client.call
    multi.vector_transport(client, state, monkeypatch, consumed=consumed, outputs=outputs, catalysts=catalysts, retained=retained, baseline=baseline)
    return tagged_transport(client, state, monkeypatch, whole=whole, base_call=base)


@pytest.mark.parametrize("whole,baseline,retained,executions", [(False, True, False, 2), (False, False, True, 1), (True, False, False, 1)])
async def test_public_multi_full_and_partial_feeds_retrieval_recovery_keep_arbitrary_context(native_bridge, monkeypatch, whole, baseline, retained, executions):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch, whole=whole, retained=retained, baseline=baseline, consumed=2, outputs=2, catalysts=2)
    if whole:
        state["player_items"][9]["count"] = 8
        state["player_items"][10]["count"] = 2
    original = copy.deepcopy({index: (state["player_items"][index], state["nbt"][index]) for index in (0, 40)})
    result = await native.invoke(client, multi.arguments(consumed=2, outputs=2, catalysts=2, executions=executions))
    assert result["status"] == "completed", result
    proof = result["retainedPlayerContext"]
    assert proof["protectedIndices"] == [0, 40] and proof["completeDualFullNbtConserved"]
    assert {index: (state["player_items"][index], state["nbt"][index]) for index in (0, 40)} == original
    assert proof["confirmedInventoryChanges"] and all(row["completeDualFullNbtVerified"] for row in proof["confirmedInventoryChanges"])
    assert any(row["phase"] == "recover_catalyst" for row in proof["confirmedInventoryChanges"])
    assert bool([call for call in state["calls"] if call["operation"] == "gui.transfer_whole_stack"]) is whole
    assert state["cursor"] is None and state["baubles_reads"] >= 12


@pytest.mark.parametrize("whole", [False, True])
async def test_native_compound_order_can_change_between_sides_and_checkpoints(native_bridge, monkeypatch, whole):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch, whole=whole)
    variants = [
        '{name:"",internalCurrentPower:123.5d,pattern:{in:[0:{id:4s,Count:1b}]},mode:0}',
        '{mode:0,pattern:{in:[0:{Count:1b,id:4s}]},internalCurrentPower:123.5d,name:""}',
    ]
    state["nbt"][0] = variants[0]
    observed = []

    def hook(data, args):
        text = variants[(state["baubles_reads"] // 2 + (args["source"] == "server")) % 2]
        data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = text
        observed.append(data)

    state["context_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "completed", result
    proof = result["retainedPlayerContext"]
    assert proof["completeDualFullNbtConserved"]
    assert all(0 not in row["changedIndices"] for row in proof["confirmedInventoryChanges"])
    assert {data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] for data in observed} == set(variants)
    assert state["nbt"][0] == variants[0] and state["cursor"] is None


@pytest.mark.parametrize("different", [
    '{b:[0:1,1:2],a:1L}',  # Numeric type is significant.
    '{b:[0:1,1:2],a:2}',
    '{b:[0:2,1:1],a:1}',  # List order is significant.
    '{b:[0:1,1:2],a:1,a:1}',  # Ambiguous compounds remain opaque.
])
async def test_compound_order_comparison_never_accepts_real_or_ambiguous_tag_changes(native_bridge, monkeypatch, different):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    state["nbt"][0] = '{a:1,b:[0:1,1:2]}'

    def hook(data, args):
        if args["source"] == "server":
            data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = different

    state["context_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "unavailable", result
    assert state["context_actions"] == [] and state["vector_power_count"] == 0


@pytest.mark.parametrize("fault", ["unsupported", "actor", "world", "dimension", "creative", "flying", "cursor", "read_only", "partial",
    "truncated", "missing_slot", "duplicate_slot", "bool_count", "missing_nbt", "empty_nbt", "untagged_nbt", "server_nbt", "missing_context", "wrong_phase", "bool_version"])
async def test_unavailable_full_identity_refuses_before_any_action(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    state["baubles_fault"] = fault
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "unavailable" and state["context_actions"] == [] and state["vector_power_count"] == 0, result


@pytest.mark.parametrize("capability", ["operation", "context"])
async def test_absent_full_nbt_support_never_speculates(native_bridge, monkeypatch, capability):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    def hook(operation, args, data):
        if operation == "bridge.health":
            if capability == "context": data["contextRpc"]["reads"].remove("baubles.inspect")
            else: data["capabilities"].remove("baubles.inspect")
    state["operation_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "unavailable" and state["baubles_reads"] == 0 and state["context_actions"] == [], result


@pytest.mark.parametrize("drift", ["nbt", "count", "metadata", "registry", "move", "unrelated_untagged"])
async def test_fresh_queued_predispatch_guard_refuses_live_context_drift(native_bridge, monkeypatch, drift):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    changed = False
    def hook(operation, args, data):
        nonlocal changed
        if operation == "gui.inspect" and state["open"] == "input" and not changed:
            changed = True
            if drift == "nbt": state["nbt"][0] = "{changed:1}"
            elif drift == "count": state["player_items"][0]["count"] += 1
            elif drift == "metadata": state["player_items"][0]["metadata"] = 0
            elif drift == "registry": state["player_items"][0]["registryName"] = "other:context"
            elif drift == "move": state["player_items"][23], state["player_items"][0] = state["player_items"][0], None
            else: state["player_items"][9]["count"] -= 1
    state["operation_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert changed and result["status"] == "failed" and state["vector_power_count"] == 0, result
    assert not any(call["operation"] in {"gui.slot_click", "gui.transfer_whole_stack"} for call in state["context_actions"])


async def test_compact_native_receipt_cannot_hide_tag_drift_and_never_replays(native_bridge, monkeypatch):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch, whole=True)
    state["player_items"][9]["count"] = 8
    def hook(operation, args, data):
        if operation == "gui.transfer_whole_stack": state["nbt"][40] = "{changed:1}"
    state["operation_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "failed" and state["vector_power_count"] == 0, result
    assert sum(call["operation"] == "gui.transfer_whole_stack" for call in state["context_actions"]) == 1
    assert not any(call["operation"] == "gui.slot_click" for call in state["context_actions"])
    assert not result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["cursor"] is None


@pytest.mark.parametrize("raw", ["consumed", "catalyst", "machine"])
async def test_tagged_material_and_catalyst_semantics_stay_strict(native_bridge, monkeypatch, raw):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    if raw == "machine": state["vector_input"][0]["hasTag"] = True
    else: state["recipe"]["items"]["inputs"]["entries"][0 if raw == "consumed" else 1]["stack"]["hasTag"] = True
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "unavailable" and state["context_actions"] == [] and state["vector_power_count"] == 0, result


@pytest.mark.parametrize("tagged", [False, True])
async def test_declared_fluid_transformations_beside_unrelated_tags_have_exact_checkpoints(native_bridge, monkeypatch, tagged):
    client, state = native_bridge
    base = client.call
    fluids.transport(client, state, monkeypatch, tagged=tagged)
    # Fluid declarations own slots 0 and 1; unrelated context occupies actual slot 23.
    saved = copy.deepcopy({index: state["player_items"][index] for index in (0, 1)})
    tagged_transport(client, state, monkeypatch, base_call=base)
    state["player_items"][23], state["nbt"][23] = state["player_items"][0], state["nbt"].pop(0)
    state["player_items"].update(saved)
    if tagged:
        state["nbt"].update({0: "{fluid:hydrogen}", 1: "{fluid:empty}"})
    def hook(operation, args, data):
        if operation == "gui.widget_action" and state["open"] in state["tanks"]:
            state["nbt"][0 if state["open"] == "fluid_input" else 1] = "{fluid:transformed}" if tagged else ""
    state["operation_hook"] = hook
    result = await native.invoke(client, fluids.declaration())
    assert result["status"] == "completed", result
    proof = result["retainedPlayerContext"]
    assert proof["protectedIndices"] == [23, 40] and proof["explicitFluidConversionIndices"] == [0, 1]
    assert [row["changedIndices"] for row in proof["confirmedInventoryChanges"] if row["phase"].startswith("fluid_container_")] == [[0], [1]]
    assert proof["completeDualFullNbtConserved"] and state["nbt"][23] == NBT and state["cursor"] is None


@pytest.mark.parametrize("restore", [False, True])
async def test_nonnoop_real_hand_borrow_and_restore_accounts_every_slot(native_bridge, monkeypatch, restore):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    for index in range(1, 9): state["player_items"][index] = native.stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1, restoreInventory=restore))
    assert result["status"] == "completed", result
    phases = [row["phase"] for row in result["retainedPlayerContext"]["confirmedInventoryChanges"]]
    assert "hand_store_0" in phases and ("hand_restore_0" in phases) is restore
    assert result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["nbt"][0] == NBT
    assert any(call["operation"] == "gui.widget_action" and call["args"].get("widgetId", "").startswith("creative:slot:") for call in state["context_actions"])


async def test_finite_native_give_adopts_only_exact_generated_slot(native_bridge, monkeypatch):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    state["player_items"][9] = None
    state["material"] = 0
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1, generateMaterials=True))
    assert result["status"] == "completed", result
    assert sum(call["operation"] == "chat.send" for call in state["context_actions"]) == 1
    assert any(row["phase"] == "generate_material" for row in result["retainedPlayerContext"]["confirmedInventoryChanges"])
    assert result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["nbt"][0] == NBT


async def test_legacy_machine_path_keeps_complete_context(native_bridge, monkeypatch):
    client, state = native_bridge
    base = client.call
    await client.call("inventory.inspect", {})
    tagged_transport(client, state, monkeypatch, base_call=base)
    result = await native.invoke(client, native.declaration())
    assert result["status"] == "completed", result
    assert result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["nbt"][0] == NBT and state["cursor"] is None


async def test_generic_single_machine_path_keeps_complete_context(single_machine, monkeypatch):
    client, state = single_machine
    finite_setup(client, state, monkeypatch, consumed=1, outputs=2, catalysts=0)
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=2, catalysts=0))
    assert result["status"] == "completed", result
    assert result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["nbt"][0] == NBT and state["cursor"] is None


async def test_generic_configured_ghost_path_keeps_complete_context(ghost_machine, monkeypatch):
    client, state = ghost_machine
    finite_setup(client, state, monkeypatch, consumed=1, outputs=2, catalysts=1)
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=2, catalysts=1, catalystMode="configured_ghost"))
    assert result["status"] == "completed", result
    assert result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["nbt"][0] == NBT and state["cursor"] is None


async def test_existing_local_provider_output_is_read_only_and_does_not_widen_context_exemptions(provider_bridge, monkeypatch):
    client, state = provider_bridge
    await client.call("inventory.inspect", {})
    tagged_transport(client, state, monkeypatch)
    result = await native.invoke(client, providers.declaration())
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputMode"] == "provider_cache" and result["retainedPlayerContext"]["completeDualFullNbtConserved"]
    assert not any(call["operation"].startswith("ae.") for call in state["context_actions"])


@pytest.mark.parametrize("unknown", ["feed", "retrieval", "catalyst"])
async def test_unknown_partial_native_burst_never_adopts_or_replays_context(native_bridge, monkeypatch, unknown):
    client, state = native_bridge
    base = client.call
    multi.vector_transport(client, state, monkeypatch, consumed=1, outputs=1, retained=False, baseline=False,
        fault={"feed": "unknown_feed", "retrieval": "unknown_retrieval", "catalyst": "unknown_catalyst_recovery"}[unknown])
    tagged_transport(client, state, monkeypatch, base_call=base)
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "failed" and result["outcomeUnknown"], result
    assert state["cursor"] is not None and not result["retainedPlayerContext"]["completeDualFullNbtConserved"]
    pickups = [call for call in state["context_actions"] if call["operation"] == "gui.slot_click" and call["args"]["button"] == 0]
    assert pickups and state["nbt"][0] == NBT
    assert not any(call["operation"] in {"chat.send", "gui.transfer_whole_stack"} for call in state["context_actions"])


@pytest.mark.parametrize("side", ["client", "server"])
async def test_final_complete_full_nbt_drift_blocks_conserved_acceptance(native_bridge, monkeypatch, side):
    client, state = native_bridge
    finite_setup(client, state, monkeypatch)
    # Mark only entry into the actual final verifier; its reads and guards still run.
    original_verify = _RecipeTask.verify_retained_inventory
    async def verify(self, **options):
        state["final_verification"] = True
        return await original_verify(self, **options)
    monkeypatch.setattr(_RecipeTask, "verify_retained_inventory", verify)
    def hook(data, args):
        if state.get("final_verification") and args["source"] == side:
            data["playerInventory"]["slots"][40]["item"]["nbtIdentity"] = "{changed_at_final:1}"
    state["context_hook"] = hook
    result = await native.invoke(client, multi.arguments(consumed=1, outputs=1))
    assert result["status"] == "failed" and state["vector_power_count"] == 1, result
    assert not result["retainedPlayerContext"]["completeDualFullNbtConserved"] and state["cursor"] is None


async def test_survival_single_placement_first_charge_partial_feeds_and_recovery_fit_real_barrier_budget(from_zero, monkeypatch):
    client, state = from_zero
    state["actual_survival"] = True
    tagged_transport(client, state, monkeypatch)
    state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"] = 16
    arguments = placement.declaration(state)
    arguments["backendInputs"][0]["count"] = 16
    sizes = []
    async def runner(client, arguments, lock, **options):
        sizes.append(len(arguments["steps"]))
        assert len(arguments["steps"]) <= 32
        return await run_workflow(client, arguments, lock, **options)
    result = await run_recipe_once(client, arguments, anyio.Lock(), runner=runner)
    assert result["status"] == "completed", result
    proof = result["retainedPlayerContext"]
    assert proof["completeDualFullNbtConserved"] and proof["confirmedInventoryChanges"][0]["phase"] == "single_block_placement"
    assert sizes and max(sizes) == 32 and sum(call["operation"] == "player.place_block" for call in state["context_actions"]) == 1
    assert state["nbt"][0] == NBT and state["cursor"] is None
