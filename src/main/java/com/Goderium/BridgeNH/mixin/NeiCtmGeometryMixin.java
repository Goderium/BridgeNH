package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderCapture;
import net.minecraft.block.Block;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual optional Angelica resolver return; never invokes or resets CTM resolution. */
@Pseudo
@Mixin(targets = "com.prupe.mcpatcher.ctm.CTMUtils", remap = false)
public abstract class NeiCtmGeometryMixin {
    @Inject(method = "getBlockIcon(Lnet/minecraft/util/IIcon;Lnet/minecraft/block/Block;Lnet/minecraft/world/IBlockAccess;IIII)Lnet/minecraft/util/IIcon;", at = @At("RETURN"), remap = false, require = 0)
    private static void codexbridge$observeCtm(IIcon original, Block block, IBlockAccess world, int x, int y, int z, int side, CallbackInfoReturnable<IIcon> callback) {
        NeiNaturalRenderCapture.markHook(8);
        NeiNaturalRenderCapture.ctmResolved(original, block, world, x, y, z, side, callback.getReturnValue());
    }
}
