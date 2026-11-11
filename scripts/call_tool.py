"""Source-checkout wrapper for the shared installed tool runner."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp-server/src"))
from minecraft_codex_bridge.tool_cli import cli

if __name__ == "__main__":
    raise SystemExit(cli())
