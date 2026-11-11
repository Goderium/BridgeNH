package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.DisplayComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.Component;
public class TooltipElement {
    public enum ElementType { SPACING, TEXT, DISPLAY_COMPONENT_ICON, COMPONENT_DESCRIPTION, FORMATTING }
    private final String text;
    protected TooltipElement() { this.text = null; }
    public TooltipElement(String text) { this.text = text; }
    public ElementType type() { return ElementType.TEXT; }
    public int spacing() { return 3; }
    public String text() { return text; }
    public DisplayComponent displayComponentIcon() { throw new AssertionError("Only active tooltip element fields are read"); }
    public Component componentDescription() { throw new AssertionError("Only active tooltip element fields are read"); }
    public TextFormatting formatting() { throw new AssertionError("Only active tooltip element fields are read"); }
    public static TooltipElement generatedSpacing(int value) { return AutoOneOf_TooltipElement.spacing(value); }
    public static TooltipElement generatedText(String value) { return AutoOneOf_TooltipElement.text(value); }
    public static TooltipElement generatedIcon(DisplayComponent value) { return AutoOneOf_TooltipElement.icon(value); }
    public static TooltipElement generatedDescription(Component value) { return AutoOneOf_TooltipElement.description(value); }
    public static TooltipElement generatedFormatting(TextFormatting value) { return AutoOneOf_TooltipElement.formatting(value); }
    public static TooltipElement unverifiedGeneratedForTest() { return AutoOneOf_TooltipElement.unverified(); }
    public static int inactiveCallsForTest() { return AutoOneOf_TooltipElement.inactiveCalls; }
    public static int unverifiedCallsForTest() { return AutoOneOf_TooltipElement.unverifiedCalls; }
    public static void resetGeneratedCallsForTest() { AutoOneOf_TooltipElement.inactiveCalls = 0; AutoOneOf_TooltipElement.unverifiedCalls = 0; }
}
