package com.Goderium.BridgeNH.client.gui;
import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import java.util.Collections;
import java.util.List;
public final class WidgetFixtures {
    public static class Screen implements IMuiScreen {
        public final Widget root = new Widget(); public final Modular screen = new Modular(root);
        public Modular getScreen() { return screen; }
    }
    public static class Modular {
        public final Panels manager;
        public final ButtonContext context = new ButtonContext();
        private com.cleanroommc.modularui.widgets.ButtonWidget pressed;
        private boolean accepted;
        public Modular(Widget root) { manager = new Panels(root); }
        public Panels getPanelManager() { return manager; }
        public ButtonContext getContext() { return context; }
        public void onFrameUpdate() {
            if (context.hovered != null) context.hovered.hovering = false;
            context.hovered = null;
            for (Object child : manager.root.children) {
                if (child instanceof com.cleanroommc.modularui.widgets.ButtonWidget) {
                    com.cleanroommc.modularui.widgets.ButtonWidget button = (com.cleanroommc.modularui.widgets.ButtonWidget) child;
                    if (!context.occluded && button.valid && button.enabled && button.getArea().contains(context.x, context.y)) {
                        context.hovered = button; button.hovering = true; break;
                    }
                }
            }
        }
        public boolean onMousePressed(int button) {
            pressed = context.hovered;
            if (pressed == null) return false;
            String result = String.valueOf(pressed.onMousePressed(button));
            accepted = result.equals("SUCCESS") || result.equals("ACCEPT");
            return result.equals("SUCCESS") || result.equals("STOP");
        }
        public boolean onMouseRelease(int button) {
            if (pressed == null) return false;
            com.cleanroommc.modularui.widgets.ButtonWidget target = pressed; pressed = null;
            if (accepted && context.hovered == target) target.onMouseTapped(button);
            return target.onMouseRelease(button);
        }
    }
    public static class ButtonContext {
        public int x, y;
        public float partial;
        public boolean occluded, dragging;
        public com.cleanroommc.modularui.widgets.ButtonWidget hovered;
        public void updateState(int x, int y, float partial) { this.x=x; this.y=y; this.partial=partial; }
        public int getAbsMouseX() { return x; }
        public int getAbsMouseY() { return y; }
        public float getPartialTicks() { return partial; }
        public boolean hasDraggable() { return dragging; }
        public Object getTopHovered() { return hovered; }
    }
    public static class Panels { public final Widget root; public Panels(Widget root) { this.root = root; } public List<Widget> getOpenPanels() { return Collections.singletonList(root); } }
    public static class Handler { public boolean test(String text) { return text.length() <= 12 && !text.contains("!"); } }
    public static class StringValue { public String value = ""; public String getStringValue() { return value; } public Object getValue() { return value; } }
    public static class Context {
        public TextFieldWidget focused;
        public void focus(Object value) { focused = (TextFieldWidget) value; focused.focused = true; }
        public void removeFocus() { if (focused != null) { focused.value.value = focused.text.trim(); focused.commits++; focused.focused = false; focused = null; } }
    }
}
