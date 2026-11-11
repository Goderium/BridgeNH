"""Full-NBT context proofs over real guarded workflows; no game or production writes."""

import copy
import json
import time

import httpx
import pytest

from minecraft_codex_bridge.bridge import CONTEXT_ACTIONS, CONTEXT_READS, BridgeError
from minecraft_codex_bridge.construction_tasks import _ConstructionTask, _context_inventory_rows
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import BACKGROUND, CONTROLLER, CONTROLLER_ITEM, declaration, key, native_construction, stack, writes


CHANNEL = '{channels:{glass:5},unrelated:{name:"preserved"}}'


@pytest.mark.parametrize("survival", [True, False])
async def test_reordered_trophy_nbt_retains_full_inventory_guards(tagged_bridge, survival):
    from test_nbt_order import CLIENT, SERVER
    client, state = tagged_bridge
    state["nbt"][40] = CLIENT
    def reordered(data, args, state):
        if args["source"] == "server":
            data["playerInventory"]["slots"][40]["item"]["nbtIdentity"] = SERVER
    state["baubles_hook"] = reordered
    instance = task(client, state, survival=survival)
    await instance.preflight()
    assert instance.tagged_context_inventory[40]["item"]["nbtIdentity"] == SERVER
    await instance.read_tagged_context_inventory(require_background=False)
    old = full_inventory(state)
    new = copy.deepcopy(old)
    new["slots"][40]["item"]["nbtIdentity"] = SERVER
    instance.accept_tagged_context_native_inventory(old, new)
    assert writes(state) == []
    changed = CLIENT.replace("1700000000000L", "1700000000001L")
    assert changed != CLIENT, "The negative fixture must change an NBT value"
    state["nbt"][40] = changed
    with pytest.raises(BridgeError):
        await instance.read_tagged_context_inventory(require_background=False)
    assert writes(state) == []


def full_inventory(state):
    return {"name": "Actual Player", "totalSlots": 41, "truncated": False, "complete": True,
        "slots": [{"index": index, "item": None if state["items"][index] is None else
            {**copy.deepcopy(state["items"][index]), "nbtIdentity": state["nbt"].get(index, "")}}
            for index in range(41)]}


def snapshot(state):
    state["context_tick"] += 1
    return {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": BACKGROUND.copy(),
        "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0}, "screenOpen": False,
        "player": {"health": 20, **state["position"], "feetY": state["position"]["y"],
            "eyeY": state["position"]["y"] + 1.62, "creative": not state["survival"], "flying": state["flying"],
            "selectedHotbarSlot": state["selected"], "serverFlight": {"available": True, "creative": not state["survival"],
                "flying": state["flying"], "allowFlying": True, "dimension": 0}},
        "serverControl": {"available": True, "source": "integrated_server_tick", "sessionId": "session",
            "worldName": "new-world", "worldFolder": "new-world", "dimension": 0, "sampleId": state["context_tick"],
            "ageMillis": 0, "playerId": "actual-player", "selectedHotbarSlot": state["selected"], "selectionSynchronized": True,
            "openContainer": {"isInventoryContainer": True, "windowId": 0},
            "clientContainer": {"isInventoryContainer": True, "windowId": 0}}}


@pytest.fixture
async def tagged_bridge(native_construction, monkeypatch):
    client, state = native_construction
    state.update(nbt={0: CHANNEL, 40: '{equipment:{upgrade:1}}'}, context_tick=0, survival=True, flying=False,
        baubles_reads=0, baubles_fault=None, baubles_hook=None, after_hint=None)
    state["items"][0]["hasTag"] = True
    state["items"][40] = {"registryName": "example:tagged_context", "metadata": 23, "count": 1, "hasTag": True}
    state["caps"].extend(["baubles.inspect", "structure.survival_build"])
    original = client.http._transport.handle_async_request

    async def respond(request):
        body = json.loads(request.content)
        operation, args = body.get("operation"), body.get("args", {})
        before = snapshot(state) if operation in CONTEXT_ACTIONS else None
        if operation == "structure.survival_build" and state.get("build_callback") is not None:
            state["calls"].append(body)
            envelope = {"requestId": body["requestId"], "ok": True, "data": state["build_callback"](args)}
        elif operation == "ae.material_source" and state.get("ae_source_callback") is not None:
            state["calls"].append(body)
            envelope = {"requestId": body["requestId"], "ok": True, "data": state["ae_source_callback"](args)}
        elif operation == "dev.teleport" and args.get("requireFlying") is False:
            state["calls"].append(body)
            assert state["survival"] and not state["flying"] and args["dimension"] == 0 and args["expectedWorld"] == "new-world"
            state["position"] = {axis: args[axis] for axis in ("x", "y", "z")}
            envelope = {"requestId": body["requestId"], "ok": True, "data": {"status": "submitted"}}
        elif operation == "baubles.inspect":
            state["calls"].append(body)
            state["baubles_reads"] += 1
            data = {"supported": True, "readOnly": True, "source": "integrated_server" if args["source"] == "server" else "client",
                "sessionId": "session", "worldFolder": "new-world", "playerId": "actual-player", "actorEntityId": 7,
                "dimension": 0, "worldTick": state["context_tick"], "inventoryClass": "Example.PublicInventory",
                "totalSlots": 7, "offset": 0, "limit": args["limit"], "slots": [], "complete": True,
                "partial": False, "hasMore": False, "cursorItem": copy.deepcopy(state["cursor"]), "nonAtomic": True,
                "creative": not state["survival"], "allowFlying": True, "flying": state["flying"], "playerInventory": full_inventory(state)}
            fault = state["baubles_fault"]
            if fault == "unsupported": data["supported"] = False
            elif fault == "actor": data["playerId"] = "other-player"
            elif fault == "dimension": data["dimension"] = 1
            elif fault == "creative": data["creative"] = not data["creative"]
            elif fault == "flying": data["flying"] = not data["flying"]
            elif fault == "cursor": data["cursorItem"] = stack(CONTROLLER_ITEM)
            elif fault == "partial": data["playerInventory"]["complete"] = False
            elif fault == "truncated": data["playerInventory"]["truncated"] = True
            elif fault == "missing_slot": data["playerInventory"]["slots"].pop(); data["playerInventory"]["totalSlots"] = 40
            elif fault == "duplicate_slot": data["playerInventory"]["slots"][-1]["index"] = 0
            elif fault == "missing_nbt": data["playerInventory"]["slots"][0]["item"].pop("nbtIdentity")
            elif fault == "empty_nbt": data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = ""
            elif fault == "bool_count": data["playerInventory"]["slots"][0]["item"]["count"] = True
            elif fault == "untagged_nbt": data["playerInventory"]["slots"][1]["item"]["nbtIdentity"] = "{hidden:1}"
            elif fault == "server_nbt" and args["source"] == "server": data["playerInventory"]["slots"][0]["item"]["nbtIdentity"] = "{changed:1}"
            if state["baubles_hook"]:
                state["baubles_hook"](data, args, state)
            envelope = {"requestId": body["requestId"], "ok": True, "data": data}
        else:
            response = await original(request)
            envelope = response.json()
            data = envelope.get("data", {})
            if operation == "bridge.health":
                data["contextRpc"] = {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}
                data["capabilities"] = copy.deepcopy(state["caps"])
            elif operation == "inventory.inspect":
                data["playerId"] = "actual-player"
            elif operation == "client.snapshot":
                envelope["data"] = data = snapshot(state)
            elif operation == "world.structure":
                material = state["items"][0]
                data["runtimeObservation"]["trigger"] = {key: material[key] for key in ("registryName", "metadata", "count")}
                data["runtimeObservation"]["trigger"]["channelData"] = state.get("capture_nbt", state["nbt"].get(0, ""))
            elif operation == "player.interact" and not args.get("sneaking") and state["after_hint"]:
                state["after_hint"](state)
        if "includeContext" in body and state["baubles_fault"] != "missing_context":
            envelope["context"] = {"version": 1, "operation": operation,
                "phase": "before" if operation in CONTEXT_ACTIONS else "after", "snapshot": before if before is not None else snapshot(state)}
        return httpx.Response(200, json=envelope)

    monkeypatch.setattr(client.http, "_transport", httpx.MockTransport(respond))
    return client, state


def task(client, state, *, survival=True):
    state["survival"], state["flying"] = survival, not survival
    instance = _ConstructionTask(client, declaration(actualSurvival=survival, timeout_ms=15000), run_workflow, started=time.monotonic())
    instance.native_approach_available = lambda intent: True
    async def approach(*args, **kwargs):
        instance.approach_selected = state["selected"]
        return CONTROLLER, 2, []
    instance.approach = approach
    return instance


@pytest.mark.parametrize("survival", [True, False])
@pytest.mark.parametrize("registry", ["structurelib:constructableTrigger", "example:any_context"])
async def test_tagged_context_preflight_binds_full_nbt_without_machine_or_item_whitelist(tagged_bridge, survival, registry):
    client, state = tagged_bridge
    state["items"][0]["registryName"] = registry
    instance = task(client, state, survival=survival)
    await instance.preflight()
    context = instance.materials[0]["contextIdentity"]
    assert context == {"registryName": registry, "metadata": 0, "hasTag": True, "nbtIdentity": CHANNEL}
    assert state["baubles_reads"] == 2 and writes(state) == []
    assert instance.tagged_context_inventory[40]["item"]["nbtIdentity"] == '{equipment:{upgrade:1}}'
    public_reads = [call for call in state["calls"] if call["operation"] == "inventory.inspect"]
    assert public_reads and all(call["args"] == {} for call in public_reads)


@pytest.mark.parametrize("fault", ["unsupported", "actor", "dimension", "creative", "flying", "cursor", "partial", "truncated", "missing_slot",
    "duplicate_slot", "missing_nbt", "empty_nbt", "bool_count", "untagged_nbt", "server_nbt", "missing_context"])
async def test_unknown_or_mismatched_full_nbt_refuses_before_dispatch(tagged_bridge, fault):
    client, state = tagged_bridge
    state["baubles_fault"] = fault
    with pytest.raises(BridgeError):
        await task(client, state).preflight()
    assert writes(state) == [] and state["hint_count"] == 0


@pytest.mark.parametrize("kind", ["operation", "context"])
async def test_full_nbt_capability_absence_refuses_without_speculative_read(tagged_bridge, kind):
    client, state = tagged_bridge
    if kind == "operation": state["caps"].remove("baubles.inspect")
    original = client.call
    async def call(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "bridge.health" and kind == "context": result["contextRpc"]["reads"].remove("baubles.inspect")
        return result
    client.call = call
    with pytest.raises(BridgeError) as error:
        await task(client, state).preflight()
    assert error.value.details["reason"] == "tagged_context_full_nbt_unavailable"
    assert state["baubles_reads"] == 0 and writes(state) == []


@pytest.mark.parametrize("source", [1, 2, 6])
async def test_tagged_consumed_sources_still_refuse(tagged_bridge, source):
    client, state = tagged_bridge
    state["items"][source]["hasTag"] = True
    state["nbt"][source] = "{resource:1}"
    with pytest.raises(BridgeError):
        await task(client, state).preflight()
    assert writes(state) == []


@pytest.mark.parametrize("survival", [True, False])
async def test_tagged_hint_uses_live_full_inventory_guards_and_conserves_all_context(tagged_bridge, survival):
    client, state = tagged_bridge
    instance = task(client, state, survival=survival)
    await instance.preflight()
    await instance.blueprint_use(build=False)
    positions, _ = await instance.collect_hints()
    assert positions and instance.capture["trigger"]["channelData"] == CHANNEL
    assert state["hint_count"] == 1
    assert state["baubles_reads"] == 8  # initial pair, prepared pair, workflow pair, AFTER pair
    assert [call["operation"] for call in writes(state)] == ["player.hotbar", "player.interact"]
    assert instance.materials[0]["count"] == 16


@pytest.mark.parametrize("index", [0, 40])
async def test_nbt_drift_between_prepared_and_live_guard_stops_hint_before_dispatch(tagged_bridge, index):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    def drift(data, args, current):
        if current["baubles_reads"] == 5:
            data["playerInventory"]["slots"][index]["item"]["nbtIdentity"] = "{changed:1}"
    state["baubles_hook"] = drift
    with pytest.raises(BridgeError) as error:
        await instance.blueprint_use(build=False)
    assert error.value.code == "ASSERTION_FAILED" and writes(state) == [] and state["hint_count"] == 0


@pytest.mark.parametrize("field,value", [("playerId", "other-player"), ("source", "client"), ("dimension", 1),
    ("cursorItem", {"registryName": "example:foreign", "metadata": 0, "count": 1, "hasTag": False}), ("supported", False)])
async def test_live_server_outer_contract_drift_refuses_hint_with_zero_dispatch(tagged_bridge, field, value):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    def drift(data, args, current):
        if current["baubles_reads"] == 6:
            data[field] = copy.deepcopy(value)
    state["baubles_hook"] = drift
    with pytest.raises(BridgeError):
        await instance.blueprint_use(build=False)
    assert writes(state) == [] and state["hint_count"] == 0


async def test_localized_names_are_not_cross_domain_item_identity(tagged_bridge):
    client, state = tagged_bridge
    def localized(data, args, current):
        if args["source"] == "server":
            data["playerInventory"]["name"] = "Actual server inventory"
            data["playerInventory"]["slots"][0]["item"]["displayName"] = "Server localized blueprint"
    state["baubles_hook"] = localized
    instance = task(client, state)
    await instance.preflight()
    await instance.blueprint_use(build=False)
    assert state["hint_count"] == 1 and instance.materials[0]["contextIdentity"]["nbtIdentity"] == CHANNEL


@pytest.mark.parametrize("index", [0, 40])
async def test_hint_after_nbt_drift_is_refused_once_without_replay(tagged_bridge, index):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    state["after_hint"] = lambda actual: actual["nbt"].update({index: "{changed:1}"})
    with pytest.raises(BridgeError):
        await instance.blueprint_use(build=False)
    assert state["hint_count"] == 1 and len([call for call in writes(state) if call["operation"] == "player.interact"]) == 1


@pytest.mark.parametrize("nbt", ["", "{channels:{glass:4}}", "{channels:{glass:5}}"])
async def test_capture_cannot_substitute_same_registry_metadata_with_different_full_channel_nbt(tagged_bridge, nbt):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    state["capture_nbt"] = nbt
    with pytest.raises(BridgeError) as error:
        await instance.collect_hints()
    assert error.value.details["reason"] == "tagged_context_capture_changed" and writes(state) == []


def authoritative(inventory):
    return {**copy.deepcopy(inventory), "source": "integrated_server", "inventorySource": "actual_player_inventory", "cursorItem": None}


@pytest.mark.parametrize("changed", [False, True])
async def test_ae_hash_key_context_after_known_move_and_native_deduction(tagged_bridge, changed):
    client, state = tagged_bridge
    state["nbt"][40] = '{list:{#9:{id:7639s,Cnt:1L},#0:{id:7638s,Cnt:1L}}}'
    instance = task(client, state, survival=True)
    await instance.preflight()
    instance.account_tagged_context_move(1, 9)
    state["items"][9], state["items"][1] = state["items"][1], None
    before = authoritative(full_inventory(state))
    before["slots"][40]["item"]["nbtIdentity"] = '{list:{#0:{Cnt:1L,id:7638s},#9:{Cnt:1L,id:7639s}}}'
    after = copy.deepcopy(before)
    after["slots"][9]["item"] = None
    if changed:
        before["slots"][40]["item"]["nbtIdentity"] = before["slots"][40]["item"]["nbtIdentity"].replace("7639s", "7637s")
        with pytest.raises(BridgeError, match=r"differing slots: \[40\]"):
            instance.accept_tagged_context_native_inventory(before, after)
    else:
        instance.accept_tagged_context_native_inventory(before, after)
        assert instance.tagged_context_inventory[9]["item"] is None
        assert instance.tagged_context_inventory[40]["item"]["nbtIdentity"] == after["slots"][40]["item"]["nbtIdentity"]
    assert writes(state) == []


@pytest.mark.parametrize("fault", [None, "context_count", "context_nbt", "unrelated_nbt", "before_nbt", "committed", "deducted", "missing_nbt"])
async def test_native_shell_accounting_preserves_full_context_while_accepting_only_exact_finite_consumption(tagged_bridge, fault):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    before = authoritative(full_inventory(state))
    after = copy.deepcopy(before)
    after["slots"][2]["item"] = None
    item = {**before["slots"][2]["item"], "count": 1}
    receipt = {"materialAccountingComplete": True, "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after,
        "batches": [{"nativeReturn": -1, "requestedBudget": 8, "configuredCooldownMillis": 200, "accountingComplete": True,
            "committedItems": [{"item": item.copy(), "amount": 1}], "playerDeductions": [{"item": item.copy(), "amount": 1}]}]}
    if fault == "context_count":
        after["slots"][0]["item"]["count"] -= 1
        consumed_context = {**before["slots"][0]["item"], "count": 1}
        for group in ("committedItems", "playerDeductions"):
            receipt["batches"][0][group].append({"item": consumed_context.copy(), "amount": 1})
    elif fault in ("context_nbt", "unrelated_nbt", "missing_nbt"):
        target = after["slots"][40 if fault == "unrelated_nbt" else 0]["item"]
        if fault == "missing_nbt": target.pop("nbtIdentity")
        else: target["nbtIdentity"] = "{changed:1}"
    elif fault == "before_nbt":
        before["slots"][0]["item"]["nbtIdentity"] = after["slots"][0]["item"]["nbtIdentity"] = "{changed:1}"
    elif fault in ("committed", "deducted"):
        receipt["batches"][0]["committedItems" if fault == "committed" else "playerDeductions"][0]["amount"] = 2
    if fault is None:
        instance.accept_survival_build_materials(receipt, {"maxBatches": 32}, 0)
        assert instance.materials[2]["count"] == 0 and instance.tagged_context_inventory[2]["item"] is None
        assert instance.tagged_context_inventory[0]["item"]["nbtIdentity"] == CHANNEL
        assert instance.survival_material_ledger[-1]["deductions"][0]["count"] == 1
    else:
        with pytest.raises(BridgeError):
            instance.accept_survival_build_materials(receipt, {"maxBatches": 32}, 0)
    assert writes(state) == []


@pytest.mark.parametrize("fault", [None, "nbt", "tag", "registry", "count", "actor"])
async def test_native_build_context_requires_the_original_tagged_blueprint_identity(tagged_bridge, fault):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    blueprint = full_inventory(state)["slots"][0]["item"]
    value = {"operation": "structure.survival_build", "actualSurvival": True, "sessionId": "session", "expectedWorld": "new-world",
        "dimension": 0, "controller": {**CONTROLLER, "dimension": 0, "registryName": CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": CONTROLLER_ITEM["metadata"]}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0, "creative": False,
            "actualSurvival": True, "flying": False, "selectedHotbarSlot": 0}, "blueprint": blueprint}
    if fault == "nbt": blueprint["nbtIdentity"] = '{channels:{glass:4}}'
    elif fault == "tag": blueprint["hasTag"] = False
    elif fault == "registry": blueprint["registryName"] = "example:other_context"
    elif fault == "count": blueprint["count"] -= 1
    elif fault == "actor": value["actor"]["playerId"] = "other-player"
    if fault is None:
        instance.validate_survival_build_context(value, 0)
    else:
        with pytest.raises(BridgeError): instance.validate_survival_build_context(value, 0)
    assert writes(state) == []


@pytest.mark.parametrize("index", [0, 40])
async def test_final_inventory_requires_fresh_full_nbt_for_every_retained_context(tagged_bridge, index):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    await instance.verify_retained_inventory()
    state["nbt"][index] = "{changed:1}"
    with pytest.raises(BridgeError):
        await instance.verify_retained_inventory()
    assert writes(state) == []


async def test_known_whole_stack_move_and_native_deduction_keep_other_tagged_context_bound(tagged_bridge):
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    instance.account_tagged_context_move(1, 9)
    state["items"][9], state["items"][1] = state["items"][1], None
    assert await instance.read_tagged_context_inventory() == _context_inventory_rows(full_inventory(state))
    instance.account_tagged_context_move(9, 1)
    state["items"][1], state["items"][9] = state["items"][9], None
    await instance.read_tagged_context_inventory()
    assert instance.tagged_context_inventory[40]["item"]["nbtIdentity"] == '{equipment:{upgrade:1}}'


@pytest.mark.parametrize("metadata", [23, 32767, -1])
async def test_untagged_blueprint_still_protects_arbitrary_tagged_inventory_context(tagged_bridge, metadata):
    client, state = tagged_bridge
    state["items"][0]["hasTag"] = False
    state["nbt"].pop(0)
    state["items"][40].update(registryName="Example:Tagged_Context", metadata=metadata)
    instance = task(client, state)
    await instance.preflight()
    assert "contextIdentity" not in instance.materials[0] and instance.tagged_context_items[40]["metadata"] == metadata
    await instance.blueprint_use(build=False)
    await instance.collect_hints()
    await instance.verify_retained_inventory()
    state["nbt"][40] = "{changed:1}"
    with pytest.raises(BridgeError): await instance.verify_retained_inventory()
    assert state["hint_count"] == 1


@pytest.mark.parametrize("remaining", [0, 1])
async def test_accounted_native_bag_cannot_consume_unrelated_tagged_retained_context(tagged_bridge, remaining):
    client, state = tagged_bridge
    state["items"][40]["count"] = 2
    instance = task(client, state)
    await instance.preflight()
    before = authoritative(full_inventory(state)); after = copy.deepcopy(before)
    after["slots"][40]["item"] = None if remaining == 0 else {**after["slots"][40]["item"], "count": remaining}
    item = {**before["slots"][40]["item"], "count": 1}
    receipt = {"materialAccountingComplete": True, "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after,
        "batches": [{"nativeReturn": -1, "requestedBudget": 8, "configuredCooldownMillis": 200, "accountingComplete": True,
            "committedItems": [{"item": item.copy(), "amount": 2 - remaining}],
            "playerDeductions": [{"item": item.copy(), "amount": 2 - remaining}]}]}
    with pytest.raises(BridgeError) as error:
        instance.accept_survival_build_materials(receipt, {"maxBatches": 32}, 0)
    assert error.value.details["reason"] == "tagged_context_changed"
    assert instance.tagged_context_items[40]["count"] == 2


async def test_accessory_partial_is_independent_of_complete_full_player_inventory(tagged_bridge):
    client, state = tagged_bridge
    def accessory_page(data, args, current):
        data.update(totalSlots=64, complete=False, partial=True, hasMore=True, nextOffset=1)
    state["baubles_hook"] = accessory_page
    instance = task(client, state)
    await instance.preflight()
    await instance.blueprint_use(build=False)
    assert state["hint_count"] == 1 and instance.tagged_context_items[40]["nbtIdentity"] == '{equipment:{upgrade:1}}'


@pytest.mark.parametrize("fault", ["during_full_read", "server_unsynchronized", "server_selected", "missing_selected"])
async def test_noop_selection_requires_fresh_dual_selection_after_full_reads(tagged_bridge, fault):
    client, state = tagged_bridge
    state["selected"] = 0
    instance = task(client, state)
    await instance.preflight()
    if fault == "during_full_read":
        def drift(data, args, current):
            if current["baubles_reads"] == 6: current["selected"] = 2
        state["baubles_hook"] = drift
    else:
        original = client.call
        async def call(operation, args, **options):
            result = await original(operation, args, **options)
            if operation == "client.snapshot":
                if fault == "server_unsynchronized": result["serverControl"]["selectionSynchronized"] = False
                elif fault == "server_selected": result["serverControl"]["selectedHotbarSlot"] = 2
                elif fault == "missing_selected": result["serverControl"].pop("selectedHotbarSlot")
            return result
        client.call = call
    with pytest.raises(BridgeError): await instance.blueprint_use(build=False)
    assert state["hint_count"] == 0 and writes(state) == []


@pytest.mark.parametrize("fault", [None, "never_sync", "wrong_actor", "wrong_dimension", "client_flight"])
async def test_creative_blueprint_waits_for_one_selection_without_replay(tagged_bridge, fault):
    client, state = tagged_bridge
    state["selected"] = 8
    instance = task(client, state, survival=False)
    await instance.preflight()
    original = client.call
    selected, observations = False, []
    async def call(operation, args, **options):
        nonlocal selected
        result = await original(operation, args, **options)
        if operation == "player.hotbar":
            selected = True
        if operation == "client.snapshot" and selected:
            observations.append(copy.deepcopy(result))
            if len(observations) <= 2 or fault == "never_sync":
                result["serverControl"].update(selectedHotbarSlot=8, selectionSynchronized=False)
            elif fault == "wrong_actor": result["serverControl"]["playerId"] = "other-player"
            elif fault == "wrong_dimension": result["serverControl"]["dimension"] = 1
            elif fault == "client_flight": result["player"]["flying"] = False
        if operation == "player.interact":
            assert len(observations) >= 3
        return result
    client.call = call
    if fault:
        with pytest.raises(BridgeError):
            await instance.blueprint_use(build=False)
        assert state["hint_count"] == 0
    else:
        await instance.blueprint_use(build=False)
        assert state["hint_count"] == 1
    assert sum(call["operation"] == "player.hotbar" for call in writes(state)) == 1
    assert sum(call["operation"] == "player.interact" for call in writes(state)) == (0 if fault else 1)
    assert 3 <= len(observations) <= 22


async def test_creative_noop_selection_keeps_immediate_guard_and_phase_budget(tagged_bridge):
    client, state = tagged_bridge
    state["selected"] = 0
    instance = task(client, state, survival=False)
    await instance.preflight()
    await instance.blueprint_use(build=False)
    steps = instance.tagged_context_selection_steps(0)
    assert len(steps) == 3 and steps[0]["kind"] == "read"
    assert not any(call["operation"] == "player.hotbar" for call in writes(state))
    assert state["hint_count"] == 1


@pytest.mark.parametrize("move,screen_open", [(False, False), (True, False), (False, True)])
async def test_real_survival_approach_and_selection_remain_within_bounded_phases(tagged_bridge, move, screen_open):
    from minecraft_codex_bridge.bridge import _ContextData
    from test_native_approach_tasks import composite_receipt
    client, state = tagged_bridge
    if not move: state["position"] = {"x": CONTROLLER["x"] + .5, "y": 2, "z": CONTROLLER["z"] - .5}
    instance = task(client, state)
    await instance.preflight()
    if screen_open: state["gui"] = "machine"
    state["blocks"][key(CONTROLLER)] = {"registryName": CONTROLLER_ITEM["registryName"], "metadata": 0,
        "itemMetadata": CONTROLLER_ITEM["metadata"]}
    async def resolved(target, **options):
        args = {**target, "dimension": 0, "expectedWorld": "new-world", "pattern": "surround", **options,
            "allowedSides": options["allowed_sides"]}
        receipt = composite_receipt(state, args)
        receipt["serverFlight"]["creative"] = False
        return _ContextData(dict(receipt), {"version": 1, "operation": "player.plan_approach", "phase": "after", "snapshot": snapshot(state)})
    instance.resolved_native_approach = resolved
    instance.approach = _ConstructionTask.approach.__get__(instance)
    original_workflow = instance.workflow
    sizes = []
    async def workflow(phase, steps, **options):
        sizes.append((phase, len(instance.survival_server_barriers(steps))))
        return await original_workflow(phase, steps, **options)
    instance.workflow = workflow
    await instance.blueprint_use(build=False)
    assert state["hint_count"] == 1 and all(size <= 32 for _, size in sizes)
    assert [phase for phase, _ in sizes] == ["tagged_blueprint_hint_approach", "blueprint_hints"]
    assert any(call["operation"] == "player.hotbar" for call in writes(state))
    assert any(call["operation"] == "dev.teleport" for call in writes(state)) is move


@pytest.mark.parametrize("status", ["native_done", "no_progress", "outcome_unknown"])
async def test_tagged_native_build_retains_receipt_and_never_replays(tagged_bridge, status):
    from test_actual_survival_workflows import install_shell_geometry
    client, state = tagged_bridge
    instance = task(client, state)
    await instance.preflight()
    before = authoritative(full_inventory(state)); after = copy.deepcopy(before)
    item = before["slots"][2]["item"]
    if status == "native_done": after["slots"][2]["item"] = None
    receipt = {"operation": "structure.survival_build", "status": status, "actualSurvival": True,
        "sessionId": "session", "expectedWorld": "new-world", "dimension": 0,
        "controller": {**CONTROLLER, "dimension": 0, "registryName": CONTROLLER_ITEM["registryName"],
            "gregtech": {"metaTileId": CONTROLLER_ITEM["metadata"]}},
        "actor": {"source": "integrated_server", "playerId": "actual-player", "dimension": 0,
            "creative": False, "actualSurvival": True, "flying": False, "selectedHotbarSlot": 0},
        "blueprint": copy.deepcopy(before["slots"][0]["item"]), "nativeDone": status == "native_done",
        "materialAccountingComplete": status != "outcome_unknown", "formationVerified": False, "partial": status == "outcome_unknown",
        "outcomeUnknown": status == "outcome_unknown", "generationPerformed": False, "inventoryCleared": False,
        "playerInventoryBefore": before, "playerInventoryAfter": after,
        "batches": [{"nativeReturn": -1 if status == "native_done" else 0, "requestedBudget": 8,
            "configuredCooldownMillis": 200, "accountingComplete": status != "outcome_unknown",
            "committedItems": [{"item": item.copy(), "amount": 1}] if status == "native_done" else [],
            "playerDeductions": [{"item": item.copy(), "amount": 1}] if status == "native_done" else []}]}
    health = copy.deepcopy(instance.approach_health)
    install_shell_geometry(instance, receipt)
    instance.approach_health = {**health, **instance.approach_health}
    # The existing accounting fixture supplies independent outside body/ray
    # observations. Real approach step budgets are exercised separately above.
    original_planned = instance.planned_approach
    async def planned(target, **options):
        attachment, side, _ = await original_planned(target, **options)
        return attachment, side, [{"id": "approach_alive", "kind": "read", "tool": "mcb_snapshot", "args": {}}]
    instance.planned_approach = planned
    def callback(args):
        assert args["blueprintHotbarSlot"] == 0 and args["maxBatches"] == 32
        if status == "native_done": state["items"][2] = None
        return copy.deepcopy(receipt)
    state["build_callback"] = callback
    if status == "native_done":
        await instance.blueprint_use(build=True)
        assert instance.materials[2]["count"] == 0 and instance.tagged_context_items[40]["count"] == 1
    else:
        with pytest.raises(BridgeError): await instance.blueprint_use(build=True)
    assert instance.survival_build_receipt["status"] == status
    assert len([call for call in writes(state) if call["operation"] == "structure.survival_build"]) == 1
    assert not instance.formation_verified


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
@pytest.mark.parametrize("fault", [None, "live_nbt", "receipt_nbt", "after_nbt"])
async def test_tagged_ae_shell_binds_live_context_exact_source_receipt_and_final_inventory(tagged_bridge, mode, fault):
    from minecraft_codex_bridge.construction_tasks import _survival_inventory_entries
    from test_actual_survival_workflows import install_shell_geometry
    from test_high_level_ae_survival import build_receipt, declaration as ae_declaration, source_observation
    client, state = tagged_bridge
    state["caps"].append("ae.material_source")
    instance = task(client, state)
    instance.args["survivalBuild"] = ae_declaration(mode)["survivalBuild"]
    instance.survival_ae_source = instance.args["survivalBuild"]["materialSource"]
    def source_callback(args):
        assert args["expectedSessionId"] == "session" and args["energyProbeItems"] == 65_536
        return copy.deepcopy(source_observation(instance.args))
    state["ae_source_callback"] = source_callback
    await instance.preflight()
    receipt, binding = build_receipt(instance.args)
    before = {**authoritative(full_inventory(state)), "selectedHotbarSlot": 0}
    receipt["playerInventoryBefore"] = copy.deepcopy(before)
    receipt["playerInventoryAfter"] = copy.deepcopy(before)
    receipt["blueprint"] = copy.deepcopy(before["slots"][0]["item"])
    # Placement ledger seeding is independent of this shell integration; its
    # known current all41 baseline is the same complete full-NBT observation.
    instance.survival_ae_player_inventory = _survival_inventory_entries(before)
    assert instance.survival_ae_binding == binding
    health = copy.deepcopy(instance.approach_health)
    install_shell_geometry(instance, receipt)
    instance.approach_health = {**health, **instance.approach_health}
    original_planned = instance.planned_approach
    async def planned(target, **options):
        attachment, side, _ = await original_planned(target, **options)
        return attachment, side, [{"id": "approach_alive", "kind": "read", "tool": "mcb_snapshot", "args": {}}]
    instance.planned_approach = planned
    original_workflow = instance.workflow
    sizes = []
    async def workflow(phase, steps, **options):
        sizes.append((phase, len(instance.survival_server_barriers(steps))))
        return await original_workflow(phase, steps, **options)
    instance.workflow = workflow
    if fault == "live_nbt":
        def drift(data, args, current):
            if current["baubles_reads"] == 5:
                data["playerInventory"]["slots"][40]["item"]["nbtIdentity"] = "{changed:1}"
        state["baubles_hook"] = drift
    elif fault == "receipt_nbt":
        receipt["playerInventoryAfter"]["slots"][40]["item"]["nbtIdentity"] = "{changed:1}"
    def callback(args):
        assert args["materialSource"] == instance.survival_ae_source
        assert args["blueprintHotbarSlot"] == 0 and args["elementBudget"] == 65_536
        if fault == "after_nbt": state["nbt"][40] = "{changed:1}"
        return copy.deepcopy(receipt)
    state["build_callback"] = callback
    if fault is None:
        await instance.blueprint_use(build=True)
        ledger = instance.survival_material_ledger[-1]
        assert ledger["sourceType"] == "ae_cells" and ledger["playerInventoryUnchanged"] is True
        assert ledger["finiteConservationVerified"] is (mode == "finite")
        assert ledger["creativeSupplyUnchanged"] is (mode == "creative_infinite")
        await instance.verify_retained_inventory()
        assert state["baubles_reads"] == 10 and instance.tagged_context_items[40]["count"] == 1
    else:
        with pytest.raises(BridgeError): await instance.blueprint_use(build=True)
    assert all(size <= 32 for _, size in sizes)
    assert [phase for phase, _ in sizes][:2] == ["tagged_blueprint_build_approach", "survival_ae_source_before_shell"]
    assert len([call for call in state["calls"] if call["operation"] == "ae.material_source"]) == 2
    assert len([call for call in writes(state) if call["operation"] == "structure.survival_build"]) == (0 if fault == "live_nbt" else 1)
    assert not instance.formation_verified
    baseline_calls = len(state["calls"])
    with pytest.raises(BridgeError) as error: await instance.blueprint_use(build=True)
    assert error.value.details["reason"] == "survival_ae_build_already_attempted" and len(state["calls"]) == baseline_calls
