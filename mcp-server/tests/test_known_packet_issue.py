import json

import pytest

from minecraft_codex_bridge.bridge import Configuration
from minecraft_codex_bridge.conveniences import read_logs
from minecraft_codex_bridge.known_issues import recognize
from minecraft_codex_bridge.server import Adapter


@pytest.fixture
def fixed_logs(tmp_path):
    root = tmp_path / ".minecraft"
    runtime = root / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671", "token": "issue-test-token"}), encoding="utf-8")
    (root / "logs").mkdir()
    (root / "logs" / "latest.log").write_text("[INFO] ordinary log\n", encoding="utf-8")
    client = root / "logs" / "fml-client-latest.log"
    client.write_text("[ERROR] NetworkDispatcher exception\njava.lang.IllegalArgumentException: Payload may not be larger than 32k\n"
                      "\tat net.minecraft.network.play.client.C17PacketCustomPayload.<init>(SourceFile:38)\n", encoding="utf-8")
    return Configuration({"MCB_RUNTIME_FILE": str(runtime)}), client


def test_fixed_fml_log_recognition_preserves_known_conditions_and_current_unknowns(fixed_logs):
    config, _ = fixed_logs
    data = read_logs(config, {"log": "fml-client-latest", "level": "ANY"})
    assert data["source"] == "logs/fml-client-latest.log" and data["returned"] == 3
    issue = data["knownIssues"][0]
    assert issue["observedPacketPath"] == "client_to_server_C17"
    prior = issue["knownReproduction"]
    assert prior["cycleConfiguration"] == 100 and prior["outputPorts"] == 0 and prior["voiding"] == "VOID_ALL"
    assert "not established" in prior["evidenceLimit"]
    assert "not a repair target" in issue["nextAction"]


def test_stack_signature_is_recognized_even_when_default_level_omits_stack_lines(fixed_logs):
    config, _ = fixed_logs
    data = read_logs(config, {"log": "fml-client-latest"})
    assert data["lines"] == ["[ERROR] NetworkDispatcher exception"]
    assert data["knownIssues"][0]["recognitionScope"] == "sanitized_bounded_log_tail"


def test_default_latest_log_has_no_unrelated_historical_error(fixed_logs):
    config, _ = fixed_logs
    data = read_logs(config, {"level": "ANY"})
    assert data["source"] == "logs/latest.log" and "knownIssues" not in data


def test_sensitive_lines_do_not_become_issue_evidence(fixed_logs):
    config, client = fixed_logs
    client.write_text("[ERROR] password=Payload may not be larger than 32k\n", encoding="utf-8")
    data = read_logs(config, {"log": "fml-client-latest", "level": "ANY"})
    assert data["lines"] == ["[redacted sensitive log line]"] and "knownIssues" not in data


@pytest.mark.parametrize("log", ["../server", "../../secrets", "fml-server-latest", "D:/other.log"])
async def test_tool_never_accepts_arbitrary_log_paths(log):
    reply = await Adapter(object()).call_tool("mcb_logs", {"log": log})
    assert reply.isError and reply.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"


def test_matching_message_does_not_invent_packet_direction_or_current_machine():
    assert recognize(["Payload may not be larger than 32k"])[0]["observedPacketPath"] == "unknown"
    assert recognize(["Payload may not be larger than 32k", "S3FPacketCustomPayload.<init>"])[0]["observedPacketPath"] == "server_to_client_S3F"
    assert recognize(["32767", "NetworkDispatcher exception"]) == []


def test_unrelated_packet_frames_do_not_change_latest_exception_direction():
    signature = "java.lang.IllegalArgumentException: Payload may not be larger than 32k"
    issue = recognize([
        "[INFO] prior C17PacketCustomPayload.<init>", signature,
        "\tat net.minecraft.network.play.server.S3FPacketCustomPayload.<init>(SourceFile:38)",
        "[ERROR] another exception", "\tat C17PacketCustomPayload.<init>(SourceFile:38)",
    ])[0]
    assert issue["observedPacketPath"] == "server_to_client_S3F"
    assert issue["directionScope"] == "latest_matching_exception_stack"


def test_latest_occurrence_without_its_stack_keeps_direction_unknown():
    signature = "Payload may not be larger than 32k"
    issue = recognize([signature, "\tat C17PacketCustomPayload.<init>(SourceFile:38)",
                       "[INFO] connection changed", signature])[0]
    assert issue["occurrencesInTail"] == 2 and issue["observedPacketPath"] == "unknown"
