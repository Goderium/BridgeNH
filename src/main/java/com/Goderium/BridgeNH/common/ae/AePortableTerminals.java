package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Item-host identity and exact native energy accounting, independent of terminal brand. */
final class AePortableTerminals {
    static boolean supports(Object target) {
        return ExpandedGuiApi.is(target, "appeng.api.implementations.guiobjects.IGuiItemObject")
            && ExpandedGuiApi.is(target, "appeng.container.interfaces.IInventorySlotAware");
    }

    static JsonObject owner(Object container, Object target) {
        Object raw = ExpandedGuiApi.call(container, "getPlayerInv");
        if (!(raw instanceof InventoryPlayer)) throw new BridgeException("unsupported", "Portable owner inventory unavailable.");
        InventoryPlayer inventory = (InventoryPlayer) raw;
        int slot = ((Number) ExpandedGuiApi.call(target, "getInventorySlot")).intValue();
        Object rawStack = ExpandedGuiApi.call(target, "getItemStack");
        if (slot < 0 || slot >= 36 || !(rawStack instanceof ItemStack) || inventory.player == null || inventory.player.worldObj == null)
            throw new BridgeException("owner_changed", "Portable host inventory binding unavailable (slot " + slot + ").");
        ItemStack hostStack = (ItemStack) rawStack, stack = inventory.getStackInSlot(slot);
        // Native terminal GUI objects retain their opening stack while slot sync,
        // charge, search/settings and pattern edits replace its mutable NBT. Owner
        // identity is the actual player slot, item and wireless network binding;
        // delivery separately proves every inventory tag and native energy debit.
        if (stack == null || stack.stackSize != 1 || hostStack.stackSize != 1 || stack.getItem() != hostStack.getItem()
                || stack.getMetadata() != hostStack.getMetadata())
            throw new BridgeException("owner_changed", "Portable host item changed in bound slot " + slot + ".");
        String networkKey = null;
        if (ExpandedGuiApi.is(stack.getItem(), "appeng.api.features.IWirelessTermHandler")) {
            networkKey = String.valueOf(ExpandedGuiApi.call(stack.getItem(), "getEncryptionKey", stack));
            if (!networkKey.equals(String.valueOf(ExpandedGuiApi.call(hostStack.getItem(), "getEncryptionKey", hostStack))))
                throw new BridgeException("owner_changed", "Portable host wireless binding changed.");
        }
        JsonObject result = new JsonObject(); result.addProperty("kind", "portable");
        result.addProperty("playerId", inventory.player.getUniqueID().toString());
        result.addProperty("dimension", inventory.player.worldObj.provider.dimensionId);
        result.addProperty("playerSlot", slot);
        result.addProperty("registryName", Item.itemRegistry.getNameForObject(stack.getItem()));
        result.addProperty("metadata", stack.getMetadata());
        if (networkKey != null) result.addProperty("networkKey", networkKey);
        return result;
    }

    /** Only a debit reproducible through the item's own public energy API is accepted. */
    static JsonObject energyDebit(ItemStack before, ItemStack after) {
        if (before == null || after == null || before.getItem() != after.getItem()
                || !ExpandedGuiApi.is(before.getItem(), "appeng.api.implementations.items.IAEItemPowerStorage")) return null;
        Object item = before.getItem();
        double oldPower = ((Number) ExpandedGuiApi.call(item, "getAECurrentPower", before)).doubleValue();
        double newPower = ((Number) ExpandedGuiApi.call(item, "getAECurrentPower", after)).doubleValue();
        if (!Double.isFinite(oldPower) || !Double.isFinite(newPower) || newPower < 0 || newPower >= oldPower) return null;
        ItemStack expected = before.copy();
        ExpandedGuiApi.call(item, "extractAEPower", expected, oldPower - newPower);
        if (!ItemStack.areItemStacksEqual(expected, after)) return null;
        JsonObject proof = new JsonObject(); proof.addProperty("beforeAE", oldPower); proof.addProperty("afterAE", newPower);
        proof.addProperty("debitedAE", oldPower - newPower); proof.addProperty("fullNbtMatchedNativeDebit", true); return proof;
    }
}
