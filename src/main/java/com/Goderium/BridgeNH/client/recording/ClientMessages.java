package com.Goderium.BridgeNH.client.recording;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/** Session-local visible chat history; never reads log files. */
public class ClientMessages {
    private final Deque<JsonObject> entries = new ArrayDeque<JsonObject>();
    private long sequence;

    public synchronized void add(String text, long clientTicks) {
        JsonObject entry = new JsonObject();
        entry.addProperty("seq", ++sequence);
        entry.addProperty("text", JsonData.bounded(text, 2048));
        entry.addProperty("textTruncated", text != null && text.length() > 2048);
        entry.addProperty("clientTicks", clientTicks);
        entry.addProperty("receivedAtMillis", System.currentTimeMillis());
        if (entries.size() >= 128) entries.removeFirst();
        entries.addLast(entry);
    }

    public synchronized JsonObject inspect(JsonObject args, String sessionId) {
        long since = args.has("since") ? sequence(args) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32;
        String query = CatalogData.text(args, "query", 128, "");
        long oldest = entries.isEmpty() ? sequence + 1 : entries.getFirst().get("seq").getAsLong();
        boolean gap = since < oldest - 1;
        JsonArray messages = new JsonArray();
        boolean more = false;
        long next = since;
        for (JsonObject entry : entries) {
            if (entry.get("seq").getAsLong() <= since || !CatalogData.matches(query, entry.get("text").getAsString())) continue;
            if (messages.size() >= limit) { more = true; break; }
            messages.add(entry);
            next = entry.get("seq").getAsLong();
        }
        if (!more) next = Math.max(next, sequence);
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", sessionId);
        result.add("messages", messages);
        result.addProperty("oldestSeq", oldest);
        result.addProperty("latestSeq", sequence);
        result.addProperty("lastSequence", sequence);
        result.addProperty("nextSince", next);
        result.addProperty("hasMore", more);
        result.addProperty("historyGap", gap);
        result.addProperty("truncated", gap || more);
        result.addProperty("capacity", 128);
        result.addProperty("query", query);
        result.addProperty("source", "non-cancelled ClientChatReceivedEvent at LOWEST priority; bounded visible chat text, not log files");
        return result;
    }

    private static long sequence(JsonObject args) {
        try {
            if (!args.get("since").isJsonPrimitive() || !args.get("since").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            long value = new BigDecimal(args.get("since").getAsString()).longValueExact();
            if (value < 0) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw new BridgeException("invalid_args", "since must be a nonnegative integer."); }
    }
}
