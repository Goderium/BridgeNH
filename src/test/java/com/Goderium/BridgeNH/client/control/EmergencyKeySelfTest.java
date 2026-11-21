package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.loader.rfb.EmergencyKeyboardTransformer;
import com.Goderium.BridgeNH.loader.rfb.PhysicalKeyboardEvents;
import com.Goderium.BridgeNH.loader.rfb.GuiMouseScope;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.google.common.io.ByteStreams;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class EmergencyKeySelfTest {
    private static int checks;
    private static final int L = 38, F8 = 66, RCTRL = 157, LSHIFT = 42, LALT = 56, RALT = 184;
    private static final int[][] EVENTS = {
        {RCTRL, 1}, {LSHIFT, 1}, {L, 1}, {L, 2},
        {RCTRL, 0}, {L, 0}, {LSHIFT, 0}, {L, 1}, {L, 0}
    };
    private static int index;

    public static final class KeyboardQueue {
        public static boolean next() { return nextPhysical(); }
    }

    public static boolean nextPhysical() { return ++index < EVENTS.length; }

    public static int run() throws Exception {
        checks = 0;
        EmergencyReleaseKey key = parse(EmergencyReleaseKey.DEFAULT);
        check("Ctrl+Shift+L".equals(key.name()), "default is Ctrl+Shift+L");
        AtomicInteger stops = new AtomicInteger();
        ClassNodeHandle handle;
        try (InputStream source = EmergencyKeySelfTest.class.getResourceAsStream("EmergencyKeySelfTest$KeyboardQueue.class")) {
            handle = new ClassNodeHandle(ByteStreams.toByteArray(source));
        }
        EmergencyKeyboardTransformer transformer = new EmergencyKeyboardTransformer();
        RfbClassTransformer.Context context = RfbClassTransformer.Context.LCL_NO_TRANSFORMS;
        transformer.transformClass(null, context, null, "org.lwjglx.input.Keyboard", handle);
        byte[] transformed = handle.computeBytes();
        transformer.transformClass(null, context, null, "org.lwjglx.input.Keyboard", handle);
        check(Arrays.equals(transformed, handle.computeBytes()), "keyboard transform is idempotent");
        Class<?> backend = new ClassLoader(EmergencyKeySelfTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, transformed, 0, transformed.length); }
        }.define();
        backend.getMethod(EmergencyKeyboardTransformer.MARKER);
        Method next = backend.getMethod("next");
        PhysicalKeyboardEvents.bind(() -> key.event(EVENTS[index][0], EVENTS[index][1] != 0, EVENTS[index][1] == 2, stops::incrementAndGet));
        List<Integer> forwarded = new ArrayList<>();
        index = -1;
        try {
            GuiMouseScope.Event move = new GuiMouseScope.Event(12, 34, 1, -1, 0, -1, false, -1);
            boolean delivered = GuiMouseScope.pump(move, () -> {
                try {
                    check(!(boolean) next.invoke(null) && index == -1, "a mouse-only pass leaves the physical keyboard queue untouched");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                check(GuiMouseScope.nextMouseEvent() && !GuiMouseScope.nextMouseEvent(), "a native input loop receives exactly one virtual event");
                check(GuiMouseScope.x() == 12 && GuiMouseScope.y() == 34, "the event handler reads the staged mouse coordinates");
            });
            check(delivered && !GuiMouseScope.pumping(), "physical input polling resumes after the virtual pass");
            GuiMouseScope.Event scroll = new GuiMouseScope.Event(16, 28, 0, 0, -120, -1, false, -1);
            check(!GuiMouseScope.pump(scroll, () -> {}) && !scroll.wasPolled(), "a list outside the pointer bounds does not claim a notch");
            try {
                GuiMouseScope.pump(scroll, () -> {
                    check(GuiMouseScope.nextMouseEvent() && GuiMouseScope.wheel() == -120, "the list receives the original signed wheel value");
                    throw new IllegalStateException("fixture after poll");
                });
                throw new AssertionError("native handler failure was hidden");
            } catch (IllegalStateException expected) {
                check(scroll.wasPolled() && !GuiMouseScope.pumping(), "a delivered event remains accounted for when a handler throws");
            }
            while ((boolean) next.invoke(null)) forwarded.add(index);
        } finally { PhysicalKeyboardEvents.bind(null); }
        check(stops.get() == 1, "holding the chord emits one stop");
        check(forwarded.equals(Arrays.asList(0, 1, 4, 6, 7, 8)), "the GUI and world receive remaining events in order, including subsequent plain L");

        EmergencyReleaseKey custom = parse(" f8 + Alt ");
        check("Alt+F8".equals(custom.name()), "config accepts reordered keys and normalizes the displayed chord");
        custom.event(LALT, true, false, stops::incrementAndGet);
        custom.event(RALT, true, false, stops::incrementAndGet);
        custom.event(LALT, false, false, stops::incrementAndGet);
        check(custom.event(F8, true, false, stops::incrementAndGet), "right Alt remains held after left Alt is released");
        custom.reset();
        check(!custom.event(F8, true, false, stops::incrementAndGet), "focus loss clears stale modifiers");
        check(parse("F8").event(F8, true, false, stops::incrementAndGet), "a single key can be configured");
        for (String bad : new String[]{"", "CTRL+SHIFT", "CTRL++L", "ALT+ALT+F8", "F8+L", "LSHIFT", "UNKNOWN"}) {
            try { parse(bad); throw new AssertionError("Accepted invalid chord " + bad); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        return checks;
    }

    private static EmergencyReleaseKey parse(String value) {
        return EmergencyReleaseKey.parse(value, name -> {
            if ("L".equals(name)) return L;
            if ("F8".equals(name)) return F8;
            if ("LSHIFT".equals(name)) return LSHIFT;
            return 0;
        });
    }

    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }
}
