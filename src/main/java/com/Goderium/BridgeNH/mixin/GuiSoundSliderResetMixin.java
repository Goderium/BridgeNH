package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.GuiMouseReset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The normal sound-slider release plays a sound. Cancelling only ends its drag. */
@Mixin(targets = "net.minecraft.client.gui.GuiScreenOptionsSounds$Button", remap = false)
public abstract class GuiSoundSliderResetMixin implements GuiMouseReset.Slider {
    @Shadow public boolean field_146155_p;

    @Override public void mcb$cancelSliderDrag() { field_146155_p = false; }
}
