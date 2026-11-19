package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.machine.WailaProviderNbt;
import com.Goderium.BridgeNH.util.BoundedNbt;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import java.io.IOException;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** One authoritative inventory write. Never run a harvesting or tile-destruction callback to obtain a copy. */
public final class PortableBlockCopy {
    private static final String GT = "gregtech.api.metatileentity.";
    private PortableBlockCopy() {}

    public static JsonObject copy(EntityPlayerMP player, JsonObject args) {
        int dimension = JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
        World world = player.worldObj;
        if (player.isDead || world.isRemote || player.dimension != dimension || world.provider.dimensionId != dimension
                || !player.capabilities.isCreativeMode)
            throw new BridgeException("permission_denied", "Copy requires the bound live creative player in the requested dimension.");
        int slot = JsonData.integer(args, "destinationSlot", 0, 35);
        if (player.openContainer != player.inventoryContainer || player.inventory.getItemStack() != null
                || player.inventory.getStackInSlot(slot) != null)
            throw new BridgeException("invalid_state", "Close the container and provide an empty cursor and destination inventory slot.");
        int x = JsonData.integer(args, "x", -30000000, 30000000);
        int y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000);
        if (!world.blockExists(x, y, z)) throw new BridgeException("chunk_unloaded", "The requested block is not loaded.");
        Block block = world.getBlock(x, y, z);
        int damage = world.getBlockMetadata(x, y, z);
        if (!JsonData.string(args, "registryName").equals(Block.blockRegistry.getNameForObject(block))
                || damage != JsonData.integer(args, "metadata", 0, 15) || block.isAir(world, x, y, z))
            throw new BridgeException("target_unverified", "The loaded block does not match the observed identity.");
        TileEntity tile = world.getTileEntity(x, y, z);
        boolean machine = ExpandedGuiApi.is(tile, GT + "BaseMetaTileEntity");
        boolean gt = machine || ExpandedGuiApi.is(tile, GT + "BaseMetaPipeEntity");
        ItemStack result;
        if (gt) {
            int expected = JsonData.integer(args, "expectedMetaTileId", 0, 32767);
            int actual = ((Number) ExpandedGuiApi.call(tile, "getMetaTileID")).intValue();
            Object meta = ExpandedGuiApi.call(tile, "getMetaTileEntity");
            if (tile.isInvalid() || expected != actual || meta == null || ExpandedGuiApi.call(meta, "getBaseMetaTileEntity") != tile)
                throw new BridgeException("target_unverified", "The authoritative GT machine identity changed.");
            result = gtItem(tile, meta, machine, actual);
        } else {
            if (args.has("expectedMetaTileId")) throw new BridgeException("target_unverified", "The target has no supported GregTech base tile.");
            int side = args.has("side") ? JsonData.integer(args, "side", 0, 5) : 1;
            ForgeDirection face = ForgeDirection.getOrientation(side);
            Vec3 center = Vec3.createVectorHelper(x + .5 + face.offsetX * .5, y + .5 + face.offsetY * .5, z + .5 + face.offsetZ * .5);
            result = block.getPickBlock(new MovingObjectPosition(x, y, z, side, center), world, x, y, z, player);
        }
        if (result == null || result.getItem() == null || result.stackSize < 1)
            throw new BridgeException("unsupported", "This block has no obtainable native item form.");
        if (result.hasTagCompound()) requirePortableTag(result.getTagCompound());
        ItemStack copy = result.copy();
        copy.stackSize = 1;
        if (world.getBlock(x, y, z) != block || world.getBlockMetadata(x, y, z) != damage || world.getTileEntity(x, y, z) != tile
                || player.isDead || player.worldObj != world || !player.capabilities.isCreativeMode
                || player.openContainer != player.inventoryContainer || player.inventory.getItemStack() != null
                || player.inventory.getStackInSlot(slot) != null)
            throw new BridgeException("target_unverified", "The target, player or inventory changed during item export.");
        JsonObject out = new JsonObject();
        out.addProperty("source", gt ? "gt_portable_item" : "forge_pick_item");
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("dimension", dimension);
        out.addProperty("destinationSlot", slot);
        out.add("item", JsonData.item(copy));
        if (copy.hasTagCompound()) out.add("tagPreview", BoundedNbt.describe(copy.getTagCompound()));
        player.inventory.setInventorySlotContents(slot, copy);
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        boolean confirmed = ItemStack.areItemStacksEqual(copy, player.inventory.getStackInSlot(slot));
        out.addProperty("serverSlotConfirmed", confirmed);
        out.addProperty("accepted", confirmed);
        out.addProperty("submitted", true);
        return out;
    }

    private static ItemStack gtItem(TileEntity tile, Object meta, boolean machine, int metaId) {
        Class<?> api = ExpandedGuiApi.load(tile, "gregtech.api.GregTechAPI");
        Block machineBlock = (Block) ExpandedGuiApi.field(null, api, "sBlockMachines");
        Item item = Item.getItemFromBlock(machineBlock);
        if (item == null) throw new BridgeException("unsupported", "The GT machine block has no registered item.");
        ItemStack stack = new ItemStack(item, 1, metaId);
        NBTTagCompound tag = new NBTTagCompound();
        // Mirrors only GT5U's item serialization. getDrops also calls onBaseTEDestroyed.
        if (machine) {
            if (Boolean.TRUE.equals(ExpandedGuiApi.field(tile, GT + "BaseMetaTileEntity", "mLockUpgrade"))) tag.setBoolean("mLockUpgrade", true);
            byte color = ((Number) ExpandedGuiApi.declaredCall(tile, GT + "CommonBaseMetaTileEntity", "getColorRaw")).byteValue();
            byte upgrades = ((Number) ExpandedGuiApi.field(tile, GT + "BaseMetaTileEntity", "mOtherUpgrades")).byteValue();
            if (color > 0) tag.setByte("mColor", color);
            if (upgrades > 0) tag.setByte("mOtherUpgrades", upgrades);
        }
        ExpandedGuiApi.declaredCall(tile, GT + "CoverableTileEntity", "writeCoverNBT", tag, true);
        ExpandedGuiApi.call(meta, "setItemNBT", tag);
        if (!tag.hasNoTags()) stack.setTagCompound(tag);
        return stack;
    }

    private static void requirePortableTag(NBTTagCompound tag) {
        WailaProviderNbt bounded = WailaProviderNbt.snapshot(tag);
        if (!bounded.describe().get("complete").getAsBoolean())
            throw new BridgeException("too_large", "Cannot copy the complete portable item tag within the structural budget.");
        try {
            if (CompressedStreamTools.compress(tag).length > Short.MAX_VALUE)
                throw new BridgeException("too_large", "The complete item tag exceeds the inventory packet limit.");
        } catch (IOException invalid) {
            throw new BridgeException("unsupported", "The portable tag cannot be serialized.");
        }
    }
}
