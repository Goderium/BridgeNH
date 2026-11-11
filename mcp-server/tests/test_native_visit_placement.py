"""Generic observed-visit placement through the real adapter/native workflow, offline only."""
import copy
import json

import pytest
import httpx

import test_construction_tasks as native
import test_placement_preflight as placement
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import _placement_aim, _normal_gt_placement_facing, _VECTORS
from minecraft_codex_bridge.placement_tasks import (
    _native_visit_header, _native_visit_union, revalidate_placement,
)
from minecraft_codex_bridge.validation import tool_validator

placement_bridge = placement.placement_bridge
native_construction = native.native_construction
AXES = ("x", "y", "z")


def complete_runtime(runtime, controller, positions, capture_id, mode=0, count=16, alignment=None):
    runtime = copy.deepcopy(runtime)
    facing = "_".join((alignment or placement.ALIGNMENT)[key].upper() for key in ("direction", "rotation", "flip"))
    groups = []
    for identifier in sorted({row["group"] for row in positions}):
        members = [row for row in positions if row["group"] == identifier]
        descriptions = list(native.ROLE_KEYS.values()) if identifier == 0 else ["public.required.air"]
        group = {"id": identifier, "descriptionKeys": descriptions,
            "suggestedCandidates": [native.stack(native.CASING)] if identifier == 0 else [],
            "candidateScope": "Suggestions at first visited position only", "capturedVisits": len(members),
            "elementClass": "Untrusted.Private.Class" if identifier == 0 else "Untrusted.Other", "elementKey": None,
            "identityCoverage": "public_definition_element", "descriptionCoverage": "public_category_descriptions",
            "candidateCoverage": "public_stack_suggestions", "representativePosition": {axis: members[0][axis] for axis in AXES},
            "roleSelection": {"available": True, "binding": "capture_scoped_public_definition_element_group",
                "captureId": capture_id, "groupId": identifier, "descriptionKeys": descriptions, "semanticRoleVerified": False}}
        groups.append(group)
    runtime.update(source="client_native_blueprint_hints", nativeInvocationReturned=True, completeStructure=False,
        observedVisits=len(positions), capturedPositions=len(positions), captureId=capture_id,
        uncapturedVisits=0, captureLossDetected=False, captureContinuationAvailable=False, captureEncodedBytesUpperBound=16000,
        limits={"positions": 16384, "groups": 128, "bytes": 131072, "captureBytes": 4194304, "ttlMillis": 30000},
        truncation={key: False for key in ("positions", "groups", "bytes", "workBudget", "cleanupFailed")}, detailErrors=0,
        nonAtomic=True, evidenceOnly=True, ageMillis=20, clientValidationAgeMillis=5,
        capturedMode=mode, serverMode=mode, capturedFacing=facing, serverFacing=facing,
        controller={**controller, "dimension": 0, "metaClass": "Public.Controller", "mode": mode, "facing": facing},
        trigger={**native.BLUEPRINT, "count": count, "channelData": ""}, query="", matchingCapturedPositions=len(positions),
        runtimePageVisited=len(runtime["positions"]), pageLimited=runtime["hasMore"], pageByteLimited=False, pageSoftBudgetPartial=False)
    low = {axis: min(row[axis] for row in positions) for axis in AXES}
    high = {axis: max(row[axis] for row in positions) for axis in AXES}
    runtime.setdefault("observedBounds", {"available": True, "complete": True, "partial": False, "completeStructure": False}).update(source="client_native_hint_visited_xyz", observedVisits=len(positions), boundedVisits=len(positions),
        worldXYZ={"min": low, "max": high, "size": {axis: high[axis] - low[axis] + 1 for axis in AXES}},
        controllerRelativeXYZ={key: {axis: value[axis] - controller[axis] for axis in AXES} for key, value in (("min", low), ("max", high))})
    runtime["groups"] = [group for group in groups if any(row["group"] == group["id"] for row in runtime["positions"])]
    runtime.setdefault("planning", {}).update(contextMatches=True, observedVisitsComplete=True, currentBoundsFitWorld=True,
        worldHeightConflict=False, fullStructureChecked=False, completeStructure=False)
    return runtime


class NativeWorld(placement.World):
    def positions(self):
        rows = super().positions()
        for row in rows:
            row["group"] = int(tuple(row[axis] - placement.SOURCE[axis] for axis in AXES) == (0, 0, 1))
        rows[1].update({key: rows[0][key] for key in ("a", "b", "c")})
        return rows

    def structure(self, args):
        data = super().structure(args)
        data["runtimeObservation"] = complete_runtime(data["runtimeObservation"], placement.SOURCE,
            self.positions(), "source-capture", self.captured_mode, self.trigger_count, self.alignment)
        fault, runtime = self.fault, data["runtimeObservation"]
        if fault == "ttl":
            runtime["ageMillis"] = 30001
        elif fault == "validation_age":
            runtime["clientValidationAgeMillis"] = 1001
        elif fault == "unknown_group":
            runtime["groups"][0]["identityCoverage"] = "class_only"
        elif fault == "group_truncated":
            runtime["groups"][0]["descriptionTruncated"] = True
        elif fault == "missing_group":
            runtime["groups"].clear()
        elif fault == "duplicate_xyz" and len(runtime["positions"]) > 1:
            runtime["positions"][1].update({axis: runtime["positions"][0][axis] for axis in AXES})
        elif fault == "filtered":
            runtime["query"] = "energy"
        elif fault == "missing_suffix":
            runtime["hasMore"] = False
        elif fault == "stale_owner":
            runtime["controller"]["x"] += 1
        elif fault == "source_mode_page" and args.get("runtimeOffset"):
            runtime["capturedMode"] = runtime["serverMode"] = runtime["controller"]["mode"] = 1
        elif fault == "source_group_page" and args.get("runtimeOffset"):
            runtime["groups"][0]["candidateScope"] = "changed"
        return data


def prepare(world):
    world.__class__ = NativeWorld
    world.literal_scan, world.context_rpc = True, True
    return world


def arguments(**changes):
    return placement.arguments(geometryMode="native_visits", **changes)


async def test_explicit_native_union_accepts_repeated_abc_and_preserves_all_real_xyz_groups(placement_bridge):
    adapter, world = placement_bridge
    prepare(world).capture_page_size = 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan, rows = result.structuredContent["data"], world.positions()
    geometry = plan["geometry"]
    assert len({tuple(row[axis] for axis in AXES) for row in rows}) == 26
    assert len({tuple(row[axis] for axis in ("a", "b", "c")) for row in rows}) == 25
    assert geometry["geometryMode"] == "native_visits" and geometry["axisTransform"] is None and geometry["piece"] is None
    assert geometry["attachmentLayerIncluded"] and geometry["fullStructureChecked"] is geometry["completeRulesKnown"] is False
    assert len(geometry["nativeVisits"]["positions"]) == 26 and len(geometry["nativeVisits"]["groups"]) == 2
    assert plan["existingTileProof"]["airCellsHaveNoExistingTile"] and plan["existingTileProof"]["cells"] == 27
    assert not any(args.get("airSemantics") == "minecraft_air" for op, args in world.calls)
    assert not any("piece" in args or "offsetA" in args for op, args in world.calls if op == "world.structure")
    assert world.guarded_requests


@pytest.mark.parametrize("fault", ["ttl", "validation_age", "unknown_group", "group_truncated", "missing_group",
    "duplicate_xyz", "filtered", "missing_suffix", "stale_owner", "source_mode_page", "source_group_page", "partial_capture"])
async def test_native_union_refuses_incomplete_stale_unknown_or_changed_capture_without_any_write(placement_bridge, fault):
    adapter, world = placement_bridge
    prepare(world).fault, world.capture_page_size = fault, 5
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["spaceVerified"] is False, result.structuredContent
    assert all(operation in ("bridge.health", "client.snapshot", "world.structure", "world.inspect", "world.scan", "world.inspect_many")
        for operation, _ in world.calls)


@pytest.mark.parametrize("fault", ["air_te", "blocker", "unloaded", "wrong_index", "zero_prefix"])
async def test_literal_scan_omission_never_substitutes_for_compact_every_cell_proof(placement_bridge, monkeypatch, fault):
    adapter, world = placement_bridge
    prepare(world)
    original = world.inspect

    def inspect(args):
        data = original(args)
        row = data["results"][0]
        if fault == "air_te":
            row["data"]["tileClass"] = "Residual.Air.Tile"
        elif fault == "blocker":
            row["data"]["registryName"] = "minecraft:stone"
        elif fault == "unloaded":
            row["data"]["loaded"] = False
        elif fault == "wrong_index":
            row["requestIndex"] += 1
        else:
            data.update(processedCount=0, nextOffset=0, results=[])
        return data
    monkeypatch.setattr(world, "inspect", inspect)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and not result.structuredContent["data"]["spaceVerified"], result.structuredContent
    assert sum(operation == "world.inspect_many" for operation, _ in world.calls) == 1
    assert not any(args.get("airSemantics") == "minecraft_air" for _, args in world.calls)


async def test_compact_partial_prefixes_cover_each_cell_once_without_replaying_prefixes(placement_bridge):
    adapter, world = placement_bridge
    prepare(world).compact_prefix = 4
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    batches = [args["positions"] for operation, args in world.calls if operation == "world.inspect_many"]
    actual = [native.key(row) for batch in batches for row in batch[:min(4, len(batch))]]
    assert len(actual) == len(set(actual)) == 27 and len(batches) == 7


async def test_native_union_retains_the_full_under_controller_attachment_layer(placement_bridge):
    adapter, world = placement_bridge
    prepare(world).geometry_height = 5
    original = world.positions
    world.positions = lambda: [{**row, "y": row["y"] + 1} for row in original()]
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    assert plan["geometry"]["visitRelativeBounds"]["min"]["y"] == 0
    assert plan["geometry"]["relativeBounds"]["min"]["y"] == -1
    assert plan["reservedBounds"]["minY"] == plan["proposedController"]["y"] - 1
    assert plan["existingTileProof"]["cells"] == 3 * 6 * 3


async def test_7x5x5_visit_envelope_retains_all_210_attachment_cells_in_two_compact_pages(placement_bridge):
    adapter, world = placement_bridge
    prepare(world)
    relative = [(x, y, z) for x in range(-3, 4) for y in range(5) for z in range(5) if (x, y, z) != (0, 0, 0)]
    selected = [(-3, 0, 0), (3, 4, 4)] + [value for value in relative if value not in ((-3, 0, 0), (3, 4, 4))][:102]
    rows = [{**{axis: placement.SOURCE[axis] + value[index] for index, axis in enumerate(AXES)},
        **dict(zip(("a", "b", "c"), value)), "group": int(value == (0, 0, 1))} for value in selected]
    for index in range(98, 104):
        rows[index].update({key: rows[index - 98][key] for key in ("a", "b", "c")})
    world.positions = lambda: copy.deepcopy(rows)
    result = await adapter.call_tool("mcb_placement_preflight", arguments(groundBand={"minY": 0, "maxY": 1}))
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    assert len(rows) == len({native.key(row) for row in rows}) == 104
    assert len({tuple(row[key] for key in ("a", "b", "c")) for row in rows}) == 98
    assert plan["reservedBounds"]["minY"] == 2 and plan["reservedBounds"]["maxY"] == 7
    assert plan["existingTileProof"]["cells"] == plan["probeCoverage"]["exactCellChecks"] == 210
    batches = [args["positions"] for operation, args in world.calls if operation == "world.inspect_many"]
    assert [len(batch) for batch in batches] == [128, 82]
    assert len({native.key(row) for batch in batches for row in batch}) == 210


async def test_native_compact_requirement_does_not_fall_back_to_literal_air_scan(placement_bridge, monkeypatch):
    adapter, world = placement_bridge
    prepare(world)
    original = adapter.client.http._transport.handle_async_request

    async def respond(request):
        response = await original(request)
        body, envelope = json.loads(request.content), json.loads(await response.aread())
        if body["operation"] == "bridge.health":
            envelope["data"]["worldInspectManyCompact"] = False
        return httpx.Response(200, json=envelope)
    monkeypatch.setattr(adapter.client.http._transport, "handle_async_request", respond)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and result.structuredContent["data"]["reason"] == "placement_existing_tile_proof_unavailable"
    assert world.calls == [("bridge.health", {})]


async def test_uncertain_compact_transport_stops_without_read_or_cursor_replay(placement_bridge, monkeypatch):
    adapter, world = placement_bridge
    prepare(world)
    original = adapter.client.http._transport.handle_async_request
    attempts = []

    async def respond(request):
        body = json.loads(request.content)
        if body["operation"] == "world.inspect_many":
            attempts.append(copy.deepcopy(body))
            raise httpx.ReadError("unknown exact observation delivery", request=request)
        return await original(request)
    monkeypatch.setattr(adapter.client.http._transport, "handle_async_request", respond)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert result.isError and not result.structuredContent["data"]["spaceVerified"]
    assert len(attempts) == 1


async def test_fresh_before_controller_air_tile_invalidates_previous_clear_proof(placement_bridge, monkeypatch):
    adapter, world = placement_bridge
    prepare(world)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan, original = result.structuredContent["data"], world.inspect

    def changed(args):
        data = original(args)
        data["results"][0]["data"]["tileClass"] = "Fresh.Residual.Tile"
        return data
    monkeypatch.setattr(world, "inspect", changed)
    with pytest.raises(BridgeError) as error:
        await revalidate_placement(adapter.client, arguments(), plan)
    assert error.value.details["reason"] == "placement_air_cell_existing_tile"


async def test_fresh_revalidation_cannot_change_native_geometry_mode_to_literal_scan(placement_bridge):
    adapter, world = placement_bridge
    prepare(world)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan, before = result.structuredContent["data"], len(world.calls)
    with pytest.raises(BridgeError) as error:
        await revalidate_placement(adapter.client, placement.arguments(), plan)
    assert error.value.details["reason"] == "placement_geometry_mode_changed"
    assert len(world.calls) == before


@pytest.mark.parametrize("fault", [None, "registry", "meta_tile", "tile_class"])
async def test_fresh_controller_exception_is_exact_and_every_other_air_cell_has_negative_tile_proof(placement_bridge, monkeypatch, fault):
    adapter, world = placement_bridge
    prepare(world)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError, result.structuredContent
    plan = result.structuredContent["data"]
    world.owned_block = {**plan["proposedController"], "source": "integrated_server", "dimension": 0,
        "registryName": native.CONTROLLER_ITEM["registryName"], "metadata": 0, "tileClass": "Public.Tile",
        "gregtech": {"metaTileId": native.CONTROLLER_ITEM["metadata"]}}
    world.extra[native.key(world.owned_block)] = {key: value for key, value in world.owned_block.items() if key not in (*AXES, "source", "dimension")}
    world.owned_fault = fault
    if fault:
        with pytest.raises(BridgeError):
            await revalidate_placement(adapter.client, arguments(), plan, owned_controller=plan["proposedController"])
    else:
        proof = await revalidate_placement(adapter.client, arguments(), plan, owned_controller=plan["proposedController"])
        assert proof["existingTileProof"]["cells"] == 27 and proof["ownedControllerCellCounted"]
        assert proof["existingTileProof"]["ownedControllerOnlyException"]
        assert proof["existingTileProof"]["ownedController"]["gregtech"]["metaTileId"] == native.CONTROLLER_ITEM["metadata"]


@pytest.mark.parametrize("mode", [None, "public_piece"])
async def test_default_public_piece_still_refuses_repeated_abc_anchor_instead_of_silently_adopting_union(placement_bridge, mode):
    adapter, world = placement_bridge
    prepare(world)
    args = placement.arguments(**({"geometryMode": mode} if mode else {}))
    result = await adapter.call_tool("mcb_placement_preflight", args)
    assert result.isError and result.structuredContent["data"]["reason"] == "public_anchor_translation_ambiguous"


@pytest.mark.parametrize("source_change", [{"piece": "body"}, {"piece": "body", "offsetA": 0, "offsetB": 0, "offsetC": 0}])
def test_explicit_union_schema_does_not_allow_piece_or_abc_offsets(source_change):
    assert list(tool_validator("mcb_placement_preflight").iter_errors(arguments(geometrySource={**placement.SOURCE, **source_change})))


def builder_wrapper(client, world, state, target_fault):
    original = client.call
    compact_calls = 0

    async def call(operation, args, **options):
        nonlocal compact_calls
        if operation == "world.structure" and args["x"] == placement.SOURCE["x"]:
            return world.structure(args)
        if operation == "world.scan":
            return world.scan(args)
        if operation == "world.inspect" and all(args[axis] == placement.SOURCE[axis] for axis in AXES):
            data = world.source_block()
            data["tileClass"] = "Untrusted.World.Tile"
            return data
        # The native mock historically required floor planning on every read.
        # Real explicit capture lookups also allow that optional field absent.
        fixture_args = {"floorBlockY": 1, **args} if operation == "world.structure" else args
        data = await original(operation, fixture_args, **options)
        if operation == "bridge.health":
            data.update(worldScanBounds=True, worldBlockPublicMaterial=True, worldInspectManyCompact=True, worldScanMinecraftAir=True)
        elif operation == "world.spatial":
            # Enrich this deterministic fixture with the real DTO fields that
            # the older base mock omitted; production never synthesizes a hit.
            data.update(source="integrated_server", dimension=0)
            if args["mode"] == "positions":
                data.update(hasMore=False, byteLimited=False, nextOffset=None)
            else:
                data.update(entityCoveragePartial=False, stoppedAt=None)
                if isinstance(data.get("hit"), dict) and data["hit"].get("type") == "block":
                    data["hit"]["point"] = dict(args["to"])
        elif operation == "player.place_block":
            attachment = data.get("attachment", {})
            if all("hit" + axis.upper() in attachment for axis in AXES):
                data["endingView"] = _placement_aim({**state["position"], "y": state["position"]["y"] + 1.62},
                    {axis: attachment["hit" + axis.upper()] for axis in AXES})
                if state["placements"][-1]["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"]:
                    state["controller_direction"] = _normal_gt_placement_facing(data["endingView"])
        elif operation == "player.interact" and args.get("sneaking") and state["placements"] \
                and state["placements"][-1]["item"]["metadata"] == native.CONTROLLER_ITEM["metadata"] and state["selected"] != 0:
            vector = _VECTORS[args["side"]]
            hit = {axis: args[axis] + .5 + vector[index] * .499 for index, axis in enumerate(AXES)}
            data["endingView"] = _placement_aim({**state["position"], "y": state["position"]["y"] + 1.62}, hit)
            state["controller_direction"] = _normal_gt_placement_facing(data["endingView"])
        elif operation == "world.inspect_many" and args.get("compact"):
            compact_calls += 1
            data = {**data, "requestedCount": len(args["positions"]), "processedCount": len(args["positions"]),
                "nextOffset": len(args["positions"]), "complete": True, "partial": False, "byteLimited": False,
                "budgetLimited": False, "worldTick": 10, "nonAtomic": True}
            for index, row in enumerate(data["results"]):
                row.update(requestIndex=index)
                row["data"]["loaded"] = True
            if (target_fault == "before_controller_tile" and compact_calls == 2
                or target_fault == "before_shell_tile" and compact_calls == 3):
                row = next(row for row in data["results"] if native.key(row["data"]) != native.key(state["controller"]))
                row["data"]["tileClass"] = "Fresh.Residual.Tile"
        elif operation == "world.structure":
            all_rows = [{**row, **{axis: row[axis] - placement.SOURCE[axis] + state["controller"][axis] for axis in AXES}}
                for row in world.positions()]
            runtime = complete_runtime(data["runtimeObservation"], state["controller"], all_rows, state["capture_id"], alignment=world.alignment)
            data.update(controller={**state["controller"], "dimension": 0, "metaClass": "Public.Controller"},
                alignment={**world.alignment, "direction": state.get("controller_direction", world.alignment["direction"])})
            if target_fault == "interior_xyz":
                row = next(row for row in runtime["positions"] if tuple(row[axis] - state["controller"][axis] for axis in AXES) == (0, 0, 1))
                row.update(state["controller"])
            elif target_fault == "group_partition":
                for row in runtime["positions"]:
                    if row["group"] == 1:
                        other = next(other for other in runtime["positions"] if other["group"] == 0)
                        row["group"], other["group"] = other["group"], row["group"]
                        break
                for group in runtime["groups"]:
                    member = next(row for row in runtime["positions"] if row["group"] == group["id"])
                    group["representativePosition"] = {axis: member[axis] for axis in AXES}
            elif target_fault == "group_semantics":
                runtime["groups"][0]["candidateScope"] += " changed"
            elif target_fault == "mode":
                runtime["capturedMode"] = runtime["serverMode"] = runtime["controller"]["mode"] = 1
            elif target_fault == "trigger":
                runtime["trigger"]["channelData"] = "changed"
            elif target_fault == "facing":
                runtime["capturedFacing"] = runtime["serverFacing"] = runtime["controller"]["facing"] = "SOUTH_NORMAL_NONE"
            elif target_fault == "ttl":
                runtime["ageMillis"] = 30001
            elif target_fault == "capture_alias":
                runtime["captureId"] = "source-capture"
                for group in runtime["groups"]:
                    group["roleSelection"]["captureId"] = "source-capture"
            elif target_fault == "floor":
                runtime["planning"].update(floorConflict=True, raiseBlocks=1)
            data["runtimeObservation"] = runtime
        return data
    return call


@pytest.mark.parametrize("fault", [None, "interior_xyz", "group_partition", "group_semantics", "mode", "trigger", "facing", "ttl", "capture_alias", "floor"])
async def test_native_builder_requires_exact_fresh_xyz_and_groups_before_shell_then_real_registered_formation(native_construction, monkeypatch, fault):
    client, state = native_construction
    world = NativeWorld()
    monkeypatch.setattr(client, "call", builder_wrapper(client, world, state, fault))
    result = await native.invoke(client, native.declaration(placement={"geometryMode": "native_visits",
        "geometrySource": placement.SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}))
    assert state["hint_count"] == 1, result
    if fault:
        assert result["status"] != "completed" and not result["formationVerified"] and state["construct_count"] == 0, result
        assert not result["placement"].get("newNativeGeometryVerified")
    else:
        assert result["status"] == "completed" and result["formationVerified"] and state["construct_count"] == 1, result
        assert result["placement"]["beforeController"]["existingTileProof"]["cells"] == 27
        assert result["placement"]["beforeNativeConstruction"]["existingTileProof"]["cells"] == 27
        assert result["placement"]["newNativeGeometryProof"]["completeNormalizedXYZAndGroupsMatch"]
        assert result["acceptance"]["registrationAcceptance"]["complete"]


@pytest.mark.parametrize("fault,expected_hints", [("before_controller_tile", 0), ("before_shell_tile", 1)])
async def test_builder_fresh_full_box_tile_failure_stops_before_its_corresponding_write(native_construction, monkeypatch, fault, expected_hints):
    client, state = native_construction
    monkeypatch.setattr(client, "call", builder_wrapper(client, NativeWorld(), state, fault))
    result = await native.invoke(client, native.declaration(placement={"geometryMode": "native_visits",
        "geometrySource": placement.SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0}))
    assert result["reason"] == "placement_air_cell_existing_tile" and not result["formationVerified"], result
    assert state["hint_count"] == expected_hints and state["construct_count"] == 0
    if expected_hints == 0:
        assert not state["placements"]


async def test_native_optional_support_column_cannot_escape_the_complete_reserved_box(native_construction, monkeypatch):
    client, state = native_construction
    monkeypatch.setattr(client, "call", builder_wrapper(client, NativeWorld(), state, None))
    result = await native.invoke(client, native.declaration(supportMode="command_column", placement={"geometryMode": "native_visits",
        "geometrySource": placement.SOURCE.copy(), "groundBand": {"minY": 0, "maxY": 8}, "clearance": 2, "searchRadius": 0}))
    assert result["reason"] == "placement_support_outside_reserved_box", result
    assert state["hint_count"] == state["construct_count"] == 0 and not state["placements"]
    assert not any(call["operation"] == "chat.send" and "//" in call["args"]["message"] for call in state["calls"])


@pytest.mark.parametrize("fault", [None, "changed", "tile", "missing", "foreign", "duplicate", "outside"])
async def test_accounted_non_tile_seed_exceptions_still_read_every_cell(placement_bridge, fault):
    adapter, world = placement_bridge
    prepare(world)
    result = await adapter.call_tool("mcb_placement_preflight", arguments())
    assert not result.isError
    plan = result.structuredContent["data"]
    controller = plan["proposedController"]
    world.owned_block = {**controller, "source": "integrated_server", "dimension": 0,
        "registryName": native.CONTROLLER_ITEM["registryName"], "metadata": 0, "tileClass": "Public.Tile",
        "gregtech": {"metaTileId": native.CONTROLLER_ITEM["metadata"]}}
    world.extra[native.key(controller)] = {k: v for k, v in world.owned_block.items() if k not in (*AXES, "source", "dimension")}
    point = {**controller, "x": controller["x"] + 1}
    identity = {"registryName": "example:seed", "metadata": 0}
    seeds = [{"position": point.copy(), "block": identity.copy()}]
    world.extra[native.key(point)] = identity.copy()
    if fault == "changed": world.extra[native.key(point)]["metadata"] = 1
    if fault == "tile": world.extra[native.key(point)]["tileClass"] = "Unexpected.Tile"
    if fault == "missing": world.extra.pop(native.key(point))
    if fault == "foreign": world.extra[native.key({**point, "z": point["z"]+1})] = identity.copy()
    if fault == "duplicate": seeds.append(copy.deepcopy(seeds[0]))
    if fault == "outside": seeds[0]["position"]["x"] += 10
    if fault:
        with pytest.raises(BridgeError):
            await revalidate_placement(adapter.client, arguments(), plan, owned_controller=controller, owned_seeds=seeds)
    else:
        proof = await revalidate_placement(adapter.client, arguments(), plan, owned_controller=controller, owned_seeds=seeds)
        assert proof["existingTileProof"]["cells"] == 27
        assert proof["ownedSeedCells"] == proof["existingTileProof"]["ownedSeedCells"] == 1
        assert not proof["existingTileProof"]["ownedControllerOnlyException"]
