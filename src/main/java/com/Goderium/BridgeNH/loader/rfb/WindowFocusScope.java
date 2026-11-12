package com.Goderium.BridgeNH.loader.rfb;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WindowProc;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Owning-thread, scoped protection. Other windows are never activated or reordered. */
@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
public final class WindowFocusScope implements AutoCloseable {
    private static final int NO_ACTIVATE = 0x08000000, EX_STYLE = -20, WINDOW_PROC = -4;
    private static final List<WindowProc> RETAINED = new ArrayList<WindowProc>();
    private final long hwnd, foreground, originalStyle, window;
    private boolean restoreFocusable;
    private long previousProc;
    private WindowProc callback;
    private boolean closed;

    public static boolean supported() { return Platform.get() == Platform.WINDOWS; }
    public static long foreground() {
        return Platform.get() == Platform.WINDOWS
            ? JNI.callP(User32.getLibrary().getFunctionAddress("GetForegroundWindow")) : 0;
    }
    public static long handle(long window) {
        return SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
    }
    public static void ensureTaskbarButton(long window) {
        if (!supported()) return;
        long hwnd = handle(window);
        // NOACTIVATE at creation suppresses a taskbar button unless APPWINDOW is explicit.
        long style = User32.GetWindowLongPtr(hwnd, EX_STYLE);
        User32.SetWindowLongPtr(null, hwnd, EX_STYLE, style | 0x00040000L); // WS_EX_APPWINDOW
        User32.SetWindowPos(null, hwnd, 0, 0, 0, 0, 0, 0x0237); // FRAMECHANGED, no move/size/activation/Z change.
        if (!taskbarButtonRequested(window)) throw new IllegalStateException("Win32 taskbar style unavailable");
    }
    public static boolean taskbarButtonRequested(long window) {
        return supported() && (User32.GetWindowLongPtr(handle(window), EX_STYLE) & 0x00040000L) != 0;
    }
    public WindowFocusScope(long window) {
        this.window = window;
        if (Platform.get() != Platform.WINDOWS) { hwnd = foreground = originalStyle = 0; return; }
        hwnd = handle(window);
        if (hwnd == 0) throw new IllegalStateException("SDL window has no Win32 handle");
        foreground = foreground();
        originalStyle = User32.GetWindowLongPtr(hwnd, EX_STYLE);
        try {
            if (foreground != hwnd) {
                if ((SDL_GetWindowFlags(window) & SDL_WINDOW_NOT_FOCUSABLE) == 0) {
                    if (!SDL_SetWindowFocusable(window, false)) throw new IllegalStateException("SDL focus guard failed");
                    restoreFocusable = true;
                }
                set(EX_STYLE, originalStyle | NO_ACTIVATE);
            }
            callback = WindowProc.create((target, message, wParam, lParam) -> {
                if (!closed && message == 0x0046 && lParam != 0) {
                    // WINDOWPOS: two pointers, four INTs, then UINT flags (both Win32 and Win64).
                    long flags = lParam + 2L * Pointer.POINTER_SIZE + 16;
                    MemoryUtil.memPutInt(flags, MemoryUtil.memGetInt(flags) | 0x0004); // SWP_NOZORDER
                }
                return User32.nCallWindowProc(previousProc, target, message, wParam, lParam);
            });
            previousProc = set(WINDOW_PROC, callback.address());
        } catch (RuntimeException | Error error) { close(); throw error; }
    }
    private long set(int index, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.callocInt(1);
            long previous = User32.SetWindowLongPtr(error, hwnd, index, value);
            if (previous == 0 && error.get(0) != 0) throw new IllegalStateException("Win32 window guard failed: " + error.get(0));
            return previous;
        }
    }
    public boolean foregroundPreserved() { return hwnd != 0 && foreground() == foreground; }
    @Override public void close() {
        if (closed || hwnd == 0) return;
        closed = true;
        try {
            if (callback != null && previousProc != 0) {
                if (User32.GetWindowLongPtr(hwnd, WINDOW_PROC) == callback.address()) {
                    set(WINDOW_PROC, previousProc); callback.free();
                } else RETAINED.add(callback); // Preserve an intervening subclass's valid callback chain.
            } else if (callback != null) callback.free();
        } finally {
            try {
                if (restoreFocusable && !SDL_SetWindowFocusable(window, true))
                    throw new IllegalStateException("SDL focus restoration failed");
            } finally {
                long current = User32.GetWindowLongPtr(hwnd, EX_STYLE);
                set(EX_STYLE, (current & ~((long) NO_ACTIVATE)) | (originalStyle & NO_ACTIVATE));
            }
        }
    }
}
