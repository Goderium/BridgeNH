"""One public constant annotation may pass; arbitrary false metadata is still a condition."""

import copy

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
from minecraft_codex_bridge.multi_recipe_tasks import supported_multi_recipe
from minecraft_codex_bridge.recipe_tasks import _Unavailable, supported_recipe


native_bridge = native.native_bridge
RECYCLE_IDENTITY = "gregtech.api.util.GTRecipeConstants.RECYCLE"


def annotation():
    return {"keyClass": "gregtech.api.recipe.metadata.SimpleRecipeMetadataKey",
            "publicKeyIdentity": RECYCLE_IDENTITY, "identifier": "recycle", "value": False}


def annotate(value):
    value.update(metadata=[annotation()], metadataTotal=1, metadataUnknownCount=0)
    return value


def candidate(kind):
    if kind == "multi":
        return annotate(multi.candidate()), multi.arguments(), supported_multi_recipe
    value, arguments = native.recorded_complete_candidate()
    return annotate(value), arguments, supported_recipe


def refused(value, arguments, reader, *, field="metadata", reason="recipe_conditions_unsupported"):
    with pytest.raises(_Unavailable) as caught:
        reader(value, arguments)
    assert caught.value.details["reason"] == reason
    if reason == "recipe_conditions_unsupported":
        evidence = caught.value.details["rejectedConditions"]
        assert any(row["field"] == field for row in evidence)
        if field == "metadata":
            assert next(row for row in evidence if row["field"] == field) == {
                "field": "metadata", "present": True, "expected": [],
                "observed": {"type": "array", "length": len(value["metadata"])}}


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("explicit_available", [False, True])
def test_unique_verified_false_annotation_preserves_registered_selection(kind, explicit_available):
    value, arguments, reader = candidate(kind)
    value["category"] = "example.unrelated.registered.category"
    plain = copy.deepcopy(value)
    plain.update(metadata=[], metadataTotal=0)
    if explicit_available:
        value["metadata"][0]["available"] = True
    assert reader(value, arguments) == reader(plain, arguments)
    assert value["executionValidated"] is False


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("raw", [True, 0, 0.0, None, "false", "False", [], {}, {"available": False}])
def test_only_typed_boolean_false_is_an_annotation(kind, raw):
    value, arguments, reader = candidate(kind)
    value["metadata"][0]["value"] = raw
    refused(value, arguments, reader)


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("mutation", [
    lambda row: row.pop("publicKeyIdentity"),
    lambda row: row.update(publicKeyIdentity="gregtech.api.util.GTRecipeConstants.CLEANROOM"),
    lambda row: row.update(publicKeyIdentity="example.PrivateConstants.RECYCLE"),
    lambda row: row.update(publicKeyIdentity="gregtech.api.util.GTRecipeConstants.recycle"),
    lambda row: row.update(identifier="low_gravity"),
    lambda row: row.update(identifier="RECYCLE"),
    lambda row: row.pop("keyClass"),
    lambda row: row.update(keyClass="unavailable"),
    lambda row: row.update(keyClass="example.PrivateKey"),
    lambda row: row.pop("value"),
    lambda row: row.update(available=False),
    lambda row: row.update(available=0),
    lambda row: row.update(reason="unrecognized_public_metadata_key"),
    lambda row: row.update(valueClass="java.lang.Boolean"),
    lambda row: row.update(extraCondition=False),
])
def test_public_identity_and_typed_row_cannot_be_inferred_from_private_or_same_name_data(kind, mutation):
    value, arguments, reader = candidate(kind)
    mutation(value["metadata"][0])
    refused(value, arguments, reader)


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("field,raw", [
    ("metadataTotal", None), ("metadataTotal", 0), ("metadataTotal", 2),
    ("metadataTotal", True), ("metadataTotal", 1.0), ("metadataTotal", "1"),
    ("metadataUnknownCount", None), ("metadataUnknownCount", 1),
    ("metadataUnknownCount", False), ("metadataUnknownCount", 0.0), ("metadataUnknownCount", "0"),
])
def test_annotation_requires_explicit_consistent_typed_coverage(kind, field, raw):
    value, arguments, reader = candidate(kind)
    if raw is None:
        value.pop(field)
    else:
        value[field] = raw
    refused(value, arguments, reader)


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("extra", [
    annotation(),
    {"keyClass": "gregtech.api.recipe.metadata.SimpleRecipeMetadataKey", "identifier": "cleanroom", "value": False},
    {"keyClass": "gregtech.api.recipe.metadata.SimpleRecipeMetadataKey", "identifier": "unknown", "value": False},
    {**annotation(), "value": True},
])
def test_annotation_never_hides_duplicate_conflicting_or_other_false_conditions(kind, extra):
    value, arguments, reader = candidate(kind)
    value["metadata"].append(copy.deepcopy(extra))
    value["metadataTotal"] = 2
    refused(value, arguments, reader)


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("field,raw", [
    ("softBudgetPartial", True), ("byteLimited", True), ("metadataTruncated", True),
    ("metadataUnknown", True), ("publicMetadataComplete", False),
    ("specialValue", 1), ("specialItems", {"condition": "unknown"}), ("dynamic", True),
])
def test_annotation_preserves_all_existing_raw_condition_refusals(kind, field, raw):
    value, arguments, reader = candidate(kind)
    value[field] = raw
    refused(value, arguments, reader, field=field)


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("field,raw", [
    ("available", False), ("enabled", False), ("hidden", True),
    ("fakeRecipe", True), ("nbtSensitive", True),
])
def test_annotation_preserves_recipe_flag_refusals(kind, field, raw):
    value, arguments, reader = candidate(kind)
    value[field] = raw
    refused(value, arguments, reader, reason="recipe_flags_unsupported")


@pytest.mark.parametrize("kind", ["simple", "multi"])
async def test_annotation_uses_existing_native_task_deltas_and_no_power_replay(native_bridge, monkeypatch, kind):
    client, state = native_bridge
    if kind == "multi":
        multi.vector_transport(client, state, monkeypatch)
        arguments = multi.arguments()
    else:
        arguments = native.declaration()
    annotate(state["recipe"])
    result = await native.invoke(client, arguments)
    assert result["status"] == "completed", result
    assert len(state["queries"]) == 1
    powers = [call["args"]["selected"] for call in state["calls"] if call["operation"] == "gui.widget_action"]
    assert powers == [1, 0]
    assert state["cursor"] is None and state["allowed"] is state["active"] is state["core"] is False
    if kind == "multi":
        assert result["acceptance"]["outputDeltas"] == result["acceptance"]["retrievedItems"]
        assert result["acceptance"]["playerInventoryConserved"] is True
        assert state["vector_power_count"] == 1 and state["vector_retrieval_count"] == 2
        assert result["acceptance"]["catalystsRetained"] == result["acceptance"]["preservedCatalystBaseline"]
    else:
        assert result["acceptance"]["consumed"] == 8 and result["acceptance"]["outputDelta"] == 1
        assert state["material"] == 24 and state["output"] == 5 and state["input_material"] == 0
        assert result["acceptance"]["catalystRetained"] is True and state["input_circuit"] == 1


@pytest.mark.parametrize("kind", ["simple", "multi"])
@pytest.mark.parametrize("fault", ["true", "private_row", "unknown_count", "truncated", "extra_false"])
async def test_annotation_refusal_is_read_only_and_does_not_requery_or_feed(native_bridge, monkeypatch, kind, fault):
    client, state = native_bridge
    if kind == "multi":
        multi.vector_transport(client, state, monkeypatch)
        arguments = multi.arguments()
    else:
        arguments = native.declaration()
    annotate(state["recipe"])
    if fault == "true":
        state["recipe"]["metadata"][0]["value"] = True
    elif fault == "private_row":
        state["recipe"]["metadata"][0].pop("publicKeyIdentity")
    elif fault == "unknown_count":
        state["recipe"]["metadataUnknownCount"] = 1
    elif fault == "truncated":
        state["recipe"]["metadataTruncated"] = True
    else:
        state["recipe"]["metadata"].append({"identifier": "cleanroom", "value": False})
        state["recipe"]["metadataTotal"] = 2
    state["query_complete"] = True
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == "no_observed_supported_recipe", result
    assert result["failedPhase"] == "recipe_query" and result["sideEffectsPossible"] is False
    assert len(state["queries"]) == 1 and not native.writes(state)
    assert result["error"]["rejectedRecipeCandidates"][0]["reason"] == "recipe_conditions_unsupported"
    assert state["material"] == 32 and state["input_material"] == 0 and state["output"] == 4
    assert state["cursor"] is None and state["allowed"] is False
    if kind == "multi":
        assert state["vector_power_count"] == state["vector_retrieval_count"] == 0
