"""Bounded guarded read reobservation; offline fixtures make no live latency claim."""

import copy
from contextlib import contextmanager
import time
from types import SimpleNamespace

import anyio
import pytest

from minecraft_codex_bridge import bridge
from minecraft_codex_bridge import recipe_tasks
from minecraft_codex_bridge.bridge import BridgeError
import test_native_actual_reach_contract as reach
import test_native_approach_client_clock as clock
import test_native_navigation as navigation
import test_native_shape_ray_contract as sampled

native_bridge = navigation.native_bridge
native_construction = navigation.native_construction


@pytest.mark.parametrize("kind", ["recipe", "construction"])
@pytest.mark.parametrize("echo", ["developer_teleport", "walking"])
async def test_creative_composite_binds_resolved_policy_to_native_receipt(
        native_bridge, native_construction, monkeypatch, kind, echo):
    client, state = native_bridge if kind == "recipe" else native_construction

    def strategy_receipt(receipt, _number, _task):
        receipt.update(movementStrategy=echo, movementStrategyVersion=1,
            movementStrategyScope="endpoint_compatibility_only")
        receipt["coverage"]["strategyIncompatibleCandidates"] = 0
        receipt["current"]["clientFlying"] = True
        receipt["serverFlight"].update(creative=True, flying=True, allowFlying=True)
        player = receipt.context["snapshot"]["player"]
        player.update(creative=True, flying=True, allowFlying=True)
        player["serverFlight"].update(creative=True, flying=True, allowFlying=True)

    task, target, options, calls, _ = fixture(client, state, monkeypatch, kind=kind,
        outcomes=("ready",), fault=strategy_receipt)
    task.actual_survival = False
    task.args["movementStrategy"] = "developer_teleport"
    task.approach_health["approachMovementStrategyVersion"] = 1
    if echo == "developer_teleport":
        assert (await task.native_approach(target, **options))["status"] == "ready"
    else:
        with pytest.raises(BridgeError):
            await task.native_approach(target, **options)
    assert len(calls) == 1 and calls[0]["movementStrategy"] == "developer_teleport"
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


def unavailable(receipt, status):
    receipt.update(status=status, selected=None, complete=status == "unknown", partial=status == "partial",
        stopReason="unknown" if status == "unknown" else "tick_budget", nextOffset=None if status == "unknown" else 0)
    receipt["coverage"].update(serverRays=0, clientRays=0, unknownChecks=1,
        serverPages=2, candidateAttempts=2)


def fixture(client, state, monkeypatch, *, kind="recipe", outcomes=("unknown", "ready"), fault=None):
    if kind == "construction":
        task, baseline, target, options = clock.construction_receipt(client, state, monkeypatch)
    else:
        task, baseline, target, options = sampled.current_receipt(client, state, monkeypatch)
        reach.actual_reach(baseline, cap=32, explicit=True)
    options.update(use_normal_reach=True, max_distance=32, allow_no_candidate=True)
    task.approach_health["approachNormalReachVersion"] = 1
    clock.phase_clock(task, baseline)
    calls, contexts = [], []
    original = client.call

    async def observed(operation, args, **kwargs):
        if operation != "player.plan_approach":
            return await original(operation, args, **kwargs)
        calls.append(copy.deepcopy(args))
        contexts.append(copy.deepcopy(bridge._REQUEST_CONTEXT.get()))
        receipt = copy.deepcopy(baseline)
        clock.phase_clock(task, receipt, capture=700 + len(calls), recheck=701 + len(calls), after=701 + len(calls))
        outcome = outcomes[min(len(calls) - 1, len(outcomes) - 1)]
        if isinstance(outcome, BridgeError):
            raise outcome
        if outcome in ("unknown", "partial"):
            unavailable(receipt, outcome)
        elif outcome == "no_candidate":
            navigation.no_current_candidate(receipt)
        elif outcome == "client_face_unverified":
            receipt.update(status=outcome, selected=None, stopReason=outcome)
        if fault is not None:
            fault(receipt, len(calls), task)
        return receipt

    monkeypatch.setattr(client, "call", observed)
    return task, target, options, calls, contexts


@pytest.mark.parametrize("kind", ["recipe", "construction"])
@pytest.mark.parametrize("outcomes", [("ready",), ("unknown", "ready"), ("partial", "ready"),
    ("unknown", "partial", "ready"), ("partial", "unknown", "ready")])
async def test_fresh_current_read_recovers_without_mutation_or_request_change(native_bridge, native_construction,
        monkeypatch, kind, outcomes):
    client, state = native_bridge if kind == "recipe" else native_construction
    task, target, options, calls, contexts = fixture(client, state, monkeypatch, kind=kind, outcomes=outcomes)
    result = await task.native_approach(target, **options)
    assert result["status"] == "ready" and result["selected"]["moveRequired"] is False
    assert len(calls) == len(outcomes) and all(call == calls[0] for call in calls)
    assert all(context == {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
        "expectedWorld": task.args["expectedWorld"], "requireBackground": True} for context in contexts)
    assert bridge._REQUEST_CONTEXT.get() is None
    summary = task.approach_summary()
    assert summary["planningReads"] == len(outcomes) and summary["readRetries"] == len(outcomes) - 1
    assert summary["unusableObservations"] == len(outcomes) - 1
    assert summary["selectedPlans"] == 1 and summary["earlierUnknownChecks"] == len(outcomes) - 1
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("status", ["unknown", "partial"])
async def test_persistent_uncertainty_stops_after_three_actual_reads(native_bridge, monkeypatch, status):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=(status,))
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    assert len(calls) == 3 and all(call == calls[0] for call in calls)
    assert caught.value.details["approachReceipt"]["status"] == status
    assert caught.value.details["approachReadRetry"]["additionalReads"] == 2
    assert caught.value.details["approachReadRetry"]["mutationReplayed"] is False
    assert task.approach_summary()["selectedPlans"] == 0
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("fault", [
    lambda r, n, t: r.update(submitted=True),
    lambda r, n, t: r.update(readOnly=False),
    lambda r, n, t: r.update(outcomeUnknown=True),
    lambda r, n, t: r.update(mutationDispatched=True),
    lambda r, n, t: r.update(sessionId="another-session"),
    lambda r, n, t: r.update(dimension=True),
    lambda r, n, t: r.update(expectedWorld="another-world"),
    lambda r, n, t: r.update(currentOnly=False),
    lambda r, n, t: r.update(stopReason="client_space_unverified"),
    lambda r, n, t: r.update(complete=1),
    lambda r, n, t: r.update(partial=0),
    lambda r, n, t: r.update(nextOffset=0),
    lambda r, n, t: r["coverage"].update(unknownChecks=0),
    lambda r, n, t: r["coverage"].update(serverPages=True),
    lambda r, n, t: r["coverage"].update(candidateAttempts=4),
    lambda r, n, t: r["coverage"].update(clientRays=1),
    lambda r, n, t: r["coverage"].update(workNanosPerPhase=4_000_001),
    lambda r, n, t: r["coverage"].pop("rayCountingVersion"),
    lambda r, n, t: r["current"].pop("serverNormalReach"),
    lambda r, n, t: r["current"].pop("captureClientTick"),
    lambda r, n, t: r["current"].update(captureClientTick=800),
    lambda r, n, t: r["current"].update(selectedHotbarSlot=True),
    lambda r, n, t: r["serverFlight"].update(playerId="foreign-player"),
    lambda r, n, t: r.context.update(phase="before"),
    lambda r, n, t: r.context["snapshot"].update(clientTicks=800),
    lambda r, n, t: r.context["snapshot"]["player"].update(x=99),
    lambda r, n, t: r.context["snapshot"]["player"].update(health=0),
    lambda r, n, t: r.context["snapshot"]["serverControl"].update(playerId="foreign-player"),
])
async def test_malformed_or_foreign_unknown_is_not_reobserved(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch, fault=fault)
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 1 and not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("fault", [
    lambda r, n, t: r["selected"]["serverBody"].update(occupiable=False),
    lambda r, n, t: r["selected"]["clientRay"].update(verified=False),
    lambda r, n, t: r["selected"]["feet"].update(y=88),
    lambda r, n, t: r["selected"].update(unknownChecks=1),
    lambda r, n, t: r["target"].update(x=88),
    lambda r, n, t: r["serverFlight"].update(playerId="foreign-player"),
    lambda r, n, t: r.context["snapshot"].update(clientTicks=88),
])
async def test_second_receipt_must_pass_original_ready_proof(native_bridge, monkeypatch, fault):
    client, state = native_bridge

    def second(receipt, number, task):
        if number == 2:
            fault(receipt, number, task)

    task, target, options, calls, _ = fixture(client, state, monkeypatch, fault=second)
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 2 and not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("outcome", [BridgeError("stale_world", "stale native getter"),
    BridgeError("connection_lost", "unknown completion", outcome_unknown=True), "client_face_unverified"])
async def test_rpc_errors_and_native_refusals_do_not_retry(native_bridge, monkeypatch, outcome):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=(outcome, "ready"))
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 1 and not state["navigation_calls"] and not navigation.recipes.writes(state)


@pytest.mark.parametrize("first", ["ready", "unknown"])
async def test_known_absence_remains_a_real_absence(native_bridge, monkeypatch, first):
    client, state = native_bridge
    outcomes = ("no_candidate",) if first == "ready" else ("unknown", "no_candidate")
    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=outcomes)
    result = await task.native_approach(target, **options)
    assert result["status"] == "no_candidate" and result["coverage"]["unknownChecks"] == 0
    assert len(calls) == len(outcomes) and not navigation.recipes.writes(state)


@pytest.mark.parametrize("after_attempt", [1, 2])
async def test_original_task_deadline_prevents_another_read(native_bridge, monkeypatch, after_attempt):
    client, state = native_bridge

    def expire(receipt, number, task):
        if number == after_attempt:
            task.deadline = time.monotonic() - .001

    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=("unknown",), fault=expire)
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == after_attempt and not navigation.recipes.writes(state)


@pytest.mark.parametrize("start,expected", [(61, 3), (62, 2), (63, 1)])
async def test_frontier_shared_budget_counts_every_retry(native_construction, monkeypatch, start, expected):
    client, state = native_construction
    task, target, options, calls, _ = fixture(client, state, monkeypatch, kind="construction", outcomes=("unknown",))
    task.survival_frontier_native_reads = start
    original = task.call

    async def bounded(operation, arguments):
        assert task.survival_frontier_native_reads < 64
        task.survival_frontier_native_reads += 1
        return await original(operation, arguments)

    task.call = bounded
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == expected and task.survival_frontier_native_reads == 64
    assert not navigation.construction.writes(state)


@pytest.mark.parametrize("stop,server,client", [("tick_budget", 0, 0), ("ray_limit", 198, 0), ("ray_limit", 2, 27)])
async def test_typed_budget_stop_can_reobserve_at_same_current_request(native_bridge, monkeypatch, stop, server, client):
    client_api, state = native_bridge

    def budget(receipt, number, task):
        if number == 1:
            receipt["stopReason"] = stop
            receipt["coverage"].update(serverRays=server, clientRays=client)
            if server == 198:
                receipt["coverage"].update(serverPages=4, candidateAttempts=4)

    task, target, options, calls, _ = fixture(client_api, state, monkeypatch, outcomes=("partial", "ready"), fault=budget)
    result = await task.native_approach(target, **options)
    assert result["status"] == "ready" and len(calls) == 2 and not navigation.recipes.writes(state)


@pytest.mark.parametrize("fault", [
    lambda r, n, t: r.update(stopReason="unknown"),
    lambda r, n, t: r.update(stopReason="ray_limit"),
    lambda r, n, t: r.update(nextOffset=True),
    lambda r, n, t: r.update(nextOffset=1),
    lambda r, n, t: r.update(complete=True),
    lambda r, n, t: r.update(partial=1),
    lambda r, n, t: r["coverage"].update(clientRays=27),
])
async def test_malformed_budget_stop_is_not_reobserved(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=("partial", "ready"), fault=fault)
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 1 and not navigation.recipes.writes(state)


@pytest.mark.parametrize("outcome", [BridgeError("stale_world", "changed native world"),
    BridgeError("connection_lost", "unknown read completion", outcome_unknown=True)])
async def test_new_rpc_error_stops_and_retains_first_failed_observation(native_bridge, monkeypatch, outcome):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=("unknown", outcome, "ready"))
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    assert len(calls) == 2 and caught.value is outcome
    assert caught.value.details["approachReadRetry"]["observations"][0]["status"] == "unknown"
    assert not navigation.recipes.writes(state)


async def test_retry_retains_explicit_strategy_target_identity_sides_and_cap(native_bridge, monkeypatch):
    client, state = native_bridge

    def echo(receipt, number, task):
        receipt.update(movementStrategy="walking", movementStrategyVersion=1, movementStrategyScope="endpoint_compatibility_only")
        receipt["coverage"]["strategyIncompatibleCandidates"] = 0

    task, target, options, calls, _ = fixture(client, state, monkeypatch, fault=echo)
    task.approach_health["approachMovementStrategyVersion"] = 1
    options.update(movement_strategy="walking", allowed_sides=(2, 3), max_distance=4.5,
        target_identity={"registryName": "gregtech:gt.blockmachines", "metaTileId": 77, "metaClass": "example.PublicMultiblock"})
    result = await task.native_approach(target, **options)
    assert result["status"] == "ready" and len(calls) == 2 and calls[0] == calls[1]
    assert calls[0]["movementStrategy"] == "walking" and calls[0]["allowedSides"] == [2, 3]
    assert calls[0]["maxDistance"] == 4.5 and calls[0]["targetIdentity"] == options["target_identity"]
    assert not state["navigation_calls"] and not navigation.recipes.writes(state)


async def test_non_current_search_does_not_gain_retries(native_bridge, monkeypatch):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch)
    options["current_only"] = False
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 1 and not navigation.recipes.writes(state)


@pytest.mark.parametrize("slot", [None, True, 6])
async def test_unknown_placement_must_retain_exact_source_slot(native_construction, monkeypatch, slot):
    client, state = native_construction

    def change_slot(receipt, number, task):
        if slot is None:
            receipt["current"].pop("requestedHotbarSlot")
        else:
            receipt["current"]["requestedHotbarSlot"] = slot

    task, target, options, calls, _ = fixture(client, state, monkeypatch, kind="construction", fault=change_slot)
    with pytest.raises(BridgeError):
        await task.native_approach(target, **options)
    assert len(calls) == 1 and not navigation.construction.writes(state)


def manual_clock(task, monkeypatch):
    current = [task.started + 1]
    monkeypatch.setattr(recipe_tasks, "time", SimpleNamespace(monotonic=lambda: current[0]))
    return current


@pytest.mark.parametrize("elapsed,accepted", [(.249, True), (.250, False), (.251, False)])
async def test_late_ready_is_not_accepted_at_or_after_retry_window(native_bridge, monkeypatch, elapsed, accepted):
    client, state = native_bridge
    current = []

    def elapsed_read(receipt, number, task):
        if number == 2:
            current[0] += elapsed

    task, target, options, calls, _ = fixture(client, state, monkeypatch, fault=elapsed_read)
    current = manual_clock(task, monkeypatch)
    original_deadline = task.deadline
    if accepted:
        assert (await task.native_approach(target, **options))["status"] == "ready"
    else:
        with pytest.raises(BridgeError) as caught:
            await task.native_approach(target, **options)
        assert caught.value.details["approachReceipt"]["status"] == "unknown"
        assert caught.value.details["approachReadRetry"]["windowExpired"] is True
        assert task.approach_summary()["selectedPlans"] == 0
        assert task.approach_summary()["planningReads"] == 2
        assert task.approach_observations[-1]["status"] == "expired_observation"
    assert len(calls) == 2 and task.deadline == original_deadline and not navigation.recipes.writes(state)


@pytest.mark.parametrize("phase", ["eligibility_validation", "new_context", "ready_validation"])
async def test_context_and_validation_work_count_inside_retry_window(native_bridge, monkeypatch, phase):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch)
    current = manual_clock(task, monkeypatch)
    original_validate = task.validate_native_approach

    def slow_validate(*args, **kwargs):
        if phase == "eligibility_validation" and kwargs.get("_read_retry_observation") is True and len(calls) == 1:
            current[0] += .250
        if phase == "ready_validation" and len(calls) == 2:
            current[0] += .250
        return original_validate(*args, **kwargs)

    monkeypatch.setattr(task, "validate_native_approach", slow_validate)
    original_context, entries = recipe_tasks.request_context, []

    @contextmanager
    def slow_context(value):
        entries.append(value)
        with original_context(value):
            if phase == "new_context" and len(entries) == 2:
                current[0] += .250
            yield

    monkeypatch.setattr(recipe_tasks, "request_context", slow_context)
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    expected = 2 if phase == "ready_validation" else 1
    assert len(calls) == expected and caught.value.details["approachReceipt"]["status"] == "unknown"
    assert caught.value.details["approachReadRetry"]["additionalReads"] == expected - 1
    assert caught.value.details["approachReadRetry"]["windowExpired"] is True
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("expired_on", [2, 3])
async def test_elapsed_unknown_preserves_last_typed_refusal_and_never_starts_later_read(native_bridge, monkeypatch, expired_on):
    client, state = native_bridge
    current = []

    def elapsed_read(receipt, number, task):
        if number > 1:
            current[0] += .125 if expired_on == 3 else .250
            receipt["coverage"]["unknownChecks"] = number

    task, target, options, calls, _ = fixture(client, state, monkeypatch, outcomes=("unknown",), fault=elapsed_read)
    current = manual_clock(task, monkeypatch)
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    assert len(calls) == expired_on
    assert caught.value.details["approachReceipt"]["coverage"]["unknownChecks"] == expired_on
    assert caught.value.details["approachReadRetry"]["additionalReads"] == expired_on - 1
    assert not navigation.recipes.writes(state)


@pytest.mark.parametrize("task_remaining", [.025, .250, .500])
async def test_rpc_scope_uses_smaller_task_or_retry_budget_without_extending_deadline(native_bridge, monkeypatch, task_remaining):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch)
    current = manual_clock(task, monkeypatch)
    task.deadline = current[0] + task_remaining
    scopes = []
    original_anyio = recipe_tasks.anyio

    def scoped(seconds):
        scopes.append(seconds)
        return original_anyio.fail_after(seconds)

    monkeypatch.setattr(recipe_tasks, "anyio", SimpleNamespace(fail_after=scoped))
    original_deadline = task.deadline
    assert (await task.native_approach(target, **options))["status"] == "ready"
    assert len(calls) == 2 and scopes[2] <= min(task_remaining, .250) + 1e-9
    assert scopes[2] > 0 and task.deadline == original_deadline


@pytest.mark.parametrize("elapsed", [.025, .026])
async def test_original_task_deadline_can_expire_before_retry_window(native_bridge, monkeypatch, elapsed):
    client, state = native_bridge
    current = []

    def elapsed_read(receipt, number, task):
        if number == 2:
            current[0] += elapsed

    task, target, options, calls, _ = fixture(client, state, monkeypatch, fault=elapsed_read)
    current = manual_clock(task, monkeypatch)
    task.deadline = current[0] + .025
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    assert len(calls) == 2 and caught.value.details["approachReceipt"]["status"] == "unknown"
    assert caught.value.details["approachReadRetry"]["windowExpired"] is True


async def test_cooperative_rpc_timeout_stops_after_first_failed_receipt(native_bridge, monkeypatch):
    client, state = native_bridge
    task, target, options, calls, _ = fixture(client, state, monkeypatch)
    original, starts = client.call, []

    async def slow_read(operation, args, **kwargs):
        if operation == "player.plan_approach":
            starts.append(operation)
            if len(starts) == 2:
                await anyio.sleep(.400)
        return await original(operation, args, **kwargs)

    monkeypatch.setattr(client, "call", slow_read)
    with pytest.raises(BridgeError) as caught:
        await task.native_approach(target, **options)
    assert len(starts) == 2 and len(calls) == 1
    assert caught.value.details["approachReceipt"]["status"] == "unknown"
    assert caught.value.details["approachReadRetry"]["additionalReads"] == 1
    assert caught.value.details["approachReadRetry"]["windowExpired"] is True
    assert not navigation.recipes.writes(state)
