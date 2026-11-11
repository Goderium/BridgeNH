"""Bounded observation waits, composite context reads and cursor paging."""
from __future__ import annotations

import json
import time
from typing import Any
import anyio
from ..bridge import BridgeClient, BridgeError, request_context, response_context
from ..tools import TOOL_BY_NAME
from ..conveniences import path_get
from ..launcher import client_diagnostics
from ..workflows import _guard
from ..recipe_tasks import _RecipeTask
from ..validation import validate_tool_arguments
from ..knowledge import record_wait_sample


def _json_equal(left: Any, right: Any) -> bool:
    # JSON booleans are not numbers (Python's True == 1 must not match).
    if type(left) is bool or type(right) is bool:
        return type(left) is type(right) and left == right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        return len(left) == len(right) and all(_json_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(_json_equal(value, right[key]) for key, value in left.items())
    return left == right


def condition_matches(observation: dict[str, Any], condition: dict[str, Any]) -> bool:
    exists, value = path_get(observation, condition["path"])
    operator = condition["operator"]
    if operator == "exists":
        return exists == condition.get("value", True)
    if not exists:
        return False
    expected = condition["value"]
    if operator == "equals":
        return _json_equal(value, expected)
    if isinstance(value, str) and isinstance(expected, str):
        return expected in value
    if isinstance(value, list):
        return any(_json_equal(item, expected) for item in value)
    if isinstance(value, dict) and isinstance(expected, str):
        return expected in value
    return False


async def wait_for(client: BridgeClient, arguments: dict[str, Any]) -> dict[str, Any]:
    name = arguments.get("tool", "mcb_snapshot")
    observation_args = arguments.get("args", {})
    validate_tool_arguments(name, observation_args)
    operation = TOOL_BY_NAME[name].operation
    timeout = arguments.get("timeout_ms", 10_000) / 1000
    interval = arguments.get("interval_ms", 1000) / 1000
    start = time.monotonic()
    deadline = start + timeout
    attempts = 0
    last = None
    try:
        with anyio.fail_after(timeout):
            while True:
                attempts += 1
                record_wait_sample()
                last = await client_diagnostics(client) if name == "mcb_diagnostics" else await client.invoke(operation, observation_args)
                if condition_matches(last, arguments["condition"]):
                    return {"matched": True, "attempts": attempts, "elapsed_ms": int((time.monotonic() - start) * 1000), "observation": last}
                await anyio.sleep(min(interval, max(0, deadline - time.monotonic())))
    except TimeoutError:
        raise BridgeError(
            "WAIT_TIMEOUT", "The condition was not observed before the bounded wait expired.",
            matched=False, attempts=attempts, elapsed_ms=int((time.monotonic() - start) * 1000), observation=last,
        ) from None


async def invoke_current_world_read(client: BridgeClient, operation: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """Use the existing AFTER context for standalone owner/client composite reads.

    These tools already mean the current local world. Bind that world from a
    fresh snapshot, preserve background preferences, and never retry a read or
    silently return a server-only half when the native client half is missing.
    """
    credentials = client.configuration.read()
    snapshot = await client.invoke("client.snapshot", {})
    session, world = snapshot.get("sessionId"), snapshot.get("world", {}).get("worldFolder")
    if snapshot.get("profile") != "developer" or snapshot.get("world", {}).get("singleplayer") is not True \
            or not isinstance(session, str) or not 1 <= len(session) <= 128 or not isinstance(world, str) or not 1 <= len(world) <= 128:
        raise BridgeError("unsupported", "This standalone composite read requires the current loaded local developer world.", outcome_unknown=False)
    protected = operation == "world.inspect_many"
    _guard(snapshot, session, "developer", world, protected)
    _RecipeTask.require_live_player(snapshot)
    dimension = snapshot.get("world", {}).get("dimension")
    if type(dimension) is not int or arguments.get("dimension", dimension) != dimension:
        raise BridgeError("WORLD_CHANGED", "Composite owner/client reads must use the actor's actual current dimension.", outcome_unknown=False)
    context = {"version": 1, "expectedSessionId": session, "expectedProfile": "developer", "expectedWorld": world, "requireBackground": protected}
    with request_context(context):
        data = await client.call(operation, arguments, expected_credentials=credentials)
    evidence = response_context(data)
    if not isinstance(evidence, dict) or type(evidence.get("version")) is not int or evidence["version"] != 1 \
            or evidence.get("operation") != operation or evidence.get("phase") != "after" or not isinstance(evidence.get("snapshot"), dict):
        raise BridgeError("MALFORMED_RESPONSE", "The composite read omitted its fresh native AFTER context.", outcome_unknown=False)
    current = evidence["snapshot"]
    _guard(current, session, "developer", world, protected)
    _RecipeTask.require_live_player(current)
    if type(current.get("world", {}).get("dimension")) is not int or current["world"]["dimension"] != dimension:
        raise BridgeError("WORLD_CHANGED", "The composite read changed dimension before its native AFTER observation.", outcome_unknown=False)
    if operation == "world.inspect_many":
        from ..workflows import _validate_player_inventory_observation
        _validate_player_inventory_observation(data, arguments, evidence)
    else:
        if "candidatePositions" in arguments:
            candidate = data.get("candidateObservation")
            if not isinstance(candidate, dict) or type(candidate.get("version")) is not int or candidate["version"] != 1 \
                    or candidate.get("source") != "client_public_structure_element_getter" or candidate.get("readOnly") is not True \
                    or candidate.get("ownerBound") is not True or candidate.get("captureMembershipVerified") is not True \
                    or candidate.get("captureId") != arguments["captureId"] or candidate.get("sessionId") != session \
                    or type(candidate.get("dimension")) is not int or candidate["dimension"] != dimension \
                    or any(type(candidate.get(axis)) is not int or candidate[axis] != arguments[axis] for axis in ("x", "y", "z")):
                raise BridgeError("MALFORMED_RESPONSE", "Exact-position candidates require their actual bound client observation; a server-only structure read is insufficient.", outcome_unknown=False)
        if arguments.get("includeSurvivalInventory") is True:
            from ..construction_tasks import _survival_inventory_entries
            inventory = data.get("serverPlayerInventory")
            _survival_inventory_entries(inventory)
            actor = current.get("serverControl", {}).get("playerId", current.get("player", {}).get("playerId"))
            if not isinstance(actor, str) or not actor or inventory.get("playerId") != actor or inventory.get("sessionId") != session \
                    or type(inventory.get("dimension")) is not int or inventory["dimension"] != dimension:
                raise BridgeError("MALFORMED_RESPONSE", "The complete finite inventory must belong to this native actor/session/dimension.", outcome_unknown=False)
    return data


async def recipe_pages(client: BridgeClient, arguments: dict[str, Any]) -> dict[str, Any]:
    args = dict(arguments)
    maximum = args.pop("maxPages", 1)
    pages = 0
    try:
        with anyio.fail_after(10):
            data = await client.invoke("recipes.query", args)
            pages = 1
            if maximum == 1:
                return data
            initial_context = (data.get("sessionId"), data.get("dimension"), data.get("source"))
            while pages < maximum and not data.get("recipes") and data.get("hasMore") is True:
                cursor = data.get("nextCursor")
                if not isinstance(cursor, str) or not cursor:
                    raise BridgeError("MALFORMED_RESPONSE", "An incomplete recipe page has no continuation cursor.")
                following = {"cursor": cursor}
                for key in ("dimension", "limit"):
                    if key in args:
                        following[key] = args[key]
                if "dimension" not in following and type(initial_context[1]) is int:
                    following["dimension"] = initial_context[1]
                data = await client.call("recipes.query", following)
                pages += 1
                if (data.get("sessionId"), data.get("dimension"), data.get("source")) != initial_context:
                    raise BridgeError("SESSION_CHANGED", "Recipe query context changed during the requested pages.")
            return {**data, "queryPages": pages, "maxPages": maximum}
    except TimeoutError:
        raise BridgeError("QUERY_TIMEOUT", "The requested recipe pages exceeded their 10-second deadline; do not replay a consumed cursor.",
                          pages_read=pages, cursor_outcome_unknown=True) from None


async def machine_capture_pages(client: BridgeClient, arguments: dict[str, Any]) -> dict[str, Any]:
    """Coalesce only pages of one frozen capture; never recapture or consume a production round."""
    args = dict(arguments)
    maximum = args.pop("maxPages", 4)
    data, pages, retained = None, 0, 0
    binding_keys = ("captureId", "sessionId", "worldFolder", "dimension", "worldTick", "endWorldTick", "totalEntries",
                    "capturedAtMillis", "targetCount", "targets")
    try:
        with anyio.fail_after(10):
            data = await client.invoke("machine.capture", args)
            pages, retained = 1, 1
            if (not _capture_page_valid(data, args.get("offset", 0)) or data.get("sessionId") != args["expectedSessionId"]
                    or "captureId" in args and data.get("captureId") != args["captureId"]
                    or "dimension" in args and data.get("dimension") != args["dimension"]):
                raise BridgeError("CAPTURE_CONTEXT_CHANGED", "Initial capture lacks its exact session/capture/dimension and frozen-page evidence.", outcome_unknown=False)
            binding = tuple(data.get(key) for key in binding_keys)
            while data.get("hasMore") is True and pages < maximum:
                following = {"captureId": data["captureId"], "offset": data["nextOffset"],
                    "expectedSessionId": args["expectedSessionId"], "expectedWorld": args["expectedWorld"],
                    "dimension": data["dimension"], "limit": args.get("limit", 64)}
                page = await client.call("machine.capture", following)
                pages += 1
                if tuple(page.get(key) for key in binding_keys) != binding or not _capture_page_valid(page, following["offset"]):
                    raise BridgeError("CAPTURE_CONTEXT_CHANGED", "A frozen capture page changed identity/tick or failed progress validation; no fresh capture substituted.", outcome_unknown=False)
                combined = {**page, "offset": data["offset"], "entries": [*data["entries"], *page["entries"]]}
                if len(json.dumps(combined, ensure_ascii=True).encode("utf-8")) > 512 * 1024:
                    data["autoPageByteLimited"] = True
                    break
                data = combined
                retained += 1
            return {**data, "queryPages": pages, "retainedPages": retained, "maxPages": maximum}
    except (BridgeError, TimeoutError) as error:
        raise BridgeError(getattr(error, "code", "CAPTURE_QUERY_TIMEOUT"),
            "Frozen capture paging stopped; retained pages remain partial, never combine a new capture.",
            outcome_unknown=False, retainedCapture=data, queryPages=pages,
            cause=error.as_dict() if isinstance(error, BridgeError) else {"code": "deadline"}) from None


def _capture_page_valid(page: dict[str, Any], offset: int) -> bool:
    if not isinstance(page, dict):
        return False
    total, following = page.get("totalEntries"), page.get("nextOffset")
    return (page.get("source") == "integrated_server_frozen_capture" and isinstance(page.get("captureId"), str) and bool(page["captureId"])
        and isinstance(page.get("sessionId"), str) and bool(page["sessionId"])
        and isinstance(page.get("worldFolder"), str) and bool(page["worldFolder"])
        and type(page.get("dimension")) is int and type(page.get("worldTick")) is int and page["worldTick"] >= 0
        and type(page.get("endWorldTick")) is int and page["endWorldTick"] == page["worldTick"]
        and type(page.get("capturedAtMillis")) is int and page["capturedAtMillis"] > 0
        and type(page.get("targetCount")) is int and 1 <= page["targetCount"] <= 8
        and isinstance(page.get("targets"), list) and len(page["targets"]) == page["targetCount"]
        and page.get("frozenAcrossPages") is True and page.get("sameWorldTick") is True
        and page.get("sameServerCallback") is True and page.get("captureTruncated") is False
        and type(total) is int and type(following) is int and type(page.get("offset")) is int and page["offset"] == offset
        and isinstance(page.get("entries"), list) and following == offset + len(page["entries"])
        and 0 <= offset <= following <= total and (following > offset or following == total)
        and page.get("hasMore") is (following < total) and page.get("complete") is (following == total))
