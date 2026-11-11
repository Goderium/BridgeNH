"""Fixed local guide mapping and strict bounds, using fixture Markdown files."""

import json
from collections import Counter
from pathlib import Path
import re

import pytest

from minecraft_codex_bridge import guides
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import Adapter


@pytest.fixture
def local_guides(tmp_path, monkeypatch):
    directory = tmp_path / "agent-guides"
    directory.mkdir()
    monkeypatch.setattr(guides, "AGENT_GUIDES_DIR", directory)
    for topic, filename in guides.GUIDE_FILES.items():
        (directory / filename).write_text(f"# {topic}\n\n中文指南: Minecraft 1.7.10.\n", encoding="utf-8-sig", newline="")
    return directory


class NoGameClient:
    async def invoke(self, *args):
        pytest.fail("Reading a local guide must not invoke the game or require runtime credentials")


@pytest.mark.parametrize("topic", list(guides.GUIDE_FILES))
async def test_help_returns_markdown_and_applicability_without_game(local_guides, topic):
    result = await Adapter(NoGameClient()).call_tool("mcb_help", {"topic": topic})
    assert not result.isError
    data = result.structuredContent["data"]
    assert data["topic"] == topic
    assert data["source"] == f"mcp-server/src/minecraft_codex_bridge/guides/{topic}.md"
    assert data["markdown"].startswith(f"# {topic}\n")
    assert "中文指南" in data["markdown"]
    assert data["applicability"]["minecraft"] == "1.7.10"
    assert data["applicability"]["modpack"] == "GT New Horizons"
    assert len(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= guides.MAX_GUIDE_BYTES


async def test_help_defaults_to_overview(local_guides):
    result = await Adapter(NoGameClient()).call_tool("mcb_help", {})
    assert result.structuredContent["data"]["topic"] == "overview"


@pytest.mark.parametrize("arguments", [{"topic": "../secret"}, {"topic": "other"}, {"path": "secret.md"}, {"topic": True}])
async def test_help_rejects_arbitrary_paths_and_unknown_topics(local_guides, arguments):
    result = await Adapter(NoGameClient()).call_tool("mcb_help", arguments)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"


async def test_help_missing_and_invalid_utf8_are_clear_errors(local_guides):
    (local_guides / "overview.md").unlink()
    missing = await Adapter(NoGameClient()).call_tool("mcb_help", {})
    assert missing.isError and missing.structuredContent["error"]["code"] == "GUIDE_UNAVAILABLE"
    (local_guides / "overview.md").write_bytes(b"\xff\xfeinvalid")
    invalid = await Adapter(NoGameClient()).call_tool("mcb_help", {})
    assert invalid.isError and invalid.structuredContent["error"]["code"] == "GUIDE_ENCODING_ERROR"


@pytest.mark.parametrize("content", [b"x" * (guides.MAX_GUIDE_BYTES + 1), b"\t" * 17_000], ids=["raw_limit", "encoded_limit"])
async def test_help_bounds_raw_read_and_serialized_response(local_guides, content):
    (local_guides / "overview.md").write_bytes(content)
    result = await Adapter(NoGameClient()).call_tool("mcb_help", {})
    assert result.isError and result.structuredContent["error"]["code"] == "GUIDE_TOO_LARGE"


def test_help_refuses_redirected_fixed_path(local_guides, monkeypatch):
    original_resolve = Path.resolve

    def redirected(path, **kwargs):
        if path == local_guides / "overview.md":
            return local_guides / "different.md"
        return original_resolve(path, **kwargs)

    monkeypatch.setattr(Path, "resolve", redirected)
    with pytest.raises(BridgeError) as error:
        guides.read_help({})
    assert error.value.code == "GUIDE_PATH_REFUSED"


def test_help_normalizes_virtualized_package_root(tmp_path, monkeypatch):
    alias = tmp_path / "virtual-install" / "minecraft_codex_bridge"
    installed = tmp_path / "actual-install" / "minecraft_codex_bridge"
    (installed / "guides").mkdir(parents=True)
    (installed / "guides/overview.md").write_text("Packaged guide", encoding="utf-8")
    original_resolve = Path.resolve

    def virtualized(path, **kwargs):
        if path == alias:
            return installed
        return original_resolve(path, **kwargs)

    monkeypatch.setattr(guides, "files", lambda _package: alias)
    monkeypatch.setattr(Path, "resolve", virtualized)
    monkeypatch.setattr(guides, "AGENT_GUIDES_DIR", guides._packaged_guides())
    assert guides.read_help({})["markdown"] == "Packaged guide"


def test_help_refuses_redirected_guide_directory(local_guides, monkeypatch):
    original_resolve = Path.resolve

    def redirected(path, **kwargs):
        if path.parent == local_guides:
            return local_guides.parent / "unrelated" / path.name
        return original_resolve(path, **kwargs)

    monkeypatch.setattr(Path, "resolve", redirected)
    with pytest.raises(BridgeError) as error:
        guides.read_help({})
    assert error.value.code == "GUIDE_PATH_REFUSED"


def test_bundled_capability_index_covers_every_public_tool_once():
    from minecraft_codex_bridge.application.descriptions import tool_descriptions
    from minecraft_codex_bridge.frontend import management_tools

    expected = Counter(tool.name for tool in [*tool_descriptions(), *management_tools()])
    assert all(count == 1 for count in expected.values())
    for filename in ("capabilities.md", "capabilities_zh.md"):
        content = guides._packaged_guides().joinpath(filename).read_text(encoding="utf-8")
        indexed = Counter(re.findall(r"^\| `(mcb_[a-z0-9_]+)` \|", content, re.MULTILINE))
        assert indexed == expected, filename
