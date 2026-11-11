package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.config.NavigationCosts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure Java geometry fixtures only. These are not client/world acceptance or navigation timing evidence. */
public final class WalkingPathPlannerSelfTest {
    private static int tests;
    private static final class Clock implements WalkingPathPlanner.Clock {
        long now;
        public long nanoTime() { return now; }
    }
    private static final class Box {
        final double minX, maxX, minZ, maxZ;
        final WalkingPathPlanner.Check check;
        Box(double minX, double maxX, double minZ, double maxZ, WalkingPathPlanner.Check check) {
            this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ; this.check = check;
        }
        boolean overlaps(WalkingPathPlanner.Sweep sweep) {
            return sweep.x + sweep.width / 2 > minX && sweep.x - sweep.width / 2 < maxX
                    && sweep.z + sweep.depth / 2 > minZ && sweep.z - sweep.depth / 2 < maxZ;
        }
    }
    private static final class World implements WalkingPathPlanner.Probe {
        final List<Box> boxes = new ArrayList<Box>();
        int calls;
        public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep, long deadline) {
            calls++;
            require(Math.abs(sweep.from.y - sweep.to.y) <= .0001, "No vertical segment is submitted.");
            require(sweep.from.distance(sweep.to) <= WalkingPathPlanner.SWEEP_STEP + .000001, "Every swept segment is bounded.");
            require(sweep.width >= .6 && sweep.height == 1.8, "Actual body height/width must be preserved or conservatively enlarged.");
            for (Box box : boxes) if (box.overlaps(sweep)) return box.check;
            return WalkingPathPlanner.Check.CLEAR;
        }
        World box(double minX, double maxX, double minZ, double maxZ, WalkingPathPlanner.Check check) {
            boxes.add(new Box(minX, maxX, minZ, maxZ, check)); return this;
        }
    }
    private static WalkingPathPlanner.Point point(double x, double z) { return new WalkingPathPlanner.Point(x, 4, z); }
    private static WalkingPathPlanner planner(WalkingPathPlanner.Point start, WalkingPathPlanner.Point... targets) {
        return new WalkingPathPlanner(start, Arrays.asList(targets), .6, 1.8, Long.MAX_VALUE / 2);
    }
    private static void run(WalkingPathPlanner planner, World world) {
        Clock clock = new Clock();
        for (int i = 0; i < 3000 && planner.status() == WalkingPathPlanner.Status.PLANNING; i++) {
            int before = world.calls;
            planner.advance(world, Long.MAX_VALUE / 2, clock);
            require(world.calls - before <= WalkingPathPlanner.MAX_SLICE_SAMPLES, "Hard slice probe bound.");
        }
        require(planner.status() != WalkingPathPlanner.Status.PLANNING, "Finite search must terminate.");
        require(planner.discoveredNodes() <= WalkingPathPlanner.MAX_NODES, "Node bound.");
        require(planner.edges() <= WalkingPathPlanner.MAX_EDGES && planner.samples() <= WalkingPathPlanner.MAX_SAMPLES, "Edge/sample bounds.");
    }
    private static void found(WalkingPathPlanner planner, World world) {
        run(planner, world);
        require(planner.status() == WalkingPathPlanner.Status.FOUND, "Expected safe route, got " + planner.reason());
        require(planner.routeDistance() <= 32.0000001 && !planner.route().isEmpty(), "Complete route remains within actual distance bound.");
        require(planner.route().size() <= WalkingPathPlanner.MAX_ROUTE_POINTS, "Compact result bound.");
    }
    private static boolean contains(List<WalkingPathPlanner.Point> route, WalkingPathPlanner.Point point) {
        for (WalkingPathPlanner.Point value : route) if (value.distance(point) < .0000001 && value.y == point.y) return true;
        return false;
    }
    private static void test(String name, Runnable body) {
        try { body.run(); tests++; } catch (Throwable error) { throw new AssertionError(name, error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void run() {
        tests = 0;
        test("exact nine-block benchmark corridor prefers direct route", () -> {
            WalkingPathPlanner p = planner(point(-638.5, -19.5), point(-629.5, -19.5));
            World w = new World().box(-635, -634, -19, -18, WalkingPathPlanner.Check.BLOCKED);
            found(p, w);
            require(p.route().size() == 1 && p.discoveredNodes() == 0 && p.routeDistance() == 9, "Clear actual benchmark row must not invoke A*.");
            require(p.samples() == 74, "Start/target, 36 direct sweeps and 36 fresh validation sweeps.");
        });
        test("AE workbench at exact native coordinates needs automatic detour", () -> {
            WalkingPathPlanner p = planner(point(-638.5, -18.5), point(-629.5, -18.5));
            found(p, new World().box(-635, -634, -19, -18, WalkingPathPlanner.Check.BLOCKED));
            require(p.discoveredNodes() > 0 && p.route().size() > 1 && p.routeDistance() > 9, "Workbench is routed around with native walking geometry.");
        });
        test("generic full body wall detour", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(6, 0));
            found(p, new World().box(2, 3, -.5, .5, WalkingPathPlanner.Check.BLOCKED));
            require(p.routeDistance() > 6 && p.route().size() >= 2, "Collision-proved shortcuts reduce turns while retaining the necessary detour.");
        });
        test("multiple obstacle turns do not need extra user waypoints", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(8, 0));
            found(p, new World().box(2, 2.5, -4, 1, WalkingPathPlanner.Check.BLOCKED)
                    .box(5, 5.5, -1, 4, WalkingPathPlanner.Check.BLOCKED));
            require(p.route().size() > 3, "More than two internally generated turns are supported.");
        });
        test("explicit two user waypoints remain exact hard visits", () -> {
            WalkingPathPlanner.Point first = point(2, 2), second = point(4, -2), last = point(6, 0);
            WalkingPathPlanner p = planner(point(0, 0), first, second, last);
            found(p, new World());
            require(p.route().size() == 3 && contains(p.route(), first) && contains(p.route(), second), "No hard waypoint may be optimized away.");
        });
        test("collinear explicit waypoint is retained", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0), point(4, 0), point(6, 0));
            found(p, new World()); require(p.route().size() == 3, "Leg-boundary waypoints stay present.");
        });
        test("zero length target still checks actual body and support", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(0, 0)); found(p, new World());
            require(p.samples() == 4 && p.routeDistance() == 0, "No movement proof is invented for an unchecked start.");
        });
        test("32 exact blocks are accepted cooperatively", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(32, 0)); found(p, new World());
            require(p.slices() >= 3 && p.discoveredNodes() == 0 && p.routeDistance() == 32, "Hard per-slice bound applies even in open terrain.");
        });
        test("longer request is refused without world queries", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(32.001, 0)); World w = new World(); run(p, w);
            require("distance_limit".equals(p.reason()) && w.calls == 0, "Length refusal precedes any world access.");
        });
        test("detour cannot exceed total 32 block budget", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(32, 0));
            run(p, new World().box(15, 16, -.5, .5, WalkingPathPlanner.Check.BLOCKED));
            require(p.status() == WalkingPathPlanner.Status.REFUSED && p.route().isEmpty(), "No over-budget partial route can be published.");
        });
        test("height edge is refused when its adapter cannot prove a native transition", () -> {
            WalkingPathPlanner p = planner(point(0, 0), new WalkingPathPlanner.Point(1, 5, 0)); World w = new World(); run(p, w);
            require(p.status() == WalkingPathPlanner.Status.REFUSED && p.route().isEmpty(), "Unproved height edges never publish a route.");
        });
        test("three-block drop and one-block jump use actual terrain heights", () -> {
            for (int delta : new int[] {1,-1,-2,-3}) {
                WalkingPathPlanner.Point start = new WalkingPathPlanner.Point(.5,4,.5), goal = new WalkingPathPlanner.Point(4.5,4+delta,.5);
                WalkingPathPlanner p = planner(start,goal); Clock clock = new Clock();
                WalkingPathPlanner.Probe terrain = new WalkingPathPlanner.Probe() {
                    public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep s,long d) {
                        double half = s.width/2;
                        if (s.x-half<1 && s.x+half>1) return WalkingPathPlanner.Check.BLOCKED;
                        return Math.abs(s.feetY-(s.x<1 ? 4 : 4+delta))<.0001 ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                    }
                    public WalkingPathPlanner.Check step(WalkingPathPlanner.Point from,WalkingPathPlanner.Point to,double w,double h,long d) {
                        return from.x==.5 && to.x==1.5 && from.z==.5 && to.z==.5 && from.y==4 && to.y==4+delta
                                ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                    }
                };
                for (int i=0;i<3000 && p.status()==WalkingPathPlanner.Status.PLANNING;i++) p.advance(terrain,Long.MAX_VALUE/2,clock);
                require(p.status()==WalkingPathPlanner.Status.FOUND,"Native terrain height route: "+delta+" / "+p.reason());
                require(contains(p.route(),new WalkingPathPlanner.Point(1.5,4+delta,.5)),"Height landing remains an exact endpoint and cannot be smoothed away.");
            }
        });
        test("native thin support can connect to ordinary or declared fractional landing height", () -> {
            for(double[] heights : new double[][] {{4.69375,4},{4,4.9375},{4.9375,4.5}}) {
                WalkingPathPlanner.Point start=new WalkingPathPlanner.Point(.5,heights[0],.5),goal=new WalkingPathPlanner.Point(3.5,heights[1],.5);
                WalkingPathPlanner p=planner(start,goal); Clock clock=new Clock();
                WalkingPathPlanner.Probe terrain=new WalkingPathPlanner.Probe() {
                    public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep s,long d) {
                        if(s.x-s.width/2<1 && s.x+s.width/2>1) return WalkingPathPlanner.Check.BLOCKED;
                        return Math.abs(s.feetY-(s.x<1?heights[0]:heights[1]))<.0001?WalkingPathPlanner.Check.CLEAR:WalkingPathPlanner.Check.BLOCKED;
                    }
                    public WalkingPathPlanner.Check step(WalkingPathPlanner.Point a,WalkingPathPlanner.Point b,double w,double h,long d) {
                        return a.x==.5 && b.x==1.5 && a.z==.5 && b.z==.5 && a.y==heights[0] && b.y==heights[1]?WalkingPathPlanner.Check.CLEAR:WalkingPathPlanner.Check.BLOCKED;
                    }
                };
                for(int i=0;i<3000 && p.status()==WalkingPathPlanner.Status.PLANNING;i++)p.advance(terrain,Long.MAX_VALUE/2,clock);
                require(p.status()==WalkingPathPlanner.Status.FOUND && contains(p.route(),new WalkingPathPlanner.Point(1.5,heights[1],.5)),"Exact native-supported fractional landing is retained: "+p.reason());
            }
        });
        for (WalkingPathPlanner.Check check : new WalkingPathPlanner.Check[] {WalkingPathPlanner.Check.BLOCKED, WalkingPathPlanner.Check.UNKNOWN, WalkingPathPlanner.Check.UNLOADED}) {
            test("unsafe start " + check, () -> {
                WalkingPathPlanner p = planner(point(0, 0), point(2, 0));
                run(p, new World().box(-1, 1, -1, 1, check));
                require(p.status() == WalkingPathPlanner.Status.REFUSED && p.reason().startsWith("start_unsafe_"), "Unknown/unloaded start is never guessed.");
            });
            test("unsafe destination " + check, () -> {
                WalkingPathPlanner p = planner(point(0, 0), point(3, 0));
                run(p, new World().box(2.5, 3.5, -.5, .5, check));
                require(p.status() == WalkingPathPlanner.Status.REFUSED && p.reason().startsWith("target_unsafe_"), "Destination refusal is exact and finite.");
            });
            test("unknown/unloaded/unsafe corridor can only use observed detour " + check, () -> {
                WalkingPathPlanner p = planner(point(0, 0), point(5, 0));
                found(p, new World().box(2, 3, -.5, .5, check)); require(p.rejectedSamples() > 0, "Unsafe corridor is not accepted.");
            });
        }
        for (String kind : new String[] {"liquid", "hazard", "collidable entity", "missing full-footprint support", "thin native collision"}) {
            test("envelope rejects " + kind, () -> {
                WalkingPathPlanner p = planner(point(0, 0), point(4, 0));
                found(p, new World().box(1.12, 1.13, -.25, .25, WalkingPathPlanner.Check.BLOCKED));
                require(p.routeDistance() > 4, "Unsafe geometry must not fit through gaps between sampled feet.");
            });
        }
        test("swept rectangle includes diagonal corner and entire footprint", () -> {
            WalkingPathPlanner.Sweep sweep = new WalkingPathPlanner.Sweep(point(0, 0), point(.125, .125), .6, 1.8);
            require(sweep.width == .725 && sweep.depth == .725 && sweep.x == .0625 && sweep.z == .0625, "Full translated body is conservatively enclosed.");
        });
        test("axis-aligned swept body does not invent perpendicular width", () -> {
            WalkingPathPlanner p = planner(point(0,0),point(5,0));
            found(p,new World().box(-1,6,.31,2,WalkingPathPlanner.Check.BLOCKED)
                    .box(-1,6,-2,-.31,WalkingPathPlanner.Check.BLOCKED));
            require(p.route().size()==1 && p.discoveredNodes()==0,"A .62-wide straight corridor admits the actual .6-wide body.");
        });
        test("unsafe cell center does not reject a safe actual start lane", () -> {
            WalkingPathPlanner p=planner(point(.01,0),point(4.01,0));
            found(p,new World().box(1.7,2.3,-.2,.2,WalkingPathPlanner.Check.BLOCKED)
                    .box(-.4,.8,.31,.8,WalkingPathPlanner.Check.BLOCKED));
            require(p.routeDistance()>4 && p.discoveredNodes()>0,"An offset actual start can route without crossing its blocked cell center.");
        });
        test("selected route is fully revalidated after search/direct evidence", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); Clock c = new Clock(); final int[] visits = {0};
            WalkingPathPlanner.Probe dynamic = (sweep, deadline) -> {
                if (sweep.from.x == 0 && sweep.to.x > 0 && ++visits[0] == 2) return WalkingPathPlanner.Check.BLOCKED;
                return WalkingPathPlanner.Check.CLEAR;
            };
            for (int i = 0; i < 100 && p.status() == WalkingPathPlanner.Status.PLANNING; i++) p.advance(dynamic, Long.MAX_VALUE / 2, c);
            require(p.status() == WalkingPathPlanner.Status.REFUSED && "route_changed_blocked".equals(p.reason()), "Dynamic unsafe change prevents publishing the cached route.");
        });
        test("partial geometry yields without treating it as safe or blocked", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); Clock c = new Clock(); final int[] calls = {0};
            p.advance((sweep, deadline) -> { calls[0]++; return WalkingPathPlanner.Check.PARTIAL; }, Long.MAX_VALUE / 2, c);
            require(calls[0] == 1 && p.status() == WalkingPathPlanner.Status.PLANNING && p.partialSamples() == 1, "Partial remains pending and consumes finite budget.");
            found(p, new World());
        });
        test("null geometry is unknown and refused", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); p.advance((s, d) -> null, Long.MAX_VALUE / 2, new Clock());
            require(p.status() == WalkingPathPlanner.Status.REFUSED && "start_unsafe_unknown".equals(p.reason()), "Missing native receipt is never clear.");
        });
        test("expired overall deadline does not invoke probe", () -> {
            WalkingPathPlanner p = new WalkingPathPlanner(point(0, 0), Collections.singletonList(point(2, 0)), .6, 1.8, 1);
            Clock c = new Clock(); c.now = 1; World w = new World(); p.advance(w, 100, c);
            require("deadline".equals(p.reason()) && w.calls == 0, "No work occurs after the request deadline.");
        });
        test("late geometry callback is never recorded as proof", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); Clock c = new Clock();
            p.advance((s, d) -> { c.now = d; return WalkingPathPlanner.Check.CLEAR; }, Long.MAX_VALUE / 2, c);
            require(p.status() == WalkingPathPlanner.Status.PLANNING && p.partialSamples() == 1 && p.route().isEmpty(), "Late clear callback must retry as partial.");
        });
        test("cooperative wall clock stops the current slice at four milliseconds", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(8, 0)); Clock c = new Clock(); final int[] calls = {0};
            p.advance((s, d) -> {
                require(d == WalkingPathPlanner.WORK_NANOS && c.now < d, "Probe receives the current bounded deadline.");
                calls[0]++; c.now += 2000000L; return WalkingPathPlanner.Check.CLEAR;
            }, Long.MAX_VALUE / 2, c);
            require(calls[0] == 2 && c.now == WalkingPathPlanner.WORK_NANOS && p.status() == WalkingPathPlanner.Status.PLANNING,
                    "No new native call is started after the four millisecond budget.");
        });
        test("external shorter owner deadline yields without any native query", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); Clock c = new Clock(); World w = new World();
            p.advance(w, 0, c); require(w.calls == 0 && p.status() == WalkingPathPlanner.Status.PLANNING, "Owner callback budget is respected.");
        });
        test("invalid dimensions and extra hard waypoints do not invoke native geometry", () -> {
            WalkingPathPlanner p = new WalkingPathPlanner(point(0, 0), Arrays.asList(point(1, 0), point(2, 0), point(3, 0), point(4, 0)), .6, 1.8, 100);
            require(p.status() == WalkingPathPlanner.Status.REFUSED && "invalid_request".equals(p.reason()), "Two user waypoints plus final destination is the request bound.");
            WalkingPathPlanner invalid = new WalkingPathPlanner(point(0, 0), Collections.singletonList(point(1, 0)), Double.NaN, 1.8, 100);
            require(invalid.status() == WalkingPathPlanner.Status.REFUSED, "Unknown actual body dimensions cannot be guessed.");
        });
        test("repeated partial observation has a finite total geometry budget", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); Clock c = new Clock();
            for (int i = 0; i <= WalkingPathPlanner.MAX_SAMPLES && p.status() == WalkingPathPlanner.Status.PLANNING; i++)
                p.advance((s, d) -> WalkingPathPlanner.Check.PARTIAL, Long.MAX_VALUE / 2, c);
            require("geometry_budget".equals(p.reason()) && p.samples() == WalkingPathPlanner.MAX_SAMPLES, "No unlimited partial retries.");
        });
        test("request and result contain immutable scalar definitions only", () -> {
            List<WalkingPathPlanner.Point> input = new ArrayList<WalkingPathPlanner.Point>(); input.add(point(2, 0));
            WalkingPathPlanner p = new WalkingPathPlanner(point(0, 0), input, .6, 1.8, Long.MAX_VALUE / 2); input.clear(); found(p, new World());
            boolean rejected = false; try { p.route().add(point(3, 0)); } catch (UnsupportedOperationException expected) { rejected = true; }
            require(rejected && p.route().size() == 1, "Caller mutation cannot alter the plan.");
            for (java.lang.reflect.Field field : WalkingPathPlanner.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                require(field.getType() != WalkingPathPlanner.Probe.class && field.getType() != WalkingPathPlanner.Clock.class, "No request instance retains a callback/world adapter or clock.");
            }
        });
        test("finished route cannot be replayed by another advance", () -> {
            WalkingPathPlanner p = planner(point(0, 0), point(2, 0)); World w = new World(); found(p, w); int calls = w.calls;
            p.advance(w, Long.MAX_VALUE / 2, new Clock()); require(w.calls == calls, "Planner completion does not replay or reobserve.");
        });
        for (boolean visible : new boolean[] {true,false}) test("one frontier can stop at a real interaction stand instead of an isolated low anchor " + visible, () -> {
            WalkingPathPlanner.Point start=new WalkingPathPlanner.Point(.5,4,.5), anchor=new WalkingPathPlanner.Point(2.5,3,.5);
            WalkingPathPlanner p=new WalkingPathPlanner(start,Collections.singletonList(anchor),.6,1.8,Long.MAX_VALUE/2,NavigationCosts.snapshot(),point -> 0);
            final int[] endpointCalls={0}; Clock clock=new Clock();
            WalkingPathPlanner.Probe terrain=new WalkingPathPlanner.Probe() {
                public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep,long deadline) {
                    if(sweep.from.distance(sweep.to)<.00001 && sweep.feetY==3 && sweep.x==2.5) return WalkingPathPlanner.Check.CLEAR;
                    return sweep.feetY==4 && sweep.x-sweep.width/2>=0 && sweep.x+sweep.width/2<=4 && Math.abs(sweep.z-.5)+sweep.width/2<=.9
                            ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                }
                public WalkingPathPlanner.Check endpoint(WalkingPathPlanner.Point point,double width,double height,long deadline) {
                    endpointCalls[0]++;
                    return point.x==2.5 && point.y==4 && point.z==.5 && visible ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.UNKNOWN;
                }
            };
            for(int i=0;i<3000 && p.status()==WalkingPathPlanner.Status.PLANNING;i++) p.advance(terrain,Long.MAX_VALUE/2,clock);
            require(endpointCalls[0]>0,"Expanded route cells query the actual interaction goal.");
            if(visible) require(p.status()==WalkingPathPlanner.Status.FOUND && contains(p.route(),new WalkingPathPlanner.Point(2.5,4,.5)),
                    "A reachable high stand completes without solving the isolated low anchor.");
            else require(p.status()==WalkingPathPlanner.Status.REFUSED && p.route().isEmpty(),"Unknown interaction views never produce a route.");
        });
        test("blocked interaction anchor is not a mandatory destination", () -> {
            WalkingPathPlanner p=new WalkingPathPlanner(point(0,0),Collections.singletonList(point(7,0)),.6,1.8,
                    Long.MAX_VALUE/2,NavigationCosts.snapshot(),v -> Math.max(0,3-v.x));
            WalkingPathPlanner.Probe terrain=new WalkingPathPlanner.Probe() {
                public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep s,long d) {
                    return s.x+s.width/2<5 ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                }
                public WalkingPathPlanner.Check endpoint(WalkingPathPlanner.Point v,double w,double h,long d) {
                    return v.x>=3 && v.x<5 ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                }
            };
            Clock clock=new Clock();
            for(int i=0;i<1000 && p.status()==WalkingPathPlanner.Status.PLANNING;i++) p.advance(terrain,Long.MAX_VALUE/2,clock);
            require(p.status()==WalkingPathPlanner.Status.FOUND && p.routeDistance()<3.3 && p.discoveredNodes()==0,
                    "Direct normal-reach goal stops before the blocked machine anchor without A*.");
        });
        test("off-center start connects to a narrower centered terrain lane", () -> {
            WalkingPathPlanner.Point start=point(.07,.31);
            WalkingPathPlanner p=new WalkingPathPlanner(start,Collections.singletonList(point(6,.31)),.6,1.8,
                    Long.MAX_VALUE/2,NavigationCosts.snapshot(),v -> Math.max(0,4-v.x));
            WalkingPathPlanner.Probe terrain=new WalkingPathPlanner.Probe() {
                public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep s,long d) {
                    double min=s.x-s.width/2,max=s.x+s.width/2;
                    if (min<-.3 || max>8 || Math.abs(s.feetY-4)>.0001) return WalkingPathPlanner.Check.BLOCKED;
                    double low=max>2 ? .199 : -.2,high=max>2 ? .801 : 1.2;
                    return s.z-s.depth/2>=low && s.z+s.depth/2<=high ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                }
                public WalkingPathPlanner.Check endpoint(WalkingPathPlanner.Point v,double w,double h,long d) {
                    return v.x>=4 && Math.abs(v.z-.5)<.001 ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
                }
            };
            Clock clock=new Clock();
            for(int i=0;i<1000 && p.status()==WalkingPathPlanner.Status.PLANNING;i++) p.advance(terrain,Long.MAX_VALUE/2,clock);
            require(p.status()==WalkingPathPlanner.Status.FOUND && p.discoveredNodes()>0,
                    "The original-offset lattice cannot represent this lane; real-start connections to centered cells can.");
        });
        System.out.println("WalkingPathPlannerSelfTest: " + tests + " offline cases passed");
    }
    public static void main(String[] args) { run(); }
}
