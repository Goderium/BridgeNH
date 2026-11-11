package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

/** Bounded owner-thread public AE observations, independent of whichever GUI happens to be open. */
public final class AeWorldAdapter {
    public static JsonObject inspect(Object tile) {
        if (!ExpandedGuiApi.is(tile, "appeng.api.networking.IGridHost") && !ExpandedGuiApi.is(tile, AePartAdapter.HOST)) return null;
        JsonObject result = new JsonObject(); result.addProperty("version", 1);
        JsonArray hosts = new JsonArray();
        if (ExpandedGuiApi.is(tile, AePartAdapter.HOST)) {
            for (int side = 0; side < 7; side++) {
                Object part = ExpandedGuiApi.call(tile, "getPart", ForgeDirection.getOrientation(side));
                if (part != null) hosts.add(host(part, side));
            }
        } else hosts.add(host(tile, -1));
        result.add("hosts", hosts); result.addProperty("readOnly", true); result.addProperty("nonAtomic", true);
        result.addProperty("source", "public_ae_owner_thread_apis");
        result.addProperty("coverage", "Exact mounted hosts, public interface waiting-to-send and storage-cell provider contents. CPU cancellation never implies interface buffer emptiness. No transfer, cancellation, flush, network substitution or cached success.");
        if (result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072)
            throw new BridgeException("result_too_large", "Complete AE host data exceeds its bounded observation size.");
        return result;
    }
    static JsonObject host(Object host, int side) {
        JsonObject result = new JsonObject(); result.addProperty("mountingSide", side); result.addProperty("hostClass", host.getClass().getName());
        try { result.add("configuration", AeConfiguration.host(host)); }
        catch (BridgeException error) { result.add("configuration", failure(error)); }
        result.add("network", network(host, side));
        if (ExpandedGuiApi.is(host, "appeng.helpers.IInterfaceHost")) {
            try { result.add("interface", interfaceState(ExpandedGuiApi.call(host, "getInterfaceDuality"))); }
            catch (BridgeException error) { result.add("interface", failure(error)); }
        }
        if (ExpandedGuiApi.is(host, "appeng.api.storage.ICellContainer")) {
            try { result.add("storage", storage(host)); }
            catch (BridgeException error) { result.add("storage", failure(error)); }
        }
        return result;
    }
    static JsonObject network(Object host, int mountingSide) {
        JsonObject result = new JsonObject(); JsonArray nodes = new JsonArray(); boolean complete = true;
        // A mounted part exposes one getGridNode() (reported as UNKNOWN/6).
        // Block hosts may expose different nodes on different public sides.
        for (int side = mountingSide >= 0 ? 6 : 0; side < 7; side++) {
            JsonObject row = new JsonObject(); row.addProperty("side", side);
            try {
                Object node = mountingSide >= 0 ? ExpandedGuiApi.call(host, "getGridNode")
                        : ExpandedGuiApi.call(host, "getGridNode", ForgeDirection.getOrientation(side));
                Object grid = node == null ? null : ExpandedGuiApi.call(node, "getGrid");
                row.addProperty("available", grid != null);
                if (grid != null) {
                    Object id = ExpandedGuiApi.call(grid, "getId");
                    if (id == null) throw new BridgeException("unsupported", "Public grid identity is unavailable.");
                    row.addProperty("gridId", String.valueOf(id));
                    row.addProperty("active", Boolean.TRUE.equals(ExpandedGuiApi.call(node, "isActive")));
                } else row.addProperty("reason", "public_node_or_grid_unavailable");
            } catch (BridgeException error) {
                complete = false; row = failure(error); row.addProperty("side", side);
            }
            nodes.add(row);
        }
        result.add("nodes", nodes); result.addProperty("complete", complete);
        result.addProperty("scope", "Current public grid identity per explicit node side; missing/offline data does not prove isolation.");
        return result;
    }
    static JsonObject interfaceState(Object duality) {
        JsonObject result = new JsonObject(); Object waiting = ExpandedGuiApi.call(duality, "getWaitingToSend");
        JsonArray rows = new JsonArray(); boolean complete = true;
        if (waiting != null && !(waiting instanceof List<?>)) throw new BridgeException("unsupported", "Public interface pending collection is unavailable.");
        if (waiting instanceof List<?>) {
            for (Object stack : (List<?>) waiting) {
                if (stack == null) continue;
                if (rows.size() >= 512) { complete = false; break; }
                rows.add(resource(stack));
            }
        }
        result.add("waitingToSend", rows); result.addProperty("complete", complete);
        result.addProperty("bufferEmpty", complete && rows.size() == 0);
        result.addProperty("cancelClearsBuffer", false); result.addProperty("swapRequiresNoActiveOrder", true);
        for (String name : new String[] {"patterns", "storage"}) {
            Object inventory = ExpandedGuiApi.call(duality, "getInventoryByName", name);
            if (inventory instanceof IInventory) result.add(name, inventory((IInventory) inventory));
        }
        return result;
    }
    static JsonObject storage(Object host) {
        Object channel = ExpandedGuiApi.enumeration(host, "appeng.api.storage.StorageChannel", "ITEMS");
        Object cells = ExpandedGuiApi.call(host, "getCellArray", channel);
        if (!(cells instanceof List<?>) || ((List<?>) cells).size() > 64) throw new BridgeException("unsupported", "Public cell array is unavailable or outside its bound.");
        Object api = ExpandedGuiApi.call(ExpandedGuiApi.load(host, "appeng.api.AEApi"), "instance");
        Object helper = ExpandedGuiApi.call(api, "storage"); JsonArray rows = new JsonArray(), providers = new JsonArray(); boolean complete = !((List<?>) cells).isEmpty();
        for (Object cell : (List<?>) cells) {
            JsonObject provider = backing(cell); int index = providers.size(); provider.addProperty("index", index); providers.add(provider);
            Object list = ExpandedGuiApi.call(helper, "createItemList");
            Object actual = ExpandedGuiApi.call(cell, "getAvailableItems", list);
            if (!(actual instanceof Iterable<?>)) throw new BridgeException("unsupported", "Public provider contents are unavailable.");
            for (Object stack : (Iterable<?>) actual) {
                if (rows.size() >= 512) { complete = false; break; }
                JsonObject row = resource(stack); row.addProperty("cellProviderIndex", index);
                row.addProperty("finiteBackingVerified", provider.get("finiteBackingVerified").getAsBoolean()); rows.add(row);
            }
            if (!complete) break;
        }
        JsonObject result = new JsonObject(); result.add("resources", rows); result.addProperty("complete", complete);
        result.add("providers", providers);
        result.addProperty("available", complete);
        if (((List<?>) cells).isEmpty()) result.addProperty("reason", "owner_exposes_no_item_cell_providers; offline_or_missing_backing_is_unknown");
        result.addProperty("cellProviders", ((List<?>) cells).size()); result.addProperty("empty", complete && rows.size() == 0);
        result.addProperty("scope", "This explicit host's native item-cell providers only. Creative/saturated amounts do not prove finite delivered materials; no global network fallback.");
        return result;
    }
    static JsonObject inventory(IInventory inventory) {
        int size = inventory.getSizeInventory();
        if (size < 0 || size > 256) throw new BridgeException("result_too_large", "Complete AE owner inventory exceeds its bounded slot scope.");
        JsonObject result = new JsonObject(); JsonArray slots = new JsonArray(); result.addProperty("totalSlots", size);
        for (int index = 0; index < size; index++) {
            JsonObject row = new JsonObject(); row.addProperty("index", index); ItemStack stack = inventory.getStackInSlot(index);
            if (stack == null) row.add("item", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject item = AeDataAdapter.actualItem(stack);
                if (item.has("nbtTruncated") && item.get("nbtTruncated").getAsBoolean())
                    throw new BridgeException("result_too_large", "Complete AE owner inventory NBT exceeds its bounded scope.");
                row.add("item", item);
            }
            slots.add(row);
        }
        result.add("slots", slots); result.addProperty("complete", true); return result;
    }
    static JsonObject resource(Object stack) {
        long count = ((Number) ExpandedGuiApi.call(stack, "getStackSize")).longValue();
        JsonObject row = new JsonObject(); row.addProperty("amount", Long.toString(count));
        Object type = ExpandedGuiApi.call(stack, "getStackType"); row.addProperty("type", String.valueOf(ExpandedGuiApi.call(type, "getId")));
        Object item = ExpandedGuiApi.call(stack, "getItemStackForNEI");
        if (!(item instanceof ItemStack)) throw new BridgeException("unsupported", "Public AEStack display identity is unavailable.");
        JsonObject identity = AeDataAdapter.actualItem((ItemStack) item);
        if (identity.has("nbtTruncated") && identity.get("nbtTruncated").getAsBoolean())
            throw new BridgeException("result_too_large", "A full delivery identity exceeds the bounded NBT scope.");
        identity.remove("count"); row.add("item", identity);
        if (count < 0) throw new BridgeException("inspection_failed", "Native AE resource quantity is negative; it is not finite delivery evidence.");
        row.addProperty("quantityUnsaturated", count < Long.MAX_VALUE); return row;
    }
    static JsonObject backing(Object handler) {
        JsonObject result = new JsonObject(); result.addProperty("finiteBackingVerified", false); result.addProperty("sourceMode", "unknown");
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        Object current = handler;
        try {
            for (int depth = 0; depth < 32 && current != null; depth++) {
                if (!visited.add(current)) break;
                String type = current.getClass().getName();
                if (type.equals("appeng.me.storage.ItemCellInventory") || type.equals("appeng.me.storage.CreativeCellInventory")) {
                    boolean finite = type.equals("appeng.me.storage.ItemCellInventory");
                    result.addProperty("backingClass", type); result.addProperty("finiteBackingVerified", finite);
                    result.addProperty("sourceMode", finite ? "finite" : "creative_infinite"); return result;
                }
                if (!ExpandedGuiApi.is(current, "appeng.api.storage.IMEInventoryHandler")
                        || ExpandedGuiApi.call(current, "getExternalNetworkInventory") != null) break;
                current = ExpandedGuiApi.call(current, ExpandedGuiApi.is(current, "appeng.api.storage.ICellInventoryHandler") ? "getCellInv" : "getInternal");
            }
        } catch (BridgeException unavailable) { result.addProperty("reason", unavailable.code); }
        result.addProperty("coverage", "Unknown, external, cyclic or nonnative backing never certifies finite delivered materials.");
        return result;
    }
    static JsonObject failure(BridgeException error) {
        JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("code", error.code);
        result.addProperty("message", error.getMessage()); result.addProperty("complete", false); return result;
    }
}
