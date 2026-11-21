package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Owns one remote player's route on the client tick thread. Network reads are reserved for arrival. */
public final class ClientGroundWalk {
    public static final long SLICE_NANOS = 2_000_000L;
    private static final int MAX_REPLANS = 4;
    private final RemoteWalkInput input;
    private final JsonObject goal;
    private final boolean preview;
    private final Runnable guard;
    private final ClientTransitMotion transit;
    private ClientTransitRoute transitSearch;
    private JsonObject leg;
    private final WalkingPathPlanner.Point origin;
    public final CompletableFuture<JsonObject> finished = new CompletableFuture<>();
    private RemoteGroundRoute search;
    private JsonArray route;
    private int waypoint, replans, stagnant, segments, slices, settled;
    private boolean intermediate;
    private double best = Double.POSITIVE_INFINITY;
    private long maxSlice, searchNanos;
    private WalkingPathPlanner.Point previousFeet;
    private final JsonArray history = new JsonArray();

    public ClientGroundWalk(RemoteWalkInput input, JsonObject goal, boolean preview, Runnable guard, java.util.function.Consumer<JsonObject> door) {
        this.input = input;
        this.goal = goal;
        this.preview = preview;
        this.guard = guard;
        transit = new ClientTransitMotion(input,door,this::authorized);
        origin = RemoteGroundRoute.feet(input.actor);
    }

    private JsonObject nextGoal(WalkingPathPlanner.Point from) {
        intermediate = false;
        if (!goal.has("destination")) return goal;
        WalkingPathPlanner.Point destination = RemoteGroundRoute.point(goal.getAsJsonObject("destination"));
        double distance = from.distance(destination);
        if (distance > 256) throw new BridgeException("out_of_range", "Client ground routes are limited to 256 blocks.");
        if (distance <= 24 && loaded(destination)) return goal;
        if (Math.abs(destination.y-from.y) > .06 || preview) {
            if (distance<=40 && loaded(destination)) return goal;
            throw new BridgeException("route_unavailable", "Transit and preview need a loaded local destination.");
        }
        for (double length = Math.min(16, distance); length >= 2; length -= 2) {
            double fraction = length / distance;
            WalkingPathPlanner.Point point = new WalkingPathPlanner.Point(from.x+(destination.x-from.x)*fraction,
                    from.y, from.z+(destination.z-from.z)*fraction);
            if (!loaded(point)) continue;
            intermediate = true;
            JsonObject next = new JsonObject();
            next.add("destination", RemoteGroundRoute.json(point));
            return next;
        }
        return null; // Wait for ordinary chunk delivery until the enclosing request expires.
    }

    private boolean authorized() {
        try { guard.run(); return true; } catch (BridgeException changed) { return false; }
    }
    public void checkOwnership() { guard.run(); }

    private boolean loaded(WalkingPathPlanner.Point p) {
        return new WorldScan.MinecraftAccess(input.world).section((int)Math.floor(p.x)>>4,
                (int)Math.floor(p.y)>>4, (int)Math.floor(p.z)>>4) != WorldScan.UNKNOWN;
    }

    private void replan() {
        if (++replans > MAX_REPLANS) throw new BridgeException("route_blocked", "Client route remained blocked after four replans.");
        route = null;
        search = null; transitSearch=null; leg=null;
        waypoint = stagnant = settled = 0;
        previousFeet = null;
        best = Double.POSITIVE_INFINITY;
    }

    private void findRoute(Minecraft client, WalkingPathPlanner.Point feet) {
        if (Math.hypot(input.actor.motionX,input.actor.motionZ) > .018) return;
        double reach = client.playerController.getBlockReachDistance();
        long start = System.nanoTime();
        try {
            if (leg == null) {
                leg=nextGoal(feet);
                if (leg==null) return;
                try { search=new RemoteGroundRoute(input.actor,leg,reach); }
                catch (BridgeException unavailable) {
                    if (!"unsupported".equals(unavailable.code) && !"out_of_reach".equals(unavailable.code)) throw unavailable;
                    transitSearch=new ClientTransitRoute(input.actor,leg,reach);
                }
            }
            long end=Math.min(input.deadline,start+SLICE_NANOS);
            if (transitSearch==null) {
                try {
                    JsonObject result=search.advance(input.actor,end);
                    if (result==null) return;
                    route=result.getAsJsonArray("waypoints");
                } catch (BridgeException unavailable) {
                    if (!"route_unavailable".equals(unavailable.code)) throw unavailable;
                    transitSearch=new ClientTransitRoute(input.actor,leg,reach);
                    return;
                }
            } else {
                route=transitSearch.advance(end);
                if (route==null) return;
            }
        } finally {
            long elapsed = System.nanoTime()-start;
            slices++;
            searchNanos += elapsed;
            maxSlice = Math.max(maxSlice,elapsed);
        }
        waypoint = 0;
        if (++segments > 32) throw new BridgeException("route_blocked", "Client route leg budget exhausted.");
        if (preview) complete(true);
    }

    public void tick(Minecraft client) {
        if (finished.isDone()) return;
        try {
            guard.run();
            if (input.held()) return;
            WalkingPathPlanner.Point actual = RemoteGroundRoute.feet(input.actor);
            if (route == null) {
                findRoute(client,actual);
                return;
            }
            JsonObject goalPoint=route.get(waypoint).getAsJsonObject();
            WalkingPathPlanner.Point next = new WalkingPathPlanner.Point(goalPoint.get("x").getAsDouble(),
                    goalPoint.get("y").getAsDouble(),goalPoint.get("z").getAsDouble());
            if (!goalPoint.has("transit") && waypoint+1<route.size() && actual.distance(next)<1.1) {
                JsonObject action=route.get(waypoint+1).getAsJsonObject().getAsJsonObject("transit");
                if (action!=null && "door".equals(action.get("kind").getAsString())
                        && ClientTransitRoute.closed(input.world,action.get("x").getAsInt(),action.get("y").getAsInt(),action.get("z").getAsInt())) {
                    transit.openAhead(client,action);
                    return;
                }
            }
            if (goalPoint.has("transit")) {
                if (!transit.advance(client,goalPoint,next,System.nanoTime()+WalkingPathPlanner.WORK_NANOS)) return;
                previousFeet=null;
                if (waypoint+1 < route.size()) {
                    record(next); waypoint++; stagnant=settled=0; best=Double.POSITIVE_INFINITY; return;
                }
                route.set(waypoint,RemoteGroundRoute.json(next)); // Never repeat the completed transit at the endpoint.
            }
            input.transitMotion=false;
            if (previousFeet != null && previousFeet.distance(actual) > 1.0) { replan(); return; }
            previousFeet = actual;
            JsonObject check = new JsonObject();
            check.add("waypoint",RemoteGroundRoute.json(next));
            boolean last = waypoint+1 == route.size();
            if (last && goal.has("block")) check.add("block",goal.get("block"));
            JsonObject local = RemoteGroundRoute.localArrival(input.actor,check,client.playerController.getBlockReachDistance(),last?.14:RemoteGroundRoute.ARRIVAL,System.nanoTime()+WalkingPathPlanner.WORK_NANOS);
            if (local.get("reached").getAsBoolean()) {
                if (last && goal.has("block") && !local.get("blockVisible").getAsBoolean()) { replan(); return; }
                if (last && (Math.hypot(input.actor.motionX,input.actor.motionZ) > .004 || settled++ < 2)) return;
                record(next); waypoint++;
                stagnant = settled = 0;
                best = Double.POSITIVE_INFINITY;
                if (last && intermediate) { search = null; transitSearch=null; leg=null; route = null; }
                else if (last) complete(false);
                return;
            }
            settled = 0;
            if (Math.hypot(input.actor.motionX,input.actor.motionZ) > .035) return;
            double distance = actual.distance(next);
            if (distance < best-.02) { best=distance; stagnant=0; }
            else if (++stagnant >= 5) { replan(); return; }
            input.pulse(client,next,this::authorized);
        } catch (BridgeException error) {
            if ("walk_slice".equals(error.code)) return;
            if (("route_unverified".equals(error.code) || "route_changed".equals(error.code) || "stale_world".equals(error.code) && search != null && route == null)
                    && replans < MAX_REPLANS && input.valid(client) && input.actor.onGround && !input.actor.isOnLadder()) replan();
            else finished.completeExceptionally(new BridgeException(error.code,error.getMessage()+" Client route: leg="+segments
                    +", waypoint="+waypoint+", replans="+replans+", door/ladder/elevator="+transit.doors+"/"+transit.ladderSteps+"/"+transit.elevatorRides+"."));
        } catch (RuntimeException error) {
            finished.completeExceptionally(error);
        }
    }

    private void record(WalkingPathPlanner.Point p) {
        if (history.size()>=96) throw new BridgeException("out_of_range","Client route waypoint budget exhausted.");
        history.add(RemoteGroundRoute.json(p));
    }

    private void complete(boolean planned) {
        JsonObject out = new JsonObject();
        out.add("start", RemoteGroundRoute.json(origin));
        out.add("waypoints", planned ? route : history);
        out.addProperty("doorsOpened",transit.doors); out.addProperty("ladderSteps",transit.ladderSteps);
        out.addProperty("elevatorRides",transit.elevatorRides);
        out.addProperty("plannerSource","client_world_tick");
        out.addProperty("routeSegments",segments);
        out.addProperty("replans",replans);
        out.addProperty("planningSlices",slices);
        out.addProperty("planningMicros",searchNanos/1000);
        out.addProperty("maximumPlanningSliceMicros",maxSlice/1000);
        finished.complete(out);
    }
}
