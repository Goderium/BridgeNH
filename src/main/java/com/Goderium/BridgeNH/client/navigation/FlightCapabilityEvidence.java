package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.client.gui.BaublesInventoryAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.Loader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** One bounded session-memory result. Equipment/permission/world changes invalidate it. No abilities are granted. */
public final class FlightCapabilityEvidence {
    private String binding;
    private JsonObject result;
    private long recordedAt;
    public void clear() { binding = null; result = null; recordedAt = 0; }
    static String key(String session, int dimension, String player, boolean creative, boolean clientAllowed,
            boolean serverAllowed, String equipment) {
        return session + ":" + dimension + ":" + player + ":" + creative + ":" + clientAllowed + ":" + serverAllowed + ":" + equipment;
    }
    public JsonObject observe(EntityPlayer player, String session, JsonObject snapshot) {
        JsonObject unknown = new JsonObject(); unknown.addProperty("tested", false); unknown.add("canFly", JsonNull.INSTANCE);
        unknown.addProperty("status", "unknown"); unknown.addProperty("source", "native_client_server_flight_test");
        if (player == null || !snapshot.has("serverControl")) { clear(); return unknown; }
        JsonObject control = snapshot.getAsJsonObject("serverControl");
        if (!control.has("available") || !control.get("available").getAsBoolean() || !control.has("pose")
                || !player.getUniqueID().toString().equals(PlayerNavigation.text(control, "playerId"))
                || !session.equals(PlayerNavigation.text(control, "sessionId"))
                || JsonData.integer(control, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != player.dimension
                || PlayerNavigation.flag(control, "creative") != player.capabilities.isCreativeMode
                || !"integrated_server_tick".equals(PlayerNavigation.text(control, "source"))
                || JsonData.number(control, "ageMillis", 0, 10000) > 1000) { clear(); return unknown; }
        JsonObject pose = control.getAsJsonObject("pose");
        String equipment;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("player-inventory:".getBytes(StandardCharsets.UTF_8)); inventory(digest, player.inventory);
            if (Loader.isModLoaded("Baubles")) { digest.update("baubles:".getBytes(StandardCharsets.UTF_8)); inventory(digest, BaublesInventoryAdapter.inventory(player)); }
            StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            equipment = hex.toString();
        } catch (Exception failure) { clear(); unknown.addProperty("reason", "equipment_binding_unavailable"); return unknown; }
        boolean clientAllowed = player.capabilities.allowFlying, serverAllowed = PlayerNavigation.flag(pose, "allowFlying");
        String current = key(session, player.dimension, player.getUniqueID().toString(), player.capabilities.isCreativeMode, clientAllowed, serverAllowed, equipment);
        if (!current.equals(binding) || System.nanoTime() - recordedAt > 300000000000L) clear();
        if (!clientAllowed || !serverAllowed) record(current, false, "actual_client_server_permission_unavailable", PlayerNavigation.sample(control));
        else if (player.capabilities.isFlying && PlayerNavigation.flag(pose, "flying") && !player.onGround && !PlayerNavigation.flag(pose, "onGround"))
            record(current, true, "actual_client_server_airborne_flight", PlayerNavigation.sample(control));
        if (result == null) { unknown.addProperty("nextAction", "Use mcb_flight_check action=probe; existing permission alone is not a successful takeoff."); return unknown; }
        JsonObject value = result.deepCopy(); value.addProperty("ageMillis", Math.max(0, System.nanoTime() - recordedAt) / 1000000.0);
        value.addProperty("equipmentFingerprint", equipment); value.addProperty("binding", "session_world_dimension_player_mode_equipment_client_server_permissions");
        value.addProperty("ttlMillis", 300000); return value;
    }
    private void record(String key, boolean canFly, String source, long sample) {
        binding = key; recordedAt = System.nanoTime(); result = new JsonObject();
        result.addProperty("tested", true); result.addProperty("canFly", canFly); result.addProperty("status", canFly ? "confirmed" : "unavailable");
        result.addProperty("source", source); result.addProperty("sampleId", sample); result.addProperty("memoryOnly", true);
    }
    private static void inventory(MessageDigest digest, IInventory inventory) {
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i); String value = "empty";
            if (stack != null) { NBTTagCompound tag = new NBTTagCompound(); stack.writeToNBT(tag); value = tag.toString(); }
            if (value.length() > 65536) throw new BridgeException("unsupported", "Equipment binding exceeds the bounded NBT budget.");
            digest.update((i + ":" + value + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
