package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
public class Slot {
    private final Tooltip tooltip;
    public Slot(Tooltip tooltip) { this.tooltip = tooltip; }
    public Point position() { return new Point(5, 7); }
    public int slotWidth() { return 18; }
    public Tooltip tooltip() { return tooltip; }
    public Object drawFunction() { return null; }
}
