package com.Goderium.BridgeNH.common.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.common.util.ForgeDirection;

public final class MachineEnergySelfTest {
    public static int run() {
        int checks = 0; Base base = new Base(); Modes meta = new Modes(); JsonObject data = MachineEnergyAdapter.read(base, meta);
        check(data.getAsJsonObject("facing").get("side").getAsInt() == 2 && data.getAsJsonObject("facing").get("direction").getAsString().equals("NORTH"), "facing comes from the public live getter, not placement guesses"); checks++;
        JsonArray sides = data.getAsJsonArray("energySides"); check(sides.size() == 6 && base.calls == 12, "six standard side gates read once each through bounded public APIs"); checks++;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            JsonObject entry = sides.get(side.ordinal()).getAsJsonObject();
            check(entry.get("side").getAsInt() == side.ordinal() && entry.get("offsetX").getAsInt() == side.offsetX
                    && entry.get("offsetY").getAsInt() == side.offsetY && entry.get("offsetZ").getAsInt() == side.offsetZ
                    && entry.get("front").getAsBoolean() == (side == ForgeDirection.NORTH), "reported side directions preserve Forge mapping and observed front"); checks++;
            check(entry.getAsJsonObject("energyInput").get("value").getAsBoolean() == (side == ForgeDirection.WEST)
                    && entry.getAsJsonObject("energyOutput").get("value").getAsBoolean() == (side == ForgeDirection.EAST), "standard public gates are independent of the facing and retain exact permissions"); checks++;
        }
        check(!data.getAsJsonObject("producing").get("value").getAsBoolean() && data.getAsJsonObject("laser").get("value").getAsBoolean(), "optional public producing/laser getters work without any machine class check"); checks++;
        JsonObject missing = MachineEnergyAdapter.read(new Object(), new Object());
        check(!missing.getAsJsonObject("facing").get("available").getAsBoolean() && !missing.getAsJsonObject("producing").get("available").getAsBoolean()
                && !missing.getAsJsonObject("laser").get("available").getAsBoolean(), "missing public getters are explicit unavailable states, never false or inferred"); checks++;
        check(!missing.getAsJsonArray("energySides").get(0).getAsJsonObject().getAsJsonObject("energyInput").get("available").getAsBoolean(), "missing side APIs cannot invent an input or output side"); checks++;
        base.front = ForgeDirection.UNKNOWN; data = MachineEnergyAdapter.read(base, meta);
        check(!data.getAsJsonObject("facing").get("available").getAsBoolean() && !data.getAsJsonArray("energySides").get(0).getAsJsonObject().has("front"), "UNKNOWN facing cannot masquerade as a cardinal direction"); checks++;
        return checks;
    }
    public static class Base {
        public ForgeDirection front = ForgeDirection.NORTH; public int calls;
        public ForgeDirection getFrontFacing() { return front; }
        public boolean inputEnergyFrom(ForgeDirection side, boolean waitForActive) { calls++; if (waitForActive) throw new AssertionError("Cached active sides must not be used"); return side == ForgeDirection.WEST; }
        public boolean outputsEnergyTo(ForgeDirection side, boolean waitForActive) { calls++; if (waitForActive) throw new AssertionError("Cached active sides must not be used"); return side == ForgeDirection.EAST; }
        private boolean isEnergyInputSide(ForgeDirection side) { throw new AssertionError("Private gate access forbidden"); }
        private boolean isEnergyOutputSide(ForgeDirection side) { throw new AssertionError("Private gate access forbidden"); }
    }
    public static class Modes { public boolean isProducing() { return false; } public boolean isLASER() { return true; } }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
