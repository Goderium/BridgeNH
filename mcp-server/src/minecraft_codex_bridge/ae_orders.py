"""Bounded, resumable AE goals. Request IDs reserve mutations before dispatch."""
from __future__ import annotations

from copy import deepcopy
import anyio

from .ae_tasks import AeTask, require
from .bridge import BridgeError
from .knowledge import operation_phase, record_wait_sample
from .nbt_order import compound_order_key


def matches(item, selector):
    return (isinstance(item, dict) and not item.get("nbtTruncated", False)
            and all(item.get(k) == selector[k] for k in ("registryName", "metadata"))
            and (item.get("nbt") == selector["nbt"] if "nbt" in selector else item.get("hasTag") is False))


class OrderTask(AeTask):
    def __init__(self, client, args, control, record):
        super().__init__(client, args, control)
        self.record = record

    async def native(self, action, **extra):
        self.remaining()
        arguments = {k: self.args[k] for k in ("expectedSessionId", "expectedWorld", "dimension", "requestId")}
        arguments.update(action=action, **extra)
        # Native execution rechecks session/world/player/dimension/owner/window and AE permissions.
        # Its own receipt is reserved before start/take, so losing this RPC cannot duplicate either.
        if action == "status":
            with operation_phase("ae_order_wait"):
                record_wait_sample()
                return await self.client.invoke("ae.order", arguments)
        self.actions += 1
        with operation_phase("ae_order_" + action):
            return await self.control(self.client, "ae.order", arguments)

    async def wait_for_order(self):
        version = (self.health or {}).get("aeOrderStatusWaitVersion")
        native_wait = type(version) is int and version >= 1
        while True:
            # Leave room for the bridge's final owning-thread read inside the outer goal deadline.
            millis = min(5000, max(0, self.remaining() - 1500)) if native_wait else 0
            observed = await self.native("status", **({"waitMillis": millis} if native_wait else {}))
            self.record["native"] = observed
            if observed.get("state") != "running":
                return observed
            if native_wait:
                require(millis > 0, "AE_GOAL_TIMEOUT", "The order is still running; resume this request ID without resubmitting it.")
                wait = observed.get("statusWait", {})
                require(wait.get("version") == 1 and wait.get("requestedMillis") == millis
                        and wait.get("windowExpired") is True and wait.get("finalStateFresh") is True,
                        "AE_WAIT_UNCONFIRMED", "The bridge did not confirm its bounded status wait; no rapid retry or mutation replay.")
            else:
                # Older bridges retain their status contract, with a bounded lower-frequency fallback.
                await anyio.sleep(min(1.0, self.remaining() / 1000))

    async def terminal(self):
        target = self.args["terminal"]
        if "portable" in target:
            await self.open_portable(target["portable"])
        else:
            await self.world(target)
            await self.approach(target, open_gui=True)
        gui = await self.gui()
        self.verify_owner(await self.ae(gui["screenId"], "owner"), target)
        return gui

    async def confirm_delivery(self):
        delivery = self.record["native"]["delivery"]
        expected = delivery.get("item")
        require(type(delivery.get("slot")) is int and 0 <= delivery["slot"] < 36
                and delivery.get("cursorEmpty") is True and isinstance(expected, dict)
                and type(expected.get("count")) is int and expected["count"] == self.args["amount"]
                and expected.get("nbtTruncated") is not True and type(expected.get("hasTag")) is bool
                and (expected["hasTag"] is False or isinstance(expected.get("nbt"), str)),
                "AE_DELIVERY_UNCONFIRMED", "Delivery lacks the complete destination item identity; do not take again.")

        def received(inventory):
            if inventory.get("truncated") is not False or "cursorItem" not in inventory or inventory["cursorItem"] is not None:
                return False
            rows = [r for r in inventory.get("slots", []) if r.get("index") == delivery["slot"]]
            item = rows[0].get("item") if len(rows) == 1 else None
            if not isinstance(item, dict) or item.get("nbtTruncated") is True:
                return False
            if any(item.get(k) != expected.get(k) for k in ("registryName", "metadata", "count", "hasTag")):
                return False
            return expected["hasTag"] is False or (isinstance(item.get("nbt"), str)
                and compound_order_key(item["nbt"]) == compound_order_key(expected["nbt"]))

        # A server receipt reserves the pickup permanently. Waiting is read-only,
        # uses the existing one-second cadence, and resumes this same receipt.
        await self.wait(lambda: self.step("mcb_inventory", {"includeNbt": True},
            fields=["slots", "cursorItem", "truncated"]), received)
        self.record["clientDelivery"] = {"confirmed": True, "slot": delivery["slot"],
            "amount": delivery["amount"], "fullNbtCompared": True, "cursorEmpty": True}
        self.record["phase"] = "completed"

    async def open_portable(self, owner):
        require((self.health or {}).get("aePortableOwnerVersion") == 1, "AE_PORTABLE_UNAVAILABLE",
                "Portable orders require native item-owner validation.")
        await self.step("mcb_close_gui", action=True)
        inv = await self.step("mcb_inventory", fields=["slots", "cursorItem"])
        item = next((r.get("item") for r in inv.get("slots", []) if r.get("index") == owner["playerSlot"]), None)
        require(inv.get("cursorItem") is None and isinstance(item, dict) and item.get("count") == 1
                and all(item.get(k) == owner[k] for k in ("registryName", "metadata")),
                "AE_OWNER_CHANGED", "Observed portable item must remain alone in its bound hotbar slot with an empty cursor.")
        await self.step("mcb_hotbar", {"slot": owner["playerSlot"]}, action=True)
        snapshot = await self.step("mcb_snapshot", fields=["player"])
        await self.step("mcb_look", {"yaw": snapshot["player"]["yaw"], "pitch": -90}, action=True)
        snapshot = await self.step("mcb_snapshot", fields=["target"])
        require(snapshot.get("target", {}).get("type") == "miss", "AE_PORTABLE_AIM_BLOCKED",
                "Opening a held terminal requires a fresh unobstructed air target.")
        await self.step("mcb_interact", {"action": "use", "expectedHeld": {"slot": owner["playerSlot"], **{k: owner[k] for k in ("registryName", "metadata")}}}, action=True)
        await self.wait(self.gui, lambda r: r.get("screenOpen") is True)
        await self.step("mcb_gui", {"waitFor": {}}, fields=["screenId", "screenOpen"])

    async def resource(self, gui):
        await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "terminal_search", "text": ""}, action=True)
        await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "terminal_setting", "setting": "VIEW_MODE", "value": "CRAFTABLE"}, action=True)
        return await self.wait(lambda: self.scan_resource(gui), lambda r: r is not None)

    async def scan_resource(self, gui):
        found, offset = [], 0
        for _ in range(64):
            page = await self.step("mcb_ae", {"screenId": gui["screenId"], "scope": "terminal", "offset": offset, "limit": 128},
                fields=["entries", "nextOffset", "hasMore", "scanTruncated"])
            require(page.get("scanTruncated") is False, "AE_INCOMPLETE_READ", "Terminal scan was truncated.")
            found.extend(r for r in page.get("entries", []) if matches(r.get("item"), self.args["item"]))
            if page.get("hasMore") is False:
                require(len(found) <= 1, "AE_RESOURCE_AMBIGUOUS", "Expected one exact visible item/NBT resource in the terminal.")
                if not found:
                    return None
                resource = found[0]
                require(resource.get("craftable") is True and resource.get("identityTruncated") is False,
                        "AE_NOT_CRAFTABLE", "The exact complete resource must be craftable.")
                return resource
            following = page.get("nextOffset")
            require(type(following) is int and following > offset, "AE_INCOMPLETE_READ", "Terminal paging made no progress.")
            offset = following
        raise BridgeError("AE_INCOMPLETE_READ", "Terminal enumeration exceeded the bounded 8192-entry goal scan.")

    async def execute(self):
        record = self.record
        if record["phase"] == "new":
            record["phase"] = "preparing"
            gui = await self.terminal()
            resource = await self.resource(gui)
            record["resource"] = resource
            await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "craft_open", "resourceId": resource["resourceId"]}, action=True)
            gui = await self.wait(self.gui, lambda r: r.get("screen") == "appeng.client.gui.implementations.GuiCraftAmount")
            self.verify_owner(await self.ae(gui["screenId"], "owner"), self.args["terminal"])
            # A lost response to calculation is recoverable only by observing the current preview.
            record["phase"] = "preview_pending"
            await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "craft_request", "amount": str(self.args["amount"]), "autoStart": False}, action=True)
        if record["phase"] == "preview_pending":
            gui = await self.wait(self.gui, lambda r: r.get("screen") == "appeng.client.gui.implementations.GuiCraftConfirm", seconds=60)
            self.verify_owner(await self.ae(gui["screenId"], "owner"), self.args["terminal"])
            preview = await self.wait(lambda: self.ae(gui["screenId"], "preview"), lambda r:
                r.get("state", {}).get("startReady") is True or
                r.get("state", {}).get("simulation") is True and int(r.get("state", {}).get("bytesUsed", "0")) > 0,
                seconds=60)
            state = preview["state"]
            record["preview"] = preview
            if not state.get("startReady"):
                record["phase"] = "blocked"
                return
            requested = state.get("requested") or {}
            require(requested.get("identityNbt") == record["resource"]["identityNbt"] and requested.get("amount") == str(self.args["amount"]),
                    "AE_PREVIEW_CHANGED", "Preview item/NBT or amount differs from this order.")
            owner = await self.ae(gui["screenId"], "owner")
            self.verify_owner(owner, self.args["terminal"])
            record["phase"] = "native_pending"
            record["native"] = await self.native("start", windowId=gui["windowId"], owner=owner["state"]["owner"],
                identityNbt=requested["identityNbt"], amount=self.args["amount"], selectedCpuSerial=state["selectedCpuSerial"])
        if record["phase"] in {"native_pending", "collecting", "take_pending"}:
            try:
                observed = await self.wait_for_order()
            except BridgeError as error:
                if error.code == "unknown_order":
                    record["phase"] = "start_unknown"
                raise
            record["native"] = observed
            if observed["state"] == "crafted":
                # Production is finished even if returning to the terminal fails.
                # Resume observes the same native receipt before attempting pickup.
                record["phase"] = "collecting"
                gui = await self.terminal()
                owner = await self.ae(gui["screenId"], "owner")
                self.verify_owner(owner, self.args["terminal"])
                record["phase"] = "take_pending"
                observed = await self.native("take", windowId=gui["windowId"], owner=owner["state"]["owner"])
                record["native"] = observed
            if observed["state"] == "completed":
                delivery = observed.get("delivery", {})
                require(delivery.get("serverConfirmed") is True and delivery.get("fullNbtConservation") is True
                        and delivery.get("amount") == self.args["amount"] and observed.get("craftingId"),
                        "AE_DELIVERY_UNCONFIRMED", "Completion lacks native job identity and exact server inventory proof.")
                record["phase"] = "delivery_sync"
            elif observed["state"] in {"canceled", "start_unknown", "take_unknown"}:
                record["phase"] = observed["state"]
        if record["phase"] == "delivery_sync":
            await self.confirm_delivery()


async def run_order(client, args, lock, control, records):
    async with lock:
        key = (args["expectedSessionId"], args["requestId"])
        record = records.get(key)
        if record is None:
            require(args["action"] == "start", "UNKNOWN_ORDER", "Order is absent from this MCP process; no automatic resubmission.")
            require(len(records) < 64, "ORDER_CAPACITY", "The MCP process retains 64 order receipts without eviction.")
            record = {"phase": "new", "args": deepcopy(args)}
            records[key] = record
        expected = record["args"]
        fields = ("expectedSessionId", "expectedWorld", "dimension") + (("terminal", "item", "amount") if args["action"] == "start" else ())
        require(all(args[k] == expected[k] for k in fields), "ORDER_CONFLICT", "Request ID is bound to different goal parameters.")
        invocation = {**expected, "timeout_ms": args.get("timeout_ms", 30000)}
        task = OrderTask(client, invocation, control, record)
        error = None
        try:
            await task.initialize()
            if record["phase"] not in {"completed", "blocked", "start_unknown", "take_unknown", "canceled", "preparing"}:
                await task.execute()
        except BridgeError as failure:
            error = failure.as_dict()
            record["lastError"] = deepcopy(error)
        report = {"requestId": args["requestId"], "status": record["phase"], "replayAllowed": False,
                  "resumable": record["phase"] in {"preview_pending", "native_pending", "collecting", "take_pending", "delivery_sync"},
                  "dispatchedActionAttempts": task.actions}
        for name in ("preview", "native", "handPreparation", "clientDelivery"):
            if name in record:
                report[name] = deepcopy(record[name])
        if record["phase"] == "completed" and isinstance(record.get("preview"), dict):
            preview = record["preview"]
            state = preview.get("state", {})
            report["preview"] = {"state": {key: state[key] for key in
                ("bytesUsed", "simulation", "noCpu", "selectedCpuSerial", "startReady") if key in state},
                "entryCount": len(preview.get("entries", [])), "detailsOmitted": True,
                "scope": "Completed-order preview summary; native delivery receipt is authoritative."}
        if error is not None or record["phase"] in {"preparing", "start_unknown", "take_unknown"} and "lastError" in record:
            report["error"] = error if error is not None else deepcopy(record["lastError"])
        return report
