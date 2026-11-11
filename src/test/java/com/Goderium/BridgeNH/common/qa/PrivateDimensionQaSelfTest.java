package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Contract guards only; this test never starts a Minecraft instance. */
public final class PrivateDimensionQaSelfTest {
    private static int checks;

    public static int run() {
        checks = 0;
        PrivateDimensionQaAdapter.requireWorld(180, 180, PrivateDimensionQaAdapter.PROVIDER, true);
        check(true, "exact loaded PersonalSpace world is accepted");
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.requireWorld(0, 0, PrivateDimensionQaAdapter.PROVIDER, true));
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.requireWorld(180, 181, PrivateDimensionQaAdapter.PROVIDER, true));
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.requireWorld(180, 180, PrivateDimensionQaAdapter.PROVIDER, false));
        expect("unsupported", () -> PrivateDimensionQaAdapter.requireWorld(180, 180, "foreign.WorldProvider", true));
        JsonObject request = new JsonObject();
        request.addProperty("section", "fixture_plan"); request.addProperty("query", "tstqa:example");
        check("fixturePlan".equals(PrivateDimensionQaAdapter.route("qa.catalog", request))
                && "tstqa:example".equals(request.get("selector").getAsString()), "fixture plan forwards the exact queried identity");
        request = new JsonObject(); request.addProperty("command", "run");
        JsonArray arguments = new JsonArray(); arguments.add(new JsonPrimitive("tstqa:example")); request.add("args", arguments);
        check("run".equals(PrivateDimensionQaAdapter.route("qa.command", request))
                && request.get("exact").getAsBoolean() && "tstqa:example".equals(request.get("selector").getAsString()),
                "command run cannot silently broaden its singleton selection");
        JsonObject broad = new JsonObject(); broad.addProperty("command", "runall");
        expect("unsupported", () -> PrivateDimensionQaAdapter.route("qa.command", broad));
        JsonObject empty = new JsonObject(); empty.addProperty("command", "run");
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.command", empty));
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.cancel", new JsonObject()));
        JsonObject stop = new JsonObject(); stop.addProperty("runId", "owned-run");
        check("stop".equals(PrivateDimensionQaAdapter.route("qa.cancel", stop))
                && "owned-run".equals(stop.get("runId").getAsString()), "private cancel forwards one explicit owned ordinary-stop ID");
        JsonObject forget = new JsonObject(); forget.addProperty("command", "forget");
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.command", forget));
        JsonArray forgotten = new JsonArray(); forgotten.add(new JsonPrimitive("983d4171-8cca-4bab-b19d-5d2dd88f9775"));
        forget.add("args", forgotten);
        check("forget".equals(PrivateDimensionQaAdapter.route("qa.command", forget))
                && "983d4171-8cca-4bab-b19d-5d2dd88f9775".equals(forget.get("runId").getAsString()),
                "forget forwards one canonical explicit UUID without clearing world fixtures");
        forgotten.add(new JsonPrimitive("a55c7c3a-88d5-416a-9a05-ed53d00090eb"));
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.command", forget));
        forgotten = new JsonArray(); forgotten.add(new JsonPrimitive("latest")); forget.add("args", forgotten);
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.command", forget));
        forgotten = new JsonArray(); forgotten.add(new JsonPrimitive("1-1-1-1-1")); forget.add("args", forgotten);
        expect("invalid_args", () -> PrivateDimensionQaAdapter.route("qa.command", forget));
        JsonObject good = good();
        good.addProperty("owned", false); good.addProperty("nativeRunOwned", false);
        JsonObject response = PrivateDimensionQaAdapter.response(good.toString(), "session", 180, PrivateDimensionQaAdapter.PROVIDER);
        check(!response.get("owned").getAsBoolean() && !response.get("nativeRunOwned").getAsBoolean(), "adapter receipt never upgrades native ownership");
        JsonObject wrong = good(); wrong.addProperty("dimension", 0); reject(wrong);
        wrong = good(); wrong.addProperty("dimension", "180"); reject(wrong);
        wrong = good(); wrong.addProperty("dimension", 180.5); reject(wrong);
        wrong = good(); wrong.addProperty("providerClass", "foreign.WorldProvider"); reject(wrong);
        wrong = good(); wrong.addProperty("sessionId", "old-session"); reject(wrong);
        wrong = good(); wrong.addProperty("source", "horizonqa_public_api"); reject(wrong);
        wrong = good(); wrong.addProperty("ok", "true"); reject(wrong);
        wrong = good(); wrong.addProperty("available", "true"); reject(wrong);
        wrong = good(); JsonObject staleWorld = new JsonObject(); staleWorld.addProperty("dimension", 0);
        staleWorld.addProperty("providerClass", PrivateDimensionQaAdapter.PROVIDER); wrong.add("world", staleWorld); reject(wrong);
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.response("not json", "session", 180, PrivateDimensionQaAdapter.PROVIDER));
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.response("[]", "session", 180, PrivateDimensionQaAdapter.PROVIDER));
        JsonObject failed = good(); failed.addProperty("ok", false);
        check(!PrivateDimensionQaAdapter.response(failed.toString(), "session", 180, PrivateDimensionQaAdapter.PROVIDER).get("ok").getAsBoolean(),
                "public runner failure remains failure");
        JsonObject unavailable = good(); unavailable.addProperty("available", false);
        check(!PrivateDimensionQaAdapter.response(unavailable.toString(), "session", 180, PrivateDimensionQaAdapter.PROVIDER).get("available").getAsBoolean(),
                "unavailable public evidence never becomes available because the ABI returned successfully");
        System.out.println("Private dimension QA contract regression passed: " + checks + " assertions; no live world or runner is started.");
        return checks;
    }

    private static JsonObject good() {
        JsonObject value = new JsonObject();
        value.addProperty("ok", true); value.addProperty("source", PrivateDimensionQaAdapter.SOURCE);
        value.addProperty("sessionId", "session"); value.addProperty("dimension", 180);
        value.addProperty("providerClass", PrivateDimensionQaAdapter.PROVIDER);
        return value;
    }

    private static void reject(JsonObject value) {
        expect("qa_context_changed", () -> PrivateDimensionQaAdapter.response(value.toString(), "session", 180, PrivateDimensionQaAdapter.PROVIDER));
    }

    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Missing rejection: " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "rejection retains expected code: " + code); }
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
}
