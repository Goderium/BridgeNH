"""Transport regressions for one-use, no-action recipe preparation observations."""
import copy

import pytest

import test_recipe_tasks as native
import test_multi_recipe_tasks as multi
import test_fluid_recipe_tasks as fluid
import test_native_observation_wait as wait
from minecraft_codex_bridge import native_wait
from minecraft_codex_bridge.fluid_recipe_tasks import FluidRecipeResources
from minecraft_codex_bridge.multi_recipe_tasks import _MultiRecipeTask
from minecraft_codex_bridge.recipe_tasks import inventory_baseline_assertion
from minecraft_codex_bridge.validation import tool_validator

native_bridge = native.native_bridge


def preparation_window(client, monkeypatch, *, defect=None):
    """Observe only the inventory proof after successful final material supply."""
    evidence = {"active": False, "reads": [], "validated": []}
    original_ensure = _MultiRecipeTask.ensure_materials
    original_validate = FluidRecipeResources.validate_player
    original_call = client.call

    async def ensure(task, required, *, grant=True):
        result = await original_ensure(task, required, grant=grant)
        if grant:
            evidence["active"] = True
        return result

    async def call(operation, args, **options):
        result = await original_call(operation, args, **options)
        if evidence["active"] and operation == "inventory.inspect":
            if defect == "partial":
                result["slots"].pop()
            elif defect == "container":
                result["slots"][0]["item"]["count"] -= 1
            evidence["reads"].append(result)
            if defect:
                evidence["active"] = False
        return result

    async def validate(resources, player):
        if evidence["active"]:
            evidence["validated"].append(player)
            evidence["active"] = False
        return await original_validate(resources, player)

    monkeypatch.setattr(_MultiRecipeTask, "ensure_materials", ensure)
    monkeypatch.setattr(FluidRecipeResources, "validate_player", validate)
    monkeypatch.setattr(client, "call", call)
    return evidence


@pytest.mark.parametrize("with_fluid", [False, True])
async def test_one_complete_prepared_inventory_serves_items_and_actual_containers(native_bridge, monkeypatch, with_fluid):
    client, state = native_bridge
    if with_fluid:
        fluid.transport(client, state, monkeypatch)
        args = fluid.declaration()
    else:
        multi.vector_transport(client, state, monkeypatch)
        args = multi.arguments()
    wait.enable_native(client, state, monkeypatch)
    evidence = preparation_window(client, monkeypatch)
    result = await native.invoke(client, args)
    assert result["status"] == "completed", result
    assert len(evidence["reads"]) == len(evidence["validated"]) == 1
    assert evidence["validated"][0] is evidence["reads"][0]
    assert state["vector_power_count"] == 1 and state["cursor"] is None
    assert result["acceptance"]["playerInventoryConserved"] is True
    if with_fluid:
        assert state["fluid_actions"] == ["fluid_input", "fluid_output"]


@pytest.mark.parametrize("defect", ["partial", "container"])
async def test_prepared_read_keeps_full_slot_and_container_conservation_refusal(native_bridge, monkeypatch, defect):
    client, state = native_bridge
    fluid.transport(client, state, monkeypatch)
    wait.enable_native(client, state, monkeypatch)
    evidence = preparation_window(client, monkeypatch, defect=defect)
    result = await native.invoke(client, fluid.declaration())
    assert result["status"] != "completed", result
    assert len(evidence["reads"]) == 1 and state["vector_power_count"] == 0
    assert state["fluid_actions"] == []
    if defect == "container":
        assert result["reason"] == "fluid_container_player_conservation_failed"


def prepare_window(client, state, monkeypatch, *, defect=None, after_prepare=False):
    evidence = {"active": False, "calls": [], "baseline": None, "plans": []}
    original_prepare = native_wait.prepare
    original_call = client.call

    async def prepare(task, *args):
        evidence["active"] = True
        try:
            result = await original_prepare(task, *args)
        finally:
            evidence["active"] = False
        if result is not None:
            evidence["plans"].append(result[0])
            evidence["baseline"] = copy.deepcopy(result[1])
            if after_prepare:
                state["vector_output"][0]["count"] += 1
        return result

    async def call(operation, args, **options):
        result = await original_call(operation, args, **options)
        if evidence["active"]:
            evidence["calls"].append(operation)
            if operation == "world.inspect_many" and defect:
                output = result["results"][args["positions"].index(native.OUTPUT)]["data"]
                if defect == "owner": output["gregtech"]["metaTileId"] += 1
                elif defect == "bag": output["inventory"]["slots"][0]["item"]["count"] += 1
                elif defect == "slots": output["inventory"]["slots"].pop()
                elif defect == "tanks": output["fluids"]["truncated"] = True
                elif defect == "unexpected_fluid":
                    output["fluids"].update(totalTanks=1,
                        tanks=[{"index": 0, "capacity": 1000, "fluid": "water", "amount": 1}])
                wait.rescale(result)
        return result

    monkeypatch.setattr(native_wait, "prepare", prepare)
    monkeypatch.setattr(client, "call", call)
    return evidence


@pytest.mark.parametrize("executions", [1, 2])
async def test_native_prepare_reuses_its_full_output_and_keeps_fresh_power_before_read(native_bridge, monkeypatch, executions):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    wait.enable_native(client, state, monkeypatch)
    evidence = prepare_window(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments(executions=executions))
    assert result["status"] == "completed", result
    assert evidence["calls"] == ["world.inspect_many"] * executions
    assert len(evidence["plans"]) == executions
    power_indices = [index for index, row in enumerate(state["wire_calls"])
        if row["operation"] == "gui.widget_action" and row["args"].get("selected") == 1]
    for index in power_indices:
        # Snapshot guards may surround the baseline, but no mutation may replace it.
        prior = [row for row in state["wire_calls"][:index] if row["operation"] != "client.snapshot"]
        assert prior[-1]["operation"] == "world.inspect" and prior[-1]["args"] == {**native.OUTPUT, "dimension": 180}
    waits = [row for row in state["wire_calls"] if "nativeWait" in row["args"]]
    assert len(waits) == 2 * executions and state["vector_power_count"] == executions
    for row in waits:
        tool_validator("mcb_world_inspect_many").validate(row["args"])
        assert not any(key.startswith("_") for key in row["args"])
    assert result["acceptance"]["playerInventoryConserved"] is True
    phases = [row for row in result["phases"] if row["phase"].startswith("multi_output_baseline_before_power_")]
    assert len(phases) == executions and all(row["completeOutputInventoryChecked"] for row in phases)


@pytest.mark.parametrize("version", [None, True, 2])
async def test_legacy_capability_route_keeps_original_separate_power_baseline(native_bridge, monkeypatch, version):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    wait.enable_native(client, state, monkeypatch, version=version)
    evidence = prepare_window(client, state, monkeypatch)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] == "completed", result
    assert evidence["calls"] == [] and evidence["plans"] == []
    before_power = [row for row in state["wire_calls"] if row["powerCount"] == 0
        and row["operation"] == "world.inspect" and row["args"] == {**native.OUTPUT, "dimension": 180}]
    # Baseline before feed, Python baseline before power and workflow baseline.
    assert len(before_power) == 3
    assert not any("nativeWait" in row["args"] for row in state["wire_calls"])


@pytest.mark.parametrize("defect", ["owner", "bag", "slots", "tanks", "unexpected_fluid"])
async def test_native_prepare_refuses_owner_bag_full_coverage_and_tank_drift_before_power(native_bridge, monkeypatch, defect):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    wait.enable_native(client, state, monkeypatch)
    evidence = prepare_window(client, state, monkeypatch, defect=defect)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert evidence["calls"] == ["world.inspect_many"]
    assert state["vector_power_count"] == state["vector_retrieval_count"] == 0
    assert result["failedPhase"] == "multi_output_baseline_before_power_1"


async def test_drift_after_native_prepare_is_caught_by_the_real_power_before_baseline(native_bridge, monkeypatch):
    client, state = native_bridge
    multi.vector_transport(client, state, monkeypatch)
    wait.enable_native(client, state, monkeypatch)
    evidence = prepare_window(client, state, monkeypatch, after_prepare=True)
    reports = []
    original_runner = native.run_workflow

    async def runner(*args, **options):
        report = await original_runner(*args, **options)
        reports.append(report)
        return report

    monkeypatch.setattr(native, "run_workflow", runner)
    result = await native.invoke(client, multi.arguments())
    assert result["status"] != "completed", result
    assert len(evidence["plans"]) == 1 and state["vector_power_count"] == state["vector_retrieval_count"] == 0
    assert any(row["operation"] == "world.inspect" and row["args"] == {**native.OUTPUT, "dimension": 180}
        for row in state["wire_calls"])
    assert inventory_baseline_assertion("baseline", evidence["baseline"])["condition"]["operator"] == "json_sha256_equals"
    assert any(report["steps"][report["failedIndex"]]["id"] == "inventory_baseline_unchanged"
        for report in reports if report["failedIndex"] is not None)
