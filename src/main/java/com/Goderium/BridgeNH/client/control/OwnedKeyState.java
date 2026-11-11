package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import com.Goderium.BridgeNH.loader.rfb.TooltipKeyScope;

/** Logical hold of exact owned binding instances only. No native key buffers, abilities or motion writes. */
public final class OwnedKeyState {
    private static final ThreadLocal<Integer> SUSPENDED = new ThreadLocal<Integer>();
    private static volatile Lease lease;
    private static volatile History observed;
    private static volatile long hookCalls;
    private static final class Lease {
        final String owner;
        final Thread thread;
        final long deadline;
        final BooleanSupplier context;
        final LongSupplier clock;
        final Map<Object, Integer> keys = new IdentityHashMap<Object, Integer>();
        final History history;
        final Map<Object, Trace> traces;
        Lease(String owner, long deadline, BooleanSupplier context, LongSupplier clock) {
            this.owner = owner; this.deadline = deadline; this.context = context; this.clock = clock; thread = Thread.currentThread();
            history = new History(thread, clock); traces = history.traces;
        }
    }
    private static final class History {
        final Thread thread;
        final LongSupplier clock;
        final Map<Object, Trace> traces = new IdentityHashMap<Object, Trace>();
        long observedAt;
        History(Thread thread, LongSupplier clock) { this.thread = thread; this.clock = clock; observedAt = clock.getAsLong(); }
    }
    private static final class Trace {
        long getterCalls, getterAccepted, getterSuspended, keyboardCalls, keyboardAccepted;
        String getterDecision = "not_observed", keyboardDecision = "not_observed";
    }
    public static void begin(String owner, long deadline, BooleanSupplier context) { begin(owner, deadline, context, System::nanoTime); }
    static void begin(String owner, long deadline, BooleanSupplier context, LongSupplier clock) {
        Lease current = lease;
        if (current != null && !sameOwner(current,owner) && validContext(current))
            throw new BridgeException("busy", "Native input is exclusively leased; release its current owner before starting another task.");
        TooltipKeyScope.bindClientThread(); TooltipKeyScope.bindOwnedInput(OwnedKeyState::keyboardOverride);
        Lease created = new Lease(owner, deadline, context, clock); lease = created; observed = created.history;
    }
    public static void hold(String owner, Object binding, int code) {
        Lease current = lease;
        if (!sameOwner(current, owner) || binding == null || code == 0) return;
        if (!current.keys.containsKey(binding) && current.keys.size() >= 9) throw new BridgeException("owned_input_limit", "Logical key ownership exceeds the native nine-binding bound.");
        if (!current.traces.containsKey(binding) && current.traces.size() < 9) current.traces.put(binding, new Trace());
        current.keys.put(binding, code);
        current.history.observedAt = current.clock.getAsLong();
    }
    public static void release(String owner, Object binding) { Lease current = lease; if (sameOwner(current, owner)) current.keys.remove(binding); }
    public static void releaseAll(String owner) { Lease current = lease; if (sameOwner(current, owner)) current.keys.clear(); }
    public static void clear(String owner) { Lease current = lease; if (sameOwner(current, owner)) { current.history.observedAt = current.clock.getAsLong(); lease = null; } }
    private static boolean sameOwner(Lease current, String owner) { return current != null && current.thread == Thread.currentThread() && current.owner.equals(owner); }
    public static boolean active(String owner) { Lease current = lease; return sameOwner(current, owner) && validContext(current); }
    private static boolean validContext(Lease current) {
        boolean valid;
        try { valid = current.clock.getAsLong() < current.deadline && current.context.getAsBoolean(); }
        catch (RuntimeException | LinkageError error) { valid = false; }
        if (!valid && lease == current) { current.history.observedAt = current.clock.getAsLong(); lease = null; }
        return valid;
    }

    /** Called before native KeyBinding modifier gates. null preserves its original/native getter path. */
    public static Boolean override(Object binding, int actualCode) {
        hookCalls++;
        Lease current = lease; Integer suspended = SUSPENDED.get();
        if (current == null || current.thread != Thread.currentThread()) return null;
        Trace trace = current.traces.get(binding);
        if (trace != null) { trace.getterCalls++; current.history.observedAt = current.clock.getAsLong(); }
        if (suspended != null && suspended > 0) { if (trace != null) trace.getterSuspended++; return null; }
        Integer expected = current.keys.get(binding);
        if (expected == null) { if (trace != null) trace.getterDecision = "released"; return null; }
        if (!validContext(current)) { if (trace != null) trace.getterDecision = "context_or_deadline_rejected"; return null; }
        if (expected != actualCode) { current.keys.remove(binding); if (trace != null) trace.getterDecision = "binding_code_changed"; return null; }
        if (trace != null) { trace.getterAccepted++; trace.getterDecision = "owned_hold"; }
        return Boolean.TRUE;
    }
    /** Logical keyboard reads for actual owned codes only; no physical events or native buffers are changed. */
    static Boolean keyboardOverride(int code) {
        Lease current = lease; Integer suspended = SUSPENDED.get();
        if (current == null || current.thread != Thread.currentThread() || code <= 0 || code >= 256 || suspended != null && suspended > 0) return null;
        for (Map.Entry<Object, Integer> entry : current.keys.entrySet()) if (entry.getValue() == code) {
            Trace trace = current.traces.get(entry.getKey());
            if (trace != null) { trace.keyboardCalls++; current.history.observedAt = current.clock.getAsLong(); }
            if (!validContext(current)) { if (trace != null) trace.keyboardDecision = "context_or_deadline_rejected"; return null; }
            if (trace != null) { trace.keyboardAccepted++; trace.keyboardDecision = "owned_hold"; }
            return Boolean.TRUE;
        }
        return null;
    }
    @com.google.common.annotations.VisibleForTesting
    public static Map<String, Object> trace(Object binding) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); History current = observed;
        if (current == null || current.thread != Thread.currentThread() || current.clock.getAsLong() - current.observedAt > 10000000000L) {
            if (current != null && current.thread == Thread.currentThread() && observed == current) observed = null;
            result.put("available", false); return result;
        }
        Trace trace = current.traces.get(binding); result.put("available", trace != null); if (trace == null) return result;
        result.put("getterCalls", trace.getterCalls); result.put("getterAccepted", trace.getterAccepted); result.put("getterSuspended", trace.getterSuspended);
        result.put("lastGetterDecision", trace.getterDecision); result.put("keyboardCalls", trace.keyboardCalls); result.put("keyboardAccepted", trace.keyboardAccepted);
        result.put("lastKeyboardDecision", trace.keyboardDecision); return result;
    }
    public static long hookCalls() { return hookCalls; }
    /** Native manual input is unchanged. Only this exact live player.input binding may fill the background focus gate. */
    public static boolean attackInput(boolean nativeAttack, Object attackBinding, int actualCode) {
        return nativeAttack || active("player.input") && Boolean.TRUE.equals(override(attackBinding, actualCode));
    }
    public static int heldCount() { Lease current = lease; return current != null && current.thread == Thread.currentThread() ? current.keys.size() : 0; }
    @com.google.common.annotations.VisibleForTesting
    public static boolean nativeRead(BooleanSupplier getter) {
        try (Scope ignored = suspend(); TooltipKeyScope.Scope keyboard = TooltipKeyScope.suspendOwnedInput()) { return getter.getAsBoolean(); }
    }
    static Scope suspend() { Integer previous = SUSPENDED.get(); SUSPENDED.set(previous == null ? 1 : previous + 1); return new Scope(previous); }
    static final class Scope implements AutoCloseable {
        final Integer previous;
        boolean closed;
        Scope(Integer previous) { this.previous = previous; }
        public void close() { if (!closed) { closed = true; if (previous == null) SUSPENDED.remove(); else SUSPENDED.set(previous); } }
    }
}
