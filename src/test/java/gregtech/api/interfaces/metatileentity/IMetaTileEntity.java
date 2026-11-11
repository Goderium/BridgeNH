package gregtech.api.interfaces.metatileentity;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.ISBRInventoryContext;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

/** Existing stack-form contract plus the actual public inventory visual getter shape. */
public interface IMetaTileEntity {
    ItemStack getStackForm(long amount);
    default ITexture[][] getInventoryTextures() { return null; }
    default IGregTechTileEntity getBaseMetaTileEntity() { return null; }
    default ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, ForgeDirection facing, int color, boolean active, boolean redstone) { return null; }
    default boolean renderInInventory(ISBRInventoryContext ctx) { throw new AssertionError("Inventory rendering is forbidden"); }
}
