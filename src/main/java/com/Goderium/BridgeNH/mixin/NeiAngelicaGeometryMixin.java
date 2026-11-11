package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the selected LWJGL3 backend entry only. Original GL calls and outcomes remain unchanged. */
@Pseudo
@Mixin(targets = "com.gtnewhorizons.angelica.lwjgl3.Lwjgl3GLRenderBackend", remap = false)
public abstract class NeiAngelicaGeometryMixin {
    @Inject(method = "drawArrays(III)V", at = @At("HEAD"), remap = false, require = 0)
    private void codexbridge$observeArrays(int mode, int first, int count, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(32);
        NeiNaturalRenderCapture.drawCommand("drawArrays", mode, first, count, -1, 0L, 1);
    }

    @Inject(method = "drawElements(IIIJ)V", at = @At("HEAD"), remap = false, require = 0)
    private void codexbridge$observeElements(int mode, int count, int type, long indices, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(32);
        NeiNaturalRenderCapture.drawCommand("drawElements", mode, 0, count, type, indices, 1);
    }

    @Inject(method = "drawArraysInstanced(IIII)V", at = @At("HEAD"), remap = false, require = 0)
    private void codexbridge$observeArraysInstanced(int mode, int first, int count, int instances, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(32);
        NeiNaturalRenderCapture.drawCommand("drawArraysInstanced", mode, first, count, -1, 0L, instances);
    }

    @Inject(method = "drawElementsInstanced(IIIJI)V", at = @At("HEAD"), remap = false, require = 0)
    private void codexbridge$observeElementsInstanced(int mode, int count, int type, long indices, int instances, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(32);
        NeiNaturalRenderCapture.drawCommand("drawElementsInstanced", mode, 0, count, type, indices, instances);
    }
}
