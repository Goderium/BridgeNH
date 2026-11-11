package com.Goderium.BridgeNH.common.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentLimits;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import net.minecraftforge.common.util.ForgeDirection;

public class StructureAlignmentFramesSelfTest {
    public static class Owner implements IAlignment {
        public ExtendedFacing getExtendedFacing() { return ExtendedFacing.DEFAULT; }
        public void setExtendedFacing(ExtendedFacing ignored) { throw new AssertionError("Read-only frame enumeration must never change alignment"); }
        public IAlignmentLimits getAlignmentLimits() { return (direction, rotation, flip) -> direction == ForgeDirection.UP; }
    }
    public static int run() {
        Owner owner = new Owner();
        JsonObject result = StructureAlignmentFrames.read(owner, owner.getExtendedFacing());
        JsonArray frames = result.getAsJsonArray("frames");
        check(frames.size() == ExtendedFacing.STATES_COUNT, "Every actual public enum state is covered");
        int checks = 1;
        for (JsonElement element : frames) {
            JsonObject row = element.getAsJsonObject();
            JsonArray matrix = row.getAsJsonArray("axisTransform");
            for (int axis = 0; axis < 3; axis++) {
                int r = 0, c = 0;
                for (int column = 0; column < 3; column++) {
                    r += Math.abs(matrix.get(axis).getAsJsonArray().get(column).getAsInt());
                    c += Math.abs(matrix.get(column).getAsJsonArray().get(axis).getAsInt());
                }
                check(r == 1 && c == 1, "Native enum yields signed orthonormal axes"); checks++;
            }
            check(row.get("sourceControllerAlignmentAllowed").getAsBoolean()
                == "UP".equals(row.getAsJsonObject("alignment").get("direction").getAsString()), "Public lambda limits queried through their interface");
            checks++;
        }
        check(result.get("readOnly").getAsBoolean() && !result.get("worldEffectsDispatched").getAsBoolean(), "Explicit read-only effect contract");
        return checks + 1;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
