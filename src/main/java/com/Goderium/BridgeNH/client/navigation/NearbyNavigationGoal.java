package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** A reusable nearby endpoint with a fresh, loaded-world line of sight to a target volume. */
public final class NearbyNavigationGoal extends NavigationGoal {
    public interface Visibility { WalkingPathPlanner.Check check(PlayerNavigation.Point feet, long deadline); }
    final PlayerNavigation.Point center;
    final double radius, minDistance, targetHeight;
    public Visibility visibility;

    NearbyNavigationGoal(JsonObject args) {
        ExpandedGuiApi.keys(args,"type","center","radius","minDistance","targetHeight");
        if (!args.has("center") || !args.get("center").isJsonObject()) throw new BridgeException("invalid_args","nearby requires center XYZ.");
        center=NavigationGoal.xyz(args.getAsJsonObject("center"));
        radius=args.has("radius") ? JsonData.number(args,"radius",2,6) : 3;
        minDistance=args.has("minDistance") ? JsonData.number(args,"minDistance",0,radius-.5) : 1.25;
        targetHeight=args.has("targetHeight") ? JsonData.number(args,"targetHeight",.2,3) : 1.8;
    }
    public boolean contains(PlayerNavigation.Point p) { double d=p.distance(center); return d>=minDistance && d<=radius; }
    private boolean plannedContains(PlayerNavigation.Point p) {
        double d=p.distance(center); return d>=minDistance+.2 && d<=radius-.2;
    }
    PlayerNavigation.Point anchor() { return center; }
    double heuristic(PlayerNavigation.Point p) { return NavigationCosts.lowerBound(Math.max(0,p.distance(center)-radius)); }
    void collect(PlayerNavigation.Point start,List<PlayerNavigation.Point> result) {
        double distance=start.distance(center), scale=(radius-.25)/Math.max(distance,.001);
        PlayerNavigation.Point projected=new PlayerNavigation.Point(center.x+(start.x-center.x)*scale,start.y,center.z+(start.z-center.z)*scale);
        if(plannedContains(projected)) result.add(projected);
        // A narrow valid annulus can contain no block-centre grid point (e.g. radius=2, minDistance=1.5).
        // Continuous feet positions still provide safe candidates, including when actor and target overlap.
        for(int side=0;side<8;side++) {
            double angle=side*Math.PI/4;
            result.add(new PlayerNavigation.Point(center.x+(radius-.25)*Math.cos(angle),center.y,center.z+(radius-.25)*Math.sin(angle)));
        }
        for(double y=Math.max(0,Math.ceil(center.y-radius));y<=Math.min(253,center.y+radius);y++)
            for(double x=Math.ceil(center.x-radius-.5)+.5;x<=center.x+radius;x++)
                for(double z=Math.ceil(center.z-radius-.5)+.5;z<=center.z+radius;z++) {
                    PlayerNavigation.Point p=new PlayerNavigation.Point(x,y,z);
                    if(plannedContains(p)) result.add(p);
                }
    }
    WalkingPathPlanner.Check visible(PlayerNavigation.Point p,long deadline) {
        if(visibility==null) throw new BridgeException("invalid_state","Nearby endpoint needs an owning-client visibility capture.");
        return visibility.check(p,deadline);
    }
    public WalkingPathPlanner.Check visible(World world,EntityPlayer actor,PlayerNavigation.Point feet,long deadline) {
        return visibility(world,actor,feet,center,targetHeight,deadline);
    }
    public static WalkingPathPlanner.Check visibility(World world,EntityPlayer actor,PlayerNavigation.Point feet,
            PlayerNavigation.Point target,double height,long deadline) {
        // Reuse spatial's bounded voxel traversal, native block ray shapes and unloaded-halo checks.
        // Two of three body rays tolerate a small obstruction without accepting a wall or one narrow slit.
        int clear=0,blocked=0;
        for(double fraction:new double[]{.85,.55,.25}) {
            if(System.nanoTime()>=deadline) return WalkingPathPlanner.Check.PARTIAL;
            JsonObject args=new JsonObject(); args.addProperty("mode","raycast"); args.addProperty("includeEntities",false);
            args.add("from",xyz(feet.x,feet.y+actor.getEyeHeight(),feet.z));
            args.add("to",xyz(target.x,target.y+height*fraction,target.z));
            JsonObject ray=WorldObservationsAdapter.spatial(world,actor,args);
            String status=ray.get("status").getAsString();
            if(!"observed".equals(status)) return "unloaded".equals(status) ? WalkingPathPlanner.Check.UNKNOWN : WalkingPathPlanner.Check.PARTIAL;
            if(ray.get("hit").isJsonNull()) clear++; else blocked++;
            if(clear>=2) return WalkingPathPlanner.Check.CLEAR;
            if(blocked>=2) return WalkingPathPlanner.Check.BLOCKED;
        }
        return WalkingPathPlanner.Check.BLOCKED;
    }
    public static JsonObject xyz(double x,double y,double z) {
        JsonObject value=new JsonObject(); value.addProperty("x",x); value.addProperty("y",y); value.addProperty("z",z); return value;
    }
}
