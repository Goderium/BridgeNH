package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Filters real native rays; never changes a tile's orientation or the player's physics. */
public final class PlacementFacing {
    public static final int VERSION = 1;
    public static String requested(JsonObject args) {
        return args.has("expectedPlacementFacing") ? ExpandedGuiApi.option(args, "expectedPlacementFacing", null,
                "NORTH", "SOUTH", "WEST", "EAST") : null;
    }
    static void source(String facing, ItemStack item) {
        if (facing != null && (item == null || !"gregtech:gt.blockmachines".equals(String.valueOf(Item.itemRegistry.getNameForObject(item.getItem())))))
            throw new BridgeException("unsupported", "Placement facing requires an actual GT machine ItemBlock; no other placement rule is inferred.");
    }
    static String direction(double yaw, double pitch) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || pitch < -90 || pitch > 90)
            throw new BridgeException("unsupported", "Placement facing needs the finite actual native view.");
        if (pitch >= 65) return "UP";
        if (pitch <= -65) return "DOWN";
        return new String[] {"NORTH", "EAST", "SOUTH", "WEST"}[(int)Math.floor(.5 + yaw / 90) & 3];
    }
    public static void require(String expected, double yaw, double pitch) {
        if (expected != null && !expected.equals(direction(yaw,pitch)))
            throw new BridgeException("placement_facing_changed", "Actual native placement view no longer matches the declared GT facing; no placement is submitted.");
    }
    static boolean matches(String facing, Vec3 eye, MovingObjectPosition hit) {
        if (facing == null) return true;
        if (hit == null || hit.hitVec == null) return false;
        double x=hit.hitVec.xCoord-eye.xCoord, y=hit.hitVec.yCoord-eye.yCoord, z=hit.hitVec.zCoord-eye.zCoord;
        return facing.equals(direction(Math.toDegrees(Math.atan2(z,x))-90, -Math.toDegrees(Math.atan2(y,Math.hypot(x,z)))));
    }
    public static NativePlacementAdapter.Access filter(final NativePlacementAdapter.Access access, final String facing) {
        if (facing == null) return access;
        return new NativePlacementAdapter.Access() {
            public boolean loaded(int x,int y,int z) { return access.loaded(x,y,z); }
            public NativePlacementAdapter.State state(int x,int y,int z) { return access.state(x,y,z); }
            public NativePlacementAdapter.State attachmentState(int x,int y,int z) { return access.attachmentState(x,y,z); }
            public MovingObjectPosition trace(Vec3 eye,Vec3 point) {
                // Vanilla ray tracing may mutate its start vector. Retain the actual eye for quantization.
                Vec3 origin=Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord);
                MovingObjectPosition hit=access.trace(eye,point);
                return matches(facing,origin,hit) ? hit : null;
            }
            public AxisAlignedBB selectedBounds(int x,int y,int z) { return access.selectedBounds(x,y,z); }
            public List<AxisAlignedBB> selectedBoxes(int x,int y,int z) { return access.selectedBoxes(x,y,z); }
            public boolean exactPartShape() { return access.exactPartShape(); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return access.selectedPart(hit); }
            public AxisAlignedBB partBounds(int x,int y,int z,JsonObject part) { return access.partBounds(x,y,z,part); }
            public List<AxisAlignedBB> partBoxes(int x,int y,int z,JsonObject part) { return access.partBoxes(x,y,z,part); }
        };
    }
}
