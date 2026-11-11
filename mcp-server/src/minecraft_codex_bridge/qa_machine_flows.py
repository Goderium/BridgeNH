"""One immutable declaration group, one UUID run and complete per-fixture acceptance."""

from __future__ import annotations

from copy import deepcopy
from collections import Counter
import hashlib
import json
import re
import time
from uuid import UUID

from .bridge import BridgeError
from .conveniences import json_size
from .qa_tasks import (
    COUNT_FIELDS, LONG_MAX, PRIVATE_PROVIDER, WHOLE_BUDGET_MS, _QaFailure, _QaTask,
    ITEM_CHANCE_CONTRACT, _output_chances, _bag, _inside, _integer, _inventory, _read, _require,
)


DECLARED_SELECTOR = "tstqa:DeclaredMachineFlow.run"
GROUP_VOLUME_MAX = 16_777_216
FIXTURE_VOLUME_MAX = 2_097_152
PLAN_KEYS = ("testId", "template", "rotation", "allocationOrigin", "placedSize", "cellBounds",
    "clearBounds", "reservedBounds", "requestedVolume", "nativeRunnerContract", "dimension",
    "providerClass", "sessionId", "declarationDigest", "machineFlowDeclaration")


class _FlowView(_QaTask):
    """Reuses the established finite-flow verifier with one case's actual context."""

    def __init__(self, parent, record):
        super().__init__(parent.client.client, {**parent.args, "selector": record["caseId"]},
                         parent.runner, parent.started)
        self.parent, self.record = parent, record
        self.client = parent.client
        self.dimension, self.provider_class = parent.dimension, parent.provider_class
        self.case = record["definition"]
        self.plan = record.get("fixture")
        self.flow_before = record.get("before")

    def test_passed(self):
        return self.parent.case_passed(self.record["caseId"])


class _MachineFlowQaTask(_QaTask):
    def __init__(self, client, args, runner, started):
        # Keep the same entry/queue clock and bounded transport. The larger
        # cell allowance covers disjoint exact fixtures, never a merged box.
        arguments = deepcopy(args)
        arguments.setdefault("maxScanCells", GROUP_VOLUME_MAX * 2)
        arguments.setdefault("reportPageSize", 1)
        arguments.setdefault("maxRequests", 256)
        super().__init__(client, arguments, runner, started)
        self.client.maximum_capture_bytes = 33_554_432
        self.declarations = deepcopy(arguments.get("machineFlows"))
        self.records = []
        self.group_origin = None
        self.fixture_volume = 0
        self.flow_results = []
        self.compact_space = False
        self.before_coverage = {}
        self.report_page_limit = arguments["reportPageSize"]
        self.report_byte_retries = 0
        self.native_cache_release_supported = False
        self.resource_release = False
        self.resource_release_attempted = False
        self.resource_release_receipt = None
        self.resource_release_error = None

    async def read_results_page(self, run_id, section, offset, *, diagnostic=False):
        while True:
            try:
                return await self.read("qa.results", "mcb_qa_results", {"runId": run_id, "section": section,
                    "offset": offset, "limit": self.report_page_limit, "includeDetails": True},
                    section=section, diagnostic=diagnostic)
            except (BridgeError, _QaFailure) as error:
                if error.code != "RESPONSE_TOO_LARGE" or self.report_page_limit == 1:
                    raise
                # A failed read-only page has consumed no report offset. Keep
                # the exact UUID/section/offset, with the normal request budget.
                self.report_page_limit = max(1, self.report_page_limit // 2)
                self.report_byte_retries += 1

    def config(self, value):
        origin = super().config(value)
        self.native_cache_release_supported = (value.get("cacheClearOperation") == "forget"
            and value.get("cacheClearPreservesWorld") is True)
        contract = value.get("fixtureCoverageContract") == "tstqa-fixture-coverage-v1"
        if any("machineMode" in declaration or "blueprintChannels" in declaration for declaration in self.declarations):
            _require(value.get("declaredRuntimeSelectionContract") == "tstqa-runtime-selection-v1"
                     and value.get("declaredMachineModeSelection") is True and value.get("declaredBlueprintChannels") is True
                     and type(value.get("maxDeclaredModeChanges")) is int and value["maxDeclaredModeChanges"] == 8
                     and type(value.get("maxDeclaredBlueprintChannels")) is int and value["maxDeclaredBlueprintChannels"] == 16
                     and type(value.get("maxDeclaredChannelValue")) is int and value["maxDeclaredChannelValue"] == 64,
                     "qa_runtime_selection_unsupported", "Selected native modes/channels require the complete public runtime-selection contract.", unsupported=True)
        self.compact_space = (contract and type(value.get("fixtureCoverageSchemaVersion")) is int
            and value["fixtureCoverageSchemaVersion"] == 1
            and value.get("allSelectedFixturesPreflightBeforeMutation") is True
            and value.get("coverageNoSampling") is True
            and type(value.get("maxFixtureReservedCells")) is int and value["maxFixtureReservedCells"] >= FIXTURE_VOLUME_MAX
            and type(value.get("maxBatchReservedCells")) is int and value["maxBatchReservedCells"] >= GROUP_VOLUME_MAX)
        _require(not contract or self.compact_space, "qa_fixture_coverage_contract_incomplete",
                 "An advertised compact contract must expose its complete no-sampling, whole-group preflight and bounded capacities.", unsupported=True)
        if any("machineMode" in declaration or "blueprintChannels" in declaration for declaration in self.declarations):
            _require(self.compact_space, "qa_runtime_selection_unsupported",
                     "Selected modes/channels require complete fresh final-controller coverage.", unsupported=True)
        if any(any(chance != 10000 for chance in _output_chances(declaration["recipe"])) for declaration in self.declarations):
            _require(self.compact_space, "qa_chance_output_coverage_invalid",
                     "Finite item chances require complete fresh final-controller/output-port coverage.", unsupported=True)
        return origin

    def declaration_binding(self, value, declaration, *, definition=True):
        declared = value.get("machineFlowDeclaration") if isinstance(value, dict) else None
        digest = value.get("declarationDigest") if definition and isinstance(value, dict) else \
            declared.get("declarationDigest") if isinstance(declared, dict) else None
        _require(isinstance(declared, dict) and isinstance(digest, str)
                 and re.fullmatch(r"[0-9a-f]{64}", digest) is not None
                 and declared.get("declarationDigest") == digest
                 and all(key in declared and declared[key] == item for key, item in declaration.items()),
                 "qa_declaration_changed", "Each case, fixture and flow must retain its exact submitted declaration and digest.")
        # Gson JsonObject.toString() hashes compact insertion-ordered JSON.
        # Do not sort keys or hash its enriched evidence object.
        expected = hashlib.sha256(json.dumps(declaration, ensure_ascii=False,
            separators=(",", ":"), allow_nan=False).encode("utf-8")).hexdigest()
        _require(digest == expected, "qa_declaration_changed",
                 "The public declaration digest must match the exact ordered submitted JSON.")
        if definition:
            _require(value.get("declarationImmutable") is True and value.get("recipeSubstitutionAllowed") is False,
                     "qa_declaration_mutability_unknown", "Declared native definitions must prohibit recipe substitution.")
        return digest

    def recipe_binding(self, evidence, declaration):
        actual, recipe = evidence.get("declaredRecipe"), declaration["recipe"]
        _require(isinstance(actual, dict), "qa_declared_recipe_changed", "The actual finite recipe needs its complete typed resource declaration.")
        chances = _output_chances(recipe)
        probabilistic = any(chance != 10000 for chance in chances)
        if len(recipe["fluidInputs"]) > 1 or len(recipe["fluidOutputs"]) > 1:
            _require(isinstance(evidence.get("publicMultiFluidBindings"), dict)
                     and evidence["publicMultiFluidBindings"].get("contract") == "native_deterministic_multi_fluid_v1",
                     "qa_multi_fluid_bindings_invalid", "Multiple raw fluids require the explicit complete physical native deterministic contract.")
        _require(type(evidence.get("schemaVersion")) is int and evidence["schemaVersion"] == (2 if probabilistic else 1)
                 and (not probabilistic or evidence.get("flowContract") == ITEM_CHANCE_CONTRACT),
                 "qa_declared_recipe_changed", "Probability declarations require the new explicit contract; deterministic declarations retain schema 1 exact quantities.")
        if probabilistic:
            raw = actual.get("rawItemOutputs")
            _require(isinstance(raw, list) and len(raw) == len(recipe["outputs"])
                     and all(isinstance(row, dict) and type(row.get("outputIndex")) is int and row["outputIndex"] == index
                         and type(row.get("chance")) is int and row["chance"] == chances[index]
                         and row.get("registryName") == expected["registryName"]
                         and type(row.get("metadata")) is int and row["metadata"] == expected["metadata"]
                         and type(row.get("count")) is int and row["count"] == expected["count"]
                         and row.get("nbt") in ("", "{}") for index, (row, expected) in enumerate(zip(raw, recipe["outputs"]))),
                     "qa_declared_recipe_changed", "Every ordered raw potential output and unchanged chance must equal the immutable submitted recipe.")
        for target, source, fluid in (("consumedItems", "inputs", False), ("physicalCatalysts", "inputs", False),
                ("itemOutputs", "outputs", False), ("fluidInputs", "fluidInputs", True), ("fluidOutputs", "fluidOutputs", True)):
            expected, observed = Counter(), Counter()
            for row in recipe[source]:
                if not fluid and source == "inputs" and (row["count"] == 0) != (target == "physicalCatalysts"):
                    continue
                identity = row["fluid"] if fluid else (row["registryName"], row["metadata"])
                expected[identity] += row["amount"] if fluid else 1 if target == "physicalCatalysts" else row["count"]
            for identity, quantity in _bag(actual.get(target), fluid=fluid).items():
                _require(identity[-1] in ("", "{}"), "qa_declared_recipe_changed", "The untagged wire declaration cannot certify a tagged native item or fluid; exact NBT cannot be stripped.")
                observed[identity[0] if fluid else identity[:2]] += quantity
            _require(observed == expected, "qa_declared_recipe_changed",
                     "The actual consumed resources, physical catalysts and products must equal the submitted finite recipe; no substitution is accepted.")

    def runtime_selection_binding(self, evidence, record):
        declaration = record["declaration"]
        if "machineMode" not in declaration and "blueprintChannels" not in declaration:
            return None
        binding = evidence.get("publicRuntimeSelection") if isinstance(evidence, dict) else None
        channels = declaration.get("blueprintChannels", [])
        _require(isinstance(channels, list) and len(channels) <= 16
                 and all(isinstance(row, dict) and set(row) == {"name", "value"}
                     and isinstance(row["name"], str) and re.fullmatch(r"[a-z0-9_.-]{1,64}", row["name"])
                     and _integer(row["value"], 1, 64) for row in channels)
                 and len({row["name"] for row in channels}) == len(channels),
                 "qa_runtime_selection_invalid", "Requested channels must retain every unique exact typed name/value.")
        _require(isinstance(binding, dict) and binding.get("contract") == "tstqa-runtime-selection-v1"
                 and binding.get("declarationDigest") == record["declarationDigest"]
                 and binding.get("nativeConstructionCompleted") is True and binding.get("prototypeMutated") is False
                 and binding.get("recipeMapIdentityVerified") is True and binding.get("channelStorageVerified") is True
                 and binding.get("nativeChannelConsumptionVerified") is False
                 and binding.get("structureMaterialProgressionVerified") is False
                 and type(binding.get("requestedMachineModePresent")) is bool
                 and binding["requestedMachineModePresent"] == ("machineMode" in declaration)
                 and binding.get("requestedMachineMode") == declaration.get("machineMode")
                 and ("machineMode" not in declaration or _integer(binding.get("requestedMachineMode"), 0, 2_147_483_647))
                 and _integer(binding.get("actualMachineMode"), 0, 2_147_483_647)
                 and ("machineMode" not in declaration or binding["actualMachineMode"] == declaration["machineMode"])
                 and binding.get("actualRecipeMap") == declaration["recipe"]["map"]
                 and _integer(binding.get("modeChanges"), 0, 8)
                 and type(binding.get("maxModeChanges")) is int and binding["maxModeChanges"] == 8
                 and isinstance(binding.get("channels"), list)
                 and all(isinstance(row, dict) and set(row) == {"name", "value"}
                     and isinstance(row["name"], str) and _integer(row["value"], 1, 64) for row in binding["channels"])
                 and binding["channels"] == channels,
                 "qa_runtime_selection_changed", "Native mode/map and exact stored channels must bind this declaration without claiming unverified channel effects.")
        trigger = binding.get("trigger")
        _require(isinstance(trigger, dict) and _integer(trigger.get("count"), 1, 64)
                 and trigger["count"] == declaration.get("constructionAmount", 1)
                 and isinstance(trigger.get("nbt"), str) and len(trigger["nbt"]) <= 4096
                 and (not channels or bool(trigger["nbt"]))
                 and trigger.get("registryName") == declaration["machine"]["registryName"]
                 and type(trigger.get("metadata")) is int and trigger["metadata"] == declaration["machine"]["metadata"],
                 "qa_runtime_trigger_changed", "Native construction needs the exact registered trigger copy/count and complete channel NBT.")
        snapshots = evidence.get("snapshots")
        _require(isinstance(evidence.get("stages"), list) and sum(isinstance(row, dict)
                     and row.get("name") == "native_public_blueprint_constructed" for row in evidence["stages"]) == 1,
                 "qa_runtime_construction_unverified", "Selected runtime state requires the actual public multiblock construction stage.")
        _require(isinstance(snapshots, list) and len(snapshots) == 5
                 and all(isinstance(row, dict) and json.dumps(row.get("runtimeSelection"), sort_keys=True, separators=(",", ":"), allow_nan=False)
                     == json.dumps(binding, sort_keys=True, separators=(",", ":"), allow_nan=False) for row in snapshots),
                 "qa_runtime_selection_checkpoint_incomplete", "All five complete checkpoints must retain the exact mode/map/trigger/channel binding.")
        return binding

    def catalog_records(self, catalog):
        rows = catalog.get("entries")
        _require(catalog.get("ok") is True and catalog.get("section") == "tests"
                 and catalog.get("offset") == 0 and type(catalog.get("offset")) is int
                 and type(catalog.get("total")) is int and catalog["total"] == len(self.declarations)
                 and catalog.get("hasMore") is False and catalog.get("nextOffset") is None
                 and isinstance(rows, list) and len(rows) == len(self.declarations),
                 "qa_catalog_incomplete", "Fresh catalog coverage must contain every declared case once without an omitted page.")
        records = []
        for row, declaration in zip(rows, self.declarations):
            _require(isinstance(row, dict), "qa_catalog_incomplete", "Every declared case needs its actual public definition.")
            digest = self.declaration_binding(row, declaration)
            case_id = DECLARED_SELECTOR + "[" + declaration["caseName"] + "-" + digest[:16] + "]"
            _require(row.get("id") == case_id and row.get("testId") == case_id
                     and row.get("parameterized") is True and row.get("skippedAtDiscovery") is False
                     and row.get("caseType") == "finite" and type(row.get("rotation")) is int
                     and row.get("template") == declaration["fixtureTemplate"],
                     "qa_declared_definition_invalid", "Only these fresh immutable finite parameterized declaration IDs may be submitted.")
            records.append({"caseId": case_id, "declarationDigest": digest,
                            "declaration": declaration, "definition": row})
        _require(len({record["caseId"] for record in records}) == len(records),
                 "qa_duplicate_case", "Each declaration must produce one distinct actual case ID.")
        return records

    def fixture_records(self, data, origin):
        fixtures = data.get("fixtures")
        _require(data.get("complete") is True and type(data.get("selectedTests")) is int
                 and data["selectedTests"] == len(self.records) and isinstance(fixtures, list)
                 and len(fixtures) == len(self.records), "qa_fixture_group_incomplete",
                 "Every selected declaration requires its complete actual public fixture plan.")
        expected_origin, accepted, volume = dict(origin), [], 0
        for record, fixture in zip(self.records, fixtures):
            self.declaration_binding(fixture, record["declaration"])
            view = _FlowView(self, record)
            plan = view.validate_plan(fixture, expected_origin)
            _require(plan["requestedVolume"] <= FIXTURE_VOLUME_MAX, "qa_fixture_volume_budget",
                     "Each complete actual fixture must fit the public single-fixture capacity.", unsupported=True)
            if record["declaration"]["fixtureTemplate"] == "tstqa:general_dynamic_flow_cell":
                raw = record["declaration"].get("fixtureBounds", {})
                _require(all(type(raw.get(prefix + axis)) is int for prefix in ("min", "max") for axis in "XYZ")
                         and raw.get("coordinateSpace") == "controller_relative_world"
                         and raw.get("facing") in ("NORTH", "SOUTH", "EAST", "WEST")
                         and all(-256 <= raw["min" + axis] <= raw["max" + axis] <= 256 for axis in "XYZ")
                         and plan["placedSize"] == {axis.lower(): raw["max" + axis] - raw["min" + axis] + 5 for axis in "XYZ"},
                         "qa_dynamic_fixture_geometry_changed", "Dynamic fixtures must retain the declared controller-relative world bounds and complete safety margins.")
            accepted.append(plan)
            volume += plan["requestedVolume"]
            expected_origin["z"] += plan["placedSize"]["z"] + 4
        _require(volume <= GROUP_VOLUME_MAX and volume * 2 <= self.args["maxScanCells"],
                 "qa_fixture_volume_budget", "The sum of all complete fixture volumes, twice, must fit the total before/after budget.", unsupported=True)
        return accepted, volume

    def baseline_records(self, data):
        rows = data.get("entries")
        _require(isinstance(rows, list) and len(rows) == len(self.records)
                 and type(data.get("total")) is int and data["total"] == len(rows)
                 and type(data.get("offset")) is int and data["offset"] == 0
                 and data.get("hasMore") is False and data.get("nextOffset") is None,
                 "qa_flow_baseline_unavailable", "The fresh baseline must cover each declared case completely.", unsupported=True)
        for record, row in zip(self.records, rows):
            _require(isinstance(row, dict) and type(row.get("schemaVersion")) is int and row["schemaVersion"] in (1, 2)
                     and (row["schemaVersion"] == 1 or row.get("flowContract") == ITEM_CHANCE_CONTRACT)
                     and row.get("caseId") == record["caseId"] and type(row.get("available")) is bool
                     and type(row.get("dimension")) is int and row["dimension"] == self.dimension
                     and row.get("providerClass") == self.provider_class
                     and row.get("sessionId") == self.args["expectedSessionId"],
                     "qa_flow_baseline_unavailable", "Each before-record must identify this actual case, private world, provider and session.", unsupported=True)
            record["before"] = deepcopy(row)

    async def prepare(self):
        _require(self.args.get("selector") == DECLARED_SELECTOR and isinstance(self.declarations, list)
                 and 1 <= len(self.declarations) <= 10 and all(isinstance(value, dict) for value in self.declarations)
                 and all(isinstance(value.get("caseName"), str) for value in self.declarations)
                 and len({value.get("caseName") for value in self.declarations}) == len(self.declarations),
                 "qa_machine_declarations_invalid", "One run accepts 1..10 distinct explicitly declared finite machine flows.", unsupported=True)
        catalog_args = {"section": "tests", "query": DECLARED_SELECTOR,
                        "machineFlows": self.declarations, "offset": 0, "limit": 10}
        self.client.initial_snapshot_guard = self.initial_snapshot
        await self.workflow("qa_group_preflight", [_read("snapshot", "mcb_snapshot"),
            _read("capabilities", "mcb_qa_catalog", {"section": "capabilities"}),
            _read("tests", "mcb_qa_catalog", catalog_args), _read("player", "mcb_inventory")])
        if self._native_workflow_runner:
            from .workflows import _workflow_discovery
            self._workflow_discovery = _workflow_discovery(self.client, self.client.credentials,
                self.client.latest[("bridge.health", "")])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        _require(self.dimension != 0 and self.provider_class == PRIVATE_PROVIDER,
                 "qa_declared_private_route_required", "Declared groups require the actual public-instance PersonalDimension route.", unsupported=True)
        default_origin = self.config(self.client.latest[("qa.catalog", "capabilities")])
        origin = self.args.get("origin", default_origin)
        _require(isinstance(origin, dict) and set(origin) == set("xyz")
                 and _integer(origin.get("x"), -30_000_000, 29_999_999)
                 and _integer(origin.get("y"), 3, 255) and _integer(origin.get("z"), -30_000_000, 29_999_999),
                 "qa_origin_invalid", "Declared groups require a complete bounded real allocation origin.", unsupported=True)
        self.group_origin = deepcopy(origin)
        if "origin" in self.args:
            self.requested_origin = deepcopy(origin)
        _inventory(self.client.latest[("inventory.inspect", "")])
        self.records = self.catalog_records(self.client.latest[("qa.catalog", "tests")])
        fixture_args = {"section": "fixture_plan", "query": DECLARED_SELECTOR,
                        "machineFlows": self.declarations, "origin": origin}
        data = await self.read("qa.catalog", "mcb_qa_catalog", fixture_args, section="fixture_plan")
        plans, self.fixture_volume = self.fixture_records(data, origin)
        space = []
        for record, plan in zip(self.records, plans):
            record["fixture"] = plan
            self.plan = plan
            if not self.compact_space:
                await self.empty_space(plan["reservedBounds"])
            space.append({"caseId": record["caseId"], "declarationDigest": record["declarationDigest"],
                          "fixturePlan": plan, "spaceVerificationPendingNativeRun": self.compact_space,
                          "scannedCells": 0 if self.compact_space else plan["requestedVolume"],
                          "completeLoadedLiteralAir": not self.compact_space,
                          "residualTilesAbsent": not self.compact_space, "intersectingEntitiesAbsent": not self.compact_space})
        self.preflight["fixtures"] = space
        self.preflight["scannedCells"] = 0 if self.compact_space else self.fixture_volume
        prepared_compact = self.compact_space
        baseline_args = {"section": "flow_evidence", "query": DECLARED_SELECTOR,
                         "machineFlows": self.declarations, "offset": 0, "limit": 10}
        await self.workflow("qa_group_before_run", [_read("snapshot", "mcb_snapshot"),
            _read("capabilities", "mcb_qa_catalog", {"section": "capabilities"}),
            _read("tests", "mcb_qa_catalog", catalog_args), _read("fixture", "mcb_qa_catalog", fixture_args),
            _read("player", "mcb_inventory"), _read("flow", "mcb_qa_catalog", baseline_args)])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        _require(self.config(self.client.latest[("qa.catalog", "capabilities")]) == default_origin,
                 "qa_fixture_plan_changed", "The actual allocation configuration changed before submission.")
        _require(self.compact_space is prepared_compact, "qa_fixture_coverage_contract_changed",
                 "The complete space-observation route cannot change during preparation.")
        fresh_records = self.catalog_records(self.client.latest[("qa.catalog", "tests")])
        _require([(row["caseId"], row["declarationDigest"], row["definition"]) for row in fresh_records] ==
                 [(row["caseId"], row["declarationDigest"], row["definition"]) for row in self.records],
                 "qa_declaration_changed", "The actual declared definitions changed during complete preparation.")
        fresh, volume = self.fixture_records(self.client.latest[("qa.catalog", "fixture_plan")], origin)
        _require(volume == self.fixture_volume and all(all(first.get(key) == second.get(key) for key in PLAN_KEYS)
                 for first, second in zip(plans, fresh)), "qa_fixture_plan_changed",
                 "Every actual fixture allocation must remain unchanged before the one submission.")
        _inventory(self.client.latest[("inventory.inspect", "")])
        self.baseline_records(self.client.latest[("qa.catalog", "flow_evidence")])

    def submission(self):
        return {"id": "run", "kind": "action", "tool": "mcb_qa_run",
            "args": {"expectedWorld": self.args["expectedWorld"], "selector": DECLARED_SELECTOR,
                     "origin": deepcopy(self.group_origin), "machineFlows": self.declarations},
            "fields": ["runId", "selector", "selectedTests", "selectedIds", "selectedIdsTruncated", "startStatus", "state", "correlation", "owned"]}

    def selected(self, value):
        return (value.get("selector", DECLARED_SELECTOR) == DECLARED_SELECTOR
                and type(value.get("selectedTests")) is int and value["selectedTests"] == len(self.records)
                and value.get("selectedIds") == [record["caseId"] for record in self.records]
                and value.get("selectedIdsTruncated") is False)

    def validate_run_ticket(self, value):
        try:
            canonical = str(UUID(value.get("runId", "")))
        except (ValueError, AttributeError, TypeError):
            raise _QaFailure("qa_run_ticket_unavailable", "Declared groups require the actual canonical public UUID run.") from None
        _require(canonical == value.get("runId") and self.selected(value), "qa_selection_changed",
                 "The one actual run must select exactly all fresh declaration IDs.")
        plans, volume = self.fixture_records({**value, "complete": True}, self.group_origin)
        _require(volume == self.fixture_volume and all(all(plan.get(key) == record["fixture"].get(key) for key in PLAN_KEYS)
                 for plan, record in zip(plans, self.records)), "qa_run_fixture_changed",
                 "The one submitted run must retain each same declared fixture and complete allocation.")
        if self.compact_space:
            before = {}
            for record, fixture in zip(self.records, value["fixtures"]):
                proof = self.coverage_phase(fixture.get("fixtureCoverage"), record, "before", value["runId"])
                before[record["caseId"]] = proof
            _require(not self.before_coverage or self.before_coverage == before, "qa_fixture_coverage_changed",
                     "The immutable native pre-mutation coverage changed after submission.")
            if not self.before_coverage:
                self.scan_cells += self.fixture_volume
            self.before_coverage = before
            self.preflight.update(spaceVerified=True, completeLoadedLiteralAir=True,
                residualTilesAbsent=True, intersectingEntitiesAbsent=True, scannedCells=self.fixture_volume,
                spaceVerificationPendingNativeRun=False, fixtureCoverageContract="tstqa-fixture-coverage-v1")

    def coverage_phase(self, coverage, record, phase, run_id):
        proof = coverage.get(phase) if isinstance(coverage, dict) else None
        _require(isinstance(coverage, dict) and coverage.get("contract") == "tstqa-fixture-coverage-v1"
                 and type(coverage.get("schemaVersion")) is int and coverage["schemaVersion"] == 1
                 and isinstance(proof, dict), "qa_fixture_coverage_unavailable",
                 "Every selected fixture needs the complete advertised native space coverage record.")
        volume = record["fixture"]["requestedVolume"]
        bounds = proof.get("bounds")
        _require(proof.get("contract") == "tstqa-fixture-coverage-v1"
                 and type(proof.get("schemaVersion")) is int and proof["schemaVersion"] == 1
                 and proof.get("runId") == run_id and proof.get("sessionId") == self.args["expectedSessionId"]
                 and proof.get("providerClass") == self.provider_class
                 and type(proof.get("dimension")) is int and proof["dimension"] == self.dimension
                 and proof.get("testId") == record["caseId"] and proof.get("declarationDigest") == record["declarationDigest"]
                 and isinstance(bounds, dict) and set(bounds) == set(record["fixture"]["reservedBounds"])
                 and all(type(value) is int for value in bounds.values())
                 and bounds == record["fixture"]["reservedBounds"] and proof.get("phase") == phase
                 and proof.get("airSemantics") == "minecraft_air" and proof.get("complete") is True
                 and proof.get("truncated") is False and proof.get("sampling") is False
                 and _integer(proof.get("worldTick"), 0, LONG_MAX)
                 and _integer(proof.get("observedNanos"), -LONG_MAX - 1, LONG_MAX)
                 and type(proof.get("requestedVolume")) is int and proof["requestedVolume"] == volume
                 and type(proof.get("examined")) is int and proof["examined"] == volume
                 and all(_integer(proof.get(key), 0, volume) for key in ("air", "nonAir", "loaded", "unloaded"))
                 and proof["air"] + proof["nonAir"] + proof["unloaded"] == volume
                 and proof["loaded"] == volume and proof["unloaded"] == 0,
                 "qa_fixture_coverage_invalid", "Native coverage must bind the entire loaded exact fixture, declaration, same UUID and actual private context without sampling.")
        if phase == "before":
            _require(proof["air"] == volume and proof["nonAir"] == 0
                     and type(proof.get("tileCount")) is int and proof["tileCount"] == 0
                     and type(proof.get("entityCount")) is int and proof["entityCount"] == 0
                     and proof.get("tileEntityAbsenceVerified") is True and proof.get("entityAbsenceVerified") is True
                     and proof.get("freshMutationGuardVerified") is True
                     and proof.get("allSelectedFixturesPreflightBeforeMutation") is True,
                     "qa_native_preflight_space_occupied", "Native mutation requires complete fresh literal air and actual tile/entity absence for every selected fixture.")
        else:
            self.after_coverage(proof, bounds, volume)
        return proof

    def after_coverage(self, proof, bounds, volume):
        _require(proof.get("sampling") is False and proof.get("gtTileIdentityCoverageComplete") is True
                 and proof.get("nonAirTypeCountsComplete") is True and proof.get("entityCoverageComplete") is True
                 and proof.get("terminalPublicCleanupCompleted") is True and proof.get("nativeInstanceDone") is True
                 and proof.get("nativeInstanceStatus") == "PASSED" and proof.get("ownedUnchangedFixtureVerified") is True
                 and proof.get("allSelectedFixturesPreflightBeforeMutation") is True
                 and _integer(proof.get("entityCount"), 0, volume),
                 "qa_fixture_after_identity_incomplete", "Complete after-observation must follow this actual passed instance's public cleanup and retain unchanged ownership, all tile identities, all entity coverage and no sampling.")
        tiles, gt_tiles, types = proof.get("tiles"), proof.get("gtTiles"), proof.get("nonAirTypes")
        _require(isinstance(tiles, list) and isinstance(gt_tiles, list) and isinstance(types, list)
                 and _integer(proof.get("tileCount"), 0, proof["nonAir"]) and proof["tileCount"] == len(tiles)
                 and _integer(proof.get("gtTileCount"), 0, min(128, len(tiles))) and proof["gtTileCount"] == len(gt_tiles)
                 and len(types) <= 256,
                 "qa_fixture_after_identity_incomplete", "Actual tile/GT counts must describe their complete bounded arrays once.")
        points = {}
        for row in tiles:
            _require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in "xyz")
                     and _inside(row, bounds) and isinstance(row.get("tileClass"), str) and row["tileClass"]
                     and row.get("valid") is True, "qa_fixture_after_tile_invalid",
                     "Every actual retained tile must have a valid public class and typed position in this exact fixture.")
            point = tuple(row[axis] for axis in "xyz")
            _require(point not in points, "qa_fixture_after_tile_duplicate", "Complete after-coverage cannot repeat a tile coordinate.")
            points[point] = row["tileClass"]
        gt_points = set()
        for row in gt_tiles:
            _require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in "xyz"),
                     "qa_fixture_after_gt_identity_invalid", "Every actual GT tile needs complete typed public coordinates.")
            point = tuple(row[axis] for axis in "xyz")
            _require(point in points and point not in gt_points and row.get("tileClass") == points[point]
                     and row.get("valid") is True and row.get("ownerAvailable") is True
                     and row.get("actualTileIdentityVerified") is True and _integer(row.get("metaTileId"), 0, 32_766)
                     and isinstance(row.get("runtimeClass"), str) and row["runtimeClass"]
                     and isinstance(row.get("registryName"), str) and row["registryName"]
                     and _integer(row.get("metadata"), 0, 32_766) and isinstance(row.get("unlocalizedName"), str)
                     and type(row.get("allowedToWork")) is bool and row.get("infoDataComplete") is True
                     and isinstance(row.get("infoData"), list) and len(row["infoData"]) <= 16
                     and all(line is None or isinstance(line, str) and len(line) <= 512 for line in row["infoData"]),
                     "qa_fixture_after_gt_identity_invalid", "Every actual GT tile must match the exact retained world tile and complete public owner, machine identity and non-mutating information observation.")
            gt_points.add(point)
        histogram, observed = set(), 0
        for row in types:
            _require(isinstance(row, dict) and isinstance(row.get("blockIdentity"), str)
                     and 0 < len(row["blockIdentity"]) <= 512 and row["blockIdentity"] not in histogram
                     and _integer(row.get("count"), 1, proof["nonAir"]),
                     "qa_fixture_after_histogram_invalid", "Every non-air block identity requires one complete typed count.")
            histogram.add(row["blockIdentity"])
            observed += row["count"]
        _require(observed == proof["nonAir"], "qa_fixture_after_histogram_invalid",
                 "The complete exact identity histogram must account for every observed non-air cell.")

    def compact_flow_tiles(self, record, evidence):
        proof = record["afterCoverage"]
        before = self.before_coverage[record["caseId"]]
        _require(proof["worldTick"] >= evidence["cleanupWorldTick"] >= before["worldTick"]
                 and proof["observedNanos"] >= before["observedNanos"], "qa_fixture_coverage_timing_invalid",
                 "The immutable full after-observation must follow this actual flow's completed public cleanup and fresh before-observation.")
        bounds = evidence["loadedFixtureBounds"]
        actual = {tuple(row[axis] for axis in "xyz") for row in proof["gtTiles"] if _inside(row, bounds)}
        witnessed = {tuple(row[axis] for axis in "xyz") for row in evidence["activeTiles"]}
        _require(actual == witnessed, "qa_flow_gt_tile_coverage_changed",
                 "The real advancing tick witnesses must cover every actual GT tile in the complete loaded producer fixture bounds.")
        machine = record["declaration"]["machine"]
        owners = [row for row in proof["gtTiles"] if row["registryName"] == machine["registryName"] and row["metadata"] == machine["metadata"]]
        _require(len(owners) == 1, "qa_flow_actual_machine_changed",
                 "The retained actual public machine identity must equal the one declared registered item and metadata.")
        _require(owners[0]["allowedToWork"] is False, "qa_flow_actual_machine_not_stopped",
                 "The same retained public machine must remain disabled after actual finite recovery and stopped stability.")
        if owners[0].get("controller") is True or any(stage["name"] == "native_public_blueprint_constructed" for stage in evidence["stages"]):
            owner = owners[0]
            selection = self.runtime_selection_binding(evidence, record)
            if selection is not None:
                _require(owner.get("runtimeSelectionStateAvailable") is True
                         and type(owner.get("machineMode")) is int and owner["machineMode"] == selection["actualMachineMode"]
                         and owner.get("recipeMap") == selection["actualRecipeMap"] and owner.get("recipeMapIdentityVerified") is True,
                         "qa_runtime_selection_after_changed", "The final fresh actual controller must retain the selected native mode and exact map identity.")
            _require(owner.get("controller") is True and owner.get("formed") is True and owner["allowedToWork"] is False
                     and owner.get("structureCheckInvokedBySnapshot") is False
                     and isinstance(owner.get("formationSource"), str) and owner["formationSource"]
                     and isinstance(owner.get("ports"), dict), "qa_flow_actual_structure_unverified",
                     "The retained actual public multiblock controller must remain formed and stopped with complete real port observations.")
            ports = owner["ports"]
            _require(set(ports) == {"energy", "maintenance", "itemInput", "itemOutput", "fluidInput", "fluidOutput", "muffler"}
                     and all(isinstance(rows, list) for rows in ports.values()), "qa_flow_actual_ports_incomplete",
                     "Every public formed port domain must remain an explicit complete array.")
            for rows in ports.values():
                observed_ports = set()
                for row in rows:
                    _require(isinstance(row, dict) and all(type(row.get(axis)) is int for axis in "xyz")
                             and tuple(row[axis] for axis in "xyz") in actual and row.get("valid") is True
                             and row.get("actualOwnerIdentityVerified") is True and _integer(row.get("metaTileId"), 0, 32_766)
                             and isinstance(row.get("tileClass"), str) and isinstance(row.get("runtimeClass"), str),
                             "qa_flow_actual_ports_incomplete", "Every actual public port must still resolve to a witnessed valid GT owner in this same complete fixture.")
                    point = tuple(row[axis] for axis in "xyz")
                    actual_owner = next(tile for tile in proof["gtTiles"] if tuple(tile[axis] for axis in "xyz") == point)
                    _require(point not in observed_ports and all(row[key] == actual_owner[key] for key in ("metaTileId", "tileClass", "runtimeClass")),
                             "qa_flow_actual_ports_incomplete", "Every unique formed public port reference must retain that exact observed world's GT owner identity.")
                    observed_ports.add(point)
            item_receipt = evidence.get("publicItemOutputBindings")
            raw_item_count = len(record["declaration"]["recipe"]["outputs"])
            if item_receipt is not None or raw_item_count == 0:
                count = len(ports["itemOutput"])
                _require(isinstance(item_receipt, dict) and count in (0, 1)
                         and type(item_receipt.get("physicalItemOutputPortCount")) is int
                         and item_receipt["physicalItemOutputPortCount"] == count
                         and item_receipt.get("allPhysicalOutputsBound") is True
                         and item_receipt.get("virtualOutputInventory") is False
                         and item_receipt.get("missingDescriptionMeansRoleAbsent") is False
                         and type(item_receipt.get("nativeRawItemOutputCount")) is int
                         and item_receipt["nativeRawItemOutputCount"] == raw_item_count
                         and type(item_receipt.get("declaredRawItemOutputCount")) is int
                         and item_receipt["declaredRawItemOutputCount"] == raw_item_count
                         and type(item_receipt.get("itemOutputRequired")) is bool
                         and item_receipt["itemOutputRequired"] is (raw_item_count > 0)
                         and (count == 1 or raw_item_count == 0),
                         "qa_flow_item_output_coverage_invalid", "Zero physical item outputs require exact empty declared/native raw products and complete actual public membership evidence.")
                actual_outputs = {tuple(row[axis] for axis in "xyz") for row in ports["itemOutput"]}
                _require(all({tuple(binding["position"][axis] for axis in "xyz") for binding in snapshot["items"]
                              if binding["binding"] == "output"} == actual_outputs for snapshot in evidence["snapshots"]),
                         "qa_flow_item_output_coverage_invalid", "Every real item output, including explicit absence, must match complete physical checkpoint bindings through recovery and stability.")
            if evidence.get("schemaVersion") == 2:
                ledger = evidence["finiteOutputLedger"]
                expected_items = {tuple(ledger["itemOutputPosition"][axis] for axis in "xyz")}
                expected_fluids = {tuple(ledger["fluidOutputPosition"][axis] for axis in "xyz")} if ledger["fluidOutputPortCount"] else set()
                _require(tuple(ledger["controllerPosition"][axis] for axis in "xyz") == tuple(owner[axis] for axis in "xyz")
                         and {tuple(row[axis] for axis in "xyz") for row in ports["itemOutput"]} == expected_items
                         and {tuple(row[axis] for axis in "xyz") for row in ports["fluidOutput"]} == expected_fluids,
                         "qa_chance_output_coverage_invalid", "Final complete actual controller/output ports must equal every ledger-bound output, including zero/unknown paths.")
            multi = evidence.get("publicMultiFluidBindings")
            if multi is not None:
                _require(isinstance(multi, dict) and multi.get("contract") == "native_deterministic_multi_fluid_v1"
                         and evidence.get("schemaVersion") == 1
                         and type(multi.get("nativeRawFluidInputCount")) is int
                         and multi["nativeRawFluidInputCount"] == len(record["declaration"]["recipe"]["fluidInputs"])
                         and type(multi.get("nativeRawFluidOutputCount")) is int
                         and multi["nativeRawFluidOutputCount"] == len(record["declaration"]["recipe"]["fluidOutputs"]),
                         "qa_multi_fluid_bindings_invalid", "The deterministic multi-fluid receipt must corroborate the complete exact raw declaration.")
                for role, port_key in (("input", "fluidInput"), ("output", "fluidOutput")):
                    _require({tuple(point[axis] for axis in "xyz") for point in multi[role + "Positions"]}
                             == {tuple(point[axis] for axis in "xyz") for point in ports[port_key]},
                             "qa_multi_fluid_final_ports_changed", "The final complete actual controller ports must match every bound physical input/output.")
                _require(all(tuple(point[axis] for axis in "xyz") in actual for point in multi["receiverPositions"]),
                         "qa_multi_fluid_final_ports_changed", "Every ordinary physical receiver must remain in the complete witnessed GT tile coverage.")

    def fixture_after_summary(self, record, evidence):
        """Only called after complete after-coverage, same-run flow and physical tile validation."""
        proof = record["afterCoverage"]
        machine = record["declaration"]["machine"]
        owner = next(tile for tile in proof["gtTiles"] if all(tile[key] == machine[key] for key in ("registryName", "metadata")))
        controller_keys = ("x", "y", "z", "registryName", "metadata", "runtimeClass", "allowedToWork", "controller", "formed",
                           "formationSource", "structureCheckInvokedBySnapshot", "machineMode", "recipeMap", "recipeMapIdentityVerified",
                           "runtimeSelectionStateAvailable", "infoData", "infoDataComplete", "ports")
        types = deepcopy(proof["nonAirTypes"])
        histogram_hash = hashlib.sha256(json.dumps(types, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        ticks = {stage["name"]: stage["worldTick"] for stage in evidence["stages"]}
        return {"scope": "validated_complete_fresh_final_fixture_after_cleanup_and_stability", "completeValidationPassed": True,
                "runId": proof["runId"], "sessionId": proof["sessionId"], "caseId": record["caseId"],
                "declarationDigest": proof["declarationDigest"], "worldTick": proof["worldTick"], "bounds": deepcopy(proof["bounds"]),
                "examined": proof["examined"], "nonAir": proof["nonAir"], "nonAirTypes": types,
                "nonAirTypeCount": len(types), "nonAirTypesSha256": histogram_hash, "nonAirTypeCountsComplete": True,
                "controller": {key: deepcopy(owner[key]) for key in controller_keys if key in owner},
                "measuredPerformance": {"scope": "observed_real_normal_world_ticks", "worldTicks": evidence["worldTicks"],
                    "elapsedNanos": evidence["elapsedNanos"], "finiteSupplyWorldTick": ticks["finite_inputs_supplied"],
                    "processingStartObservedWorldTick": ticks["processing_start_observed"],
                    "productionCompleteWorldTick": ticks["production_complete_and_stopped"],
                    "supplyToStoppedProductionTicks": ticks["production_complete_and_stopped"] - ticks["finite_inputs_supplied"],
                    "effectiveParallelOrOverclockInferred": False}, "structureMaterialProgressionVerified": False}

    def selection_verified(self):
        ticket, status = self.client.ticket or {}, self.terminal or {}
        return (bool(ticket.get("runId")) and self.selected(ticket),
                status.get("runId") == ticket.get("runId") and self.selected(status)
                and status.get("freshSinceStart") is True and status.get("nativeBatchActive") is False
                and status.get("selectionMatches") is True and status.get("state") == "completed")

    def case_passed(self, case_id):
        matching = [row for row in self.cases if row.get("id") == case_id]
        return (all(self.selection_verified()) and self.reports_complete and self.report_coherent
                and self.native_report_passed()
                and len(self.cases) == len(self.records)
                and {row.get("id") for row in self.cases} == {record["caseId"] for record in self.records}
                and len(matching) == 1 and matching[0].get("status") == "PASSED" and not self.issues)

    def native_report_passed(self):
        report = (self.terminal or {}).get("nativeReport", {})
        count = len(self.records)
        return (count > 0 and report.get("status") == "PASS" and report.get("complete") is True
                and type(report.get("exitCode")) is int and report["exitCode"] == 0
                and type(report.get("selectedTests")) is int and report["selectedTests"] == count
                and type(report.get("passed")) is int and report["passed"] == count
                and all(type(report.get(key)) is int and report[key] == 0 for key in COUNT_FIELDS))

    def test_passed(self):
        return bool(self.records) and all(self.case_passed(record["caseId"]) for record in self.records)

    async def after(self):
        await self.workflow("qa_group_after_read", [_read("snapshot", "mcb_snapshot"), _read("player", "mcb_inventory")])
        self.snapshot(self.client.latest[("client.snapshot", "")])
        _inventory(self.client.latest[("inventory.inspect", "")])
        scans = []
        for record in self.records:
            if self.compact_space:
                matches = [row for row in self.cases if row.get("id") == record["caseId"]]
                _require(len(matches) == 1, "qa_case_coverage_invalid", "Every fixture needs exactly one same-run case coverage record.")
                coverage = matches[0].get("fixtureCoverage")
                before = self.coverage_phase(coverage, record, "before", self.run_id())
                _require(before == self.before_coverage.get(record["caseId"]), "qa_fixture_coverage_changed",
                         "Case reports must retain the same immutable native pre-mutation proof.")
                scan = self.coverage_phase(coverage, record, "after", self.run_id())
                record["afterCoverage"] = scan
                self.scan_cells += record["fixture"]["requestedVolume"]
            else:
                scan = await self.scan(record["fixture"]["reservedBounds"], require_air=False)
            scans.append({"caseId": record["caseId"], "declarationDigest": record["declarationDigest"], **scan})
        self.after_scan = {"complete": True, "scannedCells": self.fixture_volume, "fixtures": scans}
        fresh = await self.read("qa.status", "mcb_qa_status", {"runId": self.run_id()})
        self.validate_run_ticket(fresh)
        self.report_coherent = fresh.get("nativeReport") == (self.terminal or {}).get("nativeReport")
        _require(self.report_coherent, "qa_report_changed", "The same UUID report changed during complete after-observation.")
        self.terminal = dict(fresh)
        self.verify_group_flows()

    def verify_group_flows(self):
        self.flow_results = []
        for record in self.records:
            matches = [row for row in self.cases if row.get("id") == record["caseId"]]
            result = {"caseId": record["caseId"], "declarationDigest": record["declarationDigest"], "verified": False}
            try:
                _require(len(matches) == 1, "qa_case_coverage_invalid", "Every selected case must occur once in the same UUID report.")
                self.declaration_binding(matches[0], record["declaration"])
                evidence = matches[0].get("flowEvidence")
                self.declaration_binding(evidence, record["declaration"], definition=False)
                self.recipe_binding(evidence, record["declaration"])
                selection = self.runtime_selection_binding(evidence, record)
                view = _FlowView(self, record)
                view.verify_flow(evidence)
                if evidence.get("publicMultiFluidBindings") is not None:
                    _require(self.compact_space, "qa_multi_fluid_final_coverage_required", "Expanded physical fluid bindings require the validated complete final controller and GT-owner fixture coverage.")
                if (view.full_flow.get("verified") is True or view.full_flow.get("actualDepositRecoveryVerified") is True) and self.compact_space:
                    self.compact_flow_tiles(record, evidence)
                result.update(view.full_flow)
                if view.full_flow.get("verified") is True:
                    result["quantitativeSummary"] = view.quantitative_summary
                    if self.compact_space:
                        result["fixtureAfterSummary"] = self.fixture_after_summary(record, evidence)
                    if selection is not None:
                        result["runtimeSelectionVerified"] = True
                        result["runtimeSelection"] = {key: deepcopy(selection[key]) for key in
                            ("actualMachineMode", "actualRecipeMap", "channels", "channelStorageVerified",
                             "nativeChannelConsumptionVerified", "structureMaterialProgressionVerified")}
            except _QaFailure as error:
                result.update(errorCode=error.code, reason=error.message)
            self.flow_results.append(result)
        verified = self.test_passed() and len(self.flow_results) == len(self.records) and all(row.get("verified") is True for row in self.flow_results)
        self.full_flow = {"available": bool(self.flow_results), "verified": verified, "scope": "api_receiver",
                          "nativeRunOwned": False, "groupCaseCount": len(self.records)}

    def accept(self):
        _require(all(self.selection_verified()), "qa_selection_changed", "The actual completed UUID run must retain the exact declared group.")
        _require(self.report_semantics_verified(), "qa_native_test_failed",
                 "Every case and the complete coherent same-run native report must pass without infrastructure issues or missing semantic records.")
        _require(self.after_scan.get("complete") is True and self.scan_cells == self.fixture_volume * 2,
                 "qa_fixture_after_incomplete", "Every disjoint fixture requires its entire before and after space observation.")
        _require(self.full_flow.get("verified") is True, "qa_group_full_flow_unverified",
                 "All declared machines must pass the established actual finite-flow and 85-tick verifier.")

    async def release_native_capture(self):
        if not self.native_cache_release_supported:
            return
        self.resource_release_attempted = True
        try:
            await self.workflow("qa_group_cache_release", [{"id": "forget", "kind": "action", "tool": "mcb_qa_command",
                "args": {"command": "forget", "args": [self.run_id()], "expectedWorld": self.args["expectedWorld"]},
                "fields": ["runId", "forgotten", "worldPreserved", "removedBlocks", "cachedCoverageRemoved",
                           "adapterTicketsReleased", "retainedRuns", "maxRetainedRuns"]}])
            value = self.client.latest[("qa.command", "")]
            _require(value.get("runId") == self.run_id() and value.get("forgotten") is True
                     and value.get("worldPreserved") is True and type(value.get("removedBlocks")) is int
                     and value["removedBlocks"] == 0 and value.get("cachedCoverageRemoved") is True
                     and value.get("adapterTicketsReleased") is True and _integer(value.get("retainedRuns"), 0, 31)
                     and type(value.get("maxRetainedRuns")) is int and value["maxRetainedRuns"] == 32,
                     "qa_cache_release_unverified", "Cache release must bind this exact completed UUID and preserve every actual world block.")
            self.resource_release = True
            self.resource_release_receipt = {key: value[key] for key in ("runId", "forgotten", "worldPreserved",
                "removedBlocks", "cachedCoverageRemoved", "adapterTicketsReleased", "retainedRuns", "maxRetainedRuns")}
        except (BridgeError, _QaFailure, TimeoutError) as error:
            self.resource_release_error = {"code": getattr(error, "code", "qa_cache_release_timeout"), "message": str(error)[:512]}

    def result(self, failure=None):
        data = super().result(failure)
        data["groupFullFlowVerified"] = (data["fullFlowVerified"] and self.full_flow.get("verified") is True
            and self.reports_complete and self.after_scan.get("complete") is True)
        data["machineFlowCount"] = len(self.records)
        data["diagnosticsComplete"] = self.diagnostics_complete and self.reports_complete
        data.update(resourceRelease=self.resource_release, resourceReleaseAttempted=self.resource_release_attempted,
            nativeCacheReleaseSupported=self.native_cache_release_supported)
        if self.resource_release_receipt is not None:
            data["resourceReleaseReceipt"] = self.resource_release_receipt
        if self.resource_release_error is not None:
            data["resourceReleaseError"] = self.resource_release_error
        data["groupLimits"] = {"maxSelectedTests": 10, "maxReservedAxis": 263,
            "maxFixtureReservedCells": FIXTURE_VOLUME_MAX, "maxBatchReservedCells": GROUP_VOLUME_MAX,
            "maxScanCells": self.args["maxScanCells"], "maxCapturedReportBytes": self.client.maximum_capture_bytes,
            "capturedReportBytes": self.client.bytes, "maxRequests": self.client.maximum,
            "maxReportPages": self.args.get("maxReportPages", 32), "reportPageSize": self.args["reportPageSize"],
            "effectiveReportPageSize": self.report_page_limit, "responseByteBudgetRetries": self.report_byte_retries}
        data["groupLimits"].update(maxRawFluidInputs=8, maxRawFluidOutputs=8, maxPhysicalFluidInputs=16,
            maxPhysicalFluidOutputs=16, maxPhysicalFluidReceivers=16, maxFiniteFluidCheckpointBindings=48)
        data["flowResults"] = [dict(row) for row in self.flow_results]
        if not self.args.get("includeDetails", False):
            for row in data["flowResults"]:
                row.pop("observation", None)
        # Prefer the useful per-machine exact quantitative receipt over repeated
        # plan declarations and duplicate native fixture records in the output.
        for key in ("preflight", "afterScan", "phases"):
            if json_size(data) <= data["maxReturnBytes"] - 512:
                break
            if key in data:
                data.pop(key)
                data["outputOmitted"] = True
                data.setdefault("omittedFields", []).append(key)
        if json_size(data) > data["maxReturnBytes"] - 512 and isinstance(data.get("runTicket"), dict):
            data["runTicket"] = {key: item for key, item in data["runTicket"].items() if key in
                ("runId", "selector", "selectedTests", "selectedIds", "selectedIdsTruncated", "startStatus", "state", "correlation", "owned")}
            data["outputOmitted"] = True
            data.setdefault("omittedFields", []).append("runTicket.otherFields")
        if json_size(data) > data["maxReturnBytes"] - 512:
            for row in data["flowResults"]:
                row.pop("observation", None)
            data["outputOmitted"] = True
            data.setdefault("omittedFields", []).append("flowResults.observation")
        if json_size(data) > data["maxReturnBytes"] - 512:
            data["flowResults"] = [{key: row[key] for key in ("caseId", "declarationDigest", "verified", "evidenceId", "errorCode", "quantitativeSummary", "fixtureAfterSummary", "runtimeSelection") if key in row}
                                   for row in data["flowResults"]]
            data.setdefault("omittedFields", []).append("flowResults.otherFields")
        if json_size(data) > data["maxReturnBytes"] - 512:
            for row in data["flowResults"]:
                summary = row.get("fixtureAfterSummary")
                if summary is not None:
                    controller = summary["controller"]
                    for field in ("ports", "infoData"):
                        if field in controller:
                            removed = controller.pop(field)
                            controller[field + "Sha256"] = hashlib.sha256(json.dumps(removed, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                            controller[field + "ProjectedComplete"] = False
            data["outputOmitted"] = True
            data.setdefault("omittedFields", []).append("flowResults.fixtureAfterSummary.controller_large_details")
        if json_size(data) > data["maxReturnBytes"] - 512:
            summaries = [row["fixtureAfterSummary"] for row in data["flowResults"] if "fixtureAfterSummary" in row]
            for summary in sorted(summaries, key=lambda value: json_size(value.get("nonAirTypes", [])), reverse=True):
                if json_size(data) <= data["maxReturnBytes"] - 512:
                    break
                if json_size(summary.get("nonAirTypes", [])) > 1024:
                    summary.pop("nonAirTypes", None)
                    summary.update(nonAirTypeCountsComplete=False, nonAirTypesOmittedReason="validated_complete_histogram_exceeds_return_budget")
                    data.setdefault("omittedFields", []).append("flowResults.fixtureAfterSummary.nonAirTypes")
        if json_size(data) > data["maxReturnBytes"] - 512:
            for row in data["flowResults"]:
                if "quantitativeSummary" in row:
                    row.pop("quantitativeSummary")
                    row["quantitativeSummaryOmittedReason"] = "complete_exact_summary_exceeds_max_return_bytes"
            data.setdefault("omittedFields", []).append("flowResults.quantitativeSummary")
        if json_size(data) > data["maxReturnBytes"] - 512:
            data["fixtureAfterSummaryProjection"] = {"scope": "validated_complete_fresh_final_fixture_after_cleanup_and_stability",
                "runId": self.run_id(), "sessionId": self.args["expectedSessionId"],
                "context": "Each flow result supplies its case and declaration digest; measured performance uses observed normal world ticks."}
            for row in data["flowResults"]:
                summary = row.get("fixtureAfterSummary")
                if summary is not None:
                    for key in ("runId", "sessionId", "caseId", "declarationDigest", "scope"):
                        summary.pop(key, None)
                    summary["controller"] = {key: value for key, value in summary["controller"].items() if key in
                        ("x", "y", "z", "registryName", "metadata", "runtimeClass", "allowedToWork", "controller", "formed", "formationSource", "machineMode", "recipeMap", "recipeMapIdentityVerified", "portsProjectedComplete", "infoDataProjectedComplete")}
                    summary["measuredPerformance"] = {key: value for key, value in summary["measuredPerformance"].items() if key in
                        ("worldTicks", "elapsedNanos", "supplyToStoppedProductionTicks", "effectiveParallelOrOverclockInferred")}
                row.pop("quantitativeSummaryOmittedReason", None)
                row.pop("evidenceId", None)
            data["quantitativeSummaryOmittedReason"] = "complete_exact_summary_exceeds_max_return_bytes"
            data.setdefault("omittedFields", []).append("flowResults.fixtureAfterSummary.repeated_context_and_controller_details")
        # Group serialization is also inside the single task clock.
        data["wholeTaskElapsed_ms"] = (time.monotonic() - self.started) * 1000
        if failure is None and data["wholeTaskElapsed_ms"] > WHOLE_BUDGET_MS:
            data.update(status="failed", completeWithin10s=False,
                error={"code": "qa_whole_budget_exceeded", "message": "The complete QA group exceeded 10 seconds.", "phase": "whole_acceptance"})
            for row in data["flowResults"]:
                row.pop("quantitativeSummary", None)
                row.pop("fixtureAfterSummary", None)
        return data

    def release_capture(self, data, failure):
        # These are task-local DTO caches. Detach references, without mutating
        # any accepted rows shared by the returned receipt. Nothing is retained
        # by the reusable BridgeClient after completion, failure or cancellation.
        self.client.latest.clear()
        self.client.result_pages.clear()
        self.client.guard = self.client.run_guard = self.client.qa_guard = self.client.initial_snapshot_guard = None
        self.client.ticket = None
        self.records, self.cases, self.issues, self.flow_results = [], [], [], []
        self.before_coverage, self.preflight, self.after_scan = {}, {}, {}
        self.terminal = self.case = self.plan = self.flow_before = self.quantitative_summary = None
        self.full_flow, self._scan_health, self._workflow_discovery = {}, None, None
        data["groupCaptureReleased"] = True
        data["wholeTaskElapsed_ms"] = (time.monotonic() - self.started) * 1000
        if failure is None and data["wholeTaskElapsed_ms"] > WHOLE_BUDGET_MS:
            data.update(status="failed", completeWithin10s=False,
                error={"code": "qa_whole_budget_exceeded", "message": "The complete QA group exceeded 10 seconds.", "phase": "whole_acceptance"})
            for row in data["flowResults"]:
                row.pop("quantitativeSummary", None)
                row.pop("fixtureAfterSummary", None)
