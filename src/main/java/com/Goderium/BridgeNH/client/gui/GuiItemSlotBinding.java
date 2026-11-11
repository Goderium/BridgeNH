package com.Goderium.BridgeNH.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/** Public display-to-slot identity only. Machine ownership remains the separate handler proof. */
@com.google.common.annotations.VisibleForTesting
public final class GuiItemSlotBinding {
    @com.google.common.annotations.VisibleForTesting
    public static final String BINDING = "public_itemslot_actual_current_container_slot_identity";
    private static final String ITEM_SLOT = "com.cleanroommc.modularui.widgets.slot.ItemSlot";
    private static final String MODULAR_SLOT = "com.cleanroommc.modularui.widgets.slot.ModularSlot";
    private static final String CONTAINER = "com.cleanroommc.modularui.screen.ModularContainer";

    static void describe(Object widget, JsonObject entry, Object screen) {
        if (!ExpandedGuiApi.is(widget, ITEM_SLOT)) return;
        entry.add("itemSlot", read(widget, screen instanceof GuiContainer ? ((GuiContainer) screen).inventorySlots : null));
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object widget, Container container) {
        JsonObject result = new JsonObject(); result.addProperty("bindingVerified", false);
        result.addProperty("acceptanceAvailable", false);
        result.addProperty("acceptanceReason", "Native slot_click is required for candidate acceptance; no slot predicate or virtual insertion was executed.");
        if (!ExpandedGuiApi.is(widget, ITEM_SLOT) || !ExpandedGuiApi.is(container, CONTAINER))
            return unavailable(result, "public_current_itemslot_container_api_unavailable");
        if (container.inventorySlots.isEmpty() || container.inventorySlots.size() > 512)
            return unavailable(result, "native_container_slot_coverage_incomplete");
        try {
            Object actual = ExpandedGuiApi.call(widget, "getSlot");
            if (!(actual instanceof Slot) || !ExpandedGuiApi.is(actual, MODULAR_SLOT)
                    || ExpandedGuiApi.call(widget, "getVanillaSlot") != actual)
                return unavailable(result, "public_itemslot_slot_identity_unverified");
            Slot slot = (Slot) actual;
            int identities = 0, numbers = 0;
            for (Object member : container.inventorySlots) {
                if (!(member instanceof Slot) || !ExpandedGuiApi.is(member, MODULAR_SLOT))
                    return unavailable(result, "public_native_container_slot_coverage_unavailable");
                if (member == slot) identities++;
                if (((Slot) member).slotNumber == slot.slotNumber) numbers++;
            }
            if (identities != 1 || numbers != 1 || slot.slotNumber < 0
                    || ExpandedGuiApi.call(container, "getModularSlot", slot.slotNumber) != slot)
                return unavailable(result, "actual_current_container_slot_identity_unverified");
            result.addProperty("bindingVerified", true); result.addProperty("binding", BINDING);
            result.addProperty("nativeContainerSlotId", slot.slotNumber);
            result.addProperty("inventoryIndex", slot.getSlotIndex());
            result.addProperty("displaySource", "This same ItemSlot object's public tooltip and texture data; join nativeContainerSlotId to separately verified machine/player bindings.");
            flag(result, slot, "isCanPut", "canPut"); flag(result, slot, "isCanTake", "canTake"); flag(result, slot, "isPhantom", "phantom");
            try {
                result.addProperty("filterPresent", ExpandedGuiApi.call(slot, "getFilter") != null);
                result.addProperty("filterSource", "ModularSlot.getFilter presence only; predicate not executed or inspected.");
            } catch (RuntimeException | LinkageError error) { result.addProperty("filterUnavailable", true); }
            return result;
        } catch (RuntimeException | LinkageError error) { return unavailable(result, "public_itemslot_binding_api_unavailable"); }
    }

    private static void flag(JsonObject result, Object slot, String api, String name) {
        try {
            Object value = ExpandedGuiApi.call(slot, api);
            if (value instanceof Boolean) result.addProperty(name, (Boolean) value);
            else result.addProperty(name + "Unavailable", true);
        } catch (RuntimeException | LinkageError error) { result.addProperty(name + "Unavailable", true); }
    }

    private static JsonObject unavailable(JsonObject result, String reason) { result.addProperty("bindingReason", reason); return result; }
}
