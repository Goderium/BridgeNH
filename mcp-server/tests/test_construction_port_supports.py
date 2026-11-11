"""Disconnected creative ports reuse explicit temporary support and native placement."""
import copy

import pytest

import test_construction_tasks as native
from test_construction_declared_goals import observe_hook
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.construction_tasks import _ConstructionTask

native_construction = native.native_construction


def creative_evidence(operation, arguments, data):
    if operation == "client.snapshot":
        data["player"]["creative"] = True
        data["player"]["serverFlight"]["creative"] = True


@pytest.mark.parametrize("mode", ["command", "command_column"])
async def test_disconnected_ports_use_owned_support_before_one_native_placement(native_construction, monkeypatch, mode):
    client, state = native_construction
    state["hint_shift"] = (10, 2, 0)
    before = copy.deepcopy(state["items"])
    observe_hook(monkeypatch, client, creative_evidence)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", supportMode=mode))
    assert result["status"] == "completed", result
    assert result["formationVerified"] and result["portsPreservedAfterNativeShell"]
    supports = [s for s in result["cleanup"]["supports"] if s.get("purpose") == "ports_first_attachment"]
    assert supports and all(s["placementConfirmed"] and s["cleanupConfirmed"] for s in supports)
    assert all(s["nativePath"] == "developer_command" for s in supports)
    assert state["construct_count"] == 1 and state["items"] == before and state["cursor"] is None
    for port in result["selectedPorts"]:
        calls = [c for c in state["calls"] if c["operation"] == "player.place_block" and native.key(c["args"]) == native.key(port["position"])]
        assert len(calls) == 1
    shell = next(i for i, c in enumerate(state["calls"]) if c["operation"] == "player.interact"
                 and c["args"].get("sneaking") and native.key(c["args"]) == native.key(state["controller"]))
    for support in supports:
        removals = [i for i, c in enumerate(state["calls"]) if c["operation"] == "chat.send"
                    and c["args"]["message"] == "/setblock {} {} {} minecraft:air 0 replace".format(*native.key(support["position"]))]
        assert len(removals) == 1 and removals[0] < shell


@pytest.mark.parametrize("mode", ["player", "command"])
async def test_missing_creative_authority_never_introduces_port_command_support(native_construction, mode):
    client, state = native_construction
    state["hint_shift"] = (10, 2, 0)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", supportMode=mode))
    assert result["status"] == "failed"
    assert state["construct_count"] == 0
    assert not any(s.get("purpose") == "ports_first_attachment" and s.get("placementAttempted") for s in result["cleanup"]["supports"])


async def test_changed_port_support_is_retained_without_shell_or_cleanup_replay(native_construction, monkeypatch):
    client, state = native_construction
    state["hint_shift"] = (10, 2, 0)
    def evidence(operation, arguments, data):
        creative_evidence(operation, arguments, data)
        if operation == "player.place_block" and arguments["hotbarSlot"] in native.PORT_SLOTS.values():
            below = (arguments["x"], arguments["y"] - 1, arguments["z"])
            if state["blocks"].get(below, {}).get("registryName") == "minecraft:stone":
                state["blocks"][below] = {"registryName": "minecraft:diamond_block", "metadata": 0}
    observe_hook(monkeypatch, client, evidence)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", supportMode="command"))
    assert result["reason"] == "owned_block_changed", result
    assert state["construct_count"] == 0
    assert any(b["registryName"] == "minecraft:diamond_block" for b in state["blocks"].values())
    assert len([c for c in state["calls"] if c["operation"] == "player.place_block"
                and c["args"]["hotbarSlot"] in native.PORT_SLOTS.values()]) == 1


@pytest.mark.parametrize("fault", ["outside_reserved", "reserved_port", "below_floor"])
async def test_port_support_stays_inside_proved_layout_and_outside_other_ports(monkeypatch, fault):
    task = object.__new__(_ConstructionTask)
    task.actual_survival = False
    task.support_mode = "command"
    task.dimension = 0
    task.floor = 3
    task.supports = []
    port = {"id": "energy.0", "position": {"x": 85, "y": 8, "z": 40}}
    task.selected_ports = [port]
    task.placement_plan = None
    task.args = {"controller": {"x": 84, "y": 7, "z": 40}}
    if fault == "outside_reserved":
        task.placement_plan = {"reservedBounds": {"minX": 84, "maxX": 86, "minY": 8, "maxY": 9, "minZ": 40, "maxZ": 55}}
    elif fault == "reserved_port":
        task.selected_ports.append({"position": {"x": 85, "y": 7, "z": 40}})
    else:
        port["position"]["y"] = 4
    async def no_attachments(*args, **kwargs): return []
    async def snapshot(): return {"player": {"creative": True, "serverFlight": {"available": True, "creative": True, "dimension": 0}}}
    async def no_writes(*args): raise AssertionError("Invalid support must refuse before commands")
    monkeypatch.setattr(task, "attachments", no_attachments)
    monkeypatch.setattr(task, "snapshot", snapshot)
    monkeypatch.setattr(task, "command_supports", no_writes)
    with pytest.raises(BridgeError):
        await task.prepare_port_attachment(port)


async def test_uncertain_support_command_is_not_repeated_removed_or_followed_by_port(native_construction, monkeypatch):
    client, state = native_construction
    state["hint_shift"] = (10, 2, 0)
    original = client.call
    submitted = []
    async def wrapped(operation, arguments, **kwargs):
        result = await original(operation, arguments, **kwargs)
        creative_evidence(operation, arguments, result)
        if operation == "chat.send" and arguments["message"].startswith("/setblock"):
            words = arguments["message"].split()
            if int(words[1]) > state["controller"]["x"] + 3 and words[-1] == "keep":
                submitted.append(arguments["message"])
                raise BridgeError("timeout", "Fixture: native support outcome unknown", outcome_unknown=True)
        return result
    monkeypatch.setattr(client, "call", wrapped)
    result = await native.invoke(client, native.declaration(constructionOrder="ports_first", supportMode="command"))
    assert result["status"] == "failed" and len(submitted) == 1
    supports = [s for s in result["cleanup"]["supports"] if s.get("purpose") == "ports_first_attachment"]
    assert len(supports) == 1 and not supports[0].get("placementConfirmed") and not supports[0].get("cleanupAttempted")
    assert state["blocks"][native.key(supports[0]["position"])]["registryName"] == "minecraft:stone"
    assert state["construct_count"] == 0
    assert not any(c["operation"] == "player.place_block" and c["args"]["hotbarSlot"] in native.PORT_SLOTS.values()
                   for c in state["calls"])


@pytest.mark.parametrize("survival,mode", [(True, "command"), (True, "player"), (False, "player")])
async def test_real_material_mode_never_uses_creative_port_support(monkeypatch, survival, mode):
    task = object.__new__(_ConstructionTask)
    task.actual_survival = survival
    task.support_mode = mode
    async def forbidden(*args, **kwargs): raise AssertionError("Real-material path must remain independent")
    monkeypatch.setattr(task, "attachments", forbidden)
    monkeypatch.setattr(task, "command_supports", forbidden)
    assert await task.prepare_port_attachment({}) is False
