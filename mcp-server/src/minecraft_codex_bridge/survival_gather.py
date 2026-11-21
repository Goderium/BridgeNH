"""One bounded survivor gathering step. Credit native pickups and the actor's real inventory."""
from __future__ import annotations

import math

import anyio

from .catalog.common import POSITION, REGISTRY_NAME, integer, obj


GATHER_POSITION = {**POSITION, "x": integer(-30_000_000, 29_999_999), "z": integer(-30_000_000, 29_999_999)}
BOUNDS = obj({prefix + axis.upper(): GATHER_POSITION[axis] for prefix in ("min", "max") for axis in "xyz"},
             [prefix + axis.upper() for prefix in ("min", "max") for axis in "xyz"])
GATHER_SCHEMA = {**obj({
    "bounds": BOUNDS,
    "block": obj({"registryName": REGISTRY_NAME, "metadata": integer(0, 15), "hotbarSlot": integer(0, 8)},
                 ["registryName", "metadata", "hotbarSlot"]),
    "blockTargets": {"type": "array", "items": obj(GATHER_POSITION, list("xyz")), "minItems": 1,
                     "maxItems": 32, "uniqueItems": True},
}, ["bounds"]), "dependentRequired": {"blockTargets": ["block"]}}


def _stop(reason, **extra):
    return {"gathered": 0, "reason": reason, "blocked": True, **extra}


def _uncertain(reason):
    return {"gathered": 0, "reason": reason, "uncertain": True}


def _head(order):
    return {k: order[k] for k in ("expectedSessionId", "expectedWorld", "dimension")}


def _inside(pos, bounds):
    return all(bounds["min"+a.upper()] <= pos[a] < bounds["max"+a.upper()] + 1 for a in "xyz")


def _bad_region(cfg):
    bounds = cfg["bounds"]
    return any(not low <= bounds["min"+a] <= bounds["max"+a] <= high or bounds["max"+a]-bounds["min"+a] > 31
               for a, low, high in (("X", -30_000_000, 29_999_999), ("Y", 0, 255), ("Z", -30_000_000, 29_999_999))) or any(
        not _inside(pos, bounds) for pos in cfg.get("blockTargets", ()))


def validate_gather_region(cfg):
    from .bridge import BridgeError
    if _bad_region(cfg):
        raise BridgeError("invalid_gather_region", "Gather bounds must be ordered, at most 32 blocks per axis, and contain every target.",
                          outcome_unknown=False)


async def _observe(client, order, baseline=None):
    from .bridge import BridgeError
    args = {**_head(order), "bounds": order["gather"]["bounds"], "item": order["item"], "observerId": order["observerId"]}
    health = await client.invoke("bridge.health", {})
    remote = order["identity"].get("server") is not None
    if remote:
        connection = (health.get("serverBridge") or {}).get("connectionId")
        if not isinstance(connection, str) or not connection:
            raise BridgeError("gather_connection_unavailable", "The bound server did not identify its live player connection.")
        args["expectedConnectionId"] = connection
    if baseline:
        args.update(epoch=baseline["epoch"], after=baseline["cursor"])
    row = await client.invoke("survival.gather_observe", args)
    if (row.get("sessionId") != order["expectedSessionId"] or row.get("playerUuid") != order["identity"]["actor"]
            or row.get("dimension") != order["dimension"] or row.get("source") != "server_player_pickups"
            or (remote and row.get("connectionId") != args["expectedConnectionId"])
            or type(row.get("complete")) is not bool or not isinstance(row.get("epoch"), str)
            or not all(type(row.get(k)) is int and row[k] >= 0 for k in ("cursor", "inventoryCount", "freeSlots", "stackSpace"))
            or not isinstance(row.get("drops"), list) or type(row.get("completeRegion")) is not bool
            or (row["complete"] and (type(row.get("pickedUp")) is not int or row["pickedUp"] < 0))):
        raise BridgeError("gather_observation_invalid", "A complete authenticated own-player pickup observation is required.")
    return row


def _baseline(row):
    return {k: row[k] for k in ("epoch", "cursor", "inventoryCount")}


async def _snapshot(client, order):
    from .bridge import BridgeError
    snap = await client.invoke("client.snapshot", {})
    if (snap.get("sessionId") != order["expectedSessionId"] or (snap.get("lifecycleTarget") or {}).get("expectedWorld") != order["expectedWorld"]
            or (snap.get("world") or {}).get("dimension") != order["dimension"]
            or (snap.get("player") or {}).get("uuid") != order["identity"]["actor"]
            or snap["player"].get("creative") is not False or (snap["player"].get("health") or 0) <= 0):
        raise BridgeError("gather_context_changed", "The living survival actor or its world changed.")
    if snap.get("screenOpen") is not False:
        raise BridgeError("gather_gui_open", "The gather action requires a closed game screen.")
    return snap


async def _action(client, order, snap, operation, args):
    from .application.control import invoke_control
    from .bridge import request_context
    if operation == "player.navigate":
        # The remote navigation operation carries its own session, connection and dimension guards.
        nav = {**_head(order), **args, "expectedWorld": order["identity"]["folder"],
               "requireSurvivalActor": True, "strategy": "walking"}
        if order["identity"].get("server") is not None:
            connection = (snap.get("serverBridge") or {}).get("connectionId")
            if not connection:
                from .bridge import BridgeError
                raise BridgeError("gather_connection_unavailable", "A current server connection is required.")
            nav["expectedConnectionId"] = connection
        return await invoke_control(client, operation, nav)
    guard = {"version": 1, "expectedSessionId": order["expectedSessionId"], "expectedWorld": order["expectedWorld"],
             "expectedProfile": snap["profile"], "expectedDimension": order["dimension"],
             "requireBackground": True, "requireIdleInput": True}
    with request_context(guard):
        return await invoke_control(client, operation, args)


def _position(p):
    return tuple(p[a] for a in "xyz")


def _distance(player, point):
    return math.dist((player["x"], player.get("feetY", player["y"]), player["z"]), _position(point))


def _aimed(snap, target):
    hit = snap.get("target") or {}
    return str(hit.get("type", "")).upper() == "BLOCK" and all(hit.get(a) == target[a] for a in "xyz")


def _tiles(bounds):
    # One 8×4×8 subregion fits the ordinary 256-row page, even when every cell is solid.
    sizes = (8, 4, 8)
    axes = [range(bounds["min"+a], bounds["max"+a]+1, size) for a, size in zip("XYZ", sizes)]
    for y in axes[1]:
        for z in axes[2]:
            for x in axes[0]:
                box = {}
                for axis, start, size in zip("XYZ", (x,y,z), sizes):
                    box["min"+axis] = start
                    box["max"+axis] = min(bounds["max"+axis], start+size-1)
                yield box


async def _block_candidates(client, order, state):
    from .bridge import BridgeError
    cfg = order["gather"]
    if "blockTargets" in cfg:
        return [v for v in cfg["blockTargets"] if list(_position(v)) not in state.get("skippedBlocks", [])], True, None
    search = dict(state.get("scan") or {"index": 0, "partial": False, "candidates": []})
    tiles = list(_tiles(cfg["bounds"]))
    if search.get("candidates"):
        return search["candidates"], True, search
    for _ in range(8):
        if search["index"] >= len(tiles):
            break
        box = tiles[search["index"]]
        args = {"cursor": search["cursor"], "limit": 256} if search.get("cursor") else {
                "dimension": order["dimension"], "bounds": box, "limit": 256}
        try:
            page = await client.invoke("world.scan", args)
        except BridgeError as exc:
            if args.get("cursor") and exc.code.lower() == "stale_cursor":
                search.pop("cursor", None)
                continue
            raise
        found = [{a: row[a] for a in "xyz"} for row in page.get("blocks", [])
                 if row.get("registryName") == cfg["block"]["registryName"]
                 and row.get("metadata") == cfg["block"]["metadata"] and _inside(row, box)
                 and list(_position(row)) not in search.get("attempted", [])]
        if page.get("complete") is True:
            search["index"] += 1
            search["partial"] |= page.get("requestedRegionFullyObserved") is not True
            search.pop("cursor", None)
            search.pop("attempted", None)
        elif isinstance(page.get("nextCursor"), str) and page["nextCursor"]:
            search["cursor"] = page["nextCursor"]
        else:
            raise BridgeError("gather_scan_incomplete", "The scan page had neither a completed region nor a continuation.")
        if found:
            search["candidates"] = found
            return found, True, search
    return [], search["index"] >= len(tiles) and not search["partial"], search


def _account(before, after):
    if not after["complete"] or before["epoch"] != after["epoch"] or after["cursor"] < before["cursor"]:
        return _uncertain("pickup_history_incomplete")
    gained = after["pickedUp"]
    if after["inventoryCount"] - before["inventoryCount"] < gained:
        return _uncertain("pickup_inventory_diverged")
    return {"gathered": gained, "reason": "confirmed_player_pickup" if gained else "no_confirmed_pickup",
            "attribution": "own_server_pickups_of_exact_item_in_area", "inventoryAfter": after["inventoryCount"]}


async def _mine(client, order, snap, target, baseline):
    from .bridge import BridgeError
    block = order["gather"]["block"]
    pos = {a: target[a] for a in "xyz"}
    if not _aimed(snap, target):
        center = {a: target[a] + .5 for a in "xyz"}
        if _distance(snap["player"], center) > 3.5:
            route = await _action(client, order, snap, "player.navigate", {"goal": {"type": "interaction",
                    "targets": [{**pos, "targetIdentity": {k: block[k] for k in ("registryName", "metadata")}}]}})
            if route.get("status") not in ("arrived", "completed") and route.get("arrivalConfirmed") is not True:
                return "unreachable"
            snap = await _snapshot(client, order)
        if not _aimed(snap, target):
            p = snap["player"]
            dx, dz = center["x"]-p["x"], center["z"]-p["z"]
            dy = center["y"] - p["eyeY"]
            await _action(client, order, snap, "player.look", {"yaw": math.degrees(math.atan2(-dx, dz)),
                          "pitch": -math.degrees(math.atan2(dy, math.hypot(dx,dz)))})
            snap = await _snapshot(client, order)
            if not _aimed(snap, target):
                return "block_not_in_crosshair"
    # Moving into reach may have collected the full outstanding quantity as a side effect.
    midway = _account(baseline, await _observe(client, order, baseline))
    if midway.get("uncertain") or midway["gathered"] >= order["quantity"] - order["gathered"]:
        return "pickup_recheck"
    await _action(client, order, snap, "player.hotbar", {"slot": block["hotbarSlot"]})
    reply = await _action(client, order, snap, "player.mine", {**_head(order), **pos, **block, "ticks": 100})
    task = reply.get("taskId")
    if not isinstance(task, str) or not task:
        raise BridgeError("mining_receipt_unknown", "The scheduled mining task was not identified.", outcome_unknown=True)
    with anyio.move_on_after(7):
        while True:
            status = await client.invoke("player.task", {"action": "status", "expectedSessionId": order["expectedSessionId"]})
            done = status.get("lastMining") or {}
            if done.get("taskId") == task and done.get("status") == "stopped":
                return "mining_" + str(done.get("reason", "stopped"))
            await anyio.sleep(.2)
    raise BridgeError("mining_completion_unknown", "Inspect the same mining task before continuing.", outcome_unknown=True)


_SKIP_REASONS = {"unreachable", "route_unavailable", "target_unreachable", "no_path", "path_blocked",
                 "target_unverified", "face_not_visible", "block_not_in_crosshair", "pickup_not_observed",
                 "mining_crosshair_or_reach_changed", "mining_tick_limit"}


async def _finish(client, order, intent, reason, *, recovery=False):
    after = await _observe(client, order, intent["baseline"])
    result = _account(intent["baseline"], after)
    if result.get("uncertain"):
        return result
    if recovery or reason.lower() in _SKIP_REASONS:
        # A positive delta proves only that some pickups occurred. It cannot prove the old mining or
        # movement task finished. Never dispatch another action from this recovery path.
        snap = await _snapshot(client, order)
        if (snap.get("inputTask") or {}).get("status") != "idle":
            return _uncertain("gather_input_still_active")
    state = dict(order.get("gatherState") or {})
    if "scan" in intent:
        state["scan"] = intent["scan"]
    state.pop("pickupCursor", None)
    state.update(lastTarget=intent["target"], action=intent["action"], reason=reason)
    if recovery:
        if not result["gathered"]:
            return _uncertain("gather_effect_unconfirmed")
        result.update(blocked=True, reason="pickup_reconciled_action_unverified", state=state)
        return result
    changed = reason == "mining_block_changed_on_client"
    skippable = reason.lower() in _SKIP_REASONS
    if (changed or skippable) and not (intent["action"] == "mine" and "scan" in intent):
        key = "skippedDrops" if intent["action"] == "pickup" else "skippedBlocks"
        value = intent["target"]["entityId"] if key == "skippedDrops" else list(_position(intent["target"]))
        skipped = list(state.get(key, []))
        if value not in skipped:
            skipped.append(value)
        if len(skipped) > 64:
            result.update(reason="gather_candidate_limit", blocked=True, state=state)
            return result
        state[key] = skipped
    if changed:
        state["pickupCursor"] = _baseline(after)
    if result["gathered"] == 0:
        result["reason"] = reason
        if not changed and not skippable and reason != "pickup_recheck":
            result["blocked"] = True
    result["state"] = state
    return result


async def reconcile_gather(client, order):
    """Re-read a durable intent, never reissue a move, slot selection or mining action."""
    from .bridge import BridgeError
    try:
        return await _finish(client, order, order["pending"], "gather_effect_unconfirmed", recovery=True)
    except BridgeError as exc:
        return _uncertain(exc.code)


async def gather_step(client, order, checkpoint):
    """One target per step, with the own-player ledger checkpointed before movement or mining."""
    from .bridge import BridgeError
    cfg = order["gather"]
    if _bad_region(cfg):
        return _stop("invalid_gather_region")
    snap = await _snapshot(client, order)
    state = order.get("gatherState") or {}
    previous = state.get("pickupCursor") if (order.get("lastStep") or {}).get("kind") == "gather" else None
    if previous:
        # A just-mined drop can enter the inventory naturally while the worker returns its receipt.
        # Another work order changes the server observer epoch. Delivery invalidates this cursor.
        before = await _observe(client, order, previous)
        late = _account(previous, before)
        if late.get("uncertain") or late["gathered"]:
            checkpoint({"action": "settle", "target": state["lastTarget"], "baseline": previous})
            if not late.get("uncertain"):
                late["state"] = {k: v for k, v in state.items() if k != "pickupCursor"}
            return late
    else:
        before = await _observe(client, order)
    if not before["freeSlots"] and not before["stackSpace"]:
        return _stop("inventory_full")
    drops = [row for row in before["drops"] if row["entityId"] not in state.get("skippedDrops", [])]
    if drops:
        target = min(drops, key=lambda v: _distance(snap["player"], v))
        action = "pickup"
        scan = None
    elif "block" in cfg:
        targets, complete, scan = await _block_candidates(client, order, state)
        if not targets:
            if scan and scan["index"] < len(list(_tiles(cfg["bounds"]))):
                return {"gathered": 0, "reason": "gather_scanning", "state": {**state, "scan": scan}}
            reason = "unreachable_or_exhausted" if state.get("skippedBlocks") or before["drops"] else "no_matching_resource"
            return _stop(reason if complete and before["completeRegion"] else "gather_coverage_partial")
        target = min(targets, key=lambda v: _distance(snap["player"], v))
        action = "mine"
        if scan is not None:
            scan["candidates"] = [v for v in targets if v != target]
            if scan.get("cursor"):
                scan["attempted"] = [*scan.get("attempted", []), list(_position(target))]
    else:
        reason = "unreachable" if before["drops"] else "no_matching_drops"
        return _stop(reason if before["completeRegion"] else "gather_coverage_partial")
    intent = {"action": action, "target": target, "baseline": _baseline(before)}
    if scan is not None:
        intent["scan"] = scan
    checkpoint(intent)
    try:
        if action == "mine":
            reason = await _mine(client, order, snap, target, intent["baseline"])
        else:
            if _distance(snap["player"], target) > .8:
                await _action(client, order, snap, "player.navigate", {
                    "x": target["x"], "y": math.floor(target["y"]), "z": target["z"]})
            # Vanilla pickup delay and inventory insertion are independent of the navigation receipt.
            reason = "pickup_not_observed"
            with anyio.move_on_after(2):
                while True:
                    observed = await _observe(client, order, intent["baseline"])
                    if not observed["complete"] or observed["pickedUp"] > 0:
                        break
                    await anyio.sleep(.25)
        return await _finish(client, order, intent, reason)
    except BridgeError as exc:
        if exc.details.get("outcome_unknown") or exc.code.lower() in {"timeout", "connection_failed", "manual_override"}:
            return _uncertain(exc.code)
        try:
            return await _finish(client, order, intent, exc.code)
        except BridgeError:
            return _uncertain(exc.code)
