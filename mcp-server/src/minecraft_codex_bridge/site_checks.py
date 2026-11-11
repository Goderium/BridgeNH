"""Read-only site acceptance composed from existing guarded owner reads."""
import time

from .bridge import BridgeError
from .conveniences import path_get, json_sha256, json_size
from .placement_attachment import VECTORS
from .workflows import condition_matches


def _component_page(components, offset):
    """Validate contiguous native page coverage; elapsed time alone is not coverage."""
    if not isinstance(components, dict):
        return None
    total, entries, next_offset = (components.get(key) for key in ("total", "entries", "nextOffset"))
    if (type(total) is not int or not 0 <= offset <= total <= 100000 or
            type(components.get("offset")) is not int or components["offset"] != offset or
            not isinstance(entries, list) or len(entries) > 128 or
            type(next_offset) is not int or next_offset != offset + len(entries) or next_offset > total or
            components.get("hasMore") is not (next_offset < total) or
            type(components.get("softBudgetPartial")) is not bool or
            (components["softBudgetPartial"] and next_offset == total) or
            (next_offset < total and not entries)):
        return None
    if any(not isinstance(entry, dict) or type(entry.get("index")) is not int or entry["index"] != offset + i
           for i, entry in enumerate(entries)):
        return None
    return entries


def layout_checks(volumes):
    checks = []
    for volume in volumes:
        b = volume["bounds"]
        if any(b["min" + axis] > b["max" + axis] for axis in "XYZ"):
            raise BridgeError("INVALID_ARGUMENTS", "Layout bounds must be ordered.")
    for index, first in enumerate(volumes):
        for second in volumes[index + 1:]:
            if first["kind"] == second["kind"] == "clearance":
                continue
            overlap = all(first["bounds"]["min" + axis] <= second["bounds"]["max" + axis]
                          and second["bounds"]["min" + axis] <= first["bounds"]["max" + axis] for axis in "XYZ")
            if overlap:
                checks.append({"id": first["id"], "check": "layout_overlap", "status": "failed", "other": second["id"]})
    return checks or [{"id": "layout", "check": "declared_volume_overlap", "status": "passed"}]


async def check_site(client, arguments, lock, *, runner):
    site = arguments["site"]
    if json_size(site) > 65536:
        raise BridgeError("PLAN_TOO_LARGE", "Site declarations are limited to 64 KiB.")
    ids = [row["id"] for key in ("machines", "endpoints", "clearance", "layout") for row in site.get(key, [])]
    if len(ids) != len(set(ids)):
        raise BridgeError("INVALID_ARGUMENTS", "Site declaration IDs must be unique.")
    groups = {row["network"] for row in site.get("endpoints", [])}
    if any(name not in groups for pair in site.get("isolatedNetworks", []) for name in pair):
        raise BridgeError("INVALID_ARGUMENTS", "Isolation requires two declared endpoint network groups.")
    checks = layout_checks(site["layout"]) if site.get("layout") else []
    receipts = []
    deadline = time.monotonic() + arguments.get("timeout_ms", 10000) / 1000
    dimension = site["dimension"]
    authority_binding = None
    component_continuations = 0

    def authority(value):
        nonlocal authority_binding
        source, connection = value.get("source"), value.get("connectionId")
        if source not in ("integrated_server", "remote_server_tick") or value.get("dimension") != dimension:
            raise BridgeError("SITE_OWNER_UNVERIFIED", "Site checks need authoritative loaded server data in the declared dimension.")
        if source == "remote_server_tick" and (value.get("sessionId") != arguments["expectedSessionId"] or
                not isinstance(connection, str) or not connection):
            raise BridgeError("SITE_OWNER_UNVERIFIED", "Remote site evidence needs its exact guarded session and connection.")
        binding = (source, connection if source == "remote_server_tick" else None)
        if authority_binding is not None and binding != authority_binding:
            raise BridgeError("SITE_OWNER_UNVERIFIED", "Site owner connection changed between observations.")
        authority_binding = binding

    def record(identifier, check, value, **details):
        checks.append({"id": identifier, "check": check,
                       "status": "unknown" if value is None else "passed" if value else "failed", **details})

    async def read(steps):
        if not steps:
            return {}
        left = int((deadline - time.monotonic()) * 1000)
        if left <= 0:
            raise BridgeError("SITE_CHECK_TIMEOUT", "The shared site observation deadline expired.")
        result = await runner(client, {"expectedSessionId": arguments["expectedSessionId"],
            "expectedWorld": arguments["expectedWorld"], "steps": steps, "timeout_ms": max(1, min(left, 60000)),
            "max_return_bytes": 262144}, lock)
        receipts.append({key: result.get(key) for key in ("status", "completedSteps", "elapsed_ms", "error")})
        if result["status"] != "completed":
            raise BridgeError("SITE_OBSERVATION_FAILED", "A guarded site read failed; no action was dispatched.", receipt=receipts[-1])
        observed = {}
        for row in result["steps"]:
            projection = row.get("observation", {})
            if projection.get("omitted") or projection.get("omittedFields") or projection.get("fieldsOmitted"):
                raise BridgeError("SITE_OBSERVATION_INCOMPLETE", "Required site evidence exceeded its projection budget.")
            value = {}
            # Workflow projections retain dotted keys literally. Reconstruct
            # only declared object paths, without inferring omitted values.
            for path, child in projection.get("values", {}).items():
                if path_get(value, path)[0]:
                    continue
                target = value
                parts = path.split(".")
                for part in parts[:-1]:
                    target = target.setdefault(part, {})
                target[parts[-1]] = child
            observed[row["id"]] = value
        return observed

    async def owners(positions, *, ae=False):
        unique = {tuple(p[axis] for axis in "xyz"): {axis: p[axis] for axis in "xyz"} for p in positions}
        if not unique:
            return {}
        if len(unique) > 256:
            raise BridgeError("SITE_POINT_LIMIT", "Derived site observations exceed 256 owner points.")
        points = list(unique.values())
        steps = [{"id": "owners" + str(i), "kind": "read", "tool": "mcb_world_inspect_many",
            "args": {"dimension": dimension, "positions": points[i:i + 16], "compact": False, "includeAe": ae},
            "fields": ["source", "sessionId", "connectionId", "dimension", "results"], "omitEmptySlots": False} for i in range(0, len(points), 16)]
        pages = await read(steps)
        found = {}
        for page in pages.values():
            authority(page)
            for row in page.get("results", []):
                value = row.get("data", {})
                point = tuple(value.get(axis) for axis in "xyz")
                if row.get("ok") is True and point in unique and value.get("dimension") == dimension:
                    if point in found:
                        raise BridgeError("SITE_OWNER_UNVERIFIED", "Duplicate owner evidence is not complete coverage.")
                    found[point] = value
        return found

    def at(values, position):
        return values.get(tuple(position[axis] for axis in "xyz"))

    async def component_pages(step, first):
        nonlocal component_continuations
        page = first.get("components", {})
        entries = _component_page(page, 0)
        if entries is None:
            return [], False, {"reason": "invalid_component_page", "pages": 1}
        entries = list(entries)
        pages = 1
        fingerprint = page.get("directoryFingerprint")
        header = {key: page.get(key) for key in ("total", "roles", "unavailableRoles", "modularHatches")}
        identity = {key: first.get(key) for key in ("x", "y", "z", "dimension", "metaTileId")}
        while page["hasMore"]:
            if not isinstance(fingerprint, str) or not fingerprint or not isinstance(header["roles"], list):
                return entries, False, {"reason": "component_directory_unverified", "pages": pages}
            if component_continuations >= 32:
                return entries, False, {"reason": "component_page_limit", "pages": pages}
            offset = page["nextOffset"]
            followup = {**step, "args": {**step["args"], "componentOffset": offset},
                        "fields": ["source", "sessionId", "connectionId", *identity, "components"]}
            component_continuations += 1
            value = (await read([followup]))[step["id"]]
            authority(value)
            pages += 1
            page = value.get("components", {})
            batch = _component_page(page, offset)
            if (batch is None or any(value.get(key) != expected for key, expected in identity.items()) or
                    page.get("directoryFingerprint") != fingerprint or
                    any(page.get(key) != expected for key, expected in header.items())):
                return entries, False, {"reason": "component_directory_changed_or_invalid_page", "pages": pages}
            entries.extend(batch)
        return entries, True, {"pages": pages, "total": len(entries)}

    exhausts, clearances = [], list(site.get("clearance", []))
    networks = {}
    try:
        machine_steps = [{"id": "machine" + str(i), "kind": "read", "tool": "mcb_machine_inspect",
            "args": {**row["position"], "dimension": dimension, "componentLimit": 128},
            "fields": list(dict.fromkeys(["source", "sessionId", "connectionId", "x", "y", "z", "dimension", "metaTileId", "state", "processing.structureFormed",
                "processing.repairStatus", "processing.idealMaintenanceStatus", "components", "coverage",
                *[r["path"] for r in row.get("requirements", [])]])), "omitEmptySlots": False}
            for i, row in enumerate(site.get("machines", []))]
        machines = await read(machine_steps)
        for index, machine in enumerate(site.get("machines", [])):
            value = machines["machine" + str(index)]
            authority(value)
            identifier = machine["id"]
            identity = all(value.get(k) == v for k, v in {**machine["position"], "dimension": dimension, "metaTileId": machine["metaTileId"]}.items())
            record(identifier, "machine_identity", identity)
            if not identity:
                continue
            for requirement in [
                {"path": "processing.structureFormed", "operator": "equals", "value": True},
                {"path": "state.allowedToWork", "operator": "equals", "value": True}, *machine.get("requirements", [])]:
                present, _ = path_get(value, requirement["path"])
                try:
                    matched = condition_matches(value, requirement) if present else None
                except BridgeError:
                    matched = None
                record(identifier, requirement["path"], matched)
            processing = value.get("processing", {})
            actual, ideal = processing.get("repairStatus"), processing.get("idealMaintenanceStatus")
            record(identifier, "maintenance", actual >= ideal if type(actual) is type(ideal) is int else None)
            entries, complete, coverage = await component_pages(machine_steps[index], value)
            record(identifier, "component_coverage", True if complete else None, **coverage)
            for component in entries:
                if component.get("role") == "muffler":
                    if component.get("metaClass") == "gregtech.api.metatileentity.implementations.MTEHatchMuffler" \
                            and component.get("dimension") == dimension and all(type(component.get(a)) is int for a in "xyz"):
                        exhausts.append({"id": identifier + "_exhaust_" + str(component.get("index")), **component})
                    else:
                        record(identifier, "muffler_rule_available", None)
                if component.get("valid") is not True:
                    record(identifier, "component_valid", component.get("valid") if type(component.get("valid")) is bool else None,
                           role=component.get("role"))
        points = [row["position"] for row in site.get("endpoints", [])] + exhausts
        world = await owners(points, ae=bool(site.get("endpoints")))
        for exhaust in exhausts:
            value = at(world, exhaust)
            identity = value is not None and value.get("gregtech", {}).get("metaTileId") == exhaust["metaTileId"]
            record(exhaust["id"], "muffler_identity", identity if value is not None else None)
            facing = (value or {}).get("gregtech", {}).get("orientation", {}).get("facing", {})
            side = facing.get("side")
            if not identity or facing.get("available") is not True or type(side) is not int or not 0 <= side < len(VECTORS):
                record(exhaust["id"], "muffler_facing", None)
                continue
            position = {axis: exhaust[axis] + VECTORS[side][i] for i, axis in enumerate("xyz")}
            if not 0 <= position["y"] <= 255:
                record(exhaust["id"], "muffler_clearance", None)
            else:
                clearances.append({"id": exhaust["id"], "position": position})
        for endpoint in site.get("endpoints", []):
            value = at(world, endpoint["position"])
            identifier = endpoint["id"]
            if value is None:
                record(identifier, "ae_owner", None)
                continue
            identity = endpoint["targetIdentity"]
            matched = all(value.get(k) == identity[k] for k in ("registryName", "metadata") if k in identity) \
                and ("metaTileId" not in identity or value.get("gregtech", {}).get("metaTileId") == identity["metaTileId"]) \
                and ("part" not in identity or identity["part"] in value.get("parts", []))
            record(identifier, "ae_identity", matched)
            if not matched:
                continue
            hosts = [host for host in value.get("ae", {}).get("hosts", []) if host.get("mountingSide") == endpoint["mountingSide"]]
            network = hosts[0].get("network", {}) if len(hosts) == 1 else {}
            nodes = network.get("nodes", [])
            available = [node for node in nodes if node.get("available") is True]
            complete = network.get("complete") is True and bool(available)
            active = any(node.get("active") is True for node in available)
            record(identifier, "ae_active", active if complete and all(type(n.get("active")) is bool for n in available) else None)
            grids = {node.get("gridId") for node in available}
            if complete and len(grids) == 1 and all(isinstance(grid, str) and grid for grid in grids):
                networks.setdefault(endpoint["network"], set()).update(grids)
            else:
                record(identifier, "ae_grid", None)
        for group, grids in networks.items():
            record(group, "same_grid", len(grids) == 1, gridIds=sorted(grids))
        for first, second in site.get("isolatedNetworks", []):
            a, b = networks.get(first), networks.get(second)
            record(first, "isolated_from_" + second, not bool(a & b) if a and b else None)
        air = await owners([row["position"] for row in clearances])
        for clearance in clearances:
            value = at(air, clearance["position"])
            record(clearance["id"], "literal_air_clearance", value.get("registryName") == "minecraft:air" if value else None,
                   position=clearance["position"])
    except BridgeError as error:
        record("site", "observation", None, error=error.as_dict())
    failed = sum(c["status"] == "failed" for c in checks)
    unknown = sum(c["status"] == "unknown" for c in checks)
    return {"status": "completed", "checkKind": "site", "planFingerprint": json_sha256(site),
        "readiness": "blocked" if failed else "unknown" if unknown else "passed",
        "declaredChecksPassed": not failed and not unknown, "failedChecks": failed, "unknownChecks": unknown,
        "checks": checks, "observations": receipts, "nonAtomic": True, "productionVerified": False,
        "logisticsTransferVerified": False, "forceLoadingVerified": False, "automaticReplayAllowed": False,
        "completionScope": "declared_site_conditions_only", "limitations": [
            "AE grid activity does not prove actual extraction, return-path delivery or spare channel capacity.",
            "Machine-specific voltage, heat, mode and other public conditions require explicit requirements; missing fields stay unknown.",
            "Component pages share the site deadline, with at most 32 continuation reads and a stable live registration fingerprint; observations remain non-atomic.",
            "Muffler air rule covers only the observed standard GT class; other exhaust implementations stay unknown.",
            "Declared layout boxes are exclusive reserved volumes; do not overlap a machine box with its own included parts.",
            "No structure recheck, enable, transfer, chunk force-loading, replay or production run is performed."]}
