package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.BaublesInventoryAdapter;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/** One fresh player-inventory read in the existing guarded inspect-many AFTER client callback. */
public final class InspectManyPlayerInventory {
    public static final int SLOT_LIMIT = 256, INVENTORY_BYTE_LIMIT = 128 * 1024, OBSERVATION_BYTE_LIMIT = 1024 * 1024;
    public static final long WORK_BUDGET_NANOS = 4000000L;

    public static Request request(String operation, JsonObject args, boolean guarded, boolean developer) {
        boolean requested = GuiDataAdapter.booleanArg(args, "includePlayerInventory", false);
        boolean budgets = args.has("playerInventoryByteBudget") || args.has("observationByteBudget") || args.has("allowPendingZero");
        if (BaublesInventoryAdapter.READ.equals(operation) && !budgets) return null;
        if (!requested) {
            if (budgets) throw new BridgeException("invalid_args", "Inventory budgets require includePlayerInventory=true.");
            return null;
        }
        if (!"world.inspect_many".equals(operation) || !guarded || !developer
                || GuiDataAdapter.booleanArg(args, "compact", false))
            throw new BridgeException("invalid_args", "includePlayerInventory requires a guarded developer world.inspect_many with compact=false.");
        return new Request(JsonData.integer(args, "playerInventoryByteBudget", 1024, INVENTORY_BYTE_LIMIT),
                JsonData.integer(args, "observationByteBudget", 8192, OBSERVATION_BYTE_LIMIT), GuiDataAdapter.booleanArg(args, "allowPendingZero", false));
    }

    public static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("pendingZeroVersion", 1);
        result.addProperty("operation", "world.inspect_many"); result.addProperty("argument", "includePlayerInventory");
        result.addProperty("guardedOnly", true); result.addProperty("developerOnly", true); result.addProperty("compact", false);
        result.addProperty("phase", "after"); result.addProperty("completeInventoryOnly", true);
        result.addProperty("requiredContext", "expectedWorld and requireBackground=true, in addition to the existing session/profile guard");
        result.addProperty("budgets", "Required playerInventoryByteBudget and observationByteBudget. The latter covers the serialized data/context pair; the ordinary HTTP envelope is excluded.");
        result.addProperty("coverage", "Fresh current client player inventory, including every actual slot, cursor and selected hotbar. Full server owner-point data is retained. The two owning-thread reads are non-atomic; no cache or arbitrary batch.");
        return result;
    }

    /** Uses the same public full-slot serializer and item DTO as inventory.inspect. No slot predicates or writes. */
    public static JsonObject read(IInventory inventory, int selectedHotbarSlot, ItemStack cursor) {
        return read(inventory, selectedHotbarSlot, cursor, false);
    }

    public static JsonObject read(IInventory inventory, int selectedHotbarSlot, ItemStack cursor, boolean allowPendingZero) {
        if (inventory == null) throw unavailable("Current player inventory is absent.");
        JsonObject result = JsonData.inventory(inventory);
        result.addProperty("selectedHotbarSlot", selectedHotbarSlot); result.add("cursorItem", JsonData.item(cursor));
        validate(result, allowPendingZero);
        if (inventory.getSizeInventory() != result.get("totalSlots").getAsInt())
            throw unavailable("The actual inventory size changed during its full read.");
        result.addProperty("complete", true);
        return result;
    }

    public static GuardedContext.Result after(Request request, JsonObject ownerData, Supplier<JsonObject> readInventory,
                                       Supplier<JsonObject> snapshot, GuardedContext guard) {
        return after(request, ownerData, readInventory, snapshot, guard, System::nanoTime);
    }

    @com.google.common.annotations.VisibleForTesting
    public static GuardedContext.Result after(Request request, JsonObject ownerData, Supplier<JsonObject> readInventory,
                                       Supplier<JsonObject> snapshot, GuardedContext guard, LongSupplier clock) {
        guard.requirePlayerInventoryRead();
        long started = clock.getAsLong();
        JsonObject inventory = readInventory.get();
        boolean pendingZero = validate(inventory, request.allowPendingZero);
        if (!flag(inventory, "complete", true) || !text(inventory, "source", "client")
                || !text(inventory, "inventorySource", "current_player_inventory") || !flag(inventory, "playerInventoryBindingVerified", true)
                || !nonempty(inventory, "playerId")) throw unavailable("Inventory lacks a full current-player binding.");
        JsonObject current = snapshot.get(); guard.check(current);
        JsonObject world = object(current, "world"), player = object(current, "player");
        if (!flag(current, "worldLoaded", true) || world == null || player == null
                || ownerData == null || !text(ownerData, "source", "integrated_server") || flag(ownerData, "compact", true))
            throw unavailable("Full owner data and a loaded client player/world are required.");
        int dimension = integer(inventory, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (dimension != integer(ownerData, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE)
                || dimension != integer(world, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE))
            throw new BridgeException("DIMENSION_CHANGED", "The server owner read and fresh client inventory/context have different dimensions.");
        if (!same(inventory, current, "sessionId") || !same(inventory, current, "clientTicks")
                || !same(inventory, player, "selectedHotbarSlot") || !same(inventory, player, "playerId"))
            throw unavailable("The fresh inventory and AFTER snapshot bindings differ.");
        if (request.allowPendingZero && !flag(current, "paused", false))
            throw unavailable("Pending-zero waits require an explicitly unpaused fresh AFTER context.");
        int inventoryBytes = bytes(inventory);
        if (inventoryBytes > request.inventoryBytes)
            throw new BridgeException("result_too_large", "The complete player inventory exceeds playerInventoryByteBudget; no partial inventory is returned.");
        JsonObject data = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : ownerData.entrySet()) data.add(entry.getKey(), entry.getValue());
        data.add("playerInventory", inventory);
        JsonObject observation = new JsonObject(); observation.addProperty("version", 1); observation.addProperty("phase", "after");
        observation.addProperty("nonAtomic", true); observation.addProperty("ownerReadBeforeInventory", true);
        observation.addProperty("inventoryComplete", !pendingZero); observation.addProperty("inventoryBytes", inventoryBytes);
        observation.addProperty("playerInventoryByteBudget", request.inventoryBytes);
        observation.addProperty("observationByteBudget", request.observationBytes); observation.addProperty("actualObservationBytes", 0);
        observation.addProperty("workBudgetNanos", WORK_BUDGET_NANOS);
        if (request.allowPendingZero) {
            observation.addProperty("pendingZeroVersion", 1); observation.addProperty("pendingZero", pendingZero);
        }
        observation.addProperty("coverage", "Full fresh current-player client inventory and unmodified full integrated-server owner points. Existing AFTER snapshot guards apply; reads are non-atomic and no inventory is cached.");
        data.add("playerInventoryObservation", observation);
        GuardedContext.Result result = guard.result(data, current);
        JsonObject payload = new JsonObject(); payload.add("data", result.data); payload.add("context", result.context);
        // Include this counter's digits in its own byte accounting. Bounded decimal length reaches a fixed point.
        int observedBytes = 0;
        for (int i = 0; i < 8; i++) {
            int next = bytes(payload);
            if (next == observedBytes) break;
            observedBytes = next; observation.addProperty("actualObservationBytes", observedBytes);
        }
        if (bytes(payload) != observedBytes || observedBytes > request.observationBytes)
            throw new BridgeException("result_too_large", "Full owner data, player inventory and AFTER context exceed observationByteBudget; no partial observation is returned.");
        if (clock.getAsLong() - started > WORK_BUDGET_NANOS)
            throw new BridgeException("inventory_observation_budget", "The fresh inventory/context observation exceeded its work budget; no partial observation is returned.");
        return result;
    }

    private static boolean validate(JsonObject inventory, boolean allowPendingZero) {
        boolean pendingZero = false;
        if (inventory == null || !flag(inventory, "truncated", false)) throw unavailable("Inventory is missing or truncated.");
        int total = integer(inventory, "totalSlots", 9, SLOT_LIMIT);
        integer(inventory, "selectedHotbarSlot", 0, 8);
        JsonElement rows = inventory.get("slots");
        if (rows == null || !rows.isJsonArray() || rows.getAsJsonArray().size() != total)
            throw unavailable("Inventory must contain every actual slot, including empty rows.");
        JsonArray slots = rows.getAsJsonArray();
        for (int i = 0; i < total; i++) {
            if (!slots.get(i).isJsonObject() || integer(slots.get(i).getAsJsonObject(), "index", 0, SLOT_LIMIT - 1) != i)
                throw unavailable("Inventory indices must be the complete unique actual index sequence.");
            pendingZero |= item(slots.get(i).getAsJsonObject().get("item"), allowPendingZero);
        }
        pendingZero |= item(inventory.get("cursorItem"), allowPendingZero);
        return pendingZero;
    }

    private static boolean item(JsonElement value, boolean allowPendingZero) {
        if (value == null) throw unavailable("Every slot and the cursor need an explicit item or null.");
        if (value.isJsonNull()) return false;
        if (!value.isJsonObject()) throw unavailable("Item DTO is not typed.");
        JsonObject item = value.getAsJsonObject();
        JsonElement displayName = item.get("displayName");
        if (!nonempty(item, "registryName") || text(item, "registryName", "null") || displayName == null
                || !displayName.isJsonPrimitive() || !displayName.getAsJsonPrimitive().isString()
                || (!flag(item, "hasTag", true) && !flag(item, "hasTag", false))) throw unavailable("Item DTO lacks its full public identity.");
        int count = integer(item, "count", allowPendingZero ? 0 : 1, Integer.MAX_VALUE);
        integer(item, "metadata", Integer.MIN_VALUE, Integer.MAX_VALUE);
        return count == 0;
    }

    private static int integer(JsonObject value, String key, int min, int max) {
        try { return JsonData.integer(value, key, min, max); }
        catch (RuntimeException error) { throw unavailable("Missing or invalid inventory/context integer: " + key); }
    }
    private static boolean same(JsonObject first, JsonObject second, String key) {
        JsonElement a = first.get(key), b = second.get(key); return a != null && a.isJsonPrimitive() && a.equals(b);
    }
    private static boolean nonempty(JsonObject value, String key) {
        JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isString() && !found.getAsString().isEmpty();
    }
    private static boolean text(JsonObject value, String key, String expected) {
        JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isString() && expected.equals(found.getAsString());
    }
    private static boolean flag(JsonObject value, String key, boolean expected) {
        JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isBoolean() && found.getAsBoolean() == expected;
    }
    private static JsonObject object(JsonObject value, String key) {
        JsonElement found = value.get(key); return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }
    private static int bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static BridgeException unavailable(String message) { return new BridgeException("inventory_observation_unavailable", message); }
    public static final class Request {
        final int inventoryBytes, observationBytes;
        public final boolean allowPendingZero;
        public Request(int inventoryBytes, int observationBytes) { this(inventoryBytes, observationBytes, false); }
        Request(int inventoryBytes, int observationBytes, boolean allowPendingZero) {
            this.inventoryBytes = inventoryBytes; this.observationBytes = observationBytes; this.allowPendingZero = allowPendingZero;
        }
    }
}
