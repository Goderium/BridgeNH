"""Runner tests use the real adapter and HTTP bridge without a game client."""

import importlib.util
import io
import json
import os
import runpy
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace

import httpx
import mcp
import pytest
from mcp import types

import minecraft_codex_bridge
from minecraft_codex_bridge import bridge, server


ROOT = Path(minecraft_codex_bridge.__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "call_tool.py"


@pytest.fixture
def runner(monkeypatch, capsys):
    spec = importlib.util.spec_from_file_location("minecraft_codex_bridge.call_tool_under_test",
        Path(minecraft_codex_bridge.__file__).parent / "tool_cli.py")
    module = importlib.util.module_from_spec(spec)
    # Import-time UTF-8 setup must not reconfigure pytest's own capture stream.
    with monkeypatch.context() as importing:
        importing.setattr(sys, "stdout", io.StringIO())
        spec.loader.exec_module(module)
    return module, capsys


def options(runtime, **updates):
    return SimpleNamespace(tool="mcb_snapshot", args="{}", runtime=str(runtime),
        output=None, image=None, transport="in-process", measure=False, **updates)


def runtime_file(tmp_path, name="chosen", port=25672, token="chosen-token"):
    path = tmp_path / (name + ".json")
    path.write_text(json.dumps({"protocolVersion": 1,
        "baseUrl": f"http://127.0.0.1:{port}", "token": token}), encoding="utf-8")
    return path


def envelope(request, *, data=None, error=None):
    body = json.loads(request.content)
    return httpx.Response(200, json={"requestId": body["requestId"],
        "ok": error is None, **({"data": data} if error is None else {"error": error})})


def mock_http(monkeypatch, respond):
    original = bridge.BridgeClient
    clients = []

    class Client(original):
        def __init__(self, configuration):
            super().__init__(configuration, transport=httpx.MockTransport(respond))
            self.close_count = 0
            clients.append(self)

        async def aclose(self):
            self.close_count += 1
            await super().aclose()

    monkeypatch.setattr(bridge, "BridgeClient", Client)
    return clients


def no_other_transport(monkeypatch, module, selected="in-process"):
    async def forbidden(*args):
        raise AssertionError("A failed call must not switch transport or replay.")
    monkeypatch.setattr(module, "call_stdio" if selected == "in-process" else "call_in_process", forbidden)


def closed_once(clients):
    assert len(clients) == 1 and clients[0].close_count == 1
    assert clients[0].http.is_closed


async def test_in_process_uses_http_adapter_and_private_runtime_copy(runner, monkeypatch, tmp_path):
    module, output = runner
    selected = runtime_file(tmp_path)
    inherited = runtime_file(tmp_path, "inherited", port=25671, token="inherited-token")
    monkeypatch.setenv("MCB_RUNTIME_FILE", str(inherited))
    monkeypatch.setenv("MCB_URL", "http://example.com")
    monkeypatch.setenv("MCB_TOKEN", "ignored-environment-token")
    before = dict(os.environ)
    requests = []

    def respond(request):
        assert request.method == "POST" and request.url.path == "/v1/call"
        assert request.url.port == 25672
        assert request.headers["Authorization"] == "Bearer chosen-token"
        operation = json.loads(request.content)["operation"]
        requests.append(operation)
        return envelope(request, data={"protocolVersion": 1, "capabilities": ["client.snapshot"]}
            if operation == "bridge.health" else {"sessionId": "chosen-session", "label": "中文"})

    clients = mock_http(monkeypatch, respond)
    no_other_transport(monkeypatch, module)
    source = str(ROOT / "mcp-server" / "src")
    monkeypatch.setattr(sys, "path", [entry for entry in sys.path if entry != source])
    assert await module.main(options(selected)) == 0
    actual = json.loads(output.readouterr().out)
    assert set(actual) == {"ok", "data", "mcpTiming", "agentRunLog"}
    # This transport fixture is a bare JSON file, not a selected game root.
    assert actual["agentRunLog"] == {"recorded": False,
        "reason": "RUN_LOG_CONFIG_REQUIRED", "gameResultPreserved": True}
    assert actual["ok"] is True and actual["data"] == {"sessionId": "chosen-session", "label": "中文"}
    assert actual["mcpTiming"]["bridgeCalls"] == 2
    assert actual["mcpTiming"]["persisted"] is False
    assert actual["mcpTiming"]["externalPlanningAndApprovalMeasured"] is False
    assert requests == ["bridge.health", "client.snapshot"]
    assert dict(os.environ) == before
    assert clients[0].configuration.environ is not os.environ
    assert clients[0].configuration.environ["MCB_RUNTIME_FILE"] == str(selected)
    closed_once(clients)


@pytest.mark.parametrize("tool,arguments,code", [
    ("mcb_hotbar", {"slot": 9}, "INVALID_ARGUMENTS"),
    ("mcb_missing_tool", {}, "UNKNOWN_TOOL"),
])
async def test_in_process_keeps_adapter_validation_before_http(runner, monkeypatch, tmp_path, tool, arguments, code):
    module, _ = runner

    def forbidden(request):
        raise AssertionError("Invalid arguments must not reach HTTP.")

    clients = mock_http(monkeypatch, forbidden)
    no_other_transport(monkeypatch, module)
    opts = options(runtime_file(tmp_path))
    opts.tool, opts.args = tool, json.dumps(arguments)
    # Exercise the lower transport directly: CLI preflight now refuses these
    # before a client exists, while adapter validation must remain independent.
    result, _, _ = await module.call_in_process(opts, arguments)
    assert result.isError and result.structuredContent["error"]["code"] == code
    closed_once(clients)


@pytest.mark.parametrize("failure", ["server", "transport", "takeover"])
async def test_in_process_preserves_takeover_errors_and_never_falls_back(runner, monkeypatch, tmp_path, failure):
    module, output = runner
    requests = []
    cancellations = []
    mutation_ids = []

    def respond(request):
        body = json.loads(request.content)
        if request.url.path == "/v1/cancel":
            cancellations.append(body["requestId"])
            return envelope(request, data={"status": "already_running"})
        operation = body["operation"]
        requests.append(operation)
        if operation == "bridge.health":
            return envelope(request, data={"protocolVersion": 1,
                "capabilities": ["player.hotbar", "client.background"], "background": {"enabled": False}})
        if operation == "client.background":
            return envelope(request, data={"enabled": True, "helperInstalled": failure != "takeover", "pauseOnLostFocus": False})
        assert operation == "player.hotbar" and json.loads(request.content)["args"] == {"slot": 2}
        mutation_ids.append(body["requestId"])
        if failure == "transport":
            raise httpx.ReadError("unknown mutation result", request=request)
        return envelope(request, error={"code": "PROFILE_DENIED", "message": "Native refusal.", "outcome_unknown": False})

    clients = mock_http(monkeypatch, respond)
    no_other_transport(monkeypatch, module)
    opts = options(runtime_file(tmp_path))
    opts.tool, opts.args = "mcb_hotbar", '{"slot":2}'
    assert await module.main(opts) == 1
    error = json.loads(output.readouterr().out)["error"]
    assert error["code"] == {"server": "PROFILE_DENIED", "transport": "BRIDGE_UNAVAILABLE", "takeover": "TAKEOVER_NOT_CONFIRMED"}[failure]
    assert requests == ["bridge.health", "client.background"] + ([] if failure == "takeover" else ["player.hotbar"])
    assert cancellations == (mutation_ids if failure == "transport" else [])
    if failure != "takeover":
        assert error["outcome_unknown"] is (failure == "transport")
    else:
        assert error["original_action_executed"] is False and error["stage"] == "takeover"
    closed_once(clients)


async def test_in_process_auth_error_keeps_error_exit_and_closes(runner, monkeypatch, tmp_path):
    module, output = runner
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(401, content=b"secret details must stay hidden")

    clients = mock_http(monkeypatch, respond)
    no_other_transport(monkeypatch, module)
    assert await module.main(options(runtime_file(tmp_path))) == 1
    rendered = output.readouterr().out
    assert json.loads(rendered)["error"]["code"] == "AUTH_FAILED"
    assert len(requests) == 1 and "secret details" not in rendered
    closed_once(clients)


@pytest.mark.parametrize("failure", ["initialization", "call"])
async def test_in_process_closes_even_when_adapter_raises(runner, monkeypatch, tmp_path, failure):
    module, _ = runner
    clients = mock_http(monkeypatch, lambda request: pytest.fail("No HTTP call expected."))

    class BrokenAdapter:
        def __init__(self, client):
            if failure == "initialization":
                raise RuntimeError("adapter initialization failed")

        async def call_tool(self, name, arguments):
            raise RuntimeError("adapter call failed")

    monkeypatch.setattr(server, "Adapter", BrokenAdapter)
    no_other_transport(monkeypatch, module)
    with pytest.raises(RuntimeError, match="adapter"):
        await module.main(options(runtime_file(tmp_path)))
    closed_once(clients)


@pytest.mark.parametrize("transport", ["stdio", "in-process"])
@pytest.mark.parametrize("measure", [False, True])
@pytest.mark.parametrize("is_error", [False, True])
async def test_stdin_text_result_output_and_measure_preserve_payload(runner, monkeypatch, tmp_path, transport, measure, is_error):
    module, output = runner
    payload = {"ok": not is_error, "error": {"code": "EXACT_ERROR", "message": "中文"}} if is_error else {"ok": True, "data": {"label": "中文"}}
    calls = []

    async def selected(options, arguments):
        calls.append((options.tool, arguments))
        return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(payload, ensure_ascii=False))],
            isError=is_error), 1.25, 2.5

    monkeypatch.setattr(module, "call_in_process" if transport == "in-process" else "call_stdio", selected)
    no_other_transport(monkeypatch, module, transport)
    monkeypatch.setattr(sys, "stdin", io.TextIOWrapper(io.BytesIO(b"\xef\xbb\xbf" + '{"query":"中文"}'.encode("utf-8")), encoding="utf-8"))
    opts = options(tmp_path / "unused.json")
    opts.tool = "mcb_bindings"
    opts.transport, opts.measure, opts.args, opts.output = transport, measure, "-", str(tmp_path / "输出" / "result.json")
    assert await module.main(opts) == int(is_error)
    actual = json.loads(output.readouterr().out)
    assert actual == json.loads(Path(opts.output).read_text(encoding="utf-8"))
    assert calls == [("mcb_bindings", {"query": "中文"})]
    if measure:
        assert set(actual) == {"result", "timing"} and actual["result"] == payload
        assert actual["timing"]["transport"] == transport
        assert actual["timing"]["setupMs"] == 1.25 and actual["timing"]["toolMs"] == 2.5
        assert actual["timing"]["totalMs"] >= 0
        assert "excludes interpreter/shell startup, JSON rendering and agent decisions" in actual["timing"]["scope"]
    else:
        assert actual == payload and "timing" not in actual


def test_default_cli_still_initializes_stdio_without_measure(monkeypatch, tmp_path):
    output = io.StringIO()
    monkeypatch.setattr(sys, "stdout", output)
    runtime = tmp_path / "selected/.minecraft/config/codexbridge/runtime.json"
    monkeypatch.setenv("CODEX_THREAD_ID", "cli-chat")
    monkeypatch.setattr(sys, "argv", [str(SCRIPT), "mcb_health", "--runtime", str(runtime)])
    events = []
    parameters = []

    @asynccontextmanager
    async def stdio(parameters_value):
        parameters.append(parameters_value)
        events.append("stdio")
        yield "read", "write"

    class Session:
        def __init__(self, read, write):
            assert (read, write) == ("read", "write")

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            events.append("closed")

        async def initialize(self):
            events.append("initialize")

        async def call_tool(self, name, arguments):
            if name == "mcb_connection":
                if not arguments:
                    events.append("binding-status")
                    return types.CallToolResult(content=[], structuredContent={"ok": True, "data": {"bindingGeneration": 0}})
                from minecraft_codex_bridge.connection_binding import client_id
                assert arguments == {"action": "bind", "requestId": "explicit-cli-target",
                    "expectedConversationId": "cli-chat", "expectedBindingGeneration": 0,
                    "clientId": client_id(runtime.parents[3])}
                events.append("bind")
                return types.CallToolResult(content=[], structuredContent={"ok": True, "data": {"bound": True}})
            assert name == "mcb_health" and arguments == {}
            events.append("call")
            return types.CallToolResult(content=[], structuredContent={"ok": True, "data": {"protocolVersion": 1}}, isError=False)

    monkeypatch.setattr(mcp, "ClientSession", Session)
    monkeypatch.setattr(sys.modules["mcp.client.stdio"], "stdio_client", stdio)
    with pytest.raises(SystemExit) as caught:
        runpy.run_path(str(SCRIPT), run_name="__main__")
    assert caught.value.code == 0 and events == ["stdio", "initialize", "binding-status", "bind", "call", "closed"]
    assert parameters[0].command == sys.executable and parameters[0].args == ["-m", "minecraft_codex_bridge"]
    assert parameters[0].env["MCB_RUNTIME_FILE"] == str(runtime) and parameters[0].env["PYTHONUTF8"] == "1"
    assert json.loads(output.getvalue()) == {"ok": True, "data": {"protocolVersion": 1}}


def test_cli_rejects_unknown_transport_before_dispatch(monkeypatch, capsys, tmp_path):
    monkeypatch.setattr(sys, "argv", [str(SCRIPT), "mcb_health", "--runtime", str(tmp_path / "unused.json"), "--transport", "unknown"])
    with pytest.raises(SystemExit) as caught:
        runpy.run_path(str(SCRIPT), run_name="__main__")
    assert caught.value.code == 2 and "invalid choice" in capsys.readouterr().err
