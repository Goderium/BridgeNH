"""Bounded, authenticated localhost transport. Mutations are never retried."""

from __future__ import annotations

import json
import logging
import math
import os
import time
import uuid
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlsplit

import anyio
import httpx

from .call_timing import parse_timing_headers, record_rpc
from .knowledge import observe_response
from .local_config import LocalConfigError, configured_environment
from .connection_binding import read_binding, binding_status
from .backend_transport import BackendError
from .runtime_discovery import RuntimeDiscoveryError, discover_runtime_candidates, select_runtime
from .survival_checkpoints import survival_failure_fields

MAX_REQUEST_BYTES = 65_536
MAX_RESPONSE_BYTES = 1_048_576
MAX_IMAGE_BYTES = 8_388_608
MAX_SCREENSHOT_RESPONSE_BYTES = 12_000_000
READ_OPERATIONS = frozenset({
    "bridge.health", "client.snapshot", "client.window", "gui.inspect", "inventory.inspect", "baubles.inspect",
    "server.observe", "structure.goal", "world.inspect", "world.scan", "world.structure", "gui.tooltip", "nei.inspect", "nei.view", "nei.bookmarks", "research.read", "recipes.query", "client.screenshot",
    "client.diagnostics", "input.bindings", "client.mods", "client.messages", "client.recording", "registry.items",
    "registry.item", "registry.variants", "world.inspect_many", "world.tiles", "client.worlds",
    "gui.widgets", "ae.inspect", "ae.material_source", "machine.inspect", "waila.inspect", "world.entities", "world.spatial", "player.plan_approach", "state.diff",
    "qa.catalog", "qa.status", "qa.results", "machine.capture",
})

CONTEXT_READS = frozenset({"bridge.health", "client.messages", "inventory.inspect", "baubles.inspect", "gui.inspect", "gui.widgets", "registry.item",
    "structure.goal", "world.inspect", "world.inspect_many", "world.spatial", "player.plan_approach", "world.structure", "machine.inspect", "ae.material_source"})
CONTEXT_ACTIONS = frozenset({"player.hotbar", "player.interact", "player.place_block", "structure.survival_build", "player.look",
    "player.flight", "player.navigate", "chat.send", "gui.close", "gui.open_inventory", "gui.open_baubles", "gui.open_pause_menu", "gui.widget_action", "gui.slot_click", "gui.transfer_whole_stack", "player.replace_owned_block"})
_OWNED_REPLACEMENT_STAGES = frozenset({"prepare", "before_dig", "attack_plan", "move_before_dig",
    "await_move_before_dig", "native_dig", "after_dig", "place_plan", "move_before_place",
    "await_move_before_place", "native_place", "after_place", "final_observation", "completed"})
_REQUEST_CONTEXT: ContextVar[dict[str, Any] | None] = ContextVar("bridge_request_context", default=None)
_CAPABILITY_SCOPE: ContextVar[dict[str, Any] | None] = ContextVar("bridge_capability_scope", default=None)


@contextmanager
def capability_scope():
    """One goal-call lifetime only; never caches dynamic observations or permissions."""
    token = _CAPABILITY_SCOPE.set({})
    try:
        yield
    finally:
        _CAPABILITY_SCOPE.reset(token)


@contextmanager
def request_context(value: dict[str, Any] | None):
    """Internal transport scope, transparent to existing operation/receipt wrappers."""
    token = _REQUEST_CONTEXT.set(value)
    try:
        yield
    finally:
        _REQUEST_CONTEXT.reset(token)


class _ContextData(dict):
    def __init__(self, data: dict[str, Any], context: dict[str, Any]):
        super().__init__(data)
        self.context = context


def response_context(value: dict[str, Any]) -> dict[str, Any] | None:
    return value.context if isinstance(value, _ContextData) else None


class BridgeError(Exception):
    def __init__(self, code: str, message: str, **details: Any):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details

    def as_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, **self.details}


def placement_failure_fields(details: dict[str, Any]) -> dict[str, Any]:
    """Keep the complete bounded native placement checkpoint, without inferring server effects."""
    fields = survival_failure_fields(details)
    required = {"stage", "failureCheckpoint", "nativeSubmitted", "callsStarted", "callsCompleted", "callbackInFlight",
        "partial", "callbackPreemptible", "automaticReplayAllowed", "batches", "knownReceipt"}
    if fields and (set(fields) != required or type(details.get("outcome_unknown")) is not bool
        or details["outcome_unknown"] is not fields["nativeSubmitted"]
        or fields["partial"] is not fields["nativeSubmitted"]
        or (fields["callsStarted"] > 0) is not fields["nativeSubmitted"]
        or fields["callbackInFlight"] is not (fields["callsStarted"] > fields["callsCompleted"])):
        raise ValueError("Incomplete or contradictory native placement failure checkpoint")
    return fields


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    def finite_float(value: str) -> float:
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("Non-finite number")
        return number

    return json.loads(
        raw.decode("utf-8"), object_pairs_hook=_unique_object,
        parse_float=finite_float,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite number")),
    )


def redact(value: Any, token: str) -> Any:
    if isinstance(value, str):
        return value.replace(token, "<redacted>")
    if isinstance(value, list):
        return [redact(item, token) for item in value]
    if isinstance(value, dict):
        return {str(redact(key, token)): redact(item, token) for key, item in value.items()}
    return value


@dataclass(frozen=True, repr=False)
class Credentials:
    base_url: str
    token: str
    instance_path: str | None = None
    session_id: str | None = None


class Configuration:
    """Runtime files are reread for every HTTP request, including wait polls.

    Supervised MCP requires explicit conversation binding. Standalone adapters
    retain explicit paths and legacy discovery. A restart of the same client
    rotates credentials; another instance never inherits a bound connection.
    """

    def __init__(self, environ: Mapping[str, str] | None = None):
        try:
            original = os.environ if environ is None else environ
            self.inventory_environ = dict(original)
            self.conversation_binding = read_binding(original)
            resolved = dict(original) if self.conversation_binding is not None else configured_environment(environ, auto=environ is None)
            if self.conversation_binding is not None:
                # Offline diagnostics, launch validation and helpers also consume these
                # selectors. They must see the bound target, never an old launch default.
                for key in ("MCB_PROFILE", "MCB_INSTANCE_PATH", "MCB_RUNTIME_FILE", "MCB_URL", "MCB_TOKEN", "MCB_PRISM_EXE"):
                    resolved.pop(key, None)
                selected = self.conversation_binding
                if selected["instancePath"] is not None:
                    resolved["MCB_INSTANCE_PATH"] = selected["instancePath"]
                    resolved["MCB_RUNTIME_FILE"] = str(Path(selected["instancePath"]) / ".minecraft/config/codexbridge/runtime.json")
                    if selected["prismExe"]:
                        resolved["MCB_PRISM_EXE"] = selected["prismExe"]
            # Preserve the existing live mapping contract for explicit URL/token rotation.
            # A profile-derived target is instead resolved once and pinned to this adapter.
            self.environ = original if resolved == dict(original) else resolved
        except LocalConfigError as error:
            raise BridgeError("CONFIGURATION_ERROR", str(error)) from error
        except BackendError as error:
            raise BridgeError(error.code, "Invalid conversation binding; no default client will be selected.") from None
        self._discovered_path: Path | None = None

    def runtime_path(self) -> Path | None:
        if self.conversation_binding is not None:
            instance = self.conversation_binding["instancePath"]
            if instance is None:
                raise BridgeError("CLIENT_NOT_BOUND", "This conversation has no client. Use mcb_clients, then mcb_connection(action=bind).")
            return Path(instance) / ".minecraft/config/codexbridge/runtime.json"
        runtime_file = self.environ.get("MCB_RUNTIME_FILE", "").strip()
        if runtime_file and runtime_file.casefold() not in {"auto", "discover"}:
            return Path(runtime_file)
        instance = self.environ.get("MCB_INSTANCE_PATH", "").strip()
        if instance:
            return Path(instance) / ".minecraft" / "config" / "codexbridge" / "runtime.json"
        # Explicit URL/token remains a supported connection mode.  Discovery
        # is the default only when no direct credentials were supplied.
        if not runtime_file and ("MCB_URL" in self.environ or "MCB_TOKEN" in self.environ):
            return None
        try:
            if self._discovered_path is None:
                self._discovered_path = select_runtime(dict(self.environ)).path
            return self._discovered_path
        except RuntimeDiscoveryError as error:
            raise BridgeError("RUNTIME_DISCOVERY_FAILED", str(error)) from None

    def connection_info(self) -> dict[str, Any]:
        """Return safe connection provenance for health diagnostics."""
        if self.conversation_binding is not None:
            info = binding_status(self.conversation_binding)
            if not info["bound"]:
                return {**info, "mode": "unbound", "selection": "none"}
            return {**info, "mode": "runtime_file", "selection": "explicit_conversation_binding",
                    "runtimeFile": str(self.runtime_path()),
                    "bindingScope": "this conversation; same instance across client restart; never fall back to another client"}
        path = self.runtime_path()
        if path is None:
            return {"mode": "explicit_url"}
        runtime_value = self.environ.get("MCB_RUNTIME_FILE", "").strip().casefold()
        pinned = bool(self.environ.get("MCB_INSTANCE_PATH")) or runtime_value not in {"", "auto", "discover"}
        info = {"mode": "runtime_file", "runtimeFile": str(path.resolve()), "instancePath": str(path.resolve().parents[3])}
        if not pinned:
            all_candidates = discover_runtime_candidates(dict(self.environ))
            candidates = all_candidates[:8]
            info["selection"] = "discovered_then_pinned"
            info["candidates"] = [str(candidate.path) for candidate in candidates]
            info["candidateCount"] = len(all_candidates)
        else:
            info["selection"] = "explicit"
        info["bindingScope"] = "instance_path; credentials reread on restart; never switch instances"
        return info

    def read(self) -> Credentials:
        runtime_file = self.runtime_path()
        instance_path = session_id = None
        if runtime_file is not None:
            try:
                with runtime_file.open("rb") as stream:
                    raw = stream.read(8_193)
                if len(raw) > 8_192:
                    raise ValueError("Runtime file too large")
                config = strict_json(raw)
                if not isinstance(config, dict) or type(config.get("protocolVersion")) is not int:
                    raise ValueError("Invalid protocolVersion")
                if config["protocolVersion"] != 1:
                    raise BridgeError("PROTOCOL_VERSION", "The runtime file uses an unsupported protocol version.")
                base_url, token = config.get("baseUrl"), config.get("token")
                instance_path, session_id = config.get("instancePath"), config.get("sessionId")
                if self.conversation_binding is not None and (instance_path is None or session_id is None):
                    raise BridgeError("BINDING_IDENTITY_UNCONFIRMED", "The bound runtime no longer identifies its instance/session; no request was sent.")
                if instance_path is not None or session_id is not None:
                    if not isinstance(instance_path, str) or not isinstance(session_id, str) or not 1 <= len(session_id) <= 128:
                        raise ValueError("Invalid instance binding")
                    if Path(instance_path).resolve() != runtime_file.resolve().parents[3]:
                        raise BridgeError("INSTANCE_MISMATCH", "The runtime file belongs to a different Minecraft instance.")
            except BridgeError:
                raise
            except (OSError, ValueError, RecursionError):
                raise BridgeError(
                    "CONFIGURATION_ERROR", f"Cannot read a valid bridge runtime file at {runtime_file}. Start Minecraft with the bridge mod or set MCB_RUNTIME_FILE explicitly."
                ) from None
        else:
            base_url, token = self.environ.get("MCB_URL"), self.environ.get("MCB_TOKEN")
        try:
            if not isinstance(base_url, str) or len(base_url) > 256:
                raise ValueError("Invalid URL")
            url = urlsplit(base_url)
            if (
                url.scheme != "http" or url.hostname != "127.0.0.1"
                or url.username is not None or url.password is not None
                or url.path not in ("", "/") or url.query or url.fragment
                or any(ord(char) <= 32 or ord(char) >= 127 for char in base_url)
                or (url.port is not None and not 1 <= url.port <= 65_535)
            ):
                raise ValueError("Non-local URL")
            if not isinstance(token, str) or not 1 <= len(token) <= 512:
                raise ValueError("Missing token")
            if any(ord(char) < 33 or ord(char) > 126 for char in token):
                raise ValueError("Invalid token")
        except (ValueError, TypeError):
            raise BridgeError(
                "CONFIGURATION_ERROR",
                "Use MCB_RUNTIME_FILE or both MCB_URL and MCB_TOKEN. Only an http://127.0.0.1 origin is allowed.",
            ) from None
        return Credentials(base_url.rstrip("/"), token, instance_path, session_id)


class BridgeClient:
    def __init__(
        self, configuration: Configuration | None = None, *,
        timeout: float = 10.0, transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.configuration = configuration or Configuration()
        self.timeout = timeout
        self.http = httpx.AsyncClient(
            transport=transport, trust_env=False, follow_redirects=False,
            timeout=httpx.Timeout(timeout, connect=min(timeout, 2.0), pool=min(timeout, 2.0)),
            limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
        )

    async def aclose(self) -> None:
        await self.http.aclose()

    async def _cancel(self, credentials: Credentials, request_id: str) -> str:
        """Cancel the original session's request; never use rotated credentials here."""
        status = "unconfirmed"
        with anyio.CancelScope(shield=True):
            with anyio.move_on_after(2.0):
                try:
                    async with self.http.stream(
                        "POST", credentials.base_url + "/v1/cancel",
                        content=json.dumps({"requestId": request_id}).encode("utf-8"),
                        headers={"Authorization": "Bearer " + credentials.token, "Content-Type": "application/json"},
                        timeout=httpx.Timeout(2.0),
                    ) as response:
                        if response.status_code != 200 or response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                            return status
                        raw = bytearray()
                        async for chunk in response.aiter_bytes():
                            raw.extend(chunk)
                            if len(raw) > 4_096:
                                return status
                    envelope = strict_json(bytes(raw))
                    if (
                        isinstance(envelope, dict) and envelope.get("requestId") == request_id
                        and envelope.get("ok") is True and isinstance(envelope.get("data"), dict)
                        and envelope["data"].get("status") in {"cancelled", "already_running", "not_found"}
                    ):
                        status = envelope["data"]["status"]
                except (httpx.HTTPError, ValueError, TypeError, RecursionError):
                    pass
        return status

    async def call(self, operation: str, args: dict[str, Any], *, expected_credentials: Credentials | None = None,
                   trace: dict[str, Any] | None = None) -> dict[str, Any]:
        started_ns = time.perf_counter_ns()
        telemetry = {}
        try:
            return await self._call(operation, args, expected_credentials=expected_credentials, trace=trace, telemetry=telemetry)
        finally:
            record_rpc(operation, time.perf_counter_ns() - started_ns, telemetry.get("server"), telemetry.get("responseBytes"))

    async def _call(self, operation: str, args: dict[str, Any], *, expected_credentials: Credentials | None = None,
                    trace: dict[str, Any] | None = None, telemetry: dict[str, Any] | None = None) -> dict[str, Any]:
        credentials = self.configuration.read()
        if expected_credentials is not None and credentials != expected_credentials:
            raise BridgeError("SESSION_CHANGED", "Runtime connection changed during the bounded workflow; later operations were blocked.", outcome_unknown=False)
        request_id = str(uuid.uuid4())
        context_request = _REQUEST_CONTEXT.get()
        if context_request is not None and operation not in CONTEXT_READS | CONTEXT_ACTIONS:
            raise BridgeError("INVALID_ARGUMENTS", "Operation is outside the fixed context RPC whitelist.", outcome_unknown=False)
        if trace is not None:
            trace.update(request_id=request_id, dispatched=False)
        try:
            request = {"requestId": request_id, "operation": operation, "args": args}
            if context_request is not None:
                request["includeContext"] = context_request
            body = json.dumps(
                request,
                ensure_ascii=False, allow_nan=False, separators=(",", ":"),
            ).encode("utf-8")
        except (ValueError, TypeError, RecursionError):
            raise BridgeError("INVALID_ARGUMENTS", "Arguments must be finite JSON values.") from None
        if len(body) > MAX_REQUEST_BYTES:
            raise BridgeError("REQUEST_TOO_LARGE", "The request exceeds 64 KiB.")
        max_bytes = MAX_SCREENSHOT_RESPONSE_BYTES if operation == "client.screenshot" else MAX_RESPONSE_BYTES
        try:
            interaction_goal = isinstance(args.get("goal"), dict) and args["goal"].get("type") == "interaction"
            navigation_millis = args.get("timeout_ms", 6000) if operation == "player.navigate" and (args.get("segmented") is True or interaction_goal) else 0
            long_navigation = type(navigation_millis) is int and 6500 < navigation_millis <= 60000
            request_timeout = max(self.timeout, navigation_millis / 1000 + 2) if long_navigation else self.timeout
            with anyio.fail_after(request_timeout):
                if trace is not None:
                    trace["dispatched"] = True
                async with self.http.stream(
                    "POST", credentials.base_url + "/v1/call", content=body,
                    headers={"Authorization": "Bearer " + credentials.token, "Content-Type": "application/json"},
                    timeout=httpx.Timeout(request_timeout, connect=min(request_timeout, 2.0), pool=min(request_timeout, 2.0)),
                ) as response:
                    if telemetry is not None:
                        telemetry["server"] = parse_timing_headers(response.headers)
                    if 300 <= response.status_code < 400:
                        raise BridgeError("REDIRECT_REFUSED", "The bridge returned a redirect. It was not followed.")
                    if response.status_code in (401, 403):
                        raise BridgeError("AUTH_FAILED", "Bridge authentication failed; verify the active session runtime file.")
                    if response.status_code != 200:
                        raise BridgeError("HTTP_ERROR", "The bridge returned an unexpected HTTP status.", status=response.status_code)
                    if response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                        raise BridgeError("MALFORMED_RESPONSE", "The bridge did not return application/json.")
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if telemetry is not None:
                            telemetry["responseBytes"] = len(raw)
                        if len(raw) > max_bytes:
                            raise BridgeError("RESPONSE_TOO_LARGE", "The bridge response exceeded the configured size limit.")
        except (httpx.TimeoutException, TimeoutError):
            cancellation_status = await self._cancel(credentials, request_id)
            if trace is not None:
                trace.update(cancellation_status=cancellation_status, outcome_unknown=operation not in READ_OPERATIONS and cancellation_status != "cancelled")
            raise BridgeError(
                "BRIDGE_TIMEOUT", "The bridge request timed out. Actions are never automatically retried.",
                request_id=request_id, cancellation_status=cancellation_status,
                outcome_unknown=operation not in READ_OPERATIONS and cancellation_status != "cancelled",
            ) from None
        except anyio.get_cancelled_exc_class():
            cancellation_status = await self._cancel(credentials, request_id)
            if trace is not None:
                trace.update(cancellation_status=cancellation_status, outcome_unknown=operation not in READ_OPERATIONS and cancellation_status != "cancelled")
            if operation not in READ_OPERATIONS and cancellation_status != "cancelled":
                logging.getLogger(__name__).warning("Cancelled action outcome is uncertain; inspect game state before another action.")
            raise
        except httpx.RequestError:
            # A broken response stream is not evidence that a submitted action never ran.
            cancellation_status = await self._cancel(credentials, request_id) if operation not in READ_OPERATIONS else "not_required_read_only"
            uncertain = operation not in READ_OPERATIONS and cancellation_status != "cancelled"
            if trace is not None:
                trace.update(cancellation_status=cancellation_status, outcome_unknown=uncertain)
            raise BridgeError(
                "BRIDGE_UNAVAILABLE", "Bridge transport failed; the original request was cancelled where possible. Inspect actual state before another action; never replay automatically.",
                request_id=request_id, cancellation_status=cancellation_status,
                outcome_unknown=uncertain,
            ) from None
        try:
            envelope = strict_json(bytes(raw))
            if not isinstance(envelope, dict) or envelope.get("requestId") != request_id or type(envelope.get("ok")) is not bool:
                raise ValueError("Invalid envelope")
            if envelope["ok"]:
                expected_keys = {"requestId", "ok", "data"} | ({"context"} if context_request is not None else set())
                if set(envelope) != expected_keys or not isinstance(envelope["data"], dict):
                    raise ValueError("Invalid success data")
                if operation == "bridge.health" and credentials.instance_path is not None:
                    data = envelope["data"]
                    if data.get("sessionId") != credentials.session_id or data.get("instancePath") != credentials.instance_path:
                        raise BridgeError("INSTANCE_MISMATCH", "The live bridge does not match the pinned runtime instance/session.")
                if context_request is not None:
                    context = envelope["context"]
                    if not isinstance(context, dict) or set(context) != {"version", "operation", "phase", "snapshot"} \
                            or type(context["version"]) is not int or context["version"] != 1 or context["operation"] != operation \
                            or context["phase"] != ("before" if operation in CONTEXT_ACTIONS else "after") \
                            or not isinstance(context["snapshot"], dict):
                        raise ValueError("Invalid context evidence")
                    value = _ContextData(redact(envelope["data"], credentials.token), redact(context, credentials.token))
                    observe_response(operation, value, value.context, credentials.instance_path, credentials.session_id, args)
                    return value
                value = redact(envelope["data"], credentials.token)
                observe_response(operation, value, None, credentials.instance_path, credentials.session_id, args)
                return value
            error = envelope.get("error")
            if set(envelope) != {"requestId", "ok", "error"} or not isinstance(error, dict):
                raise ValueError("Invalid failure data")
            if not isinstance(error.get("code"), str) or not isinstance(error.get("message"), str):
                raise ValueError("Invalid failure code/message")
            if not 1 <= len(error["code"]) <= 128 or len(error["message"]) > 4_096:
                raise ValueError("Oversized error")
        except (ValueError, UnicodeError, RecursionError):
            if context_request is not None:
                unknown = operation not in READ_OPERATIONS
                if trace is not None:
                    trace["outcome_unknown"] = unknown
                raise BridgeError("MALFORMED_RESPONSE", "The guarded RPC returned invalid or missing context evidence; no action is replayed.", outcome_unknown=unknown) from None
            raise BridgeError("MALFORMED_RESPONSE", "The bridge returned invalid JSON or a mismatched response envelope.") from None
        details = {"outcome_unknown": error["outcome_unknown"]} if type(error.get("outcome_unknown")) is bool else {}
        if type(error.get("original_action_executed")) is bool:
            details["original_action_executed"] = error["original_action_executed"]
        if operation == "gui.transfer_whole_stack":
            from .whole_stack_transfer import failure_fields
            try:
                details.update({key: redact(value, credentials.token) for key, value in failure_fields(error).items()})
            except ValueError:
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("MALFORMED_RESPONSE", "The native whole-stack failure needs typed dispatch and last-observed cursor evidence; no action is replayed.", outcome_unknown=True) from None
            if trace is not None and type(error.get("outcome_unknown")) is bool:
                trace["outcome_unknown"] = error["outcome_unknown"]
        replacement_fields = ("stage", "nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed")
        if operation == "player.replace_owned_block" and any(key in error for key in replacement_fields):
            fields = ("nativeDigSubmitted", "nativePlaceSubmitted", "digConfirmed", "placeConfirmed", "outcome_unknown")
            valid = isinstance(error.get("stage"), str) and error["stage"] in _OWNED_REPLACEMENT_STAGES \
                and all(type(error.get(key)) is bool for key in fields)
            valid = valid and (not error["digConfirmed"] or error["nativeDigSubmitted"]) \
                and (not error["nativePlaceSubmitted"] or error["digConfirmed"]) \
                and (not error["placeConfirmed"] or error["nativePlaceSubmitted"]) \
                and (not any(error[key] for key in fields[:-1]) or error["outcome_unknown"])
            if not valid:
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("MALFORMED_RESPONSE", "The owned replacement failure needs its exact typed stage and action checkpoints; no native action is replayed.", outcome_unknown=True)
            details.update({key: redact(error[key], credentials.token) for key in ("stage", *fields)})
            if trace is not None:
                trace["outcome_unknown"] = error["outcome_unknown"]
        if operation in ("structure.survival_build", "player.place_block"):
            try:
                fields = placement_failure_fields(error) if operation == "player.place_block" else survival_failure_fields(error)
            except ValueError:
                if trace is not None:
                    trace["outcome_unknown"] = True
                raise BridgeError("MALFORMED_RESPONSE", "The native survival failure needs typed bounded checkpoints; no action is replayed.", outcome_unknown=True) from None
            details.update({key: redact(value, credentials.token) for key, value in fields.items()})
            if trace is not None and type(error.get("outcome_unknown")) is bool:
                trace["outcome_unknown"] = error["outcome_unknown"]
        raise BridgeError(redact(error["code"], credentials.token), redact(error["message"], credentials.token), **details)

    async def invoke(self, operation: str, args: dict[str, Any]) -> dict[str, Any]:
        credentials = self.configuration.read()
        from .operation_policy import environment
        scope, context = _CAPABILITY_SCOPE.get(), _REQUEST_CONTEXT.get()
        cached = scope.get("entry") if scope is not None else None
        reuse = (operation != "bridge.health" and cached is not None and cached[0] is self
                 and cached[1] == credentials and context is not None
                 and context.get("expectedSessionId") == cached[2].get("sessionId")
                 and context.get("expectedProfile") == "developer" and bool(context.get("expectedWorld"))
                 and environment(cached[2]) == "local_integrated"
                 and operation in CONTEXT_READS | CONTEXT_ACTIONS)
        health = cached[2] if reuse else await self.call("bridge.health", {}, expected_credentials=credentials)
        if scope is not None and not reuse:
            scope["entry"] = (self, credentials, health)
        if type(health.get("protocolVersion")) is not int or health["protocolVersion"] != 1:
            raise BridgeError("PROTOCOL_VERSION", "The bridge uses an unsupported protocol version.")
        if operation == "bridge.health":
            return health
        from .operation_policy import machine_result, route
        original_operation = operation
        operation, args = route(operation, args, health, guarded=_REQUEST_CONTEXT.get() is not None)
        capabilities = health.get("capabilities")
        if isinstance(capabilities, list) and all(isinstance(item, str) for item in capabilities):
            supported = operation in capabilities
        elif isinstance(capabilities, dict) and all(type(value) is bool for value in capabilities.values()):
            supported = capabilities.get(operation, False)
        else:
            raise BridgeError("MALFORMED_RESPONSE", "The bridge health response has no valid operation capabilities.")
        if not supported:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active bridge does not implement this operation.", operation=operation)
        try:
            data = await self.call(operation, args, expected_credentials=credentials)
        except BaseException:
            if scope is not None:
                scope.clear()
            raise
        return machine_result(data, args) if original_operation == "machine.inspect" and operation == "server.observe" else data
