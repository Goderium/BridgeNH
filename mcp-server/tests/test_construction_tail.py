import copy
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tail import allocate, finish_air_tail, validate_terminal
from test_actual_survival_workflows import server_inventory, native_construction, task
from test_ae_material_source import SOURCE
from test_construction_tasks import declaration
from minecraft_codex_bridge.validation import tool_validator


def terminal():
    return {"status": "no_progress", "nativeDone": False, "partial": True, "outcomeUnknown": False,
        "formationVerified": False, "materialAccountingComplete": True, "callsStarted": 1, "callsCompleted": 1,
        "batches": [{"nativeReturn": 0, "committedItems": [], "playerDeductions": []}]}


def test_tail_schema_rejects_ae_source_before_any_game_action():
    validator = tool_validator("mcb_blueprint_build")
    for budget in ({}, {"finishAirTail": True}, {"finishAirTail": False, "materialSource": SOURCE}, {"materialSource": SOURCE}):
        validator.validate(declaration(actualSurvival=True, survivalBuild=budget))
    assert list(validator.iter_errors(declaration(actualSurvival=True,
        survivalBuild={"finishAirTail": True, "materialSource": SOURCE})))


async def test_tail_runtime_rejects_source_before_any_bridge_call(native_construction):
    client, state = native_construction
    instance = task(client, actualSurvival=True, survivalBuild={"finishAirTail": True, "materialSource": SOURCE})
    before = len(state["calls"])
    with pytest.raises(BridgeError) as error: await instance.preflight()
    assert error.value.details["reason"] == "survival_shell_tail_source_unsupported"
    assert len(state["calls"]) == before


@pytest.mark.parametrize("fault", [None, "unknown", "inflight", "bool_count", "negative", "committed", "deducted", "done", "ae"])
def test_tail_requires_known_completed_empty_terminal_batch(fault):
    value = terminal()
    if fault == "unknown": value["outcomeUnknown"] = True
    if fault == "inflight": value["callsStarted"] = 2
    if fault == "bool_count": value["callsCompleted"] = True
    if fault == "negative": value["batches"][0]["nativeReturn"] = -1
    if fault == "committed": value["batches"][0]["committedItems"] = [{}]
    if fault == "deducted": value["batches"][0]["playerDeductions"] = [{}]
    if fault == "done": value["nativeDone"] = True
    if fault is None:
        validate_terminal(value, None)
    else:
        with pytest.raises(BridgeError): validate_terminal(value, {} if fault == "ae" else None)


POINTS = [{"x": x, "y": 4, "z": 3, "group": 0} for x in (1, 2)]
ITEM = {"registryName": "minecraft:stone", "metadata": 0}
CHOICE = {"sourceIndex": 9, "item": ITEM, "nbtIdentity": "", "publicCandidate": {**ITEM, "count": 1}}


def options():
    return {(p["x"],p["y"],p["z"]): [copy.deepcopy(CHOICE)] for p in POINTS}


@pytest.mark.parametrize("count", [1,2])
def test_tail_reserves_shared_source_before_any_placement(count):
    rows = [{"index": 9, "item": {**ITEM, "count": count}}]
    if count == 1:
        with pytest.raises(BridgeError, match="aggregate"): allocate(POINTS, options(), rows)
    else:
        assert len(allocate(POINTS, options(), rows)) == 2
    assert rows[0]["item"]["count"] == count


@pytest.mark.parametrize("fault", [None, "shortage", "unknown_candidate", "changed", "placement", "unloaded", "too_large"])
async def test_tail_preflights_then_places_once_without_native_callback_replay(fault):
    points = copy.deepcopy(POINTS) if fault != "too_large" else [{"x": x,"y":4,"z":3,"group":0} for x in range(17)]
    task = SimpleNamespace(actual_survival=True, survival_ae_source=None, _survival_frontier_binding=None,
        _frontier_seed_borrow=None, selected_ports=[], survival_capture_points={(p['x'],p['y'],p['z']):p for p in points},
        survival_position_candidates={}, calls=[])
    def air(p): return {**p,"registryName":"minecraft:air","loaded":fault != "unloaded"}
    async def blocks(ps): return {(p['x'],p['y'],p['z']):air(p) for p in ps}
    async def pass_blocks(ps,actual): actual.update(await blocks(ps))
    async def block(p): return {**air(p), **({"registryName":"minecraft:stone"} if fault=='changed' else {})}
    async def noop(*args,**kwargs): pass
    async def inventory():
        inv = server_inventory(1)
        inv['slots'][2]['item'] = None
        inv['slots'][9]['item']={**ITEM,"count":1 if fault=='shortage' else 2,"hasTag":False,"nbtIdentity":""}
        return inv
    async def prepare(c): task.calls.append(('prepare',c['position']));return 8
    async def place(p,slot,phase,record):
        task.calls.append(('place',p))
        if fault=='placement': raise BridgeError('timeout','outcome unknown',outcome_unknown=True)
        record['placementConfirmed']=True
    task.blocks,task.block,task.verify_declared_ports=blocks,block,noop
    task.frontier_pass_blocks=pass_blocks
    task.read_position_candidates,task.frontier_inventory=noop,inventory
    task.frontier_seed_choices=lambda inv,ps: {} if fault=='unknown_candidate' else options()
    task.prepare_verified_frontier_seed,task.place,task.release_frontier_seed_source=prepare,place,noop
    if fault:
        with pytest.raises(BridgeError): await finish_air_tail(task)
        assert sum(c[0]=='place' for c in task.calls) == (1 if fault=='placement' else 0)
    else:
        await finish_air_tail(task)
        assert [c[1] for c in task.calls if c[0]=='place']==POINTS
        assert task.survival_shell_completion['status']=='completed'
        assert task.survival_shell_completion['nativeDone'] is False
        assert task.survival_shell_completion['formationVerified'] is False
