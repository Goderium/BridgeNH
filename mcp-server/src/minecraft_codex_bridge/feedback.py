"""Shipped interpretations of MCB receipts, independent of local history.

Only fixed response surfaces and exact codes are inspected. Never search item
NBT, tooltip text or arbitrary nested data for instructions or error signatures.
No game calls, retries, state changes or inferred root causes are made here.
"""
from __future__ import annotations


# Group aliases only where the verified contracts share the same next step.
_RULES = (
    ("client_restart", ("client_restart_required", "startup_cancelled"),
     "The native Forge startup confirmation was canceled; this client process requires recovery before another world or connection.",
     "Let its existing lifecycle unwind, exit this Minecraft client normally, restore the intended mod set and launch it again. Observe its new session before loading the original save; restarting Codex/MCP does not reset game registries."),
    ("startup_choice", ("startup_query_requires_choice",),
     "A native Forge startup query is waiting for its own completion signal.",
     "Read the current query and observed button identity, then use the authorized native choice. Do not close it with Escape, guess buttons, automatically accept missing registries or resubmit the load."),
    ("stale_context", (
        "SESSION_CHANGED", "PROFILE_CHANGED", "stale_session", "stale_world", "stale_screen",
        "stale_container", "stale_target", "stale_query", "stale_capture", "gui_context_changed",
        "REMOTE_DIMENSION_CHANGED", "REMOTE_READ_CONTEXT_CHANGED", "CAPTURE_CONTEXT_CHANGED"),
     "The requested context or identifier no longer matches the live context.",
     "Read the affected current session/world, GUI, target or capture once and rebuild only the remaining action. Never reuse an old slot/widget ID or consumed cursor."),
    ("permission", ("permission_denied", "RECIPE_AUTHORITY_UNAVAILABLE"),
     "This operation lacks the required current game/profile authority; it does not establish that ordinary client reads are unavailable.",
     "Use advertised capabilities and permitted player/NEI reads. Do not enable LAN, cheats or a developer profile merely to read public recipes; request extra authority only for an operation that needs it."),
    ("unsupported", ("unsupported", "UNSUPPORTED_CAPABILITY", "REMOTE_OPERATION_UNSUPPORTED",
        "REMOTE_CONTEXT_UNSUPPORTED", "REMOTE_ARGUMENT_UNSUPPORTED", "REMOTE_READ_UNAVAILABLE",
        "RECIPE_CATALOG_UNAVAILABLE", "MACHINE_CAPTURE_UNAVAILABLE"),
     "The requested API, arguments or authority are unavailable in the current context; this is not proof that the item, recipe or machine is absent.",
     "Inspect the reported capability/reason and choose a currently supported authorized path. Preserve unknown coverage; do not guess a missing API or repeat the same call."),
    ("background", ("TAKEOVER_NOT_CONFIRMED", "background_required"),
     "Required mouse isolation and unfocused-running protection were not confirmed.",
     "Inspect agent/background state and establish supported takeover before the intended control action. Check dispatch evidence before deciding whether it can be submitted again."),
    ("binding", ("CONFIGURATION_ERROR", "RUNTIME_DISCOVERY_FAILED", "INSTANCE_MISMATCH", "AUTH_FAILED"),
     "The adapter could not establish the requested client instance/session connection or authentication.",
     "Check the intended client's current runtime file and MCP instance binding. Client restarts rotate credentials; never switch to another instance or expose/copy its token as a workaround."),
    ("timeout", ("BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE", "WORKFLOW_TIMEOUT", "QUERY_TIMEOUT",
        "CAPTURE_QUERY_TIMEOUT", "timeout"),
     "A bounded request did not produce a confirmed result. Timeout or transport loss alone does not prove that a submitted action never ran.",
     "Inspect cancellation/dispatch checkpoints and fresh state for the affected target. Do not replay mutations or consumed cursors; retain unknown if verification is incomplete."),
    ("wait_unmatched", ("WAIT_TIMEOUT", "GUI_WAIT_TIMEOUT"),
     "The requested condition was not observed within the wait; this does not prove a preceding action failed.",
     "Use the last observation and fresh synchronized target state to decide what is missing. Never repeat the preceding click, supply or craft solely because the wait expired."),
    ("waila_partial", ("WAILA_PARTIAL",),
     "Some provider text or coverage is incomplete. Retained lines are usable partial evidence, not a complete overlay or a diagnosis of the in-game display.",
     "Read providerNbt, providers and completeness for the actual gap. A truncated NBT preview alone does not mean the provider's full NBT was truncated."),
    ("invalid_request", ("INVALID_ARGUMENTS", "invalid_args", "UNKNOWN_TOOL", "COMMAND_VERSION_UNSUPPORTED"),
     "The requested tool, arguments or command version do not match the supported contract.",
     "Correct only the reported argument/tool/version using its current schema; do not try guessed parameter variants or newer-version commands."),
    ("invalid_receipt", ("MALFORMED_RESPONSE", "MALFORMED_IMAGE", "RESPONSE_TOO_LARGE", "result_too_large", "PROTOCOL_VERSION"),
     "The response failed protocol, evidence or size validation; it cannot serve as a verified result.",
     "Keep dispatch/outcome uncertainty, inspect current state after mutations, and report the bounded diagnostic. For reads use an advertised smaller page/region; do not silently drop required evidence."),
    ("unloaded", ("chunk_unloaded", "world_unavailable"),
     "The required loaded world, chunk or live player was unavailable at this observation.",
     "Check readiness and the current dimension/loaded coverage. An unobserved position is unknown, not air, an empty inventory or a failed structure."),
    ("reach", ("out_of_reach",),
     "The requested interaction did not satisfy the actual player's native reach check.",
     "For a standalone interaction use the client-owned mcb_approach with the observed target/allowed faces. A reachable face point is enough; do not require standing directly below or beside the block."),
    ("flight", ("flight_not_confirmed", "flight_lost", "flight_input_blocked"),
     "Required actual flight was not confirmed, was lost, or produced no verified movement; permission/creative mode alone is insufficient.",
     "Inspect current dual flight and route geometry while on verified support. Do not repeat a suspended teleport or assume the destination was reached."),
)
_BY_CODE = {code: (key, meaning, next_step) for key, codes, meaning, next_step in _RULES for code in codes}
MAX_ITEMS = 4


def _object(value):
    return value if isinstance(value, dict) else {}


def explain(tool: str, payload: dict, *, read_only: bool) -> dict | None:
    """Additive, bounded advice; original ok/error/data remain authoritative."""
    data, error = _object(payload.get("data")), _object(payload.get("error"))
    # Reserved receipt surfaces only; never recurse through stacks, NBT or text.
    surfaces = [("error", error), ("data", data)]
    surfaces += [("data." + key, _object(data.get(key))) for key in ("error", "failure")]
    failure = _object(data.get("failure"))
    surfaces.append(("data.failure.error", _object(failure.get("error"))))
    items, seen, limited = [], set(), False

    def add(key, at, meaning, next_step):
        nonlocal limited
        if key in seen:
            return
        seen.add(key)
        if len(items) == MAX_ITEMS:
            limited = True
            return
        items.append({"id": "mcb." + key, "at": at, "meaning": meaning, "next": next_step})

    for at, value in surfaces:
        if any(value.get(key) is True for key in ("outcome_unknown", "outcomeUnknown")):
            add("unknown_effects", at,
                "Some effects are explicitly unknown. Failure is not a rollback or evidence of no change.",
                "Inspect fresh authoritative state for the affected target before another mutation. Keep unknown if coverage is incomplete; never automatically replay or switch methods.")
    if error.get("original_action_executed") is False:
        add("not_dispatched", "error.original_action_executed",
            "The intended action was explicitly refused before execution; preparatory takeover/read calls may still have occurred.",
            "Resolve the reported prerequisite or choose an independently authorized supported alternative. This does not authorize extra permissions or automatic fallback.")
    for at, value in surfaces:
        # A root data.code may be ordinary observed content, not an error.
        code = value.get("code") if at != "data" else None
        if not isinstance(code, str) or not code:
            continue
        rule = _BY_CODE.get(code)
        if rule:
            add(rule[0], at, rule[1], rule[2])
        else:
            add("unclassified_error", at,
                "There is no shipped explanation for this exact error code; a root cause has not been established.",
                "Use the original code/message, current capability and checkpoints. Record the bounded issue if needed; do not infer a cause from similar words or automatically retry.")
    dispatched = data.get("dispatchedActions")
    if (data.get("nonAtomic") is True and data.get("status") == "failed"
            and (type(dispatched) is int and dispatched > 0 or data.get("outcomeUnknown") is True)):
        add("partial_workflow", "data.status",
            "This multi-step task failed after possible dispatch. Completed steps remain effective; later steps may be not_executed and there is no rollback.",
            "Inspect failedIndex, steps, cleanupStatus and acceptance. Reconcile actual state and resume only the missing work; do not rerun the whole task.")
    cancelled_at = ("error.cancellation_status" if error.get("cancellation_status") == "cancelled" else
                    "data.cancellationStatus" if data.get("cancellationStatus") == "cancelled" else None)
    if cancelled_at:
        add("queued_cancelled", cancelled_at,
            "Cancellation acknowledged that this queued request was cancelled before it started; this says nothing about earlier task steps.",
            "Preserve earlier receipts. Fix the timeout/context cause before deciding on a fresh request; cancellation is not an instruction to retry.")
    if payload.get("ok") is False and not {"unknown_effects", "partial_workflow"} & seen:
        for at, value in surfaces:
            if value.get("sideEffectsPossible") is True:
                add("possible_effects", at,
                    "Earlier actions or preparatory takeover may have changed state. This flag alone does not mean their outcome is unknown.",
                    "Use dispatch, acceptance and cleanup receipts to retain known effects. Failure does not imply rollback or permission to repeat the whole task.")
    if data.get("status") in ("scheduled", "running", "pending", "awaiting_response", "awaiting_world"):
        add("pending", "data.status",
            "The operation is scheduled or in progress, not verified complete.",
            "Observe its existing ticket/task or snapshot lifecycle until a terminal receipt. Do not submit a duplicate start.")
    if not read_only and (data.get("submitted") is True or data.get("sent") is True or data.get("status") in ("sent", "submitted")):
        if data.get("executionConfirmed") is not True and data.get("status") != "completed":
            add("submitted", "data",
                "The command/input was submitted. Submission or a native callback alone does not prove server acceptance, inventory change or production.",
                "Use returned acceptance when present; otherwise inspect synchronized GUI/inventory/chat or the affected target. Do not repeat the action just to obtain confirmation.")
    cursor = data.get("nextCursor")
    more_pages = data.get("hasMore") is True or isinstance(cursor, str) and bool(cursor)
    if more_pages:
        add("more_pages", "data",
            "This is one bounded page, not the complete result set. A non-null nextOffset alone is not proof of more pages.",
            "Continue only if the task needs omitted data, using the returned continuation and its exact context. Respect consumed/expiring cursors; never replay one or drain all pages automatically.")
    if (data.get("complete") is False and not more_pages
            or any(data.get(key) is True for key in ("partial", "truncated", "scanTruncated", "byteLimited"))):
        if "waila_partial" not in seen:
            add("partial_read", "data",
                "The returned observation has explicit partial/size-limited coverage; omitted data is unknown, not absent.",
                "Use retained evidence and its coverage/limits. Request only the missing page/region or supported detail needed by this task; a partial result does not by itself mean any mutation occurred.")
    if tool == "mcb_waila" and data.get("nbtSnapshotTruncated") is True:
        if "waila_partial" not in seen:
            add("nbt_preview", "data.nbtSnapshotTruncated",
                "Only the bounded NBT preview is marked truncated. This flag alone does not invalidate provider text or prove full provider NBT incomplete.",
                "Use providerNbt.complete and provider/completeness receipts for coverage; do not reject otherwise complete Waila text solely because its preview was clipped.")
    if data.get("available") is False:
        add("unavailable", "data.available",
            "This requested observation/capability is unavailable; an empty-looking result does not prove the underlying content is empty.",
            "Use the returned reason/source/coverage and a supported permitted observation. Preserve unknown when no equivalent evidence is available.")
    if tool == "mcb_machine_work" and data.get("requestedWorking") is False and data.get("after") is False:
        add("gt_disabled", "data.after",
            "The public allowed-to-work flag is disabled. GT5U 5.09.54.205 base multiblocks can still finish their current batch; this is not fault stop or proof of idle.",
            "Use fresh progress/active state and real output acceptance. Do not replace ordinary disable with fault stop: the base fault path clears pending outputs and progress. Machine overrides may differ.")
    if tool == "mcb_ae" and data.get("scope") == "preview" and _object(data.get("state")).get("requestedPending") is True:
        add("ae_preview_pending", "data.state.requestedPending",
            "The current AE crafting preview is still synchronizing its requested target; absence here is not proof that the request failed.",
            "Read the same current preview until startReady and required resources/CPU data are available. Do not resend craft_request or start automatically.")
    if not items:
        return None
    return {"version": 1, "source": "mcb_builtin", "automaticReplayAllowed": False,
        "items": items, "explanationsLimited": limited}
