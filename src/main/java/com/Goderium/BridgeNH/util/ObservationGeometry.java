package com.Goderium.BridgeNH.util;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry used by bounded ray observations; no world or rendering access. */
public final class ObservationGeometry {
    /** -1 means unknown geometry, 0 disjoint, 1 overlapping. */
    public static int boxIntersection(double[] candidate, double[] query) {
        if (candidate == null || candidate.length != 6 || query == null || query.length != 6) return -1;
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(candidate[axis]) || !Double.isFinite(candidate[axis + 3])
                    || !Double.isFinite(query[axis]) || !Double.isFinite(query[axis + 3])
                    || candidate[axis] >= candidate[axis + 3] || query[axis] >= query[axis + 3]) return -1;
        }
        for (int axis = 0; axis < 3; axis++)
            if (candidate[axis + 3] <= query[axis] || candidate[axis] >= query[axis + 3]) return 0;
        return 1;
    }

    public static List<int[]> rayCells(double[] from, double[] to) {
        double[] direction = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
        double squared = direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2];
        if (!Double.isFinite(squared) || squared < .01 - .000000001 || squared > 128 * 128 + .000001)
            throw new BridgeException("invalid_args", "Ray length must be from 0.1 through 128 blocks.");
        int[] cell = {(int) Math.floor(from[0]), (int) Math.floor(from[1]), (int) Math.floor(from[2])};
        int[] end = {(int) Math.floor(to[0]), (int) Math.floor(to[1]), (int) Math.floor(to[2])};
        int[] step = new int[3];
        double[] next = new double[3], delta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            step[axis] = direction[axis] > 0 ? 1 : direction[axis] < 0 ? -1 : 0;
            delta[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction[axis]);
            next[axis] = step[axis] == 0 || cell[axis] == end[axis] ? Double.POSITIVE_INFINITY
                    : ((step[axis] > 0 ? cell[axis] + 1 : cell[axis]) - from[axis]) / direction[axis];
        }
        List<int[]> result = new ArrayList<int[]>();
        for (int i = 0; i < 512; i++) {
            result.add(cell.clone());
            if (cell[0] == end[0] && cell[1] == end[1] && cell[2] == end[2]) return result;
            int axis = next[0] <= next[1] && next[0] <= next[2] ? 0 : next[1] <= next[2] ? 1 : 2;
            cell[axis] += step[axis];
            // A negative endpoint exactly on a boundary belongs to floor(to),
            // so another tied axis must finish without stepping this one past it.
            next[axis] = cell[axis] == end[axis] ? Double.POSITIVE_INFINITY : next[axis] + delta[axis];
        }
        throw new BridgeException("too_large", "Ray traversal exceeded its bounded geometry.");
    }
}
