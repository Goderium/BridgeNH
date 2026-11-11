package com.Goderium.BridgeNH.client.navigation;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.util.AxisAlignedBB;

public final class WalkingSupportSelfTest {
    public static int run() {
        AxisAlignedBB thin=box(0,.8,2,1);
        require(covers(.37,1.114,1.37,1.114,thin),"Footprint can overhang a thin native part while retaining continuous contact.");
        require(!covers(.37,1.32,1.37,1.32,thin),"Barely touching or losing the edge is not a support proof.");
        require(covers(.4,.5,1.6,.5,box(0,0,.6,1),box(1.0,0,2,1)),"Overlapping contact intervals bridge a gap smaller than the footprint.");
        require(!covers(.4,.5,1.6,.5,box(0,0,.4,1),box(1.2,0,2,1)),"Clear endpoint support never hides an unsupported interval between them.");
        require(!covers(.4,.5,1.6,.5,box(0,0,.4,.3),box(1.2,.7,2,1)),"Disjoint diagonal contact remains blocked.");
        WalkingPathPlanner.Sweep point=sweep(.4,.5,.4,.5);
        require(!WalkingSupport.covers(point,Collections.singletonList(AxisAlignedBB.getBoundingBox(0,3,0,1,4.9,1)),Long.MAX_VALUE),
                "A lower surface is not an exact grounded support at the requested feet height.");
        require(!WalkingSupport.covers(point,Collections.singletonList(thin),0),"Expired geometry budgets cannot certify support.");
        require(!WalkingSupport.covers(point,Collections.nCopies(129,thin),Long.MAX_VALUE),"Support enumeration retains its hard bound.");
        return 8;
    }
    private static AxisAlignedBB box(double x,double z,double xx,double zz) {return AxisAlignedBB.getBoundingBox(x,4,z,xx,4.9375,zz);}
    private static WalkingPathPlanner.Sweep sweep(double x,double z,double xx,double zz) {
        return new WalkingPathPlanner.Sweep(new WalkingPathPlanner.Point(x,4.9375,z),new WalkingPathPlanner.Point(xx,4.9375,zz),.6,1.8);
    }
    private static boolean covers(double x,double z,double xx,double zz,AxisAlignedBB... boxes) {
        return WalkingSupport.covers(sweep(x,z,xx,zz),Arrays.asList(boxes),Long.MAX_VALUE);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
