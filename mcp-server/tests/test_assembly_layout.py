"""Attached equipment affects elevation; hidden routing never authorizes earth writes."""

import pytest

import test_placement_preflight as base
from minecraft_codex_bridge.bridge import BridgeError, READ_OPERATIONS
from minecraft_codex_bridge.placement_tasks import assembly_layout, revalidate_placement
from minecraft_codex_bridge.validation import tool_validator

placement_bridge = base.placement_bridge
native_construction = base.native_construction


def component(name="ae", low=(3, -3, 0), high=(4, 1, 2), **changes):
    return {"id": name, "role": "equipment", "relativeBounds": {
        "min": dict(zip("xyz", low)), "max": dict(zip("xyz", high))}, **changes}


def hidden():
    return component("io", (0, -2, 1), (0, -2, 1), role="io_routing", placement="hidden_bottom")


async def plan(adapter, *, unavailable=False, **changes):
    result = await adapter.call_tool("mcb_placement_preflight", base.arguments(**changes))
    assert bool(result.isError) == unavailable, result.structuredContent
    return result.structuredContent["data"]


@pytest.mark.parametrize("mode", ["compact", "literal", "native"])
async def test_ae_lower_offset_raises_entire_machine_and_returns_world_positions(placement_bridge, mode):
    adapter, world = placement_bridge
    world.literal_scan = mode == "literal"
    options = {}
    if mode == "native":
        from test_native_visit_placement import prepare
        prepare(world)
        options["geometryMode"] = "native_visits"
    data = await plan(adapter, layoutComponents=[component()], **options)
    assert data["spaceVerified"], data
    assert data["proposedController"]["y"] == 5
    assert data["geometry"]["relativeBounds"]["min"]["y"] == -1
    assert data["reservedBounds"]["minY"] == 2
    assert data["assemblyLayout"]["components"][0]["worldBounds"]["minY"] == 2
    assert data["reservedBounds"]["maxX"] == base.native.CONTROLLER["x"] + 4
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)
    await revalidate_placement(adapter.client, base.arguments(layoutComponents=[component()], **options), data)


async def test_ground_under_ae_extension_is_included_in_height(placement_bridge):
    adapter, world = placement_bridge
    x, z = base.native.CONTROLLER["x"] + 4, base.native.CONTROLLER["z"]
    for y in (2, 3, 4):
        world.extra[(x, y, z)] = {"registryName": "minecraft:stone", "metadata": 0, "publicMaterial": base.MATERIAL.copy()}
    data = await plan(adapter, layoutComponents=[component()])
    assert data["spaceVerified"] and data["floorBlockY"] == 4
    assert data["proposedController"]["y"] == 8


async def test_ae_only_collision_blocks_candidate(placement_bridge):
    adapter, world = placement_bridge
    world.extra[(base.native.CONTROLLER["x"] + 4, 3, base.native.CONTROLLER["z"])] = {
        "registryName": "minecraft:chest", "metadata": 0, "tileClass": "Chest"}
    data = await plan(adapter, unavailable=True, layoutComponents=[component()])
    assert not data["spaceVerified"] and data["reason"] == "placement_no_clear_candidate"


async def test_new_obstacle_in_ae_space_is_not_ignored_by_builder_recheck(placement_bridge):
    adapter, world = placement_bridge
    data = await plan(adapter, layoutComponents=[component()])
    world.extra[(base.native.CONTROLLER["x"] + 4, 3, base.native.CONTROLLER["z"])] = {
        "registryName": "minecraft:chest", "metadata": 0, "tileClass": "Chest"}
    with pytest.raises(BridgeError):
        await revalidate_placement(adapter.client, base.arguments(layoutComponents=[component()]), data)


@pytest.mark.parametrize("matrix,alignment", [
    ([[0, 0, -1], [0, 1, 0], [1, 0, 0]], {"direction": "WEST", "rotation": "normal", "flip": "none"}),
    ([[-1, 0, 0], [0, 1, 0], [0, 0, -1]], {"direction": "SOUTH", "rotation": "normal", "flip": "none"}),
])
async def test_aligned_machine_and_world_xyz_auxiliary_offsets_share_one_anchor(placement_bridge, matrix, alignment):
    adapter, world = placement_bridge
    world.matrix, world.alignment = matrix, alignment
    data = await plan(adapter, layoutComponents=[component()])
    assert data["spaceVerified"], data
    assert data["proposedController"]["y"] == 5
    assert data["geometry"]["alignment"] == alignment
    assert data["assemblyLayout"]["components"][0]["worldBounds"]["minX"] == base.native.CONTROLLER["x"] + 3


@pytest.mark.parametrize("mode", ["compact", "literal", "native"])
async def test_hidden_io_uses_only_prepared_bottom_trench_without_raising_machine(placement_bridge, mode):
    adapter, world = placement_bridge
    world.literal_scan = mode == "literal"
    options = {}
    if mode == "native":
        from test_native_visit_placement import prepare
        prepare(world)
        options["geometryMode"] = "native_visits"
    original = world.block
    trench = (base.native.CONTROLLER["x"], 1, base.native.CONTROLLER["z"] + 1)
    world.block = lambda x, y, z: None if (x, y, z) == trench else original(x, y, z)
    data = await plan(adapter, layoutComponents=[hidden()], **options)
    assert data["spaceVerified"], data
    assert data["proposedController"]["y"] == 3
    assert data["reservedBounds"]["minY"] == 1
    assert data["assemblyLayout"]["reservedBoxes"][0]["minY"] == 2
    assert world.block(trench[0] + 1, 1, trench[2])["registryName"] == "minecraft:grass"
    proof = await revalidate_placement(adapter.client, base.arguments(layoutComponents=[hidden()], **options), data)
    if mode == "native":
        assert len(proof["existingTileProof"]["regions"]) == 2
    # A fresh obstacle in the trench must stop the builder before its next write.
    world.block = original
    with pytest.raises(BridgeError):
        await revalidate_placement(adapter.client, base.arguments(layoutComponents=[hidden()], **options), data)
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)


async def test_hidden_io_does_not_overwrite_solid_ground(placement_bridge):
    adapter, world = placement_bridge
    data = await plan(adapter, unavailable=True, layoutComponents=[hidden()])
    assert not data["spaceVerified"] and data["hiddenRoutingBlocked"]
    assert data["reason"] == "placement_no_clear_candidate"
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)


async def test_io_defaults_above_ground(placement_bridge):
    adapter, _ = placement_bridge
    row = hidden()
    row.pop("placement")
    data = await plan(adapter, layoutComponents=[row])
    assert data["spaceVerified"] and data["proposedController"]["y"] == 4


@pytest.mark.parametrize("change", ["bounds", "role", "omit", "receipt"])
async def test_revalidation_cannot_drop_or_change_ae_layout(placement_bridge, change):
    adapter, _ = placement_bridge
    args = base.arguments(layoutComponents=[component()])
    data = await plan(adapter, layoutComponents=args["layoutComponents"])
    if change == "bounds":
        args["layoutComponents"][0]["relativeBounds"]["max"]["x"] += 1
    elif change == "role":
        args["layoutComponents"][0]["role"] = "io_routing"
    elif change == "omit":
        args.pop("layoutComponents")
    else:
        data["assemblyLayout"]["reservedBoxes"].pop()
    with pytest.raises(BridgeError) as error:
        await revalidate_placement(adapter.client, args, data)
    assert error.value.details["reason"] == "placement_layout_changed"


@pytest.mark.parametrize("rows", [
    [hidden() | {"role": "equipment"}],
    [component(), component()],
    [component(low=(4, 0, 0), high=(3, 0, 0))],
    [component(low=(0, -4, 0), high=(0, -3, 0), role="io_routing", placement="hidden_bottom")],
    [component(low=(0, -1, 0), high=(0, -1, 0), role="io_routing", placement="hidden_bottom")],
])
def test_invalid_layout_is_refused(rows):
    machine = {"min": {"x": -1, "y": -1, "z": 0}, "max": {"x": 1, "y": 1, "z": 2}}
    with pytest.raises(BridgeError):
        assembly_layout(machine, rows)


def test_schema_exposes_layout_to_preflight_and_builder():
    from minecraft_codex_bridge.construction_tasks import BLUEPRINT_BUILD_SCHEMA
    from minecraft_codex_bridge.placement_tasks import PLACEMENT_SCHEMA
    import jsonschema
    jsonschema.Draft202012Validator.check_schema(PLACEMENT_SCHEMA)
    assert BLUEPRINT_BUILD_SCHEMA["properties"]["placement"]["properties"]["layoutComponents"] == PLACEMENT_SCHEMA["properties"]["layoutComponents"]
    tool_validator("mcb_placement_preflight").validate(base.arguments(layoutComponents=[hidden()]))
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_placement_preflight").validate(base.arguments(layoutComponents=[hidden() | {"role": "equipment"}]))


async def test_service_ring_wraps_ae_and_preserves_machine_geometry(placement_bridge):
    adapter, world = placement_bridge
    data = await plan(adapter, layoutComponents=[component()], serviceAccess={})
    layout = data["assemblyLayout"]
    rows = [row for row in layout["components"] if row["role"] == "service_access"]
    assert len(rows) == 4 and data["proposedController"]["y"] == 5
    assert data["reservedBounds"]["maxX"] == base.native.CONTROLLER["x"] + 5
    assert layout["serviceAccess"]["headroom"] == 3 and not layout["serviceAccess"]["supportVerified"]
    assert data["geometry"]["relativeBounds"]["min"]["y"] == -1
    assert all(operation in READ_OPERATIONS for operation, _ in world.calls)
    await revalidate_placement(adapter.client, base.arguments(layoutComponents=[component()], serviceAccess={}), data)


async def test_service_ring_roof_collision_is_rechecked(placement_bridge):
    adapter, world = placement_bridge
    args = base.arguments(serviceAccess={})
    data = await plan(adapter, serviceAccess={})
    ring = next(row for row in data["assemblyLayout"]["components"] if row["id"] == "mcb.service.east")["worldBounds"]
    world.extra[(ring["minX"],ring["maxY"],ring["minZ"])] = {
        "registryName": "minecraft:chest", "metadata": 0, "tileClass": "Chest"}
    with pytest.raises(BridgeError):
        await revalidate_placement(adapter.client, args, data)
    refused = await plan(adapter, unavailable=True, serviceAccess={})
    assert refused["reason"] == "placement_no_clear_candidate"


@pytest.mark.parametrize("replacement", [None, {"width": 2}, {"headroom": 2}])
async def test_service_reservation_cannot_be_dropped_on_revalidation(placement_bridge, replacement):
    adapter, _ = placement_bridge
    data = await plan(adapter, serviceAccess={})
    args = base.arguments(**({"serviceAccess": replacement} if replacement is not None else {}))
    with pytest.raises(BridgeError) as error:
        await revalidate_placement(adapter.client, args, data)
    assert error.value.details["reason"] == "placement_layout_changed"


def test_hidden_io_keeps_depth_and_cannot_occupy_service_ring():
    machine = {"min": {"x": -1, "y": -1, "z": 0}, "max": {"x": 1, "y": 1, "z": 2}}
    result = assembly_layout(machine,[hidden()],{})
    assert result["aboveGroundRelativeBounds"]["min"]["y"] == -1
    assert result["combinedRelativeBounds"]["min"]["y"] == -2
    for row in result["components"]:
        if row["role"] == "service_access":
            b=row["relativeBounds"]
            assert b["max"]["x"] < -1 or b["min"]["x"] > 1 or b["max"]["z"] < 0 or b["min"]["z"] > 2


@pytest.mark.parametrize("obstacle", [False, True])
async def test_builder_reuses_assembly_reservations_before_writes(native_construction, monkeypatch, obstacle):
    client, state = native_construction
    world = base.World()
    if obstacle:
        world.extra[(base.native.CONTROLLER["x"] + 4, 3, base.native.CONTROLLER["z"])] = {
            "registryName": "minecraft:chest", "metadata": 0, "tileClass": "Chest"}
    monkeypatch.setattr(client, "call", base.planning_wrapper(client, world))
    declaration = base.native.declaration(placement={"geometrySource": base.SOURCE.copy(),
        "groundBand": {"minY": 0, "maxY": 8}, "searchRadius": 0,
        "layoutComponents": [component(low=(3, -1, 0))]})
    result = await base.native.invoke(client, declaration)
    if obstacle:
        assert result["reason"] == "placement_no_clear_candidate", result
        assert state["hint_count"] == state["construct_count"] == 0
    else:
        assert result["status"] == "completed" and result["formationVerified"], result
        assert result["placement"]["beforeController"]["spaceVerified"]
        assert result["placement"]["beforeNativeConstruction"]["spaceVerified"]
        assert result["placement"]["assemblyLayout"]["components"][0]["worldBounds"]["minX"] == base.native.CONTROLLER["x"] + 3
