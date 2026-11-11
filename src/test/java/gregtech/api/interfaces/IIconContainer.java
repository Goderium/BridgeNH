package gregtech.api.interfaces;

import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

public interface IIconContainer {
    IIcon getIcon();
    IIcon getOverlayIcon();
    ResourceLocation getTextureFile();
    default int getIconPasses() { return 1; }
    default int getRenderIconPass() { return 0; }
    default boolean canRenderInPass(int pass) { return pass == getRenderIconPass(); }
    default short[] getIconColor(int pass) { return new short[] {255, 255, 255, 0}; }
    default boolean isUsingColorModulation(int pass) { return pass == 0; }
}
