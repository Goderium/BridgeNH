"""Actual sampled face hits retain reach and ray guards without a center-ray prerequisite."""

import copy
import math

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_actual_reach_contract as reach
import test_native_approach_client_clock as clock

native_construction = clock.native_construction


@pytest.mark.parametrize("fault", [None, "out_of_reach", "outside_face", "wrong_plane", "different_client_ray", "blocked"])
async def test_sampled_top_edge_with_unreachable_center(native_construction, monkeypatch, fault):
    client, state = native_construction
    task, receipt, target, options = clock.construction_receipt(client, state, monkeypatch)
    selected = receipt["selected"]
    hit = {"x": target["x"] + .5, "y": target["y"], "z": target["z"] + .001}
    center = {**hit, "z": target["z"] + .5}
    reach.actual_reach(receipt, cap=2.3, explicit=True)
    options["max_distance"] = 2.3
    eye = selected["eye"]
    assert math.dist(tuple(eye.values()), tuple(hit.values())) < 2.3
    assert math.dist(tuple(eye.values()), tuple(center.values())) > 2.3
    if fault == "out_of_reach": hit["z"] = target["z"] + .9
    elif fault == "outside_face": hit["x"] = target["x"] - .1
    elif fault == "wrong_plane": hit["y"] += .2
    selected["hit"] = copy.deepcopy(hit)
    for name in ("serverRay", "clientRay"):
        selected[name]["hit"] = copy.deepcopy(hit)
    if fault == "different_client_ray": selected["clientRay"]["hit"]["x"] += .1
    elif fault == "blocked": selected["serverRay"]["verified"] = False
    if fault is None:
        task.validate_native_approach(receipt, target, **options)
    else:
        with pytest.raises(BridgeError):
            task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"]
