package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** The actual traversal engine with controlled loaded/empty/dense sections; no client or world is started. */
public final class WorldScanTests {
    private static int checks;
    public static void coldProbe() {
        long setupStart = System.nanoTime();
        Fixture fixture = new Fixture(null, 0, true);
        WorldScan scan = new WorldScan();
        JsonObject args = start(0, 64, 0, 16, 512);
        long callStart = System.nanoTime();
        JsonObject page = scan.page(fixture, args, new ScanSettings(), 0, null, new GameQueue.Control());
        long finished = System.nanoTime();
        System.out.println("WorldScan benchmark fixture(cold first scan before self-tests): pageBytes=" + page.toString().getBytes(StandardCharsets.UTF_8).length
                + ", blocks=" + page.getAsJsonArray("blocks").size() + ", cellVisits=" + page.getAsJsonObject("page").get("cellVisits").getAsInt()
                + ", callMillis=" + (finished - callStart) / 1000000D + ", includingSetupMillis=" + (finished - setupStart) / 1000000D
                + ", softBudgetMillis=2, cooperative=true, stopReason=" + page.get("stopReason").getAsString());
    }
    public static int run() throws Exception {
        settings();
        completeCoverage();
        transactions();
        invalidation();
        budgets();
        bounds();
        variableRegions();
        targetedCoverage();
        publicMaterials();
        airSemantics();
        benchmark();
        return checks;
    }

    private static void settings() {
        ScanSettings settings = new ScanSettings();
        check(settings.radii(new JsonObject())[0] == 16 && settings.radii(new JsonObject())[1] == 16, "default radii are one chunk each");
        check(settings.inspect().get("cacheRadiusBlocks").getAsInt() == 64 && settings.inspect().get("cacheVerticalRadiusBlocks").getAsInt() == 64,
                "local cache defaults are four chunks without changing the one-chunk probe");
        check(settings.cacheMaxMiB() == 2048, "lazy cache budget defaults to two GiB");
        JsonObject cache = new JsonObject();
        cache.addProperty("cacheRadiusChunks", 32); cache.addProperty("cacheVerticalRadiusChunks", 16); cache.addProperty("cacheMaxMiB", 16384);
        settings.apply(cache);
        check(settings.cacheHorizontalBlocks() == 512 && settings.cacheVerticalBlocks() == 256 && settings.cacheMaxMiB() == 16384
                && settings.radii(new JsonObject())[0] == 16, "cache limits are independent of agent scan limits");
        long cacheRevision = settings.revision();
        cache.addProperty("cacheRadiusChunks", 2); cache.addProperty("cacheMaxMiB", 16385);
        expect("invalid_args", () -> settings.apply(cache));
        check(settings.cacheHorizontalBlocks() == 512 && settings.revision() == cacheRevision, "invalid cache budget cannot partially apply radius");
        cache.addProperty("cacheMaxMiB", 64); cache.addProperty("cacheVerticalRadiusChunks", 33);
        expect("invalid_args", () -> settings.apply(cache));
        check(settings.cacheMaxMiB() == 16384, "invalid cache radius cannot partially reduce budget");
        final JsonObject args = new JsonObject(); args.addProperty("radiusChunks", 8);
        JsonObject result = settings.apply(args);
        check(result.get("radiusBlocks").getAsInt() == 128 && result.get("verticalRadiusBlocks").getAsInt() == 16 && result.get("memoryOnly").getAsBoolean(), "setting one default preserves the other in memory");
        long revision = settings.revision();
        args.addProperty("verticalRadiusChunks", 9);
        expect("invalid_args", () -> settings.apply(args));
        check(settings.revision() == revision && settings.inspect().get("verticalRadiusChunks").getAsInt() == 1, "invalid settings are atomic");
        args.remove("verticalRadiusChunks"); args.addProperty("radiusChunks", 0);
        expect("invalid_args", () -> settings.apply(args));
        args.addProperty("radiusChunks", 1.5); expect("invalid_args", () -> settings.apply(args));
        args.addProperty("radiusChunks", "2"); expect("invalid_args", () -> settings.apply(args));
        final JsonObject legacy = new JsonObject(); legacy.addProperty("radius", 128);
        check(settings.radii(legacy)[0] == 128 && settings.radii(legacy)[1] == 128, "legacy block radius accepts the expanded maximum");
        legacy.addProperty("radiusChunks", 1); expect("invalid_args", () -> settings.radii(legacy));
        legacy.remove("radiusChunks"); legacy.addProperty("radius", 129); expect("invalid_args", () -> settings.radii(legacy));
        legacy.addProperty("radius", 0); check(settings.radii(legacy)[1] == 0, "legacy radius zero remains legal");
        check(ClientControlCommand.parse(new String[] {"scan", "8"}).scanArgs.get("verticalRadiusChunks").getAsInt() == 8, "one command radius sets both axes");
        check(ClientControlCommand.parse(new String[] {"scan", "2", "3"}).scanArgs.get("verticalRadiusChunks").getAsInt() == 3, "command supports separate vertical radius");
        check(ClientControlCommand.parse(new String[] {"cache", "3", "2"}).scanArgs.get("cacheRadiusChunks").getAsInt() == 3
                && ClientControlCommand.parse(new String[] {"cache", "3", "2"}).scanArgs.get("cacheVerticalRadiusChunks").getAsInt() == 2,
                "command supports independent local cache radii");
        check(ClientControlCommand.parse(new String[] {"scan", "status"}).scanArgs.entrySet().isEmpty(), "scan status has no mutation arguments");
        check(ClientControlCommand.parse(new String[] {"cache", "32"}).scanArgs.get("cacheVerticalRadiusChunks").getAsInt() == 32, "cache command accepts wider radii");
        check(ClientControlCommand.parse(new String[] {"cache", "memory", "16384"}).scanArgs.get("cacheMaxMiB").getAsInt() == 16384, "cache budget is hot adjustable");
        check(ClientControlCommand.parse(new String[] {"cache", "status"}).scanArgs.entrySet().isEmpty(), "cache status has no mutation arguments");
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"cache", "33"}));
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"cache", "memory", "63"}));
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"cache", "memory", "16385"}));
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"scan", "0"}));
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"scan", "1", "9"}));
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"scan", "status", "2"}));
    }

    private static void completeCoverage() {
        AtomicLong clock = new AtomicLong();
        WorldScan scan = new WorldScan(clock::get);
        Fixture access = new Fixture(clock, 0, false);
        access.mixed = true;
        JsonObject args = start(-1, 16, 15, 17, 31);
        Set<String> expected = new HashSet<String>();
        long air = 0, unknown = 0;
        for (int x = -18; x <= 16; x++) for (int y = 0; y <= 33; y++) for (int z = -2; z <= 32; z++) {
            int kind = access.kind(x >> 4, y >> 4, z >> 4);
            if (kind == WorldScan.UNKNOWN) unknown++;
            else if (kind == WorldScan.EMPTY || !access.nonAir(x, y, z)) air++;
            else expected.add(key(x, y, z));
        }
        Set<String> actual = new HashSet<String>();
        JsonObject page = null;
        long offset = 0;
        int pages = 0;
        do {
            scan.beginTick();
            GameQueue.Control control = new GameQueue.Control();
            page = scan.page(access, args, new ScanSettings(), 0, new int[] {100, 100, 100}, control);
            check(page.getAsJsonObject("page").get("startOffset").getAsLong() == offset, "page ranges are contiguous");
            offset = page.getAsJsonObject("page").get("endOffsetExclusive").getAsLong();
            check(page.getAsJsonArray("blocks").size() <= 31, "results stay inside page limit");
            for (JsonElement block : page.getAsJsonArray("blocks")) {
                JsonObject b = block.getAsJsonObject();
                check(actual.add(key(b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt())), "scan never repeats an emitted coordinate");
            }
            long spanFrom = page.getAsJsonObject("page").get("startOffset").getAsLong();
            for (JsonElement span : page.getAsJsonArray("visitedSpans")) {
                JsonObject s = span.getAsJsonObject();
                check(s.get("startOffset").getAsLong() == spanFrom && s.get("endOffsetExclusive").getAsLong() > spanFrom, "spans partition the page's actual observations");
                spanFrom = s.get("endOffsetExclusive").getAsLong();
            }
            check(spanFrom == offset, "spans cover every visited cell including skipped air and unloaded regions");
            check(page.getAsJsonObject("center").get("x").getAsInt() == -1, "moving default center cannot shift a continued scan");
            scan.responseFinished(control, true);
            if (!page.get("complete").getAsBoolean()) args = next(page, 31);
            check(++pages < 2000, "bounded fixture completes");
        } while (!page.get("complete").getAsBoolean());
        JsonObject coverage = page.getAsJsonObject("coverage");
        check(actual.equals(expected), "cross-chunk scan returns every expected non-air block without omissions");
        check(coverage.get("air").getAsLong() == air && coverage.get("unloaded").getAsLong() == unknown, "empty sections and unloaded chunks remain distinct");
        check(coverage.get("nonAir").getAsLong() == expected.size() && offset == 35L * 34 * 35 && coverage.get("remaining").getAsLong() == 0, "terminal coverage accounts for the clipped volume exactly");
        check(page.get("nonAtomic").getAsBoolean() && page.get("nextCursor").isJsonNull(), "terminal observation remains explicitly non-atomic");
    }

    private static void transactions() throws Exception {
        AtomicLong clock = new AtomicLong(); Fixture access = new Fixture(clock, 0, true);
        WorldScan scan = new WorldScan(clock::get);
        JsonObject initial = start(0, 32, 0, 2, 1);
        GameQueue.Control first = new GameQueue.Control();
        JsonObject firstPage = scan.page(access, initial, new ScanSettings(), 0, null, first);
        JsonObject cursor = next(firstPage, 1);
        expect("busy", () -> scan.cursorDimension(cursor));
        scan.responseFinished(first, true);
        GameQueue.Control lost = new GameQueue.Control();
        JsonObject lostPage = scan.page(access, cursor, new ScanSettings(), 0, null, lost);
        expect("busy", () -> scan.page(access, cursor, new ScanSettings(), 0, null, new GameQueue.Control()));
        scan.responseFinished(new GameQueue.Control(), true);
        expect("busy", () -> scan.page(access, cursor, new ScanSettings(), 0, null, new GameQueue.Control()));
        scan.responseFinished(lost, false);
        access.padding = "changed after lost delivery";
        GameQueue.Control retry = new GameQueue.Control();
        GameQueue queue = new GameQueue(4);
        GameQueue.Ticket<JsonObject> ticket = queue.submit(() -> scan.page(access, cursor, new ScanSettings(), 0, null, retry), 1000, retry);
        queue.drain(1);
        JsonObject retryPage = queue.await(ticket, 1000);
        check(retryPage.getAsJsonObject("page").get("startOffset").equals(lostPage.getAsJsonObject("page").get("startOffset")), "failed delivery does not advance old cursor");
        check(blockKey(retryPage.getAsJsonArray("blocks").get(0).getAsJsonObject()).equals(blockKey(lostPage.getAsJsonArray("blocks").get(0).getAsJsonObject()))
                && retryPage.getAsJsonArray("blocks").get(0).getAsJsonObject().get("tileClass").getAsString().equals(access.padding), "retry observes the same coordinate's current data rather than replaying a cached block");
        check(!retry.cancelBeforeExecution() && retryPage.get("nonAtomic").getAsBoolean(), "cancellation after actual queue execution cannot claim it cancelled or made the read atomic");
        scan.responseFinished(retry, true);
        expect("stale_cursor", () -> scan.page(access, cursor, new ScanSettings(), 0, null, new GameQueue.Control()));
        GameQueue.Control discarded = new GameQueue.Control();
        JsonObject discardedPage = scan.page(access, initial, new ScanSettings(), 0, null, discarded);
        scan.responseFinished(discarded, false);
        expect("stale_cursor", () -> scan.page(access, next(discardedPage, 1), new ScanSettings(), 0, null, new GameQueue.Control()));
    }

    private static void invalidation() {
        AtomicLong clock = new AtomicLong(); Fixture access = new Fixture(clock, 0, true);
        WorldScan scan = new WorldScan(clock::get);
        JsonObject cursor = delivered(scan, access, start(0, 16, 0, 1, 1), 3);
        check(scan.cursorDimension(cursor) == 0, "cursor remembers the initial dimension");
        cursor.addProperty("x", 0);
        final JsonObject invalidContinuation = cursor;
        expect("invalid_args", () -> scan.page(access, invalidContinuation, new ScanSettings(), 3, null, new GameQueue.Control()));
        cursor.remove("x");
        expect("stale_cursor", () -> scan.page(access, invalidContinuation, new ScanSettings(), 4, null, new GameQueue.Control()));
        cursor = delivered(scan, access, start(0, 16, 0, 1, 1), 3);
        final JsonObject changedWorld = cursor;
        expect("stale_cursor", () -> scan.page(new Fixture(clock, 0, true), changedWorld, new ScanSettings(), 3, null, new GameQueue.Control()));
        cursor = delivered(scan, access, start(0, 16, 0, 1, 1), 3);
        final JsonObject cleared = cursor;
        scan.invalidate();
        expect("stale_cursor", () -> scan.page(access, cleared, new ScanSettings(), 3, null, new GameQueue.Control()));
        cursor = delivered(scan, access, start(0, 16, 0, 1, 1), 3);
        final JsonObject expired = cursor;
        clock.addAndGet(WorldScan.IDLE_NANOS);
        expect("stale_cursor", () -> scan.page(access, expired, new ScanSettings(), 3, null, new GameQueue.Control()));
        JsonObject partial = new JsonObject(); partial.addProperty("x", 0);
        expect("invalid_args", () -> scan.page(access, partial, new ScanSettings(), 3, null, new GameQueue.Control()));
        expect("invalid_args", () -> scan.page(access, new JsonObject(), new ScanSettings(), 3, null, new GameQueue.Control()));
        JsonObject limit = start(0, 16, 0, 1, 513);
        expect("invalid_args", () -> scan.page(access, limit, new ScanSettings(), 3, null, new GameQueue.Control()));
        JsonObject length = new JsonObject(); length.addProperty("cursor", repeat('x', 129));
        expect("invalid_args", () -> scan.page(access, length, new ScanSettings(), 3, null, new GameQueue.Control()));
    }

    private static void budgets() {
        budgets(null);
        budgets("minecraft_air");
    }

    private static void budgets(String airSemantics) {
        AtomicLong clock = new AtomicLong(); Fixture air = new Fixture(clock, 0, false);
        WorldScan scan = new WorldScan(clock::get);
        JsonObject initial = start(0, 128, 0, 128, 512);
        if (airSemantics != null) initial.addProperty("airSemantics", airSemantics);
        JsonObject page = null;
        for (int i = 0; i < 8; i++) {
            GameQueue.Control control = new GameQueue.Control();
            page = scan.page(air, initial, new ScanSettings(), 0, null, control);
            scan.responseFinished(control, true);
        }
        check(air.blocks == WorldScan.CELLS_PER_TICK, "eight queued requests share one cell budget");
        check(page.getAsJsonObject("page").get("examined").getAsLong() == 0 && !page.get("complete").getAsBoolean() && !page.get("nextCursor").isJsonNull(), "exhausted tick returns resumable empty pages, not completion");
        final WorldScan full = scan;
        expect("busy", () -> full.page(air, initial, new ScanSettings(), 0, null, new GameQueue.Control()));

        clock = new AtomicLong();
        AtomicLong timedClock = clock;
        Fixture slow = new Fixture(clock, 0, true); slow.cost = 500000;
        scan = new WorldScan(clock::get);
        GameQueue.Control control = new GameQueue.Control();
        JsonObject timed = scan.page(slow, initial, new ScanSettings(), 0, null, control);
        scan.responseFinished(control, true);
        check(slow.blocks <= 4 && timedClock.get() <= WorldScan.NANOS_PER_TICK, "cooperative time budget stops a costly cell reader");
        JsonObject resume = next(timed, 512);
        control = new GameQueue.Control();
        JsonObject empty = scan.page(slow, resume, new ScanSettings(), 0, null, control);
        check(empty.getAsJsonObject("page").get("cellVisits").getAsInt() == 0, "same tick cannot renew the time budget");
        scan.responseFinished(control, true);
        scan.beginTick();
        control = new GameQueue.Control();
        check(scan.page(slow, next(empty, 512), new ScanSettings(), 0, null, control).getAsJsonObject("page").get("cellVisits").getAsInt() > 0, "next owning tick renews scan work");

        AtomicLong probeClock = new AtomicLong(); Fixture unloaded = new Fixture(probeClock, 0, false); unloaded.allUnknown = true;
        WorldScan skips = new WorldScan(probeClock::get);
        control = new GameQueue.Control();
        JsonObject skipped = skips.page(unloaded, initial, new ScanSettings(), 0, null, control);
        check(unloaded.probes == WorldScan.PROBES_PER_TICK && unloaded.blocks == 0, "unloaded fast skipping shares a bounded probe budget");
        check(skipped.getAsJsonObject("coverage").get("air").getAsLong() == 0 && skipped.getAsJsonArray("visitedSpans").size() == WorldScan.PROBES_PER_TICK, "unknown coverage is explicit rather than air");

        AtomicLong bytesClock = new AtomicLong(); Fixture large = new Fixture(bytesClock, 0, true); large.padding = repeat('x', 700);
        WorldScan byteScan = new WorldScan(bytesClock::get);
        control = new GameQueue.Control();
        JsonObject bounded = byteScan.page(large, initial, new ScanSettings(), 0, null, control);
        check("byte_limit".equals(bounded.get("stopReason").getAsString()) && bounded.getAsJsonObject("page").get("blockBytes").getAsInt() <= WorldScan.BLOCK_BYTES_PER_PAGE, "serialized block bytes bound the page before result count");
        int firstCount = bounded.getAsJsonArray("blocks").size();
        byteScan.responseFinished(control, true);
        byteScan.beginTick();
        control = new GameQueue.Control();
        JsonObject later = byteScan.page(large, next(bounded, 512), new ScanSettings(), 0, null, control);
        check(later.getAsJsonObject("page").get("startOffset").getAsLong() == firstCount, "byte-budget stop does not skip the not-yet-emitted block");
    }

    private static void bounds() {
        AtomicLong clock = new AtomicLong(); Fixture empty = new Fixture(clock, 0, false); empty.allEmpty = true;
        WorldScan scan = new WorldScan(clock::get);
        GameQueue.Control control = new GameQueue.Control();
        JsonObject page = scan.page(empty, start(WorldScan.WORLD_MAX, 0, WorldScan.WORLD_MIN, 128, 512), new ScanSettings(), 0, null, control);
        check(page.getAsJsonObject("bounds").get("maxX").getAsInt() == WorldScan.WORLD_MAX && page.getAsJsonObject("bounds").get("minZ").getAsInt() == WorldScan.WORLD_MIN && page.getAsJsonObject("bounds").get("minY").getAsInt() == 0, "XYZ bounds clip without integer overflow");
        check(page.get("requestedVolume").getAsLong() == 257L * 257 * 257 && page.get("scannableVolume").getAsLong() == 129L * 129 * 129, "reported volumes distinguish requested and clipped coordinates");
        JsonObject bad = start(30000000, 0, 0, 1, 1);
        expect("invalid_args", () -> scan.page(empty, bad, new ScanSettings(), 0, null, new GameQueue.Control()));
        check(empty.blocks == 0, "empty-section fast path never visits individual blocks");
    }

    private static void variableRegions() {
        ScanSettings settings = new ScanSettings();
        int[] player = {10, 64, 20};
        ScanRegion defaults = ScanRegion.read(new JsonObject(), settings, player);
        check("radius".equals(defaults.mode) && defaults.radius == 16 && defaults.verticalRadius == 16
                && defaults.minX == -6 && defaults.maxX == 26 && defaults.minY == 48 && defaults.maxY == 80,
                "new geometry preserves the default player-centered one-chunk cube");
        JsonObject configured = new JsonObject(); configured.addProperty("radiusChunks", 3); settings.apply(configured);
        long revision = settings.revision();
        JsonObject absolute = box(-16, 0, -17, 16, 255, 17);
        ScanRegion region = ScanRegion.read(absolute, settings, null);
        JsonObject description = new JsonObject(); region.describe(description);
        check("bounds".equals(region.mode) && !region.anchored && region.volume == 33L * 256 * 35
                && region.requestedVolume == region.volume, "absolute closed bounds need no player or center and preserve their exact volume");
        check(description.get("center").isJsonNull() && description.get("radiusBlocks").isJsonNull()
                && description.get("verticalRadiusBlocks").isJsonNull(), "absolute bounds do not invent a center or spherical radius");
        String[] facings = {"north", "east", "south", "west"};
        int[][] expected = {{8, 62, 16, 13, 69, 21}, {9, 62, 18, 14, 69, 23},
                {7, 62, 19, 12, 69, 24}, {6, 62, 17, 11, 69, 22}};
        for (int i = 0; i < facings.length; i++) {
            region = ScanRegion.read(direction(facings[i], 4, 1, 2, 3, 5, 2), settings, player);
            check(region.minX == expected[i][0] && region.minY == expected[i][1] && region.minZ == expected[i][2]
                    && region.maxX == expected[i][3] && region.maxY == expected[i][4] && region.maxZ == expected[i][5]
                    && region.volume == 6L * 8 * 6, "asymmetric forward/backward/left/right rotate correctly for " + facings[i]);
        }
        JsonObject explicit = direction("west", 0, 0, 0, 0, 0, 0);
        explicit.addProperty("x", -33); explicit.addProperty("y", 0); explicit.addProperty("z", 17);
        region = ScanRegion.read(explicit, settings, null);
        check(region.volume == 1 && region.x == -33 && region.minY == 0 && "explicit".equals(region.anchorSource),
                "zero directional extents select exactly the complete explicit anchor");
        region = ScanRegion.read(direction("north", 128, 128, 128, 128, 128, 127), settings, new int[] {0, 127, 0});
        check(region.volume == 257L * 256 * 257 && region.minY == 0 && region.maxY == 255,
                "maximum strict directional volume remains bounded and fits world Y exactly");
        check(settings.revision() == revision && settings.radii(new JsonObject())[0] == 48,
                "one-request absolute or directional regions never change configured defaults");

        final JsonObject reverse = box(1, 0, 0, 0, 0, 0);
        expect("invalid_args", () -> ScanRegion.read(reverse, settings, null));
        final JsonObject wide = box(0, 0, 0, 263, 0, 0);
        expect("invalid_args", () -> ScanRegion.read(wide, settings, null));
        check(ScanRegion.read(box(0, 0, 0, 262, 0, 0), settings, null).volume == 263,
                "inclusive explicit axis limit is263 while ordinary radius/directional extents remain128");
        for (String radius : new String[] {"radius", "radiusChunks", "verticalRadiusChunks"}) {
            final JsonObject badBounds = box(0, 0, 0, 1, 1, 1); badBounds.addProperty(radius, 1);
            final JsonObject badDirection = direction("north", 1, 1, 1, 1, 1, 1); badDirection.addProperty(radius, 1);
            expect("invalid_args", () -> ScanRegion.read(badBounds, settings, player));
            expect("invalid_args", () -> ScanRegion.read(badDirection, settings, player));
        }
        final JsonObject both = box(0, 0, 0, 1, 1, 1); both.add("directional", direction("north", 1, 1, 1, 1, 1, 1).get("directional"));
        expect("invalid_args", () -> ScanRegion.read(both, settings, player));
        for (String anchor : new String[] {"x", "y", "z"}) {
            final JsonObject anchoredBounds = box(0, 0, 0, 1, 1, 1); anchoredBounds.addProperty(anchor, 0);
            expect("invalid_args", () -> ScanRegion.read(anchoredBounds, settings, player));
        }
        final JsonObject partialAnchor = direction("north", 1, 1, 1, 1, 1, 1); partialAnchor.addProperty("y", 64);
        expect("invalid_args", () -> ScanRegion.read(partialAnchor, settings, player));
        final JsonObject unanchored = direction("north", 1, 1, 1, 1, 1, 1);
        expect("invalid_args", () -> ScanRegion.read(unanchored, settings, null));
        for (String field : new String[] {"facing", "forward", "backward", "left", "right", "up", "down"}) {
            final JsonObject missing = direction("north", 1, 1, 1, 1, 1, 1); missing.getAsJsonObject("directional").remove(field);
            expect("invalid_args", () -> ScanRegion.read(missing, settings, player));
        }
        final JsonObject unknown = direction("north", 1, 1, 1, 1, 1, 1); unknown.getAsJsonObject("directional").addProperty("yaw", 0);
        expect("invalid_args", () -> ScanRegion.read(unknown, settings, player));
        final JsonObject unknownBox = box(0, 0, 0, 1, 1, 1); unknownBox.getAsJsonObject("bounds").addProperty("radius", 1);
        expect("invalid_args", () -> ScanRegion.read(unknownBox, settings, null));
        final JsonObject badFacing = direction("up", 1, 1, 1, 1, 1, 1);
        expect("invalid_args", () -> ScanRegion.read(badFacing, settings, player));
        final JsonObject wrongShape = new JsonObject(); wrongShape.add("bounds", JsonNull.INSTANCE);
        expect("invalid_args", () -> ScanRegion.read(wrongShape, settings, null));
        wrongShape.remove("bounds"); wrongShape.addProperty("directional", true);
        expect("invalid_args", () -> ScanRegion.read(wrongShape, settings, player));
        final JsonObject fractional = box(0, 0, 0, 1, 1, 1); fractional.getAsJsonObject("bounds").addProperty("minX", 0.5);
        expect("invalid_args", () -> ScanRegion.read(fractional, settings, null));
        final JsonObject overflow = box(0, 0, 0, 1, 1, 1); overflow.getAsJsonObject("bounds").addProperty("maxX", 4294967296L);
        expect("invalid_args", () -> ScanRegion.read(overflow, settings, null));
        final JsonObject badExtent = direction("north", 129, 1, 1, 1, 1, 1);
        expect("invalid_args", () -> ScanRegion.read(badExtent, settings, player));
        badExtent.getAsJsonObject("directional").addProperty("forward", -1);
        expect("invalid_args", () -> ScanRegion.read(badExtent, settings, player));
        badExtent.getAsJsonObject("directional").addProperty("forward", "2");
        expect("invalid_args", () -> ScanRegion.read(badExtent, settings, player));
        final JsonObject below = box(0, -1, 0, 1, 1, 1), above = box(0, 0, 0, 1, 256, 1);
        expect("invalid_args", () -> ScanRegion.read(below, settings, null));
        expect("invalid_args", () -> ScanRegion.read(above, settings, null));
        final JsonObject strictDirection = direction("east", 1, 0, 0, 0, 1, 1);
        expect("invalid_args", () -> ScanRegion.read(strictDirection, settings, new int[] {0, 0, 0}));
        expect("invalid_args", () -> ScanRegion.read(strictDirection, settings, new int[] {0, 255, 0}));
        expect("invalid_args", () -> ScanRegion.read(strictDirection, settings, new int[] {WorldScan.WORLD_MAX, 64, 0}));
        final JsonObject westEdge = direction("west", 1, 0, 0, 0, 0, 0);
        expect("invalid_args", () -> ScanRegion.read(westEdge, settings, new int[] {WorldScan.WORLD_MIN, 64, 0}));
    }

    private static void targetedCoverage() {
        AtomicLong clock = new AtomicLong(); Fixture access = new Fixture(clock, 17, false); access.mixed = true;
        WorldScan scan = new WorldScan(clock::get);
        JsonObject args = box(-18, 0, -2, 16, 33, 32); args.addProperty("dimension", 17); args.addProperty("limit", 31);
        Set<String> expected = new HashSet<String>(), actual = new HashSet<String>();
        long air = 0, unknown = 0;
        for (int x = -18; x <= 16; x++) for (int y = 0; y <= 33; y++) for (int z = -2; z <= 32; z++) {
            int kind = access.kind(x >> 4, y >> 4, z >> 4);
            if (kind == WorldScan.UNKNOWN) unknown++;
            else if (kind == WorldScan.EMPTY || !access.nonAir(x, y, z)) air++;
            else expected.add(key(x, y, z));
        }
        JsonObject page; int pages = 0; long offset = 0;
        do {
            scan.beginTick(); GameQueue.Control control = new GameQueue.Control();
            page = scan.page(access, args, new ScanSettings(), 7, null, control);
            check("bounds".equals(page.get("regionMode").getAsString()) && page.get("center").isJsonNull()
                    && page.getAsJsonObject("page").get("startOffset").getAsLong() == offset,
                    "absolute cross-chunk continuation preserves fixed bounds without a player anchor");
            offset = page.getAsJsonObject("page").get("endOffsetExclusive").getAsLong();
            for (JsonElement value : page.getAsJsonArray("blocks")) check(actual.add(blockKey(value.getAsJsonObject())), "targeted scan emits each observed block once");
            scan.responseFinished(control, true);
            if (!page.get("complete").getAsBoolean()) args = next(page, 31);
            check(++pages < 2000, "targeted mixed scan makes bounded progress");
        } while (!page.get("complete").getAsBoolean());
        JsonObject coverage = page.getAsJsonObject("coverage");
        check(actual.equals(expected) && coverage.get("air").getAsLong() == air && coverage.get("unloaded").getAsLong() == unknown
                && coverage.get("examined").getAsLong() == 35L * 34 * 35, "exact absolute volume classifies loaded air/nonair and unknown independently");
        check(!page.get("requestedRegionFullyObserved").getAsBoolean()
                && coverage.get("loaded").getAsLong() == 35L * 34 * 35 - unknown,
                "terminal traversal with unloaded gaps is not a full observed-region certificate");

        Fixture dense = new Fixture(clock, 0, true); WorldScan directionalScan = new WorldScan(clock::get);
        int[] anchor = {10, 64, 20}; JsonObject initial = direction("east", 2, 0, 1, 0, 1, 0); initial.addProperty("limit", 1);
        GameQueue.Control first = new GameQueue.Control();
        JsonObject firstPage = directionalScan.page(dense, initial, new ScanSettings(), 1, anchor, first);
        JsonObject fixedBounds = firstPage.getAsJsonObject("bounds"); directionalScan.responseFinished(first, true);
        final JsonObject invalidContinuation = next(firstPage, 1); invalidContinuation.add("bounds", box(0, 0, 0, 0, 0, 0).get("bounds"));
        expect("invalid_args", () -> directionalScan.page(dense, invalidContinuation, new ScanSettings(), 1, null, new GameQueue.Control()));
        invalidContinuation.remove("bounds"); invalidContinuation.add("directional", initial.get("directional"));
        expect("invalid_args", () -> directionalScan.page(dense, invalidContinuation, new ScanSettings(), 1, null, new GameQueue.Control()));
        anchor[0] = 1000; initial.getAsJsonObject("directional").addProperty("forward", 128);
        args = next(firstPage, 1); pages = 0;
        do {
            directionalScan.beginTick(); GameQueue.Control control = new GameQueue.Control();
            page = directionalScan.page(dense, args, new ScanSettings(), 1, anchor, control);
            check(page.getAsJsonObject("bounds").equals(fixedBounds) && page.getAsJsonObject("center").get("x").getAsInt() == 10
                    && page.getAsJsonObject("directional").get("forward").getAsInt() == 2,
                    "directional continuation is immutable despite changed player position and mutated first-page args");
            directionalScan.responseFinished(control, true);
            if (!page.get("complete").getAsBoolean()) args = next(page, 1);
            check(++pages < 50, "asymmetric directional scan completes");
        } while (!page.get("complete").getAsBoolean());
        check(page.get("requestedRegionFullyObserved").getAsBoolean() && page.get("scannableVolume").getAsLong() == 12
                && page.getAsJsonObject("coverage").get("nonAir").getAsLong() == 12,
                "fully loaded targeted dense scan certifies its observed region without claiming air");
        Fixture empty = new Fixture(clock, 0, false); empty.allEmpty = true;
        WorldScan one = new WorldScan(clock::get);
        page = one.page(empty, box(-1, 0, -1, -1, 0, -1), new ScanSettings(), 0, null, new GameQueue.Control());
        check(page.get("requestedRegionFullyObserved").getAsBoolean() && page.getAsJsonObject("coverage").get("air").getAsLong() == 1
                && empty.blocks == 0, "single-cell exact world-edge height uses known empty-section coverage");
    }

    private static void publicMaterials() {
        JsonObject rock = WorldScan.publicMaterial(Material.rock), water = WorldScan.publicMaterial(Material.water), snow = WorldScan.publicMaterial(Material.snow);
        check(rock.get("solid").getAsBoolean() && !rock.get("liquid").getAsBoolean() && !rock.get("replaceable").getAsBoolean(), "public rock material retains its coarse solid flags");
        check(!water.get("solid").getAsBoolean() && water.get("liquid").getAsBoolean(), "public liquid material is distinguished from solid terrain");
        check(snow.get("replaceable").getAsBoolean(), "public material replaceability is observed without a world or tile query");
        expect("unsupported", () -> WorldScan.publicMaterial(null));
    }

    private static void airSemantics() {
        Block air = (Block) Block.blockRegistry.getObject("minecraft:air");
        if (air == null) {
            Block.registerBlocks();
            air = (Block) Block.blockRegistry.getObject("minecraft:air");
        }
        AirBlock semantic = new AirBlock(Material.air, true), stone = new AirBlock(Material.rock, false);
        AirBlock water = new AirBlock(Material.water, false), snow = new AirBlock(Material.snow, false);
        Block.blockRegistry.addObject(3001, "bridge_scan_fixture:semantic_air", semantic);
        Block.blockRegistry.addObject(3002, "bridge_scan_fixture:stone", stone);
        Block.blockRegistry.addObject(3003, "bridge_scan_fixture:water", water);
        Block.blockRegistry.addObject(3004, "bridge_scan_fixture:snow", snow);
        AirBlock unregistered = new AirBlock(Material.air, true);
        WorldScan.AirSemantics literal = WorldScan.AirSemantics.MINECRAFT_AIR, defaults = WorldScan.AirSemantics.BLOCK_IS_AIR;
        check(literal.isAir(air, null, 0, 0, 0) && !literal.isAir(semantic, null, 0, 0, 0)
                && !literal.isAir(unregistered, null, 0, 0, 0), "literal air uses the actual exact registered identity, including custom semantic air and unregistered blocks");
        check(semantic.calls == 0 && unregistered.calls == 0, "literal classification does not call custom isAir methods");
        check(defaults.isAir(semantic, null, 0, 0, 0) && defaults.isAir(unregistered, null, 0, 0, 0)
                && semantic.calls == 1 && unregistered.calls == 1, "default semantics retain each block's own isAir result");
        check(!literal.isAir(stone, null, 0, 0, 0) && !literal.isAir(water, null, 0, 0, 0)
                && !literal.isAir(snow, null, 0, 0, 0), "solid, liquid and replaceable identities are all literal non-air");
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, false);
        check(literal.section(null) == WorldScan.EMPTY && literal.section(storage) == WorldScan.EMPTY,
                "native null and zero-count storage can skip after the actual Blocks.air registry identity is verified");
        storage.setExtBlockID(0, 0, 0, semantic);
        check(!storage.isEmpty() && literal.section(storage) == WorldScan.PRESENT && defaults.section(storage) == WorldScan.PRESENT,
                "native storage does not collapse a custom semantic-air block into an empty section");
        storage.setExtBlockID(0, 0, 0, air);
        check(storage.isEmpty() && literal.section(storage) == WorldScan.EMPTY, "native air removal restores the identity-counted empty fast path");

        AtomicLong clock = new AtomicLong();
        AirFixture access = new AirFixture(air, semantic, stone, water, snow, air, unregistered);
        JsonObject initial = box(0, 0, 0, 6, 0, 0);
        WorldScan defaultScan = new WorldScan(clock::get);
        GameQueue.Control defaultControl = new GameQueue.Control();
        JsonObject defaultPage = defaultScan.page(access, initial, new ScanSettings(), 0, null, defaultControl);
        check("block_is_air".equals(defaultPage.get("airSemantics").getAsString())
                && defaultPage.getAsJsonObject("coverage").get("air").getAsLong() == 4
                && defaultPage.getAsJsonObject("coverage").get("nonAir").getAsLong() == 3,
                "omitted air semantics keep custom semantic-air blocks excluded from default results");
        defaultScan.responseFinished(defaultControl, true);
        initial.addProperty("airSemantics", "block_is_air");
        GameQueue.Control explicitControl = new GameQueue.Control();
        JsonObject explicit = defaultScan.page(access, initial, new ScanSettings(), 0, null, explicitControl);
        check(explicit.getAsJsonObject("coverage").equals(defaultPage.getAsJsonObject("coverage"))
                && explicit.getAsJsonArray("blocks").equals(defaultPage.getAsJsonArray("blocks")), "explicit default air semantics produce the same observations");
        defaultScan.responseFinished(explicitControl, true);

        WorldScan scan = new WorldScan(clock::get);
        initial.addProperty("airSemantics", "minecraft_air"); initial.addProperty("limit", 1);
        GameQueue.Control first = new GameQueue.Control();
        JsonObject firstPage = scan.page(access, initial, new ScanSettings(), 4, null, first);
        check("minecraft_air".equals(firstPage.get("airSemantics").getAsString())
                && "bridge_scan_fixture:semantic_air".equals(firstPage.getAsJsonArray("blocks").get(0).getAsJsonObject().get("registryName").getAsString())
                && firstPage.getAsJsonObject("page").get("endOffsetExclusive").getAsLong() == 2,
                "literal scan emits custom semantic air and accounts for preceding canonical air");
        JsonObject cursor = next(firstPage, 1);
        expect("busy", () -> scan.page(access, cursor, new ScanSettings(), 4, null, new GameQueue.Control()));
        scan.responseFinished(first, true);
        cursor.addProperty("airSemantics", "minecraft_air");
        expect("invalid_args", () -> scan.page(access, cursor, new ScanSettings(), 4, null, new GameQueue.Control()));
        cursor.remove("airSemantics"); initial.addProperty("airSemantics", "block_is_air");
        GameQueue.Control lost = new GameQueue.Control();
        JsonObject lostPage = scan.page(access, cursor, new ScanSettings(), 4, null, lost);
        check("minecraft_air".equals(lostPage.get("airSemantics").getAsString())
                && blockKey(lostPage.getAsJsonArray("blocks").get(0).getAsJsonObject()).equals("2,0,0"),
                "continued literal semantics are immutable despite changed first-page args");
        scan.responseFinished(lost, false);
        access.blocks[2] = air;
        GameQueue.Control retry = new GameQueue.Control();
        JsonObject page = scan.page(access, cursor, new ScanSettings(), 4, null, retry);
        check(page.getAsJsonObject("page").get("startOffset").getAsLong() == 2
                && blockKey(page.getAsJsonArray("blocks").get(0).getAsJsonObject()).equals("3,0,0")
                && page.getAsJsonObject("coverage").get("nonAir").getAsLong() == 2,
                "lost literal delivery retains the old cursor and retries fresh identities without committing the lost non-air count");
        scan.responseFinished(retry, true);
        expect("stale_cursor", () -> scan.page(access, cursor, new ScanSettings(), 4, null, new GameQueue.Control()));
        long offset = page.getAsJsonObject("page").get("endOffsetExclusive").getAsLong();
        int pages = 0;
        while (!page.get("complete").getAsBoolean()) {
            scan.beginTick(); GameQueue.Control control = new GameQueue.Control();
            page = scan.page(access, next(page, 1), new ScanSettings(), 4, null, control);
            check("minecraft_air".equals(page.get("airSemantics").getAsString())
                    && page.getAsJsonObject("page").get("startOffset").getAsLong() == offset,
                    "all literal continuation pages preserve semantics and contiguous examined prefixes");
            offset = page.getAsJsonObject("page").get("endOffsetExclusive").getAsLong();
            scan.responseFinished(control, true);
            check(++pages < 10, "literal fixture finishes within bounded pages");
        }
        check(page.get("requestedRegionFullyObserved").getAsBoolean() && offset == 7
                && page.getAsJsonObject("coverage").get("air").getAsLong() == 3
                && page.getAsJsonObject("coverage").get("nonAir").getAsLong() == 4,
                "literal terminal coverage retains custom, liquid, replaceable and unknown-identity blockers after a fresh retry");

        for (JsonElement invalid : new JsonElement[] {JsonNull.INSTANCE, new com.google.gson.JsonPrimitive(true),
                new com.google.gson.JsonPrimitive(1), new com.google.gson.JsonArray(), new JsonObject(),
                new com.google.gson.JsonPrimitive(""), new com.google.gson.JsonPrimitive("Minecraft_air"),
                new com.google.gson.JsonPrimitive(" minecraft_air"), new com.google.gson.JsonPrimitive("isAir")}) {
            final JsonObject bad = box(0, 0, 0, 0, 0, 0); bad.add("airSemantics", invalid);
            int callsBefore = access.reads;
            expect("invalid_args", () -> scan.page(access, bad, new ScanSettings(), 4, null, new GameQueue.Control()));
            check(access.reads == callsBefore, "invalid air semantics fail before any world observations");
        }
        Fixture unknown = new Fixture(clock, 0, false); unknown.allUnknown = true;
        JsonObject literalBounds = box(0, 0, 0, 15, 0, 15); literalBounds.addProperty("airSemantics", "minecraft_air");
        page = new WorldScan(clock::get).page(unknown, literalBounds, new ScanSettings(), 0, null, new GameQueue.Control());
        check(page.get("complete").getAsBoolean() && !page.get("requestedRegionFullyObserved").getAsBoolean()
                && page.getAsJsonObject("coverage").get("unloaded").getAsLong() == 256
                && page.getAsJsonObject("coverage").get("air").getAsLong() == 0, "literal mode never promotes unloaded coverage to known air");
        Fixture empty = new Fixture(clock, 0, false); empty.allEmpty = true;
        JsonObject clipped = start(0, 0, 0, 1, 512); clipped.addProperty("airSemantics", "minecraft_air");
        page = new WorldScan(clock::get).page(empty, clipped, new ScanSettings(), 0, null, new GameQueue.Control());
        check(page.get("complete").getAsBoolean() && page.get("outOfWorld").getAsLong() == 9
                && !page.get("requestedRegionFullyObserved").getAsBoolean(), "literal mode preserves clipped requested-region rejection");
    }

    private static JsonObject box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        JsonObject value = new JsonObject(); value.addProperty("minX", minX); value.addProperty("minY", minY); value.addProperty("minZ", minZ);
        value.addProperty("maxX", maxX); value.addProperty("maxY", maxY); value.addProperty("maxZ", maxZ);
        JsonObject args = new JsonObject(); args.add("bounds", value); return args;
    }
    private static JsonObject direction(String facing, int forward, int backward, int left, int right, int up, int down) {
        JsonObject value = new JsonObject(); value.addProperty("facing", facing); value.addProperty("forward", forward); value.addProperty("backward", backward);
        value.addProperty("left", left); value.addProperty("right", right); value.addProperty("up", up); value.addProperty("down", down);
        JsonObject args = new JsonObject(); args.add("directional", value); return args;
    }

    /** Real Java timing/JSON size evidence; fixture timings are not claimed as RC1 frame timings. */
    private static void benchmark() {
        for (int i = 0; i < 12; i++) {
            Fixture warm = new Fixture(null, 0, true); WorldScan scan = new WorldScan();
            scan.page(warm, start(0, 64, 0, 16, 512), new ScanSettings(), 0, null, new GameQueue.Control());
        }
        long totalStart = System.nanoTime();
        Fixture dense = new Fixture(null, 0, true); WorldScan scan = new WorldScan();
        JsonObject args = start(0, 64, 0, 16, 512);
        long maxPage = 0, scanNanos = 0; int maxBytes = 0, pages = 0, count = 0;
        JsonObject page;
        do {
            scan.beginTick(); GameQueue.Control control = new GameQueue.Control();
            long start = System.nanoTime();
            page = scan.page(dense, args, new ScanSettings(), 0, null, control);
            maxPage = Math.max(maxPage, System.nanoTime() - start);
            scanNanos += page.getAsJsonObject("timing").get("elapsedNanos").getAsLong();
            maxBytes = Math.max(maxBytes, page.toString().getBytes(StandardCharsets.UTF_8).length);
            count += page.getAsJsonArray("blocks").size(); pages++;
            scan.responseFinished(control, true);
            if (!page.get("complete").getAsBoolean()) args = next(page, 512);
            check(pages < 20000, "timed default dense scan keeps progressing");
        } while (!page.get("complete").getAsBoolean());
        check(count == 35937 && maxBytes < 1024 * 1024, "timed dense default volume is complete and stays under HTTP cap");
        System.out.println("WorldScan benchmark fixture(default dense 33^3): pages=" + pages + ", blocks=" + count + ", maxPageBytes=" + maxBytes
                + ", maxCallMillis=" + maxPage / 1000000D + ", scanMillis=" + scanNanos / 1000000D
                + ", totalRunMillis=" + (System.nanoTime() - totalStart) / 1000000D + ", onePagePer20HzTickMinimumMillis=" + pages * 50L);
        Fixture empty = new Fixture(null, 0, false); empty.allEmpty = true;
        scan = new WorldScan(); args = start(0, 128, 0, 128, 512); pages = 0; maxBytes = 0;
        long emptyStart = System.nanoTime();
        do {
            scan.beginTick(); GameQueue.Control control = new GameQueue.Control();
            page = scan.page(empty, args, new ScanSettings(), 0, null, control);
            maxBytes = Math.max(maxBytes, page.toString().getBytes(StandardCharsets.UTF_8).length); pages++;
            scan.responseFinished(control, true);
            if (!page.get("complete").getAsBoolean()) args = next(page, 512);
            check(pages < 2000, "timed maximum empty scan keeps progressing");
        } while (!page.get("complete").getAsBoolean());
        check(page.getAsJsonObject("coverage").get("air").getAsLong() == 16908544 && empty.blocks == 0, "maximum empty scan accounts for sixteen million cells without visiting them");
        System.out.println("WorldScan benchmark fixture(max empty): pages=" + pages + ", sections=" + empty.probes + ", maxPageBytes=" + maxBytes
                + ", totalRunMillis=" + (System.nanoTime() - emptyStart) / 1000000D + ", onePagePer20HzTickMinimumMillis=" + pages * 50L);
    }

    private static JsonObject delivered(WorldScan scan, Fixture access, JsonObject args, long epoch) {
        scan.beginTick(); GameQueue.Control control = new GameQueue.Control();
        JsonObject page = scan.page(access, args, new ScanSettings(), epoch, null, control);
        scan.responseFinished(control, true);
        return next(page, 1);
    }
    private static JsonObject start(int x, int y, int z, int radius, int limit) {
        JsonObject args = new JsonObject(); args.addProperty("x", x); args.addProperty("y", y); args.addProperty("z", z); args.addProperty("radius", radius); args.addProperty("limit", limit); return args;
    }
    private static JsonObject next(JsonObject page, int limit) {
        JsonObject args = new JsonObject(); args.addProperty("cursor", page.get("nextCursor").getAsString()); args.addProperty("limit", limit); return args;
    }
    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    private static String blockKey(JsonObject block) { return key(block.get("x").getAsInt(), block.get("y").getAsInt(), block.get("z").getAsInt()); }
    private static String repeat(char c, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, c); return new String(chars); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "Expected " + code + ", got " + error.code); }
    }
    private static final class AirBlock extends Block {
        final boolean air; int calls;
        AirBlock(Material material, boolean air) { super(material); this.air = air; }
        @Override public boolean isAir(IBlockAccess world, int x, int y, int z) { calls++; return air; }
    }
    private static final class AirFixture implements WorldScan.Access {
        final Block[] blocks; int reads;
        AirFixture(Block... blocks) { this.blocks = blocks; }
        public Object identity() { return this; }
        public int dimension() { return 0; }
        public String source() { return "fixture"; }
        public long tick() { return reads; }
        public int section(int cx, int sy, int cz, WorldScan.AirSemantics semantics) { return WorldScan.PRESENT; }
        public JsonObject block(int x, int y, int z, WorldScan.AirSemantics semantics) {
            reads++; Block block = blocks[x];
            if (semantics.isAir(block, null, x, y, z)) return null;
            JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
            result.addProperty("registryName", String.valueOf(Block.blockRegistry.getNameForObject(block)));
            result.addProperty("metadata", x); result.add("publicMaterial", WorldScan.publicMaterial(block.getMaterial()));
            return result;
        }
    }
    private static final class Fixture implements WorldScan.Access {
        final AtomicLong clock; final int dimension; final boolean dense;
        boolean mixed, allEmpty, allUnknown;
        int blocks, probes; long cost; String padding;
        Fixture(AtomicLong clock, int dimension, boolean dense) { this.clock = clock; this.dimension = dimension; this.dense = dense; }
        public Object identity() { return this; }
        public int dimension() { return dimension; }
        public String source() { return "fixture"; }
        public long tick() { return blocks / 100; }
        int kind(int cx, int sy, int cz) {
            if (allUnknown || mixed && cx == 0 && cz == 0) return WorldScan.UNKNOWN;
            if (allEmpty || mixed && sy == 2) return WorldScan.EMPTY;
            return WorldScan.PRESENT;
        }
        public int section(int cx, int sy, int cz, WorldScan.AirSemantics airSemantics) { probes++; return kind(cx, sy, cz); }
        boolean nonAir(int x, int y, int z) { return dense || mixed && Math.floorMod(x * 31 + y * 17 + z, 5) == 0; }
        public JsonObject block(int x, int y, int z, WorldScan.AirSemantics airSemantics) {
            blocks++; if (clock != null) clock.addAndGet(cost);
            if (!nonAir(x, y, z)) return null;
            JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
            result.addProperty("registryName", "fixture:stone"); result.addProperty("metadata", 0);
            if (padding != null) result.addProperty("tileClass", padding);
            return result;
        }
    }
}
