package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.VirtualGuiMouse;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiTextField.class, remap = false)
public abstract class GuiMouseTextFocusMixin {
    @Inject(method = {"drawTextBox()V", "func_146194_f()V"}, at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$focusedField(CallbackInfo callback) {
        GuiTextField field = (GuiTextField) (Object) this;
        if (field.isFocused() && field.getVisible()) VirtualGuiMouse.INSTANCE.observeTextFocus(field);
    }
}
