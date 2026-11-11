"""Choose current execution paths before dispatch; never retry or submit alternatives."""

from typing import Any


LOCAL_ONLY = frozenset({
    "dev.teleport", "player.navigate", "player.plan_approach", "player.flight", "player.flight_check",
    "player.place_block", "player.replace_owned_block", "structure.survival_build", "world.structure",
    "inventory.supply", "machine.set_working", "ae.material_source", "machine.capture", "ae.order",
})


def environment(health: dict[str, Any]) -> str:
    readiness = health.get("readiness")
    if not isinstance(readiness, dict):
        return "unknown"
    if readiness.get("localIntegratedServer") is True:
        return "local_integrated"
    if readiness.get("remoteServerReady") is True or readiness.get("stage") == "remote_world_ready":
        return "remote_server"
    if (readiness.get("worldLoaded") is True and readiness.get("playerPresent") is True
            and readiness.get("integratedServerPresent") is False):
        return "remote_server"
    return "unknown"


def guidance(health: dict[str, Any]) -> dict[str, Any]:
    remote = environment(health) == "remote_server"
    bridge = health.get("serverBridge")
    bridge = bridge if isinstance(bridge, dict) else {}
    return {
        "version": 1, "environment": environment(health),
        "machineRead": "mcb_machine_inspect (automatically routed to server.observe)" if remote else "mcb_machine_inspect",
        "remoteQa": bridge.get("qaExecutionSupported") is True if remote else None,
        "loadedCode": "Use executingBridge.buildFingerprint for the executing bridge build; mcb_mods loadedSource is FML selection. Disk hashes/candidates alone never prove loaded code.",
        "machineObservation": "mcb_machine_inspect and Waila are live/display observations, not one frozen production round. When needed, use explicit developer mcb_machine_capture with includeNbt=true and page the same captureId. NBT covers serialized fields, not every transient field; inspect completeness and source.",
        "recipeQuery": health.get("recipeQueryAccess", {"available": False, "reason": "This bridge has no split catalog capability advertisement."}),
        "teleport": "approach developer_teleport uses normal /tp and needs existing safe support or verified dual flight. mcb_teleport is a separate explicit protected operation; it may enable existing creative flight permission before moving. No automatic fallback or retry.",
        "flight": "Require current player.flying and player.serverFlight.available/flying, not creative or allowFlying alone. "
                  "Cross-dimension commands reset flight: transfer only to known safe support, rebind the new connection/dimension, "
                  "then acquire and verify flight before leaving support. Unverified/unsupported teleport is not arrival.",
        "supportPriority": {
            "singleCell": ["existing_safe_attachment", "dimension_verified_setblock_keep", "native_angel_block", "verified_worldedit"],
            "multipleCells": ["existing_safe_attachment", "verified_worldedit", "dimension_verified_setblock_keep", "native_angel_block"],
            "selection": "Select the first currently available, authorized method before dispatch; unknown is not available. "
                         "A ready angel block outranks a command needing additional access. WE needs proven installation, "
                         "permission and player-dimension binding; clear selection after every operation.",
        },
        "fallback": "Skip a method refused before execution only when the next method is independently authorized and available. "
                    "Do not resubmit the same approval. Scope-wide denial stops the operation. Timeout/unknown/partial effects "
                    "require state inspection before choosing another method; never automatically execute alternatives.",
        "commands": "Minecraft 1.7.10 has no vanilla /execute. RCON coordinate commands must not be assumed to target the "
                    "player's dimension. mcb_chat uses native Forge/client chat in singleplayer, LAN and servers; the game decides "
                    "permissions. sent is submission, not execution: inspect native chat feedback and actual state. "
                    "For leave/exit use health.lifecycleTarget.expectedWorld; native disconnect needs no server bridge or OP.",
        "manualTest": "Use existing native interactions and GUI; do not add a mod/QA test, redeploy or restart for a one-off reproduction.",
    }


def flight_guidance(snapshot: dict[str, Any]) -> dict[str, Any]:
    world, player = snapshot.get("world"), snapshot.get("player")
    world = world if isinstance(world, dict) else {}
    player = player if isinstance(player, dict) else {}
    server = player.get("serverFlight")
    server = server if isinstance(server, dict) else {}
    age, dimension = server.get("ageMillis"), world.get("dimension")
    singleplayer = world.get("singleplayer")
    expected_source = ("integrated_server_tick" if singleplayer is True else
                       "remote_server_tick" if singleplayer is False else None)
    confirmed = (snapshot.get("worldLoaded") is True and type(dimension) is int
                 and type(server.get("dimension")) is int and server["dimension"] == dimension
                 and type(player.get("health")) in (int, float) and player["health"] > 0
                 and player.get("flying") is True and player.get("allowFlying") is True
                 and player.get("onGround") is False and server.get("available") is True
                 and server.get("flying") is True and server.get("allowFlying") is True
                 and server.get("onGround") is False and type(age) in (int, float) and 0 <= age <= 1000
                 and expected_source is not None and server.get("source") == expected_source)
    if singleplayer is False:
        bridge = snapshot.get("serverBridge")
        confirmed = (confirmed and isinstance(bridge, dict) and bridge.get("ready") is True
                     and isinstance(bridge.get("connectionId"), str) and bool(bridge["connectionId"])
                     and server.get("connectionId") == bridge["connectionId"]
                     and type(bridge.get("dimension")) is int and bridge["dimension"] == dimension)
    return {
        "dualFlightConfirmed": bool(confirmed), "targetSpaceVerified": False,
        "validOnlyInCurrentDimensionAndConnection": True, "crossDimensionRequiresSafeLanding": True,
        "nextStep": "Use a supported movement path with destination-space checks; revalidate after dimension/connection changes."
                    if confirmed else "Stay on verified support and acquire actual dual flight before any unguarded elevated move; allowFlying/creative alone is insufficient.",
    }


def route(operation: str, args: dict[str, Any], health: dict[str, Any], *, guarded: bool = False):
    from .bridge import BridgeError

    if operation == "recipes.query":
        access = health.get("recipeQueryAccess")
        public = args.get("mode", "catalog") == "catalog" and (
            "map" in args or isinstance(args.get("cursor"), str) and args["cursor"].startswith("client:"))
        if isinstance(access, dict) and access.get("version") == 1:
            if public:
                if access.get("publicCatalog") is not True:
                    raise BridgeError("RECIPE_CATALOG_UNAVAILABLE", "Load a client world before reading its public NEI recipe registry; no recipe request sent.",
                                      original_action_executed=False, outcome_unknown=False)
                return operation, args
            allowed = access.get("remoteMachineBackend" if environment(health) == "remote_server" else "localMachineBackend")
            if allowed is not True:
                raise BridgeError("RECIPE_AUTHORITY_UNAVAILABLE", "Machine/backend queries need the owned integrated developer context (including LAN host) or authenticated remote operator. Public map queries remain available; LAN state was not changed.",
                                  original_action_executed=False, outcome_unknown=False, recipeQueryAccess=access)
        elif health.get("developerAvailable") is not True or health.get("profile") != "developer":
            raise BridgeError("RECIPE_AUTHORITY_UNAVAILABLE", "This bridge has no separate public catalog support and currently lacks developer authority. Update the bridge or use existing NEI reads; no recipe request sent.",
                              original_action_executed=False, outcome_unknown=False)
    if operation == "machine.capture" and environment(health) != "remote_server":
        access = health.get("machineCaptureAccess")
        if not isinstance(access, dict) or access.get("version") != 1 or access.get("available") is not True:
            raise BridgeError("MACHINE_CAPTURE_UNAVAILABLE", "Frozen NBT capture requires the advertised current owned integrated developer context; no serialization request sent.",
                              original_action_executed=False, outcome_unknown=False)
    if operation == "chat.send":
        text = args.get("message", "").strip()
        root = text.split(maxsplit=1)[0] if text else ""
        if root == "/execute" and health.get("gameVersion") == "1.7.10":
            raise BridgeError("COMMAND_VERSION_UNSUPPORTED", "Minecraft 1.7.10 has no vanilla /execute; choose an existing version-compatible path before dispatch.",
                              original_action_executed=False, outcome_unknown=False)
    if environment(health) != "remote_server":
        return operation, args
    if operation in LOCAL_ONLY:
        raise BridgeError("REMOTE_OPERATION_UNSUPPORTED", "This operation needs the local integrated server; it was blocked before dispatch. Use current remote reads and native GUI/interaction instead.",
                          operation=operation, original_action_executed=False, outcome_unknown=False,
                          alternatives=["mcb_server_observe", "mcb_interact", "mcb_gui_widget_action"],
                          flightRequirement="Current dual flying is required before an unsupported destination; transfer dimensions onto known safe support first.",
                          automaticFallback=False)
    if operation != "machine.inspect":
        return operation, args
    if guarded:
        bridge = health.get("serverBridge", {})
        if bridge.get("ready") is True and bridge.get("server", {}).get("guardedObservationVersion") == 1:
            return operation, args
        raise BridgeError("REMOTE_CONTEXT_UNSUPPORTED", "A guarded local machine read cannot be converted into an unguarded remote receipt. Use mcb_server_observe with its exact remote connection binding.",
                          original_action_executed=False, outcome_unknown=False)
    if any(key in args for key in ("outputQueries", "inputOffset", "inputLimit")):
        raise BridgeError("REMOTE_ARGUMENT_UNSUPPORTED", "Remote machine reads do not implement explicit outputQueries or input-list paging arguments; the request was not weakened or sent.",
                          original_action_executed=False, outcome_unknown=False)
    bridge = health.get("serverBridge")
    server = bridge.get("server") if isinstance(bridge, dict) else None
    reads = server.get("readOperations") if isinstance(server, dict) else None
    if not (isinstance(bridge, dict) and bridge.get("ready") is True and isinstance(server, dict)
            and server.get("operatorReads") is True and isinstance(reads, list)
            and all(isinstance(value, str) for value in reads) and "machine" in reads):
        raise BridgeError("REMOTE_READ_UNAVAILABLE", "An authenticated operator server machine-read connection is required; no local queue or action was used.",
                          original_action_executed=False, outcome_unknown=False)
    session, connection, dimension = health.get("sessionId"), bridge.get("connectionId"), bridge.get("dimension")
    if not (isinstance(session, str) and 1 <= len(session) <= 128 and isinstance(connection, str)
            and 1 <= len(connection) <= 128 and type(dimension) is int):
        raise BridgeError("REMOTE_READ_UNAVAILABLE", "The current remote session, connection or dimension binding is incomplete.",
                          original_action_executed=False, outcome_unknown=False)
    if "dimension" in args and (type(args["dimension"]) is not int or args["dimension"] != dimension):
        raise BridgeError("REMOTE_DIMENSION_CHANGED", "The requested machine is not in the current advertised remote dimension; no read was sent.",
                          original_action_executed=False, outcome_unknown=False)
    return "server.observe", {**args, "kind": "machine", "expectedSessionId": session,
                              "expectedConnectionId": connection, "dimension": dimension}


def machine_result(data: dict[str, Any], binding: dict[str, Any]) -> dict[str, Any]:
    from .bridge import BridgeError

    if (data.get("source") != "remote_server_tick" or data.get("sessionId") != binding["expectedSessionId"]
            or data.get("connectionId") != binding["expectedConnectionId"]
            or type(data.get("dimension")) is not int or data["dimension"] != binding["dimension"]
            or any(type(data.get(axis)) is not int or data[axis] != binding[axis] for axis in ("x", "y", "z"))
            or not isinstance(data.get("machine"), dict)):
        raise BridgeError("REMOTE_READ_CONTEXT_CHANGED", "The remote machine reply does not match this exact session/connection/dimension/position; no retry.",
                          original_action_executed=False, outcome_unknown=False)
    # Preserve the familiar machine shape and all remote evidence without duplicating the machine payload.
    return {**data["machine"], **{key: data[key] for key in ("source", "sessionId", "connectionId", "dimension", "serverTick") if key in data},
            "remoteReadReceipt": {key: value for key, value in data.items() if key != "machine"}}


async def movement_arguments(client, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """Resolve saved intent once before a workflow; never retry a failed movement."""
    from .bridge import BridgeError
    health = await client.invoke("bridge.health", {})
    route("player.navigate" if name == "mcb_navigate" else "player.plan_approach", arguments, health)
    policy = health.get("operationPolicy", {})
    if policy.get("available") is not True:
        raise BridgeError("POLICY_UNAVAILABLE", "Correct policies.json and reload before movement.")
    strategy = policy.get("effective", {}).get("movementImplementation")
    if strategy not in {"walking", "ability_flight", "developer_teleport"}:
        raise BridgeError("POLICY_UNAVAILABLE", "Bridge did not advertise a supported movement implementation.")
    if name in {"mcb_recipe_once", "mcb_blueprint_build"} and arguments.get("actualSurvival") is not True and strategy != "developer_teleport":
        raise BridgeError("MOVEMENT_POLICY_UNSUPPORTED", "The legacy creative composite requires authorized tp policy; use generic native operations for path movement. No preparation or action was submitted.",
                          original_action_executed=False, outcome_unknown=False)
    # Native interaction-goal search currently has a grounded executor. Preserve its existing safety contract.
    if name == "mcb_navigate" and arguments.get("goal", {}).get("type") == "interaction":
        strategy = "walking"
    key = "movementStrategy" if name in {"mcb_recipe_once", "mcb_blueprint_build"} else "strategy"
    return {**arguments, key: strategy}
