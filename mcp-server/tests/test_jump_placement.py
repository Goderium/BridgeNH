import copy

import pytest
from jsonschema import Draft202012Validator

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.tools import TOOLS
from test_construction_tasks import CASING, CONTROLLER, key, native_construction
from test_survival_structure_seeds import task
from minecraft_codex_bridge.bridge import _REQUEST_CONTEXT
from minecraft_codex_bridge.server import invoke_control
from test_exact_placement_attachment import health
from minecraft_codex_bridge.construction_tasks import BLUEPRINT_BUILD_SCHEMA
from test_exact_placement_attachment import binding


@pytest.mark.parametrize("extra,valid", [({}, True), ({"replace": True}, False),
    ({"expectedPlacementFacing": "WEST"}, False), ({"requireSurvivalFlight": False}, False),
    ({"jumpUnderfoot": False}, False), ({"attachmentBinding": binding()}, False)])
def test_jump_schema_excludes_conflicting_paths(extra, valid):
    schema = next(t.schema for t in TOOLS if t.name == "mcb_place_block")
    args = {"x": 0, "y": 4, "z": 0, "hotbarSlot": 1, "expectedWorld": "test", "jumpUnderfoot": True, **extra}
    assert (not list(Draft202012Validator(schema).iter_errors(args))) is valid


@pytest.mark.parametrize("fault", [None, "capability", "occupied", "forbidden", "unowned", "partial", "headroom", "liquid", "wrong_xyz", "future_port_body"])
async def test_jump_seed_needs_public_material_owned_support_and_complete_geometry(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance = task(client)
    instance.approach_health = {"jumpPlacementVersion": True if fault == "capability" else 1}
    p = {**CONTROLLER, "x": CONTROLLER["x"] + 2, "y": 5, "group": 0}
    k = key(p); owned = {(p["x"], 4, p["z"])}
    if fault == "unowned": owned.clear()
    if fault == "occupied": state["blocks"][k] = CASING.copy()
    choice = {"sourceIndex": 9, "item": CASING.copy(), "nbtIdentity": "", "publicCandidate": {"blockIdentity": CASING.copy()}}
    reads = []
    async def observed(args, *, operation):
        reads.append(operation)
        row = {**p, "status": "observed", "loaded": True, "occupiable": True, "canStand": True,
            "liquid": fault == "liquid", "hazard": False, "entityCoveragePartial": False,
            "collisionsTruncated": False, "blockingEntities": [], "collisions": []}
        if fault == "headroom": row.update(occupiable=False, canStand=False, collisions=[{}])
        if fault == "wrong_xyz": row["x"] += 1
        return {"source": "integrated_server", "dimension": 0, "hasMore": fault == "partial", "positions": [row]}
    monkeypatch.setattr(instance, "frontier_structure_read", observed)
    forbidden = {k} if fault == "forbidden" else set()
    goals = {(p["x"] if fault == "future_port_body" else p["x"]-1,7,p["z"])}
    if fault in ("partial", "wrong_xyz"):
        with pytest.raises(BridgeError):
            await instance.choose_jump_seed({k: p}, {k: [choice]}, goals, forbidden, owned)
    else:
        result = await instance.choose_jump_seed({k: p}, {k: [choice]}, goals, forbidden, owned)
        assert result == ({**copy.deepcopy(choice), "position": p} if fault is None else None)
    assert not any(c["operation"] in ("player.input", "player.navigate", "player.place_block") for c in state["calls"])


async def test_high_port_can_choose_owned_underfoot_stance_without_an_attachment_path(native_construction, monkeypatch):
    import test_survival_frontier_connections as frontier
    from test_survival_structure_seeds import install_finite_native_receipts
    client, state = native_construction
    instance = task(client)
    points = frontier.setup(instance, state)
    points[-1]['y'] = 5
    instance.survival_capture_points = {key(p): p for p in points}
    instance.survival_capture_positions = set(instance.survival_capture_points)
    support = {**points[0], 'y': 2}
    state['blocks'][key(support)] = CASING.copy()
    state['position']['y'] = 2
    used = {key(support)}
    frontier.install_reads(instance, state, monkeypatch)
    frontier.install_moves(instance, state, monkeypatch)
    def jump_receipt(receipt):
        receipt.update(jumpPlacementVersion=1, jumpPlacementConfirmed=True, inputKeysReleased=True,
            clientAirObserved=True, serverAirObserved=True)
    placed = install_finite_native_receipts(instance, state, monkeypatch, mutate=jump_receipt)
    instance.approach_health['jumpPlacementVersion'] = 1
    original = instance.frontier_structure_read
    async def spatial(args, *, operation='world.structure'):
        if operation != 'world.spatial':
            return await original(args, operation=operation)
        assert args['positions'] == [{a:points[0][a] for a in ('x','y','z')}]
        return {'source':'integrated_server','dimension':0,'hasMore':False,'positions':[
            {**args['positions'][0],'status':'observed','loaded':True,'occupiable':True,'canStand':True,
                'liquid':False,'hazard':False,'entityCoveragePartial':False,'collisionsTruncated':False,
                'blockingEntities':[],'collisions':[]}]}
    monkeypatch.setattr(instance, 'frontier_structure_read', spatial)
    routes = []
    async def navigate(destination, strategy, phase):
        assert strategy == 'walking' and phase == 'survival_frontier_jump_approach'
        routes.append(destination)
        state['position'] = dict(destination)
    async def unexpected_probe(*args, **kwargs):
        pytest.fail('A proved underfoot stance must not first fill unrelated overhead attachment cells')
    monkeypatch.setattr(instance, 'bootstrap_navigation', navigate)
    monkeypatch.setattr(instance, 'probe_survival_frontier', unexpected_probe)
    assert await instance.extend_survival_frontier(instance.survival_port_candidates, used)
    assert len(placed) == len(routes) == instance.survival_auto_seed_count == 1
    assert placed[0][1] == {a:points[0][a] for a in ('x','y','z')}
    assert instance.survival_seed_planning[-1]['preferredUnderfootStance'] is True
    assert instance.supports[-1]['jumpPlacementConfirmed'] is True
    assert instance.survival_material_ledger[-1]['deductions'][0]['count'] == 1
    assert key(points[0]) in used


@pytest.mark.parametrize("version", [None, True, 2, 1])
async def test_standalone_jump_requires_version_and_binds_fresh_session(version):
    class Client:
        calls = []
        async def invoke(self, operation, args):
            assert operation == "bridge.health"
            return {**health(), "jumpPlacementVersion": version}
        async def call(self, operation, args):
            self.calls.append((operation, copy.deepcopy(_REQUEST_CONTEXT.get())))
            return {"jumpPlacementConfirmed": True}
    client = Client()
    args = {"x": 0, "y": 4, "z": 0, "hotbarSlot": 1, "expectedWorld": "world", "jumpUnderfoot": True}
    if type(version) is int and version == 1:
        await invoke_control(client, "player.place_block", args)
        assert client.calls == [("player.place_block", {"version": 1, "expectedSessionId": "session",
            "expectedProfile": "developer", "expectedWorld": "world", "requireBackground": True})]
    else:
        with pytest.raises(BridgeError, match="underfoot"):
            await invoke_control(client, "player.place_block", args)
        assert client.calls == []


@pytest.mark.parametrize("timeout,valid", [(60_000, True), (120_000, True), (120_001, False)])
def test_large_construction_budget_is_explicit_and_bounded(timeout, valid):
    schema = BLUEPRINT_BUILD_SCHEMA["properties"]["timeout_ms"]
    assert schema["default"] == 60_000
    assert (not list(Draft202012Validator(schema).iter_errors(timeout))) is valid
