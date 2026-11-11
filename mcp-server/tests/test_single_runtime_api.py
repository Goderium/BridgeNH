"""Shared inventories require bound public runtime API evidence for arbitrary subclasses."""

import copy

import pytest

import test_recipe_input_discovery as discovery
import test_recipe_tasks as native
from test_single_recipe_tasks import arguments, single_machine
from minecraft_codex_bridge.recipe_tasks import BASIC_MACHINE_API, COMMON_BASIC_MACHINE_API


native_bridge = native.native_bridge
SYNTHETIC_CLASS = "example.UnlistedPublicRecipeMachine"


def actual_class(state, name=SYNTHETIC_CLASS):
    state["single_meta_class"] = name
    state["single_world"]["gregtech"]["metaClass"] = name


def runtime_proof(client, monkeypatch, *, mutate=None):
    original = client.call

    async def observed(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "machine.inspect":
            value["sessionId"] = "session"
            value["singleBlockRecipeMachine"] = {"schemaVersion": 1, "apiClass": COMMON_BASIC_MACHINE_API,
                "source": "public_runtime_api", "runtimeMember": True,
                "owner": {key: value[key] for key in ("metaTileId", "metaClass", "x", "y", "z", "dimension")}}
            if mutate is not None:
                mutate(value)
        return value

    monkeypatch.setattr(client, "call", observed)


def power_calls(state):
    return [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("action") == "set_selected"]


async def test_exact_legacy_class_keeps_no_proof_compatibility(single_machine):
    client, state = single_machine
    assert state["single_meta_class"] == BASIC_MACHINE_API
    result = await native.invoke(client, arguments())
    assert result["status"] == "completed", result
    assert result["machineScope"]["commonBasicMachineAPIVerified"] is True
    assert result["acceptance"]["consumed"] == 1 and result["acceptance"]["outputDelta"] == 4


@pytest.mark.parametrize("name", [SYNTHETIC_CLASS, "another.mod.UnlistedSibling", BASIC_MACHINE_API])
async def test_positive_public_runtime_proof_accepts_actual_class_without_a_name_adapter(single_machine, monkeypatch, name):
    client, state = single_machine
    actual_class(state, name)
    runtime_proof(client, monkeypatch)
    owner, _ = discovery.public_bindings(state)
    result = await native.invoke(client, discovery.automatic_arguments())
    assert result["status"] == "completed", result
    assert result["inputGuiBinding"]["owner"] == owner and owner["metaClass"] == name
    assert result["machineScope"]["commonBasicMachineAPIVerified"] is True
    assert result["acceptance"]["consumed"] == 1 and result["acceptance"]["outputDelta"] == 4
    assert all(phase["metaClass"] == name for phase in result["phases"] if phase["phase"] == "single_machine_binding")
    assert [call["args"]["selected"] for call in power_calls(state)] == [1, 0]


async def test_unlisted_subclass_without_runtime_proof_refuses_before_query_and_writes(single_machine):
    client, state = single_machine
    actual_class(state)
    result = await native.invoke(client, arguments(preflightControls=True, generateMaterials=True))
    assert result["status"] == "unavailable" and result["reason"] == "single_machine_api_unavailable", result
    assert state["queries"] == [] and native.writes(state) == []
    assert result["sideEffectsPossible"] is False


PROOF_FAULTS = ["null", "list", "empty", "negative", "integer_member", "missing_member", "unknown_version",
    "boolean_version", "string_version", "missing_version", "wrong_api", "wrong_source", "missing_owner", "list_owner",
    "missing_class", "wrong_class", "wrong_id", "boolean_id", "string_id", "missing_id", "wrong_x", "wrong_y", "wrong_z",
    "boolean_x", "string_y", "missing_z", "wrong_dimension", "boolean_dimension", "string_dimension",
    "missing_dimension", "missing_session", "wrong_session", "boolean_session", "empty_session"]


def corrupt_proof(value, fault):
    proof = value["singleBlockRecipeMachine"]
    if fault in ("null", "list", "empty"):
        value["singleBlockRecipeMachine"] = {"null": None, "list": [], "empty": {}}[fault]
    elif fault == "negative":
        proof["runtimeMember"] = False
    elif fault == "integer_member":
        proof["runtimeMember"] = 1
    elif fault == "missing_member":
        proof.pop("runtimeMember")
    elif fault == "unknown_version":
        proof["schemaVersion"] = 2
    elif fault == "boolean_version":
        proof["schemaVersion"] = True
    elif fault == "string_version":
        proof["schemaVersion"] = "1"
    elif fault == "missing_version":
        proof.pop("schemaVersion")
    elif fault == "wrong_api":
        proof["apiClass"] = BASIC_MACHINE_API
    elif fault == "wrong_source":
        proof["source"] = "machine_name_inference"
    elif fault == "missing_owner":
        proof.pop("owner")
    elif fault == "list_owner":
        proof["owner"] = []
    elif fault == "missing_class":
        proof["owner"].pop("metaClass")
    elif fault == "wrong_class":
        proof["owner"]["metaClass"] = BASIC_MACHINE_API
    elif fault.startswith(("wrong_", "boolean_", "string_", "missing_")) and not fault.endswith("session"):
        kind, key = fault.split("_", 1)
        key = "metaTileId" if key == "id" else key
        if kind == "wrong":
            proof["owner"][key] += 1
        elif kind == "boolean":
            proof["owner"][key] = True
        elif kind == "string":
            proof["owner"][key] = str(proof["owner"][key])
        else:
            proof["owner"].pop(key)
    elif fault == "missing_session":
        value.pop("sessionId")
    else:
        value["sessionId"] = {"wrong_session": "another-session", "boolean_session": True, "empty_session": ""}[fault]


@pytest.mark.parametrize("fault", PROOF_FAULTS)
async def test_untrusted_runtime_proof_never_queries_or_mutates(single_machine, monkeypatch, fault):
    client, state = single_machine
    actual_class(state)
    runtime_proof(client, monkeypatch, mutate=lambda value: corrupt_proof(value, fault))
    result = await native.invoke(client, arguments(preflightControls=True, generateMaterials=True))
    expected = "session_changed" if fault in ("wrong_session", "boolean_session", "empty_session") else "single_machine_api_unavailable"
    assert result["status"] == "unavailable" and result["reason"] == expected, result
    assert state["queries"] == [] and native.writes(state) == []
    assert result["sideEffectsPossible"] is False


@pytest.mark.parametrize("fault", ["negative", "null", "boolean_version", "wrong_id", "missing_session"])
async def test_explicit_bad_proof_cannot_fallback_to_exact_legacy_class(single_machine, monkeypatch, fault):
    client, state = single_machine
    runtime_proof(client, monkeypatch, mutate=lambda value: corrupt_proof(value, fault))
    result = await native.invoke(client, arguments())
    assert result["status"] == "unavailable" and result["reason"] == "single_machine_api_unavailable", result
    assert state["queries"] == [] and native.writes(state) == []


@pytest.mark.parametrize("name", [SYNTHETIC_CLASS, BASIC_MACHINE_API])
@pytest.mark.parametrize("fault", ["absent", "negative", "unknown_version", "wrong_id", "wrong_x", "wrong_dimension", "missing_session"])
async def test_runtime_proof_loss_or_change_after_feed_blocks_native_power(single_machine, monkeypatch, name, fault):
    client, state = single_machine
    actual_class(state, name)

    def changed(value):
        if state["input_material"]:
            if fault == "absent":
                value.pop("singleBlockRecipeMachine")
            else:
                corrupt_proof(value, fault)

    runtime_proof(client, monkeypatch, mutate=changed)
    result = await native.invoke(client, arguments())
    assert result["status"] == "failed" and result["reason"] == "single_machine_api_unavailable", result
    assert state["input_material"] == 1 and state["material"] == 31
    assert power_calls(state) == [] and result["rolledBack"] is False


@pytest.mark.parametrize("flag,reason", [
    ("single_nonempty_tank", "single_machine_fluid_isolation_unavailable"),
    ("single_missing_tanks", "single_machine_fluid_isolation_unavailable"),
    ("single_partial_inventory", "inventory_coverage_incomplete"),
    ("single_foreign_item", "input_not_isolated"),
    ("single_map_unsupported", "UNSUPPORTED"),
    ("single_invalid_gui_index", "single_machine_gui_declaration_unavailable"),
])
async def test_valid_runtime_type_proof_keeps_item_isolation_and_gui_requirements(single_machine, monkeypatch, flag, reason):
    client, state = single_machine
    actual_class(state)
    runtime_proof(client, monkeypatch)
    state[flag] = True
    result = await native.invoke(client, arguments())
    assert result["status"] in ("unavailable", "failed") and result["reason"] == reason, result
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
    assert power_calls(state) == []


@pytest.mark.parametrize("fault", ["voltage", "amperage", "map_owner", "gui_owner", "changed_class"])
async def test_valid_runtime_type_proof_keeps_power_map_and_actual_owner_checks(single_machine, monkeypatch, fault):
    client, state = single_machine
    actual_class(state)
    runtime_proof(client, monkeypatch)
    owner, bind = discovery.public_bindings(state)
    request = discovery.automatic_arguments()
    expected = "single_machine_recipe_map_unavailable"
    if fault == "voltage":
        state["single_input_voltage"] = 1
        expected = "no_observed_supported_recipe"
    elif fault == "amperage":
        state["single_map_amperage"] = True
    elif fault == "map_owner":
        original = client.call

        async def mismatched_map(operation, args, **options):
            value = await original(operation, args, **options)
            if operation == "recipes.query":
                value["machine"]["metaClass"] = BASIC_MACHINE_API
            return value

        monkeypatch.setattr(client, "call", mismatched_map)
    elif fault == "gui_owner":
        def mismatched_gui(value):
            bind(value)
            for slot in value.get("slots", []):
                if slot.get("machineInventoryBindingVerified"):
                    slot["machineInventoryOwner"] = {**copy.deepcopy(owner), "metaClass": BASIC_MACHINE_API}

        state["gui_observation_hook"] = mismatched_gui
        expected = "input_gui_binding_unavailable"
    else:
        state["changed_class_after_feed"] = True
        expected = "component_controller_identity_changed"
    result = await native.invoke(client, request)
    assert result["status"] in ("unavailable", "failed") and result["reason"] == expected, result
    assert power_calls(state) == []
    if fault != "changed_class":
        assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])
