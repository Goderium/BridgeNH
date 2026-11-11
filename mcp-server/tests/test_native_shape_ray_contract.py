"""Versioned native trace accounting; reconstructed fixtures are not live replay evidence."""

import json
from pathlib import Path

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_navigation as navigation

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction


def sampled_contract(receipt):
    coverage = receipt["coverage"]
    coverage.update(rayCountingVersion=1, maxRaysPerFace=53, maxClientRays=27,
        serverPages=coverage.get("serverPages", 1), maxServerPages=4,
        candidateAttempts=coverage.get("candidateAttempts", coverage["candidatesExamined"]), maxCandidateAttempts=36)
    return receipt


def current_receipt(client, state, monkeypatch, *, sides=(2,), no_candidate=False, typed=True):
    target = navigation.recipes.INPUT
    state["position"] = {"x": target["x"] + .5, "y": target["y"] - 1, "z": target["z"] - 1.5}
    navigation.install_survival_navigation(client, state, monkeypatch, 180)
    task = navigation.make_task("recipe", client, state)
    args = {**target, "dimension": 180, "expectedWorld": "test-world", "intent": "use", "pattern": "cardinal",
        "requireFlying": False, "allowedSides": list(sides)}
    receipt = navigation.planner.composite_receipt(state, args)
    receipt["serverFlight"].update(creative=False, flying=False, onGround=True)
    navigation.survival_snapshot(receipt.context["snapshot"], state, 180)
    navigation.actual_current_receipt(receipt, state)
    receipt["current"]["normalReach"] = 4.5
    if no_candidate:
        navigation.no_current_candidate(receipt)
    if typed:
        sampled_contract(receipt)
    options = dict(intent="use", pattern="cardinal", allowed_sides=sides, hotbar_slot=None,
        target_identity=None, current_only=True, allow_no_candidate=no_candidate)
    return task, receipt, target, options


@pytest.mark.parametrize("sampling_pass", [0, 1])
@pytest.mark.parametrize("fault", [None, "untyped_pass", "missing_pass", "extra_pass", "missing_budget",
    "larger_budget", "extra_attempt", "early_second_pass", "wrong_intent", "ray_limit"])
async def test_attack_sampling_contract_retains_global_caps_and_other_intents(native_construction, sampling_pass, fault):
    client, state = native_construction
    planner = navigation.planner
    task = planner.construction_task(client, state)
    target = {**planner.CONTROLLER, "y": 2}
    state["position"] = {"x": target["x"] + .5, "y": 2, "z": target["z"] - 1.5}
    state["blocks"][planner.key(target)] = {"registryName": "minecraft:stone", "metadata": 0}
    options = dict(intent="attack", pattern="surround", allowed_sides=(2,), hotbar_slot=None, target_identity=None)
    receipt = sampled_contract(planner.composite_receipt(state, {**target, "dimension": 0,
        "expectedWorld": "new-world", "intent": "attack", "pattern": "surround", "allowedSides": [2]}))
    coverage = receipt["coverage"]
    coverage.update(samplingPass=sampling_pass, samplingPasses=2, maxCandidateAttempts=69)
    if sampling_pass:
        coverage.update(candidatesExamined=33, candidateAttempts=34, serverRays=54)
    if fault == "untyped_pass": coverage["samplingPass"] = bool(sampling_pass)
    if fault == "missing_pass": coverage.pop("samplingPass")
    if fault == "extra_pass": coverage["samplingPasses"] = 3
    if fault == "missing_budget": coverage.pop("maxCandidateAttempts")
    if fault == "larger_budget": coverage["maxCandidateAttempts"] = 70
    if fault == "extra_attempt": coverage["candidateAttempts"] = 67 if sampling_pass else coverage["candidatesExamined"]+1
    if fault == "early_second_pass": coverage.update(samplingPass=1, candidatesExamined=2, candidateAttempts=3)
    if fault == "wrong_intent": receipt["intent"] = options["intent"] = "use"
    if fault == "ray_limit": coverage["serverRays"] = 199
    if fault:
        with pytest.raises(BridgeError): task.validate_native_approach(receipt, target, **options)
    else:
        task.validate_native_approach(receipt, target, **options)
    assert not planner.mutation_trace(state)


async def test_reported_freezer_27_trace_absence_requires_typed_contract(native_bridge, monkeypatch):
    client, state = native_bridge
    task, receipt, target, options = current_receipt(client, state, monkeypatch, no_candidate=True, typed=False)
    excerpt = json.loads((Path(__file__).parent / "fixtures" / "cold_freezer_reported_ray_counters.json").read_text(encoding="utf-8"))
    receipt.update(excerpt["result"])
    receipt["current"].update(excerpt["current"])
    receipt["coverage"].update(excerpt["coverage"])
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    sampled_contract(receipt)
    task.validate_native_approach(receipt, target, **options)
    assert receipt["coverage"]["serverRays"] == 27 and receipt["selected"] is None
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("no_candidate,sides,pages,attempts,server_rays,client_rays", [
    (False, (2,), 1, 1, 1, 1), (False, (2,), 1, 1, 2, 2),
    (False, (2,), 1, 1, 27, 27), (False, (2,), 1, 1, 53, 27),
    (False, (2, 3), 1, 1, 106, 27), (False, (2, 3, 4, 5), 1, 1, 198, 27),
    (True, (2,), 1, 1, 27, 0), (True, (2,), 1, 1, 53, 0),
    (True, (2,), 2, 2, 106, 0),
])
async def test_sampled_ray_receipts_accept_only_bounded_versioned_counts(native_bridge, monkeypatch,
        no_candidate, sides, pages, attempts, server_rays, client_rays):
    client, state = native_bridge
    task, receipt, target, options = current_receipt(client, state, monkeypatch, sides=sides, no_candidate=no_candidate)
    receipt["coverage"].update(serverPages=pages, candidateAttempts=attempts, serverRays=server_rays, clientRays=client_rays)
    task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("no_candidate,sides,pages,attempts,server_rays,client_rays", [
    (False, (2,), 1, 1, 54, 1), (False, (2, 3), 1, 1, 107, 1),
    (False, (2, 3, 4, 5), 1, 1, 199, 1), (False, (2,), 1, 1, 2, 28),
    (False, (2,), 1, 1, 2, 0), (False, (2,), 1, 1, True, 1),
    (False, (2,), 1, 1, 2, True), (True, (2,), 1, 1, 54, 0),
    (True, (2,), 2, 2, 107, 0), (True, (2,), 1, 1, 27, 1),
])
async def test_sampled_face_attempt_and_shared_limits_refuse_excess_or_untyped_counts(native_bridge, monkeypatch,
        no_candidate, sides, pages, attempts, server_rays, client_rays):
    client, state = native_bridge
    task, receipt, target, options = current_receipt(client, state, monkeypatch, sides=sides, no_candidate=no_candidate)
    receipt["coverage"].update(serverPages=pages, candidateAttempts=attempts, serverRays=server_rays, clientRays=client_rays)
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("field,bad", [
    ("rayCountingVersion", None), ("rayCountingVersion", True), ("rayCountingVersion", 1.0),
    ("rayCountingVersion", "1"), ("rayCountingVersion", 0), ("rayCountingVersion", 2),
    ("maxRaysPerFace", None), ("maxRaysPerFace", True), ("maxRaysPerFace", 53.0),
    ("maxRaysPerFace", "53"), ("maxRaysPerFace", 27), ("maxRaysPerFace", 52),
    ("maxClientRays", None), ("maxClientRays", True), ("maxClientRays", 27.0), ("maxClientRays", 28),
    ("maxServerRays", 199), ("serverPages", None), ("maxServerPages", 5),
    ("candidateAttempts", True), ("candidateAttempts", 2), ("maxCandidateAttempts", 37),
])
async def test_sampled_contract_requires_exact_types_and_complete_budget_fields(native_bridge, monkeypatch, field, bad):
    client, state = native_bridge
    task, receipt, target, options = current_receipt(client, state, monkeypatch, no_candidate=True)
    receipt["coverage"]["serverRays"] = 27
    if bad is None:
        receipt["coverage"].pop(field)
    else:
        receipt["coverage"][field] = bad
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("server_rays,client_rays,accepted", [(6, 1, True), (7, 1, False), (2, 2, False)])
@pytest.mark.parametrize("continuation", [False, True])
async def test_legacy_receipts_keep_six_server_traces_per_attempt_and_one_client_trace(native_bridge, monkeypatch,
        server_rays, client_rays, accepted, continuation):
    client, state = native_bridge
    task, receipt, target, options = current_receipt(client, state, monkeypatch, typed=False)
    receipt["coverage"].update(serverRays=server_rays, clientRays=client_rays,
        rayCounting="Actual native Access.trace invocations, including shape fallback samples; server total is shared across continuation pages.")
    if not continuation:
        for key in ("serverPages", "maxServerPages", "candidateAttempts", "maxCandidateAttempts"):
            receipt["coverage"].pop(key)
    if accepted:
        task.validate_native_approach(receipt, target, **options)
    else:
        with pytest.raises(BridgeError):
            task.validate_native_approach(receipt, target, **options)


@pytest.mark.parametrize("fault", ["unknown", "partial", "incomplete", "selected", "target", "world", "allowed_side"])
async def test_typed_ray_contract_does_not_override_current_absence_or_selected_proof_guards(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    absent = fault != "allowed_side"
    task, receipt, target, options = current_receipt(client, state, monkeypatch, no_candidate=absent)
    receipt["coverage"].update(serverRays=27, clientRays=0 if absent else 2)
    if fault == "unknown": receipt["coverage"]["unknownChecks"] = 1
    elif fault == "partial": receipt["partial"] = True
    elif fault == "incomplete": receipt["complete"] = False
    elif fault == "selected": receipt["selected"] = {}
    elif fault == "target": receipt["target"]["x"] += 1
    elif fault == "world": receipt.context["snapshot"]["world"].update(worldName="other-world", worldFolder="other-world")
    else: receipt["selected"]["attachment"]["side"] = 4
    with pytest.raises(BridgeError):
        task.validate_native_approach(receipt, target, **options)
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("kind", ["construction", "recipe"])
@pytest.mark.parametrize("fault", [None, "same_sample", "final_ray"])
async def test_versioned_current_absence_searches_once_and_retains_fresh_navigation_ack(native_construction,
        native_bridge, monkeypatch, kind, fault):
    client, state = native_construction if kind == "construction" else native_bridge
    target = navigation.construction.CONTROLLER if kind == "construction" else navigation.recipes.INPUT
    if kind == "construction":
        state["blocks"][navigation.construction.key(target)] = {
            "registryName": navigation.construction.CONTROLLER_ITEM["registryName"],
            "itemMetadata": navigation.construction.CONTROLLER_ITEM["metadata"]}
    navigation.install_survival_navigation(client, state, monkeypatch, 0 if kind == "construction" else 180, fault=fault)
    original = client.call

    async def sampled(operation, args, **kwargs):
        receipt = await original(operation, args, **kwargs)
        if operation == "player.plan_approach":
            sampled_contract(receipt)
            receipt["coverage"].update(serverRays=27 if receipt["status"] == "no_candidate" else 2,
                clientRays=0 if receipt["status"] == "no_candidate" else 2)
        return receipt

    monkeypatch.setattr(client, "call", sampled)
    task = navigation.make_task(kind, client, state)
    if fault is None:
        await navigation.approach(kind, task)
        assert [row.get("currentOnly", False) for row in state["planner_calls"]] == [True, False, True]
        assert task.approach_summary()["knownCurrentAbsences"] == 1
    else:
        with pytest.raises(BridgeError):
            await navigation.approach(kind, task)
    assert len(state["navigation_calls"]) == 1
    assert [row["operation"] for row in navigation.construction.writes(state)] == ["player.navigate"]
