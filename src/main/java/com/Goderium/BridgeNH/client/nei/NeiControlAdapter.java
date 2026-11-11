package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Semantic NEI operations on its own client thread, under the bridge's takeover gate. */
public class NeiControlAdapter {
    private static final String GUI = "codechicken.nei.recipe.GuiRecipe";

    public static JsonObject open(Minecraft client, GuiScreen screen, JsonObject args) {
        return openCurrent(client, screen, args);
    }
    static JsonObject openCurrent(Minecraft client, Object screen, JsonObject args) {
        String mode = JsonData.string(args, "mode");
        if (!"crafting".equals(mode) && !"usage".equals(mode)) throw new BridgeException("invalid_args", "mode must be crafting or usage.");
        ItemStack stack = select(client, screen, args);
        if (stack == null) throw new BridgeException("not_found", "Selected item source is empty.");
        String owner = "crafting".equals(mode) ? "codechicken.nei.recipe.GuiCraftingRecipe" : "codechicken.nei.recipe.GuiUsageRecipe";
        try {
            Class<?> api = Class.forName(owner, false, NeiControlAdapter.class.getClassLoader());
            boolean opened = Boolean.TRUE.equals(AdapterAccess.invoke(api, null, "openRecipeGui", new Class<?>[] {String.class, Object[].class},
                    false, "item", new Object[] {stack.copy()}));
            JsonObject result = new JsonObject(); result.addProperty("opened", opened);
            result.addProperty("mode", mode); result.addProperty("source", JsonData.string(args, "source"));
            result.add("queryItem", GTRecipeAdapter.value(stack, 0));
            result.addProperty("queryIdentity", "Actual source stack copied with NBT; NEI may normalize its own copy for recipe querying");
            if (opened) result.add("navigation", navigation(client.currentScreen, 0, 32));
            return result;
        } catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "NEI recipe-open API is unavailable."); }
    }

    public static JsonObject navigate(Minecraft client, GuiScreen screen, JsonObject args) {
        return navigateCurrent(client, screen, args);
    }
    static JsonObject navigateCurrent(Minecraft client, Object screen, JsonObject args) {
        validateNavigation(args);
        Class<?> api = AdapterAccess.type(screen, GUI);
        if (api == null) throw new BridgeException("unsupported", "Current screen is not a NEI GuiRecipe.");
        String action = JsonData.string(args, "action");
        JsonObject result = new JsonObject(); result.addProperty("action", action);
        if ("resume".equals(action)) {
            if (args.has("index") || args.has("shift") || args.has("saveCount") || args.has("ttl_ms")) throw new BridgeException("invalid_args", "resume accepts only action and screenId.");
            NeiModifierHold.clear("explicit_resume"); result.add("modifiers", NeiModifierHold.inspect()); return result;
        }
        if ("back".equals(action)) {
            if (args.has("index") || args.has("shift")) throw new BridgeException("invalid_args", "back does not accept index or shift.");
            Object previous = AdapterAccess.field(screen, GUI, "prevGui", false);
            if (previous != null && !(previous instanceof GuiScreen)) throw new BridgeException("adapter_error", "NEI previous screen has an invalid type.");
            client.displayGuiScreen((GuiScreen) previous);
            result.addProperty("navigated", true); return result;
        }
        int index = JsonData.integer(args, "index", 0, 100000);
        if ("bookmark".equals(action)) return NeiOverlayAdapter.bookmark(screen, index, GuiDataAdapter.booleanArg(args, "saveCount", true));
        if ("overlay".equals(action)) return NeiOverlayAdapter.perform(client, screen, index, GuiDataAdapter.booleanArg(args, "shift", false));
        if (args.has("shift")) throw new BridgeException("invalid_args", "shift is only valid for the semantic overlay action.");
        if ("handler".equals(action)) {
            Object handlers = AdapterAccess.field(screen, GUI, "currenthandlers", false);
            if (!(handlers instanceof List<?>) || index >= ((List<?>) handlers).size()) throw new BridgeException("not_found", "Handler index is outside the current screen's handlers.");
            AdapterAccess.invoke(api, screen, "setRecipePage", new Class<?>[] {int.class}, true, index);
        } else if ("page".equals(action)) {
            Object pages = AdapterAccess.field(screen, GUI, "handlerPages", true);
            int count = ((Number) AdapterAccess.call(pages, "getNumPages")).intValue();
            if (index >= count) throw new BridgeException("not_found", "Page index is outside the current handler's pages.");
            int current = ((Number) AdapterAccess.call(pages, "getCurrentPageIndex")).intValue();
            AdapterAccess.call(pages, "changePage", new Class<?>[] {int.class}, index - Math.max(0, current));
        } else throw new BridgeException("invalid_args", "action must be handler, page, overlay, bookmark, pause, resume or back.");
        result.addProperty("navigated", true); result.add("navigation", navigation(screen, 0, 32)); return result;
    }
    public static void validateNavigation(JsonObject args) {
        String action = JsonData.string(args, "action");
        if ("pause".equals(action)) { ExpandedGuiApi.keys(args, "action", "screenId", "index", "ttl_ms"); JsonData.integer(args, "index", 0, 100000); if (args.has("ttl_ms")) JsonData.integer(args, "ttl_ms", 100, 60000); }
        else if ("resume".equals(action) || "back".equals(action)) ExpandedGuiApi.keys(args, "action", "screenId");
        else if ("bookmark".equals(action)) {
            ExpandedGuiApi.keys(args, "action", "screenId", "index", "saveCount", "taskId", "retention");
            JsonData.integer(args, "index", 0, 100000); GuiDataAdapter.booleanArg(args, "saveCount", true);
            if (args.has("taskId") || args.has("retention")) {
                if (ExpandedGuiApi.string(args, "taskId", 128).isEmpty()) throw new BridgeException("invalid_args", "taskId must not be empty.");
                ExpandedGuiApi.option(args, "retention", "temporary", "temporary", "permanent", "future");
            }
        }
        else if ("overlay".equals(action)) { ExpandedGuiApi.keys(args, "action", "screenId", "index", "shift"); JsonData.integer(args, "index", 0, 100000); GuiDataAdapter.booleanArg(args, "shift", false); }
        else if ("handler".equals(action) || "page".equals(action)) { ExpandedGuiApi.keys(args, "action", "screenId", "index"); JsonData.integer(args, "index", 0, 100000); }
        else throw new BridgeException("invalid_args", "Unknown NEI navigation action.");
    }

    /** Identity/page information for inspection and control replies; current handler list only. */
    public static JsonObject navigation(Object screen, int offset, int limit) {
        if (AdapterAccess.type(screen, GUI) == null) throw new BridgeException("unsupported", "Current screen is not a NEI GuiRecipe.");
        Object handlers = AdapterAccess.field(screen, GUI, "currenthandlers", false);
        if (!(handlers instanceof List<?>)) throw new BridgeException("adapter_error", "NEI current handler list unavailable.");
        List<?> list = (List<?>) handlers; JsonObject result = new JsonObject(); JsonArray data = new JsonArray();
        int end = Math.min(list.size(), offset + Math.min(limit, 128));
        for (int i = Math.min(offset, list.size()); i < end; i++) {
            Object handler = list.get(i); JsonObject entry = new JsonObject(); entry.addProperty("index", i);
            entry.add("id", GTRecipeAdapter.value(AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "getHandlerId", new Class<?>[0]), 0));
            entry.add("name", GTRecipeAdapter.value(AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "getRecipeName", new Class<?>[0]), 0));
            entry.add("recipeCount", GTRecipeAdapter.value(AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "numRecipes", new Class<?>[0]), 0));
            data.add(entry);
        }
        result.add("handlers", data); result.addProperty("handlerCount", list.size());
        result.add("handlerIndex", GTRecipeAdapter.value(AdapterAccess.field(screen, GUI, "recipetype", false), 0));
        Object pages = AdapterAccess.field(screen, GUI, "handlerPages", true);
        result.add("pageIndex", GTRecipeAdapter.value(AdapterAccess.call(pages, "getCurrentPageIndex"), 0));
        result.add("pageCount", GTRecipeAdapter.value(AdapterAccess.call(pages, "getNumPages"), 0));
        result.addProperty("offset", offset); result.addProperty("nextOffset", end); result.addProperty("hasMore", end < list.size());
        result.addProperty("indices", "Zero-based handler and page indices in this screen; recipe indices remain original handler indices");
        try { result.add("overlayControls", NeiOverlayAdapter.inspect(screen)); }
        catch (BridgeException error) { result.addProperty("overlayControlsUnavailable", error.code); }
        return result;
    }

    private static ItemStack select(Minecraft client, Object screen, JsonObject args) {
        String source = JsonData.string(args, "source");
        if ("registry".equals(source)) {
            sourceArgs(args, "registryName", "metadata");
            String name = JsonData.string(args, "registryName");
            if (name.isEmpty() || name.length() > 256) throw new BridgeException("invalid_args", "registryName length must be 1..256.");
            Object item = Item.itemRegistry.getObject(name);
            if (!(item instanceof Item)) throw new BridgeException("not_found", "Item registry identity was not found.");
            return new ItemStack((Item) item, 1, args.has("metadata") ? JsonData.integer(args, "metadata", 0, 32767) : 0);
        }
        if ("inventory".equals(source)) {
            sourceArgs(args, "slot");
            int slot = JsonData.integer(args, "slot", 0, 255);
            if (slot >= client.thePlayer.inventory.getSizeInventory()) throw new BridgeException("not_found", "Player inventory slot is outside its actual size.");
            return client.thePlayer.inventory.getStackInSlot(slot);
        }
        if ("container".equals(source)) {
            sourceArgs(args, "slot");
            if (!(screen instanceof GuiContainer)) throw new BridgeException("unsupported", "Current screen has no ordinary container slots.");
            int number = JsonData.integer(args, "slot", 0, Integer.MAX_VALUE), visited = 0;
            for (Object object : ((GuiContainer) screen).inventorySlots.inventorySlots) {
                if (++visited > 512) break;
                if (object instanceof Slot && ((Slot) object).slotNumber == number) return ((Slot) object).getStack();
            }
            throw new BridgeException("not_found", "No ordinary slot has that slotNumber.");
        }
        if ("recipe".equals(source)) {
            sourceArgs(args, "recipeIndex", "group", "position", "alternative");
            if (AdapterAccess.type(screen, GUI) == null) throw new BridgeException("unsupported", "Recipe source requires an open NEI recipe page.");
            Object handler = AdapterAccess.call(screen, "getHandler");
            int index = JsonData.integer(args, "recipeIndex", 0, 100000);
            int count = ((Number) AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "numRecipes", new Class<?>[0])).intValue();
            if (index >= count) throw new BridgeException("not_found", "Recipe index is outside the current handler.");
            String group = args.has("group") ? JsonData.string(args, "group") : "inputs";
            Object positions;
            if ("inputs".equals(group)) positions = AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "getIngredientStacks", new Class<?>[] {int.class}, index);
            else if ("outputs".equals(group)) positions = java.util.Collections.singletonList(AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "getResultStack", new Class<?>[] {int.class}, index));
            else if ("other".equals(group)) positions = AdapterAccess.callOn(handler, "codechicken.nei.recipe.IRecipeHandler", "getOtherStacks", new Class<?>[] {int.class}, index);
            else throw new BridgeException("invalid_args", "group must be inputs, outputs or other.");
            int position = args.has("position") ? JsonData.integer(args, "position", 0, 63) : 0;
            int alternative = args.has("alternative") ? JsonData.integer(args, "alternative", 0, 31) : 0;
            if (!(positions instanceof List<?>) || position >= ((List<?>) positions).size()) throw new BridgeException("not_found", "Recipe position is unavailable.");
            Object positioned = ((List<?>) positions).get(position);
            if (positioned == null) return null;
            Object items = AdapterAccess.field(positioned, "codechicken.nei.PositionedStack", "items", false);
            if (!(items instanceof ItemStack[]) || alternative >= ((ItemStack[]) items).length) throw new BridgeException("not_found", "Recipe alternative is unavailable.");
            return ((ItemStack[]) items)[alternative];
        }
        throw new BridgeException("invalid_args", "source must be registry, inventory, container or recipe.");
    }
    private static void sourceArgs(JsonObject args, String... allowed) {
        for (String key : new String[] {"registryName", "metadata", "slot", "recipeIndex", "group", "position", "alternative"}) {
            boolean found = false; for (String match : allowed) if (match.equals(key)) found = true;
            if (args.has(key) && !found) throw new BridgeException("invalid_args", key + " does not belong to this item source.");
        }
    }
}
