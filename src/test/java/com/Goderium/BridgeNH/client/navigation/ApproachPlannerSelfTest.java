package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Immutable scalar/loaded-space fixtures and real cube ray geometry; no player or world mutations. */
public final class ApproachPlannerSelfTest {
    private static int checks;
    private static final Object WORLD = new Object(), SERVER = new Object();
    public static int run() throws Exception {
        checks = 0; cableSideRecheck(); requests(); candidates(); sharedTargets(); wrenchEndpointContract(); movementStrategyContract(); hoverPostureContract(); buildEnvelopeContract(); prospectivePlacementBody(); identity(); extendedMetadata(); ready(); normalReachContract(); clientPhaseClock(); unknownAndRefused(); budgets(); attackCandidateBudget(); sampledRayBudgets(); thinParts(); continuations(); staleFrames(); eyeObservations(); queues(); return checks;
    }
    private static void cableSideRecheck() {
        JsonObject request = sharedArgs(2); request.getAsJsonArray("targets").remove(1);
        JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(5));
        request.getAsJsonArray("targets").get(0).getAsJsonObject().add("allowedSides", sides);
        request.add("allowedSides", sides); request.addProperty("currentOnly", true);
        ApproachPlanner.Frame at = new ApproachPlanner.Frame(new ApproachPlanner.Request(request, 0), WORLD, SERVER, "session", "uuid",
                "{}", "", "{}", "", 1.468166094337136, 64, -1.501188538570304, 1.62, .6, 1.8, 5,
                body(1.468166094337136, 64, -1.501188538570304), 0, 0, 40, 3, false, false, true, null, "");
        Fixture server = cableSideFixture(at, "integrated_server"), client = cableSideFixture(at, "client");
        ApproachPlanner.Search nativeSearch = ApproachPlanner.search(client, at, flight(), () -> 0);
        check(nativeSearch.selected != null,
                "Composite inset geometry is reachable with the normal native view ray.");
        JsonObject goalArgs = new JsonObject(); goalArgs.addProperty("type", "interaction"); goalArgs.add("targets", request.get("targets"));
        InteractionNavigationGoal goal = new InteractionNavigationGoal(goalArgs); goal.capture(at, client);
        check(goal.proveActual(client, new PlayerNavigation.Point(at.x, at.feetY, at.z), 5, 41, Long.MAX_VALUE, true)
                == WalkingPathPlanner.Check.CLEAR, "Recorded cable-side pose has an achievable native east-face navigation proof.");
        ApproachPlanner.Search search = ApproachPlanner.search(server, at, flight(), () -> 0);
        JsonObject proof = ApproachPlanner.finish(client, at, search, 41, () -> 0);
        check("ready".equals(proof.get("status").getAsString()),
                "The same multipart side and actual pose remain reachable in the public final approach recheck.");
        check(proof.getAsJsonObject("selected").getAsJsonObject("attachment").get("side").getAsInt() == 5,
                "A north panel cannot substitute its front face for the requested east face.");
        Fixture occluded = cableSideFixture(at, "client"); occluded.noHit = true;
        check(ApproachPlanner.finish(occluded, at, search, 42, () -> 0).get("selected").isJsonNull(),
                "A navigation/server proof never bypasses changed client occlusion.");
    }
    private static Fixture cableSideFixture(ApproachPlanner.Frame frame, String source) {
        return new Fixture(frame, source) {
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return AxisAlignedBB.getBoundingBox(x+.3125,y+.3125,z+.3125,x+.6875,y+.6875,z+.6875); }
            public MovingObjectPosition trace(Vec3 from, Vec3 to) {
                check(Math.abs(from.distanceTo(to) - 5) < .001,
                        "Navigation and public side rechecks both trace the full native normal reach.");
                traces++; if (noHit) return null;
                MovingObjectPosition nearest = null; double distance = Double.POSITIVE_INFINITY;
                for (AxisAlignedBB box : new AxisAlignedBB[] {
                        AxisAlignedBB.getBoundingBox(.3125,64.3125,.3125,.6875,64.6875,.6875),
                        AxisAlignedBB.getBoundingBox(.125,64.125,0,.875,64.875,.125) }) {
                    MovingObjectPosition hit = box.calculateIntercept(from,to);
                    if (hit != null && from.distanceTo(hit.hitVec) < distance) {
                        distance = from.distanceTo(hit.hitVec); nearest = new MovingObjectPosition(0,64,0,hit.sideHit,hit.hitVec);
                    }
                }
                if (nearest != null) { from.xCoord=nearest.hitVec.xCoord; from.yCoord=nearest.hitVec.yCoord; from.zCoord=nearest.hitVec.zCoord; }
                return nearest;
            }
        };
    }
    private static void wrenchEndpointContract() {
        JsonObject request = args("use", "surround"); request.addProperty("requireFlying", false);
        request.addProperty("currentOnly", true); request.addProperty("useNormalReach", true);
        request.addProperty("maxDistance", 32); request.addProperty("movementStrategy", "walking");
        JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(0)); request.add("allowedSides", sides);
        JsonObject identity = new JsonObject(); identity.addProperty("registryName", "gregtech:gt.blockmachines"); request.add("targetIdentity", identity);
        ApproachPlanner.Frame ordinary = placedFrame(request, -.997371575, 62, 3.362447378, body(-.997371575,62,3.362447378));
        JsonObject reach = flight(); reach.addProperty("normalReach",4.5);
        check(ApproachPlanner.search(wrenchFixture(ordinary,"integrated_server"),ordinary,reach,()->0).selected != null,
                "A thin preceding IO panel leaves an ordinary edge ray visible at the recorded relative stance.");
        request.addProperty("wrenchFacing", true);
        ApproachPlanner.Frame blocked = placedFrame(request,ordinary.x,ordinary.feetY,ordinary.z,ordinary.actorBody);
        ApproachPlanner.Search refused = ApproachPlanner.search(wrenchFixture(blocked,"integrated_server"),blocked,reach,()->0);
        check(refused.selected == null && !refused.partial && refused.rays == 1,
                "The identical pose refuses an occluded wrench centre in one counted read rather than attempting a click.");
        ApproachPlanner.Frame near = placedFrame(request,-1.5,62,.5,body(-1.5,62,.5));
        ApproachPlanner.Search accepted = ApproachPlanner.search(wrenchFixture(near,"integrated_server"),near,reach,()->0);
        JsonObject proof = ApproachPlanner.finish(wrenchFixture(near,"client"),near,accepted,41,()->0);
        check("ready".equals(proof.get("status").getAsString()) && accepted.rays == 1,
                "An actually visible centre retains the existing complete dual-owner body and native ray proof.");
        JsonObject child = new com.google.gson.JsonParser().parse(request.toString()).getAsJsonObject();
        JsonArray targets = new JsonArray(); JsonObject target = new JsonObject();
        for(String key:new String[]{"x","y","z","allowedSides","targetIdentity","wrenchFacing"}) target.add(key,child.get(key));
        targets.add(target); child.add("targets",targets);
        check(new ApproachPlanner.Request(child,0).sharedTargets.get(0).wrenchFacing,
                "Shared interaction/navigation targets retain the exact wrench-centre constraint.");
        JsonObject goal = new JsonObject(); goal.addProperty("type","interaction"); goal.add("targets",targets);
        check(new InteractionNavigationGoal(goal).approachArgs("test",0,true).get("wrenchFacing").getAsBoolean(),
                "Walking interaction goals preserve wrench-centre intent through endpoint and final-actual checks.");
        CrosshairArrival arrival = new CrosshairArrival(targets,"test",0,"walking");
        check(!CrosshairArrival.matches(arrival.request.sharedTargets.get(0),new MovingObjectPosition(0,64,0,0,Vec3.createVectorHelper(.1,64,.5))),
                "An actual edge crosshair cannot stop a centre-constrained navigation.");
        for(String invalid:new String[]{"false","attack","part","side","registry"}) {
            JsonObject bad = new com.google.gson.JsonParser().parse(request.toString()).getAsJsonObject();
            if("false".equals(invalid)) bad.addProperty("wrenchFacing",false);
            if("attack".equals(invalid)) bad.addProperty("intent","attack");
            if("side".equals(invalid)) bad.remove("allowedSides");
            if("registry".equals(invalid)) bad.getAsJsonObject("targetIdentity").addProperty("registryName","fixture:other");
            if("part".equals(invalid)) { JsonObject part=new JsonObject(); part.addProperty("registryName","ae:part"); part.addProperty("metadata",220); part.addProperty("side",1); bad.getAsJsonObject("targetIdentity").add("part",part); }
            expect("invalid_args",()->new ApproachPlanner.Request(bad,0));
        }
    }
    private static Fixture wrenchFixture(ApproachPlanner.Frame frame,String source) {
        return new Fixture(frame,source) {
            { registryOverride="gregtech:gt.blockmachines"; }
            public AxisAlignedBB selectedBounds(int x,int y,int z) { return AxisAlignedBB.getBoundingBox(x,y,z,x+1,y+1,z+1); }
            public MovingObjectPosition trace(Vec3 from,Vec3 to) {
                traces++; MovingObjectPosition nearest=null; double distance=Double.POSITIVE_INFINITY;
                for(int offset=0;offset<3;offset++) {
                    AxisAlignedBB box=offset==0?AxisAlignedBB.getBoundingBox(0,64,0,1,65,1)
                        :AxisAlignedBB.getBoundingBox(.1875,63.75,offset,.8125,64,offset+1);
                    MovingObjectPosition hit=box.calculateIntercept(from,to);
                    if(hit!=null&&from.distanceTo(hit.hitVec)<distance) { distance=from.distanceTo(hit.hitVec);nearest=new MovingObjectPosition(0,offset==0?64:63,offset,hit.sideHit,hit.hitVec); }
                }
                if(nearest!=null) { from.xCoord=nearest.hitVec.xCoord;from.yCoord=nearest.hitVec.yCoord;from.zCoord=nearest.hitVec.zCoord; }
                return nearest;
            }
        };
    }
    private static JsonObject sharedArgs(int secondX) {
        JsonObject request = args("use", "surround"); request.addProperty("requireFlying", false);
        request.addProperty("useNormalReach", true); request.addProperty("maxDistance", 32); request.addProperty("movementStrategy", "walking");
        JsonArray targets = new JsonArray();
        for (int x : new int[] {0, secondX}) { JsonObject target = new JsonObject(); target.addProperty("x", x); target.addProperty("y", 64); target.addProperty("z", 0); targets.add(target); }
        request.add("targets", targets); return request;
    }
    private static void sharedTargets() {
        interactionGoals();
        flightHeightPreference();
        distantFlightEndpoint();
        JsonObject request = sharedArgs(2);
        ApproachPlanner.Frame actual = placedFrame(request, .23, 63, -1.31, body(.23, 63, -1.31));
        Fixture server = new Fixture(actual, "integrated_server"), client = new Fixture(actual, "client");
        ApproachPlanner.Search search = ApproachPlanner.search(server, actual, flight(), () -> 0);
        JsonObject result = ApproachPlanner.finish(client, actual, search, 41, () -> 0);
        check("ready".equals(result.get("status").getAsString()) && !result.getAsJsonObject("selected").get("moveRequired").getAsBoolean(), "shared normal reach retains exact already reachable continuous pose");
        JsonArray views = result.getAsJsonObject("selected").getAsJsonArray("targetViews");
        check(views.size() == 2 && views.get(1).getAsJsonObject().getAsJsonObject("clientRay").get("worldTick").getAsLong() == 41, "each shared view carries the actual final client tick rather than capture tick");
        for (com.google.gson.JsonElement entry : views) {
            JsonObject view = entry.getAsJsonObject();
            check(view.getAsJsonObject("clientRay").get("verified").getAsBoolean() && view.getAsJsonObject("serverRay").get("verified").getAsBoolean()
                    && Double.isFinite(view.getAsJsonObject("aim").get("yaw").getAsDouble()) && Double.isFinite(view.getAsJsonObject("aim").get("pitch").getAsDouble()), "every target has native dual face proof and finite aim");
        }
        check(actual.request.total() == 166 && actual.request.maxPages() == 16 && result.getAsJsonObject("coverage").get("maxCandidateAttempts").getAsInt() == 181, "shared candidate heights and continuation bounds agree");
        ApproachPlanner.Candidate center = ApproachPlanner.candidate(actual, 1);
        check(center != null && Math.hypot(center.feetX-1.5,center.feetZ-.5)>.75 && center.feetY == actual.feetY,
                "shared nearest travel-side reach stand is offset from the target midpoint");
        check(ApproachPlanner.candidate(actual,2).feetY == actual.feetY-1, "shared endpoint probes a lower supported walking floor");
        JsonObject elevatedArgs = sharedArgs(2); elevatedArgs.addProperty("y", 70);
        for (com.google.gson.JsonElement target : elevatedArgs.getAsJsonArray("targets")) target.getAsJsonObject().addProperty("y", 70);
        ApproachPlanner.Frame elevated = placedFrame(elevatedArgs, -6.5, 65, .5, body(-6.5, 65, .5));
        int oblique = 0;
        for (int index = 1; index < elevated.request.total(); index++) {
            ApproachPlanner.Candidate stand = ApproachPlanner.candidate(elevated, index); if (stand == null) continue;
            oblique++;
            for (ApproachPlanner.Request target : elevated.request.sharedTargets) if (target.y+.5>stand.feetY+elevated.eyeOffset)
                check(Math.hypot(stand.feetX-Math.max(target.x,Math.min(target.x+1,stand.feetX)),
                        stand.feetZ-Math.max(target.z,Math.min(target.z+1,stand.feetZ)))>=.75+PlayerNavigation.TOLERANCE,
                        "Every relocated shared stand preserves oblique clearance to each target's footprint, including arrival drift.");
        }
        check(oblique>0, "Raised shared targets retain oblique reachable candidate stands.");
        check(ApproachPlanner.candidate(elevated,0).feetX==elevated.x,
                "An actual already-reachable pose is still retained before relocation preferences.");
        JsonObject edgeArgs = sharedArgs(1);
        // Keep the first cube out of the second cube's actual approach ray.
        edgeArgs.addProperty("z", -2); edgeArgs.getAsJsonArray("targets").get(0).getAsJsonObject().addProperty("z", -2);
        ApproachPlanner.Frame edge = placedFrame(edgeArgs, -3.49, 63, .98, body(-3.49, 63, .98));
        Fixture edgeServer = new Fixture(edge, "integrated_server"), edgeClient = new Fixture(edge, "client");
        JsonObject survivalReach = flight(); survivalReach.addProperty("normalReach",4.5);
        JsonObject edgeResult = ApproachPlanner.finish(edgeClient,edge,ApproachPlanner.search(edgeServer,edge,survivalReach,()->0),41,()->0);
        check(edge.eyeOffset+edge.feetY==64.62 && Vec3.createVectorHelper(edge.x,64.62,edge.z).distanceTo(NativePlacementAdapter.facePoint(1,64,0,4))>4.5,
                "The test actually places the second face centre outside survival reach.");
        check("ready".equals(edgeResult.get("status").getAsString()) && !edgeResult.getAsJsonObject("selected").get("moveRequired").getAsBoolean(),
                "A reachable off-centre face keeps the actual pose even when its centre is beyond survival reach.");
        check(NativePlacementAdapter.distanceToFace(Vec3.createVectorHelper(-3.45,64.62,.98),1,64,0,4) < 4.5,
                "Whole face eligibility uses the closest point rather than the centre.");
        for (int side=0;side<6;side++) {
            net.minecraftforge.common.util.ForgeDirection normal=net.minecraftforge.common.util.ForgeDirection.getOrientation(side);
            Vec3 eye=Vec3.createVectorHelper(normal.offsetX==0?.98:normal.offsetX>0?5.49:-4.49,
                    normal.offsetY==0?64.98:normal.offsetY>0?69.49:59.51,
                    normal.offsetZ==0?.98:normal.offsetZ>0?5.49:-4.49);
            Fixture faceFixture=new Fixture(frame(args("use", "surround")),"client");
            MovingObjectPosition hit=NativePlacementAdapter.visibleFace(faceFixture,eye,4.5,0,64,0,side);
            check(eye.distanceTo(NativePlacementAdapter.facePoint(0,64,0,side))>4.5 && hit!=null && hit.sideHit==side
                    && eye.distanceTo(hit.hitVec)<=4.50001 && faceFixture.traces==1,
                    "All six faces select a reachable off-centre point in one native ray when the centre is beyond reach.");
            Vec3 obliqueEye=Vec3.createVectorHelper(normal.offsetX==0?-3.38:normal.offsetX>0?3.9:-2.9,
                    normal.offsetY==0?(normal.offsetX==0?64.99:60.62):normal.offsetY>0?67.9:61.1,
                    normal.offsetZ==0?.99:normal.offsetZ>0?3.9:-2.9);
            Fixture obliqueFixture=new Fixture(frame(args("use", "surround")),"client"); obliqueFixture.nativeAdvance=true;
            MovingObjectPosition obliqueHit=NativePlacementAdapter.visibleFace(obliqueFixture,obliqueEye,4.5,0,64,0,side);
            check(obliqueEye.distanceTo(NativePlacementAdapter.facePoint(0,64,0,side))>4.5 && obliqueHit!=null && obliqueHit.sideHit==side
                    && obliqueEye.distanceTo(obliqueHit.hitVec)<=4.50001 && obliqueFixture.traces==1,
                    "Nearest oblique edge enters the requested face rather than its neighbour, without relocation.");
        }
        JsonObject missing = sharedArgs(2); missing.getAsJsonArray("targets").get(1).getAsJsonObject().remove("x");
        expect("invalid_args", () -> new ApproachPlanner.Request(missing, 0));
        expect("invalid_args", () -> new ApproachPlanner.Request(sharedArgs(0), 0));
        JsonObject wrong = sharedArgs(2); wrong.addProperty("x", 1); expect("invalid_args", () -> new ApproachPlanner.Request(wrong, 0));
        Fixture blocked = new Fixture(actual, "integrated_server"); blocked.noHit = true;
        ApproachPlanner.Search absent = ApproachPlanner.search(blocked, actual, flight(), () -> 0);
        JsonObject refusal = ApproachPlanner.finish(client, actual, absent, 41, () -> 0);
        check(!"ready".equals(refusal.get("status").getAsString()) && refusal.get("selected").isJsonNull(),
                "Occluded shared targets never infer ready views, including bounded ray-budget refusal");
        Fixture unsafe = new Fixture(actual, "integrated_server"); unsafe.canStand = false;
        check(ApproachPlanner.search(unsafe, actual, flight(), () -> 0).selected == null && unsafe.traces == 0, "unsafe walking support refuses before native rays");
        ApproachPlanner.Frame far = placedFrame(sharedArgs(24), .23, 63, -1.31, body(.23, 63, -1.31)); Fixture farServer = new Fixture(far, "integrated_server");
        ApproachPlanner.Search farSearch = ApproachPlanner.search(farServer, far, flight(), () -> 0);
        check(farSearch.selected == null && farSearch.nextOffset == far.request.total() && farServer.traces == 0, "disjoint reach volumes reject cheaply without repeated native rays");
        Fixture changed = new Fixture(actual, "client"); changed.rawMetadata = 1;
        expect("target_unverified", () -> ApproachPlanner.finish(changed, actual, search, 41, () -> 0));
    }
    private static void distantFlightEndpoint() {
        for (String strategy : new String[] {"walking", "developer_teleport", "ability_flight"}) {
            JsonObject request = sharedArgs(2); request.getAsJsonArray("targets").remove(1);
            request.addProperty("movementStrategy", strategy);
            request.addProperty("requireFlying", "ability_flight".equals(strategy));
            ApproachPlanner.Frame frame = placedFrame(request, -50, 63, .5, body(-50, 63, .5));
            Fixture server = new Fixture(frame, "integrated_server"), client = new Fixture(frame, "client");
            ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0);
            JsonObject result = ApproachPlanner.finish(client, frame, search, 41, () -> 0);
            check(("ready".equals(result.get("status").getAsString())) == !"developer_teleport".equals(strategy),
                    "Segmented walking and flight can observe a reachable endpoint beyond the nearby route bound.");
            if (search.selected != null) {
                check(search.selected.feetX > -5 && result.get("readOnly").getAsBoolean()
                        && !result.get("submitted").getAsBoolean(), "Distant endpoint retains normal reach and is never movement proof.");
                Fixture blocked = new Fixture(frame, "integrated_server"); blocked.noHit = true;
                check(ApproachPlanner.search(blocked, frame, flight(), () -> 0).selected == null,
                        "Longer endpoint travel never bypasses native occlusion.");
            }
            ApproachPlanner.Frame outside = placedFrame(request, -300, 63, .5, body(-300, 63, .5));
            check(ApproachPlanner.search(new Fixture(outside, "integrated_server"), outside, flight(), () -> 0).selected == null,
                    "Endpoint selection retains the existing outer navigation bound.");
        }
    }
    private static void flightHeightPreference() {
        JsonObject request = sharedArgs(2); request.getAsJsonArray("targets").remove(1);
        request.addProperty("movementStrategy", "ability_flight"); request.addProperty("requireFlying", true);
        ApproachPlanner.Frame frame = placedFrame(request, -10, 68, -5, body(-10, 68, -5));
        Fixture server = new Fixture(frame, "integrated_server") {
            public JsonObject space(int x, int y, int z, double width, double height, long deadline) {
                JsonObject result = super.space(x, y, z, width, height, deadline);
                result.addProperty("occupiable", y == 65 || y == 62); return result;
            }
        };
        ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0);
        check(search.selected != null && search.selected.feetY == 65,
                "Flight prefers the reachable upper stand before a lower cavity without enlarging search budgets.");
        check(frame.request.total() == 166 && frame.request.maxRays() == 2048,
                "Height ordering preserves the bounded candidate and ray contract.");
    }
    private static JsonObject args(String intent, String pattern) {
        JsonObject args = new JsonObject(); args.addProperty("intent", intent); args.addProperty("pattern", pattern);
        args.addProperty("x", 0); args.addProperty("y", 64); args.addProperty("z", 0); args.addProperty("expectedWorld", "fixture");
        if ("place_block".equals(intent)) args.addProperty("hotbarSlot", 3);
        return args;
    }
    private static void interactionGoals() {
        JsonObject request = sharedArgs(2), args = new JsonObject(); args.addProperty("type", "interaction"); args.add("targets",request.get("targets"));
        InteractionNavigationGoal goal = (InteractionNavigationGoal) NavigationGoal.parse(args);
        ApproachPlanner.Frame actual = new ApproachPlanner.Frame(new ApproachPlanner.Request(request,0), WORLD, SERVER, "session", "uuid",
                "{}", "", "{}", "", .23,63,-1.31,1.62,.6,1.8,5,body(.23,63,-1.31),0,0,40,3,false,false,true,null,"");
        Fixture client = new GoalFixture(actual,"client"); goal.capture(actual,client);
        PlayerNavigation.Point start = new PlayerNavigation.Point(.23,63,-1.31);
        check(goal.candidates(start).size() > 100, "Interaction candidates include exact and centered endpoint alternatives with native height limits.");
        check(goal.prove(client,start,5,41,Long.MAX_VALUE,true) == WalkingPathPlanner.Check.CLEAR && goal.contains(start)
                && goal.aim(start) != null, "The unchanged real pose proves all targets and their native face aim.");
        Fixture server = new GoalFixture(actual,"integrated_server"); server.noHit = true;
        check(goal.prove(server,start,5,41,Long.MAX_VALUE,false) != WalkingPathPlanner.Check.CLEAR,
                "A clear client stand does not authorize an occluded authoritative target.");
        server.noHit = false; server.rawMetadata = 1;
        expect("target_unverified", () -> goal.prove(server,start,5,41,Long.MAX_VALUE,false));
        check(goal.prove(client,start,5,41,0,true) == WalkingPathPlanner.Check.PARTIAL, "An expired endpoint deadline cannot produce a proof.");
        server.rawMetadata = 0;
        PlayerNavigation.Point shifted = new PlayerNavigation.Point(.63,63,-1.31);
        check(goal.proveActual(client,shifted,5,42,Long.MAX_VALUE,true) == WalkingPathPlanner.Check.CLEAR
                && goal.actualContains(shifted,true) && goal.contains(shifted) && goal.aim(shifted) != null,
                "A safe reachable actual pose accepts .4-block drift instead of requiring the old planned endpoint.");
        check(!goal.actualContains(start,true) && !goal.actualContains(shifted,false),
                "Actual proof is bound to its exact current pose and native owner, not a widened stale-position tolerance.");
        JsonObject exclusion = new JsonObject();
        exclusion.addProperty("minX", 0); exclusion.addProperty("maxX", 0);
        exclusion.addProperty("minY", 64); exclusion.addProperty("maxY", 64);
        exclusion.addProperty("minZ", -2); exclusion.addProperty("maxZ", -2);
        args.add("excludedBuildEnvelope", exclusion);
        InteractionNavigationGoal placementGoal = (InteractionNavigationGoal) NavigationGoal.parse(args);
        placementGoal.capture(actual, client);
        check(placementGoal.proveActual(client,start,5,42,Long.MAX_VALUE,true) == WalkingPathPlanner.Check.BLOCKED
                && !placementGoal.actualContains(start,true),
                "A reachable attachment cannot accept a stand whose head overlaps the future destination cube.");
        check(placementGoal.proveActual(client,new PlayerNavigation.Point(1.5,63,-1.31),5,42,Long.MAX_VALUE,true) == WalkingPathPlanner.Check.CLEAR,
                "The same attachment remains usable from an unobstructed outside stand.");
        check(exclusion.equals(placementGoal.approachArgs("fixture",0,true).get("excludedBuildEnvelope")),
                "Actual endpoint rechecks preserve the prospective envelope.");
        JsonObject navigation = new JsonObject(); navigation.add("goal", args);
        navigation.addProperty("expectedWorld", "fixture"); navigation.addProperty("expectedSessionId", "session");
        navigation.addProperty("dimension", 0); navigation.addProperty("strategy", "walking");
        check(new PlayerNavigation.Request(navigation).crosshair == null,
                "Seeing the attachment from inside its future cube must not stop the escape route.");
        args.remove("excludedBuildEnvelope");
        check(new PlayerNavigation.Request(navigation).crosshair != null,
                "Ordinary interaction goals retain native crosshair early stopping.");
        check(goal.proveActual(server,shifted,5,42,Long.MAX_VALUE,false) == WalkingPathPlanner.Check.CLEAR
                && goal.actualContains(shifted,false), "Both owners independently prove the actual off-centre interaction pose.");
        client.canStand = false;
        check(goal.proveActual(client,shifted,5,43,Long.MAX_VALUE,true) != WalkingPathPlanner.Check.CLEAR
                && !goal.actualContains(shifted,true), "Lost support clears the actual proof despite remaining target reach.");
        client.canStand = true;
        goal.proveActual(client,shifted,5,44,Long.MAX_VALUE,true);
        check(goal.proveActual(client,shifted,5,44,0,true) == WalkingPathPlanner.Check.PARTIAL
                && !goal.actualContains(shifted,true), "An expired new observation cannot retain an old actual proof.");
        JsonObject restricted = sharedArgs(2); JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(3));
        restricted.getAsJsonArray("targets").get(0).getAsJsonObject().add("allowedSides",sides);
        args.add("targets",restricted.get("targets")); InteractionNavigationGoal back = (InteractionNavigationGoal)NavigationGoal.parse(args);
        back.capture(actual,client);
        check(back.prove(client,start,5,41,Long.MAX_VALUE,true) != WalkingPathPlanner.Check.CLEAR, "An allowed opposite face cannot be replaced by a visible forbidden face.");
        JsonObject insetRequest=sharedArgs(2); insetRequest.getAsJsonArray("targets").remove(1);
        JsonObject insetGoalArgs=new JsonObject(); insetGoalArgs.addProperty("type","interaction");
        insetGoalArgs.add("targets",insetRequest.get("targets"));
        InteractionNavigationGoal insetGoal=(InteractionNavigationGoal)NavigationGoal.parse(insetGoalArgs);
        ApproachPlanner.Frame far=new ApproachPlanner.Frame(new ApproachPlanner.Request(insetRequest,0),WORLD,SERVER,"session","uuid",
                "{}","","{}","",.5,63,-5,1.62,.6,1.8,4.5,body(.5,63,-5),0,0,40,3,false,false,true,null,"");
        Fixture inset=new Fixture(far,"client"); inset.nativeShape=AxisAlignedBB.getBoundingBox(.125,64.25,.875,.875,64.75,1);
        insetGoal.capture(far,inset);
        PlayerNavigation.Point boundary=new PlayerNavigation.Point(.5,63,-3.45);
        check(insetGoal.reachDistance(boundary)==0 && insetGoal.proveActual(inset,boundary,4.5,41,Long.MAX_VALUE,true)==WalkingPathPlanner.Check.CLEAR,
                "The inset surface fixture is actually in normal survival reach, including its exact native ray.");
        check(insetGoal.prove(inset,boundary,4.5,41,Long.MAX_VALUE,true)==WalkingPathPlanner.Check.BLOCKED,
                "A planned relocated endpoint reserves braking distance from the native surface, not merely the nearer block boundary.");
        PlayerNavigation.Point insetSafe=new PlayerNavigation.Point(.5,63,-3.1);
        check(insetGoal.prove(inset,insetSafe,4.5,41,Long.MAX_VALUE,true)==WalkingPathPlanner.Check.CLEAR
                && insetGoal.proveActual(inset,new PlayerNavigation.Point(.5,63,-3.28),4.5,42,Long.MAX_VALUE,true)==WalkingPathPlanner.Check.CLEAR,
                "An inset endpoint with real surface margin remains clickable after the allowed 0.18-block braking drift.");
    }
    private static ApproachPlanner.Frame frame(JsonObject args) {
        if ("place_block".equals(args.get("intent").getAsString()) && !args.has("allowedSides")) {
            JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(2)); args.add("allowedSides", sides);
        }
        return new ApproachPlanner.Frame(new ApproachPlanner.Request(args, 0), WORLD, SERVER, "session", "uuid",
                "{\"registryName\":\"fixture:held\"}", "held-tag", "{\"registryName\":\"fixture:source\"}", "source-tag",
                .5, 63, -1.5, 1.62, .6, 1.8, 5, body(.5, 63, -1.5), 0, 0, 40, 7, true, false, true, null, "");
    }
    private static void clientPhaseClock() {
        ApproachPlanner.Frame captured = frame(args("use", "surround"));
        Fixture server = new Fixture(captured, "integrated_server"), client = new Fixture(captured, "client");
        ApproachPlanner.Search search = ApproachPlanner.search(server, captured, flight(), () -> 0);
        JsonObject data = ApproachPlanner.finish(client, captured, search, 39, () -> 0), current = data.getAsJsonObject("current");
        ApproachPlanner.clientPhaseClock(data, 100, 101, 101);
        check(current.get("captureWorldTick").getAsLong() == 40 && current.get("recheckWorldTick").getAsLong() == 39
                && data.get("clientWorldTick").getAsLong() == 39, "client world rollback retains the actual observation values");
        check(current.get("clientPhaseClockVersion").getAsInt() == 1
                && "bridge_client_tick".equals(current.get("clientPhaseClockSource").getAsString())
                && current.get("captureClientTick").getAsLong() == 100 && current.get("recheckClientTick").getAsLong() == 101,
                "only the native owning-client counter binds capture, recheck and AFTER");
        ApproachPlanner.clientPhaseClock(data, 101, 101, 101);
        check(current.get("captureClientTick").getAsLong() == 101, "sequential owning-queue callbacks may share one native client tick");
        for (long[] ticks : new long[][] {{-1, 101, 101}, {100, 99, 99}, {100, 101, 100}, {100, 101, 102}, {0, -1, -1}})
            expect("stale_world", () -> ApproachPlanner.clientPhaseClock(data, ticks[0], ticks[1], ticks[2]));
    }
    private static void requests() {
        for (String intent : new String[] {"place_block", "use", "attack"}) {
            ApproachPlanner.Request request = new ApproachPlanner.Request(args(intent, "surround"), 0);
            check(request.total() == 33 && request.maxDistance == 3 && request.requireFlying && request.sides.length == 6,
                    "supported read-only intents retain fixed defaults");
        }
        for (String key : new String[] {"intent", "pattern", "expectedWorld", "x", "y", "z", "requireFlying", "maxDistance", "offset", "dimension", "allowedSides", "targetIdentity", "useNormalReach"}) {
            JsonObject invalid = args("use", "surround"); invalid.add(key, JsonNull.INSTANCE);
            expect("invalid_args", () -> new ApproachPlanner.Request(invalid, 0));
        }
        JsonObject extra = args("use", "surround"); extra.addProperty("teleport", true); expect("invalid_args", () -> new ApproachPlanner.Request(extra, 0));
        JsonObject invalid = args("use", "surround"); invalid.addProperty("hotbarSlot", 3); expect("invalid_args", () -> new ApproachPlanner.Request(invalid, 0));
        JsonObject dimension = args("use", "surround"); dimension.addProperty("dimension", 1); expect("stale_world", () -> new ApproachPlanner.Request(dimension, 0));
        for (String intent : new String[] {"replace", "place", ""}) {
            JsonObject bad = args(intent, "surround"); expect("invalid_args", () -> new ApproachPlanner.Request(bad, 0));
        }
        for (double distance : new double[] {0, 3.0001, Double.NaN}) {
            JsonObject bad = args("use", "surround"); bad.addProperty("maxDistance", distance); expect("invalid_args", () -> new ApproachPlanner.Request(bad, 0));
        }
        for (int[] sides : new int[][] {{}, {2, 2}, {-1}, {6}, {0, 1, 2, 3, 4, 5, 0}}) {
            JsonObject bad = args("use", "surround"); JsonArray array = new JsonArray();
            for (int side : sides) array.add(new com.google.gson.JsonPrimitive(side)); bad.add("allowedSides", array);
            expect("invalid_args", () -> new ApproachPlanner.Request(bad, 0));
        }
        JsonObject fractional = args("use", "surround"); fractional.addProperty("offset", .5); expect("invalid_args", () -> new ApproachPlanner.Request(fractional, 0));
        JsonObject missing = args("place_block", "surround"); missing.remove("hotbarSlot"); expect("invalid_args", () -> new ApproachPlanner.Request(missing, 0));
    }
    private static void hoverPostureContract() {
        for (String strategy : new String[] {null, "walking", "developer_teleport"}) {
            JsonObject invalid = args("use", "surround"); invalid.addProperty("operationPosture", "flying");
            if (strategy != null) invalid.addProperty("movementStrategy", strategy);
            expect("invalid_args", () -> new ApproachPlanner.Request(invalid, 0));
        }
        for (String pattern : new String[] {"surround", "cardinal"}) {
            JsonObject request = args("use", pattern); request.addProperty("movementStrategy", "ability_flight"); request.addProperty("operationPosture", "flying");
            ApproachPlanner.Frame actual = placedFrame(request, 10.5, 63.25865824594294, -1.5, body(10.5, 63.25865824594294, -1.5));
            check(actual.request.total() == ("surround".equals(pattern) ? 9 : 5), "hover search has only actual-current and bounded XZ slots");
            for (int index = 0; index < actual.request.total(); index++)
                check(ApproachPlanner.candidate(actual, index).feetY == actual.feetY, "every hover endpoint uses actual continuous feetY without invented ascent");
            JsonObject authoritative = flight(); authoritative.addProperty("creative", false); authoritative.addProperty("allowFlying", true); authoritative.addProperty("onGround", false);
            Fixture server = new Fixture(actual, "integrated_server"), client = new Fixture(actual, "client"); server.canStand = client.canStand = false;
            ApproachPlanner.Search search = ApproachPlanner.search(server, actual, authoritative, () -> 0);
            JsonObject result = ApproachPlanner.finish(client, actual, search, 41, () -> 0);
            check(search.selected != null && search.selected.feetY == actual.feetY && "ready".equals(result.get("status").getAsString()),
                    "non-grounded hover still needs real body/ray/reach proof on both owners");
            check(server.continuousSpaces == server.spaces && client.continuousSpaces == 1
                    && !result.getAsJsonObject("selected").getAsJsonObject("serverBody").get("canStand").getAsBoolean()
                    && "actual_current_y_hover".equals(result.get("candidateMode").getAsString()) && result.get("operationPostureVersion").getAsInt() == 1,
                    "explicit version binds continuous body proofs and never certifies landing support");
            search.selected = new ApproachPlanner.Candidate(search.selected.feetX, actual.feetY - .00001, search.selected.feetZ);
            int calls = client.spaces; expect("strategy_incompatible", () -> ApproachPlanner.finish(client, actual, search, 41, () -> 0));
            check(client.spaces == calls, "forged epsilon descent is refused before selected client body/ray work");
            for (String missing : new String[] {"creative", "allowFlying", "flying", "onGround"}) {
                JsonObject bad = new com.google.gson.JsonParser().parse(authoritative.toString()).getAsJsonObject(); bad.addProperty(missing, "creative".equals(missing) || "onGround".equals(missing));
                expect("invalid_state", () -> ApproachPlanner.search(server, actual, bad, () -> 0));
            }
            JsonObject noFlight = new com.google.gson.JsonParser().parse(request.toString()).getAsJsonObject(); noFlight.addProperty("requireFlying", false);
            expect("invalid_args", () -> new ApproachPlanner.Request(noFlight, 0));
            JsonObject invalidOffset = new com.google.gson.JsonParser().parse(request.toString()).getAsJsonObject(); invalidOffset.addProperty("offset", actual.request.total());
            expect("invalid_args", () -> new ApproachPlanner.Request(invalidOffset, 0));
        }
    }
    private static void movementStrategyContract() {
        groundedTeleportSupport();
        for (com.google.gson.JsonElement value : new com.google.gson.JsonElement[] {JsonNull.INSTANCE,
                new com.google.gson.JsonPrimitive(true), new com.google.gson.JsonPrimitive(1),
                new com.google.gson.JsonPrimitive("walking_step"), new com.google.gson.JsonPrimitive("automatic")}) {
            JsonObject invalid = args("use", "surround"); invalid.add("movementStrategy", value);
            expect("invalid_args", () -> new ApproachPlanner.Request(invalid, 0));
        }
        for (String strategy : new String[] {null, "walking", "ability_flight", "developer_teleport"}) {
            JsonObject args = args("use", "surround"); args.addProperty("y", 65); args.addProperty("requireFlying", false);
            if (strategy != null) args.addProperty("movementStrategy", strategy);
            ApproachPlanner.Frame actual = placedFrame(args, 10.5, 63, -1.5, body(10.5, 63, -1.5));
            Fixture server = new Fixture(actual, "integrated_server"), client = new Fixture(actual, "client");
            ApproachPlanner.Search search = ApproachPlanner.search(server, actual, flight(), () -> 0);
            JsonObject result = ApproachPlanner.finish(client, actual, search, 41, () -> 0);
            boolean walking = "walking".equals(strategy);
            check(search.selected != null && search.selected.feetY == (walking ? 63 : 64),
                    "only explicit walking skips an earlier high supported endpoint for its later actual-height endpoint");
            check("ready".equals(result.get("status").getAsString()) && search.strategyIncompatible == (walking ? 16 : 0)
                    && server.spaces == 2 && server.traces > 0 && client.spaces == 1,
                    "height filtering skips body and ray callbacks but selected dual-owner endpoint proofs remain");
            check(result.has("movementStrategy") == (strategy != null)
                    && result.getAsJsonObject("coverage").has("strategyIncompatibleCandidates") == (strategy != null),
                    "unconstrained requests retain their original receipt shape");
            if (strategy != null) check(result.get("movementStrategy").getAsString().equals(strategy)
                    && result.get("movementStrategyVersion").getAsInt() == 1
                    && "endpoint_compatibility_only".equals(result.get("movementStrategyScope").getAsString()),
                    "strategy receipts echo typed version and do not claim a route or arrival");
        }
        JsonObject topArgs = args("place_block", "surround"); topArgs.addProperty("y", 65);
        topArgs.addProperty("requireFlying", false); topArgs.addProperty("movementStrategy", "walking");
        JsonArray topSide = new JsonArray(); topSide.add(new com.google.gson.JsonPrimitive(1)); topArgs.add("allowedSides", topSide);
        ApproachPlanner.Frame below = placedFrame(topArgs, 10.5, 63, -1.5, body(10.5, 63, -1.5));
        Fixture topServer = new Fixture(below, "integrated_server");
        ApproachPlanner.Search topSearch = ApproachPlanner.search(topServer, below, flight(), () -> 0);
        JsonObject topResult = ApproachPlanner.finish(new Fixture(below, "client"), below, topSearch, 41, () -> 0);
        check(topSearch.selected == null && topSearch.unknown == 0 && topSearch.candidates == 33
                && topSearch.strategyIncompatible == 24 && topSearch.rays == 0
                && topResult.get("complete").getAsBoolean() && "no_candidate".equals(topResult.get("status").getAsString()),
                "flat-height filtering cannot make an above-eye top face reachable or infer an ascent");
        JsonObject farArgs = args("use", "surround"); farArgs.addProperty("y", 66);
        farArgs.addProperty("movementStrategy", "walking"); farArgs.addProperty("requireFlying", false);
        ApproachPlanner.Frame far = placedFrame(farArgs, 10.5, 63, -1.5, body(10.5, 63, -1.5));
        Fixture unknown = new Fixture(far, "integrated_server"); unknown.unknownFirst = true;
        ApproachPlanner.Search unavailable = ApproachPlanner.search(unknown, far, flight(), () -> 0);
        JsonObject uncertain = ApproachPlanner.finish(new Fixture(far, "client"), far, unavailable, 41, () -> 0);
        check(unavailable.strategyIncompatible == 32 && unavailable.unknown == 1 && unknown.spaces == 1
                && "unknown".equals(uncertain.get("status").getAsString()),
                "height rejects are known endpoint incompatibilities and cannot erase unknown compatible geometry");
        JsonObject currentArgs = args("use", "surround"); currentArgs.addProperty("currentOnly", true);
        currentArgs.addProperty("movementStrategy", "walking"); currentArgs.addProperty("requireFlying", false);
        ApproachPlanner.Frame continuous = placedFrame(currentArgs, .63, 63.25, -1.37, body(.63, 63.25, -1.37));
        ApproachPlanner.Search current = ApproachPlanner.search(new Fixture(continuous, "integrated_server"), continuous, flight(), () -> 0);
        check(current.selected != null && current.selected.feetY == 63.25 && current.strategyIncompatible == 0,
                "actual-current continuous feet are not rounded or substituted by the flat endpoint filter");
        for (double delta : new double[] {0, .000099, .000101, -.000101}) {
            check(continuous.request.compatible(continuous, new ApproachPlanner.Candidate(.63, 63.25 + delta, -1.37))
                    == (Math.abs(delta) <= .0001), "walking endpoint uses the existing native flat threshold");
        }
        ApproachPlanner.Search forged = ApproachPlanner.search(new Fixture(continuous, "integrated_server"), continuous, flight(), () -> 0);
        forged.selected = new ApproachPlanner.Candidate(.63, 64.25, -1.37);
        Fixture finalClient = new Fixture(continuous, "client");
        expect("strategy_incompatible", () -> ApproachPlanner.finish(finalClient, continuous, forged, 41, () -> 0));
        check(finalClient.spaces == 0 && finalClient.traces == 0, "fresh client strategy refusal precedes selected body/ray callbacks");
        JsonObject pagedArgs = args("use", "surround"); pagedArgs.addProperty("y", 65);
        pagedArgs.addProperty("movementStrategy", "walking"); pagedArgs.addProperty("requireFlying", false);
        ApproachPlanner.Frame paged = placedFrame(pagedArgs, 10.5, 63, -1.5, body(10.5, 63, -1.5));
        AtomicLong clock = new AtomicLong(); Fixture cold = new Fixture(paged, "integrated_server");
        cold.clock = clock; cold.bodyCost = ApproachPlanner.WORK_NANOS;
        ApproachPlanner.Search first = ApproachPlanner.search(cold, paged, flight(), clock::get);
        cold.bodyCost = 0;
        ApproachPlanner.Search second = ApproachPlanner.search(cold, paged, flight(), first, clock::get);
        check(first.partial && second.selected != null && second.selected.feetY == 63 && second.pages == 2
                && second.strategyIncompatible == 16 && second.candidates == 18 && second.candidateAttempts == 19,
                "continuation retains unique strategy skips and the shared existing page/attempt budgets");
    }
    private static void groundedTeleportSupport() {
        JsonObject request = args("use", "surround");
        request.addProperty("requireFlying", false); request.addProperty("currentOnly", true);
        request.addProperty("movementStrategy", "developer_teleport");
        ApproachPlanner.Frame actual = placedFrame(request, .5,64,2.5,body(.5,64,2.5));
        Fixture safe = new Fixture(actual,"integrated_server");
        ApproachPlanner.Search search = ApproachPlanner.search(safe,actual,flight(),()->0);
        check(search.selected != null, "Complete grounded TP support keeps a reachable endpoint.");
        Fixture edge = new Fixture(actual,"client") {
            public JsonObject spaceAt(double x,double y,double z,double width,double height,long deadline,boolean fullSupport) {
                check(fullSupport, "Grounded TP planning asks for full support geometry before any movement.");
                JsonObject value = super.spaceAt(x,y,z,width,height,deadline);
                value.addProperty("fullSupportCoverage",false);
                value.addProperty("supportContactVersion",1);
                value.addProperty("continuousSupportCoverage",true);
                return value;
            }
        };
        JsonObject edgeBody=edge.spaceAt(.5,64,2.5,.6,1.8,Long.MAX_VALUE,true);
        check(PlayerNavigation.walkingCheck(edgeBody)==WalkingPathPlanner.Check.CLEAR
                && !PlayerNavigation.teleportSupportProven(edgeBody), "Walking edge contact is not complete grounded TP support.");
        check(ApproachPlanner.search(edge,actual,flight(),()->0).selected==null,
                "A touch-only candidate is rejected by the planner, rather than selected and refused at execution.");
        check(ApproachPlanner.finish(edge,actual,search,41,()->0).get("selected").isJsonNull(),
                "Changed client support cannot inherit a complete server-side TP proof.");
        request.addProperty("requireFlying",true);
        ApproachPlanner.Frame airborne=placedFrame(request,.5,64,2.5,body(.5,64,2.5));
        Fixture air=new Fixture(airborne,"integrated_server");air.canStand=false;
        check(ApproachPlanner.search(air,airborne,flight(),()->0).selected!=null,
                "An explicitly airborne TP endpoint retains its original clearance-only planning.");
    }
    private static void normalReachContract() {
        JsonObject args = args("use", "surround"); args.addProperty("useNormalReach", true); args.addProperty("maxDistance", 4.5);
        ApproachPlanner.Frame far = new ApproachPlanner.Frame(new ApproachPlanner.Request(args, 0), WORLD, SERVER, "session", "uuid",
                "{\"registryName\":\"fixture:held\"}", "held-tag", "{\"registryName\":\"fixture:source\"}", "source-tag",
                .5, 63, -3.5, 1.62, .6, 1.8, 5, body(.5, 63, -3.5), 0, 0, 40, 7, true, false, true, null, "");
        for (double serverReach : new double[] {4.5, 5, 3.2}) {
            JsonObject observed = flight(); observed.addProperty("normalReach", serverReach);
            Fixture server = new Fixture(far, "integrated_server"), client = new Fixture(far, "client");
            ApproachPlanner.Search search = ApproachPlanner.search(server, far, observed, () -> 0);
            JsonObject result = ApproachPlanner.finish(client, far, search, 41, () -> 0), current = result.getAsJsonObject("current");
            double expected = Math.min(5, serverReach);
            check(result.get("useNormalReach").getAsBoolean() && current.get("normalReachVersion").getAsInt() == 1
                    && "uuid".equals(current.get("reachPlayerId").getAsString())
                    && current.get("clientNormalReach").getAsDouble() == 5 && current.get("serverNormalReach").getAsDouble() == serverReach
                    && current.get("normalReach").getAsDouble() == expected
                    && current.get("maxDistance").getAsDouble() == Math.min(4.5, expected)
                    && result.getAsJsonObject("coverage").get("normalDistanceBound").getAsDouble() == Math.min(4.5, expected),
                    "explicit normal reach retains both fresh getters and clamps caller cap to their actual minimum");
            check(search.selected != null && (serverReach >= 4.5 ? search.selected.feetZ == far.z : search.selected.feetZ != far.z),
                    "a verified farther current stance can avoid relocation but a tighter actual server getter still excludes it");
        }
        JsonObject capped = args("use", "surround"); capped.addProperty("maxDistance", 4.5);
        expect("invalid_args", () -> new ApproachPlanner.Request(capped, 0));
        JsonObject wide = args("use", "surround"); wide.addProperty("useNormalReach", true); wide.addProperty("maxDistance", 32);
        check(new ApproachPlanner.Request(wide, 0).maxDistance == 32, "only explicit adoption accepts a caller ceiling above the legacy three-block contract");
        wide.addProperty("maxDistance", 32.001); expect("invalid_args", () -> new ApproachPlanner.Request(wide, 0));
        for (boolean missing : new boolean[] {false, true}) {
            JsonObject invalid = flight(); if (missing) invalid.remove("normalReach"); else invalid.addProperty("normalReach", 0);
            expect("invalid_args", () -> ApproachPlanner.search(new Fixture(far, "integrated_server"), far, invalid, () -> 0));
        }
        JsonObject another = flight(); another.addProperty("playerId", "another-player");
        expect("stale_world", () -> ApproachPlanner.search(new Fixture(far, "integrated_server"), far, another, () -> 0));
        ApproachPlanner.Search prefix = new ApproachPlanner.Search(block(0, 64, 0, "fixture:solid", 0, "integrated_server"), flight(), 0);
        prefix.partial = true; JsonObject changed = flight(); changed.addProperty("normalReach", 4.5);
        expect("stale_world", () -> ApproachPlanner.search(new Fixture(far, "integrated_server"), far, changed, prefix, () -> 0));
    }
    private static void candidates() {
        ApproachPlanner.Frame surround = frame(args("use", "surround")); Set<String> slots = new HashSet<String>();
        check(ApproachPlanner.candidate(surround, 0).y == 63, "aligned current feet are evaluated before fixed ring slots");
        for (int i = 0; i < 33; i++) {
            ApproachPlanner.Candidate candidate = ApproachPlanner.candidate(surround, i);
            if (candidate != null) check(slots.add(key(candidate.x, candidate.y, candidate.z)), "candidate duplicate coordinates are skipped");
        }
        check(slots.size() == 32 && ApproachPlanner.candidate(surround, 1) == null, "duplicate first ring keeps stable indices while skipping work");
        ApproachPlanner.Candidate east = ApproachPlanner.candidate(surround, 4), diagonal = ApproachPlanner.candidate(surround, 5);
        check(east.x == 2 && east.y == 63 && east.z == 0 && diagonal.x == -2 && diagonal.z == -2,
                "surround preserves north/south/west/east then diagonal ordering");
        ApproachPlanner.Frame cardinal = frame(args("attack", "cardinal"));
        for (int i = 1; i < 13; i++) {
            ApproachPlanner.Candidate candidate = ApproachPlanner.candidate(cardinal, i);
            if (candidate != null) check(candidate.side == 2 + (i - 1) % 4, "cardinal interaction rings bind the matching exact block face");
        }
        ApproachPlanner.Frame placement = frame(args("place_block", "cardinal"));
        check(ApproachPlanner.candidate(placement, 2).side == -1, "cardinal placement still searches attachment sides rather than target faces");
        check(ApproachPlanner.candidate(changed(surround, "x"), 0) == null, "unaligned current positions cannot silently become centered candidates");
        ApproachPlanner.Candidate lowest = ApproachPlanner.candidate(surround, 17), highest = ApproachPlanner.candidate(surround, 25);
        check(lowest.y == 62 && highest.y == 65, "surround has exactly the four approved height offsets");
    }
    private static void buildEnvelopeContract() {
        JsonObject args = args("use", "surround"), bounds = new JsonObject();
        bounds.addProperty("minX", 0); bounds.addProperty("maxX", 0); bounds.addProperty("minY", 63); bounds.addProperty("maxY", 65);
        bounds.addProperty("minZ", -2); bounds.addProperty("maxZ", 0);
        args.addProperty("requireFlying", false); args.add("excludedBuildEnvelope", bounds);
        ApproachPlanner.Frame actual = new ApproachPlanner.Frame(new ApproachPlanner.Request(args, 0), WORLD, SERVER, "session", "uuid",
                "{\"registryName\":\"fixture:held\"}", "held-tag", "{\"registryName\":\"fixture:source\"}", "source-tag",
                .5, 63, -1.5, 1.62, .6, 1.8, 5, body(.5, 63, -1.5), 0, 0, 40, 7, false, false, true, null, "");
        check(actual.request.total() == 33 && ApproachPlanner.candidate(actual, 0).buildExcluded(actual),
                "explicit envelope keeps 33 slots but excludes the actual entire current body inside future cells");
        ApproachPlanner.Candidate north = ApproachPlanner.candidate(actual, 1), east = ApproachPlanner.candidate(actual, 4);
        check(north.z == -3 && north.y == 63 && east.x == 1 && !north.buildExcluded(actual),
                "outside candidates derive from actual inclusive envelope edges and its grounded minimum height");
        JsonObject observed = flight(); observed.addProperty("flying", false); observed.addProperty("creative", false); observed.addProperty("onGround", true);
        Fixture server = new Fixture(actual, "integrated_server"), client = new Fixture(actual, "client");
        ApproachPlanner.Search search = ApproachPlanner.search(server, actual, observed, () -> 0);
        JsonObject result = ApproachPlanner.finish(client, actual, search, 41, () -> 0);
        check(search.selected != null && search.selected.z == -3 && search.unknown == 0 && server.spaces == 1
                && result.get("buildEnvelopeVersion").getAsInt() == 1 && bounds.equals(result.get("excludedBuildEnvelope")),
                "known current exclusion consumes no geometry/ray work or unknown count and echoes exact declared bounds");
        args.addProperty("currentOnly", true);
        ApproachPlanner.Frame current = new ApproachPlanner.Frame(new ApproachPlanner.Request(args, 0), WORLD, SERVER, "session", "uuid",
                actual.item, actual.itemTag, actual.sourceItem, actual.sourceTag, actual.x, actual.feetY, actual.z,
                actual.eyeOffset, actual.width, actual.height, actual.reach, actual.actorBody, 0, 0, 40, 7, false, false, true, null, "");
        Fixture unavailable = new Fixture(current, "integrated_server");
        ApproachPlanner.Search absence = ApproachPlanner.search(unavailable, current, observed, () -> 0);
        check(absence.selected == null && absence.unknown == 0 && absence.nextOffset == 1 && unavailable.spaces == 0 && absence.rays == 0,
                "current-only future overlap is complete known absence before any native body or ray probes");
        ApproachPlanner.Frame wide = new ApproachPlanner.Frame(actual.request, WORLD, SERVER, "session", "uuid",
                actual.item, actual.itemTag, actual.sourceItem, actual.sourceTag, actual.x, actual.feetY, actual.z,
                actual.eyeOffset, 1.2, actual.height, actual.reach, AxisAlignedBB.getBoundingBox(-.1, 63, -2.1, 1.1, 64.8, -.9), 0, 0, 40, 7, false, false, true, null, "");
        check(ApproachPlanner.candidate(wide, 4).buildExcluded(wide),
                "a nominal outside feet cell still fails when actual body width crosses the future envelope");
        for (String key : new String[] {"minX", "maxY", "maxZ"}) {
            JsonObject invalid = new com.google.gson.JsonParser().parse(bounds.toString()).getAsJsonObject(); invalid.remove(key);
            expect("invalid_args", () -> ApproachPlanner.Request.buildEnvelope(invalid));
        }
        for (String key : new String[] {"minX", "maxY", "maxZ"}) {
            JsonObject invalid = new com.google.gson.JsonParser().parse(bounds.toString()).getAsJsonObject(); invalid.addProperty(key, .5);
            expect("invalid_args", () -> ApproachPlanner.Request.buildEnvelope(invalid));
        }
        JsonObject reversed = new com.google.gson.JsonParser().parse(bounds.toString()).getAsJsonObject(); reversed.addProperty("maxX", -1);
        expect("invalid_args", () -> ApproachPlanner.Request.buildEnvelope(reversed));
        JsonObject oversized = new com.google.gson.JsonParser().parse(bounds.toString()).getAsJsonObject(); oversized.addProperty("maxX", 64);
        expect("invalid_args", () -> ApproachPlanner.Request.buildEnvelope(oversized));
        for (String intent : new String[] {"place_block", "attack"}) {
            JsonObject invalid = args(intent, "surround"); invalid.addProperty("requireFlying", false); invalid.add("excludedBuildEnvelope", bounds);
            expect("invalid_args", () -> new ApproachPlanner.Request(invalid, 0));
        }
        JsonObject airborne = args("use", "surround"); airborne.add("excludedBuildEnvelope", bounds);
        check(new ApproachPlanner.Request(airborne, 0).requireFlying && new ApproachPlanner.Request(airborne, 0).excludedBuildEnvelope != null,
                "explicit native flight retains the complete declared future-body exclusion contract");
    }
    private static AxisAlignedBB body(double x, double y, double z) { return AxisAlignedBB.getBoundingBox(x - .3, y, z - .3, x + .3, y + 1.8, z + .3); }
    private static ApproachPlanner.Frame placedFrame(JsonObject args, double x, double y, double z, AxisAlignedBB actual) {
        return new ApproachPlanner.Frame(new ApproachPlanner.Request(args, 0), WORLD, SERVER, "session", "uuid", "{}", "", "{}", "",
                x, y, z, 1.62, .6, 1.8, 5, actual, 0, 0, 40, 3, true, false, true, null, "");
    }
    private static void prospectivePlacementBody() {
        JsonObject args = args("place_block", "surround"); args.addProperty("currentOnly", true);
        ApproachPlanner.Frame overlap = placedFrame(args, .9, 64, .5, body(.9, 64, .5));
        Fixture server = new Fixture(overlap, "integrated_server");
        ApproachPlanner.Search rejected = ApproachPlanner.search(server, overlap, flight(), () -> 0);
        JsonObject receipt = ApproachPlanner.finish(new Fixture(overlap, "client"), overlap, rejected, 41, () -> 0);
        check(rejected.selected == null && rejected.placementBodyExcluded == 1 && rejected.unknown == 0 && rejected.nextOffset == 1
                && server.spaces == 0 && server.traces == 0 && "no_candidate".equals(receipt.get("status").getAsString())
                && receipt.getAsJsonObject("currentServerPlacementBodyProof").get("overlapsTarget").getAsBoolean(),
                "partial edge overlap is complete known current-only rejection before terrain body and ray callbacks");
        JsonObject ring = args("place_block", "surround");
        ApproachPlanner.Frame relocate = placedFrame(ring, .5, 64, .5, body(.5, 64, .5));
        ApproachPlanner.Search clear = ApproachPlanner.search(new Fixture(relocate, "integrated_server"), relocate, flight(), () -> 0);
        check(clear.selected != null && clear.placementBodyExcluded == 1 && !clear.selected.placementExcluded(relocate)
                && !clear.placementBodyProof.get("overlapsTarget").getAsBoolean(),
                "overlapping current stance may only select an actually disjoint translated body with existing terrain and native ray guards");
        for (String intent : new String[] {"use", "attack"}) {
            JsonObject use = args(intent, "surround"); use.addProperty("currentOnly", true);
            ApproachPlanner.Frame old = placedFrame(use, .9, 64, .5, body(.9, 64, .5));
            ApproachPlanner.Search unchanged = ApproachPlanner.search(new Fixture(old, "integrated_server"), old, flight(), () -> 0);
            check(unchanged.selected != null && unchanged.placementBodyExcluded == 0, "prospective placement exclusion leaves use and attack semantics unchanged");
        }
        AxisAlignedBB displaced = AxisAlignedBB.getBoundingBox(.8, 64, .2, 1.4, 65.8, .8);
        ApproachPlanner.Frame actual = placedFrame(ring, .5, 64, .5, displaced);
        ApproachPlanner.Candidate translated = new ApproachPlanner.Candidate(-.5, 64, .5);
        check(translated.placementExcluded(actual) && Math.abs(translated.body(actual).minX + .2) < .000001,
                "candidate geometry translates actual public AABB offsets instead of rebuilding a centered body");
        displaced.minX = -100;
        check(actual.actorBody.minX == .8, "captured public actor geometry is detached from mutable native AABB aliases");
        ApproachPlanner.Frame touching = placedFrame(args, 1.3, 64, .5, AxisAlignedBB.getBoundingBox(1, 64, .2, 1.6, 65.8, .8));
        check(!ApproachPlanner.candidate(touching, 0).placementExcluded(touching), "zero-volume contact at the whole cube boundary is allowed");
        Fixture differentServer = new Fixture(touching, "integrated_server"); differentServer.placementBodyOverride = body(.9, 64, .5);
        ApproachPlanner.Search actualServerOverlap = ApproachPlanner.search(differentServer, touching, flight(), () -> 0);
        check(actualServerOverlap.selected == null && actualServerOverlap.placementBodyExcluded == 1
                && differentServer.spaces == 0 && differentServer.traces == 0,
                "a clear client AABB cannot certify a current stance whose actual authoritative actor overlaps the target");
        Fixture unknownServer = new Fixture(touching, "integrated_server"); unknownServer.placementBodyUnavailable = true;
        expect("unsupported", () -> ApproachPlanner.search(unknownServer, touching, flight(), () -> 0));
        check(unknownServer.spaces == 0 && unknownServer.traces == 0, "unknown authoritative actor AABB never becomes ready or known no-candidate");
        expect("unsupported", () -> placedFrame(args, .9, 64, .5, null));
    }
    private static void identity() {
        JsonObject requestArgs = args("use", "surround"), expected = new JsonObject();
        expected.addProperty("registryName", "fixture:machine"); expected.addProperty("metadata", 3);
        expected.addProperty("metaTileId", 19420); expected.addProperty("metaClass", "fixture.Meta"); requestArgs.add("targetIdentity", expected);
        ApproachPlanner.Request request = new ApproachPlanner.Request(requestArgs, 0);
        JsonObject actual = block(0, 64, 0, "fixture:machine", 3, "integrated_server"); actual.add("gregtech", gt(19420, "fixture.Meta"));
        ApproachPlanner.requireIdentity(request, actual); checks++;
        actual.addProperty("metadata", 4); expect("target_unverified", () -> ApproachPlanner.requireIdentity(request, actual)); actual.addProperty("metadata", 3);
        actual.getAsJsonObject("gregtech").addProperty("metaTileId", 3); expect("target_unverified", () -> ApproachPlanner.requireIdentity(request, actual));
        actual.remove("gregtech"); expect("target_unverified", () -> ApproachPlanner.requireIdentity(request, actual));
        JsonObject raw = block(0, 64, 0, "null", 0, "client"); expect("target_unverified", () -> ApproachPlanner.requireIdentity(frame(args("use", "surround")).request, raw));
        JsonObject first = block(0, 64, 0, "fixture:machine", 3, "integrated_server"), second = block(0, 64, 0, "fixture:machine", 3, "client");
        first.add("gregtech", gt(19420, "fixture.Meta")); expect("target_unverified", () -> ApproachPlanner.sameIdentity(first, second));
        second.add("gregtech", gt(19420, "fixture.Other")); expect("target_unverified", () -> ApproachPlanner.sameIdentity(first, second));
        second.add("gregtech", gt(19420, "fixture.Meta")); ApproachPlanner.sameIdentity(first, second); checks++;
        second.getAsJsonObject("gregtech").remove("metaTileId"); expect("target_unverified", () -> ApproachPlanner.sameIdentity(first, second));
        expected.addProperty("metadata", 19420);
        ApproachPlanner.Request distinct = new ApproachPlanner.Request(requestArgs, 0);
        expect("target_unverified", () -> ApproachPlanner.requireIdentity(distinct, first));
    }
    private static void extendedMetadata() {
        for (int metadata : new int[] {0, 15, 16, 81, 32767, 65535}) {
            JsonObject args = args("use", "surround"), expected = new JsonObject();
            expected.addProperty("registryName", "fixture:extended"); expected.addProperty("metadata", metadata); args.add("targetIdentity", expected);
            ApproachPlanner.Request request = new ApproachPlanner.Request(args, 0);
            check(request.expectedMetadata == metadata, "target identity retains exact unsigned 16-bit world metadata without item/wildcard conversion");
            JsonObject server = block(0, 64, 0, "fixture:extended", metadata, "integrated_server");
            JsonObject client = block(0, 64, 0, "fixture:extended", metadata, "client");
            ApproachPlanner.requireIdentity(request, server); ApproachPlanner.sameIdentity(server, client); checks += 2;
            client.addProperty("metadata", metadata ^ 16);
            expect("target_unverified", () -> ApproachPlanner.sameIdentity(server, client));
            expect("target_unverified", () -> ApproachPlanner.requireIdentity(request, client));
        }
        for (com.google.gson.JsonElement metadata : new com.google.gson.JsonElement[] {
                JsonNull.INSTANCE, new com.google.gson.JsonPrimitive(true), new com.google.gson.JsonPrimitive(false),
                new com.google.gson.JsonPrimitive(-1), new com.google.gson.JsonPrimitive(65536),
                new com.google.gson.JsonPrimitive(Long.MAX_VALUE),
                new com.google.gson.JsonPrimitive(.5), new com.google.gson.JsonPrimitive("81")}) {
            JsonObject args = args("attack", "surround"), expected = new JsonObject();
            expected.addProperty("registryName", "fixture:extended"); expected.add("metadata", metadata); args.add("targetIdentity", expected);
            expect("invalid_args", () -> new ApproachPlanner.Request(args, 0));
        }
        JsonObject gtArgs = args("use", "surround"), expectedGt = new JsonObject();
        expectedGt.addProperty("registryName", "gregtech:gt.blockmachines"); expectedGt.addProperty("metadata", 81);
        expectedGt.addProperty("metaTileId", 77); expectedGt.addProperty("metaClass", "fixture.Meta"); gtArgs.add("targetIdentity", expectedGt);
        ApproachPlanner.Request gtRequest = new ApproachPlanner.Request(gtArgs, 0);
        JsonObject gtServer = block(0, 64, 0, "gregtech:gt.blockmachines", 81, "integrated_server"); gtServer.add("gregtech", gt(77, "fixture.Meta"));
        ApproachPlanner.requireIdentity(gtRequest, gtServer); checks++;
        gtServer.getAsJsonObject("gregtech").addProperty("metaTileId", 81);
        expect("target_unverified", () -> ApproachPlanner.requireIdentity(gtRequest, gtServer));
        for (String intent : new String[] {"use", "attack", "place_block"}) {
            JsonObject nativeArgs = args(intent, "surround"), expected = new JsonObject();
            boolean placement = "place_block".equals(intent);
            expected.addProperty("registryName", placement ? "minecraft:air" : "fixture:extended"); expected.addProperty("metadata", placement ? 0 : 81);
            nativeArgs.add("targetIdentity", expected); ApproachPlanner.Frame frame = frame(nativeArgs);
            Fixture server = new Fixture(frame, "integrated_server"), client = new Fixture(frame, "client");
            server.rawMetadata = 81; client.rawMetadata = 81;
            if (!placement) { server.registryOverride = "fixture:extended"; client.registryOverride = "fixture:extended"; }
            JsonObject result = finish(frame, server, client), selected = result.getAsJsonObject("selected");
            check("ready".equals(result.get("status").getAsString()) && result.getAsJsonObject("target").get("metadata").getAsInt() == (placement ? 0 : 81),
                    "extended native target metadata retains an exact ready proof while placement keeps its literal air target");
            check(selected.getAsJsonObject("attachment").get("metadata").getAsInt() == 81
                    && selected.getAsJsonObject("serverRay").get("metadata").getAsInt() == 81
                    && selected.getAsJsonObject("clientRay").get("metadata").getAsInt() == 81,
                    "native server search and client finish preserve raw attachment and both ray metadata without masking");
            client.rawMetadata = 82;
            expect("target_unverified", () -> finish(frame, server, client));
        }
    }
    private static void ready() {
        for (String intent : new String[] {"use", "attack", "place_block"}) {
            ApproachPlanner.Frame frame = frame(args(intent, "surround")); Fixture server = new Fixture(frame, "integrated_server"), client = new Fixture(frame, "client");
            JsonObject result = finish(frame, server, client);
            check("ready".equals(result.get("status").getAsString()) && !result.get("complete").getAsBoolean() && !result.get("partial").getAsBoolean()
                    && result.get("nextOffset").isJsonNull() && "first_verified_candidate".equals(result.get("stopReason").getAsString()),
                    "first fully verified success explicitly reports a bounded early stop");
            check(result.get("readOnly").getAsBoolean() && result.get("nonAtomic").getAsBoolean() && !result.get("submitted").getAsBoolean(), "planning never claims an action submission");
            JsonObject selected = result.getAsJsonObject("selected"), current = result.getAsJsonObject("current");
            check(selected.get("unknownChecks").getAsInt() == 0 && selected.getAsJsonObject("attachment").get("side").getAsInt() == 2,
                    "selected geometry has positive exact face evidence and no unknown checks");
            check("fixture:held".equals(current.getAsJsonObject("item").get("registryName").getAsString())
                    && "fixture:source".equals(current.getAsJsonObject("sourceItem").get("registryName").getAsString())
                    && current.get("selectedHotbarSlot").getAsInt() == 7 && current.get("requestedHotbarSlot").getAsInt() == ("place_block".equals(intent) ? 3 : 7),
                    "held item and requested material are separate actual inventory samples");
            for (String phase : new String[] {"server", "client"}) {
                String source = "server".equals(phase) ? "integrated_server" : "client";
                JsonObject ray = selected.getAsJsonObject(phase + "Ray"), body = selected.getAsJsonObject(phase + "Body");
                check(source.equals(ray.get("source").getAsString()) && source.equals(body.get("source").getAsString())
                        && ray.get("native").getAsBoolean() && ray.get("verified").getAsBoolean() && ray.get("side").getAsInt() == 2
                        && ray.get("dimension").getAsInt() == 0 && ray.has("worldTick") && body.has("worldTick") && body.has("body"),
                        "each selected ray/body carries its real owning phase and tick provenance");
                check("64".equals(ray.getAsJsonObject("block").get("y").toString()), "native ray block coordinates serialize as JSON integers");
            }
            check(server.traces == 1 && client.traces == 1 && server.originalStarts == 1 && client.originalStarts == 1,
                    "both native proofs start at the original eye despite destructive trace callbacks");
            check(result.getAsJsonObject("coverage").get("serverRays").getAsInt() == server.traces
                    && result.getAsJsonObject("coverage").get("clientRays").getAsInt() == client.traces,
                    "simple full-cube proofs retain one actual native trace per phase and truthful counts");
        }
        ApproachPlanner.Frame gui = changed(frame(args("use", "surround")), "screen");
        JsonObject result = finish(gui, new Fixture(gui, "integrated_server"), new Fixture(gui, "client"));
        check(result.getAsJsonObject("current").get("screenOpen").getAsBoolean(), "read-only planning returns the actual open GUI without closing it");
        ApproachPlanner.Frame near = changed(frame(args("use", "surround")), "nearX");
        JsonObject centered = finish(near, new Fixture(near, "integrated_server"), new Fixture(near, "client"));
        check(centered.getAsJsonObject("selected").get("moveRequired").getAsBoolean(), "a tolerated near-center current pose still requires exact destination movement");
    }
    private static void unknownAndRefused() {
        ApproachPlanner.Frame frame = frame(args("use", "surround")); Fixture server = new Fixture(frame, "integrated_server"), client = new Fixture(frame, "client");
        server.unknownFirst = true; JsonObject later = finish(frame, server, client);
        check("ready".equals(later.get("status").getAsString()) && later.getAsJsonObject("coverage").get("unknownChecks").getAsInt() == 1
                && later.getAsJsonObject("selected").get("unknownChecks").getAsInt() == 0, "an earlier unknown candidate cannot taint a different fully verified selection");
        server = new Fixture(frame, "integrated_server"); client = new Fixture(frame, "client"); client.hazard = true;
        JsonObject rejected = finish(frame, server, client);
        check("client_space_unverified".equals(rejected.get("status").getAsString()) && client.traces == 0,
                "hazardous client body is refused before native tracing");
        JsonObject evidence = rejected.getAsJsonObject("rejectedCandidate");
        check(rejected.get("selected").isJsonNull() && !rejected.get("submitted").getAsBoolean()
                && evidence.has("feet") && !evidence.get("clientSpaceSafe").getAsBoolean()
                && evidence.getAsJsonObject("clientBody").get("hazard").getAsBoolean()
                && !evidence.getAsJsonObject("serverBody").get("hazard").getAsBoolean()
                && evidence.getAsJsonObject("clientBody").get("worldTick").getAsInt() == 41
                && evidence.getAsJsonObject("serverBody").get("worldTick").getAsInt() == 40,
                "rejected candidate diagnostics preserve phase evidence without a usable selection or action");
        client = new Fixture(frame, "client"); client.omitHazard = true;
        check("client_space_unverified".equals(finish(frame, new Fixture(frame, "integrated_server"), client).get("status").getAsString()), "missing hazard proof is unknown rather than safe");
        client = new Fixture(frame, "client"); client.wrongFace = true;
        JsonObject wrong = finish(frame, new Fixture(frame, "integrated_server"), client);
        check("partial".equals(wrong.get("status").getAsString()) && "ray_limit".equals(wrong.get("stopReason").getAsString())
                && wrong.get("selected").isJsonNull() && wrong.getAsJsonObject("coverage").get("clientRays").getAsInt() == client.traces
                && client.traces == ApproachPlanner.MAX_CLIENT_RAYS, "a server-only exact ray never substitutes for client visibility or bypasses the actual client sample cap");
        Fixture unloaded = new Fixture(frame, "integrated_server"); unloaded.missing.add(key(0, 64, 0));
        expect("chunk_unloaded", () -> ApproachPlanner.search(unloaded, frame, flight(), () -> 0)); check(unloaded.states == 0 && unloaded.traces == 0, "unloaded targets are refused before native block reads");
        Fixture blocked = new Fixture(frame, "integrated_server"); blocked.missing.add(key(0, 64, -16));
        JsonObject rayUnknown = finish(frame, blocked, new Fixture(frame, "client"));
        check(!"ready".equals(rayUnknown.get("status").getAsString()) || blocked.traces > 0, "an unknown current ray can only be bypassed by independently verified later geometry");
        Vec3 eye = Vec3.createVectorHelper(.5, 64.62, -1.5); int before = blocked.traces;
        check(NativePlacementAdapter.visibleFace(blocked, eye, 3, 0, 64, 0, 2) == null && blocked.traces == before, "unknown ray columns never invoke native tracing");
        Fixture boundary = new Fixture(frame, "integrated_server"); boundary.missing.add(key(16, 64, 0));
        check(NativePlacementAdapter.visibleFace(boundary, Vec3.createVectorHelper(13.5, 64.5, .5), 3, 15, 64, 0, 4) == null && boundary.traces == 0,
                "an unloaded neighbour beyond a chunk-edge target prevents shape callbacks before native tracing");
        Fixture fakeAir = new Fixture(frame(args("place_block", "surround")), "integrated_server"); fakeAir.registryOverride = "fixture:semantic_air";
        expect("invalid_state", () -> ApproachPlanner.search(fakeAir, fakeAir.frame, flight(), () -> 0));
        JsonObject walkingArgs = args("use", "surround"); walkingArgs.addProperty("requireFlying", false); ApproachPlanner.Frame walking = frame(walkingArgs);
        Fixture unsupported = new Fixture(walking, "integrated_server"); unsupported.canStand = false;
        JsonObject noGround = finish(walking, unsupported, new Fixture(walking, "client"));
        check("no_candidate".equals(noGround.get("status").getAsString()) && noGround.get("complete").getAsBoolean(), "non-flying plans require real support on every candidate");
        Fixture missingGt = new Fixture(frame, "client"); missingGt.registryOverride = "fixture:different";
        expect("target_unverified", () -> finish(frame, new Fixture(frame, "integrated_server"), missingGt));
    }
    private static void budgets() {
        ApproachPlanner.Frame frame = frame(args("use", "surround"));
        for (boolean ray : new boolean[] {false, true}) {
            AtomicLong clock = new AtomicLong(); Fixture server = new Fixture(frame, "integrated_server"); server.clock = clock;
            server.bodyCost = ray ? 0 : ApproachPlanner.WORK_NANOS; server.rayCost = ray ? ApproachPlanner.WORK_NANOS : 0;
            ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), clock::get);
            check(search.partial && search.selected == null && search.nextOffset == 0 && search.candidates == 1,
                    "server body/ray budget exhaustion retains the unfinished candidate index");
            JsonObject result = ApproachPlanner.finish(new Fixture(frame, "client"), frame, search, 41, clock::get);
            check("partial".equals(result.get("status").getAsString()) && result.get("nextOffset").getAsInt() == 0, "server partial receipt has an explicit resumable offset");
            Fixture freshServer = new Fixture(frame, "integrated_server"), client = new Fixture(frame, "client"); clock.set(0);
            ApproachPlanner.Search ready = ApproachPlanner.search(freshServer, frame, flight(), clock::get); client.clock = clock;
            client.bodyCost = ray ? 0 : ApproachPlanner.WORK_NANOS; client.rayCost = ray ? ApproachPlanner.WORK_NANOS : 0;
            JsonObject partial = ApproachPlanner.finish(client, frame, ready, 41, clock::get);
            check("partial".equals(partial.get("status").getAsString()) && partial.get("selected").isJsonNull()
                    && partial.get("nextOffset").getAsInt() == 0, "client body/ray exhaustion never returns a ready candidate");
        }
        ApproachPlanner.Search old = ApproachPlanner.search(new Fixture(frame, "integrated_server"), frame, flight(), () -> 0);
        expect("stale_world", () -> ApproachPlanner.finish(new Fixture(frame, "client"), frame, old, 42, () -> 1000000001L));
        Fixture noHits = new Fixture(frame, "integrated_server"); noHits.noHit = true;
        ApproachPlanner.Search complete = ApproachPlanner.search(noHits, frame, flight(), () -> 0);
        check(complete.partial && complete.selected == null && complete.candidates <= 33 && complete.rays == ApproachPlanner.MAX_RAYS
                && noHits.traces == complete.rays && "ray_limit".equals(complete.stopReason),
                "fallback sampling stops at the original global native ray cap rather than multiplying it per face");
        JsonObject topArgs = args("use", "surround"); topArgs.addProperty("y", 255); ApproachPlanner.Frame top = frame(topArgs);
        Fixture high = new Fixture(top, "integrated_server"); high.noHit = true; ApproachPlanner.search(high, top, flight(), () -> 0);
        check(!high.invalidBodyY, "unsupported world-height bodies are skipped before spatial callbacks");
    }
    private static void attackCandidateBudget() {
        ApproachPlanner.Frame frame = frame(args("attack", "surround"));
        Fixture server = new Fixture(frame, "integrated_server") {
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
                noHit = !(eye.xCoord < 0 && eye.yCoord > 65);
                return super.trace(eye, point);
            }
        };
        ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0);
        JsonObject result = ApproachPlanner.finish(new Fixture(frame, "client"), frame, search, 41, () -> 0);
        check(result.getAsJsonObject("coverage").get("maxCandidateAttempts").getAsInt() == 69,
                "Two-pass attack receipts declare the fixed maximum including bounded continuation attempts.");
        check("ready".equals(result.get("status").getAsString()) && search.selected.feetX < 0 && search.selected.feetY >= 64
                && search.rays < 50 && search.candidates > 8,
                "Occluded early attack stands cannot exhaust detailed samples before a later visible side gets its native probe.");
        Fixture blocked = new Fixture(frame, "integrated_server"); blocked.noHit = true;
        ApproachPlanner.Search exhausted = ApproachPlanner.search(blocked, frame, flight(), () -> 0);
        check(exhausted.partial && exhausted.selected == null && exhausted.samplingPass == 1
                && exhausted.rays == ApproachPlanner.MAX_RAYS && blocked.traces == exhausted.rays
                && exhausted.candidates <= frame.request.total() && exhausted.candidateAttempts <= 2*frame.request.total(),
                "The detailed second pass retains the same global ray cap and unique candidate coverage.");
        JsonObject currentArgs = args("use", "surround"); currentArgs.addProperty("currentOnly", true);
        ApproachPlanner.Frame current = frame(currentArgs);
        JsonObject currentProof = finish(current, new Fixture(current, "integrated_server"), new Fixture(current, "client"));
        check(currentProof.getAsJsonObject("coverage").get("maxCandidateAttempts").getAsInt() == 36
                && !currentProof.getAsJsonObject("coverage").has("samplingPasses"),
                "One-pass and actual-current receipts retain their existing public budget contract.");
    }

    private static void sampledRayBudgets() {
        ApproachPlanner.Frame frame = frame(args("use", "surround"));
        Fixture insetServer = new Fixture(frame, "integrated_server"), insetClient = new Fixture(frame, "client");
        insetServer.nativeShape = insetClient.nativeShape = AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75);
        JsonObject inset = finish(frame, insetServer, insetClient);
        check("ready".equals(inset.get("status").getAsString()) && insetServer.traces == 1 && insetClient.traces == 1
                && inset.getAsJsonObject("coverage").get("serverRays").getAsInt() == 1 && inset.getAsJsonObject("coverage").get("clientRays").getAsInt() == 1,
                "inset geometry rechecks the server direction with one fresh client trace instead of repeating the shape search");
        JsonObject coverage = inset.getAsJsonObject("coverage");
        check(coverage.get("rayCountingVersion").getAsInt() == 1 && coverage.get("maxRaysPerFace").getAsInt() == 53
                && coverage.get("maxServerRays").getAsInt() == 198 && coverage.get("maxClientRays").getAsInt() == 27,
                "sampled ray receipts publish typed helper bounds while preserving the tighter shared server/client caps");
        Fixture lateServer = cornerShape(frame, "integrated_server"), lateClient = cornerShape(frame, "client");
        JsonObject late = finish(frame, lateServer, lateClient);
        check("ready".equals(late.get("status").getAsString()) && lateServer.traces == 15
                && lateClient.traces == 1, "An inset shape search rechecks its native direction in one fresh client ray.");
        Fixture occluded = cornerShape(frame, "client"); occluded.noHit = true;
        JsonObject lost = finish(frame, cornerShape(frame, "integrated_server"), occluded);
        check(lost.get("selected").isJsonNull() && "ray_limit".equals(lost.get("stopReason").getAsString())
                && occluded.traces == ApproachPlanner.MAX_CLIENT_RAYS,
                "A preferred server hit cannot bypass changed client occlusion or enlarge its ray cap.");
        Fixture fullShapeSamples = new Fixture(frame, "integrated_server"); fullShapeSamples.noHit = true;
        NativePlacementAdapter.Access selectedBounds = new NativePlacementAdapter.Access() {
            public boolean loaded(int x, int y, int z) { return fullShapeSamples.loaded(x, y, z); }
            public NativePlacementAdapter.State state(int x, int y, int z) { return fullShapeSamples.state(x, y, z); }
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) { return fullShapeSamples.trace(eye, point); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return AxisAlignedBB.getBoundingBox(x + .25, y + .25, z + .25, x + .75, y + .75, z + .75); }
        };
        check(NativePlacementAdapter.visibleFace(selectedBounds, Vec3.createVectorHelper(.5, 64.62, -1.5), 3, 0, 64, 0, 2) == null
                && fullShapeSamples.traces == ApproachPlanner.MAX_RAYS_PER_FACE,
                "the typed 52-ray helper ceiling accounts for both initial probes and both 25-sample fallback grids");
        Fixture noHits = new Fixture(frame, "integrated_server"); noHits.noHit = true;
        ApproachPlanner.Search capped = ApproachPlanner.search(noHits, frame, flight(), () -> 0);
        JsonObject capReceipt = ApproachPlanner.finish(new Fixture(frame, "client"), frame, capped, 41, () -> 0);
        check(noHits.traces == ApproachPlanner.MAX_RAYS && capped.rays == noHits.traces && capped.partial
                && "partial".equals(capReceipt.get("status").getAsString()) && "ray_limit".equals(capReceipt.get("stopReason").getAsString())
                && capReceipt.get("selected").isJsonNull() && !capReceipt.get("complete").getAsBoolean(),
                "198 real callbacks are the public global server bound and exhausted sampling cannot be ready or complete");
        check(capReceipt.getAsJsonObject("coverage").get("rayCountingVersion").getAsInt() == 1
                && capReceipt.getAsJsonObject("coverage").get("maxRaysPerFace").getAsInt() == 53,
                "partial receipts retain the same typed sampled-ray contract without implying a ready result");
        check(noHits.loads <= ApproachPlanner.MAX_RAYS * 6 + ApproachPlanner.MAX_CANDIDATES * 6 * 5 + 1
                && noHits.spaces <= ApproachPlanner.MAX_CANDIDATES,
                "fixed short-ray loaded checks and candidate body reads remain bounded after shape sampling");
        expect("invalid_state", () -> ApproachPlanner.search(noHits, frame, flight(), capped, () -> 0));
        ApproachPlanner.Search prefix = new ApproachPlanner.Search(block(0, 64, 0, "fixture:solid", 0, "integrated_server"), flight(), 0);
        prefix.partial = true; prefix.rays = ApproachPlanner.MAX_RAYS - 8;
        Fixture remaining = new Fixture(frame, "integrated_server"); remaining.noHit = true;
        ApproachPlanner.Search tail = ApproachPlanner.search(remaining, frame, flight(), prefix, () -> 0);
        check(tail.partial && tail.rays == ApproachPlanner.MAX_RAYS && remaining.traces == 8 && tail.pages == 2,
                "continuation shape sampling consumes only the eight real callbacks remaining in the shared global cap");
        for (boolean clientPhase : new boolean[] {false, true}) {
            AtomicLong clock = new AtomicLong(); Fixture sampled = new Fixture(frame, clientPhase ? "client" : "integrated_server");
            sampled.clock = clock; sampled.noHit = true; sampled.rayCost = ApproachPlanner.WORK_NANOS / 2;
            if (clientPhase) {
                ApproachPlanner.Search server = ApproachPlanner.search(new Fixture(frame, "integrated_server"), frame, flight(), clock::get);
                JsonObject expired = ApproachPlanner.finish(sampled, frame, server, 41, clock::get);
                check("partial".equals(expired.get("status").getAsString()) && "tick_budget".equals(expired.get("stopReason").getAsString())
                        && expired.get("selected").isJsonNull() && expired.getAsJsonObject("coverage").get("clientRays").getAsInt() == 2,
                        "client fallback checks the shared phase deadline between actual native callbacks");
                JsonObject repeated = ApproachPlanner.finish(new Fixture(frame, "client"), frame, server, 42, clock::get);
                check("partial".equals(repeated.get("status").getAsString()) && repeated.get("selected").isJsonNull(),
                        "a previously partial confirmation cannot later reuse its server selection to claim ready");
            } else {
                ApproachPlanner.Search expired = ApproachPlanner.search(sampled, frame, flight(), clock::get);
                check(expired.partial && expired.selected == null && expired.rays == 2 && "tick_budget".equals(expired.stopReason),
                        "server fallback discards late native results and stops at the four millisecond phase boundary");
            }
            check(sampled.traces == 2 && sampled.loads == sampled.loadsAtLastTrace,
                    "budget exhaustion starts no additional native ray or sampled loaded callback");
        }
    }
    private static Fixture cornerShape(ApproachPlanner.Frame frame, String source) {
        return new Fixture(frame, source) {
            { nativeShape = AxisAlignedBB.getBoundingBox(.65, 64.7, .25, .9, 64.9, .75); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) {
                return AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1);
            }
        };
    }
    private static void thinParts() {
        insetPartArrival();
        JsonObject part = new JsonObject(); part.addProperty("registryName", "fixture:plate"); part.addProperty("metadata", 220); part.addProperty("side", 1);
        JsonObject args = args("use", "surround"), identity = new JsonObject();
        identity.addProperty("registryName", "fixture:solid"); identity.addProperty("metadata", 0); identity.add("part", part); args.add("targetIdentity", identity);
        args.remove("allowedSides"); // Real regression: no caller workaround restricting the face.
        ApproachPlanner.Frame frame = frame(args);
        PartFixture server = new PartFixture(frame, "integrated_server", part), client = new PartFixture(frame, "client", part);
        ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0);
        JsonObject result = ApproachPlanner.finish(client, frame, search, 41, () -> 0);
        check("ready".equals(result.get("status").getAsString()) && server.traces < 27 && client.traces == 1,
                "Thin disconnected part boxes with an occluded first box need few server rays and one fresh client confirmation.");
        check(result.getAsJsonObject("coverage").get("serverRays").getAsInt() == server.traces
                && result.getAsJsonObject("coverage").get("clientRays").getAsInt() == client.traces,
                "Preferred native direction reuse still counts every actual trace.");
        client = new PartFixture(frame, "client", part); client.rejectPart = true;
        JsonObject changed = ApproachPlanner.finish(client, frame, ApproachPlanner.search(server, frame, flight(), () -> 0), 42, () -> 0);
        check(!"ready".equals(changed.get("status").getAsString()) && changed.get("selected").isJsonNull(),
                "A fresh ray hitting another part cannot reuse the server's identity proof.");
    }
    private static void insetPartArrival() {
        JsonObject part = new JsonObject(); part.addProperty("registryName", "fixture:plate"); part.addProperty("metadata", 220); part.addProperty("side", 3);
        for (boolean shared : new boolean[] {false, true}) {
            JsonObject request = shared ? sharedArgs(2) : args("use", "surround");
            request.addProperty("currentOnly", true); request.addProperty("useNormalReach", true);
            request.addProperty("movementStrategy", "ability_flight"); request.addProperty("requireFlying", true);
            JsonObject identity = new JsonObject(); identity.addProperty("registryName", "fixture:solid"); identity.add("part", part);
            JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(2));
            request.add("targetIdentity", identity); request.add("allowedSides", sides);
            if (shared) for (com.google.gson.JsonElement entry : request.getAsJsonArray("targets")) {
                entry.getAsJsonObject().add("targetIdentity", identity); entry.getAsJsonObject().add("allowedSides", sides);
            }
            // Actual arrival can stop north of an inset south-mounted plate while already south
            // of the host cube's north plane. Both primary and secondary targets must use native geometry.
            ApproachPlanner.Frame frame = placedFrame(request, -1.5, 64.635, .7246, body(-1.5, 64.635, .7246));
            InsetPartFixture server = new InsetPartFixture(frame, "integrated_server", part), client = new InsetPartFixture(frame, "client", part);
            ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0);
            JsonObject result = ApproachPlanner.finish(client, frame, search, 41, () -> 0);
            check("ready".equals(result.get("status").getAsString()) && !result.getAsJsonObject("selected").get("moveRequired").getAsBoolean(),
                    "Inset multipart current arrival retains the actual safe pose, including shared targets.");
            check(server.traces <= 4 && client.traces <= 4, "Inset arrival uses bounded native part rays without cube sampling.");
            for (int failure = 0; failure < 4; failure++) {
                InsetPartFixture refused = new InsetPartFixture(frame, "integrated_server", part);
                if (failure == 0) refused.noHit = true;
                if (failure == 1) refused.rejectPart = true;
                if (failure == 2) refused.wrongFace = true;
                if (failure == 3) refused.hazard = true;
                check(ApproachPlanner.search(refused, frame, flight(), () -> 0).selected == null,
                        "Inset arrival still rejects occlusion, other parts, wrong faces and unsafe body space.");
            }
        }
    }
    private static final class InsetPartFixture extends Fixture {
        final JsonObject part; boolean rejectPart;
        InsetPartFixture(ApproachPlanner.Frame frame, String source, JsonObject part) { super(frame, source); this.part = part; }
        public NativePlacementAdapter.State state(int x, int y, int z) {
            NativePlacementAdapter.State state = super.state(x, y, z); JsonArray parts = new JsonArray(); parts.add(part); state.description.add("parts", parts); return state;
        }
        public java.util.List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject expected) {
            return java.util.Collections.singletonList(AxisAlignedBB.getBoundingBox(x+.2, y+.2, z+.875, x+.8, y+.8, z+1));
        }
        public JsonObject selectedPart(MovingObjectPosition hit) { return hit != null && !rejectPart ? part : null; }
        public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            traces++; if (noHit) return null;
            MovingObjectPosition nearest = null; double distance = Double.POSITIVE_INFINITY;
            java.util.List<ApproachPlanner.Request> targets = frame.request.sharedTargets.isEmpty()
                    ? java.util.Collections.singletonList(frame.request) : frame.request.sharedTargets;
            for (ApproachPlanner.Request target : targets) {
                MovingObjectPosition hit = partBoxes(target.x, target.y, target.z, part).get(0).calculateIntercept(start, point);
                if (hit != null && start.distanceTo(hit.hitVec) < distance) {
                    distance=start.distanceTo(hit.hitVec);
                    nearest=new MovingObjectPosition(target.x,target.y,target.z,wrongFace ? hit.sideHit ^ 1 : hit.sideHit,hit.hitVec);
                }
            }
            return nearest;
        }
    }
    private static final class PartFixture extends Fixture {
        final JsonObject part; boolean rejectPart;
        final java.util.List<AxisAlignedBB> boxes = java.util.Arrays.asList(
            AxisAlignedBB.getBoundingBox(.1, 64.9, .1, .4, 65, .9),
            AxisAlignedBB.getBoundingBox(.6, 64.9, .1, .9, 65, .9));
        PartFixture(ApproachPlanner.Frame frame, String source, JsonObject part) { super(frame, source); this.part = part; }
        public NativePlacementAdapter.State state(int x, int y, int z) {
            NativePlacementAdapter.State state = super.state(x, y, z); JsonArray parts = new JsonArray(); parts.add(part); state.description.add("parts", parts); return state;
        }
        public java.util.List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject expected) { return boxes; }
        public JsonObject selectedPart(MovingObjectPosition hit) { return hit != null && hit.hitVec.xCoord > .5 && !rejectPart ? part : null; }
        public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            traces++; MovingObjectPosition nearest = null;
            for (AxisAlignedBB box : boxes) {
                MovingObjectPosition hit = box.calculateIntercept(start, point);
                if (hit != null && (nearest == null || start.distanceTo(hit.hitVec) < start.distanceTo(nearest.hitVec))) nearest = hit;
            }
            return nearest == null ? null : new MovingObjectPosition(0, 64, 0, nearest.sideHit, nearest.hitVec);
        }
    }
    private static ApproachPlanner.Frame camera(ApproachPlanner.Frame f, double smoothing, boolean sneakInput) {
        double normal = f.eyeOffset + (Double.isFinite(f.eyeSmoothing) ? f.eyeSmoothing : 0);
        return new ApproachPlanner.Frame(f.request, f.worldIdentity, f.serverIdentity, f.session, f.playerId,
                f.item, f.itemTag, f.sourceItem, f.sourceTag, f.x, f.feetY, f.z, normal - smoothing, f.width, f.height,
                f.reach, f.actorBody, f.yaw, f.pitch, f.captureWorldTick, f.selectedSlot, f.flying, f.sneaking,
                f.cursorEmpty, f.screenIdentity, f.screenId, smoothing, sneakInput);
    }
    private static void eyeFailure(ApproachPlanner.EyeRead read, String code, String field) throws Exception {
        try { read.future.get(); throw new AssertionError("Expected bounded pure eye failure"); }
        catch (ExecutionException error) {
            check(error.getCause() instanceof BridgeException, "eye failure remains a typed bridge refusal");
            BridgeException failure = (BridgeException) error.getCause();
            check(code.equals(failure.code) && failure.getMessage().contains(field) && failure.getMessage().contains("eyeSettling="),
                    "eye failure preserves actual changed category and compact observation receipt: " + field);
            check(failure.getMessage().length() < 4096 && !failure.getMessage().contains("held-tag") && !failure.getMessage().contains("source-tag"),
                    "camera failure diagnostics are bounded and do not expose inventory tag contents");
        }
    }
    private static void eyeObservations() throws Exception {
        ApproachPlanner.Frame ordinary = frame(args("use", "surround"));
        check(!ordinary.needsEyeSettle() && !camera(ordinary, 0, false).needsEyeSettle(), "unknown or stable-zero public smoothing has no unconditional wait");
        check(ApproachPlanner.EyeRead.immediate().get("tickSamples").getAsInt() == 0, "ordinary receipt reports zero observations and delay");
        final ApproachPlanner.Frame initial = camera(ordinary, (double) (.2F * .4F * .4F), false);
        AtomicLong clock = new AtomicLong(); final float[] smoothing = {(float) initial.eyeSmoothing};
        ApproachPlanner.EyeRead read = new ApproachPlanner.EyeRead(initial, () -> { smoothing[0] *= .4F; return camera(initial, smoothing[0], false); }, 10, 2000000000L, clock::get);
        read.tick(10); check(read.tickSamples == 0 && !read.future.isDone(), "same client tick cannot manufacture a new physical eye observation");
        for (long tick = 11; !read.future.isDone() && tick < 30; tick++) { clock.addAndGet(50000000L); read.tick(tick); }
        ApproachPlanner.Frame stable = read.future.get(); JsonObject receipt = read.receipt();
        check(stable.eyeSmoothing <= ApproachPlanner.EyeRead.STABLE_EPSILON && stable.eyeOffset != initial.eyeOffset
                && receipt.get("tickSamples").getAsInt() == 9 && receipt.get("actualClientTicks").getAsInt() == 9
                && receipt.get("elapsedMillis").getAsDouble() == 450 && receipt.get("settled").getAsBoolean(),
                "real float public .032 offset settles on nine actual samples, with truthful conditional tick/time receipt");
        expect("stale_world", () -> initial.check(stable));
        stable.check(camera(stable, 0, false)); checks++;
        Fixture server = new Fixture(stable, "integrated_server"), client = new Fixture(stable, "client");
        ApproachPlanner.Search search = ApproachPlanner.search(server, stable, flight(), clock::get);
        JsonObject ready = ApproachPlanner.finish(client, stable, search, 41, clock::get);
        check("ready".equals(ready.get("status").getAsString()) && server.traces > 0 && client.traces > 0
                && Math.abs(ready.getAsJsonObject("selected").getAsJsonObject("eye").get("y").getAsDouble()
                    - ready.getAsJsonObject("selected").getAsJsonObject("feet").get("y").getAsDouble() - stable.eyeOffset) < .0000001,
                "settled fresh frame independently reacquires both actual fixture rays; old eye proof is never reused");
        for (String key : new String[] {"world", "server", "session", "uuid", "screen", "screenId", "x", "y", "z", "body", "width", "height", "reach", "yaw", "pitch", "slot", "flight", "sneak", "cursor", "item", "itemTag", "source", "sourceTag"}) {
            ApproachPlanner.EyeRead drift = new ApproachPlanner.EyeRead(initial, () -> changed(initial, key), 0, 2000000000L, () -> 0);
            drift.tick(1); eyeFailure(drift, "stale_world", "changedFields");
        }
        ApproachPlanner.EyeRead renewedInput = new ApproachPlanner.EyeRead(initial, () -> camera(initial, .0128, true), 0, 2000000000L, () -> 0);
        renewedInput.tick(1); eyeFailure(renewedInput, "stale_world", "sneakInput");
        ApproachPlanner.EyeRead growing = new ApproachPlanner.EyeRead(initial, () -> camera(initial, .04, false), 0, 2000000000L, () -> 0);
        growing.tick(1); eyeFailure(growing, "stale_world", "eyeSmoothing");
        ApproachPlanner.EyeRead unknown = new ApproachPlanner.EyeRead(initial, () -> changed(initial, "eye"), 0, 2000000000L, () -> 0);
        unknown.tick(1); eyeFailure(unknown, "stale_world", "eyeOffset");
        ApproachPlanner.EyeRead stuck = new ApproachPlanner.EyeRead(initial, () -> initial, 0, 2000000000L, () -> 0);
        for (int tick = 1; tick <= ApproachPlanner.EyeRead.MAX_TICK_SAMPLES; tick++) stuck.tick(tick);
        eyeFailure(stuck, "stale_world", "bounded tick samples"); check(stuck.tickSamples == 16, "unsettled geometry cannot wait unboundedly");
        AtomicLong expiredClock = new AtomicLong(); ApproachPlanner.EyeRead expired = new ApproachPlanner.EyeRead(initial, () -> initial, 0, 1, expiredClock::get);
        expiredClock.set(1); expired.tick(1); eyeFailure(expired, "timeout", "original read deadline"); check(expired.tickSamples == 0, "original deadline ends before another capture or any server ray");
        final int[] captures = {0}; ApproachPlanner.EyeRead cancelled = new ApproachPlanner.EyeRead(initial, () -> { captures[0]++; return initial; }, 0, 2000000000L, () -> 0);
        cancelled.close(); cancelled.tick(1); eyeFailure(cancelled, "cancelled", "no action was submitted");
        check(captures[0] == 0, "cancellation submits no further client read or action");
    }
    private static void staleFrames() {
        ApproachPlanner.Frame original = frame(args("use", "surround")); original.check(original); checks++;
        for (String key : new String[] {"world", "server", "session", "uuid", "screen", "screenId", "x", "y", "z", "body", "eye", "width", "height", "reach", "yaw", "pitch", "slot", "flight", "sneak", "cursor", "item", "itemTag", "source", "sourceTag"})
            expect("stale_world", () -> original.check(changed(original, key)));
    }
    private static void continuations() {
        ApproachPlanner.Frame frame = frame(args("use", "surround")); AtomicLong clock = new AtomicLong();
        Fixture cold = new Fixture(frame, "integrated_server"); cold.clock = clock; cold.bodyCost = ApproachPlanner.WORK_NANOS; cold.unknownFirst = true;
        ApproachPlanner.Search first = ApproachPlanner.search(cold, frame, flight(), clock::get);
        check(first.partial && first.selected == null && first.candidates == 1 && first.candidateAttempts == 1 && first.unknown == 1,
                "a cold incomplete body page returns no reusable proof");
        cold.bodyCost = 0; cold.unknownFirst = false; JsonObject freshFlight = flight(); freshFlight.addProperty("worldTick", 42);
        ApproachPlanner.Search resumed = ApproachPlanner.search(cold, frame, freshFlight, first, clock::get); resumed.target.addProperty("worldTick", 42);
        check(!resumed.partial && resumed.selected != null && resumed.pages == 2 && resumed.candidates == 1 && resumed.candidateAttempts == 2
                && resumed.rays == 1 && resumed.unknown == 1 && first.partial && first.selected == null,
                "same-slot continuation counts unique candidates, keeps earlier unknowns and reacquires every selected proof");
        JsonObject result = ApproachPlanner.finish(new Fixture(frame, "client"), frame, resumed, 43, clock::get);
        check("ready".equals(result.get("status").getAsString()) && result.getAsJsonObject("selected").get("unknownChecks").getAsInt() == 0
                && result.getAsJsonObject("selected").getAsJsonObject("serverBody").get("worldTick").getAsInt() == 42
                && result.getAsJsonObject("selected").getAsJsonObject("serverRay").get("worldTick").getAsInt() == 42
                && result.getAsJsonObject("coverage").get("offset").getAsInt() == 0 && result.getAsJsonObject("coverage").get("serverPages").getAsInt() == 2,
                "only the fresh successful page supplies server flight/body/ray ticks while the requested offset remains unchanged");
        Fixture changed = new Fixture(frame, "integrated_server"); changed.registryOverride = "fixture:changed";
        expect("target_unverified", () -> ApproachPlanner.search(changed, frame, flight(), first, clock::get));
        check(changed.spaces == 0 && changed.traces == 0, "target identity changes between pages stop before candidate callbacks");
        AtomicLong rayClock = new AtomicLong(); Fixture ray = new Fixture(frame, "integrated_server"); ray.clock = rayClock; ray.rayCost = ApproachPlanner.WORK_NANOS;
        ApproachPlanner.Search expiredRay = ApproachPlanner.search(ray, frame, flight(), rayClock::get);
        ray.rayCost = 0; ray.hazard = true;
        ApproachPlanner.Search unsafe = ApproachPlanner.search(ray, frame, flight(), expiredRay, rayClock::get);
        check(unsafe.selected == null && !unsafe.partial && unsafe.rays == 1 && ray.traces == 1,
                "a previous timed-out native hit cannot bypass fresh hazardous body evidence on the continuation page");
        AtomicLong alwaysClock = new AtomicLong(); Fixture always = new Fixture(frame, "integrated_server"); always.clock = alwaysClock; always.bodyCost = ApproachPlanner.WORK_NANOS;
        ApproachPlanner.Search previous = null;
        for (int page = 1; page <= ApproachPlanner.MAX_SERVER_PAGES; page++) previous = ApproachPlanner.search(always, frame, flight(), previous, alwaysClock::get);
        final ApproachPlanner.Search exhausted = previous;
        check(exhausted.partial && exhausted.selected == null && exhausted.pages == 4 && exhausted.candidates == 1 && exhausted.candidateAttempts == 4 && exhausted.nextOffset == 0,
                "four budget pages remain explicitly partial when the same candidate never completes");
        expect("invalid_state", () -> ApproachPlanner.search(always, frame, flight(), exhausted, alwaysClock::get));
        JsonObject midArgs = args("use", "surround"); midArgs.addProperty("offset", 5); ApproachPlanner.Frame midFrame = frame(midArgs);
        AtomicLong midClock = new AtomicLong(); Fixture mid = new Fixture(midFrame, "integrated_server"); mid.clock = midClock; mid.bodyCost = ApproachPlanner.WORK_NANOS;
        ApproachPlanner.Search midFirst = ApproachPlanner.search(mid, midFrame, flight(), midClock::get); mid.bodyCost = 0;
        ApproachPlanner.Search midNext = ApproachPlanner.search(mid, midFrame, flight(), midFirst, midClock::get);
        JsonObject midResult = ApproachPlanner.finish(new Fixture(midFrame, "client"), midFrame, midNext, 41, midClock::get);
        check(midFirst.nextOffset == 5 && midNext.selected != null && midResult.getAsJsonObject("coverage").get("offset").getAsInt() == 5
                && midNext.candidates == 1 && midNext.candidateAttempts == 2, "a nonzero request offset survives an unfinished-slot restart without skips or duplicate slot counts");
    }
    private static ApproachPlanner.Frame changed(ApproachPlanner.Frame f, String key) {
        return new ApproachPlanner.Frame(f.request, "world".equals(key) ? new Object() : f.worldIdentity, "server".equals(key) ? new Object() : f.serverIdentity,
                "session".equals(key) ? "other" : f.session, "uuid".equals(key) ? "other" : f.playerId,
                "item".equals(key) ? "{}" : f.item, "itemTag".equals(key) ? "other" : f.itemTag,
                "source".equals(key) ? "{}" : f.sourceItem, "sourceTag".equals(key) ? "other" : f.sourceTag,
                f.x + ("x".equals(key) ? .01 : "nearX".equals(key) ? .00001 : 0), f.feetY + ("y".equals(key) ? .01 : 0), f.z + ("z".equals(key) ? .01 : 0),
                f.eyeOffset + ("eye".equals(key) ? .01 : 0), f.width + ("width".equals(key) ? .01 : 0), f.height + ("height".equals(key) ? .01 : 0),
                f.reach + ("reach".equals(key) ? .01 : 0), f.actorBody.getOffsetBoundingBox("body".equals(key) ? .01 : 0, 0, 0), f.yaw + ("yaw".equals(key) ? 1 : 0), f.pitch + ("pitch".equals(key) ? 1 : 0), f.captureWorldTick + 1,
                "slot".equals(key) ? 6 : f.selectedSlot, "flight".equals(key) ? !f.flying : f.flying, "sneak".equals(key) ? !f.sneaking : f.sneaking,
                "cursor".equals(key) ? !f.cursorEmpty : f.cursorEmpty, "screen".equals(key) ? new Object() : f.screenIdentity,
                "screen".equals(key) || "screenId".equals(key) ? "other-screen" : f.screenId);
    }
    private static void queues() throws Exception {
        GameQueue client = new GameQueue(4), server = new GameQueue(4); AtomicBoolean running = new AtomicBoolean(true);
        Thread clientThread = new Thread(() -> pump(client, running), "approach-client-fixture"), serverThread = new Thread(() -> pump(server, running), "approach-server-fixture");
        clientThread.start(); serverThread.start();
        try {
            GameQueue.Control control = new GameQueue.Control(); StringBuilder order = new StringBuilder(); ApproachPlanner.Frame frame = frame(args("use", "surround"));
            ApproachPlanner.Search search = new ApproachPlanner.Search(block(0, 64, 0, "fixture:solid", 0, "integrated_server"), flight(), 0);
            GuardedContext.Result result = ApproachPlanner.dispatch(client, server, control, new ApproachPlanner.Stages() {
                public ApproachPlanner.Frame capture() { check(Thread.currentThread() == clientThread, "capture belongs to CLIENT queue"); order.append('C'); return frame; }
                public ApproachPlanner.Search search(ApproachPlanner.Frame captured, ApproachPlanner.Search previous) {
                    check(Thread.currentThread() == serverThread && captured == frame, "authoritative scalar planning belongs to SERVER queue");
                    check(!control.cancelBeforeExecution(), "all stages retain the same started Control rather than claiming cancellability"); order.append('S'); return search;
                }
                public GuardedContext.Result finish(ApproachPlanner.Frame captured, ApproachPlanner.Search planned) {
                    check(Thread.currentThread() == clientThread && captured == frame && planned == search, "fresh native confirmation belongs to CLIENT queue");
                    order.append('F'); JsonObject data = new JsonObject(); data.addProperty("readOnly", true); return new GuardedContext.Result(data, null);
                }
            });
            check("CSF".equals(order.toString()) && result.data.get("readOnly").getAsBoolean(), "HTTP orchestration awaits three owning stages in order without game-thread cross waits");
            pagedQueues(client, server, clientThread, serverThread, frame);
            GameQueue.Control cancelled = new GameQueue.Control(); cancelled.cancelBeforeExecution(); AtomicInteger calls = new AtomicInteger();
            try {
                ApproachPlanner.dispatch(client, server, cancelled, new ApproachPlanner.Stages() {
                    public ApproachPlanner.Frame capture() { calls.incrementAndGet(); return frame; }
                    public ApproachPlanner.Search search(ApproachPlanner.Frame f, ApproachPlanner.Search previous) { calls.incrementAndGet(); return search; }
                    public GuardedContext.Result finish(ApproachPlanner.Frame f, ApproachPlanner.Search s) { calls.incrementAndGet(); return result; }
                }); throw new AssertionError("cancelled composite must fail");
            } catch (ExecutionException failure) { check(failure.getCause() instanceof BridgeException && "cancelled".equals(((BridgeException) failure.getCause()).code) && calls.get() == 0,
                    "before-start cancellation prevents every stage without submitting an action"); }
        } finally { running.set(false); clientThread.join(1000); serverThread.join(1000); }
        check(!clientThread.isAlive() && !serverThread.isAlive(), "simulation queue owners terminate after the bounded test");
    }
    private static void pagedQueues(GameQueue client, GameQueue server, Thread clientThread, Thread serverThread, ApproachPlanner.Frame frame) throws Exception {
        GameQueue.Control control = new GameQueue.Control(); StringBuilder order = new StringBuilder(); AtomicLong clock = new AtomicLong();
        AtomicInteger pages = new AtomicInteger(), finishes = new AtomicInteger(); Fixture cold = new Fixture(frame, "integrated_server"); cold.clock = clock; cold.unknownFirst = true;
        GuardedContext.Result ready = ApproachPlanner.dispatch(client, server, control, new ApproachPlanner.Stages() {
            public ApproachPlanner.Frame capture() { check(Thread.currentThread() == clientThread, "each continuation recaptures on CLIENT"); order.append('C'); return frame; }
            public ApproachPlanner.Search search(ApproachPlanner.Frame captured, ApproachPlanner.Search previous) {
                check(Thread.currentThread() == serverThread && !control.cancelBeforeExecution(), "each continuation uses SERVER and the same started Control");
                cold.bodyCost = pages.incrementAndGet() == 1 ? ApproachPlanner.WORK_NANOS : 0; order.append('S');
                return ApproachPlanner.search(cold, captured, flight(), previous, clock::get);
            }
            public GuardedContext.Result finish(ApproachPlanner.Frame captured, ApproachPlanner.Search search) {
                check(Thread.currentThread() == clientThread && captured == frame, "final native confirmation retains the original capture identity");
                finishes.incrementAndGet(); order.append('F'); return new GuardedContext.Result(ApproachPlanner.finish(new Fixture(frame, "client"), frame, search, 41, clock::get), null);
            }
        });
        check("CSCSF".equals(order.toString()) && pages.get() == 2 && finishes.get() == 1 && "ready".equals(ready.data.get("status").getAsString()),
                "one HTTP read safely recaptures between a cold partial server page and its successful bounded continuation");
        AtomicInteger captures = new AtomicInteger(), neverFinishes = new AtomicInteger(); AtomicLong boundedClock = new AtomicLong();
        Fixture bounded = new Fixture(frame, "integrated_server"); bounded.clock = boundedClock; bounded.bodyCost = ApproachPlanner.WORK_NANOS;
        GuardedContext.Result partial = ApproachPlanner.dispatch(client, server, new GameQueue.Control(), new ApproachPlanner.Stages() {
            public ApproachPlanner.Frame capture() { captures.incrementAndGet(); return frame; }
            public ApproachPlanner.Search search(ApproachPlanner.Frame captured, ApproachPlanner.Search previous) { return ApproachPlanner.search(bounded, captured, flight(), previous, boundedClock::get); }
            public GuardedContext.Result finish(ApproachPlanner.Frame captured, ApproachPlanner.Search search) {
                neverFinishes.incrementAndGet(); return new GuardedContext.Result(ApproachPlanner.finish(new Fixture(frame, "client"), frame, search, 41, boundedClock::get), null);
            }
        });
        check(captures.get() == 4 && bounded.spaces == 4 && neverFinishes.get() == 1 && "partial".equals(partial.data.get("status").getAsString())
                && partial.data.get("selected").isJsonNull() && partial.data.get("nextOffset").getAsInt() == 0,
                "persistent cold budget exhaustion stops after four pages and preserves explicit partial refusal");
        AtomicInteger driftCaptures = new AtomicInteger(), driftSearches = new AtomicInteger(); AtomicLong driftClock = new AtomicLong();
        Fixture drift = new Fixture(frame, "integrated_server"); drift.clock = driftClock; drift.bodyCost = ApproachPlanner.WORK_NANOS;
        try {
            ApproachPlanner.dispatch(client, server, new GameQueue.Control(), new ApproachPlanner.Stages() {
                public ApproachPlanner.Frame capture() { return driftCaptures.incrementAndGet() == 1 ? frame : changed(frame, "screen"); }
                public ApproachPlanner.Search search(ApproachPlanner.Frame captured, ApproachPlanner.Search previous) { driftSearches.incrementAndGet(); return ApproachPlanner.search(drift, captured, flight(), previous, driftClock::get); }
                public GuardedContext.Result finish(ApproachPlanner.Frame captured, ApproachPlanner.Search search) { throw new AssertionError("changed context must not reach final confirmation"); }
            }); throw new AssertionError("changed continuation context must fail");
        } catch (BridgeException failure) { check("stale_world".equals(failure.code) && driftCaptures.get() == 2 && driftSearches.get() == 1,
                "an intervening screen change refuses the continuation before its next server task"); }
        AtomicInteger cappedCaptures = new AtomicInteger(), cappedSearches = new AtomicInteger();
        GuardedContext.Result capped = ApproachPlanner.dispatch(client, server, new GameQueue.Control(), new ApproachPlanner.Stages() {
            public ApproachPlanner.Frame capture() { cappedCaptures.incrementAndGet(); return frame; }
            public ApproachPlanner.Search search(ApproachPlanner.Frame captured, ApproachPlanner.Search previous) {
                cappedSearches.incrementAndGet(); ApproachPlanner.Search search = new ApproachPlanner.Search(block(0, 64, 0, "fixture:solid", 0, "integrated_server"), flight(), 0);
                search.partial = true; search.rays = ApproachPlanner.MAX_RAYS; return search;
            }
            public GuardedContext.Result finish(ApproachPlanner.Frame captured, ApproachPlanner.Search search) { return new GuardedContext.Result(ApproachPlanner.finish(new Fixture(frame, "client"), frame, search, 41, () -> 0), null); }
        });
        check(cappedCaptures.get() == 1 && cappedSearches.get() == 1 && "partial".equals(capped.data.get("status").getAsString()),
                "the shared global native ray cap stops continuation instead of resetting a per-page budget");
    }
    private static void pump(GameQueue queue, AtomicBoolean running) { while (running.get()) { queue.drain(4); LockSupport.parkNanos(100000); } }
    private static JsonObject finish(ApproachPlanner.Frame frame, Fixture server, Fixture client) {
        ApproachPlanner.Search search = ApproachPlanner.search(server, frame, flight(), () -> 0); search.target.addProperty("worldTick", 40);
        return ApproachPlanner.finish(client, frame, search, 41, () -> 0);
    }
    private static JsonObject flight() {
        JsonObject result = new JsonObject(); result.addProperty("source", "integrated_server"); result.addProperty("worldTick", 40);
        result.addProperty("dimension", 0); result.addProperty("available", true); result.addProperty("creative", true); result.addProperty("flying", true);
        result.addProperty("normalReach", 5); result.addProperty("playerId", "uuid"); return result;
    }
    private static JsonObject gt(int id, String type) { JsonObject result = new JsonObject(); result.addProperty("metaTileId", id); result.addProperty("metaClass", type); return result; }
    private static JsonObject block(int x, int y, int z, String registry, int metadata, String source) {
        JsonObject result = new JsonObject(); result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z);
        result.addProperty("registryName", registry); result.addProperty("metadata", metadata); result.addProperty("dimension", 0); result.addProperty("source", source); return result;
    }
    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }
    /** Full native view rays can extend past the target; intersect actual fixture cubes rather than the endpoint cell. */
    private static final class GoalFixture extends Fixture {
        GoalFixture(ApproachPlanner.Frame frame, String source) { super(frame, source); }
        public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            if (nativeShape != null) return super.trace(start, point);
            traces++; loadsAtLastTrace = loads;
            if (noHit) return null;
            MovingObjectPosition nearest = null; double distance = Double.POSITIVE_INFINITY;
            for (ApproachPlanner.Request target : frame.request.sharedTargets) {
                MovingObjectPosition hit = AxisAlignedBB.getBoundingBox(target.x,target.y,target.z,target.x+1,target.y+1,target.z+1).calculateIntercept(start,point);
                if(hit!=null && start.distanceTo(hit.hitVec)<distance) {
                    distance=start.distanceTo(hit.hitVec);
                    nearest=new MovingObjectPosition(target.x,target.y,target.z,wrongFace ? hit.sideHit^1 : hit.sideHit,hit.hitVec);
                }
            }
            if(nearest!=null) { start.xCoord=nearest.hitVec.xCoord;start.yCoord=nearest.hitVec.yCoord;start.zCoord=nearest.hitVec.zCoord; }
            return nearest;
        }
    }
    private static class Fixture implements ApproachPlanner.Access {
        final ApproachPlanner.Frame frame; final String source; final Set<String> missing = new HashSet<String>();
        int states, traces, spaces, continuousSpaces, originalStarts, loads, loadsAtLastTrace; boolean unknownFirst, hazard, omitHazard, wrongFace, noHit, invalidBodyY, canStand = true;
        long bodyCost, rayCost; AtomicLong clock; String registryOverride; int rawMetadata;
        AxisAlignedBB nativeShape, placementBodyOverride; boolean placementBodyUnavailable, nativeAdvance;
        int rayX, rayY, rayZ;
        Fixture(ApproachPlanner.Frame frame, String source) {
            this.frame = frame; this.source = source;
            rayX=frame.request.x; rayY=frame.request.y; rayZ=frame.request.z;
        }
        public boolean loaded(int x, int y, int z) { loads++; return !missing.contains(key(x, y, z)); }
        public NativePlacementAdapter.State state(int x, int y, int z) {
            states++; boolean target = x == frame.request.x && y == frame.request.y && z == frame.request.z;
            boolean air = target && "place_block".equals(frame.request.intent);
            if (!air) { rayX=x; rayY=y; rayZ=z; }
            JsonObject description = block(x, y, z, registryOverride != null && target ? registryOverride : air ? "minecraft:air" : "fixture:solid", air ? 0 : rawMetadata, source);
            return new NativePlacementAdapter.State(air, air, description);
        }
        public AxisAlignedBB placementBody(double x, double y, double z, boolean currentOnly) {
            if (placementBodyUnavailable) return null;
            AxisAlignedBB actual = placementBodyOverride == null ? frame.actorBody : placementBodyOverride;
            return currentOnly ? actual : actual.getOffsetBoundingBox(x - frame.x, y - frame.feetY, z - frame.z);
        }
        public JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline) {
            continuousSpaces++;
            JsonObject result = space((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), width, height, deadline);
            result.add("body", PlayerNavigation.bounds(AxisAlignedBB.getBoundingBox(x - width / 2, y, z - width / 2, x + width / 2, y + height, z + width / 2)));
            return result;
        }
        public JsonObject space(int x, int y, int z, double width, double height, long deadline) {
            spaces++; invalidBodyY |= y < 0 || y + height > 256;
            if (clock != null) clock.addAndGet(bodyCost);
            JsonObject body = new JsonObject(), box = new JsonObject(); box.addProperty("minX", x + .5 - width / 2); box.addProperty("minY", y);
            box.addProperty("minZ", z + .5 - width / 2); box.addProperty("maxX", x + .5 + width / 2); box.addProperty("maxY", y + height); box.addProperty("maxZ", z + .5 + width / 2);
            body.add("body", box); body.addProperty("loaded", !(unknownFirst && spaces == 1)); body.addProperty("status", "observed"); body.addProperty("occupiable", true);
            body.addProperty("canStand", canStand); body.addProperty("fullSupportCoverage", canStand); body.addProperty("supported", canStand); body.addProperty("collisionCount", 0); body.addProperty("entityCoveragePartial", false);
            body.addProperty("liquid", false); if (!omitHazard) body.addProperty("hazard", hazard); return body;
        }
        public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            traces++; loadsAtLastTrace = loads; if (clock != null) clock.addAndGet(rayCost);
            // These reach/body fixtures expose one isolated cube per observed target.
            // The native ray may extend beyond that cube; its endpoint is not its identity.
            int x = rayX, y = rayY, z = rayZ;
            if (nativeShape == null && !frame.request.sharedTargets.isEmpty()) {
                double closest = Double.POSITIVE_INFINITY;
                for (ApproachPlanner.Request target : frame.request.sharedTargets) {
                    MovingObjectPosition candidate = AxisAlignedBB.getBoundingBox(target.x,target.y,target.z,target.x+1,target.y+1,target.z+1).calculateIntercept(start,point);
                    if (candidate != null && start.distanceTo(candidate.hitVec) < closest) {
                        closest=start.distanceTo(candidate.hitVec); x=target.x; y=target.y; z=target.z;
                    }
                }
            }
            if (nativeShape != null) { x = frame.request.x; y = frame.request.y; z = frame.request.z; }
            if (Math.abs(start.xCoord - .5 - Math.floor(start.xCoord)) < .00001 && Math.abs(start.zCoord - .5 - Math.floor(start.zCoord)) < .00001) originalStarts++;
            MovingObjectPosition geometry = noHit ? null : (nativeShape == null ? AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1) : nativeShape).calculateIntercept(start, point);
            if (geometry == null) return null;
            if (nativeAdvance) {
                // Vanilla DDA advances to the entry plane before the block's native ray callback.
                // Real Vec3.getIntermediateWith* rejects a remaining axis segment below its epsilon.
                geometry=(nativeShape==null?AxisAlignedBB.getBoundingBox(x,y,z,x+1,y+1,z+1):nativeShape).calculateIntercept(geometry.hitVec,point);
                if (geometry==null) return null;
            }
            MovingObjectPosition hit = new MovingObjectPosition(x, y, z, wrongFace ? geometry.sideHit ^ 1 : geometry.sideHit, geometry.hitVec);
            start.xCoord = hit.hitVec.xCoord; start.yCoord = hit.hitVec.yCoord; start.zCoord = hit.hitVec.zCoord; return hit;
        }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable task) { try { task.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code + ", actual " + error.code); } }
}
