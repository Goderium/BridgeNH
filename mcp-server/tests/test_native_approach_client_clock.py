"""Owning-client phase ordering stays separate from synchronized world observations."""

import copy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_actual_reach_contract as reach
import test_native_navigation as navigation
import test_native_shape_ray_contract as sampled

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction

CLOCK_FIELDS = ("clientPhaseClockVersion", "clientPhaseClockSource", "captureClientTick", "recheckClientTick")


def phase_clock(task, receipt, *, capture=700, recheck=701, after=701):
    task.approach_health["approachClientPhaseClockVersion"] = 1
    receipt["current"].update(clientPhaseClockVersion=1, clientPhaseClockSource="bridge_client_tick",
        captureClientTick=capture, recheckClientTick=recheck)
    receipt.context["snapshot"]["clientTicks"] = after


def world_observations(receipt, capture=9_558_519, recheck=9_558_518):
    receipt["current"].update(captureWorldTick=capture, recheckWorldTick=recheck)
    receipt["clientWorldTick"] = recheck
    if receipt["selected"] is not None:
        for name in ("clientBody", "clientRay"):
            receipt["selected"][name]["worldTick"] = recheck


def construction_receipt(client, state, monkeypatch):
    target = {**navigation.construction.CONTROLLER, "y": 2}
    state.update(selected=6, position={"x": target["x"] + .5, "y": 2, "z": target["z"] - 1.5})
    state["items"][0] = {**navigation.construction.CONTROLLER_ITEM, "count": 1, "hasTag": False}
    navigation.install_survival_navigation(client, state, monkeypatch, 0)
    task = reach.make_reach_task("construction", client, state)
    args = {**target, "dimension": 0, "expectedWorld": "new-world", "intent": "place_block", "pattern": "surround",
        "requireFlying": False, "allowedSides": [1], "hotbarSlot": 0}
    receipt = navigation.planner.composite_receipt(state, args)
    receipt["serverFlight"].update(creative=False, flying=False, onGround=True)
    navigation.survival_snapshot(receipt.context["snapshot"], state, 0)
    navigation.actual_current_receipt(receipt, state)
    reach.actual_reach(receipt, cap=4.5, explicit=True)
    options = dict(intent="place_block", pattern="surround", allowed_sides=(1,), hotbar_slot=0,
        target_identity=None, current_only=True, use_normal_reach=True, max_distance=4.5)
    return task, receipt, target, options


@pytest.mark.parametrize("kind", ["recipe", "construction"])
@pytest.mark.parametrize("capture,recheck,after", [(700, 701, 701), (701, 701, 701), (0, 0, 0)])
async def test_native_monotonic_clock_accepts_client_world_rollback_without_cross_domain_comparison(
        native_bridge, native_construction, monkeypatch, kind, capture, recheck, after):
    client, state = native_bridge if kind == "recipe" else native_construction
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch) if kind == "recipe" else \
        construction_receipt(client, state, monkeypatch)
    world_observations(receipt)
    phase_clock(task, receipt, capture=capture, recheck=recheck, after=after)
    server_before = copy.deepcopy((receipt["target"], receipt["serverFlight"], receipt["selected"]["serverBody"], receipt["selected"]["serverRay"]))
    task.validate_native_approach(receipt, target, **options)
    assert receipt["current"]["captureWorldTick"] > receipt["current"]["recheckWorldTick"]
    assert server_before == (receipt["target"], receipt["serverFlight"], receipt["selected"]["serverBody"], receipt["selected"]["serverRay"])
    if kind == "construction":
        assert receipt["current"]["requestedHotbarSlot"] == 0 and receipt["current"]["selectedHotbarSlot"] == 6
        assert receipt["current"]["sourceItem"]["count"] == 1 and state["items"][0]["count"] == 1
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("no_candidate", [False, True])
@pytest.mark.parametrize("field,bad", [
    ("clientPhaseClockVersion", None), ("clientPhaseClockVersion", True), ("clientPhaseClockVersion", 1.0),
    ("clientPhaseClockVersion", 0), ("clientPhaseClockVersion", 2), ("clientPhaseClockVersion", "1"),
    ("clientPhaseClockSource", None), ("clientPhaseClockSource", "client_world_time"),
    ("clientPhaseClockSource", "integrated_server_tick"), ("clientPhaseClockSource", True),
    ("captureClientTick", None), ("captureClientTick", True), ("captureClientTick", 700.0),
    ("captureClientTick", "700"), ("captureClientTick", -1), ("captureClientTick", 702),
    ("recheckClientTick", None), ("recheckClientTick", True), ("recheckClientTick", 701.0),
    ("recheckClientTick", "701"), ("recheckClientTick", -1), ("recheckClientTick", 699),
])
async def test_unknown_partial_or_backward_monotonic_receipts_refuse_before_any_action(
        native_bridge, monkeypatch, no_candidate, field, bad):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch, no_candidate=no_candidate)
    phase_clock(task, receipt)
    if bad is None:
        receipt["current"].pop(field)
    else:
        receipt["current"][field] = bad
    with pytest.raises(BridgeError, match="monotonic phase clock"):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("after", [None, True, 701.0, "701", -1, 700, 702])
async def test_client_phase_recheck_must_bind_exact_typed_final_after_tick(native_bridge, monkeypatch, after):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    phase_clock(task, receipt)
    if after is None:
        receipt.context["snapshot"].pop("clientTicks")
    else:
        receipt.context["snapshot"]["clientTicks"] = after
    with pytest.raises(BridgeError, match="final fresh AFTER"):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("version", [None, True, 1.0, 0, 2, "1"])
async def test_unknown_advertised_clock_version_never_uses_legacy_or_infers_new_clock(native_bridge, monkeypatch, version):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    phase_clock(task, receipt)
    task.approach_health["approachClientPhaseClockVersion"] = version
    with pytest.raises(BridgeError, match="advertised owning-client"):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("missing_advertisement", [False, True])
async def test_advertisement_and_complete_receipt_clock_must_agree(native_bridge, monkeypatch, missing_advertisement):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    phase_clock(task, receipt)
    if missing_advertisement:
        task.approach_health.pop("approachClientPhaseClockVersion")
    else:
        for field in CLOCK_FIELDS:
            receipt["current"].pop(field)
    with pytest.raises(BridgeError, match="advertised owning-client"):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("backward", [False, True])
async def test_legacy_without_new_clock_preserves_original_world_order_guard(native_bridge, monkeypatch, backward):
    client, state = native_bridge
    task, receipt, target, options = sampled.current_receipt(client, state, monkeypatch)
    world_observations(receipt, capture=101 if backward else 100, recheck=100)
    if backward:
        with pytest.raises(BridgeError, match="Legacy approach receipts"):
            task.validate_native_approach(receipt, target, **options)
    else:
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("fault", ["client_body_tick", "client_ray_tick", "server_target_tick", "selected_slot",
    "pose", "session", "world", "dimension", "ray", "body", "source_slot", "normal_reach", "after_phase"])
async def test_monotonic_clock_does_not_replace_owner_pose_slot_ray_body_or_after_guards(native_construction, monkeypatch, fault):
    client, state = native_construction
    task, receipt, target, options = construction_receipt(client, state, monkeypatch)
    world_observations(receipt)
    phase_clock(task, receipt)
    if fault == "client_body_tick": receipt["selected"]["clientBody"]["worldTick"] += 1
    elif fault == "client_ray_tick": receipt["selected"]["clientRay"]["worldTick"] += 1
    elif fault == "server_target_tick": receipt["target"]["worldTick"] += 1
    elif fault == "selected_slot": receipt.context["snapshot"]["player"]["selectedHotbarSlot"] = 0
    elif fault == "pose": receipt.context["snapshot"]["player"]["x"] += .1
    elif fault == "session": receipt.context["snapshot"]["sessionId"] = "another-session"
    elif fault == "world": receipt.context["snapshot"]["world"].update(worldName="another-world", worldFolder="another-world")
    elif fault == "dimension": receipt.context["snapshot"]["world"]["dimension"] = 1
    elif fault == "ray": receipt["selected"]["clientRay"]["verified"] = False
    elif fault == "body": receipt["selected"]["serverBody"]["occupiable"] = False
    elif fault == "source_slot": receipt["current"]["requestedHotbarSlot"] = 6
    elif fault == "normal_reach": receipt["current"]["normalReach"] = 5
    elif fault == "after_phase": receipt.context["phase"] = "before"
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)
