package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.ArrayList;
import java.util.List;

/** Client-owned planning windows; declared visits are preserved and every window has detour slack. */
public final class NavigationSegments {
    public static final int MAX_DISTANCE = 256, MAX_SEGMENTS = 32, MAX_ROUTE_POINTS = 1024;
    static final double WINDOW_DISTANCE = 24;
    static final int GATE_RADIUS = 4;
    static List<PlayerNavigation.Point> gates(PlayerNavigation.Point p) {
        List<PlayerNavigation.Point> values=new ArrayList<PlayerNavigation.Point>(); values.add(p);
        // Flexible planning boundaries, not required visits; the final/declared target stays exact.
        // Reuse the eight-block window slack on both sides of a boundary.
        // Two blocks cannot clear a boundary inside a machine plus its IO.
        // Each candidate still receives complete bounded route proof.
        for(int radius=1;radius<=GATE_RADIUS;radius++) for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            values.add(new PlayerNavigation.Point(p.x+d[0]*radius,p.y,p.z+d[1]*radius));
        for(int dy=-3;dy<=1;dy++) if(dy!=0 && p.y+dy>=0 && p.y+dy<=253) values.add(new PlayerNavigation.Point(p.x,p.y+dy,p.z));
        return values;
    }
    static List<PlayerNavigation.Point> split(PlayerNavigation.Point start, List<PlayerNavigation.Point> targets, boolean walking) {
        List<PlayerNavigation.Point> result = new ArrayList<PlayerNavigation.Point>(); PlayerNavigation.Point previous = start;
        double total = 0;
        for (PlayerNavigation.Point target : targets) {
            double distance = previous.distance(target); total += distance;
            int parts = Math.max(1, (int)Math.ceil(distance / WINDOW_DISTANCE));
            for (int i=1; i<parts; i++) {
                double f = i/(double)parts;
                result.add(new PlayerNavigation.Point(previous.x+(target.x-previous.x)*f,
                        walking ? Math.rint(previous.y+(target.y-previous.y)*f) : previous.y+(target.y-previous.y)*f,
                        previous.z+(target.z-previous.z)*f));
            }
            result.add(target); previous = target;
        }
        if (total > MAX_DISTANCE || result.size() > MAX_SEGMENTS) throw new BridgeException("unsupported", "Segmented route exceeds 256 blocks or 32 windows.");
        return result;
    }
}
