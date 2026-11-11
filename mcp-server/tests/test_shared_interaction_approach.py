"""Shared endpoint planning and one native relocation; mocks are not live timing claims."""

import copy
import json

import anyio
import httpx
import pytest
from jsonschema.exceptions import ValidationError

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, _ContextData
from minecraft_codex_bridge.interaction_approach import run_interaction_approach
from minecraft_codex_bridge.server import invoke_control
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import execution_validator, tool_validator
from minecraft_codex_bridge.workflows import validate_args


def args(**updates):
    return {"expectedSessionId": "session", "expectedWorld": "Debug", "dimension": 180,
            "targets": [{"x": 0, "y": 64, "z": 0}, {"x": 2, "y": 64, "z": 0}], **updates}


class Client:
    def __init__(self, *, in_range=True, fault=None, split=False, modern=False):
        self.position = {"x": .23, "feetY": 63, "z": -1.31}
        self.in_range, self.fault, self.split = in_range, fault, split
        self.calls, self.moves, self.uses = [], [], []
        self.modern = modern

    def snapshot(self):
        return {"sessionId": "session", "profile": "developer", "worldLoaded": True,
                "world": {"dimension": 180, "worldName": "Debug"}, "screen": "",
                "player": {**self.position, "health": 20}, "serverControl": {"playerId": "actor"}, "inputKeys": []}

    async def invoke(self, operation, payload):
        self.calls.append((operation, copy.deepcopy(payload)))
        if operation == "bridge.health":
            return {"sessionId": "session", "approachSharedTargetsVersion": True if self.fault == "version" else 1,
                    "walkingInteractionGoalVersion": 1 if self.modern else None,
                    "capabilities": ["player.interact"], "interactionBlockTargeting": True, "interactionViewBinding": True}
        if operation == "client.snapshot":
            return self.snapshot()
        assert operation == "player.plan_approach"
        targets = payload["targets"]
        selected_feet = {"x": self.position["x"] if self.in_range else 1.5,
                         "y": self.position["feetY"], "z": self.position["z"]}
        body = {"loaded": True, "status": "observed", "occupiable": True, "entityCoveragePartial": False,
                "hazard": False, "liquid": False, "canStand": True, "fullSupportCoverage": True}
        views = [{"targetIndex": index, "side": 2, "target": target,
                  "hit": {"x": target["x"] + .5, "y": target["y"] + .5, "z": target["z"]},
                  "aim": {"yaw": 0, "pitch": 10},
                  "clientRay": {"verified": True, "source": "client", "side": 2},
                  "serverRay": {"verified": True, "source": "integrated_server", "side": 2}}
                 for index, target in enumerate(targets)]
        slots = 1 if payload["currentOnly"] else 34
        value = {"sessionId": "session", "dimension": 180, "sharedInteractionVersion": 1,
                 "readOnly": True, "submitted": False, "partial": False, "status": "ready", "complete": False,
                 "movementStrategy": payload["movementStrategy"], "useNormalReach": True,
                 "targetCount": len(targets), "targets": targets, "targetMidpoint": {"x": 1.5, "y": 64.5, "z": .5},
                 "current": {**self.position, "reachPlayerId": "actor"},
                 "coverage": {"candidateSlots": slots, "candidatesExamined": 1, "unknownChecks": 0},
                 "selected": {"feet": selected_feet, "moveRequired": not self.in_range,
                              "targetViews": views, "clientBody": body, "serverBody": copy.deepcopy(body)}}
        if self.split and len(targets) > 1:
            value.update(status="no_candidate", selected=None, complete=True)
            value["coverage"]["candidatesExamined"] = slots
        if self.fault == "partial": value["partial"] = True
        if self.fault == "unknown": value["coverage"]["unknownChecks"] = 1; value.update(status="no_candidate", complete=True, selected=None)
        if self.fault == "ray": value["selected"]["targetViews"][1]["serverRay"]["verified"] = False
        if self.fault == "nan": value["selected"]["targetViews"][0]["aim"]["yaw"] = float("nan")
        if self.fault == "support": value["selected"]["serverBody"]["canStand"] = False
        if self.fault == "partial_support": value["selected"]["serverBody"]["fullSupportCoverage"] = False
        if self.fault == "post_ray" and payload["currentOnly"]: value["selected"]["targetViews"][1]["serverRay"]["verified"] = False
        if self.fault == "target": value["targets"] = [{"x": 22, "y": 64, "z": 0}, targets[1]]
        if self.fault == "move_claim": value["selected"]["moveRequired"] = not value["selected"]["moveRequired"]
        after = self.snapshot()
        if self.fault == "drift": after["player"]["x"] += .2
        if self.fault == "actor": after["serverControl"]["playerId"] = "respawned"
        if self.fault == "timeout": await anyio.sleep(1.1)
        return _ContextData(value, {"phase": "after", "snapshot": after})

    async def control(self, client, operation, payload):
        assert client is self
        if operation == "player.interact":
            self.uses.append(copy.deepcopy(payload))
            if self.fault == "use_timeout": raise TimeoutError()
            if self.fault == "use_refused": raise BridgeError("TAKEOVER_LOST", "No use dispatched.", original_action_executed=False)
            return {"accepted": True, "action": "use"}
        assert operation == "player.navigate"
        self.moves.append(copy.deepcopy(payload))
        if self.fault == "move_timeout": raise TimeoutError()
        if self.fault == "takeover": raise BridgeError("TAKEOVER_LOST", "No action dispatched.", original_action_executed=False)
        self.position = {"x": 1.5, "feetY": 63, "z": -1.31} if "goal" in payload else {"x": payload["x"], "feetY": payload["y"], "z": payload["z"]}
        self.in_range = True
        return {"status": "completed", "arrivalConfirmed": self.fault != "arrival"}


async def run(client, **updates):
    return await run_interaction_approach(client, args(**updates), anyio.Lock(), control=client.control)


@pytest.mark.parametrize("mode", ["walking", "ability_flight", "missing_capability", "lost_envelope"])
async def test_installation_envelope_survives_navigation_and_actual_recheck(mode):
    envelope = {"minX": 5, "maxX": 5, "minY": 64, "maxY": 64, "minZ": 0, "maxZ": 0}
    class PlacementClient(Client):
        async def invoke(self, operation, payload):
            value = await super().invoke(operation, payload)
            if operation == "bridge.health":
                value["nativeCrosshairArrivalVersion"] = 1
                if mode != "missing_capability": value["interactionBuildEnvelopeVersion"] = 1
            if operation == "player.plan_approach":
                assert payload["excludedBuildEnvelope"] == envelope
                value["buildEnvelopeVersion"] = 1
                if mode != "lost_envelope": value["excludedBuildEnvelope"] = envelope
            return value
    client = PlacementClient(in_range=False, modern=mode == "walking")
    async def execute():
        return await run_interaction_approach(client, args(strategy="ability_flight" if mode == "ability_flight" else "walking"),
            anyio.Lock(), control=client.control, excluded_build_envelope=envelope)
    if mode in {"missing_capability", "lost_envelope"}:
        with pytest.raises(BridgeError): await execute()
        assert not client.moves and not client.uses
    else:
        result = await execute()
        assert result["status"] == "completed" and result["excludedBuildEnvelope"] == envelope
        if mode == "walking": assert client.moves[0]["goal"]["excludedBuildEnvelope"] == envelope
        else: assert "arrivalTargets" not in client.moves[0]  # Do not stop early inside the future cube.
        assert any(op == "player.plan_approach" and p["currentOnly"] for op, p in client.calls)


async def test_already_in_range_keeps_exact_pose_and_one_endpoint_read():
    client = Client()
    result = await run(client)
    assert result["alreadyInRange"] and not result["movementDispatched"] and not client.moves
    assert result["groups"][0]["selected"]["feet"]["x"] == .23
    assert len([call for call in client.calls if call[0] == "player.plan_approach"]) == 1


async def test_precompute_then_single_native_move_and_single_current_recheck():
    client = Client(in_range=False)
    result = await run(client)
    assert result["status"] == "completed" and result["sharedStand"] and len(client.moves) == 1
    assert client.moves[0]["lookAt"] == result["groups"][0]["selected"]["targetViews"][0]["hit"]
    assert [payload["currentOnly"] for op, payload in client.calls if op == "player.plan_approach"] == [False, True]
    assert [row["phase"] for row in result["phases"]] == ["shared_stand_plan", "native_route_and_move", "current_recheck"]


async def test_preview_returns_all_endpoints_without_movement_or_persisted_plan():
    client = Client(in_range=False)
    result = await run(client, move=False)
    assert result["status"] == "planned" and not client.moves and not result["plansPersisted"]
    assert "endpoints/views only" in result["pathPlanning"]


async def test_walking_routes_the_target_set_in_one_call_instead_of_freezing_first_stand():
    client = Client(in_range=False, modern=True)
    result = await run(client)
    assert result["status"] == "completed" and len(client.moves) == 1
    move = client.moves[0]
    assert move["goal"] == {"type": "interaction", "targets": args()["targets"]}
    assert not any(key in move for key in ("x", "y", "z", "lookAt"))
    assert [payload["currentOnly"] for op, payload in client.calls if op == "player.plan_approach"] == [False, True]


async def test_flight_keeps_explicit_endpoint_despite_walking_goal_advertisement():
    client = Client(in_range=False, modern=True)
    result = await run(client, strategy="ability_flight")
    assert result["status"] == "completed" and "goal" not in client.moves[0] and client.moves[0]["finishFlying"]


@pytest.mark.parametrize("version", [None, False, True, 0, 1.0, "1", 1])
@pytest.mark.parametrize("timeout_ms", [6000, 15000])
async def test_flight_uses_advertised_long_navigation_within_outer_deadline(version, timeout_ms):
    client = Client(in_range=False)
    original = client.invoke

    async def invoke(operation, payload):
        result = await original(operation, payload)
        if operation == "bridge.health":
            result["nativeNavigationSegmentsVersion"] = version
            result["nativeCrosshairArrivalVersion"] = 1
        return result

    client.invoke = invoke
    result = await run(client, strategy="ability_flight", timeout_ms=timeout_ms)
    assert result["status"] == "completed" and len(client.moves) == 1
    move = client.moves[0]
    long_navigation = type(version) is int and version >= 1 and timeout_ms > 6750
    assert move.get("segmented", False) is long_navigation
    assert 250 <= move["timeout_ms"] <= timeout_ms - 250
    assert (move["timeout_ms"] > 6500) is long_navigation
    assert move["arrivalTargets"] == args()["targets"] and move["finishFlying"] is True
    assert [payload["currentOnly"] for op, payload in client.calls if op == "player.plan_approach"] == [False, True]
    validate_args("mcb_navigate", move)


@pytest.mark.parametrize("version", [None, False, True, 0, 1])
async def test_crosshair_targets_are_capability_gated_and_preserve_legacy_flight(version):
    client = Client(in_range=False, modern=True)
    original = client.invoke

    async def invoke(operation, payload):
        result = await original(operation, payload)
        if operation == "bridge.health":
            result["nativeCrosshairArrivalVersion"] = version
        return result

    client.invoke = invoke
    result = await run(client, strategy="ability_flight")
    assert result["status"] == "completed" and len(client.moves) == 1
    assert ("arrivalTargets" in client.moves[0]) is (type(version) is int and version >= 1)
    if type(version) is int and version >= 1:
        assert client.moves[0]["arrivalTargets"] == args()["targets"]


def test_crosshair_target_navigation_keeps_existing_error_validation():
    validator = execution_validator("mcb_navigate")
    nav = {"x": 0, "y": 64, "z": 0, "expectedWorld": "Debug", "expectedSessionId": "session", "dimension": 180,
           "strategy": "ability_flight", "arrivalTargets": args()["targets"]}
    assert not list(validator.iter_errors(nav))
    assert list(validator.iter_errors({**nav, "strategy": "developer_teleport"}))
    assert list(validator.iter_errors({**nav, "arrivalTargets": []}))


class DirectGoalClient(Client):
    async def invoke(self, operation, payload):
        if operation == "bridge.health":
            self.calls.append((operation, copy.deepcopy(payload)))
            return {"sessionId": "session", "approachSharedTargetsVersion": 1, "walkingInteractionGoalVersion": 2,
                    "capabilities": ["player.interact"], "interactionBlockTargeting": True, "interactionViewBinding": True}
        if operation == "player.plan_approach":
            assert payload["currentOnly"] is True or getattr(self, "preview", False), "No finite stand preview before direct client routing."
        return await super().invoke(operation, payload)

    async def control(self, client, operation, payload):
        was_in_range = self.in_range
        result = await super().control(client, operation, payload)
        if operation == "player.navigate":
            result["walkingPlan"] = {"distanceBlocks": 0 if was_in_range else 4.25}
        return result


@pytest.mark.parametrize("in_range", [True, False])
async def test_single_target_direct_goal_has_only_final_actual_face_read(in_range):
    client = DirectGoalClient(in_range=in_range)
    result = await run(client, targets=[args()["targets"][0]])
    assert result["status"] == "completed" and result["alreadyInRange"] is in_range
    assert len(client.moves) == 1 and "goal" in client.moves[0]
    assert [payload["currentOnly"] for op, payload in client.calls if op == "player.plan_approach"] == [True]
    assert result["groups"][0]["selected"]["moveRequired"] is False


async def test_new_client_preview_keeps_read_only_full_endpoint_plan():
    client = DirectGoalClient(in_range=False)
    # Preview still uses the public finite stand interface rather than movement.
    client.preview = True
    result = await run(client, targets=[args()["targets"][0]], move=False)
    assert result["status"] == "planned" and not client.moves


@pytest.mark.parametrize("capable", [True, False])
@pytest.mark.parametrize("fault", [None, "move_timeout", "arrival"])
async def test_far_walking_reuses_segmented_xyz_and_actual_recheck_without_replay(capable, fault):
    class FarClient(DirectGoalClient):
        async def invoke(self, operation, payload):
            result = await super().invoke(operation, payload)
            if operation == "bridge.health" and capable:
                result.update(approachWalkingSegmentsVersion=1, nativeNavigationSegmentsVersion=1,
                              nativeCrosshairArrivalVersion=1)
            return result
    client = FarClient(in_range=False, fault=fault)
    client.position["x"] = -96
    client.preview = True
    if not capable or fault:
        with pytest.raises(BridgeError):
            await run(client, targets=[args()["targets"][0]], interact=True, timeout_ms=60000)
        assert len(client.moves) == (1 if capable else 0) and not client.uses
    else:
        result = await run(client, targets=[args()["targets"][0]], interact=True, timeout_ms=60000)
        tool_validator("mcb_approach").validate(args(targets=[args()["targets"][0]], interact=True, timeout_ms=60000))
        assert result["status"] == "completed" and len(client.moves) == len(client.uses) == 1
        move = client.moves[0]
        assert move["segmented"] is True and "goal" not in move and move["timeout_ms"] > 15000
        assert move["arrivalTargets"] == args()["targets"][:1]
        assert not list(execution_validator("mcb_navigate").iter_errors(move))
        assert [p["currentOnly"] for op, p in client.calls if op == "player.plan_approach"] == [False, True]


@pytest.mark.parametrize("in_range", [True, False])
async def test_combined_approach_uses_proven_face_once_without_agent_round_trip(in_range):
    client = DirectGoalClient(in_range=in_range)
    result = await run(client, targets=[args()["targets"][0]], interact=True)
    assert result["status"] == "completed" and result["interactionDispatched"]
    assert client.uses == [{"action": "use", "x": 0, "y": 64, "z": 0, "side": 2}]
    assert len(client.moves) == 1
    assert [payload["currentOnly"] for op, payload in client.calls if op == "player.plan_approach"] == [True]
    assert [row["phase"] for row in result["phases"]] == ["native_route_and_move", "current_recheck", "native_use"]
    assert not result["interactionEffectConfirmed"] and not result["replayAllowed"]
    assert result["actualPlayerObservationPhase"] == "after_approach_before_use"


@pytest.mark.parametrize("updates", [{"move": False, "targets": [args()["targets"][0]]}, {}])
async def test_combined_preview_or_multiple_targets_refuse_before_reads_or_actions(updates):
    client = Client()
    with pytest.raises(BridgeError) as failure:
        await run(client, interact=True, **updates)
    assert failure.value.code == "INVALID_ARGUMENTS" and not client.calls and not client.moves and not client.uses


async def test_combined_missing_native_use_capability_refuses_before_movement():
    class OldClient(DirectGoalClient):
        async def invoke(self, operation, payload):
            value = await super().invoke(operation, payload)
            if operation == "bridge.health": value.pop("interactionViewBinding")
            return value
    client = OldClient(in_range=False)
    with pytest.raises(BridgeError) as failure:
        await run(client, targets=[args()["targets"][0]], interact=True)
    assert failure.value.code == "INTERACTION_UNAVAILABLE" and not client.moves and not client.uses


@pytest.mark.parametrize("fault", ["move_timeout", "arrival", "takeover"])
async def test_combined_move_failure_never_uses_target(fault):
    client = DirectGoalClient(in_range=False, fault=fault)
    with pytest.raises(BridgeError) as failure:
        await run(client, targets=[args()["targets"][0]], interact=True)
    assert not client.uses and failure.value.details["receipt"]["status"] == "failed"


@pytest.mark.parametrize("fault", ["use_timeout", "use_refused"])
async def test_combined_use_failure_preserves_movement_and_never_replays(fault):
    client = DirectGoalClient(in_range=False, fault=fault)
    with pytest.raises(BridgeError) as failure:
        await run(client, targets=[args()["targets"][0]], interact=True)
    receipt = failure.value.details["receipt"]
    assert len(client.uses) == 1 and len(client.moves) == 1 and receipt["movementDispatched"]
    assert receipt["status"] == "failed" and receipt["phases"][-1]["phase"] == "native_use"
    assert failure.value.details["outcome_unknown"] is (fault == "use_timeout")
    assert receipt["interactionDispatched"] is (fault == "use_timeout")
    assert failure.value.details["replayAllowed"] is False


async def test_complete_known_absence_splits_targets_without_implicit_extra_move():
    client = Client(split=True)
    result = await run(client)
    assert len(result["groups"]) == 2 and result["additionalMoveCount"] == 1
    assert result["readyTargetIndices"] == [0] and not client.moves


@pytest.mark.parametrize("fault", ["version", "partial", "unknown", "ray", "nan", "support", "partial_support", "target", "move_claim", "drift", "actor"])
async def test_bad_proof_stops_before_any_movement(fault):
    client = Client(in_range=False, fault=fault)
    with pytest.raises(BridgeError): await run(client)
    assert not client.moves


async def test_partial_plan_preserves_diagnostics_without_dispatch_or_retry():
    client = Client(in_range=False, fault="partial")
    with pytest.raises(BridgeError) as failure:
        await run(client)
    assert failure.value.details["planning"]["partial"] is True
    assert failure.value.details["planning"]["coverage"]["candidateSlots"] == 34
    assert failure.value.details["receipt"]["movementDispatched"] is False
    assert failure.value.details["replayAllowed"] is False
    assert not client.moves
    assert len([op for op, payload in client.calls if op == "player.plan_approach"]) == 1


@pytest.mark.parametrize("fault", ["arrival", "move_timeout"])
async def test_uncertain_movement_retains_receipt_and_never_replays(fault):
    client = Client(in_range=False, fault=fault)
    with pytest.raises(BridgeError) as failure: await run(client)
    assert len(client.moves) == 1 and failure.value.details["receipt"]["movementDispatched"]
    assert failure.value.details["outcome_unknown"]


async def test_read_only_timeout_is_specific_and_has_no_movement():
    client = Client(fault="timeout")
    with pytest.raises(BridgeError) as failure: await run(client, timeout_ms=1000)
    assert failure.value.code == "APPROACH_TIMEOUT" and not failure.value.details["outcome_unknown"]
    assert not client.moves


async def test_post_move_refusal_preserves_actual_movement_without_replay():
    client = Client(in_range=False, fault="post_ray")
    with pytest.raises(BridgeError) as failure: await run(client)
    assert len(client.moves) == 1 and failure.value.details["receipt"]["movement"]["arrivalConfirmed"]
    assert failure.value.details["replayAllowed"] is False


async def test_confirmed_pre_dispatch_takeover_failure_is_not_unknown_motion():
    client = Client(in_range=False, fault="takeover")
    with pytest.raises(BridgeError) as failure: await run(client)
    assert not failure.value.details["outcome_unknown"] and not failure.value.details["receipt"]["movementDispatched"]


@pytest.mark.parametrize("background_enabled", [False, True])
@pytest.mark.parametrize("failure", [None, "rejected", "started", "untyped"])
@pytest.mark.parametrize("combined", [False, True])
async def test_shared_approach_uses_real_transport_and_control_guard(background_enabled, failure, combined):
    model = Client(in_range=False)
    baseline = copy.deepcopy(model.position)
    background = {"enabled": background_enabled, "helperInstalled": background_enabled,
                  "pauseOnLostFocus": not background_enabled}
    requests = []

    async def respond(request):
        body = json.loads(request.content)
        requests.append(body)
        operation, payload = body["operation"], body["args"]
        envelope = {"requestId": body["requestId"], "ok": True}
        if operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "approachSharedTargetsVersion": 1,
                    "interactionBlockTargeting": True, "interactionViewBinding": True,
                    "capabilities": ["bridge.health", "client.snapshot", "client.background",
                                     "player.plan_approach", "player.navigate", "player.interact"], "background": background.copy()}
        elif operation == "client.background":
            assert "includeContext" not in body
            background.update(enabled=True, helperInstalled=True, pauseOnLostFocus=False)
            data = background.copy()
        elif operation in ("player.navigate", "player.interact"):
            # Mirror the native navigation gate on the actual serialized request,
            # rather than bypassing invoke_control with the permissive mock.
            expected = {"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
                        "expectedWorld": "Debug", "requireBackground": True}
            valid = body.get("includeContext") == expected and background["enabled"]
            if not valid or failure is not None:
                error = {"code": "invalid_request" if not valid or failure == "rejected" else "navigation_failed",
                         "message": "Native navigation refused or stopped."}
                if not valid or failure != "untyped":
                    error["outcome_unknown"] = valid and failure == "started"
                return httpx.Response(200, json={"requestId": body["requestId"], "ok": False, "error": error})
            before = model.snapshot()
            data = await model.control(model, operation, payload)
            envelope["context"] = {"version": 1, "operation": operation, "phase": "before", "snapshot": before}
        else:
            data = await model.invoke(operation, payload)
        if "includeContext" in body and operation not in ("player.navigate", "player.interact"):
            assert operation in ("bridge.health", "player.plan_approach")
            assert body["includeContext"]["requireBackground"] is False
            envelope["context"] = {"version": 1, "operation": operation, "phase": "after", "snapshot": model.snapshot()}
        envelope["data"] = dict(data)
        return httpx.Response(200, json=envelope)

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "test-token"}),
                          transport=httpx.MockTransport(respond))
    try:
        parameters = args(targets=[args()["targets"][0]], interact=True) if combined else args()
        if failure is None:
            result = await run_interaction_approach(client, parameters, anyio.Lock(), control=invoke_control)
            assert result["status"] == "completed" and result["movementDispatched"]
            assert len(model.moves) == 1
        else:
            with pytest.raises(BridgeError) as caught:
                await run_interaction_approach(client, parameters, anyio.Lock(), control=invoke_control)
            error = caught.value.details
            assert error["outcome_unknown"] is (failure != "rejected")
            assert error["receipt"]["movementDispatched"] is (failure != "rejected")
            assert error["replayAllowed"] is False
            assert model.position == baseline and not model.moves
        assert len([body for body in requests if body["operation"] == "player.navigate"]) == 1
        assert len([body for body in requests if body["operation"] == "player.interact"]) == int(combined and failure is None)
        plans = [body["args"]["currentOnly"] for body in requests if body["operation"] == "player.plan_approach"]
        assert plans == ([False, True] if failure is None else [False])
        assert len([body for body in requests if body["operation"] == "client.background"]) == int(not background_enabled)
        await client.call("bridge.health", {})
        assert "includeContext" not in requests[-1]
    finally:
        await client.aclose()


def test_schema_retains_original_navigation_and_bounds_shared_goals():
    validator = tool_validator("mcb_approach")
    validator.validate(args())
    validator.validate(args(targets=[args()["targets"][0]], interact=True))
    assert "mcb_navigate" in TOOL_BY_NAME
    for invalid in (args(targets=[]), args(targets=[{"x": 1, "y": 64}]), args(strategy="automatic"),
                    args(interact=True), args(targets=[args()["targets"][0]], interact=True, move=False)):
        assert list(validator.iter_errors(invalid))


class GuiGoalClient(DirectGoalClient):
    def snapshot(self):
        return {**super().snapshot(), "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}}

    async def invoke(self, operation, payload):
        if operation == "gui.inspect":
            self.calls.append((operation, copy.deepcopy(payload)))
            value = {"screenId": "gui1", "windowId": 1, "playerOpenWindowId": 1, "openContainerMatches": True,
                     "totalSlots": 1, "slots": [{"slot": 0, "item": None}], "truncated": False,
                     "containerSynchronization": {"fullNativeSlotsEqual": True, "fullNbtCompared": True, "fullPhantomSlotsEqual": True,
                         "fullPhantomFluidSlotsEqual": True, "phantomRegistryComplete": True},
                     "guiWait": {"version": 1, "source": "normal_client_end_ticks", "matched": True, "timedOut": False}}
            if self.fault == "gui_timeout": value["guiWait"].update(matched=False, timedOut=True,
                diagnostic={"screenObserved": True, "container": {"reason": "phantom_contents_pending", "phantomKey": "registered:template"}})
            if self.fault == "gui_partial": value["truncated"] = True
            if self.fault == "gui_window": value["playerOpenWindowId"] = 2
            if self.fault == "gui_nbt": value["containerSynchronization"]["fullNbtCompared"] = False
            if self.fault == "gui_fluid_unknown": value["containerSynchronization"].pop("fullPhantomFluidSlotsEqual")
            if self.fault == "gui_registry_partial": value["containerSynchronization"]["phantomRegistryComplete"] = False
            if self.fault == "gui_display_init": value["containerSynchronization"].update(fullPhantomSlotsEqual=False,
                phantomDisplayInitializationVersion=1, phantomDisplayInitializationVerified=True,
                phantomConfigurationConfirmed=True, phantomDisplayInitializedCount=1, phantomItemSlotCount=2)
            if self.fault == "gui_phantom_unknown": value["containerSynchronization"].pop("fullPhantomSlotsEqual")
            after = self.snapshot()
            if self.fault == "gui_actor": after["serverControl"]["playerId"] = "different"
            return _ContextData(value, {"phase": "after", "snapshot": after})
        value = await super().invoke(operation, payload)
        if operation == "bridge.health" and self.fault != "gui_capability": value["guiNativeWaitVersion"] = 1
        return value


async def test_single_call_moves_uses_and_accepts_real_gui_without_guessed_class():
    client = GuiGoalClient(in_range=False)
    result = await run(client, targets=[args()["targets"][0]], interact=True, openGui={})
    assert result["interactionEffectConfirmed"] and result["actualPlayerObservationPhase"] == "after_gui_acceptance"
    assert len(client.uses) == 1 and len(client.moves) == 1
    gui_calls = [payload for op, payload in client.calls if op == "gui.inspect"]
    assert len(gui_calls) == 1 and 1 <= gui_calls[0]["waitFor"]["timeoutMillis"] <= 6500


@pytest.mark.parametrize("fault", ["gui_timeout", "gui_partial", "gui_window", "gui_nbt", "gui_actor", "gui_fluid_unknown", "gui_registry_partial", "gui_phantom_unknown"])
async def test_gui_acceptance_failure_keeps_evidence_and_never_uses_again(fault):
    client = GuiGoalClient(fault=fault)
    with pytest.raises(BridgeError) as caught:
        await run(client, targets=[args()["targets"][0]], interact=True, openGui={})
    assert len(client.uses) == 1 and caught.value.details["replayAllowed"] is False
    assert caught.value.details["receipt"]["status"] == "failed" and "gui" in caught.value.details["receipt"]
    assert not caught.value.details["receipt"]["interactionEffectConfirmed"]
    if fault == "gui_timeout":
        assert caught.value.details["reason"] == "phantom_contents_pending"
        assert caught.value.details["guiWait"]["diagnostic"]["container"]["phantomKey"] == "registered:template"


async def test_native_gui_acceptance_uses_verified_display_initialization_without_replaying_use():
    client = GuiGoalClient(fault="gui_display_init")
    result = await run(client, targets=[args()["targets"][0]], interact=True, openGui={})
    assert result["interactionEffectConfirmed"] and len(client.uses) == 1
    assert result["gui"]["containerSynchronization"]["fullPhantomSlotsEqual"] is False


async def test_gui_capability_refused_before_any_motion():
    client = GuiGoalClient(fault="gui_capability", in_range=False)
    with pytest.raises(BridgeError) as caught:
        await run(client, targets=[args()["targets"][0]], interact=True, openGui={})
    assert caught.value.code == "GUI_WAIT_UNAVAILABLE" and not client.moves and not client.uses


def test_gui_goal_schema_never_accepts_placeholder_or_unbound_use():
    validator = tool_validator("mcb_approach")
    valid = args(targets=[args()["targets"][0]], interact=True, openGui={})
    validator.validate(valid)
    for invalid in ({**valid, "interact": False}, {**valid, "openGui": {"timeoutMillis": 6501}},
                    {**valid, "openGui": {"conditions": [{"path": "screenOpen", "operator": "equals", "value": True}]}},
                    {**valid, "openGui": {"conditions": [{"path": "slots", "operator": "contains", "value": {}}]}}):
        assert list(validator.iter_errors(invalid))


async def test_compact_summary_retains_actionable_views_gui_and_timing():
    from minecraft_codex_bridge.interaction_approach import approach_summary
    client = GuiGoalClient(in_range=False)
    full = await run(client, targets=[args()["targets"][0]], interact=True, openGui={})
    compact = approach_summary(full)
    assert compact["gui"]["guiWait"] == full["gui"]["guiWait"] and compact["phases"] == full["phases"]
    assert compact["gui"]["slots"] == full["gui"]["slots"] and compact["gui"]["summaryProjection"]
    assert compact["movement"]["arrivalConfirmed"] and compact["groups"][0]["selected"]["targetViews"][0]["side"] == 2
    assert "serverBody" not in compact["groups"][0]["selected"] and not compact["detailsIncluded"]


def test_compact_slots_keep_real_identity_nbt_marker_and_positive_bindings_without_index_guessing():
    from minecraft_codex_bridge.interaction_approach import _slot_summary
    item = {"registryName": "example:tagged", "count": 3, "metadata": 1, "hasTag": True}
    row = {"slot": 51, "item": item, "inventoryIndex": 99, "inventorySource": "unavailable",
           "playerInventoryBindingVerified": True, "playerInventoryIndex": 7, "machineInventoryBindingVerified": False}
    assert _slot_summary(row) == {"slot": 51, "item": item, "playerInventoryBindingVerified": True, "playerInventoryIndex": 7}
    assert _slot_summary({"slot": 0, "item": None, "playerInventoryBindingVerified": False}) == {"slot": 0, "item": None}


class PartClient(Client):
    def __init__(self, version=1, **kwargs):
        super().__init__(**kwargs)
        self.part_version = version
        self.wrong_part = False

    async def invoke(self, operation, payload):
        result = await super().invoke(operation, payload)
        if operation == "bridge.health":
            result["interactionPartTargetingVersion"] = self.part_version
            result["nativeCrosshairArrivalVersion"] = 1
        if operation == "player.plan_approach" and result.get("selected"):
            for view, target in zip(result["selected"]["targetViews"], payload["targets"]):
                part = target.get("targetIdentity", {}).get("part")
                if part is not None:
                    view["targetPart"] = {**part, "side": 5} if self.wrong_part else copy.deepcopy(part)
        return result


def mounted_target(side=1):
    return {"x": 0, "y": 64, "z": 0, "targetIdentity": {
        "registryName": "appliedenergistics2:tile.BlockCableBus", "metadata": 0,
        "part": {"side": side, "registryName": "appliedenergistics2:item.ItemMultiPart", "metadata": 440}}}


@pytest.mark.parametrize("side", range(7))
async def test_mounted_part_reaches_combined_use_without_confusing_hit_face(side):
    from minecraft_codex_bridge.interaction_approach import approach_summary
    client = PartClient()
    result = await run(client, targets=[mounted_target(side)], interact=True)
    assert len(client.uses) == 1 and client.uses[0]["side"] == 2
    assert client.uses[0]["targetPart"] == mounted_target(side)["targetIdentity"]["part"]
    assert approach_summary(result)["groups"][0]["selected"]["targetViews"][0]["targetPart"]["side"] == side


@pytest.mark.parametrize("version", [None, False, True, 0, 1.0, 2])
async def test_unknown_part_capability_refuses_before_any_movement_or_use(version):
    client = PartClient(version=version, in_range=False)
    with pytest.raises(BridgeError) as caught:
        await run(client, targets=[mounted_target()], interact=True)
    assert caught.value.code == "INTERACTION_UNAVAILABLE" and not client.moves and not client.uses


async def test_wrong_mounted_part_proof_retains_refusal_without_replay():
    client = PartClient(in_range=False)
    client.wrong_part = True
    with pytest.raises(BridgeError) as caught:
        await run(client, targets=[mounted_target()], interact=True)
    assert not client.moves and not client.uses and not caught.value.details["receipt"]["movementDispatched"]


async def test_teleport_never_receives_walk_or_flight_arrival_targets():
    client = PartClient(in_range=False)
    await run(client, targets=[mounted_target()], strategy="developer_teleport", interact=True)
    assert len(client.moves) == len(client.uses) == 1
    assert "arrivalTargets" not in client.moves[0]


@pytest.mark.parametrize("posture,expected", [("airborne", True), ("client_grounded", False),
    ("server_grounded", False), ("client_not_flying", False), ("server_not_flying", False),
    ("server_missing", False)])
async def test_teleport_endpoint_preserves_only_observed_dual_airborne_flight(posture, expected):
    class TeleportClient(Client):
        def snapshot(self):
            value = super().snapshot()
            value["player"].update(flying=True, onGround=False)
            value["serverControl"]["pose"] = {"flying": True, "onGround": False}
            side = value["player"] if posture.startswith("client") else value["serverControl"]["pose"]
            if posture.endswith("grounded"): side["onGround"] = True
            if posture.endswith("not_flying"): side["flying"] = False
            if posture == "server_missing": value["serverControl"].pop("pose")
            return value
    client = TeleportClient(in_range=False)
    await run(client, strategy="developer_teleport", interact=False)
    plans = [payload for operation, payload in client.calls if operation == "player.plan_approach"]
    assert len(plans) == 2 and all(p["requireFlying"] is expected for p in plans)
    assert len(client.moves) == 1 and client.moves[0]["strategy"] == "developer_teleport"


def test_part_schema_supports_six_mounts_and_center_independent_of_allowed_faces():
    for side in range(7):
        target = mounted_target(side)
        tool_validator("mcb_approach").validate(args(targets=[target], interact=True))
        execution_validator("mcb_navigate").validate({"strategy": "walking", "expectedWorld": "Debug", "expectedSessionId": "session", "dimension": 180, "goal": {"type": "interaction", "targets": [target]}})
        tool_validator("mcb_interact").validate({"action": "use", "x": 0, "y": 64, "z": 0, "side": 2, "targetPart": target["targetIdentity"]["part"]})
    with pytest.raises(ValidationError):
        tool_validator("mcb_interact").validate({"action": "use", "targetPart": mounted_target()["targetIdentity"]["part"]})


@pytest.mark.parametrize("version,slots", [(2, 34), (3, 166)])
async def test_bounded_nonwalking_absence_retains_planner_diagnostics(version, slots):
    class AbsentClient(Client):
        async def invoke(self, operation, payload):
            result = await super().invoke(operation, payload)
            if operation == "player.plan_approach":
                result.update(status="no_candidate", complete=True, selected=None, sharedCandidateVersion=version,
                              nonWalkingCandidateHeights=5, walkingMaxJumpBlocks=1, walkingMaxDropBlocks=3)
                result["coverage"].update(candidateSlots=slots, candidatesExamined=slots)
            return result
    client = AbsentClient()
    with pytest.raises(BridgeError) as caught:
        await run(client, targets=[args()["targets"][0]], strategy="ability_flight")
    assert caught.value.code == "NO_INTERACTION_STAND"
    assert caught.value.details["planning"]["coverage"]["candidateSlots"] == slots
    assert caught.value.details["absenceScope"] == "bounded_candidates_only_not_global_unreachability"
    assert not client.moves


def test_ae_details_are_opt_in_and_disallowed_in_compact_batches():
    tool_validator("mcb_world_inspect").validate({"x": 0, "y": 64, "z": 0, "includeAe": True})
    validator = tool_validator("mcb_world_inspect_many")
    validator.validate({"positions": [{"x": 0, "y": 64, "z": 0}], "includeAe": True})
    assert list(validator.iter_errors({"positions": [{"x": 0, "y": 64, "z": 0}], "includeAe": True, "compact": True}))
