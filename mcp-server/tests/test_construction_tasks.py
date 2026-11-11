"""Construction tasks use the real transport and native workflow, with a small world."""

import asyncio
import copy
import json
import math
import time

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.construction_tasks import BLUEPRINT_BUILD_SCHEMA, ROLE_KEYS, _ConstructionTask, _REQUIRED, run_blueprint_build
from minecraft_codex_bridge.workflows import run_workflow


CONTROLLER = {"x": -44, "y": 3, "z": 125}
CONTROLLER_ITEM = {"registryName": "gregtech:gt.blockmachines", "metadata": 10001}
CASING = {"registryName": "gregtech:gt.blockcasings2", "metadata": 1}
BLUEPRINT = {"registryName": "structurelib:constructableTrigger", "metadata": 0}
SUPPORT = {"registryName": "minecraft:dirt", "metadata": 0}
PORT_ITEMS = {"item_input": {"registryName": "gregtech:gt.blockmachines", "metadata": 77},
    "item_output": {"registryName": "gregtech:gt.blockmachines", "metadata": 81},
    "energy": {"registryName": "gregtech:gt.blockmachines", "metadata": 11304},
    "maintenance": {"registryName": "gregtech:gt.blockmachines", "metadata": 15497}}
PORT_SLOTS = {"item_input": 2, "item_output": 8, "energy": 3, "maintenance": 5}
BACKGROUND = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}


def stack(item, count=1):
    return {**item, "count": count, "hasTag": False}


def key(point):
    return tuple(point[axis] for axis in ("x", "y", "z"))


def declaration(**changes):
    ports = [{"role": role, "hotbarSlot": slot, **({"ordinaryOutputItem": PORT_ITEMS[role].copy()} if role == "item_output" else {})}
        for role, slot in PORT_SLOTS.items()]
    return {"expectedSessionId": "session", "expectedWorld": "new-world", "dimension": 0,
        "controller": CONTROLLER.copy(), "controllerItem": CONTROLLER_ITEM.copy(),
        "blueprintHotbarSlot": 0, "supportItem": SUPPORT.copy(), "supportHotbarSlot": 6,
        "requestedPorts": ports, "timeout_ms": 5000, "inventoryPreparation": "preserve", "includeDetails": True, **changes}


def writes(state):
    return [call for call in state["calls"] if call["operation"] not in READ_OPERATIONS | {"__cancel__"}]


def fill_hotbar_with_storage_controller(state):
    state["items"][9], state["items"][1] = state["items"][1], None
    for index in (1, 4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["controller_original_index"] = 9
    return copy.deepcopy(state["items"])


@pytest.fixture
async def native_construction():
    items = {index: None for index in range(41)}
    items[0], items[1], items[6] = stack(BLUEPRINT, 16), stack(CONTROLLER_ITEM), stack(SUPPORT, 64)
    for role, slot in PORT_SLOTS.items():
        items[slot] = stack(PORT_ITEMS[role])
    state = {"calls": [], "items": items, "cursor": None, "selected": 7, "gui": None, "window": 0, "tab": False,
        "position": {"x": -43.5, "y": 3, "z": 119.5}, "flying": True, "blocks": {}, "formed": False,
        "built": False, "hint_count": 0, "construct_count": 0, "preserve_preinstalled": True,
        "rechecks": 0, "mapping": True, "controller": CONTROLLER.copy(),
        "capture_id": "immutable-capture", "capture_partial": False, "context": True, "runtime_page_size": 128,
        "hint_delay": 0, "construction_delay": 0, "restoration_delay": 0, "bad_role": None, "wrong_casing": False,
        "caps": [*_REQUIRED, "player.place_block", "chat.send"], "formed_allowed": True, "foreign": None, "placements": [], "digs": [], "inventory_activations": []}
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "construction-test-token"})

    def inventory():
        return {"slots": [{"index": index, "item": copy.deepcopy(state["items"].get(index))} for index in range(41)],
            "cursorItem": copy.deepcopy(state["cursor"]), "selectedHotbarSlot": state["selected"], "totalSlots": 41, "truncated": False}

    def block(point):
        identity = state["blocks"].get(key(point))
        if identity is None:
            identity = {"registryName": "minecraft:bedrock" if point["y"] == 0 else "minecraft:grass" if point["y"] == 1 else "minecraft:air", "metadata": 0}
        result = {**{axis: point[axis] for axis in ("x", "y", "z")}, "source": "integrated_server", "dimension": 0, **identity}
        if result["registryName"] == "gregtech:gt.blockmachines":
            result.update(tileClass="Untrusted.World.Tile", metadata=0, gregtech={"metaTileId": identity["itemMetadata"]})
            role = next((role for role, item in PORT_ITEMS.items() if item["metadata"] == identity["itemMetadata"]), None)
            if role is not None:
                if role == "energy" and state.get("energy_support"):
                    result["gregtech"].update(metaClass=state["energy_meta_class"], inputVoltage=state["energy_voltage"])
                total = 4 if role in ("item_input", "item_output") else 0
                slots = [{"index": index, "item": None} for index in range(total)]
                if role == state.get("nonempty_preinstalled"):
                    slots[0]["item"] = stack({"registryName": "minecraft:stone", "metadata": 0})
                result["inventory"] = {"slots": slots, "totalSlots": total, "truncated": False}
                result["fluids"] = {"tanks": [], "totalTanks": 0, "truncated": False}
        return result

    def captured_positions():
        controller = state["controller"]
        positions = []
        for dy in (-1, 0, 1):
            for dz in (0, 1, 2):
                for dx in (-1, 0, 1):
                    if (dx, dy, dz) == (0, 0, 0):
                        continue
                    shift = state.get("hint_shift", (0, 0, 0))
                    positions.append({"x": controller["x"] + dx + shift[0], "y": controller["y"] + dy + shift[1], "z": controller["z"] + dz + shift[2],
                        "a": dx, "b": dy, "c": dz, "group": 1 if (dx, dy, dz) == (0, 0, 1) else 0})
        return positions

    def hints(args):
        positions = captured_positions()
        start = args.get("runtimeOffset", 0)
        end = min(len(positions), start + state["runtime_page_size"])
        page = positions[start:end]
        descriptions = [value for role, value in ROLE_KEYS.items() if role != state.get("missing_hint_role")]
        groups = [{"id": 0, "descriptionKeys": descriptions, "suggestedCandidates": [stack(CASING)],
            "candidateScope": "Suggestions at first visited position only", "capturedVisits": 25,
            "elementClass": "Untrusted.Private.Class", "elementKey": None},
            {"id": 1, "descriptionKeys": ["public.required.air"], "suggestedCandidates": [], "capturedVisits": 1, "elementClass": "Untrusted.Other"}]
        low, high = {axis: min(point[axis] for point in positions) for axis in ("x", "y", "z")}, {axis: max(point[axis] for point in positions) for axis in ("x", "y", "z")}
        floor = args["floorBlockY"]
        relative_min = low["y"] - state["controller"]["y"]
        raise_blocks = max(0, floor + 1 - low["y"])
        return {"runtimeObservation": {"available": True, "captureId": state["capture_id"], "capturedPositions": len(positions),
            "runtimeOffset": start, "nextRuntimeOffset": end, "hasMore": end < len(positions),
            "capturedVisitsComplete": not state["capture_partial"], "eventCaptureTruncated": state["capture_partial"],
            "contextMatches": state["context"], "modeMatches": state["context"], "facingMatches": True, "controllerIdentityMatches": True,
            "capturedFacing": "NORTH_NORMAL_NONE", "capturedMode": 0, "serverMode": 0 if state["context"] else 1,
            "observedBounds": {"available": True, "complete": True, "partial": False, "completeStructure": False,
                "worldXYZ": {"min": low, "max": high, "size": {"x": 3, "y": 3, "z": 3}},
                "controllerRelativeXYZ": {"min": {"x": -1, "y": relative_min, "z": 0}}},
            "planning": {"available": True, "floorBlockY": floor, "minimumObservedY": low["y"], "controllerRelativeMinY": relative_min,
                "minimumControllerY": floor + 1 - relative_min, "raiseBlocks": raise_blocks, "floorConflict": raise_blocks > 0},
            "positions": page, "groups": [group for group in groups if any(point["group"] == group["id"] for point in page)]}}

    def components():
        entries = []
        for point_key, identity in state["blocks"].items():
            for role, item in PORT_ITEMS.items():
                if identity.get("itemMetadata") == item["metadata"]:
                    if role == state.get("missing_registered_role"):
                        continue
                    entry = {"role": "wrong" if role == state["bad_role"] else role, "valid": True, "dimension": 0,
                        **dict(zip(("x", "y", "z"), point_key))}
                    if role == state.get("wrong_position_role"):
                        entry["x"] += 1
                    if role == "energy" and state.get("energy_support"):
                        entry.update(metaClass=state["energy_meta_class"], tier=state["energy_tier"]
                            + int(bool(state.get("energy_tier_drift") and state["rechecks"])))
                    entries.append(entry)
        return {"entries": entries, "roles": [{"role": role, "count": sum(entry["role"] == role for entry in entries)} for role in ROLE_KEYS],
            "total": len(entries), "hasMore": False, "offset": 0, "nextOffset": len(entries), "unavailableRoles": []}

    async def respond(request):
        body = json.loads(request.content)
        operation = "__cancel__" if request.url.path == "/v1/cancel" else body["operation"]
        args = body.get("args", {})
        state["calls"].append({**body, "operation": operation})
        if operation == "__cancel__":
            data = {"status": "already_running"}
        elif operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "profile": "developer", "capabilities": state["caps"],
                "background": BACKGROUND, "nativeBlockPlacement": True, "interactionSneaking": True, "interactionBlockTargeting": True,
                "interactionViewBinding": True, "interactionAttackViewBinding": True, "guiStructureRecheck": "public native two-state semantics"}
        elif operation == "client.snapshot":
            state["server_samples"] = state.get("server_samples", 0) + 1
            if state.get("position_frames"):
                state["position"] = state["position_frames"].pop(0)
            position = state["position"]
            data = {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": BACKGROUND,
                "serverControl": {"available": True, "source": "integrated_server_tick", "sessionId": "session",
                    "worldName": "new-world", "worldFolder": "new-world", "dimension": 0,
                    "sampleId": state["server_samples"], "ageMillis": 0, "playerId": "fixture-player-uuid",
                    "openContainer": {"isInventoryContainer": state["gui"] is None, "windowId": state["window"]},
                    "clientContainer": {"isInventoryContainer": state["gui"] is None, "windowId": state["window"]}},
                "screenOpen": state["gui"] is not None,
                "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0},
                "player": {"name": "TestPlayer", "health": state.get("health", 20), **position, "feetY": position["y"], "eyeY": position["y"] + 1.62, "flying": state["flying"],
                    "selectedHotbarSlot": state.get("snapshot_selected_override", state["selected"]),
                    "serverFlight": {"available": True, "allowFlying": True, "flying": state["flying"], "dimension": 0}}}
        elif operation == "inventory.inspect":
            data = inventory()
            if state.get("inventory_observation_hook"):
                state["inventory_observation_hook"](data)
            if state.get("partial_native_inventory") and state["gui"] == "creative" and state["tab"]:
                data["truncated"] = True
            if state["rechecks"] and state.get("retained_observation_fault") == "cursor_missing":
                data.pop("cursorItem")
            if state["rechecks"] and state.get("retained_observation_fault") == "partial":
                data["truncated"] = True
        elif operation == "registry.item":
            data = {**args, "displayName": "输出总线（ME）" if state.get("me_item") else "输出总线（LV）",
                "tooltip": ["多方块机器的物品输出端", "容量：4组"], "stackLimit": 64}
        elif operation == "world.inspect_many":
            data = {"source": "integrated_server", "dimension": 0, "results": [{"ok": True, "data": block(point)} for point in args["positions"]]}
        elif operation == "world.inspect":
            data = block(args)
        elif operation == "client.messages":
            messages = [message for message in state.get("messages", []) if message["seq"] > args.get("since", 0)
                and args.get("query", "") in message["text"]]
            data = {"messages": messages[:args.get("limit", 32)], "lastSequence": state.get("message_seq", 0),
                "hasMore": len(messages) > args.get("limit", 32), "historyGap": False, "truncated": False}
        elif operation == "world.structure":
            data = hints(args)
            if state.get("stale_capture") and args.get("runtimeOffset", 0):
                data["runtimeObservation"]["captureId"] = "new-capture"
        elif operation == "world.spatial":
            if args["mode"] == "positions":
                data = {"positions": [{**point, "loaded": True, "occupiable": point["y"] >= 2
                    and block(point)["registryName"] == "minecraft:air" and block({**point, "y": point["y"] + 1})["registryName"] == "minecraft:air",
                    "liquid": False, "hazard": False, "entityCoveragePartial": False} for point in args["positions"]]}
            else:
                destination = args["to"]
                point = {axis: math.floor(destination[axis]) for axis in ("x", "y", "z")}
                side = None
                for axis, negative, positive in (("x", 4, 5), ("y", 0, 1), ("z", 2, 3)):
                    fraction = destination[axis] - point[axis]
                    if abs(fraction - .001) < .0001:
                        side = negative
                    if abs(fraction - .999) < .0001:
                        side = positive
                hit = {"type": "block", "block": point, "side": side}
                if state.get("body_no_rays") or block(point)["registryName"] == "minecraft:air":
                    hit = {"type": "miss"}
                if state.get("null_rays"):
                    hit = None
                if state.get("low_face_requires_feet_two") and point["y"] == 2 and abs(args["from"]["y"] - 1.62 - 2) > .0001:
                    hit = {"type": "miss"}
                data = {"status": "observed", "hit": hit}
                if "selection_after_ray" in state:
                    state["selected"] = state["selection_after_ray"]
        elif operation == "dev.teleport":
            assert args["requireFlying"] is True and state["flying"]
            assert args["y"] >= 2
            destination = {axis: args[axis] for axis in ("x", "y", "z")}
            state["last_teleport_destination"] = destination
            order = state.get("position_sync_order")
            if order is None:
                state["position"] = destination
            else:
                position = copy.deepcopy(state["position"])
                frames = [copy.deepcopy(position)]
                for axis in order:
                    position[axis] = destination[axis]
                    frames.append(copy.deepcopy(position))
                if state.get("position_sync_drift"):
                    frames[-1][state["position_sync_drift"]] += 1
                state["position_frames"] = frames
            data = {"status": "submitted"}
        elif operation == "gui.close":
            state["gui"], state["window"] = None, 0
            data = {"status": "submitted"}
        elif operation == "gui.open_inventory":
            state["gui"], state["window"], state["tab"] = "creative", 0, False
            data = {"status": "submitted"}
        elif operation == "gui.inspect":
            data = {"screenId": "screen:" + str(state["gui"]), "windowId": state["window"]}
            if state["gui"] == "creative":
                data["creativeInventory"] = {"available": True, "inventoryTabWidgetId": "public:inventory:tab", "playerSlotsAvailable": state["tab"]}
        elif operation == "gui.widgets":
            widgets = []
            if state["gui"] == "creative":
                assert state["tab"]
                for index in range(36):
                    widget = {"widgetId": "public:actual:" + str(5000 - index * 13), "kind": "creative_player_slot", "enabled": True,
                        "valid": True, "supportedActions": ["activate"], "item": copy.deepcopy(state["items"][index]),
                        "nativeWrapperIndex": 20000 + index, "x": 17 + index}
                    if state["mapping"]:
                        widget.update(playerInventoryIndex=index, nativeContainerSlotId=700 + index * 17,
                            inventorySource="current_player_inventory", inventoryBinding="public_isHere_unique_actual_player_container_slot")
                    widgets.append(widget)
            elif state["gui"] == "energy" and state.get("energy_support"):
                widgets = copy.deepcopy(state["energy_widgets"])
                for widget in widgets:
                    if widget.get("widgetId") == "energy:tier":
                        widget.update(text=str(state["energy_gui_tier"]), value=str(state["energy_gui_tier"]))
            else:
                widgets = [{"widgetId": "public:semantic:recheck", "kind": "choice", "semantic": "structure_recheck",
                    "enabled": True, "valid": True, "supportedActions": ["recheck_structure"], "selected": 0, "optionCount": 2}]
            data = {"screenId": "screen:" + str(state["gui"]), "widgets": widgets, "hasMore": False, "partial": False,
                "scanTruncated": False, "byteLimited": False}
            if state["gui"] == "energy" and state.get("energy_gui_fault"):
                data[state["energy_gui_fault"]] = True
        elif operation == "gui.widget_action":
            if args["action"] == "set_text" and state.get("energy_support"):
                assert state["gui"] == "energy" and args["widgetId"] == "energy:tier" and args["commit"] is True
                state["energy_requests"].append(copy.deepcopy(args))
                state["energy_gui_tier"] = int(args["text"])
                if state.get("energy_commit_disconnect"):
                    raise httpx.ReadError("native tier commit outcome unknown", request=request)
                if not state.get("energy_stale_server"):
                    state["energy_tier"], state["energy_voltage"] = int(args["text"]), state["energy_expected_voltage"]
            elif args["action"] == "recheck_structure":
                state["rechecks"] += 1
                state["formed"] = state["formed_allowed"] and state["built"] and all(any(identity.get("itemMetadata") == item["metadata"]
                    for identity in state["blocks"].values()) for item in PORT_ITEMS.values())
                if state.get("corrupt_restoration"):
                    target = next(index for index in range(9, 36) if state["items"][index] is not None and state["items"][index]["registryName"] == "minecraft:stone")
                    state["items"][target]["count"] -= 1
                fault = state.get("retained_stack_fault")
                if fault in ("count", "registry", "metadata", "tag", "location", "source", "cursor"):
                    if fault == "count":
                        state["items"][10]["count"] -= 1
                    elif fault == "registry":
                        state["items"][10]["registryName"] = "minecraft:dirt"
                    elif fault == "metadata":
                        state["items"][10]["metadata"] = 0
                    elif fault == "tag":
                        state["items"][10]["hasTag"] = True
                    elif fault == "location":
                        state["items"][11], state["items"][10] = state["items"][10], None
                    elif fault == "source":
                        state["items"][1] = stack({"registryName": "minecraft:stone", "metadata": 1}, 64)
                    else:
                        state["cursor"] = stack({"registryName": "minecraft:stone", "metadata": 0})
            elif args["widgetId"] == "public:inventory:tab":
                state["tab"] = True
            else:
                index = next(index for index in range(36) if args["widgetId"] == "public:actual:" + str(5000 - index * 13))
                state["inventory_activations"].append(index)
                if state["cursor"] is None:
                    state["cursor"], state["items"][index] = state["items"][index], None
                    assert state["cursor"] is not None
                    if state.get("pickup_dispatched") is not None:
                        state["pickup_dispatched"].set()
                        await asyncio.Event().wait()
                    if state.get("disconnect_pickup"):
                        raise httpx.ReadError("uncertain native pickup", request=request)
                    if state.get("disconnect_controller_return") and index == 1 and len(state["inventory_activations"]) == 5:
                        raise httpx.ReadError("uncertain necessary controller return", request=request)
                else:
                    assert state["items"][index] is None
                    state["items"][index], state["cursor"] = state["cursor"], None
                    if index == 10 and state.get("change_controller_after_borrow"):
                        state["items"][9]["count"] += 1
                    if index == state.get("controller_original_index"):
                        await asyncio.sleep(state["restoration_delay"])
            data = {"status": "submitted"}
        elif operation == "player.hotbar":
            state["selected"] = args["slot"]
            data = {"status": "submitted", "slot": state["selected"]}
        elif operation == "player.place_block":
            if state.get("native_face_refusal"):
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": state["native_face_refusal"], "message": "The actual client native face is unavailable."}})
            assert args["expectedWorld"] == "new-world" and args["dimension"] == 0 and args["replace"] is False
            target_before = {**block(args), "source": "client", "air": True, "replaceable": True}
            assert target_before["registryName"] == "minecraft:air"
            vectors = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}
            candidates = []
            eye = {**state["position"], "y": state["position"]["y"] + 1.62}
            for side, vector in vectors.items():
                neighbour = {axis: args[axis] - vector[index] for index, axis in enumerate(("x", "y", "z"))}
                if block(neighbour)["registryName"] in ("minecraft:air", "minecraft:water", "minecraft:lava"):
                    continue
                face = {axis: neighbour[axis] + .5 + vector[index] * .499 for index, axis in enumerate(("x", "y", "z"))}
                if sum((eye[axis] - face[axis]) * vector[index] for index, axis in enumerate(("x", "y", "z"))) <= .001:
                    continue
                if sum((eye[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) > 9:
                    continue
                candidates.append({**neighbour, "side": side, **{"hit" + axis.upper(): value for axis, value in face.items()}})
            assert candidates
            attachment = candidates[-1] if state.get("alternate_native_attachment") else candidates[0]
            item = state["items"][args["hotbarSlot"]]
            assert item is not None and item["hasTag"] is False
            state["selected"] = args["hotbarSlot"]
            identity = {"registryName": item["registryName"], "metadata": item["metadata"]}
            if item["registryName"] == "gregtech:gt.blockmachines":
                identity["itemMetadata"] = item["metadata"]
            state["blocks"][key(args)] = identity
            state["placements"].append({"position": key(args), "item": copy.deepcopy(item), "attachment": attachment})
            if state.get("cursor_after_port") and item["metadata"] == PORT_ITEMS["item_input"]["metadata"]:
                state["cursor"] = stack({"registryName": "minecraft:stone", "metadata": 0})
            if state.get("disconnect_place"):
                raise httpx.ReadError("uncertain native place", request=request)
            data = {"status": "submitted", "submitted": True, "accepted": True, "nativeInvoked": True,
                "targetBefore": target_before, "attachment": copy.deepcopy(attachment),
                "serverConfirmed": False, "executionValidated": False}
            if state.get("unconfirmed_native_metadata") == item["metadata"]:
                del state["blocks"][key(args)]
                data["accepted"] = state.get("unconfirmed_local_accepted", True)
            if state.get("omit_native_attachment"):
                data.pop("attachment")
        elif operation == "player.interact":
            point = {axis: args[axis] for axis in ("x", "y", "z")}
            if args["action"] == "attack":
                assert state["items"][state["selected"]] is None
                assert key(point) in state["blocks"]
                assert state["blocks"][key(point)]["registryName"] != "gregtech:gt.blockmachines"
                state["digs"].append(key(point))
                del state["blocks"][key(point)]
                if state.get("foreign_after_dig") and len(state["digs"]) == 2:
                    state["blocks"][key(point)] = {"registryName": "minecraft:chest", "metadata": 0}
            elif state["selected"] != 0 and args.get("sneaking") is True:
                vectors = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}
                vector = vectors[args["side"]]
                destination = {axis: point[axis] + vector[index] for index, axis in enumerate(("x", "y", "z"))}
                assert block(point)["registryName"] != "minecraft:air"
                assert block(destination)["registryName"] == "minecraft:air"
                item = state["items"][state["selected"]]
                assert item is not None and item["hasTag"] is False
                identity = {"registryName": item["registryName"], "metadata": item["metadata"]}
                if item["registryName"] == "gregtech:gt.blockmachines":
                    identity["itemMetadata"] = item["metadata"]
                state["blocks"][key(destination)] = identity
                state["placements"].append({"position": key(destination), "item": copy.deepcopy(item), "attachment": {**point, "side": args["side"]}})
                if state.get("cursor_after_port") and item["metadata"] == PORT_ITEMS["item_input"]["metadata"]:
                    state["cursor"] = stack({"registryName": "minecraft:stone", "metadata": 0})
                if state.get("disconnect_place"):
                    raise httpx.ReadError("uncertain native direct placement", request=request)
            elif state["selected"] == 0:
                assert key(point) == key(state["controller"])
                if args["sneaking"]:
                    state["construct_count"] += 1
                    await asyncio.sleep(state["construction_delay"])
                    for position in captured_positions():
                        if position["group"] == 0:
                            existing = state["blocks"].get(key(position), {})
                            if state["preserve_preinstalled"] and existing.get("itemMetadata") in {item["metadata"] for item in PORT_ITEMS.values()}:
                                continue
                            state["blocks"][key(position)] = {"registryName": "minecraft:stone", "metadata": 0} if state["wrong_casing"] else CASING.copy()
                    state["built"] = True
                else:
                    await asyncio.sleep(state["hint_delay"])
                    state["hint_count"] += 1
            else:
                assert state["items"][state["selected"]] is None and args["sneaking"] is False
                energy = state["blocks"].get(key(point), {}).get("itemMetadata") == PORT_ITEMS["energy"]["metadata"]
                state["gui"], state["window"] = ("energy" if energy and state.get("energy_support") else "machine"), 23
                if energy and state.get("energy_no_gui"):
                    state["window"] = 0
            data = {"status": "submitted"}
        elif operation == "machine.inspect":
            if key(args) == key(state["controller"]):
                data = {**state["controller"], "dimension": 0, "metaTileId": CONTROLLER_ITEM["metadata"],
                    "structureDescription": {"controllerStack": stack(CONTROLLER_ITEM)}, "components": components(),
                    "processing": {"structureFormed": state["formed"], "structureErrorBits": 0 if state["formed"] else 64},
                    "checkRecipeResult": {"message": "Actual public result"}}
            else:
                data = {"outputStorage": {"available": bool(state.get("me_provider")), "reason": "public_output_provider_unavailable_or_not_implemented"}}
        elif operation == "inventory.supply":
            assert args["expectedSessionId"] == "session" and args["expectedWorld"] == "new-world"
            assert state["cursor"] is None and all(item is None for item in state["items"].values())
            dispatched = 0
            for index, item in enumerate(args["items"]):
                dispatched += 1
                if index == state.get("supply_failure_at"):
                    break
                state["items"][index] = stack({key: item[key] for key in ("registryName", "metadata")}, item["count"])
            complete = "supply_failure_at" not in state
            data = {"source": "native_server_give_commands", "complete": complete, "outcomeUnknown": state.get("supply_unknown", False),
                "commandsAttempted": dispatched, "commandsAccepted": dispatched if complete else dispatched - 1}
            if state.get("supply_wrong_inventory"):
                state["items"][0] = stack(SUPPORT)
        elif operation == "chat.send":
            words = args["message"].split()
            if words == ["/clear", "TestPlayer"]:
                state["items"] = {index: None for index in range(41)}
            elif words[:2] == ["/give", "TestPlayer"]:
                index = next(index for index in range(36) if state["items"][index] is None)
                state["items"][index] = stack({"registryName": words[2], "metadata": int(words[4])}, int(words[3]))
            elif words[0] == "/setblock":
                point = dict(zip(("x", "y", "z"), map(int, words[1:4])))
                assert words[-1] != "keep" or block(point)["registryName"] == "minecraft:air"
                state["blocks"][key(point)] = {"registryName": words[4], "metadata": int(words[5])}
            elif words[0] == "//sel":
                state["selection"] = None
                state["message_seq"] = state.get("message_seq", 0) + 1
                state.setdefault("messages", []).append({"seq": state["message_seq"], "text": "§dSelection cleared."})
                if len(words) > 1:
                    assert words[1] == "extend"
            elif words[0] in ("//pos1", "//pos2"):
                point = dict(zip(("x", "y", "z"), map(int, words[1].split(","))))
                if words[0] == "//pos1":
                    state["selection"] = (point, point)
                else:
                    state["selection"] = (state["selection"][0], point)
            elif words == ["//replace", "air", "stone"]:
                if state.get("fail_worldedit"):
                    return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                        "error": {"code": "unsupported", "message": "Fixture WorldEdit refusal"}})
                first, last = state["selection"]
                for y in range(first["y"], last["y"] + 1):
                    point = {**first, "y": y}
                    if block(point)["registryName"] == "minecraft:air":
                        state["blocks"][key(point)] = {"registryName": "minecraft:stone", "metadata": 0}
            else:
                raise AssertionError("Unexpected construction command " + words[0])
            data = {"status": "submitted"}
        else:
            raise AssertionError("Unexpected native construction operation " + operation)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": copy.deepcopy(data)})

    client = BridgeClient(config, timeout=2, transport=httpx.MockTransport(respond))
    try:
        yield client, state
    finally:
        await client.aclose()


async def invoke(client, arguments=None, **options):
    return await run_blueprint_build(client, declaration() if arguments is None else arguments, anyio.Lock(), runner=run_workflow, **options)


async def test_native_task_proves_formation_registered_roles_and_restores(native_construction):
    client, state = native_construction
    original = copy.deepcopy(state["items"])
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "completed", result
    assert result["formationVerified"] is True and result["completeRulesKnown"] is False
    assert result["acceptance"]["structureFormed"] is True
    assert result["acceptance"]["registrationAcceptance"]["complete"] is True
    assert result["acceptance"]["registrationAcceptance"]["componentCoverageComplete"] is True
    assert {entry["role"] for entry in result["acceptance"]["registeredRoles"]} == set(PORT_ITEMS)
    output = next(entry for entry in result["acceptance"]["registeredRoles"] if entry["role"] == "item_output")
    assert output["finiteInventorySlots"] == 4 and output["publicMeProviderAvailable"] is False
    assert result["cleanup"]["inventoryRestored"] and result["cleanup"]["selectedHotbarRestored"]
    assert result["cleanup"]["allConfirmedSupportsRemoved"] and state["items"] == original and state["selected"] == 7
    assert len(result["cleanup"]["supports"]) == 1 and result["cleanup"]["supports"][0]["cleanupConfirmed"] is True
    assert state["blocks"][key({**CONTROLLER, "y": 2})]["registryName"] != SUPPORT["registryName"]
    assert state["hint_count"] == 1 and state["rechecks"] == 1
    assert len(state["digs"]) == 5 and len(state["placements"]) == 6
    assert all(port["frontPlane"] for port in result["selectedPorts"])
    assert all(port["position"]["y"] == CONTROLLER["y"] for port in result["selectedPorts"] if port["role"] in ("item_input", "item_output"))
    assert all(port["lateralEnvelopeFace"] for port in result["selectedPorts"])
    assert result["positionSelectionPolicy"]["preferredOperatingY"] == 3
    assert result["positionSelectionPolicy"]["movementContext"] == "Observed developer flight"
    assert "optimization remain unknown" in result["positionSelectionPolicy"]["unknown"]
    assert "phases" not in result and len(json.dumps(result)) < 10000
    assert set(result["timings_ms"]) == {"decision", "preparation", "construction", "acceptance", "restoration", "total"}
    assert abs(sum(value for key, value in result["timings_ms"].items() if key != "total") - result["taskElapsed_ms"]) <= 5


def ranking_task(client, controller, positions, *, floor=1, flight_profile=None):
    task = _ConstructionTask(client, declaration(controller=controller), run_workflow, started=time.monotonic())
    task.floor = floor
    task.capture = {"capturedFacing": "NORTH_NORMAL_NONE", "capturedVisitsComplete": True, "eventCaptureTruncated": False, "captureId": "ranking-capture"}
    task.bounds = {"worldXYZ": {key: {axis: function(point[axis] for point in positions) for axis in ("x", "y", "z")}
        for key, function in (("min", min), ("max", max))}}
    task.materials = {PORT_SLOTS[role]: {"item": PORT_ITEMS[role], "count": 1} for role in PORT_ITEMS}
    if flight_profile:
        task.position_snapshot = {"profile": flight_profile, "sessionId": "session", "world": {"dimension": 0},
            "player": {"flying": True, "serverFlight": {"available": True, "flying": True, "allowFlying": True, "dimension": 0}}}
    groups = {0: {"id": 0, "descriptionKeys": list(ROLE_KEYS.values()), "suggestedCandidates": [stack(CASING)], "candidateScope": "Public fixture"},
        1: {"id": 1, "descriptionKeys": ["public.required.air"], "suggestedCandidates": []}}
    verified = []

    async def verify_clear(current):
        verified.append(current)
    task.verify_clear_volume = verify_clear
    return task, groups, verified


@pytest.mark.parametrize("reverse", [False, True])
async def test_ground_context_prefers_legal_lateral_faces_and_standing_operation_height_over_controller_or_visit_order(native_construction, reverse):
    client, state = native_construction
    controller = {**CONTROLLER, "y": 9}
    offsets = [(0, 9, 0, 0), (0, 2, 0, 0), (-7, 9, 0, 0), (0, 2, 7, 0), (1, 2, 7, 0),
        (-7, 3, 0, 0), (7, 3, 0, 0), (0, 3, 7, 0), (-7, 16, -7, 1), (7, 16, 7, 1)]
    positions = [{"x": CONTROLLER["x"] + dx, "y": y, "z": CONTROLLER["z"] + dz, "group": group} for dx, y, dz, group in offsets]
    # The controller itself is never a placement candidate in a real capture.
    positions[0]["y"] = 8
    if reverse:
        positions.reverse()
    task, groups, verified = ranking_task(client, controller, positions)
    await task.decide_ports(positions, groups)
    selected = {port["role"]: port for port in task.selected_ports}
    assert selected["item_input"]["position"] == {"x": CONTROLLER["x"], "y": 2, "z": CONTROLLER["z"] + 7}
    assert selected["item_output"]["position"] == {"x": CONTROLLER["x"] + 1, "y": 2, "z": CONTROLLER["z"] + 7}
    assert selected["energy"]["position"] == {"x": CONTROLLER["x"] - 7, "y": 3, "z": CONTROLLER["z"]}
    assert selected["maintenance"]["position"] == {"x": CONTROLLER["x"] + 7, "y": 3, "z": CONTROLLER["z"]}
    assert all(port["lateralEnvelopeFace"] for port in selected.values())
    assert not selected["item_input"]["frontPlane"] and selected["energy"]["frontPlane"]
    assert task.position_selection_policy["floorBlockY"] == 1 and task.position_selection_policy["lowestOperatingY"] == 2
    assert task.position_selection_policy["preferredOperatingY"] == 3 and "publicly permitted" in task.position_selection_policy["scope"]
    assert verified == [positions] and not state["calls"]


@pytest.mark.parametrize("reverse", [False, True])
async def test_developer_flight_groups_all_roles_on_exposed_front_near_controller_height_without_visit_order_bias(native_construction, reverse):
    client, state = native_construction
    controller = {**CONTROLLER, "y": 9}
    offsets = [(0, 2, 0, 0), (0, 9, 1, 0), (-7, 9, 0, 0), (0, 9, 14, 0), (-1, 9, 0, 0),
        (1, 9, 0, 0), (-2, 9, 0, 0), (2, 9, 0, 0), (-7, 16, 0, 1), (7, 16, 14, 1)]
    positions = [{"x": CONTROLLER["x"] + dx, "y": y, "z": CONTROLLER["z"] + dz, "group": group} for dx, y, dz, group in offsets]
    if reverse:
        positions.reverse()
    task, groups, verified = ranking_task(client, controller, positions, flight_profile="developer")
    await task.decide_ports(positions, groups)
    assert [port["position"] for port in task.selected_ports] == [
        {"x": CONTROLLER["x"] + dx, "y": 9, "z": CONTROLLER["z"]} for dx in (-1, -2, 1, 2)]
    assert all(port["frontPlane"] and port["lateralEnvelopeFace"] for port in task.selected_ports)
    assert task.position_selection_policy["movementContext"] == "Observed developer flight"
    assert task.position_selection_policy["preferredOperatingY"] == 9 and task.position_selection_policy["floorBlockY"] == 1
    assert "existing guarded snapshot" in task.position_selection_policy["basis"]
    assert "do not prove external clearance" in task.position_selection_policy["unknown"]
    assert verified == [positions] and not state["calls"]


async def test_player_profile_does_not_upgrade_ground_sorting_from_developer_flight_permissions(native_construction):
    client, state = native_construction
    controller = {**CONTROLLER, "y": 9}
    positions = [{"x": CONTROLLER["x"] + dx, "y": y, "z": CONTROLLER["z"] + dz, "group": group}
        for dx, y, dz, group in [(-1, 2, 0, 0), (0, 2, 0, 0), (1, 2, 0, 0), (-1, 3, 0, 0), (1, 3, 0, 0),
            (-1, 9, 0, 0), (1, 9, 0, 0), (-1, 10, 2, 1), (1, 10, 2, 1)]]
    task, groups, _ = ranking_task(client, controller, positions, flight_profile="player")
    await task.decide_ports(positions, groups)
    assert [port["position"]["y"] for port in task.selected_ports] == [2, 2, 3, 3]
    assert task.position_selection_policy["movementContext"] == "Ground or unconfirmed flight" and not state["calls"]


async def test_port_sorting_falls_back_within_public_group_when_no_lateral_candidate_exists(native_construction):
    client, state = native_construction
    positions = [{"x": CONTROLLER["x"] + dx, "y": y, "z": CONTROLLER["z"] + dz, "group": group}
        for dx, y, dz, group in [(0, 2, 0, 0), (1, 2, 0, 0), (0, 3, 0, 0), (1, 3, 0, 0), (0, 4, 0, 0),
            (-3, 2, -3, 1), (3, 4, 3, 1)]]
    task, groups, _ = ranking_task(client, CONTROLLER, positions)
    await task.decide_ports(positions, groups)
    assert len(task.selected_ports) == 4 and all(port["group"] == 0 and not port["lateralEnvelopeFace"] for port in task.selected_ports)
    assert [port["position"]["y"] for port in task.selected_ports] == [2, 2, 3, 3]
    assert not state["calls"]


async def test_operating_height_uses_high_lowest_layer_and_stays_inside_a_one_layer_capture(native_construction):
    client, _ = native_construction
    positions = [{"x": CONTROLLER["x"] + dx, "y": 10, "z": CONTROLLER["z"] + dz, "group": 0}
        for dx, dz in [(-1, 0), (0, 0), (1, 0), (-1, 1), (0, 1), (1, 1)]]
    task, groups, _ = ranking_task(client, {**CONTROLLER, "y": 20}, positions)
    await task.decide_ports(positions, groups)
    assert task.position_selection_policy["lowestOperatingY"] == task.position_selection_policy["preferredOperatingY"] == 10
    assert all(port["position"]["y"] == 10 for port in task.selected_ports)


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_full_hotbar_storage_controller_uses_actual_public_widgets_and_restores(native_construction, order):
    client, state = native_construction
    state["items"][9], state["items"][1] = state["items"][1], None
    for index in (1, 4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["controller_original_index"] = 9
    state["restoration_delay"] = .02
    original = copy.deepcopy(state["items"])
    outer_start = time.monotonic()
    result = await invoke(client, declaration(restoreInventory=True, constructionOrder=order))
    elapsed = (time.monotonic() - outer_start) * 1000
    assert result["status"] == "completed", result
    assert state["items"] == original and state["cursor"] is None
    assert len(result["inventoryMoves"]) == 2
    assert all(record["restorationConfirmed"] for record in result["inventoryMoves"])
    assert result["pendingInventoryRestoration"] == []
    assert result["inventoryRestorationRequested"] is True and result["retainedInventoryMoves"] == []
    assert state["gui"] is None and state["selected"] == 7
    assert result["timings_ms"]["preparation"] >= 20 and abs(elapsed - result["taskElapsed_ms"]) < 50
    assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9, 10, 1]
    native = [call for call in writes(state) if call["operation"] == "gui.widget_action" and call["args"]["widgetId"].startswith("public:actual:")]
    assert len(native) == 8 and not any(call["operation"] == "gui.slot_click" for call in writes(state))
    widget_reads = [call for call in state["calls"] if call["operation"] == "gui.widgets" and call["args"]["screenId"] == "screen:creative"]
    assert len(widget_reads) == 3  # One public binding page per opened screen, no repeated item-snapshot reuse.


@pytest.mark.parametrize("operation", ["world.structure", "world.spatial", "gui.widgets", "gui.open_inventory", "player.hotbar", "client.background"])
async def test_missing_required_api_is_unavailable_before_any_action(native_construction, operation):
    client, state = native_construction
    state["caps"].remove(operation)
    result = await invoke(client)
    assert result["status"] == "unavailable" and result["reason"] == "required_capability_unavailable"
    assert not writes(state)


async def test_explicit_me_item_refused_before_actions(native_construction):
    client, state = native_construction
    state["me_item"] = True
    result = await invoke(client)
    assert result["reason"] == "me_output_unsupported" and result["status"] == "unavailable"
    assert not writes(state)


@pytest.mark.parametrize("flag", ["capture_partial", "context"])
async def test_partial_or_disagreed_capture_never_builds_shell(native_construction, flag):
    client, state = native_construction
    state[flag] = flag == "capture_partial"
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["reason"] == "native_hint_capture_incomplete" and not state["built"]
    assert result["cleanup"]["inventoryRestored"] and result["formationVerified"] is False


async def test_runtime_pages_preserve_capture_and_full_count(native_construction):
    client, state = native_construction
    state["runtime_page_size"] = 4
    result = await invoke(client)
    assert result["status"] == "completed", result
    calls = [call["args"] for call in state["calls"] if call["operation"] == "world.structure"]
    assert [args["runtimeOffset"] for args in calls] == [0, 4, 8, 12, 16, 20, 24]
    assert all(args["captureId"] == "immutable-capture" for args in calls[1:])


async def test_replaced_capture_never_builds(native_construction):
    client, state = native_construction
    state.update(runtime_page_size=4, stale_capture=True)
    result = await invoke(client)
    assert result["reason"] == "native_capture_changed" and not state["built"]


async def test_actual_floor_conflict_reports_raise_without_foreign_breaking(native_construction):
    client, state = native_construction
    state["controller"]["y"] = 2
    result = await invoke(client, declaration(controller=state["controller"].copy()))
    assert result["reason"] == "floor_conflict_raise_required" and not state["built"] and state["digs"] == []
    assert result["floorBlockY"] == 1


async def test_foreign_observed_volume_is_never_cleared(native_construction):
    client, state = native_construction
    foreign = {"x": CONTROLLER["x"] + 1, "y": 4, "z": CONTROLLER["z"] + 2}
    state["blocks"][key(foreign)] = {"registryName": "minecraft:chest", "metadata": 0}
    result = await invoke(client)
    assert result["reason"] == "observed_volume_not_clear" and not state["built"]
    assert key(foreign) not in state["digs"] and state["blocks"][key(foreign)]["registryName"] == "minecraft:chest"


async def test_only_actual_suggested_task_casings_can_be_replaced(native_construction):
    client, state = native_construction
    state["wrong_casing"] = True
    result = await invoke(client)
    assert result["reason"] == "constructed_baseline_unconfirmed"
    assert len(state["digs"]) == 1 and result["formationVerified"] is False


async def test_low_ports_need_actual_clear_feet_above_grass(native_construction):
    client, state = native_construction
    state["low_face_requires_feet_two"] = True
    result = await invoke(client)
    assert result["status"] == "completed", result
    moves = [call["args"] for call in state["calls"] if call["operation"] == "dev.teleport"]
    assert all(move["y"] >= 2 for move in moves) and any(move["y"] == 2 for move in moves)


async def test_native_formation_failure_retains_actual_diagnostic_and_cleans(native_construction):
    client, state = native_construction
    state["formed_allowed"] = False
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["formationVerified"] is False
    assert result["acceptance"]["structureFormed"] is False and result["acceptance"]["structureErrorBits"] == 64
    assert state["rechecks"] == 1 and result["cleanup"]["inventoryRestored"]
    assert result["timings_ms"]["acceptance"] >= 1500


async def test_real_registered_role_is_required_even_when_formed(native_construction):
    client, state = native_construction
    state["bad_role"] = "item_output"
    result = await invoke(client)
    assert state["formed"] and result["formationVerified"] is False and result["reason"] == "registered_role_not_accepted"
    assert result["acceptance"]["structureFormed"] is True
    registration = result["acceptance"]["registrationAcceptance"]
    assert registration["complete"] is False and registration["componentCoverageComplete"] is True
    assert registration["reason"] == "registered_role_not_accepted" and registration["matchingRegisteredEntries"] == 0
    assert registration["unacceptedPort"]["role"] == "item_output"
    assert [entry["role"] for entry in result["acceptance"]["registeredRoles"]] == ["item_input"]


@pytest.mark.parametrize("fault,reason", [("controller_position", "acceptance_controller_changed"),
    ("controller_dimension", "acceptance_controller_changed"), ("controller_identity", "acceptance_controller_changed"),
    ("coverage", "registered_role_coverage_incomplete"), ("unavailable", "registered_role_unavailable"),
    ("duplicate", "registered_role_not_accepted"), ("invalid", "registered_role_not_accepted")])
async def test_server_formation_fact_preserves_independent_strict_registration_boundary(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    original = client.call

    async def changed_acceptance(operation, arguments, **options):
        data = await original(operation, arguments, **options)
        if operation == "machine.inspect" and key(arguments) == key(CONTROLLER) and state["rechecks"]:
            if fault == "controller_position":
                data["x"] += 1
            elif fault == "controller_dimension":
                data["dimension"] += 1
            elif fault == "controller_identity":
                data["structureDescription"]["controllerStack"]["metadata"] += 1
            elif fault == "coverage":
                data["components"]["hasMore"] = True
            elif fault == "unavailable":
                data["components"]["unavailableRoles"] = [{"role": "item_input", "reason": "public_role_unavailable"}]
            else:
                entry = next(entry for entry in data["components"]["entries"] if entry["role"] == "item_input")
                if fault == "duplicate":
                    data["components"]["entries"].append(copy.deepcopy(entry))
                    data["components"]["total"] += 1
                else:
                    entry["valid"] = False
        return data

    monkeypatch.setattr(client, "call", changed_acceptance)
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert state["formed"] is True and result["formationVerified"] is False and state["rechecks"] == 1
    if fault.startswith("controller_"):
        assert result["acceptance"] is None
    else:
        assert result["acceptance"]["structureFormed"] is True
        registration = result["acceptance"]["registrationAcceptance"]
        assert registration["complete"] is False
        assert registration["componentCoverageComplete"] is (fault != "coverage")
        assert result["acceptance"]["registeredRoles"] == []
        if fault != "coverage":
            assert registration["reason"] == reason and registration["unacceptedPort"]["role"] == "item_input"
            assert registration["matchingRegisteredEntries"] == {"duplicate": 2, "unavailable": 1, "invalid": 0}[fault]


async def test_public_me_provider_refuses_finite_output_claim(native_construction):
    client, state = native_construction
    state["me_provider"] = True
    result = await invoke(client)
    assert result["formationVerified"] is False and result["reason"] == "ordinary_output_finite_storage_unavailable"


@pytest.mark.parametrize("restore", [False, True])
async def test_uncertain_native_pickup_is_never_replayed_and_pending_restoration_survives(native_construction, restore):
    client, state = native_construction
    state["items"][9], state["items"][1] = state["items"][1], stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    for index in (4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["disconnect_pickup"] = True
    result = await invoke(client, declaration(restoreInventory=restore))
    assert result["status"] == "failed" and len(state["inventory_activations"]) == 1
    assert len(result["pendingInventoryRestoration"]) == 1 and result["pendingInventoryRestoration"][0]["outcomeUnknown"] is True
    assert result["cleanup"]["inventoryRestored"] is False and result["rolledBack"] is False
    assert result["inventoryRestorationRequested"] is restore and result["retainedInventoryMoves"] == []


async def test_uncertain_support_place_is_not_retried_or_broken(native_construction):
    client, state = native_construction
    state["disconnect_place"] = True
    result = await invoke(client)
    assert result["status"] == "failed" and len(state["placements"]) == 1 and not state["digs"]
    assert result["cleanup"]["supports"][0]["placementAttempted"] is True
    assert result["cleanup"]["allConfirmedSupportsRemoved"] is False
    assert result["failedNativeWorkflows"][0]["outcomeUnknown"] is True
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    assert not any(call["operation"] == "player.hotbar" for call in state["calls"])
    assert not any(call["operation"] == "player.interact" for call in state["calls"])


async def test_changed_borrowed_stack_is_not_restored_or_retried(native_construction):
    client, state = native_construction
    state["items"][9], state["items"][1] = state["items"][1], stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    for index in (4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["corrupt_restoration"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["formationVerified"] is True
    assert result["pendingInventoryRestoration"] and len(state["inventory_activations"]) == 6
    assert result["cleanup"]["inventoryRestored"] is False


async def test_total_timer_includes_lock_and_all_stages(native_construction):
    client, state = native_construction
    resolution = time.get_clock_info("monotonic").resolution
    # Windows GetTickCount64 quantizes reads at 15.625ms; an asyncio timer can
    # also wake within one clock tick. Make each delay several ticks long.
    state["hint_delay"] = max(.075, 8 * resolution)
    state["construction_delay"] = max(.1, 8 * resolution)
    lock_delay = max(.125, 10 * resolution)
    lock = anyio.Lock()
    begin_wait = asyncio.Event()
    async def holder(event):
        async with lock:
            event.set()
            await begin_wait.wait()
            await asyncio.sleep(lock_delay)
    event = asyncio.Event()
    holder_task = asyncio.create_task(holder(event))
    await event.wait()
    start = time.perf_counter()
    begin_wait.set()  # The entire injected lock delay starts inside the task's timing boundary.
    result = await run_blueprint_build(client, declaration(), lock, runner=run_workflow)
    await holder_task
    elapsed = (time.perf_counter() - start) * 1000
    assert result["status"] == "completed", result
    # Bound quantization, rather than assuming requested sleeps equal measured
    # milliseconds: decision has two timing spans/two sleeps, construction one
    # span/one sleep, and the total one span/three sleeps. Integer output floors.
    assert result["timings_ms"]["decision"] >= int((lock_delay + state["hint_delay"] - 4 * resolution) * 1000)
    assert result["timings_ms"]["construction"] >= int((state["construction_delay"] - 2 * resolution) * 1000)
    assert result["taskElapsed_ms"] >= int((lock_delay + state["hint_delay"] + state["construction_delay"] - 4 * resolution) * 1000)
    assert abs(elapsed - result["taskElapsed_ms"]) < 50


async def test_invalid_declaration_does_not_contact_client(native_construction):
    client, state = native_construction
    ports = declaration()["requestedPorts"]
    ports[1].pop("ordinaryOutputItem")
    with pytest.raises(BridgeError) as caught:
        await invoke(client, declaration(requestedPorts=ports))
    assert caught.value.code == "INVALID_ARGUMENTS" and state["calls"] == []


def test_schema_bounds_total_native_task_and_exact_declared_identity():
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["timeout_ms"]["maximum"] == 120000
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["timeout_ms"]["default"] == 60000
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["requestedPorts"]["maxItems"] == 6
    assert BLUEPRINT_BUILD_SCHEMA["dependentRequired"]["supportItem"] == ["supportHotbarSlot"]
    order = BLUEPRINT_BUILD_SCHEMA["properties"]["constructionOrder"]
    assert order["type"] == "string" and order["enum"] == ["shell_first", "ports_first"]
    assert "default" not in order and "actualSurvival" in order["description"]
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["restoreInventory"] == {"type": "boolean", "default": False}
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["includeDetails"]["type"] == "boolean"
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["includeDetails"]["default"] is False


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_default_compact_success_retains_real_identity_registration_and_cleanup(native_construction, order):
    client, state = native_construction
    arguments = declaration(constructionOrder=order)
    arguments.pop("includeDetails")
    result = await invoke(client, arguments)
    assert result["status"] == "completed" and result["formationVerified"] is True, result
    assert result["detailsRequested"] is result["detailsIncluded"] is False
    assert result["reportScope"] == "compact_success" and result["detailsOmitted"]
    assert result["controller"] == {"position": CONTROLLER, "item": CONTROLLER_ITEM}
    assert result["nonAtomic"] and result["completeRulesKnown"] is result["fullStructureChecked"] is False
    acceptance = result["acceptance"]
    assert acceptance["registrationAcceptance"]["complete"] and acceptance["registrationAcceptance"]["componentCoverageComplete"]
    assert {entry["role"] for entry in acceptance["registeredRoles"]} == set(PORT_SLOTS)
    for port in result["selectedPorts"]:
        assert port["item"] == PORT_ITEMS[port["role"]] and port["placementConfirmed"] and port["nativePlaceBlockUsed"]
        actual = next(entry for entry in reversed(state["placements"]) if entry["position"] == key(port["position"]))
        assert actual["item"] == stack(port["item"])
        assert "attachment" not in port
        assert any(entry["role"] == port["role"] and entry["position"] == port["position"] and entry["registered"] and entry["valid"]
            for entry in acceptance["registeredRoles"])
        assert "suggestedCandidates" not in port
        if "publicEmptyContents" in port:
            assert port["publicEmptyContents"] == {"coverage":
                "Complete exposed public inventory/tanks only; hidden buffers and complete machine rules remain unknown."}
        if order == "ports_first":
            assert port["preservedAfterNativeShell"] and result["portsPreservedAfterNativeShell"]
    policy = result["positionSelectionPolicy"]
    assert policy["scope"] and policy["unknown"] and "order" not in policy and "basis" not in policy
    assert result["cleanup"]["allConfirmedSupportsRemoved"] and result["cleanup"]["retainedInventoryVerified"]
    assert result["cleanup"]["error"] is result["cleanup"]["supportError"] is None
    assert set(result["timings_ms"]) == {"decision", "preparation", "construction", "acceptance", "restoration", "total"}
    assert state["construct_count"] == state["rechecks"] == 1


@pytest.mark.parametrize("include_details", [None, False, True])
async def test_failed_builder_keeps_complete_diagnostics_regardless_of_detail_request(native_construction, include_details):
    client, state = native_construction
    state["bad_role"] = "item_output"
    arguments = declaration(includeDetails=include_details)
    if include_details is None:
        arguments.pop("includeDetails")
    result = await invoke(client, arguments)
    assert result["status"] == "failed" and result["reason"] == "registered_role_not_accepted"
    assert result["detailsIncluded"] and result["reportScope"] == "full_result"
    assert result["detailsRequested"] is (include_details is True)
    assert result["positionSelectionPolicy"] and result["selectedPorts"][0]["suggestedCandidates"]
    assert result["acceptance"]["registrationAcceptance"]["unacceptedPort"]["role"] == "item_output"
    assert result["lastActionReceipt"]["dispatched"] and result["cleanup"]["allConfirmedSupportsRemoved"]
    assert len(state["placements"]) == 6 and state["construct_count"] == state["rechecks"] == 1


@pytest.mark.parametrize("capability_format", ["list", "dict"])
async def test_missing_native_placement_capability_retains_exact_legacy_attachment(native_construction, capability_format):
    client, state = native_construction
    state["native_face_refusal"] = "face_not_visible"
    state["caps"].remove("player.place_block")
    if capability_format == "dict":
        state["caps"] = {**dict.fromkeys(state["caps"], True), "player.place_block": False}
    result = await invoke(client)
    assert result["status"] == "completed", result
    assert not any(call["operation"] == "player.place_block" for call in state["calls"])
    for port in result["selectedPorts"]:
        assert port["nativePath"] == "player.interact(use, exact_attachment_face, sneaking=true)"
        assert port["nativePlaceBlockUsed"] is False and port["placementConfirmed"] is True
        actual = next(entry for entry in state["placements"] if entry["position"] == key(port["position"]))
        assert actual["attachment"] == port["attachment"]
    placing = [call for call in writes(state) if call["operation"] == "player.interact" and call["args"].get("sneaking") is True]
    assert len(placing) == 7  # Support, controller, shell and four exact port placements.


@pytest.mark.parametrize("capability_format", ["list", "dict"])
async def test_native_placement_uses_returned_client_face_and_authoritative_acceptance(native_construction, capability_format):
    client, state = native_construction
    state["alternate_native_attachment"] = True
    if capability_format == "dict":
        state["caps"] = dict.fromkeys(state["caps"], True)
    result = await invoke(client)
    assert result["status"] == "completed" and result["formationVerified"] is True, result
    assert result["acceptance"]["registrationAcceptance"]["complete"] is True
    native_calls = [call for call in state["calls"] if call["operation"] == "player.place_block"]
    assert len(native_calls) == len(state["placements"]) == 6
    changed_face = False
    for port in result["selectedPorts"]:
        assert port["nativePath"] == "player.place_block(native_client_attachment, sneaking=true)"
        assert port["nativePlaceBlockUsed"] and port["placementConfirmed"]
        actual = next(entry for entry in reversed(state["placements"]) if entry["position"] == key(port["position"]))
        assert port["attachment"] == actual["attachment"] and all(name in port["attachment"] for name in ("hitX", "hitY", "hitZ"))
        index = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.place_block" and key(call["args"]) == key(port["position"]))
        public_ray = next(call["args"]["to"] for call in reversed(state["calls"][:index])
            if call["operation"] == "world.spatial" and call["args"]["mode"] == "raycast")
        changed_face |= key(port["attachment"]) != tuple(math.floor(public_ray[axis]) for axis in ("x", "y", "z"))
    assert changed_face  # The public planner's attachment is not reported as the native selection.
    assert len([call for call in state["calls"] if call["operation"] == "player.interact"
        and call["args"].get("sneaking") is True and call["args"].get("action") == "use"]) == 1  # Blueprint shell only.


@pytest.mark.parametrize("refusal", ["face_not_visible", "blocked", "out_of_reach"])
async def test_native_placement_refusal_never_falls_back_or_replays(native_construction, refusal):
    client, state = native_construction
    state["native_face_refusal"] = refusal
    result = await invoke(client)
    assert result["status"] == "failed" and not result["formationVerified"]
    assert result["failedNativeWorkflows"][0]["error"]["code"] == refusal
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    assert not any(call["operation"] == "player.interact" for call in state["calls"])
    assert not state["placements"] and not state["digs"]


async def test_missing_native_attachment_receipt_is_never_replaced_with_public_precheck(native_construction):
    client, state = native_construction
    state["omit_native_attachment"] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == "workflow_projection_unavailable"
    support = result["cleanup"]["supports"][0]
    assert support["placementAttempted"] and support["nativePlaceBlockUsed"]
    assert "attachment" not in support and not support.get("placementConfirmed")
    assert len(state["placements"]) == 1 and not state["digs"]
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == 1
    assert not any(call["operation"] == "player.interact" for call in state["calls"])


@pytest.mark.parametrize("accepted", [True, False, None])
async def test_unconfirmed_placement_has_bounded_wait_and_retains_evidence(native_construction, accepted):
    client, state = native_construction
    state["unconfirmed_native_metadata"] = PORT_ITEMS["item_input"]["metadata"]
    state["unconfirmed_local_accepted"] = accepted
    result = await invoke(client, declaration(timeout_ms=10000, constructionOrder="ports_first"))
    assert result["status"] == "failed" and not result["formationVerified"]
    failed = next(phase for phase in result["failedNativeWorkflows"] if phase["error"]["code"] == "WAIT_TIMEOUT")
    receipt = next(action for action in failed["actionReceipts"] if action["id"] == "place")
    assert receipt["placementObservation"]["accepted"] is accepted
    assert receipt["placementObservation"]["nativeInvoked"] is True
    assert receipt["placementObservation"]["serverConfirmed"] is False
    confirmation = failed["placementConfirmation"]
    assert confirmation["lastTarget"]["registryName"] == "minecraft:air"
    assert 1 <= confirmation["attempts"] <= 21 and confirmation["matched"] is False
    assert failed["elapsed_ms"] < 4000
    attempted = [entry for entry in state["placements"] if entry["item"]["metadata"] == PORT_ITEMS["item_input"]["metadata"]]
    assert len(attempted) == 1
    assert result["cleanup"]["allConfirmedSupportsRemoved"]


async def test_storage_controller_uses_one_reservation_then_becomes_empty_hand(native_construction):
    client, state = native_construction
    state["items"][25], state["items"][1] = state["items"][1], None
    for index in (4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    original = copy.deepcopy(state["items"])
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "completed", result
    assert len(result["inventoryMoves"]) == 1 and result["inventoryMoves"][0]["restorationConfirmed"] is True
    assert state["inventory_activations"] == [25, 1, 1, 25]
    assert state["items"] == original and result["cleanup"]["inventoryRestored"]
    # The retrieved controller must be returned before the empty-hand support
    # dig; no extra unrelated hotbar stack was moved merely to create a hand.
    deposit = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action"
        and call["args"]["widgetId"] == "public:actual:" + str(5000 - 25 * 13) and call["args"]["action"] == "activate"
        and index > next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.place_block"
            and key(call["args"]) == key(CONTROLLER)))
    first_dig = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact" and call["args"]["action"] == "attack")
    assert deposit < first_dig


async def test_unavailable_null_rays_report_safe_refusal_without_native_placement(native_construction):
    client, state = native_construction
    state["null_rays"] = True
    result = await invoke(client)
    assert result["reason"] == "safe_visible_approach_unavailable" and not state["placements"]
    assert not any(call["operation"] == "player.interact" for call in state["calls"])


async def test_far_side_server_ray_is_not_a_native_attachment_proof(native_construction):
    client, state = native_construction
    task = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    target = {"x": -44, "y": 3, "z": 125}
    # Mock public rays report requested faces even from the wrong half-space.
    # A downward face can only be accepted from a real clear eye below it.
    state["blocks"][key(target)] = CASING.copy()
    try:
        attachment, side, steps = await task.approach([(target, 0)])
    except BridgeError as error:
        assert error.details["reason"] == "safe_visible_approach_unavailable"
    else:
        moves = [step["args"] for step in steps if step.get("tool") == "mcb_teleport"]
        feet = moves[-1]["y"] if moves else state["position"]["y"]
        assert feet + 1.62 < target["y"] + .001
        assert attachment == target and side == 0
    assert not writes(state)


async def test_cached_public_binding_never_reuses_its_stale_item_snapshot(native_construction):
    client, state = native_construction
    state["items"][9], state["items"][1] = state["items"][1], stack({"registryName": "minecraft:stone", "metadata": 1}, 64)
    for index in (4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["change_controller_after_borrow"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["failedPhase"] == "retrieve_controller"
    assert state["inventory_activations"] == [1, 10, 10, 1]
    assert state["items"][9]["count"] == 2 and state["items"][1]["count"] == 64 and state["cursor"] is None
    assert result["pendingInventoryRestoration"] == []
    assert not state["placements"]


async def test_native_inventory_coverage_is_verified_before_pickup(native_construction):
    client, state = native_construction
    state["items"][9], state["items"][1] = state["items"][1], stack({"registryName": "minecraft:stone", "metadata": 1}, 64)
    for index in (4, 7):
        state["items"][index] = stack({"registryName": "minecraft:stone", "metadata": index}, 64)
    state["partial_native_inventory"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["failedPhase"] == "borrow_1"
    assert state["inventory_activations"] == [] and state["cursor"] is None
    assert result["pendingInventoryRestoration"] == [] and result["cleanup"]["inventoryRestored"] is True


async def test_ports_first_installs_empty_normal_ports_then_proves_native_retention(native_construction):
    client, state = native_construction
    result = await invoke(client, declaration(constructionOrder="ports_first", restoreInventory=True))
    assert result["status"] == "completed", result
    assert result["constructionOrder"] == "ports_first" and result["portsPreservedAfterNativeShell"] is True
    assert result["formationVerified"] is True and result["completeRulesKnown"] is False
    assert state["hint_count"] == state["construct_count"] == state["rechecks"] == 1
    assert len(state["digs"]) == 1 and len(state["placements"]) == 6
    assert all(port["placementBeforeNativeShell"] and port["preservedAfterNativeShell"] for port in result["selectedPorts"])
    assert all(port["beforeNativeShell"] == port["afterNativeShell"] for port in result["selectedPorts"])
    assert all("digAttempted" not in port and "constructedBaseline" not in port for port in result["selectedPorts"])
    assert all(port["publicEmptyContents"]["inventoryAllEmpty"] for port in result["selectedPorts"])
    assert all(port["publicEmptyContents"]["observationPhase"] == "ports_after_formation" for port in result["selectedPorts"])
    output = next(port for port in result["selectedPorts"] if port["role"] == "item_output")
    assert output["item"] == PORT_ITEMS["item_output"] and output["publicEmptyContents"]["totalInventorySlots"] == 4
    assert result["cleanup"]["inventoryRestored"] and result["cleanup"]["allConfirmedSupportsRemoved"]
    build_index = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact"
        and call["args"].get("sneaking") is True and key(call["args"]) == key(CONTROLLER) and call["args"]["side"] in (2, 3, 4, 5)
        and index > max(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.place_block" and call["args"]["hotbarSlot"] == 5))
    assert all(index < build_index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact" and call["args"]["action"] == "attack")


async def test_ports_first_blueprint_overwrite_is_failed_without_replaying_ports(native_construction):
    client, state = native_construction
    state["preserve_preinstalled"] = False
    result = await invoke(client, declaration(constructionOrder="ports_first", restoreInventory=True))
    assert result["status"] == "failed" and result["reason"] == "blueprint_port_identity_changed"
    assert result["failedPhase"] == "ports_after_shell" and result["formationVerified"] is False
    assert result["portsPreservedAfterNativeShell"] is False
    assert len(state["placements"]) == 6 and state["construct_count"] == 1 and state["rechecks"] == 0
    assert len(state["digs"]) == 1 and result["cleanup"]["inventoryRestored"] is True
    port = result["selectedPorts"][0]
    assert port["beforeNativeShell"]["metaTileId"] == PORT_ITEMS[port["role"]]["metadata"]
    assert port["afterNativeShell"]["registryName"] == CASING["registryName"]
    assert not port["preservedAfterNativeShell"]


async def test_ports_first_missing_native_attachment_reports_unsupported_without_building(native_construction):
    client, state = native_construction
    state["hint_shift"] = (10, 2, 0)
    result = await invoke(client, declaration(constructionOrder="ports_first", restoreInventory=True))
    assert result["reason"] == "ports_first_attachment_unsupported" and result["status"] == "failed"
    assert state["construct_count"] == state["rechecks"] == 0 and len(state["placements"]) == 2
    assert result["cleanup"]["inventoryRestored"] is True


async def test_ports_first_nonempty_public_inventory_stops_before_native_shell(native_construction):
    client, state = native_construction
    state["nonempty_preinstalled"] = "item_input"
    result = await invoke(client, declaration(constructionOrder="ports_first", restoreInventory=True))
    assert result["status"] == "failed" and result["reason"] == "preinstalled_port_not_empty"
    assert state["construct_count"] == 0 and state["rechecks"] == 0
    assert result["cleanup"]["inventoryRestored"] is True


async def test_ports_first_floor_conflict_stops_before_ports_and_shell(native_construction):
    client, state = native_construction
    state["controller"]["y"] = 2
    result = await invoke(client, declaration(controller=state["controller"].copy(), constructionOrder="ports_first"))
    assert result["reason"] == "floor_conflict_raise_required"
    assert state["construct_count"] == 0 and len(state["placements"]) == 1 and not state["digs"]


async def test_ports_first_mid_placement_cursor_is_preserved_and_not_used_for_more_actions(native_construction):
    client, state = native_construction
    state["cursor_after_port"] = True
    result = await invoke(client, declaration(constructionOrder="ports_first"))
    assert result["status"] == "failed" and result["reason"] == "creative_material_changed"
    assert len(state["placements"]) == 3 and state["construct_count"] == state["rechecks"] == 0
    assert state["cursor"] is not None and result["cleanup"]["inventoryRestored"] is False


async def test_ports_first_explicit_me_output_is_unavailable_before_any_action(native_construction):
    client, state = native_construction
    state["me_item"] = True
    result = await invoke(client, declaration(constructionOrder="ports_first"))
    assert result["reason"] == "me_output_unsupported" and not writes(state)


async def test_shell_first_remains_the_default_and_replaces_only_constructed_casings(native_construction):
    client, state = native_construction
    result = await invoke(client)
    assert result["status"] == "completed" and result["constructionOrder"] == "shell_first"
    assert len(state["digs"]) == 5 and all(port["digConfirmed"] for port in result["selectedPorts"])
    assert result["portsPreservedAfterNativeShell"] is False


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_default_retains_verified_inventory_and_controller_gui(native_construction, order):
    client, state = native_construction
    original = fill_hotbar_with_storage_controller(state)
    result = await invoke(client, declaration(constructionOrder=order))
    expected = copy.deepcopy(original)
    expected[10], expected[1] = expected[1], None
    assert result["status"] == "completed", result
    assert result["inventoryRestorationRequested"] is False
    assert state["items"] == expected and state["cursor"] is None
    assert state["gui"] == "machine" and state["selected"] == 1
    assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9]
    assert result["cleanup"]["inventoryRestored"] is False and result["cleanup"]["selectedHotbarRestored"] is False
    assert result["cleanup"]["retainedInventoryVerified"] and result["cleanup"]["allConfirmedSupportsRemoved"]
    assert result["timings_ms"]["restoration"] == 0 and result["pendingInventoryRestoration"] == []
    retained = result["retainedInventoryMoves"]
    assert len(retained) == 1 and retained[0]["hotbarIndex"] == 1 and retained[0]["storageIndex"] == 10
    assert retained[0]["movementConfirmed"] and retained[0]["retentionConfirmed"]
    assert not retained[0].get("restorationAttempted")
    assert retained[0]["retentionObservation"]["sourceItem"] is None
    assert retained[0]["retentionObservation"]["destinationItem"] == original[1]
    controller = next(record for record in result["inventoryMoves"] if "originalIndex" in record)
    assert controller["restorationConfirmed"] and controller["originalIndex"] == 9
    recheck = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action"
        and call["args"]["action"] == "recheck_structure")
    ending = state["calls"][recheck + 1:]
    assert all(call["operation"] in READ_OPERATIONS for call in ending)
    assert sum(call["operation"] == "inventory.inspect" for call in ending) == 1
    assert result["formationVerified"] and result["completeRulesKnown"] is False
    assert {entry["role"] for entry in result["acceptance"]["registeredRoles"]} == set(PORT_ITEMS)


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_default_without_borrowing_still_proves_cursor_and_preserves_gui(native_construction, order):
    client, state = native_construction
    original = copy.deepcopy(state["items"])
    result = await invoke(client, declaration(constructionOrder=order))
    assert result["status"] == "completed" and state["items"] == original
    assert result["retainedInventoryMoves"] == result["pendingInventoryRestoration"] == []
    assert result["cleanup"]["retainedInventoryVerified"] and state["cursor"] is None
    assert state["gui"] == "machine" and state["selected"] == 4
    assert not result["cleanup"]["selectedHotbarRestored"] and result["timings_ms"]["restoration"] == 0


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("fault", ["count", "registry", "metadata", "tag", "location", "source", "cursor"])
async def test_default_retention_requires_actual_location_identity_count_and_empty_cursor(native_construction, order, fault):
    client, state = native_construction
    fill_hotbar_with_storage_controller(state)
    state["retained_stack_fault"] = fault
    result = await invoke(client, declaration(constructionOrder=order))
    assert result["status"] == "failed" and result["formationVerified"] is True
    assert result["reason"] == ("inventory_final_cursor_not_empty" if fault == "cursor" else "inventory_retained_identity_changed")
    assert result["failedPhase"] == "retained_inventory_acceptance"
    assert result["retainedInventoryMoves"] == [] and len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert pending["movementConfirmed"] and pending["retentionValidationAttempted"] and not pending["retentionConfirmed"]
    observed = pending["retentionObservation"]
    assert observed["sourceItem"] == state["items"][1] and observed["destinationItem"] == state["items"][10]
    assert observed["cursorItem"] == state["cursor"] and observed["cursorObserved"]
    assert not pending.get("restorationAttempted") and not result["cleanup"]["inventoryRestored"]
    assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9]
    assert state["gui"] == "machine" and state["selected"] == 1


@pytest.mark.parametrize("fault,reason", [("cursor_missing", "inventory_final_cursor_not_empty"), ("partial", "inventory_coverage_incomplete")])
async def test_default_retention_requires_complete_explicit_inventory_observation(native_construction, fault, reason):
    client, state = native_construction
    fill_hotbar_with_storage_controller(state)
    state["retained_observation_fault"] = fault
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == reason
    assert result["retainedInventoryMoves"] == [] and len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert not pending["retentionConfirmed"] and not pending.get("restorationAttempted")
    assert pending["retentionObservation"]["cursorObserved"] is (fault != "cursor_missing")
    assert pending["retentionObservation"]["inventoryTruncated"] is (fault == "partial")
    assert state["gui"] == "machine" and state["inventory_activations"] == [1, 10, 9, 1, 1, 9]


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_default_failure_keeps_confirmed_movement_pending_until_final_observation(native_construction, order):
    client, state = native_construction
    original = fill_hotbar_with_storage_controller(state)
    state["bad_role"] = "item_output"
    result = await invoke(client, declaration(constructionOrder=order))
    assert result["status"] == "failed" and result["reason"] == "registered_role_not_accepted"
    assert result["inventoryRestorationRequested"] is False and result["retainedInventoryMoves"] == []
    assert len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert pending["movementConfirmed"] and not pending.get("retentionConfirmed") and not pending.get("restorationAttempted")
    assert state["items"][10] == original[1] and state["items"][1] is None and state["items"][9] == original[9]
    assert state["gui"] == "machine" and state["selected"] == 1
    assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9]
    assert result["timings_ms"]["restoration"] == 0


async def test_default_failed_controller_retrieval_does_not_restore_confirmed_borrow(native_construction):
    client, state = native_construction
    fill_hotbar_with_storage_controller(state)
    state["change_controller_after_borrow"] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["failedPhase"] == "retrieve_controller"
    assert state["inventory_activations"] == [1, 10] and state["items"][9]["count"] == 2
    assert state["items"][1] is None and state["items"][10]["count"] == 64 and state["cursor"] is None
    assert state["gui"] == "creative" and state["selected"] == 7
    assert result["retainedInventoryMoves"] == [] and len(result["pendingInventoryRestoration"]) == 1
    assert result["pendingInventoryRestoration"][0]["movementConfirmed"]
    assert not result["pendingInventoryRestoration"][0].get("restorationAttempted")


async def test_uncertain_necessary_controller_return_is_pending_and_never_replayed(native_construction):
    client, state = native_construction
    fill_hotbar_with_storage_controller(state)
    state["disconnect_controller_return"] = True
    result = await invoke(client)
    assert result["status"] == "failed" and state["inventory_activations"] == [1, 10, 9, 1, 1]
    assert len(result["pendingInventoryRestoration"]) == 2 and result["retainedInventoryMoves"] == []
    controller = next(record for record in result["pendingInventoryRestoration"] if "originalIndex" in record)
    assert controller["movementConfirmed"] and controller["restorationAttempted"] and controller["outcomeUnknown"]
    assert not controller.get("restorationConfirmed") and state["cursor"] == stack(CONTROLLER_ITEM)
    assert not result["cleanup"]["inventoryRestored"] and not state["digs"]


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("restore", [False, True])
async def test_external_cancellation_only_runs_ending_restoration_when_requested(native_construction, order, restore):
    client, state = native_construction
    original = fill_hotbar_with_storage_controller(state)
    ready = asyncio.Event()
    captured = []
    async def cancel_point(tracked, args, local_lock):
        captured[:] = [tracked.task]
        report = await run_workflow(tracked, args, local_lock)
        if tracked.task.phase == "blueprint_hints":
            ready.set()
            await asyncio.Event().wait()
        return report
    lock = anyio.Lock()
    pending = asyncio.create_task(run_blueprint_build(client, declaration(constructionOrder=order, restoreInventory=restore), lock, runner=cancel_point))
    await asyncio.wait_for(ready.wait(), timeout=2)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    task = captured[0]
    assert not lock.locked() and task.restoration_started is restore
    assert task.inventory_restored is restore and task.selected_restored is restore
    if restore:
        assert state["items"] == original and state["selected"] == 7
        assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9, 10, 1]
        assert task.borrowed_inventory[0]["restorationConfirmed"]
    else:
        assert state["items"][1] is None and state["items"][10] == original[1] and state["selected"] == 0
        assert state["inventory_activations"] == [1, 10, 9, 1, 1, 9]
        assert task.borrowed_inventory[0]["movementConfirmed"] and not task.borrowed_inventory[0].get("restorationAttempted")
        assert not task.borrowed_inventory[0].get("retentionConfirmed")
    assert state["cursor"] is None and state["construct_count"] == 0


@pytest.mark.parametrize("restore", [False, True])
async def test_cancellation_preserves_uncertain_native_pickup_without_replay(native_construction, restore):
    client, state = native_construction
    fill_hotbar_with_storage_controller(state)
    state["pickup_dispatched"] = asyncio.Event()
    captured = []
    async def observe_task(tracked, args, local_lock):
        captured[:] = [tracked.task]
        return await run_workflow(tracked, args, local_lock)
    lock = anyio.Lock()
    pending = asyncio.create_task(run_blueprint_build(client, declaration(restoreInventory=restore), lock, runner=observe_task))
    await asyncio.wait_for(state["pickup_dispatched"].wait(), timeout=2)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    task = captured[0]
    record = task.borrowed_inventory[0]
    assert record["movementDispatched"] and record["outcomeUnknown"] and not record.get("movementConfirmed")
    assert not record.get("restorationAttempted") and not record.get("retentionConfirmed")
    assert state["inventory_activations"] == [1] and state["cursor"] == stack({"registryName": "minecraft:stone", "metadata": 1}, 64)
    assert state["gui"] == "creative" and state["selected"] == 7 and not lock.locked()
    assert task.restoration_started is restore and not task.inventory_restored


async def test_restore_inventory_rejects_nonboolean_before_contacting_client(native_construction):
    client, state = native_construction
    with pytest.raises(BridgeError) as caught:
        await invoke(client, declaration(restoreInventory="false"))
    assert caught.value.code == "INVALID_ARGUMENTS" and state["calls"] == []


def declare_muffler(state, monkeypatch, **changes):
    # A synthetic identity is supplied through the observed source declaration;
    # production never maps a numeric hatch ID or adapts a specific controller.
    item = {"registryName": "gregtech:gt.blockmachines", "metadata": 30003}
    monkeypatch.setitem(PORT_ITEMS, "muffler", item)
    monkeypatch.setitem(PORT_SLOTS, "muffler", 7)
    state["items"][7] = stack(item)
    return declaration(**changes)


def test_muffler_schema_uses_the_exact_public_hatch_description_key():
    assert ROLE_KEYS["muffler"] == "GT5U.MBTT.MufflerHatch"
    roles = BLUEPRINT_BUILD_SCHEMA["properties"]["requestedPorts"]["items"]["properties"]["role"]["enum"]
    assert "muffler" in roles and len(roles) == len(set(roles))


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_public_muffler_group_uses_native_placement_and_real_registered_acceptance(native_construction, monkeypatch, order):
    client, state = native_construction
    args = declare_muffler(state, monkeypatch, constructionOrder=order)
    result = await invoke(client, args)
    assert result["status"] == "completed", result
    assert result["formationVerified"] is True and result["completeRulesKnown"] is False
    muffler = next(port for port in result["selectedPorts"] if port["role"] == "muffler")
    assert muffler["descriptionKey"] == "GT5U.MBTT.MufflerHatch" and muffler["item"] == PORT_ITEMS["muffler"]
    assert muffler["placementConfirmed"] and muffler["nativePlaceBlockUsed"] is True
    actual = next(entry for entry in reversed(state["placements"]) if entry["position"] == key(muffler["position"]))
    assert actual["item"] == stack(PORT_ITEMS["muffler"])
    accepted = next(entry for entry in result["acceptance"]["registeredRoles"] if entry["role"] == "muffler")
    assert accepted == {"role": "muffler", "position": muffler["position"], "registered": True, "valid": True}
    assert state["rechecks"] == state["construct_count"] == 1
    assert state["gui"] == "machine" and result["inventoryRestorationRequested"] is False
    assert result["cleanup"]["allConfirmedSupportsRemoved"]
    if order == "ports_first":
        assert muffler["preservedAfterNativeShell"] and muffler["publicEmptyContents"]["inventoryAllEmpty"]


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
async def test_muffler_without_actual_public_hint_role_never_guesses_a_port_location(native_construction, monkeypatch, order):
    client, state = native_construction
    args = declare_muffler(state, monkeypatch, constructionOrder=order)
    state["missing_hint_role"] = "muffler"
    result = await invoke(client, args)
    assert result["status"] == "failed" and result["reason"] == "public_port_category_unavailable"
    assert not any(port["role"] == "muffler" for port in result["selectedPorts"])
    assert state["construct_count"] == state["rechecks"] == 0
    assert len(state["placements"]) == 2 and len(state["digs"]) == 1
    assert result["formationVerified"] is False


@pytest.mark.parametrize("slot,reason", [(0, "declaration_overlap"), (6, "declaration_overlap"),
    (3, "declaration_overlap"), (1, "controller_source_overlap")])
async def test_muffler_source_cannot_overlap_blueprint_support_other_port_or_controller(native_construction, monkeypatch, slot, reason):
    client, state = native_construction
    args = declare_muffler(state, monkeypatch)
    next(port for port in args["requestedPorts"] if port["role"] == "muffler")["hotbarSlot"] = slot
    result = await invoke(client, args)
    assert result["status"] == "unavailable" and result["reason"] == reason
    assert not writes(state)


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("fault", ["missing_registered_role", "bad_role", "wrong_position_role"])
async def test_muffler_needs_actual_matching_registered_role_and_position(native_construction, monkeypatch, order, fault):
    client, state = native_construction
    args = declare_muffler(state, monkeypatch, constructionOrder=order)
    state[fault] = "muffler"
    result = await invoke(client, args)
    assert state["formed"] is True and result["status"] == "failed"
    assert result["reason"] == "registered_role_not_accepted" and result["formationVerified"] is False
    muffler = next(port for port in result["selectedPorts"] if port["role"] == "muffler")
    assert muffler["placementConfirmed"] and key(muffler["position"]) in state["blocks"]
    assert state["rechecks"] == state["construct_count"] == 1
    assert state["gui"] == "machine" and result["inventoryRestorationRequested"] is False


def lathe_position_sync_task(client, state, order, *, x_already_matches=True, timeout_ms=5000, native=True):
    # Only controller identity/position, retained supports, and failedIndex=8
    # (move acknowledged, no place) are known from the real lathe686 report.
    # This starting player position and delayed snapshots are synthetic.
    controller = {"x": -108, "y": 4, "z": 145}
    item = {"registryName": "gregtech:gt.blockmachines", "metadata": 686}
    state["controller"] = controller
    state["items"][1] = stack(item)
    for y in (2, 3):
        state["blocks"][key({**controller, "y": y})] = {"registryName": "minecraft:stone", "metadata": 0}
    state["position"] = {"x": -107.5 if x_already_matches else -106.125, "y": 2, "z": 144.5}
    state["position_sync_order"] = order
    task = _ConstructionTask(client, declaration(controller=controller, controllerItem=item, timeout_ms=timeout_ms),
        run_workflow, started=time.monotonic())
    task.capabilities = [capability for capability in state["caps"] if native or capability != "player.place_block"]
    task.materials[1] = {"item": item, "count": 1}
    return task


@pytest.mark.parametrize("order", [("x", "y", "z"), ("z", "x", "y"), ("y", "z", "x")])
@pytest.mark.parametrize("x_already_matches", [True, False])
@pytest.mark.parametrize("native", [True, False])
async def test_lathe_controller_place_waits_for_all_axes_before_one_native_placement(native_construction, order, x_already_matches, native):
    client, state = native_construction
    task = lathe_position_sync_task(client, state, order, x_already_matches=x_already_matches, native=native)
    observed = await task.place(task.args["controller"], 1, "controller_place")
    assert observed["gregtech"]["metaTileId"] == 686
    assert state["position"] == state["last_teleport_destination"] and not state["position_frames"]
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1
    assert len(state["placements"]) == 1 and state["placements"][0]["position"] == (-108, 4, 145)
    assert len([call for call in state["calls"] if call["operation"] == "player.place_block"]) == int(native)
    assert not state["digs"] and task.phases[-1]["phase"] == "controller_place"
    assert task.phases[-1]["status"] == "completed"


@pytest.mark.parametrize("axis", ["y", "z"])
async def test_construction_sync_requires_xyz_in_the_same_final_snapshot(native_construction, axis):
    client, state = native_construction
    task = lathe_position_sync_task(client, state, ("y", "z", "x"), x_already_matches=False)
    state["position_sync_drift"] = axis
    with pytest.raises(BridgeError) as caught:
        await task.place(task.args["controller"], 1, "controller_place")
    assert caught.value.code == "ASSERTION_FAILED" and not state["placements"]
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1


async def test_construction_position_sync_timeout_does_not_replay_teleport_or_place(native_construction):
    client, state = native_construction
    task = lathe_position_sync_task(client, state, (), timeout_ms=1000)
    with pytest.raises(BridgeError) as caught:
        await task.place(task.args["controller"], 1, "controller_place")
    # The enclosing workflow and the wait share the remaining task deadline;
    # either bounded timeout may win, but the Y wait must be the failed step.
    assert caught.value.code in {"WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"} and not state["placements"]
    assert task.phases[-1]["failedIndex"] == 7
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1


@pytest.mark.parametrize("selected,initial_selected", [(6, 6), (7, 7), (None, 6), (True, 6), ("6", 6)])
@pytest.mark.parametrize("native", [True, False])
async def test_repeated_selection_requires_live_selected_slot_or_uses_native_hotbar(native_construction, selected, initial_selected, native):
    client, state = native_construction
    state["selected"] = initial_selected
    state["snapshot_selected_override"] = selected
    task = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    task.capabilities = [capability for capability in state["caps"] if native or capability != "player.place_block"]
    task.materials[6] = {"item": SUPPORT, "count": 64}
    point = {**CONTROLLER, "y": 2}
    observed = await task.place(point, 6, "support_place")
    assert observed["registryName"] == SUPPORT["registryName"] and len(state["placements"]) == 1
    native_calls = [call for call in state["calls"] if call["operation"] == "player.place_block"]
    assert len(native_calls) == int(native)
    if native:
        assert native_calls[0]["args"]["hotbarSlot"] == 6
    selections = [call for call in state["calls"] if call["operation"] == "player.hotbar"]
    assert len(selections) == (0 if native or type(selected) is int and selected == 6 else 1)
    assert state["selected"] == 6
    batch = next(call for call in state["calls"] if call["operation"] == "world.inspect_many")
    assert len(batch["args"]["positions"]) == 7 and point in batch["args"]["positions"]
    # The batched planning read still cannot replace the action-time target check.
    assert any(call["operation"] == "world.inspect" and key(call["args"]) == key(point) for call in state["calls"])
    assert state["items"][6] == stack(SUPPORT, 64) and state["cursor"] is None


async def test_noop_selection_stops_on_changed_live_slot_without_interaction_or_replay(native_construction):
    client, state = native_construction
    state["selected"], state["selection_after_ray"] = 6, 7
    task = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    task.capabilities = state["caps"]
    task.materials[6] = {"item": SUPPORT, "count": 64}
    with pytest.raises(BridgeError) as caught:
        await task.place({**CONTROLLER, "y": 2}, 6, "support_place")
    assert caught.value.code == "ASSERTION_FAILED"
    assert not state["placements"] and not state["digs"]
    assert not any(call["operation"] in ("player.hotbar", "player.interact", "player.place_block") for call in state["calls"])
    assert state["items"][6] == stack(SUPPORT, 64) and state["cursor"] is None


@pytest.mark.parametrize("fault", ["unloaded", "wrong_dimension", "wrong_coordinate"])
async def test_batched_placement_still_requires_each_authoritative_target_and_neighbor(native_construction, monkeypatch, fault):
    client, state = native_construction
    actual_call = client.call
    async def corrupted(operation, arguments, **options):
        data = await actual_call(operation, arguments, **options)
        if operation == "world.inspect_many":
            assert len(arguments["positions"]) == 7
            row = data["results"][-1]
            if fault == "unloaded":
                row["ok"] = False
            else:
                row["data"]["dimension" if fault == "wrong_dimension" else "x"] += 1
        return data
    monkeypatch.setattr(client, "call", corrupted)
    task = _ConstructionTask(client, declaration(), run_workflow, started=time.monotonic())
    task.materials[6] = {"item": SUPPORT, "count": 64}
    with pytest.raises(BridgeError) as caught:
        await task.place({**CONTROLLER, "y": 2}, 6, "support_place")
    assert caught.value.details["reason"] == "server_block_page_unavailable"
    assert not writes(state) and not state["placements"]


@pytest.mark.parametrize("x_already_matches", [True, False])
async def test_coordinate_waits_follow_changed_axes_and_finish_with_one_exact_xyz_snapshot(native_construction, x_already_matches):
    client, state = native_construction
    plans = []
    task = lathe_position_sync_task(client, state, ("z", "x", "y"), x_already_matches=x_already_matches)
    start = copy.deepcopy(state["position"])
    async def runner(tracked, arguments, lock):
        plans.append(arguments["steps"])
        return await run_workflow(tracked, arguments, lock)
    task.runner = runner
    await task.place(task.args["controller"], 1, "controller_place")
    destination = state["last_teleport_destination"]
    changed = sum(start[axis] != destination[axis] for axis in ("x", "y", "z"))
    waits = [step for step in plans[0] if step["kind"] == "wait" and step["tool"] == "mcb_snapshot"]
    assert len(waits) == changed and waits[-1]["id"] == "synced"
    final = [waits[-1]["condition"], *[step["condition"] for step in plans[0] if step["kind"] == "assert" and step["source"] == "synced"]]
    assert {entry["path"]: entry["value"] for entry in final} == {"player.x": destination["x"], "player.feetY": destination["y"], "player.z": destination["z"]}
    assert state["position"] == destination and len(state["placements"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1


async def test_initially_unchanged_coordinate_drift_cannot_pass_final_xyz(native_construction):
    client, state = native_construction
    task = lathe_position_sync_task(client, state, ("x", "y", "z"), x_already_matches=True)
    state["position_sync_drift"] = "x"
    with pytest.raises(BridgeError) as caught:
        await task.place(task.args["controller"], 1, "controller_place")
    assert caught.value.code == "ASSERTION_FAILED" and not state["placements"]
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1
