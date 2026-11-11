"""Use observed NEI identities within one in-memory sequence, without agent round trips."""

import pytest

from minecraft_codex_bridge.workflows import project
from test_workflows import bridge, operations, plan


@pytest.mark.parametrize("tool,args", [
    ("mcb_ae_action", {"screenId": "workbench", "action": "cell_config_set", "slot": 45}),
    ("mcb_gui_widget_action", {"screenId": "workbench", "action": "ghost_drag", "widgetId": "widget:1"}),
])
async def test_panel_ghost_source_can_bind_into_native_configuration_in_one_sequence(bridge, tool, args):
    adapter, state = bridge
    state["data"]["nei.view"] = {"screenId": "workbench", "data": {"entries": [
        {"item": {"registryName": "mod:item", "metadata": 7},
         "neiItemId": "nei-item:observed", "ghostSource": {"neiItemId": "nei-item:observed"}}]}}
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "panel", "kind": "read", "tool": "mcb_nei_view", "args": {
            "screenId": "workbench", "section": "panel", "scope": "all", "limit": 1}},
        {"id": "mark", "kind": "action", "tool": tool, "args": args, "bind": {"source": {
            "step": "panel", "path": "data.entries", "match": {"item.registryName": "mod:item", "item.metadata": 7}, "field": "ghostSource"}}}))
    assert not result.isError
    operation = "ae.action" if tool == "mcb_ae_action" else "gui.widget_action"
    mutations = [call for call in state["calls"] if call["operation"] == operation]
    assert len(mutations) == 1 and mutations[0]["args"] == {**args, "source": {"neiItemId": "nei-item:observed"}}
    assert "inventory.supply" not in operations(state) and "chat.send" not in operations(state)


async def test_bad_bound_source_fails_before_mutation(bridge):
    adapter, state = bridge
    state["data"]["nei.view"] = {"data": {"source": {"neiItemId": "nei-item:1", "nbt": "untrusted"}}}
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "panel", "kind": "read", "tool": "mcb_nei_view", "args": {"screenId": "workbench"}},
        {"id": "mark", "kind": "action", "tool": "mcb_ae_action", "args": {
            "screenId": "workbench", "action": "cell_config_set", "slot": 45},
         "bind": {"source": {"step": "panel", "path": "data.source"}}}))
    assert result.isError and "ae.action" not in operations(state)


def test_default_compact_receipts_preserve_acquisition_and_configuration_caveats():
    take = {"serverConfirmed": False, "acquisitionVerified": False, "inventoryCountBefore": "1", "expectedInventoryDelta": 1}
    cell = {"before": None, "target": {"amount": "1"}, "configurationOnly": True,
            "inventoryConsumed": False, "syncFlushInvoked": True, "serverAcceptance": "pending", "serverConfirmed": False}
    assert project(take, {"kind": "action", "tool": "mcb_nei_item_action"})["values"] == take
    assert project(cell, {"kind": "action", "tool": "mcb_ae_action"})["values"] == cell


@pytest.mark.parametrize("tool", ["mcb_nei_item_action", "mcb_ae_action", "mcb_gui_widget_action"])
def test_compact_receipts_preserve_release_success_and_cleanup_failure(tool):
    for release in [
        {"available": True, "released": True, "ghostPresentAfter": False, "realCursorPreserved": True},
        {"available": False, "released": False, "reason": "unsupported", "recovery": "never replay marking"},
    ]:
        receipt = {"submitted": True, "ghostRelease": release}
        assert project(receipt, {"kind": "action", "tool": tool})["values"] == receipt


async def test_release_joins_existing_sequence_without_extra_click_or_inventory_actions(bridge):
    adapter, state = bridge
    state["data"]["nei.item_action"] = {"submitted": True, "ghostRelease": {
        "available": True, "released": True, "ghostPresentAfter": False, "realCursorPreserved": True}}
    result = await adapter.call_tool("mcb_sequence", plan(
        {"id": "release", "kind": "action", "tool": "mcb_nei_item_action", "args": {"screenId": "workbench", "action": "release"}}))
    assert not result.isError
    assert operations(state).count("nei.item_action") == 1
    assert not any(op in operations(state) for op in ("gui.click", "gui.slot_click", "inventory.supply", "chat.send"))
