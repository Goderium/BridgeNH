import asyncio
from copy import deepcopy

import pytest

from minecraft_codex_bridge import survival_gather as gather
from minecraft_codex_bridge.bridge import BridgeError


class Native:
    def __init__(self):
        self.order = {
            "observerId": "b" * 32, "expectedSessionId": "s", "expectedWorld": "save", "dimension": 0,
            "identity": {"actor": "actor", "folder": "save", "server": None},
            "item": {"registryName": "minecraft:log", "damage": 0, "nbt": "{display:{Name:oak}}"},
            "gather": {"bounds": {"minX": 0, "minY": 60, "minZ": 0, "maxX": 8, "maxY": 70, "maxZ": 8}},
            "quantity": 3, "gathered": 0, "gatherState": {},
        }
        self.drops = [{"entityId": 12, "x": 4.5, "y": 64.1, "z": 2.5, "count": 5}]
        self.inventory, self.cursor = 12, 0
        self.free_slots, self.space = 2, 8
        self.events = []
        self.calls, self.mutations = [], []
        self.epoch = "epoch"
        self.coverage = True
        self.task = "idle"
        self.inject = None
        self.hit = {}
        self.last_mine = None
        self.scan = {"blocks": [], "complete": True, "requestedRegionFullyObserved": True}

    def picked(self, count):
        self.cursor += 1
        self.inventory += count
        self.events.append((self.cursor, count))

    def checkpoint(self, intent):
        assert not self.order.get("pending")
        self.order["pending"] = deepcopy(intent)

    async def invoke(self, op, args):
        self.calls.append((op, deepcopy(args)))
        if op == "bridge.health":
            return {"serverBridge": {"connectionId": "con"}}
        if op == "client.snapshot":
            return {"sessionId": "s", "profile": "developer", "world": {"dimension": 0}, "lifecycleTarget": {"expectedWorld": "save"},
                    "player": {"uuid": "actor", "health": 20, "creative": False, "x": .5, "y": 64, "feetY": 64, "eyeY": 65.62, "z": .5},
                    "inputTask": {"status": self.task}, "screenOpen": False, "target": self.hit, "serverBridge": {"connectionId": "con"}}
        if op == "survival.gather_observe":
            assert args["item"] == self.order["item"] and args["observerId"] == self.order["observerId"]
            return {"dimension": 0, "source": "server_player_pickups", "sessionId": "s", "playerUuid": "actor",
                    "connectionId": "con", "epoch": self.epoch, "cursor": self.cursor,
                    "complete": args.get("epoch", self.epoch) == self.epoch, "completeRegion": self.coverage,
                    "inventoryCount": self.inventory, "freeSlots": self.free_slots, "stackSpace": self.space,
                    "pickedUp": sum(n for seq, n in self.events if seq > args.get("after", self.cursor)), "drops": self.drops}
        if op == "world.scan":
            return self.scan
        if op == "player.task":
            return {"lastMining": self.last_mine, "status": self.task}
        raise AssertionError(op)

    async def action(self, client, order, snap, op, args):
        assert order.get("pending"), "durable before all actions including travel and aiming"
        self.mutations.append((op, args))
        if self.inject:
            return self.inject(op, args)
        assert op == "player.navigate"
        self.picked(5)
        self.drops.clear()
        return {"status": "arrived", "arrivalConfirmed": True}

    def step(self):
        return asyncio.run(gather.gather_step(self, self.order, self.checkpoint))

    def apply(self, answer):
        self.order.update(pending=None, gatherState=answer.get("state", {}), lastStep={"kind": "gather"})
        self.order["gathered"] += answer["gathered"]


@pytest.fixture
def native(monkeypatch):
    fixture = Native()
    monkeypatch.setattr(gather, "_action", fixture.action)
    return fixture


def test_ordinary_pickup_credits_real_delta_including_overshoot(native):
    result = native.step()
    assert result["gathered"] == 5 and result["inventoryAfter"] == 17
    assert native.order["pending"]["baseline"]["inventoryCount"] == 12
    assert [op for op, _ in native.mutations] == ["player.navigate"]


def test_inventory_gain_alone_or_despawn_proves_no_pickup(native):
    def disappear(op, args):
        native.drops.clear()
        native.inventory += 5
        raise BridgeError("target_unreachable", "drop disappeared")
    native.inject = disappear
    result = native.step()
    assert result["gathered"] == 0 and result["state"]["skippedDrops"] == [12]


def test_partial_uses_cleared_target_and_next_reachable_drop(native):
    native.drops.append({"entityId": 13, "x": 7, "y": 64, "z": 4.5, "count": 2})
    def travel(op, args):
        if args["x"] == 4.5:
            raise BridgeError("route_unavailable", "unreachable")
        native.picked(2)
        return {"status": "arrived"}
    native.inject = travel
    skipped = native.step()
    assert not skipped.get("blocked") and skipped["gathered"] == 0
    native.apply(skipped)
    assert native.step()["gathered"] == 2


def test_capacity_and_coverage_do_not_issue_input(native):
    native.free_slots = native.space = 0
    assert native.step()["reason"] == "inventory_full"
    native.space = 1
    native.drops.clear()
    native.coverage = False
    assert native.step()["reason"] == "gather_coverage_partial"
    assert not native.mutations and not native.order.get("pending")


def test_mine_then_late_pickup_is_credited_before_another_action(native):
    target = {"x": 0, "y": 64, "z": 1}
    native.drops.clear()
    native.hit = {**target, "type": "BLOCK"}
    native.order["gather"].update(blockTargets=[target], block={"registryName": "minecraft:log", "metadata": 0, "hotbarSlot": 2})
    def mine(op, args):
        if op == "player.hotbar":
            return {"selectedHotbarSlot": 2}
        assert op == "player.mine"
        native.last_mine = {"taskId": "m", "status": "stopped", "reason": "block_changed_on_client"}
        return {"taskId": "m"}
    native.inject = mine
    mined = native.step()
    assert mined["gathered"] == 0 and not mined.get("blocked") and "pickupCursor" in mined["state"]
    native.apply(mined)
    native.picked(3)
    result = native.step()
    assert result["gathered"] == 3 and "pickupCursor" not in result["state"]
    assert len(native.mutations) == 2


def test_deposit_invalidates_interstep_eligibility(native):
    native.order.update(gatherState={"pickupCursor": {"epoch": "epoch", "cursor": 0, "inventoryCount": 12}},
                        lastStep={"kind": "deposit"})
    native.drops.clear()
    native.picked(1)
    assert native.step()["gathered"] == 0 and not native.mutations


def test_unknown_movement_reconciles_without_second_dispatch(native):
    def lost(op, args):
        native.picked(2)
        raise BridgeError("timeout", "lost reply", outcome_unknown=True)
    native.inject = lost
    assert native.step()["uncertain"]
    native.task = "active"
    assert asyncio.run(gather.reconcile_gather(native, native.order))["uncertain"]
    native.task = "idle"
    receipt = asyncio.run(gather.reconcile_gather(native, native.order))
    assert receipt["gathered"] == 2 and receipt["blocked"]
    assert len(native.mutations) == 1
    native.inventory -= 1
    assert asyncio.run(gather.reconcile_gather(native, native.order))["reason"] == "pickup_inventory_diverged"
    native.epoch = "rejoined"
    assert asyncio.run(gather.reconcile_gather(native, native.order))["uncertain"]


def test_only_authorized_region_can_be_used(native):
    native.order["gather"]["blockTargets"] = [{"x": 30, "y": 64, "z": 3}]
    with pytest.raises(BridgeError, match="bounds"):
        gather.validate_gather_region(native.order["gather"])
    assert native.step()["blocked"] and not native.mutations


def test_dense_region_caches_40_mines_without_stranding_native_cursors(native, monkeypatch):
    native.drops.clear()
    native.order["quantity"] = 40
    native.order["gather"].update(bounds=dict(minX=0, maxX=7, minY=64, maxY=67, minZ=0, maxZ=7),
                                 block={"registryName": "minecraft:log", "metadata": 0, "hotbarSlot": 1})
    rows = [dict(x=i % 8, y=64, z=i // 8, registryName="minecraft:log", metadata=0) for i in range(40)]
    attempts = []
    async def mine(client, order, snap, target, baseline):
        assert native.order["pending"]
        attempts.append(tuple(target[a] for a in "xyz"))
        return "mining_block_changed_on_client"
    original = native.invoke
    async def scan(op, args):
        if op == "world.scan":
            native.calls.append((op, args))
            if args.get("cursor"):
                assert args["cursor"] == "one-use"
                return {"blocks": rows[20:], "complete": True, "requestedRegionFullyObserved": True}
            return {"blocks": rows[:20], "complete": False, "nextCursor": "one-use"}
        return await original(op, args)
    monkeypatch.setattr(gather, "_mine", mine)
    native.invoke = scan
    for _ in range(40):
        result = native.step()
        assert result["reason"] == "mining_block_changed_on_client" and not result.get("blocked")
        native.apply(result)
    assert len(attempts) == len(set(attempts)) == 40
    assert [a.get("cursor") for op,a in native.calls if op == "world.scan"] == [None, "one-use"]


def test_expired_cursor_restarts_only_current_tile_and_excludes_attempted_positions(native):
    cfg = native.order["gather"]
    cfg["block"] = {"registryName": "minecraft:log", "metadata": 0, "hotbarSlot": 1}
    native.scan = {"blocks": [dict(x=i, y=60, z=0, registryName="minecraft:log", metadata=0) for i in (0,1)],
                   "complete": True, "requestedRegionFullyObserved": True}
    original = native.invoke
    async def expired(op, args):
        if args.get("cursor") == "expired":
            raise BridgeError("stale_cursor", "expired")
        return await original(op, args)
    native.invoke = expired
    state = {"scan": {"index": 0, "partial": False, "cursor": "expired", "attempted": [[0,60,0]]}}
    rows, _, after = asyncio.run(gather._block_candidates(native, native.order, state))
    assert rows == [dict(x=1, y=60, z=0)] and after["index"] == 1
    assert set(native.calls[-1][1]) == {"dimension","bounds","limit"}


def test_explicit_unreachable_is_not_skipped_until_input_is_idle(native):
    def moving(op,args):
        native.task = "active"
        raise BridgeError("route_unavailable", "route interrupted")
    native.inject = moving
    assert native.step()["uncertain"]
    assert native.order["pending"] and not native.order["gatherState"]
