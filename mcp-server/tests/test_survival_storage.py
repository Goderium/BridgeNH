from copy import deepcopy

import pytest

from minecraft_codex_bridge import survival_storage as storage
from minecraft_codex_bridge.application import control
from minecraft_codex_bridge.bridge import _ContextData

CHEST = {"kind": "chest", "x": 2, "y": 64, "z": 3}
OTHER = {**CHEST, "x": 3}
DRAWER = {**OTHER, "kind": "drawer", "drawer": 2}
ME = {**OTHER, "kind": "me", "side": 4}
ITEM = {"registryName": "minecraft:stick", "damage": 0, "nbt": '{display:{Name:"Sample"}}'}


def order():
    return {"expectedSessionId": "s", "expectedWorld": "save", "dimension": 0,
            "identity": {"folder": "save", "server": None, "actor": "actor"}, "destination": CHEST,
            "mode": "replenish", "quantity": 12, "item": deepcopy(ITEM)}


class Server:
    def __init__(self):
        self.key = "bound-storage"
        self.count, self.player = 8, 20
        self.actor = "actor"
        self.calls, self.results = [], {}
        self.change = None

    async def invoke(self, op, args):
        self.calls.append((op, deepcopy(args)))
        if op == "bridge.health":
            return {"sessionId": "s", "profile": "player"}
        if op == "survival.storage_receipt":
            return deepcopy(self.results.get(args["intentId"], {"reason": "receipt_unavailable"}))
        assert args["item"] == ITEM
        header = {"source": "server_survival_storage", "storageKey": self.key, "actorId": self.actor,
                  "item": deepcopy(ITEM), "direction": args["direction"]}
        if op == "survival.storage_count":
            return _ContextData({**header, "endpointCount": self.count, "playerCount": self.player,
                                 "observationId": "observed", "windowId": 7, "screenId": "screen"},
                                {"phase": "after", "snapshot": {"screen": "open", "screenId": "screen"}})
        assert op == "survival.storage_move"
        if self.change:
            self.count = self.change
            self.results[args["intentId"]] = {**header, "intentId": args["intentId"], "notSubmitted": True,
                "reason": "target_already_met", "endpointBefore": self.count}
        else:
            n = args["limit"]
            sign = 1 if args["direction"] == "deposit" else -1
            self.results[args["intentId"]] = {**header, "intentId": args["intentId"], "moved": n,
                "reason": "transferred", "conserved": True, "cursorEmpty": True, "endpointBefore": self.count,
                "endpointAfter": self.count + sign*n, "playerBefore": self.player, "playerAfter": self.player - sign*n}
            self.count += sign*n
            self.player -= sign*n
        return deepcopy(self.results[args["intentId"]])


@pytest.fixture
def server(monkeypatch):
    server = Server()

    async def native(client, op, args):
        return await client.invoke(op, args)
    monkeypatch.setattr(control, "invoke_control", native)
    return server


@pytest.mark.parametrize("endpoint", [CHEST, DRAWER, ME])
async def test_deposit_only_shortfall_after_durable_checkpoint(server, endpoint):
    job = order()
    job["destination"] = endpoint
    def checkpoint(intent):
        assert not any(op.endswith("_move") for op, _ in server.calls)
        job["pending"] = deepcopy(intent)
    actual = await storage.storage_step(server, job, endpoint, "deposit", 64, checkpoint)
    assert actual == {"moved": 4, "endpointAfter": 12, "reason": "transferred", "blocked": False}
    assert server.count == 12 and server.player == 16 and job["storageScreenId"] == "screen"
    # Recreate the adapter/order from its durable intent, lose the original connection, and read the actor's receipt.
    recovered = await storage.reconcile_storage(server, deepcopy(job))
    assert recovered == actual
    assert [op for op, _ in server.calls].count("survival.storage_move") == 1
    assert [args for op, args in server.calls if op.endswith("_move")][0]["target"] == "12"


async def test_same_grid_or_double_chest_cannot_be_its_own_source(server):
    job = order()
    assert await storage.storage_count(server, job, CHEST) == 8
    def forbidden(_):
        pytest.fail("No physical move may be reserved from the destination itself")
    result = await storage.storage_step(server, job, OTHER, "withdraw", 6, forbidden)
    assert result["reason"] == "source_is_destination" and result["moved"] == 0
    assert not any(op.endswith("_move") for op, _ in server.calls)


async def test_destination_filled_after_count_returns_proven_zero(server):
    job = order()
    server.change = 14
    result = await storage.storage_step(server, job, CHEST, "deposit", 6, lambda i: job.update(pending=i))
    assert result == {"moved": 0, "endpointAfter": 14, "reason": "target_already_met", "blocked": False}
    assert await storage.reconcile_storage(server, job) == result


async def test_unknown_or_foreign_receipt_never_derives_move_from_inventory(server):
    job = order()
    await storage.storage_step(server, job, OTHER, "withdraw", 2, lambda i: job.update(pending=i))
    identity = job["pending"]["intentId"]
    good = server.results[identity]
    for patch in ({"cursorEmpty": False}, {"storageKey": "different"}, {"moved": True},
                  {"item": {**ITEM, "nbt": "{}"}}, {"playerAfter": 200}, {"actorId": "other"}):
        server.results[identity] = {**good, **patch}
        assert (await storage.reconcile_storage(server, job))["uncertain"]
    server.results.clear()
    assert (await storage.reconcile_storage(server, job))["uncertain"]
    assert [op for op, _ in server.calls].count("survival.storage_move") == 1


async def test_wrong_server_actor_cannot_checkpoint_or_move(server):
    server.actor = "someone-else"
    result = await storage.storage_step(server, order(), CHEST, "withdraw", 2, lambda _: pytest.fail("checkpoint"))
    assert result["blocked"] and not any(op.endswith("_move") for op, _ in server.calls)


async def test_unreachable_opens_once_after_approach_and_closes_only_owned_screen(server, monkeypatch):
    from minecraft_codex_bridge import survival_storage_approach as route
    job = order()
    seen = []
    ordinary = server.invoke
    stages = iter(["unreachable", "endpoint_not_open", None])

    async def invoke(op, args):
        if op == "survival.storage_count":
            why = next(stages)
            if why:
                return {"reason": why, "held": False, "windowClosed": True}
        if op == "survival.storage_open":
            seen.append("open")
            return {"reason": "opened"}
        return await ordinary(op, args)
    async def approach(client, order, endpoint):
        assert endpoint == CHEST
        seen.append("approach")
    async def close(client, order):
        seen.append("close")
    monkeypatch.setattr(route, "approach_storage", approach)
    monkeypatch.setattr(route, "close_storage_screen", close)
    monkeypatch.setattr(server, "invoke", invoke)
    assert await storage.storage_count(server, job, CHEST) == 8
    assert seen == ["approach", "close", "open"]


async def test_waits_for_server_hand_and_closed_window_before_only_open(server, monkeypatch):
    from minecraft_codex_bridge import survival_storage_approach as route
    calls = []
    replies = iter([(True, False, 1), (True, False, 1), (False, True, 3), None])
    ordinary = server.invoke

    async def invoke(op, args):
        if op == "player.hotbar":
            calls.append(("select", args["slot"]))
            return {}
        if op == "survival.storage_open":
            calls.append(("open",))
            return {"reason": "opened"}
        if op == "survival.storage_count":
            row = next(replies)
            if row is not None:
                calls.append(("read", row))
                return {"reason": "endpoint_not_open", "held": row[0], "windowClosed": row[1],
                        "selectedSlot": row[2], "emptyHotbarSlot": 3}
        return await ordinary(op, args)
    async def close(client, order):
        calls.append(("close",))
    monkeypatch.setattr(server, "invoke", invoke)
    monkeypatch.setattr(route, "close_storage_screen", close)
    assert await storage.storage_count(server, order(), CHEST) == 8
    assert calls == [("read", (True, False, 1)), ("close",), ("select", 3), ("read", (True, False, 1)),
                     ("read", (False, True, 3)), ("open",), ("select", 1)]
