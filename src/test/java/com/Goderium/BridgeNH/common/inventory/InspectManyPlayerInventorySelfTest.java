package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;

/** Full-slot freshness, typed coverage, AFTER guard and real HTTP envelope; game operations are mocked. */
public final class InspectManyPlayerInventorySelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0; policy(); publicFullRead(); afterAndBinding(); pendingZero(); budgets(); http(); return checks;
    }

    private static void policy() {
        check(InspectManyPlayerInventory.request("world.inspect_many", new JsonObject(), false, false) == null, "Legacy reads retain their original request path");
        JsonObject requested = args();
        check(InspectManyPlayerInventory.request("world.inspect_many", requested, true, true) != null, "Only an explicit protected full read enables the observation");
        JsonObject baubles = new JsonObject(); baubles.addProperty("includePlayerInventory", true);
        for (boolean guarded : Arrays.asList(false, true)) {
            for (boolean developer : Arrays.asList(false, true)) {
                check(InspectManyPlayerInventory.request("baubles.inspect", baubles, guarded, developer) == null,
                        "Baubles owns its separately bounded inventory option; world inspect-many augmentation stays inactive");
            }
        }
        JsonObject baublesFalse = copy(baubles); baublesFalse.addProperty("includePlayerInventory", false);
        check(InspectManyPlayerInventory.request("baubles.inspect", baublesFalse, false, false) == null,
                "Baubles default/false full-inventory option does not activate the world augmentation");
        for (String key : Arrays.asList("playerInventoryByteBudget", "observationByteBudget")) {
            JsonObject wrongBudget = copy(baubles); wrongBudget.addProperty(key, 8192);
            expect("invalid_args", () -> InspectManyPlayerInventory.request("baubles.inspect", wrongBudget, true, true));
        }
        JsonObject wrongBaublesFlag = copy(baubles); wrongBaublesFlag.addProperty("includePlayerInventory", 1);
        expect("invalid_args", () -> InspectManyPlayerInventory.request("baubles.inspect", wrongBaublesFlag, true, true));
        for (String operation : Arrays.asList("world.inspect", "inventory.inspect", "player.interact"))
            expect("invalid_args", () -> InspectManyPlayerInventory.request(operation, requested, true, true));
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", requested, false, true));
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", requested, true, false));
        JsonObject compact = copy(requested); compact.addProperty("compact", true);
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", compact, true, true));
        for (String key : Arrays.asList("playerInventoryByteBudget", "observationByteBudget")) {
            JsonObject absent = copy(requested); absent.remove(key);
            expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", absent, true, true));
            JsonObject typed = copy(requested); typed.addProperty(key, "8192");
            expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", typed, true, true));
        }
        JsonObject excess = copy(requested); excess.addProperty("observationByteBudget", InspectManyPlayerInventory.OBSERVATION_BYTE_LIMIT + 1);
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", excess, true, true));
        JsonObject orphan = copy(requested); orphan.addProperty("includePlayerInventory", false);
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", orphan, true, true));
        JsonObject wrongFlag = copy(requested); wrongFlag.addProperty("includePlayerInventory", 1);
        expect("invalid_args", () -> InspectManyPlayerInventory.request("world.inspect_many", wrongFlag, true, true));
        for (String removed : Arrays.asList("expectedWorld", "requireBackground")) {
            JsonObject context = context();
            if ("expectedWorld".equals(removed)) context.remove(removed); else context.addProperty(removed, false);
            expect("invalid_request", () -> GuardedContext.parse("world.inspect_many", context).requirePlayerInventoryRead());
        }
        JsonObject contract = InspectManyPlayerInventory.capabilities();
        check(contract.get("version").getAsInt() == 1 && contract.get("guardedOnly").getAsBoolean()
                && contract.get("developerOnly").getAsBoolean() && !contract.get("compact").getAsBoolean()
                && "after".equals(contract.get("phase").getAsString()), "Capability states the narrow owning-thread/full-read contract");
    }

    private static void pendingZero() {
        Fixture f = new Fixture(); f.count = 0;
        JsonObject args = args(); args.addProperty("allowPendingZero", true);
        InspectManyPlayerInventory.Request request = InspectManyPlayerInventory.request("world.inspect_many", args, true, true);
        check(request.allowPendingZero && InspectManyPlayerInventory.capabilities().get("pendingZeroVersion").getAsInt() == 1,
                "Only the explicit versioned pure read can retain typed pending-zero evidence");
        java.util.function.Supplier<JsonObject> current = () -> { JsonObject value = f.snapshot(); value.addProperty("paused", false); return value; };
        GuardedContext.Result pending = InspectManyPlayerInventory.after(request, f.owner(), () -> f.inventory(), current, guard(), () -> 0);
        JsonObject observation = pending.data.getAsJsonObject("playerInventoryObservation");
        check(observation.get("pendingZero").getAsBoolean() && !observation.get("inventoryComplete").getAsBoolean()
                && observation.get("pendingZeroVersion").getAsInt() == 1,
                "Pending is explicitly typed and cannot certify an accepted inventory");
        check(pending.data.getAsJsonObject("playerInventory").getAsJsonArray("slots").get(3).getAsJsonObject()
                .getAsJsonObject("item").get("count").getAsInt() == 0
                && pending.data.getAsJsonObject("playerInventory").getAsJsonObject("cursorItem").get("count").getAsInt() == 0,
                "Both actual nonnull zero stacks remain intact, never normalized to empty");
        expect("inventory_observation_unavailable", () -> run(f, f.inventory()));
        f.count = -1;
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.after(request, f.owner(), () -> f.inventory(), current, guard(), () -> 0));
        f.count = 0;
        for (String fault : Arrays.asList("later_bad_slot", "bool_count", "missing_count", "wrong_actor", "wrong_session")) {
            JsonObject malformed = f.inventory();
            if (fault.equals("later_bad_slot")) malformed.getAsJsonArray("slots").get(40).getAsJsonObject().remove("item");
            else if (fault.equals("bool_count")) malformed.getAsJsonArray("slots").get(3).getAsJsonObject().getAsJsonObject("item").addProperty("count", false);
            else if (fault.equals("missing_count")) malformed.getAsJsonArray("slots").get(3).getAsJsonObject().getAsJsonObject("item").remove("count");
            else if (fault.equals("wrong_actor")) malformed.addProperty("playerId", "another");
            else malformed.addProperty("sessionId", "another");
            expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.after(request, f.owner(), () -> malformed, current, guard(), () -> 0));
        }
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.after(request, f.owner(), () -> f.inventory(),
                () -> { JsonObject value = f.snapshot(); value.addProperty("paused", true); return value; }, guard(), () -> 0));
        AtomicInteger clock = new AtomicInteger();
        expect("inventory_observation_budget", () -> InspectManyPlayerInventory.after(request, f.owner(), () -> f.inventory(), current,
                guard(), () -> clock.getAndIncrement() == 0 ? 0 : InspectManyPlayerInventory.WORK_BUDGET_NANOS + 1));
        f.count = 10;
        GuardedContext.Result finalRead = InspectManyPlayerInventory.after(request, f.owner(), () -> f.inventory(), current, guard(), () -> 0);
        check(!finalRead.data.getAsJsonObject("playerInventoryObservation").get("pendingZero").getAsBoolean()
                && finalRead.data.getAsJsonObject("playerInventoryObservation").get("inventoryComplete").getAsBoolean(),
                "A later positive full read independently certifies all fresh evidence");
    }

    private static void publicFullRead() {
        CountingInventory actual = new CountingInventory(41);
        JsonObject first = InspectManyPlayerInventory.read(actual, 8, null);
        check(first.get("totalSlots").getAsInt() == 41 && first.getAsJsonArray("slots").size() == 41
                && actual.reads == 41 && first.get("complete").getAsBoolean() && !first.get("truncated").getAsBoolean(),
                "The public inventory serializer reads all 41 actual slots rather than a guessed vanilla array length");
        for (int i = 0; i < 41; i++) {
            JsonObject row = first.getAsJsonArray("slots").get(i).getAsJsonObject();
            check(row.get("index").getAsInt() == i && row.get("item").isJsonNull(), "Every unique actual index and empty row remains explicit");
        }
        check(first.get("selectedHotbarSlot").getAsInt() == 8 && first.get("cursorItem").isJsonNull()
                && "public fixture".equals(first.get("name").getAsString()), "Existing full inventory.inspect DTO fields are retained");
        actual.total = 64;
        JsonObject second = InspectManyPlayerInventory.read(actual, 2, null);
        check(second.get("totalSlots").getAsInt() == 64 && second.getAsJsonArray("slots").size() == 64
                && actual.reads == 105, "A later read freshly enumerates its actual size; no 40/41-slot fixed contract or inventory cache");
        actual.total = 257;
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.read(actual, 0, null));
        actual.total = 8;
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.read(actual, 0, null));
        CountingInventory changing = new CountingInventory(41); changing.changeDuringRead = true;
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.read(changing, 0, null));
        expect("inventory_observation_unavailable", () -> InspectManyPlayerInventory.read(null, 0, null));
        check(actual.predicates == 0 && actual.writes == 0, "Full observation never asks acceptance predicates or mutates an inventory");
    }

    private static void afterAndBinding() {
        Fixture f = new Fixture(); JsonObject originalOwner = f.owner();
        List<String> order = new ArrayList<String>();
        GuardedContext.Result result = InspectManyPlayerInventory.after(request(), originalOwner,
                () -> { order.add("inventory"); return f.inventory(); }, () -> { order.add("snapshot"); return f.snapshot(); }, guard(), () -> 0);
        check(order.equals(Arrays.asList("inventory", "snapshot")) && "after".equals(result.context.get("phase").getAsString()),
                "The new inventory read precedes the same fresh AFTER snapshot, after the server owner read");
        JsonObject owner = result.data.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonObject("data");
        check(owner.equals(originalOwner.getAsJsonArray("results").get(0).getAsJsonObject().get("data"))
                && owner.has("gregtech") && owner.has("inventory") && owner.has("fluids") && !originalOwner.has("playerInventory"),
                "Full owner coordinates, registry, GT slots/tanks/truncation data survive unchanged without mutating the original result");
        check(result.context.getAsJsonObject("snapshot").getAsJsonObject("player").get("feetY").getAsInt() == 65
                && result.context.getAsJsonObject("snapshot").getAsJsonObject("player").get("flying").getAsBoolean(),
                "Full current player pose and flight evidence remain in the existing snapshot");
        AtomicInteger reads = new AtomicInteger();
        expect("PROFILE_CHANGED", () -> InspectManyPlayerInventory.after(request(), f.owner(), () -> { reads.incrementAndGet(); return f.inventory(); },
                () -> { JsonObject wrong = f.snapshot(); wrong.addProperty("profile", "player"); return wrong; }, guard(), () -> 0));
        check(reads.get() == 1, "Guard failure discards a read receipt without substituting or repeating a native action");
        JsonObject dimension = f.inventory(); dimension.addProperty("dimension", 179);
        expect("DIMENSION_CHANGED", () -> run(f, dimension));
        for (String key : Arrays.asList("sessionId", "clientTicks", "selectedHotbarSlot", "playerId")) {
            JsonObject changed = f.inventory();
            if (key.equals("sessionId") || key.equals("playerId")) changed.addProperty(key, "another"); else changed.addProperty(key, 7);
            expect("inventory_observation_unavailable", () -> run(f, changed));
        }
        for (String key : Arrays.asList("slots", "cursorItem", "playerId", "playerInventoryBindingVerified")) {
            JsonObject absent = f.inventory(); absent.remove(key); expect("inventory_observation_unavailable", () -> run(f, absent));
        }
        JsonObject duplicate = f.inventory(); duplicate.getAsJsonArray("slots").get(40).getAsJsonObject().addProperty("index", 0);
        expect("inventory_observation_unavailable", () -> run(f, duplicate));
        JsonObject truncated = f.inventory(); truncated.addProperty("truncated", true);
        expect("inventory_observation_unavailable", () -> run(f, truncated));
        JsonObject rows = f.inventory(); rows.addProperty("totalSlots", 42);
        expect("inventory_observation_unavailable", () -> run(f, rows));
        JsonObject badItem = f.inventory(); badItem.getAsJsonArray("slots").get(1).getAsJsonObject().remove("item");
        expect("inventory_observation_unavailable", () -> run(f, badItem));
        JsonObject boolIndex = f.inventory(); boolIndex.getAsJsonArray("slots").get(2).getAsJsonObject().addProperty("index", true);
        expect("inventory_observation_unavailable", () -> run(f, boolIndex));
        JsonObject first = run(f, f.inventory()).data.getAsJsonObject("playerInventory");
        f.count = 11;
        JsonObject second = run(f, f.inventory()).data.getAsJsonObject("playerInventory");
        check(first.getAsJsonArray("slots").get(3).getAsJsonObject().getAsJsonObject("item").get("count").getAsInt() == 10
                && second.getAsJsonArray("slots").get(3).getAsJsonObject().getAsJsonObject("item").get("count").getAsInt() == 11,
                "Repeated observations return fresh slot contents rather than cached material evidence");
    }

    private static void budgets() {
        Fixture f = new Fixture();
        expect("result_too_large", () -> InspectManyPlayerInventory.after(new InspectManyPlayerInventory.Request(1024, 1048576), f.owner(), f::inventory, f::snapshot, guard(), () -> 0));
        JsonObject largeOwner = f.owner(); largeOwner.addProperty("padding", repeat("x", 9000));
        expect("result_too_large", () -> InspectManyPlayerInventory.after(new InspectManyPlayerInventory.Request(131072, 8192), largeOwner, f::inventory, f::snapshot, guard(), () -> 0));
        JsonObject hugeInventory = f.inventory(); hugeInventory.addProperty("padding", repeat("中文", 30000));
        expect("result_too_large", () -> InspectManyPlayerInventory.after(new InspectManyPlayerInventory.Request(131072, 1048576), f.owner(), () -> hugeInventory, f::snapshot, guard(), () -> 0));
        GuardedContext.Result accepted = run(f, f.inventory()); JsonObject pair = new JsonObject(); pair.add("data", accepted.data); pair.add("context", accepted.context);
        JsonObject metadata = accepted.data.getAsJsonObject("playerInventoryObservation");
        check(metadata.get("actualObservationBytes").getAsInt() == pair.toString().getBytes(StandardCharsets.UTF_8).length
                && metadata.get("inventoryBytes").getAsInt() == accepted.data.getAsJsonObject("playerInventory").toString().getBytes(StandardCharsets.UTF_8).length,
                "Exact UTF-8 accounting includes inventory, all full owner points, AFTER context and the counter's own digits");
        AtomicInteger times = new AtomicInteger();
        expect("inventory_observation_budget", () -> InspectManyPlayerInventory.after(request(), f.owner(), f::inventory, f::snapshot, guard(),
                () -> times.getAndIncrement() == 0 ? 0 : InspectManyPlayerInventory.WORK_BUDGET_NANOS + 1));
    }

    private static void http() throws Exception {
        HttpFixture bridge = new HttpFixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "inventory-observation-self-test"); server.start(0);
        try {
            JsonObject response = post(server.port());
            check(response.get("ok").getAsBoolean() && "after".equals(response.getAsJsonObject("context").get("phase").getAsString())
                    && response.getAsJsonObject("data").getAsJsonObject("playerInventory").get("totalSlots").getAsInt() == 41,
                    "Real authenticated HTTP retains separate full native data and guarded AFTER context envelopes");
            check(bridge.inventoryThread.equals(bridge.snapshotThread) && "inventory-client-owner".equals(bridge.inventoryThread)
                    && bridge.reads.get() == 1, "Inventory and snapshot are read once on one mocked owning client queue");
        } finally { server.stop(); bridge.close(); }
    }

    private static GuardedContext.Result run(Fixture f, JsonObject inventory) {
        return InspectManyPlayerInventory.after(request(), f.owner(), () -> inventory, f::snapshot, guard(), () -> 0);
    }
    private static InspectManyPlayerInventory.Request request() { return InspectManyPlayerInventory.request("world.inspect_many", args(), true, true); }
    private static GuardedContext guard() { return GuardedContext.parse("world.inspect_many", context()); }
    private static JsonObject args() {
        JsonObject value = new JsonObject(); value.addProperty("includePlayerInventory", true); value.addProperty("compact", false);
        value.addProperty("playerInventoryByteBudget", 131072); value.addProperty("observationByteBudget", 1048576);
        JsonArray positions = new JsonArray(); JsonObject point = new JsonObject(); point.addProperty("x", 1); point.addProperty("y", 64); point.addProperty("z", 2); positions.add(point); value.add("positions", positions); return value;
    }
    private static JsonObject context() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("expectedSessionId", "session");
        value.addProperty("expectedProfile", "developer"); value.addProperty("expectedWorld", "world"); value.addProperty("requireBackground", true); return value;
    }
    private static JsonObject copy(JsonObject value) { return new JsonParser().parse(value.toString()).getAsJsonObject(); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), "Expected " + code + ", received " + failure.code); }
    }
    private static JsonObject post(int port) throws Exception {
        JsonObject value = new JsonObject(); value.addProperty("requestId", UUID.randomUUID().toString()); value.addProperty("operation", "world.inspect_many"); value.add("args", args()); value.add("includeContext", context());
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/call").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Authorization", "Bearer inventory-observation-self-test"); connection.setRequestProperty("Content-Type", "application/json"); connection.setConnectTimeout(2000); connection.setReadTimeout(4000);
        connection.getOutputStream().write(value.toString().getBytes(StandardCharsets.UTF_8)); connection.getOutputStream().close();
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[2048]; int count; while ((count = input.read(chunk)) != -1) output.write(chunk, 0, count);
            return new JsonParser().parse(new String(output.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        } finally { connection.disconnect(); }
    }
    private static final class CountingInventory extends InventoryBasic {
        int total, reads, predicates, writes; boolean changeDuringRead;
        CountingInventory(int total) { super("public fixture", true, 1); this.total = total; }
        @Override public int getSizeInventory() { return changeDuringRead && reads > 0 ? total + 1 : total; }
        @Override public ItemStack getStackInSlot(int slot) { reads++; return null; }
        @Override public boolean isItemValidForSlot(int slot, ItemStack stack) { predicates++; throw new AssertionError("acceptance predicate"); }
        @Override public void setInventorySlotContents(int slot, ItemStack stack) { writes++; throw new AssertionError("inventory mutation"); }
    }
    private static class Fixture {
        int count = 10;
        JsonObject inventory() {
            JsonObject value = InspectManyPlayerInventory.read(new CountingInventory(41), 2, null);
            JsonObject item = new JsonObject(); item.addProperty("registryName", "fixture:material"); item.addProperty("displayName", "Material"); item.addProperty("count", count); item.addProperty("metadata", 4); item.addProperty("hasTag", false);
            value.getAsJsonArray("slots").get(3).getAsJsonObject().add("item", item); value.add("cursorItem", copy(item));
            value.addProperty("source", "client"); value.addProperty("inventorySource", "current_player_inventory"); value.addProperty("playerInventoryBindingVerified", true);
            value.addProperty("playerId", "player"); value.addProperty("sessionId", "session"); value.addProperty("clientTicks", 90); value.addProperty("dimension", 180); value.addProperty("worldTick", 70); return value;
        }
        JsonObject snapshot() {
            JsonObject value = new JsonObject(); value.addProperty("sessionId", "session"); value.addProperty("profile", "developer"); value.addProperty("worldLoaded", true); value.addProperty("clientTicks", 90);
            JsonObject world = new JsonObject(); world.addProperty("worldName", "world"); world.addProperty("dimension", 180); value.add("world", world);
            JsonObject background = new JsonObject(); background.addProperty("enabled", true); background.addProperty("helperInstalled", true); background.addProperty("pauseOnLostFocus", false); value.add("background", background);
            JsonObject player = new JsonObject(); player.addProperty("playerId", "player"); player.addProperty("selectedHotbarSlot", 2); player.addProperty("x", 1.5); player.addProperty("y", 66.6); player.addProperty("feetY", 65); player.addProperty("eyeY", 66.6); player.addProperty("z", 2.5); player.addProperty("yaw", 15); player.addProperty("pitch", 60); player.addProperty("flying", true); player.addProperty("allowFlying", true); value.add("player", player); return value;
        }
        JsonObject owner() {
            JsonObject value = new JsonObject(); value.addProperty("source", "integrated_server"); value.addProperty("dimension", 180); JsonArray points = new JsonArray();
            JsonObject point = new JsonObject(), data = new JsonObject(); point.addProperty("ok", true); data.addProperty("source", "integrated_server"); data.addProperty("dimension", 180); data.addProperty("x", 1); data.addProperty("y", 64); data.addProperty("z", 2); data.addProperty("registryName", "fixture:machine"); data.addProperty("metadata", 6);
            JsonObject gt = new JsonObject(); gt.addProperty("metaTileId", 99); data.add("gregtech", gt);
            JsonObject slots = new JsonObject(); slots.addProperty("truncated", false); slots.add("slots", new JsonArray()); data.add("inventory", slots);
            JsonObject fluids = new JsonObject(); fluids.addProperty("truncated", false); fluids.add("tanks", new JsonArray()); data.add("fluids", fluids);
            point.add("data", data); points.add(point); value.add("results", points); return value;
        }
    }
    private static final class HttpFixture extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
        final Fixture data = new Fixture(); final GameQueue queue = new GameQueue(8); final AtomicInteger reads = new AtomicInteger();
        volatile boolean running = true; volatile String inventoryThread, snapshotThread; final Thread owner;
        HttpFixture() {
            owner = new Thread(() -> { while (running) { queue.drain(8); try { Thread.sleep(1); } catch (InterruptedException ignored) { return; } } }, "inventory-client-owner"); owner.setDaemon(true); owner.start();
        }
        @Override public GuardedContext.Result dispatchGuarded(String operation, JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
            InspectManyPlayerInventory.Request request = InspectManyPlayerInventory.request(operation, args, true, true);
            JsonObject owner = data.owner();
            return queue.await(queue.submit(() -> InspectManyPlayerInventory.after(request, owner,
                    () -> { inventoryThread = Thread.currentThread().getName(); reads.incrementAndGet(); return data.inventory(); },
                    () -> { snapshotThread = Thread.currentThread().getName(); return data.snapshot(); }, guard, () -> 0), 3000, control), 3000);
        }
        void close() throws InterruptedException { running = false; owner.interrupt(); owner.join(2000); }
    }
}
