"""Real native task regressions for output drift before feed and power."""

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_shared_basic_fluid_tasks as shared
import test_single_recipe_tasks as single
from minecraft_codex_bridge.recipe_tasks import _RecipeTask


native_bridge = native.native_bridge
single_machine = single.single_machine


def power_ons(state):
    return [call for call in state["calls"] if call["operation"] == "gui.widget_action"
            and call["args"].get("selected") == 1]


async def test_ordinary_output_drift_before_feed_retains_materials_and_blocks_native_feed(native_bridge, monkeypatch):
    client, state = native_bridge
    original = _RecipeTask.ensure_materials

    async def drift(self, required, *, grant=True):
        result = await original(self, required, grant=grant)
        if grant:
            state["output"] += 1
        return result

    monkeypatch.setattr(_RecipeTask, "ensure_materials", drift)
    result = await native.invoke(client)
    assert result["reason"] == "output_baseline_changed", result
    assert result["failedPhase"] == "output_baseline_before_feed_1"
    assert result["executionsCompleted"] == 0 and state["input_material"] == 0
    assert state["material"] == 32 and state["cursor"] is None
    assert not power_ons(state)
    assert not any(call["operation"] == "gui.slot_click" for call in state["calls"])


@pytest.mark.parametrize("when,reason", [(1, "output_baseline_changed"), (2, "ASSERTION_FAILED")])
async def test_ordinary_output_drift_before_power_or_between_read_and_power_blocks_power(native_bridge, monkeypatch, when, reason):
    client, state = native_bridge
    original = client.call
    reads = 0

    async def drift(operation, args, **options):
        nonlocal reads
        if operation == "world.inspect" and args["x"] == native.OUTPUT["x"] and state["input_material"] == 8:
            reads += 1
            if reads == when:
                state["output"] += 1
        return await original(operation, args, **options)

    monkeypatch.setattr(client, "call", drift)
    result = await native.invoke(client)
    assert result["reason"] == reason, result
    assert result["executionsCompleted"] == 0 and state["input_material"] == 8
    assert state["material"] == 24 and state["cursor"] is None
    assert not power_ons(state)
    if when == 2:
        failed = next(phase for phase in result["phases"] if phase.get("status") == "failed")
        assert failed["phase"] == "execute_1" and failed["failedPhase"] == "resolve"


async def test_ordinary_partial_output_preflight_blocks_power_without_replaying_feed(native_bridge, monkeypatch):
    client, state = native_bridge
    original = client.call

    async def partial(operation, args, **options):
        data = await original(operation, args, **options)
        if operation == "world.inspect" and args["x"] == native.OUTPUT["x"] and state["input_material"] == 8:
            data["inventory"]["truncated"] = True
        return data

    monkeypatch.setattr(client, "call", partial)
    result = await native.invoke(client)
    assert result["reason"] == "inventory_coverage_incomplete", result
    assert result["failedPhase"] == "output_baseline_before_power_1"
    assert state["input_material"] == 8 and not power_ons(state)
    clicks = [call["args"] for call in state["calls"] if call["operation"] == "gui.slot_click"]
    assert len(clicks) == 10 and sum(click["slot"] == 80 and click["button"] == 0 for click in clicks) == 2


@pytest.mark.parametrize("fault", ["quantity", "foreign_item", "owner"])
async def test_multi_complete_output_baseline_drift_before_feed_blocks_all_consumed_items(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    original = _RecipeTask.ensure_materials
    original_call = client.call
    armed = False

    async def supply(self, required, *, grant=True):
        nonlocal armed
        result = await original(self, required, grant=grant)
        armed |= grant
        if grant and fault == "quantity":
            state["vector_output"][0]["count"] += 1
        elif grant and fault == "foreign_item":
            state["vector_output"][3] = native.stack({"registryName": "minecraft:stone", "metadata": 0}, 1)
        return result

    async def owner_drift(operation, args, **options):
        data = await original_call(operation, args, **options)
        if armed and fault == "owner" and operation == "world.inspect" and args["x"] == native.OUTPUT["x"]:
            data["gregtech"]["metaTileId"] += 1
        return data

    monkeypatch.setattr(_RecipeTask, "ensure_materials", supply)
    monkeypatch.setattr(client, "call", owner_drift)
    result = await native.invoke(client, multi.arguments())
    assert result["reason"] == ("multi_output_owner_changed" if fault == "owner" else "multi_output_baseline_changed"), result
    assert result["failedPhase"] == "multi_output_baseline_before_feed_1"
    assert state["vector_power_count"] == 0 and state["cursor"] is None
    assert all(item is None or item["registryName"] == native.CATALYST["registryName"]
               for item in state["vector_input"].values())


@pytest.mark.parametrize("when,reason", [(1, "multi_output_baseline_changed"), (2, "ASSERTION_FAILED")])
async def test_multi_drift_before_power_and_inside_workflow_blocks_power(native_bridge, monkeypatch, when, reason):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    original = client.call
    reads = 0

    async def drift(operation, args, **options):
        nonlocal reads
        fed = sum(item["count"] for item in state["vector_input"].values()
                  if item is not None and item["registryName"] != native.CATALYST["registryName"])
        if fed == 10 and operation == "world.inspect" and args["x"] == native.OUTPUT["x"]:
            reads += 1
            if reads == when:
                state["vector_output"][1]["count"] += 1
        return await original(operation, args, **options)

    monkeypatch.setattr(client, "call", drift)
    result = await native.invoke(client, multi.arguments())
    assert result["reason"] == reason, result
    assert state["vector_power_count"] == 0 and state["vector_retrieval_count"] == 0
    assert state["cursor"] is None and result["executionsCompleted"] == 0
    assert sum(item["count"] for item in state["vector_input"].values()
               if item is not None and item["registryName"] != native.CATALYST["registryName"]) == 10


@pytest.mark.parametrize("when", ["before_feed", "before_power"])
async def test_shared_fluid_item_output_drift_retains_exact_native_tank_and_item_state(single_machine, monkeypatch, when):
    client, state = single_machine
    shared.transport(client, state, monkeypatch)
    state["vector_input"][0] = native.stack(native.CATALYST, 1)
    state["player_items"][11] = None
    original_supply = _RecipeTask.ensure_materials
    original_call = client.call
    changed = False

    def mutate_output():
        nonlocal changed
        state["vector_output"][6] = native.stack(native.PRODUCT, 1)
        changed = True

    async def supply(self, required, *, grant=True):
        result = await original_supply(self, required, grant=grant)
        if grant and when == "before_feed":
            mutate_output()
        return result

    async def controls(operation, args, **options):
        data = await original_call(operation, args, **options)
        if when == "before_power" and not changed and operation == "gui.widgets" and state["fluid_activations"] == ["input"]:
            mutate_output()
        return data

    monkeypatch.setattr(_RecipeTask, "ensure_materials", supply)
    monkeypatch.setattr(client, "call", controls)
    result = await native.invoke(client, shared.declaration())
    assert result["reason"] == "multi_output_baseline_changed", result
    assert state["vector_power_count"] == 0 and state["cursor"] is None
    assert state["fluid_activations"] == ([] if when == "before_feed" else ["input"])
    assert state["named_state"] == {"input": (None, 0) if when == "before_feed" else ("hydrogen", 3000), "output": (None, 0)}
    assert result["executionsCompleted"] == 0
