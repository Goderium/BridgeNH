package com.Goderium.BridgeNH.common.diagnostics;

import com.Goderium.BridgeNH.util.JsonData;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import cpw.mods.fml.common.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Public server/JVM state sampled on the owning server thread. */
public final class ServerDiagnostics {
    private ServerDiagnostics() { }

    public static JsonObject inspect(MinecraftServer server) {
        long started = System.nanoTime();
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1); result.addProperty("readOnly", true);
        result.addProperty("serverType", server.isDedicatedServer() ? "dedicated" : "integrated");
        result.addProperty("minecraftVersion", JsonData.bounded(server.getMinecraftVersion(), 128));
        result.addProperty("serverBrand", JsonData.bounded(server.getServerModName(), 128));
        result.addProperty("onlinePlayers", server.getCurrentPlayerCount());
        result.addProperty("maximumPlayers", server.getMaxPlayers());
        result.addProperty("loadedModCount", cpw.mods.fml.common.Loader.instance().getActiveModList().size());
        result.addProperty("bridgeBuildFingerprint", com.Goderium.BridgeNH.util.BridgeBuildIdentity.fingerprint());
        Runtime runtime = Runtime.getRuntime();
        JsonObject jvm = new JsonObject();
        long total = runtime.totalMemory();
        jvm.addProperty("usedBytes", total - runtime.freeMemory()); jvm.addProperty("allocatedBytes", total);
        jvm.addProperty("maximumBytes", runtime.maxMemory()); jvm.addProperty("availableProcessors", runtime.availableProcessors());
        jvm.addProperty("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        jvm.addProperty("sharedWithClient", !server.isDedicatedServer()); result.add("jvm", jvm);
        result.add("tickDurations", tickDurations(server.tickTimeArray));
        JsonArray worlds = new JsonArray(); int totalWorlds = 0;
        int worldSlots = server.worldServers == null ? 0 : server.worldServers.length;
        for (int index = 0; index < Math.min(256, worldSlots); index++) {
            WorldServer world = server.worldServers[index]; if (world == null) continue;
            totalWorlds++;
            if (worlds.size() < 64) {
                JsonObject row = new JsonObject(); row.addProperty("dimension", world.provider.dimensionId);
                row.addProperty("worldTick", world.getTotalWorldTime()); row.addProperty("worldTime", world.getWorldTime());
                row.addProperty("providerClass", JsonData.bounded(world.provider.getClass().getName(), 256)); worlds.add(row);
            }
        }
        result.add("loadedWorlds", worlds); result.addProperty("worldCountComplete", worldSlots <= 256);
        if (worldSlots <= 256) result.addProperty("loadedWorldCount", totalWorlds); else result.add("loadedWorldCount", JsonNull.INSTANCE);
        result.addProperty("worldsTruncated", worldSlots > 256 || totalWorlds > worlds.size());
        result.addProperty("observedAtMillis", System.currentTimeMillis()); result.addProperty("nonAtomic", true);
        while (result.toString().getBytes(StandardCharsets.UTF_8).length > ServerLogReader.RESULT_BYTES && worlds.size() > 0) {
            worlds.remove(worlds.size() - 1); result.addProperty("worldsTruncated", true);
        }
        result.addProperty("samplingMillis", (System.nanoTime() - started) / 1000000D);
        return result;
    }

    public static JsonObject mods(List<ModContainer> loaded, JsonObject args) {
        long started = System.nanoTime();
        RemoteReadProtocol.keys(args, "offset", "limit");
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 10000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 64) : 16;
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray();
        result.addProperty("schemaVersion", 1); result.addProperty("readOnly", true);
        result.addProperty("readSource", "fml_active_mod_list"); result.add("mods", rows);
        result.addProperty("total", loaded.size()); result.addProperty("offset", offset);
        int next = Math.min(offset, loaded.size()), bytes = 512; boolean byteLimited = false;
        for (; next < loaded.size() && rows.size() < limit; next++) {
            ModContainer mod = loaded.get(next); JsonObject row = new JsonObject();
            String id = mod.getModId(), name = mod.getName(), version = mod.getVersion();
            row.addProperty("modId", JsonData.bounded(id, 128)); row.addProperty("name", JsonData.bounded(name, 256));
            row.addProperty("version", JsonData.bounded(version, 128));
            row.addProperty("fieldsTruncated", (id != null && id.length() > 128) || (name != null && name.length() > 256)
                || (version != null && version.length() > 128));
            int rowBytes = row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + rowBytes > ServerLogReader.RESULT_BYTES - 512) { byteLimited = true; break; }
            rows.add(row); bytes += rowBytes;
        }
        result.addProperty("returned", rows.size()); result.addProperty("hasMore", next < loaded.size());
        if (next < loaded.size()) result.addProperty("nextOffset", next); else result.add("nextOffset", JsonNull.INSTANCE);
        result.addProperty("byteLimited", byteLimited); result.addProperty("observedAtMillis", System.currentTimeMillis());
        result.addProperty("samplingMillis", (System.nanoTime() - started) / 1000000D);
        return result;
    }

    public static JsonObject tickDurations(long[] samples) {
        int count = 0; double total = 0; long maximum = 0;
        if (samples != null) for (int index = 0; index < Math.min(100, samples.length); index++) if (samples[index] > 0) {
            count++; total += samples[index]; maximum = Math.max(maximum, samples[index]);
        }
        JsonObject data = new JsonObject(); data.addProperty("available", count > 0);
        data.addProperty("sampleCount", count); data.addProperty("windowCapacity", 100);
        if (count > 0) {
            double mean = total / count / 1000000D;
            data.addProperty("meanMillis", mean); data.addProperty("maxMillis", maximum / 1000000D);
            data.addProperty("estimatedTpsIfSustained", Math.min(20D, 1000D / mean));
        } else {
            data.add("meanMillis", JsonNull.INSTANCE); data.add("maxMillis", JsonNull.INSTANCE);
            data.add("estimatedTpsIfSustained", JsonNull.INSTANCE);
        }
        data.addProperty("source", "MinecraftServer.tickTimeArray");
        data.addProperty("meaning", "Up to 100 completed native tick execution durations, excluding sleep. Estimated TPS is a sustained-load estimate, not measured wall-clock tick rate; paused/stalled servers may retain old samples.");
        return data;
    }
}
