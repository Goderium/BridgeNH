package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.WorldType;

/** Fixed vanilla menu observations; changes always use the native button callback. */
final class VanillaCreateWorldControls {
    private static final String SCREEN = "net.minecraft.client.gui.GuiCreateWorld";
    private static final int MAX_TRANSITIONS = 32, MAX_WORLD_TYPES = 256;
    private VanillaCreateWorldControls() { }

    static boolean screen(Object screen) { return ExpandedGuiApi.is(screen, SCREEN); }

    private static String setting(Object screen, Object widget) {
        if (!screen(screen) || !(widget instanceof GuiButton)) return null;
        String field, name;
        switch (((GuiButton) widget).id) {
            case 2: field = "field_146343_z"; name = "game_mode"; break;
            case 3: field = "field_146324_A"; name = "more_world_options"; break;
            case 4: field = "field_146325_B"; name = "map_features"; break;
            case 5: field = "field_146320_D"; name = "world_type"; break;
            case 6: field = "field_146321_E"; name = "allow_commands"; break;
            case 7: field = "field_146326_C"; name = "bonus_chest"; break;
            default: return null;
        }
        return ExpandedGuiApi.field(screen, SCREEN, field) == widget ? name : null;
    }

    static JsonObject read(Object screen, Object widget) {
        String setting = setting(screen, widget); if (setting == null) return null;
        JsonObject result = new JsonObject(); JsonArray options = new JsonArray(); int selected = -1;
        result.addProperty("setting", setting); result.addProperty("kind", "choice");
        if ("game_mode".equals(setting)) {
            String current = (String) field(screen, "field_146342_r");
            String[] modes = {"survival", "hardcore", "creative"};
            for (int index = 0; index < modes.length; index++) {
                option(options, index, modes[index], "selectWorld.gameMode." + modes[index]);
                if (modes[index].equals(current)) selected = index;
            }
        } else if ("world_type".equals(setting)) {
            WorldType[] types = worldTypes(); selected = ((Number) field(screen, "field_146331_K")).intValue();
            for (int index = 0; index < types.length; index++) if (types[index] != null && types[index].getCanBeCreated())
                option(options, index, types[index].getWorldTypeName(), types[index].getTranslateName());
        } else {
            boolean value = "more_world_options".equals(setting) ? flag(screen, "field_146344_y")
                : "map_features".equals(setting) ? flag(screen, "field_146341_s")
                : "allow_commands".equals(setting) ? flag(screen, "field_146340_t") && !flag(screen, "field_146337_w")
                : flag(screen, "field_146338_v") && !flag(screen, "field_146337_w");
            selected = value ? 1 : 0;
            option(options, 0, "more_world_options".equals(setting) ? "basic" : "off", "more_world_options".equals(setting) ? null : "options.off");
            option(options, 1, "more_world_options".equals(setting) ? "advanced" : "on", "more_world_options".equals(setting) ? null : "options.on");
        }
        int position = position(options, selected);
        if (position < 0) throw new BridgeException("unsupported", "The native menu selection is outside the available options.");
        result.addProperty("selected", selected); result.add("value", options.get(position).getAsJsonObject().get("value"));
        result.add("options", options); result.addProperty("optionCount", options.size());
        result.addProperty("valueSource", "native_create_world_fields");
        return result;
    }

    static void validate(JsonObject state, int selected) {
        if (state == null) throw new BridgeException("unsupported", "Only advertised create-world choices support this selection batch.");
        if (position(state.getAsJsonArray("options"), selected) < 0)
            throw new BridgeException("invalid_args", "selected must match an advertised options[].index.");
    }

    static JsonObject select(Object screen, GuiButton button, int selected, BooleanSupplier current) {
        JsonObject before = read(screen, button); validate(before, selected);
        JsonArray options = before.getAsJsonArray("options");
        int initial = position(options, before.get("selected").getAsInt()), target = position(options, selected);
        int distance = (target - initial + options.size()) % options.size();
        if (distance > MAX_TRANSITIONS) throw new BridgeException("data_limit", "Native choice requires more than 32 transitions; no callback was invoked.");
        JsonObject result = new JsonObject(); result.addProperty("requestedSelected", selected);
        result.add("requestedValue", options.get(target).getAsJsonObject().get("value"));
        result.add("beforeState", state(screen));
        int attempts = 0, completed = 0; String stop = null; boolean unknown = false;
        JsonObject after = before;
        long deadline = System.nanoTime() + 25000000L;
        for (int step = 0; step < distance; step++) {
            if (!current.getAsBoolean()) { stop = "screen_changed"; break; }
            if (!button.visible || !button.enabled) { stop = "control_disabled"; break; }
            if (System.nanoTime() >= deadline) { stop = "transition_budget_exhausted"; break; }
            try {
                JsonObject live = read(screen, button);
                if (live == null || !options.equals(live.get("options")) || !after.get("selected").equals(live.get("selected"))) {
                    stop = "choice_changed"; break;
                }
                GuiControlAdapter.neutralInput(); attempts++;
                ExpandedGuiApi.protectedCall(screen, GuiScreen.class, new String[]{"actionPerformed", "func_146284_a"}, GuiButton.class, button);
                completed++;
                if (!current.getAsBoolean()) { stop = "screen_changed"; break; }
                after = read(screen, button);
                if (after == null || !options.equals(after.get("options"))) { stop = "choice_changed"; break; }
                if (after.get("selected").getAsInt() == selected) break;
                int expected = options.get((initial + step + 1) % options.size()).getAsJsonObject().get("index").getAsInt();
                if (after.get("selected").getAsInt() != expected) { stop = "unexpected_transition"; break; }
            } catch (RuntimeException | LinkageError error) {
                stop = "native_callback_or_readback_failed"; unknown = attempts > 0;
                result.addProperty("errorType", error.getClass().getSimpleName()); break;
            }
        }
        boolean same = current.getAsBoolean();
        boolean matched = stop == null && same && after != null && after.get("selected").getAsInt() == selected;
        result.addProperty("localSelectionMatched", matched); result.addProperty("status", matched ? "completed" : "not_confirmed");
        result.addProperty("callbackAttempts", attempts); result.addProperty("nativeTransitions", completed);
        result.addProperty("outcomeUnknown", unknown); result.addProperty("currentScreen", same);
        if (stop != null) result.addProperty("stopReason", stop);
        if (same) try { result.add("afterState", state(screen)); } catch (RuntimeException | LinkageError error) { result.addProperty("stateReadbackUnavailable", true); }
        result.addProperty("worldCreationRequested", false); result.addProperty("persistenceVerified", false);
        return result;
    }

    static JsonObject state(Object screen) {
        JsonObject result = new JsonObject();
        result.addProperty("gameMode", (String) field(screen, "field_146342_r"));
        int type = ((Number) field(screen, "field_146331_K")).intValue(); WorldType[] types = worldTypes();
        if (type < 0 || type >= types.length || types[type] == null) throw new BridgeException("unsupported", "Current world type is unavailable.");
        result.addProperty("worldTypeIndex", type); result.addProperty("worldType", types[type].getWorldTypeName());
        result.addProperty("moreWorldOptions", flag(screen, "field_146344_y"));
        result.addProperty("mapFeatures", flag(screen, "field_146341_s"));
        boolean hardcore = flag(screen, "field_146337_w"); result.addProperty("hardcore", hardcore);
        result.addProperty("allowCommands", flag(screen, "field_146340_t") && !hardcore);
        result.addProperty("bonusChest", flag(screen, "field_146338_v") && !hardcore);
        result.addProperty("customGeneratorOptionsPresent", !((String) field(screen, "field_146334_a")).isEmpty());
        return result;
    }

    private static Object field(Object screen, String name) { return ExpandedGuiApi.field(screen, SCREEN, name); }
    private static boolean flag(Object screen, String name) { return (Boolean) field(screen, name); }
    private static WorldType[] worldTypes() {
        WorldType[] types = WorldType.worldTypes;
        if (types == null || types.length == 0 || types.length > MAX_WORLD_TYPES)
            throw new BridgeException("unsupported", "World-type registry exceeds the bounded native menu adapter.");
        return types;
    }
    private static int position(JsonArray options, int selected) {
        for (int index = 0; index < options.size(); index++) if (options.get(index).getAsJsonObject().get("index").getAsInt() == selected) return index;
        return -1;
    }
    private static void option(JsonArray options, int index, String value, String key) {
        JsonObject row = new JsonObject(); row.addProperty("index", index); row.addProperty("value", value);
        String label = value;
        if (key != null) {
            row.addProperty("translationKey", key);
            try { label = I18n.format(key); } catch (RuntimeException | LinkageError unavailable) { /* Headless/initializing locale. */ }
        }
        row.addProperty("label", JsonData.bounded(label, 256)); options.add(row);
    }
}
