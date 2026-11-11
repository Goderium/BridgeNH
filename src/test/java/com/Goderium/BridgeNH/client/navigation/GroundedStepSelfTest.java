package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Set;
import net.minecraft.util.AxisAlignedBB;

public final class GroundedStepSelfTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("GroundedStepSelfTest: " + run() + " checks passed"); }
    public static int run() {
        checks = 0;
        PlayerNavigation.Point start = new PlayerNavigation.Point(2.5, 4, .5), top = new PlayerNavigation.Point(1.5, 5, .5);
        GroundedStep jump = new GroundedStep(start, top, .6, 1.8);
        expect(() -> new GroundedStep(start, new PlayerNavigation.Point(1.5, 6, .5), .6, 1.8));
        expect(() -> new GroundedStep(start, new PlayerNavigation.Point(1.5, 5, 1.5), .6, 1.8));
        expect(() -> new GroundedStep(start, top, 1.2, 1.8));
        JsonObject args = xyz(top); args.addProperty("strategy", "walking_step"); args.addProperty("dimension", 0);
        args.addProperty("expectedWorld", "step-test"); args.addProperty("expectedSessionId", "step-session");
        check(new PlayerNavigation.Request(args).route(start).size() == 1, "Explicit step accepts one +1 cardinal transition");
        args.addProperty("strategy", "walking"); check(new PlayerNavigation.Request(args).route(start).size()==1,"Normal walking may plan a native height transition");
        check(new GroundedStep(new PlayerNavigation.Point(2.5,7,.5), new PlayerNavigation.Point(1.5,4,.5),.6,1.8).dy == -3,
                "Default grounded falling limit accepts three blocks");
        expect(() -> new GroundedStep(new PlayerNavigation.Point(2.5,8,.5), new PlayerNavigation.Point(1.5,4,.5),.6,1.8));
        NavigationSettings.configure(0,1);
        expect(() -> new GroundedStep(start,top,.6,1.8));
        expect(() -> new GroundedStep(new PlayerNavigation.Point(2.5,6,.5),new PlayerNavigation.Point(1.5,4,.5),.6,1.8));
        NavigationSettings.configure(1,3);
        GroundedStep thinDrop = new GroundedStep(new PlayerNavigation.Point(2.5,4.69375,.5), new PlayerNavigation.Point(1.5,4,.5),.6,1.8);
        check(Math.abs(thinDrop.dy+.69375)<.000001 && thinDrop.contact.maxY==4.69375,
                "Fractional native support retains its exact drop height and contact bound");
        check(!thinDrop.next(snapshot(thinDrop.start,true,0)).contains("jump"),"Fractional drop walks off without a jump");
        expect(() -> new GroundedStep(new PlayerNavigation.Point(2.5,4.05,.5),new PlayerNavigation.Point(1.5,4,.5),.6,1.8));
        check(jump.envelopeClear(observation(new JsonArray())), "A complete empty native body corridor is known clear");
        JsonArray contact = new JsonArray(); contact.add(PlayerNavigation.bounds(jump.contact));
        check(jump.envelopeClear(observation(contact)), "Only actual elevated-cell collision may be native wall contact");
        JsonArray foreign = new JsonArray(); JsonObject obstruction = PlayerNavigation.bounds(jump.contact);
        obstruction.addProperty("maxY", 5.1); foreign.add(obstruction);
        check(!jump.envelopeClear(observation(foreign)), "A blocker above the verified elevated support is refused");
        JsonObject partial = observation(contact); partial.addProperty("status", "partial");
        check(!jump.envelopeClear(partial), "Partial corridor is unknown, never clear");
        jump.observe(snapshot(start, true, 0), false, 0);
        Set<String> initial = jump.next(snapshot(start, true, 0));
        check(initial.contains("jump") && initial.contains("forward"), "+1 uses a real normal jump and movement key");
        jump.dispatched(initial, Collections.<String>emptySet(), 0);
        jump.observe(snapshot(new PlayerNavigation.Point(2.4, 4.42, .5), false, 1), true, 1);
        check(jump.clientAir && jump.serverAir && jump.nativeJumpEdges == 1, "Both real sides departed ground after dispatch");
        check(!jump.next(snapshot(new PlayerNavigation.Point(2.4, 4.42, .5), false, 1)).contains("jump"), "Observed native edge is released without double-tap");
        jump.clientEnvelope = jump.serverEnvelope = jump.clientStart = jump.serverStart = true;
        jump.clientLanding = jump.serverLanding = true;
        JsonObject landed = snapshot(top, true, 2); jump.observe(landed, false, 2);
        check(jump.arrival(landed), "Full support plus actual ground-air-ground permits later stable pose ACK");
        jump.serverAir = false; check(!jump.arrival(landed), "Client landing alone is not authoritative arrival"); jump.serverAir = true;
        jump.serverLanding = false; check(!jump.arrival(landed), "Unknown server landing support prevents success");
        expect(() -> jump.dispatched(initial, Collections.<String>emptySet(), 3));
        GroundedStep drop = new GroundedStep(top, start, .6, 1.8);
        drop.observe(snapshot(top, true, 0), false, 0);
        Set<String> falling = drop.next(snapshot(top, true, 0));
        check(falling.contains("forward") && !falling.contains("jump"), "-1 walks off naturally without another jump");
        drop.dispatched(falling, Collections.<String>emptySet(), 0);
        drop.clientEnvelope = drop.serverEnvelope = drop.clientStart = drop.serverStart = true;
        drop.clientLanding = drop.serverLanding = true;
        check(!drop.arrival(snapshot(start, true, 1)), "A fabricated destination without observed air cannot complete a drop");
        JsonObject dropping = snapshot(new PlayerNavigation.Point(2.3, 4.7, .5), false, 1); dropping.getAsJsonObject("player").addProperty("motionY", -.2);
        drop.observe(dropping, false, 1);
        drop.observe(snapshot(start, true, 2), false, 2);
        check(drop.arrival(snapshot(start, true, 2)), "-1 requires actual independent departure and landing");
        check(drop.receipt(true, true, true, 3).get("nativeJumpEdges").getAsInt() == 0, "Drop receipt does not invent jump evidence");
        JsonObject unknown = jump.receipt(true, true, false, 3);
        check(unknown.get("outcomeUnknown").getAsBoolean() && unknown.get("partial").getAsBoolean()
                && !unknown.get("replayAllowed").getAsBoolean(), "Dispatched incomplete step retains partial/unknown and forbids replay");
        descentEnvelope();
        lateralArrivalAndGeometryEvidence();
        postureAndNativeForward();
        return checks;
    }
    private static void lateralArrivalAndGeometryEvidence() {
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) for (int dy : new int[] {-1, 1}) {
            int dx = direction[0], dz = direction[1];
            PlayerNavigation.Point start = new PlayerNavigation.Point(10.5 + dz * .17, 5, 20.5 - dx * .17);
            PlayerNavigation.Point target = new PlayerNavigation.Point(10.5 + dx, 5 + dy, 20.5 + dz);
            GroundedStep step = new GroundedStep(start, target, .6, 1.8);
            step.dispatched = step.clientAir = step.serverAir = true;
            step.clientEnvelope = step.serverEnvelope = step.clientStart = step.serverStart = true;
            step.clientLanding = step.serverLanding = true;
            step.nativeJumpEdges = dy > 0 ? 1 : 0;
            PlayerNavigation.Point landed = new PlayerNavigation.Point(target.x - dx * .07 + dz * .17, target.y,
                    target.z - dz * .07 - dx * .17);
            JsonObject snapshot = snapshot(landed, true, 3);
            check(!step.arrival(snapshot), "Actual landed radial offset must still fail the unchanged arrival guard");
            Set<String> keys = step.next(snapshot);
            check(keys.contains("right") && !keys.contains("jump") && !keys.contains("forward"),
                    "Every cardinal jump/drop corrects residual lateral error after longitudinal steering stops without a second jump");
            snapshot.getAsJsonObject("player").addProperty("motionX", -dz * .15);
            snapshot.getAsJsonObject("player").addProperty("motionZ", dx * .15);
            check(step.next(snapshot).contains("left"), "Fast native lateral correction is braked instead of accelerating past the target");
            PlayerNavigation.Point settled = new PlayerNavigation.Point(target.x - dx * .07 + dz * .03, target.y,
                    target.z - dz * .07 - dx * .03);
            check(step.next(snapshot(settled, true, 4)).isEmpty() && step.arrival(snapshot(settled, true, 4)),
                    "A supported settled radial endpoint releases all keys and satisfies the original independent arrival proofs");
            PlayerNavigation.Point acrossCenter = new PlayerNavigation.Point(target.x - dx * .4 - dz * .06, target.y,
                    target.z - dz * .4 + dx * .06);
            JsonObject crossing=snapshot(acrossCenter,false,5); crossing.getAsJsonObject("player").addProperty("motionY",-.2);
            step.observe(crossing,false,5);
            check(step.phase.equals("transit"),
                    "A proven lateral correction across the cardinal cell centre is not misclassified against the offset takeoff line");
            PlayerNavigation.Point outside = new PlayerNavigation.Point(target.x - dx * .4 - dz * .21, target.y,
                    target.z - dz * .4 + dx * .21);
            JsonObject escaped=snapshot(outside,false,6); escaped.getAsJsonObject("player").addProperty("motionY",-.2);
            expect(()->step.observe(escaped,false,6));
        }
        GroundedStep step = new GroundedStep(new PlayerNavigation.Point(2.5, 4, .5), new PlayerNavigation.Point(1.5, 5, .5), .6, 1.8);
        JsonArray boxes = new JsonArray(); for (int i = 0; i < 8; i++) boxes.add(PlayerNavigation.bounds(step.contact));
        JsonObject blocked = observation(boxes);
        step.rejectGeometry("landing_support", "client", step.envelope, blocked);
        JsonObject evidence = step.receipt(true, true, false, 0).getAsJsonObject("geometryRejection");
        JsonObject actual = evidence.getAsJsonObject("observation");
        check("landing_support".equals(evidence.get("stage").getAsString()) && "client".equals(evidence.get("side").getAsString())
                && actual.getAsJsonArray("collisions").size() == 3 && actual.get("collisionCount").getAsInt() == 8
                && actual.get("collisionsEvidenceTruncated").getAsBoolean(), "Step refusal preserves exact side/stage/body and bounded native geometry without inventing complete evidence");
        check(step.receipt(true, true, false, 0).toString().length() + 418 < PlayerNavigation.MAX_FLIGHT_ERROR_MESSAGE,
                "Bounded step geometry survives the native diagnostic character budget");
    }
    private static void descentEnvelope() {
        PlayerNavigation.Point actual = new PlayerNavigation.Point(-636.5012913883168, 5, -34.364165554111004);
        PlayerNavigation.Point target = new PlayerNavigation.Point(-636.5, 4, -35.5);
        GroundedStep drop = new GroundedStep(actual, target, .6, 1.8);
        AxisAlignedBB nativeBody = AxisAlignedBB.getBoundingBox(actual.x - .3, 5, actual.z - .3, actual.x + .3, 6.8, actual.z + .3);
        AxisAlignedBB rearColumn = AxisAlignedBB.getBoundingBox(-637, 4, -34, -636, 7, -33);
        AxisAlignedBB oldEnvelope = padded(actual, target);
        check(oldEnvelope.intersectsWith(rearColumn) && !nativeBody.intersectsWith(rearColumn),
                "The actual negative-coordinate start is clear while old rear padding includes the upper/controller column");
        check(drop.envelope.maxZ == -34 && !drop.envelope.intersectsWith(rearColumn)
                && GroundedStep.contained(nativeBody, drop.envelope),
                "Downward corridor ends at the real starting support rear face without excluding actual body");
        check(GroundedStep.contained(drop.at(nativeBody, actual, target), drop.envelope),
                "The unchanged actual landing body still fits inside the narrowed full-height corridor");
        check(!drop.envelopeClear(collisions(oldEnvelope, drop.contact, rearColumn))
                && drop.envelopeClear(collisions(drop.envelope, drop.contact, rearColumn)),
                "Complete native collision filtering reproduces old refusal and permits only the verified support after narrowing");
        check(!GroundedStep.contained(nativeBody.copy().offset(0, 0, .07), drop.envelope),
                "A real body moving beyond the rear face is still refused by native body containment");
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            PlayerNavigation.Point start = new PlayerNavigation.Point(10.5 - dx * .135835, 5, 20.5 - dz * .135835);
            PlayerNavigation.Point end = new PlayerNavigation.Point(10.5 + dx, 4, 20.5 + dz);
            GroundedStep cardinal = new GroundedStep(start, end, .6, 1.8);
            AxisAlignedBB body = AxisAlignedBB.getBoundingBox(start.x - .3, 5, start.z - .3, start.x + .3, 6.8, start.z + .3);
            AxisAlignedBB column = AxisAlignedBB.getBoundingBox(10 - dx, 4, 20 - dz, 11 - dx, 7, 21 - dz);
            check(padded(start, end).intersectsWith(column) && !body.intersectsWith(column)
                    && !cardinal.envelope.intersectsWith(column) && GroundedStep.contained(body, cardinal.envelope),
                    "All four cardinal walk-offs remove only unrelated rear padding and retain the whole actual body");
            check(cardinal.envelopeClear(collisions(cardinal.envelope, cardinal.contact, column)),
                    "A complete downward corridor retains only its independently proved elevated support contact");
            AxisAlignedBB ahead = AxisAlignedBB.getBoundingBox(10 + dx, 4.2, 20 + dz, 11 + dx, 5.2, 21 + dz);
            check(!cardinal.envelopeClear(collisions(cardinal.envelope, cardinal.contact, ahead)),
                    "An actual obstruction ahead remains blocked rather than being exempted as padding");
            JsonObject partial = collisions(cardinal.envelope, cardinal.contact); partial.addProperty("status", "partial");
            check(!cardinal.envelopeClear(partial), "Narrowing never authorizes partial geometry");
            JsonObject hazard = collisions(cardinal.envelope, cardinal.contact); hazard.addProperty("hazard", true);
            check(!cardinal.envelopeClear(hazard), "Narrowing never authorizes hazards");
        }
        PlayerNavigation.Point centered = new PlayerNavigation.Point(10.5, 4, 20.5), raised = new PlayerNavigation.Point(11.5, 5, 20.5);
        GroundedStep up = new GroundedStep(centered, raised, .6, 1.8);
        check(up.envelope.minX == 10.02 && up.envelope.maxX == 11.98 && up.envelope.maxY == 7.3,
                "The existing +1 native jump envelope is unchanged");
    }
    private static void postureAndNativeForward() {
        PlayerNavigation.Point start = new PlayerNavigation.Point(-641.5446749432, 5, -29.6218893149);
        PlayerNavigation.Point target = new PlayerNavigation.Point(-641.5, 4, -30.5);
        double width = .6F, height = 1.8F;
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(start.x - width / 2, start.y, start.z - width / 2,
                start.x + width / 2, start.y + height, start.z + width / 2);
        GroundedStep exact = new GroundedStep(start, target, width, height);
        exact.requirePosture("client", false, false, false, false, false, width, height, body);
        check(GroundedStep.contained(body, exact.envelope), "The supplied ordinary vanilla float body remains enclosed by the unchanged v11 corridor");
        String[] names = {"creative", "flying", "sneaking", "sprinting", "jumpBoost", "widthChanged", "heightChanged"};
        for (int i = 0; i < names.length; i++) {
            final int flag = i;
            expect(() -> exact.requirePosture("server", flag == 0, flag == 1, flag == 2, flag == 3, flag == 4,
                    flag == 5 ? Math.nextUp(width) : width, flag == 6 ? Math.nextUp(height) : height, body));
            JsonObject rejected = exact.receipt(true, true, false, 0).getAsJsonObject("postureRejection");
            check("server".equals(rejected.get("side").getAsString()) && rejected.getAsJsonObject("flags").get(names[i]).getAsBoolean(),
                    "Every original posture refusal retains its precise side and actual failing flag");
            check(rejected.getAsJsonObject("expected").get("entityWidth").getAsDouble() == width
                    && rejected.getAsJsonObject("actual").getAsJsonObject("nativeBody").get("minY").getAsDouble() == 5,
                    "Rejection distinguishes unchanged float entity dimensions from observed native AABB geometry");
        }
        GroundedStep simultaneous = new GroundedStep(start, target, width, height);
        expect(() -> simultaneous.requirePosture("client", false, false, true, true, true, width, height, body));
        JsonObject flags = simultaneous.receipt(true, true, false, 0).getAsJsonObject("postureRejection").getAsJsonObject("flags");
        check(flags.get("sneaking").getAsBoolean() && flags.get("sprinting").getAsBoolean() && flags.get("jumpBoost").getAsBoolean(),
                "A compound posture rejection retains every actual cause without relaxing any guard");
        simultaneous.clientPrevious = simultaneous.serverPrevious = body;
        simultaneous.observe(snapshot(start, true, 0), false, 0);
        simultaneous.observe(snapshot(start, true, 1), false, 1);
        simultaneous.observe(snapshot(start, true, 2), false, 2);
        JsonObject bounded = simultaneous.receipt(true, true, false, 0);
        check(bounded.toString().length() + 418 < PlayerNavigation.MAX_FLIGHT_ERROR_MESSAGE
                && bounded.has("postureRejection") && bounded.has("nativeForwardWindow"),
                "Exact posture and forward evidence survives the existing bounded step error with both native bodies retained");
        GroundedStep invalidInput = new GroundedStep(start, target, width, height);
        expect(() -> invalidInput.observeForwardTick(-1, 0));
        expect(() -> invalidInput.observeForwardTick(0, Float.NaN));
        expect(() -> invalidInput.observeForwardTick(0, Float.POSITIVE_INFINITY));
        check(invalidInput.nativeForwardTick == -1 && invalidInput.forwardQuietTicks == 0,
                "Non-finite or invalid native tick input never becomes quiet-window evidence");
        NativeSprintModel old = new NativeSprintModel(); old.tick(0, true); old.tick(1, false); old.tick(2, true);
        check(old.sprinting, "The exact vanilla seven-tick forward double-tap mechanism reproduces unintended sprint without a sprint key");
        old.tick(3, false); check(!old.sprinting, "Release can restore nonsprinting fresh feedback after a genuine runtime refusal");
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) for (int dy : new int[] {-1, 1}) {
            PlayerNavigation.Point origin = new PlayerNavigation.Point(10.5, 5, 20.5);
            PlayerNavigation.Point end = new PlayerNavigation.Point(origin.x + direction[0], origin.y + dy, origin.z + direction[1]);
            GroundedStep step = new GroundedStep(origin, end, width, height);
            Set<String> requested = step.next(snapshot(origin, true, 0));
            NativeSprintModel nativeModel = new NativeSprintModel();
            for (int tick = 0; tick < 7; tick++) {
                check(step.forwardSafeKeys(requested, tick).isEmpty(), "Initial native window defers forward and the +1 jump together, in every cardinal direction");
                nativeModel.tick(tick, false); step.observeForwardTick(tick, 0);
            }
            check(!step.dispatched && !step.receipt(true, true, false, 0).get("outcomeUnknown").getAsBoolean(),
                    "Waiting before the first native edge does not fabricate mutation or an unknown dispatched outcome");
            Set<String> first = step.forwardSafeKeys(requested, 7);
            check(first.contains("forward") && first.contains("jump") == (dy > 0), "Only the original normal native jump or zero-jump walk-off is dispatched after real quiet ticks");
            step.dispatched(first, Collections.<String>emptySet(), 0); nativeModel.tick(7, true); step.observeForwardTick(7, 1);
            check(step.forwardSafeKeys(requested, 8).contains("forward"), "Continuous real forward input is never interrupted by the double-tap prevention window");
            nativeModel.tick(8, false); step.observeForwardTick(8, 0);
            for (int duplicate = 0; duplicate < 20; duplicate++) step.observeForwardTick(8, 0);
            check(step.forwardQuietTicks == 1 && !step.forwardSafeKeys(requested, 9).contains("forward"),
                    "Repeated callbacks and repeated server observations cannot count as completed native ticks");
            for (int tick = 9; tick < 15; tick++) {
                check(!step.forwardSafeKeys(requested, tick).contains("forward"), "Geometry/fresh-proof release cannot create an early second forward onset");
                nativeModel.tick(tick, false); step.observeForwardTick(tick, 0);
            }
            Set<String> second = step.forwardSafeKeys(requested, 15);
            check(second.contains("forward"), "The second real forward onset is permitted exactly eight native ticks after the first");
            nativeModel.tick(15, true); step.observeForwardTick(15, 1);
            check(!nativeModel.sprinting && nativeModel.minimumOnsetGap == 8 && step.minimumForwardOnsetGap == 8,
                    "Two actual native onsets at least eight ticks apart do not invoke vanilla double-tap sprint");
            Set<String> back = new java.util.LinkedHashSet<String>(); back.add("back");
            check(step.forwardSafeKeys(back, 16).equals(back), "The existing ordinary back braking key is unchanged");
            step.observeForwardTick(16, 0); step.observeForwardTick(20, 0);
            check(step.forwardQuietTicks == 1 && !step.forwardSafeKeys(requested, 21).contains("forward"),
                    "Missing native tick observations reset the quiet proof instead of inferring a cleared timer");
            check(!step.forwardSafeKeys(requested, 100).contains("forward"), "Stale native input evidence never authorizes another forward onset");
            JsonObject receipt = step.receipt(true, true, false, 0);
            check(receipt.get("keysReleased").getAsBoolean() && receipt.get("ownerClearConfirmed").getAsBoolean()
                    && receipt.get("outcomeUnknown").getAsBoolean() && !receipt.get("replayAllowed").getAsBoolean()
                    && !receipt.get("groundAirGroundConfirmed").getAsBoolean() && !step.arrival(snapshot(end, true, 1)),
                    "Input-window diagnostics never substitute release, timeout cleanup, actual air/landing proofs or final arrival ACK");
        }
    }
    /** The relevant audited vanilla EntityPlayerSP.onLivingUpdate branch, with ordinary ground/food posture. */
    private static final class NativeSprintModel {
        int timer, minimumOnsetGap = Integer.MAX_VALUE;
        long previousOnset = -1;
        boolean forward, sprinting;
        void tick(long tick, boolean next) {
            if (timer > 0) timer--;
            boolean before = forward; forward = next;
            if (!before && next) {
                if (previousOnset >= 0) minimumOnsetGap = Math.min(minimumOnsetGap, (int) (tick - previousOnset));
                previousOnset = tick;
                if (!sprinting) { if (timer <= 0) timer = 7; else sprinting = true; }
            }
            if (sprinting && !next) sprinting = false;
        }
    }
    private static AxisAlignedBB padded(PlayerNavigation.Point start, PlayerNavigation.Point end) {
        return AxisAlignedBB.getBoundingBox(Math.min(start.x, end.x) - .3 - GroundedStep.MARGIN,
                end.y + .00001, Math.min(start.z, end.z) - .3 - GroundedStep.MARGIN,
                Math.max(start.x, end.x) + .3 + GroundedStep.MARGIN, start.y + 1.8,
                Math.max(start.z, end.z) + .3 + GroundedStep.MARGIN);
    }
    private static JsonObject collisions(AxisAlignedBB body, AxisAlignedBB... candidates) {
        JsonArray boxes = new JsonArray();
        for (AxisAlignedBB candidate : candidates) if (candidate.intersectsWith(body)) boxes.add(PlayerNavigation.bounds(candidate));
        return observation(boxes);
    }
    private static JsonObject xyz(PlayerNavigation.Point point) {
        JsonObject result = new JsonObject(); result.addProperty("x", point.x); result.addProperty("y", point.y); result.addProperty("z", point.z); return result;
    }
    private static JsonObject snapshot(PlayerNavigation.Point point, boolean ground, long sample) {
        JsonObject pose = xyz(point); pose.addProperty("feetY", point.y); pose.addProperty("flying", false); pose.addProperty("onGround", ground);
        pose.addProperty("motionX", 0); pose.addProperty("motionY", ground ? -.0784 : .25); pose.addProperty("motionZ", 0);
        JsonObject control = new JsonObject(); control.addProperty("sampleId", sample); control.add("pose", pose);
        JsonObject result = new JsonObject(); result.add("player", pose); result.add("serverControl", control); return result;
    }
    private static JsonObject observation(JsonArray boxes) {
        JsonObject result = new JsonObject(); result.addProperty("status", "observed"); result.addProperty("loaded", true);
        result.addProperty("entityCoveragePartial", false); result.addProperty("liquid", false); result.addProperty("hazard", false);
        result.addProperty("collisionsTruncated", false); result.add("blockingEntities", new JsonArray()); result.add("collisions", boxes);
        result.addProperty("collisionCount", boxes.size()); return result;
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void expect(Runnable action) { checks++; try { action.run(); } catch (BridgeException expected) { return; } throw new AssertionError("Expected bounded native refusal"); }
}
