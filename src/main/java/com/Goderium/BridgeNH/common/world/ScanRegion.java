package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;

/** Immutable, explicit scan geometry; no world reads and no persistent default changes. */
public final class ScanRegion {
    public static final int MAX_EXTENT = 128;
    public static final int MAX_AXIS_SPAN = 263;
    final String mode, anchorSource;
    final boolean anchored;
    final int x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
    final Integer radius, verticalRadius;
    final Direction direction;
    final long requestedVolume, volume;

    ScanRegion(String mode, String anchorSource, int[] anchor, Integer radius, Integer verticalRadius,
            Direction direction, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long requestedVolume) {
        this.mode = mode; this.anchorSource = anchorSource; anchored = anchor != null;
        x = anchored ? anchor[0] : 0; y = anchored ? anchor[1] : 0; z = anchored ? anchor[2] : 0;
        this.radius = radius; this.verticalRadius = verticalRadius; this.direction = direction;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        this.requestedVolume = requestedVolume < 0 ? volume : requestedVolume;
    }

    static ScanRegion read(JsonObject args, ScanSettings settings, int[] defaultCenter) {
        boolean bounds = args.has("bounds"), directional = args.has("directional");
        boolean radii = args.has("radius") || args.has("radiusChunks") || args.has("verticalRadiusChunks");
        if (bounds && directional || (bounds || directional) && radii)
            throw new BridgeException("invalid_args", "bounds, directional and radius arguments are mutually exclusive.");
        if (bounds) {
            if (args.has("x") || args.has("y") || args.has("z"))
                throw new BridgeException("invalid_args", "Absolute bounds cannot be combined with an anchor x, y or z.");
            JsonObject box = object(args, "bounds", "minX", "minY", "minZ", "maxX", "maxY", "maxZ");
            int minX = JsonData.integer(box, "minX", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int minY = JsonData.integer(box, "minY", 0, 255);
            int minZ = JsonData.integer(box, "minZ", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int maxX = JsonData.integer(box, "maxX", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int maxY = JsonData.integer(box, "maxY", 0, 255);
            int maxZ = JsonData.integer(box, "maxZ", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            span(minX, maxX); span(minY, maxY); span(minZ, maxZ);
            return new ScanRegion("bounds", null, null, null, null, null, minX, minY, minZ, maxX, maxY, maxZ, -1);
        }
        int[] anchor = anchor(args, defaultCenter);
        String anchorSource = args.has("x") ? "explicit" : "player_feet";
        if (directional) {
            JsonObject value = object(args, "directional", "facing", "forward", "backward", "left", "right", "up", "down");
            Direction direction = new Direction(value);
            int fx = "east".equals(direction.facing) ? 1 : "west".equals(direction.facing) ? -1 : 0;
            int fz = "south".equals(direction.facing) ? 1 : "north".equals(direction.facing) ? -1 : 0;
            int rx = -fz, rz = fx;
            int minX = coordinate((long) anchor[0] + Math.min(-direction.backward * fx, direction.forward * fx)
                    + Math.min(-direction.left * rx, direction.right * rx), WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int maxX = coordinate((long) anchor[0] + Math.max(-direction.backward * fx, direction.forward * fx)
                    + Math.max(-direction.left * rx, direction.right * rx), WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int minZ = coordinate((long) anchor[2] + Math.min(-direction.backward * fz, direction.forward * fz)
                    + Math.min(-direction.left * rz, direction.right * rz), WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int maxZ = coordinate((long) anchor[2] + Math.max(-direction.backward * fz, direction.forward * fz)
                    + Math.max(-direction.left * rz, direction.right * rz), WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            int minY = coordinate((long) anchor[1] - direction.down, 0, 255);
            int maxY = coordinate((long) anchor[1] + direction.up, 0, 255);
            span(minX, maxX); span(minY, maxY); span(minZ, maxZ);
            return new ScanRegion("directional", anchorSource, anchor, null, null, direction,
                    minX, minY, minZ, maxX, maxY, maxZ, -1);
        }
        int[] radiiValue = settings.radii(args);
        int horizontal = radiiValue[0], vertical = radiiValue[1];
        return new ScanRegion("radius", anchorSource, anchor, horizontal, vertical, null,
                Math.max(WorldScan.WORLD_MIN, anchor[0] - horizontal), Math.max(0, anchor[1] - vertical),
                Math.max(WorldScan.WORLD_MIN, anchor[2] - horizontal), Math.min(WorldScan.WORLD_MAX, anchor[0] + horizontal),
                Math.min(255, anchor[1] + vertical), Math.min(WorldScan.WORLD_MAX, anchor[2] + horizontal),
                (2L * horizontal + 1) * (2L * horizontal + 1) * (2L * vertical + 1));
    }

    private static int[] anchor(JsonObject args, int[] defaultCenter) {
        boolean x = args.has("x"), y = args.has("y"), z = args.has("z");
        if (x != y || x != z) throw new BridgeException("invalid_args", "Provide x, y and z together, or omit all three.");
        int[] center = x ? new int[] {JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX),
                JsonData.integer(args, "y", 0, 255), JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX)} : defaultCenter;
        if (center == null) throw new BridgeException("invalid_args", "Scanning another dimension requires explicit x, y and z or absolute bounds.");
        if (center.length != 3 || center[0] < WorldScan.WORLD_MIN || center[0] > WorldScan.WORLD_MAX || center[1] < 0
                || center[1] > 255 || center[2] < WorldScan.WORLD_MIN || center[2] > WorldScan.WORLD_MAX)
            throw new BridgeException("invalid_args", "The default player center lies outside the supported world bounds.");
        return center;
    }

    private static JsonObject object(JsonObject args, String key, String... fields) {
        JsonElement element = args.get(key);
        if (element == null || !element.isJsonObject()) throw new BridgeException("invalid_args", key + " must be an object.");
        JsonObject value = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            boolean known = false;
            for (String field : fields) if (field.equals(entry.getKey())) { known = true; break; }
            if (!known) throw new BridgeException("invalid_args", "Unknown " + key + " field: " + entry.getKey());
        }
        for (String field : fields) if (!value.has(field)) throw new BridgeException("invalid_args", key + " requires " + field + ".");
        return value;
    }

    private static int coordinate(long value, int min, int max) {
        if (value < min || value > max) throw new BridgeException("invalid_args", "The requested directional bounds leave the supported world; no clipping is applied.");
        return (int) value;
    }

    private static void span(int min, int max) {
        if (max < min || (long) max - min + 1 > MAX_AXIS_SPAN)
            throw new BridgeException("invalid_args", "Every inclusive bounds axis must contain 1 through " + MAX_AXIS_SPAN + " blocks.");
    }

    JsonObject bounds() {
        JsonObject result = new JsonObject();
        result.addProperty("minX", minX); result.addProperty("minY", minY); result.addProperty("minZ", minZ);
        result.addProperty("maxX", maxX); result.addProperty("maxY", maxY); result.addProperty("maxZ", maxZ);
        return result;
    }

    void describe(JsonObject result) {
        result.addProperty("regionMode", mode);
        if (anchorSource == null) result.add("anchorSource", JsonNull.INSTANCE); else result.addProperty("anchorSource", anchorSource);
        if (anchored) {
            JsonObject center = new JsonObject(); center.addProperty("x", x); center.addProperty("y", y); center.addProperty("z", z);
            result.add("center", center);
        } else result.add("center", JsonNull.INSTANCE);
        result.add("bounds", bounds());
        if (radius == null) result.add("radiusBlocks", JsonNull.INSTANCE); else result.addProperty("radiusBlocks", radius);
        if (verticalRadius == null) result.add("verticalRadiusBlocks", JsonNull.INSTANCE); else result.addProperty("verticalRadiusBlocks", verticalRadius);
        if (direction != null) result.add("directional", direction.json());
    }

    static final class Direction {
        final String facing;
        final int forward, backward, left, right, up, down;
        Direction(JsonObject value) {
            facing = JsonData.string(value, "facing");
            if (!"north".equals(facing) && !"east".equals(facing) && !"south".equals(facing) && !"west".equals(facing))
                throw new BridgeException("invalid_args", "directional.facing must be north, east, south or west.");
            forward = JsonData.integer(value, "forward", 0, MAX_EXTENT); backward = JsonData.integer(value, "backward", 0, MAX_EXTENT);
            left = JsonData.integer(value, "left", 0, MAX_EXTENT); right = JsonData.integer(value, "right", 0, MAX_EXTENT);
            up = JsonData.integer(value, "up", 0, MAX_EXTENT); down = JsonData.integer(value, "down", 0, MAX_EXTENT);
        }
        JsonObject json() {
            JsonObject result = new JsonObject(); result.addProperty("facing", facing);
            result.addProperty("forward", forward); result.addProperty("backward", backward);
            result.addProperty("left", left); result.addProperty("right", right);
            result.addProperty("up", up); result.addProperty("down", down);
            return result;
        }
    }
}
