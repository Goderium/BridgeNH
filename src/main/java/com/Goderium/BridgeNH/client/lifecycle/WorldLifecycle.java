package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.network.RemoteReadClient;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraft.world.storage.WorldInfo;

/** Normal client lifecycle through public game APIs, never edits save files. */
public class WorldLifecycle {
    private static final ConnectionTarget CONNECTION = new ConnectionTarget();

    /** Opening to LAN changes visibility and game permissions, not ownership of this server. */
    static boolean integratedOwner(boolean singleplayer, Object server, Object activeServer) {
        return singleplayer && server != null && server == activeServer;
    }

    public static boolean integrated(Minecraft client) {
        return client != null && integratedOwner(client.isSingleplayer(), client.getIntegratedServer(), MinecraftServer.getServer());
    }

    /** Opaque, bounded native connection guard, also available without a server-side bridge or OP. */
    static final class ConnectionTarget {
        private WeakReference<Object> handler = new WeakReference<Object>(null);
        private WeakReference<Object> world = new WeakReference<Object>(null);
        private WeakReference<Object> player = new WeakReference<Object>(null);
        private String token;

        String get(Object currentHandler, Object currentWorld, Object currentPlayer) {
            if (currentHandler == null || currentWorld == null || currentPlayer == null) {
                handler.clear(); world.clear(); player.clear(); token = null;
                return null;
            }
            if (handler.get() != currentHandler || world.get() != currentWorld || player.get() != currentPlayer || token == null) {
                handler = new WeakReference<Object>(currentHandler);
                world = new WeakReference<Object>(currentWorld);
                player = new WeakReference<Object>(currentPlayer);
                token = "connection:" + UUID.randomUUID().toString();
            }
            return token;
        }
    }

    public static JsonObject target(Minecraft client) {
        JsonObject result = new JsonObject();
        boolean loaded = client.theWorld != null && client.thePlayer != null;
        String expected = integrated(client) && loaded ? client.getIntegratedServer().getFolderName()
                : CONNECTION.get(loaded && !client.isSingleplayer() ? client.getNetHandler() : null,
                        client.theWorld, client.thePlayer);
        result.addProperty("available", expected != null && !expected.isEmpty());
        if (expected != null) result.addProperty("expectedWorld", expected);
        result.addProperty("kind", integrated(client) ? "integrated_world" : "native_connection");
        if (!client.isSingleplayer() && client.getCurrentServerData() != null)
            result.addProperty("serverAddress", JsonData.bounded(client.getCurrentServerData().serverIP, 512));
        result.addProperty("requiresDeveloper", false);
        return result;
    }
    public static List<SaveFormatComparator> saves(Minecraft client) {
        try {
            List<SaveFormatComparator> saves = new ArrayList<SaveFormatComparator>();
            for (Object value : client.getSaveLoader().getSaveList()) if (value instanceof SaveFormatComparator) saves.add((SaveFormatComparator) value);
            Collections.sort(saves, Comparator.comparing(SaveFormatComparator::getFileName));
            return saves;
        } catch (Exception error) { throw new BridgeException("world_list_unavailable", "Public save listing failed: " + error.getClass().getSimpleName()); }
    }

    public static JsonObject list(Minecraft client, JsonObject args) {
        String query = CatalogData.text(args, "query", 128, "");
        List<JsonObject> entries = new ArrayList<JsonObject>();
        for (SaveFormatComparator save : saves(client)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("folder", JsonData.bounded(save.getFileName(), 256));
            entry.addProperty("name", JsonData.bounded(save.getDisplayName(), 512));
            entry.addProperty("requiresConversion", save.requiresConversion());
            entries.add(entry);
        }
        JsonObject result = CatalogData.page("worlds", CatalogData.matching(entries, query, "folder", "name"), args, 100000);
        result.addProperty("query", query);
        result.addProperty("source", "Read-only public ISaveFormat.getSaveList; no world creation/deletion/editing API.");
        return result;
    }

    public static String folder(JsonObject args) {
        String folder = CatalogData.text(args, "folder", 256, null);
        if (folder.isEmpty() || ".".equals(folder) || "..".equals(folder) || folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0
                || folder.indexOf(':') >= 0 || folder.indexOf('\u0000') >= 0)
            throw new BridgeException("invalid_args", "folder must be an exact existing save-list folder name, not a path.");
        return folder;
    }

    public static String loadName(Minecraft client, String folder) {
        if (client.theWorld != null || client.getIntegratedServer() != null || !mainMenu(client.currentScreen))
            throw new BridgeException("invalid_state", "Loading a world requires the normal main menu with no loaded world or integrated server.");
        SaveFormatComparator selected = null;
        for (SaveFormatComparator save : saves(client)) if (folder.equals(save.getFileName())) { selected = save; break; }
        if (selected == null || !client.getSaveLoader().canLoadWorld(folder)) throw new BridgeException("not_found", "folder is not an existing loadable save-list entry.");
        WorldInfo info = client.getSaveLoader().getWorldInfo(folder);
        if (info == null) throw new BridgeException("world_unavailable", "The existing save has no readable WorldInfo; creation is not supported.");
        if (selected.requiresConversion() || client.getSaveLoader().isOldMapFormat(folder))
            throw new BridgeException("unsupported", "Old save conversion is not permitted by client.load_world.");
        return info.getWorldName();
    }

    /** Public CustomMainMenu 1.14.1 identity, without linking its optional classes. */
    public static boolean mainMenu(Object screen) {
        if (screen instanceof GuiMainMenu) return true;
        if (screen == null || !"lumien.custommainmenu.gui.GuiCustom".equals(screen.getClass().getName())) return false;
        try {
            Object config = screen.getClass().getField("guiConfig").get(screen);
            return config != null && "lumien.custommainmenu.configuration.GuiConfig".equals(config.getClass().getName())
                    && "mainmenu".equals(config.getClass().getField("name").get(config));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    public static String host(JsonObject args) {
        String host=CatalogData.text(args,"host",253,null);
        if(host.isEmpty() || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"))
            throw new BridgeException("invalid_args","Expected a plain IPv4 address or hostname; port is separate.");
        return host;
    }
    public static void requireCurrent(Minecraft client,JsonObject args) {
        if(client.isSingleplayer()) { local(client,args); return; }
        if(client.theWorld==null || client.thePlayer==null || client.getNetHandler()==null)
            throw new BridgeException("world_unavailable","There is no joined native connection to disconnect.");
        String expected=CatalogData.text(args,"expectedWorld",128,null);
        if (expected.equals(CONNECTION.get(client.getNetHandler(), client.theWorld, client.thePlayer))) return;
        // Preserve existing authenticated world-name guards without requiring a bridge for native disconnect.
        JsonObject world=RemoteReadClient.INSTANCE.worldIdentity();
        if (world != null) {
            RemoteReadClient.INSTANCE.requireCurrent(RemoteReadClient.INSTANCE.connectionId());
            if (!expected.isEmpty() && (expected.equals(world.get("worldName").getAsString())
                    || expected.equals(world.get("worldFolder").getAsString()))) return;
        }
        throw new BridgeException("stale_world","expectedWorld differs from health.lifecycleTarget.expectedWorld for the current connection.");
    }
    public static IntegratedServer local(Minecraft client, JsonObject args) {
        IntegratedServer server = client.getIntegratedServer();
        if (client.theWorld == null || client.thePlayer == null || !integrated(client))
            throw new BridgeException("permission_denied", "Lifecycle save/leave requires the current owned integrated world.");
        String expected = CatalogData.text(args, "expectedWorld", 128, null);
        if (expected.isEmpty() || !expected.equals(server.getFolderName()) && !expected.equals(server.getWorldName()))
            throw new BridgeException("stale_world", "expectedWorld does not match the current local world name or folder.");
        return server;
    }

    public static void leave(Minecraft client) {
        client.theWorld.sendQuittingDisconnectingPacket();
        client.loadWorld((WorldClient) null);
        client.displayGuiScreen(new GuiMainMenu());
    }
}
