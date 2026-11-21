package com.Goderium.BridgeNH.loader.rfb;

import com.google.common.io.ByteStreams;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

public final class GuiMouseHandoffSelfTest {
    public interface Probe {
        void enqueue(int button, int wheel, int dx, int dy);
        boolean poll();
        int[] event();
        int[] deltas();
        void handoff();
    }

    /** Transformed by the actual RFB hook. Event polling and accumulated deltas are independent. */
    public static final class MouseQueue implements Probe {
        private static final ArrayDeque<int[]> QUEUE = new ArrayDeque<>();
        private static int[] event;
        private static int dx, dy, wheel;

        public void enqueue(int button, int notch, int x, int y) {
            QUEUE.addLast(new int[]{button, notch, x, y});
            dx += x;
            dy += y;
            wheel += notch;
        }
        public boolean poll() { return next(); }
        public int[] event() { return new int[]{getEventButton(), getEventDWheel(), getEventDX(), getEventDY()}; }
        public int[] deltas() { return new int[]{getDX(), getDY(), getDWheel()}; }
        public void handoff() { GuiMouseScope.discardBeforeHandoff(MouseQueue::next, this::deltas); }
        public static boolean next() { event = QUEUE.pollFirst(); return event != null; }
        public static int getDX() { int value = dx; dx = 0; return value; }
        public static int getDY() { int value = dy; dy = 0; return value; }
        public static int getDWheel() { int value = wheel; wheel = 0; return value; }
        public static int getEventButton() { return event[0]; }
        public static int getEventDWheel() { return event[1]; }
        public static int getEventDX() { return event[2]; }
        public static int getEventDY() { return event[3]; }
        public static int getX() { return 0; }
        public static int getY() { return 0; }
        public static int getEventX() { return 0; }
        public static int getEventY() { return 0; }
        public static long getEventNanoseconds() { return 0; }
        public static boolean getEventButtonState() { return false; }
        public static boolean isButtonDown(int button) { return false; }
        public static boolean isInsideWindow() { return true; }
        public static void setCursorPosition(int x, int y) {}
        public static void setGrabbed(boolean grabbed) {}
    }

    public static int run() throws Exception {
        ClassNodeHandle handle;
        try (InputStream bytes = GuiMouseHandoffSelfTest.class.getResourceAsStream("GuiMouseHandoffSelfTest$MouseQueue.class")) {
            handle = new ClassNodeHandle(ByteStreams.toByteArray(bytes));
        }
        new GuiMouseTransformer().transformClass(null, RfbClassTransformer.Context.LCL_NO_TRANSFORMS,
                null, "org.lwjglx.input.Mouse", handle);
        byte[] transformed = handle.computeBytes();
        Class<?> backend = new ClassLoader(GuiMouseHandoffSelfTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, transformed, 0, transformed.length); }
        }.define();
        backend.getMethod(GuiMouseTransformer.MARKER);
        Probe mouse = (Probe)backend.getConstructor().newInstance();
        boolean[] protectedGui = {true};
        GuiMouseScope.attach(new GuiMouseScope.Device() {
            public boolean protectingGui() { return protectedGui[0]; }
            public int x() { return 0; }
            public int y() { return 0; }
            public boolean down(int button) { return false; }
        });
        try {
            mouse.enqueue(0, 120, 5, -11);
            mouse.handoff();
            protectedGui[0] = false;
            mouse.enqueue(2, 0, -3, 1);
            check(mouse.poll() && Arrays.equals(mouse.event(), new int[]{2, 0, -3, 1}) && !mouse.poll(),
                    "ordinary handoff must preserve only the new physical event");
            check(Arrays.equals(mouse.deltas(), new int[]{-3, 1, 0}), "pre-handoff aggregate motion and wheel leaked");
            protectedGui[0] = true;
            mouse.enqueue(0, 0, 3, 4);
            mouse.enqueue(-1, 120, 0, 0);
            GuiMouseScope.Event staged = new GuiMouseScope.Event(2, 4, 1, -2, -120, -1, false, -1);
            boolean delivered = GuiMouseScope.pump(staged, () -> {
                mouse.handoff();
                protectedGui[0] = false;
                mouse.enqueue(1, 0, 7, -9);
                mouse.enqueue(-1, -120, 0, 0);
                check(!staged.wasPolled(), "handoff consumed the synthetic event");
                check(!mouse.poll(), "unpolled virtual input must not be delivered after handoff");
                check(Arrays.equals(mouse.deltas(), new int[]{0, 0, 0}), "staged motion or scroll remained after handoff");
            });
            check(!delivered, "handing back before consumption must report zero deliveries");
            mouse.handoff(); // An already-manual device must leave all newly arrived input alone.
            check(mouse.poll() && Arrays.equals(mouse.event(), new int[]{1, 0, 7, -9}), "stale press or motion survived handoff");
            check(mouse.poll() && Arrays.equals(mouse.event(), new int[]{-1, -120, 0, 0}), "manual scroll was lost or reordered");
            check(!mouse.poll(), "stale physical events remained after handoff");
            check(Arrays.equals(mouse.deltas(), new int[]{7, -9, -120}), "only post-handoff physical deltas should remain");
            protectedGui[0] = true;
            GuiMouseScope.Event press = new GuiMouseScope.Event(4, 2, 0, 0, 0, 0, true, 0);
            check(GuiMouseScope.pump(press, () -> {
                check(mouse.poll() && GuiMouseScope.down(0), "the native handler must see the staged press");
                mouse.handoff();
                protectedGui[0] = false;
                check(!mouse.poll() && !GuiMouseScope.down(0), "handing back inside a handler must release the held button");
            }), "one previously delivered press must retain its delivery receipt");
            return 13;
        } finally { GuiMouseScope.detach(); }
    }

    private static void check(boolean value, String error) { if (!value) throw new AssertionError(error); }
}
