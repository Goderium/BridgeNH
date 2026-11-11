package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.Loader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/** Optional TST-QA instances use the authoritative personal world through one public ABI. */
public final class PrivateDimensionQaAdapter {
    static final String SOURCE = "tstqa_public_instance_adapter";
    static final String PROVIDER = "me.eigenraven.personalspace.world.PersonalWorldProvider";
    static final String RUNNER = "io.github.goderium.tstqa.api.PrivateDimensionRunner";
    private static final int MAX_RESPONSE = 262144;
    static final int MAX_STATUS_WAIT_MILLIS = 8000;

    public static JsonObject contract() {
        JsonObject result = new JsonObject();
        result.addProperty("source", SOURCE);
        result.addProperty("publicClass", RUNNER);
        result.addProperty("publicMethod", "perform(WorldServer,String,String,String)");
        result.addProperty("providerClass", PROVIDER);
        result.addProperty("requiresActualLoadedServerWorld", true);
        result.addProperty("availabilitySource", "qa.catalog capabilities in the actual current world");
        result.addProperty("nativeOverworldRoutePreserved", true);
        result.addProperty("nativeRunOwned", false);
        result.addProperty("boundedStatusWait", completionSupported());
        result.addProperty("maxStatusWaitMillis", MAX_STATUS_WAIT_MILLIS);
        result.addProperty("statusWaitPrivateOnly", true);
        result.addProperty("publicCompletionMethod", "completion(WorldServer,String,String):CompletableFuture<String>");
        result.addProperty("privateCancelSemantics", "Own-run ordinary stop request; normal drain, recovery and stability continue.");
        result.addProperty("privateForgetSemantics", "qa.command forget with one explicit completed owned run UUID releases only adapter history and chunk tickets; world blocks are preserved.");
        return result;
    }

    public static int waitMillis(JsonObject args) {
        return args.has("waitMillis") ? JsonData.integer(args, "waitMillis", 0, MAX_STATUS_WAIT_MILLIS) : 0;
    }

    /** Capability discovery checks linkage only; it never reads a world or the runner's mutable runs. */
    private static Method completionApi() throws ReflectiveOperationException {
        Class<?> runner = Class.forName(RUNNER, false, PrivateDimensionQaAdapter.class.getClassLoader());
        Method api = runner.getMethod("completion", WorldServer.class, String.class, String.class);
        if (!Modifier.isPublic(runner.getModifiers()) || !Modifier.isPublic(api.getModifiers())
                || !Modifier.isStatic(api.getModifiers()) || api.getReturnType() != CompletableFuture.class) throw unavailable();
        return api;
    }

    static boolean completionSupported() {
        if (!Loader.isModLoaded("tstqa")) return false;
        try { completionApi(); return true; }
        catch (ReflectiveOperationException | LinkageError | BridgeException error) { return false; }
    }

    /** The owning server thread registers once, then immediately returns the pure completion handle. */
    public static CompletableFuture<?> completion(EntityPlayerMP player, String sessionId, String runId) {
        if (runId.isEmpty()) throw new BridgeException("invalid_args", "Status waiting requires one explicit owned adapter run ID.");
        if (!(player.worldObj instanceof WorldServer) || player.worldObj.isRemote || player.worldObj.provider == null)
            throw new BridgeException("world_unavailable", "The authoritative QA server world is unavailable.");
        WorldServer world = (WorldServer) player.worldObj;
        requireWorld(world.provider.dimensionId, player.dimension, world.provider.getClass().getName(),
                DimensionManager.isDimensionRegistered(player.dimension) && DimensionManager.getWorld(player.dimension) == world);
        if (!Loader.isModLoaded("tstqa")) throw unavailable();
        try {
            Object future = completionApi().invoke(null, world, sessionId, runId);
            if (!(future instanceof CompletableFuture)) throw incomplete();
            return (CompletableFuture<?>) future;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            throw new BridgeException("qa_context_changed", "Completion registration refused this actual world, session or run.");
        } catch (ReflectiveOperationException | LinkageError error) { throw unavailable(); }
    }

    /** HTTP worker only. A reader timeout or interruption never cancels the shared run or future. */
    public static String awaitCompletion(CompletableFuture<?> future, long millis) throws InterruptedException {
        if (millis <= 0) return null;
        try {
            Object value = future.get(millis, TimeUnit.MILLISECONDS);
            if (!(value instanceof String) || ((String) value).length() > MAX_RESPONSE) throw incomplete();
            return (String) value;
        } catch (TimeoutException error) { return null; }
        catch (ExecutionException | java.util.concurrent.CancellationException error) {
            throw new BridgeException("qa_context_changed", "The completion source was invalidated; inspect the client before another read.");
        }
    }

    public static void requireStatusRun(JsonObject result, String runId, boolean terminal) {
        if (!string(result, "runId", runId) || !booleanValue(result, "ok", true)
                || !booleanValue(result, "adapterRunOwned", true)) throw incomplete();
        if (terminal && (!booleanValue(result, "nativeBatchActive", false) || !string(result, "state", "completed")
                || !result.has("nativeReport") || !result.get("nativeReport").isJsonObject()
                || !booleanValue(result.getAsJsonObject("nativeReport"), "complete", true))) throw incomplete();
    }

    private static boolean booleanValue(JsonObject value, String key, boolean expected) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isBoolean()
                && value.get(key).getAsBoolean() == expected;
    }

    static JsonObject perform(String operation, MinecraftServer server, EntityPlayerMP player,
            String sessionId, JsonObject args, JsonObject policy) {
        if (!(player.worldObj instanceof WorldServer) || player.worldObj.isRemote || player.worldObj.provider == null)
            throw new BridgeException("world_unavailable", "The authoritative QA server world is unavailable.");
        WorldServer world = (WorldServer) player.worldObj;
        int dimension = world.provider.dimensionId;
        String provider = world.provider.getClass().getName();
        requireWorld(dimension, player.dimension, provider, DimensionManager.isDimensionRegistered(dimension)
                && DimensionManager.getWorld(dimension) == world);
        if (!Loader.isModLoaded("tstqa"))
            throw new BridgeException("qa_api_unavailable", "The public private-dimension runner requires TST-QA.");

        JsonObject request = new JsonParser().parse(args.toString()).getAsJsonObject();
        String method = route(operation, request);
        request.remove("policyLevel");
        request.addProperty("policyLevel", JsonData.string(policy, "level"));
        final Class<?> runner;
        try { runner = Class.forName(RUNNER, false, PrivateDimensionQaAdapter.class.getClassLoader()); }
        catch (ClassNotFoundException error) { throw unavailable(); }
        final Object raw;
        try {
            Method api = runner.getMethod("perform", WorldServer.class, String.class, String.class, String.class);
            if (!Modifier.isPublic(runner.getModifiers()) || !Modifier.isPublic(api.getModifiers())
                    || !Modifier.isStatic(api.getModifiers()) || api.getReturnType() != String.class) throw unavailable();
            if ("run".equals(method) && QaPolicy.rank(JsonData.string(policy, "level")) < 3) {
                JsonObject capabilities = response(api.invoke(null, world, "capabilities", sessionId, "{}"), sessionId, dimension, provider);
                if (!capabilities.has("controlledPolicyContract") || !QaPolicy.CONTROLLED_CONTRACT.equals(capabilities.get("controlledPolicyContract").getAsString()))
                    throw new BridgeException("qa_policy_unavailable", "Installed QA adapter does not advertise reviewed controlled-test enforcement.");
                request.addProperty("exact", true);
            }
            raw = api.invoke(null, world, method, sessionId, request.toString());
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            throw new BridgeException("qa_failed", "The public private-dimension runner failed: " + cause.getClass().getSimpleName() + ".");
        } catch (ReflectiveOperationException | LinkageError error) { throw unavailable(); }

        JsonObject result = response(raw, sessionId, dimension, provider);
        JsonObject context = new JsonObject();
        context.addProperty("name", JsonData.bounded(HorizonQaAdapter.serverWorldName(server), 128));
        context.addProperty("folder", JsonData.bounded(server.getFolderName(), 128));
        context.addProperty("dimension", dimension);
        context.addProperty("providerClass", provider);
        result.add("world", context);
        return result;
    }

    static void requireWorld(int dimension, int playerDimension, String provider, boolean sameLoadedWorld) {
        if (dimension == 0 || dimension != playerDimension || !sameLoadedWorld)
            throw new BridgeException("qa_context_changed", "QA requires the exact current loaded authoritative player world.");
        if (!PROVIDER.equals(provider))
            throw new BridgeException("unsupported", "Non-overworld QA is limited to the explicit PersonalSpace provider.");
    }

    @com.google.common.annotations.VisibleForTesting
    public static String route(String operation, JsonObject request) {
        if ("qa.catalog".equals(operation)) {
            String section = CatalogData.text(request, "section", 32, "tests");
            if ("capabilities".equals(section)) return "capabilities";
            if ("fixture_plan".equals(section)) {
                request.addProperty("selector", CatalogData.text(request, "query", 256, ""));
                return "fixturePlan";
            }
            return "catalog";
        }
        if ("qa.run".equals(operation)) return "run";
        if ("qa.status".equals(operation)) return "status";
        if ("qa.results".equals(operation)) return "results";
        if ("qa.cancel".equals(operation)) {
            if (CatalogData.text(request, "runId", 128, "").isEmpty())
                throw new BridgeException("invalid_args", "Private-dimension stop requires one explicit owned run ID.");
            return "stop";
        }
        if ("qa.command".equals(operation)) {
            String command = CatalogData.text(request, "command", 32, null);
            List<String> arguments = HorizonQaAdapter.arguments(request);
            if ("run".equals(command)) {
                if (arguments.size() != 1)
                    throw new BridgeException("invalid_args", "Private-dimension run requires one exact test ID.");
                request.addProperty("selector", arguments.get(0));
                request.addProperty("exact", true);
                return "run";
            }
            if ("clearall".equals(command) && arguments.isEmpty()) return "clear";
            if ("forget".equals(command) || "clear".equals(command)) {
                if (arguments.size() != 1)
                    throw new BridgeException("invalid_args", "Private-dimension clear/forget requires one explicit completed owned run UUID.");
                String runId = arguments.get(0);
                try {
                    if (!UUID.fromString(runId).toString().equals(runId)) throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) {
                    throw new BridgeException("invalid_args", "Private-dimension clear/forget requires a canonical UUID, never the latest run implicitly.");
                }
                request.addProperty("runId", runId);
                return command;
            }
            throw new BridgeException("unsupported", "The public private-dimension adapter supports exact run, owned-fixture clear and explicit completed-run forget commands.");
        }
        throw new BridgeException("unsupported", "This operation is not supported by the public private-dimension adapter.");
    }

    public static JsonObject response(Object raw, String sessionId, int dimension, String provider) {
        if (!(raw instanceof String) || ((String) raw).length() > MAX_RESPONSE)
            throw incomplete();
        final JsonElement parsed;
        try { parsed = new JsonParser().parse((String) raw); }
        catch (JsonParseException error) { throw incomplete(); }
        if (parsed == null || !parsed.isJsonObject()) throw incomplete();
        JsonObject result = parsed.getAsJsonObject();
        if (!string(result, "source", SOURCE) || !string(result, "sessionId", sessionId)
                || !string(result, "providerClass", provider) || !number(result, "dimension", dimension)
                || !result.has("ok") || !result.get("ok").isJsonPrimitive()
                || !result.get("ok").getAsJsonPrimitive().isBoolean()) throw incomplete();
        if (result.has("available") && (!result.get("available").isJsonPrimitive()
                || !result.get("available").getAsJsonPrimitive().isBoolean())) throw incomplete();
        if (result.has("world")) {
            if (!result.get("world").isJsonObject()) throw incomplete();
            JsonObject nested = result.getAsJsonObject("world");
            if (!number(nested, "dimension", dimension) || !string(nested, "providerClass", provider)) throw incomplete();
        }
        if (!result.get("ok").getAsBoolean() || !result.has("available"))
            result.addProperty("available", result.get("ok").getAsBoolean());
        return result;
    }

    private static boolean string(JsonObject value, String key, String expected) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isString()
                && expected.equals(value.get(key).getAsString());
    }

    private static boolean number(JsonObject value, String key, int expected) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isNumber()
                && Integer.toString(expected).equals(value.get(key).getAsString());
    }

    private static BridgeException unavailable() {
        return new BridgeException("qa_api_unavailable", "TST-QA does not expose the required public private-dimension runner ABI.");
    }

    private static BridgeException incomplete() {
        return new BridgeException("qa_context_changed", "The bounded public runner response does not match this source, world, provider and session.");
    }
}
