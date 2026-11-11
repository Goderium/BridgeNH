package com.Goderium.BridgeNH.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.Goderium.BridgeNH.client.nei.NeiHoverCapture;
import java.util.List;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe one existing natural Tooltip method; every original result and renderer argument is returned unchanged. */
@Pseudo
@Mixin(targets = "codechicken.nei.guihook.GuiContainerManager", remap = false)
public abstract class NeiTooltipDecisionMixin {
    @Shadow(remap = false) public GuiContainer window;

    @Inject(method = "renderToolTips(II)V", at = @At("HEAD"), remap = false, require = 0, allow = 1)
    private void codexbridge$tooltipEntry(int mouseX, int mouseY, CallbackInfo callback) {
        NeiHoverCapture.nativeRenderStart(window, mouseX, mouseY);
    }

    @ModifyExpressionValue(method = "renderToolTips(II)V", at = @At(value = "INVOKE", target = "Lcodechicken/nei/guihook/GuiContainerManager;shouldShowTooltip(Lnet/minecraft/client/gui/inventory/GuiContainer;)Z", remap = false), remap = false, require = 0, allow = 1)
    private boolean codexbridge$tooltipGate(boolean original) {
        return NeiHoverCapture.nativeGateResult(window, original);
    }

    @ModifyExpressionValue(method = "renderToolTips(II)V", at = @At(value = "INVOKE", target = "Lcodechicken/nei/guihook/GuiContainerManager;getStackMouseOver(Lnet/minecraft/client/gui/inventory/GuiContainer;)Lnet/minecraft/item/ItemStack;", remap = false), remap = false, require = 0, allow = 1)
    private ItemStack codexbridge$tooltipStack(ItemStack original) {
        return NeiHoverCapture.nativeStackResult(window, original);
    }

    @ModifyArg(method = "renderToolTips(II)V", at = @At(value = "INVOKE", target = "Lcodechicken/nei/guihook/GuiContainerManager;drawPagedTooltip(Lnet/minecraft/client/gui/FontRenderer;IILjava/util/List;)V", remap = false), index = 3, remap = false, require = 0)
    private List<String> codexbridge$tooltipPagedList(List<String> original) {
        NeiHoverCapture.nativeFinalList(window, original, "drawPagedTooltip");
        return original;
    }

    @ModifyArg(method = "renderToolTips(II)V", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", remap = false), index = 0, remap = false, require = 0, allow = 1)
    private Object codexbridge$tooltipAlternativeList(Object original) {
        NeiHoverCapture.nativeFinalList(window, original, "alternativeConsumer.accept");
        return original;
    }
}
