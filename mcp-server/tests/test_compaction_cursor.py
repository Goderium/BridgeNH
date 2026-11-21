from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
import asyncio

from minecraft_codex_bridge.compaction_cursor import CompactionCursor


def test_bound_turns_and_handoff():
    async def exercise():
        credentials = SimpleNamespace(session_id="game-session")
        config = SimpleNamespace(read=Mock(return_value=credentials), conversation_binding=None)
        client = SimpleNamespace(configuration=config, call=AsyncMock(return_value={"indicated": True}))
        listener = CompactionCursor()
        begin = {"event": "PreCompact", "conversationId": "owner", "turnId": "first"}
        await listener.notify(client, begin)
        config.conversation_binding = {"instancePath": "/instance", "conversationId": "other", "bindingId": "binding"}
        await listener.notify(client, begin)
        config.read.assert_not_called()

        config.conversation_binding["conversationId"] = "owner"
        await listener.notify(client, begin)
        key = client.call.await_args.args[1]["key"]
        assert client.call.await_args.args == ("client.compaction_cursor",
            {"expectedSessionId": "game-session", "key": key, "active": True})
        assert client.call.await_args.kwargs["expected_credentials"] is credentials
        await listener.notify(client, {**begin, "event": "Interrupt", "turnId": "other"})
        assert client.call.await_count == 1
        await listener.notify(client, {**begin, "turnId": "overlap"})
        await listener.notify(client, {**begin, "event": "Interrupt"})
        assert client.call.await_args.args[1] == {"expectedSessionId": "game-session", "key": key, "active": False}

        # A reloaded worker can still deliver PostCompact for the original binding and turn.
        await CompactionCursor().notify(client, {**begin, "event": "PostCompact"})
        assert client.call.await_args.args[1]["key"] == key
        await listener.notify(client, begin)
        await listener.detach(client)
        assert client.call.await_args.args[1]["active"] is False
        config.conversation_binding["bindingId"] = "new-binding"
        await listener.notify(client, begin)
        assert client.call.await_args.args[1]["key"] != key

    asyncio.run(exercise())
