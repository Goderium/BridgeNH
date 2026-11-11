"""Public group positions, finite shared sources and native AE composition contracts."""
import copy

import pytest

import test_construction_tasks as native
from test_construction_declared_goals import add_group_selection, observe_hook
from minecraft_codex_bridge.assembly_plan import (
    compile_connections, construction_slots, expand_ports, verify_group_materials, verify_networks)
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.validation import tool_validator

native_construction = native.native_construction


def public_evidence(operation, arguments, data):
    if operation == "world.structure":
        add_group_selection(data["runtimeObservation"])
    elif operation == "client.snapshot":
        data["player"]["creative"] = True


def grouped(count=3):
    args = native.declaration()
    args["requestedPorts"] = [p for p in args["requestedPorts"] if p["role"] != "item_input"]
    args["portGroups"] = [{"id": "inputs", "role": "item_input", "hotbarSlot": 2,
        "item": native.PORT_ITEMS["item_input"].copy(),
        "groupSelector": {"descriptionKeys": list(native.ROLE_KEYS.values()), "occurrence": 0},
        "positions": [{"x": -1, "y": -1, "z": z} for z in range(count)]}]
    return args


def reference(anchor="inputs.0", **changes):
    return {"anchor": anchor, "offset": {"x": -1, "y": 0, "z": 0},
        "targetIdentity": {"registryName": "ae:host", "metadata": 0}, "allowedSides": [4], **changes}


def connection():
    return {"install": [{"attachment": reference(), "hotbarSlot": 4,
        "expectedPart": {"registryName": "ae:part", "metadata": 1, "side": 5}}],
        "configure": [{"target": reference(), "priority": 10, "settings": {"BLOCK": "YES"}}]}


def test_group_declaration_keeps_order_identity_and_old_source_safety():
    args = grouped(8)
    tool_validator("mcb_blueprint_build").validate(args)
    expanded = expand_ports(args)
    assert len(expanded["requestedPorts"]) == 11
    assert expanded["constructionOrder"] == "ports_first"
    assert [p["id"] for p in expanded["requestedPorts"][3:]] == ["inputs." + str(i) for i in range(8)]
    assert construction_slots(expanded) == [0, 2, 3, 5, 6, 8]
    assert "relativePosition" not in args["portGroups"][0]
    expanded["requestedPorts"][0]["hotbarSlot"] = 2
    with pytest.raises(BridgeError, match="explicit port groups"):
        construction_slots(expanded)


@pytest.mark.parametrize("creative,count,success", [(True, 1, True), (False, 2, False), (False, 3, True)])
def test_group_materials_require_aggregate_survival_count(creative, count, success):
    args = expand_ports(grouped())
    materials = {2: {"item": native.PORT_ITEMS["item_input"], "count": count}}
    if success:
        verify_group_materials(args, materials, not creative)
    else:
        with pytest.raises(BridgeError, match="aggregate count"):
            verify_group_materials(args, materials, not creative)


def split_input_groups():
    args = grouped(3)
    first = args["portGroups"][0]
    later = copy.deepcopy(first)
    later.update(id="laterInputs", positions=first["positions"][1:])
    first["positions"] = first["positions"][:1]
    args["portGroups"].append(later)
    return args


@pytest.mark.parametrize("survival,count,success", [(False, 1, True), (True, 2, False), (True, 3, True)])
def test_distinct_groups_share_exact_source_with_combined_demand(survival, count, success):
    args = expand_ports(split_input_groups())
    assert construction_slots(args) == [0, 2, 3, 5, 6, 8]
    materials = {2: {"item": native.PORT_ITEMS["item_input"], "count": count}}
    if success:
        verify_group_materials(args, materials, survival)
    else:
        with pytest.raises(BridgeError, match="aggregate count"):
            verify_group_materials(args, materials, survival)


@pytest.mark.parametrize("fault", ["role", "item", "blueprint", "support"])
def test_shared_group_source_still_rejects_conflicting_identity_or_reservation(fault):
    args = split_input_groups()
    later = args["portGroups"][1]
    if fault == "role": later["role"] = "item_output"
    if fault == "item": later["item"]["metadata"] += 1
    if fault == "blueprint": later["hotbarSlot"] = args["blueprintHotbarSlot"]
    if fault == "support": later["hotbarSlot"] = args["supportHotbarSlot"]
    with pytest.raises(BridgeError):
        construction_slots(expand_ports(args))


async def test_shared_source_groups_complete_existing_native_pipeline(native_construction, monkeypatch):
    client, state = native_construction
    observe_hook(monkeypatch, client, public_evidence)
    result = await native.invoke(client, split_input_groups())
    assert result["status"] == "completed", result
    inputs = [p for p in result["selectedPorts"] if p["role"] == "item_input"]
    assert [p["id"] for p in inputs] == ["inputs.0", "laterInputs.0", "laterInputs.1"]
    assert all(p["placementConfirmed"] and p["preservedAfterNativeShell"] for p in inputs)
    assert state["items"][2]["count"] == 1 and state["construct_count"] == 1


@pytest.mark.parametrize("missing", ["role_label", "suggestions", "both"])
@pytest.mark.parametrize("registered", [True, False])
async def test_explicit_empty_cell_ports_use_native_acceptance_when_hints_are_partial(
        native_construction, monkeypatch, missing, registered):
    # Live GT assembly-line hints omit the output role in the side-casing group
    # and return zero suggestions for required input buses. Neither is rejection.
    client, state = native_construction
    args = native.declaration()
    del args["requestedPorts"]
    keys = [v for k, v in native.ROLE_KEYS.items() if not (missing in ("role_label", "both") and k == "item_output")]
    positions = {"item_input": (-1, -1, 0), "item_output": (1, -1, 0),
        "energy": (-1, -1, 1), "maintenance": (1, -1, 1)}
    args["portGroups"] = [{"id": role, "role": role, "hotbarSlot": native.PORT_SLOTS[role],
        "item": native.PORT_ITEMS[role].copy(), "groupSelector": {"descriptionKeys": keys, "occurrence": 0},
        "positions": [dict(zip(("x", "y", "z"), xyz))]} for role, xyz in positions.items()]
    if not registered: state["missing_registered_role"] = "item_output"
    def hook(op, arguments, data):
        if op == "world.structure":
            for group in data["runtimeObservation"]["groups"]:
                if group["id"] == 0:
                    group["descriptionKeys"] = keys.copy()
                    if missing in ("suggestions", "both"): group["suggestedCandidates"] = []
        public_evidence(op, arguments, data)
    observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, args)
    assert result["status"] == ("completed" if registered else "failed"), result
    if registered:
        assert result["formationVerified"] and all(p["placementConfirmed"] for p in result["selectedPorts"])
        output = next(p for p in result["selectedPorts"] if p["role"] == "item_output")
        assert output["groupSelection"]["semanticRoleVerified"] is False
        if missing in ("suggestions", "both"): assert output["suggestedCandidates"] == []
    else:
        assert result["reason"] == "registered_role_not_accepted", result


@pytest.mark.parametrize("fault,reason", [
    ("outside", "public_port_category_unavailable"),
    ("binding", "public_port_group_binding_unavailable"),
    ("truncated", "public_port_category_unavailable"),
    ("legacy", "public_port_group_binding_unavailable"),
])
async def test_partial_hint_support_never_relaxes_exact_membership_or_legacy_selection(
        native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    args = grouped(1)
    keys = [v for k, v in native.ROLE_KEYS.items() if k != "item_input"]
    args["portGroups"][0]["groupSelector"]["descriptionKeys"] = keys
    if fault == "outside": args["portGroups"][0]["positions"][0]["z"] = 100
    if fault == "legacy":
        args["requestedPorts"].append({"role": "item_input", "id": "legacy", "hotbarSlot": 2,
            "groupSelector": args["portGroups"][0]["groupSelector"]})
        del args["portGroups"]
        args["constructionOrder"] = "ports_first"
    def hook(op, arguments, data):
        if op == "world.structure":
            for group in data["runtimeObservation"]["groups"]:
                if group["id"] == 0: group["descriptionKeys"] = keys.copy()
        public_evidence(op, arguments, data)
        if op == "world.structure":
            for group in data["runtimeObservation"]["groups"]:
                if group["id"] != 0: continue
                if fault == "binding": group["roleSelection"]["captureId"] = "different-capture"
                if fault == "truncated": group["descriptionTruncated"] = True
    observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, args)
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert state["construct_count"] == 0
    assert not any(p.get("placementConfirmed") for p in result["selectedPorts"])


@pytest.mark.parametrize("fault", ["duplicate", "controller", "generated", "shell", "over_budget"])
def test_invalid_groups_fail_before_any_runtime(fault):
    args = grouped()
    if fault == "duplicate": args["portGroups"][0]["positions"].append({"x": -1, "y": -1, "z": 0})
    if fault == "controller": args["portGroups"][0]["positions"][0] = {a: 0 for a in ("x", "y", "z")}
    if fault == "generated": args["generateMaterials"] = True
    if fault == "shell": args["constructionOrder"] = "shell_first"
    if fault == "over_budget": args["portGroups"][0]["positions"] = [{"x": i + 1, "y": 0, "z": 0} for i in range(32)]
    with pytest.raises(BridgeError): expand_ports(args)


def deferred_group(role="item_output"):
    args = grouped(1)
    args["requestedPorts"] = [port for port in args["requestedPorts"] if port["role"] != role]
    args["portGroups"].append({"id": "deferred", "role": role, "hotbarSlot": native.PORT_SLOTS[role],
        "item": native.PORT_ITEMS[role].copy(), "installPhase": "after_shell",
        "groupSelector": {"descriptionKeys": list(native.ROLE_KEYS.values()), "occurrence": 0},
        "positions": [{"x": 1, "y": -1, "z": 2}]})
    return args


def test_deferred_group_is_explicit_and_rejects_survival_before_runtime():
    args = deferred_group()
    tool_validator("mcb_blueprint_build").validate(args)
    assert expand_ports(args)["requestedPorts"][-1]["installPhase"] == "after_shell"
    args["actualSurvival"] = True
    with pytest.raises(BridgeError, match="survival replacement accounting"):
        expand_ports(args)


async def test_deferred_output_reuses_replacement_once_after_shell(native_construction, monkeypatch):
    client, state = native_construction
    shell_ports = []
    def observe(op, args, data):
        public_evidence(op, args, data)
        if op == "player.interact" and args.get("sneaking") is True:
            shell_ports.extend(row["item"]["metadata"] for row in state["placements"])
    observe_hook(monkeypatch, client, observe)
    result = await native.invoke(client, deferred_group())
    assert result["status"] == "completed" and result["formationVerified"], result
    output = next(port for port in result["selectedPorts"] if port["role"] == "item_output")
    assert native.PORT_ITEMS["item_output"]["metadata"] not in shell_ports
    assert output["constructedBaseline"] == native.CASING and output["digConfirmed"]
    assert output["placementConfirmed"] and output["afterShellIdentityVerified"]
    assert not output["preservedAfterNativeShell"] and "beforeNativeShell" not in output
    assert all(port["preservedAfterNativeShell"] for port in result["selectedPorts"] if port != output)
    assert state["construct_count"] == state["rechecks"] == 1
    assert sum(row["item"] == native.stack(native.PORT_ITEMS["item_output"]) for row in state["placements"]) == 1
    assert result["cleanup"]["allConfirmedSupportsRemoved"]


async def test_deferred_placement_unknown_is_not_replayed(native_construction, monkeypatch):
    client, state = native_construction
    def observe(op, args, data):
        public_evidence(op, args, data)
        if op == "player.place_block" and args.get("hotbarSlot") == native.PORT_SLOTS["item_output"]:
            raise BridgeError("TEST_RESPONSE_LOST", "Native placement happened but its acknowledgement was lost.")
    observe_hook(monkeypatch, client, observe)
    result = await native.invoke(client, deferred_group())
    assert result["status"] == "failed" and state["construct_count"] == 1
    placements = [row for row in state["placements"] if row["item"]["metadata"] == native.PORT_ITEMS["item_output"]["metadata"]]
    assert len(placements) == 1 and not result["formationVerified"]
    assert state["rechecks"] == 0


@pytest.mark.parametrize("fault,reason", [
    ("no_candidates", "after_shell_baseline_unavailable"),
    ("tile", "constructed_baseline_unconfirmed"),
    ("wrong_casing", "constructed_baseline_unconfirmed"),
    ("overwrite", "blueprint_port_identity_changed"),
    ("registration", "registered_role_not_accepted"),
])
async def test_deferred_port_keeps_baseline_preservation_and_registration_guards(native_construction, monkeypatch, fault, reason):
    client, state = native_construction
    arguments = deferred_group()
    if fault == "no_candidates":
        for index, port in enumerate(arguments.pop("requestedPorts")):
            arguments["portGroups"].append({"id": port["role"], "role": port["role"], "hotbarSlot": port["hotbarSlot"],
                "item": native.PORT_ITEMS[port["role"]].copy(),
                "groupSelector": {"descriptionKeys": list(native.ROLE_KEYS.values()), "occurrence": 0},
                "positions": [{"x": 1, "y": -1, "z": index}]})
    point = tuple(native.CONTROLLER[a] + offset for a, offset in zip(("x", "y", "z"), (1, -1, 2)))
    if fault == "overwrite": state["preserve_preinstalled"] = False
    if fault == "registration": state["missing_registered_role"] = "item_output"
    def observe(op, args, data):
        public_evidence(op, args, data)
        if op == "world.structure" and fault == "no_candidates":
            for group in data["runtimeObservation"]["groups"]:
                if group["id"] == 0: group["suggestedCandidates"] = []
        if op == "player.interact" and args.get("sneaking") is True:
            if fault == "tile": state["blocks"][point]["tileClass"] = "Foreign.Tile"
            if fault == "wrong_casing": state["blocks"][point] = {"registryName": "minecraft:stone", "metadata": 0}
    observe_hook(monkeypatch, client, observe)
    result = await native.invoke(client, arguments)
    assert result["status"] == "failed" and result["reason"] == reason, result
    assert state["construct_count"] == (0 if fault == "no_candidates" else 1)
    output_placements = [row for row in state["placements"] if row["item"]["metadata"] == native.PORT_ITEMS["item_output"]["metadata"]]
    assert len(output_placements) == (1 if fault == "registration" else 0)
    assert not result["formationVerified"]


async def test_full_native_ports_first_shared_stack_and_declared_position_order(native_construction, monkeypatch):
    client, state = native_construction
    observe_hook(monkeypatch, client, public_evidence)
    args = grouped()
    args["portGroups"][0]["positions"].extend([{"x": 1, "y": -1, "z": z} for z in range(3)])
    result = await native.invoke(client, args)
    assert result["status"] == "completed", result
    ports = [p for p in result["selectedPorts"] if p.get("portGroup") == "inputs"]
    assert [p["ordinal"] for p in ports] == list(range(6))
    assert [p["position"]["z"] for p in ports] == [native.CONTROLLER["z"] + i for i in range(3)] * 2
    assert all(p["placementConfirmed"] and p["preservedAfterNativeShell"] for p in ports)
    assert state["items"][2]["count"] == 1
    assert len(result["selectedPorts"]) == 9 and state["construct_count"] == 1
    assert not {native.key(p["position"]) for p in ports} & set(state["digs"])


def test_connections_resolve_order_faces_and_reserve_ae_height():
    args = grouped()
    args["connectionPlan"] = connection()
    tool_validator("mcb_blueprint_build").validate(args)
    args = expand_ports(args)
    plan = compile_connections(args)
    target = plan["install"][0]["attachment"]
    assert target["x"] == native.CONTROLLER["x"] - 2
    assert plan["layoutComponents"][0]["relativeBounds"]["min"] == {"x": -3, "y": -1, "z": 0}
    assert plan["layoutComponents"][0]["role"] == "equipment"
    assert plan["configure"][0]["priority"] == 10
    moved = copy.deepcopy(args)
    moved["controller"]["y"] += 10
    assert compile_connections(moved)["install"][0]["attachment"]["y"] == target["y"] + 10


@pytest.mark.parametrize("wrong", [False, True])
async def test_native_registered_input_order_is_required_when_declared(native_construction, monkeypatch, wrong):
    client, state = native_construction
    args = grouped()
    args["portGroups"][0]["expectedRoleIndices"] = [0, 1, 2]
    def hook(op, arguments, data):
        public_evidence(op, arguments, data)
        if op == "machine.inspect" and "components" in data:
            inputs = [e for e in data["components"]["entries"] if e["role"] == "item_input"]
            for i, entry in enumerate(inputs): entry["roleIndex"] = i + int(wrong)
    observe_hook(monkeypatch, client, hook)
    result = await native.invoke(client, args)
    assert result["status"] == ("failed" if wrong else "completed"), result
    if wrong:
        assert result["reason"] == "registered_port_order_mismatch"
    else:
        assert all(p["registeredOrderVerified"] for p in result["selectedPorts"] if p.get("portGroup") == "inputs")


async def test_ae_cells_join_real_placement_layout_before_any_task_action(native_construction):
    import time
    from minecraft_codex_bridge.construction_tasks import _ConstructionTask
    from minecraft_codex_bridge.workflows import run_workflow
    from minecraft_codex_bridge.placement_tasks import assembly_layout
    client, state = native_construction
    args = grouped()
    args["connectionPlan"] = connection()
    # This constructor-only test deliberately does not invoke/calibrate terrain.
    args["placement"] = {"layoutComponents": []}
    task = _ConstructionTask(client, args, run_workflow, started=time.monotonic())
    components = task.args["placement"]["layoutComponents"]
    assert len(components) == 1 and components[0]["role"] == "equipment"
    bounds = {"min": {"x": -1, "y": 0, "z": 0}, "max": {"x": 1, "y": 1, "z": 2}}
    layout = assembly_layout(bounds, components)
    assert layout["aboveGroundRelativeBounds"]["min"] == {"x": -3, "y": -1, "z": 0}
    assert state["calls"] == []


@pytest.mark.parametrize("fault", ["anchor", "face", "duplicate_mount", "hidden_equipment", "networks"])
def test_connection_plan_rejects_ambiguous_targets(fault):
    args = expand_ports(grouped())
    args["connectionPlan"] = connection()
    row = args["connectionPlan"]["install"][0]
    if fault == "anchor": row["attachment"]["anchor"] = "missing"
    if fault == "face": row["expectedPart"]["side"] = 4
    if fault == "duplicate_mount": args["connectionPlan"]["install"].append(copy.deepcopy(row))
    if fault == "hidden_equipment": row["layoutPlacement"] = "hidden_bottom"
    if fault == "networks": args["connectionPlan"]["networks"] = [{"id": "same", "endpoints": []}] * 2
    with pytest.raises(BridgeError): compile_connections(args)


@pytest.mark.parametrize("grids,success", [(["A", "A", "B"], True), (["A", "B", "C"], False), (["A", "A", "A"], False), ([None, "A", "B"], False)])
async def test_network_acceptance_requires_live_membership_and_isolation(grids, success):
    class Reader:
        async def worlds(self, targets): return [await self.world(target) for target in targets]
        async def world(self, target):
            grid = grids[target["x"]]
            return {"ae": {"hosts": [{"mountingSide": -1, "network": {"nodes": [{"side": 6,
                "available": grid is not None, "gridId": grid}]}}]}}
    def endpoint(index): return {"target": {"x": index, "targetIdentity": {}}, "nodeSide": 6}
    networks = [{"id": "inputs", "endpoints": [endpoint(0), endpoint(1)]}, {"id": "return", "endpoints": [endpoint(2)]}]
    if success:
        assert (await verify_networks(Reader(), networks))["verified"] is True
    else:
        with pytest.raises(BridgeError): await verify_networks(Reader(), networks)


@pytest.mark.parametrize("fail", [False, True])
@pytest.mark.parametrize("strategy", [None, "walking", "ability_flight", "developer_teleport"])
async def test_connection_execution_reuses_ae_goals_and_retains_partial_ack(native_construction, monkeypatch, fail, strategy):
    from minecraft_codex_bridge.ae_tasks import AeTask
    client, state = native_construction
    args = grouped()
    args["connectionPlan"] = connection()
    if strategy is not None:
        args["movementStrategy"] = strategy
    state["items"][4] = native.stack({"registryName": "ae:part", "metadata": 1}, 2)
    observe_hook(monkeypatch, client, public_evidence)
    calls = []
    async def initialize(task):
        assert task.args["strategy"] == (strategy or "ability_flight")
    original_materials = AeTask.installation_materials
    async def materials(task):
        calls.append(("materials", state["construct_count"]))
        return await original_materials(task)
    async def install(task, declaration):
        assert task.args["strategy"] == (strategy or "ability_flight")
        calls.append(("install", state["construct_count"]))
        assert declaration["hotbarSlot"] == 4 and state["items"][4]["count"] == 2
        return {"serverConfirmed": True, "target": declaration["attachment"]}
    async def configure(task, declaration):
        calls.append(("configure", state["construct_count"]))
        if fail: raise BridgeError("AE_OWNER_CHANGED", "native owner changed")
        return {"serverConfirmed": True, "after": {"priority": 10}}
    monkeypatch.setattr(AeTask, "initialize", initialize)
    monkeypatch.setattr(AeTask, "installation_materials", materials)
    monkeypatch.setattr(AeTask, "install", install)
    monkeypatch.setattr(AeTask, "configure", configure)
    result = await native.invoke(client, args)
    assert result["status"] == ("failed" if fail else "completed"), result
    assert calls == [("materials", 0), ("materials", 1), ("install", 1), ("configure", 1)]
    report = result["connections"]
    assert report["status"] == result["status"] and len(report["installed"]) == 1
    assert report["replayAllowed"] is False
    assert len(report["configured"]) == (0 if fail else 1)


async def test_missing_connection_material_stops_before_controller(native_construction, monkeypatch):
    from minecraft_codex_bridge.ae_tasks import AeTask
    client, state = native_construction
    args = grouped()
    args["connectionPlan"] = connection()
    async def initialize(task): pass
    observe_hook(monkeypatch, client, public_evidence)
    monkeypatch.setattr(AeTask, "initialize", initialize)
    result = await native.invoke(client, args)
    assert result["status"] == "unavailable", result
    assert result["reason"] == "AE_MATERIALS_INSUFFICIENT"
    assert state["placements"] == [] and state["construct_count"] == 0


@pytest.mark.parametrize("open_gui,receipt", [(False, {"status": "unavailable"}),
    (True, {"status": "completed", "interactionEffectConfirmed": False})])
async def test_ae_never_continues_after_unconfirmed_approach(monkeypatch, open_gui, receipt):
    from minecraft_codex_bridge import ae_tasks
    calls = []
    async def approach(*a, **kw): return receipt
    class Task(ae_tasks.AeTask):
        async def step(self, tool, args=None, **kw): calls.append(tool)
        async def empty_hand(self): pass
    monkeypatch.setattr(ae_tasks, "run_interaction_approach", approach)
    task = Task(None, {"expectedSessionId": "s", "expectedWorld": "w", "dimension": 0}, None)
    with pytest.raises(BridgeError) as failure:
        await task.approach({}, open_gui=open_gui)
    assert failure.value.code == "AE_APPROACH_UNCONFIRMED"
    assert failure.value.details["receipt"] == receipt and failure.value.details["replayAllowed"] is False
    assert calls == (["mcb_close_gui"] if open_gui else [])


@pytest.mark.parametrize("fault", [None, "position", "partial", "unavailable"])
async def test_network_hosts_share_one_batch_with_exact_owner_validation(fault):
    from minecraft_codex_bridge.ae_tasks import AeTask
    calls = []
    target = {"x": 1, "y": 64, "z": 2, "targetIdentity": {"registryName": "ae:host", "metadata": 0}}
    class Task(AeTask):
        async def step(self, tool, args=None, **kw):
            calls.append((tool, args))
            row = {"ok": fault != "unavailable", "data": {"source": "integrated_server", "dimension": 0,
                "x": 5 if fault == "position" else 1, "y": 64, "z": 2, "registryName": "ae:host", "metadata": 0}}
            return {"source": "integrated_server", "dimension": 0, "results": [] if fault == "partial" else [row]}
    task = Task(None, {"dimension": 0}, None)
    if fault:
        with pytest.raises(BridgeError): await task.worlds([target, target])
    else:
        assert len(await task.worlds([target, target])) == 2
    assert len(calls) == 1 and calls[0][0] == "mcb_world_inspect_many"
    assert len(calls[0][1]["positions"]) == 1
