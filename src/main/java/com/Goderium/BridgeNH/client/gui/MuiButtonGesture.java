package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.awt.Rectangle;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiScreen;

/** Fixed MUI2 public input lifecycle; native layout hit testing owns hover and pressed state. */
final class MuiButtonGesture {
    private static final String MUI = "com.cleanroommc.modularui.";
    // Center, then the four inset corners for clipped rows and labels over a button.
    private static final int[][] SAMPLES = {{1,1}, {0,0}, {2,0}, {0,2}, {2,2}};
    private static int inset(int length, int fraction) {
        return fraction == 1 ? length / 2 : fraction == 0 ? Math.min(1,length-1) : Math.max(0,length-2);
    }

    private static boolean locate(Object wrapper, Object screen, Object context, Object widget, Rectangle area,
            float partial, BooleanSupplier current) {
        boolean nativePanel = ExpandedGuiApi.is(screen, MUI + "screen.ModularScreen");
        Object matrix = null;
        if (nativePanel) {
            Object located = ExpandedGuiApi.call(ExpandedGuiApi.load(widget, MUI + "screen.viewport.LocatedWidget"), "of", widget);
            matrix = ExpandedGuiApi.call(located, "getTransformationMatrix");
        }
        for (int[] sample : SAMPLES) {
            int u = inset(area.width, sample[0]), v = inset(area.height, sample[1]);
            int x = matrix == null ? area.x + u : (Integer) ExpandedGuiApi.call(matrix, "transformX", (float) u, (float) v);
            int y = matrix == null ? area.y + v : (Integer) ExpandedGuiApi.call(matrix, "transformY", (float) u, (float) v);
            if (x < 0 || y < 0 || wrapper instanceof GuiScreen && (x >= ((GuiScreen)wrapper).width || y >= ((GuiScreen)wrapper).height)) continue;
            ExpandedGuiApi.call(context, "updateState", x, y, partial);
            ExpandedGuiApi.call(screen, "onFrameUpdate");
            if (!current.getAsBoolean()) return false;
            if (!nativePanel) return ExpandedGuiApi.call(context, "getTopHovered") == widget;
            Object panel = ExpandedGuiApi.call(widget, "getPanel");
            Object manager = ExpandedGuiApi.call(screen, "getPanelManager");
            Iterator<?> panels = ((Iterable<?>) ExpandedGuiApi.call(manager, "getOpenPanels")).iterator();
            if (!panels.hasNext() || panels.next() != panel) return false;
            // Text, layout and drawable children can cover the entire row.
            // Follow the native press order through passive click-through children.
            for (Object located : (Iterable<?>) ExpandedGuiApi.call(panel, "getHovering")) {
                Object node = ExpandedGuiApi.call(located, "getElement");
                if (node == null || !Boolean.TRUE.equals(ExpandedGuiApi.call(node, "isValid"))) continue;
                if (ExpandedGuiApi.call(located, "getAdditionalHoverInfo") != null) break;
                if (node == widget) return true;
                if (ExpandedGuiApi.is(node, MUI + "api.widget.Interactable")
                        || ExpandedGuiApi.is(node, MUI + "api.widget.IDraggable")
                        || ExpandedGuiApi.is(node, MUI + "screen.ModularPanel")
                        || ExpandedGuiApi.is(node, MUI + "integration.recipeviewer.RecipeViewerGhostIngredientSlot")
                        || !Boolean.TRUE.equals(ExpandedGuiApi.call(node, "canClickThrough"))) break;
            }
        }
        return false;
    }

    static JsonObject activate(Object wrapper, Object widget, int button, BooleanSupplier current) {
        Object screen = ExpandedGuiApi.call(wrapper, "getScreen");
        Object context = ExpandedGuiApi.call(screen, "getContext");
        Object raw = ExpandedGuiApi.call(widget, "getArea");
        if (!(raw instanceof Rectangle)) throw new BridgeException("unsupported", "Native MUI2 button bounds are unavailable.");
        Rectangle area = new Rectangle((Rectangle) raw);
        if (area.width <= 0 || area.height <= 0)
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
            boolean hit = locate(wrapper, screen, context, widget, area, partial, current);
            if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "Native MUI2 screen changed before pressing.");
            if (!area.equals(ExpandedGuiApi.call(widget, "getArea"))
                    || !hit)
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
