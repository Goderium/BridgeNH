package appeng.api.storage;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

public interface IStorageHelper {
    IAEItemStack createItemStack(ItemStack stack);
    IAEFluidStack createFluidStack(FluidStack stack);
}
