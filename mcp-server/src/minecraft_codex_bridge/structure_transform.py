"""Read-only coordinate/face mapping between observed public structure frames."""
from __future__ import annotations

import copy
import itertools
import time

import anyio

from .bridge import BridgeError
from .conveniences import json_sha256
from .placement_tasks import AXES, COORD, RELATIVE_POINT, _Planner, integer, obj

ALIGNMENT = obj({key: {"type": "string", "minLength": 1, "maxLength": 64}
                 for key in ("direction", "rotation", "flip")}, ("direction", "rotation", "flip"))
FRAME = obj({**COORD["properties"], "piece": {"type": "string", "minLength": 1, "maxLength": 128},
    "variant": integer(1, 64), "expectedAlignment": ALIGNMENT}, (*AXES, "piece", "expectedAlignment"))
NAME = {"type": "string", "minLength": 1, "maxLength": 64}
TRANSFORM_SCHEMA = obj({"expectedSessionId": NAME, "expectedWorld": NAME,
    "dimension": integer(-2147483648, 2147483647), "source": FRAME, "target": FRAME, "targetAlignment": ALIGNMENT,
    "anchor": COORD,
    "points": {"type": "array", "maxItems": 128, "items": obj({"id": NAME, "offset": RELATIVE_POINT}, ("id", "offset"))},
    "bounds": {"type": "array", "maxItems": 32, "items": obj({"id": NAME, "min": RELATIVE_POINT,
        "max": RELATIVE_POINT}, ("id", "min", "max"))},
    "timeout_ms": {**integer(1000, 60000), "default": 10000},
    "maxRequests": {**integer(8, 128), "default": 64}},
    ("expectedSessionId", "expectedWorld", "dimension", "source"))
TRANSFORM_SCHEMA["oneOf"] = [
    {"required": ["target"], "not": {"required": ["targetAlignment"]}},
    {"required": ["targetAlignment", "anchor"], "not": {"required": ["target"]}}]
VECTORS = ((0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0))


def require(value, reason, message):
    if not value:
        raise BridgeError("STRUCTURE_TRANSFORM_UNAVAILABLE", message, reason=reason)


def validate_matrix(matrix):
    require(isinstance(matrix, list) and len(matrix) == 3 and all(isinstance(row, list) and len(row) == 3
        and all(type(v) is int and v in (-1, 0, 1) for v in row) and sum(abs(v) for v in row) == 1 for row in matrix)
        and all(sum(abs(matrix[r][c]) for r in range(3)) == 1 for c in range(3)),
        "invalid_axis_transform", "An observed frame must be a signed permutation of three integer axes.")


def mapping(source, target):
    validate_matrix(source)
    validate_matrix(target)
    # Observed matrices map structure ABC vectors to world XYZ. Their inverse is
    # the transpose, including reflections; no direction-name lookup is needed.
    return [[sum(target[r][k] * source[c][k] for k in range(3)) for c in range(3)] for r in range(3)]


def vector(matrix, point):
    return {axis: sum(matrix[r][c] * point[a] for c, a in enumerate(AXES)) for r, axis in enumerate(AXES)}


def validate_geometry(args):
    ids = [r["id"] for key in ("points", "bounds") for r in args.get(key, [])]
    require(len(set(ids)) == len(ids), "duplicate_geometry_id", "Every supplied point or box needs its own ID.")
    require(all(row["min"][a] <= row["max"][a] for row in args.get("bounds", []) for a in AXES),
            "inverted_geometry_box", "Inclusive source box minima must not exceed maxima.")


def resolve_geometry(args, matrix, anchor):
    validate_geometry(args)
    def world(offset):
        result = {a: anchor[a] + offset[a] for a in AXES}
        require(0 <= result["y"] <= 255 and all(-29999996 <= result[a] <= 29999996 for a in ("x", "z")),
            "transformed_world_bounds", "A transformed point lies outside supported world coordinates.")
        return result
    points, bounds = [], []
    for row in args.get("points", []):
        offset = vector(matrix, row["offset"])
        points.append({"id": row["id"], "offset": offset, "position": world(offset)})
    for row in args.get("bounds", []):
        require(all(row["min"][a] <= row["max"][a] for a in AXES), "inverted_geometry_box",
                "Inclusive source box minima must not exceed maxima.")
        corners = [vector(matrix, dict(zip(AXES, values))) for values in itertools.product(
            *[(row["min"][a], row["max"][a]) for a in AXES])]
        low = {a: min(p[a] for p in corners) for a in AXES}
        high = {a: max(p[a] for p in corners) for a in AXES}
        bounds.append({"id": row["id"], "relativeBounds": {"min": low, "max": high},
            "worldBounds": {**{"min" + a.upper(): v for a, v in world(low).items()},
                            **{"max" + a.upper(): v for a, v in world(high).items()}}})
    sides = []
    for side, values in enumerate(VECTORS):
        transformed = vector(matrix, dict(zip(AXES, values)))
        sides.append({"sourceSide": side, "targetSide": VECTORS.index(tuple(transformed[a] for a in AXES))})
    # Multipart cable slot 6 is a center marker, not a seventh direction.
    sides.append({"sourceSide": 6, "targetSide": 6})
    return {"points": points, "bounds": bounds, "sides": sides}


class _FrameReader(_Planner):
    def require(self, condition, reason, message):
        require(condition, reason, message)


async def run_structure_transform(client, args):
    validate_geometry(args)
    started = time.monotonic()
    deadline = started + args.get("timeout_ms", 10000) / 1000
    credentials = client.configuration.read()
    requests = 0

    async def read(operation, arguments):
        nonlocal requests
        require(requests < args.get("maxRequests", 64), "transform_request_budget", "The shared read budget expired.")
        left = deadline - time.monotonic()
        require(left > 0, "transform_deadline", "The original transform deadline expired.")
        requests += 1
        with anyio.fail_after(left):
            return await client.call(operation, arguments, expected_credentials=credentials)

    health = await read("bridge.health", {})
    require(type(health.get("protocolVersion")) is int and health["protocolVersion"] == 1
        and health.get("sessionId") == args["expectedSessionId"] and health.get("profile") == "developer",
        "transform_context_changed", "Frame observation needs the exact local developer session.")
    capabilities = health.get("capabilities", [])
    require(all(operation in capabilities if isinstance(capabilities, list) else isinstance(capabilities, dict)
        and capabilities.get(operation) is True for operation in ("client.snapshot", "world.inspect", "world.structure")),
        "transform_capability_unavailable", "Both frames require advertised existing snapshot/block/public-structure reads.")
    readers, frames = [], []
    for declaration in (args["source"], *([args["target"]] if "target" in args else [])):
        planner = _FrameReader(client, {**args, "geometrySource": declaration}, call=read)
        planner.deadline = deadline
        await planner.context()
        identity = await planner.source_identity()
        initial = await planner.structure({"piece": declaration["piece"], "limit": 1})
        require(initial.get("alignment") == declaration["expectedAlignment"], "transform_alignment_changed",
            "Declared offsets are bound to the exact observed direction, rotation and flip.")
        matrix = await planner.transform(declaration["piece"], initial["alignment"])
        readers.append((planner, identity, initial, declaration))
        frames.append({"controller": copy.deepcopy(planner.source_controller), "identity": identity,
            "alignment": copy.deepcopy(initial["alignment"]), "piece": declaration["piece"],
            "variant": declaration.get("variant", 1), "axisTransform": matrix,
            "coordinateFrame": "controller_relative_world_xyz", "matrixColumns": ["a", "b", "c"]})
    if "targetAlignment" in args:
        require(type(health.get("structureAlignmentFramesVersion")) is int and health["structureAlignmentFramesVersion"] == 1,
            "alignment_frames_unavailable", "A proposed target requires the native public alignment-frames capability; no orientation table fallback.")
        planner = readers[0][0]
        native = await planner.structure({"alignmentFrames": True})
        rows = native.get("frames")
        require(native.get("mode") == "alignment_frames" and native.get("version") == 1 and native.get("complete") is True
            and native.get("readOnly") is True and native.get("worldEffectsDispatched") is False
            and native.get("alignment") == frames[0]["alignment"] and isinstance(rows, list) and 1 <= len(rows) <= 128,
            "alignment_frames_incomplete", "The exact real owner must return a complete bounded read-only native frame catalog.")
        require(all(isinstance(r, dict) and isinstance(r.get("alignment"), dict)
            and set(r["alignment"]) == {"direction", "rotation", "flip"}
            and all(isinstance(v, str) and v for v in r["alignment"].values())
            and type(r.get("sourceControllerAlignmentAllowed")) is bool for r in rows)
            and len({json_sha256(r["alignment"]) for r in rows}) == len(rows),
            "alignment_frames_malformed", "Native frames need unique typed public alignments and explicit actual-owner limits.")
        for row in rows:
            validate_matrix(row.get("axisTransform"))
        current = [r for r in rows if r["alignment"] == frames[0]["alignment"]]
        target = [r for r in rows if r["alignment"] == args["targetAlignment"]]
        require(len(current) == 1 and current[0]["axisTransform"] == frames[0]["axisTransform"] and len(target) == 1,
            "alignment_frame_unavailable", "The observed source probes and requested public enum frame must resolve exactly.")
        frames.append({"controller": None, "identity": None, "alignment": copy.deepcopy(target[0]["alignment"]),
            "axisTransform": copy.deepcopy(target[0]["axisTransform"]), "coordinateFrame": "proposed_controller_relative_world_xyz",
            "matrixColumns": ["a", "b", "c"], "source": native.get("source"), "placed": False,
            "sourceControllerAlignmentAllowed": target[0]["sourceControllerAlignmentAllowed"],
            "limitsScope": native.get("limitsScope")})
    for planner, identity, initial, declaration in readers:
        await planner.source_identity(identity)
        current = await planner.structure({"piece": declaration["piece"], "limit": 1})
        require(current.get("alignment") == initial["alignment"]
            and json_sha256(current.get("mode")) == json_sha256(initial.get("mode")),
            "transform_alignment_changed", "Both source frames must retain their actual alignment and public query mode after observation.")
        await planner.context()
    matrix = mapping(frames[0]["axisTransform"], frames[1]["axisTransform"])
    anchor = copy.deepcopy(args["anchor"] if "anchor" in args else {a: args["target"][a] for a in AXES})
    return {"status": "completed", "readOnly": True, "nonAtomic": True,
        "expectedSessionId": args["expectedSessionId"], "expectedWorld": args["expectedWorld"], "dimension": args["dimension"],
        "sourceFrame": frames[0], "targetFrame": frames[1], "matrix": matrix, "anchor": anchor,
        "anchorSource": "caller_proposed" if "anchor" in args else "observed_target_controller",
        **resolve_geometry(args, matrix, anchor), "coverage": {"requests": requests, "elapsed_ms": int((time.monotonic() - started) * 1000)},
        "worldEffectsDispatched": False, "placementVerified": False, "reachVerified": False,
        "scope": "Planning only: rotate/reflect caller-declared controller-relative world XYZ offsets, inclusive boxes and cardinal sides through public ABC frames. An optional targetAlignment+anchor uses the actual source's public enum transforms and limits without a target reference controller. Known-disallowed source alignments remain explicitly false, never legal placement proof. This does not infer piece anchors, active pieces, machine mode, port order, block metadata or a legal whole-machine orientation. Gravity and world up remain world Y; equipment ground placement, support, reach, existing blocks and native role acceptance need fresh independent checks."}
