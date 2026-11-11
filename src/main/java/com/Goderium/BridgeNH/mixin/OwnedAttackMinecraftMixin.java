package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Reuse the ordinary once-per-tick mining path for one valid background input lease. */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class OwnedAttackMinecraftMixin {
    @ModifyVariable(method = {"sendClickBlockToController(Z)V", "func_147115_a(Z)V"},
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 1, allow = 1)
    private boolean codexbridge$ownedAttack(boolean nativeAttack) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.gameSettings == null) return nativeAttack;
        KeyBinding attack = client.gameSettings.keyBindAttack;
        return attack == null ? nativeAttack : OwnedKeyState.attackInput(nativeAttack, attack, attack.getKeyCode());
    }
}
