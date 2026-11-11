"""AE goal workflows composed from the existing guarded sequence and approach engines."""
from __future__ import annotations

import time
import anyio

from .bridge import BridgeError
from .workflows import run_workflow, _workflow_discovery
from .interaction_approach import run_interaction_approach
from .placement_attachment import VECTORS, from_plan, validate_receipt
from .knowledge import operation_phase, record_wait_sample
from .batch_receipts import install_checkpoint, validate_install_resume


def require(condition, code, message):
    if not condition:
        raise BridgeError(code, message)


class AeTask:
    def __init__(self, client, args, control):
        self.client, self.args, self.control = client, args, control
        self.inner_lock = anyio.Lock()
        self.deadline = time.monotonic() + args.get("timeout_ms", 30000) / 1000
        self.actions = 0
        self.discovery = None
        self.health = None
        self.hand_preparations = []

    def remaining(self):
        value = int((self.deadline - time.monotonic()) * 1000)
        require(value > 0, "AE_GOAL_TIMEOUT", "The bounded goal deadline expired; submitted actions are not replayed.")
        return min(60000, value)

    async def step(self, tool, args=None, *, action=False, fields=None):
        step = {"id": "operation", "kind": "action" if action else "read", "tool": tool,
                "args": args or {}, "omitEmptySlots": False}
        if fields is not None:
            step["fields"] = fields
        if action:
            self.actions += 1
        with operation_phase(tool.removeprefix("mcb_")):
            result = await run_workflow(self.client, {
                "expectedSessionId": self.args["expectedSessionId"], "expectedWorld": self.args["expectedWorld"],
                "timeout_ms": self.remaining(), "steps": [step], "max_return_bytes": 262144,
            }, self.inner_lock, _discovery=self.discovery, _require_background=self.discovery is not None)
        if result["status"] != "completed":
            self.fail(result)
        projection = result["steps"][0]["observation"]
        require(not projection.get("omitted") and not projection.get("fieldsOmitted"), "AE_INCOMPLETE_READ", "Required goal evidence exceeded its return budget.")
        return projection["values"]

    def fail(self, receipt):
        raise BridgeError("AE_STEP_FAILED", "A guarded operation failed; no automatic replay.", receipt=receipt)

    async def initialize(self):
        h = await self.client.invoke("bridge.health", {})
        require(h.get("profile") == "developer" and h.get("aeGoalWorkflowVersion") == 1 and h.get("readiness", {}).get("localIntegratedServer") is True,
                "AE_GOALS_UNAVAILABLE", "AE goals require the updated local developer bridge.")
        require(h.get("sessionId") == self.args["expectedSessionId"], "SESSION_CHANGED", "AE goal session differs from current runtime.")
        self.discovery = _workflow_discovery(self.client, self.client.configuration.read(), h)
        self.health = h
        await self.control(self.client, "client.background", {"enabled": True})
        s = await self.step("mcb_snapshot", fields=["world", "worldLoaded", "player"])
        require(s.get("world", {}).get("dimension") == self.args["dimension"], "WORLD_CHANGED", "Goal dimension differs from the actual world.")

    async def gui(self):
        return await self.step("mcb_gui", fields=["screenId", "screen", "screenOpen", "windowId", "openContainerMatches"])

    async def ae(self, screen, scope):
        return await self.step("mcb_ae", {"screenId": screen, "scope": scope, "limit": 128},
                               fields=["screenId", "scope", "state", "entries", "hasMore", "nextOffset", "truncated"])

    async def wait(self, reader, predicate, *, seconds=5):
        until = min(self.deadline, time.monotonic() + seconds)
        while True:
            record_wait_sample()
            observed = await reader()
            if predicate(observed):
                return observed
            require(time.monotonic() < until, "AE_OBSERVATION_TIMEOUT", "Expected native state was not observed; do not replay the mutation.")
            await anyio.sleep(min(1.0, max(0, until - time.monotonic())))

    async def empty_hand(self):
        inv = await self.step("mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot"])
        require(inv.get("cursorItem") is None, "CURSOR_OCCUPIED", "Clear the real cursor before an AE goal.")
        candidates = [r["index"] for r in inv.get("slots", []) if type(r.get("index")) is int and 0 <= r["index"] <= 8 and r.get("item") is None]
        if not candidates:
            # Use the existing native inventory planning/mapping/movement and
            # full tagged-context guards, rather than requiring agent slot clicks.
            from .ae_inventory import AeHandPreparation
            hotbar = [r for r in inv.get("slots", []) if type(r.get("index")) is int and 0 <= r["index"] <= 8]
            require(len(hotbar) == 9 and {r["index"] for r in hotbar} == set(range(9)),
                    "EMPTY_HAND_REQUIRED", "Complete hotbar coverage is required before preparing an empty hand.")
            require(len(self.hand_preparations) < 3, "AE_HAND_PREPARATION_LIMIT",
                    "Repeated new inventory arrivals exhausted the bounded hand-preparation budget.")
            snapshot = await self.step("mcb_snapshot", fields=["player"])
            helper = AeHandPreparation(self, snapshot)
            report = {}
            self.hand_preparations.append(report)
            record = getattr(self, "record", None)
            if isinstance(record, dict):
                record.setdefault("handPreparation", []).append(report)
            try:
                await helper.prepare()
            finally:
                self.actions += sum(p.get("dispatchedActions", 0) for p in helper.phases)
                report.update(helper.receipt())
            inv = await self.step("mcb_inventory", fields=["slots", "cursorItem", "selectedHotbarSlot"])
            require(inv.get("cursorItem") is None, "CURSOR_OCCUPIED", "Native hand preparation must leave the real cursor empty.")
            candidates = [r["index"] for r in inv.get("slots", []) if type(r.get("index")) is int and 0 <= r["index"] <= 8 and r.get("item") is None]
        require(bool(candidates), "EMPTY_HAND_REQUIRED", "An empty hotbar slot is required to open AE hosts without using a tool.")
        await self.step("mcb_hotbar", {"slot": candidates[0]}, action=True)

    async def approach(self, target, *, open_gui, excluded_build_envelope=None):
        if open_gui:
            await self.step("mcb_close_gui", action=True)
        args = {k: self.args[k] for k in ("expectedSessionId", "expectedWorld", "dimension")}
        strategy = self.args.get("strategy", "walking")
        # Reuse the AE goal's existing deadline for segmented native travel.
        # Travel remains bounded by that deadline, without automatic movement retry.
        args.update(targets=[target], strategy=strategy, move=True, interact=open_gui)
        if open_gui:
            await self.empty_hand()
            args["openGui"] = {}
        args["timeout_ms"] = min(60000 if strategy in {"walking", "ability_flight"} else 15000, self.remaining())
        try:
            receipt = await run_interaction_approach(self.client, args, self.inner_lock, control=self.control,
                excluded_build_envelope=excluded_build_envelope, _discovery=self.discovery, _close_gui=not open_gui)
        except BridgeError as error:
            self.actions += bool(error.details.get("receipt", {}).get("guiCloseDispatched"))
            raise
        self.actions += bool(receipt.get("guiCloseDispatched"))
        if receipt.get("status") != "completed" or open_gui and receipt.get("interactionEffectConfirmed") is not True:
            raise BridgeError("AE_APPROACH_UNCONFIRMED", "Actual approach and requested GUI acceptance must complete before the next AE action; never replay.",
                receipt=receipt, replayAllowed=False)
        return receipt

    def verify_owner(self, observed, target):
        owner = observed.get("state", {}).get("owner", {})
        if "portable" in target:
            require(owner == target["portable"] and owner.get("dimension") == self.args["dimension"],
                    "AE_OWNER_CHANGED", "Portable player, slot, item or network binding changed.")
            return
        expected = {k: target[k] for k in ("x", "y", "z")}
        expected["dimension"] = self.args["dimension"]
        if "part" in target["targetIdentity"]:
            expected["part"] = target["targetIdentity"]["part"]
        require(owner == expected, "AE_OWNER_CHANGED", "The current AE container belongs to a different host or mounted part.")

    async def world(self, target):
        value = await self.step("mcb_world_inspect", {**{k: target[k] for k in ("x", "y", "z")},
             "dimension": self.args["dimension"], "includeAe": True},
             fields=["source", "x", "y", "z", "dimension", "registryName", "metadata", "gregtech", "parts", "ae"])
        return self.verify_world(value, target)

    def verify_world(self, value, target):
        require(value.get("source") == "integrated_server" and value.get("dimension") == self.args["dimension"], "AE_OWNER_UNVERIFIED", "Authoritative loaded owner evidence is required.")
        require(all(value.get(k) == target[k] for k in ("x", "y", "z")), "AE_OWNER_CHANGED", "AE evidence belongs to a different position.")
        identity = target["targetIdentity"]
        require(all(value.get(k) == identity[k] for k in ("registryName", "metadata") if k in identity), "AE_OWNER_CHANGED", "Owner block identity changed.")
        if "part" in identity:
            require(identity["part"] in value.get("parts", []), "AE_OWNER_CHANGED", "Mounted part identity changed.")
        if "metaTileId" in identity:
            require(value.get("gregtech", {}).get("metaTileId") == identity["metaTileId"], "AE_OWNER_CHANGED", "GT owner identity changed.")
        return value

    async def worlds(self, targets):
        """One existing owner-thread batch, including different mounted parts at the same cell."""
        positions = {tuple(t[k] for k in ("x", "y", "z")): {k: t[k] for k in ("x", "y", "z")} for t in targets}
        require(1 <= len(positions) <= 16, "AE_OWNER_BATCH_LIMIT", "Network acceptance requires one bounded batch of at most 16 host cells.")
        page = await self.step("mcb_world_inspect_many", {"positions": list(positions.values()),
            "dimension": self.args["dimension"], "compact": False, "includeAe": True}, fields=["source", "dimension", "results"])
        rows = page.get("results")
        require(page.get("source") == "integrated_server" and page.get("dimension") == self.args["dimension"]
            and isinstance(rows, list) and len(rows) == len(positions), "AE_OWNER_UNVERIFIED", "Every network owner needs complete authoritative batch coverage.")
        observed = {}
        for point, row in zip(positions, rows):
            require(row.get("ok") is True and isinstance(row.get("data"), dict), "AE_OWNER_UNVERIFIED", "A network owner is unloaded, partial or unavailable.")
            observed[point] = row["data"]
        return [self.verify_world(observed[tuple(t[k] for k in ("x", "y", "z"))], t) for t in targets]

    async def configuration(self, target):
        value = await self.world(target)
        side = target["targetIdentity"].get("part", {}).get("side", -1)
        matches = [h for h in value.get("ae", {}).get("hosts", []) if h.get("mountingSide") == side]
        require(len(matches) == 1 and matches[0].get("configuration", {}).get("complete") is True,
                "AE_CONFIG_UNAVAILABLE", "The exact server owner does not expose complete AE configuration.")
        return matches[0]["configuration"]

    async def configure(self, declaration):
        target = declaration["target"]
        before = await self.configuration(target)
        requested = declaration.get("settings", {})
        require(all(k in before["settings"] for k in requested), "AE_CONFIG_UNSUPPORTED", "A requested setting is absent from this host.")
        require("priority" not in declaration or "priority" in before, "AE_CONFIG_UNSUPPORTED", "This host has no native priority.")
        if all(before["settings"][key] == value for key, value in requested.items()) and (
                "priority" not in declaration or before["priority"] == declaration["priority"]):
            return {"target": target, "before": before, "after": before, "serverConfirmed": True, "alreadyMatched": True}
        await self.approach(target, open_gui=True)
        gui = await self.gui()
        live = await self.ae(gui["screenId"], "configuration")
        self.verify_owner(live, target)
        for setting, desired in requested.items():
            if before["settings"][setting] == desired:
                continue
            live = await self.wait(lambda: self.ae(gui["screenId"], "configuration"),
                lambda r: r.get("state", {}).get("settings", {}).get(setting) == before["settings"][setting])
            self.verify_owner(live, target)
            await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "host_setting",
                "setting": setting, "expectedValue": before["settings"][setting], "value": desired}, action=True)
            await self.wait(lambda: self.configuration(target), lambda r: r["settings"].get(setting) == desired)
        if "priority" in declaration and before["priority"] != declaration["priority"]:
            await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "priority_open"}, action=True)
            gui = await self.wait(self.gui, lambda r: r.get("screen") == "appeng.client.gui.implementations.GuiPriority")
            self.verify_owner(await self.ae(gui["screenId"], "configuration"), target)
            await self.step("mcb_ae_action", {"screenId": gui["screenId"], "action": "priority_set", "priority": declaration["priority"]}, action=True)
        after = await self.wait(lambda: self.configuration(target), lambda r:
            all(r["settings"].get(k) == v for k, v in requested.items()) and
            ("priority" not in declaration or r.get("priority") == declaration["priority"]))
        await self.step("mcb_close_gui", action=True)
        return {"target": target, "before": before, "after": after, "serverConfirmed": True}

    async def install(self, declaration):
        attachment = declaration["attachment"]
        side = attachment["allowedSides"][0]
        vector = VECTORS[side]
        target = {axis: attachment[axis] + vector[i] for i, axis in enumerate(("x", "y", "z"))}
        target["dimension"] = self.args["dimension"]
        part = declaration["expectedPart"]
        same_host = declaration.get("placement") == "host"
        require(part["side"] in (6, side if same_host else side ^ 1), "AE_PART_SIDE", "Mounted part side must face its declared adjacent attachment.")
        destination = attachment if same_host else target
        envelope = {bound + axis.upper(): destination[axis] for axis in ("x", "y", "z") for bound in ("min", "max")}
        await self.approach(attachment, open_gui=False, excluded_build_envelope=envelope)
        observed = await self.world(attachment)
        observed, orientation = await self.orient_item_port(attachment, observed, part, same_host)
        selected = {**observed, "side": side}
        binding = from_plan(self.health, target, selected, [(attachment, side)])
        if same_host:
            target.update({axis: attachment[axis] for axis in ("x", "y", "z")})
        receipt = await self.step("mcb_place_block", {**target, "expectedWorld": self.args["expectedWorld"],
            "hotbarSlot": declaration["hotbarSlot"], "expectedPart": part, "attachmentBinding": binding, "partPlacement": declaration.get("placement", "adjacent")}, action=True,
            fields=["serverConfirmed", "serverConsumptionConfirmed", "serverSourceConsumption", "serverTarget", "allPlayerSlots",
                    "fullNbtConservation", "attachmentBindingVerified", "clientAttachmentBindingVerified", "serverAttachmentBindingVerified",
                    "attachmentBinding", "attachment", "expectedPart", "actualSurvival"])
        validate_receipt(receipt, binding)
        require(part in receipt.get("serverTarget", {}).get("parts", []), "AE_PART_UNCONFIRMED", "Exact server mounted part was not confirmed.")
        return {"target": target, "part": part, "serverConfirmed": True,
                "sourceConsumption": receipt["serverSourceConsumption"], "fullNbtConservation": receipt["fullNbtConservation"],
                "portOrientation": orientation}

    async def orient_item_port(self, attachment, observed, part, same_host):
        """Ordinary GT item buses expose their inventory only on the front side."""
        gt = observed.get("gregtech", {})
        classes = {"gregtech.api.metatileentity.implementations.MTEHatchInputBus",
                   "gregtech.api.metatileentity.implementations.MTEHatchOutputBus"}
        if (same_host or gt.get("metaClass") not in classes
                or part.get("registryName") != "appliedenergistics2:item.ItemMultiPart"
                or part.get("metadata") not in (220, 240, 260)):
            return observed, {"required": False, "scope": "Only ordinary GT item buses with adjacent AE item IO have a known front-only contract."}
        desired = attachment["allowedSides"][0]
        def facing(value):
            return value.get("gregtech", {}).get("orientation", {}).get("facing", {}).get("side")
        before = facing(observed)
        require(type(before) is int and 0 <= before <= 5, "AE_PORT_FACING_UNVERIFIED", "The actual ordinary bus front must be observed before attaching IO.")
        proof = {"required": True, "before": before, "expected": desired, "serverConfirmed": before == desired, "nativeUseSubmitted": False}
        if before == desired:
            return observed, proof
        require(type((self.health or {}).get("nativeGtWrenchFacingVersion")) is int and self.health["nativeGtWrenchFacingVersion"] == 1,
                "AE_PORT_WRENCH_UNAVAILABLE", "Wrong-facing item bus needs native checked wrench support before IO placement.")
        snapshot = await self.step("mcb_snapshot", fields=["player"])
        creative = snapshot.get("player", {}).get("creative")
        require(type(creative) is bool, "AE_PORT_ACTOR_UNVERIFIED", "Observe the real actor mode before rotating an item bus.")
        if not creative:
            require(type((self.health or {}).get("guiNativeWaitVersion")) is int and self.health["guiNativeWaitVersion"] == 1,
                    "AE_PORT_INVENTORY_SYNC_UNAVAILABLE", "Survival wrench preparation requires the existing native inventory synchronization before IO placement.")
        inventory = await self.step("mcb_inventory", fields=["slots", "cursorItem", "truncated"])
        require(inventory.get("cursorItem") is None and inventory.get("truncated") is False,
                "AE_PORT_WRENCH_UNAVAILABLE", "A complete inventory and empty cursor are required before choosing a real wrench.")
        tools = [row for row in inventory.get("slots", []) if type(row.get("index")) is int and 0 <= row["index"] <= 8
                 and isinstance(row.get("item"), dict) and row["item"].get("registryName") == "gregtech:gt.metatool.01"
                 and row["item"].get("metadata") == 16 and row["item"].get("hasTag") is True and row["item"].get("count") == 1]
        require(bool(tools), "AE_PORT_WRENCH_REQUIRED", "Place a real material GT wrench in the hotbar; no AE part was installed.")
        require(type((self.health or {}).get("approachWrenchFacingVersion")) is int and self.health["approachWrenchFacingVersion"] == 1,
                "AE_PORT_WRENCH_APPROACH_UNAVAILABLE", "Wrong-facing item IO requires the native face-centre endpoint capability before any wrench use.")
        slot = tools[0]["index"]
        await self.step("mcb_hotbar", {"slot": slot}, action=True)
        destination = {axis: attachment[axis] + VECTORS[desired][i] for i, axis in enumerate(("x", "y", "z"))}
        envelope = {bound + axis.upper(): destination[axis] for axis in ("x", "y", "z") for bound in ("min", "max")}
        await self.approach({**attachment, "wrenchFacing": True}, open_gui=False, excluded_build_envelope=envelope)
        use = await self.step("mcb_interact", {"action": "use", **{a: attachment[a] for a in ("x", "y", "z")},
            "side": desired, "sneaking": False, "wrenchFacing": True,
            "expectedHeld": {"slot": slot, "registryName": "gregtech:gt.metatool.01", "metadata": 16}}, action=True,
            fields=["accepted", "nativeGtWrenchFacingVersion", "blockTarget"])
        require(use.get("nativeGtWrenchFacingVersion") == 1 and use.get("accepted") is True,
                "AE_PORT_WRENCH_UNCONFIRMED", "The native wrench click was not confirmed; never repeat it automatically.")
        observed = await self.wait(lambda: self.world(attachment), lambda value: facing(value) == desired)
        proof.update(serverConfirmed=True, nativeUseSubmitted=True, wrenchSlot=slot, after=facing(observed))
        if not creative:
            # GT tool durability/NBT can lag the acknowledged machine facing. The
            # normal inventory window already owns one bounded native resync;
            # settle it before the placement BEFORE barrier, never after a failed
            # placement and never by repeating the wrench or placement callback.
            await self.step("mcb_open_inventory", action=True)
            gui = await self.step("mcb_gui", {"waitFor": {}}, fields=["windowId", "containerClass", "cursorItem", "containerSynchronization"])
            sync = gui.get("containerSynchronization", {})
            require(type(gui.get("windowId")) is int and gui["windowId"] == 0 and gui.get("containerClass") == "net.minecraft.inventory.ContainerPlayer"
                    and gui.get("cursorItem") is None and sync.get("fullNativeSlotsEqual") is True and sync.get("fullNbtCompared") is True,
                    "AE_PORT_INVENTORY_UNSYNCHRONIZED", "The native survival inventory did not prove all slots/full NBT after the single wrench use; no IO placement was attempted.")
            await self.step("mcb_close_gui", action=True)
            observed = await self.world(attachment)
            require(facing(observed) == desired, "AE_PORT_FACING_CHANGED", "The ordinary bus facing changed during inventory synchronization; never repeat the wrench.")
            proof["inventorySynchronized"] = True
        return observed, proof

    async def reconcile_installation(self, completed):
        """Re-read every destination; a checkpoint is never placement evidence."""
        destinations = []
        for declaration in self.args["targets"]:
            attachment = declaration["attachment"]
            vector = (0, 0, 0) if declaration.get("placement") == "host" else VECTORS[attachment["allowedSides"][0]]
            destinations.append({axis: attachment[axis] + vector[i] for i, axis in enumerate("xyz")})
        observed = {}
        unique = list({tuple(p[a] for a in "xyz"): p for p in destinations}.values())
        for offset in range(0, len(unique), 16):
            points = unique[offset:offset + 16]
            page = await self.step("mcb_world_inspect_many", {"positions": points,
                "dimension": self.args["dimension"], "compact": False, "includeAe": True},
                fields=["source", "dimension", "results"])
            require(page.get("source") == "integrated_server" and page.get("dimension") == self.args["dimension"],
                    "AE_RESUME_UNVERIFIED", "Resume requires fresh authoritative owner observations.")
            rows = page.get("results", [])
            require(len(rows) == len(points), "AE_RESUME_UNVERIFIED", "Resume observation coverage is incomplete.")
            for point, row in zip(points, rows):
                value = row.get("data", {})
                require(row.get("ok") is True and all(value.get(k) == v for k, v in point.items())
                    and value.get("dimension") == self.args["dimension"], "AE_RESUME_UNVERIFIED", "A resume destination is unloaded or changed context.")
                observed[tuple(point[a] for a in "xyz")] = value
        matched = {}
        for index, (declaration, point) in enumerate(zip(self.args["targets"], destinations)):
            value = observed[tuple(point[a] for a in "xyz")]
            part = declaration["expectedPart"]
            present = part in value.get("parts", [])
            require(index not in completed or present, "AE_RESUME_COMPLETED_CHANGED", "A previously completed target no longer matches; no missing target was installed.")
            if present:
                matched[index] = {"target": {**point, "dimension": self.args["dimension"]}, "part": part,
                    "serverConfirmed": True, "alreadyMatched": True, "completionScope": "fresh_existing_part",
                    "sourceConsumptionVerified": False}
            else:
                conflicting = any(p.get("side") == part["side"] for p in value.get("parts", []))
                require(not conflicting, "AE_RESUME_PART_CONFLICT", "A different part occupies a requested side; no target was installed.")
                require(value.get("registryName") == "minecraft:air" or isinstance(value.get("parts"), list),
                        "AE_RESUME_UNVERIFIED", "An occupied destination lacks a complete native part surface.")
        return matched

    async def installation_materials(self, declarations=None):
        """Preflight all declared native source slots before the first placement; no virtual stock."""
        snapshot = await self.step("mcb_snapshot", fields=["world", "player"])
        creative = snapshot.get("player", {}).get("creative")
        require(type(creative) is bool and snapshot.get("world", {}).get("dimension") == self.args["dimension"],
                "AE_MATERIALS_UNVERIFIED", "Current player mode and dimension are required for material preflight.")
        inventory = await self.step("mcb_inventory", fields=["slots", "totalSlots", "truncated", "cursorItem"])
        rows, total = inventory.get("slots", []), inventory.get("totalSlots")
        require(type(total) is int and 36 <= total <= 256 and inventory.get("truncated") is False
                and len(rows) == total and all(type(r.get("index")) is int and "item" in r for r in rows)
                and {r["index"] for r in rows} == set(range(total)),
                "AE_MATERIALS_UNVERIFIED", "Complete player inventory evidence is required before installing any target.")
        require("cursorItem" in inventory and inventory["cursorItem"] is None, "CURSOR_OCCUPIED", "Clear the cursor before installation.")
        slots = {r["index"]: r["item"] for r in rows}
        required = {}
        for declaration in self.args["targets"] if declarations is None else declarations:
            slot, part = declaration["hotbarSlot"], declaration["expectedPart"]
            side = declaration["attachment"]["allowedSides"][0]
            require(part["side"] in (6, side if declaration.get("placement") == "host" else side ^ 1),
                    "AE_PART_SIDE", "Mounted part side must match its declared attachment before batch installation.")
            identity = {k: part[k] for k in ("registryName", "metadata")}
            entry = required.setdefault(slot, {"hotbarSlot": slot, **identity, "placements": 0})
            require(all(entry[k] == v for k, v in identity.items()), "AE_MATERIAL_CONFLICT", "One hotbar source cannot supply different part identities in a batch.")
            entry["placements"] += 1
        materials = []
        for slot, entry in required.items():
            actual = slots[slot]
            matches = isinstance(actual, dict) and all(actual.get(k) == entry[k] for k in ("registryName", "metadata"))
            count = actual.get("count") if matches else 0
            require(type(count) is int and count >= 0, "AE_MATERIALS_UNVERIFIED", "Material count is not a known nonnegative integer.")
            needed = 1 if creative else entry["placements"]
            materials.append({**entry, "available": count, "required": needed, "missing": max(0, needed - count)})
        report = {"mode": "creative" if creative else "survival", "source": "client_inventory",
                  "materials": materials, "sufficient": all(m["missing"] == 0 for m in materials),
                  "nativePerPlacementVerification": True}
        if not report["sufficient"]:
            raise BridgeError("AE_MATERIALS_INSUFFICIENT", "Declared hotbar materials cannot supply the entire batch; no target was installed.", receipt=report)
        return report


async def run_configure(client, args, lock, control, *, install=False):
    previous = validate_install_resume(args) if install else set()
    task = AeTask(client, args, control)
    report = {"status": "running", "completed": [], "failedIndex": None, "replayAllowed": False}
    confirmed = set()
    async with lock:
        try:
            await task.initialize()
            matched = await task.reconcile_installation(previous) if install and "resume" in args else {}
            if install:
                pending = [t for i, t in enumerate(args["targets"]) if i not in matched]
                if pending:
                    report["materialPreflight"] = await task.installation_materials(pending) if "resume" in args else await task.installation_materials()
            for index, target in enumerate(args["targets"]):
                report["failedIndex"] = index
                report["completed"].append(matched[index] if index in matched else await (task.install(target) if install else task.configure(target)))
                confirmed.add(index)
            report.update(status="completed", failedIndex=None)
        except BridgeError as error:
            report.update(status="failed", error=error.as_dict())
        report["dispatchedActionAttempts"] = task.actions
        if task.hand_preparations:
            report["handPreparation"] = task.hand_preparations
    if install:
        report["checkpoint"] = install_checkpoint(args, confirmed | previous, report["failedIndex"], report.get("error"))
        report["resumeRequiresFreshObservation"] = True
        report["completionScope"] = "server_confirmed_parts"
    return report
