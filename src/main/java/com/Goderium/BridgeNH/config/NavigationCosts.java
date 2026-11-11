package com.Goderium.BridgeNH.config;

import com.google.gson.JsonObject;

/** A common estimated tick/resource cost, never permission or a physical execution guarantee. */
public final class NavigationCosts {
    public static volatile double walkTicksPerBlock = 4.65, jumpTicks = 8, dropRecoveryTicks = 3, placeTicks = 4, materialPenaltyTicks = 8;
    public static final class Cost {
        final double ticks;
        @com.google.common.annotations.VisibleForTesting
        public final long items;
        final double penalty;
        @com.google.common.annotations.VisibleForTesting
        public final boolean possible;
        @com.google.common.annotations.VisibleForTesting
        public Cost(double ticks, long items) { this(ticks, items, materialPenaltyTicks); }
        Cost(double ticks, long items, double penalty) {
            possible = Double.isFinite(ticks) && ticks >= 0 && items >= 0;
            this.ticks = possible ? ticks : Double.POSITIVE_INFINITY; this.items = items; this.penalty = penalty;
        }
        public double score() { return possible ? ticks + items * penalty : Double.POSITIVE_INFINITY; }
        @com.google.common.annotations.VisibleForTesting
        public Cost plus(Cost other) { return new Cost(ticks + other.ticks, items > Long.MAX_VALUE-other.items ? Long.MAX_VALUE : items + other.items, penalty); }
        public JsonObject json() {
            JsonObject value = new JsonObject(); value.addProperty("possible", possible);
            if (possible) { value.addProperty("estimatedTicks", ticks); value.addProperty("resourceItems", items); value.addProperty("scoreTicks", score()); }
            value.addProperty("estimateOnly", true); return value;
        }
    }
    /** One planning job keeps one cost model even when configuration is changed while it yields. */
    public static final class Model {
        public final double walk = walkTicksPerBlock, jump = jumpTicks, drop = dropRecoveryTicks, place = placeTicks, penalty = materialPenaltyTicks;
        public Cost walking(double horizontal, double height) {
            double ticks = horizontal * walk;
            if (height > 0) ticks += jump * height;
            else if (height < 0) ticks += Math.sqrt(-height * 2 / .08) + drop;
            return new Cost(ticks,0,penalty);
        }
        public Cost placing(int count) { return new Cost(place*count,count,penalty); }
        public Cost breaking(double hardness, boolean creative) { return new Cost(creative ? 1 : hardness > 0 && Double.isFinite(hardness) ? Math.ceil(1/hardness) : Double.POSITIVE_INFINITY,0,penalty); }
        public double lowerBound(double distance) { return distance * Math.min(1,walk); }
        public JsonObject json() {
            JsonObject value=new JsonObject(); value.addProperty("unit","estimated_native_ticks_plus_material_penalty");
            value.addProperty("walkTicksPerBlock",walk); value.addProperty("jumpTicks",jump); value.addProperty("dropRecoveryTicks",drop);
            value.addProperty("placeTicks",place); value.addProperty("materialPenaltyTicks",penalty); value.addProperty("estimatesAreNotPermissions",true); return value;
        }
    }
    public static Model snapshot() { return new Model(); }
    public static double lowerBound(double distance) { return distance * Math.min(1, walkTicksPerBlock); }
    @com.google.common.annotations.VisibleForTesting
    public static Cost walking(double horizontal, double height) {
        double ticks = horizontal * walkTicksPerBlock;
        if (height > 0) ticks += jumpTicks * height;
        else if (height < 0) ticks += Math.sqrt(-height * 2 / .08) + dropRecoveryTicks;
        return new Cost(ticks, 0);
    }
    static Cost flight(double distance) { return new Cost(distance, 0); }
    @com.google.common.annotations.VisibleForTesting
    public static Cost breaking(double relativeHardness, boolean creative) {
        return new Cost(creative ? 1 : relativeHardness > 0 && Double.isFinite(relativeHardness) ? Math.ceil(1 / relativeHardness) : Double.POSITIVE_INFINITY, 0);
    }
    @com.google.common.annotations.VisibleForTesting
    public static Cost placing(int count) { return new Cost(placeTicks * count, count); }
    public static JsonObject settings() {
        JsonObject value = new JsonObject(); value.addProperty("unit", "estimated_native_ticks_plus_material_penalty");
        value.addProperty("walkTicksPerBlock", walkTicksPerBlock); value.addProperty("jumpTicks", jumpTicks);
        value.addProperty("dropRecoveryTicks", dropRecoveryTicks); value.addProperty("placeTicks", placeTicks);
        value.addProperty("materialPenaltyTicks", materialPenaltyTicks); value.addProperty("estimatesAreNotPermissions", true); return value;
    }
}
