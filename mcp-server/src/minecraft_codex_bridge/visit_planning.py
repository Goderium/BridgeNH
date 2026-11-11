"""Dependency-preserving visit ordering over caller-declared feet positions."""
from __future__ import annotations

import math
import time

import anyio

from .bridge import BridgeError
from .placement_tasks import integer, obj

NAME = {"type": "string", "minLength": 1, "maxLength": 64}
POINT = obj({a: {"type": "number", "minimum": low, "maximum": high}
    for a, low, high in (("x", -29999996, 29999996), ("y", 0, 255), ("z", -29999996, 29999996))}, ("x", "y", "z"))
VISIT_SCHEMA = obj({"expectedSessionId": NAME, "expectedWorld": NAME,
    "dimension": integer(-2147483648, 2147483647),
    "stops": {"type": "array", "minItems": 1, "maxItems": 32, "items": obj({"id": NAME, "feet": POINT,
        "after": {"type": "array", "maxItems": 31, "uniqueItems": True, "items": NAME}}, ("id", "feet"))},
    "end": POINT, "timeout_ms": {**integer(1000, 10000), "default": 3000}},
    ("expectedSessionId", "expectedWorld", "dimension", "stops"))
AXES = ("x", "y", "z")


def order_visits(stops, start, end=None):
    by_id = {r["id"]: r for r in stops}
    if len(by_id) != len(stops) or any(d not in by_id or d == r["id"] for r in stops for d in r.get("after", [])):
        raise BridgeError("VISIT_DEPENDENCY_INVALID", "Visit IDs must be unique and dependencies must name other supplied stops.")
    def distance(a, b):
        return math.dist([a[k] for k in AXES], [b[k] for k in AXES])
    def topological(nearest):
        done, remaining, result, pose = set(), list(stops), [], start
        while remaining:
            ready = [r for r in remaining if done.issuperset(r.get("after", []))]
            if not ready:
                raise BridgeError("VISIT_DEPENDENCY_CYCLE", "The supplied visit dependencies contain a cycle.")
            selected = min(ready, key=lambda r: distance(pose, r["feet"])) if nearest else ready[0]
            result.append(selected)
            remaining.remove(selected)
            done.add(selected["id"])
            pose = selected["feet"]
        return result
    def cost(rows):
        points = [start, *(r["feet"] for r in rows), *([end] if end is not None else [])]
        return sum(distance(a, b) for a, b in zip(points, points[1:]))
    baseline, greedy = topological(False), topological(True)
    chosen = greedy if cost(greedy) < cost(baseline) else baseline
    visits = []
    for row in chosen:
        if not visits or visits[-1]["feet"] != row["feet"]:
            visits.append({"feet": dict(row["feet"]), "taskIds": []})
        visits[-1]["taskIds"].append(row["id"])
    return {"orderedTaskIds": [r["id"] for r in chosen], "visits": visits,
        "baselineTaskIds": [r["id"] for r in baseline], "baselineDistance": cost(baseline),
        "plannedDistance": cost(chosen), "distanceUnit": "euclidean_blocks_estimate",
        "stopCount": len(stops), "visitCount": len(visits), "dependenciesPreserved": True}


async def run_visit_plan(client, args):
    from .workflows import _guard
    # Validate the entire dependency graph before transport; the real start is read below.
    order_visits(args["stops"], args["stops"][0]["feet"], args.get("end"))
    deadline = time.monotonic() + args.get("timeout_ms", 3000) / 1000
    credentials = client.configuration.read()
    with anyio.fail_after(max(.001, deadline - time.monotonic())):
        snapshot = await client.call("client.snapshot", {}, expected_credentials=credentials)
        _guard(snapshot, args["expectedSessionId"], "developer", args["expectedWorld"], False)
        player = snapshot.get("player", {})
        start = {a: player.get("feetY" if a == "y" else a) for a in AXES}
        if snapshot.get("world", {}).get("dimension") != args["dimension"] or any(type(v) not in (int, float)
                or not math.isfinite(v) for v in start.values()):
            raise BridgeError("VISIT_START_UNAVAILABLE", "Visit ordering needs the actual current finite feet pose and exact dimension.")
        result = order_visits(args["stops"], start, args.get("end"))
    return {"status": "completed", "readOnly": True, "worldEffectsDispatched": False,
        "expectedSessionId": args["expectedSessionId"], "expectedWorld": args["expectedWorld"], "dimension": args["dimension"],
        "start": start, "end": args.get("end"), **result, "reachVerified": False,
        "scope": "Caller authorizes reordering stops except declared after dependencies. Stable topological baseline versus nearest-ready estimate; never worse than baseline. Consecutive identical feet positions share one visit. Uses no inventory or machine policy; actual routes, collision/support/reach and later actor movement require fresh native navigation. Not an optimal-route proof or executable persisted plan."}
