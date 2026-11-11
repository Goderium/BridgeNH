"""Successful projections retain authoritative evidence and all failure details."""

import copy
import json

import pytest

from minecraft_codex_bridge.task_reports import compact_blueprint_report


@pytest.fixture
def accepted_report():
    item = {"registryName": "gregtech:gt.blockmachines", "metadata": 84}
    point = {"x": -590, "y": 3, "z": -150}
    return {"status": "completed", "available": True, "formationVerified": True,
        "completeRulesKnown": False, "fullStructureChecked": False, "nonAtomic": True,
        "rolledBack": False, "sideEffectsPossible": True, "portsPreservedAfterNativeShell": True,
        "controller": {"position": point, "item": {**item, "metadata": 1002}},
        "selectedPorts": [{"id": "out", "role": "item_output", "position": {**point, "x": -591},
            "item": item, "placementConfirmed": True, "nativePlaceBlockUsed": True,
            "suggestedCandidates": [{"registryName": "gregtech:gt.blockcasings2", "metadata": 1}] * 128,
            "candidateScope": "First visited position only; complete rules remain unknown."}],
        "acceptance": {"structureFormed": True, "registeredRoles": [{"id": "out", "role": "item_output",
            "position": {**point, "x": -591}, "valid": True, "registered": True,
            "finiteInventorySlots": 25, "publicMeProviderAvailable": False}],
            "registrationAcceptance": {"complete": True, "componentCoverageComplete": True,
                "requestedPorts": 1, "scope": "Only the declared registered roles and coordinates."}},
        "placement": {"complete": True, "partial": False, "nonAtomic": True, "machineRulesKnown": False,
            "spaceVerified": True, "unknown": ["Unobserved dynamic pieces remain unknown."],
            "proposedController": point, "reservedBounds": {"minX": -591, "maxX": -589},
            "geometry": {"sourceController": {"x": -478, "y": 3, "z": -120},
                "sourceIdentity": {**item, "metaTileId": 1002}, "piece": "public.piece",
                "physicalPositions": 26, "complete": True, "unclipped": True,
                "coverage": "One complete compiled piece; all other machine rules remain unknown.",
                "axisTransform": [[-1, 0, 0], [0, -1, 0], [0, 0, 1]], "offsets": {"a": 1, "b": 1, "c": 0}},
            "groundRange": {"minY": 1, "maxY": 1, "policy": "Public material properties only."},
            "probeCoverage": {"requests": 29, "exactCellChecks": 81, "nonAtomic": True}},
        "volumeInspection": {"requests": 1, "serverVerifiedAirPositions": 26},
        "positionSelectionPolicy": {"movementContext": "Observed developer flight", "basis": "Native hints only.",
            "scope": "Only the current capture's allowed candidates.", "unknown": "External clearance and walkways remain unknown."},
        "energyConfiguration": {"configured": True, "serverConfirmed": True, "inputVoltage": "8589934592",
            "registeredTierConfirmed": True, "widgetPageCoverage": {"before": {"pages": 1}}},
        "cleanup": {"allConfirmedSupportsRemoved": False, "retainedInventoryVerified": True,
            "error": {"code": "RESTORATION_FAILED"}, "supportError": {"code": "SUPPORT_CHANGED"},
            "worldEditSelection": {"attempted": True, "confirmed": False},
            "supports": [{"position": point, "placementConfirmed": True, "cleanupAttempted": True,
                "cleanupConfirmed": False, "outcomeUnknown": True, "nativePath": "developer_command",
                "block": {"registryName": "minecraft:stone", "metadata": 0}, "hasTile": False}]},
        "inventoryMoves": [{"item": item, "count": "9223372036854775807", "movementConfirmed": True}],
        "retainedInventoryMoves": [{"item": item, "count": "9223372036854775807", "retentionConfirmed": True}],
        "pendingInventoryRestoration": [{"item": item, "count": 1, "outcomeUnknown": True}],
        "timings_ms": {"decision": 2062, "preparation": 1859, "construction": 4719, "acceptance": 937, "total": 9579},
        "taskElapsed_ms": 9579, "futureAcceptanceField": {"known": False}}


def test_success_is_smaller_without_losing_acceptance_or_uncertainty(accepted_report):
    report = compact_blueprint_report(accepted_report)
    assert report["reportScope"] == "compact_success" and report["detailsIncluded"] is False
    for key in ("controller", "acceptance", "completeRulesKnown", "fullStructureChecked", "nonAtomic", "rolledBack",
        "sideEffectsPossible", "portsPreservedAfterNativeShell", "formationVerified", "volumeInspection", "timings_ms",
        "taskElapsed_ms", "inventoryMoves", "retainedInventoryMoves", "pendingInventoryRestoration", "futureAcceptanceField"):
        assert report[key] == accepted_report[key]
    assert report["placement"]["unknown"] == accepted_report["placement"]["unknown"]
    assert report["placement"]["probeCoverage"] == accepted_report["placement"]["probeCoverage"]
    assert report["placement"]["geometry"]["sourceIdentity"] == accepted_report["placement"]["geometry"]["sourceIdentity"]
    assert report["positionSelectionPolicy"]["scope"] == accepted_report["positionSelectionPolicy"]["scope"]
    assert report["positionSelectionPolicy"]["unknown"] == accepted_report["positionSelectionPolicy"]["unknown"]
    assert report["selectedPorts"][0]["item"] == accepted_report["selectedPorts"][0]["item"]
    assert report["selectedPorts"][0]["position"] == accepted_report["selectedPorts"][0]["position"]
    assert report["selectedPorts"][0]["candidateScope"] == accepted_report["selectedPorts"][0]["candidateScope"]
    assert report["energyConfiguration"]["inputVoltage"] == "8589934592"
    assert report["cleanup"]["error"] == {"code": "RESTORATION_FAILED"}
    assert report["cleanup"]["supportError"] == {"code": "SUPPORT_CHANGED"}
    assert report["cleanup"]["worldEditSelection"]["confirmed"] is False
    assert report["cleanup"]["supports"][0]["cleanupConfirmed"] is False
    assert report["cleanup"]["supports"][0]["outcomeUnknown"] is True
    assert "selectedPorts.0" in report["detailsOmitted"]
    assert len(json.dumps(report)) < len(json.dumps(accepted_report)) / 2


def test_success_keeps_one_total_coverage_and_removes_repeated_placement_diagnostics(accepted_report):
    placement = accepted_report["placement"]
    placement.update(status="completed", available=True, newNativeGeometryVerified=True,
        requestedAnchor={"x": -580, "y": 3, "z": -140}, candidateCount=81,
        geometryRevalidationRequired=True)
    coverage = {"requests": 42, "scanPages": 7, "examined": 729, "exactCellChecks": 81, "nonAtomic": True}
    placement["probeCoverage"] = coverage
    for owned, stage in enumerate(("beforeController", "beforeNativeConstruction")):
        placement[stage] = {"spaceVerified": True, "ownedControllerExcluded": bool(owned),
            "ownedControllerCellCounted": bool(owned), "exactAirMethod": "world.scan(minecraft_air)",
            "probeCoverage": {"requests": 7, "exactCellChecks": 27, "nonAtomic": True},
            "totalPlacementCoverage": coverage, "geometryRevalidation": {"metadataRows": [84] * 100}}
    bounds = {"available": True, "complete": True, "partial": False, "completeStructure": False,
        "worldXYZ": {"min": {"x": -591, "y": 3, "z": -150}, "max": {"x": -589, "y": 5, "z": -148}},
        "controllerRelativeXYZ": {"min": {"x": -1, "y": 0, "z": 0}}, "structureABC": {"rows": [0] * 300}}
    accepted_report["observedBounds"] = bounds
    port = accepted_report["selectedPorts"][0]
    port.update(hotbarSlot=4, group=2, descriptionKey="item_output", attachment={"x": -592, "y": 3, "z": -150,
        "side": 5, "hitX": 1.0, "hitY": .5, "hitZ": .5}, groupSelection={"groupId": 2,
        "semanticRoleVerified": False, "scope": "Caller-selected group; hidden machine rules remain unknown."})
    accepted_report["acceptance"]["registeredRoles"].append({"id": "energy", "role": "energy",
        "position": {"x": -589, "y": 3, "z": -150}, "registered": True, "valid": True, "inputTier": 14})
    original = copy.deepcopy(accepted_report)

    report = compact_blueprint_report(accepted_report)

    assert report["acceptance"] == original["acceptance"]
    assert report["placement"]["probeCoverage"] == coverage
    assert report["placement"]["newNativeGeometryVerified"] is True
    for owned, stage in enumerate(("beforeController", "beforeNativeConstruction")):
        assert report["placement"][stage] == {"spaceVerified": True, "ownedControllerExcluded": bool(owned),
            "ownedControllerCellCounted": bool(owned)}
        assert "placement." + stage in report["detailsOmitted"]
    assert set(report["observedBounds"]) == {"available", "complete", "partial", "completeStructure", "worldXYZ"}
    assert report["observedBounds"]["worldXYZ"] == bounds["worldXYZ"]
    assert not {"requestedAnchor", "candidateCount", "geometryRevalidationRequired", "groundRange"} & report["placement"].keys()
    assert not {"hotbarSlot", "group", "descriptionKey", "attachment"} & report["selectedPorts"][0].keys()
    assert report["selectedPorts"][0]["groupSelection"] == port["groupSelection"]
    assert "relativeBounds" not in report["placement"]["geometry"]
    assert "observedBounds" in report["detailsOmitted"]
    assert accepted_report == original


def test_scope_and_unknown_survive_inside_omitted_diagnostics(accepted_report):
    placement = accepted_report["placement"]
    placement["geometryRevalidation"] = {"ticks": 12, "diagnostics": {"scope": "Current captured piece only.",
        "unknownChecks": 2, "metadataRows": [84] * 100}}
    placement["geometry"]["futureDiagnostics"] = {"outcomeUnknown": True, "captureTicks": 3}
    placement["beforeController"] = {"spaceVerified": True, "ownedControllerExcluded": False,
        "futureProof": {"coverage": "Only current loaded cells; unseen cells remain unknown.", "requests": 3}}
    accepted_report["observedBounds"] = {"available": True, "worldXYZ": {"min": {}, "max": {}},
        "controllerRelativeXYZ": {"scope": "Observed controller basis only.", "min": {"x": -1}}}
    port = accepted_report["selectedPorts"][0]
    port["publicEmptyContents"] = {"coverage": "Exposed storage only; hidden buffers remain unknown.", "slots": 25}
    port["attachment"] = {"x": -592, "nativeChecks": [{"scope": "Only this target face.", "unknownChecks": 0}, {"hitX": 1.0}]}
    accepted_report["energyConfiguration"]["widgetPageCoverage"]["before"]["unknown"] = "Other widget pages remain unknown."
    original = copy.deepcopy(accepted_report)

    report = compact_blueprint_report(accepted_report)

    assert report["placement"]["geometryRevalidation"] == {"diagnostics": {
        "scope": "Current captured piece only.", "unknownChecks": 2}}
    assert report["placement"]["geometry"]["futureDiagnostics"] == {"outcomeUnknown": True}
    assert report["placement"]["beforeController"]["futureProof"] == {
        "coverage": "Only current loaded cells; unseen cells remain unknown."}
    assert report["observedBounds"]["controllerRelativeXYZ"] == {"scope": "Observed controller basis only."}
    assert report["selectedPorts"][0]["publicEmptyContents"] == {
        "coverage": "Exposed storage only; hidden buffers remain unknown."}
    assert report["selectedPorts"][0]["attachment"] == {"nativeChecks": port["attachment"]["nativeChecks"]}
    assert report["energyConfiguration"]["widgetPageCoverage"] == {
        "before": {"unknown": "Other widget pages remain unknown."}}
    assert accepted_report == original


@pytest.mark.parametrize("status", ["failed", "unavailable", "unknown", None])
@pytest.mark.parametrize("include_details", [True, False])
def test_failures_and_unrecognized_status_keep_every_field(accepted_report, status, include_details):
    accepted_report.update(status=status, failedNativeWorkflows=[{"outcomeUnknown": True}],
        lastActionReceipt={"outcomeUnknown": True, "requestId": "once"}, remainingInputs={"count": "9223372036854775807"})
    report = compact_blueprint_report(accepted_report, include_details)
    assert all(report[key] == value for key, value in accepted_report.items())
    assert report["detailsIncluded"] is True and report["detailsRequested"] is include_details
    assert report["reportScope"] == "full_result" and "detailsOmitted" not in report


def test_requested_details_and_original_report_are_preserved(accepted_report):
    original = copy.deepcopy(accepted_report)
    detailed = compact_blueprint_report(accepted_report, True)
    compact = compact_blueprint_report(accepted_report)
    assert detailed["detailsIncluded"] is True and detailed["detailsRequested"] is True
    assert all(detailed[key] == value for key, value in original.items())
    assert accepted_report == original
    assert compact["placement"] is not accepted_report["placement"]
    assert compact["cleanup"] is not accepted_report["cleanup"]
