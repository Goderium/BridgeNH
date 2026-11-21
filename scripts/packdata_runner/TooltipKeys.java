package com.Goderium.BridgeNH.packtools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Scope used only by the explicitly launched disposable export client, on its client tick. */
final class TooltipKeys implements AutoCloseable {
    private static final int[] KEYS = {42,54,29,157,56,184};
    final String source;
    private final AutoCloseable undo;
    private TooltipKeys(String source, AutoCloseable undo) { this.source = source; this.undo = undo; }
    @Override public void close() {
        try { undo.close(); }
        catch (Exception failure) { throw new AssertionError("Could not restore the scoped tooltip modifier state", failure); }
    }

    static TooltipKeys sample(String mode, Method bridge) throws Exception {
        Class<?> keyboard;
        try { keyboard = Class.forName("org.lwjglx.input.Keyboard"); }
        catch (ClassNotFoundException legacy) { keyboard = Class.forName("org.lwjgl.input.Keyboard"); }
        Method down = keyboard.getMethod("isKeyDown", int.class);
        boolean shift = mode.equals("shift"), ctrl = mode.equals("ctrl");
        if (bridge != null) {
            AutoCloseable scope = (AutoCloseable)bridge.invoke(null, shift, ctrl, false);
            boolean success = false;
            try {
                success = matches(down, shift, ctrl);
                if (success) return new TooltipKeys("BridgeNH scoped Keyboard.isKeyDown", scope);
            } finally { if (!success) scope.close(); }
        }
        if (matches(down,shift,ctrl)) return new TooltipKeys("observed physical modifiers", () -> {});
        // The GTNH lwjgl3ify API used by Alu Hover exposes the current SDL keyboard snapshot.
        // Scope a copy to one getter and restore its original reference before returning to the game.
        Field state = keyboard.getField("sdlKeyPressedArray");
        Method scancode = Class.forName("org.lwjglx.input.KeyCodes").getMethod("lwjglToSdlScancode",int.class);
        ByteBuffer original = (ByteBuffer)state.get(null);
        int size = original == null ? 512 : original.limit();
        ByteBuffer copy = ByteBuffer.allocate(size);
        for (int i = 0; original != null && i < size; i++) copy.put(i,original.get(i));
        for (int key : KEYS) {
            int index = (Integer)scancode.invoke(null,key);
            if (index <= 0 || index >= size) throw new IllegalStateException("SDL modifier key unavailable");
            copy.put(index, (byte)((key == 42 && shift) || (key == 29 && ctrl) ? 1 : 0));
        }
        state.set(null,copy);
        boolean success = false;
        try {
            success = matches(down,shift,ctrl);
            if (!success) throw new IllegalStateException("SDL modifier readback differs");
            return new TooltipKeys("lwjgl3ify scoped SDL key snapshot", () -> state.set(null,original));
        } finally { if (!success) state.set(null,original); }
    }
    private static boolean matches(Method down, boolean shift, boolean ctrl) throws Exception {
        boolean[] actual = new boolean[KEYS.length];
        for (int i=0; i<KEYS.length; i++) actual[i] = (Boolean)down.invoke(null, KEYS[i]);
        return (actual[0] || actual[1]) == shift && (actual[2] || actual[3]) == ctrl && !actual[4] && !actual[5];
    }
}
