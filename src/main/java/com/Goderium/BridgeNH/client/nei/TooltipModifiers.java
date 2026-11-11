package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import com.Goderium.BridgeNH.loader.rfb.TooltipKeyScope;
import org.lwjgl.input.Keyboard;

/** Client-thread Tooltip scope only. Never changes a native key buffer or generates an input event. */
public final class TooltipModifiers {
    private static volatile String probeStatus = "not_probed";
    private static final String TARGET = "org.lwjglx.input.Keyboard.isKeyDown";

    static void bindClientThread() { TooltipKeyScope.bindClientThread(); }
    public static JsonObject diagnostics() {
        JsonObject result = new JsonObject(); result.addProperty("hookTarget", TARGET);
        result.addProperty("hookObserved", TooltipKeyScope.hookCalls() > 0); result.addProperty("hookCalls", TooltipKeyScope.hookCalls());
        result.addProperty("probeStatus", probeStatus); result.addProperty("clientThread", TooltipKeyScope.onClientThread()); return result;
    }
    public static void probe() {
        if (!TooltipKeyScope.onClientThread() || !("not_probed".equals(probeStatus) || "keyboard_not_created".equals(probeStatus))) return;
        long before = TooltipKeyScope.hookCalls();
        try {
            // Read the actual compatibility function once, without installing any override or changing input.
            if (!Keyboard.isCreated()) { probeStatus = "keyboard_not_created"; return; }
            Keyboard.isKeyDown(42);
            probeStatus = TooltipKeyScope.hookCalls() > before ? "actual_keyboard_hook_observed" : "actual_keyboard_hook_absent";
        } catch (RuntimeException | LinkageError error) { probeStatus = "probe_failed:" + error.getClass().getSimpleName(); }
    }

    static void validate(JsonObject args, boolean allowed) {
        if (!args.has("modifiers")) return;
        if (!allowed || !args.get("modifiers").isJsonObject()) throw new BridgeException("invalid_args", "modifiers is only an object for an actual Tooltip query.");
        JsonObject value = args.getAsJsonObject("modifiers");
        if (value.entrySet().isEmpty() || value.entrySet().size() > 3) throw new BridgeException("invalid_args", "modifiers requires 1 to 3 explicit flags.");
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            if (!("shift".equals(entry.getKey()) || "control".equals(entry.getKey()) || "alt".equals(entry.getKey()))
                    || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean())
                throw new BridgeException("invalid_args", "modifiers accepts only boolean shift, control and alt.");
        }
    }

    static Scope enter(JsonObject args) {
        JsonObject physical = physical();
        JsonObject requested = args.has("modifiers") ? args.getAsJsonObject("modifiers") : new JsonObject();
        probe(); boolean hookObserved = TooltipKeyScope.hookCalls() > 0;
        JsonObject data = new JsonObject(); data.add("requestedModifiers", requested.deepCopy()); data.add("physicalModifiers", physical);
        JsonObject effective = new JsonObject();
        for (String name : new String[] {"shift", "control", "alt"}) {
            String physicalName = "control".equals(name) ? "ctrl" : name;
            boolean actual = physical.has(physicalName) && physical.get(physicalName).getAsBoolean();
            boolean value = requested.has(name) ? requested.get(name).getAsBoolean() : actual;
            if (requested.has(name) || physical.has(physicalName)) effective.addProperty(name, value);
        }
        data.add("effectiveModifiers", effective); data.addProperty("hookTarget", TARGET); data.addProperty("hookObserved", hookObserved);
        data.addProperty("virtualModifiers", !requested.entrySet().isEmpty() && hookObserved);
        data.addProperty("support", hookObserved ? "scoped_keyboard_isKeyDown" : requested.entrySet().isEmpty() ? "physical_only" : "unsupported");
        data.add("runtimeProbe", diagnostics());
        com.google.gson.JsonArray limits = new com.google.gson.JsonArray();
        limits.add(new com.google.gson.JsonPrimitive("Direct native key-buffer reads, third-party cached key state and native modifier APIs are not intercepted."));
        limits.add(new com.google.gson.JsonPrimitive("Plain LWJGL2 org.lwjgl parent-loader exclusions are preserved; this hook requires the observed RFB/lwjgl3ify compatibility implementation."));
        data.add("limitations", limits);
        if (!requested.entrySet().isEmpty() && !TooltipKeyScope.onClientThread())
            throw new BridgeException("unsupported", "Virtual Tooltip modifiers require the existing client thread.");
        if (!requested.entrySet().isEmpty() && !hookObserved) throw new BridgeException("unsupported", "Actual Keyboard.isKeyDown scoped hook absent (" + probeStatus + "); explicit virtual Tooltip modifiers cannot be applied.");
        return new Scope(!requested.entrySet().isEmpty() ? TooltipKeyScope.enter(flag(requested, "shift"), flag(requested, "control"), flag(requested, "alt")) : null, data);
    }

    static String signature(JsonObject args) {
        if (!args.has("modifiers")) return "physical/" + physical();
        JsonObject requested = args.getAsJsonObject("modifiers"), physical = physical();
        StringBuilder signature = new StringBuilder("tooltip/");
        for (String name : new String[] {"shift", "control", "alt"}) {
            String physicalName = "control".equals(name) ? "ctrl" : name;
            signature.append(name).append('=').append(requested.has(name) ? requested.get(name) : physical.has(physicalName) ? physical.get(physicalName) : "unavailable").append('/');
        }
        return signature.toString();
    }

    private static JsonObject physical() { try (TooltipKeyScope.Scope ignored = TooltipKeyScope.suspend()) { return NeiDataAdapter.modifiers(); } }

    private static Boolean flag(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsBoolean() : null; }
    static final class Scope implements AutoCloseable {
        final TooltipKeyScope.Scope state; final JsonObject data;
        Scope(TooltipKeyScope.Scope state, JsonObject data) { this.state = state; this.data = data; }
        @Override public void close() { if (state != null) state.close(); }
    }
}
