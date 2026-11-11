"""Local SQLite and MCP tests; all game observations here are synthetic fixtures."""
from datetime import timedelta
import json
from pathlib import Path
import sqlite3
import hashlib

import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration
from minecraft_codex_bridge.knowledge import Capture, KnowledgeStore
from minecraft_codex_bridge.server import Adapter, _validate, make_server
from minecraft_codex_bridge.agent_instructions import AGENT_INSTRUCTIONS
from minecraft_codex_bridge import operation_knowledge


def store_for(tmp_path):
    instance = tmp_path / "client"
    mods = instance / ".minecraft" / "mods"
    mods.mkdir(parents=True)
    (mods / "mock-only.jar").write_bytes(b"synthetic manifest entry; not a real mod")
    return KnowledgeStore({"MCB_KNOWLEDGE_PATH": str(tmp_path / "experience.sqlite3")}), instance


def observation(instance, tool="mcb_approach", session="session-one"):
    return Capture(tool, session=session, instance=str(instance), versions={"protocolVersion": 1,
        "modVersion": "unit-test", "gameVersion": "1.7.10"}, capability_digest="mock-stable-capabilities",
        world="mock-world-digest", dimension=0, profile="developer", environment="local_integrated", health_observed=True)


def save(store, instance, *, session="session-one", tool="mcb_approach", data=None, error=None, args=None):
    payload = {"ok": error is None, "data": data or {"status": "completed", "interactionEffectConfirmed": True}}
    if error is not None:
        payload["error"] = error
    result = store.finish(observation(instance, tool, session), args or {}, payload,
        {"adapterElapsedMs": 25, "bridgeCalls": 1}, read_only=False)
    assert result and result["recorded"]
    return result


def learned_args(identity, **updates):
    return {"action": "learn", "tool": "mcb_approach", "kind": "procedure", "title": "Reuse a reachable stand",
        "when": "A currently observed target is already in interaction reach.",
        "instruction": "Ask the client for one guarded approach with interaction; retain a valid in-reach stand.",
        "verification": "Require the declared fresh dual-owner GUI acceptance, not only a crosshair hit.",
        "evidenceIds": [identity], **updates}


def test_repeated_accepted_method_distills_automatically_without_another_tool_call(tmp_path):
    store, instance = store_for(tmp_path)
    first = save(store, instance)
    assert first["operatingLessons"]["lessons"] == []
    save(store, instance, session="session-two")
    final = save(store, instance, session="session-two")
    lesson = final["operatingLessons"]["lessons"][0]
    assert lesson["origin"] == "automatic" and lesson["kind"] == "accepted_method"
    assert lesson["state"] == "corroborated_pattern" and lesson["independentSessions"] == 2
    assert lesson["occurrences"] == 3 and lesson["promotionCandidate"]
    assert not final["operatingLessons"]["automaticReplayAllowed"]
    assert "evidenceIds" not in lesson and "binding" not in lesson
    detailed = store.command({"action": "lessons", "id": lesson["id"]})["lessons"][0]
    assert not detailed["promotion"]["automaticCodeChange"] and detailed["requiresLiveRevalidation"]
    assert "never proves production" in lesson["verification"]
    assert final["operatingLessons"]["builtin"][0]["id"] == "mcb.interaction_goal"


def test_single_session_and_returned_acknowledgments_never_become_validated_knowledge(tmp_path):
    store, instance = store_for(tmp_path)
    for _ in range(4):
        result = save(store, instance)
    lesson = result["operatingLessons"]["lessons"][0]
    assert lesson["state"] == "observed_pattern" and not lesson["promotionCandidate"]
    for _ in range(3):
        result = save(store, instance, tool="mcb_slot_click", data={"status": "scheduled", "complete": True})
    assert result["operatingLessons"]["lessons"] == []


def test_failures_unknown_effects_and_mixed_outcomes_do_not_infer_recovery_or_fastest_method(tmp_path):
    store, instance = store_for(tmp_path)
    for session in ("session-one", "session-two", "session-two"):
        save(store, instance, session=session)
    error = {"code": "NO_INTERACTION_STAND", "outcome_unknown": False}
    save(store, instance, error=error)
    result = save(store, instance, error=error)
    lessons = result["operatingLessons"]["lessons"]
    accepted = next(lesson for lesson in lessons if lesson["kind"] == "accepted_method")
    failed = next(lesson for lesson in lessons if lesson["kind"] == "failure_pattern")
    assert result["hints"] == []
    assert accepted["state"] == "mixed_outcomes" and not accepted["promotionCandidate"]
    assert "not its root cause" in failed["instruction"]
    for _ in range(2):
        unknown = save(store, instance, data={"status": "completed", "sideEffectsPossible": True})
    assert unknown["evidence"] == "uncertain"
    caution = next(item for item in unknown["operatingLessons"]["builtin"] if item["id"] == "mcb.uncertain_result")
    assert "Do not replay" in caution["instruction"]
    review = store.command({"action": "review", "limit": 5})
    uncertain = next(item for item in review["lessons"] if item["kind"] == "uncertain_effects")
    assert not uncertain["promotionCandidate"]
    assert "no causal method ranking" in uncertain["timing"]["scope"]


def test_evidence_backed_agent_lesson_dedups_and_redacts_but_does_not_prove_its_interpretation(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    save(store, instance, session="session-two")
    save(store, instance, session="session-two")
    args = learned_args(identity, instruction="Use client approach. private-current-secret\nInspect D:\\private\\client 127.0.0.1")
    first = store.command(args, token="private-current-secret")
    second = store.command(args, token="private-current-secret")
    lesson = first["lesson"]
    assert lesson["id"] == second["lesson"]["id"]
    assert store.command({})["operatingLessons"] == 1
    assert lesson["origin"] == "agent_reported" and not lesson["interpretationVerified"]
    assert lesson["promotionCandidate"] and lesson["acceptanceEvidence"]
    assert not first["sourceCodeChanged"] and not first["automaticPolicyPromotion"]
    serialized = json.dumps(lesson)
    assert "private-current-secret" not in serialized and "127.0.0.1" not in serialized and "D:\\private" not in serialized


def test_evidence_refusal_is_exact_for_missing_tool_context_and_overlapping_records(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    with pytest.raises(BridgeError, match="match this client context"):
        store.command(learned_args("0" * 64))
    with pytest.raises(BridgeError, match="match this client context"):
        store.command(learned_args(identity, tool="mcb_slot_click"))
    with pytest.raises(BridgeError, match="must be distinct"):
        store.command(learned_args(identity, counterexampleIds=[identity]))
    store.context = {**store.context, "id": "other-context"}
    with pytest.raises(BridgeError, match="match this client context"):
        store.command(learned_args(identity))
    store.context = None
    with pytest.raises(BridgeError, match="Observe the current"):
        store.command(learned_args(identity))


def test_counterexamples_missing_sources_and_retirement_stop_automatic_suggestions(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    args = learned_args(identity)
    saved = store.command(args)["lesson"]
    failure = save(store, instance, error={"code": "blocked"})["recordId"]
    disputed = store.command({**args, "counterexampleIds": [failure]})["lesson"]
    assert disputed["id"] == saved["id"] and disputed["state"] == "disputed"
    assert not any(item["origin"] == "agent_reported" for item in store.command({"action": "lessons"})["lessons"])
    assert any(item["state"] == "disputed" for item in store.command({"action": "review"})["lessons"])
    store.command({"action": "forget", "id": identity})
    note = next(item for item in store.command({"action": "review"})["lessons"] if item["origin"] == "agent_reported")
    assert not note["evidenceComplete"] and not note["promotionCandidate"]
    assert store.command({"action": "retire", "id": saved["id"]})["retired"]
    assert not any(item["origin"] == "agent_reported" for item in store.command({"action": "review"})["lessons"])


def test_offline_review_survives_new_adapters_but_never_applies_an_old_binding(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    store.command(learned_args(identity))
    for _ in range(2):
        save(store, instance, error={"code": "GUI_ALREADY_OPEN"})
    offline = KnowledgeStore({"MCB_KNOWLEDGE_PATH": str(store.path)})
    assert not offline.command({"action": "lessons"})["available"]
    review = offline.command({"action": "review", "limit": 5})
    assert review["scope"] == "offline_review_only" and len(review["lessons"]) == 2
    assert not any(item["appliesToCurrentBinding"] for item in review["lessons"])
    fixed = offline.command({"action": "lessons", "tool": "mcb_approach"})
    assert fixed["builtin"][0]["origin"] == "builtin" and not fixed["historyAvailable"]
    store.check_session("restarted-client")
    assert store.context is None and not store.command({"action": "lessons"})["available"]


def test_additive_database_upgrade_preserves_existing_records_and_sources_expire(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    with sqlite3.connect(store.path) as db:
        db.execute("DROP TABLE operation_lessons")
    assert store.command({})["records"] == 1
    note = store.command(learned_args(identity))["lesson"]
    with sqlite3.connect(store.path) as db:
        db.execute("UPDATE experiences SET last_at=1")
    review = store.command({"action": "review"})
    assert review["lessons"][0]["state"] == "needs_revalidation"
    assert review["lessons"][0]["id"] == note["id"]
    assert store.command({"action": "lessons"})["lessons"] == []


def test_exact_review_can_inspect_retired_notes_and_forget_releases_the_cap(tmp_path, monkeypatch):
    store, instance = store_for(tmp_path)
    identity = save(store, instance)["recordId"]
    note = store.command(learned_args(identity))["lesson"]
    store.command({"action": "retire", "id": note["id"]})
    exact = store.command({"action": "review", "id": note["id"]})["lessons"]
    assert len(exact) == 1 and exact[0]["state"] == "retired"
    monkeypatch.setattr(operation_knowledge, "MAX_LESSONS", 1)
    with pytest.raises(BridgeError, match="lesson limit is full"):
        store.command(learned_args(identity, title="Another lesson"))
    assert store.command({"action": "forget", "id": note["id"]})["forgotten"]
    assert store.command(learned_args(identity, title="Another lesson"))["saved"]


def test_review_is_bounded_and_marks_omitted_history_in_a_large_store(tmp_path):
    store, instance = store_for(tmp_path)
    save(store, instance, error={"code": "original"})
    with sqlite3.connect(store.path) as db:
        template = list(db.execute("SELECT * FROM experiences").fetchone())
        for index in range(300):
            row = list(template)
            row[0] = hashlib.sha256(str(index).encode()).hexdigest()
            row[8] = 2
            value = json.loads(row[13])
            value["facts"]["failure"]["code"] = "failure_" + str(index)
            row[13] = json.dumps(value)
            db.execute("INSERT INTO experiences VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
    review = store.command({"action": "review", "limit": 5})
    assert len(review["lessons"]) == 5 and review["hasMore"] and review["scanLimited"]
    assert review["recordsInspectedMax"] == 256


def test_distillation_does_not_write_or_return_raw_dtos_arguments_or_images(tmp_path):
    store, instance = store_for(tmp_path)
    for _ in range(3):
        result = save(store, instance, data={"status": "completed", "interactionEffectConfirmed": True,
            "screenshot": "UNIQUE_IMAGE", "inventory": [{"NBT": "UNIQUE_NBT"}], "message": "UNIQUE_CHAT"},
            args={"command": "UNIQUE_COMMAND", "path": "UNIQUE_PATH"})
    serialized = json.dumps(result)
    with sqlite3.connect(store.path) as db:
        serialized += "".join(row[0] for row in db.execute("SELECT summary FROM experiences"))
    for marker in ("UNIQUE_IMAGE", "UNIQUE_NBT", "UNIQUE_CHAT", "UNIQUE_COMMAND", "UNIQUE_PATH"):
        assert marker not in serialized


def test_automatic_advice_does_not_cross_movement_or_inventory_methods(tmp_path):
    store, instance = store_for(tmp_path)
    identity = save(store, instance, args={"strategy": "walk", "actualSurvival": True})["recordId"]
    save(store, instance, args={"strategy": "walk", "actualSurvival": True})
    store.command(learned_args(identity))
    creative_flight = save(store, instance, args={"strategy": "fly", "actualSurvival": False})
    assert creative_flight["operatingLessons"]["lessons"] == []
    survival_walk = save(store, instance, args={"strategy": "walk", "actualSurvival": True})
    assert len(survival_walk["operatingLessons"]["lessons"]) == 2


@pytest.mark.parametrize("args", [
    {"action": "learn"}, learned_args("bad-id"), learned_args("0" * 64, evidenceIds=[]),
    learned_args("0" * 64, evidenceIds=["0" * 64] * 2), learned_args("0" * 64, instruction=""),
    {"action": "review", "path": "elsewhere"}, {"action": "lessons", "limit": 100},
    {"action": "retire", "id": "../../outside"},
])
def test_tool_schema_bounds_lessons_and_evidence(args):
    with pytest.raises(BridgeError):
        _validate("mcb_knowledge", args)


async def test_real_sdk_can_review_and_read_builtin_lessons_without_game_rpcs(tmp_path):
    def forbidden(request):
        raise AssertionError("Local lesson tools must not call Minecraft")
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "mock-only",
        "MCB_KNOWLEDGE_PATH": str(tmp_path / "sdk-experience.sqlite3")}), transport=httpx.MockTransport(forbidden))
    try:
        async with create_connected_server_and_client_session(
            make_server(client), read_timeout_seconds=timedelta(seconds=10), raise_exceptions=True,
        ) as session:
            tools = {tool.name: tool for tool in (await session.list_tools()).tools}
            assert "learn" in tools["mcb_knowledge"].inputSchema["properties"]["action"]["enum"]
            review = await session.call_tool("mcb_knowledge", {"action": "review"})
            assert not review.isError and review.structuredContent["data"]["scope"] == "offline_review_only"
            builtin = await session.call_tool("mcb_knowledge", {"action": "lessons", "tool": "mcb_approach"})
            assert not builtin.isError and builtin.structuredContent["data"]["builtin"][0]["origin"] == "builtin"
    finally:
        await client.aclose()


async def test_adapter_health_and_failures_return_lessons_without_extra_game_rpcs(tmp_path):
    store, instance = store_for(tmp_path)
    runtime = instance / ".minecraft/config/codexbridge/runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671",
        "token": "mock-only", "instancePath": str(instance), "sessionId": "session-one"}), encoding="utf-8")
    operations = []
    def respond(request):
        body = json.loads(request.content)
        operations.append(body["operation"])
        if body["operation"] == "bridge.health":
            return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {
                "protocolVersion": 1, "modVersion": "unit-test", "gameVersion": "1.7.10", "profile": "developer",
                "instancePath": str(instance), "sessionId": "session-one",
                "readiness": {"localIntegratedServer": True}, "capabilities": ["bridge.health", "client.snapshot"]}})
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
            "error": {"code": "busy", "message": "Synthetic queue failure"}})
    client = BridgeClient(Configuration({"MCB_RUNTIME_FILE": str(runtime), "MCB_KNOWLEDGE_PATH": str(store.path)}),
        transport=httpx.MockTransport(respond))
    try:
        adapter = Adapter(client)
        health = await adapter.call_tool("mcb_health", {})
        assert not health.isError and "operatingLessons" in health.structuredContent["localKnowledge"]
        assert operations == ["bridge.health"]
        assert adapter.knowledge.command({})["records"] == 0
        await adapter.call_tool("mcb_snapshot", {})
        failed = await adapter.call_tool("mcb_snapshot", {})
        assert failed.structuredContent["error"]["code"] == "busy"
        lesson = failed.structuredContent["localKnowledge"]["operatingLessons"]["lessons"][0]
        assert lesson["kind"] == "failure_pattern" and lesson["occurrences"] == 2
        assert operations == ["bridge.health", "bridge.health", "client.snapshot", "bridge.health", "client.snapshot"]
    finally:
        await client.aclose()


def test_startup_remains_compact_and_directs_agents_to_automatic_lessons():
    assert len(AGENT_INSTRUCTIONS.encode("utf-8")) <= 4096
    assert "operatingLessons auto; learn/review" in AGENT_INSTRUCTIONS
