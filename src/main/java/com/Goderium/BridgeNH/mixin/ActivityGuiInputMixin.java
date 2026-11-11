package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.loader.ClientBootstrap;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe native dispatch before virtual handlers, including mod overrides that skip super. */
@Mixin(targets = "net.minecraft.client.gui.GuiScreen", remap = false)
public abstract class ActivityGuiInputMixin {
    @Group(name = "mcbActivityMouse", min = 1, max = 1)
    @Inject(method = {"handleInput()V", "func_146269_k()V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleMouseInput()V"), remap = false, require = 0)
    private void codexbridge$mouseDev(CallbackInfo callback) { ClientBootstrap.recordMouse((GuiScreen) (Object) this); }

    @Group(name = "mcbActivityMouse", min = 1, max = 1)
    @Inject(method = {"handleInput()V", "func_146269_k()V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;func_146274_d()V"), remap = false, require = 0)
    private void codexbridge$mouseSrg(CallbackInfo callback) { ClientBootstrap.recordMouse((GuiScreen) (Object) this); }

    @Group(name = "mcbActivityKey", min = 1, max = 1)
    @Inject(method = {"handleInput()V", "func_146269_k()V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleKeyboardInput()V"), remap = false, require = 0)
    private void codexbridge$keyDev(CallbackInfo callback) { ClientBootstrap.recordKey((GuiScreen) (Object) this); }

    @Group(name = "mcbActivityKey", min = 1, max = 1)
    @Inject(method = {"handleInput()V", "func_146269_k()V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;func_146282_l()V"), remap = false, require = 0)
    private void codexbridge$keySrg(CallbackInfo callback) { ClientBootstrap.recordKey((GuiScreen) (Object) this); }
}
