"""A bounded native blueprint task over observed public Minecraft/GT interfaces.

Hints are a placement decision aid. Only the controller's later real server
formation state and registered roles are acceptance evidence.
"""

from __future__ import annotations

import copy
import heapq
import json
import math
import re
import time
from contextlib import nullcontext
from collections import deque
from typing import Any, Awaitable, Callable

import anyio
import jsonschema

from .bridge import BridgeClient, BridgeError, _ContextData, request_context, response_context
from .validation import tool_validator
from .material_sources import AE_MATERIAL_SOURCE_SCHEMA
from .survival_posture import SURVIVAL_POSTURE_PROPERTIES, SURVIVAL_POSTURE_CONSTRAINTS
from .placement_tasks import (PLACEMENT_OPTIONS, _native_visit_header, _native_visit_union,
    _validation_geometry_sha256, run_placement_preflight, revalidate_placement)
from .recipe_tasks import (
    _RecipeTask, _action, _assert, _identity, _match, _object, _quantity, _read,
    _same, _wait, inventory_slots, power_widget,
)
from .task_reports import compact_blueprint_report
from .assembly_plan import (CONNECTION_SCHEMA, compile_connections, construction_slots, expand_ports,
    port_groups_schema, verify_group_materials, verify_networks)
from .conveniences import json_sha256
from .nbt_order import context_equal
from .placement_attachment import require_capability as require_attachment_capability, from_plan as bind_placement_attachment, validate_receipt as validate_attachment_receipt


class _ApproachSteps(list):
    """Carry scalar expectations, never an observed snapshot, into one workflow."""

    def __init__(self, checks: Any):
        super().__init__()
        self.snapshot_checks = checks


_COORD = _object({"x": {"type": "integer", "minimum": -29_999_996, "maximum": 29_999_996},
    "y": {"type": "integer", "minimum": 2, "maximum": 251},
    "z": {"type": "integer", "minimum": -29_999_996, "maximum": 29_999_996}}, ["x", "y", "z"])
_ITEM = _object({"registryName": {"type": "string", "minLength": 3, "maxLength": 128,
    "pattern": r"^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+$", "not": {"pattern": r"[\s\x00-\x1F\x7F]"}},
    "metadata": {"type": "integer", "minimum": 0, "maximum": 32_766}}, ["registryName", "metadata"])
ROLE_KEYS = {"item_input": "GT5U.MBTT.InputBus", "item_output": "GT5U.MBTT.OutputBus",
    "fluid_input": "GT5U.MBTT.InputHatch", "fluid_output": "GT5U.MBTT.OutputHatch",
    "energy": "GT5U.MBTT.EnergyHatch", "maintenance": "GT5U.MBTT.MaintenanceHatch",
    "muffler": "GT5U.MBTT.MufflerHatch", "data_access": "hatch_type_dataaccess"}
_GROUP_SELECTOR = _object({
    "descriptionKeys": {"type": "array", "minItems": 1, "maxItems": 128, "uniqueItems": True,
        "items": {"type": "string", "minLength": 1, "maxLength": 256}},
    "occurrence": {"type": "integer", "minimum": 0, "maximum": 16_383},
}, ["descriptionKeys", "occurrence"])
_PORT = _object({"role": {"type": "string", "enum": list(ROLE_KEYS)},
    "id": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"^[a-zA-Z0-9_.-]+$"},
    "groupSelector": _GROUP_SELECTOR,
    "hotbarSlot": {"type": "integer", "minimum": 0, "maximum": 8}, "ordinaryOutputItem": _ITEM}, ["role", "hotbarSlot"])
_PORT.update({"if": {"properties": {"role": {"const": "item_output"}}},
    "then": {"required": ["ordinaryOutputItem"]}, "else": {"not": {"required": ["ordinaryOutputItem"]}}})
BLUEPRINT_BUILD_SCHEMA = _object({
    "expectedSessionId": {"type": "string", "minLength": 1, "maxLength": 128},
    "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128},
    "dimension": {"type": "integer", "minimum": -2_147_483_648, "maximum": 2_147_483_647},
    "controller": _COORD, "controllerItem": _ITEM,
    "controllerFacing": {"enum": ["NORTH", "SOUTH", "WEST", "EAST"],
        "description": "Required for a grounded adjacent-seed bootstrap. Declares the normal GT horizontal facing used by caller-supplied world-axis port offsets. One proved walk on the already placed seed prepares a side stance; native server/client rays and the actual click must match this facing before placement. No tile rotation, teleport, extra support or fallback. Only a perpendicular adjacent seed is supported; exact captured seed membership is still independently checked after hints."},
    "placement": {**PLACEMENT_OPTIONS,
        "description": "Explicit optional terrain planning. Uses controller as a nearby anchor, then adopts the lowest clear envelope before writes. Declare complete attached AE/equipment/access space in layoutComponents so it participates in height and footprint calculations; only pure IO may reserve the single hidden_bottom layer, with fresh trench air required. Returns all component worldBounds relative to the selected controller; auxiliary components are reserved, not installed. geometryMode defaults public_piece; explicit native_visits uses complete fresh unfiltered XYZ/public-group visits plus controller/attachment, never an inferred single piece. Requires the same real controller and exact blueprint identity/count/channel data as geometrySource. native_visits binds normal, unflipped horizontal GT placement through a proved safe current stance, actual click aim and fresh complete owner/alignment before hints; unsupported alignment or missing actual ray/pose evidence is unavailable. Total read/cell budgets include fresh full-box air checks before controller and shell (native_visits also proves existing-tile absence at every air cell), plus exact new native geometry matching; unavailable evidence never causes relocation or action replay. Omit for existing exact-coordinate behavior."},
    "blueprintHotbarSlot": {"type": "integer", "minimum": 0, "maximum": 8},
    "supportItem": {**_ITEM, "description": "Explicit caller-declared initial survival controller-column casing source, bounded to three. It is placed before hints and then binds its exact captured XYZ public candidate; it is not claimed publicly validated before placement. Generic later frontier seeds select finite real backpack casings from exact public position getters, bounded to eight. All remain permanent, consume real materials, and are never cleared or refunded. Creative support behavior is unchanged."},
    "supportHotbarSlot": {"type": "integer", "minimum": 0, "maximum": 8},
    "initialControllerSeeds": {"type": "array", "minItems": 1, "maxItems": 3,
        "items": _object({"offsetY": {"type": "integer", "minimum": -3, "maximum": -1},
            "item": _ITEM, "playerInventoryIndex": {"type": "integer", "minimum": 0, "maximum": 35}},
            ["offsetY", "item", "playerInventoryIndex"]),
        "description": "Actual-survival only: explicitly declare every literal-air layer from the observed floor+1 through controllerY-1, at most three, using unique negative offsetY and real playerInventoryIndex. Each layer may use a different untagged exact item; repeated sources require the same identity and sufficient aggregate finite count. Cannot mix with supportItem/supportHotbarSlot. Backpack sources and an unreserved hotbar source may use guarded native whole-stack borrowing, retaining only the unconsumed remainder. No pre-task placement, refunds or cleanup. Public suitability is unknown until fresh complete exact-XYZ candidates after controller/hints; mismatch stops with permanent placements retained."},
    "requestedPorts": {"type": "array", "minItems": 1, "maxItems": 6, "items": _PORT},
    "constructionOrder": {"type": "string", "enum": ["shell_first", "ports_first"],
        "description": "Defaults to shell_first for creative execution and ports_first for actualSurvival."},
    "energyTier": {"type": "integer", "minimum": 0, "maximum": 15,
        "description": "Optional public Debug Energy Hatch voltage tier. Uses its unique native voltage-tier field and confirms server voltage and registered input tier; ordinary energy ports are rejected after placement."},
    "restoreInventory": {"type": "boolean", "default": False},
    "stopOnComplete": {"type": "boolean", "default": False,
        "description": "Explicitly disable the newly constructed controller through its current public native power switch in the same controller GUI as structure recheck. Success requires one current complete formed/stopped controller and registered-component observation, zero progress and stopped exposed execution cores. Default formation-only behavior is preserved; unavailable controls or uncertain dispatch never cause reopening or replay."},
    "includeDetails": {"type": "boolean", "default": False,
        "description": "Return full success details when true; failures always retain the complete report."},
    "generateMaterials": {"type": "boolean", "default": False,
        "description": "Explicitly allow bounded task-declared supplies. Requires declaredSupplies and minimal/clear preparation. Same-role ports with separate IDs/groupSelectors/count-one declarations may receive one exact aggregate source with per-goal native placement bindings; existing inventory sources remain the default."},
    "actualSurvival": {"type": "boolean", "default": False,
        "description": "Use real noncreative player inventory and declared native operation posture (grounded by default). Defaults to ports_first, player support and preserve. Disconnected port groups may receive up to eight permanent casings selected from exact captured XYZ public getters and finite real backpack sources, with native stance/ray and server deduction proof; no source/group is inferred from a representative suggestion. Never clears, grants or replaces shell casings."},
    "bootstrapAdjacentSeed": _object({
        "offsetX": {"type": "integer", "minimum": -1, "maximum": 1},
        "offsetY": {"type": "integer", "const": -2},
        "offsetZ": {"type": "integer", "minimum": -1, "maximum": 1}, "item": _ITEM,
        "playerInventoryIndex": {"type": "integer", "minimum": 0, "maximum": 35}, "exitPosition": _COORD},
        ["offsetX", "offsetY", "offsetZ", "item", "playerInventoryIndex"]),
    "shellExitPosition": {**_COORD, "description": "Optional explicit feet cell outside the fresh captured build envelope, using the declared grounded/ability_flight operation posture. Omission uses bounded native outside/front-face approach selection. No strategy fallback."},
    **copy.deepcopy(SURVIVAL_POSTURE_PROPERTIES),
    "movementStrategy": {"enum": ["walking", "ability_flight", "developer_teleport"],
        "description": "Actual-survival approach preselection and AE connection strategy. Omitted strategy defaults to walking for actualSurvival and ability_flight for creative AE connections, matching the construction flight requirement. Explicit strategies are passed through unchanged. Creative construction keeps its existing developer approach. Explicit survival ability_flight uses normal real permission and lands by default; survivalOperationPosture=ability_flight retains already confirmed flight during operations; developer_teleport is a separately reported developer test /tp. No automatic fallback."},
    "survivalBuild": _object({"elementBudget": {"type": "integer", "minimum": 1, "maximum": 65_536},
        "finishAirTail": {"type": "boolean", "default": False,
            "description": "Explicit finite-player continuation after a fully accounted terminal no_progress: preflight at most16 remaining captured literal-air cells against exact public non-tile ItemBlock candidates and aggregate inventory, then place each once through native player interaction. Preserves ports and native receipt; never repeats survivalConstruct. Unknown/partial dispatch, AE material sources and unfillable/large tails stop. Final formation and registered-port acceptance remain required."},
        "maxBatches": {"type": "integer", "minimum": 1, "maximum": 32, "default": 32},
        "timeout_ms": {"type": "integer", "minimum": 500, "maximum": 8000, "default": 7500},
        "materialSource": AE_MATERIAL_SOURCE_SCHEMA}, []),
    "declaredSupplies": {"type": "array", "minItems": 3, "maxItems": 8,
        "items": _object({"hotbarSlot": {"type": "integer", "minimum": 0, "maximum": 8},
            "item": _ITEM, "count": {"type": "integer", "minimum": 1, "maximum": 64}}, ["hotbarSlot", "item", "count"])},
    "inventoryPreparation": {"enum": ["minimal", "clear", "preserve"], "default": "preserve",
        "description": "With generateMaterials=false, preserve is enforced and clear is refused. Explicit creative generation may use minimal (verify exact supplies or clear once) or clear. Unknown coverage or cursor state is refused."},
    "supportMode": {"enum": ["command", "command_column", "player"],
        "description": "Explicit creative command modes also prepare one temporary air-only attachment below a disconnected ports_first port, inside any proved layout. It is identity-checked and removed immediately after native placement, before the shell. Player/survival modes retain real-material support; no failed placement is replayed."},
    "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 120_000, "default": 60_000,
        "description": "Total construction deadline, including native walking, ports, shell and acceptance. Explicitly allow up to120s for larger survival builds; each native action retains its existing bounded budget and no-replay contract."},
}, ["expectedSessionId", "expectedWorld", "dimension", "controller", "controllerItem", "blueprintHotbarSlot", "requestedPorts"])
BLUEPRINT_BUILD_SCHEMA["properties"]["survivalBuild"]["description"] = "Omission uses real player materials. Optional explicit ae_cells finite/creative_infinite applies only to the native shell; controller, declared ports and permanent casing seeds still consume actual player inventory (materials may first be taken from AE through normal gameplay). The task owns fresh source readiness reads and binds exact grid/actor/anchor/mode. Each shell batch needs full backing/extraction ledgers and unchanged all41 player slots/full NBT; finite decrement and creative unchanged supply are distinct proofs. Unknown/partial results stop without fallback or replay."
BLUEPRINT_BUILD_SCHEMA["properties"]["survivalBuild"]["allOf"] = [{
    "if": {"required": ["finishAirTail"], "properties": {"finishAirTail": {"const": True}}},
    "then": {"not": {"required": ["materialSource"]}}}]
BLUEPRINT_BUILD_SCHEMA["dependentRequired"] = {"supportItem": ["supportHotbarSlot"], "supportHotbarSlot": ["supportItem"]}
BLUEPRINT_BUILD_SCHEMA["properties"]["bootstrapAdjacentSeed"]["oneOf"] = [
    {"properties": {"offsetX": {"const": x}, "offsetZ": {"const": z}}}
    for x, z in ((1, 0), (-1, 0), (0, 1), (0, -1))]
BLUEPRINT_BUILD_SCHEMA["properties"]["bootstrapAdjacentSeed"]["description"] = "Optional explicitly declared permanent cardinal adjacent floor+1 seed for exactly two controller-column layers. Native placement order is lower column, adjacent seed, one bounded normal +1 jump, upper column and controller. After hints every seed must match complete public candidates at its exact XYZ before one natural -1 walk-off. Explicit ability_flight reuses authorized native navigation instead. Unknown/partial transitions stop with known seeds retained, no replay/refund or strategy fallback. Shell construction then requires the actual full actor body outside the fresh captured envelope; optional exitPosition names an original-ground feet cell."
BLUEPRINT_BUILD_SCHEMA["allOf"] = [{"if": {"required": ["survivalBuild"]},
    "then": {"required": ["actualSurvival"], "properties": {"actualSurvival": {"const": True}}}},
    {"if": {"required": ["initialControllerSeeds"]}, "then": {"required": ["actualSurvival"],
        "properties": {"actualSurvival": {"const": True}},
        "not": {"anyOf": [{"required": ["supportItem"]}, {"required": ["supportHotbarSlot"]}]}}},
    {"if": {"required": ["bootstrapAdjacentSeed"]}, "then": {"required": ["actualSurvival", "initialControllerSeeds"],
        "properties": {"actualSurvival": {"const": True}, "initialControllerSeeds": {"minItems": 2, "maxItems": 2},
            "movementStrategy": {"enum": ["walking", "ability_flight"]}}}},
    {"if": {"required": ["shellExitPosition"]}, "then": {"required": ["actualSurvival"],
        "properties": {"actualSurvival": {"const": True}}}}]

BLUEPRINT_BUILD_SCHEMA["allOf"].extend(copy.deepcopy(SURVIVAL_POSTURE_CONSTRAINTS))
BLUEPRINT_BUILD_SCHEMA["allOf"].extend([
    {"if": {"required": ["bootstrapAdjacentSeed"], "properties": {"movementStrategy": {"const": "walking"}}},
     "then": {"required": ["controllerFacing"]}},
    {"if": {"required": ["controllerFacing"]}, "then": {"required": ["bootstrapAdjacentSeed", "actualSurvival"],
        "properties": {"actualSurvival": {"const": True}, "movementStrategy": {"const": "walking"}}}},
])

BLUEPRINT_BUILD_SCHEMA["properties"]["portGroups"] = port_groups_schema(ROLE_KEYS, _GROUP_SELECTOR, _ITEM)
BLUEPRINT_BUILD_SCHEMA["properties"]["connectionPlan"] = CONNECTION_SCHEMA
BLUEPRINT_BUILD_SCHEMA["required"].remove("requestedPorts")
BLUEPRINT_BUILD_SCHEMA["anyOf"] = [{"required": [key]} for key in ("requestedPorts", "portGroups")]

_VECTORS = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}
_FRONT = {"NORTH": (0, 0, -1), "SOUTH": (0, 0, 1), "WEST": (-1, 0, 0), "EAST": (1, 0, 0)}
_REQUIRED = ("client.snapshot", "client.background", "inventory.inspect", "registry.item", "world.inspect", "world.inspect_many",
    "world.spatial", "world.structure", "machine.inspect", "gui.inspect", "gui.widgets", "gui.widget_action",
    "gui.open_inventory", "gui.close", "player.hotbar", "player.interact", "dev.teleport")
_DEBUG_ENERGY_API = "gregtech.api.metatileentity.implementations.MTEHatchEnergyDebug"
_MUI_TEXT_FIELD_API = "com.cleanroommc.modularui.widgets.textfield.TextFieldWidget"
_MAX_CAPTURE_POSITIONS = 16_384
_MAX_CAPTURE_BYTES = 4_194_304
_GROUP_SELECTION_BINDING = "capture_scoped_public_definition_element_group"
_MAX_FRONTIER_SEEDS = 8
_MAX_FRONTIER_CANDIDATE_POSITIONS = 128
_MAX_FRONTIER_CANDIDATE_BYTES = 1_048_576
# GT5-Unofficial 5.09.54.183 public GTValues.V, used by MTEHatchEnergyDebug.maxEUInput().
# MAX is deliberately Integer.MAX_VALUE - 7, rather than a geometric extrapolation.
_DEBUG_ENERGY_VOLTAGES = (8, 32, 128, 512, 2048, 8192, 32768, 131072, 524288,
    2097152, 8388608, 33554432, 134217728, 536870912, 2147483640, 8589934592)


class _Unavailable(BridgeError):
    def __init__(self, reason: str, message: str):
        super().__init__("BLUEPRINT_TASK_UNAVAILABLE", message, reason=reason)


def _require(condition: bool, reason: str, message: str) -> None:
    if not condition:
        raise _Unavailable(reason, message)


def _xyz(value: dict[str, Any]) -> dict[str, int]:
    return {axis: value[axis] for axis in ("x", "y", "z")}


def _key(value: dict[str, Any]) -> tuple[int, int, int]:
    return tuple(value[axis] for axis in ("x", "y", "z"))


def _block_identity(value: dict[str, Any]) -> dict[str, Any]:
    _require(isinstance(value.get("registryName"), str) and type(value.get("metadata")) is int,
        "block_identity_unavailable", "A real server registry name and metadata are required.")
    return {key: value[key] for key in ("registryName", "metadata")}


def _survival_inventory_entries(inventory: dict[str, Any]) -> list[dict[str, Any]]:
    _require(isinstance(inventory, dict) and inventory.get("source") == "integrated_server"
        and inventory.get("inventorySource") == "actual_player_inventory" and inventory.get("complete") is True
        and inventory.get("cursorItem") is None and "cursorItem" in inventory,
        "survival_server_inventory_unavailable", "Actual survival needs complete authoritative player inventory and an explicitly empty cursor.")
    slots = inventory_slots(inventory, "")
    _require(len(slots) == 41 and {slot["index"] for slot in slots} == set(range(41)),
        "survival_server_inventory_unavailable", "The authoritative material ledger must cover all actual 41 player slots.")
    _require(all(row["item"] is None or isinstance(row["item"], dict)
        and type(row["item"].get("count")) is int and row["item"]["count"] > 0
        and isinstance(row["item"].get("registryName"), str) and type(row["item"].get("metadata")) is int
        and type(row["item"].get("hasTag")) is bool and isinstance(row["item"].get("nbtIdentity"), str)
        for row in slots), "survival_server_inventory_unavailable", "All authoritative items need exact positive quantities and full native NBT identity.")
    return sorted(slots, key=lambda slot: slot["index"])


def _context_inventory_rows(inventory: dict[str, Any]) -> list[dict[str, Any]]:
    """Canonical complete identities, never inferred from tag presence alone."""
    reason = "tagged_context_inventory_unavailable"
    _require(isinstance(inventory, dict) and inventory.get("complete") is True, reason,
        "Tagged context needs explicitly complete full-NBT inventory evidence.")
    slots = inventory_slots(inventory, "")
    _require(len(slots) == 41 and {row["index"] for row in slots} == set(range(41)), reason,
        "Tagged context must bind all41 actual player slots, including armor.")
    result = []
    for row in sorted(slots, key=lambda row: row["index"]):
        item = row["item"]
        if item is not None:
            _require(isinstance(item, dict) and type(item.get("hasTag")) is bool
                and type(item.get("count")) is int and item["count"] > 0
                and isinstance(item.get("nbtIdentity"), str)
                and (bool(item["nbtIdentity"]) if item["hasTag"] else item["nbtIdentity"] == ""), reason,
                "Every occupied context slot needs positive quantity, typed tag presence and explicit canonical full NBT.")
            # Validate public identifier syntax independently; tagged identity
            # is still bound by the exact hasTag/NBT fields below.
            _require(isinstance(item.get("registryName"), str)
                and 3 <= len(item["registryName"]) <= 128
                and re.fullmatch(r"[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+", item["registryName"]) is not None
                and type(item.get("metadata")) is int and -2_147_483_648 <= item["metadata"] <= 2_147_483_647,
                reason, "A retained native identity needs its complete registry and typed Java metadata; it is not a resource-source wildcard.")
            identity = {key: item[key] for key in ("registryName", "metadata")}
            item = {**identity, **{key: item[key] for key in ("count", "hasTag", "nbtIdentity")}}
        result.append({"index": row["index"], "item": item})
    return result


def _initial_seed_inventory_entries(inventory: dict[str, Any]) -> list[dict[str, Any]]:
    """Exact observable client inventory; tagged unrelated full NBT is unknown."""
    _require(isinstance(inventory, dict) and inventory.get("truncated") is False
        and type(inventory.get("totalSlots")) is int and inventory["totalSlots"] == 41
        and "cursorItem" in inventory and inventory["cursorItem"] is None,
        "initial_seed_inventory_unavailable", "Initial seed borrowing needs all41 actual slots and an explicitly empty cursor.")
    slots = inventory_slots(inventory, "")
    _require(len(slots) == 41 and all(type(row.get("index")) is int for row in slots)
        and {row["index"] for row in slots} == set(range(41)),
        "initial_seed_inventory_unavailable", "Every actual slot index must occur exactly once.")
    result = []
    for row in sorted(slots, key=lambda row: row["index"]):
        item = row["item"]
        if item is not None:
            _require(isinstance(item, dict) and isinstance(item.get("registryName"), str)
                and type(item.get("metadata")) is int and type(item.get("count")) is int and item["count"] > 0
                and type(item.get("hasTag")) is bool,
                "initial_seed_inventory_unavailable", "Every occupied slot needs its observed identity, positive count and typed tag presence.")
            nbt = item.get("nbtIdentity")
            _require(nbt is None or isinstance(nbt, str), "initial_seed_inventory_unavailable", "An observed NBT identity must be text.")
            _require(item["hasTag"] or nbt in (None, ""), "initial_seed_inventory_unavailable",
                "An explicitly untagged source cannot claim a noncanonical NBT identity.")
            item = {key: item[key] for key in ("registryName", "metadata", "count", "hasTag")}
            item["nbtIdentity"] = nbt if item["hasTag"] else ""
        result.append({"index": row["index"], "item": item})
    return result


def _survival_deductions(before: dict[str, Any], after: dict[str, Any]) -> list[dict[str, Any]]:
    """Derive exact source-slot deductions; the last item may leave literal null."""
    old, new = _survival_inventory_entries(before), _survival_inventory_entries(after)
    deductions = []
    for source, destination in zip(old, new):
        left, right = source["item"], destination["item"]
        if left == right:
            continue
        _require(isinstance(left, dict) and type(left.get("count")) is int and left["count"] > 0
            and isinstance(left.get("registryName"), str) and type(left.get("metadata")) is int
            and type(left.get("hasTag")) is bool and isinstance(left.get("nbtIdentity"), str),
            "survival_material_conservation_unavailable", "Changed real server sources need complete identity, positive count and full native NBT identity.")
        count = 0 if right is None else right.get("count") if isinstance(right, dict) else None
        _require(type(count) is int and 0 <= count < left["count"] and (right is None or
            {key: value for key, value in left.items() if key != "count"} ==
            {key: value for key, value in right.items() if key != "count"}),
            "survival_material_conservation_unavailable", "A native survival action may only decrease matching actual source stacks; identity changes, additions and unexplained moves are refused.")
        deductions.append({"playerInventoryIndex": source["index"], "registryName": left["registryName"],
            "metadata": left["metadata"], "count": left["count"] - count,
            "sourceCountBefore": left["count"], "sourceCountAfter": count, "nbtIdentity": left["nbtIdentity"]})
    return deductions


def _shortest_seed_path(points: dict[tuple[int, int, int], dict[str, Any]],
    choices: dict[tuple[int, int, int], list[dict[str, Any]]], roots: set[tuple[int, int, int]],
    goals: set[tuple[int, int, int]], counts: dict[int, int], budget: int,
    forbidden: set[tuple[int, int, int]] | None = None) -> list[dict[str, Any]] | None:
    """Shortest finite-material connection; XYZ topology never proves a native stance or acceptance."""
    queue = deque((root, [], {}) for root in sorted(roots))
    seen = set()
    expanded = 0
    while queue and expanded < 2048:
        position, path, usage = queue.popleft()
        expanded += 1
        for vector in _VECTORS.values():
            neighbour = tuple(position[index] + vector[index] for index in range(3))
            if neighbour in goals:
                if path and neighbour not in {_key(step["position"]) for step in path}:
                    return path
                # Direct candidates were already native-probed. An additional
                # casing may provide another visible face at a different goal.
                if len(goals) <= 1 or neighbour in (forbidden or set()):
                    continue
            if len(path) >= budget or neighbour not in points or neighbour in roots:
                continue
            if neighbour in {_key(step["position"]) for step in path}:
                continue
            for choice in choices.get(neighbour, []):
                index = choice["sourceIndex"]
                consumed = usage.get(index, 0) + 1
                if consumed > counts[index]:
                    continue
                following = {**usage, index: consumed}
                signature = neighbour, tuple(sorted(following.items()))
                if signature in seen:
                    continue
                seen.add(signature)
                queue.append((neighbour, path + [{"position": points[neighbour], **choice}], following))
    return None



_AE_SOURCE_FIELDS = ["source", "operation", "sessionId", "dimension", "x", "y", "z", "side", "selector",
    "supported", "bindingVerified", "sourceMode", "sourceScope", "wholeGridProviderScopeProven",
    "generationPerformed", "materialsExtracted", "accountingVerified", "nodeActive", "actionableNodeActive",
    "networkPowered", "extractPermission", "nativeExtractPermissionChecked", "bindingValidatedAtExtraction",
    "nativeExtractionCalls", "nativeEnergyItemAllowance", "energyProbeItems", "materialSource", "cellCount",
    "gridId", "actorUuid", "actorEntityId", "actorBinding", "anchorClass", "handlerRestrictionsPreserved", "cells", "reason"]
_AE_BINDING_FIELDS = ("gridId", "dimension", "x", "y", "z", "side", "selector", "actorUuid", "actorEntityId",
    "actorBinding", "anchorClass", "nativeExtractPermissionChecked", "handlerRestrictionsPreserved", "cells")
_AE_ACTOR_BINDING = "same_actual_authoritative_entity_and_world_objects"
_AE_CELL_BINDING = "same_actual_cell_stack_object; storage NBT not serialized"
_AE_LONG_MAX = 9_223_372_036_854_775_807


def _ae_source_binding(value: dict[str, Any], declaration: dict[str, Any], dimension: int,
        player_id: str) -> dict[str, Any]:
    """Readiness does not serialize stock or prove continuity with a later RPC."""
    _require(isinstance(value, dict) and value.get("gridId") == declaration["expectedGridId"]
        and value.get("actorUuid") == player_id and type(value.get("actorEntityId")) is int
        and value["actorEntityId"] >= 0 and value.get("actorBinding") == _AE_ACTOR_BINDING
        and type(value.get("dimension")) is int and value["dimension"] == dimension
        and all(type(value.get(key)) is type(declaration[key]) and value[key] == declaration[key]
            for key in ("x", "y", "z", "side", "selector"))
        and isinstance(value.get("anchorClass"), str) and bool(value["anchorClass"])
        and value.get("nativeExtractPermissionChecked") is True
        and value.get("handlerRestrictionsPreserved") is True,
        "survival_ae_source_binding_unavailable", "The source must bind the exact fresh public grid, selected anchor, authoritative actor/entity and preserved native restrictions.")
    cells = value.get("cells")
    _require(isinstance(cells, list) and 1 <= len(cells) <= 64
        and all(isinstance(cell, dict) and type(cell.get("cellIndex")) is int and cell["cellIndex"] == index
            and cell.get("creative") is (declaration["mode"] == "creative_infinite")
            and all(isinstance(cell.get(key), str) and bool(cell[key]) for key in ("handlerClass", "backingClass"))
            and isinstance(cell.get("physicalCell"), dict)
            and isinstance(cell["physicalCell"].get("registryName"), str) and bool(cell["physicalCell"]["registryName"])
            and type(cell["physicalCell"].get("metadata")) is int and cell["physicalCell"]["metadata"] >= 0
            and type(cell["physicalCell"].get("hasTag")) is bool
            and cell["physicalCell"].get("identityBinding") == _AE_CELL_BINDING
            for index, cell in enumerate(cells)), "survival_ae_source_cells_unavailable",
        "The actual selected container needs complete typed native cells matching the explicit source mode; no mixed or inferred provider scope is accepted.")
    return {key: copy.deepcopy(value[key]) for key in _AE_BINDING_FIELDS}


def _ae_source_readiness(value: dict[str, Any], declaration: dict[str, Any], dimension: int,
        player_id: str, session_id: str) -> dict[str, Any]:
    binding = _ae_source_binding(value, declaration, dimension, player_id)
    _require(value.get("source") == "integrated_server" and value.get("operation") == "ae.material_source"
        and value.get("sessionId") == session_id and value.get("supported") is True
        and value.get("bindingVerified") is True and value.get("sourceMode") == declaration["mode"]
        and value.get("materialSource") == declaration and value.get("sourceScope") == "bound_cell_container"
        and value.get("wholeGridProviderScopeProven") is False
        and all(value.get(key) is True for key in ("nodeActive", "actionableNodeActive", "networkPowered", "extractPermission"))
        and all(value.get(key) is False for key in ("generationPerformed", "materialsExtracted", "accountingVerified", "bindingValidatedAtExtraction"))
        and type(value.get("nativeExtractionCalls")) is int and value["nativeExtractionCalls"] == 0
        and type(value.get("energyProbeItems")) is int and value["energyProbeItems"] == 65_536
        and type(value.get("nativeEnergyItemAllowance")) is int and 0 < value["nativeEnergyItemAllowance"] <= 65_536
        and type(value.get("cellCount")) is int and value["cellCount"] == len(value["cells"]),
        "survival_ae_source_not_ready", "One fresh guarded source read must prove supported active/powered EXTRACT readiness for the exact explicit binding; it is not stock, extraction or formation proof.")
    return binding


def _ae_material_identity(item: Any) -> tuple[str, int, bool, str]:
    _require(isinstance(item, dict) and isinstance(item.get("registryName"), str) and bool(item["registryName"])
        and type(item.get("metadata")) is int and 0 <= item["metadata"] <= 32_767
        and type(item.get("count")) is int and item["count"] == 1
        and type(item.get("hasTag")) is bool and isinstance(item.get("nbtIdentity"), str),
        "survival_ae_material_identity_unavailable", "Every native extracted material needs its actual count-one registry/metadata/tag and complete native NBT identity.")
    return item["registryName"], item["metadata"], item["hasTag"], item["nbtIdentity"]


def _ae_exact_count(row: dict[str, Any], key: str) -> int:
    exact, numeric = row.get(key + "Exact"), row.get(key)
    _require(isinstance(exact, str) and re.fullmatch(r"0|[1-9][0-9]{0,18}", exact) is not None
        and type(numeric) is int and 0 <= numeric < _AE_LONG_MAX and int(exact) == numeric,
        "survival_ae_exact_quantity_unavailable", "Backing quantities require matching canonical decimal text and exact unsaturated native longs; rounded, missing or negative counts are refused.")
    return numeric


def _ae_survival_ledger(value: dict[str, Any], declaration: dict[str, Any], binding: dict[str, Any],
        dimension: int, player_id: str, blueprint_slot: int, prior_inventory: Any) -> dict[str, Any]:
    before = _survival_inventory_entries(value.get("playerInventoryBefore"))
    after = _survival_inventory_entries(value.get("playerInventoryAfter"))
    _require(value.get("materialSourceType") == "ae_cells" and before == after
        and all(type(value[key].get("selectedHotbarSlot")) is int
            and value[key]["selectedHotbarSlot"] == blueprint_slot for key in ("playerInventoryBefore", "playerInventoryAfter"))
        and isinstance(prior_inventory, list) and before == sorted(prior_inventory, key=lambda row: row["index"]),
        "survival_ae_player_inventory_changed", "AE shell construction must preserve every actual server slot/full NBT and selection, with the pre-shell player inventory matching the already accounted native placements/moves.")
    continuity: dict[tuple[Any, ...], int] = {}
    ledger, extracted = [], 0
    for index, batch in enumerate(value["batches"]):
        observed = _ae_source_binding(batch, declaration, dimension, player_id)
        _require(observed == binding and batch.get("sourceType") == "ae_cells"
            and batch.get("sourceMode") == declaration["mode"] and batch.get("sourceScope") == "bound_cell_container"
            and all(batch.get(key) is True for key in ("completeBackingIdentity", "externalSourceScopeProven", "accountingComplete", "playerInventoryUnchanged"))
            and all(batch.get(key) is False for key in ("wholeGridProviderScopeProven", "automaticReplayAllowed", "outcomeUnknown"))
            and ("partial" not in batch or batch["partial"] is False)
            and batch.get("failureObservation") is None and "playerDeductions" not in batch,
            "survival_ae_batch_binding_unconfirmed", "Every AE batch must retain the exact fresh actor/grid/anchor/native cell binding and complete external-source accounting; player deductions cannot substitute for AE conservation.")
        rows, committed = batch.get("backingLedger"), batch.get("committedItems")
        _require(isinstance(rows, list) and len(rows) <= 256 and isinstance(committed, list) and len(committed) <= 256
            and type(batch.get("nativeExtractionCalls")) is int and batch["nativeExtractionCalls"] >= 0,
            "survival_ae_backing_ledger_unavailable", "Every native batch requires its complete bounded backing and committed-item ledgers.")
        actual, expected, seen, calls = {}, {}, set(), 0
        for row in rows:
            _require(isinstance(row, dict), "survival_ae_backing_ledger_unavailable", "Every backing row must be a typed native receipt.")
            item = _ae_material_identity(row.get("item"))
            cell, amount, count = row.get("cellIndex"), row.get("actualExtracted"), row.get("verifiedExtractions")
            old, new = _ae_exact_count(row, "backingBefore"), _ae_exact_count(row, "backingAfter")
            key = (cell, *item)
            _require(type(cell) is int and 0 <= cell < len(binding["cells"]) and key not in seen
                and type(amount) is int and 0 < amount <= _AE_LONG_MAX and type(count) is int and 0 < count <= amount
                and row.get("quantityType") == "long"
                and (key not in continuity or continuity[key] == old)
                and (old - new == amount if declaration["mode"] == "finite" else old == new and old > 0),
                "survival_ae_backing_delta_mismatch", "Each exact cell/item row must prove its own native extraction and finite decrement or unchanged creative supply; duplicates and cross-batch quantity cancellation are refused.")
            seen.add(key); continuity[key] = new; calls += count; extracted += amount
            expected[item] = expected.get(item, 0) + amount
            ledger.append({"batchIndex": index, **copy.deepcopy(row)})
        for row in committed:
            _require(isinstance(row, dict), "survival_ae_backing_ledger_unavailable", "Every committed material row must be a typed native receipt.")
            item, amount = _ae_material_identity(row.get("item")), row.get("amount")
            _require(item not in actual and type(amount) is int and 0 < amount <= _AE_LONG_MAX,
                "survival_ae_committed_quantity_unavailable", "Committed items need unique exact full identities and positive native-long amounts.")
            actual[item] = amount
        observed_materials = bool(expected)
        _require(actual == expected and calls == batch["nativeExtractionCalls"]
            and batch.get("materialsObserved") is observed_materials
            and batch.get("bindingValidatedAtExtraction") is observed_materials
            and batch.get("finiteDecrementVerified") is (observed_materials and declaration["mode"] == "finite")
            and batch.get("supplyDidNotDecrease") is (observed_materials and declaration["mode"] == "creative_infinite")
            and (batch["nativeReturn"] == -1 or observed_materials),
            "survival_ae_committed_delta_mismatch", "Every batch's committed full-NBT bag must equal its own verified native extractions and exact mode-specific proof flags. An empty final done batch carries no extraction proof.")
    _require(extracted > 0, "survival_ae_no_extraction_proof", "A completed AE shell requires at least one observed positive native extraction; an entirely empty callback cannot establish either source proof.")
    return {"phase": "survival_blueprint_construct", "sourceType": "ae_cells", "sourceMode": declaration["mode"],
        "sourceScope": "bound_cell_container", "binding": copy.deepcopy(binding), "deductions": [],
        "backingLedger": ledger, "actualExtracted": extracted, "batches": len(value["batches"]),
        "playerInventoryUnchanged": True, "finiteConservationVerified": declaration["mode"] == "finite",
        "creativeSupplyUnchanged": declaration["mode"] == "creative_infinite", "wholeGridProviderScopeProven": False,
        "nativeDoneIsNotFormation": True, "automaticReplayAllowed": False}


def debug_energy_tier_widget(observation: dict[str, Any], screen_id: str) -> dict[str, Any]:
    _require(observation.get("screenId") == screen_id and all(observation.get(flag) is False
        for flag in ("hasMore", "partial", "scanTruncated", "byteLimited")) and isinstance(observation.get("widgets"), list),
        "debug_energy_gui_coverage_unavailable", "The already placed debug energy port needs complete current native GUI control/label coverage.")
    widgets = observation["widgets"]
    labels = [widget for widget in widgets if isinstance(widget, dict) and widget.get("kind") == "label"
        and widget.get("contextOnly") is True and widget.get("enabled") is True and widget.get("valid") is True
        and isinstance(widget.get("parentId"), str) and widget["parentId"] and isinstance(widget.get("text"), str)
        and re.fullmatch(r"(?:电压等级|Voltage Tier)(?:\s*\([^)]*\))?",
            re.sub(r"§[0-9a-fk-or]", "", widget["text"], flags=re.IGNORECASE).strip(), flags=re.IGNORECASE)]
    _require(len(labels) == 1, "debug_energy_tier_field_ambiguous", "The already placed debug energy port requires one public voltage-tier label; no GUI position or field value is guessed.")
    fields = [widget for widget in widgets if isinstance(widget, dict) and widget.get("parentId") == labels[0]["parentId"]
        and widget.get("kind") == "text"
        and widget.get("enabled") is True and widget.get("valid") is True and "set_text" in widget.get("supportedActions", [])]
    _require(len(fields) == 1 and fields[0].get("class") == _MUI_TEXT_FIELD_API
        and isinstance(fields[0].get("widgetId"), str) and fields[0]["widgetId"],
        "debug_energy_tier_field_ambiguous", "The already placed debug energy port needs exactly one supported native text field sharing the voltage-tier label's parent.")
    field = fields[0]
    _require(isinstance(field.get("text"), str) and re.fullmatch(r"(?:[0-9]|1[0-5])", field["text"]) is not None
        and field.get("value") == field["text"], "debug_energy_tier_state_unavailable", "Read one synchronized actual native debug voltage-tier value before configuring it.")
    return field


def _normal_gt_placement_facing(aim: dict[str, Any]) -> str:
    """GTUtility.getSideFromPlayerFacing over the actual native aimed view."""
    _require(isinstance(aim, dict) and all(type(aim.get(key)) in (int, float) and math.isfinite(aim[key])
        for key in ("yaw", "pitch")) and -90 <= aim["pitch"] <= 90,
        "controller_placement_aim_unavailable", "Placement needs the actual finite native yaw and pitch, never a requested stale view.")
    if aim["pitch"] >= 65:
        return "UP"
    if aim["pitch"] <= -65:
        return "DOWN"
    return ("NORTH", "EAST", "SOUTH", "WEST")[math.floor(.5 + 4 * aim["yaw"] / 360) & 3]


def _placement_aim(eye: dict[str, Any], hit: dict[str, Any]) -> dict[str, float]:
    _require(isinstance(eye, dict) and isinstance(hit, dict)
        and all(type(value.get(axis)) in (int, float) and math.isfinite(value[axis])
            for value in (eye, hit) for axis in ("x", "y", "z")),
        "controller_placement_aim_unavailable", "Placement aim must use the actual finite observed eye and selected ray-hit point.")
    dx, dy, dz = (hit[axis] - eye[axis] for axis in ("x", "y", "z"))
    _require(dx * dx + dz * dz > 0, "controller_placement_aim_unavailable", "A vertical ray cannot bind a horizontal controller direction.")
    return {"yaw": math.degrees(math.atan2(dz, dx)) - 90,
        "pitch": -math.degrees(math.atan2(dy, math.sqrt(dx * dx + dz * dz)))}


def _placement_aim_matches(actual: dict[str, Any], expected: dict[str, Any]) -> bool:
    _normal_gt_placement_facing(actual)
    return abs((actual["yaw"] - expected["yaw"] + 180) % 360 - 180) <= .001 \
        and abs(actual["pitch"] - expected["pitch"]) <= .001


def _construction_stop_identity(machine: dict[str, Any], target: dict[str, Any], dimension: int,
    controller_item: dict[str, Any], *, meta_class: str | None = None) -> str:
    """Bind only the actual task-owned controller and typed public state."""
    _require(isinstance(machine, dict) and all(type(machine.get(axis)) is int and machine[axis] == target[axis]
        for axis in ("x", "y", "z")) and type(machine.get("dimension")) is int and machine["dimension"] == dimension
        and type(machine.get("metaTileId")) is int and machine["metaTileId"] == controller_item["metadata"]
        and isinstance(machine.get("metaClass"), str) and bool(machine["metaClass"])
        and (meta_class is None or machine["metaClass"] == meta_class)
        and _same(machine.get("structureDescription", {}).get("controllerStack"), controller_item),
        "construction_stop_owner_changed", "Native stopping requires the exact newly placed controller stack, ID/class, XYZ and dimension.")
    state = machine.get("state")
    _require(isinstance(state, dict) and type(state.get("active")) is bool and type(state.get("allowedToWork")) is bool
        and all(type(state.get(key)) is int and state[key] >= 0 for key in ("progressTicks", "maxProgressTicks")),
        "construction_stop_state_unavailable", "Native stopping requires actual typed active/allowed state and finite current/maximum progress.")
    return machine["metaClass"]


def _construction_stopped_components(machine: dict[str, Any]) -> dict[str, Any]:
    """Prove stopped state and every exposed core from the same complete DTO."""
    state, components = machine["state"], machine.get("components")
    _require(machine.get("processing", {}).get("structureFormed") is True and state["active"] is False
        and state["allowedToWork"] is False and state["progressTicks"] == state["maxProgressTicks"] == 0,
        "construction_stop_unconfirmed", "The same real formed controller must be disabled, inactive and at explicitly zero current/maximum progress.")
    _require(isinstance(components, dict) and components.get("hasMore") is False
        and components.get("softBudgetPartial") is False and type(components.get("total")) is int
        and type(components.get("offset")) is int and components["offset"] == 0
        and type(components.get("nextOffset")) is int and components["nextOffset"] == components["total"]
        and isinstance(components.get("entries"), list) and len(components["entries"]) == components["total"] <= 128
        and isinstance(components.get("roles"), list) and isinstance(components.get("unavailableRoles"), list),
        "construction_stop_component_coverage_incomplete", "Stopped acceptance requires one complete finite public registered-component page, without omitted or partial entries.")
    entries, roles, unavailable = components["entries"], components["roles"], components["unavailableRoles"]
    _require(all(isinstance(entry, dict) and isinstance(entry.get("role"), str) for entry in entries)
        and all(isinstance(role, dict) and isinstance(role.get("role"), str) and type(role.get("count")) is int
            and role["count"] >= 0 for role in roles)
        and all(isinstance(role, dict) and isinstance(role.get("role"), str) for role in unavailable),
        "construction_stop_component_coverage_incomplete", "Stopped registered-component entries and actual role summaries must be typed.")
    counts = {role["role"]: role["count"] for role in roles}
    _require(len(counts) == len(roles) and sum(counts.values()) == len(entries)
        and all(sum(entry["role"] == role for entry in entries) == count for role, count in counts.items())
        and not any(role["role"] == "execution_core" for role in unavailable),
        "construction_stop_component_coverage_incomplete", "Actual public role counts must exactly cover every exposed registered component; unavailable execution-core coverage cannot prove stop.")
    modular = components.get("modularHatches")
    _require(isinstance(modular, dict) and type(modular.get("available")) is bool,
        "construction_stop_modular_coverage_unavailable", "The public modular interface availability must remain explicit in stopped acceptance.")
    modular_coverage = {"available": modular["available"]}
    if modular["available"]:
        _require(type(modular.get("registeredCount")) is int and modular["registeredCount"] == counts.get("modular_hatch")
            and not any(role["role"] == "modular_hatch" for role in unavailable),
            "construction_stop_modular_coverage_unavailable", "An available public module interface must match complete registered modules and role counts.")
        modular_coverage["registeredCount"] = modular["registeredCount"]
    else:
        _require(isinstance(modular.get("reason"), str) and bool(modular["reason"])
            and not counts.get("modular_hatch", 0), "construction_stop_modular_coverage_unavailable",
            "An unavailable module interface needs its explicit public reason and cannot coexist with unproved registered modules.")
        modular_coverage["reason"] = modular["reason"]
    cores = []
    for entry in entries:
        if "executionCore" not in entry:
            _require(entry["role"] != "execution_core", "construction_stop_core_state_unavailable",
                "Every public execution-core entry requires its actual controller-bound working state.")
            continue
        core = entry["executionCore"]
        _require(isinstance(core, dict) and type(core.get("working")) is bool and entry.get("valid") is True
            and isinstance(core.get("mainMachine"), dict) and core["mainMachine"].get("controllerIdentityMatches") is True,
            "construction_stop_core_state_unavailable", "Exposed core working state must be typed, valid and bound to the actual controller.")
        _require(core["working"] is False, "construction_stop_core_working", "Every exposed registered execution core must have stopped in this same current observation.")
        cores.append({key: entry[key] for key in ("role", "x", "y", "z", "dimension") if key in entry})
    return {"confirmed": True, "active": False, "allowedToWork": False, "progressTicks": 0, "maxProgressTicks": 0,
        "componentCoverageComplete": True, "exposedExecutionCores": cores, "modularCoverage": modular_coverage,
        "scope": "Current formed controller and complete exposed registered components in one server observation; hidden buffers and complete specialized rules remain unknown."}


class _ConstructionTask(_RecipeTask):
    def __init__(self, client: BridgeClient, arguments: dict[str, Any], runner: Callable[..., Awaitable[dict[str, Any]]], *, started: float):
        arguments = expand_ports(arguments)
        self.connection_plan = compile_connections(arguments)
        self.connection_task = None
        self.connection_report = None
        self.connection_slots = {row["hotbarSlot"] for row in (self.connection_plan or {}).get("install", [])}
        if self.connection_plan is not None and "placement" in arguments:
            components = arguments["placement"].setdefault("layoutComponents", [])
            components.extend(copy.deepcopy(self.connection_plan["layoutComponents"]))
            _require(len(components) <= 64 and len({c["id"] for c in components}) == len(components),
                "connection_layout_budget", "Combined declared and AE component reservations require unique IDs and at most 64 boxes.")
        super().__init__(client, {"timeout_ms": 60_000, **arguments}, runner, started=started)
        self.timings = {key: 0.0 for key in ("decision", "preparation", "construction", "acceptance", "restoration")}
        self.timing_stage = "decision"
        self.material_moves: list[dict[str, Any]] = []
        self.supports: list[dict[str, Any]] = []
        self.selected_ports: list[dict[str, Any]] = []
        self.position_selection_policy: dict[str, Any] | None = None
        self.position_snapshot: dict[str, Any] | None = None
        self.materials: dict[int, dict[str, Any]] = {}
        self.port_supply_bindings: dict[int, dict[str, Any]] = {}
        self.original_inventory: list[dict[str, Any]] = []
        self.original_selected: int | None = None
        self.controller_slot: int | None = None
        self.empty_slot: int | None = None
        self.capture: dict[str, Any] | None = None
        self._placed_controller_observation: dict[str, Any] | None = None
        self._target_capture_header: dict[str, Any] | None = None
        self.bounds: dict[str, Any] | None = None
        self.hint_capture_refresh: dict[str, Any] | None = None
        self.floor: int | None = None
        self.formation_verified = False
        self.acceptance: dict[str, Any] | None = None
        self.restoration_error: dict[str, Any] | None = None
        self.inventory_restored = False
        self.selected_restored = False
        self.restoration_started = False
        self.inventory_restoration_requested = arguments.get("restoreInventory", False)
        self.inventory_preparation_mode = "preserve" if self.inventory_restoration_requested or not arguments.get("generateMaterials", False) else arguments.get("inventoryPreparation", "minimal")
        self.support_mode = arguments.get("supportMode", "player" if self.inventory_preparation_mode == "preserve" else "command")
        self.construction_order = arguments.get("constructionOrder", "ports_first" if self.actual_survival else "shell_first")
        self.survival_inventory: list[dict[str, Any]] | None = None
        self.tagged_context_inventory: list[dict[str, Any]] | None = None
        self.tagged_context_player_id: str | None = None
        self.tagged_context_items: dict[int, dict[str, Any]] = {}
        self.survival_player_id: str | None = None
        self.survival_material_ledger: list[dict[str, Any]] = []
        self.survival_build_receipt: dict[str, Any] | None = None
        self.survival_shell_completion: dict[str, Any] | None = None
        self.survival_ae_source = arguments.get("survivalBuild", {}).get("materialSource")
        self.survival_ae_binding: dict[str, Any] | None = None
        self.survival_ae_readiness: list[dict[str, Any]] = []
        self.survival_ae_player_inventory: list[dict[str, Any]] | None = None
        self.survival_ae_build_attempted = False
        self.initial_controller_seed_mode = "initialControllerSeeds" in arguments
        self.initial_seed_sources: dict[int, dict[str, Any]] = {}
        self.initial_seed_declarations: dict[tuple[int, int, int], dict[str, Any]] = {}
        self._initial_seed_borrow: dict[str, Any] | None = None
        self._initial_seeds_attempted = False
        self.bootstrap_adjacent = arguments.get("bootstrapAdjacentSeed")
        self.bootstrap_position: dict[str, int] | None = None
        self.bootstrap_up_attempted = False
        self.bootstrap_down_attempted = False
        self.bootstrap_evidence: dict[str, Any] = {"declaration": copy.deepcopy(self.bootstrap_adjacent), "navigation": [],
            "permanent": True, "refunded": False, "publicValidatedBeforePlacement": False, "formationProven": False}
        self.shell_exit_evidence: list[dict[str, Any]] = []
        self.survival_seed_mode = self.actual_survival
        self.survival_seed_plan: list[dict[str, Any]] = []
        self.survival_seed_positions: set[tuple[int, int, int]] = set()
        self.survival_port_candidates: dict[int, list[Any]] | None = None
        self.survival_port_groups: dict[int, dict[str, Any]] | None = None
        self.survival_group_counts: dict[int, int] = {}
        self.survival_capture_positions: set[tuple[int, int, int]] = set()
        self.survival_capture_points: dict[tuple[int, int, int], dict[str, Any]] = {}
        self.survival_position_candidates: dict[tuple[int, int, int], dict[str, Any]] = {}
        self.survival_position_candidate_bytes = 0
        self.survival_auto_seed_count = 0
        self.survival_seed_planning: list[dict[str, Any]] = []
        self._frontier_seed_borrow: dict[str, Any] | None = None
        self.survival_frontier_observations: list[dict[str, Any]] = []
        self.survival_frontier_native_reads = 0
        self._survival_frontier_binding: list[tuple[dict[str, Any], int]] | None = None
        self._planned_placement_attachment: dict[str, Any] | None = None
        self._frontier_plan_handoff: dict[str, Any] | None = None
        self._frontier_current_geometry: dict[str, Any] | None = None
        self._frontier_current_absences: dict[Any, Any] | None = None
        self._frontier_jump_return: dict[str, Any] | None = None
        self.material_supply_mode = "existing_inventory"
        self.already_minimal_verified = False
        self.selection_cleanup: dict[str, Any] | None = None
        self.support_cleanup_error: dict[str, Any] | None = None
        self.retained_inventory_verified = False
        self.creative_mapping: tuple[str, dict[int, dict[str, Any]]] | None = None
        self.ports_preserved_verified = False
        self.approach_selected: int | None = None
        self.energy_configuration: dict[str, Any] | None = None
        self.port_operating_geometry: dict[str, Any] | None = None
        self.port_operating_faces: dict[tuple[int, int, int], list[int]] = {}
        self.declared_supplies_verified = False
        self.compact_inspection = False
        self.combined_inventory = False
        self.volume_inspection: dict[str, Any] | None = None
        self.placement_plan: dict[str, Any] | None = None
        self._workflow_discovery = None
        self._workflow_background_required = False
        self._controller_placement_pose: dict[str, Any] | None = None
        # Custom runners retain their existing contract; only our native runner
        # advertises this private task-scoped capability reuse.
        from .workflows import run_workflow
        self._native_workflow_runner = runner is run_workflow
        if self._native_workflow_runner:
            self.runner = self._run_internal_workflow

    async def _run_internal_workflow(self, client: BridgeClient, arguments: dict[str, Any], lock: anyio.Lock) -> dict[str, Any]:
        from .workflows import run_workflow
        if self._workflow_discovery is None:
            # Internal helpers may be called without construction preflight.
            # Preserve ordinary discovery until the task has verified its cache.
            return await run_workflow(client, arguments, lock)
        report = await run_workflow(client, arguments, lock, _discovery=self._workflow_discovery,
            _require_background=self._workflow_background_required,
            _snapshot_checks=arguments["steps"].snapshot_checks if type(arguments["steps"]) is _ApproachSteps else None)
        # Never reacquire protection after a task segment established it, even
        # when that segment later failed and only declared cleanup remains.
        self._workflow_background_required |= report.get("takeoverEstablished") is True
        return report

    async def snapshot(self) -> dict[str, Any]:
        result = await super().snapshot()
        self.position_snapshot = result
        return result

    def remaining(self) -> int:
        left = int((self.deadline - time.monotonic()) * 1000)
        _require(left > 0, "task_deadline", "The total blueprint task deadline expired; no mutation was replayed.")
        return min(60_000, left)

    def point(self, point: dict[str, Any]) -> dict[str, Any]:
        return {**_xyz(point), "dimension": self.dimension}

    async def blocks(self, positions: list[dict[str, Any]]) -> dict[tuple[int, int, int], dict[str, Any]]:
        result: dict[tuple[int, int, int], dict[str, Any]] = {}
        for offset in range(0, len(positions), 16):
            chunk = positions[offset:offset + 16]
            page = await self.call("world.inspect_many", {"positions": [_xyz(point) for point in chunk], "dimension": self.dimension})
            rows = page.get("results")
            _require(page.get("source") == "integrated_server" and page.get("dimension") == self.dimension
                and isinstance(rows, list) and len(rows) == len(chunk), "server_block_page_unavailable", "Every requested loaded block needs authoritative server coverage.")
            for point, row in zip(chunk, rows):
                data = row.get("data") if isinstance(row, dict) and row.get("ok") is True else None
                _require(isinstance(data, dict) and data.get("source") == "integrated_server"
                    and data.get("dimension") == self.dimension and all(data.get(axis) == point[axis] for axis in ("x", "y", "z")),
                    "server_block_page_unavailable", "A requested block was unloaded, omitted or returned under a different identity.")
                _block_identity(data)
                result[_key(point)] = data
        return result

    async def block(self, point: dict[str, Any]) -> dict[str, Any]:
        return (await self.blocks([point]))[_key(point)]

    def require_clear_volume_block(self, point: dict[str, Any], block: dict[str, Any]) -> bool:
        if _key(point) in self.survival_seed_positions:
            record = next(record for record in self.supports if record.get("permanentStructureSeed") is True
                and _key(record["position"]) == _key(point))
            _require(record.get("publicGroupMatched") is True and _block_identity(block) == record["block"]
                and "tileClass" not in block, "survival_structure_seed_changed",
                "Only a confirmed task-owned native casing seed matching its complete public capture group may remain nonair.")
            return False
        _require(block["registryName"] == "minecraft:air", "observed_volume_not_clear",
            "Every other captured position must be actual server air; foreign blocks are never cleared or adopted.")
        return True

    def compact_block_prefix(self, page: dict[str, Any], pending: list[dict[str, Any]]) -> tuple[int, list[Any]]:
        rows, count = page.get("results"), page.get("processedCount")
        _require(page.get("source") == "integrated_server" and page.get("dimension") == self.dimension
            and type(page.get("worldTick")) is int and page.get("nonAtomic") is True
            and type(page.get("requestedCount")) is int and page["requestedCount"] == len(pending)
            and type(count) is int and 1 <= count <= len(pending)
            and type(page.get("nextOffset")) is int and page["nextOffset"] == count
            and page.get("complete") is (count == len(pending)) and page.get("partial") is (count != len(pending))
            and all(type(page.get(flag)) is bool for flag in ("byteLimited", "budgetLimited"))
            and (count == len(pending) or page["byteLimited"] or page["budgetLimited"])
            and isinstance(rows, list) and len(rows) == count,
            "server_block_page_unavailable", "Compact reads need exact positive-progress request prefixes; omitted points never prove air.")
        observed = []
        for index, (point, row) in enumerate(zip(pending[:count], rows)):
            _require(isinstance(row, dict) and type(row.get("requestIndex")) is int and row["requestIndex"] == index
                and type(row.get("ok")) is bool, "server_block_page_unavailable", "Compact prefix results must retain exact request order.")
            if row["ok"] is False:
                _require(isinstance(row.get("error"), dict), "server_block_page_unavailable", "An unavailable compact point needs an explicit error.")
                observed.append(None)
                continue
            data = row.get("data")
            _require(isinstance(data, dict) and data.get("loaded") is True and data.get("source") == "integrated_server"
                and data.get("dimension") == self.dimension
                and all(type(data.get(axis)) is int and data[axis] == point[axis] for axis in ("x", "y", "z")),
                "server_block_page_unavailable", "Each successful compact point needs its exact loaded authoritative server identity.")
            _block_identity(data)
            observed.append(data)
        return count, observed

    async def verify_clear_volume(self, positions: list[dict[str, Any]]) -> None:
        """Read every captured cell; compact reads resume only verified prefixes."""
        self.volume_inspection = {"mode": "compact_128" if self.compact_inspection else "full_16",
            "requestedPositions": len(positions), "requests": 0, "serverVerifiedAirPositions": 0, "nonAtomic": True}
        if not self.compact_inspection:
            before = await self.blocks(positions)
            self.volume_inspection["requests"] = (len(positions) + 15) // 16
            air = sum(self.require_clear_volume_block(point, before[_key(point)]) for point in positions)
            self.volume_inspection["serverVerifiedAirPositions"] = air
            if self.survival_seed_positions:
                self.volume_inspection["serverVerifiedOwnedSeedPositions"] = len(positions) - air
            return
        for offset in range(0, len(positions), 128):
            pending = positions[offset:offset + 128]
            while pending:
                page = await self.call("world.inspect_many", {"positions": [_xyz(point) for point in pending],
                    "dimension": self.dimension, "compact": True})
                self.volume_inspection["requests"] += 1
                count, observed = self.compact_block_prefix(page, pending)
                for point, data in zip(pending[:count], observed):
                    _require(isinstance(data, dict), "server_block_page_unavailable", "Every captured cell must be explicitly loaded; point errors never prove all-clear.")
                    if self.require_clear_volume_block(point, data):
                        self.volume_inspection["serverVerifiedAirPositions"] += 1
                    else:
                        self.volume_inspection["serverVerifiedOwnedSeedPositions"] = self.volume_inspection.get("serverVerifiedOwnedSeedPositions", 0) + 1
                pending = pending[count:]
        _require(self.volume_inspection["serverVerifiedAirPositions"] + self.volume_inspection.get("serverVerifiedOwnedSeedPositions", 0) == len(positions),
            "server_block_page_unavailable", "Compact continuation must verify every captured coordinate before native construction.")

    def workflow_block(self, report: dict[str, Any], identifier: str, point: dict[str, Any]) -> dict[str, Any]:
        """Reuse the action's authoritative wait observation, never a live-state cache."""
        rows = [step for step in report.get("steps", []) if step.get("id") == identifier]
        observation = rows[0].get("observation", {}) if len(rows) == 1 else {}
        values = observation.get("values", {})
        if isinstance(values, dict) and "results" in values:
            result = values["results"]
            _require(isinstance(result, list) and len(result) == 1 and isinstance(result[0], dict)
                and result[0].get("ok") is True and isinstance(result[0].get("data"), dict),
                "server_block_page_unavailable", "A combined native wait must retain its one complete authoritative owner point.")
            values = result[0]["data"]
        _require(not observation.get("omitted") and not observation.get("fieldsOmitted")
            and isinstance(values, dict) and values.get("source") == "integrated_server"
            and values.get("dimension") == self.dimension and all(values.get(axis) == point[axis] for axis in ("x", "y", "z")),
            "server_block_page_unavailable", "The completed native wait must retain this exact authoritative server block observation.")
        _block_identity(values)
        return values

    async def verify_declared_supplies(self, actual: dict[str, Any], declared_slots: list[int]) -> None:
        """Authorize finite grants from explicit goals, before any inventory write."""
        _require(self.inventory_preparation_mode in ("minimal", "clear"), "declared_supply_preservation_conflict",
            "Task-declared supplies require minimal/clear preparation and cannot replace a preserved inventory.")
        supplies = self.args.get("declaredSupplies")
        _require(isinstance(supplies, list), "declared_supplies_required",
            "Explicit material generation requires exact finite declaredSupplies.")
        slots = inventory_slots(actual, "")
        _require(len(slots) == 41 and {slot["index"] for slot in slots} == set(range(41))
            and "cursorItem" in actual and actual["cursorItem"] is None,
            "declared_supply_inventory_unavailable", "Task-declared supply needs complete actual indices 0..40 and an explicitly empty real cursor before any write.")
        indices = [supply["hotbarSlot"] for supply in supplies]
        _require(len(indices) == len(set(indices)),
            "declared_supply_ambiguous", "Each finite task supply needs a distinct declared hotbar binding.")
        controllers = [supply for supply in supplies if supply["item"] == self.args["controllerItem"]]
        _require(len(controllers) == 1 and controllers[0]["count"] == 1,
            "declared_controller_supply_unavailable", "Declare exactly one controller stack with count one.")
        self.controller_slot = controllers[0]["hotbarSlot"]
        _require(self.controller_slot not in declared_slots and set(indices) == set([*declared_slots, self.controller_slot]),
            "declared_supply_scope_mismatch", "Declared supplies must exactly cover blueprint, requested ports, optional player support and one separate controller binding.")
        self.materials = {supply["hotbarSlot"]: {"item": dict(supply["item"]), "count": supply["count"]} for supply in supplies}
        _require(all(self.materials[port["hotbarSlot"]]["count"] == 1 for port in self.args["requestedPorts"]),
            "declared_port_supply_quantity", "Each declared port supply is one finite exact stack.")
        if "supportHotbarSlot" in self.args:
            support = self.materials[self.args["supportHotbarSlot"]]
            _require(support["item"] == self.args["supportItem"] and support["count"] <= 3,
                "declared_support_supply_unavailable", "The explicit player support identity/count must match the bounded declaration.")
        purposes = {self.args["blueprintHotbarSlot"]: "blueprint", self.controller_slot: "controller"}
        purposes.update({port["hotbarSlot"]: "port:" + str(index) for index, port in enumerate(self.args["requestedPorts"])})
        if "supportHotbarSlot" in self.args:
            purposes[self.args["supportHotbarSlot"]] = "support"
        grouped = self.group_build_supplies([(purposes[supply["hotbarSlot"]], supply["hotbarSlot"], supply["count"])
            for supply in supplies], "declared_supply_ambiguous")
        for supply in grouped:
            item = supply["item"]
            public = await self.call("registry.item", item)
            _require(public.get("registryName") == item["registryName"] and type(public.get("metadata")) is int
                and public["metadata"] == item["metadata"]
                and type(public.get("stackLimit")) is int and 1 <= supply["count"] <= min(64, public["stackLimit"]),
                "declared_supply_registry_unavailable", "Every finite declared supply requires exact current public registry identity and a sufficient real stack limit.")
        self.declared_supplies_verified = True

    def group_build_supplies(self, supplies: list[tuple[str, int, int]], reason: str) -> list[dict[str, Any]]:
        """Group only separately declared same-role ports, retaining every goal."""
        grouped: dict[tuple[str, int], dict[str, Any]] = {}
        _require(len(supplies) <= 8, reason, "The bounded task supports at most eight declared supply bindings.")
        for purpose, slot, count in supplies:
            item = self.materials[slot]["item"]
            identity = (item["registryName"], item["metadata"])
            group = grouped.setdefault(identity, {"item": item, "sourceSlot": slot, "count": 0, "bindings": []})
            group["count"] += count
            group["bindings"].append({"purpose": purpose, "declaredHotbarSlot": slot, "count": count})
        for group in grouped.values():
            if len(group["bindings"]) == 1:
                continue
            bindings = group["bindings"]
            _require(all(binding["purpose"].startswith("port:") for binding in bindings), reason,
                "Controller, blueprint and support supplies must retain distinct exact identities.")
            ports = [self.args["requestedPorts"][int(binding["purpose"][5:])] for binding in bindings]
            _require(len({port["role"] for port in ports}) == 1 and all("id" in port and "groupSelector" in port for port in ports)
                and all(binding["count"] == 1 for binding in bindings), reason,
                "Only same-role ports with separate IDs, exact group selectors and one finite unit per goal may share a generated source.")
        return list(grouped.values())

    def bind_port_supply(self, binding: dict[str, Any], source_slot: int, source_count: int,
        allocation_index: int, mode: str) -> None:
        if not binding["purpose"].startswith("port:"):
            return
        index = int(binding["purpose"][5:])
        self.port_supply_bindings[index] = {"declaredHotbarSlot": binding["declaredHotbarSlot"],
            "sourceHotbarSlot": source_slot, "sourceStackCount": source_count,
            "allocationIndex": allocation_index, "allocatedCount": binding["count"], "mode": mode,
            "scope": "One separately bound native placement per declared port; creative placement preserves the verified source stack."}

    async def preflight(self) -> None:
        self.phase = "preflight"
        _require(self.args.get("survivalBuild", {}).get("finishAirTail") is not True or self.survival_ae_source is None,
            "survival_shell_tail_source_unsupported", "Air-tail completion requires finite player materials; select the source before construction.")
        _require(self.args.get("inventoryPreparation") != "clear" or self.args.get("generateMaterials", False) is True,
            "inventory_clear_not_authorized", "Clear preparation requires explicit creative material generation; existing materials are preserved.")
        _require(not self.actual_survival or self.args.get("generateMaterials", False) is False,
            "survival_material_generation_forbidden", "Actual survival cannot generate or clear inventory.")
        _require(not self.actual_survival or self.construction_order == "ports_first" and self.support_mode == "player",
            "survival_construction_order_unsupported", "Actual survival supports literal-air ports_first and player materials; creative shell replacement and command supports are unavailable.")
        _require(not self.actual_survival or "placement" not in self.args or
            self.args["placement"].get("geometryMode") == "native_visits" and self.bootstrap_adjacent is not None
            and self.args.get("controllerFacing") in _FRONT and self.args.get("movementStrategy", "walking") == "walking"
            and not self.survival_flight,
            "survival_placement_calibration_unsupported", "Survival placement requires complete native geometry and the existing grounded facing-bound adjacent-seed bootstrap; other calibration/posture modes remain unavailable.")
        _require("declaredSupplies" not in self.args or self.args.get("generateMaterials", False) is True,
            "declared_supply_generation_not_authorized", "Task-declared supply requires explicit generateMaterials=true.")
        _require(not self.initial_controller_seed_mode or self.actual_survival,
            "initial_seeds_survival_required", "Explicit per-layer initial seeds require actualSurvival=true.")
        _require(not self.initial_controller_seed_mode or not any(key in self.args for key in ("supportItem", "supportHotbarSlot")),
            "initial_seeds_support_conflict", "Per-layer declarations cannot mix with the legacy same-material support pair.")
        health = await self.call("bridge.health", {})
        _require(health.get("protocolVersion") == 1 and health.get("sessionId") == self.args["expectedSessionId"]
            and health.get("profile") == "developer", "local_developer_context_required", "A matching local integrated developer bridge is required.")
        self.capabilities = health.get("capabilities")
        self.approach_health = health
        self.validate_survival_posture_declaration()
        _require(not self.survival_flight or type(health.get("survivalFlightPostureVersion")) is int
            and health["survivalFlightPostureVersion"] == 1, "survival_flight_native_unavailable",
            "Explicit airborne survival requires the advertised native dual-actor posture guard.")
        if self.actual_survival:
            _require("structure.survival_build" in self.capabilities if isinstance(self.capabilities, list) else
                isinstance(self.capabilities, dict) and self.capabilities.get("structure.survival_build") is True,
                "survival_native_build_unavailable", "The real bounded public survival construction operation must be advertised before any write.")
            if self.survival_ae_source is not None:
                from .workflows import _context_capabilities
                reads, _ = _context_capabilities(health)
                _require(("ae.material_source" in self.capabilities if isinstance(self.capabilities, list) else
                    isinstance(self.capabilities, dict) and self.capabilities.get("ae.material_source") is True)
                    and "ae.material_source" in reads, "survival_ae_source_read_unavailable",
                    "AE shell construction requires an advertised fresh guarded source read before any placement; no player fallback is allowed.")
            _require(self.native_approach_available("use") and self.native_approach_available("place_block"),
                "survival_grounded_approach_unavailable", "Real survival placement and use require complete grounded native planning.")
        if self.bootstrap_adjacent is not None:
            _require(("player.navigate" in self.capabilities if isinstance(self.capabilities, list) else
                isinstance(self.capabilities, dict) and self.capabilities.get("player.navigate") is True)
                and health.get("nativeNavigation") is True,
                "native_navigation_unavailable", "Explicit bootstrap requires advertised same-strategy native navigation before any seed placement.")
            _require(type(health.get("approachBuildEnvelopeVersion")) is int and health["approachBuildEnvelopeVersion"] == 1
                and type(health.get("survivalBuildEnvelopeVersion")) is int and health["survivalBuildEnvelopeVersion"] == 1,
                "native_build_body_exclusion_unavailable", "Explicit bootstrap needs advertised future-body approach/callback exclusion before any placement.")
            _require(self.actual_survival and self.initial_controller_seed_mode
                and abs(self.bootstrap_adjacent["offsetX"]) + abs(self.bootstrap_adjacent["offsetZ"]) == 1
                and self.args.get("movementStrategy", "walking") in ("walking", "ability_flight"),
                "bootstrap_declaration_unsupported", "One explicit cardinal permanent seed and walking/real ability_flight must be declared.")
            _require(self.args.get("movementStrategy", "walking") == "ability_flight"
                or type(health.get("nativeWalkingStepVersion")) is int and health["nativeWalkingStepVersion"] == 1,
                "native_step_unavailable", "Pure walking bootstrap needs the advertised explicit bounded native step contract.")
            nested = self.bootstrap_adjacent.get("exitPosition")
            _require(nested is None or "shellExitPosition" not in self.args or nested == self.args["shellExitPosition"],
                "bootstrap_exit_conflict", "Two explicitly supplied shell exits must name the same grounded feet cell.")
        self.compact_inspection = health.get("worldInspectManyCompact") is True
        _require(self.args.get("survivalBuild", {}).get("finishAirTail") is not True or self.compact_inspection,
            "survival_shell_tail_inspection_unsupported", "Air-tail completion requires explicit loaded-cell coverage from native compact inspection before construction.")
        from .workflows import _inspect_many_player_inventory_available
        self.combined_inventory = _inspect_many_player_inventory_available(health)
        _require(isinstance(self.capabilities, list) and all(isinstance(value, str) for value in self.capabilities)
            or isinstance(self.capabilities, dict) and all(type(value) is bool for value in self.capabilities.values()),
            "capability_discovery_unavailable", "Typed public operation capabilities are required before any action.")
        for operation in _REQUIRED:
            _require(operation in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get(operation) is True,
                "required_capability_unavailable", "Unavailable before any action: missing " + operation + ".")
        if self.inventory_preparation_mode in ("minimal", "clear") or self.support_mode != "player":
            _require("chat.send" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("chat.send") is True,
                "required_capability_unavailable", "Developer command preparation requires chat.send before any action.")
        _require(all(health.get(flag) is True for flag in ("interactionSneaking", "interactionBlockTargeting",
            "interactionViewBinding", "interactionAttackViewBinding")) and isinstance(health.get("guiStructureRecheck"), str),
            "native_public_controls_unavailable", "Native placement, scoped blueprint use, attack binding and public structure recheck are required before any action.")
        if self._native_workflow_runner:
            from .workflows import _workflow_discovery
            self._workflow_discovery = _workflow_discovery(self.tracked_client, self.credentials, health)
        inventory = None
        if self._workflow_discovery is not None and "inventory.inspect" in self._workflow_discovery.reads:
            inventory, snapshot = await self.read_with_snapshot("inventory.inspect", require_background=False)
            self.position_snapshot = snapshot
        else:
            snapshot = await self.snapshot()
        _require(snapshot.get("world", {}).get("dimension") == self.dimension, "dimension_mismatch", "The exact current dimension must match all declared targets.")
        self.require_flight(snapshot)
        self.require_survival_server_control(snapshot)
        roles = [port["role"] for port in self.args["requestedPorts"]]
        _require("data_access" not in roles or health.get("machineDataAccessRoleVersion") == 1,
            "data_access_role_unsupported", "Data-access port goals require the public registered-role reader before building.")
        _require("energyTier" not in self.args or roles.count("energy") == 1, "energy_tier_port_required",
            "An explicit energyTier requires exactly one declared energy port before any placement.")
        identifiers = [port["id"] for port in self.args["requestedPorts"] if "id" in port]
        _require(len(identifiers) == len(set(identifiers)), "declaration_overlap", "Explicit port declaration IDs must be unique.")
        _require(all(roles.count(port["role"]) == 1 or "id" in port and "groupSelector" in port
            for port in self.args["requestedPorts"]), "declaration_overlap",
            "Repeated roles require separate explicit IDs and exact public group selectors.")
        declared_slots = construction_slots(self.args)
        inventory = await self.call("inventory.inspect", {}) if inventory is None else inventory
        slots = inventory_slots(inventory, "")
        indexed = {slot["index"]: slot for slot in slots}
        _require(set(range(36)).issubset(indexed) and inventory.get("cursorItem") is None,
            "player_inventory_unavailable", "Complete player indices 0..35 and an empty real cursor are required.")
        selected = inventory.get("selectedHotbarSlot")
        _require(type(selected) is int and 0 <= selected <= 8, "selected_hotbar_unavailable", "The actual selected hotbar index is required for the inventory baseline.")
        self.original_selected = selected
        self.original_inventory = [{"index": slot["index"], "item": slot["item"]} for slot in slots]
        if self.actual_survival:
            _require(len(slots) == 41 and {slot["index"] for slot in slots} == set(range(41)),
                "survival_inventory_coverage_unavailable", "Finite survival accounting needs all actual 41 inventory entries.")
            self.survival_inventory = sorted(copy.deepcopy(self.original_inventory), key=lambda slot: slot["index"])
            self.survival_player_id = inventory.get("playerId")
            _require(isinstance(self.survival_player_id, str) and bool(self.survival_player_id),
                "survival_player_identity_unavailable", "Finite native consumption must bind the exact actual player UUID.")
            _require(snapshot["serverControl"]["playerId"] == self.survival_player_id,
                "survival_player_identity_mismatch", "Server control and actual inventory must belong to the same real player UUID.")
        if self.args.get("generateMaterials", False):
            await self.verify_declared_supplies(inventory, declared_slots)
        else:
            blueprint_slot = self.args["blueprintHotbarSlot"]
            # Consumed materials remain subject to their original untagged
            # source gates before resolving any retained full-NBT context.
            for index in declared_slots:
                item = indexed[index]["item"]
                if index != blueprint_slot and isinstance(item, dict) and item.get("hasTag") is True:
                    _identity(item)
            if self.initial_controller_seed_mode:
                for seed in [*self.args["initialControllerSeeds"], *([self.bootstrap_adjacent] if self.bootstrap_adjacent is not None else [])]:
                    item = indexed[seed["playerInventoryIndex"]]["item"]
                    _require(not isinstance(item, dict) or item.get("hasTag") is not True,
                        "initial_seed_source_changed", "Every declared source must be the exact positive untagged native stack.")
            if any(isinstance(row["item"], dict) and row["item"].get("hasTag") is True for row in slots):
                full = await self.read_tagged_context_inventory(require_background=False)
                public = [{"index": row["index"], "item": None if row["item"] is None else
                    {key: row["item"].get(key) for key in ("registryName", "metadata", "count", "hasTag")}}
                    for row in sorted(slots, key=lambda row: row["index"])]
                observed = [{"index": row["index"], "item": None if row["item"] is None else
                    {key: row["item"][key] for key in ("registryName", "metadata", "count", "hasTag")}} for row in full]
                _require(public == observed, "tagged_context_initial_inventory_changed",
                    "The initial public inventory must agree with both complete actual full-NBT inventories before any action.")
                self.tagged_context_inventory = copy.deepcopy(full)
                self.tagged_context_items = {row["index"]: copy.deepcopy(row["item"]) for row in full
                    if row["item"] is not None and row["item"]["hasTag"] is True}
                if self.actual_survival:
                    self.survival_inventory = [{"index": row["index"], "item": None if row["item"] is None else
                        {**copy.deepcopy(row["item"]), "nbtIdentity": full[row["index"]]["item"]["nbtIdentity"]}}
                        for row in sorted(self.original_inventory, key=lambda row: row["index"])]
            for index in declared_slots:
                item = indexed[index]["item"]
                context = self.tagged_context_inventory[index]["item"] if index == blueprint_slot and isinstance(item, dict) and item.get("hasTag") is True \
                    and self.tagged_context_inventory is not None else None
                identity = {key: context[key] for key in ("registryName", "metadata")} if context is not None else _identity(item)
                count = _quantity(item.get("count"))
                _require(count > 0, "declared_material_empty", "Each declared native source must contain an exact real stack.")
                self.materials[index] = {"item": identity, "count": count}
                if context is not None:
                    self.materials[index]["contextIdentity"] = {key: value for key, value in context.items() if key != "count"}
        verify_group_materials(self.args, self.materials, self.actual_survival)
        if "supportItem" in self.args:
            _require(self.materials[self.args["supportHotbarSlot"]]["item"] == self.args["supportItem"],
                "support_identity_mismatch", "The declared support identity must match the actual hotbar stack.")
        for port in self.args["requestedPorts"]:
            item = self.materials[port["hotbarSlot"]]["item"]
            if port["role"] == "item_output":
                _require(item == port["ordinaryOutputItem"], "ordinary_output_identity_mismatch", "Item output requires the caller's exact observed ordinary-output identity.")
                public = await self.call("registry.item", item)
                lines = public.get("tooltip")
                _require(public.get("registryName") == item["registryName"] and public.get("metadata") == item["metadata"]
                    and isinstance(lines, list) and all(isinstance(line, str) for line in lines)
                    and isinstance(public.get("displayName"), str), "ordinary_output_tooltip_unavailable", "The exact ordinary output needs actual public display name/tooltip evidence.")
                text = "\n".join([public["displayName"], *lines])
                _require(re.search(r"(?i)(?<![a-z0-9])ME(?![a-z0-9])", text) is None,
                    "me_output_unsupported", "The actual public item text explicitly identifies ME output; no ordinary-output substitution is allowed.")
        if not self.declared_supplies_verified:
            sources = [slot for slot in slots if _same(slot["item"], self.args["controllerItem"])]
            _require(len(sources) == 1 and 0 <= sources[0]["index"] <= 35 and _quantity(sources[0]["item"].get("count")) > 0,
                "controller_source_ambiguous", "One exact untagged actual controller stack is required in player inventory.")
            self.controller_slot = sources[0]["index"]
            self.materials[self.controller_slot] = {"item": self.args["controllerItem"], "count": _quantity(sources[0]["item"]["count"])}
        _require(self.controller_slot not in declared_slots, "controller_source_overlap", "The controller source must differ from blueprint/port/support stacks.")
        if self.connection_plan is not None:
            protected = {*declared_slots, self.controller_slot,
                *(row["playerInventoryIndex"] for row in self.args.get("initialControllerSeeds", []))}
            if "bootstrapAdjacentSeed" in self.args:
                protected.add(self.args["bootstrapAdjacentSeed"]["playerInventoryIndex"])
            _require(not protected & self.connection_slots, "connection_material_overlap",
                "AE installation sources must be separate from every construction source.")
            from .ae_tasks import AeTask
            from .application.control import invoke_control
            self.connection_task = AeTask(self.tracked_client, {**{k: self.args[k] for k in
                ("expectedSessionId", "expectedWorld", "dimension")}, "targets": self.connection_plan["install"],
                "strategy": self.args.get("movementStrategy", "walking" if self.actual_survival else "ability_flight"),
                "timeout_ms": self.remaining()}, invoke_control)
            self.connection_task.deadline = self.deadline
            self.connection_report = {"status": "preflight", "installed": [], "configured": [], "replayAllowed": False}
            await self.connection_task.initialize()
            _require(not self.connection_plan["networks"] or self.connection_task.health.get("aeWorldNetworkVersion") == 1,
                "connection_network_unsupported", "Network assertions require current public grid evidence support before building.")
            if self.connection_plan["install"]:
                self.connection_report["materialPreflight"] = await self.connection_task.installation_materials()
        if self.initial_controller_seed_mode:
            self.verify_initial_seed_sources(inventory, declared_slots)
        if "placement" in self.args:
            placement_arguments = {**self.args["placement"], "anchor": self.args["controller"],
                **{key: self.args[key] for key in ("expectedSessionId", "expectedWorld", "dimension")}, "timeout_ms": self.remaining()}
            self.placement_plan = await run_placement_preflight(self.tracked_client, placement_arguments,
                call=self.call, _discovery=self._workflow_discovery)
            _require(self.placement_plan.get("spaceVerified") is True, self.placement_plan.get("reason", "placement_unavailable"),
                "Explicit placement planning could not establish a complete current clear geometry envelope.")
            geometry = self.placement_plan["geometry"]
            source = geometry["sourceIdentity"]
            _require(source["registryName"] == self.args["controllerItem"]["registryName"]
                and source.get("metaTileId", source["metadata"]) == self.args["controllerItem"]["metadata"],
                "placement_source_controller_mismatch", "The planned geometry source must be the exact declared controller item/meta-tile identity.")
            trigger = geometry["capturedTrigger"]
            material = self.materials[self.args["blueprintHotbarSlot"]]
            _require(trigger is None or trigger == {**material["item"], "count": material["count"],
                "channelData": material.get("contextIdentity", {}).get("nbtIdentity", "")},
                "placement_source_trigger_mismatch", "Source calibration must use the exact declared native blueprint identity/count/channel data before any action.")
            self.args = {**self.args, "controller": self.placement_plan["proposedController"]}
        column = [{**self.args["controller"], "y": y} for y in range(self.args["controller"]["y"] - 1, -1, -1)]
        observed = await self.blocks([self.args["controller"], *column])
        target = observed[_key(self.args["controller"])]
        _require(target["registryName"] == "minecraft:air", "controller_target_not_air", "This bounded task places a fresh controller only at observed server air.")
        floor = next((point["y"] for point in column if observed[_key(point)]["registryName"] != "minecraft:air"), None)
        _require(floor is not None, "floor_unavailable", "A bounded read of the real controller column must find an actual floor.")
        self.floor = floor
        attached = bool(await self.attachments(self.args["controller"])) if self.actual_survival else True
        if self.initial_controller_seed_mode:
            plan = [{**self.args["controller"], "y": y} for y in range(self.floor + 1, self.args["controller"]["y"])]
            offsets = [row["offsetY"] for row in self.args["initialControllerSeeds"]]
            _require(1 <= len(plan) <= 3 and len(offsets) == len(set(offsets))
                and set(offsets) == {point["y"] - self.args["controller"]["y"] for point in plan},
                "initial_seed_column_incomplete", "Declarations must exactly cover every unique observed floor+1 to controller-1 layer, at most three.")
            _require(all(1 <= point["y"] <= 250 and _block_identity(observed[_key(point)]) == {"registryName": "minecraft:air", "metadata": 0}
                and "tileClass" not in observed[_key(point)] for point in plan),
                "survival_structure_seed_target_changed", "Every declared column layer must be the originally observed loaded literal air without a tile.")
            self.survival_seed_plan = plan
            rows = {row["offsetY"]: row for row in self.args["initialControllerSeeds"]}
            self.initial_seed_declarations = {_key(point): copy.deepcopy(rows[point["y"] - self.args["controller"]["y"]]) for point in plan}
            if self.bootstrap_adjacent is not None:
                _require(len(plan) == 2 and self.args["controller"]["y"] - self.floor == 3,
                    "bootstrap_height_unsupported", "This bounded bootstrap covers exactly two initial column layers and one +1/-1 step.")
                declaration = self.bootstrap_adjacent
                adjacent = {axis: self.args["controller"][axis] + declaration["offset" + axis.upper()] for axis in ("x", "y", "z")}
                _require(adjacent["y"] == self.floor + 1 and abs(declaration["offsetX"]) + abs(declaration["offsetZ"]) == 1,
                    "bootstrap_position_unsupported", "The single adjacent seed must be cardinal and floor+1.")
                block = await self.block(adjacent)
                _require(_block_identity(block) == {"registryName": "minecraft:air", "metadata": 0} and "tileClass" not in block,
                    "bootstrap_target_not_air", "The caller-declared permanent adjacent seed needs fresh loaded literal server air.")
                self.bootstrap_position = adjacent
                self.initial_seed_declarations[_key(adjacent)] = copy.deepcopy(declaration)
                self.survival_seed_plan = [plan[0], adjacent, plan[1]]
                self.bootstrap_evidence.update(position=dict(adjacent), originalTerrainFloor=self.floor)
                declared_exit = self.args.get("shellExitPosition", declaration.get("exitPosition"))
                _require(declared_exit is None or declared_exit["y"] == self.floor + 1,
                    "bootstrap_exit_height_unsupported", "A declared bootstrap shell exit must use the original ground feetY.")
        elif self.survival_seed_mode and "supportItem" in self.args and not attached:
            self.survival_seed_plan = [{**self.args["controller"], "y": y}
                for y in range(self.floor + 1, self.args["controller"]["y"])]
            _require(1 <= len(self.survival_seed_plan) <= 3
                and self.materials[self.args["supportHotbarSlot"]]["count"] >= len(self.survival_seed_plan),
                "survival_structure_seed_unavailable", "Explicit permanent casing seeds require one through three real column materials above the original terrain floor.")
            _require(all(observed[_key(point)]["registryName"] == "minecraft:air" for point in self.survival_seed_plan),
                "survival_structure_seed_target_changed", "Only the previously observed literal-air controller column may receive declared native casing seeds.")
        _require(not self.actual_survival or attached or bool(self.survival_seed_plan),
            "survival_controller_support_unavailable", "Actual survival currently needs an existing real adjacent controller support; temporary support drop recovery is unavailable.")
        if self.bootstrap_adjacent is not None and self.args.get("movementStrategy", "walking") == "walking":
            _require(self.args.get("controllerFacing") in _FRONT
                and self.args["controllerItem"]["registryName"] == "gregtech:gt.blockmachines"
                and type((self.approach_health or {}).get("placementFacingVersion")) is int
                and self.approach_health["placementFacingVersion"] == 1,
                "controller_placement_facing_unavailable", "Grounded bootstrap needs an explicit GT front and the native facing guard before any material movement or block placement.")
            self.controller_pose_constraint()

        if self.survival_ae_source is not None:
            await self.read_survival_ae_source("survival_ae_source_preflight", [])

    def verify_initial_seed_sources(self, inventory: dict[str, Any], declared_slots: list[int]) -> None:
        rows = _initial_seed_inventory_entries(inventory)
        declarations = [*self.args["initialControllerSeeds"], *([self.bootstrap_adjacent] if self.bootstrap_adjacent is not None else [])]
        _require(isinstance(declarations, list) and 1 <= len(declarations) <= 4, "initial_seed_declaration_unavailable", "One through three explicit layers are required.")
        for declaration in declarations:
            source, offset = declaration.get("playerInventoryIndex"), declaration.get("offsetY")
            _require(type(source) is int and 0 <= source <= 35 and source not in declared_slots and source != self.controller_slot
                and type(offset) is int and -3 <= offset <= -1, "initial_seed_source_overlap",
                "Each layer needs a legal offset and real source separate from blueprint, ports and controller.")
            item = rows[source]["item"]
            _require(_same(item, declaration["item"]) and item["nbtIdentity"] == "", "initial_seed_source_changed",
                "Every declared source must be the exact positive untagged native stack.")
            binding = self.initial_seed_sources.setdefault(source, {"item": dict(declaration["item"]), "sourceIndex": source,
                "remainingCount": item["count"], "requiredCount": 0})
            _require(binding["item"] == declaration["item"], "initial_seed_source_ambiguous", "One actual source cannot declare two different identities.")
            binding["requiredCount"] += 1
        _require(all(binding["remainingCount"] >= binding["requiredCount"] for binding in self.initial_seed_sources.values()),
            "initial_seed_material_insufficient", "Every repeated source must cover its complete finite per-layer allocation before writes.")

    async def initial_seed_inventory(self, inventory: dict[str, Any] | None = None, *, check_baseline: bool = True) -> list[dict[str, Any]]:
        value = await self.call("inventory.inspect", {}) if inventory is None else inventory
        rows = _initial_seed_inventory_entries(value)
        _require(value.get("playerId") == self.survival_player_id, "initial_seed_actor_changed", "Initial materials must remain bound to the same real player.")
        if check_baseline:
            baseline = {"slots": self.survival_inventory, "totalSlots": 41, "truncated": False, "cursorItem": None}
            expected = _initial_seed_inventory_entries(baseline)
            # Native shell receipts upgrade the baseline to complete server NBT.
            # inventory.inspect still omits full unrelated tagged NBT: absence
            # is an explicit unknown observation, never a claimed identity or
            # evidence of drift. Every provided NBT identity still must agree.
            for actual, previous in zip(rows, expected):
                left, right = actual["item"], previous["item"]
                if left is not None and right is not None and left["hasTag"] is True and right["hasTag"] is True and left["nbtIdentity"] is None:
                    right["nbtIdentity"] = None
            _require(rows == expected, "initial_seed_inventory_changed",
                "Every observable actual slot must agree with only this task's recorded moves and native deductions; omitted unrelated tagged NBT remains unknown.")
        return rows

    async def prepare_initial_seed_source(self, declaration: dict[str, Any]) -> int:
        _require(self._initial_seed_borrow is None, "initial_seed_move_unconfirmed", "A preceding seed source must be safely released before borrowing another.")
        binding = self.initial_seed_sources[declaration["playerInventoryIndex"]]
        rows = await self.initial_seed_inventory()
        source = binding["sourceIndex"]
        item = rows[source]["item"]
        _require(_same(item, declaration["item"]) and item["nbtIdentity"] == "" and item["count"] == binding["remainingCount"],
            "initial_seed_source_changed", "The complete declared source remainder must be freshly unchanged before placement.")
        if source <= 8:
            self.materials[source] = {"item": dict(declaration["item"]), "count": item["count"]}
            return source
        protected = {self.args["blueprintHotbarSlot"], self.controller_slot, *(port["hotbarSlot"] for port in self.args["requestedPorts"])}
        empty = [row["index"] for row in rows if row["index"] <= 8 and row["index"] not in protected and row["item"] is None]
        _require(bool(empty), "initial_seed_hotbar_unavailable", "A backpack source needs one real empty unreserved hotbar; no source is overwritten.")
        record = {"originalIndex": source, "temporaryIndex": empty[0], "item": dict(declaration["item"]), "count": item["count"],
            "nbtIdentity": "", "status": "retrieving", "purpose": "permanent_initial_controller_seed", "ledgerIndex": len(self.material_moves)}
        self.material_moves.append(record)
        self._initial_seed_borrow = record
        screen, _ = await self.open_creative_player_inventory("initial_seed_source_prepare")
        await self.move_native_player_stack(record, screen, source, empty[0], "initial_seed_source_retrieve", close_after=True)
        record.update(movementConfirmed=True, status="retrieved")
        self.materials[empty[0]] = {"item": dict(declaration["item"]), "count": item["count"]}
        return empty[0]

    def account_initial_seed_deduction(self, record: dict[str, Any]) -> None:
        _require(record.get("initialSourceAccounted") is not True, "initial_seed_deduction_already_accounted", "A known native deduction is accounted exactly once.")
        binding = self.initial_seed_sources[record["declaredPlayerInventoryIndex"]]
        index = record.get("survivalMaterialLedgerIndex")
        _require(type(index) is int and 0 <= index < len(self.survival_material_ledger), "initial_seed_deduction_unavailable", "Initial sources require the original native ledger entry.")
        deductions = self.survival_material_ledger[index]["deductions"]
        _require(len(deductions) == 1 and deductions[0]["playerInventoryIndex"] == record["sourceHotbarSlot"]
            and deductions[0]["count"] == 1 and deductions[0]["nbtIdentity"] == ""
            and deductions[0]["sourceCountBefore"] == binding["remainingCount"], "initial_seed_deduction_unavailable", "Exactly one bound finite untagged source item may be accounted.")
        deduction = deductions[0]
        binding["remainingCount"] = deduction["sourceCountAfter"]
        move_indices = [(self.material_moves, record.get("sourceMoveLedgerIndex")),
            (self.borrowed_inventory, record.get("sourceReservationLedgerIndex"))]
        for moves, move_index in move_indices:
            if move_index is None:
                continue
            move = moves[move_index]
            _require(move.get("movementConfirmed") is True and not move.get("outcomeUnknown")
                and move["item"] == record["sourceItem"] and move["count"] in (deduction["sourceCountBefore"], deduction["sourceCountAfter"]),
                "initial_seed_deduction_unavailable", "Only the known exact borrowed chain may retain the same native remainder.")
            move.setdefault("movedCount", deduction["sourceCountBefore"])
            move["count"] = deduction["sourceCountAfter"]
            move["nativeConsumedCount"] = move.get("nativeConsumedCount", 0) + 1
            move.setdefault("survivalMaterialLedgerIndices", []).append(index)
            if move["count"] == 0:
                move.update(restorationConfirmed=True, status="consumed")
        slot = record["sourceHotbarSlot"]
        self.materials[slot]["count"] = deduction["sourceCountAfter"]
        if self.survival_inventory is not None:
            previous = self.survival_inventory[slot]["item"]
            self.survival_inventory[slot]["item"] = None if deduction["sourceCountAfter"] == 0 else {**previous, "count": deduction["sourceCountAfter"]}
        record["initialSourceAccounted"] = True

    async def release_initial_seed_source(self) -> None:
        record = self._initial_seed_borrow
        if record is None:
            return
        _require(record.get("movementConfirmed") is True and not record.get("outcomeUnknown") and not record.get("restorationAttempted"),
            "initial_seed_move_unconfirmed", "Unknown or attempted source restoration is never replayed.")
        if record["count"]:
            record["restorationAttempted"] = True
            screen, _ = await self.open_creative_player_inventory("initial_seed_source_return")
            await self.move_native_player_stack(record, screen, record["temporaryIndex"], record["originalIndex"], "initial_seed_source_return", close_after=True)
            record.update(restorationConfirmed=True, status="restored")
        self.materials.pop(record["temporaryIndex"], None)
        self._initial_seed_borrow = None

    async def retrieve_initial_controller(self) -> None:
        rows = await self.initial_seed_inventory()
        slot = self.empty_slot
        _require(type(slot) is int and rows[slot]["item"] is None, "initial_controller_hotbar_unavailable", "The seed working slot must be freshly empty before retrieving the controller.")
        record = {"originalIndex": self.controller_slot, "temporaryIndex": slot, **self.materials[self.controller_slot],
            "status": "retrieving", "purpose": "initial_controller_after_seed_column"}
        self.material_moves.append(record)
        screen, _ = await self.open_creative_player_inventory("initial_controller_prepare")
        await self.move_native_player_stack(record, screen, self.controller_slot, slot, "initial_controller_retrieve", close_after=True)
        record.update(movementConfirmed=True, status="retrieved")
        self.materials[slot] = self.materials.pop(self.controller_slot)
        self.controller_slot = slot

    def initial_seed_record_matches(self, record: dict[str, Any]) -> bool:
        if not self.initial_controller_seed_mode:
            return record.get("sourceHotbarSlot") == self.args["supportHotbarSlot"] and record.get("sourceItem") == self.args["supportItem"]
        declaration = self.initial_seed_declarations.get(_key(record["position"]))
        if declaration is None or record.get("declaredPlayerInventoryIndex") != declaration["playerInventoryIndex"] \
                or record.get("offsetY") != declaration["offsetY"] or record.get("sourceItem") != declaration["item"] \
                or record.get("sourceNbtIdentity") != "" or record.get("initialSourceAccounted") is not True:
            return False
        source = self.initial_seed_sources[declaration["playerInventoryIndex"]]
        if record.get("sourcePlayerInventoryIndex") != source["sourceIndex"] or type(record.get("sourceHotbarSlot")) is not int:
            return False
        reservation_index = record.get("sourceReservationLedgerIndex")
        if reservation_index != source.get("reservationLedgerIndex"):
            return False
        if reservation_index is not None:
            if type(reservation_index) is not int or not 0 <= reservation_index < len(self.borrowed_inventory):
                return False
            reservation = self.borrowed_inventory[reservation_index]
            if (reservation.get("movementConfirmed") is not True or reservation.get("outcomeUnknown")
                    or reservation.get("hotbarIndex") != declaration["playerInventoryIndex"]
                    or reservation.get("storageIndex") != source["sourceIndex"] or reservation.get("item") != declaration["item"]
                    or reservation.get("nbtIdentity") != ""
                    or record["survivalMaterialLedgerIndex"] not in reservation.get("survivalMaterialLedgerIndices", [])):
                return False
        move_index = record.get("sourceMoveLedgerIndex")
        if move_index is None:
            return record["sourceHotbarSlot"] == source["sourceIndex"] and 0 <= source["sourceIndex"] <= 8
        if type(move_index) is not int or not 0 <= move_index < len(self.material_moves):
            return False
        move = self.material_moves[move_index]
        return (move.get("movementConfirmed") is True and not move.get("outcomeUnknown") and move.get("nbtIdentity") == ""
            and move["originalIndex"] == source["sourceIndex"] and move["temporaryIndex"] == record["sourceHotbarSlot"]
            and move["item"] == declaration["item"] and record["survivalMaterialLedgerIndex"] in move.get("survivalMaterialLedgerIndices", []))

    async def read_survival_ae_source(self, phase: str, steps: list[dict[str, Any]]) -> dict[str, Any]:
        declaration = self.survival_ae_source
        args = {**{key: self.args[key] for key in ("expectedSessionId", "expectedWorld", "dimension")},
            **{key: declaration[key] for key in ("x", "y", "z", "side", "selector")}, "energyProbeItems": 65_536}
        steps.append(_read("ae_source", "mcb_ae_material_source", args, _AE_SOURCE_FIELDS))
        report = await self.workflow(phase, steps)
        entries = [entry for entry in report.get("steps", []) if entry.get("id") == "ae_source"]
        observation = entries[0].get("observation", {}) if len(entries) == 1 else {}
        value = copy.deepcopy(observation.get("values"))
        _require(isinstance(value, dict), "survival_ae_source_read_unavailable",
            "A fresh source read must retain its own complete typed projected receipt before any shell submission.")
        self.survival_ae_readiness.append({"phase": phase, "observation": value,
            "missingFields": observation.get("missingFields", []),
            "storedContentsProven": False, "extractionProven": False, "formationProven": False})
        binding = _ae_source_readiness(value, declaration, self.dimension, self.survival_player_id, self.args["expectedSessionId"])
        _require(self.survival_ae_binding is None or binding == self.survival_ae_binding,
            "survival_ae_source_changed", "The exact actor/entity, grid, selected anchor and public native cell binding must remain unchanged since preflight; no source substitution or replay is allowed.")
        # The general read guard binds AFTER/world/background but does not
        # enforce living survival identity; acquire its own current evidence.
        snapshot = await self.snapshot()
        self.require_survival_server_control(snapshot)
        _require(self.survival_control_player_id == self.survival_player_id,
            "survival_ae_source_actor_changed", "The fresh current server-control player must match the source's actual authoritative actor before any shell submission.")
        self.survival_ae_binding = binding
        return value

    async def prepare_inventory(self) -> None:
        self.phase = "inventory_preparation"
        if self.inventory_preparation_mode in ("minimal", "clear"):
            await self.prepare_cleared_inventory()
            return
        before = await self.call("inventory.inspect", {})
        slots = inventory_slots(before, "")
        if self.initial_controller_seed_mode:
            await self.initial_seed_inventory(before)
        protected = set(self.materials) | self.connection_slots
        empty = [slot["index"] for slot in slots if 0 <= slot["index"] <= 8 and slot["item"] is None]
        # A storage controller needs one working slot. Return its unchanged
        # creative stack immediately after placement; that same slot then
        # supplies the empty hand for digging and opening the controller GUI.
        needed = 1
        borrow = [slot for slot in slots if 0 <= slot["index"] <= 8 and slot["index"] not in protected and slot["item"] is not None
            and (not self.initial_controller_seed_mode or slot["item"].get("hasTag") is False and slot["item"].get("nbtIdentity") in (None, ""))]
        storage = [slot["index"] for slot in slots if 9 <= slot["index"] <= 35 and slot["item"] is None]
        _require(len(empty) + len(borrow) >= needed and len(storage) >= max(0, needed - len(empty)),
            "inventory_reservation_unavailable", "Native preparation needs one empty hotbar slot and empty real storage for its borrowed stack.")
        screen_id = None
        if len(empty) < needed or self.controller_slot > 8:
            screen_id, mapped = await self.open_creative_player_inventory("inventory_preparation")
            _require(set(range(36)).issubset(mapped), "creative_player_slot_mapping_unavailable", "All real player indices 0..35 need public unique native widget bindings.")
        for slot, destination in zip(borrow[:max(0, needed - len(empty))], storage):
            record = {"hotbarIndex": slot["index"], "storageIndex": destination,
                "item": _identity(slot["item"]), "count": _quantity(slot["item"].get("count")), "status": "borrowing", "purpose": "blueprint_empty_hand_and_controller"}
            self.borrowed_inventory.append(record)
            await self.move_native_player_stack(record, screen_id, slot["index"], destination, "borrow_" + str(slot["index"]))
            record.update(movementConfirmed=True, status="borrowed")
            if self.initial_controller_seed_mode and slot["index"] in self.initial_seed_sources:
                binding = self.initial_seed_sources[slot["index"]]
                binding.update(sourceIndex=destination, reservationLedgerIndex=len(self.borrowed_inventory) - 1)
                record["nbtIdentity"] = ""
            empty.append(slot["index"])
        self.empty_slot = empty[0]
        if self.controller_slot > 8 and not self.initial_controller_seed_mode:
            record = {"originalIndex": self.controller_slot, "temporaryIndex": empty[0], **self.materials[self.controller_slot], "status": "retrieving"}
            self.material_moves.append(record)
            await self.move_native_player_stack(record, screen_id, self.controller_slot, empty[0], "retrieve_controller", close_after=True)
            record.update(movementConfirmed=True, status="retrieved")
            self.materials[empty[0]] = self.materials.pop(self.controller_slot)
            self.controller_slot = empty[0]
        elif screen_id is not None:
            await self.workflow("inventory_close", [_action("close", "mcb_close_gui", {})])

    def verify_minimal_inventory(self, actual: dict[str, Any], supplies: list[tuple[str, int, int]]) -> bool:
        slots = inventory_slots(actual, "")
        _require(len(slots) == 41 and {slot["index"] for slot in slots} == set(range(41)),
            "minimal_inventory_coverage_unavailable", "Minimal preparation requires all actual player indices 0..40, including armor, with no missing or unknown slots.")
        _require("cursorItem" in actual and actual["cursorItem"] is None, "carried_stack_not_empty",
            "Minimal preparation requires an explicitly observed empty real cursor.")
        expected = {index: (self.materials[index]["item"], count) for _, index, count in supplies}
        exact = all(0 <= index <= 8 for index in expected) and all(
            slot["item"] is None if slot["index"] not in expected else
            _same(slot["item"], expected[slot["index"]][0]) and type(slot["item"].get("count")) is int
                and slot["item"]["count"] == expected[slot["index"]][1] for slot in slots)
        self.phases.append({"phase": "verify_minimal_inventory", "status": "completed",
            "alreadyMinimalVerified": exact, "completeSlotCoverage": 41, "declaredSupplyStacks": len(supplies),
            "source": "Fresh complete actual player indices 0..40, exact untagged identity/count and declared hotbar bindings, with an explicitly empty cursor."})
        return exact

    async def prepare_cleared_inventory(self) -> None:
        """Retain proven minimal supplies when requested, otherwise clear and supply."""
        _require(self.args.get("generateMaterials", False) is True and not self.actual_survival,
            "inventory_clear_not_authorized", "This creative clear/grant path requires explicit material generation and cannot run in actual survival.")
        snapshot = await self.snapshot()
        name = snapshot.get("player", {}).get("name")
        _require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is not None,
            "player_command_identity_unavailable", "Supply only the current observed test player.")
        supplies = [("blueprint", self.args["blueprintHotbarSlot"], self.materials[self.args["blueprintHotbarSlot"]]["count"]),
            *[("port:" + str(index), port["hotbarSlot"], 1) for index, port in enumerate(self.args["requestedPorts"])],
            ("controller", self.controller_slot, 1)]
        if self.support_mode == "player" and "supportHotbarSlot" in self.args:
            supplies.append(("support", self.args["supportHotbarSlot"], min(3, self.materials[self.args["supportHotbarSlot"]]["count"])))
        grouped = self.group_build_supplies(supplies, "supply_identity_ambiguous")
        if not self.declared_supplies_verified:
            for supply in grouped:
                if len(supply["bindings"]) > 1:
                    public = await self.call("registry.item", supply["item"])
                    _require(public.get("registryName") == supply["item"]["registryName"]
                        and type(public.get("metadata")) is int and public["metadata"] == supply["item"]["metadata"]
                        and type(public.get("stackLimit")) is int and 1 <= supply["count"] <= min(64, public["stackLimit"]),
                        "declared_supply_registry_unavailable", "The aggregate port supply needs an exact current registry identity and sufficient real stack limit before clearing.")
        steps = []
        for index, supply in enumerate(grouped):
            item, count = supply["item"], supply["count"]
            command = f"/give {name} {item['registryName']} {count} {item['metadata']}"
            _require(1 <= count <= 64 and len(command) <= 100, "material_command_budget", "Validate every bounded grant before clearing inventory.")
            steps.append(_action("give_" + str(index), "mcb_chat", {"message": command}))
        if self.inventory_preparation_mode == "minimal":
            actual = await self.call("inventory.inspect", {})
            if self.verify_minimal_inventory(actual, supplies):
                self.already_minimal_verified = True
                self.empty_slot = next(slot["index"] for slot in actual["slots"] if 0 <= slot["index"] <= 8 and slot["item"] is None)
                for supply in grouped:
                    if len(supply["bindings"]) > 1:
                        for binding in supply["bindings"]:
                            self.bind_port_supply(binding, binding["declaredHotbarSlot"], binding["count"], 0, "independent_declared_hotbar")
                return
        await self.clear_player_inventory()
        batch = "inventory.supply" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("inventory.supply") is True
        if batch:
            self.material_supply_mode = "native_server_give_commands"
            items = [{**supply["item"], "count": supply["count"]} for supply in grouped]
            await self.workflow("supply_build_materials", [
                _action("supply", "mcb_supply_items", {"expectedSessionId": self.args["expectedSessionId"],
                    "expectedWorld": self.args["expectedWorld"], "items": items}),
                _assert("commands_complete", "supply", "complete", True),
                _assert("commands_known", "supply", "outcomeUnknown", False),
                _assert("commands_attempted", "supply", "commandsAttempted", len(items)),
                _assert("commands_accepted", "supply", "commandsAccepted", len(items))])
        else:
            self.material_supply_mode = "native_chat_give_commands"
            await self.workflow("supply_build_materials", steps)
        actual = await self.wait_supplied_inventory([(str(index), supply["sourceSlot"], supply["count"])
            for index, supply in enumerate(grouped)])
        slots = inventory_slots(actual, "")
        materials = {}
        self.args["requestedPorts"] = [dict(port) for port in self.args["requestedPorts"]]
        for supply in grouped:
            item, count = supply["item"], supply["count"]
            sources = [slot for slot in slots if _same(slot["item"], item)]
            _require(len(sources) == 1 and 0 <= sources[0]["index"] <= 8 and sources[0]["item"].get("count") == count,
                "supplied_hotbar_binding_unavailable", "Bind supplied stacks only from actual synchronized unique hotbar identities/counts.")
            index = sources[0]["index"]
            materials[index] = {"item": item, "count": count}
            for allocation_index, binding in enumerate(supply["bindings"]):
                purpose = binding["purpose"]
                if purpose == "controller":
                    self.controller_slot = index
                elif purpose.startswith("port:"):
                    self.args["requestedPorts"][int(purpose[5:])]["hotbarSlot"] = index
                    if len(supply["bindings"]) > 1:
                        self.bind_port_supply(binding, index, count, allocation_index, "aggregate_exact_port_supply")
                else:
                    self.args["blueprintHotbarSlot" if purpose == "blueprint" else "supportHotbarSlot"] = index
        self.materials = materials
        empty = [slot["index"] for slot in slots if 0 <= slot["index"] <= 8 and slot["item"] is None]
        _require(bool(empty) and actual.get("cursorItem") is None, "empty_hand_unavailable", "Concentrated supply must leave a real empty hotbar slot and cursor.")
        self.empty_slot = empty[0]

    async def wait_supplied_inventory(self, supplies: list[tuple[str, int, int]]) -> dict[str, Any]:
        """Wait for the whole native grant batch; never infer pickup order or replay grants."""
        from .workflows import _context_capabilities, _guard

        self.phase = "verify_supplied_inventory"
        started = time.monotonic()
        verification = {"phase": self.phase, "status": "running", "attempts": 0,
            "declaredSupplyStacks": len(supplies), "timingScope": "native_inventory_batch_wait"}
        self.phases.append(verification)
        reads, _ = _context_capabilities(self.approach_health or {})
        context = {"version": 1, "expectedSessionId": self.args["expectedSessionId"], "expectedProfile": "developer",
            "expectedWorld": self.args["expectedWorld"], "requireBackground": True} if "inventory.inspect" in reads else None
        try:
            with anyio.fail_after(self.remaining() / 1000):
                while True:
                    with request_context(context) if context is not None else nullcontext():
                        actual = await self.call("inventory.inspect", {})
                    verification["attempts"] += 1
                    if context is not None:
                        evidence = response_context(actual)
                        _require(isinstance(evidence, dict) and type(evidence.get("version")) is int and evidence["version"] == 1
                            and evidence.get("operation") == "inventory.inspect" and evidence.get("phase") == "after"
                            and isinstance(evidence.get("snapshot"), dict), "supply_context_unavailable",
                            "The advertised inventory read must retain its fresh after-context; no grant is replayed.")
                        snapshot = evidence["snapshot"]
                    else:
                        snapshot = await self.snapshot()
                    _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
                    _require(snapshot.get("worldLoaded") is True and type(snapshot.get("world", {}).get("dimension")) is int
                        and snapshot["world"]["dimension"] == self.dimension, "dimension_mismatch",
                        "The complete supply batch needs the exact current loaded dimension.")
                    slots = inventory_slots(actual, "")
                    _require(len(slots) == 41 and all(type(slot.get("index")) is int for slot in slots)
                        and {slot["index"] for slot in slots} == set(range(41)),
                        "supplied_inventory_coverage_unavailable", "The complete supply batch requires actual player indices 0..40, including armor.")
                    _require("cursorItem" in actual and actual["cursorItem"] is None, "carried_stack_not_empty",
                        "The complete supply batch requires an explicitly empty actual cursor.")
                    ready = sum(slot["item"] is not None for slot in slots) == len(supplies)
                    for _, old_slot, count in supplies:
                        sources = [slot for slot in slots if _same(slot["item"], self.materials[old_slot]["item"])]
                        ready &= len(sources) == 1 and 0 <= sources[0]["index"] <= 8 \
                            and type(sources[0]["item"].get("count")) is int and sources[0]["item"]["count"] == count
                    if ready:
                        verification.update(status="completed", completeSlotCoverage=41, completeBatchVerified=True)
                        return actual
                    await anyio.sleep(.05)
        except TimeoutError:
            verification["status"] = "failed"
            raise BridgeError("WAIT_TIMEOUT", "The complete native supply batch was not synchronized before the task deadline; no grant is replayed.",
                reason="supplied_hotbar_binding_unavailable", outcome_unknown=False) from None
        except BridgeError:
            verification["status"] = "failed"
            raise
        finally:
            verification["elapsed_ms"] = int((time.monotonic() - started) * 1000)

    async def open_creative_player_inventory(self, phase: str) -> tuple[str, dict[int, dict[str, Any]]]:
        screen_id, mapped = await super().open_creative_player_inventory(phase)
        self.creative_mapping = (screen_id, mapped)
        return screen_id, mapped

    async def move_native_player_stack(self, record: dict[str, Any], screen_id: str,
        source_index: int, destination_index: int, phase: str, *, close_after: bool = False) -> None:
        """Reuse only public slot bindings; read actual items/cursor for every move.

        A screen-bound widget's item snapshot is deliberately never reused as
        stack evidence. Native callbacks still reject stale screen/widget IDs.
        """
        if self.actual_survival:
            if self.initial_controller_seed_mode:
                before = await self.initial_seed_inventory()
                expected = copy.deepcopy(before)
                source = expected[source_index]["item"]
                _require(_same(source, record["item"]) and source["nbtIdentity"] == "" and source["count"] == record["count"]
                    and expected[destination_index]["item"] is None, "initial_seed_move_source_changed",
                    "Only an exact untagged whole source stack may move into an observed real empty destination.")
                attempts = record.setdefault("initialSeedMoveAttempts", [])
                _require(not any(row["phase"] == phase and row["source"] == source_index and row["destination"] == destination_index for row in attempts)
                    and len(attempts) < 2, "initial_seed_move_already_attempted", "Unknown or previously attempted inventory callbacks are never replayed.")
                attempt = {"phase": phase, "source": source_index, "destination": destination_index, "confirmed": False}
                attempts.append(attempt)
                expected[destination_index]["item"], expected[source_index]["item"] = source, None
                try:
                    await super().move_native_player_stack(record, screen_id, source_index, destination_index, phase, close_after=close_after)
                    after = await self.initial_seed_inventory(check_baseline=False)
                    _require(after == expected, "initial_seed_move_unconfirmed",
                        "All41 observable identities/counts/tag-presence and any available NBT must conserve exactly through a native whole-stack move.")
                except (BridgeError, TimeoutError):
                    record["outcomeUnknown"] = True
                    raise
                attempt["confirmed"] = True
                record.update(nbtIdentity="", clientAll41ObservedConserved=True,
                    unrelatedTaggedFullNbtProven=False, nativePlacementFullNbtAcceptanceRequired=True)
            else:
                await super().move_native_player_stack(record, screen_id, source_index, destination_index, phase, close_after=close_after)
            _require(self.survival_inventory is not None, "survival_inventory_baseline_unavailable", "The finite material ledger requires its complete real baseline.")
            self.survival_inventory[destination_index]["item"] = self.survival_inventory[source_index]["item"]
            self.survival_inventory[source_index]["item"] = None
            self.account_tagged_context_move(source_index, destination_index)
            if self.survival_ae_player_inventory is not None:
                self.survival_ae_player_inventory[destination_index]["item"] = self.survival_ae_player_inventory[source_index]["item"]
                self.survival_ae_player_inventory[source_index]["item"] = None
            record.update(movementDispatched=True, movementConfirmed=True)
            return
        _require(self.creative_mapping is not None and self.creative_mapping[0] == screen_id,
            "creative_player_slot_mapping_unavailable", "This exact creative screen needs previously observed public native bindings.")
        mapped = self.creative_mapping[1]
        source, destination = mapped.get(source_index), mapped.get(destination_index)
        _require(source is not None and destination is not None, "creative_player_slot_mapping_unavailable",
            "Both actual player indices need public unique native widgets; wrapper positions are never inferred.")
        identity, count = record["item"], record["count"]
        record.update(sourceWidgetId=source["widgetId"], destinationWidgetId=destination["widgetId"],
            sourceNativeContainerSlotId=source["nativeContainerSlotId"], destinationNativeContainerSlotId=destination["nativeContainerSlotId"])
        steps = [_read("before", "mcb_inventory", fields=["slots", "cursorItem", "totalSlots", "truncated"]),
            _assert("before_complete", "before", "truncated", False),
            _assert("before_total", "before", "totalSlots", len(self.original_inventory)),
            _assert("cursor_empty", "before", "cursorItem", None),
            _assert("source_exact", "before", "slots", {"index": source_index, **_match(identity, count)}, "matches_one"),
            _assert("destination_empty", "before", "slots", {"index": destination_index, "item": None}, "matches_one"),
            _action("pickup", "mcb_gui_widget_action", {"screenId": screen_id, "widgetId": source["widgetId"], "action": "activate"}),
            _wait("carried", "mcb_inventory", {}, "cursorItem.count", count, fields=["cursorItem", "slots", "totalSlots", "truncated"]),
            _assert("carried_complete", "carried", "truncated", False),
            _assert("carried_total", "carried", "totalSlots", len(self.original_inventory)),
            _assert("carried_registry", "carried", "cursorItem.registryName", identity["registryName"]),
            _assert("carried_metadata", "carried", "cursorItem.metadata", identity["metadata"]),
            _assert("carried_untagged", "carried", "cursorItem.hasTag", False),
            _assert("source_empty", "carried", "slots", {"index": source_index, "item": None}, "matches_one"),
            _action("deposit", "mcb_gui_widget_action", {"screenId": screen_id, "widgetId": destination["widgetId"], "action": "activate"}),
            _wait("placed", "mcb_inventory", {}, "cursorItem", None, fields=["slots", "cursorItem", "totalSlots", "truncated"]),
            _assert("destination_exact", "placed", "slots", {"index": destination_index, **_match(identity, count)}, "matches_one"),
            _assert("source_still_empty", "placed", "slots", {"index": source_index, "item": None}, "matches_one")]
        if close_after:
            steps.append(_action("close", "mcb_close_gui", {}))
        self.current_inventory_move = record
        try:
            report = await self.workflow(phase, steps)
        except (BridgeError, TimeoutError):
            if self.phases and self.phases[-1].get("outcomeUnknown") is True:
                record["outcomeUnknown"] = True
            raise
        finally:
            self.current_inventory_move = None
        for step_id in ("before", "carried", "placed"):
            inventory_slots({key: self.value(report, step_id, key) for key in ("slots", "totalSlots", "truncated")}, "")
        placed = {key: self.value(report, "placed", key) for key in ("slots", "cursorItem", "totalSlots", "truncated")}
        indexed = {slot["index"]: slot["item"] for slot in inventory_slots(placed, "")}
        _require(indexed.get(source_index) is None and _same(indexed.get(destination_index), identity)
            and indexed[destination_index].get("count") == count and placed["cursorItem"] is None,
            "native_inventory_move_not_confirmed", "Exact real source/destination/count and empty cursor must confirm every native move.")
        self.account_tagged_context_move(source_index, destination_index)

    async def native_approach(self, target: dict[str, Any], **options: Any) -> dict[str, Any]:
        constraint = self._controller_placement_pose
        bound = constraint is not None and constraint.get("groundedBootstrap") is True
        if bound:
            _require(options.get("intent") == "place_block" and _xyz(target) == _xyz(self.args["controller"]),
                "controller_placement_pose_changed", "A controller-facing constraint cannot be reused for another operation or target.")
            options["expected_placement_facing"] = constraint["direction"]
        result = await super().native_approach(target, **options)
        if bound:
            _require(result.get("expectedPlacementFacing") == constraint["direction"],
                "controller_placement_facing_unavailable", "The actual native planner must echo the requested placement-facing filter.")
        return result

    async def planned_approach(self, target: dict[str, Any], *, intent: str = "use", allowed_sides: tuple[int, ...] | None = None,
        hotbar_slot: int | None = None, target_identity: dict[str, Any] | None = None,
        excluded_build_envelope: dict[str, int] | None = None,
        prepared_plan: dict[str, Any] | None = None) -> tuple[dict[str, Any], int, list[dict[str, Any]]]:
        if allowed_sides is None:
            allowed_sides = (0, 1, 2, 3, 4, 5) if self.survival_flight and intent == "use" else (2, 3, 4, 5)
        options = dict(intent=intent, allowed_sides=allowed_sides, hotbar_slot=hotbar_slot, target_identity=target_identity)
        if excluded_build_envelope is not None:
            options["excluded_build_envelope"] = excluded_build_envelope
        if prepared_plan is not None:
            options["prepared_plan"] = prepared_plan
        constraint = self._controller_placement_pose
        if constraint is not None and constraint.get("groundedBootstrap") is True:
            _require(prepared_plan is None, "controller_placement_pose_changed", "The constrained controller requires a fresh actual-current placement read.")
            plan = await self.current_native_approach(target, **options)
            _require(plan.get("status") == "ready", "controller_placement_facing_unavailable",
                "No matching native placement ray is proved at the prepared grounded stance; no placement or movement fallback.")
        else:
            plan = await self.resolved_native_approach(target, **options)
        current, selected = plan["current"], plan["selected"]
        if intent == "place_block":
            self._planned_placement_attachment = copy.deepcopy(selected["attachment"])
        constraint = self._controller_placement_pose
        if constraint is not None:
            _require(intent == "place_block" and selected["moveRequired"] is False
                and all(current[axis] == selected["feet"][axis] == constraint["feet"][axis] for axis in ("x", "y", "z")),
                "controller_placement_pose_changed", "The controller planner must select the actual prescribed current stance without moving.")
            aim = _placement_aim(selected["eye"], selected["hit"])
            _require(_placement_aim_matches(selected["aim"], aim)
                and _normal_gt_placement_facing(aim) == constraint["direction"],
                "controller_placement_facing_unavailable", "The actual selected native ray aim must quantize to the source controller direction.")
            constraint.update(planner="native", eye=dict(selected["eye"]), selectedHit=dict(selected["hit"]), selectedAim=aim)
        if intent == "place_block":
            material = self.materials[hotbar_slot]
            _require(_same(current["sourceItem"], material["item"]) and current["sourceItem"]["count"] == material["count"],
                "native_approach_source_changed", "The planner's actual source stack must retain the task's exact guarded identity and count.")
        self.approach_selected = current["selectedHotbarSlot"]
        self.position_snapshot = response_context(plan)["snapshot"]
        flight = (("player.serverFlight.available", True), ("player.serverFlight.flying", self.interaction_flying),
            ("player.serverFlight.creative", False) if self.actual_survival else ("player.serverFlight.allowFlying", True),
            ("player.serverFlight.dimension", self.dimension), ("player.flying", self.interaction_flying))
        if self.actual_survival:
            flight += (("player.creative", False),)
        guarded_approach = not self.actual_survival and self._workflow_discovery is not None and "inventory.inspect" in self._workflow_discovery.reads \
            and current["screenOpen"] is False
        if guarded_approach:
            from .workflows import _WorkflowSnapshotChecks
            # The planner's proof was already consumed and checked above. The
            # next live RPC must prove flight again from its own fresh guard.
            # A move uses its existing BEFORE snapshot; no-move uses the first
            # inventory AFTER guard and must also retain every current axis.
            pose = tuple(("player." + ("feetY" if axis == "y" else axis), current[axis]) for axis in ("x", "y", "z"))
            destination_pose = tuple(("player." + ("feetY" if axis == "y" else axis), selected["feet"][axis])
                for axis in ("x", "y", "z"))
            rules = (("move", flight + pose), ("synced", flight), ("inventory", flight + destination_pose)) \
                if selected["moveRequired"] else (("inventory", flight + pose),)
            steps = _ApproachSteps(_WorkflowSnapshotChecks(self._workflow_discovery, rules))
        else:
            steps = ([] if current["screenOpen"] is False else [_action("close", "mcb_close_gui", {})]) + [_read("flight", "mcb_snapshot"),
            _assert("server_available", "flight", "player.serverFlight.available", True),
            _assert("server_flying", "flight", "player.serverFlight.flying", self.interaction_flying),
            _assert("server_mode", "flight", "player.serverFlight.creative", False) if self.actual_survival else
            _assert("server_allow", "flight", "player.serverFlight.allowFlying", True),
            _assert("server_dimension", "flight", "player.serverFlight.dimension", self.dimension),
            _assert("client_flying", "flight", "player.flying", self.interaction_flying)]
            if self.actual_survival:
                steps.append(_assert("client_mode", "flight", "player.creative", False))
        destination = selected["feet"]
        if selected["moveRequired"]:
            steps.append(_action("move", "mcb_teleport", {**destination, "dimension": self.dimension,
                "expectedWorld": self.args["expectedWorld"], "requireFlying": self.interaction_flying}))
            if guarded_approach:
                steps.append(_wait("synced", "mcb_snapshot", {}, "player",
                    {"x": destination["x"], "feetY": destination["y"], "z": destination["z"]}, operator="fields_equal"))
            else:
                paths = {"x": "player.x", "y": "player.feetY", "z": "player.z"}
                changed = [axis for axis in ("y", "z", "x") if current[axis] != destination[axis]]
                for index, axis in enumerate(changed):
                    steps.append(_wait("synced" if index == len(changed) - 1 else "sync_" + axis + "_wait",
                        "mcb_snapshot", {}, paths[axis], destination[axis]))
                steps.extend(_assert("sync_" + axis, "synced", paths[axis], destination[axis])
                    for axis in ("y", "z", "x") if axis != changed[-1])
        elif not guarded_approach:
            steps.extend([_assert("same_x", "flight", "player.x", current["x"]), _assert("same_y", "flight", "player.feetY", current["y"]),
                _assert("same_z", "flight", "player.z", current["z"])])
        return _xyz(selected["attachment"]), selected["attachment"]["side"], steps

    async def approach(self, targets: list[tuple[dict[str, Any], int]], *, center: dict[str, Any] | None = None,
        intent: str = "use", target_identity: dict[str, Any] | None = None) -> tuple[dict[str, Any], int, list[dict[str, Any]]]:
        """Precheck clear feet heights and a public ray before moving once.

        The native client still validates its own ray at dispatch; the server
        spatial ray can differ at voxel boundaries and is not client proof.
        """
        if self.native_approach_available(intent):
            _require(bool(targets) and all(_xyz(target) == _xyz(targets[0][0]) for target, _ in targets),
                "native_approach_target_unavailable", "One composite use/attack plan requires one exact clicked target.")
            target = targets[0][0]
            if target_identity is None and _xyz(target) == _xyz(self.args["controller"]):
                item = self.args["controllerItem"]
                target_identity = {"registryName": item["registryName"],
                    "metaTileId" if item["registryName"] == "gregtech:gt.blockmachines" else "metadata": item["metadata"]}
            return await self.planned_approach(target, intent=intent, allowed_sides=tuple(side for _, side in targets),
                target_identity=target_identity)
        _require(not self.actual_survival, "survival_grounded_approach_unavailable",
            "Actual survival cannot use the legacy flight planner when a grounded native candidate is unavailable.")
        snapshot = await self.snapshot()
        eye_offset = self.require_flight(snapshot)
        player = snapshot["player"]
        selected = player.get("selectedHotbarSlot")
        self.approach_selected = selected if type(selected) is int and 0 <= selected <= 8 else None
        center = targets[0][0] if center is None else center
        points = [{"x": center["x"] + dx, "y": height, "z": center["z"] + dz}
            for height in dict.fromkeys((center["y"] - 1, center["y"], center["y"] - 2, center["y"] + 1)) if 0 <= height <= 253
            for dx, dz in ((0, -2), (0, 2), (-2, 0), (2, 0), (-2, -2), (2, -2), (-2, 2), (2, 2))]
        current = {"x": player.get("x"), "y": player.get("feetY"), "z": player.get("z")}
        constraint = self._controller_placement_pose
        if constraint is not None:
            _require(all(type(current[axis]) in (int, float) and math.isfinite(current[axis])
                and current[axis] == constraint["feet"][axis] for axis in ("x", "y", "z")),
                "controller_placement_pose_changed", "Legacy placement must use the actual prescribed current stance without moving.")
            points = [{axis: math.floor(current[axis]) for axis in ("x", "y", "z")}]
        if all(type(value) in (int, float) and math.isfinite(value) for value in current.values()):
            point = {axis: math.floor(value) for axis, value in current.items()}
            if abs(current["x"] - point["x"] - .5) < .0001 and abs(current["z"] - point["z"] - .5) < .0001 and abs(current["y"] - point["y"]) < .0001:
                points.insert(0, point)
        points = list({_key(point): point for point in points}.values())
        spatial = await self.call("world.spatial", {"mode": "positions", "positions": points, "dimension": self.dimension, "limit": len(points)})
        for point in points:
            if not self.clear_body(spatial.get("positions"), point):
                continue
            origin = {"x": point["x"] + .5, "y": point["y"] + eye_offset, "z": point["z"] + .5}
            for target, side in targets:
                vector = _VECTORS[side]
                face = {axis: target[axis] + .5 + vector[index] * .499 for index, axis in enumerate(("x", "y", "z"))}
                if sum((origin[axis] - face[axis]) * vector[index] for index, axis in enumerate(("x", "y", "z"))) <= .001:
                    continue  # Never rely on a far-side face observed from its inward half-space.
                if sum((origin[axis] - face[axis]) ** 2 for axis in ("x", "y", "z")) > 9:
                    continue
                ray = await self.call("world.spatial", {"mode": "raycast", "dimension": self.dimension, "from": origin, "to": face, "includeEntities": False})
                hit = ray.get("hit")
                if ray.get("status") != "observed" or not isinstance(hit, dict) or hit.get("type") != "block" or hit.get("block") != _xyz(target) or hit.get("side") != side:
                    continue
                if constraint is not None:
                    _require(ray.get("source") == "integrated_server" and type(ray.get("dimension")) is int
                        and ray["dimension"] == self.dimension and ray.get("entityCoveragePartial") is False
                        and "stoppedAt" in ray and ray["stoppedAt"] is None,
                        "controller_placement_ray_unavailable", "Legacy pose binding needs the complete actual server ray and exact dimension.")
                    # The requested face is only a query endpoint. Consume the
                    # actual collision point; never turn that endpoint into hit evidence.
                    aim = _placement_aim({"x": player["x"], "y": player["eyeY"], "z": player["z"]}, hit.get("point"))
                    _require(_normal_gt_placement_facing(aim) == constraint["direction"],
                        "controller_placement_facing_unavailable", "The actual legacy ray aim must quantize to the source controller direction.")
                    constraint.update(planner="legacy_actual_ray", eye={"x": player["x"], "y": player["eyeY"], "z": player["z"]},
                        selectedHit=dict(hit["point"]), selectedAim=aim)
                steps = ([] if snapshot.get("screenOpen") is False else [_action("close", "mcb_close_gui", {})]) + [_read("flight", "mcb_snapshot"),
                    _assert("server_available", "flight", "player.serverFlight.available", True),
                    _assert("server_flying", "flight", "player.serverFlight.flying", True),
                    _assert("server_allow", "flight", "player.serverFlight.allowFlying", True),
                    _assert("server_dimension", "flight", "player.serverFlight.dimension", self.dimension),
                    _assert("client_flying", "flight", "player.flying", True)]
                destination = {"x": origin["x"], "y": point["y"], "z": origin["z"]}
                if any(current[axis] != value for axis, value in destination.items()):
                    steps.append(_action("move", "mcb_teleport", {**destination, "dimension": self.dimension,
                        "expectedWorld": self.args["expectedWorld"], "requireFlying": True}))
                    paths = {"x": "player.x", "y": "player.feetY", "z": "player.z"}
                    changed = [axis for axis in ("y", "z", "x") if current[axis] != destination[axis]]
                    # Wait only changed coordinates, but still require all XYZ
                    # in the same final snapshot, including unchanged axes.
                    for index, axis in enumerate(changed):
                        steps.append(_wait("synced" if index == len(changed) - 1 else "sync_" + axis + "_wait",
                            "mcb_snapshot", {}, paths[axis], destination[axis]))
                    steps.extend(_assert("sync_" + axis, "synced", paths[axis], destination[axis])
                        for axis in ("y", "z", "x") if axis != changed[-1])
                else:
                    steps.extend([_assert("same_x", "flight", "player.x", current["x"]), _assert("same_y", "flight", "player.feetY", current["y"]),
                        _assert("same_z", "flight", "player.z", current["z"])])
                return _xyz(target), side, steps
        raise _Unavailable("safe_visible_approach_unavailable", "No observed clear loaded body at the tested feet heights can reach the actual target/attachment face.")

    @staticmethod
    def attachment_neighbours(point: dict[str, Any]) -> list[dict[str, Any]]:
        return [neighbour for vector in _VECTORS.values()
            if 0 <= (neighbour := {axis: point[axis] - vector[index] for index, axis in enumerate(("x", "y", "z"))})["y"] <= 255]

    def observed_attachments(self, point: dict[str, Any], observed: dict[Any, Any], *, require_air: bool = False) -> list[tuple[dict[str, Any], int]]:
        required = ([point] if require_air else []) + self.attachment_neighbours(point)
        _require(all(isinstance(observed.get(_key(target)), dict) for target in required),
            "server_block_page_unavailable", "Every considered target and neighbour needs an explicit loaded observation; unknown points never become air.")
        if require_air:
            _require(observed[_key(point)]["registryName"] == "minecraft:air", "native_placement_target_changed",
                "Place only at actual server air; no foreign block replacement is permitted.")
        targets = []
        for side, vector in _VECTORS.items():
            neighbour = {axis: point[axis] - vector[index] for index, axis in enumerate(("x", "y", "z"))}
            block = observed.get(_key(neighbour), {})
            if block.get("registryName") not in (None, "minecraft:air", "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"):
                targets.append((neighbour, side))
        return targets

    async def attachments(self, point: dict[str, Any], *, require_air: bool = False) -> list[tuple[dict[str, Any], int]]:
        observed = await self.blocks(([point] if require_air else []) + self.attachment_neighbours(point))
        return self.observed_attachments(point, observed, require_air=require_air)

    def combined_read_args(self, point: dict[str, Any]) -> dict[str, Any]:
        return {"positions": [_xyz(point)], "dimension": self.dimension, "compact": False,
            "includePlayerInventory": True, "playerInventoryByteBudget": 131_072, "observationByteBudget": 262_144}

    def tagged_context_expected(self) -> list[dict[str, Any]]:
        _require(self.tagged_context_inventory is not None, "tagged_context_baseline_unavailable",
            "A tagged nonconsumed context needs its original complete full-NBT baseline.")
        return self.tagged_context_inventory

    def account_tagged_context_move(self, source: int, destination: int) -> None:
        if self.tagged_context_inventory is not None:
            rows = self.tagged_context_expected()
            _require(rows[destination]["item"] is None and rows[source]["item"] is not None,
                "tagged_context_move_changed", "Only a confirmed whole-stack move into an originally empty slot may update the full-NBT ledger.")
            rows[destination]["item"], rows[source]["item"] = rows[source]["item"], None

    def validate_tagged_context_preserved(self, rows: list[dict[str, Any]]) -> None:
        _require(all(context_equal(rows[slot]["item"], item) for slot, item in self.tagged_context_items.items()),
            "tagged_context_changed", "Every retained tagged context must remain in its original slot with identical full NBT, identity and count.")
        for slot, material in self.materials.items():
            identity = material.get("contextIdentity")
            if identity is not None:
                _require(context_equal(rows[slot]["item"], {**identity, "count": material["count"]}),
                    "tagged_context_changed", "A nonconsumed context stack must retain its exact original identity, full NBT and count.")

    async def read_tagged_context_inventory(self, *, require_background: bool = True,
        guard_steps: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
        from .workflows import _context_capabilities, _guard
        reads, _ = _context_capabilities(self.approach_health or {})
        capable = self.capabilities or []
        _require(("baubles.inspect" in capable if isinstance(capable, list) else capable.get("baubles.inspect") is True)
            and "baubles.inspect" in reads, "tagged_context_full_nbt_unavailable",
            "Tagged context requires an advertised guarded full-NBT inventory read; tag-presence DTOs cannot substitute.")
        paired = []
        for side in ("client", "server"):
            args = {"source": side, "includePlayerInventory": True, "limit": 1}
            with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
                "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": require_background}):
                value = await self.call("baubles.inspect", args)
            evidence = response_context(value)
            _require(isinstance(evidence, dict) and type(evidence.get("version")) is int and evidence["version"] == 1
                and evidence.get("operation") == "baubles.inspect" and evidence.get("phase") == "after"
                and isinstance(evidence.get("snapshot"), dict), "tagged_context_read_context_unavailable",
                "Each full-NBT read requires its own current typed AFTER snapshot.")
            snapshot = evidence["snapshot"]
            self.validate_snapshot(snapshot)
            _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], require_background)
            self.require_flight(snapshot)
            self.require_server_control(snapshot)
            player_id = snapshot.get("serverControl", {}).get("playerId")
            _require(isinstance(player_id, str) and bool(player_id)
                and (self.tagged_context_player_id is None or player_id == self.tagged_context_player_id)
                and (not self.actual_survival or player_id == self.survival_player_id), "tagged_context_actor_changed",
                "Both full inventories must belong to this same actual guarded client/server player.")
            self.tagged_context_player_id = player_id
            source = "integrated_server" if side == "server" else "client"
            _require(value.get("supported") is True and value.get("readOnly") is True and value.get("source") == source
                and value.get("sessionId") == self.args["expectedSessionId"] and value.get("playerId") == player_id
                and type(value.get("dimension")) is int and value["dimension"] == self.dimension
                and "cursorItem" in value and value["cursorItem"] is None
                and type(value.get("creative")) is bool and value["creative"] is snapshot["player"].get("creative")
                and type(value.get("flying")) is bool and value["flying"] is snapshot["player"].get("flying")
                and (side != "server" or isinstance(snapshot.get("world", {}).get("worldFolder"), str)
                    and value.get("worldFolder") == snapshot["world"]["worldFolder"]),
                "tagged_context_inventory_unavailable", "Full inventories need the exact actor, session, world, dimension and explicit empty cursor with complete native evidence.")
            inventory = value.get("playerInventory")
            rows = _context_inventory_rows(inventory)
            self.validate_tagged_context_preserved(rows)
            _require(self.tagged_context_inventory is None or context_equal(rows, self.tagged_context_expected()),
                "tagged_context_inventory_changed", "Every full-NBT slot must match only known native consumption and recorded moves.")
            paired.append(rows)
            if guard_steps is not None:
                identifier = "context_" + side
                guard_steps.extend([_read(identifier, "mcb_baubles", args,
                    ["supported", "readOnly", "source", "sessionId", "playerId", "dimension", "cursorItem", "playerInventory", "worldFolder"]),
                    *[_assert(identifier + "_" + key, identifier, key, expected) for key, expected in {
                        "supported": True, "source": source, "playerId": player_id,
                        "dimension": self.dimension, "cursorItem": None}.items()],
                    _assert(identifier + "_inventory", identifier, "playerInventory", json_sha256(inventory), "json_sha256_equals")])
        _require(context_equal(paired[0], paired[1]), "tagged_context_client_server_mismatch",
            "Complete client/server inventories must agree on every slot, full NBT, identity and quantity.")
        return paired[1]

    def tagged_context_selection_steps(self, slot: int) -> list[dict[str, Any]]:
        if self.tagged_context_inventory is None:
            return []
        selected = _read("context_selected", "mcb_snapshot", fields=["player", "serverControl"])
        if not self.actual_survival and self.approach_selected != slot:
            # hotbar acknowledges the client action, before a server tick may
            # observe it. Reuse the bounded read wait, never replay selection.
            # Survival already inserts its fresh-sample barrier in workflow().
            selected = _wait("context_selected", "mcb_snapshot", {}, "serverControl",
                {"selectedHotbarSlot": slot, "selectionSynchronized": True},
                operator="fields_equal", fields=["player", "serverControl"])
            selected["timeout_ms"] = min(1000, self.remaining())
        return [selected,
            _assert("context_server_selected", "context_selected", "serverControl", {
                "available": True, "source": "integrated_server_tick", "sessionId": self.args["expectedSessionId"],
                "dimension": self.dimension, "playerId": self.tagged_context_player_id,
                "selectedHotbarSlot": slot, "selectionSynchronized": True}, "fields_equal"),
            _assert("context_client_selected", "context_selected", "player", {
                "selectedHotbarSlot": slot, "flying": self.interaction_flying,
                "serverFlight.available": True, "serverFlight.flying": self.interaction_flying,
                "serverFlight.dimension": self.dimension,
                **({"creative": False, "serverFlight.creative": False} if self.actual_survival else {})}, "fields_equal")]

    def accept_tagged_context_native_inventory(self, before: dict[str, Any], after: dict[str, Any]) -> None:
        if self.tagged_context_inventory is None:
            return
        old, new = _context_inventory_rows(before), _context_inventory_rows(after)
        changed_slots = [row["index"] for row, expected in zip(old, self.tagged_context_expected())
            if not context_equal(row, expected)]
        _require(context_equal(old, self.tagged_context_expected()), "tagged_context_native_baseline_changed",
            "Native whole-player conservation must start from this exact complete full-NBT ledger; differing slots: "
            + str(changed_slots) + ".")
        self.validate_tagged_context_preserved(old)
        self.validate_tagged_context_preserved(new)
        self.tagged_context_inventory = copy.deepcopy(new)

    def material_guards(self, slot: int, *, point: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        record = self.materials[slot]
        match = _match(record["item"], record["count"])
        if "contextIdentity" in record:
            match["item.hasTag"] = True
            _require(point is None, "tagged_context_consumption_forbidden", "A tagged context item cannot become a consumed placement source.")
        if point is not None:
            return [_read("inventory", "mcb_world_inspect_many", self.combined_read_args(point),
                    ["results", "source", "dimension", "playerInventory", "playerInventoryObservation"]),
                _assert("cursor_empty", "inventory", "playerInventory.cursorItem", None),
                _assert("source_exact", "inventory", "playerInventory.slots", {"index": slot, **_match(record["item"], record["count"])}, "matches_one")]
        return [_read("inventory", "mcb_inventory", fields=["slots", "cursorItem", "totalSlots", "truncated", "selectedHotbarSlot"]),
            _assert("cursor_empty", "inventory", "cursorItem", None),
            _assert("source_exact", "inventory", "slots", {"index": slot, **match}, "matches_one")]

    def hotbar_step(self, identifier: str, slot: int, *, combined: bool = False) -> dict[str, Any]:
        # The approach snapshot only decides whether selection may be a no-op.
        # Its replacement asserts the live inventory read in this workflow;
        # a changed/missing selection stops before interaction, never guesses.
        if self.approach_selected == slot:
            return _assert(identifier, "inventory", "playerInventory.selectedHotbarSlot" if combined else "selectedHotbarSlot", slot)
        return _action(identifier, "mcb_hotbar", {"slot": slot})

    def material_after_read(self) -> dict[str, Any]:
        step = _read("material_after", "mcb_inventory", fields=["slots", "cursorItem", "totalSlots", "truncated"])
        step["omitEmptySlots"] = False
        return step

    async def verify_material(self, slot: int, *, report: dict[str, Any] | None = None, combined: bool = False,
        consumed: int = 0, inventory: dict[str, Any] | None = None) -> None:
        if self.tagged_context_inventory is not None and slot == self.args["blueprintHotbarSlot"]:
            _require(consumed == 0, "tagged_context_consumption_forbidden", "A context item is retained; resource deduction cannot consume it.")
            await self.read_tagged_context_inventory()
            return
        if inventory is None:
            inventory = self.value(report, "placed", "playerInventory") if combined else await self.call("inventory.inspect", {}) if report is None else {
                key: self.value(report, "material_after", key) for key in ("slots", "cursorItem", "totalSlots", "truncated")}
        actual = [entry for entry in inventory_slots(inventory, "") if entry["index"] == slot]
        record = self.materials[slot]
        if self.actual_survival:
            count = record["count"] - consumed
            _require(count >= 0 and inventory.get("cursorItem") is None and len(actual) == 1
                and (actual[0]["item"] is None if count == 0 else _same(actual[0]["item"], record["item"])
                    and actual[0]["item"].get("count") == count),
                "survival_material_delta_unconfirmed", "Real survival consumption must leave the exact remaining source, including an empty final slot.")
            record["count"] = count
            if self.survival_inventory is not None:
                self.survival_inventory[slot]["item"] = copy.deepcopy(actual[0]["item"])
            for move in self.material_moves:
                if move.get("temporaryIndex") == slot and (not self.initial_controller_seed_mode or move.get("restorationConfirmed") is not True):
                    move["count"] = count
                    if count == 0:
                        move.update(restorationConfirmed=True, status="consumed")
            return
        _require(inventory.get("cursorItem") is None and len(actual) == 1 and _same(actual[0]["item"], record["item"])
            and actual[0]["item"].get("count") == record["count"], "creative_material_changed", "Native creative placement must preserve the exact actual source stack and count.")

    async def supported_placement_approach(self, point: dict[str, Any], slot: int,
        record: dict[str, Any] | None = None, *, prepared_plan: dict[str, Any] | None = None) -> tuple[dict[str, Any], int, list[dict[str, Any]]]:
        """Bound fresh planning reads for an unchanged task-owned attachment."""
        observation = None
        first_error = None
        sync_deadline = min(self.deadline, time.monotonic() + .75)
        for attempt in range(8):
            try:
                sides = tuple(side for _, side in self._survival_frontier_binding) if self._survival_frontier_binding is not None else tuple(_VECTORS)
                options = {"prepared_plan": prepared_plan} if prepared_plan is not None and attempt == 0 else {}
                result = await self.planned_approach(point, intent="place_block", allowed_sides=sides, hotbar_slot=slot, **options)
                if observation is not None:
                    observation.update(status="completed", attempts=attempt + 1)
                return result
            except BridgeError as error:
                # This fixed native error can concern the target or attachment;
                # it does not identify which client block disagreed or why.
                if error.code != "target_unverified" or error.message != "Client and server approach block identities do not agree." \
                        or error.details.get("outcome_unknown") is not False or "approachReceipt" in error.details \
                        or record is not None and record.get("placementAttempted") is True:
                    if observation is not None:
                        observation.update(status="refused", attempts=attempt + 1, terminalError=error.as_dict())
                    raise
                supports = [support for support in self.supports if support.get("placementConfirmed") is True
                    and support.get("hasTile") is False and not support.get("cleanupAttempted")
                    and not support.get("outcomeUnknown") and sum(abs(support["position"][axis] - point[axis])
                        for axis in ("x", "y", "z")) == 1]
                if not supports:
                    if observation is not None:
                        observation.update(status="refused", attempts=attempt + 1, terminalError=error.as_dict())
                    raise
                if observation is None:
                    first_error = error
                    observation = {"phase": "supported_placement_sync_observation", "status": "pending", "readOnly": True,
                        "reason": "client_server_approach_identity_disagreement", "target": _xyz(point),
                        "firstError": error.as_dict(), "observations": [],
                        "scope": "Fresh complete placement planning reads before dispatch only; exact disagreement location and cause remain unknown. No chat, placement or activation replay."}
                    self.phases.append(observation)
                sample = {"attempt": attempt + 1, "error": error.as_dict()}
                observation["observations"].append(sample)
                try:
                    before = await self.blocks([point, *(support["position"] for support in supports)])
                    sample["serverBlocks"] = list(before.values())
                    _require(all(type(block.get(axis)) is int for block in before.values() for axis in ("x", "y", "z", "dimension"))
                        and _block_identity(before[_key(point)]) == {"registryName": "minecraft:air", "metadata": 0}
                        and "tileClass" not in before[_key(point)]
                        and all(_block_identity(before[_key(support["position"])]) == support["block"]
                            and "tileClass" not in before[_key(support["position"])] for support in supports),
                        "supported_placement_changed", "A planning sync observation needs exact server air and every unchanged confirmed task-owned adjacent support without a tile.")
                except (BridgeError, TimeoutError) as changed:
                    observation.update(status="refused", attempts=attempt + 1,
                        revalidationError=changed.as_dict() if isinstance(changed, BridgeError) else {"code": "timeout"})
                    first_error.details.update(reason="supported_placement_sync_refused", syncPendingObservation=observation)
                    raise first_error from changed
                if attempt == 7 or time.monotonic() + .05 >= sync_deadline:
                    observation.update(status="unconfirmed", attempts=attempt + 1)
                    first_error.details.update(reason="supported_placement_sync_unconfirmed", syncPendingObservation=observation)
                    raise first_error
                with anyio.fail_after(self.remaining() / 1000):
                    await anyio.sleep(.05)

    async def place(self, point: dict[str, Any], slot: int, phase: str, record: dict[str, Any] | None = None, *, jump_underfoot: bool = False) -> dict[str, Any]:
        self.phase = phase
        self._planned_placement_attachment = None
        handoff, self._frontier_plan_handoff = self._frontier_plan_handoff, None
        prepared_plan = None
        if handoff is not None and self._survival_frontier_binding is not None \
                and handoff["rpcEpoch"] == self.tracked_client.rpc_epoch and time.monotonic() <= handoff["expires"]:
            _require(handoff["point"] == self.point(point) and handoff["slot"] == slot
                and handoff["sides"] == tuple(side for _, side in self._survival_frontier_binding),
                "native_approach_handoff_changed", "An immediate frontier handoff must retain its exact target, source and permitted attachment faces.")
            prepared_plan = handoff["plan"]
        if self._survival_frontier_binding is not None:
            require_attachment_capability(self.approach_health)
            _require(self.native_approach_available("place_block"), "placement_attachment_plan_unavailable",
                "Every survival frontier placement requires its exact native planner attachment before mutation.")
        if jump_underfoot:
            version = (self.approach_health or {}).get("jumpPlacementVersion")
            _require(self.actual_survival and not self.survival_flight and type(version) is int and version == 1
                and self._survival_frontier_binding is None and self._controller_placement_pose is None,
                "jump_placement_unavailable", "Underfoot structural seeds require the exact native jump/accounting contract and no ordinary placement/facing handoff.")
            attachment, side, steps = {**self.point(point), "y": point["y"] - 1}, 1, []
        elif self.native_approach_available("place_block"):
            options = {"prepared_plan": prepared_plan} if prepared_plan is not None else {}
            attachment, side, steps = await self.supported_placement_approach(point, slot, record, **options)
        else:
            targets = await self.attachments(point, require_air=True)
            _require(bool(targets), "attachment_unavailable", "Normal player placement needs an actual neighbouring attachment.")
            attachment, side, steps = await self.approach(targets, center=point)
        native_place = "player.place_block" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("player.place_block") is True
        _require(not self.actual_survival or native_place, "survival_server_placement_unavailable",
            "Actual survival placement requires native server-before/after inventory accounting; a local accepted right-click is insufficient.")
        after_version = (self.approach_health or {}).get("placementAfterObservationVersion")
        if "placementAfterObservationVersion" in (self.approach_health or {}):
            _require(type(after_version) is int and after_version == 1, "placement_after_version_unavailable",
                "An advertised placement AFTER observation requires the exact typed native version.")
        native_after = self.actual_survival and native_place and type(after_version) is int and after_version == 1
        attachment_binding = None
        if self._survival_frontier_binding is not None:
            attachment_binding = bind_placement_attachment(self.approach_health, self.point(point),
                self._planned_placement_attachment, self._survival_frontier_binding)
            _require(native_place and all(attachment[axis] == attachment_binding[axis] for axis in ("x", "y", "z"))
                and side == attachment_binding["side"], "placement_attachment_plan_changed",
                "The exact selected native frontier proof must retain this placement's attachment.")
        combined = self.combined_inventory and self._workflow_discovery is not None
        # An explicit frontier binding is retained through the real native click.
        # Without a binding, the existing helper chooses its actual client face.
        # Capability absence alone chooses legacy; a dispatched failure never
        # falls back to another mutation or repeats the attempted placement.
        action = _action("place", "mcb_place_block", {**self.point(point), "hotbarSlot": slot,
            "expectedWorld": self.args["expectedWorld"], "replace": False}) if native_place else \
            _action("place", "mcb_interact", {"action": "use", **attachment, "side": side, "sneaking": True})
        if attachment_binding is not None:
            action["args"]["attachmentBinding"] = attachment_binding
        if jump_underfoot:
            action["args"]["jumpUnderfoot"] = True
        if self.actual_survival:
            action["fields"] = ["nativeInvoked", "attachment", "endingView", "actualSurvival", "serverConfirmed",
                "serverConsumptionConfirmed", "serverSourceConsumption", "serverBefore", "serverAfter", "serverTarget"]
            action["omitEmptySlots"] = False
            if jump_underfoot:
                action["fields"].extend(["jumpPlacementVersion", "jumpPlacementConfirmed", "inputKeysReleased", "clientAirObserved", "serverAirObserved"])
        if self._controller_placement_pose is not None and self._controller_placement_pose.get("groundedBootstrap") is True:
            action["args"]["expectedPlacementFacing"] = self._controller_placement_pose["direction"]
            action["fields"].extend(["expectedPlacementFacing", "clientPlacementFacingVerified", "serverPlacementFacingVerified"])
        if native_after:
            action["fields"].append("serverPlacementObservation")
        if attachment_binding is not None:
            action.setdefault("fields", []).extend(["attachmentBinding", "attachmentBindingVerified", "clientAttachmentBindingVerified",
                "serverAttachmentBindingVerified", "allPlayerSlots", "fullNbtConservation"])
        steps.extend(self.material_guards(slot, point=point if combined else None))
        if combined:
            steps.append(_assert("target_air", "inventory", "results.0.data.registryName", "minecraft:air"))
        else:
            steps.extend([_read("target", "mcb_world_inspect", self.point(point), ["registryName"]),
                _assert("target_air", "target", "registryName", "minecraft:air")])
        # Native placement selects the guarded source within its own call.
        # Retain the live no-op assertion; legacy still needs explicit selection.
        if not native_place or self.approach_selected == slot:
            steps.append(self.hotbar_step("source_hotbar", slot, combined=combined))
        after_steps = [
            _wait("placed", "mcb_world_inspect_many", self.combined_read_args(point), "results.0.data.registryName", "minecraft:air", operator="not_equals",
                fields=["results", "source", "dimension", "playerInventory", "playerInventoryObservation"]) if combined else
            _wait("placed", "mcb_world_inspect", self.point(point), "registryName", "minecraft:air", operator="not_equals",
                fields=["registryName", "metadata", "gregtech", "source", "dimension", "tileClass", "x", "y", "z", "inventory", "fluids"])]
        after_steps[-1]["omitEmptySlots"] = False
        # A missing placement is not a running recipe. Bound synchronization so
        # one acknowledged click cannot exhaust the whole construction budget.
        after_steps[-1].update(timeout_ms=min(2000, self.remaining()), interval_ms=100)
        if self._workflow_discovery is not None and not combined:
            after_steps.append(self.material_after_read())
        steps.append(action)
        if not native_after:
            steps.extend(after_steps)
        constraint = self._controller_placement_pose
        if constraint is not None:
            from .workflows import _WorkflowSnapshotChecks
            _require(self._workflow_discovery is not None, "controller_placement_guard_unavailable",
                "Constrained placement requires native sealed discovery and the actual placement BEFORE pose guard.")
            rules = steps.snapshot_checks.rules if type(steps) is _ApproachSteps else ()
            guarded = _ApproachSteps(_WorkflowSnapshotChecks(self._workflow_discovery,
                rules + (("place", self.controller_pose_fields(constraint["feet"])),)))
            guarded.extend(steps)
            steps = guarded
        if record is not None:
            binding = record.get("supplyBinding")
            if binding is not None:
                _require(binding["sourceHotbarSlot"] == slot and binding["sourceStackCount"] == self.materials[slot]["count"]
                    and not binding.get("placementAttempted"), "port_supply_binding_changed",
                    "Each exact port supply allocation may attempt its own native target once; source bindings cannot be reused as a different allocation.")
                binding["placementAttempted"] = True
            record.update(placementAttempted=True, nativePath="player.place_block(native_client_attachment, sneaking=true)" if native_place
                else "player.interact(use, exact_attachment_face, sneaking=true)", nativePlaceBlockUsed=native_place)
            if not native_place:
                record["attachment"] = {**attachment, "side": side}
        report = await self.workflow(phase, steps)
        material_checked = False
        if self.actual_survival:
            native = {key: self.value(report, "place", key) for key in
                ("actualSurvival", "serverConfirmed", "serverConsumptionConfirmed", "serverSourceConsumption",
                 "serverBefore", "serverAfter", "serverTarget")}
            _require(native["actualSurvival"] is True and native["serverConfirmed"] is True
                and native["serverConsumptionConfirmed"] is True and type(native["serverSourceConsumption"]) is int
                and native["serverSourceConsumption"] == 1,
                "survival_server_placement_unconfirmed", "The actual native survival placement must confirm its authoritative server result.")
            deductions = _survival_deductions(native["serverBefore"], native["serverAfter"])
            item = self.materials[slot]["item"]
            target = native["serverTarget"]
            _require(isinstance(target, dict) and target.get("source") == "integrated_server"
                and type(target.get("dimension")) is int and target["dimension"] == self.dimension
                and all(type(target.get(axis)) is int and target[axis] == point[axis] for axis in ("x", "y", "z"))
                and target.get("registryName") == item["registryName"]
                and target.get("gregtech", {}).get("metaTileId", target.get("metadata")) == item["metadata"],
                "survival_server_target_unconfirmed", "The authoritative placed target must retain the exact task coordinates and source block/meta-tile identity.")
            _require(len(deductions) == 1 and deductions[0]["playerInventoryIndex"] == slot
                and deductions[0]["count"] == 1 and deductions[0]["sourceCountBefore"] == self.materials[slot]["count"]
                and all(deductions[0][key] == value for key, value in item.items()),
                "survival_server_material_delta_unconfirmed", "Exactly one real matching source item must be consumed; every unrelated server slot must remain unchanged.")
            self.accept_tagged_context_native_inventory(native["serverBefore"], native["serverAfter"])
            self.survival_material_ledger.append({"phase": phase, "position": _xyz(point), "deductions": deductions,
                **({"sourceType": "actual_player_inventory", "sourceScope": "controller_ports_permanent_seeds"}
                    if self.survival_ae_source is not None else {})})
            # Initial declared seeds have their own explicit reservation-chain
            # accounting in place_controller's finally block. Other native
            # placements must also debit confirmed nested whole-stack borrows.
            if record is None or "sourceMoveLedgerIndex" not in record:
                self.account_survival_inventory_moves(deductions)
            if self.survival_ae_source is not None:
                self.survival_ae_player_inventory = copy.deepcopy(_survival_inventory_entries(native["serverAfter"]))
            if record is not None:
                record["survivalMaterialLedgerIndex"] = len(self.survival_material_ledger) - 1
                record["serverConsumptionConfirmed"] = True
            if native_after:
                # Account for the confirmed full-NBT deduction before judging
                # the optional observation. A malformed complete proof cannot
                # erase real consumption or cause a placement replay.
                await self.verify_material(slot, consumed=1, inventory=native["serverAfter"])
                material_checked = True
        if jump_underfoot:
            _require(type(self.value(report, "place", "jumpPlacementVersion")) is int
                and self.value(report, "place", "jumpPlacementVersion") == 1
                and all(self.value(report, "place", name) is True for name in
                    ("jumpPlacementConfirmed", "inputKeysReleased", "clientAirObserved", "serverAirObserved")),
                "jump_placement_unconfirmed", "Material accounting is retained, but a jump also needs actual dual airborne/landing and released-input evidence.")
            if record is not None:
                record.update(jumpPlacementConfirmed=True, nativePath="player.place_block(jumpUnderfoot=true)")
        if native_place:
            actual_attachment = self.value(report, "place", "attachment")
            actual_side = actual_attachment.get("side") if isinstance(actual_attachment, dict) else None
            _require(self.value(report, "place", "nativeInvoked") is True and type(actual_side) is int and actual_side in _VECTORS
                and all(type(actual_attachment.get(axis)) is int
                    and actual_attachment[axis] + _VECTORS[actual_side][index] == point[axis]
                    for index, axis in enumerate(("x", "y", "z"))),
                "native_attachment_unavailable", "The native placement receipt must retain its actual adjacent client attachment face; a public precheck is never substituted.")
            if record is not None:
                record["attachment"] = dict(actual_attachment)
        if constraint is not None:
            if constraint.get("groundedBootstrap") is True:
                _require(self.value(report, "place", "expectedPlacementFacing") == constraint["direction"]
                    and self.value(report, "place", "clientPlacementFacingVerified") is True
                    and self.value(report, "place", "serverPlacementFacingVerified") is True,
                    "controller_placement_facing_unconfirmed", "Both native BEFORE views must confirm the requested front; never replay placement.")
            ending = self.value(report, "place", "endingView")
            if native_place:
                hit = {axis: actual_attachment.get("hit" + axis.upper()) for axis in ("x", "y", "z")}
                aimed = _placement_aim(constraint["eye"], hit)
                constraint.update(actualHit=hit, actualEndingView=dict(ending), reconstructedAimMatches=_placement_aim_matches(ending, aimed))
                # Native re-tracing uses float trigonometry; reconstructing an angle
                # from its rounded hit can differ even when the exact native BEFORE
                # client/server facing contract succeeded. Grounded bootstrap binds
                # those two native checks and still validates the final GT direction.
                if constraint.get("groundedBootstrap") is not True:
                    _require(constraint["reconstructedAimMatches"], "controller_placement_view_changed",
                        "The actual placement receipt must retain the view derived from its own native hit point.")
                constraint["actualHit"] = hit
            _require(_normal_gt_placement_facing(ending) == constraint["direction"],
                "controller_placement_view_changed", "The actual native click must retain the source placement direction; it is never replayed.")
            constraint.update(actualEndingView=dict(ending), nativePlaceBlockUsed=native_place, actualClickDirectionVerified=True)
        observed = None
        if native_after:
            rows = [step for step in report.get("steps", []) if step.get("id") == "place"]
            values = rows[0].get("observation", {}).get("values", {}) if len(rows) == 1 else {}
            observed = self.placement_after_observation(values.get("serverPlacementObservation"), point, native["serverTarget"])
            if observed is None:
                fallback = await self.workflow(phase + "_after_observation_fallback", after_steps)
                report = {**report, "steps": report["steps"] + fallback["steps"]}
            else:
                self.phases.append({"phase": phase + "_native_after_observation", "status": "completed", "version": 1,
                    "worldTick": observed["worldTick"], "source": "native_place_server_after",
                    "scope": "Complete actual public target and exposed storage only; formation and recipe completion are not inferred."})
        if observed is None:
            observed = self.workflow_block(report, "placed", point)
            await self.verify_material(slot, report=report if self._workflow_discovery is not None else None, combined=combined,
                consumed=0 if material_checked else 1 if self.actual_survival else 0)
        item = self.materials[slot]["item"]
        expected_metadata = observed.get("gregtech", {}).get("metaTileId", observed.get("metadata"))
        _require(observed["registryName"] == item["registryName"] and expected_metadata == item["metadata"],
            "native_placed_identity_mismatch", "The real server block/meta-tile identity must match the exact native source item.")
        if record is not None:
            record.update(placementConfirmed=True, block=_block_identity(observed), hasTile="tileClass" in observed)
            if "supplyBinding" in record:
                record["supplyBinding"].update(placementConfirmed=True, sourceIdentityAndCountVerified=True)
        if self._survival_frontier_binding is not None and not any(_xyz(actual_attachment) == _xyz(target) and actual_side == side
                for target, side in self._survival_frontier_binding):
            if record is not None:
                record.update(frontierVerified=False, frontierRejectedAfterNative=True)
            raise _Unavailable("survival_port_frontier_changed",
                "The confirmed native placement used a different observed attachment; its real target and finite deduction are retained, with no replay or later construction.")
        if attachment_binding is not None:
            validate_attachment_receipt({key: self.value(report, "place", key) for key in
                ("attachmentBinding", "attachmentBindingVerified", "clientAttachmentBindingVerified", "serverAttachmentBindingVerified",
                 "allPlayerSlots", "fullNbtConservation", "serverConfirmed", "attachment")}, attachment_binding)
        return observed

    def placement_after_observation(self, observation: Any, point: dict[str, Any], native_target: dict[str, Any]) -> dict[str, Any] | None:
        if observation is None:
            return None
        reason = "placement_after_observation_unavailable"
        _require(isinstance(observation, dict) and type(observation.get("version")) is int and observation["version"] == 1
            and type(observation.get("complete")) is bool and type(observation.get("partial")) is bool
            and observation["complete"] is not observation["partial"], reason,
            "The optional native AFTER must distinguish complete target coverage from explicit partial/unavailable observations.")
        if observation["complete"] is False:
            return None
        coverage, data = observation.get("coverage"), observation.get("data")
        actual_world = (self.position_snapshot or {}).get("world", {}).get("worldName", self.args["expectedWorld"])
        _require(observation.get("source") == "integrated_server" and observation.get("sessionId") == self.args["expectedSessionId"]
            and isinstance(self.survival_player_id, str) and bool(self.survival_player_id)
            and observation.get("playerId") == self.survival_player_id
            and (self.survival_control_player_id is None or observation["playerId"] == self.survival_control_player_id)
            and observation.get("worldName") == actual_world and observation.get("phase") == "native_place_server_after"
            and type(observation.get("dimension")) is int and observation["dimension"] == self.dimension
            and type(observation.get("worldTick")) is int and observation["worldTick"] >= 0
            and observation.get("loaded") is True and isinstance(observation.get("target"), dict)
            and set(observation["target"]) == {"x", "y", "z"}
            and all(type(observation["target"].get(axis)) is int and observation["target"][axis] == point[axis] for axis in ("x", "y", "z"))
            and "error" not in observation and isinstance(data, dict) and isinstance(coverage, dict), reason,
            "A claimed complete AFTER must bind this same actual native player, session, world, dimension, target and owning server phase/tick.")
        limits = {"byteLimit": 49152, "inventorySlotLimit": 256, "fluidTankLimit": 32, "softBudgetMicros": 4000}
        _require(coverage.get("identity") == "observed" and coverage.get("ownerUnchanged") is True
            and coverage.get("budgetLimited") is False and coverage.get("tile") in ("present", "absent")
            and coverage.get("inventory") in ("observed", "not_exposed") and coverage.get("fluids") in ("observed", "not_exposed")
            and coverage.get("gregtech") in ("identity_observed", "not_exposed")
            and all(type(coverage.get(key)) is int and coverage[key] == value for key, value in limits.items())
            and type(coverage.get("elapsedMicros")) is int and coverage["elapsedMicros"] >= 0
            and type(coverage.get("dataBytes")) is int and 0 < coverage["dataBytes"] <= limits["byteLimit"]
            and len(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= limits["byteLimit"], reason,
            "Complete coverage requires known public identity and all exposed storage within the exact advertised native budgets.")
        _require(data.get("source") == "integrated_server" and data.get("loaded") is True
            and type(data.get("worldTick")) is int and data["worldTick"] == observation["worldTick"]
            and type(data.get("dimension")) is int and data["dimension"] == self.dimension
            and all(type(data.get(axis)) is int and data[axis] == point[axis] for axis in ("x", "y", "z"))
            and _block_identity(data) == _block_identity(native_target)
            and (isinstance(data.get("tileClass"), str) and bool(data["tileClass"]) if coverage["tile"] == "present" else "tileClass" not in data), reason,
            "The complete actual inspect payload must retain the original confirmed native block identity and exact AFTER target/tick.")
        _require(coverage["tile"] != "absent" or all(coverage[key] == "not_exposed" for key in ("inventory", "fluids", "gregtech")),
            reason, "A tile-free ordinary block cannot acquire invented public storage or a tile identity.")
        if coverage["gregtech"] == "identity_observed":
            gt, native_gt = data.get("gregtech"), native_target.get("gregtech")
            _require(isinstance(gt, dict) and isinstance(native_gt, dict) and type(gt.get("metaTileId")) is int
                and isinstance(gt.get("metaClass"), str) and bool(gt["metaClass"])
                and all(type(native_gt.get(key)) is type(gt[key]) and native_gt[key] == gt[key] for key in ("metaTileId", "metaClass")),
                reason, "Complete GregTech coverage must retain the same actual confirmed public meta ID/class.")
        else:
            _require("gregtech" not in data and "gregtech" not in native_target, reason, "A recognized GregTech identity cannot be silently omitted.")
        for field, total_key, rows_key, observed_key, limit in (("inventory", "totalSlots", "slots", "observedSlots", 256),
                ("fluids", "totalTanks", "tanks", "observedTanks", 32)):
            if coverage[field] == "not_exposed":
                _require(field not in data, reason, "Unexposed storage must remain absent; it is never invented as an empty inventory or tank list.")
                continue
            storage = data.get(field)
            _require(isinstance(storage, dict) and storage.get("truncated") is False and type(storage.get(total_key)) is int
                and 0 <= storage[total_key] <= limit and isinstance(storage.get(rows_key), list)
                and len(storage[rows_key]) == storage[total_key]
                and type(coverage.get(total_key)) is int and coverage[total_key] == storage[total_key]
                and type(coverage.get(observed_key)) is int and coverage[observed_key] == storage[total_key]
                and all(isinstance(row, dict) and type(row.get("index")) is int
                    and ("item" in row if field == "inventory" else True) for row in storage[rows_key])
                and {row["index"] for row in storage[rows_key]} == set(range(storage[total_key])), reason,
                "Every actually exposed public storage entry must retain complete, unique finite coverage.")
            if field == "inventory":
                _require(all(row["item"] is None or isinstance(row["item"], dict)
                    and isinstance(row["item"].get("registryName"), str) and bool(row["item"]["registryName"])
                    and type(row["item"].get("metadata")) is int and type(row["item"].get("count")) is int
                    and row["item"]["count"] > 0 and type(row["item"].get("hasTag")) is bool for row in storage[rows_key]),
                    reason, "Every exposed occupied slot must retain its actual typed item identity and positive quantity.")
            else:
                _require(all(type(row.get("capacity")) is int and row["capacity"] >= 0
                    and ("fluid" not in row and "amount" not in row or isinstance(row.get("fluid"), str) and bool(row["fluid"])
                        and type(row.get("amount")) is int and 0 < row["amount"] <= row["capacity"]) for row in storage[rows_key]),
                    reason, "Every exposed tank must retain its actual finite capacity and known empty or positive contents.")
        return data

    async def owned_block_approach(self, point: dict[str, Any], expected: dict[str, Any]) -> tuple[dict[str, Any], int, list[dict[str, Any]]]:
        """Reobserve one known dual-side disagreement; never retry a mutation."""
        observation = None
        sync_deadline = min(self.deadline, time.monotonic() + .75)
        for attempt in range(8):
            try:
                result = await self.approach([(point, side) for side in (2, 3, 4, 5, 1, 0)],
                    intent="attack", target_identity=expected)
                if observation is not None:
                    observation.update(status="completed", attempts=attempt + 1)
                return result
            except BridgeError as error:
                # Current native DTOs expose this fixed identity comparison error,
                # not a generic retryable target_unverified contract.
                if error.code != "target_unverified" or error.message != "Client and server approach block identities do not agree." \
                        or error.details.get("outcome_unknown") is not False:
                    raise
                before = await self.block(point)
                _require(_block_identity(before) == expected and "tileClass" not in before,
                    "owned_block_changed", "A sync observation requires the same confirmed task-owned server casing without a tile.")
                if observation is None:
                    observation = {"phase": "owned_target_sync_observation", "status": "pending", "readOnly": True,
                        "reason": "client_server_owned_target_identity_disagreement", "target": _xyz(point),
                        "expectedIdentity": dict(expected), "firstError": error.as_dict(), "observations": [],
                        "scope": "Fresh full approach reads only; no blueprint, dig, placement or grant replay."}
                    self.phases.append(observation)
                observation["observations"].append({"attempt": attempt + 1, "serverIdentity": _block_identity(before),
                    "serverHasTile": False, "error": error.as_dict()})
                if attempt == 7 or time.monotonic() + .05 >= sync_deadline:
                    observation.update(status="unconfirmed", attempts=attempt + 1)
                    error.details.update(reason="owned_target_sync_unconfirmed", syncPendingObservation=observation)
                    raise
                with anyio.fail_after(self.remaining() / 1000):
                    await anyio.sleep(.05)

    def record_native_action_dispatch(self, operation: str, args: dict[str, Any], receipt: dict[str, Any]) -> None:
        port = getattr(self, "pending_dig_port", None)
        if operation == "player.interact" and args.get("action") == "attack" and port is not None \
                and all(type(args.get(axis)) is int and args[axis] == port["position"][axis] for axis in ("x", "y", "z")):
            port["digAttempted"] = True
            port["digDispatchReceipt"] = dict(receipt)

    async def dig_owned(self, point: dict[str, Any], expected: dict[str, Any], phase: str, record: dict[str, Any] | None = None,
        *, before: dict[str, Any] | None = None) -> None:
        before = await self.block(point) if before is None else before
        _require(_block_identity(before) == expected and "tileClass" not in before, "owned_block_changed", "Break only an unchanged, task-owned confirmed casing/support without a tile entity.")
        _, side, steps = await self.owned_block_approach(point, expected)
        steps.extend([_read("inventory", "mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot"]),
            _assert("empty_hand", "inventory", "slots", {"index": self.empty_slot, "item": None}, "matches_one"),
            _assert("empty_cursor", "inventory", "cursorItem", None), self.hotbar_step("hand", self.empty_slot),
            _read("block", "mcb_world_inspect", self.point(point), ["registryName", "metadata", "tileClass"]),
            _assert("registry", "block", "registryName", expected["registryName"]), _assert("metadata", "block", "metadata", expected["metadata"]),
            _assert("no_tile", "block", "tileClass", False, "exists"),
            _action("dig", "mcb_interact", {"action": "attack", **_xyz(point), "side": side}),
            _wait("air", "mcb_world_inspect", self.point(point), "registryName", "minecraft:air",
                fields=["registryName", "metadata", "source", "dimension", "x", "y", "z"])])
        if record is not None:
            record["cleanupAttempted"] = True
        report = await self.workflow(phase, steps)
        _require(self.workflow_block(report, "air", point)["registryName"] == "minecraft:air", "dig_not_confirmed", "Native digging needs actual server air confirmation.")
        if record is not None:
            record["cleanupConfirmed"] = True

    async def cleanup_supports(self) -> None:
        for index, record in reversed(list(enumerate(self.supports))):
            if record.get("permanentStructureSeed") is True:
                continue
            if record.get("placementConfirmed") is True and not record.get("cleanupAttempted") and not record.get("hasTile"):
                if record.get("nativePath") == "developer_command":
                    point = record["position"]
                    before = await self.block(point)
                    _require(_block_identity(before) == record["block"] and "tileClass" not in before,
                        "owned_block_changed", "Remove only this task's unchanged confirmed temporary support without a tile.")
                    record["cleanupAttempted"] = True
                    command = f"/setblock {point['x']} {point['y']} {point['z']} minecraft:air 0 replace"
                    await self.workflow("support_cleanup_" + str(index), [_action("remove", "mcb_chat", {"message": command}),
                        _wait("air", "mcb_world_inspect", self.point(point), "registryName", "minecraft:air")])
                    record["cleanupConfirmed"] = True
                else:
                    await self.dig_owned(record["position"], record["block"], "support_cleanup_" + str(index), record)

    async def command_supports(self, positions: list[dict[str, Any]]) -> None:
        observed = await self.blocks(positions)
        _require(all(block["registryName"] == "minecraft:air" for block in observed.values()),
            "support_target_changed", "Every explicit temporary support coordinate must still be authoritative server air.")
        for point in positions:
            self.supports.append({"position": point, "placementAttempted": True, "nativePath": "developer_command", "hasTile": False})
        if len(positions) == 1:
            point = positions[0]
            commands = [f"/setblock {point['x']} {point['y']} {point['z']} minecraft:stone 0 keep"]
        else:
            point = positions[0]
            coordinate = f"{point['x']},{point['y']},{point['z']}"
            last = positions[-1]
            commands = ["//sel", "//sel extend", "//pos1 " + coordinate,
                f"//pos2 {last['x']},{last['y']},{last['z']}", "//replace air stone"]
        steps = [_read("context", "mcb_snapshot", fields=["world.dimension"]),
            _assert("dimension", "context", "world.dimension", self.dimension)]
        steps.extend(_action("command_" + str(index), "mcb_chat", {"message": command}) for index, command in enumerate(commands))
        for index, point in enumerate(positions):
            steps.extend([_wait("support_" + str(index), "mcb_world_inspect", self.point(point), "registryName", "minecraft:stone",
                fields=["registryName", "metadata", "tileClass"]),
                _assert("stone_metadata_" + str(index), "support_" + str(index), "metadata", 0),
                _assert("no_tile_" + str(index), "support_" + str(index), "tileClass", False, "exists")])
        prepared = False
        failed_action = None
        try:
            await self.workflow("temporary_support_prepare", steps)
            prepared = True
            for record in self.supports[-len(positions):]:
                record.update(placementConfirmed=True, block={"registryName": "minecraft:stone", "metadata": 0})
        except (BridgeError, TimeoutError):
            failed_action = self.last_action
            raise
        finally:
            if len(positions) > 1:
                self.selection_cleanup = {"attempted": True, "confirmed": False}
                with anyio.CancelScope(shield=True):
                    try:
                        cleanup_steps = [_read("context", "mcb_snapshot", fields=["world.dimension"]),
                            _assert("dimension", "context", "world.dimension", self.dimension),
                            _action("clear_selection", "mcb_chat", {"message": "//sel"})]
                        feedback = "client.messages" in self.capabilities if isinstance(self.capabilities, list) else self.capabilities.get("client.messages") is True
                        if feedback:
                            cleanup_steps.insert(2, _read("messages_before", "mcb_messages", {"limit": 1}, ["lastSequence"]))
                            wait = _wait("selection_cleared", "mcb_messages", {"query": "Selection cleared.", "limit": 1},
                                "messages.0.text", "Selection cleared.", operator="contains", fields=["messages", "historyGap", "hasMore"])
                            wait["bind"] = {"since": {"step": "messages_before", "path": "lastSequence"}}
                            cleanup_steps.append(wait)
                        await self.workflow("worldedit_selection_clear", cleanup_steps, emergency=True)
                        self.selection_cleanup.update(acknowledged=True, confirmed=False,
                            completion="Native //sel acknowledged; no public selection-state reader is available.")
                        if feedback:
                            self.selection_cleanup.update(confirmed=True, source="Fresh native Selection cleared. feedback after the pre-command lastSequence.")
                    except (BridgeError, TimeoutError) as error:
                        self.selection_cleanup["error"] = error.as_dict() if isinstance(error, BridgeError) else {"code": "SELECTION_CLEANUP_TIMEOUT"}
                        if prepared:
                            raise
                if failed_action is not None:
                    self.last_action = failed_action

    def controller_pose_fields(self, feet: dict[str, Any]) -> tuple[tuple[str, Any], ...]:
        if self.actual_survival:
            return (("player.serverFlight.available", True), ("player.serverFlight.flying", self.survival_flight),
                ("player.serverFlight.creative", False), ("player.serverFlight.dimension", self.dimension),
                ("player.flying", self.survival_flight)) \
                + tuple(("player." + ("feetY" if axis == "y" else axis), feet[axis]) for axis in ("x", "y", "z"))
        return (("player.serverFlight.available", True), ("player.serverFlight.flying", True),
            ("player.serverFlight.allowFlying", True), ("player.serverFlight.dimension", self.dimension), ("player.flying", True)) \
            + tuple(("player." + ("feetY" if axis == "y" else axis), feet[axis]) for axis in ("x", "y", "z"))

    def controller_pose_constraint(self) -> dict[str, Any] | None:
        if "controllerFacing" in self.args:
            _require(self.actual_survival and self.bootstrap_position is not None and self.bootstrap_adjacent is not None
                and self.args.get("movementStrategy", "walking") == "walking" and not self.survival_flight,
                "controller_placement_stance_unsupported", "This facing-bound survival bootstrap requires its declared grounded walking posture.")
            if self.placement_plan is not None:
                alignment = self.placement_plan["geometry"]["alignment"]
                _require(alignment.get("direction") == self.args["controllerFacing"]
                    and str(alignment.get("rotation", "")).upper() == "NORMAL"
                    and str(alignment.get("flip", "")).upper() == "NONE",
                    "controller_placement_alignment_unsupported", "The grounded declared facing must match the actual source alignment before any target writes.")
            front, seed, declaration = _FRONT[self.args["controllerFacing"]], self.bootstrap_position, self.bootstrap_adjacent
            _require(front[0] * declaration["offsetX"] + front[2] * declaration["offsetZ"] == 0,
                "controller_placement_stance_unsupported", "The permanent adjacent seed must be perpendicular to the declared controller front.")
            feet = {"x": seed["x"] + .5 + .15 * (front[0] - declaration["offsetX"]),
                "y": seed["y"] + 1, "z": seed["z"] + .5 + .15 * (front[2] - declaration["offsetZ"])}
            return {"direction": self.args["controllerFacing"], "feet": feet, "groundedBootstrap": True,
                **({"alignment": copy.deepcopy(self.placement_plan["geometry"]["alignment"])} if self.placement_plan is not None else {}),
                "placementRule": "GTUtility.getSideFromPlayerFacing(actual_native_aim)", "noPlacementPlannerMovement": True}
        if self.placement_plan is None or self.placement_plan["geometry"].get("geometryMode") != "native_visits":
            return None
        alignment = self.placement_plan["geometry"]["alignment"]
        _require(self.args["controllerItem"]["registryName"] == "gregtech:gt.blockmachines"
            and alignment.get("direction") in _FRONT and isinstance(alignment.get("rotation"), str)
            and alignment["rotation"].upper() == "NORMAL" and isinstance(alignment.get("flip"), str) and alignment["flip"].upper() == "NONE",
            "controller_placement_alignment_unsupported", "Native-visit placement currently binds public normal GT horizontal placement only; other alignments are unavailable before target writes.")
        _require(self._native_workflow_runner and self._workflow_discovery is not None,
            "controller_placement_guard_unavailable", "Native-visit pose binding requires the native sealed workflow and its fresh placement BEFORE guard.")
        point, front = self.args["controller"], _FRONT[alignment["direction"]]
        feet = {"x": point["x"] + .5 + 2 * front[0], "y": point["y"] - 1, "z": point["z"] + .5 + 2 * front[2]}
        _require(feet["y"] >= 2 and all(-29_999_996 <= feet[axis] <= 29_999_996 for axis in ("x", "z")),
            "controller_placement_stance_unsupported", "This conservative native placement stance needs legal clear flight feet above the floor before target writes.")
        return {"direction": alignment["direction"], "alignment": dict(alignment), "feet": feet,
            "placementRule": "GTUtility.getSideFromPlayerFacing(actual_native_aim)", "noPlacementPlannerMovement": True}

    async def prepare_controller_pose(self, constraint: dict[str, Any]) -> None:
        if constraint.get("groundedBootstrap") is True:
            await self.bootstrap_navigation(constraint["feet"], "walking", "controller_pose_prepare")
            current = await self.snapshot()
            self.require_flight(current)
            # Navigation has a small native arrival tolerance. Bind the actual stopped pose,
            # then let current-only full-body/ray planning prove the constrained click there.
            constraint["plannedFeet"] = dict(constraint["feet"])
            constraint["feet"] = {axis: current["player"]["feetY" if axis == "y" else axis] for axis in ("x", "y", "z")}
            constraint.update(stancePrepared=True, teleportAttempted=False)
            return
        from .workflows import _WorkflowSnapshotChecks
        snapshot = await self.snapshot()
        self.require_flight(snapshot)
        current = {"x": snapshot["player"].get("x"), "y": snapshot["player"].get("feetY"), "z": snapshot["player"].get("z")}
        _require(all(type(value) in (int, float) and math.isfinite(value) for value in current.values()),
            "controller_placement_pose_unavailable", "Safe stance movement requires the complete actual finite current feet.")
        feet = constraint["feet"]
        body = {axis: math.floor(feet[axis]) for axis in ("x", "y", "z")}
        spatial = await self.call("world.spatial", {"mode": "positions", "positions": [body], "dimension": self.dimension, "limit": 1})
        _require(spatial.get("source") == "integrated_server" and type(spatial.get("dimension")) is int
            and spatial["dimension"] == self.dimension and spatial.get("hasMore") is False
            and spatial.get("byteLimited") is False and "nextOffset" in spatial and spatial["nextOffset"] is None
            and self.clear_body(spatial.get("positions"), body), "controller_placement_body_unavailable",
            "The prescribed stance needs a complete loaded occupiable actual body without partial entities, liquid or exposed hazards.")
        # Native teleport is acknowledged before client coordinates synchronize.
        # Retain flight on every wait sample; its exact XYZ condition must match
        # one complete snapshot before the later eight-field click BEFORE guard.
        steps = _ApproachSteps(_WorkflowSnapshotChecks(self._workflow_discovery,
            (("move", self.controller_pose_fields(current)), ("synced", self.controller_pose_fields(feet)[:5]))))
        if snapshot.get("screenOpen") is not False:
            steps.append(_action("close", "mcb_close_gui", {}))
        if current != feet:
            steps.extend([_action("move", "mcb_teleport", {**feet, "dimension": self.dimension,
                "expectedWorld": self.args["expectedWorld"], "requireFlying": True}),
                _wait("synced", "mcb_snapshot", {}, "player", {"x": feet["x"], "feetY": feet["y"], "z": feet["z"]}, operator="fields_equal")])
            await self.workflow("controller_pose_prepare", steps)
        elif steps:
            # No move is needed, but any open GUI still closes under the usual guard.
            await self.workflow("controller_pose_prepare", list(steps))
        constraint.update(bodyVerified=True, stancePrepared=True, teleportAttempted=current != feet)

    async def verify_controller_alignment(self, constraint: dict[str, Any]) -> None:
        point = self.args["controller"]
        if constraint.get("groundedBootstrap") is True:
            data = await self.call("world.structure", {**self.point(point), "limit": 1, "floorBlockY": self.floor})
            owner, alignment = data.get("controller", {}), data.get("alignment", {})
            _require(all(type(owner.get(axis)) is int and owner[axis] == point[axis] for axis in ("x", "y", "z"))
                and owner.get("dimension") == self.dimension and alignment.get("direction") == constraint["direction"]
                and str(alignment.get("rotation", "")).upper() == "NORMAL" and str(alignment.get("flip", "")).upper() == "NONE",
                "controller_placement_alignment_changed", "The placed controller must expose its declared normal horizontal alignment before hints; no placement is replayed.")
            constraint.update(freshControllerAlignmentVerified=True, observedController=dict(owner), observedAlignment=dict(alignment))
            return
        geometry = self.placement_plan["geometry"]
        data = await self.call("world.structure", {**self.point(point), "limit": 1, "floorBlockY": self.floor})
        owner = data.get("controller", {})
        _require(isinstance(owner, dict) and all(type(owner.get(axis)) is int and owner[axis] == point[axis] for axis in ("x", "y", "z"))
            and type(owner.get("dimension")) is int and owner["dimension"] == self.dimension
            and owner.get("metaClass") == geometry["sourceMetaClass"] and data.get("alignment") == constraint["alignment"],
            "controller_placement_alignment_changed", "The actual newly placed controller needs the exact complete source alignment and owner before any blueprint hint action; no click is replayed.")
        constraint.update(freshControllerAlignmentVerified=True, observedController=dict(owner), observedAlignment=dict(data["alignment"]))

    async def place_controller(self) -> None:
        point = self.args["controller"]
        constraint = self.controller_pose_constraint()
        if constraint is not None and self.placement_plan is not None:
            self.placement_plan["controllerPlacementPose"] = constraint
        if constraint is not None and constraint.get("groundedBootstrap") is True:
            self.bootstrap_evidence["controllerPlacementPose"] = constraint
        if self.placement_plan is not None:
            placement_arguments = {**self.args["placement"], "anchor": point,
                **{key: self.args[key] for key in ("expectedSessionId", "expectedWorld", "dimension")}, "timeout_ms": self.remaining()}
            self.placement_plan["beforeController"] = await revalidate_placement(self.tracked_client, placement_arguments,
                self.placement_plan, call=self.call, _discovery=self._workflow_discovery)
        if self.survival_seed_plan:
            if self.initial_controller_seed_mode:
                _require(not self._initial_seeds_attempted, "initial_seeds_already_attempted",
                    "Initial seed placement is a once-only task phase; partial or unknown placements are never replayed.")
                self._initial_seeds_attempted = True
            for seed in self.survival_seed_plan:
                if self.bootstrap_position is not None and seed["y"] == self.floor + 2:
                    await self.bootstrap_up()
                declaration = self.initial_seed_declarations.get(_key(seed))
                slot = await self.prepare_initial_seed_source(declaration) if declaration is not None else self.args["supportHotbarSlot"]
                source_item = declaration["item"] if declaration is not None else self.args["supportItem"]
                seed_phase = "survival_structure_seed_adjacent" if seed == self.bootstrap_position else "survival_structure_seed_" + str(seed["y"])
                record = {"position": dict(seed), "permanentStructureSeed": True, "seedPhase": seed_phase,
                    "sourceHotbarSlot": slot, "sourceItem": dict(source_item),
                    "originalTerrainFloor": self.floor, "refunded": False, "publicGroupMatched": False,
                    "seedOrigin": "caller_declared_adjacent_bootstrap" if seed == self.bootstrap_position else "caller_declared_initial_controller_column", "publicValidatedBeforePlacement": False}
                self.supports.append(record)
                if declaration is not None:
                    binding = self.initial_seed_sources[declaration["playerInventoryIndex"]]
                    record.update(offsetY=declaration["offsetY"], declaredPlayerInventoryIndex=declaration["playerInventoryIndex"],
                        sourcePlayerInventoryIndex=binding["sourceIndex"], sourceNbtIdentity="",
                        sourceMoveLedgerIndex=self._initial_seed_borrow["ledgerIndex"] if self._initial_seed_borrow is not None else None,
                        sourceReservationLedgerIndex=binding.get("reservationLedgerIndex"))
                try:
                    self.phase = seed_phase
                    await self.place(seed, slot, seed_phase, record)
                except (BridgeError, TimeoutError) as error:
                    if declaration is not None and (isinstance(error, TimeoutError) or error.details.get("outcome_unknown") is True
                            or self.phases and self.phases[-1].get("outcomeUnknown") is True):
                        record["outcomeUnknown"] = True
                    raise
                finally:
                    if declaration is not None and record.get("serverConsumptionConfirmed") is True:
                        self.account_initial_seed_deduction(record)
                _require(record.get("placementConfirmed") is True and record.get("serverConsumptionConfirmed") is True
                    and record.get("hasTile") is False and record["block"] == source_item,
                    "survival_structure_seed_unconfirmed", "Permanent support requires one actual finite native casing placement without a tile; it is not returned or silently replaced.")
                if declaration is not None:
                    await self.release_initial_seed_source()
            if self.initial_controller_seed_mode and self.controller_slot > 8:
                await self.retrieve_initial_controller()
        elif not await self.attachments(point):
            if self.support_mode == "command":
                # A known test coordinate needs only its immediate attachment,
                # even when layout planning placed the controller high above ground.
                positions = [{**point, "y": point["y"] - 1}]
            else:
                _require((self.support_mode == "command_column" or "supportItem" in self.args) and point["y"] - self.floor - 1 <= 3,
                    "temporary_support_unavailable", "Column preparation is bounded to three blocks; native-player mode also needs declared support materials.")
                positions = [{**point, "y": y} for y in range(self.floor + 1, point["y"])]
            if self.placement_plan is not None and self.placement_plan["geometry"].get("geometryMode") == "native_visits":
                reserved = self.placement_plan["reservedBounds"]
                regions = self.placement_plan.get("assemblyLayout", {}).get("reservedBoxes", [reserved])
                _require(all(any(all(region["min" + axis.upper()] <= support[axis] <= region["max" + axis.upper()]
                    for axis in ("x", "y", "z")) for region in regions) for support in positions), "placement_support_outside_reserved_box",
                    "Every task-prepared native attachment/support must remain inside the freshly proved complete reserved box; an unproved lower column is unavailable.")
            if self.support_mode != "player":
                await self.command_supports(positions)
            else:
                for support in positions:
                    record = {"position": support}
                    self.supports.append(record)
                    await self.place(support, self.args["supportHotbarSlot"], "support_place_" + str(support["y"]), record)
        if constraint is not None:
            await self.prepare_controller_pose(constraint)
        self._controller_placement_pose = constraint
        controller_record = {"position": dict(point)}
        self.phase = "controller_placement"
        try:
            self._placed_controller_observation = await self.place(point, self.controller_slot, "controller_place", controller_record)
        finally:
            self._controller_placement_pose = None
        if constraint is not None:
            await self.verify_controller_alignment(constraint)
        machine = await self.call("machine.inspect", self.point(point))
        _require(_same(machine.get("structureDescription", {}).get("controllerStack"), self.args["controllerItem"]),
            "controller_identity_unavailable", "The real public controller stack form must match the exact placed controller item.")
        if self.placement_plan is not None:
            self.placement_plan["controllerPlacementEvidence"] = copy.deepcopy(controller_record)
        await self.return_controller_stack("controller_empty_hand", close_after=True)
        await self.cleanup_supports()

    async def return_controller_stack(self, phase: str, *, close_after: bool) -> str | None:
        pending = [record for record in self.material_moves if record.get("movementDispatched") is True and record.get("restorationConfirmed") is not True]
        if not pending:
            return None
        _require(all(record.get("movementConfirmed") is True and record.get("outcomeUnknown") is not True
            and record.get("restorationAttempted") is not True for record in pending), "material_restoration_uncertain",
            "Uncertain or previously attempted material movement is never replayed.")
        screen_id, _ = await self.open_creative_player_inventory(phase)
        for index, record in enumerate(pending):
            record["restorationAttempted"] = True
            await self.move_native_player_stack(record, screen_id, record["temporaryIndex"], record["originalIndex"], phase + "_return_" + str(index),
                close_after=close_after and index == len(pending) - 1)
            record.update(restorationConfirmed=True, status="restored")
        return screen_id

    def validate_survival_build_context(self, value: dict[str, Any], slot: int) -> None:
        target = self.args["controller"]
        owner, actor, blueprint = (value.get(key) for key in ("controller", "actor", "blueprint"))
        _require(value.get("operation") == "structure.survival_build" and value.get("actualSurvival") is True
            and value.get("sessionId") == self.args["expectedSessionId"] and value.get("expectedWorld") == self.args["expectedWorld"]
            and type(value.get("dimension")) is int and value.get("dimension") == self.dimension
            and isinstance(owner, dict) and type(owner.get("dimension")) is int and owner["dimension"] == self.dimension
            and all(type(owner.get(axis)) is int and owner[axis] == target[axis] for axis in ("x", "y", "z"))
            and owner.get("registryName") == self.args["controllerItem"]["registryName"]
            and type(owner.get("gregtech", {}).get("metaTileId")) is int
            and owner.get("gregtech", {}).get("metaTileId") == self.args["controllerItem"]["metadata"]
            and isinstance(actor, dict) and actor.get("source") == "integrated_server"
            and actor.get("playerId") == self.survival_player_id and type(actor.get("dimension")) is int and actor["dimension"] == self.dimension
            and actor.get("creative") is False and actor.get("actualSurvival") is True and actor.get("flying") is self.survival_flight
            and (not self.survival_flight or actor.get("allowFlying") is True)
            and type(actor.get("selectedHotbarSlot")) is int and actor["selectedHotbarSlot"] == slot
            and isinstance(blueprint, dict) and (all(type(blueprint.get(key)) is type(expected) and blueprint[key] == expected
                for key, expected in self.materials[slot]["contextIdentity"].items()) if "contextIdentity" in self.materials[slot]
                else _same(blueprint, self.materials[slot]["item"])) and type(blueprint.get("count")) is int
            and blueprint.get("count") == self.materials[slot]["count"] and isinstance(blueprint.get("nbtIdentity"), str),
            "survival_build_context_unconfirmed", "The native build must bind the exact session, controller, real survival player and retained actual blueprint.")

    def accept_survival_build_materials(self, value: dict[str, Any], args: dict[str, Any], slot: int, *, completed_only: bool = False, no_progress: bool = False) -> None:
        """Apply the same complete conservation checks to success and known completed batches."""
        _require(value.get("materialAccountingComplete") is True and value.get("generationPerformed") is False
            and value.get("inventoryCleared") is False and value.get("callbackFailed") is not True,
            "survival_build_accounting_incomplete", "Known completed batches need complete native accounting without generation or an in-flight callback.")
        if no_progress:
            from .construction_tail import validate_terminal
            validate_terminal(value, self.survival_ae_source)
        if self.survival_ae_source is None:
            deductions = _survival_deductions(value.get("playerInventoryBefore"), value.get("playerInventoryAfter"))
        _require(isinstance(value.get("batches"), list) and 1 <= len(value["batches"]) <= args["maxBatches"]
            and all(isinstance(batch, dict) and batch.get("accountingComplete") is True and type(batch.get("nativeReturn")) is int
                and type(batch.get("requestedBudget")) is int and 1 <= batch["requestedBudget"] <= 65_536
                and type(batch.get("configuredCooldownMillis")) is int and batch["configuredCooldownMillis"] >= 0
                for batch in value["batches"]) and (value["batches"][-1]["nativeReturn"] == -1 or completed_only and value["batches"][-1]["nativeReturn"] > 0
                    or no_progress and value["batches"][-1]["nativeReturn"] == 0)
            and all(batch["nativeReturn"] > 0 for batch in value["batches"][:-1]),
            "survival_build_accounting_incomplete", "Every returned native batch needs independently complete material accounting.")
        if self.survival_ae_source is not None:
            self.survival_material_ledger.append(_ae_survival_ledger(value, self.survival_ae_source,
                self.survival_ae_binding, self.dimension, self.survival_player_id, slot, self.survival_ae_player_inventory))
        else:
            expected: dict[tuple[str, int, str], int] = {}
            for row in deductions:
                identity = (row["registryName"], row["metadata"], row["nbtIdentity"])
                expected[identity] = expected.get(identity, 0) + row["count"]
            for group in ("committedItems", "playerDeductions"):
                actual: dict[tuple[str, int, str], int] = {}
                for batch in value["batches"]:
                    rows = batch.get(group)
                    _require(isinstance(rows, list), "survival_build_accounting_incomplete", "Native committed materials and real player deductions must be fully reported per batch.")
                    for row in rows:
                        _require(isinstance(row, dict), "survival_build_accounting_incomplete", "Every batch material row must be a complete typed receipt.")
                        item, amount = row.get("item"), row.get("amount")
                        _require(isinstance(item, dict) and isinstance(item.get("registryName"), str)
                            and type(item.get("metadata")) is int and type(item.get("count")) is int and item["count"] == 1
                            and isinstance(item.get("nbtIdentity"), str) and type(amount) is int and amount > 0,
                            "survival_build_accounting_incomplete", "Every committed/deducted batch material needs exact native identity and positive finite quantity.")
                        identity = (item["registryName"], item["metadata"], item["nbtIdentity"])
                        actual[identity] = actual.get(identity, 0) + amount
                _require(actual == expected, "survival_build_material_delta_mismatch", "Native committed materials and every real server slot deduction must match the same complete finite material bag.")
            for batch in value["batches"]:
                bags = []
                for group in ("committedItems", "playerDeductions"):
                    bag: dict[tuple[str, int, str], int] = {}
                    for row in batch[group]:
                        item = row["item"]
                        identity = (item["registryName"], item["metadata"], item["nbtIdentity"])
                        bag[identity] = bag.get(identity, 0) + row["amount"]
                    bags.append(bag)
                _require(bags[0] == bags[1], "survival_build_batch_delta_mismatch", "Each native batch must conserve its own committed materials and player deductions; cross-batch cancellation is insufficient.")
            self.survival_material_ledger.append({"phase": "survival_blueprint_construct", "deductions": deductions,
                "batches": len(value["batches"]), "nativeDoneIsNotFormation": True})
            self.account_survival_inventory_moves(deductions)
        after = _survival_inventory_entries(value["playerInventoryAfter"])
        self.accept_tagged_context_native_inventory(value["playerInventoryBefore"], value["playerInventoryAfter"])
        self.survival_inventory = [{"index": row["index"], "item": copy.deepcopy(row["item"])} for row in after]
        if self.survival_ae_source is not None:
            self.survival_ae_player_inventory = copy.deepcopy(after)
        for source_slot, material in self.materials.items():
            actual_item = after[source_slot]["item"]
            material["count"] = 0 if actual_item is None else actual_item["count"]

    def retain_survival_build_checkpoint(self, error: BridgeError, args: dict[str, Any], slot: int) -> None:
        checkpoint = error.details.get("survivalBuildCheckpoint")
        if not isinstance(checkpoint, dict) or checkpoint.get("operation") != "structure.survival_build":
            return
        known = checkpoint.get("knownReceipt")
        value = copy.deepcopy(known) if isinstance(known, dict) else {}
        value.update(operation="structure.survival_build", status="failed", partial=True,
            outcomeUnknown=bool(error.details.get("outcome_unknown")), formationVerified=False, automaticReplayAllowed=False,
            failureCode=error.code, failureCheckpoint=checkpoint.get("failureCheckpoint", checkpoint.get("stage")),
            checkpointPayloadComplete=checkpoint.get("payloadComplete") is True,
            callsStarted=checkpoint.get("callsStarted"), callsCompleted=checkpoint.get("callsCompleted"),
            callbackInFlight=checkpoint.get("callbackInFlight"), batches=copy.deepcopy(checkpoint.get("batches", [])),
            completedBatchAccounting="unconfirmed")
        # A positive callback is evidence of work, never native termination.
        value["nativeDone"] = (value.get("nativeDone") is True and isinstance(value["batches"], list) and bool(value["batches"])
            and isinstance(value["batches"][-1], dict) and value["batches"][-1].get("nativeReturn") == -1)
        self.survival_build_receipt = value
        batches = value["batches"]
        if checkpoint.get("payloadComplete") is not True or not isinstance(batches, list) or not batches:
            return
        try:
            _require(type(checkpoint.get("callsStarted")) is int and type(checkpoint.get("callsCompleted")) is int
                and checkpoint["callsStarted"] == checkpoint["callsCompleted"] == len(batches)
                and 1 <= len(batches) <= args["maxBatches"] and checkpoint.get("callbackInFlight") is False
                and all(isinstance(batch, dict) for batch in batches)
                and type(value.get("lastNativeReturn")) is int and value["lastNativeReturn"] == batches[-1].get("nativeReturn"),
                "survival_build_checkpoint_incomplete", "Only all completed, fully observed native batches can establish a retained material ledger.")
            self.validate_survival_build_context(value, slot)
            self.accept_survival_build_materials(value, args, slot, completed_only=True)
        except BridgeError as accounting_error:
            value["accountingError"] = {"code": accounting_error.code, "message": accounting_error.message,
                **({"reason": accounting_error.details["reason"]} if "reason" in accounting_error.details else {})}
        else:
            value["completedBatchAccounting"] = "confirmed"
            self.survival_material_ledger[-1]["completedBatchCheckpoint"] = True

    async def bootstrap_navigation(self, destination: dict[str, Any], strategy: str, phase: str) -> None:
        self.phase = phase
        _require(strategy in ("walking", "walking_step", "ability_flight"), "bootstrap_strategy_unsupported", "Bootstrap never uses teleport or a movement fallback.")
        _require(self.remaining() >= 250, "bootstrap_navigation_deadline", "One bounded native transition must fit this task's remaining deadline.")
        fields = ["status", "strategy", "arrivalConfirmed", "replayAllowed", "fallbackUsed", "outcomeUnknown", "playerId",
            "serverPoseAck", "actualClientPose", "baselineSampleId", "mutationDispatched", "elapsedMillis"]
        if strategy == "walking_step":
            fields.append("stepReceipt")
        elif strategy == "ability_flight":
            fields.append("flightTransitConfirmed")
        record = {"phase": phase, "destination": dict(destination), "strategy": strategy, "attempted": True, "completed": False}
        self.bootstrap_evidence["navigation"].append(record)
        distance = 0
        if strategy == "walking":
            player = (await self.snapshot())["player"]
            current = {"x": player.get("x"), "y": player.get("feetY"), "z": player.get("z")}
            _require(all(type(value) in (int, float) and math.isfinite(value) for value in current.values()),
                "bootstrap_navigation_pose_unavailable", "A walking transit budget requires finite actual current feet.")
            distance = math.sqrt(sum((destination[axis] - current[axis]) ** 2 for axis in ("x", "y", "z")))
        window = self.native_navigation_window(strategy, distance)
        record["navigationWindow"] = dict(window)
        action = _action("navigate", "mcb_navigate", {**destination, "dimension": self.dimension,
            "expectedSessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
            "requireSurvivalActor": True, "strategy": strategy, "finishFlying": self.survival_flight, **window})
        action["fields"] = fields
        try:
            report = await self.workflow(phase, [action])
        except (BridgeError, TimeoutError) as error:
            record["outcomeUnknown"] = isinstance(error, TimeoutError) or error.details.get("outcome_unknown") is True
            record["error"] = error.as_dict() if isinstance(error, BridgeError) else {"code": "BOOTSTRAP_NAVIGATION_TIMEOUT"}
            record["replayAllowed"] = False
            raise
        receipt = {field: self.value(report, "navigate", field) for field in fields}
        record["receipt"] = receipt
        _require(receipt["status"] == "completed" and receipt["strategy"] == strategy and receipt["arrivalConfirmed"] is True
            and receipt["replayAllowed"] is False and receipt["fallbackUsed"] is False and receipt["outcomeUnknown"] is False,
            "bootstrap_arrival_unavailable", "A single native transition requires actual arrival without fallback, replay or unknown outcome.")
        _require(strategy != "ability_flight" or receipt["flightTransitConfirmed"] is True,
            "bootstrap_flight_transit_unavailable", "Explicit ability_flight requires its actual native flight transit receipt before the declared arrival posture.")
        ack, client = receipt["serverPoseAck"], receipt["actualClientPose"]
        self.require_survival_server_control({"serverControl": ack})
        _require(type(receipt["baselineSampleId"]) is int and ack["sampleId"] > receipt["baselineSampleId"]
            and receipt["playerId"] == ack["playerId"] and ack.get("creative") is False and isinstance(ack.get("pose"), dict)
            and isinstance(client, dict) and client.get("creative") is False
            and self.require_navigation_posture((client, ack["pose"]), flying=self.survival_flight)
            and all(type(pose.get(path)) in (int, float) and math.isfinite(pose[path]) and abs(pose[path] - destination[axis]) <= .18
                for pose in (client, ack["pose"]) for axis, path in (("x", "x"), ("y", "feetY"), ("z", "z")))
            and all(abs(client[path] - ack["pose"][path]) <= .0001 for path in ("x", "feetY", "z")),
            "bootstrap_grounded_ack_unavailable", "A newer stable actual matching client/server declared survival posture is required.")
        if strategy == "walking_step":
            step = receipt["stepReceipt"]
            dy = step.get("deltaY") if isinstance(step, dict) else None
            _require(isinstance(step, dict) and type(step.get("version")) is int and step["version"] == 1 and type(dy) is int and dy == (1 if phase == "bootstrap_jump_up" else -1)
                and all(step.get(field) is True for field in ("clientEnvelopeProven", "serverEnvelopeProven", "clientStartSupportProven",
                    "serverStartSupportProven", "clientLandingSupportProven", "serverLandingSupportProven", "clientAirObserved",
                    "serverAirObserved", "groundAirGroundConfirmed", "keysReleased", "ownerClearConfirmed", "noAbilityOrMotionWrites"))
                and step.get("partial") is False and step.get("outcomeUnknown") is False and step.get("fallbackUsed") is False and step.get("replayAllowed") is False
                and type(step.get("nativeJumpEdges")) is int and step["nativeJumpEdges"] == (1 if dy > 0 else 0)
                and type(step.get("requestedJumpEdges")) is int and step["requestedJumpEdges"] == (1 if dy > 0 else 0),
                "bootstrap_step_proof_unavailable", "One real +1 jump or -1 walk-off must retain full dual-body/support and ground-air-ground proof and released input.")
            for body_name, pose in (("actualClientBody", client), ("actualServerBody", ack["pose"])):
                body = step.get(body_name)
                _require(isinstance(body, dict) and all(type(body.get(field)) in (int, float) and math.isfinite(body[field])
                    for field in ("minX", "minY", "minZ", "maxX", "maxY", "maxZ"))
                    and 0 < body["maxX"] - body["minX"] <= .9 and 0 < body["maxZ"] - body["minZ"] <= .9
                    and 0 < body["maxY"] - body["minY"] <= 2.1
                    and abs((body["minX"] + body["maxX"]) / 2 - pose["x"]) <= .0001
                    and abs((body["minZ"] + body["maxZ"]) / 2 - pose["z"]) <= .0001
                    and abs(body["minY"] - pose["feetY"]) <= .0001,
                    "bootstrap_native_body_unavailable", "A successful step must retain the full actual native client/server body at its acknowledged pose.")
        record["completed"] = True
        self.navigation_receipts.append(receipt)

    def bootstrap_ground(self) -> dict[str, Any]:
        seed, declaration = self.bootstrap_position, self.bootstrap_adjacent
        _require(seed is not None and declaration is not None and type(self.floor) is int,
            "bootstrap_position_unavailable", "The declared original-floor adjacent seed must already be bound.")
        return {"x": seed["x"] + declaration["offsetX"] + .5, "y": self.floor + 1,
            "z": seed["z"] + declaration["offsetZ"] + .5}

    async def bootstrap_up(self) -> None:
        _require(not self.bootstrap_up_attempted, "bootstrap_up_already_attempted", "A partial or unknown jump/flight bootstrap is never replayed.")
        self.bootstrap_up_attempted = True
        seed = self.bootstrap_position
        _require(seed is not None and any(record.get("placementConfirmed") is True and record.get("serverConsumptionConfirmed") is True
            and record["position"] == seed for record in self.supports), "bootstrap_seed_unconfirmed", "The permanent adjacent landing must be genuinely placed with exact finite materials first.")
        destination = {"x": seed["x"] + .5, "y": seed["y"] + 1, "z": seed["z"] + .5}
        strategy = self.args.get("movementStrategy", "walking")
        if strategy == "walking":
            await self.bootstrap_navigation(self.bootstrap_ground(), "walking", "bootstrap_takeoff")
            await self.bootstrap_navigation(destination, "walking_step", "bootstrap_jump_up")
        else:
            _require(strategy == "ability_flight", "bootstrap_strategy_unsupported", "Only explicitly selected real ability_flight can replace the normal jump.")
            await self.bootstrap_navigation(destination, "ability_flight", "bootstrap_flight_up")

    async def bootstrap_down(self) -> None:
        _require(self.bootstrap_up_attempted and not self.bootstrap_down_attempted,
            "bootstrap_exit_already_attempted", "The original bootstrap requires one known descent and no uncertain exit replay.")
        self.bootstrap_down_attempted = True
        _require(any(record.get("publicGroupMatched") is True and record["position"] == self.bootstrap_position for record in self.supports),
            "bootstrap_seed_public_unmatched", "The adjacent seed must match complete public candidates at its exact hint XYZ before exit.")
        if "controllerFacing" in self.args:
            seed = self.bootstrap_position
            await self.bootstrap_navigation({"x": seed["x"] + .5, "y": seed["y"] + 1, "z": seed["z"] + .5},
                "walking", "bootstrap_recenter")
        strategy = "walking_step" if self.args.get("movementStrategy", "walking") == "walking" else "ability_flight"
        await self.bootstrap_navigation(self.bootstrap_ground(), strategy, "bootstrap_step_down" if strategy == "walking_step" else "bootstrap_flight_down")

    def shell_envelope(self) -> dict[str, int]:
        world = self.bounds.get("worldXYZ", {}) if isinstance(self.bounds, dict) else {}
        low, high = world.get("min", {}), world.get("max", {})
        _require(self.capture is not None and all(type(low.get(axis)) is int and type(high.get(axis)) is int
            and low[axis] <= high[axis] and high[axis] - low[axis] < 64 for axis in ("x", "y", "z")),
            "shell_capture_bounds_unavailable", "Future-body exclusion needs this task's fresh complete bounded native hint envelope.")
        controller = self.args["controller"]
        return {**{"min" + axis.upper(): min(low[axis], controller[axis]) for axis in ("x", "y", "z")},
            **{"max" + axis.upper(): max(high[axis], controller[axis]) for axis in ("x", "y", "z")}}

    def shell_front_sides(self) -> tuple[int, ...]:
        side = {"NORTH": 2, "SOUTH": 3, "WEST": 4, "EAST": 5}.get(self.capture.get("capturedFacing", "").split("_", 1)[0])
        _require(side is not None, "shell_front_unavailable", "The actual native capture must declare the controller's public horizontal front.")
        return (side,)

    async def ensure_shell_exit(self) -> dict[str, Any]:
        if self._frontier_jump_return is not None:
            destination, self._frontier_jump_return = self._frontier_jump_return, None
            await self.bootstrap_navigation(destination, "walking", "survival_frontier_jump_descent")
        self.phase = "shell_actor_exit"
        health = self.approach_health or {}
        _require(type(health.get("approachBuildEnvelopeVersion")) is int and health["approachBuildEnvelopeVersion"] == 1
            and type(health.get("survivalBuildEnvelopeVersion")) is int and health["survivalBuildEnvelopeVersion"] == 1,
            "native_build_body_exclusion_unavailable", "Shell submission requires advertised native future-body approach/callback exclusion.")
        envelope, sides = self.shell_envelope(), self.shell_front_sides()
        declared = self.args.get("shellExitPosition", (self.bootstrap_adjacent or {}).get("exitPosition"))
        if declared is not None:
            destination = {"x": declared["x"] + .5, "y": declared["y"], "z": declared["z"] + .5}
            await self.bootstrap_navigation(destination, self.args.get("movementStrategy", "walking"), "shell_declared_exit")
            plan = await self.current_native_approach(self.args["controller"], allowed_sides=sides, excluded_build_envelope=envelope)
            _require(plan["status"] == "ready", "shell_exit_face_unavailable", "The explicit outside exit must retain a real front-face native ray and actual normal reach.")
        else:
            plan = await self.resolved_native_approach(self.args["controller"], allowed_sides=sides, excluded_build_envelope=envelope)
        _require(plan.get("currentOnly") is True and plan["selected"]["moveRequired"] is False,
            "shell_exit_current_unavailable", "Shell submission requires fresh exact actual-current native body/ray proof outside the captured envelope.")
        for side in ("client", "server"):
            body = plan["selected"].get(side + "Body", {}).get("body", {})
            _require(all(type(body.get(field)) in (int, float) and math.isfinite(body[field])
                for field in ("minX", "minY", "minZ", "maxX", "maxY", "maxZ"))
                and all(body["min" + axis] < body["max" + axis] for axis in ("X", "Y", "Z"))
                and any(body["max" + axis] <= envelope["min" + axis] or body["min" + axis] >= envelope["max" + axis] + 1
                    for axis in ("X", "Y", "Z")),
                "shell_actor_body_overlap", "Both full actual native body boxes must lie outside the fresh future build envelope.")
        self.shell_exit_evidence.append({"excludedBuildEnvelope": envelope, "selected": copy.deepcopy(plan["selected"]),
            "scope": "Fresh actual native body/support/front face outside this capture; neither future visibility nor formation is claimed."})
        return plan

    async def blueprint_use(self, *, build: bool) -> None:
        if build and self.survival_ae_source is not None:
            _require(not self.survival_ae_build_attempted, "survival_ae_build_already_attempted",
                "This task's AE shell was already submitted; unknown, partial and completed callbacks are never replayed.")
        target = self.args["controller"]
        if self.actual_survival and build:
            await self.ensure_shell_exit()
            _, side, steps = await self.planned_approach(target, allowed_sides=self.shell_front_sides(), excluded_build_envelope=self.shell_envelope())
        else:
            _, side, steps = await self.approach([(target, side) for side in (2, 3, 4, 5)])
        if self.tagged_context_inventory is not None and self.actual_survival and steps:
            # Keep the original complete approach proof and any movement in a
            # bounded phase. Fresh full-NBT and selection checks remain in the
            # later interaction phase, rather than exceeding its32-step limit.
            await self.workflow("tagged_blueprint_build_approach" if build else "tagged_blueprint_hint_approach", steps)
            steps = []
        slot = self.args["blueprintHotbarSlot"]
        if self.tagged_context_inventory is None or self.actual_survival and build and self.survival_ae_source is not None:
            steps.extend(self.material_guards(slot))
        if self.actual_survival and build:
            budget = self.args.get("survivalBuild", {})
            timeout = min(budget.get("timeout_ms", 7500), self.remaining())
            _require(timeout >= 500, "survival_build_deadline", "The bounded native build needs at least 500 ms remaining and is never replayed.")
            args = {**self.point(target), "expectedSessionId": self.args["expectedSessionId"],
                "expectedWorld": self.args["expectedWorld"], "blueprintHotbarSlot": slot, "side": side,
                "maxBatches": budget.get("maxBatches", 32), "timeout_ms": timeout,
                **({"elementBudget": budget["elementBudget"]} if "elementBudget" in budget else {}),
                "excludedBuildEnvelope": self.shell_envelope(),
                **({"materialSource": copy.deepcopy(self.survival_ae_source)} if self.survival_ae_source is not None else {})}
            fields = ["operation", "status", "actualSurvival", "sessionId", "expectedWorld", "dimension", "controller", "actor", "blueprint",
                "nativeDone", "materialAccountingComplete", "formationVerified", "partial", "outcomeUnknown",
                "generationPerformed", "inventoryCleared", "playerInventoryBefore", "playerInventoryAfter", "batches",
                "clientBuildBodyProof", "serverBuildBodyProof"]
            if budget.get("finishAirTail") is True:
                fields.extend(["callsStarted", "callsCompleted"])
            if self.survival_ae_source is not None:
                fields.append("materialSourceType")
                steps.append(self.hotbar_step("blueprint", slot))
                await self.read_survival_ae_source("survival_ae_source_before_shell", steps)
                steps = []
                timeout = min(budget.get("timeout_ms", 7500), self.remaining())
                _require(timeout >= 500, "survival_build_deadline", "Fresh AE readiness must leave at least 500 ms for one bounded native build; no read or action is replayed.")
                args["timeout_ms"] = timeout
            current = await self.current_native_approach(target, allowed_sides=self.shell_front_sides(), excluded_build_envelope=args["excludedBuildEnvelope"])
            _require(current["status"] == "ready" and current["selected"]["attachment"]["side"] == side,
                "shell_current_body_unavailable", "A final fresh outside body and matching real controller front face are required immediately before the guarded native build.")
            if self.tagged_context_inventory is not None:
                await self.read_tagged_context_inventory(guard_steps=steps)
                steps.extend(self.material_guards(slot))
            action = _action("build", "mcb_survival_build", args)
            action.update(fields=fields, omitEmptySlots=False)
            if self.survival_ae_source is None:
                steps.append(self.hotbar_step("blueprint", slot))
            steps.extend([*self.tagged_context_selection_steps(slot), action])
            if self.survival_ae_source is not None:
                self.survival_ae_build_attempted = True
            try:
                report = await self.workflow("survival_blueprint_construct", steps)
            except BridgeError as error:
                self.retain_survival_build_checkpoint(error, args, slot)
                raise
            value = {key: self.value(report, "build", key) for key in fields}
            self.survival_build_receipt = value
            self.validate_survival_build_context(value, slot)
            for side in ("client", "server"):
                proof = value.get(side + "BuildBodyProof")
                body = proof.get("actualNativeBody") if isinstance(proof, dict) else None
                excluded = proof.get("excludedBuildEnvelope") if isinstance(proof, dict) else None
                _require(isinstance(proof, dict) and type(proof.get("version")) is int and proof["version"] == 1
                    and proof.get("source") == side and proof.get("playerId") == self.survival_player_id
                    and type(proof.get("dimension")) is int and proof["dimension"] == self.dimension
                    and proof.get("outside") is True and proof.get("formationProven") is False
                    and isinstance(excluded, dict) and all(type(excluded.get(field)) in (int, float)
                        and excluded[field] == bound + (1 if field.startswith("max") else 0)
                        for field, bound in args["excludedBuildEnvelope"].items())
                    and isinstance(body, dict) and all(type(body.get(field)) in (int, float) and math.isfinite(body[field])
                        for field in ("minX", "minY", "minZ", "maxX", "maxY", "maxZ"))
                    and all(body["min" + axis] < body["max" + axis] for axis in ("X", "Y", "Z"))
                    and any(body["max" + axis] <= excluded["min" + axis] or body["min" + axis] >= excluded["max" + axis]
                        for axis in ("X", "Y", "Z")),
                    "survival_build_body_exclusion_unconfirmed", "Both actual native callback body proofs must bind the same actor and exact excluded envelope outside every future cell.")
            if value["status"] == "no_progress" and budget.get("finishAirTail") is True:
                self.accept_survival_build_materials(value, args, slot, no_progress=True)
                await self.verify_material(slot)
                from .construction_tail import finish_air_tail
                await finish_air_tail(self)
                return
            _require(value["status"] == "native_done", "survival_build_" + value["status"],
                "The bounded native survival build stopped without completion; its material/status receipt is retained and no call is replayed.")
            _require(isinstance(value, dict) and value.get("nativeDone") is True
                and value.get("materialAccountingComplete") is True and value.get("formationVerified") is False
                and value.get("partial") is False and value.get("outcomeUnknown") is False
                and value.get("generationPerformed") is False and value.get("inventoryCleared") is False,
                "survival_build_unconfirmed", "One bounded native build must finish with complete explicit-source material accounting; budget, cooldown, no-progress and unknown results never replay.")
            self.accept_survival_build_materials(value, args, slot)
            await self.verify_material(slot)
            return
        if self.tagged_context_inventory is not None:
            await self.read_tagged_context_inventory(guard_steps=steps)
            steps.extend(self.material_guards(slot))
        steps.extend([self.hotbar_step("blueprint", slot), *self.tagged_context_selection_steps(slot),
            _action("use", "mcb_interact", {"action": "use", **target, "side": side, "sneaking": build})])
        if self._workflow_discovery is not None:
            steps.append(self.material_after_read())
        report = await self.workflow("blueprint_construct" if build else "blueprint_hints", steps)
        await self.verify_material(slot, report=report if self._workflow_discovery is not None else None)

    def refreshable_hint_capture(self, runtime: dict[str, Any]) -> bool:
        owner, trigger, cut = (runtime.get(key) for key in ("controller", "trigger", "truncation"))
        material = self.materials.get(self.args["blueprintHotbarSlot"], {})
        expected_trigger = {**material.get("item", {}), "count": material.get("count"),
            "channelData": material.get("contextIdentity", {}).get("nbtIdentity", "")}
        return (runtime.get("source") == "client_native_blueprint_hints"
            and runtime.get("nativeInvocationReturned") is True and runtime.get("available") is True
            and runtime.get("capturedVisitsComplete") is False and runtime.get("eventCaptureTruncated") is True
            and all(runtime.get(key) is True for key in ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches"))
            and isinstance(runtime.get("captureId"), str) and 0 < len(runtime["captureId"]) <= 128
            and isinstance(cut, dict) and cut.get("workBudget") is True
            and all(cut.get(key) is False for key in ("positions", "groups", "bytes", "cleanupFailed"))
            and type(runtime.get("detailErrors")) is int and runtime["detailErrors"] == 0
            and type(runtime.get("observedVisits")) is int and type(runtime.get("capturedPositions")) is int
            and 0 <= runtime["capturedPositions"] < runtime["observedVisits"] <= _MAX_CAPTURE_POSITIONS
            and isinstance(owner, dict) and all(type(owner.get(axis)) is int and owner[axis] == value
                for axis, value in {**self.args["controller"], "dimension": self.dimension}.items())
            and isinstance(owner.get("metaClass"), str) and bool(owner["metaClass"])
            and isinstance(runtime.get("capturedFacing"), str) and bool(runtime["capturedFacing"])
            and owner.get("facing") == runtime["capturedFacing"] == runtime.get("serverFacing")
            and type(runtime.get("capturedMode")) is int and type(runtime.get("serverMode")) is int
            and runtime["capturedMode"] == owner.get("mode") == runtime["serverMode"]
            and isinstance(trigger, dict) and trigger == expected_trigger)

    async def collect_hints(self) -> tuple[list[dict[str, Any]], dict[int, dict[str, Any]]]:
        positions: list[dict[str, Any]] = []
        groups: dict[int, dict[str, Any]] = {}
        offset, capture_id, total, retained_bytes = 0, None, None, 0
        native_placement_header = None
        # A soft server tick/byte budget can yield fewer than 128 coordinates.
        # Positive raw-offset progress plus the task deadline bounds every page.
        for _ in range(_MAX_CAPTURE_POSITIONS):
            args = {**self.point(self.args["controller"]), "limit": 128, "runtimeOffset": offset, "floorBlockY": self.floor}
            if capture_id is not None:
                args["captureId"] = capture_id
            data = await self.call("world.structure", args)
            runtime = data.get("runtimeObservation", {})
            # One returned hint-only invocation may lose visits to its work budget
            # (notably on cold public getters). Discard that entire capture; never
            # merge pages or retry construction, missing/unknown callbacks or a
            # changed owner. The ordinary hint workflow retains all native guards.
            if offset == 0 and capture_id is None and self.hint_capture_refresh is None \
                    and self.refreshable_hint_capture(runtime):
                self.hint_capture_refresh = {"discardedCaptureId": runtime["captureId"],
                    "reason": "returned_hint_work_budget", "hintOnly": True, "attempted": True,
                    "capturedContext": copy.deepcopy({key: runtime[key] for key in
                        ("controller", "trigger", "capturedFacing", "capturedMode")}),
                    "originalObservedVisits": runtime["observedVisits"],
                    "originalCapturedPositions": runtime["capturedPositions"], "completed": False}
                await self.blueprint_use(build=False)
                continue
            if self.hint_capture_refresh is not None:
                refresh = self.hint_capture_refresh
                _require(runtime.get("captureId") != refresh["discardedCaptureId"]
                    and all(type(runtime.get(key)) is type(value) and runtime.get(key) == value
                        for key, value in refresh["capturedContext"].items()),
                    "native_hint_refresh_context_changed", "A fresh hint capture must retain the exact original owner, trigger, mode and facing; no further use is submitted.")
            if self.tagged_context_inventory is not None:
                material = self.materials[self.args["blueprintHotbarSlot"]]
                _require(runtime.get("trigger") == {**material["item"], "count": material["count"],
                    "channelData": material.get("contextIdentity", {}).get("nbtIdentity", "")}, "tagged_context_capture_changed",
                    "Every native hint page must bind the same retained blueprint's exact registry, metadata, count and full channel NBT.")
            if self.placement_plan is not None and (self.placement_plan["geometry"].get("geometryMode") == "native_visits"
                    or isinstance(self.placement_plan["geometry"].get("sourceCaptureHeader"), dict)):
                geometry, owner = self.placement_plan["geometry"], data.get("controller", {})
                _require(data.get("alignment") == geometry["alignment"] and isinstance(owner, dict)
                    and all(type(owner.get(axis)) is int and owner[axis] == self.args["controller"][axis] for axis in ("x", "y", "z"))
                    and type(owner.get("dimension")) is int and owner["dimension"] == self.dimension
                    and owner.get("metaClass") == geometry["sourceMetaClass"],
                    "placement_native_geometry_changed", "Every target native page must belong to the actual new matching controller and alignment.")
                current_header = _native_visit_header(runtime, owner, self.dimension, data["alignment"])
                _require(native_placement_header is None or current_header == native_placement_header,
                    "placement_native_geometry_changed", "The fresh target native capture context cannot change between complete pages.")
                native_placement_header = current_header
            bounds = runtime.get("observedBounds", {})
            _require(runtime.get("available") is True and runtime.get("capturedVisitsComplete") is True
                and runtime.get("eventCaptureTruncated") is False and all(runtime.get(flag) is True for flag in
                ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches"))
                and bounds.get("available") is True and bounds.get("complete") is True and bounds.get("partial") is False,
                "native_hint_capture_incomplete", "The actual native capture needs complete visits/bounds and matching live controller, mode and facing.")
            if any(key in runtime for key in ("uncapturedVisits", "captureLossDetected", "captureContinuationAvailable")):
                _require(type(runtime.get("uncapturedVisits")) is int and runtime["uncapturedVisits"] == 0
                    and runtime.get("captureLossDetected") is False and runtime.get("captureContinuationAvailable") is False
                    and type(runtime.get("observedVisits")) is int and runtime["observedVisits"] == runtime.get("capturedPositions"),
                    "native_hint_capture_incomplete", "A lost native visit/detail cannot be recovered by paging; complete capture evidence remains required.")
            identifier = runtime.get("captureId")
            count = runtime.get("capturedPositions")
            _require(isinstance(identifier, str) and 1 <= len(identifier) <= 128 and (capture_id is None or capture_id == identifier)
                and type(count) is int and 1 <= count <= _MAX_CAPTURE_POSITIONS and (total is None or total == count)
                and type(runtime.get("runtimeOffset")) is int and runtime["runtimeOffset"] == offset,
                "native_capture_changed", "Runtime pages must preserve one immutable exact capture and original raw offset.")
            self.capture, self.bounds = runtime, bounds
            if self.hint_capture_refresh is not None:
                self.hint_capture_refresh.update(freshCaptureId=identifier)
            capture_id, total = identifier, count
            planning = runtime.get("planning", {})
            _require(planning.get("available") is True and planning.get("floorBlockY") == self.floor,
                "native_floor_plan_unavailable", "The observed bounds need current public floor planning.")
            _require(planning.get("floorConflict") is False and planning.get("raiseBlocks") == 0,
                "floor_conflict_raise_required", "The observed lowest layer conflicts with the real floor; raise the fresh controller explicitly before another task.")
            page_positions, page_groups = runtime.get("positions"), runtime.get("groups")
            _require(isinstance(page_positions, list) and len(page_positions) <= 128 and isinstance(page_groups, list),
                "native_hint_page_unavailable", "Runtime coordinates and exact public groups must be bounded typed pages.")
            for group in page_groups:
                _require(isinstance(group, dict) and type(group.get("id")) is int and 0 <= group["id"] < _MAX_CAPTURE_POSITIONS
                    and isinstance(group.get("descriptionKeys"), list) and len(group["descriptionKeys"]) <= 128
                    and all(isinstance(key, str) and 1 <= len(key) <= 256 for key in group["descriptionKeys"])
                    and isinstance(group.get("suggestedCandidates"), list), "native_group_unavailable", "Every group needs exact public descriptions and placement suggestions.")
                group_id = group["id"]
                _require(group_id not in groups or groups[group_id] == group, "native_group_changed", "A capture's public groups must remain immutable across pages.")
                if group_id not in groups:
                    retained_bytes += len(json.dumps(group, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
                groups[group_id] = group
            for position in page_positions:
                _require(isinstance(position, dict) and all(type(position.get(axis)) is int for axis in ("x", "y", "z", "group"))
                    and 0 <= position["y"] <= 255 and position["group"] in groups,
                    "native_position_unavailable", "Every captured world coordinate needs a resolved exact public group.")
                positions.append(position)
            retained_bytes += len(json.dumps(page_positions, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
            _require(retained_bytes <= _MAX_CAPTURE_BYTES, "native_capture_memory_budget",
                "Retained native coordinates and unique public groups exceed the independent four MiB task memory bound.")
            following = runtime.get("nextRuntimeOffset")
            _require(type(following) is int and offset <= following <= total and following - offset == len(page_positions),
                "native_capture_offset_invalid", "Unfiltered runtime pages must account for every raw captured coordinate exactly.")
            if runtime.get("hasMore") is False:
                _require(following == total and len(positions) == total and len({_key(point) for point in positions}) == total,
                    "native_capture_coverage_incomplete", "Complete runtime coordinates must have exact count and unique world identities.")
                self._target_capture_header = copy.deepcopy(native_placement_header)
                if self.hint_capture_refresh is not None:
                    self.hint_capture_refresh["completed"] = True
                return positions, groups
            _require(runtime.get("hasMore") is True and following > offset, "native_capture_no_progress", "Runtime paging cannot stall or infer completion.")
            offset = following
        raise _Unavailable("native_capture_page_budget", "The bounded native capture exceeded its positive-progress page budget.")

    def pinned_empty_port(self, port: dict[str, Any]) -> bool:
        # Exact declarations may use a public predicate-only element whose
        # description omits accepted alternatives. This is placement intent,
        # never evidence that the native controller accepts the role.
        return self.construction_order == "ports_first" and all(key in port for key in
            ("portGroup", "relativePosition", "declaredItem", "groupSelector"))

    def select_port_group(self, port: dict[str, Any], groups: dict[int, dict[str, Any]]) -> int | None:
        selector = port.get("groupSelector")
        if selector is None:
            return None
        _require(isinstance(self.capture, dict) and self.capture.get("capturedVisitsComplete") is True
            and self.capture.get("eventCaptureTruncated") is False, "public_port_group_binding_unavailable",
            "An explicit group replacement needs this native capture's complete untruncated visit proof.")
        expected = sorted(selector["descriptionKeys"])
        matched = [group for _, group in sorted(groups.items()) if isinstance(group.get("descriptionKeys"), list)
            and sorted(group["descriptionKeys"]) == expected]
        _require(selector["occurrence"] < len(matched), "public_port_group_unavailable",
            "The caller's exact public description-key occurrence was not captured; no other group is substituted.")
        group = matched[selector["occurrence"]]
        binding = group.get("roleSelection", {})
        _require(isinstance(binding, dict) and binding.get("available") is True
            and binding.get("binding") == _GROUP_SELECTION_BINDING
            and binding.get("captureId") == self.capture["captureId"]
            and type(binding.get("groupId")) is int and binding["groupId"] == group["id"]
            and binding.get("semanticRoleVerified") is False and binding.get("descriptionKeys") == group["descriptionKeys"]
            and group.get("descriptionTruncated", False) is False
            and (self.pinned_empty_port(port) or ROLE_KEYS[port["role"]] in group["descriptionKeys"]), "public_port_group_binding_unavailable",
            "A caller-selected group needs the same capture's complete public definition-element binding; ordinal selection proves no seed or machine-specific semantics.")
        return group["id"]

    async def bind_survival_seeds(self, positions: list[dict[str, Any]], groups: dict[int, dict[str, Any]]) -> None:
        seeds = [record for record in self.supports if record.get("permanentStructureSeed") is True]
        _require(self.survival_seed_mode and len(seeds) == len(self.survival_seed_plan)
            and {_key(record["position"]) for record in seeds} == {_key(point) for point in self.survival_seed_plan},
            "survival_structure_seed_unproven", "Only the task's complete originally observed terrain-column seed plan may be publicly bound.")
        indexed = {_key(point): point for point in positions}
        if seeds:
            _require(all(_key(record["position"]) in indexed for record in seeds),
                "survival_structure_seed_unproven", "Every caller-declared initial seed needs exact captured XYZ membership.")
            await self.read_position_candidates([indexed[_key(record["position"])] for record in seeds])
        observed = await self.blocks([record["position"] for record in seeds])
        for record in seeds:
            point = indexed.get(_key(record["position"]))
            index = record.get("survivalMaterialLedgerIndex")
            _require(self.survival_seed_mode and point is not None and type(index) is int
                and 0 <= index < len(self.survival_material_ledger) and record.get("placementConfirmed") is True
                and record.get("serverConsumptionConfirmed") is True and record.get("nativePlaceBlockUsed") is True
                and record.get("hasTile") is False and not record.get("outcomeUnknown")
                and self.initial_seed_record_matches(record)
                and type(record.get("originalTerrainFloor")) is int and record["originalTerrainFloor"] == self.floor
                and record["block"] == record["sourceItem"],
                "survival_structure_seed_unproven", "An actual finite native placement with task ownership and a captured physical casing position is required; no foreign support is adopted.")
            ledger = self.survival_material_ledger[index]
            deductions = ledger.get("deductions")
            _require(ledger.get("position") == _xyz(point) and ledger.get("phase") == record.get("seedPhase", "survival_structure_seed_" + str(point["y"]))
                and isinstance(deductions, list) and len(deductions) == 1
                and deductions[0].get("playerInventoryIndex") == record["sourceHotbarSlot"]
                and type(deductions[0].get("count")) is int and deductions[0]["count"] == 1
                and type(deductions[0].get("sourceCountBefore")) is int and deductions[0]["sourceCountBefore"] > 0
                and type(deductions[0].get("sourceCountAfter")) is int and deductions[0]["sourceCountAfter"] >= 0
                and deductions[0]["sourceCountBefore"] - deductions[0]["sourceCountAfter"] == 1
                and deductions[0].get("nbtIdentity") == ""
                and all(deductions[0].get(key) == value for key, value in record["sourceItem"].items()),
                "survival_structure_seed_material_unproven", "The seed must retain its exact server-before/after deduction receipt, source slot, full untagged identity and count of one.")
            actual = observed[_key(point)]
            _require(_block_identity(actual) == record["block"] and "tileClass" not in actual,
                "survival_structure_seed_changed", "An already placed seed needs fresh same-identity server proof without an actual tile; a Block declaration is not presence evidence.")
            group = groups.get(point["group"], {})
            candidates = group.get("suggestedCandidates")
            scoped = self.survival_position_candidates.get(_key(point), {})
            candidates = scoped.get("detail", {}).get("suggestedCandidates")
            matched = [item for item in candidates if isinstance(item, dict) and item.get("hasTag") is False
                and item.get("nbtIdentity") == "" and type(item.get("blockHasTileEntity")) is bool
                and item.get("blockIdentity") == record["block"]
                and all(type(item.get(key)) is type(value) and item[key] == value for key, value in record["block"].items())] \
                if isinstance(candidates, list) else []
            _require(scoped.get("complete") is True and group.get("descriptionTruncated", False) is False
                and bool(matched), "survival_structure_seed_public_mismatch",
                "Every permanent casing seed must match a complete actual public candidate of its exact captured group; arbitrary stone is unsupported.")
            record.update(publicGroupMatched=True, captureId=self.capture["captureId"], group=point["group"],
                publicCandidateScope="exact_captured_xyz_public_getter_after_initial_placement",
                publicBlockTileDeclaration={"blockHasTileEntity": matched[0]["blockHasTileEntity"],
                    "source": "public Block.hasTileEntity(metadata)",
                    "scope": "Type/metadata declaration only; it does not establish an actual tile at this seed."},
                seedTilePresence={"source": "fresh_integrated_server_complete_world_inspect", "present": False,
                    "scope": "Only the loaded same-identity task-owned seed now; later no-tile rechecks remain required."},
                seedMeaning="Actual finite native casing retained in the final structure; independent real formation is still required.")
        self.survival_seed_positions = {_key(record["position"]) for record in seeds}
        await self.verify_survival_seed_blocks("seed_public_binding")

    async def verify_survival_seed_blocks(self, phase: str) -> None:
        seeds = [record for record in self.supports if record.get("permanentStructureSeed") is True]
        if not seeds:
            return
        observed = await self.blocks([record["position"] for record in seeds])
        for record in seeds:
            block = observed[_key(record["position"])]
            _require(record.get("publicGroupMatched") is True and _block_identity(block) == record["block"]
                and "tileClass" not in block, "survival_structure_seed_changed",
                "The exact publicly matched task-owned casing seed must remain at its native coordinates without a tile; it is never cleared or replaced.")
            record.update(lastServerVerification=phase, retained=True, refunded=False)
            if self.formation_verified:
                record["formationVerified"] = True

    def prepare_port_operating_geometry(self, positions: list[dict[str, Any]]) -> None:
        """Rank conservative future face corridors; never infer native access or exhaust."""
        self.port_operating_faces = {}
        self.port_operating_geometry = None
        capture = self.capture or {}
        if not self.actual_survival or capture.get("capturedVisitsComplete") is not True \
                or capture.get("eventCaptureTruncated") is not False \
                or type(capture.get("capturedPositions")) is not int or capture["capturedPositions"] != len(positions):
            return
        _require(0 < len(positions) <= _MAX_CAPTURE_POSITIONS and len({_key(point) for point in positions}) == len(positions),
            "public_port_face_geometry_incomplete", "Face ranking needs the complete unique exact native visit set; no partial closure is inferred.")
        mask = {_key(point) for point in positions} | {_key(self.args["controller"])}
        columns: list[dict[tuple[int, int], tuple[int, int]]] = [{}, {}, {}]
        for point in mask:
            for axis in range(3):
                key = tuple(point[index] for index in range(3) if index != axis)
                bounds = columns[axis].get(key, (point[axis], point[axis]))
                columns[axis][key] = min(bounds[0], point[axis]), max(bounds[1], point[axis])
        for point in positions:
            cell, sides = _key(point), []
            for axis, negative, positive in ((0, 4, 5), (1, 0, 1), (2, 2, 3)):
                column = columns[axis][tuple(cell[index] for index in range(3) if index != axis)]
                # Known terrain floor cannot become an operating approach below the port.
                if cell[axis] == column[0] and (axis != 1 or cell[1] - 1 > self.floor):
                    sides.append(negative)
                if cell[axis] == column[1]:
                    sides.append(positive)
            self.port_operating_faces[cell] = sorted(sides)
        self.port_operating_geometry = {"version": 1, "captureId": capture["captureId"],
            "capturedVisitCount": len(positions), "maskedCellCount": len(mask), "wholeProspectiveCellCubes": True,
            "scope": "Sorting only: straight outward face corridors through the complete captured visit/controller cell mask. Every visit is conservatively occupied; public air semantics are not inferred.",
            "unknown": "Future rules outside this capture, external clearance, native ray/reach, facing and pollution exhaust remain unverified."}

    def select_port_candidate(self, port_index: int, selection: Any, groups: dict[int, dict[str, Any]], total_group: int) -> dict[str, Any]:
        port = self.args["requestedPorts"][port_index]
        selected_group = self.select_port_group(port, groups)
        _, point, suggestions, lateral_face, front_plane = selection
        _require(port.get("installPhase") != "after_shell" or bool(suggestions),
            "after_shell_baseline_unavailable", "An explicitly deferred port requires public casing candidates before port installation; actual tile-free casing identity is checked after native construction.")
        group_id = point["group"]
        replacements = 1 + sum(record["group"] == group_id for record in self.selected_ports)
        _require(replacements <= total_group and (selected_group is not None or replacements < total_group),
            "baseline_casing_retention_unavailable", "Default selection retains one observed baseline casing; exact caller-selected groups still require real formation acceptance.")
        record = {"role": port["role"], "hotbarSlot": port["hotbarSlot"], "position": _xyz(point), "group": group_id,
            "item": self.materials[port["hotbarSlot"]]["item"], "descriptionKey": ROLE_KEYS[port["role"]],
            "suggestedCandidates": [{key: item[key] for key in ("registryName", "metadata")} for item in suggestions],
            "candidateScope": groups[group_id].get("candidateScope"), "airBeforeConstruction": True,
            "lateralEnvelopeFace": lateral_face, "frontPlane": front_plane}
        if self.port_operating_geometry is not None:
            record["futureOperatingFaces"] = {**self.port_operating_geometry,
                "sides": list(self.port_operating_faces[_key(point)]), "nativeReachabilityVerified": False,
                "externalClearanceVerified": False, "pollutionExhaustVerified": False,
                "laterGuiAccessVerified": False}
        if "id" in port:
            record["id"] = port["id"]
        if "portGroup" in port:
            record.update(portGroup=port["portGroup"], ordinal=port["ordinal"], relativePosition=port["relativePosition"],
                declaredItem=port["declaredItem"], installPhase=port.get("installPhase", "before_shell"), exactDeclaredPositionVerified=True,
                selectionBasis="caller_pinned_empty_cell_in_exact_public_group",
                roleDescriptionObserved=ROLE_KEYS[port["role"]] in groups[group_id]["descriptionKeys"],
                nativeRoleAcceptanceRequired=True)
            if "expectedRoleIndex" in port:
                record["expectedRoleIndex"] = port["expectedRoleIndex"]
        if port_index in self.port_supply_bindings:
            record["supplyBinding"] = dict(self.port_supply_bindings[port_index])
        if selected_group is not None:
            record["groupSelection"] = {"selector": port["groupSelector"], "captureId": self.capture["captureId"],
                "groupId": group_id, "binding": _GROUP_SELECTION_BINDING, "semanticRoleVerified": False,
                "scope": "Explicit caller selection within this native capture; no inferred seed or machine-specific role."}
        self.selected_ports.append(record)
        return record

    @staticmethod
    def match_port_layout(windows: dict[int, list[Any]], costs: dict[tuple[int, Any], int],
        capacities: dict[int, int]) -> dict[int, Any] | None:
        """At most 32 unit flows; cells and group capacities prevent duplicate replacements."""
        graph: list[list[list[int]]] = []
        def node() -> int:
            graph.append([])
            return len(graph) - 1
        def edge(left: int, right: int, capacity: int, cost: int) -> list[int]:
            forward = [right, len(graph[right]), capacity, cost]
            graph[left].append(forward)
            graph[right].append([left, len(graph[left]) - 1, 0, -cost])
            return forward
        source, sink = node(), node()
        group_nodes = {group: node() for group in sorted(capacities)}
        for group, target in group_nodes.items():
            edge(target, sink, max(0, capacities[group]), 0)
        cells = {_key(candidate[1]): candidate[1]["group"] for candidates in windows.values() for candidate in candidates}
        cell_nodes = {cell: node() for cell in sorted(cells)}
        for cell, target in cell_nodes.items():
            edge(target, group_nodes[cells[cell]], 1, 0)
        choices = {}
        for index, candidates in sorted(windows.items()):
            role_node = node()
            edge(source, role_node, 1, 0)
            for candidate in candidates:
                cell = _key(candidate[1])
                choices[index, cell] = edge(role_node, cell_nodes[cell], 1, costs[index, cell])
        potentials = [0] * len(graph)
        for _ in windows:
            distance, parents = {source: 0}, {}
            queue = [(0, source)]
            while queue:
                value, current = heapq.heappop(queue)
                if value != distance[current]:
                    continue
                for offset, (target, _, capacity, cost) in enumerate(graph[current]):
                    proposed = value + cost + potentials[current] - potentials[target]
                    if capacity and (target not in distance or proposed < distance[target]):
                        distance[target], parents[target] = proposed, (current, offset)
                        heapq.heappush(queue, (proposed, target))
            if sink not in parents:
                return None
            for target, value in distance.items():
                potentials[target] += value
            current = sink
            while current != source:
                previous, offset = parents[current]
                forward = graph[previous][offset]
                forward[2] -= 1
                graph[current][forward[1]][2] += 1
                current = previous
        return {index: cell for (index, cell), chosen in choices.items() if chosen[2] == 0}

    def rank_survival_port_layout(self, pending: dict[int, list[Any]], used: set[Any]) -> dict[int, list[Any]]:
        """Joint geometric preference only; native placement and later GUI proofs remain mandatory."""
        _require(len(pending) <= 32, "survival_port_frontier_budget", "A joint layout is bounded to 32 declared ports.")
        windows, selectors, counts = {}, {}, {}
        for index, candidates in pending.items():
            selectors[index] = self.select_port_group(self.args["requestedPorts"][index], self.survival_port_groups)
            eligible = []
            for candidate in candidates:
                point = candidate[1]
                if _key(point) in used:
                    continue
                replacements = 1 + sum(port["group"] == point["group"] for port in self.selected_ports)
                total = self.survival_group_counts[point["group"]]
                if replacements <= total and (selectors[index] is not None or replacements < total):
                    eligible.append(candidate)
            counts[index] = len(eligible)
            windows[index] = eligible
        if self.port_operating_geometry is None:
            return {index: candidates[:32] for index, candidates in windows.items()}
        controller = self.args["controller"]
        front = _FRONT[self.capture["capturedFacing"].split("_", 1)[0]]
        side = next(side for side, vector in _VECTORS.items() if vector == front)
        policy = self.position_selection_policy
        policy["order"] = "Captured exposed front, operating layer, compact IO and energy, group front depth, joint compact legal positions, stable XYZ; fresh current-pose admission and bounded move search."
        policy["basis"] = "Complete captured cell-mask geometry ranks exposed faces first. Observed developer flight prefers controller height for every role; otherwise IO and muffler prefer the lowest operating layer, and other roles the preferred operating layer. IO and energy compactness is prioritized only within shared public groups. This does not prove ground support, native reach, GUI access or exhaust."
        frequent_roles = ("item_input", "item_output", "fluid_input", "fluid_output", "energy")
        depths = {(index, group): max(sum(candidate[1][axis] * front[offset] for offset, axis in enumerate(("x", "y", "z")))
            for candidate in candidates if candidate[1]["group"] == group)
            for index, candidates in windows.items() for group in {candidate[1]["group"] for candidate in candidates}}
        base = {}
        for index, candidates in windows.items():
            role = self.args["requestedPorts"][index]["role"]
            preferred_y = policy["lowestOperatingY"] if policy["movementContext"] != "Observed developer flight" \
                and role in ("item_input", "item_output", "fluid_input", "fluid_output", "muffler") else policy["preferredOperatingY"]
            for candidate in candidates:
                point, cell = candidate[1], _key(candidate[1])
                faces = self.port_operating_faces[cell]
                projection = sum(point[axis] * front[offset] for offset, axis in enumerate(("x", "y", "z")))
                base[index, cell] = (0 if side in faces else 1 if any(face in (2, 3, 4, 5) for face in faces) else 2,
                    abs(point["y"] - preferred_y), depths[index, point["group"]] - projection)
            windows[index] = sorted(candidates, key=lambda candidate: (base[index, _key(candidate[1])], candidate[0]))[:32]
        cells = sorted({_key(candidate[1]) for candidates in windows.values() for candidate in candidates})
        installed = [_key(port["position"]) for port in self.selected_ports]
        installed_frequent = [_key(port["position"]) for port in self.selected_ports if port.get("role") in frequent_roles]
        anchors = []
        distance = lambda left, right: sum(abs(left[axis] - right[axis]) for axis in range(3))
        if cells:
            anchors.append(min(cells, key=lambda cell: (sum(distance(cell, point) for point in installed_frequent),
                sum(distance(cell, point) for point in installed), distance(cell, _key(controller)), cell)))
        while len(anchors) < min(16, len(cells)):
            anchors.append(min((cell for cell in cells if cell not in anchors),
                key=lambda cell: (-min(distance(cell, anchor) for anchor in anchors), distance(cell, _key(controller)), cell)))
        capacities = {group: total - sum(port["group"] == group for port in self.selected_ports)
            - int(any(selectors[index] is None and any(candidate[1]["group"] == group for candidate in candidates)
                for index, candidates in windows.items())) for group, total in self.survival_group_counts.items()}
        frequent_group_counts = {group: sum(self.args["requestedPorts"][index]["role"] in frequent_roles
            and any(candidate[1]["group"] == group for candidate in candidates) for index, candidates in windows.items())
            + sum(port.get("role") in frequent_roles and port["group"] == group for port in self.selected_ports)
            for group in capacities}
        # A lone IO in a separate caller-selected chamber must not lose that
        # group's front preference merely to move closer to another chamber.
        best = None
        for anchor in anchors:
            components = {(index, _key(candidate[1])): (*base[index, _key(candidate[1])][:2],
                distance(_key(candidate[1]), anchor) if self.args["requestedPorts"][index]["role"] in frequent_roles
                    and frequent_group_counts[candidate[1]["group"]] > 1 else 0,
                base[index, _key(candidate[1])][2], distance(_key(candidate[1]), anchor),
                distance(_key(candidate[1]), _key(controller)))
                for index, candidates in windows.items() for candidate in candidates}
            scales = [1 + len(windows) * max(values[offset] for values in components.values()) for offset in range(6)]
            costs = {}
            for identity, values in components.items():
                cost = 0
                for offset, value in enumerate(values):
                    cost = cost * scales[offset] + value
                costs[identity] = cost
            assignment = self.match_port_layout(windows, costs, capacities)
            if assignment is None:
                continue
            score = [sum(components[index, cell][offset] for index, cell in assignment.items()) for offset in range(6)]
            score[2] += sum(distance(anchor, point) for point in installed_frequent)
            score[4] += sum(distance(anchor, point) for point in installed)
            score = (*score, tuple(assignment[index] for index in sorted(assignment)), anchor)
            if best is None or score < best[0]:
                best = score, assignment, anchor
        if best is not None:
            _, assignment, anchor = best
            for index, candidates in windows.items():
                windows[index] = sorted(candidates, key=lambda candidate: (_key(candidate[1]) != assignment[index],
                    base[index, _key(candidate[1])], distance(_key(candidate[1]), anchor), candidate[0]))
        points = {_key(candidate[1]): candidate[1] for candidates in windows.values() for candidate in candidates}
        point_indices = {cell: index for index, cell in enumerate(sorted(points))}
        diagnostic = {"version": 1, "captureId": self.capture["captureId"], "maxAnchors": 16, "anchorsConsidered": len(anchors),
            "maxRoles": 6, "maxCandidatesPerRole": 32, "completePreferredAssignment": best is not None,
            "nativeReachabilityVerified": False, "laterGuiAccessVerified": False, "semanticRoleVerified": False,
            "scope": "Ranking only within captured public category/group candidates; geometric anchors are not standing coordinates or native proofs.",
            "candidatePositions": [{**_xyz(points[cell]), "group": points[cell]["group"]} for cell in sorted(points)],
            "roles": [{"portIndex": index, "role": self.args["requestedPorts"][index]["role"], "selectedGroup": selectors[index],
                "publicCandidateCount": counts[index], "candidateWindowComplete": counts[index] == len(candidates),
                "candidateIndices": [point_indices[_key(candidate[1])] for candidate in candidates],
                "preferredPosition": _xyz(candidates[0][1]) if best is not None else None} for index, candidates in windows.items()]}
        geometry = policy.setdefault("geometryFaceRanking", dict(self.port_operating_geometry))
        geometry["jointLayout" if "jointLayout" not in geometry else "jointLayoutLast"] = diagnostic
        return windows

    async def decide_ports(self, positions: list[dict[str, Any]], groups: dict[int, dict[str, Any]]) -> None:
        if self.survival_seed_mode:
            if not self.survival_seed_positions:
                await self.bind_survival_seeds(positions, groups)
            if self.bootstrap_position is not None:
                await self.bootstrap_down()
            self.survival_capture_positions = {_key(point) for point in positions}
            self.survival_capture_points = {_key(point): point for point in positions}
            self.survival_port_candidates, self.survival_port_groups = {}, groups
            self.survival_group_counts = {group: sum(point["group"] == group for point in positions) for group in groups}
        await self.verify_clear_volume(positions)
        controller = self.args["controller"]
        facing = self.capture.get("capturedFacing", "").split("_", 1)[0]
        front = _FRONT.get(facing)
        _require(front is not None, "public_facing_unavailable", "The captured public facing must identify a horizontal front plane.")
        world = self.bounds.get("worldXYZ", {}) if isinstance(self.bounds, dict) else {}
        low, high = world.get("min", {}), world.get("max", {})
        _require(type(self.floor) is int and all(type(low.get(axis)) is int and type(high.get(axis)) is int
            and low[axis] <= high[axis] for axis in ("x", "y", "z")), "public_port_position_basis_unavailable",
            "Convenient port ranking requires the actual observed capture envelope and known floor; it does not infer support surfaces.")
        self.prepare_port_operating_geometry(positions)
        lowest_layer = max(low["y"], self.floor + 1)
        operating_layer = min(high["y"], max(self.floor + 2, lowest_layer + 1))
        snapshot = self.position_snapshot or {}
        player, flight = snapshot.get("player", {}), snapshot.get("player", {}).get("serverFlight", {})
        flying = snapshot.get("profile") == "developer" and snapshot.get("sessionId") == self.args["expectedSessionId"] \
            and snapshot.get("world", {}).get("dimension") == self.dimension and player.get("flying") is True \
            and all(flight.get(key) is True for key in ("available", "flying", "allowFlying")) and flight.get("dimension") == self.dimension
        self.position_selection_policy = {"movementContext": "Observed developer flight" if flying else "Ground or unconfirmed flight",
            "order": "Observed lateral envelope face, front plane, controller height, shared face and nearby ports, controller horizontal distance, stable XYZ." if flying
                else "Observed lateral envelope face, preferred operating layer, lower Y, front plane, controller horizontal distance, stable XYZ.",
            "floorBlockY": self.floor, "lowestOperatingY": lowest_layer, "preferredOperatingY": controller["y"] if flying else operating_layer,
            "basis": "Uses the task's most recent existing guarded snapshot; confirmed developer flight keeps all roles near controller height and together on one known face. Otherwise IO prefers the lowest observed layer above the known floor, and other roles floor+2 or that layer+1.",
            "scope": "Sorting only within publicly permitted candidates and the caller's selected group in this native capture.",
            "unknown": "Envelope faces do not prove external clearance, walkways or support faces; pipeline and full processing layout optimization remain unknown."}
        if self.port_operating_geometry is not None:
            self.position_selection_policy["geometryFaceRanking"] = dict(self.port_operating_geometry)
            self.position_selection_policy["order"] = "Conservative horizontal future face, captured front plane, then " + self.position_selection_policy["order"]
        used: set[tuple[int, int, int]] = set(self.survival_seed_positions)
        cluster_anchor, cluster_faces = None, set()
        pinned = {tuple(controller[a] + port["relativePosition"][a] for a in ("x", "y", "z")): index
            for index, port in enumerate(self.args["requestedPorts"]) if "relativePosition" in port}
        for port_index, port in enumerate(self.args["requestedPorts"]):
            selected_group = self.select_port_group(port, groups)
            pinned_empty = self.pinned_empty_port(port)
            candidates = []
            for point in positions:
                if _key(point) in pinned and pinned[_key(point)] != port_index:
                    continue
                if "relativePosition" in port and any(point[axis] != controller[axis] + port["relativePosition"][axis]
                        for axis in ("x", "y", "z")):
                    continue
                group = groups[point["group"]]
                suggestions = group["suggestedCandidates"]
                if selected_group is not None and point["group"] != selected_group \
                        or not pinned_empty and ROLE_KEYS[port["role"]] not in group["descriptionKeys"] \
                        or group.get("descriptionTruncated", False) is True \
                        or group.get("candidatesTruncated", False) is True or _key(point) in used:
                    continue
                valid = [candidate for candidate in suggestions if isinstance(candidate, dict) and candidate.get("hasTag") is False
                    and isinstance(candidate.get("registryName"), str) and type(candidate.get("metadata")) is int]
                if not valid and not pinned_empty:
                    continue
                front_plane = sum((point[axis] - controller[axis]) * front[index] for index, axis in enumerate(("x", "y", "z"))) == 0
                lateral_face = point["x"] in (low["x"], high["x"]) or point["z"] in (low["z"], high["z"])
                low_role = port["role"] in ("item_input", "item_output", "fluid_input", "fluid_output")
                preferred_y = lowest_layer if low_role else operating_layer
                faces = {(axis, point[axis]) for axis in ("x", "z") if point[axis] in (low[axis], high[axis])}
                horizontal_distance = abs(point["x"] - controller["x"]) + abs(point["z"] - controller["z"])
                rank = (not lateral_face, not front_plane, abs(point["y"] - controller["y"]),
                    bool(cluster_faces) and not bool(faces & cluster_faces),
                    sum(abs(point[axis] - cluster_anchor[axis]) for axis in ("x", "y", "z")) if cluster_anchor else 0,
                    horizontal_distance, point["y"], point["x"], point["z"]) if flying else (
                    not lateral_face, abs(point["y"] - preferred_y), point["y"], not front_plane,
                    horizontal_distance, point["x"], point["z"])
                if self.port_operating_geometry is not None:
                    horizontal = any(side in (2, 3, 4, 5) for side in self.port_operating_faces[_key(point)])
                    rank = (not horizontal, not front_plane, *rank)
                candidates.append((rank, point, valid, lateral_face, front_plane))
            candidates.sort(key=lambda candidate: candidate[0])
            _require(bool(candidates), "public_port_category_unavailable", "No captured public category/suggestion candidate exists for " + port["role"] + ".")
            if self.survival_seed_mode:
                self.survival_port_candidates[port_index] = candidates
                continue
            _, point, suggestions, lateral_face, front_plane = candidates[0]
            group_id = point["group"]
            total_group = sum(position["group"] == group_id for position in positions)
            self.select_port_candidate(port_index, candidates[0], groups, total_group)
            used.add(_key(point))
            if cluster_anchor is None:
                cluster_anchor = _xyz(point)
                cluster_faces = {(axis, point[axis]) for axis in ("x", "z") if point[axis] in (low[axis], high[axis])}

    def native_owned_replacement_available(self) -> bool:
        from .workflows import _context_capabilities, _inspect_many_player_inventory_available
        health = self.approach_health or {}
        if self._workflow_discovery is not None:
            health = self._workflow_discovery.bind(self.tracked_client, self.credentials, self.args["expectedSessionId"])
        advertised = health.get("nativeOwnedBlockReplacement")
        if advertised is None or advertised is False:
            return False
        expected = {"version": 1, "guardedOnly": True, "developerOnly": True, "oldTilePolicy": "absent",
            "ownership": "caller task provenance; bridge verifies exact current identity, not ownership inferred from registry",
            "fullPlayerSlotConservation": True}
        contract = health.get("nativeOwnedBlockReplacementContract")
        capable = health.get("capabilities")
        reads, actions = _context_capabilities(health)
        _require(advertised is True and isinstance(contract, dict) and set(contract) == set(expected)
            and all(type(contract[key]) is type(value) and contract[key] == value for key, value in expected.items())
            and ("player.replace_owned_block" in capable if isinstance(capable, list) else isinstance(capable, dict)
                and capable.get("player.replace_owned_block") is True)
            and "player.replace_owned_block" in actions and "player.plan_approach" in reads
            and _inspect_many_player_inventory_available(health),
            "native_owned_replacement_contract_unavailable", "Advertised owned replacement needs its complete typed guarded developer contract and original full player-observation capability.")
        return True

    def validate_owned_replacement(self, value: dict[str, Any], point: dict[str, Any], slot: int,
        expected_old: dict[str, Any], expected_placed: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        from .workflows import _guard, _validate_player_inventory_observation
        reason = "native_owned_replacement_receipt_unavailable"
        def require(condition: bool, message: str) -> None:
            _require(condition, reason, message)
        def typed_equal(left: Any, right: Any) -> bool:
            if type(left) is not type(right):
                return False
            if isinstance(left, dict):
                return set(left) == set(right) and all(typed_equal(left[key], right[key]) for key in left)
            if isinstance(left, list):
                return len(left) == len(right) and all(typed_equal(a, b) for a, b in zip(left, right))
            return left == right
        def require_epoch_snapshot(snapshot: Any) -> None:
            require(isinstance(snapshot, dict) and isinstance(snapshot.get("world"), dict)
                and type(snapshot["world"].get("dimension")) is int and snapshot["world"]["dimension"] == self.dimension
                and type(snapshot.get("clientTicks")) is int and snapshot["clientTicks"] >= 0
                and isinstance(snapshot.get("player"), dict) and isinstance(snapshot["player"].get("serverFlight"), dict)
                and type(snapshot["player"]["serverFlight"].get("dimension")) is int
                and snapshot["player"]["serverFlight"]["dimension"] == self.dimension,
                "Every retained snapshot needs its exact typed world/flight dimension and nonnegative client epoch.")
        required = {"version", "operation", "status", "stage", "nonAtomic", "nativeDigSubmitted", "nativePlaceSubmitted",
            "digConfirmed", "placeConfirmed", "replacementValidated", "observations", "attackPlan", "placePlan", "nativeDig",
            "nativePlace", "moves", "serverPlayers", "inventoryConservation"}
        require(isinstance(value, dict) and set(value) == required and type(value.get("version")) is int and value["version"] == 1
            and value.get("operation") == "player.replace_owned_block" and value.get("status") == "completed"
            and value.get("stage") == "completed" and all(value.get(key) is True for key in
                ("nonAtomic", "nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed", "replacementValidated")),
            "A completed replacement must retain all exact typed native and authoritative acceptance checkpoints.")
        outer = response_context(value)
        require(isinstance(outer, dict) and set(outer) == {"version", "operation", "phase", "snapshot"}
            and type(outer.get("version")) is int and outer["version"] == 1
            and outer.get("operation") == "player.replace_owned_block" and outer.get("phase") == "before"
            and isinstance(outer.get("snapshot"), dict), "The replacement must retain its own guarded action BEFORE context.")
        require_epoch_snapshot(outer["snapshot"])
        self.validate_snapshot(outer["snapshot"])
        _guard(outer["snapshot"], self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        phases = ("beforeDig", "afterDig", "afterPlace")
        observations, server_players = value.get("observations"), value.get("serverPlayers")
        require(isinstance(observations, dict) and set(observations) == set(phases)
            and isinstance(server_players, dict) and set(server_players) == set(phases),
            "Every mutation epoch needs its own original owner/player sample and authoritative player inventory.")
        observation_args = {**self.combined_read_args(point), "observationByteBudget": 1_048_576}
        owners, client_slots, server_slots, ticks = {}, {}, {}, []
        for phase in phases:
            sample = observations[phase]
            require(isinstance(sample, dict) and set(sample) == {"data", "context"}
                and isinstance(sample["data"], dict), "Each epoch must retain its original full data/context pair.")
            context = sample["context"]
            require(isinstance(context, dict) and set(context) == {"version", "operation", "phase", "snapshot"}
                and type(context.get("version")) is int and context["version"] == 1
                and context.get("operation") == "world.inspect_many" and context.get("phase") == "after"
                and isinstance(context.get("snapshot"), dict), "Each epoch needs its own typed inspect_many AFTER context.")
            require_epoch_snapshot(context["snapshot"])
            self.validate_snapshot(context["snapshot"])
            _guard(context["snapshot"], self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
            self.require_flight(context["snapshot"])
            _validate_player_inventory_observation(sample["data"], observation_args, context)
            inventory = sample["data"]["playerInventory"]
            slots = inventory_slots(inventory, "")
            require(len(slots) == 41 and {entry["index"] for entry in slots} == set(range(41))
                and inventory.get("cursorItem") is None and slots[self.empty_slot]["item"] is None,
                "All 41 actual player entries and the exact reserved empty hand must remain complete and conserved.")
            client_slots[phase] = slots
            owners[phase] = sample["data"]["results"][0]["data"]
            authoritative = server_players[phase]
            require(isinstance(authoritative, dict) and set(authoritative) == {"source", "dimension", "worldTick", "playerId",
                    "inventory", "selectedHotbarSlot", "creative", "flying", "allowFlying", "feet"}
                and authoritative.get("source") == "integrated_server" and type(authoritative.get("dimension")) is int
                and authoritative["dimension"] == self.dimension and type(authoritative.get("worldTick")) is int
                and authoritative["worldTick"] >= 0 and authoritative.get("playerId") == inventory["playerId"]
                and all(authoritative.get(key) is True for key in ("creative", "flying", "allowFlying"))
                and type(authoritative.get("selectedHotbarSlot")) is int and 0 <= authoritative["selectedHotbarSlot"] <= 8
                and isinstance(authoritative.get("feet"), dict) and set(authoritative["feet"]) == {"x", "y", "z"}
                and all(type(authoritative["feet"].get(axis)) in (int, float)
                    and math.isfinite(authoritative["feet"][axis]) for axis in ("x", "y", "z")),
                "Authoritative player proof must retain the same live creative flying player and exact typed dimension/pose.")
            require(type(owners[phase].get("worldTick")) is int and owners[phase]["worldTick"] >= 0
                and owners[phase]["worldTick"] == authoritative["worldTick"],
                "Each original owner and authoritative player must retain the same nonnegative server epoch.")
            server_inventory = authoritative["inventory"]
            require(isinstance(server_inventory, dict) and isinstance(server_inventory.get("name"), str)
                and server_inventory.get("complete") is True and "cursorItem" in server_inventory
                and server_inventory["cursorItem"] is None,
                "Every authoritative inventory needs its explicit typed public name, complete coverage and empty cursor.")
            server_slots[phase] = inventory_slots(server_inventory, "")
            player = context["snapshot"]["player"]
            require(len(server_slots[phase]) == 41 and typed_equal(server_slots[phase], slots)
                and type(server_inventory.get("selectedHotbarSlot")) is int
                and server_inventory["selectedHotbarSlot"] == authoritative["selectedHotbarSlot"] == inventory["selectedHotbarSlot"]
                and all(type(player.get("feetY" if axis == "y" else axis)) in (int, float)
                    and math.isfinite(player["feetY" if axis == "y" else axis])
                    and abs(authoritative["feet"][axis] - player["feetY" if axis == "y" else axis]) <= .0001
                    for axis in ("x", "y", "z")),
                "All authoritative server and current client item entries, selected hand and pose must agree in their own epoch.")
            ticks.append((context["snapshot"]["clientTicks"], authoritative["worldTick"]))
        require(client_slots["beforeDig"] == client_slots["afterDig"] == client_slots["afterPlace"]
            and server_slots["beforeDig"] == server_slots["afterDig"] == server_slots["afterPlace"]
            and all(ticks[index][axis] <= ticks[index + 1][axis] for index in range(2) for axis in (0, 1)),
            "Every unrelated actual slot and creative source stack must remain identical across ordered fresh mutation epochs.")
        require(observations["beforeDig"]["data"]["playerInventory"]["selectedHotbarSlot"] == self.empty_slot
            and server_players["beforeDig"]["selectedHotbarSlot"] == self.empty_slot
            and observations["afterDig"]["data"]["playerInventory"]["selectedHotbarSlot"] == self.empty_slot
            and server_players["afterDig"]["selectedHotbarSlot"] == self.empty_slot
            and observations["afterPlace"]["data"]["playerInventory"]["selectedHotbarSlot"] == slot
            and server_players["afterPlace"]["selectedHotbarSlot"] == slot,
            "Digging must retain the exact empty hand, and placement must select the exact current allocated source.")
        proof = value["inventoryConservation"]
        proof_keys = {"scope", "actualClientSlots", "actualServerSlots", "clientItemsPreserved", "serverItemsPreserved",
            "nbtPreserved", "cursorEmpty", "creativeSourceCountUnchanged"}
        require(isinstance(proof, dict) and set(proof) == proof_keys and proof.get("scope") == "all_actual_player_slots"
            and type(proof.get("actualClientSlots")) is int and proof["actualClientSlots"] == 41
            and type(proof.get("actualServerSlots")) is int and proof["actualServerSlots"] == 41
            and all(proof.get(key) is True for key in proof_keys - {"scope", "actualClientSlots", "actualServerSlots"}),
            "The native full ItemStack/NBT conservation proof must explicitly cover all 41 client and server slots.")
        require(_block_identity(owners["beforeDig"]) == expected_old and "tileClass" not in owners["beforeDig"]
            and _block_identity(owners["afterDig"]) == {"registryName": "minecraft:air", "metadata": 0}
            and "tileClass" not in owners["afterDig"], "Only the exact confirmed task-owned no-tile casing may become fresh server air.")
        placed = owners["afterPlace"]
        require(placed.get("registryName") == expected_placed["registryName"]
            and all(type((placed.get("gregtech") or {}).get(key)) is type(wanted)
                and (placed.get("gregtech") or {}).get(key) == wanted if key in ("metaTileId", "metaClass")
                else type(placed.get(key)) is type(wanted) and placed.get(key) == wanted
                for key, wanted in expected_placed.items()), "The final real block/GT identity must match its exact native source allocation.")
        for key, intent, source_slot, expected in (("attackPlan", "attack", None, expected_old),
                ("placePlan", "place_block", slot, {"registryName": "minecraft:air", "metadata": 0})):
            plan = value[key]
            require(isinstance(plan, dict) and set(plan) == {"data", "context"} and isinstance(plan["data"], dict),
                "Both native planners need their exact ready DTO and original own AFTER context.")
            require_epoch_snapshot(plan.get("context", {}).get("snapshot") if isinstance(plan.get("context"), dict) else None)
            self.validate_native_approach(_ContextData(plan["data"], plan["context"]), point,
                intent=intent, pattern="surround", allowed_sides=tuple(_VECTORS), hotbar_slot=source_slot,
                target_identity=expected)
            if intent == "place_block":
                source, material = plan["data"]["current"]["sourceItem"], self.materials[slot]
                require(_same(source, material["item"]) and source["count"] == material["count"],
                    "The placement planner must retain the task's exact actual source identity and count.")
        plan_ticks = [value[key]["context"]["snapshot"].get("clientTicks") for key in ("attackPlan", "placePlan")]
        require(outer["snapshot"]["clientTicks"] <= plan_ticks[0] <= ticks[0][0]
                <= plan_ticks[1] <= ticks[1][0] <= ticks[2][0],
            "Each original planner and mutation observation must retain its own ordered fresh client epoch.")
        moves = value["moves"]
        require(isinstance(moves, list) and len(moves) <= 2, "One replacement allows at most one verified move per native action.")
        move_phases = [entry.get("phase") for entry in moves if isinstance(entry, dict)]
        require(len(move_phases) == len(moves) and move_phases in ([], ["before_dig"], ["before_place"], ["before_dig", "before_place"])
            and all(value[key]["data"]["selected"]["moveRequired"] is False for key in ("attackPlan", "placePlan")),
            "Final ready action proofs must use current feet, and every earlier move must retain an ordered distinct epoch.")
        for move in moves:
            key = "attackPlan" if move["phase"] == "before_dig" else "placePlan"
            require(set(move) == {"phase", "nativeTeleport", "afterSnapshot", "selectedFeet", "confirmed", "plan"}
                and move.get("confirmed") is True and isinstance(move.get("nativeTeleport"), dict)
                and bool(move["nativeTeleport"]) and isinstance(move.get("afterSnapshot"), dict)
                and isinstance(move.get("plan"), dict) and set(move["plan"]) == {"data", "context"}
                and isinstance(move["plan"]["data"], dict), "A move must retain its original ready proof, native receipt and fresh confirmed destination.")
            selected_feet = move.get("selectedFeet")
            require(isinstance(selected_feet, dict) and set(selected_feet) == {"x", "y", "z"}
                and all(type(selected_feet.get(axis)) in (int, float) and math.isfinite(selected_feet[axis])
                    for axis in ("x", "y", "z")), "Move-selected feet need exact XYZ keys and finite non-boolean numeric coordinates.")
            before = move["plan"]
            require_epoch_snapshot(before.get("context", {}).get("snapshot") if isinstance(before.get("context"), dict) else None)
            self.validate_native_approach(_ContextData(before["data"], before["context"]), point,
                intent="attack" if key == "attackPlan" else "place_block", pattern="surround", allowed_sides=tuple(_VECTORS),
                hotbar_slot=None if key == "attackPlan" else slot,
                target_identity=expected_old if key == "attackPlan" else {"registryName": "minecraft:air", "metadata": 0})
            if key == "placePlan":
                source, material = before["data"]["current"]["sourceItem"], self.materials[slot]
                require(_same(source, material["item"]) and source["count"] == material["count"],
                    "The original pre-move placement planner must retain the exact task source allocation.")
            require(before["data"]["selected"]["moveRequired"] is True
                and move["selectedFeet"] == before["data"]["selected"]["feet"]
                and all(value[key]["data"]["current"][axis] == move["selectedFeet"][axis] for axis in ("x", "y", "z")),
                "The original move-selected feet must match the later fresh action-ready current pose.")
            teleport = move["nativeTeleport"]
            require(type(teleport.get("dimension")) is int and teleport["dimension"] == self.dimension
                and self.args["expectedWorld"] in (teleport.get("worldName"), teleport.get("worldFolder"))
                and all(isinstance(teleport.get(phase), dict)
                    and all(teleport[phase].get(key) is True for key in ("flying", "allowFlying"))
                    and all(type(teleport[phase].get(axis)) in (int, float) and math.isfinite(teleport[phase][axis])
                        and abs(teleport[phase][axis] - expected[axis]) <= .0001 for axis in ("x", "y", "z"))
                    for phase, expected in (("before", before["data"]["current"]), ("after", move["selectedFeet"]))),
                "The original native teleport must retain its exact protected world, flying origin and selected destination.")
            snapshot = move["afterSnapshot"]
            require_epoch_snapshot(snapshot)
            lower_tick = outer["snapshot"]["clientTicks"] if key == "attackPlan" else ticks[0][0]
            require(lower_tick <= before["context"]["snapshot"]["clientTicks"] <= snapshot["clientTicks"]
                <= value[key]["context"]["snapshot"]["clientTicks"],
                "Every original move plan and fresh destination must retain their own ordered client epochs before the final ready plan.")
            self.validate_snapshot(snapshot)
            _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
            self.require_flight(snapshot)
            require(all(type(snapshot.get("player", {}).get("feetY" if axis == "y" else axis)) in (int, float)
                and math.isfinite(snapshot["player"]["feetY" if axis == "y" else axis])
                and snapshot["player"]["feetY" if axis == "y" else axis] == move["selectedFeet"][axis]
                for axis in ("x", "y", "z")), "The native move must end at the exact current selected feet.")
        dig, native = value["nativeDig"], value["nativePlace"]
        require(isinstance(dig, dict) and dig.get("accepted") is True and isinstance(dig.get("blockTarget"), dict)
            and all(type(dig["blockTarget"].get(axis)) is int and dig["blockTarget"][axis] == point[axis] for axis in ("x", "y", "z"))
            and dig.get("airUseFallback") is False, "The original native dig receipt must retain the exact real target and no air-use fallback.")
        attachment = native.get("attachment") if isinstance(native, dict) else None
        require(isinstance(native, dict) and native.get("nativeInvoked") is True and native.get("submitted") is True
            and native.get("accepted") is True and native.get("serverConfirmed") is False
            and native.get("executionValidated") is False and native.get("source") == "client_native"
            and type(native.get("dimension")) is int and native["dimension"] == self.dimension
            and native.get("attackSubmitted") is False and native.get("airUseFallback") is False
            and native.get("replaceRequested") is False and type(native.get("selectedHotbarSlot")) is int
            and native["selectedHotbarSlot"] == slot and isinstance(native.get("target"), dict)
            and all(type(native["target"].get(axis)) is int and native["target"][axis] == point[axis] for axis in ("x", "y", "z"))
            and isinstance(attachment, dict) and type(attachment.get("side")) is int and attachment["side"] in _VECTORS
            and all(type(attachment.get(axis)) is int and attachment[axis] + _VECTORS[attachment["side"]][index] == point[axis]
                for index, axis in enumerate(("x", "y", "z"))),
            "The original native right-click and attachment receipt cannot be promoted to machine execution or replace authoritative acceptance.")
        return placed, observations["afterPlace"]["data"]["playerInventory"]

    async def replace_owned_port(self, port: dict[str, Any], identity: dict[str, Any]) -> None:
        point, slot = port["position"], port["hotbarSlot"]
        material = self.materials[slot]
        binding = port.get("supplyBinding")
        _require(binding is None or binding["sourceHotbarSlot"] == slot
            and binding["sourceStackCount"] == material["count"] and not binding.get("placementAttempted"),
            "port_supply_binding_changed", "Each exact port allocation may attempt its one owned replacement only once.")
        item = material["item"]
        expected_placed = {"registryName": item["registryName"],
            "metaTileId" if item["registryName"] == "gregtech:gt.blockmachines" else "metadata": item["metadata"]}
        _require("metaTileId" in expected_placed or type(expected_placed["metadata"]) is int and 0 <= expected_placed["metadata"] <= 15,
            "native_owned_replacement_identity_unavailable", "A non-GT native source needs an authoritative valid block metadata identity.")
        args = {**self.point(point), "expectedWorld": self.args["expectedWorld"], "emptyHotbarSlot": self.empty_slot,
            "hotbarSlot": slot, "expectedOldIdentity": dict(identity),
            "expectedSourceItem": {**item, "count": material["count"], "hasTag": False},
            "expectedPlacedIdentity": expected_placed, "pattern": "surround", "requireFlying": True, "allowMove": True,
            "playerInventoryByteBudget": 131_072, "observationByteBudget": 1_048_576}
        self.phase = "native_owned_replacement"
        port.update(nativeReplacementAttempted=True, nativePath="player.replace_owned_block")
        if binding is not None:
            binding["placementAttempted"] = True
        value = None
        earlier_action = self.last_action
        try:
            with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
                "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
                value = await self.call("player.replace_owned_block", args)
            placed, player = self.validate_owned_replacement(value, point, slot, identity, expected_placed)
            await self.verify_material(slot, report={"steps": [{"id": "placed", "observation": {
                "values": {"playerInventory": player}}}]}, combined=True)
        except BaseException as error:
            details = error.details if isinstance(error, BridgeError) else {}
            for source, target in (("nativeDigSubmitted", "digAttempted"), ("nativePlaceSubmitted", "placementAttempted"),
                ("digConfirmed", "digConfirmed"), ("placeConfirmed", "placementConfirmed")):
                if type(details.get(source)) is bool:
                    port[target] = details[source]
            # A successful native RPC followed by local receipt/read validation
            # cannot inherit the read-only validator's outcome_unknown=False.
            cancelled = value is None and self.last_action is not earlier_action and self.last_action is not None \
                and self.last_action.get("operation") == "player.replace_owned_block" \
                and self.last_action.get("cancellationStatus") == "cancelled"
            port["outcomeUnknown"] = value is not None or not cancelled and details.get("outcome_unknown") is not False
            if isinstance(value, dict):
                port["nativeReplacementClaimedCheckpoints"] = {key: value[key] for key in
                    ("nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed") if type(value.get(key)) is bool}
            if self.last_action is not None and self.last_action.get("operation") == "player.replace_owned_block":
                self.last_action["outcomeUnknown"] = port["outcomeUnknown"]
            if isinstance(error, BridgeError):
                error.details["outcome_unknown"] = port["outcomeUnknown"]
            port["nativeReplacementFailure"] = {key: details[key] for key in
                ("stage", "nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed", "outcome_unknown") if key in details}
            raise
        port.update(digAttempted=True, digConfirmed=True, cleanupAttempted=True, cleanupConfirmed=True,
            placementAttempted=True, placementConfirmed=True, nativePlaceBlockUsed=True,
            attachment=dict(value["nativePlace"]["attachment"]), block=_block_identity(placed), hasTile="tileClass" in placed)
        port["nativeReplacement"] = {key: value[key] for key in ("version", "operation", "status", "stage", "nonAtomic",
            "nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed", "replacementValidated", "inventoryConservation")}
        if binding is not None:
            binding.update(placementConfirmed=True, sourceIdentityAndCountVerified=True)
        for key in ("attackPlan", "placePlan"):
            plan = value[key]["data"]
            self.approach_observations.append({"intent": plan["intent"], "pattern": plan["pattern"], "target": dict(point),
                "coverage": plan["coverage"], "stopReason": plan["stopReason"], "complete": plan["complete"],
                "selectedUnknownChecks": 0, "scope": "Only the selected native client/server body and exact face are verified; earlier unknown candidates remain unknown."})
        self.phases.append({"phase": self.phase, "status": "completed", "nativeOwnedReplacement": True,
            "target": _xyz(point), "inventorySlotsConserved": 41, "source": "Original three mutation-epoch observations and native full ItemStack conservation proof."})

    async def replace_ports(self, ports: list[dict[str, Any]] | None = None) -> None:
        native = self.native_owned_replacement_available()
        for index, port in enumerate(self.selected_ports if ports is None else ports):
            point = port["position"]
            block = await self.block(point)
            identity = _block_identity(block)
            _require(identity in port["suggestedCandidates"] and "tileClass" not in block,
                "constructed_baseline_unconfirmed", "Replace only a task-constructed block matching that actual group's public suggestions, without a tile entity.")
            port["constructedBaseline"] = identity
            port["digPlanned"] = True
            if native:
                await self.replace_owned_port(port, identity)
                continue
            self.pending_dig_port = port
            try:
                await self.dig_owned(point, identity, "port_dig_" + str(index), before=block)
            finally:
                self.pending_dig_port = None
            port["digConfirmed"] = True
            port["placementAttempted"] = True
            await self.place(point, port["hotbarSlot"], "port_place_" + str(index), port)
            port["placementConfirmed"] = True

    def survival_frontier_known(self) -> dict[Any, Any]:
        known = {_key(self.args["controller"]): self._placed_controller_observation}
        known.update({_key(record["position"]): {**record["block"], **({"gregtech": {"metaTileId": record["item"]["metadata"]}}
            if record.get("item", {}).get("registryName") == "gregtech:gt.blockmachines" else {})} for record in [*self.supports, *self.selected_ports]
            if record.get("placementConfirmed") is True and (record.get("publicGroupMatched") is True
                or record.get("placementBeforeNativeShell") is True)})
        return known

    async def frontier_pass_blocks(self, points: list[dict[str, Any]], observed: dict[Any, Any]) -> None:
        """Fill a caller-local read-only pass; no snapshot survives a world mutation."""
        union = {_key(target): _xyz(target) for point in points for target in [point, *self.attachment_neighbours(point)]}
        missing = [point for key, point in union.items() if key not in observed]
        if not self.compact_inspection:
            observed.update(await self.blocks(missing))
            return
        for offset in range(0, len(missing), 128):
            pending = missing[offset:offset + 128]
            while pending:
                page = await self.call("world.inspect_many", {"positions": pending, "dimension": self.dimension, "compact": True})
                count, values = self.compact_block_prefix(page, pending)
                observed.update((_key(point), value) for point, value in zip(pending[:count], values))
                pending = pending[count:]

    async def survival_frontier_attachments(self, point: dict[str, Any], *, observed: dict[Any, Any] | None = None) -> list[tuple[dict[str, Any], int]]:
        known = self.survival_frontier_known()
        if observed is None:
            observed = await self.blocks([point, *self.attachment_neighbours(point)])
        actual = self.observed_attachments(point, observed, require_air=True)
        # Compact raw block metadata cannot prove a GT meta-tile identity. Only
        # these exact known anchors need the ordinary full read, once per pass.
        gt_points = [target for target, _ in actual if isinstance(known.get(_key(target)), dict)
            and isinstance(known[_key(target)].get("gregtech"), dict)
            and _block_identity(observed[_key(target)]) == _block_identity(known[_key(target)])
            and "gregtech" not in observed[_key(target)]]
        if gt_points:
            observed.update(await self.blocks(gt_points))
        result = []
        for target, side in actual:
            block, expected = observed[_key(target)], known.get(_key(target))
            owned = isinstance(expected, dict) and _block_identity(block) == _block_identity(expected)
            if owned and isinstance(expected.get("gregtech"), dict):
                owned = block.get("gregtech", {}).get("metaTileId") == expected["gregtech"].get("metaTileId")
            ground = side == 1 and _key(target) not in self.survival_capture_positions and "tileClass" not in block
            if owned or ground:
                result.append((target, side))
        return result

    async def probe_survival_frontier(self, point: dict[str, Any], slot: int, targets: list[tuple[dict[str, Any], int]], *, current_only: bool = False) -> bool:
        original = self.call
        async def bounded(operation: str, arguments: dict[str, Any]) -> dict[str, Any]:
            if operation == "player.plan_approach":
                _require(self.survival_frontier_native_reads < 64, "survival_port_frontier_budget",
                    "The finite frontier budget covers every actual native planning read, including current-only normal-reach adoption.")
                self.survival_frontier_native_reads += 1
            return await original(operation, arguments)
        self.call = bounded
        try:
            return await self._probe_survival_frontier(point, slot, targets, current_only=current_only)
        finally:
            self.call = original

    async def _probe_survival_frontier(self, point: dict[str, Any], slot: int, targets: list[tuple[dict[str, Any], int]], *, current_only: bool = False) -> bool:
        from .workflows import _guard
        self._frontier_plan_handoff = None
        _require(len(self.survival_frontier_observations) < 64, "survival_port_frontier_budget",
            "The task's bounded read-only frontier probes are exhausted; no placement is inferred or replayed.")
        sides = tuple(side for _, side in targets)
        options = dict(intent="place_block", pattern="surround", allowed_sides=sides, hotbar_slot=slot, target_identity=None)
        if self.survival_flight:
            _require(self.actual_survival and self.args.get("movementStrategy") == "ability_flight"
                and type((self.approach_health or {}).get("approachOperationPostureVersion")) is int
                and (self.approach_health or {})["approachOperationPostureVersion"] == 1,
                "native_approach_posture_unavailable", "A frontier flight probe requires the same explicit versioned actual-current hover contract as placement.")
            options.update(operation_posture="flying", movement_strategy="ability_flight")
        current_reader = getattr(self, "current_native_approach", None)
        result, current_proof, use_normal_reach, max_distance = None, False, False, 3
        probe_key = (_key(point), slot, tuple((_key(target), side) for target, side in targets))
        absence = self._frontier_current_absences.pop(probe_key, None) \
            if not current_only and self._frontier_current_absences is not None else None
        if absence is not None:
            # This pass already received complete current-only absence. Proceed
            # straight to a new full native search, which proves its own fresh
            # context/body/reach/ray. Never reuse the negative read as admission.
            use_normal_reach, max_distance = absence
        elif callable(current_reader):
            current = await current_reader(point, **options)
            _require(current.get("status") in ("ready", "no_candidate"), "survival_port_frontier_current_unavailable",
                "Current-pose admission requires complete ready or known-absence proof; unknown/partial does not enable move search.")
            if current.get("status") in ("ready", "no_candidate"):
                pose = current.get("current", {})
                if all(type(pose.get(key)) in (int, float) and math.isfinite(pose[key])
                        for key in ("x", "eyeY", "z", "maxDistance")) and 0 < pose["maxDistance"] <= 32:
                    self._frontier_current_geometry = {"eye": {"x": pose["x"], "y": pose["eyeY"], "z": pose["z"]},
                        "reach": pose["maxDistance"]}
            if current["status"] == "ready":
                result, current_proof = current, True
            elif current.get("useNormalReach") is True:
                use_normal_reach, max_distance = True, current["current"]["maxDistance"]
            if current_only:
                result = current
        elif current_only:
            return False
        args = {**self.point(point), "expectedWorld": self.args["expectedWorld"], "intent": "place_block",
            "pattern": "surround", "requireFlying": self.survival_flight, "maxDistance": max_distance,
            "allowedSides": list(sides), "hotbarSlot": slot, "offset": 0}
        if use_normal_reach:
            args["useNormalReach"] = True
        if result is None:
            strategy = self.args.get("movementStrategy", "walking")
            _require(strategy in ("walking", "ability_flight", "developer_teleport"),
                "unsupported_movement_strategy", "Choose one explicit supported navigation strategy.")
            if "approachMovementStrategyVersion" in (self.approach_health or {}):
                _require(type(self.approach_health["approachMovementStrategyVersion"]) is int
                    and self.approach_health["approachMovementStrategyVersion"] == 1,
                    "native_approach_strategy_unavailable", "An advertised frontier strategy filter requires its exact typed version.")
                options["movement_strategy"] = strategy
            for option, field in (("movement_strategy", "movementStrategy"), ("operation_posture", "operationPosture")):
                if option in options:
                    args[field] = options[option]
            with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
                "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
                result = await self.call("player.plan_approach", args)
        context = response_context(result)
        _require(isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1
            and context.get("operation") == "player.plan_approach"
            and context.get("phase") == "after" and isinstance(context.get("snapshot"), dict),
            "survival_port_frontier_context_unavailable", "Every frontier read requires its fresh guarded native AFTER context.")
        snapshot = context["snapshot"]
        self.validate_snapshot(snapshot)
        _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        self.require_flight(snapshot)
        self.require_survival_server_control(snapshot)
        _require(result.get("source") == "client_server_client" and result.get("sessionId") == self.args["expectedSessionId"]
            and type(result.get("dimension")) is int and result["dimension"] == self.dimension
            and result.get("expectedWorld") == self.args["expectedWorld"]
            and result.get("intent") == "place_block" and result.get("pattern") == "surround"
            and result.get("readOnly") is True and result.get("submitted") is False,
            "survival_port_frontier_receipt_unavailable", "A frontier probe may only consume the exact bounded native read; no mutation or different context is accepted.")
        sample = {"position": _xyz(point), "status": result.get("status"), "readOnly": True,
            "placementDispatched": False, "knownAttachmentSides": list(sides), "sharedCurrentProof": current_proof,
            "nativeReadsSoFar": self.survival_frontier_native_reads, "currentOnlyProbe": current_only,
            "duplicateCurrentReadAvoided": absence is not None}
        self.survival_frontier_observations.append(sample)
        if result.get("status") in ("no_candidate", "client_space_unverified", "client_face_unverified", "unknown", "partial"):
            if result.get("status") == "no_candidate" and current_only and self._frontier_current_absences is not None:
                self._frontier_current_absences[probe_key] = (result.get("useNormalReach") is True, result["current"]["maxDistance"])
            sample["usable"] = False
            return False
        if not current_proof:
            self.validate_native_approach(result, point, **options,
                **({"use_normal_reach": True, "max_distance": max_distance} if use_normal_reach else {}))
        _require(_same(result["current"]["sourceItem"], self.materials[slot]["item"])
            and result["current"]["sourceItem"]["count"] == self.materials[slot]["count"],
            "survival_port_frontier_source_changed", "A ready native frontier must retain the exact actual guarded source identity and count.")
        sample.update(usable=True, selectedFeet=dict(result["selected"]["feet"]),
            attachment=dict(result["selected"]["attachment"]))
        self._frontier_plan_handoff = {"point": self.point(point), "slot": slot, "sides": sides,
            "rpcEpoch": self.tracked_client.rpc_epoch, "expires": time.monotonic() + .75, "plan": copy.deepcopy(result)}
        return True

    async def frontier_structure_read(self, arguments: dict[str, Any], *, operation: str = "world.structure") -> dict[str, Any]:
        from .workflows import _guard
        _require(operation in ("world.structure", "world.spatial"), "survival_seed_read_unavailable", "Only bounded structure/stance observations belong to the frontier.")
        with request_context({"version": 1, "expectedSessionId": self.args["expectedSessionId"],
            "expectedProfile": "developer", "expectedWorld": self.args["expectedWorld"], "requireBackground": True}):
            result = await self.call(operation, arguments)
        context = response_context(result)
        _require(isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1
            and context.get("operation") == operation and context.get("phase") == "after"
            and isinstance(context.get("snapshot"), dict), "survival_seed_structure_context_unavailable",
            "Exact public candidates and finite inventory need their own fresh guarded world.structure AFTER context.")
        snapshot = context["snapshot"]
        self.validate_snapshot(snapshot)
        _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        self.require_flight(snapshot)
        self.require_survival_server_control(snapshot)
        return result

    async def read_position_candidates(self, positions: list[dict[str, Any]]) -> None:
        """Bounded fresh public suggestions for this exact capture, never a representative's list."""
        pending = [point for point in positions if _key(point) not in self.survival_position_candidates]
        _require(len(self.survival_position_candidates) + len(pending) <= _MAX_FRONTIER_CANDIDATE_POSITIONS,
            "survival_seed_candidate_budget", "Exact-position public candidate reads are bounded to 128 positions per task.")
        for start in range(0, len(pending), 16):
            chunk, offset = pending[start:start + 16], 0
            while offset < len(chunk):
                result = await self.frontier_structure_read({**self.point(self.args["controller"]),
                    "captureId": self.capture["captureId"], "candidatePositions": [{**_xyz(point), "group": point["group"]} for point in chunk],
                    "candidateOffset": offset, "limit": 1})
                data = result.get("candidateObservation", {})
                rows, count = data.get("positions"), data.get("processedCount")
                ttl = data.get("ttlMillis", 30_000)
                _require(result.get("source") == "integrated_server" and result.get("sessionId") == self.args["expectedSessionId"]
                    and type(data.get("version")) is int and data["version"] == 1
                    and data.get("source") == "client_public_structure_element_getter" and data.get("readOnly") is True
                    and data.get("ownerBound") is True and data.get("captureMembershipVerified") is True
                    and data.get("captureId") == self.capture["captureId"] and data.get("sessionId") == self.args["expectedSessionId"]
                    and data.get("playerId") == self.survival_player_id and type(data.get("dimension")) is int and data["dimension"] == self.dimension
                    and all(type(data.get(axis)) is int and data[axis] == self.args["controller"][axis] for axis in ("x", "y", "z"))
                    and data.get("capturedFacing") == self.capture.get("capturedFacing")
                    and type(data.get("capturedMode")) is type(self.capture.get("capturedMode"))
                    and data.get("capturedMode") == self.capture.get("capturedMode") and data.get("trigger") == self.capture.get("trigger")
                    and type(ttl) is int and 1 <= ttl <= 120_000
                    and ttl == self.capture.get("limits", {}).get("ttlMillis", 30_000)
                    and type(data.get("ageMillis")) is int and 0 <= data["ageMillis"] <= ttl
                    and data.get("fullStructureChecked") is False and data.get("completeRulesKnown") is False and data.get("formationVerified") is False
                    and type(data.get("requestedCount")) is int and data["requestedCount"] == len(chunk)
                    and type(data.get("candidateOffset")) is int and data["candidateOffset"] == offset
                    and type(count) is int and 1 <= count <= len(chunk) - offset
                    and type(data.get("nextCandidateOffset")) is int and data["nextCandidateOffset"] == offset + count
                    and data.get("complete") is (offset + count == len(chunk)) and data.get("partial") is (offset + count != len(chunk))
                    and all(type(data.get(flag)) is bool for flag in ("byteLimited", "budgetLimited"))
                    and (data["complete"] or data["byteLimited"] or data["budgetLimited"])
                    and isinstance(rows, list) and len(rows) == count,
                    "survival_seed_position_candidates_unavailable", "Exact-position suggestions need a fresh complete bound owner/capture/trigger and a positive, ordered page prefix; omitted evidence is never inferred.")
                for index, (point, row) in enumerate(zip(chunk[offset:offset + count], rows), offset):
                    _require(isinstance(row, dict) and type(row.get("requestIndex")) is int and row["requestIndex"] == index
                        and all(type(row.get(axis)) is int and row[axis] == point[axis] for axis in ("x", "y", "z", "group"))
                        and type(row.get("complete")) is bool and row.get("status") in ("known_suggestions", "unknown")
                        and row["complete"] is (row["status"] == "known_suggestions")
                        and type(row.get("candidateCount")) is int and 0 <= row["candidateCount"] <= 16
                        and isinstance(row.get("detail"), dict),
                        "survival_seed_position_candidates_unavailable", "Each public row must bind the requested exact captured XYZ/group and distinguish known zero from unknown.")
                    detail = row["detail"]
                    if row["complete"]:
                        _require(row.get("publicElementBound") is True and detail.get("loaded") is True
                            and detail.get("candidateCoverage") == "public_stack_suggestions"
                            and detail.get("candidatesTruncated", False) is False
                            and isinstance(detail.get("suggestedCandidates"), list)
                            and len(detail["suggestedCandidates"]) == row["candidateCount"],
                            "survival_seed_position_candidates_unavailable", "Known suggestions need the loaded actual public definition element and all finite returned candidates.")
                    size = len(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
                    _require(self.survival_position_candidate_bytes + size <= _MAX_FRONTIER_CANDIDATE_BYTES,
                        "survival_seed_candidate_byte_budget", "The total retained/read exact-position public candidate rows exceed the independent one MiB bound.")
                    self.survival_position_candidate_bytes += size
                    self.survival_position_candidates[_key(point)] = copy.deepcopy(row)
                offset += count

    async def frontier_inventory(self) -> dict[str, Any]:
        result = await self.frontier_structure_read({**self.point(self.args["controller"]), "captureId": self.capture["captureId"],
            "includeSurvivalInventory": True, "limit": 1})
        inventory = result.get("serverPlayerInventory")
        _survival_inventory_entries(inventory)
        _require(result.get("source") == "integrated_server" and result.get("dimension") == self.dimension
            and result.get("sessionId") == self.args["expectedSessionId"]
            and inventory.get("playerId") == self.survival_player_id and inventory.get("sessionId") == self.args["expectedSessionId"]
            and type(inventory.get("dimension")) is int and inventory["dimension"] == self.dimension,
            "survival_seed_inventory_unavailable", "Seed selection requires this actual player's complete authoritative all41 inventory with full NBT identity.")
        return inventory

    def frontier_seed_choices(self, inventory: dict[str, Any], points: list[dict[str, Any]]) -> dict[tuple[int, int, int], list[dict[str, Any]]]:
        protected = {self.args["blueprintHotbarSlot"], *(port["hotbarSlot"] for port in self.args["requestedPorts"])}
        sources = [row for row in _survival_inventory_entries(inventory) if 0 <= row["index"] <= 35 and row["index"] not in protected
            and row["item"] is not None and row["item"]["hasTag"] is False and row["item"]["nbtIdentity"] == ""]
        choices = {}
        for point in points:
            scoped = self.survival_position_candidates.get(_key(point), {})
            if scoped.get("complete") is not True:
                continue
            valid = []
            for candidate in scoped["detail"]["suggestedCandidates"]:
                if not isinstance(candidate, dict) or candidate.get("hasTag") is not False or candidate.get("nbtIdentity") != "" \
                        or type(candidate.get("count")) is not int or candidate["count"] <= 0 \
                        or candidate.get("blockHasTileEntity") is not False or not isinstance(candidate.get("blockIdentity"), dict):
                    continue
                identity = {key: candidate.get(key) for key in ("registryName", "metadata")}
                if not isinstance(identity["registryName"], str) or identity["registryName"] == "minecraft:air" \
                        or type(identity["metadata"]) is not int or not 0 <= identity["metadata"] <= 32_766 \
                        or candidate["blockIdentity"] != identity:
                    continue
                for source in sources:
                    if _same(source["item"], identity):
                        valid.append({"sourceIndex": source["index"], "item": identity, "nbtIdentity": "", "publicCandidate": copy.deepcopy(candidate)})
            if valid:
                choices[_key(point)] = valid
        return choices

    async def verify_frontier_move(self, before: dict[str, Any], source: int, destination: int) -> dict[str, Any]:
        after = await self.frontier_inventory()
        expected = copy.deepcopy(_survival_inventory_entries(before))
        _require(expected[destination]["item"] is None and expected[source]["item"] is not None,
            "survival_seed_move_unavailable", "A seed borrow only moves one exact finite stack into a real empty slot.")
        expected[destination]["item"], expected[source]["item"] = expected[source]["item"], None
        _require(_survival_inventory_entries(after) == expected,
            "survival_seed_move_unconfirmed", "Every unrelated server stack/full NBT and the empty cursor must remain unchanged through the normal inventory movement.")
        return after

    async def release_frontier_seed_source(self) -> None:
        record = self._frontier_seed_borrow
        if record is None:
            return
        _require(record.get("movementConfirmed") is True and not record.get("outcomeUnknown"),
            "survival_seed_move_unconfirmed", "Unknown inventory movements are never replayed or refunded.")
        if record["count"]:
            before = await self.frontier_inventory()
            screen, _ = await self.open_creative_player_inventory("seed_source_return")
            record["restorationAttempted"] = True
            await self.move_native_player_stack(record, screen, record["temporaryIndex"], record["originalIndex"], "seed_source_return", close_after=True)
            await self.verify_frontier_move(before, record["temporaryIndex"], record["originalIndex"])
            record.update(restorationConfirmed=True, status="restored")
        self.materials.pop(record["temporaryIndex"], None)
        self._frontier_seed_borrow = None

    async def prepare_frontier_seed_source(self, choice: dict[str, Any]) -> int:
        if self._frontier_seed_borrow is not None and self._frontier_seed_borrow["temporaryIndex"] != choice["sourceIndex"]:
            await self.release_frontier_seed_source()
        before = await self.frontier_inventory()
        rows = _survival_inventory_entries(before)
        source = choice["sourceIndex"]
        item = rows[source]["item"]
        _require(_same(item, choice["item"]) and item.get("nbtIdentity") == choice["nbtIdentity"] and item["count"] > 0,
            "survival_seed_source_changed", "The planned exact real seed stack/full NBT must still be present before selection or movement.")
        if source <= 8:
            self.materials[source] = {"item": dict(choice["item"]), "count": item["count"]}
            return source
        protected = {self.args["blueprintHotbarSlot"], *(port["hotbarSlot"] for port in self.args["requestedPorts"])}
        empty = [row["index"] for row in rows if row["index"] <= 8 and row["index"] not in protected and row["item"] is None]
        _require(bool(empty), "survival_seed_hotbar_unavailable", "A backpack casing requires a real empty unreserved hotbar slot; no foreign stack is overwritten.")
        record = {"originalIndex": source, "temporaryIndex": empty[0], "item": dict(choice["item"]), "count": item["count"],
            "nbtIdentity": choice["nbtIdentity"], "status": "retrieving", "purpose": "permanent_frontier_casing_seed"}
        self.material_moves.append(record)
        self._frontier_seed_borrow = record
        screen, _ = await self.open_creative_player_inventory("seed_source_prepare")
        await self.move_native_player_stack(record, screen, source, empty[0], "seed_source_retrieve", close_after=True)
        await self.verify_frontier_move(before, source, empty[0])
        record.update(movementConfirmed=True, status="retrieved")
        self.materials[empty[0]] = {"item": dict(choice["item"]), "count": item["count"]}
        return empty[0]

    async def choose_jump_seed(self, points: dict[Any, Any], choices: dict[Any, Any], goals: set[Any], forbidden: set[Any], used: set[Any]) -> dict[str, Any] | None:
        version = (self.approach_health or {}).get("jumpPlacementVersion")
        if type(version) is not int or version != 1 or self.survival_flight or self.args.get("movementStrategy", "walking") != "walking":
            return None
        owned = {_key(self.args["controller"]), *used}
        candidates = [point for key, point in points.items() if key not in forbidden and key in choices
            and (point["x"], point["y"] - 1, point["z"]) in owned
            # Raising the actor into a remaining port's column would block its
            # later placement with the actor's own full body.
            and not any(goal[0] == point["x"] and goal[2] == point["z"]
                and point["y"] < goal[1] <= point["y"] + 2 for goal in goals)
            and any(0 <= goal[1] - point["y"] <= 2 and abs(goal[0] - point["x"]) + abs(goal[2] - point["z"]) <= 3 for goal in goals)]
        candidates.sort(key=lambda point: (min(abs(goal[0]-point["x"])+abs(goal[2]-point["z"]) for goal in goals), point["y"], _key(point)))
        candidates = candidates[:16]
        if not candidates:
            return None
        actual = await self.blocks(candidates)
        candidates = [point for point in candidates if actual[_key(point)]["registryName"] == "minecraft:air"]
        if not candidates:
            return None
        self.phase = "survival_frontier_jump_stance_probe"
        data = await self.frontier_structure_read({"mode": "positions", "dimension": self.dimension,
            "positions": [_xyz(point) for point in candidates], "width": .9, "height": 3.6, "limit": len(candidates)}, operation="world.spatial")
        rows = data.get("positions")
        _require(data.get("source") == "integrated_server" and data.get("dimension") == self.dimension
            and data.get("hasMore") is False and isinstance(rows, list) and len(rows) == len(candidates),
            "survival_jump_stance_coverage_unavailable", "A jump stance needs complete exact native geometry coverage; unknown cells never cause movement.")
        for point, row in zip(candidates, rows):
            _require(all(row.get(axis) == point[axis] for axis in ("x", "y", "z")), "survival_jump_stance_changed", "Jump stance coordinates changed.")
            if row.get("status") == "observed" and all(row.get(key) is True for key in ("loaded", "occupiable", "canStand")) \
                    and all(row.get(key) is False for key in ("liquid", "hazard", "entityCoveragePartial", "collisionsTruncated")) \
                    and row.get("blockingEntities") == [] and row.get("collisions") == []:
                return {**choices[_key(point)][0], "position": point}
        return None

    async def prepare_verified_frontier_seed(self, choice: dict[str, Any]) -> int:
        point = choice["position"]
        self.survival_position_candidates.pop(_key(point), None)
        await self.read_position_candidates([point])
        inventory = await self.frontier_inventory()
        fresh = self.frontier_seed_choices(inventory, [point]).get(_key(point), [])
        _require(any(entry == {key: choice[key] for key in ("sourceIndex", "item", "nbtIdentity", "publicCandidate")} for entry in fresh),
            "survival_seed_position_candidate_changed", "The exact selected public XYZ candidate/full NBT must remain unchanged before its native placement.")
        return await self.prepare_frontier_seed_source(choice)

    async def extend_survival_frontier(self, pending: dict[int, list[Any]], used: set[tuple[int, int, int]]) -> bool:
        remaining = _MAX_FRONTIER_SEEDS - self.survival_auto_seed_count
        if remaining <= 0 or not self.survival_capture_points:
            return False
        role_goals = [{_key(candidate[1]) for candidate in candidates[:32] if _key(candidate[1]) not in used}
            for candidates in pending.values()]
        goals = set().union(*role_goals)
        if not goals:
            return False
        forbidden = set().union(*(options for options in role_goals if len(options) == 1))
        owned = {_key(self.args["controller"]), *used}
        eligible = [point for key, point in self.survival_capture_points.items() if key not in used and point["y"] > self.floor
            and any(sum(abs(key[index] - goal[index]) for index in range(3)) <= remaining for goal in goals)]
        eligible.sort(key=lambda point: (min(sum(abs(point[axis] - root[index]) for index, axis in enumerate(("x", "y", "z"))) for root in owned), _key(point)))
        available = _MAX_FRONTIER_CANDIDATE_POSITIONS - len(self.survival_position_candidates)
        eligible = [point for point in eligible if _key(point) in self.survival_position_candidates] + [point for point in eligible if _key(point) not in self.survival_position_candidates][:available]
        if not eligible:
            return False
        await self.read_position_candidates(eligible)
        inventory = await self.frontier_inventory()
        choices = self.frontier_seed_choices(inventory, eligible)
        points = {_key(point): point for point in eligible if _key(point) in choices}
        roots = set(owned)
        grounded = [point for point in points.values() if point["y"] == self.floor + 1]
        if grounded:
            below = [{**_xyz(point), "y": point["y"] - 1} for point in grounded]
            actual = await self.blocks(below)
            roots.update(_key(point) for point in below if _key(point) not in self.survival_capture_positions
                and actual[_key(point)]["registryName"] not in ("minecraft:air", "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava")
                and "tileClass" not in actual[_key(point)])
        counts = {row["index"]: row["item"]["count"] for row in _survival_inventory_entries(inventory) if row["item"] is not None}
        path = _shortest_seed_path(points, choices, roots, goals, counts, remaining, forbidden)
        jump_choice = None
        version = (self.approach_health or {}).get("jumpPlacementVersion")
        if type(version) is int and version == 1 and not self.survival_flight \
                and self.args.get("movementStrategy", "walking") == "walking":
            snapshot = await self.snapshot()
            feet = snapshot["player"].get("feetY")
            _require(type(feet) in (int, float) and math.isfinite(feet), "survival_jump_stance_changed",
                "A high-port stance preference requires actual finite current feet height.")
            if any(goal[1] > feet + 2 for goal in goals):
                # Ordinary port search already proved no current reachable
                # candidate. Prefer an existing owned-support jump stance over
                # filling overhead connectivity cells that can obstruct it.
                jump_choice = await self.choose_jump_seed(points, choices, goals, forbidden, used)
        self.survival_seed_planning.append({"capturedPositionsExamined": len(eligible), "materialPositions": len(points),
            "remainingSeedBudget": remaining, "pathLength": len(path) if path is not None else None,
            "preferredUnderfootStance": jump_choice is not None,
            "scope": "Shortest connection within exact observed public material candidates and finite real inventory; every future step still needs fresh air/attachment/native stance/ray and server ACK."})
        if not path and jump_choice is None:
            return False
        # Place only the first step, then rebuild the frontier from real ACKs and current inventory.
        choice = jump_choice if jump_choice is not None else path[0]
        point = choice["position"]
        # Earlier candidate rows are only planning data after a world mutation.
        slot = await self.prepare_verified_frontier_seed(choice)
        attachments = [] if jump_choice is not None else await self.survival_frontier_attachments(point)
        self.phase = "survival_frontier_seed_probe"
        jump_underfoot = jump_choice is not None
        if not jump_underfoot and (not attachments or not await self.probe_survival_frontier(point, slot, attachments)):
            # Complete ordinary absence permits a separate public structural stance plan,
            # never a replay of placement. Native navigation proves the route before input.
            await self.release_frontier_seed_source()
            inventory = await self.frontier_inventory()
            choices = self.frontier_seed_choices(inventory, list(points.values()))
            choice = await self.choose_jump_seed(points, choices, goals, forbidden, used)
            if choice is None:
                return False
            point = choice["position"]
            slot = await self.prepare_verified_frontier_seed(choice)
            jump_underfoot = True
        if jump_underfoot:
            snapshot = await self.snapshot()
            if self._frontier_jump_return is None:
                self._frontier_jump_return = {"x": snapshot["player"]["x"], "y": snapshot["player"]["feetY"], "z": snapshot["player"]["z"]}
            await self.bootstrap_navigation({"x": point["x"] + .5, "y": point["y"], "z": point["z"] + .5}, "walking", "survival_frontier_jump_approach")
        phase = "survival_frontier_seed_" + str(self.survival_auto_seed_count)
        record = {"position": _xyz(point), "group": point["group"], "captureId": self.capture["captureId"],
            "permanentStructureSeed": True, "seedOrigin": "exact_public_position_frontier", "sourceItem": dict(choice["item"]),
            "sourceHotbarSlot": slot, "sourceNbtIdentity": choice["nbtIdentity"], "publicCandidate": choice["publicCandidate"],
            "publicCandidateScope": "exact_captured_xyz_public_getter_before_placement", "publicValidatedBeforePlacement": True,
            "originalTerrainFloor": self.floor, "refunded": False, "publicGroupMatched": False, "frontierVerified": True}
        self.supports.append(record)
        self._survival_frontier_binding = None if jump_underfoot else attachments
        try:
            if jump_underfoot:
                await self.place(point, slot, phase, record, jump_underfoot=True)
            else:
                await self.place(point, slot, phase, record)
            index = record.get("survivalMaterialLedgerIndex")
            _require(record.get("placementConfirmed") is True and record.get("serverConsumptionConfirmed") is True
                and record.get("nativePlaceBlockUsed") is True and record.get("hasTile") is False
                and record.get("block") == choice["publicCandidate"]["blockIdentity"] and not record.get("outcomeUnknown")
                and type(index) is int and 0 <= index < len(self.survival_material_ledger),
                "survival_frontier_seed_unconfirmed", "A permanent seed needs its exact actual native casing and authoritative finite placement receipt.")
            ledger = self.survival_material_ledger[index]
            deductions = ledger.get("deductions", [])
            _require(ledger.get("position") == _xyz(point) and ledger.get("phase") == phase and len(deductions) == 1
                and deductions[0].get("playerInventoryIndex") == slot and deductions[0].get("count") == 1
                and deductions[0].get("nbtIdentity") == choice["nbtIdentity"]
                and all(deductions[0].get(key) == value for key, value in choice["item"].items()),
                "survival_frontier_seed_material_unconfirmed", "The exact source slot/full NBT and one real deduction must bind this seed's native XYZ and phase.")
            record.update(publicGroupMatched=True, retained=True)
            self.survival_seed_positions.add(_key(point))
            used.add(_key(point))
            self.survival_auto_seed_count += 1
        finally:
            self._survival_frontier_binding = None
        return True

    async def install_survival_frontier_ports(self) -> None:
        _require(self.survival_port_candidates is not None and self.survival_port_groups is not None,
            "survival_port_frontier_unavailable", "Complete public candidates must be collected before any frontier placement.")
        pending = dict(self.survival_port_candidates)
        used = set(self.survival_seed_positions)
        while pending:
            self._frontier_current_geometry = None
            self._frontier_current_absences = {}
            chosen = None
            observed: dict[Any, Any] = {}
            windows = self.rank_survival_port_layout(pending, used)
            if self.port_operating_geometry is not None:
                snapshot = await self.snapshot()
                player = snapshot["player"]
                eye = {"x": player.get("x"), "y": player.get("eyeY"), "z": player.get("z")}
                _require(all(type(value) in (int, float) and math.isfinite(value) for value in eye.values()),
                    "survival_port_frontier_pose_unavailable", "Probe ordering requires the actual finite current eye position.")
                # Keep the joint position assignment intact, but ask about the
                # closest assigned cells first. A long line must not re-probe
                # every distant earlier role each time a nearby port succeeds.
                # Distance is ordering only: every admission still needs a fresh
                # native body/ray proof, and no absence survives this pass.
                def distance(entry: tuple[int, list[Any]]) -> float:
                    window = entry[1]
                    return sum(max(window[0][1][axis] - eye[axis], 0, eye[axis] - window[0][1][axis] - 1) ** 2
                        for axis in ("x", "y", "z")) if window else math.inf
                windows = dict(sorted(windows.items(), key=distance))
            # Explicit positions cannot steal another role's assigned cell.
            # Ordinary probing already checks the current pose before planning
            # relocation. Scanning all other nearby fixed cells first repeats
            # occluded current-pose reads after every successful placement.
            fixed_layout = all("relativePosition" in self.args["requestedPorts"][index]
                and len(window) <= 1 for index, window in windows.items())
            if self.port_operating_geometry is not None and not fixed_layout:
                def deferred(point: dict[str, Any]) -> bool:
                    geometry = self._frontier_current_geometry
                    if geometry is None:
                        return False
                    # A placement hit belongs to the destination cube boundary.
                    # Reuse this pass's actual native eye/reach solely to defer
                    # distant current-only probes to ordinary relocation search.
                    # This never claims absence, readiness or permission to move.
                    return sum(max(point[axis] - geometry["eye"][axis], 0,
                        geometry["eye"][axis] - point[axis] - 1) ** 2 for axis in ("x", "y", "z")) > geometry["reach"] ** 2 + 1e-6
                current_probed = {index: set() for index in windows}
                # Give every jointly assigned position a turn before an earlier
                # role's fallback can consume a later role's assigned cell.
                for index, window in windows.items():
                    if self.survival_frontier_native_reads >= 62:
                        break  # Keep the existing two-read ordinary-search reserve.
                    await self.frontier_pass_blocks([candidate[1] for candidate in window], observed)
                    if not window:
                        continue
                    candidate = window[0]
                    attachments = await self.survival_frontier_attachments(candidate[1], observed=observed)
                    if attachments:
                        current_probed[index].add(_key(candidate[1]))
                        if deferred(candidate[1]):
                            continue
                        if await self.probe_survival_frontier(candidate[1], self.args["requestedPorts"][index]["hotbarSlot"],
                                attachments, current_only=True):
                            chosen = index, candidate, attachments
                            break
                # A complete known absence concerns this cell, not the role.
                # Examine at most two attached cells per role in this pass and
                # leave two native reads for the unchanged ordinary search.
                for index, window in windows.items() if chosen is None else ():
                    for candidate in window:
                        if _key(candidate[1]) in current_probed[index]:
                            continue
                        if len(current_probed[index]) >= 2 or self.survival_frontier_native_reads >= 62:
                            break
                        attachments = await self.survival_frontier_attachments(candidate[1], observed=observed)
                        if not attachments:
                            continue
                        current_probed[index].add(_key(candidate[1]))
                        if deferred(candidate[1]):
                            continue
                        if await self.probe_survival_frontier(candidate[1], self.args["requestedPorts"][index]["hotbarSlot"],
                                attachments, current_only=True):
                            chosen = index, candidate, attachments
                            break
                    if chosen is not None:
                        break
                current_probed.clear()  # No absence survives placement, navigation or support changes.
            for index, window in windows.items() if chosen is None else ():
                slot = self.args["requestedPorts"][index]["hotbarSlot"]
                await self.frontier_pass_blocks([candidate[1] for candidate in window], observed)
                for candidate in window:
                    point = candidate[1]
                    attachments = await self.survival_frontier_attachments(point, observed=observed)
                    if not attachments or not await self.probe_survival_frontier(point, slot, attachments):
                        continue
                    chosen = index, candidate, attachments
                    break
                if chosen is not None:
                    break
            self._frontier_current_absences = None
            observed.clear()  # Discard before any seed/port mutation, including uncertain outcomes.
            if chosen is None and await self.extend_survival_frontier(pending, used):
                continue
            _require(chosen is not None, "survival_port_frontier_unavailable",
                "No tested public candidate has a currently observed ground/task-owned attachment and complete native body/ray proof in the declared survival posture; other candidates remain unknown.")
            index, candidate, attachments = chosen
            point = candidate[1]
            port = self.select_port_candidate(index, candidate, self.survival_port_groups,
                self.survival_group_counts[point["group"]])
            port.update(placementBeforeNativeShell=True, frontierVerified=True)
            self._survival_frontier_binding = attachments
            try:
                placed = await self.place(port["position"], port["hotbarSlot"], "port_before_shell_" + str(index), port)
                port["publicEmptyContents"] = {**self.port_empty_contents(placed, port["role"]),
                    "observationPhase": "port_before_shell_" + str(index)}
            finally:
                self._survival_frontier_binding = None
            await self.configure_port_before_shell(port)
            used.add(_key(port["position"]))
            del pending[index]
        await self.release_frontier_seed_source()
        await self.verify_declared_ports("ports_before_shell", preserved=False)

    async def prepare_port_attachment(self, port: dict[str, Any]) -> bool:
        """Prepare an explicitly authorized attachment before, never after failed placement."""
        if self.actual_survival or self.support_mode not in ("command", "command_column"):
            return False
        point = port["position"]
        if await self.attachments(point, require_air=True):
            return False
        snapshot = await self.snapshot()
        player = snapshot.get("player", {})
        server = player.get("serverFlight", {})
        _require(player.get("creative") is True and server.get("available") is True
            and server.get("creative") is True and server.get("dimension") == self.dimension,
            "creative_port_support_unavailable", "Temporary command attachments require current actual client/server creative mode.")
        support = {**_xyz(point), "y": point["y"] - 1}
        reserved_ports = {_key(entry["position"]) for entry in self.selected_ports}
        _require(support["y"] > self.floor and _key(support) not in reserved_ports
            and _key(support) != _key(self.args["controller"]),
            "port_support_position_unavailable", "The bounded temporary attachment must be above terrain and outside declared controller/port cells.")
        if self.placement_plan is not None:
            regions = self.placement_plan.get("assemblyLayout", {}).get("reservedBoxes",
                [self.placement_plan["reservedBounds"]])
            _require(any(all(region["min" + axis.upper()] <= support[axis] <= region["max" + axis.upper()]
                for axis in ("x", "y", "z")) for region in regions), "placement_support_outside_reserved_box",
                "A temporary port attachment must remain inside the freshly proved machine/AE reserved layout.")
        first = len(self.supports)
        try:
            await self.command_supports([support])
        finally:
            for record in self.supports[first:]:
                record.update(purpose="ports_first_attachment", portId=port.get("id"), portPosition=_xyz(point))
        return True

    async def install_ports_before_shell(self) -> None:
        """Use only the selected public role positions; missing support is explicit.

        This deliberately does not fall back to shell construction or retry a
        native placement. Native construction may reject or replace a declared
        port; its later real identity and formation must prove retention.
        """
        if self.survival_seed_mode:
            await self.install_survival_frontier_ports()
            return
        for index, port in enumerate(self.selected_ports):
            if port.get("installPhase") == "after_shell":
                continue
            port["placementBeforeNativeShell"] = True
            prepared_support = await self.prepare_port_attachment(port)
            try:
                placed = await self.place(port["position"], port["hotbarSlot"], "port_before_shell_" + str(index), port)
                port["publicEmptyContents"] = {**self.port_empty_contents(placed, port["role"]), "observationPhase": "port_before_shell_" + str(index)}
            except BridgeError as error:
                if error.details.get("reason") in ("attachment_unavailable", "safe_visible_approach_unavailable"):
                    raise _Unavailable("ports_first_attachment_unsupported", "The selected public port has no observed safe native attachment before the shell; no fallback or mutation replay was attempted.") from None
                raise
            if prepared_support:
                await self.cleanup_supports()
            await self.configure_port_before_shell(port)
        await self.verify_declared_ports("ports_before_shell", preserved=False, before_shell_only=True)

    @staticmethod
    def port_empty_contents(block: dict[str, Any], role: str) -> dict[str, Any]:
        inventory = block.get("inventory")
        _require(isinstance(inventory, dict) and inventory.get("truncated") is False
            and type(inventory.get("totalSlots")) is int and 0 <= inventory["totalSlots"] <= 256
            and isinstance(inventory.get("slots"), list) and len(inventory["slots"]) == inventory["totalSlots"],
            "ports_first_inventory_unsupported", "Every preinstalled port needs complete actual public inventory coverage, including an explicit zero-slot inventory.")
        slots = inventory["slots"]
        _require(all(isinstance(slot, dict) and type(slot.get("index")) is int and "item" in slot for slot in slots)
            and {slot["index"] for slot in slots} == set(range(inventory["totalSlots"])),
            "ports_first_inventory_unsupported", "Actual public port inventory indices must exactly cover the reported finite slots.")
        _require(all(slot["item"] is None for slot in slots), "preinstalled_port_not_empty", "The actual preinstalled port contains items; no content is cleared or replaced.")
        fluid = block.get("fluids")
        fluid_evidence: dict[str, Any] = {"available": fluid is not None}
        if fluid is not None or role in ("fluid_input", "fluid_output"):
            _require(isinstance(fluid, dict) and fluid.get("truncated") is False and type(fluid.get("totalTanks")) is int
                and 0 <= fluid["totalTanks"] <= 32 and isinstance(fluid.get("tanks"), list) and len(fluid["tanks"]) == fluid["totalTanks"],
                "ports_first_fluid_unsupported", "Exposed tanks and declared fluid ports need complete actual public tank coverage.")
            tanks = fluid["tanks"]
            _require(all(isinstance(tank, dict) and type(tank.get("index")) is int for tank in tanks)
                and {tank["index"] for tank in tanks} == set(range(fluid["totalTanks"])),
                "ports_first_fluid_unsupported", "Actual public tank indices must cover every reported tank.")
            _require(all("fluid" not in tank and "amount" not in tank or type(tank.get("amount")) is int and tank["amount"] == 0
                for tank in tanks), "preinstalled_port_not_empty", "The actual preinstalled port contains fluid; no content is cleared or replaced.")
            fluid_evidence.update(available=True, totalTanks=fluid["totalTanks"], allEmpty=True)
        return {"totalInventorySlots": inventory["totalSlots"], "inventoryAllEmpty": True, "fluids": fluid_evidence,
            "coverage": "Complete exposed public inventory/tanks only; hidden buffers and complete machine rules remain unknown."}

    async def verify_declared_ports(self, phase: str, *, preserved: bool, before_shell_only: bool = False) -> None:
        self.phase = phase
        ports = [port for port in self.selected_ports if not before_shell_only or port.get("installPhase") != "after_shell"]
        if preserved:
            self.ports_preserved_verified = False
            for port in ports:
                port["preservedAfterNativeShell"] = False
                if port.get("installPhase") == "after_shell":
                    port["afterShellIdentityVerified"] = False
        observed = await self.blocks([port["position"] for port in ports]) if ports else {}
        for port in ports:
            block = observed[_key(port["position"])]
            actual = {**_block_identity(block), "metaTileId": block.get("gregtech", {}).get("metaTileId")}
            expected_metadata = actual["metaTileId"] if actual["metaTileId"] is not None else actual["metadata"]
            port["afterNativeShell" if preserved else "beforeNativeShell"] = actual
            _require(port.get("placementConfirmed") is True and actual["registryName"] == port["item"]["registryName"]
                and expected_metadata == port["item"]["metadata"],
                "blueprint_port_identity_changed" if preserved else "preinstalled_port_identity_changed",
                "The actual server identity of a declared port changed; the native blueprint is not assumed to preserve ports and no placement is replayed.")
            contents = self.port_empty_contents(block, port["role"])
            port["publicEmptyContents"] = {**contents, "observationPhase": phase}
            if preserved:
                port["preservedAfterNativeShell"] = port.get("installPhase") != "after_shell"
                if port.get("installPhase") == "after_shell":
                    port["afterShellIdentityVerified"] = True
        if preserved:
            self.ports_preserved_verified = any(port.get("installPhase") != "after_shell" for port in ports)

    async def configure_port_before_shell(self, port: dict[str, Any]) -> None:
        if port["role"] == "energy" and "energyTier" in self.args:
            await self.configure_energy_tier(before_shell=True)

    async def verify_energy_tier_after_shell(self) -> None:
        if "energyTier" not in self.args:
            return
        self.phase = "energy_tier_after_shell_validation"
        proof = self.energy_configuration
        _require(isinstance(proof, dict) and proof.get("configured") is True and proof.get("serverConfirmed") is True
            and proof.get("configuredBeforeNativeShell") is True, "energy_tier_before_shell_unconfirmed",
            "The declared pre-shell GUI operation needs its original real server confirmation; it is never repeated after enclosure.")
        actual = await self.block(proof["position"])
        port = next(port for port in self.selected_ports if port["role"] == "energy")
        meta = actual.get("gregtech", {})
        _require(actual.get("registryName") == port["item"]["registryName"]
            and type(meta.get("metaTileId")) is int and meta["metaTileId"] == port["item"]["metadata"]
            and meta.get("metaClass") == _DEBUG_ENERGY_API and type(meta.get("inputVoltage")) is int
            and meta["inputVoltage"] == _DEBUG_ENERGY_VOLTAGES[self.args["energyTier"]],
            "energy_tier_after_shell_changed", "The same real port identity and public voltage must survive the shell; no GUI configuration is replayed.")
        proof.update(preservedAfterNativeShell=True, afterShellVerification="fresh_same_identity_public_voltage_read",
            afterShellInputVoltage=str(meta["inputVoltage"]), laterGuiAccessVerified=False)

    async def configure_energy_tier(self, *, before_shell: bool = False) -> None:
        if "energyTier" not in self.args:
            return
        _require(self.energy_configuration is None, "energy_tier_already_attempted",
            "The task's declared native energy configuration is attempted only once, including after unknown effects.")
        self.phase = "energy_tier_configuration"
        ports = [port for port in self.selected_ports if port["role"] == "energy"]
        _require(len(ports) == 1 and ports[0].get("placementConfirmed") is True, "energy_tier_port_required",
            "The requested energyTier needs one actually placed declared energy port.")
        port, tier = ports[0], self.args["energyTier"]
        target, voltage = port["position"], _DEBUG_ENERGY_VOLTAGES[tier]
        self.energy_configuration = {"requestedTier": tier, "position": target, "placementConfirmed": True,
            "configured": False, "serverConfirmed": False, "expectedInputVoltage": str(voltage),
            "configuredBeforeNativeShell": before_shell, "configurationReplayAllowed": False, "laterGuiAccessVerified": False,
            "voltageSource": "GT5-Unofficial 5.09.54.183 public GTValues.V used by MTEHatchEnergyDebug.maxEUInput()"}
        before = await self.block(target)
        meta = before.get("gregtech", {})
        _require(before["registryName"] == port["item"]["registryName"] and meta.get("metaTileId") == port["item"]["metadata"],
            "energy_tier_port_changed", "The already placed declared energy port identity changed before configuration.")
        _require(meta.get("metaClass") == _DEBUG_ENERGY_API, "energy_tier_port_unsupported",
            "The requested energyTier is unsupported for this already placed energy port; only the observed public Debug Energy Hatch GUI is supported.")
        _, side, steps = await self.approach([(target, side) for side in (2, 3, 4, 5, 1, 0)],
            target_identity={"registryName": before["registryName"], "metadata": before["metadata"],
                "metaTileId": meta["metaTileId"], "metaClass": meta["metaClass"]})
        steps.extend([_read("inventory", "mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot"]),
            _assert("empty_cursor", "inventory", "cursorItem", None),
            _assert("empty_slot", "inventory", "slots", {"index": self.empty_slot, "item": None}, "matches_one"),
            self.hotbar_step("hand", self.empty_slot),
            _read("port", "mcb_world_inspect", self.point(target), ["registryName", "gregtech"]),
            _assert("port_registry", "port", "registryName", port["item"]["registryName"]),
            _assert("port_id", "port", "gregtech.metaTileId", port["item"]["metadata"]),
            _assert("port_api", "port", "gregtech.metaClass", _DEBUG_ENERGY_API),
            _action("open", "mcb_interact", {"action": "use", **target, "side": side, "sneaking": False}),
            _wait("gui", "mcb_gui", {}, "windowId", 0, operator="gt", fields=["screenId", "windowId"])])
        steps[-1]["timeout_ms"] = 1000
        try:
            report = await self.workflow("energy_tier_gui", steps)
        except BridgeError as error:
            if error.code == "WAIT_TIMEOUT":
                raise _Unavailable("debug_energy_gui_unavailable", "The already placed debug energy port did not open a usable native GUI; no interaction is replayed.") from None
            raise
        screen_id = self.value(report, "gui", "screenId")
        controls = await self.control_widgets(screen_id, reason="debug_energy_gui_coverage_unavailable")
        field = debug_energy_tier_widget(controls, screen_id)
        changed = field["value"] != str(tier) or meta.get("inputVoltage") != voltage
        self.energy_configuration.update(observedTierBefore=int(field["value"]), configurationRequired=changed)
        steps = [_read("inventory", "mcb_inventory", fields=["slots", "cursorItem"]),
            _assert("empty_cursor", "inventory", "cursorItem", None),
            _assert("empty_slot", "inventory", "slots", {"index": self.empty_slot, "item": None}, "matches_one")]
        if changed:
            steps.append(_action("set_tier", "mcb_gui_widget_action", {"screenId": screen_id, "widgetId": field["widgetId"],
                "action": "set_text", "text": str(tier), "commit": True}))
        confirmed = _wait("voltage", "mcb_world_inspect", self.point(target), "gregtech.inputVoltage", voltage,
            fields=["registryName", "metadata", "source", "dimension", "x", "y", "z", "gregtech"])
        confirmed["timeout_ms"] = 1500
        steps.extend([confirmed,
            _assert("port_registry", "voltage", "registryName", port["item"]["registryName"]),
            _assert("port_id", "voltage", "gregtech.metaTileId", port["item"]["metadata"]),
            _assert("port_api", "voltage", "gregtech.metaClass", _DEBUG_ENERGY_API)])
        report = await self.workflow("energy_tier_server_confirmation", steps)
        actual = self.workflow_block(report, "voltage", target)
        synchronized = await self.control_widgets(screen_id, reason="debug_energy_gui_coverage_unavailable")
        final = debug_energy_tier_widget(synchronized, screen_id)
        _require(final["widgetId"] == field["widgetId"] and final["value"] == str(tier), "debug_energy_tier_gui_changed",
            "The actual debug energy tier control changed during native synchronization; no action is replayed.")
        await self.workflow("energy_tier_gui_close", [_action("close", "mcb_close_gui", {})])
        self.energy_configuration.update(configured=True, serverConfirmed=True, configurationMutated=changed,
            inputVoltage=str(actual["gregtech"]["inputVoltage"]), synchronizedGuiTier=int(final["value"]),
            widgetPageCoverage={"before": controls["widgetPageCoverage"], "after": synchronized["widgetPageCoverage"]},
            source="Native public Debug Energy Hatch text field commit and same-identity integrated-server inputVoltage getter")

    async def controller_stop_preflight(self, screen_id: str, window_id: int) -> dict[str, Any]:
        fields = ["processing", "components", "structureDescription", "metaTileId", "metaClass", "state", "x", "y", "z", "dimension"]
        report = await self.workflow("controller_stop_preflight", [
            _read("machine", "mcb_machine_inspect", {**self.point(self.args["controller"]), "componentLimit": 128}, fields),
            _read("inventory", "mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot", "totalSlots", "truncated"]),
            _assert("empty_cursor", "inventory", "cursorItem", None),
            _assert("empty_hand", "inventory", "slots", {"index": self.empty_slot, "item": None}, "matches_one"),
            _assert("selected_hand", "inventory", "selectedHotbarSlot", self.empty_slot),
            _read("gui", "mcb_gui", fields=["screenId", "windowId"]),
            _assert("same_screen", "gui", "screenId", screen_id), _assert("same_window", "gui", "windowId", window_id)])
        actual_inventory = {key: self.value(report, "inventory", key)
            for key in ("slots", "cursorItem", "selectedHotbarSlot", "totalSlots", "truncated")}
        _require(set(range(36)).issubset({slot["index"] for slot in inventory_slots(actual_inventory, "")}),
            "construction_stop_inventory_incomplete", "The live stopped-controller lifecycle needs complete actual player slots and an explicitly empty hand/cursor.")
        machine = {key: self.value(report, "machine", key) for key in fields}
        _construction_stop_identity(machine, self.args["controller"], self.dimension, self.args["controllerItem"])
        _require(type(window_id) is int and window_id > 0, "construction_stop_window_unavailable",
            "The task's successful actual controller open must retain its exact positive native window.")
        return machine

    async def accept(self) -> None:
        target = self.args["controller"]
        _, side, steps = await self.approach([(target, side) for side in (2, 3, 4, 5)])
        steps.extend([_read("inventory", "mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot"]),
            _assert("empty_cursor", "inventory", "cursorItem", None),
            _assert("empty_slot", "inventory", "slots", {"index": self.empty_slot, "item": None}, "matches_one"),
            self.hotbar_step("hand", self.empty_slot),
            _action("open", "mcb_interact", {"action": "use", **target, "side": side, "sneaking": False}),
            _wait("gui", "mcb_gui", {}, "windowId", 0, operator="gt", fields=["screenId", "windowId"])])
        report = await self.workflow("controller_gui", steps)
        screen_id = self.value(report, "gui", "screenId")
        stop = self.args.get("stopOnComplete", False)
        before = await self.controller_stop_preflight(screen_id, self.value(report, "gui", "windowId")) if stop else None
        controls = await self.control_widgets(screen_id, semantic=None if stop else "structure_recheck", reason="structure_recheck_coverage_unavailable")
        widgets = controls.get("widgets")
        _require(controls.get("screenId") == screen_id and controls.get("hasMore") is False and controls.get("partial") is False
            and controls.get("scanTruncated") is False and controls.get("byteLimited") is False and isinstance(widgets, list),
            "structure_recheck_coverage_unavailable", "The actual controller GUI needs complete public structure-recheck widget coverage.")
        rechecks = [widget for widget in widgets if isinstance(widget, dict) and widget.get("semantic") == "structure_recheck"
            and widget.get("enabled") is True and widget.get("valid") is True and "recheck_structure" in widget.get("supportedActions", [])]
        _require(len(rechecks) == 1 and isinstance(rechecks[0].get("widgetId"), str), "structure_recheck_unavailable", "One real public semantic recheck widget/action is required; no fallback callback is inferred.")
        formation_wait = _wait("formed", "mcb_machine_inspect", {**self.point(target), "componentLimit": 128}, "processing.structureFormed", True,
            fields=["processing", "components", "structureDescription", "metaTileId", "x", "y", "z", "dimension"])
        formation_wait["timeout_ms"] = 1500
        formation_fields = formation_wait["fields"]
        recheck = _action("recheck", "mcb_gui_widget_action", {"screenId": screen_id,
            "widgetId": rechecks[0]["widgetId"], "action": "recheck_structure"})
        formation_steps = [recheck, formation_wait]
        if stop:
            power = power_widget(controls, before["state"]["allowedToWork"], allow_public_semantic=True)
            formation_fields = [*formation_fields, "metaClass", "state"]
            # One complete read checks formation, stopped state and registered
            # roles together. Later attempts are reads only, never either action.
            formation_steps = [_action("power_off", "mcb_gui_widget_action", {"screenId": screen_id,
                "widgetId": power["widgetId"], "action": "set_selected", "selected": 0}), recheck,
                _read("formed", "mcb_machine_inspect", {**self.point(target), "componentLimit": 128}, formation_fields)]
        try:
            report = await self.workflow("server_formation", formation_steps)
            if stop:
                stop_deadline = min(self.deadline, time.monotonic() + 1.5)
                while True:
                    machine = {key: self.value(report, "formed", key) for key in formation_fields}
                    _construction_stop_identity(machine, target, self.dimension, self.args["controllerItem"], meta_class=before["metaClass"])
                    state = machine["state"]
                    if machine.get("processing", {}).get("structureFormed") is True and state["active"] is False \
                        and state["allowedToWork"] is False and state["progressTicks"] == state["maxProgressTicks"] == 0:
                        break
                    _require(time.monotonic() < stop_deadline, "construction_stop_unconfirmed",
                        "The one native stop/recheck was not confirmed by a current formed, disabled, inactive zero-progress observation; no action is replayed.")
                    await anyio.sleep(min(.05, max(0, stop_deadline - time.monotonic())))
                    report = await self.workflow("controller_stopped_observation", [
                        _read("formed", "mcb_machine_inspect", {**self.point(target), "componentLimit": 128}, formation_fields)])
        except (BridgeError, TimeoutError):
            if self.deadline - time.monotonic() > .1:
                current = await self.call("machine.inspect", {**self.point(target), "componentLimit": 128})
                self.acceptance = {"structureFormed": current.get("processing", {}).get("structureFormed"),
                    "structureErrorBits": current.get("processing", {}).get("structureErrorBits"),
                    "checkRecipeResult": current.get("checkRecipeResult"), "source": "Actual server state after the one native recheck; no inferred repair."}
            raise
        machine = {key: self.value(report, "formed", key) for key in formation_fields}
        _require(all(machine.get(axis) == target[axis] for axis in ("x", "y", "z")) and machine.get("dimension") == self.dimension
            and _same(machine.get("structureDescription", {}).get("controllerStack"), self.args["controllerItem"]),
            "acceptance_controller_changed", "Real formation evidence must belong to the exact placed public controller identity.")
        evidence = []
        self.acceptance = {"structureFormed": machine["processing"]["structureFormed"], "registeredRoles": evidence,
            "registrationAcceptance": {"complete": False, "componentCoverageComplete": False,
                "requestedPorts": len(self.selected_ports),
                "scope": "Exact requested roles and XYZ in exposed public registered components; complete specialized or machine-specific registration is unknown."},
            "source": "Native controller GUI semantic recheck, authoritative current processing.structureFormed and real registered components."}
        if stop:
            self.acceptance["stopAcceptance"] = {"requested": True, "confirmed": False, "nativePowerOffDispatched": True,
                "sameControllerGui": True, "screenId": screen_id,
                "source": "One native public power-off and structure recheck in the task's own controller GUI; complete formed/stopped/registered-component evidence from the same current server read."}
            self.acceptance["stopAcceptance"].update(_construction_stopped_components(machine))
        components = machine.get("components", {})
        entries = components.get("entries")
        _require(components.get("hasMore") is False and type(components.get("total")) is int and isinstance(entries, list)
            and components["total"] == len(entries) <= 128 and isinstance(components.get("roles"), list)
            and isinstance(components.get("unavailableRoles"), list), "registered_role_coverage_incomplete", "Formation acceptance needs one complete real registered-component page.")
        self.acceptance["registrationAcceptance"]["componentCoverageComplete"] = True
        for port in self.selected_ports:
            available = not any(entry.get("role") == port["role"] for entry in components["unavailableRoles"])
            registered = [entry for entry in entries if isinstance(entry, dict) and entry.get("role") == port["role"] and entry.get("valid") is True
                and entry.get("dimension") == self.dimension and all(entry.get(axis) == port["position"][axis] for axis in ("x", "y", "z"))]
            if not available or len(registered) != 1:
                self.acceptance["registrationAcceptance"].update(
                    reason="registered_role_not_accepted" if available else "registered_role_unavailable",
                    unacceptedPort={key: port[key] for key in ("id", "role", "position") if key in port},
                    matchingRegisteredEntries=len(registered))
            _require(available, "registered_role_unavailable", "The requested real registered role is not publicly available.")
            _require(len(registered) == 1, "registered_role_not_accepted", "The real formed controller did not register the exact requested port/role.")
            if "expectedRoleIndex" in port:
                _require(type(registered[0].get("roleIndex")) is int and registered[0]["roleIndex"] == port["expectedRoleIndex"],
                    "registered_port_order_mismatch", "The formed controller's actual public input order differs from the declared port order.")
                port["registeredOrderVerified"] = True
            evidence.append({"role": port["role"], "position": port["position"], "registered": True, "valid": True})
            if "roleIndex" in registered[0]:
                evidence[-1]["roleIndex"] = registered[0]["roleIndex"]
            if "id" in port:
                evidence[-1]["id"] = port["id"]
            if "groupSelection" in port:
                evidence[-1]["groupSelection"] = port["groupSelection"]
            if port["role"] == "energy" and "energyTier" in self.args:
                _require(self.energy_configuration is not None and self.energy_configuration.get("serverConfirmed") is True
                    and registered[0].get("metaClass") == _DEBUG_ENERGY_API and type(registered[0].get("tier")) is int
                    and registered[0]["tier"] == self.args["energyTier"], "registered_energy_tier_changed",
                    "The actually registered debug energy port must retain the requested public input tier after formation.")
                evidence[-1]["inputTier"] = registered[0]["tier"]
                self.energy_configuration["registeredTierConfirmed"] = True
            if port["role"] == "item_output":
                actual = await self.block(port["position"])
                slots = inventory_slots(actual)
                output = await self.call("machine.inspect", self.point(port["position"]))
                storage = output.get("outputStorage", {})
                _require(storage.get("available") is False and isinstance(storage.get("reason"), str) and bool(storage["reason"])
                    and all(slot["item"] is None for slot in slots), "ordinary_output_finite_storage_unavailable", "Ordinary output needs complete actual empty finite inventory and an explicitly unavailable public ME output provider.")
                evidence[-1].update(finiteInventorySlots=len(slots), publicMeProviderAvailable=False)
        self.formation_verified = True
        self.acceptance["registrationAcceptance"]["complete"] = True

    def account_survival_inventory_moves(self, deductions: list[dict[str, Any]]) -> None:
        """Apply only the already verified native slot deductions to moved stacks."""
        for deduction in deductions:
            active = [record for record in [*self.material_moves, *self.borrowed_inventory]
                if record.get("movementDispatched") is True and record.get("restorationConfirmed") is not True
                ]
            pending, seen, source = [], set(), deduction["playerInventoryIndex"]
            while source is not None:
                matches = [record for record in active if record.get("storageIndex", record.get("temporaryIndex")) == source]
                _require(len(matches) <= 1, "survival_moved_material_ledger_mismatch",
                    "A real native source deduction must bind at most one confirmed whole-stack movement at each level.")
                if not matches:
                    break
                record = matches[0]
                _require(id(record) not in seen, "survival_moved_material_ledger_mismatch",
                    "Cyclic movement records cannot account for a native deduction.")
                seen.add(id(record)); pending.append(record)
                source = record.get("originalIndex", record.get("hotbarIndex"))
            # Validate the entire chain before changing any reservation. A
            # temporary hotbar use can consume a stack originally parked by
            # the controller bootstrap; its parent remainder must agree too.
            for record in pending:
                _require(_retainable_move(record) and type(record.get("count")) is int
                    and record["count"] == deduction["sourceCountBefore"]
                    and all(record["item"].get(key) == deduction[key] for key in ("registryName", "metadata"))
                    and (len(pending) == 1 or record.get("nbtIdentity") == deduction["nbtIdentity"])
                    and len(self.survival_material_ledger)-1 not in record.get("survivalMaterialLedgerIndices", []),
                    "survival_moved_material_ledger_mismatch",
                    "Borrowed material identity and pre-consumption count must match the same authoritative source-slot ledger.")
            for record in pending:
                record.setdefault("movedCount", record["count"])
                record["nativeConsumedCount"] = record.get("nativeConsumedCount", 0) + deduction["count"]
                record.setdefault("survivalMaterialLedgerIndices", []).append(len(self.survival_material_ledger) - 1)
                record["count"] = deduction["sourceCountAfter"]
                if record["count"] == 0:
                    record.update(restorationConfirmed=True, status="consumed")

    async def verify_retained_inventory(self) -> None:
        """Confirm retained native moves with one read, without changing the UI."""
        self.phase = "retained_inventory_acceptance"
        pending = [record for record in [*self.material_moves, *self.borrowed_inventory]
            if record.get("movementDispatched") is True and record.get("restorationConfirmed") is not True]
        _require(all(_retainable_move(record) for record in pending), "retained_inventory_uncertain",
            "Unconfirmed or previously attempted native movement cannot prove retained inventory; no action is replayed.")
        for record in pending:
            record.update(retentionValidationAttempted=True, retentionConfirmed=False)
        self.final_inventory_context = None
        if self._workflow_discovery is not None and "inventory.inspect" in self._workflow_discovery.reads:
            actual, self.final_inventory_context = await self.read_with_snapshot("inventory.inspect")
        else:
            actual = await self.call("inventory.inspect", {})
        for record in pending:
            record["retentionObservation"] = {"inventoryTruncated": actual.get("truncated"),
                "inventoryTotalSlots": actual.get("totalSlots"), "cursorObserved": "cursorItem" in actual,
                "cursorItem": _stack_observation(actual.get("cursorItem"))}
        slots = inventory_slots(actual, "")
        if self.tagged_context_inventory is not None:
            await self.read_tagged_context_inventory()
        indexed = {slot["index"]: slot["item"] for slot in slots}
        _require(set(range(36)).issubset(indexed), "player_inventory_unavailable",
            "Complete actual player indices 0..35 are required to verify retained stacks.")
        for record in pending:
            source = record["hotbarIndex"] if "hotbarIndex" in record else record["originalIndex"]
            destination = record["storageIndex"] if "storageIndex" in record else record["temporaryIndex"]
            record["retentionObservation"].update(sourceIndex=source, sourceItem=_stack_observation(indexed.get(source)),
                destinationIndex=destination, destinationItem=_stack_observation(indexed.get(destination)))
        _require("cursorItem" in actual and actual["cursorItem"] is None, "inventory_final_cursor_not_empty",
            "Successful retained inventory needs an explicitly observed empty real cursor.")
        if self.actual_survival:
            def comparable(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
                return [{"index": row["index"], "item": None if row["item"] is None else
                    {key: value for key, value in row["item"].items() if key != "nbtIdentity"}}
                    for row in sorted(rows, key=lambda row: row["index"])]
            _require(self.survival_inventory is not None and comparable(slots) == comparable(self.survival_inventory),
                "survival_final_material_ledger_mismatch", "Complete final real inventory must match only the accounted native consumption and recorded native moves.")
        for record in pending:
            source = record["hotbarIndex"] if "hotbarIndex" in record else record["originalIndex"]
            destination = record["storageIndex"] if "storageIndex" in record else record["temporaryIndex"]
            item = indexed.get(destination)
            _require(indexed.get(source) is None and _same(item, record["item"])
                and type(item.get("count")) is int and item["count"] == record["count"],
                "inventory_retained_identity_changed", "A retained stack must remain at its actual recorded destination with exact identity/count and an empty original source.")
            record.update(retentionConfirmed=True, status="retained")
        self.retained_inventory_verified = True

    async def restore(self) -> None:
        self.phase = "restoration"
        self.restoration_started = True
        self.timing_stage_set("restoration")
        pending = [record for record in self.borrowed_inventory if record.get("movementDispatched") is True and record.get("restorationConfirmed") is not True]
        support_pending = any(record.get("placementConfirmed") is True and not record.get("cleanupAttempted")
            and record.get("permanentStructureSeed") is not True for record in self.supports)
        screen_id = await self.return_controller_stack("material_restoration", close_after=bool(support_pending) or not pending)
        await self.cleanup_supports()
        if pending:
            _require(all(record.get("movementConfirmed") is True and record.get("outcomeUnknown") is not True
                and record.get("restorationAttempted") is not True for record in pending), "borrowed_restoration_uncertain", "Uncertain or attempted native borrowed-stack movement is never replayed.")
            if screen_id is None or support_pending:
                screen_id, _ = await self.open_creative_player_inventory("hand_restoration")
            for index, record in enumerate(pending):
                record["restorationAttempted"] = True
                await self.move_native_player_stack(record, screen_id, record["storageIndex"], record["hotbarIndex"], "hand_return_" + str(index), close_after=index == len(pending) - 1)
                record.update(restorationConfirmed=True, status="restored")
        if self.original_selected is not None:
            await self.workflow("selection_restore", [_action("close", "mcb_close_gui", {}),
                _action("selected", "mcb_hotbar", {"slot": self.original_selected}),
                _wait("selection", "mcb_inventory", {}, "selectedHotbarSlot", self.original_selected)])
            self.selected_restored = True
        actual = await self.call("inventory.inspect", {})
        slots = inventory_slots(actual, "")
        if self.actual_survival:
            await self.verify_retained_inventory()
            self.inventory_restored = all(record.get("restorationConfirmed") is True for record in [*self.material_moves, *self.borrowed_inventory])
            _require(self.inventory_restored, "survival_native_move_restoration_unconfirmed",
                "Borrowed moves must be restored while legitimate consumed survival materials remain consumed.")
            return
        if self.tagged_context_inventory is not None:
            await self.read_tagged_context_inventory()
        self.inventory_restored = actual.get("cursorItem") is None and [{"index": slot["index"], "item": slot["item"]} for slot in slots] == self.original_inventory
        _require(self.inventory_restored, "inventory_final_identity_changed", "Final actual inventory identities/counts must equal the complete pre-task inventory.")

    async def execute(self) -> dict[str, Any]:
        await self.preflight()
        self.timing_stage_set("preparation")
        await self.prepare_inventory()
        await self.place_controller()
        self.timing_stage_set("decision")
        await self.blueprint_use(build=False)
        positions, groups = await self.collect_hints()
        if self.placement_plan is not None:
            geometry = self.placement_plan["geometry"]
            if geometry.get("geometryMode") == "native_visits":
                union = _native_visit_union(positions, groups, self.capture, self.args["controller"])
                _require(union == geometry["nativeVisits"] and self.capture["captureId"] != geometry["captureId"]
                    and self.capture.get("capturedFacing") == geometry["capturedFacing"]
                    and type(self.capture.get("capturedMode")) is type(geometry["capturedMode"])
                    and self.capture.get("capturedMode") == geometry["capturedMode"]
                    and self.capture.get("trigger") == geometry["capturedTrigger"],
                    "placement_native_geometry_changed", "The fresh complete target normalized XYZ set and actual public group partition/semantics, mode, facing and trigger must exactly match before shell construction; bounds/count alone never suffice.")
                self.placement_plan["newNativeGeometryProof"] = {"completeNormalizedXYZAndGroupsMatch": True,
                    "freshCaptureId": self.capture["captureId"], "fullStructureChecked": False, "completeRulesKnown": False}
            else:
                relative = {key: {axis: min(value[axis] - self.args["controller"][axis], 0) if key == "min"
                    else max(value[axis] - self.args["controller"][axis], 0) for axis in ("x", "y", "z")}
                    for key, value in (("min", self.bounds["worldXYZ"]["min"]), ("max", self.bounds["worldXYZ"]["max"]))}
                facing = "_".join(geometry["alignment"][key].upper() for key in ("direction", "rotation", "flip"))
                _require(relative == geometry["relativeBounds"] and len(positions) == geometry["physicalPositions"]
                    and self.capture.get("capturedFacing") == facing
                    and (geometry["captureId"] is None or type(self.capture.get("capturedMode")) is type(geometry["capturedMode"])
                        and self.capture.get("capturedMode") == geometry["capturedMode"])
                    and (geometry["capturedTrigger"] is None or self.capture.get("trigger") == geometry["capturedTrigger"])
                    and all(all(type(row.get(axis)) is int for axis in ("a", "b", "c")) and all(
                        row[axis] - self.args["controller"][axis] == sum(geometry["axisTransform"][index][column]
                            * (row[abc] - geometry["offsets"][abc]) for column, abc in enumerate(("a", "b", "c")))
                        for index, axis in enumerate(("x", "y", "z"))) for row in positions),
                    "placement_native_geometry_changed", "The new native capture must match the full planned public piece bounds/count and actual alignment/mode before construction; no controller is moved or replayed.")
            self.placement_plan["newNativeGeometryVerified"] = True
            if isinstance(geometry.get("sourceCaptureHeader"), dict) and isinstance(self._target_capture_header, dict) \
                    and self.placement_plan.get("controllerPlacementEvidence", {}).get("nativePlaceBlockUsed") is True:
                observed = self._placed_controller_observation or {}
                _require(observed.get("source") == "integrated_server" and type(observed.get("dimension")) is int
                    and observed["dimension"] == self.dimension and all(type(observed.get(axis)) is int
                        and observed[axis] == self.args["controller"][axis] for axis in ("x", "y", "z")),
                    "placement_target_validation_source_unavailable", "Handoff requires the exact authoritative natively placed controller.")
                identity = {key: observed[key] for key in ("registryName", "metadata", "tileClass") if key in observed}
                if "metaTileId" in (observed.get("gregtech") or {}):
                    identity["metaTileId"] = observed["gregtech"]["metaTileId"]
                _require(isinstance(identity.get("registryName"), str)
                    and identity["registryName"] == geometry["sourceIdentity"]["registryName"]
                    and type(identity.get("metadata")) is int and 0 <= identity["metadata"] <= 15
                    and isinstance(identity.get("tileClass"), str)
                    and identity["tileClass"] == geometry["sourceIdentity"].get("tileClass")
                    and (type(identity.get("metaTileId")) is int
                        and identity["metaTileId"] == geometry["sourceIdentity"]["metaTileId"]
                        if "metaTileId" in geometry["sourceIdentity"] else "metaTileId" not in identity)
                    and self._target_capture_header["captureId"] != geometry["captureId"],
                    "placement_target_validation_source_unavailable", "Handoff needs a distinct complete capture and the exact actual placed controller class/identity; world metadata stays separate from the GT meta-tile ID.")
                self.placement_plan["acceptedTargetValidationSource"] = {"version": 1,
                    "sessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"], "dimension": self.dimension,
                    "controller": dict(self.args["controller"]), "sourceIdentity": identity, "metaClass": geometry["sourceMetaClass"],
                    "alignment": copy.deepcopy(geometry["alignment"]), "captureHeader": copy.deepcopy(self._target_capture_header),
                    "geometrySha256": _validation_geometry_sha256(geometry)}
            placement_arguments = {**self.args["placement"], "anchor": self.args["controller"],
                **{key: self.args[key] for key in ("expectedSessionId", "expectedWorld", "dimension")}, "timeout_ms": self.remaining()}
            owned_seeds = []
            if self.actual_survival and self.survival_seed_mode:
                await self.bind_survival_seeds(positions, groups)
                owned_seeds = [{"position": dict(row["position"]), "block": dict(row["block"])}
                    for row in self.supports if row.get("permanentStructureSeed") is True]
            self.placement_plan["beforeNativeConstruction"] = await revalidate_placement(self.tracked_client, placement_arguments,
                self.placement_plan, call=self.call, owned_controller=self.args["controller"],
                owned_seeds=owned_seeds, _discovery=self._workflow_discovery)
        await self.decide_ports(positions, groups)
        self.timing_stage_set("construction")
        if self.construction_order == "ports_first":
            await self.install_ports_before_shell()
            await self.blueprint_use(build=True)
            await self.verify_declared_ports("ports_after_shell", preserved=True, before_shell_only=True)
            await self.verify_survival_seed_blocks("seed_after_shell")
            deferred = [port for port in self.selected_ports if port.get("installPhase") == "after_shell"]
            if deferred:
                await self.replace_ports(deferred)
                await self.verify_declared_ports("ports_after_installation", preserved=True)
        else:
            await self.blueprint_use(build=True)
            await self.replace_ports()
        if self.construction_order == "ports_first" and not any(
                port.get("installPhase") == "after_shell" and port["role"] == "energy" for port in self.selected_ports):
            await self.verify_energy_tier_after_shell()
        else:
            await self.configure_energy_tier()
        self.timing_stage_set("acceptance")
        await self.accept()
        if self.construction_order == "ports_first":
            await self.verify_declared_ports("ports_after_formation", preserved=True)
            await self.verify_survival_seed_blocks("seed_after_formation")
        if self.inventory_restoration_requested:
            await self.restore()
        else:
            await self.verify_retained_inventory()
        if self.connection_plan is not None:
            self.phase = "ae_connections"
            # Terrain planning may have adopted a different controller coordinate.
            self.connection_plan = compile_connections(self.args)
            task, report = self.connection_task, self.connection_report
            task.args["targets"] = self.connection_plan["install"]
            report.update(status="running", plan=self.connection_plan)
            if self.connection_plan["install"]:
                report["beforeInstallationMaterials"] = await task.installation_materials()
            from .assembly_plan import install_connections
            await install_connections(task, self.connection_plan, report)
            report.update(stage="networks", pendingIndex=None)
            report["networkAcceptance"] = await verify_networks(task, self.connection_plan["networks"])
            report.update(status="completed", dispatchedActionAttempts=task.actions)
            self.final_inventory_context = None
        self.phase = "final_context_acceptance"
        final = getattr(self, "final_inventory_context", None)
        if final is None:
            final = await self.snapshot()
        else:
            self.position_snapshot = final
        from .workflows import _guard
        _guard(final, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
        if self._workflow_discovery is not None:
            self._workflow_discovery.bind(self.tracked_client, self.credentials, self.args["expectedSessionId"])
        return {"status": "completed", "available": True}


def _retainable_move(record: dict[str, Any]) -> bool:
    return (record.get("movementDispatched") is True and record.get("movementConfirmed") is True
        and record.get("restorationConfirmed") is not True and record.get("restorationAttempted") is not True
        and record.get("outcomeUnknown") is not True)


def _stack_observation(item: Any) -> dict[str, Any] | None:
    return {key: item[key] for key in ("registryName", "metadata", "count", "hasTag") if key in item} if isinstance(item, dict) else None


def _compact_records(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    keys = ("hotbarIndex", "storageIndex", "originalIndex", "temporaryIndex", "item", "count", "status", "movementDispatched",
        "movementConfirmed", "restorationAttempted", "restorationConfirmed", "retentionValidationAttempted", "retentionConfirmed", "retentionObservation", "outcomeUnknown", "lastRequestId",
        "movedCount", "nativeConsumedCount", "survivalMaterialLedgerIndices", "purpose", "nbtIdentity",
        "initialSeedMoveAttempts", "clientAll41ObservedConserved", "unrelatedTaggedFullNbtProven", "nativePlacementFullNbtAcceptanceRequired")
    return [{key: record[key] for key in keys if key in record} for record in records]


async def run_blueprint_build(client: BridgeClient, arguments: dict[str, Any], lock: anyio.Lock, *,
    runner: Callable[..., Awaitable[dict[str, Any]]] | None = None) -> dict[str, Any]:
    """One bounded task. Shared tool registration remains the caller's responsibility."""
    started = time.monotonic()
    try:
        jsonschema.Draft202012Validator(BLUEPRINT_BUILD_SCHEMA).validate(arguments)
    except jsonschema.ValidationError as error:
        raise BridgeError("INVALID_ARGUMENTS", "Blueprint task declaration is invalid.", argument_path=list(error.absolute_path), rule=error.validator) from None
    if runner is None:
        from .workflows import run_workflow
        runner = run_workflow
    task = _ConstructionTask(client, arguments, runner, started=started)
    result: dict[str, Any]
    acquired = False
    try:
        with anyio.fail_after(task.remaining() / 1000):
            await lock.acquire()
        acquired = True
        result = await task.execute()
        await task.finish_survival_posture()
    except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as error:
        failed_phase = task.phase
        result = {"status": "failed" if task.side_effects else "unavailable", "available": False,
            "reason": error.details.get("reason", error.code) if isinstance(error, BridgeError) else
                "task_timeout" if isinstance(error, TimeoutError) else "malformed_task_observation",
            "error": error.as_dict() if isinstance(error, BridgeError) else {"code": "BLUEPRINT_TASK_FAILED", "message": str(error)[:256], "errorType": type(error).__name__},
            "failedPhase": failed_phase}
        if task.last_action is not None:
            result["lastActionReceipt"] = task.last_action
        if acquired and task.side_effects and task.inventory_restoration_requested and not task.restoration_started:
            try:
                await task.restore()
            except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as restoration:
                task.restoration_error = restoration.as_dict() if isinstance(restoration, BridgeError) else {"code": "RESTORATION_FAILED", "errorType": type(restoration).__name__}
        elif acquired and task.side_effects and any(record.get("placementConfirmed") and not record.get("cleanupAttempted")
                and record.get("permanentStructureSeed") is not True for record in task.supports):
            with anyio.CancelScope(shield=True):
                task.deadline = max(task.deadline, time.monotonic() + 2)
                try:
                    await task.cleanup_supports()
                except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as cleanup_error:
                    task.support_cleanup_error = cleanup_error.as_dict() if isinstance(cleanup_error, BridgeError) else {"code": "SUPPORT_CLEANUP_FAILED"}
    except anyio.get_cancelled_exc_class():
        if acquired and task.side_effects and task.inventory_restoration_requested and not task.restoration_started:
            with anyio.CancelScope(shield=True):
                try:
                    await task.restore()
                except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError):
                    pass
        raise
    finally:
        if acquired:
            lock.release()
    task.timing_stage_set(None)
    elapsed = int((time.monotonic() - started) * 1000)
    inventory = _compact_records([*task.material_moves, *task.borrowed_inventory])
    retained = [record for record in inventory if not task.inventory_restoration_requested
        and _retainable_move(record) and record.get("retentionConfirmed") is True]
    if task.survival_ae_source is not None:
        result["survivalMaterialSources"] = {"playerPlacements": {"sourceType": "actual_player_inventory",
            "scope": "controller_ports_permanent_seeds", "ledger": [row for row in task.survival_material_ledger
                if row.get("sourceType") == "actual_player_inventory"]},
            "nativeShell": {"declaration": copy.deepcopy(task.survival_ae_source),
                "readiness": task.survival_ae_readiness, "binding": task.survival_ae_binding,
                "scope": "bound_cell_container", "wholeGridProviderScopeProven": False, "automaticReplayAllowed": False}}
    if task.survival_flight:
        result.update(survivalOperationPosture="ability_flight", finalPosture=task.final_posture)
    failed_workflows = [phase for phase in task.phases if phase.get("status") not in ("completed", "running")]
    result.update(nonAtomic=True, rolledBack=False, completeRulesKnown=False, formationVerified=task.formation_verified,
        constructionOrder=task.construction_order, portsPreservedAfterNativeShell=task.ports_preserved_verified,
        actualSurvival=task.actual_survival, survivalMaterialLedger=task.survival_material_ledger,
        survivalNativeBuild=task.survival_build_receipt,
        survivalShellCompletion=task.survival_shell_completion,
        hintCaptureRefresh=task.hint_capture_refresh,
        controller={"position": task.args["controller"], "item": arguments["controllerItem"]}, selectedPorts=task.selected_ports,
        floorBlockY=task.floor, observedBounds=task.bounds, acceptance=task.acceptance,
        timings_ms={**{key: int(value) for key, value in task.timings.items()}, "total": elapsed}, taskElapsed_ms=elapsed,
        timingScope="Declaration/lock and all observed decisions, native preparation/construction, real server and retained-inventory acceptance and optional ending restoration; non-overlapping stages, including waits and temporary support cleanup.",
        inventoryPreparationMode=task.inventory_preparation_mode, inventoryClearConfirmed=task.inventory_clear_confirmed,
        inventoryClearReceipt=task.inventory_clear_receipt,
        alreadyMinimalVerified=task.already_minimal_verified,
        materialGenerationRequested=arguments.get("generateMaterials", False), declaredSuppliesVerified=task.declared_supplies_verified,
        materialSupplyMode=task.material_supply_mode,
        volumeInspection=task.volume_inspection,
        generatedMaterials=task.material_grants_dispatched > 0, materialGrantsDispatched=task.material_grants_dispatched,
        supportPreparationMode=task.support_mode, fullStructureChecked=False,
        inventoryRestorationRequested=task.inventory_restoration_requested, retainedInventoryMoves=retained,
        sideEffectsPossible=task.side_effects, cleanup={"inventoryRestored": task.inventory_restored,
            "selectedHotbarRestored": task.selected_restored, "retainedInventoryVerified": task.retained_inventory_verified, "supports": task.supports,
            "allConfirmedSupportsRemoved": all(record.get("cleanupConfirmed") is True for record in task.supports if record.get("placementAttempted")),
            "allConfirmedTemporarySupportsRemoved": all(record.get("cleanupConfirmed") is True for record in task.supports
                if record.get("placementAttempted") and record.get("permanentStructureSeed") is not True),
            "permanentSeedsRemoved": False,
            "permanentSeedCleanupMeaning": "Known task-owned permanent seeds are retained at their exact XYZ; cleanup never means they were removed.",
            "permanentStructureSeeds": [record for record in task.supports if record.get("permanentStructureSeed") is True],
            "error": task.restoration_error, "supportError": task.support_cleanup_error,
            "worldEditSelection": task.selection_cleanup}, inventoryMoves=inventory,
        pendingInventoryRestoration=[record for record in inventory if record.get("movementDispatched") is True
            and record.get("restorationConfirmed") is not True and record not in retained])
    if failed_workflows:
        result["failedNativeWorkflows"] = failed_workflows[-4:]
    if task.energy_configuration is not None:
        result["energyConfiguration"] = task.energy_configuration
    if task.shell_exit_evidence:
        result["shellActorBodyExclusion"] = task.shell_exit_evidence
    if task.bootstrap_adjacent is not None:
        result["bootstrapAdjacentSeed"] = {**task.bootstrap_evidence, "upAttempted": task.bootstrap_up_attempted, "downAttempted": task.bootstrap_down_attempted}
    if task.initial_controller_seed_mode:
        result["initialControllerSeeds"] = {"declarations": arguments["initialControllerSeeds"],
            "originalTerrainFloor": task.floor, "plannedPositions": task.survival_seed_plan,
            "placementPhaseAttempted": task._initial_seeds_attempted,
            "sourceBindings": [{"declaredPlayerInventoryIndex": index, **binding} for index, binding in task.initial_seed_sources.items()],
            "publicValidatedBeforePlacement": False, "permanent": True, "refunded": False,
            "unrelatedTaggedFullNbtProvenBeforePlacement": False,
            "scope": "Every initial layer is caller-declared, observed literal air and native finite materials. Borrowed participating stacks have exact untagged NBT; unrelated tagged NBT is unknown in client move observations. Every native placement separately requires full all41 authoritative before/after NBT conservation. Later exact captured XYZ public candidates and independent formation are still required; no reachability or formation promise."}
    if task.survival_seed_mode:
        result["survivalPortFrontier"] = {"readOnlyProbes": task.survival_frontier_observations,
            "maxNativeProbes": 64, "nativePlanningReads": task.survival_frontier_native_reads, "maxCandidatesPerRolePerPass": 32,
            "automaticPermanentSeeds": task.survival_auto_seed_count, "maxAutomaticPermanentSeeds": _MAX_FRONTIER_SEEDS,
            "maxExactPublicCandidatePositions": _MAX_FRONTIER_CANDIDATE_POSITIONS,
            "maxExactPublicCandidateBytes": _MAX_FRONTIER_CANDIDATE_BYTES,
            "exactPublicCandidateBytesRead": task.survival_position_candidate_bytes,
            "exactPublicCandidatePositionsRead": len(task.survival_position_candidates), "seedPlanning": task.survival_seed_planning,
            "initialSeedScope": "Caller-declared controller-column seeds precede hints and are publicly bound at their exact captured XYZ afterward; they are not claimed publicly validated before placement.",
            "scope": "Exact public-position casing candidates and finite full-NBT inventory provide bounded shortest connectivity plans. Every actual seed/port uses current observed ground or confirmed task-owned attachments with grounded native body/ray and server ACK proof; untested candidates and full rules remain unknown."}
    if task.approach_summary() is not None:
        result["approachPlanning"] = task.approach_summary()
    sync_observations = [phase for phase in task.phases if phase.get("phase") == "supported_placement_sync_observation"]
    if sync_observations:
        result["placementSyncObservations"] = sync_observations
    if task.placement_plan is not None:
        result["placement"] = task.placement_plan
    if task.connection_report is not None:
        if result["status"] != "completed":
            task.connection_report["status"] = "failed" if task.connection_report["status"] != "completed" else "completed"
        task.connection_report["dispatchedActionAttempts"] = task.connection_task.actions
        result["connections"] = task.connection_report
        result["inventoryAcceptanceScope"] = "Construction inventory acceptance precedes AE actions; connections retains independent native per-placement full-NBT/source-consumption acknowledgements."
    if task.position_selection_policy is not None:
        result["positionSelectionPolicy"] = task.position_selection_policy
    if task.last_action is not None and result["status"] != "completed" and "lastActionReceipt" not in result:
        result["lastActionReceipt"] = task.last_action
    return compact_blueprint_report(result, arguments.get("includeDetails", False))
