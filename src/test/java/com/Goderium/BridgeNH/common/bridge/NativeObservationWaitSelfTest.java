package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Offline fixed public DTO, normal-tick scheduling and pure-read cancellation fixtures. */
final class NativeObservationWaitSelfTest {
    private static int checks;
    static int run() throws Exception {
        checks = 0; policy(); quantities(); coverage(); scheduling(); cancellation(); return checks;
    }
    private static void policy() {
        NativeObservationWait.Request request = new NativeObservationWait.Request(args());
        check(request.owners.get(0).metadata == 81 && request.owners.get(0).metaTileId == 77, "Raw world metadata remains separate from actual GT identity");
        JsonObject value = args(); value.getAsJsonObject("nativeWait").addProperty("expression", "owner.tick()");
        expect("invalid_args", () -> new NativeObservationWait.Request(value));
        JsonObject arbitrary = args(); arbitrary.getAsJsonObject("nativeWait").getAsJsonArray("conditions").get(0).getAsJsonObject().addProperty("method", "getOutput");
        expect("invalid_args", () -> new NativeObservationWait.Request(arbitrary));
        JsonObject paired = args(); paired.getAsJsonObject("nativeWait").getAsJsonArray("owners").get(0).getAsJsonObject().remove("metaClass");
        expect("invalid_args", () -> new NativeObservationWait.Request(paired));
        JsonObject duplicate = args(); duplicate.getAsJsonArray("positions").get(1).getAsJsonObject().addProperty("x", 1);
        expect("invalid_args", () -> new NativeObservationWait.Request(duplicate));
        JsonObject late = args(); late.getAsJsonObject("nativeWait").addProperty("timeoutMillis", 6501);
        expect("invalid_args", () -> new NativeObservationWait.Request(late));
        for (String operation : new String[] {"player.navigate", "player.place_block", "world.replace_owned_block", "structure.survival_build", "ae.material_source", "gui.widget_action"})
            expect("invalid_args", () -> NativeObservationWait.requireOperation(operation, args()));
        NativeObservationWait.requireOperation("world.inspect_many", args());
        check(true, "Native option is refused before any wrong-operation action branch can queue work");
    }
    private static void quantities() {
        Fixture f = new Fixture(); NativeObservationWait.Request request = new NativeObservationWait.Request(args());
        check(!request.matches(f.data()), "No product delta has appeared");
        f.products = 2; check(!request.matches(f.data()), "A complete first product does not prove the other declared output");
        f.water = 100; check(request.matches(f.data()), "Every declared item and fluid matches together");
        f.products = 1; expect("native_wait_delta_changed", () -> request.matches(f.data()));
        Fixture excess = new Fixture(); excess.products = 3;
        expect("native_wait_delta_changed", () -> new NativeObservationWait.Request(args()).matches(excess.data()));
        Fixture below = new Fixture(); below.products = -1;
        expect("native_wait_delta_changed", () -> new NativeObservationWait.Request(args()).matches(below.data()));
        Fixture fluid = new Fixture(); NativeObservationWait.Request fluidRequest = new NativeObservationWait.Request(args());
        fluid.water = 50; check(!fluidRequest.matches(fluid.data()), "A partial declared fluid remains a wait");
        fluid.water = 20; expect("native_wait_delta_changed", () -> fluidRequest.matches(fluid.data()));
        Fixture foreign = new Fixture(); foreign.water = 20; foreign.fluid = "lava";
        expect("native_wait_delta_changed", () -> new NativeObservationWait.Request(args()).matches(foreign.data()));
        Fixture finalFresh = new Fixture(); finalFresh.products = 2; finalFresh.water = 100;
        NativeObservationWait.Request fresh = new NativeObservationWait.Request(args());
        check(fresh.matches(finalFresh.data()), "Tick readiness was proved"); finalFresh.water = 99;
        expect("native_wait_delta_changed", () -> fresh.matches(finalFresh.data()));
        JsonObject gt = new Fixture().data(); row(gt, 0).getAsJsonObject("gregtech").addProperty("metaTileId", 78);
        expect("native_wait_identity_changed", () -> new NativeObservationWait.Request(args()).matches(gt));
        JsonObject tile = new Fixture().data(); row(tile, 0).addProperty("tileClass", "replacement.Tile");
        expect("native_wait_identity_changed", () -> new NativeObservationWait.Request(args()).matches(tile));
        JsonObject dimension = new Fixture().data(); row(dimension, 0).addProperty("dimension", 179);
        expect("native_wait_identity_changed", () -> new NativeObservationWait.Request(args()).matches(dimension));
    }
    private static void coverage() {
        for (String defect : new String[] {"inventory", "slot", "tank", "tank_index", "named_input", "named_owner"}) {
            JsonObject data = new Fixture().data(); JsonObject value = row(data, 0);
            if ("inventory".equals(defect)) value.getAsJsonObject("inventory").addProperty("truncated", true);
            if ("slot".equals(defect)) value.getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("index", 0);
            if ("tank".equals(defect)) value.getAsJsonObject("fluids").addProperty("totalTanks", 2);
            if ("tank_index".equals(defect)) value.getAsJsonObject("fluids").getAsJsonArray("tanks").get(0).getAsJsonObject().addProperty("index", 1);
            if (defect.startsWith("named")) {
                JsonObject named = named(value); value.getAsJsonObject("fluids").add("namedTanks", named);
                if ("named_input".equals(defect)) named.getAsJsonArray("tanks").get(0).getAsJsonObject().addProperty("capacity", -1);
                else named.getAsJsonObject("owner").addProperty("metaTileId", 78);
            }
            expect("named_input".equals(defect) ? "invalid_args" : "native_wait_identity_changed", () -> new NativeObservationWait.Request(args()).matches(data));
        }
        Fixture f = new Fixture(); JsonObject good = f.data(); row(good, 0).getAsJsonObject("fluids").add("namedTanks", named(row(good, 0)));
        check(!new NativeObservationWait.Request(args()).matches(good), "Complete named input/output coverage is checked even on owners with no fluid predicate");
    }
    private static void scheduling() throws Exception {
        AtomicLong clock = new AtomicLong(); NativeObservationWait engine = new NativeObservationWait(clock::get); Fixture f = new Fixture();
        NativeObservationWait.Job job = engine.register(new NativeObservationWait.Request(args()), f, 8000000000L);
        check(f.reads == 0 && engine.activeCount() == 1 && !job.future.isDone(), "Register only binds; no inventory read, game tick or blocking await runs on a game thread");
        f.tick = 10; engine.tick(); check(f.reads == 2 && job.attempts.get() == 1 && !job.future.isDone(), "Only normal tick entry reads all owners once");
        f.products = 2; f.water = 100; f.tick = 11; engine.tick();
        check("matched".equals(job.await(1)) && engine.activeCount() == 0 && job.access == null, "Readiness removes the observer and clears world/player access immediately");
        JsonObject receipt = job.receipt("matched", true);
        check(receipt.get("attempts").getAsInt() == 2 && receipt.get("firstWorldTick").getAsLong() == 10
                && receipt.get("lastWorldTick").getAsLong() == 11 && !receipt.get("callbackPreemptible").getAsBoolean(), "Aggregate attempts/ticks distinguish ordinary observation from hard realtime");
        check(!job.receipt("matched", false).get("matched").getAsBoolean(), "An older signal never substitutes for fresh final predicates");
        Fixture slow = new Fixture(); slow.onRead = () -> clock.addAndGet(NativeObservationWait.TICK_WORK_NANOS + 1);
        NativeObservationWait.Job partial = engine.register(new NativeObservationWait.Request(args()), slow, clock.get() + 8000000000L);
        engine.tick(); check("partial".equals(partial.await(1)) && partial.receipt("partial", true).get("partial").getAsBoolean()
                && !partial.receipt("partial", true).get("matched").getAsBoolean() && partial.access == null, "A late nonpreemptible callback is partial and cannot authorize continuation");
        NativeObservationWait.Job timeout = engine.register(new NativeObservationWait.Request(args()), new Fixture(), clock.get() + 8000000000L);
        clock.addAndGet(6500000001L); engine.tick(); check("timeout".equals(timeout.await(1)) && timeout.access == null, "Expired tick observers release all access");
        NativeObservationWait.Job noEnd = engine.register(new NativeObservationWait.Request(args()), new Fixture(), clock.get() + 8000000000L);
        check("timeout".equals(noEnd.await(1)) && noEnd.access == null, "HTTP deadline releases an observer without any further END tick");
        NativeObservationWait.Job first = engine.register(new NativeObservationWait.Request(args()), new Fixture(), clock.get() + 8000000000L);
        NativeObservationWait.Job second = engine.register(new NativeObservationWait.Request(args()), new Fixture(), clock.get() + 8000000000L);
        expect("busy", () -> engine.register(new NativeObservationWait.Request(args()), new Fixture(), clock.get() + 8000000000L));
        engine.invalidateAll("paused/exit fixture");
        check(engine.activeCount() == 0 && first.access == null && second.access == null && first.future.isCompletedExceptionally(), "Pause, exit and context invalidation release every observer without ticking");
        Fixture drift = new Fixture(); drift.invalid = true;
        NativeObservationWait.Job changed = engine.register(new NativeObservationWait.Request(args()), drift, clock.get() + 8000000000L);
        engine.tick(); expectFuture("native_wait_context_changed", changed);
        check(changed.access == null && engine.activeCount() == 0, "Bound actor/control failure is terminal without retry");
        Fixture slowCheck = new Fixture(); slowCheck.onCheck = () -> clock.addAndGet(NativeObservationWait.TICK_WORK_NANOS + 1);
        NativeObservationWait.Job checkLate = engine.register(new NativeObservationWait.Request(args()), slowCheck, clock.get() + 8000000000L);
        engine.tick(); check("partial".equals(checkLate.await(1)) && slowCheck.reads == 0, "A late public binding check never starts another owner callback");
    }
    private static void cancellation() throws Exception {
        NativeObservationWait engine = new NativeObservationWait(); NativeObservationWait.Job job = engine.register(new NativeObservationWait.Request(args()), new Fixture(), System.nanoTime() + 8000000000L);
        GameQueue queue = new GameQueue(4); GameQueue.Control read = new GameQueue.Control(); AtomicInteger callbacks = new AtomicInteger();
        Runnable cancel = () -> { callbacks.incrementAndGet(); job.cancel(); }; read.onReadCancellation(cancel);
        GameQueue.Ticket<Integer> ticket = queue.submit(() -> 1, 8000, read); queue.drain(1); check(queue.await(ticket, 1) == 1, "Pure read entered its ordinary queue stage");
        check(read.cancelBeforeExecution() && callbacks.get() == 1 && job.access == null && engine.activeCount() == 0, "Started registered read can cancel its active observer");
        expectFuture("cancelled", job); read.clearReadCancellation(cancel);
        check(!read.cancelBeforeExecution(), "Clearing a read callback removes the post-start cancellation exception");
        GameQueue.Control action = new GameQueue.Control(); GameQueue.Ticket<Integer> write = queue.submit(() -> 2, 8000, action); queue.drain(1);
        check(queue.await(write, 1) == 2 && !action.cancelBeforeExecution() && !action.isCancelled(), "Existing started action cancellation behavior is unchanged");
        GameQueue.Control prior = new GameQueue.Control(); prior.cancelBeforeExecution(); prior.onReadCancellation(callbacks::incrementAndGet);
        check(callbacks.get() == 2, "Cancellation already received before binding is observed once");
    }
    private static JsonObject args() {
        return parse("{\"dimension\":180,\"compact\":false,\"includePlayerInventory\":true,\"positions\":[{\"x\":1,\"y\":64,\"z\":2},{\"x\":2,\"y\":64,\"z\":2}],\"nativeWait\":{\"version\":1,\"timeoutMillis\":6500,\"owners\":[{\"registryName\":\"gregtech:gt.blockmachines\",\"metadata\":81,\"tileClass\":\"actual.Tile\",\"metaTileId\":77,\"metaClass\":\"actual.Meta\"},{\"registryName\":\"gregtech:gt.blockmachines\",\"metadata\":81,\"tileClass\":\"actual.Tile\",\"metaTileId\":77,\"metaClass\":\"actual.Meta\"}],\"conditions\":[{\"kind\":\"item_delta\",\"owner\":0,\"item\":{\"registryName\":\"minecraft:stone\",\"metadata\":0},\"baseline\":4,\"delta\":2},{\"kind\":\"fluid_match\",\"owner\":1,\"tankKind\":\"forge\",\"tankIndex\":0,\"fluid\":\"water\",\"amount\":100}]}}");
    }
    private static JsonObject named(JsonObject value) {
        JsonObject named = parse("{\"schemaVersion\":1,\"available\":true,\"source\":\"integrated_server\",\"scope\":\"complete_actual_owner_named_tanks_public_api_read\",\"binding\":\"public_basic_machine_named_tank_identity\",\"publicApi\":\"gregtech.api.metatileentity.implementations.MTEBasicMachine.getFluidTank/getFluidOutputTank\",\"totalTanks\":2,\"truncated\":false,\"tanks\":[{\"role\":\"input\",\"publicGetter\":\"getFluidTank\",\"capacity\":1000,\"empty\":true,\"fluid\":null},{\"role\":\"output\",\"publicGetter\":\"getFluidOutputTank\",\"capacity\":1000,\"empty\":true,\"fluid\":null}]}");
        JsonObject owner = new JsonObject(); for (String key : new String[] {"x", "y", "z", "dimension", "registryName"}) owner.add(key, value.get(key));
        for (String key : new String[] {"metaTileId", "metaClass"}) owner.add(key, value.getAsJsonObject("gregtech").get(key)); named.add("owner", owner); return named;
    }
    private static final class Fixture implements NativeObservationWait.Access {
        int products, water, reads; long tick; String fluid = "water"; boolean invalid; Runnable onRead = () -> { }, onCheck = () -> { };
        public void check() { onCheck.run(); if (invalid) throw new BridgeException("native_wait_context_changed", "fixture actor/control change"); }
        public JsonObject read(NativeObservationWait.Owner owner) { reads++; onRead.run(); return row(data(), owner.x - 1); }
        public long worldTick() { return tick; }
        JsonObject data() {
            JsonArray rows = new JsonArray();
            for (int x = 1; x <= 2; x++) {
                JsonObject value = parse("{\"source\":\"integrated_server\",\"x\":1,\"y\":64,\"z\":2,\"dimension\":180,\"registryName\":\"gregtech:gt.blockmachines\",\"metadata\":81,\"tileClass\":\"actual.Tile\",\"gregtech\":{\"metaTileId\":77,\"metaClass\":\"actual.Meta\",\"active\":false,\"allowedToWork\":false},\"inventory\":{\"totalSlots\":2,\"truncated\":false,\"slots\":[{\"index\":0,\"item\":{\"registryName\":\"minecraft:stone\",\"metadata\":0,\"count\":4,\"hasTag\":false}},{\"index\":1,\"item\":null}]},\"fluids\":{\"totalTanks\":1,\"truncated\":false,\"tanks\":[{\"index\":0,\"capacity\":1000}]}}");
                value.addProperty("x", x); value.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("item").addProperty("count", 4 + products);
                if (water > 0) { JsonObject tank = value.getAsJsonObject("fluids").getAsJsonArray("tanks").get(0).getAsJsonObject(); tank.addProperty("amount", water); tank.addProperty("fluid", fluid); }
                JsonObject row = new JsonObject(); row.addProperty("ok", true); row.add("data", value); rows.add(row);
            }
            JsonObject result = new JsonObject(); result.add("results", rows); return result;
        }
    }
    private static JsonObject row(JsonObject data, int index) { return data.getAsJsonArray("results").get(index).getAsJsonObject().getAsJsonObject("data"); }
    private static JsonObject parse(String json) { return new JsonParser().parse(json).getAsJsonObject(); }
    private static void expectFuture(String code, NativeObservationWait.Job job) throws Exception {
        try { job.await(1); throw new AssertionError("Expected " + code); } catch (ExecutionException error) { check(error.getCause() instanceof BridgeException && code.equals(((BridgeException) error.getCause()).code), "Future refuses " + code); }
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "Expected " + code + " but got " + error.code); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
