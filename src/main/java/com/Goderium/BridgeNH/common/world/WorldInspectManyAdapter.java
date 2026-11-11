package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** Bounded compact read prefixes; a continuation is a fresh read of the caller's explicit remaining coordinates. */
public final class WorldInspectManyAdapter {
    public static final int POSITION_LIMIT = 128, BYTE_LIMIT = 128 * 1024, POINT_BYTE_LIMIT = 48 * 1024;
    public static final long WORK_BUDGET_NANOS = 4000000L;

    interface Access {
        String source();
        int dimension();
        long tick();
        JsonObject block(int x, int y, int z);
    }

    static JsonObject read(Access access, JsonObject args) { return read(access, args, System::nanoTime); }

    static JsonObject read(Access access, JsonObject args, LongSupplier clock) {
        JsonElement requested = args.get("positions");
        if (requested == null || !requested.isJsonArray() || requested.getAsJsonArray().size() < 1
                || requested.getAsJsonArray().size() > POSITION_LIMIT)
            throw new BridgeException("invalid_args", "compact positions must contain 1 through " + POSITION_LIMIT + " coordinate objects.");
        long started = clock.getAsLong(); JsonArray positions = requested.getAsJsonArray(), results = new JsonArray();
        JsonObject result = new JsonObject(); result.addProperty("source", access.source());
        result.addProperty("dimension", access.dimension()); result.addProperty("worldTick", access.tick());
        result.addProperty("compact", true); result.addProperty("nonAtomic", true);
        result.addProperty("requestedCount", positions.size()); result.add("results", results);
        int usedBytes = bytes(result) + 2048; boolean byteLimited = false, budgetLimited = false;
        for (int i = 0; i < positions.size(); i++) {
            if (results.size() > 0 && clock.getAsLong() - started >= WORK_BUDGET_NANOS) { budgetLimited = true; break; }
            JsonObject point = new JsonObject(); point.addProperty("requestIndex", i);
            try {
                JsonElement position = positions.get(i);
                if (!position.isJsonObject()) throw new BridgeException("invalid_args", "Each position must be a coordinate object.");
                JsonObject coordinates = position.getAsJsonObject();
                int x = JsonData.integer(coordinates, "x", -30000000, 30000000);
                int y = JsonData.integer(coordinates, "y", 0, 255);
                int z = JsonData.integer(coordinates, "z", -30000000, 30000000);
                JsonObject data = access.block(x, y, z);
                if (data == null) throw new BridgeException("inspection_failed", "Compact point did not return explicit block data.");
                if (bytes(data) > POINT_BYTE_LIMIT)
                    throw new BridgeException("result_too_large", "This point exceeds the batch per-position limit of 48 KiB; inspect it separately.");
                point.addProperty("ok", true); point.add("data", data);
            } catch (RuntimeException | LinkageError failure) {
                JsonObject error = new JsonObject();
                error.addProperty("code", failure instanceof BridgeException ? ((BridgeException) failure).code : "inspection_failed");
                error.addProperty("message", failure instanceof BridgeException ? JsonData.bounded(failure.getMessage(), 512)
                        : "Inspection failed: " + failure.getClass().getSimpleName());
                point.addProperty("ok", false); point.add("error", error);
            }
            int addedBytes = bytes(point) + 1;
            if (usedBytes + addedBytes > BYTE_LIMIT) { byteLimited = true; break; }
            results.add(point); usedBytes += addedBytes;
        }
        int processed = results.size(); boolean partial = processed < positions.size();
        result.addProperty("processedCount", processed); result.addProperty("nextOffset", processed);
        result.addProperty("partial", partial); result.addProperty("complete", !partial);
        result.addProperty("byteLimited", byteLimited); result.addProperty("budgetLimited", budgetLimited);
        result.addProperty("coverage", "Results are exactly the requested coordinate prefix, including explicit per-point errors. complete means coverage only; require every ok, source, XYZ, dimension and loaded flag before accepting a block/air claim. Send the remaining original suffix for a fresh read; no hidden cursor or chunk loading.");
        long elapsed = Math.max(0L, clock.getAsLong() - started);
        JsonObject budget = new JsonObject(); budget.addProperty("softBudgetMicros", WORK_BUDGET_NANOS / 1000L);
        budget.addProperty("actualElapsedMicros", elapsed / 1000L); budget.addProperty("softBudgetExceeded", elapsed > WORK_BUDGET_NANOS);
        budget.addProperty("policy", "Compact loaded-block reads and JSON copying on the owning world thread; at least one point progresses, then later points yield at the time or response-byte limit. A single public getter cannot be preempted.");
        result.add("budget", budget); return result;
    }

    private static int bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
}
