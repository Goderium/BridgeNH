package com.github.dcysteine.neicustomdiagram.api.diagram.interactable;
import com.github.dcysteine.neicustomdiagram.api.diagram.DiagramState;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.DisplayComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
import java.util.List;
public class InteractiveComponentGroup {
    public final List<DisplayComponent> components;
    public final Tooltip slotTooltip;
    protected final Point position = new Point(8, 10);
    public InteractiveComponentGroup(List<DisplayComponent> components, Tooltip tooltip) { this.components = components; slotTooltip = tooltip; }
    public Point position() { return position; }
    public int currentIndex(DiagramState state) { throw new AssertionError("Current cycle is derived from existing state, not a callback"); }
    public DisplayComponent currentComponent(DiagramState state) { throw new AssertionError("No current component callback"); }
}
