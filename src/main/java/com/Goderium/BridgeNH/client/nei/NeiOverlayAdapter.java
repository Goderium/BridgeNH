package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

/** Resolve the actual visible recipe control; no saved pixel coordinates or fabricated overlay handler. */
public final class NeiOverlayAdapter {
    public static String widgetOwner(Object widget) {
        for (String owner : new String[] {"codechicken.nei.recipe.widget.RecipeWidget", "codechicken.nei.recipe.NEIRecipeWidget"})
            if (NeiUiAccess.is(widget, owner)) return owner;
        return null;
    }
    public static Object findWidget(Object screen, int recipe) {
        Object container = NeiUiAccess.get(screen.getClass().getClassLoader(), "codechicken.nei.recipe.GuiRecipe", screen, "container");
        Object widgets = ExpandedGuiApi.call(container, "getWidgets"), selected = null;
        if (!(widgets instanceof List<?>) || ((List<?>) widgets).size() > 128) throw new BridgeException("unsupported", "Bounded current recipe widgets unavailable.");
        Object handler = ExpandedGuiApi.call(screen, "getHandler");
        for (Object widget : (List<?>) widgets) if (widgetOwner(widget) != null) {
            Object ref = ExpandedGuiApi.call(widget, "getRecipeHandlerRef"); ClassLoader loader = widget.getClass().getClassLoader();
            if (((Number) NeiUiAccess.publicField(loader, "codechicken.nei.recipe.RecipeHandlerRef", ref, "recipeIndex")).intValue() != recipe
                    || NeiUiAccess.publicField(loader, "codechicken.nei.recipe.RecipeHandlerRef", ref, "handler") != handler) continue;
            if (selected != null) throw new BridgeException("ambiguous_widget", "Multiple visible widgets match the current handler and original recipeIndex.");
            selected = widget;
        }
        if (selected == null) throw new BridgeException("not_found", "No visible native widget matches the current handler and original recipeIndex.");
        return selected;
    }
    static Object findButton(Object screen, int recipe) {
        Object widget = findWidget(screen, recipe), buttons = ExpandedGuiApi.call(widget, "getRecipeButtons"), selected = null;
        if (!(buttons instanceof List<?>) || ((List<?>) buttons).size() > 32) throw new BridgeException("unsupported", "Bounded current native recipe buttons unavailable.");
        Object handler = ExpandedGuiApi.call(screen, "getHandler");
        for (Object button : (List<?>) buttons) if (NeiUiAccess.is(button, "codechicken.nei.recipe.GuiOverlayButton") && button instanceof GuiButton) {
            Object ref = NeiUiAccess.publicField(button.getClass().getClassLoader(), "codechicken.nei.recipe.GuiRecipeButton", button, "handlerRef");
            if (describe(button).get("recipeIndex").getAsInt() != recipe || NeiUiAccess.publicField(button.getClass().getClassLoader(), "codechicken.nei.recipe.RecipeHandlerRef", ref, "handler") != handler) continue;
            if (selected != null) throw new BridgeException("ambiguous_widget", "Multiple native overlay buttons match this recipe.");
            selected = button;
        }
        if (selected == null) throw new BridgeException("not_found", "No actual native overlay button exists for this recipe.");
        return selected;
    }
    static JsonObject bookmark(Object screen, int recipe, boolean saveCount) {
        Object button = findButton(screen, recipe);
        if (!((GuiButton) button).visible) throw new BridgeException("not_found", "The actual recipe button is not visible.");
        Object nativeRecipe = ExpandedGuiApi.call(button, "getRecipe"), recipeId = ExpandedGuiApi.call(nativeRecipe, "getRecipeId");
        ClassLoader loader = screen.getClass().getClassLoader();
        Object panel = NeiUiAccess.publicField(loader, "codechicken.nei.ItemPanels", null, "bookmarkPanel");
        if (panel == null) throw new BridgeException("unsupported", "Native bookmark panel is not initialized.");
        Object grid = ExpandedGuiApi.call(panel, "getGrid");
        int group = ((Number) NeiUiAccess.publicField(loader, "codechicken.nei.bookmark.BookmarkGrid", null, "DEFAULT_GROUP_ID")).intValue();
        int count = ((Number) ExpandedGuiApi.call(grid, "size")).intValue();
        if (count < 0 || count > 4096) throw new BridgeException("unsupported", "Current bookmark namespace exceeds bounded verification.");
        boolean added = Boolean.TRUE.equals(ExpandedGuiApi.call(panel, "addRecipe", nativeRecipe, saveCount ? 1 : 0, group));
        JsonObject out = new JsonObject(); out.addProperty("action", "bookmark"); out.addProperty("recipeIndex", recipe);
        out.addProperty("added", added); out.addProperty("saveCount", saveCount); out.addProperty("groupId", group);
        out.add("recipeId", NeiViewAdapter.recipeId(recipeId, loader));
        JsonArray saved = new JsonArray(); int matched = 0;
        count = ((Number) ExpandedGuiApi.call(grid, "size")).intValue();
        if (count >= 0 && count <= 4096) {
            for (int i = 0; i < count; i++) {
                Object item = ExpandedGuiApi.call(grid, "getBookmarkItem", i);
                if (item == null || !recipeId.equals(NeiUiAccess.publicField(loader, "codechicken.nei.bookmark.BookmarkItem", item, "recipeId"))
                        || ((Number) NeiUiAccess.publicField(loader, "codechicken.nei.bookmark.BookmarkItem", item, "groupId")).intValue() != group) continue;
                matched++;
                if (saved.size() >= 128) continue;
                JsonObject entry = new JsonObject(); entry.addProperty("itemIndex", i);
                entry.add("item", GTRecipeAdapter.value(NeiUiAccess.publicField(loader, "codechicken.nei.bookmark.BookmarkItem", item, "itemStack"), 0));
                for (String getter : new String[] {"getAmount", "getMultiplier", "getStackSize"}) entry.add(getter, GTRecipeAdapter.value(ExpandedGuiApi.call(item, getter), 0));
                entry.add("type", GTRecipeAdapter.value(NeiUiAccess.publicField(loader, "codechicken.nei.bookmark.BookmarkItem", item, "type"), 0)); saved.add(entry);
            }
        }
        out.add("savedItems", saved); out.addProperty("savedItemCount", matched); out.addProperty("savedItemsComplete", matched > 0 && matched == saved.size());
        out.addProperty("automaticReplayAllowed", false); out.addProperty("acceptance", "Actual active-namespace bookmark entries and quantities, not a hotkey receipt. Existing recipes are retained, never toggled off; read panel/bookmarks for full NBT or larger recipes.");
        return out;
    }
    static JsonArray inspect(Object screen) {
        JsonArray result = new JsonArray();
        Object container = NeiUiAccess.get(screen.getClass().getClassLoader(), "codechicken.nei.recipe.GuiRecipe", screen, "container");
        Object widgets = ExpandedGuiApi.call(container, "getWidgets");
        if (!(widgets instanceof List<?>) || ((List<?>) widgets).size() > 128)
            throw new BridgeException("unsupported", "Bounded current NEI recipe widgets are unavailable.");
        for (Object widget : (List<?>) widgets) {
            if (widgetOwner(widget) == null) continue;
            Object buttons = ExpandedGuiApi.call(widget, "getRecipeButtons");
            if (!(buttons instanceof List<?>) || ((List<?>) buttons).size() > 32)
                throw new BridgeException("unsupported", "Bounded original NEI recipe buttons are unavailable.");
            for (Object button : (List<?>) buttons) if (ExpandedGuiApi.is(button, "codechicken.nei.recipe.GuiOverlayButton") && button instanceof GuiButton) {
                GuiButton nativeButton = (GuiButton) button; JsonObject entry = describe(button);
                entry.addProperty("enabled", nativeButton.enabled); entry.addProperty("visible", nativeButton.visible);
                entry.addProperty("x", nativeButton.xPosition); entry.addProperty("y", nativeButton.yPosition);
                entry.addProperty("width", nativeButton.width); entry.addProperty("height", nativeButton.height);
                result.add(entry);
            }
        }
        return result;
    }
    static JsonObject describe(Object button) {
        Object ref = NeiUiAccess.publicField(button.getClass().getClassLoader(), "codechicken.nei.recipe.GuiRecipeButton", button, "handlerRef");
        Object index = NeiUiAccess.publicField(button.getClass().getClassLoader(), "codechicken.nei.recipe.RecipeHandlerRef", ref, "recipeIndex");
        JsonObject result = new JsonObject(); result.addProperty("recipeIndex", ((Number) index).intValue()); result.addProperty("action", "overlay");
        result.addProperty("canFillCraftingGrid", Boolean.TRUE.equals(ExpandedGuiApi.call(button, "canFillCraftingGrid")));
        result.addProperty("hasOverlay", Boolean.TRUE.equals(ExpandedGuiApi.call(button, "hasOverlay")));
        result.addProperty("requiresShift", Boolean.TRUE.equals(ExpandedGuiApi.call(button, "requireShiftForOverlayRecipe")));
        return result;
    }
    static JsonObject perform(Minecraft client, Object screen, int recipe, boolean shift) {
        if (client.currentScreen != screen) throw new BridgeException("stale_screen", "The NEI screen changed; no overlay was submitted.");
        Object selected = findButton(screen, recipe);
        GuiButton button = (GuiButton) selected; JsonObject result = describe(selected);
        Object previous = NeiUiAccess.publicField(selected.getClass().getClassLoader(), "codechicken.nei.recipe.GuiOverlayButton", selected, "firstGui");
        boolean nativeTransfer = nativeTransferAvailable(selected, previous);
        result.addProperty("nativeTransferAvailable", nativeTransfer);
        if (!button.visible || !button.enabled || !nativeTransfer
                || result.get("requiresShift").getAsBoolean() && !shift)
            throw new BridgeException("unsupported", "This real recipe overlay cannot fill the current previous container with the declared modifier; no callback submitted.");
        Object first = ExpandedGuiApi.call(screen, "getFirstScreen");
        if (client.thePlayer == null || !(screen instanceof GuiContainer)
                || !nativeDestinationBound(first, previous, ((GuiContainer) screen).inventorySlots, client.thePlayer.openContainer))
            throw new BridgeException("stale_container", "The overlay destination is not this current NEI screen's native first container.");
        client.displayGuiScreen((GuiScreen) previous);
        if (client.currentScreen != previous || ((GuiContainer) previous).inventorySlots != client.thePlayer.openContainer)
            throw new BridgeException("stale_container", "Native return did not bind the destination container; the GUI changed but no recipe callback was submitted.");
        ExpandedGuiApi.call(selected, "overlayRecipe", shift);
        result.addProperty("submitted", true); result.addProperty("shift", shift); result.addProperty("outcomeUnknown", true);
        result.addProperty("automaticReplayAllowed", false); result.addProperty("destinationWindowId", client.thePlayer.openContainer.windowId);
        result.addProperty("acceptance", "Read the actual synchronized pattern inputs/outputs; native overlay submission alone is not acceptance."); return result;
    }
    static boolean nativeTransferAvailable(Object button, Object previous) {
        if (!(previous instanceof GuiContainer)) return false;
        Object ref = NeiUiAccess.publicField(button.getClass().getClassLoader(), "codechicken.nei.recipe.GuiRecipeButton", button, "handlerRef");
        // The button's public getter also rejects missing physical ingredients. Native clicks
        // still allow a supported handler (e.g. a pattern terminal) to transfer the recipe.
        return Boolean.TRUE.equals(ExpandedGuiApi.call(ref, "canFillCraftingGrid", previous));
    }
    static boolean nativeDestinationBound(Object first, Object previous, Object screenContainer, Object playerContainer) {
        // GuiRecipe owns an EmptyContainer while open; its saved first GUI is rebound by
        // normal displayGuiScreen, just as GuiOverlayButton.mouseReleased does.
        return previous instanceof GuiContainer && ((GuiContainer) previous).inventorySlots != null
                && first == previous && screenContainer != null && screenContainer == playerContainer;
    }
}
