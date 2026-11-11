package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.common.inventory.InventoryCapacity;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

/** NEI search, phantom-cursor release and explicit creative acquisition. */
public final class NeiItemControlAdapter {
    public static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args, boolean developer) {
        return perform(screen, args, developer, client.thePlayer.capabilities.isCreativeMode,
                screen instanceof GuiInventory || screen instanceof GuiContainerCreative,
                client.thePlayer.inventory.getItemStack(), client.thePlayer.inventory.mainInventory);
    }

    static JsonObject perform(Object screen, JsonObject args, boolean developer, boolean creative, boolean inventoryScreen, ItemStack cursor, ItemStack[] inventory) {
        String action = ExpandedGuiApi.option(args, "action", null, "search", "take", "release", "ghost_pick");
        JsonObject result = new JsonObject(); result.addProperty("action", action);
        if (action.equals("release")) {
            ExpandedGuiApi.keys(args);
            result.add("ghostRelease", NeiGhostDragAdapter.release(screen));
        } else if (action.equals("ghost_pick")) {
            ExpandedGuiApi.keys(args, "neiItemId");
            if (cursor != null) throw new BridgeException("invalid_state", "Native NEI phantom pickup requires an empty real cursor; no item is acquired or discarded.");
            GuiControlAdapter.neutralInput();
            result.add("ghostPickup", NeiViewAdapter.pickGhost(screen, ExpandedGuiApi.string(args, "neiItemId", 128)));
        } else if (action.equals("search")) {
            ExpandedGuiApi.keys(args, "text");
            String text = ExpandedGuiApi.string(args, "text", 1024);
            Object search = ExpandedGuiApi.field(null, ExpandedGuiApi.load(screen, "codechicken.nei.LayoutManager"), "searchField");
            if (search == null) throw new BridgeException("unsupported", "The native NEI search field is not initialized.");
            result.addProperty("before", String.valueOf(ExpandedGuiApi.call(search, "text")));
            // TextField.setText invokes the real SearchField.onTextChange/filter restart, even if a small GUI covers it.
            ExpandedGuiApi.call(search, "setText", text);
            result.addProperty("after", String.valueOf(ExpandedGuiApi.call(search, "text")));
            result.addProperty("filterCompletion", "pending; read the current filtered NEI panel before selecting its neiItemId");
            result.addProperty("nativeAction", "nei_search_field_setText");
        } else {
            ExpandedGuiApi.keys(args, "neiItemId", "count");
            if (!developer || !creative) throw new BridgeException("permission_denied", "NEI real-item acquisition requires creative mode and developer profile. Survival marking must use ghost templates, never give or take.");
            if (!inventoryScreen) throw new BridgeException("invalid_state", "Open the player inventory before NEI acquisition; other containers may redirect generated items.");
            if (screen instanceof GuiContainerCreative
                    && ((GuiContainerCreative) screen).func_147056_g() != CreativeTabs.tabInventory.getTabIndex())
                throw new BridgeException("invalid_state", "Select the creative player-inventory tab before NEI acquisition so native inventory packets can synchronize; no acquisition was submitted.");
            if (cursor != null) throw new BridgeException("invalid_state", "NEI acquisition requires an empty real cursor.");
            ItemStack stack = NeiViewAdapter.resolvePanelItem(screen, ExpandedGuiApi.string(args, "neiItemId", 128));
            stack.stackSize = args.has("count") ? JsonData.integer(args, "count", 1, 64) : 1;
            if (stack.stackSize > stack.getMaxStackSize()) throw new BridgeException("invalid_args", "Requested count exceeds the native item stack limit.");
            long before = count(inventory, stack);
            if (!fits(inventory, stack)) throw new BridgeException("invalid_state", "The requested real items do not fit in player inventory.");
            Class<?> config = ExpandedGuiApi.load(screen, "codechicken.nei.NEIClientConfig");
            if (!Boolean.TRUE.equals(ExpandedGuiApi.call(config, "canCheatItem", stack)))
                throw new BridgeException("permission_denied", "Native NEI item acquisition is disabled or the selected item is banned.");
            result.add("item", JsonData.item(stack)); result.addProperty("inventoryCountBefore", Long.toString(before));
            result.addProperty("observedMainInventorySlots", inventory.length);
            result.addProperty("capacitySlotsChecked", 36);
            // Normal finite NEI left-click branch, without Ctrl infinite handlers, OS hover or /give fallback.
            ExpandedGuiApi.call(ExpandedGuiApi.load(screen, "codechicken.nei.NEIClientUtils"), "cheatItem", stack.copy(), 0, 0);
            result.addProperty("nativeAction", "nei_cheatItem_finite_left_click");
            result.addProperty("serverConfirmed", false); result.addProperty("acquisitionVerified", false);
            result.addProperty("expectedInventoryDelta", stack.stackSize);
            result.addProperty("completion", "pending; verify the exact identity/NBT player-inventory increase on both sides. Never retry an unknown outcome. Manually remove temporary real marker items after use.");
        }
        result.addProperty("submitted", true); return result;
    }

    private static long count(ItemStack[] inventory, ItemStack target) {
        if (inventory == null || inventory.length < 36 || inventory.length > 4096) throw new BridgeException("target_unverified", "The bounded current player main inventory, including at least 36 ordinary storage slots, is required.");
        long count = 0; for (ItemStack item : inventory) if (same(item, target)) count += item.stackSize; return count;
    }

    private static boolean fits(ItemStack[] inventory, ItemStack target) {
        // NEI 2.8.155 canItemFitInInventory uses InventoryRange(player.inventory, 0, 36).
        // Backhand appends an offhand slot whose empty-slot pickup permission is configurable.
        // Count its actual contents above, but never mistake it for ordinary insertion capacity.
        return InventoryCapacity.fits(inventory, new ItemStack[] {target}, 64);
    }

    private static boolean same(ItemStack item, ItemStack target) {
        return item != null && item.getItem() == target.getItem() && item.getMetadata() == target.getMetadata()
                && ItemStack.areItemStackTagsEqual(item, target);
    }
}
