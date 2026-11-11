package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;

/** Explicit developer snapshot of existing loaded tiles. No load-from-NBT, setters, ticking or Waila data. */
public final class MachineObservationCapture {
    public static final String OPERATION = "machine.capture";
    public static final int MAX_BYTES = 16 * 1024 * 1024, MAX_ENTRIES = 262144, PAGE_BYTES = 96 * 1024;
    private final Map<String, Capture> captures = new LinkedHashMap<String, Capture>();

    public synchronized void clear() { captures.clear(); }

    public synchronized void expire() {
        long now = System.nanoTime();
        Iterator<Capture> expired = captures.values().iterator();
        while (expired.hasNext()) if (now - expired.next().created > 30000000000L) expired.remove();
    }

    public synchronized JsonObject read(World world, String session, String worldFolder, JsonObject args) {
        expire();
        boolean continuation = args.has("captureId");
        keys(args, continuation ? new String[] {"expectedSessionId", "expectedWorld", "dimension", "captureId", "offset", "limit"}
                : new String[] {"expectedSessionId", "expectedWorld", "dimension", "positions", "includeNbt", "offset", "limit"});
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, MAX_ENTRIES) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        Capture capture;
        if (continuation) {
            capture = captures.get(JsonData.string(args, "captureId"));
            if (capture == null || capture.world != world || !capture.session.equals(session) || !capture.folder.equals(worldFolder))
                throw new BridgeException("stale_capture", "Snapshot expired or belongs to another session/world/dimension; do not combine fresh pages.");
        } else {
            if (offset != 0) throw new BridgeException("invalid_args", "Initial capture requires offset=0.");
            JsonElement positions = args.get("positions");
            if (positions == null || !positions.isJsonArray() || positions.getAsJsonArray().size() < 1 || positions.getAsJsonArray().size() > 8)
                throw new BridgeException("invalid_args", "Snapshot positions must contain 1 to 8 loaded tile coordinates.");
            capture = new Capture(world, session, worldFolder);
            boolean nbt = GuiDataAdapter.booleanArg(args, "includeNbt", false);
            // Validate and resolve every target before invoking any serialization callback.
            List<TileEntity> tiles = new ArrayList<TileEntity>();
            for (JsonElement value : positions.getAsJsonArray()) {
                if (!value.isJsonObject()) throw new BridgeException("invalid_args", "Each target must be XYZ.");
                JsonObject point = value.getAsJsonObject(); keys(point, "x", "y", "z");
                int x = JsonData.integer(point, "x", -30000000, 30000000), y = JsonData.integer(point, "y", 0, 255), z = JsonData.integer(point, "z", -30000000, 30000000);
                if (new WorldScan.MinecraftAccess(world).section(x >> 4, y >> 4, z >> 4) == WorldScan.UNKNOWN)
                    throw new BridgeException("chunk_unloaded", "Snapshot never loads or generates a chunk.");
                Object found = world.getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
                if (!(found instanceof TileEntity) || ((TileEntity) found).isInvalid()) throw new BridgeException("not_found", "A snapshot target has no valid loaded tile.");
                tiles.add((TileEntity) found);
            }
            for (int i = 0; i < tiles.size(); i++) {
                TileEntity tile = tiles.get(i); JsonObject row = new JsonObject();
                row.addProperty("targetIndex", i); row.addProperty("x", tile.xCoord); row.addProperty("y", tile.yCoord); row.addProperty("z", tile.zCoord);
                row.addProperty("tileClass", tile.getClass().getName());
                row.addProperty("source", "owning_server_callback");
                row.addProperty("worldTick", capture.tick);
                row.addProperty("nbtRequested", nbt);
                if (nbt) {
                    NBTTagCompound tag = new NBTTagCompound();
                    try { tile.writeToNBT(tag); }
                    catch (RuntimeException | LinkageError failure) { throw new BridgeException("serialization_failed", "Tile serialization failed for target " + i + ": " + failure.getClass().getSimpleName() + "; no complete capture published."); }
                    // Some serializers borrow live child tags. Traverse a detached native copy;
                    // the 1.7.10 public list API exposes arbitrary values only through removeTag.
                    flatten(capture, i, new JsonArray(), tag.copy(), 0);
                    row.addProperty("nbtSerializationComplete", true);
                    row.addProperty("inMemoryCoverage", "Only fields emitted by this tile's normal writeToNBT, including its native metatile serialization. Omitted/transient fields and round IDs are not inferred.");
                }
                JsonObject state = new JsonObject();
                for (String method : new String[] {"isActive", "isAllowedToWork", "getProgress", "getMaxProgress", "getStoredEU", "getInputVoltage"}) {
                    try { Object value = AdapterAccess.call(tile, method); state.add(method, GTRecipeAdapter.value(value, 0)); }
                    catch (RuntimeException | LinkageError failure) { JsonObject absent = new JsonObject(); absent.addProperty("available", false); absent.addProperty("reason", "public_getter_unavailable"); state.add(method, absent); }
                }
                row.add("publicState", state);
                try {
                    Object meta = AdapterAccess.call(tile, "getMetaTileEntity");
                    if (meta != null) row.addProperty("metaClass", meta.getClass().getName());
                    row.add("publicFluidInputs", MachineDataAdapter.fluidInputs(meta, new JsonObject()));
                }
                catch (RuntimeException | LinkageError failure) { JsonObject unavailable = new JsonObject(); unavailable.addProperty("available", false); row.add("publicFluidInputs", unavailable); }
                capture.targets.add(row);
            }
            capture.endTick = world.getTotalWorldTime();
            if (capture.tick != capture.endTick) throw new BridgeException("inconsistent_capture", "Server world tick changed during capture; no consistent snapshot published.");
            capture.metadataBytes = capture.targets.toString().getBytes(StandardCharsets.UTF_8).length;
            if (capture.metadataBytes > PAGE_BYTES / 2 || capture.bytes + capture.metadataBytes > MAX_BYTES)
                throw new BridgeException("data_limit", "Capture metadata exceeds the bounded snapshot; no partial snapshot published.");
            if (captures.size() >= 4) captures.remove(captures.keySet().iterator().next());
            captures.put(capture.id, capture);
        }
        if (offset > capture.entries.size()) throw new BridgeException("invalid_args", "Snapshot offset exceeds its frozen totalEntries.");
        JsonObject result = new JsonObject(); result.addProperty("captureId", capture.id); result.addProperty("sessionId", session);
        result.addProperty("source", "integrated_server_frozen_capture"); result.addProperty("worldFolder", worldFolder);
        result.addProperty("dimension", world.provider.dimensionId); result.addProperty("worldTick", capture.tick); result.addProperty("endWorldTick", capture.endTick);
        result.addProperty("capturedAtMillis", capture.atMillis); result.addProperty("snapshotAgeMillis", Math.max(0, System.nanoTime() - capture.created) / 1000000.0);
        result.addProperty("sameServerCallback", true); result.addProperty("sameWorldTick", true); result.addProperty("frozenAcrossPages", true);
        result.addProperty("atomicAgainstExternalThreads", false); result.addProperty("roundIdentityAvailable", false);
        result.addProperty("consistency", "All targets serialized/read in one owning-server callback; paging reads retained values, not fresh ticks. No lock over mod background threads. captureId is observation identity, never a production round ID.");
        result.add("targets", capture.targets); result.addProperty("targetCount", capture.targets.size());
        JsonArray page = new JsonArray(); int index = Math.min(offset, capture.entries.size()), bytes = capture.metadataBytes + 4096;
        while (index < capture.entries.size() && page.size() < limit) {
            JsonObject entry = capture.entries.get(index); int size = entry.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + size > PAGE_BYTES) break;
            page.add(entry); bytes += size; index++;
        }
        result.add("entries", page); result.addProperty("offset", offset); result.addProperty("nextOffset", index);
        result.addProperty("totalEntries", capture.entries.size()); result.addProperty("hasMore", index < capture.entries.size());
        result.addProperty("complete", index >= capture.entries.size()); result.addProperty("captureTruncated", false);
        result.addProperty("byteLimited", index < capture.entries.size() && page.size() < limit);
        result.addProperty("captureBytes", capture.bytes + capture.metadataBytes); result.addProperty("ttlMillis", 30000);
        result.addProperty("nativeCallbackPreemptible", false);
        result.addProperty("coverage", "Complete retained NBT tree only when nbtRequested=true; publicState is an explicit getter subset, not all live memory or all machine caches.");
        if (index == offset && index < capture.entries.size()) throw new BridgeException("data_limit", "A snapshot entry cannot fit in a page; no zero-progress page returned.");
        String encoded = result.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > PAGE_BYTES) throw new BridgeException("data_limit", "Snapshot page exceeds its exact byte bound.");
        return new JsonParser().parse(encoded).getAsJsonObject();
    }

    private static void flatten(Capture capture, int target, JsonArray path, NBTBase tag, int depth) {
        if (depth > 64 || path.toString().length() > 4096) throw new BridgeException("data_limit", "NBT nesting/path exceeds the bounded capture; no complete snapshot published.");
        JsonObject row = new JsonObject(); row.addProperty("targetIndex", target); row.add("path", path); row.addProperty("nbtType", tag.getId());
        if (tag instanceof NBTTagCompound) {
            List<String> keys = new ArrayList<String>(); for (Object key : ((NBTTagCompound) tag).getKeySet()) keys.add((String) key);
            Collections.sort(keys); row.addProperty("children", keys.size()); capture.add(row);
            for (String key : keys) { JsonArray child = copyPath(path); child.add(new JsonPrimitive(key)); flatten(capture, target, child, ((NBTTagCompound) tag).getTag(key), depth + 1); }
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag; row.addProperty("children", list.tagCount()); row.addProperty("elementType", list.getTagType()); capture.add(row);
            for (int i = list.tagCount() - 1; i >= 0; i--) { JsonArray child = copyPath(path); child.add(new JsonPrimitive(i)); flatten(capture, target, child, list.removeTag(i), depth + 1); }
        } else if (tag instanceof NBTTagByteArray || tag instanceof NBTTagIntArray) {
            int length = tag instanceof NBTTagByteArray ? ((NBTTagByteArray) tag).getByteArray().length : ((NBTTagIntArray) tag).getIntArray().length;
            row.addProperty("length", length); capture.add(row);
            for (int i = 0; i < length; i += 128) {
                JsonObject chunk = new JsonObject(); chunk.addProperty("targetIndex", target); chunk.add("path", path); chunk.addProperty("nbtType", tag.getId()); chunk.addProperty("arrayOffset", i);
                JsonArray values = new JsonArray(); for (int j = i; j < Math.min(length, i + 128); j++) values.add(new JsonPrimitive(tag instanceof NBTTagByteArray ? ((NBTTagByteArray) tag).getByteArray()[j] : ((NBTTagIntArray) tag).getIntArray()[j]));
                chunk.add("values", values); capture.add(chunk);
            }
        } else if (tag instanceof NBTTagString) {
            String text = ((NBTTagString) tag).getString();
            int i = 0;
            do {
                JsonObject chunk = new JsonObject(); chunk.addProperty("targetIndex", target); chunk.add("path", path); chunk.addProperty("nbtType", tag.getId());
                int end = Math.min(text.length(), i + 4096);
                if (end < text.length() && end > i && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
                chunk.addProperty("stringOffset", i); chunk.addProperty("stringLength", text.length()); chunk.addProperty("value", text.substring(i, end)); capture.add(chunk);
                i = end;
            } while (i < text.length());
        } else { row.addProperty("valueSnbt", tag.toString()); row.addProperty("encoding", "exact_typed_snbt_scalar; long values are never converted to JSON floating point"); capture.add(row); }
    }

    private static JsonArray copyPath(JsonArray path) { JsonArray result = new JsonArray(); for (JsonElement part : path) result.add(part); return result; }
    private static void keys(JsonObject args, String... names) {
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!java.util.Arrays.asList(names).contains(entry.getKey())) throw new BridgeException("invalid_args", "Unexpected snapshot field: " + entry.getKey());
    }
    private static final class Capture {
        final String id = UUID.randomUUID().toString(), session, folder;
        final World world; final long created = System.nanoTime(), atMillis = System.currentTimeMillis(), tick;
        long endTick; int bytes, metadataBytes; final JsonArray targets = new JsonArray(); final List<JsonObject> entries = new ArrayList<JsonObject>();
        Capture(World world, String session, String folder) { this.world = world; this.session = session; this.folder = folder; this.tick = world.getTotalWorldTime(); }
        void add(JsonObject row) {
            int size = row.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > PAGE_BYTES / 2 || entries.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) throw new BridgeException("data_limit", "NBT capture exceeds its bounded entry/16 MiB/262144 entry limits; reduce targets. No truncated snapshot published.");
            bytes += size; entries.add(row);
        }
    }
}
