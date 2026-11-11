package gregtech.common.render;

import gregtech.api.interfaces.ITexture;

public class GTMultiTextureRender implements ITexture {
    protected final ITexture[] mTextures;
    public GTMultiTextureRender(ITexture... textures) { mTextures = textures; }
    @Override public boolean isValidTexture() { return true; }
    public void renderXPos() { throw new AssertionError("Texture rendering is forbidden"); }
}
