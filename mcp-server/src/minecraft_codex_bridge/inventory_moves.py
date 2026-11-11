"""Finite native player-inventory moves; no recipe or equipment policy."""
from __future__ import annotations

import time
from types import SimpleNamespace

import anyio

from .bridge import BridgeError
from .placement_tasks import integer, obj

TEXT = {"type": "string", "minLength": 1, "maxLength": 256}
MOVE = obj({"sourceIndex": integer(0, 35), "destinationIndex": integer(0, 35),
    "expectedItem": obj({"registryName": TEXT, "metadata": integer(0, 32767),
        "count": integer(1, 64), "hasTag": {"const": False}}, ("registryName", "metadata", "count", "hasTag"))},
    ("sourceIndex", "destinationIndex", "expectedItem"))
INVENTORY_MOVES_SCHEMA = obj({"expectedSessionId": TEXT, "expectedWorld": TEXT,
    "dimension": integer(-2147483648, 2147483647),
    "moves": {"type": "array", "minItems": 1, "maxItems": 16, "items": MOVE},
    "timeout_ms": {**integer(1000, 60000), "default": 30000}},
    ("expectedSessionId", "expectedWorld", "dimension", "moves"))


def validate_moves(moves):
    indices = [r[k] for r in moves for k in ("sourceIndex", "destinationIndex")]
    if len(indices) != len(set(indices)):
        raise BridgeError("INVENTORY_MOVE_CONFLICT", "All source and destination indices must be disjoint; compose dependent moves separately.")


async def run_inventory_moves(client, args, lock, control):
    from .ae_inventory import NativePlayerInventory
    from .recipe_tasks import _RecipeTask, _require, _same, _quantity, inventory_slots
    from .workflows import _guard

    validate_moves(args["moves"])
    deadline = time.monotonic() + args.get("timeout_ms", 30000) / 1000
    helper = None
    report = {"status": "running", "moves": [], "failedIndex": None, "replayAllowed": False,
        "cursorEmpty": None, "retentionConfirmed": False, "nonAtomic": True}
    try:
        with anyio.fail_after(max(.001, deadline - time.monotonic())):
            async with lock:
                credentials = client.configuration.read()
                health = await client.call("bridge.health", {}, expected_credentials=credentials)
                _require(health.get("profile") == "developer" and health.get("sessionId") == args["expectedSessionId"]
                    and health.get("readiness", {}).get("localIntegratedServer") is True,
                    "inventory_context_unavailable", "Native inventory movement needs the exact local developer session.")
                snapshot = await client.call("client.snapshot", {}, expected_credentials=credentials)
                _guard(snapshot, args["expectedSessionId"], "developer", args["expectedWorld"], False)
                _RecipeTask.require_live_player(snapshot)
                _require(snapshot.get("world", {}).get("dimension") == args["dimension"],
                    "inventory_dimension_changed", "The actual dimension must match the declared inventory context.")
                helper = NativePlayerInventory(SimpleNamespace(client=client, args=args, deadline=deadline, health=health), snapshot)
                await helper.initialize_recipe_context()
                before = await helper.call("inventory.inspect", {})
                slots = {r["index"]: r["item"] for r in inventory_slots(before, "")}
                _require(before.get("cursorItem") is None and set(slots) >= set(range(36)),
                    "inventory_move_coverage", "Every normal player slot and an empty real cursor must be observed.")
                for move in args["moves"]:
                    _require(_same(slots[move["sourceIndex"]], move["expectedItem"]) and slots[move["destinationIndex"]] is None,
                        "inventory_move_preflight", "All exact whole untagged sources and empty destinations must match before any pickup.")
                background = snapshot.get("background", {})
                if not (background.get("enabled") is True and background.get("helperInstalled") is True
                        and background.get("pauseOnLostFocus") is False):
                    await control(client, "client.background", {"enabled": True})
                screen, mapped = await helper.open_creative_player_inventory("inventory_batch")
                for move in args["moves"]:
                    source, destination = mapped.get(move["sourceIndex"]), mapped.get(move["destinationIndex"])
                    _require(source is not None and destination is not None and _same(source.get("item"), move["expectedItem"])
                        and _quantity(source["item"].get("count")) == move["expectedItem"]["count"] and destination.get("item") is None,
                        "inventory_move_mapping", "Every move needs actual current native slot bindings before the first pickup.")
                for index, move in enumerate(args["moves"]):
                    report["failedIndex"] = index
                    record = {"sourceIndex": move["sourceIndex"], "destinationIndex": move["destinationIndex"],
                        # The reused retention ledger names these historical fields; both accept any normal player index.
                        "hotbarIndex": move["sourceIndex"], "storageIndex": move["destinationIndex"],
                        "item": {k: move["expectedItem"][k] for k in ("registryName", "metadata")},
                        "count": move["expectedItem"]["count"], "movementDispatched": False,
                        "movementConfirmed": False, "status": "planned"}
                    helper.borrowed_inventory.append(record)
                    await helper.move_native_player_stack(record, screen, move["sourceIndex"], move["destinationIndex"],
                        "inventory_move_" + str(index), close_after=index == len(args["moves"]) - 1)
                    record.update(status="moved", movementConfirmed=True)
                await helper.verify_retained_inventory()
                report.update(status="completed", failedIndex=None)
    except (BridgeError, TimeoutError) as error:
        report.update(status="failed", error=error.as_dict() if isinstance(error, BridgeError) else
            {"code": "INVENTORY_MOVE_TIMEOUT", "message": "The original batch deadline expired; submitted clicks must not be replayed."})
    if helper is not None:
        report.update(moves=helper.borrowed_inventory, phases=helper.phases,
            cursorEmpty=helper.final_cursor_empty, retentionConfirmed=helper.final_inventory_verified,
            taggedContextFullNbtVerified=helper.recipe_context_inventory is not None and helper.final_inventory_verified)
    report["scope"] = "One normal inventory opening; finite whole untagged stacks into empty disjoint player slots. Actual slot mappings, per-move conservation and unrelated full-NBT context are reused. Partial/unknown receipts require inspection; no rollback, grants or replay."
    return report
