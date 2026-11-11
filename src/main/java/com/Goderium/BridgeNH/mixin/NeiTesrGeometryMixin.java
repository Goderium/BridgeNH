package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native queue CPU data, on both build and cache replay; does not invoke mesh builders. */
@Pseudo
@Mixin(targets = "com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer", remap = false)
public abstract class NeiTesrGeometryMixin {
    @Inject(method = "queue(Lcom/gtnewhorizons/angelica/rendering/tesr/TemplateBuffer;Lnet/minecraft/util/ResourceLocation;Lcom/gtnewhorizons/angelica/api/tesr/TesrMaterial;)V", at = @At("HEAD"), remap = false, require = 0)
    private void codexbridge$observeTemplate(@Coerce Object template, ResourceLocation texture, @Coerce Object material, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(16);
        NeiNaturalRenderCapture.tesrTemplate(template, texture, material);
    }
}
