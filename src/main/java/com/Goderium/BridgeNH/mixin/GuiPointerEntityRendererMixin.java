package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import java.awt.Point;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Preserves the sole normal draw call and both original Forge frame events. */
@Mixin(targets = "net.minecraft.client.renderer.EntityRenderer", remap = false)
public abstract class GuiPointerEntityRendererMixin {
    @Inject(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$renderStart(float partial, CallbackInfo callback) { LogicalGuiPointer.renderStart(); }
    @ModifyArgs(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/event/GuiScreenEvent$DrawScreenEvent$Pre;<init>(Lnet/minecraft/client/gui/GuiScreen;IIF)V"), remap = false, require = 1, allow = 1)
    private void codexbridge$prePoint(Args args) { Point point = LogicalGuiPointer.beforePre(args.get(0), args.get(1), args.get(2)); if (point != null) { args.set(1, point.x); args.set(2, point.y); } }
    @Group(name = "codexbridge_pointer_draw", min = 1, max = 1)
    @Redirect(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;drawScreen(IIF)V"), remap = false, require = 0, allow = 1)
    private void codexbridge$drawMcp(GuiScreen screen, int x, int y, float partial) { codexbridge$drawOnce(screen, x, y, partial); }
    @Group(name = "codexbridge_pointer_draw", min = 1, max = 1)
    @Redirect(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;func_73863_a(IIF)V"), remap = false, require = 0, allow = 1)
    private void codexbridge$drawSrg(GuiScreen screen, int x, int y, float partial) { codexbridge$drawOnce(screen, x, y, partial); }
    private void codexbridge$drawOnce(GuiScreen screen, int x, int y, float partial) {
        Point point = LogicalGuiPointer.forDraw(screen); boolean completed = false;
        try { screen.drawScreen(point == null ? x : point.x, point == null ? y : point.y, partial); completed = true; }
        finally { if (!completed) LogicalGuiPointer.renderFailed(); }
    }
    @Redirect(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At(value = "INVOKE", target = "Lcpw/mods/fml/common/eventhandler/EventBus;post(Lcpw/mods/fml/common/eventhandler/Event;)Z"), remap = false, require = 2, allow = 2)
    private boolean codexbridge$frameEventOnce(EventBus bus, Event event) {
        boolean completed = false;
        try { boolean canceled = bus.post(event); completed = true; return canceled; }
        finally { if (!completed) LogicalGuiPointer.renderFailed(); }
    }
    @ModifyArgs(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/event/GuiScreenEvent$DrawScreenEvent$Post;<init>(Lnet/minecraft/client/gui/GuiScreen;IIF)V"), remap = false, require = 1, allow = 1)
    private void codexbridge$postPoint(Args args) { Point point = LogicalGuiPointer.forPost(args.get(0)); if (point != null) { args.set(1, point.x); args.set(2, point.y); } }
    @Inject(method = {"updateCameraAndRender(F)V", "func_78480_b(F)V"}, at = @At("RETURN"), remap = false, require = 1)
    private void codexbridge$renderEnd(float partial, CallbackInfo callback) { LogicalGuiPointer.renderEnd(); }
}
