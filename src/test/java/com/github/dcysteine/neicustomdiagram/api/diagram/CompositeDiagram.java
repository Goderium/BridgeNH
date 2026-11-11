package com.github.dcysteine.neicustomdiagram.api.diagram;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Layout;
import java.util.List;
public class CompositeDiagram extends Diagram {
    public CompositeDiagram(Layout layout, List<Object> interactables) { super(layout, interactables); }
    public List<Diagram> activeDiagrams(DiagramState state) { throw new AssertionError("No conditional composite callback"); }
}
