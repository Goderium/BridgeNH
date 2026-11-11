package com.Goderium.BridgeNH.common.inventory;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;

public final class PlayerSlotBindingSelfTest {
    public static int run() {
        int checks = 0; InventoryPlayer actual = new InventoryPlayer(null), other = new InventoryPlayer(null);
        Slot ordinary = new Slot(actual, 34, 0, 0); ordinary.slotNumber = 71;
        JsonObject data = read(ordinary, actual);
        check(data.get("playerInventoryIndex").getAsInt() == 34 && data.get("nativeContainerSlotId").getAsInt() == 71, "Native slotNumber stays separate from the verified actual player index"); checks++;
        Slot ranged = new Slot(new InventoryBasic("dummy", false, 36), 2, 0, 0) {
            @Override public boolean isHere(IInventory inventory, int index) { return inventory == actual && index == 11; }
        };
        ranged.slotNumber = 6; data = read(ranged, actual);
        check(ranged.getSlotIndex() == 2 && data.get("playerInventoryIndex").getAsInt() == 11 && data.get("nativeContainerSlotId").getAsInt() == 6, "A ranged-handler predicate resolves its real offset without guessing from wrapper index2"); checks++;
        check(data.get("playerInventoryBindingVerified").getAsBoolean() && data.get("inventorySource").getAsString().equals("current_player_inventory"), "Ownership is published only after one exact actual-inventory match"); checks++;
        check(!read(ranged, other).has("playerInventoryIndex") && !read(ranged, null).has("playerInventoryIndex"), "A foreign or absent player inventory cannot produce a verified index"); checks++;
        Slot machine = new Slot(new InventoryBasic("machine", false, 36), 6, 0, 0);
        check(!read(machine, actual).has("playerInventoryIndex"), "A matching raw machine index is not player ownership"); checks++;
        Slot ambiguous = new Slot(actual, 0, 0, 0) { @Override public boolean isHere(IInventory inventory, int index) { return index == 1 || index == 34; } };
        data = read(ambiguous, actual);
        check(!data.get("playerInventoryBindingVerified").getAsBoolean() && !data.has("playerInventoryIndex") && data.get("inventoryBindingReason").getAsString().equals("ambiguous_player_inventory_binding"), "Multiple public matches are explicit unknown, never a first-match guess"); checks++;
        Slot failed = new Slot(actual, 0, 0, 0) { @Override public boolean isHere(IInventory inventory, int index) { if (index == 35) throw new IllegalStateException("Unavailable public binding"); return index == 1; } };
        check(!read(failed, actual).has("playerInventoryIndex"), "A later public predicate failure prevents an incomplete uniqueness claim"); checks++;
        check(!read(new Slot(actual, 39, 0, 0), actual).has("playerInventoryIndex"), "Only complete main-player indices0..35 are in this binding scope"); checks++;
        return checks;
    }
    private static JsonObject read(Slot slot, InventoryPlayer inventory) { JsonObject value = new JsonObject(); PlayerSlotBinding.describe(value, slot, inventory); return value; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
