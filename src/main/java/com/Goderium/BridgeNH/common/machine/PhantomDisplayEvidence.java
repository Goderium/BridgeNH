package com.Goderium.BridgeNH.common.machine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Reproduce only native initialization of absent/empty NBT on isolated phantom display copies. */
public final class PhantomDisplayEvidence {
    public @SideOnly(Side.CLIENT)
    static List<ItemStack> sources(ItemStack displayed) {
        List<ItemStack> result = new ArrayList<ItemStack>();
        if (displayed == null || !displayed.hasTagCompound() || displayed.getTagCompound().hasNoTags()) return result;
        for (boolean empty : new boolean[] {false, true}) {
            ItemStack source = displayed.copy();
            source.setTagCompound(empty ? new NBTTagCompound() : null);
            ItemStack projected = source.copy();
            try {
                // RenderItem's durability query, without drawing, player access, tooltip construction or stock writes.
                if (projected.getItem().showDurabilityBar(projected)) projected.getItem().getDurabilityForDisplay(projected);
                if (!ItemStack.areItemStacksEqual(source, projected) && ItemStack.areItemStacksEqual(projected, displayed))
                    result.add(source);
            } catch (RuntimeException | LinkageError unsupportedDisplay) {
                // A failed native query is not evidence of synchronization.
            }
        }
        return result;
    }
}
