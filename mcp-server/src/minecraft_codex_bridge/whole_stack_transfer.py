"""Optional real-window whole-stack transfer; every routing decision precedes mutation."""
from __future__ import annotations

from copy import deepcopy
import math
import time

from .bridge import BridgeError, request_context, response_context

OPERATION = "gui.transfer_whole_stack"
CONTAINER = "com.cleanroommc.modularui.screen.ModularContainer"
OWNER_KEYS = ("x", "y", "z", "dimension", "registryName", "metaTileId", "metaClass")


def validate_capability(capability):
    from .recipe_tasks import _require
    expected = {"version": 1, "operation": OPERATION, "containerClass": CONTAINER, "hasTag": False,
        "emptyDestinationOnly": True, "serverIntermediateRequired": True, "fullNbtConservation": True,
        "nativeClicks": 2, "allPlayerSlots": 41, "maxMillis": 8000, "automaticReplayAllowed": False}
    _require(isinstance(capability, dict) and set(capability) == set(expected)
        and all(type(capability.get(key)) is type(value) and capability.get(key) == value for key, value in expected.items()),
        "whole_stack_capability_invalid", "The optional native whole-stack capability needs its exact supported version and conservation contract.")


def preserve_gui_capability(report, step_id, gui):
    entries = [step for step in report.get("steps", []) if step.get("id") == step_id]
    values = entries[0].get("observation", {}).get("values", {}) if len(entries) == 1 else {}
    if "wholeStackTransfer" in values:
        gui["wholeStackTransfer"] = deepcopy(values["wholeStackTransfer"])
    return gui


def plan(task, gui, source, destination, identity, amount, direction, owner):
    from .recipe_tasks import _require
    from .workflows import _context_capabilities, _inspect_many_player_inventory_available
    health = task.approach_health or {}
    capability = health.get("wholeStackTransfer")
    item = source.get("item")
    if capability is None or not isinstance(item, dict) or item.get("count") != amount:
        return None
    validate_capability(capability)
    window = gui.get("wholeStackTransfer")
    if window is None:
        return None
    _require(isinstance(window, dict) and type(window.get("supported")) is bool,
        "whole_stack_window_capability_invalid", "Current-window transfer support must be explicit before choosing a native action.")
    if window["supported"] is False:
        return None
    player, machine = (source, destination) if direction == "player_to_machine" else (destination, source)
    if not isinstance(owner, dict) or player.get("playerInventoryBindingVerified") is not True or machine.get("machineInventoryBindingVerified") is not True:
        return None
    _require(all(window.get(key) == value and type(window.get(key)) is type(value) for key, value in capability.items())
        and window.get("owner") == owner and set(owner) == set(OWNER_KEYS),
        "whole_stack_window_capability_invalid", "The same current normal window must advertise the exact actual resource owner and native contract.")
    _, actions = _context_capabilities(health)
    if OPERATION not in actions or not _inspect_many_player_inventory_available(health):
        return None
    proof = task.opened_gui
    _require(isinstance(proof, dict) and gui.get("screenId") == proof.get("screenId") and gui.get("windowId") == proof.get("windowId")
        and type(proof.get("side")) is int and 0 <= proof["side"] <= 5
        and isinstance(gui.get("slots"), list) and gui.get("truncated") is False,
        "whole_stack_window_unverified", "Native transfer requires this task's exact successfully opened complete ordinary window and face.")
    _require(direction in ("player_to_machine", "machine_to_player") and item.get("hasTag") is False
        and destination.get("item") is None and type(amount) is int and amount > 0
        and player.get("playerInventoryBindingVerified") is True and player.get("machineInventoryBindingVerified") is False
        and player.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
        and player.get("inventorySource") == "current_player_inventory"
        and type(player.get("playerInventoryIndex")) is int and 0 <= player["playerInventoryIndex"] <= 35
        and machine.get("machineInventoryBindingVerified") is True and machine.get("playerInventoryBindingVerified") is False
        and machine.get("machineInventoryBinding") == "public_native_slot_actual_machine_handler_identity"
        and type(machine.get("machineInventoryIndex")) is int and 0 <= machine["machineInventoryIndex"] <= 255
        and machine.get("machineInventoryOwner") == owner
        and all(type(slot.get("slot")) is int and type(slot.get("nativeContainerSlotId")) is int
            and slot["slot"] == slot["nativeContainerSlotId"] for slot in (source, destination))
        and source["slot"] != destination["slot"],
        "whole_stack_binding_unverified", "Both exact real slots must retain their unique player/owner identities and the entire untagged source quantity.")
    for slot, key, verified in ((player, "playerInventoryIndex", "playerInventoryBindingVerified"),
        (machine, "machineInventoryIndex", "machineInventoryBindingVerified")):
        _require(sum(row.get(verified) is True and row.get(key) == slot[key] for row in gui["slots"]) == 1,
            "whole_stack_binding_ambiguous", "A native whole-stack source/destination cannot share an ambiguous backing inventory index.")
    return {"version": 1, "expectedSessionId": task.args["expectedSessionId"], "expectedWorld": task.args["expectedWorld"],
        "screenId": gui["screenId"], "windowId": gui["windowId"], "side": proof["side"], "owner": deepcopy(owner),
        "direction": direction, "sourceSlot": source["slot"], "destinationSlot": destination["slot"],
        "playerInventoryIndex": player["playerInventoryIndex"], "machineInventoryIndex": machine["machineInventoryIndex"],
        "expectedItem": {"registryName": identity["registryName"], "metadata": identity["metadata"], "count": amount, "hasTag": False}}


def validate_receipt(result, arguments):
    from .recipe_tasks import _require
    required = {"version": 1, "operation": OPERATION, "status": "completed", "sessionId": arguments["expectedSessionId"],
        "screenId": arguments["screenId"], "windowId": arguments["windowId"], "direction": arguments["direction"],
        "sourceSlot": arguments["sourceSlot"], "destinationSlot": arguments["destinationSlot"],
        "playerInventoryIndex": arguments["playerInventoryIndex"], "machineInventoryIndex": arguments["machineInventoryIndex"],
        "count": arguments["expectedItem"]["count"], "clicksDispatched": 2, "clicksCompleted": 2,
        "serverPickupConfirmed": True, "serverPlaceConfirmed": True, "serverConfirmed": True,
        "allPlayerSlots": 41, "fullNbtConservation": True, "unrelatedSlotsPreserved": True, "cursorEmpty": True,
        "outcomeUnknown": False, "automaticReplayAllowed": False, "clientCursor": None, "serverCursor": None}
    _require(isinstance(result, dict) and all(type(result.get(key)) is type(value) and result.get(key) == value for key, value in required.items())
        and result.get("owner") == arguments["owner"] and type(result.get("allMachineSlots")) is int
        and 1 <= result["allMachineSlots"] <= 256 and type(result.get("selectedHotbarSlot")) is int
        and 0 <= result["selectedHotbarSlot"] <= 8 and type(result.get("elapsedMillis")) in (int, float)
        and math.isfinite(result["elapsedMillis"]) and 0 <= result["elapsedMillis"] <= 8000,
        "whole_stack_receipt_unavailable", "One compact native receipt must prove both real server transitions and complete full-NBT conservation; unknown results never fall back.")


def failure_fields(details):
    fields = ("stage", "clicksDispatched", "clicksCompleted", "automaticReplayAllowed", "cursorCleared", "knownReceipt")
    if not any(key in details for key in fields):
        return {}
    if not (all(key in details for key in fields) and isinstance(details["stage"], str)
        and details["stage"] in {"client_before", "server_before", "pickup", "place", "server_pickup_confirmation", "server_place_confirmation", "final_client"}
        and type(details["clicksDispatched"]) is int and type(details["clicksCompleted"]) is int
        and 0 <= details["clicksCompleted"] <= details["clicksDispatched"] <= 2
        and type(details.get("outcome_unknown")) is bool and details["outcome_unknown"] is (details["clicksDispatched"] > 0)
        and details["automaticReplayAllowed"] is False and details["cursorCleared"] is False
        and isinstance(details["knownReceipt"], dict)
        and details["knownReceipt"].get("cursorObservationScope") == "last_observed_native"
        and all(type(details["knownReceipt"].get(domain + "CursorObserved")) is bool for domain in ("client", "server"))
        and all(not details["knownReceipt"][domain + "CursorObserved"] or
            domain + "Cursor" in details["knownReceipt"] and isinstance(details["knownReceipt"].get(domain + "CursorStage"), str)
            for domain in ("client", "server"))):
        raise ValueError("Incomplete native whole-stack dispatch/cursor checkpoint")
    return {key: deepcopy(details[key]) for key in fields}


def validate_after(owner, player, arguments, result):
    from .recipe_tasks import _require, _same, inventory_slots
    from .multi_recipe_tasks import _owner
    position = {key: arguments["owner"][key] for key in ("x", "y", "z", "dimension")}
    _require(_owner(owner, position) == arguments["owner"] and "cursorItem" in player and player["cursorItem"] is None
        and type(player.get("selectedHotbarSlot")) is int and player["selectedHotbarSlot"] == result["selectedHotbarSlot"],
        "whole_stack_after_changed", "The fixed fresh AFTER must preserve the exact actual owner, selection and confirmed empty cursor.")
    slots = inventory_slots(player, "")
    _require(len(slots) == 41 and {row["index"] for row in slots} == set(range(41)),
        "whole_stack_after_changed", "The actual fresh AFTER must retain complete player slots 0..40.")
    machine_slots = inventory_slots(owner)
    _require(len(machine_slots) == result["allMachineSlots"] and {row["index"] for row in machine_slots} == set(range(result["allMachineSlots"])),
        "whole_stack_after_changed", "The fixed fresh actual machine inventory must retain every confirmed native backing index.")
    player_item = next(row["item"] for row in slots if row["index"] == arguments["playerInventoryIndex"])
    machine_item = next(row["item"] for row in machine_slots if row["index"] == arguments["machineInventoryIndex"])
    source, destination = (player_item, machine_item) if arguments["direction"] == "player_to_machine" else (machine_item, player_item)
    _require(source is None and _same(destination, arguments["expectedItem"]) and type(destination.get("count")) is int
        and destination["count"] == arguments["expectedItem"]["count"], "whole_stack_after_changed",
        "The fixed fresh AFTER must retain the exact empty native source and complete untagged bound destination quantity.")


async def execute(task, arguments):
    from .recipe_tasks import _require
    phase_report = {"phase": "native_whole_stack_transfer", "status": "running", "timingScope": "native_whole_stack_transfer_nested"}
    started = time.monotonic()
    task.phases.append(phase_report)
    result = None
    try:
        if task.actual_survival:
            task.require_survival_server_control(await task.snapshot())
        with request_context({"version": 1, "expectedSessionId": task.args["expectedSessionId"], "expectedProfile": "developer",
            "expectedWorld": task.args["expectedWorld"], "requireBackground": True}):
            result = await task.call(OPERATION, arguments)
        validate_before(result, arguments, task.validate_snapshot)
        validate_receipt(result, arguments)
        phase = await task.read_owner_phase([{key: arguments["owner"][key] for key in ("x", "y", "z", "dimension")}])
        _require(phase is not None, "whole_stack_after_unavailable", "Advertised native transfer requires its fixed fresh owner/player AFTER read; no legacy or older observation can replace it.")
        owner, player = phase[0][0], phase[1]
        validate_after(owner, player, arguments, result)
        phase_report.update(status="completed", receipt=deepcopy(result))
        return owner, player
    except BridgeError as error:
        phase_report.update(status="failed", error=error.as_dict(), receipt=deepcopy(result) if result is not None else error.details.get("knownReceipt"))
        if result is not None:
            if isinstance(getattr(task, "last_action", None), dict) and task.last_action.get("operation") == OPERATION:
                task.last_action["outcomeUnknown"] = True
            raise BridgeError(error.code, error.message, **{**error.details, "outcome_unknown": True, "knownReceipt": dict(result)}) from None
        raise
    finally:
        phase_report["elapsed_ms"] = int((time.monotonic() - started) * 1000)


def validate_before(result, arguments, validate_snapshot=None):
    from .recipe_tasks import _require, _RecipeTask
    from .workflows import _guard
    context = response_context(result)
    _require(isinstance(context, dict) and set(context) == {"version", "operation", "phase", "snapshot"}
        and type(context.get("version")) is int and context["version"] == 1 and context.get("operation") == OPERATION
        and context.get("phase") == "before" and isinstance(context.get("snapshot"), dict),
        "whole_stack_before_context_unavailable", "The native action must retain its own standard BEFORE context.")
    if validate_snapshot is not None:
        validate_snapshot(context["snapshot"])
    _guard(context["snapshot"], arguments["expectedSessionId"], "developer", arguments["expectedWorld"], True)
    _RecipeTask.require_live_player(context["snapshot"])


async def invoke_standalone(client, arguments, health):
    from .recipe_tasks import _require, _RecipeTask
    from .workflows import _context_capabilities, _inspect_many_player_inventory_available, _validate_player_inventory_observation, _guard
    validate_capability(health.get("wholeStackTransfer"))
    _, actions = _context_capabilities(health)
    _require(OPERATION in actions and _inspect_many_player_inventory_available(health), "whole_stack_context_unavailable",
        "Standalone native transfer requires the standard action BEFORE and fixed complete owner/player AFTER capability.")
    context = {"version": 1, "expectedSessionId": arguments["expectedSessionId"], "expectedProfile": "developer",
        "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
    result = None
    try:
        with request_context(context):
            result = await client.call(OPERATION, arguments)
        validate_before(result, arguments)
        validate_receipt(result, arguments)
        owner = arguments["owner"]
        after_args = {"positions": [{key: owner[key] for key in ("x", "y", "z")}], "dimension": owner["dimension"],
            "compact": False, "includePlayerInventory": True, "playerInventoryByteBudget": 131072, "observationByteBudget": 1048576}
        with request_context(context):
            after = await client.call("world.inspect_many", after_args)
        evidence = response_context(after)
        _require(isinstance(evidence, dict) and set(evidence) == {"version", "operation", "phase", "snapshot"}
            and type(evidence.get("version")) is int and evidence["version"] == 1 and evidence.get("operation") == "world.inspect_many"
            and evidence.get("phase") == "after" and isinstance(evidence.get("snapshot"), dict),
            "whole_stack_after_unavailable", "Standalone transfer requires this fixed fresh owner/player read's own complete AFTER context.")
        _guard(evidence["snapshot"], arguments["expectedSessionId"], "developer", arguments["expectedWorld"], True)
        _RecipeTask.require_live_player(evidence["snapshot"])
        _validate_player_inventory_observation(after, after_args, evidence)
        validate_after(after["results"][0]["data"], after["playerInventory"], arguments, result)
        return result
    except BridgeError as error:
        if result is not None:
            raise BridgeError(error.code, error.message, **{**error.details, "outcome_unknown": True, "knownReceipt": dict(result)}) from None
        raise
