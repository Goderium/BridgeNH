"""Call a bridge tool through MCP stdio or the same local MCP adapter."""

import argparse
import asyncio
import base64
import json
import os
import sys
import time
from pathlib import Path

from mcp import ClientSession, StdioServerParameters, types
from mcp.client.stdio import stdio_client
from .local_config import configured_environment

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


async def call_stdio(options, arguments):
    started = time.perf_counter()
    environment = {**os.environ, **({"MCB_RUNTIME_FILE": options.runtime} if options.runtime else {}), "PYTHONUTF8": "1"}
    parameters = StdioServerParameters(
        command=sys.executable,
        args=["-m", "minecraft_codex_bridge"],
        env=environment,
    )
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            ready = time.perf_counter()
            # This runner creates a separate, temporary MCP connection. An explicitly
            # supplied CLI target may bind that connection, never the chat's live one.
            explicit_target = options.runtime or any(environment.get(key) for key in
                ("MCB_PROFILE", "MCB_INSTANCE_PATH", "MCB_RUNTIME_FILE"))
            if explicit_target and options.tool not in {"mcb_help", "mcb_clients", "mcb_connection", "mcb_backend_status", "mcb_backend_restart"}:
                from .bridge import BridgeError, Configuration
                from .connection_binding import client_id
                from .frontend import result as as_result
                try:
                    runtime = Configuration(configured_environment(environment)).runtime_path()
                    if runtime is None or not environment.get("CODEX_THREAD_ID"):
                        raise BridgeError("CONVERSATION_ID_REQUIRED", "Stdio selection needs this chat's CODEX_THREAD_ID and an explicit instance target. A standalone terminal can use the explicit-target in-process transport.")
                    if [part.casefold() for part in runtime.parts[-4:]] != [".minecraft", "config", "codexbridge", "runtime.json"]:
                        raise BridgeError("BINDING_RUNTIME_PATH_REQUIRED", "Stdio binding requires the selected instance's .minecraft/config/codexbridge/runtime.json.")
                    status = (await session.call_tool("mcb_connection", {})).structuredContent["data"]
                    bound = await session.call_tool("mcb_connection", {"action": "bind", "requestId": "explicit-cli-target",
                        "clientId": client_id(runtime.resolve().parents[3]),
                        "expectedConversationId": environment["CODEX_THREAD_ID"],
                        "expectedBindingGeneration": status["bindingGeneration"]})
                    if bound.isError:
                        return bound, (ready - started) * 1000, (time.perf_counter() - ready) * 1000
                except BridgeError as error:
                    return as_result({"ok": False, "error": error.as_dict()}), (ready - started) * 1000, 0
            result = await session.call_tool(options.tool, arguments)
            finished = time.perf_counter()
    return result, (ready - started) * 1000, (finished - ready) * 1000


async def call_in_process(options, arguments):
    started = time.perf_counter()
    from minecraft_codex_bridge.bridge import BridgeClient, Configuration
    from minecraft_codex_bridge.server import Adapter

    client = BridgeClient(Configuration(configured_environment({**os.environ, **({"MCB_RUNTIME_FILE": options.runtime} if options.runtime else {})})))
    try:
        adapter = Adapter(client)
        ready = time.perf_counter()
        # Keep schema validation, takeover, authenticated transport and uncertainty handling.
        result = await adapter.call_tool(options.tool, arguments)
        finished = time.perf_counter()
    finally:
        await client.aclose()
    return result, (ready - started) * 1000, (finished - ready) * 1000


async def main(options):
    started = time.perf_counter()
    from minecraft_codex_bridge.bridge import BridgeError
    from minecraft_codex_bridge.validation import validate_tool_arguments

    transport = getattr(options, "transport", "stdio")
    caller = call_in_process if transport == "in-process" else call_stdio
    validation_started = time.perf_counter()
    failure = None
    try:
        argument_text = sys.stdin.buffer.read().decode("utf-8-sig") if options.args == "-" else options.args
        arguments = json.loads(argument_text)
        if options.tool in {"mcb_clients", "mcb_connection", "mcb_backend_status", "mcb_backend_restart"}:
            import jsonschema
            from .frontend import management_tools
            schema = next(tool.inputSchema for tool in management_tools() if tool.name == options.tool)
            try:
                jsonschema.Draft202012Validator(schema).validate(arguments)
            except jsonschema.ValidationError:
                raise BridgeError("INVALID_ARGUMENTS", "Invalid MCP management tool arguments.") from None
            if transport != "stdio":
                raise BridgeError("MCP_TRANSPORT_REQUIRED", "Connection management belongs to a live MCP frontend; use stdio or the chat's MCP tools.")
        else:
            validate_tool_arguments(options.tool, arguments)
    except BridgeError as error:
        failure = error.as_dict()
    except (json.JSONDecodeError, UnicodeError, RecursionError):
        failure = {"code": "INVALID_ARGUMENTS", "message": "Expected one valid UTF-8 JSON argument document."}
    preflight_ms = (time.perf_counter() - validation_started) * 1000
    if failure is not None:
        value = {"ok": False, "error": failure,
                 "preflight": {"source": "bundled_tool_schema", "transportStarted": False, "gameCalls": 0}}
        result = types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(value, ensure_ascii=False))],
                                      structuredContent=value, isError=True)
        setup_ms = tool_ms = 0
    else:
        result, setup_ms, tool_ms = await caller(options, arguments)
    data = result.structuredContent
    if data is None:
        data = json.loads(next(part.text for part in result.content if isinstance(part, types.TextContent)))
    if options.image:
        images = [part for part in result.content if isinstance(part, types.ImageContent)]
        if images:
            destination = Path(options.image)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(base64.b64decode(images[0].data))
            data["imagePath"] = str(destination.resolve())
    if getattr(options, "measure", False):
        data = {"result": data, "timing": {
            "transport": transport, "setupMs": round(setup_ms, 3),
            "preflightMs": round(preflight_ms, 3),
            "toolMs": round(tool_ms, 3), "totalMs": round((time.perf_counter() - started) * 1000, 3),
            "scope": "Runner only; excludes interpreter/shell startup, JSON rendering and agent decisions.",
        }}
    serialized = json.dumps(data, ensure_ascii=False, indent=2)
    if options.output:
        destination = Path(options.output)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(serialized, encoding="utf-8")
    print(serialized)
    return int(bool(result.isError))


def cli():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool")
    parser.add_argument("--args", default="{}", help="JSON arguments, or - to read one UTF-8 JSON object from stdin.")
    parser.add_argument("--runtime", default=os.environ.get("MCB_RUNTIME_FILE"), help="Explicit runtime target. Stdio has no default binding; its temporary connection does not change the chat's live binding.")
    parser.add_argument("--output")
    parser.add_argument("--image")
    parser.add_argument("--transport", choices=("stdio", "in-process"), default="stdio",
                        help="stdio tests the MCP wire; in-process reuses the same validated MCP adapter without a child server.")
    parser.add_argument("--measure", action="store_true", help="Wrap the result with runner timing; ordinary output stays unchanged.")
    return asyncio.run(main(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(cli())
