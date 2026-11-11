package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.network.play.client.C03PacketPlayer;

/** Bounded native-key navigation with request-local walking/flight planning. It never grants abilities. */
public final class PlayerNavigation {
    public static final String OPERATION = "player.navigate";
    public static final double TOLERANCE = .18, STEP = .25;
    public static final int MAX_PROBES = 192, FLIGHT_JUMP_EDGE_TICKS = 8, MAX_FLIGHT_TRACE = 96;
    static final int MAX_FLIGHT_ERROR_MESSAGE = 3500;
    public static final List<String> MOVEMENT_KEYS = java.util.Collections.unmodifiableList(java.util.Arrays.asList("forward", "back", "left", "right", "jump", "sneak"));

    public static double travelPitch(Point current, Point target, boolean walking) {
        return walking ? 0 : -Math.toDegrees(Math.atan2(target.y - current.y, current.horizontal(target)));
    }

    static double travelYaw(Point current, Point target, double mx, double mz) {
        return Math.toDegrees(Math.atan2(Math.hypot(mx, mz) > .01 ? mz : target.z - current.z,
                Math.hypot(mx, mz) > .01 ? mx : target.x - current.x)) - 90;
    }

    public static Point walkingStop(Point current, double mx, double mz, double friction) {
        if (!Double.isFinite(mx) || !Double.isFinite(mz) || !Double.isFinite(friction) || friction < 0 || friction >= 1)
            throw new BridgeException("unsupported", "Native walking has no finite grounded stopping model.");
        double x = mx / (1 - friction), z = mz / (1 - friction);
        if (Math.hypot(x, z) > WalkingPathPlanner.MAX_DISTANCE)
            throw new BridgeException("unsupported", "Actual walking inertia exceeds the bounded route budget.");
        return new Point(current.x + x, current.y, current.z + z);
    }

    static boolean walkingStopped(JsonObject player) {
        return Math.hypot(motion(player, "motionX"), motion(player, "motionZ")) <= .005;
    }

    public static final class Point {
        public final double x, y, z;
        public Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public double horizontal(Point other) { return Math.hypot(other.x - x, other.z - z); }
        public double distance(Point other) { return Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)); }
        public boolean near(Point other) { return near(other, TOLERANCE); }
        public boolean near(Point other, double horizontalTolerance) { return horizontal(other) <= horizontalTolerance && Math.abs(y - other.y) <= TOLERANCE; }
        JsonObject json() { JsonObject value = new JsonObject(); value.addProperty("x", x); value.addProperty("feetY", y); value.addProperty("z", z); return value; }
    }

    public static final class Request {
        public String followTaskId;
        public Point destination;
        public Point lookAt;
        public final NavigationGoal goal;
        public final CrosshairArrival crosshair;
        public final boolean segmented, includeDetails;
        public final String strategy, world, session;
        public final int dimension, timeout;
        public final boolean finishFlying, requireSurvivalActor;
        final List<Point> declared = new ArrayList<Point>();
        public Request(JsonObject args) {
            ExpandedGuiApi.keys(args, "x", "y", "z", "dimension", "expectedWorld", "expectedSessionId", "strategy", "timeout_ms", "finishFlying", "waypoints", "lookAt", "requireSurvivalActor", "goal", "segmented", "includeDetails", "arrivalTargets");
            if (args.has("goal")) {
                if (!args.get("goal").isJsonObject() || args.has("x") || args.has("y") || args.has("z")) throw new BridgeException("invalid_args", "Use one goal or XYZ, never both.");
                goal = NavigationGoal.parse(args.getAsJsonObject("goal")); destination = goal.anchor();
            } else { destination = point(args); goal = null; }
            segmented = GuiDataAdapter.booleanArg(args, "segmented", false);
            if (segmented && goal instanceof InteractionNavigationGoal) throw new BridgeException("invalid_args", "Interaction goals use one bounded nearby search, without segmented waypoints.");
            includeDetails = GuiDataAdapter.booleanArg(args, "includeDetails", false);
            strategy = ExpandedGuiApi.option(args, "strategy", "walking", "walking", "walking_step", "ability_flight", "developer_teleport");
            if (goal instanceof InteractionNavigationGoal && (!"walking".equals(strategy) || args.has("waypoints") || args.has("lookAt")))
                throw new BridgeException("invalid_args", "Interaction goals select their own walking endpoint and face aim; no explicit waypoints or lookAt.");
            world = CatalogData.text(args, "expectedWorld", 128, null); session = CatalogData.text(args, "expectedSessionId", 128, null);
            if (world.isEmpty() || session.isEmpty()) throw new BridgeException("invalid_args", "Navigation requires exact session/world identities.");
            dimension = JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            timeout = args.has("timeout_ms") ? JsonData.integer(args, "timeout_ms", 250, segmented ? 60000 : goal instanceof InteractionNavigationGoal ? 15000 : 6500) : 6000;
            if ((segmented || goal != null) && !("walking".equals(strategy) || "ability_flight".equals(strategy))
                    && !(goal instanceof NearbyNavigationGoal && !segmented && "developer_teleport".equals(strategy)))
                throw new BridgeException("invalid_args", "Goals and segmented planning require walking/flight; nearby also supports permitted TP.");
            finishFlying = GuiDataAdapter.booleanArg(args, "finishFlying", false);
            requireSurvivalActor = GuiDataAdapter.booleanArg(args, "requireSurvivalActor", false);
            if (finishFlying && !"ability_flight".equals(strategy)) throw new BridgeException("invalid_args", "finishFlying is exclusive to explicitly selected ability_flight.");
            if (args.has("lookAt") && !args.get("lookAt").isJsonObject()) throw new BridgeException("invalid_args", "lookAt must be an XYZ object.");
            lookAt = args.has("lookAt") ? nestedPoint(args.getAsJsonObject("lookAt")) : null;
            if (args.has("arrivalTargets") && (goal != null || !args.get("arrivalTargets").isJsonArray()
                    || !("walking".equals(strategy) || "ability_flight".equals(strategy))))
                throw new BridgeException("invalid_args", "arrivalTargets require explicit XYZ walking/ability_flight; interaction goals already carry their targets.");
            crosshair = goal instanceof InteractionNavigationGoal
                    ? ((InteractionNavigationGoal)goal).reservesBuildEnvelope() ? null
                        : new CrosshairArrival(((InteractionNavigationGoal)goal).targets(), world, dimension, strategy)
                    : args.has("arrivalTargets") ? new CrosshairArrival(args.getAsJsonArray("arrivalTargets"), world, dimension, strategy) : null;
            if (args.has("waypoints")) {
                if (!args.get("waypoints").isJsonArray()) throw new BridgeException("invalid_args", "waypoints must be an array of XYZ objects.");
                JsonArray values = args.getAsJsonArray("waypoints");
                if (values.size() > 2 || !"walking".equals(strategy)) throw new BridgeException("invalid_args", "Only walking accepts zero through two explicit flat intermediate waypoints.");
                for (JsonElement value : values) { if (!value.isJsonObject()) throw new BridgeException("invalid_args", "Every waypoint must be XYZ."); declared.add(nestedPoint(value.getAsJsonObject())); }
            }
        }
        private static Point nestedPoint(JsonObject value) { ExpandedGuiApi.keys(value, "x", "y", "z"); return point(value); }
        private static Point point(JsonObject value) {
            ExpandedGuiApi.keys(value, "x", "y", "z", "dimension", "expectedWorld", "expectedSessionId", "strategy", "timeout_ms", "finishFlying", "waypoints", "lookAt", "requireSurvivalActor", "goal", "segmented", "includeDetails", "arrivalTargets");
            return new Point(JsonData.number(value, "x", -29999999, 29999999), JsonData.number(value, "y", 0, 253), JsonData.number(value, "z", -29999999, 29999999));
        }
        List<Point> route(Point start) { return route(start, false); }
        List<Point> route(Point start, boolean actualFlying) {
            List<Point> result = new ArrayList<Point>();
            if ("walking".equals(strategy)) {
                result.addAll(declared); result.add(destination);
                for (Point value : result) if (!segmented && Math.abs(value.y-start.y)>WalkingPathPlanner.MAX_HEIGHT_CHANGE)
                    throw new BridgeException("unsupported", "Grounded walking exceeds its bounded six-block total elevation search.");
            } else if ("walking_step".equals(strategy)) {
                GroundedStep.validate(start, destination); result.add(destination);
            } else if ("ability_flight".equals(strategy)) {
                // A vertical takeoff needs no horizontal cruise corridor. Keep its exact
                // endpoint and the normal full-body proof, acquisition and dual arrival ACK.
                // Adding two blocks above it creates a needless ascent/descent before hovering.
                boolean verticalTakeoff = destination.y > start.y && start.horizontal(destination) < .000001;
                if (finishFlying && (actualFlying || verticalTakeoff)) {
                    result.add(destination);
                } else {
                    double cruise = Math.max(start.y, destination.y) + 2;
                    if (cruise > 253) throw new BridgeException("unsupported", "The declared flight corridor would exceed world height.");
                    result.add(new Point(start.x, cruise, start.z)); result.add(new Point(destination.x, cruise, destination.z)); result.add(destination);
                }
            } else result.add(destination);
            double distance = 0; Point previous = start;
            for (Point value : result) { distance += previous.distance(value); previous = value; }
            if (distance > (segmented ? NavigationSegments.MAX_DISTANCE : 32)) throw new BridgeException("unsupported", "Navigation is bounded to 32 total blocks; choose a closer declared start/target.");
            return segmented ? NavigationSegments.split(start, result, "walking".equals(strategy)) : result;
        }
    }

    public static final class State {
        public final String taskId = java.util.UUID.randomUUID().toString();
        final NavigationCosts.Model costs = NavigationCosts.snapshot();
        public final Request request;
        public final JsonObject before;
        public final Object worldIdentity, playerIdentity;
        public final String playerId;
        public final boolean creative;
        public final long deadline, started, baselineSample;
        public final Point start;
        public final List<Point> route;
        final List<Point> planningTargets = new ArrayList<Point>(), plannedWindows = new ArrayList<Point>();
        final JsonArray planningWindows = new JsonArray();
        public volatile List<WalkingPathPlanner.Sweep> walkingProofSweeps;
        volatile List<FlightPathPlanner.Sweep> flightProofSweeps;
        public List<FlightPathPlanner.Sweep> flightSweeps(double width,double height) {
            if(width!=flightWidth || height!=flightHeight) throw new BridgeException("invalid_state","Actual flight body differs from the planned body.");
            if(flightProofSweeps==null) flightProofSweeps=PlayerNavigation.flightSweeps(start,route,width,height,request.segmented);
            return flightProofSweeps;
        }
        int planningWindow, goalCandidateIndex;
        List<Point> goalCandidates;
        public boolean goalResolved;
        int goalRouteAttempts, goalPlanningNodes, goalPlanningSamples, gateChoice;
        boolean goalSearchBounded;
        double bestGoalScore = Double.POSITIVE_INFINITY;
        Point bestGoalDestination;
        List<Point> bestGoalRoute;
        JsonArray bestGoalWindows;
        double bestGoalDistance;
        WalkingPathPlanner bestWalkingPlanner;
        FlightPathPlanner bestFlightPlanner;
        List<Point> gateCandidates;
        double plannedDistance;
        public volatile boolean walkingRouteReady;
        WalkingPathPlanner walkingPlanner;
        public volatile GroundedStep step;
        final JsonArray walkingSteps = new JsonArray();
        final List<JsonObject> walkingTrace = new ArrayList<JsonObject>();
        long walkingTraceTick = -1;
        public volatile boolean flightRouteReady;
        FlightPathPlanner flightPlanner;
        public double flightWidth, flightHeight;
        public volatile long serverSafetyAt;
        public volatile BridgeException failure;
        public volatile Set<String> flightKeys = java.util.Collections.emptySet();
        public volatile boolean keyReleaseConfirmed, ownerClearConfirmed;
        public volatile boolean clientGroundFloorProof, serverGroundFloorProof;
        long lastJumpEdgeTick = Long.MIN_VALUE / 2;
        int jumpEdges, traceDropped;
        long nativeJumpObservedTick = -1, lastNativeJumpEdgeTick = -1;
        int nativeJumpEdges;
        boolean nativeJumpDown;
        String flightPhase = "planning";
        final List<JsonObject> flightTrace = new ArrayList<JsonObject>();
        public JsonObject geometryRejection;
        public JsonObject lastBrakedFlightBody;
        final java.util.concurrent.atomic.AtomicReference<ServerFlightBrake> serverFlightBrake = new java.util.concurrent.atomic.AtomicReference<ServerFlightBrake>();
        int serverFlightBrakes;
        int blockedFlightInputsBraked;
        int stationaryFlightBrakes, precisionFlightWaypoint = -1, clearanceFlightWaypoint = -1, levelFlightWaypoint = -1;
        double levelFlightY;
        Point flightBrakePosition;
        long lastHorizontalPulseTick=Long.MIN_VALUE/2;
        long lastVerticalPulseTick=Long.MIN_VALUE/2;
        long traceTick = -1;
        long lastObservedSnapshotTick = -1, lastObservedSample = -1;
        public volatile double steeringYaw;
        Point lastFlightPosition;
        double actualFlightDistance;
        public double walkingWidth, walkingHeight;
        public double walkingFriction = .546;
        public double walkingAcceleration = .098;
        final Set<Integer> reanchoredWalkingCorners = new LinkedHashSet<Integer>();
        JsonObject lastWalkingCornerReanchor;
        double flightCoastDrag = (double).91F, flightCoastScale = 1;
        private double observedFlySpeed = Double.NaN;
        private long motionObservationTick = -1;
        private double previousFlightX, previousFlightZ, previousMotionX, previousMotionZ;
        private boolean previousHorizontalIdle;
        private int coastModelSamples;
        public boolean walkingCoasting;
        public final Set<String> held = new LinkedHashSet<String>();
        public final Map<String, Integer> keyCodes = new LinkedHashMap<String, Integer>();

        public void requireBoundKeys(Set<String> requested) {
            for (String name : requested) if (keyCodes.get(name) == null)
                throw new BridgeException("invalid_state", "Navigation requested an unregistered native movement key: " + name);
        }
        public volatile boolean abort;
        public boolean mutation, teleportSent, flightObserved, aiming, nativeMovementDispatched, crosshairStopping;
        final boolean teleportFlying;
        public final ArrivalPoseReport arrivalPoseReport = new ArrivalPoseReport();
        public long mutationSample, lastClientTick = -1, flightSample = -1;
        public volatile int waypoint;
        public int jumpTicks, clientProbe, serverProbe;
        public JsonObject latest, receipt;
        Point stableClient, stableServer;
        long stableSample = -1;
        String arrivalAckWait = "not_checked";
        public Object authoritativeActor, authoritativeWorld, authoritativeServer;
        public JsonObject eyeSettling;
        public JsonObject startSettling;
        State(Request request, JsonObject before, Object worldIdentity, Object playerIdentity, String playerId) {
            this(request, before, worldIdentity, playerIdentity, playerId, System.nanoTime());
        }
        public State(Request request, JsonObject before, Object worldIdentity, Object playerIdentity, String playerId, long dispatchStarted) {
            this.request = request; this.before = before; this.worldIdentity = worldIdentity; this.playerIdentity = playerIdentity; this.playerId = playerId;
            JsonObject player = object(before, "player"), control = object(before, "serverControl");
            teleportFlying = "developer_teleport".equals(request.strategy) && flag(player,"flying") && flag(object(control,"pose"),"flying")
                    && !flag(player,"onGround") && !flag(object(control,"pose"),"onGround");
            start = pose(player);
            goalResolved = request.goal == null;
            if (request.goal instanceof InteractionNavigationGoal) {
                // An interaction volume is solved by the frontier itself, without a mandatory anchor proof.
                goalResolved = true; goalRouteAttempts = 1; request.destination = request.goal.anchor();
            } else if (request.goal != null) { goalCandidates = request.goal.candidates(start); request.destination = goalCandidates.get(0); }
            route = request.goal instanceof InteractionNavigationGoal ? new ArrayList<Point>(java.util.Collections.singletonList(request.destination))
                    : new ArrayList<Point>(request.route(start, flag(player, "flying") && flag(object(control, "pose"), "flying"))); creative = flag(player, "creative");
            walkingRouteReady = !"walking".equals(request.strategy);
            flightRouteReady = !"ability_flight".equals(request.strategy);
            baselineSample = sample(control); mutationSample = baselineSample;
            started = dispatchStarted; deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(request.timeout);
            lastFlightPosition = start; planningTargets.addAll(route);
            requireContext(before);
        }
        public boolean prepareWalking(WalkingPathPlanner.Probe probe, double width, double height, long workDeadline) {
            if (walkingRouteReady) return true;
            if (!"walking".equals(request.strategy) || mutation || !held.isEmpty() || waypoint != 0
                    || start.distance(current()) > .00001)
                throw new BridgeException("invalid_state", "Walking planning requires the unchanged actual start and no dispatched movement.");
            if (request.goal instanceof InteractionNavigationGoal && System.nanoTime() >= walkingPlanningDeadline()) {
                goalSearchBounded = true;
                if (bestGoalRoute != null) { restoreBestGoal(); return true; }
                throw new BridgeException("unsupported", "The bounded interaction planning window found no complete route; no movement dispatched.");
            }
            if (!resolveGoal((p, d) -> probe.endpoint(new WalkingPathPlanner.Point(p.x,p.y,p.z), width,height, d), workDeadline)) return false;
            if(walkingRouteReady) return true;
            if (walkingPlanner == null) {
                List<WalkingPathPlanner.Point> targets = new ArrayList<WalkingPathPlanner.Point>();
                for (Point point : request.segmented ? java.util.Collections.singletonList(planningTargets.get(planningWindow)) : route) targets.add(new WalkingPathPlanner.Point(point.x, point.y, point.z));
                walkingWidth = width; walkingHeight = height;
                Point from = windowStart();
                if (request.segmented) { selectGate(); targets.clear(); Point p=planningTargets.get(planningWindow); targets.add(new WalkingPathPlanner.Point(p.x,p.y,p.z)); }
                WalkingPathPlanner.EndpointGoal endpoints = request.goal instanceof InteractionNavigationGoal
                        ? ((InteractionNavigationGoal)request.goal).estimator(costs) : null;
                walkingPlanner = new WalkingPathPlanner(new WalkingPathPlanner.Point(from.x, from.y, from.z), targets, width, height, walkingPlanningDeadline(), costs,endpoints);
            }
            if (walkingWidth != width || walkingHeight != height)
                throw new BridgeException("invalid_state", "The actual walking body changed during bounded planning.");
            WalkingPathPlanner.Status status = walkingPlanner.advance(probe, workDeadline, WalkingPathPlanner.SYSTEM_CLOCK);
            if (status == WalkingPathPlanner.Status.REFUSED) {
                if (nextGate()) { walkingPlanner=null; return false; }
                if (nextGoalCandidate()) return false;
                throw new BridgeException("unsupported", "Bounded native grounded route refused: " + walkingPlanner.reason() + ". No teleport or flight fallback.");
            }
            if (status != WalkingPathPlanner.Status.FOUND) return false;
            List<Point> window = new ArrayList<Point>();
            for (WalkingPathPlanner.Point point : walkingPlanner.route()) window.add(new Point(point.x,point.y,point.z));
            if (request.goal instanceof InteractionNavigationGoal) {
                request.destination = window.get(window.size()-1);
                request.lookAt = ((InteractionNavigationGoal)request.goal).aim(request.destination);
            }
            if (!publishWindow(window, walkingPlanner.routeDistance(), walkingPlanner.discoveredNodes(), walkingPlanner.samples())) { walkingPlanner = null; return false; }
            if (!chooseGoalRoute()) return false;
            route.clear(); route.addAll(plannedWindows);
            clientProbe = 0; serverProbe = 0; waypoint = 0;
            // Volatile publication comes last; server callbacks must never see an incomplete route.
            walkingRouteReady = true;
            return true;
        }

        public interface EndpointProbe { WalkingPathPlanner.Check inspect(Point p, long deadline); }
        long walkingPlanningDeadline() {
            // Reserve the rest of the task for normal-key travel, braking and a real dual arrival.
            return request.goal instanceof InteractionNavigationGoal ? Math.min(deadline,started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(6000,request.timeout/2))) : deadline;
        }
        public boolean resolveGoal(EndpointProbe probe, long workDeadline) {
            if (goalResolved) return true;
            for (int samples=0; goalCandidateIndex < goalCandidates.size() && samples < 64 && System.nanoTime() < workDeadline; samples++) {
                Point candidate = goalCandidates.get(goalCandidateIndex);
                if (request.goal instanceof NearbyNavigationGoal) {
                    WalkingPathPlanner.Check visible=((NearbyNavigationGoal)request.goal).visible(candidate,workDeadline);
                    if(visible==WalkingPathPlanner.Check.PARTIAL) return false;
                    if(visible!=WalkingPathPlanner.Check.CLEAR) { goalCandidateIndex++; continue; }
                }
                WalkingPathPlanner.Check check = probe.inspect(candidate,workDeadline);
                if (check == null || check == WalkingPathPlanner.Check.PARTIAL || System.nanoTime() >= workDeadline) return false;
                if (check == WalkingPathPlanner.Check.CLEAR) {
                    request.destination = candidate; route.clear();
                    if (request.goal instanceof InteractionNavigationGoal) request.lookAt = ((InteractionNavigationGoal) request.goal).aim(candidate);
                    try { route.addAll(request.route(start, flag(object(before,"player"),"flying") && flag(object(object(before,"serverControl"),"pose"),"flying"))); }
                    catch (BridgeException error) { if (!"unsupported".equals(error.code)) throw error; goalCandidateIndex++; continue; }
                    planningTargets.clear(); planningTargets.addAll(route); goalResolved = true; goalRouteAttempts++; return true;
                }
                goalCandidateIndex++;
            }
            if (goalCandidateIndex == goalCandidates.size()) {
                if (bestGoalRoute!=null) { restoreBestGoal(); return true; }
                throw new BridgeException("unsupported", "No proved reachable endpoint in the bounded goal candidates.");
            }
            return false;
        }
        Point windowStart() { return plannedWindows.isEmpty() ? start : plannedWindows.get(plannedWindows.size()-1); }
        void selectGate() {
            if (gateCandidates!=null || planningWindow==planningTargets.size()-1) return;
            Point p=planningTargets.get(planningWindow);
            for(Point required:request.declared) if(p.distance(required)<.00001) return;
            gateCandidates=NavigationSegments.gates(p); gateChoice=0;
        }
        boolean nextGate() {
            if(gateCandidates==null || ++gateChoice>=gateCandidates.size()) return false;
            if(walkingPlanner!=null) { goalPlanningNodes+=walkingPlanner.discoveredNodes(); goalPlanningSamples+=walkingPlanner.samples(); }
            if(flightPlanner!=null) { goalPlanningNodes+=flightPlanner.discoveredNodes(); goalPlanningSamples+=flightPlanner.samples(); }
            if(goalPlanningNodes>32768 || goalPlanningSamples>131072) throw new BridgeException("unsupported","Aggregate route planning budget exhausted before any movement.");
            planningTargets.set(planningWindow,gateCandidates.get(gateChoice)); return true;
        }
        double candidateLowerBound(Point p) {
            return "walking".equals(request.strategy) ? start.horizontal(p)*costs.walk : start.distance(p);
        }
        boolean nextGoalCandidate() {
            if(request.goal instanceof InteractionNavigationGoal) return false;
            if(request.goal==null) return false;
            if(walkingPlanner!=null) { goalPlanningNodes+=walkingPlanner.discoveredNodes(); goalPlanningSamples+=walkingPlanner.samples(); }
            if(flightPlanner!=null) { goalPlanningNodes+=flightPlanner.discoveredNodes(); goalPlanningSamples+=flightPlanner.samples(); }
            goalCandidateIndex++;
            // Finite choices share a deadline and aggregate limits, never a fresh budget per endpoint.
            boolean stop=goalCandidateIndex>=goalCandidates.size();
            if(!stop) {
                boolean bound=true;
                for(int i=goalCandidateIndex;i<goalCandidates.size();i++) if(candidateLowerBound(goalCandidates.get(i))<bestGoalScore-.00001) { bound=false; break; }
                stop=bestGoalRoute!=null && bound;
            }
            if(!stop && (goalRouteAttempts>=16 || goalPlanningNodes>=32768 || goalPlanningSamples>=131072 || System.nanoTime()+200000000L>=deadline)) { stop=true; goalSearchBounded=true; }
            if (!stop && request.goal instanceof InteractionNavigationGoal && (goalRouteAttempts >= 4 || System.nanoTime() + 200000000L >= walkingPlanningDeadline())) { stop=true; goalSearchBounded=true; }
            if(stop) { if(bestGoalRoute!=null) { restoreBestGoal(); return true; } return false; }
            walkingPlanner=null; flightPlanner=null; planningWindow=0; plannedDistance=0; plannedWindows.clear();
            while(planningWindows.size()>0) planningWindows.remove(planningWindows.size()-1);
            gateCandidates=null; goalResolved=false; return true;
        }
        boolean chooseGoalRoute() {
            if (request.goal instanceof NearbyNavigationGoal) return true;
            if (request.goal instanceof InteractionNavigationGoal) return true;
            if(request.goal==null) return true;
            double score=0; Point previous=start;
            for(Point p:plannedWindows) { score += "walking".equals(request.strategy) ? costs.walking(previous.horizontal(p),p.y-previous.y).score() : previous.distance(p); previous=p; }
            if(score<bestGoalScore) {
                bestGoalScore=score; bestGoalDestination=request.destination; bestGoalRoute=new ArrayList<Point>(plannedWindows);
                bestGoalDistance=plannedDistance; bestGoalWindows=new com.google.gson.JsonParser().parse(planningWindows.toString()).getAsJsonArray();
                bestWalkingPlanner=walkingPlanner; bestFlightPlanner=flightPlanner;
            }
            nextGoalCandidate(); return goalResolved;
        }
        void restoreBestGoal() {
            request.destination=bestGoalDestination; plannedWindows.clear(); plannedWindows.addAll(bestGoalRoute); plannedDistance=bestGoalDistance;
            if (request.goal instanceof InteractionNavigationGoal) request.lookAt = ((InteractionNavigationGoal) request.goal).aim(request.destination);
            while(planningWindows.size()>0) planningWindows.remove(planningWindows.size()-1);
            for(JsonElement value:bestGoalWindows) planningWindows.add(value);
            walkingPlanner=bestWalkingPlanner; flightPlanner=bestFlightPlanner; goalResolved=true;
            walkingRouteReady="walking".equals(request.strategy); flightRouteReady="ability_flight".equals(request.strategy);
            // Preserve the other strategy's already-ready flag. Route proof still runs after publication.
            if("walking".equals(request.strategy)) flightRouteReady=true; else walkingRouteReady=true;
            route.clear(); route.addAll(plannedWindows);
        }
        boolean publishWindow(List<Point> window, double distance, int nodes, int samples) {
            plannedDistance += distance; plannedWindows.addAll(window);
            if (plannedDistance > (request.segmented ? NavigationSegments.MAX_DISTANCE : 32) || plannedWindows.size() > NavigationSegments.MAX_ROUTE_POINTS)
                throw new BridgeException("unsupported", "Observed complete segmented route exceeds its actual distance/point budget.");
            JsonObject record = new JsonObject(); record.addProperty("index", planningWindow); record.addProperty("distanceBlocks",distance);
            record.addProperty("nodes",nodes); record.addProperty("geometrySamples",samples); planningWindows.add(record);
            if(request.segmented && planningWindow<planningTargets.size()-1) {
                goalPlanningNodes+=nodes; goalPlanningSamples+=samples;
                if(goalPlanningNodes>32768 || goalPlanningSamples>131072) throw new BridgeException("unsupported","Aggregate segmented planning budget exhausted before any movement.");
            }
            gateCandidates=null; gateChoice=0;
            return !request.segmented || ++planningWindow == planningTargets.size();
        }
        public boolean routeReady() { return walkingRouteReady && flightRouteReady && goalResolved; }
        public boolean prepareWalkingStep(double width, double height, boolean nativeIdle) {
            if (!"walking".equals(request.strategy) || !walkingRouteReady) return true;
            if (step != null && step.arrival(latest)) {
                if (!held.isEmpty() || !nativeIdle) return false;
                if (waypoint == route.size()-1) return true;
                walkingSteps.add(step.receipt(true, false, true, 4)); step = null; waypoint++;
            }
            Point previous = waypoint == 0 ? start : route.get(waypoint-1), target = destination();
            if (step == null && Math.abs(previous.y-target.y)>.0001) {
                JsonObject player = object(latest, "player"), server = object(object(latest,"serverControl"),"pose");
                if (!current().near(previous) || !pose(server).near(previous) || !landed() || !held.isEmpty() || !nativeIdle
                        || Math.hypot(motion(player,"motionX"),motion(player,"motionZ"))>.02
                        || Math.hypot(motion(server,"motionX"),motion(server,"motionZ"))>.02) return false;
                step = new GroundedStep(previous, target, width, height);
                serverSafetyAt = 0;
                return false; // A fresh owning-server proof must observe the newly published transition.
            }
            return true;
        }
        public boolean prepareFlight(FlightPathPlanner.Probe probe, double width, double height, long workDeadline) {
            if (flightRouteReady) return true;
            if (!"ability_flight".equals(request.strategy) || mutation || !held.isEmpty() || waypoint != 0
                    || start.distance(current()) > .00001)
                throw new BridgeException("invalid_state", "Flight planning requires the unchanged actual start and no dispatched movement.");
            if (!resolveGoal((p, d) -> {
                FlightPathPlanner.Check check = probe.inspect(new FlightPathPlanner.Sweep(new FlightPathPlanner.Point(p.x,p.y,p.z), new FlightPathPlanner.Point(p.x,p.y,p.z),width,height),d);
                return WalkingPathPlanner.Check.valueOf(check.name());
            }, workDeadline)) return false;
            if(flightRouteReady) return true;
            if (flightPlanner == null) {
                List<FlightPathPlanner.Point> targets = new ArrayList<FlightPathPlanner.Point>();
                for (Point point : request.segmented ? java.util.Collections.singletonList(planningTargets.get(planningWindow)) : route) targets.add(new FlightPathPlanner.Point(point.x, point.y, point.z));
                flightWidth = width; flightHeight = height;
                Point from = windowStart();
                if(request.segmented) { selectGate(); targets.clear(); Point p=planningTargets.get(planningWindow); targets.add(new FlightPathPlanner.Point(p.x,p.y,p.z)); }
                flightPlanner = new FlightPathPlanner(new FlightPathPlanner.Point(from.x, from.y, from.z), targets, width, height, deadline);
            }
            if (flightWidth != width || flightHeight != height)
                throw new BridgeException("invalid_state", "The actual flight body changed during bounded planning.");
            FlightPathPlanner.Status status = flightPlanner.advance(probe, workDeadline, FlightPathPlanner.SYSTEM_CLOCK);
            if (status == FlightPathPlanner.Status.REFUSED) {
                if(nextGate()) { flightPlanner=null; return false; }
                if (nextGoalCandidate()) return false;
                throw new BridgeException("unsupported", "Bounded native flight route refused: " + flightPlanner.reason() + ". No movement or fallback.");
            }
            if (status != FlightPathPlanner.Status.FOUND) return false;
            List<Point> window = new ArrayList<Point>();
            for (FlightPathPlanner.Point point : flightPlanner.route()) window.add(new Point(point.x,point.y,point.z));
            if (!publishWindow(window, flightPlanner.routeDistance(), flightPlanner.discoveredNodes(), flightPlanner.samples())) { flightPlanner = null; return false; }
            if (!chooseGoalRoute()) return false;
            route.clear(); route.addAll(plannedWindows);
            clientProbe = 0; serverProbe = 0; waypoint = 0; flightPhase = "route_proof";
            flightRouteReady = true;
            return true;
        }

        public void requireContext(JsonObject value) {
            if (abort || System.nanoTime() >= deadline) throw new BridgeException("timeout", "Navigation expired or was cancelled; held task keys are released and no action is replayed.");
            JsonObject control = object(value, "serverControl"), player = object(value, "player"), world = object(value, "world"), background = object(value, "background");
            if (!request.session.equals(text(value, "sessionId")) || !"developer".equals(text(value, "profile")) || !flag(value, "worldLoaded")
                    || (!request.world.equals(text(world, "worldName")) && !request.world.equals(text(world, "worldFolder")))
                    || JsonData.integer(world, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != request.dimension
                    || !flag(background, "enabled") || !flag(background, "helperInstalled") || flag(background, "pauseOnLostFocus")
                    || flag(value, "paused") || flag(value, "screenOpen") || !text(value, "screenId").isEmpty())
                throw new BridgeException("stale_world", "Navigation lost its exact active developer/background/world/screen context.");
            if (!flag(control, "available") || !"integrated_server_tick".equals(text(control, "source")) || !playerId.equals(text(control, "playerId"))
                    || !request.session.equals(text(control, "sessionId")) || JsonData.integer(control, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != request.dimension
                    || (!request.world.equals(text(control, "worldName")) && !request.world.equals(text(control, "worldFolder")))
                    || JsonData.number(control, "ageMillis", 0, 1000) > 1000 || sample(control) < baselineSample
                    || flag(control, "creative") != creative || flag(player, "creative") != creative
                    || !flag(object(control, "openContainer"), "isInventoryContainer") || !flag(object(control, "clientContainer"), "isInventoryContainer")
                    || JsonData.integer(object(control, "openContainer"), "windowId", 0, Integer.MAX_VALUE) != JsonData.integer(object(control, "clientContainer"), "windowId", 0, Integer.MAX_VALUE)
                    || JsonData.number(player, "health", .000001, 1000000) <= 0)
                throw new BridgeException("navigation_server_control_unavailable", "Navigation requires fresh matching actual server/client actor, mode and closed-container receipts. Control observation: "
                        + JsonData.bounded(control.toString(), 2048));
            if (request.requireSurvivalActor && !"developer_teleport".equals(request.strategy) && creative) throw new BridgeException("permission_denied", "Explicit survival comparisons require the real survival actor, without a creative-mode toggle.");
            if ("ability_flight".equals(request.strategy) && (!flag(player, "allowFlying") || !flag(object(control, "pose"), "allowFlying")))
                throw new BridgeException("permission_denied", "Ability flight requires existing actual client/server permission; equip a real flight item normally first.");
            if (("walking".equals(request.strategy) || "walking_step".equals(request.strategy)) && (flag(player, "flying") || flag(object(control, "pose"), "flying")))
                throw new BridgeException("invalid_state", "Walking cannot run while either real player is flying.");
            if ("developer_teleport".equals(request.strategy) && (flag(player,"flying") != teleportFlying || flag(object(control,"pose"),"flying") != teleportFlying))
                throw new BridgeException("flight_not_confirmed", "Command teleport preserves the initial verified dual flight/ground posture; inspect actual state, never replay.");
            observeFlightCoast(value);
            latest = value;
            if ("walking".equals(request.strategy)) traceWalking(snapshotTick(value));
            if ("ability_flight".equals(request.strategy) && positionReady()) {
                long observedTick = snapshotTick(value);
                if (observedTick >= 0) trace(observedTick, "observed", held);
            }
        }
        private void observeFlightCoast(JsonObject value) {
            if (!"ability_flight".equals(request.strategy)) return;
            long tick = snapshotTick(value); if (tick < 0 || tick == motionObservationTick) return;
            JsonObject actor = object(value,"player"); Point position = pose(actor);
            if (actor.has("flySpeed")) {
                double speed = JsonData.number(actor,"flySpeed",.000001,.5);
                if (Double.isFinite(observedFlySpeed) && speed != observedFlySpeed) resetCoastModel();
                observedFlySpeed = speed;
            }
            double mx = motion(actor,"motionX"), mz = motion(actor,"motionZ"), speed2 = previousMotionX*previousMotionX+previousMotionZ*previousMotionZ;
            boolean horizontalIdle = actor.has("nativeMoveForward") && actor.has("nativeMoveStrafing")
                    ? Math.abs(motion(actor,"nativeMoveForward"))<.000001 && Math.abs(motion(actor,"nativeMoveStrafing"))<.000001
                    : !held.contains("forward") && !held.contains("back") && !held.contains("left") && !held.contains("right");
            if (tick == motionObservationTick+1 && speed2 > .000001 && horizontalIdle && previousHorizontalIdle
                    && flag(actor,"flying") && !flag(actor,"onGround")) {
                double drag = (mx*previousMotionX+mz*previousMotionZ)/speed2;
                double scale = ((position.x-previousFlightX)*previousMotionX+(position.z-previousFlightZ)*previousMotionZ)/speed2;
                double transverse = Math.abs(mx*previousMotionZ-mz*previousMotionX)/speed2;
                if (drag >= .05 && drag <= (double).91F+.001 && scale >= .05 && scale <= 1.001 && transverse < .001) {
                    if (coastModelSamples > 0 && (Math.abs(drag-flightCoastDrag)>=.03 || Math.abs(scale-flightCoastScale)>=.03)) resetCoastModel();
                    flightCoastDrag = drag; flightCoastScale = scale; coastModelSamples++;
                } else if (speed2 > .0001 && (Math.hypot(mx,mz)>.005 || scale>1.001 || transverse>=.001)) {
                    // A changed hook, external impulse or non-damping response cannot retain an old armor model.
                    resetCoastModel();
                }
            }
            previousFlightX=position.x; previousFlightZ=position.z; previousMotionX=mx; previousMotionZ=mz; motionObservationTick=tick;
            previousHorizontalIdle=horizontalIdle;
        }
        private void resetCoastModel() { coastModelSamples = 0; flightCoastDrag=(double).91F; flightCoastScale=1; }
        public double coastDrag() { return coastModelSamples >= 3 ? flightCoastDrag : (double).91F; }
        public double coastScale() { return coastModelSamples >= 3 ? flightCoastScale : 1; }
        public boolean requiresLanding() { return !request.finishFlying && !teleportFlying; }
        public Point current() { return pose(object(latest, "player")); }
        public Point destination() { return route.get(waypoint); }
        public double walkingTolerance() { return waypoint < route.size() - 1 || request.goal instanceof InteractionNavigationGoal ? .035 : TOLERANCE; }
        private boolean flexibleFastWalkingCorner() {
            return "walking".equals(request.strategy) && walkingRouteReady
                    && Double.isFinite(walkingAcceleration) && walkingFriction > 0 && walkingFriction < 1
                    && walkingAcceleration * .3F / (1 - walkingFriction) > 2 * .035
                    && request.declared.isEmpty() && !walkingWindowBoundary() && step == null
                    && waypoint < route.size() - 1 && !reanchoredWalkingCorners.contains(waypoint)
                    && Math.abs(current().y - route.get(waypoint).y) < .0001
                    && Math.abs(current().y - route.get(waypoint + 1).y) < .0001;
        }
        private boolean walkingWindowBoundary() {
            if (!request.segmented || waypoint >= route.size()) return false;
            // Segment mode also contains ordinary bends inside each planned window.
            // Preserve only the published window endpoints, not every automatic bend.
            Point target = route.get(waypoint);
            for (Point boundary : planningTargets) if (target.distance(boundary) <= .0001) return true;
            return false;
        }
        private double walkingCornerRadius() {
            // Half of one unavoidable sneak pulse, with the existing alignment tolerance.
            return Math.min(.5, walkingAcceleration * .3F / (1 - walkingFriction) / 2 + .035);
        }
        public boolean reanchorWalkingCorner(boolean nativeIdle) {
            if (!nativeIdle || !held.isEmpty() || !flexibleFastWalkingCorner() || !landed()) return false;
            JsonObject player = object(latest, "player"), server = object(object(latest, "serverControl"), "pose");
            Point actual = current(), previous = route.get(waypoint);
            if (!walkingStopped(player) || !walkingStopped(server) || actual.horizontal(pose(server)) > .005
                    || actual.near(previous, .035) || actual.horizontal(previous) > walkingCornerRadius()) return false;
            // Only an automatically planned flat bend changes. The final goal and explicit waypoints stay exact.
            route.set(waypoint, actual);
            reanchoredWalkingCorners.add(waypoint);
            lastWalkingCornerReanchor = new JsonObject();
            lastWalkingCornerReanchor.addProperty("waypoint", waypoint);
            lastWalkingCornerReanchor.add("planned", previous.json());
            lastWalkingCornerReanchor.add("actual", actual.json());
            lastWalkingCornerReanchor.addProperty("freshDualRouteProofRequired", true);
            walkingProofSweeps = null; clientProbe = 0; serverProbe = 0;
            walkingCoasting = false;
            return true;
        }
        boolean landed() { return flag(object(latest, "player"), "onGround") && flag(object(object(latest, "serverControl"), "pose"), "onGround")
                && !flag(object(latest, "player"), "flying") && !flag(object(object(latest, "serverControl"), "pose"), "flying"); }
        public Set<String> next(long tick) { return next(tick, Double.NaN); }
        public Set<String> next(long tick, double nativeFlySpeed) {
            if (!routeReady()) throw new BridgeException("invalid_state", "Native input requires a fully published observed route.");
            Set<String> keys = new LinkedHashSet<String>();
            if (tick == lastClientTick) return new LinkedHashSet<String>(held);
            lastClientTick = tick;
            if ("developer_teleport".equals(request.strategy)) return keys;
            if (step != null) return step.next(latest);
            JsonObject player = object(latest, "player"), control = object(latest, "serverControl"), serverPose = object(control, "pose");
            if ("ability_flight".equals(request.strategy)) {
                trace(tick, "observed", held);
                if (actualFlightDistance > (request.segmented ? NavigationSegments.MAX_DISTANCE : FlightPathPlanner.MAX_DISTANCE))
                    throw new BridgeException("unsupported", "Actual native flight exceeded the 32-block traveled-distance budget.");
                if (!flightObserved) {
                    flightPhase = "acquiring";
                    if (flag(player, "flying") && flag(serverPose, "flying") && sample(control) > mutationSample) {
                        flightObserved = true; flightSample = sample(control); flightPhase = "transit";
                    } else {
                        if (flag(player, "flying") && flag(serverPose, "flying")) return keys; // Await a newer real ACK without another jump edge.
                        if (jumpTicks >= 20) throw new BridgeException("flight_not_confirmed", "Normal jump double-tap did not acquire actual flight; no fallback or ability write.");
                        // Keep the second press held through ACK. Releasing then ascending again within seven
                        // vanilla ticks is a third jump edge and toggles acquired flight back off.
                        if (jumpTicks == 0 || jumpTicks >= 2) keys.add("jump");
                        jumpTicks++; return keys;
                    }
                }
                if ((!flag(player, "flying") || !flag(serverPose, "flying")) && !expectedLanding())
                    throw new BridgeException("flight_lost", "Actual flight ended before verified final landing; no automatic restart.");
            }
            Point position = current(), target = flightControlTarget();
            boolean flight = "ability_flight".equals(request.strategy);
            if (crosshairStopping) return keys;
            double mx = flight ? motion(player, "motionX") : 0, my = flight ? motion(player, "motionY") : 0, mz = flight ? motion(player, "motionZ") : 0;
            if (position.near(target, flight ? TOLERANCE : walkingTolerance()) && waypoint < route.size() - 1 && (!flight || Math.hypot(mx, mz) < .035 && Math.abs(my) < .035)) {
                if (!flight) {
                    if (!pose(serverPose).near(target, walkingTolerance()) || !landed() || !walkingStopped(player) || !walkingStopped(serverPose)) {
                walkingCoasting = true; return keys;
                    }
                    walkingCoasting = false;
                }
                if (!flight && Math.abs(route.get(waypoint+1).y-target.y)>.0001) {
                    waypoint++; return keys;
                }
                waypoint++; target = flightControlTarget();
            }
            if (!flight) {
                if (!flag(player, "onGround") || !flag(serverPose, "onGround"))
                    throw new BridgeException("unsupported", "The grounded walking actor lost support outside its proved height transition.");
                double vx = motion(player, "motionX"), vz = motion(player, "motionZ");
                if (request.goal instanceof InteractionNavigationGoal && positionReady()) {
                    walkingCoasting = !walkingStopped(player); return keys;
                }
                if (flexibleFastWalkingCorner()
                        && walkingStop(position, vx, vz, walkingFriction).horizontal(target) <= walkingCornerRadius()) {
                    // Settle first; the caller must prove the adjusted route on both sides before advancing.
                    walkingCoasting = true; return keys;
                }
                if (walkingCoasting && !walkingStopped(player)) return keys;
                walkingCoasting = false;
                if (!walkingStopped(player) && walkingStop(position, vx, vz, walkingFriction).near(target, walkingTolerance())) {
                    walkingCoasting = true; return keys;
                }
                boolean precision = walkingAcceleration > .5
                        || position.horizontal(target) < walkingAcceleration / (1-walkingFriction) + .3;
                if (precision) keys.add("sneak");
                HorizontalFlightInput input = walkingInput(position,target,vx,vz,walkingFriction,walkingAcceleration * (precision ? .3F : 1),walkingTolerance());
                steeringYaw = input.yaw;
                if (input.first > 0) keys.add("forward");
                else if (input.first < 0) keys.add("back");
                if (angleDifference(steeringYaw,travelYaw(position,target,vx,vz))>90) {
                    steeringYaw += 180;
                    if(keys.remove("forward")) keys.add("back"); else if(keys.remove("back")) keys.add("forward");
                }
                return keys;
            }
            flightPhase = waypoint == route.size() - 1 ? request.finishFlying ? "airborne_arrival" : "landing" : "transit";
            if (expectedLanding() && landed()) return keys;
            double distance = position.horizontal(target);
            // Replan from actual native motion on every tick. A .15 vertical input changes the
            // ideal stopping height by .375, so a fixed .09 deadband can keep reversing forever.
            // Only the first input is dispatched; future pulses are predictions, never queued keys.
            int vertical = verticalInput(position.y, my, target.y, tick, lastJumpEdgeTick, held.contains("jump"));
            boolean nativeHover = flag(player,"flying") && !flag(player,"onGround") && request.finishFlying
                    && (Math.abs(my)>=.005 || Math.abs(position.y-target.y)>TOLERANCE);
            if(nativeHover) {
                // Neutral native jump+sneak prevents equipment's held-key vertical boost while
                // residual motion settles. Dispatch at most one height pulse per settled window.
                boolean pulseReady=my==0 && tick-lastVerticalPulseTick>=FLIGHT_JUMP_EDGE_TICKS
                        && tick-lastJumpEdgeTick>=FLIGHT_JUMP_EDGE_TICKS;
                if(!pulseReady) vertical=0;
                keys.add("jump"); keys.add("sneak");
                if(vertical>0) keys.remove("sneak"); else if(vertical<0) keys.remove("jump");
            }
            else if (vertical > 0) keys.add("jump");
            else if (vertical < 0) keys.add("sneak");
            if (!request.finishFlying && waypoint == route.size() - 1 && distance <= TOLERANCE) {
                keys.remove("jump");
                if (!landed()) keys.add("sneak");
            }
            if (keys.contains("jump") && !held.contains("jump") && tick - lastJumpEdgeTick < FLIGHT_JUMP_EDGE_TICKS)
                keys.remove("jump");
            if (clearanceFlightWaypoint==waypoint && waypoint<route.size()-1 && position.y<destination().y)
                return keys; // Clear the ledge vertically before renewing horizontal input into it.
            if (flag(player, "flying") && !flag(player, "onGround")) {
                // Native sneak scales horizontal input by .3. Holding jump at the same time
                // cancels vanilla descent; no ability, speed or motion is changed.
                boolean precision = Math.abs(position.y-target.y)<=TOLERANCE && Math.abs(my)<.005
                        && (distance<nativeFlySpeed*.98/(1-(double).91F)+.3 || precisionFlightWaypoint==waypoint);
                if (precision && !held.contains("jump") && tick-lastJumpEdgeTick<FLIGHT_JUMP_EDGE_TICKS) return keys;
                if (precision && vertical==0 && (held.contains("jump") || tick-lastJumpEdgeTick>=FLIGHT_JUMP_EDGE_TICKS)) {
                    keys.add("sneak"); keys.add("jump");
                }
                // Short routes/corners use isolated native pulses. Three horizontal-idle ticks
                // expose the real coast response before another pulse can accumulate speed.
                HorizontalFlightInput input = horizontalInput(position, target, mx, mz, nativeFlySpeed, keys.contains("sneak"),coastDrag(),coastScale());
                // The observation window must not defer a required reverse brake while actual
                // momentum carries the actor out of the proved arrival region.
                double impulseDot=(-Math.sin(Math.toRadians(input.yaw))*mx+Math.cos(Math.toRadians(input.yaw))*mz)*input.first;
                if (precision && tick-lastHorizontalPulseTick<4 && impulseDot>=-.000001) return keys;
                steeringYaw = input.yaw;
                if (input.first > 0) keys.add("forward");
                else if (input.first < 0) keys.add("back");
                // Choose the equivalent input representation closest to actual travel. Braking
                // remains back while looking along momentum; the stopping predictor is unchanged.
                if (angleDifference(steeringYaw, travelYaw(position, target, mx, mz)) > 90) {
                    steeringYaw += 180;
                    if (keys.remove("forward")) keys.add("back");
                    else if (keys.remove("back")) keys.add("forward");
                }
            } else if (mx != 0 || mz != 0 || distance > TOLERANCE) {
                throw new BridgeException("unsupported", "Horizontal stop prediction requires actual airborne flight or a stationary supported transition.");
            }
            return keys;
        }
        /** Server observations request a client-owned brake; never write native keys on the server. */
        public boolean requestServerFlightBrake(Set<String> keys, JsonObject rejection, java.util.function.BooleanSupplier coastProof) {
            if (!"ability_flight".equals(request.strategy) || !flightObserved || rejection == null
                    || !"server".equals(text(rejection,"side")) || !"controlled".equals(text(rejection,"stage"))
                    || flightCheck(object(rejection,"blockers")) != FlightPathPlanner.Check.BLOCKED
                    || !keys.contains("sneak") || keys.contains("jump")
                    || !(keys.contains("forward") || keys.contains("back"))) return false;
            // Invalidate the old input proof even when a fresh coast observation is partial.
            serverSafetyAt = 0;
            if (!coastProof.getAsBoolean()) return false;
            serverFlightBrake.compareAndSet(null, new ServerFlightBrake(waypoint, keys, rejection));
            return true;
        }
        public boolean serverFlightBrakePending() { return serverFlightBrake.get() != null; }
        /** Caller has already released held keys and refreshed the actual client pose. */
        public void applyServerFlightBrake(java.util.function.BooleanSupplier coastProof, long tick) {
            ServerFlightBrake pending = serverFlightBrake.get();
            if (pending == null) return;
            if (pending.waypoint != waypoint) { serverFlightBrake.compareAndSet(pending,null); return; }
            Set<String> keys = new LinkedHashSet<String>(pending.keys);
            if (!brakeRejectedFlightInput(keys,coastProof,tick)) return;
            lastBrakedFlightBody = pending.rejection;
            serverFlightBrakes++;
            serverFlightBrake.compareAndSet(pending,null);
        }
        private static final class ServerFlightBrake {
            final int waypoint;
            final Set<String> keys;
            final JsonObject rejection;
            ServerFlightBrake(int waypoint, Set<String> keys, JsonObject rejection) {
                this.waypoint=waypoint; this.keys=new LinkedHashSet<String>(keys); this.rejection=rejection.deepCopy();
            }
        }
        /** A rejected powered envelope may only yield to a freshly proved unpowered envelope. */
        public boolean brakeRejectedFlightInput(Set<String> keys, java.util.function.BooleanSupplier coastProof, long tick) {
            if (!"ability_flight".equals(request.strategy) || keys.isEmpty() || !flightObserved
                    || !flag(object(latest,"player"),"flying") || flag(object(latest,"player"),"onGround")) return false;
            if (!coastProof.getAsBoolean()) return false;
            // An intermediate climb can meet a ledge before reaching its planned height.
            // Aim at the upper side of that same waypoint before
            // trying horizontal motion again. Every new pulse still needs the normal full
            // body proof; final arrival and the published route are not changed.
            if (waypoint < route.size()-1 && (keys.contains("forward") || keys.contains("back"))
                    && motion(object(latest,"player"),"motionY")==0
                    && current().y < destination().y)
                clearanceFlightWaypoint=waypoint;
            // When descent is blocked but the actor is already hovering above the route,
            // retain that real altitude for a separately proved horizontal approach. A final
            // exact-coordinate goal cannot use this; an interaction still needs its real ray,
            // normal reach and dual arrival rather than accepting the held altitude itself.
            if ((waypoint < route.size()-1 || request.crosshair!=null) && keys.contains("sneak") && !keys.contains("jump")
                    && (keys.contains("forward") || keys.contains("back"))
                    && motion(object(latest,"player"),"motionY")==0 && current().y>destination().y) {
                levelFlightWaypoint=waypoint; levelFlightY=current().y;
            }
            boolean neutralAltitude=keys.contains("jump") && keys.contains("sneak");
            keys.clear(); if(neutralAltitude) { keys.add("jump"); keys.add("sneak"); }
            precisionFlightWaypoint=waypoint;
            if(flightBrakePosition==null || current().distance(flightBrakePosition)>.03) {
                flightBrakePosition=current(); stationaryFlightBrakes=0;
            }
            blockedFlightInputsBraked++; stationaryFlightBrakes++; trace(tick,"blocked_input_braked",keys);
            if(stationaryFlightBrakes>=8)
                throw new BridgeException("flight_input_blocked", "Repeated powered and precision flight envelopes are blocked without actual movement. Inputs release; inspect the route/geometry, never replay use.");
            return true;
        }
        private Point flightControlTarget() {
            Point target=destination();
            if ("ability_flight".equals(request.strategy) && levelFlightWaypoint==waypoint) {
                // The final interaction waypoint still needs its original altitude. Once
                // horizontal travel has finished, resume that descent through the ordinary
                // per-pulse body/coast proofs instead of hovering until the deadline.
                if (waypoint==route.size()-1 && current().horizontal(target)<=TOLERANCE)
                    levelFlightWaypoint=-1;
                else return new Point(target.x,levelFlightY,target.z);
            }
            return "ability_flight".equals(request.strategy) && clearanceFlightWaypoint==waypoint && waypoint<route.size()-1
                    ? new Point(target.x,target.y+TOLERANCE,target.z) : target;
        }
        /** Input release only; predictions never replace the unchanged actual arrival ACK. */
        public boolean inputArrivalReady(double nativeFlySpeed) {
            if (!positionReady()) return false;
            if (crosshairStopping) {
                JsonObject player = object(latest,"player"), server = object(object(latest,"serverControl"),"pose");
                return walkingStopped(player) && walkingStopped(server)
                        && (!request.finishFlying || Math.abs(motion(player,"motionY")) <= .005 && Math.abs(motion(server,"motionY")) <= .005);
            }
            if ("walking".equals(request.strategy)) return walkingStopped(object(latest, "player"))
                    && walkingStopped(object(object(latest, "serverControl"), "pose"));
            if (!"ability_flight".equals(request.strategy) || landed()) return true;
            JsonObject player = object(latest, "player");
            double mx = motion(player, "motionX"), mz = motion(player, "motionZ");
            if (flag(player, "onGround")) {
                if (mx != 0 || mz != 0) throw new BridgeException("unsupported", "A moving grounded flight pose has no airborne stopping model.");
                return true;
            }
            requireHorizontalSpeed(nativeFlySpeed);
            return horizontalStop(current(), mx, mz, 0, 0, 0, 0, 0,(double).91F,coastDrag(),coastScale()).horizontal(request.destination) <= TOLERANCE;
        }
        boolean expectedLanding() {
            return !request.finishFlying && waypoint == route.size() - 1 && current().near(request.destination);
        }
        public void dispatched(Set<String> keys, long tick) {
            if (!keys.isEmpty()) { nativeMovementDispatched = true; arrivalPoseReport.leaveArrival("native_keys_dispatched"); }
            if (step != null) step.dispatched(keys, held, sample(object(latest, "serverControl")));
            if (!"ability_flight".equals(request.strategy)) return;
            if(keys.contains("forward") || keys.contains("back")) lastHorizontalPulseTick=tick;
            if(keys.contains("jump")!=keys.contains("sneak")) lastVerticalPulseTick=tick;
            if (keys.contains("jump") && !held.contains("jump")) { lastJumpEdgeTick = tick; jumpEdges++; }
            trace(tick, "keys", keys);
            flightKeys = java.util.Collections.unmodifiableSet(new LinkedHashSet<String>(keys));
        }
        public void observeNativeJump(boolean down, long tick) {
            if (step != null) step.observe(latest, down, tick);
            if (!"ability_flight".equals(request.strategy) || nativeJumpObservedTick == tick) return;
            if (down && !nativeJumpDown) { nativeJumpEdges++; lastNativeJumpEdgeTick = tick - 1; }
            nativeJumpDown = down; nativeJumpObservedTick = tick;
        }
        private void traceWalking(long tick) {
            if (tick < 0 || tick == walkingTraceTick) return;
            walkingTraceTick = tick;
            JsonObject row = new JsonObject(), player = object(latest,"player"), server = object(object(latest,"serverControl"),"pose");
            row.addProperty("tick",tick); row.addProperty("waypoint",waypoint);
            row.add("client",pose(player).json()); row.add("server",pose(server).json());
            row.addProperty("yaw", JsonData.number(player,"yaw",-360000,360000)); row.addProperty("pitch",JsonData.number(player,"pitch",-90,90));
            row.addProperty("onGround",flag(player,"onGround")); row.addProperty("phase",step == null ? "grounded" : step.phase);
            JsonArray keys = new JsonArray(); for (String key : held) keys.add(new com.google.gson.JsonPrimitive(key)); row.add("keys",keys);
            if (walkingTrace.size() == MAX_FLIGHT_TRACE) walkingTrace.remove(0);
            walkingTrace.add(row);
        }
        public synchronized void trace(long tick, String event, Set<String> keys) {
            if (!"ability_flight".equals(request.strategy) || latest == null) return;
            if ("observed".equals(event) && traceTick == tick) return;
            if ("observed".equals(event)) { traceTick = tick; actualFlightDistance += lastFlightPosition.distance(current()); lastFlightPosition = current(); }
            JsonObject entry = new JsonObject(), player = object(latest, "player"), control = object(latest, "serverControl"), server = object(control, "pose");
            long capturedTick = snapshotTick(latest);
            if ("observed".equals(event)) { lastObservedSnapshotTick = capturedTick; lastObservedSample = sample(control); }
            entry.addProperty("actualDistanceBlocks", actualFlightDistance);
            entry.addProperty("tick", tick); entry.addProperty("event", event); entry.addProperty("phase", flightPhase); entry.addProperty("waypoint", waypoint);
            entry.addProperty("snapshotClientTick", capturedTick); entry.addProperty("retainedSnapshot", !"observed".equals(event));
            entry.addProperty("snapshotFromEventTick", capturedTick >= 0 && capturedTick == tick);
            entry.addProperty("serverSampleId", sample(control)); entry.addProperty("jumpEdges", jumpEdges); entry.addProperty("lastJumpEdgeTick", lastJumpEdgeTick);
            entry.addProperty("nativeJumpDown", nativeJumpDown); entry.addProperty("nativeJumpEdges", nativeJumpEdges);
            entry.addProperty("clientGroundFloorProof", clientGroundFloorProof); entry.addProperty("serverGroundFloorProof", serverGroundFloorProof);
            entry.addProperty("lastNativeJumpEdgeTick", lastNativeJumpEdgeTick); entry.addProperty("nativeInputObservedTick", nativeJumpObservedTick);
            for (String name : new String[] {"flying", "allowFlying", "onGround"}) { entry.addProperty("client_" + name, flag(player, name)); entry.addProperty("server_" + name, flag(server, name)); }
            entry.add("client", pose(player).json()); entry.add("server", pose(server).json());
            JsonObject velocity = new JsonObject();
            for (String name : new String[] {"motionX", "motionY", "motionZ"}) { velocity.addProperty("client_" + name, motion(player, name)); velocity.addProperty("server_" + name, motion(server, name)); }
            entry.add("motion", velocity); JsonArray pressed = new JsonArray(); for (String key : keys) pressed.add(new com.google.gson.JsonPrimitive(key)); entry.add("keys", pressed);
            if (flightTrace.size() == MAX_FLIGHT_TRACE) { flightTrace.remove(0); traceDropped++; }
            flightTrace.add(entry);
        }
        synchronized JsonObject traceReceipt(int limit) {
            JsonObject result = new JsonObject(); result.addProperty("phase", flightPhase); result.addProperty("flightObserved", flightObserved);
            result.addProperty("jumpEdges", jumpEdges); result.addProperty("jumpEdgeGuardTicks", FLIGHT_JUMP_EDGE_TICKS);
            result.addProperty("nativeJumpEdges", nativeJumpEdges);
            result.addProperty("clientGroundFloorProof", clientGroundFloorProof); result.addProperty("serverGroundFloorProof", serverGroundFloorProof);
            result.addProperty("verticalDrag", .6); result.addProperty("verticalCoastFactor", 2.5);
            result.addProperty("inertiaHorizonTicks", 1);
            result.addProperty("blockedFlightInputsBraked", blockedFlightInputsBraked);
            result.addProperty("serverFlightBrakes", serverFlightBrakes);
            result.addProperty("serverFlightBrakePending", serverFlightBrakePending());
            result.addProperty("stationaryFlightBrakes",stationaryFlightBrakes);
            result.addProperty("clearanceFlightWaypoint",clearanceFlightWaypoint);
            result.addProperty("levelFlightWaypoint",levelFlightWaypoint);
            result.addProperty("inertiaGeometry","bounded_continuous_horizontal_trajectory_with_full_vertical_envelope");
            if (lastBrakedFlightBody != null && geometryRejection == null) result.add("lastBrakedInput",lastBrakedFlightBody);
            if (routeReady()) result.add("nextTarget",destination().json());
            result.addProperty("coastModelSamples",coastModelSamples); result.addProperty("observedCoastDrag",coastDrag()); result.addProperty("observedCoastScale",coastScale());
            result.addProperty("recordsDropped", traceDropped + Math.max(0, flightTrace.size() - limit));
            result.addProperty("actualDistanceBlocks", actualFlightDistance);
            result.addProperty("arrivalAckWait", arrivalAckWait); result.addProperty("stableSampleId", stableSample);
            result.addProperty("lastObservedTick", traceTick); result.addProperty("lastObservedSnapshotTick", lastObservedSnapshotTick);
            result.addProperty("lastObservedServerSampleId", lastObservedSample);
            if (latest != null) {
                JsonObject control = object(latest, "serverControl");
                result.addProperty("latestSnapshotClientTick", snapshotTick(latest));
                result.addProperty("latestServerSampleId", sample(control));
                result.addProperty("serverSampleAgeMillis", JsonData.number(control, "ageMillis", 0, 1000));
                result.addProperty("serverClientDistance", current().distance(pose(object(control, "pose"))));
            }
            result.addProperty("inputKeysReleased", flightKeys.isEmpty());
            result.addProperty("keyReleaseConfirmed", keyReleaseConfirmed);
            result.addProperty("ownerClearConfirmed", ownerClearConfirmed);
            result.addProperty("maxRecords", MAX_FLIGHT_TRACE); result.addProperty("noAbilityOrMotionWrites", true);
            if (geometryRejection != null) result.add("geometryRejection", new com.google.gson.JsonParser().parse(geometryRejection.toString()));
            JsonArray records = new JsonArray(); for (int i = Math.max(0, flightTrace.size() - limit); i < flightTrace.size(); i++) records.add(flightTrace.get(i));
            result.add("records", records); return result;
        }
        public synchronized void rejectedFlightBody(String side, String stage, JsonObject actor, AxisAlignedBB nativeBody,
                AxisAlignedBB rejectedBody, Set<String> nextKeys, JsonObject observation) {
            JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("side", JsonData.bounded(side, 16));
            result.addProperty("stage", JsonData.bounded(stage, 24)); result.addProperty("waypoint", waypoint);
            JsonObject actual = new JsonObject();
            for (String key : new String[] {"x", "feetY", "z", "motionX", "motionY", "motionZ", "yaw", "steeringYaw", "flySpeed", "width", "height"}) copyFinite(actor, actual, key);
            actual.addProperty("nativeWidthX", nativeBody.maxX - nativeBody.minX); actual.addProperty("nativeWidthZ", nativeBody.maxZ - nativeBody.minZ);
            actual.addProperty("nativeHeight", nativeBody.maxY - nativeBody.minY); result.add("actual", actual);
            JsonArray keys = new JsonArray();
            for (String key : new String[] {"forward", "back", "jump", "sneak"}) if (nextKeys.contains(key)) keys.add(new com.google.gson.JsonPrimitive(key));
            result.add("nextKeys", keys); result.add("rejectedBounds", bounds(rejectedBody));
            JsonObject summary = new JsonObject(); summary.addProperty("status", JsonData.bounded(text(observation, "status"), 32));
            for (String key : new String[] {"loaded", "occupiable", "entityCoveragePartial", "liquid", "hazard", "collisionsTruncated", "supported", "fullSupportCoverage", "canStand"}) {
                JsonElement value = observation.get(key);
                summary.add(key, value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? new com.google.gson.JsonPrimitive(value.getAsBoolean()) : JsonNull.INSTANCE);
            }
            summary.addProperty("bodyCheck", flightCheck(observation).name().toLowerCase(java.util.Locale.ROOT));
            summary.addProperty("supportCheck", walkingCheck(observation).name().toLowerCase(java.util.Locale.ROOT));
            copyFinite(observation, summary, "collisionCount");
            JsonArray collisionBoxes = new JsonArray();
            if (observation.has("collisions") && observation.get("collisions").isJsonArray()) {
                JsonArray values = observation.getAsJsonArray("collisions");
                for (int i = 0; i < Math.min(2, values.size()); i++) if (values.get(i).isJsonObject()) {
                    JsonObject box = new JsonObject(); for (String key : new String[] {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"}) copyFinite(values.get(i).getAsJsonObject(), box, key);
                    collisionBoxes.add(box);
                }
            }
            summary.add("collisionBoxes", collisionBoxes); JsonArray entityIds = new JsonArray();
            if (observation.has("blockingEntities") && observation.get("blockingEntities").isJsonArray()) {
                JsonArray values = observation.getAsJsonArray("blockingEntities"); summary.addProperty("blockingEntityCount", values.size());
                for (int i = 0; i < Math.min(2, values.size()); i++) if (values.get(i).isJsonPrimitive() && values.get(i).getAsJsonPrimitive().isNumber())
                    entityIds.add(new com.google.gson.JsonPrimitive(values.get(i).getAsInt()));
            }
            summary.add("blockingEntityIds", entityIds); result.add("blockers", summary);
            if (result.toString().length() > 2000) {
                summary.remove("collisionBoxes"); summary.remove("blockingEntityIds"); summary.addProperty("detailsOmittedForCharacterBound", true);
            }
            geometryRejection = result;
        }
        public synchronized void clearFlightBodyRejection() { geometryRejection = null; }
        public BridgeException diagnostic(BridgeException error) {
            if (step != null) {
                String reason = error.getMessage() == null ? "walking_step failed." : error.getMessage();
                if (reason.length() > 400) reason = reason.substring(0, 400);
                for (int tail = 3; tail >= 0; tail--) {
                    JsonObject evidence = step.receipt(keyReleaseConfirmed && held.isEmpty(), ownerClearConfirmed, false, tail);
                    evidence.addProperty("errorCode", error.code); evidence.addProperty("mutationDispatched", mutation);
                    String message = reason + " Step diagnostic: " + evidence;
                    if (message.length() <= MAX_FLIGHT_ERROR_MESSAGE) return new BridgeException(error.code, message);
                }
                return new BridgeException(error.code, reason);
            }
            if (!"ability_flight".equals(request.strategy)) {
                if (!"walking".equals(request.strategy)) return error;
                String reason = error.getMessage() == null ? "Navigation failed." : error.getMessage();
                if (reason.length() > 512) reason = reason.substring(0, 509) + "...";
                JsonObject planning = walkingPlanner == null ? new JsonObject() : walkingPlanner.diagnostic();
                planning.addProperty("goalRoutesAttempted",goalRouteAttempts); planning.addProperty("goalCandidatesChecked",goalCandidateIndex);
                planning.addProperty("goalSearchBounded",goalSearchBounded); planning.addProperty("movementDispatched",mutation);
                planning.addProperty("keysReleased",keyReleaseConfirmed); planning.addProperty("ownerClear",ownerClearConfirmed);
                planning.addProperty("waypoint",waypoint); planning.add("current",current().json()); planning.add("nextTarget",destination().json());
                planning.add("legStart", (waypoint == 0 ? start : route.get(waypoint - 1)).json());
                planning.addProperty("routePointCount", route.size());
                planning.addProperty("walkingAcceleration",walkingAcceleration); planning.addProperty("walkingFriction",walkingFriction);
                planning.addProperty("walkingCornerReanchors", reanchoredWalkingCorners.size());
                planning.addProperty("walkingSegmented", request.segmented);
                planning.addProperty("walkingWindowBoundary", walkingWindowBoundary());
                planning.addProperty("walkingCornerEligible", flexibleFastWalkingCorner());
                if (lastWalkingCornerReanchor != null) planning.add("lastWalkingCornerReanchor", lastWalkingCornerReanchor.deepCopy());
                JsonArray walkingTail = new JsonArray(); for(int i=Math.max(0,walkingTrace.size()-2);i<walkingTrace.size();i++) walkingTail.add(walkingTrace.get(i));
                planning.add("traceTail",walkingTail);
                if (request.crosshair != null) planning.add("crosshairArrival",request.crosshair.receipt(crosshairStopping));
                if (geometryRejection != null) {
                    planning.remove("lastRejectedEdges");
                    for (int count=3;count>=0;count--) {
                        JsonObject execution = new JsonObject(); execution.addProperty("phase",walkingCoasting ? "coasting" : "native_movement");
                        execution.addProperty("arrivalAckWait",arrivalAckWait);
                        execution.add("destination",destination().json()); execution.add("geometryRejection",geometryRejection);
                        JsonArray tail=new JsonArray(); for (int i=Math.max(0,walkingTrace.size()-count);i<walkingTrace.size();i++) tail.add(walkingTrace.get(i));
                        execution.add("trace",tail);
                        String detail=reason+" Walking plan diagnostic: "+planning+" Walking execution diagnostic: "+execution;
                        if (detail.length()<=MAX_FLIGHT_ERROR_MESSAGE) return new BridgeException(error.code,detail);
                    }
                    JsonObject summary = new JsonObject(); summary.addProperty("phase",walkingCoasting ? "coasting" : "native_movement");
                    summary.addProperty("arrivalAckWait",arrivalAckWait);
                    summary.add("geometryRejection",geometryRejection); summary.addProperty("optionalTraceOmitted",true);
                    return new BridgeException(error.code,reason+" Walking execution diagnostic: "+summary);
                }
                String plan = planning.toString();
                String message = reason + " Walking plan diagnostic: " + plan + " Arrival pose diagnostic: " + arrivalPoseReport.receipt(this);
                if (message.length() > MAX_FLIGHT_ERROR_MESSAGE) message = reason + " Walking plan diagnostic: " + plan
                        + " keysReleased=" + keyReleaseConfirmed + " ownerClear=" + ownerClearConfirmed + " movementDispatched=" + mutation;
                return new BridgeException(error.code, message);
            }
            String reason = error.getMessage() == null ? "Navigation failed." : error.getMessage();
            boolean truncated = reason.length() > 512;
            if (truncated) reason = reason.substring(0, 509) + "...";
            // MCP's error.message contract is 4096 characters. Bound the entire serialized message
            // more conservatively, dropping complete oldest records and preserving valid JSON.
            for (int count = 8; count >= 0; count--) {
                JsonObject evidence = traceReceipt(count); evidence.addProperty("sourceErrorMessageTruncated", truncated);
                evidence.add("arrivalPoseReport", arrivalPoseReport.diagnosticReceipt(this));
                if (request.crosshair != null) evidence.add("crosshairArrival",request.crosshair.receipt(crosshairStopping));
                String message = reason + " Flight diagnostic: " + evidence;
                if (message.length() <= MAX_FLIGHT_ERROR_MESSAGE) return new BridgeException(error.code, message);
            }
            JsonObject compact = traceReceipt(0);
            Set<String> retained = new LinkedHashSet<String>(java.util.Arrays.asList("phase", "flightObserved", "arrivalAckWait", "stableSampleId",
                    "keyReleaseConfirmed", "ownerClearConfirmed", "inputKeysReleased", "recordsDropped", "records", "geometryRejection", "lastBrakedInput", "nextTarget",
                    "coastModelSamples", "observedCoastDrag", "observedCoastScale", "stationaryFlightBrakes", "inertiaGeometry"));
            List<String> omitted = new ArrayList<String>();
            for (Map.Entry<String, JsonElement> entry : compact.entrySet()) if (!retained.contains(entry.getKey())) omitted.add(entry.getKey());
            for (String key : omitted) compact.remove(key);
            compact.addProperty("sourceErrorMessageTruncated", true); compact.addProperty("optionalTraceDetailOmitted", true);
            compact.add("arrivalPoseReport", arrivalPoseReport.diagnosticReceipt(this));
            if (reason.length() > 128) reason = reason.substring(0, 125) + "...";
            return new BridgeException(error.code, reason + " Flight diagnostic: " + compact);
        }
        public boolean crosshairRouteEligible() {
            // Interaction goals prove actual endpoint bodies/faces only on the final
            // segment. Earlier ray hits must not stop input before that proof can run.
            return request.declared.isEmpty() && !(request.goal instanceof InteractionNavigationGoal)
                    || waypoint == route.size() - 1;
        }
        public boolean positionReady() {
            if (!routeReady()) return false;
            Point client = current(), server = pose(object(object(latest, "serverControl"), "pose"));
            boolean endpoint = request.goal instanceof InteractionNavigationGoal
                    ? ((InteractionNavigationGoal) request.goal).actualContains(client, true)
                        && ((InteractionNavigationGoal) request.goal).actualContains(server, false)
                        && (request.crosshair == null || request.crosshair.confirmed(latest))
                    : crosshairStopping && crosshairRouteEligible() && request.crosshair != null && request.crosshair.confirmed(latest)
                        || client.near(request.destination) && server.near(request.destination);
            return (waypoint == route.size() - 1 || crosshairStopping && crosshairRouteEligible() && request.crosshair != null && request.crosshair.confirmed(latest))
                    && endpoint && client.near(server)
                    && (!requiresLanding() ? flag(object(latest, "player"), "flying") && flag(object(object(latest, "serverControl"), "pose"), "flying") : landed())
                    && (!"ability_flight".equals(request.strategy) || flightObserved)
                    && (request.goal == null || request.goal.contains(client) && request.goal.contains(server))
                    && (step == null || step.arrival(latest));
        }
        public boolean acknowledged() {
            if (!positionReady()) { arrivalAckWait = "actual_position_or_posture_pending"; return false; }
            if (!held.isEmpty()) { arrivalAckWait = "owned_keys_pending"; return false; }
            if (sample(object(latest, "serverControl")) <= mutationSample) { arrivalAckWait = "post_mutation_sample_pending"; return false; }
            Point client = current(), server = pose(object(object(latest, "serverControl"), "pose"));
            long observed = sample(object(latest, "serverControl"));
            if (stableClient == null || client.distance(stableClient) > .00001 || server.distance(stableServer) > .00001) {
                stableClient = client; stableServer = server; stableSample = observed; arrivalAckWait = "stable_pose_sample_pending"; return false;
            }
            if (observed <= stableSample) { arrivalAckWait = "new_server_sample_pending"; return false; }
            if (client.distance(server) > .0001) { arrivalAckWait = "actual_server_client_sync_pending"; return false; }
            if (request.crosshair != null && !request.crosshair.confirmed(latest)) { arrivalAckWait = "actual_aim_sync_pending"; return false; }
            if (request.lookAt != null && request.crosshair == null) {
                JsonObject player = object(latest, "player"), pose = object(object(latest, "serverControl"), "pose");
                double[] aim = aim(current(), JsonData.number(player, "eyeY", 0, 257) - current().y, request.lookAt);
                if (angleDifference(JsonData.number(player, "yaw", -360000, 360000), aim[0]) > .02
                        || Math.abs(JsonData.number(player, "pitch", -90, 90) - aim[1]) > .02
                        || angleDifference(JsonData.number(pose, "yaw", -360000, 360000), aim[0]) > .02
                        || Math.abs(JsonData.number(pose, "pitch", -90, 90) - aim[1]) > .02) { arrivalAckWait = "actual_aim_sync_pending"; return false; }
            }
            arrivalAckWait = "confirmed";
            return true;
        }
        public JsonObject completed(long observationTick) {
            JsonObject result = new JsonObject(); result.addProperty("operation", OPERATION); result.addProperty("status", "completed");
            if (eyeSettling != null) result.add("eyeSettling", eyeSettling);
            if (startSettling != null) result.add("startSettling", startSettling);
            result.addProperty("taskId",taskId);
            result.addProperty("strategy", request.strategy); result.addProperty("sessionId", request.session); result.addProperty("dimension", request.dimension);
            result.addProperty("expectedWorld", request.world); result.addProperty("playerId", playerId); result.addProperty("arrivalConfirmed", true);
            result.addProperty("replayAllowed", false); result.addProperty("fallbackUsed", false); result.addProperty("outcomeUnknown", false);
            result.addProperty("mutationDispatched", mutation); result.addProperty("elapsedMillis", Math.max(0, System.nanoTime() - started) / 1000000.0);
            result.addProperty("toleranceBlocks", TOLERANCE); result.addProperty("flightTransitConfirmed", flightObserved); result.addProperty("flightTransitSampleId", flightSample);
            result.addProperty("arrivalCriterion", crosshairStopping ? "actual_crosshair_normal_reach_safe_dual_pose" : "coordinate_or_declared_goal");
            result.addProperty("coordinateToleranceApplied", !crosshairStopping);
            result.addProperty("scope", "Bounded developer test navigation; walking/ability_flight use normal keys, explicit developer_teleport uses one normal /tp. Arrival is a fresh actual pose ACK, never proof of formation or production.");
            JsonObject taskPlan = new JsonObject(); taskPlan.addProperty("segmented",request.segmented); taskPlan.add("windows",planningWindows);
            taskPlan.addProperty("distanceBlocks",plannedDistance); taskPlan.addProperty("goalEndpointCandidatesChecked",goalCandidateIndex+1);
            taskPlan.addProperty("goalRoutesAttempted",goalRouteAttempts); taskPlan.addProperty("goalSearchBounded",goalSearchBounded);
            if(bestGoalRoute!=null) taskPlan.addProperty("selectedRouteScoreTicks",bestGoalScore);
            taskPlan.addProperty("goalSatisfied",request.goal == null || request.goal.contains(current()) && request.goal.contains(pose(object(object(latest,"serverControl"),"pose"))));
            taskPlan.addProperty("computationOwner","client"); taskPlan.addProperty("allWindowsProvedBeforeMovement",true);
            taskPlan.add("costModel",costs.json()); result.add("navigationPlan",taskPlan);
            result.add("start", start.json()); result.add("requestedDestination", request.destination.json());
            result.add("serverPoseAck", object(latest, "serverControl")); result.add("actualClientPose", object(latest, "player"));
            if (walkingPlanner != null) {
                JsonObject planning = new JsonObject();
                planning.addProperty("status", walkingPlanner.reason());
                planning.addProperty("scope", "request_local_grounded_astar_collision_proved_line_shortcuts_and_height_transitions");
                planning.addProperty("distanceBlocks", walkingPlanner.routeDistance());
                planning.addProperty("nodeCount", walkingPlanner.discoveredNodes());
                planning.addProperty("edgeCount", walkingPlanner.edges());
                planning.addProperty("geometrySamples", walkingPlanner.samples());
                planning.addProperty("rejectedSamples", walkingPlanner.rejectedSamples());
                planning.addProperty("partialSamples", walkingPlanner.partialSamples());
                planning.addProperty("slices", walkingPlanner.slices());
                planning.addProperty("interactionGoalChecks",walkingPlanner.diagnostic().get("interactionGoalChecks").getAsInt());
                planning.addProperty("workBudgetNanos", WalkingPathPlanner.WORK_NANOS);
                planning.addProperty("maxDistanceBlocks", WalkingPathPlanner.MAX_DISTANCE);
                planning.addProperty("maxNodes", WalkingPathPlanner.MAX_NODES);
                planning.addProperty("maxEdges", WalkingPathPlanner.MAX_EDGES);
                planning.addProperty("maxGeometrySamples", WalkingPathPlanner.MAX_SAMPLES);
                planning.addProperty("planningIsNotArrival", true);
                planning.addProperty("jumpSupported", NavigationSettings.maxJumpBlocks>0);
                planning.addProperty("maxJumpBlocks", NavigationSettings.maxJumpBlocks); planning.addProperty("maxDropBlocks", NavigationSettings.maxDropBlocks);
                planning.add("heightTransitions", walkingSteps);
                JsonArray trace = new JsonArray(); for (JsonObject row : walkingTrace) trace.add(row); planning.add("trace", trace);
                JsonArray points = new JsonArray(); for (Point point : route) points.add(point.json());
                planning.add("route", points); result.add("walkingPlan", planning);
            }
            if (flightPlanner != null) {
                JsonObject plan = new JsonObject(); plan.addProperty("status", flightPlanner.reason());
                plan.addProperty("scope", "request_local_loaded_3d_swept_actual_body"); plan.addProperty("planningIsNotArrival", true);
                plan.addProperty("distanceBlocks", flightPlanner.routeDistance()); plan.addProperty("nodeCount", flightPlanner.discoveredNodes());
                plan.addProperty("edgeCount", flightPlanner.edges()); plan.addProperty("geometrySamples", flightPlanner.samples());
                plan.addProperty("rejectedSamples", flightPlanner.rejectedSamples()); plan.addProperty("partialSamples", flightPlanner.partialSamples());
                plan.addProperty("slices", flightPlanner.slices()); plan.addProperty("workBudgetNanos", FlightPathPlanner.WORK_NANOS);
                plan.addProperty("maxDistanceBlocks", FlightPathPlanner.MAX_DISTANCE); plan.addProperty("maxNodes", FlightPathPlanner.MAX_NODES);
                plan.addProperty("maxEdges", FlightPathPlanner.MAX_EDGES); plan.addProperty("maxGeometrySamples", FlightPathPlanner.MAX_SAMPLES);
                JsonArray points = new JsonArray(); for (Point point : route) points.add(point.json()); plan.add("route", points); result.add("flightPlan", plan);
            }
            if ("ability_flight".equals(request.strategy)) {
                flightPhase = "completed"; trace(observationTick, "arrival_ack", held);
                result.add("flightTrace", traceReceipt(MAX_FLIGHT_TRACE));
            }
            if (step != null) result.add("stepReceipt", step.receipt(keyReleaseConfirmed && held.isEmpty(), ownerClearConfirmed, true, GroundedStep.MAX_TRACE));
            if ("walking".equals(request.strategy) || "ability_flight".equals(request.strategy)) result.add("arrivalPoseReport", arrivalPoseReport.receipt(this));
            if (request.crosshair != null) result.add("crosshairArrival", request.crosshair.receipt(crosshairStopping));
            result.addProperty("baselineSampleId", mutationSample);
            result.addProperty("detailsIncluded", request.includeDetails);
            result.addProperty("inputKeysReleased", held.isEmpty() && flightKeys.isEmpty());
            result.addProperty("keyReleaseConfirmed", keyReleaseConfirmed); result.addProperty("ownerClearConfirmed", ownerClearConfirmed);
            // Keep scalar diagnostics and the unmodified authoritative pose ACK in ordinary reports.
            // Trace/route arrays are already bounded internally and are opt-in transport details.
            if (!request.includeDetails) {
                summarizeArray(taskPlan, "windows", "windowCount");
                if (result.has("walkingPlan")) {
                    JsonObject plan = result.getAsJsonObject("walkingPlan");
                    summarizeArray(plan, "route", "routePointCount"); summarizeArray(plan, "trace", "traceRecordCount");
                    summarizeArray(plan, "heightTransitions", "heightTransitionCount");
                }
                if (result.has("flightPlan")) summarizeArray(result.getAsJsonObject("flightPlan"), "route", "routePointCount");
                if (result.has("flightTrace")) summarizeArray(result.getAsJsonObject("flightTrace"), "records", "returnedRecordCount");
                if (result.has("stepReceipt")) summarizeArray(result.getAsJsonObject("stepReceipt"), "trace", "traceRecordCount");
            }
            receipt = result; return result;
        }
    }

    static void summarizeArray(JsonObject object, String key, String countKey) {
        JsonElement values = object.get(key);
        if (values != null && values.isJsonArray()) { object.addProperty(countKey, values.getAsJsonArray().size()); object.remove(key); }
    }

    /** One owning-client read. Values are actual actor fields, never requested coordinates. */
    public static final class ActualArrivalPose {
        final double x, feetY, stance, z, motionX, motionY, motionZ, forward, strafe;
        final float yaw, pitch;
        final boolean onGround, flying, jump, sneak, sprinting, inputAvailable;
        public ActualArrivalPose(double x, double feetY, double stance, double z, float yaw, float pitch,
                boolean onGround, boolean flying, double motionX, double motionY, double motionZ,
                boolean inputAvailable, double forward, double strafe, boolean jump, boolean sneak, boolean sprinting) {
            this.x = x; this.feetY = feetY; this.stance = stance; this.z = z; this.yaw = yaw; this.pitch = pitch;
            this.onGround = onGround; this.flying = flying; this.motionX = motionX; this.motionY = motionY; this.motionZ = motionZ;
            this.inputAvailable = inputAvailable; this.forward = forward; this.strafe = strafe; this.jump = jump; this.sneak = sneak; this.sprinting = sprinting;
        }
        boolean valid() {
            for (double value : new double[] {x, feetY, stance, z, yaw, pitch, motionX, motionY, motionZ, forward, strafe}) if (!Double.isFinite(value)) return false;
            return Math.abs(x) <= 29999999 && Math.abs(z) <= 29999999 && feetY >= 0 && feetY <= 253
                    && stance - feetY > .1 && stance - feetY < 1.65 && Math.abs(yaw) <= 360000 && Math.abs(pitch) <= 90
                    && Math.abs(motionY) <= 16;
        }
        boolean idle() { return valid() && inputAvailable && forward == 0 && strafe == 0 && !jump && !sneak && !sprinting
                && motionX == 0 && motionZ == 0 && (onGround && !flying ? motionY <= 0 : motionY == 0); }
        boolean stable(ActualArrivalPose other) {
            return other != null && new Point(x, feetY, z).distance(new Point(other.x, other.feetY, other.z)) <= .00001
                    && Math.abs(stance - other.stance) <= .00001 && angleDifference(yaw, other.yaw) <= .00001
                    && Math.abs(pitch - other.pitch) <= .00001 && onGround == other.onGround && flying == other.flying;
        }
        C03PacketPlayer.C06PacketPlayerPosLook packet() {
            if (!valid()) throw new BridgeException("invalid_state", "Actual arrival pose cannot form a truthful native stance report.");
            return new C03PacketPlayer.C06PacketPlayerPosLook(x, feetY, stance, z, yaw, pitch, onGround);
        }
        JsonObject json() {
            JsonObject value = new Point(x, feetY, z).json(); value.addProperty("stance", stance); value.addProperty("yaw", yaw); value.addProperty("pitch", pitch);
            value.addProperty("onGround", onGround); value.addProperty("flying", flying); value.addProperty("motionX", motionX); value.addProperty("motionY", motionY); value.addProperty("motionZ", motionZ);
            value.addProperty("inputAvailable", inputAvailable); value.addProperty("forward", forward); value.addProperty("strafe", strafe);
            value.addProperty("jump", jump); value.addProperty("sneak", sneak); value.addProperty("sprinting", sprinting); return value;
        }
    }
    public interface ArrivalPoseSink { void send(C03PacketPlayer.C06PacketPlayerPosLook packet); }

    /** Invocation-wide one-shot; optional idle observations are not ACK or server tick authority. */
    public static final class ArrivalPoseReport {
        long firstArrivalTick = -1, firstIdleTick = -1, lastIdleTick = -1, dispatchTick = -1, sendSample = -1, sendWorldTick = -1;
        public long dispatchStartedAt, enqueueReturnedAt, afterCaptureAt, afterSample = -1, afterWorldTick = -1, dispatchFirstIdleTick = -1, sendCaptureAt;
        int idleTicks, attempts, dispatchIdleTicks;
        public double gapBeforeSend = Double.NaN;
        public String status = "not_attempted", reason = "not_at_final_arrival", completionWait = "not_required";
        public boolean nativeKeysIdle, bodyProof, epochMatched, afterCaptureSeen;
        ActualArrivalPose idlePose, reported;
        public void leaveArrival(String reason) { idlePose = null; idleTicks = 0; firstIdleTick = -1; lastIdleTick = -1; this.reason = reason; }
        public void observe(long tick, ActualArrivalPose actual, boolean nativeKeysIdle) {
            if (firstArrivalTick < 0) firstArrivalTick = tick;
            this.nativeKeysIdle = nativeKeysIdle;
            if (!nativeKeysIdle || !actual.idle()) { leaveArrival("actual_input_or_motion_pending"); return; }
            if (!actual.stable(idlePose) || lastIdleTick > tick) { idlePose = actual; idleTicks = 1; firstIdleTick = tick; lastIdleTick = tick; }
            else if (tick > lastIdleTick) { idleTicks = Math.min(2, idleTicks + 1); lastIdleTick = tick; }
            reason = idleTicks >= 2 ? "actual_idle_stable" : "distinct_idle_tick_pending";
        }
        public boolean eligible(State state, ActualArrivalPose actual, boolean routeProved) {
            if (attempts != 0) { reason = "invocation_one_shot_consumed"; return false; }
            if ((!"walking".equals(state.request.strategy) && !"ability_flight".equals(state.request.strategy))
                    || !state.mutation || !state.nativeMovementDispatched) { reason = "outside_native_movement_scope"; return false; }
            if (!routeProved || !state.positionReady() || !state.keyReleaseConfirmed || !state.held.isEmpty() || !state.flightKeys.isEmpty()) {
                reason = "route_posture_or_release_pending"; return false;
            }
            if (!"actual_server_client_sync_pending".equals(state.arrivalAckWait)) { reason = "original_ack_not_sync_pending"; return false; }
            if (idleTicks < 2 || !nativeKeysIdle || !actual.idle() || !actual.stable(idlePose)) { reason = "distinct_actual_idle_pose_pending"; return false; }
            double gap = state.current().distance(pose(object(object(state.latest, "serverControl"), "pose")));
            if (gap <= .0001 || gap > .03 || new Point(actual.x, actual.feetY, actual.z).distance(state.current()) > .00001) {
                reason = "outside_actual_small_gap"; return false;
            }
            reason = "eligible_pending_current_body_and_epoch"; return true;
        }
        public boolean dispatch(ActualArrivalPose actual, long tick, long sample, long worldTick, ArrivalPoseSink sink, java.util.function.LongSupplier clock) {
            if (attempts != 0) return false;
            C03PacketPlayer.C06PacketPlayerPosLook packet = actual.packet();
            // Claim before invoking the native sink: an exception can follow a real side effect.
            attempts = 1; reported = actual; dispatchTick = tick; sendSample = sample; sendWorldTick = worldTick;
            dispatchIdleTicks = idleTicks; dispatchFirstIdleTick = firstIdleTick;
            dispatchStartedAt = clock.getAsLong(); status = "dispatching"; completionWait = "enqueue_return_pending";
            try { sink.send(packet); enqueueReturnedAt = clock.getAsLong(); status = "enqueued"; reason = "actual_pose_enqueued"; completionWait = "post_enqueue_capture_pending"; }
            catch (RuntimeException | LinkageError error) {
                status = "dispatch_outcome_unknown"; reason = error.getClass().getSimpleName(); completionWait = "dispatch_outcome_unknown";
                throw new BridgeException("navigation_pose_report_outcome_unknown", "The one actual arrival pose report has an unknown native dispatch outcome; input cleanup follows and no retry or fallback is allowed.");
            }
            return true;
        }
        public boolean completion(boolean matchingEpoch, long capturedAt, long sample, long worldTick) {
            if (attempts == 0) { completionWait = "not_required"; return true; }
            if (!"enqueued".equals(status)) { completionWait = "dispatch_outcome_unknown"; return false; }
            epochMatched = matchingEpoch;
            if (!matchingEpoch) { completionWait = "matching_actual_epoch_pending"; return false; }
            if (capturedAt <= enqueueReturnedAt || sample <= sendSample) { completionWait = "post_enqueue_capture_pending"; return false; }
            afterCaptureSeen = true; afterCaptureAt = capturedAt; afterSample = sample; afterWorldTick = worldTick;
            completionWait = "matching_post_enqueue_capture"; return true;
        }
        JsonObject receipt(State state) {
            JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("strategy", state.request.strategy);
            value.addProperty("attemptCount", attempts); value.addProperty("status", status); value.addProperty("reason", reason);
            value.addProperty("sessionId", state.request.session); value.addProperty("playerId", state.playerId); value.addProperty("dimension", state.request.dimension);
            value.addProperty("firstArrivalReleaseClientTick", firstArrivalTick); value.addProperty("firstIdleClientTick", firstIdleTick);
            value.addProperty("distinctIdleClientTicks", idleTicks); value.addProperty("nativeKeysIdle", nativeKeysIdle); value.addProperty("freshCurrentBodyProof", bodyProof);
            value.addProperty("dispatchClientTick", dispatchTick); value.addProperty("sendSampleId", sendSample); value.addProperty("sendServerWorldTick", sendWorldTick);
            value.addProperty("dispatchDistinctIdleClientTicks", dispatchIdleTicks); value.addProperty("dispatchFirstIdleClientTick", dispatchFirstIdleTick);
            if (Double.isFinite(gapBeforeSend)) value.addProperty("gapBeforeSendBlocks", gapBeforeSend); else value.add("gapBeforeSendBlocks", JsonNull.INSTANCE);
            value.addProperty("baselineSampleId", state.baselineSample); value.addProperty("mutationSampleId", state.mutationSample);
            value.addProperty("originalAckWait", state.arrivalAckWait); value.addProperty("poseReportCompletionWait", completionWait);
            value.addProperty("matchingEpoch", epochMatched); value.addProperty("afterEnqueueCaptureSeen", afterCaptureSeen);
            value.addProperty("afterCaptureSampleId", afterSample); value.addProperty("afterCaptureServerWorldTick", afterWorldTick);
            value.addProperty("enqueueIsNotAcknowledgment", true); value.addProperty("packetCausationProven", false);
            value.addProperty("dispatchStartedElapsedMillis", dispatchStartedAt == 0 ? -1 : (dispatchStartedAt - state.started) / 1000000.0);
            value.addProperty("enqueueReturnedElapsedMillis", enqueueReturnedAt == 0 ? -1 : (enqueueReturnedAt - state.started) / 1000000.0);
            value.addProperty("afterCaptureElapsedMillis", afterCaptureAt == 0 ? -1 : (afterCaptureAt - state.started) / 1000000.0);
            value.addProperty("sendFloorCaptureElapsedMillis", sendCaptureAt == 0 ? -1 : (sendCaptureAt - state.started) / 1000000.0);
            if (reported != null) value.add("reportedActual", reported.json());
            if (state.latest != null) { JsonObject control = object(state.latest, "serverControl"); value.addProperty("latestSampleId", sample(control));
                value.addProperty("snapshotClientTick", snapshotTick(state.latest)); value.addProperty("latestGapBlocks", state.current().distance(pose(object(control, "pose")))); }
            return value;
        }
        JsonObject diagnosticReceipt(State state) {
            JsonObject full = receipt(state), value = new JsonObject();
            for (String key : new String[] {"attemptCount", "status", "reason", "dispatchClientTick", "sendSampleId", "sendServerWorldTick",
                    "dispatchDistinctIdleClientTicks", "originalAckWait", "poseReportCompletionWait", "matchingEpoch", "afterEnqueueCaptureSeen",
                    "afterCaptureSampleId", "afterCaptureServerWorldTick"}) value.add(key, full.get(key));
            if (reported != null) {
                JsonObject raw = new JsonObject();
                for (String key : new String[] {"x", "feetY", "stance", "z", "yaw", "pitch", "onGround"}) raw.add(key, full.getAsJsonObject("reportedActual").get(key));
                value.add("reportedActual", raw);
                for (String key : new String[] {"dispatchStartedElapsedMillis", "enqueueReturnedElapsedMillis", "afterCaptureElapsedMillis", "gapBeforeSendBlocks"}) value.add(key, full.get(key));
            }
            value.addProperty("optionalDetailOmitted", true); return value;
        }
    }

    public static WalkingPathPlanner.Check walkingCheck(JsonObject body) {
        JsonElement statusValue = body.get("status");
        if (statusValue == null || !statusValue.isJsonPrimitive() || !statusValue.getAsJsonPrimitive().isString())
            return WalkingPathPlanner.Check.UNKNOWN;
        String status = statusValue.getAsString();
        if ("partial".equals(status)) return WalkingPathPlanner.Check.PARTIAL;
        if ("unloaded".equals(status) || "outside_world".equals(status)) return WalkingPathPlanner.Check.UNLOADED;
        if (!"observed".equals(status)) return WalkingPathPlanner.Check.UNKNOWN;
        for (String key : new String[] {"loaded", "occupiable", "canStand", "fullSupportCoverage", "entityCoveragePartial", "liquid", "hazard"}) {
            JsonElement value = body.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return WalkingPathPlanner.Check.UNKNOWN;
        }
        if (body.get("entityCoveragePartial").getAsBoolean()) return WalkingPathPlanner.Check.PARTIAL;
        if (!body.get("loaded").getAsBoolean()) return WalkingPathPlanner.Check.UNLOADED;
        return ApproachPlanner.safe(body, false) && supportProven(body)
                ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
    }

    static boolean supportProven(JsonObject body) {
        return flag(body,"fullSupportCoverage") || body.has("supportContactVersion")
                && body.get("supportContactVersion").isJsonPrimitive() && body.get("supportContactVersion").getAsJsonPrimitive().isNumber()
                && "1".equals(body.get("supportContactVersion").toString()) && flag(body,"continuousSupportCoverage");
    }
    public static boolean teleportSupportProven(JsonObject body) {
        return flag(body, "fullSupportCoverage") && walkingCheck(body) == WalkingPathPlanner.Check.CLEAR;
    }

    static double motion(JsonObject pose, String key) { return JsonData.number(pose, key, -16, 16); }
    static final class HorizontalFlightInput {
        final int first;
        final float yaw;
        final Point stop;
        HorizontalFlightInput(int first, float yaw, Point stop) { this.first = first; this.yaw = yaw; this.stop = stop; }
    }
    static void requireHorizontalSpeed(double flySpeed) {
        if (!Double.isFinite(flySpeed) || flySpeed <= 0 || flySpeed > .5)
            throw new BridgeException("unsupported", "Actual flight speed is outside the bounded horizontal stopping model.");
    }
    /** EntityLivingBase snaps each axis before native moveFlying, then applies the float .91 drag. */
    static Point horizontalStop(Point current, double mx, double mz, double ax, double az, int first, int second, int delay) {
        return horizontalStop(current,mx,mz,ax,az,first,second,delay,(double).91F,(double).91F,1);
    }
    static Point horizontalStop(Point current, double mx, double mz, double ax, double az, int first, int second, int delay,
            double poweredDrag, double coastDrag, double coastScale) {
        if (current == null || !Double.isFinite(current.x) || !Double.isFinite(current.y) || !Double.isFinite(current.z)
                || !Double.isFinite(mx) || !Double.isFinite(mz) || Math.abs(mx) > 16 || Math.abs(mz) > 16
                || !Double.isFinite(ax) || !Double.isFinite(az) || Math.hypot(ax, az) > .5
                || first < -1 || first > 1 || second < -1 || second > 1 || delay < 0 || delay > FLIGHT_JUMP_EDGE_TICKS
                || second != 0 && delay == 0 || !Double.isFinite(poweredDrag) || poweredDrag <= 0 || poweredDrag >= 1
                || !Double.isFinite(coastDrag) || coastDrag <= 0 || coastDrag >= 1 || !Double.isFinite(coastScale) || coastScale <= 0 || coastScale > 1.001)
            throw new BridgeException("unsupported", "Actual horizontal flight motion or bounded pulse prediction is unavailable.");
        double x = current.x, z = current.z;
        int previousInput=1; // Native armor/hooks may observe the preceding tick's horizontal input.
        for (int tick = 0; tick < 96; tick++) {
            if (Math.abs(mx) < .005) mx = 0;
            if (Math.abs(mz) < .005) mz = 0;
            int input = tick == 0 ? first : tick == delay ? second : 0;
            double scale=input==0 && previousInput==0 ? coastScale : 1;
            mx = mx*scale+ax*input; mz = mz*scale+az*input;
            if (mx == 0 && mz == 0 && (second == 0 || tick >= delay)) {
                if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 29999999 || Math.abs(z) > 29999999)
                    throw new BridgeException("unsupported", "The predicted horizontal stop exceeds the native world envelope.");
                return new Point(x, current.y, z);
            }
            x += mx; z += mz;
            double drag = input == 0 ? coastDrag/coastScale : poweredDrag;
            mx *= drag; mz *= drag;
            previousInput=input;
        }
        throw new BridgeException("unsupported", "Horizontal stop prediction exceeded its fixed native tick bound.");
    }
    static HorizontalFlightInput horizontalInput(Point current, Point target, double mx, double mz, double flySpeed, boolean sneak) {
        return horizontalInput(current,target,mx,mz,flySpeed,sneak,(double).91F,1);
    }
    static HorizontalFlightInput horizontalInput(Point current, Point target, double mx, double mz, double flySpeed, boolean sneak,
            double coastDrag, double coastScale) {
        requireHorizontalSpeed(flySpeed);
        float forward = sneak ? .3F : 1F; forward *= .98F; forward *= (float)flySpeed;
        return horizontalInput(current,target,mx,mz,forward,(double).91F,coastDrag,coastScale);
    }
    static HorizontalFlightInput walkingInput(Point current, Point target, double mx, double mz, double friction, double acceleration) {
        return walkingInput(current, target, mx, mz, friction, acceleration, TOLERANCE);
    }
    static HorizontalFlightInput walkingInput(Point current, Point target, double mx, double mz, double friction, double acceleration, double tolerance) {
        if (!Double.isFinite(acceleration) || acceleration<=0 || acceleration>.5) throw new BridgeException("unsupported","Actual walking acceleration exceeds the bounded native pulse model.");
        if (!Double.isFinite(tolerance) || tolerance <= 0 || tolerance > TOLERANCE) throw new BridgeException("unsupported","Native walking tolerance must remain within the original arrival bound.");
        return horizontalInput(current,target,mx,mz,acceleration,friction,friction,1,tolerance,true);
    }
    private static HorizontalFlightInput horizontalInput(Point current, Point target, double mx, double mz, double strength,
            double poweredDrag, double coastDrag, double coastScale) {
        return horizontalInput(current,target,mx,mz,strength,poweredDrag,coastDrag,coastScale,TOLERANCE,false);
    }
    private static HorizontalFlightInput horizontalInput(Point current, Point target, double mx, double mz, double strength,
            double poweredDrag, double coastDrag, double coastScale, double tolerance, boolean walking) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z))
            throw new BridgeException("unsupported", "The actual horizontal flight target is unavailable.");
        Point coast = horizontalStop(current,mx,mz,0,0,0,0,0,poweredDrag,coastDrag,coastScale);
        if (coast.horizontal(target) <= tolerance) return new HorizontalFlightInput(0, 0, coast);
        double dx = target.x - coast.x, dz = target.z - coast.z;
        if (mx * dx + mz * dz < 0) { dx = -dx; dz = -dz; }
        if (walking) {
            // Vanilla snaps each small axis before moveFlying. A shallow heading
            // otherwise loses the transverse residual every tick and follows a
            // curved path outside the straight collision-proved leg. Steer the
            // native acceleration toward the desired next velocity instead.
            double length = current.horizontal(target), speed = strength / (1 - poweredDrag);
            double ax = (length > .000001 ? (target.x - current.x) / length * speed : 0) - (Math.abs(mx) < .005 ? 0 : mx);
            double az = (length > .000001 ? (target.z - current.z) / length * speed : 0) - (Math.abs(mz) < .005 ? 0 : mz);
            if (Math.hypot(ax, az) > .000001) { dx = ax; dz = az; }
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float angle = yaw * (float) Math.PI / 180.0F;
        float forward = (float)strength;
        double ax = -(double) (forward * MathHelper.sin(angle)), az = (double) (forward * MathHelper.cos(angle));
        double best = Double.POSITIVE_INFINITY;
        int chosen = 0;
        Point stop = coast;
        // At most two one-tick native pulses; future input is a prediction, never a queued action.
        for (int first : new int[] {0, -1, 1}) for (int second : new int[] {0, -1, 1}) {
            for (int delay = second == 0 ? 0 : 1; delay <= (second == 0 ? 0 : FLIGHT_JUMP_EDGE_TICKS); delay++) {
                Point candidate = horizontalStop(current,mx,mz,ax,az,first,second,delay,poweredDrag,coastDrag,coastScale);
                double error = candidate.horizontal(target);
                int pulses = (first == 0 ? 0 : 1) + (second == 0 ? 0 : 1);
                double score = error <= tolerance - .000001 ? pulses * .01 + delay * .0001 + error * .001
                        : 100 + error + pulses * .0001 + delay * .000001;
                if (score < best - .000000001) { best = score; chosen = first; stop = candidate; }
            }
        }
        return new HorizontalFlightInput(chosen, yaw, stop);
    }
    static int verticalInput(double feetY, double motionY, double targetY, long tick, long lastJumpTick, boolean jumpHeld) {
        if (!Double.isFinite(feetY) || !Double.isFinite(motionY) || !Double.isFinite(targetY) || Math.abs(motionY) > 16)
            throw new BridgeException("unsupported", "Actual vertical flight motion is outside the bounded stopping predictor.");
        double best = Double.POSITIVE_INFINITY;
        int chosen = 0;
        // At most two one-tick pulses within eight ticks, followed by bounded unpowered settling.
        // Candidate order prefers no current input when scores tie. Every predicted jump edge
        // obeys the same guard as the actual dispatcher, including release between two presses.
        for (int first : new int[] {0, -1, 1}) for (int second : new int[] {0, -1, 1}) {
            for (int delay = second == 0 ? 0 : 1; delay <= (second == 0 ? 0 : FLIGHT_JUMP_EDGE_TICKS); delay++) {
                double height = feetY, velocity = motionY;
                long edge = lastJumpTick;
                boolean previousJump = jumpHeld, permitted = true;
                for (int step = 0; step <= FLIGHT_JUMP_EDGE_TICKS; step++) {
                    int input = step == 0 ? first : step == delay ? second : 0;
                    if (input > 0 && !previousJump) {
                        if (tick + step - edge < FLIGHT_JUMP_EDGE_TICKS) { permitted = false; break; }
                        edge = tick + step;
                    }
                    previousJump = input > 0;
                    velocity += input * .15;
                    if (Math.abs(velocity) < .005) velocity = 0;
                    height += velocity; velocity *= .6;
                }
                if (!permitted) continue;
                for (int step = 0; step < 32 && velocity != 0; step++) {
                    if (Math.abs(velocity) < .005) velocity = 0;
                    height += velocity; velocity *= .6;
                }
                double error = Math.abs(targetY - height);
                int pulses = (first == 0 ? 0 : 1) + (second == 0 ? 0 : 1);
                // A predicted stop inside the existing arrival tolerance wins over an outside
                // stop. Inside it, prefer fewer inputs rather than chasing the exact center.
                double score = error <= TOLERANCE - .000001 ? pulses * .01 + delay * .0001 + error * .001
                        : 100 + error + pulses * .0001 + delay * .000001;
                if (score < best - .000000001) { best = score; chosen = first; }
            }
        }
        return chosen;
    }
    public static boolean groundedFloorProven(boolean onGround, boolean flying, double motionY, JsonObject support) {
        return onGround && !flying && Double.isFinite(motionY) && motionY <= 0 && walkingCheck(support) == WalkingPathPlanner.Check.CLEAR;
    }
    public static boolean descentFloorCandidate(boolean flying, double feetY, double floor, double motionY) {
        return flying && Double.isFinite(feetY) && Double.isFinite(floor) && Double.isFinite(motionY)
                && motionY <= 0 && feetY >= floor && feetY - floor <= 1;
    }
    public static FlightPathPlanner.Check flightCheck(JsonObject body) {
        JsonElement statusValue = body.get("status");
        if (statusValue == null || !statusValue.isJsonPrimitive() || !statusValue.getAsJsonPrimitive().isString()) return FlightPathPlanner.Check.UNKNOWN;
        String status = statusValue.getAsString();
        if ("partial".equals(status)) return FlightPathPlanner.Check.PARTIAL;
        if ("unloaded".equals(status) || "outside_world".equals(status)) return FlightPathPlanner.Check.UNLOADED;
        if (!"observed".equals(status)) return FlightPathPlanner.Check.UNKNOWN;
        for (String key : new String[] {"loaded", "occupiable", "entityCoveragePartial", "liquid", "hazard"}) {
            JsonElement value = body.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return FlightPathPlanner.Check.UNKNOWN;
        }
        if (body.get("entityCoveragePartial").getAsBoolean()) return FlightPathPlanner.Check.PARTIAL;
        if (!body.get("loaded").getAsBoolean()) return FlightPathPlanner.Check.UNLOADED;
        return ApproachPlanner.safe(body, true) ? FlightPathPlanner.Check.CLEAR : FlightPathPlanner.Check.BLOCKED;
    }
    static List<FlightPathPlanner.Sweep> flightSweeps(Point start, List<Point> route, double width, double height) {
        return flightSweeps(start,route,width,height,false);
    }
    static List<FlightPathPlanner.Sweep> flightSweeps(Point start, List<Point> route, double width, double height, boolean segmented) {
        List<FlightPathPlanner.Sweep> result = new ArrayList<FlightPathPlanner.Sweep>();
        Point previous = start;
        result.add(new FlightPathPlanner.Sweep(flightPoint(start), flightPoint(start), width, height));
        for (Point target : route) {
            int steps = Math.max(1, (int) Math.ceil(previous.distance(target) / STEP));
            Point before = previous;
            for (int i = 1; i <= steps; i++) {
                double f = i / (double) steps;
                Point after = new Point(previous.x + (target.x - previous.x) * f, previous.y + (target.y - previous.y) * f, previous.z + (target.z - previous.z) * f);
                result.add(new FlightPathPlanner.Sweep(flightPoint(before), flightPoint(after), width, height)); before = after;
            }
            previous = target;
        }
        if (result.size() > (segmented ? 2048 : 384)) throw new BridgeException("unsupported", "Complete native flight proof exceeds the bounded sweep count.");
        return result;
    }
    public static AxisAlignedBB translatedFlightBody(AxisAlignedBB nativeBody, Point actual, FlightPathPlanner.Sweep sweep,
            double width, double height) {
        if (nativeBody == null || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || !Double.isFinite(actual.x) || !Double.isFinite(actual.y) || !Double.isFinite(actual.z)
                || !Double.isFinite(nativeBody.minX) || !Double.isFinite(nativeBody.minY) || !Double.isFinite(nativeBody.minZ)
                || !Double.isFinite(nativeBody.maxX) || !Double.isFinite(nativeBody.maxY) || !Double.isFinite(nativeBody.maxZ)
                || Math.abs(nativeBody.maxX - nativeBody.minX - width) > .0001
                || Math.abs(nativeBody.maxZ - nativeBody.minZ - width) > .0001
                || Math.abs(nativeBody.maxY - nativeBody.minY - height) > .0001
                || Math.abs((nativeBody.minX + nativeBody.maxX) / 2 - actual.x) > .0001
                || Math.abs((nativeBody.minZ + nativeBody.maxZ) / 2 - actual.z) > .0001
                || Math.abs(nativeBody.minY - actual.y) > .0001)
            throw new BridgeException("unsupported", "The actual native flight body is unavailable, changed or differs from its declared full dimensions.");
        // Preserve the native body validation above, and carry conservative planner
        // clearance through the actual world query. A sweep can never shrink it.
        double marginX = Math.max(0, (sweep.widthX - Math.abs(sweep.to.x - sweep.from.x) - width) / 2);
        double marginZ = Math.max(0, (sweep.widthZ - Math.abs(sweep.to.z - sweep.from.z) - width) / 2);
        return AxisAlignedBB.getBoundingBox(Math.min(sweep.from.x, sweep.to.x) + (nativeBody.minX - actual.x) - marginX,
                Math.min(sweep.from.y, sweep.to.y), Math.min(sweep.from.z, sweep.to.z) + (nativeBody.minZ - actual.z) - marginZ,
                Math.max(sweep.from.x, sweep.to.x) + (nativeBody.maxX - actual.x) + marginX,
                Math.max(sweep.from.y, sweep.to.y) + (nativeBody.maxY - nativeBody.minY),
                Math.max(sweep.from.z, sweep.to.z) + (nativeBody.maxZ - actual.z) + marginZ);
    }
    public static JsonObject bounds(AxisAlignedBB box) {
        JsonObject result = new JsonObject(); result.addProperty("minX", box.minX); result.addProperty("minY", box.minY); result.addProperty("minZ", box.minZ);
        result.addProperty("maxX", box.maxX); result.addProperty("maxY", box.maxY); result.addProperty("maxZ", box.maxZ); return result;
    }
    private static void copyFinite(JsonObject from, JsonObject to, String key) {
        JsonElement value = from.get(key);
        double number = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : Double.NaN;
        to.add(key, Double.isFinite(number) ? new com.google.gson.JsonPrimitive(number) : JsonNull.INSTANCE);
    }
    static List<FlightPathPlanner.Sweep> inertiaSweeps(Point current, double mx, double my, double mz, Set<String> keys,
            double yaw, double flySpeed, boolean flying, double supportedFloor, double width, double height) {
        return inertiaSweeps(current,mx,my,mz,keys,yaw,flySpeed,flying,supportedFloor,width,height,(double).91F,1);
    }
    public static List<FlightPathPlanner.Sweep> inertiaSweeps(Point current, double mx, double my, double mz, Set<String> keys,
            double yaw, double flySpeed, boolean flying, double supportedFloor, double width, double height,double coastDrag,double coastScale) {
        if (!Double.isFinite(flySpeed) || flySpeed <= 0 || flySpeed > .5)
            throw new BridgeException("unsupported", "Actual flight speed is outside the bounded native controller envelope.");
        if (!Double.isFinite(mx) || !Double.isFinite(my) || !Double.isFinite(mz) || !Double.isFinite(yaw)
                || Math.abs(mx) > 16 || Math.abs(my) > 16 || Math.abs(mz) > 16)
            throw new BridgeException("unsupported", "Actual native flight motion/yaw is unavailable or outside its bounded inertia proof.");
        List<FlightPathPlanner.Sweep> result = new ArrayList<FlightPathPlanner.Sweep>();
        // Bound the residual trajectory and one native input pulse followed by its full coast.
        // maintainNavigation renews the proof before every native tick, releasing on a stale server proof.
        double coastY = current.y + my * 2.5;
        if (Double.isFinite(supportedFloor)) coastY = Math.max(supportedFloor, coastY);
        Point horizontalCoast = flying ? horizontalStop(current,mx,mz,0,0,0,0,0,(double).91F,coastDrag,coastScale)
                : new Point(current.x+mx*2.75,current.y,current.z+mz*2.75);
        Point coast = new Point(horizontalCoast.x, coastY, horizontalCoast.z);
        result.add(flying ? horizontalTrajectoryEnvelope(current,mx,mz,0,0,0,coastY,width,height,coastDrag,coastScale)
                : new FlightPathPlanner.Sweep(flightPoint(current), flightPoint(coast), width, height));
        double direction = keys.contains("forward") ? 1 : keys.contains("back") ? -1 : 0;
        double angle = Math.toRadians(yaw + 90), input = direction * flySpeed * 6;
        double vertical = keys.contains("jump") == keys.contains("sneak") ? 0
                : keys.contains("jump") ? Math.max(.15, flySpeed * 3) : -Math.max(.15, flySpeed * 3);
        double controlledY = flying ? current.y + (my + vertical) * 2.5
                : current.y + my * 1.96 + vertical * 4.56;
        if (!flying && keys.contains("jump")) controlledY = Math.max(controlledY, current.y + 1.5);
        if (Double.isFinite(supportedFloor)) controlledY = Math.max(supportedFloor, controlledY);
        if (flying) {
            // Each native tick renews input only after fresh collision proof. Bound that one
            // input pulse and its entire unpowered path, including intermediate reversal extrema.
            float a = (float)yaw*(float)Math.PI/180F;
            float strength = (keys.contains("sneak") ? .3F : 1F)*.98F*(float)flySpeed;
            double ax = -(double)(strength*MathHelper.sin(a)), az = (double)(strength*MathHelper.cos(a));
            // Retain both residual and powered extrema: a speed-scaled upper bound on the
            // impulse must not hide the opposite-direction coast of a weaker native pulse.
            Point lower = new Point(current.x, Math.min(current.y, Math.min(coastY, controlledY)), current.z);
            result.add(horizontalTrajectoryEnvelope(lower,mx,mz,ax,az,(int)direction,
                    Math.max(current.y,Math.max(coastY,controlledY)),width,height,coastDrag,coastScale));
        } else {
            Point controlled = new Point(coast.x + Math.cos(angle) * input, controlledY, coast.z + Math.sin(angle) * input);
            result.add(new FlightPathPlanner.Sweep(flightPoint(current), flightPoint(controlled), width, height));
        }
        return result;
    }
    /** Every native horizontal pulse/coast segment retains the entire conservative vertical extent.
     * The outer bounds are diagnostics only: a diagonal corridor's empty corners are not traveled body. */
    private static FlightPathPlanner.Sweep horizontalTrajectoryEnvelope(Point current,double vx,double vz,double ax,double az,
            int direction,double endY,double width,double height,double coastDrag,double coastScale) {
        List<FlightPathPlanner.Sweep> pieces=new ArrayList<FlightPathPlanner.Sweep>();
        double x=current.x,z=current.z,minX=x,maxX=x,minZ=z,maxZ=z;
        double minY=Math.min(current.y,endY),maxY=Math.max(current.y,endY);
        int previousInput=1;
        for(int tick=0;tick<96;tick++) {
            if(Math.abs(vx)<.005) vx=0; if(Math.abs(vz)<.005) vz=0;
            int pulse=tick==0 ? direction : 0;
            double scale=pulse==0 && previousInput==0 ? coastScale : 1;
            vx=vx*scale+ax*pulse; vz=vz*scale+az*pulse;
            double nx=x+vx,nz=z+vz;
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(vx,vz)/FlightPathPlanner.SWEEP_STEP));
            if(pieces.size()+steps>512) throw new BridgeException("unsupported","Native flight trajectory exceeds the bounded continuous sweep budget.");
            for(int i=0;i<steps;i++) {
                double first=i/(double)steps,last=(i+1)/(double)steps;
                pieces.add(new FlightPathPlanner.Sweep(new FlightPathPlanner.Point(x+vx*first,minY,z+vz*first),
                        new FlightPathPlanner.Point(x+vx*last,maxY,z+vz*last),width,height));
            }
            x=nx; z=nz; minX=Math.min(minX,x); maxX=Math.max(maxX,x); minZ=Math.min(minZ,z); maxZ=Math.max(maxZ,z);
            double drag=tick==0 && direction!=0 ? (double).91F : coastDrag/coastScale;
            vx*=drag; vz*=drag; previousInput=pulse;
            if(vx==0 && vz==0) break;
            if(tick==95) throw new BridgeException("unsupported","Native coast did not settle within the bounded pulse model.");
        }
        return new FlightPathPlanner.Sweep(new FlightPathPlanner.Point(minX,minY,minZ),
                new FlightPathPlanner.Point(maxX,maxY,maxZ),width,height,pieces);
    }
    public static FlightPathPlanner.Point flightPoint(Point point) { return new FlightPathPlanner.Point(point.x, point.y, point.z); }

    public static List<Point> probes(Point start, List<Point> route, boolean teleport) {
        List<Point> result = new ArrayList<Point>();
        if (teleport) { result.add(route.get(route.size() - 1)); return result; }
        Point previous = start; result.add(previous);
        for (Point target : route) {
            int steps = Math.max(1, (int) Math.ceil(previous.distance(target) / STEP));
            for (int i = 1; i <= steps; i++) { double fraction = i / (double) steps; result.add(new Point(previous.x + (target.x - previous.x) * fraction, previous.y + (target.y - previous.y) * fraction, previous.z + (target.z - previous.z) * fraction)); }
            previous = target;
        }
        if (result.size() > MAX_PROBES) throw new BridgeException("unsupported", "The complete declared corridor exceeds the finite geometry probe budget.");
        return result;
    }
    public static double[] aim(Point feet, double eyeOffset, Point target) {
        double dx = target.x - feet.x, dy = target.y - feet.y - eyeOffset, dz = target.z - feet.z;
        return new double[] {Math.toDegrees(Math.atan2(dz, dx)) - 90, -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))};
    }
    public static double angleDifference(double first, double second) { double value = (first - second) % 360; return Math.abs(value > 180 ? value - 360 : value < -180 ? value + 360 : value); }
    public static Point pose(JsonObject value) { return new Point(JsonData.number(value, "x", -30000000, 30000000), JsonData.number(value, "feetY", 0, 255), JsonData.number(value, "z", -30000000, 30000000)); }
    public static long sample(JsonObject value) {
        JsonElement found = value.get("sampleId");
        if (found == null || !found.isJsonPrimitive() || !found.getAsJsonPrimitive().isNumber() || !found.toString().matches("[0-9]+"))
            throw new BridgeException("navigation_server_control_unavailable", "A real nonnegative integral server sample ID is required.");
        try { return Long.parseLong(found.toString()); } catch (NumberFormatException error) { throw new BridgeException("navigation_server_control_unavailable", "Server sample ID exceeds its native bound."); }
    }
    /** Optional diagnostic provenance; an absent/invalid tick stays unknown and never supplies an ACK. */
    static long snapshotTick(JsonObject value) {
        JsonElement found = value.get("clientTicks");
        if (found == null || !found.isJsonPrimitive() || !found.getAsJsonPrimitive().isNumber() || !found.toString().matches("[0-9]+")) return -1;
        try { return Long.parseLong(found.toString()); } catch (NumberFormatException error) { return -1; }
    }
    public static JsonObject object(JsonObject value, String key) { JsonElement found = value.get(key); if (found == null || !found.isJsonObject()) throw new BridgeException("navigation_receipt_unavailable", "Missing native navigation object: " + key); return found.getAsJsonObject(); }
    public static boolean flag(JsonObject value, String key) { JsonElement found = value.get(key); if (found == null || !found.isJsonPrimitive() || !found.getAsJsonPrimitive().isBoolean()) throw new BridgeException("navigation_receipt_unavailable", "Missing native navigation boolean: " + key); return found.getAsBoolean(); }
    public static String text(JsonObject value, String key) { JsonElement found = value.get(key); if (found == null || !found.isJsonPrimitive() || !found.getAsJsonPrimitive().isString()) throw new BridgeException("navigation_receipt_unavailable", "Missing native navigation identity: " + key); return found.getAsString(); }
}
