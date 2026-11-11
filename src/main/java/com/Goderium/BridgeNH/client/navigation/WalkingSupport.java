package com.Goderium.BridgeNH.client.navigation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.AxisAlignedBB;

/** Continuous native footprint contact, independent of how a block supplies its boxes. */
public final class WalkingSupport {
    public static final double CONTACT = .02;
    public static boolean covers(WalkingPathPlanner.Sweep sweep, List<AxisAlignedBB> supports, long deadline) {
        if (supports.size() > 128 || Math.abs(sweep.from.y-sweep.to.y) > .00001) return false;
        List<double[]> intervals = new ArrayList<double[]>();
        double half = sweep.actorWidth/2;
        for (AxisAlignedBB box : supports) {
            if (System.nanoTime() >= deadline) return false;
            if (box == null || !Double.isFinite(box.minX) || !Double.isFinite(box.maxX)
                    || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxZ) || !Double.isFinite(box.maxY)
                    || box.maxX-box.minX < CONTACT || box.maxZ-box.minZ < CONTACT
                    || Math.abs(box.maxY-sweep.feetY) > .0001) continue;
            double[] interval = {0, 1};
            if (clip(interval, sweep.from.x, sweep.to.x-sweep.from.x, box.minX-half+CONTACT, box.maxX+half-CONTACT)
                    && clip(interval, sweep.from.z, sweep.to.z-sweep.from.z, box.minZ-half+CONTACT, box.maxZ+half-CONTACT))
                intervals.add(interval);
        }
        intervals.sort((a,b) -> Double.compare(a[0],b[0]));
        double end = 0;
        for (double[] interval : intervals) {
            if (System.nanoTime() >= deadline || interval[0] > end) return false;
            end = Math.max(end,interval[1]);
            if (end >= 1) return true;
        }
        return false;
    }
    private static boolean clip(double[] interval, double start, double delta, double min, double max) {
        if (Math.abs(delta) < 1e-12) return start >= min && start <= max;
        double a=(min-start)/delta, b=(max-start)/delta;
        interval[0]=Math.max(interval[0],Math.min(a,b)); interval[1]=Math.min(interval[1],Math.max(a,b));
        return interval[0] <= interval[1];
    }
}
