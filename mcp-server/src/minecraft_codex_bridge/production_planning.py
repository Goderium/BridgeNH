"""Exact integer accounting of caller-selected recipes, never recipe discovery."""
from collections import Counter
from math import gcd

from .bridge import BridgeError
from .conveniences import json_sha256, json_size


def check_process(plan):
    if json_size(plan) > 65536:
        raise BridgeError("PLAN_TOO_LARGE", "Process declarations are limited to 64 KiB.")
    resources = {r["id"]: r for r in plan["resources"]}
    recipes = plan["recipes"]
    identities = [json_sha256(r["identity"]) for r in resources.values()]
    if len(resources) != len(plan["resources"]) or len({r["id"] for r in recipes}) != len(recipes) \
            or len(set(identities)) != len(identities):
        raise BridgeError("INVALID_ARGUMENTS", "Resource/recipe IDs and exact resource identities must be unique.")
    issues = []
    def issue(code, **details):
        issues.append({"code": code, **details})
    producers, consumers = {key: [] for key in resources}, {key: [] for key in resources}
    groups = []
    for recipe in recipes:
        parsed = {}
        if recipe["machine"] not in plan["machines"]:
            issue("missing_machine", recipe=recipe["id"], machine=recipe["machine"])
        for group in ("inputs", "outputs", "catalysts"):
            bag = Counter()
            for row in recipe.get(group, []):
                if row["resource"] not in resources or int(row["amount"]) <= 0:
                    raise BridgeError("INVALID_ARGUMENTS", "Every recipe amount needs a declared resource and a positive exact integer.")
                bag[row["resource"]] += int(row["amount"])
            parsed[group] = dict(bag)
        if set(parsed["inputs"]) & set(parsed["catalysts"]):
            raise BridgeError("INVALID_ARGUMENTS", "One resource cannot be both a consumed input and a catalyst in the same recipe.")
        for key, amount in parsed["outputs"].items():
            producers[key].append((recipe["id"], amount))
        for key, amount in parsed["inputs"].items():
            consumers[key].append((recipe["id"], amount))
        groups.append(parsed)
    for key, resource in resources.items():
        role = resource["role"]
        if role in ("intermediate", "product") and not producers[key]:
            issue("missing_producer", resource=key)
        if role in ("feed", "intermediate", "cycle") and not consumers[key] \
                and not any(key in g["catalysts"] for g in groups):
            issue("missing_consumer", resource=key)
        if role == "external" and (producers[key] or int(resource["initial"]) or "expectedFinal" in resource):
            raise BridgeError("INVALID_ARGUMENTS", "Unlimited external inputs have zero initial stock, no producer and no finite final target.")
        if role == "product" and consumers[key]:
            issue("product_also_consumed", resource=key)
        if role == "cycle" and consumers[key] and not producers[key]:
            issue("cycle_recovery_missing", resource=key)
    reachable = {key for key, resource in resources.items() if resource["role"] == "feed"}
    if reachable:
        for _ in recipes:
            expanded = reachable | {key for group in groups if reachable.intersection(group["inputs"]) for key in group["outputs"]}
            if expanded == reachable:
                break
            reachable = expanded
        for key, resource in resources.items():
            if resource["role"] == "product" and key not in reachable:
                issue("product_disconnected_from_feed", resource=key)
    stock = {key: int(r["initial"]) for key, r in resources.items()}
    external = {key for key, r in resources.items() if r["role"] == "external"}
    supplied, used, produced = Counter(), Counter(), Counter()
    remaining = [r["batches"] for r in recipes]
    schedule, transitions = [], 0
    while any(remaining) and transitions < 16384:
        progressed = False
        for index, (recipe, group) in enumerate(zip(recipes, groups)):
            if remaining[index] == 0 or any(key not in external and stock[key] < amount
                    for key, amount in group["catalysts"].items()):
                continue
            count = min([remaining[index], *[stock[key] // amount for key, amount in group["inputs"].items() if key not in external]])
            if count == 0:
                continue
            for key, amount in group["inputs"].items():
                used[key] += count * amount
                if key in external:
                    supplied[key] += count * amount
                else:
                    stock[key] -= count * amount
            for key, amount in group["outputs"].items():
                produced[key] += count * amount
                stock[key] += count * amount
            remaining[index] -= count
            transitions += 1
            progressed = True
            if len(schedule) < 128:
                schedule.append({"recipe": recipe["id"], "batches": count})
            if transitions >= 16384:
                break
        if not progressed:
            break
    limited = any(remaining) and transitions >= 16384
    blocked = []
    for index, recipe in enumerate(recipes):
        if not remaining[index]:
            continue
        needed = Counter(groups[index]["inputs"])
        needed.update(groups[index]["catalysts"])
        blocked.append({"recipe": recipe["id"], "remainingBatches": remaining[index], "shortages": [
            {"resource": key, "available": str(stock[key]), "nextBatchRequired": str(amount), "missing": str(amount - stock[key])}
            for key, amount in needed.items() if key not in external and stock[key] < amount]})
    if blocked:
        issue("simulation_limit" if limited else "batch_or_bootstrap_shortage", recipes=[row["recipe"] for row in blocked])
    balances, multiples = [], []
    for key, resource in resources.items():
        if key not in external:
            expected = resource.get("expectedFinal")
            if expected is None and resource["role"] in ("feed", "intermediate"):
                expected = "0"
            if expected is not None and stock[key] != int(expected):
                issue("final_quantity_mismatch", resource=key, expected=expected, actual=str(stock[key]))
            balances.append({"resource": key, "role": resource["role"], "initial": resource["initial"],
                "consumed": str(used[key]), "produced": str(produced[key]), "final": str(stock[key]),
                "expectedFinal": expected})
        if resource["role"] == "intermediate" and len(producers[key]) == len(consumers[key]) == 1:
            (producer, output), (consumer, needed) = producers[key][0], consumers[key][0]
            common = gcd(output, needed)
            multiples.append({"resource": key, "producer": producer, "producerBatches": needed // common,
                "consumer": consumer, "consumerBatches": output // common, "quantity": str(output * needed // common)})
    return {"status": "completed", "checkKind": "process", "planValid": not issues,
        "readiness": "unknown" if limited else "blocked" if issues else "passed",
        "planFingerprint": json_sha256(plan), "issues": issues, "balances": balances,
        "recipeBatches": [{"recipe": r["id"], "requested": r["batches"], "executable": r["batches"] - remaining[i]} for i, r in enumerate(recipes)],
        "externalInputs": [{"resource": key, "consumed": str(supplied[key])} for key in sorted(external)],
        "blocked": blocked, "pairwiseBatchMultiples": multiples,
        "schedule": schedule, "scheduleTruncated": transitions > len(schedule), "transitions": transitions,
        "completionScope": "declared_deterministic_recipe_accounting", "productionVerified": False,
        "gameCalls": 0, "automaticReplayAllowed": False,
        "limitations": ["Recipe identities, quantities and source completeness are caller declarations, not newly verified recipes.",
            "Bounded greedy execution uses declared recipe order and quotas; failure is not proof no alternative schedule exists.",
            "Chance outputs, alternative inputs and hidden machine costs must be resolved before declaring a deterministic plan.",
            "Cycle stock is allowed to remain; finite feed/intermediate stock defaults to zero final. Historical inventory is not a production delta.",
            "Pairwise batch multiples are local constraints, not a global minimum feed size. Live transport, energy and recipe conditions require site/production checks."]}
