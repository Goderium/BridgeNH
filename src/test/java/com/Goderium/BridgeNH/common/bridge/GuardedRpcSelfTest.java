package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed context contracts and real HTTP envelopes on a mocked owning-thread queue. */
final class GuardedRpcSelfTest {
    private static int checks;
    static int run() throws Exception {
        checks = 0; guards(); http(); slotHttp(); nativeControlHttp(); return checks;
    }
    private static JsonObject request() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("expectedSessionId", "session");
        value.addProperty("expectedProfile", "developer"); value.addProperty("expectedWorld", "world"); value.addProperty("requireBackground", true); return value;
    }
    private static JsonObject snapshot() {
        JsonObject value = new JsonObject(); value.addProperty("sessionId", "session"); value.addProperty("profile", "developer"); value.addProperty("worldLoaded", true);
        JsonObject world = new JsonObject(); world.addProperty("worldName", "world"); value.add("world", world);
        JsonObject background = new JsonObject(); background.addProperty("enabled", true); background.addProperty("helperInstalled", true); background.addProperty("pauseOnLostFocus", false); value.add("background", background);
        JsonObject player = new JsonObject(); player.addProperty("x", 1.5); player.addProperty("feetY", 2); player.addProperty("z", 3.5); player.addProperty("flying", true); value.add("player", player); return value;
    }
    private static void guards() throws Exception {
        for (String field : Arrays.asList("sessionId", "profile", "world", "background")) {
            JsonObject wrong = snapshot();
            if (field.equals("sessionId") || field.equals("profile")) wrong.addProperty(field, "changed"); else wrong.remove(field);
            AtomicInteger actions = new AtomicInteger();
            try { GuardedContext.parse("player.hotbar", request()).execute(() -> wrong, () -> { actions.incrementAndGet(); return new JsonObject(); }); throw new AssertionError("Guard accepted " + field); }
            catch (BridgeException expected) { check(actions.get() == 0, "Fresh context failure must precede every native action"); }
        }
        JsonObject wrong = snapshot(); wrong.getAsJsonObject("background").addProperty("pauseOnLostFocus", true);
        try { GuardedContext.parse("player.interact", request()).check(wrong); throw new AssertionError("Paused protection accepted"); }
        catch (BridgeException expected) { check(expected.code.equals("TAKEOVER_LOST"), "Current background flags must retain exact types and values"); }
        List<String> order = new ArrayList<String>();
        GuardedContext action = GuardedContext.parse("player.hotbar", request());
        GuardedContext.Result result = action.execute(() -> { order.add("context"); return snapshot(); }, () -> { order.add("native"); return new JsonObject(); });
        check(order.equals(Arrays.asList("context", "native")) && result.context.get("phase").getAsString().equals("before"), "Native actions use one fresh pre-operation context");
        check(result.context.getAsJsonObject("snapshot").getAsJsonObject("player").get("feetY").getAsInt() == 2, "Full position/flight evidence is carried, not invented");
        order.clear();
        result = GuardedContext.parse("inventory.inspect", request()).execute(() -> { order.add("context"); return snapshot(); }, () -> { order.add("read"); return new JsonObject(); });
        check(order.equals(Arrays.asList("read", "context")) && result.context.get("phase").getAsString().equals("after"), "Read context must follow the actual read");
        AtomicInteger reads = new AtomicInteger();
        try { GuardedContext.parse("world.inspect", request()).execute(() -> { JsonObject changed = snapshot(); changed.addProperty("profile", "player"); return changed; }, () -> { reads.incrementAndGet(); return new JsonObject(); }); throw new AssertionError("Stale post-read context accepted"); }
        catch (BridgeException expected) { check(reads.get() == 1 && expected.code.equals("PROFILE_CHANGED"), "Post-read context changes stop later actions without pretending to roll back the read"); }
        for (String denied : Arrays.asList("client.screenshot", "client.exit", "client.load_world", "bridge.profile", "client.background", "qa.run", "dev.teleport", "machine.set_working", "bridge.guarded_call")) {
            try { GuardedContext.parse(denied, request()); throw new AssertionError("Recursive/lifecycle operation allowed: " + denied); }
            catch (BridgeException expected) { check(expected.code.equals("unsupported"), "Unsupported operations retain the original path only"); }
        }
        for (String invalid : Arrays.asList("{\"version\":true}", "{\"version\":2}", "{\"version\":1,\"requireBackground\":true,\"extra\":0}")) {
            try { GuardedContext.parse("player.hotbar", new JsonParser().parse(invalid)); throw new AssertionError("Invalid context accepted"); }
            catch (BridgeException expected) { check(expected.code.equals("invalid_request"), "Versioned context shape is fixed"); }
        }
    }
    private static void http() throws Exception {
        Fixture bridge = new Fixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "guarded-self-test");
        server.start(0);
        try {
            JsonObject legacy = post(server.port(), "player.hotbar", null);
            check(legacy.get("ok").getAsBoolean() && !legacy.has("context"), "Legacy success envelope stays unchanged");
            JsonObject guarded = post(server.port(), "player.hotbar", request());
            check(guarded.get("ok").getAsBoolean() && guarded.getAsJsonObject("context").get("phase").getAsString().equals("before"), "Authenticated HTTP carries separate native data and context");
            check(bridge.contextThread == bridge.operationThread && bridge.operationThread.equals("mock-client-owner"), "Guard and client native operation execute on the same owning queue thread");
            bridge.reject = true; int actions = bridge.actions.get();
            JsonObject rejected = post(server.port(), "player.hotbar", request());
            check(!rejected.get("ok").getAsBoolean() && !rejected.getAsJsonObject("error").get("outcome_unknown").getAsBoolean() && bridge.actions.get() == actions, "Failed fresh guards never invoke native action and report known refusal");
            bridge.reject = false; bridge.failAfterNative = true;
            JsonObject uncertain = post(server.port(), "player.hotbar", request());
            check(!uncertain.get("ok").getAsBoolean() && uncertain.getAsJsonObject("error").get("outcome_unknown").getAsBoolean() && bridge.actions.get() == actions + 1, "Failure after native invocation keeps an unknown mutation receipt");
            JsonObject denied = post(server.port(), "client.exit", request());
            check(!denied.get("ok").getAsBoolean() && bridge.actions.get() == actions + 1, "HTTP whitelist rejection cannot enter lifecycle or native dispatch");
        } finally { server.stop(); bridge.close(); }
    }
    private static void slotHttp() throws Exception {
        Fixture bridge = new Fixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "guarded-self-test");
        server.start(0);
        try {
            boolean advertised = false;
            for (com.google.gson.JsonElement action : GuardedContext.capabilities().getAsJsonArray("actions"))
                if ("gui.slot_click".equals(action.getAsString())) advertised = true;
            check(advertised, "Native slot clicks advertise the fixed before-action context contract");
            JsonObject legacy = post(server.port(), "gui.slot_click", null);
            check(legacy.get("ok").getAsBoolean() && !legacy.has("context"), "Legacy slot-click HTTP remains available without a context envelope");
            JsonObject guarded = post(server.port(), "gui.slot_click", request());
            JsonObject context = guarded.getAsJsonObject("context");
            check(guarded.get("ok").getAsBoolean() && "before".equals(context.get("phase").getAsString())
                    && "gui.slot_click".equals(context.get("operation").getAsString()), "Authenticated slot clicks carry fresh before-action context separately from native data");
            check(bridge.actions.get() == 1 && bridge.contextThread == bridge.operationThread
                    && "mock-client-owner".equals(bridge.operationThread), "The slot-click guard and fixture native callback run once on the same owning queue thread");
            bridge.reject = true;
            JsonObject rejected = post(server.port(), "gui.slot_click", request());
            check(!rejected.get("ok").getAsBoolean() && "PROFILE_CHANGED".equals(rejected.getAsJsonObject("error").get("code").getAsString())
                    && !rejected.getAsJsonObject("error").get("outcome_unknown").getAsBoolean() && bridge.actions.get() == 1,
                    "A changed fresh context refuses a slot click before the native callback");
            bridge.reject = false; bridge.failAfterNative = true;
            JsonObject uncertain = post(server.port(), "gui.slot_click", request());
            check(!uncertain.get("ok").getAsBoolean() && "partial_native".equals(uncertain.getAsJsonObject("error").get("code").getAsString())
                    && uncertain.getAsJsonObject("error").get("outcome_unknown").getAsBoolean() && bridge.actions.get() == 2,
                    "A slot-click fixture failure after invocation reports an unknown mutation without retrying");
        } finally { server.stop(); bridge.close(); }
    }
    private static void nativeControlHttp() throws Exception {
        Fixture bridge = new Fixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "guarded-self-test");
        server.start(0);
        try {
            for (String operation : Arrays.asList("player.place_block", "player.look", "player.flight", "chat.send")) {
                boolean advertised = false;
                for (com.google.gson.JsonElement candidate : GuardedContext.capabilities().getAsJsonArray("actions"))
                    if (operation.equals(candidate.getAsString())) advertised = true;
                check(advertised, "Native client control advertises same-thread BEFORE guard: " + operation);
                int actions = bridge.actions.get();
                JsonObject accepted = post(server.port(), operation, request());
                check(accepted.get("ok").getAsBoolean() && bridge.actions.get() == actions + 1
                        && operation.equals(accepted.getAsJsonObject("context").get("operation").getAsString())
                        && "before".equals(accepted.getAsJsonObject("context").get("phase").getAsString())
                        && bridge.contextThread == bridge.operationThread && "mock-client-owner".equals(bridge.operationThread),
                        "Fresh guard and native callback run once on the same owning queue: " + operation);
                bridge.reject = true;
                JsonObject rejected = post(server.port(), operation, request());
                check(!rejected.get("ok").getAsBoolean() && bridge.actions.get() == actions + 1
                        && !rejected.getAsJsonObject("error").get("outcome_unknown").getAsBoolean(),
                        "Changed context blocks native control before dispatch: " + operation);
                bridge.reject = false; bridge.failAfterNative = true;
                JsonObject uncertain = post(server.port(), operation, request());
                check(!uncertain.get("ok").getAsBoolean() && bridge.actions.get() == actions + 2
                        && uncertain.getAsJsonObject("error").get("outcome_unknown").getAsBoolean(),
                        "Native failure reports unknown without replay: " + operation);
                bridge.failAfterNative = false;
            }
        } finally { server.stop(); bridge.close(); }
    }
    private static JsonObject post(int port, String operation, JsonObject context) throws Exception {
        JsonObject request = new JsonObject(); request.addProperty("requestId", UUID.randomUUID().toString()); request.addProperty("operation", operation); request.add("args", new JsonObject());
        if (context != null) request.add("includeContext", context);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/call").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer guarded-self-test");
        connection.setConnectTimeout(2000); connection.setReadTimeout(4000); connection.getOutputStream().write(body); connection.getOutputStream().close();
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[2048]; int count; while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
            return new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        } finally { connection.disconnect(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static final class Fixture extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
        final GameQueue queue = new GameQueue(8); final AtomicInteger actions = new AtomicInteger();
        volatile boolean running = true, reject, failAfterNative; volatile String contextThread, operationThread;
        final Thread owner;
        Fixture() {
            owner = new Thread(() -> { while (running) { queue.drain(8); try { Thread.sleep(1); } catch (InterruptedException ignored) { return; } } }, "mock-client-owner"); owner.setDaemon(true); owner.start();
        }
        @Override public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) { return new JsonObject(); }
        @Override public GuardedContext.Result dispatchGuarded(String operation, JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
            return queue.await(queue.submit(() -> guard.execute(() -> { contextThread = Thread.currentThread().getName(); JsonObject value = snapshot(); if (reject) value.addProperty("profile", "player"); return value; },
                () -> { operationThread = Thread.currentThread().getName(); actions.incrementAndGet(); if (failAfterNative) throw new BridgeException("partial_native", "Fixture native failure"); return new JsonObject(); }), 3000, control), 3000);
        }
        void close() throws InterruptedException { running = false; owner.interrupt(); owner.join(2000); }
    }
}
