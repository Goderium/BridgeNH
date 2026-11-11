package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.VanillaSettingsControls;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.Language;
import net.minecraft.world.storage.SaveFormatComparator;

/** Bounded native list discovery, public scrolling and real keybinding row setters. */
public final class NativeMenuLists {
    private static final IdentityHashMap<Object, Row> rows = new IdentityHashMap<Object, Row>();
    private static final IdentityHashMap<GuiSlot, Object> owners = new IdentityHashMap<GuiSlot, Object>();
    private static final String KEY_ROW = "net.minecraft.client.gui.GuiKeyBindingList$KeyEntry";
    private static final String CONTROLLING_ROW = "com.blamejared.controlling.client.gui.GuiNewKeyBindingList$KeyEntry";
    private static final String WORLD_LIST = "net.minecraft.client.gui.GuiSelectWorld$List";
    private static final String LANGUAGE_LIST = "net.minecraft.client.gui.GuiLanguage$List";
    static final class Row {
        final GuiSlot list; final int index; final Object entry;
        Row(GuiSlot list, int index, Object entry) { this.list = list; this.index = index; this.entry = entry; }
    }
    public static void invalidate() { rows.clear(); owners.clear(); }

    public static List<GuiSlot> discover(Object screen) {
        invalidate(); List<GuiSlot> result = new ArrayList<GuiSlot>();
        if (screen == null) return result;
        int examined = 0;
        for (Class<?> owner = screen.getClass(); owner != null && examined < 512; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (++examined > 512) break;
                if (Modifier.isStatic(field.getModifiers()) || !GuiSlot.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true); Object value = field.get(screen);
                    if (value instanceof GuiSlot && !result.contains(value)) { result.add((GuiSlot) value); owners.put((GuiSlot) value, screen); }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    throw new BridgeException("unsupported", "Actual native list field is unavailable.");
                }
            }
        }
        return result;
    }

    static int size(GuiSlot list) { return ((Number) listMethod(list, new String[] {"getSize", "func_148127_b"}, new Class<?>[0])).intValue(); }
    private static Object listMethod(GuiSlot list, String[] names, Class<?>[] signature, Object... args) {
        for (String name : names) {
            try { Method method = GuiSlot.class.getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(list, args); }
            catch (NoSuchMethodException unavailable) { /* Fixed MCP/SRG aliases only. */ }
            catch (ReflectiveOperationException | RuntimeException failure) { throw new BridgeException("adapter_error", "Normal native list callback failed."); }
        }
        throw new BridgeException("unsupported", "Normal native list callback is unavailable.");
    }
    private static boolean selected(GuiSlot list, int index) {
        return (Boolean) listMethod(list, new String[] {"isSelected", "func_148131_a"}, new Class<?>[] {int.class}, index);
    }
    public static List<Object> keyRows(GuiSlot list) {
        List<Object> result = new ArrayList<Object>();
        if (!(list instanceof GuiListExtended)) return result;
        int count = size(list);
        if (count < 0 || count > 4096) throw new BridgeException("unsupported", "Native list exceeds the bounded row catalog.");
        for (int index = 0; index < count; index++) {
            Object entry = ((GuiListExtended) list).getListEntry(index);
            if (ExpandedGuiApi.is(entry, KEY_ROW) || ExpandedGuiApi.is(entry, CONTROLLING_ROW)) { rows.put(entry, new Row(list, index, entry)); result.add(entry); }
        }
        return result;
    }
    public static boolean keyRow(Object widget) { return rows.containsKey(widget); }
    private static KeyBinding key(Object widget) {
        if (ExpandedGuiApi.is(widget, CONTROLLING_ROW)) {
            Object binding = ExpandedGuiApi.call(widget, "getKeybinding");
            if (!(binding instanceof KeyBinding)) throw new BridgeException("unsupported", "Actual Controlling keybinding is unavailable.");
            return (KeyBinding) binding;
        }
        return VanillaSettingsControls.enumBinding(widget, ExpandedGuiApi.named(widget.getClass(), KEY_ROW), KeyBinding.class);
    }
    private static List<?> labels(GuiSlot list) {
        if (ExpandedGuiApi.is(list, WORLD_LIST)) {
            Object screen = owners.get(list);
            if (screen != null) {
                Object actual = ExpandedGuiApi.field(screen, "net.minecraft.client.gui.GuiSelectWorld", "field_146639_s");
                if (actual instanceof List<?>) return (List<?>) actual;
            }
        } else if (ExpandedGuiApi.is(list, LANGUAGE_LIST)) {
            Object actual = ExpandedGuiApi.field(list, LANGUAGE_LIST, "field_148176_l");
            if (actual instanceof List<?>) return (List<?>) actual;
        }
        return null;
    }
    private static boolean label(GuiSlot list, List<?> labels, int count, int index, JsonObject row) {
        if (labels == null || labels.size() != count || index < 0 || index >= labels.size()) return false;
        Object actual = labels.get(index);
        if (actual instanceof SaveFormatComparator && ExpandedGuiApi.is(list, WORLD_LIST)) {
            SaveFormatComparator save = (SaveFormatComparator) actual;
            row.addProperty("worldFolder", JsonData.bounded(save.getFileName(), 512));
            row.addProperty("text", JsonData.bounded(save.getDisplayName(), 512)); return true;
        }
        if (actual instanceof String && ExpandedGuiApi.is(list, LANGUAGE_LIST)) {
            Object actualLanguages = ExpandedGuiApi.field(list, LANGUAGE_LIST, "field_148177_m");
            if (!(actualLanguages instanceof Map<?, ?>)) return false;
            Map<?, ?> languages = (Map<?, ?>) actualLanguages;
            Object language = languages.get(actual);
            if (language instanceof Language && actual.equals(((Language) language).getLanguageCode())) {
                row.addProperty("languageCode", (String) actual); row.addProperty("text", JsonData.bounded(language.toString(), 512)); return true;
            }
        }
        return false;
    }
    private static int rowY(GuiSlot list, int index) { return list.top + 4 + list.headerPadding - list.getAmountScrolled() + index * list.slotHeight; }
    public static boolean visible(Object widget) {
        Row row = rows.get(widget); if (row == null) return false;
        int y = rowY(row.list, row.index);
        return y >= row.list.top && y + row.list.slotHeight <= row.list.bottom;
    }

    public static JsonObject describe(Object widget) {
        JsonObject value = new JsonObject();
        if (widget instanceof GuiSlot) {
            GuiSlot list = (GuiSlot) widget; int count = size(list);
            if (count < 0 || count > 4096 || list.slotHeight <= 0) throw new BridgeException("unsupported", "Native list geometry or size exceeds the bounded catalog.");
            value.addProperty("kind", "scroll_list"); value.addProperty("value", list.getAmountScrolled());
            value.addProperty("min", 0); value.addProperty("max", Math.max(0, list.func_148135_f()));
            value.addProperty("rowCount", count); value.addProperty("rowHeight", list.slotHeight);
            value.addProperty("top", list.top); value.addProperty("bottom", list.bottom);
            JsonArray entries = new JsonArray();
            List<?> labels = null;
            try { labels = labels(list); }
            catch (RuntimeException | LinkageError unavailable) { value.addProperty("rowLabelError", unavailable.getClass().getSimpleName()); }
            boolean labelsAvailable = labels != null && labels.size() == count;
            int first = list.slotHeight <= 0 ? 0 : Math.max(0, (list.getAmountScrolled() - list.headerPadding - 4) / list.slotHeight);
            for (int index = first; index < count && entries.size() < 64 && rowY(list, index) < list.bottom; index++) {
                JsonObject row = new JsonObject(); row.addProperty("index", index); row.addProperty("selected", selected(list, index));
                labelsAvailable &= label(list, labels, count, index, row);
                row.addProperty("y", rowY(list, index)); entries.add(row);
            }
            value.add("visibleRows", entries); value.addProperty("rowLabelsAvailable", labelsAvailable);
            value.addProperty("selectable", selectable(list)); value.addProperty("enabled", true); value.addProperty("valid", true);
            value.addProperty("source", "Actual GuiSlot reference, native row callbacks/public scroll getters; known world/language labels use the actual native list data, never drawing or a separate catalog's ordering.");
        } else {
            Row row = rows.get(widget); KeyBinding binding = key(widget);
            value.addProperty("kind", "key_binding"); value.addProperty("nativeListIndex", row.index);
            value.addProperty("keyDescription", binding.getKeyDescription()); value.addProperty("category", binding.getKeyCategory());
            value.addProperty("text", JsonData.bounded(I18n.format(binding.getKeyDescription()), 512));
            value.addProperty("value", binding.getKeyCode()); value.addProperty("defaultValue", binding.getKeyCodeDefault());
            value.addProperty("min", -100); value.addProperty("max", 255);
            value.addProperty("modifierPolicy", "set_value changes the key code using public GameSettings; existing optional-mod modifiers are preserved, not reset.");
            if (ExpandedGuiApi.is(binding, "com.blamejared.controlling.keybinding.ComboKeyBinding"))
                value.addProperty("modifier", String.valueOf(ExpandedGuiApi.call(binding, "controlling$getKeyModifier")));
            value.addProperty("visible", visible(widget)); value.addProperty("enabled", visible(widget)); value.addProperty("valid", true);
            value.addProperty("source", "Actual GuiKeyBindingList.KeyEntry KeyBinding reference and public getters; no translated-name or index-to-binding inference.");
        }
        return value;
    }

    public static boolean selectable(GuiSlot list) {
        return ExpandedGuiApi.is(list, WORLD_LIST) || ExpandedGuiApi.is(list, LANGUAGE_LIST);
    }
    public static void setValue(Object widget, JsonObject args, BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "The actual menu changed before native list use.");
        if (widget instanceof GuiSlot) {
            GuiSlot list = (GuiSlot) widget;
            int desired = JsonData.integer(args, "value", 0, Math.max(0, list.func_148135_f()));
            list.scrollBy(desired - list.getAmountScrolled());
        } else {
            Row row = rows.get(widget);
            if (row == null || !visible(widget) || ((GuiListExtended) row.list).getListEntry(row.index) != widget)
                throw new BridgeException("stale_widget", "Only the actual current visible keybinding row can be edited.");
            KeyBinding binding = key(widget);
            int code = JsonData.integer(args, "value", -100, 255);
            Minecraft.getMinecraft().gameSettings.setOptionKeyBinding(binding, code);
            KeyBinding.resetKeyBindingArrayAndHash();
        }
    }
    public static void setSelected(GuiSlot list, JsonObject args, BooleanSupplier current) {
        if (!selectable(list)) throw new BridgeException("unsupported", "Native single selection is unavailable for this list.");
        int index = JsonData.integer(args, "selected", 0, size(list) - 1);
        int x = (list.left + list.right) / 2, y = rowY(list, index) + list.slotHeight / 2;
        if (!current.getAsBoolean() || y < list.top || y > list.bottom || list.getSlotIndexFromScreenCoords(x, y) != index)
            throw new BridgeException("target_unverified", "Scroll to the actual visible row and observe it before selection.");
        if (selected(list, index)) return;
        listMethod(list, new String[] {"elementClicked", "func_148144_a"}, new Class<?>[] {int.class, boolean.class, int.class, int.class}, index, false, x, y);
    }
}
