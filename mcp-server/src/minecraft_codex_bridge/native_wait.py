"""Fixed public owner predicates, native END-tick waits, and fresh complete AFTER receipts."""

from copy import deepcopy
import math

from .bridge import BridgeError


def _obj(properties, required):
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}


def _int(low, high):
    return {"type": "integer", "minimum": low, "maximum": high}


_TEXT = {"type": "string", "minLength": 1, "maxLength": 256}
_OWNER = _obj({"registryName": _TEXT, "metadata": _int(0, 65_535), "tileClass": _TEXT,
    "metaTileId": _int(0, 32_767), "metaClass": _TEXT}, ["registryName", "metadata", "tileClass"])
_OWNER["dependentRequired"] = {"metaTileId": ["metaClass"], "metaClass": ["metaTileId"]}
_OWNER["allOf"] = [{"if": {"properties": {"registryName": {"const": "gregtech:gt.blockmachines"}}},
    "then": {"required": ["metaTileId", "metaClass"]}}]
_ITEM = _obj({"registryName": _TEXT, "metadata": _int(0, 32_767)}, ["registryName", "metadata"])
NATIVE_WAIT_SCHEMA = _obj({"version": {"const": 1, "type": "integer"}, "timeoutMillis": _int(1, 6_500),
    "owners": {"type": "array", "minItems": 1, "maxItems": 16, "items": _OWNER},
    "conditions": {"type": "array", "minItems": 1, "maxItems": 16, "items": {"oneOf": [
        _obj({"owner": _int(0, 15), "kind": {"const": "item_delta"}, "item": _ITEM,
            "baseline": _int(0, 9_223_372_036_854_775_807), "delta": _int(1, 2_147_483_647)}, ["owner", "kind", "item", "baseline", "delta"]),
        _obj({"owner": _int(0, 15), "kind": {"const": "state_equal"}, "fields": {
            **_obj({"active": {"type": "boolean"}, "allowedToWork": {"type": "boolean"}}, []), "minProperties": 1}}, ["owner", "kind", "fields"]),
        _obj({"owner": _int(0, 15), "kind": {"const": "fluid_match"}, "tankKind": {"const": "forge"},
            "tankIndex": _int(0, 31), "fluid": _TEXT, "amount": _int(1, 2_147_483_647)}, ["owner", "kind", "tankKind", "tankIndex", "fluid", "amount"]),
        _obj({"owner": _int(0, 15), "kind": {"const": "fluid_match"}, "tankKind": {"const": "named_output"},
            "fluid": _TEXT, "amount": _int(1, 2_147_483_647)}, ["owner", "kind", "tankKind", "fluid", "amount"]),
    ]}}}, ["version", "timeoutMillis", "owners", "conditions"])


def _require(condition, message):
    if not condition:
        raise BridgeError("NATIVE_WAIT_RECEIPT_UNAVAILABLE", message)


def _owner(value):
    _require(isinstance(value, dict) and value.get("source") == "integrated_server"
        and all(type(value.get(key)) is int for key in ("x", "y", "z", "dimension", "metadata"))
        and 0 <= value["metadata"] <= 65_535 and all(isinstance(value.get(key), str) and value[key]
            for key in ("registryName", "tileClass")), "Every actual owner needs its exact typed world, block and tile identity.")
    result = {key: value[key] for key in ("registryName", "metadata", "tileClass")}
    gt = value.get("gregtech")
    _require(value["registryName"] != "gregtech:gt.blockmachines" or isinstance(gt, dict), "Actual GT owner identity is mandatory.")
    if gt is not None:
        _require(isinstance(gt, dict) and type(gt.get("metaTileId")) is int and 0 <= gt["metaTileId"] <= 32_767
            and isinstance(gt.get("metaClass"), str) and gt["metaClass"], "Actual metaTileId and metaClass remain separate from world metadata.")
        result.update({key: gt[key] for key in ("metaTileId", "metaClass")})
    return result


def _coverage(value):
    from .workflows import complete_item_count
    if "inventory" in value:
        complete_item_count(value, "inventory.slots", {"item.registryName": "__no_matching_native_wait_item__"})
        _require(type(value["inventory"].get("totalSlots")) is int and 0 <= value["inventory"]["totalSlots"] <= 256,
            "The full public inventory must remain within its finite owner limit.")
        for index, slot in enumerate(value["inventory"]["slots"]):
            _require(type(slot.get("index")) is int and slot["index"] == index, "Every complete actual inventory slot index is required.")
            item = slot["item"]
            _require(item is None or isinstance(item, dict) and isinstance(item.get("registryName"), str) and item["registryName"]
                and type(item.get("metadata")) is int and type(item.get("count")) is int and 0 <= item["count"] <= 2_147_483_647
                and type(item.get("hasTag")) is bool, "Every full actual inventory stack must be typed.")
    if "fluids" in value:
        fluids = value["fluids"]
        _require(isinstance(fluids, dict) and fluids.get("truncated") is False and type(fluids.get("totalTanks")) is int
            and 0 <= fluids["totalTanks"] <= 32 and isinstance(fluids.get("tanks"), list)
            and len(fluids["tanks"]) == fluids["totalTanks"], "Every observed owner retains complete tank coverage.")
        for index, tank in enumerate(fluids["tanks"]):
            _require(isinstance(tank, dict) and type(tank.get("index")) is int and tank["index"] == index
                and type(tank.get("capacity")) is int and 0 <= tank["capacity"] <= 2_147_483_647
                and type(tank.get("amount", 0)) is int and 0 <= tank.get("amount", 0) <= tank["capacity"]
                and (tank.get("amount", 0) == 0 and tank.get("fluid") is None
                    or tank.get("amount", 0) > 0 and isinstance(tank.get("fluid"), str) and tank["fluid"]),
                "Every actual tank must retain exact index, capacity and internally consistent content.")
        named = fluids.get("namedTanks")
        if isinstance(named, dict) and named.get("available") is True:
            from .fluid_recipe_tasks import named_tanks
            named_tanks(value, {key: value[key] for key in ("x", "y", "z", "dimension")})


def predicates_match(owners, conditions, previous=None):
    from .workflows import condition_matches, complete_item_count
    from .recipe_tasks import _match
    from .fluid_recipe_tasks import named_tanks
    matched = True
    current = {}
    for ordinal, condition in enumerate(conditions):
        value = owners[condition["owner"]]
        if condition["kind"] == "item_delta":
            matched &= condition_matches(value, {"operator": "item_count_delta", "path": "inventory.slots",
                "match": _match(condition["item"]), "_baselineCount": condition["baseline"], "value": condition["delta"]})
            current[ordinal] = int(complete_item_count(value, "inventory.slots", _match(condition["item"]))) - condition["baseline"]
        elif condition["kind"] == "state_equal":
            gt = value.get("gregtech")
            _require(isinstance(gt, dict) and all(type(gt.get(key)) is bool for key in condition["fields"]), "Actual public state booleans are required.")
            matched &= all(gt[key] is expected for key, expected in condition["fields"].items())
        else:
            point = {key: value[key] for key in ("x", "y", "z", "dimension")}
            if condition["tankKind"] == "named_output":
                tank = named_tanks(value, point)["output"]
            else:
                tanks = value["fluids"]["tanks"]
                _require(condition["tankIndex"] < len(tanks), "The actual complete public tank index must be present.")
                raw = tanks[condition["tankIndex"]]
                tank = {"amount": raw.get("amount", 0), "fluid": raw.get("fluid")}
            _require(tank["amount"] <= condition["amount"] and (tank["amount"] == 0 or tank["fluid"] == condition["fluid"]),
                "The complete fluid quantity must not exceed or change the exact declared output.")
            matched &= (tank["fluid"], tank["amount"]) == (condition["fluid"], condition["amount"])
            current[ordinal] = tank["amount"]
    if previous is not None:
        _require(all(quantity >= previous.get(ordinal, 0) for ordinal, quantity in current.items()),
            "A finite output decreased between complete native read windows; later actions are refused.")
        previous.update(current)
    return bool(matched)


def validate(value, arguments, previous=None):
    wait = arguments["nativeWait"]
    _require(all(type(row.get("owner")) is int and 0 <= row["owner"] < len(wait["owners"]) for row in wait["conditions"]),
        "Each fixed predicate must address one actual requested owner.")
    rows = value.get("results")
    _require(isinstance(rows, list) and len(rows) == len(arguments["positions"]) == len(wait["owners"]), "Every requested native owner must be fully returned.")
    owners = []
    for row, point, expected in zip(rows, arguments["positions"], wait["owners"]):
        _require(isinstance(row, dict) and row.get("ok") is True and isinstance(row.get("data"), dict), "Unknown or partial owner rows are unavailable.")
        owner = row["data"]
        _require(all(type(owner.get(key)) is int and owner[key] == expected_value for key, expected_value in point.items())
            and type(owner.get("dimension")) is int and owner["dimension"] == arguments["dimension"] and _owner(owner) == expected,
            "The final full native owner must retain its exact requested actual identity.")
        _coverage(owner); owners.append(owner)
    receipt = value.get("nativeWait")
    _require(isinstance(receipt, dict) and type(receipt.get("version")) is int and receipt["version"] == 1
        and receipt.get("source") == "normal_server_end_ticks" and receipt.get("readOnly") is True
        and receipt.get("submitted") is False and receipt.get("nonAtomic") is True and receipt.get("finalOwnersFresh") is True
        and receipt.get("quantitiesMonotonic") is True
        and receipt.get("partial") is False and type(receipt.get("matched")) is bool and type(receipt.get("timedOut")) is bool
        and type(receipt.get("attempts")) is int and 0 <= receipt["attempts"] <= 1_000
        and all(type(receipt.get(key)) is int and receipt[key] >= -1 for key in ("firstWorldTick", "lastWorldTick"))
        and (receipt["attempts"] == 0 and receipt["firstWorldTick"] == receipt["lastWorldTick"] == -1
            or receipt["attempts"] > 0 and 0 <= receipt["firstWorldTick"] <= receipt["lastWorldTick"])
        and type(receipt.get("elapsedMillis")) in (int, float) and math.isfinite(receipt["elapsedMillis"])
        and 0 <= receipt["elapsedMillis"] <= 8_000 and type(receipt.get("tickWorkBudgetNanos")) is int
        and receipt["tickWorkBudgetNanos"] == 4_000_000 and receipt.get("callbackPreemptible") is False,
        "A typed bounded complete native wait receipt is required; partial or late work never permits continuation.")
    ready = predicates_match(owners, wait["conditions"], previous)
    _require(not receipt["matched"] or ready and receipt["attempts"] > 0 and not receipt["timedOut"],
        "Native readiness must be backed by all exact predicates on the final fresh complete owners.")
    return receipt


async def prepare(task, expected_output, baseline_phase):
    from .workflows import _inspect_many_player_inventory_available
    health = task.approach_health or {}
    if task._workflow_discovery is not None:
        health = task._workflow_discovery.bind(task.tracked_client, task.credentials, task.args["expectedSessionId"])
    if type(health.get("nativeObservationWaitVersion")) is not int or health["nativeObservationWaitVersion"] != 1 \
            or not _inspect_many_player_inventory_available(health):
        return None
    points = [task.position("machine"), task.position("input"), task.position("output"),
        *[task.fluid_resources.position(row) for row in task.fluid_resources.rows]]
    unique = list({tuple(point[key] for key in ("x", "y", "z")): point for point in points}.values())
    if len(unique) > 16 or len(task.recipe.output_items) + sum(row["direction"] == "output" for row in task.fluid_resources.rows) + 1 > 16:
        return None
    phase = await task.read_owner_phase(unique)
    _require(phase is not None, "An advertised native wait requires its fresh full owner/player binding read.")
    values, _ = phase
    lookup = {tuple(point[key] for key in ("x", "y", "z")): index for index, point in enumerate(unique)}
    index = lambda point: lookup[tuple(point[key] for key in ("x", "y", "z"))]
    for point, value in zip(unique, values):
        _require(all(type(value.get(key)) is int and value[key] == number for key, number in point.items()), "Native preparation owner coordinates differ.")
        _owner(value); _coverage(value)
    output = values[index(task.position("output"))]
    task.validate_multi_output_baseline(output, expected_output, baseline_phase)
    task.bind_input_inventory_owner(values[index(task.position("input"))])
    machine = values[index(task.position("machine"))].get("gregtech", {})
    _require(type(machine.get("metaTileId")) is int and machine["metaTileId"] == task.machine_id
        and machine.get("metaClass") == task.machine_class, "The actual controller must keep its exact registered identity.")
    for row in task.fluid_resources.rows:
        task.fluid_resources.validate_observation(row, values[index(task.fluid_resources.position(row))], row.get("expectedTank"))
    return {"positions": [{key: point[key] for key in ("x", "y", "z")} for point in unique], "dimension": task.dimension,
        "compact": False, "includePlayerInventory": True, "playerInventoryByteBudget": 131_072, "observationByteBudget": 1_048_576,
        "nativeWait": {"version": 1, "timeoutMillis": 6_500, "owners": [_owner(value) for value in values], "conditions": []},
        "_index": index}, output


def step(plan, identifier, conditions):
    arguments = deepcopy({key: value for key, value in plan.items() if key != "_index"})
    arguments["nativeWait"]["conditions"] = conditions
    return {"id": identifier, "kind": "wait", "tool": "mcb_world_inspect_many", "args": arguments,
        "condition": {"path": "nativeWait.matched", "operator": "equals", "value": True}, "interval_ms": 50, "fields": ["nativeWait"]}


def product_step(task, plan, baseline):
    from .recipe_tasks import _match
    from .workflows import complete_item_count
    owner = plan["_index"](task.position("output"))
    conditions = [{"owner": owner, "kind": "item_delta", "item": item,
        "baseline": int(complete_item_count(baseline, "inventory.slots", _match(item))), "delta": count}
        for item, count in task.recipe.output_items]
    conditions.extend(task.fluid_resources.native_output_conditions(plan["_index"]))
    return step(plan, "native_products_wait", conditions)


def stop_step(task, plan, baseline):
    conditions = product_step(task, plan, baseline)["args"]["nativeWait"]["conditions"]
    conditions.append({"owner": plan["_index"](task.position("machine")), "kind": "state_equal",
        "fields": {"allowedToWork": False, "active": False}})
    return step(plan, "native_stopped_wait", conditions)


def metrics(report):
    metrics = [{"step": row["id"], "httpWindows": row.get("nativeWaitWindows", 0), "serverAttempts": row.get("nativeWaitAttempts", 0),
        "nativeElapsedMillis": row.get("nativeWaitElapsedMillis", 0), "elapsed_ms": row.get("elapsed_ms", 0)}
        for row in report.get("steps", []) if row["id"] in ("native_products_wait", "native_stopped_wait")]
    return metrics
