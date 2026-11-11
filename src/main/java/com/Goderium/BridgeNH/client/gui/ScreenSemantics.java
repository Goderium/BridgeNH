package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.lifecycle.WorldLifecycle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Stable GUI families from known public class/interface names, without loading optional mods. */
public class ScreenSemantics {
    private static final String[] KINDS = {"none", "main_menu", "pause_menu", "inventory", "creative_inventory",
            "container", "modularui", "nei_recipe", "ae", "chat", "unknown", "settings", "world_select",
            "server_list", "connection_progress", "disconnect", "confirmation", "game_over", "error_menu"};
    private static final ClassValue<String> CLASS_KINDS = new ClassValue<String>() {
        @Override protected String computeValue(Class<?> type) {
            if (is(type, "net.minecraft.client.gui.GuiMainMenu")) return "main_menu";
            if (is(type, "net.minecraft.client.gui.GuiIngameMenu")) return "pause_menu";
            if (is(type, "net.minecraft.client.gui.GuiChat")) return "chat";
            if (is(type, SodiumSettingsControls.SCREEN)) return "settings";
            for (String setting : new String[] {"GuiOptions", "GuiVideoSettings", "GuiControls", "GuiLanguage", "GuiScreenOptionsSounds",
                    "GuiScreenResourcePacks", "GuiChatOptions", "GuiSnooper", "GuiScreenOptions", "GuiStreamOptions"})
                if (is(type, "net.minecraft.client.gui." + setting)) return "settings";
            if (is(type, "net.minecraft.client.gui.GuiSelectWorld")) return "world_select";
            if (is(type, "net.minecraft.client.gui.GuiMultiplayer")) return "server_list";
            if (is(type, "net.minecraft.client.multiplayer.GuiConnecting") || is(type, "net.minecraft.client.multiplayer.GuiDownloadTerrain")) return "connection_progress";
            if (is(type, "net.minecraft.client.gui.GuiDisconnected")) return "disconnect";
            if (is(type, "cpw.mods.fml.client.GuiConfirmation")) return "confirmation";
            if (is(type, "cpw.mods.fml.client.GuiNotification")) return "error_menu";
            if (is(type, "net.minecraft.client.gui.GuiYesNo")) return "confirmation";
            if (is(type, "net.minecraft.client.gui.GuiGameOver")) return "game_over";
            if (is(type, "net.minecraft.client.gui.GuiMemoryErrorScreen") || is(type, "net.minecraft.client.gui.GuiErrorScreen")) return "error_menu";
            if (is(type, "codechicken.nei.recipe.GuiRecipe") || is(type, "codechicken.nei.recipe.GuiCraftingRecipe")
                    || is(type, "codechicken.nei.recipe.GuiUsageRecipe")) return "nei_recipe";
            if (is(type, "com.cleanroommc.modularui.api.IMuiScreen")
                    || is(type, "com.cleanroommc.modularui.screen.GuiContainerWrapper")) return "modularui";
            if (is(type, "appeng.client.gui.AEBaseGui")) return "ae";
            if (is(type, "net.minecraft.client.gui.inventory.GuiContainerCreative")) return "creative_inventory";
            if (is(type, "net.minecraft.client.gui.inventory.GuiInventory")) return "inventory";
            if (is(type, "net.minecraft.client.gui.inventory.GuiContainer")) return "container";
            return "unknown";
        }
    };

    public static JsonArray kinds() {
        JsonArray result = new JsonArray();
        for (String kind : KINDS) result.add(new JsonPrimitive(kind));
        return result;
    }

    @com.google.common.annotations.VisibleForTesting
    public static String classKind(Class<?> type) { return type == null ? "none" : CLASS_KINDS.get(type); }

    public static String kind(Object screen) {
        if (screen == null) return "none";
        // CustomMainMenu's main-menu identity is public instance configuration, not a class-wide property.
        if ("lumien.custommainmenu.gui.GuiCustom".equals(screen.getClass().getName()) && WorldLifecycle.mainMenu(screen))
            return "main_menu";
        return classKind(screen.getClass());
    }

    public static JsonObject describe(JsonObject result, Object screen) {
        result.addProperty("screenOpen", screen != null);
        result.addProperty("screenKind", kind(screen));
        return result;
    }

    private static boolean is(Class<?> type, String name) { return ExpandedGuiApi.named(type, name) != null; }
}
