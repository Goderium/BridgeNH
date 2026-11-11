package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/** Replacement versus appending, native commit boundaries, stale targets and truthful client-only receipts. */
public class GuiConfirmationSelfTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("GUI confirmation checks: " + run()); }
    public static int run() { checks = 0; legacyReplacement(); discoveredFields(); textCoverage(); callbackReceipt(); GuiControlAdapter.invalidate(); return checks; }

    private static void legacyReplacement() {
        ModularGui screen = new ModularGui(); TextFieldWidget field = new TextFieldWidget(); field.name = "address";
        screen.main.add(new TextWidget()); screen.main.add(field);
        com.gtnewhorizons.modularui.common.widget.ButtonWidget button = new com.gtnewhorizons.modularui.common.widget.ButtonWidget();
        button.name = "native button"; screen.main.add(button);
        JsonArray widgets = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets");
        JsonObject described = named(widgets, "address"); String id = described.get("widgetId").getAsString();
        check(described.get("framework").getAsString().equals("modularui1") && described.get("text").getAsString().equals("100")
                && described.getAsJsonArray("supportedActions").size() == 2, "legacy widget tree exposes the actual editable text and native actions");
        JsonObject set = action(id, "set_text"); set.addProperty("text", "10"); set.addProperty("expectedText", "100");
        JsonObject receipt = GuiControlAdapter.perform(screen, set, Collections.emptyList());
        check(field.text.equals("10") && field.replacements == 1 && field.commits == 1 && field.syncPackets == 1,
                "whole-string replacement produces 10 instead of appending a zero to 100, and invokes one native commit");
        check(field.serverText.equals("100") && !receipt.get("serverConfirmed").getAsBoolean()
                && receipt.get("localTextMatchesRequested").getAsBoolean() && receipt.getAsJsonObject("after").get("text").getAsString().equals("10"),
                "actual client readback is separate from an unobserved authoritative server value");
        check(receipt.get("localValueChanged").getAsBoolean() && receipt.get("confirmation").getAsString().equals("local_widget_readback_only"),
                "native accepted local editing does not imply authoritative confirmation");
        expect("value_changed", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList()));
        check(field.replacements == 1 && field.syncPackets == 1, "stale expectedText guard prevents duplicate replacement and resubmission");
        set.addProperty("expectedText", "10"); set.addProperty("text", "12"); set.addProperty("commit", false);
        receipt = GuiControlAdapter.perform(screen, set, Collections.emptyList());
        check(field.text.equals("12") && field.isFocused() && field.commits == 1 && !receipt.get("commitRequested").getAsBoolean(),
                "commit=false retains the native edit buffer without a sync packet");
        JsonObject unfocus = action(id, "focus"); unfocus.addProperty("focused", false); GuiControlAdapter.perform(screen, unfocus, Collections.emptyList());
        check(field.commits == 2 && field.syncPackets == 2 && !field.isFocused(), "native focus loss commits the retained buffer once");
        field.validator = value -> String.valueOf(Math.min(25, Integer.parseInt(value)));
        set.addProperty("expectedText", "12"); set.addProperty("text", "1000"); set.addProperty("commit", true);
        receipt = GuiControlAdapter.perform(screen, set, Collections.emptyList());
        check(field.text.equals("25") && !receipt.get("localTextMatchesRequested").getAsBoolean()
                && receipt.getAsJsonObject("after").get("text").getAsString().equals("25"), "native validation/clamping is reported as the actual post-commit value");
        set.addProperty("expectedText", "25"); set.addProperty("text", "invalid");
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList()));
        check(field.replacements == 3 && field.syncPackets == 3, "failed native pattern validation performs no edit or commit");
        set.addProperty("text", "10"); screen.main.enabled = false;
        expect("disabled", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList())); screen.main.enabled = true;
        ModularWindow popup = new ModularWindow(); popup.context = screen.context; screen.context.windows.add(popup);
        described = named(GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets"), "address");
        check(!described.get("enabled").getAsBoolean() && described.get("disabledReason").getAsString().equals("another_native_window_is_current"),
                "a live widget behind a different current window exposes its lock reason");
        expect("disabled", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList())); screen.context.windows.remove(popup);
        expect("stale_screen", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), null, () -> false));
        field.acceptFocus = false;
        expect("target_unverified", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList())); field.acceptFocus = true;
        check(field.replacements == 3 && field.syncPackets == 3, "disabled, stale and rejected-focus controls cannot be changed");
        String buttonId = named(widgets, "native button").get("widgetId").getAsString();
        receipt = GuiControlAdapter.perform(screen, action(buttonId, "activate"), Collections.emptyList());
        check(button.clicks == 1 && receipt.getAsJsonObject("nativeCallbackReturns").get("onClick").getAsString().equals("ACCEPT")
                && !receipt.get("serverConfirmed").getAsBoolean(), "a native click result is preserved independently of server confirmation");
        screen.main.children.remove(field); expect("stale_widget", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList()));
    }

    private static void discoveredFields() {
        OrdinaryScreen screen = new OrdinaryScreen(); screen.unknownName.setText("100"); screen.fields.add(screen.unknownName);
        JsonArray widgets = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets");
        check(widgets.size() == 1, "ordinary native fields are discovered by type, deduplicated across direct/list references, and model objects are not traversed");
        String id = widgets.get(0).getAsJsonObject().get("widgetId").getAsString();
        JsonObject before = GuiControlAdapter.observeInputState(screen, "gui confirmation fixture", Collections.emptyList(), null);
        JsonObject set = action(id, "set_text"); set.addProperty("text", "10"); set.addProperty("expectedText", "100");
        JsonObject edited = GuiControlAdapter.perform(screen, set, Collections.emptyList());
        JsonObject after = GuiControlAdapter.observeInputState(screen, "gui confirmation fixture", Collections.emptyList(), null);
        JsonObject receipt = GuiCallbacks.inputReceipt(before, after, true);
        check(screen.unknownName.getText().equals("10") && edited.get("localTextMatchesRequested").getAsBoolean(), "vanilla field replacement has exact text readback");
        check(receipt.get("localEffectObserved").getAsBoolean() && receipt.get("nativeAccepted").isJsonNull()
                && !receipt.get("serverConfirmed").getAsBoolean(), "observed native field change never invents a callback acceptance or server effect");
        screen.unknownName.setEnabled(false);
        expect("disabled", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList()));
    }

    private static void textCoverage() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        for (int i = 0; i < 150; i++) screen.root.children.add(new com.cleanroommc.modularui.widget.Widget());
        com.cleanroommc.modularui.widgets.TextWidget late = new com.cleanroommc.modularui.widgets.TextWidget(); late.text = "Late ordinary machine label";
        com.cleanroommc.modularui.widgets.RichTextWidget rich = new com.cleanroommc.modularui.widgets.RichTextWidget(); rich.rich.lines.add("Energy: 123 EU");
        screen.root.children.add(late); screen.root.children.add(rich);
        JsonObject extracted = GuiControlAdapter.visibleText(screen, "gui confirmation fixture", Collections.emptyList(), null);
        check(extracted.getAsJsonArray("entries").size() == 2 && extracted.get("completeForSupportedWidgets").getAsBoolean(),
                "text extraction reaches labels beyond inventory slots independently of the first widget page");
        check(extracted.getAsJsonArray("entries").get(0).getAsJsonObject().get("text").getAsString().equals("Late ordinary machine label")
                && extracted.getAsJsonArray("entries").get(1).getAsJsonObject().getAsJsonArray("textLines").get(0).getAsString().equals("Energy: 123 EU"),
                "public plain and rich labels both expose their current native text without drawing");
    }

    private static void callbackReceipt() {
        JsonObject state = new JsonObject(); state.addProperty("screenId", "same"); state.add("values", new JsonArray()); state.addProperty("complete", true);
        JsonObject receipt = GuiCallbacks.inputReceipt(state, state, true);
        check(!receipt.get("localEffectObserved").getAsBoolean() && receipt.get("nativeCallbackCompleted").getAsBoolean()
                && receipt.get("nativeAccepted").isJsonNull(), "unchanged client fields do not turn a callback return into intended effect");
        receipt = GuiCallbacks.inputReceipt(state, null, false);
        check(receipt.get("localEffectObserved").isJsonNull() && !receipt.get("observationComplete").getAsBoolean(),
                "a replaced screen prevents stale readback instead of reporting success");
    }

    public static class OrdinaryScreen extends GuiScreen {
        private final GuiTextField unknownName = new GuiTextField(null, 1, 2, 40, 12);
        private final List<GuiTextField> fields = new ArrayList<GuiTextField>();
        private final Model model = new Model();
    }
    public static class Model { public final GuiTextField hidden = new GuiTextField(null, 1, 2, 40, 12); }
    private static JsonObject named(JsonArray entries, String name) {
        for (JsonElement value : entries) if (value.getAsJsonObject().get("name").getAsString().equals(name)) return value.getAsJsonObject();
        throw new AssertionError("Missing " + name);
    }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "gui confirmation fixture"); return args; }
    private static JsonObject action(String widgetId, String action) { JsonObject args = args(); args.addProperty("widgetId", widgetId); args.addProperty("action", action); return args; }
    private static long generousStart() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error " + code); }
    }
}
