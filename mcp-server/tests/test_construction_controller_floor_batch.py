"""Controller/floor preflight batching keeps complete authoritative coverage."""

import copy
import importlib.util
import math
from pathlib import Path
import sys
import time

import anyio
import pytest

from minecraft_codex_bridge import construction_tasks as production_construction
from minecraft_codex_bridge.workflows import run_workflow
from test_construction_tasks import (
    CONTROLLER,
    declaration,
    key,
    native_construction,
    writes,
)


construction = production_construction
stage_source = Path(__file__).with_name("construction_tasks.py")
if stage_source.is_file():
    module_name = "minecraft_codex_bridge._r20_controller_floor_stage"
    spec = importlib.util.spec_from_file_location(module_name, stage_source)
    construction = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = construction
    spec.loader.exec_module(construction)


def preflight_task(client, point=None):
    args = declaration(controller=CONTROLLER.copy() if point is None else point)
    return construction._ConstructionTask(
        client, args, run_workflow, started=time.monotonic()
    )


def point_pages(state):
    return [
        call["args"]["positions"]
        for call in state["calls"]
        if call["operation"] == "world.inspect_many"
    ]


@pytest.mark.parametrize("height", [1, 2, 3, 15, 16, 17, 32, 255])
async def test_controller_and_full_column_share_bounded_authoritative_pages(
    native_construction, height
):
    client, state = native_construction
    point = {**CONTROLLER, "y": height}
    state["blocks"][key(point)] = {"registryName": "minecraft:air", "metadata": 0}
    task = preflight_task(client, point)

    await task.preflight()

    expected = [point] + [
        {**point, "y": y} for y in range(height - 1, -1, -1)
    ]
    pages = point_pages(state)
    assert [row for page in pages for row in page] == expected
    assert len(pages) == math.ceil((height + 1) / 16)
    assert all(1 <= len(page) <= 16 for page in pages)
    assert task.floor == (0 if height == 1 else 1)
    assert writes(state) == []
    assert state["cursor"] is None


@pytest.mark.parametrize("floor", [0, 1, 2])
async def test_full_column_still_selects_highest_actual_nonair_floor(
    native_construction, floor
):
    client, state = native_construction
    point = {**CONTROLLER, "y": 5}
    for y in range(floor + 1, 6):
        state["blocks"][key({**point, "y": y})] = {
            "registryName": "minecraft:air",
            "metadata": 0,
        }
    state["blocks"][key({**point, "y": floor})] = {
        "registryName": "minecraft:stone",
        "metadata": 0,
    }
    task = preflight_task(client, point)

    await task.preflight()

    assert task.floor == floor
    assert point_pages(state) == [
        [point] + [{**point, "y": y} for y in range(4, -1, -1)]
    ]
    assert writes(state) == []


@pytest.mark.parametrize(
    "fault",
    [
        "truncated_missing_tail",
        "missing_middle",
        "wrong_order",
        "duplicate_point",
        "unloaded_row",
        "wrong_page_source",
        "wrong_page_dimension",
        "wrong_row_source",
        "wrong_row_dimension",
        "wrong_coordinate",
        "missing_identity",
        "invalid_metadata",
    ],
)
async def test_malformed_combined_coverage_refuses_before_actions(
    native_construction, fault
):
    client, state = native_construction
    task = preflight_task(client)
    original_call = task.call
    injected = []

    async def malformed_call(operation, args):
        data = await original_call(operation, args)
        if operation != "world.inspect_many" or injected:
            return data
        injected.append(True)
        if fault == "truncated_missing_tail":
            data["results"] = data["results"][:-1]
        elif fault == "missing_middle":
            data["results"].pop(1)
        elif fault == "wrong_order":
            data["results"][0], data["results"][1] = (
                data["results"][1],
                data["results"][0],
            )
        elif fault == "duplicate_point":
            data["results"][1] = copy.deepcopy(data["results"][0])
        elif fault == "unloaded_row":
            data["results"][1] = {
                "ok": False,
                "error": {"code": "chunk_unloaded", "message": "Not loaded."},
            }
        elif fault == "wrong_page_source":
            data["source"] = "client"
        elif fault == "wrong_page_dimension":
            data["dimension"] = 1
        elif fault == "wrong_row_source":
            data["results"][1]["data"]["source"] = "client"
        elif fault == "wrong_row_dimension":
            data["results"][1]["data"]["dimension"] = 1
        elif fault == "wrong_coordinate":
            data["results"][1]["data"]["z"] += 1
        elif fault == "missing_identity":
            data["results"][1]["data"].pop("registryName")
        elif fault == "invalid_metadata":
            data["results"][1]["data"]["metadata"] = None
        return data

    task.call = malformed_call

    with pytest.raises(construction._Unavailable):
        await task.preflight()

    assert injected == [True]
    assert writes(state) == []
    assert state["cursor"] is None


async def test_occupied_controller_still_refuses_before_actions(native_construction):
    client, state = native_construction
    state["blocks"][key(CONTROLLER)] = {
        "registryName": "minecraft:stone",
        "metadata": 0,
    }
    task = preflight_task(client)

    with pytest.raises(construction._Unavailable) as error:
        await task.preflight()

    assert error.value.details["reason"] == "controller_target_not_air"
    assert writes(state) == []


async def test_zero_height_air_controller_without_floor_still_refuses(
    native_construction,
):
    client, state = native_construction
    point = {**CONTROLLER, "y": 0}
    state["blocks"][key(point)] = {"registryName": "minecraft:air", "metadata": 0}
    task = preflight_task(client, point)

    with pytest.raises(construction._Unavailable) as error:
        await task.preflight()

    assert error.value.details["reason"] == "floor_unavailable"
    assert point_pages(state) == [[point]]
    assert writes(state) == []


async def test_nonempty_cursor_still_stops_before_controller_floor_read(
    native_construction,
):
    client, state = native_construction
    state["cursor"] = {
        "registryName": "minecraft:stone",
        "metadata": 0,
        "count": 1,
        "hasTag": False,
    }
    preserved = copy.deepcopy(state["cursor"])
    task = preflight_task(client)

    with pytest.raises(construction._Unavailable):
        await task.preflight()

    assert point_pages(state) == []
    assert state["cursor"] == preserved
    assert writes(state) == []


async def test_staged_full_build_keeps_formation_and_recovery(native_construction):
    client, state = native_construction
    original_items = copy.deepcopy(state["items"])

    report = await construction.run_blueprint_build(
        client, declaration(restoreInventory=True), anyio.Lock(), runner=run_workflow
    )

    assert report["status"] == "completed"
    assert report["formationVerified"] is True
    assert state["formed"] is True
    assert state["construct_count"] == 1
    assert state["cursor"] is None
    assert state["items"] == original_items
    assert state["selected"] == 7
