"""Protocol conservation, exact subsets and unknown-vs-zero generated evidence; no RNG simulation."""
from copy import deepcopy

import jsonschema
import pytest

from minecraft_codex_bridge import qa_tasks as qa
from minecraft_codex_bridge import qa_machine_flows as group
from minecraft_codex_bridge.qa_flow_schema import DECLARED_MACHINE_FLOWS_SCHEMA
from test_qa_quantitative_summary import accepted_fixture, item, inventory_binding, tank_binding, position, IRON, GOLD, ORE, CATALYST
from test_qa_machine_flows import declaration, fixtures, receipt, GroupClient, run


def probability_declaration(*, zero=False, fluids=False):
    value = declaration(dynamic=True)
    value["recipe"].update(outputs=[{"registryName": IRON, "metadata": 0, "count": 2},
        {"registryName": IRON, "metadata": 0, "count": 3}, {"registryName": GOLD, "metadata": 0, "count": 11}],
        outputChances=[0 if zero else 10000, 5000, 0])
    if not fluids:
        value["recipe"].update(fluidInputs=[], fluidOutputs=[])
    return value


def apply_probability(value, submitted, *, actual=2, pending="observed"):
    recipe = submitted["recipe"]
    old = value["snapshots"]
    inp = deepcopy(old[0]["items"][0]["position"])
    recv = deepcopy(old[0]["items"][1]["position"])
    outpos = {**inp, "x": inp["x"] + 2}
    fin = deepcopy(old[0]["fluids"][0]["position"])
    frecv = deepcopy(old[0]["fluids"][1]["position"])
    fout = {**fin, "x": fin["x"] + 2}
    controller = {**inp, "x": inp["x"] + 7}
    def inventory(name, pos, rows):
        obj = inventory_binding(name, pos["x"], rows);obj["position"] = pos;return obj
    def tank(name, pos, row=None):
        obj = tank_binding(name, pos["x"], row);obj["position"] = pos;return obj
    products = [item(IRON, actual)] if actual else []
    catalysts = [item(CATALYST, 1)]
    native_fluids = deepcopy(value["declaredRecipe"]["fluidOutputs"]) if recipe["fluidOutputs"] else []
    value["machineFlowDeclaration"] = receipt(submitted)["machineFlowDeclaration"]
    value.update(schemaVersion=2, flowContract=qa.ITEM_CHANCE_CONTRACT)
    value["declaredRecipe"].update(itemOutputs=[item(IRON, 5), item(GOLD, 11)], rawItemOutputs=[
        {**item(row["registryName"], row["count"]), "outputIndex": i, "chance": recipe["outputChances"][i]}
        for i, row in enumerate(recipe["outputs"])])
    if not recipe["fluidInputs"]:
        value["declaredRecipe"].update(fluidInputs=[], fluidOutputs=[])
    for snapshot in old:
        stage = snapshot["stage"]
        source = [item(ORE, 7), *catalysts] if stage == "supplied" else catalysts if stage == "produced" else []
        receiver = catalysts + products if stage in ("recovered", "stable") else []
        snapshot["items"] = [inventory("finite_input", inp, source), inventory("output", outpos, products if stage == "produced" else []),
            inventory("item_receiver", recv, receiver)]
        fluids = [tank("fluid_receiver", frecv, native_fluids[0] if native_fluids and stage in ("recovered", "stable") else None)]
        if recipe["fluidInputs"]:
            fluids.insert(0, tank("finite_fluid_input", fin, value["declaredRecipe"]["fluidInputs"][0] if stage == "supplied" else None))
        if native_fluids:
            fluids.insert(-1, tank("output", fout, native_fluids[0] if stage == "produced" else None))
        snapshot["fluids"] = fluids
    ticks = {s["name"]: s["worldTick"] for s in value["stages"]}
    generated = {"status": pending}
    if pending == "observed":
        generated.update(items=deepcopy(products), fluids=deepcopy(native_fluids), maxProgress=1,
            worldTick=ticks["processing_start_observed"], recipesDoneAtCapture=42, processingObserved=True,
            samplePhase="normal_server_END_before_sequence")
    ledger = {"contract": qa.ITEM_CHANCE_CONTRACT, **{key: value[key] for key in ("caseId", "runId", "sessionId", "dimension")},
        "declarationDigest": value["machineFlowDeclaration"]["declarationDigest"], "capacityPolicy": "maximum_raw_potential_plus_physical_catalysts",
        "probabilityDistributionVerified": False, "actualFrozen": True, "completedIdle": True, "inputsConsumed": True,
        "recipesDoneBaseline": 42, "recipesDoneFinal": 43, "recipesDoneDelta": 1, "recipesDoneStable": 43,
        "depositedWorldTick": ticks["production_complete_and_stopped"], "stableWorldTick": ticks["normal_world_stability_verified"],
        "controllerPosition": deepcopy(controller), "itemOutputPosition": deepcopy(outpos), "allOutputPortsCovered": True,
        "itemOutputPortCount": 1, "fluidOutputPortCount": int(bool(native_fluids)),
        "depositedItems": deepcopy(products), "depositedFluids": deepcopy(native_fluids), "nativeGenerated": generated,
        "generationToDepositVerified": pending == "observed", "productRecoveryExercised": bool(products or native_fluids)}
    if native_fluids:
        ledger["fluidOutputPosition"] = deepcopy(fout)
    value["finiteOutputLedger"] = ledger
    return value


def chance_fixture(**options):
    value = probability_declaration(zero=options.pop("zero", False), fluids=options.pop("fluids", False))
    task, evidence, calls = accepted_fixture(fluids=bool(value["recipe"]["fluidInputs"]))
    apply_probability(evidence, value, **options)
    return task, evidence, calls


@pytest.mark.parametrize("actual,zero,fluids", [(2, False, False), (5, False, False), (0, True, False), (3, True, False), (2, False, True)])
def test_complete_exact_actual_ledger_and_zero_receipt(actual, zero, fluids):
    task, evidence, calls = chance_fixture(actual=actual, zero=zero, fluids=fluids)
    task.verify_flow(evidence);task.accept()
    result = task.result()
    assert result["fullFlowVerified"] and not calls
    summary = result["quantitativeSummary"]
    assert summary["recipesDoneDelta"] == 1 and summary["nativeGeneratedStatus"] == "observed"
    assert summary["generationToDepositVerified"] and summary["probabilityDistributionVerified"] is False
    assert summary["productRecoveryExercised"] == bool(actual or fluids)
    assert summary["actualItemOutputs"] == summary["itemOutputs"]
    assert summary["potentialItemOutputs"] != summary["actualItemOutputs"]
    assert summary["rawItemOutputs"][1]["count"] == 3 and summary["rawItemOutputs"][1]["chance"] == 5000
    assert summary["stableTicks"] == 85


@pytest.mark.parametrize("actual", [0, 1, 3, 4, 6])
def test_bounds_alone_cannot_accept_same_identity_subset(actual):
    task, evidence, _ = chance_fixture(actual=actual)
    task.verify_flow(evidence)
    assert not task.full_flow["verified"] and task.full_flow["errorCode"] == "qa_chance_actual_illegal"


@pytest.mark.parametrize("zero", [False, True])
def test_unknown_pending_keeps_actual_scope_without_full_proof(zero):
    task, evidence, _ = chance_fixture(zero=zero, actual=0 if zero else 2, pending="unknown")
    task.verify_flow(evidence)
    assert not task.full_flow["verified"] and task.full_flow["errorCode"] == "qa_chance_native_generated_unknown"
    assert task.full_flow["actualDepositRecoveryVerified"]
    assert task.full_flow["partialEvidence"]["nativeGeneratedStatus"] == "unknown"
    assert task.full_flow["partialEvidence"]["productRecoveryExercised"] is (not zero)
    assert task.quantitative_summary is None


@pytest.mark.parametrize("fault", ["chance", "raw_count", "index", "digest", "delta0", "delta2", "stable_counter", "pending_loss", "pending_unknown_empty",
    "pending_window", "pending_not_processing", "pending_nbt", "unknown_product", "actual_lost", "extra_port", "uncovered_port", "wrong_port_position", "wrong_port", "stable", "false_zero_recovery", "schema1", "fluid"])
def test_invalid_raw_native_counter_deposit_recovery_or_stability_refuses(fault):
    task, evidence, _ = chance_fixture(fluids=True)
    ledger = evidence["finiteOutputLedger"]
    if fault == "chance": evidence["declaredRecipe"]["rawItemOutputs"][1]["chance"] = 10000
    elif fault == "raw_count": evidence["declaredRecipe"]["rawItemOutputs"][1]["count"] = 4
    elif fault == "index": evidence["declaredRecipe"]["rawItemOutputs"][0]["outputIndex"] = 1
    elif fault == "digest": ledger["declarationDigest"] = "0" * 64
    elif fault == "delta0": ledger["recipesDoneFinal"] = 42
    elif fault == "delta2": ledger["recipesDoneFinal"] = 44
    elif fault == "stable_counter": ledger["recipesDoneStable"] = 44
    elif fault == "pending_loss": ledger["nativeGenerated"]["items"] = [item(IRON, 5)]
    elif fault == "pending_unknown_empty": ledger["nativeGenerated"]["status"] = "unknown"
    elif fault == "pending_window": ledger["nativeGenerated"]["recipesDoneAtCapture"] = 43
    elif fault == "pending_not_processing": ledger["nativeGenerated"]["maxProgress"] = 0
    elif fault == "pending_nbt": ledger["nativeGenerated"]["items"][0]["nbt"] = "{changed:1}"
    elif fault == "unknown_product": ledger["depositedItems"][0]["registryName"] = "test:unknown"
    elif fault == "actual_lost": evidence["snapshots"][2]["items"][1]["slots"][0]["count"] = 0
    elif fault == "extra_port": ledger["itemOutputPortCount"] = 2
    elif fault == "uncovered_port": ledger["allOutputPortsCovered"] = False
    elif fault == "wrong_port_position": ledger["itemOutputPosition"]["x"] += 1
    elif fault == "wrong_port":
        for snapshot in evidence["snapshots"]:
            snapshot["items"][0]["slots"], snapshot["items"][1]["slots"] = snapshot["items"][1]["slots"], snapshot["items"][0]["slots"]
    elif fault == "stable": evidence["snapshots"][-1]["items"][-1]["slots"][1]["count"] += 1
    elif fault == "false_zero_recovery": ledger["productRecoveryExercised"] = False
    elif fault == "schema1": evidence["schemaVersion"] = 1
    elif fault == "fluid": ledger["depositedFluids"][0]["amount"] -= 1
    task.verify_flow(evidence)
    assert not task.full_flow["verified"] and task.quantitative_summary is None


@pytest.mark.parametrize("chances", [[5000], [10000, 5000, 0, 1], [-1, 5000, 0], [10001, 5000, 0], [True, 5000, 0], ["10000", 5000, 0]])
def test_schema_refuses_chance_length_range_or_type(chances):
    value = probability_declaration();value["recipe"]["outputChances"] = chances
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)


def test_basic_and_manufacturing_remain_deterministic():
    value = probability_declaration();value["fixtureTemplate"] = "tstqa:core_flow_cell";value.pop("fixtureBounds")
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)
    value = probability_declaration();value["upgrades"] = [{"item": value["machine"], "manufacturingRecipe": value["recipe"], "expectedFeedbackLines": ["exact"]}]
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)


def test_deterministic_schema1_stays_exact_and_summary_is_immutable():
    task, evidence, _ = accepted_fixture();task.verify_flow(evidence);assert task.full_flow["verified"]
    evidence["declaredRecipe"]["itemOutputs"][0]["count"] -= 1
    task.verify_flow(evidence);assert not task.full_flow["verified"]
    task, evidence, _ = chance_fixture();task.verify_flow(evidence)
    saved = deepcopy(task.quantitative_summary)
    evidence["declaredRecipe"]["rawItemOutputs"][1]["chance"] = 1
    evidence["finiteOutputLedger"]["depositedItems"][0]["count"] = 999
    assert task.quantitative_summary == saved


class ProbabilityClient(GroupClient):
    def __init__(self, *, zero=False, pending="observed", fault=None):
        super().__init__(1, dynamic=True)
        self.values = [probability_declaration(zero=zero, fluids=True)]
        self.records = fixtures(self.values)
        self.zero, self.pending, self.fault = zero, pending, fault

    async def call(self, operation, args, **kwargs):
        result = await super().call(operation, args, **kwargs)
        if operation == "qa.results" and args["section"] == "cases":
            for row in result["entries"]:
                value = row["flowEvidence"]
                apply_probability(value, self.values[0], actual=0 if self.zero else 2, pending=self.pending)
                value["stages"][2]["name"] = "native_public_blueprint_constructed"
                proof = row["fixtureCoverage"]["after"]
                ledger = value["finiteOutputLedger"]
                owner = proof["gtTiles"][0]
                owner.update(ledger["controllerPosition"])
                proof["tiles"][0].update(ledger["controllerPosition"])
                value["activeTiles"][0].update(ledger["controllerPosition"])
                for i, (domain, pos) in enumerate((('itemOutput', ledger["itemOutputPosition"]), ('fluidOutput', ledger["fluidOutputPosition"]))):
                    tile = {**pos, "tileClass": "test.GTTile", "valid": True}
                    port = {**tile, "metaTileId": 1000 + i, "runtimeClass": "test.OutputPort", "actualOwnerIdentityVerified": True}
                    native = {**port, "registryName": "gregtech:gt.blockmachines", "metadata": 1000 + i,
                        "ownerAvailable": True, "actualTileIdentityVerified": True, "unlocalizedName": "test.port", "allowedToWork": False,
                        "infoData": [], "infoDataComplete": True}
                    owner["ports"][domain] = [port]
                    proof["tiles"].append(tile);proof["gtTiles"].append(native)
                    value["activeTiles"].append({**pos, "startTimer": 100, "endTimer": 101})
                proof.update(tileCount=3, gtTileCount=3, nonAir=3, air=proof["requestedVolume"] - 3)
                proof["nonAirTypes"][0]["count"] = 3
                if self.fault:
                    self.fault(row)
        return result


@pytest.mark.parametrize("zero", [False, True])
async def test_same_uuid_transport_preserves_raw_chance_and_actual_receipts(zero):
    client = ProbabilityClient(zero=zero)
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and client.runs == 1
    accepted = result["flowResults"][0]["quantitativeSummary"]
    assert accepted["nativeGeneratedStatus"] == "observed" and accepted["stableTicks"] == 85
    submitted = [args for op, args in client.operations if op == "qa.run"]
    assert len(submitted) == 1 and submitted[0]["machineFlows"] == client.values


@pytest.mark.parametrize("fault", ["unknown", "chance", "extra_final_port", "mode_contract", "unknown_extra_final_port", "unknown_controller_drift"])
async def test_transport_refuses_incomplete_proof_without_reroll(fault):
    def mutate(row):
        if fault == "chance": row["flowEvidence"]["declaredRecipe"]["rawItemOutputs"][1]["chance"] = 9999
        elif fault in ("extra_final_port", "unknown_extra_final_port"): row["fixtureCoverage"]["after"]["gtTiles"][0]["ports"]["itemOutput"] = []
        elif fault == "mode_contract": row["flowEvidence"]["finiteOutputLedger"]["sessionId"] = "drift"
        elif fault == "unknown_controller_drift": row["flowEvidence"]["finiteOutputLedger"]["controllerPosition"]["x"] += 1
    client = ProbabilityClient(pending="unknown" if fault.startswith("unknown") else "observed", fault=mutate)
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1
    assert sum(op == "qa.run" for op, _ in client.operations) == 1
    if fault in ("unknown_extra_final_port", "unknown_controller_drift"):
        assert result["flowResults"][0]["errorCode"] == "qa_chance_output_coverage_invalid"
        assert result["flowResults"][0].get("actualDepositRecoveryVerified") is not True
        assert "partialEvidence" not in result["flowResults"][0]
