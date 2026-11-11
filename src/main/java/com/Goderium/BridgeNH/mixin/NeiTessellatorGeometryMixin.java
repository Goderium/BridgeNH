package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copy requested native buffer slices before ordinary-priority optimized draw cancellation. */
@Mixin(targets = "net.minecraft.client.renderer.Tessellator", remap = false, priority = 2000)
public abstract class NeiTessellatorGeometryMixin {
    @Inject(method = {"draw()I", "func_78381_a()I"}, at = @At("HEAD"), remap = false, require = 1)
    private void codexbridge$observeDraw(CallbackInfoReturnable<Integer> callback) {
        NeiNaturalRenderCapture.markHook(2);
        NeiNaturalRenderCapture.tessellatorDraw(this);
    }
}
