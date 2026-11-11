package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.AxisAlignedBB;

/** Bounded underfoot placement geometry; normal native input performs the actual jump/use. */
public final class JumpPlacement {
    public static final int VERSION = 1, INPUT_TICKS = 6, SNEAK_PREPARE_TICKS = 40;
    public static JsonObject arguments(JsonObject input) {
        ExpandedGuiApi.keys(input, "x", "y", "z", "dimension", "expectedWorld", "hotbarSlot", "replace", "jumpUnderfoot", "attachmentBinding");
        if (!GuiDataAdapter.booleanArg(input, "jumpUnderfoot", false) || GuiDataAdapter.booleanArg(input, "replace", false))
            throw new BridgeException("invalid_args", "Underfoot jump placement requires jumpUnderfoot=true and an empty target.");
        JsonObject result = new JsonParser().parse(input.toString()).getAsJsonObject(); result.remove("jumpUnderfoot"); return result;
    }
    public static void pose(AxisAlignedBB body, int x, int y, int z, boolean grounded, boolean flying, double motionX, double motionZ) {
        body = NativePlacementAdapter.actorBody(body);
        if (!grounded || flying || Math.abs(body.minY-y) > .0001 || body.minX < x+.01 || body.maxX > x+.99
                || body.minZ < z+.01 || body.maxZ > z+.99 || body.maxY-body.minY > 2.1
                || !Double.isFinite(motionX) || !Double.isFinite(motionZ) || Math.abs(motionX) > .001 || Math.abs(motionZ) > .001)
            throw new BridgeException("invalid_state", "Underfoot placement requires a stationary grounded survival body entirely inside the declared empty cell.");
    }
    public static AxisAlignedBB envelope(AxisAlignedBB body) {
        body = NativePlacementAdapter.actorBody(body);
        return AxisAlignedBB.getBoundingBox(body.minX, body.minY+.00001, body.minZ, body.maxX, body.maxY+1.5, body.maxZ);
    }
    public static boolean landed(AxisAlignedBB body, int x, int y, int z, boolean ground, boolean flying) {
        body = NativePlacementAdapter.actorBody(body);
        return ground && !flying && Math.abs(body.minY-y-1) <= .0001
                && body.minX >= x+.01 && body.maxX <= x+.99 && body.minZ >= z+.01 && body.maxZ <= z+.99;
    }
}
