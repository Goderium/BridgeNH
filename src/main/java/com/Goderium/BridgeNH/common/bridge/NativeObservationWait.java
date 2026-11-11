package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.machine.BasicMachineFluidAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Bounded read observers. Only ordinary server END ticks read the public owners; HTTP awaits pure signals. */
final class NativeObservationWait {
    static final int MAX_ACTIVE = 2, MAX_OWNERS = 16, MAX_CONDITIONS = 16, MAX_WINDOW_MILLIS = 6500;
    static final long TICK_WORK_NANOS = 4000000L;
    interface Access { void check(); JsonObject read(Owner owner); long worldTick(); }
    static void requireOperation(String operation, JsonObject args) {
        if (args.has("nativeWait") && !"world.inspect_many".equals(operation))
            throw bad("Native observation is only a full world.inspect_many read.");
    }
    static final class Owner {
        final int x, y, z, dimension; final String registry, tileClass, metaClass; final int metadata, metaTileId;
        Owner(JsonObject point, JsonObject expected, int dimension) {
            keys(point, "x", "y", "z"); keys(expected, "registryName", "metadata", "tileClass", "metaTileId", "metaClass");
            x = JsonData.integer(point, "x", -30000000, 30000000); y = JsonData.integer(point, "y", 0, 255);
            z = JsonData.integer(point, "z", -30000000, 30000000); this.dimension = dimension;
            registry = text(expected, "registryName"); metadata = JsonData.integer(expected, "metadata", 0, 65535); tileClass = text(expected, "tileClass");
            boolean gt = expected.has("metaTileId") || expected.has("metaClass");
            if ("gregtech:gt.blockmachines".equals(registry) && !gt) throw bad("Actual GregTech owner identity is required.");
            metaTileId = gt ? JsonData.integer(expected, "metaTileId", 0, 32767) : -1; metaClass = gt ? text(expected, "metaClass") : null;
        }
        void check(JsonObject value) {
            if (value == null || !"integrated_server".equals(text(value, "source")) || integer(value, "x") != x || integer(value, "y") != y
                    || integer(value, "z") != z || integer(value, "dimension") != dimension || !registry.equals(text(value, "registryName"))
                    || integer(value, "metadata") != metadata || !tileClass.equals(text(value, "tileClass"))) throw changed("Actual owner identity changed.");
            JsonObject gt = value.has("gregtech") && value.get("gregtech").isJsonObject() ? value.getAsJsonObject("gregtech") : null;
            if (metaClass == null ? gt != null : gt == null || integer(gt, "metaTileId") != metaTileId || !metaClass.equals(text(gt, "metaClass")))
                throw changed("Actual GregTech owner identity changed.");
        }
    }
    static final class Condition {
        final int owner; final String kind, registry, fluid, tankKind; final int metadata, amount, tankIndex; final long baseline, delta;
        final JsonObject fields;
        private long previous = -1;
        Condition(JsonObject value, int count) {
            keys(value, "owner", "kind", "item", "baseline", "delta", "fields", "fluid", "amount", "tankKind", "tankIndex");
            owner = JsonData.integer(value, "owner", 0, count - 1); kind = text(value, "kind");
            registry = "item_delta".equals(kind) ? text(object(value, "item"), "registryName") : null;
            metadata = registry == null ? -1 : JsonData.integer(object(value, "item"), "metadata", 0, 32767);
            if (registry != null) keys(object(value, "item"), "registryName", "metadata");
            baseline = registry == null ? 0 : quantity(value, "baseline", 0, Long.MAX_VALUE);
            delta = registry == null ? 0 : quantity(value, "delta", 1, Integer.MAX_VALUE);
            fields = "state_equal".equals(kind) ? object(value, "fields") : null;
            if (fields != null) {
                keys(fields, "active", "allowedToWork");
                if (fields.entrySet().isEmpty()) throw bad("State equality needs one or two fixed public boolean fields.");
                for (java.util.Map.Entry<String, JsonElement> field : fields.entrySet()) bool(fields, field.getKey());
            }
            fluid = "fluid_match".equals(kind) ? text(value, "fluid") : null;
            amount = fluid == null ? 0 : JsonData.integer(value, "amount", 1, Integer.MAX_VALUE);
            tankKind = fluid == null ? null : text(value, "tankKind");
            tankIndex = fluid != null && "forge".equals(tankKind) ? JsonData.integer(value, "tankIndex", 0, 31) : -1;
            if (!"item_delta".equals(kind) && !"state_equal".equals(kind) && !"fluid_match".equals(kind)) throw bad("Unknown fixed observation condition.");
            Set<String> allowed = new HashSet<String>(); allowed.add("owner"); allowed.add("kind");
            if (registry != null) { allowed.add("item"); allowed.add("baseline"); allowed.add("delta"); }
            if (fields != null) allowed.add("fields");
            if (fluid != null) {
                if (!"forge".equals(tankKind) && !"named_output".equals(tankKind)) throw bad("Unknown public tank layout.");
                allowed.add("fluid"); allowed.add("amount"); allowed.add("tankKind"); if (tankIndex >= 0) allowed.add("tankIndex");
            }
            for (java.util.Map.Entry<String, JsonElement> entry : value.entrySet()) if (!allowed.contains(entry.getKey())) throw bad("Condition fields do not belong to its fixed kind.");
        }
        boolean matches(JsonObject value, Owner identity) {
            if (registry != null) {
                JsonObject inventory = object(value, "inventory"); JsonArray slots = array(inventory, "slots");
                int total = JsonData.integer(inventory, "totalSlots", 0, 256);
                if (bool(inventory, "truncated") || slots.size() != total) throw changed("A complete inventory is required.");
                long count = 0;
                for (int i = 0; i < total; i++) {
                    JsonObject slot = elementObject(slots.get(i)); if (integer(slot, "index") != i || !slot.has("item")) throw changed("Complete actual slot indices are required.");
                    if (slot.get("item").isJsonNull()) continue;
                    JsonObject item = object(slot, "item"); String name = text(item, "registryName"); int meta = integer(item, "metadata");
                    int size = JsonData.integer(item, "count", 0, Integer.MAX_VALUE); boolean tagged = bool(item, "hasTag");
                    if (registry.equals(name) && metadata == meta && !tagged) count = Math.addExact(count, size);
                }
                long observed = Math.subtractExact(count, baseline);
                if (observed < 0 || observed > delta) throw new BridgeException("native_wait_delta_changed", "A complete selected item count decreased or exceeded its exact finite output delta.");
                progress(observed);
                return observed == delta;
            }
            if (fields != null) {
                JsonObject gt = object(value, "gregtech"); boolean matches = true;
                for (java.util.Map.Entry<String, JsonElement> field : fields.entrySet()) matches &= bool(gt, field.getKey()) == bool(fields, field.getKey());
                return matches;
            }
            JsonObject fluids = object(value, "fluids"), container = "forge".equals(tankKind) ? fluids : object(fluids, "namedTanks");
            JsonArray tanks = array(container, "tanks");
            int total = JsonData.integer(container, "totalTanks", 1, 32);
            if (total != tanks.size() || container.has("truncated") && bool(container, "truncated")) throw changed("Complete tank coverage is required.");
            JsonObject selected = null; Set<Integer> indices = new HashSet<Integer>(); Set<String> roles = new HashSet<String>();
            if ("named_output".equals(tankKind)) {
                if (!bool(container, "available") || !"integrated_server".equals(text(container, "source")) || total != 2) throw changed("Complete named tanks are unavailable.");
                JsonObject namedOwner = object(container, "owner");
                if (integer(namedOwner, "x") != identity.x || integer(namedOwner, "y") != identity.y || integer(namedOwner, "z") != identity.z
                        || integer(namedOwner, "dimension") != identity.dimension || !identity.registry.equals(text(namedOwner, "registryName"))
                        || identity.metaClass == null || integer(namedOwner, "metaTileId") != identity.metaTileId || !identity.metaClass.equals(text(namedOwner, "metaClass"))) throw changed("Named tank owner differs.");
            }
            for (JsonElement raw : tanks) {
                JsonObject tank = elementObject(raw);
                if ("forge".equals(tankKind)) {
                    int index = JsonData.integer(tank, "index", 0, total - 1); if (!indices.add(index)) throw changed("Tank indices are duplicated.");
                    if (index == tankIndex) selected = tank;
                } else {
                    String role = text(tank, "role");
                    if (!roles.add(role) || !("input".equals(role) || "output".equals(role)) || !("input".equals(role) ? "getFluidTank" : "getFluidOutputTank").equals(text(tank, "publicGetter"))) throw changed("Complete public named tank roles differ.");
                    if ("output".equals(role)) selected = tank;
                }
            }
            if (selected == null) throw changed("The declared public tank is absent.");
            int current; String currentFluid = null;
            if ("forge".equals(tankKind)) {
                current = selected.has("amount") ? JsonData.integer(selected, "amount", 1, Integer.MAX_VALUE) : 0;
                if (current > 0) currentFluid = text(selected, "fluid");
            } else {
                boolean empty = bool(selected, "empty"); JsonElement raw = selected.get("fluid");
                if (raw == null || empty != raw.isJsonNull()) throw changed("Named tank empty/stack evidence differs.");
                current = empty ? 0 : JsonData.integer(elementObject(raw), "amount", 1, Integer.MAX_VALUE);
                if (!empty) { currentFluid = text(elementObject(raw), "fluid"); if (bool(elementObject(raw), "hasTag")) throw changed("Tagged fluid is not this finite declared output."); }
            }
            int capacity = JsonData.integer(selected, "capacity", 0, Integer.MAX_VALUE);
            if (current > capacity || current > amount || current > 0 && !fluid.equals(currentFluid)) throw new BridgeException("native_wait_delta_changed", "The actual fluid differs or exceeds its exact finite output.");
            progress(current);
            return current == amount;
        }
        private void progress(long current) {
            if (current < previous) throw new BridgeException("native_wait_delta_changed", "The actual finite output decreased since its previous complete observation.");
            previous = current;
        }
    }
    static final class Request {
        final List<Owner> owners = new ArrayList<Owner>(); final List<Condition> conditions = new ArrayList<Condition>(); final int millis;
        Request(JsonObject args) {
            JsonObject wait = object(args, "nativeWait"); keys(wait, "version", "timeoutMillis", "owners", "conditions");
            if (JsonData.integer(wait, "version", 1, 1) != 1 || GuiDataAdapter.booleanArg(args, "compact", false)
                    || !GuiDataAdapter.booleanArg(args, "includePlayerInventory", false)) throw bad("Native waits require a full combined AFTER inventory read.");
            millis = JsonData.integer(wait, "timeoutMillis", 1, MAX_WINDOW_MILLIS);
            int dimension = integer(args, "dimension"); JsonArray points = array(args, "positions"), identities = array(wait, "owners"), predicates = array(wait, "conditions");
            if (points.size() < 1 || points.size() > MAX_OWNERS || identities.size() != points.size() || predicates.size() < 1 || predicates.size() > MAX_CONDITIONS) throw bad("Native observation owner/condition bounds differ.");
            Set<String> unique = new HashSet<String>();
            for (int i = 0; i < points.size(); i++) {
                Owner owner = new Owner(elementObject(points.get(i)), elementObject(identities.get(i)), dimension);
                if (!unique.add(owner.x + ":" + owner.y + ":" + owner.z)) throw bad("Native owner coordinates must be unique."); owners.add(owner);
            }
            for (JsonElement condition : predicates) conditions.add(new Condition(elementObject(condition), owners.size()));
        }
        boolean matches(JsonObject data) {
            JsonArray rows = array(data, "results"); if (rows.size() != owners.size()) throw changed("Full owner observation rows differ.");
            List<JsonObject> values = new ArrayList<JsonObject>();
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = elementObject(rows.get(i)); if (!bool(row, "ok")) throw changed("An actual owner observation is unavailable.");
                JsonObject value = object(row, "data"); owners.get(i).check(value); values.add(value);
                coverage(value);
            }
            boolean matched = true; for (Condition condition : conditions) matched &= condition.matches(values.get(condition.owner), owners.get(condition.owner)); return matched;
        }
    }
    private final ConcurrentHashMap<Job, Boolean> active = new ConcurrentHashMap<Job, Boolean>();
    private final LongSupplier clock;
    NativeObservationWait() { this(System::nanoTime); }
    NativeObservationWait(LongSupplier clock) { this.clock = clock; }
    synchronized Job register(Request request, Access access, long dispatchDeadline) {
        if (active.size() >= MAX_ACTIVE) throw new BridgeException("busy", "The bounded native observation registry is full.");
        long now = clock.getAsLong(), end = Math.min(now + TimeUnit.MILLISECONDS.toNanos(request.millis), dispatchDeadline - TimeUnit.MILLISECONDS.toNanos(1500));
        if (end <= now) throw new BridgeException("timeout", "No bounded observer window remains before fresh final evidence.");
        Job job = new Job(request, access, now, end); active.put(job, true); return job;
    }
    /** A callback cannot be preempted. Late results are explicitly partial and never accepted. */
    void tick() {
        long deadline = clock.getAsLong() + TICK_WORK_NANOS;
        for (Job job : active.keySet()) {
            if (clock.getAsLong() >= deadline) break;
            if (clock.getAsLong() >= job.deadline) { job.signal("timeout"); continue; }
            Access access = job.access; if (access == null) continue;
            try {
                access.check();
                if (clock.getAsLong() >= deadline || clock.getAsLong() >= job.deadline) { job.signal("partial"); continue; }
                long tick = access.worldTick();
                if (tick < 0 || job.lastTick.get() > tick) throw new BridgeException("native_wait_context_changed", "The bound world's ordinary tick sequence changed.");
                job.lastTick.set(tick); job.firstTick.compareAndSet(-1, tick); job.attempts.incrementAndGet();
                if (clock.getAsLong() >= deadline || clock.getAsLong() >= job.deadline) { job.signal("partial"); continue; }
                JsonArray rows = new JsonArray();
                for (Owner owner : job.request.owners) {
                    JsonObject value = access.read(owner);
                    if (clock.getAsLong() >= deadline || clock.getAsLong() >= job.deadline) { job.signal("partial"); break; }
                    owner.check(value); JsonObject row = new JsonObject(); row.addProperty("ok", true); row.add("data", value); rows.add(row);
                }
                if (job.future.isDone()) continue;
                JsonObject data = new JsonObject(); data.add("results", rows); boolean matched = job.request.matches(data);
                access.check();
                if (clock.getAsLong() >= deadline || clock.getAsLong() >= job.deadline) job.signal("partial");
                else if (matched) job.signal("matched");
            } catch (RuntimeException | LinkageError failure) { job.fail(failure); }
        }
    }
    void invalidateAll(String reason) { for (Job job : active.keySet()) job.fail(new BridgeException("native_wait_context_changed", reason)); }
    int activeCount() { return active.size(); }
    final class Job {
        final Request request; volatile Access access; final long started, deadline;
        final CompletableFuture<String> future = new CompletableFuture<String>(); final AtomicInteger attempts = new AtomicInteger();
        final AtomicLong firstTick = new AtomicLong(-1), lastTick = new AtomicLong(-1); volatile String terminal;
        Job(Request request, Access access, long started, long deadline) { this.request = request; this.access = access; this.started = started; this.deadline = deadline; }
        void signal(String status) { active.remove(this); access = null; if (future.complete(status)) terminal = status; }
        void fail(Throwable failure) { active.remove(this); access = null; future.completeExceptionally(failure); }
        String await(long millis) throws InterruptedException, ExecutionException {
            try { return future.get(Math.max(1, millis), TimeUnit.MILLISECONDS); }
            catch (TimeoutException timeout) { signal("timeout"); return future.get(); }
        }
        void cancel() { fail(new BridgeException("cancelled", "The native read observation was cancelled; no world action was submitted.")); }
        void close() { active.remove(this); access = null; if (!future.isDone()) cancel(); }
        JsonObject receipt(String status, boolean matched) {
            JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("source", "normal_server_end_ticks");
            result.addProperty("readOnly", true); result.addProperty("submitted", false); result.addProperty("nonAtomic", true);
            result.addProperty("quantitiesMonotonic", true);
            result.addProperty("matched", matched && "matched".equals(status)); result.addProperty("partial", "partial".equals(status));
            result.addProperty("timedOut", "timeout".equals(status)); result.addProperty("finalOwnersFresh", true);
            result.addProperty("attempts", attempts.get()); result.addProperty("firstWorldTick", firstTick.get()); result.addProperty("lastWorldTick", lastTick.get());
            result.addProperty("elapsedMillis", Math.max(0, clock.getAsLong() - started) / 1000000D);
            result.addProperty("tickWorkBudgetNanos", TICK_WORK_NANOS); result.addProperty("callbackPreemptible", false); return result;
        }
    }
    private static void keys(JsonObject value, String... allowed) {
        Set<String> keys = new HashSet<String>(); java.util.Collections.addAll(keys, allowed);
        for (java.util.Map.Entry<String, JsonElement> entry : value.entrySet()) if (!keys.contains(entry.getKey())) throw bad("Unknown native observation field: " + entry.getKey());
    }
    private static void coverage(JsonObject value) {
        if (value.has("inventory")) {
            JsonObject inventory = object(value, "inventory"); JsonArray slots = array(inventory, "slots");
            int count = JsonData.integer(inventory, "totalSlots", 0, 256);
            if (bool(inventory, "truncated") || slots.size() != count) throw changed("Every observed owner requires full inventory coverage.");
            for (int i = 0; i < count; i++) {
                JsonObject slot = elementObject(slots.get(i)); if (integer(slot, "index") != i || !slot.has("item")) throw changed("Every actual slot must be present once.");
                if (!slot.get("item").isJsonNull()) { JsonObject item = object(slot, "item"); text(item, "registryName"); integer(item, "metadata"); JsonData.integer(item, "count", 0, Integer.MAX_VALUE); bool(item, "hasTag"); }
            }
        }
        if (value.has("fluids")) {
            JsonObject fluids = object(value, "fluids"); JsonArray tanks = array(fluids, "tanks"); int count = JsonData.integer(fluids, "totalTanks", 0, 32);
            if (bool(fluids, "truncated") || tanks.size() != count) throw changed("Every observed owner requires complete tank coverage.");
            for (int i = 0; i < count; i++) {
                JsonObject tank = elementObject(tanks.get(i)); int capacity = JsonData.integer(tank, "capacity", 0, Integer.MAX_VALUE);
                int amount = tank.has("amount") ? JsonData.integer(tank, "amount", 0, capacity) : 0;
                if (integer(tank, "index") != i || (amount == 0 ? tank.has("fluid") && !tank.get("fluid").isJsonNull() : text(tank, "fluid").isEmpty())) throw changed("Actual public tank content/index coverage differs.");
            }
            if (fluids.has("namedTanks")) {
                JsonObject named = object(fluids, "namedTanks");
                if (bool(named, "available")) namedCoverage(value, named);
            }
        }
    }
    private static void namedCoverage(JsonObject value, JsonObject named) {
        if (integer(named, "schemaVersion") != 1 || !"integrated_server".equals(text(named, "source"))
                || !"complete_actual_owner_named_tanks_public_api_read".equals(text(named, "scope"))
                || !BasicMachineFluidAdapter.BINDING.equals(text(named, "binding"))
                || !"gregtech.api.metatileentity.implementations.MTEBasicMachine.getFluidTank/getFluidOutputTank".equals(text(named, "publicApi"))
                || integer(named, "totalTanks") != 2 || bool(named, "truncated")) throw changed("Complete public named tank coverage differs.");
        JsonObject expected = object(value, "gregtech"), owner = object(named, "owner");
        keys(owner, "x", "y", "z", "dimension", "registryName", "metaTileId", "metaClass");
        for (String field : new String[] {"x", "y", "z", "dimension"}) if (integer(owner, field) != integer(value, field)) throw changed("Named tank world owner differs.");
        if (!text(owner, "registryName").equals(text(value, "registryName")) || integer(owner, "metaTileId") != integer(expected, "metaTileId")
                || !text(owner, "metaClass").equals(text(expected, "metaClass"))) throw changed("Named tank actual GT owner differs.");
        JsonArray tanks = array(named, "tanks"); if (tanks.size() != 2) throw changed("Both complete actual named tanks are required.");
        Set<String> roles = new HashSet<String>();
        for (JsonElement element : tanks) {
            JsonObject tank = elementObject(element); String role = text(tank, "role");
            if (!roles.add(role) || !("input".equals(role) || "output".equals(role))
                    || !("input".equals(role) ? "getFluidTank" : "getFluidOutputTank").equals(text(tank, "publicGetter"))) throw changed("Named tank role/getter coverage differs.");
            int capacity = JsonData.integer(tank, "capacity", 0, Integer.MAX_VALUE); boolean empty = bool(tank, "empty"); JsonElement stack = tank.get("fluid");
            if (stack == null || empty != stack.isJsonNull()) throw changed("Named tank empty/content coverage differs.");
            if (!empty) { JsonObject fluid = elementObject(stack); text(fluid, "fluid"); JsonData.integer(fluid, "amount", 1, capacity); if (bool(fluid, "hasTag")) throw changed("Named tank fluid must be exactly untagged."); }
        }
    }
    private static JsonObject object(JsonObject value, String key) { return elementObject(value.get(key)); }
    private static JsonObject elementObject(JsonElement value) { if (value == null || !value.isJsonObject()) throw bad("A typed native observation object is required."); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject value, String key) { JsonElement child = value.get(key); if (child == null || !child.isJsonArray()) throw bad("A typed native observation array is required."); return child.getAsJsonArray(); }
    private static String text(JsonObject value, String key) { String result = JsonData.string(value, key); if (result.isEmpty() || result.length() > 256) throw bad("Bounded nonempty strings are required."); return result; }
    private static int integer(JsonObject value, String key) { return JsonData.integer(value, key, Integer.MIN_VALUE, Integer.MAX_VALUE); }
    private static boolean bool(JsonObject value, String key) { JsonElement raw = value.get(key); if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) throw bad("A typed boolean is required."); return raw.getAsBoolean(); }
    private static long quantity(JsonObject value, String key, long minimum, long maximum) {
        try { JsonElement raw = value.get(key); if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            long result = new BigDecimal(raw.getAsString()).longValueExact(); if (result < minimum || result > maximum) throw new IllegalArgumentException(); return result;
        } catch (RuntimeException failure) { throw bad("A bounded exact integer quantity is required."); }
    }
    private static BridgeException bad(String message) { return new BridgeException("invalid_args", message); }
    private static BridgeException changed(String message) { return new BridgeException("native_wait_identity_changed", message); }
}
