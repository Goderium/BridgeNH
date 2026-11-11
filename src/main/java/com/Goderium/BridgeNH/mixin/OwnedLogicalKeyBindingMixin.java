package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact owned holds run before physical-modifier gates; all other bindings retain their native getter path. */
@Mixin(targets = "net.minecraft.client.settings.KeyBinding", remap = false, priority = 2000)
public abstract class OwnedLogicalKeyBindingMixin {
    @Inject(method = {"getIsKeyPressed()Z", "func_151470_d()Z"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 1, allow = 1)
    private void codexbridge$ownedLogicalHold(CallbackInfoReturnable<Boolean> callback) {
        Boolean owned = OwnedKeyState.override(this, ((KeyBinding) (Object) this).getKeyCode());
        if (owned != null) callback.setReturnValue(owned);
    }
}
