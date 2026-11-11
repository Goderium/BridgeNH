package com.Goderium.BridgeNH.client.navigation;

/** Refine conservative diagonal envelopes, never discard any translated body volume. */
public final class WalkingSweepProof {
    private static final int MAX_DEPTH = 3; // At most 15 native observations per original envelope.
    public interface Probe { WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep, long deadline); }

    public static WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep, Probe probe, long deadline) {
        return inspect(sweep, probe, deadline, System::nanoTime, 0);
    }
    static WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep, Probe probe, long deadline,
            WalkingPathPlanner.Clock clock, int depth) {
        if (clock.nanoTime() >= deadline) return WalkingPathPlanner.Check.PARTIAL;
        WalkingPathPlanner.Check check = probe.inspect(sweep, deadline);
        if (clock.nanoTime() >= deadline) return WalkingPathPlanner.Check.PARTIAL;
        if (check != WalkingPathPlanner.Check.BLOCKED || depth >= MAX_DEPTH
                || Math.abs(sweep.from.x-sweep.to.x) < .000001 || Math.abs(sweep.from.z-sweep.to.z) < .000001
                || Math.abs(sweep.from.y-sweep.to.y) > .000001) return check;
        // A diagonal AABB contains corners the actor never occupies. Both smaller
        // envelopes still enclose the complete continuous translation, without gaps.
        WalkingPathPlanner.Point middle = new WalkingPathPlanner.Point(sweep.x, sweep.feetY, sweep.z);
        double actorWidth = sweep.actorWidth;
        WalkingPathPlanner.Check first = inspect(new WalkingPathPlanner.Sweep(sweep.from,middle,actorWidth,sweep.height),
                probe,deadline,clock,depth+1);
        if (first != WalkingPathPlanner.Check.CLEAR) return first;
        return inspect(new WalkingPathPlanner.Sweep(middle,sweep.to,actorWidth,sweep.height),probe,deadline,clock,depth+1);
    }
}
