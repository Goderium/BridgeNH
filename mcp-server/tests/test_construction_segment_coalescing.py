"""Fresh segment boundaries remove duplicate guards without sharing live truth."""

import copy
import json

import anyio
import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeError, Configuration, CONTEXT_ACTIONS, CONTEXT_READS
from minecraft_codex_bridge.construction_tasks import _ConstructionTask, run_blueprint_build
from minecraft_codex_bridge.workflows import _guard
from test_construction_context_performance import operations, task_from
from test_guarded_rpc import guarded_bridge, action, read
from test_construction_tasks import native_construction, declaration, CONTROLLER_ITEM, stack, BACKGROUND


@pytest.fixture
def context_construction(native_construction, monkeypatch):
    client, state = native_construction
    original = client.http._transport.handle_async_request
    def snapshot():
        position = state["position"]
        return {"sessionId": "session", "profile": "developer", "worldLoaded": True,
            "world": {"worldName": "new-world", "worldFolder": "new-world", "dimension": 0},
            "background": BACKGROUND.copy(), "screenOpen": state["gui"] is not None,
            "player": {"name": "TestPlayer", "health": state.get("health", 20), **position, "feetY": position["y"], "eyeY": position["y"] + 1.62,
                "flying": state["flying"], "selectedHotbarSlot": state["selected"],
                "serverFlight": {"available": True, "allowFlying": True, "flying": state["flying"], "dimension": 0}}}
    async def respond(request):
        body = json.loads(request.content)
        context, operation = body.get("includeContext"), body.get("operation")
        before = snapshot() if context is not None and operation in CONTEXT_ACTIONS else None
        if before is not None:
            _guard(before, context["expectedSessionId"], context["expectedProfile"], context["expectedWorld"], context["requireBackground"])
        response = await original(request)
        envelope = json.loads(await response.aread())
        if envelope.get("ok") is True:
            if operation == "bridge.health":
                envelope["data"]["contextRpc"] = {"version": 1, "reads": sorted(CONTEXT_READS), "actions": sorted(CONTEXT_ACTIONS)}
            if context is not None:
                after = snapshot() if before is None else before
                _guard(after, context["expectedSessionId"], context["expectedProfile"], context["expectedWorld"], context["requireBackground"])
                envelope["context"] = {"version": 1, "operation": operation,
                    "phase": "after" if before is None else "before", "snapshot": after}
        return httpx.Response(response.status_code, json=envelope)
    monkeypatch.setattr(client.http, "_transport", httpx.MockTransport(respond))
    return client, state


async def test_full_context_construction_fuses_live_material_reads_and_keeps_final_snapshot(context_construction):
    client, state = context_construction
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "completed" and result["formationVerified"]
    assert state["construct_count"] == 1 and len(state["placements"]) == 6
    for index, call in enumerate(state["calls"]):
        if call["operation"] == "player.place_block":
            assert operations(state)[index + 1:index + 3] == ["world.inspect", "inventory.inspect"]
            assert state["calls"][index + 2]["includeContext"]["requireBackground"] is True
        elif call["operation"] == "player.interact" and call["args"].get("sneaking") is True:
            assert operations(state)[index + 1] == "inventory.inspect"
    assert operations(state)[-1] == "inventory.inspect"
    assert state["calls"][-1]["includeContext"]["requireBackground"] is True
    assert operations(state).count("bridge.health") == 1


@pytest.mark.parametrize("kind", ["read", "wait"])
async def test_cached_segment_finishes_on_its_validated_last_after_read(guarded_bridge, kind):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    tail = read("after")
    if kind == "wait":
        tail.update(kind="wait", condition={"path": "selectedHotbarSlot", "operator": "equals", "value": 1})
    await task.workflow("confirmed", [action("write"), tail,
        {"id": "selection", "kind": "assert", "source": "after",
         "condition": {"path": "selectedHotbarSlot", "operator": "equals", "value": 1}}])
    assert operations(state) == ["player.hotbar", "inventory.inspect"]
    assert state["calls"][-1]["includeContext"]["requireBackground"] is True
    assert state["actions"] == 2


@pytest.mark.parametrize("fault,code", [("session", "SESSION_CHANGED"), ("profile", "PROFILE_CHANGED"),
    ("world", "WORLD_CHANGED"), ("background", "TAKEOVER_LOST")])
async def test_last_after_read_drift_fails_before_another_write(guarded_bridge, fault, code):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    def drift(value):
        if fault == "background":
            value["background"]["enabled"] = False
        else:
            value[fault] = "changed"
    state["after_read"] = drift
    with pytest.raises(BridgeError) as error:
        await task.workflow("confirmed", [action("write"), read("after"), action("blocked")])
    assert error.value.code == code and state["actions"] == 2
    assert operations(state) == ["player.hotbar", "inventory.inspect"]
    assert "client.background" not in operations(state)


@pytest.mark.parametrize("fault", ["missing", "wrong_phase", "wrong_operation", "wrong_version", "not_snapshot", "extra"])
async def test_invalid_terminal_after_guard_never_falls_back_or_replays(guarded_bridge, fault):
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    state["after_read"] = lambda value: value.update(bad_context=fault)
    with pytest.raises(BridgeError) as error:
        await task.workflow("confirmed", [action("write"), read("after"), action("blocked")])
    assert error.value.code == "MALFORMED_RESPONSE" and not error.value.details["outcome_unknown"]
    assert state["actions"] == 2 and operations(state) == ["player.hotbar", "inventory.inspect"]


@pytest.mark.parametrize("kind", ["action", "read"])
async def test_later_noncontext_rpc_invalidates_an_earlier_after_boundary(guarded_bridge, kind):
    if kind == "action":
        guarded_bridge[1]["supported"]["actions"].remove("chat.send")
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    tail = {"id": "ordinary", "kind": kind,
        "tool": "mcb_chat" if kind == "action" else "mcb_diagnostics",
        "args": {"message": "test"} if kind == "action" else {}}
    await task.workflow("ordinary", [read(), tail])
    assert operations(state)[-1] == "client.snapshot"
    assert operations(state).count("client.snapshot") == (2 if kind == "action" else 1)
    assert "includeContext" not in next(call for call in state["calls"]
        if call["operation"] == ("chat.send" if kind == "action" else "client.diagnostics"))


@pytest.mark.parametrize("change", ["url", "token", "configuration"])
async def test_runtime_change_after_terminal_read_is_rejected_at_local_boundary(guarded_bridge, change):
    adapter, state = guarded_bridge
    task, state, _ = await task_from(guarded_bridge)
    await task.workflow("establish", [action()])
    state["calls"].clear()
    def rotate(_):
        if change == "configuration":
            task.tracked_client.configuration = Configuration(dict(adapter.client.configuration.environ))
        else:
            adapter.client.configuration.environ["MCB_URL" if change == "url" else "MCB_TOKEN"] = \
                "http://127.0.0.1:25672" if change == "url" else "rotated"
    state["after_read"] = rotate
    with pytest.raises(BridgeError) as error:
        await task.workflow("confirmed", [read("after")])
    assert error.value.code == "SESSION_CHANGED"
    assert operations(state) == ["inventory.inspect"] and state["actions"] == 1


@pytest.mark.parametrize("restore", [False, True])
@pytest.mark.parametrize("fault,reason", [("session", "session_changed"), ("profile", "local_developer_context_required"),
    ("world", "local_developer_context_required"), ("dimension", "local_developer_context_required"),
    ("background", "TAKEOVER_LOST"), ("token", "SESSION_CHANGED")])
async def test_whole_task_final_snapshot_catches_drift_after_last_inventory(native_construction, monkeypatch, restore, fault, reason):
    client, state = native_construction
    method = "restore" if restore else "verify_retained_inventory"
    original_finish = getattr(_ConstructionTask, method)
    final_pending = False
    async def finish(task):
        nonlocal final_pending
        await original_finish(task)
        final_pending = True
        if fault == "token":
            client.configuration.environ["MCB_TOKEN"] = "rotated"
    monkeypatch.setattr(_ConstructionTask, method, finish)
    original_call = client.call
    async def drift(operation, args, **options):
        value = await original_call(operation, args, **options)
        if final_pending and operation == "client.snapshot":
            if fault == "session":
                value["sessionId"] = "changed"
            elif fault == "profile":
                value["profile"] = "player"
            elif fault == "world":
                value["world"] = {"worldName": "changed", "worldFolder": "changed", "dimension": 0}
            elif fault == "dimension":
                value["world"]["dimension"] = 1
            elif fault == "background":
                value["background"] = {"enabled": False, "helperInstalled": True, "pauseOnLostFocus": False}
        return value
    monkeypatch.setattr(client, "call", drift)
    result = await run_blueprint_build(client, declaration(restoreInventory=restore), anyio.Lock())
    assert result["status"] == "failed" and result["reason"] == reason
    assert result["failedPhase"] == "final_context_acceptance"
    assert state["construct_count"] == 1 and len(state["placements"]) == 6
    assert operations(state)[-1] == ("inventory.inspect" if fault == "token" else "client.snapshot")


async def test_runtime_rotation_during_whole_task_final_snapshot_is_rejected(native_construction, monkeypatch):
    client, state = native_construction
    original_finish = _ConstructionTask.verify_retained_inventory
    final_pending = False
    async def finish(task):
        nonlocal final_pending
        await original_finish(task)
        final_pending = True
    monkeypatch.setattr(_ConstructionTask, "verify_retained_inventory", finish)
    original_call = client.call
    async def rotate(operation, args, **options):
        value = await original_call(operation, args, **options)
        if final_pending and operation == "client.snapshot":
            client.configuration.environ["MCB_TOKEN"] = "rotated"
        return value
    monkeypatch.setattr(client, "call", rotate)
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "failed" and result["reason"] == "SESSION_CHANGED"
    assert result["failedPhase"] == "final_context_acceptance" and state["construct_count"] == 1


@pytest.mark.parametrize("fault", ["cursor", "count", "identity"])
async def test_fused_material_observation_preserves_exact_failure_without_replay(native_construction, fault):
    client, state = native_construction
    def changed(data):
        if not any(row["item"]["metadata"] == CONTROLLER_ITEM["metadata"] for row in state["placements"]):
            return
        source = next(slot for slot in data["slots"] if slot["index"] == 1)["item"]
        if fault == "cursor":
            data["cursorItem"] = stack({"registryName": "minecraft:stone", "metadata": 0})
        elif fault == "count":
            source["count"] = 2
        else:
            source["registryName"] = "minecraft:stone"
    state["inventory_observation_hook"] = changed
    result = await run_blueprint_build(client, declaration(), anyio.Lock())
    assert result["status"] == "failed" and result["reason"] == "creative_material_changed"
    assert len(state["placements"]) == 2 and state["construct_count"] == state["hint_count"] == 0


async def test_material_projection_retains_complete_empty_slots_without_extra_rpc(guarded_bridge):
    task, state, _ = await task_from(guarded_bridge)
    task.materials[1] = {"item": CONTROLLER_ITEM.copy(), "count": 1}
    values = {"slots": [{"index": 0, "item": None}, {"index": 1, "item": stack(CONTROLLER_ITEM)}],
        "totalSlots": 2, "truncated": False, "cursorItem": None}
    report = {"steps": [{"id": "material_after", "observation": {"values": copy.deepcopy(values)}}]}
    before = len(state["calls"])
    await task.verify_material(1, report=report)
    assert len(state["calls"]) == before
    assert task.material_after_read()["omitEmptySlots"] is False
    report["steps"][0]["observation"]["values"]["slots"].pop(0)
    with pytest.raises(BridgeError) as error:
        await task.verify_material(1, report=report)
    assert error.value.details["reason"] == "inventory_coverage_incomplete"
