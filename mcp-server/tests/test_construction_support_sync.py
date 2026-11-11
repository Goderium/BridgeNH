"""R16's exact read error; intermediate client synchronization is synthetic."""

import copy
import time

import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError, READ_OPERATIONS
from minecraft_codex_bridge.construction_tasks import run_blueprint_build
from test_construction_tasks import CONTROLLER, SUPPORT, declaration, key, native_construction
from test_native_approach_tasks import construction_task, install_planner


R16_ERROR = {"code": "target_unverified", "message": "Client and server approach block identities do not agree.",
    "outcome_unknown": False}
R16_TARGET = {"x": -478, "y": 3, "z": -175}
R16_SUPPORT = {"x": -478, "y": 2, "z": -175, "dimension": 180, "source": "integrated_server",
    "registryName": "minecraft:stone", "metadata": 0}


def own_support(task, state, point=None):
    point = {**CONTROLLER, "y": CONTROLLER["y"] - 1} if point is None else point
    state["blocks"][key(point)] = SUPPORT.copy()
    record = {"position": point, "placementConfirmed": True, "hasTile": False, "block": SUPPORT.copy()}
    task.supports.append(record)
    return record


def install_delay(client, state, monkeypatch, count, *, error_factory=None, after_error=None, after_result=None):
    install_planner(client, state)
    original = client.call
    errors = []
    async def call(operation, args, **options):
        if operation == "player.plan_approach" and args["intent"] == "place_block" and len(errors) < count:
            error = BridgeError(**R16_ERROR, requestId="observed-read-" + str(len(errors))) if error_factory is None else error_factory()
            errors.append(error)
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args), "observedError": error.as_dict()})
            if after_error is not None:
                after_error()
            raise error
        result = await original(operation, args, **options)
        if after_result is not None:
            after_result(operation, result)
        return result
    monkeypatch.setattr(client, "call", call)
    return errors


def operations(state):
    return [call["operation"] for call in state["calls"]]


def sync_phase(task):
    return next(phase for phase in task.phases if phase["phase"] == "supported_placement_sync_observation")


async def test_exact_r16_coordinates_and_server_support_with_synthetic_next_complete_plan(native_construction, monkeypatch):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, 1)
    task = construction_task(client, state)
    task.dimension = 180
    task.args["dimension"], task.args["controller"] = 180, R16_TARGET.copy()
    support = own_support(task, state, {axis: R16_SUPPORT[axis] for axis in ("x", "y", "z")})
    support["block"] = {field: R16_SUPPORT[field] for field in ("registryName", "metadata")}
    state["blocks"][key(support["position"])] = support["block"].copy()
    original = client.call
    async def captured_dimension(operation, args, **options):
        result = await original(operation, args, **options)
        if operation == "world.inspect_many":
            result["dimension"] = 180
            for row in result["results"]:
                row["data"]["dimension"] = 180
        return result
    monkeypatch.setattr(client, "call", captured_dimension)
    await task.supported_placement_approach(R16_TARGET, 6)
    phase = sync_phase(task)
    assert phase["target"] == R16_TARGET
    assert phase["observations"][0]["serverBlocks"][1] == R16_SUPPORT
    assert errors[0].code == R16_ERROR["code"] and errors[0].details["outcome_unknown"] is False
    assert phase["status"] == "completed" and phase["attempts"] == 2
    assert all(operation in READ_OPERATIONS for operation in operations(state))


@pytest.mark.parametrize("delay", [1, 3, 7])
async def test_delayed_complete_planning_then_places_once(native_construction, monkeypatch, delay):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, delay)
    task = construction_task(client, state)
    support = own_support(task, state)
    await task.place(CONTROLLER, 6, "place")
    phase = sync_phase(task)
    assert phase["status"] == "completed" and phase["attempts"] == delay + 1
    assert phase["firstError"]["requestId"] == errors[0].details["requestId"]
    assert len(phase["observations"]) == delay
    assert all(sample["serverBlocks"][0]["registryName"] == "minecraft:air" for sample in phase["observations"])
    assert all(sample["serverBlocks"][1]["registryName"] == support["block"]["registryName"] for sample in phase["observations"])
    first_write = next(index for index, call in enumerate(state["calls"]) if call["operation"] not in READ_OPERATIONS)
    assert all(call["operation"] in READ_OPERATIONS for call in state["calls"][:first_write])
    assert operations(state).count("player.plan_approach") == delay + 1
    assert operations(state).count("player.place_block") == len(state["placements"]) == 1
    assert "remain unknown" in phase["scope"]


async def test_persistent_mismatch_is_bounded_and_preserves_first_error(native_construction, monkeypatch):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, 20)
    task = construction_task(client, state)
    own_support(task, state)
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place")
    assert failure.value is errors[0]
    phase = sync_phase(task)
    assert phase["status"] == "unconfirmed" and phase["attempts"] == 8
    assert len(errors) == len(phase["observations"]) == 8
    assert [sample["error"]["requestId"] for sample in phase["observations"]] == ["observed-read-" + str(index) for index in range(8)]
    assert failure.value.details["syncPendingObservation"] is phase
    assert all(operation in READ_OPERATIONS for operation in operations(state))
    assert not state["placements"]


async def test_total_task_deadline_bounds_read_observation(native_construction, monkeypatch):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, 20)
    task = construction_task(client, state)
    own_support(task, state)
    task.deadline = time.monotonic() + .025
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place")
    assert failure.value is errors[0] and len(errors) == 1
    assert sync_phase(task)["status"] == "unconfirmed"
    assert all(operation in READ_OPERATIONS for operation in operations(state))


@pytest.mark.parametrize("fault", ["missing", "unconfirmed", "has_tile", "cleaning", "unknown", "nonadjacent", "already_attempted"])
async def test_wait_requires_actual_confirmed_owned_adjacent_support_and_no_placement_attempt(native_construction, monkeypatch, fault):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, 20)
    task = construction_task(client, state)
    support = own_support(task, state)
    record = None
    if fault == "missing":
        task.supports.clear()
    elif fault == "unconfirmed":
        support["placementConfirmed"] = False
    elif fault == "has_tile":
        support["hasTile"] = True
    elif fault == "cleaning":
        support["cleanupAttempted"] = True
    elif fault == "unknown":
        support["outcomeUnknown"] = True
    elif fault == "nonadjacent":
        support["position"] = {**support["position"], "z": support["position"]["z"] + 1}
    else:
        record = {"placementAttempted": True}
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place", record)
    assert failure.value is errors[0]
    assert operations(state) == ["player.plan_approach"] and not task.phases


@pytest.mark.parametrize("error", [BridgeError("target_unverified", R16_ERROR["message"]),
    BridgeError("target_unverified", R16_ERROR["message"], outcome_unknown=True),
    BridgeError("target_unverified", R16_ERROR["message"], outcome_unknown=0),
    BridgeError("target_unverified", "Client and server loaded tile identities are not both available.", outcome_unknown=False),
    BridgeError("target_unverified", "Client and server current GregTech metaTileId/metaClass do not agree.", outcome_unknown=False),
    BridgeError("SESSION_CHANGED", R16_ERROR["message"], outcome_unknown=False),
    BridgeError(**R16_ERROR, approachReceipt={})])
async def test_other_errors_and_unknown_outcomes_never_wait(native_construction, monkeypatch, error):
    client, state = native_construction
    install_delay(client, state, monkeypatch, 20, error_factory=lambda: error)
    task = construction_task(client, state)
    own_support(task, state)
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place")
    assert failure.value is error and operations(state) == ["player.plan_approach"] and not task.phases


@pytest.mark.parametrize("fault", ["target", "target_tile", "support", "metadata", "tile", "float_xyz", "bool_dimension", "wrong_source"])
async def test_fresh_server_drift_refuses_before_another_plan(native_construction, monkeypatch, fault):
    client, state = native_construction
    support_point = {**CONTROLLER, "y": 2}
    def drift():
        if fault in ("target", "target_tile"):
            state["blocks"][key(CONTROLLER)] = SUPPORT.copy() if fault == "target" else {"registryName": "minecraft:air", "metadata": 0, "tileClass": "Foreign"}
        elif fault in ("support", "metadata", "tile"):
            state["blocks"][key(support_point)].update({"registryName": "minecraft:stone"} if fault == "support" else
                {"metadata": 1} if fault == "metadata" else {"tileClass": "Foreign"})
    def malformed(operation, result):
        if operation == "world.inspect_many" and fault in ("float_xyz", "bool_dimension", "wrong_source"):
            result["results"][1]["data"].update({"x": float(support_point["x"])} if fault == "float_xyz" else
                {"dimension": False} if fault == "bool_dimension" else {"source": "client"})
    errors = install_delay(client, state, monkeypatch, 20, after_error=drift, after_result=malformed)
    task = construction_task(client, state)
    own_support(task, state)
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place")
    assert failure.value is errors[0]
    assert sync_phase(task)["status"] == "refused"
    assert "revalidationError" in sync_phase(task)
    assert operations(state) == ["player.plan_approach", "world.inspect_many"]


async def test_every_owned_adjacent_support_must_be_preserved(native_construction, monkeypatch):
    client, state = native_construction
    errors = install_delay(client, state, monkeypatch, 20)
    task = construction_task(client, state)
    own_support(task, state)
    second = own_support(task, state, {**CONTROLLER, "x": CONTROLLER["x"] + 1})
    state["blocks"][key(second["position"])]["metadata"] = 1
    with pytest.raises(BridgeError) as failure:
        await task.place(CONTROLLER, 6, "place")
    assert failure.value is errors[0] and sync_phase(task)["status"] == "refused"
    assert len(sync_phase(task)["observations"][0]["serverBlocks"]) == 3
    assert operations(state) == ["player.plan_approach", "world.inspect_many"]


@pytest.mark.parametrize("fault", ["partial", "flight", "ray", "source"])
async def test_success_after_wait_still_requires_original_complete_planner_proof(native_construction, monkeypatch, fault):
    client, state = native_construction
    def corrupt(operation, result):
        if operation == "player.plan_approach":
            if fault == "partial":
                result["partial"] = True
            elif fault == "flight":
                result["serverFlight"]["flying"] = False
            elif fault == "ray":
                result["selected"]["clientRay"]["verified"] = False
            else:
                result["current"]["sourceItem"]["count"] = 63
    install_delay(client, state, monkeypatch, 1, after_result=corrupt)
    task = construction_task(client, state)
    own_support(task, state)
    with pytest.raises(BridgeError):
        await task.place(CONTROLLER, 6, "place")
    assert operations(state).count("player.plan_approach") == 2
    assert all(operation in READ_OPERATIONS for operation in operations(state)) and not state["placements"]


async def test_native_placement_unknown_outcome_is_never_replanned_or_replayed(native_construction, monkeypatch):
    client, state = native_construction
    install_delay(client, state, monkeypatch, 1)
    task = construction_task(client, state)
    own_support(task, state)
    original = client.call
    async def unknown(operation, args, **options):
        if operation == "player.place_block":
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            options["trace"].update(dispatched=True, request_id="unknown-native-placement", outcome_unknown=True)
            raise BridgeError(**{**R16_ERROR, "outcome_unknown": True})
        return await original(operation, args, **options)
    monkeypatch.setattr(client, "call", unknown)
    with pytest.raises(BridgeError):
        await task.place(CONTROLLER, 6, "place")
    assert operations(state).count("player.plan_approach") == 2
    assert operations(state).count("player.place_block") == 1
    assert task.last_action["requestId"] == "unknown-native-placement" and task.last_action["outcomeUnknown"] is True


async def test_complete_command_support_build_does_not_replay_support_or_native_placement(native_construction, monkeypatch):
    client, state = native_construction
    install_planner(client, state)
    original = client.call
    failures = 0
    async def delayed(operation, args, **options):
        nonlocal failures
        if operation == "player.plan_approach" and args["intent"] == "place_block" \
                and key(args) == key(CONTROLLER) and failures < 2:
            failures += 1
            state["calls"].append({"operation": operation, "args": copy.deepcopy(args)})
            raise BridgeError(**R16_ERROR)
        return await original(operation, args, **options)
    monkeypatch.setattr(client, "call", delayed)
    result = await run_blueprint_build(client, declaration(supportMode="command"), anyio.Lock())
    assert result["status"] == "completed", result
    assert result["formationVerified"] is True
    commands = [call["args"]["message"] for call in state["calls"] if call["operation"] == "chat.send"]
    assert commands.count("/setblock -44 2 125 minecraft:stone 0 keep") == 1
    assert commands.count("/setblock -44 2 125 minecraft:air 0 replace") == 1
    assert len(state["placements"]) == 5 and state["construct_count"] == state["rechecks"] == 1
    phase = result["placementSyncObservations"][0]
    assert phase["status"] == "completed" and phase["attempts"] == 3
