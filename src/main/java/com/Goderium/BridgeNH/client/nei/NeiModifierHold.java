package com.Goderium.BridgeNH.client.nei;

import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.loader.rfb.TooltipKeyScope;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Screen-bound Shift reads for normal NEI ticks/frames, with no native input events. */
public final class NeiModifierHold {
    private static BooleanSupplier context;
    private static LongSupplier clock = System::nanoTime;
    private static Thread thread;
    private static long deadline;
    private static int recipeIndex;
    private static String reason = "not_held";
    public static JsonObject arm(int recipe, int ttl, BooleanSupplier valid) {
        clear("replaced"); TooltipKeyScope.bindClientThread();
        TooltipKeyScope.bindGuiInput(NeiModifierHold::override);
        recipeIndex = recipe; context = valid; thread = Thread.currentThread();
        deadline = clock.getAsLong() + ttl * 1000000L; reason = "held";
        return inspect();
    }
    static Boolean override(int key) {
        if (Thread.currentThread() != thread || key != 42 && key != 54) return null;
        tick(); return context == null ? null : Boolean.TRUE;
    }
    public static void tick() {
        if (context == null || Thread.currentThread() != thread) return;
        try { if (clock.getAsLong() >= deadline || !context.getAsBoolean()) clear("expired_or_context_changed"); }
        catch (RuntimeException | LinkageError error) { clear("context_unavailable"); }
    }
    static void clear(String why) { context = null; thread = null; reason = why; }
    static JsonObject inspect() {
        tick(); JsonObject out = new JsonObject(); out.addProperty("shiftHeld", context != null);
        out.addProperty("clearReason", reason); out.addProperty("nativeInputEvents", false);
        if (context != null) { out.addProperty("recipeIndex", recipeIndex); out.addProperty("ttlRemainingMillis", Math.max(0, deadline - clock.getAsLong()) / 1000000D); }
        return out;
    }
    static void fixtureClock(LongSupplier value) { clear("fixture_reset"); clock = value == null ? System::nanoTime : value; }
}
