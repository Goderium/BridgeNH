package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.config.NavigationSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Cooperative grounded A* with collision-proved shortcuts and bounded native height transitions. */
public final class WalkingPathPlanner {
    public static final double MAX_DISTANCE = 32, GRID = 1, SWEEP_STEP = .25;
    static final int MAX_HEIGHT_CHANGE = 6;
    public static final long WORK_NANOS = 4000000L;
    static final int MAX_NODES = 4096, MAX_EDGES = 8192, MAX_SAMPLES = 32768;
    static final int MAX_SLICE_SAMPLES = 128, MAX_SLICE_OPERATIONS = 512, MAX_ROUTE_POINTS = 96;
    private static final double EPSILON = .0000001;
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    enum Status { PLANNING, FOUND, REFUSED }
    public enum Check { CLEAR, BLOCKED, UNLOADED, UNKNOWN, PARTIAL }

    interface Clock { long nanoTime(); }
    interface EndpointGoal { double estimate(Point point); }
    public interface Probe {
        /** Owning client thread only. Inspect every cell/entity in this conservative swept envelope. */
        Check inspect(Sweep sweep, long deadline);
        default Check endpoint(Point point, double width, double height, long deadline) { return inspect(new Sweep(point,point,width,height),deadline); }
        default Check step(Point from, Point to, double width, double height, long deadline) { return Check.BLOCKED; }
    }
    static final Clock SYSTEM_CLOCK = System::nanoTime;

    public static final class Point {
        public final double x, y, z;
        public Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public double distance(Point other) { return Math.sqrt((other.x-x)*(other.x-x)+(other.y-y)*(other.y-y)+(other.z-z)*(other.z-z)); }
    }

    public static final class Sweep {
        public final Point from, to;
        public final double x, feetY, z, width, depth, height, actorWidth;
        public Sweep(Point from, Point to, double actorWidth, double actorHeight) {
            this.from = from; this.to = to;
            this.actorWidth = actorWidth;
            x = (from.x + to.x) / 2; feetY = from.y; z = (from.z + to.z) / 2;
            // Independent axis extents enclose the translated body without inventing side clearance.
            width = actorWidth + Math.abs(to.x - from.x);
            depth = actorWidth + Math.abs(to.z - from.z);
            height = actorHeight;
        }
        public net.minecraft.util.AxisAlignedBB body() {
            return net.minecraft.util.AxisAlignedBB.getBoundingBox(x-width/2,feetY+.00001,z-depth/2,
                    x+width/2,feetY+height,z+depth/2);
        }
    }

    private enum Phase { START, GOAL_DIRECT, TARGET, DIRECT, CENTER, SEARCH, SMOOTH, VALIDATE }
    private static final class Node {
        final int x, z;
        final Point point;
        double cost = Double.POSITIVE_INFINITY, traveled = Double.POSITIVE_INFINITY;
        Node parent;
        boolean closed;
        Node(int x, int z, Point point) { this.x = x; this.z = z; this.point = point; }
    }
    private static final class Entry {
        final Node node;
        final double cost, estimate;
        final long order;
        Entry(Node node, Point goal, long order, NavigationCosts.Model model, EndpointGoal endpoints) { this.node = node; cost = node.cost; estimate = cost + (endpoints == null ? model.lowerBound(node.point.distance(goal)) : endpoints.estimate(node.point)); this.order = order; }
    }
    private static final class Segment {
        final Point from, to;
        final int steps;
        int next = 1;
        Check outcome;
        Segment(Point from, Point to) {
            this.from = from; this.to = to;
            steps = Math.max(1, (int) Math.ceil(from.distance(to) / SWEEP_STEP));
        }
        Point at(int index) { double f = index / (double) steps; return new Point(from.x + (to.x - from.x) * f, from.y, from.z + (to.z - from.z) * f); }
        boolean vertical() { return Math.abs(from.y-to.y) > .0001; }
    }

    private final NavigationCosts.Model costs;
    private final EndpointGoal endpointGoal;
    private final Point start;
    private final List<Point> targets;
    private final double actorWidth, actorHeight;
    private final long overallDeadline;
    private final Map<String, Node> nodes = new HashMap<String, Node>();
    private final List<Double> heightFractions = new ArrayList<Double>();
    private final PriorityQueue<Entry> open = new PriorityQueue<Entry>(64, new Comparator<Entry>() {
        @Override
        public int compare(Entry first, Entry second) {
            int value = Double.compare(first.estimate, second.estimate);
            return value != 0 ? value : Long.compare(first.order, second.order);
        }
    });
    private final List<Point> planned = new ArrayList<Point>();
    private List<Point> result = Collections.emptyList();
    private Phase phase = Phase.START;
    private Status status = Status.PLANNING;
    private String reason = "planning";
    private Point legStart;
    private int leg, samples, edges, discovered, rejected, partials, slices, validateIndex, neighbor = -1;
    private double distance;
    private long order;
    private Segment segment;
    private Node active, candidate;
    private boolean goalEdge;
    private final int maxJump = NavigationSettings.maxJumpBlocks, maxDrop = NavigationSettings.maxDropBlocks;
    private Point origin, smoothFrom;
    private List<Point> rawPath;
    private final List<Point> smoothed = new ArrayList<Point>();
    private int smoothIndex, smoothTry;
    private final int[] rejectedByCheck = new int[Check.values().length];
    private final List<com.google.gson.JsonObject> rejectedEdges = new ArrayList<com.google.gson.JsonObject>();
    private int expanded, distancePruned, heightPruned;
    private double minExpandedY = Double.NaN, maxExpandedY = Double.NaN;
    private String refusedPhase;
    private int goalChecks;
    private Point directGoalPoint;
    private boolean directGoalPending;
    private boolean actualRoot;

    WalkingPathPlanner(Point start, List<Point> targets, double actorWidth, double actorHeight, long overallDeadline) {
        this(start, targets, actorWidth, actorHeight, overallDeadline, NavigationCosts.snapshot());
    }
    WalkingPathPlanner(Point start, List<Point> targets, double actorWidth, double actorHeight, long overallDeadline, NavigationCosts.Model costs) {
        this(start,targets,actorWidth,actorHeight,overallDeadline,costs,null);
    }
    WalkingPathPlanner(Point start, List<Point> targets, double actorWidth, double actorHeight, long overallDeadline, NavigationCosts.Model costs, EndpointGoal endpointGoal) {
        this.costs = costs;
        this.endpointGoal = endpointGoal;
        this.start = copy(start); legStart = this.start;
        this.actorWidth = actorWidth; this.actorHeight = actorHeight; this.overallDeadline = overallDeadline;
        this.targets = new ArrayList<Point>();
        if (targets != null) for (Point point : targets) this.targets.add(copy(point));
        if (!valid(this.start) || this.targets.isEmpty() || this.targets.size() > 3 || !Double.isFinite(actorWidth)
                || !Double.isFinite(actorHeight) || actorWidth <= 0 || actorWidth > 4 || actorHeight <= 0 || actorHeight > 4) {
            refuse("invalid_request"); return;
        }
        double lowerBound = 0; Point previous = this.start;
        for (Point target : this.targets) {
            if (!valid(target)) { refuse("invalid_request"); return; }
            if (endpointGoal == null && Math.abs(target.y - this.start.y) > MAX_HEIGHT_CHANGE) { refuse("height_limit"); return; }
            lowerBound += previous.distance(target); previous = target;
        }
        if (endpointGoal == null && lowerBound > MAX_DISTANCE + EPSILON) refuse("distance_limit");
    }

    Status advance(Probe probe, long externalDeadline, Clock clock) {
        if (status != Status.PLANNING) return status;
        if (probe == null || clock == null) { refuse("invalid_probe"); return status; }
        long now = clock.nanoTime();
        if (now >= overallDeadline) { refuse("deadline"); return status; }
        long deadline = Math.min(overallDeadline, Math.min(externalDeadline, now + WORK_NANOS));
        int sliceSamples = samples; slices++;
        for (int operations = 0; operations < MAX_SLICE_OPERATIONS && status == Status.PLANNING; operations++) {
            now = clock.nanoTime();
            if (now >= overallDeadline) { refuse("deadline"); break; }
            if (now >= deadline || samples - sliceSamples >= MAX_SLICE_SAMPLES) break;
            if (phase == Phase.START || phase == Phase.TARGET) {
                Point point = phase == Phase.START ? start : targets.get(leg);
                Check check = inspect(probe, new Sweep(point, point, actorWidth, actorHeight), deadline, clock);
                if (check == null || check == Check.PARTIAL) break;
                if (check != Check.CLEAR) { refuse(phase == Phase.START ? "start_unsafe_" + code(check) : "target_unsafe_" + code(check)); break; }
                if (phase == Phase.START && endpointGoal != null) {
                    Point hint = targets.get(0);
                    double length = Math.hypot(hint.x-start.x,hint.z-start.z), scale = length > MAX_DISTANCE ? MAX_DISTANCE/length : 1;
                    segment = segment(start,new Point(start.x+(hint.x-start.x)*scale,start.y,start.z+(hint.z-start.z)*scale));
                    directGoalPoint = start; directGoalPending = true; phase = Phase.GOAL_DIRECT;
                } else if (phase == Phase.START) phase = Phase.TARGET;
                else if (Math.abs(legStart.y-targets.get(leg).y) > .0001) startCenter();
                else { segment = segment(legStart, targets.get(leg)); phase = Phase.DIRECT; }
            } else if (phase == Phase.GOAL_DIRECT) {
                if (directGoalPending) {
                    if (endpointGoal.estimate(directGoalPoint) <= .00001) {
                        if (samples >= MAX_SAMPLES) { refuse("geometry_budget"); break; }
                        samples++; goalChecks++;
                        Check check = probe.endpoint(directGoalPoint,actorWidth,actorHeight,deadline);
                        if (check == null || check == Check.PARTIAL || clock.nanoTime() >= deadline) { partials++; break; }
                        if (check == Check.CLEAR) { finishLeg(Collections.singletonList(directGoalPoint)); continue; }
                    }
                    directGoalPending = false;
                    if (segment.next > segment.steps) { startActualSearch(); continue; }
                }
                Check check = inspect(probe,new Sweep(segment.at(segment.next-1),segment.at(segment.next),actorWidth,actorHeight),deadline,clock);
                if (check == null || check == Check.PARTIAL) break;
                if (check != Check.CLEAR) { startActualSearch(); continue; }
                directGoalPoint = segment.at(segment.next++); directGoalPending = true;
            } else if (phase == Phase.DIRECT) {
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (status != Status.PLANNING) break;
                if (segment.outcome == Check.CLEAR) finishLeg(Collections.singletonList(targets.get(leg)));
                else startCenter();
            } else if (phase == Phase.CENTER) {
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (segment.outcome != Check.CLEAR) startActualSearch();
                else startSearch();
            } else if (phase == Phase.SEARCH) {
                if (segment != null) {
                    int progress = advanceSegment(probe, deadline, clock);
                    if (progress < 0) break;
                    if (progress == 0) continue;
                    if (status != Status.PLANNING) break;
                    if (segment.outcome == Check.CLEAR) {
                        if (goalEdge) { startSmooth(reconstruct(active, targets.get(leg))); continue; }
                        double cost = active.cost + edgeCost(active.point,candidate.point);
                        if (cost + EPSILON < candidate.cost) {
                            candidate.cost = cost; candidate.traveled = active.traveled + active.point.distance(candidate.point); candidate.parent = active;
                            open.add(new Entry(candidate, targets.get(leg), order++, costs, endpointGoal));
                        }
                    }
                    segment = null; candidate = null; neighbor++;
                } else if (active == null) {
                    while (!open.isEmpty()) {
                        if (clock.nanoTime() >= deadline) return status;
                        Entry entry = open.poll();
                        if (!entry.node.closed && entry.cost == entry.node.cost) { active = entry.node; break; }
                    }
                    if (active == null) { refuse("no_safe_route"); break; }
                    active.closed = true; neighbor = -1;
                    expanded++; minExpandedY = Double.isNaN(minExpandedY) ? active.point.y : Math.min(minExpandedY,active.point.y);
                    maxExpandedY = Double.isNaN(maxExpandedY) ? active.point.y : Math.max(maxExpandedY,active.point.y);
                } else if (neighbor == -1) {
                    if (endpointGoal != null) {
                        if (samples >= MAX_SAMPLES) { refuse("geometry_budget"); break; }
                        samples++; goalChecks++;
                        Check check = probe.endpoint(active.point,actorWidth,actorHeight,deadline);
                        if (check == null || check == Check.PARTIAL || clock.nanoTime() >= deadline) { partials++; break; }
                        if (check == Check.CLEAR) { startSmooth(reconstruct(active,active.point)); continue; }
                        // This node is a clear route cell, but not an interaction goal. Unknown
                        // face/target evidence never certifies arrival; continue the same frontier.
                        neighbor++; continue;
                    }
                    goalEdge = true;
                    if (Math.abs(active.point.y-targets.get(leg).y)<.0001 && active.point.distance(targets.get(leg)) <= 1.5 + EPSILON) segment = segment(active.point, targets.get(leg));
                    else neighbor++;
                } else if (neighbor < 8+4*(maxJump+maxDrop+1)*heightFractions.size()) {
                    goalEdge = false;
                    int direction = neighbor < 8 ? neighbor : (neighbor-8)%4;
                    double level = active.point.y;
                    if (neighbor >= 8) {
                        int index = (neighbor-8)/4, span = maxJump+maxDrop+1;
                        level = Math.floor(active.point.y)+index%span-maxDrop+heightFractions.get(index/span);
                        double rise = level-active.point.y;
                        if (Math.abs(rise)<.125 || rise>maxJump || -rise>maxDrop) { neighbor++; continue; }
                    }
                    int nx = active.x+DIRECTIONS[direction][0], nz = active.z+DIRECTIONS[direction][1];
                    Node existing = nodes.get(key(nx, level, nz));
                    Point point = existing == null ? new Point(origin.x+nx*GRID, level, origin.z+nz*GRID) : existing.point;
                    double cost = active.cost + edgeCost(active.point,point);
                    double traveled = active.traveled + active.point.distance(point);
                    // Reserve the exact lower bound through all remaining hard waypoints.
                    if (!valid(point) || Math.abs(point.y-start.y)>MAX_HEIGHT_CHANGE) { heightPruned++; neighbor++; continue; }
                    if (distance + traveled + remainingLowerBound(point) > MAX_DISTANCE + EPSILON) { distancePruned++; neighbor++; continue; }
                    if (existing != null && (existing.closed || cost + EPSILON >= existing.cost)) { neighbor++; continue; }
                    candidate = existing == null ? node(nx, nz, point) : existing;
                    if (candidate == null) break;
                    segment = segment(active.point, point);
                } else active = null;
            } else if (phase == Phase.SMOOTH) {
                if (segment == null) segment = segment(smoothFrom, rawPath.get(smoothTry));
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (segment.outcome == Check.CLEAR) {
                    smoothFrom = rawPath.get(smoothTry); smoothed.add(smoothFrom); smoothIndex = smoothTry+1; segment = null;
                    if (smoothIndex == rawPath.size()) { finishLeg(smoothed); continue; }
                    smoothTry = lastSmoothIndex();
                } else {
                    segment = null;
                    if (smoothTry == smoothIndex) { refuse("route_changed_during_smoothing"); break; }
                    smoothTry--;
                }
            } else {
                if (segment == null) {
                    if (validateIndex == planned.size()) { publish(); break; }
                    segment = segment(validateIndex == 0 ? start : planned.get(validateIndex - 1), planned.get(validateIndex));
                }
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (status != Status.PLANNING) break;
                if (segment.outcome != Check.CLEAR) { refuse("route_changed_" + code(segment.outcome)); break; }
                segment = null; validateIndex++;
            }
        }
        return status;
    }

    private Check inspect(Probe probe, Sweep sweep, long deadline, Clock clock) {
        if (samples >= MAX_SAMPLES) { refuse("geometry_budget"); return null; }
        if (clock.nanoTime() >= deadline) return null;
        samples++;
        Check check = probe.inspect(sweep, deadline);
        if (check == null) check = Check.UNKNOWN;
        // An over-budget callback is never turned into a proof. The owning adapter must also cooperate.
        if (clock.nanoTime() >= deadline) check = Check.PARTIAL;
        if (check == Check.PARTIAL) partials++;
        else if (check != Check.CLEAR) rejected(check,sweep.from,sweep.to);
        return check;
    }

    private int advanceSegment(Probe probe, long deadline, Clock clock) {
        if (segment == null || status != Status.PLANNING) return -1;
        if (segment.vertical()) {
            if (samples >= MAX_SAMPLES) { refuse("geometry_budget"); return -1; }
            if (clock.nanoTime() >= deadline) return -1;
            samples++;
            Check check = probe.step(segment.from, segment.to, actorWidth, actorHeight, deadline);
            if (check == null) check = Check.UNKNOWN;
            if (clock.nanoTime() >= deadline || check == Check.PARTIAL) { partials++; return -1; }
            if (check != Check.CLEAR) rejected(check,segment.from,segment.to);
            segment.outcome = check; return 1;
        }
        Check check = inspect(probe, new Sweep(segment.at(segment.next - 1), segment.at(segment.next), actorWidth, actorHeight), deadline, clock);
        if (check == null || check == Check.PARTIAL) return -1;
        if (check != Check.CLEAR) segment.outcome = check;
        else if (segment.next == segment.steps) segment.outcome = Check.CLEAR;
        else { segment.next++; return 0; }
        return 1;
    }

    private Segment segment(Point from, Point to) {
        if (edges >= MAX_EDGES) { refuse("edge_budget"); return null; }
        edges++; return new Segment(from, to);
    }
    private double edgeCost(Point from, Point to) {
        return costs.walking(Math.hypot(to.x-from.x,to.z-from.z),to.y-from.y).score();
    }
    private void startCenter() {
        actualRoot = false;
        origin = new Point(Math.floor(legStart.x)+.5, legStart.y, Math.floor(legStart.z)+.5);
        segment = segment(legStart, origin); phase = Phase.CENTER;
    }
    private void startActualSearch() {
        // Keep the real continuous start, but connect it directly to neighbouring
        // terrain cell centers. An offset lattice alone can miss a narrow exit.
        origin = new Point(Math.floor(legStart.x)+.5,legStart.y,Math.floor(legStart.z)+.5);
        actualRoot = true;
        startSearch();
    }
    private void startSearch() {
        segment = null; active = null; candidate = null; nodes.clear(); open.clear();
        // A real cable/slab support height must not shift the entire search lattice
        // away from ordinary ground. These are candidates only; native endpoint,
        // corridor and dual-owner execution proofs still decide every transition.
        heightFractions.clear(); addHeightFraction(origin.y); addHeightFraction(0); addHeightFraction(targets.get(leg).y);
        Point root = actualRoot ? legStart : origin;
        Node first = node(0, 0, root);
        if (first == null) return;
        first.cost = edgeCost(legStart,root); first.traveled = legStart.distance(root); open.add(new Entry(first, targets.get(leg), order++, costs, endpointGoal)); phase = Phase.SEARCH;
    }
    private void addHeightFraction(double y) {
        double fraction = y-Math.floor(y);
        for (double existing : heightFractions) if (Math.abs(existing-fraction)<.0001) return;
        heightFractions.add(fraction);
    }
    private Node node(int x, int z, Point point) {
        if (discovered >= MAX_NODES) { refuse("node_budget"); return null; }
        Node node = new Node(x, z, point); nodes.put(key(x, point.y, z), node); discovered++; return node;
    }
    private double remainingLowerBound(Point point) {
        if (endpointGoal != null) return 0;
        double value = point.distance(targets.get(leg));
        for (int i = leg + 1; i < targets.size(); i++) value += targets.get(i - 1).distance(targets.get(i));
        return value;
    }
    private static List<Point> reconstruct(Node node, Point target) {
        List<Point> path = new ArrayList<Point>();
        for (Node cursor = node; cursor != null; cursor = cursor.parent) path.add(cursor.point);
        Collections.reverse(path);
        if (path.isEmpty() || path.get(path.size() - 1).distance(target) > EPSILON) path.add(target);
        else path.set(path.size() - 1, target);
        return path;
    }
    private void startSmooth(List<Point> path) {
        rawPath = path; smoothFrom = legStart; smoothIndex = 0; smoothed.clear();
        smoothTry = lastSmoothIndex(); segment = null; phase = Phase.SMOOTH;
    }
    private int lastSmoothIndex() {
        if (Math.abs(rawPath.get(smoothIndex).y-smoothFrom.y)>.0001) return smoothIndex;
        int index = smoothIndex;
        while (index+1<rawPath.size() && Math.abs(rawPath.get(index+1).y-smoothFrom.y)<.0001) index++;
        return index;
    }
    private void finishLeg(List<Point> path) {
        Point previous = legStart;
        for (Point point : path) { distance += previous.distance(point); previous = point; }
        if (distance + remainingLowerBound(targets.get(leg)) > MAX_DISTANCE + EPSILON) { refuse("distance_limit"); return; }
        // Merge collinear grid steps only; no geometric shortcut and no user waypoint is removed.
        List<Point> compact = new ArrayList<Point>(); Point before = legStart;
        for (int i = 0; i < path.size(); i++) {
            Point point = path.get(i);
            if (i + 1 < path.size()) {
                Point after = path.get(i + 1);
                double ax = point.x - before.x, az = point.z - before.z, bx = after.x - point.x, bz = after.z - point.z;
                if (Math.abs(before.y-point.y)<.0001 && Math.abs(after.y-point.y)<.0001 && Math.abs(ax * bz - az * bx) < EPSILON && ax * bx + az * bz > 0) continue;
            }
            compact.add(point); before = point;
        }
        planned.addAll(compact);
        if (planned.size() > MAX_ROUTE_POINTS) { refuse("route_point_budget"); return; }
        legStart = endpointGoal == null ? targets.get(leg) : path.get(path.size()-1); leg++; segment = null; active = null; candidate = null; nodes.clear(); open.clear();
        phase = leg == targets.size() ? Phase.VALIDATE : Phase.TARGET;
    }
    private void publish() {
        double actual = 0; Point previous = start;
        for (Point point : planned) { actual += previous.distance(point); previous = point; }
        if (actual > MAX_DISTANCE + EPSILON || Math.abs(actual - distance) > .00001) { refuse("distance_limit"); return; }
        result = Collections.unmodifiableList(new ArrayList<Point>(planned));
        status = Status.FOUND; reason = "grounded_route_observed"; segment = null;
    }
    private void refuse(String value) { refusedPhase = phase.name(); status = Status.REFUSED; reason = value; segment = null; nodes.clear(); open.clear(); }
    private void rejected(Check check, Point from, Point to) {
        rejected++; rejectedByCheck[check.ordinal()]++;
        com.google.gson.JsonObject row = new com.google.gson.JsonObject(); row.addProperty("check",code(check));
        row.addProperty("phase",phase.name()); row.add("from", diagnosticPoint(from)); row.add("to",diagnosticPoint(to));
        if (rejectedEdges.size() == 3) rejectedEdges.remove(0);
        rejectedEdges.add(row);
    }
    private static com.google.gson.JsonArray diagnosticPoint(Point point) {
        com.google.gson.JsonArray row = new com.google.gson.JsonArray();
        row.add(new com.google.gson.JsonPrimitive(point.x)); row.add(new com.google.gson.JsonPrimitive(point.y)); row.add(new com.google.gson.JsonPrimitive(point.z)); return row;
    }
    com.google.gson.JsonObject diagnostic() {
        com.google.gson.JsonObject value = new com.google.gson.JsonObject(); value.addProperty("reason",reason);
        value.addProperty("phase",refusedPhase == null ? phase.name() : refusedPhase); value.addProperty("nodes",discovered);
        value.addProperty("expanded",expanded); value.addProperty("edges",edges); value.addProperty("samples",samples);
        value.addProperty("partials",partials); value.addProperty("slices",slices);
        value.addProperty("interactionGoalChecks",goalChecks);
        if (!Double.isNaN(minExpandedY)) { value.addProperty("minExpandedY",minExpandedY); value.addProperty("maxExpandedY",maxExpandedY); }
        value.addProperty("distancePruned",distancePruned); value.addProperty("heightPruned",heightPruned);
        com.google.gson.JsonObject checks = new com.google.gson.JsonObject();
        for (Check check : Check.values()) if (rejectedByCheck[check.ordinal()] > 0) checks.addProperty(code(check),rejectedByCheck[check.ordinal()]);
        value.add("rejected",checks); com.google.gson.JsonArray rows = new com.google.gson.JsonArray(); for (com.google.gson.JsonObject row : rejectedEdges) rows.add(row);
        value.add("lastRejectedEdges",rows); value.addProperty("scope","bounded_search_only_not_global_unreachability"); return value;
    }
    private static String key(int x, double y, int z) { return x+":"+Double.toHexString(y)+":"+z; }
    private static Point copy(Point value) { return value == null ? new Point(Double.NaN, Double.NaN, Double.NaN) : new Point(value.x, value.y, value.z); }
    private static boolean valid(Point p) { return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z) && Math.abs(p.x) <= 29999999 && Math.abs(p.z) <= 29999999 && p.y >= 0 && p.y <= 253; }
    private static String code(Check check) { return check.name().toLowerCase(java.util.Locale.ROOT); }
    Status status() { return status; }
    String reason() { return reason; }
    List<Point> route() { return result; }
    double routeDistance() { return distance; }
    int samples() { return samples; }
    int edges() { return edges; }
    int discoveredNodes() { return discovered; }
    int rejectedSamples() { return rejected; }
    int partialSamples() { return partials; }
    int slices() { return slices; }
}
