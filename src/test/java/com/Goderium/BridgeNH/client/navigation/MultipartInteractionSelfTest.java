package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

/** Native AABB fixtures, not evidence of a live AE GUI or packet acknowledgment. */
public final class MultipartInteractionSelfTest {
    static int checks;
    static JsonObject part(int side) {
        JsonObject result = new JsonObject(); result.addProperty("side", side);
        result.addProperty("registryName", "fixture:interface"); result.addProperty("metadata", 24); return result;
    }
    static final class Fixture implements CrosshairArrival.Access {
        final List<List<AxisAlignedBB>> shapes = new ArrayList<List<AxisAlignedBB>>();
        final Map<MovingObjectPosition, JsonObject> selections = new IdentityHashMap<MovingObjectPosition, JsonObject>();
        boolean wrongPart, unloaded, obstructed;
        int rays;
        Fixture() {
            for (int side = 0; side < 6; side++) {
                ForgeDirection face = ForgeDirection.getOrientation(side);
                shapes.add(Collections.singletonList(AxisAlignedBB.getBoundingBox(
                        face.offsetX == 0 ? .125 : face.offsetX < 0 ? 0 : .9375,
                        64 + (face.offsetY == 0 ? .125 : face.offsetY < 0 ? 0 : .9375),
                        face.offsetZ == 0 ? .125 : face.offsetZ < 0 ? 0 : .9375,
                        face.offsetX == 0 ? .875 : face.offsetX < 0 ? .0625 : 1,
                        64 + (face.offsetY == 0 ? .875 : face.offsetY < 0 ? .0625 : 1),
                        face.offsetZ == 0 ? .875 : face.offsetZ < 0 ? .0625 : 1)));
            }
        }
        public boolean loaded(int x, int y, int z) { return !unloaded; }
        public NativePlacementAdapter.State state(int x, int y, int z) {
            JsonObject result = new JsonObject(); result.addProperty("registryName", "fixture:cable_bus"); result.addProperty("metadata", 0);
            JsonArray parts = new JsonArray(); for (int side = 0; side < 6; side++) parts.add(part(side)); result.add("parts", parts);
            return new NativePlacementAdapter.State(false, false, result);
        }
        public List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject expected) { return shapes.get(expected.get("side").getAsInt()); }
        public AxisAlignedBB partBounds(int x, int y, int z, JsonObject expected) {
            AxisAlignedBB union = null;
            for (AxisAlignedBB box : partBoxes(x, y, z, expected)) union = union == null ? box : union.union(box);
            return union;
        }
        public MovingObjectPosition trace(Vec3 eye, Vec3 point) {
            rays++; if (obstructed) return new MovingObjectPosition(1, 64, 0, 4, Vec3.createVectorHelper(1, 64.5, .5));
            MovingObjectPosition nearest = null; int mounted = -1; double distance = Double.POSITIVE_INFINITY;
            for (int side = 0; side < shapes.size(); side++) for (AxisAlignedBB box : shapes.get(side)) {
                MovingObjectPosition hit = box.calculateIntercept(eye, point);
                if (hit != null && eye.distanceTo(hit.hitVec) < distance) {
                    nearest = new MovingObjectPosition(0, 64, 0, hit.sideHit, hit.hitVec); distance = eye.distanceTo(hit.hitVec); mounted = side;
                }
            }
            if (nearest != null) selections.put(nearest, part(wrongPart ? (mounted + 1) % 6 : mounted));
            return nearest;
        }
        public JsonObject selectedPart(MovingObjectPosition hit) { return selections.get(hit); }
        public JsonObject space(int x, int y, int z, double width, double height, long deadline) { throw new AssertionError("Native hit cannot replace body proof."); }
        public boolean entityOccluded(Vec3 eye, Vec3 end, double distance) { return false; }
    }
    public static int run() {
        checks = 0;
        for (int side = 0; side < 6; side++) {
            Fixture fixture = new Fixture(); ForgeDirection normal = ForgeDirection.getOrientation(side);
            Vec3 eye = Vec3.createVectorHelper(.5 + normal.offsetX * 3, 64.5 + normal.offsetY * 3, .5 + normal.offsetZ * 3);
            NativePlacementAdapter.Access selected = NativePlacementAdapter.forPart(fixture, part(side));
            MovingObjectPosition hit = NativePlacementAdapter.visibleFace(selected, eye, 5, 0, 64, 0, side);
            check(hit != null && hit.sideHit == side && AePartAdapter.matches(part(side), fixture.selectedPart(hit)), "All six mounted slabs select their actual part.");
            check(fixture.rays <= 52, "Simple slab geometry remains bounded.");
            fixture.wrongPart = true;
            check(NativePlacementAdapter.visibleFace(selected, eye, 5, 0, 64, 0, side) == null, "Same block and item on another mount never satisfy the declared part.");
            fixture.wrongPart = false; fixture.obstructed = true;
            check(NativePlacementAdapter.visibleFace(selected, eye, 5, 0, 64, 0, side) == null, "A nearer block cannot be replaced by a synthesized part hit.");
            fixture.obstructed = false; fixture.unloaded = true; int before = fixture.rays;
            check(NativePlacementAdapter.visibleFace(selected, eye, 5, 0, 64, 0, side) == null && before == fixture.rays, "Unknown chunk refuses before native probing.");
        }
        Fixture inset = new Fixture();
        for (int side = 0; side < 6; side++) inset.shapes.set(side, Collections.<AxisAlignedBB>emptyList());
        inset.shapes.set(1, Collections.singletonList(AxisAlignedBB.getBoundingBox(.2, 64.75, .2, .8, 64.875, .8)));
        Vec3 eye = Vec3.createVectorHelper(.5, 64.8, -2);
        MovingObjectPosition hit = NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(inset, part(1)), eye, 5, 0, 64, 0, 2);
        check(hit != null && hit.sideHit == 2 && inset.selectedPart(hit).get("side").getAsInt() == 1, "Top mount can be used through its north-facing rim; mount side is not ray side.");
        JsonArray targets = new JsonArray(); JsonObject target = new JsonObject(); target.addProperty("x", 0); target.addProperty("y", 64); target.addProperty("z", 0);
        JsonObject identity = new JsonObject(); identity.addProperty("registryName", "fixture:cable_bus"); identity.add("part", part(1)); target.add("targetIdentity", identity); targets.add(target);
        CrosshairArrival arrival = new CrosshairArrival(targets, "fixture", 0, "walking"); arrival.capture(inset);
        Vec3 end = eye.addVector(0, 0, 5); MovingObjectPosition rendered = inset.trace(eye, end);
        check(arrival.observe(inset, eye, Vec3.createVectorHelper(0, 0, 1), 5, new PlayerNavigation.Point(.5, 63.18, -2), 0, 0, 1, Long.MAX_VALUE, true, rendered), "Actual final crosshair recognizes the intended inset part.");
        inset.wrongPart = true;
        check(!arrival.observe(inset, eye, Vec3.createVectorHelper(0, 0, 1), 5, new PlayerNavigation.Point(.5, 63.18, -2), 0, 0, 2, Long.MAX_VALUE, false, null), "Crosshair arrival rejects another part even at the same XYZ and hit face.");
        inset.wrongPart = false;
        List<AxisAlignedBB> disconnected = new ArrayList<AxisAlignedBB>();
        disconnected.add(AxisAlignedBB.getBoundingBox(.06, 64.4, .2, .08, 64.6, .22));
        disconnected.add(AxisAlignedBB.getBoundingBox(.92, 64.4, .78, .94, 64.6, .8)); inset.shapes.set(1, disconnected);
        hit = NativePlacementAdapter.visibleFace(NativePlacementAdapter.forPart(inset, part(1)), Vec3.createVectorHelper(.07, 64.5, -2), 5, 0, 64, 0, 2);
        check(hit != null && hit.hitVec.xCoord >= .06 && hit.hitVec.xCoord <= .08, "Disconnected narrow boxes use their real primitives rather than the empty union center.");
        inset.wrongPart = true; ApproachPlanner.RayAccess budget = new ApproachPlanner.RayAccess(inset, () -> 0, 1, 0, 7);
        check(budget.face(eye, 5, 0, 64, 0, 2, part(1)) == null && budget.rays == 7 && "ray_limit".equals(budget.stopReason), "Multipart sampling retains the shared hard ray limit.");
        JsonObject block = inset.state(0, 64, 0).description; AePartAdapter.requirePresent(part(1), block);
        JsonObject changed = part(1); changed.addProperty("metadata", 25);
        expect("target_unverified", () -> AePartAdapter.requirePresent(changed, block));
        JsonObject other = new com.google.gson.JsonParser().parse(block.toString()).getAsJsonObject(); other.getAsJsonArray("parts").remove(1);
        expect("target_unverified", () -> ApproachPlanner.sameIdentity(block, other));
        JsonObject bad = part(7); expect("invalid_args", () -> AePartAdapter.declaration(bad));
        check(AePartAdapter.declaration(part(6)).get("side").getAsInt() == 6, "Center cable identity remains distinct from six surface mounts.");
        return checks;
    }
    static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "Existing refusal code remains compatible."); }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
