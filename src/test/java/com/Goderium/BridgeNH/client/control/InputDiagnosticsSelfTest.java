package com.Goderium.BridgeNH.client.control;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;

/** Pure read-only fake-binding fixtures. Native interception or movement is not simulated. */
public final class InputDiagnosticsSelfTest {
    private static int checks;
    private static final Object WORLD = new Object(), PLAYER = new Object();
    private static final class Binding implements InputDiagnostics.Key {
        final Object identity; final int code; boolean held; Boolean nativeHeld;
        Binding(int code, boolean held) { this(code, held, new Object()); }
        Binding(int code, boolean held, Object identity) { this.code = code; this.held = held; this.identity = identity; }
        public int code() { return code; }
        public boolean held() { return held; }
        public boolean nativeHeld() { return nativeHeld == null ? held : nativeHeld; }
        public String description() { return "key.sneak"; }
        public Object identity() { return identity; }
    }
    public static void main(String[] args) { System.out.println("InputDiagnosticsSelfTest: " + run() + " checks passed"); }
    public static int run() { checks = 0; writeAndEnd(); ownedAndNative(); identities(); bounds(); lifetime(); return checks; }
    private static JsonObject movement(boolean sneak) { JsonObject value = new JsonObject(); value.addProperty("available", true); value.addProperty("sneak", sneak); return value; }
    private static JsonObject inspect(InputDiagnostics diagnostics, long tick) { return diagnostics.inspect(WORLD, PLAYER, "session", tick, movement(false)); }
    private static JsonObject row(JsonObject result) { return result.getAsJsonArray("keys").get(0).getAsJsonObject(); }
    private static void writeAndEnd() {
        AtomicLong clock = new AtomicLong(); InputDiagnostics diagnostics = new InputDiagnostics(clock::get); Binding sneak = new Binding(42, false);
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 1, new Binding[] {sneak});
        diagnostics.write("player.input", "sneak", sneak, 42, true, 1); JsonObject initial = inspect(diagnostics, 1), first = row(initial);
        check(initial.get("active").getAsBoolean() && !first.get("heldImmediatelyAfterWrite").getAsBoolean() && !first.get("writeAccepted").getAsBoolean(), "a rejected static key write is visible without inventing a pressed state");
        check(first.get("bindingClass").getAsString().equals(Object.class.getName()) && !first.getAsJsonObject("logicalReadTrace").get("available").getAsBoolean(), "binding class and absent exact-target hook evidence remain explicit");
        check(first.get("heldAtClientTickEnd").isJsonNull() && first.get("endAgeMillis").isJsonNull(), "unobserved END stays unknown");
        sneak.held = true; clock.set(1000000); diagnostics.write("player.input", "sneak", sneak, 42, true, 2);
        sneak.held = false; clock.set(2000000); diagnostics.tickEnd(2, movement(false)); JsonObject lost = row(inspect(diagnostics, 2));
        check(lost.get("writeAccepted").getAsBoolean() && !lost.get("heldAtClientTickEnd").getAsBoolean() && lost.get("lostBetweenWriteAndEnd").getAsBoolean(), "accepted write then same-tick cleared binding is distinct from write rejection");
        check(lost.get("writeAgeMillis").getAsDouble() == 1 && lost.get("endAgeMillis").getAsDouble() == 0 && !sneak.held, "read-only evidence retains separate ages without repairing keys");
        diagnostics.write("player.input", "sneak", sneak, 42, false, 2); diagnostics.release("player.input"); JsonObject released = inspect(diagnostics, 3);
        check(!released.get("active").getAsBoolean() && row(released).get("writeAccepted").getAsBoolean() && !row(released).get("lostBetweenWriteAndEnd").getAsBoolean(), "intentional release is inactive and cannot be mistaken for a lost requested press");
        diagnostics.begin("player.navigate", WORLD, PLAYER, "session", 4, new Binding[] {sneak}); diagnostics.write("player.input", "sneak", sneak, 42, true, 4);
        check(!inspect(diagnostics, 4).get("available").getAsBoolean(), "another owner cannot add evidence to a navigation task");
        sneak.held = true; diagnostics.write("player.navigate", "sneak", sneak, 42, true, 4); sneak.held = false; diagnostics.tickEnd(5, movement(false));
        check(!row(inspect(diagnostics, 5)).get("lostBetweenWriteAndEnd").getAsBoolean(), "different-tick reads do not claim a same-tick overwrite");
    }
    private static void identities() {
        InputDiagnostics diagnostics = new InputDiagnostics(() -> 0); Object identity = new Object(); Binding target = new Binding(42, true, identity), wrapper = new Binding(42, true, identity), other = new Binding(42, false);
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 1, new Binding[] {wrapper, other}); diagnostics.write("player.input", "sneak", target, 42, true, 1);
        JsonObject observed = row(inspect(diagnostics, 1));
        check(observed.get("registeredTargetSameInstance").getAsBoolean() && observed.get("registeredTargetIndex").getAsInt() == 0 && observed.get("sameCodeRegisteredCount").getAsInt() == 2, "native binding identity is compared rather than wrapper identity");
        check(observed.get("firstSameCodeIsTarget").getAsBoolean() && observed.get("sameCodeHeldImmediatelyAfterWrite").getAsInt() == 1 && observed.get("sameCodeHeldCountExact").getAsBoolean(), "conflicting bindings are read with bounded exact immediate pressed count");
        Binding missing = new Binding(42, false), indexed = new Binding(42, true); diagnostics.begin("player.input", WORLD, PLAYER, "session", 2, new Binding[] {indexed}); diagnostics.write("player.input", "sneak", missing, 42, true, 2);
        JsonObject wrongIndex = row(inspect(diagnostics, 2));
        check(!wrongIndex.get("registeredTargetSameInstance").getAsBoolean() && !wrongIndex.get("firstSameCodeIsTarget").getAsBoolean() && wrongIndex.get("sameCodeHeldImmediatelyAfterWrite").getAsInt() == 1
                && !wrongIndex.get("writeAccepted").getAsBoolean(), "same-code indexed press with a missing real target is observable as a registration mismatch");
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 3, new Binding[] {target}); diagnostics.write("player.input", "sneak", target, 43, true, 3);
        check(!row(inspect(diagnostics, 3)).get("writeAccepted").getAsBoolean() && row(inspect(diagnostics, 3)).get("bindingKeyCodeAtWrite").getAsInt() == 42, "original owned-code versus current binding-code mismatch stays explicit");
    }
    private static void ownedAndNative() {
        InputDiagnostics diagnostics = new InputDiagnostics(() -> 0); Binding sneak = new Binding(42, true); sneak.nativeHeld = false;
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 1, new Binding[] {sneak}); diagnostics.write("player.input", "sneak", sneak, 42, true, 1);
        JsonObject initial = row(inspect(diagnostics, 1));
        check(initial.get("heldImmediatelyAfterWrite").getAsBoolean() && initial.get("writeAccepted").getAsBoolean()
                && !initial.get("nativeHeldImmediatelyAfterWrite").getAsBoolean() && !initial.get("nativeWriteAccepted").getAsBoolean(), "logical getter acceptance preserves the underlying modifier-gated rejection as separate evidence");
        check(initial.get("nativeHeldAtClientTickEnd").isJsonNull(), "unobserved native END is also unknown");
        diagnostics.tickEnd(1, movement(true)); JsonObject end = row(inspect(diagnostics, 1));
        check(end.get("heldAtClientTickEnd").getAsBoolean() && !end.get("nativeHeldAtClientTickEnd").getAsBoolean()
                && end.get("heldNow").getAsBoolean() && !end.get("nativeHeldNow").getAsBoolean(), "effective and native getters are retained at END and current observation");
        sneak.held = false; diagnostics.write("player.input", "sneak", sneak, 42, false, 2); diagnostics.release("player.input"); JsonObject released = row(inspect(diagnostics, 2));
        check(!released.get("heldNow").getAsBoolean() && !released.get("nativeHeldNow").getAsBoolean() && released.get("nativeWriteAccepted").getAsBoolean(), "release resumes native false without erasing preceding native diagnostics");
    }
    private static void bounds() {
        InputDiagnostics diagnostics = new InputDiagnostics(() -> 0); Binding binding = new Binding(42, true); diagnostics.begin("player.input", WORLD, PLAYER, "session", 1, new Binding[] {binding});
        for (int index = 0; index < 10; index++) diagnostics.write("player.input", "key" + index, binding, 42, true, 1);
        JsonObject limited = inspect(diagnostics, 1); check(limited.getAsJsonArray("keys").size() == 9 && "partial".equals(limited.get("status").getAsString()), "no more than nine actually observed owned key names are retained");
        Binding[] many = new Binding[20]; for (int index = 0; index < many.length; index++) many[index] = new Binding(42, true);
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 2, many); diagnostics.write("player.input", "sneak", many[0], 42, true, 2); JsonObject collisions = row(inspect(diagnostics, 2));
        check(collisions.get("sameCodeRegisteredCount").getAsInt() == 20 && collisions.get("sameCodeHeldImmediatelyAfterWrite").getAsInt() == 16 && !collisions.get("sameCodeHeldCountExact").getAsBoolean(), "same-code state cap preserves exact registered count and explicit partial pressed count");
        Binding[] large = new Binding[InputDiagnostics.MAX_REGISTERED + 1]; large[large.length - 1] = binding;
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 3, large); diagnostics.write("player.input", "sneak", binding, 42, true, 3); JsonObject capped = row(inspect(diagnostics, 3));
        check(capped.get("registeredTargetSameInstance").isJsonNull() && !capped.get("registeredCountExact").getAsBoolean(), "registration outside the bounded prefix cannot be called absent");
    }
    private static void lifetime() {
        AtomicLong clock = new AtomicLong(); InputDiagnostics diagnostics = new InputDiagnostics(clock::get); Binding binding = new Binding(42, true);
        diagnostics.begin("player.input", WORLD, PLAYER, "session", 1, new Binding[] {binding}); diagnostics.write("player.input", "sneak", binding, 42, true, 1);
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            JsonObject wrong = diagnostics.inspect(mismatch == 0 ? new Object() : WORLD, mismatch == 1 ? new Object() : PLAYER, mismatch == 2 ? "other" : "session", 2, movement(false));
            check(!wrong.get("available").getAsBoolean() && wrong.getAsJsonArray("keys").size() == 0, "old owner evidence is unavailable in a changed world/player/session");
        }
        clock.set(InputDiagnostics.TTL_NANOS + 1); check(!inspect(diagnostics, 2).get("available").getAsBoolean(), "inactive historical observations expire within the finite TTL");
        diagnostics.invalidate(); check(!inspect(diagnostics, 2).get("available").getAsBoolean(), "world invalidation removes retained binding evidence");
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
