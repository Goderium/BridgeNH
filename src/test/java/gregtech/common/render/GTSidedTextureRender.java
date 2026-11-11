package gregtech.common.render;

import gregtech.api.interfaces.ITexture;

public class GTSidedTextureRender implements ITexture {
    protected final ITexture[] mTextures;
    public GTSidedTextureRender(ITexture... textures) { mTextures = textures; }
    @Override public boolean isValidTexture() { return true; }
    public void renderXPos() { throw new AssertionError("Texture rendering is forbidden"); }
}
