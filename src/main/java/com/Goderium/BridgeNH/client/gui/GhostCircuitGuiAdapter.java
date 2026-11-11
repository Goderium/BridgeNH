package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.network.PacketBuffer;

/** GT's shared public ghost-circuit widget; no machine-specific or inventory-field access. */
final class GhostCircuitGuiAdapter {
    private static final String WIDGET = "gregtech.common.modularui2.widget.GhostCircuitSlotWidget";
    private static final String HANDLER = "gregtech.common.modularui2.sync.GhostCircuitSyncHandler";
    private static final String MANAGER = "com.cleanroommc.modularui.value.sync.PanelSyncManager";
    private static final String WRITER = "com.cleanroommc.modularui.api.IPacketWriter";
    static final String SET_VALUE_API = "Public GhostCircuitSyncHandler.syncToServer(SYNC_CIRCUIT_CONFIG, IPacketWriter) with PacketBuffer.writeShort(value); native selector submission, with no local configuration update.";

    static boolean supports(Object widget) { return ExpandedGuiApi.is(widget, WIDGET); }

    static JsonObject read(Object widget) {
        JsonObject result = new JsonObject();
        result.addProperty("api", "GhostCircuitSlotWidget.getSyncHandler().getGhostCircuitHandler().getCircuitConfig()");
        try {
            if (!supports(widget)) throw new BridgeException("unsupported", "Not the shared GT ghost-circuit widget.");
            Object handler = ExpandedGuiApi.call(widget, "getSyncHandler");
            Object circuit = ExpandedGuiApi.call(handler, "getGhostCircuitHandler");
            Object value = ExpandedGuiApi.call(circuit, "getCircuitConfig");
            if (!(value instanceof Integer)) throw new BridgeException("unsupported", "Public circuit configuration is unavailable.");
            result.addProperty("available", true); result.addProperty("value", (Integer) value);
            result.addProperty("noConfiguration", ((Integer) value) == -1);
            result.addProperty("source", "Current client widget sync handler; a later read is required after server synchronization.");
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("available", false); result.addProperty("reason", "public_circuit_configuration_unavailable");
        }
        return result;
    }

    static boolean setterAvailable(Object widget, Object screen) {
        try { setter(widget, screen); return true; }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    static int setValue(Object widget, Object screen, JsonObject args, InventoryPlayer inventory, BooleanSupplier stillCurrent) {
        int value = JsonData.integer(args, "value", -1, 24);
        if (value == 0) throw new BridgeException("invalid_args", "Circuit value must be -1 or 1..24.");
        Setter setter = setter(widget, screen);
        Object writer = Proxy.newProxyInstance(setter.writer.getClassLoader(), new Class<?>[] {setter.writer}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "Native ghost circuit packet writer";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == arguments[0];
            }
            if (!method.getName().equals("write") || arguments == null || arguments.length != 1 || !(arguments[0] instanceof PacketBuffer))
                throw new BridgeException("unsupported", "Unexpected public native packet writer invocation.");
            ((PacketBuffer) arguments[0]).writeShort(value);
            return null;
        });
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The current screen/container changed before circuit submission.");
        if (inventory == null) throw new BridgeException("target_unverified", "The actual player cursor is unavailable.");
        if (inventory.getItemStack() != null) throw new BridgeException("cursor_not_empty", "Circuit set_value requires an empty actual player cursor.");
        Setter current = setter(widget, screen);
        if (current.handler != setter.handler || current.manager != setter.manager || !stillCurrent.getAsBoolean())
            throw new BridgeException("stale_widget", "The native circuit handler changed before submission.");
        if (inventory.getItemStack() != null) throw new BridgeException("cursor_not_empty", "The actual cursor changed before circuit submission.");
        AdapterAccess.callOn(setter.handler, HANDLER, "syncToServer", new Class<?>[] {int.class, setter.writer}, setter.packetId, writer);
        return value;
    }

    private static Setter setter(Object widget, Object screen) {
        if (!supports(widget) || !ExpandedGuiApi.is(screen, "com.cleanroommc.modularui.api.IMuiScreen"))
            throw new BridgeException("unsupported", "The current native ghost circuit screen is unavailable.");
        Object handler = AdapterAccess.call(widget, "getSyncHandler");
        if (AdapterAccess.type(handler, HANDLER) == null || !Boolean.TRUE.equals(AdapterAccess.call(handler, "isValid"))
                || !Boolean.TRUE.equals(AdapterAccess.call(handler, "isAllowC2S")))
            throw new BridgeException("unsupported", "The native circuit handler is not ready for client-to-server sync.");
        Object manager = AdapterAccess.call(handler, "getSyncManager");
        Class<?> managerType = AdapterAccess.type(manager, MANAGER);
        if (managerType == null || !Boolean.TRUE.equals(AdapterAccess.call(manager, "isInitialised"))
                || !Boolean.TRUE.equals(AdapterAccess.call(manager, "isClient"))
                || !Boolean.TRUE.equals(AdapterAccess.call(handler, "isRegistered", new Class<?>[] {managerType}, manager)))
            throw new BridgeException("unsupported", "The native circuit handler is not registered in an initialized client sync manager.");
        Object modular = AdapterAccess.call(screen, "getScreen"), sync = AdapterAccess.call(modular, "getSyncManager");
        Object container = AdapterAccess.call(modular, "getContainer"), panelName = AdapterAccess.call(manager, "getPanelName");
        if (container == null || container != AdapterAccess.call(manager, "getContainer") || !(panelName instanceof String)
                || AdapterAccess.call(sync, "getPanelSyncManager", new Class<?>[] {String.class}, panelName) != manager)
            throw new BridgeException("unsupported", "The circuit sync handler does not belong to the current screen/container.");
        if (!read(widget).get("available").getAsBoolean()) throw new BridgeException("unsupported", "Public circuit configuration is unavailable.");
        Class<?> writer = ExpandedGuiApi.load(handler, WRITER);
        try {
            if (!writer.isInterface() || writer.getMethod("write", PacketBuffer.class).getReturnType() != void.class
                    || AdapterAccess.type(handler, HANDLER).getMethod("syncToServer", int.class, writer).getReturnType() != void.class)
                throw new BridgeException("unsupported", "Public native circuit packet API signature is unavailable.");
        } catch (NoSuchMethodException error) { throw new BridgeException("unsupported", "Public native circuit packet API is unavailable."); }
        Object packetId = AdapterAccess.field(handler, HANDLER, "SYNC_CIRCUIT_CONFIG", false);
        if (!(packetId instanceof Integer)) throw new BridgeException("unsupported", "Public circuit sync constant is unavailable.");
        return new Setter(handler, manager, writer, (Integer) packetId);
    }

    private static final class Setter {
        final Object handler, manager; final Class<?> writer; final int packetId;
        Setter(Object handler, Object manager, Class<?> writer, int packetId) { this.handler = handler; this.manager = manager; this.writer = writer; this.packetId = packetId; }
    }
}
