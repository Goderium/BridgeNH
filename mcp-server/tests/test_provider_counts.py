"""A provider counter is one exact local identity, never an aggregate or inferred zero."""

import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.provider_counts import LONG_MAX, PROVIDER_QUERY_PATH, provider_count, provider_delta_baseline, provider_match
from minecraft_codex_bridge.workflows import condition_matches, resolved_condition

OWNER = {"metaTileId": 910, "metaClass": "example.PublicLocalOutputProvider", "x": 9, "y": 20, "z": 20, "dimension": 180}
ITEM = {"registryName": "minecraft:iron_ingot", "metadata": 0}
MATCH = provider_match(OWNER, ITEM, "session")


def observation(count):
    return {**OWNER, "source": "integrated_server", "sessionId": "session", "outputStorage": {
        "cachedAmount": "999999", "resourceQuery": {"schemaVersion": 1, "source": "public_provider_cached_amount",
            "scope": "local_output_provider_cache_only", "requestedCount": 1, "complete": True,
            "owner": OWNER.copy(), "entries": [{"queryIndex": 0, "kind": "item", "available": True,
                "item": {**ITEM, "hasTag": False}, "cachedAmount": str(count)}]}}}


@pytest.mark.parametrize("count", [0, 123, 2**53 + 1, LONG_MAX])
def test_canonical_nonnegative_long_preserves_exact_quantity_and_ignores_aggregate(count):
    assert provider_count(observation(count), PROVIDER_QUERY_PATH, MATCH) == count


FAULTS = [
    ("schemaVersion", False), ("schemaVersion", 2), ("source", "aggregate"), ("scope", "network"),
    ("requestedCount", True), ("requestedCount", 2), ("complete", False), ("complete", 1), ("entries", []),
]


@pytest.mark.parametrize("key,value", FAULTS)
def test_incomplete_unknown_or_wrong_channel_is_never_zero(key, value):
    data = observation(0)
    data["outputStorage"]["resourceQuery"][key] = value
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("key", ["schemaVersion", "source", "scope", "requestedCount", "complete", "owner", "entries"])
def test_missing_query_field_is_unknown(key):
    data = observation(0)
    del data["outputStorage"]["resourceQuery"][key]
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("key,value", [("queryIndex", True), ("queryIndex", 1), ("kind", "fluid"), ("available", False), ("available", 1)])
def test_unavailable_or_misindexed_entry_cannot_authorize(key, value):
    data = observation(0)
    data["outputStorage"]["resourceQuery"]["entries"][0][key] = value
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("amount", [None, 0, True, -1, "-1", "+1", "01", "1.0", " 1", "1e3", str(LONG_MAX + 1), "9" * 1000])
def test_quantity_requires_exact_canonical_decimal_long_string(amount):
    data = observation(0)
    data["outputStorage"]["resourceQuery"]["entries"][0]["cachedAmount"] = amount
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("key,value", [("registryName", "minecraft:gold_ingot"), ("metadata", False), ("metadata", 1), ("hasTag", True), ("hasTag", None)])
def test_actual_item_identity_cannot_be_substituted(key, value):
    data = observation(0)
    data["outputStorage"]["resourceQuery"]["entries"][0]["item"][key] = value
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("where", ["actual", "query"])
@pytest.mark.parametrize("key,value", [("metaTileId", 911), ("metaTileId", True), ("metaClass", "other.Owner"),
    ("x", 10), ("y", 21), ("z", 19), ("dimension", 0)])
def test_every_real_owner_field_remains_pinned(where, key, value):
    data = observation(0)
    (data if where == "actual" else data["outputStorage"]["resourceQuery"]["owner"])[key] = value
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("session", [None, False, "replacement"])
def test_session_must_be_present_typed_and_unchanged(session):
    data = observation(0)
    data["sessionId"] = session
    with pytest.raises(BridgeError):
        provider_count(data, PROVIDER_QUERY_PATH, MATCH)


@pytest.mark.parametrize("change", ["missing", "extra", "tagged", "fluid", "bad_owner"])
def test_condition_match_requires_closed_identity_owner_and_session(change):
    match = MATCH.copy()
    if change == "missing":
        del match["owner.x"]
    elif change == "extra":
        match["other"] = 1
    elif change == "tagged":
        match["item.hasTag"] = True
    elif change == "fluid":
        match["kind"] = "fluid"
    else:
        match["owner.metaTileId"] = False
    with pytest.raises(BridgeError):
        provider_count(observation(0), PROVIDER_QUERY_PATH, match)


def test_delta_above_double_precision_is_exact_and_decrease_or_overshoot_fails_immediately():
    baseline = 2**53 + 1
    condition = resolved_condition({"condition": {"operator": "provider_count_delta", "path": PROVIDER_QUERY_PATH,
        "match": MATCH, "value": 4, "baseline": {"step": "baseline", "path": PROVIDER_QUERY_PATH}}},
        {"baseline": observation(baseline)})
    assert not condition_matches(observation(baseline + 3), condition)
    assert condition_matches(observation(baseline + 4), condition)
    for count in (baseline - 1, baseline + 5):
        with pytest.raises(BridgeError) as error:
            condition_matches(observation(count), condition)
        assert error.value.code == "PROVIDER_DELTA_CHANGED"


@pytest.mark.parametrize("delta", [True, -1, "-1", "01", LONG_MAX + 1, str(LONG_MAX + 1)])
def test_invalid_delta_never_becomes_an_implicit_zero(delta):
    with pytest.raises(BridgeError):
        provider_delta_baseline(observation(0), PROVIDER_QUERY_PATH, MATCH, delta)


def test_long_overflow_refuses_baseline_before_write():
    with pytest.raises(BridgeError) as error:
        provider_delta_baseline(observation(LONG_MAX), PROVIDER_QUERY_PATH, MATCH, 1)
    assert error.value.details["reason"] == "provider_delta_overflow"
