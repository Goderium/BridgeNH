"""Explicit client selection for the conversation that launched this MCP frontend."""
from copy import deepcopy
import os
import uuid

import anyio

from .backend_transport import BackendError
from .connection_binding import binding_environment, binding_status, conversation_id, initial_binding
from .local_config import config_path

PRISM_ROOT_SCHEMA = {"type": "string", "minLength": 1, "maxLength": 2048,
    "description": "Absolute Prism data directory containing instances/ or prismlauncher.cfg; not the executable or an individual instance."}
INSTANCE_NAME_SCHEMA = {"type": "string", "minLength": 1, "maxLength": 256,
    "description": "Instance display name or instance folder name within the selected Prism directory. Ambiguous names are refused."}
CLIENTS_SCHEMA = {"type": "object", "properties": {"prismRoot": PRISM_ROOT_SCHEMA}, "additionalProperties": False}
CONNECTION_SCHEMA = {
    "type": "object", "properties": {
        "action": {"enum": ["status", "bind", "unbind"], "default": "status"},
        "clientId": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "prismRoot": PRISM_ROOT_SCHEMA,
        "instanceName": INSTANCE_NAME_SCHEMA,
        "expectedConversationId": {"type": "string", "minLength": 1, "maxLength": 128},
        "expectedBindingGeneration": {"type": "integer", "minimum": 0},
        "requestId": {"type": "string", "minLength": 1, "maxLength": 128}},
    "additionalProperties": False,
    "oneOf": [
        {"properties": {"action": {"const": "status"}}, "propertyNames": {"enum": ["action"]}},
        {"properties": {"action": {"const": "bind"}},
         "required": ["action", "expectedConversationId", "expectedBindingGeneration", "requestId"],
         "oneOf": [
             {"required": ["prismRoot", "instanceName"], "not": {"required": ["clientId"]}},
             {"required": ["clientId"], "not": {"anyOf": [{"required": ["prismRoot"]}, {"required": ["instanceName"]}]}}]},
        {"properties": {"action": {"const": "unbind"}},
         "propertyNames": {"enum": ["action", "expectedConversationId", "expectedBindingGeneration", "requestId"]},
         "required": ["action", "expectedConversationId", "expectedBindingGeneration", "requestId"]}]}


class ConversationConnections:
    def init_connections(self, environment):
        self.environment = dict(os.environ if environment is None else environment)
        # Resolve only the inventory file location, never its active/default profile.
        path = config_path(self.environment)
        if path.is_file():
            self.environment["MCB_CONFIG_FILE"] = str(path.resolve())
        self.connection = initial_binding(self.environment)
        self.environment = binding_environment(self.environment, self.connection)
        self.connection_records = {}
        self.bind_observation = None

    def connection_status(self):
        return {**binding_status(self.connection), "bindObservation": deepcopy(self.bind_observation),
                "backendState": getattr(self, "state", "starting")}

    async def change_connection(self, args):
        identifier = args["requestId"]
        prior = self.connection_records.get(identifier)
        if prior is not None:
            if prior["args"] != args:
                return self.connection_error("CONNECTION_REQUEST_ID_CONFLICT")
            return deepcopy(prior["result"] or self.connection_error("CONNECTION_CHANGE_IN_PROGRESS"))
        requested_owner = conversation_id(args["expectedConversationId"])
        if requested_owner is None or self.connection["conversationId"] is None and args["action"] != "bind":
            return self.connection_error("CONVERSATION_ID_REQUIRED")
        if self.connection["conversationId"] is not None and requested_owner != self.connection["conversationId"]:
            return self.connection_error("CONVERSATION_MISMATCH")
        if len(self.connection_records) >= 64:
            return self.connection_error("CONNECTION_RECEIPT_CAPACITY")
        if self.gate.locked():
            return self.connection_error("BACKEND_BUSY_NOT_DISPATCHED")
        try:
            self.gate.acquire_nowait()
        except anyio.WouldBlock:
            return self.connection_error("BACKEND_BUSY_NOT_DISPATCHED")
        record = {"args": deepcopy(args), "result": None}
        self.connection_records[identifier] = record
        old, candidate, prepared, retiring = self.child, None, False, False
        reply = None
        with anyio.CancelScope(shield=True):
            try:
                if args["expectedBindingGeneration"] != self.connection["generation"]:
                    raise BackendError("BINDING_GENERATION_CHANGED")
                if self.state != "running" or old is None or old.broken or old.process.returncode is not None:
                    raise BackendError("BACKEND_UNAVAILABLE_NOT_DISPATCHED")
                target = None
                if args['action'] == 'bind':
                    with anyio.fail_after(30):
                        if 'prismRoot' in args and 'instanceName' in args:
                            target = await old.rpc('resolve_client', {key: args[key] for key in ('prismRoot', 'instanceName')})
                        elif args.get('clientId'):
                            inventory = await old.rpc('list_clients')
                            target = next((row for row in inventory['clients'] if row['clientId'] == args['clientId']), None)
                            if target is None:
                                raise BackendError('CLIENT_NOT_LISTED')
                        else:
                            raise BackendError('INVALID_ARGUMENTS')
                target_id = target['clientId'] if target is not None else None
                same_launcher = ('prismRoot' not in args or target is not None
                    and target.get('prismRoot') == self.connection['prismRoot']
                    and target.get('prismExe') == self.connection['prismExe'])
                if target_id == self.connection["clientId"] and same_launcher:
                    reply = {"ok": True, "data": {**self.connection_status(), "changed": False}}
                else:
                    with anyio.fail_after(30):
                        safety = await old.rpc("prepare_connection_change")
                        if safety["blockers"]:
                            raise BackendError("CLIENT_SWITCH_BLOCKED_" + "_AND_".join(safety["blockers"]).upper())
                        prepared = True
                        following = initial_binding(self.environment)
                        following["conversationId"] = self.connection["conversationId"] or requested_owner
                        following["ownerSource"] = self.connection["ownerSource"] or "explicit_bind_request"
                        following["generation"] = self.connection["generation"] + 1
                        if target is not None:
                            following.update(clientId=target_id, instancePath=target["instancePath"],
                                             profiles=target["profiles"], prismExe=target["prismExe"], bindingId=str(uuid.uuid4()),
                                             prismRoot=target.get('prismRoot'), instanceName=target.get('instanceName'))
                        environment = binding_environment(self.environment, following)
                        candidate = await self.candidate(environment=environment)
                        # Selecting a client is not a hidden code/catalog reload.
                        if candidate.snapshot.fingerprint != old.snapshot.fingerprint:
                            raise BackendError("BACKEND_SOURCE_CHANGED_RELOAD_FIRST")
                        observation = None
                        if target is not None:
                            observed = await candidate.rpc("probe_connection")
                            following = observed["binding"]
                            observation = observed["observation"]
                            environment = binding_environment(self.environment, following)
                        retiring = True
                        await old.stop()
                        self.child = None
                        await candidate.rpc("activate")
                        self.child, candidate = candidate, None
                        self.connection = following
                        self.environment = environment
                        self.bind_observation = observation
                        self.generation += 1
                        self.state, self.last_error = "running", None
                        reply = {"ok": True, "data": {**self.connection_status(), "changed": True,
                            "previousWorkerPid": old.process.pid, "workerPid": self.child.process.pid,
                            "backendGeneration": self.generation, "gameActionsReplayed": False,
                            "previousClientStateTransferred": False}}
            except Exception as error:
                code = error.code if isinstance(error, BackendError) else "CONNECTION_CHANGE_FAILED"
                if not retiring and old is not None and not old.broken and old.process.returncode is None:
                    try:
                        if prepared:
                            with anyio.fail_after(3):
                                await old.rpc("activate")
                        self.state = "running"
                    except Exception:
                        self.state = "faulted"
                elif retiring:
                    self.state = "faulted"
                self.last_error = code
                reply = self.connection_error(code)
            finally:
                try:
                    if candidate is not None:
                        await candidate.close()
                finally:
                    record["result"] = deepcopy(reply)
                    self.gate.release()
        return reply

    def connection_error(self, code):
        return {"ok": False, "error": {"code": code, "outcomeUnknown": False, "automaticActionRetries": False},
                "data": {**self.connection_status(), "backendState": self.state}}
