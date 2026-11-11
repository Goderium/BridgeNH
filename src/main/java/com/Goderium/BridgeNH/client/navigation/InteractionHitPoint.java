package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Generic block-local native click constraint, shared by planning, arrival and use. */
public final class InteractionHitPoint {
    static final double TOLERANCE = .02;

    public static JsonObject requested(JsonObject args) {
        if (!args.has("hitPoint")) return null;
        if (!args.get("hitPoint").isJsonObject()) throw new BridgeException("invalid_args", "hitPoint requires local XYZ.");
        JsonObject value = args.getAsJsonObject("hitPoint"), result = new JsonObject();
        ExpandedGuiApi.keys(value, "x", "y", "z");
        for (String axis : new String[] {"x", "y", "z"}) result.addProperty(axis, JsonData.number(value, axis, 0, 1));
        return result;
    }

    public static MovingObjectPosition probe(NativePlacementAdapter.Access access, Vec3 eye, double reach,
            int x, int y, int z, int side, JsonObject point) {
        Vec3 aim = Vec3.createVectorHelper(x + point.get("x").getAsDouble(),
                y + point.get("y").getAsDouble(), z + point.get("z").getAsDouble());
        // Trace the real float view to native reach, including the point's surface.
        MovingObjectPosition hit = NativePlacementAdapter.nativeView(access, reach).trace(
                Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), aim);
        return matches(hit, x, y, z, side, point) && eye.distanceTo(hit.hitVec) <= reach ? hit : null;
    }

    @com.google.common.annotations.VisibleForTesting
    public static boolean matches(MovingObjectPosition hit, int x, int y, int z, int side, JsonObject point) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.hitVec == null
                || hit.blockX != x || hit.blockY != y || hit.blockZ != z || hit.sideHit != side) return false;
        double[] local = {hit.hitVec.xCoord - x, hit.hitVec.yCoord - y, hit.hitVec.zCoord - z};
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++)
            if (!Double.isFinite(local[i]) || Math.abs(local[i] - point.get(axes[i]).getAsDouble()) > TOLERANCE) return false;
        return true;
    }
}
