package com.gtnewhorizon.structurelib.alignment.constructable;

import net.minecraft.item.ItemStack;

/** Real public text/action signatures; no structure action is permitted by the test fixture. */
public interface IConstructable {
    void construct(ItemStack stack, boolean hintsOnly);
    String[] getStructureDescription(ItemStack stack);
}
