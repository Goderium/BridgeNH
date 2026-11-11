package com.Goderium.BridgeNH.client.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline geometry fixtures only; these do not prove actual client flight or live acceptance. */
public final class FlightPathPlannerSelfTest {
    private static int tests;
    private static final class Clock implements FlightPathPlanner.Clock {
        long now;
        public long nanoTime() { return now; }
    }
    private static final class Box {
        final double minX, maxX, minY, maxY, minZ, maxZ;
        final FlightPathPlanner.Check check;
        Box(double minX, double maxX, double minY, double maxY, double minZ, double maxZ, FlightPathPlanner.Check check) {
            this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ; this.check = check;
        }
        boolean overlaps(FlightPathPlanner.Sweep s) {
            return s.x + s.widthX / 2 > minX && s.x - s.widthX / 2 < maxX && s.z + s.widthZ / 2 > minZ && s.z - s.widthZ / 2 < maxZ
                    && s.feetY + s.height > minY && s.feetY < maxY;
        }
    }
    private static final class World implements FlightPathPlanner.Probe {
        final List<Box> boxes = new ArrayList<Box>();
        int calls;
        public FlightPathPlanner.Check inspect(FlightPathPlanner.Sweep s, long deadline) {
            calls++;
            require(s.from.distance(s.to) <= FlightPathPlanner.SWEEP_STEP + .000001, "Every actual body sweep is finite.");
            require(s.widthX >= .6 && s.widthZ >= .6 && s.height >= 1.8, "Actual X/Z widths and height are never reduced.");
            if (s.feetY < 4 - .000001) return FlightPathPlanner.Check.BLOCKED;
            for (Box b : boxes) if (b.overlaps(s)) return b.check;
            return FlightPathPlanner.Check.CLEAR;
        }
        World box(double minX, double maxX, double minY, double maxY, double minZ, double maxZ, FlightPathPlanner.Check check) {
            boxes.add(new Box(minX, maxX, minY, maxY, minZ, maxZ, check)); return this;
        }
    }
    private static FlightPathPlanner.Point point(double x, double y, double z) { return new FlightPathPlanner.Point(x, y, z); }
    private static FlightPathPlanner planner(FlightPathPlanner.Point start, FlightPathPlanner.Point... targets) {
        return new FlightPathPlanner(start, Arrays.asList(targets), .6, 1.8, Long.MAX_VALUE / 2);
    }
    private static void run(FlightPathPlanner p, World w) {
        Clock c = new Clock();
        for (int i = 0; i < 3000 && p.status() == FlightPathPlanner.Status.PLANNING; i++) {
            int before = w.calls; p.advance(w, Long.MAX_VALUE / 2, c);
            require(w.calls - before <= FlightPathPlanner.MAX_SLICE_SAMPLES, "Hard per-slice sample bound.");
        }
        require(p.status() != FlightPathPlanner.Status.PLANNING, "Bounded search terminates.");
        require(p.discoveredNodes() <= FlightPathPlanner.MAX_NODES && p.edges() <= FlightPathPlanner.MAX_EDGES
                && p.samples() <= FlightPathPlanner.MAX_SAMPLES, "Strict node, edge and geometry bounds.");
    }
    private static void found(FlightPathPlanner p, World w) {
        run(p, w); require(p.status() == FlightPathPlanner.Status.FOUND, "Expected complete route, got " + p.reason());
        require(p.routeDistance() <= 32.0000001 && p.route().size() <= FlightPathPlanner.MAX_ROUTE_POINTS, "Whole route distance and point bounds.");
    }
    private static void test(String name, Runnable body) {
        try { body.run(); tests++; } catch (Throwable error) { throw new AssertionError(name, error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void run() {
        tests = 0;
        test("recorded assembly corner reserves horizontal arrival drift", () -> {
            FlightPathPlanner.Point start = point(93.64377161861819,6.103011643097426,64.84073890657544);
            FlightPathPlanner.Point target = point(82.77051125987627,6.051505821548713,51.19484528246386);
            FlightPathPlanner p = planner(start,target);
            Box machine = new Box(84,87,4,9,40,56,FlightPathPlanner.Check.BLOCKED);
            found(p,new World().box(84,87,4,9,40,56,FlightPathPlanner.Check.BLOCKED));
            require(p.route().size() <= 3, "Recorded corner needs only its proved turns, not every grid heading change.");
            for (FlightPathPlanner.Point corner : p.route()) {
                if (corner.distance(start)<=PlayerNavigation.TOLERANCE || corner.distance(target)<=PlayerNavigation.TOLERANCE) continue;
                require(!machine.overlaps(new FlightPathPlanner.Sweep(corner,corner,.6+2*PlayerNavigation.TOLERANCE,1.8)),
                        "Every internal corner retains the real controller's horizontal tolerance before turning.");
            }
        });
        test("long diagonal beside an assembly envelope stays within search budget", () -> {
            // Conservative solid envelope fixture inspired by the observed route;
            // not a capture of actual machine collision shapes.
            FlightPathPlanner p = planner(point(85.929, 6, 59.368), point(76.4024, 6, 38.2592));
            World world = new World().box(84, 87, 4, 9, 40, 56, FlightPathPlanner.Check.BLOCKED);
            run(p, world);
            require(p.status() == FlightPathPlanner.Status.FOUND,
                    "Assembly envelope route: " + p.reason() + ", nodes=" + p.discoveredNodes() + ", samples=" + p.samples());
            require(p.routeDistance() <= FlightPathPlanner.MAX_DISTANCE && p.discoveredNodes() < FlightPathPlanner.MAX_NODES,
                    "The existing route and node limits remain enforced.");
            System.out.println("Assembly envelope flight fixture nodes=" + p.discoveredNodes() + ", samples=" + p.samples());
        });
        test("open non-flat corridor retains direct three-dimensional route", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(6, 8, 2)); found(p, new World());
            require(p.discoveredNodes() == 0 && p.route().size() == 1 && Math.abs(p.routeDistance() - Math.sqrt(56)) < .000001, "Exact 3D distance is used.");
        });
        test("wall spanning horizontal budget requires vertical obstacle detour", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(6, 4, 0));
            found(p, new World().box(2, 3, 4, 7, -40, 40, FlightPathPlanner.Check.BLOCKED));
            boolean climbed = false; for (FlightPathPlanner.Point q : p.route()) climbed |= q.y >= 7;
            require(climbed && p.routeDistance() > 6 && p.discoveredNodes() > 0, "Loaded body geometry chooses an actual vertical detour.");
        });
        test("full-height obstacle needs horizontal detour", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(6, 4, 0));
            found(p, new World().box(2, 3, 0, 256, -.5, .5, FlightPathPlanner.Check.BLOCKED));
            boolean side = false; for (FlightPathPlanner.Point q : p.route()) side |= Math.abs(q.z) >= 1;
            require(side, "Three-dimensional search keeps lateral flight choices.");
        });
        for (FlightPathPlanner.Check check : new FlightPathPlanner.Check[] {FlightPathPlanner.Check.BLOCKED, FlightPathPlanner.Check.UNLOADED, FlightPathPlanner.Check.UNKNOWN}) {
            test("unsafe start refuses " + check, () -> {
                FlightPathPlanner p = planner(point(0, 4, 0), point(3, 4, 0)); World w = new World().box(-1, 1, 4, 6, -1, 1, check);
                run(p, w); require(p.reason().startsWith("start_unsafe_"), "Unsafe actual start is not clear.");
            });
            test("unsafe destination refuses " + check, () -> {
                FlightPathPlanner p = planner(point(0, 4, 0), point(3, 4, 0)); World w = new World().box(2.8, 3.2, 4, 6, -.5, .5, check);
                run(p, w); require(p.reason().startsWith("target_unsafe_"), "Unknown destination is not invented.");
            });
            test("unsafe transit permits only completely observed detour " + check, () -> {
                FlightPathPlanner p = planner(point(0, 4, 0), point(5, 4, 0));
                found(p, new World().box(2, 3, 4, 7, -.5, .5, check)); require(p.rejectedSamples() > 0, "Rejected space is never traversed.");
            });
        }
        test("continuous upward and diagonal body includes thin intermediate collisions", () -> {
            FlightPathPlanner.Sweep s = new FlightPathPlanner.Sweep(point(0, 4, 0), point(.125, 4.125, .125), .6, 1.8);
            require(s.feetY == 4 && s.height == 1.925 && s.widthX == .725 && s.widthZ == .725 && s.x == .0625, "All translated body bounds are conservatively enclosed.");
            Box thin = new Box(.3, .301, 5.91, 5.92, -.1, .1, FlightPathPlanner.Check.BLOCKED);
            require(thin.overlaps(s), "A thin obstacle between foot samples is caught by the body envelope.");
        });
        test("descent envelope starts at lower feet and preserves complete actor height", () -> {
            FlightPathPlanner.Sweep s = new FlightPathPlanner.Sweep(point(0, 6, 0), point(0, 5.75, 0), .6, 1.8);
            require(s.feetY == 5.75 && s.height == 2.05, "Descending body covers both native poses continuously.");
        });
        test("X-only travel does not enlarge the Z footprint", () -> {
            FlightPathPlanner.Sweep s = new FlightPathPlanner.Sweep(point(0, 4, 0), point(2, 4, 0), .6, 1.8);
            require(s.widthX == 2.6 && s.widthZ == .6, "Independent full translated dimensions.");
            require(!new Box(.5, 1, 4, 6, .31, .4, FlightPathPlanner.Check.BLOCKED).overlaps(s), "Side clearance is not consumed by unrelated X travel.");
            require(new Box(.5, 1, 4, 6, .299, .4, FlightPathPlanner.Check.BLOCKED).overlaps(s), "A true full-body intrusion is still blocked.");
        });
        test("Z-only travel does not enlarge the X footprint", () -> {
            FlightPathPlanner.Sweep s = new FlightPathPlanner.Sweep(point(0, 4, 0), point(0, 4, -2), .6, 1.8);
            require(s.widthX == .6 && s.widthZ == 2.6, "Axis dimensions are symmetric under X/Z exchange.");
            require(!new Box(.31, .4, 4, 6, -1, -.5, FlightPathPlanner.Check.BLOCKED).overlaps(s), "Exact side corridor remains available.");
        });
        test("unequal diagonal and turning legs retain the entire swept body", () -> {
            FlightPathPlanner.Sweep diagonal = new FlightPathPlanner.Sweep(point(0, 4, 0), point(2, 4.5, .5), .6, 1.8);
            require(diagonal.widthX == 2.6 && diagonal.widthZ == 1.1 && diagonal.height == 2.3, "Diagonal translation independently expands all three axes.");
            require(new Box(.99, 1.01, 5.79, 5.81, .24, .26, FlightPathPlanner.Check.BLOCKED).overlaps(diagonal), "Thin intermediate full-body collision remains conservative.");
            FlightPathPlanner.Sweep turn = new FlightPathPlanner.Sweep(point(2, 4, 0), point(2, 4, 2), .6, 1.8);
            require(new Box(1.9, 2.1, 4, 6, .99, 1.01, FlightPathPlanner.Check.BLOCKED).overlaps(turn), "The second turning leg cannot skip its body collision.");
        });
        test("reported wall-side inertia case has a tighter complete body envelope", () -> {
            FlightPathPlanner.Point start = point(-653.5408705349848, 5.83880650448082, -17.497783483891602);
            FlightPathPlanner.Point finish = point(start.x - .054115154539969124 * 2.75 - .3,
                    start.y - .01296 * 1.96, start.z - .007539209286446598 * 2.75);
            FlightPathPlanner.Sweep s = new FlightPathPlanner.Sweep(start, finish, .6, 1.8);
            Box column = new Box(-655, -654, 4, 10, -17, -16, FlightPathPlanner.Check.BLOCKED);
            require(!column.overlaps(s), "Full translated rectangle stays outside this independent fixture's column.");
            double oldWidth = Math.max(s.widthX, s.widthZ);
            require(s.x - oldWidth / 2 < -654 && s.z + oldWidth / 2 > -17, "The former square would overlap the same column.");
            require(s.widthX >= .6 && s.widthZ >= .6, "No smaller actor shape causes the difference.");
        });
        test("exact 32-block vertical route remains cooperative", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(0, 36, 0)); found(p, new World());
            require(p.routeDistance() == 32 && p.slices() >= 3 && p.discoveredNodes() == 0, "Distance and slice limits apply in 3D.");
        });
        test("overlength request performs no world access", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(0, 36.001, 0)); World w = new World(); run(p, w);
            require("distance_limit".equals(p.reason()) && w.calls == 0, "No partial long path can be published.");
        });
        test("detour cannot escape 32-block total route budget", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(32, 4, 0));
            run(p, new World().box(15, 16, 4, 6, -.5, .5, FlightPathPlanner.Check.BLOCKED));
            require(p.status() == FlightPathPlanner.Status.REFUSED && p.route().isEmpty(), "An overlength detour cannot authorize input.");
        });
        test("partial native coverage yields and is never proof", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(2, 4, 0)); Clock c = new Clock(); final int[] calls = {0};
            p.advance((s, d) -> { calls[0]++; return FlightPathPlanner.Check.PARTIAL; }, Long.MAX_VALUE / 2, c);
            require(calls[0] == 1 && p.status() == FlightPathPlanner.Status.PLANNING && p.route().isEmpty(), "Incomplete observation never starts a route.");
            found(p, new World());
        });
        test("selected route is freshly validated after planning", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(2, 5, 0)); Clock c = new Clock(); final int[] visits = {0};
            FlightPathPlanner.Probe dynamic = (s, d) -> s.from.x == 0 && s.to.x > 0 && ++visits[0] == 2
                    ? FlightPathPlanner.Check.BLOCKED : FlightPathPlanner.Check.CLEAR;
            for (int i = 0; i < 100 && p.status() == FlightPathPlanner.Status.PLANNING; i++) p.advance(dynamic, Long.MAX_VALUE / 2, c);
            require("route_changed_blocked".equals(p.reason()) && p.route().isEmpty(), "Changed geometry prevents route publication.");
        });
        test("late clear callback remains partial", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(2, 4, 0)); Clock c = new Clock();
            p.advance((s, d) -> { c.now = d; return FlightPathPlanner.Check.CLEAR; }, Long.MAX_VALUE / 2, c);
            require(p.status() == FlightPathPlanner.Status.PLANNING && p.partialSamples() == 1 && p.route().isEmpty(), "Over-budget callbacks never become proofs.");
        });
        test("four millisecond slice deadline is passed to every probe", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(8, 4, 0)); Clock c = new Clock(); final int[] calls = {0};
            p.advance((s, d) -> { require(d == FlightPathPlanner.WORK_NANOS && c.now < d, "Exact slice deadline."); calls[0]++; c.now += 2000000L; return FlightPathPlanner.Check.CLEAR; }, Long.MAX_VALUE / 2, c);
            require(calls[0] == 2 && p.status() == FlightPathPlanner.Status.PLANNING, "The next probe never starts after its slice deadline.");
        });
        test("repeated partial geometry has a strict total budget", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(2, 4, 0)); Clock c = new Clock();
            for (int i = 0; i <= FlightPathPlanner.MAX_SAMPLES && p.status() == FlightPathPlanner.Status.PLANNING; i++)
                p.advance((s, d) -> FlightPathPlanner.Check.PARTIAL, Long.MAX_VALUE / 2, c);
            require("geometry_budget".equals(p.reason()) && p.samples() == FlightPathPlanner.MAX_SAMPLES, "Partial retry consumes finite work.");
        });
        test("request expiry performs no callback", () -> {
            FlightPathPlanner p = new FlightPathPlanner(point(0, 4, 0), Collections.singletonList(point(2, 4, 0)), .6, 1.8, 1);
            Clock c = new Clock(); c.now = 1; World w = new World(); p.advance(w, 100, c);
            require("deadline".equals(p.reason()) && w.calls == 0, "No work after the overall request deadline.");
        });
        test("finished immutable scalar plan cannot replay world callbacks", () -> {
            FlightPathPlanner p = planner(point(0, 4, 0), point(2, 5, 1)); World w = new World(); found(p, w); int calls = w.calls;
            p.advance(w, Long.MAX_VALUE / 2, new Clock()); require(w.calls == calls, "No replay after completion.");
            boolean rejected = false; try { p.route().clear(); } catch (UnsupportedOperationException expected) { rejected = true; }
            require(rejected, "Published route is immutable.");
            for (java.lang.reflect.Field f : FlightPathPlanner.class.getDeclaredFields()) if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                require(f.getType() != FlightPathPlanner.Probe.class && f.getType() != FlightPathPlanner.Clock.class, "No world callback or clock is retained in request state.");
        });
        System.out.println("FlightPathPlannerSelfTest: " + tests + " offline cases passed");
    }
    public static void main(String[] args) { run(); }
}
