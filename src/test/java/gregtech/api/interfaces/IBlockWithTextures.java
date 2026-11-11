package gregtech.api.interfaces;

import net.minecraft.world.IBlockAccess;

public interface IBlockWithTextures {
    ITexture[][] getTextures(int meta);
    default ITexture[][] getTextures(IBlockAccess world, int x, int y, int z) { return getTextures(world.getBlockMetadata(x, y, z)); }
    default ITexture[][] getInventoryTextures(int meta) { return getTextures(meta); }
}
