"""JSON stdin reaches the same native MCP client path without shell quoting."""

import importlib.util
import io
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace

import pytest


JSON_ARGUMENTS = '{"screenId":"新的世界","widgetId":"widget:1","action":"set_text","text":"制冷机 x2"}'


@pytest.mark.parametrize(
    "argument,stdin_bytes",
    [
        ("-", JSON_ARGUMENTS.encode("utf-8")),
        ("-", JSON_ARGUMENTS.encode("utf-8-sig")),
        (JSON_ARGUMENTS, b"stdin must not be read"),
    ],
)
async def test_existing_cli_accepts_stdin_or_literal_json_and_uses_current_checkout(
    monkeypatch, capsys, tmp_path, argument, stdin_bytes
):
    path = Path(__file__).resolve().parents[1] / "src/minecraft_codex_bridge/tool_cli.py"
    spec = importlib.util.spec_from_file_location("minecraft_codex_bridge.cli_input_test", path)
    cli = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cli)
    captured = {}

    @asynccontextmanager
    async def stdio(parameters):
        captured["entry"] = parameters.args
        yield None, None

    class Session:
        def __init__(self, *streams):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            pass

        async def initialize(self):
            pass

        async def call_tool(self, name, arguments):
            if name == "mcb_connection":
                return SimpleNamespace(structuredContent={"ok": True, "data": {"bindingGeneration": 0}}, isError=False)
            captured.update(tool=name, arguments=arguments)
            return SimpleNamespace(structuredContent={"ok": True}, isError=False)

    monkeypatch.setattr(cli, "stdio_client", stdio)
    monkeypatch.setattr(cli, "ClientSession", Session)
    monkeypatch.setenv("CODEX_THREAD_ID", "input-fixture-chat")
    monkeypatch.setattr(cli.sys, "stdin", io.TextIOWrapper(io.BytesIO(stdin_bytes), encoding="gbk"))
    options = SimpleNamespace(runtime=str(tmp_path / "instance/.minecraft/config/codexbridge/runtime.json"), tool="mcb_gui_widget_action", args=argument, image=None, output=None)
    assert await cli.main(options) == 0
    assert captured["entry"] == ["-m", "minecraft_codex_bridge"]
    assert captured["tool"] == "mcb_gui_widget_action" and captured["arguments"] == {
        "screenId": "新的世界", "widgetId": "widget:1", "action": "set_text",
        "text": "制冷机 x2",
    }
    assert '"ok": true' in capsys.readouterr().out
