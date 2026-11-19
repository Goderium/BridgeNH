package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonObject;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;

/** Ordinary container slots: public virtual inventory identity, never wrapper-index arithmetic. */
public final class PlayerSlotBinding {
    public static Slot requireHotbar(Container container, InventoryPlayer inventory, int hotbarIndex) {
        if (container.inventorySlots.size() > 4096 || hotbarIndex < 0 || hotbarIndex > 8)
            throw new BridgeException("target_unverified", "The bounded native hotbar binding is unavailable.");
        Slot hotbar = null;
        for (Object value : container.inventorySlots) {
            Slot slot = (Slot) value;
            if (slot.isHere(inventory, hotbarIndex)) {
                if (hotbar != null) throw new BridgeException("target_unverified", "The actual hotbar slot has multiple native bindings.");
                hotbar = slot;
            }
        }
        if (hotbar == null || !ItemStack.areItemStacksEqual(hotbar.getStack(), inventory.getStackInSlot(hotbarIndex)))
            throw new BridgeException("target_unverified", "The actual hotbar slot cannot be verified.");
        if (hotbar.slotNumber < 0 || hotbar.slotNumber >= container.inventorySlots.size()
                || container.inventorySlots.get(hotbar.slotNumber) != hotbar)
            throw new BridgeException("stale_window", "The native hotbar slot binding changed.");
        return hotbar;
    }

    public static void describe(JsonObject entry, Slot slot, InventoryPlayer inventory) {
        entry.addProperty("inventoryIndexScope", "slot_local_index");
        entry.addProperty("playerInventoryBindingVerified", false);
        entry.addProperty("inventorySource", "unavailable"); entry.addProperty("inventoryBinding", "unavailable");
        int matched = -1;
        if (inventory != null) {
            try {
                for (int index = 0; index < 36; index++) if (slot.isHere(inventory, index)) {
                    if (matched != -1) { entry.addProperty("inventoryBindingReason", "ambiguous_player_inventory_binding"); return; }
                    matched = index;
                }
            } catch (RuntimeException | LinkageError error) { entry.addProperty("inventoryBindingReason", "public_player_inventory_binding_unavailable"); return; }
        }
        if (matched == -1) { entry.addProperty("inventoryBindingReason", "public_player_inventory_binding_unavailable"); return; }
        entry.addProperty("playerInventoryIndex", matched); entry.addProperty("nativeContainerSlotId", slot.slotNumber);
        entry.addProperty("playerInventoryBindingVerified", true); entry.addProperty("inventorySource", "current_player_inventory");
        entry.addProperty("inventoryBinding", "public_isHere_unique_actual_player_inventory_slot");
    }
}
