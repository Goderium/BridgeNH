"""One bounded native-GUI recipe task; unsupported cases keep the general workflow available."""

from __future__ import annotations

import copy
import math
import re
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

import anyio
import jsonschema

from .bridge import BridgeClient, BridgeError, READ_OPERATIONS, placement_failure_fields, request_context, response_context
from .knowledge import operation_phase
from .nbt_order import context_equal
from .conveniences import json_sha256, json_size, path_get
from .validation import tool_validator
from .provider_counts import LONG_MAX, OWNER_KEYS, PROVIDER_QUERY_PATH, provider_count, provider_match
from .survival_posture import SURVIVAL_POSTURE_PROPERTIES, SURVIVAL_POSTURE_CONSTRAINTS
from .whole_stack_transfer import preserve_gui_capability, plan as plan_whole_stack, execute as execute_whole_stack


def _object(properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}


_COORDINATES = _object({"x": {"type": "integer", "minimum": -30_000_000, "maximum": 30_000_000},
    "y": {"type": "integer", "minimum": 1, "maximum": 253},
    "z": {"type": "integer", "minimum": -30_000_000, "maximum": 30_000_000}}, ["x", "y", "z"])
_ITEM = _object({"registryName": {"type": "string", "minLength": 3, "maxLength": 128,
    "pattern": r"^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+$", "not": {"pattern": r"[\s\x00-\x1F\x7F]"}},
    "metadata": {"type": "integer", "minimum": 0, "maximum": 32_766}}, ["registryName", "metadata"])
BACKEND_INPUTS_SCHEMA = {"type": "array", "minItems": 1, "maxItems": 8,
    "items": _object({"source": {"const": "registry"}, **_ITEM["properties"],
        "count": {"type": "integer", "minimum": 1, "maximum": 64}}, ["source", "registryName", "metadata", "count"])}
RECIPE_ONCE_SCHEMA = _object({
    "expectedSessionId": {"type": "string", "minLength": 1, "maxLength": 128},
    "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 256},
    "dimension": {"type": "integer", "minimum": -2_147_483_648, "maximum": 2_147_483_647},
    "machine": _COORDINATES, "input": _COORDINATES, "output": _COORDINATES,
    "outputItem": {**_ITEM, "description": "Expected recipe product registryName/metadata to query and measure. This is not the output bus/hatch item; blueprint requestedPorts[].ordinaryOutputItem identifies the actual output bus instead."},
    "material": {**_ITEM, "description": "Optional consumed recipe input registryName/metadata used for material selection and concrete wildcard resolution; not a port or catalyst identity."},
    "executions": {"type": "integer", "minimum": 1, "maximum": 8, "default": 1},
    "selection": {"const": "first_supported"},
    "recipeLookup": {"enum": ["catalog", "backend_match"], "default": "catalog"},
    "recipeMode": {"const": "public_multi", "description": "Opt-in finite public backend resources: complete deterministic item/fluid consumption and production, native product/container/tool recovery."},
    "outputItems": {"type": "array", "minItems": 0, "maxItems": 8, "uniqueItems": True, "items": _ITEM},
    "fluidContainerInputs": {"type": "array", "minItems": 1, "maxItems": 8, "items": _object({
        "owner": _COORDINATES, "playerInventoryIndex": {"type": "integer", "minimum": 0, "maximum": 35},
        "count": {"type": "integer", "minimum": 1, "maximum": 64},
        "fluid": {"type": "string", "minLength": 1, "maxLength": 128},
        "amount": {"type": "integer", "minimum": 1, "maximum": 2_147_483_647}},
        ["owner", "playerInventoryIndex", "count", "fluid", "amount"])},
    "fluidContainerOutputs": {"type": "array", "minItems": 1, "maxItems": 8, "items": _object({
        "owner": _COORDINATES, "playerInventoryIndex": {"type": "integer", "minimum": 0, "maximum": 35},
        "count": {"type": "integer", "minimum": 1, "maximum": 64},
        "fluid": {"type": "string", "minLength": 1, "maxLength": 128},
        "amount": {"type": "integer", "minimum": 1, "maximum": 2_147_483_647}},
        ["owner", "playerInventoryIndex", "count", "fluid", "amount"])},
    "recoverExistingCatalysts": {"type": "boolean", "default": False},
    "inputGuiSlots": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True, "items": {"type": "integer", "minimum": 0, "maximum": 511}},
    "catalystGuiSlots": {"type": "array", "minItems": 0, "maxItems": 8, "uniqueItems": True, "items": {"type": "integer", "minimum": 0, "maximum": 511}},
    "outputGuiSlots": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True, "items": {"type": "integer", "minimum": 0, "maximum": 511}},
    "outputMode": {"enum": ["inventory", "provider_cache"], "default": "inventory",
        "description": "Optional local output-provider cache acceptance for one deterministic item output from a distinct multiblock and one registered item output owner with an empty actual inventory. Exact queried identity/owner/session deltas only; no cell, network or offline claim."},
    "backendInputs": {**BACKEND_INPUTS_SCHEMA, "minItems": 0},
    "inputGuiSlot": {"type": "integer", "minimum": 0, "maximum": 511,
        "description": "Optional caller-observed native input slot. When omitted, the task's own input GUI must publicly bind the lowest empty consumed-input machine index to one native slot and the exact actual server machine owner; unavailable or ambiguous bindings are refused."},
    "catalystGuiSlot": {"type": "integer", "minimum": 0, "maximum": 511},
    "catalystMode": {"enum": ["physical", "configured_ghost"], "default": "physical"},
    "generateMaterials": {"type": "boolean", "default": False,
        "description": "Explicit creative test supply. When clearing, first proves an empty cursor and fresh matching client/server inventory-container acknowledgement before /clear. Command acceptance never replaces actual complete inventory/pickup verification; missing acknowledgement stops before clearing."},
    "actualSurvival": {"type": "boolean", "default": False,
        "description": "Require real client/server survival with existing finite inventory and the declared native operation posture (grounded by default). No material generation, clearing or creative inventory controls."},
    **copy.deepcopy(SURVIVAL_POSTURE_PROPERTIES),
    "movementStrategy": {"enum": ["walking", "ability_flight", "developer_teleport"],
        "description": "Actual-survival approach preselection; defaults to walking. ability_flight uses existing real permission and lands by default; explicit survivalOperationPosture=ability_flight retains already confirmed flight during operations. developer_teleport explicitly selects one developer test /tp. No automatic fallback."},
    "singleBlockPlacement": _object({"item": _ITEM,
        "hotbarSlot": {"type": "integer", "minimum": 0, "maximum": 8},
        "powerReadyTimeout_ms": {"type": "integer", "minimum": 50, "maximum": 10_000, "default": 5000}},
        ["item", "hotbarSlot"]),
    "preflightControls": {"type": "boolean", "default": False},
    "includeDetails": {"type": "boolean", "default": False},
    "restoreInventory": {"type": "boolean", "default": False},
    "inventoryPreparation": {"enum": ["clear", "preserve"]},
    "maxRecipePages": {"type": "integer", "minimum": 1, "maximum": 16, "default": 16},
    "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 60_000, "default": 30_000},
}, ["expectedSessionId", "expectedWorld", "machine", "input", "output", "selection"])
RECIPE_ONCE_SCHEMA["allOf"] = [{"if": {"required": ["recipeLookup"],
    "properties": {"recipeLookup": {"const": "backend_match"}}}, "then": {"required": ["backendInputs"]},
    "else": {"not": {"required": ["backendInputs"]}}}]
RECIPE_ONCE_SCHEMA["allOf"].append({"if": {"required": ["recipeMode"]},
    "then": {"required": ["recipeLookup", "backendInputs", "outputItems"], "properties": {
        "recipeLookup": {"const": "backend_match"}, "outputMode": {"const": "inventory"}}},
    "else": {"not": {"anyOf": [{"required": [field]} for field in
        ("outputItems", "inputGuiSlots", "catalystGuiSlots", "outputGuiSlots", "fluidContainerInputs",
         "fluidContainerOutputs", "recoverExistingCatalysts")]}, "required": ["outputItem"],
        "properties": {"backendInputs": {"minItems": 1}}}})
RECIPE_ONCE_SCHEMA["allOf"].append({"if": {"required": ["recipeMode"]}, "then": {
    "anyOf": [{"properties": {"backendInputs": {"minItems": 1}}}, {"required": ["fluidContainerInputs"]}],
    "allOf": [{"if": {"properties": {"outputItems": {"minItems": 1}}}, "then": {"required": ["outputItem"]},
        "else": {"required": ["fluidContainerOutputs"], "not": {"required": ["outputItem"]}}},
        {"if": {"anyOf": [{"required": ["fluidContainerInputs"]}, {"required": ["fluidContainerOutputs"]}]},
         "then": {"properties": {"executions": {"const": 1}, "inventoryPreparation": {"const": "preserve"}}}}]}})
RECIPE_ONCE_SCHEMA["allOf"].append({"if": {"required": ["singleBlockPlacement"]}, "then": {
    "required": ["actualSurvival", "recipeMode", "inventoryPreparation", "preflightControls"],
    "properties": {"actualSurvival": {"const": True}, "recipeMode": {"const": "public_multi"},
        "inventoryPreparation": {"const": "preserve"}, "preflightControls": {"const": True},
        "generateMaterials": {"const": False}}}})

INTEGRATED_CIRCUIT = "gregtech:gt.integrated_circuit"
BASIC_MACHINE_API = "gregtech.api.metatileentity.implementations.MTEBasicMachineWithRecipe"
COMMON_BASIC_MACHINE_API = "gregtech.api.metatileentity.implementations.MTEBasicMachine"
COMMON_MULTIBLOCK_API = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase"
GHOST_CIRCUIT_API = "gregtech.common.modularui2.widget.GhostCircuitSlotWidget"
CIRCUIT_SUPPORT_API = "gregtech.api.interfaces.IConfigurationCircuitSupport"
GHOST_CONFIG_API = "GhostCircuitSlotWidget.getSyncHandler().getGhostCircuitHandler().getCircuitConfig()"
GHOST_CONFIG_SOURCE = "Current client widget sync handler; a later read is required after server synchronization."
MACHINE_SLOT_BINDING = "public_native_slot_actual_machine_handler_identity"
MAX_TRANSFER_COUNT = 16


class _Unavailable(BridgeError):
    def __init__(self, reason: str, message: str, **details: Any):
        super().__init__("RECIPE_TASK_UNAVAILABLE", message, reason=reason, **details)


def _require(condition: bool, reason: str, message: str) -> None:
    if not condition:
        raise _Unavailable(reason, message)


def _quantity(value: Any) -> int:
    _require(type(value) is int and value >= 0, "quantity_unavailable", "An exact nonnegative item quantity is required.")
    return value


def _identity(stack: Any, *, wildcard: bool = False) -> dict[str, Any]:
    _require(isinstance(stack, dict) and stack.get("hasTag") is False, "item_nbt_or_identity_unavailable",
             "Only explicitly untagged actual item identities are supported.")
    registry, metadata = stack.get("registryName"), stack.get("metadata")
    _require(isinstance(registry, str) and re.fullmatch(_ITEM["properties"]["registryName"]["pattern"], registry) is not None
             and type(metadata) is int and 0 <= metadata <= (32_767 if wildcard else 32_766),
             "item_identity_unavailable", "An exact registry name and concrete metadata are required.")
    return {"registryName": registry, "metadata": metadata}


def _same(stack: Any, identity: dict[str, Any]) -> bool:
    return isinstance(stack, dict) and stack.get("hasTag") is False and all(
        type(stack.get(key)) is type(value) and stack.get(key) == value for key, value in identity.items())


def _match(identity: dict[str, Any], count: int | None = None) -> dict[str, Any]:
    result = {f"item.{key}": value for key, value in identity.items()}
    result["item.hasTag"] = False
    if count is not None:
        result["item.count"] = count
    return result


def _entries(group: Any) -> list[dict[str, Any]]:
    _require(isinstance(group, dict) and group.get("truncated") is False and isinstance(group.get("entries"), list)
             and type(group.get("total")) is int and group["total"] == len(group["entries"]),
             "recipe_stack_coverage_incomplete", "Recipe stack groups must be explicitly complete.")
    return group["entries"]


def _bounded_condition_value(value: Any) -> Any:
    if value is None or type(value) is bool:
        return value
    if type(value) is int and abs(value) <= 2**63 - 1:
        return value
    if type(value) is float and math.isfinite(value) and abs(value) <= 2**63 - 1:
        return value
    if isinstance(value, str):
        return {"type": "string", "prefix": value[:128], "truncated": len(value) > 128}
    if isinstance(value, (list, dict)):
        return {"type": "array" if isinstance(value, list) else "object", "length": len(value)}
    return {"type": type(value).__name__[:32]}


def _backend_lookup_proof(data: Any) -> dict[str, Any]:
    """Fixed fields only; preserve a failed first read without copying recipe stacks or arbitrary payloads."""
    def fields(value: Any, names: tuple[str, ...]) -> dict[str, Any]:
        if not isinstance(value, dict):
            return {"observedType": type(value).__name__[:32]}
        return {key: entry if isinstance(entry := value[key], str) and len(entry) <= 128
            else _bounded_condition_value(entry) for key in names if key in value}

    proof = fields(data, ("mode", "source", "sessionId", "dimension", "lookupSource", "lookupComplete", "registeredCatalogComplete",
        "executionValidated", "hasMore", "nextCursor", "matched", "candidateValidated", "filterIncomplete", "stopReason"))
    if not isinstance(data, dict):
        return proof
    recipes = data.get("recipes")
    proof["recipesCount"] = len(recipes) if isinstance(recipes, list) else None
    proof["budget"] = fields(data.get("budget"), ("elapsedMicros", "payloadElapsedMicros", "softBudgetMicros",
        "softBudgetScope", "softBudgetExceeded", "nativeFindCalls", "nativeCallInterruptible"))
    if isinstance(recipes, list) and recipes:
        recipe = recipes[0]
        partial = fields(recipe, ("softBudgetPartial", "byteLimited", "metadataTruncated", "metadataTotal",
            "publicMetadataComplete", "metadataUnknown", "metadataUnknownCount"))
        if isinstance(recipe, dict):
            for kind in ("items", "fluids"):
                groups = recipe.get(kind)
                if isinstance(groups, dict):
                    partial[kind] = {group: fields(groups.get(group), ("truncated", "total")) for group in ("inputs", "outputs")}
        proof["firstRecipePartial"] = partial
    return proof


@dataclass(frozen=True)
class SimpleRecipe:
    material: dict[str, Any]
    material_count: int
    output: dict[str, Any]
    output_count: int
    catalyst: dict[str, Any] | None
    raw_eut: int
    raw_duration_ticks: int
    category: str
    category_index: int
    candidate_source: str | None = None


def _supported_public_metadata(recipe: dict[str, Any]) -> bool:
    metadata = recipe.get("metadata")
    if metadata == []:
        return True
    if not isinstance(metadata, list) or len(metadata) != 1 or not isinstance(metadata[0], dict):
        return False
    annotation = metadata[0]
    # RECYCLE=false is a public build-time annotation; registered raw inputs/outputs carry its result.
    # The marker is emitted only after Java matches the actual public constant by object identity.
    return (set(annotation) <= {"keyClass", "identifier", "publicKeyIdentity", "value", "available"}
            and annotation.get("keyClass") == "gregtech.api.recipe.metadata.SimpleRecipeMetadataKey"
            and annotation.get("publicKeyIdentity") == "gregtech.api.util.GTRecipeConstants.RECYCLE"
            and annotation.get("identifier") == "recycle" and annotation.get("value") is False
            and annotation.get("available", True) is True
            and type(recipe.get("metadataTotal")) is int and recipe["metadataTotal"] == 1
            and type(recipe.get("metadataUnknownCount")) is int and recipe["metadataUnknownCount"] == 0)


def validate_recipe_conditions(recipe: dict[str, Any], arguments: dict[str, Any]) -> None:
    """Shared complete raw-condition/timing/provenance guards, independent of resource kinds."""
    _require(isinstance(recipe, dict), "recipe_flags_unsupported", "A typed raw recipe is required.")
    _require(recipe.get("available") is True and recipe.get("enabled") is True and recipe.get("hidden") is False
             and recipe.get("fakeRecipe") is False and recipe.get("nbtSensitive") is False,
             "recipe_flags_unsupported", "Unavailable, disabled, fake, hidden or NBT-sensitive recipes are unsupported.")
    conditions = [
        ("softBudgetPartial", False, recipe.get("softBudgetPartial") is False),
        ("byteLimited", False, recipe.get("byteLimited", False) is False),
        ("metadataTruncated", False, recipe.get("metadataTruncated") is False),
        ("metadataUnknown", False, recipe.get("metadataUnknown") is False),
        ("publicMetadataComplete", True, recipe.get("publicMetadataComplete") is True),
        ("metadata", [], _supported_public_metadata(recipe)),
        ("specialValue", 0, recipe.get("specialValue") == 0),
        ("specialItems", None, recipe.get("specialItems") is None),
        ("dynamic", False, recipe.get("dynamic", False) is False),
    ]
    rejected = [{"field": field, "present": field in recipe, "expected": expected,
                 "observed": _bounded_condition_value(recipe.get(field))}
                for field, expected, accepted in conditions if not accepted]
    if rejected:
        raise _Unavailable("recipe_conditions_unsupported", "Unknown, dynamic, special or partially read recipe conditions are unsupported.",
                           rejectedConditions=rejected)
    _require(type(recipe.get("rawEUt")) is int and recipe["rawEUt"] > 0
             and type(recipe.get("rawDurationTicks")) is int and recipe["rawDurationTicks"] > 0,
             "recipe_timing_unavailable", "Positive raw GT EU/t and duration are required.")
    if arguments.get("recipeLookup", "catalog") == "backend_match":
        _require(recipe.get("candidateSource") == "public_backend_find" and type(recipe.get("candidateOrdinal")) is int
                 and recipe["candidateOrdinal"] == 0, "backend_candidate_source_unavailable",
                 "Explicit backend lookup accepts only the one current native first-candidate ordinal, not a catalog index.")


def supported_recipe(recipe: dict[str, Any], arguments: dict[str, Any]) -> SimpleRecipe:
    """Accept known raw conditions, never infer executability from a registered recipe."""
    validate_recipe_conditions(recipe, arguments)
    try:
        items, fluids = recipe["items"], recipe["fluids"]
        inputs, outputs = _entries(items["inputs"]), _entries(items["outputs"])
        _require(not _entries(fluids["inputs"]) and not _entries(fluids["outputs"]),
                 "fluid_recipe_unsupported", "This first task supports no fluid inputs or outputs.")
    except (KeyError, TypeError):
        raise _Unavailable("recipe_stack_coverage_incomplete", "Required raw stack groups are absent.") from None
    _require(all(isinstance(entry, dict) for entry in inputs + outputs), "recipe_stack_coverage_incomplete", "Recipe entries must be typed objects.")
    _require(type(recipe.get("chanceDenominator")) is int and recipe["chanceDenominator"] == 10_000
             and all(type(entry.get("chance")) is int and entry["chance"] == 10_000 for entry in inputs + outputs),
             "probability_recipe_unsupported", "Every declared input/output must have a 100% raw chance.")
    consumed, catalysts = [], []
    for entry in inputs:
        stack = entry.get("stack")
        identity = _identity(stack, wildcard=True)
        count = _quantity(stack.get("count"))
        _require(type(entry.get("nonConsumedCatalyst")) is bool and entry["nonConsumedCatalyst"] == (count == 0),
                 "catalyst_semantics_unavailable", "The public non-consumed catalyst marker must agree with count zero.")
        (catalysts if count == 0 else consumed).append((identity, count))
    _require(len(consumed) == 1 and len(catalysts) <= 1 and len(outputs) == 1,
             "complex_recipe_unsupported", "Only one actual consumed item, at most one retained catalyst, and one deterministic output are supported.")
    material, amount = consumed[0]
    selected_material = arguments.get("material")
    if material["metadata"] == 32_767:
        _require(isinstance(selected_material, dict) and selected_material.get("registryName") == material["registryName"],
                 "wildcard_material_required", "A wildcard input needs a caller-declared concrete material identity.")
        material = _identity({**selected_material, "hasTag": False})
    elif selected_material is not None:
        _require(material == selected_material, "material_recipe_mismatch", "The declared material does not match this raw input.")
    _require(1 <= amount <= MAX_TRANSFER_COUNT, "exact_transfer_budget", "One execution supports at most 16 native right-click placements.")
    output_stack = outputs[0].get("stack")
    output = _identity(output_stack)
    output_count = _quantity(output_stack.get("count"))
    _require(output == arguments["outputItem"] and 1 <= output_count <= 64,
             "output_recipe_mismatch", "The one real output must match the declared output filter exactly.")
    catalyst = catalysts[0][0] if catalysts else None
    _require(catalyst is None or catalyst["metadata"] != 32_767,
             "catalyst_type_unsupported", "A zero-count physical catalyst requires one exact untagged concrete item identity.")
    _require(catalyst is None or catalyst != material, "catalyst_identity_overlap", "Consumed input and retained catalyst identities must differ for exact acceptance.")
    shared_inventory = all(key in arguments for key in ("machine", "input", "output")) \
        and arguments["machine"] == arguments["input"] == arguments["output"]
    _require(not shared_inventory or material != output and (catalyst is None or catalyst != output),
             "shared_inventory_recipe_identity_overlap", "A shared inventory requires distinct consumed input, output and catalyst identities for independent real acceptance.")
    configured_ghost = arguments.get("catalystMode", "physical") == "configured_ghost"
    _require(not configured_ghost or catalyst is not None, "configured_ghost_recipe_required",
             "Configured ghost mode requires one actual raw zero-count integrated circuit catalyst.")
    _require(not configured_ghost or catalyst["registryName"] == INTEGRATED_CIRCUIT,
             "configured_ghost_catalyst_type_unsupported", "Only an integrated circuit may use the configured public ghost widget path.")
    _require(configured_ghost or catalyst is None or "catalystGuiSlot" not in arguments or "inputGuiSlot" not in arguments
             or arguments["catalystGuiSlot"] != arguments["inputGuiSlot"],
             "catalyst_destination_required", "A physical catalyst destination must be separate from the material destination.")
    _require(type(recipe.get("rawEUt")) is int and recipe["rawEUt"] > 0
             and type(recipe.get("rawDurationTicks")) is int and recipe["rawDurationTicks"] > 0,
             "recipe_timing_unavailable", "Positive raw GT EU/t and duration are required.")
    backend = arguments.get("recipeLookup", "catalog") == "backend_match"
    if backend:
        _require(recipe.get("candidateSource") == "public_backend_find" and type(recipe.get("candidateOrdinal")) is int
                 and recipe["candidateOrdinal"] == 0, "backend_candidate_source_unavailable",
                 "Explicit backend lookup accepts only the one current native first-candidate ordinal, not a catalog index.")
    return SimpleRecipe(material, amount, output, output_count, catalyst, recipe["rawEUt"], recipe["rawDurationTicks"],
                        str(recipe.get("category") or "")[:128], 0 if backend else _quantity(recipe.get("categoryRecipeIndex")),
                        "public_backend_find" if backend else None)


def inventory_slots(observation: dict[str, Any], path: str = "inventory") -> list[dict[str, Any]]:
    found, inventory = path_get(observation, path) if path else (True, observation)
    _require(found and isinstance(inventory, dict) and inventory.get("truncated") is False
             and isinstance(inventory.get("slots"), list) and type(inventory.get("totalSlots")) is int
             and 0 < inventory["totalSlots"] == len(inventory["slots"]) <= 512,
             "inventory_coverage_incomplete", "Real inventory slots must have explicit complete coverage.")
    slots = inventory["slots"]
    _require(all(isinstance(slot, dict) and "item" in slot and type(slot.get("index", slot.get("slot"))) is int for slot in slots),
             "inventory_slot_identity_unavailable", "Every inventory entry needs a real slot identity.")
    _require(len({slot.get("index", slot.get("slot")) for slot in slots}) == len(slots),
             "inventory_slot_identity_unavailable", "Real inventory slot identities must be unique.")
    return slots


def item_count(slots: list[dict[str, Any]], identity: dict[str, Any]) -> int:
    return sum(_quantity(slot["item"].get("count")) for slot in slots if _same(slot.get("item"), identity))


def unique_source(slots: list[dict[str, Any]], identity: dict[str, Any], amount: int, excluded: set[int] | None = None) -> dict[str, Any]:
    matches = [slot for slot in slots if _same(slot.get("item"), identity)
               and slot.get("slot", slot.get("index")) not in (excluded or set())]
    _require(len(matches) == 1 and _quantity(matches[0]["item"].get("count")) >= amount,
             "material_source_missing_or_ambiguous", "The real source must be one observed stack with enough items; no slot or ownership is guessed.")
    return matches[0]


def power_widget(observation: dict[str, Any], allowed_to_work: bool, *, allow_public_semantic: bool = False) -> dict[str, Any]:
    widgets = observation.get("widgets")
    _require(isinstance(widgets, list), "power_widget_unavailable", "The live power control list is absent.")
    candidates = [widget for widget in widgets if isinstance(widget, dict) and widget.get("semantic") == "power_switch"
                  and widget.get("enabled") is True and widget.get("valid") is True and widget.get("optionCount") == 2
                  and isinstance(widget.get("supportedActions"), list) and "set_selected" in widget["supportedActions"]]
    _require(len(candidates) == 1, "power_widget_ambiguous", "Exactly one observed power switch is required.")
    widget = candidates[0]
    _require(type(widget.get("selected")) is int and widget["selected"] == int(allowed_to_work),
             "power_state_not_synchronized", "The live power selection must agree with the authoritative allowed-to-work state.")
    overlay = widget.get("overlay", {})
    current = overlay.get("current", overlay) if isinstance(overlay, dict) else None
    location = current.get("location") if isinstance(current, dict) else None
    expected = "gregtech:textures/gui/overlay_button/power_switch_" + ("on" if allowed_to_work else "off") + ".png"
    public_toggle = allow_public_semantic and "overlay" not in widget and widget.get("kind") == "choice" \
        and widget.get("semanticSource") == "GT shared public tooltip/texture resource; current screen object identity required for mutation"
    _require(location == expected or public_toggle, "power_semantics_unverified",
             "The actual public resource or explicit shared GT toggle semantic and synchronized state must confirm the two-state meaning.")
    _require(isinstance(widget.get("widgetId"), str) and bool(widget["widgetId"]), "power_widget_unavailable", "The native widget identity is absent.")
    return widget


def configured_ghost_widget(observation: dict[str, Any], configuration: int) -> dict[str, Any]:
    _require(observation.get("hasMore") is False and observation.get("partial") is False
             and isinstance(observation.get("widgets"), list), "configured_ghost_widget_coverage_unavailable",
             "The public ghost widget observation must have complete explicit bounded coverage.")
    widgets = [widget for widget in observation["widgets"] if isinstance(widget, dict)
               and widget.get("semantic") == "ghost_circuit"]
    _require(len(widgets) == 1, "configured_ghost_widget_ambiguous", "Exactly one live public ghost circuit widget is required.")
    widget = widgets[0]
    _require(widget.get("class") == GHOST_CIRCUIT_API and widget.get("kind") == "ghost_circuit"
             and widget.get("enabled") is True and widget.get("valid") is True
             and isinstance(widget.get("widgetId"), str) and bool(widget["widgetId"]),
             "configured_ghost_widget_api_unavailable", "Only the actual fixed public GhostCircuitSlotWidget API is accepted.")
    config = widget.get("circuitConfig")
    _require(isinstance(config, dict) and config.get("api") == GHOST_CONFIG_API
             and config.get("source") == GHOST_CONFIG_SOURCE and config.get("available") is True,
             "configured_ghost_state_unavailable", "The live public ghost sync handler getter must explicitly expose its actual configuration.")
    _require(type(config.get("value")) is int and config["value"] == configuration
             and config.get("noConfiguration") is (configuration == -1),
             "configured_ghost_configuration_mismatch", "The actual synchronized public ghost configuration must match the expected native configuration.")
    return widget


def registered_components(components: Any, *, stopped: bool = False, allow_fluid_inputs: bool = False) -> tuple[list[dict[str, Any]], str, str | None]:
    _require(isinstance(components, dict) and components.get("hasMore") is False
             and isinstance(components.get("entries"), list) and type(components.get("total")) is int
             and 0 <= components["total"] == len(components["entries"]) <= 128
             and isinstance(components.get("unavailableRoles"), list) and isinstance(components.get("roles"), list),
             "registered_component_coverage_incomplete", "Complete current registered component pages and role coverage are required.")
    entries, summaries, unavailable = components["entries"], components["roles"], components["unavailableRoles"]
    _require(all(isinstance(entry, dict) and isinstance(entry.get("role"), str) for entry in entries)
             and all(isinstance(role, dict) and isinstance(role.get("role"), str)
                     and type(role.get("count")) is int and role["count"] >= 0 for role in summaries)
             and all(isinstance(role, dict) and isinstance(role.get("role"), str) for role in unavailable),
             "registered_component_coverage_incomplete", "Registered role summaries and entries must be typed.")
    counts = {role["role"]: role["count"] for role in summaries}
    _require(len(counts) == len(summaries) and sum(counts.values()) == len(entries)
             and all(sum(entry["role"] == role for entry in entries) == count for role, count in counts.items()),
             "registered_component_coverage_incomplete", "Registered role counts must exactly cover the observed entries.")
    for role in ("item_input", "item_output", "dual_input", "fluid_input"):
        _require(role in counts and not any(item["role"] == role for item in unavailable),
                 "registered_input_coverage_unavailable", "Actual input/output role coverage is unavailable; isolation cannot be inferred.")
    _require(counts["dual_input"] == 0 and (allow_fluid_inputs or counts["fluid_input"] == 0),
             "additional_inputs_unsupported", "This isolated first task supports no additional dual or fluid input ports.")
    modular = components.get("modularHatches")
    _require(isinstance(modular, dict) and type(modular.get("available")) is bool,
             "modular_coverage_unavailable", "Public modular interface availability must be explicit.")
    coverage, reason = "limited", modular.get("reason")
    if modular["available"]:
        _require(type(modular.get("registeredCount")) is int and modular["registeredCount"] == counts.get("modular_hatch")
                 and not any(item["role"] == "modular_hatch" for item in unavailable),
                 "modular_coverage_unavailable", "The public modular getter count must match complete registered entries.")
        coverage, reason = "public_registered_modules", None
    else:
        _require(isinstance(reason, str) and bool(reason), "modular_coverage_unavailable", "Unavailable public modules need an explicit coverage reason.")
    for entry in entries:
        if "executionCore" not in entry:
            continue
        core = entry["executionCore"]
        _require(isinstance(core, dict) and type(core.get("working")) is bool and entry.get("valid") is True
                 and isinstance(core.get("mainMachine"), dict) and core["mainMachine"].get("controllerIdentityMatches") is True,
                 "execution_core_state_unavailable", "Each exposed core must have real public working state and current controller binding.")
        if stopped:
            _require(core["working"] is False, "execution_core_not_stopped", "Every publicly exposed registered execution core must have stopped.")
    return entries, coverage, reason


def _read(identifier: str, tool: str, args: dict[str, Any] | None = None, fields: list[str] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"id": identifier, "kind": "read", "tool": tool, "args": args or {}, "omitEmptySlots": False}
    if fields is not None:
        result["fields"] = fields
    return result


def _action(identifier: str, tool: str, args: dict[str, Any]) -> dict[str, Any]:
    return {"id": identifier, "kind": "action", "tool": tool, "args": args}


def _assert(identifier: str, source: str, path: str, value: Any, operator: str = "equals") -> dict[str, Any]:
    return {"id": identifier, "kind": "assert", "source": source, "condition": {"path": path, "operator": operator, "value": value}, "fields": []}


def _wait(identifier: str, tool: str, args: dict[str, Any], path: str, value: Any, *, operator: str = "equals",
          fields: list[str] | None = None) -> dict[str, Any]:
    result = _read(identifier, tool, args, fields)
    result.update(kind="wait", condition={"path": path, "operator": operator, "value": value}, interval_ms=50)
    return result


def inventory_baseline_assertion(source: str, observation: dict[str, Any]) -> dict[str, Any]:
    """Bind a checked complete inventory to the read immediately before power.

    The fingerprint keeps the native plan bounded while retaining every raw
    slot, identity and quantity. It is a one-use guard, not a cached baseline.
    """
    inventory_slots(observation)
    return _assert("inventory_baseline_unchanged", source, "inventory",
                   json_sha256(observation["inventory"]), "json_sha256_equals")


RECIPE_ONCE_SCHEMA.setdefault("allOf", []).extend(copy.deepcopy(SURVIVAL_POSTURE_CONSTRAINTS))


class _TrackedClient:
    """Observe actual transport dispatch even if a runner is cancelled before returning."""

    def __init__(self, task: "_RecipeTask"):
        self.task = task
        self.configuration = task.client.configuration
        self.rpc_epoch = 0

    async def invoke(self, operation: str, args: dict[str, Any]) -> dict[str, Any]:
        # Reuse the transport's public protocol/capability discovery with our pinned call path.
        if operation == "recipes.query" and self.task.recipe_query_preflight_verified:
            return await self.call(operation, args)
        return await BridgeClient.invoke(self, operation, args)

    async def call(self, operation: str, args: dict[str, Any], *, expected_credentials: Any = None,
                   trace: dict[str, Any] | None = None) -> dict[str, Any]:
        if expected_credentials is not None and expected_credentials != self.task.credentials:
            raise BridgeError("SESSION_CHANGED", "Connection changed during the recipe task; later actions were blocked.")
        if getattr(self.task, "recipe_context_guard_pending", False) and operation in (
            "gui.slot_click", "gui.widget_action", "gui.transfer_whole_stack", "player.place_block", "chat.send"):
            # Check the complete current ledger at the actual first dispatch,
            # without enlarging a bounded click workflow or reading its cursor
            # while a known transfer is in progress.
            await self.task.read_recipe_context_inventory()
            self.task.recipe_context_guard_pending = False
        # A planner handoff is valid only until the next actual transport call.
        # Count reads as well as mutations, including failed/unknown dispatches.
        self.rpc_epoch += 1
        actual_trace = trace if trace is not None else {}
        result = None
        placement_failure = None
        whole_stack_failure = None
        try:
            if self.task.survival_flight and operation in ("player.place_block", "structure.survival_build", "player.interact"):
                args = {**args, "requireSurvivalFlight": True}
            result = await self.task.client.call(operation, args, expected_credentials=self.task.credentials, trace=actual_trace)
            return result
        except BridgeError as error:
            if operation in ("inventory.supply", "player.place_block") and error.details.get("outcome_unknown") is True:
                actual_trace["outcome_unknown"] = True
            if operation == "player.place_block":
                try:
                    fields = placement_failure_fields(error.details)
                except ValueError:
                    actual_trace["outcome_unknown"] = True
                    raise BridgeError("MALFORMED_RESPONSE", "Native placement failure checkpoints are incomplete or unbounded; no action is replayed.", outcome_unknown=True) from None
                if fields:
                    placement_failure = copy.deepcopy(fields)
            if operation == "gui.transfer_whole_stack":
                from .whole_stack_transfer import failure_fields
                whole_stack_failure = failure_fields(error.details)
                if error.details.get("outcome_unknown") is True:
                    actual_trace["outcome_unknown"] = True
            raise
        finally:
            if operation not in READ_OPERATIONS and actual_trace.get("dispatched"):
                self.task.side_effects = True
                receipt = {"operation": operation, "requestId": actual_trace.get("request_id"), "dispatched": True,
                    "outcomeUnknown": actual_trace.get("outcome_unknown", False)}
                if "cancellation_status" in actual_trace:
                    receipt["cancellationStatus"] = actual_trace["cancellation_status"]
                self.task.last_action = receipt
                if operation == "gui.transfer_whole_stack":
                    receipt["nativeTransfer"] = dict(result) if isinstance(result, dict) else whole_stack_failure
                if operation == "player.place_block" and self.task.single_block_placement is not None:
                    placement = self.task.single_block_placement
                    placement["placementDispatched"] = True
                    if isinstance(result, dict):
                        placement["nativeReceipt"] = dict(result)
                        receipt["outcomeUnknown"] |= result.get("outcomeUnknown") is True
                    elif placement_failure is not None:
                        placement["nativeFailureCheckpoint"] = placement_failure
                        placement["nativeReceipt"] = placement_failure["knownReceipt"]
                    placement["outcomeUnknown"] |= receipt["outcomeUnknown"]
                    placement["actionReceipt"] = dict(receipt)
                observer = getattr(self.task, "record_native_action_dispatch", None)
                if observer is not None:
                    observer(operation, args, receipt)
                if operation == "gui.widget_action" and args.get("action") == "set_selected" and args.get("selected") == 1:
                    self.task.power_dispatched = True
                if operation == "chat.send" and args.get("message", "").startswith("/give "):
                    self.task.material_grants_dispatched += 1
                if operation == "inventory.supply":
                    if isinstance(result, dict):
                        attempted = result.get("commandsAttempted")
                        if type(attempted) is int and 0 <= attempted <= 8:
                            self.task.material_grants_dispatched += attempted
                        else:
                            self.task.material_grants_count_known = False
                        receipt["nativeSupply"] = {key: _bounded_condition_value(result[key]) for key in
                            ("requestedCount", "commandsAttempted", "commandsAccepted", "complete", "outcomeUnknown") if key in result}
                        if result.get("outcomeUnknown") is True:
                            receipt["outcomeUnknown"] = True
                    elif actual_trace.get("outcome_unknown"):
                        requested = args.get("items")
                        if isinstance(requested, list) and 1 <= len(requested) <= 8:
                            self.task.material_grants_dispatched += len(requested)
                        self.task.material_grants_count_known = False
                        receipt["nativeSupply"] = {"requestedCount": len(requested) if isinstance(requested, list) else None,
                            "attemptedCountKnown": False, "outcomeUnknown": True,
                            "countScope": "Dispatched request item count is a conservative upper bound; command execution is unknown."}
                if operation in ("gui.slot_click", "gui.transfer_whole_stack") and args.get("windowId") == self.task.current_input_window \
                        and args.get("slot", args.get("destinationSlot")) == self.task.current_input_destination:
                    self.task.input_transfer_attempted = True
                moving = self.task.current_inventory_move
                if moving is not None and (operation == "gui.widget_action" and args.get("action") == "activate"
                        and args.get("widgetId") in (moving.get("sourceWidgetId"), moving.get("destinationWidgetId"))
                        or operation == "gui.slot_click" and type(moving.get("nativeWindowId")) is int
                        and args.get("windowId") == moving["nativeWindowId"]
                        and args.get("slot") in (moving.get("sourceNativeContainerSlotId"), moving.get("destinationNativeContainerSlotId"))):
                    moving["movementDispatched"] = True
                    moving["lastRequestId"] = actual_trace.get("request_id")
                    if actual_trace.get("outcome_unknown"):
                        moving["outcomeUnknown"] = True


class _RecipeTask:
    def __init__(self, client: BridgeClient, arguments: dict[str, Any], runner: Callable[..., Awaitable[dict[str, Any]]],
                 query_reader: Callable[..., Awaitable[dict[str, Any]]] | None = None, *, started: float | None = None):
        self.client, self.args = client, arguments
        self.runner, self.query_reader = runner, query_reader
        self.started = time.monotonic() if started is None else started
        self.deadline = self.started + arguments.get("timeout_ms", 30_000) / 1000
        self.credentials = client.configuration.read()
        self.local_lock = anyio.Lock()  # The caller's workflow lock is held for the whole task.
        self.dimension: int | None = arguments.get("dimension")
        self.machine_id: int | None = None
        self.machine_class: str | None = None
        self.single_inventory = all(key in arguments for key in ("machine", "input", "output")) \
            and arguments["machine"] == arguments["input"] == arguments["output"]
        self.single_api_verified = False
        self.single_block_placement: dict[str, Any] | None = None
        if "singleBlockPlacement" in arguments:
            self.single_block_placement = {"status": "pending", "position": dict(arguments["machine"]),
                **arguments["singleBlockPlacement"], "placementAttempted": False, "placementDispatched": False,
                "placementConfirmed": False, "outcomeUnknown": False, "nonAtomic": True,
                "scope": "One native survival placement, exact server item deduction and common public BasicMachine verification; no machine whitelist or action replay."}
        self.registered_multiblock_verified = False
        self.provider_cache = arguments.get("outputMode", "inventory") == "provider_cache"
        self.output_provider_owner: dict[str, Any] | None = None
        self.single_api_proof: dict[str, Any] | None = None
        self.preflight_power_verified = False
        self.single_input_voltage: int | None = None
        self.recipe_decision: dict[str, Any] | None = None
        self.backend_lookup_proof: dict[str, Any] | None = None
        self.backend_voltage_evidence: dict[str, Any] | None = None
        self.configured_ghost = arguments.get("catalystMode", "physical") == "configured_ghost"
        self.ghost_circuit: dict[str, Any] | None = None
        self.ghost_slot_proof: dict[str, Any] | None = None
        self.ghost_previous_circuit: dict[str, Any] | None = None
        self.ghost_configuration_mutated = False
        self.machine_inventory_indices: set[int] = set()
        self.declared_gui_slots: list[dict[str, Any]] = []
        self.input_gui_slot: int | None = arguments.get("inputGuiSlot")
        self.catalyst_gui_slot: int | None = arguments.get("catalystGuiSlot")
        self.catalyst_gui_binding: dict[str, Any] | None = None
        self.input_inventory_owner: dict[str, Any] | None = None
        self.input_gui_binding: dict[str, Any] | None = None
        self.phases: list[dict[str, Any]] = []
        self.side_effects = False
        self.actual_survival = arguments.get("actualSurvival", False)
        self.final_posture: dict[str, Any] | None = None
        self.final_landing_attempted = False
        self.survival_control_player_id: str | None = None
        self.power_dispatched = False
        self.material_grants_dispatched = 0
        self.material_grants_count_known = True
        self.last_action: dict[str, Any] | None = None
        self.tracked_client = _TrackedClient(self)
        self.core_stop_coverage = "limited"
        self.recipe: SimpleRecipe | None = None
        self.opened_gui: dict[str, Any] | None = None
        self.capabilities: Any = None
        self.approach_health: dict[str, Any] | None = None
        self.recipe_query_preflight_verified = False
        self.approach_observations: list[dict[str, Any]] = []
        self.navigation_receipts: list[dict[str, Any]] = []
        self.borrowed_inventory: list[dict[str, Any]] = []
        self.current_inventory_move: dict[str, Any] | None = None
        self.hand_timings = {"handPreparationElapsed_ms": 0, "handRestorationElapsed_ms": 0}
        self.inventory_preparation_mode = "preserve" if arguments.get("restoreInventory", False) \
            or not arguments.get("generateMaterials", False) else arguments.get("inventoryPreparation", "clear")
        self.inventory_clear_confirmed = False
        self.inventory_clear_receipt: dict[str, Any] | None = None
        self.material_supply_mode = "existing_real_inventory"
        self.material_supply_confirmed = False
        self.required_empty_hotbar_slots = 1
        self.single_feed_grants = False
        self.grant_capacity_proof: dict[str, Any] | None = None
        self.inventory_verification_attempted = False
        self.final_inventory_verified = False
        self.final_cursor_empty: bool | None = None
        self.recipe_context_inventory: list[dict[str, Any]] | None = None
        self.recipe_context_items: dict[int, dict[str, Any]] = {}
        self.recipe_context_player_id: str | None = None
        self.recipe_context_guard_pending = False
        self.recipe_context_events: list[dict[str, Any]] = []
        self.recipe_acceptance: dict[str, Any] | None = None
        self.executions_completed = 0
        self.input_transfer_attempted = False
        self.current_input_window: int | None = None
        self.current_input_destination: int | None = None
        self.phase = "preflight"
        self.timing_stage: str | None = "preflight"
        self.timing_started = self.started
        self.timings = {key: 0.0 for key in ("preflight", "recipeDecision", "preparation", "executionAcceptance")}
        self._workflow_discovery = None
        self._workflow_background_required = False
        # Custom runners keep their existing three-argument contract.
        from .workflows import run_workflow
        self._native_workflow_runner = runner is run_workflow
        if self._native_workflow_runner:
            self.runner = self._run_internal_workflow

    async def _run_internal_workflow(self, client: BridgeClient, arguments: dict[str, Any], lock: anyio.Lock) -> dict[str, Any]:
        from .workflows import run_workflow
        if self._workflow_discovery is None:
            # Helpers called before recipe preflight retain ordinary discovery.
            return await run_workflow(client, arguments, lock)
        report = await run_workflow(client, arguments, lock, _discovery=self._workflow_discovery,
            _require_background=self._workflow_background_required)
        # Failed segments may still establish takeover. Cleanup must retain it
        # rather than reacquire protection or replay an uncertain action.
        self._workflow_background_required |= report.get("takeoverEstablished") is True
        return report

    def timing_stage_set(self, stage: str | None) -> None:
        now = time.monotonic()
        if self.timing_stage is not None:
            self.timings[self.timing_stage] += (now - self.timing_started) * 1000
        self.timing_stage, self.timing_started = stage, now

    def remaining(self) -> int:
        left = int((self.deadline - time.monotonic()) * 1000)
        _require(left > 0, "task_deadline", "The declared task deadline expired; no action was replayed.")
        return min(60_000, left)

    def position(self, name: str) -> dict[str, Any]:
        return {**self.args[name], "dimension": self.dimension}

    async def call(self, operation: str, args: dict[str, Any]) -> dict[str, Any]:
        with anyio.fail_after(self.remaining() / 1000):
            result = await self.tracked_client.call(operation, args, expected_credentials=self.credentials)
        _require("sessionId" not in result or result["sessionId"] == self.args["expectedSessionId"],
                 "session_changed", "An observation came from a different client session.")
        return result

    async def workflow(self, phase: str, steps: list[dict[str, Any]], *, emergency: bool = False) -> dict[str, Any]:
        self.phase = phase
        context_power = not emergency and self.recipe_context_inventory is not None and any(
            step.get("kind") == "action" and step.get("id") == "power_on" for step in steps)
        if context_power:
            await self.read_recipe_context_inventory()
            self.queue_recipe_context_guard()
        if self.actual_survival and not emergency:
            steps = self.survival_server_barriers(steps)
        _require(len(steps) <= 32, "native_plan_budget", "The bounded native plan exceeds 32 steps.")
        if self.actual_survival and any(step.get("kind") == "action" for step in steps):
            # A new current-player observation must precede every mutation
            # phase; death-era inventory never becomes a material baseline.
            mutation_snapshot = await self.snapshot()
            self.require_survival_server_control(mutation_snapshot)
            if self.survival_flight and not emergency:
                self.require_flight(mutation_snapshot)
        with operation_phase(phase):
            report = await self.runner(self.tracked_client, {"expectedSessionId": self.args["expectedSessionId"],
                "expectedWorld": self.args["expectedWorld"], "timeout_ms": 2000 if emergency else self.remaining(),
                "max_return_bytes": 262_144, "steps": steps}, self.local_lock)
        self.side_effects |= bool(report.get("sideEffectsPossible"))
        self.power_dispatched |= any(step["id"] == "power_on" and step.get("dispatched") for step in report.get("steps", []))
        phase_report = {key: report[key] for key in ("status", "elapsed_ms", "acknowledgedActions", "dispatchedActions", "outcomeUnknown",
            "failedIndex", "failedPhase", "error", "cancellationStatus", "navigationFailureDiagnostics") if key in report}
        phase_report["phase"] = phase
        phase_report["timingScope"] = "native_workflow"
        from .native_wait import metrics as native_wait_metrics
        wait_metrics = native_wait_metrics(report)
        if wait_metrics:
            phase_report["nativeWaitMetrics"] = wait_metrics
        for checkpoint_key, operation in (("survivalBuildCheckpoint", "structure.survival_build"),
                ("placementCheckpoint", "player.place_block")):
            checkpoint = report.get(checkpoint_key)
            if isinstance(checkpoint, dict) and checkpoint.get("operation") == operation:
                phase_report[checkpoint_key] = checkpoint
        if report.get("status") != "completed":
            phase_report["actionReceipts"] = [{key: step[key] for key in ("id", "status", "dispatched", "requestId", "outcomeUnknown", "completion") if key in step}
                for step in report.get("steps", []) if step.get("dispatched")]
            placement_ids = {step["id"] for step in steps if step.get("kind") == "action" and step.get("tool") == "mcb_place_block"}
            for receipt in phase_report["actionReceipts"]:
                if receipt["id"] not in placement_ids:
                    continue
                observed = next(step for step in report["steps"] if step["id"] == receipt["id"]).get("observation", {}).get("values", {})
                receipt["placementObservation"] = {key: observed[key] for key in (
                    "nativeInvoked", "submitted", "accepted", "localAcceptanceKnown", "outcomeUnknown", "serverConfirmed",
                    "source", "dimension", "playerMode", "target", "attachment", "endingView", "clientPlacementBodyProof") if key in observed}
            if placement_ids:
                wait = next((step for step in report.get("steps", []) if step.get("id") == "placed" and step.get("kind") == "wait"), None)
                if wait is not None:
                    confirmation = {key: wait[key] for key in ("status", "attempts", "matched", "elapsed_ms", "pendingZeroReads") if key in wait}
                    values = wait.get("observation", {}).get("values", {})
                    rows = values.get("results")
                    target = rows[0].get("data", {}) if isinstance(rows, list) and rows and isinstance(rows[0], dict) else values
                    confirmation["lastTarget"] = {key: target[key] for key in ("x", "y", "z", "dimension", "source", "registryName", "metadata", "tileClass") if key in target}
                    phase_report["placementConfirmation"] = confirmation
            if self.last_action is not None and any(receipt.get("requestId") == self.last_action.get("requestId")
                    and receipt.get("outcomeUnknown") is True for receipt in phase_report["actionReceipts"]):
                # Some transport errors express uncertainty on the native runner receipt, not its trace.
                self.last_action["outcomeUnknown"] = True
        self.phases.append(phase_report)
        if report.get("status") != "completed":
            error = report.get("error", {})
            raise BridgeError(error.get("code", "RECIPE_WORKFLOW_FAILED"), error.get("message", "The native workflow failed."),
                              outcome_unknown=bool(report.get("outcomeUnknown")),
                              **{key: phase_report[key] for key in ("survivalBuildCheckpoint", "placementCheckpoint", "navigationFailureDiagnostics") if key in phase_report})
        if context_power:
            await self.read_recipe_context_inventory()
        return report

    def survival_server_barriers(self, steps: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Poll real tick receipts after close/selection; never accept older cache."""
        result = []
        for step in steps:
            tool = step.get("tool") if step.get("kind") == "action" else None
            if tool not in ("mcb_close_gui", "mcb_hotbar"):
                result.append(step)
                continue
            prefix = step["id"]
            before, fresh, ready = (prefix + suffix for suffix in ("_sync_before", "_sync_fresh", "_sync_ready"))
            result.append(_read(before, "mcb_snapshot", fields=["serverControl"]))
            result.append(_assert(prefix + "_sync_available", before, "serverControl.available", True))
            result.append(step)
            wait = _wait(fresh, "mcb_snapshot", {}, "serverControl.sampleId", None, operator="gt", fields=["serverControl"])
            wait["condition"].pop("value")
            wait["condition"]["valueFrom"] = {"step": before, "path": "serverControl.sampleId"}
            result.append(wait)
            expected = {"available": True, "source": "integrated_server_tick", "sessionId": self.args["expectedSessionId"],
                "dimension": self.dimension}
            if self.survival_control_player_id is not None:
                expected["playerId"] = self.survival_control_player_id
            if tool == "mcb_close_gui":
                expected["openContainer.isInventoryContainer"] = True
            else:
                expected["selectedHotbarSlot"] = step["args"]["slot"]
            result.append(_wait(ready, "mcb_snapshot", {}, "serverControl", expected, operator="fields_equal",
                fields=["serverControl"]))
            assertion = _assert(prefix + "_sync_current", ready, "serverControl.sampleId", None, "gt")
            assertion["condition"].pop("value")
            assertion["condition"]["valueFrom"] = {"step": before, "path": "serverControl.sampleId"}
            result.append(assertion)
            if tool == "mcb_close_gui":
                result.append(_assert(prefix + "_sync_client", ready, "serverControl.clientContainer.isInventoryContainer", True))
                same_window = _assert(prefix + "_sync_window", ready, "serverControl.clientContainer.windowId", None)
                same_window["condition"].pop("value")
                same_window["condition"]["valueFrom"] = {"step": ready, "path": "serverControl.openContainer.windowId"}
                result.append(same_window)
            else:
                result.append(_assert(prefix + "_sync_selection", ready, "serverControl.selectionSynchronized", True))
        return result

    def require_survival_server_control(self, snapshot: dict[str, Any]) -> None:
        if self.actual_survival:
            self.require_server_control(snapshot)

    def require_server_control(self, snapshot: dict[str, Any]) -> None:
        """Bind fresh actual server identity for survival actions or creative clearing."""
        control = snapshot.get("serverControl")
        _require(isinstance(control, dict) and control.get("available") is True
            and control.get("source") == "integrated_server_tick" and control.get("sessionId") == self.args["expectedSessionId"]
            and type(control.get("dimension")) is int and control["dimension"] == self.dimension
            and self.args["expectedWorld"] in (control.get("worldName"), control.get("worldFolder"))
            and type(control.get("sampleId")) is int and control["sampleId"] >= 0
            and type(control.get("ageMillis")) in (int, float) and 0 <= control["ageMillis"] <= 1000
            and isinstance(control.get("playerId"), str) and bool(control["playerId"]),
            "survival_server_control_unavailable", "Fresh public server tick/container/selection receipts are required before guarded inventory or GUI mutation.")
        _require(self.survival_control_player_id is None or control["playerId"] == self.survival_control_player_id,
            "survival_player_identity_mismatch", "Every current server control sample must retain this task's actual player UUID; this does not prove entity continuity across respawn.")
        self.survival_control_player_id = control["playerId"]

    @staticmethod
    def value(report: dict[str, Any], step_id: str, path: str) -> Any:
        entries = [step for step in report.get("steps", []) if step["id"] == step_id]
        values = entries[0].get("observation", {}).get("values", {}) if len(entries) == 1 else {}
        _require(path in values, "workflow_projection_unavailable", "A required native observation was omitted; no value was guessed.")
        return values[path]

    async def snapshot(self) -> dict[str, Any]:
        result = await self.call("client.snapshot", {})
        self.validate_snapshot(result)
        return result

    def validate_snapshot(self, result: dict[str, Any]) -> None:
        _require(result.get("sessionId") == self.args["expectedSessionId"] and result.get("profile") == "developer"
                 and result.get("worldLoaded") is True and self.args["expectedWorld"] in
                 (result.get("world", {}).get("worldName"), result.get("world", {}).get("worldFolder"))
                 and (self.dimension is None or result.get("world", {}).get("dimension") == self.dimension),
                 "local_developer_context_required", "The current session and loaded local developer world must match the task.")
        self.require_live_player(result)

    @staticmethod
    def require_live_player(snapshot: dict[str, Any]) -> None:
        player = snapshot.get("player")
        health = player.get("health") if isinstance(player, dict) else None
        _require(type(health) in (int, float) and math.isfinite(health) and health > 0
            and player.get("isDead", False) is False
            and snapshot.get("screen", "") != "net.minecraft.client.gui.GuiGameOver",
            "live_player_required", "A fresh current living player with positive health is required before inventory or native mutation; death-era inventory is unavailable.")

    async def read_with_snapshot(self, operation: str, *, require_background: bool = True) -> tuple[dict[str, Any], dict[str, Any]]:
        from .workflows import _context_capabilities, _guard
        reads, _ = _context_capabilities(self.approach_health or {})
        if operation not in reads:
            snapshot = await self.snapshot()
            return await self.call(operation, {}), snapshot
        with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
            "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": require_background}):
            value = await self.call(operation, {})
        evidence = response_context(value)
        _require(isinstance(evidence, dict) and type(evidence.get("version")) is int and evidence["version"] == 1
                 and evidence.get("operation") == operation and evidence.get("phase") == "after"
                 and isinstance(evidence.get("snapshot"), dict), "fresh_read_context_unavailable",
                 "An advertised context read requires its own current typed after-snapshot; no older guard is reused.")
        snapshot = evidence["snapshot"]
        self.validate_snapshot(snapshot)
        _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], require_background)
        return value, snapshot

    def recipe_context_mutable_indices(self) -> set[int]:
        resources = getattr(self, "fluid_resources", None)
        return set(resources.player_indices) if resources is not None else set()

    @staticmethod
    def recipe_context_public_rows(slots: list[dict[str, Any]]) -> list[dict[str, Any]]:
        _require(len(slots) == 41 and {row["index"] for row in slots} == set(range(41)),
            "recipe_context_inventory_unavailable", "Retained context requires every actual player slot, including armor.")
        result = []
        for row in sorted(slots, key=lambda row: row["index"]):
            item = row["item"]
            _require(item is None or isinstance(item, dict) and isinstance(item.get("registryName"), str)
                and type(item.get("metadata")) is int and type(item.get("count")) is int and item["count"] > 0
                and type(item.get("hasTag")) is bool, "recipe_context_inventory_unavailable",
                "Every observed occupied player slot needs typed complete public identity and positive count.")
            result.append({"index": row["index"], "item": None if item is None else
                {key: item[key] for key in ("registryName", "metadata", "count", "hasTag")}})
        return result

    async def initialize_recipe_context(self) -> None:
        if self.inventory_preparation_mode != "preserve":
            return
        observed, _ = await self.read_with_snapshot("inventory.inspect", require_background=False)
        slots = inventory_slots(observed, "")
        mutable = self.recipe_context_mutable_indices()
        if not any(row["index"] not in mutable and isinstance(row["item"], dict)
            and row["item"].get("hasTag") is True for row in slots):
            return
        rows = await self.read_recipe_context_inventory(initialize=True, require_background=False)
        _require("cursorItem" in observed and observed["cursorItem"] is None
            and self.recipe_context_public_rows(slots) == self.recipe_context_public_rows(rows),
            "recipe_context_initial_inventory_changed", "Both full-NBT inventories must agree with the original complete public inventory before any action.")
        self.recipe_context_inventory = copy.deepcopy(rows)
        self.recipe_context_items = {row["index"]: copy.deepcopy(row["item"]) for row in rows
            if row["index"] not in mutable and row["item"] is not None and row["item"]["hasTag"] is True}

    async def read_recipe_context_inventory(self, *, initialize: bool = False, require_background: bool = True,
        expected: list[dict[str, Any]] | None = None, mutable_indices: set[int] | None = None) -> list[dict[str, Any]] | None:
        if self.recipe_context_inventory is None and not initialize:
            return None
        from .construction_tasks import _context_inventory_rows
        from .workflows import _context_capabilities, _guard
        reads, _ = _context_capabilities(self.approach_health or {})
        capabilities = self.capabilities or []
        _require(("baubles.inspect" in capabilities if isinstance(capabilities, list) else capabilities.get("baubles.inspect") is True)
            and "baubles.inspect" in reads, "recipe_context_full_nbt_unavailable",
            "Retained tagged context requires advertised guarded complete full-NBT reads; tag presence cannot replace identity.")
        expected = self.recipe_context_inventory if expected is None else expected
        mutable_indices = mutable_indices or set()
        _require(mutable_indices <= self.recipe_context_mutable_indices() and not mutable_indices & self.recipe_context_items.keys(),
            "recipe_context_mutation_unavailable", "Only explicitly declared fluid-container checkpoints can adopt a transformed full native identity.")
        paired = []
        for side in ("client", "server"):
            with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"], "expectedProfile": "developer",
                "expectedWorld": self.args["expectedWorld"], "requireBackground": require_background}):
                value = await self.call("baubles.inspect", {"source": side, "includePlayerInventory": True, "limit": 1})
            evidence = response_context(value)
            _require(isinstance(evidence, dict) and type(evidence.get("version")) is int and evidence["version"] == 1
                and evidence.get("operation") == "baubles.inspect" and evidence.get("phase") == "after"
                and isinstance(evidence.get("snapshot"), dict), "recipe_context_read_context_unavailable",
                "Every full-NBT read needs its own fresh typed AFTER context.")
            snapshot = evidence["snapshot"]
            self.validate_snapshot(snapshot)
            _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], require_background)
            self.require_flight(snapshot)
            self.require_server_control(snapshot)
            player_id = snapshot["serverControl"]["playerId"]
            _require(self.recipe_context_player_id is None or player_id == self.recipe_context_player_id,
                "recipe_context_actor_changed", "The complete player inventories must retain this exact actual guarded actor.")
            self.recipe_context_player_id = player_id
            _require(value.get("supported") is True and value.get("readOnly") is True
                and value.get("source") == ("integrated_server" if side == "server" else "client")
                and value.get("sessionId") == self.args["expectedSessionId"] and value.get("playerId") == player_id
                and type(value.get("dimension")) is int and value["dimension"] == self.dimension
                and "cursorItem" in value and value["cursorItem"] is None
                and type(value.get("creative")) is bool and value["creative"] is snapshot["player"].get("creative")
                and type(value.get("flying")) is bool and value["flying"] is snapshot["player"].get("flying")
                and (side != "server" or isinstance(snapshot.get("world", {}).get("worldFolder"), str)
                    and value.get("worldFolder") == snapshot["world"]["worldFolder"]),
                "recipe_context_inventory_unavailable", "Full inventories require exact source, actor, session, world, dimension, posture and empty real cursor.")
            try:
                rows = _context_inventory_rows(value.get("playerInventory"))
            except BridgeError as error:
                raise _Unavailable("recipe_context_inventory_unavailable", "The complete native player inventory is missing or malformed.") from error
            _require(all(context_equal(rows[index]["item"], item) for index, item in self.recipe_context_items.items()),
                "recipe_context_changed", "Every unrelated tagged stack must retain its original slot, registry, metadata, count and complete native NBT.")
            _require(expected is None or context_equal([row for row in rows if row["index"] not in mutable_indices],
                [row for row in expected if row["index"] not in mutable_indices]), "recipe_context_ledger_changed",
                "Every full-NBT slot must equal only this task's confirmed finite inventory changes.")
            paired.append(rows)
        _require(context_equal(paired[0], paired[1]), "recipe_context_client_server_mismatch",
            "Complete client/server player identities, counts and full native NBT must agree.")
        return paired[1]

    def recipe_context_player_slots(self, slots: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if self.recipe_context_inventory is None:
            return slots
        public = self.recipe_context_public_rows(slots)
        _require(all(public[index]["item"] == {key: item[key] for key in ("registryName", "metadata", "count", "hasTag")}
            for index, item in self.recipe_context_items.items()), "recipe_context_changed",
            "A protected tagged player stack cannot be hidden from ordinary resource accounting after public drift.")
        return [row for row in slots if row["index"] not in self.recipe_context_items]

    def queue_recipe_context_guard(self) -> None:
        if self.recipe_context_inventory is not None:
            _require(not self.recipe_context_guard_pending, "recipe_context_pending_mutation",
                "An earlier inventory mutation is still unconfirmed and cannot be replayed.")
            self.recipe_context_guard_pending = True

    async def accept_recipe_context(self, expected: list[dict[str, Any]], player: dict[str, Any], label: str,
        *, mutable_indices: set[int] | None = None) -> None:
        previous = self.recipe_context_inventory
        rows = await self.read_recipe_context_inventory(expected=expected, mutable_indices=mutable_indices)
        _require("cursorItem" in player and player["cursorItem"] is None
            and self.recipe_context_public_rows(inventory_slots(player, "")) == self.recipe_context_public_rows(rows),
            "recipe_context_public_inventory_changed", "The accepted complete public inventory must agree with both fresh full-NBT inventories.")
        self.recipe_context_inventory = copy.deepcopy(rows)
        self.recipe_context_events.append({"phase": label, "changedIndices": [index for index in range(41) if not context_equal(previous[index], rows[index])],
            "beforeSha256": json_sha256(previous), "afterSha256": json_sha256(rows), "completeDualFullNbtVerified": True})

    async def account_recipe_context_item(self, index: int, identity: dict[str, Any], amount: int,
        player: dict[str, Any], label: str) -> None:
        if self.recipe_context_inventory is None:
            return
        expected = copy.deepcopy(self.recipe_context_inventory)
        old = expected[index]["item"]
        _require(index not in self.recipe_context_items and index not in self.recipe_context_mutable_indices()
            and (old is None and amount > 0 or _same(old, identity) and old["nbtIdentity"] == ""),
            "recipe_context_source_changed", "Only an exact ordinary untagged source/destination may account for a finite recipe item delta.")
        _require(type(amount) is int and amount != 0, "recipe_context_quantity_unavailable",
            "A confirmed item checkpoint needs its exact nonzero finite delta.")
        count = (old["count"] if old is not None else 0) + amount
        _require(count >= 0, "recipe_context_quantity_unavailable",
            "A confirmed item checkpoint needs its exact nonzero finite delta without negative remainder.")
        expected[index]["item"] = {**identity, "count": count, "hasTag": False, "nbtIdentity": ""} if count else None
        await self.accept_recipe_context(expected, player, label)

    async def account_recipe_context_move(self, source: int, destination: int, player: dict[str, Any], label: str) -> None:
        if self.recipe_context_inventory is None:
            return
        expected = copy.deepcopy(self.recipe_context_inventory)
        item = expected[source]["item"]
        _require(source not in self.protected_player_indices() and destination not in self.protected_player_indices()
            and isinstance(item, dict) and item["hasTag"] is False and expected[destination]["item"] is None,
            "recipe_context_move_changed", "Only a confirmed whole ordinary stack may move into an observed empty unprotected player slot.")
        expected[destination]["item"], expected[source]["item"] = item, None
        await self.accept_recipe_context(expected, player, label)

    async def read_owner_phase(self, positions: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]] | None:
        """Read complete owners and player once for immediate checks before the next action.

        Callers consume these exact raw views only within this no-action phase;
        nothing is retained for another GUI, mutation, polling epoch or wait.
        Older bridges keep their original read chain without a speculative RPC.
        """
        from .workflows import _background, _guard, _inspect_many_player_inventory_available, _validate_player_inventory_observation
        health = self.approach_health or {}
        if self._workflow_discovery is not None:
            health = self._workflow_discovery.bind(self.tracked_client, self.credentials, self.args["expectedSessionId"])
        if not _inspect_many_player_inventory_available(health):
            return None
        capable = health.get("capabilities", [])
        _require("world.inspect_many" in capable if isinstance(capable, list) else isinstance(capable, dict)
                 and capable.get("world.inspect_many") is True, "combined_owner_read_unavailable",
                 "Advertised combined owner reads require the actual operation capability.")
        # Initial preparation can precede the first workflow's takeover. Keep
        # its old chain until protection is known; this hint only selects the
        # route, while every actual batch still requires its own fresh guard.
        if not self._workflow_background_required and not _background((self.approach_health or {}).get("background")):
            return None
        _require(positions and all(type(point.get("dimension")) is int and point["dimension"] == self.dimension
                 and all(type(point.get(axis)) is int for axis in ("x", "y", "z")) for point in positions),
                 "combined_owner_read_position_invalid", "Every same-phase owner must retain the exact current typed dimension and position.")
        unique = list(dict.fromkeys(tuple(point[axis] for axis in ("x", "y", "z")) for point in positions))
        if len(unique) > 16:
            # Larger legitimate declarations keep the complete original chain;
            # no partial batch or multi-epoch composite receipt is invented.
            return None
        args = {"positions": [dict(zip(("x", "y", "z"), point)) for point in unique], "dimension": self.dimension,
            "compact": False, "includePlayerInventory": True, "playerInventoryByteBudget": 131_072,
            "observationByteBudget": 1_048_576}
        with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
            "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
            value = await self.call("world.inspect_many", args)
        evidence = response_context(value)
        _require(isinstance(evidence, dict) and set(evidence) == {"version", "operation", "phase", "snapshot"}
                 and type(evidence.get("version")) is int and evidence["version"] == 1
                 and evidence.get("operation") == "world.inspect_many" and evidence.get("phase") == "after"
                 and isinstance(evidence.get("snapshot"), dict), "fresh_read_context_unavailable",
                 "A combined owner read requires its own complete current AFTER context; no nested point context is invented.")
        self.validate_snapshot(evidence["snapshot"])
        _guard(evidence["snapshot"], self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        _validate_player_inventory_observation(value, args, evidence)
        owners = {point: row["data"] for point, row in zip(unique, value["results"])}
        return [owners[tuple(point[axis] for axis in ("x", "y", "z"))] for point in positions], value["playerInventory"]

    async def clear_player_inventory(self) -> None:
        _require(self.args.get("generateMaterials", False) is True and not self.actual_survival,
            "inventory_clear_not_authorized", "Clearing requires explicit creative material generation; existing or survival inventory is preserved.")
        capable = "chat.send" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("chat.send") is True
        _require(capable, "clear_command_unavailable", "The explicit developer chat command capability is required before clearing test inventory.")
        snapshot = await self.snapshot()
        name = snapshot.get("player", {}).get("name")
        _require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is not None,
            "player_command_identity_unavailable", "Clear only the exact current observed test player.")
        self.require_server_control(snapshot)
        self.inventory_clear_receipt = {"status": "pending", "containerAckConfirmed": False,
            "clearCommandDispatched": False, "clearConfirmed": False, "outcomeUnknown": False,
            "replayAllowed": False, "scope": "Fresh server tick and both actual inventory containers before one clear; complete actual inventory emptiness afterward. Command acceptance alone is insufficient, and unknown or unconfirmed results are never replayed."}
        steps = [_read("before", "mcb_inventory", fields=["cursorItem", "totalSlots", "truncated"]),
            _assert("cursor_empty", "before", "cursorItem", None),
            _assert("coverage_complete", "before", "truncated", False)]
        steps.extend(self.survival_server_barriers([_action("close_for_clear", "mcb_close_gui", {})]))
        steps.extend([
            _action("clear", "mcb_chat", {"message": "/clear " + name}),
            _wait("cleared", "mcb_inventory", {}, "slots", True, operator="inventory_empty",
                fields=["slots", "cursorItem", "totalSlots", "truncated"]),
            _assert("cursor_still_empty", "cleared", "cursorItem", None)])
        try:
            report = await self.workflow("clear_test_inventory", steps)
        except (BridgeError, TimeoutError) as error:
            phase = self.phases[-1] if self.phases and self.phases[-1].get("phase") == "clear_test_inventory" else {}
            dispatched = any(receipt.get("id") == "clear" and receipt.get("dispatched") is True
                for receipt in phase.get("actionReceipts", []))
            unknown = phase.get("outcomeUnknown") is True or isinstance(error, BridgeError) and error.details.get("outcome_unknown") is True
            self.inventory_clear_receipt.update(status="outcome_unknown" if unknown else "refused_or_unconfirmed",
                clearCommandDispatched=dispatched, containerAckConfirmed=dispatched, outcomeUnknown=unknown,
                confirmationBasis="A dispatched clear follows all guarded close assertions; failure does not prove empty inventory.")
            if phase:
                phase["inventoryClearReceipt"] = dict(self.inventory_clear_receipt)
            raise
        before = self.value(report, "close_for_clear_sync_before", "serverControl")
        ready = self.value(report, "close_for_clear_sync_ready", "serverControl")
        self.inventory_clear_confirmed = True
        self.inventory_clear_receipt.update(status="confirmed", containerAckConfirmed=True,
            clearCommandDispatched=True, clearConfirmed=True, baselineSampleId=before["sampleId"],
            acknowledgementSampleId=ready["sampleId"], playerId=ready["playerId"],
            serverWindowId=ready["openContainer"]["windowId"], clientWindowId=ready["clientContainer"]["windowId"])
        self.phases[-1]["clearedSlotCoverage"] = self.value(report, "cleared", "totalSlots")
        self.phases[-1]["inventoryClearReceipt"] = dict(self.inventory_clear_receipt)

    def validate_multiblock_backend_lookup(self, data: dict[str, Any], map_identity: tuple[str, int]) -> None:
        _require(self.registered_multiblock_verified and self.recipe_query_preflight_verified and not self.single_inventory,
            "backend_machine_scope_unsupported", "A multiblock backend task requires this task's complete formed, stopped, registered port preflight.")
        _require(isinstance(map_identity[0], str) and 1 <= len(map_identity[0]) <= 256,
            "backend_multiblock_recipe_map_unavailable", "The actual public current map needs its exact bounded nonempty name.")
        _require(data.get("source") == "integrated_server" and data.get("lookupSource") == "public_backend_find"
            and data.get("sessionId") == self.args["expectedSessionId"]
            and isinstance(data.get("sessionId"), str) and type(data.get("dimension")) is int and data["dimension"] == self.dimension,
            "backend_multiblock_context_unavailable", "The native lookup must retain this exact session, dimension, authoritative server source and public backend lookup source.")
        query, machine = data.get("query"), data.get("machine")
        _require(isinstance(query, dict) and isinstance(machine, dict), "backend_multiblock_query_unavailable",
            "The multiblock lookup needs complete typed native query and actual controller evidence.")
        voltage, amperage = query.get("inputVoltage"), query.get("mapAmperage")
        _require(type(voltage) is int and 0 < voltage <= LONG_MAX
            and type(amperage) is int and amperage == map_identity[1] and voltage <= LONG_MAX // amperage
            and type(query.get("nativeVoltageBound")) is int and query["nativeVoltageBound"] == voltage * amperage
            and type(machine.get("inputVoltage")) is int and machine["inputVoltage"] == voltage,
            "backend_multiblock_voltage_unavailable", "Actual controller voltage, current map amperage and their non-overflowing native bound must agree exactly.")
        evidence = query.get("inputVoltageEvidence")
        _require(isinstance(evidence, dict) and type(evidence.get("schemaVersion")) is int and evidence["schemaVersion"] == 1
            and evidence.get("source") == "public_multiblock_controller_api" and evidence.get("scope") == "native_lookup_bound_only"
            and evidence.get("apiClass") == COMMON_MULTIBLOCK_API and evidence.get("apiMethod") == "getMaxInputVoltage"
            and type(evidence.get("value")) is int and evidence["value"] == voltage,
            "backend_multiblock_voltage_evidence_unavailable", "Only the actual common public multiblock controller getMaxInputVoltage lookup evidence is accepted.")
        owner = evidence.get("owner")
        expected_owner = {**self.position("machine"), "metaTileId": self.machine_id,
            "metaClass": self.machine_class, "currentRecipeMap": map_identity[0]}
        _require(isinstance(owner, dict) and set(owner) == set(expected_owner)
            and all(type(owner.get(key)) is type(value) and owner[key] == value for key, value in expected_owner.items())
            and type(machine.get("metaTileId")) is int and machine["metaTileId"] == self.machine_id
            and isinstance(machine.get("metaClass"), str) and machine["metaClass"] == self.machine_class,
            "backend_multiblock_voltage_owner_changed", "Public voltage evidence must bind the actual declared controller ID/class, coordinates, dimension and current map.")
        self.validate_backend_resources(data)
        self.backend_voltage_evidence = {"actualInputVoltage": voltage, "mapName": map_identity[0],
            "mapAmperage": amperage, "nativeVoltageBound": query["nativeVoltageBound"],
            "inputVoltageEvidence": {key: evidence[key] for key in ("schemaVersion", "source", "scope", "apiClass", "apiMethod", "value", "owner")}}

    def validate_backend_resources(self, data: dict[str, Any]) -> None:
        query = data["query"]
        inputs = query.get("inputs")
        declared = self.args["backendInputs"]
        _require(isinstance(inputs, list) and len(inputs) == len(declared)
            and all(isinstance(actual, dict) and actual.get("hasTag") is False
                and all(type(actual.get(key)) is type(wanted[key]) and actual[key] == wanted[key]
                    for key in ("registryName", "metadata", "count")) for actual, wanted in zip(inputs, declared))
            and query.get("fluids") == [] and isinstance(query.get("fluids"), list)
            and "specialSlot" in query and query["specialSlot"] is None
            and query.get("caching") is False and query.get("ignoreStackSizes") is False,
            "backend_multiblock_input_copy_unavailable", "The native non-consuming lookup must echo the exact finite ordered untagged item inputs and explicitly empty fluids/special slot.")
        validation = data.get("validation")
        _require(not data["matched"] or isinstance(validation, dict)
            and all(validation.get(key) is True for key in ("inputMatch", "enabled", "notFake", "voltageCompatible", "outputChecked", "outputMatch"))
            and validation.get("outputUnknown") is False,
            "backend_multiblock_candidate_unvalidated", "The first candidate must have complete affirmative native input/count/flags/output/voltage validation.")

    def backend_query(self) -> dict[str, Any]:
        return {**self.position("machine"), "mode": "backend_match", "inputs": self.args["backendInputs"],
                "output": {"source": "registry", **self.args["outputItem"]}}

    def parse_candidate(self, candidate: dict[str, Any]) -> SimpleRecipe:
        return supported_recipe(candidate, self.args)

    def backend_required_inputs(self, selected: SimpleRecipe) -> list[tuple[dict[str, Any], int]]:
        return [(selected.material, selected.material_count)] + ([(selected.catalyst, 1)] if selected.catalyst else [])

    async def select_recipe(self) -> SimpleRecipe:
        query_started = time.monotonic()
        self.phase = "recipe_query"
        query_report: dict[str, Any] = {"phase": "recipe_query", "status": "running", "elapsed_ms": 0,
            "timingScope": "registered_recipe_decision", "selection": "first_supported", "uniqueRecipeClaimed": False,
            "executionValidated": False}
        self.phases.append(query_report)
        query: dict[str, Any] = {**self.position("machine"), "limit": 16}
        if "outputItem" in self.args:
            query["output"] = {"source": "registry", **self.args["outputItem"]}
        backend = self.args.get("recipeLookup", "catalog") == "backend_match"
        if backend:
            _require(self.single_api_verified or self.registered_multiblock_verified and self.recipe_query_preflight_verified,
                     "backend_machine_scope_unsupported", "This finite backend task requires an observed common BasicMachine or complete formed, stopped, registered multiblock port preflight.")
            query = self.backend_query()
        elif "material" in self.args:
            query["input"] = {"source": "registry", **self.args["material"]}
        query_requested = {"machine": self.position("machine"), "input": query.get("input"), "output": query.get("output")}
        if backend:
            query_requested.update(mode="backend_match", inputs=query["inputs"])
            if "fluidInputs" in query:
                query_requested["fluidInputs"] = query["fluidInputs"]
        query_report.update(queryRequested=query_requested, observedCandidateCount=0, lastQueryPage=None)
        reasons: list[str] = []
        rejected_candidates: list[dict[str, Any]] = []
        map_identity: tuple[str, int] | None = None
        for page_number in range(1, self.args.get("maxRecipePages", 16) + 1):
            query_report["pages"] = page_number
            if self.query_reader is None:
                data = await self.call("recipes.query", query)
            else:
                with anyio.fail_after(self.remaining() / 1000):
                    data = await self.query_reader(self.tracked_client, query)
                _require("sessionId" not in data or data["sessionId"] == self.args["expectedSessionId"],
                         "session_changed", "Recipe enumeration came from a different session.")
            query_report["elapsed_ms"] = int((time.monotonic() - query_started) * 1000)
            if backend:
                self.backend_lookup_proof = _backend_lookup_proof(data)
            _require(data.get("dimension") == self.dimension and data.get("filterIncomplete") is False,
                     "recipe_filter_incomplete", "The actual recipe filter/dimension must have complete known matching semantics.")
            if self.single_api_verified or backend:
                observed_map, observed_machine = data.get("recipeMap", {}), data.get("machine", {})
                _require(isinstance(observed_map, dict) and isinstance(observed_map.get("name"), str)
                         and type(observed_map.get("amperage")) is int and 1 <= observed_map["amperage"] <= 2**31 - 1
                         and isinstance(observed_machine, dict) and observed_machine.get("metaTileId") == self.machine_id
                         and observed_machine.get("metaClass") == self.machine_class
                         and observed_machine.get("currentRecipeMap") == observed_map["name"]
                         and all(type(observed_machine.get(axis)) is int and observed_machine[axis] == self.args["machine"][axis]
                             for axis in ("x", "y", "z")), "single_machine_recipe_map_unavailable",
                         "Voltage selection requires this actual machine's typed current public RecipeMap and amperage.")
                current_map = (observed_map["name"], observed_map["amperage"])
                _require(map_identity is None or current_map == map_identity, "single_machine_recipe_map_changed",
                         "The current public BasicMachine map and amperage changed during recipe enumeration.")
                map_identity = current_map
            recipes = data.get("recipes")
            _require(isinstance(recipes, list) and len(recipes) <= 16, "recipe_page_unavailable", "A bounded registered recipe page is required.")
            if backend:
                _require(data.get("mode") == "backend_match" and data.get("lookupSource") == "public_backend_find"
                         and data.get("lookupComplete") is True and data.get("registeredCatalogComplete") is False
                         and data.get("executionValidated") is False and data.get("hasMore") is False
                         and "nextCursor" in data and data["nextCursor"] is None and type(data.get("matched")) is bool
                         and len(recipes) == int(data["matched"]), "backend_lookup_proof_unavailable",
                         "The explicit native backend lookup must preserve its one bounded first result, input-copy provenance and non-catalog scope.")
                _require(not recipes or data.get("candidateValidated") is True, "backend_candidate_unvalidated",
                         "The first native candidate lacks known public input-count, output and native voltage/amperage validation; no alternate candidate is selected.")
                if not self.single_api_verified:
                    self.validate_multiblock_backend_lookup(data, map_identity)
                elif self.args.get("recipeMode") == "public_multi" and (self.args.get("fluidContainerInputs") or self.args.get("fluidContainerOutputs")):
                    self.validate_backend_resources(data)
            query_report["observedCandidateCount"] += len(recipes)
            query_report["lastQueryPage"] = {"page": page_number, "observedCandidateCount": len(recipes),
                "complete": data.get("complete") if type(data.get("complete")) is bool else None,
                "hasMore": data.get("hasMore") if type(data.get("hasMore")) is bool else None}
            for candidate_index, recipe in enumerate(recipes):
                try:
                    selected = self.parse_candidate(recipe)
                    if backend:
                        declared = [({key: entry[key] for key in ("registryName", "metadata")}, entry["count"])
                            for entry in self.args["backendInputs"]]
                        required = self.backend_required_inputs(selected)
                        _require(len(declared) == len(required) and all(declared.count(entry) == 1 for entry in required),
                                 "backend_declared_feed_mismatch", "The explicit backend templates must exactly describe the finite real material feed and optional one retained catalyst.")
                    if self.single_api_verified:
                        _require(type(self.single_input_voltage) is int and self.single_input_voltage > 0,
                                 "single_machine_input_voltage_unavailable", "BasicMachine recipe selection requires actual typed public input-voltage evidence.")
                        _require(selected.raw_eut <= self.single_input_voltage * map_identity[1],
                                 "single_machine_recipe_voltage_exceeded",
                                 "The raw candidate exceeds this BasicMachine's actual input voltage times the current public map amperage.")
                    elif backend:
                        _require(self.backend_voltage_evidence is not None
                            and selected.raw_eut <= self.backend_voltage_evidence["nativeVoltageBound"],
                            "backend_multiblock_recipe_voltage_exceeded", "The raw candidate exceeds the actual public controller voltage times current map amperage.")
                except _Unavailable as error:
                    if error.details["reason"] not in reasons:
                        reasons.append(error.details["reason"])
                    if len(rejected_candidates) < 4:
                        rejection = {"page": page_number, "candidateIndex": candidate_index, "reason": error.details["reason"]}
                        if isinstance(recipe, dict):
                            if isinstance(recipe.get("category"), str):
                                rejection["category"] = recipe["category"][:128]
                            if type(recipe.get("categoryRecipeIndex")) is int and 0 <= recipe["categoryRecipeIndex"] <= 2**31 - 1:
                                rejection["categoryRecipeIndex"] = recipe["categoryRecipeIndex"]
                        if "rejectedConditions" in error.details:
                            rejection["rejectedConditions"] = error.details["rejectedConditions"]
                        rejected_candidates.append(rejection)
                    continue
                query_report.update(status="completed", elapsed_ms=int((time.monotonic() - query_started) * 1000),
                    enumerationComplete=data.get("complete") is True, rejectedReasons=reasons[:8], rejectedRecipeCandidates=rejected_candidates)
                self.recipe_decision = {"category": selected.category, "categoryRecipeIndex": selected.category_index,
                    "material": selected.material, "materialPerExecution": selected.material_count,
                    "output": selected.output, "outputPerExecution": selected.output_count, "catalyst": selected.catalyst,
                    "rawEUt": selected.raw_eut, "rawDurationTicks": selected.raw_duration_ticks,
                    "candidatePage": page_number, "candidateIndex": candidate_index,
                    "machine": self.position("machine"), "source": "Current recipes.query raw candidate selected within this task; execution remains independently verified."}
                if self.single_api_verified:
                    self.recipe_decision["voltageEvidence"] = {"actualInputVoltage": self.single_input_voltage,
                        "mapName": map_identity[0], "mapAmperage": map_identity[1],
                        "rawVoltageCompatible": True,
                        "source": "This task's fresh machine.inspect state.inputVoltage public BaseMetaTileEntity.getInputVoltage and current recipes.query RecipeMap amperage."}
                if backend:
                    if not self.single_api_verified:
                        self.recipe_decision["voltageEvidence"] = {**self.backend_voltage_evidence, "rawVoltageCompatible": True}
                    self.recipe_decision.pop("categoryRecipeIndex")
                    self.recipe_decision.update(candidateOrdinal=0, candidateSource="public_backend_find")
                return selected
            if data.get("hasMore") is not True:
                break
            cursor = data.get("nextCursor")
            _require(isinstance(cursor, str) and bool(cursor), "recipe_cursor_unavailable", "An incomplete page has no one-use continuation cursor.")
            query = {"cursor": cursor, "dimension": self.dimension, "limit": 16}
        query_report.update(status="unavailable", elapsed_ms=int((time.monotonic() - query_started) * 1000),
                            rejectedReasons=reasons[:8], rejectedRecipeCandidates=rejected_candidates)
        message = "No complete supported candidate was observed within the declared recipe pages"
        message += ": " + ", ".join(reasons[:8]) if reasons else "; the query returned no candidates for the requested input/output filters within these pages."
        raise _Unavailable("no_observed_supported_recipe", message, rejectedRecipeCandidates=rejected_candidates,
                           queryRequested=query_requested, observedCandidateCount=query_report["observedCandidateCount"],
                           lastQueryPage=query_report["lastQueryPage"])

    @property
    def survival_flight(self) -> bool:
        return self.actual_survival and self.args.get("survivalOperationPosture", "grounded") == "ability_flight"

    @property
    def interaction_flying(self) -> bool:
        return not self.actual_survival or self.survival_flight

    def validate_survival_posture_declaration(self) -> None:
        posture = self.args.get("survivalOperationPosture", "grounded")
        _require(posture in ("grounded", "ability_flight") and (posture != "ability_flight" or self.actual_survival
            and self.args.get("movementStrategy") == "ability_flight"), "survival_flight_scope_unsupported",
            "Airborne survival operations require explicit actualSurvival and ability_flight strategy/posture.")
        _require(not any(key in self.args for key in ("finishFlying", "finalLandingPosition")) or self.survival_flight,
            "survival_flight_finish_unsupported", "An ending flight/landing declaration only belongs to explicit airborne survival operations.")
        if self.survival_flight:
            finish = self.args.get("finishFlying", True)
            landing = self.args.get("finalLandingPosition")
            _require(type(finish) is bool and (finish and landing is None or not finish and isinstance(landing, dict)
                and set(landing) == {"x", "y", "z"} and all(type(landing[a]) in (int, float) and math.isfinite(landing[a])
                    and (0 <= landing[a] <= 253 if a == "y" else -29_999_999 <= landing[a] <= 29_999_999) for a in ("x", "y", "z"))),
                "survival_flight_landing_unavailable", "Final landing needs one explicit finite continuous feet XYZ; no safe position is inferred.")

    def require_survival_posture(self, snapshot: dict[str, Any], *, flying: bool) -> None:
        player = snapshot.get("player", {})
        flight = player.get("serverFlight", {})
        self.require_survival_server_control(snapshot)
        _require(player.get("creative") is False and player.get("flying") is flying
            and flight.get("available") is True and flight.get("creative") is False and flight.get("flying") is flying
            and flight.get("dimension") == self.dimension,
            "actual_survival_posture_not_confirmed", "Fresh matching client/server actual survival and declared operation posture are required.")
        control = snapshot.get("serverControl", {})
        pose = control.get("pose", {})
        _require(control.get("creative") is False and control.get("flying") is flying and pose.get("flying") is flying,
            "actual_survival_posture_not_confirmed", "Current native server-control mode and pose must agree with the declared actual survival posture.")
        if not flying:
            _require(player.get("onGround") is True and flight.get("onGround") is True
                and snapshot.get("serverControl", {}).get("pose", {}).get("onGround") is True,
                "final_landing_not_confirmed", "Final grounded acceptance requires real client/server ground contact.")
        if flying:
            control = snapshot.get("serverControl", {})
            pose = control.get("pose", {})
            _require(player.get("allowFlying") is True and flight.get("allowFlying") is True
                and control.get("creative") is False and control.get("allowFlying") is True and control.get("flying") is True
                and pose.get("allowFlying") is True and pose.get("flying") is True,
                "survival_flight_permission_unconfirmed", "Both actual survival actors must already be flying with real native equipment permission.")

    def require_navigation_posture(self, poses: tuple[dict[str, Any], ...], *, flying: bool) -> bool:
        return all(pose.get("flying") is flying and (pose.get("allowFlying") is True if flying else pose.get("onGround") is True)
            for pose in poses)

    async def finish_survival_posture(self) -> None:
        if not self.survival_flight:
            return
        self.phase = "final_survival_posture"
        initial = await self.snapshot()
        self.require_flight(initial)
        if initial.get("screenOpen") is True:
            await self.workflow("final_survival_close", [_action("close", "mcb_close_gui", {})])
            self.opened_gui = None
        finish = self.args.get("finishFlying", True)
        self.final_posture = {"requested": "flying" if finish else "grounded", "confirmed": False,
            "landingAttempted": False, "replayAllowed": False, "fallbackUsed": False}
        if not finish:
            _require(not self.final_landing_attempted, "final_landing_already_attempted", "Final landing is one explicit navigation; uncertain outcomes never authorize replay.")
            self.final_landing_attempted = True
            self.final_posture["landingAttempted"] = True
            destination = self.args["finalLandingPosition"]
            fields = ["status", "strategy", "arrivalConfirmed", "replayAllowed", "fallbackUsed", "outcomeUnknown", "playerId",
                "serverPoseAck", "actualClientPose", "baselineSampleId", "elapsedMillis", "flightTransitConfirmed"]
            action = _action("navigate", "mcb_navigate", {**destination, "dimension": self.dimension,
                "expectedSessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
                "requireSurvivalActor": True, "strategy": "ability_flight", "finishFlying": False, "timeout_ms": min(6000, self.remaining())})
            action["fields"] = fields
            report = await self.workflow("final_survival_landing", [action])
            receipt = {field: self.value(report, "navigate", field) for field in fields}
            self.final_posture["navigationReceipt"] = receipt
            ack, client = receipt["serverPoseAck"], receipt["actualClientPose"]
            self.require_survival_server_control({"serverControl": ack})
            _require(receipt["status"] == "completed" and receipt["strategy"] == "ability_flight" and receipt["arrivalConfirmed"] is True
                and receipt["replayAllowed"] is False and receipt["fallbackUsed"] is False and receipt["outcomeUnknown"] is False
                and receipt["flightTransitConfirmed"] is True and type(receipt["baselineSampleId"]) is int
                and ack["sampleId"] > receipt["baselineSampleId"] and receipt["playerId"] == ack["playerId"]
                and ack.get("creative") is False and isinstance(ack.get("pose"), dict) and isinstance(client, dict) and client.get("creative") is False
                and self.require_navigation_posture((client, ack["pose"]), flying=False)
                and all(type(pose.get(path)) in (int, float) and math.isfinite(pose[path]) and abs(pose[path] - destination[axis]) <= .18
                    for pose in (client, ack["pose"]) for axis, path in (("x", "x"), ("y", "feetY"), ("z", "z")))
                and all(abs(client[path] - ack["pose"][path]) <= .0001 for path in ("x", "feetY", "z")),
                "final_landing_not_confirmed", "A final landing needs a newer stable matching actual grounded survival pose at the explicit destination.")
        final = await self.snapshot()
        self.require_survival_posture(final, flying=finish)
        from .workflows import _guard
        _guard(final, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        self.final_posture.update(confirmed=True, flying=finish, actualClientPose=copy.deepcopy(final["player"]),
            serverPoseAck=copy.deepcopy(final["serverControl"]), actualPosture="flying" if finish else "grounded")

    def require_flight(self, snapshot: dict[str, Any]) -> float:
        player, flight = snapshot.get("player", {}), snapshot.get("player", {}).get("serverFlight", {})
        if self.survival_flight:
            self.require_survival_posture(snapshot, flying=True)
        elif self.actual_survival:
            _require(player.get("creative") is False and player.get("flying") is False
                and flight.get("available") is True and flight.get("creative") is False and flight.get("flying") is False
                and flight.get("dimension") == self.dimension,
                "actual_survival_not_confirmed", "Fresh client/server noncreative, nonflying survival must be confirmed before movement.")
        else:
            _require(player.get("flying") is True and flight.get("available") is True and flight.get("flying") is True
                 and flight.get("allowFlying") is True and flight.get("dimension") == self.dimension,
                 "server_flight_not_confirmed", "Fresh client/server creative flight must already be confirmed before any movement.")
        eye_offset = player.get("eyeY", 0) - player.get("feetY", 0)
        _require(type(eye_offset) in (int, float) and 1 <= eye_offset <= 2, "player_eye_height_unavailable", "The observed player eye/feet offset is unavailable.")
        return eye_offset

    async def visible_face(self, name: str, origin: dict[str, Any], side: int, dx: int, dz: int) -> bool:
        target = self.args[name]
        face = {"x": target["x"] + .5 + .499 * dx, "y": target["y"] + .5, "z": target["z"] + .5 + .499 * dz}
        ray = await self.call("world.spatial", {"mode": "raycast", "dimension": self.dimension, "from": origin, "to": face, "includeEntities": False})
        hit = ray.get("hit", {})
        block = hit.get("block") if isinstance(hit, dict) else None
        return ray.get("status") == "observed" and isinstance(hit, dict) and hit.get("type") == "block" and isinstance(block, dict) \
            and all(block.get(axis) == target[axis] for axis in ("x", "y", "z")) and hit.get("side") == side

    @staticmethod
    def clear_body(observations: Any, point: dict[str, Any]) -> bool:
        if not isinstance(observations, list):
            return False
        observed = [entry for entry in observations if isinstance(entry, dict) and all(entry.get(key) == value for key, value in point.items())]
        return len(observed) == 1 and all(observed[0].get(key) is expected for key, expected in
            (("loaded", True), ("occupiable", True), ("liquid", False), ("hazard", False), ("entityCoveragePartial", False)))

    def native_approach_available(self, intent: str) -> bool:
        capable = "player.plan_approach" in self.capabilities if isinstance(self.capabilities, list) else \
            isinstance(self.capabilities, dict) and self.capabilities.get("player.plan_approach") is True
        if not capable:
            return False
        health = self.approach_health or {}
        intents, context = health.get("approachPlanningIntents"), health.get("contextRpc")
        _require(health.get("nativeApproachPlanning") is True and isinstance(intents, list)
            and all(isinstance(value, str) for value in intents) and intent in intents
            and isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1
            and isinstance(context.get("reads"), list) and all(isinstance(value, str) for value in context["reads"])
            and "player.plan_approach" in context["reads"], "native_approach_capability_unavailable",
            "An advertised approach planner must expose the requested native intent and fresh context read contract.")
        return True

    def approach_summary(self) -> dict[str, Any] | None:
        if not self.approach_observations:
            return None
        return {"operation": "player.plan_approach", "readOnly": True, "nonAtomic": True,
            "selectedPlans": sum(plan.get("status", "ready") == "ready" for plan in self.approach_observations),
            "planningReads": len(self.approach_observations),
            "readRetries": sum(plan.get("readRetry") is True for plan in self.approach_observations),
            "unusableObservations": sum(plan.get("status") in ("unknown", "partial", "expired_observation") for plan in self.approach_observations),
            "knownCurrentAbsences": sum(plan.get("status") == "no_candidate" for plan in self.approach_observations), "selectedUnknownChecks": 0,
            "earlierUnknownChecks": sum(plan["coverage"]["unknownChecks"] for plan in self.approach_observations),
            "searchComplete": all(plan["complete"] for plan in self.approach_observations),
            "scope": "Only each selected candidate's native client/server body and exact target face are verified; other candidates and the surrounding region remain unknown.",
            **({"navigation": self.navigation_receipts} if self.navigation_receipts else {})}

    def native_navigation_window(self, strategy: str, distance: float) -> dict[str, Any]:
        health = self.approach_health or {}
        long_walk = strategy == "walking" and distance > 8 \
            and type(health.get("nativeNavigationSegmentsVersion")) is int and health["nativeNavigationSegmentsVersion"] == 1
        # Share the existing task deadline; planning and native steps are part
        # of transit, so a long walk cannot always fit the ordinary six seconds.
        return {"timeout_ms": min(15000 if long_walk else 6000, self.remaining()),
            **({"segmented": True} if long_walk else {})}

    async def resolved_native_approach(self, target: dict[str, Any], *, intent: str = "use", pattern: str = "surround",
        allowed_sides: tuple[int, ...] = (2, 3, 4, 5), hotbar_slot: int | None = None,
        target_identity: dict[str, Any] | None = None, excluded_build_envelope: dict[str, int] | None = None,
        prepared_plan: dict[str, Any] | None = None) -> dict[str, Any]:
        options = dict(intent=intent, pattern=pattern, allowed_sides=allowed_sides, hotbar_slot=hotbar_slot, target_identity=target_identity)
        if self.survival_flight:
            _require(self.actual_survival and self.args.get("movementStrategy") == "ability_flight"
                and type((self.approach_health or {}).get("approachOperationPostureVersion")) is int
                and (self.approach_health or {})["approachOperationPostureVersion"] == 1,
                "native_approach_posture_unavailable", "Persistent survival flight needs the explicit versioned native hover endpoint contract.")
            options.update(operation_posture="flying", movement_strategy="ability_flight")
        if excluded_build_envelope is not None:
            options["excluded_build_envelope"] = excluded_build_envelope
        if prepared_plan is not None:
            _require(isinstance(prepared_plan, dict) and type(prepared_plan.get("currentOnly", False)) is bool
                and type(prepared_plan.get("useNormalReach", False)) is bool and isinstance(prepared_plan.get("current"), dict),
                "native_approach_handoff_unavailable", "A one-use handoff must retain the complete typed native receipt.")
            options.update(use_normal_reach=prepared_plan.get("useNormalReach", False),
                max_distance=prepared_plan["current"].get("maxDistance"))
            if prepared_plan.get("currentOnly", False):
                self.validate_native_approach(prepared_plan, target, **options, current_only=True)
                return prepared_plan
        if self.actual_survival and prepared_plan is None:
            current = await self.current_native_approach(target, **options)
            if current["status"] == "ready":
                return current
            if current.get("useNormalReach") is True:
                options.update(use_normal_reach=True, max_distance=current["current"]["maxDistance"])
            # Only a fully validated, fresh known absence reaches the existing
            # search. Unknown/partial/refused receipts never request a move.
        strategy = self.args.get("movementStrategy", "walking")
        movement_strategy = None
        if self.actual_survival:
            _require(strategy in ("walking", "ability_flight", "developer_teleport"),
                "unsupported_movement_strategy", "Choose one explicit supported navigation strategy.")
            health = self.approach_health or {}
            if "approachMovementStrategyVersion" in health:
                _require(type(health["approachMovementStrategyVersion"]) is int and health["approachMovementStrategyVersion"] == 1,
                    "native_approach_strategy_unavailable", "An advertised endpoint strategy filter requires its exact typed native version; no strategy fallback.")
                movement_strategy = strategy
                options["movement_strategy"] = strategy
        if prepared_plan is None:
            plan = await self.native_approach(target, **options, allow_no_candidate=movement_strategy == "walking" or self.survival_flight)
        else:
            self.validate_native_approach(prepared_plan, target, **options)
            plan = prepared_plan
        if plan["status"] == "no_candidate":
            raise _Unavailable("native_approach_no_compatible_candidate",
                "This complete bounded candidate set has no verified endpoint compatible with the explicit movement/posture contract; no elevated route, fallback or replay is inferred.",
                movementStrategy=strategy, approachReceipt=dict(plan))
        if not self.actual_survival or plan["selected"]["moveRequired"] is False:
            return plan
        health = self.approach_health or {}
        capable = "player.navigate" in self.capabilities if isinstance(self.capabilities, list) else \
            isinstance(self.capabilities, dict) and self.capabilities.get("player.navigate") is True
        _require(capable and health.get("nativeNavigation") is True and health.get("approachCurrentOnly") is True,
            "native_navigation_unavailable", "Actual survival movement requires advertised bounded native navigation and actual-current ray recheck; no teleport/flight fallback.")
        destination = plan["selected"]["feet"]
        if strategy == "walking" and abs(destination["y"] - plan["current"]["y"]) > .0001:
            raise _Unavailable("native_approach_strategy_incompatible",
                "Explicit walking requires a flat endpoint at the proved actual feetY; no step, flight or teleport fallback.",
                movementStrategy=strategy, currentFeetY=plan["current"]["y"], destinationFeetY=destination["y"], approachReceipt=dict(plan))
        steps = [] if plan["current"]["screenOpen"] is False else [_action("close", "mcb_close_gui", {})]
        _require(self.remaining() >= 250, "navigation_deadline", "The remaining task deadline cannot accommodate bounded native navigation.")
        distance = math.sqrt(sum((destination[axis] - plan["current"][axis]) ** 2 for axis in ("x", "y", "z")))
        # Reuse one native segmented route for a long return around installed
        # ports. A fixed six seconds includes planning and is insufficient even
        # for an ordinary 16-block line. No retry, forced route or task extension.
        action = _action("navigate", "mcb_navigate", {**destination, "dimension": self.dimension,
            "expectedSessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
            "requireSurvivalActor": True, "strategy": strategy, "finishFlying": self.survival_flight,
            **self.native_navigation_window(strategy, distance), "lookAt": plan["selected"]["hit"]})
        fields = ["status", "strategy", "arrivalConfirmed", "mutationDispatched", "replayAllowed", "fallbackUsed", "outcomeUnknown",
            "playerId", "serverPoseAck", "actualClientPose", "baselineSampleId", "elapsedMillis", "flightTransitConfirmed", "scope"]
        action["fields"] = fields
        report = await self.workflow("native_navigation", steps + [action])
        receipt = {key: self.value(report, "navigate", key) for key in fields}
        _require(receipt["status"] == "completed" and receipt["strategy"] == strategy and receipt["arrivalConfirmed"] is True
            and receipt["replayAllowed"] is False and receipt["fallbackUsed"] is False and receipt["outcomeUnknown"] is False,
            "navigation_arrival_unavailable", "Navigation must retain actual same-strategy arrival without replay or fallback.")
        ack, client_pose = receipt["serverPoseAck"], receipt["actualClientPose"]
        self.require_survival_server_control({"serverControl": ack})
        _require(type(receipt["baselineSampleId"]) is int and ack["sampleId"] > receipt["baselineSampleId"]
            and receipt["playerId"] == ack["playerId"] and ack.get("creative") is False and isinstance(ack.get("pose"), dict)
            and isinstance(client_pose, dict) and client_pose.get("creative") is False
            and self.require_navigation_posture((ack["pose"], client_pose), flying=self.survival_flight)
            and all(type(pose.get(path)) in (int, float) and math.isfinite(pose[path]) and abs(pose[path] - destination[axis]) <= .18
                for pose in (ack["pose"], client_pose) for axis, path in (("x", "x"), ("y", "feetY"), ("z", "z")))
            and (strategy != "ability_flight" or receipt["flightTransitConfirmed"] is True),
            "navigation_pose_unavailable", "One fresh native receipt must bind actual server/client declared survival posture near the destination and actual flight transit when selected.")
        self.navigation_receipts.append({"strategy": strategy, "elapsedMillis": receipt["elapsedMillis"],
            "arrivalConfirmed": True, "serverSampleId": ack["sampleId"], "baselineSampleId": receipt["baselineSampleId"],
            "playerId": ack["playerId"], "actualServerFeet": {key: ack["pose"][key] for key in ("x", "feetY", "z")},
            "flightTransitConfirmed": receipt["flightTransitConfirmed"], "replayAllowed": False, "fallbackUsed": False,
            "actualClientFlying": client_pose["flying"], "actualServerFlying": ack["pose"]["flying"],
            "scope": "Developer navigation only; actual ray/reach is independently rechecked afterward and machine formation/production is not inferred."})
        # Recompute body/ray/reach at the actual arrived pose; never substitute the planned half-block coordinate.
        return await self.native_approach(target, **options, current_only=True)

    async def current_native_approach(self, target: dict[str, Any], *, intent: str = "use", pattern: str = "surround",
        allowed_sides: tuple[int, ...] = (2, 3, 4, 5), hotbar_slot: int | None = None,
        target_identity: dict[str, Any] | None = None, excluded_build_envelope: dict[str, int] | None = None,
        movement_strategy: str | None = None, operation_posture: str | None = None) -> dict[str, Any]:
        """Prefer one advertised actual-reach proof; older planners retain guarded legacy-cap adoption."""
        options = dict(intent=intent, pattern=pattern, allowed_sides=allowed_sides,
            hotbar_slot=hotbar_slot, target_identity=target_identity)
        if movement_strategy is not None: options["movement_strategy"] = movement_strategy
        if operation_posture is not None: options["operation_posture"] = operation_posture
        if excluded_build_envelope is not None:
            options["excluded_build_envelope"] = excluded_build_envelope
        reach_version = (self.approach_health or {}).get("approachNormalReachVersion")
        if self.actual_survival and type(reach_version) is int and reach_version == 1:
            # The native read clamps this caller ceiling to both fresh getters;
            # its complete body/ray/context proof still validates their minimum.
            return await self.native_approach(target, **options, current_only=True, allow_no_candidate=True,
                use_normal_reach=True, max_distance=32)
        current = await self.native_approach(target, **options, current_only=True, allow_no_candidate=True)
        if current["status"] == "no_candidate" and current["current"].get("normalReachVersion") == 1 \
                and current["current"]["normalReach"] > 3:
            # Both getters came from this complete guarded native read. The
            # second read independently clamps/rechecks them at actual feet.
            return await self.native_approach(target, **options, current_only=True, allow_no_candidate=True,
                use_normal_reach=True, max_distance=current["current"]["normalReach"])
        return current

    async def native_approach(self, target: dict[str, Any], *, intent: str = "use", pattern: str = "surround",
        allowed_sides: tuple[int, ...] = (2, 3, 4, 5), hotbar_slot: int | None = None,
        target_identity: dict[str, Any] | None = None, current_only: bool = False,
        allow_no_candidate: bool = False, use_normal_reach: bool = False, max_distance: float = 3,
        excluded_build_envelope: dict[str, int] | None = None, movement_strategy: str | None = None,
        operation_posture: str | None = None, expected_placement_facing: str | None = None) -> dict[str, Any]:
        """Reobserve bounded actual-current uncertainty; never replay an action or change the request."""
        # The policy layer supplies the creative composite's resolved movement
        # strategy. Declare it on the native read too, so its echoed endpoint
        # filter is checked against the request instead of appearing unsolicited.
        if movement_strategy is None and not self.actual_survival and "movementStrategy" in self.args:
            movement_strategy = self.args["movementStrategy"]
        args = {**{axis: target[axis] for axis in ("x", "y", "z")}, "dimension": self.dimension,
            "expectedWorld": self.args["expectedWorld"], "intent": intent, "pattern": pattern,
            "requireFlying": self.interaction_flying, "maxDistance": max_distance, "allowedSides": list(allowed_sides), "offset": 0}
        if expected_placement_facing is not None:
            _require(intent == "place_block" and expected_placement_facing in ("NORTH", "SOUTH", "WEST", "EAST")
                and type((self.approach_health or {}).get("placementFacingVersion")) is int
                and self.approach_health["placementFacingVersion"] == 1,
                "placement_facing_unavailable", "Explicit GT placement facing requires its advertised native ray and BEFORE-click binding.")
            args["expectedPlacementFacing"] = expected_placement_facing
        if movement_strategy is not None:
            _require(movement_strategy in ("walking", "ability_flight", "developer_teleport")
                and type((self.approach_health or {}).get("approachMovementStrategyVersion")) is int
                and (self.approach_health or {})["approachMovementStrategyVersion"] == 1,
                "native_approach_strategy_unavailable", "Explicit endpoint filtering requires the advertised typed native strategy version.")
            args["movementStrategy"] = movement_strategy
        if operation_posture is not None:
            _require(operation_posture == "flying" and self.actual_survival and self.survival_flight
                and movement_strategy == "ability_flight" and self.args.get("movementStrategy") == "ability_flight"
                and type((self.approach_health or {}).get("approachOperationPostureVersion")) is int
                and (self.approach_health or {})["approachOperationPostureVersion"] == 1,
                "native_approach_posture_unavailable", "Hover endpoint proof requires explicit actual survival flight, strategy and typed native capability.")
            args["operationPosture"] = operation_posture
        if excluded_build_envelope is not None:
            _require(type((self.approach_health or {}).get("approachBuildEnvelopeVersion")) is int
                and (self.approach_health or {})["approachBuildEnvelopeVersion"] == 1,
                "native_build_envelope_unavailable", "Future-body exclusion requires the advertised native bounded envelope contract.")
            args["excludedBuildEnvelope"] = excluded_build_envelope
        if use_normal_reach:
            args["useNormalReach"] = True
        if current_only:
            _require((self.approach_health or {}).get("approachCurrentOnly") is True, "actual_current_approach_unavailable", "Continuous actual-current body/ray proof must be advertised.")
            args["currentOnly"] = True
        if hotbar_slot is not None:
            args["hotbarSlot"] = hotbar_slot
        if target_identity is not None:
            args["targetIdentity"] = target_identity
        validation = dict(intent=intent, pattern=pattern, allowed_sides=allowed_sides, hotbar_slot=hotbar_slot,
            target_identity=target_identity, current_only=current_only, allow_no_candidate=allow_no_candidate,
            use_normal_reach=use_normal_reach, max_distance=max_distance, excluded_build_envelope=excluded_build_envelope,
            movement_strategy=movement_strategy, operation_posture=operation_posture)
        rejected = []
        retry_deadline, last_refusal, extra_reads_started = None, None, 0

        def retry_details(*, expired: bool = False) -> dict[str, Any]:
            return {"additionalReads": extra_reads_started, "limit": 2, "maxWindowMillis": 250,
                "observations": rejected, "mutationReplayed": False, "windowExpired": expired,
                "scope": "No new read starts or ready result is accepted after the retry/task deadline; an already-running native callback or cancellation cleanup may finish later."}

        for read_attempt in range(3):
            if retry_deadline is not None and time.monotonic() + .001 >= min(self.deadline, retry_deadline):
                last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                raise last_refusal
            # Reenter the guard and perform a new owning-thread read. Transport
            # errors and unknown actions are outside this loop's retry policy.
            try:
                timeout = self.remaining() / 1000
                if retry_deadline is not None:
                    timeout = min(timeout, retry_deadline - time.monotonic())
                    if timeout <= 0:
                        last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                        raise last_refusal
                with anyio.fail_after(timeout):
                    with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
                        "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
                        if retry_deadline is not None:
                            if time.monotonic() >= min(self.deadline, retry_deadline):
                                last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                                raise last_refusal
                            extra_reads_started += 1
                        result = await self.call("player.plan_approach", args)
                observed_at = time.monotonic()
            except TimeoutError:
                if last_refusal is not None:
                    last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                    raise last_refusal from None
                raise
            except BridgeError as error:
                if last_refusal is not None and error.details.get("reason") == "task_deadline":
                    last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                    raise last_refusal from None
                if rejected:
                    error.details["approachReadRetry"] = retry_details(
                        expired=retry_deadline is not None and time.monotonic() >= min(self.deadline, retry_deadline))
                raise
            try:
                self.validate_native_approach(result, target, **validation)
            except BridgeError as error:
                error.details["approachReceipt"] = dict(result) if isinstance(result, dict) else {"malformedType": type(result).__name__}
                retryable = False
                if current_only and self.actual_survival and isinstance(result, dict) and result.get("status") in ("unknown", "partial"):
                    try:
                        self.validate_native_approach(result, target, **validation, _read_retry_observation=True)
                    except BridgeError:
                        pass
                    else:
                        retryable = True
                if retryable:
                    if retry_deadline is None:
                        retry_deadline = min(self.deadline, observed_at + .250)
                    last_refusal = error
                    observation = {"intent": intent, "pattern": pattern, "target": copy.deepcopy(args),
                        "coverage": copy.deepcopy(result["coverage"]), "stopReason": result["stopReason"],
                        "complete": result["complete"], "status": result["status"], "readRetry": read_attempt > 0,
                        "selectedUnknownChecks": None, "scope": "Unusable guarded read-only observation; no body, ray, arrival or mutation is authorized."}
                    self.approach_observations.append(observation)
                    rejected.append(copy.deepcopy(observation))
                if rejected:
                    error.details["approachReadRetry"] = retry_details(
                        expired=time.monotonic() >= min(self.deadline, retry_deadline))
                frontier_exhausted = getattr(self, "survival_frontier_native_reads", 0) >= 64
                if retryable and read_attempt < 2 and time.monotonic() + .001 < min(self.deadline, retry_deadline) and not frontier_exhausted:
                    continue
                raise
            if retry_deadline is not None and time.monotonic() >= min(self.deadline, retry_deadline):
                self.approach_observations.append({"intent": intent, "pattern": pattern, "target": copy.deepcopy(args),
                    "coverage": copy.deepcopy(result["coverage"]), "stopReason": result["stopReason"], "complete": result["complete"],
                    "status": "expired_observation", "observedStatus": result["status"], "readRetry": True,
                    "selectedUnknownChecks": None, "scope": "Guarded observation arrived or validated after the retry/task deadline; it authorizes no interaction or mutation."})
                last_refusal.details["approachReadRetry"] = retry_details(expired=True)
                raise last_refusal
            self.approach_observations.append({"intent": intent, "pattern": pattern, "target": copy.deepcopy(args),
                "coverage": result["coverage"], "stopReason": result["stopReason"], "complete": result["complete"], "status": result["status"],
                "readRetry": read_attempt > 0, "selectedUnknownChecks": 0,
                "scope": "Only the selected native client/server body and exact face are verified; earlier unknown candidates remain unknown."})
            return result

    def validate_native_approach(self, result: dict[str, Any], target: dict[str, Any], *, intent: str,
        pattern: str, allowed_sides: tuple[int, ...], hotbar_slot: int | None,
        target_identity: dict[str, Any] | None, current_only: bool = False, allow_no_candidate: bool = False,
        use_normal_reach: bool = False, max_distance: float = 3, excluded_build_envelope: dict[str, int] | None = None,
        movement_strategy: str | None = None, operation_posture: str | None = None,
        _read_retry_observation: bool = False) -> None:
        reason = "native_approach_receipt_unavailable"

        def require(condition: bool, message: str) -> None:
            _require(condition, reason, message)

        def finite(value: Any) -> bool:
            return type(value) in (int, float) and math.isfinite(value)

        def xyz(value: Any, *, integral: bool = False) -> bool:
            return isinstance(value, dict) and all(type(value.get(axis)) is int if integral else finite(value.get(axis))
                for axis in ("x", "y", "z"))

        def close(left: Any, right: Any) -> bool:
            return finite(left) and finite(right) and abs(left - right) <= 1e-6

        def block_identity(value: Any, expected: dict[str, Any] | None = None) -> bool:
            # Raw world metadata may use the client's unsigned 16-bit extension.
            if not isinstance(value, dict) or not isinstance(value.get("registryName"), str) \
                    or re.fullmatch(_ITEM["properties"]["registryName"]["pattern"], value["registryName"]) is None \
                    or type(value.get("metadata")) is not int or not 0 <= value["metadata"] <= 65_535:
                return False
            gt = value.get("gregtech")
            if value["registryName"] == "gregtech:gt.blockmachines" and not isinstance(gt, dict):
                return False
            if gt is not None and (not isinstance(gt, dict) or type(gt.get("metaTileId")) is not int
                    or not 0 <= gt["metaTileId"] <= 32_767 or not isinstance(gt.get("metaClass"), str) or not gt["metaClass"]):
                return False
            if expected is None:
                return True
            return all((gt or {}).get(key) == wanted if key in ("metaTileId", "metaClass") else value.get(key) == wanted
                for key, wanted in expected.items())

        require(isinstance(result, dict), "The native approach receipt must be a complete typed object.")
        require(result.get("source") == "client_server_client" and result.get("sessionId") == self.args["expectedSessionId"]
            and type(result.get("dimension")) is int and result["dimension"] == self.dimension
            and result.get("expectedWorld") == self.args["expectedWorld"] and result.get("intent") == intent
            and result.get("pattern") == pattern and result.get("readOnly") is True and result.get("submitted") is False
            and result.get("nonAtomic") is True, "The composite planner must retain its exact session, world, dimension, intent and read-only source.")
        require(result.get("currentOnly", False) is current_only, "The explicit actual-current planning mode must match its request.")
        strategy_fields = {"movementStrategy", "movementStrategyVersion", "movementStrategyScope"}
        if movement_strategy is not None:
            require(movement_strategy in ("walking", "ability_flight", "developer_teleport")
                and type((self.approach_health or {}).get("approachMovementStrategyVersion")) is int
                and (self.approach_health or {})["approachMovementStrategyVersion"] == 1
                and result.get("movementStrategy") == movement_strategy
                and type(result.get("movementStrategyVersion")) is int and result["movementStrategyVersion"] == 1
                and result.get("movementStrategyScope") == "endpoint_compatibility_only",
                "The explicitly requested strategy filter needs its typed capability, exact echo and endpoint-only scope.")
        else:
            require(not strategy_fields.intersection(result), "An undeclared strategy cannot alter native candidate selection.")
        posture_fields = {"operationPosture", "operationPostureVersion", "candidateMode"}
        if operation_posture is not None:
            require(operation_posture == "flying" and self.actual_survival and self.survival_flight
                and movement_strategy == "ability_flight" and self.args.get("movementStrategy") == "ability_flight"
                and type((self.approach_health or {}).get("approachOperationPostureVersion")) is int
                and (self.approach_health or {})["approachOperationPostureVersion"] == 1
                and result.get("operationPosture") == "flying" and type(result.get("operationPostureVersion")) is int
                and result["operationPostureVersion"] == 1 and result.get("candidateMode") == "actual_current_y_hover",
                "The explicit hover posture requires its exact version, candidate mode and real survival flight declaration.")
        else:
            require(not posture_fields.intersection(result), "An undeclared operation posture cannot change native candidate geometry.")
        require(result.get("useNormalReach", False) is use_normal_reach
            and finite(max_distance) and .1 <= max_distance <= (32 if use_normal_reach else 3),
            "The explicit normal-reach adoption and caller cap must match their bounded request.")
        if excluded_build_envelope is not None:
            require(type(result.get("buildEnvelopeVersion")) is int and result["buildEnvelopeVersion"] == 1
                and result.get("excludedBuildEnvelope") == excluded_build_envelope
                and all(type(value) is int for value in result["excludedBuildEnvelope"].values()),
                "The native planner must retain the exact declared bounded future-body exclusion.")
        else:
            require("excludedBuildEnvelope" not in result, "An undeclared build envelope cannot alter native candidate selection.")
        no_candidate = allow_no_candidate and (current_only or movement_strategy == "walking" or operation_posture == "flying") and result.get("status") == "no_candidate"
        if _read_retry_observation:
            require(current_only and self.actual_survival and "selected" in result and result["selected"] is None,
                "Only an unusable actual-current read may qualify for another read; no selected proof is repaired.")
            require(result.get("outcomeUnknown", False) is False and result.get("mutationDispatched", False) is False,
                "An uncertain action outcome or dispatched mutation cannot qualify for read-only reobservation.")
            require(result.get("status") == "unknown" and result.get("stopReason") == "unknown"
                    and result.get("complete") is True and result.get("partial") is False
                    and "nextOffset" in result and result["nextOffset"] is None
                or result.get("status") == "partial" and result.get("stopReason") in ("tick_budget", "ray_limit")
                    and result.get("complete") is False and result.get("partial") is True
                    and type(result.get("nextOffset")) is int and result["nextOffset"] == 0,
                "Reobservation requires typed unknown completion or a typed bounded budget stop at the same current slot.")
        else:
            require((no_candidate and result.get("complete") is True and result.get("stopReason") == "no_candidate"
                and "selected" in result and result["selected"] is None
            or result.get("status") == "ready" and type(result.get("complete")) is bool
                and result.get("stopReason") == "first_verified_candidate")
            and result.get("partial") is False and "nextOffset" in result and result["nextOffset"] is None,
            "Only a complete selected-candidate proof is usable; partial, unknown, budget-limited and refused planning never falls back.")
        observed, current, chosen, flight, coverage = (result.get(key) for key in ("target", "current", "selected", "serverFlight", "coverage"))
        require(xyz(observed, integral=True) and all(observed[axis] == target[axis] for axis in ("x", "y", "z"))
            and observed.get("source") == "integrated_server" and type(observed.get("dimension")) is int
            and observed["dimension"] == self.dimension and type(observed.get("worldTick")) is int
            and observed["worldTick"] >= 0 and block_identity(observed, target_identity),
            "Fresh server target XYZ and actual block/GT identity must match the declared target.")
        require(intent != "place_block" or observed["registryName"] == "minecraft:air",
            "Native placement planning requires literal server air at the destination.")
        require(isinstance(current, dict) and xyz(current) and finite(current.get("feetY")) and current["feetY"] == current["y"]
            and finite(current.get("eyeY")) and finite(current.get("eyeOffset")) and 1 <= current["eyeOffset"] <= 2
            and close(current["eyeY"] - current["feetY"], current["eyeOffset"])
            and all(finite(current.get(key)) and 0 < current[key] <= 8 for key in ("width", "height"))
            and finite(current.get("normalReach")) and 0 < current["normalReach"] <= 32
            and finite(current.get("maxDistance")) and close(current["maxDistance"], min(max_distance, current["normalReach"]))
            and current.get("clientFlying") is self.interaction_flying and type(current.get("screenOpen")) is bool
            and isinstance(current.get("screenId"), str) and bool(current["screenId"]) is current["screenOpen"]
            and finite(current.get("yaw")) and finite(current.get("pitch")) and -90 <= current["pitch"] <= 90
            and type(current.get("selectedHotbarSlot")) is int and 0 <= current["selectedHotbarSlot"] <= 8
            and type(current.get("captureWorldTick")) is int and type(current.get("recheckWorldTick")) is int
            and current["captureWorldTick"] >= 0 and current["recheckWorldTick"] >= 0
            and type(result.get("clientWorldTick")) is int and result["clientWorldTick"] == current["recheckWorldTick"],
            "The immutable current pose, actual eye offset, normal reach, selected slot and client world observations must be typed and finite.")
        require(isinstance(flight, dict) and flight.get("source") == "integrated_server"
            and flight.get("available") is True and flight.get("creative") is (not self.actual_survival)
            and flight.get("flying") is self.interaction_flying
            and (not self.interaction_flying or flight.get("allowFlying") is True)
            and type(flight.get("dimension")) is int and flight["dimension"] == self.dimension
            and type(flight.get("worldTick")) is int and flight["worldTick"] == observed["worldTick"]
            and finite(flight.get("ageMillis")) and 0 <= flight["ageMillis"] <= 1000,
            "Only fresh authoritative flight in the target dimension may justify an approach.")
        context = response_context(result)
        require(isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1
            and context.get("operation") == "player.plan_approach" and context.get("phase") == "after"
            and isinstance(context.get("snapshot"), dict), "The planner must retain its final fresh context snapshot.")
        snapshot = context["snapshot"]
        clock_fields = {"clientPhaseClockVersion", "clientPhaseClockSource", "captureClientTick", "recheckClientTick"}
        health = self.approach_health or {}
        if "approachClientPhaseClockVersion" in health or clock_fields.intersection(current):
            require(type(health.get("approachClientPhaseClockVersion")) is int and health["approachClientPhaseClockVersion"] == 1
                and clock_fields.issubset(current) and type(current["clientPhaseClockVersion"]) is int
                and current["clientPhaseClockVersion"] == 1 and current["clientPhaseClockSource"] == "bridge_client_tick"
                and type(current["captureClientTick"]) is int and type(current["recheckClientTick"]) is int
                and 0 <= current["captureClientTick"] <= current["recheckClientTick"]
                and type(snapshot.get("clientTicks")) is int and snapshot["clientTicks"] == current["recheckClientTick"],
                "The advertised owning-client monotonic phase clock must be complete, known and bound to its final fresh AFTER snapshot.")
        else:
            require(current["captureWorldTick"] <= current["recheckWorldTick"],
                "Legacy approach receipts require ordered client world observations; an unknown clock never relaxes freshness.")
        normal_reach_fields = {"normalReachVersion", "clientNormalReach", "serverNormalReach", "reachPlayerId"}
        if normal_reach_fields.intersection(current):
            require(normal_reach_fields.issubset(current)
                and type(current["normalReachVersion"]) is int and current["normalReachVersion"] == 1
                and all(finite(current[key]) and 0 < current[key] <= 32 for key in ("clientNormalReach", "serverNormalReach"))
                and close(current["normalReach"], min(current["clientNormalReach"], current["serverNormalReach"]))
                and close(flight.get("normalReach"), current["serverNormalReach"])
                and isinstance(current["reachPlayerId"], str) and bool(current["reachPlayerId"])
                and flight.get("playerId") == current["reachPlayerId"]
                and (not self.actual_survival or snapshot.get("serverControl", {}).get("playerId") == current["reachPlayerId"]),
                "Normal reach needs both fresh native getters, their exact minimum and the same guarded authoritative actor.")
        else:
            require(not use_normal_reach and current["normalReach"] <= 8,
                "Legacy receipts retain the three-block caller cap; an unproved actual reach never authorizes adoption.")
        world, player, background = (snapshot.get(key) for key in ("world", "player", "background"))
        require(snapshot.get("sessionId") == self.args["expectedSessionId"] and snapshot.get("profile") == "developer"
            and snapshot.get("worldLoaded") is True and isinstance(world, dict)
            and self.args["expectedWorld"] in (world.get("worldName"), world.get("worldFolder"))
            and type(world.get("dimension")) is int and world["dimension"] == self.dimension
            and isinstance(background, dict) and background.get("enabled") is True
            and background.get("helperInstalled") is True and background.get("pauseOnLostFocus") is False
            and isinstance(player, dict) and all(close(player.get(path), current[axis]) for axis, path in
                (("x", "x"), ("y", "feetY"), ("z", "z"), ("eyeY", "eyeY")))
            and type(player.get("selectedHotbarSlot")) is int and player["selectedHotbarSlot"] == current["selectedHotbarSlot"]
            and close(player.get("yaw"), current["yaw"]) and close(player.get("pitch"), current["pitch"])
            and snapshot.get("screenOpen") is current["screenOpen"] and snapshot.get("screenId") == current["screenId"],
            "Final context must still prove the exact captured current pose and protected local developer world.")
        fresh_flight = player.get("serverFlight")
        require(isinstance(fresh_flight, dict) and type(fresh_flight.get("dimension")) is int
            and fresh_flight["dimension"] == self.dimension
            and fresh_flight.get("available") is True and fresh_flight.get("flying") is self.interaction_flying
            and (fresh_flight.get("creative") is False if self.actual_survival else fresh_flight.get("allowFlying") is True),
            "The final context must retain typed current server flight in the exact target dimension.")
        self.require_flight(snapshot)
        if operation_posture is not None:
            require(player.get("onGround") is False and flight.get("onGround") is False
                and snapshot.get("serverControl", {}).get("pose", {}).get("onGround") is False,
                "A hover endpoint requires current actual airborne client/server posture, not a grounded flying flag.")
        if current_only or no_candidate or operation_posture is not None:
            self.require_live_player(snapshot)
            self.require_survival_server_control(snapshot)
            require(all(key in current for key in ("item", "sourceItem", "cursorEmpty", "sneaking"))
                and type(current["cursorEmpty"]) is bool and type(current["sneaking"]) is bool
                and all(current[key] is None or isinstance(current[key], dict) for key in ("item", "sourceItem")),
                "Actual-current planning must retain its explicit current held/source item and cursor/sneak guards.")
        require(isinstance(coverage, dict) and type(coverage.get("candidateSlots")) is int
            and coverage["candidateSlots"] == (1 if current_only else (9 if pattern == "surround" else 5) if operation_posture == "flying" else 33 if pattern == "surround" else 13)
            and type(coverage.get("offset")) is int and coverage["offset"] == 0
            and type(coverage.get("candidatesExamined")) is int and 1 <= coverage["candidatesExamined"] <= coverage["candidateSlots"]
            and type(coverage.get("maxCandidates")) is int and coverage["maxCandidates"] == 33
            and type(coverage.get("maxServerRays")) is int and coverage["maxServerRays"] == 198
            and type(coverage.get("workNanosPerPhase")) is int and coverage["workNanosPerPhase"] == 4_000_000
            and type(coverage.get("serverRays")) is int and (0 if no_candidate or _read_retry_observation else 1) <= coverage["serverRays"] <= 198
            and type(coverage.get("clientRays")) is int
                and (0 <= coverage["clientRays"] <= 27 if _read_retry_observation else coverage["clientRays"] == 0 if no_candidate else 1 <= coverage["clientRays"] <= 27)
            and type(coverage.get("unknownChecks")) is int and coverage["unknownChecks"] >= 0
            and close(coverage.get("normalDistanceBound"), current["maxDistance"]),
            "Fixed-slot coverage and ray counters must retain the real search and normal-distance bounds.")
        if movement_strategy is not None:
            require(type(coverage.get("strategyIncompatibleCandidates")) is int
                and 0 <= coverage["strategyIncompatibleCandidates"] <= coverage["candidatesExamined"]
                and (movement_strategy == "walking" and not current_only or coverage["strategyIncompatibleCandidates"] == 0),
                "Strategy rejection counts retain examined slots; current-only and unrestricted-height strategies cannot reject heights.")
        else:
            require("strategyIncompatibleCandidates" not in coverage, "Undeclared strategy filtering cannot change coverage.")
        attempts = coverage["candidatesExamined"]
        continuation_fields = {"serverPages", "maxServerPages", "candidateAttempts", "maxCandidateAttempts"}
        sampling_fields = {"samplingPass", "samplingPasses"}
        sampling_pass = 0
        max_attempts = 36
        if sampling_fields.intersection(coverage):
            require(sampling_fields.issubset(coverage) and continuation_fields.issubset(coverage)
                and intent == "attack" and not current_only
                and type(coverage["samplingPasses"]) is int and coverage["samplingPasses"] == 2
                and type(coverage["samplingPass"]) is int and 0 <= coverage["samplingPass"] <= 1
                and (coverage["samplingPass"] == 0 or coverage["candidatesExamined"] == coverage["candidateSlots"])
                and (result["complete"] is False or coverage["samplingPass"] == 1),
                "Two-pass sampling is restricted to bounded whole-block attack searches with complete typed pass coverage.")
            sampling_pass = coverage["samplingPass"]
            max_attempts = 69
        if continuation_fields.intersection(coverage):
            require(continuation_fields.issubset(coverage)
                and all(type(coverage[key]) is int for key in continuation_fields)
                and 1 <= coverage["serverPages"] <= 4 and coverage["maxServerPages"] == 4
                and coverage["maxCandidateAttempts"] == max_attempts
                and coverage["candidatesExamined"] <= coverage["candidateAttempts"]
                    <= min(max_attempts, coverage["candidatesExamined"] * (sampling_pass + 1) + coverage["serverPages"] - 1)
                and (sampling_pass == 0 or coverage["candidateAttempts"] > coverage["candidateSlots"]),
                "Continuation pages retain fixed budgets, at most one unfinished candidate per extra page, and only the declared attack sampling pass.")
            attempts = coverage["candidateAttempts"]
        sampled_ray_fields = {"rayCountingVersion", "maxRaysPerFace"}
        if sampled_ray_fields.intersection(coverage):
            require(sampled_ray_fields.issubset(coverage)
                and type(coverage["rayCountingVersion"]) is int and coverage["rayCountingVersion"] == 1
                and type(coverage["maxRaysPerFace"]) is int and coverage["maxRaysPerFace"] == 53
                and type(coverage.get("maxClientRays")) is int and coverage["maxClientRays"] == 27
                and continuation_fields.issubset(coverage),
                "Sampled native rays require the exact typed version, per-face limit and complete shared phase/page budgets.")
            rays_per_attempt = 53 * len(allowed_sides)
        else:
            require(coverage["clientRays"] == (0 if no_candidate else 1),
                "Legacy native ray receipts retain exactly one selected client trace.")
            rays_per_attempt = 6
        require(coverage["serverRays"] <= min(198, rays_per_attempt * attempts),
            "Server ray totals must retain the exact versioned face/attempt bound and shared limit; legacy receipts retain six rays per attempt.")
        if intent == "place_block":
            require(type(current.get("requestedHotbarSlot")) is int and current["requestedHotbarSlot"] == hotbar_slot
                and isinstance(current.get("sourceItem"), dict) and current.get("cursorEmpty") is True
                and isinstance(current["sourceItem"].get("registryName"), str)
                and type(current["sourceItem"].get("metadata")) is int and type(current["sourceItem"].get("count")) is int
                and current["sourceItem"]["count"] > 0,
                "Placement planning must retain its real requested source slot and empty cursor without selecting or consuming it.")
        if _read_retry_observation:
            require(clock_fields.issubset(current) and normal_reach_fields.issubset(current)
                and continuation_fields.issubset(coverage) and sampled_ray_fields.issubset(coverage)
                and (result["status"] != "unknown" or coverage["clientRays"] == 0)
                and (coverage["clientRays"] == 0 or coverage["serverRays"] > 0),
                "Retry observations need the complete native clock, reach/UUID, page and sampled-ray contracts.")
            require(result["status"] != "unknown" or coverage["unknownChecks"] > 0,
                "A typed unknown observation must retain actual counted uncertainty.")
            require(result["stopReason"] != "ray_limit" or coverage["serverRays"] == 198 or coverage["clientRays"] == 27,
                "A typed ray-budget stop must retain an actually exhausted native server or client ray counter.")
            return
        if no_candidate:
            require(coverage["candidatesExamined"] == coverage["candidateSlots"] and coverage["unknownChecks"] == 0
                and type(coverage.get("maxClientRays")) is int and coverage["maxClientRays"] == 27
                and continuation_fields.issubset(coverage),
                "Known absence needs all declared slots, zero unknown checks and full fixed budgets; filtered ordinary absence remains a refusal.")
            return
        require(isinstance(chosen, dict) and xyz(chosen.get("feet")) and xyz(chosen.get("eye"))
            and xyz(chosen.get("hit")) and isinstance(chosen.get("attachment"), dict) and isinstance(chosen.get("aim"), dict)
            and finite(chosen["aim"].get("yaw")) and finite(chosen["aim"].get("pitch"))
            and -90 <= chosen["aim"]["pitch"] <= 90 and type(chosen.get("moveRequired")) is bool
            and type(chosen.get("unknownChecks")) is int and chosen["unknownChecks"] == 0,
            "The selected candidate needs finite feet/eye/aim/hit and complete exact server/client ray proof.")
        feet, eye, attachment, hit = (chosen[key] for key in ("feet", "eye", "attachment", "hit"))
        require(-30_000_000 <= feet["x"] <= 30_000_000 and -30_000_000 <= feet["z"] <= 30_000_000
            and 0 <= feet["y"] <= (253 if pattern == "surround" else 254)
            and feet["y"] + current["height"] <= 256
            and close(eye["x"], feet["x"]) and close(eye["z"], feet["z"])
            and close(eye["y"], feet["y"] + current["eyeOffset"]), "Selected feet and eye must preserve the real body offset and legal standing range.")
        if movement_strategy == "walking":
            require(abs(feet["y"] - current["y"]) <= .0001,
                "An explicit walking endpoint must match the guarded actual feetY; this is not a route or arrival proof.")
        if operation_posture == "flying":
            require(feet["y"] == current["y"] and feet["y"] <= 253,
                "The hover candidate must use the proven continuous actual feetY, without an invented elevation or landing endpoint.")
        moved = any(feet[axis] != current[axis] for axis in ("x", "y", "z"))
        require(chosen["moveRequired"] is moved, "The planner's movement flag must match all actual feet coordinates.")
        vectors = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}
        require(xyz(attachment, integral=True) and type(attachment.get("side")) is int and attachment["side"] in allowed_sides
            and attachment.get("source") == "integrated_server" and type(attachment.get("dimension")) is int
            and attachment["dimension"] == self.dimension and block_identity(attachment),
            "The chosen attachment must retain its exact server block identity and allowed native face.")
        side, vector = attachment["side"], vectors[attachment["side"]]
        observed_identity = {key: observed[key] for key in ("registryName", "metadata")}
        if isinstance(observed.get("gregtech"), dict):
            observed_identity.update({key: observed["gregtech"][key] for key in ("metaTileId", "metaClass")})
        require(all(attachment[axis] + (vector[index] if intent == "place_block" else 0) == target[axis]
            for index, axis in enumerate(("x", "y", "z"))) and (intent == "place_block" or block_identity(attachment, observed_identity))
            and attachment["registryName"] not in ("minecraft:air", "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"),
            "The exact attachment face must belong to the destination neighbor or the declared clicked target.")
        centered = all(close(feet[axis] - .5, math.floor(feet[axis])) for axis in ("x", "z")) and close(feet["y"], math.floor(feet["y"]))
        current_center = {axis: round(current[axis]) if axis == "y" else math.floor(current[axis]) + .5 for axis in ("x", "y", "z")}
        current_alignment = [abs(current[axis] - current_center[axis]) for axis in ("x", "y", "z")]
        current_slot = centered and all(value < .0001 if pattern == "surround" else value <= .0001 for value in current_alignment) \
            and all(close(feet[axis], current_center[axis]) for axis in ("x", "y", "z"))
        if current_only:
            require(not moved and all(close(feet[axis], current[axis]) for axis in ("x", "y", "z")), "Actual-current-only proof cannot select a synthetic centered stance or require another move.")
        elif operation_posture == "flying":
            offsets = ((0, -2), (0, 2), (-2, 0), (2, 0), (-2, -2), (2, -2), (-2, 2), (2, 2)) if pattern == "surround" else ((0, -2), (0, 2), (-2, 0), (2, 0))
            if excluded_build_envelope is not None:
                b = excluded_build_envelope
                west, east = b["minX"] - 1 - target["x"], b["maxX"] + 1 - target["x"]
                north, south = b["minZ"] - 1 - target["z"], b["maxZ"] + 1 - target["z"]
                offsets = ((0, north), (0, south), (west, 0), (east, 0), (west, north), (east, north), (west, south), (east, south))
            require(not moved and all(close(feet[axis], current[axis]) for axis in ("x", "y", "z"))
                or all(close(feet[axis] - .5, math.floor(feet[axis])) for axis in ("x", "z"))
                and any(close(feet["x"], target["x"] + .5 + dx) and close(feet["z"], target["z"] + .5 + dz) for dx, dz in offsets)
                and (pattern != "cardinal" or close(feet["x"], target["x"] + .5 + 2 * vector[0]) and close(feet["z"], target["z"] + .5 + 2 * vector[2])),
                "Only the actual current stance or declared horizontal hover offsets may be selected; native body/ray proof remains required.")
        elif moved:
            heights = (target["y"] - 1, target["y"], target["y"] - 2, target["y"] + 1) if pattern == "surround" else \
                (target["y"] - 1, target["y"], target["y"] + 1)
            offsets = ((0, -2), (0, 2), (-2, 0), (2, 0), (-2, -2), (2, -2), (-2, 2), (2, 2)) if pattern == "surround" else \
                ((0, -2), (0, 2), (-2, 0), (2, 0))
            if excluded_build_envelope is not None:
                b = excluded_build_envelope
                west, east = b["minX"] - 1 - target["x"], b["maxX"] + 1 - target["x"]
                north, south = b["minZ"] - 1 - target["z"], b["maxZ"] + 1 - target["z"]
                offsets = ((0, north), (0, south), (west, 0), (east, 0), (west, north), (east, north), (west, south), (east, south))
                heights = (b["minY"], target["y"] - 1, target["y"], target["y"] + 1)
            require(current_slot or centered and feet["y"] in heights and any(close(feet["x"], target["x"] + .5 + dx)
                and close(feet["z"], target["z"] + .5 + dz) for dx, dz in offsets)
                and (pattern != "cardinal" or close(feet["x"], target["x"] + .5 + 2 * vector[0])
                    and close(feet["z"], target["z"] + .5 + 2 * vector[2])),
                "Selected movement must be a declared fixed-slot candidate, with cardinal side binding preserved.")
        else:
            tolerance = .0001
            aligned = [abs(feet[axis] - math.floor(feet[axis]) - (.5 if axis != "y" else 0)) for axis in ("x", "y", "z")]
            require(all(value < tolerance if pattern == "surround" else value <= tolerance for value in aligned),
                "An unchanged current candidate must retain the existing centered standing tolerance.")
        face = {axis: attachment[axis] + .5 + .499 * vector[index] for index, axis in enumerate(("x", "y", "z"))}
        normal_axis = ("x", "y", "z")[next(index for index, value in enumerate(vector) if value)]
        # Native shape sampling can reach a face edge while its center is out of
        # range. Distance belongs to the actual dual-verified hit, not a second,
        # synthetic center ray. The face-plane/outward and identity guards stay.
        require(sum((eye[axis] - face[axis]) * vector[index] for index, axis in enumerate(("x", "y", "z"))) > .001
            and sum((eye[axis] - hit[axis]) ** 2 for axis in ("x", "y", "z")) <= current["maxDistance"] ** 2 + 1e-6
            and all(attachment[axis] - 1e-6 <= hit[axis] <= attachment[axis] + 1 + 1e-6 for axis in ("x", "y", "z"))
            and close(hit[normal_axis], attachment[normal_axis] + (1 if 1 in vector else 0)),
            "Selected exact hit and outward face must stay inside actual normal reach and belong to the claimed block face.")
        if excluded_build_envelope is not None:
            b = excluded_build_envelope
            require(any(feet[axis] + (current["width"] / 2 if axis != "y" else current["height"]) <= b["min" + axis.upper()]
                or feet[axis] - (current["width"] / 2 if axis != "y" else 0) >= b["max" + axis.upper()] + 1
                for axis in ("x", "y", "z")), "The complete selected player body must lie outside the captured future build envelope.")
        expected_body = {"minX": feet["x"] - current["width"] / 2, "minY": feet["y"] + .00001, "minZ": feet["z"] - current["width"] / 2,
            "maxX": feet["x"] + current["width"] / 2, "maxY": feet["y"] + current["height"], "maxZ": feet["z"] + current["width"] / 2}
        attachment_identity = {key: attachment[key] for key in ("registryName", "metadata")}
        if isinstance(attachment.get("gregtech"), dict):
            attachment_identity.update({key: attachment["gregtech"][key] for key in ("metaTileId", "metaClass")})
        for phase, source in (("server", "integrated_server"), ("client", "client")):
            body = chosen.get(phase + "Body")
            require(isinstance(body, dict) and body.get("status") == "observed" and body.get("loaded") is True
                and body.get("occupiable") is True and all(body.get(key) is False for key in ("liquid", "hazard", "entityCoveragePartial"))
                and (not self.actual_survival or self.survival_flight or body.get("canStand") is True)
                and body.get("source") == source and type(body.get("dimension")) is int and body["dimension"] == self.dimension
                and type(body.get("worldTick")) is int and body["worldTick"] == (flight["worldTick"] if phase == "server" else current["recheckWorldTick"])
                and type(body.get("collisionCount")) is int and body["collisionCount"] == 0
                and isinstance(body.get("body"), dict) and all(close(body["body"].get(key), value) for key, value in expected_body.items()),
                "Both native body checks must prove the actual selected AABB loaded and collision/entity/liquid/hazard clear.")
            ray = chosen.get(phase + "Ray")
            require(isinstance(ray, dict) and ray.get("source") == source and type(ray.get("dimension")) is int
                and ray["dimension"] == self.dimension and type(ray.get("worldTick")) is int and ray["worldTick"] >= 0
                and ray["worldTick"] == body["worldTick"]
                and ray.get("native") is True and ray.get("verified") is True and ray.get("status") == "observed"
                and xyz(ray.get("block"), integral=True) and all(ray["block"][axis] == attachment[axis] for axis in ("x", "y", "z"))
                and type(ray.get("side")) is int and ray["side"] == side and block_identity(ray, attachment_identity)
                and xyz(ray.get("hit")) and all(attachment[axis] - 1e-6 <= ray["hit"][axis] <= attachment[axis] + 1 + 1e-6
                    for axis in ("x", "y", "z"))
                and close(ray["hit"][normal_axis], attachment[normal_axis] + (1 if 1 in vector else 0))
                and sum((eye[axis] - ray["hit"][axis]) ** 2 for axis in ("x", "y", "z")) <= current["maxDistance"] ** 2 + 1e-6
                and (phase != "client" or all(close(ray["hit"][axis], hit[axis]) for axis in ("x", "y", "z"))),
                "Each phase must prove the chosen exact native block, face, hit and raw block/GT identity.")

    async def approach_plan(self, name: str, *, intent: str = "use", hotbar_slot: int | None = None,
        allowed_sides: tuple[int, ...] | None = None) -> tuple[int, list[dict[str, Any]], bool]:
        if allowed_sides is None:
            allowed_sides = (0, 1, 2, 3, 4, 5) if self.survival_flight and intent == "use" else (2, 3, 4, 5)
        _require(not self.actual_survival or self.native_approach_available(intent),
            "survival_grounded_approach_unavailable", "Actual survival requires the public native planner for its explicit operation posture; strategy fallback is unavailable.")
        if self.native_approach_available(intent):
            identity = None
            if intent == "use" and self.input_inventory_owner is not None and self.args[name] == self.args["input"]:
                identity = {key: self.input_inventory_owner[key] for key in ("registryName", "metaTileId", "metaClass")}
            plan = await self.resolved_native_approach(self.args[name], intent=intent,
                pattern="cardinal" if intent == "use" else "surround", target_identity=identity,
                hotbar_slot=hotbar_slot, allowed_sides=allowed_sides)
            current, selected = plan["current"], plan["selected"]
            steps = [_action("close", "mcb_close_gui", {}), _read("flight", "mcb_snapshot"),
                _assert("server_available", "flight", "player.serverFlight.available", True),
                _assert("server_flying", "flight", "player.serverFlight.flying", self.interaction_flying),
                _assert("server_mode", "flight", "player.serverFlight.creative", False) if self.actual_survival else
                _assert("server_allow_flying", "flight", "player.serverFlight.allowFlying", True),
                _assert("server_dimension", "flight", "player.serverFlight.dimension", self.dimension),
                _assert("client_flying", "flight", "player.flying", self.interaction_flying)]
            if self.actual_survival:
                steps.append(_assert("client_mode", "flight", "player.creative", False))
            if current["screenOpen"] is False:
                steps.pop(0)
                steps[1:1] = [_assert("screen_still_closed", "flight", "screenOpen", False),
                    _assert("screen_id_still_empty", "flight", "screenId", "")]
            destination = selected["feet"]
            if selected["moveRequired"]:
                steps.extend([_action("move", "mcb_teleport", {**destination, "dimension": self.dimension,
                    "expectedWorld": self.args["expectedWorld"], "requireFlying": self.interaction_flying}),
                    _wait("position_y_synced", "mcb_snapshot", {}, "player.feetY", destination["y"]),
                    _wait("position_z_synced", "mcb_snapshot", {}, "player.z", destination["z"]),
                    _wait("position_synced", "mcb_snapshot", {}, "player.x", destination["x"]),
                    _assert("y_synced", "position_synced", "player.feetY", destination["y"]),
                    _assert("z_synced", "position_synced", "player.z", destination["z"])])
            else:
                steps.extend([_assert("same_x", "flight", "player.x", current["x"]),
                    _assert("same_y", "flight", "player.feetY", current["y"]), _assert("same_z", "flight", "player.z", current["z"])])
            return selected["attachment"]["side"], steps, selected["moveRequired"]
        snapshot = await self.snapshot()
        eye_offset = self.require_flight(snapshot)
        player, target = snapshot["player"], self.args[name]
        candidates = [(2, 0, -1), (3, 0, 1), (4, -1, 0), (5, 1, 0)]
        steps = [_action("close", "mcb_close_gui", {}), _read("flight", "mcb_snapshot"),
            _assert("server_available", "flight", "player.serverFlight.available", True),
            _assert("server_flying", "flight", "player.serverFlight.flying", True),
            _assert("server_allow_flying", "flight", "player.serverFlight.allowFlying", True),
            _assert("server_dimension", "flight", "player.serverFlight.dimension", self.dimension),
            _assert("client_flying", "flight", "player.flying", True)]
        current = {"x": player.get("x"), "y": player.get("feetY"), "z": player.get("z")}
        if all(type(value) in (int, float) and math.isfinite(value) for value in current.values()):
            point = {axis: math.floor(current[axis]) for axis in ("x", "y", "z")}
            aligned = abs(current["x"] - point["x"] - .5) <= .0001 and abs(current["z"] - point["z"] - .5) <= .0001 \
                and abs(current["y"] - point["y"]) <= .0001
            origin = {"x": current["x"], "y": player["eyeY"], "z": current["z"]}
            nearby = [(side, dx, dz) for side, dx, dz in candidates if sum((origin[axis] - value) ** 2 for axis, value in
                {"x": target["x"] + .5 + .499 * dx, "y": target["y"] + .5, "z": target["z"] + .5 + .499 * dz}.items()) <= 9]
            if aligned and nearby:
                spatial = await self.call("world.spatial", {"mode": "positions", "positions": [point], "dimension": self.dimension, "limit": 1})
                if self.clear_body(spatial.get("positions"), point):
                    for side, dx, dz in nearby:
                        if await self.visible_face(name, origin, side, dx, dz):
                            steps.extend([_assert("same_x", "flight", "player.x", current["x"]),
                                _assert("same_y", "flight", "player.feetY", current["y"]),
                                _assert("same_z", "flight", "player.z", current["z"])])
                            return side, steps, False
        heights = [height for height in (target["y"] - 1, target["y"], target["y"] + 1) if 0 <= height <= 254]
        approaches = [(side, dx, dz, {"x": target["x"] + 2 * dx, "y": height, "z": target["z"] + 2 * dz})
            for height in heights for side, dx, dz in candidates]
        points = [point for _, _, _, point in approaches]
        spatial = await self.call("world.spatial", {"mode": "positions", "positions": points, "dimension": self.dimension, "limit": len(points)})
        observations = spatial.get("positions", [])
        for side, dx, dz, point in approaches:
            if not self.clear_body(observations, point):
                continue
            origin = {"x": point["x"] + .5, "y": point["y"] + eye_offset, "z": point["z"] + .5}
            face = {"x": target["x"] + .5 + .499 * dx, "y": target["y"] + .5, "z": target["z"] + .5 + .499 * dz}
            if sum((origin[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) > 9:
                continue
            if not await self.visible_face(name, origin, side, dx, dz):
                continue
            steps.extend([_action("move", "mcb_teleport", {**point, "x": origin["x"], "z": origin["z"], "dimension": self.dimension,
                    "expectedWorld": self.args["expectedWorld"], "requireFlying": True}),
                # X can match an old snapshot while feetY/Z are still syncing.
                # The last snapshot must still prove all three coordinates.
                _wait("position_y_synced", "mcb_snapshot", {}, "player.feetY", point["y"]),
                _wait("position_z_synced", "mcb_snapshot", {}, "player.z", origin["z"]),
                _wait("position_synced", "mcb_snapshot", {}, "player.x", origin["x"]),
                _assert("y_synced", "position_synced", "player.feetY", point["y"]),
                _assert("z_synced", "position_synced", "player.z", origin["z"])])
            return side, steps, True
        raise _Unavailable("safe_visible_approach_unavailable", "No observed loaded, clear horizontal body position has a visible target face.")

    async def open_gui(self, name: str) -> dict[str, Any]:
        inventory = await self.call("inventory.inspect", {})
        slots = inventory_slots(inventory, "")
        _require(inventory.get("cursorItem") is None, "carried_stack_not_empty", "The real cursor must be empty before GUI transfer.")
        if self.single_api_verified and self.input_inventory_owner is not None \
                or self.registered_multiblock_verified and self.opened_gui is not None and "nativeOwner" in self.opened_gui:
            current = await self.reuse_gui(name)
            if current is not None:
                return current
        empty = [slot["index"] for slot in slots if 0 <= slot["index"] <= 8 and slot["item"] is None]
        if not empty and self.single_feed_grants and self.material_grants_dispatched:
            await self.prepare_empty_hand(0, reserve=1, phase="hand_recovery")
            inventory = await self.call("inventory.inspect", {})
            slots = inventory_slots(inventory, "")
            _require(inventory.get("cursorItem") is None, "carried_stack_not_empty", "Native hand recovery must leave the real cursor empty.")
            empty = [slot["index"] for slot in slots if 0 <= slot["index"] <= 8 and slot["item"] is None]
        _require(bool(empty), "empty_hotbar_slot_required", "An observed empty hotbar slot is required for safe normal GUI use.")
        side, steps, moved = await self.approach_plan(name)
        steps.extend([_action("empty_hand", "mcb_hotbar", {"slot": empty[0]}),
            _action("open", "mcb_interact", {"action": "use", **self.args[name], "side": side, "sneaking": False}),
            _wait("gui", "mcb_gui", {}, "totalSlots", 0, operator="gt", fields=["screenId", "windowId", "slots", "totalSlots", "truncated", "wholeStackTransfer"]),
            _assert("same_window", "gui", "windowId", 0, "gt")])
        self.opened_gui = None
        report = await self.workflow("open_" + name, steps)
        self.phases[-1].update(teleportDispatched=moved, positionSource="Fresh flight, clear loaded body and exact public visible face; native target-view interaction retained.")
        gui = {key: self.value(report, "gui", key) for key in ("screenId", "windowId", "slots", "totalSlots", "truncated")}
        preserve_gui_capability(report, "gui", gui)
        inventory_slots(gui, "")
        self.opened_gui = {"target": name, "screenId": gui["screenId"], "windowId": gui["windowId"], "side": side}
        if self.registered_multiblock_verified:
            owner = self.known_gui_owner(name)
            bindings = self.native_gui_bindings(gui, owner)
            if bindings is not None:
                self.opened_gui.update(nativeOwner=copy.deepcopy(owner), nativeBindings=bindings)
        return gui

    def known_gui_owner(self, name: str) -> dict[str, Any] | None:
        from .whole_stack_transfer import OWNER_KEYS as GUI_OWNER_KEYS
        target = self.args[name]
        owner = self.input_inventory_owner if target == self.args.get("input") else \
            getattr(self, "multi_output_owner", None) if target == self.args.get("output") else None
        if owner is None:
            return None
        _require(isinstance(owner, dict) and set(owner) == set(GUI_OWNER_KEYS)
            and all(type(owner.get(key)) is int for key in ("x", "y", "z", "dimension", "metaTileId"))
            and all(isinstance(owner.get(key), str) and owner[key] for key in ("registryName", "metaClass"))
            and owner["dimension"] == self.dimension and all(owner[axis] == target[axis] for axis in ("x", "y", "z")),
            "open_gui_owner_unavailable", "GUI reuse requires the exact previously observed actual server owner at this target.")
        return owner

    def native_gui_bindings(self, gui: dict[str, Any], owner: dict[str, Any] | None) -> list[tuple[int, str, int]] | None:
        from .whole_stack_transfer import validate_capability
        health = self.approach_health or {}
        capability, window = health.get("wholeStackTransfer"), gui.get("wholeStackTransfer")
        if owner is None or capability is None or window is None:
            return None
        validate_capability(capability)
        _require(isinstance(window, dict) and type(window.get("supported")) is bool,
            "whole_stack_window_capability_invalid", "Reuse requires explicit native support for the actual ordinary window.")
        if window["supported"] is False:
            return None
        _require(all(type(window.get(key)) is type(value) and window.get(key) == value for key, value in capability.items())
            and window.get("owner") == owner
            and all(type(window["owner"].get(key)) is type(value) for key, value in owner.items()),
            "open_gui_owner_changed", "The current supported window must retain its exact typed native contract and actual owner.")
        if not (self.actual_survival and type(health.get("approachNormalReachVersion")) is int
                and health["approachNormalReachVersion"] == 1 and health.get("approachCurrentOnly") is True
                and self.native_approach_available("use")):
            return None
        bindings = []
        for slot in inventory_slots(gui, ""):
            machine, player = slot.get("machineInventoryBindingVerified"), slot.get("playerInventoryBindingVerified")
            if machine is not True and player is not True:
                continue
            domain, key, limit = ("machine", "machineInventoryIndex", 255) if machine is True else ("player", "playerInventoryIndex", 40)
            _require(machine is (domain == "machine") and player is (domain == "player")
                and type(slot.get("slot")) is int and type(slot.get("nativeContainerSlotId")) is int
                and slot["slot"] == slot["nativeContainerSlotId"] and type(slot.get(key)) is int and 0 <= slot[key] <= limit
                and (domain == "machine" and slot.get("machineInventoryOwner") == owner
                    and all(type(slot["machineInventoryOwner"].get(field)) is type(value) for field, value in owner.items())
                    and slot.get("machineInventoryBinding") == "public_native_slot_actual_machine_handler_identity"
                    or domain == "player" and slot.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
                    and slot.get("inventorySource") == "current_player_inventory"),
                "open_gui_binding_changed", "Every publicly bound native slot must retain one exact real player or machine identity.")
            bindings.append((slot["slot"], domain, slot[key]))
        _require({domain for _, domain, _ in bindings} == {"player", "machine"}
            and len({(domain, index) for _, domain, index in bindings}) == len(bindings),
            "open_gui_binding_ambiguous", "The complete ordinary window needs unique actual machine and player backing slot identities.")
        return sorted(bindings)

    async def reuse_gui(self, name: str) -> dict[str, Any] | None:
        proof = self.opened_gui
        alias = proof is not None and proof["target"] != name
        if proof is None or alias and not (self.single_api_verified and self.input_inventory_owner is not None
                and self.args[proof["target"]] == self.args[name] == self.args["input"]):
            return None
        current, snapshot = await self.read_with_snapshot("gui.inspect")
        self.require_flight(snapshot)
        if current.get("screenId") != proof["screenId"] or current.get("windowId") != proof["windowId"]:
            self.opened_gui = None
            return None
        inventory_slots(current, "")
        if response_context(current) is not None:
            _require(snapshot.get("screenOpen") is True and snapshot.get("screenId") == current.get("screenId"),
                     "open_gui_context_changed", "The current GUI's own after-snapshot must identify that same open screen.")
        native_owner = None
        if "nativeOwner" in proof:
            context = response_context(current)
            _require(isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1
                and context.get("operation") == "gui.inspect" and context.get("phase") == "after"
                and context.get("snapshot") == snapshot,
                "open_gui_context_changed", "Generic owner reuse requires this current GUI read's own complete guarded AFTER snapshot.")
            native_owner = self.known_gui_owner(name)
            _require(native_owner == proof["nativeOwner"], "open_gui_owner_changed", "Reuse must retain the same exact known actual resource owner.")
            bindings = self.native_gui_bindings(current, native_owner)
            if bindings is None:
                return None
            _require(bindings == proof["nativeBindings"], "open_gui_binding_changed",
                "A reused ordinary window must preserve every exact unique native backing slot mapping.")
        if alias or self.single_api_verified and self.input_inventory_owner is not None:
            bindings = self.current_machine_gui_bindings(current)
            if not bindings or any(slot["machineInventoryIndex"] not in self.machine_inventory_indices for slot in bindings):
                return None
            if self.input_gui_binding is not None:
                self.validate_auto_input_binding(current)
            if self.catalyst_gui_binding is not None:
                self.validate_auto_catalyst_binding(current)
        player, target = snapshot["player"], self.args[name]
        origin = {"x": player.get("x"), "y": player.get("eyeY"), "z": player.get("z")}
        _require(all(type(value) in (int, float) for value in origin.values()),
                 "player_position_unavailable", "Current real player eye coordinates are required to reuse a target-bound GUI.")
        vector = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}[proof["side"]]
        face = {axis: target[axis] + .5 + .499 * vector[index] for index, axis in enumerate(("x", "y", "z"))}
        distance_bound = 3
        native_ray_verified = False
        reach_version = (self.approach_health or {}).get("approachNormalReachVersion")
        if self.actual_survival and type(reach_version) is int and reach_version == 1 \
                and (self.approach_health or {}).get("approachCurrentOnly") is True and self.native_approach_available("use"):
            identity = None
            if native_owner is not None:
                identity = {key: native_owner[key] for key in ("registryName", "metaTileId", "metaClass")}
            elif self.input_inventory_owner is not None and target == self.args["input"]:
                identity = {key: self.input_inventory_owner[key] for key in ("registryName", "metaTileId", "metaClass")}
            posture = {"operation_posture": "flying", "movement_strategy": "ability_flight"} if self.survival_flight else {}
            plan = await self.native_approach(target, intent="use", pattern="cardinal", allowed_sides=(proof["side"],),
                target_identity=identity, current_only=True, allow_no_candidate=True, use_normal_reach=True, max_distance=32, **posture)
            _require(plan["status"] == "ready" and plan["selected"]["moveRequired"] is False,
                "open_gui_target_out_of_range", "The same opened face needs a fresh exact-current native body/ray proof inside both normal reach getters.")
            fresh = response_context(plan)["snapshot"]
            fresh_player, control = fresh["player"], fresh.get("serverControl", {})
            _require(fresh.get("screenOpen") is True and fresh.get("screenId") == proof["screenId"]
                and all(type(fresh_player.get(key)) in (int, float) and math.isfinite(fresh_player[key])
                    and type(player.get(key)) in (int, float) and math.isfinite(player[key])
                    and abs(fresh_player[key] - player[key]) <= 1e-6 for key in ("x", "feetY", "z", "eyeY", "yaw", "pitch"))
                and fresh_player.get("selectedHotbarSlot") == player.get("selectedHotbarSlot")
                and control.get("openContainer", {}).get("windowId") == proof["windowId"]
                and control.get("clientContainer", {}).get("windowId") == proof["windowId"],
                "open_gui_context_changed", "The fresh native proof must retain the actual GUI/window, actor position, camera and selected source without exemptions.")
            distance_bound = plan["current"]["maxDistance"]
            native_ray_verified = True
        _require(sum((origin[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) <= distance_bound ** 2 + 1e-6,
                 "open_gui_target_out_of_range", "The previously opened actual target must remain within freshly proved native normal reach; missing capabilities retain the strict three-block bound.")
        if not native_ray_verified:
            ray = await self.call("world.spatial", {"mode": "raycast", "dimension": self.dimension,
                "from": origin, "to": face, "includeEntities": False})
            hit = ray.get("hit")
            block = hit.get("block") if isinstance(hit, dict) else None
            _require(ray.get("status") == "observed" and isinstance(hit, dict) and hit.get("type") == "block"
                     and isinstance(block, dict) and all(block.get(axis) == target[axis] for axis in ("x", "y", "z"))
                     and hit.get("side") == proof["side"], "open_gui_target_visibility_lost",
                     "The same opened target face must remain publicly observable before any transfer.")
        self.phases.append({"phase": "reuse_" + name, "status": "completed", "screenId": proof["screenId"],
            "windowId": proof["windowId"], "source": "This task's successful native open, current exact screen/window and fresh player/face observations."})
        return current

    async def transfer(self, identity: dict[str, Any], amount: int, destination: int, label: str, *, close_after: bool = True) -> None:
        before_player = await self.call("inventory.inspect", {})
        player_slots = inventory_slots(before_player, "")
        before_count = item_count(player_slots, identity)
        protected = self.protected_player_indices()
        if protected:
            await self.validate_protected_sources(before_player)
        player_source = unique_source(self.ordinary_source_slots(player_slots), identity, amount)
        source_count = _quantity(player_source["item"].get("count"))
        gui = await self.reuse_gui("input")
        if gui is None:
            gui = await self.open_gui("input")
        observed_sources = [slot for slot in self.ordinary_source_slots(inventory_slots(gui, ""), gui=True)
                            if _same(slot.get("item"), identity)]
        source_match = _match(identity, source_count)
        if protected:
            source_match.update(playerInventoryIndex=player_source["index"], playerInventoryBindingVerified=True,
                                machineInventoryBindingVerified=False, inventorySource="current_player_inventory")
        synchronized = response_context(gui) is not None and len(observed_sources) == 1 \
            and type(observed_sources[0]["item"].get("count")) is int and observed_sources[0]["item"]["count"] == source_count \
            and (not protected or observed_sources[0].get("playerInventoryIndex") == player_source["index"])
        if not synchronized:
            synced = await self.workflow("source_sync_" + label, [
                _wait("source", "mcb_gui", {}, "slots", source_match, operator="matches_one",
                    fields=["screenId", "windowId", "slots", "totalSlots", "truncated", "wholeStackTransfer"]),
                _assert("same_window", "source", "windowId", gui["windowId"]),
                _assert("same_screen", "source", "screenId", gui["screenId"])])
            gui = {key: self.value(synced, "source", key) for key in ("screenId", "windowId", "slots", "totalSlots", "truncated")}
            preserve_gui_capability(synced, "source", gui)
        inventory_slots(gui, "")
        if label == "material" and self.input_gui_binding is not None:
            gui = await self.refresh_auto_input_gui(gui)
            destination = self.input_gui_slot
        if label == "catalyst" and self.catalyst_gui_binding is not None:
            server = await self.call("world.inspect", self.position("input"))
            self.bind_input_inventory_owner(server)
            indexed = {slot["index"]: slot["item"] for slot in inventory_slots(server)}
            for binding in (self.input_gui_binding, self.catalyst_gui_binding):
                _require(binding is not None and binding["inventoryIndex"] in indexed
                    and indexed[binding["inventoryIndex"]] is None, "catalyst_gui_binding_changed",
                    "The same real material and catalyst backing entries must still be empty before the first catalyst pickup.")
        destinations = [slot for slot in gui["slots"] if slot.get("slot") == destination]
        _require(len(destinations) == 1 and destinations[0].get("item") is None, "declared_destination_not_empty",
                 "The caller-declared input GUI destination must be one real observed empty slot.")
        if self.input_gui_binding is not None:
            self.validate_auto_input_binding(gui)
        if self.catalyst_gui_binding is not None:
            self.validate_auto_catalyst_binding(gui)
        if self.single_inventory:
            self.validate_declared_single_slots(gui)
        if protected:
            await self.validate_protected_sources(before_player, gui)
        source = unique_source(self.ordinary_source_slots(gui["slots"], gui=True), identity, amount,
                               {self.input_gui_slot, self.catalyst_gui_slot})
        count, source_slot = _quantity(source["item"]["count"]), source["slot"]
        if protected:
            _require(source.get("playerInventoryBindingVerified") is True and source.get("machineInventoryBindingVerified") is False
                     and source.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
                     and source.get("inventorySource") == "current_player_inventory"
                     and type(source.get("playerInventoryIndex")) is int and source["playerInventoryIndex"] == player_source["index"],
                     "material_source_player_binding_unavailable", "The ordinary material source must bind the exact untracked actual player entry; no native slot offset is guessed.")
        _require(count == source_count, "material_source_not_synchronized", "The uniquely observed GUI stack must equal the actual player source count.")
        native = plan_whole_stack(self, gui, source, destinations[0], identity, amount, "player_to_machine",
            destinations[0].get("machineInventoryOwner"))
        if getattr(self, "recipe_context_inventory", None) is not None:
            self.queue_recipe_context_guard()
        if native is not None:
            self.current_input_window, self.current_input_destination = gui["windowId"], destination
            try:
                owner_after, after = await execute_whole_stack(self, native)
            finally:
                self.current_input_window = self.current_input_destination = None
            actual = {row["index"]: row["item"] for row in inventory_slots(owner_after)}
            _require(native["machineInventoryIndex"] in actual and _same(actual[native["machineInventoryIndex"]], identity)
                and actual[native["machineInventoryIndex"]].get("count") == amount
                and before_count - item_count(inventory_slots(after, ""), identity) == amount,
                "player_transfer_delta_mismatch", "The fixed fresh AFTER must prove the exact source loss and bound actual machine destination stack.")
            if protected:
                await self.validate_protected_sources(after)
            if getattr(self, "recipe_context_inventory", None) is not None:
                await self.account_recipe_context_item(player_source["index"], identity, -amount, after, "native_feed_" + label)
            if close_after:
                await self.workflow("close_after_native_transfer_" + label, [_action("close", "mcb_close_gui", {})])
                self.opened_gui = None
            return
        source_proof = _match(identity, count)
        if protected:
            source_proof.update(slot=source_slot, playerInventoryIndex=player_source["index"],
                                playerInventoryBindingVerified=True, machineInventoryBindingVerified=False)
        steps = [_read("before", "mcb_gui", fields=["windowId", "screenId", "slots"]),
            _assert("same_window", "before", "windowId", gui["windowId"]), _assert("same_screen", "before", "screenId", gui["screenId"]),
            _assert("source_unique", "before", "slots", source_proof, "matches_one"),
            _assert("destination_empty", "before", "slots", {"slot": destination, "item": None}, "matches_one"),
            _action("pickup", "mcb_slot_click", {"windowId": gui["windowId"], "slot": source_slot, "mode": 0, "button": 0}),
            _wait("carried", "mcb_inventory", {}, "cursorItem.count", count, fields=["cursorItem"]),
            _assert("carried_registry", "carried", "cursorItem.registryName", identity["registryName"]),
            _assert("carried_metadata", "carried", "cursorItem.metadata", identity["metadata"]),
            _assert("carried_untagged", "carried", "cursorItem.hasTag", False)]
        if protected:
            steps.insert(3, _assert("complete_source_slots", "before", "slots", json_sha256(gui["slots"]), "json_sha256_equals"))
        if self.input_gui_binding is not None:
            steps[0]["fields"].append("truncated")
            proof = {"slot": self.input_gui_slot, "machineInventoryBindingVerified": True,
                "machineInventoryBinding": MACHINE_SLOT_BINDING,
                "machineInventoryIndex": self.input_gui_binding["inventoryIndex"],
                "machineInventoryRole": "consumed_input", "consumedInput": True,
                "playerInventoryBindingVerified": False,
                **{"machineInventoryOwner." + key: value for key, value in self.input_inventory_owner.items()}}
            index = next(index for index, step in enumerate(steps) if step["id"] == "pickup")
            steps[index:index] = [_assert("input_binding_coverage", "before", "truncated", False),
                _assert("input_binding_current", "before", "slots", proof, "matches_one")]
        if self.catalyst_gui_binding is not None:
            if "truncated" not in steps[0]["fields"]:
                steps[0]["fields"].append("truncated")
            proof = {"slot": self.catalyst_gui_slot, "machineInventoryBindingVerified": True,
                "machineInventoryBinding": MACHINE_SLOT_BINDING,
                "machineInventoryIndex": self.catalyst_gui_binding["inventoryIndex"],
                "machineInventoryRole": "consumed_input", "consumedInput": True,
                "playerInventoryBindingVerified": False,
                **{"machineInventoryOwner." + key: value for key, value in self.input_inventory_owner.items()}}
            index = next(index for index, step in enumerate(steps) if step["id"] == "pickup")
            steps[index:index] = [_assert("catalyst_binding_coverage", "before", "truncated", False),
                _assert("catalyst_binding_current", "before", "slots", proof, "matches_one")]
        if count == amount:
            steps.append(_action("place_stack", "mcb_slot_click", {"windowId": gui["windowId"], "slot": destination, "mode": 0, "button": 0}))
        else:
            for index in range(amount):
                steps.append(_action("place_" + str(index), "mcb_slot_click", {"windowId": gui["windowId"], "slot": destination, "mode": 0, "button": 1}))
        if count > amount:
            steps.append(_action("return_remainder", "mcb_slot_click", {"windowId": gui["windowId"], "slot": source_slot, "mode": 0, "button": 0}))
        steps.extend([_wait("cursor_empty", "mcb_inventory", {}, "cursorItem", None),
            _wait("destination_synced", "mcb_gui", {}, "slots", {"slot": destination, **_match(identity, amount)}, operator="matches_one")])
        if close_after:
            steps.append(_action("close", "mcb_close_gui", {}))
        self.current_input_window, self.current_input_destination = gui["windowId"], destination
        try:
            await self.workflow("transfer_" + label, steps)
        finally:
            self.current_input_window = self.current_input_destination = None
        if close_after:
            self.opened_gui = None
        after = await self.call("inventory.inspect", {})
        _require(before_count - item_count(inventory_slots(after, ""), identity) == amount,
                 "player_transfer_delta_mismatch", "The real player inventory did not lose the exact transferred amount.")
        if getattr(self, "recipe_context_inventory", None) is not None:
            await self.account_recipe_context_item(player_source["index"], identity, -amount, after, "feed_" + label)

    async def control_widgets(self, screen_id: str, *, semantic: str | None = None,
        reason: str = "native_widget_coverage_incomplete") -> dict[str, Any]:
        """Collect the exact screen's public actionable controls, never replay writes."""
        from .workflows import _context_capabilities, _guard
        widgets: list[dict[str, Any]] = []
        seen: set[str] = set()
        offset, total, retained_bytes, pages, partial_pages, byte_pages = 0, None, 0, 0, 0, 0
        refreshes = 0
        for _ in range(100_000):
            args = {"screenId": screen_id, "actionableOnly": True, "offset": offset, "limit": 128}
            if semantic is not None:
                args["semantic"] = semantic
            reads, _ = _context_capabilities(self.approach_health or {})
            if "gui.widgets" in reads:
                with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
                    "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
                    data = await self.call("gui.widgets", args)
            else:
                data = await self.call("gui.widgets", args)
            rows = data.get("widgets")
            _require(data.get("screenId") == screen_id and isinstance(rows, list) and len(rows) <= 128
                and all(isinstance(row, dict) for row in rows)
                and all(type(data.get(flag)) is bool for flag in ("hasMore", "partial", "scanTruncated", "byteLimited"))
                and not (isinstance(data.get("coverage"), dict) and "treeError" in data["coverage"]), reason,
                "Public controls require the same live screen and explicit untruncated coverage without tree errors on every page.")
            if data["scanTruncated"] is True:
                budget = data.get("budget")
                _require(refreshes == 0 and pages == 0 and data["hasMore"] is True and data["partial"] is True
                    and data.get("actionableOnly") is True and type(data.get("offset")) is int and data["offset"] == 0
                    and type(data.get("nextOffset")) is int and type(data.get("visited")) is int
                    and 0 <= data["nextOffset"] <= data["visited"] <= 100_000
                    and type(data.get("rawScanned")) is int and data["rawScanned"] == data["nextOffset"]
                    and len(rows) <= data["rawScanned"] and isinstance(budget, dict)
                    and type(budget.get("softBudgetMicros")) is int and budget["softBudgetMicros"] == 4000
                    and type(budget.get("actualElapsedMicros")) is int and budget["actualElapsedMicros"] > 4000
                    and budget.get("softBudgetExceeded") is True, reason,
                    "Only one explicit first-page native soft-budget truncation may refresh; malformed/unknown coverage never retries.")
                current = await self.snapshot()
                _guard(current, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
                self.require_survival_server_control(current)
                _require(current.get("screenOpen") is True and current.get("screenId") == screen_id, reason,
                    "A bounded coverage refresh requires the same actual current native screen and protected actor/context.")
                refreshes += 1
                continue
            pages += 1
            partial_pages += int(data["partial"])
            byte_pages += int(data["byteLimited"])
            # A legacy complete single page needs no inferred continuation.
            paged = pages > 1 or data["hasMore"] is True or any(key in data for key in ("offset", "nextOffset", "visited", "rawScanned"))
            if paged:
                following, visited = data.get("nextOffset"), data.get("visited")
                _require(type(data.get("offset")) is int and data["offset"] == offset
                    and type(following) is int and offset <= following <= 100_000
                    and type(visited) is int and following <= visited <= 100_000
                    and (total is None or visited == total)
                    and type(data.get("rawScanned")) is int and data["rawScanned"] == following - offset
                    and len(rows) <= data["rawScanned"] and data.get("actionableOnly") is True,
                    reason, "Widget continuation must preserve the exact raw-tree offset, total and actionable filter.")
                total = visited
                _require(offset < following < visited if data["hasMore"] else following == visited, reason,
                    "Widget pages must progress through a contiguous raw tree and finish at its actual total.")
            identities = {row["widgetId"] for row in rows if isinstance(row.get("widgetId"), str)}
            _require(not seen.intersection(identities), reason,
                "Repeated native widget identities across pages cannot prove complete current control coverage.")
            seen.update(identities)
            retained_bytes += json_size(rows)
            _require(retained_bytes <= 2_097_152 and len(widgets) + len(rows) <= 4096, reason,
                "Public control collection exceeded its independent bounded retention budget.")
            widgets.extend(rows)
            if data["hasMore"] is False:
                _require(data["partial"] is False and data["byteLimited"] is False, reason,
                    "A terminal partial or byte-limited widget page cannot establish complete coverage.")
                return {**data, "widgets": widgets, "offset": 0, "hasMore": False, "partial": False,
                    "scanTruncated": False, "byteLimited": False,
                    "widgetPageCoverage": {"pages": pages, "rawVisited": total,
                        "partialPages": partial_pages, "byteLimitedPages": byte_pages, "nonAtomic": True,
                        **({"refreshes": refreshes} if refreshes else {})}}
            offset = following
        raise _Unavailable(reason, "Public controls exceeded the positive-progress page budget.")

    async def creative_player_widgets(self, screen_id: str) -> dict[int, dict[str, Any]]:
        if self.actual_survival:
            data = await self.call("gui.inspect", {})
            _require(data.get("screenId") == screen_id and data.get("screen") == "net.minecraft.client.gui.inventory.GuiInventory"
                and data.get("containerSource") == "current_client_gui" and data.get("openContainerMatches") is True
                and type(data.get("windowId")) is int and data["windowId"] == data.get("playerOpenWindowId")
                and data.get("truncated") is False and isinstance(data.get("slots"), list)
                and type(data.get("totalSlots")) is int and data["totalSlots"] == len(data["slots"]),
                "survival_player_slot_coverage_unavailable", "The actual ordinary player container needs complete current native slot coverage.")
            mapped, native_slots = {}, set()
            for slot in data["slots"]:
                if slot.get("playerInventoryBindingVerified") is not True:
                    continue
                index, native_slot = slot.get("playerInventoryIndex"), slot.get("nativeContainerSlotId")
                _require(type(index) is int and 0 <= index < 36 and type(native_slot) is int and 0 <= native_slot <= 4095
                    and slot.get("slot") == native_slot and slot.get("inventorySource") == "current_player_inventory"
                    and slot.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
                    and "item" in slot and index not in mapped and native_slot not in native_slots,
                    "survival_player_slot_binding_unavailable", "Every real PlayerSlotBinding must be unique; slot arithmetic and creative wrappers are unavailable.")
                mapped[index] = {**slot, "windowId": data["windowId"]}
                native_slots.add(native_slot)
            _require(set(mapped) == set(range(36)), "survival_player_slot_coverage_unavailable",
                "All 36 real main player slots must have current public bindings.")
            return mapped
        mapped: dict[int, dict[str, Any]] = {}
        native_slots: set[int] = set()
        offset = 0
        for _ in range(16):
            data = await self.call("gui.widgets", {"screenId": screen_id, "actionableOnly": True, "offset": offset, "limit": 128})
            _require(data.get("screenId") == screen_id and isinstance(data.get("widgets"), list),
                     "creative_inventory_screen_changed", "Current native inventory widgets must remain bound to the exact screen.")
            for widget in data["widgets"]:
                if not isinstance(widget, dict) or widget.get("kind") != "creative_player_slot" \
                        or widget.get("valid") is not True or widget.get("enabled") is not True:
                    continue
                index, native_slot = widget.get("playerInventoryIndex"), widget.get("nativeContainerSlotId")
                if type(index) is not int or type(native_slot) is not int:
                    continue  # Never derive a player index from a creative wrapper or screen position.
                _require(widget.get("inventorySource") == "current_player_inventory"
                         and widget.get("inventoryBinding") == "public_isHere_unique_actual_player_container_slot"
                         and 0 <= index <= 127 and 0 <= native_slot <= 32_767 and isinstance(widget.get("widgetId"), str)
                         and bool(widget["widgetId"]) and isinstance(widget.get("supportedActions"), list)
                         and "activate" in widget["supportedActions"] and "item" in widget,
                         "creative_player_slot_mapping_unavailable", "Only explicitly verified actual player/container slot bindings are supported.")
                _require(index not in mapped and native_slot not in native_slots,
                         "creative_player_slot_mapping_ambiguous", "Actual player indices and native container identities must be unique.")
                mapped[index] = widget
                native_slots.add(native_slot)
            if data.get("hasMore") is False:
                _require(data.get("scanTruncated") is False and data.get("partial") is False and data.get("byteLimited") is False,
                         "creative_player_slot_coverage_incomplete", "Native inventory mapping coverage must be explicitly complete.")
                return mapped
            following = data.get("nextOffset")
            _require(data.get("hasMore") is True and type(following) is int and following > offset,
                     "creative_player_slot_coverage_incomplete", "Native inventory mapping requires a progressing public continuation.")
            offset = following
        raise _Unavailable("creative_player_slot_coverage_incomplete", "Actual native inventory widgets exceeded the bounded 16-page mapping scope.")

    async def open_creative_player_inventory(self, phase: str) -> tuple[str, dict[int, dict[str, Any]]]:
        _require("gui.open_inventory" in self.capabilities if isinstance(self.capabilities, list)
                 else self.capabilities.get("gui.open_inventory") is True,
                 "creative_inventory_capability_unavailable", "Automatic hand preparation requires the advertised native open-inventory operation.")
        self.opened_gui = None
        if self.actual_survival:
            report = await self.workflow(phase + "_open", [_action("close", "mcb_close_gui", {}),
                _action("open", "mcb_open_inventory", {}),
                _wait("inventory", "mcb_gui", {}, "screen", "net.minecraft.client.gui.inventory.GuiInventory",
                    fields=["screen", "screenId", "windowId"])])
            screen_id = self.value(report, "inventory", "screenId")
            _require(isinstance(screen_id, str) and bool(screen_id), "survival_inventory_screen_unavailable",
                "The normal survival inventory must expose its exact current screen identity.")
            return screen_id, await self.creative_player_widgets(screen_id)
        select_tab = _action("select_inventory", "mcb_gui_widget_action", {"action": "activate"})
        select_tab["bind"] = {"screenId": {"step": "inventory", "path": "screenId"},
            "widgetId": {"step": "inventory", "path": "creativeInventory.inventoryTabWidgetId"}}
        steps = [_action("close", "mcb_close_gui", {}),
            _action("open", "mcb_open_inventory", {}),
            _wait("inventory", "mcb_gui", {}, "creativeInventory.available", True, fields=["screenId", "windowId", "creativeInventory"]),
            select_tab,
            _wait("selected", "mcb_gui", {}, "creativeInventory.playerSlotsAvailable", True, fields=["screenId", "windowId", "creativeInventory"])]
        for key in ("screenId", "windowId"):
            assertion = _assert("same_" + key, "selected", key, None)
            assertion["condition"].pop("value")
            assertion["condition"]["valueFrom"] = {"step": "inventory", "path": key}
            steps.append(assertion)
        report = await self.workflow(phase + "_open", steps)
        screen_id = self.value(report, "inventory", "screenId")
        creative = self.value(report, "inventory", "creativeInventory")
        tab = creative.get("inventoryTabWidgetId") if isinstance(creative, dict) else None
        _require(isinstance(screen_id, str) and bool(screen_id) and isinstance(tab, str) and bool(tab),
                 "creative_inventory_tab_unavailable", "The actual creative inventory-tab widget identity must be public.")
        _require(self.value(report, "selected", "screenId") == screen_id,
                 "creative_inventory_screen_changed", "The native inventory screen changed during tab selection.")
        return screen_id, await self.creative_player_widgets(screen_id)

    async def move_native_player_stack(self, record: dict[str, Any], screen_id: str,
                                       source_index: int, destination_index: int, phase: str, *, close_after: bool = False) -> None:
        mapped = await self.creative_player_widgets(screen_id)
        source, destination = mapped.get(source_index), mapped.get(destination_index)
        _require(source is not None and destination is not None,
                 "creative_player_slot_mapping_unavailable", "Both explicitly observed player indices need actual native slot widgets.")
        identity, count = record["item"], record["count"]
        _require(_same(source.get("item"), identity) and _quantity(source["item"].get("count")) == count
                 and destination.get("item") is None, "borrowed_inventory_identity_changed",
                 "The exact borrowed stack and empty destination must remain unchanged before native movement.")
        record.update(sourceWidgetId=source.get("widgetId"), destinationWidgetId=destination.get("widgetId"),
            sourceNativeContainerSlotId=source["nativeContainerSlotId"], destinationNativeContainerSlotId=destination["nativeContainerSlotId"],
            nativeWindowId=source.get("windowId"))
        def pickup(identifier: str, binding: dict[str, Any]) -> dict[str, Any]:
            if self.actual_survival:
                return _action(identifier, "mcb_slot_click", {"windowId": binding["windowId"],
                    "slot": binding["nativeContainerSlotId"], "button": 0, "mode": 0})
            return _action(identifier, "mcb_gui_widget_action", {"screenId": screen_id,
                "widgetId": binding["widgetId"], "action": "activate"})
        steps = [_read("before", "mcb_inventory", fields=["slots", "cursorItem", "totalSlots", "truncated"]),
            _assert("cursor_empty", "before", "cursorItem", None),
            _assert("source_exact", "before", "slots", {"index": source_index, **_match(identity, count)}, "matches_one"),
            _assert("destination_empty", "before", "slots", {"index": destination_index, "item": None}, "matches_one"),
            pickup("pickup", source),
            _wait("carried", "mcb_inventory", {}, "cursorItem.count", count, fields=["cursorItem", "slots"]),
            _assert("carried_registry", "carried", "cursorItem.registryName", identity["registryName"]),
            _assert("carried_metadata", "carried", "cursorItem.metadata", identity["metadata"]),
            _assert("carried_untagged", "carried", "cursorItem.hasTag", False),
            _assert("source_empty", "carried", "slots", {"index": source_index, "item": None}, "matches_one"),
            pickup("deposit", destination),
            _wait("placed", "mcb_inventory", {}, "cursorItem", None, fields=["slots", "cursorItem", "totalSlots", "truncated"]),
            _assert("destination_exact", "placed", "slots", {"index": destination_index, **_match(identity, count)}, "matches_one"),
            _assert("source_still_empty", "placed", "slots", {"index": source_index, "item": None}, "matches_one")]
        if close_after:
            steps.append(_action("close", "mcb_close_gui", {}))
        self.current_inventory_move = record
        self.queue_recipe_context_guard()
        try:
            report = await self.workflow(phase, steps)
        except (BridgeError, TimeoutError):
            if self.phases and self.phases[-1].get("outcomeUnknown") is True:
                record["outcomeUnknown"] = True
            raise
        finally:
            self.current_inventory_move = None
        placed = {key: self.value(report, "placed", key) for key in ("slots", "cursorItem", "totalSlots", "truncated")}
        actual = inventory_slots(placed, "")
        source_slots = [slot for slot in actual if slot["index"] == source_index]
        destination_slots = [slot for slot in actual if slot["index"] == destination_index]
        _require(len(source_slots) == len(destination_slots) == 1 and source_slots[0]["item"] is None
                 and _same(destination_slots[0]["item"], identity) and _quantity(destination_slots[0]["item"].get("count")) == count
                 and placed["cursorItem"] is None, "borrowed_inventory_move_not_confirmed",
                 "Native movement needs an empty real cursor and exact real source/destination deltas.")
        await self.account_recipe_context_move(source_index, destination_index, placed, phase)

    async def prepare_empty_hand(self, missing_kinds: int, *, reserve: int | None = None, phase: str = "hand_preparation",
        protected_items: list[dict[str, Any]] | None = None) -> None:
        started = time.monotonic()
        summary: dict[str, Any] = {"phase": phase, "status": "running", "timingScope": "native_inventory_preparation_total"}
        self.phases.append(summary)
        try:
            self.required_empty_hotbar_slots = 1 + missing_kinds if reserve is None else reserve
            _require(1 <= self.required_empty_hotbar_slots <= 3, "hand_preparation_budget", "At most two material kinds and one preserved empty-hand slot are supported.")
            observation = await self.call("inventory.inspect", {})
            slots = inventory_slots(observation, "")
            _require(observation.get("cursorItem") is None, "carried_stack_not_empty", "Automatic hand preparation requires an empty real cursor.")
            hotbar = [slot for slot in slots if 0 <= slot["index"] <= 8]
            _require(len(hotbar) == 9, "hotbar_coverage_incomplete", "All nine actual hotbar indices must be observed.")
            empty_count = sum(slot["item"] is None for slot in hotbar)
            needed = max(0, self.required_empty_hotbar_slots - empty_count)
            summary.update(requiredEmptyHotbarSlots=self.required_empty_hotbar_slots, alreadyEmptyHotbarSlots=empty_count, movedStacks=needed)
            summary["singleFeedGrantCapacityProven"] = self.single_feed_grants
            if needed == 0:
                summary["status"] = "completed"
                return
            if protected_items is None:
                _require(self.recipe is not None, "recipe_decision_unavailable", "Hand preparation must follow a complete supported recipe decision.")
                protected = [self.recipe.material] + ([self.recipe.catalyst] if self.recipe.catalyst else [])
            else:
                protected = protected_items
            candidates = [slot for slot in sorted(hotbar, key=lambda slot: slot["index"]) if slot["index"] not in self.protected_player_indices() and isinstance(slot["item"], dict)
                and slot["item"].get("hasTag") is False and not any(_same(slot["item"], identity) for identity in protected)]
            storage = [slot["index"] for slot in sorted(slots, key=lambda slot: slot["index"]) if 9 <= slot["index"] <= 35 and slot["item"] is None]
            _require(len(candidates) >= needed and len(storage) >= needed,
                     "native_hand_preparation_space_unavailable", "Enough unrelated untagged hotbar stacks and actual empty player storage slots are required.")
            plans = [{"hotbarIndex": slot["index"], "storageIndex": storage[index], "item": _identity(slot["item"]),
                "count": _quantity(slot["item"].get("count")), "movementDispatched": False, "movementConfirmed": False,
                "restorationConfirmed": False, "status": "planned", "purpose": phase} for index, slot in enumerate(candidates[:needed])]
            _require(all(record["count"] > 0 for record in plans), "borrowed_inventory_quantity_unavailable", "Borrowed stacks need positive actual counts.")
            screen_id, mapped = await self.open_creative_player_inventory(phase)
            for record in plans:
                source, destination = mapped.get(record["hotbarIndex"]), mapped.get(record["storageIndex"])
                _require(source is not None and destination is not None and _same(source.get("item"), record["item"])
                         and _quantity(source["item"].get("count")) == record["count"] and destination.get("item") is None,
                         "creative_player_slot_mapping_unavailable", "Every planned source/storage pair must have an exact current native player binding before the first move.")
            for index, record in enumerate(plans):
                self.borrowed_inventory.append(record)
                await self.move_native_player_stack(record, screen_id, record["hotbarIndex"], record["storageIndex"],
                    ("hand_store_" if phase == "hand_preparation" else "hand_recovery_store_") + str(index),
                    close_after=index == len(plans) - 1)
                record.update(status="stored", movementConfirmed=True)
            after = await self.call("inventory.inspect", {})
            _require(after.get("cursorItem") is None and sum(slot["item"] is None for slot in inventory_slots(after, "")
                     if 0 <= slot["index"] <= 8) >= self.required_empty_hotbar_slots,
                     "native_hand_preparation_not_confirmed", "The real hotbar must retain enough empty slots for grants and normal empty-hand use.")
            summary["status"] = "completed"
        finally:
            elapsed = int((time.monotonic() - started) * 1000)
            self.hand_timings["handPreparationElapsed_ms"] += elapsed
            summary["elapsed_ms"] = elapsed
            if summary["status"] == "running":
                summary["status"] = "failed"

    async def restore_borrowed_inventory(self) -> None:
        if not self.borrowed_inventory:
            return
        started = time.monotonic()
        summary: dict[str, Any] = {"phase": "hand_restoration", "status": "running", "timingScope": "native_inventory_restoration_total"}
        self.phases.append(summary)
        try:
            before = await self.call("inventory.inspect", {})
            slots = inventory_slots(before, "")
            _require(before.get("cursorItem") is None, "inventory_restoration_cursor_not_empty", "Restoration requires an empty real cursor.")
            for record in self.borrowed_inventory:
                source = [slot for slot in slots if slot["index"] == record["storageIndex"]]
                destination = [slot for slot in slots if slot["index"] == record["hotbarIndex"]]
                _require(record.get("movementConfirmed") is True and len(source) == len(destination) == 1
                         and _same(source[0].get("item"), record["item"]) and _quantity(source[0]["item"].get("count")) == record["count"]
                         and destination[0].get("item") is None, "inventory_restoration_identity_changed",
                         "Restore only unchanged recorded storage stacks into their original actually empty hotbar slots.")
            screen_id, _ = await self.open_creative_player_inventory("hand_restoration")
            for index, record in enumerate(self.borrowed_inventory):
                record["status"] = "restoring"
                await self.move_native_player_stack(record, screen_id, record["storageIndex"], record["hotbarIndex"], "hand_restore_" + str(index),
                    close_after=index == len(self.borrowed_inventory) - 1)
                record.update(status="restored", restorationConfirmed=True)
            summary.update(status="completed", restoredStacks=len(self.borrowed_inventory))
            await self.read_recipe_context_inventory()
            self.inventory_verification_attempted = self.final_inventory_verified = True
            self.final_cursor_empty = True
        finally:
            self.hand_timings["handRestorationElapsed_ms"] = int((time.monotonic() - started) * 1000)
            summary["elapsed_ms"] = self.hand_timings["handRestorationElapsed_ms"]
            if summary["status"] == "running":
                summary["status"] = "failed"

    async def verify_retained_inventory(self, *, on_failure: bool = False) -> None:
        """Confirm intentionally retained actual stacks without undoing a native move."""
        self.inventory_verification_attempted = True
        self.phase = "inventory_retention_verification"
        started = time.monotonic()
        summary: dict[str, Any] = {"phase": self.phase, "status": "running", "timingScope": "final_inventory_read_total"}
        self.phases.append(summary)
        for record in self.borrowed_inventory:
            if record.get("restorationConfirmed") is not True:
                record["retentionConfirmed"] = False
        try:
            with anyio.CancelScope(shield=on_failure), anyio.fail_after(1 if on_failure else self.remaining() / 1000):
                observation, snapshot = await self.read_with_snapshot("inventory.inspect")
                _require(snapshot.get("world", {}).get("dimension") == self.dimension,
                         "retained_inventory_context_changed", "Final inventory verification requires the original actual world/dimension.")
                slots = inventory_slots(observation, "")
                _require({slot.get("index") for slot in slots} >= set(range(36)), "inventory_coverage_incomplete",
                         "Final verification requires actual indices for every normal player inventory slot.")
                self.final_cursor_empty = observation["cursorItem"] is None if "cursorItem" in observation else None
                summary["cursorEmpty"] = self.final_cursor_empty
                _require(self.final_cursor_empty is True, "retained_inventory_cursor_not_empty",
                         "The final actual player cursor must be explicitly observed empty.")
                if self.recipe_context_inventory is not None:
                    full = await self.read_recipe_context_inventory()
                    _require(self.recipe_context_public_rows(slots) == self.recipe_context_public_rows(full),
                        "recipe_context_public_inventory_changed", "Final complete public slots must match both fresh full-NBT inventories.")
                for record in self.borrowed_inventory:
                    if record.get("restorationConfirmed") is True:
                        continue
                    current = [slot for slot in slots if slot.get("index") == record["storageIndex"]]
                    _require(record.get("movementConfirmed") is True and record.get("outcomeUnknown") is not True
                             and len(current) == 1 and _same(current[0].get("item"), record["item"])
                             and _quantity(current[0]["item"].get("count")) == record["count"],
                             "retained_inventory_identity_changed", "Only confirmed unchanged stacks at their actual recorded player storage indices can be intentionally retained.")
                    record.update(status="retained", retentionConfirmed=True)
                self.final_inventory_verified = True
                summary.update(status="completed", retainedStacks=sum(record.get("retentionConfirmed") is True for record in self.borrowed_inventory))
        except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as error:
            self.final_inventory_verified = False
            summary.update(status="failed", reason=error.details.get("reason", error.code) if isinstance(error, BridgeError)
                else "retained_inventory_read_unavailable")
            if not on_failure:
                raise
        finally:
            summary["elapsed_ms"] = int((time.monotonic() - started) * 1000)

    async def prove_single_feed_grants(self, required: list[tuple[dict[str, Any], int]], missing_kinds: int) -> bool:
        """Relax only the hand reserve, using actual capacity and publicly observed stack limits."""
        if self.args.get("executions", 1) != 1 or not missing_kinds:
            return False
        capable = "registry.item" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("registry.item") is True
        if not capable:
            return False
        observation = await self.call("inventory.inspect", {})
        slots = inventory_slots(observation, "")
        normal = [slot for slot in slots if 0 <= slot["index"] <= 35]
        if observation.get("cursorItem") is not None or {slot["index"] for slot in normal} != set(range(36)):
            return False
        counts = [item_count(self.ordinary_source_slots(slots), identity) for identity, _ in required]
        if any(count > amount for count, (_, amount) in zip(counts, required)):
            return False  # A remainder must retain the original safe hand reserve.
        absent = sum(count == 0 for count in counts)
        storage_empty = sum(slot["item"] is None for slot in normal if 9 <= slot["index"] <= 35)
        if storage_empty < absent:
            return False  # Storage proves grant capacity; it is never used as a hotbar index.
        limits = []
        for count, (identity, amount) in zip(counts, required):
            if count >= amount:
                continue
            try:
                observed = await self.call("registry.item", identity)
            except BridgeError:
                return False
            limit = observed.get("stackLimit")
            if not _same(observed, identity) or type(limit) is not int or not amount <= limit <= 64:
                return False
            limits.append({**identity, "stackLimit": limit, "finalPlayerCount": amount})
        self.grant_capacity_proof = {"singleExecution": True, "noPlayerRemainder": True,
            "actualEmptyStorageSlots": storage_empty, "newSourceStacks": absent, "publicStackLimits": limits,
            "source": "Complete real player indices 0..35 and registry.item stackLimit; finite GUI feed will remove each exact source."}
        return True

    async def ensure_materials(self, required: list[tuple[dict[str, Any], int]], *, grant: bool = True) -> int:
        inventory = await self.call("inventory.inspect", {})
        slots = self.ordinary_source_slots(inventory_slots(inventory, ""))
        plans: list[tuple[dict[str, Any], int, int]] = []
        for identity, amount in required:
            count = item_count(slots, identity)
            matches = [slot for slot in slots if _same(slot.get("item"), identity)]
            _require(len(matches) <= 1, "material_source_missing_or_ambiguous", "Split or ambiguous actual source stacks are unsupported before generation.")
            if count >= amount:
                unique_source(slots, identity, amount)
                continue
            _require(self.args.get("generateMaterials", False) is True, "materials_missing",
                     "Required actual materials are missing; generation is off unless explicitly requested.")
            snapshot = await self.snapshot()
            name = snapshot.get("player", {}).get("name")
            _require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is not None,
                     "player_command_identity_unavailable", "The actual singleplayer command target must be an observed normal player name.")
            missing = amount - count
            _require(1 <= missing <= 64 and amount <= 64, "material_generation_budget", "One declared material grant/merged source is limited to 64 actual items.")
            _require(len(f"/give {name} {identity['registryName']} {missing} {identity['metadata']}") <= 100,
                     "material_command_budget", "The declared grant exceeds the native chat packet limit.")
            plans.append((identity, amount, missing))
        absent = sum(item_count(slots, identity) == 0 for identity, _, _ in plans)
        empty_normal = sum(slot["item"] is None for slot in slots if 0 <= slot["index"] <= 35)
        _require(empty_normal >= absent, "material_generation_capacity_unavailable",
                 "Each missing source stack needs an actual empty normal player slot before any native grant; armor and cursor do not count.")
        if not grant:
            return len(plans)
        batch = "inventory.supply" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("inventory.supply") is True
        if plans and self.inventory_preparation_mode == "clear" and self.args.get("generateMaterials", False) is True and batch:
            await self.supply_cleared_materials(required, plans, inventory)
            return len(plans)
        if self.single_feed_grants:
            _require(all(item_count(slots, identity) <= amount for identity, amount in required),
                     "single_feed_source_changed", "Single-feed grants require every current source to remain within the exact finite feed amount.")
            _require(await self.reuse_gui("input") is not None, "single_feed_input_gui_changed_before_grant",
                     "The native input GUI must still have fresh exact screen/window, range and visible-face proof before reduced-reserve grants.")
        for identity, amount, missing in plans:
            snapshot = await self.snapshot()
            name = snapshot.get("player", {}).get("name")
            _require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is not None,
                     "player_command_identity_unavailable", "The actual singleplayer command target must be an observed normal player name.")
            command = f"/give {name} {identity['registryName']} {missing} {identity['metadata']}"
            _require(len(command) <= 100, "material_command_budget", "The declared grant exceeds the native chat packet limit.")
            self.material_supply_mode = "native_chat_give_commands"
            self.queue_recipe_context_guard()
            await self.workflow("generate_material", [_action("give", "mcb_chat", {"message": command}),
                _wait("received", "mcb_inventory", {}, "slots", _match(identity, amount), operator="matches_one")])
            inventory = await self.call("inventory.inspect", {})
            slots = self.ordinary_source_slots(inventory_slots(inventory, ""))
            supplied = unique_source(slots, identity, amount)
            await self.account_recipe_context_item(supplied["index"], identity, missing, inventory, "generate_material")
        if plans:
            self.material_supply_confirmed = True
        return len(plans)

    async def supply_cleared_materials(self, required: list[tuple[dict[str, Any], int]],
        plans: list[tuple[dict[str, Any], int, int]], inventory: dict[str, Any]) -> None:
        """One finite native supply after explicit clear; receipts never replace real inventory acceptance."""
        _require(self.inventory_clear_confirmed and not self.args.get("restoreInventory", False)
            and 1 <= len(plans) <= 8 and len(plans) == len(required)
            and len({(item["registryName"], item["metadata"]) for item, _, _ in plans}) == len(plans)
            and all(type(amount) is int and 1 <= amount <= 64 and missing == amount for _, amount, missing in plans),
            "native_supply_scope_unavailable", "Native recipe supply requires this task's confirmed clear and distinct finite exact missing item demands.")
        self.validate_native_supply_inventory(inventory, empty=True)
        capable = "registry.item" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("registry.item") is True
        _require(capable, "native_supply_registry_unavailable", "Native supply requires current public registry identity and stack limits before dispatch.")
        for identity, amount, _ in plans:
            public = await self.call("registry.item", identity)
            limit = public.get("stackLimit")
            _require(_same(public, identity) and type(limit) is int and 1 <= amount <= min(64, limit),
                "native_supply_registry_unavailable", "Every finite native supply must retain its exact untagged public identity and real stack limit.")
        self.material_supply_mode = "native_server_give_commands"
        items = [{**identity, "count": amount} for identity, amount, _ in plans]
        supply = _action("supply", "mcb_supply_items", {"expectedSessionId": self.args["expectedSessionId"],
            "expectedWorld": self.args["expectedWorld"], "items": items})
        supply["fields"] = ["requestedCount", "commandsAttempted", "commandsAccepted", "complete", "outcomeUnknown"]
        report = await self.workflow("supply_recipe_materials", [supply])
        receipt = {key: self.value(report, "supply", key) for key in supply["fields"]}
        _require(receipt["complete"] is True and receipt["outcomeUnknown"] is False
            and all(type(receipt[key]) is int and receipt[key] == len(items)
                for key in ("requestedCount", "commandsAttempted", "commandsAccepted")),
            "native_supply_receipt_incomplete", "The native supply receipt is partial, unknown or inconsistent; no grant is replayed or replaced with chat.")
        self.phase = "verify_native_material_supply"
        started = time.monotonic()
        phase = {"phase": self.phase, "status": "running", "attempts": 0,
            "declaredSupplyStacks": len(required), "timingScope": "native_inventory_batch_wait"}
        self.phases.append(phase)
        try:
            while True:
                actual, snapshot = await self.read_with_snapshot("inventory.inspect")
                _require(snapshot.get("world", {}).get("dimension") == self.dimension,
                    "native_supply_context_changed", "Supply acceptance requires the original actual session/world/dimension.")
                slots = self.validate_native_supply_inventory(actual)
                phase["attempts"] += 1
                ready = sum(slot["item"] is not None for slot in slots) == len(required)
                for identity, amount in required:
                    sources = [slot for slot in slots if _same(slot["item"], identity)]
                    ready &= len(sources) == 1 and 0 <= sources[0]["index"] <= 35 \
                        and type(sources[0]["item"].get("count")) is int and sources[0]["item"]["count"] == amount
                if ready:
                    self.material_supply_confirmed = True
                    phase.update(status="completed", completeSlotCoverage=41, completeBatchVerified=True)
                    return
                await anyio.sleep(min(.05, self.remaining() / 1000))
        finally:
            if phase["status"] == "running":
                phase["status"] = "failed"
            phase["elapsed_ms"] = int((time.monotonic() - started) * 1000)

    @staticmethod
    def validate_native_supply_inventory(observation: dict[str, Any], *, empty: bool = False) -> list[dict[str, Any]]:
        slots = inventory_slots(observation, "")
        _require(len(slots) == 41 and {slot["index"] for slot in slots} == set(range(41)),
            "native_supply_inventory_coverage_unavailable", "Native supply requires complete actual player indices 0..40, including armor.")
        _require("cursorItem" in observation and observation["cursorItem"] is None,
            "native_supply_cursor_not_empty", "Native supply requires an explicitly empty actual player cursor.")
        _require(not empty or all(slot["item"] is None for slot in slots),
            "native_supply_inventory_not_empty", "Native supply requires a fresh complete empty actual player inventory before dispatch.")
        return slots

    async def stop_on_failure(self) -> None:
        if not self.power_dispatched or self.machine_id is None:
            return
        try:
            with anyio.CancelScope(shield=True):
                report = await self.workflow("emergency_stop", [_action("stop", "mcb_machine_work", {**self.position("machine"),
                    "expectedWorld": self.args["expectedWorld"], "expectedMetaTileId": self.machine_id, "working": False}),
                    _wait("stopped", "mcb_machine_inspect", self.position("machine"), "state.allowedToWork", False)], emergency=True)
                self.phases[-1]["serverStopConfirmed"] = report.get("status") == "completed"
        except (BridgeError, TimeoutError):
            pass  # The separate guarded cleanup receipt already reports any uncertain stop.

    async def remaining_inputs_on_failure(self) -> dict[str, Any]:
        """Read the actual declared input after a dispatched feed; never replay or undo it."""
        try:
            with anyio.CancelScope(shield=True), anyio.fail_after(1):
                snapshot = await self.tracked_client.call("client.snapshot", {}, expected_credentials=self.credentials)
                world = snapshot.get("world", {})
                _require(snapshot.get("sessionId") == self.args["expectedSessionId"] and snapshot.get("worldLoaded") is True
                         and self.args["expectedWorld"] in (world.get("worldName"), world.get("worldFolder"))
                         and world.get("dimension") == self.dimension,
                         "remaining_inputs_context_changed", "Remaining inputs require the original real session/world/dimension.")
                observation = await self.tracked_client.call("world.inspect", self.position("input"),
                    expected_credentials=self.credentials)
                slots = inventory_slots(observation)
                return {"available": True, **self.position("input"),
                    "slots": [slot for slot in slots if slot["item"] is not None], "totalSlots": len(slots),
                    "scope": "shared_machine_inventory" if self.single_inventory else "declared_input_inventory",
                    "source": "Fresh complete actual declared inventory after the dispatched native feed; shared single-block inventory also includes actual outputs; no rollback."}
        except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as error:
            return {"available": False, "reason": error.details.get("reason", error.code) if isinstance(error, BridgeError)
                else "remaining_inputs_read_unavailable", "source": "Actual remaining input inventory could not be confirmed; no count inferred."}

    def validate_single_machine_api(self, machine: dict[str, Any]) -> None:
        if "singleBlockRecipeMachine" not in machine:
            _require(machine.get("metaClass") == BASIC_MACHINE_API and self.single_api_proof is None,
                     "single_machine_api_unavailable", "A shared inventory needs the exact legacy BasicMachineWithRecipe class or a current bound public BasicMachine runtime proof; observed proof cannot disappear.")
            return
        proof = machine["singleBlockRecipeMachine"]
        _require(isinstance(proof, dict) and type(proof.get("schemaVersion")) is int and proof["schemaVersion"] == 1
                 and proof.get("apiClass") == COMMON_BASIC_MACHINE_API and proof.get("source") == "public_runtime_api"
                 and proof.get("runtimeMember") is True and isinstance(proof.get("owner"), dict)
                 and isinstance(machine.get("sessionId"), str) and machine["sessionId"] == self.args["expectedSessionId"],
                 "single_machine_api_unavailable", "The shared machine requires a typed version-1 affirmative public BasicMachine runtime proof in this pinned session.")
        owner = proof["owner"]
        _require(all(type(owner.get(key)) is int and type(machine.get(key)) is int and owner[key] == machine[key]
                     for key in ("metaTileId", "x", "y", "z", "dimension"))
                 and isinstance(owner.get("metaClass"), str) and owner["metaClass"] == machine.get("metaClass")
                 and all(owner[axis] == value for axis, value in self.position("machine").items())
                 and (self.machine_id is None or owner["metaTileId"] == self.machine_id)
                 and (self.machine_class is None or owner["metaClass"] == self.machine_class),
                 "single_machine_api_unavailable", "The public BasicMachine runtime proof must identify the actual declared machine class, ID, coordinates and dimension.")
        current = {"schemaVersion": proof["schemaVersion"], "apiClass": proof["apiClass"], "source": proof["source"],
            "runtimeMember": True, "owner": {key: owner[key] for key in ("metaTileId", "metaClass", "x", "y", "z", "dimension")},
            "sessionId": machine["sessionId"]}
        _require(self.single_api_proof is None or current == self.single_api_proof,
                 "single_machine_api_unavailable", "The current bound public BasicMachine runtime proof must remain unchanged throughout this task.")
        self.single_api_proof = current
        if self.ghost_slot_proof is not None:
            self.validate_ghost_slot_api(machine)

    def validate_registered_ports(self, machine: dict[str, Any]) -> None:
        if self.single_inventory:
            _require(self.single_api_verified,
                     "single_machine_api_unavailable", "Only the actually observed common BasicMachine API is supported for a shared inventory.")
            self.validate_single_machine_api(machine)
            self.core_stop_coverage = "not_applicable_single_block"
            self.phases.append({"phase": "single_machine_binding", "status": "completed", "metaClass": machine["metaClass"],
                "source": "Current bound public BasicMachine runtime proof and caller-declared identical machine/input/output coordinates."
                    if self.single_api_proof is not None else "Exact current legacy BasicMachineWithRecipe runtime class and caller-declared identical machine/input/output coordinates.",
                "structureFormationClaimed": False, "registeredCoreStopClaimed": False})
            return
        self.registered_multiblock_verified = False
        _require(machine.get("state", {}).get("active") is False and machine.get("state", {}).get("allowedToWork") is False
            and machine.get("processing", {}).get("structureFormed") is True, "formed_stopped_machine_required",
            "Registered multiblock binding requires a formed, inactive and disabled actual controller.")
        entries, self.core_stop_coverage, coverage_reason = registered_components(machine.get("components"), stopped=True)
        for role, target in (("item_input", "input"), ("item_output", "output")):
            ports = [entry for entry in entries if entry.get("role") == role]
            _require(len(ports) == 1 and ports[0].get("valid") is True and ports[0].get("dimension") == self.dimension
                     and all(ports[0].get(axis) == self.args[target][axis] for axis in ("x", "y", "z")),
                     "isolated_port_binding_unavailable", "Exactly one valid registered input/output must match the explicitly declared port.")
            if self.provider_cache and role == "item_output":
                port = ports[0]
                _require(type(port.get("metaTileId")) is int and 0 <= port["metaTileId"] <= 32767
                    and isinstance(port.get("metaClass"), str) and 1 <= len(port["metaClass"]) <= 256
                    and all(type(port.get(key)) is int for key in ("x", "y", "z", "dimension")),
                    "provider_registered_owner_unavailable", "Local cache acceptance requires the actual typed registered output owner identity.")
                owner = {key: port[key] for key in OWNER_KEYS}
                _require(self.output_provider_owner is None or owner == self.output_provider_owner,
                    "provider_registered_owner_changed", "The registered local output-provider owner changed.")
                self.output_provider_owner = owner
        self.phases.append({"phase": "registered_component_preflight", "status": "completed",
            "registeredCoreStopCoverage": self.core_stop_coverage, "coverageReason": coverage_reason})
        self.registered_multiblock_verified = True

    def provider_output_args(self) -> dict[str, Any]:
        _require(self.recipe is not None and self.output_provider_owner is not None,
            "provider_recipe_owner_unavailable", "Resolve the raw product and registered output owner before querying local cache acceptance.")
        return {**self.position("output"), "outputQueries": [{"kind": "item", **self.recipe.output}]}

    def provider_output_count(self, observation: dict[str, Any]) -> int:
        _require(self.recipe is not None and self.output_provider_owner is not None,
            "provider_recipe_owner_unavailable", "The selected product and pinned output owner are required.")
        return provider_count(observation, PROVIDER_QUERY_PATH,
            provider_match(self.output_provider_owner, self.recipe.output, self.args["expectedSessionId"]))

    def validate_provider_output_inventory(self, observation: dict[str, Any]) -> None:
        owner = self.output_provider_owner
        gt = observation.get("gregtech")
        _require(owner is not None and observation.get("source") == "integrated_server"
            and all(type(observation.get(key)) is int and observation[key] == owner[key] for key in ("x", "y", "z", "dimension"))
            and isinstance(gt, dict) and type(gt.get("metaTileId")) is int and gt["metaTileId"] == owner["metaTileId"]
            and gt.get("metaClass") == owner["metaClass"], "provider_inventory_owner_changed",
            "The actual output inventory must still belong to the pinned registered output provider.")
        _require(all(slot["item"] is None for slot in inventory_slots(observation)),
            "provider_inventory_not_empty", "This local-cache task requires a complete empty actual output inventory, including its possible cell slot.")

    async def read_provider_output(self) -> int:
        self.validate_provider_output_inventory(await self.call("world.inspect", self.position("output")))
        return self.provider_output_count(await self.call("machine.inspect", self.provider_output_args()))

    async def read_inventory_output_baseline(self, expected: int, phase: str) -> dict[str, Any]:
        self.phase = phase
        observation = await self.call("world.inspect", self.position("output"))
        if self.single_inventory:
            self.validate_single_tanks(observation)
        actual = item_count(inventory_slots(observation), self.recipe.output)
        _require(actual == expected, "output_baseline_changed",
                 "The complete actual product quantity drifted from the initial or last accepted baseline; later feed/power is blocked without replay.")
        self.phases.append({"phase": phase, "status": "completed", "outputCount": actual,
                            "expectedOutputCount": expected, "scope": "Complete actual inventory product quantity before the next native feed or power action."})
        return observation

    def bind_input_inventory_owner(self, observation: dict[str, Any]) -> None:
        gt = observation.get("gregtech")
        _require(observation.get("source") == "integrated_server"
                 and all(type(observation.get(key)) is int and observation[key] == value for key, value in self.position("input").items())
                 and isinstance(observation.get("registryName"), str)
                 and re.fullmatch(_ITEM["properties"]["registryName"]["pattern"], observation["registryName"]) is not None
                 and isinstance(gt, dict) and type(gt.get("metaTileId")) is int and 0 <= gt["metaTileId"] <= 32_767
                 and isinstance(gt.get("metaClass"), str) and 0 < len(gt["metaClass"]) <= 256,
                 "input_gui_owner_unavailable", "Automatic input discovery requires the exact complete integrated-server input machine owner identity.")
        owner = {**self.position("input"), "registryName": observation["registryName"],
            "metaTileId": gt["metaTileId"], "metaClass": gt["metaClass"]}
        _require(self.input_inventory_owner is None or owner == self.input_inventory_owner,
                 "input_gui_owner_changed", "Fresh input inventory evidence must retain the original actual server owner identity.")
        self.input_inventory_owner = owner

    def validate_auto_input_inventory(self, server: dict[str, Any]) -> list[dict[str, Any]]:
        self.bind_input_inventory_owner(server)
        slots = inventory_slots(server)
        _require(self.recipe is not None and all(slot["item"] is None
                     or self.recipe.catalyst is not None and _same(slot["item"], self.recipe.catalyst)
                     or self.single_inventory and _same(slot["item"], self.recipe.output) for slot in slots),
                 "input_not_isolated", "Fresh automatic material discovery requires the same isolated real input inventory after earlier native transfers.")
        if self.recipe.catalyst is not None and not self.configured_ghost:
            _require(item_count(slots, self.recipe.catalyst) == 1, "catalyst_transfer_mismatch",
                     "The one actual physical catalyst must remain retained before discovering a new empty material destination.")
        if self.single_inventory:
            self.validate_single_tanks(server)
        if self.configured_ghost:
            self.validate_configured_ghost_inventory(slots)
        return slots

    async def refresh_auto_input_gui(self, gui: dict[str, Any]) -> dict[str, Any]:
        self.validate_auto_input_binding(gui)
        if self.catalyst_gui_binding is not None:
            self.validate_auto_catalyst_binding(gui)
        server = await self.call("world.inspect", self.position("input"))
        slots = self.validate_auto_input_inventory(server)
        indexed = {slot["index"]: slot["item"] for slot in slots}
        empty = [slot for slot in self.current_machine_gui_bindings(gui)
            if slot.get("machineInventoryRole") == "consumed_input" and slot.get("consumedInput") is True
            and slot["machineInventoryIndex"] in indexed and indexed[slot["machineInventoryIndex"]] is None]
        if "inputGuiSlot" in self.args:
            empty = [slot for slot in empty if slot["slot"] == self.args["inputGuiSlot"]]
        _require(bool(empty), "input_gui_binding_unavailable", "A fresh actual consumed-input server entry must be empty before material pickup.")
        selected = min(empty, key=lambda slot: slot["machineInventoryIndex"])
        if selected.get("item") is not None:
            synced = await self.workflow("automatic_input_destination_sync", [
                _wait("destination", "mcb_gui", {}, "slots", {"slot": selected["slot"], "item": None},
                    operator="matches_one", fields=["screenId", "windowId", "slots", "totalSlots", "truncated", "wholeStackTransfer"]),
                _assert("same_window", "destination", "windowId", gui["windowId"]),
                _assert("same_screen", "destination", "screenId", gui["screenId"])])
            gui = {key: self.value(synced, "destination", key) for key in ("screenId", "windowId", "slots", "totalSlots", "truncated")}
            preserve_gui_capability(synced, "destination", gui)
            self.validate_auto_input_binding(gui)
            if self.catalyst_gui_binding is not None:
                self.validate_auto_catalyst_binding(gui)
            server = await self.call("world.inspect", self.position("input"))
            slots = self.validate_auto_input_inventory(server)
        self.resolve_input_gui_slot(gui, slots, exclude_catalyst=False)
        return gui

    def current_machine_gui_bindings(self, gui: dict[str, Any]) -> list[dict[str, Any]]:
        _require(self.input_inventory_owner is not None, "input_gui_owner_unavailable", "The actual server input owner must be proven before automatic GUI discovery.")
        bindings = []
        for slot in inventory_slots(gui, ""):
            owner = slot.get("machineInventoryOwner")
            if slot.get("machineInventoryBindingVerified") is True and slot.get("machineInventoryBinding") == MACHINE_SLOT_BINDING \
                    and slot.get("playerInventoryBindingVerified") is False and isinstance(owner, dict) \
                    and all(type(owner.get(key)) is type(value) and owner[key] == value for key, value in self.input_inventory_owner.items()):
                _require(type(slot.get("machineInventoryIndex")) is int and slot["machineInventoryIndex"] >= 0
                         and type(slot.get("slot")) is int and 0 <= slot["slot"] <= 511,
                         "input_gui_binding_unavailable", "An actual machine binding needs typed server inventory and native GUI slot identities.")
                bindings.append(slot)
        return bindings

    def resolve_input_gui_slot(self, gui: dict[str, Any], server_slots: list[dict[str, Any]], *, exclude_catalyst: bool = True) -> None:
        bindings = self.current_machine_gui_bindings(gui)
        indexed = {slot["index"]: slot["item"] for slot in server_slots}
        eligible = [slot for slot in bindings if slot.get("machineInventoryRole") == "consumed_input" and slot.get("consumedInput") is True]
        _require(all(slot["machineInventoryIndex"] in indexed for slot in eligible), "input_gui_binding_unavailable",
                 "Public consumed-input indices must belong to the complete actual server inventory.")
        excluded = {self.catalyst_gui_slot} if exclude_catalyst and self.recipe and self.recipe.catalyst and not self.configured_ghost else set()
        empty = [slot for slot in eligible if slot["item"] is None and indexed[slot["machineInventoryIndex"]] is None and slot["slot"] not in excluded]
        if "inputGuiSlot" in self.args:
            empty = [slot for slot in empty if slot["slot"] == self.args["inputGuiSlot"]]
        _require(bool(empty), "input_gui_binding_unavailable", "The opened GUI exposes no supported publicly bound empty consumed-input machine destination; specify an observed inputGuiSlot.")
        selected = min(empty, key=lambda slot: slot["machineInventoryIndex"])
        actual_index = selected["machineInventoryIndex"]
        _require(len([slot for slot in bindings if slot["machineInventoryIndex"] == actual_index]) == 1,
                 "input_gui_binding_ambiguous", "The selected actual machine input index must map uniquely to one native GUI slot.")
        _require(not self.configured_ghost or self.ghost_circuit is not None and actual_index != self.ghost_circuit["inventoryIndex"],
                 "configured_ghost_destination_overlap", "A material destination cannot address the actual virtual circuit backing entry.")
        previous = self.input_gui_binding
        self.input_gui_slot = selected["slot"]
        self.input_gui_binding = {"guiSlot": self.input_gui_slot, "inventoryIndex": actual_index,
            "owner": self.input_inventory_owner, "binding": MACHINE_SLOT_BINDING, "role": "consumed_input",
            "selection": "caller_declared_consumed_input" if "inputGuiSlot" in self.args else "lowest_empty_consumed_input_index"}
        if previous is not None and self.declared_gui_slots:
            self.declared_gui_slots[0] = {"guiSlot": self.input_gui_slot, "inventoryIndex": actual_index}
        if previous != self.input_gui_binding:
            self.phases.append({"phase": "input_gui_rediscovery" if previous is not None else "input_gui_discovery",
                "status": "completed", **self.input_gui_binding,
                **({"previousGuiSlot": previous["guiSlot"], "previousInventoryIndex": previous["inventoryIndex"]} if previous is not None else {})})

    def validate_auto_input_binding(self, gui: dict[str, Any]) -> None:
        _require(self.input_gui_binding is not None, "input_gui_binding_unavailable", "An automatic input mapping must be resolved before transfer.")
        mapped = [slot for slot in self.current_machine_gui_bindings(gui)
            if slot["machineInventoryIndex"] == self.input_gui_binding["inventoryIndex"]]
        _require(len(mapped) == 1 and mapped[0]["slot"] == self.input_gui_slot
                 and mapped[0].get("machineInventoryRole") == "consumed_input" and mapped[0].get("consumedInput") is True,
                 "input_gui_binding_changed", "The same actual server owner, consumed-input index and unique native GUI slot binding must remain current before pickup.")

    def resolve_catalyst_gui_slot(self, gui: dict[str, Any], server_slots: list[dict[str, Any]], *, retained: bool) -> None:
        _require((self.single_api_verified or self.registered_multiblock_verified)
                 and self.recipe is not None and self.recipe.catalyst is not None and not self.configured_ghost,
                 "catalyst_gui_binding_unavailable", "Automatic physical catalyst discovery requires a verified registered machine, actual input owner and raw catalyst identity.")
        indexed = {slot["index"]: slot["item"] for slot in server_slots}
        bindings = self.current_machine_gui_bindings(gui)
        eligible = [slot for slot in bindings if slot.get("machineInventoryRole") == "consumed_input"
            and slot.get("consumedInput") is True and slot["slot"] != self.input_gui_slot
            and slot["machineInventoryIndex"] in indexed]
        candidates = [slot for slot in eligible if _same(slot.get("item"), self.recipe.catalyst)
            and _same(indexed[slot["machineInventoryIndex"]], self.recipe.catalyst)
            and _quantity(slot["item"].get("count")) == _quantity(indexed[slot["machineInventoryIndex"]].get("count")) == 1] if retained \
            else [slot for slot in eligible if slot["item"] is None and indexed[slot["machineInventoryIndex"]] is None]
        _require(bool(candidates) and (not retained or len(candidates) == 1), "catalyst_gui_binding_unavailable",
                 "The real physical catalyst needs a unique retained public input binding or a second actual empty consumed-input destination.")
        selected = min(candidates, key=lambda slot: slot["machineInventoryIndex"])
        actual_index = selected["machineInventoryIndex"]
        _require(len([slot for slot in bindings if slot["machineInventoryIndex"] == actual_index]) == 1
                 and (self.input_gui_binding is None or self.input_gui_binding["inventoryIndex"] != actual_index),
                 "catalyst_gui_binding_ambiguous", "The catalyst actual inventory index and native destination must be unique and separate from material.")
        self.catalyst_gui_slot = selected["slot"]
        self.catalyst_gui_binding = {"guiSlot": self.catalyst_gui_slot, "inventoryIndex": actual_index,
            "owner": self.input_inventory_owner, "binding": MACHINE_SLOT_BINDING, "role": "consumed_input",
            "selection": "unique_retained_catalyst" if retained else "lowest_empty_separate_consumed_input_index"}
        self.phases.append({"phase": "catalyst_gui_discovery", "status": "completed", **self.catalyst_gui_binding})

    async def preflight_physical_destinations(self, server_slots: list[dict[str, Any]], *, retained: bool) -> None:
        """Resolve omitted physical destinations before clear, grant or pickup."""
        gui = await self.open_gui("input")
        if self.catalyst_gui_slot is None:
            bindings = self.current_machine_gui_bindings(gui)
            indexed = {slot["index"]: slot["item"] for slot in server_slots}
            _require(all(slot["machineInventoryIndex"] in indexed and slot["item"] == indexed[slot["machineInventoryIndex"]]
                         and ("nativeContainerSlotId" not in slot or type(slot["nativeContainerSlotId"]) is int
                             and slot["nativeContainerSlotId"] == slot["slot"]) for slot in bindings),
                     "catalyst_gui_binding_unavailable", "All actual machine bindings must retain complete synchronized server/native slot identities before automatic catalyst selection.")
            self.resolve_input_gui_slot(gui, server_slots)
            self.resolve_catalyst_gui_slot(gui, server_slots, retained=retained)
        elif self.input_gui_slot is None:
            self.resolve_input_gui_slot(gui, server_slots)
        if self.single_inventory:
            self.validate_declared_single_slots(gui)
        _require(self.input_gui_slot != self.catalyst_gui_slot, "catalyst_destination_required",
                 "The real material and physical catalyst native destinations must be separate.")
        for destination in (self.input_gui_slot, self.catalyst_gui_slot):
            matches = [slot for slot in inventory_slots(gui, "") if slot.get("slot") == destination]
            catalyst = destination == self.catalyst_gui_slot and retained
            _require(len(matches) == 1 and (matches[0].get("item") is None if not catalyst else
                     _same(matches[0].get("item"), self.recipe.catalyst) and _quantity(matches[0]["item"].get("count")) == 1),
                     "declared_destination_not_empty", "Physical destinations must be empty or contain the one exact retained catalyst before material writes.")
        self.phases.append({"phase": "physical_destinations_preflight", "status": "completed", "beforeMaterialWrites": True,
            "inputGuiSlot": self.input_gui_slot, "catalystGuiSlot": self.catalyst_gui_slot,
            "inputBinding": self.input_gui_binding, "catalystBinding": self.catalyst_gui_binding,
            "screenId": gui["screenId"], "windowId": gui["windowId"]})

    def validate_auto_catalyst_binding(self, gui: dict[str, Any]) -> None:
        _require(self.catalyst_gui_binding is not None, "catalyst_gui_binding_unavailable", "The automatic catalyst mapping must first be resolved.")
        matches = [slot for slot in self.current_machine_gui_bindings(gui)
            if slot["machineInventoryIndex"] == self.catalyst_gui_binding["inventoryIndex"]]
        _require(len(matches) == 1 and matches[0]["slot"] == self.catalyst_gui_slot
                 and matches[0].get("machineInventoryRole") == "consumed_input" and matches[0].get("consumedInput") is True,
                 "catalyst_gui_binding_changed", "The same actual public catalyst owner, input index and unique native destination must remain current.")

    def validate_declared_single_slots(self, gui: dict[str, Any]) -> None:
        declarations = [self.input_gui_slot, *([self.catalyst_gui_slot]
            if self.recipe and self.recipe.catalyst and not self.configured_ghost else [])]
        current = []
        for declared in declarations:
            matches = [slot for slot in inventory_slots(gui, "") if slot.get("slot") == declared]
            index_key = "machineInventoryIndex" if self.input_gui_binding is not None and declared == self.input_gui_slot \
                or self.catalyst_gui_binding is not None and declared == self.catalyst_gui_slot else "inventoryIndex"
            _require(len(matches) == 1 and type(matches[0].get(index_key)) is int
                     and matches[0][index_key] in self.machine_inventory_indices,
                     "single_machine_gui_declaration_unavailable", "Each caller-observed declared GUI slot needs an actual slot/inventory index within the complete machine inventory.")
            current.append({"guiSlot": declared, "inventoryIndex": matches[0][index_key]})
            _require(not self.configured_ghost or self.ghost_circuit is not None
                     and matches[0][index_key] != self.ghost_circuit["inventoryIndex"],
                     "configured_ghost_destination_overlap", "Material GUI destinations must never address the actual virtual circuit backing entry.")
        _require(not self.declared_gui_slots or current == self.declared_gui_slots,
                 "single_machine_gui_declaration_changed", "The caller-declared actual GUI slot identities changed before a native pickup.")
        self.declared_gui_slots = current

    def validate_ghost_slot_api(self, machine: dict[str, Any]) -> None:
        runtime = machine.get("singleBlockRecipeMachine")
        circuit = runtime.get("configurationCircuit") if isinstance(runtime, dict) else None
        _require(self.single_api_proof is not None and isinstance(circuit, dict)
                 and circuit.get("apiClass") == CIRCUIT_SUPPORT_API and circuit.get("source") == "public_runtime_api"
                 and circuit.get("available") is True and circuit.get("allowSelectCircuit") is True
                 and type(circuit.get("inventoryIndex")) is int and circuit["inventoryIndex"] >= 0
                 and isinstance(circuit.get("owner"), dict)
                 and all(type(circuit["owner"].get(key)) is type(value) and circuit["owner"][key] == value
                     for key, value in self.single_api_proof["owner"].items()),
                 "configured_ghost_backing_api_unavailable",
                 "Switching a retained configuration requires the same owner's actual public allowSelectCircuit/getCircuitSlot proof.")
        current = {"apiClass": CIRCUIT_SUPPORT_API, "source": "public_runtime_api",
            "inventoryIndex": circuit["inventoryIndex"], "owner": dict(self.single_api_proof["owner"])}
        _require(current["inventoryIndex"] in self.machine_inventory_indices
                 and (self.ghost_slot_proof is None or current == self.ghost_slot_proof),
                 "configured_ghost_backing_api_changed", "The actual public circuit backing index and owner must remain unchanged.")
        self.ghost_slot_proof = current

    def validate_ghost_switch_inventory(self, server: dict[str, Any], configuration: int) -> dict[str, Any]:
        self.bind_input_inventory_owner(server)
        _require(self.ghost_slot_proof is not None and self.input_inventory_owner is not None
                 and all(type(self.input_inventory_owner.get(key)) is type(value) and self.input_inventory_owner[key] == value
                     for key, value in self.ghost_slot_proof["owner"].items()),
                 "configured_ghost_backing_owner_changed", "The complete server inventory must belong to the public circuit backing owner.")
        slots = inventory_slots(server)
        self.validate_single_tanks(server)
        circuits = [slot for slot in slots if isinstance(slot["item"], dict)
            and slot["item"].get("registryName") == INTEGRATED_CIRCUIT]
        identity = {"registryName": INTEGRATED_CIRCUIT, "metadata": configuration}
        _require(type(configuration) is int and 1 <= configuration <= 24 and len(circuits) == 1
                 and circuits[0]["index"] == self.ghost_slot_proof["inventoryIndex"]
                 and _same(circuits[0]["item"], identity) and type(circuits[0]["item"].get("count")) is int
                 and circuits[0]["item"]["count"] == 0,
                 "configured_ghost_previous_inventory_unavailable",
                 "The old circuit must be one untagged zero-count configuration at the actual public circuit backing index.")
        _require(self.recipe is not None and all(slot["item"] is None or slot is circuits[0]
                     or _same(slot["item"], self.recipe.output) for slot in slots),
                 "input_not_isolated", "Only the proven old ghost and known output baseline may precede a configuration switch.")
        return {"inventoryIndex": circuits[0]["index"], "configuration": configuration, "item": identity, "count": 0}

    def validate_ghost_switch_gui(self, gui: dict[str, Any]) -> None:
        bindings = self.current_machine_gui_bindings(gui)
        _require(self.ghost_slot_proof is not None and bool(bindings)
                 and all(slot["machineInventoryIndex"] in self.machine_inventory_indices
                     and slot["machineInventoryIndex"] != self.ghost_slot_proof["inventoryIndex"] for slot in bindings)
                 and any(slot.get("machineInventoryRole") == "consumed_input" and slot.get("consumedInput") is True
                     and (self.input_gui_slot is None or slot["slot"] == self.input_gui_slot) for slot in bindings),
                 "configured_ghost_gui_owner_unavailable",
                 "The actual opened GUI needs public consumed-input bindings to the same server owner, separate from its ghost backing entry.")

    async def preflight_ghost_switch(self, machine: dict[str, Any], server: dict[str, Any]) -> None:
        self.phase = "configured_ghost_previous_preflight"
        self.validate_ghost_slot_api(machine)
        circuits = [slot for slot in inventory_slots(server) if isinstance(slot["item"], dict)
            and slot["item"].get("registryName") == INTEGRATED_CIRCUIT]
        _require(len(circuits) == 1, "configured_ghost_previous_inventory_unavailable",
                 "A configuration switch requires one actual retained circuit, never multiple circuit entries.")
        configuration = circuits[0]["item"].get("metadata")
        previous = self.validate_ghost_switch_inventory(server, configuration)
        gui = await self.open_gui("input")
        self.validate_ghost_switch_gui(gui)
        observation = await self.control_widgets(gui["screenId"], reason="configured_ghost_widget_coverage_unavailable")
        widget = configured_ghost_widget(observation, configuration)
        actions = widget.get("supportedActions")
        _require(self.recipe is not None and self.recipe.catalyst is not None
                 and 1 <= self.recipe.catalyst["metadata"] <= 24 and isinstance(actions, list)
                 and ("set_value" in actions or "activate" in actions and configuration < self.recipe.catalyst["metadata"] <= 24),
                 "configured_ghost_action_unavailable", "The native widget must support exact selection or a verified forward configuration cycle.")
        for read in range(2):
            if read:
                with anyio.fail_after(self.remaining() / 1000):
                    await anyio.sleep(.05)
            await self.inspect_registered_machine()
            current = self.validate_ghost_switch_inventory(await self.call("world.inspect", self.position("input")), configuration)
            _require(current == previous, "configured_ghost_previous_inventory_changed",
                     "The old public configuration and backing index must agree across stable server reads before preparation.")
        self.ghost_previous_circuit = previous
        self.phases.append({"phase": "configured_ghost_previous_preflight", "status": "completed", **previous,
            "owner": dict(self.ghost_slot_proof["owner"]), "backingApi": CIRCUIT_SUPPORT_API,
            "widgetId": widget["widgetId"], "configurationSource": GHOST_CONFIG_API,
            "stableServerReads": 2, "serverReadInterval_ms": 50, "physicalCircuitTransferred": False})

    def validate_configured_ghost_inventory(self, slots: list[dict[str, Any]]) -> None:
        _require(self.single_api_verified and self.recipe is not None and self.recipe.catalyst is not None,
                 "configured_ghost_machine_required", "Configured ghost mode requires the verified common single-block API and a raw circuit catalyst.")
        circuits = [slot for slot in slots if _same(slot.get("item"), self.recipe.catalyst)]
        _require(len(circuits) == 1 and type(circuits[0]["item"].get("count")) is int and circuits[0]["item"]["count"] == 0,
                 "configured_ghost_inventory_unavailable", "Exactly one same-identity zero-count virtual circuit must actually exist in the complete shared machine inventory.")
        if self.ghost_slot_proof is not None:
            _require(circuits[0]["index"] == self.ghost_slot_proof["inventoryIndex"]
                     and len([slot for slot in slots if isinstance(slot["item"], dict)
                         and slot["item"].get("registryName") == INTEGRATED_CIRCUIT]) == 1,
                     "configured_ghost_inventory_changed", "The switched configuration must retain the same public backing index with no other circuit entry.")
        evidence = {"inventoryIndex": circuits[0]["index"], "configuration": self.recipe.catalyst["metadata"],
            "item": self.recipe.catalyst, "count": 0, "physicalCircuitTransferred": False,
            "configurationMutated": self.ghost_configuration_mutated}
        _require(self.ghost_circuit is None or evidence == self.ghost_circuit, "configured_ghost_inventory_changed",
                 "The retained virtual circuit identity, configuration and backing inventory index must remain unchanged.")
        self.ghost_circuit = evidence

    def verify_configured_ghost_widget(self, observation: dict[str, Any], phase: str) -> None:
        _require(self.ghost_circuit is not None, "configured_ghost_inventory_unavailable", "The actual virtual circuit inventory proof is required first.")
        widget = configured_ghost_widget(observation, self.ghost_circuit["configuration"])
        self.phases.append({"phase": phase, "status": "completed", "configuration": self.ghost_circuit["configuration"],
            "widgetId": widget["widgetId"], "source": GHOST_CONFIG_API,
            "configurationMutated": self.ghost_configuration_mutated,
            "physicalCircuitTransferred": False})

    async def inspect_configured_ghost(self, gui: dict[str, Any], phase: str) -> None:
        self.phase = phase
        observation = await self.control_widgets(gui["screenId"], reason="configured_ghost_widget_coverage_unavailable")
        if self.ghost_circuit is None:
            _require(self.recipe is not None and self.recipe.catalyst is not None
                     and 1 <= self.recipe.catalyst["metadata"] <= 24,
                     "configured_ghost_configuration_unsupported", "Native ghost configuration supports only the public cycle's exact circuit values 1..24.")
            current = self.ghost_previous_circuit["configuration"] if self.ghost_previous_circuit is not None else -1
            widget = configured_ghost_widget(observation, current)
            _require(isinstance(widget.get("supportedActions"), list),
                     "configured_ghost_action_unavailable", "The actual public ghost widget must advertise its native configuration actions.")
            native_selection = "set_value" in widget["supportedActions"]
            _require(native_selection or "activate" in widget["supportedActions"] and current < self.recipe.catalyst["metadata"],
                     "configured_ghost_action_unavailable", "A retained configuration needs native exact selection or a verified forward cycle.")
            if self.ghost_previous_circuit is not None:
                self.validate_ghost_switch_gui(gui)
                previous = self.validate_ghost_switch_inventory(await self.call("world.inspect", self.position("input")), current)
                _require(previous == self.ghost_previous_circuit, "configured_ghost_previous_inventory_changed",
                         "The old configuration must remain at its same proven public backing index before the native switch.")
            targets = [self.recipe.catalyst["metadata"]] if native_selection else range(max(1, current + 1), self.recipe.catalyst["metadata"] + 1)
            for expected in targets:
                await self.inspect_registered_machine()
                observation = await self.control_widgets(gui["screenId"], reason="configured_ghost_widget_coverage_unavailable")
                fresh_widget = configured_ghost_widget(observation, current)
                if self.ghost_previous_circuit is not None:
                    current_gui = await self.call("gui.inspect", {})
                    _require(current_gui.get("screenId") == gui["screenId"] and current_gui.get("windowId") == gui["windowId"],
                             "configured_ghost_widget_changed", "The configuration switch must retain the same current native screen and window.")
                    self.validate_ghost_switch_gui(current_gui)
                    self.validate_ghost_switch_inventory(await self.call("world.inspect", self.position("input")), current)
                _require(fresh_widget["widgetId"] == widget["widgetId"], "configured_ghost_widget_changed",
                         "Every native configuration click must target the same actual current ghost widget.")
                _require(isinstance(fresh_widget.get("supportedActions"), list)
                         and ("set_value" if native_selection else "activate") in fresh_widget["supportedActions"],
                         "configured_ghost_action_unavailable", "The exact current widget must still advertise the selected native configuration action.")
                steps = [_read("before", "mcb_inventory", fields=["cursorItem"]),
                    _assert("cursor_empty", "before", "cursorItem", None),
                    _read("current_machine", "mcb_machine_inspect", self.position("machine")),
                    _assert("machine_identity", "current_machine", "metaTileId", self.machine_id),
                    _assert("machine_class", "current_machine", "metaClass", self.machine_class),
                    _assert("machine_disabled", "current_machine", "state.allowedToWork", False),
                    _assert("machine_inactive", "current_machine", "state.active", False),
                    _assert("machine_zero_progress", "current_machine", "state.progressTicks", 0),
                    _assert("machine_zero_max_progress", "current_machine", "state.maxProgressTicks", 0),
                    _action("configure", "mcb_gui_widget_action", {"screenId": gui["screenId"],
                        "widgetId": widget["widgetId"], "action": "set_value" if native_selection else "activate",
                        **({"value": expected} if native_selection else {})})]
                steps[3:3] = [_assert("machine_" + axis, "current_machine", axis, value)
                    for axis, value in self.position("machine").items()]
                if self.ghost_slot_proof is not None:
                    circuit_path = "singleBlockRecipeMachine.configurationCircuit."
                    steps[-1:-1] = [_assert("circuit_" + key, "current_machine", circuit_path + key, value)
                        for key, value in {"apiClass": CIRCUIT_SUPPORT_API, "source": "public_runtime_api",
                            "available": True, "allowSelectCircuit": True,
                            "inventoryIndex": self.ghost_slot_proof["inventoryIndex"]}.items()] + [
                        _assert("circuit_owner_" + key, "current_machine", circuit_path + "owner." + key, value)
                        for key, value in self.ghost_slot_proof["owner"].items()]
                await self.workflow("configured_ghost_native_configuration_" + str(expected), steps)
                self.ghost_configuration_mutated = True
                for read in range(40):
                    if read:
                        with anyio.fail_after(self.remaining() / 1000):
                            await anyio.sleep(.05)
                    observation = await self.control_widgets(gui["screenId"], reason="configured_ghost_widget_coverage_unavailable")
                    widgets = [row for row in observation.get("widgets", []) if isinstance(row, dict)
                        and row.get("semantic") == "ghost_circuit"]
                    observed = widgets[0].get("circuitConfig", {}).get("value") if len(widgets) == 1 else None
                    _require(type(observed) is int and observed in (current, expected),
                             "configured_ghost_configuration_mismatch", "The native ghost value changed outside the one submitted native configuration action.")
                    value = configured_ghost_widget(observation, observed)
                    if value["circuitConfig"]["value"] == expected:
                        break
                else:
                    raise _Unavailable("configured_ghost_native_sync_unconfirmed", "The normal native circuit click was not confirmed; no click was replayed.")
                current = expected
            server = await self.call("world.inspect", self.position("machine"))
            if self.ghost_slot_proof is not None:
                await self.inspect_registered_machine()
                self.bind_input_inventory_owner(server)
            _require(server.get("gregtech", {}).get("metaTileId") == self.machine_id
                     and server.get("gregtech", {}).get("metaClass") == self.machine_class,
                     "shared_inventory_machine_identity_changed", "The newly synchronized server ghost belongs to the same BasicMachine.")
            self.validate_configured_ghost_inventory(inventory_slots(server))
        _require(self.ghost_circuit is not None, "configured_ghost_inventory_unavailable", "The actual virtual circuit inventory proof is required first.")
        configured_ghost_widget(observation, self.ghost_circuit["configuration"])
        for read in range(2):
            if read:
                with anyio.fail_after(self.remaining() / 1000):
                    await anyio.sleep(.05)
            server = await self.call("world.inspect", self.position("machine"))
            if self.ghost_slot_proof is not None:
                self.bind_input_inventory_owner(server)
            _require(server.get("gregtech", {}).get("metaTileId") == self.machine_id
                     and server.get("gregtech", {}).get("metaClass") == self.machine_class,
                     "shared_inventory_machine_identity_changed", "The server ghost inventory must belong to the same actual public BasicMachine.")
            self.validate_configured_ghost_inventory(inventory_slots(server))
        self.verify_configured_ghost_widget(observation, phase)
        self.phases[-1].update(serverConfigurationConfirmed=True, stableServerReads=2, serverReadInterval_ms=50)

    def validate_single_tanks(self, observation: dict[str, Any]) -> None:
        fluids = observation.get("fluids")
        _require(isinstance(fluids, dict) and fluids.get("truncated") is False and type(fluids.get("totalTanks")) is int
                 and 0 <= fluids["totalTanks"] <= 32 and isinstance(fluids.get("tanks"), list)
                 and len(fluids["tanks"]) == fluids["totalTanks"],
                 "single_machine_fluid_isolation_unavailable", "This item-only task needs complete actual public tank coverage in the shared machine.")
        tanks = fluids["tanks"]
        _require(all(isinstance(tank, dict) and type(tank.get("index")) is int for tank in tanks)
                 and {tank["index"] for tank in tanks} == set(range(fluids["totalTanks"]))
                 and all("fluid" not in tank and "amount" not in tank or type(tank.get("amount")) is int and tank["amount"] == 0 for tank in tanks),
                 "single_machine_fluid_isolation_unavailable", "Every actual public tank must be explicitly empty; no existing fluid is moved or cleared.")

    async def inspect_registered_machine(self, *, allow_idle_enabled: bool = False) -> dict[str, Any]:
        started = time.monotonic()
        summary: dict[str, Any] = {"phase": "single_machine_inspection" if self.single_inventory else "component_collection",
            "status": "running", "timingScope": "single_machine_read_total" if self.single_inventory else "registered_component_read_total"}
        self.phases.append(summary)
        offset, pauses, collected = 0, 0, []
        first: dict[str, Any] | None = None
        fingerprint: tuple[Any, ...] | None = None
        try:
            for page in range(1, 9):
                data = await self.call("machine.inspect", {**self.position("machine"), "componentOffset": offset, "componentLimit": 128})
                _require(all(type(data.get(axis)) is int and data[axis] == self.args["machine"][axis] for axis in ("x", "y", "z"))
                         and type(data.get("dimension")) is int and data["dimension"] == self.dimension
                         and type(data.get("metaTileId")) is int and isinstance(data.get("metaClass"), str)
                         and (self.machine_id is None or data["metaTileId"] == self.machine_id)
                         and (self.machine_class is None or data["metaClass"] == self.machine_class),
                         "component_controller_identity_changed", "Every registered component page must identify the same current declared controller/dimension.")
                if self.single_inventory:
                    self.validate_single_machine_api(data)
                    self.single_api_verified = True
                    state = data.get("state", {})
                    voltage = state.get("inputVoltage")
                    _require(type(voltage) is int and 0 < voltage <= 2**63 - 1
                             and (self.single_input_voltage is None or voltage == self.single_input_voltage),
                             "single_machine_input_voltage_unavailable", "The actual common BasicMachine requires stable typed public input-voltage evidence.")
                    self.single_input_voltage = voltage
                    _require(state.get("active") is False and type(state.get("allowedToWork")) is bool
                             and (allow_idle_enabled or state["allowedToWork"] is False)
                             and type(state.get("progressTicks")) is int and state["progressTicks"] == 0
                             and type(state.get("maxProgressTicks")) is int and state["maxProgressTicks"] == 0,
                             "stopped_single_machine_required", "The actual BasicMachine must be inactive, disabled and have explicitly zero current/maximum progress.")
                    summary.update(status="completed", pages=page, singleBlockAPI=data["metaClass"],
                        structureFormationClaimed=False, registeredCoreStopClaimed=False)
                    return data
                _require(data.get("state", {}).get("active") is False and data.get("state", {}).get("allowedToWork") is False
                         and data.get("processing", {}).get("structureFormed") is True,
                         "formed_stopped_machine_required", "Every collected page requires the real controller to remain formed, inactive and disabled.")
                components = data.get("components")
                _require(isinstance(components, dict) and isinstance(components.get("entries"), list)
                         and type(components.get("total")) is int and 0 <= components["total"] <= 128
                         and components.get("offset") == offset and type(components.get("nextOffset")) is int
                         and type(components.get("hasMore")) is bool and isinstance(components.get("roles"), list)
                         and isinstance(components.get("unavailableRoles"), list) and isinstance(components.get("modularHatches"), dict),
                         "registered_component_coverage_incomplete", "Component pages need explicit bounded counts, offsets and public role/module coverage.")
                current_fingerprint = (data["metaTileId"], data["metaClass"], data["dimension"], components["total"],
                    components["roles"], components["unavailableRoles"], components["modularHatches"])
                if first is None:
                    first, fingerprint = components, current_fingerprint
                else:
                    _require(current_fingerprint == fingerprint, "component_pagination_changed",
                             "Controller identity, registered counts and public role availability changed during collection.")
                entries, following = components["entries"], components["nextOffset"]
                _require(following == offset + len(entries) and following <= components["total"]
                         and all(isinstance(entry, dict) and type(entry.get("index")) is int and entry["index"] == offset + index
                                 for index, entry in enumerate(entries)),
                         "component_pagination_changed", "Collected registered entries must cover each actual flattened index exactly once.")
                collected.extend(entries)
                summary.update(pages=page, collectedEntries=len(collected), totalRegistered=components["total"], coldStartWaits=pauses)
                if components["hasMore"] is False:
                    _require(following == components["total"] and len(collected) == components["total"],
                             "registered_component_coverage_incomplete", "Only completely collected registered entries can satisfy isolation.")
                    data["components"] = {**first, "entries": collected, "offset": 0, "nextOffset": following,
                        "hasMore": False, "collectionPages": page, "collectionNonAtomic": page > 1}
                    summary["status"] = "completed"
                    return data
                _require(following < components["total"], "component_pagination_changed", "A continued registered page must have entries remaining.")
                if following == offset:
                    _require(pauses < 2, "component_cold_start_budget", "Zero-progress component reads are limited to two normal tick waits.")
                    pauses += 1
                    summary["coldStartWaits"] = pauses
                    with anyio.fail_after(self.remaining() / 1000):
                        await anyio.sleep(.05)
                else:
                    offset = following
            raise _Unavailable("component_collection_page_budget", "Registered component collection exceeded eight reads and the 128-entry limit.")
        finally:
            summary["elapsed_ms"] = int((time.monotonic() - started) * 1000)
            if summary["status"] == "running":
                summary["status"] = "failed"

    async def preflight_disable_idle_single_machine(self) -> dict[str, Any]:
        gui = await self.open_gui("machine")
        self.phase = "preflight_controls"
        widget = power_widget(await self.control_widgets(gui["screenId"], semantic="power_switch",
            reason="power_widget_coverage_incomplete"), True, allow_public_semantic=True)
        steps = [_read("current_machine", "mcb_machine_inspect", self.position("machine")),
            _assert("machine_identity", "current_machine", "metaTileId", self.machine_id),
            _assert("machine_class", "current_machine", "metaClass", self.machine_class),
            _assert("machine_inactive", "current_machine", "state.active", False),
            _assert("machine_enabled", "current_machine", "state.allowedToWork", True),
            _assert("machine_zero_progress", "current_machine", "state.progressTicks", 0),
            _assert("machine_zero_max_progress", "current_machine", "state.maxProgressTicks", 0),
            _action("power_off", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"],
                "action": "set_selected", "selected": 0}),
            _action("close", "mcb_close_gui", {})]
        steps[1:1] = [_assert("machine_" + axis, "current_machine", axis, expected)
            for axis, expected in self.position("machine").items()]
        await self.workflow("preflight_power_off", steps)
        self.opened_gui = None
        for read in range(40):
            if read:
                with anyio.fail_after(self.remaining() / 1000):
                    await anyio.sleep(.05)
            machine = await self.inspect_registered_machine(allow_idle_enabled=True)
            if machine["state"]["allowedToWork"] is False:
                self.preflight_power_verified = True
                return machine
        raise _Unavailable("preflight_power_off_unconfirmed", "The native power-off was not confirmed by fresh inactive, disabled and zero-progress machine evidence; no action was replayed.")

    async def place_declared_single_block(self) -> None:
        """Attempt the declared native placement once, retaining its receipt on every failure."""
        from .construction_tasks import _survival_deductions
        record = self.single_block_placement
        _require(record is not None and not record["placementAttempted"], "single_placement_already_attempted",
            "A task's native placement is never repeated, including after an unknown result.")
        started = time.monotonic()
        self.phase = "single_block_placement_preflight"
        try:
            source = await self.call("inventory.inspect", {})
            slots = inventory_slots(source, "")
            slot, item = record["hotbarSlot"], record["item"]
            actual = [row["item"] for row in slots if row["index"] == slot]
            _require(source.get("cursorItem") is None and "cursorItem" in source
                and len(slots) == 41 and {row["index"] for row in slots} == set(range(41))
                and len(actual) == 1 and isinstance(actual[0], dict)
                and all(type(actual[0].get(key)) is type(value) and actual[0][key] == value for key, value in item.items())
                and type(actual[0].get("count")) is int and actual[0]["count"] > 0
                and type(actual[0].get("hasTag")) is bool,
                "single_placement_source_unavailable", "The exact declared hotbar item needs complete real inventory, positive count and an explicitly empty cursor.")
            source_item = actual[0]
            record["sourceCountBefore"] = source_item["count"]
            air = await self.call("world.inspect", self.position("machine"))
            _require(air.get("source") == "integrated_server" and air.get("registryName") == "minecraft:air"
                and "tileClass" not in air and all(type(air.get(axis)) is int and air[axis] == value
                    for axis, value in self.position("machine").items()),
                "single_placement_air_required", "The exact loaded server destination must be literal air without a tile before native preparation.")
            # The ordinary workflow establishes background protection and the
            # existing survival close barrier before the guarded planner read.
            await self.workflow("single_block_placement_prepare", [_action("close", "mcb_close_gui", {})])
            _, steps, _ = await self.approach_plan("machine", intent="place_block", hotbar_slot=slot,
                allowed_sides=(0, 1, 2, 3, 4, 5))
            action = _action("place", "mcb_place_block", {**self.position("machine"), "hotbarSlot": slot,
                "expectedWorld": self.args["expectedWorld"], "replace": False})
            action["fields"] = ["nativeInvoked", "actualSurvival", "serverConfirmed", "serverConsumptionConfirmed",
                "serverSourceConsumption", "serverBefore", "serverAfter", "serverTarget", "outcomeUnknown", "attachment"]
            action["omitEmptySlots"] = False
            steps.extend([_read("placement_inventory", "mcb_inventory", fields=["slots", "cursorItem", "totalSlots", "truncated"]),
                _assert("placement_cursor_empty", "placement_inventory", "cursorItem", None),
                _assert("placement_source_unchanged", "placement_inventory", "slots", json_sha256(source["slots"]), "json_sha256_equals"),
                _read("placement_target", "mcb_world_inspect", self.position("machine"),
                    ["registryName", "metadata", "tileClass", "source", "dimension", "x", "y", "z"]),
                _assert("placement_target_air", "placement_target", "registryName", "minecraft:air"),
                _assert("placement_target_no_tile", "placement_target", "tileClass", False, "exists"), action])
            record["placementAttempted"] = True
            self.queue_recipe_context_guard()
            report = await self.workflow("single_block_placement", steps)
            native = {key: self.value(report, "place", key) for key in action["fields"]}
            record["nativeReceipt"] = native
            _require(native["nativeInvoked"] is True and native["actualSurvival"] is True
                and native["serverConfirmed"] is True and native["serverConsumptionConfirmed"] is True
                and type(native["serverSourceConsumption"]) is int and native["serverSourceConsumption"] == 1
                and native["outcomeUnknown"] is False,
                "single_placement_server_unconfirmed", "Native placement must confirm its actual server target and exactly one real source deduction.")
            deductions = _survival_deductions(native["serverBefore"], native["serverAfter"])
            before = next(row["item"] for row in native["serverBefore"]["slots"] if row["index"] == slot)
            target = native["serverTarget"]
            gt = target.get("gregtech") if isinstance(target, dict) else None
            _require(isinstance(target, dict) and target.get("source") == "integrated_server"
                and type(target.get("dimension")) is int and target["dimension"] == self.dimension
                and all(type(target.get(axis)) is int and target[axis] == self.args["machine"][axis] for axis in ("x", "y", "z"))
                and target.get("registryName") == item["registryName"] and isinstance(gt, dict)
                and type(gt.get("metaTileId")) is int and gt["metaTileId"] == item["metadata"]
                and isinstance(gt.get("metaClass"), str) and bool(gt["metaClass"]),
                "single_placement_identity_unconfirmed", "The real server GT meta-tile identity must match the declared source metadata, separately from world block metadata.")
            _require(len(deductions) == 1 and deductions[0]["playerInventoryIndex"] == slot and deductions[0]["count"] == 1
                and isinstance(before, dict) and all(before.get(key) == source_item.get(key)
                    for key in ("registryName", "metadata", "count", "hasTag")),
                "single_placement_material_delta_unconfirmed", "Exactly one matching source item must be consumed, preserving all unrelated authoritative slots and full native NBT identities.")
            record.update(placementConfirmed=True, status="placed", serverTarget=target, deductions=deductions,
                sourceCountAfter=deductions[0]["sourceCountAfter"],
                serverInventoryBeforeSha256=json_sha256(native["serverBefore"]),
                serverInventoryAfterSha256=json_sha256(native["serverAfter"]))
            # Recipe preparation establishes its baseline later. Wait until the
            # client sees the authoritative deduction before that baseline read.
            self.phase = "single_block_placement_inventory_sync"
            expected = {"index": slot, **{f"item.{key}": source_item[key] for key in ("registryName", "metadata", "hasTag")},
                "item.count": record["sourceCountAfter"]} if record["sourceCountAfter"] else {"index": slot, "item": None}
            await self.workflow(self.phase, [_wait("source_synced", "mcb_inventory", {}, "slots", expected,
                operator="matches_one", fields=["slots", "cursorItem", "totalSlots", "truncated"]),
                _assert("placement_synced_cursor_empty", "source_synced", "cursorItem", None)])
            record.update(clientSourceSynchronized=True,
                recipeInventoryBaseline="Established after the confirmed placement deduction and client source synchronization.")
            if self.recipe_context_inventory is not None:
                placed_player = await self.call("inventory.inspect", {})
                await self.account_recipe_context_item(slot, item, -1, placed_player, "single_block_placement")
        except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError):
            record["status"] = "failed" if record["placementConfirmed"] else "unconfirmed" if record["placementDispatched"] else "refused"
            if record["placementDispatched"] and not record["placementConfirmed"] \
                    and (record.get("nativeReceipt") or {}).get("nativeInvoked") is True:
                record["outcomeUnknown"] = True
            raise
        finally:
            record["placementElapsed_ms"] = int((time.monotonic() - started) * 1000)

    async def await_initial_single_charge(self, machine: dict[str, Any]) -> dict[str, Any]:
        record = self.single_block_placement
        if record is None:
            return machine
        self.phase = "single_block_initial_charge"
        started = time.monotonic()
        timeout = record.get("powerReadyTimeout_ms", 5000)
        deadline = min(self.deadline, started + timeout / 1000)
        charge = {"status": "waiting", "readOnly": True, "timeout_ms": timeout, "observations": 0,
            "scope": "Positive observed storedEU at this exact stopped owner is initial readiness only; complete recipe power and later energy availability are not guaranteed."}
        record["initialCharge"] = charge
        try:
            while True:
                stored = machine.get("state", {}).get("storedEU")
                _require(type(stored) is int and 0 <= stored <= LONG_MAX,
                    "single_placement_charge_unavailable", "Initial charging needs actual typed public storedEU; missing values are not treated as zero.")
                charge.update(observations=charge["observations"] + 1, storedEU=stored)
                if stored > 0:
                    charge.update(status="observed_positive", ready=True)
                    record["status"] = "ready"
                    return machine
                remaining = deadline - time.monotonic()
                _require(remaining > 0, "single_placement_charge_timeout", "Initial storedEU stayed zero within the declared read budget; no placement, feed or activation was replayed.")
                with anyio.fail_after(remaining):
                    await anyio.sleep(min(.05, remaining))
                    machine = await self.inspect_registered_machine()
        except TimeoutError:
            charge.update(status="timeout", ready=False)
            raise _Unavailable("single_placement_charge_timeout", "The initial charge read budget expired; no action was replayed.") from None
        except (BridgeError, AttributeError, KeyError, TypeError, ValueError):
            charge.update(status="unavailable", ready=False)
            raise
        finally:
            charge["elapsed_ms"] = int((time.monotonic() - started) * 1000)

    async def preflight(self) -> dict[str, Any]:
        self.validate_survival_posture_declaration()
        _require(self.single_block_placement is None or self.single_inventory and self.actual_survival
            and self.args.get("recipeMode") == "public_multi" and self.args.get("inventoryPreparation") == "preserve"
            and self.args.get("generateMaterials", False) is False and self.args.get("preflightControls") is True,
            "single_placement_scope_unsupported", "Explicit single-block placement requires identical targets, actual survival, public_multi, preserve, no generation and preflightControls=true.")
        _require(not self.actual_survival or self.args.get("generateMaterials", False) is False,
            "survival_material_generation_forbidden", "Actual survival uses existing finite materials and cannot generate or clear inventory.")
        _require(not self.actual_survival or self.args.get("recipeMode") == "public_multi",
            "survival_complete_recovery_required", "Actual survival requires public_multi so every item/fluid product and newly supplied catalyst is completely recovered and conserved.")
        health = await self.call("bridge.health", {})
        _require(health.get("protocolVersion") == 1 and health.get("sessionId") == self.args["expectedSessionId"]
                 and health.get("profile") == "developer", "local_developer_context_required", "A matching local developer bridge is required.")
        capabilities = health.get("capabilities")
        self.capabilities = capabilities
        self.approach_health = health
        _require(not self.survival_flight or type(health.get("survivalFlightPostureVersion")) is int
            and health["survivalFlightPostureVersion"] == 1, "survival_flight_native_unavailable",
            "Explicit airborne survival requires the advertised native dual-actor posture guard.")
        _require(isinstance(capabilities, list) and all(isinstance(value, str) for value in capabilities)
                 or isinstance(capabilities, dict) and all(type(value) is bool for value in capabilities.values()),
                 "capability_discovery_unavailable", "The bridge must explicitly advertise typed operation capabilities.")
        required_operations = ["client.snapshot", "client.background", "inventory.inspect", "recipes.query", "world.inspect",
            "world.spatial", "machine.inspect", "gui.inspect", "gui.widgets", "gui.widget_action", "gui.slot_click", "gui.close",
            "player.hotbar", "player.interact", "dev.teleport", "machine.set_working"]
        if self.single_block_placement is not None:
            required_operations.extend(["player.place_block", "player.plan_approach"])
        if self.args.get("generateMaterials", False):
            required_operations.append("chat.send")
        if self.args.get("fluidContainerInputs") or self.args.get("fluidContainerOutputs"):
            required_operations.append("gui.tooltip")
        for operation in required_operations:
            _require(operation in capabilities if isinstance(capabilities, list) else capabilities.get(operation) is True,
                     "required_capability_unavailable", "The task is unavailable before any write: missing " + operation + ".")
        _require(all(health.get(flag) is True for flag in ("interactionSneaking", "interactionBlockTargeting", "interactionViewBinding")),
                 "native_interaction_capability_unavailable", "Scoped sneak, block-only targeting and native view binding are required before any write.")
        if self._native_workflow_runner:
            from .workflows import _workflow_discovery
            self._workflow_discovery = _workflow_discovery(self.tracked_client, self.credentials, health)
        snapshot = await self.snapshot()
        dimension = snapshot.get("world", {}).get("dimension")
        if self.dimension is None:
            self.dimension = dimension
        _require(type(self.dimension) is int and self.dimension == dimension, "dimension_mismatch", "All declared targets must be in the current player dimension.")
        self.require_flight(snapshot)
        self.require_survival_server_control(snapshot)
        if not self.single_inventory:
            for key in ("machine", "input", "output"):
                _require(self.args[key] != self.args[[name for name in ("machine", "input", "output") if name != key][0]]
                         and self.args[key] != self.args[[name for name in ("machine", "input", "output") if name != key][1]],
                         "target_coordinates_overlap", "Declare three distinct multiblock targets or one identical single-block inventory target.")
        await self.initialize_recipe_context()
        if self.single_block_placement is not None:
            await self.place_declared_single_block()
        machine = await self.inspect_registered_machine(
            allow_idle_enabled=self.single_inventory and self.args.get("preflightControls", False))
        self.machine_id = machine.get("metaTileId")
        self.machine_class = machine.get("metaClass")
        _require(type(self.machine_id) is int and 0 <= self.machine_id <= 32_767, "machine_identity_unavailable", "The actual loaded meta-tile identity is unavailable.")
        if self.single_block_placement is not None:
            placed = self.single_block_placement["serverTarget"]["gregtech"]
            _require(self.single_api_proof is not None and self.machine_id == placed["metaTileId"] and self.machine_class == placed["metaClass"],
                "single_placement_machine_changed", "Only the exact natively placed public BasicMachine may enter recipe preflight.")
            self.single_block_placement["commonBasicMachineAPIVerified"] = True
        if self.single_inventory and machine["state"]["allowedToWork"] is True:
            machine = await self.preflight_disable_idle_single_machine()
        _require(machine.get("state", {}).get("active") is False and machine.get("state", {}).get("allowedToWork") is False
                 and (self.single_inventory or machine.get("processing", {}).get("structureFormed") is True),
                 "formed_stopped_machine_required", "The actual machine must be inactive/disabled; multiblocks also require observed formation.")
        self.validate_registered_ports(machine)
        machine = await self.await_initial_single_charge(machine)
        self.recipe_query_preflight_verified = True
        return machine

    async def failure_resources(self) -> dict[str, Any] | None:
        return None

    def protected_player_indices(self) -> set[int]:
        return set(getattr(self, "recipe_context_items", {}))

    def ordinary_source_slots(self, slots: list[dict[str, Any]], *, gui: bool = False) -> list[dict[str, Any]]:
        protected = self.protected_player_indices()
        if not protected:
            return slots
        if not gui:
            return [slot for slot in slots if slot["index"] not in protected]
        return [slot for slot in slots if not (slot.get("playerInventoryBindingVerified") is True
            and slot.get("machineInventoryBindingVerified") is False
            and slot.get("inventoryBinding") == "public_isHere_unique_actual_player_inventory_slot"
            and slot.get("inventorySource") == "current_player_inventory"
            and type(slot.get("playerInventoryIndex")) is int and slot["playerInventoryIndex"] in protected)]

    async def validate_protected_sources(self, player: dict[str, Any], gui: dict[str, Any] | None = None) -> None:
        pass

    async def execute(self) -> dict[str, Any]:
        _require(not self.provider_cache or not self.single_inventory and not self.configured_ghost
            and self.args.get("recipeLookup", "catalog") == "catalog", "provider_machine_scope_unsupported",
            "Local provider-cache acceptance requires distinct multiblock targets, ordinary physical item input and catalog recipe selection.")
        _require(not self.configured_ghost or self.single_inventory, "configured_ghost_machine_required",
                 "Configured ghost mode supports only identical common single-block machine/input/output coordinates.")
        _require(not self.configured_ghost or "catalystGuiSlot" not in self.args, "configured_ghost_physical_destination_forbidden",
                 "Configured ghost mode has no physical catalyst GUI destination; no circuit slot click or transfer is permitted.")
        machine = await self.preflight()
        self.timing_stage_set("recipeDecision")
        self.recipe = await self.select_recipe()
        self.timing_stage_set("preparation")
        recipe, executions = self.recipe, self.args.get("executions", 1)
        physical_catalyst = recipe.catalyst is not None and not self.configured_ghost
        if self.single_inventory:
            _require(recipe.material != recipe.output and (recipe.catalyst is None or recipe.catalyst != recipe.output),
                     "shared_inventory_recipe_identity_overlap", "A shared inventory requires distinct consumed input, output and catalyst identities for independent real acceptance.")
        before_input = await self.call("world.inspect", self.position("input"))
        before_output = before_input if self.single_inventory else await self.call("world.inspect", self.position("output"))
        input_slots, output_slots = inventory_slots(before_input), inventory_slots(before_output)
        if self.provider_cache:
            self.validate_provider_output_inventory(before_output)
            baseline_output = self.provider_output_count(await self.call("machine.inspect", self.provider_output_args()))
            _require(baseline_output <= LONG_MAX - recipe.output_count * executions,
                "provider_output_quantity_overflow", "The queried local cache plus all declared deterministic outputs would overflow long before any feed.")
        else:
            baseline_output = item_count(output_slots, recipe.output)
        if self.input_gui_slot is None or physical_catalyst and self.catalyst_gui_slot is None:
            self.bind_input_inventory_owner(before_input)
        if self.single_inventory:
            self.machine_inventory_indices = {slot["index"] for slot in input_slots}
            _require(before_input.get("gregtech", {}).get("metaTileId") == self.machine_id
                     and before_input.get("gregtech", {}).get("metaClass") == self.machine_class,
                     "shared_inventory_machine_identity_changed", "The complete shared inventory must identify the same actual public BasicMachine.")
            self.validate_single_tanks(before_input)
        if self.configured_ghost:
            old_circuits = [slot for slot in input_slots if isinstance(slot["item"], dict)
                and slot["item"].get("registryName") == INTEGRATED_CIRCUIT and not _same(slot["item"], recipe.catalyst)]
            if old_circuits:
                await self.preflight_ghost_switch(machine, before_input)
        _require(all(slot["item"] is None or recipe.catalyst is not None and _same(slot["item"], recipe.catalyst)
                     or self.ghost_previous_circuit is not None and slot["index"] == self.ghost_previous_circuit["inventoryIndex"]
                         and _same(slot["item"], self.ghost_previous_circuit["item"])
                     or self.single_inventory and _same(slot["item"], recipe.output) for slot in input_slots),
                 "input_not_isolated", "The actual inventory must contain only the retained catalyst and, for a shared single block, the known output baseline before the task.")
        catalyst_before = item_count(input_slots, recipe.catalyst) if recipe.catalyst else 0
        _require(catalyst_before in (0, 1), "catalyst_quantity_unsupported", "The physical catalyst must be absent or one exact retained item.")
        if self.configured_ghost:
            circuits = [slot for slot in input_slots if isinstance(slot.get("item"), dict)
                and slot["item"].get("registryName") == INTEGRATED_CIRCUIT]
            if circuits and self.ghost_previous_circuit is None:
                self.validate_configured_ghost_inventory(input_slots)
        if physical_catalyst and self.catalyst_gui_slot is None:
            await self.preflight_physical_destinations(input_slots, retained=catalyst_before == 1)
        required = [(recipe.material, recipe.material_count * executions)]
        if physical_catalyst and not catalyst_before:
            required.append((recipe.catalyst, 1))
        if self.inventory_preparation_mode == "clear":
            _require(all(1 <= amount <= 64 for _, amount in required), "material_generation_budget",
                "Validate the bounded finite material supply before clearing inventory.")
            await self.clear_player_inventory()
        missing_kinds = await self.ensure_materials(required, grant=False)
        self.single_feed_grants = await self.prove_single_feed_grants(required, missing_kinds)
        await self.prepare_empty_hand(missing_kinds, reserve=1 if self.single_feed_grants else None)
        if self.args.get("preflightControls", False) and not self.preflight_power_verified:
            control_gui = await self.open_gui("machine")
            self.phase = "preflight_controls"
            power_widget(await self.control_widgets(control_gui["screenId"], semantic="power_switch", reason="power_widget_coverage_incomplete"),
                         False, allow_public_semantic=self.single_api_verified)
        # The real registered stopped controller/ports, raw recipe and input isolation are always preflighted.
        input_gui = await self.open_gui("input")
        if self.configured_ghost:
            await self.inspect_configured_ghost(input_gui, "configured_ghost_preflight")
        if self.input_gui_slot is None:
            self.resolve_input_gui_slot(input_gui, input_slots)
        if physical_catalyst and self.catalyst_gui_slot is None:
            self.resolve_catalyst_gui_slot(input_gui, input_slots, retained=catalyst_before == 1)
        if physical_catalyst:
            if self.input_gui_binding is not None:
                self.validate_auto_input_binding(input_gui)
            if self.catalyst_gui_binding is not None:
                self.validate_auto_catalyst_binding(input_gui)
        if self.single_inventory:
            self.validate_declared_single_slots(input_gui)
        for destination in (self.input_gui_slot, *([self.catalyst_gui_slot] if physical_catalyst else [])):
            slots = [slot for slot in input_gui["slots"] if slot.get("slot") == destination]
            _require(len(slots) == 1 and (slots[0].get("item") is None or physical_catalyst
                     and destination == self.catalyst_gui_slot and catalyst_before == 1 and _same(slots[0].get("item"), recipe.catalyst)),
                     "declared_destination_not_empty", "Each caller-declared destination must be a live empty input slot or the retained circuit slot.")
        # Native chat submission preserves this proven input GUI even if /give fills the last empty hotbar slot.
        await self.ensure_materials(required)
        if physical_catalyst and not catalyst_before:
            await self.transfer(recipe.catalyst, 1, self.catalyst_gui_slot, "catalyst",
                close_after=not (self.single_feed_grants or self.single_api_verified and self.input_inventory_owner is not None))
            catalyst_after = await self.call("world.inspect", self.position("input"))
            _require(item_count(inventory_slots(catalyst_after), recipe.catalyst) == 1, "catalyst_transfer_mismatch", "The real input port did not receive the one declared physical catalyst.")
        self.timing_stage_set("executionAcceptance")
        for execution in range(executions):
            if self.provider_cache:
                self.phase = "provider_baseline_before_feed_" + str(execution + 1)
                _require(await self.read_provider_output() == baseline_output + recipe.output_count * execution,
                    "provider_baseline_changed", "The queried local cache drifted from the initial or last accepted quantity before feed.")
            else:
                await self.read_inventory_output_baseline(baseline_output + recipe.output_count * execution,
                    "output_baseline_before_feed_" + str(execution + 1))
            await self.transfer(recipe.material, recipe.material_count, self.input_gui_slot, "material",
                close_after=not (self.single_api_verified and self.input_inventory_owner is not None))
            fed = await self.call("world.inspect", self.position("input"))
            if self.single_inventory:
                self.validate_single_tanks(fed)
            if self.configured_ghost:
                self.validate_configured_ghost_inventory(inventory_slots(fed))
            _require(item_count(inventory_slots(fed), recipe.material) == recipe.material_count,
                     "input_transfer_mismatch", "The real input port did not receive exactly one execution's consumable amount.")
            self.phase = "power_controls_" + str(execution + 1)
            self.validate_registered_ports(await self.inspect_registered_machine())
            gui = await self.open_gui("machine")
            self.phase = "power_controls_" + str(execution + 1)
            controls = await self.control_widgets(gui["screenId"], semantic="power_switch", reason="power_widget_coverage_incomplete")
            widget = power_widget(controls, False, allow_public_semantic=self.single_api_verified)
            if self.configured_ghost:
                await self.inspect_configured_ghost(gui, "configured_ghost_before_power_" + str(execution + 1))
            delta = recipe.output_count * (execution + 1)
            steps = [_read("current_machine", "mcb_machine_inspect", self.position("machine")),
                _assert("machine_identity", "current_machine", "metaTileId", self.machine_id),
                _assert("machine_class", "current_machine", "metaClass", self.machine_class),
                _assert("machine_formed", "current_machine", "processing.structureFormed", True),
                _assert("machine_inactive", "current_machine", "state.active", False),
                _assert("machine_disabled", "current_machine", "state.allowedToWork", False),
                _read("baseline", "mcb_world_inspect", self.position("output"), ["inventory"]),
                _action("power_on", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"], "action": "set_selected", "selected": 1}),
                _action("power_off", "mcb_gui_widget_action", {"screenId": gui["screenId"], "widgetId": widget["widgetId"], "action": "set_selected", "selected": 0}),
                _action("close", "mcb_close_gui", {}),
                _wait("output_delta", "mcb_world_inspect", self.position("output"), "inventory.slots", recipe.output_count,
                    operator="item_count_delta", fields=["inventory"]),
                _read("input_after", "mcb_world_inspect", self.position("input"), ["inventory"]),
                _wait("inactive", "mcb_machine_inspect", {**self.position("machine"), "componentLimit": 128}, "state.active", False),
                _wait("disabled", "mcb_machine_inspect", self.position("machine"), "state.allowedToWork", False),
                _wait("stopped", "mcb_machine_inspect", {**self.position("machine"), "componentLimit": 128}, "components.entries",
                    {"executionCore.working": True}, operator="matches_none", fields=["metaTileId", "state", "components"])]
            steps[1:1] = [_assert("machine_" + axis, "current_machine", axis, expected)
                for axis, expected in self.position("machine").items()]
            next(step for step in steps if step["id"] == "output_delta")["condition"].update(
                match=_match(recipe.output), baseline={"step": "baseline", "path": "inventory.slots"})
            if self.provider_cache:
                self.phase = "provider_baseline_before_power_" + str(execution + 1)
                _require(await self.read_provider_output() == baseline_output + recipe.output_count * execution,
                    "provider_baseline_changed", "The queried local cache drifted before native power; supplied material is retained without replay.")
                baseline_step = next(step for step in steps if step["id"] == "baseline")
                baseline_step.update(tool="mcb_machine_inspect", args=self.provider_output_args(),
                    fields=[*OWNER_KEYS, "sessionId", "source", "outputStorage"])
                steps.insert(steps.index(baseline_step) + 1,
                    _assert("provider_baseline_unchanged", "baseline", PROVIDER_QUERY_PATH + ".entries.0.cachedAmount",
                        str(baseline_output + recipe.output_count * execution)))
                output_wait = next(step for step in steps if step["id"] == "output_delta")
                output_wait.update(tool="mcb_machine_inspect", args=self.provider_output_args(),
                    fields=[*OWNER_KEYS, "sessionId", "source", "outputStorage"])
                output_wait["condition"].update(path=PROVIDER_QUERY_PATH, operator="provider_count_delta",
                    match=provider_match(self.output_provider_owner, recipe.output, self.args["expectedSessionId"]),
                    baseline={"step": "baseline", "path": PROVIDER_QUERY_PATH})
            else:
                power_baseline = await self.read_inventory_output_baseline(baseline_output + recipe.output_count * execution,
                    "output_baseline_before_power_" + str(execution + 1))
                steps.insert(steps.index(next(step for step in steps if step["id"] == "baseline")) + 1,
                    inventory_baseline_assertion("baseline", power_baseline))
            if self.single_inventory:
                steps = [step for step in steps if step["id"] not in {"machine_formed", "stopped"}]
                output_wait = next(step for step in steps if step["id"] == "output_delta")
                next(step for step in steps if step["id"] == "input_after")["fields"].append("fluids")
                steps.remove(output_wait)
                steps.insert(next(index for index, step in enumerate(steps) if step["id"] == "power_off"), output_wait)
                steps[1:1] = [_assert("machine_progress_idle", "current_machine", "state.progressTicks", 0),
                              _assert("machine_max_progress_idle", "current_machine", "state.maxProgressTicks", 0)]
                steps.extend([_wait("stopped", "mcb_machine_inspect", self.position("machine"), "state.progressTicks", 0,
                                   fields=["metaTileId", "metaClass", "state"]),
                              _assert("machine_progress_finished", "stopped", "state.maxProgressTicks", 0)])
            if self.configured_ghost:
                steps = [step for step in steps if step["id"] != "close"]
                ghost_accepted = False
                try:
                    report = await self.workflow("execute_" + str(execution + 1), steps)
                    self.phase = "configured_ghost_acceptance_" + str(execution + 1)
                    observation = await self.control_widgets(gui["screenId"], reason="configured_ghost_widget_coverage_unavailable")
                    self.verify_configured_ghost_widget(observation, self.phase)
                    ghost_accepted = True
                finally:
                    failed_phase = self.phase
                    try:
                        with anyio.CancelScope(shield=not ghost_accepted):
                            await self.workflow("close_ghost_acceptance_" + str(execution + 1),
                                [_action("close", "mcb_close_gui", {})], emergency=not ghost_accepted)
                    except (BridgeError, TimeoutError):
                        if ghost_accepted:
                            raise
                    finally:
                        self.opened_gui = None
                        if not ghost_accepted:
                            self.phase = failed_phase
                self.phase = "configured_ghost_acceptance_" + str(execution + 1)
            else:
                report = await self.workflow("execute_" + str(execution + 1), steps)
            input_after = inventory_slots({"inventory": self.value(report, "input_after", "inventory")})
            if self.single_inventory:
                self.validate_single_tanks({"fluids": self.value(report, "input_after", "fluids")})
            if self.provider_cache:
                output_after = {field: self.value(report, "output_delta", field)
                    for field in (*OWNER_KEYS, "sessionId", "source", "outputStorage")}
                observed_output = self.provider_output_count(output_after)
            else:
                output_after = {"inventory": self.value(report, "output_delta", "inventory")}
                observed_output = item_count(inventory_slots(output_after), recipe.output)
            _require(observed_output - baseline_output == delta,
                     "output_delta_mismatch", "Actual accumulated output differs from the declared executions.")
            _require(item_count(input_after, recipe.material) == 0 and (not physical_catalyst or item_count(input_after, recipe.catalyst) == 1),
                     "input_or_catalyst_acceptance_failed", "Consumables must be fully used while the one actual physical catalyst remains.")
            if self.configured_ghost:
                self.validate_configured_ghost_inventory(input_after)
            state = self.value(report, "stopped", "state")
            _require(state.get("allowedToWork") is False and state.get("active") is False
                     and self.value(report, "stopped", "metaTileId") == self.machine_id,
                     "controller_stop_not_confirmed", "The original real controller must be inactive and disabled.")
            if self.single_inventory:
                _require(self.value(report, "stopped", "metaClass") == self.machine_class
                         and all(slot["item"] is None or _same(slot["item"], recipe.output)
                                 or recipe.catalyst is not None and _same(slot["item"], recipe.catalyst) for slot in input_after),
                         "shared_inventory_acceptance_failed", "The same actual single machine's complete inventory must contain only accepted outputs and the retained catalyst.")
                self.core_stop_coverage = "not_applicable_single_block"
            else:
                final_components = self.value(report, "stopped", "components")
                _, self.core_stop_coverage, coverage_reason = registered_components(final_components, stopped=True)
                self.phases[-1].update(registeredCoreStopCoverage=self.core_stop_coverage, coverageReason=coverage_reason)
            if self.provider_cache:
                self.phase = "provider_final_acceptance_" + str(execution + 1)
                self.validate_registered_ports(await self.inspect_registered_machine())
                _require(await self.read_provider_output() == baseline_output + delta,
                    "provider_final_delta_mismatch", "After controller/core stop, the fresh pinned local cache must retain the full exact cumulative product delta.")
                final_input = inventory_slots(await self.call("world.inspect", self.position("input")))
                _require(item_count(final_input, recipe.material) == 0
                    and (not physical_catalyst or item_count(final_input, recipe.catalyst) == 1),
                    "input_or_catalyst_acceptance_failed", "Fresh stopped acceptance still requires exact input consumption and the one physical catalyst.")
            self.power_dispatched = False
            self.executions_completed = execution + 1
        result = {"status": "completed", "available": True, "nonAtomic": True, "rolledBack": False,
            "selection": "first_supported", "uniqueRecipeClaimed": False, "executions": executions,
            "recipe": {"category": recipe.category, "categoryRecipeIndex": recipe.category_index, "material": recipe.material,
                "materialPerExecution": recipe.material_count, "output": recipe.output, "outputPerExecution": recipe.output_count,
                "catalyst": recipe.catalyst, "rawEUt": recipe.raw_eut, "rawDurationTicks": recipe.raw_duration_ticks},
            "acceptance": {"outputDelta": recipe.output_count * executions, "consumed": recipe.material_count * executions,
                "catalystRetained": recipe.catalyst is not None, "controllerStopped": True, "exposedRegisteredCoresStopped": True,
                "registeredCoreStopCoverage": self.core_stop_coverage, "allRegisteredCoresStopClaimed": self.core_stop_coverage == "public_registered_modules",
                "source": "Real inventory deltas after native GUI transfers and power actions; no QA substitution."}}
        if self.provider_cache:
            result["acceptance"].update(outputMode="provider_cache", outputScope="local_output_provider_cache_only",
                queriedIdentity={"kind": "item", **recipe.output, "hasTag": False}, queriedIdentitySubsetComplete=True,
                outputOwner=self.output_provider_owner, outputSessionId=self.args["expectedSessionId"],
                outputBaseline=str(baseline_output), outputFinal=str(baseline_output + recipe.output_count * executions),
                outputInventoryEmpty=True,
                source="Exact queried local provider-cache deltas with real input consumption, retained catalyst and stopped controller/registered cores; no network or offline claim.")
        self.recipe_acceptance = result["acceptance"]
        if recipe.candidate_source == "public_backend_find":
            result["recipe"].pop("categoryRecipeIndex")
            result["recipe"].update(candidateOrdinal=0, candidateSource="public_backend_find",
                recipeLookup="backend_match", registeredCatalogComplete=False)
            if not self.single_api_verified:
                result["recipe"]["voltageEvidence"] = self.backend_voltage_evidence
        if self.single_inventory:
            result["acceptance"].pop("exposedRegisteredCoresStopped")
            result["acceptance"].update(structureFormationClaimed=False, allRegisteredCoresStopClaimed=False,
                inventoryScope="complete_shared_machine_inventory", inputRoleAutomaticallyDiscovered=self.input_gui_binding is not None)
        if self.configured_ghost:
            result["recipe"]["catalystMode"] = "configured_ghost"
            result["acceptance"].update(catalystMode="configured_ghost", configuredGhostCircuitRetained=True,
                configuredGhostCircuit=self.ghost_circuit,
                catalystRetentionSource="Matching public sync-handler configuration and actual zero-count shared inventory entry")
        if self.args.get("restoreInventory", False) and self.borrowed_inventory:
            await self.restore_borrowed_inventory()
        else:
            await self.verify_retained_inventory()
        return result


async def run_recipe_once(client: BridgeClient, arguments: dict[str, Any], lock: anyio.Lock, *,
                          runner: Callable[..., Awaitable[dict[str, Any]]],
                          query_reader: Callable[..., Awaitable[dict[str, Any]]] | None = None) -> dict[str, Any]:
    """Manage the shared workflow lock internally; shared tools/server wiring belongs to the caller."""
    started = time.monotonic()
    try:
        jsonschema.Draft202012Validator(RECIPE_ONCE_SCHEMA).validate(arguments)
    except jsonschema.ValidationError as error:
        raise BridgeError("INVALID_ARGUMENTS", "Recipe task declaration is invalid.", argument_path=list(error.absolute_path), rule=error.validator) from None
    task_type = _RecipeTask
    if arguments.get("recipeMode") == "public_multi":
        from .multi_recipe_tasks import _MultiRecipeTask
        task_type = _MultiRecipeTask
    task = task_type(client, arguments, runner, query_reader, started=started)
    acquired = False
    try:
        with anyio.fail_after(task.remaining() / 1000):
            await lock.acquire()
        acquired = True
        result = await task.execute()
        await task.finish_survival_posture()
    except (BridgeError, TimeoutError) as error:
        failed_phase = task.phase
        failed_action = task.last_action
        await task.stop_on_failure()
        result = {"status": "failed" if task.side_effects else "unavailable", "available": False,
            "reason": error.details.get("reason", error.code) if isinstance(error, BridgeError) else "task_timeout",
            "error": error.as_dict() if isinstance(error, BridgeError) else {"code": "RECIPE_TASK_TIMEOUT", "message": "The declared task deadline expired."},
            "failedPhase": failed_phase, "lastActionReceipt": failed_action, "nonAtomic": True, "rolledBack": False,
            "fallback": "Use the existing sequence for cases outside this task's declared capability."}
        if isinstance(failed_action, dict) and failed_action.get("operation") == "gui.transfer_whole_stack":
            result.update(automaticReplayAllowed=False,
                fallback="A native whole-stack request was dispatched. Do not replay, use a legacy fallback, clear or discard its cursor; inspect actual state before a new plan.")
        if task.input_transfer_attempted:
            result["remainingInputs"] = await task.remaining_inputs_on_failure()
        if not task.inventory_verification_attempted and (task.recipe_context_inventory is not None and task.side_effects
                or not arguments.get("restoreInventory", False)
                and any(record.get("movementDispatched") is True for record in task.borrowed_inventory)):
            await task.verify_retained_inventory(on_failure=True)
    except (AttributeError, KeyError, TypeError, ValueError) as error:
        failed_phase = task.phase
        failed_action = task.last_action
        await task.stop_on_failure()
        result = {"status": "failed" if task.side_effects else "unavailable", "available": False,
            "reason": "malformed_task_observation", "error": {"code": "MALFORMED_RESPONSE", "message": "A required task observation was malformed; no field was inferred.", "errorType": type(error).__name__},
            "failedPhase": failed_phase, "lastActionReceipt": failed_action, "nonAtomic": True, "rolledBack": False}
        if task.input_transfer_attempted:
            result["remainingInputs"] = await task.remaining_inputs_on_failure()
        if not task.inventory_verification_attempted and (task.recipe_context_inventory is not None and task.side_effects
                or not arguments.get("restoreInventory", False)
                and any(record.get("movementDispatched") is True for record in task.borrowed_inventory)):
            await task.verify_retained_inventory(on_failure=True)
    except anyio.get_cancelled_exc_class():
        await task.stop_on_failure()
        raise
    finally:
        if acquired:
            lock.release()
    task.timing_stage_set(None)
    elapsed = int((time.monotonic() - started) * 1000)
    for phase in task.phases:
        if phase.get("status") == "running":
            phase.update(status="failed", elapsed_ms=int(task.timings["recipeDecision"]))
    result.update(taskElapsed_ms=elapsed, timings_ms={**{key: int(value) for key, value in task.timings.items()}, "total": elapsed},
        timingsScope="Non-overlapping declaration/lock/preflight, registered recipe selection, preparation (including native hand preparation), then each finite GUI feed plus execution, real acceptance and final inventory verification or requested native hand restoration; emergency stop is included in the stage that failed. Validation is included. Phase/hand timings are nested detail, not additive totals.",
        nativeWorkflowElapsed_ms=sum(phase.get("elapsed_ms", 0) for phase in task.phases if phase.get("timingScope") == "native_workflow"), phases=task.phases,
        sideEffectsPossible=task.side_effects, materialGenerationRequested=arguments.get("generateMaterials", False),
        generatedMaterials=task.material_grants_dispatched > 0 and (task.material_supply_mode != "native_server_give_commands" or task.material_supply_confirmed),
        materialGrantsDispatched=task.material_grants_dispatched,
        materialSupplyMode=task.material_supply_mode, materialSupplyConfirmed=task.material_supply_confirmed,
        timingScope="Task declaration through final real acceptance, including query/preflight/planning/native waits; native workflow time is a subset.")
    if not task.material_grants_count_known:
        result["materialGrantsDispatchedCountKnown"] = False
    if task.recipe_context_inventory is not None:
        result["retainedPlayerContext"] = {"protectedIndices": sorted(task.recipe_context_items),
            "completePlayerSlots": 41, "completeDualFullNbtConserved": task.final_inventory_verified,
            "confirmedInventoryChanges": task.recipe_context_events,
            "explicitFluidConversionIndices": sorted(task.recipe_context_mutable_indices()),
            "source": "Fresh complete client/server native identities and exact known finite checkpoints; unrelated tagged context stays in its original slot."}
    if task.survival_flight:
        result.update(survivalOperationPosture="ability_flight", finalPosture=task.final_posture)
    if task.approach_summary() is not None:
        result["approachPlanning"] = task.approach_summary()
    if task.single_block_placement is not None:
        placement = dict(task.single_block_placement)
        if result.get("status") == "completed" and not arguments.get("includeDetails", False):
            native = placement.get("nativeReceipt")
            if isinstance(native, dict):
                placement["nativeReceipt"] = {key: value for key, value in native.items() if key not in ("serverBefore", "serverAfter")}
                placement["detailsOmitted"] = ["nativeReceipt.serverBefore", "nativeReceipt.serverAfter"]
        result["singleBlockPlacement"] = placement
    borrowed = [{key: record[key] for key in ("hotbarIndex", "storageIndex", "item", "count", "status", "purpose",
        "movementDispatched", "movementConfirmed", "restorationConfirmed", "retentionConfirmed", "outcomeUnknown") if key in record} for record in task.borrowed_inventory]
    restoration_requested = arguments.get("restoreInventory", False)
    result.update(executionsCompleted=task.executions_completed, inventoryRestorationRequested=restoration_requested,
        inputGuiSlot=task.input_gui_slot,
        inputGuiSlotSource="public_machine_input_binding" if task.input_gui_binding is not None else
            "caller" if "inputGuiSlot" in arguments else "automatic_unresolved",
        inputGuiBinding=task.input_gui_binding,
        inventoryPreparation={"mode": task.inventory_preparation_mode, "clearConfirmed": task.inventory_clear_confirmed,
            "clearReceipt": task.inventory_clear_receipt,
            "requiredEmptyHotbarSlots": task.required_empty_hotbar_slots, **task.hand_timings,
            "singleFeedGrants": task.single_feed_grants, "grantCapacityProof": task.grant_capacity_proof,
            "finalInventoryVerified": task.final_inventory_verified, "finalCursorEmpty": task.final_cursor_empty,
            "borrowed": borrowed, "allBorrowedStacksRestored": all(record.get("restorationConfirmed") is True for record in borrowed)},
        retainedInventoryMoves=[record for record in borrowed if not restoration_requested and record.get("retentionConfirmed") is True
            and record.get("movementConfirmed") is True and record.get("outcomeUnknown") is not True],
        pendingInventoryRestoration=[record for record in borrowed if record.get("movementDispatched") is True and record.get("restorationConfirmed") is not True
            and (restoration_requested or record.get("retentionConfirmed") is not True or record.get("movementConfirmed") is not True
                 or record.get("outcomeUnknown") is True)])
    if task.single_inventory:
        result["machineScope"] = {"kind": "single_block" if task.single_api_verified else "unverified",
            "commonBasicMachineAPIVerified": task.single_api_verified, "structureFormationClaimed": False,
            "registeredCoreStopClaimed": False, "inputRoleAutomaticallyDiscovered": task.input_gui_binding is not None,
            "declaredGuiSlots": task.declared_gui_slots,
            "guiSlotSource": "Public actual machine owner/handler/index and consumed-input role, revalidated before transfer." if task.input_gui_binding is not None else
                "Caller-observed declared native GUI slots and their current actual inventory indices; no automatic input/output-role inference."}
    if task.recipe_acceptance is not None and result["status"] != "completed":
        result.update(recipeAcceptanceCompleted=True, acceptance=task.recipe_acceptance)
    if task.recipe_decision is not None and result["status"] != "completed":
        result["selectedRecipe"] = task.recipe_decision
    if task.recipe is not None and task.recipe.catalyst is not None and not task.configured_ghost:
        result.update(catalystGuiSlot=task.catalyst_gui_slot,
            catalystGuiSlotSource="public_machine_input_binding" if task.catalyst_gui_binding is not None else "caller",
            catalystGuiBinding=task.catalyst_gui_binding)
    include_details = arguments.get("includeDetails", False)
    result.update(detailsIncluded=include_details,
        outcomeUnknown=any(phase.get("outcomeUnknown") is True for phase in task.phases)
            or any(record.get("outcomeUnknown") is True for record in borrowed)
            or (result.get("lastActionReceipt") or {}).get("outcomeUnknown") is True
            or (task.single_block_placement or {}).get("outcomeUnknown") is True)
    if result["status"] != "completed":
        try:
            resources = await task.failure_resources()
            if resources is not None:
                result["resourceDiagnostics"] = resources
        except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError):
            result["resourceDiagnostics"] = {"available": False, "source": "Current resource read unavailable; no action replayed."}
        if result.get("failedPhase") == "recipe_query" and task.backend_lookup_proof is not None:
            result["backendLookupProof"] = task.backend_lookup_proof
        result["failedNativeWorkflows"] = [phase for phase in task.phases
            if phase.get("timingScope") == "native_workflow" and phase.get("status") != "completed"]
        emergency = [phase for phase in task.phases if phase.get("phase") == "emergency_stop"]
        if emergency:
            result["emergencyStop"] = {key: emergency[-1][key] for key in
                ("status", "serverStopConfirmed", "outcomeUnknown", "error", "cancellationStatus") if key in emergency[-1]}
    if not include_details:
        result.pop("phases")
        result["inventoryPreparation"].pop("grantCapacityProof")
        result.update(
            timingScope="Full task: declaration, lock, reads, recipe decision, native actions/waits, real acceptance, final inventory verification/requested restoration and failure diagnostics.",
            timingsScope="Non-overlapping stages; total includes all task work. Native workflow and hand timings are nested subsets.")
    return result
