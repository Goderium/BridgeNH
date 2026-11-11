package com.Goderium.BridgeNH.util;

import com.Goderium.BridgeNH.common.machine.BasicMachineFluidAdapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

public class JsonData {
    public static String string(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            return element.getAsString();
        } catch (RuntimeException e) { throw new BridgeException("invalid_args", key + " must be a string."); }
    }

    public static int integer(JsonObject object, String key, int minimum, int maximum) {
        try {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int value = new BigDecimal(element.getAsString()).intValueExact();
            if (value < minimum || value > maximum) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw new BridgeException("invalid_args", key + " must be an integer from " + minimum + " to " + maximum + "."); }
    }

    public static double number(JsonObject object, String key, double minimum, double maximum) {
        try {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            double value = element.getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw new BridgeException("invalid_args", key + " must be a finite number from " + minimum + " to " + maximum + "."); }
    }

    public static String bounded(String value, int limit) {
        return value == null ? "" : value.length() <= limit ? value : value.substring(0, limit);
    }

    public static JsonElement item(ItemStack stack) {
        if (stack == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        result.addProperty("registryName", String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())));
        result.addProperty("displayName", bounded(stack.copy().getDisplayName(), 512));
        result.addProperty("count", stack.stackSize);
        result.addProperty("metadata", stack.getMetadata());
        result.addProperty("hasTag", stack.hasTagCompound());
        return result;
    }

    public static JsonObject inventory(IInventory inventory) {
        JsonObject result = new JsonObject();
        int total = inventory.getSizeInventory();
        result.addProperty("name", bounded(inventory.getInventoryName(), 512));
        result.addProperty("totalSlots", total);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(total, 256); i++) {
            JsonObject slot = new JsonObject();
            slot.addProperty("index", i);
            slot.add("item", item(inventory.getStackInSlot(i)));
            slots.add(slot);
        }
        result.add("slots", slots);
        result.addProperty("truncated", total > 256);
        return result;
    }

    public static JsonObject fluids(IFluidHandler handler) {
        FluidTankInfo[] infos = handler.getTankInfo(ForgeDirection.UNKNOWN);
        return fluids(handler, infos == null ? new FluidTankInfo[0] : infos);
    }

    /** Already captured native tank data; the caller retains its availability/coverage proof. */
    public static JsonObject fluids(IFluidHandler handler, FluidTankInfo[] infos) {
        if (infos == null) throw new BridgeException("inspection_failed", "Native fluid tank information is unavailable.");
        JsonObject result = new JsonObject();
        JsonArray tanks = new JsonArray();
        for (int i = 0; i < Math.min(infos.length, 32); i++) {
            if (infos[i] == null) continue;
            JsonObject tank = new JsonObject();
            tank.addProperty("index", i);
            tank.addProperty("capacity", infos[i].capacity);
            FluidStack fluid = infos[i].fluid;
            if (fluid != null && fluid.getFluid() != null) {
                tank.addProperty("fluid", fluid.getFluid().getName());
                tank.addProperty("amount", fluid.amount);
            }
            tanks.add(tank);
        }
        result.add("tanks", tanks);
        result.addProperty("totalTanks", infos.length);
        result.addProperty("truncated", infos.length > 32);
        result.add("namedTanks", BasicMachineFluidAdapter.read(handler));
        return result;
    }
}
