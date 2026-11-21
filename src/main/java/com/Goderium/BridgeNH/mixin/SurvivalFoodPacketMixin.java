package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.survival.SurvivalFood;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the normal server acknowledgements only during an outstanding local food request. */
@Mixin(targets = "net.minecraft.client.network.NetHandlerPlayClient", remap = false)
public abstract class SurvivalFoodPacketMixin {
    @Inject(method = {"handleUpdateHealth(Lnet/minecraft/network/play/server/S06PacketUpdateHealth;)V",
            "func_147249_a(Lnet/minecraft/network/play/server/S06PacketUpdateHealth;)V"},
            at = @At("RETURN"), remap = false, require = 1, allow = 1)
    private void codexbridge$foodHealth(S06PacketUpdateHealth packet, CallbackInfo callback) {
        SurvivalFood.health(this, packet);
    }

    @Inject(method = {"handleEntityStatus(Lnet/minecraft/network/play/server/S19PacketEntityStatus;)V",
            "func_147236_a(Lnet/minecraft/network/play/server/S19PacketEntityStatus;)V"},
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void codexbridge$foodStatus(S19PacketEntityStatus packet, CallbackInfo callback) {
        SurvivalFood.beforeStatus(this, packet);
    }

    @Inject(method = {"handleEntityStatus(Lnet/minecraft/network/play/server/S19PacketEntityStatus;)V",
            "func_147236_a(Lnet/minecraft/network/play/server/S19PacketEntityStatus;)V"},
            at = @At("RETURN"), remap = false, require = 1, allow = 1)
    private void codexbridge$foodFinished(S19PacketEntityStatus packet, CallbackInfo callback) {
        SurvivalFood.afterStatus(packet);
    }
}
