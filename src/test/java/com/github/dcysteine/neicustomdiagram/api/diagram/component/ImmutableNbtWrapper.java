package com.github.dcysteine.neicustomdiagram.api.diagram.component;
import net.minecraft.nbt.NBTTagCompound;
public abstract class ImmutableNbtWrapper {
    private static int copyCalls;
    private static NBTTagCompound lastCopy;
    protected abstract NBTTagCompound nbt();
    public NBTTagCompound get() { copyCalls++; lastCopy = (NBTTagCompound) nbt().copy(); return lastCopy; }
    public static ImmutableNbtWrapper generated(NBTTagCompound nbt) { return new AutoValue_ImmutableNbtWrapper((NBTTagCompound) nbt.copy()); }
    public static void resetCopiesForTest() { copyCalls = 0; lastCopy = null; }
    public static int copiesForTest() { return copyCalls; }
    public static NBTTagCompound lastCopyForTest() { return lastCopy; }
}
