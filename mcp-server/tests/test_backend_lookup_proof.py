"""Failed first backend reads retain bounded evidence without changing selection or feed guards."""

import copy
import json

import pytest

import test_recipe_tasks as native
from test_recipe_optimization import backend_arguments, backend_transport
from test_single_recipe_tasks import single_machine, arguments
from minecraft_codex_bridge.recipe_tasks import _backend_lookup_proof
from minecraft_codex_bridge.server import Adapter

native_bridge = native.native_bridge


def faulty_backend(client, monkeypatch, update):
    backend_transport(client, monkeypatch)
    original = client.call

    async def observe(operation, args, **options):
        data = await original(operation, args, **options)
        if operation == "recipes.query":
            update(data)
        return data

    monkeypatch.setattr(client, "call", observe)


@pytest.mark.parametrize("include_details", [False, True])
async def test_first_partial_dto_is_preserved_through_real_recipe_pages_wrapper_without_retry_or_feed(single_machine, monkeypatch, include_details):
    client, state = single_machine
    state["movementImplementation"] = "developer_teleport"
    def partial(data):
        data.update(lookupComplete=False, budget={"elapsedMicros": 18001, "payloadElapsedMicros": 12003,
            "softBudgetMicros": 10000, "softBudgetScope": "backend_candidate_output_check_and_raw_dto",
            "softBudgetExceeded": True, "nativeFindCalls": 1, "nativeCallInterruptible": False,
            "unrelated": "do not copy"})
        recipe = data["recipes"][0]
        recipe.update(softBudgetPartial=True, metadataTruncated=False, metadataTotal=0, publicMetadataComplete=True)
        recipe["items"]["inputs"].update(truncated=True, total=2)
        recipe["items"]["inputs"]["entries"].append({"unrelated": "do not copy"})

    faulty_backend(client, monkeypatch, partial)
    response = await Adapter(client).call_tool("mcb_recipe_once", arguments(recipeLookup="backend_match",
        backendInputs=backend_arguments()["inputs"], includeDetails=include_details, generateMaterials=True))
    result = response.structuredContent["data"]
    assert result["reason"] == "backend_lookup_proof_unavailable", result
    proof = result["backendLookupProof"]
    assert proof["lookupComplete"] is False and proof["matched"] is True and proof["recipesCount"] == 1
    assert proof["lookupSource"] == "public_backend_find" and proof["candidateValidated"] is True
    assert proof["budget"] == {"elapsedMicros": 18001, "payloadElapsedMicros": 12003,
        "softBudgetMicros": 10000, "softBudgetScope": "backend_candidate_output_check_and_raw_dto",
        "softBudgetExceeded": True, "nativeFindCalls": 1, "nativeCallInterruptible": False}
    assert proof["firstRecipePartial"]["softBudgetPartial"] is True
    assert proof["firstRecipePartial"]["items"]["inputs"] == {"truncated": True, "total": 2}
    assert "do not copy" not in json.dumps(proof) and "entries" not in json.dumps(proof)
    assert len(state["queries"]) == 1 and native.writes(state) == []
    assert result["materialGrantsDispatched"] == 0 and result["executionsCompleted"] == 0
    assert ("phases" in result) is include_details


@pytest.mark.parametrize("key,value", [
    ("mode", "catalog"), ("lookupSource", "unknown"), ("lookupComplete", False),
    ("registeredCatalogComplete", True), ("executionValidated", True), ("hasMore", True),
    ("nextCursor", "unexpected"), ("matched", 1), ("matched", False),
])
async def test_changed_proof_field_remains_a_refusal_with_observed_value(single_machine, monkeypatch, key, value):
    client, state = single_machine
    faulty_backend(client, monkeypatch, lambda data: data.update({key: value}))
    result = await native.invoke(client, arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"]))
    assert result["reason"] == "backend_lookup_proof_unavailable", result
    assert type(result["backendLookupProof"][key]) is type(value) and result["backendLookupProof"][key] == value
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_missing_field_stays_missing_and_unvalidated_candidate_is_not_authorized(single_machine, monkeypatch):
    client, state = single_machine
    faulty_backend(client, monkeypatch, lambda data: data.pop("nextCursor"))
    result = await native.invoke(client, arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"]))
    assert result["reason"] == "backend_lookup_proof_unavailable" and "nextCursor" not in result["backendLookupProof"]
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_candidate_validation_guard_keeps_the_same_failure_summary(single_machine, monkeypatch):
    client, state = single_machine
    faulty_backend(client, monkeypatch, lambda data: data.update(candidateValidated=False))
    result = await native.invoke(client, arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"]))
    assert result["reason"] == "backend_candidate_unvalidated", result
    assert result["backendLookupProof"]["candidateValidated"] is False
    assert len(state["queries"]) == 1 and native.writes(state) == []


async def test_successful_backend_does_not_add_failure_diagnostics(single_machine, monkeypatch):
    client, state = single_machine
    backend_transport(client, monkeypatch)
    result = await native.invoke(client, arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"]))
    assert result["status"] == "completed" and "backendLookupProof" not in result
    assert len(state["queries"]) == 1 and result["acceptance"]["outputDelta"] == 4


def test_diagnostic_tree_is_fixed_and_bounded_for_malformed_large_fields():
    large = {"sensitive": ["not copied"] * 10000}
    recipe = {key: copy.deepcopy(large) for key in ("softBudgetPartial", "byteLimited", "metadataTruncated", "metadataTotal",
        "publicMetadataComplete", "metadataUnknown", "metadataUnknownCount")}
    recipe.update(items={group: {"truncated": large, "total": "X" * 10000, "entries": large}
        for group in ("inputs", "outputs")}, fluids={group: [large] * 1000 for group in ("inputs", "outputs")}, arbitrary=large)
    data = {key: "X" * 10000 for key in ("mode", "lookupSource", "lookupComplete", "registeredCatalogComplete",
        "executionValidated", "hasMore", "nextCursor", "matched", "candidateValidated", "filterIncomplete", "stopReason")}
    data.update(recipes=[recipe] * 10000, budget={key: large for key in ("elapsedMicros", "payloadElapsedMicros", "softBudgetMicros",
        "softBudgetScope", "softBudgetExceeded", "nativeFindCalls", "nativeCallInterruptible")}, arbitrary=large)
    proof = _backend_lookup_proof(data)
    encoded = json.dumps(proof)
    assert len(encoded.encode()) < 8192 and "sensitive" not in encoded and "not copied" not in encoded and "arbitrary" not in encoded
    assert proof["recipesCount"] == 10000 and proof["mode"]["truncated"] is True
    assert proof["firstRecipePartial"]["items"]["inputs"]["truncated"] == {"type": "object", "length": 1}
    assert proof["budget"]["elapsedMicros"] == {"type": "object", "length": 1}


@pytest.mark.parametrize("data", [None, [], False, "malformed"])
def test_nonobject_query_evidence_stays_unknown(data):
    assert _backend_lookup_proof(data) == {"observedType": type(data).__name__}


def test_large_numbers_nonfinite_and_absent_groups_are_not_fabricated():
    proof = _backend_lookup_proof({"matched": float("nan"), "budget": {"elapsedMicros": 10**500},
        "recipes": [{"items": {"inputs": None}}]})
    assert proof["matched"] == {"type": "float"} and proof["budget"]["elapsedMicros"] == {"type": "int"}
    assert proof["firstRecipePartial"]["items"]["inputs"] == {"observedType": "NoneType"}
    assert "fluids" not in proof["firstRecipePartial"]
