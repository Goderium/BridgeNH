package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

/** Bounded observations, never a world snapshot. Stores only traversal metadata, not block results. */
public final class WorldScan {
    public static final int CELLS_PER_TICK = 4096;
    public static final int PROBES_PER_TICK = 512;
    public static final long NANOS_PER_TICK = 2000000L;
    public static final int BLOCK_BYTES_PER_PAGE = 128 * 1024;
    public static final int MAX_STATES = 8;
    public static final long IDLE_NANOS = 300000000000L;
    public static final int WORLD_MIN = -30000000;
    public static final int WORLD_MAX = 29999999;
    public static final int UNKNOWN = 0, EMPTY = 1, PRESENT = 2;
    private final LongSupplier clock;
    private final LinkedHashMap<String, State> states = new LinkedHashMap<String, State>();
    private final IdentityHashMap<GameQueue.Control, Pending> pending = new IdentityHashMap<GameQueue.Control, Pending>();
    private int cellsRemaining = CELLS_PER_TICK;
    private int probesRemaining = PROBES_PER_TICK;
    private long nanosRemaining = NANOS_PER_TICK;

    public WorldScan() { this(System::nanoTime); }
    WorldScan(LongSupplier clock) { this.clock = clock; }

    public synchronized void beginTick() {
        cellsRemaining = CELLS_PER_TICK;
        probesRemaining = PROBES_PER_TICK;
        nanosRemaining = NANOS_PER_TICK;
    }

    public synchronized void invalidate() { states.clear(); pending.clear(); }

    public synchronized int cursorDimension(JsonObject args) {
        String cursor = JsonData.string(args, "cursor");
        if (cursor.isEmpty() || cursor.length() > 128) throw new BridgeException("invalid_args", "cursor must contain 1 through 128 characters.");
        expire(clock.getAsLong());
        State state = states.get(cursor);
        if (state == null) {
            requireNotReserved(cursor);
            throw new BridgeException("stale_cursor", "Scan cursor expired, completed, changed context, or was already consumed.");
        }
        return state.dimension;
    }

    private void requireNotReserved(String cursor) {
        for (Pending transaction : pending.values())
            if (cursor.equals(transaction.nextCursor)) throw new BridgeException("busy", "The preceding scan page is still awaiting HTTP delivery acknowledgement.");
    }

    /** HTTP completion commits metadata only. Failed delivery leaves the old cursor available for a fresh read. */
    public synchronized void responseFinished(GameQueue.Control control, boolean delivered) {
        Pending transaction = pending.remove(control);
        if (transaction == null) return;
        State original = states.get(transaction.oldCursor);
        if (original != transaction.original) return;
        original.inflight = false;
        original.touched = clock.getAsLong();
        if (delivered) {
            states.remove(transaction.oldCursor);
            if (!transaction.next.complete()) states.put(transaction.nextCursor, transaction.next);
        } else if (transaction.initial) {
            // No cursor for this first page reached the caller.
            states.remove(transaction.oldCursor);
        }
    }

    public synchronized JsonObject page(Access access, JsonObject args, ScanSettings settings, long epoch,
            int[] defaultCenter, GameQueue.Control control) {
        long started = clock.getAsLong();
        expire(started);
        if (control == null || pending.containsKey(control)) throw new BridgeException("invalid_state", "A scan needs its unique HTTP request control.");
        boolean continuation = args.has("cursor");
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            boolean allowed = "limit".equals(key) || "cursor".equals(key) || !continuation &&
                    ("x".equals(key) || "y".equals(key) || "z".equals(key) || "dimension".equals(key)
                            || "radius".equals(key) || "radiusChunks".equals(key) || "verticalRadiusChunks".equals(key)
                            || "bounds".equals(key) || "directional".equals(key) || "airSemantics".equals(key));
            if (!allowed) throw new BridgeException("invalid_args", "A continuation accepts only cursor and limit; first pages accept dimension, one radius/bounds/directional region, airSemantics and limit.");
        }
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 512) : 128;
        String oldCursor;
        State original;
        if (continuation) {
            oldCursor = JsonData.string(args, "cursor");
            if (oldCursor.isEmpty() || oldCursor.length() > 128) throw new BridgeException("invalid_args", "cursor must contain 1 through 128 characters.");
            original = states.get(oldCursor);
            if (original == null) {
                requireNotReserved(oldCursor);
                throw new BridgeException("stale_cursor", "Scan cursor expired, completed, changed context, or was already consumed.");
            }
            if (original.world != access.identity() || original.epoch != epoch || original.dimension != access.dimension()) {
                states.remove(oldCursor);
                throw new BridgeException("stale_cursor", "The scan world, dimension or permissions changed.");
            }
            if (original.inflight) throw new BridgeException("busy", "This scan page is awaiting HTTP delivery acknowledgement.");
        } else {
            AirSemantics airSemantics = AirSemantics.read(args);
            ScanRegion region = ScanRegion.read(args, settings, defaultCenter);
            if (states.size() >= MAX_STATES) throw new BridgeException("busy", "At most eight scans may remain open; complete them or wait for expiry.");
            original = new State(access, epoch, region, airSemantics, started);
            oldCursor = UUID.randomUUID().toString();
            states.put(oldCursor, original);
        }
        State next = new State(original);
        JsonArray blocks = new JsonArray();
        JsonArray spans = new JsonArray();
        long before = next.examined;
        long airBefore = next.air, unloadedBefore = next.unloaded, nonAirBefore = next.nonAir;
        long deadline = started + Math.max(0, nanosRemaining);
        int visits = 0, probes = 0, bytes = 0;
        String stop = "complete";
        try {
        while (!next.complete()) {
            if (blocks.size() >= limit) { stop = "result_limit"; break; }
            if (cellsRemaining <= 0 || probesRemaining <= 0 || nanosRemaining <= 0 || clock.getAsLong() >= deadline) { stop = "tick_budget"; break; }
            Group group = next.group();
            int kind = access.section(group.cx, group.sy, group.cz, next.airSemantics);
            probes++;
            probesRemaining--;
            if (kind == UNKNOWN || kind == EMPTY) {
                long count = group.volume - next.cell;
                spans.add(group.span(next.cell, group.volume, next.examined, next.examined + count, kind == UNKNOWN ? "unloaded" : "air"));
                next.examined += count;
                if (kind == UNKNOWN) next.unloaded += count;
                else next.air += count;
                next.advanceGroup();
                continue;
            }
            int cellBefore = next.cell;
            long offsetBefore = next.examined;
            while (next.cell < group.volume) {
                if (blocks.size() >= limit) { stop = "result_limit"; break; }
                if (cellsRemaining <= 0 || clock.getAsLong() >= deadline) { stop = "tick_budget"; break; }
                int[] point = group.point(next.cell);
                JsonObject block = access.block(point[0], point[1], point[2], next.airSemantics);
                visits++;
                cellsRemaining--;
                if (block != null) {
                    int size = block.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                    if (size > BLOCK_BYTES_PER_PAGE) throw new BridgeException("result_too_large", "One compact scan block exceeded the page byte budget.");
                    if (bytes + size > BLOCK_BYTES_PER_PAGE) { stop = "byte_limit"; break; }
                    bytes += size;
                    blocks.add(block);
                    next.nonAir++;
                } else next.air++;
                next.cell++;
                next.examined++;
            }
            if (next.cell > cellBefore) spans.add(group.span(cellBefore, next.cell, offsetBefore, next.examined, "observed"));
            if (next.cell == group.volume) next.advanceGroup();
            else break;
        }
        } catch (RuntimeException | LinkageError failure) {
            nanosRemaining = Math.max(0, nanosRemaining - Math.max(0, clock.getAsLong() - started));
            if (!continuation) states.remove(oldCursor);
            throw failure;
        }
        long finished = clock.getAsLong();
        long elapsed = Math.max(0, finished - started);
        nanosRemaining = Math.max(0, nanosRemaining - elapsed);
        next.touched = finished;
        next.pages++;
        next.scanNanos += elapsed;
        String nextCursor = next.complete() ? null : UUID.randomUUID().toString();
        JsonObject result = next.result(access.source());
        result.add("blocks", blocks);
        result.add("visitedSpans", spans);
        result.addProperty("complete", next.complete());
        result.addProperty("requestedRegionFullyObserved", next.complete() && next.unloaded == 0 && next.requestedVolume == next.volume);
        result.addProperty("truncated", !next.complete());
        result.add("nextCursor", nextCursor == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(nextCursor));
        result.addProperty("stopReason", next.complete() ? "complete" : stop);
        JsonObject page = coverage(next.examined - before, next.air - airBefore, next.unloaded - unloadedBefore, next.nonAir - nonAirBefore);
        page.addProperty("startOffset", before);
        page.addProperty("endOffsetExclusive", next.examined);
        page.addProperty("cellVisits", visits);
        page.addProperty("sectionProbes", probes);
        page.addProperty("blockBytes", bytes);
        result.add("page", page);
        JsonObject timing = new JsonObject();
        timing.addProperty("elapsedNanos", elapsed);
        timing.addProperty("scanNanos", next.scanNanos);
        timing.addProperty("ageMillis", Math.max(0, finished - next.created) / 1000000L);
        timing.addProperty("pageNumber", next.pages);
        timing.addProperty("startWorldTick", original.startWorldTick);
        timing.addProperty("endWorldTick", access.tick());
        result.add("timing", timing);
        // Old fields are page counts, retained for callers of the original small-radius scan.
        result.addProperty("examined", next.examined - before);
        result.addProperty("unloaded", next.unloaded - unloadedBefore);
        original.inflight = true;
        original.touched = finished;
        pending.put(control, new Pending(oldCursor, nextCursor, original, next, !continuation));
        return result;
    }

    private void expire(long now) {
        Iterator<Map.Entry<String, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (now - state.touched >= IDLE_NANOS) iterator.remove();
        }
        Iterator<Map.Entry<GameQueue.Control, Pending>> transactions = pending.entrySet().iterator();
        while (transactions.hasNext()) if (!states.containsKey(transactions.next().getValue().oldCursor)) transactions.remove();
    }

    private static JsonObject coverage(long examined, long air, long unloaded, long nonAir) {
        JsonObject result = new JsonObject();
        result.addProperty("examined", examined);
        result.addProperty("air", air);
        result.addProperty("unloaded", unloaded);
        result.addProperty("nonAir", nonAir);
        result.addProperty("loaded", examined - unloaded);
        return result;
    }

    /** Public material flags only; no collision, neighboring-block or tile getters. */
    static JsonObject publicMaterial(Material material) {
        if (material == null) throw new BridgeException("unsupported", "The block exposes no public material.");
        JsonObject result = new JsonObject();
        result.addProperty("solid", material.isSolid()); result.addProperty("liquid", material.isLiquid());
        result.addProperty("replaceable", material.isReplaceable());
        return result;
    }

    enum AirSemantics {
        BLOCK_IS_AIR("block_is_air"), MINECRAFT_AIR("minecraft_air");
        final String value;
        AirSemantics(String value) { this.value = value; }
        static AirSemantics read(JsonObject args) {
            if (!args.has("airSemantics")) return BLOCK_IS_AIR;
            String value = JsonData.string(args, "airSemantics");
            for (AirSemantics semantics : values()) if (semantics.value.equals(value)) return semantics;
            throw new BridgeException("invalid_args", "airSemantics must be block_is_air or minecraft_air.");
        }
        boolean isAir(Block block, World world, int x, int y, int z) {
            return this == MINECRAFT_AIR ? "minecraft:air".equals(Block.blockRegistry.getNameForObject(block)) : block.isAir(world, x, y, z);
        }
        int section(ExtendedBlockStorage storage) {
            if (storage != null && !storage.isEmpty()) return PRESENT;
            // Native storage counts block != Blocks.air, not Block.isAir; null storage reads as Blocks.air.
            // Only use that shortcut for literal air after checking this actual registered identity.
            if (this == MINECRAFT_AIR && !"minecraft:air".equals(Block.blockRegistry.getNameForObject(Blocks.air))) return PRESENT;
            return EMPTY;
        }
    }

    interface Access {
        Object identity();
        int dimension();
        String source();
        long tick();
        int section(int chunkX, int sectionY, int chunkZ, AirSemantics airSemantics);
        /** Null is known air under the saved semantics. Called only inside a loaded section on its owning thread. */
        JsonObject block(int x, int y, int z, AirSemantics airSemantics);
    }

    public static final class MinecraftAccess implements Access {
        private final World world;
        private Chunk current;
        private final WorldBlockCache.Reader cache;
        public MinecraftAccess(World world) { this(world, null); }
        public MinecraftAccess(World world, net.minecraft.entity.player.EntityPlayer player) {
            this.world = world; cache = player == null ? null : WorldObservationsAdapter.cacheReader(world, player);
        }
        public Object identity() { return world; }
        public int dimension() { return world.provider.dimensionId; }
        public String source() { return world.isRemote ? "client" : "integrated_server"; }
        public long tick() { return world.getTotalWorldTime(); }
        public int section(int cx, int sy, int cz) { return section(cx, sy, cz, AirSemantics.BLOCK_IS_AIR); }
        public int section(int cx, int sy, int cz, AirSemantics airSemantics) {
            IChunkProvider provider = world.getChunkProvider();
            // Vanilla's CLIENT chunkExists always returns true; its provideChunk returns an EmptyChunk without loading.
            if (world.isRemote) current = provider.provideChunk(cx, cz);
            else if (provider instanceof ChunkProviderServer && provider.chunkExists(cx, cz)) current = provider.provideChunk(cx, cz);
            else { current = null; return UNKNOWN; }
            if (current == null || current instanceof EmptyChunk || !current.isChunkLoaded || current.xPosition != cx || current.zPosition != cz) return UNKNOWN;
            ExtendedBlockStorage[] sections = current.getBlockStorageArray();
            return airSemantics.section(sections[sy]);
        }
        public JsonObject block(int x, int y, int z, AirSemantics airSemantics) {
            WorldBlockCache.Sample cached = cache == null ? null : cache.observe(current, x, y, z);
            Block block = cached == null ? current.getBlock(x & 15, y, z & 15) : cached.block;
            if (airSemantics.isAir(block, world, x, y, z)) return null;
            JsonObject result = new JsonObject();
            result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
            result.addProperty("registryName", cached == null ? JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 256) : cached.registryName);
            result.addProperty("metadata", cached == null ? current.getBlockMetadata(x & 15, y, z & 15) : cached.metadata);
            result.add("publicMaterial", publicMaterial(block.getMaterial()));
            // getTileEntity can lazily CREATE a TE. Only inspect the already existing public map.
            Object tile = current.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
            if (tile instanceof TileEntity) result.addProperty("tileClass", JsonData.bounded(tile.getClass().getName(), 256));
            return result;
        }
    }

    private static final class Group {
        final int cx, sy, cz, x, y, z, width, height, depth, volume;
        Group(State state) {
            int index = state.group;
            sy = state.minSection + index % state.sections;
            index /= state.sections;
            cz = state.minChunkZ + index % state.chunksZ;
            cx = state.minChunkX + index / state.chunksZ;
            x = Math.max(state.minX, cx * 16); y = Math.max(state.minY, sy * 16); z = Math.max(state.minZ, cz * 16);
            width = Math.min(state.maxX, cx * 16 + 15) - x + 1;
            height = Math.min(state.maxY, sy * 16 + 15) - y + 1;
            depth = Math.min(state.maxZ, cz * 16 + 15) - z + 1;
            volume = width * height * depth;
        }
        int[] point(int cell) { return new int[] {x + cell / (height * depth), y + cell / depth % height, z + cell % depth}; }
        JsonObject span(int from, int to, long offsetFrom, long offsetTo, String kind) {
            JsonObject result = new JsonObject();
            result.addProperty("chunkX", cx); result.addProperty("sectionY", sy); result.addProperty("chunkZ", cz);
            result.addProperty("minX", x); result.addProperty("minY", y); result.addProperty("minZ", z);
            result.addProperty("maxX", x + width - 1); result.addProperty("maxY", y + height - 1); result.addProperty("maxZ", z + depth - 1);
            result.addProperty("cellStart", from); result.addProperty("cellEndExclusive", to);
            result.addProperty("startOffset", offsetFrom); result.addProperty("endOffsetExclusive", offsetTo);
            result.addProperty("kind", kind);
            return result;
        }
    }

    private static final class State {
        final Object world;
        final long epoch, created, startWorldTick;
        final int dimension, minX, minY, minZ, maxX, maxY, maxZ;
        final ScanRegion region;
        final AirSemantics airSemantics;
        final int minChunkX, minChunkZ, minSection, chunksZ, sections, groups;
        final long requestedVolume, volume;
        int group, cell, pages;
        long examined, air, unloaded, nonAir, touched, scanNanos;
        boolean inflight;
        State(Access access, long epoch, ScanRegion region, AirSemantics airSemantics, long now) {
            world = access.identity(); dimension = access.dimension(); this.epoch = epoch; created = now; touched = now; startWorldTick = access.tick();
            this.region = region;
            this.airSemantics = airSemantics;
            minX = region.minX; minY = region.minY; minZ = region.minZ;
            maxX = region.maxX; maxY = region.maxY; maxZ = region.maxZ;
            minChunkX = minX >> 4; minChunkZ = minZ >> 4; minSection = minY >> 4;
            chunksZ = (maxZ >> 4) - minChunkZ + 1; sections = (maxY >> 4) - minSection + 1;
            groups = ((maxX >> 4) - minChunkX + 1) * chunksZ * sections;
            requestedVolume = region.requestedVolume; volume = region.volume;
        }
        State(State other) {
            world = other.world; epoch = other.epoch; created = other.created; startWorldTick = other.startWorldTick; dimension = other.dimension;
            region = other.region;
            airSemantics = other.airSemantics;
            minX = other.minX; minY = other.minY; minZ = other.minZ; maxX = other.maxX; maxY = other.maxY; maxZ = other.maxZ;
            minChunkX = other.minChunkX; minChunkZ = other.minChunkZ; minSection = other.minSection;
            chunksZ = other.chunksZ; sections = other.sections; groups = other.groups;
            requestedVolume = other.requestedVolume; volume = other.volume;
            group = other.group; cell = other.cell; pages = other.pages; examined = other.examined;
            air = other.air; unloaded = other.unloaded; nonAir = other.nonAir; touched = other.touched; scanNanos = other.scanNanos;
        }
        boolean complete() { return group >= groups; }
        Group group() { return new Group(this); }
        void advanceGroup() { group++; cell = 0; }
        JsonObject result(String source) {
            JsonObject result = new JsonObject();
            result.addProperty("source", source); result.addProperty("dimension", dimension);
            result.addProperty("airSemantics", airSemantics.value);
            region.describe(result);
            result.addProperty("requestedVolume", requestedVolume); result.addProperty("scannableVolume", volume);
            result.addProperty("outOfWorld", requestedVolume - volume);
            JsonObject coverage = coverage(examined, air, unloaded, nonAir);
            coverage.addProperty("remaining", volume - examined);
            coverage.addProperty("fraction", examined / (double) volume);
            coverage.addProperty("order", "chunkX, chunkZ, sectionY, localX, localY, localZ (ascending, clipped bounds)");
            result.add("coverage", coverage);
            result.addProperty("nonAtomic", true);
            result.addProperty("observation", "Only this page was observed now. Air and unloaded counts cover visited cells; remaining cells are unknown. No chunks were loaded. Pages may observe different world ticks.");
            result.addProperty("materialScope", "publicMaterial contains only Material.isSolid/isLiquid/isReplaceable; it does not certify a collision shape, full-cube support, actual replaceability or machine acceptance.");
            return result;
        }
    }

    private static final class Pending {
        final String oldCursor, nextCursor;
        final State original, next;
        final boolean initial;
        Pending(String oldCursor, String nextCursor, State original, State next, boolean initial) {
            this.oldCursor = oldCursor; this.nextCursor = nextCursor; this.original = original; this.next = next; this.initial = initial;
        }
    }
}
