package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Developer-only operations; the caller owns integrated-server thread and profile checks. */
public class DeveloperMachineTools {
    private static final int CANDIDATE_LIMIT = 8192;

    public static JsonObject tiles(World world, JsonObject args) {
        requireWorld(world, args);
        int x = JsonData.integer(args, "x", -30000000, 30000000);
        int y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000);
        int radius = args.has("radius") ? JsonData.integer(args, "radius", 1, 64) : 16;
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32;
        String query = args.has("query") ? JsonData.string(args, "query") : "";
        if (query.length() > 128) throw new BridgeException("invalid_args", "query may contain at most 128 characters.");
        String search = query.trim().toLowerCase(Locale.ROOT);
        List<Candidate> matches = new ArrayList<Candidate>();
        int totalLoaded = world.loadedTileEntityList.size();
        int examined = 0;
        for (Object entry : world.loadedTileEntityList) {
            if (examined >= CANDIDATE_LIMIT) break;
            examined++;
            if (!(entry instanceof TileEntity)) continue;
            TileEntity tile = (TileEntity) entry;
            if (tile.isInvalid() || tile.getWorld() != world) continue;
            long dx = (long) tile.xCoord - x, dy = (long) tile.yCoord - y, dz = (long) tile.zCoord - z;
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            long distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > (long) radius * radius || !world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)) continue;
            if (world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) != tile) continue;
            JsonObject identity = position(world, tile.xCoord, tile.yCoord, tile.zCoord);
            Block block = world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord);
            identity.addProperty("registryName", JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 512));
            identity.addProperty("metadata", world.getBlockMetadata(tile.xCoord, tile.yCoord, tile.zCoord));
            identity.addProperty("tileClass", tile.getClass().getName());
            identity.addProperty("distanceSquared", distanceSquared);
            JsonObject gt = GregTechAdapter.identity(tile);
            if (gt != null) identity.add("gregtech", gt);
            if (matches(identity, search)) matches.add(new Candidate(tile, identity, distanceSquared));
        }
        Collections.sort(matches, new Comparator<Candidate>() {
            public int compare(Candidate left, Candidate right) {
                int compared = Long.compare(left.distanceSquared, right.distanceSquared);
                if (compared != 0) return compared;
                compared = Integer.compare(left.tile.xCoord, right.tile.xCoord);
                if (compared != 0) return compared;
                compared = Integer.compare(left.tile.yCoord, right.tile.yCoord);
                if (compared != 0) return compared;
                compared = Integer.compare(left.tile.zCoord, right.tile.zCoord);
                if (compared != 0) return compared;
                return left.tile.getClass().getName().compareTo(right.tile.getClass().getName());
            }
        });
        int start = Math.min(offset, matches.size());
        int end = Math.min(start + limit, matches.size());
        JsonArray found = new JsonArray();
        for (int index = start; index < end; index++) {
            Candidate candidate = matches.get(index);
            JsonObject summary = GregTechAdapter.summary(candidate.tile);
            if (summary != null) candidate.identity.add("gregtech", summary);
            found.add(candidate.identity);
        }
        boolean candidateLimitReached = examined < totalLoaded;
        boolean hasMore = end < matches.size();
        JsonObject result = position(world, x, y, z);
        result.add("tiles", found);
        result.addProperty("shape", "sphere");
        result.addProperty("radius", radius);
        result.addProperty("query", query);
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("totalMatched", matches.size());
        result.addProperty("totalMatchedIsExact", !candidateLimitReached);
        result.addProperty("totalLoadedTileEntities", totalLoaded);
        result.addProperty("examined", examined);
        result.addProperty("candidateLimit", CANDIDATE_LIMIT);
        result.addProperty("candidateLimitReached", candidateLimitReached);
        result.addProperty("hasMore", hasMore);
        result.addProperty("truncated", candidateLimitReached || hasMore);
        if (hasMore) result.addProperty("nextOffset", end);
        else result.add("nextOffset", JsonNull.INSTANCE);
        return result;
    }

    public static JsonObject setWorking(World world, JsonObject args) {
        requireWorld(world, args);
        int x = JsonData.integer(args, "x", -30000000, 30000000);
        int y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000);
        int expectedId = JsonData.integer(args, "expectedMetaTileId", 0, 32767);
        JsonElement workingArg = args.get("working");
        if (workingArg == null || !workingArg.isJsonPrimitive() || !workingArg.getAsJsonPrimitive().isBoolean())
            throw new BridgeException("invalid_args", "working must be a boolean.");
        boolean desired = workingArg.getAsBoolean();
        if (!world.blockExists(x, y, z)) throw new BridgeException("chunk_unloaded", "Machine position is not in a loaded chunk.");
        TileEntity tile = world.getTileEntity(x, y, z);
        Class<?> base = machineBase(tile);
        if (tile == null || tile.isInvalid() || tile.getWorld() != world || base == null)
            throw new BridgeException("not_gregtech_machine", "Position does not contain a valid GregTech BaseMetaTileEntity machine.");
        Method work;
        Method allowed;
        Object meta;
        int actualId;
        boolean before;
        try {
            meta = base.getMethod("getMetaTileEntity").invoke(tile);
            if (meta == null) throw new BridgeException("invalid_machine", "The GregTech machine has no valid MetaTileEntity.");
            actualId = ((Number) base.getMethod("getMetaTileID").invoke(tile)).intValue();
            if (actualId != expectedId) throw new BridgeException("stale_machine", "expectedMetaTileId does not match the current machine. Inspect it before changing work state.");
            allowed = base.getMethod("isAllowedToWork");
            work = base.getMethod(desired ? "enableWorking" : "disableWorking");
            before = (Boolean) allowed.invoke(tile);
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new BridgeException("unsupported", "The required public GregTech machine API is unavailable.");
        }
        boolean invoked = before != desired;
        boolean after;
        try {
            if (invoked) work.invoke(tile);
            after = (Boolean) allowed.invoke(tile);
        } catch (InvocationTargetException error) {
            throw new BridgeException("machine_action_failed", "The public GT working-state API failed; state may have changed. Inspect the machine before repeating an action.");
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new BridgeException("machine_action_failed", "Working-state verification failed; inspect the machine before repeating an action.");
        }
        JsonObject result = position(world, x, y, z);
        result.addProperty("metaTileId", actualId);
        result.addProperty("metaClass", meta.getClass().getName());
        result.addProperty("requestedWorking", desired);
        result.addProperty("before", before);
        result.addProperty("after", after);
        result.addProperty("invoked", invoked);
        result.addProperty("changed", before != after);
        result.addProperty("meaning", "Allowed-to-work state through public GT APIs; normal machine hooks apply, without forced recipe completion or direct recipe reset.");
        return result;
    }

    private static void requireWorld(World world, JsonObject args) {
        if (world == null || world.provider == null) throw new BridgeException("world_unavailable", "No integrated-server world is available.");
        if (world.isRemote) throw new BridgeException("permission_denied", "Developer machine operations require the authoritative server world.");
        if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != world.provider.dimensionId)
            throw new BridgeException("world_mismatch", "Requested dimension does not match the selected server world.");
    }

    private static Class<?> machineBase(TileEntity tile) {
        for (Class<?> type = tile == null ? null : tile.getClass(); type != null; type = type.getSuperclass()) {
            if ("gregtech.api.metatileentity.BaseMetaTileEntity".equals(type.getName())) return type;
        }
        return null;
    }

    private static JsonObject position(World world, int x, int y, int z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("dimension", world.provider.dimensionId);
        result.addProperty("source", "integrated_server");
        return result;
    }

    private static boolean matches(JsonObject identity, String query) {
        if (query.isEmpty()) return true;
        if (contains(identity, "tileClass", query) || contains(identity, "registryName", query)) return true;
        if (identity.has("gregtech")) {
            JsonObject gt = identity.getAsJsonObject("gregtech");
            return contains(gt, "metaClass", query) || contains(gt, "displayName", query) || contains(gt, "metaTileId", query);
        }
        return false;
    }

    private static boolean contains(JsonObject object, String key, String query) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsString().toLowerCase(Locale.ROOT).contains(query);
    }

    private static class Candidate {
        final TileEntity tile;
        final JsonObject identity;
        final long distanceSquared;
        Candidate(TileEntity tile, JsonObject identity, long distanceSquared) {
            this.tile = tile;
            this.identity = identity;
            this.distanceSquared = distanceSquared;
        }
    }
}
