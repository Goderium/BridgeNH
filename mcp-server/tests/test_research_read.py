"""Research reads retain ambiguity/knowledge/coverage instead of manufacturing prerequisites."""

from copy import deepcopy

import pytest

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.tools import OBSERVATION_NAMES, SEQUENCE_READ_NAMES, TOOL_BY_NAME
from test_workflows import bridge, operations


@pytest.mark.parametrize("args", [
    {"researchKey": "RESEARCH_EXAMPLE"},
    {"title": "公开研究名称", "category": "ADDON", "section": "summary"},
    {"researchKey": "EXAMPLE", "section": "pages", "offset": 3, "limit": 16},
    {"researchKey": "EXAMPLE", "section": "page_text", "pageIndex": 1, "offset": 4096, "limit": 8192},
    {"title": "Example", "section": "dependencies", "limit": 32},
    {"researchKey": "EXAMPLE", "section": "page_links", "pageIndex": 0, "limit": 32},
])
async def test_exact_research_request_preserves_public_partial_evidence_without_takeover(bridge, args):
    adapter, state = bridge
    evidence = {"status": "locked", "complete": False, "hasMore": False,
        "researchKey": "EXAMPLE", "title": "公开研究名称", "knowledgeSource": "current_player",
        "pagesVisible": False, "reason": "Player-visible pages unavailable; no research granted."}
    state["data"]["research.read"] = deepcopy(evidence)
    result = await adapter.call_tool("mcb_research", args)
    assert not result.isError and result.structuredContent["data"] == evidence
    assert operations(state) == ["bridge.health", "research.read"]
    assert state["calls"][-1]["args"] == args
    assert state["background"]["enabled"] is False


@pytest.mark.parametrize("args", [
    {}, {"researchKey": "a", "title": "b"}, {"title": ""}, {"researchKey": True},
    {"researchKey": "a", "grant": True}, {"researchKey": "a", "pageIndex": 0},
    {"title": "a", "section": "page_text"}, {"title": "a", "section": "page_links"},
    {"title": "a", "section": "summary", "offset": 1}, {"title": "a", "limit": 2},
    {"researchKey": "a", "section": "pages", "limit": 33},
    {"title": "a", "section": "dependencies", "pageIndex": 0},
])
async def test_invalid_or_ambiguous_selector_cannot_dispatch_research_read(bridge, args):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_research", args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert operations(state) == []


def test_research_is_observable_and_never_implicit_control():
    spec = TOOL_BY_NAME["mcb_research"]
    assert spec.operation in READ_OPERATIONS and spec.read_only and not spec.takeover
    assert spec.name in OBSERVATION_NAMES and spec.name in SEQUENCE_READ_NAMES
