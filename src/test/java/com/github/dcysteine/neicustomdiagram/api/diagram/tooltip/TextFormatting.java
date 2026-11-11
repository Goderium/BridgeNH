package com.github.dcysteine.neicustomdiagram.api.diagram.tooltip;
import com.google.common.collect.ImmutableSortedSet;
import net.minecraft.util.EnumChatFormatting;
public abstract class TextFormatting {
    public abstract ImmutableSortedSet<EnumChatFormatting> formatting();
    public abstract boolean small();
    public static TextFormatting generated(boolean small, EnumChatFormatting... codes) {
        return new AutoValue_TextFormatting(ImmutableSortedSet.copyOf(codes), small);
    }
}
