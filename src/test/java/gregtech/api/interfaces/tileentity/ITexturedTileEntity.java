package gregtech.api.interfaces.tileentity;

import gregtech.api.interfaces.ITexture;
import net.minecraft.block.Block;
import net.minecraftforge.common.util.ForgeDirection;

public interface ITexturedTileEntity { ITexture[] getTexture(Block block, ForgeDirection side); }
