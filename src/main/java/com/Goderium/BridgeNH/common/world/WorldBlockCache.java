package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

/** Lazy section LRU. Every hit verifies live block/meta and the native chunk identity. */
@com.google.common.annotations.VisibleForTesting
public final class WorldBlockCache {
    static final long IDLE_NANOS = 300000000000L;
    // Conservative charge for reference slots plus every possible Sample in a full section.
    // This is retention accounting, not a measurement of the JVM or native world chunk graph.
    static final long SECTION_BYTES = 4096L * 256 + 512;
    private final LongSupplier clock;
    private final LinkedHashMap<Key, Section> sections = new LinkedHashMap<Key, Section>(64, .75f, true);
    private int horizontal = ScanSettings.DEFAULT_CACHE_RADIUS_CHUNKS, vertical = ScanSettings.DEFAULT_CACHE_RADIUS_CHUNKS;
    private long memoryBudget = ScanSettings.DEFAULT_CACHE_MAX_MIB * 1024L * 1024L;
    private long hits, misses, bypasses, evictions, invalidations, geometryHits;
    private String lastInvalidation = "startup";
    static final int WARM_CELLS_PER_TICK=512;
    static final long WARM_NANOS=500000L;
    private Object warmWorld;
    private int warmX,warmY,warmZ,warmSection,warmCell;
    private long warmReads,warmSkipped,warmPasses;

    WorldBlockCache() { this(System::nanoTime); }
    WorldBlockCache(LongSupplier clock) { this.clock = clock; }

    synchronized void configure(ScanSettings settings) {
        int nextHorizontal = settings.cacheHorizontalChunks(), nextVertical = settings.cacheVerticalChunks();
        long nextBudget = settings.cacheMaxMiB() * 1024L * 1024L;
        if (horizontal == nextHorizontal && vertical == nextVertical && memoryBudget == nextBudget) return;
        horizontal = nextHorizontal; vertical = nextVertical; memoryBudget = nextBudget;
        clear("configuration_changed");
    }

    synchronized void clear(String reason) {
        sections.clear(); invalidations++; lastInvalidation = reason;
        warmWorld=null; warmSection=0; warmCell=0;
    }

    synchronized void unload(Object world, Integer chunkX, Integer chunkZ) {
        if(warmWorld==world) { warmWorld=null; warmSection=warmCell=0; }
        Iterator<Map.Entry<Key, Section>> iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            Key key = iterator.next().getKey();
            if (key.world == world && (chunkX == null || key.x == chunkX && key.z == chunkZ)) iterator.remove();
        }
        invalidations++; lastInvalidation = chunkX == null ? "world_unloaded" : "chunk_unloaded";
    }

    /** At most 64 idle removals per tick; no world reads or full-volume scan. */
    synchronized void maintain() {
        long now = clock.getAsLong();
        Iterator<Section> iterator = sections.values().iterator();
        for (int visits = 0; visits < 64 && iterator.hasNext(); visits++) {
            if (now - iterator.next().touched < IDLE_NANOS) break;
            iterator.remove(); evictions++; lastInvalidation = "idle_expired";
        }
    }

    /** Caller supplies fresh block/meta from this already loaded chunk on its owning thread. */
    synchronized Sample observe(Object world, int dimension, Object chunk, Anchor anchor,
            int x, int y, int z, Block block, int metadata) {
        if (world == null || chunk == null || block == null || anchor == null || y < 0 || y > 255
                || x < WorldScan.WORLD_MIN || x > WorldScan.WORLD_MAX || z < WorldScan.WORLD_MIN || z > WorldScan.WORLD_MAX
                || Math.abs((long) x - anchor.x) > horizontal * 16L || Math.abs((long) z - anchor.z) > horizontal * 16L
                || Math.abs((long) y - anchor.y) > vertical * 16L) {
            bypasses++; return null;
        }
        Key key = new Key(world, dimension, x >> 4, y >> 4, z >> 4);
        Section section = sections.get(key);
        long now = clock.getAsLong();
        if (section != null && (section.chunk != chunk || now - section.touched >= IDLE_NANOS)) {
            sections.remove(key); section = null; invalidations++; lastInvalidation = "section_owner_changed_or_expired";
        }
        if (section == null) {
            while ((sections.size() + 1L) * SECTION_BYTES > memoryBudget && !sections.isEmpty()) {
                sections.remove(sections.keySet().iterator().next()); evictions++;
            }
            if (SECTION_BYTES > memoryBudget) { bypasses++; return null; }
            section = new Section(chunk); sections.put(key, section);
        }
        section.touched = now;
        int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        Sample value = section.cells[index];
        if (value != null && value.block == block && value.metadata == metadata) { hits++; return value; }
        if (value != null) { invalidations++; lastInvalidation = "live_block_or_metadata_changed"; }
        value = new Sample(block, metadata); section.cells[index] = value; misses++;
        return value;
    }

    Reader reader(World world, EntityPlayer player) {
        return new Reader(this, world, player != null && player.worldObj == world && player.boundingBox != null
                ? new Anchor((int) Math.floor(player.posX), (int) Math.floor(player.boundingBox.minY), (int) Math.floor(player.posZ)) : null);
    }
    /** Opportunistic local-only preparation. Neither the scan-return radius nor chunk loading changes. */
    void warm(World world,EntityPlayer player) {
        if(world==null || player==null || player.worldObj!=world || player.boundingBox==null) return;
        Reader access=reader(world,player); Anchor anchor=access.anchor;
        int cx=anchor.x>>4,cy=anchor.y>>4,cz=anchor.z>>4;
        if(warmWorld!=world || warmX!=cx || warmY!=cy || warmZ!=cz) { warmWorld=world; warmX=cx; warmY=cy; warmZ=cz; warmSection=warmCell=0; }
        int side=horizontal*2+1,ys=vertical*2+1,total=side*side*ys;
        long deadline=System.nanoTime()+WARM_NANOS;
        for(int cells=0,skipped=0;cells<WARM_CELLS_PER_TICK && skipped<16 && System.nanoTime()<deadline;) {
            if(warmSection>=total) { warmSection=warmCell=0; warmPasses++; break; }
            int sx=cx+centered(warmSection%side),sz=cz+centered(warmSection/side%side),sy=cy+centered(warmSection/(side*side));
            Chunk chunk=sy<0 || sy>15 ? null : loadedChunk(world,sx,sz);
            if(chunk==null) { warmSection++; warmCell=0; warmSkipped++; skipped++; continue; }
            int x=(sx<<4)+(warmCell&15),y=(sy<<4)+(warmCell>>8),z=(sz<<4)+(warmCell>>4&15);
            access.observe(chunk,x,y,z); warmReads++; cells++;
            if(++warmCell==4096) { warmCell=0; warmSection++; }
        }
    }
    @com.google.common.annotations.VisibleForTesting
    public static int centered(int index) { return index==0 ? 0 : (index+1)/2*(index%2==1 ? 1 : -1); }

    synchronized JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("radiusChunks", horizontal); result.addProperty("verticalRadiusChunks", vertical);
        result.addProperty("radiusBlocks", horizontal * 16); result.addProperty("verticalRadiusBlocks", vertical * 16);
        result.addProperty("sections", sections.size());
        result.addProperty("referenceSlotAndEntryByteEstimate", sections.size() * SECTION_BYTES);
        result.addProperty("memoryBudgetBytes", memoryBudget); result.addProperty("maximumSections", memoryBudget / SECTION_BYTES);
        result.addProperty("hits", hits); result.addProperty("misses", misses); result.addProperty("bypasses", bypasses);
        result.addProperty("capacityOrIdleEvictions", evictions); result.addProperty("invalidations", invalidations);
        result.addProperty("stableGeometryHits", geometryHits); result.addProperty("lastInvalidation", lastInvalidation);
        result.addProperty("idleMillis", IDLE_NANOS / 1000000L);
        result.addProperty("identityVerifiedEveryRead", true); result.addProperty("loadedOnly", true);
        result.addProperty("dynamicEntitiesCached", false); result.addProperty("tileContentsCached", false);
        result.addProperty("finalCollisionProofCached", false);
        result.addProperty("warmReads",warmReads); result.addProperty("warmSkippedSections",warmSkipped); result.addProperty("warmPasses",warmPasses);
        result.addProperty("warmCellsPerTick",WARM_CELLS_PER_TICK); result.addProperty("warmBudgetNanos",WARM_NANOS);
        return result;
    }

    synchronized void geometryHit() { geometryHits++; }

    static final class Anchor {
        final int x, y, z;
        Anchor(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class Sample {
        final Block block;
        final int metadata;
        final String registryName;
        private final boolean baseShape, airShape;
        private double minX, minY, minZ, maxX, maxY, maxZ;
        Sample(Block block, int metadata) {
            this.block = block; this.metadata = metadata;
            registryName = JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 256);
            // Exact classes only: overridden mod/slab/fence/door/TE collisions always remain live.
            baseShape = block.getClass() == Block.class; airShape = block.getClass() == BlockAir.class;
            if (baseShape) captureBounds();
        }

        private void captureBounds() {
            minX = block.getBlockBoundsMinX(); minY = block.getBlockBoundsMinY(); minZ = block.getBlockBoundsMinZ();
            maxX = block.getBlockBoundsMaxX(); maxY = block.getBlockBoundsMaxY(); maxZ = block.getBlockBoundsMaxZ();
        }

        /** Planner only; all six current native bounds must match before reusing the base Block box. */
        boolean appendGeometry(int x, int y, int z, AxisAlignedBB body, AxisAlignedBB support, boolean needSupport,
                List<AxisAlignedBB> collisions, List<AxisAlignedBB> supports) {
            if (airShape) return true;
            if (!baseShape) return false;
            if (minX != block.getBlockBoundsMinX() || minY != block.getBlockBoundsMinY() || minZ != block.getBlockBoundsMinZ()
                    || maxX != block.getBlockBoundsMaxX() || maxY != block.getBlockBoundsMaxY() || maxZ != block.getBlockBoundsMaxZ()) captureBounds();
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
            if (body.intersectsWith(box)) collisions.add(box);
            if (needSupport && support.intersectsWith(box)) supports.add(box);
            return true;
        }
    }

    /** One query's native chunk handle, discarded at the end of the owning-thread observation. */
    static final class Reader {
        final WorldBlockCache cache;
        final World world;
        final Anchor anchor;
        private Chunk current;
        Reader(WorldBlockCache cache, World world, Anchor anchor) { this.cache = cache; this.world = world; this.anchor = anchor; }
        Sample read(int x, int y, int z) {
            if (world == null || anchor == null || y < 0 || y > 255) return null;
            if (current == null || current.xPosition != (x >> 4) || current.zPosition != (z >> 4) || !current.isChunkLoaded)
                current = loadedChunk(world, x >> 4, z >> 4);
            if (current == null) return null;
            return observe(current, x, y, z);
        }
        Sample observe(Chunk chunk, int x, int y, int z) {
            if (anchor == null || chunk == null || chunk instanceof EmptyChunk || !chunk.isChunkLoaded
                    || chunk.xPosition != (x >> 4) || chunk.zPosition != (z >> 4) || y < 0 || y > 255) return null;
            return cache.observe(world, world.provider.dimensionId, chunk, anchor, x, y, z,
                    chunk.getBlock(x & 15, y, z & 15), chunk.getBlockMetadata(x & 15, y, z & 15));
        }
    }

    private static final class Section {
        final Object chunk;
        final Sample[] cells = new Sample[4096];
        long touched;
        Section(Object chunk) { this.chunk = chunk; }
    }

    private static final class Key {
        final Object world;
        final int dimension, x, y, z;
        Key(Object world, int dimension, int x, int y, int z) { this.world = world; this.dimension = dimension; this.x = x; this.y = y; this.z = z; }
        @Override public int hashCode() { return (((System.identityHashCode(world) * 31 + dimension) * 31 + x) * 31 + y) * 31 + z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return world == key.world && dimension == key.dimension && x == key.x && y == key.y && z == key.z;
        }
    }

    private static Chunk loadedChunk(World world, int x, int z) {
        IChunkProvider provider = world.getChunkProvider();
        Chunk chunk = null;
        if (provider instanceof ChunkProviderClient) chunk = provider.provideChunk(x, z);
        else if (provider instanceof ChunkProviderServer && provider.chunkExists(x, z)) chunk = provider.provideChunk(x, z);
        return chunk == null || chunk instanceof EmptyChunk || !chunk.isChunkLoaded || chunk.xPosition != x || chunk.zPosition != z ? null : chunk;
    }
}
