"""Public geometry planning observes terrain without writes or cached air claims."""

import copy
import json

import httpx
import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.placement_tasks import revalidate_placement
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.validation import tool_validator

native_construction = native.native_construction
SOURCE = {"x": -144, "y": 3, "z": 125}
ALIGNMENT = {"direction": "NORTH", "rotation": "normal", "flip": "none"}
MATERIAL = {"solid": True, "liquid": False, "replaceable": False}


def arguments(**changes):
    return {"expectedSessionId": "session", "expectedWorld": "new-world", "dimension": 0,
        "geometrySource": SOURCE.copy(), "anchor": native.CONTROLLER.copy(),
        "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0, **changes}


class World:
    def __init__(self):
        self.calls, self.cursors, self.next_id = [], {}, 0
        self.extra, self.slope, self.fault, self.bound_reads = {}, False, None, 0
        self.page_size = 512
        self.capture_page_size = 128
        self.geometry_height = 3
        self.compact_prefix = 128
        self.matrix = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        self.offsets = [0, 0, 0]
        self.alignment = ALIGNMENT.copy()
        self.probe_skip = False
        self.capture_subset = None
        self.scan_requests = 0
        self.trigger_count = 16
        self.source_reads = 0
        self.captured_mode = 0
        self.literal_scan = False
        self.literal_fault = None
        self.owned_block = None
        self.owned_fault = None
        self.context_rpc = False
        self.guard_fault = None
        self.guarded_requests = []

    def aligned(self, abc, offsets):
        return {axis: SOURCE[axis] + sum(self.matrix[index][column] * (abc[column] - offsets[column]) for column in range(3))
            for index, axis in enumerate(("x", "y", "z"))}

    def positions(self):
        return [{"a": x, "b": y, "c": z, **self.aligned((x, y, z), self.offsets)}
            for x in range(-1, 2) for y in range(-1, self.geometry_height - 1) for z in range(3) if (x, y, z) != (0, 0, 0)]

    def structure(self, args):
        positions = self.positions()
        observed = positions[:self.capture_subset] if self.capture_subset else positions
        offset = args.get("runtimeOffset", 0)
        end = min(len(observed), offset + self.capture_page_size)
        facing = "_".join(self.alignment[key].upper() for key in ("direction", "rotation", "flip"))
        runtime = {"available": True, "captureId": "source-capture", "capturedPositions": len(observed),
            "capturedVisitsComplete": True, "eventCaptureTruncated": False,
            **{key: True for key in ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches")},
            "capturedFacing": facing, "capturedMode": self.captured_mode, "positions": copy.deepcopy(observed[offset:end]),
            "controller": {**SOURCE, "dimension": 0, "metaClass": "Public.Controller", "facing": facing, "mode": self.captured_mode},
            "trigger": {**native.BLUEPRINT, "count": self.trigger_count, "channelData": ""},
            "runtimeOffset": offset, "nextRuntimeOffset": end, "hasMore": end < len(observed)}
        if self.fault == "partial_capture":
            runtime["capturedVisitsComplete"] = False
        elif self.fault == "bad_abc":
            runtime["positions"][0]["a"] = True
        elif self.fault == "stale_capture" and offset:
            runtime["captureId"] = "stale-capture"
        elif self.fault == "multiple_translation" and end == len(positions):
            runtime["positions"][-1]["x"] += 10
        elif self.fault == "source_trigger_changed" and offset:
            runtime["trigger"]["channelData"] = "{channel:1}"
        base = {"controller": {**SOURCE, "dimension": 0, "metaClass": "Public.Controller"}, "alignment": self.alignment.copy(), "runtimeObservation": runtime}
        if self.fault == "source_controller_changed" and "piece" in args:
            base["controller"]["metaClass"] = "Public.OtherController"
        if "piece" not in args:
            return {**base, "mode": "definition_discovery", "pieces": [{"name": "public.fixture", "selectable": True, "geometryAvailable": True}], "hasMore": False}
        offsets = [args.get("offset" + axis, 0) for axis in ("A", "B", "C")]
        first = positions[0]
        row = {axis: first[axis] - offsets[index] for index, axis in enumerate(("a", "b", "c"))}
        row.update(self.aligned(tuple(first[axis] for axis in ("a", "b", "c")), offsets))
        complete = True
        if "query" in args:
            self.bound_reads += 1
            complete = self.bound_reads > 1
        public_positions = [self.aligned(tuple(value[axis] for axis in ("a", "b", "c")), offsets) for value in positions]
        low = {axis: min(value[axis] for value in public_positions) for axis in ("x", "y", "z")}
        high = {axis: max(value[axis] for value in public_positions) for axis in ("x", "y", "z")}
        compiled = len(positions) + 9
        bounds = {"available": True, "complete": complete, "partial": not complete, "source": "public_compiled_piece_navigation",
            "scannedEntries": compiled if complete else 0, "compiledEntries": compiled, "physicalPositions": len(positions),
            "worldBoundsAvailable": True, "controllerOffsetsAvailable": True,
            "worldXYZ": {"min": low, "max": high},
            "originABC": {"min": {"a": -1, "b": -1, "c": 0}, "max": {"a": 1, "b": self.geometry_height - 2, "c": 2}}}
        if self.fault == "partial_geometry":
            bounds.update(complete=False, partial=True)
        if self.fault == "missing_anchor":
            bounds["worldBoundsAvailable"] = False
        skip = self.probe_skip and "query" not in args and args.get("offset", 0) == 0
        return {**base, "piece": args["piece"], "variant": 1, "positions": [] if "query" in args or skip else [row],
            "worldPositionsAvailable": True, "pieceBounds": bounds, "hasMore": skip, "nextOffset": 1 if skip else compiled}

    def source_block(self):
        self.source_reads += 1
        return {**SOURCE, "source": "integrated_server", "dimension": 0, "registryName": native.CONTROLLER_ITEM["registryName"],
            "metadata": 0, "gregtech": {"metaTileId": native.CONTROLLER_ITEM["metadata"] + int(self.fault == "source_identity_changed" and self.source_reads > 1)}, "tileClass": "Public.Tile"}

    def block(self, x, y, z):
        extra = self.extra.get((x, y, z))
        if extra is not None:
            return {"x": x, "y": y, "z": z, **extra}
        ground = 3 if self.slope and x > native.CONTROLLER["x"] else 1
        if y <= ground:
            return {"x": x, "y": y, "z": z, "registryName": "minecraft:stone" if y < ground else "minecraft:grass",
                "metadata": 0, "publicMaterial": MATERIAL.copy()}
        return None

    def scan(self, args):
        if "cursor" in args:
            bounds, offset, air_before, nonair_before, semantics = self.cursors.pop(args["cursor"])
        else:
            bounds, offset, air_before, nonair_before = args["bounds"], 0, 0, 0
            semantics = args.get("airSemantics", "block_is_air")
            self.scan_requests += 1
            if self.fault == "fresh_obstacle" and self.scan_requests == 2:
                self.extra[(native.CONTROLLER["x"], 4, native.CONTROLLER["z"])] = {"registryName": "minecraft:stone", "metadata": 0}
        points = [(x, y, z) for x in range(bounds["minX"], bounds["maxX"] + 1)
            for y in range(bounds["minY"], bounds["maxY"] + 1) for z in range(bounds["minZ"], bounds["maxZ"] + 1)]
        end = min(len(points), offset + self.page_size)
        rows = [self.block(*point) for point in points[offset:end]]
        blocks = [row for row in rows if row is not None]
        if self.fault == "air_like" and semantics != "minecraft_air":
            blocks = [row for row in blocks if row["registryName"] != "example:air_like"]
        air, nonair = len(rows) - len(blocks), len(blocks)
        self.next_id += 1
        cursor = "scan:" + str(self.next_id) if end < len(points) else None
        if cursor:
            self.cursors[cursor] = (bounds, end, air_before + air, nonair_before + nonair, semantics)
        unloaded = end if self.fault == "unloaded" else 0
        if unloaded:
            blocks, air, nonair, air_before, nonair_before = [], 0, 0, 0, 0
        data = {"source": "integrated_server", "dimension": 0, "bounds": copy.deepcopy(bounds),
            "requestedVolume": len(points), "scannableVolume": len(points), "outOfWorld": 0,
            "blocks": blocks, "complete": cursor is None, "nextCursor": cursor,
            "coverage": {"examined": end, "air": air_before + air, "nonAir": nonair_before + nonair,
                "unloaded": unloaded, "loaded": end - unloaded, "remaining": len(points) - end},
            "page": {"startOffset": offset, "endOffsetExclusive": end, "examined": end - offset,
                "air": air, "nonAir": len(blocks), "unloaded": unloaded, "loaded": end - offset - unloaded},
            "airSemantics": semantics, "nonAtomic": True, "requestedRegionFullyObserved": cursor is None and unloaded == 0}
        if self.fault == "wrong_bounds":
            data["bounds"]["maxY"] -= 1
        elif self.fault == "scan_gap" and offset:
            data["page"]["startOffset"] -= 1
        if semantics == "minecraft_air":
            fault = self.literal_fault
            if fault == "missing_semantics":
                data.pop("airSemantics")
            elif fault == "changed_semantics" and offset:
                data["airSemantics"] = "block_is_air"
            elif fault == "wrong_source":
                data["source"] = "client"
            elif fault == "wrong_dimension":
                data["dimension"] = 1
            elif fault == "wrong_bounds":
                data["bounds"]["maxY"] -= 1
            elif fault == "volume":
                data["scannableVolume"] -= 1
            elif fault == "loaded":
                data["coverage"]["loaded"] -= 1
            elif fault == "unloaded":
                data["coverage"]["unloaded"] = 1
                data["coverage"]["loaded"] -= 1
                data["coverage"]["air"] -= 1
                data["requestedRegionFullyObserved"] = False
                if offset == 0:
                    data["page"]["unloaded"] = 1
                    data["page"]["loaded"] -= 1
                    data["page"]["air"] -= 1
            elif fault == "page_air":
                data["page"]["air"] -= 1
            elif fault == "prefix_gap" and offset:
                data["page"]["startOffset"] -= 1
            elif fault == "false_summary":
                data["requestedRegionFullyObserved"] = not data["requestedRegionFullyObserved"]
            elif fault == "incomplete_summary" and cursor is None:
                data["complete"] = False
                data["requestedRegionFullyObserved"] = False
            elif fault == "missing_cursor" and cursor:
                data["nextCursor"] = None
            elif fault == "repeated_cursor" and "cursor" in args:
                data["nextCursor"] = args["cursor"]
            elif fault == "row_air" and blocks:
                blocks[0]["registryName"] = "minecraft:air"
            elif fault == "duplicate_row" and blocks:
                blocks.append(copy.deepcopy(blocks[0]))
                for counts in (data["coverage"], data["page"]):
                    counts["nonAir"] += 1
                    counts["air"] -= 1
        return data

    def inspect(self, args):
        count = min(self.compact_prefix, len(args["positions"])) if args.get("compact") else len(args["positions"])
        rows = []
        for index, expected in enumerate(args["positions"][:count]):
            block = self.block(expected["x"], expected["y"], expected["z"]) or {**expected, "registryName": "minecraft:air", "metadata": 0}
            rows.append({"ok": True, "requestIndex": index, "data": {**block, "source": "integrated_server", "dimension": 0, "loaded": True}})
        return {"source": "integrated_server", "dimension": 0, "results": rows,
            "requestedCount": len(args["positions"]), "processedCount": count, "nextOffset": count,
            "complete": count == len(args["positions"]), "partial": count != len(args["positions"]),
            "byteLimited": False, "budgetLimited": count != len(args["positions"]), "worldTick": 1, "nonAtomic": True}


@pytest.fixture
async def placement_bridge():
    world = World()

    async def respond(request):
        body = json.loads(request.content)
        operation, args = body["operation"], body["args"]
        world.calls.append((operation, copy.deepcopy(args)))
        if operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "profile": "developer",
                "worldScanBounds": True, "worldBlockPublicMaterial": True, "worldInspectManyCompact": True}
            if world.literal_scan is not None:
                data["worldScanMinecraftAir"] = world.literal_scan
            if world.context_rpc:
                data["contextRpc"] = {"version": 1, "reads": ["world.structure"], "actions": []}
        elif operation == "client.snapshot":
            data = {"sessionId": "session", "profile": "developer", "worldLoaded": True,
                "world": {"worldName": "new-world", "dimension": 0}}
            if world.fault == "context_changed" and world.scan_requests:
                data["world"]["dimension"] = 1
        elif operation == "world.structure":
            data = world.structure(args)
        elif operation == "world.scan":
            if args.get("airSemantics") == "minecraft_air" and world.literal_fault == "transport":
                raise httpx.ReadError("uncertain literal scan delivery", request=request)
            if args.get("airSemantics") == "minecraft_air" and world.literal_fault == "refused":
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
                    "error": {"code": "unsupported", "message": "The advertised literal scan is currently unavailable."}})
            data = world.scan(args)
        elif operation == "world.inspect_many":
            data = world.inspect(args)
        elif operation == "world.inspect":
            if all(args[axis] == SOURCE[axis] for axis in ("x", "y", "z")):
                data = world.source_block()
            else:
                assert world.owned_block is not None and all(args[axis] == world.owned_block[axis] for axis in ("x", "y", "z"))
                data = copy.deepcopy(world.owned_block)
                if world.owned_fault == "meta_tile":
                    data["gregtech"]["metaTileId"] += 1
                elif world.owned_fault == "metadata":
                    data["metadata"] += 1
                elif world.owned_fault == "tile_class":
                    data["tileClass"] = "Other.Tile"
                elif world.owned_fault == "source":
                    data["source"] = "client"
                elif world.owned_fault == "dimension":
                    data["dimension"] += 1
                elif world.owned_fault == "coordinate":
                    data["x"] += 1
                elif world.owned_fault == "registry":
                    data["registryName"] = "minecraft:chest"
        else:
            raise AssertionError(operation)
        envelope = {"requestId": body["requestId"], "ok": True, "data": data}
        if "includeContext" in body:
            assert world.context_rpc and operation == "world.structure"
            world.guarded_requests.append(copy.deepcopy(body))
            snapshot = {"sessionId": "session", "profile": "developer", "worldLoaded": True,
                "world": {"worldName": "new-world", "dimension": 0}}
            if world.guard_fault in ("session", "profile"):
                snapshot["sessionId" if world.guard_fault == "session" else "profile"] = "changed"
            elif world.guard_fault == "dimension":
                snapshot["world"]["dimension"] = 1
            elif world.guard_fault == "world":
                snapshot["world"]["worldName"] = "changed"
            elif world.guard_fault == "unloaded":
                snapshot["worldLoaded"] = False
            envelope["context"] = {"version": 1, "operation": operation, "phase": "after", "snapshot": snapshot}
            if world.guard_fault == "missing":
                envelope.pop("context")
            elif world.guard_fault == "before":
                envelope["context"]["phase"] = "before"
        return httpx.Response(200, json=envelope)

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "placement-test"}), transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), world
    finally:
        await client.aclose()


@pytest.mark.parametrize("slope,expected_y", [(False, 3), (True, 5)])
@pytest.mark.parametrize("capture_page_size", [128, 5])
async def test_preflight_calibrates_public_geometry_and_all_footprint_terrain_in_memory(placement_bridge, slope, expected_y, capture_page_size):
    adapter, world = placement_bridge
    world.slope, world.capture_page_size = slope, capture_page_size
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert data["proposedController"] == {**native.CONTROLLER, "y": expected_y}
    assert data["complete"] and data["spaceVerified"] and not data["machineRulesKnown"] and data["unknown"]
    assert data["geometry"]["offsets"] == {"a": 0, "b": 0, "c": 0}
    assert world.bound_reads == 2 and data["geometry"]["unclipped"]
    assert data["groundRange"]["maxY"] == expected_y - 2
    assert data["probeCoverage"]["exactCellChecks"] == 27
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)


async def test_existing_machine_and_floating_obstacles_never_become_ground(placement_bridge):
    adapter, world = placement_bridge
    world.extra[(native.CONTROLLER["x"], 2, native.CONTROLLER["z"])] = {"registryName": "gregtech:gt.blockmachines", "metadata": 0,
        "tileClass": "Public.Machine", "publicMaterial": MATERIAL.copy()}
    world.extra[(native.CONTROLLER["x"], 7, native.CONTROLLER["z"])] = {"registryName": "minecraft:stone", "metadata": 0, "publicMaterial": MATERIAL.copy()}
    result = await adapter.call_tool("mcb_placement_preflight", arguments(searchRadius=2))
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert data["proposedController"]["y"] == 3 and data["proposedController"] != native.CONTROLLER
    assert world.extra[(native.CONTROLLER["x"], 2, native.CONTROLLER["z"])]["tileClass"] == "Public.Machine"
    assert data["obstacles"] and data["groundRange"]["maxY"] == 1


async def test_obstacle_seen_by_many_rejected_candidates_is_reported_once(placement_bridge):
    adapter, world = placement_bridge
    obstacle = (native.CONTROLLER["x"], 4, native.CONTROLLER["z"])
    world.extra[obstacle] = {"registryName": "minecraft:stone", "metadata": 0, "publicMaterial": MATERIAL.copy()}
    result = await adapter.call_tool("mcb_placement_preflight", arguments(searchRadius=3))
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert data["obstacles"] == [{"x": obstacle[0], "y": obstacle[1], "z": obstacle[2], "registryName": "minecraft:stone"}]
    assert not data["obstaclesTruncated"] and data["spaceVerified"]
    assert "rejected candidate" in data["obstacleScope"]


async def test_distinct_obstacle_output_remains_bounded_and_reports_omitted_coordinates(placement_bridge):
    adapter, world = placement_bridge
    for dx in range(-3, 3):
        for dz in range(-2, 4):
            world.extra[(native.CONTROLLER["x"] + dx, 3, native.CONTROLLER["z"] + dz)] = {
                "registryName": "minecraft:stone", "metadata": 0, "publicMaterial": MATERIAL.copy()}
    result = await adapter.call_tool("mcb_placement_preflight", arguments(searchRadius=4))
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert len(data["obstacles"]) == len({tuple(row[axis] for axis in ("x", "y", "z")) for row in data["obstacles"]}) == 32
    assert data["obstaclesTruncated"] and data["spaceVerified"]


@pytest.mark.parametrize("fault,reason", [("partial_capture", "native_anchor_capture_incomplete"), ("bad_abc", "native_anchor_capture_changed"),
    ("stale_capture", "native_anchor_capture_changed"), ("multiple_translation", "public_anchor_translation_ambiguous"),
    ("partial_geometry", "public_geometry_no_progress"), ("missing_anchor", "public_geometry_anchor_missing"),
    ("wrong_bounds", "placement_scan_coverage_invalid"), ("scan_gap", "placement_scan_coverage_invalid")])
async def test_incomplete_geometry_anchor_or_world_coverage_never_authorizes_placement(placement_bridge, fault, reason):
    adapter, world = placement_bridge
    world.fault, world.page_size, world.capture_page_size = fault, 50, 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == reason, result.structuredContent
    assert not result.structuredContent["data"]["spaceVerified"]
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)


async def test_scan_air_like_omission_is_rejected_by_exact_full_box_read(placement_bridge):
    adapter, world = placement_bridge
    world.fault = "air_like"
    world.extra[(native.CONTROLLER["x"] + 1, 4, native.CONTROLLER["z"] + 2)] = {"registryName": "example:air_like", "metadata": 0}
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == "placement_exact_non_air", result.structuredContent
    assert any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("maximum,reason", [(8, "placement_request_budget"), (30, "placement_scan_volume_budget")])
async def test_independent_internal_read_and_cell_budgets_are_finite(placement_bridge, maximum, reason):
    adapter, _ = placement_bridge
    args = arguments(**({"maxRequests": maximum} if maximum == 8 else {"maxScanCells": maximum}))
    result = await adapter.call_tool("mcb_placement_preflight", args)
    assert result.isError and result.structuredContent["data"]["reason"] == reason, result.structuredContent


async def test_exact_compact_prefixes_resume_only_the_unprocessed_suffix(placement_bridge):
    adapter, world = placement_bridge
    world.compact_prefix = 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    reads = [args["positions"] for operation, args in world.calls if operation == "world.inspect_many"]
    assert [len(read) for read in reads] == [27, 22, 17, 12, 7, 2]
    assert len({tuple(point[axis] for axis in ("x", "y", "z")) for read in reads for point in read[:5]}) == 27


@pytest.mark.parametrize("capability", [None, False, "true", 1])
async def test_missing_typed_literal_scan_capability_keeps_exact_compact_reads(placement_bridge, capability):
    adapter, world = placement_bridge
    world.literal_scan = capability
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    assert result.structuredContent["data"]["exactAirMethod"] == "world.inspect_many"
    assert any(operation == "world.inspect_many" for operation, _ in world.calls)
    assert not any(args.get("airSemantics") == "minecraft_air" for operation, args in world.calls if operation == "world.scan")


async def test_literal_scan_fresh_full_box_counts_every_cell_without_explicit_position_reads(placement_bridge):
    adapter, world = placement_bridge
    world.literal_scan, world.page_size = True, 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    assert plan["spaceVerified"] and plan["exactAirMethod"] == "world.scan(minecraft_air)"
    assert plan["probeCoverage"]["exactCellChecks"] == 27
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)
    first = next(index for index, (operation, args) in enumerate(world.calls) if operation == "world.scan" and args.get("airSemantics") == "minecraft_air")
    pages = [args for operation, args in world.calls[first:] if operation == "world.scan"]
    assert len(pages) == 6 and pages[0]["bounds"] == plan["reservedBounds"]
    assert all(set(page) == {"cursor", "limit"} for page in pages[1:])
    assert len({page["cursor"] for page in pages[1:]}) == len(pages) - 1


async def test_literal_box_recheck_omits_only_redundant_default_scan_and_consumes_fresh_structure_context(placement_bridge):
    adapter, world = placement_bridge
    world.literal_scan = world.context_rpc = True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    starts = [args for operation, args in world.calls if operation == "world.scan" and "bounds" in args]
    assert len(starts) == 2 and starts[1]["bounds"] == plan["reservedBounds"]
    assert starts[1]["airSemantics"] == "minecraft_air" and plan["probeCoverage"]["exactCellChecks"] == 27
    assert sum(operation == "client.snapshot" for operation, _ in world.calls) == 2
    assert len(world.guarded_requests) == 1 and world.calls[-1][0] == "world.structure"
    guard = world.guarded_requests[0]["includeContext"]
    assert guard == {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
        "expectedWorld": "new-world", "requireBackground": False}
    previous = len(world.calls)
    check = await revalidate_placement(adapter.client, arguments(), plan)
    after = world.calls[previous:]
    assert check["spaceVerified"] and check["probeCoverage"]["exactCellChecks"] == 27
    assert [operation for operation, _ in after] == ["bridge.health", "client.snapshot", "world.scan", "world.inspect", "world.structure"]
    assert len(world.guarded_requests) == 2


@pytest.mark.parametrize("fault", ["session", "profile", "world", "dimension", "unloaded", "missing", "before"])
async def test_advertised_structure_after_context_failure_never_falls_back_or_replays(placement_bridge, fault):
    adapter, world = placement_bridge
    world.literal_scan = world.context_rpc = True
    world.guard_fault = fault
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError, result.structuredContent
    assert len(world.guarded_requests) == 1 and world.calls[-1][0] == "world.structure"
    assert sum(operation == "client.snapshot" for operation, _ in world.calls) == 2
    assert sum(operation == "world.scan" and args.get("airSemantics") == "minecraft_air" for operation, args in world.calls) == 1
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)


async def test_literal_scan_does_not_accept_air_like_custom_blocks_omitted_by_default_scan(placement_bridge):
    adapter, world = placement_bridge
    world.literal_scan, world.fault = True, "air_like"
    world.extra[(native.CONTROLLER["x"] + 1, 4, native.CONTROLLER["z"] + 2)] = {"registryName": "example:air_like", "metadata": 0}
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == "placement_exact_non_air", result.structuredContent
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("fault,reason", [
    ("missing_semantics", "placement_exact_scan_coverage_invalid"), ("changed_semantics", "placement_exact_scan_coverage_invalid"),
    ("wrong_source", "placement_scan_coverage_invalid"), ("wrong_dimension", "placement_scan_coverage_invalid"),
    ("wrong_bounds", "placement_scan_coverage_invalid"), ("volume", "placement_scan_coverage_invalid"),
    ("loaded", "placement_exact_scan_coverage_invalid"), ("page_air", "placement_exact_scan_coverage_invalid"),
    ("prefix_gap", "placement_scan_coverage_invalid"), ("false_summary", "placement_exact_scan_coverage_invalid"),
    ("incomplete_summary", "placement_scan_cursor_invalid"), ("missing_cursor", "placement_scan_cursor_invalid"),
    ("repeated_cursor", "placement_scan_cursor_invalid"), ("unloaded", "placement_unloaded_cells"),
])
async def test_literal_scan_requires_strict_page_accounting_and_never_falls_back(placement_bridge, fault, reason):
    adapter, world = placement_bridge
    world.literal_scan, world.literal_fault, world.page_size = True, fault, 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == reason, result.structuredContent
    assert not result.structuredContent["data"]["spaceVerified"]
    assert len([args for operation, args in world.calls if operation == "world.scan" and args.get("airSemantics") == "minecraft_air"]) == 1
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("fault", ["transport", "refused"])
async def test_dispatched_literal_scan_failure_never_restarts_or_downgrades(placement_bridge, fault):
    adapter, world = placement_bridge
    world.literal_scan, world.literal_fault = True, fault
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and not result.structuredContent["data"]["spaceVerified"], result.structuredContent
    assert len([args for operation, args in world.calls if operation == "world.scan" and args.get("airSemantics") == "minecraft_air"]) == 1
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("fault,reason", [("row_air", "placement_exact_scan_identity_invalid"), ("duplicate_row", "placement_scan_duplicate_identity")])
async def test_literal_scan_does_not_hide_or_duplicate_non_air_identity(placement_bridge, fault, reason):
    adapter, world = placement_bridge
    world.literal_scan, world.fault, world.literal_fault = True, "air_like", fault
    world.extra[(native.CONTROLLER["x"] + 1, 4, native.CONTROLLER["z"] + 2)] = {"registryName": "example:air_like", "metadata": 0}
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == reason, result.structuredContent
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


def install_owned_controller(world, *, metadata=0):
    world.owned_block = {**native.CONTROLLER, "source": "integrated_server", "dimension": 0,
        "registryName": native.CONTROLLER_ITEM["registryName"], "metadata": metadata,
        "gregtech": {"metaTileId": native.CONTROLLER_ITEM["metadata"]}, "tileClass": "Public.Tile"}
    world.extra[native.key(native.CONTROLLER)] = {key: world.owned_block[key] for key in ("registryName", "metadata", "tileClass")}


async def test_literal_scan_three_fresh_stages_allow_only_current_exact_owned_controller_and_count_it(placement_bridge):
    adapter, world = placement_bridge
    world.literal_scan = True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    before = await revalidate_placement(adapter.client, arguments(), plan)
    install_owned_controller(world, metadata=4)  # A source block metadata value is not a new GT controller contract.
    shell = await revalidate_placement(adapter.client, arguments(), plan, owned_controller=native.CONTROLLER)
    assert before["spaceVerified"] and shell["spaceVerified"] and shell["ownedControllerCellCounted"]
    assert shell["exactAirMethod"] == before["exactAirMethod"] == "world.scan(minecraft_air)"
    assert shell["probeCoverage"]["exactCellChecks"] == before["probeCoverage"]["exactCellChecks"] == 27
    assert plan["probeCoverage"]["exactCellChecks"] == 81  # Legacy exclusions total 80 for these three boxes.
    assert len([args for operation, args in world.calls if operation == "world.scan" and args.get("airSemantics") == "minecraft_air"]) == 3
    assert len([args for operation, args in world.calls if operation == "world.inspect" and args["x"] == native.CONTROLLER["x"]]) == 1
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("fault", ["meta_tile", "metadata", "tile_class", "source", "dimension", "coordinate", "registry"])
async def test_literal_scan_owned_coordinate_is_not_permission_to_accept_a_changed_controller(placement_bridge, fault):
    adapter, world = placement_bridge
    world.literal_scan = True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    install_owned_controller(world)
    world.owned_fault = fault
    with pytest.raises(BridgeError) as caught:
        await revalidate_placement(adapter.client, arguments(), result.structuredContent["data"], owned_controller=native.CONTROLLER)
    assert caught.value.details["reason"] == "placement_owned_controller_changed"
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


@pytest.mark.parametrize("fault", ["missing", "foreign", "air_like"])
async def test_literal_scan_owned_exception_never_hides_another_non_air_or_missing_controller(placement_bridge, fault):
    adapter, world = placement_bridge
    world.literal_scan = True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    if fault != "missing":
        install_owned_controller(world)
        world.extra[(native.CONTROLLER["x"] + 1, 4, native.CONTROLLER["z"] + 2)] = {
            "registryName": "example:air_like" if fault == "air_like" else "minecraft:chest", "metadata": 0}
    with pytest.raises(BridgeError) as caught:
        await revalidate_placement(adapter.client, arguments(), result.structuredContent["data"], owned_controller=native.CONTROLLER)
    assert caught.value.details["reason"] == "placement_exact_non_air"
    assert not any(operation == "world.inspect_many" for operation, _ in world.calls)


async def test_literal_scan_owned_controller_cell_cannot_escape_the_remaining_total_budget(placement_bridge):
    adapter, world = placement_bridge
    world.literal_scan = True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    install_owned_controller(world)
    budget = plan["probeCoverage"]["examined"] + plan["probeCoverage"]["exactCellChecks"] + 26
    before = len(world.calls)
    with pytest.raises(BridgeError) as caught:
        await revalidate_placement(adapter.client, arguments(maxScanCells=budget), plan, owned_controller=native.CONTROLLER)
    assert caught.value.details["reason"] == "placement_scan_volume_budget"
    assert not any(operation == "world.scan" for operation, _ in world.calls[before:])


@pytest.mark.parametrize("args,valid", [
    ({"airSemantics": "minecraft_air"}, True), ({"airSemantics": "block_is_air"}, True),
    ({"bounds": {"minX": 0, "maxX": 1, "minY": 2, "maxY": 3, "minZ": 0, "maxZ": 1}, "airSemantics": "minecraft_air"}, True),
    ({"cursor": "only-cursor", "airSemantics": "minecraft_air"}, False),
    ({"airSemantics": "minecraft:air"}, False), ({"airSemantics": True}, False),
])
def test_scan_schema_binds_air_semantics_only_on_first_page(args, valid):
    assert (not list(tool_validator("mcb_world_scan").iter_errors(args))) is valid


@pytest.mark.parametrize("matrix,alignment", [
    ([[0, 0, 1], [0, 1, 0], [-1, 0, 0]], {"direction": "EAST", "rotation": "normal", "flip": "none"}),
    ([[1, 0, 0], [0, 0, 1], [0, -1, 0]], {"direction": "NORTH", "rotation": "clockwise", "flip": "horizontal"}),
    ([[-1, 0, 0], [0, -1, 0], [0, 0, 1]], {"direction": "NORTH", "rotation": "upside_down", "flip": "vertical"}),
])
async def test_public_zero_unit_probes_support_rotation_flip_and_real_abc_offsets(placement_bridge, matrix, alignment):
    adapter, world = placement_bridge
    world.matrix, world.alignment, world.offsets, world.probe_skip = matrix, alignment, [1, 1, 0], True
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert data["geometry"]["offsets"] == {"a": 1, "b": 1, "c": 0}
    assert data["geometry"]["axisTransform"] == matrix and data["geometry"]["alignment"] == alignment
    expected_min_y = min(0, min(value["y"] - SOURCE["y"] for value in world.positions()))
    assert data["proposedController"]["y"] == 2 - expected_min_y
    assert sum(operation == "world.structure" and args.get("offset") == 1 for operation, args in world.calls) == 4


async def test_large_single_piece_envelope_remains_complete_with_clipped_source_calibration_subset(placement_bridge):
    adapter, world = placement_bridge
    world.geometry_height, world.capture_subset, world.capture_page_size = 40, 5, 2
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    data = result.structuredContent["data"]
    assert data["geometry"]["physicalPositions"] == 359 and data["reservedBounds"]["maxY"] == 41
    assert data["geometry"]["relativeBounds"]["max"]["y"] == 38
    assert data["probeCoverage"]["exactCellChecks"] == 360 and data["geometry"]["unclipped"]
    assert not data["machineRulesKnown"] and "dynamic" in data["geometry"]["coverage"]


async def test_explicit_offsets_need_public_geometry_but_no_native_calibration_capture(placement_bridge):
    adapter, world = placement_bridge
    world.fault = "partial_capture"
    result = await adapter.call_tool("mcb_placement_preflight", arguments(geometrySource={**SOURCE, "piece": "public.fixture", "offsetA": 0, "offsetB": 0, "offsetC": 0}))
    assert not result.isError, result.structuredContent
    assert result.structuredContent["data"]["geometry"]["capturedTrigger"] is None


async def test_default_ground_band_does_not_follow_an_erroneously_high_controller_anchor(placement_bridge):
    adapter, _ = placement_bridge
    args = arguments(anchor={**native.CONTROLLER, "y": 120})
    args.pop("groundBand")
    result = await adapter.call_tool("mcb_placement_preflight", args)
    assert not result.isError and result.structuredContent["data"]["proposedController"]["y"] == 3
    assert result.structuredContent["data"]["groundRange"]["band"] == {"minY": 0, "maxY": 32}


@pytest.mark.parametrize("fault,reason", [("unloaded", "placement_unloaded_cells"), ("fresh_obstacle", "placement_candidate_changed"),
    ("source_trigger_changed", "native_anchor_capture_changed"), ("source_controller_changed", "geometry_source_changed"),
    ("source_identity_changed", "geometry_source_changed"), ("context_changed", "placement_context_changed")])
async def test_changed_source_or_fresh_candidate_and_unloaded_terrain_remain_unavailable(placement_bridge, fault, reason):
    adapter, world = placement_bridge
    world.fault, world.capture_page_size = fault, 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == reason, result.structuredContent
    assert not result.structuredContent["data"]["spaceVerified"]


def planning_wrapper(client, world, *, target_fault=None):
    original = client.call

    async def public_planning(operation, args, **options):
        if operation == "world.structure" and args["x"] == SOURCE["x"]:
            return world.structure(args)
        if operation == "world.scan":
            return world.scan(args)
        if operation == "world.inspect" and all(args[axis] == SOURCE[axis] for axis in ("x", "y", "z")):
            return world.source_block()
        data = await original(operation, args, **options)
        if operation == "bridge.health":
            data.update(worldScanBounds=True, worldBlockPublicMaterial=True)
        elif operation == "world.structure":
            runtime = data["runtimeObservation"]
            runtime["trigger"] = {**native.BLUEPRINT, "count": world.trigger_count, "channelData": ""}
            if target_fault == "clipped_count":
                runtime["positions"].pop(13)
                runtime["capturedPositions"] -= 1
                runtime["nextRuntimeOffset"] -= 1
            elif target_fault == "facing":
                runtime["capturedFacing"] = "SOUTH_NORMAL_NONE"
            elif target_fault == "mode":
                runtime["capturedMode"] = 1
            elif target_fault == "unknown_mode":
                runtime["capturedMode"] = None
            elif target_fault == "boolean_mode":
                runtime["capturedMode"] = False
            elif target_fault == "trigger":
                runtime["trigger"]["channelData"] = "{channel:1}"
            elif target_fault == "abc":
                runtime["positions"][13]["a"] += 1
            elif target_fault == "controller":
                runtime["controllerIdentityMatches"] = False
        return data
    return public_planning


@pytest.mark.parametrize("fault,reason", [("clipped_count", "placement_native_geometry_changed"), ("facing", "placement_native_geometry_changed"),
    ("mode", "placement_native_geometry_changed"), ("trigger", "placement_native_geometry_changed"), ("abc", "placement_native_geometry_changed"),
    ("controller", "native_hint_capture_incomplete")])
async def test_fresh_native_geometry_mismatch_never_builds_shell_even_when_observed_aabb_is_complete(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    monkeypatch.setattr(client, "call", planning_wrapper(client, World(), target_fault=fault))
    result = await native.invoke(client, native.declaration(placement={"geometrySource": SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}))
    assert result["reason"] == reason and not result["formationVerified"], result
    assert state["hint_count"] == 1 and state["construct_count"] == 0
    assert not result["placement"].get("newNativeGeometryVerified", False)


@pytest.mark.parametrize("source_mode,target_fault,completed", [(None, "mode", False), (None, "unknown_mode", True), (0, "boolean_mode", False)])
async def test_native_calibrated_mode_matches_exact_type_and_value_including_none(native_construction, monkeypatch, source_mode, target_fault, completed):
    client, state = native_construction
    world = World()
    world.captured_mode = source_mode
    monkeypatch.setattr(client, "call", planning_wrapper(client, world, target_fault=target_fault))
    result = await native.invoke(client, native.declaration(placement={"geometrySource": SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}))
    assert (result["status"] == "completed") is completed, result
    assert result["placement"]["geometry"]["capturedMode"] is source_mode
    assert state["hint_count"] == 1 and state["construct_count"] == int(completed)
    if completed:
        assert result["formationVerified"] and result["placement"]["newNativeGeometryVerified"]
    else:
        assert result["reason"] == "placement_native_geometry_changed" and not result["formationVerified"]


async def test_builder_declared_placement_geometry_failure_occurs_before_any_write(native_construction, monkeypatch):
    client, state = native_construction
    world = World()
    world.fault = "missing_anchor"
    monkeypatch.setattr(client, "call", planning_wrapper(client, world))
    result = await native.invoke(client, native.declaration(placement={"geometrySource": SOURCE.copy()}))
    assert result["reason"] == "public_geometry_anchor_missing" and not result["sideEffectsPossible"], result
    assert not native.writes(state) and state["construct_count"] == state["hint_count"] == 0


async def test_builder_total_placement_read_budget_does_not_reset_for_fresh_before_controller_check(native_construction, monkeypatch):
    client, state = native_construction
    monkeypatch.setattr(client, "call", planning_wrapper(client, World()))
    result = await native.invoke(client, native.declaration(placement={"geometrySource": SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8},
        "searchRadius": 0, "maxRequests": 20}))
    assert result["reason"] == "placement_request_budget" and result["placement"]["spaceVerified"], result
    assert state["construct_count"] == state["hint_count"] == 0 and not state["placements"]
    assert result["placement"]["probeCoverage"]["requests"] == 20


async def test_builder_total_placement_cell_budget_includes_every_full_box_recheck(native_construction, monkeypatch):
    client, state = native_construction
    monkeypatch.setattr(client, "call", planning_wrapper(client, World()))
    result = await native.invoke(client, native.declaration(placement={"geometrySource": SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8},
        "searchRadius": 0, "maxScanCells": 180}))
    assert result["reason"] == "placement_scan_volume_budget" and result["placement"]["spaceVerified"], result
    assert state["construct_count"] == state["hint_count"] == 0 and not state["placements"]
    coverage = result["placement"]["probeCoverage"]
    assert coverage["examined"] + coverage["exactCellChecks"] == 162


async def test_explicit_builder_placement_auto_height_and_fresh_native_acceptance(native_construction, monkeypatch):
    client, state = native_construction
    world = World()
    monkeypatch.setattr(client, "call", planning_wrapper(client, world))
    args = native.declaration(controller={**native.CONTROLLER, "y": 120}, placement={"geometrySource": SOURCE.copy(),
        "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0})
    result = await native.invoke(client, args)
    assert result["status"] == "completed" and result["formationVerified"], result
    assert result["controller"]["position"] == native.CONTROLLER
    assert result["placement"]["newNativeGeometryVerified"]
    assert result["placement"]["beforeController"]["spaceVerified"] and result["placement"]["beforeNativeConstruction"]["spaceVerified"]
    assert state["hint_count"] == state["construct_count"] == 1
    assert args["controller"]["y"] == 120
