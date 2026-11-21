package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.GuiMouseReset;
import com.Goderium.BridgeNH.client.gui.ContainerClickHistory;
import com.Goderium.BridgeNH.client.gui.VirtualGuiMouse;
import com.Goderium.BridgeNH.loader.rfb.GuiMouseScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import cpw.mods.fml.relauncher.ReflectionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=GuiContainer.class, remap=false)
public abstract class GuiContainerMouseResetMixin implements GuiMouseReset.ContainerDrag {
    @Unique private final ContainerClickHistory mcb$clickHistory = new ContainerClickHistory();

    @WrapMethod(method = {"mouseClicked(III)V", "func_73864_a(III)V"}, remap = false, require = 1, allow = 1)
    private void mcb$containerPress(int x, int y, int button, Operation<Void> original) {
        GuiContainer gui = (GuiContainer)(Object)this;
        mcb$clickHistory.press(gui, VirtualGuiMouse.INSTANCE.agentPress(gui), () -> original.call(x, y, button),
                () -> Minecraft.getMinecraft().currentScreen == gui && !GuiMouseScope.handedBack());
    }

    public void mcb$forgetVirtualClicks() { mcb$clickHistory.handoff((GuiContainer)(Object)this); }

    @Inject(method = {"onGuiClosed()V", "func_146281_b()V"}, at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void mcb$closeClickHistory(CallbackInfo ci) { mcb$forgetVirtualClicks(); }

    public void mcb$cancelContainerDrag() {
        GuiContainer gui = (GuiContainer) (Object) this;
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, false, "field_147007_t", "dragSplitting");
        Set<?> slots = ReflectionHelper.getPrivateValue(GuiContainer.class, gui, "field_147008_s", "dragSplittingSlots");
        slots.clear();
    }
}
