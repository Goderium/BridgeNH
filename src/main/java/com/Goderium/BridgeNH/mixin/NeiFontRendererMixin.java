package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiTextCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read before default-priority batched-font cancellation; private lines cover independent wrapping. */
@Mixin(targets = "net.minecraft.client.gui.FontRenderer", remap = false, priority = 2000)
public abstract class NeiFontRendererMixin {
    @Inject(method = {"drawString(Ljava/lang/String;IIIZ)I", "func_85187_a(Ljava/lang/String;IIIZ)I"},
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$observeTerminal(String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> callback) {
        NeiTextCapture.observe(this, text, x, y, color, shadow);
    }

    @Inject(method = {"renderString(Ljava/lang/String;IIIZ)I", "func_78258_a(Ljava/lang/String;IIIZ)I"},
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$observeLine(String text, int x, int y, int color, boolean shadowPass, CallbackInfoReturnable<Integer> callback) {
        NeiTextCapture.observeLine(this, text, x, y, color, shadowPass);
    }
}
