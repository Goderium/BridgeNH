"""Scoped Codex hook notifications during Minecraft client takeover."""
from time import monotonic
from uuid import NAMESPACE_OID, uuid5

import anyio

from .bridge import BridgeError
from .catalog.common import ToolSpec, obj

_NAME = "mcb_compaction_cursor"
SPEC = ToolSpec(_NAME, None,
    "Codex lifecycle hook for the takeover indicator. Pass the hook session_id as conversationId, "
    "turn_id as turnId and hook_event_name as event. Uses only this live conversation binding. "
    "PreCompact shows busy in the GUI or world HUD during takeover. PostCompact or Interrupt clears that indication.",
    obj({"conversationId": {"type": "string", "minLength": 1, "maxLength": 128},
         "turnId": {"type": "string", "minLength": 1, "maxLength": 128},
         "event": {"enum": ["PreCompact", "PostCompact", "Interrupt"]}}, ["conversationId", "turnId", "event"]),
    idempotent=True)


class CompactionCursor:
    def __init__(self):
        self.pending = {}

    async def notify(self, client, event):
        binding = client.configuration.conversation_binding
        if not binding or not binding["instancePath"] or binding["conversationId"] != event["conversationId"]:
            return
        key = str(uuid5(NAMESPACE_OID, binding["bindingId"] + "/" + event["turnId"]))
        now = monotonic()
        self.pending = {k: v for k, v in self.pending.items() if v[1] > now}
        start = event["event"] == "PreCompact"
        if start and len(self.pending) >= 16 and key not in self.pending:
            return
        if event["event"] == "Interrupt" and key not in self.pending:
            return
        # PostCompact can also clear a span after a hot reload of the same binding.
        try:
            credential = client.configuration.read()
            if not credential.session_id:
                return
            with anyio.move_on_after(1.5):
                if start:
                    self.pending[key] = (credential.session_id, now + 600)
                receipt = await client.call("client.compaction_cursor", {"key": key, "active": start,
                    "expectedSessionId": credential.session_id}, expected_credentials=credential)
                if start and not receipt.get("indicated"):
                    self.pending.pop(key, None)
        except BridgeError:
            pass  # Closed, offline or older clients cannot display this optional indication.
        finally:
            if not start:
                self.pending.pop(key, None)

    async def detach(self, client):
        pending, self.pending = self.pending, {}
        if not pending:
            return
        try:
            with anyio.move_on_after(1.5):
                credential = client.configuration.read()
                for key, (session, deadline) in pending.items():
                    if deadline > monotonic() and credential.session_id == session:
                        await client.call("client.compaction_cursor", {"key": key, "active": False,
                            "expectedSessionId": session}, expected_credentials=credential)
        except BridgeError:
            pass
