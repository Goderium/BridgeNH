"""Install the pinned optional planner or calculate a finite production plan from an instance's NEI index."""

import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp-server/src"))

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.packdata import database
from minecraft_codex_bridge.production_planner import install_solver, plan_production


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, required=True, help="Exact Prism instance directory")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--install-solver", type=Path, metavar="PINNED_GIT_CHECKOUT")
    modes.add_argument("--request", type=Path, metavar="REQUEST_JSON")
    args = parser.parse_args()
    index = database(args.instance.resolve(strict=True), os.environ)
    if args.install_solver:
        result = install_solver(index, args.install_solver)
    else:
        request = json.loads(args.request.read_text(encoding="utf-8-sig"))
        result = plan_production(index, request)
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    try:
        main()
    except BridgeError as exc:
        print(json.dumps({"error": exc.as_dict()}, ensure_ascii=True), file=sys.stderr)
        sys.exit(2)
