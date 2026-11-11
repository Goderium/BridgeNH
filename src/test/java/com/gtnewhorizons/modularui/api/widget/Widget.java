package com.gtnewhorizons.modularui.api.widget;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
public class Widget {
    public boolean enabled = true;
    public String name = "legacy fixture";
    public ModularWindow window;
    public boolean isEnabled() { return enabled; }
    public boolean isInitialised() { return window != null; }
    public String getInternalName() { return name; }
    public ModularWindow getWindow() { return window; }
    public ModularGui.Context getContext() { return window.context; }
    public Rectangle getArea() { return new Rectangle(12, 34, 80, 16); }
    public List<Object> getTooltip() { return Collections.emptyList(); }
    public boolean isFocused() { return getContext().getCursor().getFocused() == this; }
    public boolean shouldGetFocus() { return true; }
    public void onRemoveFocus() { }
    public void removeFocus() { getContext().getCursor().removeFocus(this); }
}
