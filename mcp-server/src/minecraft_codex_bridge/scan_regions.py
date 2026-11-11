"""Reject explicit scan clipping before transport; player anchors remain live."""

from .bridge import BridgeError


def validate_scan_region(arguments):
    bounds = arguments.get("bounds")
    directional = arguments.get("directional")
    if directional is not None and "x" in arguments:
        forward = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}[directional["facing"]]
        right = (-forward[1], forward[0])
        points = [(arguments["x"] + f * forward[0] + r * right[0], arguments["z"] + f * forward[1] + r * right[1])
            for f in (-directional["backward"], directional["forward"])
            for r in (-directional["left"], directional["right"])]
        bounds = {"minX": min(point[0] for point in points), "maxX": max(point[0] for point in points),
            "minZ": min(point[1] for point in points), "maxZ": max(point[1] for point in points),
            "minY": arguments["y"] - directional["down"], "maxY": arguments["y"] + directional["up"]}
    if bounds is not None:
        for axis in ("X", "Y", "Z"):
            low, high = bounds["min" + axis], bounds["max" + axis]
            minimum, maximum = (0, 255) if axis == "Y" else (-30_000_000, 29_999_999)
            if not minimum <= low <= high <= maximum or high - low + 1 > 263:
                raise BridgeError("INVALID_ARGUMENTS", "Explicit scan bounds must be ordered, span at most 263 cells per axis, and stay inside the world without clipping.")
