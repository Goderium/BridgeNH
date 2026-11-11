"""Production multi-fluid acceptance, closed physical roles, full quantities and final projection."""
from copy import deepcopy
import json

import jsonschema
import pytest

from minecraft_codex_bridge.qa_flow_schema import DECLARED_MACHINE_FLOWS_SCHEMA
from test_qa_machine_flows import GroupClient, ForgetClient, declaration, fixtures, run


def multi_client(count=1, *, forget=False):
    client = ForgetClient() if forget else GroupClient(count=count, dynamic=True)
    if forget:
        client.values[0] = declaration(dynamic=True)
    for value in client.values:
        value["recipe"]["fluidInputs"].append({"fluid": "acid", "amount": 1700})
        value["recipe"]["fluidOutputs"].append({"fluid": "waste", "amount": 2300})
    client.records = fixtures(client.values)
    def mutate(rows):
        for row in rows:
            evidence, proof = row["flowEvidence"], row["fixtureCoverage"]["after"]
            owner = proof["gtTiles"][0]
            evidence["stages"][2]["name"] = "native_public_blueprint_constructed"
            evidence["declaredRecipe"]["fluidInputs"].append({"name": "acid", "amount": 1700, "nbt": "{}"})
            evidence["declaredRecipe"]["fluidOutputs"].append({"name": "waste", "amount": 2300, "nbt": "{}"})
            y, z = owner["y"], owner["z"] + 1
            points = [{"x": x, "y": y, "z": z} for x in range(3, 12)]
            names = ["finite_fluid_input", "finite_fluid_input_1", "finite_fluid_input_2", "output", "fluid_output_1", "fluid_output_2", "fluid_receiver", "fluid_receiver_1", "fluid_receiver_2"]
            multi = {"contract": "native_deterministic_multi_fluid_v1", "physicalFluidInputPortCount": 3,
                "physicalFluidOutputPortCount": 3, "physicalFluidReceiverCount": 3,
                "nativeRawFluidInputCount": 2, "nativeRawFluidOutputCount": 2, "virtualFluidInventory": False,
                **{key: True for key in ("allPhysicalInputsBound", "allPhysicalOutputsBound", "allPhysicalReceiversBound", "nativeAmountsAndNBTUnchanged", "fullVectorOutputRouting", "jointCapacityPreflight")},
                "inputPositions": points[:3], "outputPositions": points[3:6], "receiverPositions": points[6:]}
            evidence["publicMultiFluidBindings"] = multi
            for snapshot in evidence["snapshots"]:
                bindings = []
                for index, (name, point) in enumerate(zip(names, points)):
                    stage = snapshot["stage"]
                    resource = ({"name": "water", "amount": 1000, "nbt": "{}"} if index == 0 and stage == "supplied" else
                                {"name": "acid", "amount": 1700, "nbt": "{}"} if index == 1 and stage == "supplied" else
                                {"name": "distilledwater", "amount": 1000, "nbt": "{}"} if index == 3 and stage == "produced" or index == 6 and stage in ("recovered", "stable") else
                                {"name": "waste", "amount": 2300, "nbt": "{}"} if index == 4 and stage == "produced" or index == 7 and stage in ("recovered", "stable") else {"amount": 0})
                    bindings.append({"binding": name, "position": point, "complete": True, "totalTanks": 1,
                        "tanks": [{"tank": 0, "capacity": 4000, **resource}]})
                snapshot["fluids"] = bindings
            for index, point in enumerate(points):
                tile = {**point, "tileClass": "test.GTTile", "valid": True}
                hatch = {**owner, **tile, "controller": False, "metaTileId": 100 + index,
                         "metadata": 100 + index, "runtimeClass": "test.OrdinaryHatch"}
                proof["tiles"].append(tile); proof["gtTiles"].append(hatch)
                evidence["activeTiles"].append({**point, "startTimer": 100, "endTimer": 101})
                if index < 6:
                    owner["ports"]["fluidInput" if index < 3 else "fluidOutput"].append({
                        **{key: hatch[key] for key in ("x", "y", "z", "metaTileId", "tileClass", "runtimeClass", "valid")},
                        "actualOwnerIdentityVerified": True})
            proof["tileCount"] += 9; proof["gtTileCount"] += 9
            proof["nonAir"] += 9; proof["air"] -= 9; proof["nonAirTypes"][0]["count"] += 9
    client.case_mutation = mutate
    return client


def test_multi_schema_is_bounded_and_chance_core_manufacturing_remain_scalar():
    value = declaration(dynamic=True)
    for key in ("fluidInputs", "fluidOutputs"):
        value["recipe"][key] = [{"fluid": "f" + str(i), "amount": 2147483647} for i in range(8)]
    jsonschema.validate([value], DECLARED_MACHINE_FLOWS_SCHEMA)
    for key in ("fluidInputs", "fluidOutputs"):
        bad = deepcopy(value); bad["recipe"][key].append({"fluid": "ninth", "amount": 1})
        with pytest.raises(jsonschema.ValidationError):
            jsonschema.validate([bad], DECLARED_MACHINE_FLOWS_SCHEMA)
    bad = deepcopy(value); bad["recipe"]["outputChances"] = [10000, 0]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate([bad], DECLARED_MACHINE_FLOWS_SCHEMA)
    bad = deepcopy(value); bad.update(fixtureTemplate="tstqa:core_flow_cell"); del bad["fixtureBounds"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate([bad], DECLARED_MACHINE_FLOWS_SCHEMA)
    bad = declaration(dynamic=True)
    bad["upgrades"] = [{"item": bad["machine"], "manufacturingRecipe": value["recipe"], "expectedFeedbackLines": ["effect"]}]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate([bad], DECLARED_MACHINE_FLOWS_SCHEMA)


async def test_all_multi_fluid_roles_and_empty_physical_ports_are_verified():
    result = await run(multi_client())
    assert result["status"] == "completed" and result["groupFullFlowVerified"]
    row = result["flowResults"][0]
    summary = row["quantitativeSummary"]
    assert summary["receiverFluids"] == summary["fluidOutputs"]
    assert [item["amount"] for item in summary["fluidInputs"]] == ["1700", "1000"]
    assert [item["amount"] for item in summary["fluidOutputs"]] == ["1000", "2300"]
    assert row["fixtureAfterSummary"]["nonAirTypeCountsComplete"] is True
    assert row["fixtureAfterSummary"]["nonAirTypes"][0]["count"] == 10


@pytest.mark.parametrize("fault", ["missing_contract", "virtual", "raw_trimmed", "unbound_empty", "wrong_role", "alias", "tagged", "partial_amount", "wrong_final_port", "receiver_name", "capacity", "extra_binding", "late_product"])
async def test_multi_rejects_incomplete_or_forged_physical_quantity_proof(fault):
    client = multi_client(); prepare = client.case_mutation
    def mutate(rows):
        prepare(rows)
        e = rows[0]["flowEvidence"]; multi = e["publicMultiFluidBindings"]
        if fault == "missing_contract": del e["publicMultiFluidBindings"]
        elif fault == "virtual": multi["virtualFluidInventory"] = True
        elif fault == "raw_trimmed": multi["nativeRawFluidInputCount"] = 1
        elif fault == "unbound_empty": e["snapshots"][0]["fluids"].pop(2)
        elif fault == "wrong_role": e["snapshots"][1]["fluids"][3]["tanks"] = deepcopy(e["snapshots"][1]["fluids"][1]["tanks"]); e["snapshots"][1]["fluids"][1]["tanks"] = [{"tank": 0, "capacity": 4000, "amount": 0}]
        elif fault == "alias": multi["receiverPositions"][0] = multi["inputPositions"][0]
        elif fault == "tagged": e["declaredRecipe"]["fluidInputs"][1]["nbt"] = '{label:"opaque"}'
        elif fault == "partial_amount": e["snapshots"][1]["fluids"][1]["tanks"][0]["amount"] = 1
        elif fault == "wrong_final_port": rows[0]["fixtureCoverage"]["after"]["gtTiles"][0]["ports"]["fluidInput"].pop()
        elif fault == "receiver_name": e["snapshots"][0]["fluids"][-1]["binding"] = "fluid_receiver_8"
        elif fault == "capacity": e["snapshots"][1]["fluids"][1]["tanks"][0]["capacity"] = 1699
        elif fault == "extra_binding": e["snapshots"][0]["fluids"].append(deepcopy(e["snapshots"][0]["fluids"][-1]))
        else: e["snapshots"][-1]["fluids"][4]["tanks"][0].update(name="waste", nbt="{}", amount=1)
    client.case_mutation = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"]
    assert "fixtureAfterSummary" not in result["flowResults"][0]


async def test_verified_final_structure_and_performance_survive_projection_and_forget():
    client = multi_client(forget=True)
    result = await run(client, includeDetails=True, max_return_bytes=16384)
    row = result["flowResults"][0]
    assert result["status"] == "completed" and result["resourceRelease"] and client.forgets == 1
    final = row["fixtureAfterSummary"]
    assert final["nonAirTypes"][0]["count"] == 10 and final["nonAirTypeCountsComplete"]
    assert final["controller"]["formed"] is True and final["controller"]["allowedToWork"] is False
    assert final["measuredPerformance"]["supplyToStoppedProductionTicks"] == 2
    assert final["measuredPerformance"]["effectiveParallelOrOverclockInferred"] is False
    assert result["groupCaptureReleased"] and len(json.dumps(result).encode()) <= 16384


async def test_large_histogram_omission_is_explicit_and_retains_validated_hash_scope():
    client = multi_client(); prepare = client.case_mutation
    def mutate(rows):
        prepare(rows)
        p = rows[0]["fixtureCoverage"]["after"]
        p["nonAirTypes"] = [{"blockIdentity": "test:" + "long" * 100 + str(i), "count": 1} for i in range(100)]
        p["nonAir"] = 100; p["air"] = p["requestedVolume"] - 100
    client.case_mutation = mutate
    result = await run(client, max_return_bytes=16384)
    final = result["flowResults"][0]["fixtureAfterSummary"]
    assert result["status"] == "completed" and final["completeValidationPassed"]
    assert "nonAirTypes" not in final and final["nonAirTypeCountsComplete"] is False
    assert final["nonAirTypeCount"] == 100 and len(final["nonAirTypesSha256"]) == 64
    assert final["nonAirTypesOmittedReason"] and final["controller"]["formed"] is True


async def test_ten_case_minimum_budget_preserves_small_complete_histograms_and_controller_state():
    result = await run(multi_client(10), includeDetails=True, max_return_bytes=16384)
    assert result["status"] == "completed" and result["groupFullFlowVerified"]
    assert len(result["flowResults"]) == 10 and len(json.dumps(result).encode()) <= 16384
    for row in result["flowResults"]:
        final = row["fixtureAfterSummary"]
        assert final["nonAirTypeCountsComplete"] and final["nonAirTypes"][0]["count"] == 10
        assert final["controller"]["formed"] is True and final["controller"]["allowedToWork"] is False
        assert final["measuredPerformance"]["supplyToStoppedProductionTicks"] == 2
