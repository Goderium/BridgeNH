package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.lifecycle.NativeMenuLists;
import com.Goderium.BridgeNH.common.ae.AeControlAdapter;
import com.Goderium.BridgeNH.common.ae.AeDataAdapter;
import com.Goderium.BridgeNH.common.ae.AeNativeControls;
import com.Goderium.BridgeNH.common.ae.AePhantomSlots;
import com.Goderium.BridgeNH.common.machine.MachineSlotBinding;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Screen-bound object identities and native control semantics; never physical hover-cache dispatch. */
public final class GuiControlAdapter {
    private static final String MUI = "com.cleanroommc.modularui.";
    private static final String AE_GUI = AeDataAdapter.GUI;
    private static final String AE_TEXT = "appeng.client.gui.widgets.MEGuiTextField";
    private static final int SCAN_LIMIT = 4096, BYTE_LIMIT = 96 * 1024;
    private static Object boundScreen; private static String boundId; private static long nextWidget;
    private static final IdentityHashMap<Object, String> ids = new IdentityHashMap<Object, String>();
    private GuiControlAdapter() {}
    public static synchronized void invalidate() { boundScreen = null; boundId = null; ids.clear(); NativeMenuLists.invalidate(); AeNativeControls.invalidate(); }
    private static synchronized void bind(Object screen, String id) { if (screen != boundScreen || !id.equals(boundId)) { invalidate(); boundScreen = screen; boundId = id; } }
    private static synchronized String id(Object widget) {
        String value = ids.get(widget);
        if (value == null) { if (ids.size() >= SCAN_LIMIT) throw new BridgeException("too_large", "Current screen widget identity bound reached."); value = "widget:" + (++nextWidget); ids.put(widget, value); }
        return value;
    }
    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject args, List<?> buttons) {
        return read(screen, args, buttons, System.nanoTime(), client == null || client.thePlayer == null ? null : client.thePlayer.inventory,
                machineOwner(client, screen));
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object screen, JsonObject args, List<?> buttons, long started) {
        return read(screen, args, buttons, started, null);
    }
    static JsonObject read(Object screen, JsonObject args, List<?> buttons, long started, InventoryPlayer inventory) {
        return read(screen, args, buttons, started, inventory, MachineSlotBinding.Context.unavailable("public_machine_owner_api_unavailable"));
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object screen, JsonObject args, List<?> buttons, long started, InventoryPlayer inventory, MachineSlotBinding.Context owner) {
        ExpandedGuiApi.keys(args, "offset", "limit", "semantic", "actionableOnly"); String screenId = ExpandedGuiApi.string(args, "screenId", 128); bind(screen, screenId);
        if (args.has("action")) throw new BridgeException("invalid_args", "gui.widgets has no action argument.");
        boolean actionableOnly = GuiDataAdapter.booleanArg(args, "actionableOnly", false);
        String semantic = args.has("semantic") ? ExpandedGuiApi.option(args, "semantic", null, "structure_recheck", "power_switch") : null;
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        Snapshot snapshot = snapshot(screen, buttons, started, inventory, owner);
        JsonArray widgets = new JsonArray(); int next = actionableOnly && snapshot.truncated && offset > snapshot.nodes.size() ? offset : Math.min(offset, snapshot.nodes.size());
        int first = next, bytes = 4096, candidates = 0; boolean byteLimited = false;
        while (next < snapshot.nodes.size() && widgets.size() < limit && (next == first || System.nanoTime() - started < StructureAdapter.SOFT_BUDGET_NANOS)) {
            Node node = snapshot.nodes.get(next);
            if (semantic != null && (!ExpandedGuiApi.is(node.widget, MUI + "widgets.AbstractCycleButtonWidget")
                    || ((Number) ExpandedGuiApi.call(node.widget, "getStateCount")).intValue() != 2)) { next++; continue; }
            boolean contextLabel = actionableOnly && semantic == null && contextLabel(node, snapshot);
            if (actionableOnly && !contextLabel && !actionable(node, snapshot)) { next++; continue; }
            candidates++;
            JsonObject entry = contextLabel ? describeContextLabel(node, snapshot) : describe(node, snapshot);
            if (semantic != null && (!entry.has("semantic") || !semantic.equals(entry.get("semantic").getAsString()))) { next++; continue; }
            if (actionableOnly && (!entry.get("enabled").getAsBoolean() || !entry.get("valid").getAsBoolean()
                    || (!contextLabel && entry.getAsJsonArray("supportedActions").size() == 0))) { next++; continue; }
            int size = entry.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > BYTE_LIMIT - 4096) { byteLimited = true; break; }
            widgets.add(entry); bytes += size; next++;
        }
        JsonObject result = new JsonObject(); result.addProperty("screenId", screenId); result.add("widgets", widgets);
        result.addProperty("offset", offset); result.addProperty("nextOffset", next); result.addProperty("hasMore", next < snapshot.nodes.size() || snapshot.truncated);
        result.addProperty("visited", snapshot.nodes.size()); result.addProperty("scanTruncated", snapshot.truncated); result.addProperty("byteLimited", byteLimited); result.addProperty("nonAtomic", true);
        if (semantic != null) {
            result.addProperty("semantic", semantic); result.addProperty("semanticCandidates", candidates);
            result.addProperty("rawScanned", next - first);
        }
        if (actionableOnly) { result.addProperty("actionableOnly", true); result.addProperty("rawScanned", next - first); }
        result.addProperty("partial", next < snapshot.nodes.size() || snapshot.truncated || snapshot.error != null);
        if (snapshot.creative != null) {
            JsonObject creative = CreativeGuiAdapter.summary(snapshot.creative);
            if (snapshot.creative.registered(CreativeTabs.tabInventory)) creative.addProperty("inventoryTabWidgetId", id(CreativeTabs.tabInventory));
            result.add("creativeInventory", creative);
        }
        if (snapshot.sodium != null) result.add("settings", SodiumSettingsControls.summary(snapshot.sodium));
        result.add("budget", StructureAdapter.budget(started)); JsonObject coverage = new JsonObject();
        coverage.addProperty("source", "Current native GuiScreen buttons with Forge capture fallback; actual vanilla setting/sound sliders and native lists/keybinding rows; actual public creative tabs and inventory-tab player slots; bounded native vanilla/AE text fields and labels; synchronized AE remote pattern slots and native provider locator/highlighter; public MUI1 open windows and MUI2 panel/widget trees, current text, tooltips and supported native controls.");
        coverage.addProperty("identity", "Widget IDs identify actual objects for this screenId; native button IDs may repeat. Closed/replaced controls are revalidated before mutation.");
        coverage.addProperty("actions", "Native callbacks/value setters/sync; no OS input, drawing or caller-selected reflection. MUI2 buttons use native layout hit testing and screen press/release dispatch with scoped logical coordinates. Generic activate requires neutral physical modifiers/buttons. Named structure recheck on a native two-state ToggleButton uses its public state setter and normal sync.");
        if (semantic != null) coverage.addProperty("filter", "Explicit semantic filter inspects public display data only for current two-state MUI2 cycle buttons. offset/nextOffset remain raw tree positions; empty partial pages are not proof of absence.");
        if (actionableOnly) coverage.addProperty("actionableFilter", "Enabled/valid known native controls are filtered before unrelated tooltip generation, then supportedActions are revalidated. Enabled public TextWidget siblings of actionable controls provide contextOnly labels without tooltip building. offset/nextOffset remain raw tree positions; truncated prefixes preserve an issued offset.");
        if (snapshot.personalSpace != null) coverage.addProperty("personalSpace", "Public widget tree, native text/choice/slider callbacks and fixed native dropdown geometry/search methods; no dimension model writes. Dropdowns reuse a five-second logical hover lease without OS input. Natural frame validation precedes save; generated layer row IDs are read-only.");
        if (snapshot.sodium != null) coverage.addProperty("sodium", "Actual Reese/Angelica frames, page selector, clipped native controls and scrollbars. Pending option buffers and actual binding values are separate; normal Apply/Undo callbacks own persistence.");
        if (snapshot.error != null) coverage.addProperty("treeError", snapshot.error); result.add("coverage", coverage); return result;
    }
    public static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args, List<?> buttons) {
        return perform(client, screen, args, buttons, null, () -> false);
    }
    public static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args, List<?> buttons, String session, BooleanSupplier protection) {
        if (client.currentScreen != screen) throw new BridgeException("stale_screen", "The current screen changed before the widget action.");
        InventoryPlayer inventory = client.thePlayer == null ? null : client.thePlayer.inventory;
        Object container = client.thePlayer == null ? null : client.thePlayer.openContainer;
        int windowId = client.thePlayer == null || client.thePlayer.openContainer == null ? -1 : client.thePlayer.openContainer.windowId;
        return perform(screen, args, buttons, inventory, () -> client.currentScreen == screen
                && (client.thePlayer == null ? inventory == null && container == null
                    : client.thePlayer.inventory == inventory && client.thePlayer.openContainer == container
                        && (client.thePlayer.openContainer == null ? windowId == -1 : client.thePlayer.openContainer.windowId == windowId)), machineOwner(client, screen), control -> {
                    if (control == null) { LogicalGuiPointer.clear("native_dropdown_closed"); return; }
                    JsonObject pointer = new JsonObject(); pointer.addProperty("action", "set"); pointer.add("screenId", args.get("screenId"));
                    pointer.addProperty("x", control.bounds.x + control.bounds.width / 2); pointer.addProperty("y", control.bounds.y + control.bounds.height / 2); pointer.addProperty("ttl_ms", 5000);
                    LogicalGuiPointer.nativeWidget(client, screen, pointer, session, protection);
                });
    }
    static JsonObject perform(Object screen, JsonObject args, List<?> buttons) {
        return perform(screen, args, buttons, null, () -> true);
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject perform(Object screen, JsonObject args, List<?> buttons, InventoryPlayer inventory, BooleanSupplier stillCurrent) {
        return perform(screen, args, buttons, inventory, stillCurrent, MachineSlotBinding.Context.unavailable("public_machine_owner_api_unavailable"));
    }
    private static JsonObject perform(Object screen, JsonObject args, List<?> buttons, InventoryPlayer inventory, BooleanSupplier stillCurrent, MachineSlotBinding.Context owner) {
        return perform(screen, args, buttons, inventory, stillCurrent, owner, null);
    }
    private static JsonObject perform(Object screen, JsonObject args, List<?> buttons, InventoryPlayer inventory, BooleanSupplier stillCurrent, MachineSlotBinding.Context owner, java.util.function.Consumer<PersonalSpaceGuiAdapter.Control> hover) {
        String screenId = ExpandedGuiApi.string(args, "screenId", 128); bind(screen, screenId);
        String widgetId = ExpandedGuiApi.string(args, "widgetId", 128), action = ExpandedGuiApi.option(args, "action", null, "activate", "set_text", "set_value", "set_selected", "focus", "recheck_structure", "ghost_drag", "phantom_click");
        if (action.equals("set_text")) ExpandedGuiApi.keys(args, "widgetId", "text", "commit", "expectedText");
        else if (action.equals("set_value")) ExpandedGuiApi.keys(args, "widgetId", "value");
        else if (action.equals("set_selected")) ExpandedGuiApi.keys(args, "widgetId", "selected");
        else if (action.equals("focus")) ExpandedGuiApi.keys(args, "widgetId", "focused");
        else if (action.equals("ghost_drag")) ExpandedGuiApi.keys(args, "widgetId", "source");
        else if (action.equals("phantom_click") || action.equals("activate")) ExpandedGuiApi.keys(args, "widgetId", "button");
        else ExpandedGuiApi.keys(args, "widgetId");
        Snapshot snapshot = snapshot(screen, buttons, System.nanoTime(), inventory, owner); Node node = null;
        for (Node candidate : snapshot.nodes) if (widgetId.equals(id(candidate.widget))) { node = candidate; break; }
        if (node == null) throw new BridgeException(snapshot.truncated ? "target_unverified" : "stale_widget", "The exact widget is no longer present in the bounded current live tree.");
        JsonObject before = describe(node, snapshot);
        if (!before.get("enabled").getAsBoolean() || !before.get("valid").getAsBoolean()) throw new BridgeException("disabled", "The live control or its parent is disabled/invalid.");
        boolean allowed = false; for (JsonElement value : before.getAsJsonArray("supportedActions")) if (action.equals(value.getAsString())) allowed = true;
        if (!allowed) throw new BridgeException("unsupported", "The current control does not advertise this semantic action.");
        if (!(action.equals("set_value") && GhostCircuitGuiAdapter.supports(node.widget)) && !stillCurrent.getAsBoolean())
            throw new BridgeException("stale_screen", "The native screen/container changed before invocation.");
        if (snapshot.sodium == null && action.equals("set_text") && args.has("expectedText")
                && !ExpandedGuiApi.string(args, "expectedText", 1024).equals(currentText(node.widget)))
            throw new BridgeException("value_changed", "The current native field text differs from expectedText; no text was written.");
        Object widget = node.widget;
        int activateButton = action.equals("activate") && args.has("button") ? JsonData.integer(args, "button", 0, 2) : 0;
        if (activateButton != 0 && !ExpandedGuiApi.is(widget, MUI + "widgets.ButtonWidget") && !PersonalSpaceGuiAdapter.button(widget))
            throw new BridgeException("invalid_args", "Nonleft semantic activation is supported only by native MUI2 ButtonWidget controls.");
        boolean nativeStructureState = action.equals("recheck_structure") && nativeStructureState(widget, before);
        JsonElement beforeFluidCursor = FluidGuiAdapter.supports(widget) && inventory != null ? JsonData.item(inventory.getItemStack()) : null;
        if (action.equals("recheck_structure") && before.get("selected").getAsInt() == 1) {
            JsonObject pending = new JsonObject(); pending.addProperty("screenId", screenId); pending.addProperty("widgetId", widgetId);
            pending.addProperty("action", action); pending.addProperty("submitted", false); pending.addProperty("pending", true);
            pending.add("before", before); pending.addProperty("completion", "The normal GT structure-update button is already selected; wait for a later machine inspection. No second toggle was sent.");
            return pending;
        }
        JsonObject creativePickup = null, callbackReturns = null, phantomAction = null, nativeSlider = null; Integer requestedCircuitConfig = null;
        JsonElement beforePhantomCursor = PhantomGuiAdapter.supports(widget) && inventory != null ? JsonData.item(inventory.getItemStack()) : JsonNull.INSTANCE;
        JsonElement beforeCursor = snapshot.creative == null || inventory == null ? JsonNull.INSTANCE : JsonData.item(inventory.getItemStack());
        if (snapshot.sodium != null) {
            neutralInput(); callbackReturns = SodiumSettingsControls.perform(snapshot.sodium, widget, action, args, stillCurrent);
        } else if (snapshot.personalSpace != null) {
            snapshot.personalSpace.hover = hover;
            neutralInput(); callbackReturns = PersonalSpaceGuiAdapter.perform(snapshot.personalSpace, widget, action, args, activateButton, stillCurrent);
        } else if (snapshot.creative != null && (widget instanceof CreativeTabs || widget instanceof Slot)) {
            neutralInput(); creativePickup = CreativeGuiAdapter.activate((GuiScreen) screen, widget, snapshot.creative, stillCurrent);
        } else if (action.equals("ghost_drag")) phantomAction = PhantomGuiAdapter.drag(widget, screen, args, inventory, stillCurrent);
        else if (action.equals("phantom_click")) phantomAction = PhantomGuiAdapter.click(widget, screen, args, inventory, stillCurrent);
        else if (nativeStructureState) ExpandedGuiApi.call(widget, "setState", 1, true);
        else if (action.equals("activate") || action.equals("recheck_structure")) callbackReturns = activate(screen, widget, activateButton, stillCurrent);
        else if (action.equals("set_text")) setText(widget, args, stillCurrent);
        else if (action.equals("set_value") && (widget instanceof net.minecraft.client.gui.GuiSlot || NativeMenuLists.keyRow(widget))) {
            neutralInput(); NativeMenuLists.setValue(widget, args, stillCurrent);
        } else if (action.equals("set_value") && VanillaSettingsControls.supports(widget)) {
            neutralInput(); nativeSlider = VanillaSettingsControls.setValue((GuiButton) widget, args, stillCurrent);
        } else if (action.equals("set_value") && GhostCircuitGuiAdapter.supports(widget))
            requestedCircuitConfig = GhostCircuitGuiAdapter.setValue(widget, screen, args, inventory, stillCurrent);
        else if (action.equals("set_value")) {
            double value = JsonData.number(args, "value", ((Number) ExpandedGuiApi.call(widget, "getMin")).doubleValue(), ((Number) ExpandedGuiApi.call(widget, "getMax")).doubleValue());
            ExpandedGuiApi.call(widget, "setValue", value, true);
        } else if (action.equals("set_selected")) {
            if (widget instanceof net.minecraft.client.gui.GuiSlot) {
                neutralInput(); NativeMenuLists.setSelected((net.minecraft.client.gui.GuiSlot) widget, args, stillCurrent);
            } else if (ExpandedGuiApi.is(widget, MUI + "widgets.menu.DropdownWidget")) {
                List<?> options = (List<?>) ExpandedGuiApi.field(widget, MUI + "widgets.menu.DropdownWidget", "values");
                int selected = JsonData.integer(args, "selected", 0, options.size() - 1);
                ExpandedGuiApi.declaredCall(widget, MUI + "widgets.menu.DropdownWidget", "setValue", options.get(selected), true);
            } else {
                int selected = JsonData.integer(args, "selected", 0, ((Number) ExpandedGuiApi.call(widget, "getStateCount")).intValue() - 1);
                ExpandedGuiApi.call(widget, "setState", selected, true);
            }
        } else {
            if (!args.has("focused")) throw new BridgeException("invalid_args", "focused is required.");
            focus(widget, GuiDataAdapter.booleanArg(args, "focused", false));
        }
        JsonObject result = new JsonObject(); result.addProperty("screenId", screenId); result.addProperty("widgetId", widgetId); result.addProperty("action", action); result.addProperty("submitted", true);
        result.addProperty("nativeCallbackCompleted", true); result.addProperty("serverConfirmed", false);
        result.addProperty("confirmation", "local_native_callback_only");
        if (nativeSlider != null) { result.add("nativeSlider", nativeSlider); result.add("nativeAction", nativeSlider.get("nativeAction")); }
        if (widget instanceof net.minecraft.client.gui.GuiSlot || NativeMenuLists.keyRow(widget))
            result.addProperty("nativeAction", widget instanceof net.minecraft.client.gui.GuiSlot ? "native_menu_list_scroll_or_single_selection" : "native_GameSettings_setOptionKeyBinding_and_hash_refresh");
        if (phantomAction != null) {
            result.add("phantomAction", phantomAction); result.add("nativeAction", phantomAction.get("nativeAction"));
            if (phantomAction.has("ghostRelease")) result.add("ghostRelease", phantomAction.get("ghostRelease"));
            if (action.equals("ghost_drag")) result.addProperty("submitted", phantomAction.get("accepted").getAsBoolean());
            result.add("beforeCursorItem", beforePhantomCursor);
            result.add("afterCursorItem", inventory == null ? JsonNull.INSTANCE : JsonData.item(inventory.getItemStack()));
        }
        if (callbackReturns != null) { result.add("nativeCallbackReturns", callbackReturns); result.addProperty("button", activateButton); }
        if (nativeStructureState) { result.addProperty("nativeAction", "mui2_structure_recheck_state"); result.addProperty("serverConfirmed", false); }
        if (snapshot.creative != null && (widget instanceof CreativeTabs || widget instanceof Slot)) {
            result.addProperty("nativeAction", widget instanceof Slot ? "creative_inventory_left_pickup" : "creative_tab_click");
            if (widget instanceof Slot) {
                if (creativePickup.has("nativeCallback")) result.add("nativeCallback", creativePickup.get("nativeCallback"));
                result.add("nativeContainerSlotId", creativePickup.get("nativeContainerSlotId"));
                result.add("nativeDiagnostic", creativePickup);
                result.add("beforeCursorItem", beforeCursor);
            }
            result.addProperty("currentScreen", stillCurrent.getAsBoolean());
            if (stillCurrent.getAsBoolean()) {
                CreativeGuiAdapter.State current = CreativeGuiAdapter.capture(screen, inventory);
                result.add("creativeInventory", CreativeGuiAdapter.summary(current));
                if (widget instanceof Slot && current.playerSlot((Slot) widget)) {
                    JsonObject after = CreativeGuiAdapter.describe((Slot) widget, current);
                    result.add("after", after);
                    result.addProperty("localEffect", !before.get("item").equals(after.get("item")) || !beforeCursor.equals(JsonData.item(inventory.getItemStack())));
                }
            }
        }
        if (GhostCircuitGuiAdapter.supports(widget)) {
            result.addProperty("nativeAction", requestedCircuitConfig == null ? "gt_ghost_circuit_left_click" : "gt_ghost_circuit_set_value"); result.addProperty("serverConfirmed", false);
            if (requestedCircuitConfig != null) result.addProperty("requestedCircuitConfig", requestedCircuitConfig);
            result.add("afterCircuitConfig", GhostCircuitGuiAdapter.read(widget));
        }
        if (FluidGuiAdapter.supports(widget)) {
            if (phantomAction == null) result.addProperty("nativeAction", "mui2_fluid_slot_left_click"); result.addProperty("serverConfirmed", false);
            result.add("afterFluidSlot", FluidGuiAdapter.read(widget, stillCurrent.getAsBoolean() ? owner
                    : MachineSlotBinding.Context.unavailable("current_native_container_unverified"))); result.addProperty("cursorObserved", inventory != null);
            if (inventory != null) { result.add("beforeCursorItem", beforeFluidCursor); result.add("afterCursorItem", JsonData.item(inventory.getItemStack())); }
        }
        result.add("before", before);
        boolean current = stillCurrent.getAsBoolean(); result.addProperty("currentScreen", current);
        if (current) {
            Snapshot afterSnapshot = snapshot(screen, buttons, System.nanoTime(), inventory, owner); Node afterNode = afterSnapshot.seen.get(widget);
            if (afterNode != null) {
                try {
                    JsonObject after = describe(afterNode, afterSnapshot); if (!result.has("after")) result.add("after", after);
                    result.addProperty("localValueChanged", !observable(before).equals(observable(after)));
                    if (snapshot.sodium == null && action.equals("set_text")) {
                        String requested = ExpandedGuiApi.string(args, "text", 1024);
                        result.addProperty("requestedText", requested); result.addProperty("commitRequested", GuiDataAdapter.booleanArg(args, "commit", true));
                        result.addProperty("nativeCommitInvoked", GuiDataAdapter.booleanArg(args, "commit", true)
                                && !(widget instanceof GuiTextField) && !ExpandedGuiApi.is(widget, AE_TEXT) && !PersonalSpaceGuiAdapter.supports(widget));
                        result.addProperty("localTextMatchesRequested", requested.equals(currentText(widget)));
                        result.addProperty("confirmation", "local_widget_readback_only");
                        result.addProperty("commitSemantics", PersonalSpaceGuiAdapter.supports(widget)
                                ? "Native client text/search changed; next natural frame validates settings. Explicit save is required for persistence."
                                : widget instanceof GuiTextField || ExpandedGuiApi.is(widget, AE_TEXT)
                                ? "Whole native text replaced; no generic native submit callback is exposed for this field."
                                : "Whole native text replaced; commit=true invokes native focus-loss validator and configured sync.");
                    }
                } catch (RuntimeException | LinkageError error) { result.addProperty("readbackError", error.getClass().getSimpleName()); }
            } else result.addProperty("readbackUnavailable", afterSnapshot.truncated ? "current_tree_scan_incomplete" : "native_widget_removed");
        } else result.addProperty("readbackUnavailable", "native_screen_or_container_changed");
        result.addProperty("completion", "Native control invocation completed locally; synchronized server effects require a later inspection. The after value is a client widget readback, not authoritative server confirmation."); return result;
    }
    private static MachineSlotBinding.Context machineOwner(Minecraft client, GuiScreen screen) {
        if (client == null || client.currentScreen != screen || !(screen instanceof GuiContainer))
            return MachineSlotBinding.Context.unavailable("current_native_container_unverified");
        return MachineSlotBinding.observe(client, ((GuiContainer) screen).inventorySlots);
    }
    /** Bounded observations for key/click receipts. These are client values, never server acknowledgement. */
    public static JsonObject observeInputState(Minecraft client, GuiScreen screen, String screenId, List<?> buttons) {
        if (client == null || client.currentScreen != screen) throw new BridgeException("stale_screen", "The input observation requires the current screen.");
        bind(screen, screenId);
        return observeInputState(screen, screenId, buttons, client.thePlayer == null ? null : client.thePlayer.inventory);
    }
    static JsonObject observeInputState(Object screen, String screenId, List<?> buttons, InventoryPlayer inventory) {
        bind(screen, screenId); long started = System.nanoTime();
        Snapshot snapshot = snapshot(screen, buttons, started, inventory, MachineSlotBinding.Context.unavailable("input_observation_only"));
        JsonArray values = new JsonArray(); boolean truncated = snapshot.truncated; int bytes = 0;
        for (Node node : snapshot.nodes) {
            if (values.size() >= 128 || (values.size() > 0 && System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS)) { truncated = true; break; }
            Object widget = node.widget; JsonObject value = new JsonObject(); value.addProperty("widgetId", id(widget));
            try {
                if (snapshot.sodium != null) {
                    JsonObject description = describe(node, snapshot);
                    if (!description.get("visible").getAsBoolean()) continue;
                    value = observable(description); value.addProperty("widgetId", id(widget));
                    if (value.entrySet().size() <= 1) continue;
                } else if (snapshot.personalSpace != null) {
                    if (!snapshot.personalSpace.byWidget.get(widget).visible) continue;
                    JsonObject description = describe(node, snapshot);
                    value = observable(description); value.addProperty("widgetId", id(widget));
                    if (value.entrySet().size() <= 1) continue;
                } else if (widget instanceof GuiTextField || ExpandedGuiApi.is(widget, AE_TEXT) || LegacyModularGuiAdapter.editable(widget)
                        || ExpandedGuiApi.is(widget, MUI + "widgets.textfield.TextFieldWidget")) {
                    String text = currentText(widget); value.addProperty("text", JsonData.bounded(text, 1024)); value.addProperty("textLength", text.length());
                    value.addProperty("textTruncated", text.length() > 1024);
                    value.addProperty("focused", widget instanceof GuiTextField ? ((GuiTextField) widget).isFocused() : (Boolean) ExpandedGuiApi.call(widget, "isFocused"));
                    if (ExpandedGuiApi.is(widget, MUI + "widgets.textfield.TextFieldWidget"))
                        value.addProperty("value", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getStringValue"), "getStringValue")), 1024));
                } else if (ExpandedGuiApi.is(widget, MUI + "widgets.AbstractCycleButtonWidget"))
                    value.addProperty("selected", ((Number) ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getIntValue"), "getIntValue")).intValue());
                else if (ExpandedGuiApi.is(widget, MUI + "widgets.SliderWidget")) value.addProperty("value", (Number) ExpandedGuiApi.call(widget, "getSliderValue"));
                else continue;
            } catch (RuntimeException | LinkageError error) { value.addProperty("readError", error.getClass().getSimpleName()); truncated = true; }
            int size = value.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 48 * 1024) { truncated = true; break; }
            values.add(value); bytes += size;
        }
        JsonObject state = new JsonObject(); state.addProperty("screenId", screenId); state.add("values", values);
        state.addProperty("complete", !truncated && snapshot.error == null); state.addProperty("visited", snapshot.nodes.size());
        state.addProperty("source", "bounded native client widget getters; arbitrary drawn text and server acknowledgement unavailable"); return state;
    }
    public static JsonObject visibleText(Minecraft client, GuiScreen screen, String screenId, List<?> buttons) {
        bind(screen, screenId);
        return visibleText(screen, screenId, buttons, client == null || client.thePlayer == null ? null : client.thePlayer.inventory);
    }
    static JsonObject visibleText(Object screen, String screenId, List<?> buttons, InventoryPlayer inventory) {
        bind(screen, screenId); long started = System.nanoTime();
        Snapshot snapshot = snapshot(screen, buttons, started, inventory, MachineSlotBinding.Context.unavailable("text_observation_only"));
        JsonArray text = new JsonArray(); boolean truncated = snapshot.truncated, errors = snapshot.error != null; int bytes = 0, visited = 0;
        for (Node node : snapshot.nodes) {
            if (text.size() >= 128 || (visited > 0 && System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS)) { truncated = true; break; }
            visited++; Object widget = node.widget; JsonObject entry = new JsonObject();
            entry.addProperty("widgetId", id(widget)); entry.addProperty("name", node.name);
            try {
                if (snapshot.sodium != null) {
                    JsonObject description = describe(node, snapshot);
                    if (!description.get("visible").getAsBoolean() || !description.has("text")) continue;
                    displayText(entry, description.get("text").getAsString());
                    entry.addProperty("textSource", "native Reese/Angelica control");
                } else if (snapshot.personalSpace != null) {
                    if (!snapshot.personalSpace.byWidget.get(widget).visible) continue;
                    String value = PersonalSpaceGuiAdapter.text(widget); if (value.isEmpty()) continue;
                    displayText(entry, value); entry.addProperty("textSource", "native PersonalSpace widget text");
                } else if (widget instanceof GuiButton) {
                    if (!((GuiButton) widget).visible) continue;
                    displayText(entry, ((GuiButton) widget).displayString); entry.addProperty("textSource", "native button displayString");
                } else if (ExpandedGuiApi.is(widget, "net.minecraft.client.gui.GuiLabel")) {
                    describeVanillaLabel(widget, entry); if (!entry.get("visible").getAsBoolean()) continue;
                } else if (widget instanceof GuiTextField || ExpandedGuiApi.is(widget, AE_TEXT)) {
                    Object raw = widget instanceof GuiTextField ? widget : ExpandedGuiApi.field(widget, AE_TEXT, "field");
                    if (raw instanceof GuiTextField && !((GuiTextField) raw).getVisible()) continue;
                    displayText(entry, currentText(widget)); entry.addProperty("textSource", "native current editable text");
                } else if (LegacyModularGuiAdapter.supports(widget)) {
                    if (!muiEnabled(node, snapshot) || !LegacyModularGuiAdapter.valid(widget)) continue;
                    if (LegacyModularGuiAdapter.label(widget)) displayText(entry, LegacyModularGuiAdapter.labelText(widget));
                    else if (LegacyModularGuiAdapter.editable(widget)) displayText(entry, currentText(widget));
                    else continue;
                    entry.addProperty("textSource", "native current ModularUI1 label/editable text");
                } else if (ExpandedGuiApi.is(widget, MUI + "widget.Widget")) {
                    if (!muiEnabled(node, snapshot) || !(Boolean) ExpandedGuiApi.call(widget, "isValid")) continue;
                    if (ExpandedGuiApi.is(widget, MUI + "widgets.textfield.TextFieldWidget")) {
                        displayText(entry, currentText(widget)); entry.addProperty("textSource", "native current ModularUI2 editable text");
                    }
                    GuiPublicSemantics.describeDisplayText(widget, entry);
                } else continue;
            } catch (RuntimeException | LinkageError error) { errors = true; continue; }
            if (entry.has("textError") || entry.has("overlayTextError") || entry.has("backgroundTextError")) errors = true;
            if (entry.has("textTruncated") && entry.get("textTruncated").getAsBoolean()) truncated = true;
            for (String role : new String[] {"overlayText", "backgroundText"})
                if (entry.has(role) && entry.getAsJsonObject(role).has("textTruncated") && entry.getAsJsonObject(role).get("textTruncated").getAsBoolean()) truncated = true;
            if (!entry.has("text") && !entry.has("textLines") && !entry.has("overlayText") && !entry.has("backgroundText")) continue;
            int size = entry.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 24 * 1024) { truncated = true; break; }
            text.add(entry); bytes += size;
        }
        JsonObject result = new JsonObject(); result.addProperty("screenId", screenId); result.add("entries", text);
        result.addProperty("visited", visited); result.addProperty("truncated", truncated); result.addProperty("errors", errors);
        result.addProperty("completeForSupportedWidgets", !truncated && !errors);
        result.addProperty("coverage", "Native button/label/editable text, public MUI1 TextWidget and MUI2 TextWidget/RichTextWidget/text drawables. RichText contents are the last native builder state; arbitrary immediate-mode drawString labels have no supported getter.");
        return result;
    }
    static void displayText(JsonObject entry, String text) {
        if (text == null) text = "";
        entry.addProperty("text", JsonData.bounded(text, 1024)); entry.addProperty("textLength", text.length());
        entry.addProperty("textTruncated", text.length() > 1024);
    }
    private static JsonObject observable(JsonObject description) {
        JsonObject value = new JsonObject();
        for (String key : new String[] {"text", "value", "pendingValue", "appliedValue", "pendingChanges", "selected", "focused", "item", "circuitConfig", "fluidSlot", "ghostSlot"})
            if (description.has(key)) value.add(key, description.get(key));
        return value;
    }
    private static String currentText(Object widget) {
        if (PersonalSpaceGuiAdapter.supports(widget)) return PersonalSpaceGuiAdapter.text(widget);
        return widget instanceof GuiTextField ? ((GuiTextField) widget).getText() : String.valueOf(ExpandedGuiApi.call(widget, "getText"));
    }
    private static void describeVanillaLabel(Object widget, JsonObject entry) {
        String api = "net.minecraft.client.gui.GuiLabel";
        entry.addProperty("kind", "label"); boolean visible = (Boolean) ExpandedGuiApi.field(widget, api, "visible");
        entry.addProperty("visible", visible); entry.addProperty("enabled", visible); entry.addProperty("valid", true);
        Object raw = ExpandedGuiApi.field(widget, api, "field_146173_k"); JsonArray lines = new JsonArray();
        if (raw instanceof List<?>) {
            List<?> text = (List<?>) raw;
            for (int i = 0; i < Math.min(GuiDataAdapter.LINE_LIMIT, text.size()); i++) lines.add(new JsonPrimitive(JsonData.bounded(String.valueOf(text.get(i)), 1024)));
            entry.addProperty("textTotalLines", text.size()); entry.addProperty("textTruncated", text.size() > GuiDataAdapter.LINE_LIMIT);
            for (int i = 0; i < Math.min(GuiDataAdapter.LINE_LIMIT, text.size()); i++)
                if (String.valueOf(text.get(i)).length() > 1024) entry.addProperty("textTruncated", true);
        }
        entry.add("textLines", lines); entry.addProperty("textSource", "current native GuiLabel strings; fixed vanilla field mapping, read only");
        bounds(entry, ((Number) ExpandedGuiApi.field(widget, api, "field_146162_g")).intValue(), ((Number) ExpandedGuiApi.field(widget, api, "field_146174_h")).intValue(),
                ((Number) ExpandedGuiApi.field(widget, api, "field_146167_a")).intValue(), ((Number) ExpandedGuiApi.field(widget, api, "field_146161_f")).intValue());
    }
    private static Snapshot snapshot(Object screen, List<?> buttons, long started, InventoryPlayer inventory, MachineSlotBinding.Context owner) {
        Snapshot result = new Snapshot(); result.screen = screen; result.machineOwner = owner;
        if (SodiumSettingsControls.screen(screen)) {
            try {
                result.sodium = SodiumSettingsControls.capture(screen);
                for (SodiumSettingsControls.Control control : result.sodium.controls) result.add(control.widget, control.parent, "sodium_control");
                result.truncated = result.sodium.truncated;
            } catch (RuntimeException | LinkageError failure) { result.error = "sodium_widgets: " + failure.getMessage(); result.truncated = true; }
            return result;
        }
        if (PersonalSpaceGuiAdapter.screen(screen)) {
            try {
                result.personalSpace = PersonalSpaceGuiAdapter.capture(screen);
                for (PersonalSpaceGuiAdapter.Control control : result.personalSpace.controls) result.add(control.widget, control.parent, control.name);
                result.truncated = result.personalSpace.truncated;
            } catch (RuntimeException | LinkageError failure) { result.error = "personalspace_widgets: " + failure.getClass().getSimpleName(); result.truncated = true; }
            return result;
        }
        try { for (Object control : AeNativeControls.discover(screen)) if (!result.add(control, null, "native_ae_control")) break; }
        catch (RuntimeException | LinkageError failure) { result.error = "native_ae_controls: " + failure.getClass().getSimpleName(); result.truncated = true; }
        try { for (Object control : AePhantomSlots.discover(screen)) if (!result.add(control,null,"native_ae_phantom_slot")) break; }
        catch (RuntimeException | LinkageError failure) { result.error="native_ae_phantom_slots: "+failure.getClass().getSimpleName(); result.truncated=true; }
        if (buttons != null) for (Object button : buttons) if (button instanceof GuiButton) { if (!result.add(button, null, "button")) break; }
        for (String[] known : new String[][] {
                {AE_GUI + "GuiMEMonitorable", "searchField"}, {AE_GUI + "GuiCraftingCPU", "searchField"},
                {AE_GUI + "GuiCraftConfirm", "searchField"}, {AE_GUI + "GuiAmount", "amountTextField"},
                {AE_GUI + "GuiInterfaceTerminal", "searchFieldInputs"}, {AE_GUI + "GuiInterfaceTerminal", "searchFieldOutputs"}, {AE_GUI + "GuiInterfaceTerminal", "searchFieldNames"},
                {"net.minecraft.client.gui.GuiChat", "inputField"}, {"net.minecraft.client.gui.GuiChat", "field_146415_a"},
                {"net.minecraft.client.gui.GuiCreateWorld", "field_146333_g"}, {"net.minecraft.client.gui.GuiCreateWorld", "field_146335_h"}}) {
            Object field = ExpandedGuiApi.optional(screen, known[0], known[1]); if (field != null) result.add(field, null, known[1]);
        }
        ExpandedGuiApi.NativeTextControls nativeText = ExpandedGuiApi.nativeTextControls(screen);
        for (Map.Entry<Object, String> field : nativeText.controls.entrySet()) result.add(field.getKey(), null, field.getValue());
        result.truncated |= nativeText.truncated;
        if (nativeText.error != null) result.error = "native_text_fields: " + nativeText.error;
        try {
            for (net.minecraft.client.gui.GuiSlot list : NativeMenuLists.discover(screen)) {
                if (!result.add(list, null, "native_list")) break;
                for (Object row : NativeMenuLists.keyRows(list)) if (!result.add(row, list, "native_keybinding")) break;
            }
        } catch (RuntimeException | LinkageError failure) { result.error = "native_menu_lists: " + failure.getClass().getSimpleName(); }
        try {
            result.creative = CreativeGuiAdapter.capture(screen, inventory);
            if (result.creative != null) {
                for (CreativeTabs tab : result.creative.tabs) if (tab != null && result.creative.registered(tab)) { if (!result.add(tab, null, "creative_tab")) break; }
                for (Slot slot : result.creative.slots()) { if (!result.add(slot, null, "creative_player_slot")) break; }
                if (result.creative.tabsTotal > result.creative.tabs.length || result.creative.slotsTruncated) result.truncated = true;
            }
        } catch (RuntimeException | LinkageError error) { result.error = "creative_gui_unavailable: " + error.getClass().getSimpleName(); }
        boolean legacy = LegacyModularGuiAdapter.screen(screen);
        if (!legacy && !ExpandedGuiApi.is(screen, MUI + "api.IMuiScreen")) return result;
        try {
            Object panels;
            if (legacy) panels = LegacyModularGuiAdapter.windows(screen);
            else {
                Object modular = ExpandedGuiApi.call(screen, "getScreen"); Object manager = ExpandedGuiApi.call(modular, "getPanelManager");
                panels = ExpandedGuiApi.call(manager, "getOpenPanels");
            }
            ArrayDeque<Node> pending = new ArrayDeque<Node>();
            if (panels instanceof Iterable<?>) for (Object panel : (Iterable<?>) panels) { if (pending.size() >= SCAN_LIMIT) { result.truncated = true; break; } if (panel != null) pending.add(new Node(panel, null, "panel")); }
            while (!pending.isEmpty()) {
                if (result.nodes.size() >= SCAN_LIMIT || (result.nodes.size() > 0 && System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS)) { result.truncated = true; break; }
                Node node = pending.removeFirst(); if (!result.add(node.widget, node.parent, node.name)) continue;
                Object children = legacy ? LegacyModularGuiAdapter.children(node.widget) : ExpandedGuiApi.call(node.widget, "getChildren"); int count = 0;
                if (children instanceof Iterable<?>) for (Object child : (Iterable<?>) children) {
                    if (++count > SCAN_LIMIT || pending.size() + result.nodes.size() >= SCAN_LIMIT) { result.truncated = true; break; }
                    if (child != null && !result.seen.containsKey(child)) pending.add(new Node(child, node.widget, "widget"));
                }
            }
        } catch (RuntimeException | LinkageError failure) { result.error = failure.getClass().getSimpleName(); }
        return result;
    }
    private static boolean actionable(Node node, Snapshot snapshot) {
        Object widget = node.widget;
        if (snapshot.sodium != null) return SodiumSettingsControls.actionable(snapshot.sodium, widget);
        if (snapshot.personalSpace != null) {
            PersonalSpaceGuiAdapter.Control control = snapshot.personalSpace.byWidget.get(widget);
            return control.enabled && !control.dynamic && (PersonalSpaceGuiAdapter.button(widget)
                    || PersonalSpaceGuiAdapter.kind(widget, "WTextField") || PersonalSpaceGuiAdapter.kind(widget, "WSlider") || PersonalSpaceGuiAdapter.kind(widget, "WBlockDropdown"));
        }
        if (AeNativeControls.supports(widget)) return AeNativeControls.describe(widget).get("enabled").getAsBoolean();
        if (AePhantomSlots.supports(widget)) return AePhantomSlots.available(widget,snapshot.screen);
        if (widget instanceof net.minecraft.client.gui.GuiSlot) return true;
        if (NativeMenuLists.keyRow(widget)) return NativeMenuLists.visible(widget);
        if (widget instanceof GuiButton) { GuiButton button = (GuiButton) widget; return button.enabled && button.visible; }
        if (widget instanceof GuiTextField) { GuiTextField field = (GuiTextField) widget; return vanillaEnabled(field) && field.getVisible(); }
        if (snapshot.creative != null && widget instanceof CreativeTabs) {
            CreativeTabs tab = (CreativeTabs) widget;
            return snapshot.creative.nativeScreen() && snapshot.creative.visible(tab) && GuiCallbacks.supportsClick() && snapshot.creative.uniqueTabPoint(tab);
        }
        if (snapshot.creative != null && widget instanceof Slot) {
            Slot slot = (Slot) widget;
            return snapshot.creative.nativeScreen() && snapshot.creative.playerSlot(slot) && GuiCallbacks.supportsCreativePickup() && snapshot.creative.uniqueSlotPoint(slot);
        }
        if (ExpandedGuiApi.is(widget, AE_TEXT)) {
            Object raw = ExpandedGuiApi.field(widget, AE_TEXT, "field");
            return !(raw instanceof GuiTextField) || (vanillaEnabled((GuiTextField) raw) && ((GuiTextField) raw).getVisible());
        }
        if (LegacyModularGuiAdapter.supports(widget)) return (LegacyModularGuiAdapter.editable(widget) || LegacyModularGuiAdapter.button(widget))
                && muiEnabled(node, snapshot) && LegacyModularGuiAdapter.valid(widget) && LegacyModularGuiAdapter.currentWindow(widget, snapshot.screen);
        if (!ExpandedGuiApi.is(widget, MUI + "widgets.ButtonWidget") && !ExpandedGuiApi.is(widget, MUI + "widgets.textfield.TextFieldWidget")
                && !ExpandedGuiApi.is(widget, MUI + "widgets.AbstractCycleButtonWidget") && !ExpandedGuiApi.is(widget, MUI + "widgets.SliderWidget")
                && !ExpandedGuiApi.is(widget, MUI + "widgets.menu.DropdownWidget") && !GhostCircuitGuiAdapter.supports(widget) && !FluidGuiAdapter.supports(widget) && !PhantomGuiAdapter.item(widget)) return false;
        return muiEnabled(node, snapshot) && (Boolean) ExpandedGuiApi.call(widget, "isValid")
                && (!GhostCircuitGuiAdapter.supports(widget) || GhostCircuitGuiAdapter.read(widget).get("available").getAsBoolean())
                && (!FluidGuiAdapter.supports(widget) || FluidGuiAdapter.read(widget).get("nativeClickAvailable").getAsBoolean())
                && (!PhantomGuiAdapter.item(widget) || PhantomGuiAdapter.available(widget, snapshot.screen));
    }
    private static boolean contextLabel(Node node, Snapshot snapshot) {
        boolean legacy = LegacyModularGuiAdapter.label(node.widget);
        if ((!legacy && !ExpandedGuiApi.is(node.widget, MUI + "widgets.TextWidget")) || !muiEnabled(node, snapshot)
                || !(legacy ? LegacyModularGuiAdapter.valid(node.widget) : (Boolean) ExpandedGuiApi.call(node.widget, "isValid"))) return false;
        Boolean known = snapshot.contextParents.get(node.parent); if (known != null) return known;
        for (Node sibling : snapshot.nodes) if (sibling != node && sibling.parent == node.parent && actionable(sibling, snapshot)) { snapshot.contextParents.put(node.parent, true); return true; }
        snapshot.contextParents.put(node.parent, false);
        return false;
    }
    private static boolean muiEnabled(Node node, Snapshot snapshot) {
        boolean enabled = (Boolean) ExpandedGuiApi.call(node.widget, "isEnabled"); Object parent = node.parent; int count = 0;
        while (parent != null && count++ < SCAN_LIMIT) { enabled &= (Boolean) ExpandedGuiApi.call(parent, "isEnabled"); Node ancestor = snapshot.seen.get(parent); parent = ancestor == null ? null : ancestor.parent; }
        return enabled;
    }
    private static JsonObject describeContextLabel(Node node, Snapshot snapshot) {
        Object widget = node.widget; JsonObject entry = new JsonObject(); entry.addProperty("widgetId", id(widget));
        entry.add("parentId", node.parent == null ? JsonNull.INSTANCE : new JsonPrimitive(id(node.parent)));
        boolean legacy = LegacyModularGuiAdapter.label(widget);
        entry.addProperty("class", widget.getClass().getName()); entry.addProperty("name", JsonData.bounded(legacy ? LegacyModularGuiAdapter.name(widget) : String.valueOf(ExpandedGuiApi.call(widget, "getName")), 256));
        entry.addProperty("kind", "label"); entry.addProperty("contextOnly", true);
        entry.addProperty("enabled", muiEnabled(node, snapshot)); entry.addProperty("valid", legacy ? LegacyModularGuiAdapter.valid(widget) : (Boolean) ExpandedGuiApi.call(widget, "isValid"));
        String text = legacy ? LegacyModularGuiAdapter.labelText(widget) : String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getKey"), "getFormatted"));
        entry.addProperty("text", JsonData.bounded(text, 1024));
        Object area = ExpandedGuiApi.call(widget, "getArea"); if (area instanceof Rectangle) { Rectangle box = (Rectangle) area; bounds(entry, box.x, box.y, box.width, box.height); }
        entry.add("supportedActions", new JsonArray()); entry.addProperty("source", "Public TextWidget text sharing a parent with an enabled actionable control; tooltip and textures not queried.");
        return entry;
    }
    private static JsonObject describe(Node node, Snapshot snapshot) {
        Object widget = node.widget; JsonObject entry = new JsonObject(); entry.addProperty("widgetId", id(widget));
        entry.add("parentId", node.parent == null ? JsonNull.INSTANCE : new JsonPrimitive(id(node.parent))); JsonArray children = new JsonArray();
        int childCount = 0;
        for (Node child : snapshot.nodes) if (child.parent == widget) { if (++childCount <= 128) children.add(new JsonPrimitive(id(child.widget))); }
        entry.add("childIds", children); entry.addProperty("childCount", childCount); entry.addProperty("childIdsTruncated", childCount > 128);
        entry.addProperty("class", widget.getClass().getName()); entry.addProperty("name", node.name); JsonArray actions = new JsonArray();
        if (snapshot.sodium != null) {
            SodiumSettingsControls.describe(snapshot.sodium, widget, entry, actions);
        } else if (snapshot.personalSpace != null) {
            PersonalSpaceGuiAdapter.describe(snapshot.personalSpace, widget, entry, actions);
        } else if (AePhantomSlots.supports(widget)) {
            JsonObject ghost = AePhantomSlots.describe(widget,snapshot.screen);
            for (Map.Entry<String,JsonElement> value : ghost.entrySet()) entry.add(value.getKey(),value.getValue());
            if (ghost.get("enabled").getAsBoolean()) { actions.add(new JsonPrimitive("ghost_drag")); actions.add(new JsonPrimitive("phantom_click")); }
        } else if (AeNativeControls.supports(widget)) {
            JsonObject nativeAe = AeNativeControls.describe(widget);
            for (Map.Entry<String, JsonElement> value : nativeAe.entrySet()) entry.add(value.getKey(), value.getValue());
            actions.add(new JsonPrimitive("activate"));
        } else if (widget instanceof net.minecraft.client.gui.GuiSlot || NativeMenuLists.keyRow(widget)) {
            JsonObject nativeList = NativeMenuLists.describe(widget);
            for (Map.Entry<String, JsonElement> value : nativeList.entrySet()) entry.add(value.getKey(), value.getValue());
            actions.add(new JsonPrimitive("set_value"));
            if (widget instanceof net.minecraft.client.gui.GuiSlot && NativeMenuLists.selectable((net.minecraft.client.gui.GuiSlot) widget)) actions.add(new JsonPrimitive("set_selected"));
        } else if (snapshot.creative != null && (widget instanceof CreativeTabs || widget instanceof Slot)) {
            JsonObject creative = widget instanceof CreativeTabs ? CreativeGuiAdapter.describe((CreativeTabs) widget, snapshot.creative) : CreativeGuiAdapter.describe((Slot) widget, snapshot.creative);
            for (Map.Entry<String, JsonElement> value : creative.entrySet()) entry.add(value.getKey(), value.getValue());
            if (entry.get("enabled").getAsBoolean()) actions.add(new JsonPrimitive("activate"));
        } else if (widget instanceof GuiButton && VanillaSettingsControls.supports(widget)) {
            GuiButton button = (GuiButton) widget;
            entry.addProperty("kind", "slider"); entry.addProperty("nativeButtonId", button.id);
            entry.addProperty("text", JsonData.bounded(button.displayString, 512));
            entry.addProperty("visible", button.visible); entry.addProperty("enabled", button.enabled && button.visible); entry.addProperty("valid", true);
            bounds(entry, button.xPosition, button.yPosition, button.width, button.height);
            JsonObject state = VanillaSettingsControls.read(button);
            for (Map.Entry<String, JsonElement> value : state.entrySet()) entry.add(value.getKey(), value.getValue());
            actions.add(new JsonPrimitive("set_value"));
        } else if (widget instanceof GuiButton) {
            GuiButton button = (GuiButton) widget;
            entry.addProperty("kind", "button"); entry.addProperty("nativeButtonId", button.id); entry.addProperty("text", JsonData.bounded(button.displayString, 512));
            entry.addProperty("visible", button.visible); entry.addProperty("enabled", button.enabled && button.visible); entry.addProperty("valid", true);
            bounds(entry, button.xPosition, button.yPosition, button.width, button.height); actions.add(new JsonPrimitive("activate"));
        } else if (widget instanceof GuiTextField) {
            GuiTextField field = (GuiTextField) widget;
            entry.addProperty("kind", "text"); entry.addProperty("text", JsonData.bounded(field.getText(), 1024)); entry.addProperty("focused", field.isFocused());
            entry.addProperty("textLength", field.getText().length()); entry.addProperty("textTruncated", field.getText().length() > 1024);
            entry.addProperty("valueSource", "native_client_field_text"); entry.addProperty("commitSemantics", "Native setText replaces the field; generic submit callback unavailable.");
            entry.addProperty("enabled", vanillaEnabled(field)); entry.addProperty("valid", field.getVisible());
            bounds(entry, field.xPosition, field.yPosition, field.width, field.height); actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
        } else if (ExpandedGuiApi.is(widget, AE_TEXT)) {
            Object raw = ExpandedGuiApi.field(widget, AE_TEXT, "field");
            entry.addProperty("kind", "text"); entry.addProperty("text", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(widget, "getText")), 1024)); entry.addProperty("focused", (Boolean) ExpandedGuiApi.call(widget, "isFocused"));
            entry.addProperty("valueSource", "native_client_ae_field_text"); entry.addProperty("commitSemantics", "Native setText replaces the field; generic submit callback unavailable.");
            entry.addProperty("enabled", raw instanceof GuiTextField ? vanillaEnabled((GuiTextField) raw) : true); entry.addProperty("valid", !(raw instanceof GuiTextField) || ((GuiTextField) raw).getVisible());
            bounds(entry, ((Number) ExpandedGuiApi.field(widget, AE_TEXT, "x")).intValue(), ((Number) ExpandedGuiApi.field(widget, AE_TEXT, "y")).intValue(), ((Number) ExpandedGuiApi.field(widget, AE_TEXT, "w")).intValue(), ((Number) ExpandedGuiApi.field(widget, AE_TEXT, "h")).intValue());
            actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
        } else if (ExpandedGuiApi.is(widget, "net.minecraft.client.gui.GuiLabel")) {
            describeVanillaLabel(widget, entry);
        } else if (LegacyModularGuiAdapter.supports(widget)) {
            entry.addProperty("enabled", muiEnabled(node, snapshot)); LegacyModularGuiAdapter.describe(widget, snapshot.screen, entry, actions);
        } else {
            entry.addProperty("kind", "widget");
            entry.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(widget, "getName")), 256));
            entry.addProperty("enabled", muiEnabled(node, snapshot)); entry.addProperty("valid", (Boolean) ExpandedGuiApi.call(widget, "isValid"));
            Object area = ExpandedGuiApi.call(widget, "getArea"); if (area instanceof Rectangle) { Rectangle box = (Rectangle) area; bounds(entry, box.x, box.y, box.width, box.height); }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.TextWidget")) { Object key = ExpandedGuiApi.call(widget, "getKey"); entry.addProperty("text", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(key, "getFormatted")), 512)); }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.ButtonWidget")) { entry.addProperty("kind", "button"); actions.add(new JsonPrimitive("activate")); }
            if (GhostCircuitGuiAdapter.supports(widget)) {
                JsonObject circuit = GhostCircuitGuiAdapter.read(widget); entry.add("circuitConfig", circuit);
                entry.addProperty("kind", "ghost_circuit"); entry.addProperty("semantic", "ghost_circuit");
                entry.addProperty("nativeAction", "Public GhostCircuitSlotWidget.onMousePressed(0), followed by native no-op onMouseRelease(0); the normal left-click sync path sends at most one packet and respects the native selector-panel gate.");
                if (circuit.get("available").getAsBoolean()) actions.add(new JsonPrimitive("activate"));
                if (GhostCircuitGuiAdapter.setterAvailable(widget, snapshot.screen)) {
                    actions.add(new JsonPrimitive("set_value")); entry.addProperty("setValueApi", GhostCircuitGuiAdapter.SET_VALUE_API);
                }
            }
            if (FluidGuiAdapter.supports(widget)) {
                JsonObject fluid = FluidGuiAdapter.read(widget, snapshot.machineOwner); entry.add("fluidSlot", fluid);
                entry.addProperty("kind", "fluid_slot"); entry.addProperty("semantic", "fluid_slot");
                if (fluid.get("nativeClickAvailable").getAsBoolean()) actions.add(new JsonPrimitive("activate"));
            }
            if (PhantomGuiAdapter.supports(widget)) {
                JsonObject ghost = PhantomGuiAdapter.read(widget, snapshot.screen); entry.add("ghostSlot", ghost);
                if (PhantomGuiAdapter.item(widget)) { entry.addProperty("kind", "phantom_item_slot"); entry.addProperty("semantic", "phantom_item_slot"); }
                if (ghost.get("available").getAsBoolean()) {
                    if (PhantomGuiAdapter.item(widget)) actions.add(new JsonPrimitive("activate"));
                    actions.add(new JsonPrimitive("phantom_click"));
                    if (ghost.has("dragAvailable") && ghost.get("dragAvailable").getAsBoolean()) actions.add(new JsonPrimitive("ghost_drag"));
                }
            }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.textfield.TextFieldWidget")) {
                entry.addProperty("kind", "text"); entry.addProperty("text", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(widget, "getText")), 1024)); entry.addProperty("focused", (Boolean) ExpandedGuiApi.call(widget, "isFocused"));
                entry.addProperty("value", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getStringValue"), "getStringValue")), 1024));
                entry.addProperty("valueSource", "native_client_string_value; not authoritative server acknowledgement");
                entry.addProperty("commitSemantics", "Native removeFocus validates then sets the native string value with its normal configured sync.");
                actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
            }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.AbstractCycleButtonWidget")) {
                entry.addProperty("kind", "choice"); Object value = ExpandedGuiApi.call(widget, "getIntValue");
                entry.addProperty("selected", ((Number) ExpandedGuiApi.call(value, "getIntValue")).intValue()); entry.addProperty("optionCount", ((Number) ExpandedGuiApi.call(widget, "getStateCount")).intValue());
                actions.add(new JsonPrimitive("set_selected")); actions.add(new JsonPrimitive("activate"));
            }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.SliderWidget")) {
                entry.addProperty("kind", "slider"); entry.addProperty("value", (Number) ExpandedGuiApi.call(widget, "getSliderValue"));
                entry.addProperty("min", (Number) ExpandedGuiApi.call(widget, "getMin")); entry.addProperty("max", (Number) ExpandedGuiApi.call(widget, "getMax")); actions.add(new JsonPrimitive("set_value"));
            }
            if (ExpandedGuiApi.is(widget, MUI + "widgets.menu.DropdownWidget")) {
                entry.addProperty("kind", "choice"); List<?> values = (List<?>) ExpandedGuiApi.field(widget, MUI + "widgets.menu.DropdownWidget", "values");
                Object selected = ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getValue"), "getValue"); entry.addProperty("selected", values.indexOf(selected)); entry.addProperty("value", JsonData.bounded(String.valueOf(selected), 512));
                JsonArray options = new JsonArray(); for (int i = 0; i < Math.min(128, values.size()); i++) options.add(new JsonPrimitive(JsonData.bounded(String.valueOf(values.get(i)), 512)));
                entry.add("options", options); entry.addProperty("optionCount", values.size()); entry.addProperty("optionsTruncated", values.size() > 128); actions.add(new JsonPrimitive("set_selected"));
            }
        }
        GuiPublicSemantics.describe(widget, entry, snapshot.screen);
        if (entry.has("semantic") && "structure_recheck".equals(entry.get("semantic").getAsString())
                && ExpandedGuiApi.is(widget, MUI + "widgets.AbstractCycleButtonWidget") && entry.get("optionCount").getAsInt() == 2)
            actions.add(new JsonPrimitive("recheck_structure"));
        if (!entry.get("enabled").getAsBoolean() && !entry.has("disabledReason")) entry.addProperty("disabledReason", "native_control_or_ancestor_disabled_or_hidden");
        if (!entry.get("valid").getAsBoolean()) entry.addProperty("invalidReason", "native_control_not_initialized_or_not_visible");
        if (actions.size() == 0 && !entry.has("readOnlyReason")) entry.addProperty("readOnlyReason", "no_verified_native_mutation_api");
        entry.add("supportedActions", actions); return entry;
    }
    private static boolean vanillaEnabled(GuiTextField field) {
        for (String name : new String[] {"isEnabled", "field_146226_p"}) { try { return (Boolean) ExpandedGuiApi.field(field, GuiTextField.class, name); } catch (BridgeException e) { /* Fixed mapping fallback. */ } }
        throw new BridgeException("unsupported", "Native text field enabled state is unavailable.");
    }
    private static boolean nativeStructureState(Object widget, JsonObject entry) {
        return widget.getClass().getName().equals(MUI + "widgets.ToggleButton")
                && ExpandedGuiApi.is(widget, MUI + "widgets.AbstractCycleButtonWidget")
                && entry.has("semantic") && "structure_recheck".equals(entry.get("semantic").getAsString())
                && entry.has("optionCount") && entry.get("optionCount").getAsInt() == 2;
    }
    private static JsonObject activate(Object screen, Object widget, int button, BooleanSupplier stillCurrent) {
        neutralInput();
        if (AeNativeControls.supports(widget)) return AeNativeControls.activate(widget, packet -> AeControlAdapter.send(screen, packet));
        JsonObject returns = new JsonObject();
        if (widget instanceof GuiButton) { ExpandedGuiApi.protectedCall(screen, GuiScreen.class, new String[] {"actionPerformed", "func_146284_a"}, GuiButton.class, widget); returns.add("actionPerformed", JsonNull.INSTANCE); return returns; }
        if (LegacyModularGuiAdapter.button(widget)) { callbackReturn(returns, "onClick", LegacyModularGuiAdapter.activate(widget)); return returns; }
        if (ExpandedGuiApi.is(widget, MUI + "widgets.ButtonWidget"))
            return MuiButtonGesture.activate(screen, widget, button, stillCurrent);
        Object pressed = ExpandedGuiApi.call(widget, "onMousePressed", button);
        callbackReturn(returns, "onMousePressed", pressed);
        if (!stillCurrent.getAsBoolean()) { returns.addProperty("remainingCallbacksSkipped", "native_screen_or_container_changed"); return returns; }
        if (stillCurrent.getAsBoolean()) callbackReturn(returns, "onMouseRelease", ExpandedGuiApi.call(widget, "onMouseRelease", button));
        else returns.addProperty("remainingCallbacksSkipped", "native_screen_or_container_changed");
        return returns;
    }
    private static void callbackReturn(JsonObject result, String name, Object value) {
        if (value == null) result.add(name, JsonNull.INSTANCE);
        else if (value instanceof Boolean) result.addProperty(name, (Boolean) value);
        else if (value instanceof Enum<?>) result.addProperty(name, ((Enum<?>) value).name());
        else result.addProperty(name, JsonData.bounded(String.valueOf(value), 128));
    }
    public static void neutralInput() {
        try {
            if ((Keyboard.isCreated() && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))) || (Mouse.isCreated() && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1) || Mouse.isButtonDown(2)))) throw new BridgeException("input_busy", "Release physical modifiers and mouse buttons before semantic activation.");
        } catch (LinkageError ignored) { /* Headless fixtures have no native input classes. */ }
    }
    private static void setText(Object widget, JsonObject args, BooleanSupplier stillCurrent) {
        String text = ExpandedGuiApi.string(args, "text", 1024); boolean commit = GuiDataAdapter.booleanArg(args, "commit", true);
        if (widget instanceof GuiTextField) { GuiTextField field = (GuiTextField) widget; if (text.length() > field.getMaxStringLength()) throw new BridgeException("invalid_args", "Text exceeds this native field's length limit."); field.setText(text); return; }
        if (ExpandedGuiApi.is(widget, AE_TEXT)) { if (text.length() > ((Number) ExpandedGuiApi.call(widget, "getMaxStringLength")).intValue()) throw new BridgeException("invalid_args", "Text exceeds this AE field's length limit."); ExpandedGuiApi.call(widget, "setText", text); return; }
        if (LegacyModularGuiAdapter.editable(widget)) { LegacyModularGuiAdapter.setText(widget, text, commit, stillCurrent); return; }
        Object handler = ExpandedGuiApi.field(widget, MUI + "widgets.textfield.BaseTextFieldWidget", "handler");
        if (!(Boolean) ExpandedGuiApi.call(handler, "test", text)) throw new BridgeException("invalid_args", "Text fails this widget's native pattern/length validator.");
        focus(widget, true);
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The native screen/container changed while focusing; no replacement text was written.");
        if (!(Boolean) ExpandedGuiApi.call(widget, "isFocused")) throw new BridgeException("target_unverified", "Native focus was not accepted; no replacement text was written.");
        ExpandedGuiApi.call(widget, "setText", text);
        if (commit) {
            if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The native screen/container changed before submission.");
            focus(widget, false);
        }
    }
    private static void focus(Object widget, boolean focused) {
        if (widget instanceof GuiTextField) { ((GuiTextField) widget).setFocused(focused); return; }
        if (ExpandedGuiApi.is(widget, AE_TEXT)) { ExpandedGuiApi.call(widget, "setFocused", focused); return; }
        if (LegacyModularGuiAdapter.editable(widget)) { LegacyModularGuiAdapter.focus(widget, focused); return; }
        Object context = ExpandedGuiApi.call(widget, "getContext");
        if (focused) ExpandedGuiApi.call(context, "focus", widget);
        else if ((Boolean) ExpandedGuiApi.call(widget, "isFocused")) ExpandedGuiApi.call(context, "removeFocus");
    }
    private static void bounds(JsonObject entry, int x, int y, int width, int height) { entry.addProperty("x", x); entry.addProperty("y", y); entry.addProperty("width", width); entry.addProperty("height", height); }
    private static final class Node { final Object widget, parent; final String name; Node(Object widget, Object parent, String name) { this.widget = widget; this.parent = parent; this.name = name; } }
    private static final class Snapshot {
        final List<Node> nodes = new ArrayList<Node>(); final IdentityHashMap<Object, Node> seen = new IdentityHashMap<Object, Node>(); boolean truncated; String error;
        final IdentityHashMap<Object, Boolean> contextParents = new IdentityHashMap<Object, Boolean>();
        CreativeGuiAdapter.State creative;
        PersonalSpaceGuiAdapter.State personalSpace;
        SodiumSettingsControls.State sodium;
        MachineSlotBinding.Context machineOwner;
        Object screen;
        boolean add(Object widget, Object parent, String name) { if (seen.containsKey(widget)) return false; if (nodes.size() >= SCAN_LIMIT) { truncated = true; return false; } Node node = new Node(widget, parent, name); id(widget); nodes.add(node); seen.put(widget, node); return true; }
    }
}
