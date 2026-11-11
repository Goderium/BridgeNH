"""Direct command entry for existing guarded QA tools; no plan or script files."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time

from .bridge import BridgeClient, Configuration
from .application.adapter import Adapter


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--runtime-file", help="Existing bridge runtime configuration; credentials are never printed.")
    commands = root.add_subparsers(dest="command", required=True)
    task = commands.add_parser("qa-test-once", help="Run one existing finite QA tool, with complete flow required.")
    task.add_argument("selector")
    task.add_argument("--session", required=True)
    task.add_argument("--world", required=True)
    task.add_argument("--origin", type=int, nargs=3, metavar=("X", "Y", "Z"))
    task.add_argument("--details", action="store_true", help="Return the original bounded diagnostic payload.")
    task.add_argument("--brief", action="store_true", help="Return exact quantities as tuples to reduce agent reading time.")
    debug = commands.add_parser("qa-debug-observe", help="Prepare and verify ongoing normal-tick debug production; stop separately.")
    debug.add_argument("selector")
    debug.add_argument("--session", required=True)
    debug.add_argument("--world", required=True)
    debug.add_argument("--origin", type=int, nargs=3, metavar=("X", "Y", "Z"))
    debug.add_argument("--details", action="store_true")
    clear = commands.add_parser("qa-clear", help="Clear only the latest unchanged completed owned private fixture.")
    clear.add_argument("--session", required=True)
    clear.add_argument("--world", required=True)
    return root


def project_result(payload: dict, details: bool = False, brief: bool = False) -> dict:
    data = payload.get("data")
    if details or payload.get("ok") is not True or not isinstance(data, dict):
        return payload
    if data.get("status") != "completed" or data.get("fullFlowVerified") is not True:
        return payload
    if not isinstance(data.get("quantitativeSummary"), dict):
        return payload
    fields = (
        "status", "selector", "runId", "sessionId", "expectedWorld", "dimension", "providerClass",
        "testPassed", "fullFlowVerified", "reportsComplete", "reportCoherent", "diagnosticsComplete",
        "wholeTaskElapsed_ms", "timingScope", "completeWithin10s", "quantitativeSummary",
        "requests", "requestCounts", "sideEffectsPossible", "outcomeUnknown",
    )
    projected = {key: data[key] for key in fields if key in data}
    if brief:
        summary = data["quantitativeSummary"]
        item_fields = ("registryName", "metadata", "nbt", "count")
        fluid_fields = ("name", "nbt", "amount")
        try:
            exact = {key: summary[key] for key in ("scope", "quantityEncoding", "stableTicks")}
            exact["itemEncoding"] = list(item_fields)
            exact["fluidEncoding"] = list(fluid_fields)
            for key in ("consumedItems", "physicalCatalysts", "receiverItems"):
                exact[key] = [[item[field] for field in item_fields] for item in summary[key]]
            for key in ("fluidInputs", "receiverFluids"):
                exact[key] = [[fluid[field] for field in fluid_fields] for fluid in summary[key]]
        except (KeyError, TypeError):
            return payload
        brief_fields = ("status", "selector", "runId", "sessionId", "dimension", "fullFlowVerified",
                        "wholeTaskElapsed_ms", "timingScope", "completeWithin10s", "requests")
        projected = {key: data[key] for key in brief_fields if key in data}
        projected["quantitativeSummary"] = exact
    return {"ok": True, "data": projected}


async def invoke(adapter: Adapter, options: argparse.Namespace) -> dict:
    if options.command in ("qa-test-once", "qa-debug-observe"):
        args = {
            "selector": options.selector, "expectedSessionId": options.session,
            "expectedWorld": options.world,
            "includeDetails": options.details,
        }
        if options.origin is not None:
            args["origin"] = dict(zip(("x", "y", "z"), options.origin))
        if options.command == "qa-debug-observe":
            from .qa_debug_tasks import run_qa_debug_observe
            data = await run_qa_debug_observe(adapter.client, args, adapter.workflow_lock)
            accepted = data.get("status") == "observation_ready" and data.get("observationVerified") is True
            if accepted and not options.details:
                fields = ("status", "selector", "runId", "sessionId", "dimension", "observationVerified",
                          "runStillActive", "finitePass", "testPassed", "fullFlowVerified", "observationSummary",
                          "reportsComplete", "reportCoherent", "diagnosticsComplete", "diagnosticCoverage",
                          "stopPending", "stopActionSubmitted", "stopDrainStable85Verified", "remainingLifecycle", "wholeTaskElapsed_ms", "timingScope",
                          "completeWithin10s", "requests", "sideEffectsPossible", "outcomeUnknown")
                data = {key: data[key] for key in fields if key in data}
            return {"ok": accepted, "data": data}
        args["requireFullFlow"] = True
        reply = await adapter.call_tool("mcb_qa_test_once", args)
    else:
        capabilities = await adapter.call_tool("mcb_qa_catalog", {"section": "capabilities"})
        discovery = capabilities.structuredContent
        if not isinstance(discovery, dict) or discovery.get("ok") is not True:
            return discovery if isinstance(discovery, dict) else {"ok": False, "error": {"code": "INVALID_RESULT"}}
        current = discovery.get("data", {})
        if current.get("sessionId") != options.session:
            return {"ok": False, "error": {"code": "SESSION_CHANGED", "message": "Clear refused before submission."}}
        if (current.get("source") != "tstqa_public_instance_adapter" or current.get("available") is not True
                or type(current.get("dimension")) is not int or current["dimension"] == 0):
            return {"ok": False, "error": {"code": "PRIVATE_QA_REQUIRED", "message": "Only an owned private-instance fixture can be cleared by this command."}}
        reply = await adapter.call_tool("mcb_qa_command", {"command": "clearall", "expectedWorld": options.world})
    payload = reply.structuredContent
    if not isinstance(payload, dict):
        return {"ok": False, "error": {"code": "INVALID_RESULT", "message": "The SDK returned no structured result; no retry."}}
    return project_result(payload, getattr(options, "details", False), getattr(options, "brief", False))


async def execute(options: argparse.Namespace) -> dict:
    environment = dict(os.environ)
    if options.runtime_file is not None:
        environment["MCB_RUNTIME_FILE"] = options.runtime_file
    client = BridgeClient(Configuration(environment))
    try:
        adapter = Adapter(client)
        started = time.perf_counter_ns()
        start_utc = time.time_ns() // 1_000_000
        payload = await invoke(adapter, options)
        payload["commandTiming"] = {
            "sdkStartUtcMillis": start_utc, "sdkReadUtcMillis": time.time_ns() // 1_000_000,
            "sdkElapsed_ms": (time.perf_counter_ns() - started) / 1_000_000,
        }
        return payload
    finally:
        await client.aclose()


def main() -> None:
    options = parser().parse_args()
    payload = asyncio.run(execute(options))
    print(json.dumps(payload, ensure_ascii=True, separators=(",", ":")))
    sys.exit(0 if payload.get("ok") is True else 1)


if __name__ == "__main__":
    main()
