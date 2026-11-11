package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Native geometry fixtures; live renderer, keys, motion and packet acknowledgment need client tests. */
public final class CrosshairArrivalSelfTest {
    private static int checks;
    static JsonArray targets() {
        JsonArray targets = new JsonArray(); JsonObject target = new JsonObject();
        target.addProperty("x",3); target.addProperty("y",65); target.addProperty("z",0);
        JsonArray sides = new JsonArray(); sides.add(new com.google.gson.JsonPrimitive(4)); target.add("allowedSides",sides);
        JsonObject identity = new JsonObject(); identity.addProperty("registryName","fixture:target"); identity.addProperty("metadata",0);
        target.add("targetIdentity",identity); targets.add(target); return targets;
    }
    static final class Fixture implements CrosshairArrival.Access {
        boolean entity, unloaded, changed, miss, mutateOrigin;
        public boolean loaded(int x,int y,int z) { return !unloaded; }
        public NativePlacementAdapter.State state(int x,int y,int z) {
            JsonObject identity = new JsonObject(); identity.addProperty("registryName",changed ? "fixture:other" : "fixture:target");
            identity.addProperty("metadata",0); return new NativePlacementAdapter.State(false,false,identity);
        }
        public MovingObjectPosition trace(Vec3 eye,Vec3 end) {
            if (miss) return null;
            MovingObjectPosition hit = AxisAlignedBB.getBoundingBox(3,65,0,4,66,1).calculateIntercept(eye,end);
            if (mutateOrigin) { eye.xCoord=3; eye.yCoord=65; eye.zCoord=0; }
            return hit == null ? null : new MovingObjectPosition(3,65,0,hit.sideHit,hit.hitVec);
        }
        public JsonObject space(int x,int y,int z,double width,double height,long deadline) { throw new AssertionError("Hit does not prove body safety."); }
        public boolean entityOccluded(Vec3 eye,Vec3 end,double distance) { return entity; }
    }
    static boolean observe(CrosshairArrival arrival, Fixture access, double x, boolean client) {
        Vec3 eye = Vec3.createVectorHelper(x,65.62,.5), look = Vec3.createVectorHelper(1,0,0);
        return arrival.observe(access,eye,look,5,new PlayerNavigation.Point(x,64,.5),-90,0,1,Long.MAX_VALUE,client,
                access.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),eye.addVector(5,0,0)));
    }
    public static int run() {
        checks = 0; Fixture access = new Fixture(); CrosshairArrival arrival = new CrosshairArrival(targets(),"fixture",0,"walking"); arrival.capture(access);
        check(observe(arrival,access,.23,true) && observe(arrival,access,.23,false),"Off-centre real feet and any allowed-face hit are supported.");
        JsonObject pose = new JsonObject(); pose.addProperty("x",.23); pose.addProperty("feetY",64); pose.addProperty("z",.5); pose.addProperty("yaw",-90); pose.addProperty("pitch",0);
        check(arrival.contains(pose,true) && arrival.contains(pose,false),"Fresh evidence binds both owners' actual position and view.");
        pose.addProperty("x",.24); check(!arrival.contains(pose,true),"Movement invalidates a previous hit.");
        pose.addProperty("x",.23); pose.addProperty("yaw",-89); check(!arrival.contains(pose,true),"Rotation invalidates a previous hit.");
        access.entity=true; check(!observe(arrival,access,.23,true),"A nearer collidable entity prevents a block hit.");
        check(!arrival.contains(pose,true),"A failed observation clears previous proof."); access.entity=false;
        access.unloaded=true; expect("chunk_unloaded",()->observe(arrival,access,.23,true)); access.unloaded=false;
        access.changed=true; expect("target_unverified",()->observe(arrival,access,.23,true)); access.changed=false;
        access.miss=true; check(!observe(arrival,access,.23,true),"A current obstruction/miss cannot reuse the old frame."); access.miss=false;
        Vec3 eye=Vec3.createVectorHelper(.23,65.62,.5),look=Vec3.createVectorHelper(1,0,0);
        MovingObjectPosition stale=new MovingObjectPosition(3,65,0,4,Vec3.createVectorHelper(3,65.1,.5));
        check(!arrival.observe(access,eye,look,5,new PlayerNavigation.Point(.23,64,.5),-90,0,2,Long.MAX_VALUE,true,stale),"Cached rendered point must match the freshly recomputed actual ray.");
        check(!observe(arrival,access,-3,true),"Normal reach is enforced even for a visible distant block.");
        Vec3 serverEye=SurvivalConstructionAdapter.serverEye(.23,64,.5,1.62F);
        check(arrival.observe(access,serverEye,look,5,new PlayerNavigation.Point(.23,64,.5),-90,0,3,Long.MAX_VALUE,false,null),
                "Authoritative rays use server posY plus public eye height; client getPosition is not a server eye API.");
        check(!arrival.observe(access,Vec3.createVectorHelper(.23,64,.5),look,5,new PlayerNavigation.Point(.23,64,.5),-90,0,3,Long.MAX_VALUE,false,null),
                "Accidental authoritative feet-level ray cannot certify the client's higher pointed block.");
        access.mutateOrigin=true;
        MovingObjectPosition rendered=access.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),eye.addVector(5,0,0));
        check(arrival.observe(access,eye,look,5,new PlayerNavigation.Point(.23,64,.5),-90,0,4,Long.MAX_VALUE,true,rendered)
                && eye.xCoord==.23 && eye.yCoord==65.62 && eye.zCoord==.5,"A native ray's in-place origin mutation cannot corrupt actual eye, reach or the next aim.");
        access.mutateOrigin=false;
        JsonArray wrong=targets(); JsonArray otherSide=new JsonArray(); otherSide.add(new com.google.gson.JsonPrimitive(5)); wrong.get(0).getAsJsonObject().add("allowedSides",otherSide);
        CrosshairArrival restricted=new CrosshairArrival(wrong,"fixture",0,"walking"); restricted.capture(access);
        check(!observe(restricted,access,.23,true),"A disallowed face does not stop the task.");
        check(!arrival.receipt(true).get("hitIsSafeArrival").getAsBoolean(),"Ray proof never certifies body, support or settled motion.");
        nativeViewSamples();
        return checks;
    }
    private static void nativeViewSamples() {
        Fixture fixture = new Fixture();
        Vec3 eye=Vec3.createVectorHelper(.23,65.62,.5), point=Vec3.createVectorHelper(3,65.13,.523);
        Vec3 end=NativePlacementAdapter.nativeViewEnd(eye,point,5);
        check(Math.abs(end.zCoord-(eye.zCoord+(point.zCoord-eye.zCoord)*5/eye.distanceTo(point)))>.00001,
                "Vanilla float lookup can change a far thin-face edge ray despite a valid ideal double sample");
        fixture.mutateOrigin=true;
        MovingObjectPosition hit=NativePlacementAdapter.nativeView(fixture,5).trace(eye,point);
        check(hit!=null && eye.xCoord==.23 && eye.yCoord==65.62 && eye.zCoord==.5,
                "Native view sampling traces the achievable look while retaining the immutable real eye under vanilla Vec3 mutation");
        fixture.mutateOrigin=false;fixture.unloaded=true;
        check(NativePlacementAdapter.nativeView(fixture,5).trace(eye,point)==null,
                "A quantized full-reach ray never enters an unloaded neighbour halo");
    }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void expect(String code,Runnable action) { try { action.run(); throw new AssertionError("Expected "+code); } catch(BridgeException error) { check(code.equals(error.code),"Existing error code preserved: "+error.code); } }
}
