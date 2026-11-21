package com.Goderium.BridgeNH.client.survival;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import com.Goderium.BridgeNH.client.lifecycle.WorldLifecycle;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/** One ordinary mining hold. The native per-tick attack hook is its only input producer. */
public final class SurvivalMining {
    public static final String OWNER = "player.mine";
    public final String id = UUID.randomUUID().toString();
    private final World world;
    private final EntityClientPlayerMP player;
    private final int x, y, z, metadata, selected;
    private final String registry;
    private final ItemStack tool;
    private final int maxTicks;
    private final long deadline;
    private int ticks;
    private String end;

    public SurvivalMining(Minecraft client, JsonObject args, String session) {
        if (!session.equals(JsonData.string(args, "expectedSessionId"))
                || !WorldLifecycle.target(client).has("expectedWorld")
                || !WorldLifecycle.target(client).get("expectedWorld").getAsString().equals(JsonData.string(args, "expectedWorld")))
            throw new BridgeException("stale_world", "Use the current session and lifecycleTarget.expectedWorld.");
        player = client.thePlayer;
        world = client.theWorld;
        if (player.dimension != JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE))
            throw new BridgeException("stale_world", "Mining target dimension changed.");
        x = JsonData.integer(args, "x", -30000000, 30000000);
        y = JsonData.integer(args, "y", 0, 255);
        z = JsonData.integer(args, "z", -30000000, 30000000);
        metadata = JsonData.integer(args, "metadata", 0, 15);
        registry = JsonData.string(args, "registryName");
        selected = JsonData.integer(args, "hotbarSlot", 0, 8);
        tool = ItemStack.copyItemStack(player.getHeldItem());
        maxTicks = args.has("ticks") ? JsonData.integer(args, "ticks", 1, 200) : 120;
        deadline = System.nanoTime() + 15000000000L;
        String reason = changed(client);
        if (reason != null) throw new BridgeException("mining_precondition", reason);
        if (world.isAirBlock(x, y, z) || world.getBlock(x, y, z).getPlayerRelativeBlockHardness(player, world, x, y, z) <= 0)
            throw new BridgeException("mining_precondition", "Target is air or the held tool cannot make mining progress.");
    }

    private String changed(Minecraft c) {
        if (c.theWorld != world || c.thePlayer != player || player.isDead || player.getHealth() <= 0
                || c.currentScreen != null || c.isGamePaused()) return "player_or_world_changed";
        if (player.capabilities.isCreativeMode || c.playerController.isInCreativeMode() || !player.capabilities.allowEdit)
            return "survival_actor_required";
        if (!world.blockExists(x, y, z)) return "chunk_unloaded";
        if (!registry.equals(Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)))
                || world.getBlockMetadata(x, y, z) != metadata) return "block_changed_on_client";
        if (player.inventory.currentItem != selected || !ItemStack.areItemStacksEqual(tool, player.getHeldItem()))
            return "held_item_changed";
        MovingObjectPosition hit = c.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.blockX != x
                || hit.blockY != y || hit.blockZ != z || hit.hitVec == null
                || player.getPosition(1F).distanceTo(hit.hitVec) > c.playerController.getBlockReachDistance())
            return "crosshair_or_reach_changed";
        return null;
    }

    public void start(Minecraft client, BooleanSupplier protectedControl) {
        OwnedKeyState.begin(OWNER, deadline, () -> {
            if (end == null) end = !protectedControl.getAsBoolean() ? "control_released" : changed(client);
            return end == null;
        });
    }

    public boolean tick(Minecraft client) {
        if (end == null) end = changed(client);
        if (end == null && (++ticks >= maxTicks || System.nanoTime() >= deadline)) end = "hold_expired";
        if (end == null && !OwnedKeyState.active(OWNER)) end = "control_released";
        return end != null;
    }

    public void stop(String reason) {
        if (end == null) end = reason;
        OwnedKeyState.clear(OWNER);
    }

    public JsonObject receipt() {
        JsonObject data = new JsonObject();
        data.addProperty("taskId", id);
        data.addProperty("status", end == null ? "active" : "stopped");
        if (end != null) data.addProperty("reason", end);
        data.addProperty("registryName", registry);
        data.addProperty("metadata", metadata);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("elapsedTicks", ticks);
        data.addProperty("pickupConfirmed", false);
        return data;
    }
}
