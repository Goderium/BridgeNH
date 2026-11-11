package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.util.StatCollector;

/** Bounded public MUI display data. Semantics use GT's shared resources, never machine classes or coordinates. */
@com.google.common.annotations.VisibleForTesting
public final class GuiPublicSemantics {
    private static final String MUI = "com.cleanroommc.modularui.";

    @com.google.common.annotations.VisibleForTesting
    public static void describe(Object widget, JsonObject entry, Object screen) {
        describe(widget, entry);
        GuiItemSlotBinding.describe(widget, entry, screen);
    }

    static void describe(Object widget, JsonObject entry) {
        if (!ExpandedGuiApi.is(widget, MUI + "widget.Widget")) return;
        describeText(widget, entry);
        if (ExpandedGuiApi.is(widget, MUI + "api.widget.ITooltip")) {
            try {
                Object tooltip = ExpandedGuiApi.call(widget, "getTooltip");
                if (tooltip != null && ExpandedGuiApi.is(tooltip, MUI + "screen.RichTooltip")) {
                    if ((Boolean) ExpandedGuiApi.call(tooltip, "isAutoUpdate")) ExpandedGuiApi.call(tooltip, "markDirty");
                    // Native isEmpty builds only a dirty tooltip, without drawing or changing hover.
                    ExpandedGuiApi.call(tooltip, "isEmpty");
                    Object lines = ExpandedGuiApi.call(ExpandedGuiApi.call(tooltip, "getRichText"), "getAsStrings");
                    if (lines instanceof List<?>) GuiDataAdapter.text(entry, (List<?>) lines);
                    entry.addProperty("tooltipSource", "MUI2 public RichTooltip.isEmpty/getRichText/getAsStrings; native dirty builder refreshed");
                }
            } catch (RuntimeException | LinkageError error) { entry.addProperty("tooltipError", error.getClass().getSimpleName()); }
        }
        for (String role : new String[] {"overlay", "background"}) {
            try {
                Object drawable = ExpandedGuiApi.call(widget, role.equals("overlay") ? "getOverlay" : "getBackground");
                JsonObject data = drawable(drawable, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
                if (data != null) entry.add(role, data);
            } catch (RuntimeException | LinkageError error) { entry.addProperty(role + "Error", error.getClass().getSimpleName()); }
        }
        String semantic = semantic(entry);
        if (semantic != null) {
            entry.addProperty("semantic", semantic);
            entry.addProperty("semanticSource", "GT shared public tooltip/texture resource; current screen object identity required for mutation");
        }
    }

    /** Public current label data, without a draw call or mutation of the native builder's dirty state. */
    static void describeText(Object widget, JsonObject entry) {
        try {
            if (ExpandedGuiApi.is(widget, MUI + "widgets.TextWidget")) {
                GuiControlAdapter.displayText(entry, String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getKey"), "getFormatted")));
                entry.addProperty("textSource", "public current TextWidget IKey.getFormatted");
            } else if (ExpandedGuiApi.is(widget, MUI + "widgets.RichTextWidget")) {
                Object lines = ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getRichText"), "getAsStrings");
                if (lines instanceof List<?>) {
                    addTextLines(entry, (List<?>) lines);
                    entry.addProperty("textSource", "public current RichTextWidget.getRichText().getAsStrings; last native builder contents");
                    entry.addProperty("textRefreshRequiresNativeDraw", true);
                }
            }
        } catch (RuntimeException | LinkageError error) { entry.addProperty("textError", error.getClass().getSimpleName()); }
    }
    static void describeDisplayText(Object widget, JsonObject entry) {
        if (!ExpandedGuiApi.is(widget, MUI + "widget.Widget")) return;
        describeText(widget, entry);
        for (String role : new String[] {"overlay", "background"}) {
            try {
                JsonObject data = drawable(ExpandedGuiApi.call(widget, role.equals("overlay") ? "getOverlay" : "getBackground"),
                        Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
                if (data != null) {
                    JsonObject current = data;
                    for (int i = 0; i < 8 && current.has("current"); i++) current = current.getAsJsonObject("current");
                    if (current.has("text") || current.has("textLines")) entry.add(role + "Text", current);
                }
            } catch (RuntimeException | LinkageError error) { entry.addProperty(role + "TextError", error.getClass().getSimpleName()); }
        }
    }
    private static void addTextLines(JsonObject data, List<?> lines) {
        JsonArray text = new JsonArray(); boolean truncated = lines.size() > GuiDataAdapter.LINE_LIMIT;
        for (int i = 0; i < Math.min(GuiDataAdapter.LINE_LIMIT, lines.size()); i++) {
            String line = String.valueOf(lines.get(i)); if (line.length() > 1024) truncated = true;
            text.add(new com.google.gson.JsonPrimitive(JsonData.bounded(line, 1024)));
        }
        data.add("textLines", text); data.addProperty("textTotalLines", lines.size()); data.addProperty("textTruncated", truncated);
    }

    private static JsonObject drawable(Object value, Set<Object> seen) {
        if (value == null || seen.size() >= 8 || !seen.add(value)) return null;
        JsonObject data = new JsonObject();
        data.addProperty("class", JsonData.bounded(value.getClass().getName(), 256));
        if (ExpandedGuiApi.is(value, MUI + "drawable.DynamicDrawable")) {
            Object supplier = ExpandedGuiApi.call(value, "getSupplier");
            if (supplier instanceof Supplier<?>) {
                JsonObject current = drawable(((Supplier<?>) supplier).get(), seen);
                if (current != null) data.add("current", current);
            }
            data.addProperty("source", "public DynamicDrawable.getSupplier; current display value");
        } else if (ExpandedGuiApi.is(value, MUI + "api.drawable.IKey")) {
            GuiControlAdapter.displayText(data, String.valueOf(ExpandedGuiApi.call(value, "getFormatted")));
            data.addProperty("source", "public current drawable IKey.getFormatted");
        } else if (ExpandedGuiApi.is(value, MUI + "drawable.text.RichText")) {
            Object lines = ExpandedGuiApi.call(value, "getAsStrings");
            if (lines instanceof List<?>) addTextLines(data, (List<?>) lines);
            data.addProperty("source", "public current RichText drawable strings");
        } else if (ExpandedGuiApi.is(value, MUI + "drawable.UITexture")) {
            data.addProperty("location", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(value, "getLocation")), 512));
            // UITexture's UV members are public API fields, including atlas sub-area identity.
            for (String key : new String[] {"u0", "v0", "u1", "v1"}) {
                try { data.addProperty(key, (Number) value.getClass().getField(key).get(value)); }
                catch (ReflectiveOperationException error) { data.addProperty("uvUnavailable", true); }
            }
        }
        return data;
    }

    static String semantic(JsonObject entry) {
        String location = location(entry.has("overlay") ? entry.getAsJsonObject("overlay") : null);
        if ("gregtech:textures/gui/overlay_button/structure_update.png".equals(location)) return "structure_recheck";
        if (location != null && (location.equals("gregtech:textures/gui/overlay_button/power_switch_on.png")
                || location.equals("gregtech:textures/gui/overlay_button/power_switch_off.png")
                || location.equals("gregtech:textures/gui/overlay_button/power_switch_disabled.png"))) return "power_switch";
        if (matchesTitle(entry, "GT5U.gui.button.structure_update")) return "structure_recheck";
        if (matchesTitle(entry, "GT5U.gui.button.power_switch")) return "power_switch";
        return null;
    }

    private static String location(JsonObject data) {
        if (data == null) return null;
        for (int i = 0; i < 8; i++) {
            if (data.has("location")) return data.get("location").getAsString();
            if (!data.has("current")) break;
            data = data.getAsJsonObject("current");
        }
        return null;
    }

    private static boolean matchesTitle(JsonObject entry, String key) {
        if (!entry.has("tooltip")) return false;
        JsonArray lines = entry.getAsJsonArray("tooltip");
        if (lines.size() == 0) return false;
        String title = plain(lines.get(0).getAsString());
        if (title.equals(key)) return true;
        try {
            String translated = StatCollector.translateToLocal(key);
            return !key.equals(translated) && title.equals(plain(translated));
        } catch (RuntimeException | LinkageError error) { return false; }
    }

    private static String plain(String value) { return value.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "").trim(); }
}
