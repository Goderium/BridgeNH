package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.config.TeleportPolicy;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/** Fresh destination geometry and atomic developer flight protection, shared by both teleport routes. */
public class TeleportSafety {
    public static JsonObject inspect(WorldServer world, EntityPlayerMP player, TeleportPolicy.Target target) {
        AxisAlignedBB body = DeveloperDimensionTravel.destinationBody(player, target);
        TeleportPolicy.requireDestinationBody(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ,
                Math.min(256, world.getHeight()));
        DeveloperDimensionTravel.requireQueryChunks(world, body);
        JsonObject observed = WorldObservationsAdapter.spaceAt(world, player, body, System.nanoTime() + 20000000L, true);
        if (!"observed".equals(PlayerNavigation.text(observed, "status")))
            throw new BridgeException("invalid_state", "Teleport destination geometry is partial or unavailable; no teleport was submitted.");
        if (!PlayerNavigation.flag(observed, "occupiable") || PlayerNavigation.flag(observed, "liquid") || PlayerNavigation.flag(observed, "hazard")
                || !world.getCollidingBoundingBoxes(player, body).isEmpty() || world.isAnyLiquid(body))
            throw new BridgeException("invalid_state", "The destination player body intersects collision, liquid or a known hazard.");
        return observed;
    }

    public static JsonObject prepare(EntityPlayerMP player, JsonObject observed, boolean explicitlyRequired) {
        boolean supported = PlayerNavigation.flag(observed, "fullSupportCoverage");
        boolean required = TeleportPolicy.flightRequired(supported, explicitlyRequired,
                player.capabilities.isCreativeMode, player.capabilities.allowFlying, player.capabilities.isFlying);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("destinationSupported", supported);
        receipt.addProperty("flightRequired", required);
        receipt.addProperty("automaticallyEnabled", required && !player.capabilities.isFlying);
        receipt.addProperty("supportSource", "fresh_destination_body_collision_footprint");
        if (required) {
            player.capabilities.isFlying = true;
            player.onGround = false;
            player.motionX = player.motionY = player.motionZ = 0;
            player.fallDistance = 0;
            player.sendPlayerAbilities();
        }
        return receipt;
    }
}
