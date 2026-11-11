"""Fresh native target-bound GUI proof; scalar fixtures are not live evidence."""
import copy
import math

import pytest

from minecraft_codex_bridge.bridge import BridgeError
import test_native_actual_reach_contract as reach
import test_native_navigation as navigation

native_bridge = navigation.native_bridge


async def reuse_task(client, state, monkeypatch, *, distance=3.4, client_reach=4.5, server_reach=5, fault=None):
    target = navigation.recipes.INPUT
    state['position'] = {'x': target['x'] + .5, 'y': target['y'] - 1, 'z': target['z'] - distance}
    reach.install_actual_reach(client, state, monkeypatch, client_reach=client_reach, server_reach=server_reach,
        fault=fault if fault in ('unknown', 'partial', 'server_actor', 'body', 'ray', 'missing_server', 'native_getter_drift', 'outcome_unknown', 'legacy_receipt') else None)
    state['open'] = 'input'
    task = reach.make_reach_task('recipe', client, state)
    task.input_inventory_owner = {'registryName': 'gregtech:gt.blockmachines', 'metaTileId': 77, 'metaClass': 'example.PublicMultiblock'}
    task.opened_gui = {'target': 'input', 'screenId': 'controller-screen', 'windowId': 7, 'side': 2}
    snapshot = await client.call('client.snapshot', {})
    snapshot.update(screenOpen=True, screenId='controller-screen')
    snapshot['player'].update(yaw=0, pitch=0, selectedHotbarSlot=state.get('selected', 0))
    for key in ('openContainer', 'clientContainer'):
        snapshot['serverControl'][key].update(windowId=7, isInventoryContainer=False)
    gui = {'screenId': 'controller-screen', 'windowId': 7, 'totalSlots': 1, 'truncated': False, 'slots': [{'slot': 0, 'item': None}]}
    original = client.call
    rays = []

    async def observed(operation, args, **kwargs):
        if operation == 'world.spatial':
            rays.append(copy.deepcopy(args))
            return {'status': 'observed', 'hit': {'type': 'block', 'block': copy.deepcopy(target), 'side': 3 if fault == 'fresh_ray' else 2}}
        result = await original(operation, args, **kwargs)
        if operation == 'player.plan_approach':
            context = result.context['snapshot']
            for key in ('openContainer', 'clientContainer'):
                context['serverControl'][key].update(windowId=7, isInventoryContainer=False)
            if fault == 'screen': result['current']['screenId'] = context['screenId'] = 'another-screen'
            elif fault == 'server_window': context['serverControl']['openContainer']['windowId'] = 8
            elif fault == 'client_window': context['serverControl']['clientContainer']['windowId'] = 8
            elif fault == 'owner': result['target']['gregtech']['metaTileId'] += 1
            elif fault == 'side' and result['selected'] is not None: result['selected']['attachment']['side'] = 3
            elif fault == 'selected': context['player']['selectedHotbarSlot'] = 8
            elif fault == 'camera': context['player']['yaw'] = 5
            elif fault == 'position': context['player']['x'] += .1
        return result

    async def read(*args, **kwargs): return gui, snapshot
    monkeypatch.setattr(client, 'call', observed)
    monkeypatch.setattr(task, 'read_with_snapshot', read)
    return task, gui, rays


@pytest.mark.parametrize('distance,client_reach,server_reach', [(3.4, 4.5, 5), (4.4, 4.5, 5), (3.8, 5, 4), (2.4, 4.5, 5)])
async def test_fresh_minimum_reach_with_exact_screen_face(native_bridge, monkeypatch, distance, client_reach, server_reach):
    client, state = native_bridge
    task, gui, rays = await reuse_task(client, state, monkeypatch, distance=distance, client_reach=client_reach, server_reach=server_reach)
    assert await task.reuse_gui('input') == gui
    assert len(state['planner_calls']) == 1 and not rays
    call = state['planner_calls'][0]
    assert call['currentOnly'] is call['useNormalReach'] is True and call['maxDistance'] == 32
    assert call['allowedSides'] == [2] and call['targetIdentity'] == task.input_inventory_owner
    assert not state['navigation_calls'] and not navigation.recipes.writes(state)


@pytest.mark.parametrize('distance,client_reach,server_reach', [(4.6, 4.5, 5), (3.4, 5, 3), (3.4, 3, 5)])
async def test_never_exceeds_either_reach(native_bridge, monkeypatch, distance, client_reach, server_reach):
    client, state = native_bridge
    task, _, rays = await reuse_task(client, state, monkeypatch, distance=distance, client_reach=client_reach, server_reach=server_reach)
    with pytest.raises(BridgeError): await task.reuse_gui('input')
    assert len(state['planner_calls']) == 1 and not rays and not navigation.recipes.writes(state)


@pytest.mark.parametrize('fault', ['unknown', 'partial', 'server_actor', 'body', 'ray', 'missing_server', 'native_getter_drift',
    'outcome_unknown', 'legacy_receipt', 'screen', 'server_window', 'client_window', 'owner', 'side', 'selected', 'camera', 'position'])
async def test_invalid_proof_never_moves_reopens_transfers_or_falls_back(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    task, _, rays = await reuse_task(client, state, monkeypatch, fault=fault)
    with pytest.raises(BridgeError): await task.reuse_gui('input')
    assert len(state['planner_calls']) == 1 and not rays
    assert not state['navigation_calls'] and not navigation.recipes.writes(state)


@pytest.mark.parametrize('missing', ['native_operation', 'normal_reach_version', 'current_only'])
async def test_missing_capability_keeps_three_block_bound(native_bridge, monkeypatch, missing):
    client, state = native_bridge
    task, _, rays = await reuse_task(client, state, monkeypatch)
    if missing == 'native_operation': task.capabilities.remove('player.plan_approach')
    elif missing == 'normal_reach_version': task.approach_health.pop('approachNormalReachVersion')
    else: task.approach_health['approachCurrentOnly'] = False
    with pytest.raises(BridgeError) as error: await task.reuse_gui('input')
    assert error.value.details['reason'] == 'open_gui_target_out_of_range'
    assert not state['planner_calls'] and not rays and not navigation.recipes.writes(state)


@pytest.mark.parametrize('epsilon,allowed', [(-1e-5, True), (0, True), (1e-5, False)])
async def test_native_face_boundary(native_bridge, monkeypatch, epsilon, allowed):
    client, state = native_bridge
    distance = math.sqrt(4.5 ** 2 - .12 ** 2) - .001 + epsilon
    task, gui, rays = await reuse_task(client, state, monkeypatch, distance=distance)
    if allowed: assert await task.reuse_gui('input') == gui
    else:
        with pytest.raises(BridgeError): await task.reuse_gui('input')
    assert len(state['planner_calls']) == 1 and not rays and not navigation.recipes.writes(state)


async def test_legacy_reuse_still_requires_its_real_fresh_ray(native_bridge, monkeypatch):
    client, state = native_bridge
    task, _, rays = await reuse_task(client, state, monkeypatch, distance=2.4, fault='fresh_ray')
    task.approach_health.pop('approachNormalReachVersion')
    with pytest.raises(BridgeError) as error:
        await task.reuse_gui('input')
    assert error.value.details['reason'] == 'open_gui_target_visibility_lost'
    assert len(rays) == 1 and not state['planner_calls'] and not navigation.recipes.writes(state)
