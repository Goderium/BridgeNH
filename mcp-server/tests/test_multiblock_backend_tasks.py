"""A native first candidate needs complete registered multiblock and voltage provenance."""
import pytest

import test_recipe_tasks as native
from minecraft_codex_bridge.recipe_tasks import COMMON_MULTIBLOCK_API, _RecipeTask, _Unavailable
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


def arguments(**extra):
    return native.declaration(recipeLookup="backend_match", backendInputs=[
        {"source": "registry", **native.MATERIAL, "count": 8},
        {"source": "registry", **native.CATALYST, "count": 1}], **extra)


def observed_backend(client, monkeypatch, *, mutation=None, voltage=128, amperage=2, matched=True):
    original = client.call

    async def observe(operation, args, **options):
        data = await original(operation, args, **options)
        if operation == "recipes.query":
            assert args["mode"] == "backend_match"
            assert "limit" not in args and "cursor" not in args and "map" not in args
            owner = {**native.MACHINE, "dimension": 180, "metaTileId": 10001,
                     "metaClass": "example.PublicMultiblock", "currentRecipeMap": "fixture.public.current.map"}
            if matched:
                recipe = data["recipes"][0]
                recipe.pop("categoryRecipeIndex")
                recipe.update(candidateOrdinal=0, candidateSource="public_backend_find", category=None)
            else:
                data["recipes"] = []
            data.update(mode="backend_match", source="integrated_server", lookupSource="public_backend_find",
                sessionId="session", lookupComplete=True, registeredCatalogComplete=False, executionValidated=False,
                hasMore=False, nextCursor=None, matched=matched, candidateValidated=matched, complete=False, partial=True,
                recipeMap={"name": owner["currentRecipeMap"], "amperage": amperage},
                machine={key: value for key, value in owner.items() if key != "dimension"},
                query={"inputs": [native.stack({key: item[key] for key in ("registryName", "metadata")}, item["count"])
                    for item in args["inputs"]], "fluids": [], "specialSlot": None, "inputVoltage": voltage,
                    "mapAmperage": amperage, "nativeVoltageBound": voltage * amperage,
                    "caching": False, "ignoreStackSizes": False,
                    "inputVoltageEvidence": {"schemaVersion": 1, "source": "public_multiblock_controller_api",
                        "scope": "native_lookup_bound_only", "apiClass": COMMON_MULTIBLOCK_API,
                        "apiMethod": "getMaxInputVoltage", "value": voltage, "owner": owner}},
                validation={"inputMatch": matched, "enabled": matched, "notFake": matched,
                    "voltageCompatible": matched, "outputChecked": True, "outputMatch": matched, "outputUnknown": False})
            data["machine"]["inputVoltage"] = voltage
            if mutation:
                mutation(data)
        return data

    monkeypatch.setattr(client, "call", observe)


@pytest.mark.parametrize("modular", [True, False])
async def test_backend_multiblock_preserves_real_feed_deltas_catalyst_and_stop_acceptance(native_bridge, monkeypatch, modular):
    client, state = native_bridge
    state["modular"] = modular
    observed_backend(client, monkeypatch)
    result = await native.invoke(client, arguments(executions=2))
    assert result["status"] == "completed", result
    assert len(state["queries"]) == 1 and state["queries"][0]["mode"] == "backend_match"
    assert result["acceptance"]["consumed"] == 16 and result["acceptance"]["outputDelta"] == 2
    assert result["acceptance"]["catalystRetained"] is True and result["acceptance"]["controllerStopped"] is True
    assert state["material"] == 16 and state["input_material"] == 0 and state["input_circuit"] == 1
    assert state["output"] == 6 and state["allowed"] is state["active"] is False
    if modular:
        assert state["core"] is False
    else:
        assert result["acceptance"]["allRegisteredCoresStopClaimed"] is False
    assert state["cursor"] is None and result["executionsCompleted"] == 2
    proof = result["recipe"]["voltageEvidence"]
    assert proof["actualInputVoltage"] == 128 and proof["nativeVoltageBound"] == 256
    assert proof["inputVoltageEvidence"]["source"] == "public_multiblock_controller_api"
    assert proof["inputVoltageEvidence"]["owner"]["currentRecipeMap"] == proof["mapName"]


@pytest.mark.parametrize("path,value,reason", [
    ("source", "catalog", "backend_multiblock_context_unavailable"),
    ("source", "public_backend_find", "backend_multiblock_context_unavailable"),
    ("source", "client", "backend_multiblock_context_unavailable"),
    ("source", None, "backend_multiblock_context_unavailable"),
    ("lookupSource", "catalog", "backend_lookup_proof_unavailable"),
    ("lookupSource", "integrated_server", "backend_lookup_proof_unavailable"),
    ("lookupSource", None, "backend_lookup_proof_unavailable"),
    ("sessionId", "other-session", "session_changed"),
    ("sessionId", None, "session_changed"),
    ("dimension", 180.0, "backend_multiblock_context_unavailable"),
    ("query.inputVoltage", True, "backend_multiblock_voltage_unavailable"),
    ("query.inputVoltage", 0, "backend_multiblock_voltage_unavailable"),
    ("query.inputVoltage", 128.0, "backend_multiblock_voltage_unavailable"),
    ("query.inputVoltage", "128", "backend_multiblock_voltage_unavailable"),
    ("query.inputVoltage", 2**63, "backend_multiblock_voltage_unavailable"),
    ("query.mapAmperage", 1, "backend_multiblock_voltage_unavailable"),
    ("query.mapAmperage", True, "backend_multiblock_voltage_unavailable"),
    ("query.nativeVoltageBound", 257, "backend_multiblock_voltage_unavailable"),
    ("query.nativeVoltageBound", 256.0, "backend_multiblock_voltage_unavailable"),
    ("machine.inputVoltage", 127, "backend_multiblock_voltage_unavailable"),
    ("query.inputVoltageEvidence", None, "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.schemaVersion", True, "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.source", "public_base_tile_api", "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.apiClass", "gregtech.api.metatileentity.BaseMetaTileEntity", "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.apiMethod", "getInputVoltage", "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.scope", "actual_processing_power", "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.value", 127, "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.value", 128.0, "backend_multiblock_voltage_evidence_unavailable"),
    ("query.inputVoltageEvidence.owner", {}, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.x", 10.0, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.y", 20, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.z", 21, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.dimension", 181, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.metaTileId", 10002, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.metaTileId", 10001.0, "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.metaClass", "example.Other", "backend_multiblock_voltage_owner_changed"),
    ("query.inputVoltageEvidence.owner.currentRecipeMap", "other.map", "backend_multiblock_voltage_owner_changed"),
    ("machine.metaTileId", 10002, "single_machine_recipe_map_unavailable"),
    ("machine.metaClass", "example.Other", "single_machine_recipe_map_unavailable"),
    ("machine.currentRecipeMap", "other.map", "single_machine_recipe_map_unavailable"),
    ("recipeMap.amperage", 2.0, "single_machine_recipe_map_unavailable"),
    ("recipeMap.amperage", True, "single_machine_recipe_map_unavailable"),
    ("query.inputs", [], "backend_multiblock_input_copy_unavailable"),
    ("query.inputs.0.count", 9, "backend_multiblock_input_copy_unavailable"),
    ("query.inputs.0.count", 8.0, "backend_multiblock_input_copy_unavailable"),
    ("query.inputs.0.hasTag", True, "backend_multiblock_input_copy_unavailable"),
    ("query.inputs.0.registryName", "minecraft:stone", "backend_multiblock_input_copy_unavailable"),
    ("query.fluids", [{"fluid": "water", "amount": 1000}], "backend_multiblock_input_copy_unavailable"),
    ("query.specialSlot", {}, "backend_multiblock_input_copy_unavailable"),
    ("query.caching", True, "backend_multiblock_input_copy_unavailable"),
    ("query.ignoreStackSizes", True, "backend_multiblock_input_copy_unavailable"),
    ("validation.inputMatch", False, "backend_multiblock_candidate_unvalidated"),
    ("validation.outputUnknown", True, "backend_multiblock_candidate_unvalidated"),
    ("lookupComplete", False, "backend_lookup_proof_unavailable"),
    ("candidateValidated", False, "backend_candidate_unvalidated"),
])
async def test_wrong_unknown_or_drifting_native_proof_refuses_before_any_material_or_action(native_bridge, monkeypatch, path, value, reason):
    client, state = native_bridge
    def mutate(data):
        parts = path.split(".")
        current = data
        for part in parts[:-1]:
            current = current[int(part)] if isinstance(current, list) else current[part]
        if isinstance(current, list):
            current[int(parts[-1])] = value
        else:
            current[parts[-1]] = value
    observed_backend(client, monkeypatch, mutation=mutate)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["reason"] == reason, result
    assert len(state["queries"]) == 1 and native.writes(state) == []
    assert result["materialGrantsDispatched"] == 0 and result["executionsCompleted"] == 0


@pytest.mark.parametrize("fault,reason", [("core", "execution_core_not_stopped"), ("port", "isolated_port_binding_unavailable"),
    ("formed", "formed_stopped_machine_required"), ("enabled", "formed_stopped_machine_required"),
    ("unknown_input_role", "registered_input_coverage_unavailable")])
async def test_registered_preflight_cannot_be_bypassed_by_backend(native_bridge, monkeypatch, fault, reason):
    client, state = native_bridge
    observed_backend(client, monkeypatch)
    if fault == "core":
        state["core"] = True
    else:
        original = client.call
        async def changed(operation, args, **options):
            data = await original(operation, args, **options)
            if operation == "machine.inspect":
                if fault == "port":
                    data["components"]["entries"][0]["x"] += 1
                elif fault == "formed":
                    data["processing"]["structureFormed"] = False
                elif fault == "enabled":
                    data["state"]["allowedToWork"] = True
                else:
                    data["components"]["unavailableRoles"].append({"role": "item_input", "reason": "unknown"})
            return data
        monkeypatch.setattr(client, "call", changed)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["reason"] == reason, result
    assert state["queries"] == [] and native.writes(state) == []


async def test_native_no_match_is_one_lookup_and_never_catalog_fallback(native_bridge, monkeypatch):
    client, state = native_bridge
    observed_backend(client, monkeypatch, matched=False)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["reason"] == "no_observed_supported_recipe", result
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_raw_eut_cannot_exceed_the_actual_controller_times_map_bound(native_bridge, monkeypatch):
    client, state = native_bridge
    state["recipe"]["rawEUt"] = 5
    observed_backend(client, monkeypatch, voltage=2, amperage=2)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["reason"] == "no_observed_supported_recipe", result
    assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "backend_multiblock_recipe_voltage_exceeded"
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_native_voltage_bound_multiplication_cannot_overflow_long(native_bridge, monkeypatch):
    client, state = native_bridge
    observed_backend(client, monkeypatch, voltage=2**62, amperage=2)
    result = await native.invoke(client, arguments(generateMaterials=True))
    assert result["reason"] == "backend_multiblock_voltage_unavailable", result
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_unverified_multiblock_object_cannot_select_backend_before_registered_preflight(native_bridge):
    client, state = native_bridge
    task = _RecipeTask(client, arguments(), run_workflow, None)
    task.machine_id, task.machine_class = 10001, "example.PublicMultiblock"
    with pytest.raises(_Unavailable) as error:
        await task.select_recipe()
    assert error.value.details["reason"] == "backend_machine_scope_unsupported"
    assert state["queries"] == [] and native.writes(state) == []
