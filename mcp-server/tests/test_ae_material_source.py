import copy
import json

import httpx
import pytest

from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_READ_NAMES
from minecraft_codex_bridge.validation import tool_validator
from test_guarded_rpc import directive, guarded_bridge, plan


SOURCE = {"type": "ae_cells", "mode": "finite", "selector": "tile", "x": 1, "y": 2, "z": 3,
    "side": 2, "expectedGridId": "a0b1c2d3-e4f5-6789-abcd-0123456789ab"}
READ_ARGS = {"expectedSessionId": "session", "expectedWorld": "world", "dimension": 0,
    "x": 1, "y": 2, "z": 3, "side": 2, "selector": "tile"}
BUILD_ARGS = {"expectedSessionId": "session", "expectedWorld": "world", "dimension": 0,
    "x": 1, "y": 2, "z": 3, "side": 2, "blueprintHotbarSlot": 0, "elementBudget": 65536}


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
def test_exact_source_binding_and_full_native_budget_have_real_schemas(mode):
    tool_validator("mcb_ae_material_source").validate({**READ_ARGS, "energyProbeItems": 65536})
    tool_validator("mcb_survival_build").validate({**BUILD_ARGS, "materialSource": {**SOURCE, "mode": mode}})
    assert "mcb_ae_material_source" in SEQUENCE_READ_NAMES
    assert "mcb_ae_material_source" not in TOOL_BY_NAME["mcb_wait_for"].schema["properties"]["tool"]["enum"]


@pytest.mark.parametrize("key", list(SOURCE))
def test_ae_material_source_never_defaults_a_missing_binding(key):
    source = copy.deepcopy(SOURCE)
    source.pop(key)
    assert list(tool_validator("mcb_survival_build").iter_errors({**BUILD_ARGS, "materialSource": source}))


@pytest.mark.parametrize("changes", [{"mode": "mixed"}, {"mode": "unknown"}, {"mode": "player"},
    {"type": "network"}, {"selector": "auto"}, {"side": 6}, {"y": 256},
    {"expectedGridId": "A0B1C2D3-E4F5-6789-ABCD-0123456789AB"}, {"expectedGridId": "guessed"},
    {"expectedGridId": "1-1-1-1-1"}, {"unexpected": True}])
def test_uncertain_or_noncanonical_sources_fail_schema_before_dispatch(changes):
    assert list(tool_validator("mcb_survival_build").iter_errors({**BUILD_ARGS, "materialSource": {**SOURCE, **changes}}))


@pytest.mark.parametrize("name,args", [("mcb_survival_build", {**BUILD_ARGS, "elementBudget": 65537}),
    ("mcb_survival_build", {**BUILD_ARGS, "elementBudget": True}),
    ("mcb_ae_material_source", {**READ_ARGS, "energyProbeItems": 0}),
    ("mcb_ae_material_source", {**READ_ARGS, "energyProbeItems": 65537}),
    ("mcb_ae_material_source", {**READ_ARGS, "energyProbeItems": True}),
    ("mcb_ae_material_source", {**READ_ARGS, "expectedGridId": SOURCE["expectedGridId"]})])
async def test_invalid_source_queries_never_invoke_backend(guarded_bridge, name, args):
    adapter, state = guarded_bridge
    result = await adapter.call_tool(name, args)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert not state["calls"]


@pytest.mark.parametrize("fault", [None, "missing", "wrong_phase", "wrong_operation", "wrong_version", "dead", "background", "profile", "world", "session"])
async def test_direct_source_read_requires_current_after_guard_and_preserves_input_choices(guarded_bridge, fault):
    adapter, state = guarded_bridge
    if fault == "dead": state["health"] = 0
    elif fault == "background": state["background"]["enabled"] = False
    elif fault in ("profile", "world", "session"): state[fault] = "other"
    else: state["bad_context"] = fault
    result = await adapter.call_tool("mcb_ae_material_source", READ_ARGS)
    dispatched = [row for row in state["calls"] if row["operation"] == "ae.material_source"]
    assert len(dispatched) == 1 and dispatched[0]["includeContext"] == directive()
    assert not any(row["operation"] == "client.background" for row in state["calls"])
    assert state["actions"] == 0
    assert result.isError is (fault is not None)


@pytest.mark.parametrize("fault", [None, "world_missing", "context_unadvertised", "profile", "background"])
async def test_sequence_source_read_uses_same_guard_or_refuses_before_later_actions(guarded_bridge, fault):
    adapter, state = guarded_bridge
    arguments = plan({"id": "source", "kind": "read", "tool": "mcb_ae_material_source", "args": READ_ARGS})
    if fault == "world_missing": arguments.pop("expectedWorld")
    elif fault == "context_unadvertised": state["supported"]["reads"].remove("ae.material_source")
    elif fault == "profile": state["profile"] = "player"
    elif fault == "background": state["background"]["enabled"] = False
    result = await adapter.call_tool("mcb_sequence", arguments)
    assert result.isError is (fault is not None)
    dispatched = [row for row in state["calls"] if row["operation"] == "ae.material_source"]
    if fault in ("world_missing", "context_unadvertised", "profile"):
        assert not dispatched
    else:
        assert len(dispatched) == 1 and dispatched[0]["includeContext"] == directive()
    assert not any(row["operation"] == "client.background" for row in state["calls"])
    assert state["actions"] == 0


@pytest.mark.parametrize("bad_source", [False, True])
async def test_sequence_binds_only_the_observed_full_source_and_never_builds_from_invalid_dto(guarded_bridge, monkeypatch, bad_source):
    adapter, state = guarded_bridge
    original = adapter.client.http._transport.handle_async_request
    observed = {**SOURCE, **({"expectedGridId": "guess"} if bad_source else {})}
    async def respond(request):
        response = await original(request)
        body = json.loads(request.content)
        if body.get("operation") != "ae.material_source": return response
        envelope = json.loads(response.content)
        envelope["data"]["materialSource"] = observed
        return httpx.Response(200, json=envelope)
    monkeypatch.setattr(adapter.client.http._transport, "handle_async_request", respond)
    arguments = plan({"id": "source", "kind": "read", "tool": "mcb_ae_material_source", "args": READ_ARGS},
        {"id": "build", "kind": "action", "tool": "mcb_survival_build", "args": BUILD_ARGS,
            "bind": {"materialSource": {"step": "source", "path": "materialSource"}}})
    result = await adapter.call_tool("mcb_sequence", arguments)
    assert result.isError is bad_source
    builds = [row for row in state["calls"] if row["operation"] == "structure.survival_build"]
    assert len(builds) == (0 if bad_source else 1)
    if builds:
        assert builds[0]["args"]["materialSource"] == observed and builds[0]["includeContext"] == directive()


@pytest.mark.parametrize("mode", ["finite", "creative_infinite"])
def test_high_level_blueprint_accepts_only_an_explicit_complete_ae_shell_source(mode):
    from test_construction_tasks import declaration
    args = declaration(actualSurvival=True, survivalBuild={"elementBudget": 65536})
    tool_validator("mcb_blueprint_build").validate(args)
    source = {**SOURCE, "mode": mode}
    args["survivalBuild"]["materialSource"] = source
    tool_validator("mcb_blueprint_build").validate(args)
    for key in source:
        missing = copy.deepcopy(args)
        missing["survivalBuild"]["materialSource"].pop(key)
        assert list(tool_validator("mcb_blueprint_build").iter_errors(missing))
    for change in ({"mode": "mixed"}, {"mode": "unknown"}, {"expectedGridId": "guess"}, {"extra": True}):
        invalid = copy.deepcopy(args)
        invalid["survivalBuild"]["materialSource"].update(change)
        assert list(tool_validator("mcb_blueprint_build").iter_errors(invalid))
    assert list(tool_validator("mcb_blueprint_build").iter_errors({**args, "actualSurvival": False}))
