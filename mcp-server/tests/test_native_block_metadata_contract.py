"""Exact raw native metadata identities; fixtures do not establish live construction success."""

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_actual_reach_contract as reach
import test_native_navigation as navigation
import test_native_shape_ray_contract as sampled

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction
SHEET = {"registryName": "gregtech:gt.sheetmetal", "metadata": 81}


def identities(receipt):
    return {"target": receipt["target"], "attachment": receipt["selected"]["attachment"],
        "server_ray": receipt["selected"]["serverRay"], "client_ray": receipt["selected"]["clientRay"]}


def world_receipt(client, state, monkeypatch, *, metadata=81, gt=False):
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    reach.actual_reach(receipt, cap=32, explicit=True)
    for identity in identities(receipt).values():
        identity["metadata"] = metadata
        if not gt:
            identity["registryName"] = SHEET["registryName"]
            identity.pop("gregtech", None)
    expected = {key: receipt["target"][key] for key in ("registryName", "metadata")}
    if gt:
        expected.update(receipt["target"]["gregtech"])
    options.update(target_identity=expected, use_normal_reach=True, max_distance=32)
    return task, receipt, target, options


@pytest.mark.parametrize("metadata", [0, 15, 16, 81, 32_767, 65_535])
async def test_raw_unsigned_16_bit_metadata_survives_exact_target_attachment_and_both_rays(native_bridge, monkeypatch, metadata):
    client, state = native_bridge
    task, receipt, target, options = world_receipt(client, state, monkeypatch, metadata=metadata)
    task.validate_native_approach(receipt, target, **options)
    assert all(identity["metadata"] == metadata and "gregtech" not in identity for identity in identities(receipt).values())
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("location", ["target", "attachment", "server_ray", "client_ray"])
@pytest.mark.parametrize("metadata", [None, True, False, -1, 81.0, 65_536, 2 ** 100])
async def test_invalid_or_unbounded_metadata_is_rejected_in_every_native_identity_phase(native_bridge, monkeypatch, location, metadata):
    client, state = native_bridge
    task, receipt, target, options = world_receipt(client, state, monkeypatch)
    if metadata is None:
        identities(receipt)[location].pop("metadata")
    else:
        identities(receipt)[location]["metadata"] = metadata
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("location", ["target", "attachment", "server_ray", "client_ray"])
@pytest.mark.parametrize("field,bad", [("metadata", 82), ("registryName", "fixture:other_block")])
async def test_valid_extended_metadata_never_weakens_exact_registry_or_client_server_identity(native_bridge, monkeypatch, location, field, bad):
    client, state = native_bridge
    task, receipt, target, options = world_receipt(client, state, monkeypatch)
    identities(receipt)[location][field] = bad
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("location", ["target", "attachment", "server_ray", "client_ray"])
@pytest.mark.parametrize("fault", ["missing", "id_bool", "id_world_metadata", "id_unbounded", "class_missing", "class_changed"])
async def test_gt_world_metadata_stays_separate_from_mandatory_actual_meta_tile_identity(native_bridge, monkeypatch, location, fault):
    client, state = native_bridge
    task, receipt, target, options = world_receipt(client, state, monkeypatch, gt=True)
    identity = identities(receipt)[location]
    if fault == "missing": identity.pop("gregtech")
    elif fault == "id_bool": identity["gregtech"]["metaTileId"] = True
    elif fault == "id_world_metadata": identity["gregtech"]["metaTileId"] = 81
    elif fault == "id_unbounded": identity["gregtech"]["metaTileId"] = 65_535
    elif fault == "class_missing": identity["gregtech"].pop("metaClass")
    elif fault == "class_changed": identity["gregtech"]["metaClass"] = "fixture.OtherMeta"
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not navigation.recipes.writes(state)


async def test_gt_target_accepts_extended_world_metadata_without_changing_meta_tile_id(native_bridge, monkeypatch):
    client, state = native_bridge
    task, receipt, target, options = world_receipt(client, state, monkeypatch, gt=True)
    task.validate_native_approach(receipt, target, **options)
    assert all(identity["metadata"] == 81 and identity["gregtech"]["metaTileId"] == 77 for identity in identities(receipt).values())


async def test_real_sheet_stack_attachment_81_with_distinct_requested_controller_slot_is_usable(native_construction, monkeypatch):
    client, state = native_construction
    target = {**navigation.construction.CONTROLLER, "y": 5}
    attachment = {**target, "y": 4}
    state["blocks"][navigation.construction.key(attachment)] = SHEET.copy()
    state["items"][7] = navigation.construction.stack(SHEET, 23)
    state["items"][0] = navigation.construction.stack(navigation.construction.CONTROLLER_ITEM)
    state["selected"] = 7
    reach.install_actual_reach(client, state, monkeypatch, dimension=0)
    state["position"] = {"x": target["x"] + .5, "y": 4, "z": target["z"] - 3.4}
    task = reach.make_reach_task("construction", client, state)
    receipt = await task.current_native_approach(target, intent="place_block", allowed_sides=(1,), hotbar_slot=0)
    assert receipt["status"] == "ready" and receipt["selected"]["attachment"]["metadata"] == 81
    assert receipt["current"]["item"]["metadata"] == 81 and receipt["current"]["item"]["count"] == 23
    assert receipt["current"]["selectedHotbarSlot"] == 7 and receipt["current"]["requestedHotbarSlot"] == 0
    assert receipt["current"]["sourceItem"]["metadata"] == navigation.construction.CONTROLLER_ITEM["metadata"]
    assert len(state["planner_calls"]) == 1 and not navigation.construction.writes(state)
