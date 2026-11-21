package com.Goderium.BridgeNH.client.control;

import java.util.Locale;
import java.util.function.ToIntFunction;
import org.lwjgl.input.Keyboard;

/** Matches physical event order, including complete chords queued between two client ticks. */
public final class EmergencyReleaseKey {
    public static final String DEFAULT = "CTRL+SHIFT+L";
    private static final int CTRL = 1, SHIFT = 2, ALT = 4;
    private final int trigger, modifiers;
    private final String name;
    private int heldSides;
    private boolean consumed;

    private EmergencyReleaseKey(int trigger, int modifiers, String name) {
        this.name = name;
        this.trigger = trigger;
        this.modifiers = modifiers;
    }

    public static EmergencyReleaseKey parse(String value, ToIntFunction<String> keyIndex) {
        int modifiers = 0, trigger = 0;
        String primary = "";
        for (String part : value.toUpperCase(Locale.ROOT).split("\\+", -1)) {
            String name = part.trim();
            int modifier = "CTRL".equals(name) ? CTRL : "SHIFT".equals(name) ? SHIFT : "ALT".equals(name) ? ALT : 0;
            if (modifier != 0) {
                if ((modifiers & modifier) != 0) throw invalid();
                modifiers |= modifier;
            } else {
                int key = keyIndex.applyAsInt(name);
                if (trigger != 0 || key <= 0 || side(key) != 0) throw invalid();
                trigger = key;
                primary = name;
            }
        }
        if (trigger == 0) throw invalid();
        String name = ((modifiers & CTRL) != 0 ? "Ctrl+" : "") + ((modifiers & SHIFT) != 0 ? "Shift+" : "")
                + ((modifiers & ALT) != 0 ? "Alt+" : "") + primary;
        return new EmergencyReleaseKey(trigger, modifiers, name);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use one key name with optional Ctrl, Shift or Alt modifiers.");
    }

    private static int side(int key) {
        switch (key) {
            case Keyboard.KEY_LCONTROL: return CTRL;
            case Keyboard.KEY_RCONTROL: return CTRL << 3;
            case Keyboard.KEY_LSHIFT: return SHIFT;
            case Keyboard.KEY_RSHIFT: return SHIFT << 3;
            case Keyboard.KEY_LMENU: return ALT;
            case Keyboard.KEY_RMENU: return ALT << 3;
            default: return 0;
        }
    }

    public String name() { return name; }

    public void reset() { heldSides = 0; consumed = false; }

    public boolean event(int key, boolean down, boolean repeat, Runnable stop) {
        int side = side(key);
        if (side != 0) heldSides = down ? heldSides | side : heldSides & ~side;
        if (key != trigger) return false;
        if (!down) {
            boolean suppress = consumed;
            consumed = false;
            return suppress;
        }
        if (repeat) return consumed;
        consumed = ((heldSides | (heldSides >> 3)) & 7) == modifiers;
        if (consumed) stop.run();
        return consumed;
    }
}
