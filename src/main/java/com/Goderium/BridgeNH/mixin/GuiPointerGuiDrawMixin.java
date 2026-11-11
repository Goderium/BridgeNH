package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import java.awt.Point;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "codechicken.lib.gui.GuiDraw", remap = false)
public abstract class GuiPointerGuiDrawMixin {
    @Inject(method = "getMousePosition()Ljava/awt/Point;", at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private static void codexbridge$logicalPoint(CallbackInfoReturnable<Point> callback) { Point point = LogicalGuiPointer.guiDrawPosition(); if (point != null) callback.setReturnValue(point); }
}
