package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded full-piece geometry is independent of the existing filtered position cursor. */
final class PieceBoundsSelfTest {
    private static int checks;
    static int run() {
        checks = 0;
        boundedAndCached();
        navigationAndWorldTransform();
        independentPaginationAndDefinitionIdentity();
        positionsKeepTheirBudget();
        cacheLifecycleAndCapacity();
        summaryOnlyAndDynamicNavigation();
        largeSummaryBenchmark();
        return checks;
    }

    private static void boundedAndCached() {
        Element solid = new Element(); IStructureElement[] elements = new IStructureElement[StructureAdapter.GEOMETRY_SCAN_LIMIT * 2 + 10]; Arrays.fill(elements, solid);
        PieceBounds bounds = new PieceBounds(new Object(), "large", elements, IStructureElement.class);
        bounds.advance(generousStart());
        JsonObject data = bounds.describe(null, 10, 20, 30, null);
        check(data.get("scannedEntries").getAsInt() == StructureAdapter.GEOMETRY_SCAN_LIMIT && data.get("partial").getAsBoolean() && !data.get("complete").getAsBoolean(), "geometry summary respects its hard scan bound and declares partial coverage");
        check(solid.descriptionCalls == 0 && solid.candidateCalls == 0, "bounds scanner invokes geometry getters only, without descriptions or world candidates");
        for (int i = 0; i < 5; i++) bounds.advance(generousStart());
        data = bounds.describe(null, 10, 20, 30, null);
        check(data.get("complete").getAsBoolean() && data.get("physicalPositions").getAsInt() == elements.length && data.getAsJsonObject("originABC").getAsJsonObject("size").get("a").getAsLong() == elements.length, "incremental scan yields complete large-piece bounds without a fixed cube assumption");
        long calls = data.get("navigationGetterCalls").getAsLong(); bounds.advance(generousStart()); data = bounds.describe(null, 10, 20, 30, null);
        check(data.get("navigationGetterCalls").getAsLong() == calls && data.get("completeSummaryReusable").getAsBoolean(), "complete audited fixed-navigation summary does not traverse the piece again");
        PieceBounds expired = new PieceBounds(new Object(), "large", elements, IStructureElement.class);
        calls = solid.navigationCalls;
        expired.advance(System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(expired.describe(null, 0, 0, 0, null).get("scannedEntries").getAsInt() == 0 && solid.navigationCalls == calls, "optional summary defers all traversal when the shared soft deadline is exhausted");
    }

    private static void navigationAndWorldTransform() {
        Element solid = new Element(); IStructureElement[] elements = {solid, new Navigation(34, 34, 130), solid};
        PieceBounds bounds = new PieceBounds(new Object(), "large", elements, IStructureElement.class); bounds.advance(generousStart());
        JsonObject data = bounds.describe(null, 100, 200, 300, abc -> { throw new AssertionError("Unknown anchor cannot transform world bounds"); });
        JsonObject size = data.getAsJsonObject("originABC").getAsJsonObject("size");
        check(size.get("a").getAsInt() == 35 && size.get("b").getAsInt() == 35 && size.get("c").getAsInt() == 131, "public compiled navigation yields the full asymmetric 35 by 35 by 131 span");
        check(!data.get("worldBoundsAvailable").getAsBoolean() && data.get("controllerRelativeABC").isJsonNull() && !data.get("fullOriginalShapeBoundsAvailable").getAsBoolean(), "compiled extent does not invent a controller anchor or original skipped padding");
        data = bounds.describe(new int[] {17, 17, 0}, 100, 200, 300, abc -> new int[] {abc[2], -abc[1], -abc[0]});
        JsonObject world = data.getAsJsonObject("worldXYZ");
        check(data.get("worldBoundsAvailable").getAsBoolean() && world.getAsJsonObject("min").get("x").getAsInt() == 100 && world.getAsJsonObject("max").get("x").getAsInt() == 230, "explicit offsets and public transform map all eight bounding corners");
        check(world.getAsJsonObject("min").get("y").getAsInt() == 183 && world.getAsJsonObject("max").get("z").getAsInt() == 317 && world.getAsJsonObject("size").get("x").getAsInt() == 131, "flipped alignment preserves inclusive world bounds and dimensions");
        PieceBounds empty = new PieceBounds(new Object(), "empty", new IStructureElement[0], IStructureElement.class);
        JsonObject emptyData = empty.describe(null, 0, 0, 0, null);
        check(emptyData.get("complete").getAsBoolean() && emptyData.get("originABC").isJsonNull() && emptyData.get("physicalPositions").getAsInt() == 0, "empty compiled geometry is complete without an invented one-block box");
    }

    private static void independentPaginationAndDefinitionIdentity() {
        Element solid = new Element(); IStructureElement[] elements = new IStructureElement[StructureAdapter.GEOMETRY_SCAN_LIMIT * 2 + 904]; Arrays.fill(elements, solid);
        Definition definition = new Definition(elements); Object tile = new Object(), meta = new Object();
        StructureAdapter adapter = new StructureAdapter(); JsonObject args = new JsonObject(); args.addProperty("piece", "fixture"); args.addProperty("limit", 1);
        JsonObject page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(solid.descriptionCalls == 1 && page.getAsJsonObject("pieceBounds").get("navigationGetterCalls").getAsLong() == 1, "position description precedes the independent memoized fixed-navigation scan");
        check(page.get("nextOffset").getAsInt() == 1 && page.getAsJsonObject("pieceBounds").get("scannedEntries").getAsInt() == StructureAdapter.GEOMETRY_SCAN_LIMIT, "one returned position can independently accumulate thousands of summary entries");
        page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(page.get("nextOffset").getAsInt() == 1 && page.getAsJsonObject("pieceBounds").get("scannedEntries").getAsInt() == StructureAdapter.GEOMETRY_SCAN_LIMIT * 2, "repeating a position page advances summary without consuming its saved cursor");
        args.addProperty("offset", 1);
        page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(page.get("nextOffset").getAsInt() == 2 && page.getAsJsonArray("positions").get(0).getAsJsonObject().get("a").getAsInt() == 1 && page.getAsJsonObject("pieceBounds").get("complete").getAsBoolean(), "saved position cursor remains valid while independent summary completes");
        args.addProperty("offset", 0); args.addProperty("query", "not-present");
        page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(page.getAsJsonArray("positions").size() == 0 && page.getAsJsonObject("pieceBounds").get("physicalPositions").getAsInt() == elements.length, "position query cannot filter physical positions out of cached bounds");
        args.remove("query");
        Definition replacement = new Definition(elements);
        page = adapter.readPlan(null, tile, meta, replacement, null, 1, 2, 3, args, generousStart());
        check(!page.getAsJsonObject("pieceBounds").get("complete").getAsBoolean() && page.getAsJsonObject("pieceBounds").get("scannedEntries").getAsInt() == StructureAdapter.GEOMETRY_SCAN_LIMIT, "new definition identity never reuses an old complete summary even with the same array");
        adapter.invalidate();
        page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(page.getAsJsonObject("pieceBounds").get("scannedEntries").getAsInt() == StructureAdapter.GEOMETRY_SCAN_LIMIT, "world/profile invalidation clears the summary cache together with position plans");
    }

    private static void positionsKeepTheirBudget() {
        Element solid = new Element(); Definition definition = new Definition(new IStructureElement[] {solid, solid, solid});
        Object tile = new Object(), meta = new Object(); StructureAdapter adapter = new StructureAdapter();
        JsonObject args = new JsonObject(); args.addProperty("piece", "fixture"); args.addProperty("limit", 2);
        JsonObject page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(page.getAsJsonArray("positions").size() == 1 && page.get("nextOffset").getAsInt() == 1 && solid.descriptionCalls == 1, "expired budget retains the existing one-position progress contract");
        check(page.getAsJsonObject("pieceBounds").get("scannedEntries").getAsInt() == 0 && page.getAsJsonObject("pieceBounds").get("partial").getAsBoolean(), "expired position page cannot initiate an additional summary traversal");
        args.addProperty("offset", 1); args.addProperty("limit", 1);
        page = adapter.readPlan(null, tile, meta, definition, null, 1, 2, 3, args, generousStart());
        check(page.getAsJsonArray("positions").get(0).getAsJsonObject().get("a").getAsInt() == 1 && page.get("nextOffset").getAsInt() == 2 && page.getAsJsonObject("pieceBounds").get("complete").getAsBoolean(), "deferred summary resumes without advancing or corrupting the position checkpoint");
    }

    private static JsonObject bounds(StructureAdapter adapter, Definition definition, JsonObject args, long started) {
        return adapter.readPlan(null, definition, definition, definition, null, 1, 2, 3, args, started);
    }

    private static JsonObject summaryArgs() {
        JsonObject args = new JsonObject(); args.addProperty("piece", "fixture"); args.addProperty("boundsOnly", true); return args;
    }

    private static void cacheLifecycleAndCapacity() {
        AtomicLong clock = new AtomicLong(); StructureAdapter adapter = new StructureAdapter(clock::get);
        adapter.maintain("resources:A"); Definition definition = new Definition(new IStructureElement[] {new Element(), new Element()});
        JsonObject args = summaryArgs(), first = bounds(adapter, definition, args, generousStart());
        check(first.getAsJsonObject("pieceBounds").get("complete").getAsBoolean() && adapter.cacheStatistics().get("pieceBounds").getAsInt() == 1, "one complete immutable piece is retained");
        clock.set(StructureAdapter.CACHE_TTL_NANOS + 1); adapter.maintain("resources:A");
        check(adapter.cacheStatistics().get("plans").getAsInt() == 0 && adapter.cacheStatistics().get("pieceBounds").getAsInt() == 0 && adapter.cacheStatistics().get("ttlEvictions").getAsInt() == 2, "idle tick maintenance releases both plan and summary after TTL without another query");
        bounds(adapter, definition, args, generousStart()); adapter.maintain("resources:B");
        check(adapter.cacheStatistics().get("plans").getAsInt() == 0 && adapter.cacheStatistics().get("pieceBounds").getAsInt() == 0, "resource/language context changes immediately release geometric owners");
        bounds(adapter, definition, args, generousStart()); args.addProperty("variant", 2); bounds(adapter, definition, args, generousStart());
        check(adapter.cacheStatistics().get("pieceBounds").getAsInt() == 2, "different trigger variants cannot share a geometry summary entry");
        adapter.invalidate(); args.remove("variant");
        for (int i = 0; i < StructureAdapter.BOUNDS_CACHE_LIMIT + 3; i++) { clock.incrementAndGet(); args.addProperty("piece", "piece-" + i); bounds(adapter, definition, args, generousStart()); }
        JsonObject cache = adapter.cacheStatistics();
        check(cache.get("pieceBounds").getAsInt() == StructureAdapter.BOUNDS_CACHE_LIMIT && cache.get("plans").getAsInt() == StructureAdapter.PLAN_CACHE_LIMIT && cache.get("capacityEvictions").getAsInt() > 0, "summary and cursor LRUs enforce independent finite counts");
        adapter.invalidate(); args = summaryArgs();
        IStructureElement[] shared = new IStructureElement[StructureAdapter.COMPILED_LIMIT]; Arrays.fill(shared, new Element());
        Definition large = new Definition(shared);
        for (int i = 0; i < 20; i++) { clock.incrementAndGet(); args.addProperty("piece", "large-" + i); bounds(adapter, large, args, generousStart()); }
        cache = adapter.cacheStatistics();
        check(cache.get("referenceSlotByteEstimate").getAsLong() <= StructureAdapter.CACHE_REFERENCE_BYTE_BUDGET && cache.get("capacityEvictions").getAsInt() > 0, "large aliased arrays receive conservative repeated retention charges and obey the byte budget");
        check(cache.get("summaryAuxiliaryByteEstimate").getAsLong() < 1024 * 1024, "bounded navigation memo has a small independent auxiliary-memory estimate");
        adapter.invalidate();
        check(adapter.cacheStatistics().get("referenceSlotByteEstimate").getAsLong() == 0 && adapter.cacheStatistics().get("summaryAuxiliaryByteEstimate").getAsLong() == 0, "explicit world/profile/session cleanup releases every charged structure cache reference");
    }

    private static void summaryOnlyAndDynamicNavigation() {
        StructureAdapter adapter = new StructureAdapter(); Element solid = new Element(); Definition definition = new Definition(new IStructureElement[] {solid, solid});
        JsonObject args = summaryArgs(), page = bounds(adapter, definition, args, generousStart());
        check("piece_bounds".equals(page.get("mode").getAsString()) && page.getAsJsonArray("positions").size() == 0 && solid.descriptionCalls == 0 && solid.candidateCalls == 0, "summary-only reads never spend work on descriptions, candidates or world observations");
        args.addProperty("offset", 1); expect("invalid_args", () -> bounds(adapter, definition, args, generousStart())); args.remove("offset");
        args.addProperty("floorBlockY", 1); expect("invalid_args", () -> bounds(adapter, definition, args, generousStart())); args.remove("floorBlockY");
        JsonObject positions = new JsonObject(); positions.addProperty("piece", "fixture"); positions.addProperty("limit", 1);
        bounds(adapter, definition, positions, generousStart()); definition.elements = new IStructureElement[] {solid, solid, solid}; positions.addProperty("offset", 1);
        expect("stale_cursor", () -> bounds(adapter, definition, positions, generousStart()));
        check(adapter.cacheStatistics().get("pieceBounds").getAsInt() == 0, "same-definition compiled-array replacement invalidates old summaries and all saved cursors");
        page = bounds(adapter, definition, args, generousStart());
        check(page.getAsJsonObject("pieceBounds").get("physicalPositions").getAsInt() == 3, "restarting after replacement uses the newly published compiled array");
        final int[] calls = {0}; IStructureElement dynamic = new Element() {
            @Override public boolean isNavigating() { return true; }
            @Override public int getStepA() { return ++calls[0]; }
        };
        Definition changing = new Definition(new IStructureElement[] {dynamic, dynamic, dynamic, dynamic, solid});
        JsonObject before = bounds(adapter, changing, args, generousStart()).getAsJsonObject("pieceBounds");
        JsonObject after = bounds(adapter, changing, args, generousStart()).getAsJsonObject("pieceBounds");
        check(before.getAsJsonObject("originABC").getAsJsonObject("min").get("a").getAsInt() == 10 && after.getAsJsonObject("originABC").getAsJsonObject("min").get("a").getAsInt() == 26 && calls[0] == 8, "unknown dynamic navigation getters run at every occurrence and every completed-summary query");
        check(!after.get("completeSummaryReusable").getAsBoolean() && !after.get("navigationSnapshotStable").getAsBoolean() && after.get("navigationGetterCalls").getAsLong() == 29, "dynamic observations cannot masquerade as an immutable cached snapshot");
        Definition oversized = new Definition(new IStructureElement[StructureAdapter.COMPILED_LIMIT + 1]);
        expect("too_large", () -> bounds(adapter, oversized, args, generousStart()));
    }

    private static void largeSummaryBenchmark() {
        Runtime runtime = Runtime.getRuntime(); long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        int width = 81, height = 167, depth = 81; Element solid = new Element();
        IStructureElement[] elements = new IStructureElement[(width + 1) * height * depth]; int index = 0;
        for (int c = 0; c < depth; c++) for (int b = 0; b < height; b++) {
            elements[index++] = new Navigation(0, b, c);
            for (int a = 0; a < width; a++) elements[index++] = solid;
        }
        Definition definition = new Definition(elements); StructureAdapter adapter = new StructureAdapter(); JsonObject args = summaryArgs(), page;
        long started = System.nanoTime(), maximumCall = 0; int pages = 0;
        do {
            long call = System.nanoTime(); page = bounds(adapter, definition, args, call); maximumCall = Math.max(maximumCall, System.nanoTime() - call);
            if (++pages > 5000) throw new AssertionError("Large bounded geometry failed to progress");
        } while (!page.getAsJsonObject("pieceBounds").get("complete").getAsBoolean());
        long elapsed = System.nanoTime() - started, heapDelta = runtime.totalMemory() - runtime.freeMemory() - heapBefore;
        JsonObject summary = page.getAsJsonObject("pieceBounds"), size = summary.getAsJsonObject("originABC").getAsJsonObject("size");
        check(elements.length > 100000 && elements.length < StructureAdapter.COMPILED_LIMIT && summary.get("physicalPositions").getAsInt() == width * height * depth, "81 by 167 by 81 dense compiled fixture exceeds the old cap and completes under the new finite cap");
        check(size.get("a").getAsInt() == width && size.get("b").getAsInt() == height && size.get("c").getAsInt() == depth && solid.descriptionCalls == 0 && solid.candidateCalls == 0, "large summary returns exact asymmetric bounds without any description or world candidate work");
        check(summary.get("navigationTuplesCached").getAsInt() == 1 && summary.get("navigationGetterCalls").getAsLong() < elements.length, "safe inherited default physical navigation is memoized while unknown navigation remains freshly called");
        System.out.println("Structure geometry benchmark headless: compiled=" + elements.length + ", physical=" + summary.get("physicalPositions") + ", pages=" + pages
                + ", elapsedMillis=" + elapsed / 1000000.0 + ", maximumCallMillis=" + maximumCall / 1000000.0 + ", observedHeapDeltaBytes=" + heapDelta
                + ", referenceSlotByteEstimate=" + page.getAsJsonObject("cache").get("referenceSlotByteEstimate") + ", summaryAuxiliaryByteEstimate=" + page.getAsJsonObject("cache").get("summaryAuxiliaryByteEstimate")
                + "; measured heap delta includes JVM/GC noise, compiled arrays are not copied, no live client or task-time acceptance.");
    }

    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }

    public static class Definition implements IStructureDefinition {
        IStructureElement[] elements; Definition(IStructureElement[] elements) { this.elements = elements; }
        @Override public IStructureElement[] getStructureFor(String name) { return elements; }
    }
    private static class Element implements IStructureElement {
        int navigationCalls, descriptionCalls, descriptionNavigationCalls, candidateCalls;
        @Override public List<String> getDescription(Object context) { descriptionCalls++; descriptionNavigationCalls = navigationCalls; return Collections.singletonList("fixture"); }
        @Override public BlocksToPlace getBlocksToPlace(Object context, net.minecraft.world.World world, int x, int y, int z, net.minecraft.item.ItemStack stack, com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment environment) { candidateCalls++; throw new AssertionError("World candidates forbidden"); }
    }
    private static class Navigation extends Element {
        final int a, b, c; Navigation(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }
        @Override public boolean isNavigating() { navigationCalls++; return true; }
        @Override public boolean resetA() { return true; }
        @Override public boolean resetB() { return true; }
        @Override public boolean resetC() { return true; }
        @Override public int getStepA() { return a; }
        @Override public int getStepB() { return b; }
        @Override public int getStepC() { return c; }
    }
    private static long generousStart() { return System.nanoTime() + 1000000000L; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
