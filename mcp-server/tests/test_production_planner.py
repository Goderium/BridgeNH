"""Finite production regressions. Ordinary bronze quantities come from a GTNH NEI export."""

from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge import production_planner as p
from minecraft_codex_bridge.packdata import _schema, _entry, _ingredients

GT = "gregtech:gt.metaitem.01"
ALLOY, BENDER = "gt.recipe.alloysmelter", "gt.recipe.metalbender"
CU, SN, INGOT, PLATE = [f"{GT}@{i}" for i in (11035, 11057, 11300, 17300)]
CIRCUIT = "gregtech:gt.integrated_circuit@1"
MACHINES = [
    {"handler": ALLOY, "machine": "gregtech:gt.blockmachines@201", "count": 1, "tier": "LV"},
    {"handler": BENDER, "machine": "gregtech:gt.blockmachines@221", "count": 1, "tier": "LV"},
]


def slot(key, n):
    name, meta = key.rsplit("@", 1)
    return {"id": name, "meta": int(meta), "n": n}


def recipe(handler, inputs, outputs, *, ticks=20, eut=8, **extra):
    return {"h": handler, "in": inputs, "out": outputs, "ticks": ticks, "eut": eut, **extra}


def make_request():
    return {
        "target": {"resource": PLATE, "amount": 4},
        "inventory": [
            {"resource": CU, "amount": 3},
            {"resource": SN, "amount": 1},
            {"resource": CIRCUIT, "amount": 1},
        ],
        "machines": [x.copy() for x in MACHINES],
        "horizonTicks": 1200,
    }


@pytest.fixture
def catalog(tmp_path, monkeypatch):
    index = tmp_path / "records.sqlite3"
    catalog = {"maps": []}
    with closing(sqlite3.connect(index)) as db, db:
        _schema(db)
        db.execute("INSERT INTO sources VALUES(?,?)", ("nei", json.dumps({"revision": "e" * 64})))
        for m in MACHINES:
            item = m["machine"].split("@")
            _entry(
                db,
                "handler",
                m["handler"],
                "press",
                "",
                "",
                {"read": "gregtech", "machines": [{"id": item[0], "meta": int(item[1])}]},
            )
            catalog["maps"].append(
                {
                    "id": m["handler"],
                    "handlers": [
                        {
                            "id": "basic",
                            "label": "basic",
                            "kind": "single",
                            "availableTiers": ["LV", "MV"],
                            "items": [m["machine"]],
                        }
                    ],
                }
            )
    assets = p.solver_dir(index)
    assets.mkdir()
    blob = json.dumps(catalog).encode()
    (assets / "handlers.json").write_bytes(blob)
    monkeypatch.setitem(p.ASSETS, "mod/handlers.json", p.hashlib.sha256(blob).hexdigest())
    (assets / "runtime.cjs").touch()
    (assets / "highs.wasm").touch()

    def add(ref, row):
        with closing(sqlite3.connect(index)) as db, db:
            count = _entry(db, "recipe", ref, "recipe", "", "", row)
            db.executemany(
                "INSERT INTO ingredients VALUES(?,?,?,?)", ((count, *x) for x in set(_ingredients(row)))
            )

    return index, add


def native_fixture(project, assets):
    # Tests the boundary adapter; real pinned WASM has a separate opt-in integration test.
    return {
        "nodes": {
            n["id"]: {"machines": 0.25, "euT": 4, "utilization": 1, "status": "balanced"}
            for n in project["nodes"]
        },
        "storages": {
            n["id"]: {"consumedPerSecond": n["targetPerSecond"], "producedPerSecond": n["targetPerSecond"]}
            for n in project["storages"]
        },
        "diagnostics": {"backend": "fixture"},
    }


def bronze_rows(add):
    add("1-" + "1" * 20, recipe(ALLOY, [slot(CU, 3), slot(SN, 1)], [slot(INGOT, 4)], ticks=200, eut=15))
    add(
        "2-" + "2" * 20,
        recipe(BENDER, [slot(INGOT, 1), slot(CIRCUIT, 0)], [slot(PLATE, 1)], ticks=76, eut=24),
    )


def test_finite_inventory_partial_intermediate_and_fixture(catalog, monkeypatch):
    index, add = catalog
    bronze_rows(add)
    monkeypatch.setattr(p, "_run", native_fixture)
    args = make_request()
    args["inventory"] += [{"resource": INGOT, "amount": 2}, {"resource": PLATE, "amount": 1}]
    result = p.plan_production(index, args)
    assert result["productionVerified"] is False and result["liveStateObserved"] is False
    assert result["installationInputs"]["status"] == "not_checked"
    assert [x["batches"] for x in result["steps"]] == [1, 3]
    assert result["remainingInventory"] == {INGOT: 3}
    assert result["reusableFixtures"] == {CIRCUIT: 1}
    assert result["missing"] == {}
    assert [(b["resource"], b["amount"]) for b in result["flows"] if b["direction"] == "goal"] == [(PLATE, 3)]


def test_catalyst_deficit_is_one_across_repeated_batches(catalog, monkeypatch):
    index, add = catalog
    bronze_rows(add)
    monkeypatch.setattr(p, "_run", native_fixture)
    args = make_request()
    args["inventory"] = args["inventory"][:1]
    args["feedstocks"] = [CU, SN]
    result = p.plan_production(index, args)
    assert result["missing"] == {SN: 1, CIRCUIT: 1}
    assert result["status"] == "missing_materials"


def test_stage_ore_alternatives_split_only_at_whole_batches(catalog, monkeypatch):
    index, add = catalog
    add(
        "1-" + "1" * 20,
        recipe(BENDER, [{"n": 2, "ore": "ingotTest", "any": [["demo:a", 0]], "more": 1}], [slot(PLATE, 1)]),
    )
    with closing(sqlite3.connect(index)) as db, db:
        db.executemany(
            "INSERT INTO item_ores VALUES(?,?,?)",
            [("demo:a", 0, "ore:ingotTest"), ("demo:b", 0, "ore:ingotTest")],
        )
    monkeypatch.setattr(p, "_run", native_fixture)
    req = make_request()
    req["target"]["amount"] = 2
    req["inventory"] = [{"resource": "demo:a@0", "amount": 2}, {"resource": "demo:b@0", "amount": 2}]
    result = p.plan_production(index, req)
    assert result["steps"][0]["inputSlots"] == [
        {"perBatch": 2, "batchesByResource": {"demo:a@0": 1, "demo:b@0": 1}, "alternativeCount": 2}
    ]
    assert result["missing"] == {}


@pytest.mark.parametrize(
    "extra,expected",
    [({"special": 1300}, "special_or_unmodelled_recipe"), ({"fake": True}, "special_or_unmodelled_recipe")],
)
def test_pins_cannot_bypass_recipe_conditions(catalog, extra, expected):
    index, add = catalog
    ref = "4-" + "5" * 20
    add(ref, recipe(BENDER, [slot(INGOT, 1)], [slot(PLATE, 1)], **extra))
    req = make_request()
    req["recipeChoices"] = [{"resource": PLATE, "recipeId": ref}]
    with pytest.raises(BridgeError) as exc:
        p.plan_production(index, req)
    assert exc.value.code == "PLANNER_RECIPE_UNAVAILABLE"
    assert exc.value.details["reasons"][0]["reason"] == expected


def test_cycle_needs_seed_and_stale_revision_is_refused(catalog):
    index, add = catalog
    add("1-" + "1" * 20, recipe(ALLOY, [slot(PLATE, 1)], [slot(INGOT, 1)]))
    add("2-" + "2" * 20, recipe(BENDER, [slot(INGOT, 1)], [slot(PLATE, 1)]))
    args = make_request()
    assert p.plan_production(index, args)["status"] == "unsupported_cycle_or_route"
    args["expectedRevision"] = "d" * 64
    with pytest.raises(BridgeError, match="fresh recipe IDs"):
        p.plan_production(index, args)


def test_wrong_machine_tier_and_already_funded_target(catalog):
    index, add = catalog
    bronze_rows(add)
    req = make_request()
    req["machines"][1]["tier"] = "MV"
    result = p.plan_production(index, req)
    assert (
        result["status"] == "missing_materials_or_route" and result["unmodelledMachines"][0]["tier"] == "MV"
    )
    req["inventory"] = [req["target"].copy()]
    assert p.plan_production(index, req)["status"] == "inventory_satisfies_target"


def test_wrong_solver_assets_cannot_run(catalog):
    index, _ = catalog
    (p.solver_dir(index) / "handlers.json").write_text("{}")
    with pytest.raises(BridgeError) as exc:
        p.plan_production(index, make_request())
    assert exc.value.code == "PLANNER_ASSET_MISMATCH"


def test_native_external_solver_with_instance_export():
    if not os.environ.get("MCB_TEST_PLANNER_DATABASE"):
        pytest.skip("requires the pinned external solver and an explicit matching GTNH catalog")
    result = p.plan_production(Path(os.environ["MCB_TEST_PLANNER_DATABASE"]), make_request())
    assert result["status"] == "planned"
    assert result["solver"]["backend"] == "highs" and result["solver"]["diagnostics"]["highsCalls"] > 0
    assert result["averageEUt"] == pytest.approx(8.58) and result["integerBuildPeakEUt"] == pytest.approx(39)
    assert result["serialScheduleTicks"] == pytest.approx(504)


@pytest.mark.parametrize("starting_charge,missing_charge", [(0, 3), (1, 2), (3, 0), (6, 0)])
def test_returned_material_can_supply_the_next_batch(catalog, monkeypatch, starting_charge, missing_charge):
    index, add = catalog
    add("1-" + "1" * 20, recipe(ALLOY, [slot(CU, 1), slot(SN, 3)], [slot(INGOT, 1)]))
    add("2-" + "2" * 20, recipe(BENDER, [slot(INGOT, 1)], [slot(PLATE, 1), slot(SN, 3)]))
    monkeypatch.setattr(p, "_run", native_fixture)
    args = make_request()
    args["target"]["amount"] = 2
    args["inventory"] = [{"resource": CU, "amount": 2}]
    if starting_charge:
        args["inventory"].append({"resource": SN, "amount": starting_charge})
    args["feedstocks"] = [CU, SN]
    result = p.plan_production(index, args)
    assert result["missing"] == ({SN: missing_charge} if missing_charge else {})
    assert result["remainingInventory"] == {SN: max(starting_charge, 3)}
    assert sum(s["batches"] for s in result["steps"] if s["handler"] == ALLOY) == 2
    if starting_charge < 6:
        assert [s["handler"] for s in result["steps"]] == [ALLOY, BENDER, ALLOY, BENDER]
        assert any(edge["resource"] == SN and edge["amount"] == 3 for edge in result["intermediates"])


def test_large_reuse_schedule_reports_budget_instead_of_an_inflated_charge(catalog):
    index, add = catalog
    add("1-" + "1" * 20, recipe(ALLOY, [slot(CU, 1), slot(SN, 3)], [slot(INGOT, 1)]))
    add("2-" + "2" * 20, recipe(BENDER, [slot(INGOT, 1)], [slot(PLATE, 1), slot(SN, 3)]))
    args = make_request()
    args.update(target={"resource": PLATE, "amount": 20}, feedstocks=[CU, SN],
                inventory=[{"resource": CU, "amount": 20}, {"resource": SN, "amount": 3}])
    result = p.plan_production(index, args)
    assert result["status"] == "search_limited"
    assert "returned-material schedule" in result["reason"]


def test_native_solver_discovers_tungstate_chain_and_reuses_its_charge():
    database = os.environ.get("MCB_TEST_PLANNER_DATABASE")
    if not database:
        pytest.skip("requires the pinned external solver and an explicit matching GTNH catalog")
    dust = "bartworks:gt.bwMetaGenerateddust"
    chloride = dust + "@63"
    inventory = {GT + "@2841": 14, GT + "@2017": 4, "fluid:water": 8000,
                 "fluid:hydrochloricacid_gt5u": 4000, chloride: 3}
    args = {
        "target": {"resource": dust + "@29901", "amount": 8},
        "inventory": [{"resource": k, "amount": n} for k, n in inventory.items()],
        "feedstocks": list(inventory),
        "machines": [
            {"handler": "gt.recipe.autoclave", "machine": "gregtech:gt.blockmachines@574", "tier": "EV", "count": 1},
            {"handler": "gt.recipe.chemicalreactor", "machine": "gregtech:gt.blockmachines@424", "tier": "EV", "count": 1},
            {"handler": "gt.recipe.blastfurnace", "machine": "gregtech:gt.blockmachines@1000", "tier": "HV", "count": 1,
             "coilTier": "cupronickel", "energyHatch": "gregtech:gt.blockmachines@43"},
        ],
        "horizonTicks": 1200,
    }
    result = p.plan_production(Path(database), args)
    assert result["status"] == "planned" and not result["issues"]
    assert result["solver"]["backend"] == "highs" and result["solver"]["diagnostics"]["highsCalls"] > 0
    assert result["remainingInventory"] == {chloride: 3, GT + "@2006": 4, GT + "@2817": 8}
    assert any(edge["resource"] == chloride for edge in result["intermediates"])
    assert result["serialScheduleTicks"] == pytest.approx(800)
    assert result["modeledTotalEU"] == pytest.approx(960_000)
