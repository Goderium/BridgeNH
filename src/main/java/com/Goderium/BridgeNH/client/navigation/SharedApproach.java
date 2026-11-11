package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

/** Shared normal-reach endpoints. Pure observations reuse the existing native approach guards. */
final class SharedApproach {
    static final int MAX_CANDIDATES = 166;
    static int candidateCount(ApproachPlanner.Request request) {
        return 1+33*("walking".equals(request.movementStrategy) ? 1+NavigationSettings.maxJumpBlocks+NavigationSettings.maxDropBlocks : 5);
    }
    static List<ApproachPlanner.Request> targets(JsonObject args, int dimension) {
        List<ApproachPlanner.Request> result = new ArrayList<ApproachPlanner.Request>();
        if (!args.has("targets")) return result;
        JsonElement value = args.get("targets");
        if (!value.isJsonArray() || value.getAsJsonArray().size() < 1 || value.getAsJsonArray().size() > 8)
            throw new BridgeException("invalid_args", "Shared targets must contain one through eight explicit blocks.");
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new BridgeException("invalid_args", "Each shared target must be an object.");
            JsonObject target = entry.getAsJsonObject();
            ExpandedGuiApi.keys(target, "x", "y", "z", "allowedSides", "targetIdentity", "wrenchFacing", "hitPoint");
            JsonData.integer(target, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            JsonData.integer(target, "y", 0, 255);
            JsonData.integer(target, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            JsonObject child = new JsonParser().parse(args.toString()).getAsJsonObject();
            child.remove("targets"); child.remove("targetIdentity"); child.remove("allowedSides"); child.remove("wrenchFacing");
            child.remove("hitPoint");
            for (Map.Entry<String, JsonElement> field : target.entrySet()) child.add(field.getKey(), field.getValue());
            ApproachPlanner.Request request = new ApproachPlanner.Request(child, dimension);
            for (ApproachPlanner.Request previous : result)
                if (previous.x == request.x && previous.y == request.y && previous.z == request.z)
                    throw new BridgeException("invalid_args", "Shared targets must not repeat a block position.");
            result.add(request);
        }
        ApproachPlanner.Request first = result.get(0);
        if (first.x != JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX)
                || first.y != JsonData.integer(args, "y", 0, 255)
                || first.z != JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX))
            throw new BridgeException("invalid_args", "The primary approach coordinates must match the first shared target.");
        return result;
    }

    static JsonObject midpoint(ApproachPlanner.Frame frame) {
        double x = 0, y = 0, z = 0;
        for (ApproachPlanner.Request target : frame.request.sharedTargets) { x += target.x + .5; y += target.y + .5; z += target.z + .5; }
        int count = frame.request.sharedTargets.size();
        JsonObject point = new JsonObject(); point.addProperty("x", x / count); point.addProperty("y", y / count); point.addProperty("z", z / count);
        return point;
    }

    static ApproachPlanner.Candidate candidate(ApproachPlanner.Frame frame, int index) {
        if (index == 0) return new ApproachPlanner.Candidate(frame.x, frame.feetY, frame.z);
        if (frame.request.currentOnly || index < 0 || index >= frame.request.total()) return null;
        JsonObject middle = midpoint(frame);
        double x = middle.get("x").getAsDouble(), z = middle.get("z").getAsDouble();
        boolean walking = "walking".equals(frame.request.movementStrategy);
        int heights = walking ? 1+NavigationSettings.maxJumpBlocks+NavigationSettings.maxDropBlocks : 5;
        int base = (index-1)/heights, elevation = (index-1)%heights;
        double y = walking ? frame.feetY+(elevation == 0 ? 0 : elevation <= NavigationSettings.maxDropBlocks ? -elevation : elevation-NavigationSettings.maxDropBlocks)
                : height(frame, middle.get("y").getAsDouble(), elevation);
        if (base < 9) {
            double[] offsets = {0, Math.PI/8, -Math.PI/8, Math.PI/4, -Math.PI/4, Math.PI/2, -Math.PI/2, Math.PI, 3*Math.PI/4};
            double angle = Math.atan2(frame.z-z, frame.x-x)+offsets[base];
            double ux = Math.cos(angle), uz = Math.sin(angle), best = -1;
            // Outer edge of the shared native face-reach volume, inset for real arrival tolerance.
            // Candidates approach from the actual travel side; the center is never the default stand.
            double reach = Math.min(frame.reach, frame.request.maxDistance)-.3;
            for (double radius = Math.min(4.5, reach); radius >= .75; radius -= .125) {
                Vec3 eye = Vec3.createVectorHelper(x+ux*radius, y+frame.eyeOffset, z+uz*radius);
                boolean all = true;
                for (ApproachPlanner.Request target : frame.request.sharedTargets) {
                    boolean visibleFace = false;
                    for (int side : target.sides) {
                        ForgeDirection face = ForgeDirection.getOrientation(side);
                        Vec3 hit = NativePlacementAdapter.facePoint(target.x, target.y, target.z, side);
                        double outward = (eye.xCoord-hit.xCoord)*face.offsetX+(eye.yCoord-hit.yCoord)*face.offsetY+(eye.zCoord-hit.zCoord)*face.offsetZ;
                        if (target.expectedPart != null
                                ? NativePlacementAdapter.distanceToBlock(eye, target.x, target.y, target.z)<=reach
                                : outward>.05 && NativePlacementAdapter.distanceToFace(eye, target.x, target.y, target.z, side)<=reach) { visibleFace = true; break; }
                    }
                    if (!visibleFace) { all = false; break; }
                }
                if (all) { best = radius; break; }
            }
            if (best < 0) return null;
            x += ux*best; z += uz*best;
        } else {
            int[][] directions = {{0,-1},{0,1},{-1,0},{1,0},{-1,-1},{1,-1},{-1,1},{1,1}};
            int direction = (base-9)%8, radius = 1+(base-9)/8;
            x += directions[direction][0]*radius; z += directions[direction][1]*radius;
        }
        // This is an endpoint observation, not a route or arrival proof. Walking
        // and flight have bounded native segmented navigation; keep its outer bound
        // here while every actual segment retains its collision/route budgets.
        double travelBound = walking || "ability_flight".equals(frame.request.movementStrategy)
                ? NavigationSegments.MAX_DISTANCE : FlightPathPlanner.MAX_DISTANCE;
        if (Math.sqrt((x - frame.x) * (x - frame.x) + (y - frame.feetY) * (y - frame.feetY) + (z - frame.z) * (z - frame.z)) > travelBound) return null;
        // Avoid nearly vertical upward views when another native-reachable side stand is possible.
        for (ApproachPlanner.Request target : frame.request.sharedTargets)
            if (target.y+.5>y+frame.eyeOffset && Math.hypot(x-Math.max(target.x,Math.min(target.x+1,x)),
                    z-Math.max(target.z,Math.min(target.z+1,z)))<.75+PlayerNavigation.TOLERANCE) return null;
        return new ApproachPlanner.Candidate(x, y, z);
    }

    private static double height(ApproachPlanner.Frame frame, double targetY, int elevation) {
        double[] heights = new double[5];
        int[] offsets = {0, -1, 1, -2, 2};
        for (int i = 0; i < heights.length; i++)
            heights[i] = Math.max(0, Math.min(253, Math.rint(targetY-frame.eyeOffset)+offsets[i]));
        // Preserve the same bounded candidates, but flight should try the least vertical travel
        // first. A lower reachable cavity is not necessarily connected to the current flight region.
        if ("ability_flight".equals(frame.request.movementStrategy)) for (int i = 1; i < heights.length; i++) {
            double value = heights[i]; int j = i;
            while (j > 0 && Math.abs(value-frame.feetY) < Math.abs(heights[j-1]-frame.feetY)) {
                heights[j] = heights[j-1]; j--;
            }
            heights[j] = value;
        }
        return heights[elevation];
    }

    static JsonArray identities(ApproachPlanner.Access access, ApproachPlanner.Request request) {
        JsonArray identities = new JsonArray();
        for (ApproachPlanner.Request target : request.sharedTargets) {
            if (!access.loaded(target.x, target.y, target.z)) throw new BridgeException("chunk_unloaded", "A shared interaction target is not actually loaded.");
            JsonObject identity = access.state(target.x, target.y, target.z).description;
            ApproachPlanner.requireIdentity(target, identity);
            identities.add(identity);
        }
        return identities;
    }

    static void sameIdentities(JsonArray previous, JsonArray current) {
        if (previous == null || current == null || previous.size() != current.size())
            throw new BridgeException("target_unverified", "The shared target set changed during planning.");
        for (int index = 0; index < current.size(); index++) ApproachPlanner.sameIdentity(previous.get(index).getAsJsonObject(), current.get(index).getAsJsonObject());
    }

    static JsonArray serverViews(ApproachPlanner.Access access, ApproachPlanner.RayAccess rays, ApproachPlanner.Frame frame,
            ApproachPlanner.Candidate candidate, ApproachPlanner.Search search, MovingObjectPosition primary, LongSupplier clock, long deadline) {
        Vec3 eye = candidate.eye(frame); JsonArray views = new JsonArray();
        views.add(ApproachPlanner.rayEvidence("integrated_server", frame.request.dimension, search.flight.get("worldTick").getAsLong(), primary, search.sharedIdentities.get(0).getAsJsonObject()));
        for (int index = 1; index < frame.request.sharedTargets.size(); index++) {
            ApproachPlanner.Request target = frame.request.sharedTargets.get(index);
            MovingObjectPosition hit = null;
            for (int side : target.sides) {
                if (clock.getAsLong() >= deadline || rays.rays >= rays.limit) { partial(search, rays); return null; }
                ForgeDirection face = ForgeDirection.getOrientation(side);
                double outward = (eye.xCoord - target.x - .5 - face.offsetX * .5) * face.offsetX
                        + (eye.yCoord - target.y - .5 - face.offsetY * .5) * face.offsetY + (eye.zCoord - target.z - .5 - face.offsetZ * .5) * face.offsetZ;
                Vec3 point = NativePlacementAdapter.facePoint(target.x, target.y, target.z, side);
                if (target.expectedPart == null && (outward <= .001
                        || NativePlacementAdapter.distanceToFace(eye, target.x, target.y, target.z, side) > ApproachPlanner.distanceBound(frame, search))) continue;
                if (!NativePlacementAdapter.loadedRay(access, eye, point, target.y)) { search.unknown++; continue; }
                rays.wrenchFacing = target.wrenchFacing;
                rays.hitPoint = target.hitPoint;
                hit = rays.face(eye, ApproachPlanner.distanceBound(frame, search), target.x, target.y, target.z, side, target.expectedPart);
                if (rays.stopReason != null || clock.getAsLong() >= deadline) { partial(search, rays); return null; }
                if (hit != null) break;
            }
            if (hit == null) return null;
            views.add(ApproachPlanner.rayEvidence("integrated_server", target.dimension, search.flight.get("worldTick").getAsLong(), hit, search.sharedIdentities.get(index).getAsJsonObject()));
        }
        return views;
    }

    static JsonArray clientViews(ApproachPlanner.Access client, ApproachPlanner.RayAccess rays, ApproachPlanner.Frame frame,
            ApproachPlanner.Search search, JsonObject selected, long clientTick, LongSupplier clock, long deadline) {
        sameIdentities(search.sharedIdentities, identities(client, frame.request));
        if (search.sharedServerViews == null || search.sharedServerViews.size() != frame.request.sharedTargets.size())
            throw new BridgeException("target_unverified", "Complete shared server face proofs are unavailable.");
        Vec3 eye = search.selected.eye(frame); JsonArray views = new JsonArray();
        for (int index = 0; index < frame.request.sharedTargets.size(); index++) {
            ApproachPlanner.Request target = frame.request.sharedTargets.get(index);
            JsonObject serverRay = search.sharedServerViews.get(index).getAsJsonObject(); int side = serverRay.get("side").getAsInt();
            if (clock.getAsLong() >= deadline || rays.rays >= rays.limit) { partial(search, rays); return null; }
            if (!NativePlacementAdapter.loadedRay(client, eye, NativePlacementAdapter.facePoint(target.x, target.y, target.z, side), target.y)) return null;
            rays.wrenchFacing = target.wrenchFacing;
            rays.hitPoint = target.hitPoint;
            MovingObjectPosition hit = index == 0 ? null : rays.face(eye, ApproachPlanner.distanceBound(frame, search), target.x, target.y, target.z, side, target.expectedPart, serverRay);
            if (rays.stopReason != null || clock.getAsLong() >= deadline) { partial(search, rays); return null; }
            if (index != 0 && hit == null) return null;
            JsonObject view = new JsonObject(); view.addProperty("targetIndex", index); view.addProperty("side", side);
            if (target.wrenchFacing) view.addProperty("wrenchFacing", true);
            if (target.hitPoint != null) view.add("hitPoint", target.hitPoint);
            view.add("target", search.sharedIdentities.get(index)); view.add("serverRay", serverRay);
            if (target.expectedPart != null) view.add("targetPart", target.expectedPart);
            view.add("clientRay", index == 0 ? selected.get("clientRay") : ApproachPlanner.rayEvidence("client", target.dimension, clientTick, hit, search.sharedIdentities.get(index).getAsJsonObject()));
            JsonObject point = new JsonObject();
            if (index == 0) point = selected.getAsJsonObject("hit");
            else { point.addProperty("x", hit.hitVec.xCoord); point.addProperty("y", hit.hitVec.yCoord); point.addProperty("z", hit.hitVec.zCoord); }
            view.add("hit", point);
            double dx = point.get("x").getAsDouble() - eye.xCoord, dy = point.get("y").getAsDouble() - eye.yCoord, dz = point.get("z").getAsDouble() - eye.zCoord;
            JsonObject aim = new JsonObject(); aim.addProperty("yaw", Math.toDegrees(Math.atan2(dz, dx)) - 90);
            aim.addProperty("pitch", -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))); view.add("aim", aim);
            views.add(view);
        }
        return views;
    }

    static boolean withinReach(ApproachPlanner.Frame frame, ApproachPlanner.Candidate candidate, double reach) {
        Vec3 eye = candidate.eye(frame);
        for (ApproachPlanner.Request target : frame.request.sharedTargets) {
            double dx = eye.xCoord - Math.max(target.x, Math.min(target.x + 1, eye.xCoord));
            double dy = eye.yCoord - Math.max(target.y, Math.min(target.y + 1, eye.yCoord));
            double dz = eye.zCoord - Math.max(target.z, Math.min(target.z + 1, eye.zCoord));
            if (dx * dx + dy * dy + dz * dz > reach * reach) return false;
        }
        return true;
    }

    private static void partial(ApproachPlanner.Search search, ApproachPlanner.RayAccess rays) {
        search.partial = true; search.stopReason = rays.stopReason != null ? rays.stopReason : rays.rays >= rays.limit ? "ray_limit" : "tick_budget";
    }
}
