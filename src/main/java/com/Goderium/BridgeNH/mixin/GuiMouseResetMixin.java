package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.GuiMouseReset;
import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiButton;
import com.Goderium.BridgeNH.client.gui.VanillaSettingsControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value=GuiScreen.class, remap=false)
public abstract class GuiMouseResetMixin implements GuiMouseReset {
    @Shadow(aliases={"field_146287_f"}) private int eventButton;
    @Shadow(aliases={"field_146288_g"}) private long lastMouseEvent;
    @Shadow(aliases={"field_146298_h"}) private int touchValue;

    public void mcb$cancelGuiMouse() {
        eventButton=-1;
        lastMouseEvent=0;
        touchValue=0;
        // The pack may widen selectedButton. Mixin aliases cannot bind a widened target.
        GuiScreen screen = (GuiScreen) (Object) this;
        GuiButton selected = ReflectionHelper.getPrivateValue(GuiScreen.class, screen, "field_146290_a", "selectedButton");
        VanillaSettingsControls.cancelSliderDrag(selected);
        ReflectionHelper.setPrivateValue(GuiScreen.class, screen, null, "field_146290_a", "selectedButton");
    }
}
