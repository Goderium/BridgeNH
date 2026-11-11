import copy

import pytest

from minecraft_codex_bridge.nbt_order import compound_order_key, context_equal


CLIENT = '{name:"ExamplePlayer",uuid:"00000000-0000-4000-8000-000000000001",trophyID:"assembling_line",time:1700000000000L}'
SERVER = '{name:"ExamplePlayer",uuid:"00000000-0000-4000-8000-000000000001",time:1700000000000L,trophyID:"assembling_line"}'


def test_live_trophy_compound_order_and_raw_receipts_preserved():
    left = [{"index": 13, "item": {"nbtIdentity": CLIENT, "count": 1, "hasTag": True}}]
    right = copy.deepcopy(left)
    right[0]["item"]["nbtIdentity"] = SERVER
    original = copy.deepcopy((left, right))
    assert context_equal(left, right)
    assert (left, right) == original


@pytest.mark.parametrize("left,right", [
    ('{a:{x:1,y:2},b:3}', '{b:3,a:{y:2,x:1}}'),
    ('{a:[0:{x:1,y:2},1:{z:3}],b:4}', '{b:4,a:[0:{y:2,x:1},1:{z:3}]}'),
    ('{a:[{x:1,y:2}],b:"{x,y}:[]"}', '{b:"{x,y}:[]",a:[{y:2,x:1}]}'),
    ('{a:"quoted\\\"value",b:"slash\\\\value"}', '{b:"slash\\\\value",a:"quoted\\\"value"}'),
])
def test_nested_compound_order_only(left, right):
    assert context_equal({"nbtIdentity": left}, {"nbtIdentity": right})


@pytest.mark.parametrize("left,right", [
    ('{a:1,b:2}', '{a:1L,b:2}'),
    ('{a:9007199254740992L}', '{a:9007199254740993L}'),
    ('{a:"1"}', '{a:1}'),
    ('{a:[1,2]}', '{a:[2,1]}'),
    ('{a:[0:1,1:2]}', '{a:[1:1,0:2]}'),
    ('{a:"x,y"}', '{a:"y,x"}'),
    ('{a:" x"}', '{a:"x"}'),
    ('{a:1,a:2}', '{a:2,a:1}'),
    ('{a:1,"a":2}', '{"a":2,a:1}'),
    ('{a:1,b:2} trailing', '{b:2,a:1} trailing'),
    ('{a:1,b:}', '{b:,a:1}'),
    ('{a:[B;1B,2B],b:2}', '{b:2,a:[B;2B,1B]}'),
])
def test_real_changes_and_ambiguous_inputs_remain_distinct(left, right):
    assert not context_equal({"nbtIdentity": left}, {"nbtIdentity": right})


@pytest.mark.parametrize("text", [
    '{a:1,a:2}', '{a:1,"a":2}', '{a:}', '{a:"unclosed}',
    '{a:"unsupported\\n"}', '{a:1} trailing',
    '{a:' * 34 + '1' + '}' * 34,
    '{a:[' + ','.join('1' for _ in range(4097)) + ']}',
    '{a:"' + 'x' * 65536 + '"}',
], ids=["duplicate", "quoted-duplicate", "missing-value", "unclosed-string", "escape", "trailing", "depth", "nodes", "characters"])
def test_unsupported_or_over_budget_text_is_opaque(text):
    assert compound_order_key(text) == ("opaque", text)


@pytest.mark.parametrize("field,value", [("count", 2), ("count", True), ("hasTag", False), ("registryName", "example:other"), ("metadata", 1)])
def test_non_nbt_identity_fields_are_still_exact(field, value):
    left = dict(nbtIdentity=CLIENT, count=1, hasTag=True, registryName="example:trophy", metadata=0)
    right = dict(left, nbtIdentity=SERVER)
    right[field] = value
    assert not context_equal(left, right)


def test_no_normalization_of_other_text_fields_or_slot_order():
    assert not context_equal({"other": CLIENT}, {"other": SERVER})
    assert not context_equal([{"index": 1}, {"index": 2}], [{"index": 2}, {"index": 1}])


def test_ae_cell_hash_keys_are_compound_members_not_list_indices():
    left = '{list:{#9:{id:7639s,Cnt:1L},#0:{id:7638s,Cnt:1L}}}'
    right = '{list:{#0:{Cnt:1L,id:7638s},#9:{Cnt:1L,id:7639s}}}'
    assert compound_order_key(left)[0] != "opaque"
    assert context_equal({"nbtIdentity": left}, {"nbtIdentity": right})
    for changed in (right.replace("#9", "#8"), right.replace("7639s", "7638s"), right.replace("Cnt:1L", "Cnt:2L")):
        assert not context_equal({"nbtIdentity": left}, {"nbtIdentity": changed})
