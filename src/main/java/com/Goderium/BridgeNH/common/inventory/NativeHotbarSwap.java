package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Ordinary native pickup gesture for wrapped player slots; never writes an inventory directly. */
public final class NativeHotbarSwap {
    public interface Input {
        boolean current();
        boolean canTake(Slot slot);
        void pickup(int slot);
    }

    public static boolean wrappedPlayerSlot(Slot slot, InventoryPlayer inventory) {
        if (slot == null || slot.inventory == inventory) return false;
        JsonObject binding = new JsonObject(); PlayerSlotBinding.describe(binding, slot, inventory);
        return binding.get("playerInventoryBindingVerified").getAsBoolean();
    }

    public static JsonObject perform(Container container, Slot target, int hotbarIndex, InventoryPlayer inventory, Input input) {
        if (inventory.getItemStack() != null) throw new BridgeException("invalid_state", "Pickup swap requires an empty real cursor; use swapStrategy=native for the original number-key path.");
        JsonObject targetBinding = new JsonObject(); PlayerSlotBinding.describe(targetBinding, target, inventory);
        if (!targetBinding.get("playerInventoryBindingVerified").getAsBoolean())
            throw new BridgeException("unsupported", "Pickup hotbar swap requires a uniquely verified actual player-inventory target; use swapStrategy=native for other slots.");
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
        bound(container, target); bound(container, hotbar);
        ItemStack a = copy(target.getStack()), b = copy(hotbar.getStack());
        JsonObject result = new JsonObject(); JsonArray steps = new JsonArray(); result.add("steps", steps);
        result.addProperty("nativeAction", "native_pickup_hotbar_swap"); result.addProperty("hotbarContainerSlot", hotbar.slotNumber);
        result.addProperty("serverConfirmed", false); result.addProperty("submitted", false);
        if (target == hotbar || ItemStack.areItemStacksEqual(a, b)) {
            result.addProperty("localSwapObserved", true); result.addProperty("completion", "The exact stacks already match; no input was required."); return result;
        }
        if (sameType(a, b)) throw new BridgeException("unsupported", "Pickup would merge these same-type stacks instead of swapping. Use swapStrategy=native for number-key semantics.");
        if (!input.canTake(target) || !input.canTake(hotbar) || !accepts(target, b) || !accepts(hotbar, a))
            throw new BridgeException("invalid_state", "The exact native pickup swap fails slot extraction, insertion or stack-limit checks; no input was sent.");
        Slot first = a == null ? hotbar : target, second = a == null ? target : hotbar;
        ItemStack heldFirst = a == null ? b : a, heldSecond = a == null ? a : b;
        if (!input.current()) throw new BridgeException("stale_window", "The current native container changed before pickup swap.");
        // Each next click depends on the actual previous prediction, never a timed blind script.
        Slot[] order = heldSecond == null ? new Slot[] {first, second} : new Slot[] {first, second, first};
        for (int i = 0; i < order.length; i++) {
            if (!input.current()) { result.addProperty("stoppedReason", "native_context_changed"); break; }
            try { bound(container, target); bound(container, hotbar); }
            catch (BridgeException error) {
                if (steps.size() == 0) throw error;
                result.addProperty("stoppedReason", "native_slot_binding_changed"); break;
            }
            result.addProperty("submitted", true);
            try { input.pickup(order[i].slotNumber); }
            catch (RuntimeException error) { result.addProperty("stoppedReason", "native_callback_failed_after_submission"); break; }
            JsonObject step = new JsonObject(); step.addProperty("slot", order[i].slotNumber);
            step.add("target", JsonData.item(target.getStack())); step.add("hotbar", JsonData.item(hotbar.getStack()));
            step.add("cursor", JsonData.item(inventory.getItemStack())); steps.add(step);
            boolean expected = i == 0 ? ItemStack.areItemStacksEqual(inventory.getItemStack(), heldFirst) && first.getStack() == null
                    && ItemStack.areItemStacksEqual(second.getStack(), heldSecond)
                : i == 1 ? ItemStack.areItemStacksEqual(inventory.getItemStack(), heldSecond) && first.getStack() == null
                    && ItemStack.areItemStacksEqual(second.getStack(), heldFirst)
                : inventory.getItemStack() == null && ItemStack.areItemStacksEqual(target.getStack(), b) && ItemStack.areItemStacksEqual(hotbar.getStack(), a);
            if (!expected) { result.addProperty("stoppedReason", "native_prediction_differed"); break; }
        }
        result.addProperty("localSwapObserved", input.current() && inventory.getItemStack() == null
                && ItemStack.areItemStacksEqual(target.getStack(), b) && ItemStack.areItemStacksEqual(hotbar.getStack(), a));
        result.addProperty("completion", "Native pickup prediction only; confirm both exact stacks after server sync. A partial gesture is never retried or rolled back automatically; retain and inspect the real cursor.");
        return result;
    }

    private static void bound(Container container, Slot slot) {
        if (slot == null || slot.slotNumber < 0 || slot.slotNumber >= container.inventorySlots.size()
                || container.inventorySlots.get(slot.slotNumber) != slot)
            throw new BridgeException("stale_window", "The exact native slot binding changed.");
    }
    private static boolean accepts(Slot slot, ItemStack item) {
        return item == null || item.stackSize > 0 && item.stackSize <= item.getMaxStackSize()
                && item.stackSize <= slot.getSlotStackLimit() && slot.isItemValid(item.copy());
    }
    private static boolean sameType(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata() && ItemStack.areItemStackTagsEqual(a, b);
    }
    private static ItemStack copy(ItemStack stack) { return stack == null ? null : stack.copy(); }
}
