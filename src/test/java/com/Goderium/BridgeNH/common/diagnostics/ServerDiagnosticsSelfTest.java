package com.Goderium.BridgeNH.common.diagnostics;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Synthetic fixed-path logs and public metadata; no live server or world is started. */
public final class ServerDiagnosticsSelfTest {
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static JsonObject json(String text) { return new JsonParser().parse(text).getAsJsonObject(); }
    private static void refused(Runnable call, String code) {
        try { call.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(error.code.equals(code), "Explicit refusal " + code); }
    }
    public static int run() throws Exception {
        checks = 0;
        Path root = Files.createTempDirectory("mcb-server-log-fixture-");
        try {
            Path logs = Files.createDirectory(root.resolve("logs")), latest = logs.resolve("latest.log");
            Files.write(latest, ("[Server thread/INFO] Ready\n[Server thread/WARN] Fixture warning\n"
                + "[Server thread/ERROR] password=fixture-secret\njava.lang.Exception: fixture\n\tat fixture.Class.run\n").getBytes(StandardCharsets.UTF_8));
            JsonObject warn = ServerLogReader.read(root, new JsonObject(), true);
            check(warn.get("returned").getAsInt() == 2 && warn.get("redactedLines").getAsInt() == 1, "Severity and secret redaction");
            check(!warn.toString().contains("fixture-secret") && !warn.get("sharedClientServerLog").getAsBoolean(), "Dedicated receipt scope");
            JsonObject all = ServerLogReader.read(root, json("{\"level\":\"ANY\",\"limit\":2}"), false);
            check(all.get("returned").getAsInt() == 2 && all.getAsJsonArray("lines").get(1).getAsString().contains("fixture.Class"), "Tail keeps exception continuation order");
            check(all.get("truncated").getAsBoolean() && all.get("sharedClientServerLog").getAsBoolean(), "Integrated scope and line limit");
            JsonObject query = ServerLogReader.read(root, json("{\"level\":\"ANY\",\"query\":\"WARNING\"}"), true);
            check(query.get("returned").getAsInt() == 1, "Literal case-insensitive query");
            check(ServerLogReader.read(root, json("{\"level\":\"ANY\",\"query\":\"fixture-secret\"}"), true).get("returned").getAsInt() == 0, "Query cannot recover redacted content");
            refused(() -> ServerLogReader.read(root, json("{\"log\":\"../server.properties\"}"), true), "invalid_args");
            refused(() -> ServerLogReader.read(root, json("{\"path\":\"server.properties\"}"), true), "invalid_args");
            refused(() -> ServerLogReader.read(root, json("{\"limit\":129}"), true), "invalid_args");
            refused(() -> ServerLogReader.read(root, json("{\"level\":\"DEBUG\"}"), true), "invalid_args");
            refused(() -> ServerLogReader.read(root, json("{\"log\":\"fml-server-latest\"}"), true), "log_unavailable");
            Files.write(logs.resolve("fml-server-latest.log"), "[WARN] FML fixture\n".getBytes(StandardCharsets.UTF_8));
            check(ServerLogReader.read(root, json("{\"log\":\"fml-server-latest\"}"), true).get("log").getAsString().endsWith("fml-server-latest.log"), "Fixed FML server log");
            String huge = "password=" + new String(new char[70000]).replace('\0', 'q') + "\n[WARN] complete tail\n";
            Files.write(latest, huge.getBytes(StandardCharsets.UTF_8));
            JsonObject partial = ServerLogReader.read(root, json("{\"level\":\"ANY\"}"), true);
            check(partial.get("bytesRead").getAsInt() == 65536 && partial.get("partialFirstLineDropped").getAsBoolean(), "Hard input cap");
            check(partial.get("returned").getAsInt() == 1 && !partial.toString().contains("qqq"), "Partial first secret line is discarded");
            StringBuilder dense = new StringBuilder();
            for (int i = 0; i < 128; i++) dense.append("[WARN] ").append(new String(new char[400]).replace('\0', '\u0001')).append('\n');
            Files.write(latest, dense.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject bounded = ServerLogReader.read(root, new JsonObject(), true);
            check(bounded.get("byteLimited").getAsBoolean() && bounded.toString().getBytes(StandardCharsets.UTF_8).length <= 24000, "Escaped JSON obeys packet budget");
            RemoteReadProtocol.encode(bounded, RemoteReadProtocol.RESPONSE_BYTES);
            Files.delete(latest); Files.createDirectory(latest);
            refused(() -> ServerLogReader.read(root, new JsonObject(), true), "log_path_refused");
            Files.delete(latest);
            Path outside = root.resolve("private-fixture.txt"); Files.write(outside, "private fixture".getBytes(StandardCharsets.UTF_8));
            try {
                Files.createSymbolicLink(latest, outside);
                refused(() -> ServerLogReader.read(root, new JsonObject(), true), "log_path_refused");
            } catch (java.nio.file.FileSystemException | UnsupportedOperationException unavailable) {
                // Windows may prohibit symlink creation for the test account.
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            }
        }
        JsonObject empty = ServerDiagnostics.tickDurations(new long[]{0, 0});
        check(!empty.get("available").getAsBoolean() && empty.get("meanMillis").isJsonNull(), "No invented tick samples");
        JsonObject ticks = ServerDiagnostics.tickDurations(new long[]{100000000, 50000000, 0});
        check(ticks.get("sampleCount").getAsInt() == 2 && ticks.get("meanMillis").getAsDouble() == 75D, "Native sample summary");
        check(Math.abs(ticks.get("estimatedTpsIfSustained").getAsDouble() - 1000D / 75D) < 0.0001, "Explicit sustained-load estimate");
        List<ModContainer> mods = new ArrayList<ModContainer>();
        for (int i = 0; i < 70; i++) {
            ModMetadata meta = new ModMetadata(); meta.modId = "fixture" + i; meta.name = "Fixture " + i; meta.version = "1.0";
            mods.add(new DummyModContainer(meta));
        }
        JsonObject page = ServerDiagnostics.mods(mods, json("{\"offset\":64,\"limit\":4}"));
        check(page.get("total").getAsInt() == 70 && page.get("nextOffset").getAsInt() == 68 && page.get("returned").getAsInt() == 4, "Bounded metadata page");
        check(page.getAsJsonArray("mods").get(0).getAsJsonObject().get("modId").getAsString().equals("fixture64"), "Actual ordered metadata");
        check(!ServerDiagnostics.mods(mods, json("{\"offset\":70}")).get("hasMore").getAsBoolean(), "End page complete");
        refused(() -> ServerDiagnostics.mods(mods, json("{\"limit\":65}")), "invalid_args");
        return checks;
    }
}
