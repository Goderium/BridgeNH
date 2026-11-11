package gregtech.common.render;

import gregtech.api.interfaces.ITexture;
import net.minecraft.block.Block;

public class GTCopiedBlockTextureRender implements ITexture {
    private final Block mBlock;
    private final byte mSide;
    private final int mMeta;
    public GTCopiedBlockTextureRender(Block block, int side, int meta) { mBlock = block; mSide = (byte) side; mMeta = meta; }
    @Override public boolean isValidTexture() { return true; }
    public void renderXPos() { throw new AssertionError("Texture rendering is forbidden"); }
}
