package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.util.BridgeException;

import net.minecraft.item.ItemStack;

/** Shared finite supply/NEI capacity preflight; reserves copied ordinary slots only. */
public final class InventoryCapacity {
    public static boolean fits(ItemStack[] inventory, ItemStack[] requested, int inventoryLimit) {
        if (inventory == null || inventory.length < 36 || inventory.length > 4096 || inventoryLimit < 1)
            throw new BridgeException("target_unverified", "A bounded inventory with 36 ordinary storage slots is required.");
        ItemStack[] reserved = new ItemStack[36];
        for (int i = 0; i < reserved.length; i++) reserved[i] = inventory[i] == null ? null : inventory[i].copy();
        for (ItemStack target : requested) {
            if (target == null || target.stackSize <= 0) return false;
            int remaining = target.stackSize, limit = Math.min(inventoryLimit, target.getMaxStackSize());
            // Merge first, then reserve empty slots. Later identities cannot reuse the same free slot.
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < reserved.length && remaining > 0; i++) {
                    ItemStack item = reserved[i];
                    if (pass == 0 && (item == null || !target.isStackable() || item.getItem() != target.getItem()
                            || item.getMetadata() != target.getMetadata() || !ItemStack.areItemStackTagsEqual(item, target))) continue;
                    if (pass == 1 && item != null) continue;
                    int added = Math.min(remaining, Math.max(0, limit - (item == null ? 0 : item.stackSize)));
                    if (added > 0) {
                        if (item == null) { reserved[i] = target.copy(); reserved[i].stackSize = added; }
                        else item.stackSize += added;
                        remaining -= added;
                    }
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }
}
