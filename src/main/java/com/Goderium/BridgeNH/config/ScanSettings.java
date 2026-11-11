package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.Map;

/** Forge startup scan/cache defaults with memory-only runtime overrides. */
public final class ScanSettings {
    public static final int MAX_CHUNKS = 8;
    public static final int MAX_CACHE_CHUNKS = 32;
    public static final int DEFAULT_RADIUS_CHUNKS = 1;
    public static final int DEFAULT_CACHE_RADIUS_CHUNKS = 4;
    public static final int DEFAULT_CACHE_MAX_MIB = 2048;
    public static final int MAX_CACHE_MIB = 16384;
    private int horizontal = DEFAULT_RADIUS_CHUNKS;
    private int vertical = DEFAULT_RADIUS_CHUNKS;
    private int cacheHorizontal = DEFAULT_CACHE_RADIUS_CHUNKS;
    private int cacheVertical = DEFAULT_CACHE_RADIUS_CHUNKS;
    private int cacheMiB = DEFAULT_CACHE_MAX_MIB;
    private long revision;

    public ScanSettings() { }

    ScanSettings(int radiusChunks, int verticalRadiusChunks, int cacheRadiusChunks, int cacheVerticalRadiusChunks) {
        this(radiusChunks, verticalRadiusChunks, cacheRadiusChunks, cacheVerticalRadiusChunks, DEFAULT_CACHE_MAX_MIB);
    }

    public ScanSettings(int radiusChunks, int verticalRadiusChunks, int cacheRadiusChunks, int cacheVerticalRadiusChunks, int cacheMaxMiB) {
        JsonObject args = new JsonObject();
        args.addProperty("radiusChunks", radiusChunks); args.addProperty("verticalRadiusChunks", verticalRadiusChunks);
        args.addProperty("cacheRadiusChunks", cacheRadiusChunks); args.addProperty("cacheVerticalRadiusChunks", cacheVerticalRadiusChunks);
        args.addProperty("cacheMaxMiB", cacheMaxMiB); apply(args); revision = 0;
    }

    public synchronized JsonObject apply(JsonObject args) {
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.entrySet())
            if (!"radiusChunks".equals(entry.getKey()) && !"verticalRadiusChunks".equals(entry.getKey())
                    && !"cacheRadiusChunks".equals(entry.getKey()) && !"cacheVerticalRadiusChunks".equals(entry.getKey())
                    && !"cacheMaxMiB".equals(entry.getKey()))
                throw new BridgeException("invalid_args", "scan_config accepts scan/cache chunk radii and cacheMaxMiB.");
        int nextHorizontal = args.has("radiusChunks") ? JsonData.integer(args, "radiusChunks", 1, MAX_CHUNKS) : horizontal;
        int nextVertical = args.has("verticalRadiusChunks") ? JsonData.integer(args, "verticalRadiusChunks", 1, MAX_CHUNKS) : vertical;
        int nextCacheHorizontal = args.has("cacheRadiusChunks") ? JsonData.integer(args, "cacheRadiusChunks", 1, MAX_CACHE_CHUNKS) : cacheHorizontal;
        int nextCacheVertical = args.has("cacheVerticalRadiusChunks") ? JsonData.integer(args, "cacheVerticalRadiusChunks", 1, MAX_CACHE_CHUNKS) : cacheVertical;
        int nextMiB = args.has("cacheMaxMiB") ? JsonData.integer(args, "cacheMaxMiB", 64, MAX_CACHE_MIB) : cacheMiB;
        if (horizontal != nextHorizontal || vertical != nextVertical || cacheHorizontal != nextCacheHorizontal || cacheVertical != nextCacheVertical || cacheMiB != nextMiB) {
            horizontal = nextHorizontal;
            vertical = nextVertical;
            cacheHorizontal = nextCacheHorizontal;
            cacheVertical = nextCacheVertical;
            cacheMiB = nextMiB;
            revision++;
        }
        return inspect();
    }

    public synchronized JsonObject inspect() {
        JsonObject result = new JsonObject();
        result.addProperty("radiusChunks", horizontal);
        result.addProperty("verticalRadiusChunks", vertical);
        result.addProperty("radiusBlocks", horizontal * 16);
        result.addProperty("verticalRadiusBlocks", vertical * 16);
        result.addProperty("cacheRadiusChunks", cacheHorizontal);
        result.addProperty("cacheVerticalRadiusChunks", cacheVertical);
        result.addProperty("cacheRadiusBlocks", cacheHorizontal * 16);
        result.addProperty("cacheVerticalRadiusBlocks", cacheVertical * 16);
        result.addProperty("cacheChunkCapacity", (cacheHorizontal * 2 + 1) * (cacheHorizontal * 2 + 1));
        result.addProperty("cacheMaxMiB", cacheMiB);
        result.addProperty("maxCacheRadiusChunks", MAX_CACHE_CHUNKS);
        result.addProperty("maxCacheMiB", MAX_CACHE_MIB);
        result.addProperty("maxRadiusChunks", MAX_CHUNKS);
        result.addProperty("cachePolicy", "loaded_section_lru_live_identity_validation");
        result.addProperty("startupConfigCategory", "scan");
        result.addProperty("memoryOnly", true);
        result.addProperty("revision", revision);
        return result;
    }

    public synchronized long revision() { return revision; }

    public synchronized int[] radii(JsonObject args) {
        if (args.has("radius")) {
            if (args.has("radiusChunks") || args.has("verticalRadiusChunks"))
                throw new BridgeException("invalid_args", "radius cannot be combined with chunk radii.");
            int blocks = JsonData.integer(args, "radius", 0, MAX_CHUNKS * 16);
            return new int[] {blocks, blocks};
        }
        return new int[] {
                (args.has("radiusChunks") ? JsonData.integer(args, "radiusChunks", 1, MAX_CHUNKS) : horizontal) * 16,
                (args.has("verticalRadiusChunks") ? JsonData.integer(args, "verticalRadiusChunks", 1, MAX_CHUNKS) : vertical) * 16};
    }

    @com.google.common.annotations.VisibleForTesting
    public synchronized int cacheHorizontalBlocks() { return cacheHorizontal * 16; }

    @com.google.common.annotations.VisibleForTesting
    public synchronized int cacheVerticalBlocks() { return cacheVertical * 16; }

    public synchronized int cacheHorizontalChunks() { return cacheHorizontal; }

    public synchronized int cacheVerticalChunks() { return cacheVertical; }

    public synchronized int cacheMaxMiB() { return cacheMiB; }
}
