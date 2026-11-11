package com.Goderium.BridgeNH.common.world;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Immutable, bounded structured baselines. Pages compare fresh data and never advance a baseline. */
public class StateDiffAdapter {
    public static final int MAX_BASELINES = 4, BASELINE_BYTES = 256 * 1024, MAX_NODES = 16384;
    static final int PAGE_NODES = 512, RESULT_BYTES = 128 * 1024, VALUE_BYTES = 8192;
    static final long IDLE_NANOS = 300000000000L, SOFT_NANOS = 4000000L;
    private final LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<String, Baseline>(8, .75F, true);
    private final LongSupplier clock;

    public StateDiffAdapter() { this(System::nanoTime); }
    StateDiffAdapter(LongSupplier clock) { this.clock = clock; }

    public synchronized void invalidate() { baselines.clear(); }

    public synchronized List<String> sections(JsonObject args) {
        expire();
        if (args.has("baselineId")) {
            if (args.has("sections")) throw new BridgeException("invalid_args", "A continuation keeps its baseline sections.");
            return new ArrayList<String>(baseline(args).sections);
        }
        if (!args.has("sections")) return Arrays.asList("player", "inventory", "gui");
        JsonElement value = args.get("sections");
        if (!value.isJsonArray() || value.getAsJsonArray().size() < 1 || value.getAsJsonArray().size() > 4)
            throw new BridgeException("invalid_args", "sections must contain one through four unique section names.");
        Set<String> result = new LinkedHashSet<String>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
                throw new BridgeException("invalid_args", "sections must contain strings.");
            String name = entry.getAsString();
            if (!Arrays.asList("player", "inventory", "gui", "messages").contains(name) || !result.add(name))
                throw new BridgeException("invalid_args", "Unknown or duplicate section: " + JsonData.bounded(name, 128));
        }
        return new ArrayList<String>(result);
    }

    /** current contains only the requested sections; all game reads precede this pure comparison. */
    public synchronized JsonObject observe(JsonObject args, JsonObject current, Object world, String profile,
                                           String sessionId, long clientTick) {
        long started = clock.getAsLong();
        List<String> sections = sections(args);
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        Baseline baseline;
        boolean created = !args.has("baselineId");
        if (created) {
            String encoded = current.toString();
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > BASELINE_BYTES) throw new BridgeException("too_large", "Selected baseline exceeds 256 KiB; select fewer sections.");
            JsonObject copy = new JsonParser().parse(encoded).getAsJsonObject();
            baseline = new Baseline(UUID.randomUUID().toString(), sections, copy, bytes, world, profile, clientTick, started);
            flatten(copy, new ArrayList<String>(), baseline.nodes, 0);
            while (baselines.size() >= MAX_BASELINES) baselines.remove(baselines.keySet().iterator().next());
            baselines.put(baseline.id, baseline);
        } else {
            baseline = baseline(args);
            if (baseline.world != world || !baseline.profile.equals(profile)) {
                baselines.remove(baseline.id);
                throw new BridgeException("stale_baseline", "Baseline world or access profile changed.");
            }
            baseline.lastUsed = started;
        }
        JsonArray changes = new JsonArray();
        int index = Math.min(offset, baseline.nodes.size()), visited = 0;
        int bytes = 4096;
        boolean byteLimited = false;
        if (!created) {
            while (index < baseline.nodes.size() && visited < PAGE_NODES && changes.size() < limit) {
                if (visited > 0 && clock.getAsLong() - started >= SOFT_NANOS) break;
                Node node = baseline.nodes.get(index);
                JsonElement after = valueAt(current, node.parts);
                JsonObject change = difference(node, after);
                if (change != null) {
                    int size = change.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes + size > RESULT_BYTES - 4096) { byteLimited = true; break; }
                    bytes += size;
                    changes.add(change);
                }
                visited++;
                boolean sameContainer = node.value.isJsonObject() && after != null && after.isJsonObject()
                        || node.value.isJsonArray() && after != null && after.isJsonArray();
                index = sameContainer ? index + 1 : node.end;
            }
        }
        boolean more = !created && index < baseline.nodes.size();
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", sessionId); result.addProperty("baselineId", baseline.id);
        result.addProperty("created", created); result.addProperty("baselineTick", baseline.tick);
        result.addProperty("currentTick", clientTick); result.addProperty("baselineBytes", baseline.bytes);
        result.addProperty("baselineNodeCount", baseline.nodes.size()); result.addProperty("offset", offset);
        result.add("nextOffset", more ? new JsonPrimitive(index) : JsonNull.INSTANCE);
        result.addProperty("hasMore", more); result.addProperty("nodesCompared", visited);
        result.addProperty("byteLimited", byteLimited); result.addProperty("nonAtomic", true);
        JsonArray names = new JsonArray(); for (String name : sections) names.add(new JsonPrimitive(name));
        result.add("sections", names); result.add("changes", changes);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("offset", "Baseline comparison-node index, not change count. Continue nextOffset; every page observes current data afresh.");
        coverage.addProperty("baseline", "Immutable, memory-only structured sections; no images. Creation returns metadata, not a duplicate full snapshot.");
        coverage.addProperty("values", "Values over 8192 UTF-8 bytes are replaced by an explicit summary; use the corresponding source tool for details.");
        coverage.addProperty("arrays", "Indexes refer to the observed array ordering; IDs inside values identify GUI objects/resources.");
        coverage.addProperty("comparison", "No queued events or atomic multi-page history; additions are reported at their nearest baseline container.");
        result.add("coverage", coverage);
        JsonObject budget = new JsonObject();
        budget.addProperty("softMillis", 4); budget.addProperty("elapsedNanos", clock.getAsLong() - started);
        budget.addProperty("cooperative", true); result.add("budget", budget);
        return result;
    }

    private Baseline baseline(JsonObject args) {
        String id = CatalogData.text(args, "baselineId", 128, null);
        if (id.isEmpty()) throw new BridgeException("invalid_args", "baselineId must not be empty.");
        Baseline result = baselines.get(id);
        if (result == null) throw new BridgeException("stale_baseline", "Baseline expired, changed context, or was evicted.");
        return result;
    }

    private void expire() {
        long now = clock.getAsLong();
        Iterator<Baseline> entries = baselines.values().iterator();
        while (entries.hasNext()) if (now - entries.next().lastUsed >= IDLE_NANOS) entries.remove();
    }

    private static void flatten(JsonElement value, List<String> parts, List<Node> nodes, int depth) {
        if (nodes.size() >= MAX_NODES || depth > 64) throw new BridgeException("too_large", "Baseline comparison index exceeds its bounded size.");
        Node node = new Node(new ArrayList<String>(parts), value);
        nodes.add(node);
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                parts.add(entry.getKey()); flatten(entry.getValue(), parts, nodes, depth + 1); parts.remove(parts.size() - 1);
            }
        } else if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                parts.add(Integer.toString(i)); flatten(value.getAsJsonArray().get(i), parts, nodes, depth + 1); parts.remove(parts.size() - 1);
            }
        }
        node.end = nodes.size();
    }

    private static JsonElement valueAt(JsonObject current, List<String> parts) {
        JsonElement value = current;
        for (String part : parts) {
            if (value == null) return null;
            if (value.isJsonObject()) value = value.getAsJsonObject().get(part);
            else if (value.isJsonArray()) {
                int index = Integer.parseInt(part);
                value = index < value.getAsJsonArray().size() ? value.getAsJsonArray().get(index) : null;
            } else return null;
        }
        return value;
    }

    private static JsonObject difference(Node node, JsonElement after) {
        JsonElement before = node.value;
        String kind = after == null ? "remove" : "replace";
        if (before.isJsonObject() && after != null && after.isJsonObject()) {
            JsonObject added = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : after.getAsJsonObject().entrySet())
                if (!before.getAsJsonObject().has(entry.getKey())) added.add(entry.getKey(), entry.getValue());
            if (added.entrySet().isEmpty()) return null;
            before = new JsonObject(); after = added; kind = "fields_added";
        } else if (before.isJsonArray() && after != null && after.isJsonArray()) {
            if (after.getAsJsonArray().size() <= before.getAsJsonArray().size()) return null;
            JsonArray added = new JsonArray();
            for (int i = before.getAsJsonArray().size(); i < after.getAsJsonArray().size(); i++) added.add(after.getAsJsonArray().get(i));
            before = new JsonArray(); after = added; kind = "items_appended";
        } else if (before.equals(after)) return null;
        JsonObject result = new JsonObject();
        StringBuilder path = new StringBuilder();
        for (String part : node.parts) path.append('/').append(part.replace("~", "~0").replace("/", "~1"));
        result.addProperty("path", path.toString()); result.addProperty("kind", kind);
        result.addProperty("beforePresent", true); result.addProperty("afterPresent", after != null);
        boolean[] truncated = {false};
        result.add("before", boundedValue(before, truncated)); result.add("after", boundedValue(after, truncated));
        result.addProperty("valueTruncated", truncated[0]);
        return result;
    }

    private static JsonElement boundedValue(JsonElement value, boolean[] truncated) {
        if (value == null) return JsonNull.INSTANCE;
        int bytes = value.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= VALUE_BYTES) return value;
        truncated[0] = true;
        JsonObject summary = new JsonObject();
        summary.addProperty("summary", true); summary.addProperty("bytes", bytes);
        summary.addProperty("type", value.isJsonObject() ? "object" : value.isJsonArray() ? "array" : "primitive");
        if (value.isJsonArray()) summary.addProperty("length", value.getAsJsonArray().size());
        if (value.isJsonObject()) summary.addProperty("fields", value.getAsJsonObject().entrySet().size());
        return summary;
    }

    private static final class Node {
        final List<String> parts; final JsonElement value; int end;
        Node(List<String> parts, JsonElement value) { this.parts = parts; this.value = value; }
    }
    private static final class Baseline {
        final String id, profile; final List<String> sections; final List<Node> nodes = new ArrayList<Node>();
        final int bytes; final Object world; final long tick; long lastUsed;
        Baseline(String id, List<String> sections, JsonObject snapshot, int bytes, Object world, String profile, long tick, long now) {
            this.id = id; this.sections = new ArrayList<String>(sections); this.bytes = bytes; this.world = world;
            this.profile = profile; this.tick = tick; lastUsed = now;
        }
    }
}
