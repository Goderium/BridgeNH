package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.ObservationGeometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Real pure comparison/geometry tests; game-object observations are verified in the live client. */
public class ExpandedObservationSelfTest {
    private static int checks;

    public static int run() {
        checks = 0;
        geometry(); entityIntersections(); diff();
        return checks;
    }

    private static void entityIntersections() {
        double[] query = {0, 60, 0, 8, 72, 8};
        check(ObservationGeometry.boxIntersection(new double[] {-2, 65, 2, 1, 67, 4}, query) == 1,
                "an entity centered outside still blocks the fixture when its body overlaps");
        check(ObservationGeometry.boxIntersection(new double[] {-2, 65, 2, 0, 67, 4}, query) == 0,
                "a body touching the outer face without overlap is disjoint");
        check(ObservationGeometry.boxIntersection(new double[] {2, 72, 2, 4, 74, 4}, query) == 0,
                "vertical separation is respected");
        check(ObservationGeometry.boxIntersection(new double[] {-2, 59, -2, 10, 74, 10}, query) == 1,
                "an enclosing large body is included");
        check(ObservationGeometry.boxIntersection(null, query) == -1, "missing bounds are unknown");
        check(ObservationGeometry.boxIntersection(new double[] {Double.NaN, 60, 0, 1, 61, 1}, query) == -1,
                "nonfinite bounds are unknown");
        check(ObservationGeometry.boxIntersection(new double[] {2, 60, 0, 1, 61, 1}, query) == -1,
                "reversed bounds are unknown");
        check(ObservationGeometry.boxIntersection(new double[] {1, 60, 0, 1, 61, 1}, query) == -1,
                "degenerate bounds are unknown");
    }

    private static void geometry() {
        double[][] starts = {{0, 1, 0}, {-16, 3, -16}, {.5, 3.5, -.5}, {29999990, 4, -29999990}};
        double[][] directions = {{-.1, 0, 0}, {0, 0, 127}, {5, -3, 8}, {-18, 22, -31}};
        for (double[] start : starts) for (double[] direction : directions) {
            double[] end = {start[0] + direction[0], start[1] + direction[1], start[2] + direction[2]};
            List<int[]> cells = ObservationGeometry.rayCells(start, end);
            check(cells.size() <= 512, "geometry has a bounded traversal");
            check(cells.get(0)[0] == (int) Math.floor(start[0]) && cells.get(0)[1] == (int) Math.floor(start[1])
                    && cells.get(0)[2] == (int) Math.floor(start[2]), "geometry begins at the containing voxel");
            int[] last = cells.get(cells.size() - 1);
            check(last[0] == (int) Math.floor(end[0]) && last[1] == (int) Math.floor(end[1]) && last[2] == (int) Math.floor(end[2]), "geometry reaches the endpoint");
            Set<String> unique = new HashSet<String>();
            int[] previous = null;
            for (int[] cell : cells) {
                check(unique.add(cell[0] + ":" + cell[1] + ":" + cell[2]), "geometry does not duplicate voxels");
                if (previous != null) check(Math.abs(cell[0] - previous[0]) + Math.abs(cell[1] - previous[1]) + Math.abs(cell[2] - previous[2]) == 1,
                        "geometry visits adjacent voxels even at edge ties");
                previous = cell;
            }
        }
        expect("invalid_args", () -> ObservationGeometry.rayCells(new double[] {0, 0, 0}, new double[] {0, 0, 0}));
        expect("invalid_args", () -> ObservationGeometry.rayCells(new double[] {0, 0, 0}, new double[] {129, 0, 0}));
        expect("invalid_args", () -> ObservationGeometry.rayCells(new double[] {Double.NaN, 0, 0}, new double[] {1, 0, 0}));
    }

    private static void diff() {
        AtomicLong clock = new AtomicLong();
        final StateDiffAdapter adapter = new StateDiffAdapter(clock::get);
        Object world = new Object();
        JsonObject original = json("{\"player\":{\"slot\":0,\"a/b~\":true},\"inventory\":{\"slots\":[1,2,3]}}");
        JsonObject initial = adapter.observe(new JsonObject(), original, world, "player", "session", 1);
        final String originalId = initial.get("baselineId").getAsString();
        check(initial.get("created").getAsBoolean() && initial.getAsJsonArray("changes").size() == 0, "baseline creation is explicit and compact");
        original.getAsJsonObject("player").addProperty("slot", 999);
        JsonObject current = json("{\"player\":{\"slot\":1,\"a/b~\":false,\"new\":7},\"inventory\":{\"slots\":[1,2]}}");
        JsonObject args = json("{\"baselineId\":\"" + originalId + "\",\"limit\":1}");
        List<JsonObject> changes = collect(adapter, args, current, world);
        check(changes.size() == 4, "paged comparison reports add, replace and remove");
        boolean immutable = false, escaped = false, removed = false;
        for (JsonObject change : changes) {
            String path = change.get("path").getAsString();
            if ("/player/slot".equals(path)) immutable = change.get("before").getAsInt() == 0 && change.get("after").getAsInt() == 1;
            if ("/player/a~1b~0".equals(path)) escaped = true;
            if ("/inventory/slots/2".equals(path)) removed = !change.get("afterPresent").getAsBoolean();
        }
        check(immutable, "baseline copied the original instead of retaining mutable input");
        check(escaped, "paths escape slash and tilde"); check(removed, "array removal retains presence information");
        check(collect(adapter, args, current, world).toString().equals(changes.toString()), "reading differences does not advance the baseline");
        expect("invalid_args", () -> adapter.sections(json("{\"sections\":[\"gui\",\"gui\"]}")));
        expect("invalid_args", () -> adapter.sections(json("{\"sections\":[]}")));
        expect("invalid_args", () -> adapter.sections(json("{\"baselineId\":\"" + originalId + "\",\"sections\":[\"player\"]}")));
        expect("stale_baseline", () -> adapter.observe(json("{\"baselineId\":\"" + originalId + "\"}"), current, new Object(), "player", "session", 2));

        JsonObject many = new JsonObject();
        JsonArray rows = new JsonArray();
        for (int i = 0; i < 1800; i++) rows.add(json("{\"value\":" + i + "}"));
        many.add("gui", rows);
        initial = adapter.observe(json("{\"sections\":[\"gui\"]}"), many, world, "player", "session", 3);
        String id = initial.get("baselineId").getAsString();
        rows.get(1799).getAsJsonObject().addProperty("value", -1);
        args = json("{\"baselineId\":\"" + id + "\"}");
        changes = collect(adapter, args, many, world);
        check(changes.size() == 1 && "/gui/1799/value".equals(changes.get(0).get("path").getAsString()), "node pagination progresses across unchanged data to a late change");
        clock.set(StateDiffAdapter.IDLE_NANOS + 1);
        final JsonObject expiredArgs = args;
        expect("stale_baseline", () -> adapter.observe(expiredArgs, many, world, "player", "session", 4));

        final StateDiffAdapter moreAdapter = new StateDiffAdapter(clock::get);
        String first = null;
        for (int i = 0; i < 5; i++) {
            JsonObject entry = moreAdapter.observe(new JsonObject(), json("{\"player\":{\"x\":0}}"), world, "player", "s", i);
            if (first == null) first = entry.get("baselineId").getAsString();
        }
        final StateDiffAdapter evictedAdapter = moreAdapter;
        final String evictedId = first;
        expect("stale_baseline", () -> evictedAdapter.sections(json("{\"baselineId\":\"" + evictedId + "\"}")));
        JsonObject large = json("{\"player\":\"small\"}");
        JsonObject entry = moreAdapter.observe(new JsonObject(), large, world, "player", "s", 10);
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 10000; i++) text.append('x');
        large.addProperty("player", text.toString());
        JsonObject page = moreAdapter.observe(json("{\"baselineId\":\"" + entry.get("baselineId").getAsString() + "\"}"), large, world, "player", "s", 11);
        check(page.getAsJsonArray("changes").get(0).getAsJsonObject().get("valueTruncated").getAsBoolean(), "large values use an explicit summary");
        check(page.toString().length() < StateDiffAdapter.RESULT_BYTES, "summary response remains bounded");
        moreAdapter.invalidate();
        expect("stale_baseline", () -> evictedAdapter.sections(json("{\"baselineId\":\"" + entry.get("baselineId").getAsString() + "\"}")));
    }

    private static List<JsonObject> collect(StateDiffAdapter adapter, JsonObject args, JsonObject current, Object world) {
        JsonObject request = new JsonParser().parse(args.toString()).getAsJsonObject();
        List<JsonObject> changes = new ArrayList<JsonObject>();
        for (int i = 0; i < 100; i++) {
            JsonObject page = adapter.observe(request, current, world, "player", "session", 2);
            for (JsonElement change : page.getAsJsonArray("changes")) changes.add(change.getAsJsonObject());
            if (!page.get("hasMore").getAsBoolean()) return changes;
            int previous = request.has("offset") ? request.get("offset").getAsInt() : 0;
            check(page.get("nextOffset").getAsInt() > previous, "node pagination always makes progress");
            request.addProperty("offset", page.get("nextOffset").getAsInt());
        }
        throw new AssertionError("pagination did not finish");
    }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void expect(String code, Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), "expected error " + code); }
    }
}
