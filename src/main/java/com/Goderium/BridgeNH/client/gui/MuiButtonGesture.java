package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.awt.Rectangle;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiScreen;

/** Fixed MUI2 public input lifecycle; native layout hit testing owns hover and pressed state. */
final class MuiButtonGesture {
    static JsonObject activate(Object wrapper, Object widget, int button, BooleanSupplier current) {
        Object screen = ExpandedGuiApi.call(wrapper, "getScreen");
        Object context = ExpandedGuiApi.call(screen, "getContext");
        Object raw = ExpandedGuiApi.call(widget, "getArea");
        if (!(raw instanceof Rectangle)) throw new BridgeException("unsupported", "Native MUI2 button bounds are unavailable.");
        Rectangle area = new Rectangle((Rectangle) raw);
        int x = area.x + area.width / 2, y = area.y + area.height / 2;
        if (area.width <= 0 || area.height <= 0 || x < 0 || y < 0
                || wrapper instanceof GuiScreen && (x >= ((GuiScreen) wrapper).width || y >= ((GuiScreen) wrapper).height))
            throw new BridgeException("target_unverified", "Native MUI2 button center is outside the current screen.");
        if (Boolean.TRUE.equals(ExpandedGuiApi.call(context, "hasDraggable")))
            throw new BridgeException("input_busy", "Finish the current native MUI2 drag before activating a button.");
        int oldX = ((Number) ExpandedGuiApi.call(context, "getAbsMouseX")).intValue();
        int oldY = ((Number) ExpandedGuiApi.call(context, "getAbsMouseY")).intValue();
        float partial = ((Number) ExpandedGuiApi.call(context, "getPartialTicks")).floatValue();
        JsonObject out = new JsonObject();
        out.addProperty("dispatch", "mui2_native_screen_press_release");
        out.addProperty("osCursorModified", false);
        try {
            // This is MUI2's non-rendering layout/hover update, not a widget hover-field write.
            ExpandedGuiApi.call(context, "updateState", x, y, partial);
            ExpandedGuiApi.call(screen, "onFrameUpdate");
            if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "Native MUI2 screen changed before pressing.");
            if (!area.equals(ExpandedGuiApi.call(widget, "getArea"))
                    || ExpandedGuiApi.call(context, "getTopHovered") != widget)
                throw new BridgeException("target_unverified", "Native MUI2 hit testing does not select the requested button; no press was sent.");
            out.addProperty("targetHitVerified", true);
            out.addProperty("onMousePressed", Boolean.TRUE.equals(ExpandedGuiApi.call(screen, "onMousePressed", button)));
            if (current.getAsBoolean()) {
                // ModularPanel dispatches the accepted tap and release, with its own pressed-widget state.
                out.addProperty("onMouseRelease", Boolean.TRUE.equals(ExpandedGuiApi.call(screen, "onMouseRelease", button)));
            } else out.addProperty("remainingCallbacksSkipped", "native_screen_or_container_changed");
            return out;
        } finally {
            // Restore coordinates even if a callback closes the page; never dispatch into a replacement screen.
            ExpandedGuiApi.call(context, "updateState", oldX, oldY, partial);
            if (current.getAsBoolean()) ExpandedGuiApi.call(screen, "onFrameUpdate");
        }
    }
}
