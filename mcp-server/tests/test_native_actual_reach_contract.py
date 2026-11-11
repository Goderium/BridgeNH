"""Guarded actual reach adoption; scalar fixtures do not measure live navigation savings."""

import math

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_navigation as navigation
import test_native_shape_ray_contract as sampled

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction


def make_reach_task(kind, client, state, version=1):
    task = navigation.make_task(kind, client, state)
    if version is not None:
        task.approach_health["approachNormalReachVersion"] = version
    return task


def actual_reach(receipt, *, client_reach=4.5, server_reach=5, cap=3, explicit=False):
    current = receipt["current"]
    current.update(normalReachVersion=1, clientNormalReach=client_reach, serverNormalReach=server_reach,
        normalReach=min(client_reach, server_reach), reachPlayerId=navigation.UUID,
        maxDistance=min(cap, client_reach, server_reach))
    receipt["serverFlight"].update(normalReach=server_reach, playerId=navigation.UUID)
    receipt["coverage"]["normalDistanceBound"] = current["maxDistance"]
    if explicit:
        receipt["useNormalReach"] = True
    return sampled.sampled_contract(receipt)


def install_actual_reach(client, state, monkeypatch, *, client_reach=4.5, server_reach=5, fault=None, dimension=180):
    navigation.install_survival_navigation(client, state, monkeypatch, dimension,
        fault=fault if fault in ("same_sample", "final_ray") else None)
    original = client.call

    async def observed(operation, args, **kwargs):
        value = await original(operation, args, **kwargs)
        if operation != "player.plan_approach":
            return value
        # Reconstruct exact-current geometry at the fixture's real continuous
        # feet. The ordinary fixture only builds its initial proof within3.
        value = navigation.planner.composite_receipt(state, args)
        value["serverFlight"].update(creative=False, flying=False, onGround=True)
        navigation.survival_snapshot(value.context["snapshot"], state, dimension)
        if args.get("currentOnly") is True:
            navigation.actual_current_receipt(value, state)
        actual_reach(value, client_reach=client_reach, server_reach=server_reach,
            cap=args["maxDistance"], explicit=args.get("useNormalReach", False))
        if args.get("currentOnly") is True:
            selected = value["selected"]
            eye, hit, attachment = selected["eye"], selected["hit"], selected["attachment"]
            vector = navigation.planner.VECTORS[attachment["side"]]
            outward = sum((eye[axis] - attachment[axis] - .5 - .5 * vector[index]) * vector[index]
                for index, axis in enumerate(("x", "y", "z")))
            if outward <= .001 or math.dist(tuple(eye[axis] for axis in ("x", "y", "z")),
                    tuple(hit[axis] for axis in ("x", "y", "z"))) > value["current"]["maxDistance"]:
                navigation.no_current_candidate(value)
                value["coverage"]["serverRays"] = 27
            else:
                value["coverage"].update(serverRays=2, clientRays=2)
            if state["navigation_calls"] and fault == "final_ray" and value["selected"] is not None:
                value["selected"]["clientRay"]["verified"] = False
        if args.get("useNormalReach") is True:
            if fault == "missing_server": value["current"].pop("serverNormalReach")
            elif fault == "unknown":
                value["coverage"]["unknownChecks"] = 1
                if value["selected"] is not None:
                    value["selected"]["unknownChecks"] = 1
            elif fault == "partial": value["partial"] = True
            elif fault == "server_actor": value["serverFlight"]["playerId"] = "another-player"
            elif fault == "over_cap": value["current"]["maxDistance"] = value["current"]["normalReach"] + .1
            elif fault == "client_getter_drift": value["current"]["clientNormalReach"] -= .1
            elif fault == "server_getter_drift": value["serverFlight"]["normalReach"] -= .1
            elif fault == "context_pose_drift": value.context["snapshot"]["player"]["x"] += .1
            elif fault == "context_session_drift": value.context["snapshot"]["sessionId"] = "another-session"
            elif fault == "control_actor_drift": value.context["snapshot"]["serverControl"]["playerId"] = "another-player"
            elif fault == "body": value["selected"]["serverBody"]["occupiable"] = False
            elif fault == "ray": value["selected"]["clientRay"]["verified"] = False
            elif fault == "native_getter_drift": raise BridgeError("stale_world", "Native getter changed during the guarded read.")
            elif fault == "outcome_unknown": raise BridgeError("connection_lost", "Current proof completion is unknown.", outcome_unknown=True)
            elif fault == "legacy_receipt":
                for key in ("normalReachVersion", "clientNormalReach", "serverNormalReach", "reachPlayerId"):
                    value["current"].pop(key)
        return value

    monkeypatch.setattr(client, "call", observed)


@pytest.mark.parametrize("client_reach,server_reach,distance,should_move", [
    (3, 3, 2.4, False), (3, 3, 3.4, True),
    (4.5, 5, 3.4, False), (5, 5, 4.7, False), (5, 4, 4.3, True),
    (4.5, 2.5, 3.4, True),
])
async def test_actual_getter_minimum_can_reuse_farther_current_stance_without_relaxing_tighter_server(native_bridge,
        monkeypatch, client_reach, server_reach, distance, should_move):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - distance}
    install_actual_reach(client, state, monkeypatch, client_reach=client_reach, server_reach=server_reach)
    task = make_reach_task("recipe", client, state)
    result = await task.resolved_native_approach(target, pattern="cardinal")
    calls = state["planner_calls"]
    assert calls[0]["currentOnly"] is True and calls[0]["useNormalReach"] is True and calls[0]["maxDistance"] == 32
    assert [row.get("currentOnly", False) for row in calls] == ([True, False, True] if should_move else [True])
    assert all(row["useNormalReach"] is True and row["maxDistance"] == min(client_reach, server_reach) for row in calls[1:])
    assert result["current"]["normalReach"] == min(client_reach, server_reach)
    assert len(state["navigation_calls"]) == int(should_move)
    if not should_move:
        assert len(calls) == 1 and result["selected"]["feet"] == state["position"]
    assert [row["operation"] for row in navigation.recipes.writes(state)] == (["player.navigate"] if should_move else [])


async def test_normal_reach_does_not_certify_the_wrong_side(native_bridge, monkeypatch):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - 3.4}
    install_actual_reach(client, state, monkeypatch)
    task = make_reach_task("recipe", client, state)
    result = await task.resolved_native_approach(target, pattern="cardinal", allowed_sides=(4,))
    assert [row.get("currentOnly", False) for row in state["planner_calls"]] == [True, False, True]
    assert len(state["navigation_calls"]) == 1 and result["selected"]["attachment"]["side"] == 4
    assert [row["operation"] for row in navigation.recipes.writes(state)] == ["player.navigate"]


@pytest.mark.parametrize("fault", ["missing_server", "unknown", "partial", "server_actor", "over_cap",
    "client_getter_drift", "server_getter_drift", "context_pose_drift", "context_session_drift", "control_actor_drift",
    "native_getter_drift", "outcome_unknown", "legacy_receipt"])
@pytest.mark.parametrize("distance", [3.4, 5.3])
async def test_first_current_read_cannot_lose_actual_reach_or_native_absence_proof(native_bridge, monkeypatch, fault, distance):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - distance}
    install_actual_reach(client, state, monkeypatch, fault=fault)
    task = make_reach_task("recipe", client, state)
    with pytest.raises(BridgeError):
        await task.resolved_native_approach(target, pattern="cardinal")
    assert len(state["planner_calls"]) == 1 and not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("fault", ["body", "ray"])
async def test_advertised_reach_cannot_replace_actual_body_or_ray_proof(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - 3.4}
    install_actual_reach(client, state, monkeypatch, fault=fault)
    task = make_reach_task("recipe", client, state)
    with pytest.raises(BridgeError):
        await task.resolved_native_approach(target, pattern="cardinal")
    assert len(state["planner_calls"]) == 1 and not navigation.recipes.writes(state)


@pytest.mark.parametrize("version", [None, True, 1.0, 2, "1"])
async def test_missing_or_untyped_advertisement_retains_v4_guarded_two_read_path(native_bridge, monkeypatch, version):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - 3.4}
    install_actual_reach(client, state, monkeypatch)
    task = make_reach_task("recipe", client, state, version)
    result = await task.resolved_native_approach(target, pattern="cardinal")
    calls = state["planner_calls"]
    assert len(calls) == 2 and all(row["currentOnly"] is True for row in calls)
    assert calls[0]["maxDistance"] == 3 and "useNormalReach" not in calls[0]
    assert calls[1]["maxDistance"] == 4.5 and calls[1]["useNormalReach"] is True
    assert result["selected"]["feet"] == state["position"] and not navigation.recipes.writes(state)


async def test_old_mod_without_actual_reach_contract_keeps_default_cap_and_navigation(native_bridge, monkeypatch):
    client, state = native_bridge
    navigation.install_survival_navigation(client, state, monkeypatch, 180)
    task = navigation.make_task("recipe", client, state)
    await navigation.approach("recipe", task)
    calls = state["planner_calls"]
    assert [row.get("currentOnly", False) for row in calls] == [True, False, True]
    assert all(row["maxDistance"] == 3 and "useNormalReach" not in row for row in calls)
    assert len(state["navigation_calls"]) == 1


async def test_low_level_native_approach_retains_three_block_default_even_with_advertisement(native_bridge, monkeypatch):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    install_actual_reach(client, state, monkeypatch)
    task = make_reach_task("recipe", client, state)
    result = await task.native_approach(target, pattern="cardinal")
    assert len(state["planner_calls"]) == 1 and state["planner_calls"][0]["maxDistance"] == 3
    assert "useNormalReach" not in state["planner_calls"][0] and result["current"]["maxDistance"] == 3


async def test_construction_frontier_reuses_single_current_proof_at_final_read_budget(native_construction, monkeypatch):
    client, state = native_construction
    target = {**navigation.construction.CONTROLLER, "y": 2, "x": navigation.construction.CONTROLLER["x"] + 1}
    state["position"] = {"x": target["x"] + .5, "y": 2, "z": target["z"] - 3.4}
    install_actual_reach(client, state, monkeypatch, dimension=0)
    task = make_reach_task("construction", client, state)
    task.survival_frontier_native_reads = 63
    task.materials[2] = {"item": navigation.construction.PORT_ITEMS["item_input"].copy(), "count": 1}
    assert await task.probe_survival_frontier(target, 2, [({**target, "y": 1}, 1)])
    assert len(state["planner_calls"]) == 1 and state["planner_calls"][0]["maxDistance"] == 32
    assert task.survival_frontier_native_reads == 64
    assert task.survival_frontier_observations[-1]["sharedCurrentProof"] is True
    assert not state["navigation_calls"] and not navigation.construction.writes(state)


@pytest.mark.parametrize("fault", ["unknown", "partial", "context_pose_drift", "native_getter_drift", "legacy_receipt"])
async def test_frontier_cannot_search_or_place_after_invalid_first_current_proof(native_construction, monkeypatch, fault):
    client, state = native_construction
    target = {**navigation.construction.CONTROLLER, "y": 2, "x": navigation.construction.CONTROLLER["x"] + 1}
    state["position"] = {"x": target["x"] + .5, "y": 2, "z": target["z"] - 3.4}
    install_actual_reach(client, state, monkeypatch, dimension=0, fault=fault)
    task = make_reach_task("construction", client, state)
    task.materials[2] = {"item": navigation.construction.PORT_ITEMS["item_input"].copy(), "count": 1}
    with pytest.raises(BridgeError):
        await task.probe_survival_frontier(target, 2, [({**target, "y": 1}, 1)])
    assert len(state["planner_calls"]) == task.survival_frontier_native_reads == 1
    assert not task.survival_frontier_observations and not navigation.construction.writes(state)


@pytest.mark.parametrize("field,bad", [
    ("normalReachVersion", None), ("normalReachVersion", True), ("normalReachVersion", 1.0), ("normalReachVersion", 2),
    ("clientNormalReach", None), ("clientNormalReach", True), ("clientNormalReach", float("nan")),
    ("serverNormalReach", None), ("serverNormalReach", True), ("serverNormalReach", 0),
    ("serverNormalReach", 33), ("serverNormalReach", float("inf")), ("serverNormalReach", 4),
    ("normalReach", 5), ("reachPlayerId", None), ("reachPlayerId", "another-player"),
])
async def test_actual_reach_requires_complete_typed_getters_minimum_and_actor(native_bridge, monkeypatch, field, bad):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    actual_reach(receipt)
    if bad is None:
        receipt["current"].pop(field)
    else:
        receipt["current"][field] = bad
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("client_reach,server_reach,caller_cap", [(4.5, 5, 4.5), (5, 5, 5), (5, 4, 5), (4.5, 5, 32)])
async def test_explicit_caller_ceiling_is_clamped_to_current_actual_getters(native_bridge, monkeypatch,
        client_reach, server_reach, caller_cap):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    actual_reach(receipt, client_reach=client_reach, server_reach=server_reach, cap=caller_cap, explicit=True)
    task.validate_native_approach(receipt, target, **options, use_normal_reach=True, max_distance=caller_cap)
    assert receipt["current"]["maxDistance"] == min(client_reach, server_reach, caller_cap)


@pytest.mark.parametrize("explicit", [False, True])
async def test_unversioned_actual_reach_cannot_increase_the_legacy_three_block_cap(native_bridge, monkeypatch, explicit):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    receipt["current"].update(normalReach=4.5, maxDistance=4.5)
    receipt["coverage"]["normalDistanceBound"] = 4.5
    if explicit:
        receipt["useNormalReach"] = True
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options, use_normal_reach=explicit, max_distance=4.5 if explicit else 3)


@pytest.mark.parametrize("fault", ["same_sample", "final_ray"])
async def test_needed_movement_at_actual_reach_still_requires_new_server_ack_and_current_ray(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - 5.3}
    install_actual_reach(client, state, monkeypatch, fault=fault)
    task = make_reach_task("recipe", client, state)
    with pytest.raises(BridgeError):
        await task.resolved_native_approach(target, pattern="cardinal")
    assert len(state["navigation_calls"]) == 1
    assert [row["operation"] for row in navigation.recipes.writes(state)] == ["player.navigate"]


@pytest.mark.parametrize("distance,version,segmented", [(20, 1, True), (5.3, 1, False), (20, None, False), (20, True, False)])
async def test_long_walking_approach_reuses_one_bounded_native_segmented_route(native_bridge, monkeypatch, distance, version, segmented):
    client, state = native_bridge
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - distance}
    install_actual_reach(client, state, monkeypatch)
    task = make_reach_task("recipe", client, state)
    task.args["timeout_ms"] = 30_000
    task.deadline = task.started + 30
    if version is not None:
        task.approach_health["nativeNavigationSegmentsVersion"] = version
    result = await task.resolved_native_approach(target, pattern="cardinal")
    assert len(state["navigation_calls"]) == 1
    request = state["navigation_calls"][0]
    assert request.get("segmented", False) is segmented
    assert request["timeout_ms"] == (15000 if segmented else 6000)
    assert request["strategy"] == "walking" and request["requireSurvivalActor"] is True
    assert result["selected"]["moveRequired"] is False
    assert [row.get("currentOnly", False) for row in state["planner_calls"]] == [True, False, True]
