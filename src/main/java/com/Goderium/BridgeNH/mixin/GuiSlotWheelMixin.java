package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.VirtualGuiMouse;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiSlot;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla lists poll the wheel inside drawScreen, after checking their own pointer bounds. */
@Mixin(value = GuiSlot.class, remap = false)
public abstract class GuiSlotWheelMixin {
    @WrapMethod(method = {"drawScreen(IIF)V", "func_148128_a(IIF)V"}, remap = false, require = 1, allow = 1)
    private void codexbridge$listWheel(int x, int y, float partial, Operation<Void> draw) {
        VirtualGuiMouse mouse = VirtualGuiMouse.INSTANCE;
        if (mouse.awaitingListWheel(x, y)) {
            mouse.drawList(() -> draw.call(x, y, partial));
        } else {
            draw.call(x, y, partial);
        }
    }
}
