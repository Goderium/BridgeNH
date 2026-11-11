import copy
import time
from types import SimpleNamespace

import pytest

import test_recipe_tasks as native
from test_recipe_input_discovery import public_bindings
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import _ConstructionTask
from minecraft_codex_bridge.placement_tasks import _Planner

native_bridge = native.native_bridge


def owned_task(errors, *, changed=False, tile=False):
    task = object.__new__(_ConstructionTask)
    task.deadline = time.monotonic() + 5
    task.phases = []
    calls = []
    expected = {"registryName": "minecraft:stone", "metadata": 0}
    point = {"x": 1, "y": 2, "z": 3}

    async def approach(*args, **kwargs):
        calls.append("plan")
        if errors:
            raise errors.pop(0)
        return point, 2, []

    async def block(target):
        calls.append("server")
        return {**expected, **({"metadata": 1} if changed else {}), **({"tileClass": "Unknown.Tile"} if tile else {})}

    task.approach, task.block = approach, block
    return task, point, expected, calls


def disagreement(**details):
    return BridgeError("target_unverified", "Client and server approach block identities do not agree.", **details)


async def test_owned_sync_reobserves_without_any_action_and_preserves_first_error():
    task, point, expected, calls = owned_task([disagreement(outcome_unknown=False)])
    assert await task.owned_block_approach(point, expected) == (point, 2, [])
    assert calls == ["plan", "server", "plan"]
    assert task.phases[0]["readOnly"] is True and task.phases[0]["status"] == "completed"
    assert task.phases[0]["firstError"]["code"] == "target_unverified"


@pytest.mark.parametrize("error", [disagreement(outcome_unknown=True), disagreement(),
    BridgeError("target_unverified", "The requested owner changed.", outcome_unknown=False),
    BridgeError("stale_world", "World changed.", outcome_unknown=False)])
async def test_generic_or_uncertain_target_errors_do_not_retry(error):
    task, point, expected, calls = owned_task([error])
    with pytest.raises(BridgeError):
        await task.owned_block_approach(point, expected)
    assert calls == ["plan"] and task.phases == []


@pytest.mark.parametrize("changed,tile", [(True, False), (False, True)])
async def test_changed_owned_server_block_stops_before_another_plan(changed, tile):
    task, point, expected, calls = owned_task([disagreement(outcome_unknown=False)], changed=changed, tile=tile)
    with pytest.raises(BridgeError) as error:
        await task.owned_block_approach(point, expected)
    assert error.value.details["reason"] == "owned_block_changed"
    assert calls == ["plan", "server"]


async def test_owned_sync_has_a_finite_attempt_budget():
    task, point, expected, calls = owned_task([disagreement(outcome_unknown=False) for _ in range(20)])
    with pytest.raises(BridgeError) as error:
        await task.owned_block_approach(point, expected)
    assert error.value.details["reason"] == "owned_target_sync_unconfirmed"
    assert 1 <= calls.count("plan") <= 8 and calls.count("plan") == calls.count("server")
    assert task.phases[0]["status"] == "unconfirmed"


async def test_multiblock_catalyst_is_bound_before_clear_or_supply(native_bridge):
    client, state = native_bridge
    public_bindings(state)
    state["input_circuit"] = 0
    arguments = native.declaration(generateMaterials=True, inventoryPreparation="clear")
    arguments.pop("catalystGuiSlot")
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["inputGuiSlot"] == 1 and result["catalystGuiSlot"] == 0
    assert result["catalystGuiBinding"]["inventoryIndex"] == 0
    calls = state["calls"]
    opened = next(i for i, call in enumerate(calls) if call["operation"] == "player.interact"
        and all(call["args"][axis] == native.INPUT[axis] for axis in ("x", "y", "z")))
    cleared = next(i for i, call in enumerate(calls) if call["operation"] == "chat.send" and call["args"]["message"].startswith("/clear "))
    assert opened < cleared
    assert result["acceptance"]["catalystRetained"] is True


async def test_unknown_automatic_catalyst_binding_never_clears_grants_or_pickups(native_bridge):
    client, state = native_bridge
    public_bindings(state, mappings={})
    arguments = native.declaration(generateMaterials=True, inventoryPreparation="clear")
    arguments.pop("catalystGuiSlot")
    result = await native.invoke(client, arguments)
    assert result["available"] is False, result
    assert not any(call["operation"] in ("chat.send", "inventory.supply", "gui.slot_click") for call in state["calls"])


async def test_retained_multiblock_catalyst_has_unique_actual_owner_binding(native_bridge):
    client, state = native_bridge
    public_bindings(state)
    arguments = native.declaration()
    arguments.pop("catalystGuiSlot")
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert result["catalystGuiBinding"]["selection"] == "unique_retained_catalyst"
    assert result["acceptance"]["catalystRetained"] is True


def matching_fixture(*, ambiguous=False, different=False, repeated=False, drift=False, stalled=False):
    alignment = {"direction": "NORTH", "rotation": "normal", "flip": "none"}
    positions = [{"a": 0, "b": 0, "c": 1}, {"a": 1, "b": 0, "c": 1}]
    if repeated:
        positions[1] = positions[0].copy()
    capture = {"available": True, "capturedVisitsComplete": True, "eventCaptureTruncated": False,
        "captureId": "fresh-capture", "capturedPositions": 2, "capturedFacing": "NORTH_NORMAL_NONE", "capturedMode": 0,
        "controller": {"x": 0, "y": 2, "z": 0, "dimension": 0, "metaClass": "Public.Controller"},
        "trigger": {"registryName": "public:blueprint", "metadata": 0, "count": 1, "channelData": ""},
        **{flag: True for flag in ("contextMatches", "modeMatches", "facingMatches", "controllerIdentityMatches")}}
    pieces = [{"name": name, "selectable": True, "geometryAvailable": True, "compiledEntries": 3} for name in ("one", "two")]
    initial = {"pieces": pieces, "piecesTotal": 2, "offset": 0, "nextOffset": 2, "hasMore": False,
        "query": "", "alignment": alignment, "runtimeObservation": copy.deepcopy(capture)}
    configuration = SimpleNamespace(read=lambda: "pinned")
    planner = _Planner(SimpleNamespace(configuration=configuration), {})
    calls = []

    async def structure(args):
        calls.append(args)
        offset = args["offset"]
        coordinate = {"a": 0 if offset == 0 else 1, "b": 0, "c": 1}
        if different or args["piece"] == "two" and not ambiguous:
            coordinate["c"] = 2
        runtime = copy.deepcopy(capture)
        if drift:
            runtime["trigger"]["count"] = 2
        return {"mode": "piece_geometry", "piece": args["piece"], "query": "", "coordinateSpace": "piece_origin",
            "worldPositionsAvailable": False, "offsets": None, "compiledEntries": 3, "offset": offset,
            "positions": [coordinate], "nextOffset": offset if stalled else 2 if offset == 0 else 3,
            "hasMore": stalled or offset == 0, "alignment": alignment, "runtimeObservation": runtime}

    planner.structure = structure
    return planner, initial, alignment, positions, capture, calls


async def test_piece_match_uses_complete_coordinates_and_not_compiled_count():
    planner, initial, alignment, positions, capture, calls = matching_fixture()
    selected, proof = await planner.match_public_piece(initial, alignment, positions, capture)
    assert selected == "one" and proof["catalogueComplete"] is True
    assert len(calls) == 4 and all(row["physicalPositions"] == 2 for row in proof["candidates"])
    assert [row["exactCoordinateSetMatch"] for row in proof["candidates"]] == [True, False]


@pytest.mark.parametrize("fault", ["ambiguous", "different", "repeated", "stalled"])
async def test_piece_match_refuses_nonunique_incomplete_or_multipart_evidence(fault):
    planner, initial, alignment, positions, capture, calls = matching_fixture(**{fault: True})
    with pytest.raises(BridgeError) as error:
        await planner.match_public_piece(initial, alignment, positions, capture)
    assert error.value.details["reason"] == "public_piece_selection_required"
    if fault == "repeated":
        assert calls == []


async def test_piece_match_refuses_capture_trigger_drift():
    planner, initial, alignment, positions, capture, _ = matching_fixture(drift=True)
    with pytest.raises(BridgeError) as error:
        await planner.match_public_piece(initial, alignment, positions, capture)
    assert error.value.details["reason"] == "native_capture_changed"
