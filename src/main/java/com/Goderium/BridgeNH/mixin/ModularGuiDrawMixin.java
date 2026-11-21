package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MUI2 renders its wrapper inside Forge Pre and cancels the vanilla draw. */
@Pseudo
@Mixin(targets = "com.cleanroommc.modularui.screen.ClientScreenHandler", remap = false)
public abstract class ModularGuiDrawMixin {
    @Inject(method = "onGuiDraw(Lnet/minecraftforge/client/event/GuiScreenEvent$DrawScreenEvent$Pre;)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lcom/cleanroommc/modularui/screen/ClientScreenHandler;drawScreen(Lcom/cleanroommc/modularui/screen/ModularScreen;Lnet/minecraft/client/gui/GuiScreen;IIF)V"),
            remap = false, require = 0, allow = 1)
    private void codexbridge$modularDrawCompleted(GuiScreenEvent.DrawScreenEvent.Pre event, CallbackInfo callback) {
        LogicalGuiPointer.completedCustomDraw(event.gui, event.mouseX, event.mouseY);
    }
}
