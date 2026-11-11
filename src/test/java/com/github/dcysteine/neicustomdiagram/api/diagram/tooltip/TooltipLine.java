package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;
import java.util.List;
public class TooltipLine {
    private final List<TooltipElement> elements;
    public TooltipLine(List<TooltipElement> elements) { this.elements = elements; }
    public List<TooltipElement> elements() { return elements; }
    public int additionalSpacing() { return 1; }
}
