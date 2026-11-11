"""Clear-only native recipe supplies require real synchronized inventory and never replay."""

import json

import httpx
import pytest

import test_recipe_tasks as native

native_bridge = native.native_bridge


def enable_native_supply(client, state, monkeypatch, *, fault=None):
    state["caps"].append("inventory.supply")
    original = client.http._transport
    state["supply_reads"] = 0

    async def respond(request):
        body = json.loads(request.content)
        operation = body.get("operation")
        if operation == "inventory.supply":
            state["calls"].append(body)
            assert body["args"]["expectedSessionId"] == "session"
            assert body["args"]["expectedWorld"] == "test-world"
            assert state["cursor"] is None and all(item is None for item in state["player_items"].values())
            if fault == "transport":
                raise httpx.ReadError("Native supply outcome is unknown", request=request)
            if fault == "refused":
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": "NATIVE_SUPPLY_REFUSED", "message": "Authoritative player conditions changed."}})
            items = body["args"]["items"]
            accepted = 0
            for item in items:
                if fault in ("partial", "unknown") and accepted == 1:
                    break
                identity = {key: item[key] for key in ("registryName", "metadata")}
                index = next(index for index in range(36) if state["player_items"][index] is None)
                field = "material" if identity == native.MATERIAL else "circuit"
                state[field + "_index"] = index
                state[field] = item["count"]
                state["player_items"][index] = native.stack(identity, item["count"])
                state["generated"] += 1
                accepted += 1
            receipt = {"requestedCount": len(items), "commandsAttempted": len(items),
                "commandsAccepted": accepted, "complete": accepted == len(items), "outcomeUnknown": fault == "unknown"}
            if fault == "malformed_attempts":
                receipt["commandsAttempted"] = True
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": receipt})
        response = await original.handle_async_request(request)
        if operation == "inventory.inspect":
            envelope = response.json()
            inventory = envelope["data"]
            if fault != "coverage_before":
                inventory["slots"].extend({"index": index, "item": None} for index in range(36, 41))
                inventory["totalSlots"] = 41
            supplied = any(call.get("operation") == "inventory.supply" for call in state["calls"])
            if supplied:
                state["supply_reads"] += 1
                if fault == "cursor_after":
                    inventory["cursorItem"] = native.stack(native.MATERIAL, 1)
                elif fault == "coverage_after":
                    inventory["slots"].pop()
                    inventory["totalSlots"] -= 1
                elif fault == "wrong_count":
                    next(slot for slot in inventory["slots"] if slot["item"] is not None)["item"]["count"] += 1
                elif fault == "delayed" and state["supply_reads"] == 1:
                    inventory["slots"] = [{"index": index, "item": None} for index in range(41)]
            return httpx.Response(response.status_code, json=envelope)
        return response

    monkeypatch.setattr(client.http, "_transport", httpx.MockTransport(respond))


def declaration(**changes):
    return native.declaration(generateMaterials=True, inventoryPreparation="clear", **changes)


def grants(state, operation):
    return [call for call in state["calls"] if call.get("operation") == operation
        and (operation != "chat.send" or call["args"]["message"].startswith("/give "))]


@pytest.mark.parametrize("fault", [None, "delayed"])
async def test_clear_recipe_uses_one_batch_and_real_count_consumption_catalyst_stop(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    state["input_circuit"] = 0
    enable_native_supply(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration())
    assert result["status"] == "completed", result
    clear_receipt = result["inventoryPreparation"]["clearReceipt"]
    assert clear_receipt["status"] == "confirmed" and clear_receipt["containerAckConfirmed"] is True
    assert clear_receipt["clearCommandDispatched"] is clear_receipt["clearConfirmed"] is True
    assert clear_receipt["acknowledgementSampleId"] > clear_receipt["baselineSampleId"]
    assert clear_receipt["serverWindowId"] == clear_receipt["clientWindowId"] == 0
    assert clear_receipt["replayAllowed"] is False and clear_receipt["outcomeUnknown"] is False
    batches = grants(state, "inventory.supply")
    assert len(batches) == 1 and len(batches[0]["args"]["items"]) == 2
    assert grants(state, "chat.send") == []
    assert result["materialSupplyMode"] == "native_server_give_commands"
    assert result["generatedMaterials"] is result["materialSupplyConfirmed"] is True
    assert result["materialGrantsDispatched"] == 2
    assert state["input_material"] == 0 and state["input_circuit"] == 1 and state["output"] == 5
    assert not state["allowed"] and not state["core"]
    accepted = next(phase for phase in result["phases"] if phase["phase"] == "verify_native_material_supply")
    assert accepted["completeSlotCoverage"] == 41 and accepted["completeBatchVerified"] is True
    if fault == "delayed":
        assert accepted["attempts"] >= 2


@pytest.mark.parametrize("fault", ["partial", "unknown", "transport", "refused", "cursor_after", "coverage_after", "wrong_count", "malformed_attempts"])
async def test_failed_native_supply_never_replays_falls_back_feeds_or_claims_verified_generation(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    state["input_circuit"] = 0
    enable_native_supply(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, declaration(timeout_ms=1000))
    assert result["status"] == "failed", result
    assert len(grants(state, "inventory.supply")) == 1 and not grants(state, "chat.send")
    assert result["materialSupplyMode"] == "native_server_give_commands"
    assert result["generatedMaterials"] is result["materialSupplyConfirmed"] is False
    assert state["input_material"] == 0 and state["input_circuit"] == 0 and state["output"] == 4
    assert not state["allowed"] and not any(call.get("operation") == "gui.slot_click" for call in state["calls"])
    if fault in ("unknown", "transport"):
        assert result["outcomeUnknown"] is True
    if fault == "transport":
        assert result["materialGrantsDispatchedCountKnown"] is False
    if fault == "malformed_attempts":
        assert result["materialGrantsDispatchedCountKnown"] is False
    if fault == "refused":
        assert result["reason"] == "NATIVE_SUPPLY_REFUSED" and result["materialGrantsDispatched"] == 0


async def test_unknown_complete_inventory_refuses_before_supply_dispatch(native_bridge, monkeypatch):
    client, state = native_bridge
    enable_native_supply(client, state, monkeypatch, fault="coverage_before")
    result = await native.invoke(client, declaration())
    assert result["reason"] == "native_supply_inventory_coverage_unavailable", result
    assert not grants(state, "inventory.supply") and not grants(state, "chat.send")
    assert result["generatedMaterials"] is False


async def test_public_stack_limit_is_checked_before_native_supply(native_bridge, monkeypatch):
    client, state = native_bridge
    state["grant_stack_limit"] = 4
    enable_native_supply(client, state, monkeypatch)
    result = await native.invoke(client, declaration())
    assert result["reason"] == "native_supply_registry_unavailable", result
    assert not grants(state, "inventory.supply") and not grants(state, "chat.send")
    assert result["generatedMaterials"] is False


async def test_boolean_capability_map_selects_one_native_supply(native_bridge, monkeypatch):
    client, state = native_bridge
    enable_native_supply(client, state, monkeypatch)
    state["caps"] = {operation: True for operation in state["caps"]}
    result = await native.invoke(client, declaration())
    assert result["status"] == "completed", result
    assert len(grants(state, "inventory.supply")) == 1 and not grants(state, "chat.send")


@pytest.mark.parametrize("restore", [False, True])
async def test_preserved_or_restored_inventory_keeps_chat_path(native_bridge, monkeypatch, restore):
    client, state = native_bridge
    state["material"] = 0
    enable_native_supply(client, state, monkeypatch)
    arguments = declaration(restoreInventory=restore)
    if not restore:
        arguments["inventoryPreparation"] = "preserve"
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert not grants(state, "inventory.supply") and len(grants(state, "chat.send")) == 1
    assert result["materialSupplyMode"] == "native_chat_give_commands"


async def test_missing_native_capability_keeps_original_clear_chat_supply(native_bridge):
    client, state = native_bridge
    result = await native.invoke(client, declaration())
    assert result["status"] == "completed", result
    assert not grants(state, "inventory.supply") and len(grants(state, "chat.send")) == 1
    assert result["materialSupplyMode"] == "native_chat_give_commands"


async def test_generation_disabled_keeps_existing_sources_without_supply(native_bridge, monkeypatch):
    client, state = native_bridge
    enable_native_supply(client, state, monkeypatch)
    result = await native.invoke(client, native.declaration(generateMaterials=False, inventoryPreparation="clear"))
    assert result["status"] == "completed", result
    assert not grants(state, "inventory.supply") and not grants(state, "chat.send")
    assert result["materialSupplyMode"] == "existing_real_inventory" and result["generatedMaterials"] is False
