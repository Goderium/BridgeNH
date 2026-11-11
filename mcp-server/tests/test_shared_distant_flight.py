import pytest

from minecraft_codex_bridge.bridge import BridgeError
from test_shared_interaction_approach import Client, run


@pytest.mark.parametrize("version", [None, False, True, 0, 1.0, "1", 1])
async def test_distant_endpoint_requires_native_segments_even_with_short_deadline(version):
    client = Client(in_range=False)
    client.position["x"] = -50
    original = client.invoke

    async def invoke(operation, payload):
        result = await original(operation, payload)
        if operation == "bridge.health":
            result["nativeNavigationSegmentsVersion"] = version
        return result

    client.invoke = invoke
    if type(version) is int and version >= 1:
        result = await run(client, strategy="ability_flight", timeout_ms=6000)
        assert result["status"] == "completed" and len(client.moves) == 1
        assert client.moves[0]["segmented"] is True
        assert 250 <= client.moves[0]["timeout_ms"] <= 5750
        assert [a["currentOnly"] for op, a in client.calls if op == "player.plan_approach"] == [False, True]
    else:
        with pytest.raises(BridgeError) as failure:
            await run(client, strategy="ability_flight", timeout_ms=6000)
        assert failure.value.code == "APPROACH_SEGMENTS_UNAVAILABLE"
        assert not client.moves and not client.uses
