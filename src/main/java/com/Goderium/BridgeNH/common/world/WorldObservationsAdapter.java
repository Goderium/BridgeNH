package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.client.navigation.WalkingPathPlanner;
import com.Goderium.BridgeNH.client.navigation.WalkingSupport;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;
import com.Goderium.BridgeNH.util.ObservationGeometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Loaded-world entity and physical-space data, evaluated on the owning game thread. */
public class WorldObservationsAdapter {
    static final int CANDIDATES = 8192, RESULT_BYTES = 128 * 1024;
    static final long SOFT_NANOS = 4000000L;
    private static final WorldBlockCache BLOCK_CACHE = new WorldBlockCache();

    public static void configureCache(ScanSettings settings) {
        BLOCK_CACHE.configure(settings);
    }

    public static void clearCache() { BLOCK_CACHE.clear("world_profile_or_settings_changed"); }
    public static void maintainCache() { BLOCK_CACHE.maintain(); }
    public static void warmCache(World world,EntityPlayer player) { BLOCK_CACHE.warm(world,player); }
    public static void unloadCache(World world, Integer chunkX, Integer chunkZ) { BLOCK_CACHE.unload(world, chunkX, chunkZ); }

    public static JsonObject cacheStatus() { return BLOCK_CACHE.status(); }

    static WorldBlockCache.Reader cacheReader(World world, EntityPlayer player) { return BLOCK_CACHE.reader(world, player); }

    public static JsonObject entities(World world, EntityPlayer player, JsonObject args, ScanSettings settings) {
        long started = System.nanoTime();
        int[] center = center(player, args);
        int[] radii = settings.radii(args);
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        String query = CatalogData.text(args, "query", 128, "");
        boolean intersectBounds = flag(args, "intersectBounds", false);
        double[] queryBox = {center[0] - radii[0], center[1] - radii[1], center[2] - radii[0],
                center[0] + radii[0] + 1.0, center[1] + radii[1] + 1.0, center[2] + radii[0] + 1.0};
        List<?> source = world.loadedEntityList;
        JsonArray entities = new JsonArray();
        int next = Math.min(offset, source.size()), visited = 0, bytes = 4096, unknownBounds = 0;
        while (next < source.size() && visited < CANDIDATES && entities.size() < limit) {
            if (visited > 0 && System.nanoTime() - started >= SOFT_NANOS) break;
            Object entry = source.get(next++); visited++;
            if (!(entry instanceof Entity)) continue;
            Entity entity = (Entity) entry;
            if (entity.worldObj != world) continue;
            int intersection = -1;
            if (intersectBounds) {
                AxisAlignedBB box = entity.boundingBox;
                intersection = ObservationGeometry.boxIntersection(box == null ? null : new double[] {
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, queryBox);
                if (intersection == 0) continue;
            } else if (!Double.isFinite(entity.posX) || !Double.isFinite(entity.posY) || !Double.isFinite(entity.posZ)
                    || Math.abs(entity.posX - center[0]) > radii[0] || Math.abs(entity.posZ - center[2]) > radii[0]
                    || Math.abs(entity.posY - center[1]) > radii[1]) continue;
            String type = EntityList.getEntityString(entity);
            String name = entity.getCommandSenderName();
            if (!CatalogData.matches(query, type, name, entity.getClass().getName())) continue;
            JsonObject value = entity(entity);
            if (intersectBounds) value.addProperty("boundsStatus", intersection < 0 ? "unknown" : "intersecting");
            int size = value.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > RESULT_BYTES - 4096) { next--; break; }
            bytes += size; entities.add(value);
            if (intersectBounds && intersection < 0) unknownBounds++;
        }
        JsonObject result = context(world, started);
        result.add("center", coordinates(center[0], center[1], center[2]));
        JsonObject bounds = new JsonObject();
        bounds.addProperty("radiusBlocks", radii[0]); bounds.addProperty("verticalRadiusBlocks", radii[1]);
        result.add("bounds", bounds); result.add("entities", entities);
        result.addProperty("boundsMode", intersectBounds ? "intersection" : "position");
        if (intersectBounds) {
            result.add("queryBounds", box(AxisAlignedBB.getBoundingBox(queryBox[0], queryBox[1], queryBox[2], queryBox[3], queryBox[4], queryBox[5])));
            result.addProperty("unknownBoundsCandidates", unknownBounds);
        }
        result.addProperty("offset", offset); result.addProperty("candidatesVisited", visited);
        result.addProperty("loadedCandidates", source.size()); result.addProperty("hasMore", next < source.size());
        result.add("nextOffset", next < source.size() ? new JsonPrimitive(next) : JsonNull.INSTANCE);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("source", "Current loadedEntityList only; no chunk loading, entity ticking or screenshot.");
        coverage.addProperty("offset", "Loaded candidate-list index, not filtered-result count; list ordering can change between fresh observations.");
        coverage.addProperty("ownership", "Public owner UUID getter when exposed; absent fields are unknown, not proof of no owner.");
        coverage.addProperty("geometry", intersectBounds
                ? "Actual entity AABB overlapping the outer inclusive block bounds; unknown AABBs are retained regardless of entity position."
                : "Entity position inside the configured radii.");
        result.add("coverage", coverage);
        return result;
    }

    public static JsonObject spatial(World world, EntityPlayer player, JsonObject args) {
        long started = System.nanoTime();
        String mode = JsonData.string(args, "mode");
        if ("raycast".equals(mode)) return ray(world, player, args, started);
        if (!"positions".equals(mode)) throw new BridgeException("invalid_args", "mode must be raycast or positions.");
        reject(args, "from", "to", "distance", "includeEntities");
        JsonElement input = args.get("positions");
        if (input == null || !input.isJsonArray() || input.getAsJsonArray().size() < 1 || input.getAsJsonArray().size() > 128)
            throw new BridgeException("invalid_args", "positions must contain one through 128 coordinates.");
        double width = args.has("width") ? JsonData.number(args, "width", .1, 4) : player.width;
        double height = args.has("height") ? JsonData.number(args, "height", .1, 8) : player.height;
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 127) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32;
        JsonArray positions = input.getAsJsonArray(), output = new JsonArray();
        int next = Math.min(offset, positions.size()), bytes = 4096;
        boolean byteLimited = false;
        while (next < positions.size() && output.size() < limit) {
            if (output.size() > 0 && System.nanoTime() - started >= SOFT_NANOS) break;
            JsonElement entry = positions.get(next);
            if (!entry.isJsonObject()) throw new BridgeException("invalid_args", "Each position must be an object.");
            JsonObject point = entry.getAsJsonObject();
            int x = JsonData.integer(point, "x", -30000000, 30000000);
            int y = JsonData.integer(point, "y", 0, 255);
            int z = JsonData.integer(point, "z", -30000000, 30000000);
            JsonObject observed = space(world, player, x, y, z, width, height);
            int size = observed.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > RESULT_BYTES - 4096) { byteLimited = true; break; }
            bytes += size; output.add(observed); next++;
        }
        JsonObject result = context(world, started);
        result.addProperty("mode", mode); result.addProperty("width", width); result.addProperty("height", height);
        result.add("positions", output); result.addProperty("offset", offset); result.addProperty("hasMore", next < positions.size());
        result.addProperty("byteLimited", byteLimited);
        result.add("nextOffset", next < positions.size() ? new JsonPrimitive(next) : JsonNull.INSTANCE);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("geometry", "Feet at (x+0.5,y,z+0.5); actual block collision boxes plus loaded collidable entity boxes.");
        coverage.addProperty("canStand", "Occupancy clear with support directly below; excludes movement abilities, pathfinding, ladders and mod-specific hazards.");
        coverage.addProperty("hazards", "Lava/fire/cactus and intersecting liquid are exposed; no claim of exhaustive mod hazard detection.");
        coverage.addProperty("loading", "Body/support and one-block neighbor halo must be loaded; unknown regions are not passable.");
        result.add("coverage", coverage);
        return result;
    }

    private static JsonObject space(World world, EntityPlayer player, int x, int y, int z, double width, double height) {
        return space(world, player, x, y, z, width, height, System.nanoTime() + SOFT_NANOS);
    }

    public static JsonObject space(World world, EntityPlayer player, int x, int y, int z, double width, double height, long deadline) {
        JsonObject result = spaceAtInternal(world, player, x + .5, y, z + .5, width, height, deadline, false, false);
        result.addProperty("x", x); result.addProperty("y", y); result.addProperty("z", z); return result;
    }

    /** Actual continuous feet coordinates. Existing integer observations retain their prior support semantics. */
    public static JsonObject spaceAt(World world, EntityPlayer player, double feetX, double feetY, double feetZ, double width, double height, long deadline, boolean fullSupport) {
        return spaceAtInternal(world, player, feetX, feetY, feetZ, width, height, deadline, fullSupport, true);
    }

    static JsonObject planningSpaceAt(World world, EntityPlayer player, double feetX, double feetY, double feetZ,
            double width, double height, long deadline, boolean fullSupport) {
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(feetX - width / 2, feetY + .00001, feetZ - width / 2,
                feetX + width / 2, feetY + height, feetZ + width / 2);
        return spaceAtBody(world, player, feetX, feetY, feetZ, body, deadline, fullSupport, true, BLOCK_CACHE.reader(world, player));
    }

    public static JsonObject planningSpaceAt(World world, EntityPlayer player, AxisAlignedBB body, long deadline) {
        return planningSpaceAt(world,player,body,deadline,false);
    }
    static JsonObject planningSpaceAt(World world, EntityPlayer player, AxisAlignedBB body, long deadline, boolean fullSupport) {
        if (!validBody(body)) return spaceAt(world, player, body, deadline, fullSupport);
        return spaceAtBody(world, player, (body.minX + body.maxX) / 2, body.minY,
                (body.minZ + body.maxZ) / 2, body, deadline, fullSupport, true, BLOCK_CACHE.reader(world, player));
    }

    public static JsonObject walkingSpaceAt(World world, EntityPlayer player, WalkingPathPlanner.Sweep sweep, long deadline, boolean planning) {
        return spaceAtBody(world, player, sweep.x, sweep.feetY, sweep.z, sweep.body(), deadline, true, true,
                planning ? BLOCK_CACHE.reader(world, player) : null, sweep);
    }

    private static boolean validBody(AxisAlignedBB body) {
        return body != null && Double.isFinite(body.minX) && Double.isFinite(body.minY) && Double.isFinite(body.minZ)
                && Double.isFinite(body.maxX) && Double.isFinite(body.maxY) && Double.isFinite(body.maxZ)
                && body.maxX > body.minX && body.maxY > body.minY && body.maxZ > body.minZ
                && body.maxX - body.minX <= 64 && body.maxY - body.minY <= 64 && body.maxZ - body.minZ <= 64;
    }

    /** Read the full caller-supplied native body envelope without shrinking or squaring its X/Z footprint. */
    public static JsonObject spaceAt(World world, EntityPlayer player, AxisAlignedBB body, long deadline, boolean fullSupport) {
        if (!validBody(body)) {
            JsonObject result = new JsonObject(); result.addProperty("status", "unknown"); result.addProperty("loaded", false);
            result.add("occupiable", JsonNull.INSTANCE); result.add("canStand", JsonNull.INSTANCE); return result;
        }
        return spaceAtBody(world, player, (body.minX + body.maxX) / 2, body.minY,
                (body.minZ + body.maxZ) / 2, body, deadline, fullSupport, true);
    }

    private static JsonObject spaceAtInternal(World world, EntityPlayer player, double feetX, double feetY, double feetZ, double width, double height, long deadline, boolean fullSupport, boolean validateBounds) {
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(feetX - width / 2, feetY + .00001, feetZ - width / 2,
                feetX + width / 2, feetY + height, feetZ + width / 2);
        return spaceAtBody(world, player, feetX, feetY, feetZ, body, deadline, fullSupport, validateBounds);
    }

    private static JsonObject spaceAtBody(World world, EntityPlayer player, double feetX, double feetY, double feetZ,
            AxisAlignedBB body, long deadline, boolean fullSupport, boolean validateBounds) {
        return spaceAtBody(world, player, feetX, feetY, feetZ, body, deadline, fullSupport, validateBounds, null);
    }

    private static JsonObject spaceAtBody(World world, EntityPlayer player, double feetX, double feetY, double feetZ,
            AxisAlignedBB body, long deadline, boolean fullSupport, boolean validateBounds, WorldBlockCache.Reader cache) {
        return spaceAtBody(world,player,feetX,feetY,feetZ,body,deadline,fullSupport,validateBounds,cache,null);
    }
    private static JsonObject spaceAtBody(World world, EntityPlayer player, double feetX, double feetY, double feetZ,
            AxisAlignedBB body, long deadline, boolean fullSupport, boolean validateBounds, WorldBlockCache.Reader cache, WalkingPathPlanner.Sweep sweep) {
        int x = (int) Math.floor(feetX), y = (int) Math.floor(feetY), z = (int) Math.floor(feetZ);
        JsonObject result = coordinates(feetX, feetY, feetZ);
        AxisAlignedBB support = AxisAlignedBB.getBoundingBox(body.minX, feetY - .05, body.minZ, body.maxX, feetY - .00001, body.maxZ);
        if (validateBounds && (feetY < 0 || body.maxY > 256)) { result.addProperty("loaded", false); result.addProperty("status", "outside_world"); result.add("occupiable", JsonNull.INSTANCE); result.add("canStand", JsonNull.INSTANCE); return result; }
        result.add("body", box(body));
        if (!loadedArea(world, body.minX - 1, body.maxX + 1, body.minZ - 1, body.maxZ + 1)) {
            result.addProperty("loaded", false); result.addProperty("status", "unloaded");
            result.add("occupiable", JsonNull.INSTANCE); result.add("canStand", JsonNull.INSTANCE); return result;
        }
        result.addProperty("loaded", true);
        List<AxisAlignedBB> collisions = new ArrayList<AxisAlignedBB>(), supports = new ArrayList<AxisAlignedBB>();
        boolean liquid = false, hazard = false;
        for (int bx = (int) Math.floor(body.minX); bx <= (int) Math.floor(body.maxX); bx++) {
            for (int bz = (int) Math.floor(body.minZ); bz <= (int) Math.floor(body.maxZ); bz++) {
                for (int by = Math.max(0, y - 1); by <= Math.min(255, (int) Math.floor(body.maxY)); by++) {
                    if (System.nanoTime() >= deadline) {
                        result.addProperty("status", "partial"); result.addProperty("entityCoveragePartial", true);
                        result.add("occupiable", JsonNull.INSTANCE); result.add("canStand", JsonNull.INSTANCE); return result;
                    }
                    WorldBlockCache.Sample cached = cache == null ? null : cache.read(bx, by, bz);
                    Block block = cached == null ? world.getBlock(bx, by, bz) : cached.block;
                    if (cached != null && cached.appendGeometry(bx, by, bz, body, support, by <= feetY, collisions, supports)) BLOCK_CACHE.geometryHit();
                    else {
                        block.addCollisionBoxesToList(world, bx, by, bz, body, collisions, player);
                        if (by <= feetY) block.addCollisionBoxesToList(world, bx, by, bz, support, supports, player);
                    }
                    if (by < y || !AxisAlignedBB.getBoundingBox(bx, by, bz, bx + 1, by + 1, bz + 1).intersectsWith(body)) continue;
                    Material material = block.getMaterial();
                    liquid |= material.isLiquid();
                    String name = cached == null ? String.valueOf(Block.blockRegistry.getNameForObject(block)) : cached.registryName;
                    hazard |= material == Material.lava || "minecraft:fire".equals(name) || "minecraft:cactus".equals(name);
                }
            }
        }
        JsonArray blockers = new JsonArray();
        int visited = 0; boolean entityPartial = false;
        for (Object entry : world.getEntitiesWithinAABBExcludingEntity(player,body)) {
            if (++visited > CANDIDATES || System.nanoTime() >= deadline) { entityPartial = true; break; }
            if (!(entry instanceof Entity)) continue;
            Entity entity = (Entity) entry;
            if (entity == player || entity.isDead || entity.boundingBox == null || !entity.canBeCollidedWith()) continue;
            if (entity.boundingBox.intersectsWith(body)) {
                if (blockers.size() >= 128) { entityPartial = true; break; }
                blockers.add(new JsonPrimitive(entity.getEntityId()));
            }
        }
        JsonArray boxes = new JsonArray();
        for (int i = 0; i < Math.min(128, collisions.size()); i++) boxes.add(box(collisions.get(i)));
        result.add("collisions", boxes); result.addProperty("collisionCount", collisions.size());
        result.addProperty("collisionsTruncated", collisions.size() > 128);
        result.add("blockingEntities", blockers); result.addProperty("entityCoveragePartial", entityPartial);
        boolean clear = collisions.isEmpty() && blockers.size() == 0;
        result.add("occupiable", entityPartial && clear ? JsonNull.INSTANCE : new JsonPrimitive(clear));
        result.addProperty("supported", !supports.isEmpty());
        boolean coverage = !supports.isEmpty();
        if (fullSupport) {
            if (supports.size() > 128) entityPartial = true;
            else {
                List<Double> cuts = new ArrayList<Double>(); cuts.add(body.minX); cuts.add(body.maxX);
                for (AxisAlignedBB box : supports) if (Math.abs(box.maxY - feetY) <= .050001) {
                    cuts.add(Math.max(body.minX, Math.min(body.maxX, box.minX))); cuts.add(Math.max(body.minX, Math.min(body.maxX, box.maxX)));
                }
                java.util.Collections.sort(cuts);
                for (int i = 1; i < cuts.size(); i++) {
                    if (System.nanoTime() >= deadline) { entityPartial = true; break; }
                    if (cuts.get(i) - cuts.get(i - 1) <= .000001) continue;
                    double center = (cuts.get(i) + cuts.get(i - 1)) / 2, end = body.minZ;
                    List<AxisAlignedBB> strips = new ArrayList<AxisAlignedBB>();
                    for (AxisAlignedBB box : supports) if (center >= box.minX && center <= box.maxX && Math.abs(box.maxY - feetY) <= .050001) strips.add(box);
                    java.util.Collections.sort(strips, (first, second) -> Double.compare(first.minZ, second.minZ));
                    for (AxisAlignedBB box : strips) { if (box.maxZ <= end) continue; if (box.minZ > end + .000001) break; end = Math.max(end, box.maxZ); }
                    coverage &= end >= body.maxZ - .000001;
                }
            }
            result.addProperty("fullSupportCoverage", coverage && !entityPartial);
        }
        if (sweep != null) {
            boolean contact = !entityPartial && WalkingSupport.covers(sweep,supports,deadline);
            if (System.nanoTime() >= deadline) entityPartial = true;
            result.addProperty("supportContactVersion",1);
            result.addProperty("continuousSupportCoverage",contact && !entityPartial);
            result.addProperty("minimumContactPerAxis",WalkingSupport.CONTACT);
            coverage |= contact;
        }
        result.addProperty("entityCoveragePartial", entityPartial);
        result.add("canStand", entityPartial && clear ? JsonNull.INSTANCE : new JsonPrimitive(clear && coverage));
        result.addProperty("liquid", liquid); result.addProperty("hazard", hazard);
        result.addProperty("light", world.getBlockLightValue(x, y, z));
        result.addProperty("status", entityPartial ? "partial" : "observed");
        return result;
    }

    private static JsonObject ray(World world, EntityPlayer player, JsonObject args, long started) {
        reject(args, "positions", "offset", "limit", "width", "height");
        boolean explicit = args.has("from") || args.has("to");
        double[] from, to;
        if (explicit) {
            if (!args.has("from") || !args.has("to") || args.has("distance"))
                throw new BridgeException("invalid_args", "from/to must be supplied together without distance.");
            from = vector(args, "from"); to = vector(args, "to");
        } else {
            double distance = args.has("distance") ? JsonData.number(args, "distance", .1, 128) : 5;
            Vec3 eye = player.getPosition(1); Vec3 look = player.getLookVec();
            from = new double[] {eye.xCoord, eye.yCoord, eye.zCoord};
            to = new double[] {eye.xCoord + look.xCoord * distance, eye.yCoord + look.yCoord * distance, eye.zCoord + look.zCoord * distance};
        }
        boolean includeEntities = flag(args, "includeEntities", true);
        Vec3 start = Vec3.createVectorHelper(from[0], from[1], from[2]), end = Vec3.createVectorHelper(to[0], to[1], to[2]);
        MovingObjectPosition hit = null;
        JsonObject stopped = null;
        String status = "observed";
        int visited = 0;
        for (int[] cell : ObservationGeometry.rayCells(from, to)) {
            if (visited > 0 && System.nanoTime() - started >= SOFT_NANOS) { status = "budget_partial"; stopped = coordinates(cell[0], cell[1], cell[2]); break; }
            visited++;
            if (cell[1] < 0 || cell[1] > 255) continue;
            if (!loadedArea(world, cell[0] - 1, cell[0] + 2, cell[2] - 1, cell[2] + 2)) {
                status = "unloaded"; stopped = coordinates(cell[0], cell[1], cell[2]); break;
            }
            Block block = world.getBlock(cell[0], cell[1], cell[2]);
            int metadata = world.getBlockMetadata(cell[0], cell[1], cell[2]);
            if (block.canStopRayTrace(metadata, false)) {
                hit = block.collisionRayTrace(world, cell[0], cell[1], cell[2], start, end);
                if (hit != null) break;
            }
        }
        boolean entityPartial = false;
        if (includeEntities && "observed".equals(status)) {
            double nearest = hit == null ? start.distanceTo(end) : start.distanceTo(hit.hitVec);
            int candidates = 0;
            for (Object entry : world.loadedEntityList) {
                if (++candidates > CANDIDATES || System.nanoTime() - started >= SOFT_NANOS) { entityPartial = true; break; }
                if (!(entry instanceof Entity)) continue;
                Entity entity = (Entity) entry;
                if (entity == player || entity.isDead || entity.boundingBox == null || !entity.canBeCollidedWith()) continue;
                double border = entity.getCollisionBorderSize();
                AxisAlignedBB bounds = entity.boundingBox.expand(border, border, border);
                MovingObjectPosition intercept = bounds.calculateIntercept(start, end);
                double distance = bounds.isVecInside(start) ? 0 : intercept == null ? Double.POSITIVE_INFINITY : start.distanceTo(intercept.hitVec);
                if (distance < nearest) {
                    nearest = distance; hit = new MovingObjectPosition(entity);
                    hit.hitVec = distance == 0 ? start : intercept.hitVec;
                }
            }
        }
        JsonObject result = context(world, started);
        result.addProperty("mode", "raycast"); result.add("from", coordinates(from[0], from[1], from[2]));
        result.add("to", coordinates(to[0], to[1], to[2])); result.addProperty("status", status);
        result.addProperty("visitedCells", visited); result.addProperty("entityCoveragePartial", entityPartial);
        result.add("stoppedAt", stopped == null ? JsonNull.INSTANCE : stopped);
        if (hit == null) result.add("hit", JsonNull.INSTANCE);
        else {
            JsonObject value = new JsonObject();
            value.addProperty("type", hit.entityHit == null ? "block" : "entity");
            value.add("point", coordinates(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord));
            value.addProperty("distance", start.distanceTo(hit.hitVec));
            if (hit.entityHit != null) value.add("entity", entity(hit.entityHit));
            else {
                value.add("block", coordinates(hit.blockX, hit.blockY, hit.blockZ)); value.addProperty("side", hit.sideHit);
                value.addProperty("registryName", String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(hit.blockX, hit.blockY, hit.blockZ))));
                value.addProperty("metadata", world.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ));
            }
            result.add("hit", value);
        }
        JsonObject coverage = new JsonObject();
        coverage.addProperty("blocks", "Bounded voxel traversal and real block collisionRayTrace; liquids do not stop the ray. Unloaded halo or budget exhaustion is unknown, not a miss.");
        coverage.addProperty("entities", includeEntities ? "Loaded, collidable entity bounding boxes expanded by their collision border; partial coverage is explicit." : "not_requested");
        coverage.addProperty("loading", "Only existing loaded chunks; one-block halo protects ordinary neighboring block-state reads. Arbitrary third-party getters cannot be preempted.");
        result.add("coverage", coverage);
        return result;
    }

    private static JsonObject entity(Entity entity) {
        JsonObject result = new JsonObject();
        result.addProperty("id", entity.getEntityId()); result.addProperty("uuid", entity.getUniqueID().toString());
        result.addProperty("type", JsonData.bounded(EntityList.getEntityString(entity), 256));
        result.addProperty("class", JsonData.bounded(entity.getClass().getName(), 256));
        result.addProperty("name", JsonData.bounded(entity.getCommandSenderName(), 512));
        result.add("position", coordinates(entity.posX, entity.posY, entity.posZ));
        result.add("motion", coordinates(entity.motionX, entity.motionY, entity.motionZ));
        finite(result, "yaw", entity.rotationYaw); finite(result, "pitch", entity.rotationPitch);
        result.addProperty("alive", entity.isEntityAlive()); result.addProperty("dead", entity.isDead);
        if (entity.boundingBox != null) result.add("bounds", box(entity.boundingBox));
        if (entity instanceof EntityLivingBase) {
            finite(result, "health", ((EntityLivingBase) entity).getHealth());
            finite(result, "maxHealth", ((EntityLivingBase) entity).getMaxHealth());
        }
        if (entity instanceof EntityItem) result.add("item", JsonData.item(((EntityItem) entity).getEntityItem()));
        for (String getter : new String[] {"getOwnerId", "getOwnerUUID", "func_152113_b"}) {
            try {
                Object owner = entity.getClass().getMethod(getter).invoke(entity);
                if (owner != null) result.addProperty("owner", JsonData.bounded(String.valueOf(owner), 128));
                break;
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* Optional public owner getter. */ }
        }
        return result;
    }

    private static boolean loadedArea(World world, double minX, double maxX, double minZ, double maxZ) {
        WorldScan.MinecraftAccess access = new WorldScan.MinecraftAccess(world);
        for (int cx = (int) Math.floor(minX) >> 4; cx <= (int) Math.floor(maxX) >> 4; cx++)
            for (int cz = (int) Math.floor(minZ) >> 4; cz <= (int) Math.floor(maxZ) >> 4; cz++)
                if (access.section(cx, 0, cz) == WorldScan.UNKNOWN) return false;
        return true;
    }
    private static int[] center(EntityPlayer player, JsonObject args) {
        boolean explicit = args.has("x") || args.has("y") || args.has("z");
        return explicit ? new int[] {JsonData.integer(args, "x", -30000000, 30000000), JsonData.integer(args, "y", 0, 255), JsonData.integer(args, "z", -30000000, 30000000)}
                : new int[] {(int) Math.floor(player.posX), (int) Math.floor(player.boundingBox.minY), (int) Math.floor(player.posZ)};
    }
    private static double[] vector(JsonObject args, String key) {
        JsonElement input = args.get(key);
        if (input == null || !input.isJsonObject()) throw new BridgeException("invalid_args", key + " must be a coordinate object.");
        JsonObject point = input.getAsJsonObject();
        return new double[] {JsonData.number(point, "x", -30000000, 30000000), JsonData.number(point, "y", -4096, 4096), JsonData.number(point, "z", -30000000, 30000000)};
    }
    private static boolean flag(JsonObject args, String key, boolean fallback) {
        if (!args.has(key)) return fallback;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args", key + " must be boolean.");
        return args.get(key).getAsBoolean();
    }
    private static void reject(JsonObject args, String... keys) {
        for (String key : keys) if (args.has(key)) throw new BridgeException("invalid_args", key + " is not valid for this spatial mode.");
    }
    private static JsonObject context(World world, long started) {
        JsonObject result = new JsonObject(); result.addProperty("source", world.isRemote ? "client" : "integrated_server");
        result.addProperty("dimension", world.provider.dimensionId); result.addProperty("worldTick", world.getTotalWorldTime());
        result.addProperty("nonAtomic", true);
        JsonObject budget = new JsonObject(); budget.addProperty("softMillis", 4); budget.addProperty("cooperative", true);
        budget.addProperty("elapsedNanos", System.nanoTime() - started); result.add("budget", budget);
        JsonObject limits = new JsonObject(); limits.addProperty("candidates", CANDIDATES); limits.addProperty("results", 128);
        limits.addProperty("resultBytes", RESULT_BYTES); result.add("limits", limits);
        return result;
    }
    private static JsonObject coordinates(double x, double y, double z) {
        JsonObject result = new JsonObject(); finite(result, "x", x); finite(result, "y", y); finite(result, "z", z); return result;
    }
    private static void finite(JsonObject result, String key, double value) {
        result.add(key, Double.isFinite(value) ? new JsonPrimitive(value) : JsonNull.INSTANCE);
    }
    private static JsonObject box(AxisAlignedBB box) {
        JsonObject result = new JsonObject(); finite(result, "minX", box.minX); finite(result, "minY", box.minY);
        finite(result, "minZ", box.minZ); finite(result, "maxX", box.maxX); finite(result, "maxY", box.maxY); finite(result, "maxZ", box.maxZ); return result;
    }
}
