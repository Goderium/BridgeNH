package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native selected-block owner only; never replaces the rendering method. */
@Mixin(targets = "net.minecraft.client.renderer.RenderBlocks", remap = false, priority = 2000)
public abstract class NeiBlockGeometryMixin {
    @Inject(method = {"renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z", "func_147805_b(Lnet/minecraft/block/Block;III)Z"}, at = @At("HEAD"), remap = false, require = 1)
    private void codexbridge$beginBlock(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> callback) {
        NeiNaturalRenderCapture.markHook(1);
        NeiNaturalRenderCapture.blockBegin(this, block, x, y, z);
    }

    @Inject(method = {"renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z", "func_147805_b(Lnet/minecraft/block/Block;III)Z"}, at = @At("RETURN"), remap = false, require = 1)
    private void codexbridge$endBlock(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> callback) {
        NeiNaturalRenderCapture.blockEnd(this, x, y, z);
    }
}
