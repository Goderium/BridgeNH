"""Generic composition keeps native inventory effects and travel estimates separate."""
import copy
import itertools

import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import Adapter, _validate
from minecraft_codex_bridge.visit_planning import order_visits
from minecraft_codex_bridge.inventory_moves import run_inventory_moves
from minecraft_codex_bridge.server import invoke_control
from test_recipe_tasks import native_bridge, stack
from test_tagged_recipe_context import tagged_transport
from test_workflows import bridge as workflow_bridge, PROTECTED

COMMON = {"expectedSessionId": "session", "expectedWorld": "test-world", "dimension": 180}


async def setup(client, state, monkeypatch):
    await client.invoke("inventory.inspect", {})
    original = client.call
    async def call(op, args, **kw):
        result = await original(op, args, **kw)
        if op == "bridge.health":
            result["readiness"] = {"localIntegratedServer": True}
        return result
    monkeypatch.setattr(client, "call", call)


def moves(*pairs):
    return {**COMMON, "moves": [{"sourceIndex": a, "destinationIndex": b,
        "expectedItem": stack({"registryName": "minecraft:stone", "metadata": 0}, 64)} for a, b in pairs]}


async def test_native_batch_one_opening_and_return_moves(native_bridge, monkeypatch):
    client, state = native_bridge
    state["full_hotbar"] = True
    await setup(client, state, monkeypatch)
    result = await Adapter(client).call_tool("mcb_inventory_moves", moves((0, 20), (1, 21)))
    assert not result.isError, result.structuredContent
    report = result.structuredContent["data"]
    assert report["retentionConfirmed"] and report["cursorEmpty"]
    assert len(report["moves"]) == 2 and all(r["movementConfirmed"] and r["retentionConfirmed"] for r in report["moves"])
    assert sum(c["operation"] == "gui.open_inventory" for c in state["calls"]) == 1
    assert state["player_items"][0] is None and state["player_items"][1] is None and state["open"] is None
    result = await Adapter(client).call_tool("mcb_inventory_moves", moves((20, 0), (21, 1)))
    assert not result.isError, result.structuredContent
    assert state["player_items"][20] is None and state["player_items"][21] is None
    assert state["player_items"][0] == moves((0, 20))["moves"][0]["expectedItem"]
    assert not any(c["operation"] in {"inventory.supply", "chat.send", "dev.teleport", "player.navigate"} for c in state["calls"])


@pytest.mark.parametrize("fault", ["conflict", "destination", "late_source", "tagged", "cursor"])
async def test_entire_batch_preflights_before_first_pickup(native_bridge, monkeypatch, fault):
    client, state = native_bridge
    state["full_hotbar"] = True
    await setup(client, state, monkeypatch)
    args = moves((0, 20), (1, 21))
    if fault == "conflict": args["moves"][1]["sourceIndex"] = 20
    elif fault == "destination": state["player_items"][21] = stack({"registryName": "minecraft:dirt", "metadata": 0}, 1)
    elif fault == "late_source": args["moves"][1]["expectedItem"]["count"] = 63
    elif fault == "tagged": args["moves"][0]["expectedItem"]["hasTag"] = True
    else: state["cursor"] = stack({"registryName": "minecraft:dirt", "metadata": 0}, 1)
    result = await Adapter(client).call_tool("mcb_inventory_moves", args)
    assert result.isError
    assert not any(c["operation"] in {"gui.open_inventory", "gui.slot_click", "gui.widget_action"} for c in state["calls"])


async def test_uncertain_pickup_is_preserved_once(native_bridge, monkeypatch):
    client, state = native_bridge
    state["full_hotbar"], state["creative_error"] = True, "disconnect"
    await setup(client, state, monkeypatch)
    result = await Adapter(client).call_tool("mcb_inventory_moves", moves((0, 20), (1, 21)))
    assert result.isError
    report = result.structuredContent["data"]
    assert report["failedIndex"] == 0 and report["replayAllowed"] is False
    first, = report["moves"]
    assert first["movementDispatched"] and first["outcomeUnknown"] and not first["movementConfirmed"]
    assert state["cursor"] is not None and state["player_items"][1] is not None
    assert sum(c["operation"] == "gui.widget_action" and c["args"].get("widgetId", "").startswith("creative:slot:") for c in state["calls"]) == 1


async def test_later_unknown_move_keeps_earlier_confirmed_effect(native_bridge, monkeypatch):
    client, state = native_bridge
    state["full_hotbar"] = True
    await setup(client, state, monkeypatch)
    original = client.call
    async def call(op, args, **kwargs):
        if op == "gui.widget_action" and args.get("widgetId") == "creative:slot:1":
            state["creative_error"] = "disconnect"
        return await original(op, args, **kwargs)
    monkeypatch.setattr(client, "call", call)
    result = await Adapter(client).call_tool("mcb_inventory_moves", moves((0, 20), (1, 21)))
    assert result.isError
    report = result.structuredContent["data"]
    assert report["failedIndex"] == 1 and len(report["moves"]) == 2
    assert report["moves"][0]["movementConfirmed"] and report["moves"][1]["outcomeUnknown"]
    assert state["player_items"][20] is not None and state["player_items"][0] is None
    assert state["player_items"][21] is None and state["cursor"] is not None


async def test_inventory_deadline_includes_waiting_for_workflow_lock(native_bridge):
    client, state = native_bridge
    lock = anyio.Lock()
    ready = anyio.Event()
    async def hold():
        async with lock:
            ready.set()
            await anyio.sleep_forever()
    async with anyio.create_task_group() as group:
        group.start_soon(hold)
        await ready.wait()
        with anyio.fail_after(.5):
            result = await run_inventory_moves(client, {**moves((0, 20)), "timeout_ms": 20}, lock, invoke_control)
        group.cancel_scope.cancel()
    assert result["status"] == "failed" and result["error"]["code"] == "INVENTORY_MOVE_TIMEOUT"
    assert not result["moves"] and state["calls"] == []


async def test_tagged_context_stays_at_original_slots_and_full_nbt(native_bridge, monkeypatch):
    client, state = native_bridge
    state["full_hotbar"] = True
    await setup(client, state, monkeypatch)
    tagged_transport(client, state, monkeypatch)
    before = copy.deepcopy(state["player_items"][0])
    result = await Adapter(client).call_tool("mcb_inventory_moves", moves((1, 20), (2, 21)))
    assert not result.isError, result.structuredContent
    assert result.structuredContent["data"]["taggedContextFullNbtVerified"]
    assert state["player_items"][0] == before and state["player_items"][40]["hasTag"]


def point(x, z=0): return {"x": x, "y": 4, "z": z}


def test_generic_visits_group_repeated_material_stops():
    stops = [{"id": str(i), "feet": point(x)} for i, x in enumerate((-10, 10, -10, 10))]
    result = order_visits(stops, point(0), point(0))
    assert result["baselineDistance"] == 80 and result["plannedDistance"] == 40
    assert result["visitCount"] == 2 and result["visits"][0]["taskIds"] == ["0", "2"]


@pytest.mark.parametrize("order", list(itertools.permutations(range(5))))
def test_visit_order_never_breaks_dependencies_or_increases_estimated_distance(order):
    stops = [{"id": str(i), "feet": point((i % 2)*10), "after": [str(i-1)] if i in (2, 4) else []} for i in order]
    result = order_visits(stops, point(3), point(0))
    selected = result["orderedTaskIds"]
    assert sorted(selected) == [str(i) for i in range(5)]
    assert selected.index("1") < selected.index("2") and selected.index("3") < selected.index("4")
    assert result["plannedDistance"] <= result["baselineDistance"]


@pytest.mark.parametrize("stops", [
    [{"id": "a", "feet": point(0)}, {"id": "a", "feet": point(1)}],
    [{"id": "a", "feet": point(0), "after": ["missing"]}],
    [{"id": "a", "feet": point(0), "after": ["a"]}],
    [{"id": "a", "feet": point(0), "after": ["b"]}, {"id": "b", "feet": point(0), "after": ["a"]}]])
async def test_invalid_dependencies_refuse_before_transport(native_bridge, stops):
    client, state = native_bridge
    result = await Adapter(client).call_tool("mcb_plan_visits", {**COMMON, "stops": stops})
    assert result.isError and state["calls"] == []


async def test_visit_start_is_actual_pose_and_only_read(native_bridge):
    client, state = native_bridge
    result = await Adapter(client).call_tool("mcb_plan_visits", {**COMMON,
        "stops": [{"id": "inspection", "feet": point(10)}]})
    assert not result.isError, result.structuredContent
    assert result.structuredContent["data"]["start"] == {"x": 5.5, "y": 19, "z": 20.5}
    assert [c["operation"] for c in state["calls"]] == ["client.snapshot"]


@pytest.mark.parametrize("tool,args", [("mcb_inventory_moves", moves((0, 20))),
    ("mcb_plan_visits", {**COMMON, "stops": [{"id": "a", "feet": point(0)}]})])
def test_schemas_accept_only_finite_bounded_declarations(tool, args):
    _validate(tool, args)
    args["dimension"] = True
    with pytest.raises(BridgeError): _validate(tool, args)


@pytest.mark.parametrize("mode,button,valid", [(2, 3, True), (0, 0, True), (2, 9, False),
    (2, True, False), (False, 0, False), ("single", 0, False)])
async def test_sequence_reuses_destination_schema_for_observed_native_button_and_mode(workflow_bridge, mode, button, valid):
    adapter, state = workflow_bridge
    state["background"] = PROTECTED.copy()
    state["data"]["world.inspect"] = {"observedMode": mode, "observedButton": button}
    result = await adapter.call_tool("mcb_sequence", {"expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
        {"id": "observed", "kind": "read", "tool": "mcb_world_inspect", "args": {"x": 1, "y": 2, "z": 3}},
        {"id": "gesture", "kind": "action", "tool": "mcb_slot_click", "args": {"windowId": 7, "slot": 91},
            "bind": {"mode": {"step": "observed", "path": "observedMode"}, "button": {"step": "observed", "path": "observedButton"}}}]})
    assert bool(result.isError) is not valid, result.structuredContent
    assert sum(c["operation"] == "gui.slot_click" for c in state["calls"]) == int(valid)
