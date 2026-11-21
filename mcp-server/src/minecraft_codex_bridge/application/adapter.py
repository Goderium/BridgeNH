"""Shared execution envelope for every MCP, CLI and worker entrypoint."""
from __future__ import annotations

import json
import logging
import time
from typing import Any
import anyio
from mcp import types
from ..bridge import BridgeClient, BridgeError, capability_scope
from ..tools import TOOL_BY_NAME
from ..validation import validate_tool_arguments
from ..call_timing import record_validation, timing_scope
from ..operation_policy import flight_guidance, guidance as operation_guidance
from ..knowledge import KnowledgeStore, capture_scope
from ..run_log import RunLog
from ..feedback import explain as explain_feedback
from .dispatch import HANDLERS
from .control import invoke_control
from .reads import invoke_current_world_read
from .results import _result, screenshot_result, waila_result


class Adapter:
    def __init__(self, client: BridgeClient):
        self.client = client
        from ..compaction_cursor import CompactionCursor
        self.compaction_cursor = CompactionCursor()
        self.workflow_lock = anyio.Lock()
        from ..action_chain import ActionChain
        self.action_chain = ActionChain()
        self.ae_order_records = {}
        self.last_player_handoff = None
        self.knowledge = KnowledgeStore(getattr(getattr(client, "configuration", None), "environ", None))
        self.run_log = RunLog(getattr(client, "configuration", None))

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:
        if name == "mcb_compaction_cursor":
            # Codex expects only hook output fields; do not attach a game journal or timing envelope.
            return await self._call_tool(name, arguments)
        knowledge = None
        with timing_scope() as timing, capability_scope() as scope, capture_scope(name, arguments) as captured:
            result = await self._call_tool(name, arguments)
            handoff = scope.get("playerHandoff")
            previous = self.last_player_handoff
            if handoff and (previous is None or handoff[0] != previous[0] or handoff[1] > previous[1]):
                self.last_player_handoff = handoff
                result.structuredContent = {**result.structuredContent, "playerHandoff": {
                    "sessionId": handoff[0], "emergencyReleaseCount": handoff[1],
                    "message": "The player used the emergency release. Normally stop the current input sequence and reassess the task before issuing further controls.",
                }}
            spec = TOOL_BY_NAME.get(name)
            feedback = explain_feedback(name, result.structuredContent, read_only=spec is None or spec.read_only)
            if feedback is not None:
                result.structuredContent = {**result.structuredContent, "feedback": feedback}
            if name != "mcb_knowledge":
                try:
                    # Local persistence never extends the game deadline or changes
                    # action acceptance. No game calls or retries are made here.
                    with anyio.move_on_after(0.25, shield=True) as deadline:
                        knowledge = await anyio.to_thread.run_sync(
                            lambda: self.knowledge.finish(captured, arguments, result.structuredContent,
                                timing.report(), read_only=spec is None or spec.read_only), abandon_on_cancel=True)
                    if deadline.cancel_called:
                        knowledge = {"status": "local_knowledge_deadline", "gameResultPreserved": True}
                except Exception:
                    knowledge = {"status": "local_knowledge_unavailable", "gameResultPreserved": True}
            report = timing.report()
            journal = None
            if name != "mcb_run_log":
                try:
                    with anyio.move_on_after(0.25, shield=True) as deadline:
                        journal = await anyio.to_thread.run_sync(
                            lambda: self.run_log.finish(captured, arguments, result.structuredContent, report), abandon_on_cancel=True)
                    if deadline.cancel_called:
                        journal = {"recorded": False, "reason": "journal_deadline", "gameResultPreserved": True}
                except BridgeError as error:
                    journal = {"recorded": False, "reason": error.code, "gameResultPreserved": True}
                except Exception:
                    journal = {"recorded": False, "reason": "journal_unavailable", "gameResultPreserved": True}
        data = {**result.structuredContent, "mcpTiming": report}
        if journal is not None:
            data["agentRunLog"] = journal
        if knowledge is not None:
            data["localKnowledge"] = knowledge
        # Preserve native images and the original error classification.
        result.structuredContent = data
        result.content = [types.TextContent(type="text", text=json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":"))),
                          *[item for item in result.content if not isinstance(item, types.TextContent)]]
        return result

    async def _call_tool(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:
        started_at = time.monotonic() if name == "mcb_qa_test_once" else None
        try:
            validation_started_ns = time.perf_counter_ns()
            try:
                validate_tool_arguments(name, arguments)
            finally:
                record_validation(time.perf_counter_ns() - validation_started_ns)
            if name in {"mcb_approach", "mcb_ae_configure", "mcb_ae_install", "mcb_ae_order", "mcb_recipe_once", "mcb_blueprint_build"}:
                from ..operation_policy import movement_arguments
                arguments = await movement_arguments(self.client, name, arguments)
            handler = HANDLERS.get(name)
            if handler is not None:
                return await handler(self, name, arguments, started_at)
            return await self.invoke_native_tool(name, arguments)
        except BridgeError as error:
            return _result({"ok": False, "error": error.as_dict()}, error=True)
        except Exception:
            # SDK must never print exceptions containing raw responses/tokens.
            logging.getLogger(__name__).error("Unexpected bridge adapter failure (details withheld).")
            return _result({"ok": False, "error": {"code": "INTERNAL_ERROR", "message": "Unexpected bridge adapter failure."}}, error=True)

    async def invoke_native_tool(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:
        spec = TOOL_BY_NAME[name]
        if not spec.read_only:
            async with self.workflow_lock:
                data = await invoke_control(self.client, spec.operation, arguments) if spec.takeover else await self.client.invoke(spec.operation, arguments)
        else:
            composite = spec.operation == "world.structure" and ("candidatePositions" in arguments or arguments.get("includeSurvivalInventory") is True) \
                or spec.operation == "world.inspect_many" and arguments.get("includePlayerInventory") is True
            data = await invoke_current_world_read(self.client, spec.operation, arguments) if composite else await self.client.invoke(spec.operation, arguments)
        if name == "mcb_health":
            data = {**data, "agentGuidance": operation_guidance(data)}
        if name == "mcb_snapshot" and type(data.get("worldLoaded")) is bool:
            data = {**data, "movementSafety": flight_guidance(data)}
        if name == "mcb_screenshot":
            return screenshot_result(data)
        if name == "mcb_food" and data.get("complete") is not True:
            return _result({"ok": False, "data": data, "error": {"code": "FOOD_UNCONFIRMED",
                "message": "Inspect the hunger and selected-slot receipt before any further use."}}, error=True)
        if name == "mcb_waila":
            return waila_result(data)
        return _result({"ok": True, "data": data})
