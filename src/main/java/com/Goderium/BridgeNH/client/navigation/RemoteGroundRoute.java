package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.Collections;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Side-neutral terrain and reach proofs. Only the native client applies movement input. */
public final class RemoteGroundRoute {
    public static final double ARRIVAL = .18;
    private final EntityPlayer actor;
    private final WalkingPathPlanner.Point start, target;
    private final WalkingPathPlanner search;
    private final JsonObject block;
    private final double reach;

    public RemoteGroundRoute(EntityPlayer actor, JsonObject args, double reach) {
        RemoteReadProtocol.keys(args, "destination", "block", "dimension");
        if (args.has("destination") == args.has("block")) throw new BridgeException("invalid_args", "Supply one destination or block.");
        requireGround(actor);
        this.actor = actor;
        this.reach = reach;
        start = feet(actor);
        block = args.has("block") ? args.getAsJsonObject("block") : null;
        if (block == null) {
            target = point(args.getAsJsonObject("destination"));
            if (Math.abs(target.y - start.y) > .06) throw new BridgeException("unsupported", "Remote walking currently requires a level route.");
        } else {
            target = new WalkingPathPlanner.Point(JsonData.integer(block,"x",-29999999,29999999)+.5,
                    start.y, JsonData.integer(block,"z",-29999999,29999999)+.5);
            double targetHeight = JsonData.integer(block,"y",0,255)+.5;
            if (Math.abs(targetHeight - WorldObservationsAdapter.eyePosition(actor).yCoord) > reach)
                throw new BridgeException("out_of_reach", "The target is outside reach from this ground level.");
        }
        if (start.distance(target) > 32) throw new BridgeException("out_of_range", "Remote walking is limited to 32 blocks per route.");
        if (block != null) verifyBlock(actor.worldObj, block);
        search = new WalkingPathPlanner(start, Collections.singletonList(target), actor.width+(actor.worldObj.isRemote?.01:0), actor.height,
                System.nanoTime() + 4000000000L, NavigationCosts.snapshot(), block == null ? null : this::remainingReach);
    }

    public static void requireGround(EntityPlayer actor) {
        requireActor(actor);
        if (!actor.onGround || actor.isOnLadder()) throw new BridgeException("invalid_state","Grounded walk needs stable footing.");
    }
    public static void requireActor(EntityPlayer actor) {
        if (actor.isDead || actor.capabilities.isFlying || actor.ridingEntity != null || actor.riddenByEntity != null
                || actor.isInWater() || actor.isSprinting() || actor.openContainer != actor.inventoryContainer
                || actor.inventory.getItemStack() != null)
            throw new BridgeException("invalid_state", "Remote walking requires a living, grounded, unmounted actor with the container closed and cursor empty.");
    }
    public static WalkingPathPlanner.Point feet(EntityPlayer player) {
        return new WalkingPathPlanner.Point(player.posX,player.boundingBox.minY,player.posZ);
    }
    public static WalkingPathPlanner.Point point(JsonObject o) {
        if (o == null) throw new BridgeException("invalid_args","Missing walk point.");
        RemoteReadProtocol.keys(o, "x","y","z");
        return new WalkingPathPlanner.Point(JsonData.number(o,"x",-29999999,29999999),JsonData.number(o,"y",0,253),JsonData.number(o,"z",-29999999,29999999));
    }
    public static JsonObject json(WalkingPathPlanner.Point p) {
        JsonObject o = new JsonObject(); o.addProperty("x",p.x); o.addProperty("y",p.y); o.addProperty("z",p.z); return o;
    }
    private static void verifyBlock(World world, JsonObject b) {
        RemoteReadProtocol.keys(b,"x","y","z","registryName","metadata","allowedSides");
        int x=JsonData.integer(b,"x",-29999999,29999999), y=JsonData.integer(b,"y",0,255), z=JsonData.integer(b,"z",-29999999,29999999);
        if (b.has("allowedSides")) {
            if (!b.get("allowedSides").isJsonArray()) throw new BridgeException("invalid_args","allowedSides must be an array.");
            JsonArray sides = b.getAsJsonArray("allowedSides");
            if (sides.size()<1 || sides.size()>6) throw new BridgeException("invalid_args","Supply one to six visible faces.");
            int mask=0;
            for (JsonElement value : sides) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("[0-5]"))
                    throw new BridgeException("invalid_args","Each face must be an integer from 0 to 5.");
                int bit=1<<value.getAsInt();
                if ((mask & bit)!=0) throw new BridgeException("invalid_args","Duplicate visible face.");
                mask |= bit;
            }
        }
        if (new WorldScan.MinecraftAccess(world).section(x>>4,y>>4,z>>4)==WorldScan.UNKNOWN) throw new BridgeException("unloaded","Target block is outside loaded terrain.");
        Block actual=world.getBlock(x,y,z);
        if (actual==null || actual==net.minecraft.init.Blocks.air || !JsonData.string(b,"registryName").equals(Block.blockRegistry.getNameForObject(actual))
                || JsonData.integer(b,"metadata",0,15)!=world.getBlockMetadata(x,y,z))
            throw new BridgeException("target_unverified","Target block identity changed.");
    }
    private double remainingReach(WalkingPathPlanner.Point p) {
        return Math.max(0, Math.hypot(target.x-p.x,target.z-p.z) - Math.min(reach-.5,3));
    }
    static int visibleSide(EntityPlayer player, WalkingPathPlanner.Point p, JsonObject block, double reach) {
        verifyBlock(player.worldObj,block);
        int x=block.get("x").getAsInt(), y=block.get("y").getAsInt(), z=block.get("z").getAsInt();
        double eyeHeight=WorldObservationsAdapter.eyePosition(player).yCoord-player.boundingBox.minY;
        WalkingPathPlanner.Point eye=new WalkingPathPlanner.Point(p.x,p.y+eyeHeight,p.z);
        WalkingPathPlanner.Point center=new WalkingPathPlanner.Point(x+.5,y+.5,z+.5);
        if (eye.distance(center)>reach) return -1;
        JsonObject query=new JsonObject(); query.addProperty("mode","raycast"); query.add("from",json(eye)); query.add("to",json(center));
        JsonObject ray=WorldObservationsAdapter.spatial(player.worldObj,player,query);
        if (!"observed".equals(ray.get("status").getAsString()) || ray.get("entityCoveragePartial").getAsBoolean() || !ray.get("hit").isJsonObject()) return -1;
        JsonObject hit=ray.getAsJsonObject("hit").getAsJsonObject("block");
        if (hit==null || hit.get("x").getAsInt()!=x || hit.get("y").getAsInt()!=y || hit.get("z").getAsInt()!=z) return -1;
        int side=ray.getAsJsonObject("hit").get("side").getAsInt();
        if (block.has("allowedSides")) {
            for (com.google.gson.JsonElement allowed:block.getAsJsonArray("allowedSides")) if (allowed.getAsInt()==side) return side;
            return -1;
        }
        return side;
    }
    public static WalkingPathPlanner.Check geometry(EntityPlayer player, WalkingPathPlanner.Sweep sweep, long deadline) {
        JsonObject o=WorldObservationsAdapter.walkingSpaceAt(player.worldObj,player,sweep,deadline,false);
        String status=JsonData.string(o,"status");
        if ("partial".equals(status)) return WalkingPathPlanner.Check.PARTIAL;
        if ("unloaded".equals(status)) return WalkingPathPlanner.Check.UNLOADED;
        if (!"observed".equals(status)) return WalkingPathPlanner.Check.UNKNOWN;
        return o.get("loaded").getAsBoolean() && o.get("occupiable").getAsBoolean() && !o.get("entityCoveragePartial").getAsBoolean()
                && !o.get("hazard").getAsBoolean() && !o.get("liquid").getAsBoolean()
                && o.get("continuousSupportCoverage").getAsBoolean() ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
    }
    public JsonObject advance(EntityPlayer current,long deadline) {
        requireGround(current);
        if (block!=null) verifyBlock(current.worldObj,block);
        if (actor!=current || feet(actor).distance(start)>.12) throw new BridgeException("stale_world","Actor changed or moved during route search.");
        WalkingPathPlanner.Status status=search.advance(new WalkingPathPlanner.Probe() {
            public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep,long end) { return geometry(actor,sweep,end); }
            public WalkingPathPlanner.Check endpoint(WalkingPathPlanner.Point p,double width,double height,long end) {
                WalkingPathPlanner.Check space=WalkingPathPlanner.Probe.super.endpoint(p,width,height,end);
                if (space!=WalkingPathPlanner.Check.CLEAR || block==null) return space;
                return visibleSide(actor,p,block,reach)>=0 ? space : WalkingPathPlanner.Check.BLOCKED;
            }
        },deadline,WalkingPathPlanner.SYSTEM_CLOCK);
        if (status==WalkingPathPlanner.Status.PLANNING) return null;
        if (status!=WalkingPathPlanner.Status.FOUND) throw new BridgeException("route_unavailable","Ground route search: "+search.reason());
        JsonArray route=new JsonArray();
        for (WalkingPathPlanner.Point p:search.route()) route.add(json(p));
        if (route.size()==0) route.add(json(start));
        JsonObject out=new JsonObject(); out.add("waypoints",route); out.add("start",json(start)); return out;
    }
    /** Final position accepted by vanilla; corridor search stays on the controlling client. */
    public static JsonObject arrival(EntityPlayer actor,JsonObject args,double reach) {
        RemoteReadProtocol.keys(args,"dimension","waypoint","block");
        WalkingPathPlanner.Point p=feet(actor), target=point(args.getAsJsonObject("waypoint"));
        boolean reached=actor.onGround && !actor.isDead && !actor.capabilities.isFlying && actor.ridingEntity==null
                && Math.abs(p.y-target.y)<=.12 && Math.hypot(p.x-target.x,p.z-target.z)<=ARRIVAL;
        JsonObject out=new JsonObject(); out.add("feet",json(p)); out.addProperty("reached",reached);
        if (reached && args.has("block")) {
            int side=visibleSide(actor,p,args.getAsJsonObject("block"),reach);
            out.addProperty("blockVisible",side>=0); out.addProperty("side",side);
        }
        return out;
    }
    static JsonObject localArrival(EntityPlayer actor,JsonObject check,double reach,double tolerance,long end) {
        WalkingPathPlanner.Point p=feet(actor), to=point(check.getAsJsonObject("waypoint"));
        WalkingPathPlanner.Check space=geometry(actor,new WalkingPathPlanner.Sweep(p,p,actor.width+.01,actor.height),end);
        if (space!=WalkingPathPlanner.Check.CLEAR) throw new BridgeException(space==WalkingPathPlanner.Check.PARTIAL?"walk_slice":"route_unverified",
                "Current client footing: "+space);
        JsonObject result=arrival(actor,check,reach);
        result.addProperty("reached",result.get("reached").getAsBoolean() && Math.hypot(p.x-to.x,p.z-to.z)<=tolerance);
        return result;
    }
    public static JsonObject probe(EntityPlayer actor,JsonObject args,double reach,long deadline) {
        requireGround(actor);
        RemoteReadProtocol.keys(args,"dimension","waypoint","block");
        WalkingPathPlanner.Point start=feet(actor), to=point(args.getAsJsonObject("waypoint"));
        if (Math.abs(start.y-to.y)>.12 || start.distance(to)>33) throw new BridgeException("route_changed","Waypoint is outside the active level walk.");
        if (args.has("block")) verifyBlock(actor.worldObj,args.getAsJsonObject("block"));
        double dx=to.x-start.x,dz=to.z-start.z,dist=Math.hypot(dx,dz);
        // Include the braking distance of a short grounded sneak pulse and allow for minor pose disagreement.
        double length=dist<=ARRIVAL ? 0 : Math.min(dist,.6)+.24;
        WalkingPathPlanner.Point end=dist==0 ? start : new WalkingPathPlanner.Point(start.x+dx/dist*length,start.y,start.z+dz/dist*length);
        WalkingPathPlanner.Sweep sweep=new WalkingPathPlanner.Sweep(start,end,actor.width+.12,actor.height);
        if (geometry(actor,sweep,deadline)!=WalkingPathPlanner.Check.CLEAR)
            throw new BridgeException("route_unverified","Current walking corridor lacks a complete collision, hazard and support proof.");
        JsonObject out=new JsonObject(); out.add("feet",json(start)); out.addProperty("reached",dist<=ARRIVAL);
        if (dist<=ARRIVAL && args.has("block")) {
            int side=visibleSide(actor,start,args.getAsJsonObject("block"),reach);
            out.addProperty("blockVisible",side>=0); out.addProperty("side",side);
        }
        return out;
    }
}
