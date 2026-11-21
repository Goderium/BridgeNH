import json
from types import SimpleNamespace

import pytest

from minecraft_codex_bridge.application.adapter import Adapter
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.packdata import bound_database, database, import_exports, query


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def lines(path, rows):
    path.write_text("".join(json.dumps(row, ensure_ascii=False)+"\n" for row in rows), encoding="utf-8")


@pytest.fixture
def catalog(tmp_path):
    instance = tmp_path/"prism"/"sample"
    instance.mkdir(parents=True)
    env = {"MCB_PACKDATA_ROOT": str(tmp_path/"indices")}
    source = tmp_path/"export"
    source.mkdir()
    save(source/"meta.json", {"quests":{"format":1,"lang":"zh_CN","quests":1,"lines":1},
                              "guide":{"format":1,"pages":2}, "nei":{"format":1,"recipes":1,"pages":1}})
    save(source/"questlines.json", {"lines":[{"id":"c","name":"Steam","quests":["q"]}]})
    lines(source/"quests.jsonl", [{"id":"q","name":"马达","en":"Motors","pre":["missing"],"tasks":[],"rewards":[]}])
    lines(source/"guide.jsonl", [{"book":"sample","lang":"en_us","path":"root.md","title":"Root","text":""},
                                 {"book":"sample","lang":"en_us","path":"pump.md","title":"Pump","text":"a"*7000}])
    save(source/"handlers.json", [{"key":"test","name":"Example","read":"gregtech"}])
    lines(source/"recipes.jsonl",[{"h":"test","in":[{"ore":"plateIron","n":1,"any":[["mod:plate",4]],"more":2},
                                  {"id":"mod:mold","meta":32767,"n":0}], "out":[{"id":"mod:pump","meta":0,"n":1}],
                                  "fin":[{"f":"water","mb":1000}],"fake":True}])
    lines(source/"items.jsonl", [{"id":"other:plate","meta":3,"name":"Iron plate","ores":["plateIron"]}])
    import_exports(instance, source, ["quests","guide","nei"], env)
    return instance, env, source, database(instance,env)


def test_search_and_recipe_identity(catalog):
    *_, path = catalog
    def find(**args):
        return query(path,{"action":"search","kind":"recipe", **args})["results"]
    key = find(resource="mod:pump", direction="output", metadata=0)[0]["id"]
    for resource, meta in [("mod:mold",2),("mod:plate",4),("other:plate",3),("ore:plateIron",None),("fluid:water",None)]:
        assert find(resource=resource, direction="input", **({"metadata":meta} if meta is not None else {}))[0]["id"] == key
    assert not find(resource="other:plate",direction="input",metadata=4)
    assert not find(resource="other:plate",direction="output",metadata=3)
    assert not find(resource="mod:pump",direction="input")
    assert not find(resource="mod:pump",direction="output",metadata=1)
    record = query(path,{"action":"get","kind":"recipe","id":key})["record"]
    assert record["fake"] and record["in"][0]["more"] == 2 and record["in"][1]["n"] == 0
    assert query(path,{"action":"status"})["datasets"]["nei"]["handlerReadMethods"] == {"gregtech":1}
    assert query(path,{"action":"search","kind":"quest","query":"motOrS"})["results"][0]["id"] == "q"
    assert query(path,{"action":"get","kind":"chapter","id":"c"})["record"]["quests"] == ["q"]


def test_pages_and_revision(catalog):
    instance, env, source, path = catalog
    first = query(path,{"action":"search","kind":"guide","limit":1})
    assert first["nextOffset"] == 1
    other = query(path,{"action":"search","kind":"guide","limit":1,"offset":1,"expectedRevision":first["revision"]})
    lookup = {"action":"get","kind":"guide","id":other["results"][0]["id"]}
    part = query(path,lookup)
    rest = query(path,{**lookup,"offset": part["nextOffset"]})
    assert json.loads(part["jsonFragment"]+rest["jsonFragment"])["text"] == "a"*7000
    assert rest["nextOffset"] is None
    content = (source/"guide.jsonl").read_text(encoding="utf-8").replace("Pump","Pumps")
    (source/"guide.jsonl").write_text(content,encoding="utf-8")
    import_exports(instance,source,["guide"],env)
    with pytest.raises(BridgeError,match="replaced"):
        query(path,{**lookup,"expectedRevision":first["revision"]})


def test_failed_import_preserves_snapshot(catalog):
    instance, env, source, path = catalog
    old = query(path,{"action":"status"})
    (source/"recipes.jsonl").write_text("",encoding="utf-8")
    with pytest.raises(ValueError,match="mismatch"):
        import_exports(instance,source,["quests","nei"],env)
    assert query(path,{"action":"status"}) == old


def test_removed_guide_leaves_independent_nei_snapshot(catalog):
    instance, env, source, path = catalog
    old_nei = query(path, {})["datasets"]["nei"]
    import_exports(instance, source, ["quests"], env, remove=("guide",))
    datasets = query(path, {})["datasets"]
    assert set(datasets) == {"quests", "nei"}
    assert datasets["nei"] == old_nei


async def test_bound_lookup_without_game_or_discovery(catalog):
    instance, env, _, path = catalog
    cfg = SimpleNamespace(environ=env, conversation_binding={"instancePath":str(instance)})
    assert bound_database(cfg) == path
    adapter = Adapter(SimpleNamespace(configuration=cfg))
    result = await adapter.call_tool("mcb_packdata", {"action":"search","kind":"quest","query":"马达"})
    assert result.structuredContent["data"]["results"][0]["id"] == "q"
    assert result.structuredContent["mcpTiming"]["bridgeCalls"] == 0
    cfg.conversation_binding = {"instancePath":str(instance.parent/"other")}
    assert not (await adapter.call_tool("mcb_packdata", {})).structuredContent["data"]["available"]
    cfg.conversation_binding = {"instancePath":None}
    assert (await adapter.call_tool("mcb_packdata", {})).structuredContent["error"]["code"] == "CLIENT_NOT_BOUND"
