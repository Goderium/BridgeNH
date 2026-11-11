from copy import deepcopy
import anyio
import pytest

from minecraft_codex_bridge import ae_tasks
from minecraft_codex_bridge.batch_receipts import install_checkpoint
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from test_workflows import bridge, plan, action, read, operations

PART = {"registryName": "ae:part", "metadata": 1, "side": 6}
ARGS = {"expectedSessionId": "session", "expectedWorld": "test-world", "dimension": 0, "targets": [
    {"attachment": {"x": x, "y": 64, "z": 0, "targetIdentity": {"registryName": "minecraft:stone"}, "allowedSides": [1]},
     "hotbarSlot": 0, "expectedPart": PART} for x in (1, 2)]}


async def test_install_resume_rechecks_destinations_and_only_supplies_missing(monkeypatch):
    args = deepcopy(ARGS)
    args["resume"] = install_checkpoint(args, {0}, 1)
    _validate("mcb_ae_install", args)
    placements, supplies = [], []
    async def initialize(self): pass
    async def step(self, tool, arguments, **kwargs):
        assert tool == "mcb_world_inspect_many"
        return {"source": "integrated_server", "dimension": 0, "results": [{"ok": True, "data": {
            **p, "dimension": 0, "registryName": "ae:cable" if p["x"] == 1 else "minecraft:air",
            "parts": [PART] if p["x"] == 1 else []}} for p in arguments["positions"]]}
    async def materials(self, declarations=None):
        supplies.extend(declarations)
        return {"sufficient": True}
    async def install(self, target):
        placements.append(target)
        return {"serverConfirmed": True}
    monkeypatch.setattr(ae_tasks.AeTask, "initialize", initialize)
    monkeypatch.setattr(ae_tasks.AeTask, "step", step)
    monkeypatch.setattr(ae_tasks.AeTask, "installation_materials", materials)
    monkeypatch.setattr(ae_tasks.AeTask, "install", install)
    result = await ae_tasks.run_configure(None, args, anyio.Lock(), None, install=True)
    assert result["status"] == "completed" and placements == supplies == [args["targets"][1]]
    assert result["completed"][0]["alreadyMatched"]
    assert result["checkpoint"]["completedIndices"] == [0, 1]


@pytest.mark.parametrize("case", ["uncertain", "world", "partition"])
async def test_resume_invalid_or_uncertain_checkpoint_never_takes_over(monkeypatch, case):
    args = deepcopy(ARGS)
    args["resume"] = install_checkpoint(args, {0}, 1, {"outcome_unknown": case == "uncertain"})
    if case == "world": args["expectedWorld"] = "other"
    if case == "partition": args["resume"]["pendingIndices"] = [0]
    async def initialize(self): pytest.fail("invalid resume took over")
    monkeypatch.setattr(ae_tasks.AeTask, "initialize", initialize)
    with pytest.raises(BridgeError):
        await ae_tasks.run_configure(None, args, anyio.Lock(), None, install=True)


async def test_completed_destination_drift_stops_before_any_new_install():
    args = deepcopy(ARGS)
    class Task(ae_tasks.AeTask):
        async def step(self, tool, arguments, **kwargs):
            return {"source": "integrated_server", "dimension": 0, "results": [
                {"ok": True, "data": {**p, "dimension": 0, "registryName": "minecraft:air", "parts": []}}
                for p in arguments["positions"]]}
    with pytest.raises(BridgeError, match="previously completed"):
        await Task(None, args, None).reconcile_installation({0})


async def test_duplicate_destination_is_rejected_before_takeover(monkeypatch):
    args = deepcopy(ARGS)
    args["targets"][1] = deepcopy(args["targets"][0])
    async def initialize(self): pytest.fail("duplicate plan took over")
    monkeypatch.setattr(ae_tasks.AeTask, "initialize", initialize)
    with pytest.raises(BridgeError, match="same destination"):
        await ae_tasks.run_configure(None, args, anyio.Lock(), None, install=True)


async def test_sequence_submission_and_explicit_postcondition_are_distinct(bridge):
    adapter, state = bridge
    state["data"]["inventory.inspect"] = {"selectedHotbarSlot": 1}
    result = await adapter.call_tool("mcb_sequence", plan(action(), read("after", tool="mcb_inventory"),
        {"id": "accepted", "kind": "assert", "source": "after", "accepts": ["act"],
         "condition": {"path": "selectedHotbarSlot", "operator": "equals", "value": 1}}))
    assert not result.isError
    receipt = result.structuredContent["data"]["checkpoint"]
    assert receipt["actions"][0]["state"] == "condition_verified" and receipt["effectAcceptanceComplete"]
    result = await adapter.call_tool("mcb_sequence", plan(action()))
    assert result.structuredContent["data"]["checkpoint"]["actions"][0]["state"] == "acknowledged"


async def test_failed_acceptance_cannot_promote_or_replay_action(bridge):
    adapter, state = bridge
    state["data"]["inventory.inspect"] = {"selectedHotbarSlot": 0}
    result = await adapter.call_tool("mcb_sequence", plan(action(), read("after", tool="mcb_inventory"),
        {"id": "accepted", "kind": "assert", "source": "after", "accepts": ["act"],
         "condition": {"path": "selectedHotbarSlot", "operator": "equals", "value": 1}}, action("never")))
    assert result.isError and operations(state).count("player.hotbar") == 1
    receipt = result.structuredContent["data"]["checkpoint"]
    assert receipt["actions"][0]["state"] == "acknowledged" and receipt["unexecutedStepIds"] == ["never"]


async def test_every_declared_acceptance_must_pass_for_the_same_action(bridge):
    adapter, state = bridge
    state["data"]["inventory.inspect"] = {"selectedHotbarSlot": 1, "cursorItem": {"count": 1}}
    assertions = [{"id": name, "kind": "assert", "source": "after", "accepts": ["act"],
        "condition": {"path": path, "operator": "equals", "value": value}}
        for name, path, value in [("selected", "selectedHotbarSlot", 1), ("empty", "cursorItem", None)]]
    result = await adapter.call_tool("mcb_sequence", plan(action(), read("after", tool="mcb_inventory"), *assertions))
    assert result.isError
    checkpoint = result.structuredContent["data"]["checkpoint"]
    assert checkpoint["actions"][0]["state"] == "acknowledged" and not checkpoint["effectAcceptanceComplete"]


@pytest.mark.parametrize("source", ["before", "act"])
async def test_pre_action_or_acknowledgement_cannot_be_acceptance(bridge, source):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_sequence", plan(read("before"), action(),
        {"id": "accepted", "kind": "assert", "source": source, "accepts": ["act"],
         "condition": {"path": "submitted", "operator": "equals", "value": True}}))
    assert result.isError and not state["calls"]
