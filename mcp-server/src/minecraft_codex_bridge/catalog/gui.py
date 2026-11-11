"""Gui tool schemas and native operation declarations."""

from .common import (
    DIMENSION,
    EXPECTED_SESSION,
    EXPECTED_WORLD,
    LIVE_ID,
    POSITION,
    SCREEN_ID,
    TEXT_INPUT,
    ToolSpec,
    integer,
    named_actions,
    obj,
)
from .nei import (
    GHOST_SOURCE_SCHEMA,
)


T_MCB_WINDOW = ToolSpec("mcb_window", "client.window", "Read actual client/framebuffer dimensions and native window flags. window.mode distinguishes windowed, maximized, undecorated_windowed, borderless_fullscreen and game_fullscreen; gameFullscreen and config preferences are separate. Missing SDL/lwjgl3ify support is explicit unknown. No focus, resize or settings changes.", obj(), True)

T_MCB_WINDOW_ACTION = ToolSpec("mcb_window_action", "client.window_action", "Control the observed runtime SDL window without OS input or restart. Supply current health session and mcb_window.window identity/mode. maximize/restore use native window requests and flag readback, and refuse fullscreen; windowed explicitly leaves fullscreen via Minecraft's normal method without sending F11. No implicit fullscreen transition, borderless preference edit or configuration save. confirmed is actual native readback; framebuffer resize follows normal events. Re-read after an unconfirmed result, never blindly replay.", obj({"expectedSessionId": EXPECTED_SESSION, "expectedWindowId": {"type": "string", "minLength": 1, "maxLength": 64}, "expectedMode": {"enum": ["windowed", "maximized", "undecorated_windowed", "borderless_fullscreen", "game_fullscreen"]}, "action": {"enum": ["maximize", "restore", "windowed"]}}, ["expectedSessionId", "expectedWindowId", "expectedMode", "action"]), idempotent=False)


GUI_ACCEPTANCE_SCHEMA = obj({
    "timeoutMillis": {**integer(1, 6500), "default": 2000},
    "conditions": {"type": "array", "maxItems": 8, "items": obj({
        "path": {"type": "string", "maxLength": 128,
                 "pattern": r"^(?:slots|visibleText|widgets|containerClass|totalSlots)(?:\.[A-Za-z0-9_-]+)*$"},
        "operator": {"enum": ["equals", "contains"]},
        "value": {"type": ["string", "number", "boolean"], "maxLength": 1024}}, ["path", "operator", "value"]) |
        {"if": {"properties": {"operator": {"const": "contains"}}}, "then": {"properties": {"value": {"type": "string"}}}}}})


SLOT_CLICK_SCHEMA = {
    **obj({"windowId": integer(0, 32_767), "slot": {"type": "integer", "anyOf": [{"const": -999}, {"minimum": 0, "maximum": 4_095}]}, "button": integer(0, 8), "mode": {"type": "integer", "enum": [0, 1, 2, 3, 4, 6]}, "swapStrategy": {"type": "string", "enum": ["auto", "native", "pickup"]}}, ["windowId", "slot"]),
    "allOf": [{"if": {"required": ["swapStrategy"]}, "then": {"required": ["mode"], "properties": {"mode": {"const": 2}}}}],
    "oneOf": [
        {"properties": {"mode": {"enum": [0, 1, 4, 6]}, "button": integer(0, 1)}},
        {"properties": {"mode": {"const": 2}, "button": integer(0, 8)}, "required": ["mode"]},
        {"properties": {"mode": {"const": 3}, "button": {"const": 2}}, "required": ["mode", "button"]},
    ],
}


GUI_TOOLTIP_SCHEMA = {
    **obj({"screenId": SCREEN_ID, "slot": integer(0, 2_147_483_647), "x": integer(0, 2_147_483_647), "y": integer(0, 2_147_483_647), "advanced": {"type": "boolean"},
           "containerFluid": {"type": "string", "minLength": 1, "maxLength": 128}}, ["screenId"]),
    "oneOf": [
        {"required": ["slot"], "not": {"anyOf": [{"required": ["x"]}, {"required": ["y"]}]}},
        {"required": ["x", "y"], "not": {"required": ["slot"]}},
    ],
}


NAVIGATION_KEYS = [1, 14, 15, 28, 156, 199, 200, 201, 203, 205, 207, 208, 209, 211]


GUI_KEY_SCHEMA = {
    **obj({"screenId": SCREEN_ID, "keyCode": {"type": "integer", "enum": [0, *NAVIGATION_KEYS]}, "character": {"type": "string", "maxLength": 1}}, ["screenId", "keyCode"]),
    "oneOf": [
        {"properties": {"keyCode": {"enum": NAVIGATION_KEYS}, "character": {"const": ""}}},
        {"properties": {"keyCode": {"const": 0}, "character": {"type": "string", "minLength": 1, "maxLength": 1, "pattern": r"^[\u0020-\u007E\u00A0-\uD7FF\uE000-\uFFFF]$"}}, "required": ["character"]},
    ],
}


GUI_POINTER_SCHEMA = {
    **obj({"screenId": SCREEN_ID, "action": {"type": "string", "enum": ["set", "clear"]},
        "source": {"type": "string", "enum": ["coordinates", "recipe", "slot"], "default": "coordinates"},
        "x": integer(0, 32_767), "y": integer(0, 32_767), "ttl_ms": {**integer(100, 5000), "default": 2000},
        "recipeIndex": integer(0, 2_147_483_647), "positionIndex": {**integer(0, 2_147_483_647), "default": 0},
        "positionKind": {"type": "string", "enum": ["inputs", "outputs", "other"], "default": "inputs"}, "slot": integer(0, 4095)}, ["screenId", "action"]),
    "oneOf": [
        {"properties": {"action": {"const": "clear"}}, "propertyNames": {"enum": ["screenId", "action"]}},
        {"properties": {"action": {"const": "set"}, "source": {"const": "coordinates"}}, "required": ["x", "y"],
         "propertyNames": {"enum": ["screenId", "action", "source", "x", "y", "ttl_ms"]}},
        {"properties": {"action": {"const": "set"}, "source": {"const": "recipe"}}, "required": ["source", "recipeIndex"],
         "propertyNames": {"enum": ["screenId", "action", "source", "recipeIndex", "positionKind", "positionIndex", "ttl_ms"]}},
        {"properties": {"action": {"const": "set"}, "source": {"const": "slot"}}, "required": ["source", "slot"],
         "propertyNames": {"enum": ["screenId", "action", "source", "slot", "ttl_ms"]}},
    ],
}


WIDGET_ACTION_SCHEMA = named_actions({"widgetId": LIVE_ID, "text": TEXT_INPUT, "expectedText": TEXT_INPUT, "commit": {"type": "boolean"},
                                     "value": {"type": "number"}, "selected": integer(0, 2_147_483_647), "focused": {"type": "boolean"},
                                     "source": GHOST_SOURCE_SCHEMA, "button": integer(0, 2)}, {
    "activate": (["widgetId", "button"], ["widgetId"], {}), "set_text": (["widgetId", "text", "expectedText", "commit"], ["widgetId", "text"], {}),
    "set_value": (["widgetId", "value"], ["widgetId", "value"], {}), "set_selected": (["widgetId", "selected"], ["widgetId", "selected"], {}),
    "focus": (["widgetId", "focused"], ["widgetId", "focused"], {}),
    "recheck_structure": (["widgetId"], ["widgetId"], {}),
    "ghost_drag": (["widgetId", "source"], ["widgetId", "source"], {}),
    "phantom_click": (["widgetId", "button"], ["widgetId", "button"], {}),
})


T_MCB_GUI = ToolSpec("mcb_gui", "gui.inspect", "Inspect client screen identity, screenOpen/screenKind, public buttons/container slots, visibleText and supported widgets with explicit coverage. After one GUI-open action require the actual expected text/slots/widgets in this fresh screen/window; screenOpen alone and loading placeholders do not prove content synchronization. In an owned developer world or an authenticated operator server advertising guiNativeWaitVersion=1, optional waitFor:{} performs one bounded client-native wait for matching current windows, full ordinary-slot NBT from the server native last-announced snapshot and full registered phantom contents. For MUI2, require phantomRegistryComplete/fullPhantomFluidSlotsEqual and inspect phantomItemSlotCount/phantomFluidSlotCount: total phantomSlotCount includes both, including empty configured slots. Older receipts with only item phantom counts do not prove fluid synchronization. Fluid values compare full cached type, amount and NBT on both owner threads. This proves GUI synchronization, not equality with later live equipment/inventory mutations or execution of an earlier action. Declared observed predicates can require actual expected values; at most one container-bound native resync, never another click. Remote waiting binds the same live connection, dimension and server container token, comparing SHA-256 of bounded canonical complete NBT at up to four native samples per second; it needs server MCB, not QA. Player-only or unadvertised servers remain unsupported. Otherwise use a bounded mcb_wait_for read for an expected structured path. Use stable screenKind rather than guessed Java class names; retain exact current screenId/windowId for actions. Prefer this live client data, inventory and gui tooltip before local source; use screenshots only when necessary or permitted. Versioned help is available for unfamiliar GTNH NEI/AE controls. Unsupported GUI internals require a dedicated adapter.", obj({"waitFor": GUI_ACCEPTANCE_SCHEMA}), True)


T_MCB_GUI_POINTER = ToolSpec("mcb_gui_pointer", "gui.pointer", "Set or clear a screen-bound logical GUI hover point without moving the physical mouse or clicking. Requires the exact current screenId. set with source=coordinates (default) requires scaled integer x/y; source=recipe requires the visible original recipeIndex and optional positionKind=inputs|outputs|other/positionIndex (defaults inputs/0); source=slot requires the real current container slotNumber as slot0..4095. Source branches reject each other's selectors and do not silently fall back to coordinates. Recipe/slot resolution verifies an actual native visible target before using its center. ttl_ms100..5000 defaults2000 and screen changes/expiry invalidate the point. set automatically establishes normal full background protection; clear requires only action/screenId, rejects coordinates/TTL, and preserves current mouse/background choices. Receipt pointer state is not proof of hovered text. After set, read mcb_nei_view's passive completed-frame hover targets and require the actual frame/source/pointer generation diagnostics before claiming hover coverage. Native keys and GUI/NEI operations remain available. No mouse movement, button press, fake tooltip or forced rendering; no automatic retry.", GUI_POINTER_SCHEMA, idempotent=False)


T_MCB_GUI_TOOLTIP = ToolSpec("mcb_gui_tooltip", "gui.tooltip", "Read a current client GUI tooltip using the exact screenId and either a real container slot or scaled x/y coordinates. Prefers the actual ItemStack.getTooltip; actual-stack item tooltips also expose fluidContainer from bounded independent copies via public fluid container getters (including NBT-backed capacity/amount). Require available=true and inspect capacity.available; an unavailable read is unknown. Supported public widget adapters report their coverage. Does not move the mouse, change physical hover or take a screenshot. Read live GUI/inventory/item data first, then local source only if data is insufficient.", GUI_TOOLTIP_SCHEMA, True)


T_MCB_GUI_WIDGETS = ToolSpec("mcb_gui_widgets", "gui.widgets", "Read live screen-bound widgetId/actions/state/tooltip/texture. Prefer actionableOnly=true for decisions: skip disabled controls and unrelated inventory tooltips before construction, retaining nearby contextOnly text labels. semantic=structure_recheck|power_switch narrows two-state buttons. Creative inventory exposes inventoryTabWidgetId; activate it, then read creative_player_slot widgets. Inspect partial/coverage/nextOffset/hasMore/budget; offsets are original tree indices, never widgetId numbers. Empty partial pages do not prove absence. Original full-tree mode remains default.", obj({"screenId": SCREEN_ID, "actionableOnly": {"type": "boolean", "default": False}, "semantic": {"type": "string", "enum": ["structure_recheck", "power_switch"]}, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 128), "default": 64}}, ["screenId"]), True)


T_MCB_GUI_WIDGET_ACTION = ToolSpec("mcb_gui_widget_action", "gui.widget_action", "Apply an advertised named action to a current stable widgetId under exact screenId: activate (button defaults 0; native MUI2 ButtonWidget also accepts 1/right and 2/middle for real negative tests; native accepted tap precedes release), set_text (commit defaults true), set_value, set_selected, focus, recheck_structure, ghost_drag or phantom_click. Uses native validation/sync; rejects closed/replaced widgets. ghost_drag configures public MUI2 phantom inputs through one normal NEI drag: source observed neiItemId/count (full NEI NBT), registryName/metadata/count (defaults metadata=0,count=1), actual playerSlot/count (copies full NBT without consuming it), or fluidName/amount (GT NEI fake-fluid template, defaults amount=1000). For fluids prefer an observed filled cell's playerSlot; cells can mark types regardless of storage capacity. NEI fake-fluid templates commonly represent bookmarked recipe ingredients; fluidName creates the equivalent native display template. Read ghostSlot first: dragging the same type can decrement/clear it, so do not blindly replay. Accepted ghost_drag auto-releases NEI fake cursors; ghostRelease preserves real held items and reports cleanup separately from marking. PhantomItemSlot is not an ordinary container slot. phantom_click button=0/1 (fluid also 2) uses the actual cursor; use an already owned item/cell for real-cursor configuration; survival marking prefers ghost_drag and never generates real stock. Clean up temporary creative marker items manually. Debug templates are never consumed; finite-mode bus amounts are virtual stock, not finite conservation. Configure its amount/toggle with normal text/choice controls. Submitted callbacks are not server confirmation: inspect after sync. Ordinary FluidSlot activate requires real containers; verify world tank deltas. No OS input or screenshots.", WIDGET_ACTION_SCHEMA)


T_MCB_BAUBLES = ToolSpec("mcb_baubles", "baubles.inspect", "Read the current optional public Baubles accessory inventory on its owning client (default) or integrated-server thread (server requires developer profile). Exact item/NBT/count, real abilities and native container slot/type bindings; no equipment writes. Optional candidateInventoryIndex checks the real player stack against public inventory and bound native slot acceptance. Missing native binding stays unavailable. Equip/unequip using mcb_slot_click on a fresh actual Baubles GUI/window; then require fresh client/server source/destination/cursor and real capability proof. Page at most32 slots; retain complete/partial/hasMore. includePlayerInventory returns the complete actual player inventory separately. This does not infer equipment effects or grant flight.", obj({"source": {"enum": ["client", "server"], "default": "client"}, "offset": {**integer(0, 256), "default": 0}, "limit": {**integer(1, 32), "default": 32}, "includePlayerInventory": {"type": "boolean", "default": False}, "candidateInventoryIndex": integer(0, 255)}), True)


T_MCB_SLOT_CLICK = ToolSpec("mcb_slot_click", "gui.slot_click", "Click an actual container slot with the current windowId. AE2 virtual item grids are not container slots. Defaults mode/button to 0. Modes 0/1/4/6 accept button 0..1; mode 2 swaps hotbar button 0..8; swapStrategy defaults auto and uses a verified 2/3 native-pickup gesture for MUI2 wrapped player slots, requiring an empty real cursor and distinct stack identities to avoid merging. A partial gesture stops and retains the cursor; inspect before recovery and never replay blindly. swapStrategy=native retains the original single number-key windowClick, which can legitimately do nothing for wrapped/full inventories; pickup explicitly selects the gesture. Both return client evidence only, requiring later exact slot/hotbar server verification. mode 3 requires button 2 and creative mode. Drag mode 5 is unsupported. Slot -999 clicks outside.", SLOT_CLICK_SCHEMA)


T_MCB_TRANSFER_WHOLE_STACK = ToolSpec("mcb_transfer_whole_stack", "gui.transfer_whole_stack", "Transfer one complete explicitly untagged source stack to one empty actual destination in the advertised normal ModularUI2 machine window. Supply fresh exact screen/window, normal visible owner face, complete native source/destination slot and backing player/machine inventory bindings. Uses exactly two ordinary native pickup/place clicks, with real server pickup confirmation before place. Every phase retains actual actor/session/world/owner/ray/reach and all 41 player slots, the complete machine inventory, selection and full unrelated NBT. One fixed fresh owner/player AFTER follows the native BEFORE receipt. Partial, shift, phantom, virtual or tagged routes are unsupported. Unknown/refused state after pickup stops and preserves the last observed cursor; never retry, fall back, clear or discard it.", obj({
        "version": {"const": 1, "type": "integer"}, "expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD,
        "screenId": SCREEN_ID, "windowId": integer(1, 32767), "side": integer(0, 5),
        "direction": {"enum": ["player_to_machine", "machine_to_player"]}, "sourceSlot": integer(0, 511), "destinationSlot": integer(0, 511),
        "playerInventoryIndex": integer(0, 35), "machineInventoryIndex": integer(0, 255),
        "owner": obj({**POSITION, "dimension": DIMENSION, "registryName": {"type": "string", "minLength": 1, "maxLength": 256},
            "metaTileId": integer(0, 32767), "metaClass": {"type": "string", "minLength": 1, "maxLength": 256}},
            ["x", "y", "z", "dimension", "registryName", "metaTileId", "metaClass"]),
        "expectedItem": obj({"registryName": {"type": "string", "minLength": 1, "maxLength": 256}, "metadata": integer(0, 32766),
            "count": integer(1, 2147483647), "hasTag": {"const": False, "type": "boolean"}}, ["registryName", "metadata", "count", "hasTag"]),
        }, ["version", "expectedSessionId", "expectedWorld", "screenId", "windowId", "side", "direction", "sourceSlot", "destinationSlot", "playerInventoryIndex", "machineInventoryIndex", "owner", "expectedItem"]), idempotent=False)


T_MCB_CLOSE_GUI = ToolSpec("mcb_close_gui", "gui.close", "Close the current game screen through the normal client path once. A close click is not a server container ACK. Before standalone low-level movement or feeding, observe mcb_snapshot with serverControl.sampleId newer than the before-close sample, openContainer.isInventoryContainer=true and clientContainer.isInventoryContainer=true and matching windowId. Prefer a bounded read-only wait/sequence; never repeat close because synchronization is delayed. Goal tasks already enforce their own container barriers, so do not add duplicate checks around them.", obj())


T_MCB_OPEN_INVENTORY = ToolSpec("mcb_open_inventory", "gui.open_inventory", "Open the normal player inventory screen from world view; close an existing GUI first. With inventoryPlayerTabVersion>=1, creative mode selects the native player-inventory tab once so ordinary inventory packets can synchronize. Returns the current GUI, screenId/windowId and creative tab state; reuse this receipt instead of a second GUI read. Older mods require selecting the returned inventoryTabWidgetId before NEI acquisition.", obj())


T_MCB_OPEN_PAUSE_MENU = ToolSpec("mcb_open_pause_menu", "gui.open_pause_menu", "Open the native in-game Esc menu from live world view with an empty inventory cursor, releasing agent inputs first. Uses Minecraft's normal menu method, without OS input. Inspect actual screenId and mcb_gui_widgets, then use advertised native button actions for Options and difficulty. Opening does not itself change or persist settings; verify each actual native value and use the normal Done controls. May pause a local world.", obj())


T_MCB_OPEN_BAUBLES = ToolSpec("mcb_open_baubles", "gui.open_baubles", "From world view with empty cursor, send the installed Baubles normal inventory-open packet once. No OS key input or direct slot writes. Submission is not an open/gear/flight acknowledgement: wait for the actual new GUI/window and matching fresh server container before native slot clicks, then read mcb_baubles. Missing public API is explicitly unsupported; no retry.", obj())


T_MCB_GUI_CLICK = ToolSpec("mcb_gui_click", "gui.click", "Click and release scaled GUI coordinates using the exact screenId from mcb_gui. Rejects stale screens. Read the current width/height before choosing coordinates. AE2 virtual grids and ModularUI2 use cached hover state and refuse generic coordinate clicks; dedicated adapters are required. Use mcb_slot_click only for actual container slots. Chat and command-block screens require local developer profile.", obj({"screenId": SCREEN_ID, "x": integer(0, 32_767), "y": integer(0, 32_767), "button": integer(0, 2)}, ["screenId", "x", "y"]))


T_MCB_GUI_KEY = ToolSpec("mcb_gui_key", "gui.key", "Send a GUI navigation key with the exact current screenId: Esc 1, Backspace 14, Tab 15, Enter 28/156, Home 199, arrows 200/203/205/208, PageUp 201, End 207, PageDown 209, Delete 211. Navigation uses no character. For text, use keyCode 0 and exactly one printable BMP character. Chat and command-block screens require local developer profile.", GUI_KEY_SCHEMA)
