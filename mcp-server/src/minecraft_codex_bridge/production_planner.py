"""Finite material accounting over a bound NEI catalog and a pinned Factory Flow process."""

from __future__ import annotations

from collections import Counter, defaultdict
from contextlib import closing
from copy import deepcopy
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import time

from jsonschema import Draft202012Validator

from .bridge import BridgeError
from .catalog.planner import PRODUCTION_PLAN_SCHEMA
from .packdata_inputs import last_checks
from .planner_batches import RouteLimit, schedule_returns

ALU_COMMIT = "2abfae1a423f407f36cd9f73c0ede81a01e9a706"
FACTORY_FLOW = "e493e36a64d5a226e6110fab8a765ae08c3bf7c5"
PLANNER_MOD = "69dd09fe21117d7b8558625dc9556723873942ca"
ASSETS = {
    "dist/runtime.cjs": "5965e880c2992db755822902e9751efb81d12fd319e791aa1dafd82fd72ef025",
    "dist/highs.wasm": "7e6432b2b26f4fab9f6d9bac55da43307c7a4b1b071cb204cb4d23e1901bc4d0",
    "mod/handlers.json": "342116a1c83c58266bc5bfcab995792477165e7c03594724612138541302189e",
}
TIERS = ["ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV"]
# MetaTileEntityIDs at GT5-Unofficial bb00f3f1023e20d3cce84cf09abb442d22a3a5a5.
# Numbers in each range are contiguous LV-IV and LuV-UMV variants respectively.
# Both the instance's handler and the pinned Planner Mod catalog must independently list the item.
GT_SERIES = {
    "alloysmelter": (201, 10760),
    "autoclave": (571, 10790),
    "chemicalreactor": (421, 11150),
    "metalbender": (221, 10800),
    "extruder": (281, 10880),
    "fluidextractor": (511, 11190),
    "fluidsolidifier": (521, 10890),
    "wiremill": (351, 11050),
    "assembler": (211, None),
    "cutter": (251, 10820),
    "lathe": (291, 10920),
    "mixer": (581, 11210),
    "compressor": (241, 10810),
    "macerator": (301, 10940),
    "hammer": (611, 10910),
    "formingpress": (601, 10900),
}


def _matches_machine_tier(machine):
    name = machine["handler"].removeprefix("gt.recipe.")
    idx = TIERS.index(machine["tier"])
    if name == "circuitassembler" and idx:
        meta = 1180 + idx - 1 if idx <= 8 else 12090 + idx - 9
    elif name in GT_SERIES and 1 <= idx <= 12:
        low, high = GT_SERIES[name]
        if idx > 5 and high is None:
            return False
        meta = low + idx - 1 if idx <= 5 else high + idx - 6
    else:
        return False
    return machine["machine"] == "gregtech:gt.blockmachines@" + str(meta)


POS = {"x": 0, "y": 0}
VALIDATOR = Draft202012Validator(PRODUCTION_PLAN_SCHEMA)


def solver_dir(database):
    return Path(database).parent / ("factory-flow-" + ALU_COMMIT[:12])


def install_solver(database, checkout):
    """Install immutable git blobs from the named commit into this instance's private index directory."""
    dest = solver_dir(database)
    dest.parent.mkdir(parents=True, exist_ok=True)
    files = {"LICENSE": "ALU-LICENSE.txt", **{"brain/vendor/gtnh-planner/" + p: Path(p).name for p in ASSETS}}
    for license in ("gtnh-factory-flow", "highs", "zod", "esbuild"):
        files["brain/vendor/gtnh-planner/licenses/" + license + "-MIT.txt"] = license + "-MIT.txt"
    files["brain/vendor/gtnh-planner/mod/LICENSE"] = "planner-mod-LICENSE.txt"
    with tempfile.TemporaryDirectory(dir=dest.parent) as tmp:
        stage = Path(tmp) / "solver"
        stage.mkdir()
        for source, name in files.items():
            result = subprocess.run(
                ["git", "-C", str(Path(checkout).resolve()), "cat-file", "blob", ALU_COMMIT + ":" + source],
                capture_output=True,
                timeout=25,
                check=True,
            )
            if expected := ASSETS.get(source.removeprefix("brain/vendor/gtnh-planner/")):
                if hashlib.sha256(result.stdout).hexdigest() != expected:
                    raise ValueError("Unexpected pinned solver asset: " + name)
            (stage / name).write_bytes(result.stdout)
        if dest.exists():
            if not all(
                (dest / f.name).is_file() and (dest / f.name).read_bytes() == f.read_bytes()
                for f in stage.iterdir()
            ):
                raise ValueError(
                    "Existing installation differs from the pinned files. Archive it before installing."
                )
        else:
            stage.rename(dest)
    return {"installed": True, "sourceCommit": ALU_COMMIT, "assetSha256": ASSETS}


def _item(row):
    return row["id"] + "@" + str(row.get("meta", 0))


def _split(resource):
    if resource.startswith("fluid:"):
        return "fluid", resource[6:]
    return "item", resource


class Unsupported(ValueError):
    """A real record lacks the semantics needed to issue a finite production plan."""

    def __init__(self, reason, **details):
        super().__init__(reason)
        self.details = details


def _blast_furnace(db, reference, mapping, machine):
    """Model the standard GT EBF with exactly one normal energy input hatch per controller."""
    tier = TIERS.index(machine["tier"])
    if machine["machine"] != "gregtech:gt.blockmachines@1000" or tier < TIERS.index("MV"):
        raise Unsupported("machine_or_handler_not_modelled")
    hatch_meta = 40 + tier if tier <= TIERS.index("UHV") else 11300 + tier - TIERS.index("UEV")
    if machine["energyHatch"] != f"gregtech:gt.blockmachines@{hatch_meta}":
        raise Unsupported("energy_hatch_tier_mismatch")
    control = reference["controls"]["heatingCoil~5"]
    coil = next((c for c in control["tiers"] if c["key"] == machine["coilTier"]), None)
    if ("heatingCoil~5" not in mapping.get("controls", ()) or not coil or not any(
        c["key"] == coil["key"] and c["heat"] == coil["heat"] for c in reference["coils"]
    )):
        raise Unsupported("coil_not_modelled")
    coil_item = coil["icon"] if "@" in coil["icon"] else coil["icon"] + "@0"
    if db.execute("SELECT count(*) FROM entries WHERE kind='item' AND ref IN (?, ?)",
                  (coil_item, machine["energyHatch"])).fetchone()[0] != 2:
        raise Unsupported("coil_or_energy_hatch_absent_from_index")
    # GT5U MTEElectricBlastFurnace.checkMachine uses the maximum input voltage's tier.
    return {"coilItem": coil_item, "coilHeatK": coil["heat"],
            "availableHeatK": coil["heat"] + 100 * (tier - TIERS.index("MV"))}


def _accepts_machine(profile, machine):
    if machine["machine"] not in profile.get("items", []):
        return False
    if machine["handler"] == "gt.recipe.blastfurnace":
        return profile.get("kind") == "multiblock" and profile["id"] == "electric-blast-furnace"
    return (profile.get("kind") == "single" and machine["tier"] in profile.get("availableTiers", [])
            and "steam" not in profile["id"])


@dataclass
class Stock:
    available: Counter
    missing: Counter = field(default_factory=Counter)
    fixtures: Counter = field(default_factory=Counter)
    steps: list = field(default_factory=list)
    reasons: list = field(default_factory=list)


class Routes:
    def __init__(self, db, args, reference):
        self.db = db
        catalog = {m["id"]: m for m in reference["maps"]}
        self.pins = {x["resource"]: x["recipeId"] for x in args.get("recipeChoices", [])}
        self.feedstocks = set(args.get("feedstocks", []))
        if self.pins.keys() & self.feedstocks:
            raise BridgeError(
                "INVALID_ARGUMENTS", "A pinned producer and a raw feed boundary cannot use the same resource."
            )
        self.pools = {}
        self.unusable = []
        self.visits = 0
        self.rejected = {}
        self.recipe_cache = {}
        self.ore_cache = {}
        machine_ids = [m["machine"] for m in args["machines"]]
        if len(set(machine_ids)) != len(machine_ids):
            raise BridgeError(
                "INVALID_ARGUMENTS",
                "Shared physical machine pools across different handlers need a separate schedule.",
            )
        for machine in args["machines"]:
            handler = machine["handler"]
            if handler in self.pools or any(x["handler"] == handler for x in self.unusable):
                raise BridgeError("INVALID_ARGUMENTS", "Declare one machine pool per handler.")
            row = db.execute(
                "SELECT payload FROM entries WHERE kind='handler' AND ref=?", (handler,)
            ).fetchone()
            exported = json.loads(row[0]) if row else {}
            mapping = catalog.get(handler, {})
            ebf = handler == "gt.recipe.blastfurnace"
            profile = next((p for p in mapping.get("handlers", []) if _accepts_machine(p, machine)), None)
            if (
                (not ebf and not _matches_machine_tier(machine))
                or not profile
                or exported.get("read") != "gregtech"
                or not any(_item(x) == machine["machine"] for x in exported.get("machines", []))
            ):
                self.unusable.append({**machine, "reason": "machine_or_handler_not_modelled"})
                continue
            try:
                config = _blast_furnace(db, reference, mapping, machine) if ebf else {}
            except Unsupported as exc:
                self.unusable.append({**machine, "reason": str(exc)})
                continue
            self.pools[handler] = {**machine, **config, "profile": profile["label"], "kind": profile["kind"]}

    def candidates(self, resource):
        if resource in self.feedstocks:
            return []
        if resource in self.recipe_cache:
            return self.recipe_cache[resource]
        if len(self.recipe_cache) >= 256:
            raise RouteLimit("resource expansion budget; pin recipes or supply intermediates")
        if resource.startswith("fluid:"):
            key, meta = resource, -1
        else:
            key, raw = resource.rsplit("@", 1)
            meta = int(raw)
        selected = self.pins.get(resource)
        if selected:
            rows = self.db.execute(
                "SELECT ref,payload FROM entries WHERE kind='recipe' AND ref=?", (selected,)
            )
        elif self.pools:
            placeholders = ",".join("?" for _ in self.pools)
            rows = self.db.execute(
                "SELECT e.ref,e.payload FROM ingredients i JOIN entries e ON e.id=i.entry "
                "WHERE i.resource=? AND i.meta=? AND i.direction='output' AND "
                f"json_extract(e.payload,'$.h') IN ({placeholders}) ORDER BY e.id LIMIT 257",
                (key, meta, *self.pools),
            )
        else:
            return []
        unique, choices, reasons = set(), [], []
        for index, (ref, raw) in enumerate(rows):
            if index == 256:
                raise RouteLimit("recipe candidate read budget; pin a recipe for " + resource)
            if raw in unique:
                continue
            unique.add(raw)
            try:
                recipe = self.parse(ref, json.loads(raw))
                if resource not in recipe["outputs"]:
                    raise Unsupported("wrong_output")
                choices.append(recipe)
            except Unsupported as exc:
                if len(reasons) < 8:
                    reasons.append({"recipeId": ref, "reason": str(exc), **exc.details})
        if reasons:
            self.rejected[resource] = reasons
        if selected and not choices:
            raise BridgeError(
                "PLANNER_RECIPE_UNAVAILABLE",
                "Pinned recipe cannot supply its exact resource on the declared pool.",
                resource=resource,
                recipeId=selected,
                reasons=reasons,
            )
        self.recipe_cache[resource] = choices
        return choices

    def ore(self, name):
        if name in self.ore_cache:
            return self.ore_cache[name]
        members = self.db.execute(
            "SELECT resource,meta FROM item_ores WHERE ore=? LIMIT 257", ("ore:" + name,)
        ).fetchall()
        if len(members) > 256:
            raise Unsupported("ore_membership_too_large")
        self.ore_cache[name] = [k + "@" + str(meta) for k, meta in members if meta != 32767]
        return self.ore_cache[name]

    def parse(self, ref, row):
        machine = self.pools.get(row.get("h"))
        if not machine:
            raise Unsupported("machine_unavailable")
        heat = {}
        if row.get("h") == "gt.recipe.blastfurnace":
            required = row.get("special")
            if type(required) is not int or required <= 0:
                raise Unsupported("unknown_recipe_heat")
            if required > machine["availableHeatK"]:
                raise Unsupported("insufficient_heat", requiredHeatK=required, availableHeatK=machine["availableHeatK"])
            heat["requiredHeatK"] = required
        elif row.get("special"):
            raise Unsupported("special_or_unmodelled_recipe")
        if (
            row.get("fake")
            or row.get("other")
            or set(row) - {"in", "out", "fin", "fout", "h", "eut", "ticks", "special", "fake"}
        ):
            raise Unsupported("special_or_unmodelled_recipe")
        eut, duration = row.get("eut"), row.get("ticks")
        if (
            type(eut) != int
            or type(duration) != int
            or not 0 < eut <= 8 * 4 ** TIERS.index(machine["tier"])
            or duration <= 0
        ):
            raise Unsupported("power_or_time_unsupported")
        inputs, outputs = [], Counter()
        for group in ("in", "fin", "out", "fout"):
            for slot in row.get(group, []):
                if group.startswith("f"):
                    if set(slot) - {"f", "mb", "name"} or not slot.get("f"):
                        raise Unsupported("fluid_identity_unsupported")
                    options, amount = ["fluid:" + slot["f"]], slot.get("mb")
                else:
                    if (
                        set(slot) - {"id", "meta", "n", "any", "ore", "more", "chance"}
                        or slot.get("chance", 1) != 1
                    ):
                        raise Unsupported("tagged_chance_or_unknown_slot")
                    amount = slot.get("n")
                    if "id" in slot:
                        if type(slot.get("meta", 0)) != int or slot.get("meta") == 32767:
                            raise Unsupported("wildcard_item")
                        options = [_item(slot)]
                    else:
                        options = [k + "@" + str(m) for k, m in slot.get("any", []) if m != 32767]
                        if slot.get("ore"):
                            options += self.ore(slot["ore"])
                        elif slot.get("more"):
                            raise Unsupported("truncated_alternatives")
                        options = sorted(set(options))
                if not options or type(amount) != int or amount < 0:
                    raise Unsupported("unknown_ingredient")
                if group in ("in", "fin"):
                    if not amount and group == "fin":
                        raise Unsupported("zero_fluid_input")
                    inputs.append((options, amount))
                else:
                    if len(options) != 1 or amount <= 0:
                        raise Unsupported("indeterminate_output")
                    outputs[options[0]] += amount
        if not outputs or not inputs:
            raise Unsupported("incomplete_recipe")
        return {
            "recipeId": ref,
            "handler": row["h"],
            **heat,
            "eut": eut,
            "ticks": duration,
            "inputs": inputs,
            "outputs": dict(outputs),
        }

    def supply(self, resource, amount, state, path):
        if amount > 1_000_000_000:
            raise RouteLimit("finite material budget exceeded")
        used = min(state.available[resource], amount)
        state.available[resource] -= used
        amount -= used
        if not amount:
            return state
        if resource in path:
            state.reasons.append({"code": "cycle_requires_seed_or_alternate_route", "resource": resource})
            state.missing[resource] += amount
            return state
        if len(path) >= 12 or len(state.steps) >= 32:
            raise RouteLimit("route depth or stage budget; pin a shorter route or supply intermediates")
        choices = self.candidates(resource)
        if not choices:
            state.missing[resource] += amount
            return state

        def immediate(recipe):
            batches = (amount + recipe["outputs"][resource] - 1) // recipe["outputs"][resource]
            return (
                sum(
                    max(
                        0,
                        (n * batches if n else 1)
                        - max(state.available[x] + (0 if n else state.fixtures[x]) for x in opts),
                    )
                    for opts, n in recipe["inputs"]
                ),
                batches * recipe["ticks"],
                recipe["recipeId"],
            )

        best, best_score = None, None
        for recipe in sorted(choices, key=immediate):
            self.visits += 1
            if self.visits > 768:
                raise RouteLimit("route search budget; pin recipes or supply intermediates")
            trial = deepcopy(state)
            output = recipe["outputs"][resource]
            batches = (amount + output - 1) // output
            consumed, fixtures = Counter(), Counter()
            slots = []
            for opts, qty in recipe["inputs"]:
                # Prefer actual stock and existing fixtures before seeking a producer.
                ranked = sorted(
                    opts,
                    key=lambda x: (
                        -min(qty * batches or 1, trial.available[x] + (0 if qty else trial.fixtures[x])),
                        x,
                    ),
                )
                if qty:
                    if len(ranked) > 1 and not trial.available[ranked[0]]:
                        ranked.sort(key=lambda x: (not bool(self.candidates(x)), x))
                    # One alternative per slot per batch; split only at whole recipe-batch boundaries.
                    remaining = batches
                    assigned = Counter()
                    for key in ranked:
                        count = min(remaining, trial.available[key] // qty)
                        if count:
                            trial.available[key] -= count * qty
                            consumed[key] += count * qty
                            assigned[key] += count
                            remaining -= count
                    if remaining:
                        key = ranked[0]
                        trial = self.supply(key, remaining * qty, trial, path + (resource,))
                        consumed[key] += remaining * qty
                        assigned[key] += remaining
                    slots.append(
                        {"perBatch": qty, "batchesByResource": dict(assigned), "alternativeCount": len(opts)}
                    )
                else:
                    key = ranked[0]
                    fixtures[key] += 1
                    extra = max(0, fixtures[key] - trial.fixtures[key])
                    take = min(extra, trial.available[key])
                    trial.available[key] -= take
                    trial.fixtures[key] += extra
                    if extra > take:
                        trial.missing[key] += extra - take
                    slots.append({"perBatch": 0, "reusable": key, "alternativeCount": len(opts)})
            produced = {key: n * batches for key, n in recipe["outputs"].items()}
            if max([*produced.values(), *consumed.values()]) > 1_000_000_000:
                raise RouteLimit("finite material budget exceeded")
            trial.available.update(produced)
            trial.available[resource] -= amount
            trial.steps.append(
                {k: recipe[k] for k in ("recipeId", "handler", "ticks", "eut")}
                | ({"requiredHeatK": recipe["requiredHeatK"]} if "requiredHeatK" in recipe else {})
                | {
                    "batches": batches,
                    "consumed": dict(consumed),
                    "produced": produced,
                    "reusables": dict(fixtures),
                    "inputSlots": slots,
                }
            )
            score = (
                bool(trial.missing),
                len(trial.reasons),
                len(trial.missing),
                sum(trial.missing.values()),
                sum(x["ticks"] * x["batches"] for x in trial.steps),
                len(trial.steps),
            )
            if best_score is None or score < best_score:
                best, best_score = trial, score
            if not trial.missing and len(choices) > 1:
                # An actually funded route suffices. Global alternative ranking is outside the search contract.
                break
        return best


def _project(state, initial, target, horizon, pools):
    """Bind each material transfer to an actual upstream node or a measured-quantity boundary."""
    recipes, nodes, edges, storages = [], [], [], []
    lots = defaultdict(list)
    boundaries = {}
    used_feed = Counter()
    retained = Counter()
    intermediates = []

    def store(direction, key, quantity):
        ident = "store" + str(len(storages))
        kind, name = _split(key)
        entry = {
            "id": ident,
            "kind": kind,
            "resourceId": name,
            "position": POS,
            "targetPerSecond": quantity * 20 / horizon,
            "targetMode": "exact",
        }
        storages.append(entry)
        boundaries[ident] = (direction, key, quantity)
        return ident

    def wire(source, dest, resource):
        kind, name = _split(resource)
        edges.append(
            {
                "id": "edge" + str(len(edges)),
                "source": source,
                "target": dest,
                "resourceKind": kind,
                "resourceId": name,
            }
        )

    def take(key, amount, dest):
        for lot in lots[key]:
            moved = min(lot[1], amount)
            if moved:
                lot[1] -= moved
                amount -= moved
                if dest is None:
                    # A tool made by an earlier stage remains physical stock. Only
                    # that one-off production crosses the boundary; no consumption rate.
                    if not lot[0].startswith("feed:"):
                        wire(lot[0], store("retained_fixture", key, moved), key)
                else:
                    wire(lot[0], dest, key)
                    if lot[0].startswith("feed:"):
                        used_feed[key] += moved
                    elif dest.startswith("step"):
                        intermediates.append(
                            {
                                "sourceStep": int(lot[0][4:]),
                                "targetStep": int(dest[4:]),
                                "resource": key,
                                "amount": moved,
                                "plannedPerSecond": moved * 20 / horizon,
                                "edgeId": edges[-1]["id"],
                            }
                        )
            if not amount:
                return
        raise ValueError("Unbalanced graph for " + key)

    # Initial stock, including any unconsumed target, stays outside factory throughput.
    # A source feeds only the part used for processing; reusable tools never become a flow.
    available = Counter(initial) + state.missing
    goal_from_stock = min(initial[target["resource"]], target["amount"])
    fresh_goal = target["amount"] - goal_from_stock
    for key in available:
        free = available[key] - (goal_from_stock if key == target["resource"] else 0)
        if free:
            lots[key].append(["feed:" + key, free])
    for index, step in enumerate(state.steps):
        node = "step" + str(index)
        batches = step["batches"]

        def ports(total):
            return [
                {"kind": _split(key)[0], "id": _split(key)[1], "amount": n / batches}
                for key, n in total.items()
            ]

        machine = pools[step["handler"]]
        mintier = next(t for i, t in enumerate(TIERS) if step["eut"] <= 8 * 4**i)
        recipes.append(
            {
                "id": "r" + str(index),
                "name": step["recipeId"],
                "machineType": machine["profile"],
                "minimumTier": mintier,
                "durationTicks": step["ticks"],
                "eut": step["eut"],
                "inputs": ports(step["consumed"]),
                "outputs": ports(step["produced"]),
            }
        )
        nodes.append(
            {
                "id": node,
                "recipeId": "r" + str(index),
                "machineCount": 1,
                "parallel": 1,
                "overclockTier": machine["tier"],
                "enabled": True,
                "position": POS,
            }
        )
        if "requiredHeatK" in step:
            # The declared machine has a fixed physical coil. Give Factory Flow its actual
            # tier/heat and recipe special value so its EBF overclock and EU discount apply.
            coil = machine["coilTier"]
            recipes[-1].update(
                nei={"additionalInfo": [f"Special value: {step['requiredHeatK']}"]},
                machineProfile={"kind": "multiblock", "machineType": machine["profile"], "minimumTier": "MV"},
                machineConfigControls=[{
                    "id": "heatingCoil", "label": "Heating Coil", "minimumKey": coil, "defaultKey": coil,
                    "tiers": [{"key": coil, "label": coil, "heat": machine["coilHeatK"],
                               "resource": {"kind": "item", "id": machine["coilItem"], "amount": 1}}],
                }],
            )
            nodes[-1]["coilTier"] = coil
        for key, qty in step["reusables"].items():
            extra = max(0, qty - retained[key])
            if extra:
                take(key, extra, None)
                retained[key] += extra
        for key, n in step["consumed"].items():
            take(key, n, node)
        for key, n in step["produced"].items():
            lots[key].append([node, n])
    if fresh_goal:
        take(target["resource"], fresh_goal, store("goal", target["resource"], fresh_goal))
    # Source allocations are aggregated after FIFO consumption. Surplus from a recipe
    # has its own sink, whereas unused inventory remains in its original store.
    for key, rows in lots.items():
        for node, remaining in rows:
            if node.startswith("feed:"):
                used = used_feed[key]
                if used:
                    ident = store("feed", key, used)
                    for edge in edges:
                        if edge["source"] == node:
                            edge["source"] = ident
            elif remaining:
                wire(node, store("surplus", key, remaining), key)
    project = {
        "schemaVersion": 1,
        "id": "production",
        "name": "NEI batch plan",
        "planMode": True,
        "solveMode": True,
        "recipes": recipes,
        "nodes": nodes,
        "storages": storages,
        "edges": edges,
        "fuelProfiles": [],
    }
    return project, boundaries, intermediates


def _run(project, assets):
    node = shutil.which("node")
    if not node:
        raise BridgeError(
            "PLANNER_RUNTIME_MISSING", "Install Node.js 22 or later for the offline Factory Flow solver."
        )
    raw = json.dumps(project, separators=(",", ":"), allow_nan=False).encode()
    try:
        result = subprocess.run(
            [
                node,
                "--max-old-space-size=384",
                str(Path(__file__).with_name("factory_flow_runner.cjs")),
                str(assets.resolve()),
            ],
            input=raw,
            capture_output=True,
            timeout=18,
            env={k: v for k, v in os.environ.items() if k.upper() not in ("NODE_OPTIONS", "NODE_PATH")},
        )
    except subprocess.TimeoutExpired as exc:
        raise BridgeError("PLANNER_TIMEOUT", "Factory Flow exceeded its 18-second deadline.") from exc
    if result.returncode or len(result.stdout) > 1_000_000:
        raise BridgeError(
            "PLANNER_SOLVER_FAILED",
            result.stderr.decode(errors="replace")[-800:] or "Invalid solver response.",
        )
    return json.loads(result.stdout)


def plan_production(database, args):
    error = next(VALIDATOR.iter_errors(args), None)
    if error:
        raise BridgeError("INVALID_ARGUMENTS", error.message[:300])
    if (
        any(type(row["amount"]) is not int for row in [args["target"], *args["inventory"]])
        or type(args["horizonTicks"]) is not int
        or any(type(row["count"]) is not int for row in args["machines"])
    ):
        raise BridgeError("INVALID_ARGUMENTS", "Finite counts and the tick horizon must be integers.")
    inventory = {x["resource"]: x["amount"] for x in args["inventory"]}
    pins = args.get("recipeChoices", [])
    if len(inventory) != len(args["inventory"]) or len({x["resource"] for x in pins}) != len(pins):
        raise BridgeError("INVALID_ARGUMENTS", "Use one inventory row or recipe choice per exact resource.")
    for key in [
        *inventory,
        *args.get("feedstocks", []),
        args["target"]["resource"],
        *[p["resource"] for p in pins],
        *[m["machine"] for m in args["machines"]],
    ]:
        if key.startswith("fluid:") and "@" in key:
            raise BridgeError("INVALID_ARGUMENTS", "A fluid name identifies mB and has no item metadata.")
        if not key.startswith("fluid:"):
            suffix = key.rsplit("@", 1)[1]
            if str(int(suffix)) != suffix or int(suffix) >= 32767:
                raise BridgeError(
                    "INVALID_ARGUMENTS", "Choose an exact canonical metadata value between 0 and 32766."
                )
    database = Path(database)
    if not database.is_file():
        raise BridgeError("PACKDATA_MISSING", "Index the selected instance's NEI export before planning.")
    assets = solver_dir(database)
    catalog_file = assets / "handlers.json"
    if (
        not catalog_file.is_file()
        or not (assets / "runtime.cjs").is_file()
        or not (assets / "highs.wasm").is_file()
    ):
        raise BridgeError(
            "PLANNER_RUNTIME_MISSING",
            "Use scripts/plan_production.py --install-solver with the pinned Alu checkout for this instance.",
        )
    blob = catalog_file.read_bytes()
    if hashlib.sha256(blob).hexdigest() != ASSETS["mod/handlers.json"]:
        raise BridgeError("PLANNER_ASSET_MISMATCH", "The reference machine catalog has changed.")
    reference = json.loads(blob)
    initial = Counter(inventory)
    target = args["target"]
    base = {
        "source": "bound_nei_and_factory_flow",
        "productionVerified": False,
        "liveStateObserved": False,
        "target": target,
        "horizonTicks": args["horizonTicks"],
        "inventorySource": "caller_declared",
        "machineTierAndCountSource": "caller_declared",
        "factoryFlowCommit": FACTORY_FLOW,
        "plannerModCommit": PLANNER_MOD,
        "solverAssetCommit": ALU_COMMIT,
        "globalOptimumVerified": False,
    }
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)) as db:
        deadline = time.monotonic() + 7
        db.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
        db.execute("BEGIN")
        data = db.execute("SELECT info FROM sources WHERE dataset='nei'").fetchone()
        if not data:
            raise BridgeError("PACKDATA_MISSING", "The selected catalog has no NEI source.")
        stamp = json.loads(data[0])
        base["revision"] = stamp["revision"]
        recent = {}
        if (stamp.get("inputSignature") or {}).get("sha256"):
            recent = last_checks(database, {"nei": stamp}).get("nei", {})
        base["installationInputs"] = {
            "status": recent.get("status", "not_checked"),
            "checkedAt": recent.get("checkedAt"),
            "refreshRecommended": recent.get("status") == "refresh_recommended",
            "scope": "Recorded installation files for this export revision; world state requires separate observation.",
        }
        if args.get("expectedRevision", base["revision"]) != base["revision"]:
            raise BridgeError("PACKDATA_CHANGED", "Obtain fresh recipe IDs for the selected NEI catalog.")
        routes = Routes(db, args, reference)
        try:
            for key in routes.pins:
                routes.candidates(key)
            state = routes.supply(target["resource"], target["amount"], Stock(Counter(initial)), ())
            state = schedule_returns(state, initial, target)
        except (RouteLimit, sqlite3.OperationalError) as exc:
            return {**base, "status": "search_limited", "reason": str(exc), "productionVerified": False}
    base.update(
        steps=state.steps,
        unmodelledMachines=routes.unusable,
        missing=dict(state.missing),
        reusableFixtures=dict(state.fixtures),
        remainingInventory={k: v for k, v in state.available.items() if v},
        guards=state.reasons,
        rejectedRecipes=dict(list(routes.rejected.items())[:24]),
        rejectedResourceCount=len(routes.rejected),
        routeCandidatesEvaluated=routes.visits,
    )
    if len(state.steps) > 32:
        return {**base, "status": "search_limited", "reason": "stage budget"}
    if not state.steps:
        return {
            **base,
            "status": "inventory_satisfies_target" if not state.missing else "missing_materials_or_route",
            "solverInvoked": False,
        }
    # A missing final target or cyclic seed is an unproved external requirement; do not model it as output.
    if state.missing[target["resource"]] or state.reasons:
        return {**base, "status": "unsupported_cycle_or_route", "solverInvoked": False}
    project, bounds, intermediates = _project(state, initial, target, args["horizonTicks"], routes.pools)
    native = _run(project, assets)
    issues = []

    def flag(code, **details):
        issues.append({"code": code, **details})

    if base["installationInputs"]["refreshRecommended"]:
        flag("refresh_or_recheck_installation_inputs")
    for transfer in intermediates:
        actual = native.get("edgeRates", {}).get(transfer["edgeId"])
        transfer["solverPerSecond"] = actual
        if actual is None or not math.isclose(
            actual, transfer["plannedPerSecond"], abs_tol=1e-8, rel_tol=1e-6
        ):
            flag("intermediate_rate_mismatch", edge=transfer["edgeId"])
    if (
        native.get("failure")
        or native.get("stale")
        or native.get("unanchored")
        or native.get("shortfalls")
        or native.get("unmetDrawers")
        or native.get("forced")
    ):
        flag("upstream_balance_or_goal_guard")
    flows = []
    for ident, (kind, key, n) in bounds.items():
        reading = native["storages"].get(ident, {})
        actual = reading.get("consumedPerSecond" if kind == "feed" else "producedPerSecond")
        expected = 20 * n / args["horizonTicks"]
        if actual is None or not math.isclose(actual, expected, abs_tol=1e-8, rel_tol=1e-6):
            flag("boundary_rate_mismatch", boundary=ident)
        flows.append(
            {
                "direction": kind,
                "resource": key,
                "amount": n,
                "targetPerSecond": expected,
                "solverPerSecond": actual,
            }
        )
    occupancy, power = Counter(), Counter()
    elapsed = 0
    average = 0
    for i, step in enumerate(state.steps):
        node = native["nodes"].get("step" + str(i), {})
        fraction = node.get("machines")
        if (
            not isinstance(fraction, (float, int))
            or fraction <= 0
            or node.get("powerStalled")
            or node.get("status") == "missing-recipe"
        ):
            flag("upstream_machine_unavailable", recipeId=step["recipeId"])
            continue
        handler = step["handler"]
        pool = routes.pools[handler]
        occupancy[handler] += fraction
        draw = node["euT"] * node["utilization"]
        full_load = draw / fraction
        if "requiredHeatK" in step:
            # Factory Flow returns continuous power. GT5U's OverclockCalculator rounds
            # the finished per-machine EU/t upward after applying the coil discounts.
            step["solverFullMachineEUt"] = full_load
            nearest = round(full_load)
            full_load = nearest if abs(full_load - nearest) <= 4 * math.ulp(full_load) else math.ceil(full_load)
            draw = full_load * fraction
        average += draw
        power[handler] = max(power[handler], full_load)
        ticks = fraction * args["horizonTicks"] / step["batches"]
        parallel = min([pool["count"], *[state.fixtures[k] // n for k, n in step["reusables"].items()]])
        serial = math.ceil(step["batches"] / parallel) * ticks
        elapsed += serial
        step.update(
            machine=pool["machine"],
            tier=pool["tier"],
            modelTicksPerBatch=ticks,
            fractionalMachines=fraction,
            averageEUt=draw,
            fullMachineEUt=full_load,
            serialStageTicks=serial,
        )
        if "requiredHeatK" in step:
            step.update({k: pool[k] for k in ("coilTier", "coilItem", "coilHeatK", "energyHatch", "availableHeatK")})
    pools = []
    for key, value in occupancy.items():
        count = math.ceil(value - 1e-8)
        pool = routes.pools[key]
        pools.append(
            {
                **pool,
                "fractionalMachines": value,
                "requiredWholeMachines": count,
                "fullMachineEUt": power[key],
            }
        )
        if count > pool["count"]:
            flag("machine_capacity_exceeded", handler=key, required=count, available=pool["count"])
    # The serial-stage schedule is a conservative witness; pipelined makespan may be smaller.
    if elapsed > args["horizonTicks"] + 1e-6:
        flag("serial_schedule_exceeds_horizon", serialTicks=elapsed)
    status = "missing_materials" if state.missing else "conditional" if issues else "planned"
    return {
        **base,
        "status": status,
        "solverInvoked": True,
        "solver": native,
        "flows": flows,
        "intermediates": intermediates,
        "machinePools": pools,
        "modeledTotalEU": average * args["horizonTicks"],
        "averageEUt": average,
        "integerBuildPeakEUt": sum(p["requiredWholeMachines"] * p["fullMachineEUt"] for p in pools),
        "serialScheduleTicks": elapsed,
        "issues": issues,
    }
