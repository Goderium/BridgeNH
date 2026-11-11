package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
public class ComponentLabel {
    private final Object component;
    public ComponentLabel(Object component) { this.component = component; }
    public Object component() { return component; }
    public Point position() { return new Point(2, 4); }
}
