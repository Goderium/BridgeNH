package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.nei.NeiUiAccess;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Optional public AE part selection. Mounting side is independent of the native ray entry face. */
public final class AePartAdapter {
    public static final String HOST = "appeng.api.parts.IPartHost";
    public static JsonObject declaration(JsonObject value) {
        ExpandedGuiApi.keys(value, "side", "registryName", "metadata");
        JsonData.integer(value, "side", 0, 6);
        String registry = JsonData.string(value, "registryName");
        if (registry.length() > 256 || registry.indexOf(':') < 1)
            throw new BridgeException("invalid_args", "A part requires its actual registered item identity.");
        JsonData.integer(value, "metadata", 0, 32767);
        return new com.google.gson.JsonParser().parse(value.toString()).getAsJsonObject();
    }
    static JsonObject identity(Object part, int side) {
        Object mode = ExpandedGuiApi.enumeration(part, "appeng.api.parts.PartItemStack", "Pick");
        Object value = ExpandedGuiApi.call(part, "getItemStack", mode);
        if (!(value instanceof ItemStack)) throw new BridgeException("target_unverified", "The real selected part has no public pick identity.");
        ItemStack item = (ItemStack) value;
        String registry = String.valueOf(Item.itemRegistry.getNameForObject(item.getItem()));
        if (registry.indexOf(':') < 1 || registry.length() > 256 || item.getMetadata() < 0 || item.getMetadata() > 32767)
            throw new BridgeException("target_unverified", "The real selected part has no bounded registered pick identity.");
        JsonObject result = new JsonObject(); result.addProperty("side", side);
        result.addProperty("registryName", registry); result.addProperty("metadata", item.getMetadata());
        return result;
    }
    public static JsonArray parts(World world, int x, int y, int z) {
        JsonArray result = new JsonArray(); TileEntity tile = world.getTileEntity(x, y, z);
        if (!ExpandedGuiApi.is(tile, HOST)) return result;
        for (int side = 0; side < 7; side++) {
            Object part = ExpandedGuiApi.call(tile, "getPart", ForgeDirection.getOrientation(side));
            if (part != null) result.add(identity(part, side));
        }
        return result;
    }
    public static boolean matches(JsonObject expected, JsonObject actual) {
        return expected != null && actual != null && expected.equals(actual);
    }
    public static JsonObject selected(World world, MovingObjectPosition hit) {
        if (hit == null || hit.hitVec == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        Object tile = world.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
        if (!ExpandedGuiApi.is(tile, HOST)) return null;
        Object selected = ExpandedGuiApi.call(tile, "selectPart", Vec3.createVectorHelper(
                hit.hitVec.xCoord-hit.blockX, hit.hitVec.yCoord-hit.blockY, hit.hitVec.zCoord-hit.blockZ));
        Object part = NeiUiAccess.publicField(tile.getClass().getClassLoader(), "appeng.api.parts.SelectedPart", selected, "part");
        Object side = NeiUiAccess.publicField(tile.getClass().getClassLoader(), "appeng.api.parts.SelectedPart", selected, "side");
        return part == null || !(side instanceof ForgeDirection) ? null : identity(part, ((ForgeDirection) side).ordinal());
    }
    public static AxisAlignedBB bounds(World world, int x, int y, int z, JsonObject expected) {
        AxisAlignedBB union = null;
        for (AxisAlignedBB box : boxes(world, x, y, z, expected)) union = union == null ? box : union.union(box);
        return union;
    }
    public static List<AxisAlignedBB> boxes(World world, int x, int y, int z, JsonObject expected) {
        Object tile = world.getTileEntity(x, y, z);
        if (!ExpandedGuiApi.is(tile, HOST)) throw new BridgeException("target_unverified", "The actual loaded block no longer exposes the declared part host.");
        int side = JsonData.integer(expected, "side", 0, 6);
        Object part = ExpandedGuiApi.call(tile, "getPart", ForgeDirection.getOrientation(side));
        if (part == null || !matches(expected, identity(part, side)))
            throw new BridgeException("target_unverified", "The declared mounted part changed.");
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
        // Reuse AE's own public orientation helper and IPart.getBoxes, without duplicating its geometry.
        Object helper = ExpandedGuiApi.create(part, "appeng.parts.BusCollisionHelper", boxes, ForgeDirection.getOrientation(side), null, true);
        ExpandedGuiApi.call(part, "getBoxes", helper);
        if (boxes.isEmpty() || boxes.size() > 64) throw new BridgeException("unsupported", "The native part shape is empty or exceeds its bounded geometry scope.");
        List<AxisAlignedBB> result = new ArrayList<AxisAlignedBB>();
        for (AxisAlignedBB box : boxes) {
            AxisAlignedBB placed = box.getOffsetBoundingBox(x, y, z);
            if (!NativePlacementAdapter.boundedShape(placed, x, y, z))
                throw new BridgeException("unsupported", "The native part shape is outside its finite bounded geometry scope.");
            result.add(placed);
        }
        return result;
    }
    public static void requirePresent(JsonObject expected, JsonObject block) {
        if (expected == null) return;
        if (block.has("parts") && block.get("parts").isJsonArray())
            for (JsonElement value : block.getAsJsonArray("parts"))
                if (value.isJsonObject() && matches(expected, value.getAsJsonObject())) return;
        throw new BridgeException("target_unverified", "The declared actual mounted part is unavailable on this owner.");
    }
}
