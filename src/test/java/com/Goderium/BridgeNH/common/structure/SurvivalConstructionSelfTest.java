package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.common.ae.AeSurvivalSourceSelfTest;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Directed pure inventory and distinct real owning-queue tests. Native construction/world actions are fixtures. */
public final class SurvivalConstructionSelfTest {
    private static int checks;
    private static Item fixtureItem;
    public static void main(String[] args) throws Exception { System.out.println("Survival directed assertions: " + run()); }
    static int run() throws Exception {
        checks = 0;
        fixtureItem = new Item() { @Override public String getItemStackDisplayName(ItemStack stack) { return "Survival material fixture"; } };
        Item.itemRegistry.addObject(32301, "survival_fixture:material", fixtureItem);
        requests(); modesAndGuards(); inventoryAndAccounting(); publicSource(); batches(); placementStages(); localPlacementOutcomes(); localPlacementCheckpoints();
        return checks;
    }
    private static JsonObject args() {
        JsonObject result = new JsonObject(); result.addProperty("expectedSessionId", "session"); result.addProperty("expectedWorld", "world");
        result.addProperty("dimension", 180); result.addProperty("x", -620); result.addProperty("y", 4); result.addProperty("z", -12);
        result.addProperty("blueprintHotbarSlot", 2); result.addProperty("side", 4); return result;
    }
    private static JsonObject context() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("expectedSessionId", "session");
        result.addProperty("expectedProfile", "developer"); result.addProperty("expectedWorld", "world"); result.addProperty("requireBackground", true); return result;
    }
    private static JsonObject snapshot() {
        JsonObject result = new JsonObject(); result.addProperty("sessionId", "session"); result.addProperty("profile", "developer"); result.addProperty("worldLoaded", true);
        JsonObject world = new JsonObject(); world.addProperty("worldName", "world"); result.add("world", world);
        JsonObject protection = new JsonObject(); protection.addProperty("enabled", true); protection.addProperty("helperInstalled", true);
        protection.addProperty("pauseOnLostFocus", false); result.add("background", protection); return result;
    }
    private static void requests() {
        SurvivalConstructionAdapter.Request request = new SurvivalConstructionAdapter.Request(args());
        check(request.budget == -1 && request.maxBatches == 1 && request.timeout == 7500 && request.side == 4, "Default single batch uses actual native budget/cooldown without guessed trigger settings");
        for (String key : new String[] {"expectedSessionId", "expectedWorld", "dimension", "x", "y", "z", "blueprintHotbarSlot", "side"}) {
            JsonObject invalid = args(); invalid.remove(key); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(invalid));
        }
        for (int budget : new int[] {0, 65537}) { JsonObject invalid = args(); invalid.addProperty("elementBudget", budget); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(invalid)); }
        for (int count : new int[] {0, 33}) { JsonObject invalid = args(); invalid.addProperty("maxBatches", count); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(invalid)); }
        for (int timeout : new int[] {499, 8001}) { JsonObject invalid = args(); invalid.addProperty("timeout_ms", timeout); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(invalid)); }
        JsonObject unknown = args(); unknown.addProperty("pieceName", "guessed"); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(unknown));
        JsonObject interval = args(); interval.addProperty("interval", 0); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(interval));
        JsonObject valid = args(); valid.addProperty("maxBatches", 32); valid.addProperty("elementBudget", 65536); valid.addProperty("timeout_ms", 8000);
        SurvivalConstructionAdapter.Request largest = new SurvivalConstructionAdapter.Request(valid);
        check(largest.maxBatches == 32 && largest.budget == 65536 && largest.materialSource == null, "Explicit large native budget is accepted while omitted source remains actual player");
        JsonObject sourceArgs = args(); sourceArgs.add("materialSource", AeSurvivalSourceSelfTest.sourceArgs("finite"));
        check("finite".equals(new SurvivalConstructionAdapter.Request(sourceArgs).materialSource.mode), "Explicit AE source is parsed without changing default player source");
        for (com.google.gson.JsonElement invalid : new com.google.gson.JsonElement[] {com.google.gson.JsonNull.INSTANCE, new com.google.gson.JsonArray(), new com.google.gson.JsonPrimitive("player")}) {
            JsonObject requestArgs = args(); requestArgs.add("materialSource", invalid); expect("invalid_args", () -> new SurvivalConstructionAdapter.Request(requestArgs));
        }
    }
    private static void modesAndGuards() {
        NativePlacementAdapter.requireMatchingMode(false, false); NativePlacementAdapter.requireMatchingMode(true, true);
        check(true, "Normal native placement accepts consistent survival or creative mode");
        expect("permission_denied", () -> NativePlacementAdapter.requireMatchingMode(false, true));
        expect("permission_denied", () -> NativePlacementAdapter.requireMatchingMode(true, false));
        expect("permission_denied", () -> NativePlacementAdapter.requireCreative(false, false));
        check(true, "Destructive replacement creative gate is retained separately");
        for (String operation : new String[] {SurvivalConstructionAdapter.OPERATION, "player.place_block"}) {
            GuardedContext guard = GuardedContext.parse(operation, context()); guard.requireSurvivalOperation();
            check(!guard.outcomeUnknown(), "Guard parse/preflight does not claim a submitted callback");
            JsonObject lost = snapshot(); lost.getAsJsonObject("background").addProperty("enabled", false);
            expect("TAKEOVER_LOST", () -> guard.survivalMutationStarting(lost)); check(!guard.outcomeUnknown(), "Lost background stops before mutation boundary");
            guard.survivalMutationStarting(snapshot()); check(guard.outcomeUnknown(), "Actual mutation boundary marks unknown-error policy");
            JsonObject unprotected = context(); unprotected.addProperty("requireBackground", false);
            expect("invalid_request", () -> GuardedContext.parse(operation, unprotected).requireSurvivalOperation());
        }
        expect("invalid_request", () -> GuardedContext.parse("player.interact", context()).requireSurvivalOperation());
        RequestRegistry registry = new RequestRegistry(8, 1000);
        for (String operation : new String[] {SurvivalConstructionAdapter.OPERATION, "player.place_block"}) {
            GameQueue.Control control = registry.register(operation, operation);
            check(control instanceof OwnedBlockReplacement.Control, "Only fixed staged native operations reuse boundary-cancellable Control");
        }
        check(!(registry.register("ordinary", "player.interact") instanceof OwnedBlockReplacement.Control), "Ordinary control contract is unchanged");
        check(!RequestRegistry.readOnly(SurvivalConstructionAdapter.OPERATION), "Survival construction retains mutation-grade request deduplication");
    }
    private static InventoryBasic inventory(int count) {
        InventoryBasic result = new InventoryBasic("all actual fixture slots", false, 41);
        ItemStack material = new ItemStack(fixtureItem, count, 7); material.stackTagCompound = new NBTTagCompound(); material.stackTagCompound.setString("channel", "native");
        result.setInventorySlotContents(2, material); ItemStack last = new ItemStack(fixtureItem, 3, 8);
        last.stackTagCompound = new NBTTagCompound(); last.stackTagCompound.setString("unrelated", "preserve"); result.setInventorySlotContents(40, last); return result;
    }
    private static SurvivalConstructionAdapter.Inventory copied(InventoryBasic inventory) { return new SurvivalConstructionAdapter.Inventory(inventory, 2, null); }
    private static void inventoryAndAccounting() {
        for (int count : new int[] {1, 2, 64}) {
            InventoryBasic live = inventory(count); SurvivalConstructionAdapter.Inventory before = copied(live);
            check(before.slots.length == 41 && before.slots[2] != live.getStackInSlot(2) && before.slots[2].stackTagCompound != live.getStackInSlot(2).stackTagCompound, "All actual stacks and tags are independently copied");
            JsonObject full = before.json(); check(full.getAsJsonArray("slots").size() == 41 && "integrated_server".equals(full.get("source").getAsString())
                    && !full.get("truncated").getAsBoolean() && full.get("cursorItem").isJsonNull(), "Complete authoritative 41-slot DTO retains empty rows and empty cursor");
            check(full.getAsJsonArray("slots").get(2).getAsJsonObject().getAsJsonObject("item").get("nbtIdentity").getAsString().contains("native"), "Complete tag identity is carried as data, not only hasTag");
            if (count == 1) live.setInventorySlotContents(2, null); else live.getStackInSlot(2).stackSize--;
            SurvivalConstructionAdapter.Inventory after = copied(live);
            check(SurvivalConstructionAdapter.placementConsumed(before, after, 2), "Exactly one real source is consumed including null after the last item");
            Map<String, SurvivalConstructionAdapter.Amount> take = new LinkedHashMap<String, SurvivalConstructionAdapter.Amount>();
            SurvivalConstructionAdapter.add(take, before.slots[2], 1);
            JsonObject accounted = SurvivalConstructionAdapter.accounting(before, after, take, 1);
            check(accounted.get("accountingComplete").getAsBoolean() && !accounted.get("externalSourceScopeProven").getAsBoolean(), "Observed player ledger match proves this batch without claiming generic network/extractor scope");
            check(accounted.getAsJsonArray("playerDeductions").get(0).getAsJsonObject().get("amount").getAsLong() == 1, "Actual material count uses long ledger amount");
            live.getStackInSlot(40).stackTagCompound.setString("unrelated", "changed");
            check(!SurvivalConstructionAdapter.placementConsumed(before, copied(live), 2), "NBT-only changes in an unrelated actual slot invalidate conservation");
            check(!SurvivalConstructionAdapter.accounting(before, copied(live), take, 1).get("accountingComplete").getAsBoolean(), "Unaccounted tag identity change stops native batch accounting");
            check("preserve".equals(before.slots[40].stackTagCompound.getString("unrelated")), "Later live tag mutation cannot alter frozen before evidence");
        }
        InventoryBasic live = inventory(2); SurvivalConstructionAdapter.Inventory same = copied(live);
        Map<String, SurvivalConstructionAdapter.Amount> external = new LinkedHashMap<String, SurvivalConstructionAdapter.Amount>();
        SurvivalConstructionAdapter.add(external, same.slots[2], 1);
        check(!SurvivalConstructionAdapter.accounting(same, same, external, 1).get("accountingComplete").getAsBoolean(), "Committed source returns supplied externally do not pretend player deduction");
        check(!SurvivalConstructionAdapter.accounting(same, same, new LinkedHashMap<String, SurvivalConstructionAdapter.Amount>(), 1).get("accountingComplete").getAsBoolean(), "Positive native progress with no finite deduction is not a finite-material proof");
        check(SurvivalConstructionAdapter.accounting(same, same, new LinkedHashMap<String, SurvivalConstructionAdapter.Amount>(), -1).get("accountingComplete").getAsBoolean(), "Done with no mutations and no source takes preserves actual player inventory");
        expect("inventory_observation_unavailable", () -> new SurvivalConstructionAdapter.Inventory(new InventoryBasic("missing", false, 40), 2, null));
        expect("inventory_observation_unavailable", () -> new SurvivalConstructionAdapter.Inventory(live, 2, new ItemStack(fixtureItem)));
        expect("inventory_observation_unavailable", () -> SurvivalConstructionAdapter.add(external, null, 1));
        ItemStack huge = new ItemStack(fixtureItem); huge.stackTagCompound = new NBTTagCompound(); huge.stackTagCompound.setString("large", new String(new char[65537]).replace('\0', 'x'));
        expect("result_too_large", () -> SurvivalConstructionAdapter.item(huge));
    }
    private static void publicSource() {
        IItemSource actual = (predicate, simulate, count) -> {
            Map<ItemStack, Integer> result = new LinkedHashMap<ItemStack, Integer>(); ItemStack stack = new ItemStack(fixtureItem, 1, 7);
            if (predicate.test(stack)) result.put(stack, count); return result;
        };
        SurvivalConstructionAdapter.AccountedSource source = new SurvivalConstructionAdapter.AccountedSource(actual);
        source.take(stack -> true, true, 2); check(source.committed.isEmpty(), "Simulated availability probes are excluded from committed material ledger");
        source.take(stack -> true, false, 2); source.take(stack -> true, false, 3);
        check(source.committed.size() == 1 && source.committed.values().iterator().next().count == 5, "Public non-simulate take returns aggregate actual committed identities/counts once");
        source.take(stack -> false, false, 1); check(source.committed.values().iterator().next().count == 5, "An empty real take result does not invent consumption");
    }
    private static void batches() throws Exception {
        for (String mode : new String[] {"done", "unsupported", "no_progress", "limit", "unaccounted", "ae_uncertain", "cancel", "deadline", "callback_error", "never_ready"}) {
            try (Harness harness = new Harness()) {
                JsonObject args = args(); args.addProperty("maxBatches", 3); BuildFixture fixture = new BuildFixture(harness, mode);
                try {
                    GuardedContext.Result result = SurvivalConstructionAdapter.dispatch(harness.client, harness.server, fixture.control,
                            new SurvivalConstructionAdapter.Request(args), fixture, harness.clock::get);
                    check(!result.data.get("formationVerified").getAsBoolean(), "Native callback done remains separate from actual structure formation");
                    if ("done".equals(mode)) check("native_done".equals(result.data.get("status").getAsString()) && fixture.calls == 2
                            && fixture.secondCallbackAt - fixture.firstCallbackAt >= 300000000L,
                            "Two bounded native callbacks respect configured simulated cooldown and then stop on -1");
                    if ("no_progress".equals(mode)) check("no_progress".equals(result.data.get("status").getAsString()) && fixture.calls == 1, "No progress stops after one callback without retrying");
                    if ("unsupported".equals(mode)) check("unsupported".equals(result.data.get("status").getAsString()) && fixture.calls == 1, "Native -2 reports unsupported migration/environment separately from missing-material no progress");
                    if ("limit".equals(mode)) check("batch_limit".equals(result.data.get("status").getAsString()) && fixture.calls == 3, "Explicit maxBatches bounds actual callback count");
                    if ("unaccounted".equals(mode)) check("external_source_unaccounted".equals(result.data.get("status").getAsString()) && fixture.calls == 1, "Incomplete material accounting stops all later native batches");
                    if ("ae_uncertain".equals(mode)) check("external_source_unaccounted".equals(result.data.get("status").getAsString()) && fixture.calls == 1
                            && result.data.get("outcomeUnknown").getAsBoolean(), "AE batch accounting failure retains its uncertain submitted outcome in the final receipt");
                } catch (SurvivalConstructionAdapter.Failure failure) {
                    check("cancel".equals(mode) || "deadline".equals(mode) || "callback_error".equals(mode) || "never_ready".equals(mode), "Only directed stopping fixtures fail");
                    check(fixture.calls <= 1 && failure.details.get("callsStarted").getAsInt() == fixture.calls, "Cancellation/deadline/native failure never replay a submitted callback");
                    if ("callback_error".equals(mode)) check("native_refused".equals(failure.code), "Actual callback failure code is retained in staged failure");
                    if ("never_ready".equals(mode)) check(fixture.calls == 0 && !failure.details.get("outcome_unknown").getAsBoolean(), "Failed packet/cooldown readiness has a known no-callback outcome");
                    else check(failure.details.get("outcome_unknown").getAsBoolean(), "Submitted callback retains unknown-error/no-automatic-replay policy");
                    JsonObject response = BridgeHttpServer.failure("fixture", failure);
                    check(response.getAsJsonObject("error").has("callsStarted") && response.getAsJsonObject("error").has("knownReceipt"), "RPC error exposes truthful staged receipt details");
                }
            }
        }
    }
    private static void localPlacementCheckpoints() {
        for (String mode : new String[] {"preflight", "expired", "takeover_lost", "callback", "returned"}) {
            AtomicLong now = new AtomicLong();
            SurvivalConstructionAdapter.State state = new SurvivalConstructionAdapter.State(new OwnedBlockReplacement.Control(), now::get, 500);
            GuardedContext guard = GuardedContext.parse("player.place_block", context());
            JsonObject observed = snapshot();
            try {
                SurvivalConstructionAdapter.localPlacement(state, guard, () -> observed, starting -> {
                    if ("preflight".equals(mode)) throw new BridgeException("out_of_reach", "Target beyond native reach.");
                    if ("expired".equals(mode)) now.set(500000000L);
                    if ("takeover_lost".equals(mode)) observed.getAsJsonObject("background").addProperty("enabled", false);
                    starting.run();
                    if ("callback".equals(mode)) throw new BridgeException("native_refused", "Native callback failed.");
                    return new JsonObject();
                });
                check("returned".equals(mode) && state.callsCompleted == 1 && !state.callbackInFlight,
                        "Successful local creative placement accounts exactly one completed callback");
            } catch (BridgeException error) {
                String expected = "preflight".equals(mode) ? "out_of_reach" : "expired".equals(mode) ? "timeout"
                        : "takeover_lost".equals(mode) ? "TAKEOVER_LOST" : "native_refused";
                check(expected.equals(error.code), "Original preflight/native error code survives");
                JsonObject envelope = BridgeHttpServer.failure("fixture", new SurvivalConstructionAdapter.Failure(state, error));
                JsonObject failure = envelope.getAsJsonObject("error");
                failure.addProperty("outcome_unknown", guard.outcomeUnknown()); // HTTP boundary applies the same guard flag.
                check(failure.get("outcome_unknown").getAsBoolean() == failure.get("nativeSubmitted").getAsBoolean()
                                && failure.get("partial").getAsBoolean() == guard.outcomeUnknown(),
                        "Transport guard and native checkpoint agree before and after the mutation boundary");
            }
            boolean submitted = "callback".equals(mode) || "returned".equals(mode);
            check(guard.outcomeUnknown() == submitted && state.nativeSubmitted == submitted
                            && state.callsStarted == (submitted ? 1 : 0),
                    "Preflight, deadline and takeover failure never mark a native callback as submitted");
        }
    }
    private static void placementStages() throws Exception {
        for (boolean creative : new boolean[] {false, true}) try (Harness harness = new Harness()) {
            PlacementFixture fixture = new PlacementFixture(harness, creative);
            GuardedContext.Result result = SurvivalConstructionAdapter.dispatchPlacement(harness.client, harness.server, fixture.control, fixture);
            check(fixture.places == 1 && result.data.get("serverConfirmed").getAsBoolean() == !creative, "One native placement has authoritative confirmation only on actual survival path");
            check(creative ? fixture.beforeReads == 0 && fixture.afterReads == 0 : fixture.beforeReads == 2 && fixture.afterReads == 2, "Creative preserves local path; survival requires fresh server before/after barriers");
        }
    }
    private static void localPlacementOutcomes() throws Exception {
        try (Harness harness = new Harness()) {
            PlacementFixture f = new PlacementFixture(harness, false); f.accepted = new JsonPrimitive(false);
            try { SurvivalConstructionAdapter.dispatchPlacement(harness.client, harness.server, f.control, f); throw new AssertionError("local rejection must stop"); }
            catch (SurvivalConstructionAdapter.Failure failure) {
                JsonObject flags = failure.details, known = flags.getAsJsonObject("knownReceipt");
                check("native_refused".equals(failure.code) && "local_place_rejected".equals(flags.get("failureCheckpoint").getAsString())
                        && f.places == 1 && f.afterReads == 0 && flags.get("callsStarted").getAsInt() == 1 && flags.get("callsCompleted").getAsInt() == 1
                        && !flags.get("callbackInFlight").getAsBoolean() && flags.get("outcome_unknown").getAsBoolean()
                        && !flags.get("automaticReplayAllowed").getAsBoolean() && !known.get("accepted").getAsBoolean(),
                        "typed local false stops before ineffective server polling while retaining submission, completion, unknown effects and no replay");
            }
        }
        for (JsonElement accepted : new JsonElement[] {null, JsonNull.INSTANCE, new JsonPrimitive(true), new JsonPrimitive("false"), new JsonPrimitive(0)})
            try (Harness harness = new Harness()) {
                PlacementFixture f = new PlacementFixture(harness, false); f.accepted = accepted;
                SurvivalConstructionAdapter.dispatchPlacement(harness.client, harness.server, f.control, f);
                check(f.places == 1 && f.afterReads == 2, "only explicit typed false takes local rejection shortcut; unknown local values retain authoritative confirmation");
            }
    }
    private static final class Harness implements AutoCloseable {
        final GameQueue client = new GameQueue(16), server = new GameQueue(16);
        final AtomicLong clock = new AtomicLong(1);
        final Thread clientOwner, serverOwner;
        volatile boolean running = true;
        Harness() { clientOwner = pump(client, "survival-client-owner"); serverOwner = pump(server, "survival-server-owner"); }
        Thread pump(GameQueue queue, String name) {
            Thread thread = new Thread(() -> { while (running) { queue.drain(1); LockSupport.parkNanos(100000L); } }, name);
            thread.setDaemon(true); thread.start(); return thread;
        }
        @Override public void close() throws InterruptedException {
            running = false; clientOwner.interrupt(); serverOwner.interrupt(); clientOwner.join(1000); serverOwner.join(1000);
            check(!clientOwner.isAlive() && !serverOwner.isAlive(), "Distinct directed owner pumps shut down after fixture");
        }
    }
    private static void owner(boolean server) { check(Thread.currentThread().getName().equals(server ? "survival-server-owner" : "survival-client-owner"), "Every staged callback executes on its actual owner queue"); }
    private static final class BuildFixture implements SurvivalConstructionAdapter.BuildStages {
        final Harness harness;
        final String mode;
        final OwnedBlockReplacement.Control control = new OwnedBlockReplacement.Control();
        final GuardedContext guard = GuardedContext.parse(SurvivalConstructionAdapter.OPERATION, context());
        int calls, cooldownWaits;
        long completed, firstCallbackAt, secondCallbackAt;
        BuildFixture(Harness harness, String mode) { this.harness = harness; this.mode = mode; }
        public void prepare(SurvivalConstructionAdapter.State state) { owner(false); state.before = snapshot(); guard.check(state.before); }
        public JsonObject batch(SurvivalConstructionAdapter.State state) {
            owner(true);
            if ("never_ready".equals(mode)) return null;
            if (calls > 0 && harness.clock.get() - completed < 300000000L) { cooldownWaits++; return null; }
            guard.survivalMutationStarting(state.before); state.starting(); calls++;
            if (calls == 1) firstCallbackAt = harness.clock.get();
            if (calls == 2) secondCallbackAt = harness.clock.get();
            if ("callback_error".equals(mode)) throw new BridgeException("native_refused", "Actual directed public callback refusal");
            JsonObject result = new JsonObject(); result.addProperty("accountingComplete", !"unaccounted".equals(mode) && !"ae_uncertain".equals(mode));
            if ("ae_uncertain".equals(mode)) result.addProperty("outcomeUnknown", true);
            result.addProperty("nativeReturn", "done".equals(mode) && calls == 2 ? -1 : "unsupported".equals(mode) ? -2 : "no_progress".equals(mode) ? 0 : 1);
            result.addProperty("configuredCooldownMillis", 300); state.completed(); completed = harness.clock.get();
            if ("cancel".equals(mode)) control.cancelBeforeExecution();
            if ("deadline".equals(mode)) harness.clock.set(8000000001L);
            return result;
        }
        public void refresh(SurvivalConstructionAdapter.State state) { owner(false); harness.clock.addAndGet(100000000L); }
        public GuardedContext.Result finish(SurvivalConstructionAdapter.State state) { owner(false); return guard.result(state.receipt, state.before); }
    }
    private static final class PlacementFixture implements SurvivalConstructionAdapter.PlacementStages {
        final Harness harness;
        final boolean creative;
        final OwnedBlockReplacement.Control control = new OwnedBlockReplacement.Control();
        int places, beforeReads, afterReads; JsonElement accepted;
        PlacementFixture(Harness harness, boolean creative) { this.harness = harness; this.creative = creative; }
        public GuardedContext.Result prepare(SurvivalConstructionAdapter.State state) {
            owner(false); state.before = snapshot();
            if (!creative) return null;
            places++; state.receipt.addProperty("serverConfirmed", false); return new GuardedContext.Result(state.receipt, null);
        }
        public boolean before(SurvivalConstructionAdapter.State state) { owner(true); return ++beforeReads >= 2; }
        public void place(SurvivalConstructionAdapter.State state) { owner(false); state.starting(); places++; state.completed();
            state.receipt.addProperty("nativeInvoked", true); if (accepted != null) state.receipt.add("accepted", accepted);
        }
        public boolean after(SurvivalConstructionAdapter.State state) { owner(true); return ++afterReads >= 2; }
        public void refresh(SurvivalConstructionAdapter.State state) { owner(false); }
        public GuardedContext.Result finish(SurvivalConstructionAdapter.State state) { owner(false); state.receipt.addProperty("serverConfirmed", true); return new GuardedContext.Result(state.receipt, null); }
    }
    private static void expect(String code, Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException actual) { check(code.equals(actual.code), "Expected directed failure: " + code + ", got " + actual.code); }
    }
    private static synchronized void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
