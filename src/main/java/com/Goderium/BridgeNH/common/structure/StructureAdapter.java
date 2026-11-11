package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.navigation.SchematicGoal;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

/** Public StructureLib planning data. Never invokes structure checking or placement. */
public class StructureAdapter {
    public static final int COMPILED_LIMIT = 2097152, DEFINITION_PIECE_LIMIT = 65536, PAGE_LIMIT = 128, SCAN_LIMIT = 2048, GEOMETRY_SCAN_LIMIT = 65536;
    public static final int BYTE_LIMIT = 128 * 1024, CANDIDATE_LIMIT = 16;
    public static final long SOFT_BUDGET_NANOS = 4000000L;
    static final int PLAN_CACHE_LIMIT = 16, CATALOG_CACHE_LIMIT = 8, BOUNDS_CACHE_LIMIT = 32;
    static final long CACHE_TTL_NANOS = 300000000000L, CACHE_REFERENCE_BYTE_BUDGET = 1024L * 1024 * 1024;
    private static final String PREFIX = "com.gtnewhorizon.structurelib.";
    private final List<Plan> plans = new ArrayList<Plan>();
    private final List<DefinitionCatalog> catalogs = new ArrayList<DefinitionCatalog>();
    private final List<PieceBounds> pieceBounds = new ArrayList<PieceBounds>();
    private final BlueprintObservationAdapter blueprints = new BlueprintObservationAdapter();
    private final LongSupplier clock;
    private String resourceContext = "";
    private long cacheEpoch, ttlEvictions, capacityEvictions;

    public StructureAdapter() { this(System::nanoTime); }
    StructureAdapter(LongSupplier clock) { this.clock = clock; }

    public BlueprintObservationAdapter blueprints() { return blueprints; }

    /** Release references immediately when world, profile or LAN permission context changes. */
    public synchronized void invalidate() { plans.clear(); catalogs.clear(); pieceBounds.clear(); blueprints.invalidate(); cacheEpoch++; }

    /** Client-thread maintenance releases expired owners even when no new structure query arrives. */
    public synchronized void maintain(String resources) {
        if (!resourceContext.equals(resources)) { invalidate(); resourceContext = resources; }
        expireCaches();
    }

    private void expireCaches() {
        long now = clock.getAsLong();
        for (Iterator<Plan> it = plans.iterator(); it.hasNext();) if (now - it.next().lastAccess >= CACHE_TTL_NANOS) { it.remove(); ttlEvictions++; }
        for (Iterator<DefinitionCatalog> it = catalogs.iterator(); it.hasNext();) if (now - it.next().lastAccess >= CACHE_TTL_NANOS) { it.remove(); ttlEvictions++; }
        for (Iterator<PieceBounds> it = pieceBounds.iterator(); it.hasNext();) if (now - it.next().lastAccess >= CACHE_TTL_NANOS) { it.remove(); ttlEvictions++; }
    }

    private long referenceSlots() {
        long count = 0;
        for (Plan plan : plans) count += plan.elements.length;
        for (DefinitionCatalog catalog : catalogs) count += catalog.referenceSlots;
        for (PieceBounds bounds : pieceBounds) count += bounds.elements.length;
        return count;
    }

    private void trimCaches() {
        while (plans.size() > PLAN_CACHE_LIMIT) { plans.remove(0); capacityEvictions++; }
        while (catalogs.size() > CATALOG_CACHE_LIMIT) { catalogs.remove(0); capacityEvictions++; }
        while (pieceBounds.size() > BOUNDS_CACHE_LIMIT) { pieceBounds.remove(0); capacityEvictions++; }
        while (referenceSlots() > CACHE_REFERENCE_BYTE_BUDGET / 8) {
            long plan = plans.isEmpty() ? Long.MAX_VALUE : plans.get(0).lastAccess;
            long catalog = catalogs.isEmpty() ? Long.MAX_VALUE : catalogs.get(0).lastAccess;
            long bounds = pieceBounds.isEmpty() ? Long.MAX_VALUE : pieceBounds.get(0).lastAccess;
            if (plan <= catalog && plan <= bounds) plans.remove(0);
            else if (catalog <= bounds) catalogs.remove(0);
            else pieceBounds.remove(0);
            capacityEvictions++;
        }
    }

    synchronized JsonObject cacheStatistics() {
        JsonObject result = new JsonObject();
        result.addProperty("epoch", cacheEpoch); result.addProperty("plans", plans.size()); result.addProperty("definitionCatalogs", catalogs.size()); result.addProperty("pieceBounds", pieceBounds.size());
        result.addProperty("maxPlans", PLAN_CACHE_LIMIT); result.addProperty("maxDefinitionCatalogs", CATALOG_CACHE_LIMIT); result.addProperty("maxPieceBounds", BOUNDS_CACHE_LIMIT);
        result.addProperty("ttlMillis", CACHE_TTL_NANOS / 1000000); result.addProperty("ttlEvictions", ttlEvictions); result.addProperty("capacityEvictions", capacityEvictions);
        result.addProperty("estimatedReferenceSlots", referenceSlots()); result.addProperty("referenceSlotByteEstimate", referenceSlots() * 8);
        result.addProperty("maxReferenceSlots", CACHE_REFERENCE_BYTE_BUDGET / 8); result.addProperty("maxReferenceSlotByteBudget", CACHE_REFERENCE_BYTE_BUDGET);
        long auxiliary = 0; for (PieceBounds bounds : pieceBounds) auxiliary += bounds.auxiliaryBytes();
        result.addProperty("summaryAuxiliaryByteEstimate", auxiliary); result.addProperty("maxNavigationTuplesPerSummary", PieceBounds.NAVIGATION_CACHE_LIMIT);
        result.addProperty("accounting", "Conservative 8-byte reference-slot retention charge, aliases charged per cache entry; not a JVM heap measurement or bound on the GT-owned transitive definition/world object graph. Existing compiled arrays are not copied.");
        result.addProperty("scope", "Definition navigation and bounded cursor/summary state only. World blocks, inventories, candidates and live formation remain fresh reads and are never stored by this cache.");
        result.addProperty("invalidation", "Idle TTL, resource reload/language context, explicit world/profile/LAN/session cleanup, definition identity and current public compiled-array identity.");
        return result;
    }

    /** Caller enforces developer/local non-LAN permissions and schedules this on the world thread. */
    public synchronized JsonObject inspect(World world, JsonObject args) {
        long started = System.nanoTime();
        if (args.has("registryName") || args.has("metadata")) return PrototypeStructureAdapter.inspect(this, args, started);
        RuntimeParameters runtime = runtimeParameters(args);
        int x = JsonData.integer(args, "x", -30000000, 30000000);
        int y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000);
        if (!world.blockExists(x, y, z)) throw new BridgeException("world_unavailable", "Controller position is not loaded.");
        IChunkProvider provider = world.getChunkProvider();
        if (!(provider instanceof ChunkProviderServer) || !provider.chunkExists(x >> 4, z >> 4))
            throw new BridgeException("world_unavailable", "Controller chunk is not loaded on the local server.");
        Chunk chunk = provider.provideChunk(x >> 4, z >> 4);
        if (chunk == null || !chunk.isChunkLoaded) throw new BridgeException("world_unavailable", "Controller chunk is not currently loaded.");
        Object existing = chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        TileEntity tile = existing instanceof TileEntity ? (TileEntity) existing : null;
        Class<?> base = publicBase(tile, "gregtech.api.metatileentity.BaseMetaTileEntity");
        if (base == null) throw new BridgeException("unsupported", "This position is not a GregTech controller.");
        Object meta = invoke(base, tile, "getMetaTileEntity", new Class<?>[0]);
        if (meta == null) throw new BridgeException("unsupported", "Controller has no current meta tile entity.");
        Object definition = invoke(meta.getClass(), meta, "getStructureDefinition", new Class<?>[0]);
        Object facing = null;
        try { facing = invoke(meta.getClass(), meta, "getExtendedFacing", new Class<?>[0]); }
        catch (BridgeException error) { if (!"unsupported".equals(error.code)) throw error; }
        if (GuiDataAdapter.booleanArg(args, "alignmentFrames", false)) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : args.entrySet())
                if (!Arrays.asList("x", "y", "z", "dimension", "alignmentFrames", "variant").contains(entry.getKey()))
                    throw new BridgeException("invalid_args", "alignmentFrames is an exclusive read-only controller view.");
            JsonObject frames = StructureAlignmentFrames.read(meta, facing);
            JsonObject controller = coords(x, y, z); controller.addProperty("dimension", world.provider.dimensionId);
            controller.addProperty("metaClass", meta.getClass().getName()); frames.add("controller", controller);
            frames.add("budget", budget(started));
            return frames;
        }
        JsonObject result = readPlan(world, tile, meta, definition, facing, x, y, z, args, started);
        if (GuiDataAdapter.booleanArg(args, "boundsOnly", false)) return result;
        int remainingBytes = BYTE_LIMIT - result.toString().getBytes(StandardCharsets.UTF_8).length - 256;
        result.add("runtimeObservation", blueprints.describe(world, tile, meta, definition, facing, x, y, z,
                CatalogData.text(args, "query", 128, ""), runtime.captureId, runtime.offset, runtime.floorBlockY,
                optionalInt(args, "limit", 64, 1, PAGE_LIMIT), remainingBytes));
        return result;
    }

    static RuntimeParameters runtimeParameters(JsonObject args) {
        int offset = optionalInt(args, "runtimeOffset", 0, 0, BlueprintObservationAdapter.POSITION_LIMIT);
        String captureId = present(args, "captureId") ? CatalogData.text(args, "captureId", 128, "") : null;
        if (captureId != null && captureId.isEmpty()) throw new BridgeException("invalid_args", "captureId must not be empty.");
        if (offset > 0 && captureId == null) throw new BridgeException("invalid_args", "A nonzero runtimeOffset requires the current captureId.");
        Integer floor = present(args, "floorBlockY") ? JsonData.integer(args, "floorBlockY", 0, 254) : null;
        return new RuntimeParameters(captureId, offset, floor);
    }

    static final class RuntimeParameters {
        final String captureId; final int offset; final Integer floorBlockY;
        RuntimeParameters(String captureId, int offset, Integer floorBlockY) {
            this.captureId = captureId; this.offset = offset; this.floorBlockY = floorBlockY;
        }
    }

    synchronized JsonObject readPlan(World world, Object tile, Object meta, Object definition, Object facing,
                        int x, int y, int z, JsonObject args, long started) {
        if (definition == null) throw new BridgeException("unsupported", "Controller exposes no StructureLib definition.");
        expireCaches();
        int offset = optionalInt(args, "offset", 0, 0, COMPILED_LIMIT);
        int limit = optionalInt(args, "limit", 64, 1, PAGE_LIMIT);
        int variant = optionalInt(args, "variant", 1, 1, 64);
        String query = CatalogData.text(args, "query", 128, "").toLowerCase(Locale.ROOT);
        Piece piece = piece(meta.getClass().getName(), args);
        boolean boundsOnly = GuiDataAdapter.booleanArg(args, "boundsOnly", false);
        if (boundsOnly && (piece.name.isEmpty() || offset != 0)) throw new BridgeException("invalid_args", "boundsOnly requires one exact piece and offset 0; independent summary progress does not consume a position cursor.");
        if (boundsOnly && (args.has("captureId") || args.has("runtimeOffset") || args.has("floorBlockY")))
            throw new BridgeException("invalid_args", "boundsOnly does not read runtime captures or floor planning.");
        JsonObject alignment = facing == null ? new JsonObject() : alignment(facing);
        if (piece.name.isEmpty()) return discover(world, tile, meta, definition, alignment, x, y, z, offset, limit, query, started);
        if (piece.offsets != null && facing == null)
            throw new BridgeException("unsupported", "World positions require the public ExtendedFacing alignment API.");
        Plan selected = null;
        for (Plan plan : plans) {
            if (plan.world == world && plan.tile == tile && plan.meta == meta && plan.definition == definition
                    && plan.facing.equals(alignment.toString()) && plan.x == x && plan.y == y && plan.z == z
                    && plan.variant == variant && plan.piece.equals(piece.name) && Arrays.equals(plan.offsets, piece.offsets)
                    && plan.query.equals(query)) { selected = plan; break; }
        }
        if (selected != null && !selected.currentElements()) {
            Object[] stale = selected.elements;
            for (Iterator<Plan> it = plans.iterator(); it.hasNext();) { Plan cached = it.next(); if (cached.definition == definition && cached.elements == stale) it.remove(); }
            for (Iterator<PieceBounds> it = pieceBounds.iterator(); it.hasNext();) { PieceBounds cached = it.next(); if (cached.definition == definition && cached.elements == stale) it.remove(); }
            selected = null;
        }
        if (offset == 0 && selected == null) {
            selected = new Plan(world, tile, meta, definition, facing, alignment.toString(), x, y, z, piece, variant, query);
            plans.add(selected);
        } else if (selected == null || !selected.checkpoints.containsKey(offset)) {
            throw new BridgeException("stale_cursor", "Use nextOffset from this current structure plan; restart with offset 0 after context changes or eviction.");
        } else {
            plans.remove(selected);
            plans.add(selected);
        }
        selected.lastAccess = clock.getAsLong(); trimCaches();
        Plan plan = selected;
        if (boundsOnly) return boundsSummary(plan, facing, alignment, started);
        int[] abc = plan.checkpoints.get(offset).clone();
        int index = offset, visited = 0, bytes = 4096;
        JsonArray positions = new JsonArray();
        boolean byteLimited = false;
        while (index < plan.elements.length && visited < SCAN_LIMIT && positions.size() < limit) {
            if (visited > 0 && System.nanoTime() - started >= SOFT_BUDGET_NANOS) break;
            Object element = plan.elements[index];
            int[] next = abc.clone();
            boolean navigating = (Boolean) invoke(plan.elementApi, element, "isNavigating", new Class<?>[0]);
            if (navigating) {
                next[0] = ((Boolean) invoke(plan.elementApi, element, "resetA", new Class<?>[0]) ? -baseOffset(piece.offsets, 0) : abc[0])
                        + ((Number) invoke(plan.elementApi, element, "getStepA", new Class<?>[0])).intValue();
                next[1] = ((Boolean) invoke(plan.elementApi, element, "resetB", new Class<?>[0]) ? -baseOffset(piece.offsets, 1) : abc[1])
                        + ((Number) invoke(plan.elementApi, element, "getStepB", new Class<?>[0])).intValue();
                next[2] = ((Boolean) invoke(plan.elementApi, element, "resetC", new Class<?>[0]) ? -baseOffset(piece.offsets, 2) : abc[2])
                        + ((Number) invoke(plan.elementApi, element, "getStepC", new Class<?>[0])).intValue();
            } else {
                int[] xyz = null;
                if (piece.offsets != null) {
                    xyz = new int[3];
                    invoke(facing.getClass(), facing, "getWorldOffset", new Class<?>[] {int[].class, int[].class}, abc.clone(), xyz);
                    xyz[0] += x; xyz[1] += y; xyz[2] += z;
                }
                JsonObject position = position(plan, element, xyz, abc, variant, query);
                if (matches(position, query)) {
                    int size = position.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                    if (bytes + size > BYTE_LIMIT - 4096) { byteLimited = true; break; }
                    positions.add(position);
                    bytes += size;
                }
                next[0]++; // StructureUtility.iterateV2 increments A after every non-navigation element.
            }
            abc = next;
            index++;
            visited++;
        }
        if (index < plan.elements.length && index != offset) plan.save(index, abc);
        // Preserve the position page's budget; optional geometry uses only the remaining time.
        JsonObject bounds = bounds(plan, facing, started);
        JsonObject result = new JsonObject();
        result.add("controller", controller(world, meta, x, y, z));
        result.addProperty("mode", "piece_geometry");
        result.add("definition", definitionInfo(definition));
        result.addProperty("piece", piece.name);
        result.addProperty("variant", variant);
        if (piece.offsets == null) result.add("offsets", JsonNull.INSTANCE);
        else {
            JsonObject offsets = new JsonObject();
            offsets.addProperty("a", piece.offsets[0]); offsets.addProperty("b", piece.offsets[1]); offsets.addProperty("c", piece.offsets[2]);
            result.add("offsets", offsets);
        }
        result.addProperty("coordinateSpace", piece.offsets == null ? "piece_origin" : "controller_relative");
        result.addProperty("worldPositionsAvailable", piece.offsets != null);
        result.add("alignment", alignment);
        result.addProperty("planSource", piece.source);
        result.addProperty("query", query);
        result.add("positions", positions);
        result.addProperty("offset", offset);
        result.addProperty("nextOffset", index);
        result.addProperty("hasMore", index < plan.elements.length);
        result.addProperty("visited", visited);
        result.addProperty("compiledEntries", plan.elements.length);
        result.add("pieceBounds", bounds);
        result.addProperty("byteLimited", byteLimited);
        result.addProperty("nonAtomic", true);
        JsonObject coverage = coverage();
        coverage.addProperty("geometry", piece.offsets == null
                ? "Compiled navigation relative to the definition piece origin only; controller anchor is unknown, so no world coordinates are inferred."
                : "Compiled navigation and public ExtendedFacing.getWorldOffset using explicit caller ABC offsets; skipped spaces/controller markers are absent.");
        coverage.addProperty("observations", piece.offsets == null ? "unavailable_controller_offsets" : "current loaded blocks only; unknown when unloaded, never force-loaded");
        result.add("coverage", coverage);
        JsonObject limits = new JsonObject();
        limits.addProperty("compiledEntries", COMPILED_LIMIT); limits.addProperty("scanEntries", SCAN_LIMIT);
        limits.addProperty("positions", PAGE_LIMIT); limits.addProperty("candidatesPerPosition", CANDIDATE_LIMIT);
        limits.addProperty("resultBytes", BYTE_LIMIT); limits.addProperty("plansCached", PLAN_CACHE_LIMIT); limits.addProperty("checkpointsPerPlan", 64);
        limits.addProperty("pieceBoundsCached", BOUNDS_CACHE_LIMIT); limits.addProperty("pieceBoundsScanEntries", GEOMETRY_SCAN_LIMIT);
        result.add("limits", limits);
        result.add("cache", cacheStatistics());
        result.add("budget", budget(started));
        return result;
    }

    private JsonObject boundsSummary(Plan plan, Object facing, JsonObject alignment, long started) {
        JsonObject summary = bounds(plan, facing, started), result = new JsonObject();
        result.addProperty("mode", "piece_bounds"); result.addProperty("boundsOnly", true);
        result.add("controller", controller(plan.world, plan.meta, plan.x, plan.y, plan.z)); result.add("definition", definitionInfo(plan.definition));
        result.addProperty("piece", plan.piece); result.addProperty("variant", plan.variant); result.addProperty("compiledEntries", plan.elements.length);
        result.add("pieceBounds", summary); result.add("positions", new JsonArray()); result.add("alignment", alignment);
        result.addProperty("coordinateSpace", plan.offsets == null ? "piece_origin" : "controller_relative"); result.addProperty("worldPositionsAvailable", plan.offsets != null);
        JsonObject offsets = new JsonObject();
        if (plan.offsets != null) { offsets.addProperty("a", plan.offsets[0]); offsets.addProperty("b", plan.offsets[1]); offsets.addProperty("c", plan.offsets[2]); }
        result.add("offsets", plan.offsets == null ? JsonNull.INSTANCE : offsets);
        result.addProperty("offset", 0); result.addProperty("nextOffset", 0);
        result.addProperty("hasMore", summary.has("available") && summary.get("available").getAsBoolean() && !summary.get("complete").getAsBoolean());
        result.addProperty("nonAtomic", true);
        JsonObject coverage = coverage(); coverage.addProperty("observations", "not_requested_bounds_only; no world block, description or candidate getter is called");
        coverage.addProperty("geometry", "Incremental compiled-piece navigation only. Repeat the same offset-zero declaration while pieceBounds.partial; no position cursor or complete-machine/formation claim.");
        result.add("coverage", coverage);
        JsonObject limits = new JsonObject(); limits.addProperty("compiledEntries", COMPILED_LIMIT); limits.addProperty("pieceBoundsScanEntries", GEOMETRY_SCAN_LIMIT);
        limits.addProperty("resultBytes", BYTE_LIMIT); limits.addProperty("plansCached", PLAN_CACHE_LIMIT); limits.addProperty("pieceBoundsCached", BOUNDS_CACHE_LIMIT);
        result.add("limits", limits); result.add("cache", cacheStatistics()); result.add("budget", budget(started)); return result;
    }

    private JsonObject bounds(Plan plan, Object facing, long started) {
        PieceBounds selected = null;
        String geometryContext = plan.variant + ":" + plan.facing + ":" + Arrays.toString(plan.offsets);
        for (PieceBounds bounds : pieceBounds) if (bounds.definition == plan.definition && bounds.elements == plan.elements && bounds.piece.equals(plan.piece) && bounds.geometryContext.equals(geometryContext)) { selected = bounds; break; }
        try {
            if (selected != null && selected.complete() && !selected.reusable()) { pieceBounds.remove(selected); selected = null; }
            if (selected == null) { selected = new PieceBounds(plan.definition, plan.piece, plan.elements, plan.elementApi, geometryContext); pieceBounds.add(selected); }
            else { pieceBounds.remove(selected); pieceBounds.add(selected); }
            selected.lastAccess = clock.getAsLong(); trimCaches();
            selected.advance(started);
            PieceBounds.Transform transform = facing == null ? null : abc -> {
                int[] xyz = new int[3]; invoke(facing.getClass(), facing, "getWorldOffset", new Class<?>[] {int[].class, int[].class}, abc, xyz); return xyz;
            };
            return selected.describe(plan.offsets, plan.x, plan.y, plan.z, transform);
        } catch (RuntimeException | LinkageError error) {
            JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("complete", false); result.addProperty("partial", true);
            result.addProperty("reason", "public_navigation_unavailable"); result.addProperty("error", JsonData.bounded(error.getMessage(), 512)); return result;
        }
    }

    private JsonObject discover(World world, Object tile, Object meta, Object definition, JsonObject alignment,
                                int x, int y, int z, int offset, int limit, String query, long started) {
        DefinitionCatalog selected = null;
        for (DefinitionCatalog catalog : catalogs) {
            if (catalog.world == world && catalog.tile == tile && catalog.meta == meta && catalog.definition == definition
                    && catalog.x == x && catalog.y == y && catalog.z == z && catalog.query.equals(query)) { selected = catalog; break; }
        }
        if (selected == null && offset != 0)
            throw new BridgeException("stale_cursor", "Restart definition discovery with offset 0 after context changes or eviction.");
        if (selected == null) {
            Class<?> api = publicBase(definition, PREFIX + "structure.StructureDefinition");
            if (api == null) return discoveryUnavailable(world, meta, definition, alignment, x, y, z, query, started,
                    "IStructureDefinition has no piece enumeration contract; this definition does not expose public StructureDefinition.getStructures.");
            Object structures;
            try { structures = invoke(api, definition, "getStructures", new Class<?>[0]); }
            catch (BridgeException error) {
                return discoveryUnavailable(world, meta, definition, alignment, x, y, z, query, started, error.getMessage());
            }
            if (!(structures instanceof Map<?, ?>)) return discoveryUnavailable(world, meta, definition, alignment, x, y, z, query, started,
                    "Public StructureDefinition.getStructures did not return a structure map.");
            selected = new DefinitionCatalog(world, tile, meta, definition, x, y, z, query, (Map<?, ?>) structures);
            catalogs.add(selected);
        } else {
            catalogs.remove(selected);
            catalogs.add(selected);
        }
        selected.lastAccess = clock.getAsLong(); trimCaches();
        JsonObject result = definitionPage(selected, offset, limit, started);
        result.add("controller", controller(world, meta, x, y, z));
        result.add("definition", definitionInfo(definition));
        result.add("alignment", alignment);
        result.add("cache", cacheStatistics());
        return result;
    }

    static JsonObject definitionPage(DefinitionCatalog catalog, int offset, int limit, long started) {
        if (!catalog.checkpoints.containsKey(offset)) throw new BridgeException("stale_cursor", "Use nextOffset from this current definition discovery.");
        JsonArray pieces = new JsonArray();
        int index = offset, visited = 0, bytes = 4096;
        boolean byteLimited = false;
        while (index < catalog.names.length && visited < SCAN_LIMIT && pieces.size() < limit) {
            if (visited > 0 && System.nanoTime() - started >= SOFT_BUDGET_NANOS) break;
            Object key = catalog.names[index];
            if (key instanceof String && CatalogData.matches(catalog.query, (String) key)) {
                Object value = catalog.structures.get(key);
                JsonObject piece = new JsonObject();
                piece.addProperty("name", JsonData.bounded((String) key, 128));
                piece.addProperty("selectable", ((String) key).length() <= 128);
                boolean available = value instanceof Object[] && ((Object[]) value).length <= COMPILED_LIMIT;
                if (value instanceof Object[]) piece.addProperty("compiledEntries", ((Object[]) value).length);
                piece.addProperty("geometryAvailable", available);
                piece.add("offsets", JsonNull.INSTANCE);
                piece.addProperty("coordinateSpace", "piece_origin");
                piece.addProperty("worldPositionsAvailable", false);
                int size = piece.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes + size > BYTE_LIMIT - 4096) { byteLimited = true; break; }
                pieces.add(piece); bytes += size;
            }
            index++; visited++;
        }
        if (index < catalog.names.length && index != offset) saveCheckpoint(catalog.checkpoints, index, new int[0]);
        JsonObject result = discoveryResult(catalog.query, started);
        result.add("pieces", pieces);
        result.addProperty("piecesTotal", catalog.names.length);
        result.addProperty("offset", offset); result.addProperty("nextOffset", index);
        result.addProperty("hasMore", index < catalog.names.length);
        result.addProperty("visited", visited); result.addProperty("byteLimited", byteLimited);
        return result;
    }

    private static JsonObject discoveryUnavailable(World world, Object meta, Object definition, JsonObject alignment,
                                                   int x, int y, int z, String query, long started, String reason) {
        JsonObject result = discoveryResult(query, started);
        result.add("controller", controller(world, meta, x, y, z));
        JsonObject info = definitionInfo(definition);
        info.addProperty("pieceDiscoveryAvailable", false);
        info.addProperty("pieceDiscoverySource", "unavailable");
        info.addProperty("pieceDiscoveryReason", JsonData.bounded(reason, 512));
        result.add("definition", info); result.add("alignment", alignment);
        result.add("pieces", new JsonArray()); result.add("piecesTotal", JsonNull.INSTANCE);
        result.addProperty("offset", 0); result.addProperty("nextOffset", 0);
        result.addProperty("hasMore", false); result.addProperty("visited", 0); result.addProperty("byteLimited", false);
        return result;
    }

    private static JsonObject discoveryResult(String query, long started) {
        JsonObject result = new JsonObject();
        result.addProperty("mode", "definition_discovery"); result.addProperty("query", query);
        result.add("positions", new JsonArray()); result.addProperty("nonAtomic", true);
        result.add("offsets", JsonNull.INSTANCE); result.addProperty("coordinateSpace", "piece_origin"); result.addProperty("worldPositionsAvailable", false);
        JsonObject coverage = coverage();
        coverage.addProperty("geometry", "Piece catalog only; select a discovered piece for bounded origin-relative compiled geometry, or also supply explicit offsetA/offsetB/offsetC for world positions.");
        coverage.addProperty("observations", "not_requested_definition_discovery");
        result.add("coverage", coverage);
        JsonObject limits = new JsonObject();
        limits.addProperty("definitionPieces", DEFINITION_PIECE_LIMIT); limits.addProperty("scanEntries", SCAN_LIMIT);
        limits.addProperty("pieces", PAGE_LIMIT); limits.addProperty("resultBytes", BYTE_LIMIT);
        limits.addProperty("definitionCatalogsCached", CATALOG_CACHE_LIMIT); limits.addProperty("checkpointsPerCatalog", 64);
        result.add("limits", limits); result.add("budget", budget(started));
        return result;
    }

    private static JsonObject definitionInfo(Object definition) {
        JsonObject info = new JsonObject();
        info.addProperty("class", JsonData.bounded(definition.getClass().getName(), 256));
        info.addProperty("api", PREFIX + "structure.IStructureDefinition");
        boolean available = publicBase(definition, PREFIX + "structure.StructureDefinition") != null;
        info.addProperty("pieceDiscoveryAvailable", available);
        info.addProperty("pieceDiscoverySource", available ? PREFIX + "structure.StructureDefinition.getStructures" : "unavailable");
        return info;
    }

    private static JsonObject coverage() {
        JsonObject coverage = new JsonObject();
        coverage.addProperty("pieceOffsets", "unavailable from public definition APIs; original '~' anchor markers are removed during shape compilation. Explicit caller ABC is required for controller-relative/world geometry.");
        coverage.addProperty("description", "Public IStructureElement.getDescription gives partial translated element categories; an empty description does not mean unrestricted.");
        coverage.addProperty("candidates", "Public getBlocksToPlace/getStacks suggestions require located loaded positions and selected trigger size; suggestions are not a complete acceptance rule.");
        coverage.addProperty("rules", "unavailable as a complete declarative model; no element predicates or machine checks are executed");
        coverage.addProperty("chambers", "unavailable; piece names and element descriptions do not declare chamber roles, instance locations, counts or rules");
        coverage.addProperty("modules", "unavailable from StructureLib definition APIs; module instances require an existing public controller module interface");
        coverage.addProperty("dynamicPieces", "unavailable; public definitions do not declare active pieces, repetition count, placement offsets or selected runtime variant");
        coverage.addProperty("validation", "not_requested; no checkMachine/checkStructure/element.check/couldBeValid/spawnHint calls");
        return coverage;
    }

    private static JsonObject controller(World world, Object meta, int x, int y, int z) {
        JsonObject value = coords(x, y, z);
        if (world != null) value.addProperty("dimension", world.provider.dimensionId);
        value.addProperty("metaClass", JsonData.bounded(meta.getClass().getName(), 256));
        return value;
    }

    private static JsonObject position(Plan plan, Object element, int[] xyz, int[] abc, int variant, String query) {
        JsonObject result = xyz == null ? new JsonObject() : coords(xyz[0], xyz[1], xyz[2]);
        result.addProperty("a", abc[0]); result.addProperty("b", abc[1]); result.addProperty("c", abc[2]);
        result.addProperty("coordinateSpace", xyz == null ? "piece_origin" : "controller_relative");
        result.addProperty("elementClass", JsonData.bounded(element.getClass().getName(), 256));
        JsonArray keys = new JsonArray(), text = new JsonArray(), candidates = new JsonArray();
        try {
            Object description = invoke(plan.elementApi, element, "getDescription", new Class<?>[] {Object.class}, plan.meta);
            if (description instanceof Iterable<?>) {
                for (Object value : (Iterable<?>) description) {
                    if (keys.size() >= 16) { result.addProperty("descriptionTruncated", true); break; }
                    String key = JsonData.bounded(String.valueOf(value), 256);
                    keys.add(new JsonPrimitive(key));
                    text.add(new JsonPrimitive(JsonData.bounded(StatCollector.translateToLocal(key), 256)));
                }
            }
        } catch (RuntimeException | LinkageError error) { result.addProperty("descriptionError", error.getClass().getSimpleName()); }
        result.add("descriptionKeys", keys); result.add("description", text);
        // Filter before expensive world/candidate getters; filtered positions are never emitted.
        if (!matches(result, query)) return result;
        if (xyz == null) {
            result.addProperty("observationCoverage", "unavailable_controller_offsets");
            result.addProperty("candidateCoverage", "not_queried_unknown_controller_offsets");
            result.add("candidates", candidates);
            return result;
        }
        boolean loaded = plan.world != null && plan.world.blockExists(xyz[0], xyz[1], xyz[2]);
        result.addProperty("loaded", loaded);
        if (!loaded) {
            result.addProperty("candidateCoverage", "not_queried_unloaded");
            result.add("candidates", candidates);
            return result;
        }
        if (loaded) {
            JsonObject observed = new JsonObject();
            Block block = plan.world.getBlock(xyz[0], xyz[1], xyz[2]);
            observed.addProperty("registryName", JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 256));
            observed.addProperty("metadata", plan.world.getBlockMetadata(xyz[0], xyz[1], xyz[2]));
            result.add("observed", observed);
        }
        try {
            ItemStack trigger = new ItemStack(Items.stick, variant, 0);
            Object placement = invoke(plan.elementApi, element, "getBlocksToPlace",
                    new Class<?>[] {Object.class, World.class, int.class, int.class, int.class, ItemStack.class, plan.environmentApi},
                    plan.meta, plan.world, xyz[0], xyz[1], xyz[2], trigger, null);
            Object stacks = placement == null ? null : invoke(plan.blocksApi, placement, "getStacks", new Class<?>[0]);
            result.addProperty("candidateCoverage", stacks == null ? "unavailable_or_predicate_only" : "public_stack_suggestions");
            if (stacks instanceof Iterable<?>) {
                Iterator<?> iterator = ((Iterable<?>) stacks).iterator();
                int examined = 0;
                while (iterator.hasNext() && examined < CANDIDATE_LIMIT) {
                    Object value = iterator.next(); examined++;
                    if (!(value instanceof ItemStack) || ((ItemStack) value).getItem() == null) continue;
                    ItemStack stack = (ItemStack) value;
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("registryName", JsonData.bounded(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())), 256));
                    candidate.addProperty("metadata", stack.getMetadata());
                    candidate.addProperty("count", stack.stackSize);
                    candidate.addProperty("hasTag", stack.hasTagCompound());
                    candidates.add(candidate);
                }
                result.addProperty("candidatesTruncated", iterator.hasNext());
            }
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("candidateCoverage", "public_getter_unavailable_without_autoplace_environment");
            result.addProperty("candidateError", error.getClass().getSimpleName());
        }
        result.add("candidates", candidates);
        result.add("schematicGoal",SchematicGoal.publicPosition(result));
        return result;
    }

    static Piece piece(String type, JsonObject args) {
        String name = present(args, "piece") ? CatalogData.text(args, "piece", 128, "") : "";
        boolean any = present(args, "offsetA") || present(args, "offsetB") || present(args, "offsetC");
        if (name.isEmpty() && any) throw new BridgeException("invalid_args", "Explicit ABC offsets require a piece name.");
        int[] offsets = any ? new int[] {JsonData.integer(args, "offsetA", -2048, 2048), JsonData.integer(args, "offsetB", -2048, 2048), JsonData.integer(args, "offsetC", -2048, 2048)} : null;
        return new Piece(name, offsets, offsets == null ? "public definition geometry; controller offsets unavailable" : "explicit caller piece/offset planning parameters");
    }

    private static boolean matches(JsonObject position, String query) {
        return query.isEmpty() || (position.get("elementClass").getAsString() + position.get("descriptionKeys") + position.get("description"))
                .toLowerCase(Locale.ROOT).contains(query);
    }

    private static JsonObject alignment(Object facing) {
        JsonObject value = new JsonObject();
        for (String part : new String[] {"Direction", "Rotation", "Flip"}) value.addProperty(part.toLowerCase(Locale.ROOT),
                String.valueOf(invoke(facing.getClass(), facing, "get" + part, new Class<?>[0])));
        return value;
    }

    public static JsonObject budget(long started) {
        long elapsed = Math.max(0, System.nanoTime() - started);
        JsonObject budget = new JsonObject();
        budget.addProperty("softBudgetMicros", SOFT_BUDGET_NANOS / 1000);
        budget.addProperty("actualElapsedMicros", elapsed / 1000);
        budget.addProperty("softBudgetExceeded", elapsed > SOFT_BUDGET_NANOS);
        budget.addProperty("policy", "Soft budget includes public getters and JSON construction; a single getter cannot be preempted. At least one compiled entry progresses per page.");
        return budget;
    }

    private static JsonObject coords(int x, int y, int z) {
        JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z); return result;
    }

    private static boolean present(JsonObject args, String key) { return args.has(key) && !args.get(key).isJsonNull(); }

    private static int baseOffset(int[] offsets, int axis) { return offsets == null ? 0 : offsets[axis]; }

    private static int optionalInt(JsonObject args, String key, int fallback, int min, int max) { return present(args, key) ? JsonData.integer(args, key, min, max) : fallback; }

    private static Class<?> publicBase(Object value, String name) {
        for (Class<?> type = value == null ? null : value.getClass(); type != null; type = type.getSuperclass()) if (name.equals(type.getName())) return type;
        return null;
    }

    private static Object invoke(Class<?> api, Object target, String method, Class<?>[] types, Object... args) {
        try { return api.getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", method + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public API unavailable: " + method); }
    }

    static final class Piece {
        final String name, source; final int[] offsets;
        Piece(String name, int[] offsets, String source) { this.name = name; this.offsets = offsets; this.source = source; }
    }

    static final class DefinitionCatalog {
        final World world; final Object tile, meta, definition;
        final int x, y, z; final String query;
        final Map<?, ?> structures; final Object[] names;
        final long referenceSlots;
        long lastAccess;
        final Map<Integer, int[]> checkpoints = new LinkedHashMap<Integer, int[]>();
        DefinitionCatalog(World world, Object tile, Object meta, Object definition, int x, int y, int z, String query, Map<?, ?> structures) {
            this.world = world; this.tile = tile; this.meta = meta; this.definition = definition;
            this.x = x; this.y = y; this.z = z; this.query = query; this.structures = structures;
            if (structures.size() > DEFINITION_PIECE_LIMIT) throw new BridgeException("too_large", "Definition exceeds the bounded public piece catalog limit: " + DEFINITION_PIECE_LIMIT);
            names = structures.keySet().toArray();
            if (names.length > DEFINITION_PIECE_LIMIT) throw new BridgeException("too_large", "Definition exceeds the bounded public piece catalog limit: " + DEFINITION_PIECE_LIMIT);
            long references = names.length;
            for (Object value : structures.values()) if (value instanceof Object[]) {
                references += ((Object[]) value).length;
                if (references > CACHE_REFERENCE_BYTE_BUDGET / 8) throw new BridgeException("too_large", "The public definition catalog exceeds the conservative compiled-reference retention budget; select one known piece directly instead.");
            }
            referenceSlots = references;
            checkpoints.put(0, new int[0]);
        }
    }

    private static void saveCheckpoint(Map<Integer, int[]> checkpoints, int offset, int[] abc) {
        checkpoints.put(offset, abc.clone());
        while (checkpoints.size() > 64) {
            Iterator<Integer> keys = checkpoints.keySet().iterator();
            Integer key = keys.next();
            if (key == 0) key = keys.next();
            checkpoints.remove(key);
        }
    }

    private static final class Plan {
        final World world; final Object tile, meta, definition;
        final String facing, piece, query; final int x, y, z, variant; final int[] offsets;
        final Object[] elements; final Class<?> definitionApi, elementApi, environmentApi, blocksApi;
        long lastAccess;
        final Map<Integer, int[]> checkpoints = new LinkedHashMap<Integer, int[]>();
        Plan(World world, Object tile, Object meta, Object definition, Object facing, String alignment,
             int x, int y, int z, Piece piece, int variant, String query) {
            this.world = world; this.tile = tile; this.meta = meta; this.definition = definition; this.facing = alignment;
            this.x = x; this.y = y; this.z = z; this.piece = piece.name; this.offsets = piece.offsets == null ? null : piece.offsets.clone(); this.variant = variant; this.query = query;
            try {
                ClassLoader loader = definition.getClass().getClassLoader();
                definitionApi = Class.forName(PREFIX + "structure.IStructureDefinition", false, loader);
                elementApi = Class.forName(PREFIX + "structure.IStructureElement", false, loader);
                environmentApi = Class.forName(PREFIX + "structure.AutoPlaceEnvironment", false, loader);
                blocksApi = Class.forName(PREFIX + "structure.IStructureElement$BlocksToPlace", false, loader);
                Object value = invoke(definitionApi, definition, "getStructureFor", new Class<?>[] {String.class}, piece.name);
                if (!(value instanceof Object[])) throw new BridgeException("not_found", "No compiled structure exists for this piece.");
                elements = (Object[]) value;
                if (elements.length > COMPILED_LIMIT) throw new BridgeException("too_large", "Compiled structure exceeds the bounded entry limit: " + COMPILED_LIMIT);
            } catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "StructureLib public planning API is unavailable."); }
            checkpoints.put(0, new int[] {-baseOffset(offsets, 0), -baseOffset(offsets, 1), -baseOffset(offsets, 2)});
        }
        void save(int offset, int[] abc) {
            saveCheckpoint(checkpoints, offset, abc);
        }
        boolean currentElements() { return invoke(definitionApi, definition, "getStructureFor", new Class<?>[] {String.class}, piece) == elements; }
    }
}
