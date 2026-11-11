package com.Goderium.BridgeNH.client.navigation;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Dynamic-target decisions only; all movement is still executed by PlayerNavigation. */
public final class FollowTarget {
    public enum Decision { HOLD, MOVE, KEEP, REPLAN }
    public final String taskId=UUID.randomUUID().toString(), playerId, playerName, world, session;
    public final Object worldIdentity, actorIdentity, targetIdentity;
    public final long revision;
    public final int dimension;
    public volatile boolean active=true, workerBusy;
    public volatile int generation;
    public volatile String phase="starting", reason="";
    public long nextObservation;
    public int observations, segments, replans;
    public boolean holding;
    public PlayerNavigation.Point target, legTarget;

    public FollowTarget(String playerId,String playerName,String world,String session,int dimension,long revision,
            Object worldIdentity,Object actorIdentity,Object targetIdentity) {
        this.playerId=playerId; this.playerName=playerName; this.world=world; this.session=session; this.dimension=dimension;
        this.revision=revision; this.worldIdentity=worldIdentity; this.actorIdentity=actorIdentity; this.targetIdentity=targetIdentity;
    }
    public static Decision decide(PlayerNavigation.Point actor,PlayerNavigation.Point target,boolean visible,
            boolean holding,boolean moving,PlayerNavigation.Point legTarget) {
        if(visible && actor.distance(target)<=(holding ? 4 : 3)) return Decision.HOLD;
        if(!moving) return Decision.MOVE;
        // Small target motion does not restart a safe leg every second. Large turns invalidate its old endpoint.
        return legTarget!=null && legTarget.distance(target)>5 ? Decision.REPLAN : Decision.KEEP;
    }
    public JsonObject inspect() {
        JsonObject value=new JsonObject(); value.addProperty("taskId",taskId); value.addProperty("active",active);
        value.addProperty("phase",phase); value.addProperty("reason",reason); value.addProperty("playerName",playerName);
        value.addProperty("playerUuid",playerId); value.addProperty("observations",observations); value.addProperty("segments",segments);
        value.addProperty("replans",replans); value.addProperty("observationIntervalMillis",1000);
        value.addProperty("arrivalRadius",3); value.addProperty("resumeRadius",4);
        if(target!=null) value.add("target",target.json()); return value;
    }
}
