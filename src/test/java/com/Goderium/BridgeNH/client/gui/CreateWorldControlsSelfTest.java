package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiCreateWorld;

public final class CreateWorldControlsSelfTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "create-world-fixture"); args.addProperty("limit", 128); return args; }
    private static JsonObject read(GuiCreateWorld screen) { return GuiControlAdapter.read(screen, args(), screen.buttons(), System.nanoTime() + 1000000000L); }
    private static JsonObject choice(JsonObject page, String setting) {
        for (JsonElement row : page.getAsJsonArray("widgets")) if (row.getAsJsonObject().has("setting") && setting.equals(row.getAsJsonObject().get("setting").getAsString())) return row.getAsJsonObject();
        throw new AssertionError("Missing choice " + setting);
    }
    private static JsonObject selection(JsonObject page, String setting, int selected) {
        JsonObject result = new JsonObject(); result.add("widgetId", choice(page, setting).get("widgetId")); result.addProperty("selected", selected); return result;
    }
    private static JsonObject action(JsonObject page, String setting, int selected) {
        JsonObject result = selection(page, setting, selected); result.addProperty("screenId", "create-world-fixture"); result.addProperty("action", "set_selected"); return result;
    }
    private static JsonObject batch(JsonObject... values) {
        JsonObject args = new JsonObject(); args.addProperty("screenId", "create-world-fixture"); args.addProperty("action", "set_selected_many");
        JsonArray selections = new JsonArray(); for (JsonObject value : values) selections.add(value); args.add("selections", selections); return args;
    }
    private static void refused(Runnable call, String code) {
        try { call.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "Explicit refusal " + code); }
    }
    public static int run() {
        checks = 0; GuiControlAdapter.invalidate(); GuiCreateWorld screen = new GuiCreateWorld(); JsonObject page = read(screen);
        check(choice(page, "game_mode").get("selected").getAsInt() == 0, "Native survival state is exposed");
        check(choice(page, "world_type").getAsJsonArray("options").size() >= 4, "World types come from the runtime registry");
        check(!choice(page, "world_type").get("enabled").getAsBoolean(), "Hidden choice remains unavailable for immediate invocation");
        JsonObject result = GuiControlAdapter.perform(screen, action(page, "game_mode", 2), screen.buttons());
        check(result.get("localSelectionMatched").getAsBoolean() && screen.callbacks == 2, "One request reaches creative through two real transitions");
        check(result.getAsJsonObject("after").get("value").getAsString().equals("creative"), "Same-call after observation confirms actual selection");
        check(result.getAsJsonObject("nativeSelection").getAsJsonObject("afterState").get("allowCommands").getAsBoolean(), "Native game-mode side effect is preserved");
        result = GuiControlAdapter.perform(screen, action(page, "game_mode", 2), screen.buttons());
        check(!result.get("submitted").getAsBoolean() && result.get("localSelectionMatched").getAsBoolean() && screen.callbacks == 2, "Already selected is a verified zero-callback result");
        refused(() -> GuiControlAdapter.perform(screen, action(page, "game_mode", 99), screen.buttons()), "invalid_args");
        check(screen.callbacks == 2, "Invalid target invokes no callback");
        GuiCreateWorld fresh = new GuiCreateWorld(); JsonObject menu = read(fresh);
        result = GuiControlAdapter.perform(fresh, batch(selection(menu, "game_mode", 2), selection(menu, "more_world_options", 1), selection(menu, "world_type", 1)), fresh.buttons());
        check(result.get("localSelectionMatched").getAsBoolean() && result.get("completedSelections").getAsInt() == 3, "A single ordered call configures creative and flat across menu pages");
        check(fresh.callbacks == 4 && fresh.creates == 0, "Only required configuration callbacks run; no world creation");
        check(result.getAsJsonObject("afterState").get("worldType").getAsString().equals("flat"), "Final batch readback proves world type");
        result = GuiControlAdapter.perform(fresh, batch(selection(menu, "game_mode", 2), selection(menu, "more_world_options", 1), selection(menu, "world_type", 1)), fresh.buttons());
        check(result.get("localSelectionMatched").getAsBoolean() && !result.get("submitted").getAsBoolean() && fresh.callbacks == 4,
            "Already-matching batch reads hidden settings without a page switch or any callback");
        fresh.field_146334_a = "fixture preset";
        result = GuiControlAdapter.perform(fresh, action(menu, "world_type", 1), fresh.buttons());
        check(!result.get("submitted").getAsBoolean() && fresh.field_146334_a.equals("fixture preset"), "No-op preserves custom generator settings");
        GuiControlAdapter.perform(fresh, action(menu, "world_type", 0), fresh.buttons());
        check(fresh.field_146334_a.isEmpty(), "A changed world type uses normal generator-options clearing");
        JsonObject bad = new JsonObject(); bad.addProperty("widgetId", "absent"); bad.addProperty("selected", 1); int before = fresh.callbacks;
        refused(() -> GuiControlAdapter.perform(fresh, batch(selection(menu, "map_features", 0), bad), fresh.buttons()), "stale_widget");
        check(fresh.callbacks == before, "Entire batch identity preflight precedes any change");
        for (JsonElement row : menu.getAsJsonArray("widgets")) if (row.getAsJsonObject().has("nativeButtonId") && row.getAsJsonObject().get("nativeButtonId").getAsInt() == 0) {
            JsonObject creation = new JsonObject(); creation.add("widgetId", row.getAsJsonObject().get("widgetId")); creation.addProperty("selected", 1);
            refused(() -> GuiControlAdapter.perform(fresh, batch(selection(menu, "map_features", 0), creation), fresh.buttons()), "unsupported");
            check(fresh.callbacks == before && fresh.creates == 0, "Creation is rejected before any configuration callback");
        }
        refused(() -> GuiControlAdapter.perform(fresh, batch(selection(menu, "map_features", 0), selection(menu, "map_features", 1)), fresh.buttons()), "invalid_args");
        GuiCreateWorld ignored = new GuiCreateWorld(); JsonObject ignoredPage = read(ignored); ignored.ignore = true;
        result = GuiControlAdapter.perform(ignored, batch(selection(ignoredPage, "game_mode", 2), selection(ignoredPage, "more_world_options", 1)), ignored.buttons());
        check(!result.get("localSelectionMatched").getAsBoolean() && ignored.callbacks == 1, "Unchanged state stops after one callback instead of repeated clicking");
        check(result.getAsJsonArray("selections").get(1).getAsJsonObject().get("status").getAsString().equals("not_executed"), "Remaining selections stop on nonconfirmation");
        GuiCreateWorld changed = new GuiCreateWorld(); JsonObject changedPage = read(changed); boolean[] current = {true}; changed.callback = () -> current[0] = false;
        result = GuiControlAdapter.perform(changed, action(changedPage, "game_mode", 2), changed.buttons(), null, () -> current[0]);
        check(changed.callbacks == 1 && !result.get("localSelectionMatched").getAsBoolean(), "Screen changes prevent a second transition");
        GuiCreateWorld failed = new GuiCreateWorld(); JsonObject failedPage = read(failed); failed.fail = true;
        result = GuiControlAdapter.perform(failed, action(failedPage, "game_mode", 2), failed.buttons());
        check(result.get("outcomeUnknown").getAsBoolean() && !result.get("localSelectionMatched").getAsBoolean() && failed.callbacks == 1, "Callback exceptions preserve uncertainty and never retry");
        GuiCreateWorld unreadable = new GuiCreateWorld(); JsonObject unreadablePage = read(unreadable);
        GuiControlAdapter.perform(unreadable, action(unreadablePage, "more_world_options", 1), unreadable.buttons());
        net.minecraft.world.WorldType[] types = net.minecraft.world.WorldType.worldTypes;
        unreadable.callback = () -> net.minecraft.world.WorldType.worldTypes = null;
        try {
            result = GuiControlAdapter.perform(unreadable, action(unreadablePage, "world_type", 1), unreadable.buttons());
            check(result.get("outcomeUnknown").getAsBoolean() && !result.get("localSelectionMatched").getAsBoolean(), "Unavailable post-callback readback cannot claim a known outcome");
        } finally { net.minecraft.world.WorldType.worldTypes = types; }
        GuiCreateWorld sideEffect = new GuiCreateWorld(); JsonObject sideEffectPage = read(sideEffect);
        result = GuiControlAdapter.perform(sideEffect, batch(selection(sideEffectPage, "allow_commands", 0), selection(sideEffectPage, "game_mode", 2)), sideEffect.buttons());
        check(result.get("completedSelections").getAsInt() == 2 && !result.get("localSelectionMatched").getAsBoolean()
            && result.getAsJsonObject("afterState").get("allowCommands").getAsBoolean(),
            "Final acceptance rejects a later native default that invalidates an earlier matching choice");
        GuiControlAdapter.invalidate(); return checks;
    }
}
