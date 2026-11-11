package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
import java.util.Optional;
public class DisplayComponent {
    private final Object component;
    private final Tooltip tooltip;
    private final Integer quantity;
    public DisplayComponent(Object component, Tooltip tooltip) { this(component, tooltip, Integer.valueOf(12)); }
    public DisplayComponent(Object component, Tooltip tooltip, Integer quantity) { this.component = component; this.tooltip = tooltip; this.quantity = quantity; }
    public Object component() { return component; }
    public Optional<Integer> stackSize() { return Optional.ofNullable(quantity); }
    public Optional<String> additionalInfo() { return Optional.of("A\uD83D\uDE80B additional info"); }
    public Tooltip additionalTooltip() { return tooltip; }
}
