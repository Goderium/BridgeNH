import pytest

from minecraft_codex_bridge.bridge import READ_OPERATIONS
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOL_BY_NAME


class RecordingClient:
    def __init__(self):
        self.calls = []

    async def invoke(self, operation, args):
        self.calls.append((operation, args))
        return {"recordingId": "test", "enabled": True, "nextSince": 17,
                "historyGap": False, "events": [{"seq": 17, "kind": "gui_slot_click"}]}


@pytest.mark.asyncio
async def test_recording_read_preserves_native_coverage_and_never_takes_control():
    client = RecordingClient()
    args = {"recordingId": "test", "since": 16, "limit": 1}
    result = (await Adapter(client)._call_tool("mcb_recording", args)).structuredContent
    assert result["ok"] is True
    assert result["data"]["events"][0]["seq"] == 17
    assert client.calls == [("client.recording", args)]
    spec = TOOL_BY_NAME["mcb_recording"]
    assert spec.read_only and not spec.takeover and not spec.destructive
    assert "client.recording" in READ_OPERATIONS
    assert spec.operation is None  # History views are MCP-local, never forwarded as live game reads.


@pytest.mark.asyncio
@pytest.mark.parametrize("args", [{"action": "on"}, {"since": -1}, {"limit": 129}, {"path": "other-instance"}])
async def test_reader_rejects_controls_paths_and_unbounded_pages(args):
    client = RecordingClient()
    result = (await Adapter(client)._call_tool("mcb_recording", args)).structuredContent
    assert result["ok"] is False
    assert client.calls == []
