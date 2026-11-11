"""Generic public debug declaration and strict Stage5 complete-source ABI model."""

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
from test_qa_debug_observe import RUN_ID, DebugState, operations, route


pytestmark = pytest.mark.anyio
RECORD = json.loads((Path(__file__).parent / 'fixtures/r37-live-debug-fluid-initial.json').read_text(encoding='utf-8'))
RECORDED_CASE = RECORD['case']['data']['entries'][0]
RECORDED_FLOW = RECORDED_CASE['flowEvidence']
CASE = RECORDED_CASE['id']
SOURCE_BASELINE = next(row for checkpoint in RECORDED_FLOW['snapshots'] if checkpoint['stage'] == 'debug_supplied'
                       for row in checkpoint['fluids'] if row['binding'] == 'finite_fluid_input')
TEXT_FIELDS = ('failureMessage', 'failureType', 'failureTrace', 'blockedByIssueId',
               'detailsCoverage', 'detailsTruncated', 'outputTotal', 'outputLines')


class GenericFluidState(DebugState):
    def __init__(self):
        super().__init__()
        self.plan = deepcopy(RECORD['status']['fixtures'][0])
        route(self.plan)
        self.case = {'id': CASE, 'template': self.plan['template'], 'rotation': 0,
                     'caseType': 'infiniteDebug', 'parameterized': False, 'skippedAtDiscovery': False}
        self.complete_source_abi_model = True
        def before_flow(value, arguments, state):
            if not state.ran:
                value['flowEvidence']['caseId'] = CASE
        def current_case(value, arguments, state):
            for row in value['entries']:
                row['id'] = CASE
                row.update({field: deepcopy(RECORDED_CASE[field]) for field in TEXT_FIELDS})
        self.overrides[('qa.catalog', 'flow_evidence')] = before_flow
        self.overrides[('qa.results', 'cases')] = current_case

    def flow(self):
        self.counter += 1
        value = deepcopy(RECORDED_FLOW)
        value.update(sessionId='session', runId=RUN_ID)
        if self.complete_source_abi_model:
            # Explicit model of the separately required Java Stage5 fix.
            # Raw captured DTO lacks this binding and remains a negative case.
            # The copied source has its actual baseline position/capacity/empty tank;
            # the enclosing checkpoint retains the captured current normal tick.
            value['infiniteObservation']['receiverCheckpoint']['fluids'].insert(0, deepcopy(SOURCE_BASELINE))
        if self.flow_mutation:
            self.flow_mutation(value)
        return value

    def status(self, *, ready=True):
        value, flow = super().status(ready=ready)
        value.update(selector=CASE, selectedIds=[CASE], fixtures=[deepcopy(self.plan)])
        value['observations'][0]['testId'] = CASE
        return value, flow


@pytest.fixture
async def generic():
    state = GenericFluidState()
    client = BridgeClient(Configuration({'MCB_URL': 'http://127.0.0.1:25671', 'MCB_TOKEN': 'offline-debug-test-token'}),
                          timeout=.5, transport=httpx.MockTransport(state.respond))
    try:
        yield client, state
    finally:
        await client.aclose()


async def observe(generic):
    return await run_qa_debug_observe(generic[0], {'selector': CASE, 'expectedSessionId': 'session',
        'expectedWorld': 'test-world', 'includeDetails': True, 'max_return_bytes': 262144}, anyio.Lock())


def assert_no_replay_or_stop(state):
    assert len(operations(state, 'qa.command')) == 1 and operations(state, 'qa.command')[0]['args']['command'] == 'run'
    assert not operations(state, 'qa.cancel') and not operations(state, 'qa.run')
    assert [value['args']['section'] for value in operations(state, 'qa.results')] == ['cases', 'issues']


@pytest.mark.parametrize('minimum', [0, 3])
async def test_known_public_declaration_with_complete_stage5_source_model_keeps_all_real_products(generic, minimum):
    generic[1].flow_mutation = lambda value: value['infiniteRecipe'].update(minimumIncrementTicksPerItemProduct=minimum)
    data = await observe(generic)
    assert data['status'] == 'observation_ready' and data['observationVerified'], data.get('error')
    assert data['reportsComplete'] and data['reportCoherent'] and data['diagnosticsComplete'] is False
    summary = data['observationSummary']
    assert summary['itemProducts'] == [{**row, 'count': str(row['count'])} for row in RECORDED_FLOW['infiniteObservation']['recoveredItems']]
    assert summary['fluidProducts'] == [{**row, 'amount': str(row['amount'])} for row in RECORDED_FLOW['infiniteObservation']['recoveredFluids']]
    assert summary['itemProductIncrementTicks'][0]['outputIncrementTicks'] == '131'
    assert summary['fluidProductIncrementTicks'][0]['outputIncrementTicks'] == '131'
    observation = data['fullFlowEvidence']['observation']['infiniteObservation']
    assert observation['receiverCheckpoint']['worldTick'] == observation['normalWorldTick']
    assert observation['receiverCheckpoint']['fluids'][0] == SOURCE_BASELINE
    assert data['scanCells'] == 2 * generic[1].plan['requestedVolume']
    assert data['runStillActive'] and data['stopPending']
    assert all(data[key] is False for key in ('testPassed', 'finitePass', 'fullFlowVerified',
                                             'stopActionSubmitted', 'stopDrainStable85Verified'))
    assert_no_replay_or_stop(generic[1])


async def test_actual_raw_capture_missing_current_source_binding_still_fails_closed(generic):
    generic[1].complete_source_abi_model = False
    data = await observe(generic)
    assert data['observationVerified'] is False and data['error']['code'] == 'qa_debug_receiver_changed'
    assert_no_replay_or_stop(generic[1])


@pytest.mark.parametrize('minimum', [-1, 1, 2, 4, True, '0', None])
async def test_unknown_or_untyped_public_minimum_is_refused(generic, minimum):
    generic[1].flow_mutation = lambda value: value['infiniteRecipe'].update(minimumIncrementTicksPerItemProduct=minimum)
    data = await observe(generic)
    assert data['observationVerified'] is False and data['error']['code'] == 'qa_debug_scope_invalid'
    assert_no_replay_or_stop(generic[1])


@pytest.mark.parametrize('fault', ['item_growth_one', 'fluid_growth_one', 'fluid_growth_missing',
                                 'wrong_fluid_growth_identity', 'foreign_source_fluid', 'wrong_receiver_fluid_nbt',
                                 'receiver_quantity_missing', 'wrong_checkpoint_tick'])
async def test_generic_zero_declaration_never_weakens_item_fluid_or_same_tick_physical_checks(generic, fault):
    def mutate(value):
        observation = value['infiniteObservation']
        if fault == 'item_growth_one':
            observation['itemProductGrowth'][0]['outputIncrementTicks'] = 1
        elif fault == 'fluid_growth_one':
            observation['fluidProductGrowth'][0]['outputIncrementTicks'] = 1
        elif fault == 'fluid_growth_missing':
            observation['fluidProductGrowth'] = []
        elif fault == 'wrong_fluid_growth_identity':
            observation['fluidProductGrowth'][0]['name'] = 'water'
        elif fault == 'foreign_source_fluid':
            observation['receiverCheckpoint']['fluids'][0]['tanks'][0].update(name='water', nbt='', amount=1)
        elif fault == 'wrong_receiver_fluid_nbt':
            observation['receiverCheckpoint']['fluids'][-1]['tanks'][0]['nbt'] = '{"foreign":1}'
        elif fault == 'receiver_quantity_missing':
            observation['receiverCheckpoint']['fluids'][-1]['tanks'][0]['amount'] -= 1
        else:
            observation['receiverCheckpoint']['worldTick'] -= 1
    generic[1].flow_mutation = mutate
    data = await observe(generic)
    assert data['observationVerified'] is False and data['status'] != 'observation_ready'
    assert data['reportsComplete'] and data['fullFlowVerified'] is False
    assert_no_replay_or_stop(generic[1])


async def test_real_command_with_complete_stage5_model_keeps_fluid_quantity_scope_and_diagnostic_gap(generic):
    options = parser().parse_args(['qa-debug-observe', CASE, '--session', 'session', '--world', 'test-world'])
    payload = await invoke(Adapter(generic[0]), options)
    assert payload['ok'] is True, payload['data'].get('error')
    data = payload['data']
    assert data['observationSummary']['fluidProducts'] == [{'name': 'oxygen', 'nbt': '', 'amount': '8253000'}]
    assert data['reportsComplete'] and data['reportCoherent'] and data['diagnosticsComplete'] is False
    assert data['diagnosticCoverage']['rowSummaries'][0]['outputLinesOmitted'] == 4
    assert data['finitePass'] is False and data['fullFlowVerified'] is False and data['stopActionSubmitted'] is False
    assert_no_replay_or_stop(generic[1])
