"""One fixed private infinite-debug observation; stopping has a separate lifetime."""

from __future__ import annotations

from copy import deepcopy
import time
from uuid import UUID

import anyio
import jsonschema

from .bridge import BridgeError
from .conveniences import json_size
from .qa_tasks import (
    COUNT_FIELDS, LONG_MAX, PRIVATE_SOURCE, QA_TEST_ONCE_SCHEMA, WHOLE_BUDGET_MS,
    _QaFailure, _QaTask, _checkpoint, _identity, _integer, _inventory, _read,
    _require, _resource_quantities,
)


QA_DEBUG_OBSERVE_SCHEMA = deepcopy(QA_TEST_ONCE_SCHEMA)
QA_DEBUG_OBSERVE_SCHEMA['properties'].pop('requireFullFlow')
QA_DEBUG_OBSERVE_SCHEMA['properties']['timeout_ms']['maximum'] = WHOLE_BUDGET_MS


def _layout(checkpoint):
    """Only physical binding identity/capacity, never changing contents."""
    result = {}
    for domain in ('items', 'fluids'):
        result[domain] = {}
        for name, binding in checkpoint[domain]['bindings'].items():
            identity = {key: binding[key] for key in ('position', 'total', 'representation', 'layout') if key in binding}
            if domain == 'fluids':
                identity['tankCapacities'] = [row['capacity'] for row in binding['entries']]
            result[domain][name] = identity
    return result


def _resources(entries, *, fluid=False, zero=False):
    _require(isinstance(entries, list) and len(entries) <= 128, 'qa_debug_products_incomplete',
             'Debug product and logical-demand arrays must be complete and bounded.')
    key = 'amount' if fluid else 'count'
    result = {}
    for row in entries:
        identity = _identity(row, fluid=fluid)
        _require(identity not in result and _integer(row.get(key), 0 if zero else 1, LONG_MAX),
                 'qa_debug_products_incomplete', 'Each exact debug resource identity needs one typed signed-long quantity.')
        result[identity] = row[key]
    return result


class _DebugObservationTask(_QaTask):
    def __init__(self, *args):
        super().__init__(*args)
        self.observation_summary = None
        self.observation_evidence = None
        self.evidence_id = None
        self.receiver_layout = None
        self.last_quantities = None
        self.ready_quantified = None

    def validate_case_definition(self):
        _require(self.dimension != 0 and self.case.get('caseType') == 'infiniteDebug',
                 'qa_debug_private_case_required', 'This observation requires one exact private infiniteDebug case.', unsupported=True)
        _require(isinstance(self.case.get('template'), str) and self.case.get('parameterized') is False
                 and self.case.get('skippedAtDiscovery') is False and type(self.case.get('rotation')) is int,
                 'qa_single_definition_required', 'Parameterized or skipped debug definitions cannot be submitted.', unsupported=True)

    def active_status(self, value, *, ready=False):
        self.validate_run_ticket({**value, 'selector': value.get('selector', self.args['selector'])})
        _require(value.get('runId') == self.run_id() and value.get('freshSinceStart') is True
                 and value.get('nativeBatchActive') is True and value.get('adapterActive') is True
                 and value.get('adapterRunOwned') is True and value.get('owned') is False
                 and value.get('selectionMatches') is True and value.get('state') == 'running',
                 'qa_debug_active_run_changed', 'Observation must retain this exact fresh active owned adapter run; native-global ownership stays false.')
        report = value.get('nativeReport')
        _require(isinstance(report, dict), 'qa_debug_report_incomplete', 'The current complete native report is required.')
        self.qa_context(report)
        _require(report.get('runId') == self.run_id() and report.get('status') == 'RUNNING'
                 and report.get('complete') is False and report.get('runnerOwned') is True
                 and report.get('nativeRunOwned') is False and type(report.get('exitCode')) is int and report['exitCode'] == -1
                 and type(report.get('selectedTests')) is int and report['selectedTests'] == 1
                 and type(report.get('passed')) is int and report['passed'] == 0
                 and all(type(report.get(key)) is int and report[key] == (1 if key == 'incomplete' else 0) for key in COUNT_FIELDS),
                 'qa_debug_native_run_failed', 'The ongoing singleton report must be running, with no failed/skipped/infrastructure outcomes; incomplete is the active case.')
        observations = value.get('observations')
        _require(type(value.get('observationReady')) is bool and isinstance(observations, list) and len(observations) == 1
                 and isinstance(observations[0], dict) and observations[0].get('testId') == self.args['selector']
                 and type(observations[0].get('observationReady')) is bool
                 and observations[0]['observationReady'] is value['observationReady'],
                 'qa_debug_ready_unverified', 'Ready must describe only the exact requested case in this actual run.')
        if ready:
            _require(value['observationReady'], 'qa_debug_ready_unverified', 'The exact active run is not observation-ready.')
            quantified = observations[0].get('quantifiedOutputs')
            _require(isinstance(quantified, dict), 'qa_debug_quantities_unavailable', 'Ready requires complete actual quantified output evidence.', unsupported=True)
            return quantified
        return value['observationReady']

    async def execute(self):
        scan_pages = self.client.operation_counts['world.scan']
        reserve = max(32, 26 + 4 * scan_pages)
        remaining_requests = self.client.maximum - self.client.calls
        slots = (remaining_requests - reserve - 10) // 2
        _require(slots >= 2, 'qa_request_budget_insufficient', 'Ready polling cannot consume the complete report and after-observation reserve.')
        interval = min(250, max(100, self.remaining() // max(1, slots - 2)))
        self.polling = {'reservedAfterRequests': reserve, 'beforeScanPages': scan_pages,
            'remainingRequestsBeforeRun': remaining_requests, 'setupRequestAllowance': 10,
            'statusPollRequestCost': 2, 'statusPollAllowance': slots, 'statusPolls': 0, 'interval_ms': interval}
        submission = {'id': 'run', 'kind': 'action', 'tool': 'mcb_qa_command',
            'args': {'expectedWorld': self.args['expectedWorld'], 'command': 'run', 'args': [self.args['selector']]}}
        if self.requested_origin is not None:
            submission['tool'] = 'mcb_qa_run'
            submission['args'] = {'expectedWorld': self.args['expectedWorld'], 'selector': self.args['selector'],
                                  'origin': dict(self.requested_origin)}
        before = self.client.operation_counts['qa.status']
        self.client.request_reserve = reserve
        try:
            await self.workflow('qa_debug_submit_exact_run', [submission])
            self.phase = 'qa_debug_wait_ready'
            for attempt in range(slots):
                # The completion future only wakes after END. Infinite ready
                # polling intentionally uses the original immediate status read.
                value = await self.read('qa.status', 'mcb_qa_status', {'runId': self.run_id()})
                self.phase = 'qa_debug_wait_ready'
                if self.active_status(value):
                    self.ready_quantified = self.active_status(value, ready=True)
                    self.terminal = dict(value)
                    return
                if attempt + 1 < slots:
                    await anyio.sleep(interval / 1000)
            raise _QaFailure('qa_request_budget_reserved', 'The finite ready allowance ended; full reports and after-read remain reserved.')
        finally:
            self.client.request_reserve = 0
            self.polling['statusPolls'] = self.client.operation_counts['qa.status'] - before

    async def pages(self, section, *, diagnostic=False):
        # Active report time advances. Preserve every original pagination,
        # coverage, ticket and budget check while validating active report state.
        run_id, offset, offsets, entries, total = self.run_id(), 0, set(), [], None
        while True:
            _require(len(self.client.result_pages) < self.args.get('maxReportPages', 32), 'qa_report_page_budget', 'The complete report-page allowance was exhausted.')
            value = await self.read('qa.results', 'mcb_qa_results', {'runId': run_id, 'section': section,
                'offset': offset, 'limit': self.args.get('reportPageSize', 8), 'includeDetails': True}, section=section, diagnostic=diagnostic)
            self.active_status(value)
            rows = value.get('entries')
            _require(value.get('section') == section and type(value.get('offset')) is int and value['offset'] == offset
                     and _integer(value.get('total')) and isinstance(rows, list) and type(value.get('hasMore')) is bool
                     and value.get('truncated') is value['hasMore'],
                     'qa_result_page_invalid', 'Every active page must retain its exact typed ticket, complete coverage and returned offset.')
            total = value['total'] if total is None else total
            _require(total == value['total'] and len(entries) + len(rows) <= total, 'qa_report_changed', 'The active case/issue page count changed or exceeded its complete suffix.')
            for row in rows:
                _require(isinstance(row, dict), 'qa_result_entry_invalid', 'Every case/issue row must remain an object.')
                self.diagnostics_complete &= (row.get('detailsCoverage') == 'requested' and row.get('detailsTruncated') is False
                    and all(flag is False for key, flag in row.items() if key.endswith('Truncated'))
                    and ('outputTotal' not in row or isinstance(row.get('outputLines'), list)
                         and type(row['outputTotal']) is int and row['outputTotal'] == len(row['outputLines'])))
            entries.extend(rows)
            if section == 'cases':
                self.cases = list(entries)
            else:
                self.issues = list(entries)
            if not value['hasMore']:
                _require(value.get('nextOffset') is None and len(entries) == total, 'qa_result_suffix_missing', 'Every complete active result suffix is required.')
                return entries
            following = value.get('nextOffset')
            _require(_integer(following, offset + 1) and following == offset + len(rows) and following not in offsets,
                     'qa_result_offset_invalid', 'Consume only the returned new offset.')
            offsets.add(following)
            offset = following

    def verify_observation(self, evidence, *, lower_bound=None):
        _require(isinstance(evidence, dict) and evidence.get('available') is True,
                 'qa_debug_flow_unavailable', 'Fresh complete public ongoing evidence is required.', unsupported=True)
        fresh_id = evidence.get('evidenceId')
        try:
            canonical = str(UUID(fresh_id))
        except (ValueError, AttributeError, TypeError):
            canonical = None
        _require(canonical is not None and fresh_id == canonical and fresh_id != self.flow_before.get('evidenceId')
                 and (self.evidence_id is None or fresh_id == self.evidence_id),
                 'qa_flow_stale', 'Every observation must retain one fresh canonical flow UUID distinct from the before-record.')
        _require(type(evidence.get('schemaVersion')) is int and evidence['schemaVersion'] == 1
                 and evidence.get('caseId') == self.args['selector'] and evidence.get('caseType') == 'infiniteDebug'
                 and type(evidence.get('dimension')) is int and evidence['dimension'] == self.dimension
                 and evidence.get('providerClass') == self.provider_class and evidence.get('sessionId') == self.args['expectedSessionId']
                 and evidence.get('runId') == self.run_id() and evidence.get('source') == PRIVATE_SOURCE
                 and evidence.get('adapterRunOwned') is True and evidence.get('origin') == self.plan['allocationOrigin']
                 and evidence.get('executionMode') == 'normal_world' and evidence.get('retrievalScope') == 'api_receiver'
                 and evidence.get('methodEntryThroughRecoveryIncluded') is True and evidence.get('fixturePreparationIncluded') is False
                 and evidence.get('state') == 'running' and evidence.get('cleanupCompleted') is False
                 and evidence.get('observationReady') is True and 'cleanupFailure' not in evidence,
                 'qa_debug_flow_context_invalid', 'Ongoing evidence must bind the same actual private run, case, flow, origin, session and normal-tick receiver scope.')
        declaration, observation = evidence.get('infiniteRecipe'), evidence.get('infiniteObservation')
        _require(isinstance(declaration, dict) and isinstance(observation, dict)
                 and declaration.get('normalTickProductionRequired') is True
                 and type(declaration.get('minimumDistinctOutputIncrementTicks')) is int and declaration['minimumDistinctOutputIncrementTicks'] == 3
                 and type(declaration.get('minimumIncrementTicksPerItemProduct')) is int and declaration['minimumIncrementTicksPerItemProduct'] in (0, 3)
                 and declaration.get('batchModeEnabled') is False and observation.get('ordinaryStopRequested') is False
                 and observation.get('logicalDemandIsPhysicalConsumptionMeasurement') is False,
                 'qa_debug_scope_invalid', 'Debug observation must retain ordinary unbatched production, fixed three-increment scope and explicitly logical consumption.')
        tick = observation.get('normalWorldTick')
        _require(_integer(tick, 0, LONG_MAX) and _integer(observation.get('outputIncrementTicks'), 3, LONG_MAX)
                 and _integer(observation.get('firstProductTick'), 0, tick) and _integer(observation.get('lastProductTick'), observation['firstProductTick'] + 2, tick)
                 and observation.get('workingAllowed') is True and observation.get('active') is True
                 and all(_integer(observation.get(key), 0, LONG_MAX) for key in ('controllerTimerAtEntry', 'outputTimerAtEntry'))
                 and _integer(observation.get('controllerTimer'), observation['controllerTimerAtEntry'] + 1, LONG_MAX)
                 and _integer(observation.get('outputTimer'), observation['outputTimerAtEntry'] + 1, LONG_MAX),
                 'qa_debug_ticks_unverified', 'All products need distinct actual normal ticks with advancing real controller/output timers and ongoing work.')
        stages = evidence.get('stages')
        _require(isinstance(stages, list) and 1 <= len(stages) <= 32, 'qa_debug_ticks_unverified', 'Complete bounded actual lifecycle stages are required.')
        names, previous_time, previous_tick = [], -1, -1
        for stage in stages:
            _require(isinstance(stage, dict) and isinstance(stage.get('name'), str)
                     and _integer(stage.get('elapsedNanos'), previous_time, LONG_MAX)
                     and _integer(stage.get('worldTick'), previous_tick, tick), 'qa_debug_ticks_unverified', 'Actual stages must retain ordered normal ticks and times.')
            names.append(stage['name'])
            previous_time, previous_tick = stage['elapsedNanos'], stage['worldTick']
        required_stages = ('method_entry', 'receiver_prepared', 'standard_debug_sources_configured', 'infinite_observation_ready')
        _require(all(names.count(name) == 1 for name in required_stages)
                 and [names.index(name) for name in required_stages] == sorted(names.index(name) for name in required_stages),
                 'qa_debug_ticks_unverified', 'The real startup, supply and ready stages must occur once in their lifecycle order.')
        products = {'items': _resources(declaration.get('itemProductsPerRecipe')),
                    'fluids': _resources(declaration.get('fluidProductsPerRecipe'), fluid=True)}
        _require(bool(products['items'] or products['fluids']), 'qa_debug_products_incomplete', 'Every actual public product identity must be declared.')
        recovered = {'items': _resources(observation.get('recoveredItems')),
                     'fluids': _resources(observation.get('recoveredFluids'), fluid=True)}
        batches = observation.get('completedLogicalBatches')
        _require(_integer(batches, 3, LONG_MAX), 'qa_debug_quantities_unverified', 'At least three actual recovered logical batches are required.')
        growth = {}
        for domain, fluid, field in (('items', False, 'itemProductGrowth'), ('fluids', True, 'fluidProductGrowth')):
            rows = observation.get(field)
            _require(isinstance(rows, list) and len(rows) == len(products[domain]), 'qa_debug_product_growth_unavailable',
                     'Every item and fluid product needs its own complete normal-tick growth rows.', unsupported=True)
            growth[domain] = {}
            for row in rows:
                identity = _identity(row, fluid=fluid)
                _require(identity in products[domain] and identity not in growth[domain]
                         and _integer(row.get('outputIncrementTicks'), 3, LONG_MAX)
                         and _integer(row.get('lastProductTick'), observation['firstProductTick'], tick)
                         and row.get('amount' if fluid else 'count') == recovered[domain].get(identity)
                         and type(row.get('amount' if fluid else 'count')) is int,
                         'qa_debug_product_growth_unverified', 'Each exact product must independently grow on at least three actual ticks with its complete recovered quantity.')
                growth[domain][identity] = row['outputIncrementTicks']
            _require(products[domain].keys() == recovered[domain].keys() == growth[domain].keys()
                     and all(amount * batches <= LONG_MAX and recovered[domain][identity] == amount * batches for identity, amount in products[domain].items()),
                     'qa_debug_quantities_unverified', 'Recovered products must exactly cover the declared identities and complete public recipe multiples.')
        snapshots = evidence.get('snapshots')
        _require(isinstance(snapshots, list) and len(snapshots) <= 12, 'qa_flow_checkpoint_invalid', 'The complete startup checkpoint array is required.')
        initial = [value for value in snapshots if isinstance(value, dict) and value.get('stage') == 'debug_supplied']
        _require(len(initial) == 1, 'qa_flow_checkpoint_invalid', 'One complete actual configured startup baseline is required.')
        baseline = _checkpoint(initial[0], self.plan['reservedBounds'])
        _require(all(not baseline[domain][part] for domain in ('items', 'fluids') for part in ('source', 'receiver')),
                 'qa_debug_receiver_baseline_dirty', 'The actual configured sources/receivers must start physically empty.')
        checkpoint = observation.get('receiverCheckpoint')
        _require(isinstance(checkpoint, dict) and checkpoint.get('stage') == 'debug_observed'
                 and type(checkpoint.get('worldTick')) is int and checkpoint['worldTick'] == tick,
                 'qa_debug_receiver_checkpoint_unavailable', 'A current complete actual receiver checkpoint from this same normal tick is required.', unsupported=True)
        actual = _checkpoint(checkpoint, self.plan['reservedBounds'])
        layout = _layout(actual)
        _require(layout == _layout(baseline) and (self.receiver_layout is None or layout == self.receiver_layout),
                 'qa_debug_receiver_changed', 'Every actual channel, position, configured identity, capacity and tank layout must retain the configured baseline.')
        item_receiver = actual['items']['bindings']['item_receiver']
        _require(item_receiver.get('representation') == 'digital_chest_channels'
                 and {entry['configuredType'] for entry in item_receiver['layout'] if entry['configuredType'] is not None} == products['items'].keys(),
                 'qa_debug_channels_incomplete', 'The complete actual physical digital channels must cover every exact item product once.')
        for domain in ('items', 'fluids'):
            _require(actual[domain]['receiver'] == recovered[domain]
                     and actual[domain]['source'].keys() <= products[domain].keys(),
                     'qa_debug_receiver_quantities_invalid', 'Current complete physical receiver quantities must equal all recovered products, with no foreign source identity.')
        logical = {}
        for domain, fluid, declaration_key, observed_key in (
            ('items', False, 'logicalInputsPerRecipe', 'logicalInputDemandFromObservedProducts'),
            ('fluids', True, 'logicalFluidsPerRecipe', 'logicalFluidDemandFromObservedProducts')):
            templates = _resources(declaration.get(declaration_key), fluid=fluid, zero=True)
            logical[domain] = _resources(observation.get(observed_key), fluid=fluid, zero=True)
            _require(templates.keys() == logical[domain].keys()
                     and all(amount * batches <= LONG_MAX and logical[domain][identity] == amount * batches for identity, amount in templates.items()),
                     'qa_debug_logical_demand_invalid', 'Logical demand must equal public recipe templates times observed complete batches; it is never physical consumption.')
        if lower_bound is not None:
            _require(_integer(lower_bound.get('normalWorldTick'), 0, tick), 'qa_debug_observation_reversed', 'The fresh flow cannot precede its same-run guarded status.')
            for domain, fluid, field in (('items', False, 'recoveredItems'), ('fluids', True, 'recoveredFluids')):
                previous = _resources(lower_bound.get(field), fluid=fluid)
                _require(previous.keys() == recovered[domain].keys() and all(recovered[domain][identity] >= amount for identity, amount in previous.items()),
                         'qa_debug_observation_reversed', 'Same-run actual product quantities cannot decrease between protected reads.')
        if self.last_quantities is not None:
            old_tick, previous = self.last_quantities
            _require(tick >= old_tick and all(previous[domain].keys() == recovered[domain].keys()
                     and all(recovered[domain][identity] >= amount for identity, amount in previous[domain].items()) for domain in ('items', 'fluids')),
                     'qa_debug_observation_reversed', 'The same evidence witness cannot move backward in tick or product quantity.')
        self.evidence_id, self.receiver_layout = fresh_id, layout
        self.last_quantities = tick, recovered
        self.observation_evidence = evidence
        channel_quantities = []
        for channel in item_receiver['entries']:
            quantity = channel['bulk']['count'] + sum(row['count'] for row in channel['physicalSlots'])
            channel_quantities.append({'channel': channel['channel'], 'position': dict(channel['position']),
                'configuredItem': dict(channel['configuredType']) if channel['configuredType'] is not None else None,
                'physicalCount': str(quantity), 'bulkCapacity': str(channel['capacity']),
                'displayProjectionExcludedFromAccounting': True})
        self.observation_summary = {'scope': 'ongoing_private_debug_production_api_receiver', 'evidenceId': fresh_id,
            'observedWorldTick': str(tick), 'normalWorldTicks': True, 'completedLogicalBatches': str(batches),
            'itemProducts': _resource_quantities(recovered['items']), 'fluidProducts': _resource_quantities(recovered['fluids'], fluid=True),
            'itemProductIncrementTicks': [dict(zip(('registryName', 'metadata', 'nbt'), identity), outputIncrementTicks=str(amount)) for identity, amount in sorted(growth['items'].items())],
            'fluidProductIncrementTicks': [dict(zip(('name', 'nbt'), identity), outputIncrementTicks=str(amount)) for identity, amount in sorted(growth['fluids'].items())],
            'actualReceiverChannels': item_receiver['total'], 'receiverChannels': channel_quantities, 'receiverComplete': True,
            'logicalItemDemand': _resource_quantities(logical['items']), 'logicalFluidDemand': _resource_quantities(logical['fluids'], fluid=True),
            'logicalDemandIsPhysicalConsumptionMeasurement': False}

    async def after(self):
        # Complete case/issue pages and physical evidence are acceptance inputs.
        # Bounded native text logs retain the finite task's separate coverage flag.
        _require(self.reports_complete and len(self.cases) == 1
                 and self.cases[0].get('id') == self.args['selector'] and self.cases[0].get('caseType') == 'infiniteDebug'
                 and self.cases[0].get('status') == 'RUNNING' and not self.issues,
                 'qa_debug_report_incomplete', 'The complete exact active case and all empty infrastructure issue pages are required.')
        _require(all(flag is False for key, flag in self.cases[0].items()
                     if key.endswith('Truncated') and key != 'detailsTruncated'),
                 'qa_debug_report_incomplete', 'Only the known bounded text detailsTruncated flag is separate from physical acceptance; other truncated evidence is refused.')
        self.verify_observation(self.cases[0].get('flowEvidence'), lower_bound=self.ready_quantified)
        await self.workflow('qa_debug_after_read', [_read('snapshot', 'mcb_snapshot'), _read('player', 'mcb_inventory')])
        self.snapshot(self.client.latest[('client.snapshot', '')])
        _inventory(self.client.latest[('inventory.inspect', '')])
        self.after_scan = await self.scan(self.plan['reservedBounds'], require_air=False)
        await self.workflow('qa_debug_final_observation', [
            _read('status', 'mcb_qa_status', {'runId': self.run_id()}),
            _read('flow', 'mcb_qa_catalog', {'section': 'flow_evidence', 'query': self.args['selector']})])
        status = self.client.latest[('qa.status', '')]
        lower_bound = self.active_status(status, ready=True)
        self.verify_observation(self.client.latest[('qa.catalog', 'flow_evidence')].get('flowEvidence'), lower_bound=lower_bound)
        self.terminal = dict(status)
        self.report_coherent = True

    def text_diagnostic_coverage(self):
        rows = [('cases', row) for row in self.cases] + [('issues', row) for row in self.issues]
        summaries, truncated = [], False
        for section, row in rows:
            lines = row.get('outputLines')
            returned = len(lines) if isinstance(lines, list) else None
            total = row.get('outputTotal')
            total = total if type(total) is int and total >= 0 else None
            omitted = total - returned if total is not None and returned is not None and total >= returned else None
            truncated |= row.get('detailsTruncated') is True or omitted is not None and omitted > 0
            if len(summaries) < 8:
                summaries.append({'section': section, 'id': str(row.get('id', ''))[:256],
                    'detailsCoverage': str(row.get('detailsCoverage', ''))[:64],
                    'detailsTruncated': row.get('detailsTruncated') if type(row.get('detailsTruncated')) is bool else None,
                    'outputLinesReturned': returned, 'outputTotal': total, 'outputLinesOmitted': omitted})
        return {'scope': 'bounded_native_case_and_issue_text',
            'nativeTextComplete': self.reports_complete and self.diagnostics_complete,
            'nativeTextTruncationObserved': truncated, 'outputLinePaginationAvailable': False,
            'rowsRead': len(rows), 'rowSummaries': summaries, 'rowSummariesOmitted': max(0, len(rows) - len(summaries)),
            'meaning': 'Text diagnostic coverage is separate from complete same-run physical observation evidence.'}

    def result(self, failure=None):
        self.full_flow = {'available': self.observation_evidence is not None, 'verified': False,
                          'scope': 'infinite_debug_observation', 'nativeRunOwned': False}
        if self.args.get('includeDetails') or failure is not None:
            self.full_flow['observation'] = self.observation_evidence
        data = super().result(failure)
        data['diagnosticCoverage'] = self.text_diagnostic_coverage()
        success = data['status'] == 'completed' and self.observation_summary is not None
        active = (self.terminal or {}).get('nativeBatchActive')
        if type(active) is not bool:
            active = None if self.client.action_attempted else False
        data.update(status='observation_ready' if success else data['status'], observationVerified=success,
            testPassed=False, finitePass=False, fullFlowVerified=False,
            runStillActive=active, stopPending=self.client.action_attempted,
            stopActionSubmitted=False, stopDrainStable85Verified=False,
            remainingLifecycle=['explicit_normal_stop', 'ordinary_drain_recovery', '85_real_stopped_ticks'],
            timingScope='command_entry_validation_queue_full_preflight_one_run_ready_complete_reports_full_after_read_observation_acceptance')
        if self.observation_summary is not None:
            data['observationSummary'] = self.observation_summary
        for key in ('cases', 'issues', 'phases', 'afterScan', 'preflight', 'runTicket'):
            if json_size(data) <= data['maxReturnBytes'] - 512:
                break
            if key in data:
                data.pop(key)
                data.update(outputOmitted=True, diagnosticsComplete=False)
                data.setdefault('omittedFields', []).append(key)
        if json_size(data) > data['maxReturnBytes'] - 512:
            data.pop('observationSummary', None)
            data.update(status='failed', observationVerified=False, completeWithin10s=False, outputOmitted=True,
                error={'code': 'qa_debug_summary_budget', 'message': 'Complete exact observation quantities exceed the return budget.', 'phase': 'observation_acceptance'})
            data.setdefault('omittedFields', []).append('observationSummary')
        data['wholeTaskElapsed_ms'] = (time.monotonic() - self.started) * 1000
        if failure is None and data['wholeTaskElapsed_ms'] > WHOLE_BUDGET_MS:
            data.update(status='failed', observationVerified=False, completeWithin10s=False,
                error={'code': 'qa_whole_budget_exceeded', 'message': 'Complete observation exceeded 10 seconds.', 'phase': 'whole_acceptance'})
        return data


async def run_qa_debug_observe(client, arguments, lock, *, runner=None, started_at=None):
    """Return task data once; no tool registration, action retry or implicit stop."""
    started = time.monotonic() if started_at is None else started_at
    errors = list(jsonschema.Draft202012Validator(QA_DEBUG_OBSERVE_SCHEMA).iter_errors(arguments))
    if errors:
        return {'status': 'failed', 'observationVerified': False, 'testPassed': False, 'finitePass': False,
            'fullFlowVerified': False, 'runSubmissions': 0, 'sideEffectsPossible': False, 'outcomeUnknown': False,
            'wholeTaskElapsed_ms': (time.monotonic() - started) * 1000,
            'error': {'code': 'INVALID_ARGUMENTS', 'message': errors[0].message[:512], 'phase': 'validation'}}
    if runner is None:
        from .workflows import run_workflow
        runner = run_workflow
    task = _DebugObservationTask(client, arguments, runner, started)
    failure, failure_phase, acquired = None, None, False
    try:
        with anyio.fail_after(max(.001, task.deadline - time.monotonic())):
            task.phase = 'queue'
            await lock.acquire()
            acquired = True
            await task.prepare()
            await task.execute()
            await task.diagnostics()
            await task.after()
            task.phase = 'observation_acceptance'
    except (BridgeError, _QaFailure, TimeoutError) as error:
        failure_phase = task.phase
        failure = error if isinstance(error, _QaFailure) else _QaFailure(
            getattr(error, 'code', 'qa_deadline'), str(error)[:512] or 'The bounded debug observation timed out.')
        if task.client.ticket is not None and not task.reports_complete:
            with anyio.CancelScope(shield=True):
                with anyio.move_on_after(2) as recovery_scope:
                    try:
                        await task.diagnostics(recovery=True)
                    except (BridgeError, _QaFailure) as diagnostic:
                        task.diagnostic_errors.append({'code': getattr(diagnostic, 'code', 'qa_diagnostic_failed'), 'message': str(diagnostic)[:512]})
                        task.diagnostics_complete = False
                if recovery_scope.cancel_called:
                    task.diagnostic_errors.append({'code': 'qa_diagnostic_deadline', 'message': 'The separate diagnostic allowance expired.'})
                    task.diagnostics_complete = False
    finally:
        if acquired:
            lock.release()
    if failure_phase is not None:
        task.phase = failure_phase
    return task.result(failure)
