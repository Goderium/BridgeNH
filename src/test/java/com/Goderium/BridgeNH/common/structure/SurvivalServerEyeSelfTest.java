package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.function.LongSupplier;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Directed coordinate and failure checkpoint fixtures. Live native placement is verified by the main agent. */
public final class SurvivalServerEyeSelfTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Survival server eye assertions: " + run()); }
    static int run() {
        checks = 0;
        for (double feet : new double[] {4, 0, 255, 20.5}) for (float nativeHeight : new float[] {1.62F, 1.54F, 1.2F}) {
            Vec3 eye = SurvivalConstructionAdapter.serverEye(-628.5, feet, -18.5, nativeHeight);
            check(eye.xCoord == -628.5 && eye.zCoord == -18.5 && Math.abs(eye.yCoord - feet - nativeHeight) < 1E-10, "Actual server posY plus public native eye height is used");
        }
        Vec3 serverEye = SurvivalConstructionAdapter.serverEye(-628.5, 4, -18.5, 1.62F);
        check(Math.abs(serverEye.yCoord - 5.620000004768372) < 1E-10, "Reported MP feet4 reconstructs the actual matching client eye5.62");
        // Native selection-shape tracing uses the same look direction with the correct eye and the old MP feet origin.
        double hitX = -627.5, hitZ = -20.5;
        double dx = hitX - serverEye.xCoord, dy = 4 - serverEye.yCoord, dz = hitZ - serverEye.zCoord;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        AxisAlignedBB floor = AxisAlignedBB.getBoundingBox(-628, 3, -21, -627, 4, -20);
        Vec3 end = Vec3.createVectorHelper(serverEye.xCoord + dx / distance * 4.5, serverEye.yCoord + dy / distance * 4.5, serverEye.zCoord + dz / distance * 4.5);
        MovingObjectPosition nativeHit = floor.calculateIntercept(serverEye, end);
        check(nativeHit != null && nativeHit.sideHit == 1 && Math.abs(nativeHit.hitVec.yCoord - 4) < 1E-10,
                "Correct server eye produces the native top-face floor support hit aimed by the client");
        Vec3 oldFeet = Vec3.createVectorHelper(serverEye.xCoord, 4, serverEye.zCoord);
        Vec3 oldEnd = Vec3.createVectorHelper(oldFeet.xCoord + dx / distance * 4.5, oldFeet.yCoord + dy / distance * 4.5, oldFeet.zCoord + dz / distance * 4.5);
        check(floor.calculateIntercept(oldFeet, oldEnd) == null, "The identical aimed look misses the floor support when old MP feet is used as ray origin");
        for (float height : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY, 9}) expect("unsupported", () -> SurvivalConstructionAdapter.serverEye(0, 4, 0, height));
        expect("unsupported", () -> SurvivalConstructionAdapter.serverEye(Double.NaN, 4, 0, 1.62F));
        LongSupplier clock = () -> 1;
        SurvivalConstructionAdapter.State before = new SurvivalConstructionAdapter.State(new GameQueue.Control(), clock, 7500);
        before.stage = "server_before_place"; before.receipt.addProperty("lastCheckpoint", "server_aim_pending");
        SurvivalConstructionAdapter.Failure failure = new SurvivalConstructionAdapter.Failure(before, new BridgeException("timeout", "Directed before barrier timeout"));
        check("server_before_place".equals(failure.details.get("stage").getAsString()) && "server_aim_pending".equals(failure.details.get("failureCheckpoint").getAsString())
                && !failure.details.get("nativeSubmitted").getAsBoolean() && !failure.details.get("outcome_unknown").getAsBoolean(), "Before barrier failure retains typed stage/checkpoint and known no-native outcome");
        before.receipt.addProperty("beforeBarrierComplete", true); before.receipt.addProperty("lastCheckpoint", "server_target_pending");
        before.stage = "server_after_place"; before.starting(); before.completed();
        JsonObject after = new SurvivalConstructionAdapter.Failure(before, new BridgeException("timeout", "Directed after barrier timeout")).details;
        check("server_after_place".equals(after.get("stage").getAsString()) && "server_target_pending".equals(after.get("failureCheckpoint").getAsString())
                && after.get("nativeSubmitted").getAsBoolean() && after.get("outcome_unknown").getAsBoolean() && after.getAsJsonObject("knownReceipt").get("beforeBarrierComplete").getAsBoolean(),
                "After barrier timeout preserves verified before receipt and unknown native outcome without replay");
        return checks;
    }
    private static void expect(String code, Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException actual) { check(code.equals(actual.code), "Expected " + code + ", got " + actual.code); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
