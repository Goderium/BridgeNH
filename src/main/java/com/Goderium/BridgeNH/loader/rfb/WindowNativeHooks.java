package com.Goderium.BridgeNH.loader.rfb;

import java.util.function.BooleanSupplier;
import static org.lwjgl.sdl.SDLHints.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Fixed SDL calls on lwjgl3ify's owning thread, used by the early Display hook and live controls. */
@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
public final class WindowNativeHooks {
    private static volatile String startupMode = "not_observed";
    private static volatile boolean startupApplied;
    private static long createdWindow;
    private WindowNativeHooks() { }
    public static String startupMode() { return startupMode; }
    public static boolean startupApplied() { return startupApplied; }

    public static long create(int properties) {
        try { return createWindow(properties); }
        catch (RuntimeException | Error error) { recordFailure(error); throw error; }
    }
    private static long createWindow(int properties) {
        startupApplied = false;
        startupMode = WindowStartupPolicy.current();
        if (!"normal".equals(startupMode)) {
            // Create hidden with normal taskbar eligibility; the first show is guarded below.
            SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN, true);
            if ("minimized".equals(startupMode)) {
                // SDL's Win32 backend shows an initially minimized window during creation,
                // before the scoped first-show guard can run. Keep it hidden until show().
                SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_MINIMIZED_BOOLEAN, !WindowFocusScope.supported());
                SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_MAXIMIZED_BOOLEAN, false);
            }
        }
        createdWindow = SDL_CreateWindowWithProperties(properties);
        if (createdWindow != 0) WindowFocusScope.ensureTaskbarButton(createdWindow);
        return createdWindow;
    }
    public static boolean show(long window) {
        try {
            boolean result = showWindow(window);
            if (!result) recordFailure(new IllegalStateException("SDL window show failed: " + org.lwjgl.sdl.SDLError.SDL_GetError()));
            return result;
        }
        catch (RuntimeException | Error error) { recordFailure(error); throw error; }
    }
    private static boolean showWindow(long window) {
        if (window != createdWindow || "normal".equals(startupMode)) {
            boolean result = SDL_ShowWindow(window); startupApplied = window == createdWindow && result; return result;
        }
        String previous = SDL_GetHint(SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN);
        try (WindowFocusScope scope = new WindowFocusScope(window)) {
            SDL_SetHintWithPriority(SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0", SDL_HINT_OVERRIDE);
            boolean result = SDL_ShowWindow(window);
            if (result && "minimized".equals(startupMode)) result = SDL_MinimizeWindow(window);
            SDL_SyncWindow(window);
            startupApplied = result;
            return result;
        } finally {
            // Restore manual activation after the guarded first show.
            SDL_SetWindowFocusable(window, true);
            if (previous == null) SDL_ResetHint(SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN);
            else SDL_SetHintWithPriority(SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, previous, SDL_HINT_OVERRIDE);
        }
    }
    private static boolean guarded(long window, BooleanSupplier action) {
        try (WindowFocusScope scope = new WindowFocusScope(window)) {
            boolean result = action.getAsBoolean();
            if (result) SDL_SyncWindow(window);
            return result;
        }
    }
    private static void revealMinimized(long window) {
        if (org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS
            && (SDL_GetWindowFlags(window) & SDL_WINDOW_MINIMIZED) != 0
            && WindowFocusScope.foreground() != WindowFocusScope.handle(window)) {
            // Windows can activate a previously maximized, minimized HWND even for
            // SW_SHOWNOACTIVATE. Hide the already minimized window before revealing
            // it; SDL receives the normal hidden/shown/restored events on this thread.
            org.lwjgl.system.windows.User32.ShowWindow(WindowFocusScope.handle(window), 0); // SW_HIDE
            org.lwjgl.system.windows.User32.ShowWindow(WindowFocusScope.handle(window), 4); // SW_SHOWNOACTIVATE
            SDL_SyncWindow(window);
        }
    }
    public static boolean maximize(long window) {
        return guarded(window, () -> { revealMinimized(window); return SDL_MaximizeWindow(window); });
    }
    public static boolean restore(long window) {
        return guarded(window, () -> {
            revealMinimized(window);
            return (SDL_GetWindowFlags(window) & (SDL_WINDOW_MINIMIZED | SDL_WINDOW_MAXIMIZED)) == 0
                || SDL_RestoreWindow(window);
        });
    }
    public static boolean minimize(long window) { return guarded(window, () -> SDL_MinimizeWindow(window)); }
    public static boolean resize(long window, int width, int height) { return guarded(window, () -> SDL_SetWindowSize(window, width, height)); }
    public static boolean fullscreen(long window, boolean enabled) { return guarded(window, () -> SDL_SetWindowFullscreen(window, enabled)); }
    public static int[] dimensions(long window) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer width = stack.callocInt(1), height = stack.callocInt(1);
            if (!SDL_GetWindowSize(window, width, height)) throw new IllegalStateException("Native window dimensions unavailable");
            return new int[] {width.get(0), height.get(0)};
        }
    }
    private static void recordFailure(Throwable error) {
        // The normal crash reporter may require a GL context that failed to initialize.
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("user.dir"), "config/codexbridge/window-startup-error.log");
            java.nio.file.Files.createDirectories(path.getParent());
            try (java.io.PrintWriter out = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(path))) {
                out.println(error.getClass().getName() + ": " + error.getMessage());
                StackTraceElement[] frames = error.getStackTrace();
                for (int i = 0; i < Math.min(frames.length, 32); i++) out.println(frames[i]);
            }
        } catch (Exception ignored) { }
    }
}
