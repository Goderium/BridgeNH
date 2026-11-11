package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.network.BridgeHttpServer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real authenticated HTTP after an owned loopback port collision, plus bounded startup policy. */
public final class LoopbackPortSelfTest {
    private static int checks;

    public static int run() throws Exception {
        checks = 0;
        policy();
        occupiedPort();
        return checks;
    }

    private static void policy() throws Exception {
        List<Integer> attempts = new ArrayList<Integer>();
        LoopbackPortPolicy.start(25671, attempts::add);
        check(attempts.equals(Arrays.asList(25671)), "A successful configured port is used once without fallback");

        attempts.clear();
        IOException failure = new IOException("Non-bind fixture failure", new BindException("Nested bind error"));
        try {
            LoopbackPortPolicy.start(25671, port -> { attempts.add(port); throw failure; });
            throw new AssertionError("Non-bind startup failure was swallowed");
        } catch (IOException expected) {
            check(expected == failure && attempts.equals(Arrays.asList(25671)), "Non-bind IOException propagates unchanged without trying another port");
        }

        attempts.clear();
        IOException fallbackFailure = new IOException("OS-allocated port fixture failure");
        try {
            LoopbackPortPolicy.start(25671, port -> {
                attempts.add(port);
                if (attempts.size() == 1) throw new BindException("Owned port collision");
                throw fallbackFailure;
            });
            throw new AssertionError("Fallback startup failure was swallowed");
        } catch (IOException expected) {
            check(expected == fallbackFailure && attempts.equals(Arrays.asList(25671, 0)), "A collision has only one fallback and fallback failure propagates unchanged");
        }

        attempts.clear();
        BindException secondCollision = new BindException("Fallback collision");
        try {
            LoopbackPortPolicy.start(25671, port -> {
                attempts.add(port);
                if (attempts.size() == 1) throw new BindException("Configured port collision");
                throw secondCollision;
            });
            throw new AssertionError("Second bind failure was swallowed");
        } catch (BindException expected) {
            check(expected == secondCollision && attempts.equals(Arrays.asList(25671, 0)), "A fallback BindException cannot cause a retry loop");
        }
    }

    private static void occupiedPort() throws Exception {
        String token = UUID.randomUUID().toString();
        Fixture bridge = new Fixture();
        CountingServer server = new CountingServer(bridge, token);
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int configuredPort = occupied.getLocalPort();
            LoopbackPortPolicy.start(configuredPort, server::start);
            int actualPort = server.port();
            check(server.attempts.equals(Arrays.asList(configuredPort, 0)), "The same HTTP server retries its normal start method with port zero");
            check(actualPort > 0 && actualPort != configuredPort, "The service exposes the actual distinct OS-allocated port");
            check(!occupied.isClosed() && occupied.isBound(), "Fallback leaves the occupied fixture socket untouched");

            Reply absent = post(actualPort, null);
            check(absent.status == 401 && !absent.body.get("ok").getAsBoolean()
                    && "unauthorized".equals(absent.body.getAsJsonObject("error").get("code").getAsString()), "The fallback port still rejects a missing token");
            Reply wrong = post(actualPort, "Bearer previous-session-token");
            check(wrong.status == 401 && !wrong.body.get("ok").getAsBoolean()
                    && "unauthorized".equals(wrong.body.getAsJsonObject("error").get("code").getAsString()), "The fallback port still rejects another session token");
            check(bridge.calls.get() == 0, "Unauthenticated fallback requests never reach bridge dispatch");

            Reply accepted = post(actualPort, "Bearer " + token);
            check(accepted.status == 200 && accepted.body.get("ok").getAsBoolean()
                    && accepted.body.getAsJsonObject("data").get("fixture").getAsBoolean(), "The original session token authorizes real HTTP on the actual fallback port");
            check(bridge.calls.get() == 1, "The authenticated request reaches the original bridge exactly once");
        } finally {
            server.stop();
        }
    }

    private static Reply post(int port, String authorization) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("requestId", UUID.randomUUID().toString());
        request.addProperty("operation", "fixture.port");
        request.add("args", new JsonObject());
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/call").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(4000);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[2048];
                int count;
                while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
                return new Reply(status, new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static final class Reply {
        final int status;
        final JsonObject body;
        Reply(int status, JsonObject body) { this.status = status; this.body = body; }
    }

    private static final class Fixture extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
        final AtomicInteger calls = new AtomicInteger();
        @Override public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) {
            calls.incrementAndGet();
            JsonObject data = new JsonObject();
            data.addProperty("fixture", true);
            return data;
        }
    }

    private static final class CountingServer extends BridgeHttpServer {
        final List<Integer> attempts = new ArrayList<Integer>();
        CountingServer(GameBridge bridge, String token) { super(bridge, token); }
        @Override public void start(int port) throws IOException {
            attempts.add(port);
            super.start(port);
        }
    }
}
