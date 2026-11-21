package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import com.Goderium.BridgeNH.util.BridgeException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** A single grounded native input owner. Tick deadlines also release keys if the HTTP worker disappears. */
public final class RemoteWalkInput {
    public static final String OWNER = "player.remote_walk";
    public final String id = UUID.randomUUID().toString();
    public final EntityPlayer actor;
    public final World world;
    public final long deadline;
    private final KeyBinding forward, sneak, jump;
    private final int forwardCode, sneakCode, jumpCode;
    private boolean holdsForward, holdsSneak, holdsJump;
    public boolean transitMotion;
    private int ticks;
    private java.util.concurrent.CompletableFuture<Void> released;
    public java.util.concurrent.CompletableFuture<Void> released() { return released; }
    private long pulseDeadline;
    public volatile boolean aborted;
    public int pulses;
    public ClientGroundWalk job;

    public RemoteWalkInput(Minecraft client,long deadline) {
        this.deadline=deadline;
        actor=client.thePlayer; world=client.theWorld;
        forward=client.gameSettings.keyBindForward; sneak=client.gameSettings.keyBindSneak;
        jump=client.gameSettings.keyBindJump; jumpCode=jump.getKeyCode();
        forwardCode=forward.getKeyCode(); sneakCode=sneak.getKeyCode();
        if (forwardCode==0 || sneakCode==0 || forwardCode==sneakCode)
            throw new BridgeException("unsupported","Remote walking requires distinct forward and sneak bindings.");
    }
    public boolean held() { return ticks>0; }
    public boolean valid(Minecraft client) {
        return !aborted && System.nanoTime()<deadline && client.thePlayer==actor && client.theWorld==world && !actor.isDead
                && client.currentScreen==null && !client.isGamePaused() && !actor.capabilities.isFlying
                && forward==client.gameSettings.keyBindForward && forwardCode==forward.getKeyCode()
                && jump==client.gameSettings.keyBindJump && jumpCode==jump.getKeyCode()
                && sneak==client.gameSettings.keyBindSneak && sneakCode==sneak.getKeyCode();
    }
    public void pulse(Minecraft client,WalkingPathPlanner.Point target, BooleanSupplier authorized) {
        if (held() || !valid(client) || !authorized.getAsBoolean()) throw new BridgeException("stale_task","Remote walk input ownership changed.");
        double speed=actor.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.movementSpeed).getAttributeValue();
        net.minecraft.block.Block support=world.getBlock(net.minecraft.util.MathHelper.floor_double(actor.posX),
                net.minecraft.util.MathHelper.floor_double(actor.boundingBox.minY-.01), net.minecraft.util.MathHelper.floor_double(actor.posZ));
        if (!Double.isFinite(speed) || speed<=0 || speed>.14 || !Float.isFinite(support.slipperiness) || support.slipperiness<.4F || support.slipperiness>.65F)
            throw new BridgeException("route_unverified","Current speed or low-friction ground exceeds the verified sneak braking distance.");
        double dx=target.x-actor.posX, dz=target.z-actor.posZ;
        int duration=Math.hypot(dx,dz)<.38 ? 2 : 4;
        // Sneaking on an elevator is itself a request to descend.
        boolean elevator=ClientTransitRoute.color(world,ClientTransitRoute.floor(actor.posX),ClientTransitRoute.floor(actor.boundingBox.minY-.01),ClientTransitRoute.floor(actor.posZ))>=0;
        double length=Math.hypot(dx,dz);
        if (!elevator && length>0) {
            int nextX=ClientTransitRoute.floor(actor.posX+dx/length*.45), nextZ=ClientTransitRoute.floor(actor.posZ+dz/length*.45);
            int y=ClientTransitRoute.floor(actor.boundingBox.minY-.01);
            elevator=ClientTransitRoute.loaded(world,nextX,y,nextZ) && ClientTransitRoute.color(world,nextX,y,nextZ)>=0;
        }
        // Verify one pulse plus its coast, including lateral momentum. Shorten the pulse at a tight turn.
        WalkingPathPlanner.Point from=RemoteGroundRoute.feet(actor);
        double drag=Math.max(.546,support.slipperiness*.91), acceleration=speed*(elevator?1:.3)*.16277136/Math.pow(support.slipperiness*.91,3);
        long end=System.nanoTime()+WalkingPathPlanner.WORK_NANOS;
        for (int n=elevator?1:duration;n>=1;n--) {
            double travel=acceleration*n/(1-drag)+.025;
            WalkingPathPlanner.Point stop=new WalkingPathPlanner.Point(from.x+actor.motionX/(1-drag)+dx/length*travel,from.y,
                    from.z+actor.motionZ/(1-drag)+dz/length*travel);
            WalkingPathPlanner.Check space=RemoteGroundRoute.geometry(actor,new WalkingPathPlanner.Sweep(from,stop,actor.width+.01,actor.height),end);
            if (space==WalkingPathPlanner.Check.PARTIAL) throw new BridgeException("walk_slice","Client movement proof yielded.");
            if (space!=WalkingPathPlanner.Check.CLEAR) continue;
            press(client,dx,dz,true,!elevator,false,n,authorized);
            return;
        }
        throw new BridgeException("route_unverified","No grounded input pulse has a clear braking corridor.");
    }

    public void press(Minecraft client,double dx,double dz,boolean move,boolean crouch,boolean rise,int duration,BooleanSupplier authorized) {
        if (held() || !valid(client) || !authorized.getAsBoolean()) throw new BridgeException("stale_task","Walk input ownership changed.");
        if (rise && (jumpCode==0 || jumpCode==forwardCode || jumpCode==sneakCode))
            throw new BridgeException("unsupported","Transit requires a distinct native jump binding.");
        pulseDeadline=Math.min(deadline,System.nanoTime()+450000000L);
        OwnedKeyState.begin(OWNER,pulseDeadline,()->held() && valid(client) && authorized.getAsBoolean());
        released=new java.util.concurrent.CompletableFuture<Void>(); ticks=duration;
        try {
            if (dx!=0 || dz!=0) actor.rotationYaw=(float)-Math.toDegrees(Math.atan2(dx,dz));
            holdsForward=move; holdsSneak=crouch; holdsJump=rise;
            if (move) hold(forward,forwardCode);
            if (crouch) hold(sneak,sneakCode);
            if (rise) hold(jump,jumpCode);
            pulses++;
        } catch (RuntimeException failure) { abort(); throw failure; }
    }
    private static void hold(KeyBinding key,int code) {
        OwnedKeyState.hold(OWNER,key,code); KeyBinding.setKeyBindState(code,true);
    }
    public void tick(Minecraft client,boolean end) {
        if (!valid(client)) { interrupt("Client world, focus state, key binding or deadline changed."); return; }
        if (held() && (!OwnedKeyState.active(OWNER) || System.nanoTime()>=pulseDeadline)) { interrupt("Client input lease expired or its authorization changed."); return; }
        if (held() && (!transitMotion && !actor.onGround || actor.ridingEntity!=null)) { interrupt("Normal ground pulse lost physical support."); return; }
        if (!end && job!=null) job.tick(client);
        if (end && held() && ticks==1) release();
        else if (end && held()) ticks--;
    }
    private void release() {
        if (ticks>0) {
            if (holdsForward) KeyBinding.setKeyBindState(forwardCode,false);
            if (holdsSneak) KeyBinding.setKeyBindState(sneakCode,false);
            if (holdsJump) KeyBinding.setKeyBindState(jumpCode,false);
            holdsForward=holdsSneak=holdsJump=false;
            OwnedKeyState.clear(OWNER); ticks=0; released.complete(null);
        }
    }
    private void interrupt(String reason) {
        aborted=true; release();
        if (job!=null) job.finished.completeExceptionally(new BridgeException("walk_interrupted",reason));
    }
    public void abort() { interrupt("Client walk stopped before completion."); }
}
