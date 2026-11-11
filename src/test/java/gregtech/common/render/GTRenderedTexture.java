package gregtech.common.render;

import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;

public class GTRenderedTexture implements ITexture {
    protected final IIconContainer mIconContainer;
    private final short[] mRGBa;
    private final boolean glow, useExtFacing;
    public GTRenderedTexture(IIconContainer container, short[] rgba, boolean glow, boolean facing) { mIconContainer = container; mRGBa = rgba; this.glow = glow; useExtFacing = facing; }
    public short[] getRGBA() { return mRGBa; }
    @Override public boolean isValidTexture() { return true; }
    public void renderXPos() { throw new AssertionError("Texture rendering is forbidden"); }
}
