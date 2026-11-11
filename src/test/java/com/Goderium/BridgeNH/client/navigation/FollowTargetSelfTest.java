package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Hypothetical moving-player geometry, using the production target decisions and navigation planner. */
public final class FollowTargetSelfTest {
    private static int checks;
    private static PlayerNavigation.Point p(double x,double z) { return new PlayerNavigation.Point(x,4,z); }
    private static void check(boolean value,String reason) { checks++; if(!value) throw new AssertionError(reason); }
    private static NearbyNavigationGoal goal(PlayerNavigation.Point target) {
        JsonObject args=new JsonObject(); args.addProperty("type","nearby"); args.add("center",NearbyNavigationGoal.xyz(target.x,target.y,target.z));
        NearbyNavigationGoal goal=new NearbyNavigationGoal(args);
        goal.visibility=(feet,deadline)->visible(feet,target) ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
        return goal;
    }
    // A full-height wall occupies x=4..12, z=2..3. Body clearance is checked by the existing swept planner.
    private static boolean visible(PlayerNavigation.Point from,PlayerNavigation.Point to) {
        for(int i=0;i<=200;i++) {
            double t=i/200.0,x=from.x+(to.x-from.x)*t,z=from.z+(to.z-from.z)*t;
            if(x>=4 && x<=12 && z>=2 && z<=3) return false;
        }
        return true;
    }
    private static final WalkingPathPlanner.Probe WORLD=(sweep,deadline)-> {
        if(Math.abs(sweep.from.y-4)>.00001 || Math.abs(sweep.to.y-4)>.00001) return WalkingPathPlanner.Check.BLOCKED;
        if(sweep.x+sweep.width/2>4 && sweep.x-sweep.width/2<12 && sweep.z+sweep.depth/2>2 && sweep.z-sweep.depth/2<3)
            return WalkingPathPlanner.Check.BLOCKED;
        return WalkingPathPlanner.Check.CLEAR;
    };
    private static JsonObject frame(PlayerNavigation.Point actor) {
        JsonObject frame=new JsonObject(); frame.addProperty("sessionId","session"); frame.addProperty("profile","developer");
        frame.addProperty("worldLoaded",true); frame.addProperty("paused",false); frame.addProperty("screenOpen",false); frame.addProperty("screenId","");
        JsonObject world=new JsonObject(); world.addProperty("worldName","fixture"); world.addProperty("worldFolder","fixture"); world.addProperty("dimension",0); frame.add("world",world);
        JsonObject background=new JsonObject(); background.addProperty("enabled",true); background.addProperty("helperInstalled",true); background.addProperty("pauseOnLostFocus",false); frame.add("background",background);
        JsonObject pose=NearbyNavigationGoal.xyz(actor.x,actor.y,actor.z); pose.addProperty("feetY",actor.y); pose.addProperty("eyeY",actor.y+1.62);
        pose.addProperty("yaw",0); pose.addProperty("pitch",0); pose.addProperty("motionX",0); pose.addProperty("motionY",0); pose.addProperty("motionZ",0);
        pose.addProperty("health",20); pose.addProperty("creative",false); pose.addProperty("flying",false); pose.addProperty("allowFlying",false); pose.addProperty("onGround",true);
        frame.add("player",pose);
        JsonObject server=new JsonObject(); server.addProperty("available",true); server.addProperty("source","integrated_server_tick"); server.addProperty("playerId","uuid");
        server.addProperty("sessionId","session"); server.addProperty("worldName","fixture"); server.addProperty("worldFolder","fixture"); server.addProperty("dimension",0);
        server.addProperty("ageMillis",0); server.addProperty("sampleId",10); server.addProperty("creative",false); server.add("pose",pose.deepCopy());
        JsonObject container=new JsonObject(); container.addProperty("isInventoryContainer",true); container.addProperty("windowId",0);
        server.add("openContainer",container); server.add("clientContainer",container.deepCopy()); frame.add("serverControl",server); return frame;
    }
    private static List<PlayerNavigation.Point> plan(PlayerNavigation.Point actor,PlayerNavigation.Point target) {
        JsonObject args=new JsonObject(); args.addProperty("expectedWorld","fixture"); args.addProperty("expectedSessionId","session");
        args.addProperty("dimension",0); args.addProperty("strategy","walking"); args.addProperty("segmented",true); args.addProperty("timeout_ms",15000);
        JsonObject goalArgs=new JsonObject(); goalArgs.addProperty("type","nearby"); goalArgs.add("center",NearbyNavigationGoal.xyz(target.x,target.y,target.z)); args.add("goal",goalArgs);
        PlayerNavigation.Request request=new PlayerNavigation.Request(args);
        ((NearbyNavigationGoal)request.goal).visibility=goal(target).visibility;
        PlayerNavigation.State state=new PlayerNavigation.State(request,frame(actor),new Object(),new Object(),"uuid");
        for(int i=0;i<4000 && !state.routeReady();i++) state.prepareWalking(WORLD,.6,1.8,System.nanoTime()+4000000L);
        check(state.routeReady(),"A complete nearby route must be planned.");
        PlayerNavigation.Point endpoint=state.route.get(state.route.size()-1);
        check(request.goal.contains(endpoint) && visible(endpoint,target),"Endpoint must be near and visible, never just on the opposite side of the wall.");
        return new ArrayList<PlayerNavigation.Point>(state.route);
    }
    public static int run() {
        checks=0;
        JsonObject narrowArgs=new JsonObject(); narrowArgs.addProperty("type","nearby"); narrowArgs.addProperty("radius",2); narrowArgs.addProperty("minDistance",1.5);
        narrowArgs.add("center",NearbyNavigationGoal.xyz(.5,4,.5)); NearbyNavigationGoal narrow=new NearbyNavigationGoal(narrowArgs);
        check(!narrow.candidates(p(.5,.5)).isEmpty(),"Narrow valid bands and overlapping start still have continuous endpoints.");
        for(PlayerNavigation.Point candidate:narrow.candidates(p(.5,.5))) {
            check(narrow.contains(candidate),"Continuous candidates obey the requested distance band.");
            double distance=candidate.distance(narrow.center);
            for(double drift:new double[]{-PlayerNavigation.TOLERANCE,PlayerNavigation.TOLERANCE}) {
                PlayerNavigation.Point stopped=new PlayerNavigation.Point(narrow.center.x+(candidate.x-narrow.center.x)*(distance+drift)/distance,
                        narrow.center.y+(candidate.y-narrow.center.y)*(distance+drift)/distance,
                        narrow.center.z+(candidate.z-narrow.center.z)*(distance+drift)/distance);
                check(narrow.contains(stopped),"Planned endpoints reserve native stopping tolerance at both band boundaries.");
            }
        }
        check(FollowTarget.decide(p(8,1),p(8,3.5),false,true,false,null)==FollowTarget.Decision.MOVE,"Nearby across a wall must move.");
        check(FollowTarget.decide(p(0,0),p(3.8,0),true,true,false,null)==FollowTarget.Decision.HOLD,"Holding hysteresis prevents boundary jitter.");
        check(FollowTarget.decide(p(0,0),p(3.8,0),true,false,false,null)==FollowTarget.Decision.MOVE,"Initial arrival still requires 3 blocks.");
        check(FollowTarget.decide(p(0,0),p(8,0),true,false,true,p(7,0))==FollowTarget.Decision.KEEP,"Small motion retains the running leg.");
        check(FollowTarget.decide(p(0,0),p(8,8),true,false,true,p(7,0))==FollowTarget.Decision.REPLAN,"Large turn invalidates the old leg.");
        List<PlayerNavigation.Point> wall=plan(p(8,1),p(8,3.5));
        check(wall.size()>2,"Wall route must contain real detour points.");
        check(ClientControlCommand.followArgs(new String[]{"follow","start","TestPlayer"}).get("player").getAsString().equals("TestPlayer"),"Player identity preserves case.");
        try { ClientControlCommand.followArgs(new String[]{"follow","开始","TestPlayer"}); throw new AssertionError("Localized command accepted"); }
        catch(BridgeException expected) { check("invalid_args".equals(expected.code),"Command enums are English only."); }

        double[][] trajectory={{0,2.5,.5},{2,2.5,.5},{5,8.5,.5},{8,12.5,.5},{12,14.5,5.5},{16,9.5,5.5},
                {22,9.5,5.5},{26,3,5.5},{30,2.5,.5},{45,2.5,.5}};
        PlayerNavigation.Point actor=p(.5,.5),target=null,legTarget=null;
        List<PlayerNavigation.Point> route=new ArrayList<PlayerNavigation.Point>(); boolean holding=false; int plans=0,holds=0,observations=0;
        for(int tick=0;tick<=900;tick++) {
            double time=tick*.05; int leg=0; while(leg<trajectory.length-2 && time>trajectory[leg+1][0]) leg++;
            double fraction=Math.min(1,(time-trajectory[leg][0])/(trajectory[leg+1][0]-trajectory[leg][0]));
            target=p(trajectory[leg][1]+fraction*(trajectory[leg+1][1]-trajectory[leg][1]),trajectory[leg][2]+fraction*(trajectory[leg+1][2]-trajectory[leg][2]));
            if(tick%20==0) {
                observations++;
                FollowTarget.Decision decision=FollowTarget.decide(actor,target,visible(actor,target),holding,!route.isEmpty(),legTarget);
                if(decision==FollowTarget.Decision.HOLD) { route.clear(); holding=true; holds++; check(visible(actor,target),"No hold behind wall."); }
                else if(decision==FollowTarget.Decision.REPLAN) { route.clear(); holding=false; }
                else if(decision==FollowTarget.Decision.MOVE) { route=plan(actor,target); legTarget=target; plans++; holding=false; }
                else holding=false;
            }
            if(!route.isEmpty()) {
                PlayerNavigation.Point next=route.get(0); double distance=actor.distance(next),step=Math.min(.215,distance);
                PlayerNavigation.Point moved=distance<.000001 ? next : p(actor.x+(next.x-actor.x)*step/distance,actor.z+(next.z-actor.z)*step/distance);
                WalkingPathPlanner.Sweep sweep=new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(actor.x,4,actor.z),new WalkingPathPlanner.Point(moved.x,4,moved.z),.6,1.8);
                check(WORLD.inspect(sweep,Long.MAX_VALUE)==WalkingPathPlanner.Check.CLEAR,"Every simulated follow movement avoids the wall.");
                actor=moved; if(step>=distance-.000001) route.remove(0);
            }
        }
        check(holding && visible(actor,target) && actor.distance(target)<=4,"After target stops, follower settles nearby and visible.");
        check(plans>2 && plans<25 && holds>5 && observations==46,"Bounded 1s target observations and finite replans.");
        System.out.println("Hypothetical follow: simulatedSeconds=45, observations="+observations+", plannedLegs="+plans+", holdSamples="+holds+", finalDistance="+actor.distance(target)+"; production planner, simulated motion, not live multiplayer acceptance.");
        return checks;
    }
}
