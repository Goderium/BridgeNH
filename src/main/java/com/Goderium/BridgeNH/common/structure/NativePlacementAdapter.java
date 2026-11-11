package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.navigation.PlayerNavigation;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.machine.GregTechAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.function.Function;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Six-neighbour planning and one existing native interaction; never sets blocks or invokes item use directly. */
public final class NativePlacementAdapter {
    public interface Access {
        boolean loaded(int x, int y, int z);
        State state(int x, int y, int z);
        default State attachmentState(int x, int y, int z) { return state(x, y, z); }
        MovingObjectPosition trace(Vec3 eye, Vec3 point);
        default AxisAlignedBB selectedBounds(int x, int y, int z) { return null; }
        default List<AxisAlignedBB> selectedBoxes(int x, int y, int z) { return Collections.singletonList(selectedBounds(x, y, z)); }
        default boolean exactPartShape() { return false; }
        default JsonObject selectedPart(MovingObjectPosition hit) { return null; }
        default AxisAlignedBB partBounds(int x, int y, int z, JsonObject part) { throw new BridgeException("unsupported", "Native part geometry is unavailable."); }
        default List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject part) { return Collections.singletonList(partBounds(x, y, z, part)); }
    }
    public static final class State {
        public final boolean air, replaceable;
        public final JsonObject description;
        @com.google.common.annotations.VisibleForTesting
        public State(boolean air, boolean replaceable, JsonObject description) { this.air = air; this.replaceable = replaceable; this.description = description; }
    }
    public static final class Plan {
        public final int x, y, z, slot, dimension;
        final boolean replace;
        public boolean creative = true;
        public final ItemStack item;
        public final JsonObject before, placementBodyProof;
        public final MovingObjectPosition attachment;
        public PlacementAttachment binding;
        public JsonObject part;
        public Plan(int x, int y, int z, int slot, int dimension, boolean replace, ItemStack item, JsonObject before, JsonObject placementBodyProof, MovingObjectPosition attachment) {
            this.x = x; this.y = y; this.z = z; this.slot = slot; this.dimension = dimension;
            this.replace = replace; this.item = item; this.before = before; this.placementBodyProof = placementBodyProof; this.attachment = attachment;
        }
        JsonObject interaction() {
            JsonObject result = new JsonObject(); result.addProperty("action", "use");
            result.addProperty("x", attachment.blockX); result.addProperty("y", attachment.blockY); result.addProperty("z", attachment.blockZ);
            result.addProperty("side", attachment.sideHit); result.addProperty("sneaking", true); result.addProperty("restoreView", false);
            return result;
        }
    }

    public static Plan plan(Minecraft client, JsonObject args, int dimension) {
        requireMatchingMode(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode());
        if (client.theWorld.provider.dimensionId != dimension || client.thePlayer.dimension != dimension)
            throw new BridgeException("stale_world", "The current client and local server dimensions do not agree.");
        int slot = JsonData.integer(args, "hotbarSlot", 0, 8);
        ItemStack item = client.thePlayer.inventory.getStackInSlot(slot);
        if (args.has("expectedPart")) NativePartPlacement.source(item, args.getAsJsonObject("expectedPart"));
        else requireItem(item);
        double reach = client.playerController.getBlockReachDistance();
        Access blocks = access(client.theWorld, dimension);
        // The authoritative BEFORE barrier verifies the actual float view on the
        // server. An ideal double edge hit can become another face after
        // vanilla's trig lookup, so sample achievable rays before selecting it.
        if (!client.thePlayer.capabilities.isCreativeMode || args.has("attachmentBinding") || args.has("expectedPlacementFacing"))
            blocks = nativeView(blocks, reach);
        Plan plan = select(blocks, client.thePlayer.getPosition(1.0F), reach, client.thePlayer.boundingBox, item, dimension, args);
        plan.creative = client.thePlayer.capabilities.isCreativeMode;
        return plan;
    }

    public static Access access(final World world, final int dimension) {
        final WorldScan.MinecraftAccess chunks = new WorldScan.MinecraftAccess(world);
        return new Access() {
            public boolean loaded(int x, int y, int z) {
                return x >= WorldScan.WORLD_MIN && x <= WorldScan.WORLD_MAX && z >= WorldScan.WORLD_MIN && z <= WorldScan.WORLD_MAX
                        && y >= 0 && y <= 255 && chunks.section(x >> 4, y >> 4, z >> 4) != WorldScan.UNKNOWN;
            }
            public State state(int x, int y, int z) {
                Block block = world.getBlock(x, y, z);
                boolean air = block.isAir(world, x, y, z), replaceable = air || block.isReplaceable(world, x, y, z);
                JsonObject data = new JsonObject(); data.addProperty("x", x); data.addProperty("y", y); data.addProperty("z", z);
                data.addProperty("dimension", dimension); data.addProperty("source", world.isRemote ? "client" : "integrated_server");
                data.addProperty("registryName", JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)), 256));
                data.addProperty("metadata", world.getBlockMetadata(x, y, z)); data.addProperty("air", air); data.addProperty("replaceable", replaceable);
                if (ExpandedGuiApi.is(world.getTileEntity(x, y, z), AePartAdapter.HOST)) data.add("parts", AePartAdapter.parts(world, x, y, z));
                return new State(air, replaceable, data);
            }
            public State attachmentState(int x, int y, int z) {
                State result = state(x, y, z); JsonObject gt = GregTechAdapter.identity(world.getTileEntity(x, y, z));
                if (gt != null) result.description.add("gregtech", gt); return result;
            }
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) { return world.rayTraceBlocks(eye, point); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return NativePlacementAdapter.selectedBounds(world.getBlock(x, y, z), world, x, y, z); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return AePartAdapter.selected(world, hit); }
            public AxisAlignedBB partBounds(int x, int y, int z, JsonObject part) { return AePartAdapter.bounds(world, x, y, z, part); }
            public List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject part) { return AePartAdapter.boxes(world, x, y, z, part); }
        };
    }

    public static Access forPart(final Access access, final JsonObject part) {
        if (part == null) return access;
        return new Access() {
            public boolean exactPartShape() { return true; }
            public boolean loaded(int x, int y, int z) { return access.loaded(x, y, z); }
            public State state(int x, int y, int z) { return access.state(x, y, z); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return access.partBounds(x, y, z, part); }
            public List<AxisAlignedBB> selectedBoxes(int x, int y, int z) { return access.partBoxes(x, y, z, part); }
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
                MovingObjectPosition hit = access.trace(eye, point);
                return AePartAdapter.matches(part, access.selectedPart(hit)) ? hit : null;
            }
        };
    }

    /** Navigation needs a ray achievable by the actual float view and vanilla trig lookup. */
    public static Access nativeView(final Access access, final double reach) {
        return new Access() {
            public boolean loaded(int x, int y, int z) { return access.loaded(x, y, z); }
            public State state(int x, int y, int z) { return access.state(x, y, z); }
            public State attachmentState(int x, int y, int z) { return access.attachmentState(x, y, z); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return access.selectedBounds(x, y, z); }
            public List<AxisAlignedBB> selectedBoxes(int x, int y, int z) { return access.selectedBoxes(x, y, z); }
            public boolean exactPartShape() { return access.exactPartShape(); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return access.selectedPart(hit); }
            public AxisAlignedBB partBounds(int x, int y, int z, JsonObject part) { return access.partBounds(x, y, z, part); }
            public List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject part) { return access.partBoxes(x, y, z, part); }
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
                Vec3 end = nativeViewEnd(eye, point, reach);
                if (!loadedRay(access, eye, end, (int)Math.floor(point.yCoord))) return null;
                return access.trace(Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), end);
            }
        };
    }

    @com.google.common.annotations.VisibleForTesting
    public static Vec3 nativeViewEnd(Vec3 eye, Vec3 point, double reach) {
        double x = point.xCoord - eye.xCoord, y = point.yCoord - eye.yCoord, z = point.zCoord - eye.zCoord;
        float yaw = (float)(Math.toDegrees(Math.atan2(z, x)) - 90);
        float pitch = (float)-Math.toDegrees(Math.atan2(y, Math.hypot(x, z)));
        // EntityLivingBase.getLook(1F), including its float multiply order.
        float a = -yaw * .017453292F - (float)Math.PI;
        float b = -pitch * .017453292F, horizontal = -MathHelper.cos(b);
        return eye.addVector((double)(MathHelper.sin(a) * horizontal) * reach,
                (double)MathHelper.sin(b) * reach, (double)(MathHelper.cos(a) * horizontal) * reach);
    }

    /** Public selection geometry is a hint only; every candidate still needs a real world collisionRayTrace hit. */
    static AxisAlignedBB selectedBounds(Block block, World world, int x, int y, int z) {
        AxisAlignedBB before = AxisAlignedBB.getBoundingBox(block.getBlockBoundsMinX(), block.getBlockBoundsMinY(), block.getBlockBoundsMinZ(),
                block.getBlockBoundsMaxX(), block.getBlockBoundsMaxY(), block.getBlockBoundsMaxZ());
        try {
            block.setBlockBoundsBasedOnState(world, x, y, z);
            AxisAlignedBB bounds = block.getSelectedBoundingBoxFromPool(world, x, y, z);
            return bounds == null ? null : AxisAlignedBB.getBoundingBox(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        } catch (RuntimeException unavailable) {
            return null;
        } finally {
            block.setBlockBounds((float) before.minX, (float) before.minY, (float) before.minZ,
                    (float) before.maxX, (float) before.maxY, (float) before.maxZ);
        }
    }

    public static void requireCreative(boolean player, boolean controller) {
        if (!player || !controller) throw new BridgeException("permission_denied", "Native block placement requires current local developer creative mode.");
    }
    public static void requireMatchingMode(boolean player, boolean controller) {
        if (player != controller) throw new BridgeException("permission_denied", "The actual player and native controller game modes disagree.");
    }
    public static void requireItem(ItemStack item) {
        if (item == null || item.stackSize <= 0 || !(item.getItem() instanceof ItemBlock))
            throw new BridgeException("unsupported", "hotbarSlot must contain an actual non-empty ItemBlock stack.");
    }

    /** Snapshot the actual public actor box; unknown or malformed geometry cannot certify a placement. */
    public static AxisAlignedBB actorBody(AxisAlignedBB body) {
        if (body == null || !Double.isFinite(body.minX) || !Double.isFinite(body.minY) || !Double.isFinite(body.minZ)
                || !Double.isFinite(body.maxX) || !Double.isFinite(body.maxY) || !Double.isFinite(body.maxZ)
                || body.maxX - body.minX < .1 || body.maxX - body.minX > 4 || body.maxZ - body.minZ < .1 || body.maxZ - body.minZ > 4
                || body.maxY - body.minY < .1 || body.maxY - body.minY > 8
                || body.minX < WorldScan.WORLD_MIN - 4.0 || body.maxX > WorldScan.WORLD_MAX + 5.0
                || body.minZ < WorldScan.WORLD_MIN - 4.0 || body.maxZ > WorldScan.WORLD_MAX + 5.0 || body.minY < -8 || body.maxY > 264)
            throw new BridgeException("unsupported", "The actual bounded public actor AABB is unavailable; no placement body proof can be inferred.");
        return AxisAlignedBB.getBoundingBox(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ);
    }
    public static AxisAlignedBB targetCube(int x, int y, int z) { return AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0); }
    public static JsonObject placementBodyProof(AxisAlignedBB body, int x, int y, int z) {
        AxisAlignedBB actual = actorBody(body), target = targetCube(x, y, z);
        JsonObject proof = new JsonObject(); proof.addProperty("version", 1); proof.addProperty("source", "actual_public_actor_aabb");
        proof.add("body", PlayerNavigation.bounds(actual)); proof.add("targetCube", PlayerNavigation.bounds(target));
        proof.addProperty("overlapsTarget", actual.intersectsWith(target)); proof.addProperty("wholeTargetCube", true);
        return proof;
    }
    public static void requirePlacementBody(JsonObject proof) {
        if (proof.get("overlapsTarget").getAsBoolean())
            throw new BridgeException("invalid_state", "The actual actor body overlaps the whole prospective placement cube; no native placement was submitted.");
    }

    static Plan select(Access access, Vec3 eye, double reach, AxisAlignedBB body, ItemStack item, int dimension, JsonObject args) {
        if (args.has("partPlacement") && !args.has("expectedPart"))
            throw new BridgeException("invalid_args", "partPlacement requires an exact expectedPart declaration.");
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("x") && !key.equals("y") && !key.equals("z") && !key.equals("hotbarSlot")
                    && !key.equals("expectedWorld") && !key.equals("dimension") && !key.equals("replace") && !key.equals("requireSurvivalFlight") && !key.equals("attachmentBinding") && !key.equals("expectedPart") && !key.equals("partPlacement") && !key.equals("expectedPlacementFacing"))
                throw new BridgeException("invalid_args", "Unexpected placement argument: " + key);
        }
        String expected = JsonData.string(args, "expectedWorld");
        if (expected.isEmpty() || expected.length() > 128) throw new BridgeException("invalid_args", "expectedWorld length must be 1..128.");
        if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension)
            throw new BridgeException("stale_world", "Block placement can target only the current dimension.");
        int x = JsonData.integer(args, "x", -30000000, 30000000), y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000), slot = JsonData.integer(args, "hotbarSlot", 0, 8);
        boolean replace = GuiDataAdapter.booleanArg(args, "replace", false);
        String facing = PlacementFacing.requested(args);
        PlacementFacing.source(facing,item);
        if (facing != null && args.has("expectedPart")) throw new BridgeException("invalid_args", "GT placement-facing binding cannot be combined with AE parts.");
        access = PlacementFacing.filter(access,facing);
        GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false);
        if (!Double.isFinite(reach) || reach <= 0 || reach > 32 || !Double.isFinite(eye.xCoord) || !Double.isFinite(eye.yCoord) || !Double.isFinite(eye.zCoord))
            throw new BridgeException("unsupported", "The bounded normal player reach/view is unavailable.");
        JsonObject bodyProof = placementBodyProof(body, x, y, z);
        requirePlacementBody(bodyProof);
        if (!access.loaded(x, y, z)) throw new BridgeException("chunk_unloaded", "Placement target is not already loaded.");
        State before = access.state(x, y, z);
        JsonObject part = args.has("expectedPart") ? AePartAdapter.declaration(args.getAsJsonObject("expectedPart")) : null;
        if (part != null) {
            NativePartPlacement.source(item, part); NativePartPlacement.vacant(before.description, part);
            if (!args.has("attachmentBinding") || replace) throw new BridgeException("invalid_args", "AE part installation requires an exact attachmentBinding and never replaces existing parts.");
        } else {
            if (!before.air && !replace) throw new BridgeException("invalid_state", "Target is occupied; replace=true is required even for a natively replaceable block.");
            if (!before.air && !before.replaceable) throw new BridgeException("unsupported", "Solid-target replacement requires a separate native digging workflow; no attack was submitted.");
        }
        if (args.has("attachmentBinding")) {
            boolean sameHost = args.has("partPlacement") && ExpandedGuiApi.option(args, "partPlacement", null, "adjacent", "host").equals("host");
            if (sameHost && part == null) throw new BridgeException("invalid_args", "Host installation requires expectedPart.");
            PlacementAttachment binding = new PlacementAttachment(args, x, y, z, dimension, sameHost);
            MovingObjectPosition hit = binding.face(access, eye, reach);
            Plan plan = new Plan(x, y, z, slot, dimension, replace, item, before.description, bodyProof, hit);
            plan.binding = binding; plan.part = part;
            if (part != null && part.get("side").getAsInt() != 6 && part.get("side").getAsInt() != (sameHost ? hit.sideHit : hit.sideHit ^ 1))
                throw new BridgeException("invalid_args", "The declared mounted side must face the exact adjacent attachment.");
            return plan;
        }
        MovingObjectPosition best = null; double bestDistance = Double.POSITIVE_INFINITY;
        boolean support = false, inReach = false;
        for (int side = 0; side < 6; side++) {
            ForgeDirection face = ForgeDirection.getOrientation(side);
            int nx = x - face.offsetX, ny = y - face.offsetY, nz = z - face.offsetZ;
            if (ny < 0 || ny > 255 || nx < -30000000 || nx > 30000000 || nz < -30000000 || nz > 30000000 || !access.loaded(nx, ny, nz)) continue;
            State neighbour = access.state(nx, ny, nz);
            if (neighbour.air || neighbour.replaceable) continue;
            support = true;
            if (distanceToBlock(eye, nx, ny, nz) > reach) continue;
            inReach = true;
            MovingObjectPosition hit = visibleFace(access, eye, reach, nx, ny, nz, side);
            if (hit == null) continue;
            double distance = eye.distanceTo(hit.hitVec);
            if (distance < bestDistance) { bestDistance = distance; best = hit; }
        }
        if (best == null) {
            if (!support) throw new BridgeException("target_unverified", "No loaded non-replaceable attachment neighbour is available.");
            if (!inReach) throw new BridgeException("out_of_reach", "No attachment face is within normal player reach.");
            throw new BridgeException("face_not_visible", "No attachment face has a visible native ray through already loaded chunks.");
        }
        return new Plan(x, y, z, slot, dimension, replace, item, before.description, bodyProof, best);
    }

    /** Require the short ray and a one-block neighbour halo before native shape callbacks can read adjacent chunks. */
    public static boolean loadedRay(Access access, Vec3 eye, Vec3 point, int y) {
        int minX = ((int) Math.floor(Math.min(eye.xCoord, point.xCoord) - 1)) >> 4, maxX = ((int) Math.floor(Math.max(eye.xCoord, point.xCoord) + 1)) >> 4;
        int minZ = ((int) Math.floor(Math.min(eye.zCoord, point.zCoord) - 1)) >> 4, maxZ = ((int) Math.floor(Math.max(eye.zCoord, point.zCoord) + 1)) >> 4;
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) if (!access.loaded(cx << 4, y, cz << 4)) return false;
        return true;
    }

    public static Vec3 facePoint(int x, int y, int z, int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        return Vec3.createVectorHelper(x + .5 + face.offsetX * .499, y + .5 + face.offsetY * .499, z + .5 + face.offsetZ * .499);
    }

    /** Optimistic distance to the whole cube face; only an actual native trace can prove visibility. */
    public static double distanceToFace(Vec3 eye, int x, int y, int z, int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        double px = face.offsetX == 0 ? Math.max(x, Math.min(x+1, eye.xCoord)) : x+(face.offsetX > 0 ? 1 : 0);
        double py = face.offsetY == 0 ? Math.max(y, Math.min(y+1, eye.yCoord)) : y+(face.offsetY > 0 ? 1 : 0);
        double pz = face.offsetZ == 0 ? Math.max(z, Math.min(z+1, eye.zCoord)) : z+(face.offsetZ > 0 ? 1 : 0);
        return eye.distanceTo(Vec3.createVectorHelper(px, py, pz));
    }

    private static Vec3 closestFacePoint(Vec3 eye, AxisAlignedBB bounds, int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        double ix = (bounds.maxX-bounds.minX)*.001, iy = (bounds.maxY-bounds.minY)*.001, iz = (bounds.maxZ-bounds.minZ)*.001;
        Vec3 surface = Vec3.createVectorHelper(
                face.offsetX == 0 ? Math.max(bounds.minX+ix, Math.min(bounds.maxX-ix, eye.xCoord)) : face.offsetX > 0 ? bounds.maxX : bounds.minX,
                face.offsetY == 0 ? Math.max(bounds.minY+iy, Math.min(bounds.maxY-iy, eye.yCoord)) : face.offsetY > 0 ? bounds.maxY : bounds.minY,
                face.offsetZ == 0 ? Math.max(bounds.minZ+iz, Math.min(bounds.maxZ-iz, eye.zCoord)) : face.offsetZ > 0 ? bounds.maxZ : bounds.minZ);
        // Extend along the SAME eye-to-surface line. A normal inset changes the entry face at edges;
        // a tiny endpoint leaves too little segment after vanilla World advances the start to the face.
        // probeFace still clips the full ray to native reach and accepts only a real requested-side hit.
        double normal = Math.abs((surface.xCoord-eye.xCoord)*face.offsetX+(surface.yCoord-eye.yCoord)*face.offsetY+(surface.zCoord-eye.zCoord)*face.offsetZ);
        double scale = 1+.01/Math.max(.001,normal);
        return Vec3.createVectorHelper(eye.xCoord+(surface.xCoord-eye.xCoord)*scale,
                eye.yCoord+(surface.yCoord-eye.yCoord)*scale,eye.zCoord+(surface.zCoord-eye.zCoord)*scale);
    }

    public static MovingObjectPosition visibleFace(Access access, Vec3 eye, double reach, int x, int y, int z, int side) {
        return visibleFace(access, eye, reach, x, y, z, side, null);
    }

    public static MovingObjectPosition primaryFace(Access access, Vec3 eye, double reach, int x, int y, int z, int side) {
        Vec3 aim = facePoint(x, y, z, side);
        if (eye.distanceTo(aim) > reach)
            aim = closestFacePoint(eye, AxisAlignedBB.getBoundingBox(x, y, z, x+1, y+1, z+1), side);
        return probeFace(access, eye, reach, aim, x, y, z, side);
    }

    public static MovingObjectPosition visibleFace(Access access, Vec3 eye, double reach, int x, int y, int z, int side, MovingObjectPosition preferred) {
        if (side < 0 || side > 5 || !Double.isFinite(reach) || reach <= 0 || reach > 32
                || !Double.isFinite(eye.xCoord) || !Double.isFinite(eye.yCoord) || !Double.isFinite(eye.zCoord)
                || distanceToBlock(eye, x, y, z) > reach || !access.loaded(x, y, z)) return null;
        // Vanilla/Waila already identify the pointed face. Reuse only its ray direction, never
        // its cached visibility/reach claim; current geometry must produce a fresh native hit.
        if (preferred != null && preferred.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && preferred.blockX == x && preferred.blockY == y && preferred.blockZ == z && preferred.sideHit == side
                && preferred.hitVec != null && Double.isFinite(preferred.hitVec.xCoord)
                && Double.isFinite(preferred.hitVec.yCoord) && Double.isFinite(preferred.hitVec.zCoord)) {
            double distance = eye.distanceTo(preferred.hitVec);
            if (distance > .00001 && distance <= reach + .00001) {
                double scale = 1 + .01 / distance;
                MovingObjectPosition current = probeFace(access, eye, reach, Vec3.createVectorHelper(
                        eye.xCoord + (preferred.hitVec.xCoord - eye.xCoord) * scale,
                        eye.yCoord + (preferred.hitVec.yCoord - eye.yCoord) * scale,
                        eye.zCoord + (preferred.hitVec.zCoord - eye.zCoord) * scale), x, y, z, side);
                if (current != null) return current;
            }
        }
        if (access.exactPartShape()) return visiblePartFace(access, eye, reach, x, y, z, side);
        MovingObjectPosition hit = primaryFace(access, eye, reach, x, y, z, side);
        if (hit != null) return hit;
        hit = probeFace(access, eye, reach, Vec3.createVectorHelper(x + .5, y + .5, z + .5), x, y, z, side);
        if (hit != null) return hit;
        double[] offsets = {.5, .25, .75, .001, .999};
        ForgeDirection face = ForgeDirection.getOrientation(side);
        // A distant cube endpoint can pass over a visible inset face. Aim just inside the actual public
        // selection bounds instead, after checking the loaded neighbour halo for its shape callbacks.
        List<AxisAlignedBB> boxes = loadedRay(access, eye, facePoint(x, y, z, side), y) ? access.selectedBoxes(x, y, z) : null;
        if (boxes != null && boxes.size() > 64) throw new BridgeException("unsupported", "Selection shape exceeds its bounded geometry scope.");
        if (boxes != null) for (AxisAlignedBB bounds : boxes) if (boundedShape(bounds, x, y, z)) {
            for (double a : offsets) for (double b : offsets) {
                double px = bounds.minX + (bounds.maxX - bounds.minX) * (face.offsetX == 0 ? a : face.offsetX > 0 ? .999 : .001);
                double py = bounds.minY + (bounds.maxY - bounds.minY) * (face.offsetY == 0 ? face.offsetZ == 0 ? a : b : face.offsetY > 0 ? .999 : .001);
                double pz = bounds.minZ + (bounds.maxZ - bounds.minZ) * (face.offsetZ == 0 ? b : face.offsetZ > 0 ? .999 : .001);
                Vec3 shapePoint = Vec3.createVectorHelper(px, py, pz);
                if (a == .5 && b == .5 && eye.distanceTo(shapePoint) > reach)
                    shapePoint = closestFacePoint(eye, bounds, side);
                hit = probeFace(access, eye, reach, shapePoint, x, y, z, side);
                if (hit != null) return hit;
            }
        }
        // Keep the original through-volume samples for disconnected parts whose selected bounds are
        // only a union or unavailable. Multipart sampling is bounded by 64 boxes and the caller's
        // ray/deadline budget; every accepted result is still a real native hit.
        for (double a : offsets) for (double b : offsets) {
            Vec3 through = face.offsetX != 0 ? Vec3.createVectorHelper(x + .5 - face.offsetX * .501, y + a, z + b)
                    : face.offsetY != 0 ? Vec3.createVectorHelper(x + a, y + .5 - face.offsetY * .501, z + b)
                    : Vec3.createVectorHelper(x + a, y + b, z + .5 - face.offsetZ * .501);
            hit = probeFace(access, eye, reach, through, x, y, z, side);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Sample AE's individual public boxes, not the cable host cube or their empty union. */
    private static MovingObjectPosition visiblePartFace(Access access, Vec3 eye, double reach, int x, int y, int z, int side) {
        if (!loadedRay(access, eye, facePoint(x, y, z, side), y)) return null;
        List<AxisAlignedBB> boxes = access.selectedBoxes(x, y, z);
        if (boxes == null || boxes.isEmpty() || boxes.size() > 64)
            throw new BridgeException("unsupported", "Exact part selection geometry is unavailable or exceeds its bound.");
        for (AxisAlignedBB box : boxes) if (!boundedShape(box, x, y, z))
            throw new BridgeException("unsupported", "Exact part selection geometry is outside its finite bound.");
        ForgeDirection face = ForgeDirection.getOrientation(side);
        double[] offsets = {.5, .05, .95};
        // Prefer the face interior before edge-clamped points: float view angles and AE's
        // native ray can otherwise miss a thin part after a double-precision planning hit.
        // Visit every box before fallback samples so a hidden box cannot spend the budget.
        for (int order = 0; order < 10; order++) for (AxisAlignedBB box : boxes) {
            int sample = order == 0 ? 0 : order == 1 ? -1 : order - 1;
            double outward = (eye.xCoord - (face.offsetX > 0 ? box.maxX : box.minX)) * face.offsetX
                    + (eye.yCoord - (face.offsetY > 0 ? box.maxY : box.minY)) * face.offsetY
                    + (eye.zCoord - (face.offsetZ > 0 ? box.maxZ : box.minZ)) * face.offsetZ;
            if (outward <= .00001) continue;
            Vec3 point;
            if (sample < 0) point = closestFacePoint(eye, box, side);
            else {
                double a = offsets[sample / 3], b = offsets[sample % 3];
                double px = box.minX + (box.maxX - box.minX) * (face.offsetX == 0 ? a : face.offsetX > 0 ? 1 : 0);
                double py = box.minY + (box.maxY - box.minY) * (face.offsetY == 0 ? face.offsetZ == 0 ? a : b : face.offsetY > 0 ? 1 : 0);
                double pz = box.minZ + (box.maxZ - box.minZ) * (face.offsetZ == 0 ? b : face.offsetZ > 0 ? 1 : 0);
                double scale = 1 + .01 / outward;
                point = Vec3.createVectorHelper(eye.xCoord + (px - eye.xCoord) * scale,
                        eye.yCoord + (py - eye.yCoord) * scale, eye.zCoord + (pz - eye.zCoord) * scale);
            }
            MovingObjectPosition hit = probeFace(access, eye, reach, point, x, y, z, side);
            if (hit != null) return hit;
        }
        return null;
    }

    public static boolean boundedShape(AxisAlignedBB bounds, int x, int y, int z) {
        return bounds != null && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
                && bounds.minX < bounds.maxX && bounds.minY < bounds.maxY && bounds.minZ < bounds.maxZ
                && bounds.minX >= x - 1.0 && bounds.minY >= y - 1.0 && bounds.minZ >= z - 1.0
                && bounds.maxX <= x + 2.0 && bounds.maxY <= y + 2.0 && bounds.maxZ <= z + 2.0;
    }

    public static double distanceToBlock(Vec3 eye, int x, int y, int z) {
        double dx = Math.max(x - eye.xCoord, Math.max(0, eye.xCoord - (x + 1.0)));
        double dy = Math.max(y - eye.yCoord, Math.max(0, eye.yCoord - (y + 1.0)));
        double dz = Math.max(z - eye.zCoord, Math.max(0, eye.zCoord - (z + 1.0)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static MovingObjectPosition probeFace(Access access, Vec3 eye, double reach, Vec3 point, int x, int y, int z, int side) {
        double distance = eye.distanceTo(point);
        if (distance > reach) {
            double scale = reach / distance;
            point = Vec3.createVectorHelper(eye.xCoord + (point.xCoord - eye.xCoord) * scale,
                    eye.yCoord + (point.yCoord - eye.yCoord) * scale, eye.zCoord + (point.zCoord - eye.zCoord) * scale);
        }
        if (!loadedRay(access, eye, point, y)) return null;
        // Native 1.7.10 tracing advances its start Vec3 in place. Every sample receives its own origin.
        MovingObjectPosition hit = access.trace(Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), point);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.blockX == x && hit.blockY == y && hit.blockZ == z && hit.sideHit == side && hit.hitVec != null
                && Double.isFinite(hit.hitVec.xCoord) && Double.isFinite(hit.hitVec.yCoord) && Double.isFinite(hit.hitVec.zCoord)
                && eye.distanceTo(hit.hitVec) <= reach + .00001 ? hit : null;
    }

    public static JsonObject invoke(Plan plan, InventoryPlayer inventory, Function<JsonObject, JsonObject> nativeUse) {
        if (inventory.getStackInSlot(plan.slot) != plan.item) throw new BridgeException("stale_widget", "The actual hotbar stack changed before placement.");
        if (plan.part != null) NativePartPlacement.source(plan.item, plan.part);
        else requireItem(plan.item);
        int sourceBefore = plan.item.stackSize;
        int previous = inventory.currentItem; inventory.currentItem = plan.slot;
        JsonObject nativeResult = nativeUse.apply(plan.interaction());
        ItemStack after = inventory.getStackInSlot(plan.slot);
        int sourceAfter = after == null ? 0 : after.stackSize;
        JsonObject result = new JsonObject(); result.addProperty("nativeInvoked", true); result.addProperty("submitted", true);
        JsonElement accepted = nativeResult == null ? null : nativeResult.get("accepted");
        boolean known = accepted != null && accepted.isJsonPrimitive() && accepted.getAsJsonPrimitive().isBoolean();
        result.add("accepted", known ? accepted : JsonNull.INSTANCE); result.addProperty("localAcceptanceKnown", known);
        result.addProperty("outcomeUnknown", true); result.addProperty("automaticReplayAllowed", false);
        result.addProperty("serverConfirmed", false); result.addProperty("executionValidated", false);
        result.addProperty("source", "client_native"); result.addProperty("dimension", plan.dimension);
        result.addProperty("playerMode", plan.creative ? "creative" : "survival");
        result.addProperty("sourceCountBefore", sourceBefore); result.addProperty("sourceCountAfter", sourceAfter);
        result.addProperty("expectedSourceConsumption", plan.creative ? 0 : 1);
        result.addProperty("localPredictedSourceConsumption", sourceBefore - sourceAfter);
        result.addProperty("localConsumptionMatchesExpected", sourceBefore - sourceAfter == (plan.creative ? 0 : 1));
        result.addProperty("previousHotbarSlot", previous); result.addProperty("selectedHotbarSlot", inventory.currentItem);
        result.addProperty("replaceRequested", plan.replace); result.addProperty("attackSubmitted", false); result.addProperty("airUseFallback", false);
        result.add("targetBefore", plan.before); result.add("clientPlacementBodyProof", plan.placementBodyProof);
        JsonObject target = new JsonObject(); target.addProperty("x", plan.x); target.addProperty("y", plan.y); target.addProperty("z", plan.z); result.add("target", target);
        JsonObject attachment = new JsonObject(); MovingObjectPosition hit = plan.attachment;
        attachment.addProperty("x", hit.blockX); attachment.addProperty("y", hit.blockY); attachment.addProperty("z", hit.blockZ); attachment.addProperty("side", hit.sideHit);
        attachment.addProperty("hitX", hit.hitVec.xCoord); attachment.addProperty("hitY", hit.hitVec.yCoord); attachment.addProperty("hitZ", hit.hitVec.zCoord); result.add("attachment", attachment);
        if (plan.binding != null) {
            result.add("attachmentBinding", new com.google.gson.JsonParser().parse(plan.binding.declaration.toString()));
            result.addProperty("clientAttachmentBindingVerified", true);
        }
        result.add("endingView", nativeResult == null || !nativeResult.has("endingView") ? JsonNull.INSTANCE : nativeResult.get("endingView")); result.addProperty("viewRestored", false);
        result.addProperty("completion", "One normal native right-click invocation completed locally. accepted is its local return value, not server acceptance. Confirm a later integrated-server target inspection before treating the block as placed.");
        result.addProperty("coverage", "ItemBlock and native subclass semantics; six loaded neighbours, normal reach and exact visible attachment face. Solid replacement and machine-specific placement/execution rules are not evaluated.");
        return result;
    }
}
