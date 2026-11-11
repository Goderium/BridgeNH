"""Exact quantities for one explicitly queried local output-provider identity."""

import re
from typing import Any

from .bridge import BridgeError
from .conveniences import path_get

LONG_MAX = 2**63 - 1
PROVIDER_QUERY_PATH = "outputStorage.resourceQuery"
OWNER_KEYS = ("metaTileId", "metaClass", "x", "y", "z", "dimension")
MATCH_KEYS = frozenset({"kind", "item.registryName", "item.metadata", "item.hasTag", "sessionId",
    *("owner." + key for key in OWNER_KEYS)})


def _require(condition: bool, reason: str) -> None:
    if not condition:
        raise BridgeError("PROVIDER_COUNT_UNAVAILABLE", "Exact local output-provider quantity evidence is unavailable.", reason=reason)


def provider_match(owner: dict[str, Any], item: dict[str, Any], session: str) -> dict[str, Any]:
    return {"kind": "item", "item.registryName": item["registryName"], "item.metadata": item["metadata"],
        "item.hasTag": False, "sessionId": session, **{"owner." + key: owner[key] for key in OWNER_KEYS}}


def provider_count(observation: Any, path: str, match: dict[str, Any]) -> int:
    """Never count an aggregate, inserted cell or network; require this same actual owner/session every read."""
    _require(isinstance(match, dict) and match.keys() == MATCH_KEYS and match.get("kind") == "item"
        and isinstance(match.get("item.registryName"), str) and 1 <= len(match["item.registryName"]) <= 256
        and type(match.get("item.metadata")) is int and 0 <= match["item.metadata"] <= 32766
        and match.get("item.hasTag") is False and isinstance(match.get("sessionId"), str)
        and 1 <= len(match["sessionId"]) <= 128, "provider_match_invalid")
    expected_owner = {key: match["owner." + key] for key in OWNER_KEYS}
    _require(type(expected_owner["metaTileId"]) is int and 0 <= expected_owner["metaTileId"] <= 32767
        and isinstance(expected_owner["metaClass"], str) and 1 <= len(expected_owner["metaClass"]) <= 256
        and all(type(expected_owner[key]) is int for key in ("x", "y", "z", "dimension"))
        and -30000000 <= expected_owner["x"] <= 30000000 and -30000000 <= expected_owner["z"] <= 30000000
        and 0 <= expected_owner["y"] <= 255 and -2**31 <= expected_owner["dimension"] <= 2**31 - 1,
        "provider_owner_invalid")
    _require(isinstance(observation, dict) and observation.get("source") == "integrated_server"
        and observation.get("sessionId") == match["sessionId"], "provider_session_or_source_changed")
    _require(all(type(observation.get(key)) is type(expected) and observation[key] == expected
        for key, expected in expected_owner.items()), "provider_actual_owner_changed")
    exists, query = path_get(observation, path)
    _require(exists and isinstance(query, dict) and type(query.get("schemaVersion")) is int and query["schemaVersion"] == 1
        and query.get("source") == "public_provider_cached_amount"
        and query.get("scope") == "local_output_provider_cache_only"
        and type(query.get("requestedCount")) is int and query["requestedCount"] == 1
        and query.get("complete") is True, "provider_query_incomplete")
    owner = query.get("owner")
    _require(isinstance(owner, dict) and all(type(owner.get(key)) is type(expected) and owner[key] == expected
        for key, expected in expected_owner.items()), "provider_query_owner_changed")
    entries = query.get("entries")
    _require(isinstance(entries, list) and len(entries) == 1 and isinstance(entries[0], dict), "provider_entries_incomplete")
    entry = entries[0]
    _require(type(entry.get("queryIndex")) is int and entry["queryIndex"] == 0 and entry.get("kind") == "item"
        and entry.get("available") is True, "provider_entry_unavailable")
    item = entry.get("item")
    _require(isinstance(item, dict) and item.get("registryName") == match["item.registryName"]
        and type(item.get("metadata")) is int and item["metadata"] == match["item.metadata"]
        and item.get("hasTag") is False, "provider_item_identity_changed")
    amount = entry.get("cachedAmount")
    _require(isinstance(amount, str) and len(amount) <= 19 and re.fullmatch(r"(?:0|[1-9][0-9]*)", amount) is not None,
        "provider_quantity_invalid")
    count = int(amount)
    _require(count <= LONG_MAX, "provider_quantity_overflow")
    return count


def provider_delta(value: Any) -> int:
    _require(type(value) is int or isinstance(value, str) and len(value) <= 19
        and re.fullmatch(r"(?:0|[1-9][0-9]*)", value) is not None, "provider_delta_invalid")
    delta = int(value)
    _require(0 <= delta <= LONG_MAX, "provider_delta_invalid")
    return delta


def provider_delta_baseline(observation: Any, path: str, match: dict[str, Any], value: Any) -> int:
    count, delta = provider_count(observation, path, match), provider_delta(value)
    _require(count <= LONG_MAX - delta, "provider_delta_overflow")
    return count
