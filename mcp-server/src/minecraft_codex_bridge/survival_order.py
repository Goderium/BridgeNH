"""Quantitative survival collection/delivery and deficit replenishment."""

from __future__ import annotations

from copy import deepcopy
from time import monotonic
from uuid import uuid4

from .bridge import BridgeError
from .survival_order_store import OrderStore


def fail(code, message):
    raise BridgeError(code, message, outcome_unknown=False)


async def live_identity(client, args):
    snapshot = await client.invoke("client.snapshot", {})
    world, target, player = (snapshot.get(key) or {} for key in ("world", "lifecycleTarget", "player"))
    if (snapshot.get("worldLoaded") is not True or snapshot.get("sessionId") != args["expectedSessionId"]
            or target.get("expectedWorld") != args["expectedWorld"] or world.get("dimension") != args["dimension"]):
        fail("ORDER_CONTEXT_CHANGED", "Read the current session, lifecycle target and dimension.")
    if player.get("creative") is not False or not player.get("uuid") or (player.get("health") or 0) <= 0:
        fail("SURVIVAL_ACTOR_REQUIRED", "A living survival actor with observable identity is required.")
    folder = world.get("worldFolder")
    if world.get("singleplayer") is True and target.get("kind") == "integrated_world":
        if not folder or folder != args["expectedWorld"]:
            fail("ORDER_WORLD_UNKNOWN", "The integrated save folder must match the current target.")
        server = None
    elif target.get("kind") == "native_connection" and world.get("identitySource") == "remote_server_handshake":
        server = target.get("serverAddress")
        if not (server and folder):
            fail("ORDER_WORLD_UNKNOWN", "The server address and authenticated save identity are required.")
    else:
        fail("ORDER_WORLD_UNKNOWN", "The server did not identify the current save.")
    return {"actor": player["uuid"], "folder": folder, "server": server, "dimension": args["dimension"]}


def view(order):
    in_transit = order["gathered"] + order["withdrawn"] - order["delivered"]
    return {"orderId": order["orderId"], "mode": order["mode"], "revision": order["revision"],
            "status": order["status"], "reason": order["reason"], "quantity": str(order["quantity"]),
            "gathered": str(order["gathered"]), "withdrawn": str(order["withdrawn"]),
            "delivered": str(order["delivered"]), "inTransit": str(in_transit),
            "destinationObserved": None if order["observed"] is None else str(order["observed"]),
            "remaining": None if order["remaining"] is None else str(order["remaining"]),
            "pending": (order["pendingStep"] or {}).get("kind"), "lastStep": order.get("lastStep"),
            "sourceProgress": list(order["sourceProgress"].values()),
            "automaticReplayAllowed": False}


def create_order(args, identity):
    if args["mode"] == "gather":
        from .survival_gather import validate_gather_region
        validate_gather_region(args["gather"])
    for source in args.get("sources", []):
        if source == args["destination"]:
            fail("ORDER_SOURCE_IS_DESTINATION", "Source and destination must be distinct storage locations.")
    quantity = int(args["quantity"])
    return {**{k: deepcopy(v) for k, v in args.items() if k not in ("action", "maxSteps")},
            "quantity": quantity, "status": "ready", "reason": "opened", "revision": 0, "identity": identity,
            "observerId": uuid4().hex,
            "observed": None, "remaining": quantity if args["mode"] == "gather" else None,
            "pendingStep": None, "pending": None, "gatherState": {}, "stopGather": None, "sourceIndex": 0,
            "sourceProgress": {}, "gathered": 0, "withdrawn": 0, "delivered": 0}


def account(order, step, receipt):
    kind = step["kind"]
    if not isinstance(receipt, dict):
        fail("ORDER_RECEIPT_INVALID", "The operation omitted its receipt.")
    reason = receipt.get("reason")
    if not isinstance(reason, str) or not reason or len(reason) > 160:
        fail("ORDER_RECEIPT_INVALID", "The operation omitted its termination reason.")
    if receipt.get("uncertain"):
        if order["pending"] is None:
            fail("ORDER_RECEIPT_INVALID", "An uncertain material action requires a durable checkpoint.")
        order.update(status="pending", reason=reason)
        return
    amount = receipt.get("gathered" if kind == "gather" else "moved")
    if type(amount) is not int or amount < 0 or (kind != "gather" and amount > step["limit"]):
        fail("ORDER_RECEIPT_INVALID", "The observed quantity is outside the authorized step.")
    if amount and not order["pending"]:
        fail("ORDER_RECEIPT_INVALID", "No durable intent precedes the reported material change.")
    order.update(pending=None, pendingStep=None, status="ready", reason=reason,
                 lastStep={"kind": kind, "amount": str(amount), "reason": reason})
    if "endpoint" in step:
        order["lastStep"]["endpoint"] = step["endpoint"]
    if kind == "gather":
        order["gathered"] += amount
        if "state" in receipt:
            order["gatherState"] = receipt["state"]
        if receipt.get("blocked"):
            order["stopGather"] = reason
    elif kind == "withdraw":
        order["withdrawn"] += amount
        source = order["sourceProgress"].setdefault(str(order["sourceIndex"]),
            {"endpoint": step["endpoint"], "withdrawn": "0"})
        source.update(withdrawn=str(int(source["withdrawn"]) + amount), reason=reason)
        if not amount and reason in {"source_empty", "unreachable", "endpoint_unavailable", "source_is_destination"}:
            order["sourceIndex"] += 1
            return
    else:
        order["gatherState"].pop("pickupCursor", None)
        order["delivered"] += amount
        after = receipt.get("endpointAfter")
        if type(after) is int and after >= 0:
            order["observed"] = after
        elif order["observed"] is not None:
            order["observed"] += amount
        if order["mode"] == "gather":
            order["remaining"] = max(0, order["quantity"] - order["delivered"])
            if order["remaining"] == 0:
                order.update(status="completed", reason="quantity_delivered")
                return
        else:
            order["remaining"] = max(0, order["quantity"] - order["observed"])
        if amount and order["stopGather"] == "inventory_full":
            order["stopGather"] = None
    if receipt.get("blocked") and kind != "gather":
        order["status"] = "blocked"


async def next_step(client, order):
    from .survival_storage import storage_count

    received = order["gathered"] + order["withdrawn"] - order["delivered"]
    if order["mode"] == "gather":
        remaining = order["quantity"] - order["delivered"]
        if received < remaining and not order["stopGather"]:
            return {"kind": "gather", "limit": remaining - received}
        if received == 0:
            order.update(status="blocked", reason=order["stopGather"] or "no_items_in_transit")
            return None
    else:
        existing = await storage_count(client, order, order["destination"])
        if type(existing) is not int or existing < 0:
            fail("ORDER_COUNT_INVALID", "The destination count is unavailable.")
        remaining = max(0, order["quantity"] - existing)
        order.update(observed=existing, remaining=remaining)
        if remaining == 0:
            order.update(status="completed", reason="destination_target_observed")
            return None
        if received == 0:
            if order["sourceIndex"] >= len(order["sources"]):
                order.update(status="blocked", reason="sources_exhausted")
                return None
            return {"kind": "withdraw", "endpoint": order["sources"][order["sourceIndex"]], "limit": remaining}
    return {"kind": "deposit", "endpoint": order["destination"], "limit": min(received, remaining)}


async def perform(client, order, store, *, reconcile=False):
    from .survival_gather import gather_step, reconcile_gather
    from .survival_storage import storage_step, reconcile_storage

    step = order["pendingStep"] if reconcile else await next_step(client, order)
    if step is None:
        store.save(order)
        return

    def checkpoint(intent):
        if order["pending"] is not None or not isinstance(intent, dict) or not intent:
            fail("ORDER_ALREADY_PENDING", "Resolve the outstanding material action before dispatch.")
        order.update(pending=intent, pendingStep=step, status="pending", reason="intent_recorded")
        store.save(order)

    if reconcile:
        fn = reconcile_gather if step["kind"] == "gather" else reconcile_storage
        receipt = await fn(client, order)
    elif step["kind"] == "gather":
        if order.get("storageScreenId"):
            from .survival_storage_approach import close_storage_screen
            await close_storage_screen(client, order)
        receipt = await gather_step(client, order, checkpoint)
    else:
        receipt = await storage_step(client, order, step["endpoint"], step["kind"], step["limit"], checkpoint)
    account(order, step, receipt)
    store.save(order)


async def run_order(adapter, args):
    store = OrderStore(adapter.run_log)
    action = args["action"]
    if action == "status":
        order = store.load(args["orderId"])
        if order is None:
            fail("ORDER_NOT_FOUND", "This conversation has no such order in the bound instance.")
        return view(order)

    async with adapter.workflow_lock:
        with store.exclusive():
            order = store.load(args["orderId"])
            if action == "open":
                if order is not None:
                    fail("ORDER_EXISTS", "Read the existing order using status.")
                order = create_order(args, await live_identity(adapter.client, args))
                store.save(order, create=True)
                return view(order)
            if order is None:
                fail("ORDER_NOT_FOUND", "Read the order in its originating instance and conversation.")
            if order["revision"] != args["expectedRevision"]:
                fail("ORDER_CHANGED", "Use status to obtain the current revision and pending step.")
            if action == "cancel":
                if order["pending"] is not None:
                    fail("ORDER_RECONCILIATION_REQUIRED", "Resolve the outstanding action before cancelling.")
                if order["status"] != "completed":
                    order.update(status="cancelled", reason="caller_cancelled")
                    store.save(order)
                return view(order)
            if order["status"] in ("completed", "cancelled"):
                return view(order)

            # Only explicit reconciliation may bind a new game session. Match the
            # actor, physical server, authenticated save and dimension before reads.
            if action == "advance" and (order["expectedSessionId"], order["expectedWorld"]) != (
                    args["expectedSessionId"], args["expectedWorld"]):
                fail("ORDER_RECONCILIATION_REQUIRED", "Reconcile this order against the new connection first.")
            if await live_identity(adapter.client, args) != order["identity"]:
                fail("ORDER_CONTEXT_CHANGED", "The actor, save, server or dimension differs from the original order.")
            if action == "reconcile":
                order.update(expectedSessionId=args["expectedSessionId"], expectedWorld=args["expectedWorld"])
                if order["pending"] is not None:
                    await perform(adapter.client, order, store, reconcile=True)
                else:
                    order["gatherState"].pop("pickupCursor", None)
                    # Reopen an exhausted source pass only on explicit user demand.
                    if order["status"] == "blocked":
                        order.update(sourceIndex=0, stopGather=None, gatherState={})
                    order.update(status="ready", reason="context_reconciled")
                    store.save(order)
                return view(order)
            if order["status"] != "ready" or order["pending"] is not None:
                fail("ORDER_RECONCILIATION_REQUIRED", "Inspect the order and reconcile before continuing.")
            end = monotonic() + 30
            for index in range(args.get("maxSteps", 16)):
                if index and await live_identity(adapter.client, args) != order["identity"]:
                    fail("ORDER_CONTEXT_CHANGED", "The actor or world changed between steps.")
                try:
                    await perform(adapter.client, order, store)
                except BridgeError as error:
                    # Reload the durable version: a failed save after dispatch
                    # must never turn an uncommitted readback into a receipt.
                    order = store.load(args["orderId"])
                    order.update(status="pending" if order["pending"] else "blocked", reason=error.code)
                    store.save(order)
                    return view(order)
                if order["status"] != "ready" or monotonic() >= end:
                    break
            return view(order)
