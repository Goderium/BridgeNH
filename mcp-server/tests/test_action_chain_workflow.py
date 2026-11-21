"""Action-chain boundaries through the real catalog, sequence runner and HTTP transport."""

import json

import httpx
import pytest

from minecraft_codex_bridge.application.adapter import Adapter
from minecraft_codex_bridge.bridge import BridgeClient, Configuration


SESSION, WORLD, DIM = "live-session", "fixture-world", -1
BOUND = {"expectedSessionId": SESSION, "expectedWorld": WORLD}
POS = {"x": 4, "y": 64, "z": -2}
BACKGROUND = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
MANUAL = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
READS = {"world.inspect", "inventory.inspect"}
ACTIONS = {"player.mine", "survival.food", "player.hotbar"}


class Game:
    def __init__(self):
        self.session, self.world, self.dimension = SESSION, WORLD, DIM
        self.background, self.native = MANUAL, {"status": "idle"}
        self.block, self.food, self.slot = "minecraft:stone", 12, 0
        self.calls = []
        self.context_version = 1
        self.idle_version = 1
        self.acquire_before = None
        self.conflicting_receipt = None

    def snapshot(self):
        return {"sessionId": self.session, "profile": "player", "worldLoaded": True, "background": self.background,
                "world": {"worldFolder": self.world, "dimension": self.dimension},
                "lifecycleTarget": {"expectedWorld": self.world}, "inputTask": self.native,
                "player": {"food": self.food, "selectedHotbarSlot": self.slot}}

    def rpc(self, request):
        body = json.loads(request.content)
        self.calls.append(body)
        op, args = body["operation"], body["args"]
        context = body.get("includeContext")
        if context is not None:
            expected = {"version": 1, "expectedProfile": "player", **BOUND,
                        "expectedDimension": DIM, "requireBackground": True}
            if op in ACTIONS:
                expected["requireIdleInput"] = True
            assert context == expected
            if self.acquire_before == op:
                # The row was idle at the earlier boundary; the new task owns input on the native thread now.
                self.native = {"status": "active", "taskId": "another-owner"}
            if context.get("requireIdleInput") and self.native["status"] != "idle":
                return httpx.Response(200, json={"ok": False, "requestId": body["requestId"], "error": {
                    "code": "INPUT_TASK_ACTIVE", "message": "Native input is already owned.",
                    "original_action_executed": False, "outcome_unknown": False}})
        before = self.snapshot()
        if op == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": self.session, "profile": "player", "background": self.background,
                    "operationPolicy": {"available": True, "selected": {"mode": "survival", "readOnly": False}},
                    "capabilities": sorted(READS | ACTIONS | {"client.background", "bridge.health", "client.snapshot"}),
                    "contextRpc": {"version": 1, "dimensionGuardVersion": self.context_version,
                                   "idleInputGuardVersion": self.idle_version,
                                   "reads": sorted(READS), "actions": sorted(ACTIONS)}}
        elif op == "client.snapshot":
            data = self.snapshot()
        elif op == "client.background":
            assert args == {"enabled": True}
            self.background = BACKGROUND
            data = self.background
        elif op == "player.mine":
            assert self.block == args["registryName"] and self.slot == args["hotbarSlot"]
            self.block = "minecraft:air"
            self.native = {"status": "active", "taskId": "held-native-task"}
            data = {"status": "started"}
        elif op == "world.inspect":
            assert args == POS
            data = {"loaded": True, "registryName": self.block, "dimension": self.dimension, **POS}
        elif op == "survival.food":
            self.slot = args["slot"]
            self.food += 4
            data = {"status": "eaten", "complete": True, "useSubmitted": True, "foodGainVerified": True,
                    "serverUseFinished": True, "foodBefore": before["player"]["food"], "foodAfter": self.food}
        elif op == "player.hotbar":
            self.slot = args["slot"]
            data = {"selectedHotbarSlot": self.slot}
        elif op == "inventory.inspect":
            data = {"selectedHotbarSlot": self.slot}
        else:
            pytest.fail(f"Unexpected native request {op}")
        envelope = {"ok": True, "requestId": body["requestId"], "data": data}
        if context is not None:
            if op in ACTIONS and self.conflicting_receipt is not None:
                before["inputTask"] = self.conflicting_receipt
            envelope["context"] = {"version": 1, "operation": op, "phase": "before" if op in ACTIONS else "after",
                                   "snapshot": before if op in ACTIONS else self.snapshot()}
        return httpx.Response(200, json=envelope)


@pytest.fixture
async def chain():
    game = Game()
    config = Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "fixture-chain-token"})
    client = BridgeClient(config, transport=httpx.MockTransport(game.rpc))
    adapter = Adapter(client)
    try:
        yield adapter, game
    finally:
        await client.aclose()


def segment(name, tool, args, observation, path, expected, read_args=None):
    return {"id": name, "steps": [{"id": "act", "kind": "action", "tool": tool, "args": args},
                                  {"id": "readback", "kind": "read", "tool": observation, "args": read_args or {}},
                                  {"id": "verify", "kind": "assert", "source": "readback", "accepts": ["act"],
                                   "condition": {"path": path, "operator": "equals", "value": expected}}]}


MINE = segment("mine", "mcb_mine", {**BOUND, "dimension": DIM, **POS, "registryName": "minecraft:stone",
                                    "metadata": 0, "hotbarSlot": 0}, "mcb_world_inspect", "registryName", "minecraft:air", POS)
EAT = segment("eat", "mcb_food", {"action": "eat", "registryName": "minecraft:apple", "metadata": 0, "slot": 2},
              "mcb_snapshot", "player.food", 16)
TOOL = segment("pickaxe", "mcb_hotbar", {"slot": 0}, "mcb_inventory", "selectedHotbarSlot", 0)


async def call(adapter, action, current=None, **args):
    reference = {} if current is None else {"chainId": current["chainId"], "expectedRevision": current["revision"]}
    return await adapter._call_tool("mcb_action_chain", {"action": action, **reference, **args})


def ok(result):
    assert not result.isError, result.structuredContent
    return result.structuredContent["data"]


def verified(result, expected_segments):
    data = ok(result)
    assert data["completed"] == expected_segments
    report = data["last"]["report"]
    assert data["last"]["segment"] == expected_segments[-1]
    assert report["dispatchedActions"] == report["acknowledgedActions"] == 1
    assert report["checkpoint"]["effectAcceptanceComplete"] is True
    assert report["checkpoint"]["actions"] == [{"id": "act", "state": "condition_verified", "acceptedBy": ["verify"]}]
    return data


async def test_real_segments_wait_for_owner_accept_insert_and_resume_after_readback(chain):
    adapter, game = chain
    current = ok(await call(adapter, "open", **BOUND, dimension=DIM, segments=[MINE, TOOL]))
    assert not game.calls
    current = verified(await call(adapter, "advance", current), ["mine"])
    assert current["pending"] == ["pickaxe"]
    mutations = lambda: [call["operation"] for call in game.calls if call["operation"] in ACTIONS]
    assert mutations() == ["player.mine"]

    index = len(game.calls)
    current = ok(await call(adapter, "advance", current))
    assert current["status"] == "ready" and current["waitingForNativeTask"] == "held-native-task"
    assert current["dispatched"] is False and current["pending"] == ["pickaxe"]
    assert [rpc["operation"] for rpc in game.calls[index:]] == ["bridge.health", "client.snapshot"]
    index = len(game.calls)
    current = ok(await call(adapter, "insert", current, segments=[EAT]))
    assert len(game.calls) == index and current["pending"] == ["eat", "pickaxe"]
    game.native = {"status": "idle"}

    current = verified(await call(adapter, "advance", current), ["mine", "eat"])
    assert current["pending"] == ["pickaxe"]
    current = verified(await call(adapter, "advance", current), ["mine", "eat", "pickaxe"])
    assert current["status"] == "completed" and current["pending"] == []
    assert mutations() == ["player.mine", "survival.food", "player.hotbar"]
    assert sum(rpc["operation"] == "client.background" for rpc in game.calls) == 1
    assert all("includeContext" in rpc for rpc in game.calls if rpc["operation"] in ACTIONS | READS)
    assert len({rpc["requestId"] for rpc in game.calls}) == len(game.calls)


@pytest.mark.parametrize("field,changed,code", [
    ("session", "other-login", "CHAIN_WORLD_CHANGED"), ("world", "other-world", "CHAIN_WORLD_CHANGED"),
    ("dimension", 0, "CHAIN_WORLD_CHANGED"), ("background", MANUAL, "CHAIN_CONTROL_RELEASED"),
])
async def test_boundary_change_blocks_next_real_segment_without_takeover_or_replay(chain, field, changed, code):
    adapter, game = chain
    current = ok(await call(adapter, "open", **BOUND, dimension=DIM, segments=[MINE, EAT]))
    current = verified(await call(adapter, "advance", current), ["mine"])
    setattr(game, field, changed)
    index = len(game.calls)
    aborted = await call(adapter, "advance", current)
    assert aborted.isError and aborted.structuredContent["error"]["code"] == code
    status = ok(await call(adapter, "status"))
    assert status["status"] == "blocked" and status["completed"] == ["mine"] and status["pending"] == ["eat"]
    assert [entry["operation"] for entry in game.calls[index:]] == ["bridge.health", "client.snapshot"]


@pytest.mark.parametrize("guard", ["context_version", "idle_version"])
@pytest.mark.parametrize("invalid", [0, True])
async def test_missing_native_guard_stops_before_taking_over_or_mining(chain, guard, invalid):
    adapter, game = chain
    setattr(game, guard, invalid)
    current = ok(await call(adapter, "open", **BOUND, dimension=DIM, segments=[MINE]))
    result = await call(adapter, "advance", current)
    assert result.isError
    report = result.structuredContent["data"]["last"]["report"]
    assert report["error"]["code"] == "CHAIN_NATIVE_GUARD_REQUIRED" and report["dispatchedActions"] == 0
    assert all(rpc["operation"] in {"bridge.health", "client.snapshot"} for rpc in game.calls)


async def test_new_owner_between_boundary_and_native_action_refuses_without_replay(chain):
    adapter, game = chain
    game.acquire_before = "player.mine"
    current = ok(await call(adapter, "open", **BOUND, dimension=DIM, segments=[MINE, EAT]))
    blocked = await call(adapter, "advance", current)
    assert blocked.isError
    data = blocked.structuredContent["data"]
    report = data["last"]["report"]
    assert data["status"] == "blocked" and data["completed"] == [] and data["pending"] == ["mine", "eat"]
    assert report["error"]["code"] == "INPUT_TASK_ACTIVE" and report["outcomeUnknown"] is False
    assert report["acknowledgedActions"] == 0 and report["steps"][1]["status"] == "not_executed"
    assert game.native["taskId"] == "another-owner" and game.block == "minecraft:stone"
    assert [rpc["operation"] for rpc in game.calls if rpc["operation"] in ACTIONS] == ["player.mine"]
    game.native = {"status": "idle"}
    index = len(game.calls)
    refused = await call(adapter, "advance", data)
    assert refused.isError and refused.structuredContent["error"]["code"] == "CHAIN_NOT_READY"
    assert len(game.calls) == index


@pytest.mark.parametrize("input_receipt", [{"status": "active", "taskId": "conflicting-owner"}, ["idle"]])
async def test_success_with_conflicting_before_receipt_stops_as_uncertain(chain, input_receipt):
    adapter, game = chain
    game.conflicting_receipt = input_receipt
    current = ok(await call(adapter, "open", **BOUND, dimension=DIM, segments=[MINE, EAT]))
    result = await call(adapter, "advance", current)
    assert result.isError
    data = result.structuredContent["data"]
    report = data["last"]["report"]
    assert data["status"] == "blocked" and data["pending"] == ["mine", "eat"]
    assert report["error"]["code"] == "MALFORMED_RESPONSE" and report["outcomeUnknown"] is True
    assert not report["checkpoint"]["effectAcceptanceComplete"]
    assert [rpc["operation"] for rpc in game.calls if rpc["operation"] in ACTIONS] == ["player.mine"]
