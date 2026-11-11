package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.ae.AeWorldAdapter;
import com.Goderium.BridgeNH.common.machine.GregTechAdapter;
import com.Goderium.BridgeNH.common.structure.PlacementAfterObservation;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fluids.IFluidHandler;

/** Shared loaded owner reads; callers supply the execution side after authenticating it. */
public final class WorldInspectionAdapter {
    public static JsonObject inspectMany(World world, JsonObject args, boolean developer) {
        boolean includeAe = RemoteReadProtocol.booleanArg(args, "includeAe");
        if (includeAe && (!developer || RemoteReadProtocol.booleanArg(args, "compact")))
            throw new BridgeException("invalid_args", "includeAe requires developer noncompact owner inspection.");
        if (RemoteReadProtocol.booleanArg(args, "compact")) {
            return WorldInspectManyAdapter.read(new WorldInspectManyAdapter.Access() {
                public String source() { return world.isRemote ? "client" : "integrated_server"; }
                public int dimension() { return world.provider.dimensionId; }
                public long tick() { return world.getTotalWorldTime(); }
                public JsonObject block(int x, int y, int z) { return compactBlock(world, x, y, z); }
            }, args);
        }
        JsonElement positions = args.get("positions");
        if (positions == null || !positions.isJsonArray() || positions.getAsJsonArray().size() < 1 || positions.getAsJsonArray().size() > 16)
            throw new BridgeException("invalid_args", "positions must contain 1 through 16 coordinate objects.");
        JsonArray results = new JsonArray();
        for (JsonElement position : positions.getAsJsonArray()) {
            JsonObject point = new JsonObject();
            try {
                if (!position.isJsonObject()) throw new BridgeException("invalid_args", "Each position must be a coordinate object.");
                JsonObject coordinates = position.getAsJsonObject();
                int x = JsonData.integer(coordinates, "x", -30000000, 30000000);
                int y = JsonData.integer(coordinates, "y", 0, 255);
                int z = JsonData.integer(coordinates, "z", -30000000, 30000000);
                JsonObject data = aeDetails(world, x, y, z, developer, args, block(world, x, y, z, developer));
                if (data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48 * 1024)
                    throw new BridgeException("result_too_large", "This point exceeds the batch per-position limit of 48 KiB; inspect it separately.");
                point.add("data", data);
                point.addProperty("ok", true);
            } catch (RuntimeException | LinkageError failure) {
                JsonObject error = new JsonObject();
                error.addProperty("code", failure instanceof BridgeException ? ((BridgeException) failure).code : "inspection_failed");
                error.addProperty("message", failure instanceof BridgeException ? JsonData.bounded(failure.getMessage(), 512) : "Inspection failed: " + failure.getClass().getSimpleName());
                point.addProperty("ok", false);
                point.add("error", error);
            }
            results.add(point);
        }
        JsonObject result = new JsonObject();
        result.addProperty("source", developer ? "integrated_server" : "client");
        result.addProperty("dimension", world.provider.dimensionId);
        result.add("results", results);
        return result;
    }

    static JsonObject compactBlock(World world, int x, int y, int z) {
        if (new WorldScan.MinecraftAccess(world).section(x >> 4, y >> 4, z >> 4) == WorldScan.UNKNOWN)
            throw new BridgeException("chunk_unloaded", "Compact inspection requires an existing currently loaded chunk.");
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        Block block = chunk.getBlock(x & 15, y, z & 15);
        JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
        result.addProperty("dimension", world.provider.dimensionId); result.addProperty("source", world.isRemote ? "client" : "integrated_server");
        result.addProperty("loaded", true);
        result.addProperty("registryName", JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 256));
        result.addProperty("metadata", chunk.getBlockMetadata(x & 15, y, z & 15));
        result.add("publicMaterial", WorldScan.publicMaterial(block.getMaterial()));
        Object tile = chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        if (tile instanceof TileEntity) result.addProperty("tileClass", JsonData.bounded(tile.getClass().getName(), 256));
        return result;
    }

    public static JsonObject block(World world, int x, int y, int z, boolean developer) {
        return block(world, x, y, z, developer, null);
    }

    public static JsonObject aeDetails(World world, int x, int y, int z, boolean developer, JsonObject args, JsonObject data) {
        if (!RemoteReadProtocol.booleanArg(args, "includeAe")) return data;
        if (!developer) throw new BridgeException("permission_denied", "Detailed AE owner data requires developer inspection.");
        JsonObject ae = AeWorldAdapter.inspect(world.getTileEntity(x, y, z));
        if (ae != null) data.add("ae", ae);
        return data;
    }

    public static JsonObject block(World world, int x, int y, int z, boolean developer, PlacementAfterObservation.Coverage coverage) {
        if (!world.blockExists(x, y, z)) throw new BridgeException("chunk_unloaded", "Block is outside the loaded chunks.");
        Block block = world.getBlock(x, y, z);
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("dimension", world.provider.dimensionId);
        result.addProperty("source", world.isRemote ? "client" : "integrated_server");
        result.addProperty("registryName", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        result.addProperty("metadata", world.getBlockMetadata(x, y, z));
        TileEntity tile = world.getTileEntity(x, y, z);
        if (coverage != null) coverage.interfaces(tile != null, tile instanceof net.minecraft.inventory.IInventory, tile instanceof IFluidHandler,
                ExpandedGuiApi.is(tile, "gregtech.api.metatileentity.BaseMetaTileEntity") || ExpandedGuiApi.is(tile, "gregtech.api.metatileentity.BaseMetaPipeEntity"));
        if (tile != null) {
            result.addProperty("tileClass", tile.getClass().getName());
            if (ExpandedGuiApi.is(tile, AePartAdapter.HOST)) result.add("parts", AePartAdapter.parts(world, x, y, z));
            if (developer) {
                if (tile instanceof net.minecraft.inventory.IInventory && (coverage == null || coverage.proceed())) {
                    JsonObject inventory = JsonData.inventory((net.minecraft.inventory.IInventory) tile); result.add("inventory", inventory);
                    if (coverage != null) coverage.inventory(inventory);
                }
                if (tile instanceof IFluidHandler && (coverage == null || coverage.proceed())) {
                    if (coverage == null) result.add("fluids", JsonData.fluids((IFluidHandler) tile));
                    else {
                        net.minecraftforge.fluids.FluidTankInfo[] tanks = ((IFluidHandler) tile).getTankInfo(net.minecraftforge.common.util.ForgeDirection.UNKNOWN);
                        if (tanks != null) {
                            JsonObject fluids = JsonData.fluids((IFluidHandler) tile, tanks); result.add("fluids", fluids);
                            boolean known = true;
                            for (int i = 0; i < Math.min(tanks.length, 32); i++)
                                if (tanks[i] == null || tanks[i].fluid != null && tanks[i].fluid.getFluid() == null) known = false;
                            coverage.fluids(fluids, known);
                        }
                    }
                }
                if (coverage == null || coverage.proceed()) {
                    JsonObject gt = GregTechAdapter.inspect(tile);
                    if (gt != null) result.add("gregtech", gt);
                    if (coverage != null && gt != null) coverage.gregtech(gt);
                }
            }
        }
        return result;
    }
}
