package com.Goderium.BridgeNH.loader.rfb;

import static org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus;
import static org.lwjgl.sdl.SDLMouse.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Checked on lwjgl3ify's SDL owner thread, immediately before releasing this process's window. */
@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
public final class InactiveMouseHandoff {
    private InactiveMouseHandoff() {}

    public static final class Refusal extends RuntimeException {
        public final String code;
        Refusal(String code, String message) { super(message); this.code = code; }
    }

    static void requireInactive(long window, long hwnd, long foreground, long flags, long keyboardFocus, long mouseFocus) {
        if (window == 0 || hwnd == 0 || foreground == 0 || flags == 0)
            throw new Refusal("mouse_handoff_unavailable", "Window identity or native foreground ownership could not be established.");
        if (hwnd == foreground || keyboardFocus == window || mouseFocus == window
                || (flags & (SDL_WINDOW_INPUT_FOCUS | SDL_WINDOW_MOUSE_FOCUS | SDL_WINDOW_MOUSE_CAPTURE)) != 0)
            throw new Refusal("mouse_manual_control", "The bound window has foreground focus or is receiving native input. Its mouse capture remains with the player.");
    }

    public static void release(long window) {
        if (window == 0 || !WindowFocusScope.supported() || !"windows".equals(SDL_GetCurrentVideoDriver()))
            throw new Refusal("mouse_handoff_unavailable", "This SDL backend cannot verify an inactive Win32 client window.");
        requireInactive(window, WindowFocusScope.handle(window), WindowFocusScope.foreground(),
            SDL_GetWindowFlags(window), SDL_GetKeyboardFocus(), SDL_GetMouseFocus());
        // An inactive window can retain lwjgl3ify's logical grab even after SDL released relative mode.
        if (SDL_GetWindowRelativeMouseMode(window) && !SDL_SetWindowRelativeMouseMode(window, false))
            throw new Refusal("mouse_handoff_unconfirmed", "SDL did not confirm release of relative mouse mode on the bound window.");
        if (SDL_GetWindowRelativeMouseMode(window))
            throw new Refusal("mouse_handoff_unconfirmed", "The bound window still reports relative mouse mode after release.");
    }
}
