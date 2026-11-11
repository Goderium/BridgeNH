package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

/** Fixed public GT tool checks and a normal face-centre click, never a tile setter. */
public final class NativeGtWrench {
    public static final int APPROACH_VERSION = 1;
    /** Read-only endpoint test using exactly the same face-centre contract as the eventual click. */
    public static MovingObjectPosition probe(NativePlacementAdapter.Access access, Vec3 eye, double reach,
            int x, int y, int z, int side) {
        try { return target(access, eye, reach, x, y, z, side); }
        catch (BridgeException error) {
            if ("face_not_visible".equals(error.code)) return null;
            throw error;
        }
    }
    public static void source(ItemStack stack) {
        if (stack == null || stack.stackSize != 1
                || !"gregtech:gt.metatool.01".equals(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())))
                || stack.getMetadata() != 16 || !stack.hasTagCompound())
            throw new BridgeException("wrench_required", "Use one actual material GT wrench (metadata 16), with its native tool NBT.");
        Class<?> tool = ExpandedGuiApi.load(stack.getItem(), "gregtech.api.items.MetaGeneratedTool");
        long maximum = ((Number) ExpandedGuiApi.call(tool, "getToolMaxDamage", stack)).longValue();
        long damage = ((Number) ExpandedGuiApi.call(tool, "getToolDamage", stack)).longValue();
        if (maximum <= 0 || damage < 0 || damage >= maximum)
            throw new BridgeException("wrench_required", "The actual GT wrench lacks positive native remaining durability.");
    }

    public static MovingObjectPosition target(NativePlacementAdapter.Access access, Vec3 eye, double reach,
            int x, int y, int z, int side) {
        JsonObject state = access.state(x, y, z).description;
        if (!state.has("registryName") || !"gregtech:gt.blockmachines".equals(state.get("registryName").getAsString()))
            throw new BridgeException("target_unverified", "A wrench-facing interaction requires the actual loaded GT machine block.");
        ForgeDirection face = ForgeDirection.getOrientation(side);
        Vec3 point = Vec3.createVectorHelper(x + .5 + face.offsetX * .499,
                y + .5 + face.offsetY * .499, z + .5 + face.offsetZ * .499);
        if (!NativePlacementAdapter.loadedRay(access, eye, point, y))
            throw new BridgeException("chunk_unloaded", "The wrench ray must stay in loaded geometry.");
        MovingObjectPosition hit = access.trace(Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), point);
        if (!centre(hit, x, y, z, side) || eye.distanceTo(hit.hitVec) > reach)
            throw new BridgeException("face_not_visible", "The requested GT wrench face centre is not in normal reach; no click submitted.");
        return hit;
    }

    public static boolean centre(MovingObjectPosition hit, int x, int y, int z, int side) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.blockX != x || hit.blockY != y || hit.blockZ != z || hit.sideHit != side || hit.hitVec == null) return false;
        double[] point = {hit.hitVec.xCoord - x, hit.hitVec.yCoord - y, hit.hitVec.zCoord - z};
        int normal = side < 2 ? 1 : side < 4 ? 2 : 0;
        for (int axis = 0; axis < 3; axis++)
            if (!Double.isFinite(point[axis]) || axis != normal && (point[axis] < .3 || point[axis] > .7)) return false;
        return true;
    }
}
