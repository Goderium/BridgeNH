package com.Goderium.BridgeNH.client.recording;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded passive journal. The writer never touches game objects; reading never consumes events. */
public final class PlayerActivityLog {
    static final int MEMORY_BYTES = 2 * 1024 * 1024;
    static final int EVENT_BYTES = 16 * 1024;
    static final long RUN_BYTES = 32L * 1024 * 1024;
    static final long DIRECTORY_BYTES = 128L * 1024 * 1024;
    private final Deque<String> recent = new ArrayDeque<String>();
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<String>(512);
    private volatile boolean enabled;
    private volatile Thread writer;
    private String recordingId = "", sessionId = "", file = "", stopReason = "not_started";
    private long sequence, oldest = 1, acceptedBytes, byteBudget, writtenSequence, flushedSequence, rejectedEvents;
    private int memoryBytes;

    synchronized JsonObject start(Path directory, String session, JsonObject context) {
        if (enabled) return status();
        if (writer != null && writer.isAlive()) throw new BridgeException("recording_busy", "Previous recording is still flushing; no new recording started.");
        Path target;
        try {
            Files.createDirectories(directory);
            long used = 0; int files = 0;
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "recording-*.jsonl")) {
                for (Path path : paths) {
                    if (++files > 256) throw new IOException("file_count_limit");
                    used += Files.size(path);
                }
            }
            byteBudget = Math.min(RUN_BYTES, DIRECTORY_BYTES - used);
            if (byteBudget < 64 * 1024) throw new IOException("directory_budget");
            recordingId = UUID.randomUUID().toString();
            target = directory.resolve("recording-" + recordingId + ".jsonl");
            Files.createFile(target);
        } catch (IOException error) {
            throw new BridgeException("recording_unavailable", "Cannot create bounded recording. Existing files were preserved: " + error.getClass().getSimpleName());
        }
        recent.clear(); pending.clear(); memoryBytes = 0; sequence = 0; oldest = 1;
        acceptedBytes = 0; writtenSequence = 0; flushedSequence = 0; rejectedEvents = 0;
        sessionId = session; file = target.toAbsolutePath().toString(); stopReason = ""; enabled = true;
        add("recording_started", "recorder", context);
        writer = new Thread(() -> write(target), "MCB passive recording writer");
        writer.setDaemon(true); writer.start();
        return status();
    }

    boolean enabled() { return enabled; }

    synchronized long add(String kind, String source, JsonObject details) {
        if (!enabled) return 0;
        JsonObject event = new JsonObject();
        event.addProperty("seq", sequence + 1); event.addProperty("recordingId", recordingId);
        event.addProperty("sessionId", sessionId); event.addProperty("timeMillis", System.currentTimeMillis());
        event.addProperty("kind", kind); event.addProperty("source", source); event.add("details", details);
        String line = event.toString();
        int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (bytes > EVENT_BYTES) {
            JsonObject omitted = new JsonObject(); omitted.addProperty("payloadOmitted", true);
            omitted.addProperty("reason", "event_byte_budget"); omitted.addProperty("originalBytes", bytes);
            event.add("details", omitted); line = event.toString(); bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        if (acceptedBytes + bytes > byteBudget || !pending.offer(line)) {
            rejectedEvents++; enabled = false;
            stopReason = acceptedBytes + bytes > byteBudget ? "file_budget" : "writer_queue_full";
            return 0;
        }
        sequence++; acceptedBytes += bytes; memoryBytes += bytes; recent.addLast(line);
        while (memoryBytes > MEMORY_BYTES || recent.size() > 8192) {
            memoryBytes -= recent.removeFirst().getBytes(StandardCharsets.UTF_8).length + 1; oldest++;
        }
        return sequence;
    }

    synchronized JsonObject stop(String reason) {
        if (enabled) {
            JsonObject detail = new JsonObject(); detail.addProperty("reason", reason);
            add("recording_stopped", "recorder", detail);
            if (enabled) { stopReason = reason; enabled = false; }
        }
        return status();
    }

    public synchronized JsonObject status() {
        JsonObject result = new JsonObject(); result.addProperty("recordingVersion", 1);
        result.addProperty("enabled", enabled); result.addProperty("recordingId", recordingId);
        result.addProperty("sessionId", sessionId); result.addProperty("path", file);
        result.addProperty("latestSeq", sequence); result.addProperty("oldestSeq", oldest);
        result.addProperty("writtenSeq", writtenSequence); result.addProperty("flushedSeq", flushedSequence);
        result.addProperty("flushPending", flushedSequence < sequence);
        result.addProperty("writerActive", writer != null && writer.isAlive());
        result.addProperty("rejectedEvents", rejectedEvents); result.addProperty("stopReason", stopReason);
        result.addProperty("acceptedBytes", acceptedBytes); result.addProperty("fileByteBudget", byteBudget);
        result.addProperty("memoryByteBudget", MEMORY_BYTES); result.addProperty("directoryByteBudget", DIRECTORY_BYTES);
        result.addProperty("automaticReplayAllowed", false);
        return result;
    }

    synchronized JsonObject read(JsonObject args) {
        long since = 0;
        try {
            if (args.has("since")) {
                if (!args.get("since").isJsonPrimitive() || !args.getAsJsonPrimitive("since").isNumber()) throw new IllegalArgumentException();
                since = new BigDecimal(args.get("since").getAsString()).longValueExact();
            }
            if (since < 0 || since > sequence) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new BridgeException("invalid_args", "since must be an integer in this recording's sequence range."); }
        if (args.has("recordingId") && !recordingId.equals(JsonData.string(args, "recordingId")))
            throw new BridgeException("recording_changed", "Recording identity changed; do not reuse the old sequence cursor.");
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32;
        JsonObject result = status(); JsonArray events = new JsonArray();
        long next = since, current = oldest; int bytes = 0;
        for (String line : recent) {
            long index = current++;
            if (index <= since) continue;
            int cost = line.getBytes(StandardCharsets.UTF_8).length;
            if (events.size() >= limit || bytes + cost > 24 * 1024) break;
            events.add(new JsonParser().parse(line)); next = index; bytes += cost;
        }
        result.add("events", events); result.addProperty("nextSince", next);
        result.addProperty("historyGap", since < oldest - 1); result.addProperty("hasMore", next < sequence);
        result.addProperty("scope", "current_process_latest_recording; older events remain in the fixed local JSONL file");
        return result;
    }

    private void write(Path target) {
        long lastFlush = System.nanoTime();
        try (BufferedWriter output = Files.newBufferedWriter(target, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            while (enabled || !pending.isEmpty()) {
                String line = pending.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    output.write(line); output.write('\n');
                    synchronized (this) { writtenSequence++; }
                }
                if (System.nanoTime() - lastFlush >= TimeUnit.SECONDS.toNanos(1) || !enabled && pending.isEmpty()) {
                    output.flush(); synchronized (this) { flushedSequence = writtenSequence; }
                    lastFlush = System.nanoTime();
                }
            }
            output.flush(); synchronized (this) { flushedSequence = writtenSequence; }
        } catch (IOException | InterruptedException error) {
            synchronized (this) { enabled = false; stopReason = "writer_failed_" + error.getClass().getSimpleName(); }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        stop("client_shutdown"); Thread thread = writer;
        if (thread != null) {
            try { thread.join(2000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    static String command(String value) {
        String text = value == null ? "" : value.trim();
        String root = text.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (java.util.Arrays.asList("/login", "/register", "/l", "/reg", "/auth", "/password", "/changepassword").contains(root))
            return root + " <redacted>";
        return JsonData.bounded(text, 2048);
    }
}
