"""Native control preconditions and once-only dispatch guards."""
from __future__ import annotations

from typing import Any
from ..bridge import BridgeClient, BridgeError, request_context, response_context
from ..workflows import _guard
from ..recipe_tasks import _RecipeTask
from ..operation_policy import route as operation_route


def _require_capability(health: dict[str, Any], operation: str) -> None:
    capabilities = health.get("capabilities")
    if isinstance(capabilities, list) and all(isinstance(item, str) for item in capabilities):
        supported = operation in capabilities
    elif isinstance(capabilities, dict) and all(type(value) is bool for value in capabilities.values()):
        supported = capabilities.get(operation, False)
    else:
        raise BridgeError("MALFORMED_RESPONSE", "The bridge health response has no valid operation capabilities.")
    if not supported:
        raise BridgeError("UNSUPPORTED_CAPABILITY", "The active bridge does not implement this operation.", operation=operation)


def _full_background(state: Any) -> bool:
    return isinstance(state, dict) and state.get("enabled") is True and state.get("helperInstalled") is True and state.get("pauseOnLostFocus") is False


async def invoke_control(client: BridgeClient, operation: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """Verify current protection per action; manual release and session changes invalidate it immediately."""
    if operation == "gui.pointer" and arguments.get("action") == "clear":
        return await client.invoke(operation, arguments)
    try:
        # Takeover has to be established before the original action guard can
        # require it. Restore the caller's guard for the once-only action.
        with request_context(None):
            health = await client.invoke("bridge.health", {})
        operation_route(operation, arguments, health)
        _require_capability(health, operation)
        if operation == "player.interact" and "sneaking" in arguments and health.get("interactionSneaking") is not True:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support scoped sneak interaction.", operation=operation)
        if operation == "player.interact" and any(key in arguments for key in ("x", "y", "z")) and health.get("interactionBlockTargeting") is not True:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support aimed block-only interaction without air-use fallback.", operation=operation)
        if operation == "player.interact" and arguments.get("action") == "use" and "x" in arguments and health.get("interactionViewBinding") is not True:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support target-bound view and the developer-only legacy view option.", operation=operation)
        if operation == "player.interact" and arguments.get("action") == "attack" and "x" in arguments and health.get("interactionAttackViewBinding") is not True:
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod does not support target-bound attack view.", operation=operation)
        _require_capability(health, "client.background")
        if not _full_background(health.get("background")):
            with request_context(None):
                protection = await client.call("client.background", {"enabled": True})
            if not _full_background(protection):
                raise BridgeError("TAKEOVER_NOT_CONFIRMED", "The bridge did not confirm full background protection with its actual mouse helper and unfocused-running state.")
    except BridgeError as error:
        details = {**error.details, "stage": "takeover", "blocked_operation": operation, "original_action_executed": False}
        raise BridgeError(error.code, error.message, **details) from None
    # Transport retains the original action's request ID, cancellation handling and uncertain-outcome semantics.
    if operation == "player.navigate":
        from ..navigation_gate import closed_container_before_navigation
        close_sync = await closed_container_before_navigation(client, arguments, health)
        result = await client.call(operation, arguments)
        if close_sync is not None:
            result["containerCloseSynchronization"] = close_sync
        return result
    if operation == "player.place_block":
        from ..placement_attachment import require_capability, validate_receipt
        if "attachmentBinding" in arguments:
            require_capability(health)
        if arguments.get("jumpUnderfoot") is True and (type(health.get("jumpPlacementVersion")) is not int or health["jumpPlacementVersion"] != 1):
            raise BridgeError("UNSUPPORTED_CAPABILITY", "The active mod lacks guarded native underfoot placement.", original_action_executed=False)
        context = {"version": 1, "expectedSessionId": health.get("sessionId"), "expectedProfile": "developer",
            "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
        if not isinstance(context["expectedSessionId"], str) or not 1 <= len(context["expectedSessionId"]) <= 128:
            raise BridgeError("placement_attachment_context_unavailable", "Native placement needs the current real session before dispatch.", outcome_unknown=False)
        with request_context(context):
            result = await client.call(operation, arguments)
        if "attachmentBinding" in arguments:
            validate_receipt(result, arguments["attachmentBinding"])
        return result
    if operation == "gui.transfer_whole_stack":
        from ..whole_stack_transfer import invoke_standalone
        return await invoke_standalone(client, arguments, health)
    if operation == "structure.survival_build":
        context = {"version": 1, "expectedSessionId": arguments["expectedSessionId"], "expectedProfile": "developer",
            "expectedWorld": arguments["expectedWorld"], "requireBackground": True}
        snapshot = await client.call("client.snapshot", {})
        try:
            _guard(snapshot, context["expectedSessionId"], "developer", context["expectedWorld"], True)
            _RecipeTask.require_live_player(snapshot)
        except BridgeError as error:
            raise BridgeError(error.code, error.message, **{**error.details, "outcome_unknown": False,
                "original_action_executed": False}) from None
        with request_context(context):
            data = await client.call(operation, arguments)
        evidence = response_context(data)
        try:
            if not isinstance(evidence, dict) or evidence.get("phase") != "before" or not isinstance(evidence.get("snapshot"), dict):
                raise BridgeError("MALFORMED_RESPONSE", "Native survival construction requires its exact current BEFORE guard.")
            _guard(evidence["snapshot"], context["expectedSessionId"], "developer", context["expectedWorld"], True)
            _RecipeTask.require_live_player(evidence["snapshot"])
        except BridgeError as error:
            # Validation occurs after the once-only native dispatch. Retain its
            # actual receipt and report uncertainty rather than permitting retry.
            raise BridgeError(error.code, error.message, **{**error.details, "outcome_unknown": True,
                "knownReceipt": dict(data)}) from None
        return data
    return await client.call(operation, arguments)
