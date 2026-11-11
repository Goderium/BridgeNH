package com.github.dcysteine.neicustomdiagram.api.diagram;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Layout;
import java.util.List;
public class Diagram {
    protected Layout layout;
    protected List<Object> interactables;
    public Diagram(Layout layout, List<Object> interactables) { this.layout = layout; this.interactables = interactables; }
    public void draw() { throw new AssertionError("No diagram draw"); }
    public void drawBackground(DiagramState state) { throw new AssertionError("No background draw"); }
    public void drawForeground(DiagramState state) { throw new AssertionError("No foreground draw"); }
    public void refresh() { throw new AssertionError("No diagram refresh"); }
}
