package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.ae.AeDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Restriction labels, native enablement and actual candidate acceptance are separate evidence. */
public final class SlotAcceptance {
    public static void describe(JsonObject state, Slot slot, ItemStack candidate) {
        if (!ExpandedGuiApi.is(slot, "appeng.container.slot.AppEngSlot")) return;
        JsonObject result = new JsonObject(); result.addProperty("source", "public_native_ae_slot_api");
        try {
            boolean enabled = Boolean.TRUE.equals(ExpandedGuiApi.call(slot, "isEnabled"));
            result.addProperty("enabled", enabled); result.addProperty("candidateObserved", candidate != null);
            result.add("candidate", candidate == null ? JsonNull.INSTANCE : AeDataAdapter.actualItem(candidate));
            result.add("acceptsCandidate", candidate == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(enabled && slot.isItemValid(candidate.copy())));
            result.addProperty("serverAcceptanceStillRequired", true);
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("available", false); result.addProperty("complete", false);
            result.addProperty("reason", error instanceof BridgeException ? ((BridgeException) error).code : error.getClass().getSimpleName());
        }
        state.add("slotAcceptance", result);
    }
    public static void preflight(Slot slot, ItemStack cursor, int mode) {
        if (slot == null || !ExpandedGuiApi.is(slot, "appeng.container.slot.AppEngSlot")) return;
        if (!Boolean.TRUE.equals(ExpandedGuiApi.call(slot, "isEnabled")))
            throw new BridgeException("invalid_state", "The actual AE slot is disabled; no click was submitted and the cursor is retained.");
        // Vanilla allows taking matching output onto the cursor even when insertion is forbidden.
        // Reject only an empty destination, where a nonempty cursor unambiguously requests insertion.
        if (mode == 0 && cursor != null && !slot.getHasStack() && !slot.isItemValid(cursor.copy()))
            throw new BridgeException("invalid_state", "The actual AE slot rejects this exact cursor item/NBT; no click was submitted.");
    }
}
