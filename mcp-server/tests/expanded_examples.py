"""Contract examples shared by adapter, takeover and actual stdio SDK tests."""

READ_EXAMPLES = {
    "mcb_baubles": {"source": "server", "limit": 2, "candidateInventoryIndex": 9, "includePlayerInventory": True},
    "mcb_quest_progress": {"questId": "0"},
    "mcb_research": {"researchKey": "MOCK_RESEARCH"},
    "mcb_nei_view": {"screenId": "mock-screen"},
    "mcb_bindings": {},
    "mcb_entities": {},
    "mcb_spatial": {"mode": "raycast"},
    "mcb_state_diff": {},
    "mcb_gui_widgets": {"screenId": "mock-screen"},
    "mcb_ae": {"screenId": "mock-screen"},
    "mcb_machine_inspect": {"x": 0, "y": 64, "z": 0},
    "mcb_waila": {},
}
ACTION_EXAMPLES = {
    "mcb_quest_step": {"action":"checkbox","questId":"1","taskId":0,"expectedSessionId":"mock-session","worldFolder":"test","dimension":0},
    "mcb_navigate": {"x": 1.5, "y": 64, "z": 0.5, "dimension": 0, "expectedWorld": "mock-world", "expectedSessionId": "mock-session"},
    "mcb_open_baubles": {},
    "mcb_virtual_mouse": {"screenId": "mock-screen", "expectedSessionId": "mock-session", "action": "move", "x": 10, "y": 10},
    "mcb_gui_pointer": {"screenId": "mock-screen", "action": "set", "source": "recipe", "recipeIndex": 0},
    "mcb_flight": {"action": "set", "flying": True, "expectedWorld": "mock-world"},
    "mcb_nei_open": {"screenId": "mock-screen", "mode": "crafting", "source": "inventory", "slot": 0},
    "mcb_nei_navigate": {"screenId": "mock-screen", "action": "back"},
    "mcb_nei_item_action": {"screenId": "mock-screen", "action": "search", "text": "@GregTech circuit"},
    "mcb_nei_preview_control": {"screenId": "mock-screen", "contextToken": "mock-nei-context", "action": "set_channel", "channel": "glass", "value": 2},
    "mcb_ae_action": {"screenId": "mock-screen", "action": "terminal_search", "text": "@GregTech circuit"},
    "mcb_gui_widget_action": {"screenId": "mock-screen", "widgetId": "widget-1", "action": "activate"},
}
