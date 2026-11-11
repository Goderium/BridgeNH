"""A workflow validates every provider poll and its exact baseline before any following action."""

import copy

import anyio
import jsonschema
import pytest

import test_recipe_tasks as native
from test_recipe_provider_cache import provider_bridge
from test_provider_counts import OWNER
from minecraft_codex_bridge.provider_counts import LONG_MAX, PROVIDER_QUERY_PATH, provider_match
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


def plan():
    args = {**native.OUTPUT, "dimension": 180, "outputQueries": [{"kind": "item", **native.PRODUCT}]}
    return {"expectedSessionId": "session", "expectedWorld": "test-world", "timeout_ms": 1000, "steps": [
        {"id": "baseline", "kind": "read", "tool": "mcb_machine_inspect", "args": args},
        {"id": "one_action", "kind": "action", "tool": "mcb_close_gui", "args": {}},
        {"id": "growth", "kind": "wait", "tool": "mcb_machine_inspect", "args": args,
            "condition": {"operator": "provider_count_delta", "path": PROVIDER_QUERY_PATH, "value": 1,
                "match": provider_match({**OWNER, **native.OUTPUT}, native.PRODUCT, "session"),
                "baseline": {"step": "baseline", "path": PROVIDER_QUERY_PATH}}}]}


@pytest.mark.parametrize("count", [0, 123, 2**53 + 1])
async def test_public_workflow_exact_provider_delta_preserves_long_baseline_without_replaying_action(provider_bridge, monkeypatch, count):
    client, state = provider_bridge
    state["provider_base"] = count
    original = client.call
    async def grow(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "gui.close":
            state["output"] += 1
        return value
    monkeypatch.setattr(client, "call", grow)
    report = await run_workflow(client, plan(), anyio.Lock())
    assert report["status"] == "completed", report
    quantity = report["steps"][2]["quantityDelta"]
    assert quantity["baseline"] == str(count) and quantity["current"] == str(count + 1) and quantity["delta"] == "1"
    assert quantity["expectedDelta"] == "1" and quantity["observedDelta"] == "1"
    assert quantity["scope"] == "local_output_provider_cache_only"
    assert sum(call["operation"] == "gui.close" for call in state["calls"]) == 1 and len(state["provider_queries"]) == 2


@pytest.mark.parametrize("fault", ["incomplete", "unknown", "overflow", "wrong_owner"])
async def test_bad_provider_baseline_blocks_following_action(provider_bridge, fault):
    client, state = provider_bridge
    def fail(data, _):
        query = data["outputStorage"]["resourceQuery"]
        if fault == "incomplete":
            query["complete"] = False
        elif fault == "unknown":
            query["entries"][0]["available"] = False
        elif fault == "overflow":
            query["entries"][0]["cachedAmount"] = str(LONG_MAX)
        else:
            query["owner"]["metaTileId"] += 1
    state["provider_hook"] = fail
    report = await run_workflow(client, plan(), anyio.Lock())
    assert report["status"] != "completed" and report["dispatchedActions"] == 0, report
    assert native.writes(state) == [] and len(state["provider_queries"]) == 1
    assert all("quantityDelta" not in step for step in report["steps"])


@pytest.mark.parametrize("fault", ["decrease", "overshoot", "incomplete", "missing", "unknown", "session", "owner"])
async def test_each_poll_revalidates_provider_and_stops_on_mutation_without_replaying_action(provider_bridge, fault):
    client, state = provider_bridge
    state["provider_base"] = 10
    def fail(data, state):
        if len(state["provider_queries"]) > 1:
            query = data["outputStorage"]["resourceQuery"]
            if fault == "decrease":
                query["entries"][0]["cachedAmount"] = "9"
            elif fault == "overshoot":
                query["entries"][0]["cachedAmount"] = "12"
            elif fault == "incomplete":
                query["complete"] = False
            elif fault == "missing":
                del data["outputStorage"]["resourceQuery"]
            elif fault == "unknown":
                query["entries"][0]["available"] = False
            elif fault == "session":
                data["sessionId"] = "replacement"
            else:
                data["metaTileId"] = query["owner"]["metaTileId"] = 911
    state["provider_hook"] = fail
    report = await run_workflow(client, plan(), anyio.Lock())
    assert report["status"] != "completed" and report["dispatchedActions"] == 1, report
    assert len(state["provider_queries"]) == 2 and sum(call["operation"] == "gui.close" for call in state["calls"]) == 1
    if fault in {"decrease", "overshoot"}:
        quantity = report["steps"][2]["quantityDelta"]
        assert quantity["delta"] == quantity["expectedDelta"] == "1"
        assert quantity["observedDelta"] == ("-1" if fault == "decrease" else "2")
    else:
        assert "quantityDelta" not in report["steps"][2]


@pytest.mark.parametrize("progress", [0, 1])
async def test_provider_timeout_distinguishes_expected_from_exact_observed_delta_without_replaying_action(provider_bridge, monkeypatch, progress):
    client, state = provider_bridge
    baseline = 2**53 + 1
    state["provider_base"] = baseline
    original = client.call
    async def grow(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "gui.close":
            state["output"] += progress
        return value
    monkeypatch.setattr(client, "call", grow)
    request = plan()
    request["steps"][2].update(timeout_ms=150, interval_ms=50)
    request["steps"][2]["condition"]["value"] = 2
    request["steps"].append({"id": "later_action", "kind": "action", "tool": "mcb_close_gui", "args": {}})
    report = await run_workflow(client, request, anyio.Lock())
    assert report["status"] != "completed" and report["error"]["code"] == "WAIT_TIMEOUT", report
    quantity = report["steps"][2]["quantityDelta"]
    assert quantity["baseline"] == str(baseline) and quantity["current"] == str(baseline + progress)
    assert quantity["delta"] == quantity["expectedDelta"] == "2"
    assert quantity["observedDelta"] == str(progress)
    assert report["steps"][3]["status"] == "not_executed"
    assert sum(call["operation"] == "gui.close" for call in state["calls"]) == 1


@pytest.mark.parametrize("fault", ["extra", "missing", "tagged", "negative_delta", "noncanonical", "huge_delta"])
def test_provider_condition_schema_remains_closed_and_exact(fault):
    request = plan()
    condition = request["steps"][2]["condition"]
    if fault == "extra":
        condition["match"]["unexpected"] = True
    elif fault == "missing":
        del condition["match"]["owner.x"]
    elif fault == "tagged":
        condition["match"]["item.hasTag"] = True
    else:
        condition["value"] = -1 if fault == "negative_delta" else "01" if fault == "noncanonical" else LONG_MAX + 1
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(request)


def test_existing_inventory_match_limit_is_not_expanded_by_provider_support():
    request = plan()
    condition = request["steps"][2]["condition"]
    condition.update(operator="item_count_delta", match={"field" + str(index): 1 for index in range(9)})
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_sequence").validate(request)
