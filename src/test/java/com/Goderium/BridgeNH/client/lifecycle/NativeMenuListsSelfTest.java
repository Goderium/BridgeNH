package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.client.gui.GuiCallbacks;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraft.world.WorldSettings;

/** Real GuiSlot scroll/clamp callbacks without drawing, a window, or OS input. */
public class NativeMenuListsSelfTest {
    private static int checks;
    static class ListFixture extends GuiSlot {
        int clicks;
        ListFixture() { super(null, 427, 240, 32, 176, 20); }
        protected int getSize() { return 30; }
        protected void elementClicked(int index, boolean twice, int x, int y) { clicks++; }
        protected boolean isSelected(int index) { return index == 2; }
        protected void drawBackground() { throw new AssertionError("No drawing permitted"); }
        protected void drawSlot(int index, int x, int y, int height, Tessellator tessellator, int mx, int my) { throw new AssertionError("No drawing permitted"); }
    }
    static class Screen { private final ListFixture list = new ListFixture(); private final String unrelated = "ignored"; }
    static class Menu extends GuiScreen {
        void actual(GuiButton button) { buttonList = new ArrayList(); buttonList.add(button); }
    }
    public static int run() {
        checks = 0; Screen screen = new Screen();
        check(NativeMenuLists.discover(screen).size() == 1, "Discover only actual typed native list references");
        check(NativeMenuLists.size(screen.list) == 30, "Invoke real native list size");
        JsonObject before = NativeMenuLists.describe(screen.list);
        check(before.get("kind").getAsString().equals("scroll_list") && !before.get("selectable").getAsBoolean(), "Unknown lists advertise scrolling only");
        check(before.get("max").getAsInt() == 460 && !before.get("rowLabelsAvailable").getAsBoolean(), "Native scroll range and explicit absent labels");
        JsonObject args = new JsonObject(); args.addProperty("value", 200);
        NativeMenuLists.setValue(screen.list, args, () -> true);
        check(screen.list.getAmountScrolled() == 200 && screen.list.clicks == 0, "Public native scrolling never clicks a row");
        JsonObject after = NativeMenuLists.describe(screen.list);
        check(after.getAsJsonArray("visibleRows").get(0).getAsJsonObject().get("index").getAsInt() == 9, "Visible rows follow actual scroll offset");
        expect("stale_screen", () -> NativeMenuLists.setValue(screen.list, args, () -> false));
        args.addProperty("value", 461); expect("invalid_args", () -> NativeMenuLists.setValue(screen.list, args, () -> true));
        args.addProperty("selected", 2); expect("unsupported", () -> NativeMenuLists.setSelected(screen.list, args, () -> true));
        check(screen.list.getAmountScrolled() == 200 && screen.list.clicks == 0, "Rejected actions have no scroll/selection side effect");
        check(NativeMenuLists.discover(null).isEmpty(), "Closed screens clear native list bindings");
        Menu menu = new Menu(); GuiButton first = new GuiButton(1, 0, 0, "first"); menu.actual(first);
        List<?> captured = GuiCallbacks.buttons(menu, null);
        check(captured.size() == 1 && captured.get(0) == first, "Menus bypassing Forge init still expose their actual native button objects");
        GuiButton replacement = new GuiButton(1, 0, 0, "replacement"); menu.actual(replacement);
        check(GuiCallbacks.buttons(menu, captured).get(0) == replacement, "Replaced native button lists supersede stale captured objects even with the same ID");
        check(GuiCallbacks.buttons(null, captured).isEmpty(), "Closed screens cannot reuse captured menu buttons");
        nativeWorldSelection(); NativeMenuLists.invalidate(); return checks;
    }
    private static void nativeWorldSelection() {
        try {
            GuiSelectWorld screen = new GuiSelectWorld(null); screen.width = 427; screen.height = 240;
            assign(screen, "field_146639_s", Arrays.asList(
                new SaveFormatComparator("z-actual-first", "same label", 2, 0, WorldSettings.GameType.SURVIVAL, false, false, false),
                new SaveFormatComparator("a-actual-second", "same label", 1, 0, WorldSettings.GameType.SURVIVAL, false, false, false)));
            assign(screen, "field_146640_r", -1);
            for (String name : new String[] {"field_146641_z", "field_146642_y", "field_146630_A", "field_146631_B"})
                assign(screen, name, new GuiButton(1, 0, 0, ""));
            Constructor<?> constructor = Class.forName("net.minecraft.client.gui.GuiSelectWorld$List").getDeclaredConstructor(GuiSelectWorld.class);
            constructor.setAccessible(true); GuiSlot list = (GuiSlot) constructor.newInstance(screen); assign(screen, "field_146638_t", list);
            check(NativeMenuLists.discover(screen).get(0) == list, "Use the actual native world-selection list object");
            JsonObject described = NativeMenuLists.describe(list);
            check(described.get("rowLabelsAvailable").getAsBoolean()
                && described.getAsJsonArray("visibleRows").get(0).getAsJsonObject().get("worldFolder").getAsString().equals("z-actual-first"), "Duplicate titles retain actual native folder identity and native order");
            JsonObject args = new JsonObject(); args.addProperty("selected", 1);
            NativeMenuLists.setSelected(list, args, () -> true);
            check(NativeMenuLists.describe(list).getAsJsonArray("visibleRows").get(1).getAsJsonObject().get("selected").getAsBoolean(), "Real native single selection completes without Minecraft/world loading or double-clicking");
            NativeMenuLists.setSelected(list, args, () -> true);
            args.addProperty("selected", 0); expect("target_unverified", () -> NativeMenuLists.setSelected(list, args, () -> false));
            check(NativeMenuLists.describe(list).getAsJsonArray("visibleRows").get(1).getAsJsonObject().get("selected").getAsBoolean(), "Stale native selection preserves the current world choice");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void assign(GuiSelectWorld screen, String name, Object value) throws ReflectiveOperationException {
        Field field = GuiSelectWorld.class.getDeclaredField(name); field.setAccessible(true); field.set(screen, value);
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException expected) { check(code.equals(expected.code), "Native list failure " + expected.code); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
