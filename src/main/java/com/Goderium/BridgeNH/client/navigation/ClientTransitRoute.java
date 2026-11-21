package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/** Bounded client-world search for transitions that cannot be represented by a horizontal sweep. */
final class ClientTransitRoute {
    private static final int[][] SIDES={{1,0},{-1,0},{0,1},{0,-1}};
    private final EntityPlayer actor;
    private final WorldScan.MinecraftAccess terrain;
    private final JsonObject block;
    private final WalkingPathPlanner.Point start, target;
    private final double reach;
    private final PriorityQueue<Node> open=new PriorityQueue<>();
    private final Map<String,Double> costs=new HashMap<>();
    private Node current;
    private int edge;
    private boolean started;
    private static final class Node implements Comparable<Node> {
        final int x,y,z;
        final double cost,score;
        final Node parent;
        final JsonObject action;
        Node(int x,int y,int z,double cost,double estimate,Node parent,JsonObject action) {
            this.x=x; this.y=y; this.z=z; this.cost=cost; this.score=cost+estimate; this.parent=parent; this.action=action;
        }
        public int compareTo(Node n) { return Double.compare(score,n.score); }
        WalkingPathPlanner.Point point() { return center(x,y,z); }
    }

    ClientTransitRoute(EntityPlayer actor,JsonObject goal,double reach) {
        this.actor=actor; this.reach=reach; terrain=new WorldScan.MinecraftAccess(actor.worldObj);
        start=RemoteGroundRoute.feet(actor);
        block=goal.getAsJsonObject("block");
        target=block==null ? RemoteGroundRoute.point(goal.getAsJsonObject("destination"))
                : center(block.get("x").getAsInt(),block.get("y").getAsInt(),block.get("z").getAsInt());
        if (start.distance(target)>40) throw new BridgeException("out_of_range","Transit search covers a local 40-block region.");
        offer(floor(start.x),floor(start.y+.06),floor(start.z),0,null,null);
    }

    static int floor(double value) { return (int)Math.floor(value); }
    static WalkingPathPlanner.Point center(int x,int y,int z) { return new WalkingPathPlanner.Point(x+.5,y,z+.5); }
    private static String key(int x,int y,int z) { return x+"/"+y+"/"+z; }
    private boolean loaded(int x,int y,int z) {
        return y>=1 && y<253 && terrain.section(x>>4,y>>4,z>>4)!=WorldScan.UNKNOWN;
    }
    private void offer(int x,int y,int z,double step,Node parent,JsonObject action) {
        double cost=(parent==null ? 0 : parent.cost)+step;
        if (cost>80 || center(x,y,z).distance(start)>40 || !loaded(x,y,z)) return;
        String key=key(x,y,z);
        if (cost>=costs.getOrDefault(key,Double.POSITIVE_INFINITY)) return;
        if (costs.size()>=2048) throw new BridgeException("route_unavailable","Client transit search reached its node budget.");
        costs.put(key,cost);
        open.add(new Node(x,y,z,cost,center(x,y,z).distance(target),parent,action));
    }

    private boolean sweep(WalkingPathPlanner.Point a,WalkingPathPlanner.Point b,long end) {
        WalkingPathPlanner.Check check=RemoteGroundRoute.geometry(actor,new WalkingPathPlanner.Sweep(a,b,actor.width,actor.height),end);
        if (check==WalkingPathPlanner.Check.PARTIAL) throw YIELD;
        return check==WalkingPathPlanner.Check.CLEAR;
    }
    private static final Yield YIELD=new Yield();
    private static final class Yield extends RuntimeException {
        private static final long serialVersionUID=1L;
        Yield() { super(null,null,false,false); }
    }
    private boolean clearance(WalkingPathPlanner.Point p,long end) {
        JsonObject space=WorldObservationsAdapter.spaceAt(actor.worldObj,actor,p.x,p.y,p.z,actor.width,actor.height,end,false);
        if ("partial".equals(space.get("status").getAsString()) || System.nanoTime()>=end) throw YIELD;
        return "observed".equals(space.get("status").getAsString()) && space.get("occupiable").getAsBoolean()
                && !space.get("hazard").getAsBoolean() && !space.get("liquid").getAsBoolean()
                && !space.get("entityCoveragePartial").getAsBoolean();
    }
    static boolean loaded(World world,int x,int y,int z) {
        return y>=0 && y<255 && new WorldScan.MinecraftAccess(world).section(x>>4,y>>4,z>>4)!=WorldScan.UNKNOWN;
    }
    static JsonObject action(String kind,int x,int y,int z) {
        JsonObject a=new JsonObject(); a.addProperty("kind",kind); a.addProperty("x",x); a.addProperty("y",y); a.addProperty("z",z); return a;
    }
    static boolean door(World w,int x,int y,int z) {
        return w.getBlock(x,y,z)==Blocks.wooden_door && (w.getBlockMetadata(x,y,z)&8)==0;
    }
    static boolean closed(World w,int x,int y,int z) { return door(w,x,y,z) && (w.getBlockMetadata(x,y,z)&4)==0; }
    static boolean ladder(World w,int x,int y,int z) { return w.getBlock(x,y,z)==Blocks.ladder; }
    private static final Method COLOR=elevatorColor();
    private static Method elevatorColor() {
        try { return Class.forName("openblocks.api.IElevatorBlock").getMethod("getColor",World.class,int.class,int.class,int.class); }
        catch (ReflectiveOperationException | LinkageError absent) { return null; }
    }
    static int color(World w,int x,int y,int z) {
        Block b=w.getBlock(x,y,z);
        if (COLOR==null || !COLOR.getDeclaringClass().isInstance(b)) return -1;
        try { return (Integer)COLOR.invoke(b,w,x,y,z); }
        catch (ReflectiveOperationException | RuntimeException failure) { return -1; }
    }

    private void horizontal(int dx,int dz,long end) {
        int x=current.x+dx,y=current.y,z=current.z+dz;
        if (!loaded(x,y,z)) return;
        World w=actor.worldObj;
        WalkingPathPlanner.Point from=current.point(), to=center(x,y,z);
        if (closed(w,x,y,z)) {
            int tx=x+dx,tz=z+dz;
            if (loaded(tx,y,tz) && sweep(center(tx,y,tz),center(tx,y,tz),end)
                    && w.getBlock(x,y-1,z).isSideSolid(w,x,y-1,z,net.minecraftforge.common.util.ForgeDirection.UP))
                offer(tx,y,tz,3,current,action("door",x,y,z));
            return;
        }
        boolean fromLadder=ladder(w,current.x,y,current.z), toLadder=ladder(w,x,y,z);
        if (fromLadder || toLadder) {
            if (clearance(from,end) && clearance(to,end) && (toLadder || sweep(to,to,end)))
                offer(x,y,z,1.5,current,action(toLadder?"ladder_enter":"ladder_exit",x,y,z));
        } else if (sweep(from,to,end)) offer(x,y,z,1,current,null);
    }
    private void vertical(int sign,long end) {
        World w=actor.worldObj; int x=current.x,y=current.y,z=current.z;
        if (loaded(x,y+sign,z) && ladder(w,x,y,z) && ladder(w,x,y+sign,z) && clearance(center(x,y+sign,z),end))
            offer(x,y+sign,z,2,current,action(sign>0?"ladder_up":"ladder_down",x,y,z));
        int color=color(w,x,y-1,z);
        if (color<0) return;
        for (int height=1;height<=32;height++) {
            int dest=y+height*sign;
            if (!loaded(x,dest-1,z) || !loaded(x,dest+1,z)) return;
            if (color(w,x,dest-1,z)!=color) continue;
            WalkingPathPlanner.Point landing=center(x,dest,z);
            if (sweep(landing,landing,end)) {
                JsonObject a=action(sign>0?"elevator_up":"elevator_down",x,y-1,z);
                a.addProperty("color",color);
                offer(x,dest,z,2+height*.1,current,a);
            }
            return;
        }
    }

    JsonArray advance(long end) {
        if (RemoteGroundRoute.feet(actor).distance(start)>.12) throw new BridgeException("route_changed","Actor moved during transit planning.");
        try {
            if (!started) {
                if (open.isEmpty()) throw new BridgeException("unloaded","The client has no loaded starting cell.");
                if (!sweep(start,open.element().point(),end)) throw new BridgeException("route_unavailable","No safe approach to the transit grid.");
                started=true;
            }
            while (System.nanoTime()<end) {
                if (current==null) {
                    current=open.poll(); edge=-1;
                    if (current==null) throw new BridgeException("route_unavailable","No supported route through the loaded ground, doors, ladders or elevators.");
                    if (current.cost>costs.get(key(current.x,current.y,current.z))) { current=null; continue; }
                }
                if (edge==-1) {
                    boolean reached=block==null ? Math.abs(target.y-current.y)<.06 && current.point().distance(target)<=1
                            && sweep(current.point(),target,end)
                            : current.point().distance(target)<=reach && sweep(current.point(),current.point(),end)
                            && RemoteGroundRoute.visibleSide(actor,current.point(),block,reach)>=0;
                    if (reached && !ladder(actor.worldObj,current.x,current.y,current.z)) return route();
                } else if (edge<4) horizontal(SIDES[edge][0],SIDES[edge][1],end);
                else vertical(edge==4 ? 1 : -1,end);
                if (++edge==6) current=null;
            }
        } catch (Yield waitForNextTick) { /* Resume the same edge on the next client tick. */ }
        return null;
    }

    private JsonArray route() {
        List<Node> nodes=new ArrayList<>();
        for (Node n=current;n!=null;n=n.parent) nodes.add(n);
        Collections.reverse(nodes);
        JsonArray out=new JsonArray();
        for (Node n:nodes) {
            JsonObject point=RemoteGroundRoute.json(n.point());
            if (n.action!=null) point.add("transit",n.action);
            out.add(point);
        }
        if (block==null && (current.action!=null || current.point().distance(target)>.001)) out.add(RemoteGroundRoute.json(target));
        return out;
    }
}
