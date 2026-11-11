package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import net.minecraft.nbt.NBTTagCompound;
final class AutoValue_ImmutableNbtWrapper extends ImmutableNbtWrapper {
    private final NBTTagCompound nbt;
    AutoValue_ImmutableNbtWrapper(NBTTagCompound nbt) { this.nbt = nbt; }
    @Override protected NBTTagCompound nbt() { return nbt; }
}
