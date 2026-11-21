package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Executes a single planned transit at a time, using ordinary player input and client-world readback. */
final class ClientTransitMotion {
    private final RemoteWalkInput input;
    private final Consumer<JsonObject> useDoor;
    private final BooleanSupplier authorized;
    private final Set<String> attemptedDoors=new HashSet<>();
    private final Set<String> attemptedRides=new HashSet<>();
    private JsonObject current;
    private int observations, stableTicks;
    private boolean dispatched;
    int doors, ladderSteps, elevatorRides;

    ClientTransitMotion(RemoteWalkInput input,Consumer<JsonObject> useDoor,BooleanSupplier authorized) {
        this.input=input; this.useDoor=useDoor; this.authorized=authorized;
    }
    void openAhead(Minecraft client,JsonObject action) {
        int x=action.get("x").getAsInt(),y=action.get("y").getAsInt(),z=action.get("z").getAsInt();
        if (!ClientTransitRoute.closed(input.world,x,y,z)) return;
        if (current!=action) { current=action; observations=stableTicks=0; dispatched=false; }
        if (dispatched) {
            if (++observations>15) throw new BridgeException("transit_unconfirmed","The door did not open.");
            return;
        }
        open(client,x,y,z);
    }

    boolean advance(Minecraft client,JsonObject waypoint,WalkingPathPlanner.Point target,long end) {
        JsonObject action=waypoint.getAsJsonObject("transit");
        if (current!=action) { current=action; observations=stableTicks=0; dispatched=false; }
        if (++observations>100) throw new BridgeException("transit_unconfirmed","Transit did not reach its expected landing.");
        int x=action.get("x").getAsInt(), y=action.get("y").getAsInt(), z=action.get("z").getAsInt();
        String kind=action.get("kind").getAsString();
        WalkingPathPlanner.Point p=RemoteGroundRoute.feet(input.actor);
        if (!ClientTransitRoute.loaded(input.world,x,y,z) || !ClientTransitRoute.loaded(input.world,
                ClientTransitRoute.floor(target.x),ClientTransitRoute.floor(target.y),ClientTransitRoute.floor(target.z)))
            throw new BridgeException("unloaded","Transit endpoint unloaded before execution.");
        if ("door".equals(kind)) return door(client,target,p,x,y,z,end);
        input.transitMotion=true;
        if (kind.startsWith("elevator_")) {
            int color=action.get("color").getAsInt();
            if (ClientTransitRoute.color(input.world,x,y,z)!=color || ClientTransitRoute.color(input.world,x,(int)target.y-1,z)!=color)
                throw new BridgeException("target_unverified","The elevator pair changed.");
            if (dispatched) {
                if (settled(p,target) && input.actor.onGround && input.actor.motionY<=.001) {
                    if (++stableTicks>=4) { elevatorRides++; return true; }
                } else stableTicks=0;
                return false;
            }
            if (!input.actor.onGround || Math.abs(p.y-y-1)>.12 || Math.hypot(x+.5-p.x,z+.5-p.z)>.3)
                throw new BridgeException("transit_unconfirmed","The player is not on the expected elevator.");
            requireLanding(target,end);
            if (!attemptedRides.add(kind+"/"+x+"/"+y+"/"+z))
                throw new BridgeException("transit_unconfirmed","An elevator input was already issued for this departure.");
            boolean up=kind.equals("elevator_up");
            input.press(client,0,0,false,!up,up,1,authorized);
            dispatched=true;
            return false;
        }
        if (kind.equals("ladder_up") || kind.equals("ladder_down")) {
            if (!ClientTransitRoute.ladder(input.world,x,y,z) || !ClientTransitRoute.ladder(input.world,x,(int)target.y,z)
                    || !input.actor.isOnLadder() || Math.abs(p.x-target.x)>.52 || Math.abs(p.z-target.z)>.52)
                throw new BridgeException("transit_unconfirmed","Lost the planned ladder column.");
            boolean up=kind.equals("ladder_up");
            if (up ? p.y>=target.y-.015 : p.y<=target.y+.08) { ladderSteps++; return true; }
            double nextY=up ? Math.min(target.y,p.y+.21) : Math.max(target.y,p.y-.16);
            if (!clear(new WalkingPathPlanner.Point(p.x,nextY,p.z),end)) return false;
            int side=input.world.getBlockMetadata(x,y,z);
            if (side<2 || side>5) throw new BridgeException("transit_unconfirmed","Unsupported ladder attachment.");
            if (up) input.press(client,x+.5+(side==4?.9:side==5?-.9:0)-p.x,
                    z+.5+(side==2?.9:side==3?-.9:0)-p.z,true,false,input.actor.onGround,2,authorized);
            else centerOnLadder(client,p,target,false,end);
            return false;
        }
        boolean entering=kind.equals("ladder_enter");
        if (entering && !ClientTransitRoute.ladder(input.world,x,y,z)) throw new BridgeException("target_unverified","The entry ladder changed.");
        if (entering && input.actor.isOnLadder() && Math.abs(p.y-target.y)<.45) {
            if (Math.hypot(p.x-target.x,p.z-target.z)<=.14 && Math.hypot(input.actor.motionX,input.actor.motionZ)<=.018)
                return true;
            centerOnLadder(client,p,target,true,end);
            return false;
        }
        if (!entering && input.actor.onGround && !input.actor.isOnLadder() && ClientTransitRoute.floor(p.x)==x && ClientTransitRoute.floor(p.z)==z
                && Math.abs(p.y-target.y)<=.12) {
            if (Math.hypot(input.actor.motionX,input.actor.motionZ)>.035) return false;
            return true;
        }
        if (Math.abs(p.y-target.y)>.7 || Math.hypot(target.x-p.x,target.z-p.z)>1.9)
            throw new BridgeException("transit_unconfirmed","Lost the ladder landing.");
        if (!entering) requireLanding(target,end);
        if (!clear(target,end)) return false;
        input.press(client,target.x-p.x,target.z-p.z,true,input.actor.isOnLadder(),false,2,authorized);
        return false;
    }

    private void centerOnLadder(Minecraft client,WalkingPathPlanner.Point feet,WalkingPathPlanner.Point target,boolean brake,long end) {
        // A player can carry enough lateral momentum off the top ledge to leave the ladder several rungs later.
        double dx=target.x-feet.x-5*input.actor.motionX;
        double dz=target.z-feet.z-5*input.actor.motionZ;
        boolean steer=Math.hypot(dx,dz)>.09;
        if (steer && !clear(new WalkingPathPlanner.Point(target.x,feet.y,target.z),end)) return;
        if (steer || brake) input.press(client,steer?dx:0,steer?dz:0,steer,brake,false,1,authorized);
    }

    private boolean door(Minecraft client,WalkingPathPlanner.Point target,WalkingPathPlanner.Point p,int x,int y,int z,long end) {
        input.transitMotion=false;
        World world=input.world;
        if (!ClientTransitRoute.door(world,x,y,z)) throw new BridgeException("target_unverified","The wooden door changed.");
        if (ClientTransitRoute.closed(world,x,y,z)) {
            if (dispatched) {
                if (observations>15) throw new BridgeException("transit_unconfirmed","The door remained closed after normal use.");
                return false;
            }
            open(client,x,y,z);
            return false;
        }
        JsonObject check=new JsonObject(); check.add("waypoint",RemoteGroundRoute.json(target));
        JsonObject proof=RemoteGroundRoute.localArrival(input.actor,check,client.playerController.getBlockReachDistance(),RemoteGroundRoute.ARRIVAL,end);
        if (proof.get("reached").getAsBoolean()) return true;
        if (Math.hypot(input.actor.motionX,input.actor.motionZ)<=.035) input.pulse(client,target,authorized);
        return false;
    }

    private void open(Minecraft client,int x,int y,int z) {
        World world=input.world;
        String key=x+"/"+y+"/"+z;
        if (attemptedDoors.contains(key)) throw new BridgeException("transit_unconfirmed","A previously used door is closed again.");
        net.minecraft.block.Block b=world.getBlock(x,y,z);
        b.setBlockBoundsBasedOnState(world,x,y,z);
        AxisAlignedBB bounds=b.getSelectedBoundingBoxFromPool(world,x,y,z);
        Vec3 eye=WorldObservationsAdapter.eyePosition(input.actor);
        Vec3 center=Vec3.createVectorHelper((bounds.minX+bounds.maxX)/2,(bounds.minY+bounds.maxY)/2,(bounds.minZ+bounds.maxZ)/2);
        double reach=client.playerController.getBlockReachDistance();
        MovingObjectPosition hit=null;
        for (int half=0;half<=1;half++) {
            Vec3 aim=Vec3.createVectorHelper(center.xCoord-eye.xCoord,center.yCoord+half-eye.yCoord,center.zCoord-eye.zCoord).normalize();
            Vec3 end=eye.addVector(aim.xCoord*reach,aim.yCoord*reach,aim.zCoord*reach);
            MovingObjectPosition ray=world.rayTraceBlocks(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),end);
            if (ray!=null && ray.blockX==x && ray.blockZ==z && (ray.blockY==y || ray.blockY==y+1)
                    && world.getBlock(x,ray.blockY,z)==net.minecraft.init.Blocks.wooden_door && eye.distanceTo(ray.hitVec)<=reach) {
                hit=ray; break;
            }
        }
        if (hit==null) throw new BridgeException("face_not_visible","Neither half of the wooden door has a reachable native hit.");
        y=hit.blockY;
        JsonObject click=new JsonObject(); click.addProperty("action","use");
        click.addProperty("x",x); click.addProperty("y",y); click.addProperty("z",z); click.addProperty("side",hit.sideHit);
        JsonObject aim=new JsonObject(); aim.addProperty("x",hit.hitVec.xCoord-x);
        aim.addProperty("y",hit.hitVec.yCoord-y); aim.addProperty("z",hit.hitVec.zCoord-z); click.add("hitPoint",aim);
        attemptedDoors.add(key); dispatched=true; useDoor.accept(click); doors++;
    }

    private static boolean settled(WalkingPathPlanner.Point a,WalkingPathPlanner.Point b) {
        return Math.abs(a.y-b.y)<=.12 && Math.hypot(a.x-b.x,a.z-b.z)<=RemoteGroundRoute.ARRIVAL;
    }
    private void requireLanding(WalkingPathPlanner.Point p,long end) {
        WalkingPathPlanner.Check c=RemoteGroundRoute.geometry(input.actor,new WalkingPathPlanner.Sweep(p,p,input.actor.width,input.actor.height),end);
        if (c==WalkingPathPlanner.Check.PARTIAL) throw new BridgeException("walk_slice","Client landing check yielded.");
        if (c!=WalkingPathPlanner.Check.CLEAR) throw new BridgeException("route_unverified","Transit landing is no longer verified.");
    }
    private boolean clear(WalkingPathPlanner.Point to,long end) {
        WalkingPathPlanner.Point p=RemoteGroundRoute.feet(input.actor);
        double distance=p.distance(to), scale=distance==0?0:Math.min(.24,distance)/distance;
        AxisAlignedBB body=input.actor.boundingBox.addCoord((to.x-p.x)*scale,(to.y-p.y)*scale,(to.z-p.z)*scale);
        JsonObject s=WorldObservationsAdapter.spaceAt(input.world,input.actor,body,end,false);
        String status=s.get("status").getAsString();
        if (status.equals("partial")) return false;
        if (!status.equals("observed") || !s.get("occupiable").getAsBoolean() || s.get("hazard").getAsBoolean()
                || s.get("liquid").getAsBoolean() || s.get("entityCoveragePartial").getAsBoolean())
            throw new BridgeException("route_unverified","Transit corridor changed.");
        return true;
    }
}
