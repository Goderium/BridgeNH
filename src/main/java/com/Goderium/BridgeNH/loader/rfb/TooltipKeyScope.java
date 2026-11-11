package com.Goderium.BridgeNH.loader.rfb;

import java.util.function.IntFunction;

/** Early-loader-safe state. Only JDK types; no input events, native buffers or game dependencies. */
public final class TooltipKeyScope {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<State>();
    private static final ThreadLocal<Integer> OWNED_SUSPENDED = new ThreadLocal<Integer>();
    private static volatile Thread clientThread;
    private static volatile long hookCalls;
    private static volatile IntFunction<Boolean> ownedInput;
    private static volatile IntFunction<Boolean> guiInput;

    public static void bindClientThread() { clientThread = Thread.currentThread(); }
    public static boolean onClientThread() { return Thread.currentThread() == clientThread; }
    public static long hookCalls() { return hookCalls; }
    /** A JDK-only callback keeps the early parent-loader helper independent of game classes. */
    public static void bindOwnedInput(IntFunction<Boolean> provider) { ownedInput = provider; }
    public static void bindGuiInput(IntFunction<Boolean> provider) { guiInput = provider; }
    public static Boolean override(int key) {
        hookCalls++;
        State state = CURRENT.get();
        if (!onClientThread()) return null;
        if (state != null) {
            switch (key) {
                case 42: case 54: return state.shift;
                case 29: case 157: return state.control;
                case 56: case 184: return state.alt;
                default: return null;
            }
        }
        Integer suspended = OWNED_SUSPENDED.get(); IntFunction<Boolean> provider = ownedInput;
        if (suspended != null && suspended > 0) return null;
        IntFunction<Boolean> gui = guiInput;
        Boolean held = gui == null ? null : gui.apply(key);
        return held != null ? held : provider == null ? null : provider.apply(key);
    }
    public static Scope enter(Boolean shift, Boolean control, Boolean alt) {
        State previous = CURRENT.get(); CURRENT.set(new State(shift, control, alt)); return new Scope(previous, true, null, false);
    }
    public static Scope suspend() {
        State previous = CURRENT.get(); CURRENT.remove(); Integer previousOwned = suspendOwned(); return new Scope(previous, true, previousOwned, true);
    }
    public static Scope suspendOwnedInput() { return new Scope(null, false, suspendOwned(), true); }
    private static Integer suspendOwned() { Integer previous = OWNED_SUSPENDED.get(); OWNED_SUSPENDED.set(previous == null ? 1 : previous + 1); return previous; }
    private static final class State {
        final Boolean shift, control, alt;
        State(Boolean shift, Boolean control, Boolean alt) { this.shift = shift; this.control = control; this.alt = alt; }
    }
    public static final class Scope implements AutoCloseable {
        private final State previous;
        private final boolean restoreTooltip, restoreOwned;
        private final Integer previousOwned;
        private boolean closed;
        Scope(State previous, boolean restoreTooltip, Integer previousOwned, boolean restoreOwned) {
            this.previous = previous; this.restoreTooltip = restoreTooltip; this.previousOwned = previousOwned; this.restoreOwned = restoreOwned;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if (restoreTooltip) { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
            if (restoreOwned) { if (previousOwned == null) OWNED_SUSPENDED.remove(); else OWNED_SUSPENDED.set(previousOwned); }
        }
    }
}
