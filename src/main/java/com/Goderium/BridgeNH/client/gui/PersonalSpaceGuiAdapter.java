package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiTextField;

/** Optional PersonalSpace widget API. All writes use native field/input callbacks, never dimension data. */
public final class PersonalSpaceGuiAdapter {
    private static final String API = "me.eigenraven.personalspace.gui.";
    private static WeakReference<Object> renderedScreen = new WeakReference<Object>(null);
    private static long frame, editedFrame = -1;
    private static WeakReference<Object> commitScreen = new WeakReference<Object>(null);
    private static final IdentityHashMap<Object, GuiTextField> pendingCommits = new IdentityHashMap<Object, GuiTextField>();
    private PersonalSpaceGuiAdapter() {}
    static boolean screen(Object value) { return ExpandedGuiApi.is(value, API + "GuiEditWorld"); }
    static boolean supports(Object value) { return ExpandedGuiApi.is(value, API + "Widget"); }
    static boolean kind(Object value, String name) { return ExpandedGuiApi.is(value, API + name); }
    static boolean button(Object value) { return kind(value, "WButton"); }
    public static void rendered(Object screen) {
        if (!screen(screen)) return;
        if (renderedScreen.get() != screen) { renderedScreen = new WeakReference<Object>(screen); frame = 0; editedFrame = -1; }
        frame++;
        if (!pendingCommits.isEmpty()) {
            if (commitScreen.get() == screen) {
                // GuiEditWorld overwrites unfocused extended fields BEFORE parsing them. Release focus only after its own draw has parsed the edit.
                try {
                    State live = capture(screen);
                    for (java.util.Map.Entry<Object, GuiTextField> entry : pendingCommits.entrySet())
                        if (live.byWidget.containsKey(entry.getKey()) && textField(entry.getKey()) == entry.getValue()) entry.getValue().setFocused(false);
                } catch (RuntimeException | LinkageError unavailable) { return; }
            }
            clearPending();
        }
    }
    public static void clearPending() { pendingCommits.clear(); commitScreen.clear(); }
    static boolean pending(Object screen) { return renderedScreen.get() != screen || frame <= editedFrame || (commitScreen.get() == screen && !pendingCommits.isEmpty()); }
    private static void edited(Object screen) {
        if (renderedScreen.get() != screen) { renderedScreen = new WeakReference<Object>(screen); frame = 0; }
        editedFrame = frame;
    }
    private static Object field(Object object, String name) {
        try { return object.getClass().getField(name).get(object); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "PersonalSpace public widget field unavailable: " + name); }
    }
    private static int integer(Object object, String name) { return ((Number) field(object, name)).intValue(); }
    private static boolean flag(Object object, String name) { return (Boolean) field(object, name); }
    private static Object dropdownField(Object widget, String name) { return ExpandedGuiApi.field(widget, API + "WBlockDropdown", name); }
    private static Object dropdownCall(Object widget, String name, Object... args) { return ExpandedGuiApi.call(ExpandedGuiApi.load(widget, API + "WBlockDropdown"), name, args); }
    private static GuiTextField textField(Object widget) { return (GuiTextField) field(widget, "textField"); }
    static String text(Object widget) {
        if (kind(widget, "WTextField")) return textField(widget).getText();
        if (kind(widget, "WBlockDropdown")) {
            Object search = dropdownField(widget, "searchField");
            return (Boolean) ExpandedGuiApi.call(widget, "isOpen") && search instanceof GuiTextField ? ((GuiTextField) search).getText() : "";
        }
        return button(widget) || kind(widget, "WLabel") || kind(widget, "WSlider") ? String.valueOf(ExpandedGuiApi.call(widget, "getText")) : "";
    }
    static final class Control {
        final Object widget, parent; final String name; final Rectangle bounds; final boolean visible, enabled, dynamic;
        Control(Object widget, Object parent, String name, Rectangle bounds, boolean visible, boolean enabled, boolean dynamic) {
            this.widget = widget; this.parent = parent; this.name = name; this.bounds = bounds; this.visible = visible; this.enabled = enabled; this.dynamic = dynamic;
        }
    }
    static final class State {
        final Object screen; final List<Control> controls = new ArrayList<Control>();
        final IdentityHashMap<Object, Control> byWidget = new IdentityHashMap<Object, Control>();
        boolean truncated;
        java.util.function.Consumer<Control> hover;
        State(Object screen) { this.screen = screen; }
    }
    static State capture(Object screen) {
        State state = new State(screen); IdentityHashMap<Object, String> names = new IdentityHashMap<Object, String>();
        for (Field f : screen.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || ExpandedGuiApi.named(f.getType(), API + "Widget") == null) continue;
            try { Object value = f.get(screen); if (value != null) names.put(value, f.getName()); }
            catch (IllegalAccessException error) { throw new BridgeException("unsupported", "PersonalSpace public widget tree unavailable."); }
        }
        Object root = field(screen, "rootWidget");
        ArrayDeque<Control> queue = new ArrayDeque<Control>();
        queue.add(control(root, null, names.get(root), integer(screen, "guiLeft"), integer(screen, "guiTop"), true, false));
        while (!queue.isEmpty()) {
            if (state.controls.size() >= 4096) { state.truncated = true; break; }
            Control c = queue.removeFirst(); if (state.byWidget.containsKey(c.widget)) continue;
            state.controls.add(c); state.byWidget.put(c.widget, c);
            Object children = field(c.widget, "children");
            if (!(children instanceof List<?>)) throw new BridgeException("unsupported", "PersonalSpace widget children unavailable.");
            int index = 0;
            for (Object child : (List<?>) children) {
                if (queue.size() + state.controls.size() >= 4096) { state.truncated = true; break; }
                if (!supports(child)) continue;
                String name = names.get(child); boolean dynamic = c.dynamic || ("presetEditor".equals(c.name) && name == null);
                queue.add(control(child, c.widget, name == null ? c.name + "/" + index : name, c.bounds.x, c.bounds.y, c.visible, dynamic)); index++;
            }
        }
        return state;
    }
    private static Control control(Object widget, Object parent, String name, int x, int y, boolean parentVisible, boolean dynamic) {
        Rectangle local = (Rectangle) field(widget, "position"); Rectangle bounds = new Rectangle(x + local.x, y + local.y, local.width, local.height);
        // Widget.mouseClicked checks each child's enabled flag independently; disabled image parents still have usable children.
        boolean visible = parentVisible && flag(widget, "visible");
        return new Control(widget, parent, name == null ? "rootWidget" : name, bounds, visible, visible && flag(widget, "enabled"), dynamic);
    }
    static void describe(State state, Object widget, JsonObject out, JsonArray actions) {
        Control c = state.byWidget.get(widget); if (c == null) throw new BridgeException("stale_widget", "PersonalSpace control left the live tree.");
        out.addProperty("framework", "personalspace"); out.addProperty("kind", "widget"); out.addProperty("visible", c.visible);
        out.addProperty("valid", true); out.addProperty("enabled", c.enabled); out.addProperty("name", c.name);
        out.addProperty("x", c.bounds.x); out.addProperty("y", c.bounds.y); out.addProperty("width", c.bounds.width); out.addProperty("height", c.bounds.height);
        if (button(widget) || kind(widget, "WTextField") || kind(widget, "WLabel") || kind(widget, "WSlider")) GuiControlAdapter.displayText(out, text(widget));
        if (button(widget) || kind(widget, "WTextField")) { Object tooltip = field(widget, "tooltip"); if (tooltip != null) out.addProperty("tooltipText", JsonData.bounded(String.valueOf(tooltip), 1024)); }
        if (button(widget)) {
            out.addProperty("kind", "button"); actions.add(new JsonPrimitive("activate"));
            Object icon = field(widget, "buttonIcon"); if (icon instanceof Enum<?>) out.addProperty("icon", ((Enum<?>) icon).name());
            Object stack = field(widget, "itemStack"); if (stack instanceof net.minecraft.item.ItemStack) out.add("item", JsonData.item((net.minecraft.item.ItemStack) stack));
            Object count = field(widget, "itemStackText"); if (count != null) out.addProperty("itemText", JsonData.bounded(String.valueOf(count), 64));
        }
        if (kind(widget, "WLabel")) out.addProperty("kind", "label");
        if (kind(widget, "WTextField")) {
            out.addProperty("kind", "text"); out.addProperty("focused", textField(widget).isFocused()); out.addProperty("maxLength", textField(widget).getMaxStringLength());
            actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
            out.addProperty("commitSemantics", "Client text only; native next frame validates and applies it. Activate save after pendingNativeFrame=false; cancel discards edits.");
        }
        if (kind(widget, "WSlider")) {
            out.addProperty("kind", "slider"); out.addProperty("value", (Number) ExpandedGuiApi.call(widget, "getValue"));
            out.addProperty("min", (Number) field(widget, "minValue")); out.addProperty("max", (Number) field(widget, "maxValue")); out.addProperty("step", (Number) field(widget, "step"));
            out.addProperty("resolution", "Native integer slider pixels and step snapping; inspect actual value."); actions.add(new JsonPrimitive("set_value"));
        }
        if (kind(widget, "WToggleButton")) {
            out.addProperty("kind", "choice"); out.addProperty("selected", (Boolean) ExpandedGuiApi.call(widget, "getValue") ? 1 : 0); out.addProperty("optionCount", 2); actions.add(new JsonPrimitive("set_selected"));
        } else if (kind(widget, "WCycleButton")) {
            Enum<?> selected = (Enum<?>) ExpandedGuiApi.call(widget, "getState"); out.addProperty("kind", "choice"); out.addProperty("selected", selected.ordinal());
            JsonArray options = new JsonArray(); for (Object option : selected.getDeclaringClass().getEnumConstants()) options.add(new JsonPrimitive(((Enum<?>) option).name()));
            out.add("options", options); out.addProperty("optionCount", options.size()); actions.add(new JsonPrimitive("set_selected"));
        } else if (kind(widget, "WBlockDropdown")) {
            out.addProperty("kind", "dropdown"); boolean open = (Boolean) ExpandedGuiApi.call(widget, "isOpen"); out.addProperty("open", open);
            out.addProperty("selected", (Number) ExpandedGuiApi.call(widget, "getSelectedIndex")); out.addProperty("addMode", (Boolean) dropdownField(widget, "addMode"));
            List<?> all = (List<?>) ExpandedGuiApi.call(widget, "getEntries"), filtered = open ? (List<?>) dropdownField(widget, "filteredEntries") : all;
            out.addProperty("optionCount", all.size()); out.addProperty("filteredCount", filtered.size()); out.addProperty("optionsTruncated", filtered.size() > 32); out.addProperty("text", text(widget));
            JsonArray options = new JsonArray(); for (int i = 0; i < Math.min(32, filtered.size()); i++) {
                Object option = filtered.get(i); JsonObject value = new JsonObject(); value.addProperty("index", all.indexOf(option));
                value.addProperty("block", String.valueOf(ExpandedGuiApi.call(option, "blockName"))); value.addProperty("meta", (Number) ExpandedGuiApi.call(option, "meta"));
                value.addProperty("text", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(option, "tooltip")), 256)); options.add(value);
            }
            out.add("options", options); actions.add(new JsonPrimitive("activate")); actions.add(new JsonPrimitive("set_selected")); actions.add(new JsonPrimitive("set_text"));
            out.addProperty("selectionSemantics", "selected is the original option index; set_text searches through the native dropdown. ADD mode adds one layer per selection.");
        }
        out.addProperty("pendingNativeFrame", pending(state.screen));
        if (c.dynamic) { actions = clear(actions); out.addProperty("actionUnavailable", "Layer row objects are recreated every frame. Edit presetEntry or use layersBlockDropdown; stale row IDs are never replayed."); }
        if (!c.enabled) clear(actions);
        if ((Boolean) dropdownCall(widget, "isAnyDropdownOpen") && !kind(widget, "WBlockDropdown")) {
            clear(actions); out.addProperty("actionUnavailable", "Close the native dropdown before operating another control.");
        }
    }
    private static JsonArray clear(JsonArray values) { while (values.size() > 0) values.remove(values.size() - 1); return values; }
    static JsonObject perform(State state, Object widget, String action, JsonObject args, int mouse, BooleanSupplier current) {
        Control c = state.byWidget.get(widget); JsonObject result = new JsonObject();
        if (c == null || !c.enabled || c.dynamic) throw new BridgeException("disabled", "PersonalSpace control is not currently actionable.");
        if ("save".equals(c.name) && pending(state.screen)) throw new BridgeException("not_ready", "Wait for a completed native GUI frame before saving edited settings; inspect pendingNativeFrame.");
        boolean dropdown = kind(widget, "WBlockDropdown");
        if ((Boolean) dropdownCall(widget, "isAnyDropdownOpen") && (!dropdown || !(Boolean) ExpandedGuiApi.call(widget, "isOpen"))) throw new BridgeException("disabled", "Another native dropdown is open.");
        // Even a callback that throws after changing local text must not allow immediate submission of old configuration.
        edited(state.screen);
        if (dropdown) dropdownAction(state, c, action, args, current);
        else if (action.equals("set_text")) {
            String value = ExpandedGuiApi.string(args, "text", 1024); GuiTextField input = textField(widget);
            if (value.length() > input.getMaxStringLength()) throw new BridgeException("invalid_args", "Text exceeds this native field's limit.");
            input.setText(value); input.setFocused(true);
            if (commitScreen.get() != state.screen) { clearPending(); commitScreen = new WeakReference<Object>(state.screen); }
            if (GuiDataAdapter.booleanArg(args, "commit", true)) pendingCommits.put(widget, input);
            else pendingCommits.remove(widget);
        } else if (action.equals("focus")) {
            if (!args.has("focused")) throw new BridgeException("invalid_args", "focused is required.");
            if (!GuiDataAdapter.booleanArg(args, "focused", false) && pendingCommits.containsKey(widget)) throw new BridgeException("not_ready", "Wait for native text parsing before releasing this field's focus.");
            textField(widget).setFocused(GuiDataAdapter.booleanArg(args, "focused", false));
        } else if (action.equals("set_value")) {
            double min = ((Number) field(widget, "minValue")).doubleValue(), max = ((Number) field(widget, "maxValue")).doubleValue(), step = ((Number) field(widget, "step")).doubleValue();
            double requested = JsonData.number(args, "value", min, max);
            int px = VanillaSettingsControls.pixel(requested, c.bounds.width - 6, fraction -> { double v = min + fraction * (max - min); return step > 1.0e-6 ? Math.round(v / step) * step : v; });
            click(c, 3 + px, c.bounds.height / 2, 0, current); result.addProperty("requestedValue", requested); result.addProperty("actualValue", (Number) ExpandedGuiApi.call(widget, "getValue"));
        } else if (action.equals("set_selected")) {
            if (kind(widget, "WToggleButton")) {
                int selected = JsonData.integer(args, "selected", 0, 1); if (((Boolean) ExpandedGuiApi.call(widget, "getValue") ? 1 : 0) != selected) click(c, c.bounds.width / 2, c.bounds.height / 2, 0, current);
            } else {
                Enum<?> value = (Enum<?>) ExpandedGuiApi.call(widget, "getState"); int count = value.getDeclaringClass().getEnumConstants().length;
                if (count < 1 || count > 16) throw new BridgeException("unsupported", "Unbounded native cycle state.");
                int selected = JsonData.integer(args, "selected", 0, count - 1);
                for (int i = 0; i < count && ((Enum<?>) ExpandedGuiApi.call(widget, "getState")).ordinal() != selected; i++) click(c, c.bounds.width / 2, c.bounds.height / 2, 0, current);
            }
        } else click(c, c.bounds.width / 2, c.bounds.height / 2, mouse, current);
        result.addProperty("nativeAction", "personalspace_native_widget_input"); result.addProperty("pendingNativeFrame", current.getAsBoolean() && pending(state.screen));
        result.addProperty("serverConfirmed", false); return result;
    }
    private static void click(Control c, int x, int y, int button, BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "PersonalSpace screen changed before native input.");
        Rectangle local = (Rectangle) field(c.widget, "position");
        try { if (!(Boolean) ExpandedGuiApi.call(c.widget, "mouseClicked", local.x + x, local.y + y, button)) throw new BridgeException("target_unverified", "Native widget rejected its press."); }
        finally { ExpandedGuiApi.call(c.widget, "mouseMovedOrUp", local.x + x, local.y + y, button); }
    }
    private static int[] dropdownPosition(Object widget) {
        return (int[]) ExpandedGuiApi.declaredCall(widget, API + "WBlockDropdown", "computeDropdownPosition", widget, dropdownField(widget, "filteredEntries"));
    }
    private static void dropdownAction(State state, Control c, String action, JsonObject args, BooleanSupplier current) {
        Object widget = c.widget;
        if (action.equals("activate")) {
            boolean open = (Boolean) ExpandedGuiApi.call(widget, "isOpen");
            if (!open && state.hover != null) state.hover.accept(c);
            click(c, c.bounds.width / 2, c.bounds.height / 2, 0, current);
            if (open && state.hover != null) state.hover.accept(null);
            return;
        }
        // Validate before opening a popup or modifying its search text.
        String search = action.equals("set_text") ? ExpandedGuiApi.string(args, "text", 1024) : null;
        List<?> all = (List<?>) ExpandedGuiApi.call(widget, "getEntries");
        int selected = search == null ? JsonData.integer(args, "selected", 0, all.size() - 1) : -1;
        if (search != null && (search.length() > 256 || search.chars().anyMatch(ch -> ch < 32 || ch == 127))) throw new BridgeException("invalid_args", "Native dropdown search accepts at most 256 printable characters.");
        if (state.hover != null) state.hover.accept(c);
        if (!(Boolean) ExpandedGuiApi.call(widget, "isOpen")) click(c, c.bounds.width / 2, c.bounds.height / 2, 0, current);
        if (search != null) {
            GuiTextField input = (GuiTextField) dropdownField(widget, "searchField");
            if (input == null || search.length() > input.getMaxStringLength()) throw new BridgeException("unsupported", "Native dropdown search field is unavailable.");
            input.setText(search); input.setFocused(true);
            ExpandedGuiApi.declaredCall(widget, API + "WBlockDropdown", "updateFilteredEntries");
            return;
        }
        List<?> filtered = (List<?>) dropdownField(widget, "filteredEntries"); int index = filtered.indexOf(all.get(selected));
        if (index < 0) throw new BridgeException("target_unverified", "Requested option is excluded by the current native search; clear or change search first.");
        int columns = ((Number) dropdownField(widget, "itemsPerRow")).intValue(), rows = ((Number) dropdownField(widget, "maxVisibleRows")).intValue();
        if (columns < 1 || rows < 1) throw new BridgeException("unsupported", "Invalid native dropdown geometry.");
        int targetRow = index / columns;
        for (int step = 0; step < 128; step++) {
            if (!current.getAsBoolean() || !(Boolean) ExpandedGuiApi.call(widget, "isOpen")) throw new BridgeException("stale_screen", "Dropdown changed during native selection.");
            int scroll = ((Number) dropdownField(widget, "scrollOffset")).intValue(); int[] point = dropdownPosition(widget);
            if (targetRow >= scroll && targetRow < scroll + rows) {
                dropdownCall(widget, "handleOpenDropdownClick", point[0] + (index % columns) * 20 + 10, point[1] + 16 + (targetRow - scroll) * 20 + 10, 0);
                dropdownCall(widget, "handleOpenDropdownMouseUp");
                if (!(Boolean) ExpandedGuiApi.call(widget, "isOpen") && state.hover != null) state.hover.accept(null);
                return;
            }
            if (!(Boolean) dropdownCall(widget, "handleOpenDropdownScroll", point[0] + 2, point[1] + 18, targetRow < scroll ? 120 : -120)) break;
        }
        throw new BridgeException("target_unverified", "Option exceeds bounded native scrolling; narrow the dropdown with set_text first.");
    }
}
