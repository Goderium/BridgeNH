"""Offline HTTP tests: whole fixed observation, physical evidence and failure boundaries."""

from copy import deepcopy
import json
from pathlib import Path
import time

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.qa_debug_tasks import run_qa_debug_observe
from minecraft_codex_bridge.qa_tasks import run_qa_test_once
from minecraft_codex_bridge.tools import TOOL_BY_NAME


pytestmark = pytest.mark.anyio
SAMPLE = json.loads((Path(__file__).parent / 'fixtures/r37-active-debug.json').read_text(encoding='utf-8'))
CASE = SAMPLE['flow']['caseId']
RUN_ID = SAMPLE['flow']['runId']
DIMENSION = SAMPLE['flow']['dimension']
PROVIDER = SAMPLE['flow']['providerClass']
SOURCE = 'tstqa_public_instance_adapter'
CONTRACT = 'tstqa-private-instance-v1'


def args(**extra):
    return {'selector': CASE, 'expectedSessionId': 'session', 'expectedWorld': 'test-world', **extra}


def route(value):
    value.update(ok=True, schemaVersion=1, source=SOURCE, nativeRunnerContract=CONTRACT,
        dimension=DIMENSION, providerClass=PROVIDER, sessionId='session', nativeRunOwned=False)
    value['world'] = {'name': 'test-world', 'folder': 'test-world', 'dimension': DIMENSION, 'providerClass': PROVIDER}
    return value


class DebugState:
    def __init__(self):
        self.calls, self.overrides = [], {}
        self.ran = self.protected = False
        self.counter = self.warmups = self.plan_reads = 0
        self.flow_mutation = None
        self.plan = deepcopy(SAMPLE['start']['fixtures'][0])
        route(self.plan)
        self.case = {'id': CASE, 'template': self.plan['template'], 'rotation': 0,
                     'caseType': 'infiniteDebug', 'parameterized': False, 'skippedAtDiscovery': False}
        self.statuses = []

    def flow(self):
        self.counter += 1
        value = deepcopy(SAMPLE['flow'])
        value.update(sessionId='session')
        observation = value['infiniteObservation']
        tick = observation['normalWorldTick'] + self.counter
        batches = observation['completedLogicalBatches'] + self.counter
        observation.update(normalWorldTick=tick, lastProductTick=tick, outputIncrementTicks=208 + self.counter,
                           completedLogicalBatches=batches, controllerTimer=215 + self.counter, outputTimer=215 + self.counter)
        products = value['infiniteRecipe']['itemProductsPerRecipe']
        observation['recoveredItems'] = [dict(row, count=row['count'] * batches) for row in products]
        observation['itemProductGrowth'] = [dict(row, outputIncrementTicks=208 + self.counter, lastProductTick=tick)
            for row in observation['recoveredItems']]
        observation['logicalInputDemandFromObservedProducts'] = [dict(row, count=row['count'] * batches)
            for row in value['infiniteRecipe']['logicalInputsPerRecipe']]
        observation['fluidProductGrowth'] = []
        observation['allFluidProductsRepeated'] = True
        checkpoint = deepcopy(value['snapshots'][0])
        checkpoint.update(stage='debug_observed', worldTick=tick)
        receiver = next(binding for binding in checkpoint['items'] if binding['binding'] == 'item_receiver')
        for channel, row in zip(receiver['channels'], observation['recoveredItems']):
            channel['bulk'] = deepcopy(row)
        observation['receiverCheckpoint'] = checkpoint
        if self.flow_mutation:
            self.flow_mutation(value)
        return value

    def status(self, *, ready=True):
        value = deepcopy(SAMPLE['status'])
        route(value)
        value.update(selector=CASE, runId=RUN_ID, nativeBatchActive=True, adapterActive=True, state='running',
                     observationReady=ready, adapterRunOwned=True)
        route(value['nativeReport'])
        value['nativeReport'].update(runId=RUN_ID, status='RUNNING', complete=False, exitCode=-1,
            selectedTests=1, passed=0, failed=0, timedOut=0, skipped=0, incomplete=1,
            requiredFailures=0, optionalFailures=0, infrastructureErrors=0, runnerOwned=True,
            durationSeconds=self.counter / 100)
        flow = self.flow()
        value['observations'] = [{'testId': CASE, 'observationReady': ready,
                                  'quantifiedOutputs': deepcopy(flow['infiniteObservation']) if ready else []}]
        return value, flow

    async def respond(self, request):
        body = json.loads(request.content)
        operation, arguments = body['operation'], body.get('args', {})
        self.calls.append(body)
        background = {'enabled': self.protected, 'helperInstalled': self.protected, 'pauseOnLostFocus': not self.protected}
        if operation == 'bridge.health':
            value = {'protocolVersion': 1, 'sessionId': 'session', 'profile': 'developer', 'background': background,
                'worldScanMinecraftAir': True, 'capabilities': [spec.operation for spec in TOOL_BY_NAME.values() if spec.operation]}
        elif operation == 'client.snapshot':
            value = {'sessionId': 'session', 'profile': 'developer', 'worldLoaded': True, 'paused': False,
                'world': {'worldName': 'test-world', 'worldFolder': 'test-world', 'dimension': DIMENSION, 'singleplayer': True},
                'player': {'serverFlight': {'available': True, 'source': 'integrated_server_tick', 'dimension': DIMENSION,
                    'ageMillis': 0, 'worldTick': 7000000}}, 'background': background}
        elif operation == 'client.background':
            self.protected = True
            value = {'enabled': True, 'helperInstalled': True, 'pauseOnLostFocus': False}
        elif operation == 'inventory.inspect':
            value = {'totalSlots': 41, 'truncated': False, 'cursorItem': None,
                     'slots': [{'index': index, 'item': None} for index in range(41)]}
        elif operation == 'qa.catalog':
            section = arguments['section']
            value = route({'section': section})
            if section == 'capabilities':
                value.update(available=True, normalWorldTicks=True, ownsHqaNativeRunner=False,
                    usesPublicInstanceLifecycle=True, privateDimension=True, fixturePlanAvailable=True, preClear=False,
                    minimumClearY=2, clearRequiresOwnedUnchangedFixture=True, active=False, mode='normal_world', turbo=False,
                    defaultOrigin=deepcopy(self.plan['allocationOrigin']), boundedStatusWait=True,
                    statusWaitPrivateOnly=True, maxStatusWaitMillis=8000)
                value['runnerConfig'] = {'source': SOURCE, 'mode': 'normal_world', 'turbo': False, 'active': False,
                    'maxSelectedTests': 10, 'defaultOrigin': deepcopy(self.plan['allocationOrigin'])}
            elif section == 'tests':
                value.update(entries=[deepcopy(self.case)], total=1, offset=0, hasMore=False)
            elif section == 'fixture_plan':
                self.plan_reads += 1
                value['publicFixturePlan'] = deepcopy(self.plan)
            else:
                value['flowEvidence'] = self.flow() if self.ran else {
                    'schemaVersion': 1, 'available': False, 'caseId': CASE, 'dimension': DIMENSION,
                    'providerClass': PROVIDER, 'sessionId': 'session'}
        elif operation in ('qa.command', 'qa.run'):
            self.ran = True
            value, _ = self.status(ready=False)
            value.update(startStatus='STARTED', fixtures=[deepcopy(self.plan)])
        elif operation == 'qa.status':
            ready = self.warmups == 0
            self.warmups = max(0, self.warmups - 1)
            value, _ = self.status(ready=ready)
            self.statuses.append(deepcopy(value))
        elif operation == 'qa.results':
            value, flow = self.status()
            rows = [{'id': CASE, 'caseType': 'infiniteDebug', 'status': 'RUNNING', 'detailsCoverage': 'requested',
                     'detailsTruncated': False, 'outputLines': [], 'outputTotal': 0, 'flowEvidence': flow}] if arguments['section'] == 'cases' else []
            offset, limit = arguments['offset'], arguments['limit']
            selected = rows[offset:offset + limit]
            more = offset + len(selected) < len(rows)
            value.update(section=arguments['section'], entries=selected, offset=offset, total=len(rows), limit=limit,
                         hasMore=more, truncated=more, nextOffset=offset + len(selected) if more else None)
        elif operation == 'world.scan':
            volume = self.plan['requestedVolume']
            value = {'source': 'integrated_server', 'dimension': DIMENSION, 'bounds': deepcopy(self.plan['reservedBounds']),
                'outOfWorld': 0, 'requestedVolume': volume, 'scannableVolume': volume, 'airSemantics': 'minecraft_air',
                'nonAtomic': True, 'complete': True, 'requestedRegionFullyObserved': True, 'blocks': [], 'nextCursor': None,
                'coverage': {'examined': volume, 'air': volume, 'nonAir': 0, 'unloaded': 0, 'loaded': volume, 'remaining': 0},
                'page': {'startOffset': 0, 'endOffsetExclusive': volume, 'examined': volume, 'air': volume,
                         'nonAir': 0, 'unloaded': 0, 'loaded': volume}}
        elif operation == 'world.tiles':
            value = {'source': 'integrated_server', 'dimension': DIMENSION, 'shape': 'sphere', 'radius': arguments['radius'],
                **{axis: arguments[axis] for axis in 'xyz'}, 'tiles': [], 'totalMatched': 0, 'totalMatchedIsExact': True,
                'candidateLimitReached': False, 'hasMore': False, 'truncated': False, 'nextOffset': None}
        elif operation == 'world.entities':
            center = {axis: arguments[axis] for axis in 'xyz'}
            h, v = arguments['radiusChunks'] * 16, arguments['verticalRadiusChunks'] * 16
            query = {prefix + axis: center[axis.lower()] + (-1 if prefix == 'min' else 1) * (h if axis in 'XZ' else v)
                + (1 if prefix == 'max' else 0) for prefix in ('min', 'max') for axis in 'XYZ'}
            value = {'source': 'integrated_server', 'dimension': DIMENSION, 'center': center,
                'bounds': {'radiusBlocks': h, 'verticalRadiusBlocks': v}, 'offset': arguments['offset'], 'entities': [],
                'loadedCandidates': 1, 'candidatesVisited': 1, 'hasMore': False, 'nextOffset': None,
                'boundsMode': 'intersection', 'queryBounds': query, 'unknownBoundsCandidates': 0,
                'coverage': {'source': 'Current loadedEntityList only; no chunk loading.'}}
        else:
            raise AssertionError(operation)
        mutation = self.overrides.get((operation, arguments.get('section')), self.overrides.get(operation))
        if mutation:
            mutation(value, arguments, self)
        return httpx.Response(200, json={'requestId': body['requestId'], 'ok': True, 'data': value})


@pytest.fixture
async def native():
    state = DebugState()
    client = BridgeClient(Configuration({'MCB_URL': 'http://127.0.0.1:25671', 'MCB_TOKEN': 'offline-debug-test-token'}),
                          timeout=.5, transport=httpx.MockTransport(state.respond))
    try:
        yield client, state
    finally:
        await client.aclose()


def operations(state, operation):
    return [value for value in state.calls if value['operation'] == operation]


async def observe(native, **extra):
    client, _ = native
    return await run_qa_debug_observe(client, args(**extra), anyio.Lock())


async def test_full_single_call_returns_real_multichannel_ready_without_finite_pass_or_stop(native):
    report = await observe(native, includeDetails=True)
    _, state = native
    assert report['status'] == 'observation_ready', report.get('error')
    assert report['observationVerified'] and report['runStillActive']
    assert not report['finitePass'] and not report['testPassed'] and not report['fullFlowVerified']
    assert not report['stopActionSubmitted'] and not report['stopDrainStable85Verified']
    assert report['reportsComplete'] and report['diagnosticsComplete'] and report['reportCoherent']
    assert report['scanCells'] == 2 * state.plan['requestedVolume']
    assert state.plan_reads == 2 and len(operations(state, 'world.tiles')) == len(operations(state, 'world.entities')) == 1
    assert len(operations(state, 'qa.command')) == 1
    assert all(value['args']['runId'] == RUN_ID and 'waitMillis' not in value['args']
        for operation in ('qa.status', 'qa.results') for value in operations(state, operation))
    assert not operations(state, 'qa.cancel') and [value['args']['command'] for value in operations(state, 'qa.command')] == ['run']
    summary = report['observationSummary']
    assert summary['actualReceiverChannels'] == 2 and summary['receiverComplete']
    assert len(summary['itemProducts']) == 2 and all(type(row['count']) is str for row in summary['itemProducts'])
    assert all(int(row['outputIncrementTicks']) >= 3 for row in summary['itemProductIncrementTicks'])
    assert not summary['logicalDemandIsPhysicalConsumptionMeasurement']
    assert state.statuses[-1]['nativeReport']['durationSeconds'] != state.statuses[0]['nativeReport']['durationSeconds']


async def test_warmup_is_bounded_and_stays_in_one_call(native):
    native[1].warmups = 1
    report = await observe(native)
    assert report['status'] == 'observation_ready', report.get('error')
    assert report['polling']['statusPolls'] == 2 and report['runSubmissions'] == 1


async def test_finite_entry_still_refuses_infinite_before_any_action(native):
    report = await run_qa_test_once(native[0], args(), anyio.Lock())
    assert report['error']['code'] == 'qa_infinite_debug_outside_finite_scope'
    assert report['runSubmissions'] == 0 and not operations(native[1], 'qa.command')


@pytest.mark.parametrize('change', [
    {'requireFullFlow': True}, {'timeout_ms': 10001}, {'maxRequests': True}, {'origin': {'x': 1, 'y': 3}}, {'selector': '*'},
])
async def test_invalid_fixed_interface_never_reads_or_submits(native, change):
    report = await observe(native, **change)
    assert report['error']['code'] == 'INVALID_ARGUMENTS' and not native[1].calls


@pytest.mark.parametrize('operation,mutation', [
    (('qa.catalog', 'tests'), lambda value, args, state: value['entries'][0].update(caseType='finite')),
    (('qa.catalog', 'tests'), lambda value, args, state: value['entries'][0].update(parameterized=True)),
    (('qa.catalog', 'capabilities'), lambda value, args, state: value.update(normalWorldTicks=False)),
    (('qa.catalog', 'capabilities'), lambda value, args, state: value.update(sessionId='other')),
    (('qa.catalog', 'fixture_plan'), lambda value, args, state: value['publicFixturePlan'].update(complete=False)),
    ('inventory.inspect', lambda value, args, state: value.update(truncated=True)),
    ('world.tiles', lambda value, args, state: value.update(candidateLimitReached=True)),
    ('world.entities', lambda value, args, state: value.update(unknownBoundsCandidates=1)),
    ('client.snapshot', lambda value, args, state: value.update(worldLoaded=False)),
])
async def test_original_preflight_refusals_happen_before_submission(native, operation, mutation):
    native[1].overrides[operation] = mutation
    report = await observe(native)
    assert report['status'] in ('failed', 'unsupported') and report['runSubmissions'] == 0
    assert not operations(native[1], 'qa.command')


@pytest.mark.parametrize('mutation', [
    lambda value: value.update(runId='f869ca7d-a972-4709-a790-eb15727ea07e'),
    lambda value: value.update(schemaVersion=True),
    lambda value: value.update(sessionId='other'),
    lambda value: value.update(origin={'x': 0, 'y': 4, 'z': 8}),
    lambda value: value.update(cleanupCompleted=True),
    lambda value: value['infiniteObservation'].update(ordinaryStopRequested=True),
    lambda value: value['infiniteObservation'].pop('receiverCheckpoint'),
    lambda value: value['infiniteObservation']['receiverCheckpoint'].update(worldTick=0),
    lambda value: value['infiniteObservation']['itemProductGrowth'][1].update(outputIncrementTicks=2),
    lambda value: value['infiniteObservation'].update(itemProductGrowth=value['infiniteObservation']['itemProductGrowth'][:1]),
    lambda value: value['infiniteObservation']['recoveredItems'][1].update(count=1),
    lambda value: value['infiniteObservation'].update(logicalDemandIsPhysicalConsumptionMeasurement=True),
    lambda value: value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels'][1].update(complete=False),
    lambda value: value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels'][1]['bulk'].update(count=1),
    lambda value: value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels'][1].update(voidOverflow=True),
    lambda value: value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels'][1]['displayProjection'].update(count=1,registryName='minecraft:stone',metadata=0,nbt=''),
    lambda value: value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels'][1].update(capacity=9000000),
])
async def test_ready_flag_cannot_replace_complete_exact_actual_product_evidence(native, mutation):
    native[1].flow_mutation = mutation
    report = await observe(native)
    assert report['status'] in ('failed', 'unsupported') and not report['observationVerified']
    assert not report['finitePass'] and report['runSubmissions'] == 1
    assert len(operations(native[1], 'qa.command')) == 1 and not operations(native[1], 'qa.cancel')


@pytest.mark.parametrize('mutation', [
    lambda value: value.update(runId='f869ca7d-a972-4709-a790-eb15727ea07e'),
    lambda value: value.update(freshSinceStart=False),
    lambda value: value.update(nativeBatchActive=False),
    lambda value: value.update(adapterRunOwned=False),
    lambda value: value.update(selectedIds=[CASE, 'other']),
    lambda value: value.update(observationReady='true'),
    lambda value: value['nativeReport'].update(failed=1),
])
async def test_same_run_active_ready_status_is_guarded_without_replay(native, mutation):
    native[1].overrides['qa.status'] = lambda value, arguments, state: mutation(value)
    report = await observe(native)
    assert report['status'] == 'failed' and not report['observationVerified']
    assert report['runSubmissions'] == 1 and not operations(native[1], 'qa.cancel')


@pytest.mark.parametrize('fault', ['session', 'world', 'profile', 'background', 'dimension'])
async def test_after_submission_live_snapshot_drift_stops_later_actions(native, fault):
    def drift(value, arguments, state):
        if not state.ran:
            return
        if fault == 'session': value['sessionId'] = 'other'
        elif fault == 'world': value['world']['worldName'] = value['world']['worldFolder'] = 'other'
        elif fault == 'profile': value['profile'] = 'player'
        elif fault == 'background': value['background']['enabled'] = False
        else: value['world']['dimension'] = 0
    native[1].overrides['client.snapshot'] = drift
    report = await observe(native)
    assert report['status'] == 'failed' and not report['observationVerified'] and report['runSubmissions'] == 1


async def test_full_after_scan_cannot_accept_an_unloaded_suffix(native):
    def unload(value, arguments, state):
        if not state.ran: return
        for container in ('coverage', 'page'):
            value[container]['air'] -= 1
            value[container]['loaded'] -= 1
            value[container]['unloaded'] = 1
        value['requestedRegionFullyObserved'] = False
    native[1].overrides['world.scan'] = unload
    report = await observe(native)
    assert report['error']['code'] == 'qa_space_unloaded' and not report['observationVerified']


async def test_elapsed_queue_budget_includes_entry_and_never_submits(native):
    lock = anyio.Lock()
    ready, release = anyio.Event(), anyio.Event()
    async def holder():
        async with lock:
            ready.set()
            await release.wait()
    async with anyio.create_task_group() as group:
        group.start_soon(holder)
        await ready.wait()
        report = await run_qa_debug_observe(native[0], args(timeout_ms=100), lock, started_at=time.monotonic() - .2)
        release.set()
    assert report['error']['code'] == 'qa_deadline' and not native[1].calls


@pytest.mark.parametrize('mutation', [
    lambda value, arguments, state: value.update(total=2),
    lambda value, arguments, state: value.update(nextOffset=1),
    lambda value, arguments, state: value['entries'][0].update(flowEvidenceTruncated=True),
])
async def test_complete_case_coverage_is_required(native, mutation):
    native[1].overrides[('qa.results', 'cases')] = mutation
    report = await observe(native)
    assert report['status'] == 'failed' and not report['observationVerified']


async def test_changed_final_evidence_uuid_is_refused(native):
    def change(value, arguments, state):
        if state.ran:
            value['flowEvidence']['evidenceId'] = 'f869ca7d-a972-4709-a790-eb15727ea07e'
    native[1].overrides[('qa.catalog', 'flow_evidence')] = change
    report = await observe(native)
    assert report['error']['code'] == 'qa_flow_stale' and not report['observationVerified']


def add_fluid(value):
    recipe, observation = value['infiniteRecipe'], value['infiniteObservation']
    recipe['fluidProductsPerRecipe'] = [{'name': 'water', 'amount': 1000, 'nbt': ''}]
    recipe['logicalFluidsPerRecipe'] = [{'name': 'distilledwater', 'amount': 500, 'nbt': ''}]
    batches = observation['completedLogicalBatches']
    observation['recoveredFluids'] = [{'name': 'water', 'amount': batches * 1000, 'nbt': ''}]
    observation['logicalFluidDemandFromObservedProducts'] = [{'name': 'distilledwater', 'amount': batches * 500, 'nbt': ''}]
    observation['fluidProductGrowth'] = [dict(observation['recoveredFluids'][0], outputIncrementTicks=208,
                                             lastProductTick=observation['normalWorldTick'])]
    actual = observation['receiverCheckpoint']['fluids'][-1]['tanks'][0]
    actual.update(observation['recoveredFluids'][0])


async def test_each_actual_fluid_product_has_its_own_growth_and_physical_quantity(native):
    native[1].flow_mutation = add_fluid
    report = await observe(native)
    assert report['status'] == 'observation_ready', report.get('error')
    assert report['observationSummary']['fluidProducts'][0]['name'] == 'water'
    assert report['observationSummary']['fluidProductIncrementTicks'][0]['outputIncrementTicks'] == '208'
    assert not report['observationSummary']['logicalDemandIsPhysicalConsumptionMeasurement']


@pytest.mark.parametrize('fault', ['missing', 'one_increment', 'wrong_identity', 'receiver_missing'])
async def test_global_item_increments_never_substitute_for_every_fluid_product(native, fault):
    def fail(value):
        add_fluid(value)
        observation = value['infiniteObservation']
        if fault == 'missing': observation.pop('fluidProductGrowth')
        elif fault == 'one_increment': observation['fluidProductGrowth'][0]['outputIncrementTicks'] = 1
        elif fault == 'wrong_identity': observation['fluidProductGrowth'][0]['name'] = 'lava'
        else: observation['receiverCheckpoint']['fluids'][-1]['tanks'][0]['amount'] = 0
    native[1].flow_mutation = fail
    report = await observe(native)
    assert report['status'] in ('failed', 'unsupported') and not report['observationVerified']


async def test_current_checkpoint_display_projection_is_excluded_from_exact_bulk_quantity(native):
    def display(value):
        for channel in value['infiniteObservation']['receiverCheckpoint']['items'][1]['channels']:
            template = channel['configuredType']
            channel['displayProjection'].update(template, count=64)
    native[1].flow_mutation = display
    report = await observe(native)
    assert report['status'] == 'observation_ready', report.get('error')
    assert int(report['observationSummary']['itemProducts'][0]['count']) % 64 != 0


async def test_final_active_flow_tick_and_quantities_cannot_decrease(native):
    def reverse(value, arguments, state):
        if state.ran:
            observation = value['flowEvidence']['infiniteObservation']
            observation['normalWorldTick'] -= 100
    native[1].overrides[('qa.catalog', 'flow_evidence')] = reverse
    report = await observe(native)
    assert report['status'] == 'failed' and not report['observationVerified']


async def test_pause_menu_is_refused_before_submission_without_closing_or_replaying(native):
    def pause(value, arguments, state):
        value.update(paused=True, screenOpen=True, screenKind='pause_menu')
    native[1].overrides['client.snapshot'] = pause
    report = await observe(native)
    assert report['runSubmissions'] == 0 and report['status'] == 'failed'
    assert not operations(native[1], 'gui.close') and not operations(native[1], 'qa.command')


async def test_exact_identity_summary_cannot_be_silently_truncated_into_verified_success(native):
    def large(value):
        nbt = 'x' * 20000
        value['infiniteRecipe']['itemProductsPerRecipe'][0]['nbt'] = nbt
        observation = value['infiniteObservation']
        observation['recoveredItems'][0]['nbt'] = nbt
        observation['itemProductGrowth'][0]['nbt'] = nbt
        initial_channel = value['snapshots'][0]['items'][1]['channels'][0]
        initial_channel['configuredType']['nbt'] = nbt
        current_channel = observation['receiverCheckpoint']['items'][1]['channels'][0]
        current_channel['configuredType']['nbt'] = current_channel['bulk']['nbt'] = nbt
    native[1].flow_mutation = large
    report = await observe(native, max_return_bytes=16384)
    assert report['error']['code'] == 'qa_debug_summary_budget'
    assert not report['observationVerified'] and not report['finitePass'] and not report['completeWithin10s']
    assert report['runStillActive'] is True and report['stopPending'] is True
