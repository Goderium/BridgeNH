package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** One finite native-command preparation on the authoritative server thread. */
public final class NativeSupplyAdapter {
    public interface Access {
        String playerName();
        boolean creative();
        boolean inventoryEmpty();
        boolean cursorEmpty();
        boolean fits(JsonArray items);
        int stackLimit(String registryName, int metadata);
        int execute(String command);
    }

    public static JsonObject supply(JsonObject args, Access access) {
        JsonElement requested = args.get("items");
        if (requested == null || !requested.isJsonArray() || requested.getAsJsonArray().size() < 1 || requested.getAsJsonArray().size() > 8)
            throw new BridgeException("invalid_args", "Supply requires 1 through 8 exact distinct item identities.");
        String name = access.playerName();
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
            throw new BridgeException("unsupported", "The current server player's command identity is unavailable.");
        boolean preserve = GuiDataAdapter.booleanArg(args, "preserveInventory", false);
        if (!access.creative() || (!preserve && !access.inventoryEmpty()) || !access.cursorEmpty())
            throw new BridgeException("permission_denied", "Finite supply requires the current creative player, an empty cursor and either an empty inventory or explicit preserveInventory; nothing was supplied.");
        JsonArray items = requested.getAsJsonArray();
        String[] commands = new String[items.size()];
        Set<String> identities = new HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) throw new BridgeException("invalid_args", "Each supply must be an exact item object.");
            JsonObject item = items.get(i).getAsJsonObject();
            if (item.entrySet().size() != 3 || !item.has("registryName") || !item.has("metadata") || !item.has("count"))
                throw new BridgeException("invalid_args", "Supply items accept only registryName, metadata and count.");
            String registry = JsonData.string(item, "registryName");
            int metadata = JsonData.integer(item, "metadata", 0, 32766), count = JsonData.integer(item, "count", 1, 64);
            if (registry.length() > 128 || !registry.matches("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+") || !identities.add(registry + ":" + metadata))
                throw new BridgeException("invalid_args", "Supply identities must be exact, bounded and distinct.");
            int limit = access.stackLimit(registry, metadata);
            if (limit < 1 || count > limit)
                throw new BridgeException("invalid_args", "Supply requires a registered identity and count within its actual public stack limit.");
            commands[i] = "give " + name + " " + registry + " " + count + " " + metadata;
            if (commands[i].length() + 1 > 100)
                throw new BridgeException("invalid_args", "The native command exceeds the existing 100-character budget.");
        }
        if (!access.fits(items))
            throw new BridgeException("inventory_full", "The complete supply batch does not fit in ordinary inventory slots; nothing was supplied.");
        JsonObject result = new JsonObject();
        result.addProperty("preserveInventory", preserve); result.addProperty("capacityVerified", true);
        result.addProperty("capacitySlotsChecked", 36);
        result.addProperty("source", "native_server_give_commands"); result.addProperty("requestedCount", commands.length);
        int attempted = 0, accepted = 0; boolean unknown = false;
        JsonArray receipts = new JsonArray();
        for (String command : commands) {
            attempted++; JsonObject receipt = new JsonObject(); receipt.addProperty("index", attempted - 1);
            try {
                int value = access.execute(command); receipt.addProperty("commandResult", value); receipts.add(receipt);
                if (value <= 0) break;
                accepted++;
            } catch (RuntimeException error) {
                unknown = true; receipt.addProperty("outcomeUnknown", true); receipts.add(receipt); break;
            }
        }
        result.add("receipts", receipts); result.addProperty("commandsAttempted", attempted); result.addProperty("commandsAccepted", accepted);
        result.addProperty("complete", accepted == commands.length); result.addProperty("outcomeUnknown", unknown);
        result.addProperty("sideEffectsPossible", attempted > 0); result.addProperty("inventoryVerificationRequired", true);
        return result;
    }
}
