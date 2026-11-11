package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.world.WorldBlockCache;
import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Scalar fixtures only. Actual native walking, flight acquisition and command arrival require live testing. */
public final class PlayerNavigationSelfTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("PlayerNavigationSelfTest: " + run() + " checks passed"); }
    public static int run() { checks = 0; travelView(); requests(); genericGoalsAndSegments(); contexts(); walkingBrakeBindings(); walkingPlanning(); flightPlanning(); normalFlightKeys(); flightFeedback(); verticalSettling(); horizontalSettling(); groundedFlightInertia(); rectangularFlightBodies(); flightGeometryDiagnostics(); flightProtocolBoundary(); actualArrival(); directHoverAck(); nearLevelHoverRoute(); arrivalTraceProvenance(); actualPoseReport(); compactSuccess(); currentOnly(); return checks; }

    private static void walkingBrakeBindings() {
        walkingStartSettling();
        long dispatched = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(123);
        PlayerNavigation.State waited = new PlayerNavigation.State(new PlayerNavigation.Request(args("walking",1.5)),
                frame(.5,10,false,true),new Object(),new Object(),"uuid",dispatched);
        check(waited.started == dispatched && waited.deadline == dispatched + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waited.request.timeout),
                "Native eye observation shares the original navigation deadline; constructing movement state never resets its budget.");
        check(waited.held.isEmpty() && !waited.mutation, "Eye preparation does not itself dispatch movement input.");
        safeFlightInputBraking();
        crosshairStopAndArrival();
        walkingCoastAndArrival();
        PlayerNavigation.State walking = state("walking", 1.5);
        int code = 1; for (String name : PlayerNavigation.MOVEMENT_KEYS) walking.keyCodes.put(name, code++);
        GroundedStep step = new GroundedStep(new PlayerNavigation.Point(.5, 64, .5), new PlayerNavigation.Point(1.5, 65, .5), .6, 1.8);
        step.nativeJumpEdges = 1;
        JsonObject overshoot = frame(1.25, 11, false, false);
        overshoot.getAsJsonObject("player").addProperty("feetY", 65);
        overshoot.getAsJsonObject("player").addProperty("motionX", .18);
        java.util.Set<String> brake = step.next(overshoot);
        check(brake.contains("back"), "Ordinary walking height transitions need real reverse input to brake native momentum.");
        walking.requireBoundKeys(brake);
        check(walking.keyCodes.get("back") != null, "Native walking owns its height controller's reverse brake before any input.");
        check(walking.keyCodes.get("left") != null && walking.keyCodes.get("right") != null,
                "Every grounded transition owns both lateral correction bindings before native input");
        walking.requireBoundKeys(java.util.Collections.singleton("left"));
        walking.requireBoundKeys(java.util.Collections.singleton("right"));
        walking.keyCodes.remove("back");
        expect("invalid_state", () -> walking.requireBoundKeys(brake));
        check(walking.held.isEmpty() && !walking.mutation, "Missing key registration refuses before native input or partial ownership.");
        expect("invalid_state", () -> walking.requireBoundKeys(java.util.Collections.singleton("attack")));
        walkingCornerAlignment();
    }
    private static void walkingStartSettling() {
        JsonObject initial=frame(.5,10,false,true);
        initial.getAsJsonObject("player").addProperty("nativeMoveForward",0);
        initial.getAsJsonObject("player").addProperty("nativeMoveStrafing",0);
        NavigationStartSettle wait=new NavigationStartSettle(initial);
        initial.getAsJsonObject("player").addProperty("motionX",.03);
        check(wait.observe(initial,1)==null,"Released keys with remaining inertia do not bind a walking start.");
        initial.getAsJsonObject("player").addProperty("motionX",0);
        check(wait.observe(initial,2)==null && wait.observe(initial,2)==null,"Two reads in one tick are not two quiet native ticks.");
        initial.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("motionZ",.01);
        check(wait.observe(initial,3)==null,"Server residual motion resets the quiet sequence.");
        initial.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("motionZ",0);
        check(wait.observe(initial,4)==null && wait.observe(initial,5)!=null,"Two dual-owner quiet ticks establish the new start without input.");
        initial.getAsJsonObject("player").addProperty("x",4);
        expect("navigation_start_changed",()->wait.observe(initial,6));
    }
    private static void walkingCornerAlignment() {
        fastWalkingCorner();
        PlayerNavigation.State state = state("walking", 3.5); prepareClear(state);
        state.route.clear(); state.route.add(new PlayerNavigation.Point(1.5, 64, .5)); state.route.add(new PlayerNavigation.Point(3.5, 64, 1.5));
        state.requireContext(frame(1.35,11,false,true));
        java.util.Set<String> correcting = state.next(11);
        check(state.waypoint == 0 && (correcting.contains("forward") || correcting.contains("back")),
                "A stopped body within final tolerance still aligns to the intermediate corner before following its next collision-proved segment");
        state.requireContext(frame(1.49,12,false,true)); state.next(12);
        check(state.waypoint == 1 && state.walkingTolerance() == PlayerNavigation.TOLERANCE,
                "Tight corner alignment advances normally and preserves the original final arrival tolerance");
        for (double angle : new double[] {0, .1, .7, 1.8}) {
            double x=0,z=0,vx=0,vz=0; int ticks=0;
            PlayerNavigation.Point target=new PlayerNavigation.Point(1.5*Math.cos(angle),64,1.5*Math.sin(angle));
            for (; ticks<160; ticks++) {
                PlayerNavigation.Point actual=new PlayerNavigation.Point(x,64,z);
                if(actual.horizontal(target)<=.035 && Math.hypot(vx,vz)<.005)break;
                PlayerNavigation.HorizontalFlightInput input=PlayerNavigation.walkingInput(actual,target,vx,vz,.546,.098*.3F,.035);
                if(Math.abs(vx)<.005)vx=0;if(Math.abs(vz)<.005)vz=0;
                float nativeAngle=input.yaw*(float)Math.PI/180F;
                vx += -(double)((float)(.098*.3F)*MathHelper.sin(nativeAngle))*input.first;
                vz += (double)((float)(.098*.3F)*MathHelper.cos(nativeAngle))*input.first;
                x+=vx;z+=vz;vx*=.546;vz*=.546;
            }
            check(ticks<160,"The existing finite native pulse model converges to tight diagonal/cardinal corner alignment without pose writes");
        }
        snappedShallowLeg();
    }
    private static void fastWalkingCorner() {
        simulatedFastWalkingCorner();
        segmentedWalkingCornerBoundaries();
        PlayerNavigation.State state=state("walking",3.5);prepareClear(state);
        state.route.clear();state.route.add(new PlayerNavigation.Point(1.5,64,.5));state.route.add(new PlayerNavigation.Point(3.5,64,1.5));
        state.walkingAcceleration=.6860000295926486;state.walkingFriction=.5460000216960907;
        JsonObject moving=frame(1.29236842344304,11,false,true);
        moving.getAsJsonObject("player").addProperty("motionX",.03);
        state.requireContext(moving);
        check(state.next(11).isEmpty() && !state.reanchorWalkingCorner(true),
                "Seven-times walking speed settles near a generated bend without reanchoring a moving body.");
        state.requireContext(frame(1.29236842344304,12,false,true));
        state.clientProbe=9;state.serverProbe=10;state.walkingProofSweeps=java.util.Collections.emptyList();
        check(!state.reanchorWalkingCorner(false),"Physical/native input must be idle before any corner adjustment.");
        check(state.reanchorWalkingCorner(true) && state.clientProbe==0 && state.serverProbe==0 && state.walkingProofSweeps==null,
                "The recorded accelerated-walking stall reanchors only after rest and invalidates both geometry proofs.");
        check(state.waypoint==0 && state.route.get(0).x==1.29236842344304 && state.request.destination.x==3.5,
                "Reanchoring is a route edit, not arrival or a player/final-goal position write.");
        check(!state.reanchorWalkingCorner(true),"Each automatic bend can be adjusted only once.");
        state.next(13);
        check(state.waypoint==1,"After fresh caller-owned proof, a stopped aligned bend advances normally.");
        check(!state.reanchorWalkingCorner(true),"A final endpoint never uses automatic bend tolerance.");
        PlayerNavigation.State explicit=state("walking",3.5);prepareClear(explicit);
        explicit.route.add(0,new PlayerNavigation.Point(1.5,64,.5));explicit.request.declared.add(explicit.route.get(0));
        explicit.walkingAcceleration=.686;explicit.requireContext(frame(1.29,12,false,true));
        check(!explicit.reanchorWalkingCorner(true),"Caller-declared intermediate waypoints remain exact.");
        PlayerNavigation.State normal=state("walking",3.5);prepareClear(normal);
        normal.route.add(0,new PlayerNavigation.Point(1.5,64,.5));normal.requireContext(frame(1.29,12,false,true));
        check(!normal.reanchorWalkingCorner(true),"Ordinary native walking retains tight corner alignment.");
    }
    private static void segmentedWalkingCornerBoundaries() {
        JsonObject request=args("walking",50.5);request.addProperty("segmented",true);
        PlayerNavigation.State state=new PlayerNavigation.State(new PlayerNavigation.Request(request),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        prepareClear(state);state.walkingAcceleration=.686;
        check(state.planningTargets.size()>1,"This fixture publishes multiple native planning windows.");
        PlayerNavigation.Point boundary=state.planningTargets.get(0);
        state.route.clear();state.route.add(boundary);state.route.add(state.request.destination);
        state.requireContext(frame(boundary.x-.2,12,false,true));
        check(!state.reanchorWalkingCorner(true),"A segmented window endpoint retains its original alignment contract.");
        state.route.add(0,new PlayerNavigation.Point(boundary.x-2,64,.5));
        state.requireContext(frame(boundary.x-2.2,13,false,true));
        state.clientProbe=4;state.serverProbe=5;state.walkingProofSweeps=java.util.Collections.emptyList();
        check(state.reanchorWalkingCorner(true) && state.clientProbe==0 && state.serverProbe==0 && state.walkingProofSweeps==null,
                "An internal automatic bend in that same window may reanchor and invalidates both complete route proofs.");
        check(state.route.get(1)==boundary && state.planningTargets.get(0)==boundary && state.request.destination.x==50.5,
                "Reanchoring an internal bend does not relocate a window endpoint or final target.");
    }
    private static void simulatedFastWalkingCorner() {
        for (boolean segmented : new boolean[] {false,true}) for (double acceleration : new double[] {.196, .49, .6860000295926486}) {
            JsonObject request=args("walking",9.5);request.addProperty("z",2.5);request.addProperty("segmented",segmented);
            PlayerNavigation.State state=new PlayerNavigation.State(new PlayerNavigation.Request(request),frame(.5,10,false,true),new Object(),new Object(),"uuid");
            prepareClear(state);
            state.route.clear();state.route.add(new PlayerNavigation.Point(7.5,64,.5));state.route.add(new PlayerNavigation.Point(9.5,64,2.5));
            state.walkingAcceleration=acceleration;state.walkingFriction=.5460000216960907;
            double x=.5,z=.5,vx=0,vz=0;int ticks=0;
            for (;ticks<180 && state.waypoint==0;ticks++) {
                JsonObject observed=frame(x,11+ticks,false,true);
                for (JsonObject actor:new JsonObject[]{observed.getAsJsonObject("player"),observed.getAsJsonObject("serverControl").getAsJsonObject("pose")}) {
                    actor.addProperty("z",z);actor.addProperty("motionX",vx);actor.addProperty("motionZ",vz);
                }
                state.requireContext(observed);
                if (state.reanchorWalkingCorner(state.held.isEmpty())) {
                    PlayerNavigation.Point corner=state.route.get(0),next=state.route.get(1);
                    WalkingPathPlanner.Sweep fresh=new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(corner.x,corner.y,corner.z),
                            new WalkingPathPlanner.Point(next.x,next.y,next.z),.6,1.8);
                    check(WalkingSweepProof.inspect(fresh,(piece,deadline)->WalkingPathPlanner.Check.CLEAR,Long.MAX_VALUE)==WalkingPathPlanner.Check.CLEAR,
                            "Adjusted open fixture corridor receives fresh swept-body proof.");
                    check(WalkingSweepProof.inspect(fresh,(piece,deadline)->WalkingPathPlanner.Check.BLOCKED,Long.MAX_VALUE)==WalkingPathPlanner.Check.BLOCKED,
                            "The same adjusted route with changed blocking geometry is refused by the reused proof.");
                    continue;
                }
                java.util.Set<String> keys=state.next(11+ticks);
                state.dispatched(keys,11+ticks);state.held.clear();state.held.addAll(keys);
                if(Math.abs(vx)<.005)vx=0;if(Math.abs(vz)<.005)vz=0;
                float force=(float)(acceleration*(keys.contains("sneak")?.3F:1));
                float angle=(float)state.steeringYaw*(float)Math.PI/180F;
                int input=keys.contains("forward")?1:keys.contains("back")?-1:0;
                vx-=force*MathHelper.sin(angle)*input;vz+=force*MathHelper.cos(angle)*input;
                x+=vx;z+=vz;vx*=state.walkingFriction;vz*=state.walkingFriction;
            }
            check(ticks<180 && state.waypoint==1,"Accelerated native key/drag simulation clears an automatic bend without the old oscillation: "+acceleration+", segmented="+segmented);
        }
    }
    private static void snappedShallowLeg() {
        AxisAlignedBB terminal=AxisAlignedBB.getBoundingBox(305.125,4.125,108,305.875,4.875,108.125);
        for (double initialOffset : new double[] {0, -.02, .02}) {
            double x=314,z=108+initialOffset,vx=0,vz=0;int tick=0;
            PlayerNavigation.Point target=new PlayerNavigation.Point(301.5,4,107.5);
            for (;tick<220;tick++) {
                PlayerNavigation.Point current=new PlayerNavigation.Point(x,4,z);
                if(current.horizontal(target)<=.035 && Math.hypot(vx,vz)<.005)break;
                double acceleration=.098*(current.horizontal(target)<.098/(1-.546)+.3 ? .3F : 1);
                PlayerNavigation.HorizontalFlightInput input=PlayerNavigation.walkingInput(current,target,vx,vz,.546,acceleration,.035);
                if(Math.abs(vx)<.005)vx=0;if(Math.abs(vz)<.005)vz=0;
                float a=input.yaw*(float)Math.PI/180F;
                vx += -(double)((float)acceleration*MathHelper.sin(a))*input.first;
                vz += (double)((float)acceleration*MathHelper.cos(a))*input.first;
                double nextX=x+vx,nextZ=z+vz;
                WalkingPathPlanner.Sweep sweep=new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(x,4,z),new WalkingPathPlanner.Point(nextX,4,nextZ),.6,1.8);
                WalkingPathPlanner.Check safe=WalkingSweepProof.inspect(sweep,(piece,deadline)->piece.body().intersectsWith(terminal)?WalkingPathPlanner.Check.BLOCKED:WalkingPathPlanner.Check.CLEAR,Long.MAX_VALUE);
                check(safe==WalkingPathPlanner.Check.CLEAR,"Native per-axis snap simulation must preserve the recorded shallow route's full-body clearance around the thin AE terminal");
                x=nextX;z=nextZ;vx*=.546;vz*=.546;
            }
            check(tick<220,"Native shallow-angle walking converges after preserving clearance, including bounded initial corner error");
        }
    }
    private static void safeFlightInputBraking() {
        recordedVerticalFeedback(new PlayerNavigation.Point(-657.7846436562056,114.3500134719837,-919.0391310882104),
                new PlayerNavigation.Point(-657.7496150883013,112,-918.9930715894244), null,
                AxisAlignedBB.getBoundingBox(-659,110,-920,-657,111,-918), "final_level_flight");
        serverFlightInputBraking();
        boundedFlightCorridor();
        nativeHorizontalPulseModels();
        nativeHeightPulseWindows();
        PlayerNavigation.State flight = state("ability_flight",9.5); prepareFlight(flight);
        flight.requireContext(frame(.5,11,true,false)); flight.flightObserved = true;
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(); keys.add("forward");
        check(!flight.brakeRejectedFlightInput(keys,()->false,12) && keys.contains("forward") && flight.blockedFlightInputsBraked == 0,
                "Unknown or partial no-input proof cannot erase a blocked flight request.");
        check(flight.brakeRejectedFlightInput(keys,()->true,13) && keys.isEmpty() && flight.blockedFlightInputsBraked == 1,
                "Only a fresh clear no-input envelope releases powered movement without rejecting the whole route.");
        check(!flight.acknowledged(), "A safe brake is never arrival or interaction acceptance.");
        keys.add("forward");
        try { flight.brakeRejectedFlightInput(keys,()->{ throw new BridgeException("unsupported","Coast blocked"); },14); throw new AssertionError("Unsafe coast accepted"); }
        catch (BridgeException error) { check("unsupported".equals(error.code) && keys.contains("forward"), "Unsafe coast keeps its collision error and never claims a dispatched brake."); }
        flight.flightObserved = false;
        check(!flight.brakeRejectedFlightInput(keys,()->{throw new AssertionError("No flight proof");},15), "Unconfirmed flight cannot select the airborne coasting alternative.");
        flight.flightObserved=true; keys.clear(); keys.add("forward"); keys.add("jump"); keys.add("sneak");
        check(flight.brakeRejectedFlightInput(keys,()->true,16) && keys.size()==2 && keys.contains("jump") && keys.contains("sneak"),
                "A freshly proved altitude-neutral brake preserves jump/sneak while releasing every horizontal input.");
        PlayerNavigation.State pulses=state("ability_flight",9.5); prepareFlight(pulses); pulses.flightObserved=true;
        pulses.route.clear(); pulses.route.add(new PlayerNavigation.Point(2.5,64,.5)); pulses.requireContext(frame(.5,11,true,false));
        java.util.Set<String> first=pulses.next(20,.25);
        check(first.contains("forward") || first.contains("back"),"A short-route precision pulse initially requests horizontal movement.");
        pulses.dispatched(first,20); pulses.held.addAll(first);
        for(long tick=21;tick<=23;tick++) {
            java.util.Set<String> idle=pulses.next(tick,.25);
            check(!idle.contains("forward") && !idle.contains("back") && idle.contains("jump") && idle.contains("sneak"),
                    "Each precision pulse retains three real horizontal-idle ticks before another acceleration can accumulate.");
            pulses.dispatched(idle,tick); pulses.held.clear(); pulses.held.addAll(idle);
        }
        check(pulses.next(24,.25).contains("forward") || pulses.next(24,.25).contains("back"),
                "The next precision pulse is available after its bounded coast observation window.");
        PlayerNavigation.State blocked=state("ability_flight",9.5); prepareFlight(blocked); blocked.flightObserved=true;
        blocked.requireContext(frame(.5,11,true,false));
        for(int tick=20;tick<27;tick++) {
            java.util.Set<String> rejected=new java.util.LinkedHashSet<String>(); rejected.add("forward");
            check(blocked.brakeRejectedFlightInput(rejected,()->true,tick), "Only finite stationary brake observations are accepted.");
        }
        expect("flight_input_blocked",()->blocked.brakeRejectedFlightInput(new java.util.LinkedHashSet<String>(java.util.Collections.singleton("forward")),()->true,27));
        check(!blocked.acknowledged(),"Blocked precision/coast cannot claim arrival or request an interaction.");
    }
    private static void serverFlightInputBraking() {
        PlayerNavigation.Point actual=new PlayerNavigation.Point(120.17037660076298,7.298483200000003,64.69870108750195);
        JsonObject request=args("ability_flight",104.5); request.addProperty("y",5); request.addProperty("z",48.5); request.addProperty("finishFlying",true);
        PlayerNavigation.State flight=new PlayerNavigation.State(new PlayerNavigation.Request(request),corridorFrame(actual,0,0,10),new Object(),new Object(),"uuid");
        prepareFlight(flight); flight.flightObserved=true;
        flight.route.clear(); flight.route.add(new PlayerNavigation.Point(104.5,5,48.5)); flight.route.add(new PlayerNavigation.Point(101.5,5,46.5));
        flight.requireContext(corridorFrame(actual,0,0,11));
        java.util.Set<String> keys=new java.util.LinkedHashSet<String>(); keys.add("forward"); keys.add("sneak");
        AxisAlignedBB controller=AxisAlignedBB.getBoundingBox(120,6,64,121,7,65);
        List<FlightPathPlanner.Sweep> sweeps=PlayerNavigation.inertiaSweeps(actual,0,0,0,keys,-222.6382598876953,.05,true,Double.NaN,.6,1.8);
        for(FlightPathPlanner.Sweep piece:sweeps.get(0).pieces)
            check(!sweepBox(piece).intersectsWith(controller),"Recorded server pose has a clear unpowered coast above the controller.");
        boolean collision=false; for(FlightPathPlanner.Sweep piece:sweeps.get(1).pieces) collision|=sweepBox(piece).intersectsWith(controller);
        check(collision,"Recorded server downward pulse genuinely intersects the controller; it must not be accepted as clear.");
        JsonObject blockers=new JsonObject(); blockers.addProperty("status","observed"); blockers.addProperty("loaded",true);
        blockers.addProperty("occupiable",false); blockers.addProperty("entityCoveragePartial",false);
        blockers.addProperty("liquid",false); blockers.addProperty("hazard",false);
        JsonObject rejection=new JsonObject(); rejection.addProperty("side","server"); rejection.addProperty("stage","controlled"); rejection.add("blockers",blockers);
        flight.serverSafetyAt=100;
        check(!flight.requestServerFlightBrake(keys,rejection,()->false) && !flight.serverFlightBrakePending() && flight.serverSafetyAt==0,
                "Partial server coast cannot publish a safe brake or retain an old powered-input proof.");
        expect("unsupported",()->flight.requestServerFlightBrake(keys,rejection,()->{throw new BridgeException("unsupported","Actual coast blocked");}));
        check(!flight.serverFlightBrakePending(),"Unsafe actual coast still fails without a deferred recovery.");
        for(String stage:new String[]{"coast","route","landing_support"}) {
            rejection.addProperty("stage",stage);
            check(!flight.requestServerFlightBrake(keys,rejection,()->{throw new AssertionError("Not a controlled descent");}),
                    "Route, residual-coast and landing failures cannot be hidden by the server brake.");
        }
        rejection.addProperty("stage","controlled"); blockers.addProperty("entityCoveragePartial",true);
        check(!flight.requestServerFlightBrake(keys,rejection,()->true),"Incomplete geometry cannot become a server brake.");
        blockers.addProperty("entityCoveragePartial",false);
        check(flight.requestServerFlightBrake(keys,rejection,()->true) && flight.serverFlightBrakePending()
                && flight.blockedFlightInputsBraked==0 && flight.held.isEmpty(),
                "Server publishes only a copied request, never client input or arrival state.");
        keys.clear(); rejection.addProperty("stage","changed_after_publication");
        flight.applyServerFlightBrake(()->false,12);
        check(flight.serverFlightBrakePending() && flight.serverFlightBrakes==0,"Partial client coast retains the request with inputs released by its caller.");
        expect("unsupported",()->flight.applyServerFlightBrake(()->{throw new BridgeException("unsupported","Client coast blocked");},13));
        flight.applyServerFlightBrake(()->true,14);
        check(!flight.serverFlightBrakePending() && flight.serverFlightBrakes==1 && flight.levelFlightWaypoint==0
                && flight.levelFlightY==actual.y && "controlled".equals(PlayerNavigation.text(flight.lastBrakedFlightBody,"stage")),
                "Fresh client coast reuses the existing level-flight brake and preserves the exact server rejection.");
        check(!flight.acknowledged() && flight.destination().y==5,"Braking never changes route coordinates or claims actual arrival.");
        keys.add("forward"); keys.add("sneak"); rejection.addProperty("stage","controlled");
        check(flight.requestServerFlightBrake(keys,rejection,()->true),"A later server observation can independently request a brake.");
        flight.waypoint=1;
        flight.applyServerFlightBrake(()->{throw new AssertionError("Stale waypoint");},15);
        check(!flight.serverFlightBrakePending() && flight.serverFlightBrakes==1,"A stale waypoint request only releases inputs and requires fresh proof.");
    }
    private static void boundedFlightCorridor() {
        PlayerNavigation.Point current=new PlayerNavigation.Point(-64.81770970569698,156.82251199999996,-112.23217394831545);
        java.util.Set<String> keys=new java.util.LinkedHashSet<String>(); keys.add("forward");
        List<FlightPathPlanner.Sweep> envelopes=PlayerNavigation.inertiaSweeps(current,0,0,0,keys,60.81589126586914,.25,true,Double.NaN,.6,1.8);
        AxisAlignedBB blocker=AxisAlignedBB.getBoundingBox(-65,156,-111,-64,158,-110);
        FlightPathPlanner.Sweep powered=envelopes.get(1);
        check(sweepBox(powered).intersectsWith(blocker),"Reported production blocker intersects the old aggregate diagonal bounds.");
        check(powered.pieces!=null && powered.pieces.size()>1 && powered.pieces.size()<=512,"Actual full coast is represented by a bounded continuous sequence.");
        for(FlightPathPlanner.Sweep piece:powered.pieces) {
            check(!sweepBox(piece).intersectsWith(blocker),"Diagonal native trajectory does not visit the aggregate box's blocker corner.");
            check(Math.hypot(piece.to.x-piece.from.x,piece.to.z-piece.from.z)<=FlightPathPlanner.SWEEP_STEP+.000001,
                    "No gap or unbounded native displacement is hidden by a narrowed body check.");
        }
        AxisAlignedBB realBlocker=AxisAlignedBB.getBoundingBox(-66.7,156,-111.6,-66.4,160,-111.3);
        boolean hits=false; for(FlightPathPlanner.Sweep piece:powered.pieces) hits|=sweepBox(piece).intersectsWith(realBlocker);
        check(hits,"A genuine diagonal trajectory blocker still rejects powered input.");
        List<FlightPathPlanner.Sweep> vertical=PlayerNavigation.inertiaSweeps(current,.1,.2,.1,java.util.Collections.singleton("jump"),0,.25,true,Double.NaN,.6,1.8);
        for(FlightPathPlanner.Sweep piece:vertical.get(1).pieces)
            check(piece.feetY==vertical.get(1).feetY && piece.height==vertical.get(1).height,"Every horizontal slice retains the complete conservative vertical/headroom envelope.");
        for(boolean armor:new boolean[]{false,true}) recordedCorridorFeedback(current,blocker,armor);
        recordedDescentFeedback();
        verticalPulseEnvelopes();
    }
    private static void verticalPulseEnvelopes() {
        for(double speed:new double[]{.05,.25}) for(double motion:new double[]{-.3,-.005,-.0049,0,.0049,.005,.3})
                for(int direction:new int[]{-1,0,1}) {
            java.util.Set<String> keys=new java.util.LinkedHashSet<String>();
            if(direction<0) keys.add("sneak"); else if(direction>0) keys.add("jump");
            FlightPathPlanner.Sweep proof=PlayerNavigation.inertiaSweeps(new PlayerNavigation.Point(0,4,0),0,motion,0,
                    keys,0,speed,true,Double.NaN,.6,1.8).get(1);
            double y=4,velocity=motion;
            for(int tick=0;tick<48;tick++) {
                if(tick==0) velocity+=direction*.15;
                if(Math.abs(velocity)<.005) velocity=0;
                y+=velocity; velocity*=.6;
                check(y>=proof.feetY-.000001 && y+1.8<=proof.feetY+proof.height+.000001,
                        "One native vertical pulse and every subsequent coast position remain inside the complete body envelope.");
            }
        }
        PlayerNavigation.Point low=new PlayerNavigation.Point(0,2.2,0);
        FlightPathPlanner.Sweep unsafe=PlayerNavigation.inertiaSweeps(low,0,0,0,java.util.Collections.singleton("sneak"),
                0,.05,true,Double.NaN,.6,1.8).get(1);
        check(unsafe.feetY<2,"A genuine descent into the floor is still blocked without fresh landing support; the floor is never silently clamped.");
    }
    private static void recordedDescentFeedback() {
        recordedVerticalFeedback(new PlayerNavigation.Point(83.6451098123479,7.822512,52.04103186865648),
                new PlayerNavigation.Point(86,9,55),
                new PlayerNavigation.Point(84.5890079539743,9,52.99166383309314),
                AxisAlignedBB.getBoundingBox(84,7,51,85,8,53),"intermediate_climb");
        PlayerNavigation.Point start=new PlayerNavigation.Point(100.3263717700486,2.5419904000000013,38.524498158343455);
        PlayerNavigation.Point target=new PlayerNavigation.Point(100.13414962355701,2,38.846143087203004);
        recordedVerticalFeedback(start,target,null,AxisAlignedBB.getBoundingBox(98,1,37,102,2,41),"descent");
        recordedVerticalFeedback(new PlayerNavigation.Point(100.64745838344045,3.990318693376,39.68104597159589),
                new PlayerNavigation.Point(100.74272130231499,4.347806693376002,41.07422548726861),
                new PlayerNavigation.Point(100.74272130231499,4.167806693376002,40.663696164722225),
                AxisAlignedBB.getBoundingBox(100,3,40,101,4,41),"ledge");
        recordedVerticalFeedback(new PlayerNavigation.Point(100.73392132337251,4.354821093376,40.54664631179093),
                new PlayerNavigation.Point(100.68604670744739,4.354821093376,41.57422548726861),
                new PlayerNavigation.Point(100.68604670744739,4,41.07422548726861),
                AxisAlignedBB.getBoundingBox(100,3,40,102,4,42),"level_flight");
    }
    private static void recordedVerticalFeedback(PlayerNavigation.Point start,PlayerNavigation.Point target,
            PlayerNavigation.Point intermediate,AxisAlignedBB floor,String fixture) {
        JsonObject request=args("ability_flight",target.x); request.addProperty("y",target.y); request.addProperty("z",target.z); request.addProperty("finishFlying",true);
        PlayerNavigation.State flight=new PlayerNavigation.State(new PlayerNavigation.Request(request),corridorFrame(start,0,0,10),new Object(),new Object(),"uuid");
        prepareFlight(flight); flight.flightObserved=true; flight.route.clear();
        if(intermediate!=null) flight.route.add(intermediate);
        flight.route.add(target);
        if("final_level_flight".equals(fixture)) { flight.levelFlightWaypoint=0; flight.levelFlightY=start.y; }
        double x=start.x,y=start.y,z=start.z,vx=0,vy=0,vz=0;
        int tick;
        for(tick=20;tick<180;tick++) {
            PlayerNavigation.Point actual=new PlayerNavigation.Point(x,y,z);
            JsonObject frame=corridorFrame(actual,vx,vz,tick);
            for(JsonObject pose:new JsonObject[]{frame.getAsJsonObject("player"),frame.getAsJsonObject("serverControl").getAsJsonObject("pose")}) {
                pose.addProperty("motionY",vy); pose.addProperty("flySpeed",.05);
            }
            flight.requireContext(frame);
            if(flight.inputArrivalReady(.05) && Math.hypot(vx,vz)<=.005 && vy==0) break;
            java.util.Set<String> keys=flight.next(tick,.05);
            List<FlightPathPlanner.Sweep> proof=PlayerNavigation.inertiaSweeps(actual,vx,vy,vz,keys,flight.steeringYaw,.05,true,Double.NaN,.6,1.8);
            boolean blocked=false;
            for(FlightPathPlanner.Sweep piece:proof.get(0).pieces)
                check(!sweepBox(piece).intersectsWith(floor),"Actual residual coast remains safe before considering another native pulse.");
            for(FlightPathPlanner.Sweep piece:proof.get(1).pieces) blocked|=sweepBox(piece).intersectsWith(floor);
            if(blocked) {
                check(intermediate!=null,"Recorded descent needs no unsafe pulse or repeated floor braking.");
                flight.brakeRejectedFlightInput(keys,()->true,tick);
            }
            flight.dispatched(keys,tick); flight.held.clear(); flight.held.addAll(keys);
            if(keys.contains("sneak")) vy-=.15; if(keys.contains("jump")) vy+=.15;
            if(Math.abs(vx)<.005) vx=0; if(Math.abs(vy)<.005) vy=0; if(Math.abs(vz)<.005) vz=0;
            int direction=keys.contains("forward") ? 1 : keys.contains("back") ? -1 : 0;
            float angle=(float)flight.steeringYaw*(float)Math.PI/180F;
            float strength=(keys.contains("sneak") ? .3F : 1F)*.98F*.05F;
            vx-=strength*MathHelper.sin(angle)*direction; vz+=strength*MathHelper.cos(angle)*direction;
            x+=vx; y+=vy; z+=vz; vx*=(double).91F; vy*=.6; vz*=(double).91F;
            check(!AxisAlignedBB.getBoundingBox(x-.3,y,z-.3,x+.3,y+1.8,z+.3).intersectsWith(floor),
                    "Independent native vertical feedback never intersects the physical floor/ledge.");
        }
        check(tick<180 && new PlayerNavigation.Point(x,y,z).near(target),"The exact stalled descent converges through native flight inputs within the unchanged arrival tolerance.");
        if(intermediate!=null) check((flight.clearanceFlightWaypoint==0 || flight.levelFlightWaypoint==0) && flight.waypoint==1,
                "A blocked intermediate waypoint uses freshly checked vertical clearance or level flight and advances without altering the final destination.");
        System.out.println("Recorded "+fixture+" offline: simulatedTicks="+(tick-20)+", feetY="+y+"; not live client acceptance.");
    }
    private static void recordedCorridorFeedback(PlayerNavigation.Point start,AxisAlignedBB blocker,boolean armor) {
        PlayerNavigation.Point target=new PlayerNavigation.Point(-67.57124191177274,157,-110.69427889845136);
        JsonObject request=args("ability_flight",target.x); request.addProperty("y",target.y); request.addProperty("z",target.z); request.addProperty("finishFlying",true);
        JsonObject initial=corridorFrame(start,0,0,10);
        PlayerNavigation.State flight=new PlayerNavigation.State(new PlayerNavigation.Request(request),initial,new Object(),new Object(),"uuid");
        prepareFlight(flight); flight.flightObserved=true; flight.route.clear(); flight.route.add(target);
        double x=start.x,z=start.z,vx=0,vz=0; boolean previousIdle=false; int tick;
        for(tick=20;tick<220;tick++) {
            PlayerNavigation.Point actual=new PlayerNavigation.Point(x,start.y,z);
            flight.requireContext(corridorFrame(actual,vx,vz,tick));
            if(flight.inputArrivalReady(.25) && Math.hypot(vx,vz)<.005) break;
            java.util.Set<String> keys=flight.next(tick,.25);
            List<FlightPathPlanner.Sweep> proof=PlayerNavigation.inertiaSweeps(actual,vx,0,vz,keys,flight.steeringYaw,.25,true,Double.NaN,.6,1.8,flight.coastDrag(),flight.coastScale());
            boolean blocked=false;
            for(FlightPathPlanner.Sweep envelope:proof) for(FlightPathPlanner.Sweep piece:envelope.pieces)
                blocked|=sweepBox(piece).intersectsWith(blocker);
            if(blocked) flight.brakeRejectedFlightInput(keys,()->true,tick);
            flight.dispatched(keys,tick); flight.held.clear(); flight.held.addAll(keys);
            boolean idle=!keys.contains("forward") && !keys.contains("back");
            if(armor && idle && previousIdle) {vx*=.5;vz*=.5;}
            if(Math.abs(vx)<.005)vx=0; if(Math.abs(vz)<.005)vz=0;
            int direction=keys.contains("forward") ? 1 : keys.contains("back") ? -1 : 0;
            float angle=(float)flight.steeringYaw*(float)Math.PI/180F;
            float strength=(keys.contains("sneak") ? .3F : 1F)*.98F*.25F;
            vx-=strength*MathHelper.sin(angle)*direction; vz+=strength*MathHelper.cos(angle)*direction;
            x+=vx;z+=vz;vx*=(double).91F;vz*=(double).91F; previousIdle=idle;
            check(!AxisAlignedBB.getBoundingBox(x-.3,start.y,z-.3,x+.3,start.y+1.8,z+.3).intersectsWith(blocker),
                    "Independent native feedback never crosses the recorded physical blocker.");
        }
        check(tick-20<120 && new PlayerNavigation.Point(x,start.y,z).near(target),
                "Required reverse braking is not deferred by the idle observation window; the recorded corridor converges within 120 simulated ticks with or without armor damping.");
        System.out.println("Recorded flight corridor offline: armorDamping="+armor+", simulatedTicks="+(tick-20)
                +", blockedInputs="+flight.blockedFlightInputsBraked+"; not a live client/time acceptance.");
    }
    private static JsonObject corridorFrame(PlayerNavigation.Point p,double vx,double vz,long tick) {
        JsonObject frame=flightFrame(p.x,p.y,tick,0); frame.addProperty("clientTicks",tick);
        for(JsonObject pose:new JsonObject[]{frame.getAsJsonObject("player"),frame.getAsJsonObject("serverControl").getAsJsonObject("pose")}) {
            pose.addProperty("z",p.z); pose.addProperty("motionX",vx);pose.addProperty("motionZ",vz);pose.addProperty("flySpeed",.25);
        }
        return frame;
    }
    private static AxisAlignedBB sweepBox(FlightPathPlanner.Sweep s) {
        return AxisAlignedBB.getBoundingBox(s.x-s.widthX/2,s.feetY,s.z-s.widthZ/2,s.x+s.widthX/2,s.feetY+s.height,s.z+s.widthZ/2);
    }
    private static void nativeHeightPulseWindows() {
        for (int direction : new int[] {-1, 1}) {
            JsonObject args = args("ability_flight", .5); args.addProperty("finishFlying", true); args.addProperty("y",64 + direction * 2);
            PlayerNavigation.State flight = new PlayerNavigation.State(new PlayerNavigation.Request(args), frame(.5,10,true,false),new Object(),new Object(),"uuid");
            prepareFlight(flight); flight.flightObserved = true;
            flight.route.clear(); flight.route.add(new PlayerNavigation.Point(.5,64 + direction * 2,.5));
            flight.requireContext(frame(.5,11,true,false));
            java.util.Set<String> pulse = flight.next(20,.25);
            check(pulse.contains(direction > 0 ? "jump" : "sneak") && !pulse.contains(direction > 0 ? "sneak" : "jump"),
                    "A settled native hover permits one height pulse in the requested direction.");
            flight.dispatched(pulse,20); flight.held.addAll(pulse);
            JsonObject moving = frame(.5,12,true,false); moving.getAsJsonObject("player").addProperty("motionY",direction * .135);
            flight.requireContext(moving);
            java.util.Set<String> coast = flight.next(21,.25);
            check(coast.contains("jump") && coast.contains("sneak"),
                    "After a height pulse, normal neutral jump/sneak prevents equipment held-key boosting while actual vertical motion settles.");
            flight.dispatched(coast,21); flight.held.clear(); flight.held.addAll(coast);
            flight.requireContext(frame(.5,13,true,false));
            for (long tick = 22; tick < 28; tick++) {
                java.util.Set<String> idle = flight.next(tick,.25);
                check(idle.contains("jump") && idle.contains("sneak"), "A zero-motion observation cannot bypass the native pulse/edge cooldown.");
            }
            moving.getAsJsonObject("player").addProperty("motionY",direction * .01); flight.requireContext(moving);
            java.util.Set<String> residual = flight.next(29,.25);
            check(residual.contains("jump") && residual.contains("sneak"), "Residual actual motion prevents another pulse even after the cooldown.");
            flight.requireContext(frame(.5,14,true,false));
            java.util.Set<String> next = flight.next(30,.25);
            check(next.contains(direction > 0 ? "jump" : "sneak") && !next.contains(direction > 0 ? "sneak" : "jump"),
                    "Only settled motion and both native tick guards allow the next height pulse.");
            check(!flight.positionReady(), "Neutral input and pulse eligibility alone never prove dual arrival.");
        }
    }
    private static void nativeHorizontalPulseModels() {
        PlayerNavigation.Point current = new PlayerNavigation.Point(0,64,0);
        PlayerNavigation.Point vanilla = PlayerNavigation.horizontalStop(current,.2,0,0,0,0,0,0);
        PlayerNavigation.Point damped = PlayerNavigation.horizontalStop(current,.2,0,0,0,0,0,0,(double).91F,(double).91F*.5,.5);
        check(damped.x>0 && damped.x<vanilla.x/4,"Observed extra coast damping shortens only the native predictor, without changing motion.");
        for(double acceleration:new double[]{.098,.196,.392}) {
            double x=0,v=0; PlayerNavigation.Point target=new PlayerNavigation.Point(3.5,64,0);
            int ticks=0;
            for(;ticks<160 && (Math.abs(x-target.x)>.18 || Math.abs(v)>.005);ticks++) {
                PlayerNavigation.HorizontalFlightInput input=PlayerNavigation.walkingInput(new PlayerNavigation.Point(x,64,0),target,v,0,.546,acceleration);
                if(Math.abs(v)<.005)v=0;
                v += -Math.sin(Math.toRadians(input.yaw))*acceleration*input.first;
                x += v; v *= .546;
            }
            check(ticks<160 && Math.abs(x-target.x)<=.18,"Accelerated native walking pulses converge at a waypoint with exact ordinary drag: "+acceleration);
        }
        PlayerNavigation.State walking=state("walking",3.5); prepareClear(walking);
        walking.walkingAcceleration=.784; walking.requireContext(frame(.5,11,false,true));
        java.util.Set<String> slow=walking.next(11);
        check(slow.contains("sneak") && (slow.contains("forward") || slow.contains("back")),
                "Accelerated walking uses ordinary native sneak input without changing equipment or movement attributes.");
        PlayerNavigation.State flight=state("ability_flight",9.5); prepareFlight(flight);
        flight.held.add("jump"); flight.held.add("sneak");
        double x=.5,v=.2;
        for(int tick=11;tick<=15;tick++) {
            JsonObject f=frame(x,tick,true,false); f.addProperty("clientTicks",tick); f.getAsJsonObject("player").addProperty("motionX",v);
            flight.requireContext(f); x+=v*.5; v*=.455;
        }
        check(flight.traceReceipt(0).get("coastModelSamples").getAsInt()>=3 && Math.abs(flight.coastScale()-.5)<.00001 && Math.abs(flight.coastDrag()-.455)<.00001,
                "Altitude-neutral native jump/sneak still permits measured no-horizontal-input inertia learning.");
        JsonObject switched=frame(x,16,true,false); switched.addProperty("clientTicks",16);
        switched.getAsJsonObject("player").addProperty("motionX",v); flight.requireContext(switched);
        x+=v; v*=.91;
        switched=frame(x,17,true,false); switched.addProperty("clientTicks",17); switched.getAsJsonObject("player").addProperty("motionX",v);
        flight.requireContext(switched);
        check(flight.coastScale()==1 && Math.abs(flight.coastDrag()-(double).91F)<.00001,
                "Disabling no-inertia flight immediately invalidates the old half-speed coast response.");
        // State-level arrival must use the same measured damping as input selection, not vanilla inertia.
        JsonObject hoverArgs=args("ability_flight",.5); hoverArgs.addProperty("finishFlying",true);
        PlayerNavigation.State hover=new PlayerNavigation.State(new PlayerNavigation.Request(hoverArgs),frame(.5,10,true,false),new Object(),new Object(),"uuid");
        prepareFlight(hover); hover.flightObserved=true;
        double hx=.35,hv=.12;
        for(int tick=11;tick<=15;tick++) {
            JsonObject f=frame(hx,tick,true,false); f.addProperty("clientTicks",tick); f.getAsJsonObject("player").addProperty("motionX",hv);
            hover.requireContext(f); hx+=hv*.5; hv*=.455;
        }
        JsonObject arrival=frame(.5,16,true,false); arrival.addProperty("clientTicks",20); arrival.getAsJsonObject("player").addProperty("motionX",.04);
        hover.requireContext(arrival);
        check(hover.inputArrivalReady(.05),"Measured no-inertia coast can release native input when vanilla residual drift would overshoot.");
        arrival.getAsJsonObject("player").addProperty("flySpeed",.05); arrival.addProperty("clientTicks",21); hover.requireContext(arrival);
        arrival.getAsJsonObject("player").addProperty("flySpeed",.25); arrival.addProperty("clientTicks",23); hover.requireContext(arrival);
        check(hover.coastScale()==1 && hover.traceReceipt(0).get("coastModelSamples").getAsInt()==0,
                "Actual flight-speed changes invalidate request-local coast evidence before another pulse.");
        java.util.Set<String> precision = new java.util.LinkedHashSet<String>(); precision.add("forward"); precision.add("sneak"); precision.add("jump");
        List<FlightPathPlanner.Sweep> proof=PlayerNavigation.inertiaSweeps(current,0,0,0,precision,-90,.25,true,Double.NaN,.6,1.8);
        check(proof.get(1).height==1.8 && proof.get(1).widthX<1.5,"Native simultaneous jump/sneak precision preserves altitude and proves reduced horizontal swept input.");
    }
    private static void crosshairStopAndArrival() {
        for (String strategy : new String[] {"walking","ability_flight"}) {
            JsonObject request=args(strategy,8.5); request.add("arrivalTargets",CrosshairArrivalSelfTest.targets());
            if ("ability_flight".equals(strategy)) request.addProperty("finishFlying",true);
            boolean flying="ability_flight".equals(strategy);
            PlayerNavigation.State state=new PlayerNavigation.State(new PlayerNavigation.Request(request),frame(.5,10,flying,!flying),new Object(),new Object(),"uuid");
            if (flying) { prepareFlight(state); state.flightObserved=true; } else prepareClear(state);
            state.waypoint=state.route.size()-1;
            JsonObject actual=frame(.23,11,flying,!flying); actual.getAsJsonObject("player").addProperty("yaw",-90);
            actual.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("yaw",-90); state.requireContext(actual);
            CrosshairArrivalSelfTest.Fixture fixture=new CrosshairArrivalSelfTest.Fixture(); state.request.crosshair.capture(fixture);
            CrosshairArrivalSelfTest.observe(state.request.crosshair,fixture,.23,true); CrosshairArrivalSelfTest.observe(state.request.crosshair,fixture,.23,false);
            state.crosshairStopping=true;
            check(state.positionReady(),"Both actual hits allow safe off-endpoint interaction arrival: "+strategy);
            state.route.add(new PlayerNavigation.Point(9.5,64,.5));
            check(state.positionReady() && state.next(11,.05).isEmpty(),
                    "Real dual target hits may terminate an earlier safe route segment instead of forcing a redundant detour: "+strategy);
            actual.getAsJsonObject("player").addProperty("motionX",.1); state.requireContext(actual);
            check(!state.inputArrivalReady(.05) && state.next(11,.05).isEmpty(),"A hit releases keys but drifting does not complete arrival: "+strategy);
            state.request.crosshair.clear(false); check(!state.positionReady(),"Missing fresh server hit cannot finish: "+strategy);
            state.crosshairStopping=false; check(!state.positionReady(),"Lost hit returns to the original coordinate route: "+strategy);
            BridgeException failure=state.diagnostic(new BridgeException("timeout","Crosshair test deadline."));
            check("timeout".equals(failure.code) && failure.getMessage().length()<=PlayerNavigation.MAX_FLIGHT_ERROR_MESSAGE,"Crosshair timeout keeps the existing bounded error contract.");
        }
        JsonObject invalid=args("developer_teleport",1.5); invalid.add("arrivalTargets",CrosshairArrivalSelfTest.targets());
        expect("invalid_args",()->new PlayerNavigation.Request(invalid));
        JsonObject declared = args("walking",8.5); declared.add("arrivalTargets",CrosshairArrivalSelfTest.targets());
        JsonArray visits = new JsonArray(); visits.add(xyz(4.5,64,.5)); declared.add("waypoints",visits);
        PlayerNavigation.State mandatory = new PlayerNavigation.State(new PlayerNavigation.Request(declared),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        prepareClear(mandatory);
        check(!mandatory.crosshairRouteEligible(), "Actual target hits cannot skip caller-declared mandatory waypoints.");
        mandatory.crosshairStopping = true;
        CrosshairArrivalSelfTest.Fixture fixture = new CrosshairArrivalSelfTest.Fixture(); mandatory.request.crosshair.capture(fixture);
        CrosshairArrivalSelfTest.observe(mandatory.request.crosshair,fixture,.5,true); CrosshairArrivalSelfTest.observe(mandatory.request.crosshair,fixture,.5,false);
        check(!mandatory.positionReady(), "Retained crosshair evidence cannot complete before the last mandatory route segment.");
        mandatory.waypoint = mandatory.route.size()-1;
        check(mandatory.crosshairRouteEligible(), "Final target interaction remains eligible after declared visits.");
        for (int count : new int[] {1, 6}) {
            JsonObject interaction = args("walking",8.5);
            interaction.remove("x"); interaction.remove("y"); interaction.remove("z");
            JsonObject goal = new JsonObject(); goal.addProperty("type", "interaction");
            JsonArray targets = CrosshairArrivalSelfTest.targets();
            for (int index = 1; index < count; index++) {
                JsonObject target = new com.google.gson.JsonParser().parse(targets.get(0).toString()).getAsJsonObject();
                target.addProperty("z", index); targets.add(target);
            }
            goal.add("targets", targets); interaction.add("goal", goal);
            PlayerNavigation.State routed = new PlayerNavigation.State(new PlayerNavigation.Request(interaction),frame(.5,10,false,true),new Object(),new Object(),"uuid");
            routed.route.clear(); routed.route.add(new PlayerNavigation.Point(1.5,64,.5)); routed.route.add(new PlayerNavigation.Point(2.5,64,.5));
            check(!routed.crosshairRouteEligible(), "Interaction hits cannot halt an intermediate segment before actual endpoint proofs: " + count);
            routed.waypoint = 1;
            check(routed.crosshairRouteEligible(), "The final interaction segment still permits native crosshair braking: " + count);
        }
    }
    private static void walkingCoastAndArrival() {
        PlayerNavigation.Point point = new PlayerNavigation.Point(.5,64,.5);
        check(Math.abs(PlayerNavigation.walkingStop(point,.227,0,.546).x-1)<.00001,
                "Ground braking uses native residual motion rather than waiting until it crosses the endpoint.");
        check(PlayerNavigation.walkingStop(point,.1,0,.8918).x > PlayerNavigation.walkingStop(point,.1,0,.546).x,
                "Low-friction ground retains a longer bounded coast prediction without changing speed.");
        expect("unsupported", () -> PlayerNavigation.walkingStop(point,.1,0,1));
        expect("unsupported", () -> PlayerNavigation.walkingStop(point,Double.NaN,0,.546));
        PlayerNavigation.State walking = state("walking",2.5); prepareClear(walking);
        JsonObject moving = frame(2.2,11,false,true);
        moving.getAsJsonObject("player").addProperty("motionX",.1362);
        moving.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("motionX",.1362);
        walking.requireContext(moving);
        check(walking.next(11).isEmpty() && walking.walkingCoasting,
                "Forward releases before the exact endpoint when natural stopping remains within its normal tolerance.");
        moving = frame(2.36,12,false,true); moving.getAsJsonObject("player").addProperty("motionX",.07);
        moving.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("motionX",.07);
        walking.requireContext(moving);
        check(walking.positionReady() && !walking.inputArrivalReady(Double.NaN) && walking.next(12).isEmpty(),
                "Reachable residual drift cannot turn the camera toward the final interaction while still coasting.");
        walking.requireContext(frame(2.45,13,false,true));
        check(walking.inputArrivalReady(Double.NaN), "Actually stationary dual off-centre feet permit the final view.");
        JsonObject routeArgs = args("walking",4.5); JsonArray visits = new JsonArray(); visits.add(xyz(2.5,64,.5)); routeArgs.add("waypoints",visits);
        PlayerNavigation.State turn = new PlayerNavigation.State(new PlayerNavigation.Request(routeArgs),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        prepareClear(turn); turn.requireContext(moving); turn.next(14);
        check(turn.waypoint == 0 && turn.walkingCoasting, "Intermediate bends wait for real braking and do not cut corners on inertia.");
        turn.requireContext(frame(2.45,14,false,true)); turn.next(15);
        check(turn.waypoint == 0, "Stopping alone cannot cut an intermediate corner outside its tighter alignment bound.");
        turn.requireContext(frame(2.49,15,false,true)); turn.next(16);
        check(turn.waypoint == 1, "A stopped aligned native intermediate waypoint then advances normally.");
        JsonObject actor = pose(2.36,false,true); actor.addProperty("width",.6); actor.addProperty("height",1.8);
        JsonObject unsafe = walkingBody(); unsafe.addProperty("canStand",false); unsafe.addProperty("fullSupportCoverage",false);
        walking.rejectedFlightBody("client","coast",actor,AxisAlignedBB.getBoundingBox(2.06,64,.2,2.66,65.8,.8),
                AxisAlignedBB.getBoundingBox(2.06,64,.2,2.9,65.8,.8),java.util.Collections.<String>emptySet(),unsafe);
        walking.arrivalAckWait = "stable_pose_sample_pending";
        String message = walking.diagnostic(new BridgeException("unsupported","Actual walking coast rejected.")).getMessage();
        check(message.length()<=3500 && message.contains("geometryRejection") && message.contains("stable_pose_sample_pending")
                && message.contains("coast") && !message.contains("lastRejectedEdges"),
                "Walking refusal retains bounded exact execution geometry and ACK state instead of unrelated search edges.");
    }

    private static void travelView() {
        PlayerNavigation.Point start = new PlayerNavigation.Point(0, 64, 0), target = new PlayerNavigation.Point(3, 67, 0);
        check(PlayerNavigation.travelPitch(start, target, true) == 0, "walking keeps its view along the floor");
        check(Math.abs(PlayerNavigation.travelPitch(start, target, false) + 45) < .0001, "ascending flight faces its next path segment");
        check(PlayerNavigation.travelYaw(start, target, 0, 0) == -90 && PlayerNavigation.travelYaw(start, target, 0, .1) == 0, "actual horizontal travel overrides destination heading while momentum remains");
    }

    private static JsonObject args(String strategy, double x) {
        JsonObject value = xyz(x, 64, .5); value.addProperty("expectedWorld", "fixture"); value.addProperty("expectedSessionId", "session");
        value.addProperty("dimension", 0); if (strategy != null) value.addProperty("strategy", strategy); return value;
    }
    private static JsonObject xyz(double x, double y, double z) { JsonObject value = new JsonObject(); value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z); return value; }
    private static JsonObject pose(double x, boolean flying, boolean ground) {
        JsonObject value = xyz(x, 64, .5); value.addProperty("feetY", 64); value.addProperty("eyeY", 65.62); value.addProperty("yaw", 0); value.addProperty("pitch", 0);
        value.addProperty("motionX", 0); value.addProperty("motionY", 0); value.addProperty("motionZ", 0);
        value.addProperty("health", 20); value.addProperty("creative", false); value.addProperty("flying", flying); value.addProperty("allowFlying", true); value.addProperty("onGround", ground); return value;
    }
    private static JsonObject frame(double x, long sample, boolean flying, boolean ground) {
        JsonObject value = new JsonObject(); value.addProperty("sessionId", "session"); value.addProperty("profile", "developer");
        value.addProperty("worldLoaded", true); value.addProperty("paused", false); value.addProperty("screenOpen", false); value.addProperty("screenId", "");
        JsonObject world = new JsonObject(); world.addProperty("worldName", "fixture"); world.addProperty("worldFolder", "folder"); world.addProperty("dimension", 0); value.add("world", world);
        JsonObject background = new JsonObject(); background.addProperty("enabled", true); background.addProperty("helperInstalled", true); background.addProperty("pauseOnLostFocus", false); value.add("background", background);
        value.add("player", pose(x, flying, ground)); JsonObject control = new JsonObject(); control.addProperty("available", true); control.addProperty("source", "integrated_server_tick");
        control.addProperty("playerId", "uuid"); control.addProperty("sessionId", "session"); control.addProperty("worldName", "fixture"); control.addProperty("worldFolder", "folder");
        control.addProperty("dimension", 0); control.addProperty("ageMillis", 0); control.addProperty("sampleId", sample); control.addProperty("creative", false);
        JsonObject container = new JsonObject(); container.addProperty("isInventoryContainer", true); container.addProperty("windowId", 0);
        JsonObject clientContainer = new JsonObject(); clientContainer.addProperty("isInventoryContainer", true); clientContainer.addProperty("windowId", 0);
        control.add("openContainer", container); control.add("clientContainer", clientContainer); control.add("pose", pose(x, flying, ground)); value.add("serverControl", control); return value;
    }
    private static PlayerNavigation.State state(String strategy, double x) { return new PlayerNavigation.State(new PlayerNavigation.Request(args(strategy, x)), frame(.5, 10, false, true), new Object(), new Object(), "uuid"); }
    private static void genericGoalsAndSegments() {
        check(BridgeHttpServer.navigationWatchdogMillis(new PlayerNavigation.Request(args("walking", 1.5))) == 10000,
                "Ordinary navigation keeps the existing bounded HTTP watchdog.");
        unreachableFirstEndpoint();
        capabilityCompletion();
        JsonObject longArgs=args("walking",80.5); longArgs.addProperty("segmented",true); longArgs.addProperty("timeout_ms",20000);
        check(BridgeHttpServer.navigationWatchdogMillis(new PlayerNavigation.Request(longArgs)) == 21500,
                "Validated segmented navigation receives the entire task budget plus bounded delivery slack.");
        JsonObject interactionArgs = args("walking",1.5); interactionArgs.remove("x"); interactionArgs.remove("y"); interactionArgs.remove("z");
        JsonObject interaction = new JsonObject(); interaction.addProperty("type","interaction"); interaction.add("targets",CrosshairArrivalSelfTest.targets());
        interactionArgs.add("goal",interaction); interactionArgs.addProperty("timeout_ms",15000);
        check(BridgeHttpServer.navigationWatchdogMillis(new PlayerNavigation.Request(interactionArgs)) == 16500,
                "Fifteen-second interaction navigation cannot be disconnected by the ten-second HTTP watchdog.");
        PlayerNavigation.State longState=new PlayerNavigation.State(new PlayerNavigation.Request(longArgs),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        check(longState.planningTargets.size()==4,"An eighty-block request is split by the client into four windows.");
        WalkingPathPlanner.Probe clear=(sweep,deadline)->WalkingPathPlanner.Check.CLEAR;
        for(int i=0;i<1000 && !longState.walkingRouteReady;i++) longState.prepareWalking(clear,.6,1.8,Long.MAX_VALUE);
        check(longState.walkingRouteReady && longState.planningWindows.size()==4 && Math.abs(longState.plannedDistance-80)<.00001,"Every bounded window is independently planned, then the complete route is published.");
        check(!longState.mutation && longState.held.isEmpty(),"Long planning dispatches no movement.");
        segmentedMachineBoundary(longArgs);
        JsonObject goalArgs=args("walking",1.5); goalArgs.remove("x"); goalArgs.remove("y"); goalArgs.remove("z");
        JsonObject box=new JsonObject(); box.addProperty("type","box"); box.add("min",xyz(2,64,0)); box.add("max",xyz(4,64,2)); goalArgs.add("goal",box);
        PlayerNavigation.State goalState=new PlayerNavigation.State(new PlayerNavigation.Request(goalArgs),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        for(int i=0;i<1000 && !goalState.walkingRouteReady;i++) goalState.prepareWalking(clear,.6,1.8,Long.MAX_VALUE);
        check(goalState.walkingRouteReady && goalState.request.goal.contains(goalState.request.destination),"Client endpoint selection satisfies the volume goal.");
        check(goalState.request.goal.heuristic(new PlayerNavigation.Point(3,64,1))==0,"Goal heuristic is zero inside the goal.");
        goalArgs.addProperty("x",1); expect("invalid_args",()->new PlayerNavigation.Request(goalArgs)); goalArgs.remove("x");
        JsonObject union=new JsonObject(); union.addProperty("type","any"); JsonArray members=new JsonArray(); members.add(box); JsonObject point=xyz(10,64,1); point.addProperty("type","point"); members.add(point); union.add("goals",members);
        NavigationGoal any=NavigationGoal.parse(union); check(any.contains(new PlayerNavigation.Point(3,64,1)) && any.contains(new PlayerNavigation.Point(10,64,1)),"General goal union accepts either branch.");
        check(NavigationCosts.walking(1,1).score()>NavigationCosts.walking(1,0).score(),"Jump overhead participates in the same route cost.");
        check(NavigationCosts.walking(1,-3).score()>NavigationCosts.walking(1,-1).score(),"Larger falls have a larger estimated cost.");
        check(!NavigationCosts.breaking(0,false).possible && NavigationCosts.breaking(0,true).possible,"Unbreakable survival blocks cannot acquire finite break costs.");
        check(NavigationCosts.placing(2).items==2 && NavigationCosts.placing(2).score()>NavigationCosts.placing(1).score(),"Placement resources participate in the common score.");
        int[] transformed=SchematicGoal.transform(1,2,3,1,true); check(transformed[0]==-3 && transformed[1]==2 && transformed[2]==-1,"Generic transforms mirror then rotate without changing height.");
        JsonObject declaration=new JsonObject(); declaration.add("anchor",xyz(10,64,20)); JsonArray cells=new JsonArray(); JsonObject cell=xyz(1,0,0); cell.addProperty("kind","block"); JsonArray alternatives=new JsonArray(); JsonObject identity=new JsonObject(); identity.addProperty("registryName","minecraft:stone"); identity.addProperty("metadata",0); alternatives.add(identity); cell.add("alternatives",alternatives); cells.add(cell); declaration.add("cells",cells);
        SchematicGoal.ExplicitSource source=new SchematicGoal.ExplicitSource(declaration); check(source.completeRules() && source.cells().get(0).x==11,"Explicit generic cells bind transformed absolute positions.");
        cells.add(cell); expect("invalid_args",()->new SchematicGoal.ExplicitSource(declaration));
        JsonObject actual=new com.google.gson.JsonParser().parse(identity.toString()).getAsJsonObject(); identity.addProperty("metaTileId",10); check(!SchematicGoal.matches(identity,actual),"Block metadata alone cannot satisfy an explicitly required GT machine subtype.");
    }
    private static void segmentedMachineBoundary(JsonObject args) {
        PlayerNavigation.State state=new PlayerNavigation.State(new PlayerNavigation.Request(args),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        AxisAlignedBB machine=AxisAlignedBB.getBoundingBox(35,63,-3,56,70,4);
        WalkingPathPlanner.Probe geometry=(sweep,deadline)-> Math.abs(sweep.feetY-64)>.0001
                || sweep.body().intersectsWith(machine) ? WalkingPathPlanner.Check.BLOCKED : WalkingPathPlanner.Check.CLEAR;
        for(int i=0;i<5000 && !state.walkingRouteReady;i++) state.prepareWalking(geometry,.6,1.8,Long.MAX_VALUE);
        check(state.walkingRouteReady && state.planningWindows.size()==4,
                "A flexible segment boundary inside a wide machine/IO band moves to a proved clear gate within existing window slack.");
        PlayerNavigation.Point previous=state.start;
        boolean outside=false;
        for(PlayerNavigation.Point point:state.route) {
            check(geometry.inspect(new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(previous.x,previous.y,previous.z),
                    new WalkingPathPlanner.Point(point.x,point.y,point.z),.6,1.8),Long.MAX_VALUE)==WalkingPathPlanner.Check.CLEAR,
                    "Every published segment retains full-body obstacle clearance.");
            outside |= Math.abs(point.z-.5)>2;
            previous=point;
        }
        check(outside && previous.distance(state.request.destination)<.00001 && state.request.destination.x==80.5,
                "Only flexible boundaries move; the original final destination remains exact.");
        check(!state.mutation && state.held.isEmpty() && state.goalPlanningNodes<=32768 && state.goalPlanningSamples<=131072,
                "Expanded gate candidates neither dispatch movement nor reset aggregate planning budgets.");
    }
    private static void capabilityCompletion() {
        NavigationCosts.Model model=NavigationCosts.snapshot(); NavigationCosts.Cost before=model.placing(2);
        double old=NavigationCosts.materialPenaltyTicks;
        try { NavigationCosts.materialPenaltyTicks=old+100; check(before.score()==model.placing(2).score(),"A yielded task retains its original resource score."); }
        finally { NavigationCosts.materialPenaltyTicks=old; }
        check(new NavigationCosts.Cost(1,Integer.MAX_VALUE).plus(new NavigationCosts.Cost(1,10)).items==(long)Integer.MAX_VALUE+10,"Resource accumulation cannot wrap into negative costs.");
        JsonObject goalArgs=args("walking",1.5); goalArgs.remove("x"); goalArgs.remove("y"); goalArgs.remove("z");
        JsonArray goals=new JsonArray(); JsonObject first=xyz(2.5,64,.5); first.addProperty("type","point"); goals.add(first);
        JsonObject second=xyz(.5,64,2.5); second.addProperty("type","point"); goals.add(second); goals.add(first);
        JsonObject union=new JsonObject(); union.addProperty("type","any"); union.add("goals",goals); goalArgs.add("goal",union);
        PlayerNavigation.State state=new PlayerNavigation.State(new PlayerNavigation.Request(goalArgs),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        check(state.goalCandidates.size()==2,"Overlapping goals are deduplicated before expensive route planning.");
        WalkingPathPlanner.Probe access=(sweep,deadline)->sweep.from.distance(sweep.to)>.00001 && sweep.to.x>1 ? WalkingPathPlanner.Check.UNKNOWN : WalkingPathPlanner.Check.CLEAR;
        for(int i=0;i<2000 && !state.walkingRouteReady;i++) state.prepareWalking(access,.6,1.8,Long.MAX_VALUE);
        check(state.walkingRouteReady && state.request.destination.near(new PlayerNavigation.Point(.5,64,2.5)) && state.goalRouteAttempts==2,"An unreachable safe endpoint is skipped before movement and a reachable goal is selected.");
        check(!state.mutation && state.held.isEmpty(),"Comparing candidate routes sends no movement input.");
        JsonObject segmented=args("walking",80.5); segmented.addProperty("segmented",true); segmented.addProperty("timeout_ms",20000);
        PlayerNavigation.State longState=new PlayerNavigation.State(new PlayerNavigation.Request(segmented),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        WalkingPathPlanner.Probe boundary=(sweep,deadline)->sweep.from.distance(sweep.to)<.00001 && Math.abs(sweep.x-20.5)<.00001 ? WalkingPathPlanner.Check.BLOCKED : WalkingPathPlanner.Check.CLEAR;
        for(int i=0;i<2000 && !longState.walkingRouteReady;i++) longState.prepareWalking(boundary,.6,1.8,Long.MAX_VALUE);
        check(longState.walkingRouteReady && longState.route.get(0).x!=20.5 && longState.route.get(longState.route.size()-1).x==80.5,"An unsafe interpolation boundary moves to a proved nearby gate while the final goal stays exact.");
        JsonObject flight=args("ability_flight",80.5); flight.addProperty("segmented",true); flight.addProperty("finishFlying",true); flight.addProperty("timeout_ms",20000);
        PlayerNavigation.State flying=new PlayerNavigation.State(new PlayerNavigation.Request(flight),frame(.5,10,true,false),new Object(),new Object(),"uuid");
        FlightPathPlanner.Probe airBoundary=(sweep,deadline)->sweep.from.distance(sweep.to)<.00001 && Math.abs(sweep.x-20.5)<.00001 ? FlightPathPlanner.Check.BLOCKED : FlightPathPlanner.Check.CLEAR;
        for(int i=0;i<2000 && !flying.flightRouteReady;i++) flying.prepareFlight(airBoundary,.6,1.8,Long.MAX_VALUE);
        check(flying.flightRouteReady && flying.route.get(0).x!=20.5 && flying.route.get(flying.route.size()-1).x==80.5,"Flight planning also shifts an unsafe artificial gate without moving the exact arrival.");
        JsonObject compact=new JsonObject(); compact.add("anchor",xyz(0,64,0)); JsonArray palette=new JsonArray(); JsonObject air=new JsonObject(); air.addProperty("kind","air"); palette.add(air); compact.add("palette",palette);
        JsonArray boxes=new JsonArray(); JsonObject box=new JsonObject(); box.add("min",xyz(0,0,0)); box.add("max",xyz(31,3,31)); box.addProperty("palette",0); boxes.add(box); compact.add("boxes",boxes); compact.addProperty("includePlacementOrder",false);
        SchematicGoal.Comparison comparison=new SchematicGoal.Comparison(compact);
        check(comparison.source.cells().size()==4096 && comparison.observationPositions().size()==4096,"A compact cross-chunk region expands with bounded exact predicates and target-only reads.");
        JsonObject actual=new JsonObject(); actual.addProperty("loaded",true); actual.addProperty("air",true); actual.addProperty("replaceable",true);
        while(!comparison.advance((x,y,z)->actual,0,64,0,false,Long.MAX_VALUE)) { }
        check(comparison.finish().get("goalSatisfied").getAsBoolean() && comparison.supportPositions().size()==0,"Read-only large schematic acceptance skips unneeded neighbour reads.");
        box.add("max",xyz(63,3,63)); expect("invalid_args",()->SchematicGoal.source(compact));
        box.add("max",xyz(0,0,0)); boxes.add(box); expect("invalid_args",()->SchematicGoal.source(compact));
        check(WorldBlockCache.centered(0)==0 && WorldBlockCache.centered(1)==1 && WorldBlockCache.centered(2)==-1,"Cache warming prioritizes nearby sections without loading them.");
    }

    private static void compactSuccess() {
        for (boolean details : new boolean[] {false, true}) {
            JsonObject request = args("walking", 2.5); if (details) request.addProperty("includeDetails", true);
            PlayerNavigation.State state = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, false, true), new Object(), new Object(), "uuid");
            prepareClear(state); state.waypoint = state.route.size() - 1;
            state.requireContext(frame(2.5, 11, false, true)); state.trace(11, "observed", state.held);
            check(!state.acknowledged(), "A compact report does not skip the initial real stationary observation.");
            state.requireContext(frame(2.5, 12, false, true)); check(state.acknowledged(), "Both report modes retain actual dual arrival validation.");
            JsonObject receipt = state.completed(20), plan = receipt.getAsJsonObject("walkingPlan");
            check(receipt.get("detailsIncluded").getAsBoolean() == details && plan.has("route") == details && plan.has("trace") == details,
                    "Detailed trajectories are explicit while ordinary reports keep only bounded counts.");
            check(receipt.getAsJsonObject("serverPoseAck").get("sampleId").getAsLong() == 12
                    && receipt.getAsJsonObject("actualClientPose").get("x").getAsDouble() == 2.5
                    && receipt.get("inputKeysReleased").getAsBoolean(), "Report size never weakens actual pose/key evidence.");
        }
    }
    private static void unreachableFirstEndpoint() {
        JsonObject request = args("walking",1.5); request.remove("x"); request.remove("y"); request.remove("z");
        JsonObject goal = new JsonObject(); goal.addProperty("type","any"); JsonArray goals = new JsonArray();
        for (double x : new double[] {1.5,-1.5}) { JsonObject leaf=xyz(x,64,.5); leaf.addProperty("type","point"); goals.add(leaf); }
        goal.add("goals",goals); request.add("goal",goal);
        PlayerNavigation.State state = new PlayerNavigation.State(new PlayerNavigation.Request(request),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        WalkingPathPlanner.Probe islands = (sweep,deadline) -> {
            if (sweep.from.distance(sweep.to) < .00001 && sweep.x == 1.5 && sweep.z == .5) return WalkingPathPlanner.Check.CLEAR;
            return sweep.x-sweep.width/2 >= -3 && sweep.x+sweep.width/2 <= .9 && Math.abs(sweep.z-.5)+sweep.width/2 <= .9
                    ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
        };
        for (int i=0;i<1000 && !state.walkingRouteReady;i++) state.prepareWalking(islands,.6,1.8,Long.MAX_VALUE);
        check(state.walkingRouteReady && state.request.destination.x == -1.5 && state.goalRouteAttempts == 2,
                "A safe isolated first stand is skipped for a proved reachable alternative before any movement.");
        check(!state.mutation && state.held.isEmpty(), "Candidate comparison never dispatches keys or retries an executed action.");
        PlayerNavigation.State impossible=state("walking",1.5);
        try {
            for(int i=0;i<1000;i++) impossible.prepareWalking((sweep,deadline)->WalkingPathPlanner.Check.BLOCKED,.6,1.8,Long.MAX_VALUE);
            throw new AssertionError("Expected an unsafe start refusal");
        } catch(BridgeException failure) {
            String message=impossible.diagnostic(failure).getMessage(); int begin=message.indexOf("Walking plan diagnostic: ")+"Walking plan diagnostic: ".length();
            int end=message.indexOf(" Arrival pose diagnostic:");
            JsonObject evidence=new com.google.gson.JsonParser().parse(message.substring(begin,end)).getAsJsonObject();
            check("start_unsafe_blocked".equals(evidence.get("reason").getAsString()) && evidence.getAsJsonObject("rejected").get("blocked").getAsInt()==1,
                    "Walking failure retains bounded valid planner evidence after frontier cleanup.");
        }
    }

    private static void requests() {
        PlayerNavigation.Request walking = new PlayerNavigation.Request(args(null, 9.5)); List<PlayerNavigation.Point> route = walking.route(new PlayerNavigation.Point(.5, 64, .5));
        check("walking".equals(walking.strategy) && walking.timeout == 6000 && !walking.finishFlying && route.size() == 1, "default walking never chooses a flight or teleport strategy");
        check(PlayerNavigation.probes(new PlayerNavigation.Point(.5, 64, .5), route, false).size() == 37, "nine-block complete corridor uses quarter-block bounded probes");
        check(PlayerNavigation.probes(new PlayerNavigation.Point(.5, 64, .5), route, true).size() == 1, "explicit teleport checks actual destination without claiming traversal");
        JsonObject detour = args("walking", 9.5); JsonArray waypoints = new JsonArray(); waypoints.add(xyz(.5, 64, 1.5)); waypoints.add(xyz(9.5, 64, 1.5)); detour.add("waypoints", waypoints);
        check(new PlayerNavigation.Request(detour).route(new PlayerNavigation.Point(.5, 64, .5)).size() == 3, "two explicit flat intermediate points retain their declared route");
        waypoints.add(xyz(10, 64, 1.5)); expect("invalid_args", () -> new PlayerNavigation.Request(detour));
        for (String strategy : new String[] {"walking", "developer_teleport"}) { JsonObject bad = args(strategy, 1.5); bad.addProperty("finishFlying", true); expect("invalid_args", () -> new PlayerNavigation.Request(bad)); }
        for (String key : new String[] {"lookAt", "waypoints"}) { JsonObject bad = args(null, 1.5); bad.add(key, JsonNull.INSTANCE); expect("invalid_args", () -> new PlayerNavigation.Request(bad)); }
        JsonObject unknown = args(null, 1.5); unknown.addProperty("fallback", true); expect("invalid_args", () -> new PlayerNavigation.Request(unknown));
        JsonObject badNested = args(null, 1.5); JsonObject look = xyz(1, 64, 1); look.addProperty("strategy", "walking"); badNested.add("lookAt", look); expect("invalid_args", () -> new PlayerNavigation.Request(badNested));
        JsonObject step = args(null, 1.5); step.addProperty("y", 65);
        check(new PlayerNavigation.Request(step).route(new PlayerNavigation.Point(.5,64,.5)).size()==1,"Walking declares height goals for full geometry planning before input");
        expect("unsupported", () -> new PlayerNavigation.Request(args("walking", 33.5)).route(new PlayerNavigation.Point(.5, 64, .5)));
        for (int timeout : new int[] {249, 6501}) { JsonObject bad = args(null, .5); bad.addProperty("timeout_ms", timeout); expect("invalid_args", () -> new PlayerNavigation.Request(bad)); }
        PlayerNavigation.Request flight = new PlayerNavigation.Request(args("ability_flight", 9.5)); List<PlayerNavigation.Point> airborne = flight.route(new PlayerNavigation.Point(.5, 64, .5));
        check(airborne.size() == 3 && airborne.get(0).y == 66 && airborne.get(1).y == 66 && airborne.get(2).y == 64, "flight route takes off, commutes and normally lands");
        JsonObject high = args("ability_flight", .5); high.addProperty("y", 253); expect("unsupported", () -> new PlayerNavigation.Request(high).route(new PlayerNavigation.Point(.5, 253, .5)));
    }
    private static void contexts() {
        PlayerNavigation.State walking = state("walking", 9.5);
        JsonObject stale = frame(.5, 9, false, true); expect("navigation_server_control_unavailable", () -> walking.requireContext(stale));
        JsonObject wrongId = frame(.5, 11, false, true); wrongId.getAsJsonObject("serverControl").addProperty("playerId", "other"); expect("navigation_server_control_unavailable", () -> walking.requireContext(wrongId));
        JsonObject mode = frame(.5, 11, false, true); mode.getAsJsonObject("player").addProperty("creative", true); expect("navigation_server_control_unavailable", () -> walking.requireContext(mode));
        JsonObject windows = frame(.5, 11, false, true); windows.getAsJsonObject("serverControl").getAsJsonObject("clientContainer").addProperty("windowId", 1); expect("navigation_server_control_unavailable", () -> walking.requireContext(windows));
        JsonObject serverFlight = frame(.5, 11, false, true); serverFlight.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("flying", true); expect("invalid_state", () -> walking.requireContext(serverFlight));
        for (String key : new String[] {"sessionId", "profile", "screenId"}) { JsonObject invalid = frame(.5, 11, false, true); invalid.addProperty(key, "other"); expect("stale_world", () -> walking.requireContext(invalid)); }
        for (String key : new String[] {"paused", "screenOpen"}) { JsonObject invalid = frame(.5, 11, false, true); invalid.addProperty(key, true); expect("stale_world", () -> walking.requireContext(invalid)); }
        JsonObject background = frame(.5, 11, false, true); background.getAsJsonObject("background").addProperty("enabled", false); expect("stale_world", () -> walking.requireContext(background));
        JsonObject dimension = frame(.5, 11, false, true); dimension.getAsJsonObject("world").addProperty("dimension", 1); expect("stale_world", () -> walking.requireContext(dimension));
        PlayerNavigation.State flight = state("ability_flight", 9.5);
        for (boolean client : new boolean[] {false, true}) { JsonObject invalid = frame(.5, 11, false, true); (client ? invalid.getAsJsonObject("player") : invalid.getAsJsonObject("serverControl").getAsJsonObject("pose")).addProperty("allowFlying", false); expect("permission_denied", () -> flight.requireContext(invalid)); }
        for (String strategy : new String[] {"walking", "walking_step", "ability_flight", "developer_teleport"}) {
            JsonObject creativeFrame = frame(.5, 10, false, true);
            creativeFrame.getAsJsonObject("player").addProperty("creative", true);
            creativeFrame.getAsJsonObject("serverControl").addProperty("creative", true);
            JsonObject generic = args(strategy, 1.5);
            if ("walking_step".equals(strategy)) generic.addProperty("y", 65);
            new PlayerNavigation.State(new PlayerNavigation.Request(generic), creativeFrame, new Object(), new Object(), "uuid");
            check(true, "generic native navigation accepts unchanged creative mode: " + strategy);
            generic.addProperty("requireSurvivalActor", true);
            if (!"developer_teleport".equals(strategy)) expect("permission_denied", () -> new PlayerNavigation.State(new PlayerNavigation.Request(generic), creativeFrame, new Object(), new Object(), "uuid"));
        }
        String key = FlightCapabilityEvidence.key("session", 0, "uuid", false, true, true, "equipment-A");
        check(!key.equals(FlightCapabilityEvidence.key("session", 0, "uuid", false, true, true, "equipment-B")), "equipment changes invalidate flight evidence");
        check(!key.equals(FlightCapabilityEvidence.key("session", 1, "uuid", false, true, true, "equipment-A")), "dimension changes invalidate flight evidence");
        check(!key.equals(FlightCapabilityEvidence.key("session", 0, "uuid", false, true, false, "equipment-A")), "authoritative permission changes invalidate flight evidence");
        walking.abort = true; expect("timeout", () -> walking.requireContext(frame(.5, 11, false, true)));
    }
    private static java.util.Set<String> dispatch(PlayerNavigation.State flight, long tick) {
        java.util.Set<String> next = flight.next(tick, .05F); flight.dispatched(next, tick); flight.held.clear(); flight.held.addAll(next); return next;
    }
    private static void prepareFlight(PlayerNavigation.State state) {
        for (int slice = 0; slice < 100 && !state.flightRouteReady; slice++)
            state.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.CLEAR, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS);
        check(state.flightRouteReady && state.routeReady(), "Complete scalar flight geometry must precede owned native input.");
    }
    private static JsonObject flightFrame(double x, double y, long sample, double my) {
        JsonObject frame = frame(x, sample, true, false);
        for (JsonObject pose : new JsonObject[] {frame.getAsJsonObject("player"), frame.getAsJsonObject("serverControl").getAsJsonObject("pose")}) {
            pose.addProperty("feetY", y); pose.addProperty("motionY", my);
        }
        return frame;
    }
    private static void normalFlightKeys() {
        PlayerNavigation.State flight = state("ability_flight", 9.5); prepareFlight(flight); flight.mutation = true;
        check(dispatch(flight, 0).contains("jump") && flight.jumpTicks == 1, "First native jump pulse is requested.");
        check(flight.next(0).contains("jump") && flight.jumpTicks == 1, "The same client tick retains owned input without advancing acquisition.");
        check(dispatch(flight, 1).isEmpty() && dispatch(flight, 2).contains("jump") && dispatch(flight, 3).contains("jump"), "The second double-tap press stays held through ACK to avoid a third rising edge.");
        flight.requireContext(frame(.5, 11, true, false));
        check(dispatch(flight, 4).contains("jump") && flight.flightObserved && flight.flightSample == 11 && flight.jumpEdges == 2, "Actual later client/server flight ascends without another jump edge.");
        flight.requireContext(flightFrame(.5, 66, 12, 0));
        check(dispatch(flight, 5).contains("forward") && flight.waypoint == 1, "Actual settled cruise advances to horizontal transit.");
        flight.requireContext(flightFrame(.5, 65.7, 13, 0));
        check(!dispatch(flight, 6).contains("jump"), "A later ascend correction cannot create a jump edge inside the vanilla toggle window.");
        check(dispatch(flight, 10).contains("jump") && flight.jumpEdges == 3, "The next jump edge is allowed only after eight actual client ticks.");
        PlayerNavigation.State denied = state("ability_flight", 9.5); prepareFlight(denied); denied.jumpTicks = 20;
        expect("flight_not_confirmed", () -> denied.next(20));
        PlayerNavigation.State teleport = state("developer_teleport", 9.5);
        check(teleport.next(0).isEmpty() && !teleport.teleportSent, "Teleport scalar controller invents neither input nor arrival.");
    }
    private static void flightFeedback() {
        PlayerNavigation.State flight = state("ability_flight", 9.5); prepareFlight(flight); flight.flightObserved = true; flight.flightSample = 11;
        flight.requireContext(flightFrame(.5, 65.8, 12, .2));
        check(dispatch(flight, 20).contains("sneak"), "Actual upward inertia is braked before height overshoot even below the target.");
        flight.requireContext(flightFrame(.5, 66.2, 13, -.2));
        check(dispatch(flight, 30).contains("jump"), "Actual downward inertia is braked before undershoot even above the target.");
        flight.waypoint = 1; JsonObject fast = flightFrame(9.3, 66, 14, 0); fast.getAsJsonObject("player").addProperty("motionX", .25);
        flight.requireContext(fast); check(dispatch(flight, 40).contains("back"), "Native reverse input brakes real horizontal inertia at a waypoint.");
        flight.requireContext(frame(4.5, 15, false, true));
        try { flight.next(50); throw new AssertionError("Expected flight_lost"); }
        catch (BridgeException error) {
            BridgeException diagnostic = flight.diagnostic(error);
            check("flight_lost".equals(diagnostic.code) && diagnostic.getMessage().contains("client_onGround")
                    && diagnostic.getMessage().contains("client_motionY") && diagnostic.getMessage().contains("jumpEdges"), "Loss diagnostics retain phase, actual grounding, motion and edge evidence.");
        }
        for (int i = 0; i < 200; i++) flight.trace(100 + i, "keys", java.util.Collections.<String>emptySet());
        JsonObject trace = flight.traceReceipt(96);
        check(trace.getAsJsonArray("records").size() == 96 && trace.get("recordsDropped").getAsInt() > 0, "Flight trace memory has a strict record bound.");
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(); keys.add("jump");
        List<FlightPathPlanner.Sweep> inertia = PlayerNavigation.inertiaSweeps(new PlayerNavigation.Point(0, 4, 0), .2, -.3, 0,
                keys, -90, .05, true, Double.NaN, .6, 1.8);
        check(inertia.get(0).feetY < 4 && inertia.get(0).widthX > .6 && inertia.get(0).widthZ == .6 && inertia.get(1).height >= 1.8, "Every native step checks the full actual body plus real vertical/horizontal inertia.");
        expect("unsupported", () -> PlayerNavigation.inertiaSweeps(new PlayerNavigation.Point(0, 4, 0), 0, 0, 0, keys, 0, 0, true, Double.NaN, .6, 1.8));
    }
    private static void verticalSettling() {
        check(PlayerNavigation.verticalInput(65.83, 0, 66, 20, 0, false) == 0,
                "Already settled native height inside the unchanged arrival tolerance needs no repeated correction.");
        check(PlayerNavigation.verticalInput(65.8, .2, 66, 20, 0, false) == -1
                && PlayerNavigation.verticalInput(66.2, -.2, 66, 20, 0, false) == 1,
                "Fresh real vertical motion determines braking direction on either side of the target.");
        check(PlayerNavigation.verticalInput(64, 0, 66, 7, 0, false) != 1
                && PlayerNavigation.verticalInput(64, 0, 66, 8, 0, false) == 1,
                "Predicted inputs retain the exact eight-client-tick jump rising-edge guard.");
        expect("unsupported", () -> PlayerNavigation.verticalInput(64, Double.NaN, 66, 20, 0, false));
        expect("unsupported", () -> PlayerNavigation.verticalInput(64, 16.1, 66, 20, 0, false));
        double[] old = verticalFixture(65.8181, 0, true), improved = verticalFixture(65.8181, 0, false);
        check(old[2] >= 8 && improved[2] == 1 && improved[3] == 2 && old[3] > improved[3],
                "The observed flat-height fixture settles with one jump edge instead of chasing the old narrow deadband.");
        for (int offset = -16; offset <= 16; offset++) for (double velocity : new double[] {-.2, -.05, 0, .05, .2}) {
            double[] actual = verticalFixture(66 + offset * .05, velocity, false);
            check(Math.abs(actual[0] - 66) <= PlayerNavigation.TOLERANCE && actual[1] == 0 && actual[4] < 120,
                    "Bounded native vertical fixtures settle inside the original tolerance with released input.");
        }
        PlayerNavigation.State turn = state("ability_flight", 9.5); prepareFlight(turn);
        turn.flightObserved = true; turn.requireContext(flightFrame(.5, 66, 11, .09)); dispatch(turn, 20);
        check(turn.waypoint == 0, "An actual near waypoint retains its original stop gate while vertical inertia is high.");
        turn.requireContext(flightFrame(.5, 65.83, 12, 0)); dispatch(turn, 21);
        check(turn.waypoint == 1 && turn.held.contains("forward") && !turn.held.contains("jump") && !turn.held.contains("sneak"),
                "A truly settled waypoint advances normally without starting another vertical pulse.");
    }
    private static double[] verticalFixture(double height, double velocity, boolean legacy) {
        long edge = -1000;
        boolean held = false;
        int edges = 0, inputs = 0, lastInput = -1;
        // Independent scalar native physics: EntityPlayerSP adds +/- .15, EntityLivingBase
        // snaps small velocity before movement, then EntityPlayer applies .6 flight drag.
        for (int tick = 0; tick < 160; tick++) {
            double error = 66 - height - velocity * 2.5;
            int input = legacy ? error > .09 ? 1 : error < -.09 ? -1 : 0
                    : PlayerNavigation.verticalInput(height, velocity, 66, tick, edge, held);
            if (legacy && input == 1 && !held && tick - edge < PlayerNavigation.FLIGHT_JUMP_EDGE_TICKS) input = 0;
            if (input == 1 && !held) {
                check(tick - edge >= PlayerNavigation.FLIGHT_JUMP_EDGE_TICKS, "Every actual scalar jump edge preserves the guard.");
                edge = tick; edges++;
            }
            held = input == 1;
            if (input != 0) { inputs++; lastInput = tick; }
            velocity += input * .15;
            if (Math.abs(velocity) < .005) velocity = 0;
            height += velocity; velocity *= .6;
        }
        return new double[] {height, velocity, edges, inputs, lastInput};
    }
    private static void horizontalSettling() {
        PlayerNavigation.Point origin = new PlayerNavigation.Point(0, 64, 0);
        PlayerNavigation.Point stop = PlayerNavigation.horizontalStop(origin, .05, 0, 0, 0, 0, 0, 0);
        check(Math.abs(stop.x - .5029827195884791) < .000000000001 && stop.z == 0,
                "Complete native coast uses float .91 friction, rather than a three-tick geometry horizon.");
        check(PlayerNavigation.horizontalStop(origin, .0049, -.0049, 0, 0, 0, 0, 0).horizontal(origin) == 0,
                "Native .005 cutoff is per axis even when vector speed exceeds .005.");
        check(PlayerNavigation.horizontalStop(origin, .005, -.005, 0, 0, 0, 0, 0).x == .005
                && PlayerNavigation.horizontalStop(origin, .005, -.005, 0, 0, 0, 0, 0).z == -.005,
                "An axis exactly at .005 moves before its next native cutoff.");
        for (double vx : new double[] {-.25, -.05, -.006569, -.005, -.0049, 0, .0049, .005, .006569, .05, .25}) {
            for (double vz : new double[] {-.12, -.0051, -.0049, 0, .0049, .0051, .12}) {
                double[] actual = {0, 0, vx, vz};
                for (int tick = 0; tick < 96 && (actual[2] != 0 || actual[3] != 0); tick++) nativeHorizontalTick(actual, 0, 0, .05F, false);
                PlayerNavigation.Point predicted = PlayerNavigation.horizontalStop(origin, vx, vz, 0, 0, 0, 0, 0);
                check(actual[2] == 0 && actual[3] == 0 && Math.hypot(predicted.x - actual[0], predicted.z - actual[1]) < .000000000001,
                        "Independent native trajectory matches the bounded signed two-axis unpowered stop.");
            }
        }
        PlayerNavigation.HorizontalFlightInput outgoing = PlayerNavigation.horizontalInput(origin, new PlayerNavigation.Point(.17, 64, 0), .05, 0, .05F, false);
        check(outgoing.first < 0 && outgoing.stop.horizontal(new PlayerNavigation.Point(.17, 64, 0)) < PlayerNavigation.TOLERANCE,
                "Currently near but coasting out of tolerance requires a native brake before input release.");
        check(PlayerNavigation.horizontalInput(origin, stop, .05, 0, .05F, false).first == 0,
                "Currently outside but naturally coasting to the target prefers no new input.");
        PlayerNavigation.HorizontalFlightInput opposite = PlayerNavigation.horizontalInput(origin, new PlayerNavigation.Point(.17, 64, 0), -.05, 0, .05F, false);
        double[] reversed = {0, 0, -.05, 0}; nativeHorizontalTick(reversed, opposite.first, opposite.yaw, .05F, false);
        check(opposite.first != 0 && reversed[2] > -.05 && opposite.stop.horizontal(new PlayerNavigation.Point(.17, 64, 0)) < PlayerNavigation.TOLERANCE,
                "Opposite actual motion receives a native impulse toward the target regardless of the chosen steering/key representation.");
        check(PlayerNavigation.horizontalInput(origin, new PlayerNavigation.Point(PlayerNavigation.TOLERANCE, 64, 0), 0, 0, .05F, false).first == 0,
                "A genuinely idle pose on the existing arrival boundary receives no gratuitous movement.");
        check(PlayerNavigation.horizontalInput(origin, new PlayerNavigation.Point(.10, 64, .10), .05, .05, .05F, false).first != 0,
                "A radial two-axis outgoing coast cannot pass an axis-only arrival decision.");
        for (double speed : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -.05, .50001})
            expect("unsupported", () -> PlayerNavigation.horizontalInput(origin, stop, 0, 0, speed, false));
        for (double velocity : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 16.01})
            expect("unsupported", () -> PlayerNavigation.horizontalStop(origin, velocity, 0, 0, 0, 0, 0, 0));
        expect("unsupported", () -> PlayerNavigation.horizontalInput(origin, new PlayerNavigation.Point(Double.NaN, 64, 0), 0, 0, .05F, false));
        expect("unsupported", () -> PlayerNavigation.horizontalStop(origin, 0, 0, .05, 0, 1, -1, 0));
        check(PlayerNavigation.horizontalInput(origin, origin, 0, 0, .5F, true).first == 0,
                "The largest allowed native speed invents no input for a genuinely idle target.");
        for (float speed : new float[] {.01F, .025F, .05F, .1F}) for (boolean sneak : new boolean[] {false, true}) {
            for (double[] vector : new double[][] {{.181, 0}, {-.7, 0}, {0, .7}, {.55, -.45}}) {
                for (double[] motion : new double[][] {{0, 0}, {.05, -.03}, {-.12, .07}}) {
                    double[] actual = horizontalFixture(vector[0], vector[1], motion[0], motion[1], speed, sneak, 1024);
                    check(Math.hypot(actual[0] - vector[0], actual[1] - vector[1]) <= PlayerNavigation.TOLERANCE
                            && actual[2] == 0 && actual[3] == 0 && actual[4] < 1024,
                            "Native closed-loop short trajectories settle without synthetic pose or velocity writes: speed=" + speed
                                    + " sneak=" + sneak + " target=" + vector[0] + "," + vector[1] + " final=" + actual[0] + "," + actual[1]);
                }
            }
        }
        for (double[] vector : new double[][] {{24, 0}, {-24, 0}, {0, 24}, {16, -17}}) {
            double[] actual = horizontalFixture(vector[0], vector[1], -.05, .08, .05F, false, 256);
            check(Math.hypot(actual[0] - vector[0], actual[1] - vector[1]) <= PlayerNavigation.TOLERANCE
                    && actual[2] == 0 && actual[3] == 0 && actual[4] < 256,
                    "Long normal native scalar trajectories retain a finite complete stop; model ticks are not live wall time.");
        }
        JsonObject request = args("ability_flight", .67); request.addProperty("finishFlying", true);
        PlayerNavigation.State near = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
        prepareFlight(near); near.flightObserved = true;
        JsonObject moving = frame(.5, 11, true, false); moving.getAsJsonObject("player").addProperty("motionX", .05);
        near.requireContext(moving);
        check(near.positionReady() && !near.inputArrivalReady(.05F), "Existing actual near predicate cannot bypass predicted outgoing coast.");
        check(near.next(20, .05F).contains("back"), "Queued/native input continues braking while the unchanged actual position predicate is already true.");
        near.held.clear(); check(!near.acknowledged() && "stable_pose_sample_pending".equals(near.arrivalAckWait),
                "Coast policy does not weaken or replace the original first stationary-pose observation.");
        JsonObject inward = frame(.5, 12, true, false); inward.getAsJsonObject("player").addProperty("motionX", .01);
        near.requireContext(inward); check(near.inputArrivalReady(.05F), "A near actual pose with a safe full coast can release immediately.");
        expect("unsupported", () -> near.inputArrivalReady(Double.NaN));
        near.requireContext(frame(.67, 13, true, false)); check(near.inputArrivalReady(.05F), "A truly idle airborne arrival remains ready for the original strict ACK.");
        PlayerNavigation.State walking = state("walking", .54); prepareClear(walking);
        walking.requireContext(frame(.54, 11, false, true)); check(walking.inputArrivalReady(Double.NaN) == walking.positionReady(),
                "Walking needs no new flight metadata and retains its exact release predicate.");
        try {
            String bridge = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/java/com/Goderium/BridgeNH/common/bridge/GameBridge.java")), java.nio.charset.StandardCharsets.UTF_8);
            String queued = bridge.substring(bridge.indexOf("private GuardedContext.Result navigationClient("), bridge.indexOf("private boolean navigationInputArrivalReady("));
            String maintenance = bridge.substring(bridge.indexOf("} else if (\"ability_flight\".equals(state.request.strategy) && state.mutation"), bridge.indexOf("private JsonObject placeBlock("));
            for (String caller : new String[] {queued, maintenance}) check(caller.contains("navigationInputArrivalReady(state, client, deadline)")
                    && !caller.contains("if (state.positionReady())") && caller.contains("state.next(clientTicks, client.thePlayer.capabilities.getFlySpeed())"),
                    "Both real callers must use the shared coast release decision and fresh actual fly speed, without the old near-position bypass.");
        } catch (java.io.IOException error) { throw new AssertionError("Missing current caller source for integration regression", error); }
    }
    private static double[] horizontalFixture(double targetX, double targetZ, double vx, double vz, float speed, boolean sneak, int limit) {
        double[] actual = {0, 0, vx, vz, 0};
        PlayerNavigation.Point target = new PlayerNavigation.Point(targetX, 64, targetZ);
        for (int tick = 0; tick < limit; tick++) {
            PlayerNavigation.HorizontalFlightInput input = PlayerNavigation.horizontalInput(new PlayerNavigation.Point(actual[0], 64, actual[1]), target, actual[2], actual[3], speed, sneak);
            nativeHorizontalTick(actual, input.first, input.yaw, speed, sneak); actual[4] = tick + 1;
            if (input.first == 0 && actual[2] == 0 && actual[3] == 0 && Math.hypot(targetX - actual[0], targetZ - actual[1]) <= PlayerNavigation.TOLERANCE) break;
        }
        return actual;
    }
    /** Independent native tick: actual per-axis cutoff precedes moveFlying's float arithmetic. */
    private static void nativeHorizontalTick(double[] actor, int input, float yaw, float speed, boolean sneak) {
        if (Math.abs(actor[2]) < .005) actor[2] = 0;
        if (Math.abs(actor[3]) < .005) actor[3] = 0;
        float forward = input;
        if (sneak) forward = (float) ((double) forward * .3D);
        forward *= .98F;
        float length = MathHelper.sqrt_float(forward * forward);
        if (forward * forward >= 1.0E-4F) {
            if (length < 1F) length = 1F;
            forward *= speed / length;
            float radians = yaw * (float) Math.PI / 180F;
            actor[2] -= (double) (forward * MathHelper.sin(radians)); actor[3] += (double) (forward * MathHelper.cos(radians));
        }
        actor[0] += actor[2]; actor[1] += actor[3]; actor[2] *= (double) .91F; actor[3] *= (double) .91F;
    }
    private static void flightPlanning() {
        JsonObject body = walkingBody(); body.remove("canStand"); body.remove("fullSupportCoverage");
        check(PlayerNavigation.flightCheck(body) == FlightPathPlanner.Check.CLEAR, "Airborne body proof needs complete collision/liquid/hazard coverage rather than floor support.");
        for (String key : new String[] {"loaded", "occupiable", "entityCoveragePartial", "liquid", "hazard"}) {
            JsonObject absent = walkingBody(); absent.remove(key);
            check(PlayerNavigation.flightCheck(absent) == FlightPathPlanner.Check.UNKNOWN, "Missing flight " + key + " cannot authorize movement.");
        }
        JsonObject partial = walkingBody(); partial.addProperty("entityCoveragePartial", true);
        check(PlayerNavigation.flightCheck(partial) == FlightPathPlanner.Check.PARTIAL, "Incomplete entity geometry is not clear flight space.");
        for (String key : new String[] {"liquid", "hazard"}) {
            JsonObject unsafe = walkingBody(); unsafe.addProperty(key, true);
            check(PlayerNavigation.flightCheck(unsafe) == FlightPathPlanner.Check.BLOCKED, "The rectangular flight read never passes native " + key + " space.");
        }
        JsonObject unknown = walkingBody(); unknown.addProperty("status", "unknown");
        check(PlayerNavigation.flightCheck(unknown) == FlightPathPlanner.Check.UNKNOWN, "Unknown rectangular body coverage cannot authorize movement.");
        PlayerNavigation.State pending = state("ability_flight", 9.5);
        check(!pending.flightRouteReady && !pending.positionReady(), "Flight cannot acquire input or arrive before whole route planning.");
        expect("invalid_state", () -> pending.next(0));
        check(!pending.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.PARTIAL, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS)
                && !pending.mutation && pending.held.isEmpty(), "Incomplete flight planning owns no movement.");
        expect("invalid_state", () -> pending.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.CLEAR, .7, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS));
        PlayerNavigation.State moved = state("ability_flight", 9.5); moved.requireContext(frame(.5001, 11, false, true));
        expect("invalid_state", () -> moved.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.CLEAR, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS));
        PlayerNavigation.State ready = state("ability_flight", 9.5); prepareFlight(ready);
        List<FlightPathPlanner.Sweep> proof = PlayerNavigation.flightSweeps(ready.start, ready.route, .6, 1.8);
        check(proof.size() <= 384 && proof.get(1).height > 1.8 && ready.flightPlanner.routeDistance() == 13, "Published route has bounded continuous 3D body sweeps, including ascent height.");
    }
    private static void groundedFlightInertia() {
        double gravityResidual = -.0784000015258789;
        JsonObject support = walkingBody();
        check(PlayerNavigation.groundedFloorProven(true, false, gravityResidual, support), "Actual grounded negative gravity residual can use freshly complete current support.");
        check(!PlayerNavigation.groundedFloorProven(false, false, gravityResidual, support)
                && !PlayerNavigation.groundedFloorProven(true, true, gravityResidual, support)
                && !PlayerNavigation.groundedFloorProven(true, false, .02, support), "Neither airborne flight nor positive ascent is treated as supported ground coast.");
        for (String key : new String[] {"loaded", "occupiable", "canStand", "fullSupportCoverage"}) {
            JsonObject incomplete = walkingBody(); incomplete.addProperty(key, false);
            check(!PlayerNavigation.groundedFloorProven(true, false, gravityResidual, incomplete), "Ground flag cannot bypass missing actual " + key + " proof.");
        }
        JsonObject partial = walkingBody(); partial.addProperty("status", "partial");
        check(!PlayerNavigation.groundedFloorProven(true, false, gravityResidual, partial), "Incomplete native support never clamps ground inertia.");
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(); keys.add("jump");
        PlayerNavigation.Point current = new PlayerNavigation.Point(0, 4, 0);
        List<FlightPathPlanner.Sweep> grounded = PlayerNavigation.inertiaSweeps(current, 0, gravityResidual, 0, keys, 0, .05, false, 4, .6, 1.8);
        check(grounded.get(0).feetY == 4 && grounded.get(0).height == 1.8, "Supported idle grounded gravity residual is not predicted through the floor.");
        check(grounded.get(1).feetY == 4 && grounded.get(1).height >= 3.3, "Supported ground clamp retains the entire normal takeoff headroom proof.");
        List<FlightPathPlanner.Sweep> airborne = PlayerNavigation.inertiaSweeps(current, 0, gravityResidual, 0,
                java.util.Collections.<String>emptySet(), 0, .05, true, Double.NaN, .6, 1.8);
        check(airborne.get(0).feetY < 4 && airborne.get(1).feetY < 4, "Actual airborne downward inertia retains its floor collision checks.");
        check(PlayerNavigation.descentFloorCandidate(true, 4.269581648904425, 4, 0),
                "Recorded low flight descent may inspect actual floor support before predicting native collision clipping.");
        check(!PlayerNavigation.descentFloorCandidate(false, 4.2, 4, 0)
                && !PlayerNavigation.descentFloorCandidate(true, 3.9, 4, 0)
                && !PlayerNavigation.descentFloorCandidate(true, 5.01, 4, 0)
                && !PlayerNavigation.descentFloorCandidate(true, 4.2, 4, .01)
                && !PlayerNavigation.descentFloorCandidate(true, Double.NaN, 4, 0),
                "Low descent support discovery excludes other postures, ascent, high transit and unknown positions.");
        java.util.Set<String> descentKeys = new java.util.LinkedHashSet<String>(); descentKeys.add("forward"); descentKeys.add("sneak");
        PlayerNavigation.Point low = new PlayerNavigation.Point(83.86879757146305, 4.269581648904425, 43.078998842378496);
        List<FlightPathPlanner.Sweep> unsupported = PlayerNavigation.inertiaSweeps(low, 0, 0, 0, descentKeys, -16.746758, .05, true, Double.NaN, .6, 1.8);
        List<FlightPathPlanner.Sweep> supported = PlayerNavigation.inertiaSweeps(low, 0, 0, 0, descentKeys, -16.746758, .05, true, 4, .6, 1.8);
        check(unsupported.get(1).feetY < 4 && supported.get(1).feetY == 4,
                "Recorded controlled descent crosses the floor only without an explicit fresh support proof.");
        check(supported.get(1).widthX == unsupported.get(1).widthX && supported.get(1).widthZ == unsupported.get(1).widthZ
                && supported.get(1).feetY + supported.get(1).height >= low.y + 1.8,
                "Supported descent preserves the entire horizontal footprint and overhead envelope for subsequent body/support checks.");
        List<FlightPathPlanner.Sweep> drifting = PlayerNavigation.inertiaSweeps(current, .1, gravityResidual, -.1, keys, 0, .05, false, 4, .6, 1.8);
        check(drifting.get(0).widthX > .6 && drifting.get(0).widthZ > .6, "A supported start still checks complete support beneath horizontally drifting footprint.");
    }
    private static void rectangularFlightBodies() {
        AxisAlignedBB actual = AxisAlignedBB.getBoundingBox(.2, 64, .2, .8, 65.8, .8);
        PlayerNavigation.Point feet = new PlayerNavigation.Point(.5, 64, .5);
        FlightPathPlanner.Sweep sweep = new FlightPathPlanner.Sweep(PlayerNavigation.flightPoint(feet),
                new FlightPathPlanner.Point(2.5, 64.25, .75), .6, 1.8);
        AxisAlignedBB body = PlayerNavigation.translatedFlightBody(actual, feet, sweep, .6, 1.8);
        check(Math.abs(body.minX - .2) < .000001 && body.minY == 64 && Math.abs(body.minZ - .2) < .000001
                && Math.abs(body.maxX - 2.8) < .000001 && Math.abs(body.maxZ - 1.05) < .000001
                && Math.abs(body.maxY - 66.05) < .000001, "Full actual native AABB translates continuously with independent X/Z widths and complete height.");
        check(actual.minX == .2 && actual.minY == 64 && actual.maxY == 65.8, "The live native body is never mutated.");
        AxisAlignedBB padded = PlayerNavigation.translatedFlightBody(actual, feet,
                new FlightPathPlanner.Sweep(sweep.from, sweep.to, .6 + 2*PlayerNavigation.TOLERANCE, 1.8), .6, 1.8);
        check(Math.abs(padded.minX - (body.minX-PlayerNavigation.TOLERANCE)) < .000001
                && Math.abs(padded.maxX - (body.maxX+PlayerNavigation.TOLERANCE)) < .000001
                && Math.abs(padded.minZ - (body.minZ-PlayerNavigation.TOLERANCE)) < .000001
                && Math.abs(padded.maxZ - (body.maxZ+PlayerNavigation.TOLERANCE)) < .000001
                && padded.minY == body.minY && padded.maxY == body.maxY,
                "Planner corner clearance survives the production native-body translation without vertical padding.");
        AxisAlignedBB narrow = PlayerNavigation.translatedFlightBody(actual, feet,
                new FlightPathPlanner.Sweep(sweep.from, sweep.to, .3, 1.8), .6, 1.8);
        check(Math.abs(narrow.minX-body.minX) < .000001 && Math.abs(narrow.maxZ-body.maxZ) < .000001,
                "Declared sweeps never shrink the actual native body.");
        expect("unsupported", () -> PlayerNavigation.translatedFlightBody(null, feet, sweep, .6, 1.8));
        expect("unsupported", () -> PlayerNavigation.translatedFlightBody(AxisAlignedBB.getBoundingBox(.1, 64, .2, .9, 65.8, .8), feet, sweep, .6, 1.8));
        expect("unsupported", () -> PlayerNavigation.translatedFlightBody(AxisAlignedBB.getBoundingBox(.3, 64, .2, .7, 65.8, .8), feet, sweep, .6, 1.8));
        expect("unsupported", () -> PlayerNavigation.translatedFlightBody(actual, new PlayerNavigation.Point(.6, 64, .5), sweep, .6, 1.8));
        expect("unsupported", () -> PlayerNavigation.translatedFlightBody(AxisAlignedBB.getBoundingBox(.2, 64, .2, .8, 65.4, .8), feet, sweep, .6, 1.8));
        java.util.Set<String> none = java.util.Collections.<String>emptySet();
        expect("unsupported", () -> PlayerNavigation.inertiaSweeps(feet, Double.NaN, 0, 0, none, 0, .05, true, Double.NaN, .6, 1.8));
        expect("unsupported", () -> PlayerNavigation.inertiaSweeps(feet, 0, 0, 0, none, Double.POSITIVE_INFINITY, .05, true, Double.NaN, .6, 1.8));
        check(PlayerNavigation.flightCheck(WorldObservationsAdapter.spaceAt(null, null, (AxisAlignedBB) null, Long.MAX_VALUE, false)) == FlightPathPlanner.Check.UNKNOWN,
                "Missing native geometry remains unknown before any world query.");
        check(PlayerNavigation.flightCheck(WorldObservationsAdapter.spaceAt(null, null,
                AxisAlignedBB.getBoundingBox(0, 0, 0, 65, 1, 1), Long.MAX_VALUE, false)) == FlightPathPlanner.Check.UNKNOWN,
                "Oversized native geometry cannot escape its bounded read.");
        check(PlayerNavigation.flightCheck(WorldObservationsAdapter.spaceAt(null, null,
                AxisAlignedBB.getBoundingBox(Double.NaN, 0, 0, 1, 1, 1), Long.MAX_VALUE, false)) == FlightPathPlanner.Check.UNKNOWN,
                "Nonfinite native geometry is unknown before any world query.");
    }
    private static void flightGeometryDiagnostics() {
        PlayerNavigation.State flight = state("ability_flight", 9.5); prepareFlight(flight);
        JsonObject actor = pose(.5, true, false); actor.addProperty("width", .6); actor.addProperty("height", 1.8);
        actor.addProperty("steeringYaw", 90); actor.addProperty("flySpeed", .05);
        AxisAlignedBB nativeBody = AxisAlignedBB.getBoundingBox(.2, 64, .2, .8, 65.8, .8);
        AxisAlignedBB rejected = AxisAlignedBB.getBoundingBox(.2, 64, .2, 1.5, 65.8, .8);
        JsonObject observation = walkingBody(); observation.addProperty("occupiable", false); observation.addProperty("canStand", false);
        observation.addProperty("collisionCount", 1024); observation.addProperty("collisionsTruncated", true);
        JsonArray collisions = new JsonArray(), entities = new JsonArray();
        for (int i = 0; i < 1024; i++) { collisions.add(PlayerNavigation.bounds(rejected)); entities.add(new com.google.gson.JsonPrimitive(i)); }
        observation.add("collisions", collisions); observation.add("blockingEntities", entities);
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(); keys.add("forward"); keys.add("jump");
        flight.rejectedFlightBody("client", "controlled", actor, nativeBody, rejected, keys, observation);
        JsonObject proof = flight.traceReceipt(0).getAsJsonObject("geometryRejection");
        check("client".equals(proof.get("side").getAsString()) && "controlled".equals(proof.get("stage").getAsString())
                && proof.getAsJsonArray("nextKeys").size() == 2 && proof.getAsJsonObject("actual").get("steeringYaw").getAsInt() == 90
                && proof.getAsJsonObject("actual").get("flySpeed").getAsDouble() == .05, "The exact rejected side, next inputs and native steering/speed are retained.");
        check(proof.getAsJsonObject("actual").get("nativeWidthZ").getAsDouble() >= .6
                && proof.getAsJsonObject("rejectedBounds").get("maxX").getAsDouble() == 1.5, "Diagnostics retain full native body dimensions and exact queried bounds.");
        JsonObject blockers = proof.getAsJsonObject("blockers");
        check(blockers.getAsJsonArray("collisionBoxes").size() <= 2 && blockers.getAsJsonArray("blockingEntityIds").size() <= 2
                && blockers.get("blockingEntityCount").getAsInt() == 1024 && proof.toString().length() <= 2000, "Collision/entity summaries have independent finite detail bounds.");
        actor.addProperty("steeringYaw", 0); proof.getAsJsonObject("actual").addProperty("steeringYaw", 0);
        check(flight.traceReceipt(0).getAsJsonObject("geometryRejection").getAsJsonObject("actual").get("steeringYaw").getAsInt() == 90,
                "The rejected primitive evidence is copied, never a mutable response alias.");
        flight.keyReleaseConfirmed = true; flight.ownerClearConfirmed = true;
        for (int i = 0; i < 96; i++) flight.trace(i, "keys_released", java.util.Collections.<String>emptySet());
        StringBuilder reason = new StringBuilder(); for (int i = 0; i < 7000; i++) reason.append('x');
        BridgeException error = flight.diagnostic(new BridgeException("unsupported", reason.toString()));
        JsonObject bounded = new com.google.gson.JsonParser().parse(error.getMessage().substring(error.getMessage().indexOf("Flight diagnostic: ") + 19)).getAsJsonObject();
        check(error.getMessage().length() <= 3500 && bounded.has("geometryRejection") && bounded.get("sourceErrorMessageTruncated").getAsBoolean()
                && bounded.get("keyReleaseConfirmed").getAsBoolean() && bounded.get("ownerClearConfirmed").getAsBoolean(), "Full geometry survives the existing valid bounded error contract and releases.");
        flight.rejectedFlightBody("server", "coast", actor, nativeBody, rejected, keys, observation);
        check("server".equals(flight.traceReceipt(0).getAsJsonObject("geometryRejection").get("side").getAsString()), "Server coast evidence remains distinct from client controlled evidence.");
        for (String key : new String[] {"x", "feetY", "z", "motionX", "motionY", "motionZ", "yaw", "steeringYaw", "flySpeed", "width", "height"})
            actor.addProperty(key, -1.2345678901234567E-308);
        AxisAlignedBB precise = AxisAlignedBB.getBoundingBox(-12345678.123456789, 64.12345678901234, -22345678.12345679,
                -12345677.523456788, 65.92345678901234, -22345677.52345679);
        JsonArray preciseCollisions = new JsonArray(); preciseCollisions.add(PlayerNavigation.bounds(precise)); preciseCollisions.add(PlayerNavigation.bounds(precise));
        StringBuilder escapedStatus = new StringBuilder(); for (int i = 0; i < 32; i++) escapedStatus.append((char) (i % 8 + 1));
        observation.add("collisions", preciseCollisions); observation.addProperty("status", escapedStatus.toString());
        keys.add("back"); keys.add("sneak");
        flight.rejectedFlightBody("client", "controlled_support", actor, precise, precise, keys, observation);
        JsonObject maximum = flight.traceReceipt(0).getAsJsonObject("geometryRejection");
        check(maximum.toString().length() <= 2000 && maximum.getAsJsonArray("nextKeys").size() == 4
                && maximum.has("rejectedBounds") && maximum.has("actual"), "Worst finite numeric evidence preserves all required geometry and inputs within the fixed diagnostic bound.");
        BridgeException maximumError = flight.diagnostic(new BridgeException("unsupported", reason.toString()));
        JsonObject maximumReceipt = new com.google.gson.JsonParser().parse(maximumError.getMessage().substring(maximumError.getMessage().indexOf("Flight diagnostic: ") + 19)).getAsJsonObject();
        check(maximumError.getMessage().length() <= 3500 && maximumReceipt.has("geometryRejection"), "Worst bounded geometry remains valid JSON within the complete protocol error bound.");
        PlayerNavigation.ActualArrivalPose reported = new PlayerNavigation.ActualArrivalPose(-12345678.123456789, 64.12345678901234,
                65.74345678901234, -22345678.12345679, -359999.97F, 89.99999F, true, false, 0, -.0784000015258789, 0, true, 0, 0, false, false, false);
        flight.arrivalPoseReport.dispatch(reported, 96, 12, 100, packet -> {}, () -> flight.started + 123456789);
        BridgeException reportedError = flight.diagnostic(new BridgeException("unsupported", reason.toString()));
        JsonObject reportedReceipt = new com.google.gson.JsonParser().parse(reportedError.getMessage().substring(reportedError.getMessage().indexOf("Flight diagnostic: ") + 19)).getAsJsonObject();
        check(reportedError.getMessage().length() <= 3500 && reportedReceipt.has("geometryRejection")
                && reportedReceipt.getAsJsonObject("arrivalPoseReport").getAsJsonObject("reportedActual").get("stance").getAsDouble() == reported.stance,
                "Worst geometry and one submitted actual packet retain valid bounded error evidence together.");
        flight.clearFlightBodyRejection(); check(!flight.traceReceipt(0).has("geometryRejection"), "A later complete clear read removes stale rejected geometry.");
    }
    private static void flightProtocolBoundary() {
        PlayerNavigation.State flight = state("ability_flight", 9.5); prepareFlight(flight);
        flight.keyReleaseConfirmed = true; flight.ownerClearConfirmed = true;
        for (int i = 0; i < 96; i++) flight.trace(i, "keys_released", java.util.Collections.<String>emptySet());
        String reason = "Actual swept flight body is blocked; no movement fallback.";
        check((reason + " Flight diagnostic: " + flight.traceReceipt(8)).length() > 4096, "Full old eight-record tail exceeds the exact MCP error message contract in this fixture.");
        BridgeException error = flight.diagnostic(new BridgeException("unsupported", reason));
        check("unsupported".equals(error.code) && error.getMessage().startsWith(reason)
                && error.getMessage().length() <= 3500, "Complete error message preserves code/reason and obeys its conservative serialization boundary.");
        JsonObject evidence = new com.google.gson.JsonParser().parse(error.getMessage().substring(error.getMessage().indexOf("Flight diagnostic: ") + 19)).getAsJsonObject();
        check(evidence.getAsJsonArray("records").size() < 8 && evidence.getAsJsonArray("records").size() > 0
                && evidence.get("recordsDropped").getAsInt() > 0, "The oldest complete tail records are omitted rather than truncating diagnostic JSON.");
        check(evidence.get("keyReleaseConfirmed").getAsBoolean() && evidence.get("ownerClearConfirmed").getAsBoolean()
                && !evidence.get("sourceErrorMessageTruncated").getAsBoolean(), "Compact failure still proves task key release and owner clear.");
        StringBuilder largeReason = new StringBuilder(); for (int i = 0; i < 7000; i++) largeReason.append('x');
        BridgeException oversized = flight.diagnostic(new BridgeException("fixture_fault", largeReason.toString()));
        JsonObject bounded = new com.google.gson.JsonParser().parse(oversized.getMessage().substring(oversized.getMessage().indexOf("Flight diagnostic: ") + 19)).getAsJsonObject();
        check("fixture_fault".equals(oversized.code) && oversized.getMessage().length() <= 3500
                && bounded.get("sourceErrorMessageTruncated").getAsBoolean(), "An oversized original reason cannot break the complete bounded error contract.");
    }
    private static JsonObject walkingBody() {
        JsonObject body = new JsonObject(); body.addProperty("status", "observed");
        for (String key : new String[] {"loaded", "occupiable", "canStand", "fullSupportCoverage"}) body.addProperty(key, true);
        for (String key : new String[] {"entityCoveragePartial", "liquid", "hazard"}) body.addProperty(key, false);
        return body;
    }
    private static void prepareClear(PlayerNavigation.State state) {
        for (int slice = 0; slice < 100 && !state.walkingRouteReady; slice++)
            state.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.CLEAR, .6, 1.8, System.nanoTime() + WalkingPathPlanner.WORK_NANOS);
        check(state.walkingRouteReady, "The scalar walking fixture first publishes complete observed flat geometry.");
    }
    private static void walkingPlanning() {
        check(PlayerNavigation.walkingCheck(walkingBody()) == WalkingPathPlanner.Check.CLEAR, "Complete current native geometry is required for walking.");
        for (String key : new String[] {"loaded", "occupiable", "canStand", "fullSupportCoverage", "entityCoveragePartial", "liquid", "hazard"}) {
            JsonObject absent = walkingBody(); absent.remove(key);
            check(PlayerNavigation.walkingCheck(absent) == WalkingPathPlanner.Check.UNKNOWN, "Missing native " + key + " is not proof of a safe route.");
        }
        for (String key : new String[] {"occupiable", "canStand", "fullSupportCoverage"}) {
            JsonObject blocked = walkingBody(); blocked.addProperty(key, false);
            check(PlayerNavigation.walkingCheck(blocked) == WalkingPathPlanner.Check.BLOCKED, "Actual unsafe body/support is blocked.");
        }
        for (String key : new String[] {"liquid", "hazard"}) {
            JsonObject blocked = walkingBody(); blocked.addProperty(key, true);
            check(PlayerNavigation.walkingCheck(blocked) == WalkingPathPlanner.Check.BLOCKED, "Native liquid/hazard prevents walking.");
        }
        JsonObject entityPartial = walkingBody(); entityPartial.addProperty("entityCoveragePartial", true);
        check(PlayerNavigation.walkingCheck(entityPartial) == WalkingPathPlanner.Check.PARTIAL, "Unknown native collidable entity bounds remain partial.");
        JsonObject malformed = walkingBody(); malformed.addProperty("loaded", "true");
        check(PlayerNavigation.walkingCheck(malformed) == WalkingPathPlanner.Check.UNKNOWN, "Malformed native geometry cannot be truthy.");
        for (String status : new String[] {"partial", "unloaded", "outside_world", "unknown"}) {
            JsonObject body = walkingBody(); body.addProperty("status", status);
            WalkingPathPlanner.Check expected = "partial".equals(status) ? WalkingPathPlanner.Check.PARTIAL
                    : "unknown".equals(status) ? WalkingPathPlanner.Check.UNKNOWN : WalkingPathPlanner.Check.UNLOADED;
            check(PlayerNavigation.walkingCheck(body) == expected, "Native partial/unloaded status is preserved.");
        }
        PlayerNavigation.State pending = state("walking", .5);
        check(!pending.walkingRouteReady && !pending.positionReady() && !pending.acknowledged(), "Same-position target cannot bypass pending walking geometry.");
        pending.lastClientTick = 5; pending.held.add("forward"); expect("invalid_state", () -> pending.next(5)); pending.held.clear();
        check(!pending.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.PARTIAL, .6, 1.8,
                System.nanoTime() + WalkingPathPlanner.WORK_NANOS) && !pending.mutation && pending.held.isEmpty(), "Partial client planning owns no movement or input.");
        expect("invalid_state", () -> pending.next(6));
        expect("invalid_state", () -> pending.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.CLEAR, .7, 1.8,
                System.nanoTime() + WalkingPathPlanner.WORK_NANOS));
        PlayerNavigation.State changedStart = state("walking", 2.5); changedStart.requireContext(frame(.5001, 11, false, true));
        expect("invalid_state", () -> changedStart.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.CLEAR, .6, 1.8,
                System.nanoTime() + WalkingPathPlanner.WORK_NANOS));
        PlayerNavigation.State dispatched = state("walking", 2.5); dispatched.mutation = true;
        expect("invalid_state", () -> dispatched.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.CLEAR, .6, 1.8,
                System.nanoTime() + WalkingPathPlanner.WORK_NANOS));
        PlayerNavigation.State refused = state("walking", 2.5);
        expect("unsupported", () -> refused.prepareWalking((sweep, deadline) -> WalkingPathPlanner.Check.BLOCKED, .6, 1.8,
                System.nanoTime() + WalkingPathPlanner.WORK_NANOS));
        check(!refused.walkingRouteReady && !refused.mutation && refused.held.isEmpty(), "A refused path cannot authorize original straight input.");
        for (String strategy : new String[] {"ability_flight", "developer_teleport"}) {
            PlayerNavigation.State unchanged = state(strategy, 2.5);
            check(unchanged.walkingRouteReady && unchanged.prepareWalking((sweep, deadline) -> { throw new AssertionError("No walking search for other strategies."); },
                    .6, 1.8, System.nanoTime() + WalkingPathPlanner.WORK_NANOS), "Flight/explicit teleport keep their existing path and gates.");
        }
        PlayerNavigation.State detour = state("walking", 6.5);
        for (int slice = 0; slice < 100 && !detour.walkingRouteReady; slice++)
            detour.prepareWalking((sweep, deadline) -> sweep.x + sweep.width / 2 > 3 && sweep.x - sweep.width / 2 < 4
                    && sweep.z + sweep.width / 2 > 0 && sweep.z - sweep.width / 2 < 1
                    ? WalkingPathPlanner.Check.BLOCKED : WalkingPathPlanner.Check.CLEAR, .6, 1.8, System.nanoTime() + WalkingPathPlanner.WORK_NANOS);
        check(detour.walkingRouteReady && detour.route.size() > 1 && detour.walkingPlanner.routeDistance() > 6
                && !detour.mutation && !detour.acknowledged(), "Client-selected automatic detour is only geometry, not native movement or arrival.");
        detour.waypoint = detour.route.size() - 1; detour.requireContext(frame(6.5, 11, false, true));
        check(!detour.acknowledged(), "Published path still requires a first real stationary pose observation.");
        detour.requireContext(frame(6.5, 12, false, true)); check(detour.acknowledged(), "Later matching actual server/client stationary receipt remains the completion requirement.");
        JsonObject receipt = detour.completed(40), plan = receipt.getAsJsonObject("walkingPlan");
        check(plan.get("planningIsNotArrival").getAsBoolean() && plan.get("jumpSupported").getAsBoolean()
                && plan.get("routePointCount").getAsInt() == detour.route.size() && !plan.has("route") && !plan.has("trace") && receipt.get("arrivalConfirmed").getAsBoolean(), "Compact route evidence is distinct from actual arrival ACK.");
        JsonObject hard = args("walking", 6.5); JsonArray waypoints = new JsonArray(); waypoints.add(xyz(2.5, 64, .5)); waypoints.add(xyz(4.5, 64, .5)); hard.add("waypoints", waypoints);
        PlayerNavigation.State mandatory = new PlayerNavigation.State(new PlayerNavigation.Request(hard), frame(.5, 10, false, true), new Object(), new Object(), "uuid");
        prepareClear(mandatory);
        check(mandatory.route.size() == 3 && mandatory.route.get(0).x == 2.5 && mandatory.route.get(1).x == 4.5, "Declared intermediate visits survive native route publication exactly.");
    }
    private static void directHoverAck() {
        JsonObject request = args("ability_flight", 9.5); request.addProperty("finishFlying", true);
        PlayerNavigation.State direct = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
        prepareFlight(direct); direct.mutation = true;
        check(direct.route.size() == 1 && direct.waypoint == 0, "already flying same-level direct route retains its single observed leg");
        direct.requireContext(frame(.5, 11, true, false)); dispatch(direct, 1);
        check(direct.flightObserved && direct.jumpEdges == 0, "a fresh actual dual flight sample confirms transit without another acquisition edge");
        direct.held.clear(); JsonObject stale = frame(9.39, 12, true, false);
        stale.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("x", 9.38);
        direct.requireContext(stale); check(direct.positionReady() && !direct.acknowledged(), "near destination is only the first real stationary sample");
        check(!direct.acknowledged() && "new_server_sample_pending".equals(direct.arrivalAckWait), "same cached sample never confirms direct arrival");
        stale.getAsJsonObject("serverControl").addProperty("sampleId", 13); direct.requireContext(stale);
        check(!direct.acknowledged() && "actual_server_client_sync_pending".equals(direct.arrivalAckWait), "fresh but mismatched .01 server pose never becomes an ACK");
        direct.requireContext(frame(9.39, 14, true, false)); check(!direct.acknowledged(), "new real server convergence requires another stable observation");
        direct.requireContext(frame(9.39, 15, true, false)); check(direct.acknowledged() && "confirmed".equals(direct.arrivalAckWait), "single-leg waypoint zero accepts only a later actual matching stable ACK");
        JsonObject receipt = direct.completed(50), ack = receipt.getAsJsonObject("serverPoseAck");
        check(ack.get("sampleId").getAsLong() == 15 && receipt.get("flightTransitConfirmed").getAsBoolean()
                && "confirmed".equals(receipt.getAsJsonObject("flightTrace").get("arrivalAckWait").getAsString()),
                "completion returns the real current server sample and precise wait evidence, without synthesizing arrival");
    }
    private static void actualArrival() {
        PlayerNavigation.State teleport=new PlayerNavigation.State(new PlayerNavigation.Request(args("developer_teleport",9.5)),frame(.5,10,true,false),new Object(),new Object(),"uuid");
        teleport.requireContext(frame(9.5,11,true,false));
        check(teleport.positionReady() && !teleport.requiresLanding() && !teleport.acknowledged(),"Verified airborne command TP preserves flight and still requires a distinct stable arrival sample.");
        teleport.requireContext(frame(9.5,12,true,false)); check(teleport.acknowledged(),"Stable dual airborne TP arrival does not wait forever for an undispatched landing.");
        expect("flight_not_confirmed",()->teleport.requireContext(frame(9.5,13,false,true)));
        PlayerNavigation.State grounded=new PlayerNavigation.State(new PlayerNavigation.Request(args("developer_teleport",9.5)),frame(.5,10,false,true),new Object(),new Object(),"uuid");
        grounded.requireContext(frame(9.5,11,false,false)); check(!grounded.positionReady() && grounded.requiresLanding(),"Grounded command TP cannot treat unsupported falling as arrival.");
        PlayerNavigation.State walking = state("walking", .5); prepareClear(walking); walking.mutation = true;
        walking.requireContext(frame(.54, 11, false, true)); check(walking.positionReady(), "bounded actual arrival tolerates an off-center real pose");
        walking.held.add("forward"); check(!walking.acknowledged(), "owned keys must be released before arrival ACK"); walking.held.clear();
        check(!walking.acknowledged() && !walking.acknowledged(), "arrival needs stability across distinct actual server samples");
        walking.requireContext(frame(.54, 12, false, true)); check(walking.acknowledged(), "later same actual pose confirms stationary arrival");
        JsonObject receipt = walking.completed(40); check(receipt.getAsJsonObject("actualClientPose").get("x").getAsDouble() == .54
                && receipt.getAsJsonObject("requestedDestination").get("x").getAsDouble() == .5 && !receipt.get("fallbackUsed").getAsBoolean()
                && !receipt.get("replayAllowed").getAsBoolean(), "receipt distinguishes actual pose and target, with no replay/fallback");
        walking.requireContext(frame(.55, 13, false, true)); check(!walking.acknowledged(), "normal residual movement resets stability");
        walking.requireContext(frame(.55, 14, false, true)); check(walking.acknowledged(), "stationary later off-center pose becomes usable");
        JsonObject airborne = frame(.55, 15, false, false); walking.requireContext(airborne); check(!walking.positionReady(), "default completion cannot turn airborne receipt into grounded construction eligibility");
        PlayerNavigation.State flight = state("ability_flight", .5); flight.waypoint = 2; flight.requireContext(frame(.5, 11, false, true)); check(!flight.positionReady(), "ability flight completion requires actually observed transit");
        JsonObject finish = args("ability_flight", .5); finish.addProperty("finishFlying", true); finish.addProperty("includeDetails", true);
        PlayerNavigation.State airborneFinish = new PlayerNavigation.State(new PlayerNavigation.Request(finish), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
        prepareFlight(airborneFinish); airborneFinish.waypoint = airborneFinish.route.size() - 1; airborneFinish.flightObserved = true; airborneFinish.requireContext(frame(.54, 11, true, false)); check(airborneFinish.positionReady(), "explicit standalone finishFlying retains verified actual flight");
        airborneFinish.lastClientTick = 20;
        check(!airborneFinish.acknowledged(), "Flight final acceptance still waits for a distinct newer stable server sample.");
        airborneFinish.requireContext(frame(.54, 12, true, false)); check(airborneFinish.acknowledged(), "A newer stable dual actual airborne pose retains final ACK eligibility.");
        JsonArray trace = airborneFinish.completed(60).getAsJsonObject("flightTrace").getAsJsonArray("records");
        JsonObject ack = trace.get(trace.size() - 1).getAsJsonObject();
        check("arrival_ack".equals(ack.get("event").getAsString()) && ack.get("tick").getAsLong() == 60
                && ack.get("serverSampleId").getAsLong() == 12 && airborneFinish.lastClientTick == 20,
                "Arrival trace uses the current observed completion tick, independently of the old last control tick.");
        check(PlayerNavigation.angleDifference(359.99, -.01) < .000001, "target-facing yaw ACK wraps native degrees correctly");
    }
    private static void nearLevelHoverRoute() {
        verticalTakeoffHover();
        for (double difference : new double[] {-.17999, -.126946147254226, .126946147254226, .17999}) {
            JsonObject request = args("ability_flight", 3.5); request.addProperty("finishFlying", true); request.addProperty("y", 64 + difference);
            PlayerNavigation.State direct = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
            check(direct.route.size() == 1 && direct.route.get(0).y == 64 + difference && !direct.positionReady(),
                    "A near-level dual real hover retains the exact requested endpoint, with no arrival before complete geometry.");
            check(!direct.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.PARTIAL, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS)
                    && !direct.flightRouteReady && !direct.mutation && direct.held.isEmpty(), "Direct near-level geometry remains incomplete without clear loaded body observations.");
            prepareFlight(direct);
            List<FlightPathPlanner.Sweep> sweeps = PlayerNavigation.flightSweeps(direct.start, direct.route, .6, 1.8);
            check(sweeps.size() > 1 && sweeps.get(0).from.y == 64 && sweeps.get(sweeps.size() - 1).to.y == 64 + difference
                    && direct.flightPlanner.routeDistance() < 3.01, "Direct shallow three-dimensional route still publishes the complete actual continuous body sweep.");
            JsonObject arrival = frame(3.5, 12, true, false); direct.flightObserved = true; direct.requireContext(arrival);
            check(direct.positionReady() && !direct.acknowledged() && !direct.acknowledged(), "The original off-center Y tolerance never supplies a new stable ACK by itself.");
            direct.requireContext(frame(3.5, 13, true, false)); check(direct.acknowledged(), "Only a later actual stationary dual pose ACK confirms near-level hover.");
        }
        JsonObject boundary = args("ability_flight", 3.5); boundary.addProperty("finishFlying", true); boundary.addProperty("y", PlayerNavigation.TOLERANCE);
        PlayerNavigation.Request exact = new PlayerNavigation.Request(boundary);
        check(exact.route(new PlayerNavigation.Point(.5, 0, .5), true).size() == 1, "The direct-route bound is the unchanged inclusive near Y tolerance.");
        for (double difference : new double[] {-.18001, .18001}) {
            JsonObject request = args("ability_flight", 3.5); request.addProperty("finishFlying", true); request.addProperty("y", 64 + difference);
            check(new PlayerNavigation.Request(request).route(new PlayerNavigation.Point(.5, 64, .5), true).size() == 1,
                    "A verified airborne actor also starts nonlevel goals with one native three-dimensional leg, retaining obstacle planning.");
        }
        JsonObject request = args("ability_flight", 3.5); request.addProperty("finishFlying", true); request.addProperty("y", 64.12694614725423);
        JsonObject onlyClient = frame(.5, 10, true, false); onlyClient.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("flying", false);
        PlayerNavigation.State acquiring = new PlayerNavigation.State(new PlayerNavigation.Request(request), onlyClient, new Object(), new Object(), "uuid");
        check(acquiring.route.size() == 3, "Client flying alone cannot select the dual actual-hover shortcut.");
        request.addProperty("finishFlying", false);
        check(new PlayerNavigation.Request(request).route(new PlayerNavigation.Point(.5, 64, .5), true).size() == 3,
                "A requested real landing retains the original transit and landing route.");
        request.addProperty("finishFlying", true);
        PlayerNavigation.State unloaded = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
        expect("unsupported", () -> unloaded.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.UNLOADED, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS));
        check(!unloaded.flightRouteReady && !unloaded.mutation && unloaded.held.isEmpty(), "The hover shortcut never treats unloaded direct body space as movement authorization.");
    }
    private static void verticalTakeoffHover() {
        JsonObject request = args("ability_flight", .5);
        request.addProperty("y", 66); request.addProperty("finishFlying", true);
        PlayerNavigation.State takeoff = new PlayerNavigation.State(new PlayerNavigation.Request(request),
                frame(.5, 10, false, true), new Object(), new Object(), "uuid");
        check(takeoff.route.size() == 1 && takeoff.route.get(0).y == 66,
                "A vertical takeoff does not climb above the requested hover and descend again.");
        check(!takeoff.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.PARTIAL, .6, 1.8,
                System.nanoTime() + FlightPathPlanner.WORK_NANOS) && !takeoff.flightRouteReady && !takeoff.mutation,
                "The direct takeoff still requires complete native body geometry before any input.");
        prepareFlight(takeoff);
        dispatch(takeoff, 0); check(takeoff.held.contains("jump"), "Grounded vertical takeoff starts native flight acquisition.");
        dispatch(takeoff, 1); check(!takeoff.held.contains("jump"), "Native acquisition retains its release tick.");
        dispatch(takeoff, 2); check(takeoff.held.contains("jump"), "Native acquisition retains its second jump edge.");
        // Exercise the whole feedback controller, including its neutral keys and pulse
        // windows, after normal acquisition. This is not the isolated scalar predictor.
        for (double initial : new double[] {64.4, 65.25, 66.5}) {
            PlayerNavigation.State state = new PlayerNavigation.State(new PlayerNavigation.Request(request),
                    frame(.5, 10, false, true), new Object(), new Object(), "uuid");
            prepareFlight(state);
            double height = initial, velocity = .09;
            int settled = -1;
            for (int tick = 10; tick < 120; tick++) {
                state.requireContext(flightFrame(.5, height, tick + 10, velocity));
                if (state.positionReady()) { settled = tick; break; }
                dispatch(state, tick);
                int vertical = (state.held.contains("jump") ? 1 : 0) - (state.held.contains("sneak") ? 1 : 0);
                velocity += vertical * .15;
                if (Math.abs(velocity) < .005) velocity = 0;
                height += velocity; velocity *= .6;
            }
            check(settled >= 0 && settled < 100 && Math.abs(height - 66) <= PlayerNavigation.TOLERANCE,
                    "Whole native hover feedback settles takeoff and overshoot within the existing probe budget.");
        }
    }
    private static void arrivalTraceProvenance() {
        JsonObject request = args("ability_flight", 3.5); request.addProperty("finishFlying", true);
        PlayerNavigation.State direct = new PlayerNavigation.State(new PlayerNavigation.Request(request), frame(.5, 10, true, false), new Object(), new Object(), "uuid");
        prepareFlight(direct); direct.flightObserved = true;
        JsonObject arrived = frame(3.5, 12, true, false); arrived.addProperty("clientTicks", 101);
        direct.requireContext(arrived); // Actual ready snapshot is logged before GameBridge's early release/return.
        direct.trace(101, "observed", java.util.Collections.<String>emptySet());
        JsonObject first = direct.traceReceipt(96); JsonArray records = first.getAsJsonArray("records");
        check(records.size() == 1 && "observed".equals(records.get(0).getAsJsonObject().get("event").getAsString())
                && first.get("actualDistanceBlocks").getAsDouble() == 3, "A validated arrival snapshot records once per real client tick without duplicate distance.");
        JsonObject observation = records.get(0).getAsJsonObject();
        check(observation.get("snapshotClientTick").getAsLong() == 101 && observation.get("snapshotFromEventTick").getAsBoolean()
                && !observation.get("retainedSnapshot").getAsBoolean(), "Arrival trace binds the supplied actual snapshot tick and observation event.");
        direct.trace(103, "keys_released", java.util.Collections.<String>emptySet());
        JsonObject cached = direct.traceReceipt(96); JsonObject released = cached.getAsJsonArray("records").get(1).getAsJsonObject();
        check(released.get("tick").getAsLong() == 103 && released.get("snapshotClientTick").getAsLong() == 101
                && released.get("retainedSnapshot").getAsBoolean() && !released.get("snapshotFromEventTick").getAsBoolean(),
                "Later cleanup tick explicitly retains the older actual snapshot instead of appearing to sample again.");
        check(cached.get("lastObservedTick").getAsLong() == 101 && cached.get("lastObservedSnapshotTick").getAsLong() == 101
                && cached.get("lastObservedServerSampleId").getAsLong() == 12 && cached.get("latestSnapshotClientTick").getAsLong() == 101,
                "Summary separates last observation provenance from cleanup and latest retained context.");
        check(!direct.acknowledged() && !direct.acknowledged(), "Recording and cleanup provenance never turn a repeated server sample into an ACK.");
        JsonObject fresh = frame(3.5, 13, true, false); fresh.addProperty("clientTicks", 105); direct.requireContext(fresh);
        check(direct.acknowledged() && direct.traceReceipt(96).getAsJsonArray("records").size() == 3
                && direct.traceReceipt(0).get("lastObservedServerSampleId").getAsLong() == 13,
                "A later actual ready snapshot is visible and only its unchanged strict pose proof can complete.");
        for (com.google.gson.JsonElement invalid : new com.google.gson.JsonElement[] {JsonNull.INSTANCE,
                new com.google.gson.JsonPrimitive("106"), new com.google.gson.JsonPrimitive(true), new com.google.gson.JsonPrimitive(-1),
                new com.google.gson.JsonPrimitive(1.5), new com.google.gson.JsonParser().parse("9223372036854775808")}) {
            JsonObject malformed = frame(3.5, 13, true, false); malformed.add("clientTicks", invalid);
            check(PlayerNavigation.snapshotTick(malformed) == -1, "Absent or invalid optional snapshot provenance stays explicitly unknown.");
        }
        check(PlayerNavigation.snapshotTick(frame(3.5, 13, true, false)) == -1, "Missing optional diagnostic tick is never fabricated from server sample or event tick.");
        check(PlayerNavigation.snapshotTick(fresh) == 105, "A typed nonnegative actual snapshot tick is retained exactly.");
    }
    private static PlayerNavigation.ActualArrivalPose reportPose(double x, boolean ground, boolean flying, double motionY, double forward) {
        return new PlayerNavigation.ActualArrivalPose(x, 64, 65.62, .5, 37.25F, -12.5F, ground, flying, 0, motionY, 0, true, forward, 0, false, false, false);
    }
    private static JsonObject gapFrame(long sample, double gap) {
        JsonObject value = frame(.54, sample, false, true); value.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("x", .54 - gap); return value;
    }
    private static void actualPoseReport() {
        PlayerNavigation.State walking = state("walking", .5); prepareClear(walking); walking.mutation = true; walking.nativeMovementDispatched = true; walking.keyReleaseConfirmed = true;
        walking.requireContext(gapFrame(11, .01)); check(!walking.acknowledged(), "Original stable baseline is required before optional reporting.");
        walking.requireContext(gapFrame(12, .01)); check(!walking.acknowledged() && "actual_server_client_sync_pending".equals(walking.arrivalAckWait), "Only the original strict actual sync wait enables reporting.");
        PlayerNavigation.ArrivalPoseReport report = walking.arrivalPoseReport; PlayerNavigation.ActualArrivalPose actual = reportPose(.54, true, false, -.0784, 0);
        report.observe(40, actual, true); report.observe(40, actual, true);
        check(report.idleTicks == 1 && !report.eligible(walking, actual, true), "Repeated callbacks on one actual client tick do not establish two idle ticks.");
        report.observe(41, actual, true); check(report.eligible(walking, actual, true), "Two real idle ticks may qualify a bounded true grounded pose with native negative gravity.");
        check(!report.eligible(walking, actual, false), "Incomplete route proof cannot report.");
        walking.keyReleaseConfirmed = false; check(!report.eligible(walking, actual, true), "Unconfirmed logical key release cannot report."); walking.keyReleaseConfirmed = true;
        walking.held.add("forward"); check(!report.eligible(walking, actual, true), "Owned keys cannot report."); walking.held.clear();
        walking.flightKeys = java.util.Collections.singleton("jump"); check(!report.eligible(walking, actual, true), "Released-flight key proof is mandatory."); walking.flightKeys = java.util.Collections.emptySet();
        walking.nativeMovementDispatched = false; check(!report.eligible(walking, actual, true), "Never-dispatched same-position or aim-only operations cannot report."); walking.nativeMovementDispatched = true;
        for (String wait : new String[] {"post_mutation_sample_pending", "stable_pose_sample_pending", "new_server_sample_pending", "actual_aim_sync_pending", "confirmed"}) {
            walking.arrivalAckWait = wait; check(!report.eligible(walking, actual, true), "Other original ACK waits cannot report: " + wait);
        }
        walking.arrivalAckWait = "actual_server_client_sync_pending";
        for (double gap : new double[] {0, .00009, .031}) { walking.requireContext(gapFrame(12, gap)); check(!report.eligible(walking, actual, true), "Actual small-gap eligibility is conservative: " + gap); }
        walking.requireContext(gapFrame(12, .01));
        report.observe(42, actual, false); check(report.idleTicks == 0 && !report.eligible(walking, actual, true), "Restored physical native key state defeats empty ownership.");
        report.observe(43, actual, true); report.observe(44, reportPose(.54, true, false, -.0784, 1), true); check(report.idleTicks == 0, "Actual forward input resets optional idle proof.");
        report.observe(45, actual, true); report.observe(46, reportPose(.54002, true, false, -.0784, 0), true); check(report.idleTicks == 1, "Actual movement exceeding original stability scale resets optional idle proof.");
        report.observe(47, actual, true); report.observe(48, actual, true); check(report.eligible(walking, actual, true), "Stable actual pose must qualify again without changing original ACK state.");
        PlayerNavigation.Point stableClient = walking.stableClient, stableServer = walking.stableServer; long stableSample = walking.stableSample;
        check(!reportPose(.54, false, true, -.00001, 0).idle() && reportPose(.54, false, true, 0, 0).idle(), "Airborne reporting requires actual zero vertical motion.");
        for (PlayerNavigation.ActualArrivalPose busy : new PlayerNavigation.ActualArrivalPose[] {
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, .00001, -.0784, 0, true, 0, 0, false, false, false),
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, 0, -.0784, 0, false, 0, 0, false, false, false),
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, 0, -.0784, 0, true, 0, 1, false, false, false),
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, 0, -.0784, 0, true, 0, 0, true, false, false),
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, 0, -.0784, 0, true, 0, 0, false, true, false),
                new PlayerNavigation.ActualArrivalPose(.54, 64, 65.62, .5, 0, 0, true, false, 0, -.0784, 0, true, 0, 0, false, false, true)}) check(!busy.idle(), "Actual native input/sprint/horizontal motion remains ineligible.");
        PlayerNavigation.ActualArrivalPose invalid = reportPose(Double.NaN, true, false, 0, 0);
        PlayerNavigation.ArrivalPoseReport refused = new PlayerNavigation.ArrivalPoseReport(); expect("invalid_state", () -> refused.dispatch(invalid, 48, 12, 100, packet -> { throw new AssertionError("invalid packet must never reach sink"); }, () -> 100));
        check(refused.attempts == 0, "Constructor refusal before native dispatch consumes no marker.");
        final int[] sends = {0}; final long[] clock = {100};
        check(report.dispatch(actual, 48, 12, 100, packet -> {
            sends[0]++; check(report.attempts == 1 && "dispatching".equals(report.status), "One-shot is consumed before native sink entry.");
            check(packet.getPositionX() == actual.x && packet.getPositionY() == actual.feetY && packet.getStance() == actual.stance
                    && packet.getPositionZ() == actual.z && packet.getYaw() == actual.yaw && packet.getPitch() == actual.pitch
                    && packet.func_149465_i() == actual.onGround && packet.func_149466_j() && packet.getRotating(), "C06 getters retain exact actual feet/stance/rotation, distinct from requested target.");
        }, () -> clock[0]++), "One truthful native packet may be submitted.");
        check(walking.stableClient == stableClient && walking.stableServer == stableServer && walking.stableSample == stableSample && walking.mutationSample == 10, "Optional report leaves every original ACK baseline untouched.");
        for (int i = 0; i < 10; i++) { report.leaveArrival("correction_or_reentry"); report.observe(49 + i, actual, true); check(!report.dispatch(actual, 49 + i, 13, 100, packet -> sends[0]++, () -> 200), "Arrival re-entry cannot rearm invocation one-shot."); }
        check(sends[0] == 1 && !report.eligible(walking, actual, true), "Repeated final arrival callbacks submit exactly once.");
        check(!report.completion(true, 100, 13, 100) && !report.completion(true, 101, 13, 100), "N+1 captured before/during enqueue return cannot satisfy freshness.");
        check(!report.completion(false, 102, 13, 100) && !report.completion(true, 102, 12, 100), "Changed epoch or unchanged server sample cannot satisfy freshness.");
        check(report.completion(true, 102, 13, 100) && report.afterWorldTick == report.sendWorldTick, "Matching actual capture after enqueue may share native world tick; samples are not ticks.");
        check(!walking.acknowledged(), "Enqueue and even newer capture do not relax the original 0.0001 ACK threshold.");
        walking.requireContext(frame(.54, 13, false, true)); check(!walking.acknowledged(), "Real server convergence naturally rebuilds original stability.");
        walking.requireContext(frame(.54, 14, false, true)); check(walking.acknowledged() && report.completion(true, 103, 14, 101), "Only original strict ACK plus matching post-enqueue capture permits final completion.");
        JsonObject receipt = walking.completed(65).getAsJsonObject("arrivalPoseReport"); check(receipt.get("attemptCount").getAsInt() == 1 && receipt.get("afterEnqueueCaptureSeen").getAsBoolean() && !receipt.get("packetCausationProven").getAsBoolean(), "Success truthfully distinguishes submission/fresh convergence from packet causation.");
        for (boolean linkage : new boolean[] {false, true}) {
            PlayerNavigation.ArrivalPoseReport unknown = new PlayerNavigation.ArrivalPoseReport(); final int[] count = {0};
            expect("navigation_pose_report_outcome_unknown", () -> unknown.dispatch(actual, 50, 12, 100, packet -> { count[0]++; if (linkage) throw new LinkageError("injected"); throw new IllegalStateException("injected after possible native effect"); }, () -> 100));
            check(unknown.attempts == 1 && "dispatch_outcome_unknown".equals(unknown.status) && !unknown.completion(true, 102, 13, 101), "Ambiguous native dispatch consumes marker and cannot complete.");
            unknown.leaveArrival("reentry"); check(!unknown.dispatch(actual, 51, 13, 101, packet -> count[0]++, () -> 200) && count[0] == 1, "Unknown native outcome never retries or falls back.");
        }
        PlayerNavigation.State teleport = state("developer_teleport", .5); teleport.mutation = true; teleport.nativeMovementDispatched = true; teleport.keyReleaseConfirmed = true;
        teleport.arrivalPoseReport.observe(50, actual, true); teleport.arrivalPoseReport.observe(51, actual, true); teleport.arrivalAckWait = "actual_server_client_sync_pending";
        check(!teleport.arrivalPoseReport.eligible(teleport, actual, true), "Explicit developer teleport remains excluded.");
        JsonObject stepArgs = args("walking_step", 1.5); stepArgs.addProperty("y", 65);
        PlayerNavigation.State step = new PlayerNavigation.State(new PlayerNavigation.Request(stepArgs), frame(.5, 10, false, true), new Object(), new Object(), "uuid");
        step.mutation = true; step.nativeMovementDispatched = true; step.arrivalAckWait = "actual_server_client_sync_pending";
        check(!step.arrivalPoseReport.eligible(step, actual, true), "Native walking_step remains on its original path.");
        PlayerNavigation.State flight = state("ability_flight", .5); prepareFlight(flight); flight.waypoint = flight.route.size() - 1; flight.mutation = true; flight.nativeMovementDispatched = true; flight.keyReleaseConfirmed = true;
        flight.requireContext(gapFrame(12, .01)); flight.arrivalAckWait = "actual_server_client_sync_pending"; flight.arrivalPoseReport.observe(50, actual, true); flight.arrivalPoseReport.observe(51, actual, true);
        check(!flight.arrivalPoseReport.eligible(flight, actual, true), "Flight never bypasses actually observed acquired transit.");
        flight.flightObserved = true; check(flight.arrivalPoseReport.eligible(flight, actual, true), "Acquired actual grounded flight arrival may qualify.");
        String longReason = new String(new char[6000]).replace('\0', 'x');
        for (PlayerNavigation.State value : new PlayerNavigation.State[] {walking, flight}) {
            BridgeException error = value.diagnostic(new BridgeException("timeout", longReason));
            check("timeout".equals(error.code) && error.getMessage().length() <= PlayerNavigation.MAX_FLIGHT_ERROR_MESSAGE
                    && (error.getMessage().contains("arrivalPoseReport") || error.getMessage().contains("Arrival pose diagnostic")), "Walking/flight failure retains bounded one-shot evidence.");
            String diagnostic = error.getMessage(); int marker = diagnostic.indexOf("Arrival pose diagnostic: ");
            if (marker >= 0) diagnostic = diagnostic.substring(marker);
            new com.google.gson.JsonParser().parse(diagnostic.substring(diagnostic.indexOf('{'))).getAsJsonObject();
            check(true, "Bounded failure evidence retains complete valid JSON.");
        }
    }
    private static void currentOnly() {
        JsonObject args = xyz(0, 64, 0); args.addProperty("intent", "use"); args.addProperty("expectedWorld", "fixture"); args.addProperty("currentOnly", true); args.addProperty("requireFlying", false);
        ApproachPlanner.Request request = new ApproachPlanner.Request(args, 0);
        ApproachPlanner.Frame frame = new ApproachPlanner.Frame(request, new Object(), new Object(), "session", "uuid", "{}", "", "{}", "",
                .54, 64, -1.47, 1.62, .6, 1.8, 5, AxisAlignedBB.getBoundingBox(.24, 64, -1.77, .84, 65.8, -1.17), 0, 0, 40, 0, false, false, true, null, "");
        ApproachPlanner.Candidate candidate = ApproachPlanner.candidate(frame, 0);
        check(request.total() == 1 && candidate.feetX == .54 && candidate.feetZ == -1.47 && ApproachPlanner.candidate(frame, 1) == null, "current-only retains exact real feet and has no synthetic neighbor candidates");
        final boolean[] continuous = {false}; ApproachPlanner.Access access = new ApproachPlanner.Access() {
            public boolean loaded(int x, int y, int z) { return true; }
            public NativePlacementAdapter.State state(int x, int y, int z) { throw new AssertionError("unused"); }
            public MovingObjectPosition trace(Vec3 eye, Vec3 target) { throw new AssertionError("unused"); }
            public JsonObject space(int x, int y, int z, double width, double height, long deadline) { throw new AssertionError("must not center actual feet"); }
            public JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline) { continuous[0] = x == .54 && y == 64 && z == -1.47; return new JsonObject(); }
        };
        candidate.space(access, frame, Long.MAX_VALUE); check(continuous[0], "continuous native body callback receives actual off-center feet");
        args.addProperty("offset", 1); expect("invalid_args", () -> new ApproachPlanner.Request(args, 0));
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "expected " + code + ", got " + error.code); } }
}
