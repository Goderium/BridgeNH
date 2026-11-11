package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** ModularUI 1.x framework APIs only: no machine class, value field or packet format is selected here. */
final class LegacyModularGuiAdapter {
    static final String API = "com.gtnewhorizons.modularui.";
    static final String SCREEN = API + "common.internal.wrapper.ModularGui";
    static final String WINDOW = API + "api.screen.ModularWindow";
    static final String WIDGET = API + "api.widget.Widget";
    static final String TEXT = API + "common.widget.textfield.TextFieldWidget";
    static final String BASE_TEXT = API + "common.widget.textfield.BaseTextFieldWidget";

    static boolean screen(Object screen) { return ExpandedGuiApi.is(screen, SCREEN); }
    static boolean supports(Object widget) { return ExpandedGuiApi.is(widget, WIDGET) || ExpandedGuiApi.is(widget, WINDOW); }
    static boolean label(Object widget) { return ExpandedGuiApi.is(widget, API + "common.widget.TextWidget"); }
    static boolean editable(Object widget) { return ExpandedGuiApi.is(widget, TEXT); }
    static boolean button(Object widget) { return ExpandedGuiApi.is(widget, API + "common.widget.ButtonWidget"); }
    static Object windows(Object screen) { return ExpandedGuiApi.call(ExpandedGuiApi.call(screen, "getContext"), "getOpenWindows"); }
    static Object children(Object widget) {
        return ExpandedGuiApi.is(widget, API + "api.widget.IWidgetParent")
                ? ExpandedGuiApi.call(widget, "getChildren") : Collections.emptyList();
    }
    static boolean valid(Object widget) {
        return (Boolean) ExpandedGuiApi.call(widget, ExpandedGuiApi.is(widget, WINDOW) ? "isInitialized" : "isInitialised");
    }
    static boolean currentWindow(Object widget, Object screen) {
        Object window = ExpandedGuiApi.is(widget, WINDOW) ? widget : ExpandedGuiApi.call(widget, "getWindow");
        return window != null && window == ExpandedGuiApi.call(ExpandedGuiApi.call(screen, "getContext"), "getCurrentWindow");
    }
    static String name(Object widget) {
        return ExpandedGuiApi.is(widget, WINDOW) ? "window" : String.valueOf(ExpandedGuiApi.call(widget, "getInternalName"));
    }
    static String labelText(Object widget) {
        Object text = ExpandedGuiApi.call(widget, "getText");
        return text == null ? "" : String.valueOf(ExpandedGuiApi.call(text, "getFormatted"));
    }
    static void describe(Object widget, Object screen, JsonObject entry, JsonArray actions) {
        entry.addProperty("framework", "modularui1");
        entry.addProperty("name", JsonData.bounded(name(widget), 256));
        entry.addProperty("kind", ExpandedGuiApi.is(widget, WINDOW) ? "window" : "widget");
        entry.addProperty("valid", valid(widget));
        entry.addProperty("currentWindow", currentWindow(widget, screen));
        if (!currentWindow(widget, screen)) {
            entry.addProperty("enabled", false); entry.addProperty("disabledReason", "another_native_window_is_current");
        }
        if (ExpandedGuiApi.is(widget, WIDGET)) {
            Object area = ExpandedGuiApi.call(widget, "getArea");
            if (area instanceof Rectangle) {
                Rectangle box = (Rectangle) area;
                entry.addProperty("x", box.x); entry.addProperty("y", box.y); entry.addProperty("width", box.width); entry.addProperty("height", box.height);
            }
        }
        if (label(widget)) {
            entry.addProperty("kind", "label"); GuiControlAdapter.displayText(entry, labelText(widget));
            entry.addProperty("textSource", "native public TextWidget.getText().getFormatted; current client text");
        }
        if (editable(widget)) {
            entry.addProperty("kind", "text"); GuiControlAdapter.displayText(entry, String.valueOf(ExpandedGuiApi.call(widget, "getText")));
            entry.addProperty("value", entry.get("text").getAsString());
            entry.addProperty("focused", (Boolean) ExpandedGuiApi.call(widget, "isFocused"));
            entry.addProperty("valueSource", "native_client_widget_text; may contain an unsubmitted edit");
            entry.addProperty("syncsToServer", (Boolean) ExpandedGuiApi.call(widget, "syncsToServer"));
            entry.addProperty("syncsToClient", (Boolean) ExpandedGuiApi.call(widget, "syncsToClient"));
            entry.addProperty("commitSemantics", "Native removeFocus invokes validator, setter and configured sync; server acknowledgement is unavailable.");
            actions.add(new JsonPrimitive("set_text")); actions.add(new JsonPrimitive("focus"));
        } else if (ExpandedGuiApi.is(widget, API + "common.widget.textfield.NumericWidget")) {
            entry.addProperty("kind", "numeric_text"); entry.addProperty("value", (Number) ExpandedGuiApi.call(widget, "getValue"));
            entry.addProperty("focused", (Boolean) ExpandedGuiApi.call(widget, "isFocused"));
            entry.addProperty("text", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.field(widget, BASE_TEXT, "handler"), "getTextAsString")), 1024));
            entry.addProperty("valueSource", "native_client_numeric_widget_value");
            entry.addProperty("readOnlyReason", "No verified public whole-text replacement and submission API for NumericWidget.");
        }
        if (button(widget)) { entry.addProperty("kind", "button"); actions.add(new JsonPrimitive("activate")); }
        if (ExpandedGuiApi.is(widget, WIDGET)) {
            try {
                Object tooltip = ExpandedGuiApi.call(widget, "getTooltip");
                if (tooltip instanceof List<?>) {
                    JsonArray lines = new JsonArray(); int count = 0;
                    for (Object line : (List<?>) tooltip) {
                        if (++count > GuiDataAdapter.LINE_LIMIT) break;
                        lines.add(new JsonPrimitive(JsonData.bounded(String.valueOf(ExpandedGuiApi.call(line, "getFormatted")), GuiDataAdapter.TEXT_LIMIT)));
                    }
                    entry.add("tooltip", lines); entry.addProperty("tooltipTotalLines", ((List<?>) tooltip).size());
                    entry.addProperty("tooltipTruncated", ((List<?>) tooltip).size() > GuiDataAdapter.LINE_LIMIT);
                    entry.addProperty("tooltipSource", "MUI1 public current getTooltip/getFormatted; current physical modifier state");
                }
            } catch (RuntimeException | LinkageError error) { entry.addProperty("tooltipError", error.getClass().getSimpleName()); }
        }
    }
    static void focus(Object widget, boolean focused) {
        if (focused) ExpandedGuiApi.call(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getContext"), "getCursor"), "updateFocused", widget);
        else if ((Boolean) ExpandedGuiApi.call(widget, "isFocused")) ExpandedGuiApi.call(widget, "removeFocus");
    }
    static void setText(Object widget, String text, boolean commit, BooleanSupplier stillCurrent) {
        Object handler = ExpandedGuiApi.field(widget, BASE_TEXT, "handler");
        if (!(Boolean) ExpandedGuiApi.call(handler, "test", text)) throw new BridgeException("invalid_args", "Text fails this native widget's pattern/length validator.");
        focus(widget, true);
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The native screen/container changed while focusing; no replacement text was written.");
        if (ExpandedGuiApi.call(widget, "getWindow") != ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getContext"), "getCurrentWindow"))
            throw new BridgeException("stale_widget", "The current native window changed while focusing; no replacement text was written.");
        if (!(Boolean) ExpandedGuiApi.call(widget, "isFocused")) throw new BridgeException("target_unverified", "Native focus was not accepted; no replacement text was written.");
        ExpandedGuiApi.call(widget, "setText", text);
        // Whole-string replacement uses no character callbacks and never appends to the original contents.
        if (commit) {
            if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The native screen/container changed before submission.");
            focus(widget, false);
        }
    }
    static Object activate(Object widget) { return ExpandedGuiApi.call(widget, "onClick", 0, false); }
}
