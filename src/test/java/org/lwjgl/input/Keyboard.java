package org.lwjgl.input;

/** Tooltip route tests have no physical input device; a held key must never be invented. */
public final class Keyboard {
    public static boolean isCreated() { return false; }
    public static boolean isKeyDown(int key) { throw new AssertionError("No physical keyboard exists in the headless fixture"); }
}
