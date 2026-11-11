package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Independent display templates only. Never grants, picks up or consumes an owned ItemStack. */
public final class GhostItemSource {
    public static ItemStack resolve(Object screen, JsonObject source, ItemStack[] inventory) {
        int sources = (source.has("registryName") ? 1 : 0) + (source.has("playerSlot") ? 1 : 0)
                + (source.has("fluidName") ? 1 : 0) + (source.has("neiItemId") ? 1 : 0);
        if (sources != 1) throw new BridgeException("invalid_args", "Choose one registryName, playerSlot, fluidName or observed neiItemId ghost source.");
        String[] keys = source.has("registryName") ? new String[] {"registryName", "metadata", "count"}
                : source.has("playerSlot") ? new String[] {"playerSlot", "count"}
                : source.has("neiItemId") ? new String[] {"neiItemId", "count"} : new String[] {"fluidName", "amount"};
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            boolean allowed = false; for (String key : keys) if (key.equals(entry.getKey())) allowed = true;
            if (!allowed) throw new BridgeException("invalid_args", "Unexpected ghost source argument: " + entry.getKey());
        }
        if (source.has("fluidName")) {
            String name = ExpandedGuiApi.string(source, "fluidName", 128);
            Fluid fluid = FluidRegistry.getFluid(name);
            if (name.isEmpty() || fluid == null || !name.equals(fluid.getName())) throw new BridgeException("not_found", "Registered fluid identity is unavailable.");
            int amount = source.has("amount") ? JsonData.integer(source, "amount", 1, Integer.MAX_VALUE) : 1000;
            Object raw = ExpandedGuiApi.call(ExpandedGuiApi.load(screen, "gregtech.api.util.GTUtility"), "getFluidDisplayStack", new FluidStack(fluid, amount), true);
            if (!(raw instanceof ItemStack) || ((ItemStack) raw).getItem() == null || ((ItemStack) raw).stackSize <= 0)
                throw new BridgeException("unsupported", "The public GT NEI fluid display API is unavailable; use an observed NEI fluid or a filled container's playerSlot instead.");
            return ((ItemStack) raw).copy();
        }
        ItemStack stack;
        if (source.has("neiItemId")) {
            stack = NeiViewAdapter.resolvePanelItem(screen, ExpandedGuiApi.string(source, "neiItemId", 128));
        } else if (source.has("playerSlot")) {
            int index = JsonData.integer(source, "playerSlot", 0, 35);
            if (inventory == null || index >= inventory.length) throw new BridgeException("target_unverified", "The actual player inventory source is unavailable.");
            stack = inventory[index];
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) throw new BridgeException("not_found", "Actual player source slot is empty.");
            stack = stack.copy();
        } else {
            String name = ExpandedGuiApi.string(source, "registryName", 256); Object raw = Item.itemRegistry.getObject(name);
            if (name.isEmpty() || !(raw instanceof Item) || !name.equals(Item.itemRegistry.getNameForObject(raw))) throw new BridgeException("not_found", "Registered item identity is unavailable.");
            stack = new ItemStack((Item) raw, 1, source.has("metadata") ? JsonData.integer(source, "metadata", 0, 32766) : 0);
        }
        stack.stackSize = source.has("count") ? JsonData.integer(source, "count", 1, Integer.MAX_VALUE) : 1;
        return stack;
    }
}
