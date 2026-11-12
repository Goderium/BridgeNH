"""Actual SDK initialize/list/call over a launched stdio server; game HTTP is mocked."""

import asyncio
import base64
import json
import struct
import threading
import time
import zlib
from contextlib import asynccontextmanager
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from mcp import ClientSession, StdioServerParameters, types
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError

from minecraft_codex_bridge.tools import TOOLS
from minecraft_codex_bridge.agent_instructions import AGENT_INSTRUCTIONS
from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.guides import GUIDE_RESOURCES
from minecraft_codex_bridge.connection_binding import client_id
from expanded_examples import ACTION_EXAMPLES, READ_EXAMPLES

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "mock-bridge-secret-token-which-should-not-leak"
def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


PNG = base64.b64encode(
    b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
    + png_chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff")) + png_chunk(b"IEND", b"")
).decode("ascii")


@pytest.fixture
def http_bridge():
    state = {"calls": [], "profile": "developer", "counter": 0, "inspect_counter": 0, "fail": None, "capabilities": [tool.operation for tool in TOOLS if tool.operation], "slow_operation": None, "cancelled": set(), "executed": [], "cancel_status": "cancelled", "cancel_event": threading.Event(), "mouse_enabled": True, "unfocused_enabled": False, "background_error": None, "session_id": "mock-session", "scan_radius": 1, "scan_vertical_radius": 1, "cache_radius": 4, "cache_vertical_radius": 4, "cache_mib": 2048}

    def background():
        return {"enabled": not state["mouse_enabled"] and state["unfocused_enabled"], "mouseEnabled": state["mouse_enabled"], "unfocusedEnabled": state["unfocused_enabled"], "helperInstalled": not state["mouse_enabled"], "pauseOnLostFocus": not state["unfocused_enabled"]}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/v1/cancel":
                state["calls"].append({**body, "operation": "__cancel__"})
            else:
                state["calls"].append(body)
            if self.path not in {"/v1/call", "/v1/cancel"} or self.headers.get("Authorization") != "Bearer " + TOKEN:
                self.send_response(401)
                self.end_headers()
                return
            if state["fail"]:
                self.send_response(state["fail"])
                self.end_headers()
                return
            if self.path == "/v1/cancel":
                if state["cancel_status"] == "cancelled":
                    state["cancelled"].add(body["requestId"])
                state["cancel_event"].set()
                self.reply(body["requestId"], {"status": state["cancel_status"]})
                return
            operation = body["operation"]
            if operation == state["slow_operation"]:
                # Simulate cancellation arriving before game-queue registration.
                state["cancel_event"].wait(timeout=0.8)
                time.sleep(0.05)
                if body["requestId"] in state["cancelled"]:
                    self.reply(body["requestId"], {"code": "REQUEST_CANCELLED", "message": "Request cancelled before execution."}, error=True)
                    return
            state["executed"].append(operation)
            if operation == "bridge.health":
                data = {"protocolVersion": 1, "capabilities": state["capabilities"], "profile": state["profile"], "sessionId": state["session_id"], "instancePath": state.get("instance_path"), "background": background()}
                data["operationPolicy"] = {"available": True, "effective": {"movementImplementation": "developer_teleport"}}
                data["recipeQueryAccess"] = {"version": 1, "publicCatalog": True,
                    "localMachineBackend": state["profile"] == "developer", "remoteMachineBackend": False}
            elif operation == "client.snapshot":
                state["counter"] += 1
                data = {"sessionId": state["session_id"], "profile": state["profile"], "worldLoaded": True, "background": background(), "world": {"name": "mock-world", "worldName": "mock-world", "worldFolder": "mock-world", "dimension": 0}, "player": {"health": 20, "x": 0, "y": 64, "z": 0}, "screen": "inventory", "counter": state["counter"]}
            elif operation == "client.screenshot":
                data = {"base64": PNG, "width": 1, "height": 1, "mimeType": "image/png"}
            elif operation == "bridge.policy":
                data = {"available": True, "selected": {"mode": "survival", "movement": "path"}}
            elif operation == "world.inspect":
                state["inspect_counter"] += 1
                data = {"source": "integrated_server", "gregtech": {"progress": state["inspect_counter"], "maxProgress": 20, "active": True, "allowedToWork": True, "storedEU": 5000, "signedCalculatedEUt": -32, "eutMeaning": "Calculated machine EU/t; negative consumes, positive generates; not original recipe EU/t.", "recipeResultMessage": "working"}}
            elif operation == "gui.inspect":
                data = {"screenId": "mock-screen", "windowId": 1, "screen": "inventory", "width": 400, "height": 300, "widgets": [], "coverage": {"vanilla": True, "mui2": False, "aeVirtualSlots": False, "richTooltips": False, "text": False}}
            elif operation == "gui.tooltip":
                data = {"screenId": "mock-screen", "source": "ItemStack.getTooltip", "tooltip": ["Actual client stack"], "tooltipTotalLines": 1, "tooltipTruncated": False, "advanced": body["args"].get("advanced", False), "coverage": {"item": True}}
            elif operation == "nei.inspect":
                data = {"screenId": "mock-screen", "handler": {"class": "CurrentHandler", "id": "current", "name": "Current recipes"}, "recipeCount": 100, "visibleRecipeIndices": [42, 77], "visibleRecipeCount": 2, "visibleIndicesTruncated": False, "recipes": [{"pageOffset": 0, "recipeIndex": 42, "inputs": [], "outputs": [], "other": [], "positionsVisited": 0, "positionsTruncated": True, "softBudgetPartial": True}], "offset": body["args"].get("offset", 0), "nextOffset": 1, "hasMore": True, "includeTooltips": body["args"].get("includeTooltips", False), "advanced": body["args"].get("advanced", False), "modifiers": {"shift": False, "ctrl": False, "alt": False, "source": "current physical keyboard state"}, "coverage": {"extraDrawnText": "unavailable"}, "limits": {"recipes": 16, "positionsPerRecipe": 64, "alternativesPerPosition": 32, "visibleRefs": 256, "resultBytes": 131072}, "budget": {"actualElapsedMicros": 5000, "softBudgetExceeded": True}, "byteLimited": False, "nonAtomic": True}
            elif operation == "world.scan_config":
                state["scan_radius"] = body["args"].get("radiusChunks", state["scan_radius"])
                state["scan_vertical_radius"] = body["args"].get("verticalRadiusChunks", state["scan_vertical_radius"])
                state["cache_radius"] = body["args"].get("cacheRadiusChunks", state["cache_radius"])
                state["cache_vertical_radius"] = body["args"].get("cacheVerticalRadiusChunks", state["cache_vertical_radius"])
                state["cache_mib"] = body["args"].get("cacheMaxMiB", state["cache_mib"])
                data = {"radiusChunks": state["scan_radius"], "verticalRadiusChunks": state["scan_vertical_radius"], "radiusBlocks": state["scan_radius"] * 16, "verticalRadiusBlocks": state["scan_vertical_radius"] * 16, "cacheRadiusChunks": state["cache_radius"], "cacheVerticalRadiusChunks": state["cache_vertical_radius"], "cacheRadiusBlocks": state["cache_radius"] * 16, "cacheVerticalRadiusBlocks": state["cache_vertical_radius"] * 16, "cacheMaxMiB": state["cache_mib"], "maxRadiusChunks": 8, "maxCacheRadiusChunks": 32, "maxCacheMiB": 16384, "memoryOnly": True}
            elif operation == "world.scan":
                data = {"source": "client", "blocks": [], "complete": False, "nextCursor": "next-mock-cursor", "stopReason": "tick_budget", "nonAtomic": True, "coverage": {"examined": 0, "air": 0, "unloaded": 0}}
            elif operation == "world.structure":
                data = {"piece": "main", "planSource": "StructureLib", "positions": [], "offset": body["args"].get("offset", 0), "nextOffset": 2048, "hasMore": True, "coverage": {"validation": "not_performed"}}
            elif operation == "client.diagnostics":
                data = {"sessionId": "mock-session", "fps": 60, "worldLoaded": True, "serverTicks": {"available": True, "meanMillis": 2.1}}
            elif operation == "client.worlds":
                data = {"worlds": [{"folder": "mock-world", "name": "mock-world", "requiresConversion": False}], "totalMatched": 1}
            elif operation in {"client.load_world", "client.leave_world", "client.exit"}:
                data = {"status": "scheduled", "ticketId": "mock-ticket", "operation": operation, "sessionId": "mock-session", "completion": "Observe snapshot.lifecycle and worldLoaded."}
            elif operation == "client.background":
                if state["background_error"]:
                    self.reply(body["requestId"], {"code": state["background_error"], "message": "Background mode refused."}, error=True)
                    return
                state["mouse_enabled"] = not body["args"]["enabled"]
                state["unfocused_enabled"] = body["args"]["enabled"]
                data = background()
            elif operation == "client.mouse":
                state["mouse_enabled"] = body["args"]["enabled"]
                data = background()
            elif operation == "client.unfocused":
                state["unfocused_enabled"] = body["args"]["enabled"]
                data = background()
            else:
                data = {"operation": operation, "args": body["args"]}
            self.reply(body["requestId"], data)

        def reply(self, request_id, data, *, error=False):
            raw = json.dumps({"requestId": request_id, "ok": not error, "error" if error else "data": data}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield state, f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@asynccontextmanager
async def session_for(url, tmp_path, extra_env=None, *, state=None):
    stderr = tmp_path / "mcp-stderr.log"
    import sys
    environment = {"MCB_URL": url, "MCB_TOKEN": TOKEN, "PYTHONUTF8": "1", **(extra_env or {}),
                   "CODEX_THREAD_ID": "stdio-fixture-chat", "LOCALAPPDATA": str(tmp_path / "local-app-data"),
                   "MCB_INSTANCE_ROOT": str(tmp_path / "no-other-instances")}
    config = tmp_path / "clients.json"
    profiles = {}
    if state is not None:
        runtime = Path(environment.get("MCB_RUNTIME_FILE") or tmp_path / "instance/.minecraft/config/codexbridge/runtime.json").resolve()
        instance = runtime.parents[3]
        runtime.parent.mkdir(parents=True, exist_ok=True)
        state["instance_path"] = str(instance)
        state["runtime_file"] = runtime
        runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": url, "token": TOKEN,
            "instancePath": str(instance), "sessionId": state["session_id"]}), encoding="utf-8")
        profiles["fixture"] = {"instancePath": str(instance)}
    config.write_text(json.dumps({"version": 1, "profiles": profiles}), encoding="utf-8")
    environment["MCB_CONFIG_FILE"] = str(config)
    params = StdioServerParameters(command=sys.executable, args=[str(ROOT / "run.py")], env=environment)
    with stderr.open("w", encoding="utf-8") as errors:
        async with stdio_client(params, errlog=errors) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=10)) as session:
                initialize = await session.initialize()
                assert initialize.serverInfo.name == "minecraft-codex-bridge"
                assert initialize.instructions == AGENT_INSTRUCTIONS
                status = (await session.call_tool("mcb_connection", {})).structuredContent["data"]
                assert not status["bound"]
                if state is not None:
                    clients = (await session.call_tool("mcb_clients", {})).structuredContent["data"]["clients"]
                    assert {row["clientId"] for row in clients} == {client_id(instance)}
                    bound = await session.call_tool("mcb_connection", {"action": "bind", "clientId": client_id(instance),
                        "expectedConversationId": "stdio-fixture-chat", "expectedBindingGeneration": status["generation"],
                        "requestId": "fixture-bind"})
                    assert not bound.isError, bound
                    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]
                    state["calls"].clear()
                    state["executed"].clear()
                yield session
    assert TOKEN not in stderr.read_text(encoding="utf-8")


async def test_actual_mcp_initialize_list_and_call_all_tools(http_bridge, tmp_path, *, supervised=True):
    state, url = http_bridge
    game = tmp_path / ".minecraft"
    runtime = game / "config" / "codexbridge" / "runtime.json"
    runtime.parent.mkdir(parents=True)
    runtime.write_text(json.dumps({"protocolVersion": 1, "baseUrl": url, "token": TOKEN}), encoding="utf-8")
    (game / "logs").mkdir()
    (game / "logs" / "latest.log").write_text("[WARN] mock warning\n[ERROR] Authorization: Bearer other-secret\n", encoding="utf-8")
    examples = {
        "mcb_health": {}, "mcb_snapshot": {}, "mcb_gui": {}, "mcb_inventory": {},
        "mcb_world_inspect": {"x": 0, "y": 64, "z": 0},
        "mcb_world_scan": {"x": 0, "y": 64, "z": 0, "radius": 1, "limit": 5},
        "mcb_scan_config": {}, "mcb_world_structure": {"x": 0, "y": 64, "z": 0},
        "mcb_gui_tooltip": {"screenId": "mock-screen", "slot": 0}, "mcb_nei": {"screenId": "mock-screen"},
        "mcb_screenshot": {}, "mcb_look": {"yaw": 1080, "pitch": 0}, "mcb_hotbar": {"slot": 2},
        "mcb_input": {"keys": ["forward"], "ticks": 5}, "mcb_interact": {"action": "use"},
        "mcb_place_block": {"x": 0, "y": 64, "z": 0, "hotbarSlot": 1, "expectedWorld": "mock-world"},
        "mcb_slot_click": {"windowId": 1, "slot": 0}, "mcb_close_gui": {},
        "mcb_transfer_whole_stack": {"version": 1, "expectedSessionId": "mock-session", "expectedWorld": "mock-world",
            "screenId": "mock-screen", "windowId": 1, "side": 2, "direction": "player_to_machine",
            "sourceSlot": 0, "destinationSlot": 1, "playerInventoryIndex": 9, "machineInventoryIndex": 1,
            "owner": {"x": 0, "y": 64, "z": 0, "dimension": 0, "registryName": "gregtech:gt.blockmachines",
                "metaTileId": 123, "metaClass": "example.Machine"},
            "expectedItem": {"registryName": "minecraft:stone", "metadata": 0, "count": 1, "hasTag": False}},
        "mcb_open_inventory": {}, "mcb_gui_click": {"screenId": "mock-screen", "x": 10, "y": 10},
        "mcb_gui_key": {"screenId": "mock-screen", "keyCode": 1},
        "mcb_chat": {"message": "test"}, "mcb_policy": {"action": "status"},
        "mcb_survival_build": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "dimension": 0,
            "x": 0, "y": 64, "z": 0, "blueprintHotbarSlot": 0, "side": 2},
        "mcb_ae_material_source": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "dimension": 0,
            "x": 0, "y": 64, "z": 0, "side": 2, "selector": "tile"},
        "mcb_supply_items": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world",
            "items": [{"registryName": "Railcraft:fluid.creosote.cell", "metadata": 0, "count": 1}]},
        "mcb_diagnostics": {}, "mcb_mods": {"query": "gregtech", "limit": 8},
        "mcb_messages": {"since": 0, "limit": 8}, "mcb_items": {"query": "machine", "limit": 8},
        "mcb_item": {"registryName": "minecraft:stone", "metadata": 0},
        "mcb_item_variants": {"registryName": "gregtech:gt.blockmachines", "limit": 8},
        "mcb_world_inspect_many": {"positions": [{"x": 0, "y": 64, "z": 0}], "dimension": 0},
        "mcb_world_tiles": {"x": 0, "y": 64, "z": 0, "radius": 16, "limit": 8},
        "mcb_machine_work": {"x": 0, "y": 64, "z": 0, "expectedWorld": "mock-world", "expectedMetaTileId": 123, "working": False},
        "mcb_teleport": {"x": 1.5, "y": 64, "z": 0.5, "expectedWorld": "mock-world"},
        "mcb_worlds": {}, "mcb_load_world": {"folder": "mock-world", "expectedSessionId": "mock-session"},
        "mcb_leave_world": {"expectedWorld": "mock-world", "expectedSessionId": "mock-session"},
        "mcb_exit": {"expectedWorld": "mock-world", "expectedSessionId": "mock-session"}, "mcb_background": {"enabled": True},
        "mcb_mouse": {"enabled": True}, "mcb_unfocused": {"enabled": True},
        "mcb_wait_for": {"condition": {"path": "player.health", "operator": "equals", "value": 20}, "timeout_ms": 1_000},
        "mcb_launch": {},
        "mcb_help": {}, "mcb_logs": {}, "mcb_capture": {"include_screenshot": True, "positions": [{"x": 0, "y": 64, "z": 0}]},
        "mcb_machine_watch": {"x": 0, "y": 64, "z": 0, "duration_ms": 100, "interval_ms": 200},
        "mcb_sequence": {"expectedSessionId": "mock-session", "steps": [{"id": "gui", "kind": "read", "tool": "mcb_gui", "fields": ["screenId"]}]},
        "mcb_recipe_query": {"map": "gt.recipe.assembler", "output": {"source": "registry", "registryName": "minecraft:chest"}},
        "mcb_recipe_once": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world",
            "machine": {"x": 0, "y": 64, "z": 0}, "input": {"x": 1, "y": 64, "z": 0},
            "output": {"x": -1, "y": 64, "z": 0}, "outputItem": {"registryName": "minecraft:stone", "metadata": 0},
            "selection": "first_supported", "inputGuiSlot": 0},
        "mcb_qa_test_once": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world",
            "selector": "tstqa:mock_finite", "requireFullFlow": True},
        "mcb_blueprint_build": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "dimension": 0,
            "controller": {"x": 0, "y": 64, "z": 0}, "controllerItem": {"registryName": "gregtech:gt.blockmachines", "metadata": 1002},
            "blueprintHotbarSlot": 0, "requestedPorts": [{"role": "item_input", "hotbarSlot": 2},
                {"role": "item_output", "hotbarSlot": 8, "ordinaryOutputItem": {"registryName": "gregtech:gt.blockmachines", "metadata": 81}}]},
        "mcb_placement_preflight": {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "dimension": 0,
            "geometrySource": {"x": 16, "y": 64, "z": 0}, "anchor": {"x": 0, "y": 64, "z": 0}},
        "mcb_observe": {"views": [{"id": "gui", "tool": "mcb_gui", "fields": ["screenId"]}]},
    }
    from qa_examples import READ_QA, ACTION_QA
    examples.update(READ_EXAMPLES | ACTION_EXAMPLES | READ_QA | ACTION_QA)
    async with session_for(url, tmp_path, {"MCB_RUNTIME_FILE": str(runtime)}, state=state) as session:
        listed = await session.list_tools()
        management = {"mcb_backend_status", "mcb_backend_restart", "mcb_clients", "mcb_connection"} if supervised else set()
        assert len(listed.tools) == len(TOOLS) + len(management)
        assert {tool.name for tool in listed.tools} == {spec.name for spec in TOOLS} | management
        assert set(examples) <= {spec.name for spec in TOOLS}
        for tool in listed.tools:
            schema = tool.inputSchema
            branches = schema.get("oneOf", [])
            assert schema.get("additionalProperties") is False or branches and all(
                branch.get("type") == "object" and branch.get("additionalProperties") is False
                for branch in branches), tool.name
        calls_before_recovery = len(state["calls"])
        recovery = await session.call_tool("mcb_action_recovery", {"action": "list"})
        if supervised:
            assert not recovery.isError and recovery.structuredContent["data"]["actions"] == []
        else:
            assert recovery.isError and recovery.structuredContent["error"]["code"] == "ACTION_RECOVERY_WORKER_REQUIRED"
        assert len(state["calls"]) == calls_before_recovery
        for tool in listed.tools:
            if tool.name in management:
                assert tool.annotations.destructiveHint is False
                assert tool.annotations.readOnlyHint == (tool.name in {"mcb_backend_status", "mcb_clients"})
                continue
            assert tool.annotations.destructiveHint == next(spec.destructive for spec in TOOLS if spec.name == tool.name)
            if tool.name == "mcb_recipe_query":
                assert tool.inputSchema["type"] == "object"
                assert set(tool.inputSchema["properties"]) == {
                    "map", "x", "y", "z", "dimension", "input", "output", "cursor", "limit", "maxPages",
                    "mode", "inputs", "fluidInputs", "fluids", "specialSlot"}
                assert len(tool.inputSchema["oneOf"]) == 2
            if tool.name in {"mcb_mouse", "mcb_unfocused"}:
                assert tool.inputSchema["required"] == ["enabled"]
                assert tool.inputSchema["properties"] == {"enabled": {"type": "boolean"}}
                assert tool.annotations.readOnlyHint is False
                assert tool.annotations.idempotentHint is False
            if tool.name in {"mcb_hotbar", "mcb_load_world", "mcb_exit", "mcb_supply_items", "mcb_survival_build"}:
                assert "automatically ensures full background takeover" in tool.description
            if tool.name == "mcb_world_scan":
                assert tool.annotations.readOnlyHint is True
                assert tool.annotations.idempotentHint is False
            if tool.name == "mcb_state_diff":
                assert tool.annotations.readOnlyHint is True
                assert tool.annotations.idempotentHint is False
            if tool.name == "mcb_placement_preflight":
                assert tool.annotations.readOnlyHint is True
                assert tool.annotations.idempotentHint is False
                assert "automatically ensures full background takeover" not in tool.description
            if tool.name in ACTION_EXAMPLES:
                assert tool.annotations.readOnlyHint is False
                assert tool.annotations.idempotentHint is False
                if tool.name == "mcb_gui_pointer":
                    assert "set automatically establishes normal full background protection" in tool.description
                    assert "clear requires only action/screenId" in tool.description
                    assert "preserves current mouse/background choices" in tool.description
                else:
                    assert "automatically ensures full background takeover" in tool.description
            if tool.name in READ_EXAMPLES:
                assert tool.annotations.readOnlyHint is True
                assert "automatically ensures full background takeover" not in tool.description
            if tool.name in {"mcb_world_structure", "mcb_gui_tooltip", "mcb_nei", "mcb_nei_view"}:
                assert tool.annotations.readOnlyHint is True
                assert tool.annotations.idempotentHint is True
        for name, arguments in examples.items():
            calls_before = len(state["calls"])
            result = await session.call_tool(name, arguments)
            if name == "mcb_launch":
                # This SDK fixture is deliberately not a configured Prism instance; never launch a real GUI.
                assert result.isError and result.structuredContent["error"]["code"] == "LAUNCH_CONFIGURATION"
                continue
            if name == "mcb_qa_test_once":
                # This general fixture has no exact live QA world/fixture evidence.
                assert result.isError and result.structuredContent["ok"] is False
                data = result.structuredContent["data"]
                assert data["status"] == "failed" and data["error"]["code"] == "qa_context_changed"
                assert data["sideEffectsPossible"] is False and data["runSubmissions"] == 0
                assert data["fullFlowVerified"] is False
                assert not any(call["operation"] == "qa.run" for call in state["calls"][calls_before:])
                continue
            if name in {"mcb_recipe_once", "mcb_blueprint_build"}:
                # This general SDK fixture lacks complete real machine observations.
                assert result.isError and result.structuredContent["ok"] is False
                assert result.structuredContent["data"]["status"] == "unavailable"
                assert result.structuredContent["data"]["sideEffectsPossible"] is False
                assert TOKEN not in result.model_dump_json()
                continue
            if name == "mcb_transfer_whole_stack":
                # This general SDK fixture has no native transfer capability.
                # The real stdio call must refuse before a native action reaches HTTP.
                assert result.isError and result.structuredContent["ok"] is False
                assert result.structuredContent["error"]["code"] == "RECIPE_TASK_UNAVAILABLE"
                assert result.structuredContent["error"]["reason"] == "whole_stack_capability_invalid"
                assert [call["operation"] for call in state["calls"][calls_before:]] == ["bridge.health"]
                assert TOKEN not in result.model_dump_json()
                continue
            if name == "mcb_survival_build":
                # This generic SDK server intentionally supplies no guarded
                # envelope or native survival receipt. It cannot prove a build.
                assert result.isError and result.structuredContent["error"]["code"] == "MALFORMED_RESPONSE"
                assert result.structuredContent["error"]["outcome_unknown"] is True
                dispatched = [call for call in state["calls"][calls_before:] if call["operation"] == "structure.survival_build"]
                assert len(dispatched) == 1 and dispatched[0]["includeContext"] == {
                    "version": 1, "expectedSessionId": "mock-session", "expectedProfile": "developer",
                    "expectedWorld": "mock-world", "requireBackground": True}
                continue
            if name == "mcb_place_block":
                # Even ordinary placement needs a native guarded receipt. This
                # generic mock supplies none; reject it without action replay.
                assert result.isError and result.structuredContent["error"]["code"] == "MALFORMED_RESPONSE"
                assert result.structuredContent["error"]["outcome_unknown"] is True
                dispatched = [call for call in state["calls"][calls_before:] if call["operation"] == "player.place_block"]
                assert len(dispatched) == 1 and dispatched[0]["includeContext"] == {
                    "version": 1, "expectedSessionId": "mock-session", "expectedProfile": "developer",
                    "expectedWorld": "mock-world", "requireBackground": True}
                continue
            if name == "mcb_ae_material_source":
                # A plain SDK fixture cannot substitute for the mandatory
                # authoritative AFTER context of this bound server read.
                assert result.isError and result.structuredContent["error"]["code"] == "MALFORMED_RESPONSE"
                assert result.structuredContent["error"]["outcome_unknown"] is False
                dispatched = [call for call in state["calls"][calls_before:] if call["operation"] == "ae.material_source"]
                assert len(dispatched) == 1 and dispatched[0]["includeContext"] == {
                    "version": 1, "expectedSessionId": "mock-session", "expectedProfile": "developer",
                    "expectedWorld": "mock-world", "requireBackground": True}
                continue
            if name == "mcb_placement_preflight":
                # This SDK fixture lacks the advertised exact spatial planning capabilities.
                assert result.isError and result.structuredContent["ok"] is False
                data = result.structuredContent["data"]
                assert data["status"] == "unavailable" and data["spaceVerified"] is False and data["complete"] is False
                assert data["reason"] == "placement_scan_capability_missing"
                assert [call["operation"] for call in state["calls"][calls_before:]] == ["bridge.health"]
                assert TOKEN not in result.model_dump_json()
                continue
            assert not result.isError, (name, result)
            assert result.structuredContent["ok"] is True
            assert TOKEN not in result.model_dump_json()
            if name == "mcb_supply_items":
                assert result.structuredContent["data"] == {"operation": "inventory.supply", "args": arguments}
                assert [call["operation"] for call in state["calls"][calls_before:]].count("inventory.supply") == 1
            if name == "mcb_look":
                assert result.structuredContent["data"]["args"]["yaw"] == 1080
            if name in {"mcb_screenshot", "mcb_capture"}:
                images = [content for content in result.content if isinstance(content, types.ImageContent)]
                assert len(images) == 1
                assert base64.b64decode(images[0].data)[:8] == b"\x89PNG\r\n\x1a\n"
                if name == "mcb_screenshot":
                    assert result.structuredContent["data"]["width"] == 1
                else:
                    assert result.structuredContent["data"]["atomic"] is False
                    assert result.structuredContent["data"]["sessionConsistent"] is True
                    assert result.structuredContent["data"]["observations"]["screenshot"]["data"]["width"] == 1
            if name == "mcb_logs":
                assert result.structuredContent["data"]["lines"] == ["[WARN] mock warning", "[redacted sensitive log line]"]
            if name == "mcb_machine_watch":
                assert result.structuredContent["data"]["sampleCount"] == 1
                assert result.structuredContent["data"]["samples"][0]["values"]["gregtech.active"]["value"] is True
                assert result.structuredContent["data"]["samples"][0]["values"]["gregtech.signedCalculatedEUt"] == {"exists": True, "value": -32}
            if name == "mcb_help":
                assert result.structuredContent["data"]["topic"] == "overview"
                assert result.structuredContent["data"]["applicability"]["minecraft"] == "1.7.10"
            if name in {"mcb_load_world", "mcb_leave_world", "mcb_exit"}:
                assert result.structuredContent["data"]["status"] == "scheduled"
                assert result.structuredContent["data"]["ticketId"] == "mock-ticket"
    action_calls = [call for call in state["calls"] if call["operation"] == "player.input"]
    assert len(action_calls) == 1
    assert len({call["requestId"] for call in state["calls"]}) == len(state["calls"])


async def test_actual_mcp_takeover_and_independent_manual_controls(http_bridge, tmp_path):
    state, url = http_bridge

    def background_count():
        return sum(call["operation"] == "client.background" for call in state["calls"])

    async with session_for(url, tmp_path, state=state) as session:
        initial = await session.call_tool("mcb_health", {})
        assert initial.structuredContent["data"]["background"]["mouseEnabled"] is True
        assert initial.structuredContent["data"]["background"]["unfocusedEnabled"] is False
        assert not (await session.call_tool("mcb_snapshot", {})).isError
        assert background_count() == 0
        assert not (await session.call_tool("mcb_hotbar", {"slot": 2})).isError
        assert [call["operation"] for call in state["calls"]][-3:] == ["bridge.health", "client.background", "player.hotbar"]
        assert not state["mouse_enabled"] and state["unfocused_enabled"]
        assert not (await session.call_tool("mcb_look", {"yaw": 5, "pitch": 0})).isError
        assert background_count() == 1
        assert [call["operation"] for call in state["calls"]][-2:] == ["bridge.health", "player.look"]

        mouse = await session.call_tool("mcb_mouse", {"enabled": True})
        assert not mouse.isError and state["mouse_enabled"] and state["unfocused_enabled"]
        assert mouse.structuredContent["data"]["enabled"] is False
        unfocused = await session.call_tool("mcb_unfocused", {"enabled": False})
        assert not unfocused.isError and state["mouse_enabled"] and not state["unfocused_enabled"]
        mouse = await session.call_tool("mcb_mouse", {"enabled": False})
        assert not mouse.isError and not state["mouse_enabled"] and not state["unfocused_enabled"]
        unfocused = await session.call_tool("mcb_unfocused", {"enabled": True})
        assert not unfocused.isError and not state["mouse_enabled"] and state["unfocused_enabled"]
        assert unfocused.structuredContent["data"]["enabled"] is True
        assert background_count() == 1

        release = await session.call_tool("mcb_background", {"enabled": False})
        assert not release.isError and state["mouse_enabled"] and not state["unfocused_enabled"]
        assert background_count() == 2
        assert not (await session.call_tool("mcb_policy", {"action": "status"})).isError
        assert not (await session.call_tool("mcb_snapshot", {})).isError
        assert background_count() == 2
        assert state["mouse_enabled"] and not state["unfocused_enabled"]
        assert not (await session.call_tool("mcb_hotbar", {"slot": 3})).isError
        assert background_count() == 3
        assert not state["mouse_enabled"] and state["unfocused_enabled"]

        # A new game session with default manual controls is not covered by an old success.
        state["session_id"] = "second-session"
        runtime = state["runtime_file"]
        identity = json.loads(runtime.read_text(encoding="utf-8"))
        identity["sessionId"] = state["session_id"]
        runtime.write_text(json.dumps(identity), encoding="utf-8")
        state["mouse_enabled"] = True
        state["unfocused_enabled"] = False
        assert not (await session.call_tool("mcb_open_inventory", {})).isError
        assert background_count() == 4
        assert [call["operation"] for call in state["calls"]][-3:] == ["bridge.health", "client.background", "gui.open_inventory"]


async def test_actual_mcp_failed_takeover_blocks_control(http_bridge, tmp_path):
    state, url = http_bridge
    state["background_error"] = "mouse_grabbed"
    async with session_for(url, tmp_path, state=state) as session:
        result = await session.call_tool("mcb_hotbar", {"slot": 2})
        error = result.structuredContent["error"]
        assert result.isError and error["code"] == "mouse_grabbed"
        assert error["stage"] == "takeover" and error["original_action_executed"] is False
        assert error["blocked_operation"] == "player.hotbar"
        assert [call["operation"] for call in state["calls"]] == ["bridge.health", "client.background"]
        assert "player.hotbar" not in state["executed"]
        assert state["mouse_enabled"] and not state["unfocused_enabled"]
        assert not (await session.call_tool("mcb_snapshot", {})).isError


async def test_actual_mcp_budgeted_scan_structure_and_tooltip_preserve_manual_input(http_bridge, tmp_path):
    state, url = http_bridge
    async with session_for(url, tmp_path, state=state) as session:
        config = await session.call_tool("mcb_scan_config", {})
        assert config.structuredContent["data"]["radiusChunks"] == 1
        assert config.structuredContent["data"]["verticalRadiusChunks"] == 1
        assert config.structuredContent["data"]["cacheRadiusBlocks"] == 64
        assert config.structuredContent["data"]["cacheMaxMiB"] == 2048
        config = await session.call_tool("mcb_scan_config", {"radiusChunks": 3})
        assert config.structuredContent["data"]["radiusBlocks"] == 48
        assert config.structuredContent["data"]["verticalRadiusChunks"] == 1
        config = await session.call_tool("mcb_scan_config", {"verticalRadiusChunks": 2})
        assert config.structuredContent["data"]["radiusChunks"] == 3
        assert config.structuredContent["data"]["verticalRadiusBlocks"] == 32
        config = await session.call_tool("mcb_scan_config", {"cacheRadiusChunks": 32, "cacheVerticalRadiusChunks": 16, "cacheMaxMiB": 16384})
        assert not config.isError
        assert config.structuredContent["data"]["cacheRadiusBlocks"] == 512
        assert config.structuredContent["data"]["cacheVerticalRadiusBlocks"] == 256
        assert config.structuredContent["data"]["cacheMaxMiB"] == 16384
        assert config.structuredContent["data"]["radiusChunks"] == 3
        calls_before = len(state["calls"])
        invalid = await session.call_tool("mcb_scan_config", {"cacheRadiusChunks": 2, "cacheMaxMiB": 16385})
        assert invalid.isError and invalid.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
        assert len(state["calls"]) == calls_before
        config = await session.call_tool("mcb_scan_config", {})
        assert config.structuredContent["data"]["cacheRadiusChunks"] == 32
        assert config.structuredContent["data"]["cacheMaxMiB"] == 16384

        scan = await session.call_tool("mcb_world_scan", {})
        assert not scan.isError and scan.structuredContent["data"]["stopReason"] == "tick_budget"
        assert scan.structuredContent["data"]["coverage"]["examined"] == 0
        scan_calls = [call for call in state["calls"] if call["operation"] == "world.scan"]
        assert len(scan_calls) == 1 and scan_calls[0]["args"] == {}
        await session.call_tool("mcb_world_scan", {"cursor": scan.structuredContent["data"]["nextCursor"], "limit": 5})
        scan_calls = [call for call in state["calls"] if call["operation"] == "world.scan"]
        assert len(scan_calls) == 2 and scan_calls[1]["args"] == {"cursor": "next-mock-cursor", "limit": 5}

        structure = await session.call_tool("mcb_world_structure", {"x": 0, "y": 64, "z": 0})
        assert not structure.isError and structure.structuredContent["data"]["positions"] == []
        assert structure.structuredContent["data"]["hasMore"] is True
        assert structure.structuredContent["data"]["nextOffset"] == 2048
        assert len([call for call in state["calls"] if call["operation"] == "world.structure"]) == 1
        tooltip = await session.call_tool("mcb_gui_tooltip", {"screenId": "mock-screen", "x": 10, "y": 20})
        assert not tooltip.isError and tooltip.structuredContent["data"]["source"] == "ItemStack.getTooltip"
        assert tooltip.structuredContent["data"]["tooltip"] == ["Actual client stack"]
        nei = await session.call_tool("mcb_nei", {"screenId": "mock-screen"})
        assert not nei.isError and nei.structuredContent["data"]["recipes"][0]["recipeIndex"] == 42
        assert nei.structuredContent["data"]["includeTooltips"] is False
        assert nei.structuredContent["data"]["coverage"]["extraDrawnText"] == "unavailable"
        assert nei.structuredContent["data"]["recipes"][0]["positionsTruncated"] is True
        assert nei.structuredContent["data"]["recipes"][0]["softBudgetPartial"] is True
        assert nei.structuredContent["data"]["budget"]["softBudgetExceeded"] is True
        assert nei.structuredContent["data"]["nextOffset"] == 1 and nei.structuredContent["data"]["hasMore"] is True
        assert nei.structuredContent["data"]["limits"]["positionsPerRecipe"] == 64
        assert nei.structuredContent["data"]["limits"]["alternativesPerPosition"] == 32
        assert len([call for call in state["calls"] if call["operation"] == "nei.inspect"]) == 1
        nei = await session.call_tool("mcb_nei", {"screenId": "mock-screen", "includeTooltips": True, "limit": 1})
        assert not nei.isError and nei.structuredContent["data"]["includeTooltips"] is True
        unsupported_poll = await session.call_tool("mcb_wait_for", {"tool": "mcb_world_scan", "args": {"cursor": "next-mock-cursor"}, "condition": {"path": "complete", "operator": "equals", "value": True}})
        assert unsupported_poll.isError and unsupported_poll.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not any(call["operation"] in {"client.background", "client.screenshot"} for call in state["calls"])
    assert state["mouse_enabled"] and not state["unfocused_enabled"]


async def test_actual_mcp_fixed_guide_tools_and_resources_need_no_game(tmp_path):
    # Deliberately closed local port: guide tools/resources must not contact it.
    async with session_for("http://127.0.0.1:1", tmp_path) as session:
        listed = await session.list_resources()
        assert {str(resource.uri) for resource in listed.resources} == set(GUIDE_RESOURCES)
        for uri, topic in GUIDE_RESOURCES.items():
            tool = await session.call_tool("mcb_help", {"topic": topic})
            assert not tool.isError
            resource = await session.read_resource(uri)
            assert len(resource.contents) == 1
            assert resource.contents[0].mimeType == "application/json"
            data = json.loads(resource.contents[0].text)
            assert data == tool.structuredContent["data"]
            assert data["source"] == f"mcp-server/src/minecraft_codex_bridge/guides/{topic}.md"
            assert TOKEN not in json.dumps(data)
        with pytest.raises(McpError, match="Unknown agent guide resource"):
            await session.read_resource("minecraft-codex-bridge://agent-guides/other")
        with pytest.raises(McpError, match="Unknown agent guide resource"):
            await session.read_resource("file:///C:/arbitrary.md")


async def test_actual_mcp_error_results_and_wait_timeout(http_bridge, tmp_path):
    state, url = http_bridge
    async with session_for(url, tmp_path, state=state) as session:
        invalid = await session.call_tool("mcb_hotbar", {"slot": 99})
        assert invalid.isError and invalid.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
        assert not state["calls"]
        unknown = await session.call_tool("unknown-tool", {})
        assert unknown.isError and unknown.structuredContent["error"]["code"] == "UNKNOWN_TOOL"
        state["capabilities"] = ["bridge.health", "client.snapshot"]
        unsupported = await session.call_tool("mcb_hotbar", {"slot": 1})
        assert unsupported.isError and unsupported.structuredContent["error"]["code"] == "UNSUPPORTED_CAPABILITY"
        timeout = await session.call_tool("mcb_wait_for", {"condition": {"path": "player.health", "operator": "equals", "value": 0}, "timeout_ms": 150, "interval_ms": 100})
        assert timeout.isError and timeout.structuredContent["error"]["code"] == "WAIT_TIMEOUT"
        assert timeout.structuredContent["error"]["observation"]["player"]["health"] == 20
        state["fail"] = 401
        unauthorized = await session.call_tool("mcb_health", {})
        assert unauthorized.isError and unauthorized.structuredContent["error"]["code"] == "AUTH_FAILED"


async def test_actual_mcp_cancelled_wait_stops_polling(http_bridge, tmp_path):
    state, url = http_bridge
    async with session_for(url, tmp_path, state=state) as session:
        request_id = session._request_id  # SDK has no public cancel handle; use the actual assigned JSON-RPC ID.
        task = asyncio.create_task(session.call_tool("mcb_wait_for", {"condition": {"path": "player.health", "operator": "equals", "value": 0}, "timeout_ms": 60_000, "interval_ms": 100}))
        deadline = time.monotonic() + 5
        while state["counter"] == 0 and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        assert state["counter"] > 0
        await session.send_notification(types.ClientNotification(types.CancelledNotification(params=types.CancelledNotificationParams(requestId=request_id, reason="test cancellation"))))
        await asyncio.sleep(0.1)
        count = len(state["calls"])
        await asyncio.sleep(0.2)
        assert len(state["calls"]) == count
        with pytest.raises(McpError, match="Request cancelled"):
            await task
        # A cancelled wait must not damage later SDK calls.
        healthy = await session.call_tool("mcb_health", {})
        assert not healthy.isError


async def test_actual_mcp_capture_reports_unsupported_observation_and_continues(http_bridge, tmp_path):
    state, url = http_bridge
    state["capabilities"].remove("client.diagnostics")
    async with session_for(url, tmp_path, state=state) as session:
        result = await session.call_tool("mcb_capture", {})
        data = result.structuredContent["data"]
        assert not result.isError and data["partial"] is True
        assert data["errors"][0]["observation"] == "diagnostics"
        assert data["errors"][0]["code"] == "UNSUPPORTED_CAPABILITY"
        assert data["observations"]["healthEnd"]["ok"] is True
        assert "client.diagnostics" not in state["executed"]


async def test_actual_mcp_cancelled_machine_watch_stops_http_polls(http_bridge, tmp_path):
    state, url = http_bridge
    async with session_for(url, tmp_path, state=state) as session:
        request_id = session._request_id
        task = asyncio.create_task(session.call_tool("mcb_machine_watch", {"x": 0, "y": 64, "z": 0, "duration_ms": 30_000, "interval_ms": 200}))
        deadline = time.monotonic() + 5
        while state["inspect_counter"] == 0 and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        assert state["inspect_counter"] > 0
        await session.send_notification(types.ClientNotification(types.CancelledNotification(params=types.CancelledNotificationParams(requestId=request_id))))
        with pytest.raises(McpError, match="Request cancelled"):
            await task
        count = len(state["calls"])
        await asyncio.sleep(0.3)
        assert len(state["calls"]) == count
        healthy = await session.call_tool("mcb_health", {})
        assert not healthy.isError


@pytest.mark.parametrize("slow_operation", ["player.hotbar", "client.background"])
async def test_actual_mcp_cancel_sends_original_http_id_and_prevents_late_action(http_bridge, tmp_path, slow_operation):
    state, url = http_bridge
    state["slow_operation"] = slow_operation
    async with session_for(url, tmp_path, state=state) as session:
        mcp_request_id = session._request_id
        task = asyncio.create_task(session.call_tool("mcb_hotbar", {"slot": 2}))
        deadline = time.monotonic() + 5
        while not any(call["operation"] == slow_operation for call in state["calls"]) and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        original = next(call for call in state["calls"] if call["operation"] == slow_operation)
        await session.send_notification(types.ClientNotification(types.CancelledNotification(params=types.CancelledNotificationParams(requestId=mcp_request_id))))
        with pytest.raises(McpError, match="Request cancelled"):
            await task
        await asyncio.sleep(0.2)
        cancellations = [call for call in state["calls"] if call["operation"] == "__cancel__"]
        assert len(cancellations) == 1
        assert cancellations[0]["requestId"] == original["requestId"]
        assert len([call for call in state["calls"] if call["operation"] == slow_operation]) == 1
        assert "player.hotbar" not in state["executed"]
        if slow_operation == "client.background":
            assert "client.background" not in state["executed"]
            assert not any(call["operation"] == "player.hotbar" for call in state["calls"])
        assert TOKEN not in repr(state["calls"])


@pytest.mark.parametrize("cancel_status,unknown", [("cancelled", False), ("already_running", True), ("not_found", True)])
async def test_real_http_timeout_acknowledgement_and_race(http_bridge, cancel_status, unknown):
    state, url = http_bridge
    state["slow_operation"] = "player.hotbar"
    state["cancel_status"] = cancel_status
    client = BridgeClient(Configuration({"MCB_URL": url, "MCB_TOKEN": TOKEN}), timeout=0.05)
    try:
        result = await Adapter(client).call_tool("mcb_hotbar", {"slot": 3})
        error = result.structuredContent["error"]
        assert result.isError and error["code"] == "BRIDGE_TIMEOUT"
        assert error["outcome_unknown"] is unknown
        assert error["cancellation_status"] == cancel_status
        original = next(call for call in state["calls"] if call["operation"] == "player.hotbar")
        cancelled = next(call for call in state["calls"] if call["operation"] == "__cancel__")
        assert original["requestId"] == cancelled["requestId"] == error["request_id"]
        await asyncio.sleep(0.1)
        if cancel_status == "cancelled":
            assert "player.hotbar" not in state["executed"]
        assert len([call for call in state["calls"] if call["operation"] == "player.hotbar"]) == 1
        assert TOKEN not in result.model_dump_json()
    finally:
        await client.aclose()


async def test_actual_persistent_stdio_sequence_and_observe_with_dynamic_identity(http_bridge, tmp_path):
    state, url = http_bridge
    async with session_for(url, tmp_path, state=state) as session:
        listed = {tool.name: tool for tool in (await session.list_tools()).tools}
        assert listed["mcb_observe"].annotations.readOnlyHint is True
        assert listed["mcb_sequence"].annotations.readOnlyHint is False and listed["mcb_sequence"].annotations.idempotentHint is False
        for _ in range(3):
            result = await session.call_tool("mcb_observe", {"expectedSessionId": "mock-session", "views": [
                {"id": "gui", "tool": "mcb_gui", "fields": ["screenId", "windowId"]}]})
            assert not result.isError and result.structuredContent["data"]["steps"][0]["observation"]["values"]["screenId"] == "mock-screen"
        assert "client.background" not in [call["operation"] for call in state["calls"]]
        result = await session.call_tool("mcb_sequence", {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "steps": [
            {"id": "gui", "kind": "read", "tool": "mcb_gui", "fields": []},
            {"id": "key", "kind": "action", "tool": "mcb_gui_key", "args": {"keyCode": 14}, "bind": {"screenId": {"step": "gui", "path": "screenId"}}},
            {"id": "confirm", "kind": "wait", "tool": "mcb_snapshot", "condition": {"path": "player.health", "operator": "gte", "value": 20}, "fields": ["player.health"]}]})
        assert not result.isError and result.structuredContent["data"]["completedSteps"] == 3
        keys = [call for call in state["calls"] if call["operation"] == "gui.key"]
        assert len(keys) == 1 and keys[0]["args"] == {"keyCode": 14, "screenId": "mock-screen"}
        assert len([call for call in state["calls"] if call["operation"] == "client.background"]) == 1
        invalid = await session.call_tool("mcb_sequence", {"expectedSessionId": "mock-session", "expectedWorld": "mock-world", "steps": [
            {"id": "first", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 1}},
            {"id": "bad", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 9}}]})
        assert invalid.isError and invalid.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
        assert "player.hotbar" not in [call["operation"] for call in state["calls"]]
