"""Public proof, finite feeds and transport counts across recipe optimizations."""

import copy

import jsonschema
import pytest

import test_recipe_tasks as native
from test_single_recipe_tasks import single_machine, arguments
from test_ghost_recipe_tasks import ghost_machine, arguments as ghost_arguments
from test_recipe_input_discovery import public_bindings
from minecraft_codex_bridge import bridge
from minecraft_codex_bridge.recipe_tasks import _RecipeTask, _Unavailable
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import run_workflow

native_bridge = native.native_bridge


def actual_snapshot(state):
    position = state["position"]
    return {"sessionId": "session", "profile": "developer", "worldLoaded": True,
        "world": {"worldName": "test-world", "worldFolder": "test-world", "dimension": 180},
        "background": native.PROTECTED, "screenOpen": state["open"] is not None,
        "screenId": "screen:" + state["open"] if state["open"] is not None else "",
        "player": {"name": "TestPlayer", "health": state.get("health", 20), **position, "feetY": position["y"], "eyeY": position["y"] + 1.62,
            "flying": state["flying"], "serverFlight": {"available": True, "flying": state["flying"],
                "allowFlying": True, "dimension": 180, "ageMillis": 0}}}


def modern_transport(client, state, monkeypatch, *, fault=None):
    original = client.call
    used_fault = False

    async def contextual(operation, args, **options):
        nonlocal used_fault
        requested = bridge._REQUEST_CONTEXT.get()
        before = actual_snapshot(state)
        with bridge.request_context(None):
            value = await original(operation, args, **options)
        if operation == "bridge.health":
            value["contextRpc"] = {"version": 1, "reads": sorted(bridge.CONTEXT_READS),
                "actions": sorted(bridge.CONTEXT_ACTIONS)}
        if operation == "client.snapshot":
            value.update(screenOpen=state["open"] is not None,
                screenId="screen:" + state["open"] if state["open"] is not None else "")
        if requested is None:
            return value
        evidence = {"version": 1, "operation": operation,
            "phase": "before" if operation in bridge.CONTEXT_ACTIONS else "after",
            "snapshot": before if operation in bridge.CONTEXT_ACTIONS else actual_snapshot(state)}
        if operation == "inventory.inspect" and fault == "final_inventory_context" and state["output"] > 4:
            evidence["snapshot"]["world"]["dimension"] = 0
        if operation == "gui.inspect" and state["gui_reads"] >= 2 and fault and not used_fault:
            used_fault = True
            if fault == "missing":
                return value
            if fault == "screen":
                evidence["snapshot"]["screenId"] = "screen:replacement"
            elif fault == "dimension":
                evidence["snapshot"]["world"]["dimension"] = 0
            elif fault == "source_count":
                value = copy.deepcopy(value)
                source = next(slot for slot in value["slots"] if slot.get("item", {})
                    and slot["item"]["registryName"] == native.MATERIAL["registryName"])
                source["item"]["count"] -= 1
        return bridge._ContextData(value, evidence)

    monkeypatch.setattr(client, "call", contextual)


def automatic_args(**changes):
    args = arguments(**changes)
    args.pop("inputGuiSlot")
    return args


@pytest.mark.parametrize("modern", [False, True])
async def test_same_actual_single_container_is_opened_once_and_feed_acceptance_remains_real(single_machine, monkeypatch, modern):
    client, state = single_machine
    public_bindings(state)
    if modern:
        modern_transport(client, state, monkeypatch)
    result = await native.invoke(client, automatic_args(preflightControls=True))
    assert result["status"] == "completed", result
    assert sum(call["operation"] == "player.interact" for call in state["calls"]) == 1
    assert any(phase["phase"] == "reuse_machine" for phase in result["phases"])
    assert any(phase["phase"] == "source_sync_material" for phase in result["phases"]) is (not modern)
    assert result["acceptance"]["consumed"] == 1 and result["acceptance"]["outputDelta"] == 4
    assert result["acceptance"]["controllerStopped"] and state["cursor"] is None
    assert state["input_material"] == 0 and result["inventoryPreparation"]["finalInventoryVerified"]


@pytest.mark.parametrize("fault", ["missing", "screen", "dimension"])
async def test_advertised_fresh_gui_context_never_falls_back_to_old_snapshot(single_machine, monkeypatch, fault):
    client, state = single_machine
    public_bindings(state)
    modern_transport(client, state, monkeypatch, fault=fault)
    result = await native.invoke(client, automatic_args(preflightControls=True))
    assert result["status"] == "failed", result
    assert not any(call["operation"] in {"gui.slot_click", "chat.send"}
        or call["operation"] == "gui.widget_action" and call["args"].get("selected") == 1 for call in state["calls"])


async def test_fresh_source_count_mismatch_uses_original_sync_path(single_machine, monkeypatch):
    client, state = single_machine
    public_bindings(state)
    modern_transport(client, state, monkeypatch, fault="source_count")
    result = await native.invoke(client, automatic_args())
    assert result["status"] == "completed", result
    assert any(phase["phase"] == "source_sync_material" for phase in result["phases"])


async def test_final_inventory_own_context_drift_does_not_claim_complete_task_acceptance(single_machine, monkeypatch):
    client, state = single_machine
    public_bindings(state)
    modern_transport(client, state, monkeypatch, fault="final_inventory_context")
    result = await native.invoke(client, automatic_args())
    assert result["status"] == "failed" and result["recipeAcceptanceCompleted"] is True, result
    assert result["acceptance"]["outputDelta"] == 4
    assert result["inventoryPreparation"]["finalInventoryVerified"] is False
    assert result["failedPhase"] == "inventory_retention_verification"


@pytest.mark.parametrize("retained", [False, True])
async def test_physical_catalyst_uses_second_actual_input_and_one_real_retained_item(single_machine, monkeypatch, retained):
    client, state = single_machine
    state["single_catalyst_inventory_index"] = 8
    state["single_gui"]["slots"][0]["inventoryIndex"] = 8
    state["input_circuit"], state["circuit"] = (1, 0) if retained else (0, 1)
    state["recipe"]["items"]["inputs"]["entries"].append({"index": 1,
        "stack": native.stack(native.CATALYST, 0), "chance": 10000, "nonConsumedCatalyst": True})
    state["recipe"]["items"]["inputs"]["total"] = 2
    owner, _ = public_bindings(state, mappings={2: 5, 0: 8})
    modern_transport(client, state, monkeypatch)
    args = automatic_args(preflightControls=True)
    args.pop("catalystGuiSlot")
    result = await native.invoke(client, args)
    assert result["status"] == "completed", result
    assert result["inputGuiSlot"] == 2 and result["catalystGuiSlot"] == 0
    assert result["catalystGuiSlotSource"] == "public_machine_input_binding"
    assert result["catalystGuiBinding"]["owner"] == owner
    assert result["acceptance"]["catalystRetained"] and result["acceptance"]["consumed"] == 1
    assert state["input_circuit"] == 1 and state["circuit"] == 0 and state["cursor"] is None
    assert sum(call["operation"] == "player.interact" for call in state["calls"]) == 1
    assert sum(call["operation"] == "gui.slot_click" and call["args"]["slot"] == 0
        for call in state["calls"]) == (0 if retained else 1)


@pytest.mark.parametrize("fault", ["no_second", "owner", "duplicate"])
async def test_physical_catalyst_unknown_or_ambiguous_second_binding_refuses_before_pickup(single_machine, fault):
    client, state = single_machine
    state["input_circuit"], state["circuit"] = 0, 1
    state["single_catalyst_inventory_index"] = 8
    state["single_gui"]["slots"][0]["inventoryIndex"] = 8
    state["recipe"]["items"]["inputs"]["entries"].append({"index": 1,
        "stack": native.stack(native.CATALYST, 0), "chance": 10000, "nonConsumedCatalyst": True})
    state["recipe"]["items"]["inputs"]["total"] = 2
    _, bind = public_bindings(state, mappings={2: 5, **({} if fault == "no_second" else {0: 8})})

    def changed(data):
        bind(data)
        if fault == "owner":
            data["slots"][0]["machineInventoryOwner"]["metaTileId"] += 1
        elif fault == "duplicate":
            data["slots"][1].update(copy.deepcopy(data["slots"][0]))
            data["slots"][1]["slot"] = 1

    state["gui_observation_hook"] = changed
    args = automatic_args()
    args.pop("catalystGuiSlot")
    result = await native.invoke(client, args)
    assert result["status"] == "failed", result
    assert not any(call["operation"] == "gui.slot_click" or call["operation"] == "gui.widget_action"
        and call["args"].get("selected") == 1 for call in state["calls"])


def backend_arguments():
    return {"mode": "backend_match", **native.MACHINE, "dimension": 180,
        "inputs": [{"source": "registry", **native.MATERIAL, "count": 1}],
        "output": {"source": "registry", **native.PRODUCT}}


@pytest.mark.parametrize("query", [
    {"map": "gt.recipe.assembler", "input": {"source": "registry", "registryName": "minecraft:stone"}},
    {"cursor": "native-cursor", "dimension": 180, "limit": 1, "maxPages": 2},
    {**native.MACHINE, "dimension": 180, "output": {"source": "inventory", "slot": 0}},
    backend_arguments(),
    {**backend_arguments(), "fluids": [], "specialSlot": None},
])
def test_recipe_query_schema_accepts_catalog_and_explicit_backend_modes(query):
    tool_validator("mcb_recipe_query").validate(query)


@pytest.mark.parametrize("query", [
    {"map": "gt.recipe.assembler", "inputs": backend_arguments()["inputs"]},
    {"cursor": "native-cursor", "output": {"source": "registry", **native.PRODUCT}},
    {**native.MACHINE, "fluids": []},
    {**native.MACHINE, "mode": "catalog"},
    {**backend_arguments(), "unknown": True},
    {key: value for key, value in backend_arguments().items() if key != "mode"},
    {key: value for key, value in backend_arguments().items() if key != "inputs"},
    {key: value for key, value in backend_arguments().items() if key != "x"},
    [],
])
def test_recipe_query_schema_keeps_branch_fields_required_and_closed(query):
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_recipe_query").validate(query)


def backend_transport(client, monkeypatch, *, proof_fault=None):
    original = client.call

    async def native_lookup(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "recipes.query":
            assert args["mode"] == "backend_match" and args["inputs"][0]["count"] == 1
            assert not {"limit", "map", "cursor", "input", "maxPages"}.intersection(args)
            recipe = value["recipes"][0]
            recipe.pop("categoryRecipeIndex")
            recipe.update(candidateOrdinal=0, candidateSource="public_backend_find", category=None)
            value.update(mode="backend_match", lookupSource="public_backend_find", lookupComplete=True,
                registeredCatalogComplete=False, executionValidated=False, hasMore=False, nextCursor=None,
                matched=True, candidateValidated=True, complete=False, partial=True,
                validation={"inputMatch": True, "enabled": True, "notFake": True,
                    "voltageCompatible": True, "outputMatch": True})
            if proof_fault:
                value[proof_fault] = False
        return value

    monkeypatch.setattr(client, "call", native_lookup)


async def test_public_backend_recipe_tool_and_once_share_explicit_count_contract(single_machine, monkeypatch):
    client, state = single_machine
    state["movementImplementation"] = "developer_teleport"
    backend_transport(client, monkeypatch)
    adapter = Adapter(client)
    query = await adapter.call_tool("mcb_recipe_query", backend_arguments())
    assert not query.isError
    args = arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"])
    result = await adapter.call_tool("mcb_recipe_once", args)
    assert not result.isError, result
    data = result.structuredContent["data"]
    assert data["recipe"]["candidateOrdinal"] == 0 and "categoryRecipeIndex" not in data["recipe"]
    assert data["recipe"]["registeredCatalogComplete"] is False
    assert data["acceptance"]["consumed"] == 1 and data["acceptance"]["outputDelta"] == 4


@pytest.mark.parametrize("fault", ["lookupComplete", "candidateValidated"])
async def test_backend_unknown_or_invalid_first_candidate_never_falls_back_or_feeds(single_machine, monkeypatch, fault):
    client, state = single_machine
    backend_transport(client, monkeypatch, proof_fault=fault)
    result = await native.invoke(client, arguments(recipeLookup="backend_match", backendInputs=backend_arguments()["inputs"]))
    assert result["status"] == "unavailable", result
    assert len(state["queries"]) == 1 and native.writes(state) == []


@pytest.mark.parametrize("extra", [{"cursor": "consumed"}, {"map": "any"}, {"limit": 1}, {"maxPages": 2},
    {"input": {"source": "registry", "registryName": "minecraft:stone"}}, {"fluids": ["water"]}, {"specialSlot": {}}])
def test_backend_public_schema_rejects_catalog_mix_and_unhandled_conditions(extra):
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_recipe_query").validate({**backend_arguments(), **extra})


@pytest.mark.parametrize("count", [None, 0, 65, False])
def test_backend_public_schema_requires_exact_positive_finite_count(count):
    args = backend_arguments()
    if count is None:
        args["inputs"][0].pop("count")
    else:
        args["inputs"][0]["count"] = count
    with pytest.raises(jsonschema.ValidationError):
        tool_validator("mcb_recipe_query").validate(args)


async def test_verified_recipe_adapter_query_uses_no_extra_health_and_keeps_pinned_transport(single_machine):
    client, state = single_machine
    task = _RecipeTask(client, arguments(), run_workflow)
    task.recipe_query_preflight_verified = True
    await task.tracked_client.invoke("recipes.query", {**native.MACHINE, "output": {"source": "registry", **native.PRODUCT}})
    assert [call["operation"] for call in state["calls"]] == ["recipes.query"]
    task.recipe_query_preflight_verified = False
    await task.tracked_client.invoke("recipes.query", {**native.MACHINE, "output": {"source": "registry", **native.PRODUCT}})
    assert [call["operation"] for call in state["calls"]][-2:] == ["bridge.health", "recipes.query"]


async def test_advertised_native_ghost_selection_jumps_once_then_confirms_real_retention(ghost_machine):
    client, state = ghost_machine
    native.CATALYST["metadata"] = 24
    state["recipe"]["items"]["inputs"]["entries"][1]["stack"]["metadata"] = 24
    state["missing_ghost_inventory"] = True
    state["ghost_widget"]["circuitConfig"].update(value=-1, noConfiguration=True)
    state["ghost_widget"]["supportedActions"].append("set_value")
    result = await native.invoke(client, ghost_arguments())
    assert result["status"] == "completed", result
    actions = [call for call in state["calls"] if call["operation"] == "gui.widget_action"
        and call["args"].get("widgetId") == state["ghost_widget"]["widgetId"]]
    assert len(actions) == 1 and actions[0]["args"]["action"] == "set_value" and actions[0]["args"]["value"] == 24
    assert result["acceptance"]["configuredGhostCircuit"]["configuration"] == 24
    assert result["acceptance"]["configuredGhostCircuit"]["count"] == 0 and state["input_circuit"] == 0


@pytest.mark.parametrize("opened", [False, True])
async def test_native_closed_plan_skips_only_proven_noop_close(native_bridge, opened):
    from test_native_approach_tasks import install_planner
    client, state = native_bridge
    state["open"] = "input" if opened else None
    install_planner(client, state)
    task = _RecipeTask(client, native.declaration(), run_workflow)
    task.approach_health = await task.call("bridge.health", {})
    task.capabilities = task.approach_health["capabilities"]
    _, steps, _ = await task.approach_plan("input")
    assert (steps[0].get("tool") == "mcb_close_gui") is opened
    if not opened:
        assert {step["id"] for step in steps} >= {"screen_still_closed", "screen_id_still_empty", "client_flying"}


async def test_closed_plan_changed_gui_fails_actual_flight_snapshot_before_any_native_mutation(native_bridge, monkeypatch):
    from test_native_approach_tasks import install_planner
    client, state = native_bridge
    install_planner(client, state)
    original = client.call

    async def opened_after_plan(operation, args, **options):
        value = await original(operation, args, **options)
        if operation == "player.plan_approach":
            state["open"] = "replacement"
        return value

    monkeypatch.setattr(client, "call", opened_after_plan)
    task = _RecipeTask(client, native.declaration(), run_workflow)
    task.approach_health = await task.call("bridge.health", {})
    task.capabilities = task.approach_health["capabilities"]
    _, steps, _ = await task.approach_plan("input")
    with pytest.raises(bridge.BridgeError) as failure:
        await task.workflow("open_input", steps)
    assert failure.value.code == "ASSERTION_FAILED"
    assert native.writes(state) == []
