package com.Goderium.BridgeNH.loader.rfb;

import java.util.function.BooleanSupplier;

/** Shared by LWJGL and the mod across loaders. Only the bound client thread handles events. */
public final class PhysicalKeyboardEvents {
    private static final ThreadLocal<BooleanSupplier> FILTER = new ThreadLocal<>();
    private PhysicalKeyboardEvents() {}

    public static void bind(BooleanSupplier listener) { FILTER.set(listener); }
    public static boolean consume() {
        BooleanSupplier listener = FILTER.get();
        return listener != null && listener.getAsBoolean();
    }
}
