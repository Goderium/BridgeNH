"""Start/reuse the Prism GUI, then launch the configured 1.7.10 instance.

Uses the same bounded launcher as mcb_launch. No keyboard/mouse input,
account login automation, or arbitrary shell commands are performed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import anyio

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.launcher import launch_client, launch_status
from minecraft_codex_bridge.local_config import configured_environment


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prism", help="Fixed PrismLauncher.exe path; alternatively MCB_PRISM_EXE.")
    parser.add_argument("--instance", help="Prism instance directory; alternatively MCB_INSTANCE_PATH.")
    parser.add_argument("--runtime", help="Standard Prism runtime path for inference; file need not exist.")
    parser.add_argument("--status", action="store_true", help="Read-only bounded startup continuation; never launches a process.")
    parser.add_argument("--timeout-ms", type=int, default=30000, help="Status observation window, 1000..60000 ms.")
    options = parser.parse_args()
    environment = dict(os.environ)
    for argument, key in ((options.prism, "MCB_PRISM_EXE"),
                          (options.instance, "MCB_INSTANCE_PATH"),
                          (options.runtime, "MCB_RUNTIME_FILE")):
        if argument:
            environment[key] = argument
    environment = configured_environment(environment)
    try:
        if not 1000 <= options.timeout_ms <= 60000:
            parser.error("--timeout-ms must be 1000..60000")
        async def observe():
            client = BridgeClient(Configuration(environment))
            try:
                return await launch_status(client, options.timeout_ms)
            finally:
                await client.aclose()
        result = anyio.run(observe) if options.status else anyio.run(launch_client, Configuration(environment))
        print(json.dumps({"ok": True, "data": result}, ensure_ascii=False))
        return 0
    except BridgeError as error:
        print(json.dumps({"ok": False, "error": error.as_dict()}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
