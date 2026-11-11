package com.Goderium.BridgeNH.client.navigation;

/** Continuous-body corner fixtures, not game timing or live movement acceptance. */
public final class WalkingSweepProofSelfTest {
    public static int run() {
        WalkingPathPlanner.Sweep diagonal=sweep(0,0,.2,.2);
        WalkingSweepProof.Probe corner=(s,d) -> overlaps(s,-.29,-.27,.45,.47)
                ? WalkingPathPlanner.Check.BLOCKED : WalkingPathPlanner.Check.CLEAR;
        require(corner.inspect(diagonal,100)==WalkingPathPlanner.Check.BLOCKED,"Coarse diagonal has a false corner overlap.");
        require(prove(diagonal,corner)==WalkingPathPlanner.Check.CLEAR,"Full sub-envelopes prove the diagonal clear.");
        WalkingSweepProof.Probe actual=(s,d) -> overlaps(s,.38,.40,-.11,-.09)
                ? WalkingPathPlanner.Check.BLOCKED : WalkingPathPlanner.Check.CLEAR;
        require(prove(diagonal,actual)==WalkingPathPlanner.Check.BLOCKED,"Intermediate continuous collision stays blocked.");
        int checks=3;
        for(WalkingPathPlanner.Check unknown:new WalkingPathPlanner.Check[]{WalkingPathPlanner.Check.UNKNOWN,
                WalkingPathPlanner.Check.UNLOADED,WalkingPathPlanner.Check.PARTIAL}) {
            int[] calls={0};
            require(prove(diagonal,(s,d)->{calls[0]++; return unknown;})==unknown && calls[0]==1,
                    "Unknown, unloaded and partial evidence cannot become clear through refinement."); checks++;
        }
        int[] calls={0};
        require(prove(sweep(0,0,.2,0),(s,d)->{calls[0]++;return WalkingPathPlanner.Check.BLOCKED;})
                ==WalkingPathPlanner.Check.BLOCKED && calls[0]==1,"Axis aligned envelope needs no refinement."); checks++;
        calls[0]=0;
        require(prove(diagonal,(s,d)->{calls[0]++;return s.from.distance(s.to)>.036 ? WalkingPathPlanner.Check.BLOCKED
                : WalkingPathPlanner.Check.CLEAR;})==WalkingPathPlanner.Check.CLEAR && calls[0]==15,"Refinement has a hard 15-read bound."); checks++;
        final long[] clock={0}; calls[0]=0;
        require(WalkingSweepProof.inspect(diagonal,(s,d)->{calls[0]++; clock[0]=101;return WalkingPathPlanner.Check.CLEAR;},100,
                ()->clock[0],0)==WalkingPathPlanner.Check.PARTIAL && calls[0]==1,"Deadline expiry never accepts the last late sample."); checks++;
        require(prove(diagonal,(s,d)->WalkingPathPlanner.Check.BLOCKED)==WalkingPathPlanner.Check.BLOCKED,
                "Refinement exhaustion is still blocked."); checks++;
        return checks;
    }
    private static WalkingPathPlanner.Sweep sweep(double x,double z,double endX,double endZ) {
        return new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(x,4,z),new WalkingPathPlanner.Point(endX,4,endZ),.6,1.8);
    }
    private static WalkingPathPlanner.Check prove(WalkingPathPlanner.Sweep s,WalkingSweepProof.Probe p) {
        return WalkingSweepProof.inspect(s,p,100,()->0,0);
    }
    private static boolean overlaps(WalkingPathPlanner.Sweep s,double minX,double maxX,double minZ,double maxZ) {
        return s.x+s.width/2>minX && s.x-s.width/2<maxX && s.z+s.depth/2>minZ && s.z-s.depth/2<maxZ;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
