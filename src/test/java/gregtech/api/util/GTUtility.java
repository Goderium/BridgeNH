package gregtech.api.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Exact GT distinction: false omits the amount and produces a zero-amount display. */
public class GTUtility {
    public static final Item DISPLAY = new Item() {
        @Override public String getItemStackDisplayName(ItemStack stack) { return "fixture NEI fluid"; }
    };
    public static ItemStack getFluidDisplayStack(FluidStack fluid, boolean useAmount) {
        ItemStack stack = new ItemStack(DISPLAY, 1, 0);
        NBTTagCompound tag = new NBTTagCompound(); tag.setString("fluid", fluid.getFluid().getName());
        tag.setLong("mFluidDisplayAmount", useAmount ? fluid.amount : 0); stack.setTagCompound(tag); return stack;
    }
    public static FluidStack getFluidFromDisplayStack(ItemStack stack) {
        if (stack.getItem() != DISPLAY || !stack.hasTagCompound()) return null;
        return new FluidStack(FluidRegistry.getFluid(stack.getTagCompound().getString("fluid")), (int) stack.getTagCompound().getLong("mFluidDisplayAmount"));
    }
}
