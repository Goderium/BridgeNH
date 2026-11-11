package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/** Bounded native AE configuration. Mutations remain normal GUI packets. */
final class AeConfiguration {
    static final String[] SETTINGS = {"BLOCK", "STORAGE_FILTER", "ADVANCED_BLOCKING_MODE"};

    static JsonObject owner(Object container) {
        AeDataAdapter.require(container, "appeng.container.AEBaseContainer");
        Object target = ExpandedGuiApi.call(container, "getTarget");
        if (AePortableTerminals.supports(target)) return AePortableTerminals.owner(container, target);
        Object tile = target;
        JsonObject part = null;
        if (ExpandedGuiApi.is(target, "appeng.api.parts.IPart")) {
            Object host = ExpandedGuiApi.call(target, "getHost");
            tile = ExpandedGuiApi.call(host, "getTile");
            Object side = ExpandedGuiApi.call(target, "getSide");
            if (!(side instanceof ForgeDirection)) throw new BridgeException("unsupported", "AE owner side is unavailable.");
            part = AePartAdapter.identity(target, ((ForgeDirection) side).ordinal());
        }
        if (!(tile instanceof TileEntity) || ((TileEntity) tile).getWorld() == null)
            throw new BridgeException("unsupported", "AE host has neither a supported placed owner nor a bound portable item owner.");
        TileEntity te = (TileEntity) tile;
        JsonObject result = new JsonObject();
        result.addProperty("x", te.xCoord); result.addProperty("y", te.yCoord); result.addProperty("z", te.zCoord);
        result.addProperty("dimension", te.getWorld().provider.dimensionId);
        if (part != null) result.add("part", part);
        return result;
    }

    static JsonObject host(Object host) {
        JsonObject result = new JsonObject();
        if (ExpandedGuiApi.is(host, "appeng.helpers.IPriorityHost"))
            result.addProperty("priority", ((Number) ExpandedGuiApi.call(host, "getPriority")).intValue());
        JsonObject settings = new JsonObject();
        if (ExpandedGuiApi.is(host, "appeng.api.util.IConfigurableObject")) {
            Object manager = ExpandedGuiApi.call(host, "getConfigManager");
            Object keys = ExpandedGuiApi.call(manager, "getSettings");
            if (!(keys instanceof Iterable<?>)) throw new BridgeException("unsupported", "AE config keys are unavailable.");
            for (Object key : (Iterable<?>) keys) for (String name : SETTINGS) if (name.equals(String.valueOf(key)))
                settings.addProperty(name, String.valueOf(ExpandedGuiApi.call(manager, "getSetting", key)));
        }
        result.add("settings", settings); result.addProperty("complete", true);
        return result;
    }

    static JsonObject read(Object container) {
        JsonObject result = new JsonObject();
        result.add("owner", owner(container));
        JsonObject settings = new JsonObject();
        if (ExpandedGuiApi.is(container, AeDataAdapter.CONTAINER + "ContainerPriority"))
            result.addProperty("priority", ((Number) ExpandedGuiApi.field(container, AeDataAdapter.CONTAINER + "ContainerPriority", "priorityValue")).longValue());
        if (ExpandedGuiApi.is(container, AeDataAdapter.CONTAINER + "ContainerStorageBus"))
            settings.addProperty("STORAGE_FILTER", String.valueOf(ExpandedGuiApi.call(container, "getStorageFilter")));
        if (ExpandedGuiApi.is(container, AeDataAdapter.CONTAINER + "ContainerInterface")) {
            settings.addProperty("BLOCK", String.valueOf(ExpandedGuiApi.call(container, "getBlockingMode")));
            settings.addProperty("ADVANCED_BLOCKING_MODE", String.valueOf(ExpandedGuiApi.call(container, "getAdvancedBlockingMode")));
        }
        result.add("settings", settings);
        result.addProperty("confirmation", "synchronized_client_container; use owner world read for authoritative confirmation");
        return result;
    }

    static void action(Object screen, Object container, JsonObject args, JsonObject result, AeControlAdapter.PacketSink sink) {
        String action = ExpandedGuiApi.string(args, "action", 64);
        AeDataAdapter.require(screen, "appeng.client.gui.AEBaseGui");
        AeDataAdapter.require(container, "appeng.container.AEBaseContainer");
        if (action.equals("priority_open")) {
            ExpandedGuiApi.keys(args);
            Object target = ExpandedGuiApi.call(container, "getTarget");
            AeDataAdapter.require(target, "appeng.helpers.IPriorityHost");
            Object bridge = ExpandedGuiApi.enumeration(screen, "appeng.core.sync.GuiBridge", "GUI_PRIORITY");
            sink.send(ExpandedGuiApi.create(screen, "appeng.core.sync.packets.PacketSwitchGuis", bridge));
        } else if (action.equals("priority_set")) {
            ExpandedGuiApi.keys(args, "priority");
            AeDataAdapter.require(screen, AeDataAdapter.GUI + "GuiPriority");
            AeDataAdapter.require(container, AeDataAdapter.CONTAINER + "ContainerPriority");
            int priority = JsonData.integer(args, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE);
            sink.send(ExpandedGuiApi.create(screen, "appeng.core.sync.packets.PacketValueConfig", "PriorityHost.Priority", Integer.toString(priority)));
            result.addProperty("requestedPriority", priority);
        } else {
            ExpandedGuiApi.keys(args, "setting", "value", "expectedValue");
            String setting = ExpandedGuiApi.option(args, "setting", null, SETTINGS);
            String expected = ExpandedGuiApi.string(args, "expectedValue", 64);
            String requested = ExpandedGuiApi.string(args, "value", 64);
            JsonObject values = read(container).getAsJsonObject("settings");
            if (!values.has(setting) || !expected.equals(values.get(setting).getAsString()))
                throw new BridgeException("stale_config", "The synchronized setting differs from the bound expected value.");
            Object key = ExpandedGuiApi.enumeration(screen, "appeng.api.config.Settings", setting);
            Object options = ExpandedGuiApi.call(key, "getPossibleValues");
            JsonArray names = new JsonArray();
            boolean found = false;
            if (options instanceof Iterable<?>) for (Object value : (Iterable<?>) options) {
                names.add(new com.google.gson.JsonPrimitive(String.valueOf(value)));
                found |= requested.equals(String.valueOf(value));
            }
            // These native switches are binary. One toggle, followed by authoritative readback,
            // avoids local client prediction and unbounded button cycling.
            if (names.size() != 2 || !found) throw new BridgeException("unsupported", "Only an advertised binary configuration value is supported.");
            if (!expected.equals(requested)) sink.send(ExpandedGuiApi.create(screen, "appeng.core.sync.packets.PacketConfigButton", key, false));
            result.addProperty("before", expected); result.addProperty("requested", requested);
            result.addProperty("changed", !expected.equals(requested));
        }
        result.addProperty("serverConfirmed", false);
    }
}
