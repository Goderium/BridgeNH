package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable position, inclusive feet-position volume, or finite union of goals. No live world is retained. */
public abstract class NavigationGoal {
    public static final int MAX_CANDIDATES = 4096;
    abstract boolean contains(PlayerNavigation.Point point);
    abstract void collect(PlayerNavigation.Point start, List<PlayerNavigation.Point> result);
    abstract PlayerNavigation.Point anchor();
    abstract double heuristic(PlayerNavigation.Point point);
    final List<PlayerNavigation.Point> candidates(PlayerNavigation.Point start) {
        List<PlayerNavigation.Point> values = new ArrayList<PlayerNavigation.Point>();
        if (contains(start)) values.add(start);
        collect(start, values);
        Map<String,PlayerNavigation.Point> unique = new LinkedHashMap<String,PlayerNavigation.Point>();
        for (PlayerNavigation.Point p : values) unique.put(p.x+":"+p.y+":"+p.z,p);
        values = new ArrayList<PlayerNavigation.Point>(unique.values());
        if (values.size() > MAX_CANDIDATES) throw new BridgeException("unsupported", "Goal volume exceeds the finite 4096 endpoint budget.");
        Collections.sort(values, Comparator.comparingDouble(start::distance)); return values;
    }
    static NavigationGoal point(PlayerNavigation.Point point) { return new Box(point, point); }
    static NavigationGoal parse(JsonObject args) { return parse(args, 0); }
    private static NavigationGoal parse(JsonObject args, int depth) {
        String type = JsonData.string(args, "type");
        if ("nearby".equals(type) && depth == 0) return new NearbyNavigationGoal(args);
        if ("interaction".equals(type) && depth == 0) return new InteractionNavigationGoal(args);
        if ("point".equals(type)) {
            ExpandedGuiApi.keys(args, "type", "x", "y", "z"); return point(xyz(args));
        }
        if ("box".equals(type)) {
            ExpandedGuiApi.keys(args, "type", "min", "max");
            if (!args.has("min") || !args.get("min").isJsonObject() || !args.has("max") || !args.get("max").isJsonObject())
                throw new BridgeException("invalid_args", "A box goal requires min/max XYZ objects.");
            return new Box(xyz(args.getAsJsonObject("min")), xyz(args.getAsJsonObject("max")));
        }
        if ("any".equals(type) && depth == 0) {
            ExpandedGuiApi.keys(args, "type", "goals");
            if (!args.has("goals") || !args.get("goals").isJsonArray() || args.getAsJsonArray("goals").size() < 1 || args.getAsJsonArray("goals").size() > 8)
                throw new BridgeException("invalid_args", "any requires 1..8 point/box goals.");
            List<NavigationGoal> goals = new ArrayList<NavigationGoal>();
            for (JsonElement value : args.getAsJsonArray("goals")) {
                if (!value.isJsonObject()) throw new BridgeException("invalid_args", "Every goal must be an object.");
                goals.add(parse(value.getAsJsonObject(), depth + 1));
            }
            return new NavigationGoal() {
                boolean contains(PlayerNavigation.Point p) { for (NavigationGoal goal : goals) if (goal.contains(p)) return true; return false; }
                void collect(PlayerNavigation.Point s, List<PlayerNavigation.Point> r) { for (NavigationGoal goal : goals) goal.collect(s, r); }
                PlayerNavigation.Point anchor() { return goals.get(0).anchor(); }
                double heuristic(PlayerNavigation.Point p) { double best = Double.POSITIVE_INFINITY; for (NavigationGoal goal : goals) best = Math.min(best, goal.heuristic(p)); return best; }
            };
        }
        throw new BridgeException("invalid_args", "Supported goals are point, box and a one-level any union.");
    }
    static PlayerNavigation.Point xyz(JsonObject value) {
        ExpandedGuiApi.keys(value, "type", "x", "y", "z");
        return new PlayerNavigation.Point(JsonData.number(value, "x", -29999999, 29999999), JsonData.number(value, "y", 0, 253), JsonData.number(value, "z", -29999999, 29999999));
    }
    private static final class Box extends NavigationGoal {
        final PlayerNavigation.Point min, max;
        Box(PlayerNavigation.Point min, PlayerNavigation.Point max) {
            this.min = min; this.max = max;
            if (min.x > max.x || min.y > max.y || min.z > max.z || max.x-min.x > 16 || max.y-min.y > 16 || max.z-min.z > 16)
                throw new BridgeException("invalid_args", "Goal min must precede max; each inclusive extent is bounded to 16 blocks.");
        }
        boolean contains(PlayerNavigation.Point p) { if (min.distance(max) == 0) return p.near(min); return p.x >= min.x && p.x <= max.x && p.y >= min.y && p.y <= max.y && p.z >= min.z && p.z <= max.z; }
        PlayerNavigation.Point projected(PlayerNavigation.Point p) { return new PlayerNavigation.Point(Math.max(min.x, Math.min(max.x, p.x)), Math.max(min.y, Math.min(max.y, p.y)), Math.max(min.z, Math.min(max.z, p.z))); }
        double heuristic(PlayerNavigation.Point p) { return NavigationCosts.lowerBound(p.distance(projected(p))); }
        PlayerNavigation.Point anchor() { return min; }
        void collect(PlayerNavigation.Point s, List<PlayerNavigation.Point> r) {
            r.add(projected(s));
            for (double y = Math.ceil(min.y); y <= max.y; y++)
                for (double x = Math.ceil(min.x-.5)+.5; x <= max.x; x++)
                    for (double z = Math.ceil(min.z-.5)+.5; z <= max.z; z++) {
                        r.add(new PlayerNavigation.Point(x,y,z));
                        if (r.size() > MAX_CANDIDATES) throw new BridgeException("unsupported", "Goal endpoint budget exceeded.");
                    }
        }
    }
}
