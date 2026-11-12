package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.Goderium.BridgeNH.loader.rfb.WindowNativeHooks;
import com.Goderium.BridgeNH.loader.rfb.WindowFocusScope;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

/** Runtime-only window control via lwjgl3ify's owning thread and public SDL API. No OS key injection. */
public final class ClientWindowControls {
    public static final int VERSION = 2;

    private static final class Api {
        final Class<?> display, sdl, executor;
        Api() throws ClassNotFoundException {
            ClassLoader loader = ClientWindowControls.class.getClassLoader();
            display = Class.forName("org.lwjglx.opengl.Display", false, loader);
            sdl = Class.forName("org.lwjgl.sdl.SDLVideo", false, loader);
            executor = Class.forName("me.eigenraven.lwjgl3ify.client.MainThreadExec", false, loader);
        }
        Object call(Class<?> type, String name, Object... args) { return ExpandedGuiApi.call(type, name, args); }
        long window() { return ((Number) call(display, "getWindow")).longValue(); }
        long flag(String name) {
            try { return ((Number) sdl.getField(name).get(null)).longValue(); }
            catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Required native window flag unavailable: " + name); }
        }
        void main(Runnable action) {
            final AtomicReference<RuntimeException> failure = new AtomicReference<RuntimeException>();
            call(executor, "runOnMainThread", (Runnable) () -> {
                try { action.run(); } catch (RuntimeException error) { failure.set(error); }
            });
            if (failure.get() != null) throw failure.get();
        }
        JsonObject state() {
            final JsonObject result = new JsonObject();
            main(() -> {
                long window = window();
                if (window == 0) throw new BridgeException("not_ready", "Native SDL window is not created.");
                long flags = ((Number) call(sdl, "SDL_GetWindowFlags", window)).longValue();
                boolean maximized = (flags & flag("SDL_WINDOW_MAXIMIZED")) != 0;
                boolean minimized = (flags & flag("SDL_WINDOW_MINIMIZED")) != 0;
                boolean nativeFullscreen = (flags & flag("SDL_WINDOW_FULLSCREEN")) != 0;
                boolean decorated = (flags & flag("SDL_WINDOW_BORDERLESS")) == 0;
                String backendMode = String.valueOf(ExpandedGuiApi.field(null, display, "displayWindowed"));
                boolean manualBorderless = Boolean.TRUE.equals(ExpandedGuiApi.field(null, display, "windowsBorderlessActive"));
                boolean borderless = manualBorderless || ("BORDERLESS".equals(backendMode) && nativeFullscreen);
                result.addProperty("available", true); result.addProperty("windowId", Long.toHexString(window));
                result.addProperty("maximized", maximized); result.addProperty("minimized", minimized); result.addProperty("decorated", decorated);
                result.addProperty("borderlessFullscreen", borderless); result.addProperty("nativeFullscreen", nativeFullscreen);
                result.addProperty("backendMode", backendMode);
                result.addProperty("mode", mode(maximized, borderless, nativeFullscreen, decorated));
                result.addProperty("resizable", (flags & flag("SDL_WINDOW_RESIZABLE")) != 0);
                result.addProperty("focused", (flags & flag("SDL_WINDOW_INPUT_FOCUS")) != 0);
                int[] dimensions = WindowNativeHooks.dimensions(window);
                result.addProperty("width", dimensions[0]); result.addProperty("height", dimensions[1]);
                result.addProperty("startupMode", WindowNativeHooks.startupMode());
                result.addProperty("startupModeApplied", WindowNativeHooks.startupApplied());
                result.addProperty("taskbarButtonRequested", WindowFocusScope.taskbarButtonRequested(window));
                result.addProperty("focusProtection", WindowFocusScope.supported() ? "scoped_Win32_noactivate_and_nozorder" : "unavailable_on_this_platform");
                result.addProperty("source", "SDL_GetWindowFlags_on_lwjgl3ify_MainThreadExec_and_actual_Display_mode");
            });
            return result;
        }
    }

    static String mode(boolean maximized, boolean borderless, boolean fullscreen, boolean decorated) {
        if (borderless) return "borderless_fullscreen";
        if (fullscreen) return "game_fullscreen";
        if (maximized) return "maximized";
        return decorated ? "windowed" : "undecorated_windowed";
    }

    static JsonObject inspect(Minecraft client) {
        JsonObject result;
        try { result = new Api().state(); }
        catch (ClassNotFoundException | LinkageError | RuntimeException error) {
            result = new JsonObject(); result.addProperty("available", false); result.addProperty("mode", "unknown");
            result.addProperty("reason", JsonData.bounded(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), 512));
        }
        result.addProperty("version", VERSION); result.addProperty("observedAtMillis", System.currentTimeMillis());
        result.addProperty("gameFullscreen", client.isFullScreen());
        JsonArray actions = new JsonArray();
        if (result.get("available").getAsBoolean()) {
            actions.add(new JsonPrimitive("windowed"));
            if (!client.isFullScreen() && !result.get("borderlessFullscreen").getAsBoolean() && !result.get("nativeFullscreen").getAsBoolean()) {
                actions.add(new JsonPrimitive("restore"));
                actions.add(new JsonPrimitive("minimize"));
                if (result.get("resizable").getAsBoolean()) actions.add(new JsonPrimitive("resize"));
                if (result.get("resizable").getAsBoolean()) actions.add(new JsonPrimitive("maximize"));
            }
        }
        result.add("supportedActions", actions);
        result.addProperty("scope", "Live window flags and backend intent, independent of config preferences. game_fullscreen does not prove driver-exclusive presentation. Controls do not save options or change the borderless preference; use windowed explicitly before maximize/restore if fullscreen is active.");
        return result;
    }

    public static JsonObject perform(Minecraft client, JsonObject args) {
        String action = ExpandedGuiApi.option(args, "action", null, "maximize", "restore", "windowed", "minimize", "resize");
        ExpandedGuiApi.keys(args, "expectedSessionId", "expectedWindowId", "expectedMode", "width", "height");
        final int width = action.equals("resize") ? JsonData.integer(args, "width", 256, 8192) : 0;
        final int height = action.equals("resize") ? JsonData.integer(args, "height", 144, 8192) : 0;
        if (!action.equals("resize") && (args.has("width") || args.has("height")))
            throw new BridgeException("invalid_args", "Dimensions apply only to resize.");
        JsonObject before = inspect(client);
        if (!before.get("available").getAsBoolean()) throw new BridgeException("unsupported", "Native SDL window control is unavailable.");
        if (!ExpandedGuiApi.string(args, "expectedWindowId", 64).equals(before.get("windowId").getAsString())
                || !ExpandedGuiApi.string(args, "expectedMode", 64).equals(before.get("mode").getAsString()))
            throw new BridgeException("window_changed", "Current window identity/mode differs from the observed state; no window action submitted.");
        GuiControlAdapter.neutralInput();
        if (!action.equals("windowed") && (client.isFullScreen() || before.get("borderlessFullscreen").getAsBoolean() || before.get("nativeFullscreen").getAsBoolean()))
            throw new BridgeException("invalid_state", "Window actions require windowed mode; explicitly request windowed first. Fullscreen is never toggled implicitly.");
        if ((action.equals("maximize") || action.equals("resize")) && !before.get("resizable").getAsBoolean()) throw new BridgeException("unsupported", "Native window is not resizable.");
        boolean submitted = false, synchronizedWindow = true;
        long foregroundBefore = WindowFocusScope.foreground();
        if (action.equals("windowed")) {
            if (before.get("nativeFullscreen").getAsBoolean() || before.get("borderlessFullscreen").getAsBoolean()) {
                if (!client.isFullScreen()) throw new BridgeException("invalid_state", "Minecraft and native fullscreen disagree; no toggle was issued.");
            }
            if (client.isFullScreen()) { client.toggleFullscreen(); submitted = true; }
        } else {
            try {
                Api api = new Api(); final boolean[] receipt = new boolean[2];
                api.main(() -> {
                    long window = api.window();
                    if (!Long.toHexString(window).equals(before.get("windowId").getAsString()))
                        throw new BridgeException("window_changed", "Window was replaced before the native call.");
                    if (action.equals("maximize")) receipt[0] = WindowNativeHooks.maximize(window);
                    else if (action.equals("minimize")) receipt[0] = WindowNativeHooks.minimize(window);
                    else if (action.equals("resize")) receipt[0] = WindowNativeHooks.resize(window, width, height);
                    else receipt[0] = WindowNativeHooks.restore(window);
                    if (receipt[0]) receipt[1] = Boolean.TRUE.equals(api.call(api.sdl, "SDL_SyncWindow", window));
                });
                submitted = receipt[0]; synchronizedWindow = receipt[1];
            } catch (ClassNotFoundException error) { throw new BridgeException("unsupported", "Native SDL window control disappeared."); }
        }
        JsonObject after = inspect(client), result = new JsonObject();
        result.addProperty("action", action); result.addProperty("submitted", submitted); result.addProperty("nativeSyncCompleted", synchronizedWindow);
        result.add("before", before); result.add("after", after);
        boolean available = after.get("available").getAsBoolean();
        boolean confirmed = available && !after.get("gameFullscreen").getAsBoolean() && !after.get("nativeFullscreen").getAsBoolean() && !after.get("borderlessFullscreen").getAsBoolean();
        if (confirmed && action.equals("maximize")) confirmed = after.get("maximized").getAsBoolean() && !after.get("minimized").getAsBoolean();
        if (confirmed && action.equals("restore")) confirmed = !after.get("maximized").getAsBoolean() && !after.get("minimized").getAsBoolean();
        if (confirmed && action.equals("minimize")) confirmed = after.get("minimized").getAsBoolean();
        if (confirmed && action.equals("resize")) confirmed = after.get("width").getAsInt() == width && after.get("height").getAsInt() == height;
        result.addProperty("foregroundPreserved", foregroundBefore != 0 && foregroundBefore == WindowFocusScope.foreground());
        result.addProperty("focusProtection", WindowFocusScope.supported() ? "scoped_Win32_noactivate_and_nozorder" : "unavailable_on_this_platform");
        if (action.equals("resize")) { result.addProperty("requestedWidth", width); result.addProperty("requestedHeight", height); }
        result.addProperty("confirmed", confirmed); result.addProperty("configurationSaved", false);
        result.addProperty("nativeAction", action.equals("windowed") ? "Minecraft.toggleFullscreen_only_when_leaving_fullscreen" : "SDL_native_window_request_and_flag_readback");
        result.addProperty("completion", "Native window state readback; client/framebuffer dimensions may follow on the next normal resize event. Re-read mcb_window or snapshot; do not replay an unconfirmed request.");
        return result;
    }
}
