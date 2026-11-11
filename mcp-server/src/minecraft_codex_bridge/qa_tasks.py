"""One exact native QA case with bounded in-memory preparation and report acceptance."""

from __future__ import annotations

import math
import re
import time
from collections import Counter
from typing import Any
from uuid import UUID

import anyio

from .bridge import BridgeError, request_context, response_context
from .conveniences import json_size
from .scan_regions import validate_scan_region
from .qa_flow_schema import DECLARED_MACHINE_FLOWS_SCHEMA


QA_ORIGIN_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "x": {"type": "integer", "minimum": -30_000_000, "maximum": 29_999_999},
        "y": {"type": "integer", "minimum": 3, "maximum": 255},
        "z": {"type": "integer", "minimum": -30_000_000, "maximum": 29_999_999},
    },
    "required": ["x", "y", "z"],
}


QA_TEST_ONCE_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "expectedSessionId": {"type": "string", "minLength": 1, "maxLength": 128},
        "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128},
        "selector": {"type": "string", "minLength": 3, "maxLength": 256,
                     "pattern": r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$"},
        "origin": QA_ORIGIN_SCHEMA,
        "machineFlows": DECLARED_MACHINE_FLOWS_SCHEMA,
        "timeout_ms": {"type": "integer", "minimum": 100, "maximum": 60_000, "default": 10_000},
        "maxRequests": {"type": "integer", "minimum": 16, "maximum": 256, "default": 128},
        "maxScanCells": {"type": "integer", "minimum": 1, "maximum": 33_554_432, "default": 262_144},
        "maxReportPages": {"type": "integer", "minimum": 2, "maximum": 128, "default": 32},
        "reportPageSize": {"type": "integer", "minimum": 1, "maximum": 32, "default": 8},
        "requireFullFlow": {"type": "boolean", "default": False},
        "includeDetails": {"type": "boolean", "default": False},
        "max_return_bytes": {"type": "integer", "minimum": 16_384, "maximum": 262_144, "default": 65_536},
    },
    "required": ["expectedSessionId", "expectedWorld", "selector"],
    "allOf": [{"if": {"required": ["machineFlows"]},
        "then": {"properties": {"selector": {"const": "tstqa:DeclaredMachineFlow.run"}}},
        "else": {"properties": {"maxScanCells": {"maximum": 1_048_576}}}}],
}

PLAN_SOURCE = "horizonqa_public_template_loader"
RUNNER_CONTRACT = "horizonqa-0.15.0-singleton-cell5-margin3"
PRIVATE_SOURCE = "tstqa_public_instance_adapter"
PRIVATE_PROVIDER = "me.eigenraven.personalspace.world.PersonalWorldProvider"
PRIVATE_CONTRACT = "tstqa-private-instance-v1"
WHOLE_BUDGET_MS = 10_000
MAX_CAPTURE_BYTES = 2_097_152
LONG_MAX = 9_223_372_036_854_775_807
COUNT_FIELDS = ("failed", "timedOut", "skipped", "incomplete", "requiredFailures", "optionalFailures", "infrastructureErrors")
STATUS_FIELDS = ["runId", "state", "nativeBatchActive", "freshSinceStart", "selectionMatches", "selectedTests",
                 "selectedIds", "selectedIdsTruncated", "correlation", "owned", "nativeReport"]
RESULT_FIELDS = ["runId", "section", "entries", "total", "offset", "limit", "hasMore", "truncated", "nextOffset",
                 "correlation", "owned", "freshSinceStart", "nativeReport"]


class _QaFailure(Exception):
    def __init__(self, code: str, message: str, *, unsupported: bool = False):
        super().__init__(message)
        self.code, self.message, self.unsupported = code, message, unsupported


def _require(condition: bool, code: str, message: str, *, unsupported: bool = False) -> None:
    if not condition:
        raise _QaFailure(code, message, unsupported=unsupported)


def _integer(value: Any, low: int = 0, high: int = 100_000) -> bool:
    return type(value) is int and low <= value <= high


def _read(identifier: str, tool: str, args: dict[str, Any] | None = None, fields: list[str] | None = None) -> dict[str, Any]:
    step = {"id": identifier, "kind": "read", "tool": tool, "args": args or {}, "omitEmptySlots": False}
    if fields is not None:
        step["fields"] = fields
    return step


def _inside(point: dict[str, Any], bounds: dict[str, int]) -> bool:
    return all(bounds["min" + axis.upper()] <= point[axis] <= bounds["max" + axis.upper()] for axis in "xyz")


def _intersects(box: dict[str, Any], bounds: dict[str, int]) -> bool:
    return all(box["max" + axis] > bounds["min" + axis] and box["min" + axis] < bounds["max" + axis] + 1
               for axis in "XYZ")


def _inventory(value: dict[str, Any]) -> list[dict[str, Any]]:
    slots = value.get("slots")
    _require(value.get("truncated") is False and value.get("totalSlots") == 41 and isinstance(slots, list)
             and len(slots) == 41 and all(isinstance(slot, dict) and _integer(slot.get("index"), 0, 40)
             and "item" in slot for slot in slots) and {slot["index"] for slot in slots} == set(range(41))
             and "cursorItem" in value and value["cursorItem"] is None,
             "qa_player_coverage_unavailable", "QA needs complete actual player indices 0..40 and an explicitly empty cursor.")
    return slots


def _position(value, bounds):
    _require(isinstance(value, dict) and set(value) == set("xyz")
             and all(type(value[axis]) is int for axis in "xyz") and _inside(value, bounds),
             "qa_flow_position_invalid", "Each actual source/receiver position must stay inside the completely observed fixture envelope.")
    return tuple(value[axis] for axis in "xyz")


def _identity(value, *, fluid=False):
    _require(isinstance(value, dict) and isinstance(value.get("nbt"), str) and len(value["nbt"]) <= 131_072,
             "qa_flow_identity_invalid", "Actual item/fluid identities need their complete exact NBT string.")
    if fluid:
        _require(isinstance(value.get("name"), str) and 0 < len(value["name"]) <= 256,
                 "qa_flow_identity_invalid", "Every nonempty actual fluid needs its exact public name.")
        return value["name"], value["nbt"]
    registry_name, metadata = value.get("registryName"), value.get("metadata")
    # Forge 1.7.10 mod namespaces may contain '|'; retain exact spelling/case.
    if not (isinstance(registry_name, str)
            and re.fullmatch(r"[A-Za-z0-9_.|-]+:[A-Za-z0-9_./-]+", registry_name) is not None
            and _integer(metadata, 0, 2_147_483_647)):
        name_detail = repr(registry_name[:128])[:160] if isinstance(registry_name, str) else "<non-string>"
        metadata_detail = (repr(metadata[:32] if isinstance(metadata, str) else metadata)[:64]
                           if type(metadata) in (str, int, float, bool, type(None)) else "<non-scalar>")
        raise _QaFailure("qa_flow_identity_invalid", "Every nonempty actual item needs its exact registry and typed metadata. "
                         f"registryName={name_detail} (type={type(registry_name).__name__[:32]}); "
                         f"metadata={metadata_detail} (type={type(metadata).__name__[:32]}).")
    return registry_name, metadata, value["nbt"]


def _bag(entries, *, fluid=False):
    _require(isinstance(entries, list) and len(entries) <= 128,
             "qa_flow_declaration_invalid", "Finite typed resource declarations must be bounded complete arrays.")
    result = Counter()
    quantity = "amount" if fluid else "count"
    for entry in entries:
        _require(isinstance(entry, dict) and _integer(entry.get(quantity), 1, LONG_MAX),
                 "qa_flow_quantity_invalid", "Declared finite quantities must be positive typed signed-long values.")
        identity = _identity(entry, fluid=fluid)
        result[identity] += entry[quantity]
        _require(result[identity] <= LONG_MAX, "qa_flow_quantity_invalid", "Finite accounting cannot overflow signed long.")
    return result


def _resource_quantities(bag, *, fluid=False):
    """Project complete accepted counters without retaining mutable observation rows."""
    if fluid:
        return [{"name": identity[0], "nbt": identity[1], "amount": str(quantity)}
                for identity, quantity in sorted(bag.items())]
    return [{"registryName": identity[0], "metadata": identity[1], "nbt": identity[2], "count": str(quantity)}
            for identity, quantity in sorted(bag.items())]


ITEM_CHANCE_CONTRACT = "tstqa-finite-item-chances-v1"


def _output_chances(recipe):
    _require(isinstance(recipe, dict) and isinstance(recipe.get("outputs"), list) and len(recipe["outputs"]) <= 8,
             "qa_chance_declaration_invalid", "Chance declarations require complete bounded raw output slots.")
    chances = recipe.get("outputChances", [10000] * len(recipe["outputs"]))
    _require(isinstance(chances, list) and len(chances) == len(recipe["outputs"])
             and all(_integer(chance, 0, 10000) for chance in chances),
             "qa_chance_declaration_invalid", "outputChances must retain one exact integer 0..10000 per raw output index.")
    return chances


def _legal_chance_bag(raw, actual):
    legal = {}
    for row in raw:
        identity = _identity(row)
        totals = legal.setdefault(identity, {0})
        chance, count = row["chance"], row["count"]
        if chance:
            added = {total + count for total in totals}
            _require(all(total <= LONG_MAX for total in added), "qa_flow_quantity_invalid", "Raw possible aggregate exceeds signed long.")
            legal[identity] = added if chance == 10000 else totals | added
    _require(set(actual) <= set(legal) and all(actual[identity] in totals for identity, totals in legal.items()),
             "qa_chance_actual_illegal", "Actual output must be an exact discrete raw-slot subset, preserving guaranteed, zero-chance and exact identity/NBT outputs.")


def _binding_bag(check, domain, binding):
    rows = check[domain]["bindings"][binding]["entries"]
    key = "amount" if domain == "fluids" else "count"
    return _bag([row for row in rows if row[key]], fluid=domain == "fluids")


def _chance_actual(evidence, declarations, declared, checks):
    submitted = evidence.get("machineFlowDeclaration")
    _require(isinstance(submitted, dict) and submitted.get("fixtureTemplate") != "tstqa:core_flow_cell",
             "qa_chance_context_invalid", "Item chance evidence belongs to an explicit ordinary finite multiblock declaration.")
    recipe = submitted.get("recipe")
    chances = _output_chances(recipe)
    _require(any(chance != 10000 for chance in chances), "qa_chance_context_invalid", "Schema 2 must identify explicit finite item chances.")
    raw = declarations.get("rawItemOutputs")
    _require(isinstance(raw, list) and len(raw) == len(chances) and all(isinstance(row, dict)
             and type(row.get("outputIndex")) is int and row["outputIndex"] == index
             and _integer(row.get("chance"), 0, 10000) and row["chance"] == chances[index]
             and _integer(row.get("count"), 1, 2147483647)
             and row["count"] == recipe["outputs"][index].get("count")
             and row.get("registryName") == recipe["outputs"][index].get("registryName")
             and type(row.get("metadata")) is int and row["metadata"] == recipe["outputs"][index].get("metadata")
             and row.get("nbt") in ("", "{}") for index, row in enumerate(raw)),
             "qa_chance_raw_changed", "Immutable raw potential, original index and native chance must match every submitted output slot.")
    _require(_bag(raw) == declared["itemOutputs"], "qa_chance_raw_changed", "Merged potential must equal complete raw outputs, never actual products.")
    ledger = evidence.get("finiteOutputLedger")
    _require(isinstance(ledger, dict) and ledger.get("contract") == ITEM_CHANCE_CONTRACT
             and all(ledger.get(key) == evidence.get(key) for key in ("caseId", "runId", "sessionId", "dimension"))
             and type(ledger.get("dimension")) is int and isinstance(ledger.get("declarationDigest"), str)
             and re.fullmatch(r"[0-9a-f]{64}", ledger["declarationDigest"])
             and ledger["declarationDigest"] == submitted.get("declarationDigest")
             and ledger.get("capacityPolicy") == "maximum_raw_potential_plus_physical_catalysts"
             and ledger.get("probabilityDistributionVerified") is False and ledger.get("actualFrozen") is True
             and ledger.get("completedIdle") is True and ledger.get("inputsConsumed") is True,
             "qa_chance_context_invalid", "The once-frozen actual ledger requires exact run/session/dimension/declaration and native completed-consumption scope.")
    _require(_integer(ledger.get("recipesDoneBaseline"), 0, LONG_MAX - 1)
             and _integer(ledger.get("recipesDoneFinal"), 1, LONG_MAX)
             and ledger["recipesDoneFinal"] == ledger["recipesDoneBaseline"] + 1
             and type(ledger.get("recipesDoneDelta")) is int and ledger["recipesDoneDelta"] == 1
             and type(ledger.get("recipesDoneStable")) is int and ledger["recipesDoneStable"] == ledger["recipesDoneFinal"],
             "qa_chance_completion_invalid", "One finite execution must advance native recipesDone exactly once and stay unchanged through stability.")
    ticks = {row["name"]: row["worldTick"] for row in evidence["stages"]}
    _require(_integer(ledger.get("depositedWorldTick"), ticks["finite_inputs_supplied"], ticks["production_complete_and_stopped"])
             and type(ledger.get("stableWorldTick")) is int and ledger["stableWorldTick"] == ticks["normal_world_stability_verified"],
             "qa_chance_timing_invalid", "Actual deposit and unchanged completion counter must retain normal production and 85-tick checkpoint timing.")
    _position(ledger.get("controllerPosition"), evidence["loadedFixtureBounds"])
    item_bindings = checks["prepared"]["items"]["bindings"]
    fluid_bindings = checks["prepared"]["fluids"]["bindings"]
    _require(set(item_bindings) == {"finite_input", "output", "item_receiver"}
             and set(fluid_bindings) <= {"finite_fluid_input", "output", "fluid_receiver"}
             and ledger.get("allOutputPortsCovered") is True
             and type(ledger.get("itemOutputPortCount")) is int and ledger["itemOutputPortCount"] == 1
             and type(ledger.get("fluidOutputPortCount")) is int and ledger["fluidOutputPortCount"] == int("output" in fluid_bindings)
             and _position(ledger.get("itemOutputPosition"), evidence["loadedFixtureBounds"]) == item_bindings["output"]["position"]
             and ("output" not in fluid_bindings or _position(ledger.get("fluidOutputPosition"), evidence["loadedFixtureBounds"]) == fluid_bindings["output"]["position"]),
             "qa_chance_output_coverage_invalid", "All actual output ports must be explicitly bound; unbound extra buses/hatches are refused even for zero output.")
    actual, fluids = _bag(ledger.get("depositedItems")), _bag(ledger.get("depositedFluids"), fluid=True)
    _legal_chance_bag(raw, actual)
    _require(fluids == declared["fluidOutputs"], "qa_chance_fluid_changed", "Fluid products retain their deterministic exact declaration.")
    _require(type(ledger.get("productRecoveryExercised")) is bool and ledger["productRecoveryExercised"] == bool(actual or fluids),
             "qa_chance_recovery_scope_invalid", "Empty products cannot claim that product recovery was exercised by catalyst recovery.")
    for stage, check in checks.items():
        expected_input = declared["consumedItems"] + declared["physicalCatalysts"] if stage == "supplied" else declared["physicalCatalysts"] if stage == "produced" else Counter()
        _require(_binding_bag(check, "items", "finite_input") == expected_input
                 and _binding_bag(check, "items", "output") == (actual if stage == "produced" else Counter()),
                 "qa_chance_port_quantities_invalid", "Exact consumption/catalysts and real actual products must match their individual complete input/output ports.")
        expected_in = declared["fluidInputs"] if stage == "supplied" else Counter()
        expected_out = fluids if stage == "produced" else Counter()
        _require(("finite_fluid_input" in fluid_bindings or not declared["fluidInputs"])
                 and ("output" in fluid_bindings or not fluids)
                 and ("finite_fluid_input" not in fluid_bindings or _binding_bag(check, "fluids", "finite_fluid_input") == expected_in)
                 and ("output" not in fluid_bindings or _binding_bag(check, "fluids", "output") == expected_out),
                 "qa_chance_port_quantities_invalid", "Deterministic fluids must retain complete separate source/output port quantities.")
    native = ledger.get("nativeGenerated")
    _require(isinstance(native, dict) and native.get("status") in ("observed", "unknown"),
             "qa_chance_pending_invalid", "Native generated evidence needs explicit observed or unknown state.")
    if native["status"] == "observed":
        generated_items, generated_fluids = _bag(native.get("items")), _bag(native.get("fluids"), fluid=True)
        _legal_chance_bag(raw, generated_items)
        _require(native.get("processingObserved") is True and _integer(native.get("maxProgress"), 1, 2147483647)
                 and type(native.get("recipesDoneAtCapture")) is int and native["recipesDoneAtCapture"] == ledger["recipesDoneBaseline"]
                 and native.get("samplePhase") == "normal_server_END_before_sequence"
                 and _integer(native.get("worldTick"), ticks["finite_inputs_supplied"], ledger["depositedWorldTick"])
                 and generated_items == actual and generated_fluids == fluids and ledger.get("generationToDepositVerified") is True,
                 "qa_chance_generation_deposit_mismatch", "Deep-copied native generated pending must be observed in this processing window and equal every real deposited item/fluid.")
    else:
        _require(set(native) == {"status"} and ledger.get("generationToDepositVerified") is False,
                 "qa_chance_pending_invalid", "Unknown pending cannot be an invented empty observed vector or claim full generation-to-deposit proof.")
    return actual, ledger


DIGITAL_CHEST_SINK_API = "GTNHLib ItemSink.store(ImmutableItemStack), bulk channel 0, forced=false"


def _digital_item_row(row, configured, limit=None, *, slot=None, physical=False):
    if physical:
        _require(isinstance(row, dict) and _integer(row.get("stackLimit"), 1, 64),
                 "qa_flow_digital_capacity_invalid", "Every actual physical/display row needs its public item/inventory stack limit.")
        limit = row["stackLimit"]
    _require(isinstance(row, dict) and (slot is None or type(row.get("slot")) is int and row["slot"] == slot)
             and _integer(row.get("count"), 0, limit),
             "qa_flow_digital_quantity_invalid", "Every actual digital bulk/physical/display row needs its complete typed count within public capacity.")
    quantity = row["count"]
    if quantity or any(key in row for key in ("registryName", "metadata", "nbt")):
        identity = _identity(row)
        _require(configured is not None and identity == configured,
                 "qa_flow_digital_identity_changed", "Every actual digital resource and retained empty identity must match this channel's exact configured registry/metadata/NBT.")
    return quantity


def _digital_chest_channels(binding, bounds):
    channels, total = binding.get("channels"), binding.get("totalChannels")
    _require(_integer(total, 1, 16) and isinstance(channels, list) and len(channels) == total
             and all(isinstance(channel, dict) and _integer(channel.get("channel"), 0, total - 1) for channel in channels)
             and {channel["channel"] for channel in channels} == set(range(total))
             and not any(key in binding for key in ("slots", "totalSlots", "tanks", "totalTanks")),
             "qa_flow_digital_channels_incomplete", "Digital receivers need 1..16 complete distinct real channel indices and cannot masquerade as a slot/tank inventory.")
    bag, positions, configured_types, normalized, layout = Counter(), set(), set(), [], []
    for channel in sorted(channels, key=lambda entry: entry["channel"]):
        _require(channel.get("complete") is True and channel.get("worldTileIdentityVerified") is True
                 and channel.get("metaTileIdentityVerified") is True
                 and channel.get("registeredName") == "gt.blockmachines.super.chest.tier.01"
                 and channel.get("runtimeClass") == "gregtech.common.tileentities.storage.MTESuperChest"
                 and channel.get("voidOverflow") is False and channel.get("filterDisabled") is False
                 and channel.get("itemSinkApi") == DIGITAL_CHEST_SINK_API
                 and channel.get("displayProjectionExcludedFromAccounting") is True,
                 "qa_flow_digital_binding_invalid", "Every digital channel needs its exact actual standard receiver, checked world/meta identity, non-voiding public sink and explicitly excluded display projection.")
        position = _position(channel.get("position"), bounds)
        _require(position not in positions, "qa_flow_source_receiver_alias", "Each digital channel must occupy a distinct real receiver position.")
        positions.add(position)
        if channel["channel"] == 0:
            _require(channel["position"] == binding["position"], "qa_flow_digital_binding_invalid", "The digital parent receiver position must equal its first actual channel.")
        capacity = channel.get("capacity")
        _require(_integer(capacity, 1, LONG_MAX),
                 "qa_flow_digital_capacity_invalid", "Digital bulk capacity must be a complete positive public signed-long value.")
        configured = channel.get("configuredType")
        _require("configuredType" in channel and (isinstance(configured, dict) or configured is None),
                 "qa_flow_digital_binding_invalid", "Each actual channel must retain its explicit configured type or one empty fluid-only channel.")
        if configured is None:
            _require(total == 1, "qa_flow_digital_binding_invalid", "Only one empty fluid-only digital channel may have no configured item identity.")
        else:
            _require(type(configured.get("count")) is int and configured["count"] == 1,
                     "qa_flow_digital_binding_invalid", "Digital channel configuration is a count-one exact identity template.")
            configured = _identity(configured)
            _require(configured not in configured_types, "qa_flow_digital_binding_invalid", "Distinct digital channels cannot duplicate an exact configured registry/metadata/NBT identity.")
            configured_types.add(configured)
        quantity = _digital_item_row(channel.get("bulk"), configured, capacity)
        physical = channel.get("physicalSlots")
        _require(isinstance(physical, list) and len(physical) == 2
                 and all(isinstance(row, dict) and _integer(row.get("slot"), 0, 1) for row in physical)
                 and {row["slot"] for row in physical} == {0, 1},
                 "qa_flow_inventory_incomplete", "Digital physical coverage must retain exact real slots 0 and 1 once, including empties.")
        for row in physical:
            count = _digital_item_row(row, configured, slot=row["slot"], physical=True)
            _require(row["slot"] != 0 or count == 0, "qa_flow_digital_quantity_invalid",
                     "A bulk-only public digital receiver must retain an explicitly empty actual input slot 0.")
            quantity += count
        _digital_item_row(channel.get("displayProjection"), configured, slot=2, physical=True)
        _require(quantity <= LONG_MAX, "qa_flow_quantity_invalid", "Digital bulk plus actual physical resources cannot overflow signed long.")
        if quantity:
            bag[configured] += quantity
            _require(bag[configured] <= LONG_MAX, "qa_flow_quantity_invalid", "Actual digital receiver accounting overflowed signed long.")
        normalized.append(dict(channel))
        layout.append({"position": position, "capacity": capacity,
                       "stackLimits": [row["stackLimit"] for row in sorted(physical, key=lambda row: row["slot"])] + [channel["displayProjection"]["stackLimit"]],
                       "configuredType": configured})
    return bag, {"position": _position(binding["position"], bounds), "total": total,
                 "representation": "digital_chest_channels", "layout": layout, "entries": normalized}, positions


def _checkpoint(value, bounds, multiple_receivers=False):
    """Complete actual slots/tanks, grouped only by their producer's public bindings."""
    _require(isinstance(value, dict), "qa_flow_checkpoint_invalid", "A checkpoint must remain a complete object.")
    result = {}
    for domain, fluid, receiver in (("items", False, "item_receiver"), ("fluids", True, "fluid_receiver")):
        entries = value.get(domain)
        _require(isinstance(entries, list) and 1 <= len(entries) <= (48 if fluid and multiple_receivers else 8),
                 "qa_flow_checkpoint_invalid", "Every checkpoint needs complete source and actual receiver binding arrays.")
        sources, received, signatures, positions = Counter(), Counter(), {}, set()
        for binding in entries:
            _require(isinstance(binding, dict) and isinstance(binding.get("binding"), str) and binding["binding"]
                     and binding["binding"] not in signatures and binding.get("complete") is True,
                     "qa_flow_binding_invalid", "Each actual binding must be unique and explicitly complete.")
            is_receiver = binding["binding"] == receiver
            if fluid and binding["binding"].startswith("fluid_receiver_"):
                _require(multiple_receivers and re.fullmatch(r"fluid_receiver_([1-9]|1[0-5])", binding["binding"]) is not None,
                         "qa_flow_binding_invalid", "Additional physical receivers require the closed bounded deterministic multi-fluid contract.")
                is_receiver = True
            position = _position(binding.get("position"), bounds)
            _require(position not in positions, "qa_flow_source_receiver_alias", "Actual source and receiver identities must use distinct world positions within each resource domain.")
            positions.add(position)
            if binding.get("representation") == "digital_chest_channels":
                _require(not fluid and binding["binding"] == receiver,
                         "qa_flow_digital_binding_invalid", "Digital chest channels are supported only as the actual item receiver.")
                bag, signature, channel_positions = _digital_chest_channels(binding, bounds)
                _require(channel_positions.isdisjoint(positions - {position}), "qa_flow_source_receiver_alias",
                         "All actual digital channels must remain separate from every source binding.")
                positions.update(channel_positions)
                signatures[binding["binding"]] = signature
                received = bag
                continue
            rows_key, total_key, index_key, quantity_key, bound = (("tanks", "totalTanks", "tank", "amount", 8) if fluid
                                                                else ("slots", "totalSlots", "slot", "count", 128))
            rows, total = binding.get(rows_key), binding.get(total_key)
            _require(_integer(total, 0, bound) and isinstance(rows, list) and len(rows) == total
                     and all(isinstance(row, dict) and _integer(row.get(index_key), 0, max(0, total - 1)) for row in rows)
                     and {row[index_key] for row in rows} == set(range(total)),
                     "qa_flow_inventory_incomplete", "Actual slot/tank coverage must retain every unique typed index, including empties.")
            bag = Counter()
            normalized = []
            for row in sorted(rows, key=lambda entry: entry[index_key]):
                quantity = row.get(quantity_key)
                _require(_integer(quantity, 0, LONG_MAX), "qa_flow_quantity_invalid", "Actual resource quantities need typed nonnegative signed-long values.")
                if fluid:
                    _require(_integer(row.get("capacity"), 0, LONG_MAX) and quantity <= row["capacity"],
                             "qa_flow_tank_invalid", "Actual tank amount must remain within its typed capacity.")
                if quantity:
                    identity = _identity(row, fluid=fluid)
                    bag[identity] += quantity
                    _require(bag[identity] <= LONG_MAX, "qa_flow_quantity_invalid", "Actual resource accounting overflowed signed long.")
                # Include zero-count identity fields too; stable snapshots cannot hide a changed ghost slot.
                normalized.append(dict(row))
            signatures[binding["binding"]] = {"position": position, "total": total, "entries": normalized}
            if is_receiver:
                received.update(bag)
            else:
                sources.update(bag)
        _require(receiver in signatures and (fluid or len(entries) >= 2),
                 "qa_flow_receiver_missing", "The real receiver must be an explicit complete binding separate from sources.")
        result[domain] = {"source": sources, "receiver": received, "bindings": signatures}
    return result


class _RecordingClient:
    """Keeps native QA DTOs before workflow output projection; credentials stay pinned."""

    def __init__(self, client, credentials, maximum: int):
        self.client, self.credentials, self.maximum = client, credentials, maximum
        self.configuration = client.configuration
        self.calls = 0
        self.operation_counts: Counter[str] = Counter()
        self.request_reserve = 0
        self.latest: dict[tuple[str, str], dict[str, Any]] = {}
        self.ticket: dict[str, Any] | None = None
        self.bytes = 0
        self.maximum_capture_bytes = MAX_CAPTURE_BYTES
        self.result_pages: list[dict[str, Any]] = []
        self.action_attempted = False
        self.submission_rejected = False
        self.submitted_at = None
        self.guard = None
        self.run_guard = None
        self.qa_guard = None
        self.initial_snapshot_guard = None
        self.remote_binding = None

    async def _request(self, operation, args, **kwargs):
        _require(self.calls < self.maximum, "qa_request_budget", "The declared finite QA request budget was exhausted.")
        _require(self.calls < self.maximum - self.request_reserve, "qa_request_budget_reserved",
                 "QA waiting reached the request allowance reserved for same-run reports and complete after-observation.")
        self.calls += 1
        self.operation_counts[operation] += 1
        submission = operation == "qa.run" or operation == "qa.command" and args.get("command") == "run"
        if submission:
            _require(not self.action_attempted, "qa_run_replay_forbidden", "A QA run is submitted at most once.")
            self.action_attempted = True
            self.submitted_at = time.monotonic()
        if self.configuration.read() != self.credentials:
            raise BridgeError("SESSION_CHANGED", "The QA runtime connection changed; later operations were blocked.")
        kwargs["expected_credentials"] = self.credentials
        if self.remote_binding is not None and operation.startswith("qa."):
            args={**args,"expectedSessionId":self.remote_binding["sessionId"],"expectedConnectionId":self.remote_binding["connectionId"]}
        try:
            value=await self.client.call(operation,args,**kwargs)
        except BridgeError as error:
            if submission and error.details.get("original_action_executed") is False and error.details.get("outcome_unknown") is False:
                self.submission_rejected = True
            raise
        if submission and value.get("ok") is False and value.get("submitted") is False and value.get("sideEffectsPossible") is False and value.get("outcomeUnknown") is False:
            self.submission_rejected = True
            raise BridgeError(value.get("errorCode", "qa_submission_rejected"), value.get("error", "QA submission rejected before execution."),
                              original_action_executed=False, outcome_unknown=False)
        if self.remote_binding is not None and (operation.startswith("qa.") or operation in ("world.scan","world.tiles","world.entities","recipes.query")):
            _require(value.get("connectionId")==self.remote_binding["connectionId"]
                     and value.get("sessionId")==self.remote_binding["sessionId"]
                     and value.get("dimension")==self.remote_binding["dimension"]
                     and value.get("transportSource",value.get("source"))=="remote_server_tick",
                     "qa_context_changed","Remote QA/observations must retain the pinned runtime, connection and authoritative dimension.")
        return value

    async def call(self, operation, args, **kwargs):
        initial_guard = self.initial_snapshot_guard if operation == "client.snapshot" else None
        if initial_guard is not None:
            self.initial_snapshot_guard = None
        before = time.monotonic()
        value = await self._request(operation, args, **kwargs)
        if initial_guard is not None:
            async def read_snapshot():
                return await self._request(operation, args, **kwargs)
            value = await initial_guard(value, read_snapshot, before)
        submission = operation == "qa.run" or operation == "qa.command" and args.get("command") == "run"
        if self.guard is not None:
            if operation == "client.snapshot":
                self.guard(value)
            context = response_context(value)
            if isinstance(context, dict) and isinstance(context.get("snapshot"), dict):
                self.guard(context["snapshot"])
        section = args.get("section", "") if operation in ("qa.catalog", "qa.results") else ""
        self.latest[(operation, section)] = value
        if submission:
            self.ticket = dict(value)
        if self.qa_guard is not None and operation.startswith("qa."):
            self.qa_guard(value)
        if submission:
            if self.run_guard is not None:
                self.run_guard(value)
        if operation == "qa.results":
            size = json_size(value)
            _require(self.bytes + size <= self.maximum_capture_bytes, "qa_report_memory_budget",
                     "Complete QA report pages exceeded 2 MiB." if self.maximum_capture_bytes == MAX_CAPTURE_BYTES
                     else "Complete QA report pages exceeded the declared bounded capture allowance.")
            self.bytes += size
            self.result_pages.append(dict(value))
        return value


class _QaTask:
    def __init__(self, client, args, runner, started):
        self.args, self.runner, self.started = args, runner, started
        self.deadline = started + args.get("timeout_ms", 10_000) / 1000
        self.client = _RecordingClient(client, client.configuration.read(), args.get("maxRequests", 128))
        self.client.guard = self.snapshot
        self.client.run_guard = self.validate_run_ticket
        self.client.qa_guard = self.qa_context
        self.dimension: int | None = None
        self.provider_class: str | None = None
        self.observation_source = "integrated_server"
        self.flight_source = "integrated_server_tick"
        self.local_lock = anyio.Lock()
        self._workflow_discovery = None
        self._workflow_background_required = False
        self._status_wait_max_ms = 0
        # Custom runners keep their existing three-argument contract.
        from .workflows import run_workflow
        self._native_workflow_runner = runner is run_workflow
        self.phase = "preflight"
        self.phases: list[dict[str, Any]] = []
        self.case: dict[str, Any] | None = None
        self.plan: dict[str, Any] | None = None
        self.requested_origin: dict[str, int] | None = None
        self.preflight: dict[str, Any] = {}
        self.after_scan: dict[str, Any] = {}
        self.scan_cells = 0
        self._scan_health: dict[str, Any] | None = None
        self.flow_before: dict[str, Any] | None = None
        self.terminal: dict[str, Any] | None = None
        self.cases: list[dict[str, Any]] = []
        self.issues: list[dict[str, Any]] = []
        self.reports_complete = False
        self.report_coherent = True
        self.diagnostics_complete = True
        self.diagnostic_errors: list[dict[str, str]] = []
        self.polling: dict[str, Any] = {}
        self.quantitative_summary: dict[str, Any] | None = None
        self.full_flow: dict[str, Any] = {"available": False, "verified": False,
            "reason": "No fresh complete public TST-QA preparation/execution/receiver-retrieval evidence has been accepted."}

    def remaining(self) -> int:
        return max(1, int((self.deadline - time.monotonic()) * 1000))

    async def workflow(self, name: str, steps: list[dict[str, Any]], *, diagnostic: bool = False):
        self.phase = name
        before = time.monotonic()
        plan = {"expectedSessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
                "timeout_ms": 2_000 if diagnostic else max(100, self.remaining()),
                "max_return_bytes": 262_144, "steps": steps}
        if self._native_workflow_runner and self._workflow_discovery is not None:
            report = await self.runner(self.client, plan, self.local_lock,
                _discovery=self._workflow_discovery, _require_background=self._workflow_background_required)
            # A failed segment may still establish takeover. Later diagnostics
            # must retain it, never reacquire it or replay the submitted run.
            self._workflow_background_required |= report.get("takeoverEstablished") is True
        else:
            report = await self.runner(self.client, plan, self.local_lock)
        self.phases.append({"phase": name, "status": report["status"],
                            "elapsed_ms": int((time.monotonic() - before) * 1000)})
        if report["status"] != "completed":
            error = report.get("error", {})
            raise _QaFailure(error.get("code", "qa_workflow_failed"), error.get("message", "A guarded QA workflow failed."))
        return report

    async def read(self, operation: str, tool: str, args=None, *, section="", diagnostic=False):
        if operation == "world.scan":
            return await self.read_scan(args)
        await self.workflow("read_" + operation.replace(".", "_") + ("_" + section if section else ""),
                            [_read("value", tool, args)], diagnostic=diagnostic)
        return self.client.latest[(operation, section)]

    async def discover_scan(self):
        from .workflows import _require_capability
        health = self._workflow_discovery.bind(self.client, self.client.credentials, self.args["expectedSessionId"]) \
            if self._workflow_discovery is not None else await self.client.call("bridge.health", {})
        _require(type(health.get("protocolVersion")) is int and health["protocolVersion"] == 1
                 and health.get("sessionId") == self.args["expectedSessionId"] and health.get("profile") == "developer",
                 "qa_context_changed", "Scan capabilities must retain the exact developer session; the next snapshot verifies live context.")
        _require(health.get("worldScanMinecraftAir") is True, "qa_literal_air_unavailable",
                 "Current health does not expose the literal minecraft:air contract.", unsupported=True)
        _require_capability(health, "world.scan")
        await self.client.call("client.snapshot", {})
        return health

    async def read_scan(self, arguments):
        # Native scan cursors are one-shot, so ordinary repeatable workflow
        # read/wait tools deliberately exclude them. This fixed task consumes
        # each once with the same current capability/context/credential guards.
        from .workflows import _context_capabilities
        self.phase = "read_world_scan"
        before = time.monotonic()
        try:
            if "cursor" not in arguments:
                self._scan_health = None
                self._scan_health = await self.discover_scan()
            health = self._scan_health
            _require(isinstance(health, dict), "qa_scan_context_unavailable",
                     "A continuation must belong to this freshly guarded exact scan.")
            # Only capabilities are retained for this cursor chain. Every page
            # has a fresh after-snapshot; it also guards the next continuation,
            # whose native cursor enforces the world/dimension/permission epoch.
            guarded = "world.scan" in _context_capabilities(health)[0]
            context = {"version": 1, "expectedSessionId": self.args["expectedSessionId"], "expectedProfile": "developer",
                       "expectedWorld": self.args["expectedWorld"], "requireBackground": False} if guarded else None
            with request_context(context):
                value = await self.client.call("world.scan", arguments)
            if guarded:
                evidence = response_context(value)
                _require(isinstance(evidence, dict) and evidence.get("operation") == "world.scan"
                         and evidence.get("phase") == "after" and type(evidence.get("version")) is int and evidence["version"] == 1
                         and isinstance(evidence.get("snapshot"), dict),
                         "qa_scan_context_unavailable", "Advertised scan context must retain its actual fresh after-guard.")
            else:
                await self.client.call("client.snapshot", {})
            if value.get("complete") is True:
                if "cursor" in arguments:
                    await self.discover_scan()
                self._scan_health = None
            self.phases.append({"phase": self.phase, "status": "completed", "elapsed_ms": int((time.monotonic() - before) * 1000)})
            return value
        except (BridgeError, _QaFailure):
            self._scan_health = None
            self.phases.append({"phase": self.phase, "status": "failed", "elapsed_ms": int((time.monotonic() - before) * 1000)})
            raise

    def snapshot_dimension(self, value):
        world = value.get("world", {})
        dimension = world.get("dimension") if isinstance(world, dict) else None
        remote=isinstance(world,dict) and world.get("singleplayer") is False
        bridge=value.get("serverBridge",{})
        if remote:
            _require(isinstance(bridge,dict) and bridge.get("ready") is True and bridge.get("qaExecutionSupported") is True
                     and isinstance(bridge.get("connectionId"),str) and bool(bridge["connectionId"])
                     and bridge.get("dimension")==dimension and world.get("identitySource")=="remote_server_handshake",
                     "qa_context_changed","Remote QA requires the live authenticated operator bridge and authoritative server identity.")
            binding={"sessionId":self.args["expectedSessionId"],"connectionId":bridge["connectionId"],"dimension":dimension}
            _require(self.client.remote_binding in (None,binding),"qa_context_changed","Remote QA server connection changed.")
            self.client.remote_binding=binding; self.observation_source="remote_server_tick"; self.flight_source="remote_server_tick"
        else:
            _require(self.client.remote_binding is None,"qa_context_changed","Remote QA cannot silently switch to a local world.")
        _require(value.get("sessionId") == self.args["expectedSessionId"] and value.get("profile") == "developer"
                 and value.get("worldLoaded") is True and value.get("paused") is False
                 and isinstance(world, dict) and _integer(dimension, -2_147_483_648, 2_147_483_647)
                 and (world.get("singleplayer") is True or remote)
                 and self.args["expectedWorld"] in (world.get("worldName"), world.get("worldFolder"))
                 and (self.dimension is None or dimension == self.dimension),
                 "qa_context_changed", "QA requires the pinned live developer session/world, unpaused integrated server and fresh matching server-player dimension.")
        return dimension

    def snapshot(self, value):
        dimension = self.snapshot_dimension(value)
        player = value.get("player", {})
        flight = player.get("serverFlight", {}) if isinstance(player, dict) else {}
        _require(isinstance(flight, dict) and flight.get("available") is True
                 and flight.get("source") == self.flight_source
                 and type(flight.get("dimension")) is int and flight["dimension"] == dimension
                 and type(flight.get("ageMillis")) in (int, float)
                 and 0 <= flight["ageMillis"] <= 1_000 and math.isfinite(flight["ageMillis"]),
                 "qa_context_changed", "QA requires the pinned live developer session/world, unpaused integrated server and fresh matching server-player dimension.")
        self.dimension = dimension

    async def initial_snapshot(self, value, read_snapshot, started):
        dimension = self.snapshot_dimension(value)
        try:
            self.snapshot(value)
            return value
        except _QaFailure:
            # Only the first read can await a missing/stale sample. Pin the
            # already valid client dimension before observing any later read.
            self.dimension = dimension
        deadline = min(started + .250, self.deadline, self.started + WHOLE_BUDGET_MS / 1000)
        evidence = {"waited": False, "maxWait_ms": 250, "interval_ms": 50, "snapshotReads": 1,
                    "worldTickAdvanceVerified": False}
        self.preflight["initialReadiness"] = evidence
        baseline = previous = None
        message = "Initial QA readiness requires a fresh matching server-player sample and a real advancing world tick within 250ms."
        try:
            with anyio.fail_after(max(.001, deadline - time.monotonic())):
                while True:
                    self.snapshot_dimension(value)
                    player = value.get("player")
                    flight = player.get("serverFlight") if isinstance(player, dict) else None
                    _require(isinstance(flight, dict) and type(flight.get("available")) is bool,
                             "qa_context_changed", message)
                    available = flight["available"]
                    for key in ("source", "dimension", "ageMillis", "worldTick"):
                        _require(not available or key in flight, "qa_context_changed", message)
                    _require("source" not in flight or flight["source"] == self.flight_source,
                             "qa_context_changed", message)
                    _require("dimension" not in flight or type(flight["dimension"]) is int
                             and flight["dimension"] == self.dimension, "qa_context_changed", message)
                    age = flight.get("ageMillis")
                    try:
                        valid_age = type(age) in (int, float) and age >= 0 and math.isfinite(age)
                    except OverflowError:
                        valid_age = False
                    _require("ageMillis" not in flight or valid_age, "qa_context_changed", message)
                    tick = flight.get("worldTick")
                    _require("worldTick" not in flight or _integer(tick, 0, LONG_MAX), "qa_context_changed", message)
                    if tick is not None:
                        _require(previous is None or tick >= previous, "qa_context_changed", message)
                        previous = tick
                        if baseline is None:
                            baseline = tick
                            evidence["baselineWorldTick"] = tick
                    _require(time.monotonic() < deadline, "qa_context_changed", message)
                    if available and valid_age and age <= 1_000 and tick > baseline:
                        self.snapshot(value)
                        evidence.update(worldTickAdvanceVerified=True, readyWorldTick=tick)
                        return value
                    evidence["waited"] = True
                    await anyio.sleep(min(.050, max(0, deadline - time.monotonic())))
                    _require(time.monotonic() < deadline, "qa_context_changed", message)
                    value = await read_snapshot()
                    evidence["snapshotReads"] += 1
        except TimeoutError:
            raise _QaFailure("qa_context_changed", message) from None
        finally:
            evidence["elapsed_ms"] = (time.monotonic() - started) * 1000

    def qa_context(self, value):
        if self.dimension == 0:
            # Old public native DTOs have world.dimension, without a top-level
            # dimension or provider. Keep that contract while rejecting drift.
            if "dimension" in value:
                _require(type(value["dimension"]) is int and value["dimension"] == 0,
                         "qa_context_changed", "The native QA DTO changed the pinned overworld dimension.")
        else:
            _require(self.dimension is not None and value.get("source") == PRIVATE_SOURCE
                     and type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
                     and value.get("nativeRunnerContract") == PRIVATE_CONTRACT
                     and type(value.get("dimension")) is int and value["dimension"] == self.dimension
                     and value.get("providerClass") == PRIVATE_PROVIDER
                     and value.get("sessionId") == self.args["expectedSessionId"],
                     "qa_private_route_unavailable", "Nonzero QA requires the public instance adapter in the pinned real PersonalDimension, provider and session.", unsupported=True)
            self.provider_class = PRIVATE_PROVIDER
            _require(("owned" not in value or value["owned"] is False)
                     and ("nativeRunOwned" not in value or value["nativeRunOwned"] is False),
                     "qa_native_ownership_invalid", "Public-instance observations cannot promote ownership of HQA's native global runner.")
            if "nativeReport" in value:
                _require(isinstance(value["nativeReport"], dict), "qa_private_route_unavailable",
                         "The private result report must retain a complete actual-context object.")
                self.qa_context(value["nativeReport"])
                _require(value["nativeReport"].get("runId") == value.get("runId"), "qa_private_route_unavailable",
                         "Private status and result reports must identify the same actual public UUID run.")
        if "world" in value:
            world = value["world"]
            _require(isinstance(world, dict) and type(world.get("dimension")) is int
                     and world["dimension"] == self.dimension
                     and (self.dimension == 0 or world.get("providerClass") == self.provider_class),
                     "qa_context_changed", "Every QA world DTO must retain the actual pinned dimension and private provider.")

    def config(self, value):
        config = value.get("runnerConfig", {})
        _require(isinstance(config, dict), "qa_runner_configuration_unsupported",
                 "The actual public runner configuration must remain a complete object.", unsupported=True)
        if self.dimension != 0:
            origin = config.get("defaultOrigin")
            _require(value.get("available") is True and value.get("nativeRunnerContract") == PRIVATE_CONTRACT
                     and value.get("normalWorldTicks") is True and value.get("ownsHqaNativeRunner") is False
                     and value.get("usesPublicInstanceLifecycle") is True and value.get("privateDimension") is True
                     and value.get("fixturePlanAvailable") is True and value.get("preClear") is False
                     and type(value.get("minimumClearY")) is int and value["minimumClearY"] == 2
                     and value.get("clearRequiresOwnedUnchangedFixture") is True
                     and value.get("active") is False and value.get("mode") == "normal_world" and value.get("turbo") is False
                     and isinstance(config, dict) and config.get("source") == PRIVATE_SOURCE
                     and config.get("mode") == "normal_world" and config.get("turbo") is False and config.get("active") is False
                     and type(config.get("maxSelectedTests")) is int and config["maxSelectedTests"] == 10
                     and isinstance(origin, dict) and set(origin) == set("xyz")
                     and all(type(origin.get(axis)) is int for axis in "xyz") and origin["y"] >= 3
                     and value.get("defaultOrigin") == origin,
                     "qa_runner_configuration_unsupported", "A complete idle public-instance runner in the actual private world with real normal ticks and its exact default origin is required.", unsupported=True)
            maximum = value.get("maxStatusWaitMillis")
            self._status_wait_max_ms = maximum if value.get("boundedStatusWait") is True \
                and value.get("statusWaitPrivateOnly") is True and type(maximum) is int and 1 <= maximum <= 8_000 else 0
            return origin
        self._status_wait_max_ms = 0
        _require(config.get("available") is True and config.get("partial") is False
                 and all(type(config.get("gridOrigin" + axis)) is int for axis in "XYZ")
                 and config.get("mode") == "interactive" and type(config.get("turboMultiplier")) is int
                 and config["turboMultiplier"] == 1 and config.get("turboActive") is False
                 and config.get("nativeBatchActive") is False,
                 "qa_runner_configuration_unsupported", "A complete idle public interactive runner with turbo multiplier 1 is required.", unsupported=True)
        return {axis.lower(): config["gridOrigin" + axis] for axis in "XYZ"}

    async def prepare(self):
        self.client.initial_snapshot_guard = self.initial_snapshot
        await self.workflow("qa_preflight", [
            _read("snapshot", "mcb_snapshot"),
            _read("capabilities", "mcb_qa_catalog", {"section": "capabilities"}),
            _read("tests", "mcb_qa_catalog", {"section": "tests", "query": self.args["selector"], "offset": 0, "limit": 64}),
            _read("player", "mcb_inventory"),
        ])
        if self._native_workflow_runner:
            from .workflows import _workflow_discovery
            # The complete first preflight already validated this actual health
            # response. Retain immutable capabilities, never live world truth.
            self._workflow_discovery = _workflow_discovery(self.client, self.client.credentials,
                self.client.latest[("bridge.health", "")])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        default_origin = self.config(self.client.latest[("qa.catalog", "capabilities")])
        origin = default_origin
        fixture_args = {"section": "fixture_plan", "query": self.args["selector"]}
        if "origin" in self.args:
            _require(self.dimension != 0, "qa_origin_unsupported",
                     "An explicit QA allocation origin is supported only by the verified private PersonalDimension runner.", unsupported=True)
            requested = self.args["origin"]
            _require(isinstance(requested, dict) and set(requested) == set("xyz")
                     and _integer(requested.get("x"), -30_000_000, 29_999_999)
                     and _integer(requested.get("y"), 3, 255)
                     and _integer(requested.get("z"), -30_000_000, 29_999_999),
                     "qa_origin_invalid", "The explicit private allocation origin needs complete bounded typed integer coordinates.", unsupported=True)
            self.requested_origin = dict(requested)
            origin = self.requested_origin
            fixture_args["origin"] = dict(origin)
            self.preflight["requestedOrigin"] = dict(origin)
        _inventory(self.client.latest[("inventory.inspect", "")])
        catalog = self.client.latest[("qa.catalog", "tests")]
        entries = catalog.get("entries")
        _require((catalog.get("available") is True if self.dimension == 0 else catalog.get("ok") is True)
                 and catalog.get("hasMore") is False and isinstance(entries, list),
                 "qa_catalog_incomplete", "The fresh exact-selector catalog page must be complete.", unsupported=True)
        if self.dimension != 0:
            _require(catalog.get("section") == "tests" and type(catalog.get("offset")) is int and catalog["offset"] == 0
                     and type(catalog.get("total")) is int and catalog["total"] == len(entries),
                     "qa_catalog_incomplete", "The private exact-selector catalog must retain its complete typed page count and offset.", unsupported=True)
        matches = [entry for entry in entries if isinstance(entry, dict) and entry.get("id") == self.args["selector"]]
        _require(len(matches) == 1, "qa_exact_test_required", "Only one exact ID from the fresh runtime test catalog may be submitted.", unsupported=True)
        self.case = matches[0]
        self.validate_case_definition()
        data = await self.read("qa.catalog", "mcb_qa_catalog", fixture_args, section="fixture_plan")
        self.plan = self.validate_plan(data.get("publicFixturePlan"), origin)
        _require(self.plan["requestedVolume"] * 2 <= self.args.get("maxScanCells", 262_144),
                 "qa_fixture_volume_budget", "Complete before and after observations must fit the total declared scan-cell budget.", unsupported=True)
        self.preflight["fixturePlan"] = dict(self.plan)
        await self.empty_space(self.plan["reservedBounds"])
        # The public plan and runner are reread after the bounded space observations.
        await self.workflow("qa_before_run", [
            _read("snapshot", "mcb_snapshot"),
            _read("capabilities", "mcb_qa_catalog", {"section": "capabilities"}),
            _read("fixture", "mcb_qa_catalog", fixture_args),
            _read("player", "mcb_inventory"),
            _read("flow", "mcb_qa_catalog", {"section": "flow_evidence", "query": self.args["selector"]}),
        ])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        fresh_origin = self.config(self.client.latest[("qa.catalog", "capabilities")])
        if self.requested_origin is not None:
            _require(fresh_origin == default_origin, "qa_fixture_plan_changed",
                     "The actual runner default allocation configuration changed during explicit-origin preparation.")
            fresh_origin = self.requested_origin
        fresh = self.validate_plan(self.client.latest[("qa.catalog", "fixture_plan")].get("publicFixturePlan"), fresh_origin)
        keys = ("testId", "template", "rotation", "allocationOrigin", "clearBounds", "reservedBounds", "nativeRunnerContract", "dimension")
        if self.dimension != 0:
            keys += ("providerClass", "sessionId")
        _require(all(fresh[key] == self.plan[key] for key in keys), "qa_fixture_plan_changed", "The actual singleton fixture allocation changed before submission.")
        _inventory(self.client.latest[("inventory.inspect", "")])
        self.flow_before = self.client.latest[("qa.catalog", "flow_evidence")].get("flowEvidence")
        if self.dimension != 0:
            _require(isinstance(self.flow_before, dict) and type(self.flow_before.get("schemaVersion")) is int
                     and self.flow_before["schemaVersion"] in (1, 2)
                     and (self.flow_before["schemaVersion"] == 1 or self.flow_before.get("flowContract") == ITEM_CHANCE_CONTRACT) and self.flow_before.get("caseId") == self.args["selector"]
                     and type(self.flow_before.get("available")) is bool
                     and type(self.flow_before.get("dimension")) is int and self.flow_before["dimension"] == self.dimension
                     and self.flow_before.get("providerClass") == self.provider_class
                     and self.flow_before.get("sessionId") == self.args["expectedSessionId"],
                     "qa_flow_baseline_unavailable", "The private before-record must explicitly identify this real case/dimension/provider/session before submission.", unsupported=True)

    def validate_case_definition(self):
        _require(self.case.get("caseType") != "infiniteDebug", "qa_infinite_debug_outside_finite_scope",
                 "Infinite debug cases use their separate runner path and cannot enter finite stopped-flow acceptance.", unsupported=True)
        _require(isinstance(self.case.get("template"), str)
                 and self.case.get("parameterized") is False and self.case.get("skippedAtDiscovery") is False
                 and (self.dimension == 0 or self.case.get("caseType") == "finite" and type(self.case.get("rotation")) is int),
                 "qa_single_definition_required", "Parameterized or discovery-skipped definitions are outside singleton QA scope.", unsupported=True)

    def validate_plan(self, plan, origin):
        private = self.dimension != 0
        _require(isinstance(plan, dict) and type(plan.get("schemaVersion")) is int and plan["schemaVersion"] == 1
                 and plan.get("complete") is True and plan.get("clearEnvelopeComplete") is True
                 and plan.get("source") == (PRIVATE_SOURCE if private else PLAN_SOURCE)
                 and plan.get("nativeRunnerContract") == (PRIVATE_CONTRACT if private else RUNNER_CONTRACT)
                 and plan.get("geometry") == ("exact_adapter_reserved_bounds" if private else "exact_native_clear_bounds")
                 and type(plan.get("dimension")) is int and plan["dimension"] == self.dimension
                 and plan.get("testId") == self.args["selector"] and plan.get("template") == self.case["template"]
                 and isinstance(plan.get("allocationOrigin"), dict) and set(plan["allocationOrigin"]) == set("xyz")
                 and all(type(plan["allocationOrigin"].get(axis)) is int for axis in "xyz")
                 and plan["allocationOrigin"] == origin and type(plan.get("rotation")) is int
                 and plan.get("callbackWritesBounded") is False,
                 "qa_fixture_plan_unavailable", "Fresh version-pinned public-loader fixture bounds in the actual dimension are required; callback writes remain explicitly unknown.", unsupported=True)
        if private:
            _require(plan.get("available") is True and plan.get("providerClass") == self.provider_class
                     and plan.get("sessionId") == self.args["expectedSessionId"] and plan["rotation"] == self.case["rotation"]
                     and type(plan.get("margin")) is int and plan["margin"] == 1
                     and plan.get("preClear") is False and plan.get("requiresFreshAirPreflight") is True
                     and type(plan.get("protectsGroundY")) is int and plan["protectsGroundY"] == 1,
                     "qa_fixture_plan_unavailable", "Private preparation must retain the verified provider/session, fresh air requirement and protected Y=1 ground.", unsupported=True)
        else:
            _require(plan.get("available") is True and plan.get("scope") == "native_singleton_preparation_only"
                     and plan.get("nativeBoundsPubliclyAvailable") is False,
                     "qa_fixture_plan_unavailable", "Native preparation must retain its audited singleton scope and explicitly unavailable native public bounds.", unsupported=True)
        bounds = plan.get("reservedBounds")
        _require(isinstance(bounds, dict) and set(bounds) == {prefix + axis for prefix in ("min", "max") for axis in "XYZ"}
                 and all(type(value) is int for value in bounds.values()) and plan.get("clearBounds") == bounds,
                 "qa_fixture_bounds_invalid", "The complete inclusive singleton clear envelope must retain typed uncut coordinates.", unsupported=True)
        validate_scan_region({"bounds": bounds})
        size = plan.get("placedSize")
        _require(isinstance(size, dict) and set(size) == set("xyz") and all(_integer(size.get(axis), 0, 263) for axis in "xyz"),
                 "qa_fixture_bounds_invalid", "The installed public template must retain complete typed rotated dimensions.", unsupported=True)
        if private:
            _require(all(size[axis] >= 1 for axis in "xyz"), "qa_fixture_bounds_invalid",
                     "Private fixture dimensions must describe a nonempty actual loaded template.", unsupported=True)
        cell = {axis: size[axis.lower()] if private else max(5, size[axis.lower()]) for axis in "XYZ"}
        expected_clear = {prefix + axis: origin[axis.lower()] + (-1 if prefix == "min" else cell[axis]) if private
                          else origin[axis.lower()] + (-3 if prefix == "min" else cell[axis] + 2)
                          for prefix in ("min", "max") for axis in "XYZ"}
        if private:
            expected_clear["minY"] = max(2, expected_clear["minY"])
        expected_cell = {prefix + axis: origin[axis.lower()] + (0 if prefix == "min" else cell[axis] - 1)
                         for prefix in ("min", "max") for axis in "XYZ"}
        _require(bounds == expected_clear and plan.get("cellBounds") == expected_cell,
                 "qa_fixture_bounds_invalid", "The complete geometry must equal the audited dimension-specific runner contract.", unsupported=True)
        volume = math.prod(bounds["max" + axis] - bounds["min" + axis] + 1 for axis in "XYZ")
        _require(type(plan.get("requestedVolume")) is int and plan["requestedVolume"] == volume
                 and volume <= self.args.get("maxScanCells", 262_144),
                 "qa_fixture_volume_budget", "The actual complete fixture envelope must fit the declared bounded scan volume.", unsupported=True)
        return plan

    async def empty_space(self, bounds):
        await self.scan(bounds, require_air=True)
        tile_queries = 0
        for x in range(bounds["minX"], bounds["maxX"] + 1, 64):
            for y in range(bounds["minY"], bounds["maxY"] + 1, 64):
                for z in range(bounds["minZ"], bounds["maxZ"] + 1, 64):
                    lows = {"x": x, "y": y, "z": z}
                    highs = {axis: min(lows[axis] + 63, bounds["max" + axis.upper()]) for axis in "xyz"}
                    center = {axis: (lows[axis] + highs[axis]) // 2 for axis in "xyz"}
                    radius = max(1, math.ceil(math.sqrt(sum(max(abs(lows[axis] - center[axis]), abs(highs[axis] - center[axis])) ** 2 for axis in "xyz"))))
                    await self.empty_tiles(bounds, center, radius)
                    tile_queries += 1
        await self.empty_entities(bounds)
        self.preflight.update(spaceVerified=True, completeLoadedLiteralAir=True, scannedCells=self.plan["requestedVolume"],
                              tileQueries=tile_queries, residualTilesAbsent=True, intersectingEntitiesAbsent=True)

    async def scan(self, bounds, *, require_air):
        self._scan_health = None
        _require(self.client.latest.get(("bridge.health", ""), {}).get("worldScanMinecraftAir") is True,
                 "qa_literal_air_unavailable", "Current health must advertise the typed literal minecraft:air scan contract.", unsupported=True)
        volume = math.prod(bounds["max" + axis] - bounds["min" + axis] + 1 for axis in "XYZ")
        _require(self.scan_cells + volume <= self.args.get("maxScanCells", 262_144),
                 "qa_fixture_volume_budget", "The total exact before/after cell-read budget was exhausted.")
        request = {"dimension": self.dimension, "bounds": bounds, "limit": 512, "airSemantics": "minecraft_air"}
        previous, seen, zero = 0, set(), 0
        previous_counts = {key: 0 for key in ("air", "nonAir", "unloaded", "loaded")}
        positions, identities = set(), Counter()
        while True:
            data = await self.read("world.scan", "mcb_world_scan", request)
            coverage, page = data.get("coverage", {}), data.get("page", {})
            examined = coverage.get("examined")
            _require(data.get("source") == self.observation_source and type(data.get("dimension")) is int and data["dimension"] == self.dimension
                     and data.get("bounds") == bounds and data.get("outOfWorld") == 0
                     and data.get("requestedVolume") == volume and data.get("scannableVolume") == volume
                     and data.get("airSemantics") == "minecraft_air" and data.get("nonAtomic") is True
                     and type(data.get("complete")) is bool
                     and _integer(examined, previous, volume) and all(_integer(coverage.get(key), 0, volume)
                     for key in ("air", "nonAir", "unloaded", "loaded", "remaining"))
                     and coverage["air"] + coverage["nonAir"] + coverage["unloaded"] == examined
                     and coverage["loaded"] == examined - coverage["unloaded"] and coverage["remaining"] == volume - examined
                     and all(_integer(page.get(key), 0, volume) for key in (*previous_counts, "examined", "startOffset", "endOffsetExclusive"))
                     and all(page[key] == coverage[key] - previous_counts[key] for key in previous_counts)
                     and page["loaded"] == page["examined"] - page["unloaded"]
                     and page.get("startOffset") == previous and page.get("endOffsetExclusive") == examined
                     and page.get("examined") == examined - previous
                     and isinstance(data.get("blocks"), list) and len(data["blocks"]) <= 512
                     and page.get("nonAir") == len(data["blocks"])
                     and data.get("requestedRegionFullyObserved") is (data["complete"] and coverage["unloaded"] == 0),
                     "qa_air_coverage_invalid", "Literal-air pages must cover every cell of the exact loaded fixture envelope without clipping or gaps.")
            _require(coverage["unloaded"] == 0, "qa_space_unloaded", "Unloaded cells remain unknown; QA is never used to load or clear them.", unsupported=True)
            for row in data["blocks"]:
                _require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in "xyz")
                         and _inside(row, bounds) and isinstance(row.get("registryName"), str)
                         and row["registryName"] != "minecraft:air" and type(row.get("metadata")) is int,
                         "qa_scan_identity_invalid", "Every non-air row needs its actual exact block coordinate and registry/metadata identity.")
                point = tuple(row[axis] for axis in "xyz")
                _require(point not in positions, "qa_scan_duplicate_identity", "The exact scan repeated a non-air coordinate.")
                positions.add(point)
                identities[(row["registryName"], row["metadata"])] += 1
            self.scan_cells += examined - previous
            if require_air:
                _require(not data["blocks"] and coverage["nonAir"] == 0 and coverage["air"] == examined,
                         "qa_space_occupied", "An actual non-air fixture cell blocks native QA clearing.")
            if data["complete"]:
                _require(examined == volume and data.get("nextCursor") is None and len(positions) == coverage["nonAir"],
                         "qa_air_suffix_missing", "Complete exact block proof cannot omit a suffix.")
                return {"bounds": bounds, "complete": True, "requestedRegionFullyObserved": True,
                        "dimension": self.dimension,
                        "scannedCells": volume, "coverage": coverage, "airSemantics": "minecraft_air",
                        "nonAirIdentities": [{"registryName": key[0], "metadata": key[1], "blocks": amount}
                                             for key, amount in sorted(identities.items())],
                        "scope": "native_singleton_clear_envelope" if self.dimension == 0 else "adapter_reserved_fixture_envelope", "callbackWritesBounded": False,
                        "callbackScopeUnknown": True}
            cursor = data.get("nextCursor")
            _require(isinstance(cursor, str) and cursor and cursor not in seen, "qa_scan_cursor_invalid", "Only a new native scan cursor may be consumed once.")
            seen.add(cursor)
            zero = zero + 1 if examined == previous else 0
            _require(zero <= 4, "qa_scan_no_progress", "The bounded native air scan did not make progress.")
            if zero:
                await anyio.sleep(.05)
            previous = examined
            previous_counts = {key: coverage[key] for key in previous_counts}
            request = {"cursor": cursor, "limit": 512}

    async def empty_tiles(self, bounds, center, radius):
        offset, seen, retained, total = 0, set(), 0, None
        while True:
            data = await self.read("world.tiles", "mcb_world_tiles", {**center, "dimension": self.dimension, "radius": radius, "offset": offset, "limit": 128})
            rows = data.get("tiles")
            _require(data.get("source") == self.observation_source and type(data.get("dimension")) is int and data["dimension"] == self.dimension
                     and all(data.get(axis) == value for axis, value in center.items()) and data.get("shape") == "sphere"
                     and data.get("radius") == radius and data.get("totalMatchedIsExact") is True
                     and data.get("candidateLimitReached") is False and isinstance(rows, list)
                     and _integer(data.get("totalMatched")) and type(data.get("hasMore")) is bool
                     and data.get("truncated") is data["hasMore"],
                     "qa_tile_coverage_invalid", "The native tile-map observation must cover the entire declared loaded sphere without a candidate cap.")
            total = data["totalMatched"] if total is None else total
            _require(total == data["totalMatched"], "qa_tile_page_drift", "Tile pagination changed its complete match count.")
            for row in rows:
                _require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in "xyz"), "qa_tile_identity_unknown", "Every actual tile needs typed coordinates.")
                point = tuple(row[axis] for axis in "xyz")
                _require(point not in seen, "qa_tile_page_duplicate", "A tile page repeated an already observed coordinate.")
                seen.add(point)
                _require(not _inside(row, bounds), "qa_residual_tile_present", "A residual actual tile inside the air envelope blocks QA clearing.")
            retained += len(rows)
            if not data["hasMore"]:
                _require(data.get("nextOffset") is None and retained == total, "qa_tile_suffix_missing", "Complete tile coverage cannot omit a page suffix.")
                return
            next_offset = data.get("nextOffset")
            _require(_integer(next_offset, offset + 1) and next_offset == offset + len(rows), "qa_tile_offset_invalid", "Only the returned advancing tile offset may be used.")
            offset = next_offset

    async def empty_entities(self, bounds):
        center = {axis: (bounds["min" + axis.upper()] + bounds["max" + axis.upper()]) // 2 for axis in "xyz"}
        horizontal = max(abs(bounds[prefix + axis] + (1 if prefix == "max" else 0) - center[axis.lower()]) for prefix in ("min", "max") for axis in "XZ")
        vertical = max(abs(bounds[prefix + "Y"] + (1 if prefix == "max" else 0) - center["y"]) for prefix in ("min", "max"))
        chunks, vertical_chunks = max(1, math.ceil(horizontal / 16)), max(1, math.ceil(vertical / 16))
        _require(chunks <= 8 and vertical_chunks <= 8, "qa_entity_region_unsupported", "The full clear envelope exceeds the bounded native entity observation region.", unsupported=True)
        offset, offsets, loaded = 0, set(), None
        query_bounds = {prefix + axis: center[axis.lower()] + (-1 if prefix == "min" else 1) *
                        (chunks * 16 if axis in "XZ" else vertical_chunks * 16) + (1 if prefix == "max" else 0)
                        for prefix in ("min", "max") for axis in "XYZ"}
        while True:
            data = await self.read("world.entities", "mcb_entities", {**center, "dimension": self.dimension,
                "radiusChunks": chunks, "verticalRadiusChunks": vertical_chunks, "offset": offset, "limit": 128,
                "intersectBounds": True})
            rows, scope = data.get("entities"), data.get("bounds", {})
            _require(data.get("source") == self.observation_source and type(data.get("dimension")) is int and data["dimension"] == self.dimension
                     and data.get("center") == center and scope.get("radiusBlocks") == chunks * 16
                     and scope.get("verticalRadiusBlocks") == vertical_chunks * 16 and data.get("offset") == offset
                     and isinstance(rows, list) and _integer(data.get("loadedCandidates"))
                     and _integer(data.get("candidatesVisited"), 0, data["loadedCandidates"])
                     and type(data.get("hasMore")) is bool and isinstance(data.get("coverage"), dict)
                     and data.get("boundsMode") == "intersection" and data.get("queryBounds") == query_bounds
                     and type(data.get("unknownBoundsCandidates")) is int
                     and data["unknownBoundsCandidates"] == 0
                     and str(data["coverage"].get("source", "")).startswith("Current loadedEntityList"),
                     "qa_entity_coverage_invalid", "The current loaded-entity candidate page must retain the full declared region and native candidate offsets.")
            loaded = data["loadedCandidates"] if loaded is None else loaded
            _require(loaded == data["loadedCandidates"], "qa_entity_page_drift", "The native loaded candidate count changed across pages.")
            for row in rows:
                box = row.get("bounds") if isinstance(row, dict) else None
                _require(isinstance(row, dict) and row.get("boundsStatus") == "intersecting"
                    and isinstance(box, dict) and all(type(box.get(prefix + axis)) in (int, float)
                    and math.isfinite(box[prefix + axis]) for prefix in ("min", "max") for axis in "XYZ")
                    and all(box["min" + axis] < box["max" + axis] for axis in "XYZ"),
                    "qa_entity_bounds_unknown", "Absent or invalid actual entity bounds remain unknown.", unsupported=True)
                _require(not _intersects(box, bounds), "qa_entity_intersects_fixture", "An actual entity intersects a cleared block volume; block maxima are inclusive.")
            if not data["hasMore"]:
                _require(data.get("nextOffset") is None and offset + data["candidatesVisited"] == loaded,
                         "qa_entity_suffix_missing", "The complete entity page cannot omit any loaded candidate suffix.")
                return
            next_offset = data.get("nextOffset")
            _require(_integer(next_offset, offset + 1, loaded) and next_offset not in offsets
                     and offset + data["candidatesVisited"] - 1 <= next_offset <= offset + data["candidatesVisited"],
                     "qa_entity_offset_invalid", "Offsets advance by consumed loaded candidates; a final visited candidate excluded by the native byte budget may remain for the next page.")
            offsets.add(next_offset)
            offset = next_offset

    async def execute(self):
        # A status attempt also needs a fresh standalone snapshot. Leave enough
        # RPCs for both report sections, player/Flow reads, the full after scan,
        # and final report coherence. Empty before pages do not predict the
        # density of the placed fixture: multi-page tasks preserve spare RPCs
        # for after-observation instead of spending them on fast status polls.
        scan_pages = self.client.operation_counts["world.scan"]
        reserve = max(32, 26 + 4 * scan_pages)
        remaining_requests = self.client.maximum - self.client.calls
        poll_slots = (remaining_requests - reserve - 10) // 2
        late_collection = self.deadline > self.started + WHOLE_BUDGET_MS / 1000
        if scan_pages > 1:
            # Diagnostic collection gets the same finite ten-second poll
            # budget, even if the acceptance window has already ended. A
            # longer deadline changes spacing, never the number of polls.
            poll_window_ms = max(1, min(self.remaining(), WHOLE_BUDGET_MS if late_collection else
                math.ceil((self.started + WHOLE_BUDGET_MS / 1000 - time.monotonic()) * 1000)))
            poll_slots = min(poll_slots, math.ceil(poll_window_ms / 500) + 2)
            reserve = max(reserve, remaining_requests - 10 - 2 * poll_slots)
        if late_collection:
            # Keep an additional complete guarded report-coherence read after
            # the exact submission and diagnostic wait use separate segments,
            # plus two RPCs when the terminal read uses the final finite slot.
            reserve += 6
            poll_slots = min(poll_slots, (remaining_requests - reserve - 10) // 2)
        self.polling = {"reservedAfterRequests": reserve, "beforeScanPages": scan_pages,
                        "remainingRequestsBeforeRun": remaining_requests, "setupRequestAllowance": 10,
                        "statusPollRequestCost": 2, "statusPollAllowance": max(0, poll_slots),
                        "statusPolls": 0}
        _require(poll_slots >= 2, "qa_request_budget_insufficient",
                 "The remaining finite request budget cannot cover one submission, both guarded waits, reports and after-observation.")
        interval = min(500, max(100, math.ceil(self.remaining() / max(1, poll_slots - 2))))
        self.polling["interval_ms"] = interval
        binding = {"runId": {"step": "run", "path": "runId"}}
        status_before = self.client.operation_counts["qa.status"]
        self.client.request_reserve = reserve
        try:
            submission = self.submission()
            if late_collection:
                await self.workflow("qa_submit_exact_run", [submission])
                await self.wait_diagnostic_completion(reserve, poll_slots, interval)
            elif self._native_workflow_runner and self._workflow_discovery is not None:
                await self.workflow("qa_submit_exact_run", [submission])
                await self.wait_completion(poll_slots, interval)
            else:
                await self.workflow("qa_run_and_wait", [submission,
                {"id": "fresh", "kind": "wait", "tool": "mcb_qa_status", "bind": binding,
                 "condition": {"path": "freshSinceStart", "operator": "equals", "value": True}, "interval_ms": interval, "fields": STATUS_FIELDS},
                {"id": "idle", "kind": "wait", "tool": "mcb_qa_status", "bind": binding,
                 "condition": {"path": "nativeBatchActive", "operator": "equals", "value": False}, "interval_ms": interval, "fields": STATUS_FIELDS},
                ])
        finally:
            self.client.request_reserve = 0
            self.polling["statusPolls"] = self.client.operation_counts["qa.status"] - status_before
        self.terminal = dict(self.client.latest[("qa.status", "")])

    def submission(self):
        step = {"id": "run", "kind": "action", "tool": "mcb_qa_command",
            "args": {"expectedWorld": self.args["expectedWorld"], "command": "run", "args": [self.args["selector"]]},
            "fields": ["runId", "selector", "selectedTests", "selectedIds", "selectedIdsTruncated", "startStatus", "state", "correlation", "owned"]}
        if self.requested_origin is not None:
            step["tool"] = "mcb_qa_run"
            step["args"] = {"expectedWorld": self.args["expectedWorld"], "selector": self.args["selector"],
                            "origin": dict(self.requested_origin)}
        return step

    def validate_run_ticket(self, value):
        _require(isinstance(value.get("runId"), str) and bool(value["runId"]),
                 "qa_run_ticket_unavailable", "The exact submission must retain its explicit run ticket; no action is replayed.")
        _require(value.get("selector") == self.args["selector"] and type(value.get("selectedTests")) is int
                 and value["selectedTests"] == 1 and value.get("selectedIds") == [self.args["selector"]]
                 and value.get("selectedIdsTruncated") is False,
                 "qa_selection_changed", "The submitted native ticket must select exactly the requested ID before normal waiting continues.")
        if self.dimension != 0:
            try:
                canonical = str(UUID(value["runId"]))
            except (ValueError, AttributeError):
                raise _QaFailure("qa_run_ticket_unavailable", "The private instance adapter must return its actual public UUID run ticket.") from None
            _require(canonical == value["runId"], "qa_run_ticket_unavailable",
                     "The private instance adapter must return its canonical public UUID run ticket.")
            if self.requested_origin is not None:
                fixtures = value.get("fixtures")
                _require(isinstance(fixtures, list) and len(fixtures) == 1,
                         "qa_run_origin_unverified", "The explicit-origin submission must retain one complete actual owned fixture allocation.")
                try:
                    actual = self.validate_plan(fixtures[0], self.requested_origin)
                except _QaFailure as error:
                    raise _QaFailure("qa_run_origin_unverified", "The actual submitted fixture does not verify the requested origin and public plan: " + error.code + ".") from None
                keys = ("testId", "template", "rotation", "allocationOrigin", "placedSize", "cellBounds", "clearBounds",
                        "reservedBounds", "requestedVolume", "nativeRunnerContract", "dimension", "providerClass", "sessionId")
                _require(all(actual[key] == self.plan[key] for key in keys), "qa_run_origin_unverified",
                         "The actual submitted fixture must match the same fresh explicit-origin plan before status waiting.")

    def status_arguments(self):
        arguments = {"runId": self.run_id()}
        # Only a fresh advertised private capability can opt in. Keep 500ms
        # below native dispatch's 8s ceiling and 1500ms for complete after-read.
        maximum = min(self._status_wait_max_ms, 7_500, max(0, self.remaining() - 1_500))
        if self.dimension != 0 and maximum > 0:
            arguments["waitMillis"] = maximum
        return arguments

    async def wait_completion(self, poll_slots, interval):
        # One same-run observation must be both fresh and inactive. Separate
        # successful waits otherwise repeat the final status and its snapshot.
        from .workflows import _guard, _require_capability
        self.phase = "qa_run_and_wait"
        before = time.monotonic()
        phase_status = "failed"
        attempts = 0
        try:
            health = self._workflow_discovery.bind(self.client, self.client.credentials, self.args["expectedSessionId"])
            _require_capability(health, "qa.status")
            with anyio.fail_after(max(.001, self.deadline - time.monotonic())):
                while True:
                    _require(attempts < poll_slots, "qa_request_budget_reserved",
                             "The finite same-run status allowance ended; after-observation requests remain reserved.")
                    value = await self.client.call("qa.status", self.status_arguments())
                    if "sessionId" in value and value["sessionId"] != self.args["expectedSessionId"]:
                        raise BridgeError("SESSION_CHANGED", "An observation came from a different client session.")
                    snapshot = await self.client.call("client.snapshot", {})
                    _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
                    attempts += 1
                    self.validate_run_ticket({**value, "selector": value.get("selector", self.args["selector"])})
                    _require(value.get("runId") == self.run_id() and type(value.get("freshSinceStart")) is bool
                             and type(value.get("nativeBatchActive")) is bool,
                             "qa_terminal_unverified", "Same-run status must retain its typed freshness and active-batch state.")
                    if value["freshSinceStart"] and not value["nativeBatchActive"]:
                        _require(value.get("selectionMatches") is True and value.get("state") in ("completed", "completion_unverified"),
                                 "qa_terminal_unverified", "The fresh inactive native report must keep its exact singleton selection.")
                        phase_status = "completed"
                        return
                    await anyio.sleep(interval / 1000)
        finally:
            self.phases.append({"phase": "qa_run_and_wait", "status": phase_status,
                                "elapsed_ms": int((time.monotonic() - before) * 1000)})

    async def wait_diagnostic_completion(self, reserve, poll_slots, interval):
        # This is the same guarded read path as a workflow wait: immutable
        # capability discovery at entry, pinned credentials on every RPC and
        # a fresh after-snapshot for every same-run status observation.
        from .workflows import _guard, _require_capability
        self.phase = "qa_wait_and_collect"
        before = time.monotonic()
        phase_status = "failed"
        attempts, late_attempts = 0, 0
        whole_end = self.started + WHOLE_BUDGET_MS / 1000
        try:
            health = await self.client.call("bridge.health", {})
            _require(type(health.get("protocolVersion")) is int and health["protocolVersion"] == 1
                     and health.get("sessionId") == self.args["expectedSessionId"] and health.get("profile") == "developer",
                     "qa_context_changed", "Diagnostic waiting must keep the exact developer session.")
            _require_capability(health, "qa.status")
            snapshot = await self.client.call("client.snapshot", {})
            _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
            allowance = min(poll_slots, (self.client.maximum - self.client.calls - reserve) // 2)
            _require(allowance >= 1, "qa_request_budget_reserved", "No same-run diagnostic poll fits while preserving after-observation.")
            fast_allowance = max(1, allowance - max(2, math.ceil(allowance / 4)))
            fast_end = whole_end - interval / 1000
            self.polling.update(statusPollAllowance=allowance, fastStatusPollAllowance=fast_allowance,
                                fastPollWindowEndOffset_ms=WHOLE_BUDGET_MS - interval,
                                diagnosticStatusPollAllowance=allowance - fast_allowance, diagnosticStatusPolls=0,
                                diagnosticFrontIntervalAllowance=2 if allowance - fast_allowance >= 5 else 0,
                                diagnosticFrontIntervals=0, diagnosticAfterTimeReserve_ms=5_000)
            while True:
                _require(attempts < allowance, "qa_request_budget_reserved",
                         "The finite same-run status allowance ended; after-observation requests remain reserved.")
                now = time.monotonic()
                if now < whole_end and attempts >= fast_allowance:
                    await anyio.sleep(whole_end - now)
                    now = time.monotonic()
                late = now >= whole_end
                if late and late_attempts == 0 and self.polling["diagnosticFrontIntervalAllowance"]:
                    # Anchor the short diagnostic probes to the actual native
                    # dispatch, while delaying this first late read by at most
                    # one second beyond the whole acceptance boundary.
                    first_probe = min(whole_end + 1, self.client.submitted_at + WHOLE_BUDGET_MS / 1000)
                    if now < first_probe:
                        await anyio.sleep(first_probe - now)
                value = await self.client.call("qa.status", self.status_arguments())
                if "sessionId" in value and value["sessionId"] != self.args["expectedSessionId"]:
                    raise BridgeError("SESSION_CHANGED", "An observation came from a different client session.")
                snapshot = await self.client.call("client.snapshot", {})
                _guard(snapshot, self.args["expectedSessionId"], "developer", self.args["expectedWorld"], True)
                attempts += 1
                if late:
                    late_attempts += 1
                    self.polling["diagnosticStatusPolls"] = late_attempts
                self.validate_run_ticket({**value, "selector": value.get("selector", self.args["selector"])})
                _require(value.get("runId") == self.run_id() and type(value.get("freshSinceStart")) is bool
                         and type(value.get("nativeBatchActive")) is bool,
                         "qa_terminal_unverified", "Same-run status must retain its typed freshness and active-batch state.")
                if value["freshSinceStart"] and not value["nativeBatchActive"]:
                    _require(value.get("selectionMatches") is True and value.get("state") in ("completed", "completion_unverified"),
                             "qa_terminal_unverified", "The fresh inactive native report must keep its exact singleton selection.")
                    phase_status = "completed"
                    return
                if late:
                    remaining_slots = allowance - attempts
                    # Keep five seconds of the collection deadline for reads;
                    # neither a late completion nor a report changes the
                    # independent ten-second acceptance outcome.
                    pause = max(.5, (self.deadline - time.monotonic() - 5) / max(1, remaining_slots))
                    # Short native runs can finish just after ten seconds.
                    # Probe twice near that boundary only with enough slots
                    # to keep at least two subsequent long-tail observations.
                    if late_attempts <= self.polling["diagnosticFrontIntervalAllowance"] and remaining_slots >= 3:
                        pause = min(1., pause)
                        self.polling["diagnosticFrontIntervals"] += 1
                    self.polling["diagnosticInterval_ms"] = math.ceil(pause * 1000)
                else:
                    # The finite fast allowance must reach the late part of
                    # the whole acceptance window. Spending every slot at the
                    # nominal interval leaves an unobserved gap before ten
                    # seconds, even when the same run completes in that gap.
                    # Keep one nominal interval before the whole boundary;
                    # reports and complete after-observation still count there.
                    pause = max(interval / 1000, (fast_end - time.monotonic()) / max(1, fast_allowance - attempts))
                    self.polling["fastInterval_ms"] = math.ceil(pause * 1000)
                await anyio.sleep(pause)
        finally:
            self.phases.append({"phase": "qa_wait_and_collect", "status": phase_status,
                                "elapsed_ms": int((time.monotonic() - before) * 1000)})

    def run_id(self):
        ticket = self.client.ticket or {}
        value = ticket.get("runId")
        _require(isinstance(value, str) and value, "qa_run_ticket_unavailable", "The one submitted QA action did not yield an explicit run ticket; it is never replayed.")
        return value

    async def read_results_page(self, run_id, section, offset, *, diagnostic=False):
        return await self.read("qa.results", "mcb_qa_results", {"runId": run_id, "section": section,
            "offset": offset, "limit": self.args.get("reportPageSize", 8), "includeDetails": True},
            section=section, diagnostic=diagnostic)

    async def pages(self, section: str, *, diagnostic=False):
        run_id, offset, offsets, entries, total, report = self.run_id(), 0, set(), [], None, None
        while True:
            _require(len(self.client.result_pages) < self.args.get("maxReportPages", 32), "qa_report_page_budget", "The finite complete QA report-page budget was exhausted.")
            data = await self.read_results_page(run_id, section, offset, diagnostic=diagnostic)
            rows = data.get("entries")
            _require(data.get("runId") == run_id and data.get("freshSinceStart") is True
                     and data.get("section") == section and data.get("offset") == offset
                     and _integer(data.get("total")) and isinstance(rows, list) and type(data.get("hasMore")) is bool
                     and data.get("truncated") is data["hasMore"] and isinstance(data.get("nativeReport"), dict),
                     "qa_result_page_invalid", "Every native result page must keep this explicit fresh ticket, report and returned offset.")
            total = data["total"] if total is None else total
            report = data["nativeReport"] if report is None else report
            _require(total == data["total"] and report == data["nativeReport"], "qa_report_changed", "The native-global report changed while reading its bounded pages.")
            if self.terminal is not None:
                _require(report == self.terminal.get("nativeReport"), "qa_report_changed", "Result pages no longer describe the observed terminal report.")
            _require(len(entries) + len(rows) <= total, "qa_result_page_excess", "A result page exceeds its declared complete case/issue count.")
            for row in rows:
                _require(isinstance(row, dict), "qa_result_entry_invalid", "Every native case or issue must remain an object.")
                self.diagnostics_complete &= (row.get("detailsCoverage") == "requested"
                    and row.get("detailsTruncated") is False
                    and all(value is False for key, value in row.items() if key.endswith("Truncated"))
                    and ("outputTotal" not in row or isinstance(row.get("outputLines"), list)
                         and type(row["outputTotal"]) is int and row["outputTotal"] == len(row["outputLines"])))
            entries.extend(rows)
            if section == "cases":
                self.cases = list(entries)
            else:
                self.issues = list(entries)
            if not data["hasMore"]:
                _require(data.get("nextOffset") is None and len(entries) == total, "qa_result_suffix_missing", "A final native result page must contain its complete declared suffix.")
                return entries
            next_offset = data.get("nextOffset")
            _require(_integer(next_offset, offset + 1) and next_offset == offset + len(rows) and next_offset not in offsets,
                     "qa_result_offset_invalid", "Only the returned new result offset may be consumed.")
            offsets.add(next_offset)
            offset = next_offset

    async def diagnostics(self, *, recovery=False):
        self.run_id()
        if recovery or self.terminal is None:
            self.terminal = dict(await self.read("qa.status", "mcb_qa_status", {"runId": self.run_id()}, diagnostic=recovery))
        # No result assertion precedes either section's diagnostic reads.
        errors = []
        for section in ("cases", "issues"):
            try:
                rows = await self.pages(section, diagnostic=recovery)
                if section == "cases":
                    self.cases = rows
                else:
                    self.issues = rows
            except (BridgeError, _QaFailure) as error:
                errors.append({"section": section, "code": getattr(error, "code", "qa_diagnostic_failed"), "message": str(error)[:512]})
        self.diagnostic_errors.extend(errors)
        self.reports_complete = not errors
        self.diagnostics_complete &= self.reports_complete

    def selection_verified(self):
        ticket, status = self.client.ticket or {}, self.terminal or {}
        run_id = ticket.get("runId")
        return (isinstance(run_id, str) and bool(run_id)
                 and ticket.get("selector") == self.args["selector"] and type(ticket.get("selectedTests")) is int and ticket["selectedTests"] == 1
                 and ticket.get("selectedIds") == [self.args["selector"]] and ticket.get("selectedIdsTruncated") is False,
                 status.get("runId") == run_id and status.get("freshSinceStart") is True
                 and status.get("nativeBatchActive") is False and status.get("selectionMatches") is True
                 and type(status.get("selectedTests")) is int and status["selectedTests"] == 1 and status.get("selectedIds") == [self.args["selector"]]
                 and status.get("selectedIdsTruncated") is False
                 and status.get("state") in ("completed", "completion_unverified"))

    def test_passed(self):
        report = (self.terminal or {}).get("nativeReport", {})
        return (all(self.selection_verified()) and self.reports_complete and self.report_coherent and len(self.cases) == 1
                 and self.cases[0].get("id") == self.args["selector"] and self.cases[0].get("status") == "PASSED"
                 and report.get("status") == ("passed" if self.dimension == 0 else "PASS")
                 and (self.dimension == 0 or report.get("complete") is True)
                 and report.get("exitCode") == 0 and type(report.get("exitCode")) is int
                 and type(report.get("selectedTests")) is int and report["selectedTests"] == 1
                 and type(report.get("passed")) is int and report["passed"] == 1
                 and all(type(report.get(key)) is int and report[key] == 0 for key in COUNT_FIELDS)
                 and not self.issues)

    def report_semantics_verified(self):
        """Complete same-run case/report acceptance; bounded diagnostic logs have separate coverage."""
        return self.reports_complete and self.report_coherent and self.test_passed()

    def accept(self):
        selection, terminal = self.selection_verified()
        _require(selection, "qa_selection_changed", "The actual submitted run must select exactly the requested fresh catalog ID.")
        _require(terminal, "qa_terminal_unverified", "The fresh inactive native report must retain the exact singleton selection; ownership is not promoted.")
        _require(self.reports_complete and len(self.cases) == 1 and self.cases[0].get("id") == self.args["selector"],
                 "qa_report_incomplete", "The exact case and all infrastructure issue pages are required before result acceptance.")
        _require(self.report_semantics_verified(), "qa_native_test_failed", "The actual case/report must pass with no failed, skipped, incomplete or infrastructure outcomes.")
        if self.args.get("requireFullFlow", False):
            _require(self.full_flow.get("verified") is True, "qa_full_flow_evidence_unavailable",
                     "Normal machine whole acceptance needs fresh complete actual preparation, execution and receiver-retrieval evidence.")

    async def after(self):
        await self.workflow("qa_after_read", [_read("snapshot", "mcb_snapshot"), _read("player", "mcb_inventory"),
            _read("flow", "mcb_qa_catalog", {"section": "flow_evidence", "query": self.args["selector"]})])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        _inventory(self.client.latest[("inventory.inspect", "")])
        self.after_scan = await self.scan(self.plan["reservedBounds"], require_air=False)
        fresh_status = await self.read("qa.status", "mcb_qa_status", {"runId": self.run_id()})
        self.report_coherent = fresh_status.get("nativeReport") == (self.terminal or {}).get("nativeReport")
        if not self.report_coherent:
            self.diagnostics_complete = False
        _require(self.report_coherent,
                 "qa_report_changed", "The explicit native report changed during complete after-observation.")
        self.terminal = dict(fresh_status)
        self.verify_flow(self.client.latest[("qa.catalog", "flow_evidence")].get("flowEvidence"))

    def verify_flow(self, evidence):
        self.quantitative_summary = None
        self.full_flow = {"available": isinstance(evidence, dict) and evidence.get("available") is True,
                          "verified": False, "scope": "api_receiver", "nativeRunOwned": False}
        if not isinstance(evidence, dict) or evidence.get("available") is not True:
            self.full_flow["reason"] = "No public actual machine-flow record is available for this legacy or unobserved case."
            return
        self.full_flow["evidenceId"] = evidence.get("evidenceId")
        try:
            _require(isinstance(self.flow_before, dict) and type(self.flow_before.get("schemaVersion")) is int
                     and self.flow_before["schemaVersion"] in (1, 2)
                     and (self.flow_before["schemaVersion"] == 1 or self.flow_before.get("flowContract") == ITEM_CHANCE_CONTRACT) and self.flow_before.get("caseId") == self.args["selector"]
                     and type(self.flow_before.get("available")) is bool
                     and (self.dimension == 0 or type(self.flow_before.get("dimension")) is int
                          and self.flow_before["dimension"] == self.dimension
                          and self.flow_before.get("providerClass") == self.provider_class
                          and self.flow_before.get("sessionId") == self.args["expectedSessionId"]),
                     "qa_flow_baseline_unavailable", "Freshness needs an explicit typed public before-record for this same exact case.")
            fresh_id = evidence.get("evidenceId")
            _require(isinstance(fresh_id, str), "qa_flow_freshness_invalid", "The new actual flow must identify a UUID witness.")
            try:
                parsed = UUID(fresh_id)
            except (ValueError, AttributeError):
                raise _QaFailure("qa_flow_freshness_invalid", "The actual flow witness is not a valid UUID.") from None
            _require(str(parsed) == fresh_id and fresh_id != self.flow_before.get("evidenceId"),
                     "qa_flow_stale", "A preexisting public flow record cannot prove this submitted native test.")
            _require(type(evidence.get("schemaVersion")) is int and evidence["schemaVersion"] in (1, 2)
                     and (evidence["schemaVersion"] == 1 or evidence.get("flowContract") == ITEM_CHANCE_CONTRACT)
                     and evidence.get("caseId") == self.args["selector"] and type(evidence.get("dimension")) is int
                     and evidence["dimension"] == self.dimension and evidence.get("origin") == self.plan["allocationOrigin"]
                     and (self.dimension == 0 or evidence.get("providerClass") == self.provider_class
                          and evidence.get("sessionId") == self.args["expectedSessionId"]
                          and evidence.get("runId") == self.run_id())
                     and evidence.get("executionMode") == "normal_world" and evidence.get("retrievalScope") == "api_receiver"
                     and evidence.get("recoveryOperation") == "public_inventory_and_fluid_api"
                     and evidence.get("methodEntryThroughRecoveryIncluded") is True
                     and evidence.get("fixturePreparationIncluded") is False
                     and evidence.get("state") == "completed" and evidence.get("cleanupCompleted") is True
                     and "cleanupFailure" not in evidence,
                     "qa_flow_context_incomplete", "Actual normal-world evidence must retain this case/dimension/origin and successful method-to-recovery plus cleanup scope.")
            self.verify_flow_stages(evidence)
            bounds = evidence.get("loadedFixtureBounds")
            envelope = self.plan["reservedBounds"]
            _require(isinstance(bounds, dict) and all(type(bounds.get(prefix + axis)) is int for prefix in ("min", "max") for axis in "XYZ")
                     and all(envelope["min" + axis] <= bounds["min" + axis] <= bounds["max" + axis] <= envelope["max" + axis] for axis in "XYZ")
                     and bounds.get("coverage") == "all_present_GT_tiles_in_fixture_bounds"
                     and type(bounds.get("chunkCount")) is int and bounds["chunkCount"] ==
                         ((bounds["maxX"] >> 4) - (bounds["minX"] >> 4) + 1) * ((bounds["maxZ"] >> 4) - (bounds["minZ"] >> 4) + 1),
                     "qa_flow_loaded_bounds_invalid", "The entire producer fixture bound must lie inside both complete loaded task observations.")
            witnesses = evidence.get("activeTiles")
            _require(isinstance(witnesses, list) and 1 <= len(witnesses) <= 128,
                     "qa_flow_ticks_missing", "Actual normal-world GT tile tick witnesses are required.")
            points = set()
            for witness in witnesses:
                _require(isinstance(witness, dict), "qa_flow_ticks_missing", "Each tile tick witness must remain an object.")
                point = _position({axis: witness.get(axis) for axis in "xyz"}, bounds)
                _require(point not in points and _integer(witness.get("startTimer"), 0, LONG_MAX)
                         and _integer(witness.get("endTimer"), witness["startTimer"] + 1, LONG_MAX),
                         "qa_flow_ticks_missing", "Each unique actual tile must have a strictly advancing real timer.")
                points.add(point)
            declarations = evidence.get("declaredRecipe")
            _require(isinstance(declarations, dict), "qa_flow_declaration_invalid", "The fresh public recipe must expose its finite exact typed declaration.")
            _require(evidence["schemaVersion"] != 1 or ("rawItemOutputs" not in declarations
                     and "finiteOutputLedger" not in evidence and evidence.get("flowContract") != ITEM_CHANCE_CONTRACT),
                     "qa_chance_context_invalid", "Schema 1 deterministic consumers cannot interpret probability potential/actual evidence.")
            declared = {key: _bag(declarations.get(key), fluid=key.startswith("fluid")) for key in
                        ("consumedItems", "physicalCatalysts", "itemOutputs", "fluidInputs", "fluidOutputs")}
            _require(bool(declared["consumedItems"] or declared["fluidInputs"])
                     and bool(declared["itemOutputs"] or declared["fluidOutputs"]),
                     "qa_flow_declaration_invalid", "A normal finite flow must consume a declared resource and create an actual declared product.")
            snapshots = evidence.get("snapshots")
            stages = ("prepared", "supplied", "produced", "recovered", "stable")
            _require(isinstance(snapshots, list) and len(snapshots) == len(stages)
                     and [snapshot.get("stage") if isinstance(snapshot, dict) else None for snapshot in snapshots] == list(stages),
                     "qa_flow_checkpoint_incomplete", "All five ordered complete actual source/receiver checkpoints are required.")
            multi = evidence.get("publicMultiFluidBindings")
            multiple_receivers = isinstance(multi, dict) and multi.get("contract") == "native_deterministic_multi_fluid_v1"
            checks = {name: _checkpoint(snapshot, bounds, multiple_receivers) for name, snapshot in zip(stages, snapshots)}
            if multiple_receivers:
                _require(evidence["schemaVersion"] == 1 and all(multi.get(key) is True for key in
                         ("allPhysicalInputsBound", "allPhysicalOutputsBound", "allPhysicalReceiversBound", "nativeAmountsAndNBTUnchanged", "fullVectorOutputRouting", "jointCapacityPreflight"))
                         and multi.get("virtualFluidInventory") is False,
                         "qa_multi_fluid_bindings_invalid", "Expanded fluid receipts require complete deterministic physical bindings and joint exact native capacity proof.")
                role_names = {"input": ["finite_fluid_input"] + ["finite_fluid_input_" + str(i) for i in range(1, 16)],
                              "output": ["output"] + ["fluid_output_" + str(i) for i in range(1, 16)],
                              "receiver": ["fluid_receiver"] + ["fluid_receiver_" + str(i) for i in range(1, 16)]}
                counts = {"input": "physicalFluidInputPortCount", "output": "physicalFluidOutputPortCount", "receiver": "physicalFluidReceiverCount"}
                positions = {}
                for role in role_names:
                    count = multi.get(counts[role])
                    _require(_integer(count, 1 if role == "receiver" else 0, 16), "qa_multi_fluid_bindings_invalid", "Every physical fluid role count must fit the 16/16/16 bound; raw inputs/outputs remain8/8.")
                    declared_positions = multi.get(role + "Positions")
                    _require(isinstance(declared_positions, list) and len(declared_positions) == count,
                             "qa_multi_fluid_bindings_invalid", "Each role must enumerate every physical position once.")
                    positions[role] = {_position(point, bounds) for point in declared_positions}
                    _require(len(positions[role]) == count, "qa_multi_fluid_bindings_invalid", "Physical role positions cannot repeat.")
                _require(sum(len(points) for points in positions.values()) == len(set().union(*positions.values())),
                         "qa_flow_source_receiver_alias", "Physical fluid source/output/receiver roles must be disjoint.")
                allowed = {name for role, names in role_names.items() for name in names[:multi[counts[role]]]}
                for stage, check in checks.items():
                    bindings = check["fluids"]["bindings"]
                    _require(set(bindings) == allowed, "qa_multi_fluid_bindings_invalid", "Closed role names must cover all physical tanks, including empties.")
                    for role, names in role_names.items():
                        selected = [bindings[name] for name in names[:multi[counts[role]]]]
                        _require({binding["position"] for binding in selected} == positions[role], "qa_multi_fluid_bindings_invalid", "Each complete checkpoint must match every declared physical role position.")
                        bag = Counter()
                        for binding in selected:
                            bag.update(_bag([row for row in binding["entries"] if row["amount"] > 0], fluid=True))
                        expected = (declared["fluidInputs"] if role == "input" and stage == "supplied" else
                                    declared["fluidOutputs"] if role == "output" and stage == "produced" or role == "receiver" and stage in ("recovered", "stable") else Counter())
                        _require(bag == expected, "qa_multi_fluid_role_quantity_mismatch", "Every input/output/receiver role must match exact stage-specific fluid/NBT quantities.")
            for domain in ("items", "fluids"):
                first = checks["prepared"][domain]
                bindings = {key: (value["position"], value["total"]) for key, value in first["bindings"].items()}
                _require(all({key: (value["position"], value["total"]) for key, value in check[domain]["bindings"].items()} == bindings
                             for check in checks.values()), "qa_flow_binding_changed", "Complete actual binding positions/counts must stay fixed across all checkpoints.")
                digital_layouts = {key: value.get("layout") for key, value in first["bindings"].items()
                                   if value.get("representation") == "digital_chest_channels"}
                _require(all({key: value.get("layout") for key, value in check[domain]["bindings"].items()
                              if value.get("representation") == "digital_chest_channels"} == digital_layouts
                             for check in checks.values()), "qa_flow_binding_changed",
                         "Actual digital channel positions, configured identities and public capacities must stay fixed across every checkpoint.")
                produced, recovered = checks["produced"][domain], checks["recovered"][domain]
                _require(not first["receiver"] and not checks["supplied"][domain]["receiver"] and not produced["receiver"],
                         "qa_flow_receiver_baseline_nonempty", "Actual receivers must remain empty until real public recovery.")
                _require(produced["source"] + produced["receiver"] == recovered["source"] + recovered["receiver"],
                         "qa_flow_recovery_not_conserved", "Actual source decreases and receiver credits must conserve every exact identity/NBT quantity.")
                _require(checks["recovered"][domain] == checks["stable"][domain],
                         "qa_flow_stability_changed", "Complete actual slots/tanks must remain unchanged during real-world stopped stability.")
            item_base, fluid_base = checks["prepared"]["items"]["source"], checks["prepared"]["fluids"]["source"]
            catalysts, outputs = declared["physicalCatalysts"], declared["itemOutputs"]
            chance_ledger = None
            if evidence["schemaVersion"] == 2:
                outputs, chance_ledger = _chance_actual(evidence, declarations, declared, checks)
            _require(checks["supplied"]["items"]["source"] == item_base + declared["consumedItems"] + catalysts
                     and checks["produced"]["items"]["source"] == item_base + catalysts + outputs
                     and checks["recovered"]["items"]["source"] == item_base
                     and checks["recovered"]["items"]["receiver"] == catalysts + outputs
                     and checks["supplied"]["fluids"]["source"] == fluid_base + declared["fluidInputs"]
                     and checks["produced"]["fluids"]["source"] == fluid_base + declared["fluidOutputs"]
                     and checks["recovered"]["fluids"]["source"] == fluid_base
                     and checks["recovered"]["fluids"]["receiver"] == declared["fluidOutputs"],
                     "qa_flow_finite_contract_mismatch", "Actual supply, consumption, retained catalysts, all products and real receiver receipts must match the fresh finite declaration exactly.")
            _require(self.test_passed(), "qa_flow_native_test_unaccepted", "Fresh actual flow evidence is accepted only with the exact complete native PASSED report.")
            self.full_flow.update(verified=True, fresh=True, sessionId=self.args["expectedSessionId"],
                expectedWorld=self.args["expectedWorld"], dimension=self.dimension, origin=evidence["origin"],
                caseId=evidence["caseId"], elapsedNanos=evidence["elapsedNanos"], worldTicks=evidence["worldTicks"],
                producerFixturePreparationIncluded=False, taskFixturePreparationIncluded=True,
                nativeTestAssertionsPassed=True, sourceReceiverConservation=True, finiteDeclarationMatched=True,
                physicalCatalystsRecovered=True, actualNormalWorldTicks=True, cleanupCompleted=True,
                contextBinding="fresh_guarded_bridge_reads_and_new_evidence_uuid; native_report_ownership_unverified")
            if self.dimension != 0:
                self.full_flow["providerClass"] = self.provider_class
            stage_ticks = {stage["name"]: stage["worldTick"] for stage in evidence["stages"]}
            self.quantitative_summary = {
                "scope": "verified_finite_declaration_and_actual_api_receivers",
                "quantityEncoding": "decimal_integer",
                "consumedItems": _resource_quantities(declared["consumedItems"]),
                "physicalCatalysts": _resource_quantities(declared["physicalCatalysts"]),
                "itemOutputs": _resource_quantities(outputs),
                "fluidInputs": _resource_quantities(declared["fluidInputs"], fluid=True),
                "fluidOutputs": _resource_quantities(declared["fluidOutputs"], fluid=True),
                "receiverItems": _resource_quantities(checks["recovered"]["items"]["receiver"]),
                "receiverFluids": _resource_quantities(checks["recovered"]["fluids"]["receiver"], fluid=True),
                "stableTicks": stage_ticks["normal_world_stability_verified"] - stage_ticks["api_receiver_recovery_complete"],
            }
            if chance_ledger is not None:
                probability = {"contract": ITEM_CHANCE_CONTRACT, "rawItemOutputs": [dict(row) for row in declarations["rawItemOutputs"]],
                    "potentialItemOutputs": _resource_quantities(declared["itemOutputs"]),
                    "actualItemOutputs": _resource_quantities(outputs),
                    "nativeGeneratedStatus": chance_ledger["nativeGenerated"]["status"],
                    "generationToDepositVerified": chance_ledger["generationToDepositVerified"],
                    "recipesDoneDelta": 1, "productRecoveryExercised": chance_ledger["productRecoveryExercised"],
                    "probabilityDistributionVerified": False}
                self.quantitative_summary.update(probability)
                self.full_flow.update(actualDepositRecoveryVerified=True, probabilityDistributionVerified=False,
                    productRecoveryExercised=chance_ledger["productRecoveryExercised"],
                    generationToDepositVerified=chance_ledger["generationToDepositVerified"])
                if chance_ledger["nativeGenerated"]["status"] == "unknown":
                    self.full_flow.update(verified=False, partialEvidence=dict(self.quantitative_summary))
                    self.quantitative_summary = None
                    raise _QaFailure("qa_chance_native_generated_unknown", "Actual deposited products, recovery and stable consumption are verified; missed native pending leaves generation-to-deposit proof unknown.")
            if self.args.get("includeDetails", False):
                self.full_flow["observation"] = evidence
        except _QaFailure as error:
            self.full_flow.update(reason=error.message, errorCode=error.code)

    def verify_flow_stages(self, evidence):
        stages = evidence.get("stages")
        _require(isinstance(stages, list) and 10 <= len(stages) <= 32
                 and all(isinstance(stage, dict) and isinstance(stage.get("name"), str)
                         and _integer(stage.get("elapsedNanos"), 0, LONG_MAX) and _integer(stage.get("worldTick"), 0, LONG_MAX) for stage in stages),
                 "qa_flow_stages_incomplete", "The actual method/placement/processing/recovery/stability stages need typed complete timing witnesses.")
        names = [stage["name"] for stage in stages]
        placement = [name for name in ("registered_native_machine_placed", "native_public_blueprint_constructed") if name in names]
        required = ["method_entry", "receiver_prepared", *placement, "machine_prepared_and_freshly_checked", "finite_inputs_supplied",
                    "processing_start_observed", "production_complete_and_stopped", "api_receiver_recovery_complete",
                    "normal_world_stability_verified", "complete"]
        _require(len(placement) == 1 and all(names.count(name) == 1 for name in required)
                 and [names.index(name) for name in required] == sorted(names.index(name) for name in required)
                 and all(first["elapsedNanos"] <= second["elapsedNanos"] and first["worldTick"] <= second["worldTick"]
                         for first, second in zip(stages, stages[1:])),
                 "qa_flow_stages_incomplete", "Actual placement, preparation, processing, stop, recovery and stable completion must be ordered once.")
        indexed = {stage["name"]: stage for stage in stages}
        _require(indexed["normal_world_stability_verified"]["worldTick"] - indexed["api_receiver_recovery_complete"]["worldTick"] >= 85
                 and _integer(evidence.get("elapsedNanos"), stages[-1]["elapsedNanos"], LONG_MAX)
                 and _integer(evidence.get("worldTicks"), 85, LONG_MAX)
                 and evidence["worldTicks"] == indexed["complete"]["worldTick"] - indexed["method_entry"]["worldTick"]
                 and _integer(evidence.get("cleanupElapsedNanos"), evidence["elapsedNanos"], LONG_MAX)
                 and _integer(evidence.get("cleanupWorldTick"), indexed["complete"]["worldTick"], LONG_MAX),
                 "qa_flow_stability_unverified", "Frozen method timing and successful cleanup must cover at least 85 actual stability ticks.")

    def result(self, failure=None):
        status = self.terminal or {}
        native = status.get("nativeReport", {})
        data = {"status": "completed" if failure is None else "unsupported" if failure.unsupported else "failed",
                "available": failure is None or not failure.unsupported, "nonAtomic": True, "rolledBack": False,
                "selector": self.args["selector"], "runId": (self.client.ticket or {}).get("runId"),
                "sessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
                "dimension": self.dimension, "providerClass": self.provider_class,
                "testPassed": self.report_semantics_verified(),
                "fullFlowVerified": self.full_flow.get("verified") is True and self.report_semantics_verified(), "fullFlowEvidence": self.full_flow,
                "nativeState": status.get("state"), "nativeReport": native,
                "correlation": status.get("correlation", (self.client.ticket or {}).get("correlation")),
                "owned": status.get("owned", (self.client.ticket or {}).get("owned")),
                "reportsComplete": self.reports_complete, "reportCoherent": self.report_coherent,
                "diagnosticsComplete": self.diagnostics_complete and self.reports_complete,
                "caseCount": len(self.cases), "issueCount": len(self.issues),
                "cases": self.cases, "issues": self.issues, "diagnosticErrors": self.diagnostic_errors,
                "preflight": self.preflight, "afterScan": self.after_scan, "requests": self.client.calls,
                "requestCounts": dict(sorted(self.client.operation_counts.items())), "polling": self.polling,
                "scanCells": self.scan_cells,
                "runTicket": self.client.ticket,
                "runAttempts": int(self.client.action_attempted), "submissionRejected": self.client.submission_rejected,
                "runSubmissions": int(self.client.action_attempted and not self.client.submission_rejected),
                "sideEffectsPossible": self.client.action_attempted and not self.client.submission_rejected,
                "outcomeUnknown": self.client.action_attempted and not self.client.submission_rejected and self.client.ticket is None,
                "wholeBudget_ms": WHOLE_BUDGET_MS,
                "timingScope": "adapter_entry_before_validation_through_queue_preflight_run_wait_complete_reports_after_read_and_acceptance",
                "phases": self.phases}
        if failure:
            data["error"] = {"code": failure.code, "message": failure.message, "phase": self.phase}
        if not self.args.get("includeDetails", False) and failure is None:
            data["cases"] = [{key: case[key] for key in ("id", "status", "detailsCoverage", "detailsTruncated") if key in case}
                             for case in self.cases]
            data.pop("phases", None)
        elapsed = (time.monotonic() - self.started) * 1000
        data.update(wholeTaskElapsed_ms=elapsed, completeWithin10s=failure is None and elapsed <= WHOLE_BUDGET_MS)
        if failure is None and elapsed > WHOLE_BUDGET_MS:
            data.update(status="failed", completeWithin10s=False,
                        error={"code": "qa_whole_budget_exceeded", "message": "The complete QA task exceeded 10 seconds.", "phase": "whole_acceptance"})
        if data["status"] == "completed" and data["fullFlowVerified"] and self.quantitative_summary is not None:
            data["quantitativeSummary"] = self.quantitative_summary
        data["outputOmitted"] = False
        data["maxReturnBytes"] = self.args.get("max_return_bytes", 65_536)
        # Large fields are omitted whole, preserving receipt/status/count/evidence identifiers.
        omit = [(data["fullFlowEvidence"], "observation"), (data, "cases"), (data, "issues"),
                (data, "phases"), (data, "afterScan"), (data, "preflight"), (data, "diagnosticErrors"),
                (data, "quantitativeSummary")]
        for owner, key in omit:
            if json_size(data) <= data["maxReturnBytes"] - 512:
                break
            if key in owner:
                owner.pop(key)
                if key == "quantitativeSummary":
                    data["quantitativeSummaryOmittedReason"] = "complete_exact_summary_exceeds_max_return_bytes"
                data.update(outputOmitted=True, diagnosticsComplete=False)
                data.setdefault("omittedFields", []).append("fullFlowEvidence.observation" if owner is data["fullFlowEvidence"] else key)
        if json_size(data) > data["maxReturnBytes"] - 512:
            keep = {"status", "exitCode", "selectedTests", "passed", "failed", "timedOut", "skipped", "incomplete", *COUNT_FIELDS}
            data["nativeReport"] = {key: value for key, value in native.items() if key in keep}
            data["runTicket"] = {key: value for key, value in (self.client.ticket or {}).items()
                                 if key in ("runId", "selector", "selectedTests", "selectedIds", "selectedIdsTruncated", "state", "startStatus", "correlation", "owned")}
            data.update(outputOmitted=True, diagnosticsComplete=False)
            data.setdefault("omittedFields", []).extend(["nativeReport.otherFields", "runTicket.otherFields"])
        # Serialization/compaction is part of the whole acceptance clock too.
        data["wholeTaskElapsed_ms"] = (time.monotonic() - self.started) * 1000
        if failure is None and data["wholeTaskElapsed_ms"] > WHOLE_BUDGET_MS:
            data.pop("quantitativeSummary", None)
            data.update(status="failed", completeWithin10s=False,
                        error={"code": "qa_whole_budget_exceeded", "message": "The complete QA task exceeded 10 seconds.", "phase": "whole_acceptance"})
        return data


async def run_qa_test_once(client, arguments, lock, *, runner=None, started_at=None):
    """One tool entry; no action retry, global cancellation, JSON plan or script files."""
    started = time.monotonic() if started_at is None else started_at
    if runner is None:
        from .workflows import run_workflow
        runner = run_workflow
    if "machineFlows" in arguments:
        from .qa_machine_flows import _MachineFlowQaTask
        task = _MachineFlowQaTask(client, arguments, runner, started)
    else:
        task = _QaTask(client, arguments, runner, started)
    failure = None
    failure_phase = None
    acquired = False
    try:
        with anyio.fail_after(max(.001, task.deadline - time.monotonic())):
            task.phase = "queue"
            await lock.acquire()
            acquired = True
            await task.prepare()
            await task.execute()
            await task.diagnostics()
            await task.after()
            task.phase = "acceptance"
            task.accept()
            if "machineFlows" in arguments:
                await task.release_native_capture()
    except anyio.get_cancelled_exc_class():
        if "machineFlows" in arguments:
            task.release_capture({}, _QaFailure("qa_cancelled", "The QA group was cancelled."))
        raise
    except (BridgeError, _QaFailure, TimeoutError) as error:
        failure_phase = task.phase
        failure = error if isinstance(error, _QaFailure) else _QaFailure(
            getattr(error, "code", "qa_deadline"), str(error)[:512] or "The complete bounded QA task timed out.")
        if task.client.ticket is not None and not task.reports_complete:
            # Same explicit ticket only, with its own bounded diagnostic allowance.
            with anyio.CancelScope(shield=True):
                with anyio.move_on_after(2) as recovery_scope:
                    try:
                        await task.diagnostics(recovery=True)
                    except (BridgeError, _QaFailure) as diagnostic:
                        task.diagnostic_errors.append({"code": getattr(diagnostic, "code", "qa_diagnostic_failed"), "message": str(diagnostic)[:512]})
                        task.diagnostics_complete = False
                if recovery_scope.cancel_called:
                    task.diagnostic_errors.append({"code": "qa_diagnostic_deadline", "message": "The separate two-second diagnostic allowance expired."})
                    task.diagnostics_complete = False
    finally:
        if acquired:
            lock.release()
    if failure_phase is not None:
        task.phase = failure_phase
    result = task.result(failure)
    if "machineFlows" in arguments:
        task.release_capture(result, failure)
    return result
