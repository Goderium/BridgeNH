package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.util.Iterator;
import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.call;

/** MUI2 ScrollArea takes widget-local positions, including for its scrollbar thumb. */
final class MuiScrollControls {
    private MuiScrollControls() {}
    static boolean supports(Object widget) {
        return ExpandedGuiApi.is(widget, "com.cleanroommc.modularui.widget.AbstractScrollWidget");
    }
    private static Object axis(Object area, boolean vertical) {
        return call(area, vertical ? "getScrollY" : "getScrollX");
    }
    private static boolean active(Object data, Object area) {
        return data != null && Boolean.TRUE.equals(call(data, "isScrollBarActive", area));
    }
    static void describe(Object widget, JsonObject dto, JsonArray actions) {
        Object area = call(widget, "getScrollArea");
        JsonObject axes = new JsonObject();
        for (boolean vertical : new boolean[]{true, false}) {
            Object data = axis(area, vertical);
            if (!active(data, area)) continue;
            JsonObject state = new JsonObject();
            state.addProperty("offset", (Integer) call(data, "getScroll"));
            state.addProperty("maximum", (Integer) call(data, "getScrollSize") - (Integer) call(data, "getFullVisibleSize", area));
            state.addProperty("animating", (Boolean) call(data, "isAnimating"));
            if (state.get("animating").getAsBoolean()) state.addProperty("target", (Integer) call(data, "getAnimatingTo"));
            axes.add(vertical ? "vertical" : "horizontal", state);
        }
        dto.addProperty("kind", "scroll_area");
        dto.add("scrollAxes", axes);
        if (!axes.entrySet().isEmpty()) {
            actions.add(new JsonPrimitive("scroll"));
            actions.add(new JsonPrimitive("drag_scrollbar"));
        }
    }
    static JsonObject perform(Object widget, JsonObject args, String action) {
        Object panel = call(widget, "getPanel");
        Object manager = call(call(panel, "getScreen"), "getPanelManager");
        Iterator<?> panels = ((Iterable<?>) call(manager, "getOpenPanels")).iterator();
        if (!panels.hasNext() || panels.next() != panel || Boolean.TRUE.equals(call(call(widget, "getContext"), "hasDraggable")))
            throw new BridgeException("input_busy", "The active panel must own the scroll area and have no drag in progress.");
        boolean vertical = "vertical".equals(ExpandedGuiApi.option(args, "axis", null, "vertical", "horizontal"));
        Object area = call(widget, "getScrollArea"), data = axis(area, vertical), other = axis(area, !vertical);
        if (!active(data, area)) throw new BridgeException("disabled", "The requested scrollbar is inactive.");
        if (Boolean.TRUE.equals(call(data, "isDragging")) || other != null && Boolean.TRUE.equals(call(other, "isDragging")))
            throw new BridgeException("input_busy", "A native scroll thumb is already held.");
        JsonObject out = new JsonObject();
        out.addProperty("offsetBefore", (Integer) call(data, "getScroll"));
        if ("scroll".equals(action)) {
            int delta = "up".equals(ExpandedGuiApi.option(args, "direction", null, "up", "down")) ? 120 : -120;
            out.addProperty("accepted", (Boolean) call(area, "mouseScroll", 0, 0, delta, !vertical));
        } else {
            int percent = JsonData.integer(args, "percent", 0, 100);
            if (Boolean.TRUE.equals(call(data, "isAnimating"))) throw new BridgeException("input_busy", "Wait for the native scroll animation to finish.");
            int track = (Integer) call(data, "getFullVisibleSize", area);
            int thumb = (Integer) call(data, "getScrollBarLength", area);
            if (thumb < 1 || track <= thumb) throw new BridgeException("disabled", "This thumb has no draggable range.");
            int start = (Integer) call(data, "getScrollBarStart", area, thumb, track) + thumb / 2;
            int end = Math.round((track - thumb) * (percent / 100f)) + thumb / 2;
            Rectangle bounds = (Rectangle) area;
            int cross = Boolean.TRUE.equals(call(data, "isOnAxisStart")) ? 0 : (vertical ? bounds.width : bounds.height) - 1;
            int x = vertical ? cross : start, y = vertical ? start : cross;
            if (!Boolean.TRUE.equals(call(data, "isInsideScrollbarArea", area, x, y)))
                throw new BridgeException("target_unverified", "The native thumb is no longer at the observed position.");
            try {
                boolean pressed = Boolean.TRUE.equals(call(area, "mouseClicked", x, y));
                boolean held = pressed && Boolean.TRUE.equals(call(data, "isDragging"));
                if (held) call(area, "drag", vertical ? cross : end, vertical ? end : cross);
                out.addProperty("accepted", held);
            } finally {
                call(area, "mouseReleased", x, y);
            }
        }
        out.addProperty("offsetAfter", (Integer) call(data, "getScroll"));
        return out;
    }
}
