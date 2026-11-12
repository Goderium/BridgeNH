"""Private child of the single MCB MCP service; never registered with Codex."""
from __future__ import annotations

import asyncio
from copy import deepcopy
import os
from pathlib import Path
import sys

import anyio

from .adapter_session import _emit, startup_metadata
from .backend_transport import BackendError, MAX_FRAME, PROTOCOL, decode, encode, fingerprint, runtime_versions
from .backend_recovery import MAX_MOVEMENT_FAILURES, movement_failure
from .backend_state import STATE_VERSION, MAX_FAILED_ACTIONS, action_result, replay_key, validate_state
from .bridge import BridgeClient, BridgeError, Configuration, MAX_REQUEST_BYTES
from .guides import GUIDE_RESOURCES, read_help
from .application.adapter import Adapter
from .application.results import _result, health_result
from .application.descriptions import resource_descriptions, tool_descriptions
from .validation import validate_tool_arguments as _validate
from .tools import TOOL_BY_NAME
from .connection_binding import binding_environment
from .client_connections import client_inventory, named_client, probe_bound_client, switch_blockers

TERMINAL_ORDERS = {"completed", "blocked", "canceled"}


class Worker:
    def __init__(self, client):
        self.client = client
        self.adapter = Adapter(client)
        self.adapter.action_recovery = self.recover_action
        self.mutation_seen = False
        self.uncertain = False
        self.failed_actions = []
        self.movement_failures = []
        self.reconciled_movements = 0
        self.active = False
        self.prepared = False
        self.restored = False
        self.contract = {
            "tools": [t.model_dump(mode="json", by_alias=True, exclude_none=True) for t in tool_descriptions()],
            "resources": [r.model_dump(mode="json", by_alias=True, exclude_none=True) for r in resource_descriptions()],
        }

    def ready(self):
        try:
            runtime = self.client.configuration.runtime_path()
        except BridgeError:
            runtime = None
        metadata = startup_metadata(runtime)
        self.contract["instructions"] = metadata["instructions"]
        return {**metadata, "protocol": PROTOCOL, "pid": os.getpid(), "contract": self.contract, "runtime": runtime_versions(),
                "contractFingerprint": fingerprint(self.contract)}

    async def binding(self):
        """Pin the configured connection, without consulting the game or its state."""
        conversation = self.client.configuration.conversation_binding
        if conversation is not None:
            return {"kind": "mcb_conversation_v1", "conversation": deepcopy(conversation),
                    "connection": {"mode": "unbound"} if conversation["instancePath"] is None else
                    {"mode": "runtime_file", "runtimeFile": str(self.client.configuration.runtime_path().resolve())}}
        try:
            path = self.client.configuration.runtime_path()
        except BridgeError as error:
            if error.code != "RUNTIME_DISCOVERY_FAILED":
                raise
            return {"kind": "mcb_connection_v2", "connection": {"mode": "undiscovered"}}
        if path is not None:
            connection = {"mode": "runtime_file", "runtimeFile": str(path.resolve())}
        else:
            env = self.client.configuration.environ
            connection = {"mode": "explicit_url", "configurationFingerprint": fingerprint(
                {key: env.get(key) for key in ("MCB_URL", "MCB_TOKEN")})}
        return {"kind": "mcb_connection_v2", "connection": connection}

    async def checkpoint(self):
        state = {"version": STATE_VERSION, "orders": deepcopy(list(self.adapter.ae_order_records.values())),
            "mutationSeen": self.mutation_seen, "uncertain": self.uncertain,
            "failedActions": deepcopy(self.failed_actions), "movementFailures": deepcopy(self.movement_failures),
            "reconciledMovements": self.reconciled_movements}
        validate_state(state, _validate)
        return {"state": state, "binding": await self.binding(), "stateFingerprint": fingerprint(state)}

    def remember_unknown(self, name, arguments, result):
        record = {"tool": name, "arguments": deepcopy(arguments), "result": action_result(result)}
        if record in self.failed_actions:
            return
        from .action_recovery import action_id, reconciled
        if any(reconciled(row) and action_id(row) == action_id(record) for row in self.failed_actions):
            # A later identical receipt is a different action, not the already
            # reconciled one. Keep its ID distinct across checkpoint handoff.
            from uuid import uuid4
            record["result"]["actionRecoveryRecordId"] = uuid4().hex
        if len(self.failed_actions) >= MAX_FAILED_ACTIONS:
            resolved = next((row for row in self.failed_actions if reconciled(row)), None)
            if resolved is not None:
                self.failed_actions.remove(resolved)
        self.failed_actions.append(record)
        movement = movement_failure(name, arguments, result)
        if movement is not None and len(self.movement_failures) < MAX_MOVEMENT_FAILURES:
            self.movement_failures.append(movement)
        else:
            self.uncertain = True

    def replay_blocked(self, name, arguments):
        # Existing AE IDs continue through the original guarded state machine:
        # native status is read before any take; start/take_unknown never replay.
        if name == "mcb_ae_order" and (arguments.get("expectedSessionId"), arguments.get("requestId")) in self.adapter.ae_order_records:
            return False
        if self.uncertain and not self.failed_actions:
            return True
        key = replay_key(name, arguments)
        from .action_recovery import reconciled
        return any(not reconciled(record) and replay_key(record["tool"], record["arguments"]) == key for record in self.failed_actions)

    async def recover_action(self, arguments):
        from .action_recovery import manage
        return await manage(self, arguments)

    async def dispatch(self, method, params):
        if method == "list_clients":
            return client_inventory(self.client.configuration.inventory_environ, params.get('prismRoot'))
        if method == 'resolve_client':
            return named_client(params['prismRoot'], params['instanceName'])
        if method == "prepare_connection_change":
            blockers = switch_blockers(self)
            if not blockers:
                await self.checkpoint()  # Validate retained state before considering a switch.
                self.prepared = True
            return {"blockers": blockers}
        if method == "probe_connection":
            if self.active:
                raise BackendError("BACKEND_ALREADY_ACTIVE")
            observation = await probe_bound_client(self.client)
            binding = self.client.configuration.conversation_binding
            binding["sessionIdAtBind"] = observation["sessionId"]
            self.client.configuration.environ = binding_environment(self.client.configuration.environ, binding)
            return {"binding": deepcopy(binding), "observation": observation}
        if method == "activate":
            self.active = True
            self.prepared = False
            return {"active": True}
        if method == "prepare_restart":
            checkpoint = await self.checkpoint()
            self.prepared = True
            return checkpoint
        if method == "restore_state":
            if self.active or self.restored:
                raise BackendError("BACKEND_STATE_ALREADY_ACTIVE")
            state = params.get("state")
            restored = validate_state(state, _validate)
            if params.get("stateFingerprint") != fingerprint(state):
                raise BackendError("BACKEND_STATE_FINGERPRINT_CHANGED")
            binding = await self.binding()
            if binding != params.get("binding"):
                raise BackendError("BACKEND_CONNECTION_CHANGED")
            self.adapter.ae_order_records = restored
            self.mutation_seen = state["mutationSeen"]
            self.uncertain = state["uncertain"]
            self.failed_actions = deepcopy(state["failedActions"])
            self.movement_failures = deepcopy(state["movementFailures"])
            self.reconciled_movements = state["reconciledMovements"]
            self.restored = True
            exported = await self.checkpoint()
            return {"restoredOrders": len(restored), "binding": binding,
                "stateFingerprint": exported["stateFingerprint"], "restoredFailedActions": len(self.failed_actions)}
        if method == "verify_binding":
            binding = await self.binding()
            if binding != params.get("binding"):
                raise BackendError("BACKEND_CONNECTION_CHANGED")
            return {"binding": binding}
        if method == "read_resource":
            topic = GUIDE_RESOURCES.get(params.get("uri"))
            if topic is None:
                raise BackendError("BACKEND_RESOURCE_UNKNOWN")
            return read_help({"topic": topic})
        if method != "call_tool":
            raise BackendError("BACKEND_METHOD_UNKNOWN")
        if not self.active or self.prepared:
            raise BackendError("BACKEND_NOT_ACTIVE")
        name, arguments = params.get("name"), params.get("arguments")
        if not isinstance(name, str) or not isinstance(arguments, dict) or len(encode(params)) > MAX_REQUEST_BYTES:
            raise BackendError("BACKEND_TOOL_REQUEST_INVALID")
        invocation = None
        if name == "mcb_sequence" and "call" in arguments:
            from .dynamic_catalog import resolve_call
            try:
                name, arguments, invocation = resolve_call(self.client.configuration, arguments)
            except BridgeError as error:
                return _result({"ok": False, "error": error.as_dict()}, error=True).model_dump(mode="json", by_alias=True, exclude_none=True)
            # Canonicalize the private request before dispatch so cancellation and
            # exception receipts use the same identity as a direct tool call.
            params["name"], params["arguments"] = name, arguments
        spec = TOOL_BY_NAME.get(name)
        conversation = self.client.configuration.conversation_binding
        if conversation is not None and conversation["instancePath"] is None and spec is not None and name != "mcb_help":
            try:
                _validate(name, arguments)
            except BridgeError as error:
                return _result({"ok": False, "error": error.as_dict()}, error=True).model_dump(mode="json", by_alias=True, exclude_none=True)
            return _result({"ok": False, "error": {"code": "CLIENT_NOT_BOUND",
                "message": "No default binding. List clients and explicitly bind this conversation before game tools.",
                "outcome_unknown": False}, "mcpConnection": self.client.configuration.connection_info()}, error=True).model_dump(mode="json", by_alias=True, exclude_none=True)
        recovery = name == "mcb_action_recovery"
        if spec is not None and not spec.read_only and not recovery:
            if self.replay_blocked(name, arguments):
                from .action_recovery import action_id, reconciled
                return _result({"ok": False, "error": {"code": "BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION",
                    "message": "A prior matching action has unresolved effects; reload does not authorize replay.",
                    "outcome_unknown": False, "replayAllowed": False, "recoveryTool": "mcb_action_recovery",
                    "actionIds": [action_id(row) for row in self.failed_actions if not reconciled(row)
                        and replay_key(row["tool"], row["arguments"]) == replay_key(name, arguments)]}},
                    error=True).model_dump(mode="json", by_alias=True, exclude_none=True)
            from .action_recovery import reconciled
            if sum(not reconciled(row) for row in self.failed_actions) >= MAX_FAILED_ACTIONS:
                raise BackendError("BACKEND_ACTION_RECORD_CAPACITY")
            self.mutation_seen = True
        result = await self.adapter.call_tool(name, arguments)
        if spec is not None and not spec.read_only and not recovery and uncertain_tool_result(result.structuredContent):
            self.remember_unknown(name, arguments, result.structuredContent)
        if name == "mcb_health":
            from .action_recovery import reconciled
            result = health_result(result, self.client, {"pid": os.getpid(), "reloadMode": "supervised_worker",
                "schemaFingerprint": fingerprint(self.contract), "sourceStatus": "frozen_snapshot",
                "reloadRecovery": {"unclassifiedOutcome": self.uncertain,
                    "pendingMovements": deepcopy(self.movement_failures), "reconciledMovements": self.reconciled_movements,
                    "unresolvedActions": sum(not reconciled(row) for row in self.failed_actions),
                    "reconciledActions": sum(reconciled(row) for row in self.failed_actions), "pendingOrders": sum(
                        record.get("phase") not in TERMINAL_ORDERS for record in self.adapter.ae_order_records.values()),
                    "reloadBlockedByGameOutcome": False, "stateVersion": STATE_VERSION,
                    "automaticActionRetries": False}})
        if invocation is not None:
            from .dynamic_catalog import annotate_result
            result = annotate_result(result, invocation)
        return result.model_dump(mode="json", by_alias=True, exclude_none=True)


def uncertain_tool_result(value):
    # Adapter adds localKnowledge after the current action finishes. Its hints
    # describe historical results, not effects of this call. Exclude only that
    # top-level annotation; native data/error and nested receipts stay guarded.
    if isinstance(value, dict):
        value = {key: child for key, child in value.items() if key != "localKnowledge"}
    return uncertain_result(value)


def uncertain_result(value):
    if isinstance(value, dict):
        if value.get("outcome_unknown") is True or value.get("outcomeUnknown") is True:
            return True
        if value.get("code") in {"MALFORMED_RESPONSE", "RESPONSE_TOO_LARGE", "HTTP_ERROR", "REDIRECT_REFUSED"}:
            return True
        return any(uncertain_result(child) for child in value.values())
    return isinstance(value, list) and any(uncertain_result(child) for child in value)


async def run(source, target, client=None):
    client = client or BridgeClient(Configuration(dict(os.environ)))
    worker = Worker(client)
    last_id = 0
    current = {}

    async def execute(request):
        identifier = request["id"]
        replied = False
        try:
            with anyio.CancelScope() as scope:
                current["scope"] = scope
                if current.get("cancelled"):
                    scope.cancel()
                try:
                    await anyio.lowlevel.checkpoint()
                    result = await worker.dispatch(request["method"], request["params"])
                    _emit(target, {"id": identifier, "ok": True, "result": result})
                    replied = True
                except (BackendError, BridgeError) as error:
                    _emit(target, {"id": identifier, "ok": False, "error": {"code": error.code,
                        "outcomeUnknown": getattr(error, "outcome_unknown", False)}})
                    replied = True
                except Exception:
                    worker.uncertain = True
                    if request["method"] == "call_tool":
                        worker.remember_unknown(request["params"].get("name"), request["params"].get("arguments", {}),
                            {"ok": False, "error": {"code": "BACKEND_EXECUTION_UNKNOWN", "outcomeUnknown": True}})
                    _emit(target, {"id": identifier, "ok": False, "error": {
                        "code": "BACKEND_EXECUTION_UNKNOWN", "outcomeUnknown": True}})
                    replied = True
            if scope.cancel_called and not replied:
                spec = TOOL_BY_NAME.get(request["params"].get("name")) if request["method"] == "call_tool" else None
                unknown = request["method"] == "call_tool" and (spec is None or not spec.read_only)
                if unknown:
                    worker.remember_unknown(request["params"].get("name"), request["params"].get("arguments", {}),
                        {"ok": False, "error": {"code": "BACKEND_REQUEST_CANCELLED", "outcomeUnknown": True}})
                _emit(target, {"id": identifier, "ok": False, "error": {
                    "code": "BACKEND_REQUEST_CANCELLED", "outcomeUnknown": unknown}})
        finally:
            current.clear()

    try:
        _emit(target, worker.ready())
        async with anyio.create_task_group() as group:
            while True:
                # Keep stdin responsive to cancellation while one original Adapter request executes.
                raw = await anyio.to_thread.run_sync(lambda: source.readline(MAX_FRAME + 1), abandon_on_cancel=True)
                if not raw:
                    if current.get("scope") is not None:
                        current["scope"].cancel()
                    return
                request = decode(raw)
                if isinstance(request, dict) and set(request) == {"cancel"} and type(request["cancel"]) is int:
                    if request["cancel"] == current.get("id"):
                        current["cancelled"] = True
                        if current.get("scope") is not None:
                            current["scope"].cancel()
                    continue
                if (not isinstance(request, dict) or set(request) != {"id", "method", "params"}
                        or type(request["id"]) is not int or not last_id < request["id"] <= 9223372036854775807
                        or not isinstance(request["method"], str) or not isinstance(request["params"], dict) or current):
                    raise BackendError("BACKEND_INVALID_REQUEST")
                last_id = request["id"]
                if request["method"] == "shutdown":
                    _emit(target, {"id": last_id, "ok": True, "result": {"stopped": True}})
                    return
                current["id"] = last_id
                group.start_soon(execute, request)
    finally:
        await client.aclose()


def main():
    asyncio.run(run(sys.stdin.buffer, sys.stdout.buffer))


if __name__ == "__main__":
    main()
