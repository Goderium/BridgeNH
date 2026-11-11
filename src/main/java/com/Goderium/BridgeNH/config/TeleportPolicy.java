package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.client.navigation.PlayerNavigation;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;

/** Validates the scalar arguments and final state before an authoritative teleport. */
public class TeleportPolicy {
    public static Target target(JsonObject args, int currentDimension) {
        return target(args, currentDimension, false);
    }

    public static Target target(JsonObject args, int currentDimension, boolean allowDimensionChange) {
        return target(args, currentDimension, allowDimensionChange, false);
    }

    public static Target target(JsonObject args, int currentDimension, boolean allowDimensionChange, boolean allowTrackingChunkLoad) {
        double x = JsonData.number(args, "x", -30000000, 30000000);
        double y = JsonData.number(args, "y", 0, 255);
        double z = JsonData.number(args, "z", -30000000, 30000000);
        int dimension = args.has("dimension") ? JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) : currentDimension;
        if (dimension != currentDimension && !allowDimensionChange) throw new BridgeException("permission_denied", "Teleport supports only the current dimension.");
        if (allowTrackingChunkLoad && (!allowDimensionChange || dimension == currentDimension))
            throw new BridgeException("invalid_args", "allowTrackingChunkLoad requires changeDimension=true and an actual different destination dimension.");
        return new Target(x, y, z, dimension, allowTrackingChunkLoad);
    }

    public static void requireReady(boolean permitted, boolean screenOpen, boolean paused, boolean fresh,
            boolean containerOpen, boolean mounted, boolean chunkLoaded) {
        if (!permitted) throw new BridgeException("permission_denied", "Teleport requires the server player to have normal tp command permission; creative mode alone is insufficient.");
        if (screenOpen || paused || !fresh) throw new BridgeException("invalid_state", "Teleport requires a current unpaused client with no GUI open.");
        if (containerOpen || mounted) throw new BridgeException("invalid_state", "Close the server container and dismount before teleport.");
        if (!chunkLoaded) throw new BridgeException("chunk_unloaded", "Teleport destination must already be loaded.");
    }

    public static void requireDestinationBody(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, int worldHeight) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX >= maxX || minY >= maxY || minZ >= maxZ
                || minX < -30000000 || maxX >= 30000000 || minZ < -30000000 || maxZ >= 30000000
                || minY < 0 || maxY > worldHeight || worldHeight <= 0)
            throw new BridgeException("invalid_args", "The full destination player body must fit inside valid world bounds.");
    }

    public static void requireFlight(boolean required, boolean allowFlying, boolean flying) {
        if (required && (!allowFlying || !flying))
            throw new BridgeException("invalid_state", "Airborne teleport requires authoritative server flight. Jump, set flight through player.flight and confirm the server sample first.");
    }

    public static boolean flightRequired(boolean supported, boolean explicitlyRequired, boolean creative, boolean allowFlying, boolean flying) {
        boolean required = !supported || explicitlyRequired;
        if (required && (!allowFlying || !creative && !flying))
            throw new BridgeException("permission_denied", "An unsupported teleport destination requires existing flight; only creative developer teleport may enable it automatically.");
        return required;
    }

    public static boolean arrived(JsonObject snapshot, Target target, boolean flightRequired, long newerThanSample) {
        if (!snapshot.has("player") || !snapshot.has("world") || !snapshot.has("serverControl")) return false;
        JsonObject player = PlayerNavigation.object(snapshot, "player"), control = PlayerNavigation.object(snapshot, "serverControl");
        if (!PlayerNavigation.flag(control, "available")) return false;
        JsonObject pose = PlayerNavigation.object(control, "pose"), world = PlayerNavigation.object(snapshot, "world");
        if (PlayerNavigation.sample(control) <= newerThanSample
                || JsonData.integer(world, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != target.dimension
                || JsonData.integer(control, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != target.dimension) return false;
        return near(player, target) && near(pose, target)
                && (!flightRequired || PlayerNavigation.flag(player, "flying") && PlayerNavigation.flag(pose, "flying")
                    && !PlayerNavigation.flag(player, "onGround") && !PlayerNavigation.flag(pose, "onGround"));
    }

    private static boolean near(JsonObject pose, Target target) {
        return pose.has("x") && pose.has("feetY") && pose.has("z")
                && Math.abs(JsonData.number(pose, "x", -30000000, 30000000) - target.x) <= .02
                && Math.abs(JsonData.number(pose, "feetY", 0, 256) - target.y) <= .02
                && Math.abs(JsonData.number(pose, "z", -30000000, 30000000) - target.z) <= .02;
    }

    public static class Target {
        public final double x;
        public final double y;
        public final double z;
        public final int dimension;
        public final boolean allowTrackingChunkLoad;
        @com.google.common.annotations.VisibleForTesting
        public Target(double x, double y, double z, int dimension) {
            this(x, y, z, dimension, false);
        }
        Target(double x, double y, double z, int dimension, boolean allowTrackingChunkLoad) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.allowTrackingChunkLoad = allowTrackingChunkLoad;
        }
    }
}
