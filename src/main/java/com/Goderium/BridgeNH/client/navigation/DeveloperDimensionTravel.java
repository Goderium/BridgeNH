package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.config.TeleportPolicy;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/** Guarded developer migration through the public Forge player transfer API. */
public class DeveloperDimensionTravel {
    public static JsonObject transfer(IntegratedServer server, EntityPlayerMP player, TeleportPolicy.Target target, boolean requireFlying) {
        if (player.dimension == 1 || target.dimension == 1)
            throw new BridgeException("unsupported", "Developer dimension travel involving the End is unsupported.");
        if (!DimensionManager.isDimensionRegistered(target.dimension))
            throw new BridgeException("world_unavailable", "The destination dimension is not registered.");
        WorldServer destination = DimensionManager.getWorld(target.dimension);
        if (destination == null || destination.isRemote || destination.provider.dimensionId != target.dimension)
            throw new BridgeException("world_unavailable", "The destination server dimension must already be loaded.");
        AxisAlignedBB body = destinationBody(player, target);
        JsonObject observed = TeleportSafety.inspect(destination, player, target);
        int trackingRadius = MathHelper.clamp_int(server.getConfigurationManager().getViewDistance(), 3, 20) + 1;
        // PlayerManager.addPlayer truncates the position before shifting, including at negative coordinates.
        int trackingX = (int) target.x >> 4;
        int trackingZ = (int) target.z >> 4;
        if (!target.allowTrackingChunkLoad) requireLoadedChunks(destination, trackingX - trackingRadius, trackingZ - trackingRadius,
                trackingX + trackingRadius, trackingZ + trackingRadius);
        if (!destination.getCollidingBoundingBoxes(player, body).isEmpty())
            throw new BridgeException("invalid_state", "The destination player body intersects a collision box.");
        if (destination.isAnyLiquid(body))
            throw new BridgeException("invalid_state", "The destination player body intersects liquid.");

        Snapshot before = new Snapshot(player);
        JsonObject flightSafety = TeleportSafety.prepare(player, observed, requireFlying);
        Snapshot migrationBaseline = new Snapshot(player);
        ExactTeleporter teleporter = new ExactTeleporter(destination, target, player.rotationYaw, player.rotationPitch);
        JsonArray diagnostics = new JsonArray();
        boolean migrationReturned = false;
        try {
            server.getConfigurationManager().transferPlayerToDimension(player, target.dimension, teleporter);
            migrationReturned = true;
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("dimension_transfer", failure));
        }

        Snapshot after = null;
        boolean arrivalConfirmed = false;
        try {
            arrivalConfirmed = arrived(player, destination, target, body, teleporter);
            after = new Snapshot(player);
            if (migrationReturned && arrivalConfirmed && before.sameInventory(after) && migrationBaseline.sameCapabilities(after))
                player.sendPlayerAbilities();
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("arrival_observation_or_abilities_sync", failure));
        }
        boolean inventoryPreserved = after != null && before.sameInventory(after);
        boolean capabilitiesPreserved = after != null && migrationBaseline.sameCapabilities(after);
        boolean complete = migrationReturned && arrivalConfirmed && inventoryPreserved && capabilitiesPreserved
                && diagnostics.size() == 0;
        JsonObject result = new JsonObject();
        result.addProperty("dimension", player.dimension);
        result.addProperty("targetDimension", target.dimension);
        result.addProperty("worldName", JsonData.bounded(server.getWorldName(), 128));
        result.addProperty("worldFolder", JsonData.bounded(server.getFolderName(), 128));
        result.addProperty("coordinateMeaning", "Authoritative server player posY; before and after include actual dimensions.");
        result.addProperty("submitted", true);
        result.addProperty("migrationReturned", migrationReturned);
        result.addProperty("serverArrivalConfirmed", arrivalConfirmed);
        result.addProperty("clientSyncRequired", true);
        result.addProperty("clientSyncObserved", false);
        result.addProperty("state", complete ? "submitted_client_sync_unverified" : "transfer_incomplete");
        result.addProperty("outcomeUnknown", !complete);
        result.addProperty("automaticRetryAllowed", false);
        result.addProperty("nextAction", "Take a fresh client snapshot and authoritative player sample before any further movement; do not automatically retry.");
        result.addProperty("normalTrackingChunkLoadingAllowed", target.allowTrackingChunkLoad);
        result.addProperty("normalTrackingChunkLoadingScope", target.allowTrackingChunkLoad
                ? "Standard public Forge/PlayerManager transfer may normally load or generate player tracking chunks; the destination body and collision query halo were already loaded."
                : "The complete player tracking window was already loaded; no additional tracking chunk loading was permitted.");
        result.addProperty("trackingWindowPrechecked", !target.allowTrackingChunkLoad);
        result.addProperty("normalTrackingChunkRadius", trackingRadius);
        result.add("trackingChunkRadiusChecked", target.allowTrackingChunkLoad ? JsonNull.INSTANCE : new JsonPrimitive(trackingRadius));
        result.add("inventoryPreserved", after == null ? JsonNull.INSTANCE : new JsonPrimitive(inventoryPreserved));
        result.add("capabilitiesPreserved", after == null ? JsonNull.INSTANCE : new JsonPrimitive(capabilitiesPreserved));
        result.addProperty("capabilitiesBaseline", "after_required_flight_protection_before_dimension_transfer");
        result.add("flightSafety", flightSafety);
        result.add("before", before.json);
        JsonObject afterJson = after == null ? position(player) : after.json;
        afterJson.addProperty("inventoryObservationAvailable", after != null);
        result.add("after", afterJson);
        if (diagnostics.size() != 0) result.add("diagnostics", diagnostics);
        return result;
    }

    static AxisAlignedBB destinationBody(EntityPlayerMP player, TeleportPolicy.Target target) {
        // Entity.setPositionAndRotation resets yOffset2 to zero before constructing this exact body.
        double halfWidth = player.width / 2.0F;
        double minY = target.y - player.yOffset;
        return AxisAlignedBB.getBoundingBox(target.x - halfWidth, minY, target.z - halfWidth,
                target.x + halfWidth, minY + player.height, target.z + halfWidth);
    }

    static void requireQueryChunks(WorldServer world, AxisAlignedBB body) {
        // Collision entity queries use the mod-adjustable MAX_ENTITY_RADIUS; liquid scans also pad negative minima.
        double padding = Math.max(2.0D, World.MAX_ENTITY_RADIUS + 0.25D);
        if (!Double.isFinite(padding) || padding > 256)
            throw new BridgeException("invalid_state", "The destination collision query exceeds the bounded preflight area.");
        requireLoadedChunks(world, MathHelper.floor_double((body.minX - padding) / 16.0D),
                MathHelper.floor_double((body.minZ - padding) / 16.0D),
                MathHelper.floor_double((body.maxX + padding) / 16.0D),
                MathHelper.floor_double((body.maxZ + padding) / 16.0D));
    }

    private static void requireLoadedChunks(WorldServer world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.getChunkProvider().chunkExists(x, z))
                    throw new BridgeException("chunk_unloaded", "Destination collision and player tracking chunks must already be loaded.");
            }
        }
    }

    private static boolean arrived(EntityPlayerMP player, WorldServer destination, TeleportPolicy.Target target,
            AxisAlignedBB body, ExactTeleporter teleporter) {
        return teleporter.placed && !player.isDead && player.playerNetServerHandler != null
                && player.dimension == target.dimension && player.worldObj == destination && player.boundingBox != null
                && destination.provider.dimensionId == target.dimension
                && player.posX == target.x && player.posY == target.y && player.posZ == target.z
                && player.rotationYaw == teleporter.yaw && player.rotationPitch == teleporter.pitch
                && player.boundingBox.minX == body.minX && player.boundingBox.minY == body.minY
                && player.boundingBox.minZ == body.minZ && player.boundingBox.maxX == body.maxX
                && player.boundingBox.maxY == body.maxY && player.boundingBox.maxZ == body.maxZ;
    }

    private static JsonObject diagnostic(String stage, RuntimeException failure) {
        JsonObject result = new JsonObject();
        result.addProperty("stage", stage);
        result.addProperty("exception", JsonData.bounded(failure.getClass().getName(), 256));
        result.addProperty("message", JsonData.bounded(failure.getMessage(), 512));
        return result;
    }

    private static JsonObject position(EntityPlayerMP player) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", player.dimension);
        if (player.worldObj != null && player.worldObj.provider != null) {
            result.addProperty("worldDimension", player.worldObj.provider.dimensionId);
            result.addProperty("worldName", JsonData.bounded(player.worldObj.getWorldInfo().getWorldName(), 128));
        }
        result.addProperty("x", player.posX);
        result.addProperty("y", player.posY);
        result.addProperty("z", player.posZ);
        result.addProperty("yaw", player.rotationYaw);
        result.addProperty("pitch", player.rotationPitch);
        result.addProperty("flying", player.capabilities.isFlying);
        result.addProperty("allowFlying", player.capabilities.allowFlying);
        result.addProperty("onGround", player.onGround);
        result.addProperty("alive", !player.isDead);
        return result;
    }

    private static class ExactTeleporter extends Teleporter {
        final TeleportPolicy.Target target;
        final float yaw;
        final float pitch;
        boolean placed;

        ExactTeleporter(WorldServer destination, TeleportPolicy.Target target, float yaw, float pitch) {
            super(destination);
            this.target = target;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public void placeInPortal(Entity entity, double oldX, double oldY, double oldZ, float oldYaw) {
            entity.setPositionAndRotation(target.x, target.y, target.z, yaw, pitch);
            placed = true;
        }
    }

    private static class Snapshot {
        final ItemStack[] inventory;
        final ItemStack cursor;
        final int selectedSlot;
        final NBTTagCompound capabilities;
        final JsonObject json;

        Snapshot(EntityPlayerMP player) {
            IInventory source = player.inventory;
            inventory = new ItemStack[source.getSizeInventory()];
            JsonArray slots = new JsonArray();
            for (int i = 0; i < inventory.length; i++) {
                inventory[i] = ItemStack.copyItemStack(source.getStackInSlot(i));
                JsonObject slot = new JsonObject();
                slot.addProperty("index", i);
                slot.add("item", item(inventory[i]));
                slots.add(slot);
            }
            cursor = ItemStack.copyItemStack(player.inventory.getItemStack());
            selectedSlot = player.inventory.currentItem;
            capabilities = new NBTTagCompound();
            player.capabilities.writeCapabilitiesToNBT(capabilities);
            json = position(player);
            JsonObject inventoryJson = new JsonObject();
            inventoryJson.addProperty("totalSlots", inventory.length);
            inventoryJson.addProperty("selectedSlot", selectedSlot);
            inventoryJson.add("cursor", item(cursor));
            inventoryJson.add("slots", slots);
            inventoryJson.addProperty("comparisonIncludesAllSlotsCountsAndNbt", true);
            json.add("inventory", inventoryJson);
            JsonObject flags = new JsonObject();
            flags.addProperty("disableDamage", player.capabilities.disableDamage);
            flags.addProperty("isFlying", player.capabilities.isFlying);
            flags.addProperty("allowFlying", player.capabilities.allowFlying);
            flags.addProperty("isCreativeMode", player.capabilities.isCreativeMode);
            flags.addProperty("allowEdit", player.capabilities.allowEdit);
            flags.addProperty("flySpeed", player.capabilities.getFlySpeed());
            flags.addProperty("walkSpeed", player.capabilities.getWalkSpeed());
            json.add("capabilities", flags);
        }

        boolean sameInventory(Snapshot other) {
            if (selectedSlot != other.selectedSlot || inventory.length != other.inventory.length
                    || !ItemStack.areItemStacksEqual(cursor, other.cursor)) return false;
            for (int i = 0; i < inventory.length; i++)
                if (!ItemStack.areItemStacksEqual(inventory[i], other.inventory[i])) return false;
            return true;
        }

        boolean sameCapabilities(Snapshot other) {
            return capabilities.equals(other.capabilities);
        }

        private static JsonElement item(ItemStack stack) {
            JsonElement result = JsonData.item(stack);
            if (stack != null && stack.hasTagCompound()) {
                String nbt = stack.getTagCompound().toString();
                result.getAsJsonObject().addProperty("tagNbt", JsonData.bounded(nbt, 512));
                result.getAsJsonObject().addProperty("tagNbtTruncated", nbt.length() > 512);
            }
            return result;
        }
    }
}
