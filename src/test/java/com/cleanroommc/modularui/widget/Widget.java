package com.cleanroommc.modularui.widget;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import com.cleanroommc.modularui.api.widget.ITooltip;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.RichTooltip;

public class Widget implements ITooltip, IWidget {
    public String name = "fixture"; public boolean enabled = true, valid = true;
    public final List<Object> children = new ArrayList<Object>();
    public RichTooltip tooltip;
    public Object overlay, background;
    public String getName() { return name; } public boolean isEnabled() { return enabled; } public boolean isValid() { return valid; }
    public Rectangle getArea() { return new Rectangle(10, 20, 30, 40); }
    public List<Object> getChildren() { return children; }
    public RichTooltip getTooltip() { return tooltip; }
    public Object getOverlay() { return overlay; }
    public Object getBackground() { return background; }
}
