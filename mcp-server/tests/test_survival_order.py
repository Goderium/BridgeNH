from copy import deepcopy
from types import SimpleNamespace

import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.run_log import RunLog
from minecraft_codex_bridge.survival_order import run_order


CTX = {"expectedSessionId": "session", "expectedWorld": "save", "dimension": 0}
TARGET = {"kind": "chest", "x": 1, "y": 70, "z": 0}
SOURCES = [{**TARGET, "x": 2}, {**TARGET, "x": 3}]
OPEN = {"action": "open", "orderId": "refill", **CTX, "mode": "replenish", "quantity": "11",
        "sources": SOURCES, "destination": TARGET, "item": {"registryName": "minecraft:log", "damage": 1}}


class Client:
    def __init__(self, tmp, chat="operator"):
        game = tmp / ".minecraft"
        runtime = game / "config" / "codexbridge" / "runtime.json"
        runtime.parent.mkdir(exist_ok=True, parents=True)
        self.configuration = SimpleNamespace(runtime_path=lambda: runtime,
            conversation_binding={"instancePath": str(tmp), "conversationId": chat})
        self.snapshot = {"worldLoaded": True, "sessionId": "session", "world": {
            "singleplayer": True, "worldFolder": "save", "dimension": 0},
            "lifecycleTarget": {"expectedWorld": "save", "kind": "integrated_world"},
            "player": {"uuid": "fixture-player", "creative": False, "health": 20}}

    async def invoke(self, op, args):
        assert op == "client.snapshot"
        return deepcopy(self.snapshot)


def adapter(tmp, chat="operator"):
    client = Client(tmp, chat)
    return SimpleNamespace(client=client, workflow_lock=anyio.Lock(), run_log=RunLog(client.configuration))


def cmd(action, receipt, **kwargs):
    return {"action": action, "orderId": receipt["orderId"], "expectedRevision": receipt["revision"],
            **({} if action == "cancel" else CTX), **kwargs}


@pytest.mark.anyio
async def test_open_requires_current_living_survival_actor_and_distinct_bins(tmp_path):
    app = adapter(tmp_path)
    app.client.snapshot["player"]["creative"] = True
    with pytest.raises(BridgeError, match="survival"):
        await run_order(app, OPEN)
    app.client.snapshot["player"]["creative"] = False
    with pytest.raises(BridgeError, match="distinct"):
        await run_order(app, {**OPEN, "sources": [TARGET]})
    opened = await run_order(app, OPEN)
    cancelled = await run_order(app, cmd("cancel", opened))
    assert cancelled["status"] == "cancelled"
    assert (await run_order(app, cmd("advance", cancelled))) == cancelled


def test_instance_lock_releases_after_worker_error(tmp_path):
    from minecraft_codex_bridge.survival_order_store import OrderStore
    one = OrderStore(adapter(tmp_path).run_log)
    two = OrderStore(adapter(tmp_path, "other").run_log)
    with pytest.raises(ValueError):
        with one.exclusive():
            with pytest.raises(BridgeError, match="advancing"):
                with two.exclusive():
                    pytest.fail("simultaneous instance writers")
            raise ValueError("simulate worker exit")
    with two.exclusive():
        assert two.owner != one.owner


@pytest.mark.anyio
async def test_public_adapter_validates_order_and_reads_durable_status(tmp_path):
    from minecraft_codex_bridge.application.adapter import Adapter
    app = Adapter(Client(tmp_path))
    invalid = await app._call_tool("mcb_survival_order", {**OPEN, "unexpected": True})
    assert invalid.isError and invalid.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    opened = await app._call_tool("mcb_survival_order", OPEN)
    assert not opened.isError, opened.structuredContent
    read = await app._call_tool("mcb_survival_order", {"action": "status", "orderId": "refill"})
    assert read.structuredContent["data"] == opened.structuredContent["data"]


class Stores:
    def __init__(self, monkeypatch):
        from minecraft_codex_bridge import survival_storage
        self.counts = {1: 3, 2: 4, 3: 7}
        self.moves = []
        self.interrupt = False
        monkeypatch.setattr(survival_storage, "storage_count", self.count)
        monkeypatch.setattr(survival_storage, "storage_step", self.transfer)
        monkeypatch.setattr(survival_storage, "reconcile_storage", self.reconcile)

    async def count(self, client, order, endpoint):
        return self.counts[endpoint["x"]]

    async def transfer(self, client, order, endpoint, direction, limit, checkpoint):
        pos = endpoint["x"]
        moved = limit if direction == "deposit" else min(limit, self.counts[pos])
        if not moved:
            return {"moved": 0, "reason": "source_empty", "blocked": True}
        checkpoint({"before": self.counts[pos], "amount": moved})
        self.counts[pos] += moved if direction == "deposit" else -moved
        self.moves.append((direction, moved))
        if self.interrupt:
            raise RuntimeError("simulate worker death after mutation")
        return {"moved": moved, "reason": "transferred"}

    async def reconcile(self, client, order):
        assert order["pendingStep"]["kind"] == "withdraw"
        qty = order["pending"]["before"] - self.counts[2]
        assert qty == order["pending"]["amount"]
        return {"moved": qty, "reason": "reconciled"}


@pytest.mark.anyio
async def test_replenish_net_delta_consecutive_sources_and_durable_status(tmp_path, monkeypatch):
    stocks = Stores(monkeypatch)
    first = await run_order(adapter(tmp_path), OPEN)
    done = await run_order(adapter(tmp_path), cmd("advance", first, maxSteps=12))
    assert (done["status"], done["delivered"], done["remaining"], done["destinationObserved"]) == (
        "completed", "8", "0", "11")
    assert stocks.moves == [("withdraw", 4), ("deposit", 4), ("withdraw", 4), ("deposit", 4)]
    assert [(row["withdrawn"], row["reason"]) for row in done["sourceProgress"]] == [
        ("4", "source_empty"), ("4", "transferred")]
    assert await run_order(adapter(tmp_path), {"action": "status", "orderId": "refill"}) == done


@pytest.mark.anyio
async def test_crash_reconcile_only_then_finish_with_new_session(tmp_path, monkeypatch):
    stocks = Stores(monkeypatch)
    app = adapter(tmp_path)
    first = await run_order(app, OPEN)
    stocks.interrupt = True
    with pytest.raises(RuntimeError):
        await run_order(app, cmd("advance", first))
    app = adapter(tmp_path)
    pending = await run_order(app, {"action": "status", "orderId": "refill"})
    assert pending["pending"] == "withdraw" and pending["withdrawn"] == "0"
    app.client.snapshot["sessionId"] = "new"
    with pytest.raises(BridgeError, match="Reconcile"):
        await run_order(app, cmd("advance", pending, expectedSessionId="new"))
    with pytest.raises(BridgeError, match="outstanding"):
        await run_order(app, cmd("cancel", pending))
    app.client.snapshot["player"]["uuid"] = "someone-else"
    with pytest.raises(BridgeError, match="actor"):
        await run_order(app, cmd("reconcile", pending, expectedSessionId="new"))
    app.client.snapshot["player"]["uuid"] = "fixture-player"
    resumed = await run_order(app, cmd("reconcile", pending, expectedSessionId="new"))
    assert resumed["withdrawn"] == "4" and resumed["inTransit"] == "4"
    assert stocks.moves == [("withdraw", 4)]
    with pytest.raises(BridgeError, match="revision"):
        await run_order(app, cmd("advance", pending, expectedSessionId="new"))
    stocks.interrupt = False
    done = await run_order(app, cmd("advance", resumed, expectedSessionId="new", maxSteps=12))
    assert done["delivered"] == "8" and stocks.counts[1] == 11


@pytest.mark.anyio
async def test_counted_gather_delivers_partial_before_reporting_shortage(tmp_path, monkeypatch):
    from minecraft_codex_bridge import survival_gather
    stocks = Stores(monkeypatch)
    async def gather(client, order, checkpoint):
        if order["gathered"]:
            return {"gathered": 0, "reason": "no_reachable_target", "blocked": True}
        checkpoint({"actorCount": 6})
        return {"gathered": 5, "reason": "picked_up"}
    monkeypatch.setattr(survival_gather, "gather_step", gather)
    args = {**OPEN, "mode": "gather", "gather": {"bounds": {
        "minX": 0, "minY": 60, "minZ": 0, "maxX": 10, "maxY": 70, "maxZ": 10}}}
    del args["sources"]
    record = await run_order(adapter(tmp_path), args)
    partial = await run_order(adapter(tmp_path), cmd("advance", record, maxSteps=8))
    assert (partial["status"], partial["gathered"], partial["delivered"], partial["remaining"]) == (
        "blocked", "5", "5", "6")
    assert stocks.counts[1] == 8


@pytest.mark.anyio
async def test_replenish_preserves_long_deficit_and_transit_if_target_fills(tmp_path, monkeypatch):
    stocks = Stores(monkeypatch)
    app = adapter(tmp_path)
    target = (1 << 53) + 7
    stocks.counts[1] = target - 3
    opened = await run_order(app, {**OPEN, "quantity": str(target)})
    carried = await run_order(app, cmd("advance", opened, maxSteps=1))
    assert (carried["remaining"], carried["inTransit"], carried["delivered"]) == ("3", "3", "0")
    stocks.counts[1] = target
    done = await run_order(app, cmd("advance", carried))
    assert (done["status"], done["remaining"], done["inTransit"]) == ("completed", "0", "3")
    assert stocks.moves == [("withdraw", 3)]


@pytest.mark.anyio
async def test_already_stocked_and_conversation_isolation(tmp_path, monkeypatch):
    stocks = Stores(monkeypatch)
    stocks.counts[1] = 30
    app = adapter(tmp_path)
    first = await run_order(app, OPEN)
    with pytest.raises(BridgeError, match="existing order"):
        await run_order(app, OPEN)
    with pytest.raises(BridgeError, match="no such order"):
        await run_order(adapter(tmp_path, "other"), {"action": "status", "orderId": "refill"})
    done = await run_order(app, cmd("advance", first))
    assert done["status"] == "completed" and done["delivered"] == "0" and stocks.moves == []
