"""Offline native-target lifecycle handoff through both unchanged geometry gates."""
import copy

import pytest

import test_construction_tasks as native
import test_native_visit_placement as visits
import test_placement_preflight as placement
import minecraft_codex_bridge.construction_tasks as construction
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.placement_tasks import _target_validation_view, revalidate_placement

native_construction = native.native_construction
AXES = ("x", "y", "z")
MODES = ("native_visits", "public_piece")


class RichPublicWorld(placement.World):
    def positions(self):
        rows = super().positions()
        for row in rows:
            row["group"] = int(tuple(row[axis] - placement.SOURCE[axis] for axis in AXES) == (0, 0, 1))
        return rows

    def structure(self, args):
        data = super().structure(args)
        data["runtimeObservation"] = visits.complete_runtime(data["runtimeObservation"], placement.SOURCE,
            self.positions(), "source-capture", self.captured_mode, self.trigger_count, self.alignment)
        return data


def declaration(mode):
    return native.declaration(placement={"geometryMode": mode, "geometrySource": placement.SOURCE.copy(),
        "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0})


def placement_arguments(mode, controller):
    return placement.arguments(geometryMode=mode, anchor=copy.deepcopy(controller))


def instrument(client, state, mode, monkeypatch, *, target_fault=None, fresh_fault=None, thin=False, source_metadata=0):
    world = visits.NativeWorld() if mode == "native_visits" else RichPublicWorld()
    if thin:
        world.structure = lambda args: placement.World.structure(world, args)
    source_block = world.source_block

    def prototype():
        data = source_block()
        data["metadata"] = source_metadata
        return data
    world.source_block = prototype
    wrapped = visits.builder_wrapper(client, world, state, target_fault)
    log, captured = [], {"complete": False}

    async def call(operation, args, **options):
        source = operation in ("world.inspect", "world.structure") and all(args.get(axis) == placement.SOURCE[axis] for axis in AXES)
        target = operation in ("world.inspect", "world.structure") and all(args.get(axis) == state["controller"][axis] for axis in AXES)
        log.append((operation, copy.deepcopy(args), captured["complete"]))
        if source and captured["complete"]:
            raise BridgeError("CHUNK_UNLOADED", "The original prototype is now outside loaded chunks.", reason="chunk_unloaded")
        if operation == "world.scan" and state["placements"]:
            world.extra[native.key(state["controller"])] = {"registryName": native.CONTROLLER_ITEM["registryName"],
                "metadata": 0, "tileClass": "Untrusted.World.Tile"}
        data = await wrapped(operation, args, **options)
        if target and operation == "world.inspect" and captured["complete"]:
            if fresh_fault == "tile_class":
                data["tileClass"] = "Replacement.Tile"
            elif fresh_fault == "meta_tile":
                data["gregtech"]["metaTileId"] += 1
            elif fresh_fault == "registry":
                data["registryName"] = "other:controller"
        if target and operation == "world.structure":
            runtime = data["runtimeObservation"]
            if args.get("limit") == 1 and captured["complete"]:
                if fresh_fault == "ttl":
                    runtime["ageMillis"] = 30001
                elif fresh_fault == "client_age":
                    runtime["clientValidationAgeMillis"] = 1001
                elif fresh_fault == "context":
                    runtime["contextMatches"] = False
                elif fresh_fault == "capture":
                    runtime["captureId"] = "replacement-capture"
                elif fresh_fault == "meta_class":
                    data["controller"]["metaClass"] = runtime["controller"]["metaClass"] = "Replacement.Controller"
                elif fresh_fault == "mode":
                    runtime["capturedMode"] = runtime["serverMode"] = runtime["controller"]["mode"] = 1
                elif fresh_fault == "mode_bool":
                    runtime["capturedMode"] = runtime["serverMode"] = runtime["controller"]["mode"] = False
                elif fresh_fault == "trigger":
                    runtime["trigger"]["channelData"] = "changed"
                elif fresh_fault == "limits_bool":
                    runtime["limits"]["groups"] = True
            elif target_fault == "partial":
                runtime["capturedVisitsComplete"] = False
            elif target_fault == "meta_class" and state["hint_count"]:
                data["controller"]["metaClass"] = runtime["controller"]["metaClass"] = "Replacement.Controller"
            elif target_fault == "missing_header":
                runtime.pop("nativeInvocationReturned")
            elif target_fault == "abc" and runtime["positions"]:
                runtime["positions"][0]["a"] += 1
            if runtime.get("hasMore") is False and args.get("limit") == 128:
                captured["complete"] = True
        return data
    monkeypatch.setattr(client, "call", call)
    return world, log


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("source_metadata", [0, 7])
async def test_original_prototype_can_unload_only_after_complete_target_acceptance(native_construction, monkeypatch, mode, source_metadata):
    client, state = native_construction
    # Complete multi-page reads must retain both original geometry policies.
    state["runtime_page_size"] = 5
    world, log = instrument(client, state, mode, monkeypatch, source_metadata=source_metadata)
    world.capture_page_size = 5
    before = []
    original = construction.revalidate_placement

    async def revalidate(*args, **kwargs):
        plan = args[2]
        if kwargs.get("owned_controller") is not None:
            before.append(copy.deepcopy(plan))
        return await original(*args, **kwargs)
    monkeypatch.setattr(construction, "revalidate_placement", revalidate)
    result = await native.invoke(client, declaration(mode))
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["acceptance"]["registrationAcceptance"]["complete"]
    assert state["hint_count"] == state["construct_count"] == 1
    assert sum(entry["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"] for entry in state["placements"]) == 1
    plan, geometry = result["placement"], result["placement"]["geometry"]
    assert geometry["sourceController"] == placement.SOURCE and geometry["captureId"] == "source-capture"
    assert geometry["sourceCaptureHeader"]["controller"]["x"] == placement.SOURCE["x"]
    assert geometry["sourceIdentity"]["metadata"] == source_metadata
    assert geometry == before[0]["geometry"]
    proof = plan["acceptedTargetValidationSource"]
    assert proof["controller"] == state["controller"] and proof["captureHeader"]["captureId"] == state["capture_id"]
    assert proof["sourceIdentity"]["metadata"] == 0 and proof["sourceIdentity"]["metaTileId"] == native.CONTROLLER_ITEM["metadata"]
    assert "ageMillis" not in proof["captureHeader"] and "clientValidationAgeMillis" not in proof["captureHeader"]
    current = plan["beforeNativeConstruction"]
    assert current["validationSource"] == {"kind": "accepted_target", "controller": state["controller"], "captureId": state["capture_id"]}
    assert current["totalPlacementCoverage"]["requests"] > before[0]["probeCoverage"]["requests"] > plan["beforeController"]["probeCoverage"]["requests"]
    assert current["totalPlacementCoverage"]["exactCellChecks"] >= before[0]["probeCoverage"]["exactCellChecks"]
    assert not any(op in ("world.inspect", "world.structure") and ready
        and all(args.get(axis) == placement.SOURCE[axis] for axis in AXES) for op, args, ready in log)
    fresh = [args for op, args, ready in log if op == "world.structure" and ready and args.get("limit") == 1]
    assert len(fresh) == 1 and fresh[0]["captureId"] == state["capture_id"]
    assert all(fresh[0][axis] == state["controller"][axis] for axis in AXES)
    if mode == "native_visits":
        assert geometry["axisTransform"] is None and plan["newNativeGeometryProof"]["completeNormalizedXYZAndGroupsMatch"]
        assert current["existingTileProof"]["ownedControllerOnlyException"]
    else:
        assert geometry["axisTransform"] == world.matrix and geometry["offsets"] == dict(zip(("a", "b", "c"), world.offsets))
        assert "nativeVisits" not in geometry


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("fault", ["partial", "meta_class", "missing_header", "interior_xyz", "mode", "trigger", "facing", "ttl", "capture_alias"])
async def test_target_capture_must_pass_original_complete_geometry_and_context_gates_before_handoff(native_construction, monkeypatch, mode, fault):
    client, state = native_construction
    instrument(client, state, mode, monkeypatch, target_fault=fault)
    result = await native.invoke(client, declaration(mode))
    assert result["status"] != "completed" and not result["formationVerified"] and state["construct_count"] == 0, result
    assert state["hint_count"] == 1
    assert sum(entry["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"] for entry in state["placements"]) == 1
    assert "acceptedTargetValidationSource" not in result["placement"]


async def test_public_piece_retains_every_xyz_to_abc_gate(native_construction, monkeypatch):
    client, state = native_construction
    instrument(client, state, "public_piece", monkeypatch, target_fault="abc")
    result = await native.invoke(client, declaration("public_piece"))
    assert result["reason"] == "placement_native_geometry_changed" and state["construct_count"] == 0, result
    assert "acceptedTargetValidationSource" not in result["placement"]


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("fault", ["ttl", "client_age", "context", "capture", "meta_class", "mode", "mode_bool", "trigger", "limits_bool", "tile_class", "meta_tile", "registry"])
async def test_fresh_target_change_stops_before_shell_without_fallback_or_rehint(native_construction, monkeypatch, mode, fault):
    client, state = native_construction
    _, log = instrument(client, state, mode, monkeypatch, fresh_fault=fault)
    result = await native.invoke(client, declaration(mode))
    assert result["status"] != "completed" and not result["formationVerified"] and state["construct_count"] == 0, result
    assert result["placement"]["newNativeGeometryVerified"] and "acceptedTargetValidationSource" in result["placement"]
    assert state["hint_count"] == 1
    assert not any(op in ("world.inspect", "world.structure") and ready
        and all(args.get(axis) == placement.SOURCE[axis] for axis in AXES) for op, args, ready in log)


async def test_thin_public_dto_never_synthesizes_target_handoff(native_construction, monkeypatch):
    client, state = native_construction
    instrument(client, state, "public_piece", monkeypatch, thin=True)
    result = await native.invoke(client, declaration("public_piece"))
    assert result["status"] != "completed" and result["reason"] == "chunk_unloaded" and state["construct_count"] == 0, result
    assert result["placement"]["geometry"]["sourceCaptureHeader"] is None
    assert "acceptedTargetValidationSource" not in result["placement"] and state["hint_count"] == 1


@pytest.mark.parametrize("mode", MODES)
async def test_validation_view_copies_every_nested_owner_argument_and_geometry_value(native_construction, monkeypatch, mode):
    client, state = native_construction
    instrument(client, state, mode, monkeypatch)
    result = await native.invoke(client, declaration(mode))
    assert result["status"] == "completed", result
    plan, args = result["placement"], placement_arguments(mode, state["controller"])
    original_plan, original_args = copy.deepcopy(plan), copy.deepcopy(args)
    current_args, view, bound = _target_validation_view(args, plan, state["controller"])
    assert bound and current_args["geometrySource"]["captureId"] == state["capture_id"]
    current_args["geometrySource"]["x"] += 1
    current_args["groundBand"]["minY"] += 1
    view["sourceController"]["x"] += 1
    view["sourceIdentity"]["metadata"] += 1
    view["sourceCaptureHeader"]["controller"]["x"] += 1
    view["sourceCaptureHeader"]["trigger"]["count"] += 1
    view["alignment"]["direction"] = "SOUTH"
    view["relativeBounds"]["min"]["x"] += 1
    if mode == "native_visits":
        view["nativeVisits"]["groups"][0]["roleSelection"]["descriptionKeys"].append("changed")
    else:
        view["axisTransform"][0][0] += 1
        view["offsets"]["a"] += 1
    assert plan == original_plan and args == original_args


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("tamper", ["digest", "gate", "native_place", "confirmed", "tile", "controller", "identity", "identity_bool", "class", "capture_owner", "capture_alias", "proof_keys", "dimension_bool", "geometry_mode"])
async def test_tampered_internal_handoff_cannot_authorize_any_fresh_read_or_shell(native_construction, monkeypatch, mode, tamper):
    client, state = native_construction
    instrument(client, state, mode, monkeypatch)
    original, attempts = construction.revalidate_placement, []

    async def revalidate(*args, **kwargs):
        plan = args[2]
        if kwargs.get("owned_controller") is not None:
            attempts.append(True)
            proof = plan["acceptedTargetValidationSource"]
            if tamper == "digest":
                proof["geometrySha256"] = "0" * 64
            elif tamper == "gate":
                plan["newNativeGeometryVerified"] = False
            elif tamper in ("native_place", "confirmed", "tile"):
                plan["controllerPlacementEvidence"][{"native_place": "nativePlaceBlockUsed", "confirmed": "placementConfirmed", "tile": "hasTile"}[tamper]] = False
            elif tamper == "controller":
                proof["controller"]["x"] += 1
            elif tamper == "identity":
                proof["sourceIdentity"]["metaTileId"] += 1
            elif tamper == "identity_bool":
                proof["sourceIdentity"]["metadata"] = False
            elif tamper == "class":
                proof["metaClass"] = "Replacement.Controller"
            elif tamper == "capture_owner":
                proof["captureHeader"]["controller"]["x"] += 1
            elif tamper == "capture_alias":
                proof["captureHeader"]["captureId"] = "source-capture"
            elif tamper == "proof_keys":
                proof["trustMe"] = True
            elif tamper == "dimension_bool":
                proof["dimension"] = False
            else:
                plan["geometry"]["geometryMode"] = "unknown"
        return await original(*args, **kwargs)
    monkeypatch.setattr(construction, "revalidate_placement", revalidate)
    result = await native.invoke(client, declaration(mode))
    assert result["reason"] == "placement_target_validation_source_unavailable", result
    assert attempts == [True] and state["hint_count"] == 1 and state["construct_count"] == 0


@pytest.mark.parametrize("mode", MODES)
async def test_target_revalidation_retains_original_cumulative_request_budget(native_construction, monkeypatch, mode):
    client, state = native_construction
    instrument(client, state, mode, monkeypatch)
    original = construction.revalidate_placement

    async def revalidate(*args, **kwargs):
        if kwargs.get("owned_controller") is not None:
            args[2]["probeCoverage"]["requests"] = args[1].get("maxRequests", 128)
        return await original(*args, **kwargs)
    monkeypatch.setattr(construction, "revalidate_placement", revalidate)
    result = await native.invoke(client, declaration(mode))
    assert result["reason"] == "placement_request_budget" and state["construct_count"] == 0, result
    assert result["placement"]["probeCoverage"]["requests"] == 128 and state["hint_count"] == 1


@pytest.mark.parametrize("fault", ["first_ttl", "first_client_age", "first_missing_header", "page_missing_header", "page_limits", "page_bounds_bool"])
async def test_complete_public_source_header_is_validated_on_every_calibration_page(native_construction, monkeypatch, fault):
    client, state = native_construction
    world, _ = instrument(client, state, "public_piece", monkeypatch)
    world.capture_page_size = 5
    original = world.structure

    def structure(args):
        data = original(args)
        runtime = data["runtimeObservation"]
        offset = args.get("runtimeOffset", 0)
        if fault == "first_ttl" and offset == 0:
            runtime["ageMillis"] = 30001
        elif fault == "first_client_age" and offset == 0:
            runtime["clientValidationAgeMillis"] = 1001
        elif fault == "first_missing_header" and offset == 0 or fault == "page_missing_header" and offset:
            runtime.pop("nativeInvocationReturned")
        elif fault == "page_limits" and offset:
            runtime["limits"]["groups"] -= 1
        elif fault == "page_bounds_bool" and offset:
            runtime["observedBounds"]["size"] = {"x": True}
        return data
    world.structure = structure
    result = await native.invoke(client, declaration("public_piece"))
    assert result["status"] != "completed" and not state["placements"], result
    assert state["hint_count"] == state["construct_count"] == 0
    assert result["reason"] in ("native_visit_capture_unavailable", "native_anchor_capture_changed")


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("field,value", [("partial", 0), ("complete", 1)])
async def test_saved_target_header_cannot_treat_booleans_as_equal_numbers(native_construction, monkeypatch, mode, field, value):
    client, state = native_construction
    instrument(client, state, mode, monkeypatch)
    original = construction.revalidate_placement

    async def revalidate(*args, **kwargs):
        if kwargs.get("owned_controller") is not None:
            args[2]["acceptedTargetValidationSource"]["captureHeader"]["observedBounds"][field] = value
        return await original(*args, **kwargs)
    monkeypatch.setattr(construction, "revalidate_placement", revalidate)
    result = await native.invoke(client, declaration(mode))
    assert result["reason"] == "geometry_source_changed" and state["construct_count"] == 0, result
    assert state["hint_count"] == 1
