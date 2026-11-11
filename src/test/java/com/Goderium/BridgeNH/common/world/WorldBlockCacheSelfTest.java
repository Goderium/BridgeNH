package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.config.ScanSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.material.Material;
import net.minecraft.util.AxisAlignedBB;

/** Native block geometry and deterministic retention lifecycle; no client/world is started. */
public final class WorldBlockCacheSelfTest {
    private static int checks;
    private static final WorldBlockCache.Anchor ANCHOR = new WorldBlockCache.Anchor(0, 64, 0);

    public static int run() {
        liveIdentity();
        rangeAndCoordinates();
        lifecycleAndBudget();
        geometry();
        return checks;
    }

    private static Block block(String name) { return (Block) Block.blockRegistry.getObject("minecraft:" + name); }

    private static void liveIdentity() {
        AtomicLong clock = new AtomicLong();
        WorldBlockCache cache = new WorldBlockCache(clock::get);
        Object world = new String("world"), equalWorld = new String("world"), chunk = new Object();
        Block stone = block("cobblestone"), air = block("air");
        WorldBlockCache.Sample first = cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        check(first != null && first == cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0), "same live identity reuses the sample");
        WorldBlockCache.Sample changed = cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, air, 0);
        check(changed != first && changed.block == air, "block edits are visible immediately without a TTL wait");
        changed = cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 1);
        check(changed.metadata == 1 && changed != first, "metadata changes cannot reuse old state");
        check(changed != cache.observe(equalWorld, 0, chunk, ANCHOR, 0, 64, 0, stone, 1), "equal objects still have distinct world identity");
        check(changed != cache.observe(world, 1, chunk, ANCHOR, 0, 64, 0, stone, 1), "dimension participates in the key");
        WorldBlockCache.Sample replacement = cache.observe(world, 0, new Object(), ANCHOR, 0, 64, 0, stone, 1);
        check(replacement != changed, "replacement native chunk discards all prior section entries");
        clock.set(WorldBlockCache.IDLE_NANOS - 1);
        cache.observe(world, 0, chunk, ANCHOR, 1, 64, 0, stone, 0);
        check(cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, air, 0).block == air, "new cell access never hides another cell's edit");
        check(cache.status().get("finalCollisionProofCached").getAsBoolean() == false, "final collision proof is explicitly live");
    }

    private static void rangeAndCoordinates() {
        WorldBlockCache cache = new WorldBlockCache();
        Object world = new Object(), chunk = new Object();
        Block stone = block("cobblestone");
        check(cache.observe(world, 0, chunk, ANCHOR, -64, 0, 64, stone, 0) != null, "cache includes all configured range endpoints");
        check(cache.observe(world, 0, chunk, ANCHOR, -65, 64, 0, stone, 0) == null, "negative horizontal outside range bypasses retention");
        check(cache.observe(world, 0, chunk, ANCHOR, 0, 129, 0, stone, 0) == null, "vertical outside range bypasses retention");
        check(cache.observe(world, 0, chunk, ANCHOR, 0, -1, 0, stone, 0) == null, "negative world height is not cached");
        check(cache.observe(world, 0, chunk, new WorldBlockCache.Anchor(0, 255, 0), 0, 256, 0, stone, 0) == null, "above world height is not cached");
        check(cache.observe(world, 0, chunk, null, 0, 64, 0, stone, 0) == null, "unbound other dimension reads do not retain data");
        WorldBlockCache.Sample negative = cache.observe(world, 0, chunk, ANCHOR, -1, 64, -1, stone, 0);
        check(negative != cache.observe(world, 0, chunk, ANCHOR, 15, 64, 15, stone, 0), "negative chunk coordinates do not alias positive cells");
        check(negative == cache.observe(world, 0, chunk, ANCHOR, -1, 64, -1, stone, 0), "negative section indexing is stable");
        cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        check(negative != cache.observe(world, 0, chunk, new WorldBlockCache.Anchor(0, 65, 0), -1, 65, -1, stone, 0), "each Y cell has an independent identity");
    }

    private static void lifecycleAndBudget() {
        AtomicLong clock = new AtomicLong();
        WorldBlockCache cache = new WorldBlockCache(clock::get);
        ScanSettings settings = new ScanSettings(1, 1, 32, 32, 64);
        cache.configure(settings);
        check(cache.status().get("sections").getAsInt() == 0, "large cache config does not preallocate sections");
        Object world = new Object(), chunk = new Object();
        Block stone = block("cobblestone");
        int capacity = cache.status().get("maximumSections").getAsInt();
        WorldBlockCache.Sample oldest = cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        for (int i = 1; i < capacity; i++) cache.observe(world, 0, chunk, ANCHOR, i % 16 * 16, 64, i / 16 * 16, stone, 0);
        check(cache.status().get("sections").getAsInt() == capacity, "section charge respects the configured capacity");
        check(oldest == cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0), "access refreshes LRU position");
        cache.observe(world, 0, chunk, ANCHOR, -16, 64, 0, stone, 0);
        check(oldest == cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0), "LRU eviction preserves the recently used section");
        check(cache.status().get("referenceSlotAndEntryByteEstimate").getAsLong() <= 64L * 1024 * 1024, "conservative retention charge remains below budget");
        WorldBlockCache.Sample recent = cache.observe(world, 0, chunk, ANCHOR, -16, 64, 0, stone, 0);
        clock.set(WorldBlockCache.IDLE_NANOS);
        check(recent != cache.observe(world, 0, chunk, ANCHOR, -16, 64, 0, stone, 0), "first read at idle expiry cannot resurrect old entries");
        cache.clear("test");
        cache.configure(new ScanSettings(1, 1, 32, 32, 128));
        for (int i = 0; i < 70; i++) cache.observe(world, 0, chunk, ANCHOR, i % 16 * 16, 64, i / 16 * 16, stone, 0);
        clock.addAndGet(WorldBlockCache.IDLE_NANOS);
        cache.maintain();
        check(cache.status().get("sections").getAsInt() == 6, "one tick clears at most 64 expired sections");
        cache.maintain();
        check(cache.status().get("sections").getAsInt() == 0, "idle cleanup eventually releases every section");
        Object anotherWorld = new Object();
        cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        cache.observe(world, 0, chunk, ANCHOR, 0, 80, 0, stone, 0);
        cache.observe(anotherWorld, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        cache.unload(world, 0, 0);
        check(cache.status().get("sections").getAsInt() == 1, "chunk unload drops every vertical section only for its owner");
        cache.unload(anotherWorld, null, null);
        check(cache.status().get("sections").getAsInt() == 0, "world unload releases all its native references");
        cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        cache.configure(new ScanSettings(1, 1, 1, 1, 64));
        check(cache.status().get("sections").getAsInt() == 0 && cache.status().get("radiusBlocks").getAsInt() == 16, "shrinking cache settings clears incompatible retained entries");
        cache.observe(world, 0, chunk, ANCHOR, 0, 64, 0, stone, 0);
        cache.configure(new ScanSettings(1, 1, 1, 1, 64));
        check(cache.status().get("sections").getAsInt() == 1, "unchanged cache settings preserve useful entries");
    }

    private static void geometry() {
        Block stone = block("cobblestone"), air = block("air");
        check(stone.getClass() == Block.class && air.getClass() == BlockAir.class, "native fixtures exercise exact audited geometry classes");
        double[] original = {stone.getBlockBoundsMinX(), stone.getBlockBoundsMinY(), stone.getBlockBoundsMinZ(),
                stone.getBlockBoundsMaxX(), stone.getBlockBoundsMaxY(), stone.getBlockBoundsMaxZ()};
        try {
            WorldBlockCache.Sample sample = new WorldBlockCache.Sample(stone, 0);
            compareNative(sample, -1, 64, -1, AxisAlignedBB.getBoundingBox(-1.1, 64.1, -1.1, .1, 66, .1));
            stone.setBlockBounds(.1f, .2f, .3f, .7f, .8f, .9f);
            compareNative(sample, -1, 64, -1, AxisAlignedBB.getBoundingBox(-1.1, 64.1, -1.1, .1, 66, .1));
            compareNative(sample, 0, 64, 0, AxisAlignedBB.getBoundingBox(1, 64, 1, 2, 66, 2));
            compareNative(new WorldBlockCache.Sample(air, 0), 0, 64, 0, AxisAlignedBB.getBoundingBox(0, 64, 0, 1, 66, 1));
            List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
            AxisAlignedBB body = AxisAlignedBB.getBoundingBox(0, 64, 0, 1, 66, 1);
            check(!new WorldBlockCache.Sample(new DynamicBlock(), 0).appendGeometry(0, 64, 0, body, body, true, boxes, boxes), "overridden mod collision remains on the live native path");
            check(!new WorldBlockCache.Sample(new DynamicAir(), 0).appendGeometry(0, 64, 0, body, body, true, boxes, boxes), "custom air subclasses cannot bypass native collision");
        } finally {
            stone.setBlockBounds((float) original[0], (float) original[1], (float) original[2], (float) original[3], (float) original[4], (float) original[5]);
        }
    }

    private static void compareNative(WorldBlockCache.Sample sample, int x, int y, int z, AxisAlignedBB body) {
        AxisAlignedBB support = AxisAlignedBB.getBoundingBox(x, y + .7, z, x + 1, y + 1.05, z + 1);
        List<AxisAlignedBB> expected = new ArrayList<AxisAlignedBB>(), expectedSupport = new ArrayList<AxisAlignedBB>();
        List<AxisAlignedBB> actual = new ArrayList<AxisAlignedBB>(), actualSupport = new ArrayList<AxisAlignedBB>();
        sample.block.addCollisionBoxesToList(null, x, y, z, body, expected, null);
        sample.block.addCollisionBoxesToList(null, x, y, z, support, expectedSupport, null);
        check(sample.appendGeometry(x, y, z, body, support, true, actual, actualSupport), "audited class provides geometry");
        check(sameBoxes(expected, actual) && sameBoxes(expectedSupport, actualSupport), "cached base geometry matches native collision and support after bounds changes");
    }

    private static boolean sameBoxes(List<AxisAlignedBB> first, List<AxisAlignedBB> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            AxisAlignedBB a = first.get(i), b = second.get(i);
            if (a.minX != b.minX || a.minY != b.minY || a.minZ != b.minZ || a.maxX != b.maxX || a.maxY != b.maxY || a.maxZ != b.maxZ) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static final class DynamicBlock extends Block { DynamicBlock() { super(Material.rock); } }
    private static final class DynamicAir extends BlockAir { }
}
