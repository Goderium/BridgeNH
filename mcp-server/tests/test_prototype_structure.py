"""Prototype and placed StructureLib contexts must never be mixed."""

import pytest

from minecraft_codex_bridge.validation import tool_validator


def prototype(**extra):
    return {"registryName": "gregtech:gt.blockmachines", "metadata": 1234, **extra}


@pytest.mark.parametrize("arguments", [
    prototype(), prototype(piece="main", offset=0, limit=1),
    prototype(piece="main", variant=64, dimension=180),
    prototype(piece="main", boundsOnly=True),
    prototype(piece="main", offset=2_097_152),
    {"x": 3, "y": 7, "z": 9},
    {"x": 3, "y": 7, "z": 9, "piece": "main", "boundsOnly": True},
    {"x": 3, "y": 7, "z": 9, "piece": "main", "offsetA": 1, "offsetB": 2, "offsetC": 3},
    {"x": 3, "y": 7, "z": 9, "runtimeOffset": 1, "captureId": "actual-capture"},
])
def test_bounded_prototype_and_existing_placed_queries_validate(arguments):
    tool_validator("mcb_world_structure").validate(arguments)


@pytest.mark.parametrize("extra", [
    {"x": 3}, {"y": 7}, {"z": 9}, {"x": 3, "y": 7, "z": 9},
    {"piece": "main", "offsetA": 1, "offsetB": 2, "offsetC": 3},
    {"floorBlockY": 1}, {"runtimeOffset": 0}, {"captureId": "actual-capture"},
    {"metadata": 32767}, {"metadata": -1}, {"metadata": True},
    {"registryName": "gregtech:gt.blockmachines\n"},
    {"boundsOnly": True}, {"piece": "main", "boundsOnly": True, "offset": 1},
    {"piece": "main", "boundsOnly": 1}, {"piece": "main", "offset": 2_097_153},
])
def test_prototype_has_no_world_anchor_runtime_or_invalid_identity(extra):
    assert not tool_validator("mcb_world_structure").is_valid(prototype(**extra))


@pytest.mark.parametrize("arguments", [
    {}, {"registryName": "gregtech:gt.blockmachines"}, {"metadata": 1234},
    {"x": 3, "y": 7}, {"x": 3, "y": 7, "z": 9, "metadata": 1234},
])
def test_one_complete_exclusive_identity_is_required(arguments):
    assert not tool_validator("mcb_world_structure").is_valid(arguments)


@pytest.mark.parametrize("extra", [{"captureId": "capture"}, {"runtimeOffset": 0}, {"floorBlockY": 1}])
def test_summary_only_never_mixes_runtime_or_floor_planning(extra):
    arguments = {"x": 3, "y": 7, "z": 9, "piece": "main", "boundsOnly": True, **extra}
    assert not tool_validator("mcb_world_structure").is_valid(arguments)
