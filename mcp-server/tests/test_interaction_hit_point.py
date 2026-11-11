from copy import deepcopy
import pytest
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from test_shared_interaction_approach import Client, run

TARGET = {"x": 0, "y": 64, "z": 0, "allowedSides": [2],
          "targetIdentity": {"registryName": "fixture:machine"}, "hitPoint": {"x": .5, "y": .5, "z": 0}}

class PointClient(Client):
    def __init__(self, capability=1, edge=False):
        super().__init__(in_range=False)
        self.capability, self.edge = capability, edge
    async def invoke(self, operation, payload):
        value = await super().invoke(operation, payload)
        if operation == "bridge.health":
            value["interactionHitPointVersion"] = self.capability
            value["nativeCrosshairArrivalVersion"] = 1
        if operation == "player.plan_approach":
            assert payload["hitPoint"] == TARGET["hitPoint"]
            view = value["selected"]["targetViews"][0]
            view["hitPoint"] = TARGET["hitPoint"]
            if self.edge: view["hit"]["x"] = .1
        return value

async def test_point_is_retained_through_navigation_recheck_and_single_use():
    client = PointClient()
    assert (await run(client, targets=[TARGET], interact=True))["status"] == "completed"
    assert len(client.moves) == len(client.uses) == 1
    assert client.uses[0]["hitPoint"] == TARGET["hitPoint"]
    assert all(t["hitPoint"] == TARGET["hitPoint"] for t in client.moves[0]["arrivalTargets"])

@pytest.mark.parametrize("capability", [None, True, 0, 1.0, 2])
async def test_old_capability_stops_before_any_movement(capability):
    client = PointClient(capability)
    with pytest.raises(BridgeError): await run(client, targets=[TARGET])
    assert not client.moves and not client.uses

async def test_edge_hit_cannot_satisfy_centre_point():
    client = PointClient(edge=True)
    with pytest.raises(BridgeError): await run(client, targets=[TARGET])
    assert not client.moves and not client.uses

@pytest.mark.parametrize("change", [{"allowedSides": [2, 3]}, {"wrenchFacing": True},
    {"hitPoint": {"x": 1.1, "y": .5, "z": 0}}, {"hitPoint": {"x": True, "y": .5, "z": 0}}])
def test_bad_point_schema(change):
    with pytest.raises(BridgeError):
        _validate("mcb_approach", {"expectedSessionId": "s", "expectedWorld": "w", "dimension": 0,
                                  "targets": [{**deepcopy(TARGET), **change}]})
