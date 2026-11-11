package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Actual Reese/Angelica controls. Reflection is restricted to this versioned optional GUI API. */
public final class SodiumSettingsControls {
    public static final int VERSION = 1;
    static final String SCREEN = "me.flashyreese.mods.reeses_sodium_options.client.gui.ReeseSodiumVideoOptionsScreen";
    private static final String REESE = "me.flashyreese.mods.reeses_sodium_options.client.gui.";
    private static final String SODIUM = "me.jellysquid.mods.sodium.client.gui.";
    private static final String FRAME = REESE + "frame.AbstractFrame", TABS = REESE + "frame.tab.TabFrame";
    private static final String SCROLL_FRAME = REESE + "frame.ScrollableFrame", SCROLL = REESE + "frame.components.ScrollBarComponent";
    private static final String SEARCH = REESE + "frame.components.SearchTextFieldComponent";
    private static final String BUTTON = SODIUM + "widgets.FlatButtonWidget", ELEMENT = SODIUM + "options.control.ControlElement";
    private static final String IMPL = SODIUM + "options.OptionImpl";
    private static final String NOT_FINE = "jss.notfine.core.Settings$NotFineOption";
    private static final String FACTORY = SODIUM + "options.control.element.SodiumControlElementFactory$";
    private static final int LIMIT = 1024;

    static boolean screen(Object screen) { return ExpandedGuiApi.is(screen, SCREEN); }
    private static boolean is(Object value, String type) { return ExpandedGuiApi.is(value, type); }
    private static Object field(Object value, String owner, String name) { return ExpandedGuiApi.field(value, owner, name); }
    private static Object call(Object value, String name, Object... args) { return ExpandedGuiApi.call(value, name, args); }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static String text(Object value) { return JsonData.bounded(String.valueOf(value), 1024); }
    private static JsonElement scalar(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        return new JsonPrimitive(value instanceof Enum<?> ? ((Enum<?>) value).name() : text(value));
    }
    private static Rectangle rectangle(Object dim) {
        return new Rectangle(number(call(dim, "getOriginX")), number(call(dim, "getOriginY")), number(call(dim, "getWidth")), number(call(dim, "getHeight")));
    }
    static final class Control {
        final Object widget, parent;
        final Rectangle bounds, clip;
        Control(Object widget, Object parent, Rectangle bounds, Rectangle clip) { this.widget = widget; this.parent = parent; this.bounds = bounds; this.clip = clip; }
        boolean visible() { return bounds != null && clip.intersects(bounds); }
    }
    static final class State {
        final Object screen;
        final List<Control> controls = new ArrayList<Control>();
        final IdentityHashMap<Object, Control> byWidget = new IdentityHashMap<Object, Control>();
        boolean truncated;
        State(Object screen) { this.screen = screen; }
    }
    static State capture(Object screen) {
        State state = new State(screen);
        // Native frame geometry is already scaled and laid out. Never draw or build a frame while observing it.
        net.minecraft.client.gui.GuiScreen gui = (net.minecraft.client.gui.GuiScreen) screen;
        Rectangle viewport = new Rectangle(0, 0, gui.width, gui.height);
        // Reese initGui can retain old roots in children() after a normal window resize.
        // drawScreen renders only this current frame; stale roots must never become actionable.
        visit(state, field(screen, SCREEN, "frame"), null, viewport, 0);
        return state;
    }
    private static void visit(State state, Object widget, Object parent, Rectangle clip, int depth) {
        if (widget == null || state.byWidget.containsKey(widget)) return;
        if (depth > 32 || state.controls.size() >= LIMIT) { state.truncated = true; return; }
        Object dim = is(widget, ELEMENT) ? call(widget, "getDimensions")
                : is(widget, FRAME) ? field(widget, FRAME, "dim")
                : is(widget, BUTTON) ? field(widget, BUTTON, "dim")
                : is(widget, SCROLL) ? field(widget, SCROLL, "dim")
                : is(widget, SEARCH) ? field(widget, SEARCH, "dim") : null;
        Control control = new Control(widget, parent, dim == null ? null : rectangle(dim), clip);
        state.controls.add(control); state.byWidget.put(widget, control);
        if (!is(widget, FRAME)) return;
        Rectangle childrenClip = clip;
        if (is(widget, TABS)) childrenClip = clip.intersection(control.bounds);
        if (is(widget, SCROLL_FRAME)) {
            Object viewport = field(widget, SCROLL_FRAME, "viewPortDimension");
            if (viewport != null) childrenClip = clip.intersection(rectangle(viewport));
        }
        for (Object child : (List<?>) call(widget, "children")) visit(state, child, widget, childrenClip, depth + 1);
        // Scrollbars are deliberately outside children() in the native frame implementation.
        if (is(widget, TABS)) visit(state, field(widget, TABS, "tabSectionScrollBar"), widget, clip, depth + 1);
        if (is(widget, SCROLL_FRAME)) {
            visit(state, field(widget, SCROLL_FRAME, "verticalScrollBar"), widget, clip, depth + 1);
            visit(state, field(widget, SCROLL_FRAME, "horizontalScrollBar"), widget, clip, depth + 1);
        }
    }
    static JsonObject summary(State state) {
        JsonObject result = new JsonObject(); JsonArray pages = new JsonArray(); int pending = 0;
        for (Object page : (List<?>) field(state.screen, SCREEN, "pages")) {
            JsonObject row = new JsonObject(); row.addProperty("name", text(call(page, "getName")));
            List<?> options = (List<?>) call(page, "getOptions"); int changed = 0;
            for (Object option : options) if (Boolean.TRUE.equals(call(option, "hasChanged"))) changed++;
            pending += changed; row.addProperty("optionCount", options.size()); row.addProperty("pendingCount", changed); pages.add(row);
        }
        result.addProperty("version", VERSION); result.add("pages", pages); result.addProperty("pendingCount", pending);
        result.addProperty("valueSemantics", "pendingValue is the native option edit buffer; appliedValue reads its actual binding and storage. Native Apply/Undo affects every page. A stored setting is not proof of achieved FPS or persistence after restart.");
        return result;
    }
    static boolean actionable(State state, Object widget) {
        JsonObject entry = new JsonObject(); JsonArray actions = new JsonArray(); describe(state, widget, entry, actions);
        return entry.get("enabled").getAsBoolean() && actions.size() > 0;
    }
    static void describe(State state, Object widget, JsonObject entry, JsonArray actions) {
        Control control = state.byWidget.get(widget);
        entry.addProperty("kind", "sodium_container"); entry.addProperty("valid", true);
        boolean visible = control.visible(), enabled = visible;
        entry.addProperty("visible", visible);
        if (control.bounds != null) {
            entry.addProperty("x", control.bounds.x); entry.addProperty("y", control.bounds.y);
            entry.addProperty("width", control.bounds.width); entry.addProperty("height", control.bounds.height);
        }
        if (is(widget, BUTTON)) {
            enabled &= Boolean.TRUE.equals(field(widget, BUTTON, "enabled")) && Boolean.TRUE.equals(field(widget, BUTTON, "visible"));
            entry.addProperty("visible", visible && Boolean.TRUE.equals(field(widget, BUTTON, "visible")));
            entry.addProperty("kind", "button"); entry.addProperty("text", text(field(widget, BUTTON, "label")));
            entry.addProperty("selected", Boolean.TRUE.equals(field(widget, BUTTON, "selected")));
            for (String role : new String[] {"apply", "undo", "close"})
                if (widget == field(state.screen, SCREEN, role + "Button")) entry.addProperty("semantic", "settings_" + role);
            actions.add(new JsonPrimitive("activate"));
        } else if (is(widget, TABS)) {
            entry.addProperty("kind", "choice"); entry.addProperty("text", "Settings pages");
            List<?> tabs = (List<?>) field(widget, TABS, "tabs"); JsonArray options = new JsonArray();
            for (Object tab : tabs) options.add(new JsonPrimitive(text(call(tab, "getTitle"))));
            entry.add("options", options); entry.addProperty("optionCount", tabs.size());
            entry.addProperty("selected", tabs.indexOf(field(widget, TABS, "selectedTab"))); actions.add(new JsonPrimitive("set_selected"));
        } else if (is(widget, SCROLL)) {
            entry.addProperty("kind", "scrollbar"); entry.addProperty("text", text(field(widget, SCROLL, "mode")));
            entry.addProperty("value", number(field(widget, SCROLL, "offset"))); entry.addProperty("min", 0);
            entry.addProperty("max", number(field(widget, SCROLL, "maxScrollBarOffset"))); actions.add(new JsonPrimitive("set_value"));
        } else if (is(widget, SEARCH)) {
            enabled &= Boolean.TRUE.equals(call(widget, "isVisible")) && Boolean.TRUE.equals(call(widget, "isEditable"));
            entry.addProperty("visible", visible && Boolean.TRUE.equals(call(widget, "isVisible")));
            entry.addProperty("kind", "text"); entry.addProperty("text", text(field(widget, SEARCH, "text")));
            entry.addProperty("focused", Boolean.TRUE.equals(call(widget, "isFocused")));
            entry.addProperty("maxLength", number(field(widget, SEARCH, "maxLength")));
            actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
        } else if (is(widget, ELEMENT)) {
            Object option = call(widget, "getOption"); enabled &= Boolean.TRUE.equals(call(option, "isAvailable"));
            entry.addProperty("optionClass", option.getClass().getName());
            entry.addProperty("text", text(call(option, "getName"))); entry.addProperty("tooltip", text(call(option, "getTooltip")));
            entry.addProperty("pendingChanges", Boolean.TRUE.equals(call(option, "hasChanged")));
            Object value = call(option, "getValue"); entry.add("pendingValue", scalar(value)); entry.add("value", scalar(value));
            if (is(option, IMPL)) {
                entry.add("baselineValue", scalar(field(option, IMPL, "value")));
                entry.add("appliedValue", scalar(call(field(option, IMPL, "binding"), "getValue", call(call(option, "getStorage"), "getData"))));
                entry.addProperty("appliedValueSource", "actual_option_binding_getter");
            } else if (is(option, NOT_FINE)) {
                entry.add("baselineValue", scalar(field(option, NOT_FINE, "value")));
                entry.add("appliedValue", scalar(call(option, "getStore")));
                entry.addProperty("appliedValueSource", "actual_NotFineOption_getStore");
            }
            entry.addProperty("appliedValueAvailable", entry.has("appliedValue"));
            if (is(widget, FACTORY + "SliderControlElement")) {
                int min = number(field(widget, FACTORY + "SliderControlElement", "min"));
                entry.addProperty("kind", "slider"); entry.addProperty("min", min);
                entry.addProperty("max", min + number(field(widget, FACTORY + "SliderControlElement", "range")));
                entry.addProperty("step", number(field(widget, FACTORY + "SliderControlElement", "interval")));
                entry.addProperty("valueText", text(call(field(widget, FACTORY + "SliderControlElement", "formatter"), "format", value)));
                actions.add(new JsonPrimitive("set_value"));
            } else if (is(widget, FACTORY + "CyclingControlElement")) {
                entry.addProperty("kind", "choice"); JsonArray options = new JsonArray(), values = new JsonArray();
                Object[] allowed = (Object[]) field(widget, FACTORY + "CyclingControlElement", "allowedValues");
                String[] names = (String[]) field(widget, FACTORY + "CyclingControlElement", "names"); int selected = -1;
                for (int i = 0; i < allowed.length; i++) {
                    values.add(scalar(allowed[i])); options.add(new JsonPrimitive(names[((Enum<?>) allowed[i]).ordinal()]));
                    if (allowed[i] == value) selected = i;
                }
                entry.add("options", options); entry.add("values", values); entry.addProperty("optionCount", allowed.length);
                entry.addProperty("selected", selected); actions.add(new JsonPrimitive("set_selected")); actions.add(new JsonPrimitive("activate"));
            } else if (is(widget, FACTORY + "TickBoxControlElement")) {
                entry.addProperty("kind", "choice"); entry.addProperty("selected", Boolean.TRUE.equals(value) ? 1 : 0);
                JsonArray options = new JsonArray(); options.add(new JsonPrimitive("false")); options.add(new JsonPrimitive("true"));
                entry.add("options", options); entry.addProperty("optionCount", 2);
                actions.add(new JsonPrimitive("set_selected")); actions.add(new JsonPrimitive("activate"));
            } else if (is(option, SODIUM + "options.ActionOption") || is(option, SODIUM + "options.ButtonOption") || is(option, SODIUM + "options.SubScreenOption")) {
                if (is(option, SODIUM + "options.ActionOption")) enabled &= Boolean.TRUE.equals(field(option, SODIUM + "options.ActionOption", "clickable"));
                entry.addProperty("kind", "button"); actions.add(new JsonPrimitive("activate"));
            }
        }
        entry.addProperty("enabled", enabled);
        if (!visible) entry.addProperty("disabledReason", "outside_current_native_viewport; use the current scrollbar or page selector");
        entry.addProperty("source", "current_Reese_Angelica_native_tree");
    }
    private static void current(BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "The native settings screen changed before invocation.");
    }
    private static void click(Control control, BooleanSupplier stillCurrent) {
        current(stillCurrent);
        Rectangle hit = control.bounds.intersection(control.clip);
        if (hit.isEmpty()) throw new BridgeException("disabled", "Native control is outside the viewport.");
        double x = hit.getCenterX(), y = hit.getCenterY(); boolean accepted;
        try { accepted = Boolean.TRUE.equals(call(control.widget, "mouseClicked", x, y, 0)); }
        finally { call(control.widget, "mouseReleased", x, y, 0); }
        if (!accepted) throw new BridgeException("native_rejected", "The actual settings control refused the click.");
    }
    static JsonObject perform(State state, Object widget, String action, JsonObject args, BooleanSupplier stillCurrent) {
        current(stillCurrent); Control control = state.byWidget.get(widget); int callbacks = 0;
        if (action.equals("activate")) { click(control, stillCurrent); callbacks++; }
        else if (is(widget, TABS)) {
            List<?> tabs = (List<?>) field(widget, TABS, "tabs");
            int selected = JsonData.integer(args, "selected", 0, tabs.size() - 1);
            call(widget, "setTab", tabs.get(selected)); callbacks++;
        } else if (is(widget, SCROLL)) {
            int value = JsonData.integer(args, "value", 0, number(field(widget, SCROLL, "maxScrollBarOffset")));
            call(widget, "setOffset", value); callbacks++;
        } else if (is(widget, SEARCH)) {
            if (action.equals("focus")) { call(widget, "setFocused", GuiDataAdapter.booleanArg(args, "focused", false)); }
            else {
                String old = String.valueOf(field(widget, SEARCH, "text"));
                String replacement = ExpandedGuiApi.string(args, "text", number(field(widget, SEARCH, "maxLength")));
                if (args.has("expectedText") && !old.equals(ExpandedGuiApi.string(args, "expectedText", 1024)))
                    throw new BridgeException("value_changed", "Search changed before text replacement.");
                call(widget, "setCursor", 0); call(widget, "setSelectionEnd", old.length());
                call(widget, "write", replacement);
            }
            callbacks++;
        } else if (is(widget, FACTORY + "SliderControlElement")) {
            Object option = call(widget, "getOption"); String type = FACTORY + "SliderControlElement";
            int min = number(field(widget, type, "min")), max = min + number(field(widget, type, "range")), step = number(field(widget, type, "interval"));
            int target = JsonData.integer(args, "value", min, max), start = number(call(option, "getValue"));
            if (step < 1 || (target - min) % step != 0 || (target - start) % step != 0 || Math.abs((long) target - start) / step > 4096)
                throw new BridgeException("invalid_args", "Requested value must be an exact native slider step within the bounded range.");
            int count = Math.abs(target - start) / step, key = target > start ? 205 : 203;
            for (int i = 0; i < count; i++) {
                current(stillCurrent);
                if (!Boolean.TRUE.equals(call(widget, "keyTyped", '\0', key))) throw new BridgeException("native_rejected", "The native slider refused its arrow callback.");
                callbacks++;
            }
            if (number(call(option, "getValue")) != target) throw new BridgeException("native_rejected", "Native slider did not reach the requested pending value.");
        } else if (action.equals("set_selected")) {
            JsonObject before = new JsonObject(); describe(state, widget, before, new JsonArray());
            int count = before.get("optionCount").getAsInt(), selected = JsonData.integer(args, "selected", 0, count - 1);
            if (count > 256) throw new BridgeException("unsupported", "Native option cycle exceeds the bounded range.");
            for (int i = 0; i < count && before.get("selected").getAsInt() != selected; i++) {
                click(control, stillCurrent); callbacks++; before = new JsonObject(); describe(state, widget, before, new JsonArray());
            }
            if (before.get("selected").getAsInt() != selected) throw new BridgeException("native_rejected", "Native choice did not reach the requested pending value.");
        } else throw new BridgeException("unsupported", "No native settings callback for this action.");
        JsonObject result = new JsonObject(); result.addProperty("nativeAction", "Reese_Angelica_native_control_callback");
        result.addProperty("callbacks", callbacks); result.addProperty("persistenceVerified", false);
        if (stillCurrent.getAsBoolean()) result.add("settings", summary(state));
        return result;
    }
}
