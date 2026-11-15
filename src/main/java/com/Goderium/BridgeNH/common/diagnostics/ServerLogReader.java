package com.Goderium.BridgeNH.common.diagnostics;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Fixed server-process log tails. Filesystem work runs outside the game threads. */
public final class ServerLogReader {
    public static final int TAIL_BYTES = 65536, RESULT_BYTES = 24000;
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?i)(?:\\bbearer\\s+|\\bauthorization\\b|\\bauth(?:entication)?\\b|\\bcredentials\\b|"
        + "\\b(?:access|refresh|session|auth|id)[ _-]?token\\b|\\btoken\\b|\\bpassword\\b|\\bpasswd\\b|"
        + "\\bsecret\\b|\\bapi[ _-]?key\\b|\\blaunch(?:er)?[ _-]?(?:auth|credentials)\\b|"
        + "--(?:accessToken|session|username)\\b|\\bsession[ _-]?id\\b)");
    private static final Pattern WARN = Pattern.compile("\\b(?:WARN|ERROR|FATAL)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR = Pattern.compile("\\b(?:ERROR|FATAL)\\b", Pattern.CASE_INSENSITIVE);
    private ServerLogReader() { }

    public static void validate(JsonObject args) {
        RemoteReadProtocol.keys(args, "log", "level", "query", "limit");
        String log = text(args, "log", "latest", 32), level = text(args, "level", "WARN", 8);
        if (!"latest".equals(log) && !"fml-server-latest".equals(log))
            throw new BridgeException("invalid_args", "Expected latest or fml-server-latest.");
        if (!"ANY".equals(level) && !"WARN".equals(level) && !"ERROR".equals(level))
            throw new BridgeException("invalid_args", "Expected ANY, WARN or ERROR.");
        text(args, "query", "", 256);
        if (args.has("limit")) JsonData.integer(args, "limit", 1, 128);
    }

    private static String text(JsonObject args, String key, String fallback, int maximum) {
        if (!args.has(key)) return fallback;
        String value = JsonData.string(args, key);
        if (value.length() > maximum) throw new BridgeException("invalid_args", key + " exceeds its limit.");
        return value;
    }

    public static JsonObject read(Path directory, JsonObject args, boolean dedicated) {
        long started = System.nanoTime();
        validate(args);
        String filename = "fml-server-latest".equals(text(args, "log", "latest", 32))
            ? "fml-server-latest.log" : "latest.log";
        String level = text(args, "level", "WARN", 8), query = text(args, "query", "", 256);
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 128;
        long size, start, modified;
        byte[] bytes;
        try {
            Path root = directory.toRealPath(), file = root.resolve("logs").resolve(filename);
            if (!file.toRealPath().equals(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new BridgeException("log_path_refused", "The fixed server log must be a regular file without redirected path components.");
            modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
            try (SeekableByteChannel stream = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                size = stream.size(); start = Math.max(0, size - TAIL_BYTES);
                stream.position(start);
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, TAIL_BYTES));
                while (buffer.hasRemaining() && stream.read(buffer) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                }
                bytes = new byte[buffer.position()]; buffer.flip(); buffer.get(bytes);
            }
        } catch (IOException | SecurityException unavailable) {
            throw new BridgeException("log_unavailable", "The selected fixed server log is missing or cannot be read.");
        }
        int offset = 0;
        if (start > 0) {
            // Discard a partial first line, including any secret whose label was outside the tail.
            while (offset < bytes.length && bytes[offset++] != '\n') { }
        }
        String content = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        List<String> matches = new ArrayList<String>();
        int redacted = 0, clipped = 0;
        for (String original : content.split("\\r?\\n|\\r", -1)) {
            if (original.isEmpty()) continue;
            if (!"ANY".equals(level) && !("WARN".equals(level) ? WARN : ERROR).matcher(original).find()) continue;
            String line = original;
            if (SENSITIVE.matcher(line).find()) { line = "[redacted sensitive log line]"; redacted++; }
            if (!line.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            if (line.length() > 2048) {
                int end = Character.isHighSurrogate(line.charAt(2047)) ? 2047 : 2048;
                line = line.substring(0, end); clipped++;
            }
            matches.add(line);
        }
        JsonArray lines = new JsonArray();
        for (int index = Math.max(0, matches.size() - limit); index < matches.size(); index++)
            lines.add(new JsonPrimitive(matches.get(index)));
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("source", "server_process_log");
        result.addProperty("readSource", "server_process_log_file"); result.addProperty("capturedOutsideGameThread", true);
        result.addProperty("log", "logs/" + filename);
        result.addProperty("serverType", dedicated ? "dedicated" : "integrated");
        result.addProperty("sharedClientServerLog", !dedicated);
        result.addProperty("level", level); result.add("lines", lines);
        result.addProperty("fileSizeBytes", size); result.addProperty("fileModifiedAtMillis", modified);
        result.addProperty("bytesRead", bytes.length); result.addProperty("tailLimitBytes", TAIL_BYTES);
        result.addProperty("matchedInTail", matches.size()); result.addProperty("redactedLines", redacted);
        result.addProperty("clippedLines", clipped); result.addProperty("partialFirstLineDropped", start > 0);
        result.addProperty("historical", true); result.addProperty("nonAtomic", true);
        result.addProperty("readAtMillis", System.currentTimeMillis());
        result.addProperty("backgroundReadMillis", (System.nanoTime() - started) / 1000000D);
        result.addProperty("coverage", "Fixed current log tail only; missing matches do not prove absence. Rotation and concurrent writes are not an atomic snapshot. Integrated servers share their JVM logs with the client.");
        finish(result, lines, start > 0 || clipped > 0 || lines.size() < matches.size(), false);
        while (result.toString().getBytes(StandardCharsets.UTF_8).length > RESULT_BYTES && lines.size() > 0) {
            lines.remove(0); finish(result, lines, true, true);
        }
        return result;
    }

    private static void finish(JsonObject result, JsonArray lines, boolean truncated, boolean byteLimited) {
        result.addProperty("returned", lines.size()); result.addProperty("truncated", truncated);
        result.addProperty("byteLimited", byteLimited);
    }
}
