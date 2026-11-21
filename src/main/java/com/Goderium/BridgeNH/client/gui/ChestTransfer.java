package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** One normal shift-click in the currently observed chest window. */
public final class ChestTransfer {
    private ChestTransfer() {}

    public static JsonObject move(Minecraft client, JsonObject args, String screenId) {
        Container open = client.thePlayer.openContainer;
        if (open == null || open.getClass() != ContainerChest.class || client.currentScreen == null
                || !screenId.equals(JsonData.string(args, "screenId"))
                || open.windowId != JsonData.integer(args, "windowId", 0, 255)
                || client.thePlayer.inventory.getItemStack() != null)
            throw new BridgeException("stale_window", "Expected the observed normal chest with an empty cursor.");
        int index = JsonData.integer(args, "slot", 0, open.inventorySlots.size() - 1);
        Slot slot = open.getSlot(index);
        ItemStack stack = slot.getStack();
        String direction = JsonData.string(args, "direction");
        if (!"deposit".equals(direction) && !"withdraw".equals(direction))
            throw new BridgeException("invalid_args", "Choose deposit or withdraw.");
        boolean deposit = "deposit".equals(direction);
        if (slot.slotNumber != index)
            throw new BridgeException("stale_slot", "Native slot ID " + slot.slotNumber + " differs from the observed slot " + index + ".");
        IInventory chest = ((ContainerChest) open).getLowerChestInventory();
        if (!slot.isHere(deposit ? client.thePlayer.inventory : chest, slot.getSlotIndex()))
            throw new BridgeException("stale_slot", "The observed slot belongs to a different inventory.");
        if (stack == null)
            throw new BridgeException("stale_slot", "The source slot is now empty.");
        String registry = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        if (!JsonData.string(args, "registryName").equals(registry) || JsonData.integer(args, "metadata", 0, 32767) != stack.getMetadata())
            throw new BridgeException("stale_slot", "Current source item is " + registry + " with metadata " + stack.getMetadata() + ".");
        if (JsonData.integer(args, "count", 1, 127) != stack.stackSize)
            throw new BridgeException("stale_slot", "Current source count is " + stack.stackSize + ".");
        // A modded client can report false here while the server accepts its normal shift-click.
        // windowClick preserves both the native client path and server-side slot validation.
        boolean clientCanTake = slot.canTakeStack(client.thePlayer);
        if (stack.hasTagCompound() ? !args.has("nbt") || !stack.getTagCompound().toString().equals(JsonData.string(args, "nbt")) : args.has("nbt"))
            throw new BridgeException("stale_slot", "Supply the complete observed source NBT for a tagged stack.");
        ItemStack identity = stack.copy();
        int[] before = totals(open, client, identity);
        client.playerController.windowClick(open.windowId, index, 0, 1, client.thePlayer);
        int[] after = totals(open, client, identity);
        int moved = deposit ? before[0] - after[0] : after[0] - before[0];
        int received = deposit ? after[1] - before[1] : before[1] - after[1];
        JsonObject result = new JsonObject();
        result.addProperty("source", "client_container");
        result.addProperty("submitted", true);
        result.addProperty("clientCanTakeBefore", clientCanTake);
        result.addProperty("serverConfirmed", false);
        result.addProperty("playerBefore", before[0]); result.addProperty("playerAfter", after[0]);
        result.addProperty("chestBefore", before[1]); result.addProperty("chestAfter", after[1]);
        boolean balanced = moved == received && moved >= 0 && moved <= identity.stackSize
                && client.thePlayer.inventory.getItemStack() == null;
        result.addProperty("conservedOnClient", balanced);
        if (balanced) result.addProperty("movedOnClient", moved);
        result.addProperty("status", balanced && moved == identity.stackSize ? "submitted" : "partial");
        return result;
    }

    private static int[] totals(Container window, Minecraft client, ItemStack match) {
        int[] result = new int[2];
        for (Object value : window.inventorySlots) {
            Slot slot = (Slot) value;
            ItemStack stack = slot.getStack();
            if (stack != null && stack.isItemEqual(match) && ItemStack.areItemStackTagsEqual(stack, match))
                result[slot.isHere(client.thePlayer.inventory, slot.getSlotIndex()) ? 0 : 1] += stack.stackSize;
        }
        return result;
    }
}
