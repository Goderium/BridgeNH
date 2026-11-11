package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.Set;

/** Scalar protocol/route fixtures, not live flight, placement or GUI evidence. */
final class SurvivalFlightPostureSelfTest {
    private static int checks;
    public static void main(String[] args) throws Exception { System.out.println("ApproachPlannerSelfTest: " + ApproachPlannerSelfTest.run() + " checks passed"); actors(); snapshots(); bodies(); routes(); System.out.println("SurvivalFlightPostureSelfTest: " + checks + " checks passed"); }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    private static JsonObject copy(JsonObject value) { return json(value.toString()); }
    private static void actors() {
        SurvivalFlightPosture.actor(false, false, true, true); checks++;
        for (int mask = 0; mask < 16; mask++) {
            final boolean creative = (mask & 1) != 0, controller = (mask & 2) != 0, allow = (mask & 4) != 0, flying = (mask & 8) != 0;
            if (mask != 12) reject(() -> SurvivalFlightPosture.actor(creative, controller, allow, flying));
        }
    }
    private static JsonObject pose(double x, boolean flying, boolean ground) {
        JsonObject value = json("{\"x\":0.5,\"y\":64,\"feetY\":64,\"eyeY\":65.62,\"z\":0.5,\"yaw\":0,\"pitch\":0,\"health\":20,\"creative\":false,\"allowFlying\":true,\"flying\":true,\"onGround\":false,\"motionX\":0,\"motionY\":0,\"motionZ\":0}");
        value.addProperty("x", x); value.addProperty("flying", flying); value.addProperty("onGround", ground); return value;
    }
    private static JsonObject frame(double x, long sample, boolean flying, boolean ground) {
        JsonObject frame = json("{\"sessionId\":\"session\",\"profile\":\"developer\",\"worldLoaded\":true,\"paused\":false,\"screenOpen\":false,\"screenId\":\"\",\"world\":{\"worldName\":\"fixture\",\"worldFolder\":\"folder\",\"dimension\":0},\"background\":{\"enabled\":true,\"helperInstalled\":true,\"pauseOnLostFocus\":false}}");
        JsonObject player = pose(x, flying, ground);
        JsonObject flight = json("{\"available\":true,\"creative\":false,\"allowFlying\":true,\"flying\":true,\"source\":\"integrated_server_tick\",\"dimension\":0,\"ageMillis\":0}");
        flight.addProperty("flying", flying); player.add("serverFlight", flight); frame.add("player", player);
        JsonObject control = json("{\"available\":true,\"source\":\"integrated_server_tick\",\"playerId\":\"uuid\",\"sessionId\":\"session\",\"worldName\":\"fixture\",\"worldFolder\":\"folder\",\"dimension\":0,\"ageMillis\":0,\"creative\":false,\"allowFlying\":true,\"flying\":true,\"openContainer\":{\"isInventoryContainer\":true,\"windowId\":0},\"clientContainer\":{\"isInventoryContainer\":true,\"windowId\":0}}");
        control.addProperty("sampleId", sample); control.addProperty("flying", flying); control.add("pose", pose(x, flying, ground)); frame.add("serverControl", control); return frame;
    }
    private static void snapshots() {
        JsonObject fresh = frame(.5, 10, true, false);
        SurvivalFlightPosture.snapshot(fresh, "session", "uuid", "fixture", 0); checks++;
        for (String identity : Arrays.asList("sessionId", "playerId", "worldName", "worldFolder", "source")) {
            JsonObject changed = copy(fresh), control = changed.getAsJsonObject("serverControl"); control.addProperty(identity, "different");
            if ("worldName".equals(identity) || "worldFolder".equals(identity)) { control.addProperty("worldName", "different"); control.addProperty("worldFolder", "different"); }
            reject(() -> SurvivalFlightPosture.snapshot(changed, "session", "uuid", "fixture", 0));
        }
        for (String part : Arrays.asList("player", "flight", "control", "pose")) {
            for (String flag : Arrays.asList("creative", "allowFlying", "flying")) {
                JsonObject changed = copy(fresh), object = "player".equals(part) ? changed.getAsJsonObject("player") : "flight".equals(part)
                        ? changed.getAsJsonObject("player").getAsJsonObject("serverFlight") : "control".equals(part)
                        ? changed.getAsJsonObject("serverControl") : changed.getAsJsonObject("serverControl").getAsJsonObject("pose");
                object.addProperty(flag, "creative".equals(flag));
                reject(() -> SurvivalFlightPosture.snapshot(changed, "session", "uuid", "fixture", 0));
            }
        }
        for (String part : Arrays.asList("serverControl", "serverFlight")) {
            JsonObject stale = copy(fresh), value = "serverControl".equals(part) ? stale.getAsJsonObject(part) : stale.getAsJsonObject("player").getAsJsonObject(part);
            value.addProperty("ageMillis", 1001); reject(() -> SurvivalFlightPosture.snapshot(stale, "session", "uuid", "fixture", 0));
        }
    }
    private static JsonObject body() { return json("{\"status\":\"observed\",\"loaded\":true,\"occupiable\":true,\"entityCoveragePartial\":false,\"liquid\":false,\"hazard\":false,\"canStand\":false,\"collisionCount\":0}"); }
    private static void bodies() {
        SurvivalFlightPosture.space(body()); checks++;
        for (String key : Arrays.asList("loaded", "occupiable", "entityCoveragePartial", "liquid", "hazard")) {
            JsonObject bad = body(); bad.addProperty(key, !Arrays.asList("loaded", "occupiable").contains(key)); reject(() -> SurvivalFlightPosture.space(bad));
            JsonObject missing = body(); missing.remove(key); reject(() -> SurvivalFlightPosture.space(missing));
        }
        JsonObject partial = body(); partial.addProperty("status", "partial"); reject(() -> SurvivalFlightPosture.space(partial));
        JsonObject collision = body(); collision.addProperty("collisionCount", 1); reject(() -> SurvivalFlightPosture.space(collision));
        JsonObject grounded = body(); grounded.addProperty("canStand", true); SurvivalFlightPosture.space(grounded); checks++;
        JsonObject truncated = body(); truncated.addProperty("collisionsTruncated", true); reject(() -> SurvivalFlightPosture.space(truncated));
    }
    private static JsonObject request(double x, boolean finish) {
        JsonObject args = json("{\"x\":2.5,\"y\":64,\"z\":0.5,\"dimension\":0,\"expectedWorld\":\"fixture\",\"expectedSessionId\":\"session\",\"strategy\":\"ability_flight\"}");
        args.addProperty("x", x); args.addProperty("finishFlying", finish); return args;
    }
    private static void prepare(PlayerNavigation.State state, FlightPathPlanner.Probe probe) {
        for (int slice = 0; slice < 100 && !state.flightRouteReady; slice++) state.prepareFlight(probe, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS);
        check(state.flightRouteReady, "Complete bounded flight geometry must precede input.");
    }
    private static void routes() {
        Object world = new Object(), actor = new Object();
        PlayerNavigation.State first = new PlayerNavigation.State(new PlayerNavigation.Request(request(2.5, true)), frame(.5, 10, true, false), world, actor, "uuid");
        check(first.route.size() == 1 && first.route.get(0).y == 64, "A genuinely flying same-level move has one direct bounded route target.");
        prepare(first, (sweep, deadline) -> FlightPathPlanner.Check.CLEAR);
        check(first.next(1).isEmpty() && first.jumpTicks == 0, "Awaiting newer actual flight ACK never adds a new jump edge.");
        first.requireContext(frame(.5, 11, true, false)); Set<String> keys = first.next(2);
        check(keys.contains("forward") && !keys.contains("jump") && !keys.contains("sneak"), "Same-level cruise uses native horizontal input without forced takeoff/descent.");
        first.requireContext(frame(2.5, 12, true, false)); check(first.positionReady() && !first.acknowledged(), "Airborne arrival still needs another stable dual sample after input release.");
        first.requireContext(frame(2.5, 13, true, false)); check(first.acknowledged(), "Newer stable actual airborne pose supplies the same final ACK.");
        PlayerNavigation.State second = new PlayerNavigation.State(new PlayerNavigation.Request(request(4.5, true)), frame(2.5, 20, true, false), world, actor, "uuid");
        prepare(second, (sweep, deadline) -> FlightPathPlanner.Check.CLEAR); check(second.route.size() == 1 && second.jumpTicks == 0, "The next explicit hover visit retains the same route strategy and no reacquisition pulse.");
        PlayerNavigation.State landing = new PlayerNavigation.State(new PlayerNavigation.Request(request(2.5, false)), frame(.5, 10, true, false), world, actor, "uuid");
        check(landing.route.size() == 3, "Explicit final grounded arrival retains original cruise/landing route.");
        PlayerNavigation.State fromGround = new PlayerNavigation.State(new PlayerNavigation.Request(request(2.5, true)), frame(.5, 10, false, true), world, actor, "uuid");
        check(fromGround.route.size() == 3, "The direct shortcut requires real prior flight on both actors.");
        JsonObject verticalArgs = request(2.5, true); verticalArgs.addProperty("y", 65);
        PlayerNavigation.State vertical = new PlayerNavigation.State(new PlayerNavigation.Request(verticalArgs), frame(.5, 10, true, false), world, actor, "uuid");
        check(vertical.route.size() == 3, "Height-changing arrival keeps the original conservative route.");
        JsonObject denied = frame(.5, 11, true, false); denied.getAsJsonObject("serverControl").getAsJsonObject("pose").addProperty("allowFlying", false);
        reject(() -> second.requireContext(denied));
        PlayerNavigation.State obstruction = new PlayerNavigation.State(new PlayerNavigation.Request(request(2.5, true)), frame(.5, 10, true, false), world, actor, "uuid");
        reject(() -> obstruction.prepareFlight((sweep, deadline) -> FlightPathPlanner.Check.UNLOADED, .6, 1.8, System.nanoTime() + FlightPathPlanner.WORK_NANOS));
        check(!obstruction.mutation && obstruction.held.isEmpty(), "Unknown loaded geometry refuses before dispatch or strategy fallback.");
    }
    private static void reject(Runnable action) { try { action.run(); throw new AssertionError("Expected explicit refusal"); } catch (BridgeException expected) { checks++; } }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
