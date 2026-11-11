package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.structure.NativeGtWrench;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.structure.PlacementFacing;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

/** One bounded read: client scalars, authoritative server space, then actual client-native visibility. */
public final class ApproachPlanner {
    public static final long WORK_NANOS = 4000000L;
    public static final int CLIENT_PHASE_CLOCK_VERSION = 1;
    public static final int MOVEMENT_STRATEGY_VERSION = 1;
    public static final int OPERATION_POSTURE_VERSION = 1;
    static final String CLIENT_PHASE_CLOCK_SOURCE = "bridge_client_tick";
    public static final int MAX_CANDIDATES = 33, MAX_RAYS = 198, MAX_CLIENT_RAYS = 27, MAX_SERVER_PAGES = 4;
    static final int MAX_CANDIDATE_ATTEMPTS = MAX_CANDIDATES + MAX_SERVER_PAGES - 1;
    static final int RAY_COUNTING_VERSION = 1, MAX_RAYS_PER_FACE = 52;
    public interface Access extends NativePlacementAdapter.Access {
        JsonObject space(int x, int y, int z, double width, double height, long deadline);
        default AxisAlignedBB placementBody(double x, double y, double z, boolean currentOnly) {
            throw new BridgeException("unsupported", "Actual owning-thread placement actor AABB is unavailable.");
        }
        default JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline) {
            throw new BridgeException("unsupported", "Continuous actual-current body observation is unavailable.");
        }
        default JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline, boolean fullSupport) {
            JsonObject result = spaceAt(x, y, z, width, height, deadline);
            if (fullSupport && !PlayerNavigation.supportProven(result)) result.addProperty("canStand", false);
            return result;
        }
    }
    /** Count actual native callbacks, and stop the shape sampler as soon as a shared bound expires. */
    static final class RayAccess implements NativePlacementAdapter.Access {
        final Access delegate;
        final LongSupplier clock;
        final long deadline;
        final int limit;
        int rays;
        String stopReason;
        String expectedPlacementFacing;
        boolean wrenchFacing, primaryOnly;
        JsonObject hitPoint;
        double nativeViewReach = Double.NaN;
        RayAccess(Access delegate, LongSupplier clock, long deadline, int rays, int limit) {
            this.delegate = delegate; this.clock = clock; this.deadline = deadline; this.rays = rays; this.limit = limit;
        }
        private void check() {
            if (clock.getAsLong() >= deadline) { stopReason = "tick_budget"; throw new RayLimit(); }
            if (rays >= limit) { stopReason = "ray_limit"; throw new RayLimit(); }
        }
        private void checkTime() {
            if (clock.getAsLong() >= deadline) { stopReason = "tick_budget"; throw new RayLimit(); }
        }
        public boolean loaded(int x, int y, int z) {
            check(); boolean loaded = delegate.loaded(x, y, z); checkTime(); return loaded;
        }
        public NativePlacementAdapter.State state(int x, int y, int z) {
            check(); NativePlacementAdapter.State state = delegate.state(x, y, z); checkTime(); return state;
        }
        public AxisAlignedBB selectedBounds(int x, int y, int z) {
            check(); AxisAlignedBB bounds = delegate.selectedBounds(x, y, z); checkTime(); return bounds;
        }
        public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
            check(); rays++;
            MovingObjectPosition hit = delegate.trace(eye, point);
            // A native callback cannot be preempted. Its late result is rejected before any new probe.
            checkTime(); return hit;
        }
        public JsonObject selectedPart(MovingObjectPosition hit) { checkTime(); JsonObject part = delegate.selectedPart(hit); checkTime(); return part; }
        public AxisAlignedBB partBounds(int x, int y, int z, JsonObject part) { checkTime(); AxisAlignedBB bounds = delegate.partBounds(x, y, z, part); checkTime(); return bounds; }
        public java.util.List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject part) { checkTime(); java.util.List<AxisAlignedBB> boxes = delegate.partBoxes(x, y, z, part); checkTime(); return boxes; }
        MovingObjectPosition face(Vec3 eye, double reach, int x, int y, int z, int side) {
            return face(eye, reach, x, y, z, side, null);
        }
        MovingObjectPosition face(Vec3 eye, double reach, int x, int y, int z, int side, JsonObject part) {
            return face(eye, reach, x, y, z, side, part, null);
        }
        MovingObjectPosition face(Vec3 eye, double reach, int x, int y, int z, int side, JsonObject part, JsonObject preferred) {
            try {
                MovingObjectPosition direction = null;
                // Every shape can reuse the proved server direction. This is only
                // a first probe: the client still traces its current geometry and
                // verifies block, face, part, reach and the existing ray/time cap.
                if (preferred != null) {
                    JsonObject point = preferred.getAsJsonObject("hit");
                    direction = new MovingObjectPosition(x, y, z, side, Vec3.createVectorHelper(
                        point.get("x").getAsDouble(), point.get("y").getAsDouble(), point.get("z").getAsDouble()));
                }
                NativePlacementAdapter.Access view = Double.isFinite(nativeViewReach) ? NativePlacementAdapter.nativeView(this, nativeViewReach)
                    : wrenchFacing ? NativePlacementAdapter.nativeView(this, reach) : this;
                MovingObjectPosition hit = primaryOnly ? NativePlacementAdapter.primaryFace(view, eye, reach, x, y, z, side)
                    : hitPoint != null ? InteractionHitPoint.probe(NativePlacementAdapter.forPart(this, part), eye, reach, x, y, z, side, hitPoint)
                    : wrenchFacing ? NativeGtWrench.probe(view, eye, reach, x, y, z, side)
                    : NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(PlacementFacing.filter(view,expectedPlacementFacing), part), eye, reach, x, y, z, side, direction);
                if (hit == null && rays >= limit) stopReason = "ray_limit";
                return hit;
            }
            catch (RayLimit stop) { return null; }
        }
    }
    private static final class RayLimit extends RuntimeException {
        RayLimit() { super(null, null, false, false); }
    }
    public interface Stages {
        Frame capture();
        default Frame ready(Frame frame, long deadline, GameQueue.Control control) throws ExecutionException, InterruptedException { return frame; }
        default JsonObject eyeObservation() { return EyeRead.immediate(); }
        Search search(Frame frame, Search previous);
        GuardedContext.Result finish(Frame frame, Search search);
    }
    public static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Stages stages)
            throws ExecutionException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        long remaining = remaining(deadline);
        Frame captured = client.await(client.submit(stages::capture, remaining, control), remaining);
        final Frame frame = stages.ready(captured, deadline, control);
        Search search = null;
        for (int page = 0; page < frame.request.maxPages(); page++) {
            Frame current = frame;
            if (page > 0) {
                remaining = remaining(deadline);
                current = client.await(client.submit(stages::capture, remaining, control), remaining);
                frame.check(current);
            }
            final Frame checked = current; final Search previous = search;
            remaining = remaining(deadline);
            search = server.await(server.submit(() -> stages.search(checked, previous), remaining, control), remaining);
            if (!search.partial || search.selected != null || search.rays >= frame.request.maxRays()) break;
        }
        remaining = remaining(deadline);
        final Search completed = search;
        return client.await(client.submit(() -> stages.finish(frame, completed), remaining, control), remaining);
    }
    public static long remaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new BridgeException("timeout", "Approach planning exhausted its shared read deadline.");
        return remaining;
    }

    public static final class Request {
        public final String intent, pattern, expectedWorld, expectedRegistry, expectedMetaClass, movementStrategy, operationPosture;
        public final int x, y, z, dimension, slot, offset, expectedMetadata, expectedMetaTileId;
        public final boolean requireFlying, currentOnly, useNormalReach, wrenchFacing;
        final double maxDistance;
        final int[] sides;
        final JsonObject expectedPart;
        final JsonObject hitPoint;
        final String expectedPlacementFacing;
        final net.minecraft.util.AxisAlignedBB excludedBuildEnvelope;
        final List<Request> sharedTargets = new ArrayList<Request>();
        public Request(JsonObject args, int dimension) {
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
                String key = entry.getKey();
                if (!"intent".equals(key) && !"pattern".equals(key) && !"x".equals(key) && !"y".equals(key) && !"z".equals(key)
                        && !"dimension".equals(key) && !"expectedWorld".equals(key) && !"hotbarSlot".equals(key)
                        && !"requireFlying".equals(key) && !"maxDistance".equals(key) && !"allowedSides".equals(key)
                        && !"targetIdentity".equals(key) && !"offset".equals(key) && !"currentOnly".equals(key) && !"useNormalReach".equals(key) && !"excludedBuildEnvelope".equals(key)
                        && !"movementStrategy".equals(key) && !"operationPosture".equals(key) && !"targets".equals(key) && !"expectedPlacementFacing".equals(key) && !"wrenchFacing".equals(key) && !"hitPoint".equals(key))
                    throw new BridgeException("invalid_args", "Unexpected approach argument: " + key);
            }
            intent = JsonData.string(args, "intent");
            expectedPlacementFacing = PlacementFacing.requested(args);
            if (expectedPlacementFacing != null && !"place_block".equals(intent))
                throw new BridgeException("invalid_args", "Placement-facing filtering is only valid for block placement planning.");
            if (!"place_block".equals(intent) && !"use".equals(intent) && !"attack".equals(intent))
                throw new BridgeException("invalid_args", "intent must be place_block, use or attack.");
            pattern = args.has("pattern") ? JsonData.string(args, "pattern") : "surround";
            if (!"surround".equals(pattern) && !"cardinal".equals(pattern)) throw new BridgeException("invalid_args", "pattern must be surround or cardinal.");
            x = JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            y = JsonData.integer(args, "y", 0, 255); z = JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            this.dimension = dimension;
            if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension)
                throw new BridgeException("stale_world", "Approach planning supports only the current dimension.");
            expectedWorld = JsonData.string(args, "expectedWorld");
            if (expectedWorld.isEmpty() || expectedWorld.length() > 128) throw new BridgeException("invalid_args", "expectedWorld must contain 1 through 128 characters.");
            slot = "place_block".equals(intent) ? JsonData.integer(args, "hotbarSlot", 0, 8) : -1;
            if (slot < 0 && args.has("hotbarSlot")) throw new BridgeException("invalid_args", "hotbarSlot is only valid for place_block.");
            requireFlying = GuiDataAdapter.booleanArg(args, "requireFlying", true);
            currentOnly = GuiDataAdapter.booleanArg(args, "currentOnly", false);
            movementStrategy = args.has("movementStrategy") ? ExpandedGuiApi.option(args, "movementStrategy", "walking",
                    "walking", "ability_flight", "developer_teleport") : null;
            operationPosture = args.has("operationPosture") ? ExpandedGuiApi.option(args, "operationPosture", "flying", "flying") : null;
            if (operationPosture != null && (!requireFlying || !"ability_flight".equals(movementStrategy)))
                throw new BridgeException("invalid_args", "Explicit flying operation posture requires ability_flight and actual required flight; no posture or strategy fallback.");
            excludedBuildEnvelope = args.has("excludedBuildEnvelope") ? buildEnvelope(args.get("excludedBuildEnvelope")) : null;
            if (excludedBuildEnvelope != null && (!"use".equals(intent) || !"surround".equals(pattern)))
                throw new BridgeException("invalid_args", "Build-body exclusion is an explicitly declared surround/use constraint; actual flight still requires native permission.");
            useNormalReach = GuiDataAdapter.booleanArg(args, "useNormalReach", false);
            maxDistance = args.has("maxDistance") ? JsonData.number(args, "maxDistance", .1, useNormalReach ? 32 : 3) : 3;
            offset = args.has("offset") ? JsonData.integer(args, "offset", 0, total() - 1) : 0;
            if (currentOnly && offset != 0) throw new BridgeException("invalid_args", "Actual-current-only planning has exactly one candidate and offset zero.");
            if (args.has("allowedSides")) {
                JsonElement value = args.get("allowedSides");
                if (!value.isJsonArray() || value.getAsJsonArray().size() < 1 || value.getAsJsonArray().size() > 6)
                    throw new BridgeException("invalid_args", "allowedSides must contain one through six distinct sides.");
                sides = new int[value.getAsJsonArray().size()]; boolean[] used = new boolean[6]; int index = 0;
                for (JsonElement side : value.getAsJsonArray()) {
                    JsonObject scalar = new JsonObject(); scalar.add("side", side);
                    int number = JsonData.integer(scalar, "side", 0, 5);
                    if (used[number]) throw new BridgeException("invalid_args", "allowedSides must not repeat sides.");
                    used[number] = true; sides[index++] = number;
                }
            } else sides = new int[] {0, 1, 2, 3, 4, 5};
            String registry = null, metaClass = null; int metadata = -1, metaTileId = -1; JsonObject part = null;
            if (args.has("targetIdentity")) {
                JsonElement value = args.get("targetIdentity");
                if (!value.isJsonObject()) throw new BridgeException("invalid_args", "targetIdentity must be an object.");
                JsonObject identity = value.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : identity.entrySet())
                    if (!"registryName".equals(entry.getKey()) && !"metadata".equals(entry.getKey())
                            && !"metaTileId".equals(entry.getKey()) && !"metaClass".equals(entry.getKey()) && !"part".equals(entry.getKey()))
                        throw new BridgeException("invalid_args", "Unknown targetIdentity field.");
                registry = JsonData.string(identity, "registryName");
                if (identity.has("part")) {
                    if (!identity.get("part").isJsonObject() || "place_block".equals(intent)) throw new BridgeException("invalid_args", "A mounted part identity requires use/attack and an exact part object.");
                    part = AePartAdapter.declaration(identity.getAsJsonObject("part"));
                }
                // Raw World metadata can use the client's unsigned 16-bit extension; never mask it to vanilla nibbles.
                if (identity.has("metadata")) metadata = JsonData.integer(identity, "metadata", 0, 65535);
                if (identity.has("metaTileId")) metaTileId = JsonData.integer(identity, "metaTileId", 0, 32767);
                if (identity.has("metaClass")) {
                    metaClass = JsonData.string(identity, "metaClass");
                    if (metaClass.isEmpty() || metaClass.length() > 256) throw new BridgeException("invalid_args", "metaClass length must be 1 through 256.");
                }
                if (registry.isEmpty() || registry.length() > 256) throw new BridgeException("invalid_args", "target registryName length must be 1 through 256.");
            }
            expectedRegistry = registry; expectedMetadata = metadata; expectedMetaTileId = metaTileId; expectedMetaClass = metaClass;
            expectedPart = part;
            wrenchFacing = GuiDataAdapter.booleanArg(args, "wrenchFacing", false);
            hitPoint = InteractionHitPoint.requested(args);
            if (hitPoint != null && (wrenchFacing || "place_block".equals(intent) || sides.length != 1 || expectedRegistry == null))
                throw new BridgeException("invalid_args", "A local hit point requires use/attack, one face and target identity; it excludes wrenchFacing.");
            if (args.has("wrenchFacing") && (!wrenchFacing || !"use".equals(intent)
                    || !"gregtech:gt.blockmachines".equals(expectedRegistry) || expectedPart != null || sides.length != 1))
                throw new BridgeException("invalid_args", "Wrench-facing endpoints require use, one declared face and an exact whole GT machine identity.");
            sharedTargets.addAll(SharedApproach.targets(args, dimension));
            if (!sharedTargets.isEmpty() && ("place_block".equals(intent) || operationPosture != null || offset != 0))
                throw new BridgeException("invalid_args", "Shared interaction planning supports use/attack, offset zero and the normal declared posture only.");
        }
        public static net.minecraft.util.AxisAlignedBB buildEnvelope(JsonElement value) {
            if (!value.isJsonObject()) throw new BridgeException("invalid_args", "excludedBuildEnvelope must contain six integer block bounds.");
            JsonObject b = value.getAsJsonObject(); ExpandedGuiApi.keys(b, "minX", "minY", "minZ", "maxX", "maxY", "maxZ");
            int minX = JsonData.integer(b, "minX", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), maxX = JsonData.integer(b, "maxX", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int minY = JsonData.integer(b, "minY", 0, 255), maxY = JsonData.integer(b, "maxY", 0, 255);
            int minZ = JsonData.integer(b, "minZ", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), maxZ = JsonData.integer(b, "maxZ", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            if (maxX < minX || maxY < minY || maxZ < minZ || maxX - minX >= 64 || maxY - minY >= 64 || maxZ - minZ >= 64)
                throw new BridgeException("invalid_args", "Build-body exclusion requires one ordered bounded 64-cell envelope.");
            return net.minecraft.util.AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }
        int total() { return currentOnly ? 1 : !sharedTargets.isEmpty() ? SharedApproach.candidateCount(this) : operationPosture != null ? "cardinal".equals(pattern) ? 5 : 9 : "cardinal".equals(pattern) ? 13 : 33; }
        int maxPages() { return sharedTargets.isEmpty() ? MAX_SERVER_PAGES : 16; }
        int maxRays() { return sharedTargets.isEmpty() ? MAX_RAYS : 2048; }
        int samplingPasses() { return "attack".equals(intent) && !currentOnly && sharedTargets.isEmpty()
                && expectedPart == null && hitPoint == null ? 2 : 1; }
        boolean compatible(Frame frame, Candidate candidate) {
            return (!"walking".equals(movementStrategy) || Math.abs(candidate.feetY - frame.feetY) <= (sharedTargets.isEmpty() ? .0001 : WalkingPathPlanner.MAX_HEIGHT_CHANGE))
                    && (operationPosture == null || candidate.feetY == frame.feetY && candidate.feetY <= 253);
        }
    }
    public static final class Frame {
        public final Request request;
        final Object worldIdentity;
        public final Object serverIdentity;
        public final Object screenIdentity;
        public final String session, playerId, item, itemTag, sourceItem, sourceTag, screenId;
        public final double x, feetY, z, eyeOffset, width, height, reach, eyeSmoothing;
        final boolean sneakInput;
        final AxisAlignedBB actorBody;
        final float yaw, pitch;
        final int selectedSlot;
        public final long captureWorldTick;
        public final boolean flying, sneaking, cursorEmpty, screenOpen;
        Frame(Request request, Object worldIdentity, Object serverIdentity, String session, String playerId, String item, String itemTag, String sourceItem, String sourceTag,
                double x, double feetY, double z, double eyeOffset, double width, double height, double reach, AxisAlignedBB actorBody,
                float yaw, float pitch, long captureWorldTick, int selectedSlot, boolean flying, boolean sneaking, boolean cursorEmpty, Object screenIdentity, String screenId) {
            this(request, worldIdentity, serverIdentity, session, playerId, item, itemTag, sourceItem, sourceTag,
                    x, feetY, z, eyeOffset, width, height, reach, actorBody, yaw, pitch, captureWorldTick, selectedSlot,
                    flying, sneaking, cursorEmpty, screenIdentity, screenId, Double.NaN, sneaking);
        }
        public Frame(Request request, Object worldIdentity, Object serverIdentity, String session, String playerId, String item, String itemTag, String sourceItem, String sourceTag,
                double x, double feetY, double z, double eyeOffset, double width, double height, double reach, AxisAlignedBB actorBody,
                float yaw, float pitch, long captureWorldTick, int selectedSlot, boolean flying, boolean sneaking, boolean cursorEmpty, Object screenIdentity, String screenId, double eyeSmoothing, boolean sneakInput) {
            this.request = request; this.worldIdentity = worldIdentity; this.serverIdentity = serverIdentity; this.session = session; this.playerId = playerId; this.item = item; this.itemTag = itemTag;
            this.sourceItem = sourceItem; this.sourceTag = sourceTag;
            this.x = x; this.feetY = feetY; this.z = z; this.eyeOffset = eyeOffset; this.width = width; this.height = height; this.reach = reach;
            this.yaw = yaw; this.pitch = pitch; this.selectedSlot = selectedSlot; this.flying = flying; this.sneaking = sneaking; this.cursorEmpty = cursorEmpty;
            this.eyeSmoothing = eyeSmoothing; this.sneakInput = sneakInput; this.captureWorldTick = captureWorldTick; this.actorBody = NativePlacementAdapter.actorBody(actorBody);
            this.screenIdentity = screenIdentity; this.screenId = screenId; screenOpen = screenIdentity != null;
            if (!Double.isFinite(x) || !Double.isFinite(feetY) || !Double.isFinite(z) || !Double.isFinite(eyeOffset) || eyeOffset < 1 || eyeOffset > 2
                    || !Double.isFinite(width) || width < .1 || width > 4 || !Double.isFinite(height) || height < .1 || height > 8
                    || !Double.isFinite(reach) || reach <= 0 || reach > 32 || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new BridgeException("unsupported", "The current bounded player body, eye or normal reach is unavailable.");
        }
        public void check(Frame after) { requireStable(after, true); }
        private void requireStable(Frame after, boolean eye) {
            JsonArray changed = new JsonArray();
            drift(changed, "world", worldIdentity != after.worldIdentity); drift(changed, "server", serverIdentity != after.serverIdentity);
            drift(changed, "session", !session.equals(after.session)); drift(changed, "playerId", !playerId.equals(after.playerId));
            drift(changed, "screen", screenIdentity != after.screenIdentity || screenOpen != after.screenOpen || !screenId.equals(after.screenId));
            drift(changed, "dimension", request.dimension != after.request.dimension);
            drift(changed, "x", Math.abs(x - after.x) > .0001); drift(changed, "feetY", Math.abs(feetY - after.feetY) > .0001);
            drift(changed, "z", Math.abs(z - after.z) > .0001); drift(changed, "eyeOffset", eye && Math.abs(eyeOffset - after.eyeOffset) > .0001);
            drift(changed, "width", Math.abs(width - after.width) > .0001); drift(changed, "height", Math.abs(height - after.height) > .0001);
            drift(changed, "reach", reach != after.reach);
            drift(changed, "actorBody", Math.abs(actorBody.minX - after.actorBody.minX) > .0001 || Math.abs(actorBody.minY - after.actorBody.minY) > .0001
                    || Math.abs(actorBody.minZ - after.actorBody.minZ) > .0001 || Math.abs(actorBody.maxX - after.actorBody.maxX) > .0001
                    || Math.abs(actorBody.maxY - after.actorBody.maxY) > .0001 || Math.abs(actorBody.maxZ - after.actorBody.maxZ) > .0001);
            drift(changed, "yaw", Math.abs(yaw - after.yaw) > .01); drift(changed, "pitch", Math.abs(pitch - after.pitch) > .01);
            drift(changed, "slot", selectedSlot != after.selectedSlot); drift(changed, "flying", flying != after.flying);
            drift(changed, "sneaking", sneaking != after.sneaking); drift(changed, "sneakInput", sneakInput != after.sneakInput);
            drift(changed, "cursor", cursorEmpty != after.cursorEmpty); drift(changed, "heldItem", !item.equals(after.item));
            drift(changed, "heldTag", !itemTag.equals(after.itemTag)); drift(changed, "sourceItem", !sourceItem.equals(after.sourceItem));
            drift(changed, "sourceTag", !sourceTag.equals(after.sourceTag));
            if (changed.size() > 0) throw changed(after, changed);
        }
        private static void drift(JsonArray changed, String key, boolean value) { if (value) changed.add(new com.google.gson.JsonPrimitive(key)); }
        private BridgeException changed(Frame after, JsonArray fields) {
            JsonObject delta = new JsonObject(); delta.add("changedFields", fields);
            delta.addProperty("x", after.x - x); delta.addProperty("feetY", after.feetY - feetY); delta.addProperty("z", after.z - z);
            delta.addProperty("eyeOffset", after.eyeOffset - eyeOffset); delta.addProperty("yaw", after.yaw - yaw); delta.addProperty("pitch", after.pitch - pitch);
            delta.addProperty("readOnly", true); delta.addProperty("submitted", false);
            return new BridgeException("stale_world", "The client world, player pose or actual inventory state changed during approach planning. actualDelta=" + delta);
        }
        public boolean needsEyeSettle() { return !sneaking && !sneakInput && boundedSmoothing() && eyeSmoothing > EyeRead.STABLE_EPSILON; }
        private boolean boundedSmoothing() { return Double.isFinite(eyeSmoothing) && eyeSmoothing >= 0 && eyeSmoothing <= (double) .2F; }
        void checkSettling(Frame after, Frame previous) {
            requireStable(after, false);
            if (sneaking || sneakInput || after.sneaking || after.sneakInput || !boundedSmoothing() || !after.boundedSmoothing()
                    || after.eyeSmoothing > previous.eyeSmoothing + .0000001
                    || Math.abs(eyeOffset + eyeSmoothing - after.eyeOffset - after.eyeSmoothing) > .0001) {
                JsonArray fields = new JsonArray(); drift(fields, "eyeOffset", true); drift(fields, "eyeSmoothing", true);
                throw changed(after, fields);
            }
        }
    }
    /** Client-tick pure observations only; no key, pose, world or inventory setter is reachable here. */
    public static final class EyeRead {
        public static final int MAX_ACTIVE = 4, MAX_TICK_SAMPLES = 16;
        static final double STABLE_EPSILON = .00001;
        public final CompletableFuture<Frame> future = new CompletableFuture<Frame>();
        final Frame initial;
        final Callable<Frame> capture;
        final LongSupplier clock;
        final long deadline, startedAt, startTick;
        Frame latest;
        long lastTick, finishedAt;
        int tickSamples;
        boolean ended;
        public EyeRead(Frame initial, Callable<Frame> capture, long tick, long deadline, LongSupplier clock) {
            if (!initial.needsEyeSettle()) throw new BridgeException("invalid_state", "Only actual released-sneak public eye smoothing may register this pure read.");
            this.initial = initial; latest = initial; this.capture = capture; startTick = tick; lastTick = tick;
            this.deadline = deadline; this.clock = clock; startedAt = clock.getAsLong();
        }
        public synchronized void tick(long tick) {
            if (future.isDone() || tick <= lastTick) return;
            if (clock.getAsLong() >= deadline) { fail(new BridgeException("timeout", "Approach eye observation reached its original read deadline.")); return; }
            lastTick = tick; tickSamples++;
            try {
                Frame current = capture.call(); initial.checkSettling(current, latest); latest = current;
                if (current.eyeSmoothing <= STABLE_EPSILON) {
                    finishedAt = clock.getAsLong(); ended = true; future.complete(current);
                } else if (tickSamples >= MAX_TICK_SAMPLES) fail(new BridgeException("stale_world", "Released-sneak actual eye smoothing did not settle within its bounded tick samples."));
            } catch (BridgeException error) { fail(error); }
            catch (Exception error) { fail(new BridgeException("internal_error", "Approach pure eye observation failed: " + error.getClass().getSimpleName())); }
        }
        public synchronized JsonObject receipt() {
            JsonObject value = new JsonObject(); value.addProperty("readOnly", true); value.addProperty("submitted", false);
            value.addProperty("waited", true); value.addProperty("tickSamples", tickSamples); value.addProperty("actualClientTicks", lastTick - startTick);
            value.addProperty("elapsedMillis", Math.max(0, (ended ? finishedAt : clock.getAsLong()) - startedAt) / 1000000D);
            value.addProperty("initialEyeOffset", initial.eyeOffset); value.addProperty("finalEyeOffset", latest.eyeOffset);
            value.addProperty("initialPublicYOffset2", initial.eyeSmoothing); value.addProperty("finalPublicYOffset2", latest.eyeSmoothing);
            value.addProperty("stableEpsilon", STABLE_EPSILON); value.addProperty("settled", ended && latest.eyeSmoothing <= STABLE_EPSILON);
            return value;
        }
        synchronized void fail(BridgeException error) {
            if (future.isDone()) return;
            finishedAt = clock.getAsLong(); ended = true;
            future.completeExceptionally(new BridgeException(error.code, error.getMessage() + " eyeSettling=" + receipt()));
        }
        public Frame await() throws ExecutionException, InterruptedException {
            try { return future.get(remaining(deadline), TimeUnit.MILLISECONDS); }
            catch (TimeoutException error) { fail(new BridgeException("timeout", "Approach eye observation reached its original read deadline.")); return future.get(); }
            catch (BridgeException error) { fail(error); return future.get(); }
        }
        public void close() { fail(new BridgeException("cancelled", "The pure approach eye observation was cancelled; no action was submitted.")); }
        public static JsonObject immediate() {
            JsonObject value = new JsonObject(); value.addProperty("readOnly", true); value.addProperty("submitted", false);
            value.addProperty("waited", false); value.addProperty("tickSamples", 0); value.addProperty("actualClientTicks", 0); value.addProperty("elapsedMillis", 0);
            return value;
        }
    }
    static final class Candidate {
        final int x, y, z, side;
        final double feetX, feetY, feetZ;
        Candidate(int x, int y, int z, int side) { this.x = x; this.y = y; this.z = z; this.side = side; feetX = x + .5; feetY = y; feetZ = z + .5; }
        Candidate(double x, double y, double z) { this(x, y, z, -1); }
        Candidate(double x, double y, double z, int side) { this.x = (int) Math.floor(x); this.y = (int) Math.floor(y); this.z = (int) Math.floor(z); this.side = side; feetX = x; feetY = y; feetZ = z; }
        Vec3 eye(Frame frame) { return Vec3.createVectorHelper(feetX, feetY + frame.eyeOffset, feetZ); }
        AxisAlignedBB body(Frame frame) { return frame.actorBody.getOffsetBoundingBox(feetX - frame.x, feetY - frame.feetY, feetZ - frame.z); }
        boolean placementExcluded(Frame frame) { return "place_block".equals(frame.request.intent)
                && body(frame).intersectsWith(NativePlacementAdapter.targetCube(frame.request.x, frame.request.y, frame.request.z)); }
        boolean buildExcluded(Frame frame) {
            if (frame.request.excludedBuildEnvelope == null) return false;
            return net.minecraft.util.AxisAlignedBB.getBoundingBox(feetX - frame.width / 2, feetY, feetZ - frame.width / 2,
                    feetX + frame.width / 2, feetY + frame.height, feetZ + frame.width / 2).intersectsWith(frame.request.excludedBuildEnvelope);
        }
        JsonObject space(Access access, Frame frame, long deadline) { return frame.request.currentOnly || frame.request.operationPosture != null || !frame.request.sharedTargets.isEmpty() || groundedTeleport(frame.request)
                ? access.spaceAt(feetX, feetY, feetZ, frame.width, frame.height, deadline,
                        groundedTeleport(frame.request) || !frame.request.sharedTargets.isEmpty() && "walking".equals(frame.request.movementStrategy)) : access.space(x, y, z, frame.width, frame.height, deadline); }
    }
    static Candidate candidate(Frame frame, int index) {
        Request request = frame.request;
        if (!request.sharedTargets.isEmpty()) return SharedApproach.candidate(frame, index);
        if (request.currentOnly || request.operationPosture != null && index == 0) return index == 0 ? new Candidate(frame.x, frame.feetY, frame.z) : null;
        if (index == 0) {
            int x = (int) Math.floor(frame.x), y = (int) Math.rint(frame.feetY), z = (int) Math.floor(frame.z);
            double tolerance = "cardinal".equals(request.pattern) ? .0001000000001 : .0001;
            return Math.abs(frame.x - x - .5) < tolerance && Math.abs(frame.z - z - .5) < tolerance && Math.abs(frame.feetY - y) < tolerance
                    ? new Candidate(x, y, z, -1) : null;
        }
        int[][] offsets = {{0, -2}, {0, 2}, {-2, 0}, {2, 0}, {-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        if (request.excludedBuildEnvelope != null) {
            net.minecraft.util.AxisAlignedBB b = request.excludedBuildEnvelope;
            int west = (int) b.minX - 1 - request.x, east = (int) b.maxX - request.x;
            int north = (int) b.minZ - 1 - request.z, south = (int) b.maxZ - request.z;
            offsets = new int[][] {{0, north}, {0, south}, {west, 0}, {east, 0}, {west, north}, {east, north}, {west, south}, {east, south}};
        }
        int[] heights = "cardinal".equals(request.pattern) ? new int[] {-1, 0, 1} : request.excludedBuildEnvelope != null
                ? new int[] {(int) request.excludedBuildEnvelope.minY - request.y, -1, 0, 1} : new int[] {-1, 0, -2, 1};
        int stride = "cardinal".equals(request.pattern) ? 4 : 8, direction = (index - 1) % stride;
        if (request.operationPosture != null) return new Candidate(request.x + .5 + offsets[direction][0], frame.feetY,
                request.z + .5 + offsets[direction][1], "cardinal".equals(request.pattern) && !"place_block".equals(request.intent) ? new int[] {2, 3, 4, 5}[direction] : -1);
        Candidate candidate = new Candidate(request.x + offsets[direction][0], request.y + heights[(index - 1) / stride], request.z + offsets[direction][1],
                "cardinal".equals(request.pattern) && !"place_block".equals(request.intent) ? new int[] {2, 3, 4, 5}[direction] : -1);
        Candidate current = candidate(frame, 0);
        return current != null && current.x == candidate.x && current.y == candidate.y && current.z == candidate.z ? null : candidate;
    }
    public static final class Search {
        public final JsonObject target, flight;
        final double serverReach;
        public Candidate selected;
        JsonObject body, attachment, serverRay, placementBodyProof, currentPlacementBodyProof;
        JsonArray sharedIdentities, sharedServerViews;
        public int candidates, candidateAttempts, rays, unknown, nextOffset, placementBodyExcluded, strategyIncompatible, samplingPass, pages = 1;
        final boolean[] visited = new boolean[SharedApproach.MAX_CANDIDATES];
        long serverObservedAt;
        public boolean partial;
        String stopReason;
        Search(JsonObject target, JsonObject flight, int offset) {
            this.target = target; this.flight = flight; nextOffset = offset;
            serverReach = JsonData.number(flight, "normalReach", Double.MIN_VALUE, 32);
        }
    }
    static Search search(Access access, Frame frame, JsonObject flight, LongSupplier clock) {
        return search(access, frame, flight, null, clock);
    }
    public static Search search(Access access, Frame frame, JsonObject flight, Search previous, LongSupplier clock) {
        Request request = frame.request;
        if (request.operationPosture != null && (!frame.flying || !yes(flight, "flying") || !yes(flight, "allowFlying") || !no(flight, "creative") || !no(flight, "onGround")))
            throw new BridgeException("invalid_state", "Hover endpoints require real client/server survival flight and existing native permission.");
        long observedAt = clock.getAsLong(), deadline = observedAt + WORK_NANOS;
        if (!access.loaded(request.x, request.y, request.z)) throw new BridgeException("chunk_unloaded", "Approach target is not actually loaded.");
        JsonObject target = access.state(request.x, request.y, request.z).description;
        requireIdentity(request, target);
        if ("place_block".equals(request.intent) && !"minecraft:air".equals(JsonData.string(target, "registryName")))
            throw new BridgeException("invalid_state", "Placement planning requires literal minecraft:air; replacement uses its existing separate workflow.");
        Search search = new Search(target, flight, request.offset);
        if (!request.sharedTargets.isEmpty()) search.sharedIdentities = SharedApproach.identities(access, request);
        if (!frame.playerId.equals(JsonData.string(flight, "playerId")))
            throw new BridgeException("stale_world", "The owning-thread normal reach observation belongs to another authoritative player.");
        if (previous != null) {
            if (!previous.partial || previous.selected != null || previous.pages >= request.maxPages() || previous.rays >= request.maxRays())
                throw new BridgeException("invalid_state", "Only an unfinished bounded approach observation may continue.");
            sameIdentity(previous.target, target);
            if (!request.sharedTargets.isEmpty()) SharedApproach.sameIdentities(previous.sharedIdentities, search.sharedIdentities);
            if (search.serverReach != previous.serverReach)
                throw new BridgeException("stale_world", "The actual authoritative normal reach changed between bounded approach pages.");
            search.nextOffset = previous.nextOffset; search.pages = previous.pages + 1;
            search.candidates = previous.candidates; search.candidateAttempts = previous.candidateAttempts;
            search.rays = previous.rays; search.unknown = previous.unknown; search.placementBodyExcluded = previous.placementBodyExcluded;
            search.currentPlacementBodyProof = previous.currentPlacementBodyProof;
            search.strategyIncompatible = previous.strategyIncompatible;
            search.samplingPass = previous.samplingPass;
            System.arraycopy(previous.visited, 0, search.visited, 0, search.visited.length);
        }
        search.serverObservedAt = observedAt;
        RayAccess rays = new RayAccess(access, clock, deadline, search.rays, request.maxRays());
        // Endpoint search and actual-arrival rechecks must use the same achievable
        // native view. A short ideal ray can stop before an inset multipart face.
        rays.nativeViewReach = Math.min(frame.reach, search.serverReach);
        rays.expectedPlacementFacing = request.expectedPlacementFacing;
        // Distribute the first native face probe across the bounded endpoints before
        // spending the same global ray budget on detailed occlusion/shape samples.
        for (int pass = search.samplingPass; pass < request.samplingPasses(); pass++) {
            search.samplingPass = pass;
            rays.primaryOnly = request.samplingPasses() == 2 && pass == 0;
            for (int index = search.nextOffset; index < request.total(); index++) {
                if (clock.getAsLong() >= deadline) { search.partial = true; break; }
                search.nextOffset = index; search.candidateAttempts++;
                boolean firstVisit = !search.visited[index];
                if (firstVisit) { search.visited[index] = true; search.candidates++; }
                Candidate candidate = candidate(frame, index);
                if (candidate == null || candidate.x < WorldScan.WORLD_MIN || candidate.x > WorldScan.WORLD_MAX || candidate.z < WorldScan.WORLD_MIN
                        || candidate.z > WorldScan.WORLD_MAX || candidate.y < 0 || candidate.y > ("cardinal".equals(request.pattern) ? 254 : 253)
                        || candidate.feetY + frame.height > 256 || request.operationPosture != null && candidate.feetY > 253) { search.nextOffset = index + 1; continue; }
                if (!request.compatible(frame, candidate)) { if (firstVisit) search.strategyIncompatible++; search.nextOffset = index + 1; continue; }
                if (!request.sharedTargets.isEmpty() && !SharedApproach.withinReach(frame, candidate, distanceBound(frame, search))) { search.nextOffset = index + 1; continue; }
                if (candidate.buildExcluded(frame)) { search.nextOffset = index + 1; continue; }
                JsonObject placementProof = null;
                if ("place_block".equals(request.intent)) {
                    placementProof = NativePlacementAdapter.placementBodyProof(access.placementBody(candidate.feetX, candidate.feetY, candidate.feetZ, request.currentOnly), request.x, request.y, request.z);
                    if (index == 0) search.currentPlacementBodyProof = placementProof;
                    if (candidate.placementExcluded(frame) || placementProof.get("overlapsTarget").getAsBoolean()) {
                        search.placementBodyExcluded++; search.nextOffset = index + 1; continue;
                    }
                }
                JsonObject body = candidate.space(access, frame, deadline);
                if (!safe(body, request)) {
                    if (!yes(body, "loaded") || yes(body, "entityCoveragePartial") || !body.has("occupiable") || body.get("occupiable").isJsonNull()) search.unknown++;
                    if (clock.getAsLong() >= deadline) { search.partial = true; break; }
                    search.nextOffset = index + 1; continue;
                }
                Vec3 eye = candidate.eye(frame);
                for (int side : request.sides) {
                    if (candidate.side >= 0 && candidate.side != side) continue;
                    if (clock.getAsLong() >= deadline || search.rays >= request.maxRays()) {
                        search.partial = true; search.stopReason = search.rays >= request.maxRays() ? "ray_limit" : "tick_budget"; break;
                    }
                    ForgeDirection face = ForgeDirection.getOrientation(side);
                    int x = request.x, y = request.y, z = request.z;
                    if ("place_block".equals(request.intent)) { x -= face.offsetX; y -= face.offsetY; z -= face.offsetZ; }
                    if (y < 0 || y > 255 || x < WorldScan.WORLD_MIN || x > WorldScan.WORLD_MAX || z < WorldScan.WORLD_MIN || z > WorldScan.WORLD_MAX) continue;
                    if (!access.loaded(x, y, z)) { search.unknown++; continue; }
                    NativePlacementAdapter.State state = access.state(x, y, z);
                    if (JsonData.string(state.description, "registryName").indexOf(':') < 1) { search.unknown++; continue; }
                    if ("place_block".equals(request.intent) && (state.air || state.replaceable)) continue;
                    double outward = (eye.xCoord - x - .5 - face.offsetX * .5) * face.offsetX
                            + (eye.yCoord - y - .5 - face.offsetY * .5) * face.offsetY + (eye.zCoord - z - .5 - face.offsetZ * .5) * face.offsetZ;
                    Vec3 point = NativePlacementAdapter.facePoint(x, y, z, side);
                    // A multipart face can be inset behind the host cube's plane. Its bounded native
                    // shape sampler checks the real face, reach and part identity below.
                    if (request.expectedPart == null && (outward <= .001
                            || NativePlacementAdapter.distanceToFace(eye, x, y, z, side) > distanceBound(frame, search))) continue;
                    if (!NativePlacementAdapter.loadedRay(access, eye, point, y)) { search.unknown++; continue; }
                    rays.wrenchFacing = request.wrenchFacing;
                    rays.hitPoint = request.hitPoint;
                    MovingObjectPosition hit = rays.face(eye, distanceBound(frame, search), x, y, z, side, request.expectedPart);
                    search.rays = rays.rays;
                    if (rays.stopReason != null) { search.partial = true; search.stopReason = rays.stopReason; break; }
                    if (clock.getAsLong() >= deadline) { search.partial = true; break; }
                    if (hit == null) continue;
                    if (!request.sharedTargets.isEmpty()) {
                        search.sharedServerViews = SharedApproach.serverViews(access, rays, frame, candidate, search, hit, clock, deadline);
                        search.rays = rays.rays;
                        if (search.sharedServerViews == null) break;
                    }
                    search.selected = candidate; search.body = body; search.attachment = state.description; search.placementBodyProof = placementProof;
                    search.attachment.addProperty("side", side);
                    search.serverRay = rayEvidence("integrated_server", request.dimension, flight.get("worldTick").getAsLong(), hit, state.description);
                    return search;
                }
                if (search.partial) break;
                search.nextOffset = index + 1;
            }
            if (search.partial) return search;
            if (pass + 1 < request.samplingPasses()) search.nextOffset = request.offset;
        }
        return search;
    }
    static double distanceBound(Frame frame, Search search) {
        return Math.min(Math.min(frame.reach, search.serverReach), frame.request.maxDistance);
    }
    private static boolean groundedTeleport(Request request) {
        return "developer_teleport".equals(request.movementStrategy) && !request.requireFlying;
    }
    private static boolean safe(JsonObject body, Request request) {
        return groundedTeleport(request) ? PlayerNavigation.teleportSupportProven(body) : safe(body, request.requireFlying);
    }
    public static boolean safe(JsonObject body, boolean flying) {
        return yes(body, "loaded") && yes(body, "occupiable") && no(body, "entityCoveragePartial")
                && no(body, "liquid") && no(body, "hazard") && "observed".equals(body.has("status") ? body.get("status").getAsString() : "")
                && (flying || yes(body, "canStand"));
    }
    static void requireIdentity(Request request, JsonObject identity) {
        AePartAdapter.requirePresent(request.expectedPart, identity);
        String actualRegistry = JsonData.string(identity, "registryName");
        if (actualRegistry.indexOf(':') < 1) throw new BridgeException("target_unverified", "Approach block has no actual registered identity.");
        if (request.expectedRegistry != null && (!request.expectedRegistry.equals(JsonData.string(identity, "registryName"))
                || request.expectedMetadata >= 0 && request.expectedMetadata != identity.get("metadata").getAsInt()))
            throw new BridgeException("target_unverified", "Approach target differs from the requested actual block registry identity or metadata.");
        if (request.expectedMetaTileId >= 0 || request.expectedMetaClass != null) {
            JsonObject gt = identity.has("gregtech") && identity.get("gregtech").isJsonObject() ? identity.getAsJsonObject("gregtech") : null;
            if (gt == null || request.expectedMetaTileId >= 0 && (!gt.has("metaTileId") || gt.get("metaTileId").getAsInt() != request.expectedMetaTileId)
                    || request.expectedMetaClass != null && (!gt.has("metaClass") || !request.expectedMetaClass.equals(gt.get("metaClass").getAsString())))
                throw new BridgeException("target_unverified", "Approach target does not expose the requested current GregTech identity.");
        }
    }
    public static void sameIdentity(JsonObject expected, JsonObject actual) {
        if (expected.has("parts") != actual.has("parts") || expected.has("parts") && !expected.get("parts").equals(actual.get("parts")))
            throw new BridgeException("target_unverified", "Client/server mounted part identities changed or do not agree.");
        if (!JsonData.string(expected, "registryName").equals(JsonData.string(actual, "registryName"))
                || expected.get("metadata").getAsInt() != actual.get("metadata").getAsInt())
            throw new BridgeException("target_unverified", "Client and server approach block identities do not agree.");
        boolean expectedGt = expected.has("gregtech"), actualGt = actual.has("gregtech");
        if (expectedGt != actualGt) throw new BridgeException("target_unverified", "Client and server loaded tile identities are not both available.");
        if (expectedGt) {
            JsonObject first = expected.getAsJsonObject("gregtech"), second = actual.getAsJsonObject("gregtech");
            if (!first.has("metaTileId") || !second.has("metaTileId") || !first.has("metaClass") || !second.has("metaClass")
                    || first.get("metaTileId").getAsInt() != second.get("metaTileId").getAsInt()
                    || !first.get("metaClass").getAsString().equals(second.get("metaClass").getAsString()))
                throw new BridgeException("target_unverified", "Client and server current GregTech metaTileId/metaClass do not agree.");
        }
    }
    /** Client world time is observation data; only the bridge-owned client counter orders these phases. */
    public static void clientPhaseClock(JsonObject data, long captureTick, long recheckTick, long afterTick) {
        if (captureTick < 0 || recheckTick < captureTick || afterTick != recheckTick)
            throw new BridgeException("stale_world", "The owning-client approach phase clock changed before its final AFTER snapshot.");
        JsonObject current = data.getAsJsonObject("current");
        current.addProperty("clientPhaseClockVersion", CLIENT_PHASE_CLOCK_VERSION);
        current.addProperty("clientPhaseClockSource", CLIENT_PHASE_CLOCK_SOURCE);
        current.addProperty("captureClientTick", captureTick); current.addProperty("recheckClientTick", recheckTick);
    }
    public static JsonObject finish(Access client, Frame frame, Search search, long clientTick, LongSupplier clock) {
        Request request = frame.request;
        long observedAt = clock.getAsLong(), deadline = observedAt + WORK_NANOS;
        long age = Math.max(0, observedAt - search.serverObservedAt);
        if (age > 1000000000L) throw new BridgeException("stale_world", "Authoritative approach space and flight observations are no longer fresh.");
        search.flight.addProperty("ageMillis", age / 1000000D);
        if (!client.loaded(request.x, request.y, request.z)) throw new BridgeException("chunk_unloaded", "Approach target unloaded on the client.");
        JsonObject clientTarget = client.state(request.x, request.y, request.z).description;
        sameIdentity(search.target, clientTarget); requireIdentity(request, clientTarget);
        JsonObject result = new JsonObject();
        result.addProperty("source", "client_server_client"); result.addProperty("sessionId", frame.session); result.addProperty("dimension", request.dimension);
        result.addProperty("expectedWorld", request.expectedWorld); result.addProperty("intent", request.intent); result.addProperty("pattern", request.pattern);
        if (request.currentOnly) result.addProperty("currentOnly", true);
        if (request.movementStrategy != null) {
            result.addProperty("movementStrategy", request.movementStrategy);
            result.addProperty("movementStrategyVersion", MOVEMENT_STRATEGY_VERSION);
            result.addProperty("movementStrategyScope", "endpoint_compatibility_only");
        }
        if (request.operationPosture != null) {
            result.addProperty("operationPosture", request.operationPosture);
            result.addProperty("operationPostureVersion", OPERATION_POSTURE_VERSION);
            result.addProperty("candidateMode", "actual_current_y_hover");
        }
        if (request.useNormalReach) result.addProperty("useNormalReach", true);
        if (request.excludedBuildEnvelope != null) {
            JsonObject b = PlayerNavigation.bounds(request.excludedBuildEnvelope);
            b.addProperty("maxX", (int) request.excludedBuildEnvelope.maxX - 1); b.addProperty("maxY", (int) request.excludedBuildEnvelope.maxY - 1);
            b.addProperty("maxZ", (int) request.excludedBuildEnvelope.maxZ - 1);
            b.addProperty("minX", (int) request.excludedBuildEnvelope.minX); b.addProperty("minY", (int) request.excludedBuildEnvelope.minY); b.addProperty("minZ", (int) request.excludedBuildEnvelope.minZ);
            result.add("excludedBuildEnvelope", b); result.addProperty("buildEnvelopeVersion", 1);
        }
        result.addProperty("readOnly", true); result.addProperty("nonAtomic", true); result.addProperty("submitted", false);
        result.add("target", search.target); result.add("serverFlight", search.flight);
        if ("place_block".equals(request.intent)) {
            result.addProperty("placementBodyExclusionVersion", 1);
            if (search.currentPlacementBodyProof != null) result.add("currentServerPlacementBodyProof", search.currentPlacementBodyProof);
        }
        JsonObject current = point(frame.x, frame.feetY, frame.z); current.addProperty("feetY", frame.feetY); current.addProperty("eyeY", frame.feetY + frame.eyeOffset);
        current.add("actualActorBody", PlayerNavigation.bounds(frame.actorBody));
        current.addProperty("eyeOffset", frame.eyeOffset); current.addProperty("width", frame.width); current.addProperty("height", frame.height);
        current.addProperty("normalReachVersion", 1); current.addProperty("reachPlayerId", frame.playerId);
        current.addProperty("clientNormalReach", frame.reach); current.addProperty("serverNormalReach", search.serverReach);
        current.addProperty("normalReach", Math.min(frame.reach, search.serverReach)); current.addProperty("maxDistance", distanceBound(frame, search));
        current.addProperty("selectedHotbarSlot", frame.selectedSlot); current.addProperty("clientFlying", frame.flying); current.addProperty("sneaking", frame.sneaking);
        current.addProperty("yaw", frame.yaw); current.addProperty("pitch", frame.pitch); current.addProperty("screenId", frame.screenId);
        current.addProperty("screenOpen", frame.screenOpen); current.addProperty("captureWorldTick", frame.captureWorldTick); current.addProperty("recheckWorldTick", clientTick);
        current.addProperty("cursorEmpty", frame.cursorEmpty); current.add("item", new JsonParser().parse(frame.item)); result.add("current", current);
        current.add("sourceItem", new JsonParser().parse(frame.sourceItem)); current.addProperty("requestedHotbarSlot", request.slot >= 0 ? request.slot : frame.selectedSlot);
        String status = search.partial ? "partial" : search.unknown > 0 ? "unknown" : "no_candidate";
        int clientRayLimit = request.sharedTargets.isEmpty() ? MAX_CLIENT_RAYS : MAX_CLIENT_RAYS * request.sharedTargets.size();
        RayAccess clientRays = new RayAccess(client, clock, deadline, 0, clientRayLimit);
        clientRays.nativeViewReach = Math.min(frame.reach, search.serverReach);
        clientRays.expectedPlacementFacing = request.expectedPlacementFacing;
        clientRays.wrenchFacing = request.wrenchFacing;
        clientRays.hitPoint = request.hitPoint;
        if (request.expectedPlacementFacing != null) result.addProperty("expectedPlacementFacing",request.expectedPlacementFacing);
        JsonObject selected = null;
        if (search.selected != null && !search.partial) {
            Candidate candidate = search.selected; JsonObject attachment = search.attachment;
            if (!request.compatible(frame, candidate))
                throw new BridgeException("strategy_incompatible", "The selected endpoint is incompatible with the explicit movement strategy at the fresh actual feetY; no route or arrival is inferred.");
            JsonObject clientPlacementProof = "place_block".equals(request.intent) ? NativePlacementAdapter.placementBodyProof(
                    client.placementBody(candidate.feetX, candidate.feetY, candidate.feetZ, request.currentOnly), request.x, request.y, request.z) : null;
            int x = attachment.get("x").getAsInt(), y = attachment.get("y").getAsInt(), z = attachment.get("z").getAsInt(), side = attachment.get("side").getAsInt();
            if (!client.loaded(x, y, z)) throw new BridgeException("chunk_unloaded", "Planned attachment unloaded on the client.");
            sameIdentity(attachment, client.state(x, y, z).description);
            JsonObject body = candidate.space(client, frame, deadline);
            if (clock.getAsLong() >= deadline) { search.partial = true; status = "partial"; }
            else if (candidate.buildExcluded(frame) || candidate.placementExcluded(frame) || clientPlacementProof != null && clientPlacementProof.get("overlapsTarget").getAsBoolean() || !safe(body, request)) {
                status = "client_space_unverified";
                JsonObject rejected = new JsonObject();
                rejected.add("feet", point(candidate.feetX, candidate.feetY, candidate.feetZ));
                rejected.addProperty("buildEnvelopeOverlap", candidate.buildExcluded(frame));
                rejected.addProperty("capturedBodyTargetOverlap", candidate.placementExcluded(frame));
                rejected.addProperty("clientSpaceSafe", safe(body, request));
                rejected.add("serverBody", compactBody(search.body, "integrated_server", request.dimension, search.flight.get("worldTick").getAsLong()));
                rejected.add("clientBody", compactBody(body, "client", request.dimension, clientTick));
                if (clientPlacementProof != null) {
                    rejected.add("clientPlacementBodyProof", clientPlacementProof);
                    rejected.add("serverPlacementBodyProof", search.placementBodyProof);
                }
                result.add("rejectedCandidate", rejected);
            }
            else {
                Vec3 eye = candidate.eye(frame);
                MovingObjectPosition hit = null;
                if (NativePlacementAdapter.loadedRay(client, eye, NativePlacementAdapter.facePoint(x, y, z, side), y)) {
                    hit = clientRays.face(eye, distanceBound(frame, search), x, y, z, side, request.expectedPart, search.serverRay);
                }
                if (clientRays.stopReason != null || clock.getAsLong() >= deadline) {
                    search.partial = true; status = "partial";
                    search.stopReason = clientRays.stopReason == null ? "tick_budget" : clientRays.stopReason;
                }
                else if (hit == null) status = "client_face_unverified";
                else {
                    status = "ready"; selected = new JsonObject(); selected.add("feet", point(candidate.feetX, candidate.feetY, candidate.feetZ));
                    selected.add("eye", point(eye.xCoord, eye.yCoord, eye.zCoord)); selected.add("attachment", attachment);
                    selected.add("hit", point(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord));
                    double dx = hit.hitVec.xCoord - eye.xCoord, dy = hit.hitVec.yCoord - eye.yCoord, dz = hit.hitVec.zCoord - eye.zCoord;
                    JsonObject aim = new JsonObject(); aim.addProperty("yaw", Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    aim.addProperty("pitch", -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))); selected.add("aim", aim);
                    selected.addProperty("moveRequired", frame.x != candidate.feetX || frame.feetY != candidate.feetY || frame.z != candidate.feetZ);
                    selected.add("serverBody", compactBody(search.body, "integrated_server", request.dimension, search.flight.get("worldTick").getAsLong()));
                    selected.add("clientBody", compactBody(body, "client", request.dimension, clientTick));
                    selected.addProperty("unknownChecks", 0);
                    if ("place_block".equals(request.intent)) {
                        selected.add("clientPlacementBodyProof", clientPlacementProof); selected.add("serverPlacementBodyProof", search.placementBodyProof);
                    }
                    selected.add("serverRay", search.serverRay); selected.add("clientRay", rayEvidence("client", request.dimension, clientTick, hit, attachment));
                }
            }
        }
        if (selected != null && !request.sharedTargets.isEmpty()) {
            JsonArray views = SharedApproach.clientViews(client, clientRays, frame, search, selected, clientTick, clock, deadline);
            if (views == null) { selected = null; status = search.partial ? "partial" : "client_face_unverified"; }
            else {
                selected.add("targetViews", views);
                selected.addProperty("alreadyInRange", !selected.get("moveRequired").getAsBoolean());
            }
        }
        if (!request.sharedTargets.isEmpty()) {
            result.addProperty("sharedInteractionVersion", 1);
            result.addProperty("sharedCandidateVersion", 3);
            result.addProperty("nonWalkingCandidateHeights", 5);
            result.addProperty("walkingMaxJumpBlocks", NavigationSettings.maxJumpBlocks);
            result.addProperty("walkingMaxDropBlocks", NavigationSettings.maxDropBlocks);
            result.addProperty("targetCount", request.sharedTargets.size());
            result.add("targets", search.sharedIdentities);
            result.add("targetMidpoint", SharedApproach.midpoint(frame));
        }
        result.addProperty("status", status); result.add("selected", selected == null ? JsonNull.INSTANCE : selected);
        result.addProperty("stopReason", "ready".equals(status) ? "first_verified_candidate" : "partial".equals(status)
                ? search.stopReason == null ? "tick_budget" : search.stopReason : status);
        result.addProperty("complete", !search.partial && search.samplingPass == request.samplingPasses()-1 && search.nextOffset >= request.total()); result.addProperty("partial", search.partial);
        result.addProperty("clientWorldTick", clientTick);
        JsonObject coverage = new JsonObject(); coverage.addProperty("candidateSlots", request.total()); coverage.addProperty("offset", request.offset);
        coverage.addProperty("placementBodyExcluded", search.placementBodyExcluded);
        if (request.movementStrategy != null) coverage.addProperty("strategyIncompatibleCandidates", search.strategyIncompatible);
        coverage.addProperty("candidatesExamined", search.candidates); coverage.addProperty("serverRays", search.rays); coverage.addProperty("unknownChecks", search.unknown);
        coverage.addProperty("clientRays", clientRays.rays); coverage.addProperty("maxClientRays", clientRayLimit);
        coverage.addProperty("serverPages", search.pages); coverage.addProperty("maxServerPages", request.maxPages());
        coverage.addProperty("candidateAttempts", search.candidateAttempts); coverage.addProperty("maxCandidateAttempts",
                request.sharedTargets.isEmpty() ? MAX_CANDIDATES * request.samplingPasses() + MAX_SERVER_PAGES - 1 : request.total() + request.maxPages() - 1);
        if (request.samplingPasses() == 2) { coverage.addProperty("samplingPass", search.samplingPass); coverage.addProperty("samplingPasses", 2); }
        coverage.addProperty("normalDistanceBound", distanceBound(frame, search)); coverage.addProperty("workNanosPerPhase", WORK_NANOS);
        coverage.addProperty("faceReachModel", "Whole face nearest-distance eligibility; actual native surface hits prove reach and visibility. Bounded shape/occlusion sampling never proves exhaustive absence.");
        coverage.addProperty("maxCandidates", request.sharedTargets.isEmpty() ? MAX_CANDIDATES : request.total()); coverage.addProperty("maxServerRays", request.maxRays());
        boolean multipart = request.expectedPart != null;
        for (Request target : request.sharedTargets) multipart |= target.expectedPart != null;
        coverage.addProperty("rayCountingVersion", RAY_COUNTING_VERSION); coverage.addProperty("maxRaysPerFace", multipart ? 1 + 64 * 10 : 1 + MAX_RAYS_PER_FACE);
        if (multipart) coverage.addProperty("partShapeSamplingVersion", 1);
        coverage.addProperty("rayCounting", "Actual native Access.trace invocations, including shape fallback samples; server total is shared across continuation pages.");
        coverage.addProperty("budgetEnforcement", "Check deadline before and after each sampled loaded/state/trace callback. A native callback already running cannot be preempted; its late result is rejected and no further sampled callback starts.");
        coverage.addProperty("observation", "Only this bounded read was observed, with at most " + request.maxPages() + " separately queued server pages of four milliseconds each. Earlier unknown observations remain counted; selected proofs are fresh and complete. Native block faces and actual loaded body regions; no pathfinding or exhaustive mod hazard proof. Recheck flight, target, body and native ray at action time.");
        result.add("coverage", coverage);
        result.add("nextOffset", search.partial ? new com.google.gson.JsonPrimitive(search.nextOffset) : JsonNull.INSTANCE);
        return result;
    }
    private static JsonObject compactBody(JsonObject body, String source, int dimension, long tick) {
        JsonObject result = new JsonObject();
        for (String key : new String[] {"body", "loaded", "status", "occupiable", "canStand", "fullSupportCoverage", "supportContactVersion", "continuousSupportCoverage", "minimumContactPerAxis", "supported", "collisionCount", "entityCoveragePartial", "liquid", "hazard"})
            if (body.has(key)) result.add(key, body.get(key));
        result.addProperty("source", source); result.addProperty("dimension", dimension); result.addProperty("worldTick", tick);
        return result;
    }
    static JsonObject rayEvidence(String source, int dimension, long tick, MovingObjectPosition hit, JsonObject identity) {
        JsonObject result = new JsonObject(); result.addProperty("source", source); result.addProperty("dimension", dimension); result.addProperty("worldTick", tick);
        result.addProperty("native", true); result.addProperty("verified", true); result.addProperty("status", "observed");
        JsonObject block = new JsonObject(); block.addProperty("x", hit.blockX); block.addProperty("y", hit.blockY); block.addProperty("z", hit.blockZ);
        result.add("block", block); result.addProperty("side", hit.sideHit);
        result.add("hit", point(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord));
        result.addProperty("registryName", JsonData.string(identity, "registryName")); result.add("metadata", identity.get("metadata"));
        if (identity.has("gregtech")) result.add("gregtech", identity.get("gregtech"));
        return result;
    }
    private static boolean yes(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean(); }
    private static boolean no(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() && !value.get(key).getAsBoolean(); }
    private static JsonObject point(double x, double y, double z) { JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z); return point; }
}
