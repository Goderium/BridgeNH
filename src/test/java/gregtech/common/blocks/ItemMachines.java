package gregtech.common.blocks;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class ItemMachines extends Item {
    public IMetaTileEntity fixtureMeta;
    public static IMetaTileEntity getMetaTileEntity(ItemStack stack) { return stack.getItem() instanceof ItemMachines ? ((ItemMachines) stack.getItem()).fixtureMeta : null; }
    @Override public String getItemStackDisplayName(ItemStack stack) { return "fixture native hatch"; }
}
