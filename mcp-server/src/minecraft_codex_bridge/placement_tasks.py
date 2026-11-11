"""Bounded public geometry and fresh terrain planning; no world writes or files."""

import copy
import json
import time
from contextlib import nullcontext
from itertools import islice

import anyio

from .bridge import BridgeError, request_context, response_context
from .conveniences import json_sha256
from .scan_regions import validate_scan_region


def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}


def integer(low, high):
    return {"type": "integer", "minimum": low, "maximum": high}


COORD = obj({"x": integer(-29_999_999, 29_999_998), "y": integer(0, 255), "z": integer(-29_999_999, 29_999_998)}, ("x", "y", "z"))
SOURCE = obj({**COORD["properties"], "piece": {"type": "string", "minLength": 1, "maxLength": 128},
    "captureId": {"type": "string", "minLength": 1, "maxLength": 128}, "variant": integer(1, 64),
    **{"offset" + axis: integer(-2048, 2048) for axis in ("A", "B", "C")}}, ("x", "y", "z"))
SOURCE["dependentRequired"] = {"offset" + axis: ["piece", *["offset" + other for other in ("A", "B", "C") if other != axis]] for axis in ("A", "B", "C")}
RELATIVE_POINT = obj({axis: integer(-128, 128) for axis in ("x", "y", "z")}, ("x", "y", "z"))
LAYOUT_COMPONENT = obj({"id": {"type": "string", "minLength": 1, "maxLength": 64},
    "role": {"enum": ["equipment", "io_routing"]},
    "placement": {"enum": ["above_ground", "hidden_bottom"], "default": "above_ground"},
    "relativeBounds": obj({"min": RELATIVE_POINT, "max": RELATIVE_POINT}, ("min", "max"))},
    ("id", "role", "relativeBounds"))
LAYOUT_COMPONENT["allOf"] = [{"if": {"required": ["placement"], "properties": {"placement": {"const": "hidden_bottom"}}},
    "then": {"properties": {"role": {"const": "io_routing"}}}}]
PLACEMENT_OPTIONS = obj({"geometrySource": SOURCE, "geometryMode": {"enum": ["public_piece", "native_visits"],
    "default": "public_piece", "description": "public_piece preserves compiled single-piece bounds/ABC calibration. native_visits uses only the complete fresh unfiltered native XYZ/group union plus controller and its attachment layer; full machine rules remain unknown."},
    "searchRadius": integer(0, 8),
    "groundBand": obj({"minY": integer(0, 254), "maxY": integer(0, 254)}, ("minY", "maxY")),
    "clearance": integer(1, 8), "maxRequests": integer(8, 256), "maxScanCells": integer(1, 262_144),
    "serviceAccess": {**obj({"width": integer(1, 3), "headroom": integer(2, 4)}),
        "description": "Reserve an automatic exterior service ring around the complete machine plus declared AE footprint. Default width1/headroom3, at the above-ground minimum feet layer. Uses existing terrain/air budgets and revalidation; never excavates or builds a floor. Returned ring is reserved clearance, not a native walking or buried-port access proof; steps and upper ports still require native navigation."},
    "layoutComponents": {"type": "array", "minItems": 1, "maxItems": 64, "items": LAYOUT_COMPONENT,
        "description": "Declare the complete attached AE/equipment layout before choosing a site. Inclusive bounds are world-axis XYZ offsets from the controller in the captured orientation (no automatic rotation). Equipment, terminals, storage and CPUs default above_ground and raise the whole assembly. Only pure io_routing may use hidden_bottom: exactly one layer immediately below the above-ground envelope. All reserved cells still require fresh air; occupied trenches are refused, never excavated. Include access space in equipment bounds. Omission covers the machine only."}}, ("geometrySource",))
PLACEMENT_OPTIONS["allOf"] = [{"if": {"required": ["geometryMode"], "properties": {"geometryMode": {"const": "native_visits"}}},
    "then": {"properties": {"geometrySource": {"not": {"anyOf": [{"required": [key]} for key in ("piece", "offsetA", "offsetB", "offsetC")]}}}}}]
PLACEMENT_SCHEMA = obj({**PLACEMENT_OPTIONS["properties"], "anchor": COORD,
    "expectedSessionId": {"type": "string", "minLength": 1, "maxLength": 128},
    "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128},
    "dimension": integer(-2_147_483_648, 2_147_483_647), "timeout_ms": integer(1000, 60_000)},
    ("geometrySource", "anchor", "expectedSessionId", "expectedWorld", "dimension"))
PLACEMENT_SCHEMA["allOf"] = PLACEMENT_OPTIONS["allOf"]
AXES = ("x", "y", "z")
ABC = ("a", "b", "c")


def point(value):
    return {axis: value[axis] for axis in AXES}


def _validation_geometry_sha256(geometry):
    keys = ("nativeVisits",) if geometry["geometryMode"] == "native_visits" else (
        "piece", "variant", "offsets", "axisTransform", "relativeBounds", "physicalPositions")
    return json_sha256({"geometryMode": geometry["geometryMode"], **{key: geometry[key] for key in keys}})


def _target_validation_view(arguments, plan, owned_controller):
    """Bind an already accepted native target without rewriting the origin plan."""
    proof = plan.get("acceptedTargetValidationSource")
    if proof is None:
        return arguments, plan["geometry"], False
    def require(condition):
        if not condition:
            raise BridgeError("PLACEMENT_UNAVAILABLE", "Only the fully accepted current native target may replace the planning source.",
                reason="placement_target_validation_source_unavailable")
    geometry = plan["geometry"]
    geometry_keys = {"geometryMode", "sourceIdentity", "sourceCaptureHeader", "alignment", "sourceMetaClass", "captureId",
        "physicalPositions", "capturedMode", "capturedFacing", "capturedTrigger"}
    require(isinstance(geometry, dict) and geometry_keys <= set(geometry)
        and geometry["geometryMode"] in ("native_visits", "public_piece")
        and ({"nativeVisits"} if geometry["geometryMode"] == "native_visits" else
            {"piece", "variant", "offsets", "axisTransform", "relativeBounds"}) <= set(geometry)
        and isinstance(geometry["sourceIdentity"], dict) and "registryName" in geometry["sourceIdentity"]
        and isinstance(geometry["alignment"], dict) and isinstance(owned_controller, dict)
        and isinstance(plan.get("proposedController"), dict) and set(AXES) <= set(plan["proposedController"]))
    required = {"version", "sessionId", "expectedWorld", "dimension", "controller", "sourceIdentity", "metaClass",
        "alignment", "captureHeader", "geometrySha256"}
    require(isinstance(proof, dict) and set(proof) == required and type(proof.get("version")) is int and proof["version"] == 1
        and proof.get("sessionId") == arguments["expectedSessionId"] and proof.get("expectedWorld") == arguments["expectedWorld"]
        and type(proof.get("dimension")) is int and proof["dimension"] == arguments["dimension"]
        and isinstance(proof.get("controller"), dict) and set(proof["controller"]) == set(AXES)
        and all(type(proof["controller"].get(axis)) is int and proof["controller"][axis] == owned_controller.get(axis)
            == plan["proposedController"][axis] for axis in AXES)
        and plan.get("newNativeGeometryVerified") is True
        and proof.get("geometrySha256") == _validation_geometry_sha256(geometry)
        and isinstance(geometry.get("sourceCaptureHeader"), dict))
    placement = plan.get("controllerPlacementEvidence")
    require(isinstance(placement, dict) and placement.get("nativePlaceBlockUsed") is True
        and placement.get("placementConfirmed") is True and placement.get("placementAttempted") is True
        and placement.get("hasTile") is True and json_sha256(placement.get("position")) == json_sha256(proof["controller"]))
    identity, header = proof.get("sourceIdentity"), proof.get("captureHeader")
    require(isinstance(identity, dict) and set(identity) in ({"registryName", "metadata", "tileClass"},
            {"registryName", "metadata", "tileClass", "metaTileId"})
        and isinstance(identity.get("registryName"), str) and identity["registryName"] == geometry["sourceIdentity"]["registryName"]
        and type(identity.get("metadata")) is int and 0 <= identity["metadata"] <= 15
        and isinstance(identity.get("tileClass"), str) and identity["tileClass"] == geometry["sourceIdentity"].get("tileClass")
        and (type(identity.get("metaTileId")) is int and identity["metaTileId"] == geometry["sourceIdentity"]["metaTileId"]
            if "metaTileId" in geometry["sourceIdentity"] else "metaTileId" not in identity)
        and json_sha256(placement.get("block")) == json_sha256({key: identity[key] for key in ("registryName", "metadata")})
        and isinstance(proof.get("metaClass"), str) and proof["metaClass"] == geometry.get("sourceMetaClass")
        and json_sha256(proof.get("alignment")) == json_sha256(geometry["alignment"]) and isinstance(header, dict)
        and set(header) == set(geometry["sourceCaptureHeader"]))
    owner = header.get("controller")
    require(isinstance(owner, dict) and all(type(owner.get(axis)) is int and owner[axis] == proof["controller"][axis] for axis in AXES)
        and type(owner.get("dimension")) is int and owner["dimension"] == proof["dimension"]
        and owner.get("metaClass") == proof["metaClass"] and isinstance(header.get("captureId"), str)
        and header["captureId"] != geometry["captureId"] and type(header.get("capturedPositions")) is int
        and header["capturedPositions"] == geometry["physicalPositions"]
        and type(header.get("capturedMode")) is type(geometry["capturedMode"])
        and header["capturedMode"] == geometry["capturedMode"] and header.get("capturedFacing") == geometry["capturedFacing"]
        and header.get("trigger") == geometry["capturedTrigger"])
    if geometry["geometryMode"] == "native_visits":
        pose = plan.get("controllerPlacementPose", {})
        require(pose.get("nativePlaceBlockUsed") is True and pose.get("actualClickDirectionVerified") is True
            and pose.get("freshControllerAlignmentVerified") is True and pose.get("alignment") == proof["alignment"]
            and isinstance(pose.get("observedController"), dict) and pose["observedController"].get("metaClass") == proof["metaClass"])
    current_arguments, view = copy.deepcopy(arguments), copy.deepcopy(geometry)
    current_arguments["geometrySource"] = {**proof["controller"], "variant": geometry.get("variant", 1), "captureId": header["captureId"]}
    view.update(sourceController=copy.deepcopy(proof["controller"]), sourceIdentity=copy.deepcopy(identity),
        sourceMetaClass=proof["metaClass"], sourceCaptureHeader=copy.deepcopy(header), captureId=header["captureId"],
        sourceCapturedPositions=header["capturedPositions"], capturedMode=header["capturedMode"],
        capturedFacing=header["capturedFacing"], capturedTrigger=copy.deepcopy(header["trigger"]))
    return current_arguments, view, True


def box(value):
    if not isinstance(value, dict) or not all(isinstance(value.get(key), dict) for key in ("min", "max")):
        raise BridgeError("PLACEMENT_UNAVAILABLE", "Complete typed public geometry bounds are required.", reason="public_geometry_bounds_missing")
    low, high = value["min"], value["max"]
    keys = AXES if "x" in low else ABC
    if not all(type(low.get(axis)) is int and type(high.get(axis)) is int and low[axis] <= high[axis] for axis in keys):
        raise BridgeError("PLACEMENT_UNAVAILABLE", "Public geometry bounds must be complete and ordered.", reason="public_geometry_bounds_invalid")
    return {"min": {axis: low[axis] for axis in keys}, "max": {axis: high[axis] for axis in keys}}


def assembly_layout(machine_bounds, components, service_access=None):
    """Keep caller-declared auxiliary space separate from native machine geometry."""
    def require(condition):
        if not condition:
            raise BridgeError("PLACEMENT_UNAVAILABLE", "Layout needs unique component IDs and ordered XYZ offsets; only pure IO may reserve the single layer directly below the above-ground envelope.",
                reason="placement_layout_invalid")

    require(isinstance(components, list) and len(components) <= 64)
    above = copy.deepcopy(machine_bounds)
    rows, names = [], set()
    for component in components:
        require(isinstance(component, dict) and isinstance(component.get("id"), str)
            and 1 <= len(component["id"]) <= 64 and component["id"] not in names
            and component.get("role") in ("equipment", "io_routing")
            and component.get("placement", "above_ground") in ("above_ground", "hidden_bottom"))
        names.add(component["id"])
        bounds = box(component.get("relativeBounds"))
        require(set(bounds["min"]) == set(AXES) and all(-128 <= bounds[key][axis] <= 128 for key in ("min", "max") for axis in AXES))
        row = {**copy.deepcopy(component), "placement": component.get("placement", "above_ground"), "relativeBounds": bounds}
        rows.append(row)
        if row["placement"] == "above_ground":
            for axis in AXES:
                above["min"][axis] = min(above["min"][axis], bounds["min"][axis])
                above["max"][axis] = max(above["max"][axis], bounds["max"][axis])
    hidden = [row for row in rows if row["placement"] == "hidden_bottom"]
    combined = copy.deepcopy(above)
    for row in hidden:
        bounds = row["relativeBounds"]
        require(row["role"] == "io_routing" and bounds["min"]["y"] == bounds["max"]["y"] == above["min"]["y"] - 1)
        for axis in AXES:
            combined["min"][axis] = min(combined["min"][axis], bounds["min"][axis])
            combined["max"][axis] = max(combined["max"][axis], bounds["max"][axis])
    access = None
    if service_access is not None:
        require(isinstance(service_access, dict) and set(service_access) <= {"width", "headroom"})
        width, headroom = service_access.get("width", 1), service_access.get("headroom", 3)
        require(type(width) is int and 1 <= width <= 3 and type(headroom) is int and 2 <= headroom <= 4)
        inner = copy.deepcopy(combined)
        y = above["min"]["y"]
        x0, x1 = inner["min"]["x"], inner["max"]["x"]
        z0, z1 = inner["min"]["z"], inner["max"]["z"]
        bounds = [(x0-width, x1+width, z0-width, z0-1), (x0-width, x1+width, z1+1, z1+width),
                  (x0-width, x0-1, z0, z1), (x1+1, x1+width, z0, z1)]
        for side, (low_x, high_x, low_z, high_z) in zip(("north", "south", "west", "east"), bounds):
            name = "mcb.service." + side
            require(name not in names)
            row = {"id": name, "role": "service_access", "placement": "above_ground",
                "relativeBounds": {"min": {"x": low_x, "y": y, "z": low_z},
                                   "max": {"x": high_x, "y": y + headroom - 1, "z": high_z}}}
            rows.append(row)
            for axis in AXES:
                above["min"][axis] = min(above["min"][axis], row["relativeBounds"]["min"][axis])
                above["max"][axis] = max(above["max"][axis], row["relativeBounds"]["max"][axis])
                combined["min"][axis] = min(combined["min"][axis], above["min"][axis])
                combined["max"][axis] = max(combined["max"][axis], above["max"][axis])
        access = {"width": width, "headroom": headroom, "reservedClearanceOnly": True,
            "nativeNavigationRequired": True, "buriedPortAccessVerified": False, "supportVerified": False}
    return {"aboveGroundRelativeBounds": above, "combinedRelativeBounds": combined, "components": rows,
        "reservedRelativeBoxes": [above, *[row["relativeBounds"] for row in hidden]],
        "coordinateFrame": "controller_relative_world_xyz", "declaredLayoutOnly": True,
        **({"serviceAccess": access} if access is not None else {})}


def world_box(relative, controller):
    return {prefix + axis.upper(): controller[axis] + relative[prefix][axis]
        for prefix in ("min", "max") for axis in AXES}


def contains(bounds, position):
    return all(bounds["min" + axis.upper()] <= position[axis] <= bounds["max" + axis.upper()] for axis in AXES)


def placed_layout(layout, controller):
    return {**copy.deepcopy(layout), "reservedBoxes": [world_box(bounds, controller) for bounds in layout["reservedRelativeBoxes"]],
        "components": [{**copy.deepcopy(row), "worldBounds": world_box(row["relativeBounds"], controller)} for row in layout["components"]]}


async def verify_layout_air(planner, boxes, *, owned_controller=None, owned_identity=None, owned_seeds=None):
    planner.require(owned_controller is None or contains(boxes[0], owned_controller),
        "placement_owned_controller_outside_box", "The owned controller exception must remain inside the above-ground reservation.")
    seeds = owned_seeds or []
    planner.require(isinstance(seeds, list) and len(seeds) <= 4 and (not seeds or planner.native_visits and owned_controller is not None)
        and all(isinstance(row, dict) and set(row) == {"position", "block"}
            and isinstance(row["position"], dict) and set(row["position"]) == set(AXES)
            and all(type(row["position"][axis]) is int for axis in AXES)
            and contains(boxes[0], row["position"]) and row["position"] != owned_controller
            and isinstance(row["block"], dict) and set(row["block"]) == {"registryName", "metadata"}
            and isinstance(row["block"]["registryName"], str) and row["block"]["registryName"] != "minecraft:air"
            and type(row["block"]["metadata"]) is int and 0 <= row["block"]["metadata"] <= 15 for row in seeds),
        "placement_owned_seed_invalid", "Internal seed exceptions require at most four exact previously accounted non-tile blocks inside the above-ground native reservation.")
    planner.require(len({tuple(row["position"][axis] for axis in AXES) for row in seeds}) == len(seeds),
        "placement_owned_seed_invalid", "Task-owned seed coordinates must be unique.")
    proofs = []
    for bounds in boxes:
        owner = owned_controller if owned_controller is not None and contains(bounds, owned_controller) else None
        await planner.exact_air(bounds, owned_controller=owner, owned_identity=owned_identity,
            owned_seeds=[row for row in seeds if contains(bounds, row["position"])])
        if planner.native_visits:
            proofs.append(copy.deepcopy(planner.summary["existingTileProof"]))
    if len(proofs) > 1:
        planner.summary["existingTileProof"] = {"complete": True, "regions": proofs,
            "cells": sum(proof["cells"] for proof in proofs), "airCellsHaveNoExistingTile": True, "nonAtomic": True}


def _native_visit_header(runtime, controller, dimension, alignment):
    """Validate fresh immutable capture metadata, without claiming complete machine rules."""
    def require(condition, message):
        if not condition:
            raise BridgeError("PLACEMENT_UNAVAILABLE", message, reason="native_visit_capture_unavailable")

    require(isinstance(runtime, dict) and isinstance(controller, dict) and isinstance(alignment, dict),
        "Native visit planning requires complete actual controller/alignment/capture objects.")
    flags = ("available", "nativeInvocationReturned", "capturedVisitsComplete", "contextMatches",
        "modeMatches", "facingMatches", "controllerIdentityMatches", "nonAtomic", "evidenceOnly")
    require(all(runtime.get(key) is True for key in flags) and runtime.get("source") == "client_native_blueprint_hints"
        and runtime.get("eventCaptureTruncated") is False and runtime.get("completeStructure") is False
        and runtime.get("captureLossDetected") is False and runtime.get("captureContinuationAvailable") is False
        and type(runtime.get("uncapturedVisits")) is int and runtime["uncapturedVisits"] == 0
        and type(runtime.get("detailErrors")) is int and runtime["detailErrors"] == 0,
        "Only a returned, lossless native invocation with matching current context can supply observed visits.")
    identifier, count = runtime.get("captureId"), runtime.get("capturedPositions")
    require(isinstance(identifier, str) and 1 <= len(identifier) <= 128 and type(count) is int and 1 <= count <= 16_384
        and type(runtime.get("observedVisits")) is int and runtime["observedVisits"] == count,
        "Native capture identity and complete positive visit count must remain typed and bounded.")
    limits, cut = runtime.get("limits"), runtime.get("truncation")
    maximum = {"positions": 16_384, "groups": 128, "bytes": 131_072, "captureBytes": 4_194_304, "ttlMillis": 120_000}
    require(isinstance(limits, dict) and all(type(limits.get(key)) is int and 1 <= limits[key] <= limit
        for key, limit in maximum.items()) and count <= limits["positions"]
        and isinstance(cut, dict) and all(cut.get(key) is False for key in ("positions", "groups", "bytes", "workBudget", "cleanupFailed"))
        and type(runtime.get("captureEncodedBytesUpperBound")) is int
        and 0 <= runtime["captureEncodedBytesUpperBound"] <= limits["captureBytes"],
        "Native visit storage/response limits and every capture truncation flag must prove bounded lossless data.")
    require(type(runtime.get("ageMillis")) is int and 0 <= runtime["ageMillis"] <= limits["ttlMillis"]
        and type(runtime.get("clientValidationAgeMillis")) is int
        and 0 <= runtime["clientValidationAgeMillis"] <= min(1000, limits["ttlMillis"]),
        "Native capture TTL and the current public adapter's one-second client-validation window must hold on every page.")
    owner, trigger = runtime.get("controller"), runtime.get("trigger")
    require(all(isinstance(alignment.get(key), str) and alignment[key] for key in ("direction", "rotation", "flip")),
        "The current real public alignment must be explicit.")
    facing = "_".join(alignment[key].upper() for key in ("direction", "rotation", "flip"))
    mode = runtime.get("capturedMode")
    require((mode is None or type(mode) is int) and type(runtime.get("serverMode")) is type(mode)
        and runtime["serverMode"] == mode and runtime.get("capturedFacing") == facing and runtime.get("serverFacing") == facing
        and isinstance(owner, dict) and type(owner.get("dimension")) is int and owner["dimension"] == dimension
        and all(type(owner.get(axis)) is int and owner[axis] == controller.get(axis) for axis in AXES)
        and isinstance(controller.get("metaClass"), str) and bool(controller["metaClass"])
        and owner.get("metaClass") == controller["metaClass"] and owner.get("facing") == facing
        and type(owner.get("mode")) is type(mode) and owner["mode"] == mode
        and isinstance(trigger, dict) and isinstance(trigger.get("registryName"), str) and ":" in trigger["registryName"]
        and type(trigger.get("metadata")) is int and 0 <= trigger["metadata"] <= 32766
        and type(trigger.get("count")) is int and 1 <= trigger["count"] <= 2_147_483_647
        and isinstance(trigger.get("channelData"), str),
        "Every page needs the exact current controller/meta-class, dimension, mode, facing and complete trigger identity/count/channel data.")
    bounds = runtime.get("observedBounds")
    require(isinstance(bounds, dict) and bounds.get("available") is True and bounds.get("complete") is True
        and bounds.get("partial") is False and bounds.get("completeStructure") is False
        and bounds.get("source") == "client_native_hint_visited_xyz"
        and type(bounds.get("observedVisits")) is int and bounds["observedVisits"] == count
        and type(bounds.get("boundedVisits")) is int and bounds["boundedVisits"] == count,
        "Complete observed bounds must cover exactly this invocation's visits, never hidden structure rules.")
    box(bounds.get("worldXYZ"))
    require(runtime.get("query") == "" and type(runtime.get("matchingCapturedPositions")) is int
        and runtime["matchingCapturedPositions"] == count,
        "Native geometry must retain the unfiltered capture, including all public groups.")
    planning = runtime.get("planning")
    require(isinstance(planning, dict) and all(planning.get(key) is True for key in
        ("contextMatches", "observedVisitsComplete", "currentBoundsFitWorld"))
        and all(planning.get(key) is False for key in ("worldHeightConflict", "fullStructureChecked", "completeStructure")),
        "Fresh native height/context planning must preserve observed-only coverage and explicit lack of full structure knowledge.")
    rows, page_groups = runtime.get("positions"), runtime.get("groups")
    offset, following = runtime.get("runtimeOffset"), runtime.get("nextRuntimeOffset")
    require(isinstance(rows, list) and len(rows) <= 128 and isinstance(page_groups, list) and len(page_groups) <= 128
        and type(offset) is int and 0 <= offset <= count and type(following) is int and following == offset + len(rows) <= count
        and type(runtime.get("runtimePageVisited")) is int and runtime["runtimePageVisited"] == len(rows)
        and runtime.get("hasMore") is (following < count) and runtime.get("pageLimited") is runtime["hasMore"]
        and type(runtime.get("pageByteLimited")) is bool and type(runtime.get("pageSoftBudgetPartial")) is bool
        and (following == count or following > offset),
        "Every unfiltered native page needs an exact bounded positive raw prefix, current typed limits and consistent continuation flags.")
    return {key: runtime[key] for key in ("captureId", "capturedPositions", "capturedMode", "capturedFacing",
        "controller", "trigger", "limits", "observedBounds", "captureEncodedBytesUpperBound")}


def _native_visit_union(positions, groups, runtime, controller):
    """Normalize exact observed XYZ/group partitions; no single-piece or cross-side element inference."""
    def require(condition, message):
        if not condition:
            raise BridgeError("PLACEMENT_UNAVAILABLE", message, reason="native_visit_group_geometry_unavailable")

    count = runtime["capturedPositions"]
    require(isinstance(positions, list) and len(positions) == count and isinstance(groups, dict)
        and 1 <= len(groups) <= runtime["limits"]["groups"], "The union needs complete actual coordinates and public group coverage.")
    keys, relative = set(), []
    for row in positions:
        require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in (*AXES, *ABC, "group"))
            and -29_999_999 <= row["x"] <= 29_999_998 and 0 <= row["y"] <= 255
            and -29_999_999 <= row["z"] <= 29_999_998 and row["group"] in groups,
            "Every native XYZ/ABC DTO needs exact typed coordinates and a resolved actual public group.")
        key = tuple(row[axis] for axis in AXES)
        require(key not in keys, "Repeated world XYZ cannot be collapsed into a falsely complete capture.")
        keys.add(key)
        relative.append([*(row[axis] - controller[axis] for axis in AXES), row["group"]])
    actual_bounds = {key: {axis: operation(row[axis] for row in positions) for axis in AXES}
        for key, operation in (("min", min), ("max", max))}
    require(box(runtime["observedBounds"]["worldXYZ"]) == actual_bounds
        and box(runtime["observedBounds"].get("controllerRelativeXYZ")) == {key: {axis: value[axis] - controller[axis] for axis in AXES}
            for key, value in actual_bounds.items()},
        "The full coordinate set must reproduce the actual native bounds; bounds/count alone never establish equality.")
    normalized_groups = []
    for identifier, group in sorted(groups.items()):
        require(type(identifier) is int and 0 <= identifier < 128 and isinstance(group, dict)
            and type(group.get("id")) is int and group["id"] == identifier
            and group.get("identityCoverage") == "public_definition_element"
            and isinstance(group.get("elementClass"), str) and bool(group["elementClass"])
            and group.get("descriptionCoverage") == "public_category_descriptions"
            # A public predicate-only element still has exact captured XYZ.
            # Keep its lack of suggestions in the normalized proof; it grants
            # neither a material choice nor native placement/role acceptance.
            and (group.get("candidateCoverage") == "public_stack_suggestions"
                or group.get("candidateCoverage") == "unavailable_or_predicate_only"
                and group.get("suggestedCandidates") == [])
            and group.get("descriptionTruncated", False) is False and group.get("candidatesTruncated", False) is False
            and isinstance(group.get("candidateScope"), str) and bool(group["candidateScope"]),
            "Class-only, unknown or truncated group semantics cannot be guessed or upgraded.")
        descriptions, candidates, binding = group.get("descriptionKeys"), group.get("suggestedCandidates"), group.get("roleSelection")
        require(isinstance(descriptions, list) and 1 <= len(descriptions) <= 128
            and all(isinstance(key, str) and 1 <= len(key) <= 256 for key in descriptions)
            and len(set(descriptions)) == len(descriptions) and isinstance(candidates, list) and len(candidates) <= 128
            and all(isinstance(item, dict) and isinstance(item.get("registryName"), str) and ":" in item["registryName"]
                and type(item.get("metadata")) is int and 0 <= item["metadata"] <= 32766
                and type(item.get("count")) is int and 1 <= item["count"] <= 2_147_483_647 and item.get("hasTag") is False for item in candidates)
            and isinstance(binding, dict) and binding.get("available") is True
            and binding.get("binding") == "capture_scoped_public_definition_element_group"
            and binding.get("captureId") == runtime["captureId"] and type(binding.get("groupId")) is int
            and binding["groupId"] == identifier and binding.get("descriptionKeys") == descriptions
            and binding.get("semanticRoleVerified") is False,
            "Each actual group needs complete copied public category/candidate DTOs and its exact capture-scoped binding.")
        members = [row for row in positions if row["group"] == identifier]
        representative = group.get("representativePosition")
        require(members and type(group.get("capturedVisits")) is int and group["capturedVisits"] == len(members)
            and isinstance(representative, dict) and all(type(representative.get(axis)) is int for axis in AXES)
            and any(all(row[axis] == representative[axis] for axis in AXES) for row in members),
            "Complete group counts and the actual representative must agree with the complete coordinate partition.")
        normalized = json.loads(json.dumps(group, ensure_ascii=False))
        normalized["representativePosition"] = {axis: representative[axis] - controller[axis] for axis in AXES}
        normalized["roleSelection"].pop("captureId")
        normalized_groups.append(normalized)
    require(len(json.dumps({"positions": positions, "groups": list(groups.values())}, ensure_ascii=False)
        .encode("utf-8")) <= 4_194_304, "The union's complete capture retention exceeds its independent four-MiB budget.")
    relative_bounds = {key: {axis: operation([0, *(row[index] for row in relative)]) for index, axis in enumerate(AXES)}
        for key, operation in (("min", min), ("max", max))}
    return {"positions": sorted(relative), "groups": normalized_groups, "relativeBounds": relative_bounds}


class _Planner:
    def __init__(self, client, arguments, call=None, usage=None, *, discovery=None):
        self.client, self.args, self.reader = client, arguments, call
        self.credentials = client.configuration.read()
        self.discovery = discovery
        self.deadline = time.monotonic() + arguments.get("timeout_ms", 10_000) / 1000
        usage = usage or {}
        self.requests = usage.get("requests", 0)
        self.scan_pages = usage.get("scanPages", 0)
        self.scan_cells = usage.get("examined", 0)
        self.exact_cells = usage.get("exactCellChecks", 0)
        self.native_visits = arguments.get("geometryMode", "public_piece") == "native_visits"
        self.compact = False
        self.literal_scan = False
        self.structure_context = False
        self.source_controller = None
        self.summary = {"status": "unavailable", "available": False, "complete": False, "partial": False,
            "spaceVerified": False, "machineRulesKnown": False, "nonAtomic": True,
            "unknown": ["Unobserved dynamic pieces, original padding and complete formation/function rules remain unknown."],
            "proposedController": None, "obstacles": [], "obstaclesTruncated": False,
            "obstacleScope": "Distinct non-air cells encountered in rejected candidate envelopes."}

    def coverage(self):
        return {"requests": self.requests, "scanPages": self.scan_pages, "examined": self.scan_cells,
            "exactCellChecks": self.exact_cells, "nonAtomic": True}

    def require(self, condition, reason, message):
        if not condition:
            raise BridgeError("PLACEMENT_UNAVAILABLE", message, reason=reason)

    async def call(self, operation, arguments):
        self.require(self.requests < self.args.get("maxRequests", 128), "placement_request_budget", "The finite placement read budget was exhausted.")
        self.requests += 1
        left = self.deadline - time.monotonic()
        self.require(left > 0, "placement_deadline", "The placement read deadline expired; no action or consumed cursor is replayed.")
        with anyio.fail_after(left):
            data = await self.reader(operation, arguments) if self.reader else await self.client.call(operation, arguments, expected_credentials=self.credentials)
        self.require("sessionId" not in data or data["sessionId"] == self.args["expectedSessionId"], "placement_session_changed", "The live bridge session changed.")
        return data

    def validate_context(self, data):
        world = data.get("world", {})
        self.require(isinstance(world, dict) and data.get("sessionId") == self.args["expectedSessionId"] and data.get("profile") == "developer"
            and data.get("worldLoaded") is True and type(world.get("dimension")) is int and world["dimension"] == self.args["dimension"]
            and self.args["expectedWorld"] in (world.get("worldName"), world.get("worldFolder")),
            "placement_context_changed", "Placement needs the exact current local developer session/world/dimension.")

    async def context(self):
        self.validate_context(await self.call("client.snapshot", {}))

    async def health(self):
        if self.discovery is None:
            return await self.call("bridge.health", {})
        from .workflows import _WorkflowDiscovery
        self.require(type(self.discovery) is _WorkflowDiscovery, "placement_discovery_unavailable",
            "Embedded placement may reuse only the task's identity-bound immutable capability seal.")
        return self.discovery.bind(self.client, self.credentials, self.args["expectedSessionId"])

    def read_modes(self, health):
        self.compact = health.get("worldInspectManyCompact") is True
        self.literal_scan = health.get("worldScanMinecraftAir") is True and not self.native_visits
        self.require(not self.native_visits or self.compact, "placement_existing_tile_proof_unavailable",
            "Native visit planning requires advertised compact per-cell reads of the existing tile-entity map; a literal-air scan alone cannot prove empty air cells have no residual tiles.")
        context = health.get("contextRpc")
        self.structure_context = isinstance(context, dict) and type(context.get("version")) is int and context["version"] == 1 \
            and all(isinstance(context.get(key), list) and len(context[key]) <= 128
                and all(isinstance(value, str) for value in context[key]) for key in ("reads", "actions")) \
            and "world.structure" in context["reads"]

    async def structure(self, extra, *, guard_after=False):
        source = self.args["geometrySource"]
        context = {"version": 1, "expectedSessionId": self.args["expectedSessionId"], "expectedProfile": "developer",
            "expectedWorld": self.args["expectedWorld"], "requireBackground": False} if guard_after and self.structure_context else None
        with request_context(context) if context is not None else nullcontext():
            data = await self.call("world.structure", {**point(source), "dimension": self.args["dimension"],
                "variant": source.get("variant", 1), **extra})
        if context is not None:
            evidence = response_context(data)
            self.require(isinstance(evidence, dict) and type(evidence.get("version")) is int and evidence["version"] == 1
                and evidence.get("operation") == "world.structure" and evidence.get("phase") == "after"
                and isinstance(evidence.get("snapshot"), dict), "placement_context_unavailable",
                "The advertised structure RPC must include its fresh after-context; no read or action is replayed.")
            self.validate_context(evidence["snapshot"])
        controller = data.get("controller", {})
        self.require(all(type(controller.get(axis)) is int and controller[axis] == source[axis] for axis in AXES)
            and type(controller.get("dimension")) is int and controller["dimension"] == self.args["dimension"],
            "geometry_source_changed", "Every public geometry page must belong to the exact live source controller.")
        if self.source_controller is None:
            self.source_controller = controller.copy()
        self.require(controller == self.source_controller, "geometry_source_changed", "The actual public source controller identity cannot change across geometry reads.")
        return data

    async def source_identity(self, expected=None):
        source = self.args["geometrySource"]
        block = await self.call("world.inspect", {**point(source), "dimension": self.args["dimension"]})
        self.require(block.get("source") == "integrated_server" and block.get("dimension") == self.args["dimension"]
            and all(type(block.get(axis)) is int and block[axis] == source[axis] for axis in AXES)
            and isinstance(block.get("registryName"), str) and block["registryName"] != "minecraft:air"
            and type(block.get("metadata")) is int, "geometry_source_identity_unavailable", "The source requires its real loaded server block/item identity.")
        identity = {key: block[key] for key in ("registryName", "metadata", "tileClass") if key in block}
        meta = block.get("gregtech", {})
        if "metaTileId" in meta:
            self.require(type(meta["metaTileId"]) is int, "geometry_source_identity_unavailable", "The actual GT source meta-tile ID must be typed.")
            identity["metaTileId"] = meta["metaTileId"]
        self.require(expected is None or identity == expected, "geometry_source_changed", "The source controller was replaced during placement planning.")
        return identity

    async def source_current(self, geometry, *, guard_after=False):
        await self.source_identity(geometry["sourceIdentity"])
        data = await self.structure({"limit": 1, **({"captureId": geometry["captureId"]} if geometry["captureId"] else {})}, guard_after=guard_after)
        self.require(data.get("alignment") == geometry["alignment"], "geometry_source_changed", "The source public facing, rotation or flip changed.")
        if isinstance(geometry.get("sourceCaptureHeader"), dict):
            header = _native_visit_header(data.get("runtimeObservation"), data.get("controller"), self.args["dimension"], data.get("alignment"))
            self.require(json_sha256(header) == json_sha256(geometry["sourceCaptureHeader"]), "geometry_source_changed",
                "The immutable source visit capture and complete owner/mode/facing/trigger/bounds/limits must remain unchanged and fresh.")
        if geometry["captureId"]:
            runtime = data.get("runtimeObservation", {})
            self.require(runtime.get("available") is True and runtime.get("capturedVisitsComplete") is True
                and runtime.get("eventCaptureTruncated") is False and runtime.get("captureId") == geometry["captureId"]
                and runtime.get("capturedPositions") == geometry["sourceCapturedPositions"]
                and runtime.get("capturedMode") == geometry["capturedMode"]
                and runtime.get("capturedFacing") == geometry["capturedFacing"]
                and runtime.get("trigger") == geometry["capturedTrigger"] and all(runtime.get(flag) is True for flag in
                ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches")),
                "geometry_source_changed", "The calibration capture's source mode, facing, controller or exact trigger changed.")
        return guard_after and self.structure_context

    async def transform(self, piece, alignment):
        probes = []
        for probe in range(4):
            values = [int(probe == axis + 1) for axis in range(3)]
            offset = 0
            for _ in range(64):
                data = await self.structure({"piece": piece, "limit": 1, "offset": offset,
                    **{"offset" + axis: values[index] for index, axis in enumerate(("A", "B", "C"))}})
                rows = data.get("positions", [])
                self.require(data.get("alignment") == alignment and data.get("piece") == piece
                    and data.get("worldPositionsAvailable") is True and isinstance(rows, list) and len(rows) <= 1,
                    "public_anchor_transform_unavailable", "Public zero/unit-offset transforms must preserve the same exact piece and alignment.")
                if rows:
                    break
                following = data.get("nextOffset")
                self.require(data.get("hasMore") is True and type(following) is int and offset < following <= 100_000,
                    "public_anchor_transform_unavailable", "A bounded public prefix must progress to a physical point; empty navigation is not an anchor.")
                offset = following
            self.require(len(rows) == 1 and isinstance(rows[0], dict) and all(type(rows[0].get(axis)) is int for axis in (*AXES, *ABC)),
                "public_anchor_transform_unavailable", "Public offset transforms need the exact first physical point.")
            probes.append(rows[0])
            self.require(all(rows[0][axis] == probes[0][axis] - values[index] for index, axis in enumerate(ABC)),
                "public_anchor_transform_unavailable", "Unit-offset probes must retain the same compiled point's exact public relative ABC.")
        matrix = [[probes[0][axis] - probes[index + 1][axis] for index in range(3)] for axis in AXES]
        self.require(all(sum(abs(value) for value in row) == 1 for row in matrix)
            and all(sum(abs(matrix[row][column]) for row in range(3)) == 1 for column in range(3)),
            "public_anchor_transform_unavailable", "The public alignment must resolve a unique integer axis transform.")
        return matrix

    async def native_capture(self, initial, alignment, controller):
        source = self.args["geometrySource"]
        positions, capture = [], None
        runtime = initial.get("runtimeObservation", {})
        offset, identifier, total, captured_context, header = 0, None, None, None, None
        while True:
            if header is not None or runtime.get("source") == "client_native_blueprint_hints":
                current = _native_visit_header(runtime, controller, self.args["dimension"], alignment)
                self.require(header is None or json_sha256(current) == json_sha256(header), "native_anchor_capture_changed",
                    "Every complete public calibration page must retain one fresh typed native capture context.")
                header = current
            self.require(runtime.get("available") is True and runtime.get("capturedVisitsComplete") is True
                and runtime.get("eventCaptureTruncated") is False and all(runtime.get(flag) is True for flag in
                ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches")),
                "native_anchor_capture_incomplete", "Inferring a fresh placement anchor requires an untruncated current native ABC/XYZ capture; a partial capture is never upgraded.")
            count, following = runtime.get("capturedPositions"), runtime.get("nextRuntimeOffset")
            rows = runtime.get("positions")
            self.require(isinstance(runtime.get("captureId"), str) and (identifier is None or runtime["captureId"] == identifier)
                and type(count) is int and 1 <= count <= 16_384 and (total is None or total == count)
                and runtime.get("runtimeOffset") == offset and isinstance(rows, list) and len(rows) <= 128
                and type(following) is int and following == offset + len(rows) <= count
                and all(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in (*AXES, *ABC)) for row in rows),
                "native_anchor_capture_changed", "Native anchor pages need one immutable capture and exact positive raw-prefix progress.")
            identifier, total, capture = runtime["captureId"], count, runtime
            current_context = {key: runtime.get(key) for key in ("capturedFacing", "capturedMode", "controller", "trigger")}
            trigger = current_context["trigger"]
            captured_controller = current_context["controller"]
            self.require(isinstance(current_context["capturedFacing"], str)
                and current_context["capturedFacing"].upper() == "_".join(alignment[key].upper() for key in ("direction", "rotation", "flip"))
                and (current_context["capturedMode"] is None or type(current_context["capturedMode"]) is int)
                and isinstance(trigger, dict) and isinstance(trigger.get("registryName"), str)
                and type(trigger.get("metadata")) is int and type(trigger.get("count")) is int and trigger["count"] >= 1
                and isinstance(trigger.get("channelData"), str)
                and isinstance(captured_controller, dict) and captured_controller.get("dimension") == self.args["dimension"]
                and all(type(captured_controller.get(axis)) is int and captured_controller[axis] == source[axis] for axis in AXES)
                and captured_controller.get("metaClass") == controller.get("metaClass")
                and (captured_context is None or current_context == captured_context),
                "native_anchor_capture_changed", "Source capture pages must preserve exact facing, mode, controller and trigger identity/count/channel data.")
            captured_context = current_context
            positions.extend(rows)
            self.require(len(json.dumps(positions, separators=(",", ":"))) <= 4_194_304, "placement_geometry_memory_budget", "Native anchor retention exceeded four MiB.")
            if runtime.get("hasMore") is False:
                self.require(following == total and len({tuple(row[axis] for axis in AXES) for row in positions}) == total,
                    "native_anchor_capture_changed", "Native anchor positions must finish with unique exact world coordinates.")
                break
            self.require(runtime.get("hasMore") is True and following > offset, "native_anchor_capture_no_progress", "Native anchor paging cannot stall.")
            offset = following
            runtime = (await self.structure({"captureId": identifier, "runtimeOffset": offset, "limit": 128}))["runtimeObservation"]
        return positions, capture

    async def native_visit_capture(self, initial, alignment, controller):
        positions, groups, header, offset = [], {}, None, 0
        data = initial
        while True:
            runtime = data.get("runtimeObservation", {})
            current = _native_visit_header(runtime, controller, self.args["dimension"], alignment)
            self.require(header is None or current == header, "native_visit_capture_changed",
                "Every unfiltered page must retain one immutable complete source capture context.")
            self.require("captureId" not in self.args["geometrySource"] or current["captureId"] == self.args["geometrySource"]["captureId"],
                "native_visit_capture_changed", "An explicitly requested source capture ID cannot be substituted.")
            header = current
            rows, page_groups, following = runtime.get("positions"), runtime.get("groups"), runtime.get("nextRuntimeOffset")
            self.require(type(runtime.get("runtimeOffset")) is int and runtime["runtimeOffset"] == offset
                and isinstance(rows, list) and len(rows) <= 128 and isinstance(page_groups, list) and len(page_groups) <= 128
                and type(following) is int and following == offset + len(rows) <= header["capturedPositions"]
                and type(runtime.get("runtimePageVisited")) is int and runtime["runtimePageVisited"] == len(rows),
                "native_visit_capture_page_invalid", "Complete unfiltered raw offsets must account for every retained native coordinate exactly.")
            for group in page_groups:
                self.require(isinstance(group, dict) and type(group.get("id")) is int and 0 <= group["id"] < 128,
                    "native_visit_group_geometry_unavailable", "Each actual native group must have a bounded typed identity.")
                identifier = group["id"]
                self.require(identifier not in groups or groups[identifier] == group, "native_visit_group_changed",
                    "The actual public group DTO cannot change between pages of one immutable capture.")
                groups[identifier] = group
            positions.extend(rows)
            self.require(len(groups) <= 128 and len(json.dumps({"positions": positions, "groups": list(groups.values())}, ensure_ascii=False)
                .encode("utf-8")) <= 4_194_304, "placement_geometry_memory_budget", "Complete visit/group retention exceeded four MiB.")
            if runtime.get("hasMore") is False:
                self.require(following == header["capturedPositions"], "native_visit_capture_page_invalid", "A complete native page cannot omit its suffix.")
                return _native_visit_union(positions, groups, runtime, controller), runtime, header
            self.require(runtime.get("hasMore") is True and following > offset, "native_visit_capture_no_progress",
                "A native capture's returned positive suffix advances once; partial/uncertain pages are never replayed.")
            offset = following
            data = await self.structure({"captureId": header["captureId"], "runtimeOffset": offset, "limit": 128}, guard_after=True)

    async def match_public_piece(self, initial, alignment, positions, capture):
        """Select only one full public piece with the exact captured origin ABC."""
        reason = "public_piece_selection_required"
        captured = {tuple(row[axis] for axis in ABC) for row in positions}
        self.require(capture is not None and positions and len(captured) == len(positions), reason,
            "A multipart/repeated native capture cannot be inferred as one piece from repeated origin ABC.")

        def context(data):
            runtime = data.get("runtimeObservation", {})
            self.require(data.get("alignment") == alignment and runtime.get("available") is True
                and runtime.get("capturedVisitsComplete") is True and runtime.get("eventCaptureTruncated") is False
                and all(runtime.get(flag) is True for flag in ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches"))
                and all(type(runtime.get(key)) is type(capture.get(key)) and runtime.get(key) == capture.get(key)
                    for key in ("captureId", "capturedPositions", "capturedFacing", "capturedMode", "controller", "trigger")),
                "native_capture_changed", "Every public candidate read must retain the exact complete native capture context.")

        catalogue, offset, total, data = [], 0, None, initial
        while True:
            context(data)
            rows, count, following = data.get("pieces"), data.get("piecesTotal"), data.get("nextOffset")
            self.require(data.get("query") == "" and data.get("offset") == offset and isinstance(rows, list)
                and len(rows) <= 128 and type(count) is int and 1 <= count <= 100_000 and (total is None or total == count)
                and type(following) is int and following == offset + len(rows) <= count, reason,
                "Unique matching requires the complete unfiltered public piece catalogue and exact raw-prefix progress.")
            for row in rows:
                self.require(isinstance(row, dict) and isinstance(row.get("name"), str) and 1 <= len(row["name"]) <= 128
                    and row.get("selectable") is True and row.get("geometryAvailable") is True
                    and type(row.get("compiledEntries")) is int and 1 <= row["compiledEntries"] <= 100_000, reason,
                    "Every public candidate must expose selectable complete compiled geometry; unknown candidates cannot be excluded.")
                catalogue.append(row)
            total = count
            if data.get("hasMore") is False:
                self.require(following == total and len(catalogue) == total
                    and len({row["name"] for row in catalogue}) == total, reason,
                    "The complete public catalogue must have unique exact names.")
                break
            self.require(data.get("hasMore") is True and following > offset, reason, "Public catalogue paging cannot stall.")
            offset = following
            data = await self.structure({"offset": offset, "limit": 128, "captureId": capture["captureId"]})

        matches, proofs = [], []
        for candidate in catalogue:
            offset, coordinates = 0, []
            while True:
                data = await self.structure({"piece": candidate["name"], "offset": offset, "limit": 128,
                    "captureId": capture["captureId"]})
                context(data)
                rows, following = data.get("positions"), data.get("nextOffset")
                self.require(data.get("mode") == "piece_geometry" and data.get("piece") == candidate["name"]
                    and data.get("query") == "" and data.get("coordinateSpace") == "piece_origin"
                    and data.get("worldPositionsAvailable") is False and data.get("offsets") is None
                    and data.get("compiledEntries") == candidate["compiledEntries"] and data.get("offset") == offset
                    and isinstance(rows, list) and len(rows) <= 128 and type(following) is int
                    and offset <= following <= candidate["compiledEntries"]
                    and all(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in ABC) for row in rows), reason,
                    "Matching needs the entire unfiltered public piece-origin navigation, with no inferred controller offsets.")
                coordinates.extend(tuple(row[axis] for axis in ABC) for row in rows)
                self.require(len(coordinates) <= 16_384, "placement_geometry_memory_budget", "Piece matching exceeded the finite physical-coordinate budget.")
                if data.get("hasMore") is False:
                    self.require(following == candidate["compiledEntries"], reason, "A candidate must finish its complete compiled navigation.")
                    break
                self.require(data.get("hasMore") is True and following > offset, reason, "Public candidate paging cannot stall.")
                offset = following
            unique = set(coordinates)
            self.require(coordinates and len(unique) == len(coordinates), reason,
                "Repeated public physical coordinates do not prove an unambiguous single-piece comparison.")
            matched = unique == captured
            proofs.append({"piece": candidate["name"], "compiledEntries": candidate["compiledEntries"],
                "physicalPositions": len(coordinates), "complete": True, "exactCoordinateSetMatch": matched})
            if matched:
                matches.append(candidate["name"])
        self.require(len(matches) == 1, reason, "Zero or multiple exact public coordinate-set matches require explicit piece selection.")
        return matches[0], {"source": "unique_complete_public_piece_origin_abc_match", "captureId": capture["captureId"],
            "capturedPositions": len(positions), "catalogueComplete": True, "candidates": proofs,
            "matchedPiece": matches[0], "scope": "Piece identity only; native role, formation, anchor and terrain checks remain required."}

    async def geometry(self):
        source = self.args["geometrySource"]
        identity = await self.source_identity()
        initial = await self.structure({"limit": 128, **({"captureId": source["captureId"]} if "captureId" in source else {})})
        controller = initial.get("controller", {})
        self.require(all(controller.get(axis) == source[axis] for axis in AXES) and controller.get("dimension") == self.args["dimension"],
            "geometry_source_changed", "The public definition must belong to the exact live source controller.")
        alignment = initial.get("alignment")
        self.require(isinstance(alignment, dict) and all(isinstance(alignment.get(key), str) for key in ("direction", "rotation", "flip")),
            "public_alignment_missing", "The real public direction, rotation and flip are required.")
        if self.native_visits:
            self.require(not any(key in source for key in ("piece", "offsetA", "offsetB", "offsetC")),
                "native_visit_piece_offsets_unsupported", "Native visit unions never invent a piece or compiled ABC anchor.")
            self.require(isinstance(identity.get("tileClass"), str) and bool(identity["tileClass"]),
                "geometry_source_identity_unavailable", "Native visit placement requires the actual existing source tile class.")
            union, capture, header = await self.native_visit_capture(initial, alignment, controller)
            relative = {key: values.copy() for key, values in union["relativeBounds"].items()}
            relative["min"]["y"] = min(relative["min"]["y"], -1)
            self.require(all(relative["max"][axis] - relative["min"][axis] + 1 <= 129 for axis in AXES),
                "placement_geometry_extent_budget", "Observed visits plus controller/attachment must fit the bounded per-axis scope.")
            return {"geometryMode": "native_visits", "sourceController": point(source), "sourceIdentity": identity,
                "sourceMetaClass": controller["metaClass"], "piece": None, "variant": source.get("variant", 1),
                "offsets": None, "axisTransform": None, "alignment": alignment, "relativeBounds": relative,
                "visitRelativeBounds": union["relativeBounds"], "nativeVisits": union, "sourceCaptureHeader": header,
                "physicalPositions": capture["capturedPositions"], "complete": True, "unclipped": True,
                "fullStructureChecked": False, "completeRulesKnown": False, "attachmentLayerIncluded": True,
                "anchorSource": "complete unfiltered observed native world XYZ plus controller and its lower attachment layer",
                "captureId": capture["captureId"], "sourceCapturedPositions": capture["capturedPositions"],
                "capturedFacing": capture["capturedFacing"], "capturedMode": capture["capturedMode"], "capturedTrigger": capture["trigger"],
                "coverage": "All lossless visits and their actual complete public groups from one fresh native invocation. The whole enclosed controller/attachment box is reserved; hidden chambers, required air, dynamic state and full machine rules remain unknown."}
        explicit = "offsetA" in source
        positions, capture = ([], None) if explicit else await self.native_capture(initial, alignment, controller)
        piece = source.get("piece")
        selection = None
        if piece is None:
            pieces = initial.get("pieces", [])
            if initial.get("hasMore") is False and isinstance(pieces, list) and len(pieces) == 1 \
                    and isinstance(pieces[0], dict) and pieces[0].get("selectable") is True and pieces[0].get("geometryAvailable") is True:
                piece = pieces[0]["name"]
            else:
                self.require(not explicit, "public_piece_selection_required", "Explicit offsets with multiple public pieces also need an explicit piece.")
                piece, selection = await self.match_public_piece(initial, alignment, positions, capture)
        matrix = await self.transform(piece, alignment)
        if explicit:
            offsets = [source["offset" + axis] for axis in ("A", "B", "C")]
        else:
            first = positions[0]
            offsets = [first[ABC[index]] - sum(matrix[row][index] * (first[axis] - source[axis]) for row, axis in enumerate(AXES)) for index in range(3)]
            self.require(all(-2048 <= value <= 2048 for value in offsets) and all(
                row[axis] == source[axis] + sum(matrix[index][column] * (row[ABC[column]] - offsets[column]) for column in range(3))
                for row in positions for index, axis in enumerate(AXES)),
                "public_anchor_translation_ambiguous", "Observed native ABC/XYZ must fit one public piece anchor; multiple dynamic translations are unsupported.")
        extra = {"piece": piece, "limit": 1, "query": "__mcb_geometry_bounds_only__",
            **{"offset" + axis: offsets[index] for index, axis in enumerate(("A", "B", "C"))}}
        previous_scanned = -1
        for attempt in range(64):
            data = await self.structure(extra)
            bounds = data.get("pieceBounds", {})
            self.require(data.get("alignment") == alignment and data.get("piece") == piece
                and data.get("worldPositionsAvailable") is True and bounds.get("available") is True,
                "public_geometry_context_changed", "The compiled geometry must preserve its current source, piece and public alignment.")
            if bounds.get("complete") is True and bounds.get("partial") is False:
                break
            scanned = bounds.get("scannedEntries")
            self.require(type(scanned) is int and (scanned > previous_scanned or attempt < 3),
                "public_geometry_no_progress", "Independent compiled bounds did not progress within their bounded soft-budget reads.")
            previous_scanned = scanned
        else:
            raise BridgeError("PLACEMENT_UNAVAILABLE", "Compiled piece bounds exceeded the read budget.", reason="public_geometry_partial")
        self.require(bounds.get("worldBoundsAvailable") is True and bounds.get("controllerOffsetsAvailable") is True
            and bounds.get("source") == "public_compiled_piece_navigation" and type(bounds.get("physicalPositions")) is int
            and type(bounds.get("compiledEntries")) is int and bounds.get("scannedEntries") == bounds["compiledEntries"]
            and 1 <= bounds["physicalPositions"] <= min(16_384, bounds["compiledEntries"]), "public_geometry_anchor_missing", "Full compiled navigation needs an exact public world mapping and finite physical-position count.")
        world = box(bounds.get("worldXYZ"))
        if positions:
            origin = box(bounds.get("originABC"))
            self.require(all(origin["min"][axis] <= row[axis] <= origin["max"][axis] for row in positions for axis in ABC)
                and len(positions) <= bounds["physicalPositions"], "native_capture_piece_mismatch", "Native anchor observations are not contained in the full public piece geometry.")
        relative = {key: {axis: min(value[axis] - source[axis], 0) if key == "min" else max(value[axis] - source[axis], 0)
            for axis in AXES} for key, value in world.items()}
        self.require(all(relative["max"][axis] - relative["min"][axis] + 1 <= 129 for axis in AXES),
            "placement_geometry_extent_budget", "The conservative geometry envelope exceeds the finite 129-cell per-axis convenience scope.")
        header = _native_visit_header(capture, controller, self.args["dimension"], alignment) \
            if capture and capture.get("source") == "client_native_blueprint_hints" else None
        return {"geometryMode": "public_piece", "sourceController": point(source), "sourceIdentity": identity,
            "sourceMetaClass": controller.get("metaClass"), "sourceCaptureHeader": header, "piece": piece, "variant": source.get("variant", 1),
            "offsets": dict(zip(ABC, offsets)), "alignment": alignment, "axisTransform": matrix, "relativeBounds": relative,
            "physicalPositions": bounds["physicalPositions"], "complete": True, "unclipped": True,
            **({"pieceSelection": selection} if selection is not None else {}),
            "anchorSource": "explicit caller offsets with public transform" if explicit else "native ABC/XYZ calibrated against public zero/unit-offset transforms",
            "captureId": capture.get("captureId") if capture else None,
            "sourceCapturedPositions": len(positions) if capture else None,
            "capturedFacing": capture.get("capturedFacing") if capture else None,
            "capturedMode": capture.get("capturedMode") if capture else None,
            "capturedTrigger": capture.get("trigger") if capture else None,
            "coverage": "Full public compiled piece bounds plus the controller; the entire enclosed box is reserved as air. Original exterior padding and dynamic pieces remain unknown."}

    async def scan(self, bounds, *, literal_air=False):
        validate_scan_region({"bounds": bounds})
        volume = 1
        for axis in ("X", "Y", "Z"):
            volume *= bounds["max" + axis] - bounds["min" + axis] + 1
        self.require(self.scan_cells + self.exact_cells + volume <= self.args.get("maxScanCells", 262_144), "placement_scan_volume_budget", "Placement requires a declared finite scan volume; it never drains the maximum world volume.")
        blocks, seen_cursors, previous, retained = [], set(), 0, 0
        previous_counts = {key: 0 for key in ("air", "nonAir", "unloaded")}
        request = {"bounds": bounds, "dimension": self.args["dimension"], "limit": 512,
            **({"airSemantics": "minecraft_air"} if literal_air else {})}
        zero_pages = 0
        while True:
            data = await self.call("world.scan", request)
            self.scan_pages += 1
            coverage, page = data.get("coverage", {}), data.get("page", {})
            rows = data.get("blocks")
            if literal_air:
                self.require(data.get("airSemantics") == "minecraft_air" and data.get("nonAtomic") is True
                    and type(data.get("complete")) is bool and type(data.get("requestedRegionFullyObserved")) is bool
                    and data["requestedRegionFullyObserved"] is (data["complete"] and coverage.get("unloaded") == 0)
                    and type(coverage.get("loaded")) is int and coverage["loaded"] == coverage.get("examined", 0) - coverage.get("unloaded", 0)
                    and all(type(page.get(key)) is int for key in ("examined", "air", "nonAir", "unloaded", "loaded", "startOffset", "endOffsetExclusive"))
                    and page["examined"] == coverage.get("examined", 0) - previous
                    and all(page[key] == coverage.get(key, 0) - previous_counts[key] for key in previous_counts)
                    and page["loaded"] == page["examined"] - page["unloaded"],
                    "placement_exact_scan_coverage_invalid", "Literal-air scan pages must preserve their exact air semantics and complete loaded prefix accounting.")
            self.require(data.get("source") == "integrated_server" and data.get("dimension") == self.args["dimension"]
                and data.get("bounds") == bounds and data.get("outOfWorld") == 0
                and data.get("requestedVolume") == volume and data.get("scannableVolume") == volume
                and type(coverage.get("examined")) is int and previous <= coverage["examined"] <= volume
                and all(type(coverage.get(key)) is int and coverage[key] >= 0 for key in ("air", "nonAir", "unloaded", "remaining"))
                and coverage["air"] + coverage["nonAir"] + coverage["unloaded"] == coverage["examined"]
                and coverage["remaining"] == volume - coverage["examined"]
                and page.get("startOffset") == previous and page.get("endOffsetExclusive") == coverage["examined"]
                and isinstance(rows, list) and len(rows) <= 512 and page.get("nonAir") == len(rows)
                and all(coverage[key] >= previous_counts[key] for key in previous_counts)
                and coverage["nonAir"] == previous_counts["nonAir"] + len(rows),
                "placement_scan_coverage_invalid", "Terrain pages must cover the exact uncut box with contiguous authoritative raw offsets and typed coverage.")
            self.require(all(isinstance(row, dict) and isinstance(row.get("registryName"), str)
                and all(type(row.get(axis)) is int and bounds["min" + axis.upper()] <= row[axis] <= bounds["max" + axis.upper()] for axis in AXES) for row in rows),
                "placement_scan_identity_invalid", "Each terrain obstacle must retain its exact current world coordinate and identity.")
            if literal_air:
                self.require(all(type(row.get("metadata")) is int and row["registryName"] != "minecraft:air"
                    and ("tileClass" not in row or isinstance(row["tileClass"], str)) for row in rows),
                    "placement_exact_scan_identity_invalid", "Every literal scan non-air row needs its exact basic block identity; air-like custom blocks cannot disappear.")
                self.exact_cells += coverage["examined"] - previous
            else:
                self.scan_cells += coverage["examined"] - previous
            blocks.extend(rows)
            retained += len(json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
            self.require(retained <= 4_194_304 and len(blocks) <= 32_768, "placement_scan_memory_budget", "Terrain retention exceeded its independent bounded memory budget.")
            if data.get("complete") is True:
                self.require(coverage["examined"] == volume and data.get("nextCursor") is None, "placement_scan_coverage_invalid", "Complete scan coverage cannot omit a suffix.")
                self.summary["probeCoverage"] = {**self.coverage(), "unloaded": coverage["unloaded"]}
                self.require(coverage["unloaded"] == 0, "placement_unloaded_cells", "Unloaded cells remain unknown; no chunks are forced loaded.")
                self.require(len({tuple(row[axis] for axis in AXES) for row in blocks}) == len(blocks)
                    and coverage["nonAir"] == len(blocks), "placement_scan_duplicate_identity", "Terrain coverage cannot contain repeated or omitted non-air cells.")
                return blocks
            cursor = data.get("nextCursor")
            self.require(data.get("complete") is False and isinstance(cursor, str) and cursor and cursor not in seen_cursors,
                "placement_scan_cursor_invalid", "Only a new returned cursor can continue this finite read; consumed cursors are never replayed.")
            seen_cursors.add(cursor)
            zero_pages = zero_pages + 1 if coverage["examined"] == previous else 0
            self.require(zero_pages <= 4, "placement_scan_no_progress", "Terrain scan repeatedly exhausted its tick budget without progress.")
            if zero_pages:
                await anyio.sleep(.05)
            previous = coverage["examined"]
            previous_counts = {key: coverage[key] for key in previous_counts}
            request = {"cursor": cursor, "limit": 512}

    async def exact_air(self, bounds, *, owned_controller=None, owned_identity=None, owned_seeds=None):
        seeds = {tuple(row["position"][axis] for axis in AXES): row["block"] for row in (owned_seeds or [])}
        validate_scan_region({"bounds": bounds})
        volume = (bounds["maxX"] - bounds["minX"] + 1) * (bounds["maxY"] - bounds["minY"] + 1) * (bounds["maxZ"] - bounds["minZ"] + 1)
        self.require(self.scan_cells + self.exact_cells + volume - int(owned_controller is not None and not self.literal_scan and not self.native_visits) <= self.args.get("maxScanCells", 262_144),
            "placement_scan_volume_budget", "Exact geometry revalidation must fit the remaining total placement cell-read budget.")
        owned_block = None
        if self.native_visits and owned_controller is not None:
            self.require(all(bounds["min" + axis.upper()] <= owned_controller[axis] <= bounds["max" + axis.upper()] for axis in AXES),
                "placement_owned_controller_changed", "The one declared owned controller must be inside this exact complete reserved box.")
            owned_block = await self.call("world.inspect", {**point(owned_controller), "dimension": self.args["dimension"]})
            meta = owned_block.get("gregtech", {})
            self.require(isinstance(owned_identity, dict) and owned_block.get("source") == "integrated_server"
                and type(owned_block.get("dimension")) is int and owned_block["dimension"] == self.args["dimension"]
                and all(type(owned_block.get(axis)) is int and owned_block[axis] == owned_controller[axis] for axis in AXES)
                and owned_block.get("registryName") == owned_identity.get("registryName")
                and type(owned_block.get("metadata")) is int and owned_block["metadata"] == owned_identity.get("metadata")
                and isinstance(owned_block.get("tileClass"), str) and owned_block["tileClass"] == owned_identity.get("tileClass")
                and (type(meta.get("metaTileId")) is int and meta["metaTileId"] == owned_identity["metaTileId"]
                    if "metaTileId" in owned_identity else True),
                "placement_owned_controller_changed", "The sole reserved-cell exception needs the exact current full server controller identity and existing tile class.")
        if self.literal_scan:
            rows = await self.scan(bounds, literal_air=True)
            if owned_controller is None:
                self.require(not rows, "placement_exact_non_air", "Every reserved cell must actually be minecraft:air.")
            else:
                self.require(len(rows) == 1 and all(rows[0][axis] == owned_controller[axis] for axis in AXES),
                    "placement_exact_non_air", "Only the one currently verified owned controller may occupy the reserved box; every other cell must actually be minecraft:air.")
                block = await self.call("world.inspect", {**point(owned_controller), "dimension": self.args["dimension"]})
                meta = block.get("gregtech", {})
                self.require(isinstance(owned_identity, dict) and block.get("source") == "integrated_server"
                    and block.get("dimension") == self.args["dimension"]
                    and all(type(block.get(axis)) is int and block[axis] == owned_controller[axis] for axis in AXES)
                    and block.get("registryName") == owned_identity.get("registryName") and type(block.get("metadata")) is int
                    and (type(meta.get("metaTileId")) is int and meta["metaTileId"] == owned_identity["metaTileId"]
                        if "metaTileId" in owned_identity else block["metadata"] == owned_identity.get("metadata"))
                    and all(rows[0].get(key) == block.get(key) for key in ("registryName", "metadata", "tileClass")),
                    "placement_owned_controller_changed", "The literal scan's only exception needs the current exact server controller registry/meta-tile identity and matching observed basic block identity.")
            return
        positions = ({"x": x, "y": y, "z": z} for x in range(bounds["minX"], bounds["maxX"] + 1)
            for y in range(bounds["minY"], bounds["maxY"] + 1) for z in range(bounds["minZ"], bounds["maxZ"] + 1)
            if self.native_visits or owned_controller is None or (x, y, z) != tuple(owned_controller[axis] for axis in AXES))
        while True:
            pending = list(islice(positions, 128 if self.compact else 16))
            if not pending:
                break
            while pending:
                args = {"positions": pending, "dimension": self.args["dimension"], **({"compact": True} if self.compact else {})}
                data = await self.call("world.inspect_many", args)
                rows = data.get("results")
                count = data.get("processedCount") if self.compact else len(pending)
                self.require(data.get("source") == "integrated_server" and data.get("dimension") == self.args["dimension"]
                    and type(count) is int and 1 <= count <= len(pending) and isinstance(rows, list) and len(rows) == count,
                    "placement_exact_coverage_invalid", "Exact geometry-cell reads require current loaded authoritative server prefixes.")
                if self.compact:
                    self.require(data.get("requestedCount") == len(pending) and data.get("nextOffset") == count
                        and data.get("complete") is (count == len(pending)) and data.get("partial") is (count != len(pending))
                        and data.get("nonAtomic") is True and type(data.get("worldTick")) is int
                        and all(type(data.get(key)) is bool for key in ("byteLimited", "budgetLimited"))
                        and (count == len(pending) or data["byteLimited"] or data["budgetLimited"]),
                        "placement_exact_coverage_invalid", "Compact exact reads must account for each unprocessed suffix without re-reading its prefix.")
                self.exact_cells += count
                for index, (expected, row) in enumerate(zip(pending[:count], rows)):
                    block = row.get("data") if isinstance(row, dict) and row.get("ok") is True else None
                    self.require(isinstance(block, dict) and block.get("source") == "integrated_server"
                        and block.get("dimension") == self.args["dimension"] and all(type(block.get(axis)) is int and block[axis] == expected[axis] for axis in AXES)
                        and (not self.compact or block.get("loaded") is True and type(row.get("requestIndex")) is int and row["requestIndex"] == index),
                        "placement_exact_coverage_invalid", "Every enclosed geometry/air cell must retain the exact loaded server identity.")
                    self.require(not self.native_visits or type(data.get("dimension")) is int
                        and type(data.get("requestedCount")) is int and type(data.get("nextOffset")) is int
                        and data["worldTick"] >= 0 and type(block.get("dimension")) is int
                        and type(block.get("metadata")) is int and 0 <= block["metadata"] <= 15,
                        "placement_exact_coverage_invalid", "Native reserved-cell proof needs complete typed basic block metadata, dimension and compact prefix accounting.")
                    if self.native_visits and owned_controller is not None and expected == point(owned_controller):
                        self.require(all(block.get(key) == owned_block.get(key) for key in ("registryName", "metadata", "tileClass")),
                            "placement_owned_controller_changed", "Compact coverage must retain the same actual full-read owned controller, including its positive existing-tile proof.")
                    elif self.native_visits and tuple(expected[axis] for axis in AXES) in seeds:
                        identity = seeds[tuple(expected[axis] for axis in AXES)]
                        self.require(all(block.get(key) == value for key, value in identity.items()) and "tileClass" not in block,
                            "placement_owned_seed_changed", "Every accounted permanent seed must retain its exact loaded server block identity and explicit absence of an existing tile.")
                    else:
                        self.require(block.get("registryName") == "minecraft:air", "placement_exact_non_air", "A scan's air-like omission is insufficient: every reserved cell must actually be minecraft:air.")
                        self.require(not self.native_visits or "tileClass" not in block, "placement_air_cell_existing_tile",
                            "Every reserved air cell must be explicitly free of an existing tile in the complete compact projection; literal scan omission is insufficient.")
                pending = pending[count:]
        if self.native_visits:
            self.summary["existingTileProof"] = {"complete": True, "bounds": bounds.copy(), "cells": volume,
                "method": "world.inspect_many(compact128_existing_tile_map)", "airCellsHaveNoExistingTile": True,
                "ownedControllerOnlyException": owned_controller is not None and not seeds, "ownedController": owned_block,
                "ownedSeedCells": len(seeds), "nonAtomic": True}

    async def execute(self):
        health = await self.health()
        self.require(health.get("protocolVersion") == 1 and health.get("sessionId") == self.args["expectedSessionId"]
            and health.get("profile") == "developer", "placement_developer_context_required", "Public placement planning requires the matching local developer bridge.")
        self.require(health.get("worldScanBounds") is True and health.get("worldBlockPublicMaterial") is True,
            "placement_scan_capability_missing", "Placement requires advertised exact bounds scans and public material properties.")
        self.read_modes(health)
        await self.context()
        geometry = await self.geometry()
        self.summary["geometry"] = geometry
        layout = assembly_layout(geometry["relativeBounds"], self.args.get("layoutComponents", []), self.args.get("serviceAccess"))
        relative, anchor = layout["combinedRelativeBounds"], self.args["anchor"]
        above = layout["aboveGroundRelativeBounds"]
        if layout["components"]:
            self.summary["assemblyLayout"] = layout
        radius, clearance = self.args.get("searchRadius", 4), self.args.get("clearance", 1)
        height = above["max"]["y"] - above["min"]["y"]
        band = self.args.get("groundBand", {"minY": 0, "maxY": min(32, 254 - clearance - height)})
        self.require(0 <= band["minY"] <= band["maxY"] <= 254, "placement_ground_band_invalid", "Ground planning requires an explicit ordered finite vertical band.")
        region = {"minX": anchor["x"] - radius + relative["min"]["x"], "maxX": anchor["x"] + radius + relative["max"]["x"],
            "minZ": anchor["z"] - radius + relative["min"]["z"], "maxZ": anchor["z"] + radius + relative["max"]["z"],
            "minY": band["minY"], "maxY": band["maxY"] + clearance + height}
        blocks = await self.scan(region)
        floors = {}
        occupied = {tuple(row[axis] for axis in AXES): row for row in blocks}
        for x in range(region["minX"], region["maxX"] + 1):
            for z in range(region["minZ"], region["maxZ"] + 1):
                for y in range(band["minY"], band["maxY"] + 1):
                    row = occupied.get((x, y, z))
                    material = row.get("publicMaterial") if row is not None else None
                    if row is None or "tileClass" in row or not isinstance(material, dict) \
                            or not all(type(material.get(key)) is bool for key in ("solid", "liquid", "replaceable")) \
                            or material != {"solid": True, "liquid": False, "replaceable": False}:
                        break
                    floors[(x, z)] = row
        candidates, reported_obstacles = [], set()
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                x, z = anchor["x"] + dx, anchor["z"] + dz
                cells = [floors.get((cx, cz)) for cx in range(x + relative["min"]["x"], x + relative["max"]["x"] + 1)
                    for cz in range(z + relative["min"]["z"], z + relative["max"]["z"] + 1)]
                if not all(row is not None and isinstance(row.get("publicMaterial"), dict)
                    and all(type(row["publicMaterial"].get(key)) is bool for key in ("solid", "liquid", "replaceable"))
                    and row["publicMaterial"] == {"solid": True, "liquid": False, "replaceable": False} for row in cells):
                    continue
                ground = max(row["y"] for row in cells)
                y = ground + clearance - above["min"]["y"]
                proposed = {"x": x, "y": y, "z": z}
                bounds = world_box(relative, proposed)
                boxes = [world_box(value, proposed) for value in layout["reservedRelativeBoxes"]]
                if not 2 <= y <= 251 or bounds["minY"] < 0 or bounds["maxY"] > 255:
                    continue
                collisions = [row for (cx, cy, cz), row in occupied.items() if bounds["minX"] <= cx <= bounds["maxX"]
                    and bounds["minY"] <= cy <= bounds["maxY"] and bounds["minZ"] <= cz <= bounds["maxZ"]
                    and any(contains(region, row) for region in boxes)]
                if not collisions:
                    candidates.append((y, abs(dx) + abs(dz), dx * dx + dz * dz, x, z, proposed, bounds, min(row["y"] for row in cells), ground))
                else:
                    if len(boxes) > 1 and any(any(contains(region, row) for region in boxes[1:]) for row in collisions):
                        self.summary["hiddenRoutingBlocked"] = True
                    for row in collisions:
                        key = tuple(row[axis] for axis in AXES)
                        if key in reported_obstacles:
                            continue
                        if len(self.summary["obstacles"]) == 32:
                            self.summary["obstaclesTruncated"] = True
                            continue
                        reported_obstacles.add(key)
                        self.summary["obstacles"].append({**point(row), "registryName": row["registryName"]})
        self.require(bool(candidates), "placement_no_clear_candidate", "No bounded candidate has complete solid-material ground and clear machine/equipment/IO reservations. Hidden IO still needs a clear bottom-layer trench; no excavation or overwrite is performed.")
        selected = min(candidates)
        proposed, bounds = selected[5], selected[6]
        reserved_boxes = [world_box(value, proposed) for value in layout["reservedRelativeBoxes"]]
        await self.context()
        if not self.literal_scan and not self.native_visits:
            for reserved in reserved_boxes:
                fresh = await self.scan(reserved)
                self.require(not fresh, "placement_candidate_changed", "The selected assembly space changed during fresh revalidation; no automatic alternate plan is replayed.")
        await verify_layout_air(self, reserved_boxes)
        if not await self.source_current(geometry, guard_after=True):
            await self.context()
        self.summary.pop("hiddenRoutingBlocked", None)
        self.summary.update(status="completed", available=True, complete=True, partial=False, spaceVerified=True,
            proposedController=proposed, reservedBounds=bounds, floorBlockY=selected[8],
            exactAirMethod="world.inspect_many(compact128_existing_tile_map)" if self.native_visits else "world.scan(minecraft_air)" if self.literal_scan else "world.inspect_many",
            groundRange={"minY": selected[7], "maxY": selected[8], "band": band,
                "policy": "Contiguous material layers from the declared ground-band bottom must be solid, non-liquid, non-replaceable and have no tileClass; floating obstacles and machines never become ground. Material properties do not prove full support faces."},
            candidateCount=len(candidates), requestedAnchor=anchor, geometryRevalidationRequired=True)
        if layout["components"]:
            self.summary["assemblyLayout"] = placed_layout(layout, proposed)
            self.summary["unknown"].append("Auxiliary equipment and access space are caller-declared; undeclared AE components are not discovered or reserved.")
        self.summary["probeCoverage"] = {**self.coverage(), "unloaded": 0}
        return self.summary


async def run_placement_preflight(client, arguments, *, call=None, _discovery=None):
    planner = _Planner(client, arguments, call, discovery=_discovery)
    try:
        return await planner.execute()
    except (BridgeError, TimeoutError, AttributeError, KeyError, TypeError, ValueError) as error:
        reason = error.details.get("reason", error.code) if isinstance(error, BridgeError) else "placement_deadline" if isinstance(error, TimeoutError) else "placement_malformed_observation"
        planner.summary.update(reason=reason, partial=reason.startswith("placement_scan") or reason in ("placement_deadline", "placement_request_budget", "placement_unloaded_cells"),
            error=error.as_dict() if isinstance(error, BridgeError) else {"code": "PLACEMENT_UNAVAILABLE", "message": "The bounded placement read could not establish complete current coverage."},
            probeCoverage=planner.coverage())
        return planner.summary


async def revalidate_placement(client, arguments, plan, *, call=None, owned_controller=None, owned_seeds=None, _discovery=None):
    """Fresh world proof immediately before a new controller, never a saved lease."""
    current_arguments, geometry, target_bound = _target_validation_view(arguments, plan, owned_controller)
    planner = _Planner(client, current_arguments, call, plan["probeCoverage"], discovery=_discovery)
    before = planner.coverage()
    try:
        planner.require(arguments.get("geometryMode", "public_piece") == plan["geometry"].get("geometryMode", "public_piece"),
            "placement_geometry_mode_changed", "Fresh revalidation cannot switch the planned geometry mode or weaken its existing-tile proof.")
        layout = assembly_layout(geometry["relativeBounds"], arguments.get("layoutComponents", []), arguments.get("serviceAccess"))
        placed = placed_layout(layout, plan["proposedController"])
        planner.require((not layout["components"] and "assemblyLayout" not in plan or plan.get("assemblyLayout") == placed)
            and plan["reservedBounds"] == world_box(layout["combinedRelativeBounds"], plan["proposedController"]),
            "placement_layout_changed", "Fresh revalidation must preserve the complete declared equipment and hidden IO layout.")
        health = await planner.health()
        planner.require(health.get("protocolVersion") == 1 and health.get("sessionId") == arguments["expectedSessionId"]
            and health.get("profile") == "developer", "placement_developer_context_required", "Fresh revalidation requires the matching local developer bridge.")
        planner.read_modes(health)
        await planner.context()
        await verify_layout_air(planner, placed["reservedBoxes"], owned_controller=owned_controller,
            owned_identity=geometry["sourceIdentity"], owned_seeds=owned_seeds)
        if not await planner.source_current(geometry, guard_after=True):
            await planner.context()
    finally:
        plan["probeCoverage"] = planner.coverage()
    return {"spaceVerified": True, "ownedControllerExcluded": owned_controller is not None,
        "ownedSeedCells": len(owned_seeds or []),
        **({"validationSource": {"kind": "accepted_target", "controller": point(current_arguments["geometrySource"]),
            "captureId": geometry["captureId"]}} if target_bound else {}),
        "ownedControllerCellCounted": owned_controller is not None and (planner.literal_scan or planner.native_visits),
        "exactAirMethod": "world.inspect_many(compact128_existing_tile_map)" if planner.native_visits else "world.scan(minecraft_air)" if planner.literal_scan else "world.inspect_many",
        **({"existingTileProof": planner.summary["existingTileProof"]} if planner.native_visits else {}),
        "probeCoverage": {"requests": planner.requests - before["requests"], "exactCellChecks": planner.exact_cells - before["exactCellChecks"], "nonAtomic": True},
        "totalPlacementCoverage": planner.coverage()}
