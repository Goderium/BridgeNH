"""Bounded convenience tools: local fixture logs and explicitly mocked observations."""

import asyncio
import json

import anyio
import pytest

from minecraft_codex_bridge import conveniences
from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.conveniences import capture, json_size, machine_watch, read_logs
from minecraft_codex_bridge.server import Adapter

TOKEN = "MixedCase-LocalSecret-123"
POSITION = {"x": 0, "y": 64, "z": 0}


@pytest.fixture
def game_logs(tmp_path):
    game = tmp_path / ".minecraft"
    runtime = game / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1:25671", "token": TOKEN}), encoding="utf-8")
    log = game / "logs" / "latest.log"
    log.parent.mkdir()
    return Configuration({"MCB_RUNTIME_FILE": str(runtime)}), log


def test_log_levels_query_and_actual_byte_count(game_logs):
    configuration, log = game_logs
    content = "[INFO] normal\n[WARN] Some Machine warning\n[ERROR] machine failed\n[FATAL] machine stopped\n"
    log.write_text(content, encoding="utf-8", newline="")
    result = read_logs(configuration, {})
    assert result["lines"] == content.splitlines()[1:]
    assert result["bytesRead"] == len(content.encode("utf-8"))
    assert not result["truncated"]
    assert read_logs(configuration, {"level": "ERROR", "query": "MACHINE", "limit": 1})["lines"] == ["[FATAL] machine stopped"]
    assert read_logs(configuration, {"level": "ANY"})["returned"] == 4
    log.write_bytes(b"")
    assert read_logs(configuration, {})["bytesRead"] == 0


@pytest.mark.parametrize("secret_line", [
    "Authorization: Bearer arbitrary-secret", "accessToken=arbitrary-secret",
    "refresh_token: arbitrary-secret", "auth=arbitrary-secret",
    "password=arbitrary-secret", "passwd=arbitrary-secret",
    "launcherCredentials: arbitrary-secret", "sessionId=arbitrary-secret",
    "--username arbitrary-secret", "API_KEY=arbitrary-secret", TOKEN,
])
def test_log_redacts_sensitive_lines_before_filtering(game_logs, secret_line):
    configuration, log = game_logs
    log.write_text(f"[WARN] {secret_line}\n[WARN] readable\n", encoding="utf-8")
    result = read_logs(configuration, {})
    assert result["lines"] == ["[redacted sensitive log line]", "[WARN] readable"]
    assert result["redactedLines"] == 1
    assert "arbitrary-secret" not in json.dumps(result)
    assert TOKEN not in json.dumps(result)
    assert read_logs(configuration, {"query": "arbitrary-secret"})["lines"] == []
    query = read_logs(configuration, {"query": TOKEN})
    assert query["query"] == "<redacted>"
    assert TOKEN not in json.dumps(query)


def test_log_bounded_tail_lines_and_utf8(game_logs):
    configuration, log = game_logs
    log.write_text("[WARN] discarded history\n" * 10_000 + "[WARN] 中文机器\n" * 200, encoding="utf-8", newline="")
    result = read_logs(configuration, {"limit": 128})
    assert result["bytesRead"] == conveniences.MAX_LOG_TAIL_BYTES
    assert result["truncated"] is True
    assert result["returned"] == 128
    assert all(line == "[WARN] 中文机器" for line in result["lines"])
    assert sum(len(line.encode("utf-8")) for line in result["lines"]) <= conveniences.MAX_LOG_TEXT_BYTES


def test_log_omits_partial_sensitive_first_line(game_logs):
    configuration, log = game_logs
    log.write_bytes(b"[WARN] Authorization: " + b"s" * 80_000 + b"\n[WARN] safe final line\n")
    result = read_logs(configuration, {"level": "ANY"})
    assert result["lines"] == ["[WARN] safe final line"]
    assert result["bytesRead"] == conveniences.MAX_LOG_TAIL_BYTES
    assert result["truncated"] is True


def test_log_refuses_missing_runtime_invalid_location_and_missing_file(game_logs, tmp_path):
    configuration, _ = game_logs
    with pytest.raises(BridgeError) as missing_log:
        read_logs(configuration, {})
    assert missing_log.value.code == "LOG_UNAVAILABLE"
    with pytest.raises(BridgeError) as missing_runtime:
        read_logs(Configuration({"MCB_URL": "http://127.0.0.1", "MCB_TOKEN": TOKEN}), {})
    assert missing_runtime.value.code == "LOG_CONFIG_REQUIRED"
    runtime = tmp_path / "runtime.json"
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": "http://127.0.0.1", "token": TOKEN}), encoding="utf-8")
    with pytest.raises(BridgeError) as invalid_location:
        read_logs(Configuration({"MCB_RUNTIME_FILE": str(runtime)}), {})
    assert invalid_location.value.code == "LOG_CONFIG_REQUIRED"


def test_log_refuses_symlink_redirection(game_logs, tmp_path):
    configuration, log = game_logs
    outside = tmp_path / "outside.log"
    outside.write_text("[WARN] outside", encoding="utf-8")
    try:
        log.symlink_to(outside)
    except OSError:
        pytest.skip("Creating symlinks is unavailable on this Windows account")
    with pytest.raises(BridgeError) as redirected:
        read_logs(configuration, {})
    assert redirected.value.code == "LOG_PATH_REFUSED"


class Observations:
    def __init__(self, **overrides):
        self.calls = []
        self.values = {
            "bridge.health": {"sessionId": "test-session", "profile": "developer"},
            "client.snapshot": {"worldLoaded": True, "world": {"name": "test-world"}, "player": POSITION},
            "gui.inspect": {"screenId": "screen-1", "windowId": 2, "width": 400, "height": 300},
            "inventory.inspect": {"slots": []}, "client.diagnostics": {"fps": 60},
            "world.inspect_many": {"positions": []},
            "world.inspect": {"source": "integrated-server", "gregtech": {"progress": 5}},
            **overrides,
        }

    async def invoke(self, operation, arguments):
        self.calls.append((operation, arguments))
        value = self.values[operation]
        if isinstance(value, Exception):
            raise value
        if callable(value):
            value = value()
        return value


async def test_capture_bundle_metadata_and_optional_positions():
    client = Observations()
    result, screenshot = await capture(client, {"positions": [POSITION], "dimension": 0})
    assert screenshot is None
    assert result["atomic"] is False and result["partial"] is False
    assert result["sessionConsistent"] is True
    assert result["sessionId"] == result["sessionIdEnd"] == "test-session"
    assert result["origin"]["world"]["name"] == "test-world"
    assert result["window"]["windowId"] == 2
    assert result["startedAt"] <= result["endedAt"]
    assert ("world.inspect_many", {"positions": [POSITION], "dimension": 0}) in client.calls
    assert "client.screenshot" not in [operation for operation, _ in client.calls]


async def test_capture_no_world_skips_inventory_and_records_partial_error():
    client = Observations(**{"client.snapshot": {"worldLoaded": False}, "client.diagnostics": BridgeError("UNSUPPORTED_CAPABILITY", "Unavailable")})
    result, _ = await capture(client, {})
    assert "inventory.inspect" not in [operation for operation, _ in client.calls]
    assert result["observations"]["inventory"]["skipped"] is True
    assert result["partial"] is True
    assert result["errors"][0]["observation"] == "diagnostics"
    assert result["errors"][0]["code"] == "UNSUPPORTED_CAPABILITY"
    assert result["observations"]["healthEnd"]["ok"] is True


@pytest.mark.parametrize("sessions", [[None, None], ["first", "second"], ["", ""]])
async def test_capture_never_claims_missing_or_rotated_sessions_are_consistent(sessions):
    values = iter(sessions)
    client = Observations(**{"bridge.health": lambda: {"sessionId": next(values), "profile": "developer"}})
    result, _ = await capture(client, {})
    assert result["sessionConsistent"] is False


async def test_capture_preserves_metadata_on_collection_timeout(monkeypatch):
    class SlowInventory(Observations):
        async def invoke(self, operation, arguments):
            if operation == "inventory.inspect":
                await anyio.sleep(1)
            return await super().invoke(operation, arguments)

    monkeypatch.setattr(conveniences, "CAPTURE_TIMEOUT_SECONDS", 0.02)
    result, _ = await capture(SlowInventory(), {})
    assert result["partial"] is True
    assert result["sessionId"] == "test-session"
    assert result["origin"]["world"]["name"] == "test-world"
    assert result["window"]["windowId"] == 2
    assert result["errors"][-1]["code"] == "CAPTURE_TIMEOUT"
    assert result["sessionConsistent"] is False


async def test_capture_bounds_individual_and_total_observations():
    large = {"padding": "x" * 60_000}
    client = Observations(**{
        "client.snapshot": {"worldLoaded": True, **large}, "gui.inspect": large,
        "inventory.inspect": large, "client.diagnostics": large, "world.inspect_many": large,
    })
    result, _ = await capture(client, {"positions": [POSITION]})
    assert json_size(result) <= conveniences.MAX_CAPTURE_BYTES - 1024
    assert result["partial"] is True
    assert any(error["code"] == "CAPTURE_SIZE_LIMIT" for error in result["errors"])
    client.values["client.diagnostics"] = {"padding": "x" * 70_000}
    result, _ = await capture(client, {})
    assert result["observations"]["diagnostics"]["error"]["code"] == "OBSERVATION_TOO_LARGE"


async def test_capture_invalid_screenshot_is_partial_and_has_no_image():
    client = Observations(**{"client.screenshot": {"base64": "invalid", "width": 1, "height": 1}})
    result = await Adapter(client).call_tool("mcb_capture", {"include_screenshot": True})
    assert not result.isError and result.structuredContent["ok"] is True
    assert result.structuredContent["data"]["partial"] is True
    assert result.structuredContent["data"]["errors"][-1]["code"] == "MALFORMED_IMAGE"
    assert len(result.content) == 1
    assert [operation for operation, _ in client.calls].count("client.screenshot") == 1


async def test_watch_projects_bounded_values_and_missing_fields():
    client = Observations(**{"world.inspect": {"source": "integrated-server", "gregtech": {"progress": 10, "message": "中" * 10_000}, "slots": [{"count": 2}]}})
    result = await machine_watch(client, {**POSITION, "duration_ms": 100, "fields": ["gregtech.progress", "gregtech.message", "gregtech.missing", "slots.0.count"]})
    assert result["sampleCount"] == 1 and result["reason"] == "duration_elapsed"
    values = result["samples"][0]["values"]
    assert values["gregtech.progress"] == {"exists": True, "value": 10}
    assert values["gregtech.missing"] == {"exists": False}
    assert values["slots.0.count"]["value"] == 2
    assert values["gregtech.message"]["value"]["truncated"] is True
    assert json_size(values["gregtech.message"]) < conveniences.MAX_WATCH_VALUE_BYTES


@pytest.mark.parametrize("calculated_eut", [-9_007_199_254_740_993, 512])
async def test_watch_defaults_preserve_current_machine_eut_sign_and_long_precision(calculated_eut):
    client = Observations(**{"world.inspect": {"source": "integrated_server", "gregtech": {
        "progress": 10, "maxProgress": 20, "active": True, "allowedToWork": True,
        "storedEU": 10_000, "signedCalculatedEUt": calculated_eut,
        "eutMeaning": "Calculated machine EU/t; negative consumes, positive generates; not original recipe EU/t.",
        "recipeResultMessage": "working",
    }}})
    result = await machine_watch(client, {**POSITION, "duration_ms": 100})
    values = result["samples"][0]["values"]
    assert values["gregtech.signedCalculatedEUt"] == {"exists": True, "value": calculated_eut}
    assert "gregtech.rawEUt" not in values
    assert client.calls == [("world.inspect", POSITION)]


async def test_watch_bridge_error_returns_partial_samples(monkeypatch):
    replies = iter([{"gregtech": {"progress": 1}}, BridgeError("WORLD_NOT_LOADED", "World closed")])

    def reply():
        result = next(replies)
        if isinstance(result, Exception):
            raise result
        return result

    async def no_delay(_):
        pass

    monkeypatch.setattr(conveniences.anyio, "sleep", no_delay)
    client = Observations(**{"world.inspect": reply})
    result = await Adapter(client).call_tool("mcb_machine_watch", {**POSITION, "duration_ms": 100})
    assert result.isError and result.structuredContent["error"]["code"] == "WORLD_NOT_LOADED"
    assert result.structuredContent["data"]["sampleCount"] == 1
    assert result.structuredContent["data"]["reason"] == "bridge_error"
    assert len(client.calls) == 2


async def test_watch_sample_and_report_limits(monkeypatch):
    async def no_delay(_):
        pass

    monkeypatch.setattr(conveniences.anyio, "sleep", no_delay)
    result = await machine_watch(Observations(), {**POSITION, "duration_ms": 30_000})
    assert result["sampleCount"] == 64 and result["reason"] == "sample_limit"
    large = {f"field{i}": "x" * 1000 for i in range(16)}
    result = await machine_watch(Observations(**{"world.inspect": large}), {**POSITION, "duration_ms": 30_000, "fields": list(large)})
    assert 0 < result["sampleCount"] < 64
    assert result["reason"] == "report_size_limit" and result["truncated"] is True
    assert json_size(result) <= conveniences.MAX_WATCH_BYTES


async def test_capture_and_watch_propagate_cancellation():
    class Pending(Observations):
        def __init__(self):
            super().__init__()
            self.started = asyncio.Event()

        async def invoke(self, operation, arguments):
            self.calls.append((operation, arguments))
            self.started.set()
            await asyncio.sleep(60)

    for function, arguments in [(capture, {}), (machine_watch, {**POSITION, "duration_ms": 30_000})]:
        client = Pending()
        task = asyncio.create_task(function(client, arguments))
        await asyncio.wait_for(client.started.wait(), timeout=1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert len(client.calls) == 1
