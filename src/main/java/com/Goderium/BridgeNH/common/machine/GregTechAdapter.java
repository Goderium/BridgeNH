package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.Loader;
import java.lang.reflect.Method;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;

/** Optional public-method adapter: no GregTech classes are linked into this mod. */
public class GregTechAdapter {
    public static JsonObject inspect(TileEntity tile) {
        return read(tile, true);
    }

    /** Cheap identity for loaded-tile discovery, without inventory or recipe inspection. */
    public static JsonObject identity(TileEntity tile) {
        Class<?> base = gregTechBase(tile);
        if (base == null) return null;
        if (!Loader.isModLoaded("gregtech")) return null;
        try {
            Object meta = base.getMethod("getMetaTileEntity").invoke(tile);
            if (meta == null) return null;
            return identity(tile, meta);
        } catch (ReflectiveOperationException | RuntimeException error) {
            JsonObject result = new JsonObject();
            result.addProperty("adapterError", error.getClass().getSimpleName());
            return result;
        }
    }

    /** Bounded machine state for discovery pages; deliberately excludes getInfoData. */
    public static JsonObject summary(TileEntity tile) {
        return read(tile, false);
    }

    private static JsonObject read(TileEntity tile, boolean includeInfo) {
        Class<?> base = gregTechBase(tile);
        if (base == null) return null;
        if (!Loader.isModLoaded("gregtech")) return null;
        try {
            Method getMeta = base.getMethod("getMetaTileEntity");
            Object meta = getMeta.invoke(tile);
            if (meta == null) return null;
            JsonObject result = identity(tile, meta);
            result.add("orientation", MachineEnergyAdapter.read(tile, meta));
            collect(result, tile, "getProgress", "progress");
            collect(result, tile, "getMaxProgress", "maxProgress");
            collect(result, tile, "isActive", "active");
            collect(result, tile, "isAllowedToWork", "allowedToWork");
            collect(result, tile, "getStoredEU", "storedEU");
            collect(result, tile, "getEUCapacity", "euCapacity");
            collect(result, tile, "getInputVoltage", "inputVoltage");
            collect(result, tile, "getOutputVoltage", "outputVoltage");
            if (includeInfo) collect(result, tile, "getInfoData", "info");
            collect(result, meta, "getErrorDisplayID", "structureErrorBits");
            collect(result, meta, "getEUtForDamageCalc", "signedCalculatedEUt");
            if (result.has("signedCalculatedEUt")) result.addProperty("eutMeaning",
                    "Current controller's signed, already calculated EU/t; negative consumes, positive generates. This is not the recipe's raw EU/t");
            collect(result, meta, "getMachineMode", "machineMode");
            collect(result, meta, "getMachineModeName", "machineModeName");
            collect(result, meta, "getMaxParallelRecipes", "maxParallelRecipes");
            collect(result, meta, "getTotalPowerTier", "totalPowerTier");
            collect(result, meta, "getDominantInputVoltageTier", "dominantInputVoltageTier");
            try {
                Object recipeResult = meta.getClass().getMethod("getCheckRecipeResult").invoke(meta);
                if (recipeResult != null) {
                    collect(result, recipeResult, "getID", "recipeResultId");
                    collect(result, recipeResult, "getDisplayString", "recipeResultMessage");
                }
            } catch (ReflectiveOperationException ignored) { }
            return result;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            JsonObject result = new JsonObject();
            result.addProperty("adapterError", e.getClass().getSimpleName());
            return result;
        }
    }

    private static JsonObject identity(TileEntity tile, Object meta) {
        JsonObject result = new JsonObject();
        result.addProperty("metaClass", meta.getClass().getName());
        try { result.addProperty("metaTileId", (Number) gregTechBase(tile).getMethod("getMetaTileID").invoke(tile)); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
        if (tile instanceof IInventory) {
            try { result.addProperty("displayName", JsonData.bounded(((IInventory) tile).getInventoryName(), 512)); }
            catch (RuntimeException ignored) { }
        }
        return result;
    }

    private static Class<?> gregTechBase(TileEntity tile) {
        for (Class<?> type = tile == null ? null : tile.getClass(); type != null; type = type.getSuperclass()) {
            if ("gregtech.api.metatileentity.BaseMetaTileEntity".equals(type.getName())
                    || "gregtech.api.metatileentity.BaseMetaPipeEntity".equals(type.getName())) return type;
        }
        return null;
    }

    private static void collect(JsonObject result, Object target, String method, String key) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            if (value instanceof Number) result.addProperty(key, (Number) value);
            else if (value instanceof Boolean) result.addProperty(key, (Boolean) value);
            else if (value instanceof String) result.addProperty(key, JsonData.bounded((String) value, 1024));
            else if (value instanceof String[]) {
                String[] lines = (String[]) value;
                JsonArray data = new JsonArray();
                for (int i = 0; i < Math.min(lines.length, 64); i++) data.add(new com.google.gson.JsonPrimitive(JsonData.bounded(lines[i], 1024)));
                result.add(key, data);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
