package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.discovery.ASMDataTable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;

/** Optional, fixed public API surface; no Horizon-QA classes enter the bridge's linkage graph. */
public final class HorizonQaAdapter {
    private static final String ROOT = "com.gtnewhorizons.horizonqa.";
    private static final String[] COMMANDS = {"run", "runall", "runfailed", "tp", "runthis", "runthat", "pos", "clearall", "load", "export", "clear", "label", "labels"};
    private static ASMDataTable asmData;
    private static volatile CatalogState catalogState = new CatalogState(null, null, "server_not_ready", "");
    private final QaRunTracker runs = new QaRunTracker();
    private volatile long invalidation;
    private Object observedReport;
    private boolean observedBatchActive;

    public static void preInit(ASMDataTable data) { asmData = data; }

    public static void serverStarted(MinecraftServer server) {
        if (!Loader.isModLoaded("horizonqa")) {
            catalogState = new CatalogState(server, null, "mod_not_loaded", "");
            return;
        }
        try {
            String mode = string(callStatic("HorizonQAProperties", "modeName"));
            if (Boolean.TRUE.equals(callStatic("HorizonQAProperties", "isOff"))) {
                catalogState = new CatalogState(server, null, "mode_off", mode);
                return;
            }
            Object catalog = invoke(type("internal.GameTestRegistry"), null, "discoverTests", new Class<?>[] {ASMDataTable.class}, asmData);
            catalogState = new CatalogState(server, catalog, "", mode);
        } catch (BridgeException | LinkageError error) {
            catalogState = new CatalogState(server, null, "public_api_unavailable", "");
        }
    }

    public static void serverStopped() { catalogState = new CatalogState(null, null, "server_not_ready", ""); }

    public void invalidate() { invalidation++; }

    /** Tick sampling is diagnostic only: Horizon-QA publishes after FML END event subscribers. */
    public void tick(MinecraftServer server) {
        bind(server);
        if (runs.latest() == null || !ready(server)) return;
        try { observe(); } catch (BridgeException | LinkageError ignored) { }
    }

    public JsonObject perform(String operation, MinecraftServer server, EntityPlayerMP player, String sessionId, JsonObject args, JsonObject policy) {
        bind(server);
        if (!Loader.isModLoaded("horizonqa")) return unavailable(base(server, sessionId), "mod_not_loaded");
        if (player != null && (player.dimension != 0 || player.worldObj != null
                && player.worldObj.provider != null && player.worldObj.provider.dimensionId != 0)) {
            try { return PrivateDimensionQaAdapter.perform(operation, server, player, sessionId, args, policy); }
            catch (BridgeException error) {
                if (!"qa_api_unavailable".equals(error.code)) throw error;
                JsonObject missing = unavailable(base(server, sessionId), Loader.isModLoaded("tstqa") ? "public_api_unavailable" : "private_adapter_not_loaded");
                missing.getAsJsonObject("world").addProperty("dimension", player.dimension); return missing;
            } catch (LinkageError error) {
                JsonObject missing = unavailable(base(server, sessionId), "public_api_unavailable");
                missing.getAsJsonObject("world").addProperty("dimension", player.dimension); return missing;
            }
        }
        JsonObject result = base(server, sessionId);
        if (!ready(server)) return unavailable(result, catalogState.server == server ? catalogState.reason : "catalog_not_ready");
        QaPolicy.requireNativeScope(operation, args, policy);
        try {
            observe();
            switch (operation) {
                case "qa.catalog": return catalog(server, player, args, result);
                case "qa.run": return run(server, args, result, false);
                case "qa.status": return status(args, result);
                case "qa.results": return results(args, result);
                case "qa.command": return command(server, player, args, result);
                case "qa.cancel":
                    QaRunTracker.Ticket ticket = runs.find(runId(args));
                    result.addProperty("runId", ticket.id);
                    result.addProperty("submitted", false);
                    result.addProperty("cancelled", false);
                    return unavailable(result, "public_api_unavailable");
                default: throw new BridgeException("unsupported", "Unknown Horizon-QA operation.");
            }
        } catch (BridgeException error) {
            if ("qa_api_unavailable".equals(error.code)) return unavailable(result, "public_api_unavailable");
            throw error;
        } catch (LinkageError error) {
            return unavailable(result, "public_api_unavailable");
        }
    }

    private void bind(MinecraftServer server) {
        runs.bind(server, server == null ? null : DimensionManager.getWorld(0), catalogState, invalidation);
    }

    private static boolean ready(MinecraftServer server) { return server != null && catalogState.server == server && catalogState.catalog != null; }

    private void observe() {
        observedReport = callStatic("internal.ReportedRun", "lastResult");
        observedBatchActive = Boolean.TRUE.equals(callStatic("internal.GameTestRunner", "isBatchActive"));
    }

    /** MinecraftServer.getWorldName is CLIENT-only and stripped by Forge on dedicated servers. */
    public static String serverWorldName(MinecraftServer server) {
        net.minecraft.world.WorldServer world = DimensionManager.getWorld(0);
        if (world != null && world.getWorldInfo() != null) return world.getWorldInfo().getWorldName();
        return server.getFolderName();
    }

    private static JsonObject base(MinecraftServer server, String sessionId) {
        JsonObject result = new JsonObject();
        result.addProperty("available", true);
        result.addProperty("source", "horizonqa_public_api");
        result.addProperty("sessionId", sessionId);
        result.addProperty("mode", catalogState.mode);
        JsonObject world = new JsonObject();
        world.addProperty("folder", JsonData.bounded(server.getFolderName(), 128));
        world.addProperty("name", JsonData.bounded(serverWorldName(server), 128));
        world.addProperty("dimension", 0);
        result.add("world", world);
        return result;
    }

    private static JsonObject unavailable(JsonObject result, String reason) {
        result.addProperty("available", false);
        result.addProperty("reason", reason);
        if ("public_api_unavailable".equals(reason))
            result.addProperty("detail", "Horizon-QA does not expose the required public API; private state is not read.");
        return result;
    }

    private JsonObject catalog(MinecraftServer server, EntityPlayerMP player, JsonObject args, JsonObject result) {
        String section = CatalogData.text(args, "section", 32, "tests");
        String query = CatalogData.text(args, "query", 128, "");
        List<JsonObject> entries = new ArrayList<JsonObject>();
        if ("tests".equals(section)) {
            for (Object definition : list(call(catalogState.catalog, "tests"))) {
                JsonObject item = definition(definition);
                if (CatalogData.matches(query, item.get("id").getAsString(), item.get("holder").getAsString(), item.get("template").getAsString(), item.get("batch").getAsString())) entries.add(item);
            }
        } else if ("selectors".equals(section)) {
            for (Object selector : list(call(catalogState.catalog, "selectorCandidates"))) {
                if (!CatalogData.matches(query, string(selector))) continue;
                JsonObject item = new JsonObject(); item.addProperty("selector", JsonData.bounded(string(selector), 512)); entries.add(item);
            }
        } else if ("templates".equals(section)) {
            ICommand command = nativeCommand(server);
            if (command == null) return unavailable(result, "command_not_registered");
            List<?> names = command.addTabCompletionOptions(player, new String[] {"load", ""});
            result.addProperty("coverage", "native_command_completion");
            result.addProperty("boundsAvailable", false);
            result.addProperty("boundsUnavailableReason", "public_api_unavailable");
            result.addProperty("boundsDetail", "Native completion supplies names; template sizes and final cleared regions are not included.");
            if (names != null) for (Object name : names) {
                if (!CatalogData.matches(query, string(name))) continue;
                JsonObject item = new JsonObject(); item.addProperty("template", JsonData.bounded(string(name), 512)); entries.add(item);
            }
        } else if ("fixture_plan".equals(section)) {
            JsonObject plan = fixturePlan(query);
            result.add("publicFixturePlan", plan);
            entries.add(plan);
        } else if ("flow_evidence".equals(section)) {
            JsonObject evidence = flowEvidence(query);
            result.add("flowEvidence", evidence);
            entries.add(evidence);
        } else if ("diagnostics".equals(section)) {
            Object diagnostics = call(catalogState.catalog, "diagnostics");
            for (Object issue : list(call(diagnostics, "issues"))) entries.add(discoveryIssue(issue, "discovery"));
            for (Object invalid : list(call(diagnostics, "invalidTests"))) {
                String id = string(call(invalid, "intendedTestId"));
                for (Object issue : list(call(invalid, "issues"))) { JsonObject item = discoveryIssue(issue, "invalid_test"); item.addProperty("testId", JsonData.bounded(id, 512)); entries.add(item); }
            }
            for (Object invalid : list(call(diagnostics, "invalidHooks"))) {
                for (Object issue : list(call(invalid, "issues"))) {
                    JsonObject item = discoveryIssue(issue, "invalid_hook");
                    item.addProperty("batch", JsonData.bounded(string(call(invalid, "batch")), 256));
                    item.addProperty("phase", string(call(invalid, "phase"))); entries.add(item);
                }
            }
            for (Object duplicate : list(call(diagnostics, "duplicateIds"))) {
                JsonObject item = new JsonObject(); item.addProperty("category", "duplicate_id");
                item.addProperty("testId", JsonData.bounded(string(call(duplicate, "testId")), 512));
                item.addProperty("kind", "DUPLICATE_TEST_ID"); entries.add(item);
            }
        } else if ("capabilities".equals(section)) {
            result.add("runnerConfig", runnerConfig(HorizonQaAdapter::callStatic));
            result.add("reportedRunScope", reportedRunScope());
            result.add("gtFastForwardScope", gtFastForwardScope(hasPublicFastForward()));
            boolean commandPresent = nativeCommand(server) != null;
            for (String command : COMMANDS) {
                JsonObject capability = new JsonObject(); capability.addProperty("command", command);
                capability.addProperty("available", commandPresent);
                capability.addProperty("execution", "run".equals(command) || "runall".equals(command) ? "bridge_reported_run" : "native_command");
                if (!commandPresent) capability.addProperty("reason", "command_not_registered");
                entries.add(capability);
            }
            JsonObject cancel = new JsonObject(); cancel.addProperty("operation", "qa.cancel"); cancel.addProperty("available", false); cancel.addProperty("reason", "public_api_unavailable"); entries.add(cancel);
            JsonObject correlation = new JsonObject(); correlation.addProperty("capability", "asynchronous_run_correlation"); correlation.addProperty("available", false); correlation.addProperty("reason", "public_api_unavailable"); entries.add(correlation);
        } else throw new BridgeException("invalid_args", "section must be tests, selectors, templates, diagnostics, capabilities, fixture_plan or flow_evidence.");
        return page(result, section, entries, args, 64, 32);
    }

    /** Singleton geometry uses the installed public template, with the audited native allocation contract. */
    private JsonObject fixturePlan(String testId) {
        if (testId.isEmpty() || "all".equals(testId)) throw new BridgeException("invalid_args", "fixture_plan requires one exact catalog test ID.");
        Object definition = invoke(catalogState.catalog.getClass(), catalogState.catalog, "findTest", new Class<?>[] {String.class}, testId);
        if (definition == null || !testId.equals(string(call(definition, "getTestId"))))
            throw new BridgeException("qa_test_unknown", "The exact test ID is absent from the current public catalog.");
        if (Boolean.TRUE.equals(call(definition, "isParameterized")))
            throw new BridgeException("unsupported", "Parameterized fixture allocation is not covered by the singleton plan.");
        if (Loader.instance().getIndexedModList().get("horizonqa") == null
                || !"0.15.0".equals(Loader.instance().getIndexedModList().get("horizonqa").getVersion()))
            throw new BridgeException("unsupported", "The native singleton allocation contract requires Horizon-QA 0.15.0.");
        int rotation = ((Number) call(definition, "getRotation")).intValue();
        String templateName = string(call(definition, "getTemplateName"));
        int sizeX = 5, sizeY = 5, sizeZ = 5;
        if (!templateName.isEmpty()) {
            Class<?> templateType = type("structure.HybridStructureTemplate");
            Object template = invoke(type("structure.HybridStructureLoader"), null, "load", new Class<?>[] {String.class}, templateName);
            sizeX = ((Number) invoke(type("structure.StructurePlacer"), null, "placedSizeX", new Class<?>[] {templateType, int.class}, template, rotation)).intValue();
            sizeY = ((Number) call(template, "getSizeY")).intValue();
            sizeZ = ((Number) invoke(type("structure.StructurePlacer"), null, "placedSizeZ", new Class<?>[] {templateType, int.class}, template, rotation)).intValue();
        }
        int originX = ((Number) callStatic("HorizonQAProperties", "gridOriginX")).intValue();
        int originY = ((Number) callStatic("HorizonQAProperties", "gridOriginY")).intValue();
        int originZ = ((Number) callStatic("HorizonQAProperties", "gridOriginZ")).intValue();
        int cellX = Math.max(5, sizeX), cellY = Math.max(5, sizeY), cellZ = Math.max(5, sizeZ);
        long minX = (long) originX - 3, minY = (long) originY - 3, minZ = (long) originZ - 3;
        long maxX = (long) originX + cellX + 2, maxY = (long) originY + cellY + 2, maxZ = (long) originZ + cellZ + 2;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || minY < 0 || maxY > 255
                || minX < -30000000 || minZ < -30000000 || maxX > 29999999 || maxZ > 29999999)
            throw new BridgeException("unsupported", "The full native singleton cell and clearing margin must fit inside supported world bounds.");
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("available", true);
        result.addProperty("source", "horizonqa_public_template_loader");
        result.addProperty("geometry", "exact_native_clear_bounds");
        result.addProperty("nativeRunnerContract", "horizonqa-0.15.0-singleton-cell5-margin3");
        result.addProperty("nativeBoundsPubliclyAvailable", false);
        result.addProperty("testId", testId);
        result.addProperty("template", templateName);
        result.addProperty("dimension", 0);
        result.addProperty("rotation", rotation);
        JsonObject origin = new JsonObject(); origin.addProperty("x", originX); origin.addProperty("y", originY); origin.addProperty("z", originZ);
        result.add("allocationOrigin", origin);
        JsonObject size = new JsonObject(); size.addProperty("x", sizeX); size.addProperty("y", sizeY); size.addProperty("z", sizeZ);
        result.add("placedSize", size);
        result.add("cellBounds", bounds(originX, originY, originZ, (long) originX + cellX - 1, (long) originY + cellY - 1, (long) originZ + cellZ - 1));
        result.add("clearBounds", bounds(minX, minY, minZ, maxX, maxY, maxZ));
        result.add("reservedBounds", bounds(minX, minY, minZ, maxX, maxY, maxZ));
        result.addProperty("requestedVolume", (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
        result.addProperty("complete", true);
        result.addProperty("clearEnvelopeComplete", true);
        result.addProperty("scope", "native_singleton_preparation_only");
        result.addProperty("callbackWritesBounded", false);
        result.addProperty("detail", "Installed public template dimensions and rotation plus the version-pinned native singleton clearing contract. Test callback writes require separate fixture evidence.");
        return result;
    }

    private static JsonObject bounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        JsonObject result = new JsonObject();
        result.addProperty("minX", minX); result.addProperty("minY", minY); result.addProperty("minZ", minZ);
        result.addProperty("maxX", maxX); result.addProperty("maxY", maxY); result.addProperty("maxZ", maxZ);
        return result;
    }

    /** Optional TST-QA evidence is an explicit public API and never upgrades native run correlation. */
    private static JsonObject flowEvidence(String testId) {
        if (testId.isEmpty() || "all".equals(testId)) throw new BridgeException("invalid_args", "flow_evidence requires one exact test ID.");
        if (!Loader.isModLoaded("tstqa")) throw apiUnavailable();
        final Class<?> evidenceType;
        try { evidenceType = Class.forName("io.github.goderium.tstqa.api.FlowEvidence", false, HorizonQaAdapter.class.getClassLoader()); }
        catch (ClassNotFoundException error) { throw apiUnavailable(); }
        Object value = invoke(evidenceType, null, "snapshot", new Class<?>[] {String.class}, testId);
        if (!(value instanceof String) || ((String) value).length() > 131072)
            throw new BridgeException("qa_evidence_incomplete", "The complete public TST-QA evidence exceeds the bounded response contract.");
        final JsonElement parsed;
        try { parsed = new JsonParser().parse((String) value); }
        catch (JsonParseException error) { throw new BridgeException("qa_evidence_incomplete", "The public TST-QA evidence is not valid JSON."); }
        if (parsed == null || !parsed.isJsonObject()) throw apiUnavailable();
        JsonObject result = parsed.getAsJsonObject();
        if (!result.has("schemaVersion") || !result.get("schemaVersion").isJsonPrimitive()
                || !result.get("schemaVersion").getAsJsonPrimitive().isNumber() || result.get("schemaVersion").getAsInt() != 1
                || !result.has("caseId") || !result.get("caseId").isJsonPrimitive()
                || !result.get("caseId").getAsJsonPrimitive().isString() || !testId.equals(result.get("caseId").getAsString()))
            throw new BridgeException("qa_evidence_incomplete", "The public TST-QA evidence version or exact case binding does not match.");
        return result;
    }

    /** Each fixed getter reports its own coverage; an older optional API may expose only some of these. */
    static JsonObject runnerConfig(PublicGetter reader) {
        String[][] getters = {
                {"gridOriginX", "HorizonQAProperties", "gridOriginX", "number"},
                {"gridOriginY", "HorizonQAProperties", "gridOriginY", "number"},
                {"gridOriginZ", "HorizonQAProperties", "gridOriginZ", "number"},
                {"mode", "HorizonQAProperties", "modeName", "string"},
                {"turboMultiplier", "HorizonQAProperties", "turboMultiplier", "number"},
                {"turboActive", "internal.GameTestRunner", "isTurboActive", "boolean"},
                {"nativeBatchActive", "internal.GameTestRunner", "isBatchActive", "boolean"}};
        JsonObject result = new JsonObject(); JsonObject coverage = new JsonObject(); int read = 0;
        for (String[] getter : getters) {
            JsonObject field = new JsonObject();
            field.addProperty("publicGetter", ROOT + getter[1] + "." + getter[2]);
            try {
                Object value = reader.read(getter[1], getter[2]);
                if ("number".equals(getter[3]) && value instanceof Number) result.addProperty(getter[0], (Number) value);
                else if ("boolean".equals(getter[3]) && value instanceof Boolean) result.addProperty(getter[0], (Boolean) value);
                else if ("string".equals(getter[3]) && value instanceof String) text(result, getter[0], value, 64);
                else throw apiUnavailable();
                field.addProperty("available", true); read++;
            } catch (BridgeException error) {
                field.addProperty("available", false);
                field.addProperty("reason", "qa_api_unavailable".equals(error.code) ? "public_api_unavailable" : error.code);
            } catch (LinkageError error) {
                field.addProperty("available", false); field.addProperty("reason", "public_api_unavailable");
            }
            coverage.add(getter[0], field);
        }
        result.addProperty("available", read > 0);
        result.addProperty("partial", read < getters.length);
        if (read == 0) result.addProperty("reason", "public_api_unavailable");
        result.add("coverage", coverage);
        return result;
    }

    static JsonObject reportedRunScope() {
        JsonObject result = new JsonObject();
        result.addProperty("coverage", "native_reported_runner_contract");
        result.addProperty("dimension", 0);
        result.addProperty("world", "overworld");
        result.addProperty("allocationOrigin", "runnerConfig.gridOriginX/Y/Z");
        result.addProperty("clearsFixtureCellsBeforePlacement", true);
        result.addProperty("clearIncludesCellMargin", true);
        result.addProperty("boundsAvailable", false);
        result.addProperty("boundsUnavailableReason", "public_api_unavailable");
        result.addProperty("boundsDetail", "Cells depend on selected templates, rotation and native allocation; the public reported-run API does not expose complete final clear bounds.");
        return result;
    }

    static JsonObject gtFastForwardScope(boolean supported) {
        JsonObject result = new JsonObject(); result.addProperty("available", supported);
        result.addProperty("publicMethod", ROOT + "api.gt.GTNHGameTestHelper.fastForwardTicks(int)");
        if (!supported) { result.addProperty("reason", "public_api_unavailable"); return result; }
        result.addProperty("coverage", "native_public_method_contract");
        result.addProperty("tileEntityScope", "IGregTechTileEntity_in_test_region");
        result.addProperty("advancesEUSupplyJobs", true);
        result.addProperty("advancesAEWorldTicks", false);
        result.addProperty("advancesWorldTime", false);
        result.addProperty("boundsAvailable", false);
        result.addProperty("boundsUnavailableReason", "public_api_unavailable");
        result.addProperty("detail", "GT fast-forward ticks GT tile entities and EU supply jobs in the test region; it does not tick AE tile entities or advance global world time.");
        return result;
    }

    private static boolean hasPublicFastForward() {
        try {
            Method method = type("api.gt.GTNHGameTestHelper").getMethod("fastForwardTicks", int.class);
            return Modifier.isPublic(method.getDeclaringClass().getModifiers());
        } catch (ReflectiveOperationException | BridgeException | LinkageError error) { return false; }
    }

    interface PublicGetter { Object read(String owner, String method); }

    private JsonObject run(MinecraftServer server, JsonObject args, JsonObject result, boolean exact) {
        if (observedBatchActive) throw new BridgeException("qa_busy", "Horizon-QA already has an active batch; no run was started.");
        if (Boolean.TRUE.equals(callStatic("HorizonQAProperties", "stopServerAfterRun")) || Boolean.TRUE.equals(callStatic("HorizonQAProperties", "hasModeError")))
            return unavailable(result, "reported_run_would_stop_server");
        String selector = CatalogData.text(args, "selector", 256, "all");
        if (selector.trim().isEmpty()) throw new BridgeException("invalid_args", "selector must not be empty.");
        List<?> definitions;
        List<Object> issues = new ArrayList<Object>();
        if (exact) {
            Object definition = invoke(catalogState.catalog.getClass(), catalogState.catalog, "findTest", new Class<?>[] {String.class}, selector);
            if (definition == null) throw new BridgeException("qa_test_unknown", "The exact test ID is absent from the public catalog; inspect diagnostics.");
            definitions = Collections.singletonList(definition);
        } else {
            Object selection;
            if ("all".equals(selector)) {
                selection = invoke(catalogState.catalog.getClass(), catalogState.catalog, "select", new Class<?>[] {boolean.class, List.class}, true, Collections.emptyList());
            } else {
                Class<?> selectorType = type("HorizonQAProperties$SelectorType");
                Object selectorKind = null;
                String kind = selector.indexOf(':') >= 0 ? "TEST_ID_PREFIX" : "NAMESPACE_OR_HOLDER";
                for (Object value : selectorType.getEnumConstants()) if (kind.equals(string(value))) selectorKind = value;
                if (selectorKind == null) throw apiUnavailable();
                Object nativeSelector = construct(type("HorizonQAProperties$TestSelector"), new Class<?>[] {selectorType, String.class}, selectorKind, selector);
                selection = invoke(catalogState.catalog.getClass(), catalogState.catalog, "select", new Class<?>[] {boolean.class, List.class}, false, Collections.singletonList(nativeSelector));
            }
            definitions = list(call(selection, "selectedTests"));
            for (Object issue : list(call(selection, "infrastructureIssues")))
                issues.add(invoke(type("report.IssueResult"), null, "selection", new Class<?>[] {type("internal.GameTestSelection$SelectionIssue")}, issue));
        }
        if (definitions.isEmpty() && issues.isEmpty()) {
            Object issue = invoke(type("internal.GameTestSelection"), null, "noSelectedTests", new Class<?>[] {boolean.class, String.class}, "all".equals(selector), selector);
            issues.add(invoke(type("report.IssueResult"), null, "selection", new Class<?>[] {type("internal.GameTestSelection$SelectionIssue")}, issue));
        }
        Supplier<List<Object>> configuration = () -> {
            List<Object> propertyIssues = new ArrayList<Object>();
            for (Object issue : list(callStatic("HorizonQAProperties", "reportInfrastructureIssues")))
                propertyIssues.add(invoke(type("report.IssueResult"), null, "property", new Class<?>[] {type("HorizonQAProperties$PropertyIssue")}, issue));
            return propertyIssues;
        };
        Object nativeRun = construct(type("internal.ReportedRun"), new Class<?>[] {type("internal.GameTestCatalog"), List.class, List.class, Supplier.class}, catalogState.catalog, definitions, issues, configuration);
        List<String> selectedIds = new ArrayList<String>();
        for (Object definition : definitions) selectedIds.add(string(call(definition, "getTestId")));
        Object baseline = observedReport;
        String startStatus = string(call(nativeRun, "start"));
        if ("ALREADY_ACTIVE".equals(startStatus)) throw new BridgeException("qa_busy", "Horizon-QA execution is already active; no run was started.");
        observe();
        QaRunTracker.Ticket ticket = runs.started(selector, selectedIds, baseline, startStatus, observedReport);
        result.addProperty("submitted", true);
        addTicket(result, ticket);
        return result;
    }

    private JsonObject status(JsonObject args, JsonObject result) {
        QaRunTracker.Ticket ticket = args.has("runId") ? runs.find(runId(args)) : runs.latest();
        if (ticket != null) addTicket(result, ticket);
        else {
            result.addProperty("state", observedBatchActive ? "native_running" : "native_idle");
            result.addProperty("correlation", "native-global");
            result.addProperty("owned", false);
            result.addProperty("nativeBatchActive", observedBatchActive);
            if (observedReport != null) result.add("nativeReport", reportSummary(observedReport, false));
        }
        return result;
    }

    private JsonObject results(JsonObject args, JsonObject result) {
        boolean details = includeDetails(args);
        result.addProperty("includeDetails", details);
        result.addProperty("detailsCoverage", details ? "requested" : "not_requested");
        QaRunTracker.Ticket ticket = args.has("runId") ? runs.find(runId(args)) : null;
        Object report = ticket == null ? observedReport : ticket.report(observedReport);
        result.addProperty("owned", ticket != null && ticket.owned());
        result.addProperty("correlation", ticket == null ? "native-global" : ticket.owned() ? "synchronous_start" : "unverified");
        if (ticket != null) { result.addProperty("runId", ticket.id); result.addProperty("runOwnedByBridge", true); result.addProperty("freshSinceStart", report != null); }
        String section = CatalogData.text(args, "section", 16, "cases");
        if (!"cases".equals(section) && !"issues".equals(section)) throw new BridgeException("invalid_args", "section must be cases or issues.");
        if (report == null) return unavailable(result, "report_not_ready");
        List<?> items = list(call(report, section));
        int offset = offset(args);
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 32) : 16;
        JsonArray entries = new JsonArray();
        int end = Math.min(items.size(), offset + limit);
        for (int i = offset; i < end; i++) entries.add("cases".equals(section) ? caseResult(items.get(i), details) : issueResult(items.get(i), details));
        result.add("nativeReport", reportSummary(report, ticket != null && ticket.owned()));
        pagination(result, section, entries, items.size(), offset, limit);
        return result;
    }

    private JsonObject command(MinecraftServer server, EntityPlayerMP player, JsonObject args, JsonObject result) {
        String command = CatalogData.text(args, "command", 32, null);
        if (!Arrays.asList(COMMANDS).contains(command)) throw new BridgeException("invalid_args", "Unknown Horizon-QA subcommand.");
        List<String> arguments = arguments(args);
        if ("run".equals(command) || "runall".equals(command)) {
            if (arguments.size() > 1 || "run".equals(command) && arguments.isEmpty()) throw new BridgeException("invalid_args", "run requires one test ID; runall accepts one optional selector.");
            String selector = arguments.isEmpty() ? "all" : arguments.get(0);
            JsonObject runArgs = new JsonObject(); runArgs.addProperty("selector", selector);
            return run(server, runArgs, result, "run".equals(command));
        }
        ICommand nativeCommand = nativeCommand(server);
        if (nativeCommand == null) return unavailable(result, "command_not_registered");
        if (!nativeCommand.canCommandSenderUseCommand(player)) throw new BridgeException("permission_denied", "The native Horizon-QA command rejected this server player.");
        List<String> nativeArgs = new ArrayList<String>(); nativeArgs.add(command); nativeArgs.addAll(arguments);
        nativeCommand.processCommand(player, nativeArgs.toArray(new String[nativeArgs.size()]));
        result.addProperty("submitted", true);
        result.addProperty("command", command);
        result.addProperty("commandOutcome", "not_reported");
        result.addProperty("messagesSource", "client.messages");
        observe(); result.addProperty("nativeBatchActive", observedBatchActive);
        return result;
    }

    private void addTicket(JsonObject result, QaRunTracker.Ticket ticket) {
        Object report = ticket.report(observedReport);
        result.addProperty("runId", ticket.id);
        result.addProperty("runOwnedByBridge", true);
        result.addProperty("selector", ticket.selector);
        result.addProperty("startStatus", ticket.startStatus);
        result.addProperty("state", ticket.state(observedReport, observedBatchActive));
        result.addProperty("owned", ticket.owned());
        result.addProperty("correlation", ticket.owned() ? "synchronous_start" : "unverified");
        result.addProperty("selectedTests", ticket.selectedIds.size());
        JsonArray ids = new JsonArray(); for (int i = 0; i < Math.min(64, ticket.selectedIds.size()); i++) ids.add(new com.google.gson.JsonPrimitive(JsonData.bounded(ticket.selectedIds.get(i), 512)));
        result.add("selectedIds", ids);
        result.addProperty("selectedIdsTruncated", ticket.selectedIds.size() > 64);
        result.addProperty("nativeBatchActive", observedBatchActive);
        result.addProperty("freshSinceStart", report != null);
        result.addProperty("ageMillis", Math.max(0, (System.nanoTime() - ticket.startedAt) / 1000000L));
        if (report != null) {
            result.add("nativeReport", reportSummary(report, ticket.owned()));
            Set<String> reportIds = new LinkedHashSet<String>(); for (Object item : list(call(report, "cases"))) reportIds.add(string(call(item, "id")));
            result.addProperty("selectionMatches", reportIds.equals(new LinkedHashSet<String>(ticket.selectedIds)));
        }
        result.addProperty("cancelAvailable", false);
        result.addProperty("cancelUnavailableReason", "public_api_unavailable");
    }

    private static JsonObject definition(Object value) {
        JsonObject item = new JsonObject();
        text(item, "id", call(value, "getTestId"), 512); text(item, "baseId", call(value, "getBaseTestId"), 512);
        text(item, "holder", call(value, "getHolderClassName"), 512); text(item, "template", call(value, "getTemplateName"), 512);
        text(item, "batch", call(value, "getBatch"), 256); text(item, "caseName", call(value, "getCaseName"), 256);
        item.addProperty("required", Boolean.TRUE.equals(call(value, "isRequired")));
        item.addProperty("parameterized", Boolean.TRUE.equals(call(value, "isParameterized")));
        item.addProperty("timeoutTicks", (Number) call(value, "getTimeoutTicks"));
        item.addProperty("skippedAtDiscovery", Boolean.TRUE.equals(call(value, "isSkippedAtDiscovery")));
        text(item, "discoverySkipReason", call(value, "getDiscoverySkipReason"), 512);
        return item;
    }

    private static JsonObject discoveryIssue(Object value, String category) {
        JsonObject item = new JsonObject(); item.addProperty("category", category);
        text(item, "id", call(value, "id"), 512); text(item, "kind", call(value, "kind"), 128); text(item, "message", call(value, "message"), 1024);
        return item;
    }

    private static JsonObject reportSummary(Object report, boolean owned) {
        JsonObject result = new JsonObject(); result.addProperty("source", owned ? "synchronous_start" : "native-global"); result.addProperty("owned", owned);
        text(result, "status", call(report, "status"), 32); text(result, "mode", call(report, "mode"), 32); text(result, "junitReport", call(report, "junitReport"), 512);
        for (String field : new String[] {"exitCode", "selectedTests", "passed", "failed", "timedOut", "skipped", "incomplete", "requiredFailures", "optionalFailures", "infrastructureErrors", "durationSeconds"}) result.addProperty(field, (Number) call(report, field));
        return result;
    }

    static boolean includeDetails(JsonObject args) { return RemoteReadProtocol.booleanArg(args, "includeDetails"); }

    static JsonObject caseResult(Object value, boolean includeDetails) {
        JsonObject item = new JsonObject();
        for (String key : new String[] {"id", "classname", "name", "status", "failureType", "blockedByIssueId"}) text(item, key, call(value, key), 512);
        text(item, "failureMessage", call(value, "failureMessage"), 1024);
        item.addProperty("required", Boolean.TRUE.equals(call(value, "required")));
        item.addProperty("tickCount", (Number) call(value, "tickCount")); item.addProperty("timeSeconds", (Number) call(value, "timeSeconds"));
        item.addProperty("detailsCoverage", includeDetails ? "requested" : "not_requested");
        if (!includeDetails) return item;
        text(item, "failureTrace", call(value, "failureTrace"), 4096);
        List<?> output = list(call(value, "outputLines")); JsonArray lines = new JsonArray();
        boolean clipped = output.size() > 16;
        for (int i = 0; i < Math.min(16, output.size()); i++) {
            String line = string(output.get(i)); clipped |= line.length() > 512;
            lines.add(new com.google.gson.JsonPrimitive(JsonData.bounded(line, 512)));
        }
        item.add("outputLines", lines); item.addProperty("outputTotal", output.size()); item.addProperty("outputTruncated", clipped);
        item.addProperty("detailsTruncated", clipped || item.has("failureTraceTruncated"));
        return item;
    }

    static JsonObject issueResult(Object value, boolean includeDetails) {
        JsonObject item = new JsonObject();
        for (String key : new String[] {"id", "kind", "classname", "name"}) text(item, key, call(value, key), 512);
        text(item, "message", call(value, "message"), 1024);
        item.addProperty("fatalInCi", Boolean.TRUE.equals(call(value, "fatalInCi")));
        item.addProperty("detailsCoverage", includeDetails ? "requested" : "not_requested");
        if (!includeDetails) return item;
        text(item, "details", call(value, "details"), 2048); text(item, "stackTrace", call(value, "stackTrace"), 4096);
        item.addProperty("detailsTruncated", item.has("detailsTruncated") || item.has("stackTraceTruncated"));
        return item;
    }

    private static JsonObject page(JsonObject result, String section, List<JsonObject> items, JsonObject args, int maximum, int fallback) {
        int offset = offset(args); int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, maximum) : fallback;
        JsonArray entries = new JsonArray(); for (int i = offset; i < Math.min(items.size(), offset + limit); i++) entries.add(items.get(i));
        pagination(result, section, entries, items.size(), offset, limit); return result;
    }

    private static void pagination(JsonObject result, String section, JsonArray entries, int total, int offset, int limit) {
        result.addProperty("section", section); result.add("entries", entries); result.addProperty("total", total);
        result.addProperty("offset", offset); result.addProperty("limit", limit); boolean more = offset + entries.size() < total;
        result.addProperty("hasMore", more); result.addProperty("truncated", more);
        if (more) result.addProperty("nextOffset", offset + entries.size()); else result.add("nextOffset", JsonNull.INSTANCE);
    }

    private static int offset(JsonObject args) { return args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0; }

    private static String runId(JsonObject args) {
        String value = CatalogData.text(args, "runId", 128, null);
        if (value.isEmpty()) throw new BridgeException("invalid_args", "runId must not be empty.");
        return value;
    }

    static List<String> arguments(JsonObject args) {
        if (!args.has("args")) return Collections.emptyList();
        JsonElement element = args.get("args");
        if (!element.isJsonArray() || element.getAsJsonArray().size() > 8) throw new BridgeException("invalid_args", "args must be an array of at most 8 strings.");
        List<String> result = new ArrayList<String>();
        for (JsonElement arg : element.getAsJsonArray()) {
            if (!arg.isJsonPrimitive() || !arg.getAsJsonPrimitive().isString()) throw new BridgeException("invalid_args", "Each command arg must be a string.");
            String value = arg.getAsString();
            if (value.isEmpty() || value.length() > 256) throw new BridgeException("invalid_args", "Command args must contain 1 through 256 characters.");
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= 0x20 || value.charAt(i) == 0x7f)
                throw new BridgeException("invalid_args", "Command args must be single tokens without ASCII whitespace or control characters.");
            result.add(value);
        }
        return result;
    }

    private static ICommand nativeCommand(MinecraftServer server) {
        Object value = server.getCommandManager().getCommands().get("horizonqa");
        return value instanceof ICommand && value.getClass().getName().equals(ROOT + "command.HorizonQACommand") ? (ICommand) value : null;
    }

    private static void text(JsonObject item, String key, Object value, int bound) { String raw = string(value); item.addProperty(key, JsonData.bounded(raw, bound)); if (raw.length() > bound) item.addProperty(key + "Truncated", true); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static List<?> list(Object value) { if (!(value instanceof List)) throw apiUnavailable(); return (List<?>) value; }
    private static Class<?> type(String suffix) {
        try { return Class.forName(ROOT + suffix, false, HorizonQaAdapter.class.getClassLoader()); }
        catch (ClassNotFoundException error) { throw apiUnavailable(); }
    }
    private static Object callStatic(String type, String name) { return invoke(type(type), null, name, new Class<?>[0]); }
    private static Object call(Object target, String name) { return invoke(target.getClass(), target, name, new Class<?>[0]); }
    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = owner.getMethod(name, signature);
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) throw apiUnavailable();
            return method.invoke(target, args);
        } catch (InvocationTargetException error) { throw nativeFailure(error.getCause()); }
        catch (ReflectiveOperationException error) { throw apiUnavailable(); }
    }
    private static Object construct(Class<?> owner, Class<?>[] signature, Object... args) {
        try {
            Constructor<?> constructor = owner.getConstructor(signature);
            if (!Modifier.isPublic(owner.getModifiers())) throw apiUnavailable();
            return constructor.newInstance(args);
        } catch (InvocationTargetException error) { throw nativeFailure(error.getCause()); }
        catch (ReflectiveOperationException error) { throw apiUnavailable(); }
    }
    private static BridgeException nativeFailure(Throwable error) {
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        if (error instanceof BridgeException) return (BridgeException) error;
        return new BridgeException("qa_failed", "Horizon-QA public API failed: " + error.getClass().getSimpleName() + ".");
    }
    private static BridgeException apiUnavailable() { return new BridgeException("qa_api_unavailable", "The installed Horizon-QA version does not expose this required public API."); }

    private static final class CatalogState {
        final MinecraftServer server; final Object catalog; final String reason; final String mode;
        CatalogState(MinecraftServer server, Object catalog, String reason, String mode) { this.server = server; this.catalog = catalog; this.reason = reason; this.mode = mode; }
    }
}
