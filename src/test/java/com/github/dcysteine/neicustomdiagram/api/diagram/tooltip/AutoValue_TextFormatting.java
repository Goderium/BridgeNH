package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;
import com.google.common.collect.ImmutableSortedSet;
import net.minecraft.util.EnumChatFormatting;
final class AutoValue_TextFormatting extends TextFormatting {
    private final ImmutableSortedSet<EnumChatFormatting> codes;
    private final boolean small;
    AutoValue_TextFormatting(ImmutableSortedSet<EnumChatFormatting> codes, boolean small) { this.codes = codes; this.small = small; }
    @Override public ImmutableSortedSet<EnumChatFormatting> formatting() { return codes; }
    @Override public boolean small() { return small; }
}
