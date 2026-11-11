package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Opt-in observation guards only. Normal equipment owns the flight abilities. */
public final class SurvivalFlightPosture {
    public static void actor(boolean creative, boolean controllerCreative, boolean allowFlying, boolean flying) {
        if (creative || controllerCreative || !allowFlying || !flying)
            throw new BridgeException("survival_flight_not_confirmed", "Explicit airborne survival requires an actual noncreative actor already flying with native equipment permission.");
    }

    public static void snapshot(JsonObject snapshot, String session, String playerId, String expectedWorld, int dimension) {
        JsonObject player = object(snapshot, "player"), flight = object(player, "serverFlight");
        JsonObject control = object(snapshot, "serverControl"), pose = object(control, "pose");
        actor(flag(player, "creative"), flag(player, "creative"), flag(player, "allowFlying"), flag(player, "flying"));
        actor(flag(flight, "creative"), flag(control, "creative"), flag(flight, "allowFlying"), flag(flight, "flying"));
        if (!flag(flight, "available") || !flag(control, "available") || !flag(control, "allowFlying") || !flag(control, "flying")
                || !flag(pose, "allowFlying") || !flag(pose, "flying") || pose.has("creative") && flag(pose, "creative")
                || !session.equals(text(control, "sessionId")) || !session.equals(text(snapshot, "sessionId"))
                || !playerId.equals(text(control, "playerId")) || !"integrated_server_tick".equals(text(control, "source"))
                || !"integrated_server_tick".equals(text(flight, "source"))
                || JsonData.integer(control, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension
                || JsonData.integer(flight, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension
                || !(expectedWorld.equals(text(control, "worldName")) || expectedWorld.equals(text(control, "worldFolder")))
                || JsonData.number(control, "ageMillis", 0, 1000) > 1000 || JsonData.number(flight, "ageMillis", 0, 1000) > 1000)
            throw new BridgeException("survival_flight_not_confirmed", "Fresh flight must bind the same actual survival session, server actor, world and dimension.");
    }

    public static void space(JsonObject body) {
        if (!"observed".equals(text(body, "status")) || !flag(body, "loaded") || !flag(body, "occupiable")
                || flag(body, "entityCoveragePartial") || flag(body, "liquid") || flag(body, "hazard")
                || body.has("collisionsTruncated") && flag(body, "collisionsTruncated")
                || JsonData.integer(body, "collisionCount", 0, Integer.MAX_VALUE) != 0)
            throw new BridgeException("survival_flight_body_unverified", "The complete actual airborne body must be loaded and collision/entity/liquid/hazard clear.");
    }

    private static JsonObject object(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonObject()) throw new BridgeException("survival_flight_not_confirmed", "Missing airborne survival object: " + key);
        return value.getAsJsonObject();
    }
    private static boolean flag(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new BridgeException("survival_flight_not_confirmed", "Missing airborne survival flag: " + key);
        return value.getAsBoolean();
    }
    private static String text(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new BridgeException("survival_flight_not_confirmed", "Missing airborne survival identity: " + key);
        return value.getAsString();
    }
}
