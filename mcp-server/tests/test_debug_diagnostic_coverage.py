"""Live 18/16 text DTO regression while retaining all physical acceptance guards."""

from copy import deepcopy
import json
from pathlib import Path

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.command import invoke, parser
from minecraft_codex_bridge.qa_debug_tasks import run_qa_debug_observe
from minecraft_codex_bridge.server import Adapter
from test_qa_debug_observe import CASE, RUN_ID, DebugState, args, operations


pytestmark = pytest.mark.anyio
RECORD = json.loads((Path(__file__).parent / 'fixtures/r37-live-debug-truncated.json').read_text(encoding='utf-8'))
RECORDED_CASE = RECORD['case']['data']['entries'][0]
RECORDED_FLOW = RECORDED_CASE['flowEvidence']
TEXT_FIELDS = ('failureMessage', 'failureType', 'failureTrace', 'blockedByIssueId',
               'detailsCoverage', 'detailsTruncated', 'outputTotal', 'outputLines')


class RecordedState(DebugState):
    def flow(self):
        self.counter += 1
        value = deepcopy(RECORDED_FLOW)
        # Offline replay uses the existing mock session and its explicit run ID.
        # All actual quantities, NBT, physical slots, stages and ticks stay intact.
        value.update(sessionId='session', runId=RUN_ID)
        if self.flow_mutation:
            self.flow_mutation(value)
        return value


@pytest.fixture
async def recorded():
    state = RecordedState()
    def recorded_text(value, arguments, state):
        for row in value['entries']:
            row.update({field: deepcopy(RECORDED_CASE[field]) for field in TEXT_FIELDS})
    state.overrides[('qa.results', 'cases')] = recorded_text
    client = BridgeClient(Configuration({'MCB_URL': 'http://127.0.0.1:25671', 'MCB_TOKEN': 'offline-debug-test-token'}),
                          timeout=.5, transport=httpx.MockTransport(state.respond))
    try:
        yield client, state
    finally:
        await client.aclose()


async def observe(recorded):
    return await run_qa_debug_observe(recorded[0], args(includeDetails=True, max_return_bytes=262144), anyio.Lock())


def single_run_and_complete_pages(state, *, diagnostic_recovery=False):
    assert len(operations(state, 'qa.command')) == 1
    assert operations(state, 'qa.command')[0]['args']['command'] == 'run'
    assert not operations(state, 'qa.cancel') and not operations(state, 'qa.run')
    results = operations(state, 'qa.results')
    assert [value['args']['section'] for value in results] == ['cases', 'issues'] * (2 if diagnostic_recovery else 1)
    assert all(value['args']['runId'] == RUN_ID and value['args']['offset'] == 0 for value in results)


def assert_actual_text_coverage(data):
    assert data['diagnosticsComplete'] is False
    coverage = data['diagnosticCoverage']
    assert coverage['nativeTextComplete'] is False and coverage['nativeTextTruncationObserved'] is True
    assert coverage['outputLinePaginationAvailable'] is False
    assert coverage['rowsRead'] == 1 and coverage['rowSummariesOmitted'] == 0
    row = coverage['rowSummaries'][0]
    assert row['id'] == CASE and row['detailsCoverage'] == 'requested' and row['detailsTruncated'] is True
    assert (row['outputLinesReturned'], row['outputTotal'], row['outputLinesOmitted']) == (16, 18, 2)


async def test_exact_live_text_truncation_keeps_complete_actual_physical_observation(recorded):
    data = await observe(recorded)
    assert data['status'] == 'observation_ready' and data['observationVerified'], data.get('error')
    assert data['reportsComplete'] and data['reportCoherent']
    assert_actual_text_coverage(data)
    assert data['cases'][0]['outputLines'] == RECORDED_CASE['outputLines']
    assert data['cases'][0]['outputTotal'] == 18 and data['cases'][0]['detailsTruncated'] is True
    observation = data['fullFlowEvidence']['observation']['infiniteObservation']
    assert observation['receiverCheckpoint'] == RECORDED_FLOW['infiniteObservation']['receiverCheckpoint']
    assert data['observationSummary']['itemProducts'] == [
        {**row, 'count': str(row['count'])} for row in sorted(observation['recoveredItems'],
            key=lambda value: (value['registryName'], value['metadata'], value['nbt']))]
    assert data['scanCells'] == 2 * recorded[1].plan['requestedVolume']
    assert data['runStillActive'] and data['stopPending']
    assert all(data[key] is False for key in ('testPassed', 'finitePass', 'fullFlowVerified',
                                             'stopActionSubmitted', 'stopDrainStable85Verified'))
    single_run_and_complete_pages(recorded[1])


@pytest.mark.parametrize('details', [False, True])
async def test_actual_command_keeps_text_truncation_and_complete_report_flags(recorded, details):
    options = parser().parse_args(['qa-debug-observe', CASE, '--session', 'session', '--world', 'test-world',
                                  *(['--details'] if details else [])])
    payload = await invoke(Adapter(recorded[0]), options)
    assert payload['ok'] is True, payload['data'].get('error')
    assert payload['data']['reportsComplete'] and payload['data']['reportCoherent']
    assert_actual_text_coverage(payload['data'])
    single_run_and_complete_pages(recorded[1])


@pytest.mark.parametrize('fault', ['missing_checkpoint', 'wrong_tick', 'bulk_mismatch', 'growth_missing', 'growth_one_tick'])
async def test_truncated_text_never_replaces_complete_same_tick_physical_evidence(recorded, fault):
    def mutate(value):
        observation = value['infiniteObservation']
        if fault == 'missing_checkpoint':
            observation.pop('receiverCheckpoint')
        elif fault == 'wrong_tick':
            observation['receiverCheckpoint']['worldTick'] -= 1
        elif fault == 'bulk_mismatch':
            observation['receiverCheckpoint']['items'][1]['channels'][0]['bulk']['count'] -= 1
        elif fault == 'growth_missing':
            observation['itemProductGrowth'].pop()
        else:
            observation['itemProductGrowth'][0]['outputIncrementTicks'] = 1
    recorded[1].flow_mutation = mutate
    data = await observe(recorded)
    assert data['status'] != 'observation_ready' and data['observationVerified'] is False
    assert data['reportsComplete'] and data['fullFlowVerified'] is False
    assert_actual_text_coverage(data)
    single_run_and_complete_pages(recorded[1])


@pytest.mark.parametrize('fault', ['issue', 'case_suffix_missing', 'wrong_case', 'physical_truncation_flag'])
async def test_text_truncation_never_hides_infrastructure_issues_or_incomplete_case_pages(recorded, fault):
    state = recorded[1]
    if fault == 'issue':
        state.overrides[('qa.results', 'issues')] = lambda value, arguments, state: value.update(
            entries=[{'id': 'actual-issue', 'detailsCoverage': 'requested', 'detailsTruncated': False,
                      'outputTotal': 0, 'outputLines': []}], total=1)
    else:
        previous = state.overrides[('qa.results', 'cases')]
        def mutate(value, arguments, state):
            previous(value, arguments, state)
            if fault == 'case_suffix_missing':
                value['total'] = 2
            elif fault == 'wrong_case':
                value['entries'][0]['id'] = 'tstqa:another.case'
            else:
                value['entries'][0]['flowEvidenceTruncated'] = True
        state.overrides[('qa.results', 'cases')] = mutate
    data = await observe(recorded)
    assert data['status'] != 'observation_ready' and data['observationVerified'] is False
    assert data['reportsComplete'] is (fault != 'case_suffix_missing')
    assert data['fullFlowVerified'] is False and data['diagnosticsComplete'] is False
    single_run_and_complete_pages(state, diagnostic_recovery=fault == 'case_suffix_missing')


async def test_truncated_text_still_requires_the_entire_loaded_after_scan(recorded):
    def incomplete_after(value, arguments, state):
        if state.ran:
            value['coverage']['unloaded'] = 1
            value['coverage']['loaded'] -= 1
            value['coverage']['air'] -= 1
            value['page']['unloaded'] = 1
            value['page']['loaded'] -= 1
            value['page']['air'] -= 1
            value['requestedRegionFullyObserved'] = False
    recorded[1].overrides['world.scan'] = incomplete_after
    data = await observe(recorded)
    assert data['status'] != 'observation_ready' and data['observationVerified'] is False
    assert data['reportsComplete'] and data['error']['code'] == 'qa_space_unloaded'
    assert len(operations(recorded[1], 'world.scan')) == 2
    assert_actual_text_coverage(data)
    single_run_and_complete_pages(recorded[1])
