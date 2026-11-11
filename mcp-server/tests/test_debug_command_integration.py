"""Exercise the actual command, Adapter and observer against offline native HTTP DTOs."""

from copy import deepcopy

import pytest

from minecraft_codex_bridge.command import invoke, parser
from minecraft_codex_bridge.server import Adapter
from test_qa_debug_observe import CASE, RUN_ID, native, operations


pytestmark = pytest.mark.anyio


def options(*extra):
    return parser().parse_args([
        'qa-debug-observe', CASE, '--session', 'session', '--world', 'test-world', *extra,
    ])


def assert_single_run_without_stop(state, *, explicit_origin=False):
    submissions = operations(state, 'qa.command') + operations(state, 'qa.run')
    assert len(submissions) == 1
    if explicit_origin:
        assert submissions[0]['operation'] == 'qa.run' and submissions[0]['args']['selector'] == CASE
    else:
        assert submissions[0]['operation'] == 'qa.command' and submissions[0]['args']['command'] == 'run'
    assert not operations(state, 'qa.cancel')
    assert all(value['args']['runId'] == RUN_ID and 'waitMillis' not in value['args']
               for operation in ('qa.status', 'qa.results') for value in operations(state, operation))


async def test_real_default_debug_command_preserves_exact_physical_channels_and_scope(native):
    client, state = native
    payload = await invoke(Adapter(client), options())
    assert payload['ok'] is True, payload['data'].get('error')
    data = payload['data']
    assert data['status'] == 'observation_ready' and data['observationVerified'] is True
    assert data['runStillActive'] is True
    assert data['stopPending'] is True
    assert all(data[key] is False for key in ('finitePass', 'testPassed', 'fullFlowVerified',
                                             'stopActionSubmitted', 'stopDrainStable85Verified'))
    assert data['remainingLifecycle'] == ['explicit_normal_stop', 'ordinary_drain_recovery', '85_real_stopped_ticks']
    summary = data['observationSummary']
    assert summary['actualReceiverChannels'] == len(summary['receiverChannels']) == 2
    products = {(row['registryName'], row['metadata'], row['nbt']): row['count']
                for row in summary['itemProducts']}
    assert len(products) == 2 and summary['receiverComplete'] is True
    for channel in summary['receiverChannels']:
        item = channel['configuredItem']
        identity = tuple(item[field] for field in ('registryName', 'metadata', 'nbt'))
        assert channel['physicalCount'] == products[identity]
        assert type(channel['physicalCount']) is str and int(channel['physicalCount']) > 0
        assert channel['displayProjectionExcludedFromAccounting'] is True
    assert summary['logicalDemandIsPhysicalConsumptionMeasurement'] is False
    assert_single_run_without_stop(state)


async def test_real_details_debug_command_keeps_complete_diagnostics_and_exact_origin(native):
    client, state = native
    origin = state.plan['allocationOrigin']
    # The captured sample's status fixtures carry its live session. The mock
    # status must mirror its own fresh plan/session, just as the run response does.
    def current_fixtures(value, arguments, state):
        value['fixtures'] = [deepcopy(state.plan)]
    state.overrides['qa.status'] = current_fixtures
    state.overrides['qa.results'] = current_fixtures
    adapter = Adapter(client)
    payload = await invoke(adapter, options('--details', '--origin', *[str(origin[axis]) for axis in 'xyz']))
    assert payload['ok'] is True, payload['data'].get('error')
    data = payload['data']
    assert all(data[key] is True for key in ('reportsComplete', 'reportCoherent', 'diagnosticsComplete', 'stopPending'))
    assert len(data['cases']) == 1 and data['cases'][0]['id'] == CASE
    assert data['issues'] == [] and data['preflight'] and data['afterScan']
    assert data['fullFlowEvidence']['verified'] is False
    assert data['fullFlowEvidence']['observation']['infiniteObservation']['receiverCheckpoint']['worldTick'] == int(
        data['observationSummary']['observedWorldTick'])
    plans = [value for value in operations(state, 'qa.catalog') if value['args']['section'] == 'fixture_plan']
    assert len(plans) == 2 and all(value['args']['origin'] == origin for value in plans)
    assert operations(state, 'qa.run')[0]['args']['origin'] == origin
    assert not adapter.workflow_lock.locked()
    assert_single_run_without_stop(state, explicit_origin=True)


async def test_real_debug_command_refuses_missing_current_checkpoint_without_replay(native):
    client, state = native
    state.flow_mutation = lambda value: value['infiniteObservation'].pop('receiverCheckpoint')
    payload = await invoke(Adapter(client), options())
    assert payload['ok'] is False
    data = payload['data']
    assert data['observationVerified'] is False and data['fullFlowVerified'] is False
    assert data['error']['code'] == 'qa_debug_receiver_checkpoint_unavailable'
    assert data['reportsComplete'] and data['diagnosticsComplete'] and data['cases']
    assert data['stopPending'] is True and data['stopActionSubmitted'] is False
    assert_single_run_without_stop(state)


async def test_real_debug_command_keeps_pause_guard_before_any_submission(native):
    client, state = native
    state.overrides['client.snapshot'] = lambda value, arguments, state: value.update(paused=True)
    payload = await invoke(Adapter(client), options())
    assert payload['ok'] is False
    assert payload['data']['observationVerified'] is False and payload['data']['runSubmissions'] == 0
    assert not operations(state, 'qa.command') and not operations(state, 'qa.cancel')
