package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.common.util.ForgeDirection;

/** Fixed public GT getters shared by world and machine inspection; never rotates or changes energy state. */
final class MachineEnergyAdapter {
    static JsonObject read(Object base, Object meta) {
        JsonObject result = new JsonObject(); ForgeDirection front = null;
        try {
            Object value = AdapterAccess.call(base, "getFrontFacing");
            if (value instanceof ForgeDirection && value != ForgeDirection.UNKNOWN) front = (ForgeDirection) value;
        } catch (RuntimeException | LinkageError error) { /* Explicitly unavailable below. */ }
        result.add("facing", front == null ? unavailable("getFrontFacing", "public_front_facing_unavailable") : direction(front));
        JsonArray sides = new JsonArray();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            JsonObject entry = direction(side);
            if (front != null) entry.addProperty("front", side == front);
            entry.add("energyInput", energySide(base, "inputEnergyFrom", side));
            entry.add("energyOutput", energySide(base, "outputsEnergyTo", side)); sides.add(entry);
        }
        result.add("energySides", sides);
        result.addProperty("energySideApi", "Public inputEnergyFrom(ForgeDirection, false)/outputsEnergyTo(ForgeDirection, false); current standard side gates including covers and machine mode, without waiting for cached active-side flags.");
        result.add("producing", mode(meta, "isProducing")); result.add("laser", mode(meta, "isLASER"));
        result.addProperty("scope", "Live public getters only; side permission does not prove a connected producer/consumer or flowing energy. No rotation, topology scan, private state or energy mutation.");
        return result;
    }

    private static JsonObject direction(ForgeDirection side) {
        JsonObject result = new JsonObject(); result.addProperty("available", true);
        result.addProperty("side", side.ordinal()); result.addProperty("direction", side.name());
        result.addProperty("offsetX", side.offsetX); result.addProperty("offsetY", side.offsetY); result.addProperty("offsetZ", side.offsetZ);
        return result;
    }

    private static JsonObject energySide(Object base, String api, ForgeDirection side) {
        try {
            Object value = AdapterAccess.call(base, api, new Class<?>[] {ForgeDirection.class, boolean.class}, side, false);
            if (!(value instanceof Boolean)) return unavailable(api, "public_energy_side_getter_unavailable");
            JsonObject result = new JsonObject(); result.addProperty("available", true); result.addProperty("value", (Boolean) value); return result;
        } catch (RuntimeException | LinkageError error) { return unavailable(api, "public_energy_side_getter_unavailable"); }
    }

    private static JsonObject mode(Object meta, String api) {
        try {
            Object value = AdapterAccess.call(meta, api);
            if (!(value instanceof Boolean)) return unavailable(api, "public_mode_getter_unavailable");
            JsonObject result = new JsonObject(); result.addProperty("available", true); result.addProperty("value", (Boolean) value); result.addProperty("api", api); return result;
        } catch (RuntimeException | LinkageError error) { return unavailable(api, "public_mode_getter_unavailable"); }
    }

    private static JsonObject unavailable(String api, String reason) {
        JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("api", api); result.addProperty("reason", reason); return result;
    }
}
