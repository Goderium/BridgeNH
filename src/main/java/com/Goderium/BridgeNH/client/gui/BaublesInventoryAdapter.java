package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Fixed optional public Baubles APIs. No registry-specific equipment or inventory writes. */
public final class BaublesInventoryAdapter {
    public static final String READ = "baubles.inspect", OPEN = "gui.open_baubles";
    private static final String SLOT = "baubles.common.container.SlotBauble";
    private static volatile Method inventoryMethod;

    public static boolean serverRead(String operation, JsonObject args) {
        return READ.equals(operation) && "server".equals(source(args));
    }

    private static String source(JsonObject args) {
        String source = args.has("source") ? JsonData.string(args, "source") : "client";
        if (!"client".equals(source) && !"server".equals(source))
            throw new BridgeException("invalid_args", "Baubles source must be client or server.");
        return source;
    }

    public static IInventory inventory(EntityPlayer player) {
        if (!Loader.isModLoaded("Baubles"))
            throw new BridgeException("unsupported", "The optional public Baubles API is not installed.");
        try {
            Method api = inventoryMethod;
            if (api == null) {
                api = Class.forName("baubles.api.BaublesApi", false, player.getClass().getClassLoader())
                        .getMethod("getBaubles", EntityPlayer.class);
                inventoryMethod = api;
            }
            Object result = api.invoke(null, player);
            if (!(result instanceof IInventory))
                throw new BridgeException("baubles_inventory_unavailable", "The current public Baubles inventory is unavailable.");
            return (IInventory) result;
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new BridgeException("unsupported", "The fixed public Baubles inventory API is unavailable.");
        }
    }

    public static JsonObject open(EntityPlayer player) {
        inventory(player); // Resolve optional public API before dispatch; this never equips an item.
        if (player.isDead || player.getHealth() <= 0 || !player.worldObj.isRemote)
            throw new BridgeException("invalid_state", "Open Baubles only for the current living client player.");
        if (player.inventory.getItemStack() != null)
            throw new BridgeException("invalid_state", "An empty cursor is required before opening another inventory.");
        try {
            ClassLoader loader = player.getClass().getClassLoader();
            Object channel = Class.forName("baubles.common.network.PacketHandler", false, loader).getField("INSTANCE").get(null);
            Object packet = Class.forName("baubles.common.network.PacketOpenBaublesInventory", false, loader).getConstructor().newInstance();
            if (!(channel instanceof SimpleNetworkWrapper) || !(packet instanceof IMessage))
                throw new BridgeException("unsupported", "The public normal Baubles open packet is unavailable.");
            ((SimpleNetworkWrapper) channel).sendToServer((IMessage) packet);
            JsonObject result = new JsonObject(); result.addProperty("submitted", true);
            result.addProperty("source", "normal_Baubles_keybinding_packet");
            result.addProperty("playerId", player.getUniqueID().toString());
            result.addProperty("actorEntityId", player.getEntityId()); result.addProperty("dimension", player.dimension);
            result.addProperty("serverContainerConfirmed", false); result.addProperty("replayAllowed", false);
            result.addProperty("scope", "Normal PacketOpenBaublesInventory submitted once. Require a fresh actual GUI/window and matching server container before slot clicks; submission is not equipment or flight proof.");
            return result;
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new BridgeException("unsupported", "The fixed public normal Baubles open API is unavailable.");
        }
    }

    public static JsonObject inspect(EntityPlayer player, JsonObject args) {
        String source = source(args);
        if (player == null || player.worldObj == null || player.isDead || player.getHealth() <= 0)
            throw new BridgeException("world_unavailable", "A current living player is required for accessory inspection.");
        if (player.worldObj.isRemote != "client".equals(source))
            throw new BridgeException("invalid_state", "Accessory source does not match the owning game thread.");
        IInventory bag = inventory(player);
        int total = bag.getSizeInventory();
        if (total < 0 || total > 256)
            throw new BridgeException("unsupported", "This accessory inventory exceeds the bounded 256-slot public contract.");
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 256) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 32) : 32;
        if (offset > total) throw new BridgeException("invalid_args", "Accessory offset exceeds the actual inventory size.");
        boolean includePlayer = GuiDataAdapter.booleanArg(args, "includePlayerInventory", false);
        ItemStack candidate = null;
        int candidateIndex = -1;
        if (args.has("candidateInventoryIndex")) {
            candidateIndex = JsonData.integer(args, "candidateInventoryIndex", 0, player.inventory.getSizeInventory() - 1);
            candidate = player.inventory.getStackInSlot(candidateIndex);
        }
        JsonObject result = new JsonObject(); result.addProperty("supported", true); result.addProperty("readOnly", true);
        result.addProperty("source", "server".equals(source) ? "integrated_server" : "client");
        result.addProperty("playerId", player.getUniqueID().toString()); result.addProperty("actorEntityId", player.getEntityId());
        result.addProperty("dimension", player.dimension); result.addProperty("worldTick", player.worldObj.getTotalWorldTime());
        result.addProperty("inventoryClass", bag.getClass().getName()); result.addProperty("totalSlots", total);
        result.addProperty("offset", offset); result.addProperty("limit", limit);
        JsonArray rows = new JsonArray();
        for (int index = offset; index < Math.min(total, offset + limit); index++) {
            JsonObject row = new JsonObject(); row.addProperty("index", index);
            row.add("item", SurvivalConstructionAdapter.item(bag.getStackInSlot(index)));
            Slot bound = null; int matches = 0;
            if (player.openContainer != null) {
                for (Object entry : player.openContainer.inventorySlots) {
                    if (!(entry instanceof Slot)) continue;
                    Slot slot = (Slot) entry;
                    if (slot.inventory == bag && slot.getSlotIndex() == index) { matches++; bound = slot; }
                }
            }
            row.addProperty("nativeSlotMatches", matches); row.addProperty("nativeSlotBindingAvailable", matches == 1);
            if (matches == 1) {
                row.addProperty("guiSlot", bound.slotNumber); row.addProperty("windowId", player.openContainer.windowId);
                row.addProperty("slotClass", bound.getClass().getName()); describeSlot(row, bound);
                row.addProperty("canTakeStack", bound.canTakeStack(player));
            }
            if (candidateIndex >= 0) {
                row.addProperty("candidatePresent", candidate != null);
                if (candidate != null) {
                    row.addProperty("inventoryAllowsCandidate", bag.isItemValidForSlot(index, candidate));
                    if (matches == 1) row.addProperty("nativeSlotAllowsCandidate", bound.isItemValid(candidate));
                }
            }
            rows.add(row);
        }
        result.add("slots", rows); boolean more = offset + rows.size() < total;
        result.addProperty("complete", offset == 0 && !more); result.addProperty("partial", offset != 0 || more);
        result.addProperty("hasMore", more); if (more) result.addProperty("nextOffset", offset + rows.size());
        if (candidateIndex >= 0) {
            result.addProperty("candidateInventoryIndex", candidateIndex);
            result.add("candidateItem", SurvivalConstructionAdapter.item(candidate));
        }
        result.add("cursorItem", SurvivalConstructionAdapter.item(player.inventory.getItemStack()));
        result.addProperty("creative", player.capabilities.isCreativeMode);
        result.addProperty("allowFlying", player.capabilities.allowFlying); result.addProperty("flying", player.capabilities.isFlying);
        if (includePlayer) result.add("playerInventory", completeInventory(player.inventory));
        result.addProperty("nonAtomic", true);
        result.addProperty("scope", "Current public accessory inventory on its owning thread. Exact native Slot.inventory/index binding is available only for the actual open container. Slot acceptance is not equipment, server synchronization or flight proof; compare fresh source/destination/cursor and actual abilities after normal slot clicks. Client/server reads are separate observations.");
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > 131072)
            throw new BridgeException("result_too_large", "Complete accessory identities exceed 128 KiB; read a smaller slot page without the optional player inventory.");
        return result;
    }

    private static JsonObject completeInventory(IInventory inventory) {
        JsonObject result = JsonData.inventory(inventory);
        if (result.get("truncated").getAsBoolean())
            throw new BridgeException("inventory_observation_unavailable", "The full player inventory exceeds the bounded observation.");
        for (JsonElement entry : result.getAsJsonArray("slots")) {
            JsonObject row = entry.getAsJsonObject();
            row.add("item", SurvivalConstructionAdapter.item(inventory.getStackInSlot(row.get("index").getAsInt())));
        }
        result.addProperty("complete", true); return result;
    }

    public static void describeSlot(JsonObject row, Slot slot) {
        if (!ExpandedGuiApi.is(slot, SLOT)) return;
        row.addProperty("accessorySlot", true);
        try {
            Object type = ExpandedGuiApi.call(slot, "getSlotType");
            if (type instanceof String) row.addProperty("accessorySlotType", JsonData.bounded((String) type, 128));
            else row.addProperty("accessorySlotTypeUnavailable", true);
        } catch (BridgeException error) { row.addProperty("accessorySlotTypeUnavailable", true); }
    }
}
