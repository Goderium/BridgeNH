package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.loader.ClientBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the submitted string, including client-only and unknown commands. */
@Mixin(targets = "net.minecraft.client.gui.GuiChat", remap = false)
public abstract class ActivityChatMixin {
    @Inject(method = {"submitChatMessage(Ljava/lang/String;)V", "func_146403_a(Ljava/lang/String;)V"},
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$command(String text, CallbackInfo callback) {
        ClientBootstrap.recordCommand(text);
    }
}
