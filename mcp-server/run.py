"""Run the MCP stdio server from a checkout without installing the package."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))
from minecraft_codex_bridge.frontend import main

if __name__ == "__main__":
    main()
