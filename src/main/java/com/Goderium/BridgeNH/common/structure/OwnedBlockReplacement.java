package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.inventory.InspectManyPlayerInventory;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/** A fixed native replacement transaction. Only its HTTP worker awaits owning queues. */
public final class OwnedBlockReplacement {
    public static final String OPERATION = "player.replace_owned_block";
    static final int VERSION = 1, MAX_POLLS = 128, MAX_MOVES = 1;
    static final long DEADLINE_MILLIS = 8000;
    static final Set<String> STAGES = new HashSet<String>(Arrays.asList("prepare", "before_dig", "attack_plan", "move_before_dig",
            "await_move_before_dig", "native_dig", "after_dig", "place_plan", "move_before_place", "await_move_before_place",
            "native_place", "after_place", "final_observation", "completed"));

    public enum Decision { READY, PENDING, MOVE }

    public interface Stages {
        void prepare(State state);
        Decision beforeDig(State state);
        Decision dig(State state);
        void move(State state, boolean place);
        boolean awaitMove(State state, boolean place);
        void refresh(State state, boolean place);
        Decision afterDig(State state);
        Decision place(State state);
        Decision afterPlace(State state);
        GuardedContext.Result finish(State state);
    }

    /** Ordinary operations retain the original cancellation contract and Control implementation. */
    public static final class Control extends GameQueue.Control {
        private volatile boolean cancelRequested;
        @Override public synchronized boolean cancelBeforeExecution() {
            cancelRequested = true;
            return super.cancelBeforeExecution();
        }
        boolean stopRequested() { return cancelRequested || isCancelled(); }
    }

    public static final class State {
        public final GameQueue.Control control;
        final LongSupplier clock;
        final long deadline;
        public volatile String stage = "prepare";
        public volatile boolean mutationStarted, nativeDigSubmitted, nativePlaceSubmitted, digConfirmed, placeConfirmed;
        public JsonObject before;
        public final JsonObject observations = new JsonObject(), serverPlayers = new JsonObject();
        public final JsonArray moves = new JsonArray();
        public JsonObject attackPlan, placePlan, nativeDig, nativePlace;
        public JsonObject conservation;
        State(GameQueue.Control control, LongSupplier clock, long timeoutMillis) {
            this.control = control; this.clock = clock;
            deadline = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }
        public void checkRunning() {
            if (Thread.currentThread().isInterrupted() || control.isCancelled()
                    || control instanceof Control && ((Control) control).stopRequested())
                throw new BridgeException("cancelled", "Native replacement was cancelled; no later native stage may be submitted.");
            if (clock.getAsLong() >= deadline)
                throw new BridgeException("timeout", "Native replacement exhausted its shared deadline; no later native stage may be submitted.");
        }
        long remaining() {
            checkRunning();
            long millis = TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong());
            if (millis < 1) throw new BridgeException("timeout", "Native replacement has no remaining owning-thread deadline.");
            return millis;
        }
        public void markMutation() { checkRunning(); mutationStarted = true; }
        public void markDig() { checkRunning(); if (nativeDigSubmitted) throw new BridgeException("invalid_state", "Native digging cannot be replayed."); mutationStarted = true; nativeDigSubmitted = true; }
        public void markPlace() { checkRunning(); if (nativePlaceSubmitted) throw new BridgeException("invalid_state", "Native placement cannot be replayed."); mutationStarted = true; nativePlaceSubmitted = true; }
        JsonObject flags() {
            JsonObject flags = new JsonObject(); flags.addProperty("stage", stage);
            flags.addProperty("nativeDigSubmitted", nativeDigSubmitted); flags.addProperty("nativePlaceSubmitted", nativePlaceSubmitted);
            flags.addProperty("digConfirmed", digConfirmed); flags.addProperty("placeConfirmed", placeConfirmed);
            flags.addProperty("outcome_unknown", mutationStarted); return flags;
        }
        public JsonObject receipt() {
            if (!nativeDigSubmitted || !nativePlaceSubmitted || !digConfirmed || !placeConfirmed
                    || !observations.has("beforeDig") || !observations.has("afterDig") || !observations.has("afterPlace")
                    || attackPlan == null || placePlan == null || nativeDig == null || nativePlace == null
                    || !serverPlayers.has("beforeDig") || !serverPlayers.has("afterDig") || !serverPlayers.has("afterPlace"))
                throw new BridgeException("invalid_state", "A complete confirmed native replacement receipt is unavailable.");
            JsonObject result = new JsonObject(); result.addProperty("version", VERSION); result.addProperty("operation", OPERATION);
            result.addProperty("status", "completed"); result.addProperty("stage", "completed"); result.addProperty("nonAtomic", true);
            result.addProperty("nativeDigSubmitted", true); result.addProperty("nativePlaceSubmitted", true);
            result.addProperty("digConfirmed", true); result.addProperty("placeConfirmed", true); result.addProperty("replacementValidated", true);
            result.add("observations", observations); result.add("attackPlan", attackPlan); result.add("placePlan", placePlan);
            result.add("nativeDig", nativeDig); result.add("nativePlace", nativePlace); result.add("moves", moves); result.add("serverPlayers", serverPlayers);
            if (conservation == null) throw new BridgeException("inventory_observation_unavailable", "Complete native stack/NBT conservation is not established.");
            result.add("inventoryConservation", conservation);
            return result;
        }
    }

    public static final class Failure extends BridgeException {
        public final JsonObject details;
        Failure(State state, Throwable cause) {
            super(code(cause), message(cause)); details = state.flags();
        }
        private static String code(Throwable cause) { return cause instanceof BridgeException ? ((BridgeException) cause).code : cause instanceof InterruptedException ? "cancelled" : "internal_error"; }
        private static String message(Throwable cause) { return cause instanceof BridgeException ? cause.getMessage() : cause instanceof InterruptedException ? "Native replacement worker was interrupted; no later native stage was submitted." : "Native replacement failed: " + cause.getClass().getSimpleName(); }
    }

    public static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Stages stages) throws InterruptedException {
        return dispatch(client, server, control, stages, System::nanoTime, DEADLINE_MILLIS);
    }

    static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Stages stages,
                                          LongSupplier clock, long timeoutMillis) throws InterruptedException {
        State state = new State(control, clock, timeoutMillis);
        try {
            client(client, state, () -> { stages.prepare(state); return null; });
            int attackMoves = 0;
            for (;;) {
                pollServer(client, server, state, "before_dig", false, stages::beforeDig, stages);
                state.stage = "native_dig";
                Decision decision = client(client, state, () -> stages.dig(state));
                if (decision == Decision.READY) break;
                if (decision == Decision.MOVE) {
                    if (++attackMoves > MAX_MOVES) throw new BridgeException("stale_world", "Attack relocation did not settle within its bounded native replacement.");
                    move(client, server, state, false, stages);
                } else client(client, state, () -> { stages.refresh(state, false); return null; });
            }
            if (!state.nativeDigSubmitted) throw new BridgeException("invalid_state", "The digging stage completed without a native invocation.");
            int placeMoves = 0;
            for (;;) {
                pollServer(client, server, state, "after_dig", true, stages::afterDig, stages);
                state.stage = "native_place";
                Decision decision = client(client, state, () -> stages.place(state));
                if (decision == Decision.READY) break;
                if (decision == Decision.MOVE) {
                    if (++placeMoves > MAX_MOVES) throw new BridgeException("stale_world", "Placement relocation did not settle within its bounded native replacement.");
                    move(client, server, state, true, stages);
                } else client(client, state, () -> { stages.refresh(state, true); return null; });
            }
            if (!state.nativePlaceSubmitted) throw new BridgeException("invalid_state", "The placement stage completed without a native invocation.");
            pollServer(client, server, state, "after_place", true, stages::afterPlace, stages);
            for (int poll = 0; poll < MAX_POLLS; poll++) {
                state.stage = "final_observation";
                GuardedContext.Result result = client(client, state, () -> stages.finish(state));
                if (result != null) { state.stage = "completed"; return result; }
                pollServer(client, server, state, "after_place", true, stages::afterPlace, stages);
            }
            throw new BridgeException("timeout", "The confirmed server placement did not synchronize to a final client observation.");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw new Failure(state, cause == null ? failure : cause);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new Failure(state, failure);
        } catch (RuntimeException failure) {
            if (failure instanceof Failure) throw failure;
            throw new Failure(state, failure);
        }
    }

    interface ServerPhase { Decision call(State state); }
    private static void pollServer(GameQueue client, GameQueue server, State state, String phase, boolean place,
                                   ServerPhase action, Stages stages) throws ExecutionException, InterruptedException {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            state.stage = phase;
            Decision decision = server(server, state, () -> action.call(state));
            if (decision == Decision.READY) return;
            if (decision != Decision.PENDING) throw new BridgeException("invalid_state", "An authoritative server phase cannot request a client move.");
            client(client, state, () -> { stages.refresh(state, place); return null; });
        }
        throw new BridgeException("timeout", "Native replacement synchronization exhausted its bounded observation polls.");
    }

    private static void move(GameQueue client, GameQueue server, State state, boolean place, Stages stages) throws ExecutionException, InterruptedException {
        state.stage = place ? "move_before_place" : "move_before_dig";
        server(server, state, () -> { stages.move(state, place); return null; });
        state.stage = place ? "await_move_before_place" : "await_move_before_dig";
        for (int poll = 0; poll < MAX_POLLS; poll++)
            if (client(client, state, () -> stages.awaitMove(state, place))) return;
        throw new BridgeException("timeout", "Native replacement's server relocation did not synchronize to the client.");
    }

    private static <T> T client(GameQueue queue, State state, Callable<T> action) throws ExecutionException, InterruptedException { return queued(queue, state, action); }
    private static <T> T server(GameQueue queue, State state, Callable<T> action) throws ExecutionException, InterruptedException { return queued(queue, state, action); }
    private static <T> T queued(GameQueue queue, State state, Callable<T> action) throws ExecutionException, InterruptedException {
        long remaining = state.remaining();
        return queue.await(queue.submit(() -> { state.checkRunning(); return action.call(); }, remaining, state.control), remaining);
    }

    public static final class Identity {
        public final String registry, metaClass;
        final int metadata, metaTileId;
        Identity(JsonElement value, boolean placed) {
            if (value == null || !value.isJsonObject()) throw new BridgeException("invalid_args", "Replacement identities must be fixed typed objects.");
            JsonObject object = value.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String key = entry.getKey(); if (!"registryName".equals(key) && !"metadata".equals(key)
                    && !(placed && ("metaTileId".equals(key) || "metaClass".equals(key))))
                throw new BridgeException("invalid_args", "Unknown replacement identity field.");
            }
            registry = JsonData.string(object, "registryName");
            if (registry.indexOf(':') < 1 || registry.length() > 256) throw new BridgeException("invalid_args", "Replacement identity requires a bounded actual registry name.");
            metadata = object.has("metadata") ? JsonData.integer(object, "metadata", 0, 15) : -1;
            metaTileId = placed && object.has("metaTileId") ? JsonData.integer(object, "metaTileId", 0, 32767) : -1;
            metaClass = placed && object.has("metaClass") ? JsonData.string(object, "metaClass") : null;
            if (metadata < 0 && metaTileId < 0 || !placed && metadata < 0 || metaClass != null && (metaClass.isEmpty() || metaClass.length() > 256))
                throw new BridgeException("invalid_args", "Replacement identities require actual world metadata or a separate GregTech metaTileId.");
        }
        public void require(JsonObject actual) {
            if (!registry.equals(JsonData.string(actual, "registryName")) || metadata >= 0 && JsonData.integer(actual, "metadata", 0, 15) != metadata)
                throw new BridgeException("target_unverified", "The current actual block identity differs from the native replacement contract.");
            if (metaTileId >= 0 || metaClass != null) {
                JsonObject gt = actual.has("gregtech") && actual.get("gregtech").isJsonObject() ? actual.getAsJsonObject("gregtech") : null;
                if (gt == null || metaTileId >= 0 && (!gt.has("metaTileId") || gt.get("metaTileId").getAsInt() != metaTileId)
                        || metaClass != null && (!gt.has("metaClass") || !metaClass.equals(gt.get("metaClass").getAsString())))
                    throw new BridgeException("target_unverified", "The current GregTech block identity differs from the native replacement contract.");
            }
        }
        JsonObject json() { JsonObject result = new JsonObject(); result.addProperty("registryName", registry); if (metadata >= 0) result.addProperty("metadata", metadata); if (metaTileId >= 0) result.addProperty("metaTileId", metaTileId); if (metaClass != null) result.addProperty("metaClass", metaClass); return result; }
    }

    public static final class Request {
        public final int x, y, z, dimension, emptySlot, slot;
        public final String world, pattern, sourceRegistry;
        final int sourceMetadata, sourceCount;
        public final boolean sourceHasTag, flying, allowMove;
        public final Identity old, placed;
        public final InspectManyPlayerInventory.Request inventory;
        public Request(JsonObject args) {
            Set<String> keys = new HashSet<String>(Arrays.asList("x", "y", "z", "dimension", "expectedWorld", "emptyHotbarSlot", "hotbarSlot",
                    "expectedOldIdentity", "expectedSourceItem", "expectedPlacedIdentity", "pattern", "requireFlying", "allowMove", "playerInventoryByteBudget", "observationByteBudget"));
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!keys.contains(entry.getKey())) throw new BridgeException("invalid_args", "Unknown native replacement argument.");
            x = JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX); y = JsonData.integer(args, "y", 0, 255);
            z = JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX); dimension = JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            world = JsonData.string(args, "expectedWorld"); if (world.isEmpty() || world.length() > 128) throw new BridgeException("invalid_args", "Replacement expectedWorld is unbounded or empty.");
            emptySlot = JsonData.integer(args, "emptyHotbarSlot", 0, 8); slot = JsonData.integer(args, "hotbarSlot", 0, 8);
            if (emptySlot == slot) throw new BridgeException("invalid_args", "Empty-hand and source hotbar slots must differ.");
            old = new Identity(args.get("expectedOldIdentity"), false); placed = new Identity(args.get("expectedPlacedIdentity"), true);
            if ("minecraft:air".equals(old.registry)) throw new BridgeException("invalid_args", "Owned replacement requires an actual old non-air block.");
            JsonElement sourceValue = args.get("expectedSourceItem");
            if (sourceValue == null || !sourceValue.isJsonObject()) throw new BridgeException("invalid_args", "expectedSourceItem must be a fixed actual Item DTO identity.");
            JsonObject source = sourceValue.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) if (!Arrays.asList("registryName", "metadata", "count", "hasTag").contains(entry.getKey())) throw new BridgeException("invalid_args", "Unknown expected source Item field.");
            sourceRegistry = JsonData.string(source, "registryName"); sourceMetadata = JsonData.integer(source, "metadata", 0, 32767); sourceCount = JsonData.integer(source, "count", 1, 1000000);
            if (sourceRegistry.indexOf(':') < 1 || sourceRegistry.length() > 256 || !source.has("hasTag") || !source.get("hasTag").isJsonPrimitive() || !source.get("hasTag").getAsJsonPrimitive().isBoolean())
                throw new BridgeException("invalid_args", "Replacement source must have a bounded actual item registry and typed hasTag.");
            sourceHasTag = source.get("hasTag").getAsBoolean();
            pattern = args.has("pattern") ? JsonData.string(args, "pattern") : "surround";
            if (!"cardinal".equals(pattern) && !"surround".equals(pattern)) throw new BridgeException("invalid_args", "Unknown bounded replacement approach pattern.");
            flying = GuiDataAdapter.booleanArg(args, "requireFlying", false); allowMove = GuiDataAdapter.booleanArg(args, "allowMove", false);
            inventory = new InspectManyPlayerInventory.Request(JsonData.integer(args, "playerInventoryByteBudget", 1024, InspectManyPlayerInventory.INVENTORY_BYTE_LIMIT),
                    JsonData.integer(args, "observationByteBudget", 8192, InspectManyPlayerInventory.OBSERVATION_BYTE_LIMIT));
        }
        /** Actual native placeability is checked before any replacement mutation, including empty-hand selection. */
        public void requireSource(ItemStack stack) {
            NativePlacementAdapter.requireItem(stack);
            GameBridge.approachTag(stack);
            requireSource(JsonData.item(stack));
        }
        void requireSource(JsonElement value) {
            if (value == null || !value.isJsonObject()) throw new BridgeException("target_unverified", "Replacement source item is absent.");
            JsonObject source = value.getAsJsonObject();
            if (!sourceRegistry.equals(JsonData.string(source, "registryName")) || JsonData.integer(source, "metadata", 0, 32767) != sourceMetadata
                    || JsonData.integer(source, "count", 1, 1000000) != sourceCount || !source.has("hasTag") || source.get("hasTag").getAsBoolean() != sourceHasTag)
                throw new BridgeException("target_unverified", "Replacement source item identity/count/tag presence changed.");
        }
        public JsonObject pointArgs() { JsonObject args = new JsonObject(); JsonArray points = new JsonArray(); JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z); points.add(point); args.add("positions", points); args.addProperty("compact", false); return args; }
        public JsonObject approachArgs(boolean place) { JsonObject args = new JsonObject(); args.addProperty("x", x); args.addProperty("y", y); args.addProperty("z", z); args.addProperty("dimension", dimension); args.addProperty("expectedWorld", world); args.addProperty("intent", place ? "place_block" : "attack"); args.addProperty("pattern", pattern); args.addProperty("requireFlying", flying); if (place) args.addProperty("hotbarSlot", slot); args.add("targetIdentity", place ? airIdentity() : old.json()); return args; }
        public JsonObject placeArgs() { JsonObject args = new JsonObject(); args.addProperty("x", x); args.addProperty("y", y); args.addProperty("z", z); args.addProperty("dimension", dimension); args.addProperty("expectedWorld", world); args.addProperty("hotbarSlot", slot); args.addProperty("replace", false); return args; }
    }

    static JsonObject airIdentity() { JsonObject air = new JsonObject(); air.addProperty("registryName", "minecraft:air"); air.addProperty("metadata", 0); return air; }
    public static JsonObject pair(GuardedContext.Result result) { JsonObject pair = new JsonObject(); pair.add("data", result.data); pair.add("context", result.context); return pair; }
    public static void requireFullInventory(JsonObject inventory) {
        if (inventory == null || !inventory.has("truncated") || !inventory.get("truncated").isJsonPrimitive() || !inventory.get("truncated").getAsJsonPrimitive().isBoolean()
                || inventory.get("truncated").getAsBoolean() || !inventory.has("slots") || !inventory.get("slots").isJsonArray())
            throw new BridgeException("inventory_observation_unavailable", "Replacement needs every actual player inventory slot.");
        int total = JsonData.integer(inventory, "totalSlots", 1, InspectManyPlayerInventory.SLOT_LIMIT); JsonArray slots = inventory.getAsJsonArray("slots");
        if (slots.size() != total || !inventory.has("cursorItem") || !inventory.get("cursorItem").isJsonNull())
            throw new BridgeException("inventory_observation_unavailable", "Replacement player inventory must be complete with an empty cursor.");
        for (int i = 0; i < total; i++) if (!slots.get(i).isJsonObject() || JsonData.integer(slots.get(i).getAsJsonObject(), "index", 0, total - 1) != i || !slots.get(i).getAsJsonObject().has("item"))
            throw new BridgeException("inventory_observation_unavailable", "Replacement player slots lack complete ordered actual indices/items.");
    }
    public static void requireConserved(JsonObject before, JsonObject after) {
        requireFullInventory(before); requireFullInventory(after);
        if (before.get("totalSlots").getAsInt() != after.get("totalSlots").getAsInt() || !before.getAsJsonArray("slots").equals(after.getAsJsonArray("slots")))
            throw new BridgeException("inventory_changed", "Creative native replacement changed a source or unrelated actual player slot.");
    }

    /** The independent server epoch must describe the same player pose/selection as its own client AFTER epoch. */
    public static void requireEpochBinding(JsonObject serverPlayer, JsonObject observation) {
        JsonObject snapshot = observation.getAsJsonObject("context").getAsJsonObject("snapshot");
        JsonObject client = snapshot.getAsJsonObject("player"), world = snapshot.getAsJsonObject("world");
        JsonObject feet = serverPlayer.getAsJsonObject("feet");
        if (!"integrated_server".equals(JsonData.string(serverPlayer, "source"))
                || !JsonData.string(serverPlayer, "playerId").equals(JsonData.string(client, "playerId"))
                || JsonData.integer(serverPlayer, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != JsonData.integer(world, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE)
                || JsonData.integer(serverPlayer, "selectedHotbarSlot", 0, 8) != JsonData.integer(client, "selectedHotbarSlot", 0, 8)
                || Math.abs(JsonData.number(feet, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX) - JsonData.number(client, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX)) > .0001
                || Math.abs(JsonData.number(feet, "y", -4096, 4096) - JsonData.number(client, "feetY", -4096, 4096)) > .0001
                || Math.abs(JsonData.number(feet, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX) - JsonData.number(client, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX)) > .0001
                || !serverPlayer.get("creative").equals(client.get("creative")) || !serverPlayer.get("flying").equals(client.get("flying"))
                || !serverPlayer.get("allowFlying").equals(client.get("allowFlying")))
            throw new BridgeException("stale_world", "Native replacement's independent server player and its own AFTER observation pose/selection differ.");
        if (JsonData.integer(serverPlayer.getAsJsonObject("inventory"), "selectedHotbarSlot", 0, 8) != JsonData.integer(serverPlayer, "selectedHotbarSlot", 0, 8))
            throw new BridgeException("inventory_changed", "Native replacement's server epoch inventory selection differs from its actual player selection.");
    }

    public static final class InventorySnapshot {
        public final ItemStack[] slots;
        public InventorySnapshot(IInventory inventory, ItemStack cursor) {
            if (inventory == null || inventory.getSizeInventory() < 1 || inventory.getSizeInventory() > InspectManyPlayerInventory.SLOT_LIMIT || cursor != null)
                throw new BridgeException("inventory_observation_unavailable", "Replacement native inventory snapshot is incomplete or has a cursor item.");
            slots = new ItemStack[inventory.getSizeInventory()];
            for (int i = 0; i < slots.length; i++) {
                ItemStack item = inventory.getStackInSlot(i); slots[i] = item == null ? null : item.copy();
            }
        }
        public void require(IInventory inventory, ItemStack cursor) {
            if (inventory == null || inventory.getSizeInventory() != slots.length || cursor != null)
                throw new BridgeException("inventory_changed", "Replacement actual inventory size or carried item changed.");
            for (int i = 0; i < slots.length; i++) if (!ItemStack.areItemStacksEqual(slots[i], inventory.getStackInSlot(i)))
                throw new BridgeException("inventory_changed", "Creative replacement changed an actual source/unrelated player stack or its NBT at slot " + i + ".");
        }
    }

    public static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("version", VERSION);
        result.addProperty("guardedOnly", true); result.addProperty("developerOnly", true);
        result.addProperty("oldTilePolicy", "absent"); result.addProperty("fullPlayerSlotConservation", true);
        result.addProperty("ownership", "caller task provenance; bridge verifies exact current identity, not ownership inferred from registry");
        return result;
    }
}
