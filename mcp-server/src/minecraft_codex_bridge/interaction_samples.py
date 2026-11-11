"""Bounded real native use actions with one preflight and incremental chat evidence; no machine setters."""

from __future__ import annotations

import json
import re
import time
from typing import Any

import anyio

from .bridge import BridgeError, request_context, response_context
from .operation_policy import environment
from .recipe_tasks import _RecipeTask
from .workflows import _guard


def _reported_metrics(message: dict[str, Any]) -> dict[str, Any]:
    # Localizations with known positional placeholder mistakes must not acquire semantics
    # from a list of numbers. Preserve their text for source-verified interpretation.
    text = re.sub(r"\u00a7.", "", message.get("text", ""))
    patterns = {
        "averageNanos": r"\baverage(?:\s+tick)?\s*[:=]\s*(\d+)\s*ns\b",
        "worstNanos": r"\bworst(?:\s+tick)?\s*[:=]\s*(\d+)\s*ns\b",
        "windowTicks": r"\b(?:window|over)\s*[:=]?\s*(\d+)\s*ticks?\b",
    }
    matches = {key: re.search(pattern, text, re.IGNORECASE) for key, pattern in patterns.items()}
    if message.get("textTruncated") is True or not all(matches.values()):
        return {"available": False, "reason": "unrecognized_or_untrusted_localized_format", "rawMessageSeq": message.get("seq")}
    return {"available": True, "source": "explicit_english_labels_in_visible_message", "rawMessageSeq": message.get("seq"),
            "scope": "reported instrument window; not RPC wall time, recipe planning time or a production round",
            **{key: int(match.group(1)) for key, match in matches.items()}}


async def run_interaction_samples(client, arguments, lock, *, require_capability, full_background) -> dict[str, Any]:
    started = time.monotonic()
    count, interval, timeout = arguments.get("count", 3), arguments.get("interval_ms", 1300), arguments.get("timeout_ms", 10000)
    result: dict[str, Any] = {"status": "preflight", "requestedCount": count, "completedActions": 0, "samples": [],
        "replayAllowed": False, "actionInFlight": False, "sessionId": arguments["expectedSessionId"],
        "timingScope": "outer wall clock including preflight, native calls and waits; never machine CPU time",
        "messageAttribution": "Incremental receive window, not causal action acknowledgement or production-round identity"}
    if count * interval + 250 >= timeout:
        raise BridgeError("SAMPLING_BUDGET", "Requested waits leave no bounded time for the native actions; reduce count/interval or increase timeout<=30s.",
                          original_action_executed=False, outcome_unknown=False, receipt=result)
    context = {"version": 1, "expectedSessionId": arguments["expectedSessionId"], "expectedProfile": "developer",
               "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
    async with lock:
        try:
            with anyio.fail_after(timeout / 1000):
                health = await client.invoke("bridge.health", {})
                if (health.get("sessionId") != arguments["expectedSessionId"] or health.get("profile") != "developer"
                        or environment(health) != "local_integrated" or health.get("interactionHeldBindingVersion") != 1):
                    raise BridgeError("SAMPLING_UNAVAILABLE", "Sampling needs the exact current local developer session and native held-item binding support.",
                                      original_action_executed=False, outcome_unknown=False)
                for operation in ("player.interact", "client.messages", "inventory.inspect", "client.background"):
                    require_capability(health, operation)
                guard = health.get("contextRpc", {})
                if "client.messages" not in guard.get("reads", []) or "player.interact" not in guard.get("actions", []):
                    raise BridgeError("SAMPLING_UNAVAILABLE", "Sampling requires advertised guarded message reads and native use actions.", outcome_unknown=False)
                if health.get("interactionViewBinding") is not True or health.get("interactionBlockTargeting") is not True:
                    raise BridgeError("SAMPLING_UNAVAILABLE", "Aimed normal-reach native interaction is unavailable.", outcome_unknown=False)
                if "targetPart" in arguments and health.get("interactionPartTargetingVersion") != 1:
                    raise BridgeError("SAMPLING_UNAVAILABLE", "Native part-target binding is unavailable.", outcome_unknown=False)
                if not full_background(health.get("background")):
                    if not full_background(await client.call("client.background", {"enabled": True})):
                        raise BridgeError("TAKEOVER_NOT_CONFIRMED", "Sampling did not establish full background protection.", outcome_unknown=False)
                with request_context(context):
                    inventory = await client.call("inventory.inspect", {})
                initial = _context_snapshot(inventory, context, "after")
                world, server = initial.get("world"), initial.get("serverControl")
                dimension = world.get("dimension") if isinstance(world, dict) else None
                actor = server.get("playerId") if isinstance(server, dict) else None
                if (type(dimension) is not int or not isinstance(actor, str) or not actor
                        or server.get("available") is not True or actor != inventory.get("playerId")):
                    raise BridgeError("SAMPLING_CONTEXT_UNAVAILABLE", "Sampling needs a fresh authoritative actor/dimension binding before the first click.", outcome_unknown=False)
                slot = inventory.get("selectedHotbarSlot")
                held = next((row.get("item") for row in inventory.get("slots", []) if row.get("index") == slot), None)
                if (type(slot) is not int or not 0 <= slot <= 8 or not isinstance(held, dict)
                        or not isinstance(held.get("registryName"), str) or type(held.get("metadata")) is not int):
                    raise BridgeError("SAMPLING_HELD_ITEM_REQUIRED", "Equip the intended instrument before sampling; empty hand is refused.", outcome_unknown=False)
                action = {"action": "use", **{key: arguments[key] for key in ("x", "y", "z", "side", "targetPart") if key in arguments},
                          "expectedHeld": {"slot": slot, "registryName": held["registryName"], "metadata": held["metadata"]}}
                result["heldIdentity"] = action["expectedHeld"]
                with request_context(context):
                    baseline = await client.call("client.messages", {"limit": 1})
                _context_snapshot(baseline, context, "after", dimension, actor)
                since = baseline["latestSeq"]
                result["baselineMessageSeq"] = since
                retained_bytes, messages_seen = 0, 0
                for index in range(count):
                    result.update(status="sampling", actionInFlight=True, attemptedCount=index + 1)
                    began = time.monotonic()
                    with request_context(context):
                        receipt = await client.call("player.interact", action)
                    # Retain a returned action before validating its evidence. It must never be replayed.
                    result["actionInFlight"] = False
                    sample = {"index": index, "nativeAction": dict(receipt), "receivedAtMillis": int(time.time() * 1000),
                              "actionWallMillis": round((time.monotonic() - began) * 1000, 3)}
                    result["samples"].append(sample)
                    result["completedActions"] += 1
                    _context_snapshot(receipt, context, "before", dimension, actor)
                    if receipt.get("guiOpen") is True:
                        raise BridgeError("SAMPLING_GUI_OPENED", "Native use opened a GUI; no further sample action is allowed.", outcome_unknown=False)
                    await anyio.sleep(interval / 1000)
                    with request_context(context):
                        messages = await client.call("client.messages", {"since": since, "limit": 128})
                    _context_snapshot(messages, context, "after", dimension, actor)
                    metrics = [_reported_metrics(row) for row in messages.get("messages", [])]
                    size = len(json.dumps({"chat": messages, "reportedMetrics": metrics}, ensure_ascii=True).encode("utf-8"))
                    if retained_bytes + size > 512 * 1024 or messages_seen + len(messages.get("messages", [])) > 256:
                        sample["chat"] = {"retained": False, "truncated": True, "reason": "sampling_data_limit", "unretainedSince": since}
                        raise BridgeError("SAMPLING_DATA_LIMIT", "Bounded evidence capacity reached; latest messages were not retained and remaining clicks were not submitted.", outcome_unknown=False)
                    sample["chat"], sample["reportedMetrics"] = dict(messages), metrics
                    retained_bytes += size
                    messages_seen += len(messages.get("messages", []))
                    if messages.get("historyGap") is True or messages.get("hasMore") is True:
                        raise BridgeError("SAMPLING_MESSAGE_GAP", "Visible chat history overflowed or has an unread page; sampling stopped, no complete metric claim.", outcome_unknown=False)
                    since = messages["nextSince"]
                    result["nextSince"] = since
                result.update(status="completed", messagesComplete=True, elapsedMillis=round((time.monotonic() - started) * 1000, 3))
                return result
        except (BridgeError, TimeoutError) as failure:
            unknown = result["actionInFlight"] and (
                not isinstance(failure, BridgeError) or failure.details.get("outcome_unknown") is not False)
            result.update(status="stopped", elapsedMillis=round((time.monotonic() - started) * 1000, 3),
                          messagesComplete=False, automaticReplayAllowed=False)
            raise BridgeError(getattr(failure, "code", "SAMPLING_TIMEOUT"),
                "Native sampling stopped. Retain completed action/message receipts; no automatic repeat.",
                outcome_unknown=bool(unknown), receipt=result,
                cause=failure.as_dict() if isinstance(failure, BridgeError) else {"code": "deadline"}) from None


def _context_snapshot(data, context, phase, dimension=None, actor=None):
    evidence = response_context(data)
    current = evidence.get("snapshot") if isinstance(evidence, dict) else None
    if not isinstance(current, dict) or evidence.get("phase") != phase:
        raise BridgeError("SAMPLING_CONTEXT_UNAVAILABLE", "Each native action/read requires its actual matching context receipt.", outcome_unknown=True)
    _guard(current, context["expectedSessionId"], "developer", context["expectedWorld"], True)
    _RecipeTask.require_live_player(current)
    if (dimension is not None and current.get("world", {}).get("dimension") != dimension
            or actor is not None and current.get("serverControl", {}).get("playerId") != actor):
        raise BridgeError("SAMPLING_CONTEXT_CHANGED", "Actor or dimension changed; remaining sample actions are refused.", outcome_unknown=True)
    if current.get("screen") != "":
        raise BridgeError("SAMPLING_GUI_OPEN", "Sampling requires the observed world view; remaining sample actions are refused.", outcome_unknown=False)
    return current
