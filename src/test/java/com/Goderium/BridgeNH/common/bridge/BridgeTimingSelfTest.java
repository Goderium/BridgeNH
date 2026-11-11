package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline real queue/loopback transport and pure current-readiness boundaries. */
public class BridgeTimingSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        queueSuccessAndFailure();
        cancellationAndBusy();
        runningIncomplete();
        headerContract();
        readinessBoundaries();
        loopback();
        System.out.println("Bridge timing/readiness checks: " + checks);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void queueSuccessAndFailure() throws Exception {
        GameQueue queue = new GameQueue(4);
        GameQueue.Control control = new GameQueue.Control();
        GameQueue.Ticket<Integer> first = queue.submit(() -> 17, 8000, control);
        check(control.timingSnapshot()[0] == 1 && control.timingSnapshot()[1] == 0, "submission is not execution");
        queue.drain(1);
        check(queue.await(first, 1000) == 17, "original success");
        long[] finished = control.timingSnapshot();
        check(finished[0] == 1 && finished[1] == 1 && finished[2] == 1, "callback timing visible before await returns");
        check(finished[4] > 0 && finished[5] > 0 && finished[6] == 1 && finished[7] > 0, "wall time measured");
        GameQueue.Ticket<Integer> second = queue.submit(() -> { throw new BridgeException("fixture", "Original failure."); }, 8000, control);
        queue.drain(1);
        try { queue.await(second, 1000); throw new AssertionError("original failure missing"); }
        catch (ExecutionException failure) { check(((BridgeException) failure.getCause()).code.equals("fixture"), "failure preserved"); }
        finished = control.timingSnapshot();
        check(finished[0] == 2 && finished[1] == 2 && finished[2] == 2 && finished[6] == 2, "sequential aggregate");
        control.recordNativeAwait(50); control.recordNativeAwait(70);
        check(control.timingSnapshot()[8] == 2 && control.timingSnapshot()[9] == 120, "explicit native wait aggregate");
        control.recordNativeAwait(Long.MAX_VALUE);
        check(control.timingSnapshot()[9] == Long.MAX_VALUE, "duration addition saturates");
    }

    private static void cancellationAndBusy() throws Exception {
        GameQueue queue = new GameQueue(1); AtomicInteger executed = new AtomicInteger();
        GameQueue.Control cancelled = new GameQueue.Control();
        GameQueue.Ticket<Integer> ticket = queue.submit(executed::incrementAndGet, 8000, cancelled);
        check(ticket.cancelBeforeExecution(), "cancel before execution"); ticket.cancelBeforeExecution(); queue.drain(1);
        try { queue.await(ticket, 1000); throw new AssertionError("cancel missing"); }
        catch (ExecutionException failure) { check(((BridgeException) failure.getCause()).code.equals("cancelled"), "original cancellation"); }
        long[] counts = cancelled.timingSnapshot();
        check(counts[0] == 1 && counts[1] == 0 && counts[2] == 0 && counts[3] == 1, "cancel counted once");
        check(counts[4] == 0 && counts[5] == 0 && executed.get() == 0, "unexecuted callback not claimed as measured");
        GameQueue.Control expired = new GameQueue.Control();
        GameQueue.Ticket<Integer> deadline = queue.submit(executed::incrementAndGet, -1, expired); queue.drain(1);
        check(expired.timingSnapshot()[3] == 1 && expired.timingSnapshot()[1] == 0, "expired-before claim");
        try { queue.await(deadline, 1000); throw new AssertionError("deadline missing"); } catch (ExecutionException expected) { checks++; }
        GameQueue.Control pre = new GameQueue.Control(); pre.cancelBeforeExecution();
        queue.submit(executed::incrementAndGet, 8000, pre);
        check(pre.timingSnapshot()[0] == 0 && pre.timingSnapshot()[3] == 0, "pre-cancel was never submitted");
        queue.submit(executed::incrementAndGet, 8000);
        GameQueue.Control busy = new GameQueue.Control();
        try { queue.submit(executed::incrementAndGet, 8000, busy); throw new AssertionError("busy missing"); }
        catch (BridgeException failure) { check(failure.code.equals("busy") && busy.timingSnapshot()[0] == 0, "rejected busy not submitted"); }
    }

    private static void runningIncomplete() throws Exception {
        GameQueue queue = new GameQueue(1); GameQueue.Control control = new GameQueue.Control();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        GameQueue.Ticket<Integer> ticket = queue.submit(() -> { started.countDown(); finish.await(); return 5; }, 8000, control);
        Thread owner = new Thread(() -> queue.drain(1)); owner.start();
        try {
            check(started.await(2, TimeUnit.SECONDS), "running fixture started");
            long[] values = control.timingSnapshot();
            check(values[0] == 1 && values[1] == 1 && values[2] == 0 && values[5] == 0, "running callback remains incomplete");
            try { queue.await(ticket, 1); throw new AssertionError("timeout missing"); }
            catch (BridgeException failure) { check(failure.code.equals("timeout"), "original running timeout"); }
            check(control.timingSnapshot()[3] == 0 && control.timingSnapshot()[2] == 0, "timeout cannot claim callback finish");
        } finally { finish.countDown(); owner.join(2000); }
        check(!owner.isAlive() && queue.await(ticket, 1000) == 5, "original completion after timeout");
        check(control.timingSnapshot()[2] == 1 && control.timingSnapshot()[5] > 0, "late callback eventually measured");
    }

    private static void headerContract() {
        Headers headers = new Headers(); BridgeHttpServer.timingHeaders(headers, 123, null);
        check(headers.size() == 12 && "1".equals(headers.getFirst("X-MCB-Timing-Version")), "fixed versioned header set");
        check("123".equals(headers.getFirst("X-MCB-Timing-Http-Wall-Ns")), "HTTP wall scope");
        check("0".equals(headers.getFirst("X-MCB-Timing-Finished-Tickets")), "no control has no callbacks");
    }

    private static JsonObject ready(String profile, boolean world, boolean player, boolean healthy, boolean consistent,
            boolean server, boolean local, boolean matches, boolean developer, boolean paused, boolean pending, boolean menu, boolean background) {
        return ClientReadiness.healthReadiness(profile, world, player, healthy, consistent, server, local, matches, developer, paused, pending, menu, background);
    }

    private static void readinessBoundaries() {
        JsonObject menu = ready("player", false, false, false, false, false, false, false, false, false, false, true, false);
        check(menu.get("menuReady").getAsBoolean() && menu.get("stage").getAsString().equals("main_menu"), "true main menu");
        check(menu.get("nextToolCondition").getAsString().contains("skip worlds"), "known exact folder avoids duplicate listing");
        check(!ready("player", false, false, false, false, true, false, false, false, false, false, true, false).get("menuReady").getAsBoolean(), "LAN/nonlocal server cannot become menu");
        check(!ready("player", false, false, false, false, false, false, false, false, false, false, false, false).get("menuReady").getAsBoolean(), "unknown screen is not a menu");
        check(ready("player", false, false, false, false, true, true, false, false, false, false, false, false).get("stage").getAsString().equals("world_loading"), "partial startup");
        JsonObject current = ready("developer", true, true, true, true, true, true, true, true, false, false, false, true);
        check(current.get("worldReady").getAsBoolean() && current.get("developerReady").getAsBoolean(), "consistent local developer current world");
        check(!current.get("machineFormationProved").getAsBoolean() && !current.get("retryAction").getAsBoolean(), "readiness is no machine proof or replay authorization");
        for (int missing = 0; missing < 7; missing++) {
            JsonObject state = ready("developer", missing != 0, missing != 1, missing != 2, missing != 3,
                    true, missing != 4, missing != 5, true, false, missing == 6, false, true);
            check(!state.get("worldReady").getAsBoolean() && !state.get("developerReady").getAsBoolean(), "missing readiness evidence " + missing);
        }
        check(ready("developer", true, true, true, true, true, true, true, true, true, false, false, true).get("stage").getAsString().equals("world_paused"), "paused state");
        check(ready("developer", true, true, true, true, true, true, true, true, false, true, false, true).get("stage").getAsString().equals("lifecycle_pending"), "scheduled is not ready");
        check(!ready("player", true, true, true, true, true, true, true, false, false, false, false, true).get("developerReady").getAsBoolean(), "player profile preserved");
        check(!ready("developer", true, true, true, true, true, true, true, false, false, false, false, true).get("developerReady").getAsBoolean(), "stale developer context refused");
        check(ready("developer", true, true, true, true, true, true, true, true, false, false, false, false).get("nextTool").getAsString().equals("mcb_background"), "conditional background suggestion");
    }

    private static void loopback() throws Exception {
        Fixture bridge = new Fixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "timing-fixture-secret"); server.start(0);
        try {
            for (String operation : new String[] {"success", "failure"}) {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/v1/call").openConnection();
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
                connection.setRequestProperty("Authorization", "Bearer timing-fixture-secret"); connection.setRequestProperty("Content-Type", "application/json");
                byte[] body = ("{\"requestId\":\"" + operation + "\",\"operation\":\"" + operation + "\",\"args\":{}}").getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(body); connection.getOutputStream().close();
                check(connection.getResponseCode() == 200, "original HTTP status");
                check("1".equals(connection.getHeaderField("X-MCB-Timing-Version")) && "1".equals(connection.getHeaderField("X-MCB-Timing-Finished-Tickets")), "real response callback headers");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[1024]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); }
                JsonObject envelope = new JsonParser().parse(new String(output.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                check(envelope.entrySet().size() == 3 && envelope.get("requestId").getAsString().equals(operation), "strict envelope unchanged");
                check(envelope.get("ok").getAsBoolean() == operation.equals("success"), "original outcome");
                check(operation.equals("success") ? envelope.getAsJsonObject("data").get("count").getAsInt() == 17
                        : envelope.getAsJsonObject("error").get("code").getAsString().equals("fixture"), "action data/refusal preserved");
                connection.disconnect();
            }
            check(bridge.actions.get() == 2, "each original callback exactly once");
        } finally { server.stop(); }
    }

    private static class Fixture extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
        final GameQueue queue = new GameQueue(2); final AtomicInteger actions = new AtomicInteger();
        @Override public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
            GameQueue.Ticket<JsonObject> ticket = queue.submit(() -> {
                actions.incrementAndGet();
                if (operation.equals("failure")) throw new BridgeException("fixture", "Original refusal.");
                JsonObject data = new JsonObject(); data.addProperty("count", 17); return data;
            }, 8000, control);
            queue.drain(1); return queue.await(ticket, 1000);
        }
    }
}
