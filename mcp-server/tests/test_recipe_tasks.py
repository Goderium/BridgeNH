"""Recipe tasks must prove real deltas through the existing transport/native runner."""

import asyncio
import copy
import json
import math
import time
from pathlib import Path

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.recipe_tasks import (
    _RecipeTask, _Unavailable, inventory_slots, power_widget, registered_components, run_recipe_once,
    supported_recipe, unique_source,
)
from minecraft_codex_bridge.tools import TOOLS
from minecraft_codex_bridge.workflows import run_workflow


MACHINE = {"x": 10, "y": 19, "z": 20}
INPUT = {"x": 11, "y": 21, "z": 20}
OUTPUT = {"x": 12, "y": 21, "z": 20}
MATERIAL = {"registryName": "minecraft:planks", "metadata": 0}
PRODUCT = {"registryName": "minecraft:chest", "metadata": 0}
CATALYST = {"registryName": "gregtech:gt.integrated_circuit", "metadata": 8}
PROTECTED = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}


def stack(identity, count):
    return {**identity, "count": count, "hasTag": False}


def group(entries):
    return {"entries": entries, "total": len(entries), "truncated": False}


def recipe(*, circuit=True, wildcard=True):
    inputs = [{"index": 0, "stack": stack({**MATERIAL, "metadata": 32767 if wildcard else 0}, 8),
               "chance": 10000, "nonConsumedCatalyst": False}]
    if circuit:
        inputs.append({"index": 1, "stack": stack(CATALYST, 0), "chance": 10000, "nonConsumedCatalyst": True})
    return {"available": True, "enabled": True, "hidden": False, "fakeRecipe": False, "nbtSensitive": False,
            "softBudgetPartial": False, "metadataTruncated": False, "metadataUnknown": False,
            "publicMetadataComplete": True, "metadata": [], "specialValue": 0, "specialItems": None,
            "rawEUt": 4, "rawDurationTicks": 800, "chanceDenominator": 10000,
            "category": "gt.recipe.assembler", "categoryRecipeIndex": 3942,
            "items": {"inputs": group(inputs), "outputs": group([
                {"index": 0, "stack": stack(PRODUCT, 1), "chance": 10000}])},
            "fluids": {"inputs": group([]), "outputs": group([])}, "executionValidated": False}


def declaration(**changes):
    return {"expectedSessionId": "session", "expectedWorld": "test-world", "dimension": 180,
            "machine": MACHINE.copy(), "input": INPUT.copy(), "output": OUTPUT.copy(),
            "outputItem": PRODUCT.copy(), "material": MATERIAL.copy(), "selection": "first_supported",
            "inputGuiSlot": 1, "catalystGuiSlot": 0, "timeout_ms": 5000, "includeDetails": True,
            "inventoryPreparation": "preserve", **changes}


def component_data(*, working=False, modular=True):
    entries = [{"role": "item_input", "valid": True, "dimension": 180, **INPUT},
               {"role": "item_output", "valid": True, "dimension": 180, **OUTPUT}]
    roles = [{"role": "item_input", "count": 1}, {"role": "item_output", "count": 1},
             {"role": "dual_input", "count": 0}, {"role": "fluid_input", "count": 0}]
    missing = [{"role": "exotic_energy", "reason": "getter_unavailable"}]
    if modular:
        entries.append({"role": "modular_hatch", "valid": True, "dimension": 180, "x": 9, "y": 21, "z": 20,
                        "executionCore": {"working": working, "mainMachine": {"controllerIdentityMatches": True}}})
        roles.append({"role": "modular_hatch", "count": 1})
        info = {"available": True, "registeredCount": 1}
    else:
        info = {"available": False, "reason": "public_modular_interface_unavailable_or_not_implemented"}
        missing.append({"role": "modular_hatch", "reason": info["reason"]})
    for index, entry in enumerate(entries):
        entry["index"] = index
    return {"entries": entries, "roles": roles, "unavailableRoles": missing,
            "modularHatches": info, "total": len(entries), "hasMore": False, "offset": 0, "nextOffset": len(entries)}


def control(selected=0):
    return {"widgets": [{"widgetId": "widget:actual", "semantic": "power_switch", "kind": "choice",
                         "enabled": True, "valid": True, "selected": selected, "optionCount": 2,
                         "supportedActions": ["set_selected"], "overlay": {"current": {
                             "location": "gregtech:textures/gui/overlay_button/power_switch_" + ("on" if selected else "off") + ".png"}}}]}


def writes(state):
    return [call for call in state["calls"] if call["operation"] not in READ_OPERATIONS | {"__cancel__"}]


@pytest.fixture
async def native_bridge():
    state = {"calls": [], "caps": [spec.operation for spec in TOOLS if spec.operation and spec.operation != "inventory.supply"],
             "recipe": recipe(), "material": 32, "circuit": 0, "input_material": 0, "input_circuit": 1,
             "output": 4, "allowed": False, "active": False, "core": False, "core_reads": 0,
             "modular": True, "flying": True, "cursor": None, "open": None, "window": 0,
             "gui_reads": 0, "gui_delay": False, "gui_item_delay": False, "duplicate_source": False,
             "query_delay": 0, "queries": [], "power_error": None, "cancel_status": "already_running",
             "entered_power": asyncio.Event(), "bad_input_delta": False, "bad_catalyst": False,
             "position": {"x": 5.5, "y": 19, "z": 20.5}, "generated": 0, "controls": None,
             "player_items": None, "material_index": 9, "circuit_index": 10,
             "creative_tab": False, "creative_mapping": True, "creative_error": None,
             "creative_tab_widget": "creative:tab", "creative_window": 0,
             "component_preflight_calls": 0, "component_chunk": 128, "component_zero_reads": 0,
             "component_changes": False}
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "recipe-test-token"})

    def player_inventory():
        if state["player_items"] is None:
            items = {index: None for index in range(36)}
            if state.get("full_hotbar") or state.get("material_in_last_hotbar"):
                for index in range(9):
                    items[index] = stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
            if state.get("material_in_last_hotbar"):
                state["material_index"] = 4
            items[state["material_index"]] = stack(MATERIAL, state["material"]) if state["material"] else None
            items[state["circuit_index"]] = stack(CATALYST, state["circuit"]) if state["circuit"] else None
            if state["duplicate_source"]:
                items[35] = stack(MATERIAL, 1)
            if state.get("storage_full"):
                for index in range(9, 36):
                    if items[index] is None:
                        items[index] = stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
            state["player_items"] = items
        slots = [{"index": index, "item": state["player_items"][index]} for index in range(36)]
        return {"slots": slots, "totalSlots": len(slots), "truncated": False, "cursorItem": state["cursor"]}

    def port_inventory(output=False):
        if state.get("single_machine"):
            inventory = copy.deepcopy(state["single_world"]["inventory"])
            for slot in inventory["slots"]:
                slot["item"] = None
            inventory["slots"][5]["item"] = stack(MATERIAL, state["input_material"]) if state["input_material"] else None
            inventory["slots"][state.get("single_output_inventory_index", 6)]["item"] = stack(PRODUCT, state["output"]) if state["output"] else None
            inventory["slots"][state.get("single_catalyst_inventory_index", 3)]["item"] = stack(CATALYST, state["input_circuit"]) if state["input_circuit"] else None
            if state.get("configured_ghost_machine") and not state.get("missing_ghost_inventory"):
                ghost_index = state.get("ghost_inventory_after_index", 4) if state["output"] else 4
                inventory["slots"][ghost_index]["item"] = stack(CATALYST, state.get("ghost_inventory_count", 0))
                if state.get("duplicate_ghost_inventory"):
                    inventory["slots"][8]["item"] = stack(CATALYST, 0)
            if state.get("single_foreign_item"):
                inventory["slots"][10]["item"] = stack({"registryName": "minecraft:stone", "metadata": 0}, 1)
            if state.get("single_partial_inventory"):
                inventory["slots"] = inventory["slots"][:-1]
            return inventory
        slots = [{"index": index, "item": None} for index in range(4)]
        if output:
            slots[0]["item"] = stack(PRODUCT, state["output"]) if state["output"] else None
        else:
            slots[0]["item"] = stack(CATALYST, state["input_circuit"]) if state["input_circuit"] else None
            slots[1]["item"] = stack(MATERIAL, state["input_material"]) if state["input_material"] else None
        return {"slots": slots, "totalSlots": len(slots), "truncated": False}

    async def respond(request):
        body = json.loads(request.content)
        operation = "__cancel__" if request.url.path == "/v1/cancel" else body["operation"]
        state["calls"].append({**body, "operation": operation})
        args = body.get("args", {})
        if operation == "__cancel__":
            data = {"status": state["cancel_status"]}
        elif operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "profile": "developer", "capabilities": state["caps"],
                    "operationPolicy": {"available": True, "effective": {
                        "movementImplementation": state.get("movementImplementation", "walking")}},
                    "recipeQueryAccess": {"version": 1, "publicCatalog": True,
                        "localMachineBackend": True, "remoteMachineBackend": False},
                    "background": PROTECTED, "interactionSneaking": True, "interactionBlockTargeting": True,
                    "interactionViewBinding": True}
        elif operation == "client.snapshot":
            state["server_samples"] = state.get("server_samples", 0) + 1
            if state.get("position_frames"):
                state["position"] = state["position_frames"].pop(0)
            position = state["position"]
            data = {"sessionId": "session", "profile": "developer", "worldLoaded": True, "background": PROTECTED,
                    "serverControl": {"available": True, "source": "integrated_server_tick", "sessionId": "session",
                        "worldName": "test-world", "worldFolder": "test-world", "dimension": 180,
                        "sampleId": state["server_samples"], "ageMillis": 0, "playerId": "fixture-player-uuid",
                        "openContainer": {"isInventoryContainer": state["open"] is None, "windowId": state["window"]},
                        "clientContainer": {"isInventoryContainer": state["open"] is None, "windowId": state["window"]}},
                    "screenOpen": state["open"] is not None, "screenId": "screen:" + state["open"] if state["open"] is not None else "",
                    "world": {"worldName": "test-world", "worldFolder": "test-world", "dimension": 180},
                    "player": {"name": "TestPlayer", "health": state.get("health", 20), **position, "feetY": position["y"], "eyeY": position["y"] + 1.62,
                               "creative": state.get("creative", True), "flying": state["flying"], "serverFlight": {"available": True, "creative": state.get("creative", True), "flying": state["flying"],
                               "allowFlying": True, "dimension": 180, "ageMillis": 0}}}
        elif operation == "inventory.inspect":
            data = player_inventory()
            if state["output"] > 4:
                if state.get("final_inventory_error") == "disconnect":
                    raise httpx.ReadError("final actual inventory unavailable", request=request)
                if state.get("final_inventory_error") == "missing_cursor":
                    data.pop("cursorItem")
                elif state.get("final_inventory_error") == "partial":
                    data["slots"] = data["slots"][:-1]
                elif state.get("final_inventory_error") == "partial_with_matching_total":
                    data["slots"] = data["slots"][:-1]
                    data["totalSlots"] = len(data["slots"])
        elif operation == "registry.item":
            data = {**stack(args, 1), "stackLimit": state.get("grant_stack_limit", 64)}
        elif operation == "machine.inspect":
            if state["core_reads"]:
                state["core_reads"] -= 1
                state["core"] = state["core_reads"] > 0
            components = component_data(working=state["core"], modular=state["modular"])
            if state.get("changed_ports_after_feed") and state["input_material"]:
                components["entries"][0]["x"] += 1
            if "componentOffset" in args:
                state["component_preflight_calls"] += 1
                offset = args["componentOffset"]
                end = min(components["total"], offset + state["component_chunk"])
                if state["component_preflight_calls"] <= state["component_zero_reads"]:
                    end = offset
                components["entries"] = components["entries"][offset:end]
                components.update(offset=offset, nextOffset=end, hasMore=end < components["total"])
                if state["component_changes"] and state["component_preflight_calls"] > 1:
                    components["roles"][-1]["count"] += 1
            data = {"metaTileId": 10001, "metaClass": "example.Replacement" if state.get("changed_class_after_feed") and state["input_material"]
                    else "example.PublicMultiblock", **MACHINE, "dimension": 180,
                    "state": {"active": state["active"], "allowedToWork": state["allowed"]
                              or bool(state.get("enabled_after_feed") and state["input_material"])},
                    "processing": {"structureFormed": True}, "components": components}
            if state.get("single_machine"):
                data.update(metaTileId=state["single_meta_id"], metaClass=state["single_meta_class"],
                    state={"active": state["active"], "allowedToWork": state["allowed"],
                           "progressTicks": state.get("single_progress", 0), "maxProgressTicks": state.get("single_max_progress", 0),
                           "inputVoltage": state.get("single_input_voltage", 8192)},
                    processing={"signedCalculatedEUt": {"available": False, "error": "Fixed API unavailable"}},
                    components={"roles": [], "entries": [], "total": 0, "offset": 0, "nextOffset": 0, "hasMore": False})
                if state.get("changed_class_after_feed") and state["input_material"]:
                    data["metaClass"] = "example.Replacement"
                if state.get("single_missing_progress"):
                    data["state"].pop("progressTicks")
        elif operation == "recipes.query":
            state["queries"].append(args.copy())
            query_started = time.monotonic()
            await asyncio.sleep(state["query_delay"])
            state["query_elapsed_ms"] = int((time.monotonic() - query_started) * 1000)
            if state.get("single_map_unsupported"):
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": "UNSUPPORTED", "message": "Public registered RecipeMap unavailable for this dynamic backend."}})
            data = {"dimension": 180, "filterIncomplete": False, "recipes": [state["recipe"]], "hasMore": True,
                    "nextCursor": "one-use-unused", "partial": True, "complete": False}
            if state.get("query_complete"):
                data.update(hasMore=False, nextCursor=None, partial=False, complete=True)
            if state.get("single_machine"):
                map_name = "fixture.current.public.map"
                data.update(recipeMap={"name": map_name, "amperage": state.get("single_map_amperage", 1)},
                    machine={**MACHINE, "metaTileId": state["single_meta_id"], "metaClass": state["single_meta_class"],
                        "currentRecipeMap": map_name})
        elif operation == "world.inspect":
            if state.get("single_pending") and state["allowed"]:
                state["single_pending"] -= 1
                if state["single_pending"] == 0:
                    consumed = state["recipe"]["items"]["inputs"]["entries"][0]["stack"]["count"]
                    state["input_material"] -= 0 if state["bad_input_delta"] else consumed
                    state["output"] += state["recipe"]["items"]["outputs"]["entries"][0]["stack"]["count"]
                    state["input_circuit"] = 0 if state["bad_catalyst"] else state["input_circuit"]
                    state.update(active=False, single_progress=0, single_max_progress=0)
                    if state.get("single_foreign_after_execution"):
                        state["single_foreign_item"] = True
                    if state.get("cursor_after_execution"):
                        state["cursor"] = stack({"registryName": "minecraft:stone", "metadata": 0}, 1)
            data = {"inventory": port_inventory(args["x"] == OUTPUT["x"])}
            if state.get("single_machine"):
                data.update(gregtech=state["single_world"]["gregtech"], fluids=copy.deepcopy(state["single_world"]["fluids"]))
                if state.get("single_nonempty_tank"):
                    data["fluids"].update(totalTanks=1, tanks=[{"index": 0, "fluid": "water", "amount": 1000}])
                if state.get("single_missing_tanks"):
                    data.pop("fluids")
            if state.get("world_observation_hook"):
                state["world_observation_hook"](data, args)
        elif operation == "world.spatial":
            if args["mode"] == "positions":
                data = {"positions": [{**point, "loaded": True,
                        "occupiable": not state.get("all_body_blocked") and point["y"] not in state.get("blocked_feet_heights", [])
                        and point["y"] >= state.get("body_minimum_y", 0), "liquid": False,
                        "hazard": False, "entityCoveragePartial": False} for point in args["positions"]]}
            else:
                target = {axis: math.floor(args["to"][axis]) for axis in ("x", "y", "z")}
                origin = args["from"]
                side = 2 if origin["z"] < target["z"] else 3 if origin["z"] > target["z"] + 1 else 4 if origin["x"] < target["x"] else 5
                data = {"status": "observed", "hit": {"type": "block", "block": target, "side": side}}
        elif operation == "dev.teleport":
            assert args["requireFlying"] is True and state["flying"]
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
            state["open"] = None
            state["window"] = 0
            if state.get("move_after_close"):
                state["position"]["x"] += .125
            if state.get("lose_flight_after_close"):
                state["flying"] = False
            data = {"status": "submitted"}
        elif operation == "gui.open_inventory":
            state["open"], state["window"], state["creative_tab"] = "creative", state["creative_window"], False
            data = {"status": "submitted"}
        elif operation == "player.interact":
            assert args["sneaking"] is False and args["action"] == "use"
            dx, dz = {2: (0, -1), 3: (0, 1), 4: (-1, 0), 5: (1, 0)}[args["side"]]
            face = {"x": args["x"] + .5 + .499 * dx, "y": args["y"] + .5, "z": args["z"] + .5 + .499 * dz}
            eye = {**state["position"], "y": state["position"]["y"] + 1.62}
            assert sum((eye[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) <= 9
            state["open"] = "input" if args["x"] == INPUT["x"] else "machine"
            state["window"] += 1
            state["gui_reads"] = 0
            data = {"status": "submitted"}
        elif operation == "player.hotbar":
            assert player_inventory()["slots"][args["slot"]]["item"] is None
            data = {"status": "submitted"}
        elif operation == "gui.inspect":
            state["gui_reads"] += 1
            data = {"screenId": "screen:" + str(state["open"]), "windowId": state["window"]}
            if state["open"] == "creative":
                data["creativeInventory"] = {"available": True, "inventoryTabWidgetId": state["creative_tab_widget"],
                                            "playerSlotsAvailable": state["creative_tab"]}
            elif not state["gui_delay"] or state["gui_reads"] > 1:
                slots = [{"slot": index, "inventoryIndex": 300 + index,
                          "inventoryClass": "net.minecraft.inventory.InventoryBasic", "item": None} for index in range(100)]
                slots[0]["item"] = stack(CATALYST, state["input_circuit"]) if state["input_circuit"] else None
                slots[1]["item"] = stack(MATERIAL, state["input_material"]) if state["input_material"] else None
                if not state["gui_item_delay"] or state["gui_reads"] > 3:
                    slots[80]["item"] = stack(MATERIAL, state["material"]) if state["material"] else None
                    slots[81]["item"] = stack(CATALYST, state["circuit"]) if state["circuit"] else None
                data.update(slots=slots, totalSlots=len(slots), truncated=False)
                if state.get("single_machine"):
                    actual_inventory = port_inventory()["slots"]
                    actual_player = player_inventory()["slots"]
                    slots = copy.deepcopy(state["single_gui"]["slots"])
                    for slot in slots:
                        slot["item"] = (actual_inventory if slot["slot"] < state.get("single_gui_machine_slot_count", 7)
                                        else actual_player)[slot["inventoryIndex"]]["item"]
                    if state.get("single_invalid_gui_index"):
                        slots[2]["inventoryIndex"] = 999
                    if state.get("single_gui_mapping_changed") and state["gui_reads"] > 1:
                        slots[2]["inventoryIndex"] = 4
                    data.update(slots=slots, totalSlots=len(slots), truncated=False)
            if state.get("gui_observation_hook"):
                state["gui_observation_hook"](data)
        elif operation == "gui.widgets":
            if state["open"] == "creative":
                widgets = []
                for slot in player_inventory()["slots"]:
                    widget = {"widgetId": "creative:slot:" + str(slot["index"]), "kind": "creative_player_slot",
                              "inventorySource": "current_player_inventory", "valid": True, "enabled": True,
                              "supportedActions": ["activate"], "item": copy.deepcopy(slot["item"]),
                              "nativeWrapperIndex": 2000 + slot["index"]}
                    if state["creative_mapping"]:
                        widget.update(playerInventoryIndex=slot["index"], nativeContainerSlotId=300 + slot["index"],
                                      inventoryBinding="public_isHere_unique_actual_player_container_slot")
                    widgets.append(widget)
                data = {"screenId": "screen:creative", "widgets": widgets, "hasMore": False,
                        "scanTruncated": False, "partial": False, "byteLimited": False}
            else:
                data = state["controls"] or control(int(state["allowed"]))
                if state.get("configured_ghost_machine"):
                    ghost = copy.deepcopy(state["ghost_widget"])
                    if state["output"] and "ghost_after_configuration" in state:
                        ghost["circuitConfig"].update(value=state["ghost_after_configuration"],
                            noConfiguration=state["ghost_after_configuration"] == -1)
                    if "semantic" not in args:
                        data = {"widgets": [*copy.deepcopy(data["widgets"]), ghost], "hasMore": state.get("ghost_widgets_more", False),
                                "partial": state.get("ghost_widgets_partial", False)}
                        if state.get("duplicate_ghost_widget"):
                            data["widgets"].append(copy.deepcopy(ghost))
                        if state.get("missing_ghost_widget"):
                            data["widgets"] = []
                        if state["output"] and state.get("ghost_after_read_error"):
                            raise httpx.ReadError("public ghost getter disconnected after power off", request=request)
                data = {"screenId": "screen:" + str(state["open"]), "hasMore": False,
                    "partial": False, "scanTruncated": False, "byteLimited": False, **copy.deepcopy(data)}
        elif operation == "gui.slot_click":
            assert args["windowId"] == state["window"]
            destination = args["slot"]
            single_player = state.get("single_machine") and destination >= state.get("single_gui_machine_slot_count", 7)
            if destination in (80, 81) or single_player:
                field, identity = ("material", MATERIAL) if destination == 80 else ("circuit", CATALYST)
                if single_player:
                    actual_gui_index = next(slot["inventoryIndex"] for slot in state["single_gui"]["slots"] if slot["slot"] == destination)
                    field, identity = ("material", MATERIAL) if actual_gui_index == state["material_index"] else ("circuit", CATALYST)
                actual_index = state[field + "_index"]
                if state["cursor"] is None:
                    state["cursor"] = stack(identity, state[field])
                    state[field] = 0
                    state["player_items"][actual_index] = None
                else:
                    state[field] += state["cursor"]["count"]
                    state["player_items"][actual_index] = stack(identity, state[field])
                    state["cursor"] = None
            else:
                assert args["button"] in (0, 1) and state["cursor"] is not None
                field = "input_material" if destination == (state.get("single_input_gui_slot", 2) if state.get("single_machine") else 1) else "input_circuit"
                amount = state["cursor"]["count"] if args["button"] == 0 else 1
                state[field] += amount
                state["cursor"]["count"] -= amount
                if not state["cursor"]["count"]:
                    state["cursor"] = None
            data = {"status": "submitted"}
        elif operation == "gui.widget_action":
            if args["action"] in ("activate", "set_value") and state.get("configured_ghost_machine") \
                    and args["widgetId"] == state["ghost_widget"]["widgetId"]:
                assert state["cursor"] is None and state["allowed"] is False
                if not state.get("ghost_native_ignored"):
                    config = state["ghost_widget"]["circuitConfig"]
                    config.update(value=args["value"] if args["action"] == "set_value"
                        else 1 if config["value"] == -1 else config["value"] + 1, noConfiguration=False)
                    state["missing_ghost_inventory"] = False
                if state.get("ghost_native_uncertain"):
                    raise httpx.ReadError("native ghost click outcome uncertain", request=request)
            elif args["action"] == "activate":
                assert state["open"] == "creative" and args["screenId"] == "screen:creative"
                if args["widgetId"] == state["creative_tab_widget"]:
                    state["creative_tab"] = True
                    if state.get("change_creative_window_after_tab"):
                        state["window"] += 1
                else:
                    assert state["creative_tab"] and args["widgetId"].startswith("creative:slot:")
                    actual_index = int(args["widgetId"].split(":")[-1])
                    if state["cursor"] is None:
                        state["cursor"] = state["player_items"][actual_index]
                        assert state["cursor"] is not None
                        state["player_items"][actual_index] = None
                        if state["creative_error"] == "disconnect":
                            raise httpx.ReadError("native creative pickup outcome uncertain", request=request)
                    else:
                        assert state["player_items"][actual_index] is None
                        state["player_items"][actual_index] = state["cursor"]
                        state["cursor"] = None
            else:
                assert args["widgetId"] == state.get("single_power_widget_id", "widget:actual") and args["action"] == "set_selected"
                state["allowed"] = bool(args["selected"])
            if args.get("selected") == 1:
                state["entered_power"].set()
                if state["power_error"] == "cancel":
                    await asyncio.sleep(10)
                if state["power_error"] == "disconnect":
                    raise httpx.ReadError("mock disconnected after possible native dispatch", request=request)
                if state.get("single_machine"):
                    state.update(single_pending=2, active=True, single_progress=1,
                                 single_max_progress=state["recipe"]["rawDurationTicks"])
                elif state["input_material"] == 8 and state["input_circuit"] == 1:
                    state["input_material"] = 1 if state["bad_input_delta"] else 0
                    state["input_circuit"] = 0 if state["bad_catalyst"] else 1
                    state["output"] += 1
                    if state.get("corrupt_borrowed_after_execution"):
                        borrowed_slot = next(index for index in range(9, 36) if state["player_items"][index] is not None
                                             and state["player_items"][index]["registryName"] == "minecraft:stone")
                        state["player_items"][borrowed_slot]["count"] -= 1
                    if state.get("cursor_after_execution"):
                        state["cursor"] = stack({"registryName": "minecraft:stone", "metadata": 0}, 1)
                    state["core_reads"] = 4
                    state["core"] = True
            data = {"status": "submitted"}
        elif operation == "machine.set_working":
            assert args["expectedMetaTileId"] == state.get("single_meta_id", 10001) and args["expectedWorld"] == "test-world" and args["working"] is False
            state["allowed"] = False
            state["core"] = False
            state["core_reads"] = 0
            data = {"status": "submitted"}
        elif operation == "chat.send":
            words = args["message"].split()
            if words == ["/clear", "TestPlayer"]:
                state["player_items"] = {index: None for index in range(36)}
                state["material"] = state["circuit"] = 0
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"status": "submitted"}})
            assert words[0:2] == ["/give", "TestPlayer"]
            field = "material" if words[2] == MATERIAL["registryName"] else "circuit"
            identity = MATERIAL if field == "material" else CATALYST
            actual_slots = player_inventory()["slots"]
            source = next((slot for slot in actual_slots if slot["item"] is not None and all(slot["item"].get(key) == value for key, value in identity.items())), None)
            if source is None:
                source = next(slot for slot in actual_slots if slot["item"] is None)
            state[field + "_index"] = source["index"]
            state[field] += int(words[3])
            state["player_items"][source["index"]] = stack(identity, state[field])
            state["generated"] += 1
            if state.get("replace_gui_after_grant"):
                state["open"], state["window"] = None, 0
            data = {"status": "submitted"}
        else:
            raise AssertionError("Unexpected recipe-task operation " + operation)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": copy.deepcopy(data)})

    client = BridgeClient(config, timeout=2, transport=httpx.MockTransport(respond))
    try:
        yield client, state
    finally:
        await client.aclose()


async def invoke(client, arguments=None, **options):
    return await run_recipe_once(client, arguments or declaration(), anyio.Lock(), runner=run_workflow, **options)


def test_raw_public_candidate_uses_concrete_wildcard_and_zero_count_circuit():
    selected = supported_recipe(recipe(), declaration())
    assert selected.material == MATERIAL and selected.material_count == 8
    assert selected.catalyst == CATALYST and selected.output == PRODUCT
    with pytest.raises(_Unavailable) as error:
        args = declaration()
        del args["material"]
        supported_recipe(recipe(), args)
    assert error.value.details["reason"] == "wildcard_material_required"


@pytest.mark.parametrize("mutation,reason", [
    (lambda value: value.update(metadataUnknown=True), "recipe_conditions_unsupported"),
    (lambda value: value.update(metadata=[{"key": "unknown"}]), "recipe_conditions_unsupported"),
    (lambda value: value.update(specialValue=1), "recipe_conditions_unsupported"),
    (lambda value: value["items"]["outputs"]["entries"][0].update(chance=5000), "probability_recipe_unsupported"),
    (lambda value: value["fluids"].update(inputs=group([{"fluid": "water"}])), "fluid_recipe_unsupported"),
    (lambda value: value["items"]["inputs"]["entries"][1]["stack"].update(metadata=32767), "catalyst_type_unsupported"),
    (lambda value: value["items"]["inputs"]["entries"][1].update(nonConsumedCatalyst=False), "catalyst_semantics_unavailable"),
    (lambda value: value["items"]["inputs"]["entries"][0]["stack"].update(hasTag=True), "item_nbt_or_identity_unavailable"),
    (lambda value: value["items"]["inputs"]["entries"].__setitem__(0, None), "recipe_stack_coverage_incomplete"),
])
def test_unknown_or_complex_raw_conditions_never_become_native_execution(mutation, reason):
    value = recipe()
    mutation(value)
    with pytest.raises(_Unavailable) as error:
        supported_recipe(value, declaration())
    assert error.value.details["reason"] == reason


def test_gui_source_uses_identity_not_inventory_class_or_inventory_index():
    slots = [{"slot": 1, "inventoryIndex": 9, "inventoryClass": "InventoryBasic", "item": None},
             {"slot": 80, "inventoryIndex": 380, "inventoryClass": "InventoryBasic", "item": stack(MATERIAL, 32)}]
    assert unique_source(slots, MATERIAL, 8)["slot"] == 80
    slots.append({"slot": 81, "inventoryIndex": 381, "item": stack(MATERIAL, 1)})
    with pytest.raises(_Unavailable):
        unique_source(slots, MATERIAL, 8)


def test_missing_inventory_and_power_semantics_fail_with_typed_unavailability():
    with pytest.raises(_Unavailable):
        inventory_slots({"slots": [], "totalSlots": 0, "truncated": False}, "")
    value = control()
    value["widgets"][0]["overlay"]["current"] = None
    with pytest.raises(_Unavailable) as error:
        power_widget(value, False)
    assert error.value.details["reason"] == "power_semantics_unverified"


def test_public_component_coverage_preserves_unrelated_failures_and_marks_missing_modules_limited():
    entries, coverage, reason = registered_components(component_data(), stopped=True)
    assert len(entries) == 3 and coverage == "public_registered_modules" and reason is None
    _, coverage, reason = registered_components(component_data(modular=False), stopped=True)
    assert coverage == "limited" and reason == "public_modular_interface_unavailable_or_not_implemented"
    value = component_data()
    value["entries"][-1]["executionCore"]["working"] = {"error": "getter_failed"}
    with pytest.raises(_Unavailable) as error:
        registered_components(value)
    assert error.value.details["reason"] == "execution_core_state_unavailable"


async def test_native_task_accepts_first_complete_raw_candidate_on_partial_page_and_real_gui_deltas(native_bridge):
    client, state = native_bridge
    state["gui_delay"] = state["gui_item_delay"] = True
    state["query_delay"] = .025
    result = await invoke(client)
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputDelta"] == 1 and result["acceptance"]["consumed"] == 8
    assert result["acceptance"]["catalystRetained"] is True and state["input_circuit"] == 1
    assert state["material"] == 24 and state["output"] == 5 and state["input_material"] == 0
    assert not state["allowed"] and not state["core"]
    assert len(state["queries"]) == 1 and result["uniqueRecipeClaimed"] is False
    # Windows event-loop timers may wake within the clock resolution before the requested delay.
    # Compare against the observed request span so omitting query time still fails meaningfully.
    observed_query_ms = state["query_elapsed_ms"]
    assert observed_query_ms > 0
    assert result["taskElapsed_ms"] >= result["nativeWorkflowElapsed_ms"] + observed_query_ms
    assert result["timings_ms"]["recipeDecision"] >= observed_query_ms
    assert result["timings_ms"]["total"] == result["taskElapsed_ms"]
    assert sum(value for key, value in result["timings_ms"].items() if key != "total") <= result["taskElapsed_ms"]
    query_phase = next(phase for phase in result["phases"] if phase["phase"] == "recipe_query")
    assert query_phase["elapsed_ms"] >= observed_query_ms and query_phase["timingScope"] == "registered_recipe_decision"
    clicks = [call["args"]["slot"] for call in state["calls"] if call["operation"] == "gui.slot_click"]
    assert clicks == [80] + [1] * 8 + [80]
    powers = [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"]
    assert powers == [1, 0]
    assert not any(call["operation"].startswith("qa.") for call in state["calls"])
    assert result["generatedMaterials"] is False and state["generated"] == 0


async def test_exact_two_executions_each_feed_one_raw_recipe_and_preserve_catalyst(native_bridge):
    client, state = native_bridge
    result = await invoke(client, declaration(executions=2))
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputDelta"] == 2 and result["acceptance"]["consumed"] == 16
    assert state["output"] == 6 and state["material"] == 16 and state["input_circuit"] == 1
    assert [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"] == [1, 0, 1, 0]


@pytest.mark.parametrize("mode", ["flight", "capability", "materials", "ambiguous"])
async def test_preflight_refusals_do_not_generate_move_click_or_power(native_bridge, mode):
    client, state = native_bridge
    args = declaration(generateMaterials=True)
    if mode == "flight":
        state["flying"] = False
    elif mode == "capability":
        state["caps"].remove("gui.widget_action")
    elif mode == "materials":
        state["material"] = 0
        args["generateMaterials"] = False
    else:
        state["duplicate_source"] = True
    result = await invoke(client, args)
    assert result["status"] == "unavailable" and result["available"] is False, result
    assert writes(state) == []


async def test_generation_requires_declaration_and_reports_actual_grants(native_bridge):
    client, state = native_bridge
    state["material"] = state["input_circuit"] = 0
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    assert state["generated"] == 2 and result["generatedMaterials"] is True and result["materialGrantsDispatched"] == 2
    assert result["materialGenerationRequested"] is True and state["input_circuit"] == 1


async def test_requested_generation_with_existing_materials_reports_zero_actual_grants(native_bridge):
    client, state = native_bridge
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    assert result["materialGenerationRequested"] is True and result["generatedMaterials"] is False
    assert result["materialGrantsDispatched"] == 0


async def test_generated_material_fills_last_empty_hotbar_but_proven_input_gui_is_reused(native_bridge):
    client, state = native_bridge
    state["material"] = 0
    state["material_in_last_hotbar"] = True
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    assert result["materialGrantsDispatched"] == 1 and state["material"] == 0 and state["output"] == 5
    assert any(phase["phase"] == "reuse_input" for phase in result["phases"])
    grant_index = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "chat.send")
    pickup_index = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.slot_click")
    assert not any(call["operation"] in {"gui.close", "player.interact", "dev.teleport"}
                   for call in state["calls"][grant_index + 1:pickup_index])
    assert any(call["operation"] == "world.spatial" and call["args"]["mode"] == "raycast"
               for call in state["calls"][grant_index + 1:pickup_index])


async def test_replaced_input_gui_uses_native_hand_recovery_before_new_normal_open(native_bridge):
    client, state = native_bridge
    state["material"] = 0
    state["material_in_last_hotbar"] = state["replace_gui_after_grant"] = True
    result = await invoke(client, declaration(generateMaterials=True, restoreInventory=True))
    assert result["status"] == "completed", result
    assert sum(phase["phase"] == "reuse_input" for phase in result["phases"]) == 1  # Fresh pre-grant proof only.
    assert any(phase["phase"] == "hand_recovery" and phase["status"] == "completed" for phase in result["phases"])
    assert len([call for call in state["calls"] if call["operation"] == "player.interact" and call["args"]["x"] == INPUT["x"]]) == 2
    assert state["input_material"] == 0 and state["output"] == 5
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is True
    assert result["inventoryPreparation"]["singleFeedGrants"] is True
    assert len(result["inventoryPreparation"]["borrowed"]) == 1
    assert result["inventoryPreparation"]["borrowed"][0]["purpose"] == "hand_recovery"
    grant_index = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "chat.send")
    recovery_pickup = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action"
                           and call["args"]["widgetId"] == "creative:slot:0")
    second_open = [index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact"
                   and call["args"]["x"] == INPUT["x"]][1]
    assert grant_index < recovery_pickup < second_open


async def test_full_hotbar_is_prepared_via_actual_native_player_bindings_and_restored(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "completed", result
    preparation = result["inventoryPreparation"]
    assert preparation["requiredEmptyHotbarSlots"] == 1 and len(preparation["borrowed"]) == 1
    borrowed = preparation["borrowed"][0]
    assert borrowed["hotbarIndex"] == 0 and borrowed["storageIndex"] == 10
    assert borrowed["movementConfirmed"] is True and borrowed["restorationConfirmed"] is True
    assert preparation["allBorrowedStacksRestored"] is True and result["pendingInventoryRestoration"] == []
    assert result["inventoryRestorationRequested"] is True and result["retainedInventoryMoves"] == []
    assert preparation["finalInventoryVerified"] is True and preparation["finalCursorEmpty"] is True
    assert all(state["player_items"][index] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64) for index in range(9))
    assert state["player_items"][10] is None and state["material"] == 24
    native = [call["args"]["widgetId"] for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["action"] == "activate" and call["args"]["widgetId"].startswith("creative:slot:")]
    assert native == ["creative:slot:0", "creative:slot:10", "creative:slot:10", "creative:slot:0"]
    assert preparation["handPreparationElapsed_ms"] <= result["timings_ms"]["preparation"]
    assert preparation["handRestorationElapsed_ms"] <= result["timings_ms"]["executionAcceptance"]


@pytest.mark.parametrize("include_details", [False, True])
async def test_default_full_hotbar_retains_verified_native_moves_and_actual_recipe_state(native_bridge, include_details):
    client, state = native_bridge
    state["full_hotbar"] = True
    initial_position = state["position"].copy()
    result = await invoke(client, declaration(includeDetails=include_details))
    assert result["status"] == "completed" and result["detailsIncluded"] is include_details, result
    preparation = result["inventoryPreparation"]
    assert result["inventoryRestorationRequested"] is False and result["pendingInventoryRestoration"] == []
    assert preparation["allBorrowedStacksRestored"] is False and preparation["handRestorationElapsed_ms"] == 0
    assert preparation["finalInventoryVerified"] is True and preparation["finalCursorEmpty"] is True
    assert len(preparation["borrowed"]) == len(result["retainedInventoryMoves"]) == 1
    retained = result["retainedInventoryMoves"][0]
    assert retained == preparation["borrowed"][0]
    assert retained["hotbarIndex"] == 0 and retained["storageIndex"] == 10 and retained["status"] == "retained"
    assert retained["movementConfirmed"] is True and retained["retentionConfirmed"] is True
    assert retained.get("restorationConfirmed") is not True and retained.get("outcomeUnknown") is not True
    assert state["player_items"][0] is None
    assert state["player_items"][10] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    assert state["material"] == 24 and state["input_material"] == 0 and state["input_circuit"] == 1
    assert state["output"] == 5 and not state["allowed"] and not state["core"] and state["cursor"] is None
    assert state["position"] != initial_position
    assert result["acceptance"]["consumed"] == 8 and result["acceptance"]["outputDelta"] == 1
    assert result["acceptance"]["catalystRetained"] is result["acceptance"]["controllerStopped"] is True
    native = [call["args"]["widgetId"] for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["action"] == "activate" and call["args"]["widgetId"].startswith("creative:slot:")]
    assert native == ["creative:slot:0", "creative:slot:10"]
    assert sum(call["operation"] == "gui.open_inventory" for call in state["calls"]) == 1
    if include_details:
        assert any(phase["phase"] == "inventory_retention_verification" and phase["status"] == "completed"
                   for phase in result["phases"])
        assert not any(phase["phase"] == "hand_restoration" for phase in result["phases"])
    else:
        assert "phases" not in result


@pytest.mark.parametrize("include_details", [False, True])
async def test_default_unknown_native_pickup_retains_uncertainty_and_pending_without_replay(native_bridge, include_details):
    client, state = native_bridge
    state["full_hotbar"], state["creative_error"] = True, "disconnect"
    result = await invoke(client, declaration(includeDetails=include_details))
    assert result["status"] == "failed" and result["outcomeUnknown"] is True and result["rolledBack"] is False, result
    assert result["inventoryRestorationRequested"] is False and result["retainedInventoryMoves"] == []
    assert len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert pending["movementDispatched"] is True and pending["movementConfirmed"] is False
    assert pending["outcomeUnknown"] is True and pending["retentionConfirmed"] is False
    assert result["lastActionReceipt"]["operation"] == "gui.widget_action"
    assert result["lastActionReceipt"]["dispatched"] is result["lastActionReceipt"]["outcomeUnknown"] is True
    assert result["inventoryPreparation"]["finalInventoryVerified"] is False
    assert result["inventoryPreparation"]["finalCursorEmpty"] is False
    assert state["cursor"] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 1
    assert not any(call["operation"] in {"chat.send", "player.interact", "gui.slot_click"} for call in state["calls"])


@pytest.mark.parametrize("include_details", [False, True])
async def test_default_unknown_power_keeps_confirmed_moves_but_preserves_actual_inputs_and_stop(native_bridge, include_details):
    client, state = native_bridge
    state["full_hotbar"], state["power_error"] = True, "disconnect"
    result = await invoke(client, declaration(includeDetails=include_details))
    assert result["status"] == "failed" and result["failedPhase"] == "execute_1" and result["outcomeUnknown"] is True, result
    assert result["inventoryRestorationRequested"] is False and result["pendingInventoryRestoration"] == []
    assert len(result["retainedInventoryMoves"]) == 1 and result["retainedInventoryMoves"][0]["retentionConfirmed"] is True
    assert result["inventoryPreparation"]["finalInventoryVerified"] is result["inventoryPreparation"]["finalCursorEmpty"] is True
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is False
    failed = result["failedNativeWorkflows"]
    assert len(failed) == 1 and failed[0]["phase"] == "execute_1" and failed[0]["outcomeUnknown"] is True
    power_receipt = next(receipt for receipt in failed[0]["actionReceipts"] if receipt["id"] == "power_on")
    original = result["lastActionReceipt"]
    assert original["operation"] == "gui.widget_action" and original["outcomeUnknown"] is True
    assert original["requestId"] == power_receipt["requestId"]
    assert result["emergencyStop"]["status"] == "completed" and result["emergencyStop"]["serverStopConfirmed"] is True
    assert result["remainingInputs"]["available"] is True
    assert result["remainingInputs"]["slots"] == [{"index": 0, "item": stack(CATALYST, 1)}, {"index": 1, "item": stack(MATERIAL, 8)}]
    assert result["rolledBack"] is False and result["executionsCompleted"] == 0
    assert state["input_material"] == 8 and state["output"] == 4 and not state["allowed"]
    assert state["player_items"][0] is None and state["player_items"][10] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    assert sum(call["operation"] == "gui.widget_action" and call["args"].get("action") == "set_selected" for call in state["calls"]) == 1
    assert sum(call["operation"] == "gui.open_inventory" for call in state["calls"]) == 1


@pytest.mark.parametrize("include_details", [False, True])
async def test_default_changed_retained_stack_cannot_be_reported_verified_or_restored(native_bridge, include_details):
    client, state = native_bridge
    state["full_hotbar"] = state["corrupt_borrowed_after_execution"] = True
    result = await invoke(client, declaration(includeDetails=include_details))
    assert result["status"] == "failed" and result["reason"] == "retained_inventory_identity_changed", result
    assert result["recipeAcceptanceCompleted"] is True and result["acceptance"]["outputDelta"] == 1
    assert result["inventoryRestorationRequested"] is False and result["retainedInventoryMoves"] == []
    assert len(result["pendingInventoryRestoration"]) == 1
    assert result["pendingInventoryRestoration"][0]["retentionConfirmed"] is False
    assert result["inventoryPreparation"]["finalInventoryVerified"] is False
    assert result["inventoryPreparation"]["finalCursorEmpty"] is True
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is False and result["rolledBack"] is False
    assert state["player_items"][10]["count"] == 63 and state["player_items"][0] is None
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 2
    power_index = next(index for index, call in enumerate(state["calls"])
                       if call["operation"] == "gui.widget_action" and call["args"].get("selected") == 1)
    assert sum(call["operation"] == "inventory.inspect" for call in state["calls"][power_index + 1:]) == 1


@pytest.mark.parametrize("mode,reason,cursor_empty", [
    ("nonempty_cursor", "retained_inventory_cursor_not_empty", False),
    ("missing_cursor", "retained_inventory_cursor_not_empty", None),
    ("partial", "inventory_coverage_incomplete", None),
    ("partial_with_matching_total", "inventory_coverage_incomplete", None),
    ("disconnect", "BRIDGE_UNAVAILABLE", None),
])
async def test_default_final_inventory_requires_real_empty_cursor_and_complete_read(native_bridge, mode, reason, cursor_empty):
    client, state = native_bridge
    state["full_hotbar"] = True
    if mode == "nonempty_cursor":
        state["cursor_after_execution"] = True
    else:
        state["final_inventory_error"] = mode
    result = await invoke(client, declaration(includeDetails=False))
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert result["recipeAcceptanceCompleted"] is True and result["acceptance"]["consumed"] == 8
    assert result["failedPhase"] == "inventory_retention_verification"
    assert result["retainedInventoryMoves"] == [] and len(result["pendingInventoryRestoration"]) == 1
    assert result["pendingInventoryRestoration"][0]["retentionConfirmed"] is False
    assert result["inventoryPreparation"]["finalInventoryVerified"] is False
    assert result["inventoryPreparation"]["finalCursorEmpty"] is cursor_empty
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is False
    assert state["input_material"] == 0 and state["input_circuit"] == 1 and state["output"] == 5 and not state["allowed"]
    assert sum(call["operation"] == "gui.open_inventory" for call in state["calls"]) == 1
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 2


async def test_full_hotbar_generation_and_four_executions_leave_native_empty_hand_then_restore(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    state["material"] = 0
    result = await invoke(client, declaration(generateMaterials=True, executions=4, restoreInventory=True))
    assert result["status"] == "completed", result
    assert result["acceptance"]["outputDelta"] == 4 and result["executionsCompleted"] == 4
    assert state["material"] == 0 and state["output"] == 8 and state["generated"] == 1
    assert result["inventoryPreparation"]["requiredEmptyHotbarSlots"] == 2
    assert len(result["inventoryPreparation"]["borrowed"]) == 2
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is True and result["pendingInventoryRestoration"] == []
    assert all(state["player_items"][index] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64) for index in range(9))
    power = [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"
             and call["args"]["action"] == "set_selected"]
    assert power == [1, 0] * 4


async def test_two_missing_material_kinds_without_public_capacity_proof_reserve_three_empty_slots(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    state["material"] = state["input_circuit"] = 0
    state["caps"].remove("registry.item")
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    assert state["generated"] == 2 and state["input_circuit"] == 1
    assert result["inventoryPreparation"]["requiredEmptyHotbarSlots"] == 3
    assert len(result["inventoryPreparation"]["borrowed"]) == 3
    assert result["pendingInventoryRestoration"] == []


@pytest.mark.parametrize("unavailable", ["mapping", "space", "capability"])
async def test_no_native_player_move_when_actual_binding_space_or_capability_is_unavailable(native_bridge, unavailable):
    client, state = native_bridge
    state["full_hotbar"] = True
    if unavailable == "mapping":
        state["creative_mapping"] = False
    elif unavailable == "space":
        state["storage_full"] = True
    else:
        state["caps"].remove("gui.open_inventory")
    result = await invoke(client)
    assert result["available"] is False, result
    assert result["reason"] in {"creative_player_slot_mapping_unavailable", "native_hand_preparation_space_unavailable", "creative_inventory_capability_unavailable"}
    assert not any(call["operation"] == "gui.widget_action" and call["args"]["widgetId"].startswith("creative:slot:") for call in state["calls"])
    assert not any(call["operation"] in {"gui.slot_click", "chat.send", "player.interact"} for call in state["calls"])
    assert result["pendingInventoryRestoration"] == []


async def test_unknown_native_hand_pickup_is_not_replayed_and_reports_pending_inventory_restoration(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    state["creative_error"] = "disconnect"
    result = await invoke(client)
    assert result["status"] == "failed" and result["rolledBack"] is False, result
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 1 and native[0]["args"]["widgetId"] == "creative:slot:0"
    assert len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert pending["movementDispatched"] is True and pending["movementConfirmed"] is False and pending["outcomeUnknown"] is True
    assert state["cursor"] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
    assert not any(call["operation"] in {"chat.send", "player.interact", "gui.slot_click"} for call in state["calls"])


async def test_changed_borrowed_stack_after_recipe_acceptance_is_not_overwritten_or_claimed_restored(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = state["corrupt_borrowed_after_execution"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["reason"] == "inventory_restoration_identity_changed", result
    assert result["recipeAcceptanceCompleted"] is True and result["acceptance"]["outputDelta"] == 1
    assert result["inventoryPreparation"]["allBorrowedStacksRestored"] is False
    assert len(result["pendingInventoryRestoration"]) == 1 and result["rolledBack"] is False
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
              and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 2  # Only the confirmed preparation, no unsafe restoration pickup.


async def test_component_cold_start_and_public_pages_are_collected_before_any_native_action(native_bridge):
    client, state = native_bridge
    state["component_zero_reads"], state["component_chunk"] = 1, 1
    result = await invoke(client)
    assert result["status"] == "completed", result
    reads = [call for call in state["calls"] if call["operation"] == "machine.inspect" and "componentOffset" in call["args"]]
    assert [call["args"]["componentOffset"] for call in reads] == [0, 0, 1, 2, 0, 1, 2]
    collected = next(phase for phase in result["phases"] if phase["phase"] == "component_collection")
    assert collected["pages"] == 4 and collected["coldStartWaits"] == 1 and collected["collectedEntries"] == 3
    assert collected["status"] == "completed"
    collection_indices = [index for index, call in enumerate(state["calls"]) if call["operation"] == "machine.inspect" and "componentOffset" in call["args"]]
    first_write = next(index for index, call in enumerate(state["calls"]) if call["operation"] not in READ_OPERATIONS)
    first_power = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action" and call["args"].get("selected") == 1)
    assert collection_indices[3] < first_write < collection_indices[4] < collection_indices[-1] < first_power


@pytest.mark.parametrize("failure,reason", [("changed", "component_pagination_changed"), ("stalled", "component_cold_start_budget")])
async def test_unstable_or_repeated_zero_progress_component_pages_never_look_empty(native_bridge, failure, reason):
    client, state = native_bridge
    if failure == "changed":
        state["component_chunk"], state["component_changes"] = 1, True
    else:
        state["component_zero_reads"] = 10
    result = await invoke(client)
    assert result["status"] == "unavailable" and result["reason"] == reason, result
    assert writes(state) == []
    assert state["component_preflight_calls"] == (2 if failure == "changed" else 3)
    assert result["available"] is False


async def test_missing_public_modules_complete_with_explicit_limited_stop_coverage(native_bridge):
    client, state = native_bridge
    state["modular"] = False
    result = await invoke(client)
    assert result["status"] == "completed", result
    assert result["acceptance"]["registeredCoreStopCoverage"] == "limited"
    assert result["acceptance"]["allRegisteredCoresStopClaimed"] is False


@pytest.mark.parametrize("failure", ["bad_input_delta", "bad_catalyst"])
async def test_output_without_matching_consumption_or_catalyst_retention_fails_acceptance_and_stops(native_bridge, failure):
    client, state = native_bridge
    state[failure] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == "input_or_catalyst_acceptance_failed", result
    assert result["failedPhase"] == "execute_1"
    assert any(phase.get("serverStopConfirmed") is True for phase in result["phases"])
    assert sum(call["operation"] == "machine.set_working" for call in state["calls"]) == 1


async def test_uncertain_native_power_is_not_retried_and_guarded_stop_preserves_original_phase(native_bridge):
    client, state = native_bridge
    state["power_error"] = "disconnect"
    result = await invoke(client)
    assert result["status"] == "failed" and result["failedPhase"] == "execute_1", result
    power = [call for call in state["calls"] if call["operation"] == "gui.widget_action"]
    assert len(power) == 1 and power[0]["args"]["selected"] == 1
    assert any(phase.get("outcomeUnknown") is True for phase in result["phases"])
    assert not state["allowed"] and any(phase.get("serverStopConfirmed") is True for phase in result["phases"])


async def test_external_cancellation_after_power_dispatch_still_runs_identity_guarded_stop(native_bridge):
    client, state = native_bridge
    state["power_error"] = "cancel"
    pending = asyncio.create_task(invoke(client))
    await asyncio.wait_for(state["entered_power"].wait(), timeout=3)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert len([call for call in state["calls"] if call["operation"] == "gui.widget_action"]) == 1
    stops = [call for call in state["calls"] if call["operation"] == "machine.set_working"]
    assert len(stops) == 1 and stops[0]["args"]["expectedMetaTileId"] == 10001
    assert not state["allowed"]


async def test_query_reader_is_injected_without_importing_server_and_receives_original_page_args(native_bridge):
    from minecraft_codex_bridge.server import recipe_pages

    client, state = native_bridge
    pages = []

    async def reader(guarded_client, args):
        pages.append(args.copy())
        return await recipe_pages(guarded_client, args)

    result = await invoke(client, query_reader=reader)
    assert result["status"] == "completed", result
    assert len(pages) == 1 and pages[0]["output"]["registryName"] == PRODUCT["registryName"]


async def test_exact_finite_source_stack_uses_one_native_whole_stack_deposit(native_bridge):
    client, state = native_bridge
    state["material"] = 8
    result = await invoke(client)
    assert result["status"] == "completed", result
    clicks = [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"]
    assert [(click["slot"], click["button"]) for click in clicks] == [(80, 0), (1, 0)]
    assert state["material"] == state["input_material"] == 0 and state["output"] == 5
    assert result["acceptance"]["consumed"] == 8 and state["cursor"] is None


async def test_current_clear_nearby_visible_position_avoids_redundant_teleports(native_bridge):
    client, state = native_bridge
    state["position"] = {"x": INPUT["x"] + .5, "y": INPUT["y"] - 1, "z": INPUT["z"] - 1.5}
    result = await invoke(client)
    assert result["status"] == "completed", result
    assert not any(call["operation"] == "dev.teleport" for call in state["calls"])
    opens = [phase for phase in result["phases"] if phase["phase"] in ("open_input", "open_machine")]
    assert len(opens) == 2 and all(phase["teleportDispatched"] is False for phase in opens)
    assert not any(phase["phase"].startswith("approach_") for phase in result["phases"])
    assert [call["args"]["x"] for call in state["calls"] if call["operation"] == "player.interact"] == [INPUT["x"], MACHINE["x"]]


async def test_vertical_port_and_controller_separation_still_requires_real_movement(native_bridge, monkeypatch):
    client, state = native_bridge
    monkeypatch.setitem(INPUT, "y", 27)
    state["position"] = {"x": INPUT["x"] + .5, "y": INPUT["y"] - 1, "z": INPUT["z"] - 1.5}
    result = await invoke(client)
    assert result["status"] == "completed", result
    moves = [call["args"] for call in state["calls"] if call["operation"] == "dev.teleport"]
    assert len(moves) == 1 and moves[0]["y"] == MACHINE["y"] - 1 and moves[0]["requireFlying"] is True
    opens = {phase["phase"]: phase for phase in result["phases"] if phase["phase"] in ("open_input", "open_machine")}
    assert opens["open_input"]["teleportDispatched"] is False and opens["open_machine"]["teleportDispatched"] is True


@pytest.mark.parametrize("change", ["move_after_close", "lose_flight_after_close"])
async def test_nearby_position_shortcut_still_blocks_fresh_movement_or_flight_drift(native_bridge, change):
    client, state = native_bridge
    state["position"] = {"x": INPUT["x"] + .5, "y": INPUT["y"] - 1, "z": INPUT["z"] - 1.5}
    state[change] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == "ASSERTION_FAILED", result
    assert result["failedPhase"] == "open_input"
    assert not any(call["operation"] in {"player.interact", "gui.slot_click", "dev.teleport"} for call in state["calls"])


async def test_creative_open_tab_binding_and_final_close_share_native_workflows(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = True
    state["creative_tab_widget"], state["creative_window"] = "creative:tab:observed", 7
    observed_workflows = []

    async def audited_runner(guarded_client, arguments, lock):
        call_start = len(state["calls"])
        report = await run_workflow(guarded_client, arguments, lock)
        observed_workflows.append((copy.deepcopy(arguments), report, state["calls"][call_start:]))
        return report

    result = await run_recipe_once(client, declaration(restoreInventory=True), anyio.Lock(), runner=audited_runner)
    assert result["status"] == "completed", result
    phases = [phase["phase"] for phase in result["phases"]]
    assert "hand_preparation_open" in phases and "hand_restoration_open" in phases
    assert not any(phase.endswith("_tab") or phase in {"hand_preparation_close", "hand_restoration_close"} for phase in phases)
    tabs = [call["args"] for call in state["calls"] if call["operation"] == "gui.widget_action"
            and call["args"]["widgetId"] == state["creative_tab_widget"]]
    assert len(tabs) == 2 and all(tab["screenId"] == "screen:creative" and tab["action"] == "activate" for tab in tabs)
    moves = [(plan, report, calls) for plan, report, calls in observed_workflows
             if any(step["id"] == "deposit" for step in plan["steps"])]
    assert len(moves) == 2
    for (plan, report, calls), (source_index, destination_index) in zip(moves, [(0, 10), (10, 0)]):
        assert [step["id"] for step in plan["steps"]][-5:] == ["deposit", "placed", "destination_exact", "source_still_empty", "close"]
        completed = {step["id"]: step for step in report["steps"]}
        assert all(completed[identifier]["status"] == "completed" for identifier in ("deposit", "placed", "destination_exact", "source_still_empty", "close"))
        placed = completed["placed"]["observation"]["values"]
        assert placed["cursorItem"] is None
        actual = inventory_slots(placed, "")
        assert actual[source_index]["item"] is None
        assert actual[destination_index]["item"] == stack({"registryName": "minecraft:stone", "metadata": 0}, 64)
        deposit = next(index for index, call in enumerate(calls) if call["requestId"] == completed["deposit"]["requestId"])
        close = next(index for index, call in enumerate(calls) if call["requestId"] == completed["close"]["requestId"])
        assert calls[deposit]["operation"] == "gui.widget_action" and calls[deposit]["args"]["action"] == "activate"
        assert calls[deposit]["args"]["widgetId"] == "creative:slot:" + str(destination_index)
        assert calls[close]["operation"] == "gui.close" and close > deposit
        # Fresh world/session guards may surround the actual inventory read and native close.
        # No other read or mutation may replace the required deposit/inventory/close sequence.
        between = [call["operation"] for call in calls[deposit + 1:close]]
        assert "client.snapshot" in between
        assert [operation for operation in between if operation != "client.snapshot"] == ["inventory.inspect"]
    assert result["pendingInventoryRestoration"] == []


async def test_combined_creative_workflow_rejects_changed_actual_window_before_player_move(native_bridge):
    client, state = native_bridge
    state["full_hotbar"] = state["change_creative_window_after_tab"] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == "ASSERTION_FAILED", result
    assert result["failedPhase"] == "hand_preparation_open"
    assert not any(call["operation"] == "gui.widget_action" and call["args"]["widgetId"].startswith("creative:slot:") for call in state["calls"])


async def test_default_deferred_unsupported_power_reports_actual_finite_remaining_inputs(native_bridge):
    client, state = native_bridge
    state["controls"] = {"widgets": []}
    state["full_hotbar"] = True
    result = await invoke(client, declaration(restoreInventory=True))
    assert result["status"] == "failed" and result["reason"] == "power_widget_ambiguous", result
    assert result["failedPhase"] == "power_controls_1" and result["executionsCompleted"] == 0
    remaining = result["remainingInputs"]
    assert remaining["available"] is True and remaining["totalSlots"] == 4
    assert remaining["slots"] == [{"index": 0, "item": stack(CATALYST, 1)}, {"index": 1, "item": stack(MATERIAL, 8)}]
    assert state["material"] == 24 and state["input_material"] == 8 and state["output"] == 4 and not state["allowed"]
    assert not any(call["operation"] == "gui.widget_action" and call["args"]["action"] == "set_selected" for call in state["calls"])
    assert len(result["pendingInventoryRestoration"]) == 1 and result["rolledBack"] is False


async def test_requested_power_preflight_rejects_unsupported_widget_before_finite_feed(native_bridge):
    client, state = native_bridge
    state["controls"] = {"widgets": []}
    result = await invoke(client, declaration(preflightControls=True))
    assert result["status"] == "failed" and result["reason"] == "power_widget_ambiguous", result
    assert result["failedPhase"] == "preflight_controls" and "remainingInputs" not in result
    assert not any(call["operation"] in {"gui.slot_click", "chat.send"} for call in state["calls"])
    assert state["material"] == 32 and state["input_material"] == 0 and state["output"] == 4


@pytest.mark.parametrize("change,reason", [("changed_class_after_feed", "component_controller_identity_changed"),
    ("changed_ports_after_feed", "isolated_port_binding_unavailable"), ("enabled_after_feed", "formed_stopped_machine_required")])
async def test_fresh_controller_identity_state_and_port_binding_are_rechecked_after_feed_before_power(native_bridge, change, reason):
    client, state = native_bridge
    state[change] = True
    result = await invoke(client)
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert result["failedPhase"] == "power_controls_1"
    assert result["remainingInputs"]["available"] is True and state["input_material"] == 8
    assert state["output"] == 4 and not any(call["operation"] == "gui.widget_action" for call in state["calls"])


async def test_single_exact_grant_with_existing_empty_hand_avoids_borrow_and_restore(native_bridge):
    client, state = native_bridge
    state["material"], state["material_in_last_hotbar"] = 0, True
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    preparation = result["inventoryPreparation"]
    assert preparation["singleFeedGrants"] is True and preparation["requiredEmptyHotbarSlots"] == 1
    assert preparation["borrowed"] == [] and result["pendingInventoryRestoration"] == []
    proof = preparation["grantCapacityProof"]
    assert proof["singleExecution"] is True and proof["noPlayerRemainder"] is True
    assert proof["actualEmptyStorageSlots"] >= proof["newSourceStacks"] == 1
    assert proof["publicStackLimits"] == [{**MATERIAL, "stackLimit": 64, "finalPlayerCount": 8}]
    assert state["player_items"][4] is None and state["generated"] == 1 and state["output"] == 5
    assert not any(call["operation"] == "gui.open_inventory" for call in state["calls"])
    assert not any(phase["phase"] == "hand_restoration" for phase in result["phases"])
    assert [call["args"]["slot"] for call in state["calls"] if call["operation"] == "player.hotbar"] == [4, 4]


async def test_two_exact_grants_use_real_storage_capacity_and_preserve_input_gui_between_feeds(native_bridge):
    client, state = native_bridge
    state["material"] = state["input_circuit"] = 0
    state["material_in_last_hotbar"] = True
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "completed", result
    preparation = result["inventoryPreparation"]
    assert preparation["singleFeedGrants"] is True and preparation["requiredEmptyHotbarSlots"] == 1 and preparation["borrowed"] == []
    assert preparation["grantCapacityProof"]["newSourceStacks"] == 2
    assert state["generated"] == 2 and state["input_circuit"] == 1 and state["output"] == 5
    # Both actual sources are consumed from the player, although only material occupied hotbar 4.
    assert state["material_index"] == 4 and state["circuit_index"] == 9
    assert state["player_items"][4] is state["player_items"][9] is None
    opens = [call for call in state["calls"] if call["operation"] == "player.interact" and call["args"]["x"] == INPUT["x"]]
    assert len(opens) == 1
    first_click = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.slot_click")
    material_pickup = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.slot_click" and call["args"]["slot"] == 80)
    assert not any(call["operation"] in {"gui.close", "player.interact", "dev.teleport"} for call in state["calls"][first_click + 1:material_pickup])
    assert not any(call["operation"] == "gui.open_inventory" for call in state["calls"])


async def test_insufficient_real_normal_inventory_capacity_prevents_all_grants(native_bridge):
    client, state = native_bridge
    state["material"] = state["input_circuit"] = 0
    state["material_in_last_hotbar"] = state["storage_full"] = True
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "unavailable" and result["reason"] == "material_generation_capacity_unavailable", result
    assert writes(state) == [] and state["generated"] == 0
    assert not any(call["operation"] == "chat.send" for call in state["calls"])


async def test_multiple_executions_keep_original_empty_hand_reserve_despite_capacity(native_bridge):
    client, state = native_bridge
    state["material"], state["material_in_last_hotbar"] = 0, True
    result = await invoke(client, declaration(generateMaterials=True, executions=2, restoreInventory=True))
    assert result["status"] == "completed", result
    preparation = result["inventoryPreparation"]
    assert preparation["singleFeedGrants"] is False and preparation["requiredEmptyHotbarSlots"] == 2
    assert len(preparation["borrowed"]) == 1 and preparation["allBorrowedStacksRestored"] is True
    assert state["generated"] == 1 and state["output"] == 6 and state["material"] == 0


async def test_single_execution_with_real_player_remainder_keeps_original_hand_reserve(native_bridge):
    client, state = native_bridge
    state["input_circuit"] = 0
    state["material_in_last_hotbar"] = True
    result = await invoke(client, declaration(generateMaterials=True, restoreInventory=True))
    assert result["status"] == "completed", result
    preparation = result["inventoryPreparation"]
    assert preparation["singleFeedGrants"] is False and preparation["requiredEmptyHotbarSlots"] == 2
    assert len(preparation["borrowed"]) == 2 and preparation["allBorrowedStacksRestored"] is True
    assert state["material"] == 24 and state["generated"] == 1 and state["output"] == 5


async def test_unknown_native_recovery_move_after_grant_is_not_replayed_and_stays_pending(native_bridge):
    client, state = native_bridge
    state["material"], state["material_in_last_hotbar"] = 0, True
    state["replace_gui_after_grant"], state["creative_error"] = True, "disconnect"
    result = await invoke(client, declaration(generateMaterials=True))
    assert result["status"] == "failed" and result["generatedMaterials"] is True, result
    assert result["failedPhase"] == "hand_recovery_store_0" and result["rolledBack"] is False
    assert len(result["pendingInventoryRestoration"]) == 1
    pending = result["pendingInventoryRestoration"][0]
    assert pending["purpose"] == "hand_recovery" and pending["movementDispatched"] is True and pending["restorationConfirmed"] is False
    native = [call for call in state["calls"] if call["operation"] == "gui.widget_action" and call["args"]["widgetId"].startswith("creative:slot:")]
    assert len(native) == 1
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
    assert state["input_material"] == 0 and state["output"] == 4


async def test_low_input_uses_target_height_above_solid_ground_and_retains_normal_reach(native_bridge, monkeypatch):
    client, state = native_bridge
    monkeypatch.setitem(MACHINE, "y", 3)
    monkeypatch.setitem(INPUT, "y", 2)
    monkeypatch.setitem(OUTPUT, "y", 2)
    state["body_minimum_y"] = 2  # Grass/solid ground at feetY=1 cannot contain the player body.
    result = await invoke(client)
    assert result["status"] == "completed", result
    body_reads = [call["args"] for call in state["calls"] if call["operation"] == "world.spatial" and call["args"]["mode"] == "positions"]
    first = body_reads[0]
    assert first["limit"] == len(first["positions"]) == 12
    assert [point["y"] for point in first["positions"]] == [1] * 4 + [2] * 4 + [3] * 4
    moves = [call["args"] for call in state["calls"] if call["operation"] == "dev.teleport"]
    assert len(moves) == 1 and moves[0]["y"] == 2 and moves[0]["requireFlying"] is True
    assert state["input_material"] == 0 and state["output"] == 5
    assert [call["args"]["y"] for call in state["calls"] if call["operation"] == "player.interact"] == [2, 3]
    for call in state["calls"]:
        if call["operation"] == "world.spatial" and call["args"]["mode"] == "raycast":
            ray = call["args"]
            assert sum((ray["from"][axis] - ray["to"][axis]) ** 2 for axis in ("x", "y", "z")) <= 9


@pytest.mark.parametrize("blocked_offsets,selected_offset", [([], -1), ([-1], 0), ([-1, 0], 1)])
async def test_clear_target_or_upper_height_is_selected_from_one_public_body_read(native_bridge, blocked_offsets, selected_offset):
    client, state = native_bridge
    state["blocked_feet_heights"] = [INPUT["y"] + offset for offset in blocked_offsets]
    result = await invoke(client)
    assert result["status"] == "completed", result
    first_move = next(call["args"] for call in state["calls"] if call["operation"] == "dev.teleport")
    assert first_move["y"] == INPUT["y"] + selected_offset and first_move["requireFlying"] is True
    first_interact = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "player.interact")
    bodies = [call["args"] for call in state["calls"][:first_interact] if call["operation"] == "world.spatial" and call["args"]["mode"] == "positions"]
    rays = [call["args"] for call in state["calls"][:first_interact] if call["operation"] == "world.spatial" and call["args"]["mode"] == "raycast"]
    assert len(bodies) == len(rays) == 1
    assert bodies[0]["limit"] == len(bodies[0]["positions"]) == 12
    assert rays[0]["from"]["y"] == first_move["y"] + 1.62
    assert sum((rays[0]["from"][axis] - rays[0]["to"][axis]) ** 2 for axis in ("x", "y", "z")) <= 9


async def test_all_three_approach_heights_blocked_refuses_without_teleport_open_or_click(native_bridge):
    client, state = native_bridge
    state["all_body_blocked"] = True
    result = await invoke(client)
    assert result["status"] == "unavailable" and result["reason"] == "safe_visible_approach_unavailable", result
    assert writes(state) == []
    bodies = [call["args"] for call in state["calls"] if call["operation"] == "world.spatial" and call["args"]["mode"] == "positions"]
    assert len(bodies) == 1 and bodies[0]["limit"] == len(bodies[0]["positions"]) == 12
    assert not any(call["operation"] in {"dev.teleport", "player.interact", "gui.slot_click"} for call in state["calls"])
    assert not any(call["operation"] == "world.spatial" and call["args"]["mode"] == "raycast" for call in state["calls"])


@pytest.mark.parametrize("target_height,expected_heights", [(1, [0, 1, 2]), (253, [252, 253, 254])])
async def test_approach_height_candidates_remain_inside_legal_standing_range(native_bridge, monkeypatch, target_height, expected_heights):
    client, state = native_bridge
    monkeypatch.setitem(INPUT, "y", target_height)
    result = await invoke(client)
    assert result["status"] == "completed", result
    bodies = [call["args"] for call in state["calls"] if call["operation"] == "world.spatial" and call["args"]["mode"] == "positions"]
    assert sorted({point["y"] for point in bodies[0]["positions"]}) == expected_heights
    assert all(0 <= point["y"] <= 254 for batch in bodies for point in batch["positions"])


async def test_default_compact_success_keeps_real_acceptance_full_timings_and_restoration_status(native_bridge):
    client, state = native_bridge
    state["material"], state["material_in_last_hotbar"] = 0, True
    arguments = declaration(generateMaterials=True)
    arguments.pop("includeDetails")
    result = await invoke(client, arguments)
    assert result["status"] == "completed" and result["detailsIncluded"] is False, result
    assert "phases" not in result and "grantCapacityProof" not in result["inventoryPreparation"]
    assert result["recipe"]["material"] == MATERIAL and result["recipe"]["output"] == PRODUCT
    assert result["acceptance"]["consumed"] == 8 and result["acceptance"]["outputDelta"] == 1
    assert result["acceptance"]["controllerStopped"] is True and result["acceptance"]["catalystRetained"] is True
    assert set(result["timings_ms"]) == {"preflight", "recipeDecision", "preparation", "executionAcceptance", "total"}
    assert result["timings_ms"]["total"] == result["taskElapsed_ms"] >= result["nativeWorkflowElapsed_ms"]
    assert "Full task" in result["timingScope"] and "nested subsets" in result["timingsScope"]
    assert result["executionsCompleted"] == 1 and result["outcomeUnknown"] is False and result["sideEffectsPossible"] is True
    preparation = result["inventoryPreparation"]
    assert preparation["singleFeedGrants"] is True and preparation["allBorrowedStacksRestored"] is True
    assert preparation["borrowed"] == result["pendingInventoryRestoration"] == []
    assert result["generatedMaterials"] is True and result["materialGrantsDispatched"] == 1
    assert state["material"] == state["input_material"] == 0 and state["output"] == 5 and not state["allowed"]
    assert len(json.dumps(result).encode("utf-8")) < 4096


async def test_requested_details_preserve_all_phase_and_actual_generation_capacity_evidence(native_bridge):
    client, state = native_bridge
    state["material"], state["material_in_last_hotbar"] = 0, True
    result = await invoke(client, declaration(generateMaterials=True, includeDetails=True))
    assert result["status"] == "completed" and result["detailsIncluded"] is True, result
    phases = result["phases"]
    assert any(phase["phase"] == "recipe_query" and phase["status"] == "completed" for phase in phases)
    assert any(phase["phase"] == "execute_1" and phase["status"] == "completed" for phase in phases)
    assert result["inventoryPreparation"]["grantCapacityProof"]["publicStackLimits"] == [{**MATERIAL, "stackLimit": 64, "finalPlayerCount": 8}]
    assert result["inventoryPreparation"]["grantCapacityProof"]["actualEmptyStorageSlots"] >= 1
    assert result["nativeWorkflowElapsed_ms"] == sum(phase.get("elapsed_ms", 0) for phase in phases if phase.get("timingScope") == "native_workflow")
    assert result["timingsScope"].startswith("Non-overlapping declaration/lock/preflight")


async def test_compact_uncertain_power_failure_retains_original_receipt_real_inputs_pending_restore_and_stop(native_bridge):
    client, state = native_bridge
    state["full_hotbar"], state["power_error"] = True, "disconnect"
    result = await invoke(client, declaration(includeDetails=False, restoreInventory=True))
    assert result["status"] == "failed" and result["failedPhase"] == "execute_1", result
    assert result["detailsIncluded"] is False and "phases" not in result
    assert "grantCapacityProof" not in result["inventoryPreparation"] and result["outcomeUnknown"] is True
    failed = result["failedNativeWorkflows"]
    assert len(failed) == 1 and failed[0]["phase"] == "execute_1" and failed[0]["outcomeUnknown"] is True
    power_receipt = next(receipt for receipt in failed[0]["actionReceipts"] if receipt["id"] == "power_on")
    original = result["lastActionReceipt"]
    assert original["operation"] == "gui.widget_action" and original["dispatched"] is True and original["outcomeUnknown"] is True
    assert original["requestId"] == power_receipt["requestId"]
    assert result["emergencyStop"]["status"] == "completed" and result["emergencyStop"]["serverStopConfirmed"] is True
    assert result["remainingInputs"]["available"] is True
    assert result["remainingInputs"]["slots"] == [{"index": 0, "item": stack(CATALYST, 1)}, {"index": 1, "item": stack(MATERIAL, 8)}]
    assert len(result["pendingInventoryRestoration"]) == 1 and result["inventoryPreparation"]["allBorrowedStacksRestored"] is False
    assert result["rolledBack"] is False and result["executionsCompleted"] == 0 and not state["allowed"]
    assert sum(call["operation"] == "gui.widget_action" and call.get("args", {}).get("action") == "set_selected"
        for call in state["calls"]) == 1


async def test_compact_semantic_failure_keeps_reason_and_actual_remaining_inputs_without_failed_workflow_invention(native_bridge):
    client, state = native_bridge
    state["controls"] = {"widgets": []}
    state["full_hotbar"] = True
    result = await invoke(client, declaration(includeDetails=False, restoreInventory=True))
    assert result["status"] == "failed" and result["reason"] == "power_widget_ambiguous", result
    assert result["failedPhase"] == "power_controls_1" and result["failedNativeWorkflows"] == []
    assert result["outcomeUnknown"] is False and result["lastActionReceipt"]["operation"] == "player.interact"
    assert "phases" not in result and "emergencyStop" not in result
    assert result["remainingInputs"]["available"] is True and result["remainingInputs"]["slots"][-1]["item"] == stack(MATERIAL, 8)
    assert len(result["pendingInventoryRestoration"]) == 1 and result["rolledBack"] is False
    assert state["input_material"] == 8 and state["output"] == 4


def recipe_position_sync_task(client, state, order, *, x_already_matches=True, timeout_ms=5000):
    state["position"] = {"x": INPUT["x"] + (.5 if x_already_matches else .125),
        "y": INPUT["y"] - 3, "z": INPUT["z"] - 2.5}
    state["position_sync_order"] = order
    return _RecipeTask(client, declaration(timeout_ms=timeout_ms), run_workflow)


@pytest.mark.parametrize("order", [("x", "y", "z"), ("z", "x", "y"), ("y", "z", "x")])
@pytest.mark.parametrize("x_already_matches", [True, False])
async def test_recipe_open_waits_for_all_axes_before_one_native_interaction(native_bridge, order, x_already_matches):
    client, state = native_bridge
    task = recipe_position_sync_task(client, state, order, x_already_matches=x_already_matches)
    gui = await task.open_gui("input")
    assert gui["screenId"] == "screen:input" and gui["windowId"] > 0
    assert state["position"] == state["last_teleport_destination"] and not state["position_frames"]
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1
    assert len([call for call in state["calls"] if call["operation"] == "player.interact"]) == 1
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
    assert task.phases[-1]["phase"] == "open_input" and task.phases[-1]["teleportDispatched"]


@pytest.mark.parametrize("axis", ["y", "z"])
async def test_recipe_position_sync_requires_xyz_in_the_same_final_snapshot(native_bridge, axis):
    client, state = native_bridge
    task = recipe_position_sync_task(client, state, ("y", "z", "x"), x_already_matches=False)
    state["position_sync_drift"] = axis
    with pytest.raises(BridgeError) as caught:
        await task.open_gui("input")
    assert caught.value.code == "ASSERTION_FAILED"
    assert not any(call["operation"] in {"player.interact", "gui.slot_click"} for call in state["calls"])
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1


async def test_recipe_position_sync_timeout_does_not_replay_teleport_or_open(native_bridge):
    client, state = native_bridge
    task = recipe_position_sync_task(client, state, (), timeout_ms=1000)
    with pytest.raises(BridgeError) as caught:
        await task.open_gui("input")
    assert caught.value.code in {"WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"}
    assert task.phases[-1]["failedIndex"] == 8  # The bounded feetY wait, before any native open.
    assert not any(call["operation"] in {"player.interact", "gui.slot_click"} for call in state["calls"])
    assert len([call for call in state["calls"] if call["operation"] == "dev.teleport"]) == 1


def recorded_complete_candidate():
    data = json.loads((Path(__file__).parent / "fixtures" / "cold-conditions.json").read_text(encoding="utf-8"))["data"]
    identity = lambda item: {key: item[key] for key in ("registryName", "metadata")}
    arguments = declaration(material=identity(data["query"]["input"]), outputItem=identity(data["query"]["output"]))
    return data["recipes"][0], arguments


def test_recorded_complete_client_candidate_is_supported_without_machine_special_cases():
    candidate, arguments = recorded_complete_candidate()
    selected = supported_recipe(candidate, arguments)
    assert selected.material == arguments["material"] and selected.output == arguments["outputItem"]
    assert selected.material_count == selected.output_count == 1 and selected.catalyst is None
    assert selected.raw_eut == 1920 and selected.raw_duration_ticks == 357
    candidate["category"], candidate["categoryRecipeIndex"] = "other.public.map", 123
    assert supported_recipe(candidate, arguments).category == "other.public.map"


@pytest.mark.parametrize("field,value,expected", [
    ("softBudgetPartial", True, False), ("byteLimited", True, False),
    ("metadataTruncated", True, False), ("metadataUnknown", True, False),
    ("publicMetadataComplete", False, True), ("metadata", [{"key": "unsupported_condition"}], []),
    ("specialValue", 1, 0), ("specialItems", {"unknown": True}, None), ("dynamic", True, False),
])
def test_recorded_candidate_unknown_conditions_stay_rejected_with_actual_field_evidence(field, value, expected):
    candidate, arguments = recorded_complete_candidate()
    candidate[field] = value
    with pytest.raises(_Unavailable) as error:
        supported_recipe(candidate, arguments)
    assert error.value.details["reason"] == "recipe_conditions_unsupported"
    conditions = error.value.details["rejectedConditions"]
    assert len(conditions) == 1 and conditions[0]["field"] == field
    assert conditions[0]["present"] is True and conditions[0]["expected"] == expected
    if isinstance(value, (list, dict)):
        assert conditions[0]["observed"]["length"] == len(value)
    else:
        assert conditions[0]["observed"] == value


@pytest.mark.parametrize("field", ["softBudgetPartial", "metadataUnknown"])
def test_missing_required_condition_field_is_explicitly_unavailable(field):
    candidate, arguments = recorded_complete_candidate()
    del candidate[field]
    with pytest.raises(_Unavailable) as error:
        supported_recipe(candidate, arguments)
    assert error.value.details["reason"] == "recipe_conditions_unsupported"
    assert error.value.details["rejectedConditions"] == [{"field": field, "present": False, "expected": False, "observed": None}]


@pytest.mark.parametrize("include_details", [False, True])
async def test_recorded_budget_partial_candidate_reports_evidence_without_native_actions_or_requery(native_bridge, include_details):
    client, state = native_bridge
    candidate, arguments = recorded_complete_candidate()
    candidate["softBudgetPartial"] = True
    state["recipe"] = candidate
    state["query_complete"] = True
    arguments["includeDetails"] = include_details
    result = await invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    assert result["sideEffectsPossible"] is False and result["failedPhase"] == "recipe_query"
    evidence = result["error"]["rejectedRecipeCandidates"]
    assert evidence == [{"page": 1, "candidateIndex": 0, "reason": "recipe_conditions_unsupported",
                         "category": candidate["category"], "categoryRecipeIndex": candidate["categoryRecipeIndex"],
                         "rejectedConditions": [{"field": "softBudgetPartial", "present": True, "expected": False, "observed": True}]}]
    assert len(state["queries"]) == 1 and writes(state) == []
    if include_details:
        phase = next(phase for phase in result["phases"] if phase["phase"] == "recipe_query")
        assert phase["rejectedRecipeCandidates"] == evidence
    else:
        assert "phases" not in result


async def test_candidate_rejection_evidence_is_bounded_without_embedding_unknown_metadata_or_special_stacks(native_bridge):
    client, state = native_bridge
    candidate, arguments = recorded_complete_candidate()
    candidate.update(metadata=[{"unbounded": "x" * 2048}] * 1024, specialItems={"unbounded": "y" * 2048},
                     category="c" * 2048, specialValue=10**100)
    query_calls = []

    async def query_reader(client, query):
        query_calls.append(query)
        return {"dimension": 180, "filterIncomplete": False, "recipes": [candidate] * 16, "hasMore": False, "complete": True}

    result = await invoke(client, arguments, query_reader=query_reader)
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    evidence = result["error"]["rejectedRecipeCandidates"]
    assert len(evidence) == 4 and len(query_calls) == 1 and writes(state) == []
    assert all(len(entry["category"]) == 128 for entry in evidence)
    conditions = {entry["field"]: entry["observed"] for entry in evidence[0]["rejectedConditions"]}
    assert conditions == {"metadata": {"type": "array", "length": 1024}, "specialValue": {"type": "int"},
                          "specialItems": {"type": "object", "length": 1}}
    encoded = json.dumps(evidence)
    assert len(encoded) < 4096 and "unbounded" not in encoded
