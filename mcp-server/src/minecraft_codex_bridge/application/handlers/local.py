"""Bounded local records, guides, diagnostics and client lifecycle handlers."""
from __future__ import annotations

import sqlite3
import anyio
from ...bridge import BridgeError
from ...conveniences import read_logs
from ...guides import read_help
from ...launcher import launch_client, launch_status, client_diagnostics
from ...player_recordings import read_recording
from ..results import _result


async def handle_action_recovery(adapter, name, arguments, started_at):
    recovery = getattr(adapter, "action_recovery", None)
    if recovery is None:
        raise BridgeError("ACTION_RECOVERY_WORKER_REQUIRED", "Use the supervised MCP connection that retains the unresolved action.", outcome_unknown=False)
    return _result({"ok": True, "data": await recovery(arguments)})


async def handle_dialogue(adapter, name, arguments, started_at):
    from ...dialogue import manage
    return _result({"ok": True, "data": await manage(adapter.client, arguments)})

async def handle_recording(adapter, name, arguments, started_at):
    if arguments.get("view", "live") == "live":
        data = await adapter.client.invoke("client.recording", {key: value for key, value in arguments.items() if key != "view"})
    else:
        try:
            with anyio.fail_after(10):
                data = await anyio.to_thread.run_sync(read_recording, adapter.client.configuration, arguments, abandon_on_cancel=True)
        except TimeoutError:
            raise BridgeError("RECORDING_BUDGET", "Recording read deadline exceeded; no game action or file mutation occurred.") from None
    return _result({"ok": True, "data": data})


async def handle_run_log(adapter, name, arguments, started_at):
    try:
        with anyio.fail_after(2):
            data = await anyio.to_thread.run_sync(adapter.run_log.command, arguments, abandon_on_cancel=True)
    except (TimeoutError, OSError, ValueError, sqlite3.Error):
        raise BridgeError("RUN_LOG_UNAVAILABLE", "The bounded client journal is unavailable. No game action was executed; existing logs were preserved.") from None
    return _result({"ok": True, "data": data})


async def handle_knowledge(adapter, name, arguments, started_at):
    note_token = ""
    if arguments.get("action") in {"query", "candidates", "sites", "diagnostics", "lessons", "learn"}:
        try:
            credentials = adapter.client.configuration.read()
            adapter.knowledge.check_session(credentials.session_id)
            if arguments.get("action") == "learn":
                note_token = credentials.token
        except BridgeError:
            adapter.knowledge.check_session(None)
    try:
        with anyio.fail_after(1):
            data = await anyio.to_thread.run_sync(lambda: adapter.knowledge.command(arguments, token=note_token), abandon_on_cancel=True)
    except (TimeoutError, OSError, ValueError, sqlite3.Error):
        raise BridgeError("KNOWLEDGE_UNAVAILABLE", "The bounded local knowledge operation is unavailable; game state was not changed.") from None
    return _result({"ok": True, "data": data})


async def handle_help(adapter, name, arguments, started_at):
    if arguments.get("topic") == "tools":
        from ...dynamic_catalog import describe_catalog
        return _result({"ok": True, "data": describe_catalog(adapter.client.configuration, arguments)})
    data = await anyio.to_thread.run_sync(read_help, arguments, abandon_on_cancel=True)
    return _result({"ok": True, "data": data})


async def handle_launch(adapter, name, arguments, started_at):
    if arguments.get("action") == "status":
        return _result({"ok": True, "data": await launch_status(adapter.client, arguments.get("timeout_ms", 30000))})
    return _result({"ok": True, "data": await launch_client(adapter.client.configuration, arguments.get("windowMode", "minimized"))})


async def handle_diagnostics(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await client_diagnostics(adapter.client)})


async def handle_logs(adapter, name, arguments, started_at):
    data = await anyio.to_thread.run_sync(read_logs, adapter.client.configuration, arguments, abandon_on_cancel=True)
    return _result({"ok": True, "data": data})
