"""Bounded failure evidence for the one native survival construction operation."""

from __future__ import annotations

import copy
import json
import math
from typing import Any


SURVIVAL_OPERATION = "structure.survival_build"
_BOOLEANS = ("nativeSubmitted", "callbackInFlight", "partial", "callbackPreemptible", "automaticReplayAllowed")
_COUNTS = ("callsStarted", "callsCompleted")
_STRINGS = ("stage", "failureCheckpoint")
_FIELDS = (*_STRINGS, *_BOOLEANS, *_COUNTS, "batches", "knownReceipt")
_RECEIPT_FIELDS = frozenset({"version", "operation", "status", "actualSurvival", "sessionId", "expectedWorld", "dimension",
    "controller", "actor", "blueprint", "nativeDone", "materialAccountingComplete", "formationVerified", "partial",
    "outcomeUnknown", "generationPerformed", "inventoryCleared", "materialSourceType", "playerInventoryBefore",
    "playerInventoryAfter", "lastNativeReturn", "lastCheckpoint", "callbackFailed", "callbackError", "callbackErrorMessage",
    "committedItemsAfterFailure", "materialSourceAfterFailure", "afterFailureObservationError",
    "clientBuildBodyProof", "serverBuildBodyProof"})


def survival_failure_fields(error: dict[str, Any]) -> dict[str, Any]:
    """Validate the existing optional transport contract, without inferring completion."""
    valid = all(key not in error or type(error[key]) is bool for key in (*_BOOLEANS, "outcome_unknown"))
    valid = valid and all(key not in error or type(error[key]) is int and 0 <= error[key] <= 32 for key in _COUNTS)
    valid = valid and all(key not in error or isinstance(error[key], str) and 1 <= len(error[key]) <= 128 for key in _STRINGS)
    valid = valid and ("batches" not in error or isinstance(error["batches"], list) and len(error["batches"]) <= 32
        and all(isinstance(batch, dict) for batch in error["batches"]))
    valid = valid and ("knownReceipt" not in error or isinstance(error["knownReceipt"], dict))
    valid = valid and all(key not in error or error[key] is False for key in ("callbackPreemptible", "automaticReplayAllowed"))
    valid = valid and (not set(_COUNTS) <= error.keys() or error["callsCompleted"] <= error["callsStarted"])
    if not valid:
        raise ValueError("Invalid bounded native survival checkpoints")
    result = {key: error[key] for key in _FIELDS if key in error}
    # JSON is already response-bounded at transport; these also bound custom runners.
    pending, nodes = [(result, 0)], 0
    while pending:
        value, depth = pending.pop()
        nodes += 1
        if nodes > 100_000 or depth > 24:
            raise ValueError("Native survival checkpoint exceeds structural bounds")
        if isinstance(value, dict):
            if any(not isinstance(key, str) or len(key) > 256 for key in value):
                raise ValueError("Invalid checkpoint field name")
            pending.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            pending.extend((child, depth + 1) for child in value)
        elif isinstance(value, str):
            if len(value) > 65_536:
                raise ValueError("Checkpoint text exceeds native full identity bounds")
        elif type(value) not in (int, bool, float) and value is not None or type(value) is float and not math.isfinite(value):
            raise ValueError("Invalid checkpoint JSON value")
    if len(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 1_048_576:
        raise ValueError("Native survival checkpoint exceeds transport bounds")
    return result


def survival_failure_checkpoint(details: dict[str, Any], maximum: int, *, operation: str = SURVIVAL_OPERATION) -> dict[str, Any] | None:
    """Keep one complete fixed payload, or explicit unavailable evidence; never truncate it."""
    fields = survival_failure_fields(details)
    if not fields:
        return None
    if operation not in (SURVIVAL_OPERATION, "player.place_block"):
        raise ValueError("Unsupported native checkpoint operation")
    checkpoint = {"operation": operation, "payloadComplete": True,
        **copy.deepcopy({key: value for key, value in fields.items() if key != "knownReceipt"})}
    if "knownReceipt" in fields:
        checkpoint["knownReceipt"] = copy.deepcopy({key: value for key, value in fields["knownReceipt"].items()
            if operation == "player.place_block" or key in _RECEIPT_FIELDS})
    if len(json.dumps(checkpoint, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > min(196_608, max(0, maximum - 16_384)):
        checkpoint = {key: value for key, value in checkpoint.items() if key not in ("batches", "knownReceipt")}
        checkpoint.update(payloadComplete=False, payloadOmittedReason="return_budget")
    return checkpoint
