"""Predicate-only public elements retain geometry, never material/role proof."""
import copy

import pytest

import test_construction_tasks as native
import test_native_visit_placement as visits
import test_placement_preflight as placement

placement_bridge = placement.placement_bridge
native_construction = native.native_construction


def predicate_group(data, identifier=0):
    for group in data["runtimeObservation"]["groups"]:
        if group["id"] == identifier:
            group["candidateCoverage"] = "unavailable_or_predicate_only"
            group["suggestedCandidates"] = []
    return data


async def test_predicate_only_public_group_keeps_complete_paged_geometry(placement_bridge, monkeypatch):
    adapter, world = placement_bridge
    visits.prepare(world).capture_page_size = 5
    original = world.structure
    monkeypatch.setattr(world, "structure", lambda args: predicate_group(original(args)))
    result = await adapter.call_tool("mcb_placement_preflight", visits.arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    geometry = plan["geometry"]
    assert plan["spaceVerified"] and plan["existingTileProof"]["cells"] == 27
    assert not geometry["fullStructureChecked"] and not geometry["completeRulesKnown"]
    assert len(geometry["nativeVisits"]["positions"]) == 26
    group = next(row for row in geometry["nativeVisits"]["groups"] if row["id"] == 0)
    assert group["candidateCoverage"] == "unavailable_or_predicate_only"
    assert group["suggestedCandidates"] == [] and group["roleSelection"]["semanticRoleVerified"] is False
    assert all(op in ("bridge.health", "client.snapshot", "world.structure", "world.inspect", "world.scan", "world.inspect_many")
        for op, _ in world.calls)


@pytest.mark.parametrize("fault", ["class_only", "description_unknown", "description_truncated", "candidates_truncated",
    "candidate_in_predicate", "unknown_coverage", "missing_candidates", "missing_binding", "wrong_capture", "wrong_count"])
async def test_predicate_only_does_not_weaken_geometry_and_binding_guards(placement_bridge, monkeypatch, fault):
    adapter, world = placement_bridge
    visits.prepare(world).capture_page_size = 5
    original = world.structure
    def structure(args):
        data = predicate_group(original(args))
        for group in data["runtimeObservation"]["groups"]:
            if group["id"] != 0:
                continue
            if fault == "class_only": group["identityCoverage"] = "class_only"
            elif fault == "description_unknown": group["descriptionCoverage"] = "unavailable"
            elif fault == "description_truncated": group["descriptionTruncated"] = True
            elif fault == "candidates_truncated": group["candidatesTruncated"] = True
            elif fault == "candidate_in_predicate": group["suggestedCandidates"] = [native.stack(native.CASING)]
            elif fault == "unknown_coverage": group["candidateCoverage"] = "unknown"
            elif fault == "missing_candidates": group.pop("suggestedCandidates")
            elif fault == "missing_binding": group.pop("roleSelection")
            elif fault == "wrong_capture": group["roleSelection"]["captureId"] = "other"
            elif fault == "wrong_count": group["capturedVisits"] += 1
        return data
    monkeypatch.setattr(world, "structure", structure)
    result = await adapter.call_tool("mcb_placement_preflight", visits.arguments())
    assert result.isError and not result.structuredContent["data"]["spaceVerified"], result.structuredContent
    assert result.structuredContent["data"]["reason"] == "native_visit_group_geometry_unavailable"
    assert not any(op == "world.inspect_many" for op, _ in world.calls)


@pytest.mark.parametrize("changed_target", [False, True])
async def test_predicate_geometry_handoff_preserves_semantics_and_native_formation(native_construction, monkeypatch, changed_target):
    client, state = native_construction
    world = visits.NativeWorld()
    original_structure = world.structure
    world.structure = lambda args: predicate_group(original_structure(args), 1)
    wrapped = visits.builder_wrapper(client, world, state, None)
    async def call(operation, args, **options):
        data = await wrapped(operation, args, **options)
        if operation == "world.structure":
            predicate_group(data, 1)
            if changed_target and all(args.get(axis) == state["controller"][axis] for axis in visits.AXES):
                for group in data["runtimeObservation"]["groups"]:
                    if group["id"] == 1:
                        group["candidateCoverage"] = "public_stack_suggestions"
        return data
    monkeypatch.setattr(client, "call", call)
    result = await native.invoke(client, native.declaration(placement={"geometryMode": "native_visits",
        "geometrySource": copy.deepcopy(placement.SOURCE), "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}))
    if changed_target:
        assert result["status"] != "completed" and not result["formationVerified"] and state["construct_count"] == 0, result
        assert result["reason"] == "placement_native_geometry_changed", result
    else:
        assert result["status"] == "completed" and result["formationVerified"], result
        assert result["placement"]["newNativeGeometryProof"]["completeNormalizedXYZAndGroupsMatch"]
        assert result["acceptance"]["registrationAcceptance"]["complete"]
