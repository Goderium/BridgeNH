package com.Goderium.BridgeNH.client.recording;

import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PlayerActivityLogSelfTest {
    private static int checks;
    public static int run() throws Exception {
        PlayerActivityRecorder recorder = new PlayerActivityRecorder("disabled-test", screen -> "unused");
        cpw.mods.fml.common.eventhandler.ASMEventHandler handler = new cpw.mods.fml.common.eventhandler.ASMEventHandler(recorder,
                PlayerActivityRecorder.class.getMethod("opening", net.minecraftforge.client.event.GuiOpenEvent.class), null);
        handler.invoke(new net.minecraftforge.client.event.GuiOpenEvent(null));
        recorder.tick(new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
        check(!recorder.log.enabled(), "real Forge generated listener can invoke disabled recorder without touching Minecraft");
        Path root = java.nio.file.Paths.get("build/tmp/agents-player-activity-selftest");
        Files.createDirectories(root); Path directory = Files.createTempDirectory(root, "journal-");
        PlayerActivityLog log = new PlayerActivityLog();
        check(log.add("disabled", "test", new JsonObject()) == 0, "default-off never records");
        JsonObject start = log.start(directory, "session-one", new JsonObject());
        String id = start.get("recordingId").getAsString(), file = start.get("path").getAsString();
        check(log.start(directory, "ignored", new JsonObject()).get("recordingId").getAsString().equals(id), "on is idempotent while enabled");
        JsonObject original = new JsonObject(); original.addProperty("text", "before");
        log.add("command", "test", original); original.addProperty("text", "mutated");
        JsonObject page = log.read(new JsonObject());
        check(page.getAsJsonArray("events").get(1).getAsJsonObject().getAsJsonObject("details").get("text").getAsString().equals("before"), "events freeze payload at observation");
        JsonObject args = new JsonObject(); args.addProperty("recordingId", id); args.addProperty("since", 1); args.addProperty("limit", 1);
        check(log.read(args).getAsJsonArray("events").size() == 1, "bounded incremental read");
        check(log.read(args).get("events").equals(log.read(args).get("events")), "repeated reads do not consume events or change recording");
        args.addProperty("recordingId", "old"); expect("recording_changed", () -> log.read(args));
        args.remove("recordingId"); args.addProperty("since", 1.5); expect("invalid_args", () -> log.read(args));
        args.addProperty("since", -1); expect("invalid_args", () -> log.read(args));
        args.addProperty("since", 999); expect("invalid_args", () -> log.read(args));
        JsonObject oversized = new JsonObject(); oversized.addProperty("text", repeat('x', 20000));
        log.add("oversized", "test", oversized);
        check(log.read(new JsonObject()).getAsJsonArray("events").get(2).getAsJsonObject().getAsJsonObject("details").get("payloadOmitted").getAsBoolean(), "oversize is explicit, never a truncated JSON string");
        log.stop("test_done"); log.shutdown();
        check(!log.enabled() && !log.status().get("flushPending").getAsBoolean(), "off drains writer");
        List<String> lines = Files.readAllLines(java.nio.file.Paths.get(file), StandardCharsets.UTF_8);
        check(lines.size() == 4, "JSONL preserves every accepted event plus start/stop");
        for (int i = 0; i < lines.size(); i++) check(new JsonParser().parse(lines.get(i)).getAsJsonObject().get("seq").getAsLong() == i + 1, "ordered durable sequence");
        log.start(directory, "session-one", new JsonObject());
        check(!log.status().get("recordingId").getAsString().equals(id) && Files.readAllLines(java.nio.file.Paths.get(file), StandardCharsets.UTF_8).equals(lines), "new runs never overwrite old evidence");
        JsonObject payload = new JsonObject(); payload.addProperty("value", repeat('a', 1500));
        for (int group = 0; group < 20; group++) {
            for (int i = 0; i < 100; i++) log.add("sample", "test", payload);
            awaitWritten(log);
        }
        JsonObject ring = log.read(new JsonObject());
        check(log.enabled() && ring.get("historyGap").getAsBoolean() && ring.get("oldestSeq").getAsLong() > 1, "memory eviction reports a gap while disk recording continues");
        check(ring.toString().getBytes(StandardCharsets.UTF_8).length < 32 * 1024 && ring.get("hasMore").getAsBoolean(), "bounded MCP page fits below 32 KiB");
        log.shutdown();
        check(log.status().get("latestSeq").equals(log.status().get("flushedSeq")), "ring eviction does not lose persisted events");
        PlayerActivityLog overloaded = new PlayerActivityLog(); overloaded.start(directory, "session-two", new JsonObject());
        synchronized (overloaded) {
            for (int i = 0; i < 700; i++) overloaded.add("burst", "test", new JsonObject());
        }
        overloaded.shutdown();
        check(!overloaded.enabled() && overloaded.status().get("stopReason").getAsString().equals("writer_queue_full"), "writer backlog stops recording explicitly without blocking player input");
        check(overloaded.status().get("rejectedEvents").getAsLong() == 1, "unaccepted event is accounted for");
        check(PlayerActivityLog.command(" /login very-secret ").equals("/login <redacted>"), "authentication arguments redacted");
        check(PlayerActivityLog.command("/tp 1 2 3").equals("/tp 1 2 3"), "ordinary command coordinates retained");
        check(ClientControlCommand.parse(new String[] {"record", "on"}).scanArgs.get("action").getAsString().equals("on"), "record command has its own route, not takeover");
        check(ClientControlCommand.parse(new String[] {"record", "status"}).control.equals("record"), "record status route");
        expect("invalid_args", () -> ClientControlCommand.parse(new String[] {"record", "anything"}));
        return checks;
    }
    private static void awaitWritten(PlayerActivityLog log) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (log.status().get("writtenSeq").getAsLong() != log.status().get("latestSeq").getAsLong()) {
            if (System.nanoTime() > end) throw new AssertionError("writer failed to drain"); Thread.sleep(5);
        }
    }
    private static String repeat(char c, int count) { char[] data = new char[count]; java.util.Arrays.fill(data, c); return new String(data); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable work) {
        try { work.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "typed refusal " + code); }
    }
}
