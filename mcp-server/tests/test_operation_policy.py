from copy import deepcopy
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration, request_context
from minecraft_codex_bridge.operation_policy import environment, flight_guidance, guidance
from minecraft_codex_bridge.server import Adapter


@pytest.fixture
async def current_remote():
    state = {
        "calls": [],
        "health": {
            "protocolVersion": 1, "gameVersion": "1.7.10", "sessionId": "session",
            "readiness": {"remoteServerReady": True, "worldLoaded": True, "playerPresent": True,
                          "integratedServerPresent": False},
            "capabilities": ["machine.inspect", "server.observe", "dev.teleport", "player.flight",
                             "player.navigate", "chat.send", "client.background"],
            "background": {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True},
            "serverBridge": {"ready": True, "connectionId": "connection", "dimension": 180,
                             "qaExecutionSupported": True,
                             "server": {"operatorReads": True, "readOperations": ["self", "blocks", "machine"]}},
        },
        "machine": {"sessionId": "session", "connectionId": "connection", "dimension": 180,
                    "source": "remote_server_tick", "serverTick": "500", "x": 400, "y": 135, "z": 401,
                    "loaded": True, "inventory": {"slots": []},
                    "machine": {"processing": {"structureFormed": True, "active": False},
                                "outputStorage": {"amount": "9223372036854775807"}}},
    }

    def respond(request):
        body = json.loads(request.content)
        state["calls"].append(body)
        data = state["health"] if body["operation"] == "bridge.health" else state["machine"]
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "policy-test-token"}),
                          transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


def operations(state):
    return [call["operation"] for call in state["calls"]]


async def test_machine_read_routes_once_and_keeps_shape_exact_quantities_and_remote_evidence(current_remote):
    adapter, state = current_remote
    args = {"x": 400, "y": 135, "z": 401, "componentOffset": 32, "componentLimit": 64}
    reply = await adapter.call_tool("mcb_machine_inspect", args)
    assert not reply.isError
    assert operations(state) == ["bridge.health", "server.observe"]
    assert state["calls"][-1]["args"] == {**args, "kind": "machine", "dimension": 180,
                                          "expectedSessionId": "session", "expectedConnectionId": "connection"}
    data = reply.structuredContent["data"]
    assert data["processing"]["structureFormed"] is True
    assert data["outputStorage"]["amount"] == "9223372036854775807"
    assert data["remoteReadReceipt"]["inventory"] == {"slots": []}
    assert "machine" not in data["remoteReadReceipt"]
    assert args == {"x": 400, "y": 135, "z": 401, "componentOffset": 32, "componentLimit": 64}


async def test_routed_read_uses_effective_capability_not_a_local_only_capability(current_remote):
    adapter, state = current_remote
    state["health"]["capabilities"] = ["server.observe"]
    assert not (await adapter.call_tool("mcb_machine_inspect", {"x": 400, "y": 135, "z": 401})).isError
    assert operations(state) == ["bridge.health", "server.observe"]


@pytest.mark.parametrize("mutation", [
    lambda value: value.update(ready=False),
    lambda value: value.update(connectionId=""),
    lambda value: value.update(dimension=True),
    lambda value: value["server"].update(operatorReads=False),
    lambda value: value["server"].update(readOperations=["self"]),
])
async def test_invalid_remote_binding_never_enters_local_queue(current_remote, mutation):
    adapter, state = current_remote
    mutation(state["health"]["serverBridge"])
    reply = await adapter.call_tool("mcb_machine_inspect", {"x": 400, "y": 135, "z": 401})
    assert reply.isError and reply.structuredContent["error"]["code"] == "REMOTE_READ_UNAVAILABLE"
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("args,code", [
    ({"dimension": 0}, "REMOTE_DIMENSION_CHANGED"),
    ({"outputQueries": [{"kind": "fluid", "fluidName": "water"}]}, "REMOTE_ARGUMENT_UNSUPPORTED"),
])
async def test_remote_read_never_weakens_explicit_request(current_remote, args, code):
    adapter, state = current_remote
    reply = await adapter.call_tool("mcb_machine_inspect", {"x": 400, "y": 135, "z": 401, **args})
    assert reply.isError and reply.structuredContent["error"]["code"] == code
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("change", [{"sessionId": "other"}, {"connectionId": "other"}, {"dimension": 0},
                                    {"dimension": True}, {"x": 399}, {"source": "client"}, {"machine": None}])
async def test_remote_reply_must_match_exact_owner_context_without_retry(current_remote, change):
    adapter, state = current_remote
    state["machine"].update(change)
    reply = await adapter.call_tool("mcb_machine_inspect", {"x": 400, "y": 135, "z": 401})
    assert reply.isError and reply.structuredContent["error"]["code"] == "REMOTE_READ_CONTEXT_CHANGED"
    assert operations(state) == ["bridge.health", "server.observe"]


async def test_guarded_local_read_is_not_downconverted(current_remote):
    adapter, state = current_remote
    # BridgeClient health is itself guard-aware. A mocked health scope keeps the
    # test focused on refusing a local AFTER contract rather than replacing it.
    async def health_or_fail(operation, args, **kwargs):
        state["calls"].append({"operation": operation})
        assert operation == "bridge.health"
        return deepcopy(state["health"])
    adapter.client.call = health_or_fail
    with request_context({"version": 1, "expectedSessionId": "session", "expectedProfile": "developer",
                          "expectedWorld": "world", "requireBackground": True}):
        reply = await adapter.call_tool("mcb_machine_inspect", {"x": 400, "y": 135, "z": 401})
    assert reply.structuredContent["error"]["code"] == "REMOTE_CONTEXT_UNSUPPORTED"
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("tool,args", [
    ("mcb_teleport", {"x": 400.5, "y": 135, "z": 399.5, "expectedWorld": "world"}),
    ("mcb_flight", {"action": "set", "flying": True, "expectedWorld": "world"}),
    ("mcb_navigate", {"x": 400.5, "y": 135, "z": 399.5, "expectedWorld": "world", "expectedSessionId": "session", "dimension": 180}),
])
async def test_remote_local_only_control_refuses_before_takeover_or_action(current_remote, tool, args):
    adapter, state = current_remote
    reply = await adapter.call_tool(tool, args)
    assert reply.isError, reply.structuredContent
    error = reply.structuredContent["error"]
    assert error["code"] == "REMOTE_OPERATION_UNSUPPORTED"
    assert error["original_action_executed"] is False and error["outcome_unknown"] is False
    assert error["automaticFallback"] is False
    assert operations(state) == ["bridge.health"]


async def test_command_version_checked_before_dispatch(current_remote):
    adapter, state = current_remote
    reply = await adapter.call_tool("mcb_chat", {"message": "/execute player ~ ~ ~ setblock ~ ~ ~ stone"})
    assert reply.structuredContent["error"]["code"] == "COMMAND_VERSION_UNSUPPORTED"
    assert operations(state) == ["bridge.health"]


@pytest.mark.parametrize("command", ["/help", "/setblock 0 64 0 stone", "//set 1"])
@pytest.mark.parametrize("local", [False, True])
async def test_native_player_commands_reach_game_permissions_without_operator_or_server_bridge(current_remote, command, local):
    adapter, state = current_remote
    state["health"].update(profile="player", developerAvailable=False, serverBridge={"ready": False})
    state["health"]["readiness"] = {"worldLoaded": True, "playerPresent": True,
                                  "integratedServerPresent": local, "localIntegratedServer": local}
    state["health"]["background"] = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
    state["machine"] = {"sent": True, "executionConfirmed": False, "source": "native_player_chat_packet"}
    reply = await adapter.call_tool("mcb_chat", {"message": command})
    assert not reply.isError
    assert reply.structuredContent["data"]["executionConfirmed"] is False
    assert operations(state) == ["bridge.health", "chat.send"]
    assert state["calls"][-1]["args"] == {"message": command}


@pytest.mark.parametrize("tool", ["mcb_leave_world", "mcb_exit"])
async def test_native_remote_lifecycle_needs_no_server_bridge_or_profile(current_remote, tool):
    adapter, state = current_remote
    state["health"].update(profile="player", developerAvailable=False, serverBridge={"ready": False})
    state["health"]["capabilities"] += ["client.leave_world", "client.exit"]
    state["health"]["readiness"] = {"worldLoaded": True, "playerPresent": True, "integratedServerPresent": False}
    state["health"]["background"] = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
    args = {"expectedSessionId": "session", "expectedWorld": "connection:native-opaque-target"}
    assert not (await adapter.call_tool(tool, args)).isError
    assert operations(state) == ["bridge.health", "client.leave_world" if tool == "mcb_leave_world" else "client.exit"]
    assert state["calls"][-1]["args"] == args


def test_lan_host_ownership_wins_over_remote_handshake_metadata():
    assert environment({"readiness": {"localIntegratedServer": True, "remoteServerReady": True}}) == "local_integrated"


async def test_remote_local_bridge_command_is_still_available(current_remote):
    adapter, state = current_remote
    state["health"]["background"] = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
    assert not (await adapter.call_tool("mcb_chat", {"message": "/mcb agent off"})).isError
    assert operations(state) == ["bridge.health", "chat.send"]


async def test_local_machine_read_keeps_original_dispatch(current_remote):
    adapter, state = current_remote
    state["health"]["readiness"] = {"localIntegratedServer": True}
    args = {"x": 400, "y": 135, "z": 401, "outputQueries": [{"kind": "fluid", "fluidName": "water"}]}
    reply = await adapter.call_tool("mcb_machine_inspect", args)
    assert not reply.isError
    assert operations(state) == ["bridge.health", "machine.inspect"]
    assert state["calls"][-1]["args"] == args


async def test_startup_health_has_ranked_options_and_safe_approval_boundary(current_remote):
    adapter, state = current_remote
    reply = await adapter.call_tool("mcb_health", {})
    policy = reply.structuredContent["data"]["agentGuidance"]
    assert policy["environment"] == "remote_server" and policy["remoteQa"] is True
    assert policy["supportPriority"]["singleCell"].index("native_angel_block") < policy["supportPriority"]["singleCell"].index("verified_worldedit")
    assert policy["supportPriority"]["multipleCells"][1] == "verified_worldedit"
    assert "independently authorized" in policy["fallback"] and "Scope-wide denial stops" in policy["fallback"]
    assert "Cross-dimension" in policy["flight"] and "not creative or allowFlying alone" in policy["flight"]
    assert operations(state) == ["bridge.health"]


def test_unknown_runtime_is_not_invented_as_local_or_remote():
    assert environment({}) == "unknown"
    assert environment({"serverBridge": {"ready": False}}) == "unknown"
    assert guidance({})["environment"] == "unknown"


def flying_snapshot():
    return {"worldLoaded": True, "world": {"dimension": 180, "singleplayer": False},
            "player": {"health": 20, "flying": True, "allowFlying": True, "onGround": False,
                       "serverFlight": {"available": True, "flying": True, "allowFlying": True,
                                        "onGround": False, "source": "remote_server_tick", "dimension": 180,
                                        "connectionId": "connection", "ageMillis": 20}},
            "serverBridge": {"ready": True, "connectionId": "connection", "dimension": 180}}


def test_flight_summary_requires_actual_dual_state_and_never_proves_destination():
    data = flight_guidance(flying_snapshot())
    assert data["dualFlightConfirmed"] is True and data["targetSpaceVerified"] is False
    assert data["crossDimensionRequiresSafeLanding"] is True


@pytest.mark.parametrize("change", [{"available": False}, {"flying": False}, {"allowFlying": False},
                                    {"onGround": True}, {"ageMillis": 1001}, {"ageMillis": True},
                                    {"dimension": 0}, {"connectionId": "prior"}, {"source": "client"}])
def test_stale_or_reset_server_flight_is_not_confirmed(change):
    snapshot = flying_snapshot()
    snapshot["player"]["serverFlight"].update(change)
    assert flight_guidance(snapshot)["dualFlightConfirmed"] is False


@pytest.mark.parametrize("change", [{"flying": False, "creative": True}, {"health": 0}, {"onGround": True},
                                    {"allowFlying": False}])
def test_creative_permission_is_not_actual_flight(change):
    snapshot = flying_snapshot()
    snapshot["player"].update(change)
    assert flight_guidance(snapshot)["dualFlightConfirmed"] is False


@pytest.mark.parametrize("mode,source,confirmed", [
    (False, "integrated_server_tick", False),
    (None, "remote_server_tick", False),
    (True, "remote_server_tick", False),
    (True, "integrated_server_tick", True),
])
def test_flight_source_must_match_known_connection_mode(mode, source, confirmed):
    snapshot = flying_snapshot()
    snapshot["world"]["singleplayer"] = mode
    snapshot["player"]["serverFlight"]["source"] = source
    assert flight_guidance(snapshot)["dualFlightConfirmed"] is confirmed
