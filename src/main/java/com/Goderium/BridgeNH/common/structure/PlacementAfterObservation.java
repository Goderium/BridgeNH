package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** Optional read-only proof appended after the original native placement/accounting confirmation. */
public final class PlacementAfterObservation {
    public static final int VERSION = 1, BYTE_LIMIT = 48 * 1024;
    static final long WORK_NANOS = 4000000L;
    public interface Access {
        boolean loaded();
        long tick();
        JsonObject data(Coverage coverage);
        boolean unchanged();
    }

    public static JsonObject read(String session, String player, String world, int dimension, int x, int y, int z,
            JsonObject target, Access access, long operationDeadline, LongSupplier clock) {
        long started = clock.getAsLong(); Coverage coverage = new Coverage(clock, Math.min(operationDeadline, started + WORK_NANOS));
        JsonObject result = new JsonObject(), point = new JsonObject();
        result.addProperty("version", VERSION); result.addProperty("source", "integrated_server");
        result.addProperty("sessionId", session); result.addProperty("playerId", player); result.addProperty("worldName", world);
        result.addProperty("dimension", dimension); result.addProperty("phase", "native_place_server_after");
        point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z); result.add("target", point);
        result.add("loaded", JsonNull.INSTANCE); result.addProperty("complete", false); result.addProperty("partial", true);
        boolean complete = false;
        try {
            long tick = access.tick(); result.addProperty("worldTick", tick);
            boolean loaded = access.loaded(); result.addProperty("loaded", loaded);
            if (!loaded) throw new BridgeException("chunk_unloaded", "The AFTER target is not loaded; no empty target is inferred.");
            if (!coverage.proceed()) throw new BridgeException("observation_budget", "The optional AFTER observation has no remaining read budget.");
            JsonObject data = access.data(coverage);
            if (data == null) throw new BridgeException("inspection_failed", "The AFTER read returned no target data.");
            data.addProperty("loaded", true); data.addProperty("worldTick", tick);
            int bytes = data.toString().getBytes(StandardCharsets.UTF_8).length;
            coverage.json.addProperty("dataBytes", bytes);
            if (bytes > BYTE_LIMIT) throw new BridgeException("result_too_large", "The optional AFTER target exceeds 48 KiB; use the original readback.");
            result.add("data", data);
            boolean identity = matches(data, target, dimension, x, y, z);
            coverage.json.addProperty("identity", identity ? "observed" : "unavailable");
            boolean owner = access.loaded() && access.unchanged() && access.tick() == tick;
            coverage.json.addProperty("ownerUnchanged", owner);
            boolean budget = coverage.proceed();
            complete = tick >= 0 && session != null && !session.isEmpty() && player != null && !player.isEmpty()
                    && world != null && !world.isEmpty() && identity && owner && coverage.complete(data) && budget;
            if (!complete) error(result, "observation_partial", "The optional AFTER observation lacks complete identity, owner, slot/tank or budget coverage.");
        } catch (RuntimeException | LinkageError failure) {
            error(result, failure instanceof BridgeException ? ((BridgeException) failure).code : "inspection_failed",
                    failure instanceof BridgeException ? JsonData.bounded(failure.getMessage(), 512) : "Optional AFTER read failed: " + failure.getClass().getSimpleName());
        }
        coverage.json.addProperty("byteLimit", BYTE_LIMIT); coverage.json.addProperty("inventorySlotLimit", 256);
        coverage.json.addProperty("fluidTankLimit", 32); coverage.json.addProperty("softBudgetMicros", WORK_NANOS / 1000L);
        long finished = clock.getAsLong(); coverage.json.addProperty("elapsedMicros", Math.max(0L, finished - started) / 1000L);
        if (finished >= coverage.deadline) {
            coverage.budgetLimited = true; complete = false;
            error(result, "observation_budget", "The optional AFTER read exceeded its soft or operation deadline; the original placement receipt is retained.");
        }
        result.addProperty("complete", complete); result.addProperty("partial", !complete);
        coverage.json.addProperty("budgetLimited", coverage.budgetLimited);
        coverage.json.addProperty("scope", "Loaded native target identity and every exposed public inventory slot/tank. Optional GT runtime fields retain world.inspect semantics; no structure or recipe completion is inferred.");
        coverage.json.addProperty("policy", "Read-only on the same real server AFTER owner; public getters cannot be preempted. Partial evidence preserves the original placement/accounting receipt and requires ordinary readback, never placement replay.");
        result.add("coverage", coverage.json); return result;
    }

    private static void error(JsonObject result, String code, String message) {
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message); result.add("error", error);
    }
    private static boolean matches(JsonObject data, JsonObject target, int dimension, int x, int y, int z) {
        try {
            String registry = JsonData.string(data, "registryName");
            if (registry.isEmpty() || "null".equals(registry) || !"integrated_server".equals(JsonData.string(data, "source")) || JsonData.integer(data, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension
                    || JsonData.integer(data, "x", -30000000, 30000000) != x || JsonData.integer(data, "y", 0, 255) != y
                    || JsonData.integer(data, "z", -30000000, 30000000) != z || target == null
                    || !registry.equals(JsonData.string(target, "registryName"))
                    || JsonData.integer(data, "metadata", Integer.MIN_VALUE, Integer.MAX_VALUE) != JsonData.integer(target, "metadata", Integer.MIN_VALUE, Integer.MAX_VALUE)) return false;
            if (target.has("gregtech")) {
                JsonObject expected = target.getAsJsonObject("gregtech"), actual = data.getAsJsonObject("gregtech");
                return expected != null && actual != null && !expected.has("adapterError") && !actual.has("adapterError")
                        && JsonData.integer(expected, "metaTileId", 0, 32767) == JsonData.integer(actual, "metaTileId", 0, 32767)
                        && JsonData.string(expected, "metaClass").equals(JsonData.string(actual, "metaClass"));
            }
            return !data.has("gregtech");
        } catch (RuntimeException failure) { return false; }
    }

    public static final class Coverage {
        final JsonObject json = new JsonObject();
        final LongSupplier clock; final long deadline;
        boolean budgetLimited;
        Coverage(LongSupplier clock, long deadline) {
            this.clock = clock; this.deadline = deadline;
            for (String key : new String[] {"tile", "inventory", "fluids", "gregtech"}) json.addProperty(key, "unknown");
        }
        public boolean proceed() { if (clock.getAsLong() >= deadline) budgetLimited = true; return !budgetLimited; }
        public void interfaces(boolean tile, boolean inventory, boolean fluids, boolean gregtech) {
            json.addProperty("tile", tile ? "present" : "absent");
            json.addProperty("inventory", inventory ? "unavailable" : "not_exposed");
            json.addProperty("fluids", fluids ? "unavailable" : "not_exposed");
            json.addProperty("gregtech", gregtech ? "unavailable" : "not_exposed");
        }
        public void gregtech(JsonObject value) {
            boolean valid = false;
            try { valid = value != null && !value.has("adapterError") && JsonData.integer(value, "metaTileId", 0, 32767) >= 0
                    && !JsonData.string(value, "metaClass").isEmpty(); }
            catch (RuntimeException failure) { valid = false; }
            json.addProperty("gregtech", valid ? "identity_observed" : "unavailable");
        }
        public void inventory(JsonObject value) {
            boolean valid = false;
            try {
                int total = JsonData.integer(value, "totalSlots", 0, 256); JsonArray rows = value.getAsJsonArray("slots");
                valid = booleanValue(value, "truncated", false) && rows != null && rows.size() == total;
                json.addProperty("observedSlots", rows == null ? 0 : rows.size()); json.addProperty("totalSlots", total);
                for (int i = 0; valid && i < total; i++) {
                    JsonObject row = rows.get(i).getAsJsonObject(); valid = JsonData.integer(row, "index", 0, 255) == i && row.has("item");
                    JsonElement item = row.get("item");
                    if (valid && !item.isJsonNull()) {
                        JsonObject stack = item.getAsJsonObject(); String registry = JsonData.string(stack, "registryName");
                        valid = !registry.isEmpty() && !"null".equals(registry) && JsonData.integer(stack, "count", 1, Integer.MAX_VALUE) > 0
                                && (booleanValue(stack, "hasTag", false) || booleanValue(stack, "hasTag", true));
                        if (valid) JsonData.integer(stack, "metadata", Integer.MIN_VALUE, Integer.MAX_VALUE);
                    }
                }
            } catch (RuntimeException failure) { valid = false; }
            json.addProperty("inventory", valid ? "observed" : "unavailable");
        }
        public void fluids(JsonObject value, boolean rawKnown) {
            boolean valid = false;
            try {
                int total = JsonData.integer(value, "totalTanks", 0, 32); JsonArray tanks = value.getAsJsonArray("tanks");
                valid = rawKnown && booleanValue(value, "truncated", false) && tanks != null && tanks.size() == total;
                json.addProperty("observedTanks", tanks == null ? 0 : tanks.size()); json.addProperty("totalTanks", total);
                for (int i = 0; valid && i < total; i++) {
                    JsonObject tank = tanks.get(i).getAsJsonObject(); valid = JsonData.integer(tank, "index", 0, 31) == i
                            && JsonData.integer(tank, "capacity", 0, Integer.MAX_VALUE) >= 0;
                    if (tank.has("fluid") || tank.has("amount")) valid &= !JsonData.string(tank, "fluid").isEmpty()
                            && JsonData.integer(tank, "amount", 1, Integer.MAX_VALUE) <= JsonData.integer(tank, "capacity", 0, Integer.MAX_VALUE);
                }
            } catch (RuntimeException failure) { valid = false; }
            json.addProperty("fluids", valid ? "observed" : "unavailable");
        }
        boolean complete(JsonObject data) {
            try {
                if (!known("tile", "present", "absent") || !known("inventory", "observed", "not_exposed") || !known("fluids", "observed", "not_exposed")
                        || !known("gregtech", "identity_observed", "not_exposed")) return false;
                if ("present".equals(json.get("tile").getAsString())) { if (JsonData.string(data, "tileClass").isEmpty()) return false; }
                else if (data.has("tileClass")) return false;
                if ("observed".equals(json.get("inventory").getAsString())) inventory(data.getAsJsonObject("inventory"));
                else if (data.has("inventory")) return false;
                if ("observed".equals(json.get("fluids").getAsString())) fluids(data.getAsJsonObject("fluids"), true);
                else if (data.has("fluids")) return false;
                if ("identity_observed".equals(json.get("gregtech").getAsString())) gregtech(data.getAsJsonObject("gregtech"));
                else if (data.has("gregtech")) return false;
                return known("inventory", "observed", "not_exposed") && known("fluids", "observed", "not_exposed") && known("gregtech", "identity_observed", "not_exposed");
            } catch (RuntimeException failure) { return false; }
        }
        private boolean known(String key, String a, String b) { String value = json.get(key).getAsString(); return a.equals(value) || b.equals(value); }
        private boolean booleanValue(JsonObject value, String key, boolean expected) {
            JsonElement element = value.get(key); return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean() == expected;
        }
    }
}
