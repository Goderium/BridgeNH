package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.loader.ClientBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Run after Forge has finished drawing and handling the current native startup prompt. */
@Mixin(targets = "net.minecraft.client.LoadingScreenRenderer", remap = false)
public abstract class LoadingScreenQueueMixin {
    @Inject(method = {"setLoadingProgress(I)V", "func_73718_a(I)V"}, at = @At("RETURN"),
            remap = false, require = 1, allow = 1)
    private void codexbridge$startupQueue(int progress, CallbackInfo callback) {
        ClientBootstrap.loadingFrame();
    }
}
