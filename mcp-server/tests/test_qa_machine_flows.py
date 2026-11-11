"""Complete same-run declaration groups, real finite-flow acceptance and no scan clipping."""

from copy import deepcopy
import hashlib
import json
import math
import time

import anyio
import jsonschema
import pytest

from minecraft_codex_bridge import qa_tasks as qa
from minecraft_codex_bridge import qa_machine_flows as group
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.qa_flow_schema import DECLARED_MACHINE_FLOWS_SCHEMA
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from test_qa_completion_wait import private_capabilities
from test_qa_quantitative_summary import accepted_fixture, RUN_ID
from test_qa_task_discovery import QaClient


def declaration(index=0, *, dynamic=False):
    value = {"caseName": "machine" + str(index), "machine": {"registryName": "gregtech:gt.blockmachines", "metadata": 19037},
        "recipe": {"map": "test:map", "inputs": [{"registryName": "test|fixture:ore", "metadata": 0, "count": 7},
            {"registryName": "test|fixture:circuit", "metadata": 0, "count": 0}],
            "outputs": [{"registryName": "test|fixture:iron", "metadata": 0, "count": 5},
                {"registryName": "test|fixture:gold", "metadata": 0, "count": 11}],
            "fluidInputs": [{"fluid": "water", "amount": 1000}], "fluidOutputs": [{"fluid": "distilledwater", "amount": 1000}],
            "eut": 8, "durationTicks": 1}, "voltageTier": 1, "fixtureTemplate": "tstqa:core_flow_cell"}
    if dynamic:
        value.update(fixtureTemplate="tstqa:general_dynamic_flow_cell", fixtureBounds={
            "minX": -16, "minY": 0, "minZ": -16, "maxX": 16, "maxY": 32, "maxZ": 16,
            "facing": "SOUTH", "coordinateSpace": "controller_relative_world"})
    return value


def context():
    return {"ok": True, "source": qa.PRIVATE_SOURCE, "schemaVersion": 1,
        "nativeRunnerContract": qa.PRIVATE_CONTRACT, "dimension": 180,
        "providerClass": qa.PRIVATE_PROVIDER, "sessionId": "session", "owned": False}


def receipt(value):
    digest = hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    return {"machineFlowDeclaration": {**deepcopy(value), "declarationDigest": digest},
        "declarationDigest": digest, "declarationImmutable": True, "recipeSubstitutionAllowed": False}


def fixtures(values):
    records, origin = [], {"x": 0, "y": 3, "z": 0}
    for value in values:
        binding = receipt(value)
        case_id = group.DECLARED_SELECTOR + "[" + value["caseName"] + "-" + binding["declarationDigest"][:16] + "]"
        size = {axis: 16 for axis in "xyz"}
        if "fixtureBounds" in value:
            raw = value["fixtureBounds"]
            size = {axis.lower(): raw["max" + axis] - raw["min" + axis] + 5 for axis in "XYZ"}
        bounds = {prefix + axis: origin[axis.lower()] + (-1 if prefix == "min" else size[axis.lower()])
                  for prefix in ("min", "max") for axis in "XYZ"}
        cell = {prefix + axis: origin[axis.lower()] + (0 if prefix == "min" else size[axis.lower()] - 1)
                for prefix in ("min", "max") for axis in "XYZ"}
        plan = {**context(), **binding, "testId": case_id, "template": value["fixtureTemplate"], "geometry": "exact_adapter_reserved_bounds",
            "rotation": 0, "available": True, "allocationOrigin": deepcopy(origin), "placedSize": size,
            "cellBounds": cell, "clearBounds": bounds, "reservedBounds": bounds,
            "requestedVolume": math.prod(bounds["max" + axis] - bounds["min" + axis] + 1 for axis in "XYZ"),
            "margin": 1, "complete": True, "clearEnvelopeComplete": True, "callbackWritesBounded": False,
            "preClear": False, "requiresFreshAirPreflight": True, "protectsGroundY": 1}
        definition = {**binding, "id": case_id, "testId": case_id, "template": value["fixtureTemplate"],
            "rotation": 0, "caseType": "finite", "parameterized": True, "skippedAtDiscovery": False}
        records.append({"plan": plan, "definition": definition, "declaration": value})
        origin["z"] += size["z"] + 4
    return records


def proof(record, phase):
    plan = record["plan"]
    volume = plan["requestedVolume"]
    value = {"contract": "tstqa-fixture-coverage-v1", "schemaVersion": 1,
        "runId": RUN_ID, "sessionId": "session", "providerClass": qa.PRIVATE_PROVIDER, "dimension": 180,
        "testId": plan["testId"], "declarationDigest": plan["declarationDigest"], "bounds": plan["reservedBounds"],
        "phase": phase, "airSemantics": "minecraft_air", "requestedVolume": volume, "examined": volume,
        "air": volume, "nonAir": 0, "loaded": volume, "unloaded": 0, "complete": True, "truncated": False,
        "sampling": False, "worldTick": 100 if phase == "before" else 200,
        "observedNanos": 1000000000 if phase == "before" else 2000000000,
        "tileCount": 0, "entityCount": 0, "tileEntityAbsenceVerified": True, "entityAbsenceVerified": True,
        "freshMutationGuardVerified": True, "allSelectedFixturesPreflightBeforeMutation": True}
    if phase == "after":
        point = {"x": 1, "y": plan["allocationOrigin"]["y"] + 4, "z": plan["allocationOrigin"]["z"] + 1}
        tile = {**point, "tileClass": "test.GTTile", "valid": True}
        owner = {**tile, "metaTileId": 19037, "ownerAvailable": True, "actualTileIdentityVerified": True,
            "runtimeClass": "test.Controller", **record["declaration"]["machine"], "unlocalizedName": "test.machine",
            "allowedToWork": False, "infoData": [], "infoDataComplete": True, "controller": True,
            "formed": True, "formationSource": "actual public formed feedback", "structureCheckInvokedBySnapshot": False,
            "ports": {key: [] for key in ("energy", "maintenance", "itemInput", "itemOutput", "fluidInput", "fluidOutput", "muffler")}}
        value.update(air=volume - 1, nonAir=1, tileCount=1, gtTileCount=1, tiles=[tile], gtTiles=[owner],
            gtTileIdentityCoverageComplete=True, nonAirTypes=[{"blockIdentity": "gregtech:gt.blockmachines@1", "count": 1}],
            nonAirTypeCountsComplete=True, entityCoverageComplete=True, terminalPublicCleanupCompleted=True,
            nativeInstanceDone=True, nativeInstanceStatus="PASSED", ownedUnchangedFixtureVerified=True)
    return value


def flow(record):
    _, value, _ = accepted_fixture()
    origin = record["plan"]["allocationOrigin"]
    def shift(node):
        if isinstance(node, dict):
            if set("xyz") <= set(node):
                node["y"] += origin["y"]
                node["z"] += origin["z"]
            if "worldTick" in node:
                node["worldTick"] += 100
            if "cleanupWorldTick" in node:
                node["cleanupWorldTick"] += 100
            for child in node.values():
                shift(child)
        elif isinstance(node, list):
            for child in node:
                shift(child)
    shift(value)
    bounds = record["plan"]["reservedBounds"]
    value.update(caseId=record["plan"]["testId"], sessionId="session", origin=origin,
        machineFlowDeclaration=receipt(record["declaration"])["machineFlowDeclaration"],
        loadedFixtureBounds={**bounds, "coverage": "all_present_GT_tiles_in_fixture_bounds", "chunkCount":
            ((bounds["maxX"] >> 4) - (bounds["minX"] >> 4) + 1) * ((bounds["maxZ"] >> 4) - (bounds["minZ"] >> 4) + 1)})
    return value


class GroupClient(QaClient):
    def __init__(self, count=2, *, compact=True, dynamic=False):
        super().__init__()
        self.values = [declaration(index, dynamic=dynamic) for index in range(count)]
        self.records = fixtures(self.values)
        self.compact = compact
        self.state["dimension"] = 180
        self.health["capabilities"] += ["qa.results", "world.tiles", "world.entities"]
        self.catalog_mutation = None
        self.fixture_mutation = None
        self.case_mutation = None
        self.status_mutation = None
        self.issues = []
        self.runs = 0
        self.catalog_reads = 0
        self.fixture_reads = 0

    def run_status(self):
        count = len(self.records)
        native = {**context(), "runId": RUN_ID, "status": "PASS", "complete": True, "exitCode": 0,
            "selectedTests": count, "passed": count, **{key: 0 for key in qa.COUNT_FIELDS}}
        actual = []
        for record in self.records:
            coverage = {"contract": "tstqa-fixture-coverage-v1", "schemaVersion": 1,
                        "before": proof(record, "before")}
            actual.append({**deepcopy(record["plan"]), "fixtureCoverage": coverage})
        value = {**context(), "runId": RUN_ID, "selector": group.DECLARED_SELECTOR,
            "selectedTests": count, "selectedIds": [record["plan"]["testId"] for record in self.records],
            "selectedIdsTruncated": False, "freshSinceStart": True, "nativeBatchActive": False,
            "selectionMatches": True, "state": "completed", "startStatus": "STARTED", "fixtures": actual,
            "nativeReport": native}
        if self.status_mutation:
            self.status_mutation(value)
        return value

    async def call(self, operation, args, **kwargs):
        if operation in ("bridge.health", "client.snapshot", "inventory.inspect", "client.background"):
            return await super().call(operation, args, **kwargs)
        self.operations.append((operation, deepcopy(args)))
        if operation == "qa.catalog":
            section = args["section"]
            if section == "capabilities":
                value = {**private_capabilities(), **context()}
                if self.compact:
                    value.update(fixtureCoverageContract="tstqa-fixture-coverage-v1", fixtureCoverageSchemaVersion=1,
                        allSelectedFixturesPreflightBeforeMutation=True, coverageNoSampling=True,
                        maxFixtureReservedCells=group.FIXTURE_VOLUME_MAX, maxBatchReservedCells=group.GROUP_VOLUME_MAX)
                return value
            if section == "tests":
                self.catalog_reads += 1
                rows = deepcopy([record["definition"] for record in self.records])
                if self.catalog_mutation:
                    self.catalog_mutation(rows, self.catalog_reads)
                return {**context(), "section": section, "entries": rows, "total": len(rows), "offset": 0,
                        "hasMore": False, "truncated": False, "nextOffset": None}
            if section == "fixture_plan":
                self.fixture_reads += 1
                rows = deepcopy([record["plan"] for record in self.records])
                if self.fixture_mutation:
                    self.fixture_mutation(rows, self.fixture_reads)
                return {**context(), "complete": True, "fixtures": rows, "selectedTests": len(rows)}
            if section == "flow_evidence":
                rows = [{**context(), "available": False, "caseId": record["plan"]["testId"]} for record in self.records]
                return {**context(), "entries": rows, "total": len(rows), "offset": 0, "hasMore": False, "nextOffset": None}
        if operation == "qa.run":
            self.runs += 1
            return self.run_status()
        if operation == "qa.status":
            assert args["runId"] == RUN_ID
            return self.run_status()
        if operation == "qa.results":
            assert args["runId"] == RUN_ID and args["includeDetails"] is True
            rows = self.issues if args["section"] == "issues" else [{"id": record["plan"]["testId"], "status": "PASSED",
                "detailsCoverage": "requested", "detailsTruncated": False, "outputTotal": 0, "outputLines": [],
                **receipt(record["declaration"]), "flowEvidence": flow(record),
                "fixtureCoverage": {"contract": "tstqa-fixture-coverage-v1", "schemaVersion": 1,
                    "before": proof(record, "before"), "after": proof(record, "after")}} for record in self.records]
            rows = deepcopy(rows)
            if self.case_mutation and args["section"] == "cases":
                self.case_mutation(rows)
            offset, limit = args["offset"], args["limit"]
            selected = rows[offset:offset + limit]
            more = offset + len(selected) < len(rows)
            return {**self.run_status(), "section": args["section"], "entries": selected, "total": len(rows),
                "offset": offset, "limit": limit, "hasMore": more, "truncated": more,
                "nextOffset": offset + len(selected) if more else None}
        if operation == "world.scan":
            bounds = args["bounds"]
            volume = math.prod(bounds["max" + axis] - bounds["min" + axis] + 1 for axis in "XYZ")
            counts = {"air": volume, "nonAir": 0, "unloaded": 0, "loaded": volume, "examined": volume}
            return {"source": "integrated_server", "dimension": 180, "bounds": bounds, "outOfWorld": 0,
                "requestedVolume": volume, "scannableVolume": volume, "airSemantics": "minecraft_air", "nonAtomic": True,
                "complete": True, "coverage": {**counts, "remaining": 0}, "page": {**counts, "startOffset": 0, "endOffsetExclusive": volume},
                "blocks": [], "requestedRegionFullyObserved": True, "nextCursor": None}
        if operation == "world.tiles":
            return {"source": "integrated_server", "dimension": 180, **{key: args[key] for key in ("x", "y", "z", "radius")},
                "shape": "sphere", "totalMatchedIsExact": True, "candidateLimitReached": False, "tiles": [],
                "totalMatched": 0, "hasMore": False, "truncated": False, "nextOffset": None}
        if operation == "world.entities":
            center = {key: args[key] for key in "xyz"}
            radius, vertical = args["radiusChunks"] * 16, args["verticalRadiusChunks"] * 16
            bounds = {prefix + axis: center[axis.lower()] + (-1 if prefix == "min" else 1) *
                (radius if axis in "XZ" else vertical) + (1 if prefix == "max" else 0)
                for prefix in ("min", "max") for axis in "XYZ"}
            return {"source": "integrated_server", "dimension": 180, "center": center,
                "bounds": {"radiusBlocks": radius, "verticalRadiusBlocks": vertical}, "offset": args["offset"],
                "entities": [], "loadedCandidates": 0, "candidatesVisited": 0, "hasMore": False,
                "coverage": {"source": "Current loadedEntityList fully observed"}, "boundsMode": "intersection",
                "queryBounds": bounds, "unknownBoundsCandidates": 0, "nextOffset": None}
        raise AssertionError(operation)


async def run(client, **options):
    args = {"expectedSessionId": "session", "expectedWorld": "world", "selector": group.DECLARED_SELECTOR,
            "machineFlows": client.values, "requireFullFlow": True, **options}
    jsonschema.validate(args, qa.QA_TEST_ONCE_SCHEMA)
    return await qa.run_qa_test_once(client, args, anyio.Lock())


class ForgetClient(GroupClient):
    def __init__(self, *, fault=None, advertised=True):
        super().__init__(1)
        self.forget_fault, self.forget_advertised = fault, advertised
        self.forgets = 0

    async def call(self, operation, args, **kwargs):
        if operation == "qa.command":
            self.operations.append((operation, deepcopy(args)))
            self.forgets += 1
            assert args == {"command": "forget", "args": [RUN_ID], "expectedWorld": "world"}
            if self.forget_fault == "error":
                raise BridgeError("UNKNOWN_ACTION_OUTCOME", "The cache release receipt was unavailable.")
            value = {**context(), "runId": RUN_ID, "forgotten": True, "worldPreserved": True, "removedBlocks": 0,
                "cachedCoverageRemoved": True, "adapterTicketsReleased": True, "retainedRuns": 0, "maxRetainedRuns": 32}
            if self.forget_fault == "uuid":
                value["runId"] = "wrong-run"
            elif self.forget_fault == "world":
                value["worldPreserved"] = False
            elif self.forget_fault == "blocks":
                value["removedBlocks"] = 1
            return value
        value = await super().call(operation, args, **kwargs)
        if operation == "qa.catalog" and args["section"] == "capabilities":
            value.update(cacheClearOperation="forget", cacheClearPreservesWorld=self.forget_advertised)
        return value


async def test_native_forget_runs_once_after_complete_group_acceptance_and_keeps_receipt():
    client = ForgetClient()
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and result["resourceRelease"]
    assert result["resourceReleaseAttempted"] and result["nativeCacheReleaseSupported"]
    assert result["resourceReleaseReceipt"]["runId"] == RUN_ID and result["resourceReleaseReceipt"]["removedBlocks"] == 0
    assert result["runId"] == RUN_ID and result["flowResults"][0]["quantitativeSummary"]["stableTicks"] == 85
    assert client.forgets == client.runs == 1
    actions = [(operation, args) for operation, args in client.operations if operation.startswith("qa.")]
    assert actions[-1][0] == "qa.command" and actions[-2][0] == "qa.status"
    assert any(operation == "qa.results" and args["section"] == "issues" for operation, args in actions[:-1])


@pytest.mark.parametrize("fault", ["error", "uuid", "world", "blocks"])
async def test_native_forget_failure_is_separate_and_never_replayed(fault):
    client = ForgetClient(fault=fault)
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and not result["resourceRelease"]
    assert result["resourceReleaseAttempted"] and result["resourceReleaseError"] and result["runId"] == RUN_ID
    assert client.forgets == client.runs == 1


async def test_native_forget_requires_both_capabilities_and_complete_acceptance():
    client = ForgetClient(advertised=False)
    result = await run(client)
    assert result["status"] == "completed" and not result["resourceReleaseAttempted"] and client.forgets == 0
    client = ForgetClient()
    client.case_mutation = lambda rows: rows[0].pop("flowEvidence")
    result = await run(client)
    assert result["status"] == "failed" and not result["resourceReleaseAttempted"] and client.forgets == 0


async def test_native_forget_time_counts_in_the_fixed_whole_budget(monkeypatch):
    original_clock, offset = time.monotonic, [0]
    monkeypatch.setattr(time, "monotonic", lambda: original_clock() + offset[0])
    class LateForgetClient(ForgetClient):
        async def call(self, operation, args, **kwargs):
            value = await super().call(operation, args, **kwargs)
            if operation == "qa.command":
                offset[0] = 10.01
            return value
    client = LateForgetClient()
    result = await run(client, timeout_ms=60000)
    assert result["resourceRelease"] and result["wholeTaskElapsed_ms"] >= 10010 - 0.0001
    assert result["status"] == "failed" and not result["completeWithin10s"] and client.forgets == client.runs == 1


@pytest.mark.parametrize("count", [1, 2, 10])
async def test_complete_group_uses_one_uuid_run_and_every_real_flow(count):
    client = GroupClient(count)
    result = await run(client)
    assert result["status"] == "completed", result
    assert result["groupFullFlowVerified"] and result["machineFlowCount"] == count
    assert len(result["flowResults"]) == count and all(row["verified"] for row in result["flowResults"])
    assert all(row["quantitativeSummary"]["stableTicks"] == 85 for row in result["flowResults"])
    assert result["scanCells"] == sum(record["plan"]["requestedVolume"] for record in client.records) * 2
    assert client.runs == result["runSubmissions"] == 1
    assert not any(operation == "world.scan" for operation, _ in client.operations)
    cases = [args for operation, args in client.operations if operation == "qa.results" and args["section"] == "cases"]
    assert len(cases) == count and all(args["limit"] == 1 and args["includeDetails"] for args in cases)
    assert result["groupCaptureReleased"] and result["groupLimits"]["maxCapturedReportBytes"] == 33_554_432


async def test_complete_cases_and_issues_are_paged_from_same_run():
    client = GroupClient(10)
    result = await run(client, reportPageSize=3)
    assert result["status"] == "completed"
    requests = [args for operation, args in client.operations if operation == "qa.results"]
    assert [args["offset"] for args in requests if args["section"] == "cases"] == [0, 3, 6, 9]
    assert requests[-1]["section"] == "issues" and all(args["runId"] == RUN_ID for args in requests)


def clipped_case_logs(rows):
    for row in rows:
        row.update(detailsCoverage="requested", detailsTruncated=True, outputTotal=195,
                   outputLines=["native diagnostic line " + str(index) for index in range(16)])


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("include_details", [False, True])
async def test_clipped_logs_preserve_complete_semantic_group_acceptance(count, include_details):
    client = GroupClient(count)
    client.case_mutation = clipped_case_logs
    result = await run(client, includeDetails=include_details, max_return_bytes=262144)
    assert result["status"] == "completed" and result["completeWithin10s"], result
    assert result["testPassed"] and result["fullFlowVerified"] and result["groupFullFlowVerified"]
    assert result["reportsComplete"] and result["reportCoherent"] and not result["diagnosticsComplete"]
    assert result["nativeReport"]["status"] == "PASS" and result["caseCount"] == count and result["issueCount"] == 0
    assert result["runId"] == RUN_ID and client.runs == result["runSubmissions"] == 1
    assert all(row["verified"] and row["quantitativeSummary"]["stableTicks"] == 85 for row in result["flowResults"])
    assert result["scanCells"] == sum(record["plan"]["requestedVolume"] for record in client.records) * 2
    assert len(result["cases"]) == count and all(row["detailsTruncated"] for row in result["cases"])
    assert all(row["detailsCoverage"] == "requested" and row["status"] == "PASSED" for row in result["cases"])
    if include_details:
        assert all(row["outputTotal"] == 195 and len(row["outputLines"]) == 16 for row in result["cases"])
    requests = [args for operation, args in client.operations if operation == "qa.results"]
    assert [args["offset"] for args in requests if args["section"] == "cases"] == list(range(count))
    assert requests[-1]["section"] == "issues" and all(args["runId"] == RUN_ID for args in requests)


async def test_clipped_logs_allow_authorized_cache_release_after_semantic_acceptance():
    client = ForgetClient()
    client.case_mutation = clipped_case_logs
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and not result["diagnosticsComplete"]
    assert result["resourceRelease"] and result["resourceReleaseAttempted"] and client.forgets == client.runs == 1
    assert result["resourceReleaseReceipt"]["runId"] == RUN_ID and result["resourceReleaseReceipt"]["removedBlocks"] == 0


@pytest.mark.parametrize("fault", ["flow_missing", "snapshot_missing", "uuid", "ticks", "after_incomplete", "native_failed"])
async def test_clipped_logs_never_replace_missing_semantic_acceptance(fault):
    client = ForgetClient()
    def mutate(rows):
        clipped_case_logs(rows)
        evidence = rows[0]["flowEvidence"]
        if fault == "flow_missing":
            rows[0].pop("flowEvidence")
        elif fault == "snapshot_missing":
            evidence["snapshots"].pop()
        elif fault == "uuid":
            evidence["runId"] = "d0bfb751-cf01-4838-b2bf-2c5098b2f1d8"
        elif fault == "ticks":
            evidence["stages"][-2]["worldTick"] = 91
        elif fault == "after_incomplete":
            rows[0]["fixtureCoverage"]["after"]["examined"] -= 1
        else:
            rows[0]["status"] = "FAILED"
    client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and not result["diagnosticsComplete"]
    assert result["runId"] == RUN_ID and client.runs == result["runSubmissions"] == 1
    assert not result["resourceReleaseAttempted"] and client.forgets == 0


async def test_explicit_ten_case_page_still_verifies_all_cases_once():
    client = GroupClient(10)
    result = await run(client, reportPageSize=10)
    assert result["status"] == "completed" and result["groupFullFlowVerified"]
    requests = [args for operation, args in client.operations if operation == "qa.results" and args["section"] == "cases"]
    assert len(requests) == 1 and requests[0]["limit"] == 10 and requests[0]["offset"] == 0
    assert result["groupLimits"]["effectiveReportPageSize"] == 10 and client.runs == 1


async def test_response_byte_limit_shrinks_only_same_uuid_read_page():
    class ByteBoundedClient(GroupClient):
        async def call(self, operation, args, **kwargs):
            if operation == "qa.results" and args["section"] == "cases" and args["limit"] > 2:
                self.operations.append((operation, deepcopy(args)))
                raise BridgeError("RESPONSE_TOO_LARGE", "Complete native page exceeds transport budget.")
            return await super().call(operation, args, **kwargs)
    client = ByteBoundedClient(10)
    result = await run(client, reportPageSize=10)
    assert result["status"] == "completed" and result["groupFullFlowVerified"]
    requests = [args for operation, args in client.operations if operation == "qa.results" and args["section"] == "cases"]
    assert [(args["offset"], args["limit"]) for args in requests] == [(0, 10), (0, 5), (0, 2), (2, 2), (4, 2), (6, 2), (8, 2)]
    assert all(args["runId"] == RUN_ID and args["includeDetails"] is True for args in requests)
    assert result["groupLimits"]["responseByteBudgetRetries"] == 2 and client.runs == 1


async def test_native_byte_prefix_pages_follow_exact_returned_offset():
    class PrefixClient(GroupClient):
        async def call(self, operation, args, **kwargs):
            value = await super().call(operation, args, **kwargs)
            if operation == "qa.results" and len(value["entries"]) > 2:
                value["entries"] = value["entries"][:2]
                value.update(hasMore=True, truncated=True, nextOffset=args["offset"] + 2)
            return value
    client = PrefixClient(10)
    result = await run(client, reportPageSize=10)
    requests = [args for operation, args in client.operations if operation == "qa.results" and args["section"] == "cases"]
    assert result["status"] == "completed" and result["groupFullFlowVerified"]
    assert [args["offset"] for args in requests] == [0, 2, 4, 6, 8]
    assert all(args["limit"] == 10 and args["runId"] == RUN_ID for args in requests)
    assert result["groupLimits"]["responseByteBudgetRetries"] == 0 and client.runs == 1


async def test_single_oversized_case_is_failed_without_truncation_or_run_replay():
    class OversizedClient(GroupClient):
        async def call(self, operation, args, **kwargs):
            if operation == "qa.results" and args["section"] == "cases":
                self.operations.append((operation, deepcopy(args)))
                raise BridgeError("RESPONSE_TOO_LARGE", "One complete native row exceeds transport budget.")
            return await super().call(operation, args, **kwargs)
    client = OversizedClient(1)
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and not result["reportsComplete"]
    assert any(error["code"] == "RESPONSE_TOO_LARGE" for error in result["diagnosticErrors"])
    assert client.runs == 1 and result["groupCaptureReleased"]


@pytest.mark.parametrize("failed", [False, True])
async def test_capture_release_detaches_task_dtos_without_changing_receipt(monkeypatch, failed):
    tasks = []
    class TrackedTask(group._MachineFlowQaTask):
        def __init__(self, *args):
            super().__init__(*args)
            tasks.append(self)
    monkeypatch.setattr(group, "_MachineFlowQaTask", TrackedTask)
    client = GroupClient(1)
    if failed:
        client.case_mutation = lambda rows: rows[0].pop("flowEvidence")
    result = await run(client, includeDetails=True, max_return_bytes=262144)
    task = tasks[0]
    assert result["groupCaptureReleased"] and result["runId"] == RUN_ID and result["runSubmissions"] == 1
    assert result["runTicket"]["fixtures"][0]["fixtureCoverage"]["before"]["complete"] is True
    assert task.client.latest == {} and task.client.result_pages == [] and task.client.ticket is None
    assert task.client.guard is task.client.qa_guard is task.client.run_guard is None
    assert task.records == task.cases == task.issues == task.flow_results == []
    assert task.terminal is task.case is task.plan is task.flow_before is None
    assert result["status"] == ("failed" if failed else "completed")
    if not failed:
        assert result["flowResults"][0]["quantitativeSummary"]["stableTicks"] == 85


@pytest.mark.parametrize("group_capture,limit", [(False, 2097152), (True, 33554432)])
async def test_report_capture_accepts_exact_bounded_capacity_and_rejects_one_byte_more(monkeypatch, group_capture, limit):
    class CaptureClient(QaClient):
        async def call(self, operation, args, **kwargs):
            return {"capturedBytes": args["capturedBytes"]}
    client = CaptureClient()
    recorder = group._MachineFlowQaTask(client, {"machineFlows": [declaration()]}, None, time.monotonic()).client \
        if group_capture else qa._RecordingClient(client, client.configuration.read(), 16)
    recorder.qa_guard = None
    monkeypatch.setattr(qa, "json_size", lambda value: value["capturedBytes"])
    assert recorder.maximum_capture_bytes == limit
    await recorder.call("qa.results", {"capturedBytes": limit - 1})
    await recorder.call("qa.results", {"capturedBytes": 1})
    assert recorder.bytes == limit and len(recorder.result_pages) == 2
    with pytest.raises(qa._QaFailure, match="2 MiB" if not group_capture else "bounded capture allowance") as error:
        await recorder.call("qa.results", {"capturedBytes": 1})
    assert error.value.code == "qa_report_memory_budget" and recorder.bytes == limit and len(recorder.result_pages) == 2


def test_forget_schema_requires_one_canonical_lowercase_uuid():
    schema = TOOL_BY_NAME["mcb_qa_command"].schema
    jsonschema.validate({"command": "forget", "expectedWorld": "world", "args": [RUN_ID]}, schema)
    for args in (None, [], [RUN_ID, RUN_ID], [RUN_ID.upper()], ["all"], ["not-a-run"]):
        value = {"command": "forget", "expectedWorld": "world"}
        if args is not None:
            value["args"] = args
        with pytest.raises(jsonschema.ValidationError):
            jsonschema.validate(value, schema)
    jsonschema.validate({"command": "labels", "expectedWorld": "world"}, schema)


def test_declared_recipe_integer_limits_and_supported_rules_remain_explicit():
    value = declaration()
    value["recipe"]["durationTicks"] = 2147483647
    value["recipe"]["inputs"][0]["count"] = 2147483647
    value["recipe"]["outputs"][0]["count"] = 2147483647
    value["recipe"]["inputs"].append(deepcopy(value["recipe"]["inputs"][0]))
    jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)
    for change in (lambda recipe: recipe.update(durationTicks=2147483648),
                   lambda recipe: recipe["inputs"][0].update(count=2147483648),
                   lambda recipe: recipe["inputs"][0].update(count=-1),
                   lambda recipe: recipe["outputs"][0].update(count=2147483648),
                   lambda recipe: recipe["outputs"][0].update(count=0),
                   lambda recipe: recipe["fluidInputs"].append({"fluid": "lava", "amount": 1}),
                   lambda recipe: recipe.update(chances=[5000]),
                   lambda recipe: recipe.update(specialValue=1),
                   lambda recipe: recipe.update(metadata={"heat": 1})):
        candidate = deepcopy(value)
        change(candidate["recipe"])
        with pytest.raises(jsonschema.ValidationError):
            jsonschema.validate([candidate], DECLARED_MACHINE_FLOWS_SCHEMA)
    value["upgrades"] = [{"item": value["machine"], "expectedFeedbackLines": ["changed"],
        "manufacturingRecipe": {"source": "public_crafting", "outputCount": 64}}]
    jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)
    value["upgrades"][0]["manufacturingRecipe"]["outputCount"] = 65
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)


@pytest.mark.parametrize("fault", ["digest", "fixture", "duplicate", "missing"])
async def test_fresh_catalog_and_fixture_drift_blocks_submission(fault):
    client = GroupClient()
    if fault == "fixture":
        client.fixture_mutation = lambda rows, read: rows[0]["allocationOrigin"].update(z=1) if read == 2 else None
    else:
        def mutate(rows, read):
            if read != 2:
                return
            if fault == "digest":
                rows[0]["declarationDigest"] = "0" * 64
            elif fault == "duplicate":
                rows[1] = deepcopy(rows[0])
            else:
                rows.pop()
        client.catalog_mutation = mutate
    result = await run(client)
    assert result["status"] != "completed" and not result["groupFullFlowVerified"] and client.runs == 0


@pytest.mark.parametrize("fault", ["missing", "duplicate", "digest", "uuid", "session", "ticks", "coverage", "native", "flow_missing", "substitution", "malformed"])
async def test_one_invalid_case_prevents_group_acceptance_without_replay(fault):
    client = GroupClient()
    def mutate(rows):
        evidence = rows[-1]["flowEvidence"]
        if fault == "missing":
            rows.pop()
        elif fault == "duplicate":
            rows[-1] = deepcopy(rows[0])
        elif fault == "digest":
            rows[-1]["declarationDigest"] = "0" * 64
        elif fault == "uuid":
            evidence["runId"] = "d0bfb751-cf01-4838-b2bf-2c5098b2f1d8"
        elif fault == "session":
            evidence["sessionId"] = "different-session"
        elif fault == "ticks":
            evidence["stages"][-2]["worldTick"] = 91
        elif fault == "coverage":
            rows[-1]["fixtureCoverage"]["after"]["examined"] -= 1
        elif fault == "native":
            rows[-1]["status"] = "FAILED"
        elif fault == "substitution":
            def substitute(node):
                if isinstance(node, dict):
                    if node.get("registryName") == "test|fixture:iron":
                        node["registryName"] = "test|fixture:lead"
                    for child in node.values():
                        substitute(child)
                elif isinstance(node, list):
                    for child in node:
                        substitute(child)
            substitute(evidence["declaredRecipe"])
            substitute(evidence["snapshots"])
        elif fault == "malformed":
            evidence["machineFlowDeclaration"] = "invalid-json-shape"
        else:
            rows[-1].pop("flowEvidence")
    client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1


async def test_infrastructure_issue_is_read_and_prevents_full_group():
    client = GroupClient()
    client.issues = [{"message": "actual preparation failure", "detailsCoverage": "requested", "detailsTruncated": False}]
    result = await run(client)
    assert result["status"] == "failed" and result["issueCount"] == 1 and client.runs == 1
    assert not result["groupFullFlowVerified"]


async def test_group_scan_budget_is_sum_of_reserved_volumes_twice():
    client = GroupClient(2, dynamic=True)
    total = sum(record["plan"]["requestedVolume"] for record in client.records) * 2
    result = await run(client, maxScanCells=total - 1)
    assert result["error"]["code"] == "qa_fixture_volume_budget" and client.runs == 0
    result = await run(client, maxScanCells=total)
    assert result["status"] == "completed" and result["scanCells"] == total


async def test_late_group_clock_is_never_divided_by_case_count(monkeypatch):
    client = GroupClient(10)
    result = await qa.run_qa_test_once(client, {"expectedSessionId": "session", "expectedWorld": "world",
        "selector": group.DECLARED_SELECTOR, "machineFlows": client.values, "timeout_ms": 60000}, anyio.Lock(),
        started_at=time.monotonic() - 10.01)
    assert result["status"] == "failed" and not result["completeWithin10s"]
    assert result["wholeTaskElapsed_ms"] >= 10010 and result["machineFlowCount"] == 10
    assert result["error"]["code"] == "qa_whole_budget_exceeded"


def test_dynamic_schema_requires_exact_world_facing_bounds_only_for_dynamic_template():
    value = declaration(dynamic=True)
    jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)
    for change in (lambda candidate: candidate.pop("fixtureBounds"),
                   lambda candidate: candidate["fixtureBounds"].update(facing="UNKNOWN"),
                   lambda candidate: candidate.update(fixtureTemplate="tstqa:core_flow_cell")):
        candidate = deepcopy(value)
        change(candidate)
        with pytest.raises(jsonschema.ValidationError):
            jsonschema.validate([candidate], DECLARED_MACHINE_FLOWS_SCHEMA)


async def test_unadvertised_compact_contract_retains_full_disjoint_before_and_after_scans():
    client = GroupClient(2, compact=False)
    result = await run(client)
    assert result["status"] == "completed", result
    scans = [args["bounds"] for operation, args in client.operations if operation == "world.scan"]
    expected = [record["plan"]["reservedBounds"] for record in client.records]
    assert scans == expected + expected and result["scanCells"] == sum(record["plan"]["requestedVolume"] for record in client.records) * 2
    assert client.runs == 1 and result["groupFullFlowVerified"]


@pytest.mark.parametrize("fault", ["before_cells", "before_tile", "before_entity", "bounds_type", "before_run", "after_loaded", "after_session", "after_digest"])
async def test_compact_proof_never_promotes_incomplete_or_wrong_context_space(fault):
    client = GroupClient()
    if fault.startswith("before"):
        def mutate(value):
            proof = value["fixtures"][-1]["fixtureCoverage"]["before"]
            if fault == "before_cells":
                proof["examined"] -= 1
            elif fault == "before_tile":
                proof["tileCount"] = 1
            elif fault == "before_entity":
                proof["entityCount"] = 1
            else:
                proof["runId"] = "wrong-run"
        client.status_mutation = mutate
    else:
        def mutate(rows):
            proof = rows[-1]["fixtureCoverage"]["after"]
            if fault == "bounds_type":
                proof["bounds"]["minX"] = False
            elif fault == "after_loaded":
                proof["loaded"] -= 1
            elif fault == "after_session":
                proof["sessionId"] = "wrong-session"
            else:
                proof["declarationDigest"] = "0" * 64
        client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1


@pytest.mark.parametrize("fault", ["gt_omitted", "gt_duplicate", "world_identity", "owner_identity", "machine_stopped", "formed", "histogram", "cleanup_tick", "sampling", "truncated", "port_identity"])
async def test_compact_after_requires_every_real_tile_machine_and_complete_histogram(fault):
    client = GroupClient()
    def mutate(rows):
        proof = rows[-1]["fixtureCoverage"]["after"]
        owner = proof["gtTiles"][0]
        evidence = rows[-1]["flowEvidence"]
        if fault == "gt_omitted":
            proof.update(gtTiles=[], gtTileCount=0)
        elif fault == "gt_duplicate":
            proof["gtTiles"].append(deepcopy(owner))
            proof["gtTileCount"] = 2
        elif fault == "world_identity":
            owner["actualTileIdentityVerified"] = False
        elif fault == "owner_identity":
            owner["metadata"] += 1
        elif fault == "machine_stopped":
            owner["allowedToWork"] = True
        elif fault == "formed":
            evidence["stages"][2]["name"] = "native_public_blueprint_constructed"
            owner["formed"] = False
        elif fault == "histogram":
            proof["nonAirTypes"][0]["count"] += 1
        elif fault == "cleanup_tick":
            proof["worldTick"] = evidence["cleanupWorldTick"] - 1
        elif fault == "sampling":
            proof["sampling"] = True
        elif fault == "truncated":
            proof["truncated"] = True
        else:
            evidence["stages"][2]["name"] = "native_public_blueprint_constructed"
            port = {key: owner[key] for key in ("x", "y", "z", "tileClass", "valid", "metaTileId", "runtimeClass")}
            port.update(actualOwnerIdentityVerified=True, runtimeClass="different.ActualOwner")
            owner["ports"]["energy"] = [port]
    client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1


async def test_native_group_counts_must_match_all_actual_cases():
    client = GroupClient()
    client.status_mutation = lambda value: value["nativeReport"].update(passed=1)
    result = await run(client)
    assert result["status"] == "failed" and not result["testPassed"] and not result["groupFullFlowVerified"]


async def test_every_infrastructure_issue_suffix_is_collected_before_failure():
    client = GroupClient()
    client.issues = [{"message": "actual issue " + str(index), "detailsCoverage": "requested", "detailsTruncated": False} for index in range(7)]
    result = await run(client, reportPageSize=3)
    requests = [args for operation, args in client.operations if operation == "qa.results" and args["section"] == "issues"]
    assert result["issueCount"] == 7 and result["reportsComplete"] and result["status"] == "failed"
    assert [args["offset"] for args in requests] == [0, 3, 6] and all(args["runId"] == RUN_ID for args in requests)


def zero_item_output_client(*, catalyst):
    client = GroupClient(count=1)
    client.values[0]["recipe"]["outputs"] = []
    if not catalyst:
        client.values[0]["recipe"]["inputs"] = client.values[0]["recipe"]["inputs"][:1]
    client.records = fixtures(client.values)
    def mutate(rows):
        evidence = rows[0]["flowEvidence"]
        evidence["stages"][2]["name"] = "native_public_blueprint_constructed"
        evidence["declaredRecipe"]["itemOutputs"] = []
        if not catalyst:
            evidence["declaredRecipe"]["physicalCatalysts"] = []
        evidence["publicItemOutputBindings"] = {"physicalItemOutputPortCount": 0,
            "allPhysicalOutputsBound": True, "itemOutputRequired": False,
            "nativeRawItemOutputCount": 0, "declaredRawItemOutputCount": 0,
            "missingDescriptionMeansRoleAbsent": False, "virtualOutputInventory": False}
        for snapshot in evidence["snapshots"]:
            for binding in snapshot["items"]:
                if binding["binding"] == "item_source":
                    binding["binding"] = "finite_input"
                for slot in binding["slots"]:
                    if slot.get("registryName") in ("test|fixture:iron", "test|fixture:gold") or (
                            not catalyst and slot.get("registryName") == "test|fixture:circuit"):
                        slot.clear()
                for index, slot in enumerate(binding["slots"]):
                    if not slot:
                        slot.update(slot=index, count=0)
    client.case_mutation = mutate
    return client


@pytest.mark.parametrize("catalyst", [False, True])
async def test_exact_fluid_product_flow_without_physical_item_output(catalyst):
    client = zero_item_output_client(catalyst=catalyst)
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and client.runs == 1
    summary = result["flowResults"][0]["quantitativeSummary"]
    assert summary["itemOutputs"] == []
    assert len(summary["physicalCatalysts"]) == int(catalyst)
    assert summary["stableTicks"] == 85 and summary["receiverFluids"][0]["amount"] == "1000"


@pytest.mark.parametrize("fault", ["receipt_missing", "raw_product", "declared_product", "required",
    "count", "virtual", "description_absence", "unbound_port", "fake_output", "extra_item", "lost_catalyst"])
async def test_missing_item_output_needs_exact_public_absence_and_complete_accounting(fault):
    client = zero_item_output_client(catalyst=True)
    prepare = client.case_mutation
    def mutate(rows):
        prepare(rows)
        evidence = rows[0]["flowEvidence"]
        receipt = evidence["publicItemOutputBindings"]
        if fault == "receipt_missing":
            del evidence["publicItemOutputBindings"]
        elif fault == "raw_product":
            receipt["nativeRawItemOutputCount"] = 1
        elif fault == "declared_product":
            receipt["declaredRawItemOutputCount"] = 1
        elif fault == "required":
            receipt["itemOutputRequired"] = True
        elif fault == "count":
            receipt["physicalItemOutputPortCount"] = 1
        elif fault == "virtual":
            receipt["virtualOutputInventory"] = True
        elif fault == "description_absence":
            receipt["missingDescriptionMeansRoleAbsent"] = True
        elif fault == "unbound_port":
            owner = rows[0]["fixtureCoverage"]["after"]["gtTiles"][0]
            port = {key: owner[key] for key in ("x", "y", "z", "tileClass", "valid", "metaTileId", "runtimeClass")}
            port["actualOwnerIdentityVerified"] = True
            owner["ports"]["itemOutput"] = [port]
        elif fault == "fake_output":
            for snapshot in evidence["snapshots"]:
                fake = deepcopy(snapshot["items"][0])
                fake.update(binding="output", position={"x": 3, "y": 7, "z": 1})
                fake["slots"] = [{"slot": index, "count": 0} for index in range(4)]
                snapshot["items"].append(fake)
        elif fault == "extra_item":
            evidence["snapshots"][2]["items"][0]["slots"][1].update(
                registryName="test|fixture:iron", metadata=0, nbt="{}", count=1)
        else:
            for snapshot in evidence["snapshots"][3:]:
                snapshot["items"][1]["slots"][0] = {"slot": 0, "count": 0}
    client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1
