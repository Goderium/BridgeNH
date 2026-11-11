"""Per-call, memory-only timing; never stores arguments, credentials or responses."""

from __future__ import annotations

import re
import time
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any


SERVER_FIELDS = {
    "httpWallNs": "Http-Wall-Ns", "submittedTickets": "Submitted-Tickets", "startedTickets": "Started-Tickets",
    "finishedTickets": "Finished-Tickets", "cancelledBeforeTickets": "Cancelled-Before-Tickets",
    "queueNs": "Queue-Ns", "callbackWallNs": "Callback-Wall-Ns", "queueAwaitCalls": "Queue-Await-Calls",
    "queueAwaitWallNs": "Queue-Await-Wall-Ns", "nativeAwaitCalls": "Native-Await-Calls", "nativeAwaitWallNs": "Native-Await-Wall-Ns",
}


def parse_timing_headers(headers):
    """Strict optional telemetry; absence/malformed never changes a completed action result."""
    prefix = "x-mcb-timing-"
    present = {name.lower() for name in headers.keys() if name.lower().startswith(prefix)}
    if not present:
        return {"status": "unavailable"}
    expected = {prefix + "version", *(prefix + name.lower() for name in SERVER_FIELDS.values())}
    if present != expected or headers.get_list(prefix + "version") != ["1"]:
        return {"status": "malformed"}
    values = {}
    for key, name in SERVER_FIELDS.items():
        declared = headers.get_list(prefix + name)
        if len(declared) != 1 or not re.fullmatch(r"0|[1-9][0-9]{0,18}", declared[0]):
            return {"status": "malformed"}
        value = int(declared[0])
        if value > 9223372036854775807:
            return {"status": "malformed"}
        values[key] = value
    if not (values["finishedTickets"] <= values["startedTickets"] <= values["submittedTickets"]
            and values["cancelledBeforeTickets"] <= values["submittedTickets"] - values["startedTickets"]
            and (values["startedTickets"] or not values["queueNs"])
            and (values["finishedTickets"] or not values["callbackWallNs"])
            and (values["queueAwaitCalls"] or not values["queueAwaitWallNs"])
            and (values["nativeAwaitCalls"] or not values["nativeAwaitWallNs"])):
        return {"status": "malformed"}
    return {"status": "measured", **values}


@dataclass
class CallTiming:
    started_ns: int = field(default_factory=time.perf_counter_ns)
    validation_ns: int = 0
    rpc_calls: int = 0
    rpc_ns: int = 0
    rpc_max_ns: int = 0
    response_bytes: int = 0
    response_measured_calls: int = 0
    operations: dict[str, dict[str, int]] = field(default_factory=dict)
    omitted_operations: bool = False
    server_measured_calls: int = 0
    server_unavailable_calls: int = 0
    server_malformed_calls: int = 0
    server_sums: dict[str, int] = field(default_factory=lambda: {key: 0 for key in SERVER_FIELDS})

    def rpc(self, operation: str, elapsed_ns: int, server_timing=None, response_bytes=None) -> None:
        duration = max(0, elapsed_ns)
        self.rpc_calls += 1
        self.rpc_ns += duration
        self.rpc_max_ns = max(self.rpc_max_ns, duration)
        if type(response_bytes) is int and response_bytes >= 0:
            self.response_bytes += response_bytes
            self.response_measured_calls += 1
        status = server_timing.get("status") if isinstance(server_timing, dict) else "unavailable"
        if status == "measured":
            self.server_measured_calls += 1
            for key in SERVER_FIELDS:
                self.server_sums[key] += server_timing[key]
        else:
            if status == "malformed":
                self.server_malformed_calls += 1
            else:
                self.server_unavailable_calls += 1
        # Operation names alone are retained; arbitrary input strings are excluded.
        name = operation if isinstance(operation, str) and re.fullmatch(r"[a-z_]{1,32}\.[a-z_]{1,32}", operation) else "unclassified"
        if name not in self.operations and len(self.operations) >= 32:
            self.omitted_operations = True
            return
        entry = self.operations.setdefault(name, {"calls": 0, "elapsedNs": 0})
        entry["calls"] += 1
        entry["elapsedNs"] += duration

    def report(self) -> dict[str, Any]:
        return {
            "scope": "mcp_adapter_received_to_result",
            "adapterElapsedMs": round(max(0, time.perf_counter_ns() - self.started_ns) / 1_000_000, 3),
            "validationMs": round(self.validation_ns / 1_000_000, 3),
            "bridgeCalls": self.rpc_calls,
            "bridgeResponseBytes": self.response_bytes,
            "bridgeResponseMeasuredCalls": self.response_measured_calls,
            "bridgeResponseByteScope": "decoded HTTP body bytes received; excludes headers, cancellation RPCs and MCP delivery",
            "bridgeRoundTripSumMs": round(self.rpc_ns / 1_000_000, 3),
            "bridgeRoundTripMaxMs": round(self.rpc_max_ns / 1_000_000, 3),
            "bridgeByOperation": [
                {"operation": name, "calls": values["calls"], "elapsedMs": round(values["elapsedNs"] / 1_000_000, 3)}
                for name, values in self.operations.items()
            ],
            "operationSummaryLimited": self.omitted_operations,
            "bridgeDurationIncludes": "connection, serialization, game queue/execution, response validation and any cancellation wait",
            "serverTiming": {
                "contract": "mcb-http-timing-v1", "measuredCalls": self.server_measured_calls,
                "unavailableCalls": self.server_unavailable_calls, "malformedCalls": self.server_malformed_calls,
                "available": self.server_measured_calls > 0,
                "sums": {key: value for key, value in self.server_sums.items()} if self.server_measured_calls else None,
                "incompleteTickets": self.server_sums["submittedTickets"] - self.server_sums["finishedTickets"] - self.server_sums["cancelledBeforeTickets"] if self.server_measured_calls else None,
                "queueMeasuredTickets": self.server_sums["startedTickets"] if self.server_measured_calls else None,
                "callbackMeasuredTickets": self.server_sums["finishedTickets"] if self.server_measured_calls else None,
                "scope": "HTTP handler entry through response headers ready; fixed Control aggregates only",
                "queueAwaitWallIncludes": "owning-thread queue plus callback and scheduling; overlaps their measurements",
                "nativeAwaitWallScope": "explicit QA/AE completion, native observation and approach eye future waits only",
                "cpuTimeMeasured": False, "responseDeliveryMeasured": False, "httpResidualClassified": False,
            },
            "durationsAdditive": False,
            "externalPlanningAndApprovalMeasured": False,
            "responseDeliveryMeasured": False,
            "persisted": False,
        }


_CURRENT: ContextVar[CallTiming | None] = ContextVar("mcb_call_timing", default=None)


@contextmanager
def timing_scope():
    timing = CallTiming()
    token = _CURRENT.set(timing)
    try:
        yield timing
    finally:
        _CURRENT.reset(token)


def record_rpc(operation: str, elapsed_ns: int, server_timing=None, response_bytes=None) -> None:
    timing = _CURRENT.get()
    if timing is not None:
        timing.rpc(operation, elapsed_ns, server_timing, response_bytes)


def record_validation(elapsed_ns: int) -> None:
    timing = _CURRENT.get()
    if timing is not None:
        timing.validation_ns += max(0, elapsed_ns)
