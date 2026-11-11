package com.Goderium.BridgeNH.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pagination helpers shared by bounded client catalogs. */
public class CatalogData {
    public static String text(JsonObject args, String key, int maximum, String fallback) {
        String value = args.has(key) ? JsonData.string(args, key) : fallback;
        if (value == null || value.length() > maximum)
            throw new BridgeException("invalid_args", key + " must contain at most " + maximum + " characters.");
        return value;
    }

    public static boolean matches(String query, String... fields) {
        String needle = query.toLowerCase(Locale.ROOT);
        for (String field : fields) if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    public static JsonObject page(String key, List<JsonObject> entries, JsonObject args, int maximumOffset) {
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, maximumOffset) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32;
        JsonArray page = new JsonArray();
        int end = Math.min(entries.size(), offset + limit);
        for (int i = offset; i < end; i++) page.add(entries.get(i));
        boolean more = end < entries.size();
        JsonObject result = new JsonObject();
        result.add(key, page);
        result.addProperty("totalMatched", entries.size());
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("hasMore", more);
        result.addProperty("truncated", more);
        if (more) result.addProperty("nextOffset", end);
        else result.add("nextOffset", JsonNull.INSTANCE);
        return result;
    }

    public static List<JsonObject> matching(List<JsonObject> entries, String query, String... keys) {
        List<JsonObject> result = new ArrayList<JsonObject>();
        for (JsonObject entry : entries) {
            String[] fields = new String[keys.length];
            for (int i = 0; i < keys.length; i++) fields[i] = entry.has(keys[i]) ? entry.get(keys[i]).getAsString() : "";
            if (matches(query, fields)) result.add(entry);
        }
        return result;
    }
}
