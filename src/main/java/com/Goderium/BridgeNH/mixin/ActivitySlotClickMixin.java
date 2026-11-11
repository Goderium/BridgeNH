package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.loader.ClientBootstrap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.multiplayer.PlayerControllerMP", remap = false)
public abstract class ActivitySlotClickMixin {
    @Inject(method = {"windowClick(IIIILnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
            "func_78753_a(IIIILnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;"},
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$slot(int window, int slot, int button, int mode, EntityPlayer player, CallbackInfoReturnable<ItemStack> callback) {
        ClientBootstrap.recordSlot(window, slot, button, mode);
    }
}
