package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.client.navigation.PlayerNavigation;
import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransfer;
import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacement;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class BridgeHttpServer {
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final byte[] authorization;
    private final GameBridge bridge;
    private final RequestRegistry registry;
    // Only two /call handlers may await game ticks; other workers handle /cancel or bounded request parsing.
    private final Semaphore activeCalls = new Semaphore(2);
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService watchdog;

    public BridgeHttpServer(GameBridge bridge, String token) {
        this(bridge, token, new RequestRegistry(1024, 60000));
    }

    @com.google.common.annotations.VisibleForTesting
    public BridgeHttpServer(GameBridge bridge, String token, RequestRegistry registry) {
        this.registry = registry;
        this.bridge = bridge;
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "Codex Bridge HTTP");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(16), factory, new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(factory);
        server.setExecutor(executor);
        server.createContext("/v1/call", this::handle);
        server.createContext("/v1/cancel", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (watchdog != null) watchdog.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        final long handlerStarted = System.nanoTime();
        AtomicReference<GameQueue.Control> control = new AtomicReference<GameQueue.Control>();
        AtomicBoolean expired = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> watchdogTicket = new AtomicReference<ScheduledFuture<?>>();
        ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            expired.set(true);
            GameQueue.Control active = control.get();
            if (active != null) active.cancelBeforeExecution();
            exchange.close();
        }, 10, TimeUnit.SECONDS);
        watchdogTicket.set(deadline);
        boolean delivered = false;
        try { delivered = process(exchange, control, expired, handlerStarted, watchdogTicket); }
        finally {
            watchdogTicket.get().cancel(false);
            exchange.close();
            bridge.responseFinished(control.get(), delivered && !expired.get());
        }
    }

    private boolean process(HttpExchange exchange, AtomicReference<GameQueue.Control> current, AtomicBoolean expired, long handlerStarted, AtomicReference<ScheduledFuture<?>> watchdogTicket) throws IOException {
        String id = "";
        String operation = "";
        int status = 200;
        boolean permit = false;
        boolean dispatchStarted = false;
        GuardedContext guarded = null;
        JsonObject response;
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress())
                throw new BridgeException("unauthorized", "Loopback clients only.");
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.UTF_8))) {
                status = 401;
                throw new BridgeException("unauthorized", "A valid session token is required.");
            }
            boolean cancel = "/v1/cancel".equals(exchange.getRequestURI().getPath());
            if (!"POST".equals(exchange.getRequestMethod()) || (!cancel && !"/v1/call".equals(exchange.getRequestURI().getPath()))) {
                status = 405;
                throw new BridgeException("invalid_request", "Use POST /v1/call or POST /v1/cancel.");
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim()))
                throw new BridgeException("invalid_request", "Content-Type must be application/json.");
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            if (length != null && Long.parseLong(length) > 65536)
                throw new BridgeException("request_too_large", "Request body exceeded 64 KiB.");
            if (!cancel) {
                permit = activeCalls.tryAcquire();
            }
            JsonObject request;
            try { request = new JsonParser().parse(readBody(exchange.getRequestBody())).getAsJsonObject(); }
            catch (BridgeException error) {
                // Close before installing response headers: JDK's close otherwise drains
                // an unfinished request stream and can hold a worker indefinitely.
                if ("request_too_large".equals(error.code)) {
                    exchange.close();
                    throw new IOException("Oversized streamed request closed.");
                }
                throw error;
            }
            catch (RuntimeException error) { throw new BridgeException("invalid_request", "Expected a JSON request object."); }
            id = JsonData.string(request, "requestId");
            if (id.isEmpty() || id.length() > 128) throw new BridgeException("invalid_request", "requestId must contain 1 through 128 characters.");
            if (!cancel && !permit)
                throw new BridgeException("busy", "At most two game requests may wait at once; retry later with a new requestId.");
            if (cancel) {
                JsonObject data = new JsonObject();
                data.addProperty("status", registry.cancel(id));
                response = success(id, data);
            } else {
                operation = JsonData.string(request, "operation");
                JsonObject args;
                try { args = request.has("args") ? request.getAsJsonObject("args") : new JsonObject(); }
                catch (RuntimeException error) { throw new BridgeException("invalid_request", "args must be an object."); }
                if (operation.length() > 80 || args == null) throw new BridgeException("invalid_request", "operation is too long or args is not an object.");
                GameQueue.Control control = registry.register(id, operation);
                current.set(control);
                if (expired.get()) control.cancelBeforeExecution();
                args = bridge.prepareOperation(operation, args, control);
                if (PlayerNavigation.OPERATION.equals(operation)) {
                    PlayerNavigation.Request navigationRequest = new PlayerNavigation.Request(args);
                    // Request validation permits long interaction goals as well as segmented routes.
                    if (navigationRequest.timeout > 6500) {
                        ScheduledFuture<?> old = watchdogTicket.get();
                        ScheduledFuture<?> extended = watchdog.schedule(() -> {
                            expired.set(true); GameQueue.Control active = current.get();
                            if (active != null) active.cancelBeforeExecution(); exchange.close();
                        }, navigationWatchdogMillis(navigationRequest), TimeUnit.MILLISECONDS);
                        watchdogTicket.set(extended); old.cancel(false);
                    }
                }
                if (request.has("includeContext")) guarded = GuardedContext.parse(operation, request.get("includeContext"));
                dispatchStarted = true;
                if (guarded == null) response = success(id, bridge.dispatch(operation, args, control));
                else {
                    GuardedContext.Result result = bridge.dispatchGuarded(operation, args, control, guarded);
                    response = success(id, result.data); response.add("context", result.context);
                }
                registry.complete(id, control);
            }
        } catch (BridgeException error) {
            if (!"timeout".equals(error.code)) registry.complete(id, current.get());
            response = failure(id, error);
        } catch (ExecutionException error) {
            registry.complete(id, current.get());
            Throwable cause = error.getCause();
            response = cause instanceof BridgeException
                    ? failure(id, (BridgeException) cause)
                    : failure(id, "internal_error", "Game operation failed: " + cause.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            response = failure(id, "cancelled", "HTTP request was interrupted.");
        } catch (RuntimeException error) {
            response = failure(id, "internal_error", "Request processing failed.");
        } finally { if (permit) activeCalls.release(); }
        if (OwnedBlockReplacement.OPERATION.equals(operation) && !response.get("ok").getAsBoolean() && !response.getAsJsonObject("error").has("stage"))
            addReplacementFailureState(response.getAsJsonObject("error"), false);
        if (guarded != null && !response.get("ok").getAsBoolean()) response.getAsJsonObject("error").addProperty("outcome_unknown", guarded.outcomeUnknown());
        if (!dispatchStarted && !response.get("ok").getAsBoolean()) {
            response.getAsJsonObject("error").addProperty("original_action_executed", false);
            response.getAsJsonObject("error").addProperty("outcome_unknown", false);
        }
        byte[] bytes = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        int maximum = "client.screenshot".equals(operation) ? 12 * 1024 * 1024 : 1024 * 1024;
        if (bytes.length > maximum) {
            boolean replacementCompleted = OwnedBlockReplacement.OPERATION.equals(operation) && response.get("ok").getAsBoolean();
            response = failure(id, "result_too_large", "Result exceeded the bounded response limit.");
            if (OwnedBlockReplacement.OPERATION.equals(operation)) addReplacementFailureState(response.getAsJsonObject("error"), replacementCompleted);
            if (guarded != null) response.getAsJsonObject("error").addProperty("outcome_unknown", guarded.outcomeUnknown());
            bytes = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        timingHeaders(exchange.getResponseHeaders(), Math.max(0, System.nanoTime() - handlerStarted), current.get());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
        exchange.getResponseBody().close();
        return response.get("ok").getAsBoolean();
    }

    @com.google.common.annotations.VisibleForTesting
    public static long navigationWatchdogMillis(PlayerNavigation.Request request) {
        return Math.max(10000L, request.timeout + 1500L);
    }

    /** Fixed optional telemetry only; JSON action/context envelopes and response delivery are unchanged. */
    @com.google.common.annotations.VisibleForTesting
    public static void timingHeaders(com.sun.net.httpserver.Headers headers, long elapsed, GameQueue.Control control) {
        String[] fields = { "Submitted-Tickets", "Started-Tickets", "Finished-Tickets", "Cancelled-Before-Tickets",
            "Queue-Ns", "Callback-Wall-Ns", "Queue-Await-Calls", "Queue-Await-Wall-Ns", "Native-Await-Calls", "Native-Await-Wall-Ns" };
        long[] values = control == null ? new long[fields.length] : control.timingSnapshot();
        headers.set("X-MCB-Timing-Version", "1");
        headers.set("X-MCB-Timing-Http-Wall-Ns", Long.toString(Math.max(0, elapsed)));
        for (int i = 0; i < fields.length; i++) headers.set("X-MCB-Timing-" + fields[i], Long.toString(values[i]));
    }

    private String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new BridgeException("request_too_large", "Request body exceeded 64 KiB.");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JsonObject success(String id, JsonObject data) {
        JsonObject result = new JsonObject();
        result.addProperty("requestId", id);
        result.addProperty("ok", true);
        result.add("data", data);
        return result;
    }

    private static void addReplacementFailureState(JsonObject error, boolean completed) {
        error.addProperty("stage", completed ? "completed" : "prepare"); error.addProperty("nativeDigSubmitted", completed); error.addProperty("nativePlaceSubmitted", completed);
        error.addProperty("digConfirmed", completed); error.addProperty("placeConfirmed", completed); error.addProperty("outcome_unknown", completed);
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject failure(String id, BridgeException failure) {
        JsonObject response = failure(id, failure.code, failure.getMessage());
        if (failure instanceof OwnedBlockReplacement.Failure)
            for (java.util.Map.Entry<String, JsonElement> entry : ((OwnedBlockReplacement.Failure) failure).details.entrySet())
                response.getAsJsonObject("error").add(entry.getKey(), entry.getValue());
        if (failure instanceof SurvivalConstructionAdapter.Failure)
            for (java.util.Map.Entry<String, JsonElement> entry : ((SurvivalConstructionAdapter.Failure) failure).details.entrySet())
                response.getAsJsonObject("error").add(entry.getKey(), entry.getValue());
        if (failure instanceof WholeStackTransfer.Failure)
            for (java.util.Map.Entry<String, JsonElement> entry : ((WholeStackTransfer.Failure) failure).details.entrySet())
                response.getAsJsonObject("error").add(entry.getKey(), entry.getValue());
        return response;
    }

    static JsonObject failure(String id, String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("requestId", id);
        result.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        result.add("error", error);
        return result;
    }
}
