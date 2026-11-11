"""Optional debug energy configuration follows the recorded public native GUI."""

import copy
import time

import pytest

import test_construction_tasks as native
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import BLUEPRINT_BUILD_SCHEMA

native_construction = native.native_construction
DEBUG_API = "gregtech.api.metatileentity.implementations.MTEHatchEnergyDebug"
TEXT_API = "com.cleanroommc.modularui.widgets.textfield.TextFieldWidget"


def recorded_public_controls(label="§r电压等级 (§f§l§n§oMAX+§r)"):
    # The field class, sibling parent and dynamic localized label were observed
    # in the live Debug Energy Hatch GUI. Unrelated fields prevent numeric guesses.
    field = {"widgetId": "energy:tier", "parentId": "energy:voltage_row", "class": TEXT_API,
        "kind": "text", "enabled": True, "valid": True, "supportedActions": ["set_text", "focus"],
        "text": "15", "value": "15"}
    caption = {"widgetId": "energy:tier_label", "parentId": field["parentId"], "kind": "label",
        "contextOnly": True, "enabled": True, "valid": True, "text": label, "supportedActions": []}
    amperage = {**field, "widgetId": "energy:amperage", "parentId": "energy:amperage_row", "text": "2", "value": "2"}
    refill = {**field, "widgetId": "energy:refill", "parentId": "energy:refill_row", "text": "600", "value": "600"}
    return [refill, caption, amperage, field]


@pytest.fixture
async def debug_energy(native_construction):
    client, state = native_construction
    state.update(energy_support=True, energy_meta_class=DEBUG_API, energy_tier=15,
        energy_gui_tier=15, energy_voltage=8589934592, energy_expected_voltage=32768,
        energy_widgets=recorded_public_controls(), energy_requests=[])
    yield client, state


@pytest.mark.parametrize("order", ["shell_first", "ports_first"])
@pytest.mark.parametrize("tier,voltage", [(0, 8), (6, 32768), (14, 2147483640), (15, 8589934592)])
async def test_requested_debug_tier_uses_one_native_commit_and_real_server_and_registered_acceptance(debug_energy, order, tier, voltage):
    client, state = debug_energy
    state["energy_expected_voltage"] = voltage
    result = await native.invoke(client, native.declaration(constructionOrder=order, energyTier=tier))
    assert result["status"] == "completed" and result["formationVerified"], result
    proof = result["energyConfiguration"]
    assert proof["placementConfirmed"] and proof["configured"] and proof["serverConfirmed"] and proof["registeredTierConfirmed"]
    assert proof["requestedTier"] == proof["synchronizedGuiTier"] == tier and proof["inputVoltage"] == str(voltage)
    assert len(state["energy_requests"]) == (0 if tier == 15 else 1)
    if state["energy_requests"]:
        assert state["energy_requests"][0] == {"screenId": "screen:energy", "widgetId": "energy:tier", "action": "set_text",
            "text": str(tier), "commit": True}
    energy = next(row for row in result["acceptance"]["registeredRoles"] if row["role"] == "energy")
    assert energy["inputTier"] == tier
    assert all(call["operation"] != "dev.machine" for call in state["calls"])
    requests = [index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action"
        and call["args"]["action"] == "set_text"]
    formation = next(index for index, call in enumerate(state["calls"]) if call["operation"] == "gui.widget_action"
        and call["args"]["action"] == "recheck_structure")
    assert not requests or requests[0] < formation


async def test_explicit_deferred_energy_configures_once_after_placement(debug_energy, monkeypatch):
    from test_assembly_plan import deferred_group, public_evidence
    from test_construction_declared_goals import observe_hook
    client, state = debug_energy
    observe_hook(monkeypatch, client, public_evidence)
    args = deferred_group("energy")
    args["energyTier"] = 6
    result = await native.invoke(client, args)
    assert result["status"] == "completed" and result["formationVerified"], result
    proof = result["energyConfiguration"]
    assert proof["serverConfirmed"] and proof["registeredTierConfirmed"]
    assert proof.get("configuredBeforeNativeShell") is not True
    assert state["energy_voltage"] == 32768 and len(state["energy_requests"]) == 1


async def test_english_public_voltage_tier_label_is_supported_without_numeric_or_coordinate_guessing(debug_energy):
    client, state = debug_energy
    state["energy_widgets"] = recorded_public_controls("Voltage Tier (MAX+)")
    result = await native.invoke(client, native.declaration(energyTier=6, constructionOrder="ports_first"))
    assert result["status"] == "completed", result
    assert [request["widgetId"] for request in state["energy_requests"]] == ["energy:tier"]


@pytest.mark.parametrize("order", ["ports_first", "shell_first"])
async def test_native_energy_commit_occurs_immediately_after_its_port_and_in_the_declared_shell_phase(debug_energy, monkeypatch, order):
    client, state = debug_energy
    original = client.call
    commits = []

    async def checked(operation, arguments, **options):
        if operation == "gui.widget_action" and arguments.get("action") == "set_text":
            assert state["built"] is (order == "shell_first")
            if order == "ports_first":
                assert state["placements"][-1]["item"]["metadata"] == native.PORT_ITEMS["energy"]["metadata"]
                assert not any(row["item"]["metadata"] == native.PORT_ITEMS["maintenance"]["metadata"] for row in state["placements"])
            commits.append(state["construct_count"])
        return await original(operation, arguments, **options)

    monkeypatch.setattr(client, "call", checked)
    result = await native.invoke(client, native.declaration(constructionOrder=order, energyTier=6))
    assert result["status"] == "completed" and result["formationVerified"], result
    proof = result["energyConfiguration"]
    assert commits == [int(order == "shell_first")]
    assert proof["configuredBeforeNativeShell"] is (order == "ports_first")
    assert proof["configurationReplayAllowed"] is False and proof["laterGuiAccessVerified"] is False
    if order == "ports_first":
        assert proof["preservedAfterNativeShell"] is True
        assert proof["afterShellVerification"] == "fresh_same_identity_public_voltage_read"
        assert proof["afterShellInputVoltage"] == "32768"


async def test_pre_shell_configuration_succeeds_when_shell_closes_later_native_gui_access(debug_energy, monkeypatch):
    client, state = debug_energy
    original = client.call

    async def enclosed(operation, arguments, **options):
        data = await original(operation, arguments, **options)
        if state["built"]:
            state["energy_no_gui"] = True
        return data

    monkeypatch.setattr(client, "call", enclosed)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", energyTier=6))
    assert result["status"] == "completed" and result["formationVerified"], result
    proof = result["energyConfiguration"]
    assert proof["configuredBeforeNativeShell"] and proof["serverConfirmed"] and proof["preservedAfterNativeShell"]
    assert proof["laterGuiAccessVerified"] is False and len(state["energy_requests"]) == 1
    target = proof["position"]
    openings = [call for call in state["calls"] if call["operation"] == "player.interact"
        and call["args"].get("sneaking") is False and all(call["args"].get(axis) == target[axis] for axis in ("x", "y", "z"))]
    assert len(openings) == state["construct_count"] == state["rechecks"] == 1


@pytest.mark.parametrize("drift", ["voltage", "api"])
async def test_after_shell_voltage_or_api_drift_stops_with_fresh_read_only_proof_and_no_gui_replay(debug_energy, monkeypatch, drift):
    client, state = debug_energy
    original = client.call

    async def changed_by_shell(operation, arguments, **options):
        data = await original(operation, arguments, **options)
        if state["built"]:
            if drift == "voltage":
                state["energy_voltage"] = 8
            else:
                state["energy_meta_class"] = "example.ChangedEnergyAPI"
        return data

    monkeypatch.setattr(client, "call", changed_by_shell)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "energy_tier_after_shell_changed", result
    proof = result["energyConfiguration"]
    assert proof["configuredBeforeNativeShell"] and proof["configured"] and proof["serverConfirmed"]
    assert proof.get("preservedAfterNativeShell") is not True and proof["laterGuiAccessVerified"] is False
    assert len(state["energy_requests"]) == state["construct_count"] == 1 and state["rechecks"] == 0
    target = proof["position"]
    assert sum(call["operation"] == "player.interact" and call["args"].get("sneaking") is False
        and all(call["args"].get(axis) == target[axis] for axis in ("x", "y", "z")) for call in state["calls"]) == 1


async def test_unknown_pre_shell_commit_stops_before_the_next_port_and_shell_without_retry(debug_energy):
    client, state = debug_energy
    state["energy_commit_disconnect"] = True
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", energyTier=6))
    assert result["status"] == "failed" and result["lastActionReceipt"]["outcomeUnknown"], result
    assert len(state["energy_requests"]) == 1 and state["construct_count"] == state["rechecks"] == 0
    assert result["energyConfiguration"]["configuredBeforeNativeShell"] is True
    assert result["energyConfiguration"]["serverConfirmed"] is False and not result["formationVerified"]
    assert not any(row["item"]["metadata"] == native.PORT_ITEMS["maintenance"]["metadata"] for row in state["placements"])


async def test_prior_unknown_energy_attempt_cannot_dispatch_another_configuration(debug_energy):
    client, state = debug_energy
    instance = native._ConstructionTask(client, native.declaration(energyTier=6), native.run_workflow, started=time.monotonic())
    instance.energy_configuration = {"configured": False, "serverConfirmed": False, "configuredBeforeNativeShell": True}
    with pytest.raises(BridgeError) as caught:
        await instance.configure_energy_tier(before_shell=True)
    assert caught.value.details["reason"] == "energy_tier_already_attempted" and not state["calls"]


async def test_omitted_energy_tier_keeps_existing_energy_behavior(debug_energy):
    client, state = debug_energy
    result = await native.invoke(client)
    assert result["status"] == "completed" and "energyConfiguration" not in result, result
    assert not state["energy_requests"] and state["energy_tier"] == 15
    assert not any(call["operation"] == "gui.widgets" and call["args"].get("screenId") == "screen:energy"
        for call in state["calls"])


async def test_energy_tier_without_declared_energy_role_is_unavailable_before_writes(native_construction):
    client, state = native_construction
    arguments = native.declaration(energyTier=6)
    arguments["requestedPorts"] = [port for port in arguments["requestedPorts"] if port["role"] != "energy"]
    result = await native.invoke(client, arguments)
    assert result["status"] == "unavailable" and result["reason"] == "energy_tier_port_required", result
    assert not native.writes(state)


async def test_ordinary_energy_port_is_rejected_after_placement_without_opening_or_mutating_its_gui(debug_energy):
    client, state = debug_energy
    state["energy_meta_class"] = "gregtech.api.metatileentity.implementations.MTEHatchEnergy"
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "energy_tier_port_unsupported", result
    assert result["energyConfiguration"]["placementConfirmed"] and not result["energyConfiguration"]["serverConfirmed"]
    assert not state["energy_requests"] and state["rechecks"] == 0


@pytest.mark.parametrize("fault", ["missing_label", "duplicate_label", "duplicate_field", "unknown_duplicate_field", "wrong_parent", "unsupported_api", "no_set_text"])
async def test_energy_gui_missing_or_ambiguous_public_binding_fails_without_setting_or_formation(debug_energy, fault):
    client, state = debug_energy
    widgets = state["energy_widgets"]
    field = next(widget for widget in widgets if widget["widgetId"] == "energy:tier")
    label = next(widget for widget in widgets if widget["widgetId"] == "energy:tier_label")
    if fault == "missing_label":
        widgets.remove(label)
    elif fault == "duplicate_label":
        widgets.append({**copy.deepcopy(label), "widgetId": "energy:extra_label"})
    elif fault in ("duplicate_field", "unknown_duplicate_field"):
        widgets.append({**copy.deepcopy(field), "widgetId": "energy:extra_field"})
        if fault == "unknown_duplicate_field":
            widgets[-1]["class"] = "example.UnknownTextField"
    elif fault == "wrong_parent":
        field["parentId"] = "energy:amperage_row"
    elif fault == "unsupported_api":
        field["class"] = "example.UnknownTextField"
    else:
        field["supportedActions"] = ["focus"]
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "debug_energy_tier_field_ambiguous", result
    assert result["energyConfiguration"]["placementConfirmed"] and not state["energy_requests"] and not state["rechecks"]


@pytest.mark.parametrize("flag", ["hasMore", "partial", "scanTruncated", "byteLimited"])
async def test_incomplete_energy_gui_cannot_prove_a_unique_public_field(debug_energy, flag):
    client, state = debug_energy
    state["energy_gui_fault"] = flag
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "debug_energy_gui_coverage_unavailable", result
    assert not state["energy_requests"] and not state["rechecks"]


async def test_missing_native_energy_gui_reports_already_placed_port_without_replaying_open(debug_energy):
    client, state = debug_energy
    state["energy_no_gui"] = True
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "debug_energy_gui_unavailable", result
    assert result["energyConfiguration"]["placementConfirmed"] and not state["energy_requests"] and not state["rechecks"]
    target = result["energyConfiguration"]["position"]
    openings = [call for call in state["calls"] if call["operation"] == "player.interact"
        and call["args"].get("sneaking") is False and all(call["args"].get(axis) == target[axis] for axis in ("x", "y", "z"))]
    assert len(openings) == 1


async def test_cursor_change_after_energy_gui_observation_blocks_native_tier_commit(debug_energy, monkeypatch):
    client, state = debug_energy
    actual_call = client.call

    async def cursor_changed(operation, arguments, **options):
        result = await actual_call(operation, arguments, **options)
        if operation == "gui.widgets" and arguments.get("screenId") == "screen:energy":
            state["cursor"] = native.stack({"registryName": "minecraft:stone", "metadata": 0})
        return result

    monkeypatch.setattr(client, "call", cursor_changed)
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "ASSERTION_FAILED", result
    assert not state["energy_requests"] and not state["rechecks"]
    assert state["cursor"] is not None and not result["energyConfiguration"]["serverConfirmed"]


async def test_optimistic_gui_tier_does_not_replace_real_server_voltage_confirmation(debug_energy):
    client, state = debug_energy
    state["energy_stale_server"] = True
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] in ("WAIT_TIMEOUT", "WORKFLOW_TIMEOUT"), result
    assert state["energy_gui_tier"] == 6 and state["energy_tier"] == 15
    assert len(state["energy_requests"]) == 1 and not state["rechecks"] and not result["formationVerified"]
    assert not result["energyConfiguration"]["serverConfirmed"]


async def test_unknown_energy_commit_is_not_replayed_or_claimed_server_confirmed(debug_energy):
    client, state = debug_energy
    state["energy_commit_disconnect"] = True
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed", result
    assert result["lastActionReceipt"]["outcomeUnknown"] and len(state["energy_requests"]) == 1
    assert not result["energyConfiguration"]["serverConfirmed"] and not state["rechecks"]


async def test_registered_debug_energy_tier_drift_after_configuration_fails_acceptance(debug_energy):
    client, state = debug_energy
    state["energy_tier_drift"] = True
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "registered_energy_tier_changed", result
    assert result["energyConfiguration"]["serverConfirmed"] and not result["formationVerified"]
    assert state["rechecks"] == 1 and len(state["energy_requests"]) == 1


@pytest.mark.parametrize("empty_prefix", [False, True])
@pytest.mark.parametrize("limit_flag", ["partial", "byteLimited"])
async def test_energy_gui_before_and_after_commit_drains_only_raw_suffix_once(debug_energy, monkeypatch, empty_prefix, limit_flag):
    client, state = debug_energy
    original = client.call

    async def paged_controls(operation, arguments, **options):
        data = await original(operation, arguments, **options)
        if operation == "gui.widgets" and arguments.get("screenId") == "screen:energy":
            rows = data["widgets"]
            first = [row for row in rows if row["widgetId"] in ("energy:refill", "energy:amperage")]
            offset = arguments["offset"]
            assert offset in (0, 56)
            selected = ([] if empty_prefix else first) if offset == 0 else (rows if empty_prefix else [row for row in rows if row not in first])
            following = 56 if offset == 0 else 70
            data.update(widgets=selected, offset=offset, nextOffset=following, visited=70,
                rawScanned=following - offset, actionableOnly=True, hasMore=offset == 0,
                partial=offset == 0 and limit_flag == "partial", byteLimited=offset == 0 and limit_flag == "byteLimited")
        return data

    monkeypatch.setattr(client, "call", paged_controls)
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "completed" and result["formationVerified"], result
    assert len(state["energy_requests"]) == 1 and state["energy_requests"][0]["widgetId"] == "energy:tier"
    reads = [call["args"] for call in state["calls"] if call["operation"] == "gui.widgets"
        and call["args"].get("screenId") == "screen:energy"]
    assert [read["offset"] for read in reads] == [0, 56, 0, 56]
    assert all(read["actionableOnly"] is True and read["limit"] == 128 for read in reads)
    assert all(proof["pages"] == 2 and proof["rawVisited"] == 70
        for proof in result["energyConfiguration"]["widgetPageCoverage"].values())
    target = result["energyConfiguration"]["position"]
    openings = [call for call in state["calls"] if call["operation"] == "player.interact"
        and call["args"].get("sneaking") is False and all(call["args"].get(axis) == target[axis] for axis in ("x", "y", "z"))]
    assert len(openings) == state["hint_count"] == state["construct_count"] == state["rechecks"] == 1


async def test_postcommit_energy_widget_screen_drift_never_replays_setting_or_open(debug_energy, monkeypatch):
    client, state = debug_energy
    original = client.call

    async def changed_after_commit(operation, arguments, **options):
        data = await original(operation, arguments, **options)
        if operation == "gui.widgets" and arguments.get("screenId") == "screen:energy" and state["energy_requests"]:
            data["screenId"] = "screen:changed"
        return data

    monkeypatch.setattr(client, "call", changed_after_commit)
    result = await native.invoke(client, native.declaration(energyTier=6))
    assert result["status"] == "failed" and result["reason"] == "debug_energy_gui_coverage_unavailable", result
    assert len(state["energy_requests"]) == 1 and not state["rechecks"] and not result["formationVerified"]
    assert state["energy_tier"] == 6 and state["energy_voltage"] == 32768
    target = result["energyConfiguration"]["position"]
    assert sum(call["operation"] == "player.interact" and call["args"].get("sneaking") is False
        and all(call["args"].get(axis) == target[axis] for axis in ("x", "y", "z")) for call in state["calls"]) == 1


@pytest.mark.parametrize("tier", [-1, 16, True, "6", None])
async def test_invalid_energy_tier_declaration_is_rejected_before_any_native_calls(native_construction, tier):
    client, state = native_construction
    with pytest.raises(BridgeError) as caught:
        await native.invoke(client, native.declaration(energyTier=tier))
    assert caught.value.code == "INVALID_ARGUMENTS" and not state["calls"]
    assert "default" not in BLUEPRINT_BUILD_SCHEMA["properties"]["constructionOrder"]
    assert "actualSurvival" in BLUEPRINT_BUILD_SCHEMA["properties"]["constructionOrder"]["description"]
