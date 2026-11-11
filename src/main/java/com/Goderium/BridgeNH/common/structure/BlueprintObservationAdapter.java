package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.lifecycle.WorldLifecycle;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;

/** Records native hints and reads position-bound public suggestions; never invokes construction or predicates. */
public final class BlueprintObservationAdapter {
    public static final int POSITION_LIMIT = 16384, GROUP_LIMIT = 128, BYTE_LIMIT = 128 * 1024;
    public static final int CAPTURE_BYTE_LIMIT = 4 * 1024 * 1024;
    private static final int POSITION_ENCODED_BYTES = 160; // Conservative UTF-8 JSON bound for seven signed ints and fixed keys.
    public static final long WORK_BUDGET_NANOS = 4000000L;
    // Immutable hints must survive the supported 60-second construction task.
    // This is retention only: owning-client identity validation remains <=1s,
    // and every server read still binds the actual owner, mode and facing.
    public static final long TTL_NANOS = 120000000000L;
    private static final long VALIDATION_LAG_NANOS = 1000000000L;
    static final int CANDIDATE_POSITION_LIMIT = 16;
    private final LongSupplier clock;
    private Instrument instrument;
    private final ThreadLocal<Collector> active = new ThreadLocal<Collector>();
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
    private volatile long epoch;

    public BlueprintObservationAdapter() { this(null, System::nanoTime); }
    BlueprintObservationAdapter(Instrument instrument, LongSupplier clock) { this.instrument = instrument; this.clock = clock; }

    /** Wrap exactly the original onPlayerRightClick call on the client thread. Ineligible/busy calls still run once. */
    public boolean runNative(Minecraft client, String sessionId, boolean developer, MovingObjectPosition target,
                             BooleanSupplier nativeUse) {
        Context context = null;
        try {
            if (!eligible(client, developer, target)) {
                if (developer) noteAttempt(client, target, "native_hint_capture_ineligible", null);
            } else if (!Loader.isModLoaded("structurelib")) {
                noteAttempt(client, target, "structurelib_not_loaded", null);
            } else {
                ItemStack held = client.thePlayer.getHeldItem();
                if (held == null || held.getItem() != StructureLibAPI.getDefaultHologramItem())
                    noteAttempt(client, target, "not_structurelib_trigger", null);
                else {
                    if (instrument == null) instrument = new SlInstrument(this);
                    context = context(client, sessionId, target, held);
                    if (context == null) noteAttempt(client, target, "client_controller_unavailable_or_trigger_oversized", null);
                }
            }
        } catch (RuntimeException | LinkageError error) { noteAttempt(client, target, "observer_preparation_failed", error); }
        return context == null ? nativeUse.getAsBoolean() : execute(context, nativeUse);
    }

    static boolean eligible(Minecraft client, boolean developer, MovingObjectPosition target) {
        return developer && client != null && client.thePlayer != null && client.theWorld != null
                && client.currentScreen == null && WorldLifecycle.integrated(client) && !client.thePlayer.isSneaking()
                && target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && client.theWorld.blockExists(target.blockX, target.blockY, target.blockZ);
    }

    /** Called only from the client tick. Server readers never dereference these client-world bindings. */
    public synchronized void validateClient(Minecraft client, String sessionId, boolean developer) {
        long now = clock.getAsLong();
        if (!developer || client == null || client.thePlayer == null || client.theWorld == null
                || !WorldLifecycle.integrated(client)) {
            invalidate();
            return;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            Context scope = entry.scope;
            try {
                String reason = now - entry.capturedAt > TTL_NANOS ? "native_observation_expired"
                        : !scope.session.equals(sessionId) ? "client_session_changed"
                        : !scope.player.equals(client.thePlayer.getUniqueID().toString()) ? "client_player_changed"
                        : scope.world.get() != client.theWorld ? "client_world_changed" : null;
                Object tile = reason == null ? loadedTile(client.theWorld, scope.x, scope.y, scope.z) : null;
                Object meta = tile == null ? null : meta(tile);
                if (reason == null && (tile == null || tile != scope.tile.get())) reason = "client_controller_identity_changed_or_unloaded";
                if (reason == null && meta != scope.meta.get()) reason = "client_meta_identity_changed";
                if (reason == null && optional(meta, "getStructureDefinition") != scope.definition.get()) reason = "client_definition_identity_changed";
                String currentFacing = facing(optional(meta, "getExtendedFacing"));
                Integer currentMode = mode(meta);
                if (reason == null && !scope.facing.equals(currentFacing)) reason = "client_facing_changed";
                if (reason == null && !Objects.equals(scope.mode, currentMode)) reason = "client_mode_changed";
                // Switching away from the tool to place hatches does not invalidate its observed plan.
                ItemStack held = client.thePlayer.getHeldItem();
                if (reason == null && held != null && scope.triggerItem == held.getItem()
                        && !scope.triggerSignature.equals(triggerSignature(held))) reason = "client_trigger_changed";
                if (reason != null) { note(scope, reason, currentFacing, currentMode, null); entries.remove(i); }
                else entry.validatedAt = now;
            } catch (RuntimeException | LinkageError error) { note(scope, "client_validation_failed", null, null, error); entries.remove(i); }
        }
    }

    public synchronized void invalidate() { epoch++; entries.clear(); diagnostics.clear(); }

    /** Client owning thread, after the normal server owner/capture read. No captured event or supplier is retained. */
    public JsonObject candidates(Minecraft client, String session, JsonObject args, JsonObject serverResult) {
        if (!args.has("candidatePositions") || !args.get("candidatePositions").isJsonArray())
            throw new BridgeException("invalid_args", "candidatePositions must be an explicit bounded XYZ/group array.");
        for (String key : new String[] {"registryName", "metadata", "piece", "offsetA", "offsetB", "offsetC", "variant", "offset", "query", "boundsOnly", "runtimeOffset", "floorBlockY"})
            if (args.has(key)) throw new BridgeException("invalid_args", "Position candidates cannot mix definition, prototype, query or runtime selectors.");
        String capture = CatalogData.text(args, "captureId", 128, "");
        if (capture.isEmpty()) throw new BridgeException("invalid_args", "Position candidates require the exact current captureId.");
        validateClient(client, session, true);
        int x = JsonData.integer(args, "x", -30000000, 30000000), y = JsonData.integer(args, "y", 0, 255), z = JsonData.integer(args, "z", -30000000, 30000000);
        Entry found = null;
        synchronized (this) { for (Entry entry : entries) if (capture.equals(entry.captureId)) { found = entry; break; } }
        if (found == null || found.scope.x != x || found.scope.y != y || found.scope.z != z || found.scope.trigger == null)
            throw new BridgeException("stale_capture", "The exact client controller/trigger capture is unavailable.");
        Context current = context(client, session, new MovingObjectPosition(x, y, z, 1, net.minecraft.util.Vec3.createVectorHelper(x, y, z)), found.scope.trigger);
        if (current == null) throw new BridgeException("stale_capture", "The captured public client owner/definition is unavailable.");
        JsonObject runtime = serverResult.has("runtimeObservation") && serverResult.get("runtimeObservation").isJsonObject()
                ? serverResult.getAsJsonObject("runtimeObservation") : new JsonObject();
        JsonObject result = candidatePage(current, runtime, args.getAsJsonArray("candidatePositions"),
                args.has("candidateOffset") ? JsonData.integer(args, "candidateOffset", 0, CANDIDATE_POSITION_LIMIT) : 0,
                (element, scope, point) -> {
                    World world = (World) scope.world.get();
                    if (world == null || !world.blockExists(point.x, point.y, point.z) || !world.getChunkProvider().chunkExists(point.x >> 4, point.z >> 4))
                        return unavailable("candidate_position_unloaded");
                    JsonObject value = detail((IStructureElement<?>) element, scope, world, point.x, point.y, point.z);
                    value.addProperty("loaded", true); return value;
                });
        validateClient(client, session, true);
        Context ending = context(client, session, new MovingObjectPosition(x, y, z, 1, net.minecraft.util.Vec3.createVectorHelper(x, y, z)), found.scope.trigger);
        synchronized (this) {
            if (!entries.contains(found) || ending == null || !sameContext(found.scope, ending) || clock.getAsLong() - found.capturedAt > TTL_NANOS)
                throw new BridgeException("stale_capture", "The actual client owner, trigger or capture changed during its public position getters.");
            result.addProperty("ageMillis", Math.max(0, clock.getAsLong() - found.capturedAt) / 1000000L);
        }
        return result;
    }

    interface CandidateGetter { JsonObject read(Object element, Context scope, CapturedPosition point); }

    synchronized JsonObject candidatePage(Context current, JsonObject runtime, JsonArray requested, int offset, CandidateGetter getter) {
        if (requested.size() < 1 || requested.size() > CANDIDATE_POSITION_LIMIT || offset < 0 || offset >= requested.size())
            throw new BridgeException("invalid_args", "Position candidate requests need 1..16 exact coordinates and an in-range continuation.");
        String capture = runtime.has("captureId") ? runtime.get("captureId").getAsString() : "";
        Entry found = null; for (Entry entry : entries) if (capture.equals(entry.captureId)) { found = entry; break; }
        long started = clock.getAsLong();
        if (found == null || started - found.capturedAt > TTL_NANOS || started - found.validatedAt > VALIDATION_LAG_NANOS
                || !sameContext(found.scope, current) || !flag(runtime, "contextMatches") || !flag(runtime, "modeMatches")
                || !flag(runtime, "facingMatches") || !flag(runtime, "controllerIdentityMatches")
                || !flag(runtime, "capturedVisitsComplete") || !runtime.has("eventCaptureTruncated") || flag(runtime, "eventCaptureTruncated"))
            throw new BridgeException("stale_capture", "Position suggestions require the same complete current client/server owner, trigger, mode, facing, session and capture.");
        List<CapturedPosition> points = new ArrayList<CapturedPosition>();
        java.util.HashSet<String> unique = new java.util.HashSet<String>();
        for (com.google.gson.JsonElement request : requested) {
            if (!request.isJsonObject()) throw new BridgeException("invalid_args", "Every candidate position needs exact integer XYZ/group.");
            JsonObject row = request.getAsJsonObject();
            int x = JsonData.integer(row, "x", -30000000, 30000000), y = JsonData.integer(row, "y", 0, 255), z = JsonData.integer(row, "z", -30000000, 30000000), group = JsonData.integer(row, "group", 0, GROUP_LIMIT - 1);
            CapturedPosition selected = null;
            for (CapturedPosition point : found.positions) if (point.x == x && point.y == y && point.z == z && point.group == group) {
                if (selected != null) throw new BridgeException("unsupported", "Duplicate native XYZ/group visits are ambiguous for position suggestions."); selected = point;
            }
            if (selected == null || !unique.add(x + ":" + y + ":" + z))
                throw new BridgeException("invalid_args", "Every unique XYZ/group must belong to this exact complete native capture.");
            points.add(selected);
        }
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray(); result.add("positions", rows);
        result.addProperty("version", 1); result.addProperty("source", "client_public_structure_element_getter");
        result.addProperty("captureId", capture); result.addProperty("sessionId", current.session); result.addProperty("playerId", current.player);
        result.addProperty("dimension", current.dimension); result.addProperty("x", current.x); result.addProperty("y", current.y); result.addProperty("z", current.z);
        result.addProperty("capturedFacing", current.facing); nullableMode(result, "capturedMode", current.mode); result.add("trigger", parse(current.triggerJson));
        result.addProperty("ownerBound", true); result.addProperty("captureMembershipVerified", true); result.addProperty("readOnly", true);
        result.addProperty("fullStructureChecked", false); result.addProperty("completeRulesKnown", false); result.addProperty("formationVerified", false);
        result.addProperty("requestedCount", requested.size()); result.addProperty("candidateOffset", offset);
        result.addProperty("ttlMillis", TTL_NANOS / 1000000L);
        result.addProperty("ageMillis", Math.max(0, started - found.capturedAt) / 1000000L);
        int next = offset; boolean bytes = false;
        while (next < points.size() && (next == offset || clock.getAsLong() - started < WORK_BUDGET_NANOS)) {
            CapturedPosition point = points.get(next); WeakReference<Object> reference = found.elements.get(point.group);
            Object element = reference == null ? null : reference.get();
            JsonObject row = new JsonObject(); row.addProperty("x", point.x); row.addProperty("y", point.y); row.addProperty("z", point.z); row.addProperty("group", point.group); row.addProperty("requestIndex", next);
            boolean bound = element != null && current.elementGroups.containsKey(element);
            JsonObject detail = unavailable("public_definition_element_unavailable");
            if (bound) try { detail = parse(getter.read(element, current, point).toString()); }
            catch (RuntimeException | LinkageError error) { detail = unavailable("public_candidate_getter_failed"); }
            boolean complete = bound && flag(detail, "loaded") && detail.has("candidateCoverage") && "public_stack_suggestions".equals(detail.get("candidateCoverage").getAsString())
                    && !flag(detail, "candidatesTruncated") && detail.has("suggestedCandidates") && detail.get("suggestedCandidates").isJsonArray();
            row.addProperty("publicElementBound", bound); row.addProperty("complete", complete); row.addProperty("status", complete ? "known_suggestions" : "unknown");
            row.add("detail", detail); row.addProperty("candidateCount", detail.has("suggestedCandidates") && detail.get("suggestedCandidates").isJsonArray() ? detail.getAsJsonArray("suggestedCandidates").size() : 0);
            int size = bytes(result) + bytes(row) + 2048;
            if (size > BYTE_LIMIT) { bytes = true; break; }
            rows.add(row); next++;
        }
        result.addProperty("nextCandidateOffset", next); result.addProperty("processedCount", next - offset);
        long finished = clock.getAsLong();
        if (finished - found.capturedAt > TTL_NANOS || !entries.contains(found))
            throw new BridgeException("stale_capture", "The exact hint capture expired or was invalidated during its bounded public position getters.");
        result.addProperty("ageMillis", Math.max(0, finished - found.capturedAt) / 1000000L);
        result.addProperty("complete", next == requested.size()); result.addProperty("partial", next != requested.size());
        result.addProperty("byteLimited", bytes); result.addProperty("budgetLimited", next < requested.size() && !bytes);
        result.addProperty("scope", "Fresh getBlocksToPlace at each exact captured XYZ with its original trigger. Known zero candidates differs from unknown/predicate-only/truncated data. Public suggestions do not establish full acceptance or formation.");
        return result;
    }

    private static boolean flag(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isBoolean() && value.get(key).getAsBoolean(); }
    private static boolean sameContext(Context left, Context right) {
        return left.world.get() != null && left.world.get() == right.world.get() && left.tile.get() == right.tile.get() && left.meta.get() == right.meta.get()
                && left.definition.get() == right.definition.get() && left.dimension == right.dimension && left.x == right.x && left.y == right.y && left.z == right.z
                && left.session.equals(right.session) && left.player.equals(right.player) && left.facing.equals(right.facing)
                && Objects.equals(left.mode, right.mode) && left.triggerSignature.equals(right.triggerSignature) && left.triggerJson.equals(right.triggerJson);
    }

    /** Attach only immutable previously copied data, bound to the current authoritative target's public identity. */
    JsonObject describe(World world, Object tile, Object meta, Object definition, Object alignment,
                        int x, int y, int z, String query, String captureId, int runtimeOffset,
                        Integer floorBlockY, int limit, int byteBudget) {
        return page(world.provider.dimensionId, tile, meta, definition, facing(alignment), mode(meta), x, y, z,
                query, captureId, runtimeOffset, floorBlockY, limit, byteBudget);
    }

    synchronized JsonObject page(int dimension, Object tile, Object meta, Object definition, String alignment,
                                 Integer mode, int x, int y, int z, String query, int limit, int byteBudget) {
        return page(dimension, tile, meta, definition, alignment, mode, x, y, z, query, null, 0, null, limit, byteBudget);
    }

    synchronized JsonObject page(int dimension, Object tile, Object meta, Object definition, String alignment,
                                 Integer mode, int x, int y, int z, String query, String captureId, int runtimeOffset,
                                 Integer floorBlockY, int limit, int byteBudget) {
        long now = clock.getAsLong();
        if (runtimeOffset < 0 || runtimeOffset > POSITION_LIMIT || limit < 1 || limit > 128)
            throw new BridgeException("invalid_args", "runtimeOffset must be 0.." + POSITION_LIMIT + " and limit must be 1..128.");
        if (captureId != null && (captureId.isEmpty() || captureId.length() > 128))
            throw new BridgeException("invalid_args", "captureId length must be 1..128.");
        if (runtimeOffset > 0 && captureId == null)
            throw new BridgeException("invalid_args", "A nonzero runtimeOffset requires the current captureId.");
        if (floorBlockY != null && (floorBlockY < 0 || floorBlockY > 254))
            throw new BridgeException("invalid_args", "floorBlockY must be 0..254.");
        Entry found = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            Context scope = entry.scope;
            if (now - entry.capturedAt > TTL_NANOS) { note(scope, "native_observation_expired", null, null, null); entries.remove(i); continue; }
            if (scope.dimension != dimension || scope.x != x || scope.y != y || scope.z != z) continue;
            if (now - entry.validatedAt > VALIDATION_LAG_NANOS) { note(scope, "client_validation_not_recent", alignment, mode, null); continue; }
            String reason = !scope.metaClass.equals(type(meta)) ? "server_meta_class_mismatch"
                    : !scope.definitionClass.equals(type(definition)) ? "server_definition_class_mismatch"
                    : !scope.facing.equals(alignment) ? "server_facing_mismatch" : null;
            if (reason != null) { note(scope, reason, alignment, mode, null); entries.remove(i); continue; }
            if (!entry.bindServer(tile, meta, definition)) { note(scope, "server_controller_identity_changed", alignment, mode, null); entries.remove(i); continue; }
            found = entry;
            break;
        }
        if (captureId != null && (found == null || !captureId.equals(found.captureId)))
            throw new BridgeException("stale_capture", "This native hint capture expired, was replaced or no longer matches the current controller context. Restart at runtimeOffset 0 without captureId.");
        if (found == null) return unavailableAt(dimension, x, y, z, byteBudget, now);
        if (byteBudget < 2048) return unavailable("structure_page_byte_budget");
        JsonObject stored = parse(found.payload);
        List<GroupSnapshot> allGroups = found.groups;
        List<CapturedPosition> allPositions = found.positions;
        if (runtimeOffset > allPositions.size())
            throw new BridgeException("invalid_args", "runtimeOffset exceeds the stored positions in this capture.");
        String filter = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonObject result = stored;
        JsonArray positions = new JsonArray(), groups = new JsonArray();
        result.add("positions", positions); result.add("groups", groups);
        result.addProperty("ageMillis", Math.max(0, now - found.capturedAt) / 1000000L);
        result.addProperty("clientValidationAgeMillis", Math.max(0, now - found.validatedAt) / 1000000L);
        nullableMode(result, "capturedMode", found.scope.mode); nullableMode(result, "serverMode", mode);
        result.addProperty("capturedFacing", found.scope.facing); result.addProperty("serverFacing", alignment);
        result.addProperty("controllerIdentityMatches", true); result.addProperty("facingMatches", true);
        boolean modeMatches = Objects.equals(found.scope.mode, mode);
        result.addProperty("modeMatches", modeMatches); result.addProperty("contextMatches", modeMatches);
        result.addProperty("evidenceOnly", true);
        if (!modeMatches) result.addProperty("contextWarning", "client_server_mode_disagreement; copied native client hints only, not a server structure plan");
        result.add("planning", planning(result.getAsJsonObject("observedBounds"), modeMatches, y, floorBlockY));
        result.addProperty("runtimeOffset", runtimeOffset);
        result.addProperty("nextRuntimeOffset", runtimeOffset);
        result.addProperty("hasMore", runtimeOffset < allPositions.size());
        Map<Integer, Boolean> included = new LinkedHashMap<Integer, Boolean>();
        int usedBytes = bytes(result) + 2048; // Reserve page counters/budget/policy and the bounded UTF-8 query.
        if (usedBytes > Math.min(BYTE_LIMIT, byteBudget)) return unavailable("structure_page_byte_budget");
        boolean[] matchingGroups = new boolean[allGroups.size()];
        int matched = 0;
        for (int i = 0; i < allGroups.size(); i++) {
            GroupSnapshot group = allGroups.get(i);
            matchingGroups[i] = filter.isEmpty() || group.payload.toLowerCase(Locale.ROOT).contains(filter);
            if (matchingGroups[i]) matched += group.capturedVisits;
        }
        int next = runtimeOffset, visited = 0;
        boolean byteLimited = false;
        while (next < allPositions.size() && positions.size() < limit) {
            if (visited > 0 && clock.getAsLong() - now >= WORK_BUDGET_NANOS) break;
            CapturedPosition captured = allPositions.get(next);
            int groupId = captured.group;
            if (!matchingGroups[groupId]) { next++; visited++; continue; }
            JsonObject position = captured.json();
            boolean addedGroup = !included.containsKey(groupId);
            GroupSnapshot group = allGroups.get(groupId);
            int addedBytes = bytes(position) + 1 + (addedGroup ? group.encodedBytes + 1 : 0);
            if (usedBytes + addedBytes > Math.min(BYTE_LIMIT, byteBudget)) { byteLimited = true; break; }
            if (addedGroup) groups.add(parse(group.payload));
            positions.add(position);
            usedBytes += addedBytes;
            included.put(groupId, Boolean.TRUE);
            next++; visited++;
        }
        result.addProperty("matchingCapturedPositions", matched);
        result.addProperty("nextRuntimeOffset", next);
        result.addProperty("hasMore", next < allPositions.size());
        result.addProperty("runtimePageVisited", visited);
        result.addProperty("pageLimited", next < allPositions.size());
        result.addProperty("pageByteLimited", byteLimited);
        long elapsed = Math.max(0, clock.getAsLong() - now);
        result.addProperty("pageSoftBudgetPartial", next < allPositions.size() && elapsed >= WORK_BUDGET_NANOS);
        JsonObject budget = new JsonObject(); budget.addProperty("softBudgetMicros", WORK_BUDGET_NANOS / 1000L);
        budget.addProperty("actualElapsedMicros", elapsed / 1000L); budget.addProperty("softBudgetExceeded", elapsed > WORK_BUDGET_NANOS);
        budget.addProperty("policy", "Immutable capture metadata/group filtering and this page's integer-coordinate serialization only; earlier coordinate pages are never reparsed. At least one raw position is visited unless the response byte budget blocks it. No public machine getters, construction or checks.");
        result.add("pageBudget", budget);
        result.addProperty("query", JsonData.bounded(filter, 128));
        result.addProperty("pagePolicy", "runtimeOffset/nextRuntimeOffset index the immutable unfiltered captured positions; continue with the same captureId. Query filters returned coordinates only; matching counts and observedBounds cover this capture independently of pages. No automatic continuation.");
        return result;
    }

    private static JsonObject planning(JsonObject bounds, boolean contextMatches, int controllerY, Integer floorBlockY) {
        JsonObject result = new JsonObject(); result.addProperty("available", false);
        result.addProperty("scope", "Only XYZ visits from this one native client hint invocation; no complete machine envelope, required-air, hidden chamber rules or server formation guarantee.");
        result.addProperty("completeStructure", false); result.addProperty("fullStructureChecked", false);
        result.addProperty("worldMinY", 0); result.addProperty("worldMaxY", 255);
        result.addProperty("contextMatches", contextMatches);
        result.addProperty("observedVisitsComplete", bounds.get("complete").getAsBoolean());
        if (floorBlockY != null) result.addProperty("floorBlockY", floorBlockY);
        if (!bounds.get("available").getAsBoolean()) { result.addProperty("reason", "no_observed_bounds"); return result; }
        JsonObject box = bounds.getAsJsonObject("worldXYZ");
        long minimumY = box.getAsJsonObject("min").get("y").getAsLong();
        long maximumY = box.getAsJsonObject("max").get("y").getAsLong(), relativeY = minimumY - controllerY;
        boolean heightConflict = minimumY < 0L || maximumY > 255L;
        result.addProperty("minimumObservedY", minimumY); result.addProperty("maximumObservedY", maximumY);
        result.addProperty("controllerRelativeMinY", relativeY);
        result.addProperty("worldHeightConflict", heightConflict); result.addProperty("currentBoundsFitWorld", !heightConflict);
        result.add("dimensionsFromObservedBounds", box.getAsJsonObject("size"));
        if (floorBlockY != null) result.addProperty("floorConflict", minimumY <= floorBlockY);
        if (!contextMatches) { result.addProperty("reason", "client_server_context_mismatch"); return result; }
        if (!bounds.get("complete").getAsBoolean()) { result.addProperty("reason", "observed_bounds_partial"); return result; }
        if (floorBlockY == null) { result.addProperty("reason", "floor_not_requested"); return result; }
        long minimumControllerY = floorBlockY + 1L - relativeY, raise = Math.max(0L, minimumControllerY - controllerY);
        result.addProperty("minimumControllerY", minimumControllerY); result.addProperty("raiseBlocks", raise);
        result.addProperty("raisedMinimumObservedY", minimumY + raise); result.addProperty("raisedMaximumObservedY", maximumY + raise);
        boolean raisedFits = minimumY + raise >= 0L && maximumY + raise <= 255L && controllerY + raise <= 255L;
        result.addProperty("raisedBoundsFitWorld", raisedFits);
        if (!raisedFits) {
            result.addProperty("reason", "world_height_unsupported"); result.addProperty("code", "unsupported"); return result;
        }
        result.addProperty("available", true); return result;
    }

    private static JsonObject unavailable(String reason) {
        JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("reason", reason);
        result.addProperty("source", "client_native_blueprint_hints"); return result;
    }

    private synchronized JsonObject unavailableAt(int dimension, int x, int y, int z, int byteBudget, long now) {
        for (int i = diagnostics.size() - 1; i >= 0; i--) {
            Diagnostic diagnostic = diagnostics.get(i);
            if (now - diagnostic.recordedAt > TTL_NANOS) { diagnostics.remove(i); continue; }
            if (diagnostic.dimension != dimension || diagnostic.x != x || diagnostic.y != y || diagnostic.z != z) continue;
            JsonObject details = parse(diagnostic.payload), result = unavailable(details.get("reason").getAsString());
            details.addProperty("ageMillis", Math.max(0, now - diagnostic.recordedAt) / 1000000L);
            result.add("diagnostic", details);
            return bytes(result) <= byteBudget ? result : unavailable("structure_page_byte_budget");
        }
        return unavailable("no_current_native_hint_observation");
    }

    private void noteAttempt(Minecraft client, MovingObjectPosition target, String reason, Throwable error) {
        try {
            if (client == null || client.theWorld == null || target == null
                    || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
            JsonObject details = new JsonObject(); details.addProperty("stage", "native_capture_preparation"); addError(details, error);
            note(client.theWorld.provider.dimensionId, target.blockX, target.blockY, target.blockZ, reason, details);
        } catch (RuntimeException | LinkageError ignored) { /* Diagnostics must never change native use. */ }
    }

    private void note(Context scope, String reason, String currentFacing, Integer currentMode, Throwable error) {
        try {
            JsonObject details = new JsonObject(); details.addProperty("stage", "native_observation_binding");
            details.addProperty("capturedMetaClass", JsonData.bounded(scope.metaClass, 256));
            details.addProperty("capturedDefinitionClass", JsonData.bounded(scope.definitionClass, 256));
            details.addProperty("capturedFacing", JsonData.bounded(scope.facing, 128)); nullableMode(details, "capturedMode", scope.mode);
            if (currentFacing != null) details.addProperty("currentFacing", JsonData.bounded(currentFacing, 128));
            nullableMode(details, "currentMode", currentMode); addError(details, error);
            note(scope.dimension, scope.x, scope.y, scope.z, reason, details);
        } catch (RuntimeException | LinkageError ignored) { /* Best-effort bounded diagnostics only. */ }
    }

    private synchronized void note(int dimension, int x, int y, int z, String reason, JsonObject details) {
        details.addProperty("reason", reason); details.addProperty("dimension", dimension);
        details.addProperty("x", x); details.addProperty("y", y); details.addProperty("z", z);
        for (int i = diagnostics.size() - 1; i >= 0; i--) {
            Diagnostic old = diagnostics.get(i);
            if (old.dimension == dimension && old.x == x && old.y == y && old.z == z) diagnostics.remove(i);
        }
        diagnostics.add(new Diagnostic(dimension, x, y, z, clock.getAsLong(), details.toString()));
        while (diagnostics.size() > 4) diagnostics.remove(0);
    }

    private static void nullableMode(JsonObject result, String key, Integer mode) {
        if (mode == null) result.add(key, JsonNull.INSTANCE); else result.addProperty(key, mode);
    }

    private static void addError(JsonObject details, Throwable error) {
        if (error == null) return;
        details.addProperty("errorClass", JsonData.bounded(error.getClass().getName(), 256));
        details.addProperty("error", JsonData.bounded(error.getMessage(), 256));
    }

    boolean execute(Context context, BooleanSupplier nativeUse) {
        Collector collector = null;
        try {
            if (instrument.enabled()) note(context, "instrumentation_busy", null, null, null);
            else collector = new Collector(context, clock.getAsLong(), epoch);
        } catch (RuntimeException | LinkageError error) { note(context, "instrument_preparation_failed", null, null, error); }
        if (collector == null) return nativeUse.getAsBoolean();
        try { instrument.enable(collector.identifier); }
        catch (RuntimeException | LinkageError error) { note(context, "instrument_enable_failed", null, null, error); return nativeUse.getAsBoolean(); }
        active.set(collector);
        boolean completed = false;
        try {
            boolean result = nativeUse.getAsBoolean();
            completed = true;
            return result;
        } finally {
            active.remove();
            try { if (instrument.enabled()) instrument.disable(); }
            catch (RuntimeException | LinkageError error) { collector.cleanupFailed = true; }
            try { publish(collector, completed); }
            catch (RuntimeException | LinkageError error) { note(context, "capture_publication_failed", null, null, error); }
        }
    }

    /** The listener copies every value during dispatch; event references are never kept. */
    void observe(Object identifier, Object world, int x, int y, int z, int a, int b, int c,
                 String elementClass, String knownGroup, Supplier<JsonObject> details) {
        Collector collector = active.get();
        if (collector == null || !collector.identifier.equals(identifier) || collector.context.world.get() != world) return;
        collector.visits++;
        if (collector.budgetLimited) return;
        long started = clock.getAsLong();
        try {
            collector.observeBounds(x, y, z);
            if (!collector.stopped) collector.add(x, y, z, a, b, c, elementClass, knownGroup, details);
        }
        catch (RuntimeException | LinkageError error) { collector.detailErrors++; collector.stopped = true; }
        finally {
            collector.workNanos += Math.max(0, clock.getAsLong() - started);
            if (collector.workNanos >= WORK_BUDGET_NANOS) { collector.budgetLimited = true; collector.stopped = true; }
        }
    }

    private synchronized void publish(Collector collector, boolean completed) {
        if (collector.epoch != epoch) return;
        JsonObject result = collector.finish(completed);
        String payload = result.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > BYTE_LIMIT) { note(collector.context, "capture_payload_byte_limit", null, null, null); return; }
        long now = clock.getAsLong();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Context old = entries.get(i).scope, next = collector.context;
            if (old.world.get() == next.world.get() && old.dimension == next.dimension && old.x == next.x && old.y == next.y && old.z == next.z)
                entries.remove(i);
        }
        entries.add(new Entry(collector.context.withoutElementReferences(), payload, now, String.valueOf(collector.identifier),
                collector.positions, collector.groups, collector.elements));
        while (entries.size() > 4) entries.remove(0);
    }

    private final class Collector {
        final Object identifier = UUID.randomUUID().toString();
        final Context context; final long startedAt, epoch;
        final List<CapturedPosition> positions = new ArrayList<CapturedPosition>();
        final JsonArray groups = new JsonArray();
        final Map<String, Integer> groupIds = new LinkedHashMap<String, Integer>();
        final Map<Integer, WeakReference<Object>> elements = new LinkedHashMap<Integer, WeakReference<Object>>();
        final long[] boundsMin = new long[3], boundsMax = new long[3];
        long visits, boundsVisits, workNanos; int storedBytes, detailErrors;
        boolean stopped, positionLimited, groupLimited, byteLimited, budgetLimited, cleanupFailed;
        Collector(Context context, long startedAt, long epoch) {
            this.context = context; this.startedAt = startedAt; this.epoch = epoch;
            storedBytes = bytes(finish(false)) + 1024; // Reserve counter growth after measuring the actual trigger/metadata.
        }
        void observeBounds(int x, int y, int z) {
            boundsMin[0] = boundsVisits == 0 ? x : Math.min(boundsMin[0], x);
            boundsMin[1] = boundsVisits == 0 ? y : Math.min(boundsMin[1], y);
            boundsMin[2] = boundsVisits == 0 ? z : Math.min(boundsMin[2], z);
            boundsMax[0] = boundsVisits == 0 ? x : Math.max(boundsMax[0], x);
            boundsMax[1] = boundsVisits == 0 ? y : Math.max(boundsMax[1], y);
            boundsMax[2] = boundsVisits == 0 ? z : Math.max(boundsMax[2], z);
            boundsVisits++;
        }
        void add(int x, int y, int z, int a, int b, int c, String elementClass, String knownGroup, Supplier<JsonObject> details) {
            if (positions.size() >= POSITION_LIMIT) { positionLimited = stopped = true; return; }
            String key = knownGroup == null ? "unknown:" + elementClass : knownGroup;
            Integer id = groupIds.get(key);
            JsonObject newGroup = null;
            if (id == null) {
                if (groups.size() >= GROUP_LIMIT) { groupLimited = stopped = true; return; }
                id = groups.size();
                try { newGroup = details.get(); }
                catch (RuntimeException | LinkageError error) { newGroup = new JsonObject(); newGroup.addProperty("descriptionCoverage", "public_getter_failed"); detailErrors++; }
                newGroup = parse(newGroup.toString());
                newGroup.addProperty("id", id); newGroup.addProperty("elementClass", JsonData.bounded(elementClass, 256));
                newGroup.addProperty("identityCoverage", knownGroup == null ? "class_only_unknown_element" : "public_definition_element");
                newGroup.add("elementKey", JsonNull.INSTANCE);
                newGroup.addProperty("elementKeyCoverage", "unavailable_cross_side_mapping_not_guaranteed");
                newGroup.addProperty("capturedVisits", 0);
                JsonObject representative = new JsonObject(); representative.addProperty("x", x); representative.addProperty("y", y); representative.addProperty("z", z);
                newGroup.add("representativePosition", representative);
                newGroup.addProperty("candidateScope", "Suggestions at the first visited position only; rules may vary at other positions of this element.");
                newGroup.add("roleSelection", roleSelection(newGroup, knownGroup != null, String.valueOf(identifier), id));
            }
            int added = POSITION_ENCODED_BYTES + (newGroup == null ? 0 : bytes(newGroup) + 64);
            if (storedBytes + added > CAPTURE_BYTE_LIMIT) { byteLimited = stopped = true; return; }
            if (newGroup != null) {
                groupIds.put(key, id); groups.add(newGroup);
                if (knownGroup != null) for (Map.Entry<Object, String> element : context.elementGroups.entrySet())
                    if (knownGroup.equals(element.getValue())) { elements.put(id, new WeakReference<Object>(element.getKey())); break; }
            }
            JsonObject group = groups.get(id).getAsJsonObject();
            group.addProperty("capturedVisits", group.get("capturedVisits").getAsLong() + 1);
            positions.add(new CapturedPosition(x, y, z, a, b, c, id)); storedBytes += added;
        }
        JsonObject finish(boolean completed) {
            JsonObject result = new JsonObject(); result.addProperty("available", positions.size() > 0);
            if (positions.size() == 0) result.addProperty("reason", "no_captured_native_visits");
            result.addProperty("captureId", String.valueOf(identifier)); result.addProperty("source", "client_native_blueprint_hints");
            result.addProperty("nativeInvocationReturned", completed); result.addProperty("completeStructure", false);
            result.addProperty("partial", true);
            result.addProperty("eventCaptureTruncated", stopped || !completed || detailErrors > 0 || cleanupFailed);
            result.addProperty("capturedVisitsComplete", completed && !stopped && detailErrors == 0 && !cleanupFailed && positions.size() == visits);
            result.addProperty("observedVisits", visits); result.addProperty("capturedPositions", positions.size());
            long uncaptured = Math.max(0L, visits - positions.size());
            result.addProperty("uncapturedVisits", uncaptured);
            result.addProperty("captureLossDetected", uncaptured > 0L || detailErrors > 0);
            result.addProperty("captureContinuationAvailable", false);
            result.addProperty("captureEncodedBytesUpperBound", storedBytes);
            result.add("observedBounds", observedBounds(completed));
            result.addProperty("captureWorkMicros", workNanos / 1000L); result.addProperty("captureDurationMillis", Math.max(0, clock.getAsLong() - startedAt) / 1000000L);
            result.add("groups", new JsonArray()); result.add("positions", new JsonArray()); result.add("trigger", parse(context.triggerJson));
            JsonObject target = new JsonObject(); target.addProperty("dimension", context.dimension);
            target.addProperty("x", context.x); target.addProperty("y", context.y); target.addProperty("z", context.z);
            target.addProperty("metaClass", context.metaClass); target.addProperty("facing", context.facing);
            if (context.mode == null) target.add("mode", JsonNull.INSTANCE); else target.addProperty("mode", context.mode);
            result.add("controller", target); result.addProperty("nonAtomic", true);
            JsonObject limits = new JsonObject(); limits.addProperty("positions", POSITION_LIMIT); limits.addProperty("groups", GROUP_LIMIT);
            limits.addProperty("bytes", BYTE_LIMIT); limits.addProperty("captureBytes", CAPTURE_BYTE_LIMIT);
            limits.addProperty("positionEncodedBytesUpperBound", POSITION_ENCODED_BYTES);
            limits.addProperty("captureWorkMicros", WORK_BUDGET_NANOS / 1000L); limits.addProperty("ttlMillis", TTL_NANOS / 1000000L);
            result.add("limits", limits);
            JsonObject cut = new JsonObject(); cut.addProperty("positions", positionLimited); cut.addProperty("groups", groupLimited);
            cut.addProperty("bytes", byteLimited); cut.addProperty("workBudget", budgetLimited); cut.addProperty("cleanupFailed", cleanupFailed);
            result.add("truncation", cut); result.addProperty("detailErrors", detailErrors);
            JsonObject coverage = new JsonObject();
            coverage.addProperty("coordinates", "Exact loaded positions visited during this one client native blueprint call; ABC is piece-origin, XYZ is world absolute.");
            coverage.addProperty("grouping", "Public definition element identity when available, otherwise class-only; groups are element categories, not declared machine chambers.");
            coverage.addProperty("roleSelection", "Exact captureId/groupId plus untruncated public category keys can distinguish caller-declared roles across multiple element groups. Seed/ordinary labels are caller declarations, not discovered machine rules; class-only grouping is unavailable for role selection.");
            coverage.addProperty("elementKeys", "unavailable; public client definition keys do not guarantee a stable corresponding server element identity. No private fields or guessed mapping.");
            coverage.addProperty("bounds", "observedBounds covers instrumented XYZ visits only, independently of coordinate pages and query. Complete means all visits from a returned invocation were summarized within the same observation work budget, never a complete machine envelope.");
            coverage.addProperty("piece", "unknown; the public event carries no piece name, operation result or repeat-instance identity");
            coverage.addProperty("suggestions", "Partial public placement suggestions for the actual trigger; never actual hint textures or a complete acceptance rule.");
            coverage.addProperty("formation", "unavailable; client hints do not prove server formation, build completion or successful placement");
            coverage.addProperty("omissions", "Spaces/controller markers, unloaded positions and unvisited/dynamic pieces are absent; collection limits may omit later chamber candidates.");
            coverage.addProperty("captureLoss", "uncapturedVisits counts dispatched native visits without retained coordinates; descriptor errors are also reported. captureLossDetected=false alone is not completion: require capturedVisitsComplete and eventCaptureTruncated=false. Exhausting stored pages cannot recover a lost native visit; a fresh native invocation is required.");
            coverage.addProperty("captureStorage", "Bounded immutable integer-coordinate records and copied group JSON, separate from response bytes. captureEncodedBytesUpperBound conservatively bounds encoded retained data, not JVM heap overhead; at most four captures are retained.");
            coverage.addProperty("freshness", "Short-lived client-validated controller/facing/public-mode binding; client/server mode disagreement is retained as evidence and reported explicitly. Hidden dynamic state cannot be fully validated; repeat native blueprint use after relevant changes.");
            result.add("coverage", coverage); return result;
        }
        JsonObject observedBounds(boolean completed) {
            JsonObject result = new JsonObject(); boolean complete = completed && !cleanupFailed && boundsVisits == visits;
            result.addProperty("available", boundsVisits > 0); result.addProperty("complete", complete); result.addProperty("partial", !complete);
            result.addProperty("source", "client_native_hint_visited_xyz");
            result.addProperty("scope", "All instrumented visits summarized before the observation work budget stopped; missing spaces/controller markers, unloaded or unvisited dynamic pieces remain unknown.");
            result.addProperty("observedVisits", visits); result.addProperty("boundedVisits", boundsVisits);
            result.addProperty("completeStructure", false);
            if (boundsVisits == 0) { result.add("worldXYZ", JsonNull.INSTANCE); result.add("controllerRelativeXYZ", JsonNull.INSTANCE); return result; }
            result.add("worldXYZ", boundsBox(boundsMin, boundsMax, new long[] {0, 0, 0}));
            result.add("controllerRelativeXYZ", boundsBox(boundsMin, boundsMax, new long[] {context.x, context.y, context.z}));
            return result;
        }
    }

    private static JsonObject roleSelection(JsonObject group, boolean knownElement, String captureId, int groupId) {
        JsonArray keys = group.getAsJsonArray("descriptionKeys");
        boolean described = keys != null && keys.size() > 0
                && (!group.has("descriptionTruncated") || !group.get("descriptionTruncated").getAsBoolean())
                && (!group.has("descriptionCoverage") || !"public_getter_failed".equals(group.get("descriptionCoverage").getAsString()));
        JsonObject result = new JsonObject(); result.addProperty("available", knownElement && described);
        result.addProperty("binding", "capture_scoped_public_definition_element_group");
        result.addProperty("captureId", captureId); result.addProperty("groupId", groupId);
        result.add("descriptionKeys", keys == null ? new JsonArray() : keys);
        result.addProperty("semanticRoleVerified", false);
        result.addProperty("scope", "Caller-declared roles may select distinct observed public element groups using this exact capture. Public categories are partial suggestions, not chamber/seed rules or acceptance at every position.");
        if (!knownElement) result.addProperty("reason", "public_element_identity_unavailable");
        else if (!described) result.addProperty("reason", "public_category_keys_unavailable_or_truncated");
        return result;
    }

    private static JsonObject boundsBox(long[] minimum, long[] maximum, long[] origin) {
        JsonObject result = new JsonObject(), min = new JsonObject(), max = new JsonObject(), size = new JsonObject();
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            min.addProperty(axes[i], minimum[i] - origin[i]); max.addProperty(axes[i], maximum[i] - origin[i]);
            size.addProperty(axes[i], maximum[i] - minimum[i] + 1L);
        }
        result.add("min", min); result.add("max", max); result.add("size", size); return result;
    }

    interface Instrument { boolean enabled(); void enable(Object identifier); void disable(); }
    private static final class SlInstrument implements Instrument {
        SlInstrument(BlueprintObservationAdapter owner) { MinecraftForge.EVENT_BUS.register(new SlListener(owner)); }
        public boolean enabled() { return StructureLibAPI.isInstrumentEnabled(); }
        public void enable(Object id) { StructureLibAPI.enableInstrument(id); }
        public void disable() { StructureLibAPI.disableInstrument(); }
    }
    public static final class SlListener {
        final BlueprintObservationAdapter owner;
        SlListener(BlueprintObservationAdapter owner) { this.owner = owner; }
        @SubscribeEvent public void visit(StructureElementVisitedEvent event) {
            Collector collector = owner.active.get();
            if (collector == null || !collector.identifier.equals(event.getInstrumentIdentifier())) return;
            IStructureElement<?> element = event.getElement();
            String known = collector.context.elementGroups.get(element);
            owner.observe(event.getInstrumentIdentifier(), event.getWorld(), event.getX(), event.getY(), event.getZ(),
                    event.getA(), event.getB(), event.getC(), element.getClass().getName(), known,
                    () -> detail(element, collector.context, event.getWorld(), event.getX(), event.getY(), event.getZ()));
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonObject detail(IStructureElement<?> value, Context context, World world, int x, int y, int z) {
        IStructureElement<Object> element = (IStructureElement<Object>) value;
        JsonObject result = new JsonObject(); JsonArray descriptions = new JsonArray(), names = new JsonArray(), candidates = new JsonArray();
        try {
            List<String> list = element.getDescription(context.meta.get());
            if (list != null) for (String key : list) {
                if (descriptions.size() >= 16) { result.addProperty("descriptionTruncated", true); break; }
                descriptions.add(new JsonPrimitive(JsonData.bounded(key, 256)));
                names.add(new JsonPrimitive(JsonData.bounded(StatCollector.translateToLocal(key), 256)));
            }
            result.addProperty("descriptionCoverage", list == null ? "unavailable" : "public_category_descriptions");
        } catch (RuntimeException | LinkageError error) { result.addProperty("descriptionCoverage", "public_getter_failed"); }
        try {
            IStructureElement.BlocksToPlace placement = element.getBlocksToPlace(context.meta.get(), world, x, y, z, context.trigger.copy(), null);
            Iterable<ItemStack> stacks = placement == null ? null : placement.getStacks();
            result.addProperty("candidateCoverage", stacks == null ? "unavailable_or_predicate_only" : "public_stack_suggestions");
            if (stacks != null) {
                int visited = 0;
                for (ItemStack stack : stacks) {
                    if (visited++ >= 16) { result.addProperty("candidatesTruncated", true); break; }
                    if (stack == null || stack.getItem() == null) continue;
                    JsonObject candidate = new JsonObject(); candidate.addProperty("registryName", JsonData.bounded(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())), 256));
                    candidate.addProperty("metadata", stack.getMetadata()); candidate.addProperty("count", stack.stackSize);
                    candidate.addProperty("unlocalizedName", JsonData.bounded(stack.getUnlocalizedName(), 256)); candidate.addProperty("hasTag", stack.hasTagCompound());
                    String nbt = stack.hasTagCompound() ? stack.getTagCompound().toString() : "";
                    if (nbt.length() > 65536) { result.addProperty("candidatesTruncated", true); result.addProperty("candidateCoverage", "public_candidate_nbt_oversized"); break; }
                    candidate.addProperty("nbtIdentity", nbt);
                    if (stack.getItem() instanceof ItemBlock) {
                        Block block = Block.getBlockFromItem(stack.getItem()); int metadata = stack.getItem().getMetadata(stack.getMetadata());
                        JsonObject identity = new JsonObject(); identity.addProperty("registryName", String.valueOf(Block.blockRegistry.getNameForObject(block))); identity.addProperty("metadata", metadata);
                        candidate.add("blockIdentity", identity); candidate.addProperty("blockHasTileEntity", block.hasTileEntity(metadata));
                    }
                    candidates.add(candidate);
                }
            }
        } catch (RuntimeException | LinkageError error) { result.addProperty("candidateCoverage", "unavailable_without_autoplace_environment"); }
        result.add("descriptionKeys", descriptions); result.add("description", names); result.add("suggestedCandidates", candidates);
        return result;
    }

    private static Context context(Minecraft client, String session, MovingObjectPosition target, ItemStack trigger) {
        Object tile = loadedTile(client.theWorld, target.blockX, target.blockY, target.blockZ);
        if (tile == null) return null;
        Object meta = meta(tile), definition = optional(meta, "getStructureDefinition");
        String signature = triggerSignature(trigger);
        if (signature.length() > 4096) return null;
        JsonObject tool = new JsonObject(); tool.addProperty("registryName", String.valueOf(Item.itemRegistry.getNameForObject(trigger.getItem())));
        tool.addProperty("metadata", trigger.getMetadata()); tool.addProperty("count", trigger.stackSize);
        tool.addProperty("channelData", trigger.hasTagCompound() ? trigger.getTagCompound().toString() : "");
        Context scope = new Context(client.theWorld, tile, meta, definition, client.theWorld.provider.dimensionId,
                target.blockX, target.blockY, target.blockZ, session, client.thePlayer.getUniqueID().toString(),
                facing(optional(meta, "getExtendedFacing")), mode(meta), tool.toString(), trigger.copy(), signature);
        // References originate from the public definition, not from events, and are discarded when the call finishes.
        Object elements = optional(definition, "getElements");
        if (elements instanceof Map<?, ?>) {
            int visited = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) elements).entrySet()) {
                if (visited++ >= 1024) break;
                Object element = entry.getValue();
                if (element != null) scope.elementGroups.put(element, "element:" + String.valueOf(entry.getKey()));
            }
        }
        return scope;
    }

    private static Object loadedTile(World world, int x, int y, int z) {
        if (!world.blockExists(x, y, z) || !world.getChunkProvider().chunkExists(x >> 4, z >> 4)) return null;
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
    }
    private static Object meta(Object tile) {
        Object result = optional(tile, "getMetaTileEntity");
        if (result != null) return result;
        result = optional(tile, "getConstructable");
        return result == null ? tile : result;
    }
    private static Object optional(Object target, String method) {
        if (target == null) return null;
        try { Method getter = target.getClass().getMethod(method); return getter.invoke(target); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return null; }
    }
    private static Integer mode(Object target) { Object value = optional(target, "getMachineMode"); return value instanceof Number ? ((Number) value).intValue() : null; }
    private static String facing(Object value) { return value == null ? "unavailable" : String.valueOf(value); }
    private static String type(Object value) { return value == null ? "unavailable" : value.getClass().getName(); }
    private static String triggerSignature(ItemStack stack) { return stack.getMetadata() + ":" + stack.stackSize + ":" + (stack.hasTagCompound() ? stack.getTagCompound().toString() : ""); }
    private static int bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static JsonObject parse(String value) { return new JsonParser().parse(value).getAsJsonObject(); }

    static final class Context {
        final WeakReference<Object> world, tile, meta, definition;
        final int dimension, x, y, z; final String session, player, facing, metaClass, definitionClass, triggerJson, triggerSignature;
        final Integer mode; final ItemStack trigger; final Item triggerItem;
        final IdentityHashMap<Object, String> elementGroups = new IdentityHashMap<Object, String>();
        Context(Object world, Object tile, Object meta, Object definition, int dimension, int x, int y, int z,
                String session, String player, String facing, Integer mode, String triggerJson, ItemStack trigger, String signature) {
            this.world = new WeakReference<Object>(world); this.tile = new WeakReference<Object>(tile);
            this.meta = new WeakReference<Object>(meta); this.definition = new WeakReference<Object>(definition);
            this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.session = session; this.player = player;
            this.facing = facing; this.mode = mode; this.metaClass = type(meta); this.definitionClass = type(definition);
            this.triggerJson = triggerJson; this.trigger = trigger; this.triggerItem = trigger == null ? null : trigger.getItem(); this.triggerSignature = signature;
        }
        Context withoutElementReferences() {
            return new Context(world.get(), tile.get(), meta.get(), definition.get(), dimension, x, y, z, session, player,
                    facing, mode, triggerJson, trigger, triggerSignature);
        }
    }

    private static final class Entry {
        final Context scope; final String payload, captureId; final long capturedAt; long validatedAt;
        final List<CapturedPosition> positions;
        final List<GroupSnapshot> groups;
        final Map<Integer, WeakReference<Object>> elements;
        WeakReference<Object> serverTile, serverMeta, serverDefinition;
        Entry(Context scope, String payload, long now, String captureId, List<CapturedPosition> positions, JsonArray groups, Map<Integer, WeakReference<Object>> elements) {
            this.scope = scope; this.payload = payload; this.captureId = captureId; this.capturedAt = this.validatedAt = now;
            this.positions = Collections.unmodifiableList(new ArrayList<CapturedPosition>(positions));
            List<GroupSnapshot> copiedGroups = new ArrayList<GroupSnapshot>();
            for (int i = 0; i < groups.size(); i++) copiedGroups.add(new GroupSnapshot(groups.get(i).getAsJsonObject()));
            this.groups = Collections.unmodifiableList(copiedGroups);
            this.elements = Collections.unmodifiableMap(new LinkedHashMap<Integer, WeakReference<Object>>(elements));
        }
        boolean bindServer(Object tile, Object meta, Object definition) {
            if (serverTile == null) { serverTile = new WeakReference<Object>(tile); serverMeta = new WeakReference<Object>(meta); serverDefinition = new WeakReference<Object>(definition); return true; }
            return serverTile.get() == tile && serverMeta.get() == meta && serverDefinition.get() == definition;
        }
    }

    static final class CapturedPosition {
        final int x, y, z, a, b, c, group;
        CapturedPosition(int x, int y, int z, int a, int b, int c, int group) {
            this.x = x; this.y = y; this.z = z; this.a = a; this.b = b; this.c = c; this.group = group;
        }
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
            result.addProperty("a", a); result.addProperty("b", b); result.addProperty("c", c);
            result.addProperty("group", group); result.add("piece", JsonNull.INSTANCE);
            return result;
        }
    }

    private static final class GroupSnapshot {
        final String payload; final int capturedVisits, encodedBytes;
        GroupSnapshot(JsonObject group) {
            payload = group.toString(); capturedVisits = group.get("capturedVisits").getAsInt();
            encodedBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static final class Diagnostic {
        final int dimension, x, y, z; final long recordedAt; final String payload;
        Diagnostic(int dimension, int x, int y, int z, long recordedAt, String payload) {
            this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.recordedAt = recordedAt; this.payload = payload;
        }
    }
}
