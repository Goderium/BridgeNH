import pytest

from minecraft_codex_bridge import survival_storage_approach as approach
from minecraft_codex_bridge.bridge import BridgeError


ORDER = {"expectedSessionId": "session", "expectedWorld": "save", "dimension": 0,
         "identity": {"folder": "save", "server": None, "actor": "owner"}}
POINT = {"kind": "drawer", "x": 10, "y": 64, "z": 10, "drawer": 0, "side": 2}


class Client:
    def __init__(self):
        self.screen = ""
        self.calls = []

    async def invoke(self, op, args):
        self.calls.append((op, args))
        if op == "client.snapshot":
            return {"sessionId": "session", "world": {"dimension": 0}, "profile": "player",
                    "screen": self.screen, "screenId": "visible" if self.screen else "",
                    "lifecycleTarget": {"expectedWorld": "save"}, "player": {"uuid": "owner"}}
        if op == "bridge.health":
            return {"sessionId": "session", "operationPolicy": {"available": True, "effective": {
                "movementImplementation": "walking"}}}
        if op == "world.inspect":
            return {**args, "metadata": 1, "registryName": "test:drawer"}
        pytest.fail(op)


@pytest.mark.anyio
async def test_other_screen_stops_before_move_or_close(monkeypatch):
    client = Client()
    client.screen = "some.gui"
    async def unexpected(*args):
        pytest.fail("unowned screen was closed")
    monkeypatch.setattr(approach, "invoke_control", unexpected)
    with pytest.raises(BridgeError, match="Another screen"):
        await approach.approach_storage(client, {**ORDER, "storageScreenId": "old"}, POINT)


@pytest.mark.anyio
async def test_close_owned_container_once_then_walk_to_bound_face(monkeypatch):
    client = Client()
    client.screen = "some.gui"
    called = []
    async def control(client, op, args):
        called.append((op, args))
        if op == "gui.close":
            assert args == {"screenId": "visible"}
            return {"closed": True}
        assert op == "player.navigate" and args["strategy"] == "walking"
        assert args["requireSurvivalActor"] is True and args["expectedWorld"] == "save"
        assert args["goal"]["targets"][0] == {"x": 10, "y": 64, "z": 10, "allowedSides": [2],
                                              "targetIdentity": {"registryName": "test:drawer", "metadata": 1}}
        return {"status": "completed", "arrivalConfirmed": True, "sessionId": "session", "dimension": 0}
    monkeypatch.setattr(approach, "invoke_control", control)
    await approach.approach_storage(client, {**ORDER, "storageScreenId": "visible"}, POINT)
    assert [op for op, _ in called] == ["gui.close", "player.navigate"]


@pytest.mark.anyio
async def test_remote_storage_walk_binds_authenticated_server_without_integrated_planner(monkeypatch):
    order = {**ORDER, "expectedWorld": "connection:current", "identity": {**ORDER["identity"], "server": "server"}}
    class Remote(Client):
        async def invoke(self, op, args):
            if op == "bridge.health":
                return {"sessionId": "session", "readiness": {"remoteServerReady": True},
                        "operationPolicy": {"available": True, "effective": {"remoteGroundWalkVersion": 1,
                            "machineRead": True, "movementImplementation": "walking"}},
                        "serverBridge": {"ready": True, "dimension": 0, "connectionId": "conn",
                            "server": {"groundWalkVersion": 1, "operatorReads": True, "world": {"worldFolder": "save"}}}}
            if op == "server.observe":
                assert args["expectedConnectionId"] == "conn"
                return {"sessionId": "session", "connectionId": "conn", "dimension": 0, "complete": True,
                        "source": "remote_server_tick", "blocks": [{"x": 10, "y": 64, "z": 10, "loaded": True,
                                                                    "registryName": "test:drawer", "metadata": 1}]}
            data = await super().invoke(op, args)
            data["lifecycleTarget"]["expectedWorld"] = "connection:current"
            return data
    async def control(client, op, args):
        assert op == "player.navigate" and args["expectedWorld"] == "save" and args["expectedConnectionId"] == "conn"
        return {"status": "arrived", "arrivalConfirmed": True, "sessionId": "session", "dimension": 0}
    monkeypatch.setattr(approach, "invoke_control", control)
    await approach.approach_storage(Remote(), order, POINT)
