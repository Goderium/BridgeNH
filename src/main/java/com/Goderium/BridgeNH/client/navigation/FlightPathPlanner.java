package com.Goderium.BridgeNH.client.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Request-local, cooperative three-dimensional native flight search. No world, actor, input or teleport is retained. */
public final class FlightPathPlanner {
    static final double MAX_DISTANCE = 32, GRID = .5, SWEEP_STEP = .25;
    public static final long WORK_NANOS = 4000000L;
    static final int MAX_NODES = 4096, MAX_EDGES = 16384, MAX_SAMPLES = 32768;
    static final int MAX_SLICE_SAMPLES = 128, MAX_SLICE_OPERATIONS = 512, MAX_ROUTE_POINTS = 96;
    private static final double EPSILON = .0000001;
    private static final int[][] DIRECTIONS = {
        {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {-1, 0, -1}, {1, 0, -1},
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
        {1, 1, 1}, {-1, 1, 1}, {-1, 1, -1}, {1, 1, -1},
        {1, -1, 1}, {-1, -1, 1}, {-1, -1, -1}, {1, -1, -1}
    };

    enum Status { PLANNING, FOUND, REFUSED }
    public enum Check { CLEAR, BLOCKED, UNLOADED, UNKNOWN, PARTIAL }

    interface Clock { long nanoTime(); }
    public interface Probe {
        /** Owning client thread only. Inspect every cell/entity in this conservative swept envelope. */
        Check inspect(Sweep sweep, long deadline);
    }
    static final Clock SYSTEM_CLOCK = System::nanoTime;

    public static final class Point {
        final double x, y, z;
        public Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        double distance(Point other) { return Math.sqrt((other.x - x) * (other.x - x) + (other.y - y) * (other.y - y) + (other.z - z) * (other.z - z)); }
    }

    public static final class Sweep {
        final Point from, to;
        public final double x, feetY, z, widthX, widthZ, height;
        public final List<Sweep> pieces;
        public Sweep(Point from, Point to, double actorWidth, double actorHeight) {
            this(from, to, actorWidth, actorHeight, null);
        }
        Sweep(Point from, Point to, double actorWidth, double actorHeight, List<Sweep> pieces) {
            this.from = from; this.to = to;
            this.pieces = pieces == null ? null : Collections.unmodifiableList(new ArrayList<Sweep>(pieces));
            x = (from.x + to.x) / 2; feetY = Math.min(from.y, to.y); z = (from.z + to.z) / 2;
            // The complete translated body AABB expands each horizontal axis independently.
            widthX = actorWidth + Math.abs(to.x - from.x);
            widthZ = actorWidth + Math.abs(to.z - from.z);
            height = actorHeight + Math.abs(to.y - from.y);
        }
    }

    private enum Phase { START, TARGET, DIRECT, SEARCH, SMOOTH, VALIDATE }
    private static final class Node {
        final int x, y, z;
        final Point point;
        double cost = Double.POSITIVE_INFINITY;
        Node parent;
        boolean closed;
        Node(int x, int y, int z, Point point) { this.x = x; this.y = y; this.z = z; this.point = point; }
    }
    private static final class Entry {
        final Node node;
        final double cost, estimate;
        final long order;
        Entry(Node node, Point goal, long order) { this.node = node; cost = node.cost; estimate = cost + node.point.distance(goal); this.order = order; }
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
        Point at(int index) { double f = index / (double) steps; return new Point(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f, from.z + (to.z - from.z) * f); }
    }

    private final Point start;
    private final List<Point> targets;
    private final double actorWidth, actorHeight;
    private final long overallDeadline;
    private final Map<Long, Node> nodes = new HashMap<Long, Node>();
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
    private int shortcutAttempts;
    private double nextShortcutCost;
    private List<Point> smoothingPath;
    private final List<Point> smoothed = new ArrayList<Point>();
    private int smoothingIndex, smoothingCandidate, smoothingAttempts;
    private Point smoothingStart;

    FlightPathPlanner(Point start, List<Point> targets, double actorWidth, double actorHeight, long overallDeadline) {
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
            lowerBound += previous.distance(target); previous = target;
        }
        if (lowerBound > MAX_DISTANCE + EPSILON) refuse("distance_limit");
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
                if (phase == Phase.START) phase = Phase.TARGET;
                else { segment = segment(legStart, targets.get(leg)); phase = Phase.DIRECT; }
            } else if (phase == Phase.DIRECT) {
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (status != Status.PLANNING) break;
                if (segment.outcome == Check.CLEAR) finishLeg(Collections.singletonList(targets.get(leg)));
                else startSearch();
            } else if (phase == Phase.SEARCH) {
                if (segment != null) {
                    int progress = advanceSegment(probe, deadline, clock);
                    if (progress < 0) break;
                    if (progress == 0) continue;
                    if (status != Status.PLANNING) break;
                    if (segment.outcome == Check.CLEAR) {
                        if (goalEdge) { smoothLeg(reconstruct(active, targets.get(leg))); continue; }
                        double cost = active.cost + active.point.distance(candidate.point);
                        if (cost + EPSILON < candidate.cost) {
                            candidate.cost = cost; candidate.parent = active;
                            open.add(new Entry(candidate, targets.get(leg), order++));
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
                } else if (neighbor == -1) {
                    goalEdge = true;
                    boolean near = active.point.distance(targets.get(leg)) <= .75 + EPSILON;
                    // Reuse the same continuous full-body direct probe after making
                    // progress around an obstacle. Do not force the remaining clear
                    // corridor through thousands of half-block frontier nodes.
                    boolean shortcut = !near && shortcutAttempts < 16 && active.cost >= nextShortcutCost;
                    if (shortcut) { shortcutAttempts++; nextShortcutCost = active.cost + 1; }
                    if (near || shortcut) segment = segment(active.point, targets.get(leg));
                    else neighbor++;
                } else if (neighbor < DIRECTIONS.length) {
                    goalEdge = false;
                    int nx = active.x + DIRECTIONS[neighbor][0], ny = active.y + DIRECTIONS[neighbor][1], nz = active.z + DIRECTIONS[neighbor][2];
                    Point point = new Point(legStart.x + nx * GRID, legStart.y + ny * GRID, legStart.z + nz * GRID);
                    double cost = active.cost + active.point.distance(point);
                    // Reserve the exact lower bound through all remaining hard waypoints.
                    if (!valid(point) || distance + cost + remainingLowerBound(point) > MAX_DISTANCE + EPSILON) { neighbor++; continue; }
                    Node existing = nodes.get(key(nx, ny, nz));
                    if (existing != null && (existing.closed || cost + EPSILON >= existing.cost)) { neighbor++; continue; }
                    candidate = existing == null ? node(nx, ny, nz, point) : existing;
                    if (candidate == null) break;
                    segment = segment(active.point, point);
                } else active = null;
            } else if (phase == Phase.SMOOTH) {
                if (smoothingIndex == smoothingPath.size()) { finishLeg(smoothed); continue; }
                if (segment == null) {
                    // Retain the already proved adjacent edge when no longer shortcut
                    // remains, or the optional smoothing work has reached its bound.
                    if (smoothingCandidate == smoothingIndex || smoothingAttempts >= 32) {
                        smoothed.add(smoothingPath.get(smoothingIndex++));
                        smoothingStart = smoothed.get(smoothed.size()-1);
                        smoothingCandidate = smoothingPath.size()-1;
                        continue;
                    }
                    smoothingAttempts++;
                    segment = segment(smoothingStart, smoothingPath.get(smoothingCandidate));
                    if (segment == null) break;
                }
                int progress = advanceSegment(probe, deadline, clock);
                if (progress < 0) break;
                if (progress == 0) continue;
                if (status != Status.PLANNING) break;
                if (segment.outcome == Check.CLEAR) {
                    smoothed.add(smoothingPath.get(smoothingCandidate));
                    smoothingIndex = smoothingCandidate+1;
                    smoothingStart = smoothed.get(smoothed.size()-1);
                    smoothingCandidate = smoothingPath.size()-1;
                } else smoothingCandidate--;
                segment = null;
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
        else if (check != Check.CLEAR) rejected++;
        return check;
    }

    private int advanceSegment(Probe probe, long deadline, Clock clock) {
        if (segment == null || status != Status.PLANNING) return -1;
        Point from = segment.at(segment.next - 1), to = segment.at(segment.next);
        double margin = Math.min(PlayerNavigation.TOLERANCE, Math.min(start.distance(from), start.distance(to)));
        for (Point target : targets) margin = Math.min(margin, Math.min(target.distance(from), target.distance(to)));
        // Native feedback accepts a horizontal arrival tolerance at grid corners.
        // Reserve that same margin along the route instead of allowing a tolerated
        // corner stop to cut into a block on the next leg. Actual start/hard endpoints
        // retain their exact body; the margin tapers only within their tolerance.
        Check check = inspect(probe, new Sweep(from, to, actorWidth + 2*margin, actorHeight), deadline, clock);
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
    private void startSearch() {
        segment = null; active = null; candidate = null; nodes.clear(); open.clear();
        shortcutAttempts = 0; nextShortcutCost = 1;
        Node first = node(0, 0, 0, legStart);
        if (first == null) return;
        first.cost = 0; open.add(new Entry(first, targets.get(leg), order++)); phase = Phase.SEARCH;
    }
    private Node node(int x, int y, int z, Point point) {
        if (discovered >= MAX_NODES) { refuse("node_budget"); return null; }
        Node node = new Node(x, y, z, point); nodes.put(key(x, y, z), node); discovered++; return node;
    }
    private double remainingLowerBound(Point point) {
        double value = point.distance(targets.get(leg));
        for (int i = leg + 1; i < targets.size(); i++) value += targets.get(i - 1).distance(targets.get(i));
        return value;
    }
    private static List<Point> reconstruct(Node node, Point target) {
        List<Point> path = new ArrayList<Point>();
        for (Node cursor = node; cursor.parent != null; cursor = cursor.parent) path.add(cursor.point);
        Collections.reverse(path);
        if (path.isEmpty() || path.get(path.size() - 1).distance(target) > EPSILON) path.add(target);
        else path.set(path.size() - 1, target);
        return path;
    }
    private void smoothLeg(List<Point> path) {
        // Work only inside this leg: explicit hard targets cannot be removed.
        // Every shortcut uses the same bounded, clearance-aware swept-body probe
        // and the entire published route is still freshly validated afterwards.
        smoothingPath = path; smoothed.clear(); smoothingIndex = 0;
        smoothingCandidate = path.size()-1; smoothingAttempts = 0;
        smoothingStart = legStart; segment = null; phase = Phase.SMOOTH;
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
                double ax = point.x - before.x, ay = point.y - before.y, az = point.z - before.z;
                double bx = after.x - point.x, by = after.y - point.y, bz = after.z - point.z;
                if (Math.abs(ax * bz - az * bx) < EPSILON && Math.abs(ax * by - ay * bx) < EPSILON
                        && Math.abs(ay * bz - az * by) < EPSILON && ax * bx + ay * by + az * bz > 0) continue;
            }
            compact.add(point); before = point;
        }
        planned.addAll(compact);
        if (planned.size() > MAX_ROUTE_POINTS) { refuse("route_point_budget"); return; }
        legStart = targets.get(leg++); segment = null; active = null; candidate = null; nodes.clear(); open.clear();
        phase = leg == targets.size() ? Phase.VALIDATE : Phase.TARGET;
    }
    private void publish() {
        double actual = 0; Point previous = start;
        for (Point point : planned) { actual += previous.distance(point); previous = point; }
        if (actual > MAX_DISTANCE + EPSILON || Math.abs(actual - distance) > .00001) { refuse("distance_limit"); return; }
        result = Collections.unmodifiableList(new ArrayList<Point>(planned));
        status = Status.FOUND; reason = "flight_route_observed"; segment = null;
    }
    private void refuse(String value) { status = Status.REFUSED; reason = value; segment = null; nodes.clear(); open.clear(); }
    private static long key(int x, int y, int z) {
        // The 32-block route bound limits every grid offset to [-64, 64]. This is injective.
        return ((long) (x + 64) << 16) | ((long) (y + 64) << 8) | (z + 64);
    }
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
