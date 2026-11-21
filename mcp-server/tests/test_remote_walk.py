"""Public remote movement through the MCP adapter and serialized HTTP transport."""
import pytest

from test_operation_policy import current_remote, operations


BINDING = {"expectedSessionId": "session", "expectedWorld": "fixture-world", "dimension": 180}
POSITION = {"x": 12.5, "y": 64.0, "z": 5.5}
BLOCK = {"x": 14, "y": 64, "z": 5, "allowedSides": [4],
         "targetIdentity": {"registryName": "minecraft:log", "metadata": 1}}
NATIVE_BLOCK = {**{key: BLOCK[key] for key in ("x", "y", "z", "allowedSides")}, **BLOCK["targetIdentity"]}
SAMPLE = {"source": "remote_server_tick", "sessionId": "session", "connectionId": "connection",
          "dimension": 180, "worldTick": 101}


@pytest.fixture
def walk(current_remote):
    adapter, state = current_remote
    health = state["health"]
    health["capabilities"].append("player.remote_walk")
    health["serverBridge"]["server"]["groundWalkVersion"] = 1
    health["background"] = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
    health["profile"] = "developer"
    health["operationPolicy"] = {
        "available": True, "selected": {"mode": "survival_efficient", "movement": "path", "readOnly": False},
        "effective": {"machineRead": True, "remoteGroundWalkVersion": 1, "movementImplementation": "walking",
                      "navigationExecutionSupported": True},
    }
    state["machine"] = {**SAMPLE, "status": "arrived", "waypoints": [POSITION.copy()], "arrivalConfirmed": True,
                        "start": {**POSITION, "x": 10.5}, "clientArrival": POSITION.copy(), "serverConfirmed": True,
                        "serverArrival": {**SAMPLE, "worldTick": 107, "feet": POSITION.copy(), "reached": True},
                        "movementDispatched": True, "nativePulses": 4, "taskId": "walk"}
    return adapter, state


def dispatches(state):
    return [row for row in state["calls"] if row["operation"] != "bridge.health"]


def single_walk(state):
    rows = dispatches(state)
    assert [row["operation"] for row in rows] == ["player.remote_walk"]
    return rows[0]["args"]


@pytest.mark.parametrize("goal,details", [(False, False), (True, True)])
async def test_navigate_binds_exact_point_and_returns_confirmed_server_arrival(walk, goal, details):
    adapter, state = walk
    point = {"goal": {"type": "point", **POSITION}} if goal else POSITION
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **point, "includeDetails": details,
                                                      "requireSurvivalActor": True, "timeout_ms": 5000})
    assert not result.isError, result.structuredContent
    assert single_walk(state) == {**BINDING, "expectedConnectionId": "connection", "destination": POSITION,
                                  "requireSurvivalActor": True, "timeout_ms": 5000}
    data = result.structuredContent["data"]
    assert data["status"] == "arrived" and data["serverArrival"]["feet"] == POSITION
    if details:
        assert data["waypoints"] == [POSITION]
    else:
        assert "waypoints" not in data and data["waypointCount"] == 1


def visible_arrival(state):
    state["machine"]["serverArrival"].update(blockVisible=True, side=4)


async def test_interaction_navigation_selects_one_block_without_issuing_use(walk):
    adapter, state = walk
    visible_arrival(state)
    result = await adapter.call_tool("mcb_navigate", {**BINDING, "goal": {"type": "interaction", "targets": [BLOCK]}})
    assert not result.isError, result.structuredContent
    assert single_walk(state) == {**BINDING, "expectedConnectionId": "connection", "block": NATIVE_BLOCK, "timeout_ms": 30000}


@pytest.mark.parametrize("accepted", [True, False])
async def test_approach_preserves_the_result_of_one_native_use(walk, accepted):
    adapter, state = walk
    visible_arrival(state)
    interaction = {"accepted": accepted, "airUseFallback": False, "viewRestored": False,
                   "blockTarget": {key: BLOCK[key] for key in ("x", "y", "z")} | {"side": 4}}
    state["machine"]["interaction"] = interaction
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "interact": True, "timeout_ms": 35000})
    assert not result.isError, result.structuredContent
    assert single_walk(state) == {**BINDING, "expectedConnectionId": "connection", "timeout_ms": 35000,
                                  "block": NATIVE_BLOCK, "preview": False, "interact": True}
    assert result.structuredContent["data"]["interaction"] == interaction


def preview(state):
    state["health"]["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    state["machine"] = {**SAMPLE, "status": "planned", "start": {**POSITION, "x": 10.5},
                        "waypoints": [POSITION.copy()], "movementDispatched": False}


async def test_approach_preview_keeps_manual_control_and_reports_only_a_plan(walk):
    adapter, state = walk
    preview(state)
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "move": False, "includeDetails": True})
    assert not result.isError, result.structuredContent
    assert single_walk(state) == {**BINDING, "expectedConnectionId": "connection", "timeout_ms": 30000,
                                  "block": NATIVE_BLOCK, "preview": True, "interact": False}
    assert result.structuredContent["data"] == state["machine"]


@pytest.mark.parametrize("tool", ["mcb_navigate", "mcb_approach"])
@pytest.mark.parametrize("section,key,value", [
    ("server", "groundWalkVersion", 0), ("server", "groundWalkVersion", True),
    ("server", "operatorReads", False), ("serverBridge", "ready", False),
    ("operationPolicy", "available", False), ("effective", "remoteGroundWalkVersion", 0),
    ("effective", "remoteGroundWalkVersion", True), ("effective", "machineRead", False),
    ("effective", "movementImplementation", "ability_flight"), ("serverBridge", "connectionId", ""),
])
async def test_stale_advertisement_missing_authority_or_binding_prevents_dispatch(walk, tool, section, key, value):
    adapter, state = walk
    health = state["health"]
    sections = {"server": health["serverBridge"]["server"], "effective": health["operationPolicy"]["effective"],
                "operationPolicy": health["operationPolicy"], "serverBridge": health["serverBridge"]}
    sections[section][key] = value
    args = {**BINDING, **(POSITION if tool == "mcb_navigate" else {"targets": [BLOCK]})}
    result = await adapter.call_tool(tool, args)
    assert result.isError, result.structuredContent
    error = result.structuredContent["error"]
    assert error["original_action_executed"] is False and error["outcome_unknown"] is False
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("request_change", [{"expectedSessionId": "earlier-session"}, {"dimension": 181}])
async def test_stale_requested_session_or_dimension_refuses_before_takeover(walk, request_change):
    adapter, state = walk
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION, **request_change})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_READ_CONTEXT_CHANGED"
    assert not dispatches(state)


@pytest.mark.parametrize("tool,options", [
    ("mcb_navigate", {**POSITION, "waypoints": [POSITION]}),
    ("mcb_navigate", {**POSITION, "lookAt": POSITION}),
    ("mcb_navigate", {**POSITION, "finishFlying": True}),
    ("mcb_navigate", {"goal": {"type": "box", "min": POSITION, "max": POSITION}}),
    ("mcb_approach", {"targets": [BLOCK, {**BLOCK, "x": 15}]}),
    ("mcb_approach", {"targets": [{**BLOCK, "targetIdentity": {"registryName": "minecraft:log"}}]}),
    ("mcb_approach", {"targets": [{**BLOCK, "hitPoint": {"x": 0, "y": .5, "z": .5}}]}),
    ("mcb_approach", {"targets": [BLOCK], "interact": True, "openGui": {}}),
])
async def test_local_only_constraints_are_refused_without_a_partial_walk(walk, tool, options):
    adapter, state = walk
    result = await adapter.call_tool(tool, {**BINDING, **options})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_ARGUMENT_UNSUPPORTED"
    assert not dispatches(state)


@pytest.mark.parametrize("acknowledged", [True, False])
async def test_explicit_segmentation_needs_native_acknowledgement_without_replay(walk, acknowledged):
    adapter, state = walk
    if acknowledged:
        state["machine"]["segmented"] = True
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION, "segmented": True, "timeout_ms": 60000})
    assert single_walk(state) == {**BINDING, "expectedConnectionId": "connection", "destination": POSITION,
                                  "segmented": True, "timeout_ms": 60000}
    if acknowledged:
        assert not result.isError and result.structuredContent["data"]["segmented"] is True
    else:
        assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"


@pytest.mark.parametrize("tool,options", [("mcb_navigate", POSITION), ("mcb_approach", {"targets": [BLOCK], "move": False})])
async def test_missing_client_executor_never_enters_a_local_route(walk, tool, options):
    adapter, state = walk
    state["health"]["capabilities"].remove("player.remote_walk")
    result = await adapter.call_tool(tool, {**BINDING, **options})
    assert result.isError and result.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
    assert not dispatches(state)


async def test_failed_background_handoff_blocks_the_remote_walk(walk):
    adapter, state = walk
    state["health"]["background"] = state["machine"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "interact": True})
    assert result.isError and result.structuredContent["error"]["code"] == "TAKEOVER_NOT_CONFIRMED"
    assert [row["operation"] for row in dispatches(state)] == ["client.background"]
    assert dispatches(state)[0]["args"] == {"enabled": True}


@pytest.mark.parametrize("server_calls,valid", [(0, True), (1, False)])
async def test_client_preview_is_negotiated_and_has_no_server_probe(walk, server_calls, valid):
    adapter, state = walk
    state["health"]["operationPolicy"]["effective"]["clientGroundPlanVersion"] = 1
    preview(state)
    state["machine"].update(source="client_world_tick", plannerSource="client_world_tick", serverProbes=server_calls)
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "move": False})
    assert result.isError == (not valid)
    assert single_walk(state)["clientPreview"] is True


@pytest.mark.parametrize("change", [{"sessionId": "other"}, {"connectionId": "other"}, {"dimension": 0},
                                    {"source": "client"}, {"status": "planned"}, {"arrivalConfirmed": False},
                                    {"serverConfirmed": False}, {"serverArrival": None},
                                    {"clientArrival": None}, {"clientArrival": {**POSITION, "x": 15.5}}])
async def test_insufficient_or_cross_session_arrival_evidence_is_not_replayed(walk, change):
    adapter, state = walk
    state["machine"].update(change)
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    assert result.structuredContent["error"]["outcome_unknown"] is True
    single_walk(state)


@pytest.mark.parametrize("feet,endpoint", [
    ({**POSITION, "x": 13}, POSITION),
    ({**POSITION, "x": 13}, {**POSITION, "x": 13}),
    ({**POSITION, "y": 64.13}, POSITION),
    ({**POSITION, "x": 12.64, "z": 5.64}, POSITION),
    (POSITION, {**POSITION, "z": 6}),
])
async def test_arrival_must_match_both_the_requested_point_and_the_last_waypoint(walk, feet, endpoint):
    adapter, state = walk
    state["machine"]["waypoints"] = [endpoint]
    state["machine"]["serverArrival"]["feet"] = feet
    state["machine"]["clientArrival"] = feet.copy()
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    assert result.structuredContent["error"]["outcome_unknown"] is True
    single_walk(state)


async def test_native_settling_tolerance_allows_a_small_geometric_difference(walk):
    adapter, state = walk
    state["machine"]["serverArrival"]["feet"] = {**POSITION, "x": 12.62, "y": 64.11, "z": 5.60}
    state["machine"]["clientArrival"] = {**POSITION, "x": 12.61, "y": 64.10, "z": 5.60}
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION})
    assert not result.isError, result.structuredContent
    single_walk(state)


async def test_local_and_server_feet_must_agree_even_when_both_are_near_the_goal(walk):
    adapter, state = walk
    state["machine"]["clientArrival"] = {**POSITION, "x": 12.43}
    state["machine"]["serverArrival"]["feet"] = {**POSITION, "x": 12.57}
    result = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    assert result.structuredContent["error"]["outcome_unknown"] is True
    single_walk(state)


@pytest.mark.parametrize("change", [{"sessionId": "old"}, {"connectionId": "other"}, {"reached": False},
                                    {"feet": {"x": True, "y": 64, "z": 5}}, {"blockVisible": False}, {"side": 3}])
async def test_block_approach_requires_a_current_visible_allowed_face_at_the_actual_feet(walk, change):
    adapter, state = walk
    visible_arrival(state)
    state["machine"]["serverArrival"].update(change)
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK]})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    assert result.structuredContent["error"]["outcome_unknown"] is True
    single_walk(state)


@pytest.mark.parametrize("interaction", [None, {}, {"accepted": True, "airUseFallback": False,
                                                     "blockTarget": {"x": 100, "y": 64, "z": 5, "side": 4}}])
async def test_interaction_receipt_must_identify_the_requested_block_without_replay(walk, interaction):
    adapter, state = walk
    visible_arrival(state)
    state["machine"]["interaction"] = interaction
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "interact": True})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    assert result.structuredContent["error"]["outcome_unknown"] is True
    single_walk(state)


@pytest.mark.parametrize("change", [{"sessionId": "other"}, {"movementDispatched": True}])
async def test_stale_or_contradictory_preview_is_not_taken_as_a_valid_plan(walk, change):
    adapter, state = walk
    preview(state)
    state["machine"].update(change)
    result = await adapter.call_tool("mcb_approach", {**BINDING, "targets": [BLOCK], "move": False})
    assert result.isError and result.structuredContent["error"]["code"] == "REMOTE_WALK_RECEIPT_INVALID"
    single_walk(state)


async def test_remote_world_name_is_checked_before_takeover(walk):
    adapter, state = walk
    state["health"]["serverBridge"]["server"]["world"] = {"worldName": "world", "worldFolder": "world"}
    state["health"]["background"] = {"enabled": False}
    reply = await adapter.call_tool("mcb_navigate", {**BINDING, **POSITION, "expectedWorld": "server-address"})
    assert reply.structuredContent["error"]["code"] == "REMOTE_READ_CONTEXT_CHANGED"
    assert dispatches(state) == []
