package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.structure.NativeGtWrench;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Request-local actual look-ray evidence. A hit releases input; it never certifies safe arrival. */
public final class CrosshairArrival {
    public interface Access extends ApproachPlanner.Access {
        boolean entityOccluded(Vec3 eye, Vec3 end, double blockDistance);
    }

    final ApproachPlanner.Request request;
    private JsonArray identities;
    private volatile Evidence client, server;
    private String reason = "not_observed";
    private long firstHitTick = -1;
    private int hits, losses;

    CrosshairArrival(JsonArray targets, String world, int dimension, String strategy) {
        JsonObject args = new JsonObject();
        args.addProperty("intent", "use"); args.addProperty("expectedWorld", world); args.addProperty("dimension", dimension);
        args.addProperty("movementStrategy", strategy); args.addProperty("requireFlying", "ability_flight".equals(strategy));
        args.addProperty("currentOnly", true); args.addProperty("useNormalReach", true); args.addProperty("maxDistance", 32);
        args.add("targets", new JsonParser().parse(targets.toString()));
        if (targets.size() < 1 || !targets.get(0).isJsonObject()) throw new BridgeException("invalid_args", "Arrival targets require exact block objects.");
        JsonObject first = targets.get(0).getAsJsonObject();
        for (String key : new String[] {"x", "y", "z", "targetIdentity", "allowedSides", "wrenchFacing", "hitPoint"}) if (first.has(key)) args.add(key, first.get(key));
        request = new ApproachPlanner.Request(args, dimension);
    }

    public void capture(ApproachPlanner.Access access) { identities = SharedApproach.identities(access, request); }

    private void identities(ApproachPlanner.Access access) {
        SharedApproach.sameIdentities(identities, SharedApproach.identities(access, request));
    }

    /** Choose any visible allowed face, only once the entire shared target set is in normal reach. */
    public PlayerNavigation.Point aim(Access access, Vec3 eye, double reach, long deadline) {
        if (!withinReach(eye, reach)) return null;
        identities(access);
        MovingObjectPosition primary = null;
        NativePlacementAdapter.Access view = NativePlacementAdapter.nativeView(access, reach);
        for (ApproachPlanner.Request target : request.sharedTargets) {
            MovingObjectPosition found = null;
            for (int side : target.sides) {
                if (System.nanoTime() >= deadline) return null;
                MovingObjectPosition hit = target.hitPoint != null ? InteractionHitPoint.probe(NativePlacementAdapter.forPart(access, target.expectedPart), eye, reach, target.x, target.y, target.z, side, target.hitPoint)
                    : target.wrenchFacing ? NativeGtWrench.probe(view, eye, reach, target.x, target.y, target.z, side)
                    : NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(view, target.expectedPart), eye, reach, target.x, target.y, target.z, side);
                if (hit != null && !access.entityOccluded(eye, hit.hitVec, eye.distanceTo(hit.hitVec))) { found = hit; break; }
            }
            if (found == null) return null;
            if (primary == null) primary = found;
        }
        identities(access);
        return new PlayerNavigation.Point(primary.hitVec.xCoord, primary.hitVec.yCoord, primary.hitVec.zCoord);
    }

    public boolean withinReach(Vec3 eye, double reach) {
        if (!finite(eye) || !Double.isFinite(reach) || reach <= 0 || reach > 32) return false;
        for (ApproachPlanner.Request target : request.sharedTargets)
            if (NativePlacementAdapter.distanceToBlock(eye, target.x, target.y, target.z) > reach) return false;
        return true;
    }

    public boolean observe(Access access, Vec3 eye, Vec3 look, double reach, PlayerNavigation.Point feet,
            float yaw, float pitch, long tick, long deadline, boolean clientOwner, MovingObjectPosition rendered) {
        clear(clientOwner);
        if (!withinReach(eye, reach) || !finite(look)) { reason = "normal_reach_pending"; return false; }
        identities(access);
        ApproachPlanner.Request target = request.sharedTargets.get(0);
        Vec3 end = eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        if (System.nanoTime() >= deadline || !NativePlacementAdapter.loadedRay(access, eye, end, target.y)) {
            reason = "current_ray_geometry_pending"; return false;
        }
        // Vanilla 1.7.10 ray tracing advances its origin Vec3 in place. Keep the real eye
        // untouched for renderer comparison, entity occlusion, shared faces and any final aim.
        MovingObjectPosition hit = access.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),
                Vec3.createVectorHelper(end.xCoord,end.yCoord,end.zCoord));
        if (!matches(target, hit) || !finite(hit.hitVec) || eye.distanceTo(hit.hitVec) > reach + .000001
                || target.expectedPart != null && !AePartAdapter.matches(target.expectedPart, access.selectedPart(hit))) {
            reason = "actual_crosshair_target_or_face_pending"; return false;
        }
        if (clientOwner && !sameHit(hit, rendered)) { reason = "actual_render_crosshair_pending"; return false; }
        if (target.wrenchFacing && NativeGtWrench.probe(NativePlacementAdapter.nativeView(access, reach), eye, reach,
                target.x, target.y, target.z, hit.sideHit) == null) { reason = "wrench_centre_pending"; return false; }
        if (access.entityOccluded(eye, end, eye.distanceTo(hit.hitVec))) { reason = "entity_occlusion_pending"; return false; }
        // Other targets need visible allowed faces at this same actual stand; one crosshair selects the primary.
        for (int index = 1; index < request.sharedTargets.size(); index++) {
            ApproachPlanner.Request other = request.sharedTargets.get(index); boolean visible = false;
            for (int side : other.sides) {
                if (System.nanoTime() >= deadline) { reason = "shared_faces_pending"; return false; }
                MovingObjectPosition secondary = other.hitPoint != null ? InteractionHitPoint.probe(NativePlacementAdapter.forPart(access, other.expectedPart), eye, reach, other.x, other.y, other.z, side, other.hitPoint)
                    : other.wrenchFacing ? NativeGtWrench.probe(NativePlacementAdapter.nativeView(access, reach), eye, reach, other.x, other.y, other.z, side)
                    : NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(access, other.expectedPart), eye, reach, other.x, other.y, other.z, side);
                if (secondary != null && !access.entityOccluded(eye, secondary.hitVec, eye.distanceTo(secondary.hitVec))) { visible = true; break; }
            }
            if (!visible) { reason = "shared_faces_pending"; return false; }
        }
        identities(access);
        if (System.nanoTime() >= deadline) { reason = "current_ray_geometry_pending"; return false; }
        Evidence proof = new Evidence(feet, yaw, pitch, hit.sideHit, tick, System.nanoTime());
        if (clientOwner) { client = proof; if (firstHitTick < 0) firstHitTick = tick; hits++; } else server = proof;
        reason = "actual_crosshair_hit"; return true;
    }

    public void clear(boolean clientOwner) { if (clientOwner) client = null; else server = null; }
    public void lost() { losses++; reason = "actual_hit_lost_resume_original_route"; }
    boolean contains(JsonObject pose, boolean clientOwner) {
        Evidence proof = clientOwner ? client : server;
        return proof != null && System.nanoTime() - proof.capturedAt <= 100000000L
                && proof.feet.distance(PlayerNavigation.pose(pose)) <= .00001
                && PlayerNavigation.angleDifference(proof.yaw, JsonData.number(pose, "yaw", -360000, 360000)) <= .00001
                && Math.abs(proof.pitch - JsonData.number(pose, "pitch", -90, 90)) <= .00001;
    }
    boolean confirmed(JsonObject latest) {
        return contains(PlayerNavigation.object(latest, "player"), true)
                && contains(PlayerNavigation.object(PlayerNavigation.object(latest, "serverControl"), "pose"), false);
    }
    JsonObject receipt(boolean stopping) {
        JsonObject result = new JsonObject(); result.addProperty("version", 1);
        result.addProperty("source", "fresh_native_actual_pose_and_render_crosshair"); result.addProperty("reason", reason);
        result.addProperty("inputStopping", stopping); result.addProperty("firstHitClientTick", firstHitTick);
        result.addProperty("hitObservations", hits); result.addProperty("lostHitCount", losses);
        result.addProperty("hitIsSafeArrival", false); result.addProperty("targetCount", request.sharedTargets.size());
        if (client != null) { result.addProperty("clientHitTick", client.tick); result.addProperty("clientSide", client.side); }
        if (server != null) { result.addProperty("serverHitTick", server.tick); result.addProperty("serverSide", server.side); }
        return result;
    }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.xCoord) && Double.isFinite(value.yCoord) && Double.isFinite(value.zCoord);
    }
    static boolean matches(ApproachPlanner.Request target, MovingObjectPosition hit) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.blockX != target.x || hit.blockY != target.y || hit.blockZ != target.z) return false;
        for (int side : target.sides) if (hit.sideHit == side) return (target.hitPoint == null || InteractionHitPoint.matches(hit, target.x, target.y, target.z, side, target.hitPoint))
                && (!target.wrenchFacing || NativeGtWrench.centre(hit, target.x, target.y, target.z, side));
        return false;
    }
    static boolean sameHit(MovingObjectPosition actual, MovingObjectPosition rendered) {
        return rendered != null && rendered.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && actual.blockX == rendered.blockX && actual.blockY == rendered.blockY && actual.blockZ == rendered.blockZ
                && actual.sideHit == rendered.sideHit && finite(rendered.hitVec) && actual.hitVec.distanceTo(rendered.hitVec) <= .0001;
    }
    private static final class Evidence {
        final PlayerNavigation.Point feet;
        final float yaw, pitch;
        final int side;
        final long tick, capturedAt;
        Evidence(PlayerNavigation.Point feet, float yaw, float pitch, int side, long tick, long capturedAt) {
            this.feet = feet; this.yaw = yaw; this.pitch = pitch; this.side = side; this.tick = tick; this.capturedAt = capturedAt;
        }
    }
}
