package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

/** Optional exact public neighbor identity. It never chooses another attachment. */
public final class PlacementAttachment {
    public final int x, y, z, dimension, side;
    public final JsonObject declaration;
    public PlacementAttachment(JsonObject args, int targetX, int targetY, int targetZ, int targetDimension) {
        this(args, targetX, targetY, targetZ, targetDimension, false);
    }
    @com.google.common.annotations.VisibleForTesting
    public PlacementAttachment(JsonObject args, int targetX, int targetY, int targetZ, int targetDimension, boolean sameHost) {
        JsonElement value = args.get("attachmentBinding");
        if (value == null || !value.isJsonObject()) throw bad("attachmentBinding must be an exact object.");
        declaration = new JsonParser().parse(value.toString()).getAsJsonObject();
        keys(declaration, declaration.has("gregtech")
                ? new String[] {"version", "x", "y", "z", "dimension", "side", "registryName", "metadata", "gregtech"}
                : new String[] {"version", "x", "y", "z", "dimension", "side", "registryName", "metadata"});
        JsonData.integer(declaration, "version", 1, 1);
        x = JsonData.integer(declaration, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
        y = JsonData.integer(declaration, "y", 0, 255);
        z = JsonData.integer(declaration, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
        dimension = JsonData.integer(declaration, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
        side = JsonData.integer(declaration, "side", 0, 5);
        text(declaration, "registryName"); JsonData.integer(declaration, "metadata", 0, 32766);
        if (declaration.has("gregtech")) {
            if (!declaration.get("gregtech").isJsonObject()) throw bad("gregtech must retain its exact public identity.");
            JsonObject gt = declaration.getAsJsonObject("gregtech"); keys(gt, "metaTileId", "metaClass");
            JsonData.integer(gt, "metaTileId", 0, 32767); text(gt, "metaClass");
        }
        ForgeDirection face = ForgeDirection.getOrientation(side);
        if (dimension != targetDimension || (sameHost ? x != targetX || y != targetY || z != targetZ
                : x + face.offsetX != targetX || y + face.offsetY != targetY || z + face.offsetZ != targetZ))
            throw bad("The bound face must be the exact adjacent destination in the current dimension.");
    }
    public JsonObject verify(NativePlacementAdapter.Access access) {
        if (!access.loaded(x, y, z)) throw new BridgeException("chunk_unloaded", "Exact placement attachment is not already loaded.");
        NativePlacementAdapter.State state = access.attachmentState(x, y, z);
        JsonObject actual = state.description;
        if (state.air || state.replaceable || !same(declaration, actual, "registryName") || !same(declaration, actual, "metadata")
                || declaration.has("gregtech") != actual.has("gregtech")) throw changed();
        if (declaration.has("gregtech")) {
            if (!actual.get("gregtech").isJsonObject()) throw changed();
            JsonObject expectedGt = declaration.getAsJsonObject("gregtech"), actualGt = actual.getAsJsonObject("gregtech");
            if (!same(expectedGt, actualGt, "metaTileId") || !same(expectedGt, actualGt, "metaClass")) throw changed();
        }
        return new JsonParser().parse(actual.toString()).getAsJsonObject();
    }
    MovingObjectPosition face(NativePlacementAdapter.Access access, Vec3 eye, double reach) {
        JsonObject actual = verify(access);
        if (NativePlacementAdapter.distanceToBlock(eye, x, y, z) > reach)
            throw new BridgeException("out_of_reach", "Exact placement attachment is outside normal reach; no neighbor is substituted.");
        MovingObjectPosition hit = null;
        if (actual.has("parts")) {
            for (JsonElement part : actual.getAsJsonArray("parts")) {
                hit = NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(access, part.getAsJsonObject()), eye, reach, x, y, z, side);
                if (hit != null) break;
            }
        } else hit = NativePlacementAdapter.visibleFace(access, eye, reach, x, y, z, side);
        if (hit == null) throw new BridgeException("face_not_visible", "Exact bound placement face has no real native ray; no neighbor is substituted.");
        return hit;
    }
    public static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("operation", "player.place_block");
        result.addProperty("argument", "attachmentBinding"); result.addProperty("guardedOnly", true);
        result.addProperty("exactAdjacentFace", true); result.addProperty("publicBlockIdentity", true);
        result.addProperty("publicGregTechIdentity", true); result.addProperty("clientServerRequired", true);
        result.addProperty("allPlayerSlots", 41); result.addProperty("normalReachAndRayRequired", true);
        result.addProperty("wholeTargetBodyExcluded", true); result.addProperty("reselectAllowed", false);
        result.addProperty("automaticReplayAllowed", false); return result;
    }
    private static boolean same(JsonObject first, JsonObject second, String key) {
        return first.has(key) && second.has(key) && first.get(key).equals(second.get(key));
    }
    private static void keys(JsonObject object, String... expected) {
        HashSet<String> actual = new HashSet<String>(); for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) actual.add(entry.getKey());
        if (!actual.equals(new HashSet<String>(Arrays.asList(expected)))) throw bad("Unexpected or missing exact attachment fields.");
    }
    private static void text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty() || value.getAsString().length() > 256) throw bad("Invalid public attachment identity: " + key);
    }
    private static BridgeException bad(String message) { return new BridgeException("invalid_args", message); }
    private static BridgeException changed() { return new BridgeException("placement_attachment_changed", "The exact public Block/GT attachment identity changed; no neighbor is substituted."); }
}
