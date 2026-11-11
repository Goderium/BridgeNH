"""Read only the fixed packaged agent guides, independent of a game session."""

from importlib.resources import files
from pathlib import Path
from typing import Any

from .bridge import BridgeError
from .conveniences import json_size

MAX_GUIDE_BYTES = 32_768


def _packaged_guides():
    # Windows app virtualization can alias the installed package's parent path.
    # Canonicalize that trusted package root once, then keep the guides directory
    # and individual filenames fixed so redirects below it remain refused.
    package = files("minecraft_codex_bridge")
    if isinstance(package, Path):
        package = package.resolve()
    return package.joinpath("guides")


AGENT_GUIDES_DIR = _packaged_guides()
GUIDE_FILES = {"overview": "overview.md", "capabilities": "capabilities.md", "maintenance": "maintenance.md", "workspace": "workspace.md", "testing": "testing.md", "operations": "operations.md", "nei": "nei.md", "ae": "ae.md"}
GUIDE_RESOURCES = {f"minecraft-codex-bridge://agent-guides/{topic}": topic for topic in GUIDE_FILES}
APPLICABILITY = {
    "minecraft": "1.7.10", "modpack": "GT New Horizons",
    "variants": "GTNH builds of NEI and AE2; exact tested versions and client scope are stated in the guide.",
}


def read_help(arguments: dict[str, Any]) -> dict[str, Any]:
    topic = arguments.get("topic", "overview")
    if topic not in GUIDE_FILES:
        raise BridgeError("INVALID_ARGUMENTS", "Choose a fixed guide topic: " + ", ".join(GUIDE_FILES) + "; arbitrary guide paths are forbidden.")
    filename = GUIDE_FILES[topic]
    path = AGENT_GUIDES_DIR / filename
    try:
        if isinstance(path, Path) and path.resolve(strict=True) != path:
            raise BridgeError("GUIDE_PATH_REFUSED", "The fixed guide path may not redirect to another file or directory.")
        with path.open("rb") as stream:
            raw = stream.read(MAX_GUIDE_BYTES + 1)
        if len(raw) > MAX_GUIDE_BYTES:
            raise BridgeError("GUIDE_TOO_LARGE", "The local agent guide exceeds the 32 KiB response limit.")
        markdown = raw.decode("utf-8-sig")
    except BridgeError:
        raise
    except UnicodeError:
        raise BridgeError("GUIDE_ENCODING_ERROR", "The local agent guide must be valid UTF-8 Markdown.") from None
    except OSError:
        raise BridgeError("GUIDE_UNAVAILABLE", "The fixed local agent guide is missing or cannot be read.", topic=topic) from None
    result = {"topic": topic, "source": f"mcp-server/src/minecraft_codex_bridge/guides/{filename}", "applicability": APPLICABILITY.copy(), "markdown": markdown}
    if json_size(result) > MAX_GUIDE_BYTES:
        raise BridgeError("GUIDE_TOO_LARGE", "The local agent guide exceeds the 32 KiB response limit.")
    return result
