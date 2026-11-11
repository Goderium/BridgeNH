package gregtech.api.metatileentity;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.ISBRInventoryContext;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

public class CommonMetaTileEntity implements IMetaTileEntity {
    public ITexture[][] fixtureTextures;
    public int fixtureReads, fixtureFallbacks;
    public boolean fixtureNativeContext = true;
    @Override public ItemStack getStackForm(long amount) { return null; }
    @Override public ITexture[][] getInventoryTextures() { fixtureReads++; return fixtureTextures; }
    @Override public IGregTechTileEntity getBaseMetaTileEntity() { return new IGregTechTileEntity() {}; }
    @Override public boolean renderInInventory(ISBRInventoryContext ctx) { throw new AssertionError("Inventory rendering is forbidden"); }
    @Override public ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, ForgeDirection facing, int color, boolean active, boolean redstone) { fixtureFallbacks++; fixtureNativeContext &= facing == ForgeDirection.WEST && color == -1 && active && !redstone; return fixtureTextures == null ? new ITexture[0] : fixtureTextures[side.ordinal()]; }
}
