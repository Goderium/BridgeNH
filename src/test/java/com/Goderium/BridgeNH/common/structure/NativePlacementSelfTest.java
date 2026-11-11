package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.navigation.InteractionHitPoint;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Tests selection against bounded loaded/visibility fixtures and one real hotbar state transition; no world mutation. */
public final class NativePlacementSelfTest {
    private static int checks;
    public static int run() {
        checks = 0; localHitPoints(); wrenchCentres(); jumpPlacement(); placementFacing(); prospectiveBody(); faces(); exactAttachments(); multipartAttachment(); mutableNativeRays(); insetNativeShapes(); preferredNativeHit(); obliqueSmallFaces(); publicSelectionBounds(); rejection(); nativeInvocation(); placementNativeViewEdge(); return checks;
    }
    private static void localHitPoints() {
        GeometryFixture fixture = new GeometryFixture(0,64,0); fixture.support.add(key(0,64,0));
        for (int side=0;side<6;side++) {
            ForgeDirection face=ForgeDirection.getOrientation(side);
            JsonObject point=new JsonObject(); point.addProperty("x",.5+face.offsetX*.5);
            point.addProperty("y",.5+face.offsetY*.5); point.addProperty("z",.5+face.offsetZ*.5);
            Vec3 eye=Vec3.createVectorHelper(.5+face.offsetX*3,64.5+face.offsetY*3,.5+face.offsetZ*3);
            MovingObjectPosition hit=InteractionHitPoint.probe(fixture,eye,4.5,0,64,0,side,point);
            check(hit!=null && NativeGtWrench.centre(hit,0,64,0,side),"All six generic face-centre points preserve the intended GT cover grid cell.");
            check(InteractionHitPoint.probe(fixture,eye,1,0,64,0,side,point)==null,"Exact point never bypasses native reach.");
        }
        JsonObject point=new JsonObject();point.addProperty("x",.5);point.addProperty("y",.5);point.addProperty("z",1);
        check(!InteractionHitPoint.matches(new MovingObjectPosition(0,64,0,3,Vec3.createVectorHelper(.1,64.5,1)),0,64,0,3,point),
            "A same-face edge which would select a different cover side fails the point binding.");
        fixture.support.add(key(0,64,1));
        check(InteractionHitPoint.probe(fixture,Vec3.createVectorHelper(.5,64.5,3),4.5,0,64,0,3,point)==null,
            "Occluding geometry cannot be skipped for a requested local point.");
        ShapeFixture thin=new ShapeFixture(AxisAlignedBB.getBoundingBox(.25,64.25,.25,.75,64.75,.75));
        point.addProperty("z",.75);
        check(InteractionHitPoint.probe(thin,Vec3.createVectorHelper(.5,64.5,3),4.5,0,64,0,3,point)!=null,
            "The same primitive supports actual inset surfaces, not just full cubes.");
    }
    private static void placementNativeViewEdge() {
        GeometryFixture fixture = new GeometryFixture(461,6,112) {
            @Override public AxisAlignedBB selectedBounds(int x,int y,int z) {
                return AxisAlignedBB.getBoundingBox(x,y,z,x+1,y+1,z+1);
            }
        };
        fixture.support.add(key(460,6,112));
        fixture.support.add(key(461,6,111));
        Vec3 eye = Vec3.createVectorHelper(461.4313817266038,5.620000004768372,109.54083850414881);
        MovingObjectPosition ideal = NativePlacementAdapter.visibleFace(fixture,eye,4.5,460,6,112,5);
        check(ideal != null && ideal.sideHit == 5,"Recorded seed pose has an ideal east-face sample.");
        Vec3 end = NativePlacementAdapter.nativeViewEnd(eye,ideal.hitVec,4.5);
        MovingObjectPosition actual = fixture.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),end);
        check(actual != null && actual.sideHit == 0,"Recorded ideal edge sample becomes the bottom face under native float view.");
        MovingObjectPosition selected = NativePlacementAdapter.visibleFace(NativePlacementAdapter.nativeView(fixture,4.5),eye,4.5,460,6,112,5);
        check(selected != null && selected.blockX == 460 && selected.blockY == 6 && selected.blockZ == 112 && selected.sideHit == 5,
                "Existing bounded face sampler skips unachievable float edge rays and finds an actual allowed face.");
        actual = fixture.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),NativePlacementAdapter.nativeViewEnd(eye,selected.hitVec,4.5));
        check(actual != null && actual.blockX == 460 && actual.blockY == 6 && actual.blockZ == 112 && actual.sideHit == 5,
                "Aiming at the returned native hit retains the same required face before a single click.");
        check(eye.xCoord == 461.4313817266038 && eye.zCoord == 109.54083850414881,
                "Placement sampling retains the original immutable eye despite destructive native traces.");
    }
    private static void wrenchCentres() {
        for (int side = 0; side < 6; side++) {
            ForgeDirection direction = ForgeDirection.getOrientation(side);
            Vec3 centre = Vec3.createVectorHelper(.5 + direction.offsetX * .5, 64.5 + direction.offsetY * .5, .5 + direction.offsetZ * .5);
            check(NativeGtWrench.centre(new MovingObjectPosition(0,64,0,side,centre),0,64,0,side),
                    "Each exact face centre belongs to its declared wrench direction.");
            Vec3 edge = Vec3.createVectorHelper(direction.offsetX == 0 ? .1 : centre.xCoord,
                    direction.offsetY == 0 ? 64.1 : centre.yCoord, direction.offsetZ == 0 ? .1 : centre.zCoord);
            check(!NativeGtWrench.centre(new MovingObjectPosition(0,64,0,side,edge),0,64,0,side),
                    "An edge or corner must not rotate a different GT wrench grid direction.");
        }
        check(!NativeGtWrench.centre(null,0,64,0,0), "Missing wrench ray cannot submit a click.");
    }
    private static void multipartAttachment() {
        ShapeFixture shape = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25,64.875,.25,.75,65,.75));
        JsonObject part = new JsonObject(); part.addProperty("registryName","ae:part"); part.addProperty("metadata",220); part.addProperty("side",1);
        JsonObject description = new JsonObject(); description.addProperty("registryName","ae:host"); description.addProperty("metadata",0);
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray(); parts.add(part); description.add("parts",parts);
        AtomicInteger shapeReads = new AtomicInteger();
        NativePlacementAdapter.Access access = new NativePlacementAdapter.Access() {
            public boolean loaded(int x,int y,int z) { return true; }
            public NativePlacementAdapter.State state(int x,int y,int z) { return new NativePlacementAdapter.State(false,false,description); }
            public MovingObjectPosition trace(Vec3 from,Vec3 to) { return shape.trace(from,to); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return hit==null?null:part; }
            public java.util.List<AxisAlignedBB> partBoxes(int x,int y,int z,JsonObject expected) {
                shapeReads.incrementAndGet(); return java.util.Arrays.asList(shape.shape);
            }
        };
        JsonObject binding = new JsonObject(); binding.addProperty("version",1); binding.addProperty("x",0); binding.addProperty("y",64); binding.addProperty("z",0);
        binding.addProperty("dimension",0); binding.addProperty("side",0); binding.addProperty("registryName","ae:host"); binding.addProperty("metadata",0);
        JsonObject args = new JsonObject(); args.add("attachmentBinding",binding);
        Vec3 eye = Vec3.createVectorHelper(1.01,64.62,-1.16);
        MovingObjectPosition hit = new PlacementAttachment(args,0,64,0,0,true).face(access,eye,4.5);
        check(shapeReads.get()>0 && hit.sideHit==0,"Existing host placement uses actual mounted selection boxes, not its full block bounds.");
        check(Math.abs(hit.hitVec.xCoord-.5)<.001 && Math.abs(hit.hitVec.zCoord-.5)<.001,"Thin-part placement prefers interior aim to unstable box edges.");
        double dx=hit.hitVec.xCoord-eye.xCoord,dy=hit.hitVec.yCoord-eye.yCoord,dz=hit.hitVec.zCoord-eye.zCoord;
        float yaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90),pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
        float sy=net.minecraft.util.MathHelper.sin(-yaw*.017453292F-(float)Math.PI),cy=net.minecraft.util.MathHelper.cos(-yaw*.017453292F-(float)Math.PI);
        float cp=-net.minecraft.util.MathHelper.cos(-pitch*.017453292F),sp=net.minecraft.util.MathHelper.sin(-pitch*.017453292F);
        NativePartPlacement.requireRay(hit,shape.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),
            eye.addVector(sy*cp*4.5,sp*4.5,cy*cp*4.5)),eye,4.5);
        checks++;
    }
    private static void jumpPlacement() {
        AxisAlignedBB body=AxisAlignedBB.getBoundingBox(.2,64,.2,.8,65.8,.8);
        JumpPlacement.pose(body,0,64,0,true,false,0,0);
        check(JumpPlacement.envelope(body).maxY==67.3,"Jump headroom retains the whole native body and rise.");
        check(!JumpPlacement.landed(body,0,64,0,true,false),"Grounded start is not elevated arrival.");
        check(JumpPlacement.landed(body.getOffsetBoundingBox(0,1,0),0,64,0,true,false),"Exact elevated supported pose is eligible for independent native support proof.");
        check(!JumpPlacement.landed(body.getOffsetBoundingBox(0,1,0),0,64,0,false,false),"Airborne coordinates never count as landing.");
        expect("invalid_state",()->JumpPlacement.pose(body,0,64,0,true,true,0,0));
        expect("invalid_state",()->JumpPlacement.pose(body,0,64,0,false,false,0,0));
        expect("invalid_state",()->JumpPlacement.pose(body,0,64,0,true,false,.02,0));
        expect("invalid_state",()->JumpPlacement.pose(body.getOffsetBoundingBox(.3,0,0),0,64,0,true,false,0,0));
        JsonObject args=new JsonObject();args.addProperty("jumpUnderfoot",true);args.addProperty("replace",false);
        check(!JumpPlacement.arguments(args).has("jumpUnderfoot"),"The placement option is removed before common native argument handling.");
        args.addProperty("replace",true);expect("invalid_args",()->JumpPlacement.arguments(args));
    }
    private static void placementFacing() {
        ShapeFixture cube = new ShapeFixture(AxisAlignedBB.getBoundingBox(0,64,0,1,65,1));
        Vec3 eye = Vec3.createVectorHelper(.35,65.62,1.35);
        MovingObjectPosition old = NativePlacementAdapter.visibleFace(cube,eye,4.5,0,64,0,1);
        check(old != null && PlacementFacing.matches("SOUTH",eye,old), "Original center ray selects the wrong front for the side seed.");
        cube.traces=0;
        MovingObjectPosition bound = NativePlacementAdapter.visibleFace(PlacementFacing.filter(cube,"WEST"),eye,4.5,0,64,0,1);
        check(bound != null && PlacementFacing.matches("WEST",eye,bound) && bound.sideHit==1 && cube.traces<=27,
                "Native cube-face sampling finds the desired front from the same supported side stance within the existing ray budget.");
        check(NativePlacementAdapter.visibleFace(PlacementFacing.filter(cube,"NORTH"),eye,4.5,0,64,0,1)==null,
                "An impossible orientation produces no native ray rather than changing orientation or accepting the default hit.");
        cube.support.add(key(0,65,1));
        check(NativePlacementAdapter.visibleFace(PlacementFacing.filter(cube,"WEST"),eye,4.5,0,64,0,1)==null,
                "An actual obstruction still rejects a facing-filtered ray.");
        check(PlacementFacing.direction(-90,0).equals("WEST") && PlacementFacing.direction(180,0).equals("SOUTH")
                && PlacementFacing.direction(0,65).equals("UP") && PlacementFacing.direction(0,-65).equals("DOWN"),
                "GT horizontal and vertical quantization boundaries are preserved.");
        expect("placement_facing_changed", () -> PlacementFacing.require("WEST",180,0));
        expect("unsupported", () -> PlacementFacing.source("WEST",null));
        JsonObject args=new JsonObject(); args.addProperty("expectedPlacementFacing","UP");
        expect("invalid_args", () -> PlacementFacing.requested(args));
        check(PlacementFacing.filter(cube,null)==cube, "Omitted facing keeps the original native ray path.");
    }
    private static void preferredNativeHit() {
        ShapeFixture sparse = new ShapeFixture(AxisAlignedBB.getBoundingBox(.2, 64.7, .7, .4, 64.9, .9),
                AxisAlignedBB.getBoundingBox(.2, 64.05, .05, .4, 64.15, .15));
        sparse.eye = Vec3.createVectorHelper(3, 64.8, .8);
        MovingObjectPosition preferred = NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5);
        check(preferred != null && sparse.traces > 2, "The pointed off-centre face otherwise needs shape sampling.");
        sparse.traces = 0; sparse.originalStarts = 0; sparse.boundsReads = 0;
        MovingObjectPosition fresh = NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5, preferred);
        check(fresh != null && fresh != preferred && sparse.traces == 1 && sparse.originalStarts == 1 && sparse.boundsReads == 0,
                "A valid vanilla hit direction obtains a fresh native face in one ray without shape enumeration.");
        MovingObjectPosition stale = new MovingObjectPosition(0, 64, 0, 5, Vec3.createVectorHelper(.4, 64.4, .4));
        sparse.traces = 0;
        check(NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5, stale) != null && sparse.traces > 1,
                "An outdated pointed direction falls back to normal current geometry rather than fabricating a hit.");
        sparse.support.add(key(1, 64, 0)); sparse.traces = 0;
        check(NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5, preferred) == null && sparse.traces <= 53,
                "A new obstruction invalidates even the exact previous face and remains bounded.");
        sparse.support.remove(key(1, 64, 0)); sparse.traces = 0;
        check(NativePlacementAdapter.visibleFace(sparse, sparse.eye, 2, 0, 64, 0, 5, preferred) == null && sparse.traces <= 52,
                "Cached normal reach does not bypass the actual current reach limit.");
        sparse.missing.add(key(0, 64, 0)); sparse.traces = 0;
        check(NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5, preferred) == null && sparse.traces == 0,
                "A preferred hit cannot authorize callbacks into an unloaded target.");
    }
    private static void exactAttachments() {
        for (int side = 0; side < 6; side++) {
            BoundFixture f = new BoundFixture(); ForgeDirection face = ForgeDirection.getOrientation(side);
            f.support.add(key(-face.offsetX, 64 - face.offsetY, -face.offsetZ));
            f.bind(-face.offsetX, 64 - face.offsetY, -face.offsetZ, side);
            NativePlacementAdapter.Plan selected = f.plan();
            check(selected.binding != null && selected.attachment.sideHit == side && f.traces == 1 && f.states == 2,
                    "all six bound native faces read only the destination and exact neighbor");
        }
        BoundFixture closest = new BoundFixture(); closest.eye = Vec3.createVectorHelper(.25, 66, -2);
        closest.body = AxisAlignedBB.getBoundingBox(-.05, 64.38, -2.3, .55, 66.18, -1.7);
        closest.support.add(key(-1, 64, 0)); closest.support.add(key(1, 64, 0));
        check(closest.plan().attachment.blockX == -1, "unbound selection retains the closest external visible neighbor");
        closest.traces = closest.states = 0; closest.bind(1, 64, 0, 4);
        NativePlacementAdapter.Plan owned = closest.plan();
        check(owned.attachment.blockX == 1 && owned.attachment.sideHit == 4 && closest.states == 2 && closest.traces == 1,
                "binding retains the owned face even when an external visible neighbor is closer");
        JsonObject declared = closest.args.getAsJsonObject("attachmentBinding"); declared.addProperty("metadata", 1);
        check(owned.binding.declaration.get("metadata").getAsInt() == 0, "bound declaration is copied before later caller mutation");
        expect("placement_attachment_changed", closest::plan);
        declared.addProperty("metadata", 0); declared.addProperty("side", 5); expect("invalid_args", closest::plan);
        declared.addProperty("side", 4); declared.addProperty("dimension", 1); expect("invalid_args", closest::plan);
        declared.addProperty("dimension", 0); declared.addProperty("version", true); expect("invalid_args", closest::plan);
        declared.addProperty("version", 1); declared.addProperty("extra", 1); expect("invalid_args", closest::plan); declared.remove("extra");
        closest.support.remove(key(1, 64, 0)); expect("placement_attachment_changed", closest::plan);
        closest.support.add(key(1, 64, 0)); closest.missing.add(key(1, 64, 0)); expect("chunk_unloaded", closest::plan);
        closest.missing.clear(); closest.obstructed = true; expect("face_not_visible", closest::plan);
        closest.obstructed = false; closest.wrongFace = true; expect("face_not_visible", closest::plan);
        closest.wrongFace = false; closest.eye = Vec3.createVectorHelper(100, 64.5, .5); expect("out_of_reach", closest::plan);
        closest.eye = Vec3.createVectorHelper(.5, 64.5, .5); closest.body = NativePlacementAdapter.targetCube(0, 64, 0);
        expect("invalid_state", closest::plan);
        BoundFixture gt = new BoundFixture(); gt.support.add(key(1, 64, 0)); gt.bind(1, 64, 0, 4);
        gt.gt = new JsonObject(); gt.gt.addProperty("metaTileId", 604); gt.gt.addProperty("metaClass", "public.FixtureMeta");
        expect("placement_attachment_changed", gt::plan);
        gt.args.getAsJsonObject("attachmentBinding").add("gregtech", new com.google.gson.JsonParser().parse(gt.gt.toString()));
        NativePlacementAdapter.Plan exactGt = gt.plan(); gt.gt.addProperty("metaTileId", 304);
        expect("placement_attachment_changed", () -> exactGt.binding.verify(gt));
        gt.gt.addProperty("metaTileId", 604); gt.gt.addProperty("metaClass", "public.OtherMeta");
        expect("placement_attachment_changed", () -> exactGt.binding.verify(gt));
        gt.gt = null; expect("placement_attachment_changed", () -> exactGt.binding.verify(gt));
        BoundFixture dispatch = new BoundFixture(); dispatch.support.add(key(1, 64, 0)); dispatch.bind(1, 64, 0, 4);
        InventoryPlayer inventory = new InventoryPlayer(null); inventory.setInventorySlotContents(3, dispatch.item);
        AtomicInteger clicks = new AtomicInteger();
        NativePlacementAdapter.Plan bound = dispatch.plan();
        NativePlacementAdapter.invoke(bound, inventory, use -> { clicks.incrementAndGet(); JsonObject result = new JsonObject(); result.addProperty("accepted", true); return result; });
        check(clicks.get() == 1, "one successful bound selection reaches exactly one existing native use");
        dispatch.obstructed = true;
        expect("face_not_visible", () -> NativePlacementAdapter.invoke(dispatch.plan(), inventory, use -> { clicks.incrementAndGet(); return null; }));
        check(clicks.get() == 1, "a refused exact face performs zero later native calls and never reselects or replays");
    }
    private static final class BoundFixture extends GeometryFixture {
        JsonObject gt;
        BoundFixture() { super(0, 64, 0); }
        void bind(int bx, int by, int bz, int side) {
            JsonObject binding = new JsonObject(); binding.addProperty("version", 1); binding.addProperty("dimension", 0);
            binding.addProperty("x", bx); binding.addProperty("y", by); binding.addProperty("z", bz); binding.addProperty("side", side);
            binding.addProperty("registryName", "fixture:solid"); binding.addProperty("metadata", 0); args.add("attachmentBinding", binding);
        }
        @Override public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            MovingObjectPosition hit = super.trace(start, point);
            if (hit == null) return null;
            return new MovingObjectPosition(obstructed ? hit.blockX + 10 : hit.blockX, hit.blockY, hit.blockZ,
                    wrongFace ? hit.sideHit ^ 1 : hit.sideHit, hit.hitVec);
        }
        @Override public NativePlacementAdapter.State attachmentState(int x, int y, int z) {
            NativePlacementAdapter.State result = state(x, y, z); result.description.addProperty("registryName", "fixture:solid"); result.description.addProperty("metadata", 0);
            if (gt != null) result.description.add("gregtech", gt); return result;
        }
    }
    private static void faces() {
        for (int side = 0; side < 6; side++) {
            Fixture f = new Fixture(0, 64, 0); ForgeDirection direction = ForgeDirection.getOrientation(side);
            int nx = -direction.offsetX, ny = 64 - direction.offsetY, nz = -direction.offsetZ;
            f.support.add(key(nx, ny, nz));
            NativePlacementAdapter.Plan plan = f.plan();
            check(plan.attachment.blockX == nx && plan.attachment.blockY == ny && plan.attachment.blockZ == nz && plan.attachment.sideHit == side,
                    "all six attachment faces lead to the exact requested target");
            check(f.traces == 1 && f.states <= 7, "only bounded neighbour reads and exact native visibility rays are used");
        }
        Fixture nearest = new Fixture(0, 64, 0); nearest.eye = Vec3.createVectorHelper(4, 64.5, .5);
        nearest.support.add(key(1, 64, 0)); nearest.support.add(key(0, 63, 0));
        check(nearest.plan().attachment.blockX == 1 && nearest.traces == 2, "nearest visible native face is chosen independently of direction enumeration order");
        Fixture negative = new Fixture(-17, 64, -17); negative.support.add(key(-16, 64, -17));
        check(negative.plan().attachment.blockX == -16, "negative coordinates use the actual neighbouring block without truncating chunk coordinates");
    }
    private static void rejection() {
        expect("permission_denied", () -> NativePlacementAdapter.requireCreative(false, true));
        expect("permission_denied", () -> NativePlacementAdapter.requireCreative(true, false));
        NativePlacementAdapter.requireCreative(true, true);
        expect("unsupported", () -> NativePlacementAdapter.requireItem(null));
        expect("unsupported", () -> NativePlacementAdapter.requireItem(new ItemStack(new Item(), 1, 0)));
        Fixture empty = new Fixture(0, 64, 0); empty.item.stackSize = 0;
        expect("unsupported", () -> NativePlacementAdapter.requireItem(empty.item));
        Fixture unloaded = new Fixture(0, 64, 0); unloaded.missing.add(key(0, 64, 0));
        expect("chunk_unloaded", unloaded::plan); check(unloaded.states == 0 && unloaded.traces == 0, "unloaded target is rejected before block reads or rays");
        Fixture occupied = solid(); occupied.air = false; expect("invalid_state", occupied::plan);
        occupied.args.addProperty("replace", true); expect("unsupported", occupied::plan);
        check(occupied.traces == 0, "solid replacement never reaches a ray or native attack/use");
        occupied.replaceable = true; check(occupied.plan().replace, "explicit replace=true permits only a native replaceable target");
        Fixture noSupport = new Fixture(0, 64, 0); expect("target_unverified", noSupport::plan);
        Fixture distant = solid(); distant.eye = Vec3.createVectorHelper(100, 64, 0); expect("out_of_reach", distant::plan);
        check(distant.traces == 0, "out-of-reach support is rejected before tracing");
        Fixture blocked = solid(); blocked.obstructed = true; expect("face_not_visible", blocked::plan);
        Fixture wrongFace = solid(); wrongFace.wrongFace = true; expect("face_not_visible", wrongFace::plan);
        Fixture unknownRay = new Fixture(16, 64, 0); unknownRay.support.add(key(17, 64, 0)); unknownRay.eye = Vec3.createVectorHelper(15.5, 64.5, .5);
        unknownRay.missing.add(key(0, 64, 0)); expect("face_not_visible", unknownRay::plan);
        check(unknownRay.traces == 0, "unknown chunk anywhere in ray bounding columns is rejected without tracing or loading it");
        Fixture dimension = solid(); dimension.args.addProperty("dimension", 1); expect("stale_world", dimension::plan);
        Fixture extra = solid(); extra.args.addProperty("side", 5); expect("invalid_args", extra::plan);
        Fixture badReplace = solid(); badReplace.args.addProperty("replace", "true"); expect("invalid_args", badReplace::plan);
        Fixture badSlot = solid(); badSlot.args.addProperty("hotbarSlot", 9); expect("invalid_args", badSlot::plan);
        Fixture badExpected = solid(); badExpected.args.addProperty("expectedWorld", ""); expect("invalid_args", badExpected::plan);
        Fixture noWorld = solid(); noWorld.args.remove("expectedWorld"); expect("invalid_args", noWorld::plan);
    }
    private static void mutableNativeRays() {
        GeometryFixture freezer = new GeometryFixture(-44, 2, 125);
        freezer.eye = Vec3.createVectorHelper(-44.5, 3.6200000047683716, 123.5);
        freezer.support.add(key(-44, 3, 125)); // Controller: its bottom is enumerated before the grass/casing faces.
        freezer.support.add(key(-44, 1, 125));
        freezer.support.add(key(-44, 2, 126));
        NativePlacementAdapter.Plan plan = freezer.plan();
        check(freezer.firstHitSide == 2 && plan.attachment.blockX == -44 && plan.attachment.blockY == 1
                && plan.attachment.blockZ == 125 && plan.attachment.sideHit == 1,
                "a refused controller face cannot move later native rays away from the visible grass top");
        check(freezer.traces <= 3 * 27 && freezer.originalStarts == freezer.traces && freezer.states <= 7,
                "every bounded attachment sample starts at the same actual eye despite destructive tracing");
        check(freezer.eye.xCoord == -44.5 && freezer.eye.yCoord == 3.6200000047683716 && freezer.eye.zCoord == 123.5,
                "native trace mutation cannot alter the caller's view origin or later reach comparisons");
        GeometryFixture casing = new GeometryFixture(-44, 2, 125);
        casing.eye = Vec3.createVectorHelper(-44.5, 3.6200000047683716, 123.5);
        casing.support.add(key(-44, 3, 125)); casing.support.add(key(-44, 2, 126));
        NativePlacementAdapter.Plan behind = casing.plan();
        check(casing.firstHitSide == 2 && behind.attachment.blockY == 2 && behind.attachment.blockZ == 126
                && behind.attachment.sideHit == 2 && casing.traces <= 2 * 27 && casing.originalStarts == casing.traces,
                "the visible rear casing remains selectable after an earlier destructive wrong-face trace");
    }
    private static void insetNativeShapes() {
        for (int side = 0; side < 6; side++) {
            ShapeFixture cable = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75));
            ForgeDirection face = ForgeDirection.getOrientation(side);
            cable.eye = Vec3.createVectorHelper(.5 + face.offsetX * 3, 64.5 + face.offsetY * 3, .5 + face.offsetZ * 3);
            MovingObjectPosition hit = NativePlacementAdapter.visibleFace(cable, cable.eye, 5, 0, 64, 0, side);
            check(hit != null && hit.blockX == 0 && hit.blockY == 64 && hit.blockZ == 0 && hit.sideHit == side,
                    "all requested faces of an inset native cable shape are selected by real interception");
            double coordinate = face.offsetX != 0 ? hit.hitVec.xCoord : face.offsetY != 0 ? hit.hitVec.yCoord - 64 : hit.hitVec.zCoord;
            check(Math.abs(coordinate - (face.offsetX + face.offsetY + face.offsetZ > 0 ? .75 : .25)) < .00001
                    && cable.traces <= 27 && cable.originalStarts == cable.traces,
                    "inset face reports actual hit coordinates with bounded rays and immutable eye origin");
        }
        ShapeFixture sparse = new ShapeFixture(AxisAlignedBB.getBoundingBox(.2, 64.7, .7, .4, 64.9, .9),
                AxisAlignedBB.getBoundingBox(.2, 64.05, .05, .4, 64.15, .15));
        sparse.eye = Vec3.createVectorHelper(3, 64.8, .8);
        MovingObjectPosition offCenter = NativePlacementAdapter.visibleFace(sparse, sparse.eye, 5, 0, 64, 0, 5);
        check(offCenter != null && offCenter.sideHit == 5 && Math.abs(offCenter.hitVec.xCoord - .4) < .00001
                && sparse.traces > 2 && sparse.traces <= 27 && sparse.originalStarts == sparse.traces,
                "bounded native samples resolve disconnected off-center parts missed by face and center rays");
        ShapeFixture obstructed = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75));
        obstructed.eye = Vec3.createVectorHelper(3, 64.5, .5); obstructed.support.add(key(1, 64, 0));
        check(NativePlacementAdapter.visibleFace(obstructed, obstructed.eye, 5, 0, 64, 0, 5) == null && obstructed.traces <= 52,
                "native obstruction cannot be bypassed by inset-shape fallback rays");
        ShapeFixture wrongSide = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75));
        wrongSide.eye = Vec3.createVectorHelper(3, 64.5, .5);
        check(NativePlacementAdapter.visibleFace(wrongSide, wrongSide.eye, 5, 0, 64, 0, 4) == null && wrongSide.traces <= 52,
                "fallback never fabricates the requested face from a native hit on its opposite side");
        ShapeFixture near = new ShapeFixture(AxisAlignedBB.getBoundingBox(.8, 64.25, .25, .9, 64.75, .75));
        near.eye = Vec3.createVectorHelper(3, 64.5, .5);
        MovingObjectPosition reachable = NativePlacementAdapter.visibleFace(near, near.eye, 2.11, 0, 64, 0, 5);
        check(reachable != null && near.eye.distanceTo(reachable.hitVec) <= 2.11001,
                "native hit distance permits an inset face at normal reach even when through-volume endpoint is farther");
        ShapeFixture far = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75));
        far.eye = Vec3.createVectorHelper(3, 64.5, .5);
        check(NativePlacementAdapter.visibleFace(far, far.eye, 2.1, 0, 64, 0, 5) == null && far.traces <= 52,
                "closest cube bounds do not substitute for the actual shape hit reach limit");
        ShapeFixture unloaded = new ShapeFixture(AxisAlignedBB.getBoundingBox(.25, 64.25, .25, .75, 64.75, .75));
        unloaded.eye = Vec3.createVectorHelper(3, 64.5, .5); unloaded.missing.add(key(0, 64, 0));
        check(NativePlacementAdapter.visibleFace(unloaded, unloaded.eye, 5, 0, 64, 0, 5) == null && unloaded.traces == 0,
                "unknown shape target remains rejected before any native callback");
    }
    private static void obliqueSmallFaces() {
        ShapeFixture cable = new ShapeFixture(AxisAlignedBB.getBoundingBox(.375, 64.375, .375, .625, 64.625, .625));
        cable.eye = Vec3.createVectorHelper(1.5, 65.62, .5);
        MovingObjectPosition side = NativePlacementAdapter.visibleFace(cable, cable.eye, 5, 0, 64, 0, 5);
        check(side != null && side.sideHit == 5 && Math.abs(side.hitVec.xCoord - .625) < .00001,
                "the observed quarter-block core remains usable on its visible east face from a standing eye above it");
        check(cable.firstHitSide == 1 && cable.traces == 3 && cable.boundsReads == 1 && cable.originalStarts == cable.traces,
                "a top hit on the cube-centre ray is followed by one actual-bound ray without moving the caller eye");
        ShapeFixture top = new ShapeFixture(AxisAlignedBB.getBoundingBox(.375, 64.375, .375, .625, 64.625, .625));
        top.eye = Vec3.createVectorHelper(-1.5, 65.12, 1.5);
        MovingObjectPosition topHit = NativePlacementAdapter.visibleFace(top, top.eye, 5, 0, 64, 0, 1);
        check(topHit != null && topHit.sideHit == 1 && Math.abs(topHit.hitVec.yCoord - 64.625) < .00001 && top.traces <= 3,
                "a visible inset top remains reachable from the oblique finite-height viewpoint that misses far-plane samples");
        for (int requested = 0; requested < 6; requested++) {
            ForgeDirection direction = ForgeDirection.getOrientation(requested);
            for (double skew : new double[] {-.95, .95}) {
                ShapeFixture shape = new ShapeFixture(AxisAlignedBB.getBoundingBox(.47, 64.41, .29, .53, 64.59, .31));
                shape.eye = Vec3.createVectorHelper(.5 + direction.offsetX * 1.5 + (direction.offsetX == 0 ? skew : 0),
                        64.5 + direction.offsetY * 1.5 + (direction.offsetY == 0 ? skew : 0),
                        .3 + direction.offsetZ * 1.5 + (direction.offsetZ == 0 ? skew : 0));
                MovingObjectPosition actual = NativePlacementAdapter.visibleFace(shape, shape.eye, 5, 0, 64, 0, requested);
                check(actual != null && actual.sideHit == requested && shape.eye.distanceTo(actual.hitVec) <= 5.00001
                        && shape.traces <= 52 && shape.originalStarts == shape.traces,
                        "all six thin off-centre native faces stay selectable from both oblique directions");
            }
        }
        ShapeFixture halo = new ShapeFixture(AxisAlignedBB.getBoundingBox(.375, 64.375, .375, .625, 64.625, .625));
        halo.eye = Vec3.createVectorHelper(1.5, 65.62, .5); halo.missing.add(key(-16, 64, 0));
        check(NativePlacementAdapter.visibleFace(halo, halo.eye, 5, 0, 64, 0, 5) == null && halo.traces == 0 && halo.boundsReads == 0,
                "a loaded target with an unknown neighbouring ray column invokes neither selection geometry nor native tracing");
        for (AxisAlignedBB invalid : new AxisAlignedBB[] {
                AxisAlignedBB.getBoundingBox(Double.NaN, 64.375, .375, .625, 64.625, .625),
                AxisAlignedBB.getBoundingBox(-100, 64.375, .375, 100, 64.625, .625),
                AxisAlignedBB.getBoundingBox(.625, 64.375, .375, .375, 64.625, .625)}) {
            ShapeFixture malformed = new ShapeFixture(AxisAlignedBB.getBoundingBox(.375, 64.375, .375, .625, 64.625, .625));
            malformed.eye = Vec3.createVectorHelper(1.5, 65.62, .5); malformed.boundsOverride = invalid;
            check(NativePlacementAdapter.visibleFace(malformed, malformed.eye, 5, 0, 64, 0, 5) == null
                    && malformed.traces <= 27 && malformed.boundsReads == 1,
                    "non-finite, unbounded or inverted selection hints cannot create a reachable face or unbounded work");
        }
        ShapeFixture unseen = new ShapeFixture(AxisAlignedBB.getBoundingBox(.375, 64.375, .375, .625, 64.625, .625));
        unseen.eye = Vec3.createVectorHelper(1.5, 65.62, .5);
        check(NativePlacementAdapter.visibleFace(unseen, unseen.eye, 5, 0, 64, 0, 4) == null && unseen.traces <= 52,
                "a real selected box never authorizes the hidden opposite face");
    }
    private static void publicSelectionBounds() {
        SelectionBlock block = new SelectionBlock();
        block.setBlockBounds(.125F, .25F, .375F, .625F, .75F, .875F);
        AxisAlignedBB hint = NativePlacementAdapter.selectedBounds(block, null, -638, 4, -18);
        check(hint != null && hint != block.hint && hint.minX == -637.625 && hint.maxY == 4.625 && block.stateCalls == 1 && block.selectionCalls == 1,
                "the public state-dependent selection API provides a detached actual small-shape bound");
        block.hint.minX = 0;
        check(hint.minX == -637.625 && block.getBlockBoundsMinX() == .125 && block.getBlockBoundsMinY() == .25
                && block.getBlockBoundsMinZ() == .375 && block.getBlockBoundsMaxX() == .625
                && block.getBlockBoundsMaxY() == .75 && block.getBlockBoundsMaxZ() == .875,
                "mutable mod selection boxes and temporary callback bounds cannot leak into later rendering or tracing");
        block.fail = true;
        check(NativePlacementAdapter.selectedBounds(block, null, -638, 4, -18) == null && block.getBlockBoundsMinX() == .125
                && block.getBlockBoundsMaxZ() == .875 && block.stateCalls == 2 && block.selectionCalls == 2,
                "an unavailable optional selection hint restores singleton bounds and leaves native fallback available");
        SelectionBlock nativeBlock = new SelectionBlock();
        Vec3 eye = Vec3.createVectorHelper(-636.5, 5.62, -17.5);
        MovingObjectPosition nativeHit = nativeBlock.collisionRayTrace(null, -638, 4, -18, eye,
                Vec3.createVectorHelper(-637.37525, 4.5, -17.5));
        check(nativeHit != null && nativeHit.blockX == -638 && nativeHit.blockY == 4 && nativeHit.blockZ == -18
                && nativeHit.sideHit == 5 && Math.abs(nativeHit.hitVec.xCoord + 637.375) < .00001
                && eye.xCoord == -636.5 && eye.yCoord == 5.62 && eye.zCoord == -17.5,
                "the actual public vanilla collisionRayTrace accepts the observed visible small east face without synthesizing a hit");
    }
    private static void prospectiveBody() {
        for (AxisAlignedBB overlap : new AxisAlignedBB[] {
                AxisAlignedBB.getBoundingBox(-.5, 64, .2, .1, 65.8, .8), AxisAlignedBB.getBoundingBox(.9, 64, .2, 1.5, 65.8, .8),
                AxisAlignedBB.getBoundingBox(.2, 62.3, .2, .8, 64.1, .8), AxisAlignedBB.getBoundingBox(.2, 64.9, .2, .8, 66.7, .8),
                AxisAlignedBB.getBoundingBox(.2, 64, -.5, .8, 65.8, .1), AxisAlignedBB.getBoundingBox(.2, 64, .9, .8, 65.8, 1.5),
                AxisAlignedBB.getBoundingBox(.9, 64.9, .9, 1.5, 66.7, 1.5)}) {
            Fixture f = solid(); f.body = overlap; expect("invalid_state", f::plan);
            check(f.traces == 0 && f.states == 0, "any positive-volume whole target overlap rejects before loaded state and native ray probes");
        }
        for (AxisAlignedBB touching : new AxisAlignedBB[] {
                AxisAlignedBB.getBoundingBox(-.6, 64, .2, 0, 65.8, .8), AxisAlignedBB.getBoundingBox(1, 64, .2, 1.6, 65.8, .8),
                AxisAlignedBB.getBoundingBox(.2, 62.2, .2, .8, 64, .8), AxisAlignedBB.getBoundingBox(.2, 65, .2, .8, 66.8, .8),
                AxisAlignedBB.getBoundingBox(.2, 64, -.6, .8, 65.8, 0), AxisAlignedBB.getBoundingBox(.2, 64, 1, .8, 65.8, 1.6)}) {
            Fixture f = solid(); f.body = touching; NativePlacementAdapter.Plan plan = f.plan();
            check(!plan.placementBodyProof.get("overlapsTarget").getAsBoolean() && f.traces > 0,
                    "touching each exact cube boundary preserves normal native attachment selection");
        }
        Fixture observed = new Fixture(-621, 4, -49); observed.body = AxisAlignedBB.getBoundingBox(-620.7665, 4, -48.9065, -620.1665, 5.8, -48.3065);
        expect("invalid_state", observed::plan);
        check(observed.traces == 0, "observed negative-coordinate actor edge is excluded from the full intended placement cube");
        for (AxisAlignedBB invalid : new AxisAlignedBB[] {null,
                AxisAlignedBB.getBoundingBox(Double.NaN, 64, .2, .8, 65.8, .8),
                AxisAlignedBB.getBoundingBox(.8, 64, .2, .2, 65.8, .8), AxisAlignedBB.getBoundingBox(-20, 64, .2, 20, 65.8, .8)}) {
            Fixture f = solid(); f.body = invalid; expect("unsupported", f::plan);
            check(f.traces == 0 && f.states == 0, "unknown actor geometry is unsupported and cannot be a known clear body");
        }
    }
    private static void nativeInvocation() {
        Fixture f = solid(); NativePlacementAdapter.Plan plan = f.plan(); InventoryPlayer inventory = new InventoryPlayer(null);
        inventory.mainInventory[3] = f.item; inventory.currentItem = 7; AtomicInteger calls = new AtomicInteger();
        JsonObject reply = NativePlacementAdapter.invoke(plan, inventory, request -> {
            calls.incrementAndGet();
            check(inventory.currentItem == 3 && request.get("action").getAsString().equals("use")
                    && request.get("x").getAsInt() == 1 && request.get("y").getAsInt() == 64 && request.get("z").getAsInt() == 0
                    && request.get("side").getAsInt() == 4 && request.get("sneaking").getAsBoolean() && !request.get("restoreView").getAsBoolean(),
                    "hotbar selection precedes one explicit native use with the proven attachment face and bound view");
            JsonObject result = new JsonObject(); result.addProperty("accepted", false);
            JsonObject view = new JsonObject(); view.addProperty("yaw", 30); view.addProperty("pitch", 45); result.add("endingView", view); return result;
        });
        check(calls.get() == 1 && inventory.currentItem == 3 && f.item.stackSize == 2, "a rejected native use is never retried or converted to air use");
        check(reply.get("submitted").getAsBoolean() && !reply.get("accepted").getAsBoolean() && !reply.get("serverConfirmed").getAsBoolean()
                && !reply.get("executionValidated").getAsBoolean() && !reply.get("attackSubmitted").getAsBoolean(), "native invocation/submission is separate from local success and authoritative acceptance");
        check(reply.getAsJsonObject("attachment").get("side").getAsInt() == 4 && reply.getAsJsonObject("targetBefore").get("source").getAsString().equals("client")
                && reply.getAsJsonObject("endingView").get("pitch").getAsInt() == 45, "actual attachment, pre-use client observation and ending native view are returned");
        for (Object value : new Object[] {null, "false", 0, new JsonObject()}) {
            calls.set(0);
            JsonObject unknown = NativePlacementAdapter.invoke(plan, inventory, request -> {
                calls.incrementAndGet();
                if (value == null) return null;
                JsonObject nativeReply = new JsonObject();
                if (value instanceof String) nativeReply.addProperty("accepted", (String) value);
                else if (value instanceof Integer) nativeReply.addProperty("accepted", (Integer) value);
                else nativeReply.add("accepted", (JsonObject) value);
                return nativeReply;
            });
            check(calls.get() == 1 && !unknown.get("localAcceptanceKnown").getAsBoolean() && unknown.get("accepted").isJsonNull()
                    && unknown.get("outcomeUnknown").getAsBoolean() && !unknown.get("automaticReplayAllowed").getAsBoolean(),
                    "empty or malformed local native receipt preserves invocation and unknown server effects without coercing accepted=false or replay");
        }
        inventory.mainInventory[3] = new ItemStack(f.item.getItem(), 2, 0);
        expect("stale_widget", () -> NativePlacementAdapter.invoke(plan, inventory, request -> { throw new AssertionError("stale stack must not invoke native use"); }));
        inventory.mainInventory[3] = f.item; IllegalStateException failure = new IllegalStateException("native failure"); calls.set(0);
        try { NativePlacementAdapter.invoke(plan, inventory, request -> { calls.incrementAndGet(); throw failure; }); throw new AssertionError("native exception suppressed"); }
        catch (IllegalStateException actual) { check(actual == failure && calls.get() == 1, "native failures propagate without retrying the placement"); }
    }
    private static Fixture solid() { Fixture result = new Fixture(0, 64, 0); result.support.add(key(1, 64, 0)); return result; }
    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }
    private static class Fixture implements NativePlacementAdapter.Access {
        final int x, y, z; final Set<String> support = new HashSet<String>(), missing = new HashSet<String>();
        final JsonObject args = new JsonObject(); final ItemStack item = new ItemStack(new ItemBlock(new FixtureBlock()), 2, 0);
        AxisAlignedBB body; Vec3 eye; int traces, states; boolean air = true, replaceable, obstructed, wrongFace;
        Fixture(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z; body = AxisAlignedBB.getBoundingBox(x + 2, y, z + 2, x + 2.6, y + 1.8, z + 2.6); eye = Vec3.createVectorHelper(x + .5, y + .5, z + .5);
            args.addProperty("x", x); args.addProperty("y", y); args.addProperty("z", z); args.addProperty("hotbarSlot", 3); args.addProperty("expectedWorld", "fixture");
        }
        NativePlacementAdapter.Plan plan() { return NativePlacementAdapter.select(this, eye, 5, body, item, 0, args); }
        public boolean loaded(int px, int py, int pz) { return !missing.contains(key(px, py, pz)); }
        public NativePlacementAdapter.State state(int px, int py, int pz) {
            states++; boolean target = px == x && py == y && pz == z;
            JsonObject description = new JsonObject(); description.addProperty("source", "client"); description.addProperty("air", target ? air : !support.contains(key(px, py, pz)));
            return new NativePlacementAdapter.State(target ? air : !support.contains(key(px, py, pz)), target && replaceable, description);
        }
        public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
            traces++; int nx = (int) Math.floor(point.xCoord), ny = (int) Math.floor(point.yCoord), nz = (int) Math.floor(point.zCoord);
            int side = nx != x ? nx < x ? 5 : 4 : ny != y ? ny < y ? 1 : 0 : nz < z ? 3 : 2;
            return new MovingObjectPosition(obstructed ? nx + 10 : nx, ny, nz, wrongFace ? side ^ 1 : side, point);
        }
    }
    /** Full-cube collision geometry with the destructive start-vector behavior of the 1.7.10 world ray. */
    private static class GeometryFixture extends Fixture {
        double originalX, originalY, originalZ; int originalStarts, firstHitSide = -1;
        GeometryFixture(int x, int y, int z) { super(x, y, z); }
        @Override public MovingObjectPosition trace(Vec3 start, Vec3 point) {
            if (traces++ == 0) { originalX = start.xCoord; originalY = start.yCoord; originalZ = start.zCoord; }
            if (start.xCoord == originalX && start.yCoord == originalY && start.zCoord == originalZ) originalStarts++;
            MovingObjectPosition nearest = null; double distance = Double.POSITIVE_INFINITY;
            for (String coordinate : support) {
                String[] parts = coordinate.split(":");
                int px = Integer.parseInt(parts[0]), py = Integer.parseInt(parts[1]), pz = Integer.parseInt(parts[2]);
                for (AxisAlignedBB box : boxes(px, py, pz)) {
                    MovingObjectPosition hit = box.calculateIntercept(start, point);
                    if (hit != null && start.distanceTo(hit.hitVec) < distance) {
                        distance = start.distanceTo(hit.hitVec); nearest = new MovingObjectPosition(px, py, pz, hit.sideHit, hit.hitVec);
                    }
                }
            }
            if (nearest != null) {
                if (firstHitSide < 0) firstHitSide = nearest.sideHit;
                start.xCoord = nearest.hitVec.xCoord; start.yCoord = nearest.hitVec.yCoord; start.zCoord = nearest.hitVec.zCoord;
            }
            return nearest;
        }
        AxisAlignedBB[] boxes(int px, int py, int pz) {
            return new AxisAlignedBB[] {AxisAlignedBB.getBoundingBox(px, py, pz, px + 1, py + 1, pz + 1)};
        }
    }
    private static final class ShapeFixture extends GeometryFixture {
        final AxisAlignedBB[] shape; AxisAlignedBB boundsOverride; int boundsReads;
        ShapeFixture(AxisAlignedBB... shape) { super(0, 64, 0); this.shape = shape; support.add(key(0, 64, 0)); }
        @Override AxisAlignedBB[] boxes(int px, int py, int pz) { return px == 0 && py == 64 && pz == 0 ? shape : super.boxes(px, py, pz); }
        @Override public AxisAlignedBB selectedBounds(int px, int py, int pz) {
            boundsReads++;
            if (boundsOverride != null) return boundsOverride;
            AxisAlignedBB union = AxisAlignedBB.getBoundingBox(shape[0].minX, shape[0].minY, shape[0].minZ, shape[0].maxX, shape[0].maxY, shape[0].maxZ);
            for (AxisAlignedBB part : shape) {
                union.minX = Math.min(union.minX, part.minX); union.minY = Math.min(union.minY, part.minY); union.minZ = Math.min(union.minZ, part.minZ);
                union.maxX = Math.max(union.maxX, part.maxX); union.maxY = Math.max(union.maxY, part.maxY); union.maxZ = Math.max(union.maxZ, part.maxZ);
            }
            return union;
        }
    }
    private static final class SelectionBlock extends FixtureBlock {
        AxisAlignedBB hint = AxisAlignedBB.getBoundingBox(-637.625, 4.375, -17.625, -637.375, 4.625, -17.375);
        int stateCalls, selectionCalls; boolean fail;
        @Override public void setBlockBoundsBasedOnState(IBlockAccess world, int x, int y, int z) {
            stateCalls++; setBlockBounds(.375F, .375F, .375F, .625F, .625F, .625F);
        }
        @Override public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
            selectionCalls++; setBlockBounds(0, 0, 0, 1, 1, 1);
            if (fail) throw new IllegalStateException("selection geometry unavailable");
            return hint;
        }
    }
    private static class FixtureBlock extends Block { FixtureBlock() { super(Material.rock); } }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable task) { try { task.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(error.code.equals(code), "error " + code + ", actual " + error.code); } }
}
