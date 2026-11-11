"""Public control reads drain only verified raw-tree suffixes, without writes."""

import copy

import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.workflows import run_workflow

native_construction = native.native_construction


def page(offset, following, total, widgets, **changes):
    return {"screenId": "screen:control", "widgets": widgets, "offset": offset,
        "nextOffset": following, "visited": total, "rawScanned": following - offset,
        "hasMore": following < total, "partial": False, "scanTruncated": False,
        "byteLimited": False, "actionableOnly": True, **changes}


def reader(monkeypatch, client, pages):
    task = _RecipeTask(client, native.declaration(), run_workflow)
    requests = []

    async def read(operation, arguments):
        assert operation == "gui.widgets"
        requests.append(copy.deepcopy(arguments))
        return copy.deepcopy(pages[len(requests) - 1])

    monkeypatch.setattr(task, "call", read)
    return task, requests


@pytest.mark.parametrize("first_rows", [[], [{"widgetId": "first"}]])
@pytest.mark.parametrize("limit_flag", ["partial", "byteLimited"])
async def test_filtered_control_prefix_continues_from_raw_offset(native_construction, monkeypatch, first_rows, limit_flag):
    client, state = native_construction
    task, requests = reader(monkeypatch, client, [
        page(0, 56, 70, first_rows, **{limit_flag: True}),
        page(56, 70, 70, [{"widgetId": "tail"}])])
    result = await task.control_widgets("screen:control", semantic="structure_recheck")
    assert result["widgets"] == [*first_rows, {"widgetId": "tail"}]
    assert requests == [{"screenId": "screen:control", "actionableOnly": True,
        "offset": offset, "limit": 128, "semantic": "structure_recheck"} for offset in (0, 56)]
    assert result["widgetPageCoverage"] == {"pages": 2, "rawVisited": 70,
        "partialPages": int(limit_flag == "partial"), "byteLimitedPages": int(limit_flag == "byteLimited"), "nonAtomic": True}
    assert not native.writes(state)


@pytest.mark.parametrize("fault", ["screen", "offset", "offset_bool", "stalled", "total_changed", "next_bool",
    "raw_count", "filtered_count", "filter", "missing_flag", "scan_truncated", "terminal_partial",
    "terminal_bytes", "duplicate_id", "false_terminal", "past_total", "more_at_total"])
async def test_inconsistent_widget_suffix_refuses_without_retry(native_construction, monkeypatch, fault):
    client, state = native_construction
    pages = [page(0, 56, 70, [{"widgetId": "first"}], partial=True),
        page(56, 70, 70, [{"widgetId": "tail"}])]
    tail = pages[-1]
    if fault == "screen":
        tail["screenId"] = "screen:other"
    elif fault == "offset":
        tail["offset"] = 1
    elif fault == "offset_bool":
        tail["offset"] = False
    elif fault == "stalled":
        tail.update(nextOffset=56, rawScanned=0, hasMore=True, widgets=[])
    elif fault == "total_changed":
        tail.update(nextOffset=71, visited=71, rawScanned=15)
    elif fault == "next_bool":
        tail["nextOffset"] = True
    elif fault == "raw_count":
        tail["rawScanned"] = 8
    elif fault == "filtered_count":
        tail["widgets"] = [{"widgetId": str(index)} for index in range(15)]
    elif fault == "filter":
        tail["actionableOnly"] = False
    elif fault == "missing_flag":
        tail.pop("byteLimited")
    elif fault == "scan_truncated":
        tail["scanTruncated"] = True
    elif fault == "terminal_partial":
        tail["partial"] = True
    elif fault == "terminal_bytes":
        tail["byteLimited"] = True
    elif fault == "duplicate_id":
        tail["widgets"][0]["widgetId"] = "first"
    elif fault == "false_terminal":
        tail.update(nextOffset=60, rawScanned=4)
    elif fault == "past_total":
        tail.update(nextOffset=71, rawScanned=15)
    else:
        tail["hasMore"] = True
    task, requests = reader(monkeypatch, client, pages)
    with pytest.raises(BridgeError) as caught:
        await task.control_widgets("screen:control")
    assert caught.value.details["reason"] == "native_widget_coverage_incomplete"
    assert [request["offset"] for request in requests] == [0, 56]
    assert not native.writes(state)


async def test_legacy_single_complete_page_keeps_specific_semantic_ambiguity_available(native_construction, monkeypatch):
    client, _ = native_construction
    legacy = {"screenId": "screen:control", "widgets": [{"widgetId": "duplicate"}, {"widgetId": "duplicate"}],
        "hasMore": False, "partial": False, "scanTruncated": False, "byteLimited": False}
    task, requests = reader(monkeypatch, client, [legacy])
    result = await task.control_widgets("screen:control")
    assert result["widgets"] == legacy["widgets"] and len(requests) == 1
    assert result["widgetPageCoverage"]["rawVisited"] is None


@pytest.mark.parametrize("fault", ["missing_metadata", "missing_complete_flag", "non_boolean_flag", "too_many_rows", "bytes", "rows"])
async def test_widget_retention_and_explicit_coverage_bounds(native_construction, monkeypatch, fault):
    client, _ = native_construction
    pages = [page(0, 1, 1, [{"widgetId": "first"}])]
    if fault == "missing_metadata":
        pages[0].pop("rawScanned")
    elif fault == "missing_complete_flag":
        pages[0].pop("partial")
    elif fault == "non_boolean_flag":
        pages[0]["hasMore"] = 0
    elif fault == "too_many_rows":
        pages = [page(0, 129, 129, [{"widgetId": str(index)} for index in range(129)])]
    elif fault == "bytes":
        pages[0]["widgets"][0]["text"] = "x" * 2_097_152
    else:
        pages = [page(offset, offset + 128, 4224, [{"widgetId": str(index)}
            for index in range(offset, offset + 128)]) for offset in range(0, 4224, 128)]
    task, requests = reader(monkeypatch, client, pages)
    with pytest.raises(BridgeError) as caught:
        await task.control_widgets("screen:control")
    assert caught.value.details["reason"] == "native_widget_coverage_incomplete"
    assert len(requests) == (33 if fault == "rows" else 1)


@pytest.mark.parametrize("fault", [None, "repeat_budget", "screen", "session", "world", "dimension", "background", "health",
    "unknown_budget", "missing_budget", "tree_error", "malformed_offset", "malformed_flag"])
async def test_one_explicit_budget_refresh_needs_same_fresh_native_screen_context(native_construction, monkeypatch, fault):
    client, state = native_construction
    task = _RecipeTask(client, native.declaration(), run_workflow)
    task.dimension = 0
    prefix = page(0, 1, 1, [{"widgetId": "discarded-prefix"}], scanTruncated=True, partial=True, hasMore=True,
        budget={"softBudgetMicros": 4000, "actualElapsedMicros": 5001, "softBudgetExceeded": True})
    if fault == "unknown_budget": prefix["budget"]["softBudgetExceeded"] = None
    elif fault == "missing_budget": prefix.pop("budget")
    elif fault == "tree_error": prefix["coverage"] = {"treeError": "unsupported_native_tree"}
    elif fault == "malformed_offset": prefix["offset"] = False
    elif fault == "malformed_flag": prefix["partial"] = 1
    snapshot = {"sessionId": "session", "profile": "developer", "worldLoaded": True,
        "world": {"worldName": "new-world", "dimension": 0}, "player": {"health": 20},
        "background": copy.deepcopy(native.BACKGROUND), "screenOpen": True, "screenId": "screen:control"}
    if fault == "screen": snapshot["screenId"] = "screen:other"
    elif fault == "session": snapshot["sessionId"] = "other-session"
    elif fault == "world": snapshot["world"]["worldName"] = "other-world"
    elif fault == "dimension": snapshot["world"]["dimension"] = 1
    elif fault == "background": snapshot["background"]["enabled"] = False
    elif fault == "health": snapshot["player"]["health"] = 0
    calls = []

    async def read(operation, args):
        calls.append((operation, copy.deepcopy(args)))
        if operation == "client.snapshot": return copy.deepcopy(snapshot)
        assert operation == "gui.widgets"
        count = sum(row[0] == "gui.widgets" for row in calls)
        return copy.deepcopy(prefix if count == 1 or fault == "repeat_budget" else page(0, 1, 1, [{"widgetId": "fresh-control"}]))

    monkeypatch.setattr(task, "call", read)
    if fault is None:
        value = await task.control_widgets("screen:control")
        assert value["widgets"] == [{"widgetId": "fresh-control"}]
        assert value["widgetPageCoverage"]["refreshes"] == 1 and value["widgetPageCoverage"]["pages"] == 1
        assert [row[0] for row in calls] == ["gui.widgets", "client.snapshot", "gui.widgets"]
        assert [row[1]["offset"] for row in calls if row[0] == "gui.widgets"] == [0, 0]
    else:
        with pytest.raises(BridgeError): await task.control_widgets("screen:control")
        assert sum(row[0] == "gui.widgets" for row in calls) == (2 if fault == "repeat_budget" else 1)
    assert not native.writes(state)
