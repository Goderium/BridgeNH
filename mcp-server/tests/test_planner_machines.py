"""Machine identity, physical EBF configuration, and its projection into Factory Flow."""
import json
import math
import os
import sqlite3
from pathlib import Path

import pytest

from minecraft_codex_bridge import production_planner as planner
from minecraft_codex_bridge.bridge import BridgeError


GT = "gregtech:gt.blockmachines@"
EBF = "gt.recipe.blastfurnace"
AUTOCLAVE = "gt.recipe.autoclave"
CHEMICAL = "gt.recipe.chemicalreactor"
TUNGSTIC_ACID = "bartworks:gt.bwMetaGenerateddust@29900"
TRIOXIDE = "bartworks:gt.bwMetaGenerateddust@29901"


def machine(handler=EBF, meta=1000, tier="HV", **config):
    if handler == EBF:
        config = {"coilTier": "cupronickel", "energyHatch": GT + "43", **config}
    return {"handler": handler, "machine": GT + str(meta), "tier": tier, "count": 1, **config}


def request(pool):
    return {"machines": [pool], "inventory": [], "target": {"resource": TRIOXIDE, "amount": 4},
            "horizonTicks": 1200}


@pytest.fixture
def reference():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE entries(kind TEXT,ref TEXT,payload TEXT)")
    coil = {"key": "cupronickel", "label": "Cupronickel", "heat": 1801, "icon": "gregtech:gt.blockcasings5"}
    catalog = {"coils": [coil], "controls": {"heatingCoil~5": {"tiers": [coil]}}, "maps": []}
    for handler, meta, kind in ((AUTOCLAVE, 574, "single"), (CHEMICAL, 424, "single"), (EBF, 1000, "multiblock")):
        entry = {"id": "electric-blast-furnace" if handler == EBF else handler, "label": handler,
                 "items": [GT + str(meta)], "kind": kind, "availableTiers": ["EV", "HV"]}
        catalog["maps"].append({"id": handler, "controls": ["heatingCoil~5"], "handlers": [entry]})
        db.execute("INSERT INTO entries VALUES ('handler',?,?)", (handler, json.dumps({
            "read": "gregtech", "machines": [{"id": "gregtech:gt.blockmachines", "meta": meta}]})))
    db.executemany("INSERT INTO entries VALUES ('item',?,'{}')", [(GT + "43",), ("gregtech:gt.blockcasings5@0",)])
    try:
        yield db, catalog
    finally:
        db.close()


def raw_recipe(handler, **extra):
    return {"h": handler, "in": [{"id": "example:raw", "n": 1}],
            "out": [{"id": "example:finished", "n": 1}], "ticks": 200, "eut": 480, **extra}


@pytest.mark.parametrize("pool", [machine(AUTOCLAVE,574,"EV"), machine(CHEMICAL,424,"EV")])
def test_registered_single_block_machines_keep_special_and_fake_guards(reference, pool):
    db, catalog = reference
    routes = planner.Routes(db, request(pool), catalog)
    assert routes.parse("recipe", raw_recipe(pool["handler"]))["handler"] == pool["handler"]
    for flag in ({"special": 1}, {"fake": True}):
        with pytest.raises(planner.Unsupported, match="special_or_unmodelled_recipe"):
            routes.parse("recipe", raw_recipe(pool["handler"], **flag))
    assert planner.Routes(db, request({**pool, "tier": "HV"}), catalog).unusable


def test_ebf_voltage_bonus_and_machine_identity(reference):
    db, catalog = reference
    routes = planner.Routes(db, request(machine()), catalog)
    assert routes.pools[EBF]["availableHeatK"] == 1901
    assert routes.parse("recipe", raw_recipe(EBF, special=1901))["requiredHeatK"] == 1901
    with pytest.raises(planner.Unsupported, match="insufficient_heat") as caught:
        routes.parse("recipe", raw_recipe(EBF, special=1902))
    assert caught.value.details == {"requiredHeatK": 1902, "availableHeatK": 1901}
    with pytest.raises(planner.Unsupported, match="special_or_unmodelled_recipe"):
        routes.parse("recipe", raw_recipe(EBF, special=1200, fake=True))
    for pool in (machine(meta=963), machine(energyHatch=GT + "44"), machine(energyHatch=GT + "209"),
                 machine(coilTier="unknown")):
        assert not planner.Routes(db, request(pool), catalog).pools
    db.execute("DELETE FROM entries WHERE kind='item' AND ref=?", (GT + "43",))
    assert planner.Routes(db, request(machine()), catalog).unusable[0]["reason"] == "coil_or_energy_hatch_absent_from_index"


def test_schema_accepts_exact_embedded_spaces_and_physical_ebf_config():
    args = request(machine())
    args["target"]["resource"] = "fluid:sodium tungstate"
    assert planner.VALIDATOR.is_valid(args)
    for value in ("fluid: sodium tungstate", "fluid:sodium tungstate ", "fluid:sodium\ntungstate",
                  "fluid:sodium tungstate\n", "a:b c@0"):
        assert not planner.VALIDATOR.is_valid({**args, "target": {"resource": value, "amount": 1}})
    for pool in ({k:v for k,v in machine().items() if k != "energyHatch"},
                 machine(CHEMICAL,424,"EV",coilTier="cupronickel")):
        assert not planner.VALIDATOR.is_valid(request(pool))


@pytest.mark.skipif(not os.environ.get("MCB_TEST_PLANNER_DATABASE"), reason="requires real GTNH NEI and pinned solver")
def test_real_blast_furnace_heat_in_native_solver():
    database = Path(os.environ["MCB_TEST_PLANNER_DATABASE"])
    # Resolve the exact records from this revision, independently of their JSONL line numbers.
    with sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True) as db:
        def output_recipe(resource, meta, heat):
            return db.execute("SELECT e.ref FROM ingredients i JOIN recipe_handlers h ON h.entry=i.entry "
                              "JOIN entries e ON e.id=i.entry WHERE i.direction='output' AND i.resource=? AND i.meta=? "
                              "AND h.handler=? AND json_extract(e.payload,'$.special')=? ORDER BY e.id LIMIT 1",
                              (resource, meta, EBF, heat)).fetchone()[0]
        trioxide = output_recipe(*TRIOXIDE.split("@"), 1200)
        titanium = output_recipe("gregtech:gt.metaitem.01", 12028, 2140)
    cases = [
        ("HV", 43, "cupronickel", 200, 480), ("HV", 43, "kanthal", 200, 456),
        ("HV", 43, "nichrome", 200, 433.2), ("EV", 44, "cupronickel", 100, 1920),
        ("EV", 44, "nichrome", 50, 1732.8),
    ]
    for tier, hatch_meta, coil, duration, eut in cases:
        args = request(machine(tier=tier, energyHatch=GT + str(hatch_meta), coilTier=coil))
        args.update(inventory=[{"resource": TUNGSTIC_ACID, "amount": 7}], feedstocks=[TUNGSTIC_ACID],
                    recipeChoices=[{"resource": TRIOXIDE, "recipeId": trioxide}])
        result = planner.plan_production(database, args)
        assert result["status"] == "planned" and result["solver"]["backend"] == "highs"
        step, = result["steps"]
        assert step["coilTier"] == coil and step["requiredHeatK"] == 1200
        assert step["modelTicksPerBatch"] == pytest.approx(duration)
        assert step["solverFullMachineEUt"] == pytest.approx(eut)
        assert step["fullMachineEUt"] == math.ceil(eut)
        assert result["integerBuildPeakEUt"] == math.ceil(eut)
        assert result["modeledTotalEU"] == pytest.approx(math.ceil(eut) * duration)
    target = "gregtech:gt.metaitem.01@12028"
    args = request(machine())
    args.update(target={"resource": target, "amount": 1}, recipeChoices=[{"resource": target, "recipeId": titanium}])
    with pytest.raises(BridgeError) as caught:
        planner.plan_production(database, args)
    assert caught.value.as_dict()["reasons"] == [
        {"recipeId": titanium, "reason": "insufficient_heat", "requiredHeatK": 2140, "availableHeatK": 1901}]
