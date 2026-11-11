package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Verifies compact-prefix coverage/errors/bytes/budget using a pure loaded-block reader fixture. */
public final class WorldInspectManySelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        completeAndErrors();
        softBudgetAndFreshSuffix();
        byteLimits();
        return checks;
    }

    private static void completeAndErrors() {
        Fixture f = new Fixture(); JsonObject result = f.read(positions(128));
        check(result.get("requestedCount").getAsInt() == 128 && result.get("processedCount").getAsInt() == 128
                && result.get("nextOffset").getAsInt() == 128 && result.get("complete").getAsBoolean()
                && !result.get("partial").getAsBoolean() && f.calls == 128,
                "compact inspection covers an explicit 128-point request without the legacy 16-point RPC split");
        check("integrated_server".equals(result.get("source").getAsString()) && result.get("dimension").getAsInt() == 180
                && result.get("worldTick").getAsLong() == 73 && result.get("nonAtomic").getAsBoolean(),
                "compact batch keeps actual source/dimension/tick and does not claim an atomic world snapshot");
        JsonArray replies = result.getAsJsonArray("results");
        for (int i = 0; i < replies.size(); i++) {
            JsonObject point = replies.get(i).getAsJsonObject(), data = point.getAsJsonObject("data");
            check(point.get("ok").getAsBoolean() && point.get("requestIndex").getAsInt() == i
                    && data.get("x").getAsInt() == i && data.get("y").getAsInt() == 64 && data.get("z").getAsInt() == -i
                    && data.get("loaded").getAsBoolean() && "integrated_server".equals(data.get("source").getAsString())
                    && "minecraft:air".equals(data.get("registryName").getAsString()) && data.get("metadata").getAsInt() == 0,
                    "every compact reply retains the exact corresponding loaded server coordinate and block identity");
        }
        expectInvalid(() -> f.read(positions(0)));
        expectInvalid(() -> f.read(positions(129)));
        Fixture missing = new Fixture(); missing.unloadedX = 2;
        JsonObject failures = missing.read(positions(4));
        check(failures.get("complete").getAsBoolean() && failures.get("processedCount").getAsInt() == 4
                && !failures.getAsJsonArray("results").get(2).getAsJsonObject().get("ok").getAsBoolean()
                && "chunk_unloaded".equals(failures.getAsJsonArray("results").get(2).getAsJsonObject().getAsJsonObject("error").get("code").getAsString()),
                "coverage complete never hides a per-point unknown/unloaded error as a proved air result");
        JsonObject invalid = positions(3); invalid.getAsJsonArray("positions").get(1).getAsJsonObject().addProperty("y", 256);
        Fixture malformed = new Fixture(); JsonObject malformedReply = malformed.read(invalid);
        check(malformed.calls == 2 && !malformedReply.getAsJsonArray("results").get(1).getAsJsonObject().get("ok").getAsBoolean()
                && malformedReply.getAsJsonArray("results").get(1).getAsJsonObject().get("requestIndex").getAsInt() == 1,
                "invalid coordinates are rejected before the world reader and do not reorder later coverage");
        Fixture absent = new Fixture(); absent.nullX = 0;
        check(!absent.read(positions(1)).getAsJsonArray("results").get(0).getAsJsonObject().get("ok").getAsBoolean(),
                "missing data cannot be converted to known air");
        Fixture client = new Fixture(); client.source = "client";
        JsonObject clientReply = client.read(positions(1));
        check("client".equals(clientReply.get("source").getAsString())
                && "client".equals(clientReply.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonObject("data").get("source").getAsString()),
                "client compact reads cannot claim integrated-server provenance");
    }

    private static void softBudgetAndFreshSuffix() {
        Fixture f = new Fixture(); f.nanosPerRead = WorldInspectManyAdapter.WORK_BUDGET_NANOS + 1;
        JsonObject original = positions(128), first = f.read(original);
        check(first.get("processedCount").getAsInt() == 1 && first.get("nextOffset").getAsInt() == 1
                && first.get("partial").getAsBoolean() && !first.get("complete").getAsBoolean()
                && first.get("budgetLimited").getAsBoolean() && first.getAsJsonObject("budget").get("softBudgetExceeded").getAsBoolean(),
                "a non-preemptible first read progresses once then yields with explicit incomplete prefix coverage");
        JsonObject suffix = new JsonObject(); JsonArray remaining = new JsonArray();
        for (int i = 1; i < 128; i++) remaining.add(original.getAsJsonArray("positions").get(i));
        suffix.add("positions", remaining);
        f.nanosPerRead = 0; f.registry = "minecraft:stone";
        JsonObject second = f.read(suffix), firstRemaining = second.getAsJsonArray("results").get(0).getAsJsonObject();
        check(second.get("processedCount").getAsInt() == 127 && second.get("complete").getAsBoolean()
                && firstRemaining.get("requestIndex").getAsInt() == 0 && firstRemaining.getAsJsonObject("data").get("x").getAsInt() == 1
                && "minecraft:stone".equals(firstRemaining.getAsJsonObject("data").get("registryName").getAsString()) && f.calls == 128,
                "explicit suffix continuation reads fresh current blocks and has no hidden cached world cursor");
    }

    private static void byteLimits() {
        Fixture bytes = new Fixture(); bytes.padding = repeat("x", 40000);
        JsonObject partial = bytes.read(positions(128));
        check(partial.get("partial").getAsBoolean() && !partial.get("complete").getAsBoolean()
                && partial.get("byteLimited").getAsBoolean() && partial.get("processedCount").getAsInt() > 0
                && partial.get("nextOffset").getAsInt() == partial.getAsJsonArray("results").size()
                && partial.toString().getBytes(StandardCharsets.UTF_8).length <= WorldInspectManyAdapter.BYTE_LIMIT,
                "total batch bytes yield a finite exact prefix and retain a usable explicit suffix");
        Fixture unicode = new Fixture(); unicode.padding = repeat("中文", 7000);
        JsonObject utf8 = unicode.read(positions(128));
        check(utf8.get("byteLimited").getAsBoolean() && utf8.toString().getBytes(StandardCharsets.UTF_8).length <= WorldInspectManyAdapter.BYTE_LIMIT,
                "response accounting uses UTF-8 bytes rather than character counts");
        Fixture oversized = new Fixture(); oversized.padding = repeat("x", WorldInspectManyAdapter.POINT_BYTE_LIMIT);
        JsonObject point = oversized.read(positions(1)).getAsJsonArray("results").get(0).getAsJsonObject();
        check(!point.get("ok").getAsBoolean() && "result_too_large".equals(point.getAsJsonObject("error").get("code").getAsString())
                && !point.has("data"), "the existing 48-KiB per-point bound returns an explicit failure without leaking oversized data");
    }

    private static JsonObject positions(int count) {
        JsonObject args = new JsonObject(); JsonArray positions = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject point = new JsonObject(); point.addProperty("x", i); point.addProperty("y", 64); point.addProperty("z", -i); positions.add(point);
        }
        args.add("positions", positions); return args;
    }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid_args"); }
        catch (BridgeException failure) { check("invalid_args".equals(failure.code), "invalid compact batch shape has a precise error"); }
    }
    private static final class Fixture implements WorldInspectManyAdapter.Access {
        final AtomicLong clock = new AtomicLong(); int calls, unloadedX = -1, nullX = -1;
        long nanosPerRead; String source = "integrated_server", registry = "minecraft:air", padding;
        public String source() { return source; }
        public int dimension() { return 180; }
        public long tick() { return 73; }
        public JsonObject block(int x, int y, int z) {
            calls++; clock.addAndGet(nanosPerRead);
            if (x == unloadedX) throw new BridgeException("chunk_unloaded", "Fixture point is not loaded.");
            if (x == nullX) return null;
            JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z);
            point.addProperty("source", source); point.addProperty("dimension", 180); point.addProperty("loaded", true);
            point.addProperty("registryName", registry); point.addProperty("metadata", 0);
            if (padding != null) point.addProperty("fixturePadding", padding); return point;
        }
        JsonObject read(JsonObject args) { return WorldInspectManyAdapter.read(this, args, clock::get); }
    }
}
