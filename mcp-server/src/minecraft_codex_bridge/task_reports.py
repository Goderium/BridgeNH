"""Compact task acceptance reports; detailed failures remain inspectable."""


_PORT_SUMMARY = ("id", "role", "position", "item", "groupSelection", "supplyBinding", "placementAttempted", "placementConfirmed",
    "nativePlaceBlockUsed", "preservedAfterNativeShell", "futureOperatingFaces", "portGroup", "ordinal",
    "relativePosition", "declaredItem", "exactDeclaredPositionVerified", "expectedRoleIndex", "registeredOrderVerified")
_PLACEMENT_SUMMARY = ("status", "available", "complete", "partial", "nonAtomic", "machineRulesKnown", "spaceVerified",
    "proposedController", "reservedBounds", "exactAirMethod", "newNativeGeometryVerified", "probeCoverage", "geometry",
    "beforeController", "beforeNativeConstruction", "assemblyLayout")
_GEOMETRY_SUMMARY = ("sourceController", "sourceIdentity", "piece", "variant", "physicalPositions", "complete",
    "unclipped", "coverage")
_REVALIDATION_SUMMARY = ("spaceVerified", "ownedControllerExcluded", "ownedControllerCellCounted", "ownedSeedCells")
_BOUNDS_SUMMARY = ("available", "complete", "partial", "completeStructure", "worldXYZ")
_SUPPORT_SUMMARY = ("position", "placementAttempted", "placementConfirmed", "cleanupAttempted", "cleanupConfirmed",
    "outcomeUnknown", "block", "nativePath")


def compact_blueprint_report(result, include_details=False):
    """Project successful reports only, preserving acceptance and uncertainty.

    This function does not validate or alter task execution. Failure, unavailable,
    and caller-requested detailed reports retain every original field. Nested
    evidence is copied before projection, so internal task reports stay intact.
    Scope and unknown qualifiers survive even within otherwise omitted diagnostics.
    """
    report = dict(result)
    report["detailsRequested"] = include_details
    report["detailsIncluded"] = include_details or result.get("status") != "completed"
    report["reportScope"] = "full_result" if report["detailsIncluded"] else "compact_success"
    if report["detailsIncluded"]:
        return report
    omitted = []

    def qualifications(value):
        if isinstance(value, dict):
            retained = {}
            for key, item in value.items():
                name = key.lower() if isinstance(key, str) else ""
                if "scope" in name or "unknown" in name or (name == "coverage" and isinstance(item, str)):
                    retained[key] = item
                else:
                    nested = qualifications(item)
                    if nested is not None:
                        retained[key] = nested
            return retained or None
        if isinstance(value, list) and any(qualifications(item) is not None for item in value):
            return value
        return None

    def project(value, keys, path):
        retained = {key: value[key] for key in keys if key in value}
        for key, item in (qualifications(value) or {}).items():
            if key not in retained:
                retained[key] = item
        if retained != value:
            omitted.append(path)
        return retained

    ports = result.get("selectedPorts")
    if isinstance(ports, list):
        report["selectedPorts"] = [project(port, _PORT_SUMMARY, "selectedPorts." + str(index))
            if isinstance(port, dict) else port for index, port in enumerate(ports)]
    placement = result.get("placement")
    if isinstance(placement, dict):
        report["placement"] = project(placement, _PLACEMENT_SUMMARY, "placement")
        geometry = placement.get("geometry")
        if isinstance(geometry, dict):
            report["placement"]["geometry"] = project(geometry, _GEOMETRY_SUMMARY, "placement.geometry")
        for stage in ("beforeController", "beforeNativeConstruction"):
            if isinstance(placement.get(stage), dict):
                report["placement"][stage] = project(placement[stage], _REVALIDATION_SUMMARY, "placement." + stage)
    bounds = result.get("observedBounds")
    if isinstance(bounds, dict):
        report["observedBounds"] = project(bounds, _BOUNDS_SUMMARY, "observedBounds")
    cleanup = result.get("cleanup")
    if isinstance(cleanup, dict) and isinstance(cleanup.get("supports"), list):
        report["cleanup"] = dict(cleanup)
        report["cleanup"]["supports"] = [project(support, _SUPPORT_SUMMARY, "cleanup.supports." + str(index))
            if isinstance(support, dict) else support for index, support in enumerate(cleanup["supports"])]
    policy = result.get("positionSelectionPolicy")
    if isinstance(policy, dict):
        report["positionSelectionPolicy"] = project(policy,
            ("movementContext", "floorBlockY", "lowestOperatingY", "preferredOperatingY", "geometryFaceRanking", "scope", "unknown"),
            "positionSelectionPolicy")
    energy = result.get("energyConfiguration")
    if isinstance(energy, dict) and "widgetPageCoverage" in energy:
        report["energyConfiguration"] = project(energy, tuple(key for key in energy if key != "widgetPageCoverage"),
            "energyConfiguration.widgetPageCoverage")
    report["detailsOmitted"] = omitted
    return report
