package com.Goderium.BridgeNH.config;

/** Native navigation limits. Captured per request; no world, player or path cache. */
public final class NavigationSettings {
    public static volatile int maxJumpBlocks = 1;
    public static volatile int maxDropBlocks = 3;
    public static void configure(int jump, int drop) {
        maxJumpBlocks = Math.max(0, Math.min(1, jump));
        maxDropBlocks = Math.max(0, Math.min(3, drop));
    }
}
