package com.cleanroommc.modularui.utils.fluid;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

public class FluidInteractions {
    public static FluidStack getFluidForItem(ItemStack stack) {
        if (stack.getItem() instanceof IFluidContainerItem) return ((IFluidContainerItem) stack.getItem()).getFluid(stack);
        FluidStack result = FluidContainerRegistry.getFluidForFilledItem(stack);
        return result == null ? gregtech.api.util.GTUtility.getFluidFromDisplayStack(stack) : result;
    }
}
