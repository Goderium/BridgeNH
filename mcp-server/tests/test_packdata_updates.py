"""The updater compares observed content and coverage, independently of export order."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import importlib.util

import pytest

from minecraft_codex_bridge import packdata, packdata_inputs, packdata_updates as updates
from minecraft_codex_bridge.bridge import BridgeError


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")


def lines(path, values):
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in values), encoding="utf-8")


def rows(path):
    return [json.loads(s) for s in path.read_text(encoding="utf-8").splitlines()]


def recipe(result="test:plate", amount=1, power=30):
    return {"h": "metal", "in": [{"id": "test:mold", "meta": 1, "n": 0},
                                 {"n": 2, "ore": "ingotIron", "any": [["test:ingot", 0], ["example:ingot", 0]]}],
            "out": [{"id": result, "meta": 0, "n": amount, "chance": 0.75}],
            "ticks": 32, "eut": power}


@pytest.fixture
def sample(tmp_path):
    instance = tmp_path / "prism" / "sample"
    (instance / ".minecraft").mkdir(parents=True)
    source = tmp_path / "input"
    source.mkdir()
    save(source / "meta.json", {"quests": {"format": 1, "quests": 1, "lines": 2, "lang": "en_US"},
         "guide": {"format": 1, "pages": 1}, "nei": {"format": 1, "recipes": 3, "pages": 1, "lang": "en_US"}})
    save(source / "questlines.json", {"lines": [{"id": key, "name": key, "quests": ["quest"]} for key in ("a", "b")]})
    lines(source / "quests.jsonl", [{"id": "quest", "name": "First plate", "pre": ["a", "b"],
                                     "tasks": [{"type": "retrieval", "items": [{"id": "test:plate", "n": 1}]}]}])
    lines(source / "guide.jsonl", [{"book": "metal", "lang": "en_us", "path": "plate.md", "title": "Plate", "text": "Original"}])
    save(source / "handlers.json", [{"key": "metal", "name": "Metal", "grid": True, "ms": 1, "read": "gregtech"}])
    lines(source / "recipes.jsonl", [recipe(), recipe(), recipe("test:wire")])
    lines(source / "items.jsonl", [{"id": "test:plate", "meta": 0, "name": "Plate", "ores": ["metal", "plateIron"]}])
    lines(source / "tooltips.jsonl", [{"id": "test:plate", "meta": 0, "mode": mode, "language": "en_us",
                                       "lines": [text]} for mode, text in (("normal", "Iron plate"), ("shift", "Press"))])
    env = {"MCB_PACKDATA_ROOT": str(tmp_path / "catalog")}
    return instance, source, env, packdata.database(instance, env)


def run(sample, mode="update", datasets=("quests", "guide", "nei"), **options):
    instance, source, env, _ = sample
    return updates.maintain(instance, dict.fromkeys(datasets, source), mode=mode, env=env, **options)


def test_initial_legacy_links_unchanged_and_per_instance_selection(sample, tmp_path):
    instance, source, env, db = sample
    checked = run(sample, "check")
    assert all(r["needsUpdate"] for r in checked["datasets"].values()) and not db.exists()
    report = run(sample)
    assert report["updateId"] and set(report["datasets"]["nei"]["missingOptional"]) == {"block", "structure", "carving"}
    assert run(sample)["updateId"] is None
    assert len(updates.query_updates(db, {"action": "update_status"})["results"]) == 1
    with sqlite3.connect(db) as connection:
        previous = json.loads(connection.execute("SELECT info FROM sources WHERE dataset='guide'").fetchone()[0])
        del previous["itemLinksVersion"]
        connection.execute("UPDATE sources SET info=? WHERE dataset='guide'", (json.dumps(previous),))
    assert run(sample, datasets=("guide",))["updateId"] and not run(sample, "check")["datasets"]["guide"]["needsUpdate"]
    other = tmp_path / "other-instance"
    (other / ".minecraft").mkdir(parents=True)
    assert not updates.query_updates(packdata.database(other, env), {"action": "update_status"})["available"]
    save(source / "nei-provenance.json", {"source": "fixture", "instance": other.name})
    with pytest.raises(ValueError, match="another instance"):
        run(sample)


def test_recipe_order_keys_sets_handler_timing_and_book_row_noise(sample):
    _, source, _, _ = sample
    run(sample)
    recipes = rows(source / "recipes.jsonl")
    for row in recipes:
        row["in"][1]["any"].reverse()
        row["in"].reverse()
    lines(source / "recipes.jsonl", [dict(reversed(list(row.items()))) for row in reversed(recipes)])
    handlers = json.loads((source / "handlers.json").read_text())
    handlers[0]["ms"] = 903
    save(source / "handlers.json", handlers)
    quest = rows(source / "quests.jsonl")
    quest[0]["pre"].reverse()
    lines(source / "quests.jsonl", quest)
    items = rows(source / "items.jsonl")
    items[0]["ores"].reverse()
    lines(source / "items.jsonl", items)
    lines(source / "tooltips.jsonl", reversed(rows(source / "tooltips.jsonl")))
    diff = run(sample, "compare")
    assert not diff["datasets"]["nei"]["changes"] and not diff["datasets"]["quests"]["changes"]
    assert run(sample, "check")["datasets"]["nei"]["needsUpdate"]


def test_duplicate_recipe_counts_and_real_semantics_across_sources(sample, tmp_path):
    _, source, _, db = sample
    run(sample)
    all_rows = rows(source / "recipes.jsonl")
    all_rows[0]["out"][0]["n"] = 3
    all_rows[0]["in"][0]["n"] = 1
    all_rows[0]["out"][0]["chance"] = 0.5
    all_rows[0]["fake"] = True
    all_rows[0]["ticks"] = 64
    all_rows[0]["eut"] = 120
    all_rows[2] = recipe("test:sheet")
    lines(source / "recipes.jsonl", reversed(all_rows))
    guide = rows(source / "guide.jsonl")
    guide[0]["text"] = "Updated"
    lines(source / "guide.jsonl", guide)
    quest = rows(source / "quests.jsonl")
    quest[0]["tasks"][0]["items"][0]["n"] = 2
    lines(source / "quests.jsonl", quest)
    chapters = json.loads((source / "questlines.json").read_text())
    chapters["lines"].reverse()
    save(source / "questlines.json", chapters)
    tips = rows(source / "tooltips.jsonl")
    tips[1]["lines"] = ["First", "Second"]
    lines(source / "tooltips.jsonl", tips)
    audit = tmp_path / "changes.jsonl"
    diff = run(sample, "compare", audit_file=audit)
    assert diff["datasets"]["nei"]["changes"]["recipe"] == {"added": 1, "removed": 1, "modified": 1}
    assert diff["datasets"]["quests"]["changes"] == {"chapter": {"modified": 1}, "quest": {"modified": 1}}
    assert diff["datasets"]["guide"]["changes"] == {"guide": {"modified": 1}}
    changed = [r for r in rows(audit)[1:] if r["kind"] == "recipe" and r["change"] == "modified"][0]
    assert set(changed["fields"]) == {"ticks", "eut", "fake", "in", "out"}
    assert changed["before"]["count"] == changed["after"]["count"] == 1
    assert len(updates.query_updates(db, {"action": "update_status"})["results"]) == 1
    committed = run(sample)
    assert committed["datasets"]["nei"]["changes"] == diff["datasets"]["nei"]["changes"]
    selection = {"action": "changes", "updateId": committed["updateId"], "kind": "tooltip", "change": "modified"}
    view = updates.query_updates(db, selection)["results"]
    assert len(view) == 1 and view[0]["identity"] == "test:plate@0:shift:en_us" and view[0]["fields"] == ["lines"]
    with pytest.raises(BridgeError, match="Unknown update"):
        updates.query_updates(db, {"action": "changes", "updateId": diff["updateId"]})


def test_ambiguous_variants_stay_unpaired_and_loss_of_file_reports_unknown_coverage(sample):
    _, source, _, db = sample
    lines(source / "recipes.jsonl", [recipe(power=1), recipe(power=2), recipe("test:wire")])
    run(sample)
    lines(source / "recipes.jsonl", [recipe(power=3), recipe(power=4), recipe("test:wire")])
    (source / "tooltips.jsonl").unlink()
    report = run(sample)
    assert report["datasets"]["nei"]["changes"]["recipe"] == {"added": 2, "removed": 2}
    assert "tooltip" not in report["datasets"]["nei"]["changes"]
    assert report["datasets"]["nei"]["coverage"]["tooltip"] == {"available": False, "rows": None}
    delta = updates.query_updates(db, {"action": "changes", "kind": "coverage"})["results"]
    assert any(r["identity"] == "tooltip" and r["before"]["rows"] == 2 for r in delta)


def test_transaction_rolls_back_all_datasets_and_audit_on_invalid_later_dataset(sample):
    _, source, _, db = sample
    run(sample)
    before = updates.query_updates(db, {"action": "update_status"})
    guide = rows(source / "guide.jsonl")
    guide[0]["title"] = "Bad batch"
    lines(source / "guide.jsonl", guide)
    lines(source / "recipes.jsonl", [recipe()])
    with pytest.raises(ValueError, match="count mismatch"):
        run(sample)
    assert updates.query_updates(db, {"action": "update_status"}) == before
    assert packdata.query(db, {"action": "get", "kind": "guide", "id": "metal:en_us:plate.md"})["record"]["title"] == "Plate"
    (source / "recipes.jsonl").unlink()
    with pytest.raises(ValueError, match="required export missing"):
        run(sample, "check")
    with pytest.raises(ValueError, match="cannot replace"):
        run(sample, audit_file=source / "guide.jsonl")


def test_simultaneous_updates_serialize_and_a_later_noop_preserves_refs(sample):
    _, _, _, db = sample
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: run(sample), range(2)))
    assert sum(r["updateId"] is not None for r in results) == 1
    assert len(updates.query_updates(db, {"action": "update_status"})["results"]) == 1


def test_absent_installed_guides_retires_only_guide_coverage(sample):
    instance, _, env, db = sample
    run(sample)
    result = updates.maintain(instance, {"guide": None}, env=env)
    assert result["datasets"]["guide"]["after"] is None
    assert set(packdata.query(db, {"action": "status"})["datasets"]) == {"nei", "quests"}
    assert updates.query_updates(db, {"action": "changes"})["results"][0]["identity"] == "guide"


def test_capture_proof_changes_transactionally_without_inventing_old_provenance(sample):
    instance, source, env, _ = sample
    run(sample)
    proof = packdata_inputs.capture_inputs(instance, ["quests", "guide", "nei"])
    packdata_inputs.write_export_inputs(source, proof, proof)
    assert all(v["needsUpdate"] for v in run(sample,"check")["datasets"].values())
    assert run(sample)["datasets"]["nei"]["after"]["inputSignature"]["files"] == 0
    assert not run(sample, "check")["datasets"]["nei"]["needsUpdate"]
    assert packdata_inputs.check_install(instance,["nei"],env)["datasets"]["nei"]["status"] == "matched"
    (source/"export-inputs.json").unlink()
    assert run(sample, "check")["datasets"]["nei"]["needsUpdate"]
    run(sample)
    assert packdata_inputs.check_install(instance,["nei"],env)["datasets"]["nei"]["status"] == "unverified_export_inputs"


def test_native_publish_preserves_any_untested_previous_tooltip_and_rolls_back(sample):
    _, source, _, db = sample
    initial = run(sample)
    samples = rows(source/"tooltips.jsonl")
    lines(source/"tooltips.jsonl", samples[:1])
    with pytest.raises(ValueError, match="previously sampled"):
        run(sample, preserve_coverage=True)
    (source/"tooltips.jsonl").unlink()
    with pytest.raises(ValueError, match="previously covered"):
        run(sample, preserve_coverage=True)
    assert updates.query_updates(db, {"action":"update_status"})["results"][0]["updateId"] == initial["updateId"]
    assert packdata.query(db, {"action":"get", "kind":"tooltip", "id":"test:plate@0:shift:en_us"})["record"]["lines"] == ["Press"]


def native_module():
    spec = importlib.util.spec_from_file_location("packdata_nei", Path(__file__).resolve().parents[2]/"scripts/packdata_nei.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_native_completion_binds_new_request_totals_and_handler_counts(sample):
    _, source, _, _ = sample
    mod = native_module()
    save(source/"meta.json", {"nei":{"format":1, "recipes":3, "pages":1, "items":1}})
    save(source/"handlers.json", [{"key":"metal","recipes":3,"read":"gregtech"}])
    (source/"export.ok").write_text("old")
    with pytest.raises(ValueError, match="matching"):
        mod._verify(source,"new")
    (source/"export.ok").write_text("new")
    assert mod._verify(source,"new")["handlerReadMethods"] == {"gregtech":1}
    save(source/"handlers.json", [{"key":"metal","recipes":2}])
    with pytest.raises(ValueError, match="declared row count"):
        mod._verify(source,"new")
    save(source/"handlers.json", [{"key":"another","recipes":3}])
    with pytest.raises(ValueError, match="identities"):
        mod._verify(source,"new")


def test_bounded_tooltip_merge_replaces_only_measured_mode_and_keeps_missing_observations(sample):
    _, source, _, _ = sample
    mod = native_module()
    save(source/"meta.json", {"nei":{"format":1,"lang":"en_us"}})
    save(source/"tooltip-sample-report.json", {"samples":1,"lang":"en_us","missing":[{"item":"test:plate@0","mode":"ctrl"}]})
    before = rows(source/"tooltips.jsonl")
    lines(source/"tooltip-samples.jsonl", [{**before[1], "lines":["Changed Shift"]}])
    coverage = mod._merge_samples(source)
    assert coverage["missing"][0]["mode"] == "ctrl"
    assert rows(source/"tooltips.jsonl") == [before[0], {**before[1],"lines":["Changed Shift"]}]
    assert mod._selections(["test:plate@0"]*2, ["ctrl","normal","ctrl"]) == (["test:plate@0"], ["ctrl","normal"])
    with pytest.raises(ValueError, match="identifiers"):
        mod._selections(["test:plate@70000"], ["normal"])


def test_only_illustrative_furnace_slots_and_fuel_example_pairs_are_excluded():
    smelting = {"h":"codechicken.nei.recipe.FurnaceRecipeHandler","in":[{"id":"ore"}],
                "out":[{"id":"ingot","n":1}],"other":[{"id":"stick"}]}
    other_fuel = {**deepcopy(smelting),"other":[{"id":"planks"}]}
    assert updates._normalize("recipe",deepcopy(smelting),set()) == updates._normalize("recipe",other_fuel,set())
    different_quantity = deepcopy(smelting)
    different_quantity["out"][0]["n"] = 2
    assert updates._normalize("recipe",deepcopy(smelting),set()) != updates._normalize("recipe",different_quantity,set())
    example = {**deepcopy(smelting), "h":"codechicken.nei.recipe.FuelRecipeHandler"}
    same_fuel = {**deepcopy(example), "in":[{"id":"another-ore"}], "out":[{"id":"another-ingot"}]}
    assert updates._normalize("recipe",example,set()) == updates._normalize("recipe",same_fuel,set())
    assert updates._normalize("recipe",{**deepcopy(smelting),"h":"custom"},set())["other"] == [{"id":"stick"}]


def test_prism_wait_tolerates_jvm_handoff_and_checks_native_completion_and_quiet_exit(tmp_path, monkeypatch):
    mod = native_module()
    now = [0]
    timeline = {4:True,6:False,8:True,10:True,12:True,14:False,16:True}
    def running(platform,setting,out):
        t = now[0]
        if t == 8:
            (out/"runner.started").write_text("new")
        if t == 12:
            (out/"export.ok").write_text("new")
        return timeline.get(t,False)
    monkeypatch.setattr(mod,"_running",running)
    def sleep(n):
        now[0] += n
    mod._await_exit(None,None,tmp_path,"new",30,lambda:now[0],sleep)
    assert now == [24]
