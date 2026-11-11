"""Plan shared normal-reach stands before any optional native relocation; no persisted plans."""

from __future__ import annotations

import time
import math
from typing import Any

import anyio

from .bridge import BridgeError, request_context, response_context
from .recipe_tasks import _RecipeTask
from .workflows import _guard, _WorkflowDiscovery
from .gui_acceptance import phantom_items_confirmed, wait_failure


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise BridgeError("APPROACH_RECEIPT_INVALID", message, outcome_unknown=False)


def _point(value, *, feet=False):
    _require(isinstance(value, dict), "Actual finite XYZ pose is required.")
    fields = ("x", "feetY" if feet else "y", "z")
    _require(all(type(value.get(key)) in (int, float) and math.isfinite(value[key]) for key in fields),
             "Actual finite XYZ pose is required.")
    return tuple(value[key] for key in fields)


def _validate_plan(proof, targets, baseline, actor, strategy, *, current_only, budget_observation=False):
    _require(type(proof.get("sharedInteractionVersion")) is int and proof["sharedInteractionVersion"] == 1,
             "Shared target version must be explicitly advertised.")
    _require(proof.get("readOnly") is True and proof.get("submitted") is False
             and (proof.get("partial") is True if budget_observation else proof.get("partial") is False),
             "Only complete read-only endpoint proofs can be used.")
    _require(proof.get("movementStrategy") == strategy and proof.get("useNormalReach") is True,
             "The endpoint must use the requested movement strategy and real normal reach.")
    current, coverage = proof.get("current"), proof.get("coverage")
    _require(isinstance(current, dict) and isinstance(coverage, dict), "Endpoint provenance is missing.")
    _require(all(abs(a - b) <= .0001 for a, b in zip(_point(current, feet=True), baseline))
             and current.get("reachPlayerId") == actor, "Endpoint proof belongs to a different actor or pose.")
    identities = proof.get("targets")
    _require(type(proof.get("targetCount")) is int and proof["targetCount"] == len(targets)
             and isinstance(identities, list) and len(identities) == len(targets), "The exact target set is required.")
    for target, identity in zip(targets, identities):
        _require(isinstance(identity, dict) and all(identity.get(k) == target[k] for k in ("x", "y", "z")),
                 "Endpoint proof changed an interaction target.")
    if budget_observation:
        # Match the existing actual-current read policy: never retry a search,
        # ray exhaustion, action, changed actor or an unbounded observation.
        _require(current_only and proof.get("currentOnly") is True
                 and proof.get("status") == "partial" and proof.get("stopReason") == "tick_budget"
                 and proof.get("complete") is False and proof.get("selected") is None,
                 "Only an unfinished current-position tick-budget read can be observed again.")
        _require(type(coverage.get("candidateSlots")) is int and coverage["candidateSlots"] == 1
                 and type(coverage.get("candidatesExamined")) is int and 0 <= coverage["candidatesExamined"] <= 1
                 and type(coverage.get("unknownChecks")) is int and coverage["unknownChecks"] >= 0,
                 "Current-position read coverage must stay explicitly bounded.")
        return
    if proof.get("status") == "no_candidate":
        slots = coverage.get("candidateSlots")
        expected_slots = 1 if current_only else 34
        if "sharedCandidateVersion" in proof:
            _require(type(proof["sharedCandidateVersion"]) is int and proof["sharedCandidateVersion"] in (1, 2, 3),
                     "Shared candidate coverage requires an explicit supported integer version.")
        if not current_only and proof.get("sharedCandidateVersion") in (2, 3):
            jump, drop = proof.get("walkingMaxJumpBlocks"), proof.get("walkingMaxDropBlocks")
            _require(type(jump) is int and 0 <= jump <= 1 and type(drop) is int and 0 <= drop <= 3,
                     "Client navigation limits must be explicitly bounded.")
            heights = 5 if proof.get("sharedCandidateVersion") == 3 else 1
            if proof.get("sharedCandidateVersion") == 3:
                _require(type(proof.get("nonWalkingCandidateHeights")) is int and proof["nonWalkingCandidateHeights"] == 5,
                         "Versioned nonwalking vertical candidate coverage is required.")
            expected_slots = 1 + 33 * (1 + jump + drop if strategy == "walking" else heights)
        _require(proof.get("complete") is True and type(slots) is int and slots == expected_slots
                 and coverage.get("candidatesExamined") == slots and coverage.get("unknownChecks") == 0,
                 "Only complete known bounded absence can justify splitting target groups.")
        return
    _require(proof.get("status") == "ready", "Endpoint proof is partial, unknown or stale.")
    selected = proof.get("selected")
    _require(isinstance(selected, dict), "Ready endpoint needs its selected pose.")
    feet = _point(selected.get("feet"))
    moved = any(abs(a - b) > .0001 for a, b in zip(feet, baseline))
    _require(type(selected.get("moveRequired")) is bool and selected["moveRequired"] == moved
             and not (current_only and moved), "Endpoint movement claim contradicts its actual position.")
    for side in ("clientBody", "serverBody"):
        body = selected.get(side)
        _require(isinstance(body, dict) and body.get("loaded") is True and body.get("status") == "observed"
                 and body.get("occupiable") is True and body.get("entityCoveragePartial") is False
                 and body.get("hazard") is False and body.get("liquid") is False
                 and (strategy != "walking" or body.get("canStand") is True and (body.get("fullSupportCoverage") is True
                     or type(body.get("supportContactVersion")) is int and body["supportContactVersion"] == 1
                     and body.get("continuousSupportCoverage") is True)), "A complete safe endpoint body is required.")
    views = selected.get("targetViews")
    _require(isinstance(views, list) and len(views) == len(targets), "Every target needs its precomputed view.")
    for index, view in enumerate(views):
        _require(isinstance(view, dict) and view.get("targetIndex") == index
                 and type(view.get("side")) is int and view["side"] in targets[index].get("allowedSides", range(6)),
                 "Target view is outside the explicitly allowed native faces.")
        _point(view.get("hit"))
        if "hitPoint" in targets[index]:
            _require(view.get("hitPoint") == targets[index]["hitPoint"] and all(
                abs(view["hit"][axis] - targets[index][axis] - targets[index]["hitPoint"][axis]) <= .02
                for axis in ("x", "y", "z")), "Native endpoint must preserve the declared local hit point.")
        if targets[index].get("wrenchFacing") is True:
            _require(view.get("wrenchFacing") is True, "Native endpoint evidence must retain the requested wrench-centre constraint.")
            normal = 1 if view["side"] < 2 else 2 if view["side"] < 4 else 0
            _require(all(.3 <= view["hit"][axis] - targets[index][axis] <= .7
                         for ordinal, axis in enumerate(("x", "y", "z")) if ordinal != normal),
                     "Wrench endpoints need the native face-centre region, not an arbitrary visible edge.")
        expected_part = targets[index].get("targetIdentity", {}).get("part")
        if expected_part is not None:
            _require(view.get("targetPart") == expected_part, "Native target views must bind the exact mounted part independently of the hit face.")
        aim = view.get("aim")
        _require(isinstance(aim, dict) and all(type(aim.get(k)) in (int, float) and math.isfinite(aim[k]) for k in ("yaw", "pitch")),
                 "Every target needs finite precomputed yaw and pitch.")
        for owner, source in (("clientRay", "client"), ("serverRay", "integrated_server")):
            ray = view.get(owner)
            _require(isinstance(ray, dict) and ray.get("verified") is True and ray.get("source") == source
                     and ray.get("side") == view["side"], "Both native owners must prove the selected face.")


async def run_interaction_approach(client, arguments: dict[str, Any], lock, *, control, excluded_build_envelope=None,
                                   _discovery=None, _close_gui=False) -> dict[str, Any]:
    started = time.monotonic()
    progress = {"movementDispatched": False, "interactionDispatched": False, "plansPersisted": False, "phases": []}
    try:
        return await _run_interaction_approach(client, arguments, lock, control=control, progress=progress,
                                               excluded_build_envelope=excluded_build_envelope,
                                               _discovery=_discovery, _close_gui=_close_gui)
    except BridgeError as error:
        if progress["movementDispatched"] or progress["interactionDispatched"] or progress.get("guiCloseDispatched"):
            progress["recoveryObservation"] = await _recovery_observation(client, arguments)
        progress.update(status="failed", elapsedMs=round((time.monotonic() - started) * 1000, 3), errorCode=error.code)
        raise BridgeError(error.code, error.message, **{**error.details, "receipt": progress, "replayAllowed": False}) from None
    except TimeoutError:
        if progress["movementDispatched"] or progress["interactionDispatched"] or progress.get("guiCloseDispatched"):
            progress["recoveryObservation"] = await _recovery_observation(client, arguments)
        progress.update(status="failed", elapsedMs=round((time.monotonic() - started) * 1000, 3), errorCode="APPROACH_TIMEOUT")
        raise BridgeError("APPROACH_TIMEOUT", "Shared approach deadline expired; no automatic replay.",
                          outcome_unknown=progress["movementDispatched"] or progress["interactionDispatched"] or bool(progress.get("guiCloseDispatched")),
                          receipt=progress, replayAllowed=False) from None


async def _recovery_observation(client, arguments: dict[str, Any]) -> dict[str, Any]:
    """One bounded fresh read after native cleanup, never a retry, cancellation or release of a newer owner."""
    result = {"available": False, "source": "fresh_client_snapshot_after_failure", "automaticReplayAllowed": False}
    try:
        with anyio.move_on_after(.75, shield=True) as deadline:
            with request_context(None):
                current = await client.call("client.snapshot", {})
            world = current.get("world", {})
            if (current.get("sessionId") != arguments["expectedSessionId"] or not isinstance(world, dict)
                    or arguments["expectedWorld"] not in (world.get("worldName"), world.get("worldFolder"))
                    or world.get("dimension") != arguments["dimension"]):
                return {**result, "reason": "session_world_or_dimension_changed"}
            result.update(available=True, observedAtMillis=int(time.time() * 1000),
                          **{key: current[key] for key in ("sessionId", "profile", "world", "player", "serverControl",
                              "inputTask", "inputKeys", "inputTicksRemaining", "inputDiagnostics", "target") if key in current})
            task, diagnostics = current.get("inputTask"), current.get("inputDiagnostics")
            result["bridgeOwnedKeysReleased"] = (
                task.get("status") == "idle" and task.get("ownedKeyCount") == 0
                and diagnostics.get("logicalOwnedKeyCount") == 0 and current.get("inputKeys") == []
            ) if isinstance(task, dict) and isinstance(diagnostics, dict) else None
            result["physicalInputReleaseClaimed"] = False
        if deadline.cancel_called:
            return {**result, "available": False, "reason": "recovery_read_deadline"}
        return result
    except Exception as failure:
        # Recovery is best-effort evidence and must never replace the original
        # movement/use error. Cancellation (BaseException) still propagates.
        return {**result, "reason": getattr(failure, "code", "recovery_read_unavailable"),
                "errorType": type(failure).__name__}


async def _run_interaction_approach(client, arguments: dict[str, Any], lock, *, control, progress, excluded_build_envelope=None,
                                    _discovery=None, _close_gui=False) -> dict[str, Any]:
    started = time.monotonic()
    session, world, dimension = arguments["expectedSessionId"], arguments["expectedWorld"], arguments["dimension"]
    strategy = arguments.get("strategy", "walking")
    move = arguments.get("move", True)
    interact = arguments.get("interact", False)
    open_gui = arguments.get("openGui")
    if interact and (not move or len(arguments["targets"]) != 1):
        raise BridgeError("INVALID_ARGUMENTS", "interact=true requires move=true and exactly one target; no action dispatched.", outcome_unknown=False)
    if open_gui is not None and not interact:
        raise BridgeError("INVALID_ARGUMENTS", "openGui requires one combined normal use; no action dispatched.", outcome_unknown=False)
    protected = _discovery is not None
    context = {"version": 1, "expectedSessionId": session, "expectedProfile": "developer",
               "expectedWorld": world, "requireBackground": protected}
    calls = 0
    phases = progress["phases"]
    async def snapshot_now():
        if _discovery is None:
            return await client.invoke("client.snapshot", {})
        credentials = client.configuration.read()
        _discovery.bind(client, credentials, session)
        # Discovery already proved this read capability. Fetch actual current state
        # directly, as run_workflow does, without another health before the snapshot.
        if "client.snapshot" not in _discovery.capabilities:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "Approach requires current client snapshots.", outcome_unknown=False)
        return await client.call("client.snapshot", {}, expected_credentials=credentials)
    async with lock:
        with anyio.fail_after(arguments.get("timeout_ms", 10000) / 1000):
            if _discovery is None:
                health = await client.invoke("bridge.health", {})
            elif type(_discovery) is _WorkflowDiscovery:
                health = _discovery.bind(client, client.configuration.read(), session)
            else:
                raise BridgeError("SESSION_CHANGED", "Approach discovery must be bound to the current task and client.", outcome_unknown=False)
            if excluded_build_envelope is not None:
                if type(health.get("interactionBuildEnvelopeVersion")) is not int or health["interactionBuildEnvelopeVersion"] != 1:
                    raise BridgeError("PLACEMENT_APPROACH_UNAVAILABLE", "Installation needs native prospective destination exclusion before movement.", outcome_unknown=False)
                progress["excludedBuildEnvelope"] = excluded_build_envelope
            if health.get("sessionId") != session or type(health.get("approachSharedTargetsVersion")) is not int or health["approachSharedTargetsVersion"] != 1:
                raise BridgeError("SHARED_APPROACH_UNAVAILABLE", "Shared approach needs the exact current session and updated mod.", outcome_unknown=False)
            if interact:
                if any(target.get("wrenchFacing") is True for target in arguments["targets"]):
                    raise BridgeError("WRENCH_APPROACH_MOVEMENT_ONLY", "Use the separately checked held-tool wrench action after this movement-only endpoint goal.", outcome_unknown=False)
                capabilities = health.get("capabilities")
                supported = "player.interact" in capabilities if isinstance(capabilities, list) else (
                    isinstance(capabilities, dict) and capabilities.get("player.interact") is True)
                if not supported or health.get("interactionBlockTargeting") is not True or health.get("interactionViewBinding") is not True:
                    raise BridgeError("INTERACTION_UNAVAILABLE", "Combined approach requires advertised normal explicit block use and view binding before any movement.", outcome_unknown=False)
            if any("hitPoint" in target for target in arguments["targets"]) and (
                type(health.get("interactionHitPointVersion")) is not int or health["interactionHitPointVersion"] != 1
            ):
                raise BridgeError("INTERACTION_UNAVAILABLE", "Local hit points require advertised shared native support before movement.", outcome_unknown=False)
            if any(target.get("wrenchFacing") is True for target in arguments["targets"]) and (
                type(health.get("approachWrenchFacingVersion")) is not int or health["approachWrenchFacingVersion"] != 1
            ):
                raise BridgeError("WRENCH_APPROACH_UNAVAILABLE", "Face-centre movement needs the explicit native wrench endpoint capability before movement.", outcome_unknown=False)
            if any("part" in target.get("targetIdentity", {}) for target in arguments["targets"]) and (
                type(health.get("interactionPartTargetingVersion")) is not int or health["interactionPartTargetingVersion"] != 1
            ):
                raise BridgeError("INTERACTION_UNAVAILABLE", "Exact mounted parts require advertised native part selection before movement.", outcome_unknown=False)
            if open_gui is not None and (type(health.get("guiNativeWaitVersion")) is not int or health["guiNativeWaitVersion"] != 1):
                raise BridgeError("GUI_WAIT_UNAVAILABLE", "Combined GUI opening requires advertised native container synchronization before movement.", outcome_unknown=False)
            snapshot = await snapshot_now()
            _guard(snapshot, session, "developer", world, protected)
            _RecipeTask.require_live_player(snapshot)
            if snapshot.get("world", {}).get("dimension") != dimension:
                raise BridgeError("DIMENSION_CHANGED", "Shared approach uses only the actual current dimension.", outcome_unknown=False)
            if _close_gui:
                _require(isinstance(snapshot.get("screen"), str), "Conditional close needs the current observed screen.")
            if _close_gui and snapshot["screen"] != "":
                # Use the fresh entry observation, never a screen cache from a prior part.
                # Native control still checks current protection and the exact world before close.
                progress["guiCloseDispatched"] = True
                try:
                    with request_context({**context, "requireBackground": True}):
                        await control(client, "gui.close", {})
                except BridgeError as error:
                    if error.details.get("original_action_executed") is False:
                        progress["guiCloseDispatched"] = False
                    raise
                snapshot = await snapshot_now()
                _guard(snapshot, session, "developer", world, True)
                _RecipeTask.require_live_player(snapshot)
                if snapshot.get("world", {}).get("dimension") != dimension or snapshot.get("screen") != "":
                    raise BridgeError("GUI_CLOSE_UNCONFIRMED", "Current GUI did not close in the expected dimension; no movement dispatched.", outcome_unknown=False)
            player = snapshot["player"]
            # Match native PlayerNavigation.State: TP preserves an already airborne
            # dual-side flight posture. Grounded TP still needs a supported endpoint.
            server_pose = snapshot.get("serverControl", {}).get("pose", {})
            require_flying = strategy == "ability_flight" or strategy == "developer_teleport" and all(
                pose.get("flying") is True and pose.get("onGround") is False for pose in (player, server_pose))
            if open_gui is not None and snapshot.get("screen") != "":
                raise BridgeError("GUI_ALREADY_OPEN", "Close the observed current GUI before requesting a distinct GUI-open goal; no use dispatched.", outcome_unknown=False)
            baseline = _point(player, feet=True)
            actor = snapshot.get("serverControl", {}).get("playerId")
            _require(isinstance(actor, str) and actor != "", "A current authoritative player identity is required.")

            async def observe_once(targets, *, current_only=False, stable_start=True):
                nonlocal calls
                calls += 1
                if calls > 16:
                    raise BridgeError("APPROACH_BUDGET", "Bounded shared planning exhausted its request budget; no further movement.", outcome_unknown=False)
                first = targets[0]
                args = {"intent": "use", "pattern": "surround", "expectedWorld": world, "dimension": dimension,
                        "x": first["x"], "y": first["y"], "z": first["z"], "targets": targets,
                        "useNormalReach": True, "maxDistance": 32, "currentOnly": current_only,
                        "requireFlying": require_flying, "movementStrategy": strategy}
                for key in ("allowedSides", "targetIdentity", "wrenchFacing", "hitPoint"):
                    if key in first:
                        args[key] = first[key]
                if excluded_build_envelope is not None:
                    args["excludedBuildEnvelope"] = excluded_build_envelope
                began = time.monotonic()
                with request_context(context):
                    result = await client.invoke("player.plan_approach", args)
                evidence = response_context(result)
                after = evidence.get("snapshot") if isinstance(evidence, dict) and evidence.get("phase") == "after" else None
                if not isinstance(after, dict):
                    raise BridgeError("APPROACH_GUARD_MISSING", "Shared endpoint proof needs its fresh AFTER context.", outcome_unknown=False)
                _guard(after, session, "developer", world, protected)
                _RecipeTask.require_live_player(after)
                current = after["player"]
                actual = _point(current, feet=True)
                if after.get("world", {}).get("dimension") != dimension or after.get("serverControl", {}).get("playerId") != actor or stable_start and any(
                        abs(actual - expected) > .0001 for actual, expected in zip(
                            actual, baseline)):
                    raise BridgeError("APPROACH_ACTOR_CHANGED", "Actual actor moved during read-only shared planning; no replay.", outcome_unknown=False)
                if result.get("sessionId") != session or result.get("dimension") != dimension or result.get("sharedInteractionVersion") != 1:
                    raise BridgeError("APPROACH_RECEIPT_INVALID", "Shared target receipt is not bound to the current actor.", outcome_unknown=False)
                phases.append({"phase": "current_recheck" if current_only else "shared_stand_plan", "targets": len(targets),
                               "elapsedMs": round((time.monotonic() - began) * 1000, 3), "status": result.get("status")})
                if excluded_build_envelope is not None:
                    _require(result.get("buildEnvelopeVersion") == 1 and result.get("excludedBuildEnvelope") == excluded_build_envelope,
                             "Native approach must retain the exact prospective destination exclusion.")
                try:
                    _validate_plan(result, targets, actual, actor, strategy, current_only=current_only)
                except BridgeError as error:
                    retryable = False
                    if current_only:
                        try:
                            _validate_plan(result, targets, actual, actor, strategy,
                                           current_only=True, budget_observation=True)
                        except BridgeError:
                            pass
                        else:
                            retryable = True
                    raise BridgeError(error.code, error.message, **{**error.details,
                                      "planning": {key: result.get(key) for key in
                                                   ("status", "stopReason", "partial", "complete", "coverage")},
                                      "readRetryable": retryable, "observedFeet": actual,
                                      "receipt": progress, "replayAllowed": False}) from None
                selected = result.get("selected")
                if result.get("status") == "ready":
                    views = selected.get("targetViews") if isinstance(selected, dict) else None
                    if not isinstance(views, list) or len(views) != len(targets) or any(
                            view.get("targetIndex") != index or view.get("clientRay", {}).get("verified") is not True
                            or view.get("serverRay", {}).get("verified") is not True for index, view in enumerate(views)):
                        raise BridgeError("APPROACH_VIEWS_INCOMPLETE", "Every shared target needs complete native client/server face proofs.", outcome_unknown=False)
                    return result, after
                if result.get("status") == "no_candidate" and result.get("complete") is True and result.get("partial") is False \
                        and result.get("coverage", {}).get("unknownChecks") == 0:
                    return result, after
                raise BridgeError("APPROACH_UNVERIFIED", "Unknown, partial or stale shared planning cannot justify extra relocation.",
                                  outcome_unknown=False, planning_status=result.get("status"))

            async def observe(targets, *, current_only=False, stable_start=True):
                deadline, refusal, fixed_pose = None, None, None
                for attempt in range(3):
                    if deadline is not None and time.monotonic() >= deadline:
                        refusal.details["approachReadRetry"]["windowExpired"] = True
                        raise refusal
                    try:
                        if refusal is not None:
                            refusal.details["approachReadRetry"]["additionalReads"] = attempt
                        with anyio.fail_after(max(.001, deadline - time.monotonic()) if deadline else None):
                            proof, after = await observe_once(targets, current_only=current_only, stable_start=stable_start)
                        if fixed_pose is not None:
                            _require(all(abs(a - b) <= .0001 for a, b in zip(_point(after["player"], feet=True), fixed_pose)),
                                     "Actual pose changed between read-only current-position observations.")
                        if deadline is not None:
                            if time.monotonic() >= deadline:
                                refusal.details["approachReadRetry"]["windowExpired"] = True
                                raise refusal
                            progress["approachReadRetry"] = {**refusal.details["approachReadRetry"], "additionalReads": attempt}
                        return proof, after
                    except TimeoutError:
                        if refusal is None:
                            raise
                        refusal.details["approachReadRetry"]["windowExpired"] = True
                        raise refusal from None
                    except BridgeError as error:
                        if error.details.get("readRetryable") is not True:
                            raise
                        pose = error.details["observedFeet"]
                        _require(fixed_pose is None or all(abs(a - b) <= .0001 for a, b in zip(pose, fixed_pose)),
                                 "Actual pose changed during the bounded read-only retry window.")
                        fixed_pose = pose
                        if deadline is None:
                            deadline = min(started + arguments.get("timeout_ms", 10000) / 1000, time.monotonic() + .250)
                        refusal = error
                        refusal.details["approachReadRetry"] = {"additionalReads": attempt, "limit": 2,
                            "maxWindowMillis": 250, "mutationReplayed": False, "windowExpired": time.monotonic() >= deadline}
                        if attempt == 2 or calls >= 16 or time.monotonic() >= deadline:
                            raise

            groups = []
            async def partition(targets, indices):
                proof, after = await observe(targets)
                if proof["status"] == "ready":
                    groups.append({"targetIndices": indices, "targets": targets, "selected": proof["selected"],
                                   "targetMidpoint": proof["targetMidpoint"], "coverage": proof["coverage"]})
                    return after
                if len(targets) == 1:
                    raise BridgeError("NO_INTERACTION_STAND", "No verified bounded stand for an interaction target; no movement dispatched.", outcome_unknown=False,
                                      planning={key: proof.get(key) for key in ("status", "stopReason", "partial", "complete", "coverage", "current", "targets")},
                                      absenceScope="bounded_candidates_only_not_global_unreachability")
                split = len(targets) // 2
                await partition(targets[:split], indices[:split])
                return await partition(targets[split:], indices[split:])

            targets = arguments["targets"]
            target_distance = max(math.dist(baseline, tuple(target[key] + .5 for key in ("x", "y", "z"))) for target in targets)
            walking_segments = (type(health.get("approachWalkingSegmentsVersion")) is int
                    and health["approachWalkingSegmentsVersion"] >= 1
                    and type(health.get("nativeNavigationSegmentsVersion")) is int
                    and health["nativeNavigationSegmentsVersion"] >= 1)
            if strategy == "walking" and target_distance > 32 and not walking_segments:
                raise BridgeError("APPROACH_SEGMENTS_UNAVAILABLE", "Distant walking needs native segmented endpoint support; no movement dispatched.", outcome_unknown=False)
            # Match the existing recipe-task walking window; retain the legacy nearby
            # contract when the bridge has not advertised the extended endpoints.
            distant_walk = strategy == "walking" and target_distance > 8 and walking_segments
            direct_goal = (move and strategy == "walking" and not distant_walk and len(targets) == 1
                           and type(health.get("walkingInteractionGoalVersion")) is int
                           and health["walkingInteractionGoalVersion"] >= 2)
            if direct_goal:
                # Client navigation proves reachable endpoints without a finite-preview gate.
                groups.append({"targetIndices": [0], "targets": targets, "selected": None,
                               "targetMidpoint": {key: targets[0][key] + .5 for key in ("x", "y", "z")},
                               "coverage": {"endpointSelectionOwner": "client_navigation"}})
                after = snapshot
            else:
                after = await partition(targets, list(range(len(targets))))
            first = groups[0]
            result = progress
            result.update({"status": "planned", "sessionId": session, "expectedWorld": world, "dimension": dimension,
                      "strategy": strategy, "groups": groups, "sharedStand": len(groups) == 1,
                      "additionalMoveCount": max(0, len(groups) - 1), "movementDispatched": False,
                      "planningIsArrival": False, "plansPersisted": False, "phases": phases,
                      "pathPlanning": "Native client computes and proves the first group's route before movement input; move=false previews endpoints/views only.",
                      "groupingScope": "Current pose first, then midpoint and bounded surrounding stands; split only after complete known shared-search refusal."})
            if move:
                selected = first["selected"]
                if direct_goal or selected["moveRequired"]:
                    remaining_ms = int(arguments.get("timeout_ms", 10000) - (time.monotonic() - started) * 1000)
                    if remaining_ms < 500:
                        raise BridgeError("APPROACH_TIMEOUT", "Insufficient remaining time for native movement and final proof; movement was not dispatched.",
                                          outcome_unknown=False, receipt=result)
                    nav = {"expectedSessionId": session, "expectedWorld": world, "dimension": dimension,
                           "strategy": strategy, "timeout_ms": min(6000, remaining_ms - 250)}
                    if selected is not None:
                        feet = selected["feet"]
                        nav.update(x=feet["x"], y=feet["y"], z=feet["z"], lookAt=selected["targetViews"][0]["hit"])
                    if distant_walk:
                        # A nearby interaction search is not a long route planner.
                        # The endpoint was proved above; native segments prove the route,
                        # and the actual arrival is independently rechecked below.
                        nav.update(segmented=True, timeout_ms=min(60000, remaining_ms - 250))
                    elif strategy == "walking" and type(health.get("walkingInteractionGoalVersion")) is int and health["walkingInteractionGoalVersion"] >= 1:
                        for key in ("x", "y", "z", "lookAt"):
                            nav.pop(key, None)
                        nav["goal"] = {"type": "interaction", "targets": first["targets"]}
                        if excluded_build_envelope is not None:
                            nav["goal"]["excludedBuildEnvelope"] = excluded_build_envelope
                        nav["timeout_ms"] = min(14500, remaining_ms - 250)
                    if strategy == "ability_flight":
                        nav["finishFlying"] = True
                        distant = math.dist(_point(selected["feet"]), _point(after["player"], feet=True)) > 32
                        segmented_available = (type(health.get("nativeNavigationSegmentsVersion")) is int
                                               and health["nativeNavigationSegmentsVersion"] >= 1)
                        if distant and not segmented_available:
                            raise BridgeError("APPROACH_SEGMENTS_UNAVAILABLE", "Distant flight requires advertised native segmented navigation; no movement dispatched.", outcome_unknown=False)
                        if segmented_available and (distant or remaining_ms - 250 > 6500):
                            # Reuse native long navigation within this goal's existing deadline.
                            # A short route may still need time for height pulses and the final ACK.
                            nav.update(segmented=True, timeout_ms=min(60000, remaining_ms - 250))
                    if excluded_build_envelope is None and strategy in ("walking", "ability_flight") and "goal" not in nav and type(health.get("nativeCrosshairArrivalVersion")) is int and health["nativeCrosshairArrivalVersion"] >= 1:
                        nav["arrivalTargets"] = first["targets"]
                    began = time.monotonic()
                    result["movementDispatched"] = True
                    try:
                        with request_context({**context, "requireBackground": True}):
                            movement = await control(client, "player.navigate", nav)
                    except (BridgeError, TimeoutError) as error:
                        # For guarded navigation, the native false outcome flag
                        # means its mutation checkpoint was never entered.
                        undispatched = isinstance(error, BridgeError) and (
                            error.details.get("original_action_executed") is False
                            or error.details.get("outcome_unknown") is False)
                        if undispatched:
                            result["movementDispatched"] = False
                        phases.append({"phase": "native_route_and_move", "elapsedMs": round((time.monotonic() - began) * 1000, 3),
                                       "status": "refused" if undispatched else "stopped_or_unknown"})
                        raise BridgeError(getattr(error, "code", "APPROACH_TIMEOUT"),
                                          "Native approach stopped; retain the receipt and never replay automatically.",
                                          outcome_unknown=not undispatched, receipt=result, replayAllowed=False,
                                          cause=error.as_dict() if isinstance(error, BridgeError) else {"code": "timeout"}) from None
                    result["movement"] = movement
                    phases.append({"phase": "native_route_and_move", "elapsedMs": round((time.monotonic() - began) * 1000, 3)})
                    if movement.get("status") != "completed" or movement.get("arrivalConfirmed") is not True:
                        raise BridgeError("APPROACH_ARRIVAL_UNCONFIRMED", "Native relocation did not establish actual arrival; never replay.", outcome_unknown=True, receipt=result)
                    if direct_goal:
                        distance = movement.get("walkingPlan", {}).get("distanceBlocks")
                        _require(type(distance) in (int, float) and math.isfinite(distance) and distance >= 0,
                                 "Direct navigation needs its actual planned route distance.")
                        result["movementDispatched"] = distance > .00001
                    proof, after = await observe(first["targets"], current_only=True, stable_start=False)
                    if proof["status"] != "ready" or proof["selected"]["moveRequired"]:
                        raise BridgeError("APPROACH_FINAL_REACH_UNCONFIRMED", "Actual arrival does not establish all planned target views; no more movement.", outcome_unknown=False, receipt=result)
                    first["selected"] = proof["selected"]
                result["status"] = "completed"
                result["readyTargetIndices"] = first["targetIndices"]
                result["alreadyInRange"] = not result["movementDispatched"]
                if interact:
                    view = first["selected"]["targetViews"][0]
                    target = first["targets"][0]
                    use = {"action": "use", **{key: target[key] for key in ("x", "y", "z")}, "side": view["side"]}
                    if "hitPoint" in target:
                        use["hitPoint"] = target["hitPoint"]
                    if "part" in target.get("targetIdentity", {}):
                        use["targetPart"] = target["targetIdentity"]["part"]
                    began = time.monotonic()
                    result["interactionDispatched"] = True
                    try:
                        with request_context({**context, "requireBackground": True}):
                            result["interaction"] = await control(client, "player.interact", use)
                    except (BridgeError, TimeoutError) as error:
                        undispatched = isinstance(error, BridgeError) and (
                            error.details.get("original_action_executed") is False or error.details.get("outcome_unknown") is False)
                        result["interactionDispatched"] = not undispatched
                        phases.append({"phase": "native_use", "elapsedMs": round((time.monotonic() - began) * 1000, 3),
                                       "status": "refused" if undispatched else "unknown"})
                        raise BridgeError(getattr(error, "code", "APPROACH_TIMEOUT"),
                                          "Normal use stopped or is unknown; never repeat it automatically.",
                                          outcome_unknown=not undispatched,
                                          cause=error.as_dict() if isinstance(error, BridgeError) else {"code": "timeout"}) from None
                    phases.append({"phase": "native_use", "elapsedMs": round((time.monotonic() - began) * 1000, 3), "status": "returned"})
                    result["interactionEffectConfirmed"] = False
                    result["replayAllowed"] = False
                    if open_gui is not None:
                        remaining_ms = int(arguments.get("timeout_ms", 10000) - (time.monotonic() - started) * 1000)
                        if remaining_ms <= 250:
                            raise BridgeError("GUI_WAIT_TIMEOUT", "No acceptance window remains after the single use; inspect current state, never replay.", outcome_unknown=True)
                        began = time.monotonic()
                        with request_context({**context, "requireBackground": True}):
                            gui = await client.invoke("gui.inspect", {"waitFor": {
                                **open_gui, "timeoutMillis": min(open_gui.get("timeoutMillis", 6500), 6500, remaining_ms - 250)}})
                        result["gui"] = gui
                        phases.append({"phase": "native_gui_acceptance", "elapsedMs": round((time.monotonic() - began) * 1000, 3)})
                        evidence = response_context(gui)
                        current = evidence.get("snapshot") if isinstance(evidence, dict) and evidence.get("phase") == "after" else None
                        _require(isinstance(current, dict), "Native GUI acceptance requires its fresh AFTER context.")
                        _guard(current, session, "developer", world, True)
                        _RecipeTask.require_live_player(current)
                        _require(current.get("world", {}).get("dimension") == dimension
                                 and current.get("serverControl", {}).get("playerId") == actor,
                                 "GUI acceptance belongs to a changed actor or dimension.")
                        receipt = gui.get("guiWait", {})
                        if receipt.get("matched") is not True:
                            failure = wait_failure(gui)
                            raise BridgeError(failure["code"], failure["message"], reason=failure["reason"], guiWait=receipt, outcome_unknown=True)
                        _require(receipt.get("version") == 1 and type(receipt.get("version")) is int
                                 and receipt.get("source") == "normal_client_end_ticks" and receipt.get("timedOut") is False
                                 and gui.get("containerSynchronization", {}).get("fullNativeSlotsEqual") is True
                                 and phantom_items_confirmed(gui.get("containerSynchronization", {}))
                                 and gui.get("containerSynchronization", {}).get("fullPhantomFluidSlotsEqual") is True
                                 and gui.get("containerSynchronization", {}).get("phantomRegistryComplete") is True
                                 and gui.get("containerSynchronization", {}).get("fullNbtCompared") is True
                                 and gui.get("openContainerMatches") is True and type(gui.get("windowId")) is int
                                 and gui["windowId"] == gui.get("playerOpenWindowId")
                                 and isinstance(gui.get("screenId"), str) and gui["screenId"] != ""
                                 and gui.get("truncated") is False and isinstance(gui.get("slots"), list)
                                 and len(gui["slots"]) == gui.get("totalSlots"), "Complete native GUI synchronization evidence is required.")
                        result["interactionEffectConfirmed"] = True
                        after = current
            result["actualPlayer"] = after["player"]
            result["actualPlayerObservationPhase"] = "after_gui_acceptance" if open_gui is not None else (
                "after_approach_before_use" if interact else "after_approach")
            result["serverControl"] = after.get("serverControl")
            result["inputKeys"] = after.get("inputKeys")
            result["elapsedMs"] = round((time.monotonic() - started) * 1000, 3)
            if _discovery is not None:
                _discovery.bind(client, client.configuration.read(), session)
            return result


def approach_summary(result: dict[str, Any]) -> dict[str, Any]:
    """Keep actionable current views/GUI while dropping repeated geometry and navigation traces."""
    summary = {key: value for key, value in result.items() if key not in
               {"movement", "groups", "pathPlanning", "groupingScope", "serverControl", "inputKeys"}}
    summary["detailsIncluded"] = False
    summary["groups"] = [{key: group.get(key) for key in ("targetIndices", "targets")} | {
        "selected": None if group.get("selected") is None else {
            "feet": group["selected"].get("feet"), "moveRequired": group["selected"].get("moveRequired"),
            "targetViews": [{key: view[key] for key in ("targetIndex", "side", "hit", "aim", "targetPart") if key in view}
                            for view in group["selected"].get("targetViews", [])]}}
        for group in result.get("groups", [])]
    if "movement" in result:
        summary["movement"] = {key: result["movement"][key] for key in ("status", "arrivalConfirmed", "elapsedMillis", "taskId")
                               if key in result["movement"]}
    if "gui" in result:
        gui = result["gui"]
        summary["gui"] = {key: gui[key] for key in (
            "screen", "screenId", "screenKind", "screenOpen", "width", "height", "windowId", "containerClass",
            "openContainerMatches", "playerOpenWindowId", "cursorItem", "totalSlots", "truncated",
            "containerSynchronization", "guiWait", "wholeStackTransfer", "visibleText", "widgetTruncated") if key in gui}
        if "slots" in gui:
            summary["gui"]["slots"] = [_slot_summary(slot) for slot in gui["slots"]]
            summary["gui"]["slotBindingScope"] = "Actual slot IDs/items and positive unique native bindings; omitted binding fields remain unknown."
        if "controls" in gui:
            controls = gui["controls"]
            summary["gui"]["controls"] = {key: value for key, value in controls.items() if key not in {"widgets", "coverage", "budget"}}
            summary["gui"]["controls"]["widgets"] = [widget for widget in controls.get("widgets", [])
                                                        if widget.get("supportedActions") or widget.get("contextOnly") is True]
            summary["gui"]["controls"]["nonActionableWidgetsOmitted"] = True
            coverage = controls.get("coverage", {})
            if "treeError" in coverage:
                summary["gui"]["controls"]["treeError"] = coverage["treeError"]
        summary["gui"]["summaryProjection"] = True
        summary["gui"]["detailsTool"] = "mcb_gui"
    return summary


def _slot_summary(slot: dict[str, Any]) -> dict[str, Any]:
    compact = {key: slot[key] for key in ("slot", "nativeContainerSlotId", "item", "restrictionType", "slotAcceptance") if key in slot}
    if slot.get("playerInventoryBindingVerified") is True:
        compact.update({key: slot[key] for key in ("playerInventoryBindingVerified", "playerInventoryIndex") if key in slot})
    if slot.get("machineInventoryBindingVerified") is True:
        compact.update({key: slot[key] for key in ("machineInventoryBindingVerified", "machineInventoryIndex",
            "machineInventoryOwner", "machineInventoryRole", "machineInventoryCanPut", "consumedInput") if key in slot})
    return compact
