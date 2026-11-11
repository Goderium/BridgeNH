"""Bounded declarative workflows over existing operations, without executable code."""

from __future__ import annotations

import math
import re
import time
from collections import deque
from decimal import Decimal, localcontext
from contextlib import nullcontext
from dataclasses import dataclass
from typing import Any

import anyio

from .bridge import BridgeClient, BridgeError, CONTEXT_ACTIONS, CONTEXT_READS, request_context, response_context, placement_failure_fields
from .conveniences import json_sha256, json_size, path_get
from .tools import SEQUENCE_ACTION_NAMES, SEQUENCE_READ_NAMES, TOOL_BY_NAME
from .validation import validate_tool_arguments
from .knowledge import record_wait_sample
from .provider_counts import provider_count, provider_delta, provider_delta_baseline
from .survival_checkpoints import SURVIVAL_OPERATION, survival_failure_checkpoint

MAX_PLAN_BYTES = 65_536
MAX_RAW_BYTES = 2_097_152
MAX_METADATA_NODES = 8192
MAX_METADATA_MILLIS = 10
MIN_METADATA_NODES = 32
ACTION_FIELDS = ("status", "submitted", "accepted", "sent", "action", "nativeAction", "nativeCallback", "nativeContainerSlotId", "localEffect", "nativeDiagnostic", "sessionId", "screenId",
    "previousScreenId", "screenOpen", "screenKind", "windowId", "ticketId", "slot", "x", "y", "z",
    "yaw", "pitch", "blockTarget", "viewBinding", "viewRestored", "endingView", "airUseFallback",
    "interactedWithBlock", "nativeInvoked", "attachment", "targetBefore", "selectedHotbarSlot", "serverConfirmed", "executionValidated", "allowedToWork", "flying", "ticks", "completion", "ghostRelease")
NEI_PREVIEW_ACTION_FIELDS = (*ACTION_FIELDS, "source", "contextToken", "channel", "controlIndex", "previousValue", "value", "minimum", "maximum",
    "requiresViewRefresh", "contextPreserved", "contextRefreshed", "currentScreenChanged", "effectStatus", "callbackMillis", "previewAfter", "previewAfterSource", "camera", "selection", "candidateGroupCount",
    "captureId", "captureStatus", "ownerPosition", "position", "pixelDataStored", "graphicsComplete")
GUI_POINTER_ACTION_FIELDS = (*ACTION_FIELDS, "pointer", "generation", "remainingTTL", "currentScreenBound", "source", "target", "acceptance")
NEI_ITEM_ACTION_FIELDS = (*ACTION_FIELDS, "before", "after", "filterCompletion", "item", "inventoryCountBefore", "expectedInventoryDelta", "acquisitionVerified")
AE_ACTION_FIELDS = (*ACTION_FIELDS, "source", "before", "target", "acceptedType", "configurationOnly", "inventoryConsumed", "nativeProtocol", "syncFlushInvoked", "serverAcceptance")
BINDABLE = frozenset({"screenId", "windowId", "slot", "hotbarSlot", "side", "button", "mode", "widgetId", "resourceId", "neiItemId", "source", "runId", "expectedMetaTileId", "expectedWorld",
    "expectedSessionId", "materialSource", "yaw", "pitch", "x", "y", "z", "amount", "value", "text", "selected", "serial",
    "expectedCpuSerial", "index", "working", "paused", "since", "captureId", "contextToken", "offset", "handlerIndex", "recipeIndex",
    "positionIndex", "alternativeIndex", "itemIndex", "lineIndex", "metadataIndex", "groupId", "badgeIndex", "configPath", "subsetPath", "channel", "controlIndex",
    "rotationYaw", "rotationPitch", "zoom", "center", "position", "groupIndex", "textureSide", "previewPosition", "modifiers"})
KNOWN_REFUSALS = frozenset({"stale_screen", "stale_container", "stale_widget", "stale_resource", "stale_session",
    "invalid_args", "stale_context", "permission_denied", "background_required", "input_busy", "gui_open", "no_player",
    "chunk_unloaded", "face_not_visible", "out_of_reach", "blocked", "world_paused", "unsupported", "busy", "REQUEST_TOO_LARGE",
    "CONFIGURATION_ERROR", "SESSION_CHANGED", "AUTH_FAILED", "UNSUPPORTED_CAPABILITY", "qa_busy", "stale_run", "qa_test_unknown"})
DEFAULT_FIELDS = {
    "mcb_health": ["sessionId", "profile", "clientTicks", "background", "readiness", "interactionSneaking", "interactionBlockTargeting", "interactionViewBinding", "interactionAttackViewBinding", "recipesQuery", "nativeBlockPlacement", "screenKinds", "serverPauseGuard"],
    "mcb_snapshot": ["sessionId", "profile", "worldLoaded", "world", "player", "screenId", "screen", "screenOpen", "screenKind", "paused", "target"],
    "mcb_gui": ["screenId", "windowId", "screen", "screenOpen", "screenKind", "width", "height", "buttons", "slots", "creativeInventory"],
    "mcb_inventory": ["selectedHotbarSlot", "cursorItem", "slots"],
    "mcb_machine_inspect": ["metaTileId", "state", "processing.structureFormed", "processing.structureErrorBits", "processing.recipesDone", "processing.signedCalculatedEUt", "structureDescription", "machineDescription", "structureTooltip", "moduleType", "executionCore", "components", "outputStorage", "coverage", "budget"],
    "mcb_world_structure": ["mode", "controller", "definition", "pieces", "piecesTotal", "piece", "compiledEntries", "pieceBounds", "offsets", "coordinateSpace", "worldPositionsAvailable", "alignment", "positions", "runtimeObservation", "registeredPrototype", "prototypeScope", "prototypeSource", "runtimeClass", "structureFormationVerified", "fullMachineBoundsAvailable", "machineDescription", "structureDescription", "structureTooltip", "boundsOnly", "descriptionCoverage", "cache", "coverage", "budget", "limits"],
    "mcb_world_inspect": ["x", "y", "z", "registryName", "metadata", "tileClass", "inventory", "fluids", "gregtech"],
    "mcb_gui_widgets": ["screenId", "widgets"],
    "mcb_waila": ["cacheTarget", "matchesVanillaCrosshair", "sections", "nbtCoordinatesMatch"],
    "mcb_gui_tooltip": ["screenId", "source", "item", "tooltip"],
    "mcb_nei_view": ["screenId", "section", "contextToken", "status", "data", "coverage", "limits", "budget"],
    "mcb_mods": ["mods"], "mcb_messages": ["messages"],
    "mcb_items": ["items"], "mcb_item": ["registryName", "displayName", "metadata", "tooltip"], "mcb_item_variants": ["items"],
    "mcb_world_inspect_many": ["results"], "mcb_world_tiles": ["tiles"], "mcb_entities": ["entities"],
    "mcb_spatial": ["mode", "status", "hit", "positions"], "mcb_worlds": ["worlds"],
    "mcb_qa_catalog": ["available", "section", "entries", "runnerConfig"],
    "mcb_qa_run": ["runId", "selector", "selectedTests", "selectedIds", "startStatus", "state", "correlation"],
    "mcb_qa_status": ["runId", "state", "nativeBatchActive", "correlation", "nativeReport"],
    "mcb_qa_results": ["runId", "section", "entries", "correlation"],
    "mcb_qa_command": ["submitted", "command", "args", "commandOutcome", "runId"],
    "mcb_qa_cancel": ["available", "submitted", "cancelled", "reason", "runId"],
}
META_KEYS = frozenset({"coverage", "budget", "limits", "nonAtomic", "source", "offset", "nextOffset", "hasMore",
    "complete", "nextCursor", "stopReason", "loaded", "status", "sessionId", "worldTick", "clientTicks",
    "total", "totalMatched", "totalRegistered", "visited", "candidatesVisited", "loadedCandidates",
    "available", "reason", "correlation", "owned", "freshSinceStart", "runId", "startStatus", "nativeBatchActive", "contextToken", "section", "unit", "offsetUnit",
    "frameId", "frameAgeMillis", "observerActive", "droppedTextDraws", "coordinateSpace", "worldPositionsAvailable",
    "projectionLimit", "mappedDraws", "unmappedDraws", "requiresViewRefresh", "resultBytes", "captureBudgetNanos",
    "textDrawsTotal", "capturedCodepoints", "projectionAvailable", "screenPositionStatus", "visibilityStatus",
    "captureDurationNanos", "captureBudgetLimited", "filteredNonNeiDraws", "unscopedDrawsAfterBudget", "observerErrors",
    "contextPreserved", "contextRefreshed", "captureStatus", "emptyCapture", "terminalHookCalls", "privateLineHookCalls", "shadowPassSkips",
    "terminalAncestorSkips", "emptyTextInputs", "recordingAttempts", "terminalHooksLifetime", "privateLineHooksLifetime",
    "shift", "ctrl", "control", "alt", "virtualModifiers", "support", "hookObserved", "hookTarget", "limitations", "missingCapabilities",
    "mouseX", "mouseY", "tooltipPhase", "observerRegistered", "callbackCalls", "originalTooltipLines", "capturedTooltipLines", "captureLimited",
    "visibleInformation", "resourceContext", "coordinateFrame", "candidateRulesCoverage", "positionSource",
    "selectedCandidateGroupCount", "candidateCacheMatchesPosition", "candidateCacheSource", "candidateCacheMatchesSelection",
    "candidateSource", "candidateStatus", "hintProvenance", "tooltipSource", "textureReadSource", "textureGraphicsCoverage",
    "textureSide", "maximumTraversedNodes", "maximumTextureEntries", "textureRootSource", "textureModelComplete",
    "textureModelLimitReason", "unresolvedTextureEntries", "pixelDataStored", "passesComplete", "ctmStatus", "derivedSourceStatus",
    "frontFacing", "active", "colorization", "getterNameStatus", "visualSource", "itemRendererClass", "itemRendererStatus",
    "itemVisualKind", "inventoryTextureSource", "inventoryRenderContext", "inventoryModelStatus", "visualCoverage",
    "generation", "remainingTTL", "currentScreenBound", "pointerSource", "pointerGeneration", "cursorSource", "naturalDrawInvoked", "nativeTooltipGate",
    "physicalMouseX", "physicalMouseY", "physicalCoordinateSource",
    "provenance", "forced", "decisionObserved", "sampledShouldShowTooltip",
    "captureId", "naturalFramesObserved", "ownerPosition", "position", "observerNanos", "maximumObserverNanos", "maximumVertices", "maximumEntries", "gaps", "graphicsComplete",
    "everObservedInNaturalExecution", "observedInRequestedNaturalFrame", "meaning",
    "blockRenderType", "blockRenderPass", "nativeBlockInventory3d", "itemTintColorRGB", "blockRenderColorRGB"})
META_DIAGNOSTIC_STRING_LISTS = frozenset({"limitations", "missingCapabilities", "gaps"})


def json_equal(left: Any, right: Any) -> bool:
    if type(left) is bool or type(right) is bool:
        return type(left) is type(right) and left == right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        return len(left) == len(right) and all(json_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(json_equal(v, right[k]) for k, v in left.items())
    return left == right


def number(value: Any) -> Decimal | None:
    if type(value) in (int, float) and (type(value) is int or math.isfinite(value)):
        return Decimal(str(value))
    if isinstance(value, str) and re.fullmatch(r"-?(?:0|[1-9][0-9]*)", value) and len(value) <= 128:
        return Decimal(value)
    return None


def complete_item_count(observation: Any, path: str, match: dict[str, Any]) -> Decimal:
    exists, slots = path_get(observation, path)
    parent_path = path.rsplit(".", 1)[0] if "." in path else ""
    parent_exists, parent = path_get(observation, parent_path) if parent_path else (True, observation)
    if not exists or not isinstance(slots, list) or len(slots) > 4096:
        raise BridgeError("CONDITION_TYPE", "Item deltas require a bounded real inventory slot array.")
    if not parent_exists or not isinstance(parent, dict) or parent.get("truncated") is not False or type(parent.get("totalSlots")) is not int or parent["totalSlots"] != len(slots):
        raise BridgeError("INCOMPLETE_OBSERVATION", "Item deltas require explicit complete inventory coverage for both observations.",
                          path=path, observed_slots=len(slots),
                          declared_slots=parent.get("totalSlots") if isinstance(parent, dict) and type(parent.get("totalSlots")) is int else None,
                          truncated=parent.get("truncated") if isinstance(parent, dict) and type(parent.get("truncated")) is bool else None)
    total = Decimal(0)
    for entry in slots:
        if not isinstance(entry, dict) or "item" not in entry or not any(type(entry.get(key)) is int for key in ("slot", "index", "slotNumber")):
            raise BridgeError("CONDITION_TYPE", "Item deltas require real inventory slot identities.")
        if all((found := path_get(entry, key))[0] and json_equal(found[1], target) for key, target in match.items()):
            found, count = path_get(entry, "item.count")
            amount = number(count) if found and type(count) in (int, str) else None
            if amount is None or amount < 0:
                raise BridgeError("CONDITION_TYPE", "A matched item stack has no exact nonnegative quantity.")
            total += amount
    return total


def condition_matches(observation: Any, condition: dict[str, Any]) -> bool:
    if condition["operator"] == "provider_count_delta":
        delta = provider_count(observation, condition["path"], condition["match"]) - condition["_baselineCount"]
        expected = provider_delta(condition["value"])
        if delta < 0 or delta > expected:
            raise BridgeError("PROVIDER_DELTA_CHANGED", "The queried local cache decreased or exceeded the exact expected output delta.")
        return delta == expected
    if condition["operator"] == "item_count_delta":
        delta = complete_item_count(observation, condition["path"], condition["match"]) - condition["_baselineCount"]
        expected = number(condition["value"])
        if expected is None:
            raise BridgeError("CONDITION_TYPE", "Item deltas require an exact finite signed expected quantity.")
        if delta < min(Decimal(0), expected) or delta > max(Decimal(0), expected):
            raise BridgeError("ITEM_DELTA_CHANGED", "The complete item quantity moved outside the exact finite expected delta; later actions were blocked without replay.")
        return delta == expected
    exists, value = path_get(observation, condition["path"])
    op = condition["operator"]
    if op == "inventory_empty":
        parent_path = condition["path"].rsplit(".", 1)[0] if "." in condition["path"] else ""
        found, parent = path_get(observation, parent_path) if parent_path else (True, observation)
        if not exists or not isinstance(value, list) or not found or not isinstance(parent, dict) \
                or parent.get("truncated") is not False or type(parent.get("totalSlots")) is not int \
                or parent["totalSlots"] != len(value) or len(value) > 4096:
            raise BridgeError("INCOMPLETE_OBSERVATION", "Empty inventory checks require complete explicit slot coverage.")
        ids = [next((entry[key] for key in ("index", "slot", "slotNumber") if type(entry.get(key)) is int), None)
            if isinstance(entry, dict) and "item" in entry else None for entry in value]
        if any(identity is None for identity in ids) or len(set(ids)) != len(ids):
            raise BridgeError("CONDITION_TYPE", "Empty inventory checks require unique actual slot identities.")
        return all(entry["item"] is None for entry in value) == condition["value"]
    if op == "exists":
        return exists == condition.get("value", True)
    if op == "fields_equal":
        expected = condition["value"]
        if not exists or not isinstance(value, dict) or not isinstance(expected, dict) or not 1 <= len(expected) <= 8:
            raise BridgeError("CONDITION_TYPE", "Field equality requires an observed object and 1..8 scalar fields.")
        matched = True
        for path, target in expected.items():
            found, actual = path_get(value, path)
            numeric = type(target) in (int, float)
            if not found or type(target) not in (str, int, float, bool, type(None)) \
                    or (numeric and (type(actual) not in (int, float) or number(actual) is None or number(target) is None)) \
                    or (not numeric and type(actual) is not type(target)):
                raise BridgeError("CONDITION_TYPE", "Every compared field requires its observed scalar type and finite quantity.", path=path)
            matched &= json_equal(actual, target)
        return matched
    if not exists:
        return False
    expected = condition["value"]
    if op == "json_sha256_equals":
        if type(expected) is not str or re.fullmatch(r"[0-9a-f]{64}", expected) is None:
            raise BridgeError("CONDITION_TYPE", "JSON fingerprint equality requires one lowercase 64-character SHA-256 digest.")
        try:
            return json_sha256(value) == expected
        except (TypeError, ValueError, UnicodeError, RecursionError):
            raise BridgeError("CONDITION_TYPE", "JSON fingerprint equality requires a complete finite JSON value.") from None
    if op in {"matches_one", "matches_none"}:
        if not isinstance(value, list) or len(value) > 4096:
            raise BridgeError("CONDITION_TYPE", "Live matching requires an observed array of at most 4096 entries.")
        matches = 0
        for entry in value:
            if all((found := path_get(entry, key))[0] and json_equal(found[1], target) for key, target in expected.items()):
                matches += 1
                if matches > (1 if op == "matches_one" else 0):
                    return False
        return matches == (1 if op == "matches_one" else 0)
    if op in {"equals", "not_equals"}:
        same = json_equal(value, expected)
        return same if op == "equals" else not same
    if op in {"gt", "gte", "lt", "lte"}:
        left, right = number(value), number(expected)
        if left is None or right is None:
            raise BridgeError("CONDITION_TYPE", "Numeric comparison requires finite numbers or canonical decimal quantity strings.")
        return {"gt": left > right, "gte": left >= right, "lt": left < right, "lte": left <= right}[op]
    if isinstance(value, str) and isinstance(expected, str):
        return expected in value
    if isinstance(value, list):
        return any(json_equal(item, expected) for item in value)
    return isinstance(value, dict) and isinstance(expected, str) and expected in value


def quantity_delta_evidence(observation: Any, condition: dict[str, Any]) -> dict[str, Any] | None:
    if condition["operator"] == "item_count_delta":
        baseline = condition["_baselineCount"]
        current = complete_item_count(observation, condition["path"], condition["match"])
        expected = number(condition["value"])
        if expected is None:
            raise BridgeError("CONDITION_TYPE", "Item deltas require an exact finite signed expected quantity.")
        with localcontext() as context:
            context.prec = max(current.adjusted(), baseline.adjusted(), 0) \
                - min(current.as_tuple().exponent, baseline.as_tuple().exponent, 0) + 2
            observed = current - baseline
        return {"baseline": str(baseline), "current": str(current),
                "delta": str(expected), "expectedDelta": str(expected), "observedDelta": str(observed),
                "match": condition["match"]}
    if condition["operator"] == "provider_count_delta":
        baseline = condition["_baselineCount"]
        current = provider_count(observation, condition["path"], condition["match"])
        expected = provider_delta(condition["value"])
        return {"baseline": str(baseline), "current": str(current),
                "delta": str(expected), "expectedDelta": str(expected), "observedDelta": str(current - baseline),
                "match": condition["match"],
                "scope": "local_output_provider_cache_only"}
    return None


def validate_args(name: str, args: dict[str, Any]) -> None:
    # Only internally assembled navigation may carry a bound executor.
    if name == "mcb_navigate" and "strategy" in args:
        from .validation import execution_validator
        import jsonschema
        try:
            execution_validator(name).validate(args)
        except jsonschema.ValidationError:
            raise BridgeError("INVALID_ARGUMENTS", "Invalid internal navigation arguments.") from None
        args = {key: value for key, value in args.items() if key != "strategy"}
    validate_tool_arguments(name, args)


def _witness(name: str, key: str) -> Any:
    schema = TOOL_BY_NAME[name].schema["properties"].get(key)
    if key not in BINDABLE or schema is None:
        raise BridgeError("INVALID_ARGUMENTS", "Binding is not allowed for this argument.", tool=name, argument=key)
    if name == "mcb_survival_build" and key == "materialSource":
        return {"type": "ae_cells", "mode": "finite", "selector": "tile", "x": 0, "y": 0, "z": 0,
            "side": 0, "expectedGridId": "00000000-0000-0000-0000-000000000000"}
    if key == "source" and name in {"mcb_ae_action", "mcb_gui_widget_action"}:
        return {"neiItemId": "bound"}
    if name == "mcb_nei_preview_control":
        if key == "value":
            return 1
        if key in {"center", "position", "previewPosition"}:
            return {"x": 0, "y": 0, "z": 0}
    if name == "mcb_nei_view":
        if key == "previewPosition":
            return {"x": 0, "y": 0, "z": 0}
        if key == "modifiers":
            return {"shift": False}
    if "const" in schema:
        return schema["const"]
    if "enum" in schema:
        return schema["enum"][0]
    kind = schema.get("type")
    if kind == "string":
        return "1" if key == "amount" else "bound"
    if kind in {"integer", "number"}:
        return schema.get("minimum", 0)
    if kind == "boolean":
        return False
    if key == "value":
        return True if name == "mcb_ae_action" else 0
    raise BridgeError("INVALID_ARGUMENTS", "Only typed scalar arguments may be bound.", tool=name, argument=key)


def validate_plan(arguments: dict[str, Any], observe: bool) -> list[dict[str, Any]]:
    if json_size(arguments) > MAX_PLAN_BYTES:
        raise BridgeError("PLAN_TOO_LARGE", "A workflow plan is limited to 64 KiB.")
    steps = [{**view, "kind": "read"} for view in arguments["views"]] if observe else arguments["steps"]
    seen: set[str] = set()
    actions = {}
    positions = {}
    for index, step in enumerate(steps):
        if step["id"] in seen:
            raise BridgeError("INVALID_ARGUMENTS", "Workflow IDs must be unique.", step_index=index)
        refs = list(step.get("bind", {}).values())
        if "valueFrom" in step.get("condition", {}):
            refs.append(step["condition"]["valueFrom"])
        if "baseline" in step.get("condition", {}):
            refs.append(step["condition"]["baseline"])
        if step["kind"] == "assert":
            refs.append({"step": step["source"]})
        if any(ref["step"] not in seen for ref in refs):
            raise BridgeError("INVALID_ARGUMENTS", "References must name an earlier step.", step_index=index)
        if step["kind"] != "assert":
            name = step["tool"]
            allowed = SEQUENCE_ACTION_NAMES if step["kind"] == "action" else SEQUENCE_READ_NAMES
            if name not in allowed:
                raise BridgeError("INVALID_ARGUMENTS", "The selected tool is not allowed for this step kind.", step_index=index)
            args = dict(step.get("args", {}))
            for key in step.get("bind", {}):
                if key in args:
                    raise BridgeError("INVALID_ARGUMENTS", "A bound argument cannot also have a literal value.", step_index=index, argument=key)
                args[key] = _witness(name, key)
                if key == "value" and name == "mcb_ae_action" and args.get("action") == "terminal_setting":
                    args[key] = "bound"
            try:
                validate_args(name, args)
            except BridgeError as error:
                error.details["step_index"] = index
                raise
        condition = step.get("condition", {})
        for accepted in step.get("accepts", []):
            if step["kind"] not in {"wait", "assert"} or accepted not in actions \
                    or step["kind"] == "assert" and positions.get(step["source"], -1) <= actions[accepted]:
                raise BridgeError("INVALID_ARGUMENTS", "Acceptance must observe a condition after an earlier action, not its acknowledgement or pre-action baseline.", step_index=index)
            if step["kind"] == "assert" and any(s["id"] == step["source"] and s["kind"] not in {"read", "wait"} for s in steps):
                raise BridgeError("INVALID_ARGUMENTS", "Action acceptance requires a later read or wait source.", step_index=index)
        if condition.get("operator") in {"gt", "gte", "lt", "lte"} and "value" in condition and number(condition["value"]) is None:
            raise BridgeError("INVALID_ARGUMENTS", "Numeric conditions need a finite number or canonical decimal string.", step_index=index)
        seen.add(step["id"])
        positions[step["id"]] = index
        if step["kind"] == "action":
            actions[step["id"]] = index
    cleanup = arguments.get("cleanup", [])
    if cleanup and not any(step["kind"] == "action" for step in steps):
        raise BridgeError("INVALID_ARGUMENTS", "Stop cleanup requires an action plan.")
    for cleanup_index, step in enumerate(cleanup):
        if "afterStep" in step and not any(item["id"] == step["afterStep"] and item["kind"] == "action" for item in steps):
            raise BridgeError("INVALID_ARGUMENTS", "Cleanup afterStep must name an action in this plan.")
        if step["id"] in seen:
            raise BridgeError("INVALID_ARGUMENTS", "Cleanup IDs must be unique across the plan.")
        seen.add(step["id"])
        try:
            validate_args(step["tool"], step["args"])
        except BridgeError as error:
            error.details["cleanup_index"] = cleanup_index
            raise
        if step["args"]["expectedWorld"] != arguments["expectedWorld"]:
            raise BridgeError("INVALID_ARGUMENTS", "Cleanup must use the same expectedWorld as the action plan.")
    return steps


def resolve(ref: dict[str, Any], results: dict[str, Any]) -> Any:
    exists, value = path_get(results[ref["step"]], ref["path"])
    if not exists:
        raise BridgeError("MISSING_BINDING", "A referenced field was absent; no default value was invented.", source_step=ref["step"], path=ref["path"])
    if "match" in ref:
        if not isinstance(value, list) or len(value) > 4096:
            raise BridgeError("INVALID_SELECTION", "Selection requires a bounded observed array of at most 4096 entries.")
        matches = []
        for entry in value:
            if all((found := path_get(entry, key))[0] and json_equal(found[1], expected) for key, expected in ref["match"].items()):
                matches.append(entry)
                if len(matches) > 1:
                    break
        if len(matches) != 1:
            raise BridgeError("AMBIGUOUS_SELECTION" if matches else "MISSING_SELECTION", "Selection must match exactly one observed entry; no identity was guessed.")
        exists, value = path_get(matches[0], ref["field"])
        if not exists:
            raise BridgeError("MISSING_BINDING", "The selected entry lacks the requested field.", path=ref["field"])
    return value


def resolved_args(step: dict[str, Any], results: dict[str, Any]) -> dict[str, Any]:
    args = dict(step.get("args", {}))
    for key, ref in step.get("bind", {}).items():
        args[key] = resolve(ref, results)
    validate_args(step["tool"], args)
    return args


def resolved_condition(step: dict[str, Any], results: dict[str, Any]) -> dict[str, Any]:
    condition = dict(step["condition"])
    if "valueFrom" in condition:
        condition["value"] = resolve(condition.pop("valueFrom"), results)
    if "baseline" in condition:
        baseline = condition.pop("baseline")
        condition["_baselineCount"] = provider_delta_baseline(results[baseline["step"]], baseline["path"], condition["match"], condition["value"]) \
            if condition["operator"] == "provider_count_delta" else complete_item_count(results[baseline["step"]], baseline["path"], condition["match"])
    return condition


def _metadata(data: Any) -> tuple[dict[str, Any], dict[str, bool]]:
    metadata: dict[str, Any] = {}
    summary = {"partial": False, "pagination": False, "unknownOrUnloaded": False, "metadataOmitted": False, "falseFlagsSummarized": False}
    nodes = 0
    deadline = time.monotonic() + MAX_METADATA_MILLIS / 1000
    priority_keys = ("partial", "truncated", "byteLimited", "captureLimited", "complete", "hasMore",
        "nextCursor", "nextOffset", "loaded", "status", "coverage", "budget", "limits")
    priority_names = frozenset(priority_keys)
    pending = deque([(data, "", 0)])
    payloads = deque()

    def omitted_coverage() -> None:
        summary["metadataOmitted"] = True
        # An unvisited subtree may contain coverage flags; absence is unknown.
        summary["unknownOrUnloaded"] = True

    def consume() -> bool:
        nonlocal nodes
        if nodes >= MAX_METADATA_NODES or nodes >= MIN_METADATA_NODES and time.monotonic() >= deadline:
            omitted_coverage()
            return False
        nodes += 1
        return True

    def retain(key: str, child: Any, child_path: str) -> bool:
        lower = key.lower()
        marked = key in META_KEYS or any(part in lower for part in ("partial", "truncated", "bytelimited"))
        if marked:
            summary["partial"] |= child is True and (key == "captureLimited" or any(part in lower for part in ("partial", "truncated", "bytelimited")))
            summary["pagination"] |= key == "hasMore" and child is True or key == "nextCursor" and child not in (False, None, "")
            summary["unknownOrUnloaded"] |= key == "loaded" and child is False or key == "status" and child in ("unloaded", "unknown", "budget_partial")
            if "." in child_path and child is False and any(part in lower for part in ("partial", "truncated", "bytelimited")):
                summary["falseFlagsSummarized"] = True
                return False
            # Never serialize a large marked subtree solely to estimate metadata size.
            if len(metadata) < 256 and not isinstance(child, (dict, list)) and json_size(child) <= 8192:
                metadata[child_path] = child
            elif key in {"ownerPosition", "position"} and isinstance(child, dict):
                if len(metadata) < 256 and set(child) == {"x", "y", "z"} and all(type(coordinate) is int
                        and -33_554_432 <= coordinate <= 33_554_431 for coordinate in child.values()):
                    metadata[child_path] = dict(child)
                else:
                    summary["metadataOmitted"] = True
                return False
            elif key in META_DIAGNOSTIC_STRING_LISTS and isinstance(child, list):
                if len(metadata) < 256 and len(child) <= 32 and all(type(item) is str and len(item) <= 2048 for item in child) and json_size(child) <= 8192:
                    metadata[child_path] = list(child)
                else:
                    summary["metadataOmitted"] = True
            elif not isinstance(child, (dict, list)):
                summary["metadataOmitted"] = True
        return isinstance(child, (dict, list))

    while pending or payloads:
        if pending:
            value, path, depth = pending.popleft()
            if depth > 32:
                omitted_coverage()
                continue
            if isinstance(value, dict):
                # Direct bounded lookups read explicit coverage before bulk payloads.
                for key in priority_keys:
                    if key in value:
                        if not consume():
                            return metadata, summary
                        child = value[key]
                        child_path = path + "." + key if path else key
                        if retain(key, child, child_path):
                            pending.append((child, child_path, depth + 1))
                payloads.append((iter(value.items()), path, depth, True))
            elif isinstance(value, list):
                payloads.append((iter(enumerate(value)), path, depth, False))
            continue
        iterator, path, depth, mapping = payloads.popleft()
        try:
            key, child = next(iterator)
        except StopIteration:
            continue
        # Advance one entry at a time so a large list cannot starve sibling metadata.
        payloads.append((iterator, path, depth, mapping))
        if mapping and key in priority_names:
            continue
        if not consume():
            return metadata, summary
        child_path = path + "." + str(key) if path or not mapping else str(key)
        if (retain(key, child, child_path) if mapping else isinstance(child, (dict, list))):
            pending.append((child, child_path, depth + 1))
        elif not mapping and depth >= 32:
            omitted_coverage()
    return metadata, summary


def project(data: dict[str, Any], step: dict[str, Any]) -> dict[str, Any]:
    defaults = [] if step.get("kind") == "assert" else DEFAULT_FIELDS.get(step.get("tool"), list(data)[:32])
    if step.get("kind") == "action":
        defaults = [field for field in (NEI_PREVIEW_ACTION_FIELDS if step.get("tool") == "mcb_nei_preview_control" else
            GUI_POINTER_ACTION_FIELDS if step.get("tool") == "mcb_gui_pointer" else
            NEI_ITEM_ACTION_FIELDS if step.get("tool") == "mcb_nei_item_action" else
            AE_ACTION_FIELDS if step.get("tool") == "mcb_ae_action" else ACTION_FIELDS) if field in data]
    fields = step.get("fields", defaults)
    metadata, summary = _metadata(data)
    result: dict[str, Any] = {"values": {}, "missingFields": [], "metadata": metadata, "coverageSummary": summary, "emptySlotFiltering": []}

    def filter_slots(value: list[Any], path: str) -> list[Any]:
        # Preserve actual IDs; virtual resource lists have no explicit real slot identity.
        filtered = [entry for entry in value if not (isinstance(entry, dict)
            and any(key in entry for key in ("slot", "slotId", "slotNumber", "index"))
            and "item" in entry and entry["item"] is None)]
        result["emptySlotFiltering"].append({"path": path, "originalEntries": len(value),
            "omittedEmpty": len(value) - len(filtered), "indicesPreserved": True})
        return filtered

    for path in fields:
        exists, value = path_get(data, path)
        if not exists:
            result["missingFields"].append(path)
            continue
        if step.get("omitEmptySlots", True) and isinstance(value, list) and path.split(".")[-1] == "slots":
            value = filter_slots(value, path)
        elif step.get("omitEmptySlots", True) and path.split(".")[-1] == "inventory" and isinstance(value, dict) and isinstance(value.get("slots"), list):
            value = {**value, "slots": filter_slots(value["slots"], path + ".slots")}
        result["values"][path] = value
    return result


def _require_capability(health: dict[str, Any], operation: str) -> None:
    caps = health.get("capabilities")
    if isinstance(caps, list) and all(isinstance(item, str) for item in caps):
        supported = operation in caps
    elif isinstance(caps, dict) and all(type(value) is bool for value in caps.values()):
        supported = caps.get(operation, False)
    else:
        raise BridgeError("MALFORMED_RESPONSE", "The bridge health response has invalid capabilities.")
    if not supported:
        raise BridgeError("UNSUPPORTED_CAPABILITY", "The active bridge lacks a required workflow operation.", operation=operation)


def _background(data: Any) -> bool:
    return isinstance(data, dict) and data.get("enabled") is True and data.get("helperInstalled") is True and data.get("pauseOnLostFocus") is False


def _context_capabilities(health: dict[str, Any]) -> tuple[set[str], set[str]]:
    value = health.get("contextRpc")
    if not isinstance(value, dict) or type(value.get("version")) is not int or value["version"] != 1:
        return set(), set()
    reads, actions = value.get("reads"), value.get("actions")
    if not all(isinstance(items, list) and len(items) <= 128 and all(isinstance(item, str) for item in items) for items in (reads, actions)):
        return set(), set()
    return set(reads) & CONTEXT_READS, set(actions) & CONTEXT_ACTIONS


_PLAYER_INVENTORY_LIMITS = {"inspectManyPlayerInventorySlots": 256,
    "inspectManyPlayerInventoryResultBytes": 131_072, "inspectManyPlayerInventoryObservationBytes": 1_048_576,
    "inspectManyPlayerInventoryNanos": 4_000_000}
_PLAYER_INVENTORY_CONTRACT = {"version": 1, "operation": "world.inspect_many", "argument": "includePlayerInventory",
    "guardedOnly": True, "developerOnly": True, "compact": False, "phase": "after", "completeInventoryOnly": True}


def _inspect_many_player_inventory_available(health: dict[str, Any]) -> bool:
    flag = health.get("worldInspectManyPlayerInventory", False)
    if type(flag) is not bool:
        raise BridgeError("MALFORMED_RESPONSE", "Combined inventory capability must be a typed boolean.")
    if not flag:
        return False
    contract, limits = health.get("worldInspectManyPlayerInventoryContract"), health.get("limits")
    if not isinstance(contract, dict) or not isinstance(limits, dict) \
            or any(type(contract.get(key)) is not type(value) or contract[key] != value for key, value in _PLAYER_INVENTORY_CONTRACT.items()) \
            or any(not isinstance(contract.get(key), str) or not contract[key] for key in ("requiredContext", "budgets", "coverage")) \
            or any(type(limits.get(key)) is not int or limits[key] != value for key, value in _PLAYER_INVENTORY_LIMITS.items()) \
            or health.get("profile") != "developer" or "world.inspect_many" not in _context_capabilities(health)[0]:
        raise BridgeError("MALFORMED_RESPONSE", "Advertised combined inventory reads need their complete guarded developer contract and finite limits.")
    return True


def _pending_zero_inventory_available(health: dict[str, Any]) -> bool:
    contract = health.get("worldInspectManyPlayerInventoryContract")
    version = contract.get("pendingZeroVersion") if isinstance(contract, dict) else None
    if version is None:
        return False
    if type(version) is not int or version < 1:
        raise BridgeError("MALFORMED_RESPONSE", "Pending-zero inventory capability requires an exact positive integer version.")
    return version == 1 and _inspect_many_player_inventory_available(health)


def _validate_player_inventory_observation(value: dict[str, Any], args: dict[str, Any], evidence: Any) -> bool:
    """Consume one fresh composite receipt before any subsequent native action."""
    def require(condition: bool, message: str) -> None:
        if not condition:
            raise BridgeError("MALFORMED_RESPONSE", message, outcome_unknown=False)

    def item(entry: Any, *, allow_zero: bool = False) -> None:
        require(entry is None or isinstance(entry, dict) and isinstance(entry.get("registryName"), str)
            and bool(entry["registryName"]) and entry["registryName"] != "null"
            and isinstance(entry.get("displayName"), str) and type(entry.get("hasTag")) is bool
            and type(entry.get("count")) is int and (0 if allow_zero else 1) <= entry["count"] <= 2_147_483_647
            and type(entry.get("metadata")) is int and -2_147_483_648 <= entry["metadata"] <= 2_147_483_647,
            "Every combined inventory item must retain its complete typed public DTO.")

    def slots(group: Any, minimum: int, *, allow_zero: bool = False) -> None:
        require(isinstance(group, dict) and isinstance(group.get("name"), str) and group.get("truncated") is False
            and type(group.get("totalSlots")) is int and minimum <= group["totalSlots"] <= 256
            and isinstance(group.get("slots"), list) and len(group["slots"]) == group["totalSlots"],
            "Combined reads require all actual inventory slots, including empty entries.")
        for index, row in enumerate(group["slots"]):
            require(isinstance(row, dict) and type(row.get("index")) is int and row["index"] == index and "item" in row,
                "Combined inventory indices must be the complete unique actual sequence.")
            item(row["item"], allow_zero=allow_zero)

    require(isinstance(evidence, dict) and isinstance(evidence.get("snapshot"), dict),
        "Combined inventory reads require their own complete AFTER snapshot.")
    snapshot = evidence["snapshot"]
    world, player = snapshot.get("world"), snapshot.get("player")
    inventory, observation = value.get("playerInventory"), value.get("playerInventoryObservation")
    require(isinstance(world, dict) and isinstance(player, dict) and isinstance(inventory, dict)
        and isinstance(observation, dict), "Combined reads require world, player, full inventory and observation bindings.")
    allow_pending = args.get("allowPendingZero") is True
    slots(inventory, 9, allow_zero=allow_pending)
    require(inventory.get("complete") is True and inventory.get("source") == "client"
        and inventory.get("inventorySource") == "current_player_inventory"
        and inventory.get("playerInventoryBindingVerified") is True
        and isinstance(inventory.get("playerId"), str) and bool(inventory["playerId"])
        and inventory["playerId"] == player.get("playerId")
        and isinstance(inventory.get("sessionId"), str) and inventory["sessionId"] == snapshot.get("sessionId")
        and type(inventory.get("clientTicks")) is int and inventory["clientTicks"] >= 0
        and type(snapshot.get("clientTicks")) is int and inventory["clientTicks"] == snapshot["clientTicks"]
        and type(inventory.get("worldTick")) is int and inventory["worldTick"] >= 0
        and type(inventory.get("selectedHotbarSlot")) is int and 0 <= inventory["selectedHotbarSlot"] <= 8
        and type(player.get("selectedHotbarSlot")) is int and inventory["selectedHotbarSlot"] == player["selectedHotbarSlot"]
        and "cursorItem" in inventory, "Combined inventory must bind the exact fresh player, session, ticks, selected slot and cursor.")
    item(inventory["cursorItem"], allow_zero=allow_pending)
    pending = any(isinstance(entry, dict) and entry["count"] == 0
        for entry in [*[row["item"] for row in inventory["slots"]], inventory["cursorItem"]])
    if allow_pending:
        require(snapshot.get("paused") is False, "Pending-zero pure reads require an explicitly unpaused current context.")
        require(type(observation.get("pendingZeroVersion")) is int and observation["pendingZeroVersion"] == 1
            and type(observation.get("pendingZero")) is bool and observation["pendingZero"] is pending,
            "The full DTO must prove exactly the declared typed pending-zero state.")
    else:
        require(observation.get("pendingZero", False) is False, "Ordinary reads cannot accept pending inventory evidence.")
    if pending:
        control = snapshot.get("serverControl")
        require(isinstance(control, dict) and control.get("available") is True
            and control.get("source") == "integrated_server_tick" and control.get("sessionId") == inventory["sessionId"]
            and type(control.get("dimension")) is int and control["dimension"] == inventory.get("dimension")
            and control.get("playerId") == inventory["playerId"]
            and (control.get("worldName") in (world.get("worldName"), world.get("worldFolder"))
                or control.get("worldFolder") in (world.get("worldName"), world.get("worldFolder")))
            and type(player.get("health")) in (int, float) and math.isfinite(player["health"]) and player["health"] > 0
            and player.get("isDead", False) is False
            and type(control.get("sampleId")) is int and control["sampleId"] >= 0
            and type(control.get("ageMillis")) in (int, float) and math.isfinite(control["ageMillis"]) and 0 <= control["ageMillis"] <= 1000,
            "A pending-zero stack requires a fresh matching actual server actor, not a guessed client sync delay.")
        container = control.get("openContainer")
        require(isinstance(container, dict) and type(container.get("windowId")) is int and container["windowId"] >= 0
            and type(container.get("isInventoryContainer")) is bool,
            "Pending-zero waits require an explicit current native container binding.")
    dimension = value.get("dimension")
    require(value.get("source") == "integrated_server" and value.get("compact", False) is False
        and type(dimension) is int and type(world.get("dimension")) is int and world["dimension"] == dimension
        and type(inventory.get("dimension")) is int and inventory["dimension"] == dimension
        and ("dimension" not in args or type(args["dimension"]) is int and args["dimension"] == dimension),
        "Full server owners and fresh client inventory/context must retain the exact requested dimension.")
    rows, positions = value.get("results"), args.get("positions")
    require(isinstance(positions, list) and 1 <= len(positions) <= 16
        and isinstance(rows, list) and len(rows) == len(positions), "Combined owner points need exact full request coverage.")
    for row, position in zip(rows, positions):
        data = row.get("data") if isinstance(row, dict) and row.get("ok") is True else None
        require(isinstance(data, dict) and data.get("source") == "integrated_server"
            and type(data.get("dimension")) is int and data["dimension"] == dimension
            and all(type(data.get(axis)) is int and data[axis] == position[axis] for axis in ("x", "y", "z"))
            and isinstance(data.get("registryName"), str) and bool(data["registryName"])
            and data["registryName"] != "null" and type(data.get("metadata")) is int,
            "Every combined owner point must retain its exact complete authoritative block identity.")
        require("tileClass" not in data or isinstance(data["tileClass"], str) and bool(data["tileClass"]),
            "Owner tile identities must remain typed and complete.")
        if "inventory" in data:
            slots(data["inventory"], 0, allow_zero=True)
        if "fluids" in data:
            tanks = data["fluids"]
            require(isinstance(tanks, dict) and tanks.get("truncated") is False
                and type(tanks.get("totalTanks")) is int and 0 <= tanks["totalTanks"] <= 32
                and isinstance(tanks.get("tanks"), list) and len(tanks["tanks"]) == tanks["totalTanks"]
                and all(isinstance(tank, dict) and type(tank.get("index")) is int and tank["index"] == index
                    and type(tank.get("capacity")) is int for index, tank in enumerate(tanks["tanks"])),
                "Combined owner tank coverage must remain complete and untruncated.")
        require("gregtech" not in data or isinstance(data["gregtech"], dict), "Full owner GregTech evidence must remain typed.")
    expected = {"version": 1, "phase": "after", "nonAtomic": True, "ownerReadBeforeInventory": True,
        "inventoryComplete": not pending, "workBudgetNanos": 4_000_000}
    require(all(type(observation.get(key)) is type(target) and observation[key] == target for key, target in expected.items())
        and isinstance(observation.get("coverage"), str) and bool(observation["coverage"]),
        "Combined receipt must retain its complete non-atomic AFTER and bounded work contract.")
    for key, minimum, maximum in (("playerInventoryByteBudget", 1024, 131_072), ("observationByteBudget", 8192, 1_048_576)):
        require(type(args.get(key)) is int and minimum <= args[key] <= maximum
            and type(observation.get(key)) is int and observation[key] == args[key], "Combined budgets must echo the explicit bounded request.")
    require(type(observation.get("inventoryBytes")) is int and 0 < observation["inventoryBytes"] <= args["playerInventoryByteBudget"]
        and type(observation.get("actualObservationBytes")) is int
        and observation["inventoryBytes"] < observation["actualObservationBytes"] <= args["observationByteBudget"]
        and json_size(inventory) <= args["playerInventoryByteBudget"]
        and json_size({"data": dict(value), "context": evidence}) <= args["observationByteBudget"],
        "The complete retained inventory and owner/context receipt must fit both explicit byte budgets.")
    return pending


_DISCOVERY_SEAL = object()
_OWNED_REPLACEMENT_CONTRACT = {"version": 1, "guardedOnly": True, "developerOnly": True, "oldTilePolicy": "absent",
    "ownership": "caller task provenance; bridge verifies exact current identity, not ownership inferred from registry",
    "fullPlayerSlotConservation": True}


@dataclass(frozen=True)
class _WorkflowDiscovery:
    """Task-local immutable capabilities; profile/session are guard expectations, never live state."""

    client: Any
    configuration: Any
    credentials: Any
    session: str
    expected_profile: str
    capabilities: frozenset[str]
    reads: frozenset[str]
    actions: frozenset[str]
    flags: tuple[tuple[str, bool], ...]
    seal: Any
    inventory_contract: tuple[tuple[str, Any], ...] = ()
    inventory_limits: tuple[tuple[str, int], ...] = ()
    owned_replacement_contract: tuple[tuple[str, Any], ...] = ()
    native_wait_version: int = 0
    approach_versions: tuple[tuple[str, int], ...] = ()

    def bind(self, client: BridgeClient, credentials: Any, session: Any) -> dict[str, Any]:
        if self.seal is not _DISCOVERY_SEAL or client is not self.client or client.configuration is not self.configuration \
                or credentials != self.credentials or client.configuration.read() != self.credentials or session != self.session:
            raise BridgeError("SESSION_CHANGED", "Task capability discovery belongs to a different client, runtime or session; no operation was replayed.")
        return {"protocolVersion": 1, "sessionId": self.session, "profile": self.expected_profile,
            "capabilities": sorted(self.capabilities), **dict(self.flags),
            "worldInspectManyPlayerInventoryContract": dict(self.inventory_contract), "limits": dict(self.inventory_limits),
            "nativeOwnedBlockReplacementContract": dict(self.owned_replacement_contract),
            "nativeObservationWaitVersion": self.native_wait_version, **dict(self.approach_versions),
            "contextRpc": {"version": 1, "reads": sorted(self.reads), "actions": sorted(self.actions)}}


@dataclass(frozen=True)
class _WorkflowSnapshotChecks:
    """Additional construction assertions over this step's actual fresh guard."""

    discovery: _WorkflowDiscovery
    rules: tuple[tuple[str, tuple[tuple[str, Any], ...]], ...]

    def valid(self, steps: list[dict[str, Any]]) -> bool:
        if type(self.rules) is not tuple or not 1 <= len(self.rules) <= 32:
            return False
        seen: set[str] = set()
        identifiers = {step["id"] for step in steps if step["kind"] != "assert"}
        for rule in self.rules:
            if type(rule) is not tuple or len(rule) != 2:
                return False
            identifier, fields = rule
            if type(identifier) is not str or identifier not in identifiers or identifier in seen \
                    or type(fields) is not tuple or not 1 <= len(fields) <= 8:
                return False
            seen.add(identifier)
            paths: set[str] = set()
            for field in fields:
                if type(field) is not tuple or len(field) != 2:
                    return False
                path, expected = field
                if type(path) is not str or not 1 <= len(path) <= 128 or re.fullmatch(r"[A-Za-z0-9_.]+", path) is None \
                        or path in paths or type(expected) not in (str, int, float, bool, type(None)) \
                        or type(expected) is float and not math.isfinite(expected):
                    return False
                paths.add(path)
        return True


def _workflow_discovery(client: BridgeClient, credentials: Any, health: dict[str, Any]) -> _WorkflowDiscovery:
    """Only internal fixed-task runners opt into this private, identity-bound value."""
    if type(health.get("protocolVersion")) is not int or health["protocolVersion"] != 1:
        raise BridgeError("PROTOCOL_VERSION", "The bridge uses an unsupported protocol version.")
    session, profile = health.get("sessionId"), health.get("profile")
    if not isinstance(session, str) or not session or profile not in ("player", "developer"):
        raise BridgeError("SESSION_CHANGED", "Task discovery requires a typed session and guard profile.")
    caps = health.get("capabilities")
    if isinstance(caps, list) and all(isinstance(value, str) for value in caps):
        supported = frozenset(caps)
    elif isinstance(caps, dict) and all(isinstance(key, str) and type(value) is bool for key, value in caps.items()):
        supported = frozenset(key for key, value in caps.items() if value)
    else:
        raise BridgeError("MALFORMED_RESPONSE", "The bridge health response has invalid capabilities.")
    reads, actions = _context_capabilities(health)
    flags = tuple((key, health.get(key) is True) for key in ("interactionSneaking", "interactionBlockTargeting",
        "interactionViewBinding", "interactionAttackViewBinding"))
    placement_flags = ("worldScanBounds", "worldBlockPublicMaterial", "worldInspectManyCompact", "worldScanMinecraftAir",
        "worldInspectManyPlayerInventory", "nativeOwnedBlockReplacement")
    if any(key in health and type(health[key]) is not bool for key in placement_flags):
        raise BridgeError("MALFORMED_RESPONSE", "Task placement capabilities must be explicit typed booleans.")
    flags += tuple((key, health.get(key) is True) for key in placement_flags)
    combined = _inspect_many_player_inventory_available(health)
    pending_zero = _pending_zero_inventory_available(health) if combined else False
    owned_contract = health.get("nativeOwnedBlockReplacementContract")
    if health.get("nativeOwnedBlockReplacement") is True and (not isinstance(owned_contract, dict)
            or set(owned_contract) != set(_OWNED_REPLACEMENT_CONTRACT)
            or any(type(owned_contract[key]) is not type(value) or owned_contract[key] != value
                for key, value in _OWNED_REPLACEMENT_CONTRACT.items())):
        raise BridgeError("MALFORMED_RESPONSE", "Advertised owned replacement requires its complete exact typed contract.")
    return _WorkflowDiscovery(client, client.configuration, credentials, session, profile, supported,
        frozenset(reads), frozenset(actions), flags, _DISCOVERY_SEAL,
        tuple((key, health["worldInspectManyPlayerInventoryContract"][key])
            for key in (*_PLAYER_INVENTORY_CONTRACT, "requiredContext", "budgets", "coverage", *(("pendingZeroVersion",) if pending_zero else ()))) if combined else (),
        tuple(_PLAYER_INVENTORY_LIMITS.items()) if combined else (),
        tuple((key, owned_contract[key]) for key in _OWNED_REPLACEMENT_CONTRACT)
            if health.get("nativeOwnedBlockReplacement") is True else (),
        1 if type(health.get("nativeObservationWaitVersion")) is int and health["nativeObservationWaitVersion"] == 1 else 0,
        tuple((key, health[key]) for key in (
            "approachSharedTargetsVersion", "interactionBuildEnvelopeVersion", "interactionHitPointVersion",
            "approachWrenchFacingVersion", "interactionPartTargetingVersion", "guiNativeWaitVersion",
            "walkingInteractionGoalVersion", "nativeNavigationSegmentsVersion", "nativeCrosshairArrivalVersion")
            if type(health.get(key)) is int))


def _guard(snapshot: dict[str, Any], session: str, profile: Any, world: str | None, protection: bool) -> None:
    if snapshot.get("sessionId") != session:
        raise BridgeError("SESSION_CHANGED", "Client session changed; subsequent steps were blocked.")
    if snapshot.get("profile") != profile:
        raise BridgeError("PROFILE_CHANGED", "Access profile changed during the workflow.")
    if world is not None:
        current = snapshot.get("world", {})
        if not snapshot.get("worldLoaded") or not isinstance(current, dict) or world not in (current.get("worldName"), current.get("worldFolder")):
            raise BridgeError("WORLD_CHANGED", "The expected loaded world does not match the current client.")
    if protection and not _background(snapshot.get("background")):
        raise BridgeError("TAKEOVER_LOST", "Background protection was released during the workflow; the next action was blocked.")


def _bounded_report(report: dict[str, Any], maximum: int) -> dict[str, Any]:
    report["maxReturnBytes"] = maximum
    report["outputOmitted"] = False
    if json_size(report) > maximum - 256 and "checkpoint" in report:
        # The original per-step request IDs and dispatch flags remain intact;
        # avoid duplicating long action IDs in the portable progress summary.
        checkpoint = report["checkpoint"]
        checkpoint["actions"] = [{"stepIndex": next(i for i, step in enumerate(report["steps"]) if step["id"] == row["id"]),
            "state": row["state"], "acceptedByIndices": [next(i for i, step in enumerate(report["steps"]) if step["id"] == accepted)
                                                       for accepted in row["acceptedBy"]]} for row in checkpoint["actions"]]
        checkpoint["unexecutedStepIndices"] = [i for i, step in enumerate(report["steps"]) if step["status"] == "not_executed"]
        checkpoint.pop("unexecutedStepIds", None)
        checkpoint["compactIndices"] = True
    if "navigationFailureDiagnostics" in report and json_size(report) > maximum - 256:
        report["navigationFailureDiagnostics"] = {"complete": False, "omitted": True, "reason": "return_budget"}
        report["outputOmitted"] = True
    entries = report["steps"] + report.get("cleanup", [])
    # Drop the largest complete fields first, retaining small identities and
    # counts. Never truncate a requested NBT/long value or an action receipt.
    fields = sorted(((json_size(value), entry["observation"], key)
        for entry in entries if entry.get("kind") != "action" and "observation" in entry
        for key, value in entry["observation"].get("values", {}).items()), key=lambda field: field[0], reverse=True)
    for _, observation, key in fields:
        if json_size(report) <= maximum - 256:
            break
        observation["values"].pop(key)
        observation.setdefault("omittedFields", []).append(key)
        observation.update(fieldsOmitted=True, omitted=not bool(observation["values"]), reason="return_budget")
        report["outputOmitted"] = True
    for entry in entries:
        if json_size(report) <= maximum - 256:
            break
        if "observation" in entry:
            observation = entry["observation"]
            observation.update(omitted=True, reason="return_budget", omittedFields=list(observation["values"]), values={})
            report["outputOmitted"] = True
    for entry in entries:
        if json_size(report) <= maximum - 256:
            break
        if "observation" in entry:
            observation = entry["observation"]
            metadata = observation["metadata"]
            observation["metadata"] = {key: value for key, value in metadata.items() if "." not in key and key in {"nextOffset", "nextCursor", "hasMore", "complete", "stopReason", "nonAtomic", "byteLimited"}}
            observation["coverageSummary"]["metadataOmitted"] = True
            observation["metadataOmitted"] = True
            observation["missingFields"] = []
            observation["missingFieldsOmitted"] = True
            observation["omittedFields"] = []
            observation["omittedFieldNames"] = True
            report["outputOmitted"] = True
    # Preserve every step's status and action receipt even at the minimum budget.
    # Payload omission must never turn completed side effects into an unstructured error.
    for entry in entries:
        if json_size(report) <= maximum - 256:
            break
        if "observation" in entry:
            entry["observation"] = {"omitted": True, "reason": "return_budget",
                "coverageSummary": {**entry["observation"]["coverageSummary"], "metadataOmitted": True}}
            report["outputOmitted"] = True
    return report


async def run_workflow(client: BridgeClient, arguments: dict[str, Any], lock: anyio.Lock, *, observe: bool = False,
                       _discovery: _WorkflowDiscovery | None = None, _require_background: bool = False,
                       _snapshot_checks: _WorkflowSnapshotChecks | None = None) -> dict[str, Any]:
    start = time.monotonic()
    steps = validate_plan(arguments, observe)
    timeout = arguments.get("timeout_ms", 10_000) / 1000
    report: dict[str, Any] = {"status": "completed", "nonAtomic": True, "rolledBack": False, "steps": [
        {"id": step["id"], "kind": step["kind"], "status": "not_executed"} for step in steps],
        "acknowledgedActions": 0, "dispatchedActions": 0, "outcomeUnknown": False, "completedSteps": 0, "failedIndex": None,
        "takeoverEstablished": False, "timeout_ms": int(timeout * 1000)}
    raw: dict[str, Any] = {}
    native_wait_progress: dict[str, dict[int, int]] = {}
    pending_zero_bindings: dict[str, tuple[Any, ...]] = {}
    raw_bytes = 0
    active: dict[str, Any] | None = None
    active_index: int | None = None
    trace: dict[str, Any] = {}
    phase = "prepare"
    has_actions = any(step["kind"] == "action" for step in steps)
    protection_actions = any(step["kind"] == "action" and not (step["tool"] == "mcb_gui_pointer"
        and step.get("args", {}).get("action") == "clear") for step in steps)
    protection_reads = any(step.get("tool") == "mcb_ae_material_source"
        or step.get("tool") == "mcb_world_inspect_many" and step.get("args", {}).get("includePlayerInventory") is True
        for step in steps)
    protection = protection_actions or protection_reads or _require_background
    credentials = client.configuration.read()
    session = arguments.get("expectedSessionId")
    profile = None
    context_reads: set[str] = set()
    context_actions: set[str] = set()
    fresh_after_guard = False

    def guard_snapshot(snapshot: dict[str, Any], required: bool = protection) -> None:
        _guard(snapshot, session, profile, arguments.get("expectedWorld"), required)
        if _snapshot_checks is not None and active is not None:
            for identifier, fields in _snapshot_checks.rules:
                if identifier == active["id"] and not all((found := path_get(snapshot, path))[0]
                        and json_equal(found[1], expected) for path, expected in fields):
                    raise BridgeError("ASSERTION_FAILED", "The fresh step guard did not retain the required flight and complete pose.")

    async def call(operation: str, args: dict[str, Any], *, action: bool = False) -> dict[str, Any]:
        nonlocal trace, fresh_after_guard
        trace = {}
        # Only the last actual RPC can prove this segment's completion. A later
        # action or unguarded read invalidates the marker before dispatch.
        fresh_after_guard = False
        guarded = operation in (context_actions if action else context_reads) and profile in ("player", "developer")
        context = {"version": 1, "expectedSessionId": session, "expectedProfile": profile, "requireBackground": protection} if guarded else None
        if context is not None and arguments.get("expectedWorld") is not None:
            context["expectedWorld"] = arguments["expectedWorld"]
        if "nativeWait" in args and active is not None:
            active["nativeWaitWindows"] = active.get("nativeWaitWindows", 0) + 1
        with request_context(context):
            value = await client.call(operation, args, expected_credentials=credentials, trace=trace)
        if guarded:
            evidence = response_context(value)
            if not isinstance(evidence, dict) or set(evidence) != {"version", "operation", "phase", "snapshot"} \
                    or type(evidence.get("version")) is not int or evidence["version"] != 1 \
                    or evidence.get("operation") != operation or evidence.get("phase") != ("before" if action else "after") \
                    or not isinstance(evidence.get("snapshot"), dict):
                raise BridgeError("MALFORMED_RESPONSE", "Advertised context RPC omitted its fresh guard; no action is replayed.", outcome_unknown=action)
            guard_snapshot(evidence["snapshot"])
        if operation == "world.inspect_many" and args.get("includePlayerInventory") is True:
            if not guarded or not protection or not arguments.get("expectedWorld") or profile != "developer":
                raise BridgeError("INVALID_ARGUMENTS", "Combined inventory reads require a guarded developer world and background protection.")
            pending_zero = _validate_player_inventory_observation(value, args, response_context(value))
            if args.get("allowPendingZero") is True:
                current = response_context(value)["snapshot"]
                inventory = value["playerInventory"]
                control = current.get("serverControl")
                container = control.get("openContainer") if isinstance(control, dict) else None
                container = container if isinstance(container, dict) else {}
                binding = (inventory["playerId"], inventory["sessionId"], inventory["dimension"], inventory["selectedHotbarSlot"],
                    current.get("screenId"), container.get("windowId"), container.get("isInventoryContainer"))
                if active is None or phase != "wait":
                    raise BridgeError("INVALID_ARGUMENTS", "Pending-zero inventory evidence belongs only to an internal pure-read wait.")
                previous = pending_zero_bindings.get(active["id"])
                if previous is not None and binding != previous:
                    raise BridgeError("INVENTORY_CONTEXT_CHANGED", "The pending inventory wait actor/container changed; no action is replayed.")
                if pending_zero:
                    pending_zero_bindings[active["id"]] = binding
            if "nativeWait" in args:
                from .native_wait import validate as validate_native_wait
                receipt = value.get("nativeWait", {})
                if active is not None:
                    if isinstance(receipt, dict) and type(receipt.get("attempts")) is int and 0 <= receipt["attempts"] <= 1_000:
                        active["nativeWaitAttempts"] = active.get("nativeWaitAttempts", 0) + receipt["attempts"]
                    if isinstance(receipt, dict) and type(receipt.get("elapsedMillis")) in (int, float) \
                            and math.isfinite(receipt["elapsedMillis"]) and 0 <= receipt["elapsedMillis"] <= 8_000:
                        active["nativeWaitElapsedMillis"] = active.get("nativeWaitElapsedMillis", 0) + receipt["elapsedMillis"]
                validate_native_wait(value, args, native_wait_progress.setdefault(active["id"], {}) if active is not None else None)
            if pending_zero:
                # The full owner quantities are validated above even on pending
                # client inventory; later windows cannot erase a quantity drift.
                raise BridgeError("INVENTORY_SYNC_PENDING_ZERO", "A fully bound nonnull client stack still has exact count zero.", outcome_unknown=False)
        if "sessionId" in value and value["sessionId"] != session:
            raise BridgeError("SESSION_CHANGED", "An observation came from a different client session.")
        fresh_after_guard = guarded and not action
        return value

    async def check_observation(value: dict[str, Any], operation: str) -> None:
        nonlocal fresh_after_guard
        if protection or arguments.get("expectedWorld") is not None:
            evidence = response_context(value)
            snapshot = evidence["snapshot"] if evidence is not None and evidence["phase"] == "after" else value if operation == "client.snapshot" else await call("client.snapshot", {})
            guard_snapshot(snapshot)
            # This standalone snapshot is the last actual RPC, just like an
            # embedded AFTER guard. Every later call invalidates the marker.
            fresh_after_guard = True

    try:
        with anyio.fail_after(timeout):
            # Independent live reads may run alongside the one write executor.
            # They keep fresh context checks and are explicitly non-atomic.
            async with lock if has_actions else nullcontext():
                if _discovery is None:
                    if _require_background:
                        raise BridgeError("INVALID_ARGUMENTS", "Persistent task protection requires private task discovery.")
                    health = await client.call("bridge.health", {}, expected_credentials=credentials)
                elif type(_discovery) is _WorkflowDiscovery and type(_require_background) is bool:
                    health = _discovery.bind(client, credentials, session)
                else:
                    raise BridgeError("INVALID_ARGUMENTS", "Invalid private task discovery; no capability fallback was attempted.")
                if _snapshot_checks is not None and (type(_snapshot_checks) is not _WorkflowSnapshotChecks
                        or _discovery is None or _snapshot_checks.discovery is not _discovery
                        or not _snapshot_checks.valid(steps)):
                    raise BridgeError("INVALID_ARGUMENTS", "Additional snapshot assertions require their own sealed task discovery and declared steps.")
                if type(health.get("protocolVersion")) is not int or health["protocolVersion"] != 1:
                    raise BridgeError("PROTOCOL_VERSION", "The bridge uses an unsupported protocol version.")
                actual_session = health.get("sessionId")
                if not isinstance(actual_session, str) or not actual_session or session is not None and session != actual_session:
                    raise BridgeError("SESSION_CHANGED", "The expected session does not match current capability discovery.")
                session, profile = actual_session, health.get("profile")
                context_reads, context_actions = _context_capabilities(health)
                report.update(sessionId=session, profile=profile, expectedWorld=arguments.get("expectedWorld"))
                for step in steps:
                    if step["kind"] != "assert":
                        _require_capability(health, TOOL_BY_NAME[step["tool"]].operation)
                        if step["tool"] == "mcb_ae_material_source" and ("ae.material_source" not in context_reads
                                or profile != "developer" or not arguments.get("expectedWorld")):
                            raise BridgeError("UNSUPPORTED_CAPABILITY", "AE material-source reads require the advertised developer AFTER context and explicit workflow world before dispatch.")
                        if step["tool"] == "mcb_world_inspect_many" and step.get("args", {}).get("includePlayerInventory") is True:
                            if not _inspect_many_player_inventory_available(health) or not protection or not arguments.get("expectedWorld"):
                                raise BridgeError("UNSUPPORTED_CAPABILITY", "Combined inventory requires its complete guarded developer capability before dispatch.")
                        if step["tool"] == "mcb_interact" and "sneaking" in step.get("args", {}) and health.get("interactionSneaking") is not True:
                            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support scoped sneak interaction.", operation="player.interact")
                        if step["tool"] == "mcb_interact" and any(key in step.get("args", {}) or key in step.get("bind", {}) for key in ("x", "y", "z")) and health.get("interactionBlockTargeting") is not True:
                            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support aimed block-only interaction without air-use fallback.", operation="player.interact")
                        if step["tool"] == "mcb_interact" and step.get("args", {}).get("action") != "attack" and any(key in step.get("args", {}) or key in step.get("bind", {}) for key in ("x", "y", "z")) and health.get("interactionViewBinding") is not True:
                            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support target-bound view and the developer-only legacy view option.", operation="player.interact")
                        if step["tool"] == "mcb_interact" and step.get("args", {}).get("action") == "attack" and any(key in step.get("args", {}) or key in step.get("bind", {}) for key in ("x", "y", "z")) and health.get("interactionAttackViewBinding") is not True:
                            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support target-bound attack view.", operation="player.interact")
                for step in arguments.get("cleanup", []):
                    _require_capability(health, TOOL_BY_NAME[step["tool"]].operation)
                initial = None
                # Cached discovery contains no background/world truth. Once the
                # task established takeover, the first live guard must preserve it.
                entry_protected = _require_background if _discovery is not None else _background(health.get("background"))
                if protection or arguments.get("expectedWorld") is not None:
                    _require_capability(health, "client.snapshot")
                    first = steps[0]
                    first_snapshot_guards_entry = first["kind"] in {"read", "wait"} \
                        and TOOL_BY_NAME[first["tool"]].operation == "client.snapshot" \
                        and (not protection_actions or entry_protected)
                    first_context_guards_entry = first["kind"] in {"read", "wait", "action"} \
                        and profile in ("player", "developer") \
                        and TOOL_BY_NAME[first["tool"]].operation in (context_actions if first["kind"] == "action" else context_reads) \
                        and (not protection_actions or entry_protected)
                    first_action_guards_entry = _discovery is not None and _require_background \
                        and first["kind"] == "action"
                    # The first AFTER read or native BEFORE action guards entry
                    # itself. Keep the legacy guard before any takeover/non-context RPC.
                    if not first_snapshot_guards_entry and not first_context_guards_entry and not first_action_guards_entry:
                        initial = await call("client.snapshot", {})
                        _guard(initial, session, profile, arguments.get("expectedWorld"), _require_background)
                if protection_actions:
                    _require_capability(health, "client.background")
                    current_background = initial.get("background") if _discovery is not None and initial is not None else health.get("background")
                    if not _require_background and not _background(current_background):
                        phase = "takeover"
                        if not _background(await call("client.background", {"enabled": True})):
                            raise BridgeError("TAKEOVER_NOT_CONFIRMED", "The bridge did not confirm full background protection.")
                    report["takeoverEstablished"] = True
                for index, step in enumerate(steps):
                    active, active_index = report["steps"][index], index
                    trace = {}
                    phase = "resolve"
                    before = time.monotonic()
                    if step["kind"] == "assert":
                        value = raw[step["source"]]
                        condition = resolved_condition(step, raw)
                        if not condition_matches(value, condition):
                            raise BridgeError("ASSERTION_FAILED", "The condition did not match the earlier observation.")
                        active["matched"] = True
                    else:
                        args = resolved_args(step, raw)
                        operation = TOOL_BY_NAME[step["tool"]].operation
                        if step["kind"] == "action":
                            if operation not in context_actions or profile not in ("player", "developer"):
                                phase = "guard"
                                snapshot = await call("client.snapshot", {})
                                guard_snapshot(snapshot, protection)
                        if step["kind"] == "wait":
                            phase = "wait"
                            condition = resolved_condition(step, raw)
                            attempts = 0
                            if operation == "world.inspect_many" and args.get("includePlayerInventory") is True \
                                    and args.get("compact", False) is False and _pending_zero_inventory_available(health):
                                args = {**args, "allowPendingZero": True}
                            try:
                                with anyio.fail_after(step.get("timeout_ms", int(timeout * 1000)) / 1000):
                                    while True:
                                        attempts += 1
                                        record_wait_sample()
                                        active.update(attempts=attempts, matched=False)
                                        try:
                                            value = await call(operation, args)
                                        except BridgeError as pending_error:
                                            if pending_error.code != "INVENTORY_SYNC_PENDING_ZERO" or args.get("allowPendingZero") is not True:
                                                raise
                                            active["pendingZeroReads"] = active.get("pendingZeroReads", 0) + 1
                                            await anyio.sleep(step.get("interval_ms", 100) / 1000)
                                            continue
                                        await check_observation(value, operation)
                                        if raw_bytes + json_size(value) > MAX_RAW_BYTES:
                                            raise BridgeError("RAW_RESULT_LIMIT", "Unprojected workflow results exceed the independent 2 MiB memory bound.")
                                        active["observation"] = project(value, step)
                                        evidence = quantity_delta_evidence(value, condition)
                                        if evidence is not None:
                                            active["quantityDelta"] = evidence
                                        if condition_matches(value, condition):
                                            break
                                        await anyio.sleep(step.get("interval_ms", 100) / 1000)
                            except TimeoutError:
                                raise BridgeError("WAIT_TIMEOUT", "The condition was not observed before this step's wait expired.", attempts=attempts) from None
                            active.update(attempts=attempts, matched=True)
                        else:
                            phase = "action" if step["kind"] == "action" else "read"
                            value = await call(operation, args, action=step["kind"] == "action")
                            if step["kind"] == "action":
                                report["dispatchedActions"] += 1
                                report["acknowledgedActions"] += 1
                                active.update(dispatched=True, requestId=trace.get("request_id"), completion="acknowledged; use a read/wait/assert for server confirmation")
                            else:
                                await check_observation(value, operation)
                    if step["kind"] == "assert":
                        evidence = quantity_delta_evidence(value, condition)
                        if evidence is not None:
                            active["quantityDelta"] = evidence
                    phase = "retain"
                    # Guard DTOs are consumed immediately, never retained as
                    # hidden per-step data outside the 2 MiB raw-result budget.
                    if response_context(value) is not None:
                        value = dict(value)
                    size = json_size(value)
                    if raw_bytes + size > MAX_RAW_BYTES:
                        raise BridgeError("RAW_RESULT_LIMIT", "Unprojected workflow results exceed the independent 2 MiB memory bound.")
                    raw[step["id"]] = value
                    raw_bytes += size
                    # Reject an incomplete quantity baseline before any later write,
                    # rather than discovering it only at the final production wait.
                    for future in steps[index + 1:]:
                        future_condition = future.get("condition", {})
                        baseline = future_condition.get("baseline")
                        if baseline and baseline["step"] == step["id"]:
                            if future_condition["operator"] == "provider_count_delta":
                                provider_delta_baseline(value, baseline["path"], future_condition["match"], future_condition["value"])
                            else:
                                complete_item_count(value, baseline["path"], future_condition["match"])
                    active.update(status="completed", elapsed_ms=int((time.monotonic() - before) * 1000), observation=project(value, step))
                    report["completedSteps"] += 1
                phase = "finish"
                # Credentials and session are rechecked at the boundary without returning another full DTO.
                if _discovery is not None:
                    _discovery.bind(client, credentials, session)
                    # A validated native AFTER read or the standalone snapshot
                    # following an observation already guards every prior write.
                    # A final BEFORE action still needs its own after-snapshot.
                    if not fresh_after_guard:
                        end = await call("client.snapshot", {})
                        _guard(end, session, profile, arguments.get("expectedWorld"), protection)
                    _discovery.bind(client, credentials, session)
                else:
                    end = await call("bridge.health", {})
                    if end.get("profile") != profile:
                        raise BridgeError("PROFILE_CHANGED", "Access profile changed before workflow completion.")
                    await check_observation(end, "bridge.health")
    except (BridgeError, TimeoutError) as error:
        if isinstance(error, TimeoutError):
            error = BridgeError("WORKFLOW_TIMEOUT", "The total execution budget expired; subsequent steps were not executed.")
        unknown = False
        if phase == "action" and trace.get("dispatched"):
            unknown = trace.get("outcome_unknown", error.details.get("outcome_unknown", error.code not in KNOWN_REFUSALS))
            if not active.get("dispatched"):
                report["dispatchedActions"] += 1
            active.update(dispatched=True, outcomeUnknown=bool(unknown), requestId=trace.get("request_id"))
        if phase == "takeover" and trace.get("dispatched"):
            unknown = trace.get("outcome_unknown", error.details.get("outcome_unknown", True))
        report.update(status="failed", failedIndex=active_index, outcomeUnknown=bool(unknown), failedPhase=phase,
            error={"code": error.code, "message": error.message[:512], **{k: v for k, v in error.details.items() if k in {"attempts", "argument", "argument_path", "tool", "operation", "outcome_unknown", "rule"}}})
        if phase == "action" and operation == "player.navigate" and len(error.message) > 512:
            report["navigationFailureDiagnostics"] = ({"complete": True, "source": "native_navigation_error",
                "message": error.message, "evidenceOnly": True, "replayAllowed": False}
                if len(error.message.encode("utf-8")) <= 16_384 else
                {"complete": False, "omitted": True, "reason": "navigation_diagnostic_byte_budget"})
        if phase == "action" and operation in (SURVIVAL_OPERATION, "player.place_block"):
            try:
                if operation == "player.place_block":
                    placement_failure_fields(error.details)
                checkpoint = survival_failure_checkpoint(error.details, arguments.get("max_return_bytes", 65_536), operation=operation)
            except ValueError:
                report.update(outcomeUnknown=True, error={"code": "MALFORMED_RESPONSE",
                    "message": "The native survival failure checkpoint is malformed; no action is replayed.", "outcome_unknown": True})
            else:
                if checkpoint is not None:
                    report["placementCheckpoint" if operation == "player.place_block" else "survivalBuildCheckpoint"] = checkpoint
        if active is not None and active["status"] != "completed":
            active.update(status="failed", elapsed_ms=int((time.monotonic() - before) * 1000), error=report["error"])
        if trace.get("cancellation_status"):
            report["cancellationStatus"] = trace["cancellation_status"]
    cleanup = arguments.get("cleanup", [])
    if cleanup:
        report["cleanup"] = [{"id": step["id"], "kind": "action", "status": "not_executed"} for step in cleanup]
        report["cleanupStatus"] = "not_needed"
        report["executionElapsed_ms"] = int((time.monotonic() - start) * 1000)
        report["cleanup_timeout_ms"] = arguments.get("cleanup_timeout_ms", 2_000)
        # A preflight refusal must not mutate the world. An acknowledged or
        # uncertain dispatched action does require the declared stop attempts.
        eligible = lambda step: "afterStep" not in step or any(entry["id"] == step["afterStep"] and entry.get("dispatched") for entry in report["steps"])
        if report["dispatchedActions"] and any(eligible(step) for step in cleanup):
            cleanup_start = time.monotonic()
            report["cleanupStatus"] = "completed"
            cleanup_active = None
            cleanup_phase = "guard"
            try:
                with anyio.fail_after(report["cleanup_timeout_ms"] / 1000):
                    async with lock:
                        for step, entry in zip(cleanup, report["cleanup"]):
                            if not eligible(step):
                                entry["reason"] = "trigger_not_dispatched"
                                continue
                            cleanup_active = entry
                            cleanup_phase = "guard"
                            snapshot = await call("client.snapshot", {})
                            _guard(snapshot, session, profile, arguments.get("expectedWorld"), True)
                            cleanup_phase = "action"
                            value = await call(TOOL_BY_NAME[step["tool"]].operation, step["args"], action=True)
                            entry.update(status="completed", dispatched=True, requestId=trace.get("request_id"),
                                completion="acknowledged stop; observe state.allowedToWork/active for server confirmation", observation=project(value, step))
                            report["dispatchedActions"] += 1
                            report["acknowledgedActions"] += 1
            except (BridgeError, TimeoutError) as error:
                if isinstance(error, TimeoutError):
                    error = BridgeError("CLEANUP_TIMEOUT", "The separate stop-cleanup budget expired.")
                report["cleanupStatus"] = "failed"
                cleanup_error = {"code": error.code, "message": error.message[:512]}
                if cleanup_active is not None:
                    cleanup_active.update(status="failed", error=cleanup_error)
                    if cleanup_phase == "action" and trace.get("dispatched"):
                        unknown = trace.get("outcome_unknown", error.details.get("outcome_unknown", error.code not in KNOWN_REFUSALS))
                        cleanup_active.update(dispatched=True, outcomeUnknown=bool(unknown), requestId=trace.get("request_id"))
                        report["dispatchedActions"] += 1
                        report["outcomeUnknown"] |= bool(unknown)
                if report["status"] == "completed":
                    report.update(status="failed", failedPhase="cleanup", error=cleanup_error)
            report["cleanupElapsed_ms"] = int((time.monotonic() - cleanup_start) * 1000)
    report.update(elapsed_ms=int((time.monotonic() - start) * 1000), rawResultBytes=raw_bytes,
                  sideEffectsPossible=report["dispatchedActions"] > 0 or report["takeoverEstablished"] or report["outcomeUnknown"])
    if not observe:
        from .batch_receipts import sequence_receipts
        report["checkpoint"] = sequence_receipts(steps, report)
    return _bounded_report(report, arguments.get("max_return_bytes", 65_536))
