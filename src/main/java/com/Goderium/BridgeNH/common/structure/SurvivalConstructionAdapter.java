package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.navigation.ApproachPlanner;
import com.Goderium.BridgeNH.common.ae.AeSurvivalSource;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.ConfigurationHandler;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import cpw.mods.fml.common.Loader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;

/** Actual public survival callbacks and finite player accounting. Only the HTTP worker awaits queues. */
public final class SurvivalConstructionAdapter {
    public static final String OPERATION = "structure.survival_build";
    public static final int MAX_BATCHES = 32, MAX_POLLS = 128, MAX_EXPLICIT_BUDGET = 65536, INVENTORY_BYTES = 128 * 1024;

    public static final class Request {
        public final String session, world;
        public final int dimension, x, y, z, slot, side, budget, maxBatches, timeout;
        public final AeSurvivalSource.Request materialSource;
        public final net.minecraft.util.AxisAlignedBB excludedBuildEnvelope;
        final boolean requireSurvivalFlight;
        public Request(JsonObject args) {
            Set<String> keys = new HashSet<String>(Arrays.asList("expectedSessionId", "expectedWorld", "dimension", "x", "y", "z",
                    "blueprintHotbarSlot", "side", "elementBudget", "maxBatches", "timeout_ms", "materialSource", "excludedBuildEnvelope", "requireSurvivalFlight"));
            for (Map.Entry<String, JsonElement> entry : args.entrySet())
                if (!keys.contains(entry.getKey())) throw new BridgeException("invalid_args", "Unexpected survival construction argument: " + entry.getKey());
            session = text(args, "expectedSessionId"); world = text(args, "expectedWorld");
            dimension = JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            x = JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            y = JsonData.integer(args, "y", 0, 255); z = JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            slot = JsonData.integer(args, "blueprintHotbarSlot", 0, 8); side = JsonData.integer(args, "side", 0, 5);
            budget = args.has("elementBudget") ? JsonData.integer(args, "elementBudget", 1, MAX_EXPLICIT_BUDGET) : -1;
            maxBatches = args.has("maxBatches") ? JsonData.integer(args, "maxBatches", 1, MAX_BATCHES) : 1;
            timeout = args.has("timeout_ms") ? JsonData.integer(args, "timeout_ms", 500, 8000) : 7500;
            if (args.has("materialSource") && !args.get("materialSource").isJsonObject())
                throw new BridgeException("invalid_args", "materialSource must be one explicit source object; omission keeps the actual player source.");
            materialSource = args.has("materialSource") ? new AeSurvivalSource.Request(args.getAsJsonObject("materialSource")) : null;
            excludedBuildEnvelope = args.has("excludedBuildEnvelope") ? ApproachPlanner.Request.buildEnvelope(args.get("excludedBuildEnvelope")) : null;
            requireSurvivalFlight = GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false);
        }
        public JsonObject contextArgs() { JsonObject result = new JsonObject(); result.addProperty("expectedWorld", world); if (requireSurvivalFlight) result.addProperty("requireSurvivalFlight", true); return result; }
        private static String text(JsonObject args, String name) {
            String value = JsonData.string(args, name);
            if (value.isEmpty() || value.length() > 128) throw new BridgeException("invalid_args", name + " length must be 1..128.");
            return value;
        }
    }

    public interface BuildStages {
        void prepare(State state);
        /** null means waiting for normal packet synchronization or native cooldown; no callback was invoked. */
        JsonObject batch(State state);
        void refresh(State state);
        GuardedContext.Result finish(State state);
    }
    public interface PlacementStages {
        /** Unbound creative placement retains its local receipt; facing/binding constraints use server confirmation. */
        GuardedContext.Result prepare(State state) throws Exception;
        boolean before(State state);
        void place(State state);
        boolean after(State state);
        void refresh(State state);
        GuardedContext.Result finish(State state);
    }
    public static final class State {
        final GameQueue.Control control;
        public final LongSupplier clock;
        public final long started, deadline;
        volatile String stage = "prepare";
        public volatile int callsStarted, callsCompleted;
        volatile boolean callbackInFlight, nativeSubmitted;
        public JsonObject before, receipt = new JsonObject();
        final JsonArray batches = new JsonArray();
        State(GameQueue.Control control, LongSupplier clock, int timeout) {
            this.control = control; this.clock = clock; started = clock.getAsLong();
            deadline = started + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
        public void check() {
            if (Thread.currentThread().isInterrupted() || control.isCancelled()
                    || control instanceof OwnedBlockReplacement.Control && ((OwnedBlockReplacement.Control) control).stopRequested())
                throw new BridgeException("cancelled", "Survival operation was cancelled; no later native batch may start.");
            if (clock.getAsLong() >= deadline) throw new BridgeException("timeout", "Survival operation exhausted its shared deadline; an already running callback cannot be interrupted.");
        }
        long remaining() {
            check(); long value = TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong());
            if (value < 1) throw new BridgeException("timeout", "No owning-thread deadline remains.");
            return value;
        }
        public void starting() { starting(() -> {}); }
        public void starting(Runnable validate) {
            check(); validate.run();
            nativeSubmitted = true; callbackInFlight = true; callsStarted++;
        }
        public void completed() { callbackInFlight = false; callsCompleted++; }
        JsonObject flags() {
            JsonObject result = new JsonObject(); result.addProperty("stage", stage);
            result.addProperty("failureCheckpoint", receipt.has("lastCheckpoint") ? receipt.get("lastCheckpoint").getAsString() : stage);
            result.addProperty("nativeSubmitted", nativeSubmitted); result.addProperty("callsStarted", callsStarted);
            result.addProperty("callsCompleted", callsCompleted); result.addProperty("callbackInFlight", callbackInFlight);
            result.addProperty("partial", nativeSubmitted); result.addProperty("outcome_unknown", nativeSubmitted);
            result.addProperty("callbackPreemptible", false); result.addProperty("automaticReplayAllowed", false);
            result.add("batches", batches); result.add("knownReceipt", receipt); return result;
        }
    }
    public static final class Failure extends BridgeException {
        public final JsonObject details;
        Failure(State state, Throwable error) {
            super(error instanceof BridgeException ? ((BridgeException) error).code : error instanceof InterruptedException ? "cancelled" : "internal_error",
                    error instanceof BridgeException ? error.getMessage() : "Survival operation failed: " + error.getClass().getSimpleName());
            details = state.flags();
        }
    }

    public static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Request request, BuildStages stages) {
        return dispatch(client, server, control, request, stages, System::nanoTime);
    }
    static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Request request, BuildStages stages, LongSupplier clock) {
        State state = new State(control, clock, request.timeout);
        try {
            queued(client, state, () -> { stages.prepare(state); return null; });
            String status = "batch_limit"; boolean outcomeUnknown = false;
            for (int batch = 0; batch < request.maxBatches; batch++) {
                JsonObject observed = null;
                for (int poll = 0; poll < MAX_POLLS && observed == null; poll++) {
                    state.stage = "native_batch";
                    observed = queued(server, state, () -> stages.batch(state));
                    if (observed == null) queued(client, state, () -> { stages.refresh(state); return null; });
                }
                if (observed == null) throw new BridgeException("timeout", "Survival packet synchronization or native cooldown exhausted bounded polls.");
                state.batches.add(observed);
                outcomeUnknown |= observed.has("outcomeUnknown") && observed.get("outcomeUnknown").getAsBoolean();
                if (!observed.get("accountingComplete").getAsBoolean()) { status = "external_source_unaccounted"; break; }
                int nativeReturn = observed.get("nativeReturn").getAsInt();
                if (nativeReturn == -1) { status = "native_done"; break; }
                if (nativeReturn == -2) { status = "unsupported"; break; }
                if (nativeReturn <= 0) { status = "no_progress"; break; }
                if (batch + 1 < request.maxBatches) queued(client, state, () -> { stages.refresh(state); return null; });
            }
            state.receipt.addProperty("status", status); state.receipt.addProperty("nativeDone", "native_done".equals(status));
            state.receipt.addProperty("formationVerified", false); state.receipt.addProperty("partial", !"native_done".equals(status));
            state.receipt.addProperty("outcomeUnknown", outcomeUnknown); state.receipt.addProperty("callsStarted", state.callsStarted);
            state.receipt.addProperty("callsCompleted", state.callsCompleted); state.receipt.add("batches", state.batches);
            state.receipt.addProperty("callbackPreemptible", false); state.receipt.addProperty("deadlineBoundaryOnly", true);
            state.receipt.addProperty("elapsedMillis", Math.max(0, clock.getAsLong() - state.started) / 1000000.0);
            state.stage = "final_observation";
            return queued(client, state, () -> stages.finish(state));
        } catch (ExecutionException error) { throw new Failure(state, error.getCause() == null ? error : error.getCause()); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Failure(state, error); }
        catch (RuntimeException error) { throw error instanceof Failure ? error : new Failure(state, error); }
    }

    public static GuardedContext.Result localPlacement(State state, GuardedContext guard, Supplier<JsonObject> snapshot,
            Function<Runnable, JsonObject> operation) {
        guard.requireSurvivalOperation(); state.before = snapshot.get(); guard.check(state.before);
        JsonObject result = operation.apply(() -> state.starting(() -> guard.survivalMutationStarting(snapshot.get())));
        if (state.nativeSubmitted) state.completed();
        state.receipt = result;
        return guard.result(result, state.before);
    }

    public static GuardedContext.Result dispatchPlacement(GameQueue client, GameQueue server, GameQueue.Control control, PlacementStages stages) {
        State state = new State(control, System::nanoTime, 7500);
        try {
            GuardedContext.Result creative = queued(client, state, () -> stages.prepare(state));
            if (creative != null) return creative;
            poll(client, server, state, stages, false);
            state.stage = "native_place"; queued(client, state, () -> { stages.place(state); return null; });
            if (state.nativeSubmitted && booleanValue(state.receipt, "nativeInvoked", true) && booleanValue(state.receipt, "accepted", false)) {
                state.stage = "local_place_rejected"; state.receipt.addProperty("lastCheckpoint", "local_place_rejected");
                state.receipt.addProperty("outcomeUnknown", true); state.receipt.addProperty("automaticReplayAllowed", false);
                throw new BridgeException("native_refused", "The one native placement returned explicit local accepted=false. A packet may already have been sent; inspect fresh server state and never replay automatically.");
            }
            poll(client, server, state, stages, true);
            state.stage = "final_observation"; return queued(client, state, () -> stages.finish(state));
        } catch (ExecutionException error) { throw new Failure(state, error.getCause() == null ? error : error.getCause()); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Failure(state, error); }
        catch (RuntimeException error) { throw error instanceof Failure ? error : new Failure(state, error); }
    }
    private static boolean booleanValue(JsonObject receipt, String key, boolean expected) {
        JsonElement value = receipt.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean() == expected;
    }
    private static void poll(GameQueue client, GameQueue server, State state, PlacementStages stages, boolean after) throws ExecutionException, InterruptedException {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            state.stage = after ? "server_after_place" : "server_before_place";
            if (queued(server, state, () -> after ? stages.after(state) : stages.before(state))) return;
            queued(client, state, () -> { stages.refresh(state); return null; });
        }
        throw new BridgeException("timeout", "The actual survival placement did not synchronize inside its bounded confirmation polls; do not replay it.");
    }
    private static <T> T queued(GameQueue queue, State state, Callable<T> action) throws ExecutionException, InterruptedException {
        long remaining = state.remaining();
        return queue.await(queue.submit(() -> { state.check(); return action.call(); }, remaining, state.control), remaining);
    }

    public static void requireBlueprint(ItemStack stack) {
        if (!Loader.isModLoaded("structurelib")) throw new BridgeException("unsupported", "StructureLib is not loaded.");
        if (stack == null || stack.stackSize <= 0 || stack.getItem() != StructureLibAPI.getDefaultHologramItem())
            throw new BridgeException("unsupported", "blueprintHotbarSlot must contain the actual StructureLib hologram item.");
        item(stack);
    }
    static ISurvivalConstructable constructable(TileEntity tile) {
        IConstructable constructable = tile instanceof IConstructableProvider ? ((IConstructableProvider) tile).getConstructable()
                : tile instanceof IConstructable ? (IConstructable) tile : null;
        if (!(constructable instanceof ISurvivalConstructable))
            throw new BridgeException("unsupported", "The actual controller does not expose public ISurvivalConstructable; no creative construction fallback was used.");
        return (ISurvivalConstructable) constructable;
    }
    static int nativeBudget(Request request) { return request.budget < 0 ? ConfigurationHandler.INSTANCE.getAutoPlaceBudget() : request.budget; }
    public static int cooldown() { return ConfigurationHandler.INSTANCE.getAutoPlaceInterval(); }
    /** MP posY is feet on 1.7.10; its client-only getPosition(1) does not add the native public eye height. */
    public static Vec3 serverEye(double x, double serverPosY, double z, float eyeHeight) {
        if (!Double.isFinite(x) || !Double.isFinite(serverPosY) || !Double.isFinite(z) || !Float.isFinite(eyeHeight) || eyeHeight <= 0 || eyeHeight > 8)
            throw new BridgeException("unsupported", "Actual authoritative player eye coordinates are unavailable.");
        return Vec3.createVectorHelper(x, serverPosY + eyeHeight, z);
    }
    public static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("operation", OPERATION);
        result.addProperty("defaultSource", "actual_player_fromPlayer"); result.addProperty("defaultBudget", "actual_native_configuration");
        result.addProperty("maxExplicitElementBudget", MAX_EXPLICIT_BUDGET); result.addProperty("maxBatches", MAX_BATCHES);
        result.addProperty("cooldown", "actual_native_configuration_unchanged"); result.addProperty("totalDeadlineMaximumMillis", 8000);
        result.addProperty("callbackPreemptible", false); result.addProperty("formationVerified", false);
        result.addProperty("automaticReplayAllowed", false); result.add("explicitMaterialSource", AeSurvivalSource.capabilities()); return result;
    }

    /** Copy every actual server slot before mutation; never truncate tag identities or omit empty slots. */
    public static final class Inventory {
        final ItemStack[] slots;
        final int selected;
        public Inventory(IInventory inventory, int selected, ItemStack cursor) {
            if (inventory == null || inventory.getSizeInventory() != 41 || selected < 0 || selected > 8 || cursor != null)
                throw new BridgeException("inventory_observation_unavailable", "Survival accounting requires all 41 actual player slots and an empty cursor.");
            this.selected = selected; slots = new ItemStack[41];
            for (int i = 0; i < slots.length; i++) {
                ItemStack actual = inventory.getStackInSlot(i); slots[i] = actual == null ? null : actual.copy();
            }
            json();
        }
        public JsonObject json() {
            JsonObject result = new JsonObject(); result.addProperty("source", "integrated_server");
            result.addProperty("inventorySource", "actual_player_inventory"); result.addProperty("totalSlots", 41);
            result.addProperty("selectedHotbarSlot", selected); result.addProperty("truncated", false); result.addProperty("complete", true);
            result.add("cursorItem", JsonNull.INSTANCE); JsonArray rows = new JsonArray();
            for (int i = 0; i < slots.length; i++) { JsonObject row = new JsonObject(); row.addProperty("index", i); row.add("item", item(slots[i])); rows.add(row); }
            result.add("slots", rows);
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > INVENTORY_BYTES)
                throw new BridgeException("result_too_large", "Full survival player inventory/NBT exceeds its bounded receipt; no partial inventory is returned.");
            return result;
        }
        Map<String, Amount> bag() {
            Map<String, Amount> result = new LinkedHashMap<String, Amount>();
            for (ItemStack stack : slots) if (stack != null) add(result, stack, stack.stackSize);
            return result;
        }
    }
    public static JsonElement item(ItemStack stack) {
        if (stack == null) return JsonNull.INSTANCE;
        String nbt = stack.hasTagCompound() ? stack.getTagCompound().toString() : "";
        if (nbt.length() > 65536) throw new BridgeException("result_too_large", "Actual stack NBT exceeds the bounded complete identity.");
        JsonObject result = JsonData.item(stack).getAsJsonObject(); result.addProperty("nbtIdentity", nbt); return result;
    }
    public static final class Amount {
        final ItemStack identity;
        long count;
        public long count() { return count; }
        Amount(ItemStack identity, long count) { this.identity = identity.copy(); this.identity.stackSize = 1; this.count = count; }
    }
    private static String key(ItemStack stack) {
        return String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())) + ":" + stack.getMetadata() + ":" + (stack.hasTagCompound() ? stack.getTagCompound().toString() : "");
    }
    public static void add(Map<String, Amount> amounts, ItemStack stack, long count) {
        if (stack == null || count <= 0) throw new BridgeException("inventory_observation_unavailable", "Native source returned an invalid committed stack/count.");
        String key = key(stack); Amount existing = amounts.get(key);
        if (existing == null) {
            if (amounts.size() >= 256) throw new BridgeException("result_too_large", "Material ledger exceeds 256 actual identities.");
            amounts.put(key, new Amount(stack, count));
        } else existing.count = Math.addExact(existing.count, count);
    }
    public static JsonArray amounts(Map<String, Amount> amounts) {
        JsonArray result = new JsonArray();
        for (Amount amount : amounts.values()) { JsonObject row = new JsonObject(); row.add("item", item(amount.identity)); row.addProperty("amount", amount.count); result.add(row); }
        return result;
    }
    static JsonObject accounting(Inventory before, Inventory after, Map<String, Amount> committed, int nativeReturn) {
        Map<String, Amount> first = before.bag(), last = after.bag(), deducted = new LinkedHashMap<String, Amount>();
        boolean complete = true;
        for (Map.Entry<String, Amount> entry : first.entrySet()) {
            Amount current = last.remove(entry.getKey()); long delta = entry.getValue().count - (current == null ? 0 : current.count);
            if (delta < 0) complete = false;
            else if (delta > 0) add(deducted, entry.getValue().identity, delta);
        }
        if (!last.isEmpty() || deducted.size() != committed.size()) complete = false;
        for (Map.Entry<String, Amount> entry : committed.entrySet()) {
            Amount actual = deducted.get(entry.getKey()); if (actual == null || actual.count != entry.getValue().count) complete = false;
        }
        if (nativeReturn > 0 && committed.isEmpty()) complete = false;
        JsonObject result = new JsonObject(); result.addProperty("accountingComplete", complete);
        result.add("committedItems", amounts(committed)); result.add("playerDeductions", amounts(deducted));
        result.addProperty("externalSourceScopeProven", false);
        result.addProperty("accountingMeaning", "Only this observed batch: actual committed public source returns equal complete server player deductions. External extractor/network scope is not generally proven.");
        return result;
    }
    static final class AccountedSource implements IItemSource {
        final IItemSource actual;
        final Map<String, Amount> committed = new LinkedHashMap<String, Amount>();
        boolean sourceFailed;
        AccountedSource(EntityPlayerMP player) { this(IItemSource.fromPlayer(player)); }
        AccountedSource(IItemSource source) { actual = source; }
        @Override public Map<ItemStack, Integer> take(Predicate<ItemStack> predicate, boolean simulate, int count) {
            Map<ItemStack, Integer> returned = actual.take(predicate, simulate, count);
            if (!simulate) {
                try {
                    if (returned == null) sourceFailed = true;
                    else for (Map.Entry<ItemStack, Integer> entry : returned.entrySet()) add(committed, entry.getKey(), entry.getValue());
                } catch (RuntimeException error) { sourceFailed = true; throw error; }
            }
            return returned;
        }
    }
    static JsonObject invoke(EntityPlayerMP player, TileEntity tile, ItemStack trigger, Request request, State state, Runnable nativeStarting) {
        return invoke(player, tile, trigger, request, state, nativeStarting, null);
    }
    public static JsonObject invoke(EntityPlayerMP player, TileEntity tile, ItemStack trigger, Request request, State state, Runnable nativeStarting, AeSurvivalSource aeSource) {
        requireBlueprint(trigger); ISurvivalConstructable constructable = constructable(tile);
        Inventory before = new Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack());
        if ((request.materialSource != null) != (aeSource != null))
            throw new BridgeException("unsupported", "Requested material source is not actually bound; no fallback to player inventory is allowed.");
        AccountedSource playerSource = aeSource == null ? new AccountedSource(player) : null;
        IItemSource source = aeSource == null ? playerSource : aeSource;
        if (aeSource != null) aeSource.beginBatch();
        int budget = nativeBudget(request), interval = cooldown();
        if (budget < 1 || budget > (request.budget < 0 ? 200 : MAX_EXPLICIT_BUDGET) || interval < 0 || interval > 20000)
            throw new BridgeException("unsupported", "Native StructureLib default budget/cooldown or explicit budget is outside its declared range.");
        if (!state.receipt.has("playerInventoryBefore")) state.receipt.add("playerInventoryBefore", before.json());
        state.starting(nativeStarting); long started = state.clock.getAsLong();
        int nativeReturn;
        try { nativeReturn = constructable.survivalConstruct(trigger.copy(), budget, source, player); }
        catch (RuntimeException | LinkageError error) {
            state.receipt.addProperty("callbackFailed", true); state.receipt.addProperty("callbackError", error.getClass().getSimpleName());
            state.receipt.addProperty("callbackErrorMessage", JsonData.bounded(error.getMessage(), 512));
            state.receipt.add("committedItemsAfterFailure", amounts(aeSource == null ? playerSource.committed : aeSource.committed));
            if (aeSource != null) { aeSource.callbackFailed(); state.receipt.add("materialSourceAfterFailure", aeSource.receipt()); }
            try { state.receipt.add("playerInventoryAfter", new Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack()).json()); }
            catch (RuntimeException observationError) { state.receipt.addProperty("afterFailureObservationError", observationError.getClass().getSimpleName()); }
            state.completed();
            if (error instanceof BridgeException) throw (BridgeException) error;
            throw new BridgeException("native_callback_failed", "The actual public survival callback failed: " + error.getClass().getSimpleName() + ". No later batch was submitted.");
        }
        state.completed(); state.receipt.addProperty("lastNativeReturn", nativeReturn);
        Inventory after;
        try { after = new Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack()); }
        catch (RuntimeException | LinkageError error) {
            if (aeSource != null) { aeSource.callbackFailed(); state.receipt.add("materialSourceAfterFailure", aeSource.receipt()); }
            throw error;
        }
        JsonObject result = aeSource == null ? accounting(before, after, playerSource.committed, nativeReturn) : aeAccounting(before, after, aeSource, nativeReturn);
        if (playerSource != null && playerSource.sourceFailed) result.addProperty("accountingComplete", false);
        result.addProperty("nativeReturn", nativeReturn); result.addProperty("requestedBudget", budget);
        result.addProperty("budgetOrigin", request.budget < 0 ? "native_configuration" : "explicit_request");
        result.addProperty("configuredCooldownMillis", interval); result.addProperty("callbackElapsedMillis", Math.max(0, state.clock.getAsLong() - started) / 1000000.0);
        result.addProperty("serverWorldTick", player.worldObj.getTotalWorldTime());
        state.receipt.add("playerInventoryAfter", after.json());
        state.receipt.addProperty("materialAccountingComplete", result.get("accountingComplete").getAsBoolean());
        return result;
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject aeAccounting(Inventory before, Inventory after, AeSurvivalSource source, int nativeReturn) {
        boolean unchanged = before.selected == after.selected;
        for (int i = 0; i < before.slots.length; i++) if (!ItemStack.areItemStacksEqual(before.slots[i], after.slots[i])) unchanged = false;
        try { source.verifyBatchEnd(); }
        catch (RuntimeException | LinkageError error) {
            source.callbackFailed();
            JsonObject failed = source.receipt(); failed.addProperty("accountingComplete", false);
            failed.addProperty("batchEndObservationError", error instanceof BridgeException ? ((BridgeException) error).code : error.getClass().getSimpleName());
            failed.addProperty("playerInventoryUnchanged", unchanged); return failed;
        }
        JsonObject result = source.receipt();
        boolean complete = result.get("accountingComplete").getAsBoolean() && unchanged && (nativeReturn <= 0 || !source.committed.isEmpty());
        result.addProperty("playerInventoryUnchanged", unchanged); result.addProperty("accountingComplete", complete);
        result.addProperty("accountingMeaning", "This explicit cell-container batch only: fresh native backing counts match actual powered extraction, and every actual player slot/full NBT remains unchanged. Creative supply is separate from finite conservation.");
        if (!complete) {
            source.callbackFailed(); result.addProperty("finiteDecrementVerified", false); result.addProperty("supplyDidNotDecrease", false);
            result.addProperty("outcomeUnknown", true); result.addProperty("automaticReplayAllowed", false);
        }
        return result;
    }
    /** Strict normal placement: only one selected-slot decrement, with exact full Stack/NBT preservation elsewhere. */
    public static boolean placementConsumed(Inventory before, Inventory after, int slot) {
        if (slot < 0 || slot > 8 || before.slots[slot] == null || before.slots[slot].stackSize < 1) return false;
        for (int i = 0; i < 41; i++) {
            ItemStack expected = before.slots[i] == null ? null : before.slots[i].copy();
            if (i == slot && --expected.stackSize == 0) expected = null;
            if (!ItemStack.areItemStacksEqual(expected, after.slots[i])) return false;
        }
        return true;
    }
}
