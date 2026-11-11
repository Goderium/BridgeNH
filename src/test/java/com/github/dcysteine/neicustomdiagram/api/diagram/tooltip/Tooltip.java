package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;
import java.util.List;
public class Tooltip {
    private final List<TooltipLine> lines;
    public Tooltip(List<TooltipLine> lines) { this.lines = lines; }
    public List<TooltipLine> lines() { return lines; }
}
