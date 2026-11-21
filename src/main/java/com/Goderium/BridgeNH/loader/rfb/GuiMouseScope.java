package com.Goderium.BridgeNH.loader.rfb;

import java.util.function.BooleanSupplier;

/** Game-thread-only logical device, shared with the LWJGL compatibility class through the parent loader. */
public final class GuiMouseScope {
    public interface Device {
        boolean protectingGui();
        int x();
        int y();
        boolean down(int button);
    }

    private static final ThreadLocal<Device> DEVICE = new ThreadLocal<>();
    private static final ThreadLocal<Event> EVENT = new ThreadLocal<>();

    public static void attach(Device device) { DEVICE.set(device); }
    public static void detach() { DEVICE.remove(); }
    /** Drain only input already published to the native device while this GUI is still protected. */
    public static void discardBeforeHandoff(BooleanSupplier next, Runnable discardDeltas) {
        Device device = DEVICE.get();
        if (device == null || !device.protectingGui()) return;
        // A release inside a mod callback must drain the native queue, not poll its staged event.
        Event staged = EVENT.get();
        if (staged != null) EVENT.remove();
        try {
            while (next.getAsBoolean()) { /* Discard buffered button, wheel and motion events. */ }
            discardDeltas.run();
        } finally {
            if (staged != null) {
                // Keep the old callback scoped until it unwinds. Neither an unpolled virtual event
                // nor new manual events belong to that input loop after the handoff.
                staged.handedBack = true;
                staged.remainingDX = staged.remainingDY = staged.remainingWheel = 0;
                EVENT.set(staged);
            }
        }
    }
    public static boolean dispatching() { return EVENT.get()!=null; }
    public static boolean active() {
        Device device = DEVICE.get();
        return EVENT.get() != null || device != null && device.protectingGui();
    }
    public static boolean down(int button) {
        Event event = EVENT.get();
        return button>=0 && (event != null ? !event.handedBack && button == event.held : DEVICE.get().down(button));
    }
    public static final class Event {
        final int x, y, dx, dy, wheel, button, held;
        final boolean pressed;
        final long nanos = System.nanoTime();
        int remainingDX, remainingDY, remainingWheel;
        private boolean inputPass, consumed, handedBack;
        public boolean wasPolled() { return consumed; }

        public Event(int x, int y, int dx, int dy, int wheel, int button, boolean pressed, int held) {
            this.x=x; this.y=y; this.dx=dx; this.dy=dy; this.wheel=wheel;
            this.button=button; this.pressed=pressed; this.held=held;
            remainingDX=dx; remainingDY=dy; remainingWheel=wheel;
        }
    }
    public static void dispatch(Event event, Runnable callback) {
        if (EVENT.get()!=null) throw new IllegalStateException("Nested GUI mouse event");
        EVENT.set(event);
        try { callback.run(); } finally { EVENT.remove(); }
    }
    /** Deliver one event through the screen's own input loop, including mod pre/post listeners. */
    public static boolean pump(Event event, Runnable inputLoop) {
        event.inputPass = true;
        try {
            dispatch(event, inputLoop);
            return event.consumed;
        } finally { event.inputPass = false; }
    }

    public static boolean pumping() {
        Event event = EVENT.get();
        return event != null && (event.inputPass || event.handedBack);
    }

    public static boolean handedBack() {
        Event event = EVENT.get();
        return event != null && event.handedBack;
    }

    public static boolean nextMouseEvent() {
        Event event = EVENT.get();
        if (event == null || !event.inputPass || event.consumed || event.handedBack) return false;
        event.consumed = true;
        return true;
    }

    public static int x() { Event e=EVENT.get(); return e==null ? DEVICE.get().x() : e.x; }
    public static int y() { Event e=EVENT.get(); return e==null ? DEVICE.get().y() : e.y; }
    public static int consumeDX() { Event e=EVENT.get(); if (e==null) return 0; int n=e.remainingDX; e.remainingDX=0; return n; }
    public static int consumeDY() { Event e=EVENT.get(); if (e==null) return 0; int n=e.remainingDY; e.remainingDY=0; return n; }
    public static int consumeWheel() { Event e=EVENT.get(); if (e==null) return 0; int n=e.remainingWheel; e.remainingWheel=0; return n; }
    public static int eventDX() { Event e=EVENT.get(); return e==null ? 0 : e.dx; }
    public static int eventDY() { Event e=EVENT.get(); return e==null ? 0 : e.dy; }
    public static int wheel() { Event e=EVENT.get(); return e==null ? 0 : e.wheel; }
    public static int button() { Event e=EVENT.get(); return e==null ? -1 : e.button; }
    public static boolean pressed() { Event e=EVENT.get(); return e!=null && e.pressed; }
    public static long nanos() { Event e=EVENT.get(); return e==null ? 0 : e.nanos; }

    private GuiMouseScope() {}
}
