package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

/** Native remote pattern slots and provider locators exposed as ordinary GUI controls. */
public final class AeNativeControls {
    private static final String INTERFACE = AeDataAdapter.GUI + "GuiInterfaceTerminal", ENTRY = INTERFACE + "$InterfaceTerminalEntry", CPU = AeDataAdapter.GUI + "GuiCraftingCPU";
    private static Object screen;
    private static final IdentityHashMap<Object, Map<String, Control>> controls = new IdentityHashMap<Object, Map<String, Control>>();
    private static Request request;
    public static void invalidate() { screen = null; controls.clear(); request = null; }
    public static boolean supports(Object value) { return value instanceof Control; }
    private static Object field(Object v, String owner, String name) { return ExpandedGuiApi.field(v, owner, name); }

    public static List<Object> discover(Object current) {
        if (current != screen) { invalidate(); screen = current; }
        List<Object> result = new ArrayList<Object>();
        if (ExpandedGuiApi.is(current, INTERFACE)) {
            Object master = field(current, INTERFACE, "masterList");
            Map<?, ?> entries = (Map<?, ?>) field(master, INTERFACE + "$InterfaceTerminalList", "list");
            if (entries.size() > 128) throw new BridgeException("too_large", "Remote pattern controls exceed 128 synchronized providers.");
            List<Object> ordered = new ArrayList<Object>(entries.values());
            Collections.sort(ordered, Comparator.comparingLong(e -> ((Number) field(e, ENTRY, "id")).longValue()));
            for (Object entry : ordered) {
                result.add(control(entry, "interface_highlight", -1));
                int size = AeDataAdapter.inventorySize(ExpandedGuiApi.call(entry, "getInventory"));
                if (size > 64 || size < 0) throw new BridgeException("unsupported", "Native pattern inventory exceeds the supported slot bound.");
                for (int slot = 0; slot < size; slot++) result.add(control(entry, "interface_pattern_slot", slot));
            }
        } else if (ExpandedGuiApi.is(current, CPU)) {
            observe(current); Object visual = field(current, CPU, "visualState");
            Map<?, ?> entries = (Map<?, ?>) field(visual, CPU + "$CraftingCpuVisualState", "entries");
            if (entries.size() > 512) throw new BridgeException("too_large", "Native CPU job-entry bound exceeded.");
            for (Object entry : entries.values()) result.add(control(entry, "cpu_locate_providers", -1));
            if (request != null) result.add(control(request, "cpu_highlight_providers", -1));
        }
        return result;
    }
    private static Control control(Object target, String kind, int slot) {
        Map<String, Control> map = controls.get(target);
        if (map == null) { map = new LinkedHashMap<String, Control>(); controls.put(target, map); }
        String key = kind + ":" + slot; Control c = map.get(key);
        if (c == null) { c = new Control(screen, target, kind, slot); map.put(key, c); }
        return c;
    }
    public static JsonObject describe(Object value) {
        Control c = (Control) value; JsonObject r = new JsonObject();
        r.addProperty("kind", c.kind); r.addProperty("nativeAction", c.kind); r.addProperty("valid", c.screen == screen); r.addProperty("enabled", true);
        r.addProperty("source", "Actual synchronized AE object and native packet/highlighter; no world scan or physical hover.");
        if (c.kind.startsWith("interface_")) {
            r.addProperty("interfaceId", String.valueOf(field(c.target, ENTRY, "id"))); r.addProperty("text", JsonData.bounded(String.valueOf(field(c.target, ENTRY, "dispName")), 512));
            for (String k : new String[] {"x", "y", "z", "dim", "side"}) r.addProperty(k, (Number) field(c.target, ENTRY, k));
            if (c.slot >= 0) {
                Object item = AeDataAdapter.inventoryItem(ExpandedGuiApi.call(c.target, "getInventory"), c.slot);
                r.addProperty("patternSlot", c.slot); r.add("item", item instanceof ItemStack ? AeDataAdapter.actualItem((ItemStack) item) : JsonNull.INSTANCE);
                r.addProperty("inventoryMeaning", "Real remote pattern slot. Activate picks up/puts down the real cursor; not a phantom or vanilla container index.");
            }
        } else if (c.kind.equals("cpu_locate_providers")) {
            String reason = locatorUnsupported(c.screen);
            r.add("resource", AeDataAdapter.stack(ExpandedGuiApi.call(c.target, "getStack"))); r.addProperty("enabled", request == null && reason == null); r.addProperty("text", "Query this CPU item's native providers once");
            if (reason != null) r.addProperty("disabledReason", reason);
        } else {
            r.add("locator", locator(c.screen)); r.addProperty("text", "Highlight native provider reply and close GUI");
            r.addProperty("enabled", request == c.target && !request.invalidated && request.locations != null && !request.locations.isEmpty() && request.serial == serial(c.screen));
        }
        return r;
    }
    public static JsonObject activate(Object value, AeControlAdapter.PacketSink sink) {
        Control c = (Control) value;
        if (c.screen != screen) throw new BridgeException("stale_screen", "AE control screen changed.");
        JsonObject r = new JsonObject(); r.addProperty("nativeAction", c.kind);
        if (c.kind.equals("interface_pattern_slot")) {
            Object action = ExpandedGuiApi.enumeration(screen, "appeng.helpers.InventoryAction", "PICKUP_OR_SET_DOWN"); long id = ((Number) field(c.target, ENTRY, "id")).longValue();
            sink.send(ExpandedGuiApi.create(screen, "appeng.core.sync.packets.PacketInventoryAction", action, c.slot, id)); r.addProperty("patternSlot", c.slot); r.addProperty("interfaceId", String.valueOf(id));
        } else if (c.kind.equals("interface_highlight")) {
            highlight(Collections.singletonList(ExpandedGuiApi.create(screen, "appeng.api.util.NamedDimensionalCoord", field(c.target, ENTRY, "x"), field(c.target, ENTRY, "y"), field(c.target, ENTRY, "z"), field(c.target, ENTRY, "dim"), String.valueOf(field(c.target, ENTRY, "dispName")))));
        } else if (c.kind.equals("cpu_locate_providers")) {
            String reason = locatorUnsupported(screen);
            if (reason != null) throw new BridgeException("unsupported", reason);
            if (request != null) throw new BridgeException("busy", "Native provider query already owned by this screen. Inspect it; do not resend.");
            Object stack = ExpandedGuiApi.call(ExpandedGuiApi.call(c.target, "getStack"), "copy"), packet = ExpandedGuiApi.create(screen, "appeng.core.sync.packets.PacketCraftingItemInterface", stack);
            ExpandedGuiApi.call(screen, "postInterfaceLocationsUpdate", (Object) null); request = new Request(stack, serial(screen)); sink.send(packet);
        } else {
            observe(screen);
            if (request != c.target || request.invalidated || request.serial != serial(screen)) throw new BridgeException("stale_target", "CPU selection changed during provider lookup.");
            if (request.locations == null || request.locations.isEmpty()) throw new BridgeException("invalid_state", "No native provider reply available to highlight.");
            highlight(request.locations);
        }
        r.addProperty("submitted", true); r.addProperty("serverConfirmed", false); return r;
    }
    public static void observe(Object current) {
        try { observeCurrent(current); }
        catch (RuntimeException | LinkageError failure) { if (request != null) { request.invalidated = true; request.error = failure.getClass().getSimpleName(); } }
    }
    private static void observeCurrent(Object current) {
        if (request == null || current != screen || !ExpandedGuiApi.is(current, CPU) || request.locations != null) return;
        if (request.serial != serial(current)) { request.invalidated = true; return; }
        Object raw = field(current, CPU, "hoveredInterfaceLocations");
        if (raw instanceof List<?>) { List<?> list = (List<?>) raw; if (list.size() > 128) request.invalidated = true; else request.locations = new ArrayList<Object>(list); }
    }
    static JsonObject locator(Object current) {
        JsonObject r = new JsonObject(); r.addProperty("available", current == screen && request != null);
        String reason = locatorUnsupported(current); r.addProperty("supported", reason == null);
        r.addProperty("capabilitySource", "current_client_host_interface; server-only openContext and CPU are validated by native packet handling, actual reply required");
        if (reason != null) r.addProperty("unsupportedReason", reason);
        if (current != screen || request == null) return r;
        observe(current); r.addProperty("pending", request.locations == null && !request.invalidated); r.addProperty("invalidated", request.invalidated || request.serial != serial(current));
        if (request.error != null) r.addProperty("observationError", request.error);
        r.addProperty("selectedCpuSerial", request.serial); r.add("resource", AeDataAdapter.stack(request.stack)); JsonArray locations = new JsonArray();
        if (request.locations != null) for (Object p : request.locations) {
            JsonObject e = new JsonObject(); for (String k : new String[] {"x", "y", "z"}) e.addProperty(k, (Number) field(p, "appeng.api.util.WorldCoord", k));
            e.addProperty("dimension", ((Number) ExpandedGuiApi.call(p, "getDimension")).intValue()); e.addProperty("name", JsonData.bounded(String.valueOf(ExpandedGuiApi.call(p, "getCustomName")), 512)); locations.add(e);
        }
        r.add("locations", locations); r.addProperty("correlation", "Native reply has no request/job ID; one owned query per unchanged screen/CPU. Concurrent external queries or replacement jobs are not independently correlated.");
        r.addProperty("source", "Native PacketCraftingItemInterface/getProviders reply, not machine-progress proof."); return r;
    }
    private static String locatorUnsupported(Object current) {
        if (!ExpandedGuiApi.is(current, CPU)) return "Provider lookup requires a native CPU screen.";
        Object container = field(current, CPU, "container"), target = ExpandedGuiApi.call(container, "getTarget");
        if (!ExpandedGuiApi.is(target, "appeng.api.networking.IGridHost"))
            return "Native provider packet requires an IGridHost; this terminal host does not implement it.";
        // GuiBridge.updateGui sets openContext only on server containers. Every
        // normal client container may have null here, including working placed
        // terminals. Do not mistake an unsynchronized server field for refusal.
        return null;
    }
    private static int serial(Object current) {
        return ExpandedGuiApi.is(current, AeDataAdapter.GUI + "GuiCraftingStatus") ? ((Number) field(AeDataAdapter.cpuTable(current), AeDataAdapter.CONTAINER + "ContainerCPUTable", "selectedCpuSerial")).intValue() : -1;
    }
    private static void highlight(List<?> locations) {
        Minecraft client = Minecraft.getMinecraft();
        if (client.currentScreen != screen || client.thePlayer == null) throw new BridgeException("stale_screen", "Native highlight requires the current player GUI.");
        Map<Object, String[]> messages = new LinkedHashMap<Object, String[]>();
        String found = String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.enumeration(screen, "appeng.core.localization.PlayerMessages", "MachineHighlightedNamed"), "getUnlocalized"));
        String wrong = String.valueOf(ExpandedGuiApi.call(ExpandedGuiApi.enumeration(screen, "appeng.core.localization.PlayerMessages", "MachineInOtherDimNamed"), "getUnlocalized"));
        for (Object p : locations) messages.put(p, new String[] {found, wrong});
        Object highlighter = ExpandedGuiApi.field(null, ExpandedGuiApi.load(screen, "appeng.client.render.highlighter.BlockPosHighlighter"), "INSTANCE");
        ExpandedGuiApi.call(highlighter, "highlightNamedBlocks", client.thePlayer, messages, "ME Interface"); ExpandedGuiApi.declaredCall(screen, "appeng.client.gui.AEBaseGui", "closeGui");
    }
    private static final class Control {
        final Object screen, target; final String kind; final int slot;
        Control(Object screen, Object target, String kind, int slot) { this.screen = screen; this.target = target; this.kind = kind; this.slot = slot; }
    }
    private static final class Request {
        final Object stack; final int serial; List<?> locations; boolean invalidated; String error;
        Request(Object stack, int serial) { this.stack = stack; this.serial = serial; }
    }
}
