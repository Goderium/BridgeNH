package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;

import com.github.dcysteine.neicustomdiagram.api.diagram.component.Component;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.DisplayComponent;

/** Exact installed 1.8.35 generated class names, visibility and getter declaration ownership. */
final class AutoOneOf_TooltipElement {
    static int inactiveCalls;
    static int unverifiedCalls;
    static TooltipElement spacing(int value) { return new Impl_spacing(value); }
    static TooltipElement text(String value) { return new Impl_text(value); }
    static TooltipElement icon(DisplayComponent value) { return new Impl_displayComponentIcon(value); }
    static TooltipElement description(Component value) { return new Impl_componentDescription(value); }
    static TooltipElement formatting(TextFormatting value) { return new Impl_formatting(value); }
    static TooltipElement unverified() { return new Impl_unverified(); }
    private static AssertionError inactive() { inactiveCalls++; return new AssertionError("Inactive generated union getter must not run"); }
    private abstract static class Parent_ extends TooltipElement {
        @Override public int spacing() { throw inactive(); }
        @Override public String text() { throw inactive(); }
        @Override public DisplayComponent displayComponentIcon() { throw inactive(); }
        @Override public Component componentDescription() { throw inactive(); }
        @Override public TextFormatting formatting() { throw inactive(); }
    }
    private static final class Impl_spacing extends Parent_ {
        private final int value;
        Impl_spacing(int value) { this.value = value; }
        @Override public ElementType type() { return ElementType.SPACING; }
        @Override public int spacing() { return value; }
    }
    private static final class Impl_text extends Parent_ {
        private final String value;
        Impl_text(String value) { this.value = value; }
        @Override public ElementType type() { return ElementType.TEXT; }
        @Override public String text() { return value; }
    }
    private static final class Impl_displayComponentIcon extends Parent_ {
        private final DisplayComponent value;
        Impl_displayComponentIcon(DisplayComponent value) { this.value = value; }
        @Override public ElementType type() { return ElementType.DISPLAY_COMPONENT_ICON; }
        @Override public DisplayComponent displayComponentIcon() { return value; }
    }
    private static final class Impl_componentDescription extends Parent_ {
        private final Component value;
        Impl_componentDescription(Component value) { this.value = value; }
        @Override public ElementType type() { return ElementType.COMPONENT_DESCRIPTION; }
        @Override public Component componentDescription() { return value; }
    }
    private static final class Impl_formatting extends Parent_ {
        private final TextFormatting value;
        Impl_formatting(TextFormatting value) { this.value = value; }
        @Override public ElementType type() { return ElementType.FORMATTING; }
        @Override public TextFormatting formatting() { return value; }
    }
    private static final class Impl_unverified extends Parent_ {
        @Override public ElementType type() { unverifiedCalls++; throw new AssertionError("Near-matching generated type is not audited"); }
        @Override public String text() { unverifiedCalls++; throw new AssertionError("Near-matching generated getter must not run"); }
    }
}
