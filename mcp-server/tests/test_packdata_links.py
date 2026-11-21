import json
from hashlib import sha256

import pytest

from minecraft_codex_bridge import packdata
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.packdata_inputs import capture_inputs, check_install, write_export_inputs


def write(path, data):
    path.write_text(json.dumps(data), encoding="utf-8")


def records(path, data):
    path.write_text("".join(json.dumps(row)+"\n" for row in data), encoding="utf-8")


@pytest.fixture
def export(tmp_path):
    instance = tmp_path/"instance"
    mods = instance/".minecraft"/"mods"
    mods.mkdir(parents=True)
    (mods/"example.jar").write_bytes(b"v1")
    root = tmp_path/"export"
    root.mkdir()
    write(root/"meta.json", {"nei":{"format":1,"lang":"en_US","structures":1}, "tooltips":{"lang":"zh_CN"}})
    item = {"id":"example:controller", "meta":4}
    write(root/"handlers.json",[{"key":"example.bender","machines":[item]}])
    records(root/"recipes.jsonl",[{"h":"example.bender","in":[dict(item,n=1)],"out":[{"id":"example:plate","n":1,"meta":0}]}])
    records(root/"items.jsonl",[dict(item,name="Bender")])
    records(root/"structures.jsonl",[dict(item,tiers=[3],blocks=[{"id":"example:casing","meta":0}])])
    records(root/"carving.jsonl",[{"group":"panels","variants":[item]}])
    records(root/"tooltips.jsonl",[dict(item,tip=["Base"],shift=["More"]),dict(item,mode="ctrl",language="en_US",lines=["Ctrl"])])
    records(root/"blocks.jsonl",[item,item])
    return instance,root,{"MCB_PACKDATA_ROOT":str(tmp_path/"db")}


def test_linked_catalog_and_tooltip_modes(export):
    instance,root,env=export
    packdata.import_exports(instance, root, ["nei"], env)
    db=packdata.database(instance,env)
    result=packdata.query(db,{"action":"related","resource":"example:controller","metadata":4})
    links=result["links"]
    assert links["handler"]["records"][0]["roles"] == ["machine_handler"]
    assert links["structure"]["records"][0]["id"]=="example:controller@4:3"
    assert links["carving"]["records"][0]["id"]=="panels"
    assert len(links["block"]["records"])==1
    assert {r["id"] for r in links["tooltip"]["records"]}=={
        "example:controller@4:normal:zh_CN", "example:controller@4:shift:zh_CN","example:controller@4:ctrl:en_US"}
    assert packdata.query(db,{"action":"search","kind":"recipe","handler":links["handler"]["records"][0]["id"]})["results"]
    assert not packdata.query(db,{"action":"search","kind":"recipe","handler":"example.oven"})["results"]
    assert not packdata.query(db,{"action":"related","resource":"example:controller","metadata":5,"kind":"structure"})["links"]["structure"]["records"]
    with pytest.raises(BridgeError,match="changed"):
        packdata.query(db,{"action":"related","resource":"example:controller","expectedRevisions":{"nei":"0"*64}})


def test_capture_freshness_is_revision_bound(export):
    instance,root,env=export
    texture_cache = instance / ".minecraft/mods/carpentersblocks/CarpentersBlocksCachedResources.zip"
    texture_cache.parent.mkdir()
    texture_cache.write_bytes(b"old atlas")
    before=capture_inputs(instance,["nei"])
    texture_cache.write_bytes(b"regenerated atlas")
    assert capture_inputs(instance,["nei"]) == before
    write_export_inputs(root,before,before)
    packdata.import_exports(instance,root,["nei"],env)
    assert check_install(instance,["nei"],env)["datasets"]["nei"]["status"]=="matched"
    (instance/".minecraft/mods/example.jar").write_bytes(b"v2")
    checked=check_install(instance,["nei"],env)["datasets"]["nei"]
    assert checked["status"]=="refresh_recommended" and checked["modified"]["count"]==1
    status = packdata.query(packdata.database(instance,env),{})
    assert status["refreshRecommended"] == ["nei"] and "fetch-nei" in checked["next"]
    notice = status["inputChecks"]["nei"]
    assert notice["checkedAt"] and notice["status"] == "refresh_recommended" and notice["modified"] == checked["modified"]
    changed=capture_inputs(instance,["nei"])
    with pytest.raises(ValueError,match="changed during export"):
        write_export_inputs(root,before,changed)
    other=instance.parent/"other"
    (other/".minecraft").mkdir(parents=True)
    with pytest.raises(ValueError,match="another instance"):
        packdata.import_exports(other,root,["nei"],env)


def test_original_import_command_reports_the_audited_publication(export, monkeypatch, capsys):
    instance, root, env = export
    monkeypatch.setenv("MCB_PACKDATA_ROOT", env["MCB_PACKDATA_ROOT"])
    args = ["import", "--instance", str(instance), "--source", str(root), "--datasets", "nei"]
    packdata.main(args)
    first = json.loads(capsys.readouterr().out)
    catalog = packdata.database(instance, env)
    assert first["datasets"]["nei"]["recipe"] and first["database"] == str(catalog)
    assert first["updateId"] == packdata.query(catalog, {})["lastUpdate"]["id"]
    packdata.main(args)
    second = json.loads(capsys.readouterr().out)
    assert second["updateId"] is None and second["datasets"] == first["datasets"]


def test_status_distinguishes_first_publication_from_a_new_content_change(export):
    from minecraft_codex_bridge.packdata_updates import maintain
    instance, root, env = export
    catalog = packdata.database(instance, env)
    initial = maintain(instance, {"nei": root}, env=env)
    first = packdata.query(catalog, {"action": "status", "knownUpdateId": initial["updateId"]})
    assert first["lastUpdate"]["newBaselines"] == ["nei"]
    assert first["lastUpdate"]["changedSinceKnown"] is False
    items = [json.loads(line) for line in (root / "items.jsonl").read_text().splitlines()]
    items[0]["name"] = "Updated item name"
    records(root / "items.jsonl", items)
    changed = maintain(instance, {"nei": root}, env=env)
    notice = packdata.query(catalog, {"action": "status", "knownUpdateId": initial["updateId"]})["lastUpdate"]
    assert notice["changedSinceKnown"] and notice["id"] == changed["updateId"]
    assert notice["changes"]["nei"]["item"] == {"modified": 1} and not notice["newBaselines"]
    items[0]["name"] = "Second change"
    records(root / "items.jsonl", items)
    latest = maintain(instance, {"nei": root}, env=env)
    window = packdata.query(catalog, {"action": "status", "knownUpdateId": initial["updateId"]})["updatesSinceKnown"]
    assert window["knownUpdateFound"] and not window["hasMore"]
    assert [n["id"] for n in window["updates"]] == [latest["updateId"], changed["updateId"]]
    assert not packdata.query(catalog, {"knownUpdateId": "unknown"})["updatesSinceKnown"]["knownUpdateFound"]


def test_quest_inputs_track_the_selected_translation_and_static_definitions(export):
    instance, root, env = export
    game = instance / ".minecraft"
    book = game / "config/betterquesting/DefaultQuests"
    translations = game / "config/txloader/forceload/betterquesting/lang"
    (book / "Quests").mkdir(parents=True)
    translations.mkdir(parents=True)
    write(book / "Quests/example.json", {"id": 1})
    en = translations / "en_US.lang"
    en.write_text("title=Test", encoding="utf-8")
    proof = capture_inputs(instance, ["quests"], quest_language="en_US")
    write_export_inputs(root, proof, proof)
    meta = json.loads((root / "meta.json").read_text())
    meta["quests"] = {"format": 1, "lang": "en_US", "quests": 1, "lines": 1}
    write(root / "questlines.json", {"lines": [{"id": "c1", "name": "Examples"}]})
    records(root / "quests.jsonl", [{"id": "q1", "name": "Example"}])
    write(root / "meta.json", meta)
    packdata.import_exports(instance, root, ["quests"], env)
    write(book / "QuestProgress.json", {"player": 1})
    (translations / "zh_CN.lang").write_text("title=例", encoding="utf-8")
    assert check_install(instance, ["quests"], env)["datasets"]["quests"]["status"] == "matched"
    en.write_text("title=Updated", encoding="utf-8")
    notice = check_install(instance, ["quests"], env)["datasets"]["quests"]
    assert notice["modified"]["examples"] == [en.relative_to(instance).as_posix()]


def test_registry_snapshot_stays_independent_and_preserves_quantities(export):
    pytest.importorskip("ijson")
    from minecraft_codex_bridge.recex_packdata import import_recex
    instance,root,env=export
    packdata.import_exports(instance,root,["nei"],env)
    db=packdata.database(instance,env)
    before=packdata.query(db,{})["datasets"]["nei"]["revision"]
    raw=root/"registry.json"
    record={"iI":[{"id":"example:dust","m":0,"a":2}],"iO":[{"id":"example:plate","m":0,"a":1}],
            "fI":[{"id":"water","a":144}],"eut":8,"dur":16}
    write(raw,{"sources":[{"machines":[{"recs":[record],"n":"Example"}],"type":"gregtech"}]})
    import_recex(instance,raw,env)
    provenance = packdata.query(db,{})["datasets"]["recex"]["export"]
    assert provenance["rawSha256"] == sha256(raw.read_bytes()).hexdigest()
    assert provenance["recordsByProvider"] == {"gregtech": 1}
    registry=packdata.query(db,{"action":"search","resource":"fluid:water","kind":"registry_recipe","direction":"input"})
    details=packdata.query(db,{"action":"get","kind":"registry_recipe","id":registry["results"][0]["id"]})
    assert details["record"]["recipe"]["fI"]==[{"id":"water","a":144}]
    assert packdata.query(db,{})["datasets"]["nei"]["revision"]==before
    assert not packdata.query(db,{"action":"search","kind":"recipe","resource":"fluid:water"})["results"]
