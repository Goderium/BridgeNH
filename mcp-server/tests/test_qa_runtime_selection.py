"""Exact generic native mode/channel binding through the complete QA transport path."""
from copy import deepcopy

import jsonschema
import pytest

from minecraft_codex_bridge import qa_machine_flows as group
from minecraft_codex_bridge import qa_tasks as qa
from test_qa_machine_flows import GroupClient, fixtures, run


CAPABILITIES = {"declaredRuntimeSelectionContract": "tstqa-runtime-selection-v1",
    "declaredMachineModeSelection": True, "declaredBlueprintChannels": True,
    "maxDeclaredModeChanges": 8, "maxDeclaredBlueprintChannels": 16, "maxDeclaredChannelValue": 64}


class SelectionClient(GroupClient):
    def __init__(self, selection=None):
        super().__init__(1, dynamic=True)
        self.values[0].update(selection if selection is not None else {
            "machineMode": 1, "blueprintChannels": [{"name": "tier", "value": 1}, {"name": "length", "value": 64}]})
        self.records = fixtures(self.values)
        self.selection_fault = None
        self.capability_fault = None

    async def call(self, operation, args, **kwargs):
        value = await super().call(operation, args, **kwargs)
        if operation == "qa.catalog" and args["section"] == "capabilities":
            value.update(CAPABILITIES)
            if self.capability_fault:
                self.capability_fault(value)
        if operation == "qa.results" and args["section"] == "cases":
            for row in value["entries"]:
                declaration, record = self.values[0], self.records[0]
                if "machineMode" not in declaration and "blueprintChannels" not in declaration:
                    continue
                channels = declaration.get("blueprintChannels", [])
                selected_mode = declaration.get("machineMode", 0)
                binding = {"contract": "tstqa-runtime-selection-v1", "declarationDigest": record["plan"]["declarationDigest"],
                    "initialMachineMode": 0, "initialRecipeMap": "test:default", "requestedMachineModePresent": "machineMode" in declaration,
                    "requestedMachineMode": declaration.get("machineMode"), "actualMachineMode": selected_mode,
                    "actualRecipeMap": declaration["recipe"]["map"], "recipeMapIdentityVerified": True,
                    "modeChanges": selected_mode, "maxModeChanges": 8, "nativeConstructionCompleted": True,
                    "prototypeMutated": False, "channelStorageVerified": True, "nativeChannelConsumptionVerified": False,
                    "structureMaterialProgressionVerified": False, "channels": deepcopy(channels),
                    "trigger": {**declaration["machine"], "count": declaration.get("constructionAmount", 1),
                        "nbt": "{channels:{tier:1,length:64}}" if channels else ""}}
                evidence = row["flowEvidence"]
                evidence["publicRuntimeSelection"] = binding
                evidence["stages"][2]["name"] = "native_public_blueprint_constructed"
                for snapshot in evidence["snapshots"]:
                    snapshot["runtimeSelection"] = deepcopy(binding)
                row["fixtureCoverage"]["after"]["gtTiles"][0].update(machineMode=selected_mode,
                    recipeMap=declaration["recipe"]["map"], recipeMapIdentityVerified=True, runtimeSelectionStateAvailable=True)
            if self.selection_fault:
                self.selection_fault(value["entries"])
        return value


@pytest.mark.parametrize("selection", [
    {"machineMode": 1, "blueprintChannels": [{"name": "tier", "value": 1}]},
    {"machineMode": 0}, {"blueprintChannels": [{"name": "length", "value": 64}]}, {"blueprintChannels": []}, {}])
async def test_selection_and_legacy_keep_complete_finite_flow_and_unknown_effects(selection):
    client = SelectionClient(selection)
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and client.runs == 1
    accepted = result["flowResults"][0]
    assert accepted["quantitativeSummary"]["stableTicks"] == 85
    assert accepted["quantitativeSummary"]["receiverFluids"] == accepted["quantitativeSummary"]["fluidOutputs"]
    if selection:
        assert accepted["runtimeSelectionVerified"]
        assert accepted["runtimeSelection"]["actualMachineMode"] == selection.get("machineMode", 0)
        assert accepted["runtimeSelection"]["channels"] == selection.get("blueprintChannels", [])
        assert accepted["runtimeSelection"]["channelStorageVerified"]
        assert accepted["runtimeSelection"]["nativeChannelConsumptionVerified"] is False
        assert accepted["runtimeSelection"]["structureMaterialProgressionVerified"] is False
    else:
        assert "runtimeSelectionVerified" not in accepted
    dispatched = [args for operation, args in client.operations if operation == "qa.run"]
    assert len(dispatched) == 1 and dispatched[0]["machineFlows"] == client.values


@pytest.mark.parametrize("key", list(CAPABILITIES) + ["fixtureCoverageContract"])
async def test_absent_runtime_capability_rejects_before_native_dispatch(key):
    client = SelectionClient()
    client.capability_fault = lambda value: value.pop(key)
    result = await run(client)
    assert result["status"] != "completed" and not result["groupFullFlowVerified"] and client.runs == 0


def preserve_unrelated_trigger_nbt(rows):
    evidence = rows[0]["flowEvidence"]
    binding = evidence["publicRuntimeSelection"]
    binding["trigger"]["nbt"] = '{other:"preserved"}'
    for snapshot in evidence["snapshots"]:
        snapshot["runtimeSelection"] = deepcopy(binding)


@pytest.mark.parametrize("selection", [{"machineMode": 0}, {"blueprintChannels": []}])
async def test_selection_without_channels_preserves_unrelated_complete_trigger_nbt(selection):
    client = SelectionClient(selection)
    client.selection_fault = preserve_unrelated_trigger_nbt
    result = await run(client)
    assert result["status"] == "completed" and result["groupFullFlowVerified"] and client.runs == 1
    accepted = result["flowResults"][0]
    assert accepted["runtimeSelectionVerified"] and accepted["quantitativeSummary"]["stableTicks"] == 85
    assert accepted["runtimeSelection"]["channels"] == []
    assert accepted["runtimeSelection"]["nativeChannelConsumptionVerified"] is False
    assert accepted["runtimeSelection"]["structureMaterialProgressionVerified"] is False
    dispatched = [args for operation, args in client.operations if operation == "qa.run"]
    assert len(dispatched) == 1 and dispatched[0]["machineFlows"] == client.values


async def test_unrelated_trigger_nbt_checkpoint_drift_without_channels_never_accepts_or_replays():
    client = SelectionClient({"machineMode": 0})
    def drift(rows):
        preserve_unrelated_trigger_nbt(rows)
        rows[0]["flowEvidence"]["snapshots"][4]["runtimeSelection"]["trigger"]["nbt"] += "changed"
    client.selection_fault = drift
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1
    assert not result["flowResults"][0]["verified"]
    assert result["flowResults"][0]["errorCode"] == "qa_runtime_selection_checkpoint_incomplete"
    assert "quantitativeSummary" not in result["flowResults"][0]


@pytest.mark.parametrize("fault", ["missing", "digest", "mode", "typed_mode", "requested_bool", "map", "map_identity",
    "prototype", "construction", "construction_stage", "channel_effect", "material_progression", "channel_bool",
    "channel_changed", "trigger_count", "trigger_nbt", "trigger_identity", "checkpoint_missing", "checkpoint_nbt",
    "checkpoint_bool", "after_mode", "after_map", "after_identity", "after_unavailable"])
async def test_changed_or_partial_selection_never_accepts_or_replays(fault):
    client = SelectionClient()
    def mutate(rows):
        row = rows[0]
        evidence, owner = row["flowEvidence"], row["fixtureCoverage"]["after"]["gtTiles"][0]
        binding = evidence["publicRuntimeSelection"]
        if fault == "missing":
            evidence.pop("publicRuntimeSelection")
        elif fault == "checkpoint_missing":
            evidence["snapshots"][2].pop("runtimeSelection")
        elif fault == "checkpoint_nbt":
            evidence["snapshots"][4]["runtimeSelection"]["trigger"]["nbt"] += "changed"
        elif fault == "checkpoint_bool":
            evidence["snapshots"][4]["runtimeSelection"]["prototypeMutated"] = 0
        elif fault == "construction_stage":
            evidence["stages"][2]["name"] = "registered_native_machine_placed"
        elif fault.startswith("after_"):
            owner[{"after_mode": "machineMode", "after_map": "recipeMap", "after_identity": "recipeMapIdentityVerified",
                "after_unavailable": "runtimeSelectionStateAvailable"}[fault]] = {
                "after_mode": 0, "after_map": "test:other", "after_identity": False, "after_unavailable": False}[fault]
        else:
            changes = {"digest": ("declarationDigest", "0" * 64), "mode": ("actualMachineMode", 0),
                "typed_mode": ("actualMachineMode", True), "requested_bool": ("requestedMachineMode", True),
                "map": ("actualRecipeMap", "test:other"), "map_identity": ("recipeMapIdentityVerified", False),
                "prototype": ("prototypeMutated", True), "construction": ("nativeConstructionCompleted", False),
                "channel_effect": ("nativeChannelConsumptionVerified", True), "material_progression": ("structureMaterialProgressionVerified", True)}
            if fault in changes:
                key, value = changes[fault]; binding[key] = value
            elif fault == "channel_bool":
                binding["channels"][0]["value"] = True
            elif fault == "channel_changed":
                binding["channels"][0]["value"] = 2
            elif fault == "trigger_count":
                binding["trigger"]["count"] = 2
            elif fault == "trigger_nbt":
                binding["trigger"]["nbt"] = ""
            else:
                binding["trigger"]["registryName"] = "test:other"
            for snapshot in evidence["snapshots"]:
                snapshot["runtimeSelection"] = deepcopy(binding)
    client.selection_fault = mutate
    result = await run(client)
    assert result["status"] == "failed" and not result["groupFullFlowVerified"] and client.runs == 1
    assert not result["flowResults"][0]["verified"]
    assert "quantitativeSummary" not in result["flowResults"][0]


@pytest.mark.parametrize("selection", [{"machineMode": True}, {"machineMode": "1"}, {"machineMode": -1},
    {"machineMode": 2147483648}, {"blueprintChannels": [{"name": "Tier", "value": 1}]},
    {"blueprintChannels": [{"name": "a", "value": True}]}, {"blueprintChannels": [{"name": "a", "value": 65}]}])
def test_malformed_typed_selection_rejected_by_tool_schema(selection):
    client = SelectionClient(selection)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({"expectedSessionId": "session", "expectedWorld": "world", "selector": group.DECLARED_SELECTOR,
            "machineFlows": client.values, "requireFullFlow": True}, qa.QA_TEST_ONCE_SCHEMA)
    assert client.runs == 0
