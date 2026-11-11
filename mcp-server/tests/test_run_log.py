"""Real filesystem/SQLite tests; game traffic is mocked explicitly."""
import json
import copy
import os
from pathlib import Path
import sqlite3
import zipfile
from datetime import timedelta

import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.knowledge import Capture
from minecraft_codex_bridge.run_log import RunLog, FOLDER, SEGMENT_BYTES
from minecraft_codex_bridge.server import Adapter, _validate, make_server


TOKEN = "private-journal-session-credential"


def setup(tmp_path, name="instance"):
    instance = tmp_path / name
    runtime = instance / ".minecraft" / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671",
        "token": TOKEN, "instancePath": str(instance.resolve()), "sessionId": "journal-session"}), encoding="utf-8")
    return RunLog(Configuration({"MCB_RUNTIME_FILE": str(runtime)})), runtime


def capture(tool="mcb_approach"):
    return Capture(tool, session="journal-session", versions={"modVersion": "0.1.0"}, environment="remote_server")


def finish(journal, *, payload=None, elapsed=20, tool="mcb_approach", args=None):
    return journal.finish(capture(tool), args or {}, payload or {"ok": True, "data": {"arrivalConfirmed": True}},
        {"adapterElapsedMs": elapsed})


def test_rotation_deletes_oldest_only_and_keeps_issue_summaries(tmp_path):
    journal, _ = setup(tmp_path)
    journal.command({"action": "configure", "normalMaxMiB": 4})
    failure = {"ok": False, "error": {"code": "no_path", "message": "No reachable surface."}}
    identity = finish(journal, payload=failure)["issueId"]
    with journal.locked() as root:
        old = root / "normal" / "operations-00000000000000000001-000000000001.jsonl"
        old.write_bytes(b"x" * SEGMENT_BYTES)
        unowned = root / "normal" / "my-notes.txt"
        unowned.write_text("keep", encoding="utf-8")
    finish(journal)
    status = journal.command({})
    assert status["normalBytes"] <= 4 * 1024 * 1024 and not old.exists()
    assert unowned.read_text() == "keep"
    assert journal.command({"action": "read", "id": identity})["entries"][0]["occurrences"] == 1


def test_age_cleanup_and_cross_session_issue_dedup(tmp_path):
    journal, runtime = setup(tmp_path)
    failure = {"ok": False, "error": {"code": "no_path", "message": "No reachable surface."}}
    first = finish(journal, payload=failure)
    other = RunLog(journal.configuration)
    second = finish(other, payload=failure)
    assert first["issueId"] == second["issueId"]
    rows = journal.command({"action": "read", "category": "error"})["entries"]
    assert len(rows) == 1 and rows[0]["occurrences"] == 2
    normal = runtime.parents[2] / FOLDER / "normal"
    for path in normal.iterdir():
        os.utime(path, (1, 1))
    assert journal.command({})["normalFiles"] == 0
    assert journal.command({"action": "read", "category": "error"})["entries"]


def test_interrupted_append_rotates_and_next_receipt_remains_readable(tmp_path):
    journal, _ = setup(tmp_path)
    first = finish(journal)
    with journal.locked() as root:
        path = next((root / "normal").iterdir())
        with path.open("ab") as stream:
            stream.write(b'{"interrupted":')
    second = finish(RunLog(journal.configuration))
    rows = journal.command({"action": "read", "category": "normal"})["entries"]
    assert [row["id"] for row in rows] == [second["eventId"], first["eventId"]]
    assert path.read_bytes().endswith(b'{"interrupted":')
    assert journal.command({})["normalFiles"] == 2


@pytest.mark.parametrize("payload,elapsed,category,reason", [
    ({"ok": True, "data": {}}, 10001, "optimization", "slow_goal"),
    ({"ok": True, "data": {"partial": True}}, 1, "optimization", "partial_result"),
    ({"ok": True, "data": {"outcomeUnknown": True}}, 1, "error", "outcome_unknown"),
    ({"ok": True, "data": {"status": "failed"}}, 1, "error", "failure"),
    ({"ok": True, "data": {"failure": {"code": "blocked"}}}, 1, "error", "failure"),
])
def test_classification_labels_observation_without_claiming_root_cause(tmp_path, payload, elapsed, category, reason):
    journal, _ = setup(tmp_path)
    result = finish(journal, payload=payload, elapsed=elapsed)
    assert result["category"] == category
    entry = journal.command({"action": "read", "id": result["issueId"]})["entries"][0]
    assert entry["automatic"]["attention"]["reason"] == reason
    assert entry["agentSummary"] == {}


def test_long_read_wait_is_not_slow_goal_and_recording_can_be_disabled(tmp_path):
    journal, _ = setup(tmp_path)
    assert "issueId" not in finish(journal, elapsed=50000, tool="mcb_snapshot")
    journal.command({"action": "configure", "enabled": False})
    assert finish(journal) == {"recorded": False, "reason": "disabled"}
    assert len(journal.command({"action": "read", "category": "normal"})["entries"]) == 1


def test_report_enriches_resolves_reopens_and_exports_without_sensitive_dtos(tmp_path):
    journal, _ = setup(tmp_path)
    payload = {"ok": False, "error": {"code": "blocked", "message": TOKEN + " at D:\\private\\world 127.0.0.1:25565"},
        "data": {"inventory": [{"NBT": "UNIQUE_NBT_SECRET"}], "screenshot": "UNIQUE_IMAGE_SECRET"}}
    logged = finish(journal, payload=payload, args={"message": "UNIQUE_CHAT_SECRET", "raw": "UNIQUE_ARGUMENT_SECRET"})
    identity = logged["issueId"]
    journal.command({"action": "report", "id": identity, "title": "移动到交互面失败", "observed": "No reachable surface.",
        "expected": "Stop anywhere within reach.", "reproduction": "password=UNIQUE_PASSWORD_SECRET", "suggestion": "Separate geometry from permission checks."})
    journal.command({"action": "resolve", "id": identity, "resolution": "Retest passed."})
    assert journal.command({"action": "read", "state": "resolved"})["entries"][0]["agentSummary"]["title"] == "移动到交互面失败"
    finish(journal, payload=payload)
    reopened = journal.command({"action": "read", "state": "open"})["entries"][0]
    assert reopened["occurrences"] == 2 and reopened["agentSummary"]["expected"] == "Stop anywhere within reach."
    export = journal.command({"action": "export", "id": identity})
    assert export["uploaded"] is False
    with zipfile.ZipFile(export["path"]) as archive:
        assert set(archive.namelist()) == {"report.json", "report.md"}
        data = archive.read("report.json").decode("utf-8")
        assert json.loads(data)["issues"]["entries"][0]["state"] == "open"
        assert "移动到交互面失败" in archive.read("report.md").decode("utf-8")
    for sensitive in (TOKEN, "UNIQUE_NBT_SECRET", "UNIQUE_IMAGE_SECRET", "UNIQUE_CHAT_SECRET", "UNIQUE_ARGUMENT_SECRET", "UNIQUE_PASSWORD_SECRET", "127.0.0.1", "D:\\private"):
        assert sensitive not in data


def test_offline_commands_use_pinned_client_and_do_not_require_minecraft(tmp_path):
    journal, runtime = setup(tmp_path)
    runtime.unlink()
    result = journal.command({"action": "report", "category": "optimization", "title": "Too many calls", "observed": "Repeated acceptance reads."})
    assert result["saved"]
    other, _ = setup(tmp_path, "different-client")
    assert other.command({"action": "read"})["entries"] == []
    assert str(runtime.parents[2]) in journal.command({})["root"]
    wrong = capture()
    wrong.instance = str(tmp_path / "different-client")
    assert journal.finish(wrong, {}, {"ok": True}, {})["reason"] == "instance_mismatch"


def test_simultaneous_serial_adapter_lock_refuses_without_touching_logs(tmp_path):
    journal, _ = setup(tmp_path)
    other = RunLog(journal.configuration)
    with journal.locked():
        with pytest.raises(BridgeError, match="Another process"):
            other.command({})
    assert finish(other)["recorded"]


def test_issue_db_failure_does_not_destroy_normal_receipt(tmp_path):
    journal, _ = setup(tmp_path)
    root = journal.root()
    path = root / "issues" / "issues.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE unrelated (value TEXT)")
    with pytest.raises(ValueError, match="not an agent"):
        finish(journal, payload={"ok": False, "error": {"code": "blocked"}})
    assert journal.command({"action": "read", "category": "normal"})["entries"][0]["issueId"]


@pytest.mark.parametrize("args", [
    {"action": "configure", "normalMaxMiB": 0}, {"action": "configure", "enabled": "yes"},
    {"action": "read", "category": "normal", "path": "../logs"},
    {"action": "report", "title": "missing observation"}, {"action": "export", "path": "elsewhere"},
    {"action": "resolve", "id": "../../outside", "resolution": "fixed"},
    {"action": "read", "limit": 1000}, {"action": "read", "category": "normal", "offset": 1},
])
def test_schema_refuses_unbounded_or_ambiguous_actions(args):
    with pytest.raises(BridgeError):
        _validate("mcb_run_log", args)


def test_symlink_destination_is_refused(tmp_path):
    journal, runtime = setup(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    try:
        (runtime.parents[2] / FOLDER).symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("OS does not permit symlink creation")
    with pytest.raises(BridgeError, match="redirect"):
        journal.command({})
    assert list(outside.iterdir()) == []


async def test_adapter_records_failure_and_exposes_health_with_no_extra_game_rpcs(tmp_path):
    journal, _ = setup(tmp_path)
    operations = []
    def respond(request):
        body = json.loads(request.content)
        operations.append(body["operation"])
        if body["operation"] == "bridge.health":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {
                "protocolVersion": 1, "capabilities": ["bridge.health", "client.snapshot"],
                "instancePath": str(journal.root().parent.parent), "sessionId": "journal-session", "profile": "player"}})
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
            "error": {"code": "busy", "message": "Queue busy."}})
    client = BridgeClient(journal.configuration, transport=httpx.MockTransport(respond))
    try:
        adapter = Adapter(client)
        health = await adapter.call_tool("mcb_health", {})
        assert health.structuredContent["agentRunLog"]["normalFiles"] == 1
        assert operations == ["bridge.health"]
        failed = await adapter.call_tool("mcb_snapshot", {})
        assert failed.isError and failed.structuredContent["error"]["code"] == "busy"
        assert failed.structuredContent["agentRunLog"]["issueId"]
        before = list(operations)
        report = await adapter.call_tool("mcb_run_log", {"action": "export"})
        assert report.structuredContent["ok"] and operations == before
        assert json.loads(failed.content[0].text) == failed.structuredContent
        adapter.run_log.finish = lambda *a: (_ for _ in ()).throw(OSError("full disk"))
        another = await adapter.call_tool("mcb_snapshot", {})
        assert another.isError and another.structuredContent["error"]["code"] == "busy"
        assert another.structuredContent["agentRunLog"]["gameResultPreserved"]
    finally:
        await client.aclose()


async def test_sdk_discovers_and_uses_offline_journal_without_game_calls(tmp_path):
    journal, runtime = setup(tmp_path)
    runtime.unlink()
    def forbidden(request):
        raise AssertionError("Local journal tools must not call Minecraft.")
    client = BridgeClient(journal.configuration, transport=httpx.MockTransport(forbidden))
    try:
        async with create_connected_server_and_client_session(
            make_server(client), read_timeout_seconds=timedelta(seconds=10), raise_exceptions=True,
        ) as session:
            tools = {tool.name: tool for tool in (await session.list_tools()).tools}
            assert "mcb_run_log" in tools
            assert tools["mcb_run_log"].annotations.readOnlyHint is False
            assert tools["mcb_run_log"].annotations.openWorldHint is False
            report = await session.call_tool("mcb_run_log", {"action": "report", "category": "optimization",
                "title": "Fewer reads", "observed": "The workflow repeated its acceptance read."})
            assert not report.isError and report.structuredContent["data"]["saved"]
            exported = await session.call_tool("mcb_run_log", {"action": "export"})
            result = exported.structuredContent["data"]
            assert not exported.isError and result["issueCount"] == 1 and not result["uploaded"]
            assert Path(result["path"]).is_file()
    finally:
        await client.aclose()


def running_order():
    return {"ok": True, "data": {"status": "native_pending", "resumable": True, "replayAllowed": False,
        "native": {"requestId": "same-order", "state": "running", "source": "integrated_server", "replayAllowed": False},
        "error": {"code": "AE_GOAL_TIMEOUT", "message": "Resume the same request."}}}


def test_confirmed_running_wait_is_history_not_a_failure_or_slow_issue(tmp_path):
    journal, _ = setup(tmp_path)
    payload = running_order()
    before = copy.deepcopy(payload)
    result = finish(journal, tool="mcb_ae_order", payload=payload, elapsed=60000, args={"requestId": "same-order"})
    assert "issueId" not in result and payload == before
    row = journal.command({"action": "read", "category": "normal"})["entries"][0]
    assert row["observationKind"] == "native_order_still_running"
    assert row["facts"]["error"]["code"] == "AE_GOAL_TIMEOUT"  # Evidence retained.
    assert journal.command({"action": "read"})["entries"] == []


@pytest.mark.parametrize("location,key,value", [
    ("payload", "ok", False), ("data", "resumable", False), ("data", "replayAllowed", True),
    ("data", "outcomeUnknown", True), ("data", "sideEffectsPossible", True),
    ("data", "acceptance", {"outcomeUnknown": True}), ("data", "recovery", {"sideEffectsPossible": True}),
    ("native", "outcome_unknown", True), ("native", "state", "unknown"),
    ("native", "source", "client"), ("error", "code", "AE_WAIT_UNCONFIRMED"),
    ("native", "requestId", "different-order"),
])
def test_running_wait_exception_does_not_hide_unknown_or_failed_receipts(tmp_path, location, key, value):
    journal, _ = setup(tmp_path)
    payload = running_order()
    target = payload if location == "payload" else payload["data"] if location == "data" else payload["data"][location]
    target[key] = value
    result = finish(journal, tool="mcb_ae_order", payload=payload, args={"requestId": "same-order"})
    assert result["category"] == "error" and result["issueId"]


@pytest.mark.parametrize("tool,offset,next_offset,unknown,expected_page", [
    ("mcb_baubles", 0, 1, False, True), ("mcb_ae", 64, 128, False, True),
    ("mcb_baubles", 0, True, False, False), ("mcb_ae", 64, 64, False, False),
    ("mcb_baubles", 0, 1, True, False), ("mcb_ae_install", 0, 1, False, False),
])
def test_only_successful_read_pages_are_normal_history(tmp_path, tool, offset, next_offset, unknown, expected_page):
    journal, _ = setup(tmp_path)
    payload = {"ok": True, "data": {"partial": True, "complete": False, "hasMore": True,
        "nextOffset": next_offset, "outcomeUnknown": unknown}}
    result = finish(journal, tool=tool, args={"offset": offset}, payload=payload)
    assert ("issueId" not in result) is expected_page


def test_compact_issue_reads_keep_details_available_and_separate_triage_counts(tmp_path):
    journal, _ = setup(tmp_path)
    identity = finish(journal, payload={"ok": False, "error": {"code": "blocked"}})["issueId"]
    compact = journal.command({"action": "read", "state": "open"})
    full = journal.command({"action": "read", "id": identity, "includeDetails": True})
    assert compact["detailsIncluded"] is False and full["detailsIncluded"] is True
    assert "operations" not in compact["entries"][0]["automatic"]
    assert "operations" in full["entries"][0]["automatic"]
    assert journal.command({})["triage"] == {"openUnreviewed": 1, "openReviewed": 0, "deferred": 0, "resolved": 0}
    journal.command({"action": "report", "id": identity, "title": "Observed refusal"})
    assert journal.command({"action": "read", "reviewStatus": "unreviewed"})["entries"] == []
    assert journal.command({"action": "read", "reviewStatus": "reviewed"})["entries"][0]["id"] == identity
    assert journal.command({})["triage"]["openReviewed"] == 1
    exported = journal.command({"action": "export"})
    with zipfile.ZipFile(exported["path"]) as archive:
        assert "operations" in json.loads(archive.read("report.json"))["issues"]["entries"][0]["automatic"]


def test_exact_related_resolution_is_atomic_preserves_notes_and_reopens_on_recurrence(tmp_path):
    journal, _ = setup(tmp_path)
    payload = {"ok": False, "error": {"code": "blocked"}}
    child = finish(journal, payload=payload)["issueId"]
    other = finish(journal, payload={"ok": False, "error": {"code": "other"}})["issueId"]
    parent = journal.command({"action": "report", "category": "error", "title": "Verified fix", "observed": "Exact related incident."})["id"]
    journal.command({"action": "report", "id": child, "suggestion": "Preserve original note."})
    for related in ([child, "0" * 64], [parent]):
        with pytest.raises(BridgeError):
            journal.command({"action": "resolve", "id": parent, "relatedIds": related, "resolution": "Retest passed."})
        assert len(journal.command({"action": "read", "state": "open"})["entries"]) == 3
    journal.command({"action": "resolve", "id": parent, "relatedIds": [child], "resolution": "Retest passed.", "resolutionKind": "fixed"})
    rows = {r["id"]: r for r in journal.command({"action": "read"})["entries"]}
    assert rows[other]["state"] == "open" and rows[parent]["state"] == rows[child]["state"] == "resolved"
    assert rows[child]["agentSummary"]["suggestion"] == "Preserve original note."
    assert rows[child]["agentSummary"]["resolvedWith"] == parent
    assert rows[child]["occurrences"] == 1
    finish(journal, payload=payload)
    rows = {r["id"]: r for r in journal.command({"action": "read"})["entries"]}
    assert rows[parent]["state"] == rows[child]["state"] == "open" and rows[child]["occurrences"] == 2
    assert rows[child]["agentSummary"]["resolution"] == "Retest passed."


def test_explicit_defer_survives_recurrence_and_report_can_resume(tmp_path):
    journal, _ = setup(tmp_path)
    payload = {"ok": False, "error": {"code": "no_path"}}
    identity = finish(journal, payload=payload)["issueId"]
    parent = journal.command({"action": "report", "category": "error", "title": "Prior repair", "observed": "Prior exact reproduction."})["id"]
    journal.command({"action": "resolve", "id": parent, "relatedIds": [identity], "resolution": "Prior test passed."})
    args = {"action": "defer", "id": identity, "reason": "User postponed reproduction.", "resumeWhen": "Reproduce in the chosen later world."}
    _validate("mcb_run_log", args)
    assert journal.command(args)["state"] == "deferred"
    finish(journal, payload=payload)
    entry = journal.command({"action": "read", "state": "deferred"})["entries"][0]
    assert entry["occurrences"] == 2 and entry["agentSummary"]["resumeWhen"] == args["resumeWhen"]
    assert journal.command({})["triage"]["deferred"] == 1
    assert journal.command({"action": "read", "id": parent})["entries"][0]["state"] == "resolved"
    journal.command({"action": "report", "id": identity, "observed": "User resumed investigation."})
    assert journal.command({"action": "read", "state": "open"})["entries"][0]["id"] == identity


@pytest.mark.parametrize("args", [
    {"action": "defer", "id": "a" * 64, "reason": "Missing resume condition"},
    {"action": "defer", "id": "a" * 64, "reason": "", "resumeWhen": "later"},
    {"action": "resolve", "id": "a" * 64, "resolution": "fixed", "relatedIds": ["b" * 64] * 2},
    {"action": "resolve", "id": "a" * 64, "resolution": "fixed", "relatedIds": [format(i, '064x') for i in range(33)]},
    {"action": "report", "id": "a" * 64, "relatedIds": ["b" * 64]},
    {"action": "read", "category": "normal", "includeDetails": True},
])
def test_triage_schema_rejects_unbounded_or_ambiguous_commands(args):
    with pytest.raises(BridgeError):
        _validate("mcb_run_log", args)
