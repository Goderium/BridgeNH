package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.google.gson.JsonObject;
import java.util.Collections;

/** Named public state semantics preserve the normal source update without invoking physical mouse callbacks. */
final class NamedStructureStateSelfTest {
    private static int checks;
    static int run() {
        checks = 0; recheck(); restrictions(); powerSetter(); GuiControlAdapter.invalidate(); return checks;
    }
    private static ToggleButton recheckButton() {
        ToggleButton button = new ToggleButton(); button.overlay = new UITexture("gregtech:textures/gui/overlay_button/structure_update.png");
        button.mouseBlocked = true; return button;
    }
    private static void recheck() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen(); ToggleButton button = recheckButton(); screen.root.children.add(button);
        String id = id(screen); expect("adapter_error", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
        check(button.changes == 0 && button.presses == 1, "generic activate retains its original native mouse path and does not bypass input refusal");
        JsonObject result = GuiControlAdapter.perform(screen, action(id, "recheck_structure"), Collections.emptyList());
        check(result.get("submitted").getAsBoolean() && !result.get("serverConfirmed").getAsBoolean()
                && "mui2_structure_recheck_state".equals(result.get("nativeAction").getAsString()), "named native state invocation remains a local submission rather than server confirmation");
        check(button.selected == 1 && button.changes == 1 && button.presses == 1 && button.releases == 0,
                "named recheck uses exactly one inherited source setter without a mouse press/release");
        JsonObject pending = GuiControlAdapter.perform(screen, action(id, "recheck_structure"), Collections.emptyList());
        check(!pending.get("submitted").getAsBoolean() && pending.get("pending").getAsBoolean() && button.changes == 1 && button.selected == 1,
                "already selected structure update remains pending and is never toggled back or submitted twice");
    }
    private static void restrictions() {
        WidgetFixtures.Screen unknownScreen = new WidgetFixtures.Screen(); UnknownToggle unknown = new UnknownToggle();
        unknown.overlay = new UITexture("gregtech:textures/gui/overlay_button/structure_update.png"); unknown.mouseBlocked = true; unknownScreen.root.children.add(unknown);
        String unknownId = id(unknownScreen); expect("adapter_error", () -> GuiControlAdapter.perform(unknownScreen, action(unknownId, "recheck_structure"), Collections.emptyList()));
        check(unknown.changes == 0 && unknown.presses == 1, "unknown subclasses retain the original guarded mouse path even with a GT structure label");
        WidgetFixtures.Screen foreignScreen = new WidgetFixtures.Screen(); ToggleButton foreign = recheckButton();
        foreign.overlay = new UITexture("foreign:textures/gui/overlay_button/structure_update.png"); foreignScreen.root.children.add(foreign);
        String foreignId = id(foreignScreen); expect("unsupported", () -> GuiControlAdapter.perform(foreignScreen, action(foreignId, "recheck_structure"), Collections.emptyList()));
        check(foreign.changes == 0 && foreign.presses == 0, "foreign resources do not authorize the named state route");
        WidgetFixtures.Screen nonBooleanScreen = new WidgetFixtures.Screen(); ToggleButton nonBoolean = recheckButton(); nonBoolean.count = 3; nonBooleanScreen.root.children.add(nonBoolean);
        String nonBooleanId = id(nonBooleanScreen); expect("unsupported", () -> GuiControlAdapter.perform(nonBooleanScreen, action(nonBooleanId, "recheck_structure"), Collections.emptyList()));
        check(nonBoolean.changes == 0, "a non-boolean control cannot use the named state route");
        WidgetFixtures.Screen live = new WidgetFixtures.Screen(); ToggleButton button = recheckButton(); live.root.children.add(button); String liveId = id(live);
        live.root.enabled = false; expect("disabled", () -> GuiControlAdapter.perform(live, action(liveId, "recheck_structure"), Collections.emptyList()));
        live.root.enabled = true; live.root.children.remove(button); expect("stale_widget", () -> GuiControlAdapter.perform(live, action(liveId, "recheck_structure"), Collections.emptyList()));
        check(button.changes == 0, "parent state and live object identity are revalidated before the source setter");
    }
    private static void powerSetter() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen(); ToggleButton power = new ToggleButton(); power.mouseBlocked = true;
        power.overlay = new UITexture("gregtech:textures/gui/overlay_button/power_switch_off.png"); screen.root.children.add(power); String id = id(screen);
        expect("adapter_error", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
        JsonObject on = action(id, "set_selected"); on.addProperty("selected", 1); GuiControlAdapter.perform(screen, on, Collections.emptyList());
        JsonObject off = action(id, "set_selected"); off.addProperty("selected", 0); GuiControlAdapter.perform(screen, off, Collections.emptyList());
        check(power.selected == 0 && power.changes == 2 && power.presses == 1 && power.releases == 0,
                "existing explicit power state setters retain normal source updates while generic activation remains guarded");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(id, "recheck_structure"), Collections.emptyList()));
        check(power.changes == 2, "power semantics do not authorize a structure recheck");
    }
    private static String id(WidgetFixtures.Screen screen) {
        return GuiControlAdapter.read(screen, args(), Collections.emptyList(), System.nanoTime() + 60000000000L)
                .getAsJsonArray("widgets").get(1).getAsJsonObject().get("widgetId").getAsString();
    }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "named state fixture"); return args; }
    private static JsonObject action(String id, String action) { JsonObject args = args(); args.addProperty("widgetId", id); args.addProperty("action", action); return args; }
    public static final class UnknownToggle extends ToggleButton {}
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable task) { try { task.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code + ", actual " + error.code); } }
}
