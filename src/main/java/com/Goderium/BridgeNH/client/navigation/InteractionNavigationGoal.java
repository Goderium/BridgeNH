package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.AxisAlignedBB;

/** Request-local interaction endpoints; reuse native approach body, identity and face-ray proofs. */
public final class InteractionNavigationGoal extends NavigationGoal {
    private final JsonArray targets;
    private final JsonObject excludedBuildEnvelope;
    private final List<PlayerNavigation.Point> endpoints = new ArrayList<PlayerNavigation.Point>();
    private final Map<String, PlayerNavigation.Point> hits = new LinkedHashMap<String, PlayerNavigation.Point>();
    private final Map<String, PlayerNavigation.Point> provedEndpoints = new LinkedHashMap<String, PlayerNavigation.Point>();
    private ApproachPlanner.Frame frame;
    private ApproachPlanner.Request endpointRequest;
    private JsonArray identities;
    private int[][] coordinates;
    private volatile PlayerNavigation.Point actualClientProof, actualServerProof;

    JsonArray targets() { return targets; }
    boolean reservesBuildEnvelope() { return excludedBuildEnvelope != null; }

    InteractionNavigationGoal(JsonObject args) {
        ExpandedGuiApi.keys(args, "type", "targets", "excludedBuildEnvelope");
        if (!args.has("targets") || !args.get("targets").isJsonArray() || args.getAsJsonArray("targets").size() < 1
                || args.getAsJsonArray("targets").size() > 8)
            throw new BridgeException("invalid_args", "An interaction goal requires 1..8 exact target blocks.");
        targets = new JsonParser().parse(args.get("targets").toString()).getAsJsonArray();
        if (args.has("excludedBuildEnvelope")) ApproachPlanner.Request.buildEnvelope(args.get("excludedBuildEnvelope"));
        excludedBuildEnvelope = args.has("excludedBuildEnvelope")
                ? new JsonParser().parse(args.get("excludedBuildEnvelope").toString()).getAsJsonObject() : null;
        // Validate nested identities and allowed faces before obtaining any world or input ownership.
        new ApproachPlanner.Request(approachArgs("validation", 0, false), 0);
    }

    public JsonObject approachArgs(String world, int dimension, boolean currentOnly) {
        JsonObject args = new JsonObject();
        args.addProperty("intent", "use"); args.addProperty("pattern", "surround");
        args.addProperty("expectedWorld", world); args.addProperty("dimension", dimension);
        args.addProperty("movementStrategy", "walking"); args.addProperty("requireFlying", false);
        args.addProperty("useNormalReach", true); args.addProperty("maxDistance", 32);
        args.addProperty("currentOnly", currentOnly); args.add("targets", targets);
        if (excludedBuildEnvelope != null) args.add("excludedBuildEnvelope", excludedBuildEnvelope);
        if (!targets.get(0).isJsonObject()) throw new BridgeException("invalid_args", "Every interaction target must be an object.");
        JsonObject first = targets.get(0).getAsJsonObject();
        for (String key : new String[] {"x", "y", "z", "allowedSides", "targetIdentity", "wrenchFacing", "hitPoint"})
            if (first.has(key)) args.add(key, first.get(key));
        return args;
    }

    public void capture(ApproachPlanner.Frame value, ApproachPlanner.Access access) {
        frame = value;
        if (frame.needsEyeSettle() || frame.sneaking || frame.sneakInput || frame.flying)
            throw new BridgeException("invalid_state", "Walking interaction goals require the actual released, settled upright nonflying eye.");
        endpointRequest = new ApproachPlanner.Request(approachArgs(frame.request.expectedWorld, frame.request.dimension, true), frame.request.dimension);
        identities = SharedApproach.identities(access, frame.request);
        coordinates = targetCoordinates();
        for (int index = 0; index < frame.request.total(); index++) {
            ApproachPlanner.Candidate candidate = SharedApproach.candidate(frame, index);
            if (candidate == null) continue;
            endpoints.add(new PlayerNavigation.Point(candidate.feetX, candidate.feetY, candidate.feetZ));
            if (index > 0) endpoints.add(new PlayerNavigation.Point(Math.floor(candidate.feetX) + .5,
                    candidate.feetY, Math.floor(candidate.feetZ) + .5));
        }
    }

    public WalkingPathPlanner.Check prove(ApproachPlanner.Access access, PlayerNavigation.Point point, double normalReach, long tick, long deadline, boolean client) {
        return prove(access,point,normalReach,tick,deadline,client,false);
    }
    public WalkingPathPlanner.Check proveActual(ApproachPlanner.Access access, PlayerNavigation.Point point, double normalReach, long tick, long deadline, boolean client) {
        return prove(access,point,normalReach,tick,deadline,client,true);
    }
    private WalkingPathPlanner.Check prove(ApproachPlanner.Access access, PlayerNavigation.Point point, double normalReach, long tick, long deadline, boolean client, boolean actual) {
        if (actual) { if (client) actualClientProof = null; else actualServerProof = null; }
        if (frame == null || System.nanoTime() >= deadline) return WalkingPathPlanner.Check.PARTIAL;
        if (reachDistance(point) > .00001) return WalkingPathPlanner.Check.BLOCKED;
        // Leave room for native braking drift at the actual clickable surface, including
        // inset multipart shapes. A block-box distance alone cannot reserve that margin.
        boolean reserveBraking = !actual && point.distance(new PlayerNavigation.Point(frame.x,frame.feetY,frame.z)) > .00001;
        if (reserveBraking
                && reachDistance(point.x,point.y,point.z,coordinates,frame.eyeOffset,Math.min(frame.reach,normalReach)-.3) > .00001)
            return WalkingPathPlanner.Check.BLOCKED;
        SharedApproach.sameIdentities(identities, SharedApproach.identities(access, endpointRequest));
        AxisAlignedBB body = frame.actorBody.getOffsetBoundingBox(point.x - frame.x, point.y - frame.feetY, point.z - frame.z);
        ApproachPlanner.Frame at = new ApproachPlanner.Frame(endpointRequest, frame.worldIdentity, frame.serverIdentity,
                frame.session, frame.playerId, frame.item, frame.itemTag, frame.sourceItem, frame.sourceTag,
                point.x, point.y, point.z, frame.eyeOffset, frame.width, frame.height, frame.reach, body,
                frame.yaw, frame.pitch, tick, frame.selectedSlot, false, false, frame.cursorEmpty, frame.screenIdentity, frame.screenId);
        JsonObject reach = new JsonObject(); reach.addProperty("playerId", frame.playerId);
        reach.addProperty("normalReach", reserveBraking ? normalReach-.3 : normalReach); reach.addProperty("worldTick", tick);
        ApproachPlanner.Search proof = ApproachPlanner.search(access, at, reach, System::nanoTime);
        if (System.nanoTime() >= deadline || proof.partial) return WalkingPathPlanner.Check.PARTIAL;
        if (proof.selected == null) return proof.unknown > 0 ? WalkingPathPlanner.Check.UNKNOWN : WalkingPathPlanner.Check.BLOCKED;
        if (client) {
            JsonObject hit = proof.serverRay.getAsJsonObject("hit");
            hits.put(key(point), new PlayerNavigation.Point(hit.get("x").getAsDouble(), hit.get("y").getAsDouble(), hit.get("z").getAsDouble()));
            provedEndpoints.put(key(point),point);
        }
        if (actual) { if (client) actualClientProof = point; else actualServerProof = point; }
        return WalkingPathPlanner.Check.CLEAR;
    }

    public boolean actualContains(PlayerNavigation.Point point, boolean client) {
        PlayerNavigation.Point proved = client ? actualClientProof : actualServerProof;
        return proved != null && point.distance(proved) <= .00001;
    }

    public PlayerNavigation.Point aim(PlayerNavigation.Point point) { return hits.get(key(point)); }
    boolean contains(PlayerNavigation.Point point) {
        if (actualContains(point, true) || actualContains(point, false)) return true;
        for (PlayerNavigation.Point endpoint : provedEndpoints.values()) if (point.near(endpoint)) return true;
        return false;
    }
    void collect(PlayerNavigation.Point start, List<PlayerNavigation.Point> result) {
        if (frame == null) throw new BridgeException("invalid_state", "Interaction candidates require the actual client capture.");
        result.addAll(endpoints);
    }
    PlayerNavigation.Point anchor() {
        JsonObject target = targets.get(0).getAsJsonObject();
        return new PlayerNavigation.Point(target.get("x").getAsDouble() + .5, target.get("y").getAsDouble(), target.get("z").getAsDouble() + .5);
    }
    double heuristic(PlayerNavigation.Point point) {
        return NavigationCosts.lowerBound(reachDistance(point));
    }
    public double reachDistance(PlayerNavigation.Point point) {
        if (frame == null) return 0;
        return reachDistance(point.x,point.y,point.z,coordinates,frame.eyeOffset,Math.min(frame.reach,frame.request.maxDistance));
    }
    private int[][] targetCoordinates() {
        int[][] positions = new int[frame.request.sharedTargets.size()][3];
        for (int index=0;index<positions.length;index++) {
            ApproachPlanner.Request target=frame.request.sharedTargets.get(index);
            positions[index]=new int[] {target.x,target.y,target.z};
        }
        return positions;
    }
    WalkingPathPlanner.EndpointGoal estimator(NavigationCosts.Model costs) {
        final int[][] positions=coordinates; final double eyeOffset=frame.eyeOffset, reach=Math.min(frame.reach,frame.request.maxDistance);
        return p -> costs.lowerBound(reachDistance(p.x,p.y,p.z,positions,eyeOffset,reach));
    }
    private static double reachDistance(double x,double y,double z,int[][] targets,double eyeOffset,double reach) {
        double distance = 0;
        for (int[] target : targets) {
            double dx=x-Math.max(target[0],Math.min(target[0]+1,x));
            double eyeY=y+eyeOffset, dy=eyeY-Math.max(target[1],Math.min(target[1]+1,eyeY));
            double dz=z-Math.max(target[2],Math.min(target[2]+1,z));
            distance=Math.max(distance,Math.sqrt(dx*dx+dy*dy+dz*dz)-reach);
        }
        return Math.max(0,distance);
    }
    private static String key(PlayerNavigation.Point point) { return point.x + ":" + point.y + ":" + point.z; }
}
