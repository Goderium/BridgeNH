package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.AxisAlignedBB;

/** One collision-proved native jump or bounded walk-off, also used by grounded route execution. */
public final class GroundedStep {
    static final double RISE = 1.5, MARGIN = .18;
    static final int MAX_TRACE = 32;
    static final int FORWARD_QUIET_TICKS = 7;
    public final PlayerNavigation.Point start, target;
    public final int dx, dz;
    final double dy;
    final double width, height;
    public final AxisAlignedBB envelope, contact;
    public boolean clientEnvelope, serverEnvelope, clientStart, serverStart, clientLanding, serverLanding;
    public boolean clientAir, serverAir, dispatched, partial;
    public int requestedJumpEdges, nativeJumpEdges, geometrySamples, partialReads;
    long lastTick = -1, lastSample = -1, dispatchSample = -1;
    boolean nativeJumpDown;
    long nativeForwardTick = -1, lastForwardOnsetTick = -1, lastForwardDeferredTick = -1;
    boolean nativeForwardDown;
    int forwardQuietTicks, nativeForwardOnsets, forwardDeferrals, minimumForwardOnsetGap = Integer.MAX_VALUE;
    volatile JsonObject postureRejection;
    volatile JsonObject geometryRejection;
    String phase = "geometry_proof";
    final List<JsonObject> trace = new ArrayList<JsonObject>();
    public AxisAlignedBB clientPrevious, serverPrevious;

    public GroundedStep(PlayerNavigation.Point start, PlayerNavigation.Point target, double width, double height) {
        validate(start, target);
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || width > .9 || height <= 0 || height > 2.1)
            throw new BridgeException("unsupported", "walking_step requires the actual bounded upright native body.");
        this.start = start; this.target = target; this.width = width; this.height = height;
        dy = target.y - start.y;
        dx = (int) Math.rint(target.x - (Math.floor(start.x) + .5));
        dz = (int) Math.rint(target.z - (Math.floor(start.z) + .5));
        double floor = Math.min(start.y, target.y), top = dy > 0 ? start.y + RISE : start.y;
        envelope = AxisAlignedBB.getBoundingBox(Math.min(start.x, target.x) - width / 2 - MARGIN,
                floor + .00001, Math.min(start.z, target.z) - width / 2 - MARGIN,
                Math.max(start.x, target.x) + width / 2 + MARGIN, top + height,
                Math.max(start.z, target.z) + width / 2 + MARGIN);
        if (dy < 0) {
            // A normal walk-off needs no corridor behind its starting support cell. Padding
            // an off-center start across that face can falsely include a neighboring column.
            // This narrows the permitted body envelope; actual/current/swept proofs still reject
            // every collision outside the independently proved elevated support contact.
            if (dx > 0) envelope.minX = Math.max(envelope.minX, Math.floor(start.x));
            else if (dx < 0) envelope.maxX = Math.min(envelope.maxX, Math.floor(start.x) + 1);
            if (dz > 0) envelope.minZ = Math.max(envelope.minZ, Math.floor(start.z));
            else if (dz < 0) envelope.maxZ = Math.min(envelope.maxZ, Math.floor(start.z) + 1);
        }
        PlayerNavigation.Point elevated = dy > 0 ? target : start;
        contact = AxisAlignedBB.getBoundingBox(Math.floor(elevated.x), floor, Math.floor(elevated.z),
                Math.floor(elevated.x) + 1, floor + Math.abs(dy), Math.floor(elevated.z) + 1);
    }

    static void validate(PlayerNavigation.Point start, PlayerNavigation.Point target) {
        double sx = Math.floor(start.x) + .5, sz = Math.floor(start.z) + .5;
        double x = target.x - sx, z = target.z - sz, y = target.y - start.y;
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(y)
                || Math.abs(start.x - sx) > MARGIN || Math.abs(start.z - sz) > MARGIN
                || Math.abs(target.x - Math.floor(target.x) - .5) > .0001
                || Math.abs(target.z - Math.floor(target.z) - .5) > .0001
                || Math.abs(y) < .125 || y>NavigationSettings.maxJumpBlocks || -y>NavigationSettings.maxDropBlocks
                || !((Math.abs(Math.abs(x) - 1) < .0001 && Math.abs(z) < .0001)
                    || (Math.abs(Math.abs(z) - 1) < .0001 && Math.abs(x) < .0001)))
            throw new BridgeException("unsupported", "Grounded height transitions require one centered cardinal cell and configured native jump/drop limits.");
    }

    public AxisAlignedBB at(AxisAlignedBB nativeBody, PlayerNavigation.Point actual, PlayerNavigation.Point point) {
        return PlayerNavigation.translatedFlightBody(nativeBody, actual,
                new FlightPathPlanner.Sweep(PlayerNavigation.flightPoint(point), PlayerNavigation.flightPoint(point), width, height), width, height);
    }

    public static boolean contained(AxisAlignedBB inner, AxisAlignedBB outer) {
        return inner.minX >= outer.minX - .0001 && inner.minY >= outer.minY - .0001 && inner.minZ >= outer.minZ - .0001
                && inner.maxX <= outer.maxX + .0001 && inner.maxY <= outer.maxY + .0001 && inner.maxZ <= outer.maxZ + .0001;
    }

    /** Native support contact is allowed only inside the single independently proved elevated support cell. */
    public boolean envelopeClear(JsonObject observation) {
        if (!"observed".equals(PlayerNavigation.text(observation, "status"))) return false;
        for (String key : new String[] {"loaded", "entityCoveragePartial", "liquid", "hazard", "collisionsTruncated"})
            if (PlayerNavigation.flag(observation, key) != "loaded".equals(key)) return false;
        JsonElement blockers = observation.get("blockingEntities"), boxes = observation.get("collisions");
        if (blockers == null || !blockers.isJsonArray() || blockers.getAsJsonArray().size() != 0
                || boxes == null || !boxes.isJsonArray() || observation.get("collisionCount") == null
                || !observation.get("collisionCount").isJsonPrimitive()
                || observation.get("collisionCount").getAsInt() != boxes.getAsJsonArray().size()) return false;
        for (JsonElement value : boxes.getAsJsonArray()) {
            if (!value.isJsonObject()) return false;
            JsonObject b = value.getAsJsonObject();
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(JsonData.number(b, "minX", -30000000, 30000000),
                    JsonData.number(b, "minY", 0, 256), JsonData.number(b, "minZ", -30000000, 30000000),
                    JsonData.number(b, "maxX", -30000000, 30000000), JsonData.number(b, "maxY", 0, 256),
                    JsonData.number(b, "maxZ", -30000000, 30000000));
            if (!contained(box, contact)) return false;
        }
        return true;
    }

    void observe(JsonObject snapshot, boolean nativeJump, long tick) {
        JsonObject player = PlayerNavigation.object(snapshot, "player"), control = PlayerNavigation.object(snapshot, "serverControl");
        JsonObject server = PlayerNavigation.object(control, "pose");
        long sample = PlayerNavigation.sample(control);
        if (!dispatched && nativeJump) throw new BridgeException("step_input_changed", "walking_step requires native jump released before its one owned edge.");
        if (tick != lastTick) {
            if (nativeJump && !nativeJumpDown) nativeJumpEdges++;
            nativeJumpDown = nativeJump; lastTick = tick;
        }
        if (nativeJumpEdges > (dy > 0 ? 1 : 0))
            throw new BridgeException("step_input_changed", "walking_step observed an extra native jump edge; no jump is repeated.");
        for (JsonObject pose : new JsonObject[] {player, server}) {
            if (PlayerNavigation.flag(pose, "flying")) throw new BridgeException("invalid_state", "walking_step lost actual nonflying survival.");
            double mx = PlayerNavigation.motion(pose, "motionX"), my = PlayerNavigation.motion(pose, "motionY"), mz = PlayerNavigation.motion(pose, "motionZ");
            if (Math.hypot(mx, mz) > .45 || my > .55 || my < -Math.max(.8, Math.sqrt(.16*Math.abs(dy))+.25) || dy < 0 && my > .08)
                throw new BridgeException("unsupported", "Actual step motion exceeds its bounded normal-key envelope; no physics/motion write.");
            PlayerNavigation.Point point = PlayerNavigation.pose(pose);
            if (point.y < Math.min(start.y, target.y) - .02 || point.y > start.y + (dy > 0 ? RISE : .02)
                    || point.horizontal(start) > 1.65 || Math.abs((point.x - target.x) * dz - (point.z - target.z) * dx) > MARGIN + .02)
                throw new BridgeException("unsupported", "Actual native step left its declared one-cell corridor.");
        }
        if (!dispatched) {
            if (!PlayerNavigation.flag(player, "onGround") || !PlayerNavigation.flag(server, "onGround")
                    || !PlayerNavigation.pose(player).near(start) || !PlayerNavigation.pose(server).near(start)
                    || Math.hypot(PlayerNavigation.motion(player, "motionX"), PlayerNavigation.motion(player, "motionZ")) > .02
                    || Math.hypot(PlayerNavigation.motion(server, "motionX"), PlayerNavigation.motion(server, "motionZ")) > .02)
                throw new BridgeException("invalid_state", "walking_step requires a stable actually grounded client/server start.");
        } else {
            if (!PlayerNavigation.flag(player, "onGround")) clientAir = true;
            if (sample > dispatchSample && !PlayerNavigation.flag(server, "onGround")) serverAir = true;
            phase = clientAir && serverAir ? "transit" : dy > 0 ? "jump_requested" : "walk_off_requested";
            if (clientAir && serverAir && PlayerNavigation.flag(player, "onGround") && PlayerNavigation.flag(server, "onGround")) phase = "landing";
        }
        if (sample != lastSample || trace.isEmpty()) {
            JsonObject row = new JsonObject(); row.addProperty("tick", tick); row.addProperty("sampleId", sample); row.addProperty("phase", phase);
            row.add("client", PlayerNavigation.pose(player).json()); row.add("server", PlayerNavigation.pose(server).json());
            row.addProperty("clientOnGround", PlayerNavigation.flag(player, "onGround")); row.addProperty("serverOnGround", PlayerNavigation.flag(server, "onGround"));
            row.addProperty("nativeJumpEdges", nativeJumpEdges);
            for (String field : new String[] {"motionX", "motionY", "motionZ"}) { row.addProperty("client_" + field, PlayerNavigation.motion(player, field)); row.addProperty("server_" + field, PlayerNavigation.motion(server, field)); }
            row.addProperty("clientFlying", false); row.addProperty("serverFlying", false);
            if (trace.size() == MAX_TRACE) trace.remove(0);
            trace.add(row); lastSample = sample;
        }
    }

    /** Observe completed client native ticks only, never HTTP/render callbacks or server sample IDs. */
    public void observeForwardTick(long tick, float forward) {
        if (tick < 0 || !Float.isFinite(forward))
            throw new BridgeException("invalid_state", "walking_step requires a finite actual native movement input tick.");
        if (tick <= nativeForwardTick) return;
        boolean consecutive = nativeForwardTick >= 0 && tick == nativeForwardTick + 1;
        if (!consecutive) forwardQuietTicks = 0;
        boolean down = forward >= .8F;
        if (down) {
            if (!consecutive || !nativeForwardDown) {
                if (consecutive && lastForwardOnsetTick >= 0)
                    minimumForwardOnsetGap = Math.min(minimumForwardOnsetGap, (int) Math.min(Integer.MAX_VALUE, tick - lastForwardOnsetTick));
                lastForwardOnsetTick = tick; nativeForwardOnsets++;
            }
            forwardQuietTicks = 0;
        } else forwardQuietTicks = Math.min(FORWARD_QUIET_TICKS, forwardQuietTicks + 1);
        nativeForwardDown = down; nativeForwardTick = tick;
    }

    /** Vanilla rearms double-tap sprint after seven native ticks. Continuous forward creates no new edge. */
    public Set<String> forwardSafeKeys(Set<String> requested, long tick) {
        Set<String> safe = new LinkedHashSet<String>(requested);
        boolean fresh = nativeForwardTick >= tick - 1 && nativeForwardTick <= tick;
        if (safe.contains("forward") && !(fresh && (nativeForwardDown || forwardQuietTicks == FORWARD_QUIET_TICKS))) {
            safe.remove("forward");
            // A +1 jump must not depart in place while its first forward edge is still deferred.
            if (!dispatched) { safe.clear(); phase = "native_forward_window"; }
            if (tick != lastForwardDeferredTick) { lastForwardDeferredTick = tick; forwardDeferrals++; }
        }
        return safe;
    }

    void requirePosture(String side, boolean creative, boolean flying, boolean sneaking, boolean sprinting,
            boolean jumpBoost, double actualWidth, double actualHeight, AxisAlignedBB body) {
        requirePosture(side, creative, flying, sneaking, sprinting, jumpBoost, actualWidth, actualHeight, body, false);
    }
    public void requirePosture(String side, boolean creative, boolean flying, boolean sneaking, boolean sprinting,
            boolean jumpBoost, double actualWidth, double actualHeight, AxisAlignedBB body, boolean allowCreative) {
        if ((!creative || allowCreative) && !flying && !sneaking && !sprinting && !jumpBoost && actualWidth == width && actualHeight == height) return;
        JsonObject rejected = new JsonObject(), flags = new JsonObject(), expected = new JsonObject(), actual = new JsonObject();
        rejected.addProperty("version", 1); rejected.addProperty("side", side);
        flags.addProperty("creative", creative); flags.addProperty("flying", flying); flags.addProperty("sneaking", sneaking);
        flags.addProperty("sprinting", sprinting); flags.addProperty("jumpBoost", jumpBoost);
        flags.addProperty("widthChanged", actualWidth != width); flags.addProperty("heightChanged", actualHeight != height);
        expected.addProperty("entityWidth", width); expected.addProperty("entityHeight", height);
        actual.addProperty("entityWidth", actualWidth); actual.addProperty("entityHeight", actualHeight);
        if (body != null) actual.add("nativeBody", PlayerNavigation.bounds(body));
        rejected.add("flags", flags); rejected.add("expected", expected); rejected.add("actual", actual); postureRejection = rejected;
        throw new BridgeException("unsupported", "walking_step requires the unchanged actual upright nonsprinting/nonsneaking survival body without jump boost or flight.");
    }

    Set<String> next(JsonObject snapshot) {
        Set<String> result = new LinkedHashSet<String>();
        JsonObject player = PlayerNavigation.object(snapshot, "player"); PlayerNavigation.Point actual = PlayerNavigation.pose(player);
        if (dy > 0 && nativeJumpEdges == 0) result.add("jump");
        double error = (target.x - actual.x) * dx + (target.z - actual.z) * dz;
        double velocity = PlayerNavigation.motion(player, "motionX") * dx + PlayerNavigation.motion(player, "motionZ") * dz;
        if (error > .08) result.add(velocity > Math.max(.035, error / 2.5) ? "back" : "forward");
        else if (error < -.08) result.add(velocity < Math.min(-.035, error / 2.5) ? "forward" : "back");
        else if (velocity > .02) result.add("back");
        else if (velocity < -.02) result.add("forward");
        // The step heading stays cardinal. Retaining the start's permitted
        // lateral offset can leave a landed body outside radial arrival tolerance
        // even after longitudinal steering stops; correct it using native strafe.
        double lateral = (target.x - actual.x) * dz - (target.z - actual.z) * dx;
        double drift = PlayerNavigation.motion(player, "motionX") * dz - PlayerNavigation.motion(player, "motionZ") * dx;
        if (lateral > .04) result.add(drift > Math.max(.02, lateral / 2.5) ? "right" : "left");
        else if (lateral < -.04) result.add(drift < Math.min(-.02, lateral / 2.5) ? "left" : "right");
        else if (drift > .02) result.add("right");
        else if (drift < -.02) result.add("left");
        return result;
    }

    public void rejectGeometry(String stage, String side, AxisAlignedBB body, JsonObject observation) {
        JsonObject rejected = new JsonObject(), observed = new JsonObject();
        rejected.addProperty("stage", stage); rejected.addProperty("side", side);
        rejected.addProperty("evidenceOnly", true); rejected.add("body", PlayerNavigation.bounds(body));
        for (String key : new String[] {"status", "loaded", "liquid", "hazard", "collisionCount", "collisionsTruncated", "entityCoveragePartial", "canStand"})
            if (observation.has(key)) observed.add(key, observation.get(key));
        for (String key : new String[] {"collisions", "blockingEntities"}) {
            JsonElement value = observation.get(key);
            if (value == null || !value.isJsonArray()) continue;
            JsonArray bounded = new JsonArray(), values = value.getAsJsonArray();
            for (int i = 0; i < Math.min(3, values.size()); i++) bounded.add(values.get(i));
            observed.add(key, bounded); observed.addProperty(key + "EvidenceTruncated", values.size() > 3);
        }
        rejected.add("observation", observed); geometryRejection = rejected;
    }

    void dispatched(Set<String> keys, Set<String> held, long sample) {
        if (keys.contains("jump") && !held.contains("jump")) {
            if (++requestedJumpEdges > 1) throw new BridgeException("unsupported", "walking_step never repeats a jump after release or unknown input.");
        }
        if (!keys.isEmpty() && !dispatched) { dispatchSample = sample; dispatched = true; }
    }

    public boolean arrival(JsonObject snapshot) {
        if (!dispatched || !clientAir || !serverAir || !clientEnvelope || !serverEnvelope || !clientStart || !serverStart
                || !clientLanding || !serverLanding || nativeJumpEdges != (dy > 0 ? 1 : 0)) return false;
        JsonObject client = PlayerNavigation.object(snapshot, "player"), server = PlayerNavigation.object(PlayerNavigation.object(snapshot, "serverControl"), "pose");
        return PlayerNavigation.flag(client, "onGround") && PlayerNavigation.flag(server, "onGround")
                && PlayerNavigation.pose(client).near(target) && PlayerNavigation.pose(server).near(target);
    }

    JsonObject receipt(boolean released, boolean ownerClear, boolean completed, int tail) {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("phase", completed ? "completed" : phase);
        result.addProperty("deltaY", dy); result.addProperty("clientEnvelopeProven", clientEnvelope); result.addProperty("serverEnvelopeProven", serverEnvelope);
        result.addProperty("clientStartSupportProven", clientStart); result.addProperty("serverStartSupportProven", serverStart);
        result.addProperty("clientLandingSupportProven", clientLanding); result.addProperty("serverLandingSupportProven", serverLanding);
        result.addProperty("clientAirObserved", clientAir); result.addProperty("serverAirObserved", serverAir);
        result.addProperty("groundAirGroundConfirmed", completed); result.addProperty("requestedJumpEdges", requestedJumpEdges); result.addProperty("nativeJumpEdges", nativeJumpEdges);
        result.addProperty("geometrySamples", geometrySamples); result.addProperty("keysReleased", released); result.addProperty("ownerClearConfirmed", ownerClear);
        result.addProperty("partial", !completed && (partial || dispatched)); result.addProperty("partialGeometryReads", partialReads); result.addProperty("outcomeUnknown", dispatched && !completed);
        result.addProperty("replayAllowed", false); result.addProperty("fallbackUsed", false); result.addProperty("noAbilityOrMotionWrites", true);
        JsonObject forward = new JsonObject(); forward.addProperty("version", 1); forward.addProperty("observedNativeTick", nativeForwardTick);
        forward.addProperty("requiredQuietTicks", FORWARD_QUIET_TICKS); forward.addProperty("minimumOnsetGapTicks", FORWARD_QUIET_TICKS + 1);
        forward.addProperty("quietTicks", forwardQuietTicks); forward.addProperty("nativeForwardDown", nativeForwardDown);
        forward.addProperty("observedOnsets", nativeForwardOnsets); forward.addProperty("deferredOnsets", forwardDeferrals);
        if (minimumForwardOnsetGap != Integer.MAX_VALUE) forward.addProperty("minimumObservedOnsetGapTicks", minimumForwardOnsetGap);
        result.add("nativeForwardWindow", forward);
        JsonObject rejected = postureRejection;
        if (rejected != null) result.add("postureRejection", new com.google.gson.JsonParser().parse(rejected.toString()));
        JsonObject geometry = geometryRejection;
        if (geometry != null) result.add("geometryRejection", new com.google.gson.JsonParser().parse(geometry.toString()));
        if (clientPrevious != null) result.add("actualClientBody", PlayerNavigation.bounds(clientPrevious));
        if (serverPrevious != null) result.add("actualServerBody", PlayerNavigation.bounds(serverPrevious));
        result.addProperty("geometryScope", "fresh_loaded_full_native_body_and_end_support_with_only_verified_elevated_cell_contact");
        JsonArray rows = new JsonArray(); for (int i = Math.max(0, trace.size() - tail); i < trace.size(); i++) rows.add(trace.get(i)); result.add("trace", rows);
        return result;
    }
}
