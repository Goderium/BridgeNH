"""Coverage remains explicit when unrelated projection data exhausts its budget."""

import itertools

import pytest

from minecraft_codex_bridge import workflows


def expired_clock(monkeypatch):
    ticks = itertools.chain([0.0], itertools.repeat(100.0))
    monkeypatch.setattr(workflows.time, "monotonic", lambda: next(ticks))


@pytest.mark.parametrize("wrapper", [None, "after", "processing"])
def test_expired_metadata_budget_keeps_root_and_nearby_coverage(monkeypatch, wrapper):
    expired_clock(monkeypatch)
    coverage = {"partial": True, "hasMore": True, "nextCursor": "actual-next"}
    data = {"submitted": True, "before": {"tooltip": ["large"] * 1000},
        "byteLimited": True, "loaded": False}
    data.update({"coverage": coverage} if wrapper is None else {wrapper: {"coverage": coverage}})

    compact = workflows.project(data, {"kind": "action", "tool": "mcb_gui_widget_action"})
    prefix = "coverage" if wrapper is None else wrapper + ".coverage"

    assert compact["values"] == {"submitted": True}
    assert compact["metadata"]["byteLimited"] is True
    assert compact["metadata"][prefix + ".partial"] is True
    assert compact["metadata"][prefix + ".nextCursor"] == "actual-next"
    assert compact["coverageSummary"]["partial"] is True
    assert compact["coverageSummary"]["pagination"] is True
    assert compact["coverageSummary"]["unknownOrUnloaded"] is True
    assert compact["coverageSummary"]["metadataOmitted"] is True


def test_expired_budget_does_not_turn_unvisited_coverage_into_completeness(monkeypatch):
    expired_clock(monkeypatch)
    data = {"before": {"tooltip": ["large"] * 1000}, "coverage": {"partial": False}}

    metadata, summary = workflows._metadata(data)

    assert "coverage.partial" not in metadata
    assert summary["falseFlagsSummarized"] is True
    assert summary["partial"] is False
    assert summary["metadataOmitted"] is True
    assert summary["unknownOrUnloaded"] is True


def test_unrecognized_nearby_partial_flag_precedes_bulk_list(monkeypatch):
    expired_clock(monkeypatch)
    data = {"before": {"tooltip": ["large"] * 1000},
        "after": {"detail": {"positionsTruncated": True}}}

    metadata, summary = workflows._metadata(data)

    assert metadata["after.detail.positionsTruncated"] is True
    assert summary["partial"] is True
    assert summary["metadataOmitted"] is True


def test_node_limit_stops_before_reading_unbudgeted_metadata(monkeypatch):
    monkeypatch.setattr(workflows.time, "monotonic", lambda: 0.0)
    monkeypatch.setattr(workflows, "MAX_METADATA_NODES", 4)
    serialized = []
    original_size = workflows.json_size
    monkeypatch.setattr(workflows, "json_size", lambda value: serialized.append(value) or original_size(value))

    metadata, summary = workflows._metadata({"coverage": {"partial": True, "hasMore": True,
        "nextCursor": "actual-next"}, "before": {"source": "unbudgeted"}})

    assert metadata == {"coverage.partial": True, "coverage.hasMore": True,
        "coverage.nextCursor": "actual-next"}
    assert len(serialized) == 3 and "unbudgeted" not in serialized
    assert summary["partial"] is True
    assert summary["metadataOmitted"] is True
    assert summary["unknownOrUnloaded"] is True


def test_depth_limit_keeps_unvisited_coverage_unknown(monkeypatch):
    monkeypatch.setattr(workflows.time, "monotonic", lambda: 0.0)
    data = {"partial": True}
    for _ in range(34):
        data = {"child": data}

    metadata, summary = workflows._metadata(data)

    assert metadata == {}
    assert summary["partial"] is False
    assert summary["metadataOmitted"] is True
    assert summary["unknownOrUnloaded"] is True


def test_metadata_value_and_entry_limits_remain_bounded(monkeypatch):
    monkeypatch.setattr(workflows.time, "monotonic", lambda: 0.0)
    data = {"source": "oversized" * 2000,
        "rows": [{"source": "actual-" + str(index)} for index in range(400)]}

    metadata, summary = workflows._metadata(data)

    assert "source" not in metadata
    assert len(metadata) == 256
    assert all(workflows.json_size(value) <= 8192 for value in metadata.values())
    assert summary["metadataOmitted"] is True
