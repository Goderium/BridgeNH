"""Explicit reconciliation of returned creative placement, never automatic replay."""
from copy import deepcopy
import time

import anyio

from .backend_transport import fingerprint
from .bridge import BridgeError, request_context, response_context
from .construction_tasks import _context_inventory_rows
from .nbt_order import compound_order_key, context_equal
from .workflows import _guard

MARKER = "actionReconciliation"


def action_id(record):
    result = {key: value for key, value in record["result"].items() if key != MARKER}
    return fingerprint({"tool": record["tool"], "arguments": record["arguments"], "result": result})


def reconciled(record):
    value = record["result"].get(MARKER)
    return (isinstance(value, dict) and value.get("version") == 1
        and value.get("resolution") == "verified_creative_placement_reset"
        and value.get("actionId") == action_id(record)
        and isinstance(value.get("evidenceFingerprint"), str) and len(value["evidenceFingerprint"]) == 64)


def require(condition, reason):
    if not condition:
        raise BridgeError("ACTION_RECOVERY_UNCONFIRMED", reason, outcome_unknown=False)


def placement(record):
    args, result = record["arguments"], record["result"]
    data = result.get("data")
    require(record["tool"] == "mcb_place_block" and result.get("ok") is True and isinstance(data, dict),
        "Only a returned standalone placement receipt is supported; transport failures and other actions stay blocked.")
    require(data.get("source") == "client_native" and data.get("playerMode") == "creative"
        and data.get("nativeInvoked") is True and data.get("submitted") is True
        and data.get("attackSubmitted") is False and data.get("airUseFallback") is False
        and data.get("replaceRequested") is False and data.get("expectedSourceConsumption") == 0
        and data.get("sourceCountBefore") == data.get("sourceCountAfter")
        and type(data.get("sourceCountBefore")) is int and data["sourceCountBefore"] > 0,
        "Recovery requires a returned creative ItemBlock callback with no predicted source loss, replacement or air-use fallback.")
    require(not any(key in args for key in ("expectedPart", "attachmentBinding", "jumpUnderfoot"))
        and data.get("targetBefore", {}).get("registryName") == "minecraft:air"
        and isinstance(data.get("sessionId"), str) and bool(data["sessionId"])
        and type(data.get("dimension")) is int
        and isinstance(args.get("expectedWorld"), str) and bool(args["expectedWorld"])
        and type(args.get("hotbarSlot")) is int and 0 <= args["hotbarSlot"] <= 8,
        "The original receipt must identify its session, world, dimension, slot and initially empty target.")
    target, attachment = data.get("target"), data.get("attachment")
    require(isinstance(target, dict) and isinstance(attachment, dict)
        and all(type(target.get(axis)) is int and target[axis] == args.get(axis)
            and type(attachment.get(axis)) is int for axis in ("x", "y", "z"))
        and sum(abs(target[axis] - attachment[axis]) for axis in ("x", "y", "z")) == 1,
        "The original placement target and adjacent attachment are incomplete.")
    return data


def context_snapshot(value, operation, args):
    evidence = response_context(value)
    require(isinstance(evidence, dict) and evidence.get("version") == 1
        and evidence.get("operation") == operation and evidence.get("phase") == "after"
        and isinstance(evidence.get("snapshot"), dict), "A fresh native AFTER context is required for every recovery read.")
    snapshot = evidence["snapshot"]
    _guard(snapshot, args["expectedSessionId"], "developer", args["expectedWorld"], False)
    world, player, control = (snapshot.get(key, {}) for key in ("world", "player", "serverControl"))
    require(world.get("singleplayer") is True and type(world.get("dimension")) is int
        and world["dimension"] == args["dimension"] and world.get("worldFolder") == args["expectedWorld"]
        and player.get("creative") is True
        and type(player.get("health")) in (int, float) and player["health"] > 0
        and control.get("available") is True and control.get("source") == "integrated_server_tick"
        and control.get("sessionId") == args["expectedSessionId"] and control.get("dimension") == args["dimension"]
        and isinstance(control.get("playerId"), str) and bool(control["playerId"])
        and type(control.get("ageMillis")) in (int, float) and 0 <= control["ageMillis"] <= 1000,
        "Recovery requires the current healthy creative actor in the guarded local integrated world.")
    task = snapshot.get("inputTask", {})
    require(task.get("status") == "idle" and task.get("owner") == "none"
        and task.get("ownedKeyCount") == 0 and snapshot.get("inputKeys") == []
        and snapshot.get("inputTicksRemaining") == 0, "An active input task or held keys prevents reconciliation.")
    return snapshot, control["playerId"]


async def observe(client, record, args):
    original = placement(record)
    require(args["expectedWorld"] == record["arguments"]["expectedWorld"]
        and args["dimension"] == original["dimension"], "Recovery cannot transfer an action to another world or dimension.")
    credentials = client.configuration.read()
    context = {"version": 1, "expectedSessionId": args["expectedSessionId"], "expectedProfile": "developer",
        "expectedWorld": args["expectedWorld"], "requireBackground": False}
    rows, actor = [], None
    for side in ("client", "server"):
        with request_context(context):
            value = await client.call("baubles.inspect", {"source": side, "includePlayerInventory": True, "limit": 1},
                expected_credentials=credentials)
        snapshot, current_actor = context_snapshot(value, "baubles.inspect", args)
        require(actor is None or actor == current_actor, "The actor changed during recovery observation.")
        actor = current_actor
        require(value.get("supported") is True and value.get("readOnly") is True
            and value.get("source") == ("client" if side == "client" else "integrated_server")
            and value.get("sessionId") == args["expectedSessionId"] and value.get("playerId") == actor
            and value.get("dimension") == args["dimension"] and value.get("creative") is True
            and "cursorItem" in value and value["cursorItem"] is None
            and (side != "server" or value.get("worldFolder") == snapshot["world"].get("worldFolder")),
            "Complete client/server inventory ownership and an empty cursor must be confirmed.")
        rows.append(_context_inventory_rows(value.get("playerInventory")))
    require(context_equal(rows[0], rows[1]), "Client/server full-NBT inventories disagree.")
    held = rows[1][record["arguments"]["hotbarSlot"]]["item"]
    require(isinstance(held, dict) and held.get("count", 0) > 0, "The intended placement slot is empty.")
    points = [{axis: original[key][axis] for axis in ("x", "y", "z")} for key in ("target", "attachment")]
    with request_context(context):
        blocks = await client.call("world.inspect_many", {"positions": points, "dimension": args["dimension"], "compact": False},
            expected_credentials=credentials)
    _, final_actor = context_snapshot(blocks, "world.inspect_many", args)
    require(final_actor == actor and blocks.get("source") == "integrated_server"
        and blocks.get("dimension") == args["dimension"] and len(blocks.get("results", [])) == 2,
        "Recovery needs both loaded server block observations from the same actor and dimension.")
    identities = []
    for point, row in zip(points, blocks["results"]):
        data = row.get("data", {})
        require(row.get("ok") is True and data.get("source") == "integrated_server"
            and data.get("dimension") == args["dimension"] and all(data.get(axis) == point[axis] for axis in point)
            and isinstance(data.get("registryName"), str) and type(data.get("metadata")) is int,
            "A block observation is missing, unloaded or belongs to another coordinate.")
        identities.append({**point, **{key: data.get(key) for key in ("registryName", "metadata", "tileClass")}})
    require(identities[0]["registryName"] == "minecraft:air" and identities[0]["tileClass"] is None
        and identities[1]["registryName"] != "minecraft:air", "The target must be empty and its original adjacent support present.")
    # Native compound iteration order can differ across client/server copies.
    # Preserve raw evidence; normalize only compound member order for the hash.
    inventory_key = deepcopy(rows[1])
    for row in inventory_key:
        if row["item"] is not None:
            row["item"]["nbtIdentity"] = compound_order_key(row["item"]["nbtIdentity"])
    state = {"sessionId": args["expectedSessionId"], "world": args["expectedWorld"], "dimension": args["dimension"],
        "playerId": actor, "inventory": inventory_key, "blocks": identities}
    return {"evidenceFingerprint": fingerprint(state), "sessionId": args["expectedSessionId"],
        "originalSessionId": original["sessionId"], "world": args["expectedWorld"], "dimension": args["dimension"],
        "playerId": actor, "target": identities[0], "attachment": identities[1], "sourceItem": held,
        "sourceSlot": record["arguments"]["hotbarSlot"], "inventoryAgreement": "all41_full_nbt",
        "inputIdle": True, "nonAtomic": True, "originalOutcomeKnown": False,
        "scope": "Current empty target, existing support and agreed inventory permit a separately requested new placement; the historical result remains unknown."}


async def manage(worker, args):
    if args["action"] == "reconcile":
        require(args.get("acknowledgement") == "allow_new_placement_after_verified_reset",
            "Explicit acknowledgement of a separately requested new placement is required.")
    records = worker.failed_actions
    if args["action"] == "list":
        return {"actions": [{"actionId": action_id(row), "tool": row["tool"], "reconciled": reconciled(row),
            "arguments": deepcopy(row["arguments"])} for row in records], "total": len(records),
            "unclassifiedOutcome": worker.uncertain, "gameCalls": 0, "automaticReplayAllowed": False}
    record = next((row for row in records if action_id(row) == args["actionId"]), None)
    require(record is not None, "The action ID is not retained by this worker connection.")
    if reconciled(record):
        return {"status": "already_reconciled", "receipt": deepcopy(record["result"][MARKER]), "gameCalls": 0}
    try:
        async with worker.adapter.workflow_lock:
            with anyio.fail_after(10):
                evidence = await observe(worker.client, record, args)
            if args["action"] == "inspect":
                return {"status": "verified_current_reset", "actionId": args["actionId"], "evidence": evidence,
                    "nextAction": "reconcile", "guardStillActive": True, "automaticReplayAllowed": False}
            require(evidence["evidenceFingerprint"] == args["evidenceFingerprint"],
                "State changed since inspection; inspect this same action again. Its guard remains active.")
            receipt = {"version": 1, "resolution": "verified_creative_placement_reset", "actionId": args["actionId"],
                "evidenceFingerprint": evidence["evidenceFingerprint"], "sessionId": args["expectedSessionId"],
                "world": args["expectedWorld"], "dimension": args["dimension"], "verifiedAtMillis": int(time.time() * 1000),
                "originalOutcomeKnown": False, "automaticReplayAllowed": False}
            record["result"][MARKER] = receipt
            return {"status": "reconciled", "receipt": deepcopy(receipt), "evidence": evidence,
                "gameMutationDispatched": False, "newActionRequired": True}
    except TimeoutError:
        raise BridgeError("ACTION_RECOVERY_UNCONFIRMED", "Recovery reads timed out; the original guard remains active.", outcome_unknown=False) from None
