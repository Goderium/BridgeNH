package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.client.navigation.PlayerNavigation;
import com.Goderium.BridgeNH.common.ae.AeSurvivalSource;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransfer;
import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacement;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/** Optional fixed-operation RPC context. It never supplies Java method names or replaces native gates. */
public final class GuardedContext {
    private static final String[] READS = {"survival.storage_count", "survival.storage_receipt", "quest.progress", "bridge.health", "client.messages", "inventory.inspect", "baubles.inspect", "gui.inspect", "gui.widgets",
            "structure.goal", "registry.item", "world.inspect", "world.inspect_many", "world.spatial", "world.structure", "machine.inspect", "player.plan_approach", "ae.material_source"};
    private static final String[] ACTIONS = {"player.hotbar", "player.mine", "player.entity_interact", "gui.chest_transfer", "player.world_mouse", "player.interact", "player.place_block", "player.look",
            "player.flight", "player.navigate", "survival.storage_open", "survival.storage_move", "quest.step", "survival.food", "chat.send", "gui.close", "gui.open_inventory", "gui.open_baubles", "gui.open_pause_menu", "gui.widget_action", "gui.key", "gui.slot_click", WholeStackTransfer.OPERATION, "gui.pointer", "player.replace_owned_block", "structure.survival_build"};
    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList("version", "expectedSessionId", "expectedProfile", "expectedWorld", "requireBackground", "menuOnly", "expectedDimension", "requireIdleInput"));
    final String operation;
    @com.google.common.annotations.VisibleForTesting
    public final boolean action;
    private final String session, profile, world;
    private final boolean protection;
    private boolean menuOnly;
    private Integer expectedDimension;
    private boolean requireIdleInput;
    private volatile boolean actionStarted;

    private GuardedContext(String operation, boolean action, String session, String profile, String world, boolean protection) {
        this.operation = operation; this.action = action; this.session = session; this.profile = profile; this.world = world; this.protection = protection;
    }

    static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("unloadedMenuVersion", 1); result.addProperty("dimensionGuardVersion", 1);
        result.addProperty("idleInputGuardVersion", 1);
        JsonArray reads = new JsonArray(), actions = new JsonArray();
        for (String operation : READS) reads.add(new com.google.gson.JsonPrimitive(operation));
        for (String operation : ACTIONS) actions.add(new com.google.gson.JsonPrimitive(operation));
        result.add("reads", reads); result.add("actions", actions); return result;
    }

    public static GuardedContext parse(String operation, JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new BridgeException("invalid_request", "includeContext must be a versioned object.");
        JsonObject request = value.getAsJsonObject();
        for (java.util.Map.Entry<String, JsonElement> entry : request.entrySet())
            if (!KEYS.contains(entry.getKey())) throw new BridgeException("invalid_request", "Unknown includeContext field.");
        JsonElement version = request.get("version"), protectedValue = request.get("requireBackground");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.toString())
                || protectedValue == null || !protectedValue.isJsonPrimitive() || !protectedValue.getAsJsonPrimitive().isBoolean())
            throw new BridgeException("invalid_request", "includeContext requires version=1 and a boolean requireBackground.");
        boolean action = Arrays.asList(ACTIONS).contains(operation);
        if (!action && !Arrays.asList(READS).contains(operation))
            throw new BridgeException("unsupported", "Operation is outside the fixed context RPC whitelist.");
        String session = text(request, "expectedSessionId"), profile = text(request, "expectedProfile");
        if (!"developer".equals(profile) && !"player".equals(profile)) throw new BridgeException("invalid_request", "Unknown expectedProfile.");
        GuardedContext result = new GuardedContext(operation, action, session, profile, request.has("expectedWorld") ? text(request, "expectedWorld") : null, protectedValue.getAsBoolean());
        if (request.has("expectedDimension")) {
            if (result.world == null || request.has("menuOnly"))
                throw new BridgeException("invalid_request", "A dimension guard requires a loaded expectedWorld.");
            result.expectedDimension = com.Goderium.BridgeNH.util.JsonData.integer(request, "expectedDimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        if (request.has("requireIdleInput")) {
            if (!action || !protectedValue.getAsBoolean() || result.expectedDimension == null
                    || !bool(request, "requireIdleInput", true))
                throw new BridgeException("invalid_request", "Idle-input checks require a protected action in an expected dimension.");
            result.requireIdleInput = true;
        }
        if (request.has("menuOnly")) {
            JsonElement menu = request.get("menuOnly");
            if (!menu.isJsonPrimitive() || !menu.getAsJsonPrimitive().isBoolean() || !menu.getAsBoolean()
                    || request.has("expectedWorld") || !Arrays.asList("bridge.health", "gui.inspect", "gui.widgets", "gui.widget_action", "gui.key").contains(operation))
                throw new BridgeException("invalid_request", "menuOnly=true requires a fixed GUI operation without expectedWorld.");
            result.menuOnly = true;
        }
        return result;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty() || value.getAsString().length() > 128)
            throw new BridgeException("invalid_request", "Context identity must contain 1 through 128 characters: " + key);
        return value.getAsString();
    }

    public void check(JsonObject snapshot) {
        if (!string(snapshot, "sessionId", session)) throw new BridgeException("SESSION_CHANGED", "Client session changed; context operation refused.");
        if (!string(snapshot, "profile", profile)) throw new BridgeException("PROFILE_CHANGED", "Access profile changed; context operation refused.");
        if (menuOnly && (!bool(snapshot, "worldLoaded", false) || !bool(snapshot, "unloadedMenu", true)))
            throw new BridgeException("MENU_CONTEXT_CHANGED", "The client left its idle unloaded menu; subsequent GUI actions were blocked.");
        JsonObject current = object(snapshot, "world");
        JsonObject lifecycle = object(snapshot, "lifecycleTarget");
        if (expectedDimension != null && (current == null || !current.has("dimension") || !expectedDimension.toString().equals(current.get("dimension").toString())))
            throw new BridgeException("DIMENSION_CHANGED", "The expected dimension no longer matches the current client.");
        if (world != null && (!bool(snapshot, "worldLoaded", true) || current == null
                || (!string(current, "worldName", world) && !string(current, "worldFolder", world) && (lifecycle == null || !string(lifecycle, "expectedWorld", world)))))
            throw new BridgeException("WORLD_CHANGED", "The expected loaded world does not match the current client.");
        if (requireIdleInput && !actionStarted) {
            JsonObject task = object(snapshot, "inputTask");
            if (task == null || !string(task, "status", "idle"))
                throw new BridgeException("INPUT_TASK_ACTIVE", "Another native input task owns the player controls.");
        }
        JsonObject background = object(snapshot, "background");
        if (protection && (background == null || !bool(background, "enabled", true) || !bool(background, "helperInstalled", true)
                || !bool(background, "pauseOnLostFocus", false)))
            throw new BridgeException("TAKEOVER_LOST", "Background protection was released; context operation refused.");
    }

    @com.google.common.annotations.VisibleForTesting
    public Result execute(Callable<JsonObject> snapshot, Callable<JsonObject> operation) throws Exception {
        if (action) {
            JsonObject before = snapshot.call(); check(before); actionStarted = true;
            return result(operation.call(), before);
        }
        JsonObject data = operation.call(), after = snapshot.call(); check(after); return result(data, after);
    }

    public Result result(JsonObject data, JsonObject snapshot) {
        JsonObject context = new JsonObject(); context.addProperty("version", 1); context.addProperty("operation", operation);
        context.addProperty("phase", action ? "before" : "after"); context.add("snapshot", snapshot);
        return new Result(data, context);
    }

    public void requirePlayerInventoryRead() {
        if (action || !"world.inspect_many".equals(operation) || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "Player inventory observation requires the existing developer AFTER guard with expectedWorld and requireBackground=true.");
    }
    @com.google.common.annotations.VisibleForTesting
    public void requireAeMaterialSourceRead() {
        if (action || !AeSurvivalSource.INSPECT_OPERATION.equals(operation) || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "AE source inspection requires its fixed developer AFTER guard, expectedWorld and requireBackground=true.");
    }

    void requireWholeStackTransfer() {
        if (!action || !WholeStackTransfer.OPERATION.equals(operation) || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "Whole-stack transfer requires its fixed developer action BEFORE guard, expectedWorld and full background protection.");
    }
    public void wholeStackMutationStarting(JsonObject before) { requireWholeStackTransfer(); check(before); actionStarted = true; }

    public boolean outcomeUnknown() { return action && actionStarted; }

    @com.google.common.annotations.VisibleForTesting
    public void requireOwnedReplacement() {
        if (!action || !OwnedBlockReplacement.OPERATION.equals(operation) || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "Owned replacement requires the developer action guard, expectedWorld and full background protection.");
    }

    @com.google.common.annotations.VisibleForTesting
    public GuardedContext replacementRead(String operation) {
        requireOwnedReplacement();
        if (!"world.inspect_many".equals(operation) && !"player.plan_approach".equals(operation))
            throw new BridgeException("invalid_request", "Owned replacement has only fixed observation and approach child guards.");
        return new GuardedContext(operation, false, session, profile, world, protection);
    }

    @com.google.common.annotations.VisibleForTesting
    public void replacementMutationStarting(JsonObject before) {
        requireOwnedReplacement(); check(before); actionStarted = true;
    }
    public void requireSurvivalOperation() {
        if (!action || !(SurvivalConstructionAdapter.OPERATION.equals(operation) || "player.place_block".equals(operation))
                || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "Survival construction/confirmation requires the developer action guard, expectedWorld and full background protection.");
    }
    public void survivalMutationStarting(JsonObject before) {
        requireSurvivalOperation(); check(before); actionStarted = true;
    }
    void requireNavigation() {
        if (!action || !PlayerNavigation.OPERATION.equals(operation) || !"developer".equals(profile) || world == null || !protection)
            throw new BridgeException("invalid_request", "Navigation requires its developer action guard, expectedWorld and background protection.");
    }
    void navigationMutationStarting(JsonObject before) { requireNavigation(); check(before); actionStarted = true; }
    void questStarting(JsonObject before) {
        if (!action || !("quest.step".equals(operation) || com.Goderium.BridgeNH.common.survival.SurvivalStorage.write(operation)) || world==null || !protection)
            throw new BridgeException("invalid_request","Quest steps require a protected world BEFORE guard.");
        check(before); actionStarted=true;
    }
    void foodStarting(JsonObject before) {
        if (!action || !"survival.food".equals(operation)) throw new BridgeException("invalid_request", "Expected a food action guard.");
        check(before); actionStarted = true;
    }
    private static JsonObject object(JsonObject value, String key) { JsonElement found = value.get(key); return found != null && found.isJsonObject() ? found.getAsJsonObject() : null; }
    private static boolean string(JsonObject value, String key, String expected) { JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isString() && expected.equals(found.getAsString()); }
    private static boolean bool(JsonObject value, String key, boolean expected) { JsonElement found = value.get(key); return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isBoolean() && found.getAsBoolean() == expected; }
    public static final class Result {
        public final JsonObject data, context;
        @com.google.common.annotations.VisibleForTesting
        public Result(JsonObject data, JsonObject context) { this.data = data; this.context = context; }
    }
}
