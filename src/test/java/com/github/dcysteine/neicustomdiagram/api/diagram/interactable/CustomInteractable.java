package com.github.dcysteine.neicustomdiagram.api.diagram.interactable;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
public class CustomInteractable {
    public final Object drawable;
    public final Tooltip tooltip;
    public CustomInteractable(Object drawable, Tooltip tooltip) { this.drawable = drawable; this.tooltip = tooltip; }
    public void draw() { throw new AssertionError("No custom draw callback"); }
}
