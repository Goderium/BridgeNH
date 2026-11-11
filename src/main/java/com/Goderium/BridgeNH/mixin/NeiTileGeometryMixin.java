package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Match the exact existing selected preview tile, including cached native TESR dispatch. */
@Mixin(targets = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher", remap = false, priority = 2000)
public abstract class NeiTileGeometryMixin {
    @Inject(method = {"renderTileEntityAt(Lnet/minecraft/tileentity/TileEntity;DDDF)V", "func_147549_a(Lnet/minecraft/tileentity/TileEntity;DDDF)V"}, at = @At("HEAD"), remap = false, require = 1)
    private void codexbridge$beginTile(TileEntity tile, double x, double y, double z, float partialTick, CallbackInfo callback) {
        NeiNaturalRenderCapture.markHook(4);
        NeiNaturalRenderCapture.tileBegin(tile);
    }

    @Inject(method = {"renderTileEntityAt(Lnet/minecraft/tileentity/TileEntity;DDDF)V", "func_147549_a(Lnet/minecraft/tileentity/TileEntity;DDDF)V"}, at = @At("RETURN"), remap = false, require = 1)
    private void codexbridge$endTile(TileEntity tile, double x, double y, double z, float partialTick, CallbackInfo callback) {
        NeiNaturalRenderCapture.tileEnd(tile);
    }
}
