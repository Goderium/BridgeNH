"""Real acceptance of all48 physical bindings and263 padded cells; no live world execution."""
from copy import deepcopy

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.scan_regions import validate_scan_region
from test_qa_machine_flows import GroupClient, fixtures, run
from test_qa_multi_fluid import multi_client


def physical_client():
    client = multi_client()
    original = client.case_mutation
    def mutate(rows):
        original(rows)
        row = rows[0]
        evidence, proof = row['flowEvidence'], row['fixtureCoverage']['after']
        owner = proof['gtTiles'][0]
        multi = evidence['publicMultiFluidBindings']
        occupied = {tuple(point.values()) for role in ('input', 'output', 'receiver') for point in multi[role + 'Positions']}
        candidates = [{'x': x, 'y': owner['y'], 'z': owner['z'] + z} for z in range(1, 6) for x in range(3, 15)]
        points = [point for point in candidates if tuple(point.values()) not in occupied]
        index = 0
        for role, prefix, count_key in (('input', 'finite_fluid_input', 'physicalFluidInputPortCount'),
                ('output', 'fluid_output', 'physicalFluidOutputPortCount'),
                ('receiver', 'fluid_receiver', 'physicalFluidReceiverCount')):
            for number in range(3, 16):
                point = points[index]; index += 1
                multi[role + 'Positions'].append(point)
                for snapshot in evidence['snapshots']:
                    snapshot['fluids'].append({'binding': prefix + '_' + str(number), 'position': point,
                        'complete': True, 'totalTanks': 1, 'tanks': [{'tank': 0, 'capacity': 4000, 'amount': 0}]})
                tile = {**point, 'tileClass': 'test.GTTile', 'valid': True}
                hatch = {**owner, **tile, 'controller': False, 'metaTileId': 200 + index,
                    'metadata': 200 + index, 'runtimeClass': 'test.OrdinaryHatch'}
                proof['tiles'].append(tile); proof['gtTiles'].append(hatch)
                evidence['activeTiles'].append({**point, 'startTimer': 100, 'endTimer': 101})
                if role != 'receiver':
                    owner['ports']['fluidInput' if role == 'input' else 'fluidOutput'].append({
                        **{key: hatch[key] for key in ('x', 'y', 'z', 'metaTileId', 'tileClass', 'runtimeClass', 'valid')},
                        'actualOwnerIdentityVerified': True})
            multi[count_key] = 16
        proof['tileCount'] += 39; proof['gtTileCount'] += 39
        proof['nonAir'] += 39; proof['air'] -= 39; proof['nonAirTypes'][0]['count'] += 39
    client.case_mutation = mutate
    return client


async def test_all48_complete_bindings_including_empty_eleventh_and_sixteenth_are_accepted():
    result = await run(physical_client())
    assert result['status'] == 'completed' and result['groupFullFlowVerified']
    limits = result['groupLimits']
    assert [limits[key] for key in ('maxRawFluidInputs', 'maxRawFluidOutputs')] == [8, 8]
    assert [limits[key] for key in ('maxPhysicalFluidInputs', 'maxPhysicalFluidOutputs', 'maxPhysicalFluidReceivers')] == [16, 16, 16]
    assert limits['maxFiniteFluidCheckpointBindings'] == 48


@pytest.mark.parametrize('fault', ['missing_empty', 'seventeenth', 'receiver_alias', 'late_empty_product', 'wrong_owner'])
async def test_empty_physical_tail_still_requires_conservation_owner_and_capacity(fault):
    client = physical_client(); original = client.case_mutation
    def mutate(rows):
        original(rows)
        evidence = rows[0]['flowEvidence']; multi = evidence['publicMultiFluidBindings']
        if fault == 'missing_empty': evidence['snapshots'][2]['fluids'].pop()
        elif fault == 'seventeenth': multi['physicalFluidOutputPortCount'] = 17
        elif fault == 'receiver_alias': multi['receiverPositions'][-1] = deepcopy(multi['inputPositions'][-1])
        elif fault == 'late_empty_product': evidence['snapshots'][-1]['fluids'][-1]['tanks'][0].update(name='waste', nbt='{}', amount=1)
        else: rows[0]['fixtureCoverage']['after']['gtTiles'][0]['ports']['fluidOutput'].pop()
    client.case_mutation = mutate
    result = await run(client)
    assert result['status'] == 'failed' and not result['groupFullFlowVerified']


async def test257_observed_shape_has263_complete_padded_cells_with_unchanged_volume_budget():
    client = GroupClient(count=1, dynamic=True)
    client.values[0]['fixtureBounds'].update(minX=0, maxX=256, minY=0, maxY=3, minZ=0, maxZ=3)
    client.records = fixtures(client.values)
    result = await run(client)
    assert result['status'] == 'completed' and result['groupFullFlowVerified']
    assert client.records[0]['plan']['placedSize']['x'] == 261
    assert client.records[0]['plan']['requestedVolume'] == 26300
    assert result['groupLimits']['maxReservedAxis'] == 263
    assert result['groupLimits']['maxFixtureReservedCells'] == 2097152
    assert result['groupLimits']['maxBatchReservedCells'] == 16777216


def test_explicit_scan263_has_no_clipping_and264_is_refused():
    validate_scan_region({'bounds': {'minX': 0, 'maxX': 262, 'minY': 2, 'maxY': 4, 'minZ': 0, 'maxZ': 2}})
    with pytest.raises(BridgeError):
        validate_scan_region({'bounds': {'minX': 0, 'maxX': 263, 'minY': 2, 'maxY': 4, 'minZ': 0, 'maxZ': 2}})
