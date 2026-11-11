package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTTagCompound;

/** The client's synchronized AE state, using exact RC1 API names; no drawing or world access. */
public final class AeDataAdapter {
    public static final String GUI = "appeng.client.gui.implementations.";
    static final String CONTAINER = "appeng.container.implementations.";
    static final String STACK = "appeng.api.storage.data.IAEStack";
    static final int SCAN_LIMIT = 4096, BYTE_LIMIT = 128 * 1024;
    private static Object boundScreen;
    private static String boundId;
    private static long nextResource;
    private static final Map<String, Resource> resources = new LinkedHashMap<String, Resource>();
    private static final Map<Integer, List<Resource>> identities = new HashMap<Integer, List<Resource>>();

    private AeDataAdapter() {}
    static int inventorySize(Object inventory) {
        return inventory instanceof IInventory ? ((IInventory) inventory).getSizeInventory()
                : ((Number) ExpandedGuiApi.call(inventory, "getSizeInventory")).intValue();
    }
    static ItemStack inventoryItem(Object inventory, int slot) {
        return inventory instanceof IInventory ? ((IInventory) inventory).getStackInSlot(slot)
                : (ItemStack) ExpandedGuiApi.call(inventory, "getStackInSlot", slot);
    }
    public static synchronized void invalidate() { boundScreen = null; boundId = null; resources.clear(); identities.clear(); }
    static synchronized void bind(Object screen, String id) {
        if (screen != boundScreen || !id.equals(boundId)) { invalidate(); boundScreen = screen; boundId = id; }
    }
    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject args) {
        return read(screen, screen instanceof GuiContainer ? ((GuiContainer) screen).inventorySlots : null, args, System.nanoTime());
    }
    static JsonObject read(Object screen, Object container, JsonObject args, long started) {
        ExpandedGuiApi.keys(args, "scope", "offset", "limit");
        if (args.has("action")) throw new BridgeException("invalid_args", "ae.inspect has no action argument.");
        String id = ExpandedGuiApi.string(args, "screenId", 128); bind(screen, id);
        String scope = ExpandedGuiApi.option(args, "scope", "auto", "auto", "terminal", "pattern", "cell_config", "preview", "cpu", "interfaces", "requests", "configuration", "owner");
        if (scope.equals("auto")) scope = defaultScope(screen);
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        Page page = new Page(offset, limit, started);
        JsonObject state = new JsonObject();
        if (scope.equals("configuration")) state = AeConfiguration.read(container);
        else if (scope.equals("owner")) state.add("owner", AeConfiguration.owner(container));
        else if (scope.equals("terminal")) terminal(screen, state, page);
        else if (scope.equals("pattern")) pattern(screen, container, state, page);
        else if (scope.equals("cell_config")) cellConfig(screen, container, state, page);
        else if (scope.equals("preview")) preview(screen, container, state, page);
        else if (scope.equals("cpu")) cpu(screen, container, state, page);
        else if (scope.equals("requests")) requests(screen, state, page);
        else interfaces(screen, state, page);
        virtualSlots(screen, state);
        JsonObject result = new JsonObject();
        result.addProperty("screenId", id); result.addProperty("scope", scope);
        if (container instanceof net.minecraft.inventory.Container) result.addProperty("windowId", ((net.minecraft.inventory.Container) container).windowId);
        result.add("state", state); page.finish(result);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("source", "AE rv3-beta-1073/1080-GTNH synchronized client GUI/container state; unloaded or inaccessible server networks are not inspected.");
        coverage.addProperty("identity", "Original AEStack type and normalized full NBT; decimal-string long counts; resource IDs expire with screenId and are revalidated against current live storage.");
        coverage.addProperty("scope", scope.equals("terminal") ? "Current filtered/sorted ItemRepo.view, independent of scroll; view-cell/type/search filters apply." : scope.equals("pattern") ? "All synchronized input/output phantom storage slots including hidden pages; native geometry and mode state." : scope.equals("cell_config") ? "Current CellWorkbench synchronized config inventory, including empty/hidden phantom slots and the inserted cell's accepted AEStack type. Client preview may await server synchronization; configuration does not prove cell storage contents or server acceptance." : scope.equals("preview") ? "Full synchronized storage/pending/missing collections; may be incomplete while server computes/synchronizes preview." : scope.equals("cpu") ? "Synchronized CPU summaries and currently selected job visual entries; per-item stored/active/pending and scheduling reason." : "Synchronized interface entry inventory/location/priority/type capability; current local filters reported separately, no hover lookup.");
        result.add("coverage", coverage);
        if (scope.equals("requests")) coverage.addProperty("scope", "AE2FC synchronized request/maintainer providers and slots, independent of local search/scroll. Counts are decimal strings. Native entry craft click is not implemented in this version; this is observation, not an order submission API.");
        if (utf8(result) > BYTE_LIMIT) throw new BridgeException("too_large", "AE response exceeded its bounded output budget.");
        return result;
    }
    private static String defaultScope(Object screen) {
        if (ExpandedGuiApi.is(screen, "com.glodblock.github.client.gui.GuiLevelTerminal")) return "requests";
        if (ExpandedGuiApi.is(screen, GUI + "GuiPatternTerm")) return "pattern";
        if (ExpandedGuiApi.is(screen, GUI + "GuiCellWorkbench")) return "cell_config";
        if (ExpandedGuiApi.is(screen, GUI + "GuiMEMonitorable")) return "terminal";
        if (ExpandedGuiApi.is(screen, GUI + "GuiCraftConfirm")) return "preview";
        if (ExpandedGuiApi.is(screen, GUI + "GuiCraftingCPU")) return "cpu";
        if (ExpandedGuiApi.is(screen, GUI + "GuiInterfaceTerminal")) return "interfaces";
        throw new BridgeException("unsupported", "Open an AE terminal, pattern terminal, cell workbench, preview, CPU or interface terminal.");
    }
    static void require(Object target, String type) {
        if (!ExpandedGuiApi.is(target, type)) throw new BridgeException("unsupported", "Current screen/container is not " + type.substring(type.lastIndexOf('.') + 1));
    }
    static List<?> terminalView(Object screen) {
        require(screen, GUI + "GuiMEMonitorable");
        Object repo = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "repo");
        return (List<?>) ExpandedGuiApi.field(repo, "appeng.client.me.ItemRepo", "view");
    }
    private static void terminal(Object screen, JsonObject state, Page page) {
        List<?> view = terminalView(screen);
        Object repo = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "repo");
        state.addProperty("search", String.valueOf(ExpandedGuiApi.call(repo, "getSearchString")));
        state.addProperty("powered", (Boolean) ExpandedGuiApi.call(repo, "hasPower"));
        state.addProperty("paused", (Boolean) ExpandedGuiApi.call(repo, "isPaused"));
        state.addProperty("sortBy", String.valueOf(ExpandedGuiApi.call(screen, "getSortBy")));
        state.addProperty("sortDirection", String.valueOf(ExpandedGuiApi.call(screen, "getSortDir")));
        state.addProperty("viewMode", String.valueOf(ExpandedGuiApi.call(screen, "getSortDisplay")));
        Object config = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "configSrc");
        JsonObject options = new JsonObject();
        for (String setting : new String[] {"SORT_BY", "SORT_DIRECTION", "VIEW_MODE"}) {
            Object key = ExpandedGuiApi.enumeration(screen, "appeng.api.config.Settings", setting);
            Object possible = ExpandedGuiApi.call(key, "getPossibleValues");
            JsonArray values = new JsonArray(); if (possible instanceof Iterable<?>) for (Object value : (Iterable<?>) possible) values.add(new JsonPrimitive(String.valueOf(value)));
            options.add(setting, values);
        }
        state.add("settingOptions", options);
        Object filters = ExpandedGuiApi.call(screen, "getTypeFilter");
        JsonArray types = new JsonArray();
        if (filters instanceof Map<?, ?>) {
            int n = 0;
            for (Map.Entry<?, ?> filter : ((Map<?, ?>) filters).entrySet()) {
                if (++n > 64) break;
                JsonObject entry = new JsonObject(); entry.addProperty("type", String.valueOf(ExpandedGuiApi.call(filter.getKey(), "getId")));
                entry.addProperty("enabled", Boolean.TRUE.equals(filter.getValue())); types.add(entry);
            }
        }
        state.add("typeFilters", types); state.addProperty("totalResources", view.size());
        for (int i = 0; i < view.size() && page.canContinue(); i++) { final int index = i; final Object stack = view.get(i); page.add(() -> { JsonObject entry = stack(stack); entry.addProperty("viewIndex", index); return entry; }); }
        page.more = page.cursor < view.size();
    }
    static Object patternInventory(Object container, String side) {
        require(container, CONTAINER + "ContainerPatternTerm");
        Object sync = ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTerm", side.equals("input") ? "inputsSync" : "outputsSync");
        return ExpandedGuiApi.call(sync, "get");
    }
    static Object cellConfigSync(Object container) {
        require(container, CONTAINER + "ContainerCellWorkbench");
        return ExpandedGuiApi.field(container, CONTAINER + "ContainerCellWorkbench", "configSync");
    }
    static Object cellConfigInventory(Object container) { return ExpandedGuiApi.call(cellConfigSync(container), "get"); }
    private static void cellConfig(Object screen, Object container, JsonObject state, Page page) {
        require(screen, GUI + "GuiCellWorkbench");
        Object inventory = cellConfigInventory(container);
        int size = inventorySize(inventory);
        if (size < 1 || size > 63) throw new BridgeException("unsupported", "CellWorkbench config inventory exceeds the validated native slot range.");
        Object type = ExpandedGuiApi.call(container, "getStackType");
        state.addProperty("haveCell", (Boolean) ExpandedGuiApi.call(container, "haveCell"));
        state.add("acceptedType", type == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(ExpandedGuiApi.call(type, "getId"))));
        state.addProperty("typeUnrestricted", type == null);
        state.addProperty("totalSlots", size);
        state.addProperty("slotAddressing", "Zero-based config inventory indices, not ordinary container slot numbers.");
        for (int i = 0; i < size && page.canContinue(); i++) {
            final int slot = i;
            page.add(() -> { Object value = ExpandedGuiApi.call(inventory, "getAEStackInSlot", slot); JsonObject entry = new JsonObject(); entry.addProperty("slot", slot); entry.addProperty("phantom", true); entry.add("resource", value == null ? JsonNull.INSTANCE : stack(value)); return entry; });
        }
        page.more = page.cursor < size;
    }
    private static void pattern(Object screen, Object container, JsonObject state, Page page) {
        require(screen, GUI + "GuiPatternTerm"); require(container, CONTAINER + "ContainerPatternTerm");
        for (String key : new String[] {"craftingMode", "substitute", "beSubstitute"}) state.addProperty(key, (Boolean) ExpandedGuiApi.call(ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTerm", key + "Sync"), "get"));
        state.addProperty("craftingModeSupport", (Boolean) ExpandedGuiApi.call(container, "getCraftingModeSupport"));
        for (String side : new String[] {"input", "output"}) {
            String cap = side.equals("input") ? "Inputs" : "Outputs";
            JsonObject geometry = new JsonObject();
            geometry.addProperty("width", (Number) ExpandedGuiApi.call(container, "getPattern" + cap + "Width"));
            geometry.addProperty("height", (Number) ExpandedGuiApi.call(container, "getPattern" + cap + "Heigh"));
            geometry.addProperty("pages", (Number) ExpandedGuiApi.call(container, side.equals("input") ? "getPatternInputPages" : "getPatternOutputPages"));
            state.add(side + "Geometry", geometry);
        }
        if (ExpandedGuiApi.is(container, CONTAINER + "ContainerPatternTermEx")) {
            state.addProperty("inverted", (Boolean) ExpandedGuiApi.call(ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTermEx", "invertedSync"), "get"));
            state.addProperty("activePage", (Number) ExpandedGuiApi.call(ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTermEx", "activePageSync"), "get"));
        } else { state.addProperty("inverted", false); state.addProperty("activePage", 0); }
        int total = 0;
        for (String side : new String[] {"input", "output"}) {
            Object inventory = patternInventory(container, side);
            int size = inventorySize(inventory); total += size;
            for (int i = 0; i < size && page.canContinue(); i++) {
                final int slot = i; final String named = side;
                page.add(() -> { Object stack = ExpandedGuiApi.call(inventory, "getAEStackInSlot", slot); JsonObject entry = new JsonObject(); entry.addProperty("side", named); entry.addProperty("slot", slot); entry.addProperty("phantom", true); entry.add("resource", stack == null ? JsonNull.INSTANCE : stack(stack)); return entry; });
            }
        }
        state.addProperty("totalSlots", total); page.more = page.cursor < total;
    }
    private static void preview(Object screen, Object container, JsonObject state, Page page) {
        require(screen, GUI + "GuiCraftConfirm"); require(container, CONTAINER + "ContainerCraftConfirm");
        state.addProperty("bytesUsed", String.valueOf(ExpandedGuiApi.call(container, "getUsedBytes")));
        state.addProperty("simulation", (Boolean) ExpandedGuiApi.call(container, "isSimulation"));
        state.addProperty("noCpu", (Boolean) ExpandedGuiApi.call(container, "hasNoCPU"));
        state.addProperty("selectedCpuSerial", ((Number) ExpandedGuiApi.call(container, "getSelectedCpu")).intValue());
        boolean requestedPending = pendingPreviewTarget(container);
        Object target = requestedPending ? null : ExpandedGuiApi.call(container, "getItemToCraft");
        state.add("requested", target == null ? JsonNull.INSTANCE : stack(target));
        state.addProperty("requestedPending", requestedPending);
        state.addProperty("startReady", target != null && readyPreviewCpu(container));
        boolean complete = true;
        for (String section : new String[] {"storage", "pending", "missing"}) {
            Object values = ExpandedGuiApi.field(screen, GUI + "GuiCraftConfirm", section);
            if (!(values instanceof Iterable<?>)) throw new BridgeException("unsupported", "AE preview collection is unavailable.");
            for (Object value : (Iterable<?>) values) {
                if (!page.canContinue()) { complete = false; break; }
                final String kind = section;
                page.add(() -> { JsonObject entry = stack(value); entry.addProperty("section", kind); return entry; });
            }
            if (!complete) break;
        }
        page.more = !complete;
    }
    static boolean readyPreview(Object container) {
        if (pendingPreviewTarget(container) || ExpandedGuiApi.call(container, "getItemToCraft") == null) return false;
        return readyPreviewCpu(container);
    }
    private static boolean pendingPreviewTarget(Object container) {
        require(container, CONTAINER + "ContainerCraftConfirm");
        Object serialized = ExpandedGuiApi.field(container, CONTAINER + "ContainerCraftConfirm", "serializedItemToCraft");
        if (!(serialized instanceof String)) throw new BridgeException("unsupported", "AE synchronized preview target is not the expected serialized string.");
        // The native @GuiSync field starts empty; its public reader cannot deserialize that initial state.
        return ((String) serialized).isEmpty();
    }
    private static boolean readyPreviewCpu(Object container) {
        if ((Boolean) ExpandedGuiApi.call(container, "isSimulation") || (Boolean) ExpandedGuiApi.call(container, "hasNoCPU") || ((Number) ExpandedGuiApi.call(container, "getUsedBytes")).longValue() <= 0) return false;
        Object table = ExpandedGuiApi.field(container, CONTAINER + "ContainerCraftConfirm", "cpuTable");
        Object selected = ExpandedGuiApi.call(table, "getSelectedCPU");
        return selected != null && (Boolean) ExpandedGuiApi.call(container, "cpuMatches", selected);
    }
    static Object cpuTable(Object screen) {
        Object guiTable = ExpandedGuiApi.call(screen, "getCPUTable");
        return ExpandedGuiApi.call(guiTable, "getContainer");
    }
    private static void cpu(Object screen, Object container, JsonObject state, Page page) {
        require(screen, GUI + "GuiCraftingCPU"); require(container, CONTAINER + "ContainerCraftingCPU");
        state.add("providerLocator", AeNativeControls.locator(screen));
        state.addProperty("paused", (Boolean) ExpandedGuiApi.field(container, CONTAINER + "ContainerCraftingCPU", "cachedSuspend"));
        state.addProperty("allow", String.valueOf(ExpandedGuiApi.field(container, CONTAINER + "ContainerCraftingCPU", "allow")));
        state.addProperty("remainingOperations", ((Number) ExpandedGuiApi.field(screen, GUI + "GuiCraftingCPU", "remainingOperations")).intValue());
        if (ExpandedGuiApi.is(screen, GUI + "GuiCraftingStatus")) {
            Object table = cpuTable(screen);
            state.addProperty("selectedCpuSerial", ((Number) ExpandedGuiApi.field(table, CONTAINER + "ContainerCPUTable", "selectedCpuSerial")).intValue());
            List<?> cpus = (List<?>) ExpandedGuiApi.call(table, "getCPUs"); state.addProperty("totalCpus", cpus.size());
            for (Object value : cpus) { if (!page.canContinue()) break; page.add(() -> cpuSummary(value)); }
            if (page.cursor < cpus.size()) { page.more = true; return; }
        } else state.addProperty("cpuSelection", "fixed block CPU screen; no network selection table");
        Object visual = ExpandedGuiApi.field(screen, GUI + "GuiCraftingCPU", "visualState");
        Object entries = ExpandedGuiApi.field(visual, GUI + "GuiCraftingCPU$CraftingCpuVisualState", "entries");
        boolean complete = true;
        for (Object value : ((Map<?, ?>) entries).values()) {
            if (!page.canContinue()) { complete = false; break; }
            page.add(() -> {
                JsonObject entry = new JsonObject(); entry.addProperty("kind", "jobEntry"); entry.add("resource", stack(ExpandedGuiApi.call(value, "getStack")));
                for (String key : new String[] {"Stored", "Active", "Pending"}) entry.addProperty(key.toLowerCase(), String.valueOf(ExpandedGuiApi.call(value, "get" + key + "Amount")));
                entry.addProperty("scheduledReason", String.valueOf(ExpandedGuiApi.call(value, "getScheduledReason"))); return entry;
            });
        }
        page.more = !complete;
    }
    private static JsonObject cpuSummary(Object value) {
        JsonObject entry = new JsonObject(); entry.addProperty("kind", "cpu");
        entry.addProperty("serial", ((Number) ExpandedGuiApi.call(value, "getSerial")).intValue()); entry.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(value, "getName")), 512));
        for (String key : new String[] {"Storage", "UsedStorage", "Coprocessors", "TotalItems", "RemainingItems", "CraftingElapsedTime"}) {
            String method = key.equals("Coprocessors") ? "getCoprocessors" : "get" + key;
            entry.addProperty(Character.toLowerCase(key.charAt(0)) + key.substring(1), String.valueOf(ExpandedGuiApi.call(value, method)));
        }
        entry.addProperty("busy", (Boolean) ExpandedGuiApi.call(value, "isBusy")); entry.addProperty("paused", (Boolean) ExpandedGuiApi.call(value, "isSuspended"));
        entry.addProperty("allow", String.valueOf(ExpandedGuiApi.call(value, "allowMode")));
        Object crafting = ExpandedGuiApi.call(value, "getCrafting"); entry.add("crafting", crafting == null ? JsonNull.INSTANCE : stack(crafting)); return entry;
    }
    private static void requests(Object screen, JsonObject state, Page page) {
        String gui = "com.glodblock.github.client.gui.GuiLevelTerminal", entryClass = gui + "$LevelTerminalEntry";
        require(screen, gui);
        state.addProperty("online", (Boolean) ExpandedGuiApi.field(screen, gui, "online"));
        state.addProperty("nativeEntryCraftSupported", false);
        for (String name : new String[] {"Outputs", "Names"})
            state.addProperty("search" + name, String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.field(screen, gui, "searchField" + name), "getText")));
        Object list = ExpandedGuiApi.field(screen, gui, "masterList");
        Map<?, ?> map = (Map<?, ?>) ExpandedGuiApi.field(list, gui + "$LevelTerminalList", "list");
        List<Object> values = new ArrayList<Object>();
        for (Object value : map.values()) { if (values.size() >= SCAN_LIMIT) { page.scanTruncated = true; break; } values.add(value); }
        Collections.sort(values, Comparator.comparingLong(value -> ((Number) ExpandedGuiApi.field(value, entryClass, "id")).longValue()));
        state.addProperty("totalProviders", map.size());
        for (Object value : values) { if (!page.canContinue()) break; page.add(() -> requestEntry(value, entryClass)); }
        page.more = page.cursor < map.size();
    }
    private static JsonObject requestEntry(Object value, String owner) {
        JsonObject entry = new JsonObject(); entry.addProperty("providerId", String.valueOf(ExpandedGuiApi.field(value, owner, "id")));
        entry.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.field(value, owner, "customName")), 512));
        for (String key : new String[] {"x", "y", "z", "dim", "side", "rows", "rowSize"}) entry.addProperty(key, (Number) ExpandedGuiApi.field(value, owner, key));
        Object[] values = (Object[]) ExpandedGuiApi.field(value, owner, "infoList");
        JsonArray slots = new JsonArray(); int bytes = 0;
        if (values != null) for (int i = 0; i < Math.min(64, values.length); i++) {
            JsonObject slot = new JsonObject(); slot.addProperty("slot", i); Object info = values[i];
            if (info == null) slot.add("resource", JsonNull.INSTANCE);
            else {
                String type = "appeng.api.features.LevelItemInfo"; Object resource = ExpandedGuiApi.field(info, type, "stack");
                slot.add("resource", resource == null ? JsonNull.INSTANCE : stack(resource));
                for (String key : new String[] {"quantity", "batchSize", "state"}) slot.addProperty(key, String.valueOf(ExpandedGuiApi.field(info, type, key)));
            }
            int size = utf8(slot); if (bytes + size > 48 * 1024) break; bytes += size; slots.add(slot);
        }
        entry.add("slots", slots); entry.addProperty("slotsPending", values == null);
        entry.addProperty("totalSlots", values == null ? 0 : values.length);
        entry.addProperty("slotsTruncated", values != null && slots.size() < values.length); return entry;
    }
    private static void interfaces(Object screen, JsonObject state, Page page) {
        require(screen, GUI + "GuiInterfaceTerminal");
        for (String name : new String[] {"Inputs", "Outputs", "Names"}) {
            Object field = ExpandedGuiApi.field(screen, GUI + "GuiInterfaceTerminal", "searchField" + name);
            state.addProperty("search" + name, String.valueOf(ExpandedGuiApi.call(field, "getText")));
        }
        for (String key : new String[] {"online", "onlyMolecularAssemblers", "onlyBrokenRecipes", "onlySubstitute", "showHidden"}) state.addProperty(key, (Boolean) ExpandedGuiApi.field(screen, GUI + "GuiInterfaceTerminal", key));
        Object list = ExpandedGuiApi.field(screen, GUI + "GuiInterfaceTerminal", "masterList");
        Map<?, ?> map = (Map<?, ?>) ExpandedGuiApi.field(list, GUI + "GuiInterfaceTerminal$InterfaceTerminalList", "list");
        List<Object> values = new ArrayList<Object>();
        int visited = 0; for (Object value : map.values()) { if (++visited > SCAN_LIMIT) { page.scanTruncated = true; break; } values.add(value); }
        Collections.sort(values, Comparator.comparingLong(value -> ((Number) ExpandedGuiApi.field(value, GUI + "GuiInterfaceTerminal$InterfaceTerminalEntry", "id")).longValue()));
        state.addProperty("totalInterfaces", map.size());
        for (Object value : values) { if (!page.canContinue()) break; page.add(() -> interfaceEntry(value)); }
        page.more = page.cursor < map.size();
    }
    private static JsonObject interfaceEntry(Object value) {
        String owner = GUI + "GuiInterfaceTerminal$InterfaceTerminalEntry";
        JsonObject entry = new JsonObject(); entry.addProperty("interfaceId", String.valueOf(ExpandedGuiApi.field(value, owner, "id")));
        entry.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.field(value, owner, "dispName")), 512));
        for (String key : new String[] {"x", "y", "z", "dim", "side", "priority", "rows", "rowSize", "numSlots"}) entry.addProperty(key, (Number) ExpandedGuiApi.field(value, owner, key));
        for (String key : new String[] {"online", "p2pOutput", "terminalVisible", "isCraftingPatternProvider"}) entry.addProperty(key, (Boolean) ExpandedGuiApi.field(value, owner, key));
        Object inventory = ExpandedGuiApi.call(value, "getInventory"); int size = inventorySize(inventory);
        JsonArray slots = new JsonArray(); int slotBytes = 0; boolean slotsByteLimited = false;
        for (int i = 0; i < Math.min(size, 64); i++) {
            JsonObject slot = new JsonObject(); slot.addProperty("slot", i); Object item = inventoryItem(inventory, i);
            slot.add("item", item instanceof ItemStack ? actualItem((ItemStack) item) : JsonNull.INSTANCE);
            int bytes = utf8(slot);
            if (slotBytes + bytes > 48 * 1024) { slotsByteLimited = true; break; }
            slots.add(slot); slotBytes += bytes;
        }
        JsonArray types = new JsonArray(); Object array = ExpandedGuiApi.field(value, owner, "supportedStackTypes");
        if (array instanceof Object[]) for (int i = 0; i < Math.min(((Object[]) array).length, 64); i++) types.add(new JsonPrimitive(String.valueOf(ExpandedGuiApi.call(((Object[]) array)[i], "getId"))));
        entry.add("supportedTypes", types); entry.add("slots", slots); entry.addProperty("inventorySlots", size); entry.addProperty("slotsTruncated", slots.size() < size); entry.addProperty("slotsByteLimited", slotsByteLimited); return entry;
    }
    private static void virtualSlots(Object screen, JsonObject state) {
        if (!ExpandedGuiApi.is(screen, "appeng.client.gui.AEBaseGui")) return;
        Object source = ExpandedGuiApi.field(screen, "appeng.client.gui.AEBaseGui", "virtualSlots");
        if (!(source instanceof List<?>)) return;
        List<?> values = (List<?>) source; JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(64, values.size()); i++) {
            Object value = values.get(i); JsonObject entry = new JsonObject();
            entry.addProperty("slotIndex", (Number) ExpandedGuiApi.call(value, "getSlotIndex"));
            entry.addProperty("hidden", (Boolean) ExpandedGuiApi.call(value, "isHidden"));
            entry.addProperty("x", (Number) ExpandedGuiApi.call(value, "getX")); entry.addProperty("y", (Number) ExpandedGuiApi.call(value, "getY"));
            boolean phantom = ExpandedGuiApi.is(value, "appeng.client.gui.slots.VirtualMEPhantomSlot"); entry.addProperty("phantom", phantom);
            if (phantom) entry.addProperty("storage", String.valueOf(ExpandedGuiApi.call(value, "getStorageName")));
            Object stack = ExpandedGuiApi.call(value, "getAEStack");
            if (stack != null) { entry.addProperty("resourceId", remember(stack).id); entry.addProperty("amount", String.valueOf(ExpandedGuiApi.call(stack, "getStackSize"))); }
            slots.add(entry);
        }
        state.add("virtualSlots", slots); state.addProperty("totalVirtualSlots", values.size()); state.addProperty("virtualSlotsTruncated", values.size() > 64);
    }
    public static JsonObject actualItem(ItemStack item) {
        JsonObject result = JsonData.item(item).getAsJsonObject();
        if (item.hasTagCompound()) { String nbt = item.getTagCompound().toString(); result.addProperty("nbt", JsonData.bounded(nbt, 4096)); result.addProperty("nbtTruncated", nbt.length() > 4096); }
        return result;
    }
    static JsonObject stack(Object stack) {
        require(stack, STACK);
        Resource ref = remember(stack);
        JsonObject result = new JsonObject(); result.addProperty("resourceId", ref.id); result.addProperty("type", ref.type);
        result.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(stack, "getDisplayName")), 512));
        result.addProperty("amount", String.valueOf(ExpandedGuiApi.call(stack, "getStackSize"))); result.addProperty("requestable", String.valueOf(ExpandedGuiApi.call(stack, "getCountRequestable")));
        result.addProperty("craftable", (Boolean) ExpandedGuiApi.call(stack, "isCraftable"));
        Object icon = ExpandedGuiApi.call(stack, "getItemStackForNEI"); result.add("item", icon instanceof ItemStack ? actualItem((ItemStack) icon) : JsonNull.INSTANCE);
        String nbt = ref.identity.toString(); result.addProperty("identityNbt", JsonData.bounded(nbt, 4096)); result.addProperty("identityTruncated", nbt.length() > 4096);
        result.addProperty("amountPerUnit", (Number) ExpandedGuiApi.call(stack, "getAmountPerUnit")); return result;
    }
    static NBTTagCompound identity(Object value) {
        Object copy = ExpandedGuiApi.call(value, "copy");
        ExpandedGuiApi.call(copy, "setStackSize", 1L); ExpandedGuiApi.call(copy, "setCountRequestable", 0L); ExpandedGuiApi.call(copy, "setCountRequestableCrafts", 0L); ExpandedGuiApi.call(copy, "setCraftable", false);
        NBTTagCompound tag = new NBTTagCompound(); ExpandedGuiApi.call(copy, "writeToNBTGeneric", tag); return tag;
    }
    private static synchronized Resource remember(Object stack) {
        NBTTagCompound tag = identity(stack); String type = String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(stack, "getStackType"), "getId"));
        int hash = 31 * type.hashCode() + tag.hashCode(); List<Resource> bucket = identities.get(hash);
        if (bucket != null) for (Resource value : bucket) if (type.equals(value.type) && tag.equals(value.identity)) return value;
        if (resources.size() >= SCAN_LIMIT) throw new BridgeException("too_large", "Screen resource identity limit reached; reopen the screen to release old identities.");
        Resource value = new Resource("ae:" + (++nextResource), type, tag);
        if (bucket == null) { bucket = new ArrayList<Resource>(); identities.put(hash, bucket); } bucket.add(value); resources.put(value.id, value); return value;
    }
    static synchronized Object resolve(Object screen, Object container, String resourceId) {
        Resource expected = resources.get(resourceId);
        if (screen != boundScreen || expected == null) throw new BridgeException("stale_resource", "Resource ID is not bound to this current screen.");
        int visited = 0;
        if (ExpandedGuiApi.is(screen, GUI + "GuiMEMonitorable")) for (Object stack : terminalView(screen)) {
            if (++visited > SCAN_LIMIT) break; if (expected.matches(stack)) return stack;
        }
        if (ExpandedGuiApi.is(container, CONTAINER + "ContainerPatternTerm")) for (String side : new String[] {"input", "output"}) {
            Object inv = patternInventory(container, side); int size = inventorySize(inv);
            for (int i = 0; i < Math.min(size, 1024); i++) { Object stack = ExpandedGuiApi.call(inv, "getAEStackInSlot", i); if (stack != null && expected.matches(stack)) return stack; }
        }
        if (ExpandedGuiApi.is(screen, GUI + "GuiCellWorkbench") && ExpandedGuiApi.is(container, CONTAINER + "ContainerCellWorkbench")) {
            Object inv = cellConfigInventory(container); int size = inventorySize(inv);
            if (size > 63) throw new BridgeException("unsupported", "CellWorkbench config inventory exceeds the validated native slot range.");
            for (int i = 0; i < size; i++) { Object stack = ExpandedGuiApi.call(inv, "getAEStackInSlot", i); if (stack != null && expected.matches(stack)) return stack; }
        }
        throw new BridgeException("stale_resource", "The exact type/NBT resource is absent from the current live view, pattern storage and cell configuration.");
    }
    static final class Resource {
        final String id, type; final NBTTagCompound identity;
        Resource(String id, String type, NBTTagCompound identity) { this.id = id; this.type = type; this.identity = identity; }
        boolean matches(Object stack) { return stack != null && type.equals(String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.call(stack, "getStackType"), "getId"))) && identity.equals(identity(stack)); }
    }
    private interface Entry { JsonObject get(); }
    private static final class Page {
        final int offset, limit; final long started; final JsonArray entries = new JsonArray();
        int cursor, bytes = 16384, visited; boolean more, byteLimited, scanTruncated;
        Page(int offset, int limit, long started) { this.offset = offset; this.limit = limit; this.started = started; }
        boolean canContinue() {
            if (visited >= SCAN_LIMIT) { scanTruncated = true; return false; }
            return entries.size() < limit && !byteLimited && (entries.size() == 0 || System.nanoTime() - started < StructureAdapter.SOFT_BUDGET_NANOS);
        }
        void add(Entry getter) {
            if (cursor < offset) { cursor++; return; }
            visited++;
            JsonObject entry = getter.get(); int size = utf8(entry) + 1;
            if (bytes + size > BYTE_LIMIT - 4096) { byteLimited = true; return; }
            entries.add(entry); bytes += size; cursor++;
        }
        void finish(JsonObject result) {
            result.add("entries", entries); result.addProperty("offset", offset); result.addProperty("nextOffset", cursor);
            result.addProperty("hasMore", more); result.addProperty("visited", visited); result.addProperty("byteLimited", byteLimited); result.addProperty("scanTruncated", scanTruncated); result.addProperty("nonAtomic", true);
            result.add("budget", StructureAdapter.budget(started));
        }
    }
    private static int utf8(JsonObject object) { return object.toString().getBytes(StandardCharsets.UTF_8).length; }
}
