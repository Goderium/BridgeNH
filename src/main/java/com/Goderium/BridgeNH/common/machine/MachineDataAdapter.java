package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.common.recipe.OutputResourceQueryAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;

/** Detailed read-only GT diagnostics. Caller supplies an existing loaded tile on its owning world thread. */
public class MachineDataAdapter {
    private static final String BASE = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String BASIC_MACHINE = "gregtech.api.metatileentity.implementations.MTEBasicMachine";
    private static final String CIRCUIT_SUPPORT = "gregtech.api.interfaces.IConfigurationCircuitSupport";
    private static final String MULTI = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase";
    private static final String TST = "com.Nxer.TwistSpaceTechnology.common.machine.multiMachineClasses.GTCM_MultiMachineBase";
    private static final String MODULAR = "com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic.IModularizedMachine";
    private static final String MODULAR_HATCH = "com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.IModularHatch";
    private static final String EXECUTION_CORE = "com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.ExecutionCores.IExecutionCore";
    private static final String OUTPUT_ENVIRONMENT = "gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase$Environment";
    public static final int COMPONENT_LIMIT = 128;

    public static JsonObject inspect(World world, TileEntity tile, JsonObject args) { return read(world, tile, args, System.nanoTime()); }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(World world, Object tile, JsonObject args, long started) {
        if (AdapterAccess.type(tile, BASE) == null) throw new BridgeException("unsupported", "Target is not a supported GregTech machine.");
        int offset = args.has("componentOffset") ? JsonData.integer(args, "componentOffset", 0, 100000) : 0;
        int limit = args.has("componentLimit") ? JsonData.integer(args, "componentLimit", 1, COMPONENT_LIMIT) : 32;
        Object meta = AdapterAccess.call(tile, "getMetaTileEntity");
        if (meta == null) throw new BridgeException("not_found", "Machine has no valid meta tile entity.");
        JsonObject result = new JsonObject(); result.addProperty("metaClass", meta.getClass().getName());
        result.add("orientation", MachineEnergyAdapter.read(tile, meta));
        result.add("metaTileId", GTRecipeAdapter.value(AdapterAccess.call(tile, "getMetaTileID"), 0));
        if (tile instanceof TileEntity) {
            TileEntity position = (TileEntity) tile;
            result.addProperty("x", position.xCoord); result.addProperty("y", position.yCoord); result.addProperty("z", position.zCoord);
            result.addProperty("dimension", world.provider.dimensionId);
        }
        result.add("singleBlockRecipeMachine", singleBlockRecipeMachine(meta, result));
        JsonObject state = new JsonObject();
        for (String[] pair : new String[][] {{"isActive", "active"}, {"isAllowedToWork", "allowedToWork"}, {"getProgress", "progressTicks"},
                {"getMaxProgress", "maxProgressTicks"}, {"getStoredEU", "storedEU"}, {"getEUCapacity", "euCapacity"},
                {"getInputVoltage", "inputVoltage"}, {"getOutputVoltage", "outputVoltage"}}) collect(state, tile, pair[0], pair[1]);
        result.add("state", state);
        JsonObject upgrades = new JsonObject(); collect(upgrades, tile, "getUpgradeCount", "generalUpgradeCount");
        collect(upgrades, tile, "isMuffled", "muffler"); collect(upgrades, tile, "privateAccess", "privateAccess");
        upgrades.add("lockUpgrade", fixedField(tile, BASE, "mLockUpgrade", true));
        upgrades.add("otherUpgradeCount", fixedField(tile, BASE, "mOtherUpgrades", true));
        upgrades.addProperty("machineSpecificCoverage", "GT base upgrade flags and fixed TST base processing modifiers; arbitrary machine-specific upgrade fields are unavailable");
        result.add("upgrades", upgrades);
        result.add("shutdownReason", message(tile, "getLastShutDownReason", false));
        result.add("checkRecipeResult", message(meta, "getCheckRecipeResult", true));
        JsonObject modes = new JsonObject();
        for (String[] pair : new String[][] {{"getMachineMode", "index"}, {"getMachineModeName", "name"}, {"isInputSeparationEnabled", "inputSeparation"},
                {"isBatchModeEnabled", "batch"}, {"isRecipeLockingEnabled", "recipeLocked"}, {"getVoidingMode", "voiding"},
                {"getMaxParallelRecipes", "maxParallelRecipes"}, {"getTrueParallel", "trueParallelLimit"}, {"getPowerPanelMaxParallel", "powerPanelMaxParallel"},
                {"isAlwaysMaxParallel", "alwaysMaxParallel"}}) collect(modes, meta, pair[0], pair[1]);
        result.add("modes", modes);
        JsonObject processing = new JsonObject();
        collect(processing, meta, "getEUtForDamageCalc", "signedCalculatedEUt");
        processing.addProperty("eutMeaning", "Current controller's signed, already calculated EU/t; negative consumes, positive generates. This is not the recipe's raw EU/t");
        if (AdapterAccess.type(meta, MULTI) != null) {
            for (String[] pair : new String[][] {{"mMachine", "structureFormed"}, {"mStructureChanged", "structureChanged"}, {"mEfficiency", "efficiency10000"},
                    {"mEfficiencyIncrease", "efficiencyIncrease"}, {"lastParallel", "lastParallel"}, {"mProgresstime", "progressTicks"},
                    {"mMaxProgresstime", "durationTicks"}, {"recipesDone", "recipesDone"}})
                processing.add(pair[1], fixedField(meta, MULTI, pair[0], false));
            for (String[] pair : new String[][] {{"getIdealStatus", "idealMaintenanceStatus"}, {"getRepairStatus", "repairStatus"},
                    {"getErrorDisplayID", "structureErrorBits"}, {"getTotalRunTime", "totalRuntimeTicks"}, {"getLastWorkingTick", "lastWorkingTick"}})
                collect(processing, meta, pair[0], pair[1]);
            try {
                Object signed = AdapterAccess.call(meta, "getEUtForDamageCalc");
                if (signed instanceof Number && ((Number) signed).longValue() < 0)
                    processing.add("actualEnergyUsagePerTick", GTRecipeAdapter.value(AdapterAccess.invoke(AdapterAccess.type(meta, MULTI), meta,
                            "getActualEnergyUsage", new Class<?>[0], true), 0));
                else processing.addProperty("actualEnergyUsagePerTickSource", "Consumption getter only applies while signedCalculatedEUt is negative");
            } catch (RuntimeException | LinkageError error) { processing.add("actualEnergyUsagePerTick", failure(error)); }
            Object logic = null;
            try { logic = AdapterAccess.field(meta, MULTI, "processingLogic", true); }
            catch (RuntimeException | LinkageError error) { processing.add("logic", failure(error)); }
            if (logic != null) {
                JsonObject calculated = new JsonObject(); collect(calculated, logic, "getCalculatedEut", "eut");
                collect(calculated, logic, "getDuration", "durationTicks"); collect(calculated, logic, "getCurrentParallels", "parallels");
                calculated.addProperty("source", "Existing ProcessingLogic result; may be cached after completion, not a new simulation");
                processing.add("lastProcessingCalculation", calculated);
                try {
                    Object recipe = AdapterAccess.field(logic, "gregtech.api.logic.ProcessingLogic", "lastRecipe", true);
                    if (recipe != null) {
                        JsonObject last = GTRecipeAdapter.read(recipe, started);
                        last.addProperty("scope", "ProcessingLogic.lastRecipe matching cache; not proof this recipe is currently running");
                        processing.add("lastMatchedRecipe", last);
                    }
                } catch (RuntimeException | LinkageError error) { processing.add("lastMatchedRecipe", failure(error)); }
            }
        }
        if (AdapterAccess.type(meta, TST) != null) {
            JsonObject tst = new JsonObject();
            for (String[] pair : new String[][] {{"getEuModifier", "euModifier"}, {"getSpeedBonus", "speedBonus"}, {"isEnablePerfectOverclock", "perfectOverclock"}}) {
                try { tst.add(pair[1], GTRecipeAdapter.value(AdapterAccess.invoke(AdapterAccess.type(meta, TST), meta, pair[0], new Class<?>[0], true), 0)); }
                catch (RuntimeException | LinkageError error) { tst.add(pair[1], failure(error)); }
            }
            collect(tst, meta, "getTotalPowerTier", "totalPowerTier"); collect(tst, meta, "getDominantInputVoltageTier", "dominantInputVoltageTier");
            processing.add("tstBaseModifiers", tst);
        }
        result.add("processing", processing);
        moduleData(result, meta, null);
        JsonObject storage = outputStorage(meta);
        if (args.has("outputQueries")) storage.add("resourceQuery", OutputResourceQueryAdapter.read(meta, args, result));
        result.add("outputStorage", storage);
        result.add("fluidInputs", fluidInputs(meta, args));
        collect(result, meta, "getInfoData", "info");
        MachineDescriptionAdapter.add(result, meta);
        result.add("components", components(world, meta, offset, limit, started));
        JsonObject coverage = new JsonObject();
        coverage.addProperty("scope", "Live existing GT controller, registered component lists and fixed processing caches on the owning world thread");
        coverage.addProperty("components", "Registered GT roles and public IModularizedMachine.getAllModularHatches when implemented, including invalid/unloaded entries; no structure recheck or forced chunk loading");
        coverage.addProperty("modules", "Public declared module type and IExecutionCore state only; registered collection membership does not prove chamber rules, operational validity or an exhaustive physical structure scan");
        coverage.addProperty("outputStorage", "Public ME output provider cache totals and optional explicit untagged identity queries, decimal-string long values; excludes inserted cell and connected network. No enumeration or flush.");
        coverage.addProperty("fluidInputs", "Public getFluidList when implemented, paged with original null slot positions and exact list totals; not a generic getFluid first-tank fallback. Stack tags are bounded summaries; native full serialized NBT requires explicit machine.capture opt-in.");
        coverage.addProperty("descriptions", "Fixed public StructureLib construction hints and GT primary/secondary tooltip branches, bounded per field; no modifier-state selection, construction or structure checks.");
        coverage.addProperty("missingApis", "Unavailable fields/getters are explicit objects, never implied to be supported or empty");
        coverage.addProperty("consistency", "Read-only, non-atomic across paginated requests; component offset is in current flattened role order");
        result.add("coverage", coverage); result.addProperty("nonAtomic", true); result.add("budget", StructureAdapter.budget(started));
        if (result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128 * 1024)
            throw new BridgeException("too_large", "Machine diagnostics exceed the bounded response size; request fewer components.");
        return result;
    }

    static JsonObject fluidInputs(Object meta, JsonObject args) {
        int offset = args.has("inputOffset") ? JsonData.integer(args, "inputOffset", 0, 16384) : 0;
        int limit = args.has("inputLimit") ? JsonData.integer(args, "inputLimit", 1, 128) : 32;
        JsonObject result = new JsonObject(); result.addProperty("source", "public_getFluidList");
        result.addProperty("scope", "Native exposed input fluid list; configured debug fluids are templates, not finite inventory or network contents");
        try {
            Object value = AdapterAccess.call(meta, "getFluidList");
            if (!(value instanceof net.minecraftforge.fluids.FluidStack[])) throw new BridgeException("unsupported", "Public fluid list unavailable.");
            net.minecraftforge.fluids.FluidStack[] fluids = (net.minecraftforge.fluids.FluidStack[]) value;
            if (fluids.length > 16384) throw new BridgeException("data_limit", "Public fluid list exceeds the bounded enumeration.");
            if (offset > fluids.length) throw new BridgeException("invalid_args", "Input fluid offset exceeds the actual list size.");
            int configured = 0; for (net.minecraftforge.fluids.FluidStack fluid : fluids) if (fluid != null) configured++;
            JsonArray page = new JsonArray(); int end = Math.min(fluids.length, offset + limit);
            for (int i = offset; i < end; i++) {
                JsonObject row = new JsonObject(); row.addProperty("index", i); row.add("fluid", GTRecipeAdapter.value(fluids[i], 0)); page.add(row);
            }
            result.addProperty("available", true); result.add("entries", page); result.addProperty("totalSlots", fluids.length);
            result.addProperty("configuredFluids", configured); result.addProperty("offset", offset); result.addProperty("nextOffset", end);
            result.addProperty("hasMore", end < fluids.length); result.addProperty("pageTruncated", false);
            result.addProperty("nonAtomicAcrossRequests", true); result.addProperty("nbtCoverage", "Bounded per-stack tag summary, not full NBT");
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("available", false); result.addProperty("complete", false);
            result.addProperty("reason", error instanceof BridgeException ? ((BridgeException) error).code : error.getClass().getSimpleName());
        }
        return result;
    }

    /** Public runtime membership only; actual recipe/map, slots and execution need their own observations. */
    static JsonObject singleBlockRecipeMachine(Object meta, JsonObject observation) {
        JsonObject proof = new JsonObject();
        proof.addProperty("schemaVersion", 1);
        proof.addProperty("apiClass", BASIC_MACHINE);
        proof.addProperty("source", "public_runtime_api");
        proof.addProperty("runtimeMember", publicApi(meta, BASIC_MACHINE) != null);
        JsonObject owner = new JsonObject();
        for (String key : new String[] {"metaTileId", "metaClass", "x", "y", "z", "dimension"})
            if (observation.has(key)) owner.add(key, observation.get(key));
        proof.add("owner", owner);
        proof.add("configurationCircuit", configurationCircuit(meta, owner));
        return proof;
    }

    /** Actual public backing index, distinct from the phantom widget's native GUI inventory index. */
    private static JsonObject configurationCircuit(Object meta, JsonObject owner) {
        JsonObject result = new JsonObject();
        result.addProperty("apiClass", CIRCUIT_SUPPORT);
        result.addProperty("source", "public_runtime_api");
        result.add("owner", owner);
        Class<?> api = publicApi(meta, CIRCUIT_SUPPORT);
        if (publicApi(meta, BASIC_MACHINE) == null || api == null) {
            result.addProperty("available", false);
            result.addProperty("reason", "public_configuration_circuit_api_unavailable_or_not_implemented");
            return result;
        }
        try {
            Object selectable = AdapterAccess.invoke(api, meta, "allowSelectCircuit", new Class<?>[0], false);
            Object index = AdapterAccess.invoke(api, meta, "getCircuitSlot", new Class<?>[0], false);
            if (!(selectable instanceof Boolean) || !(index instanceof Integer) || (Integer) index < 0)
                throw new BridgeException("unsupported", "Public configuration circuit getters returned invalid types or index.");
            result.addProperty("available", true);
            result.addProperty("allowSelectCircuit", (Boolean) selectable);
            result.addProperty("inventoryIndex", (Integer) index);
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("available", false);
            result.addProperty("reason", "public_configuration_circuit_getter_failed");
            result.add("error", failure(error));
        }
        return result;
    }

    static JsonObject outputStorage(Object meta) {
        JsonObject result = new JsonObject(); result.addProperty("api", OUTPUT_ENVIRONMENT + ".getProvider");
        Class<?> environment = publicApi(meta, OUTPUT_ENVIRONMENT);
        if (environment == null) {
            result.addProperty("available", false); result.addProperty("reason", "public_output_provider_unavailable_or_not_implemented");
            return result;
        }
        try {
            Object provider = AdapterAccess.invoke(environment, meta, "getProvider", new Class<?>[0], false);
            if (provider == null) throw new BridgeException("unsupported", "Public output provider is null.");
            result.addProperty("available", true);
            for (String[] pair : new String[][] {{"getCachedAmount", "cachedAmount"}, {"getCacheCapacity", "cacheCapacity"}}) {
                Object value = AdapterAccess.call(provider, pair[0]);
                if (!(value instanceof Long)) throw new BridgeException("unsupported", "Public output quantity is not a long.");
                result.addProperty(pair[1], Long.toString((Long) value));
            }
            result.addProperty("scope", "Aggregate provider cache only; excludes inserted cell and connected network; this aggregate does not establish item/fluid identities.");
        } catch (RuntimeException | LinkageError error) {
            result = failure(error); result.addProperty("reason", "public_output_provider_failed");
        }
        return result;
    }

    private static JsonObject components(World world, Object meta, int offset, int limit, long started) {
        JsonObject result = new JsonObject(); JsonArray unavailable = new JsonArray(); List<Role> roles = new ArrayList<>();
        if (AdapterAccess.type(meta, MULTI) != null) {
            for (String[] pair : new String[][] {{"mInputHatches", "fluid_input"}, {"mOutputHatches", "fluid_output"}, {"mInputBusses", "item_input"},
                    {"mOutputBusses", "item_output"}, {"mDualInputHatches", "dual_input"}, {"mDynamoHatches", "dynamo"},
                    {"mMufflerHatches", "muffler"}, {"mEnergyHatches", "energy"}, {"mMaintenanceHatches", "maintenance"},
                    {"mDataAccessHatches", "data_access"}})
                addRole(roles, unavailable, meta, pair[0], pair[1], false);
            for (String[] pair : new String[][] {{"getExoticEnergyHatches", "exotic_energy"}, {"getExoticDynamoHatches", "exotic_dynamo"},
                    {"getCryotheumHatches", "cryotheum"}, {"getPyrotheumHatches", "pyrotheum"}})
                addRole(roles, unavailable, meta, pair[0], pair[1], true);
        }
        result.add("modularHatches", modularRole(roles, unavailable, meta));
        int total = 0; JsonArray summaries = new JsonArray();
        for (Role role : roles) { JsonObject summary = new JsonObject(); summary.addProperty("role", role.name);
            summary.addProperty("count", role.entries.size()); summaries.add(summary);
            if (role.entries.size() > 100000 - total) throw new BridgeException("too_large", "Registered components exceed the 100000-entry pagination bound.");
            total += role.entries.size(); }
        result.addProperty("directoryFingerprint", componentDirectoryFingerprint(world, meta, roles));
        JsonArray data = new JsonArray(); int global = 0, next = Math.min(offset, total); boolean budgetPartial = false;
        outer: for (Role role : roles) {
            if (global + role.entries.size() <= offset) { global += role.entries.size(); continue; }
            int first = Math.max(0, offset - global);
            Iterator<?> iterator = role.entries instanceof List<?> ? null : role.entries.iterator();
            if (iterator != null) for (int skipped = 0; skipped < first && iterator.hasNext(); skipped++) iterator.next();
            for (int i = first; i < role.entries.size(); i++) {
                if (data.size() >= limit) break outer;
                if (data.size() > 0 && GTRecipeAdapter.expired(started)) { budgetPartial = true; break outer; }
                if (iterator != null && !iterator.hasNext()) break;
                Object registered = iterator == null ? ((List<?>) role.entries).get(i) : iterator.next();
                JsonObject entry = component(world, registered, meta);
                entry.addProperty("role", role.name); entry.addProperty("roleIndex", i); entry.addProperty("index", global + i);
                data.add(entry); next = global + i + 1;
            }
            global += role.entries.size();
        }
        result.add("roles", summaries); result.add("entries", data); result.add("unavailableRoles", unavailable);
        result.addProperty("total", total); result.addProperty("offset", offset); result.addProperty("nextOffset", next);
        result.addProperty("hasMore", next < total); result.addProperty("softBudgetPartial", budgetPartial);
        result.addProperty("limit", limit); return result;
    }

    /** Bounded public registration identities only; no component getters, chunk loading or retained world references. */
    private static String componentDirectoryFingerprint(World world, Object meta, List<Role> roles) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestInt(digest, System.identityHashCode(world)); digestInt(digest, System.identityHashCode(meta));
            for (Role role : roles) {
                digest.update(role.name.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                digestInt(digest, role.entries.size());
                for (Object entry : role.entries) digestInt(digest, System.identityHashCode(entry));
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) { hex.append(Character.forDigit((value >>> 4) & 15, 16)); hex.append(Character.forDigit(value & 15, 16)); }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void digestInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16)); digest.update((byte) (value >>> 8)); digest.update((byte) value);
    }

    private static JsonObject modularRole(List<Role> roles, JsonArray unavailable, Object meta) {
        JsonObject result = new JsonObject(); result.addProperty("api", MODULAR + ".getAllModularHatches");
        Class<?> api = publicApi(meta, MODULAR);
        if (api == null) {
            result.addProperty("available", false); result.addProperty("reason", "public_modular_interface_unavailable_or_not_implemented");
        } else try {
            Object value = AdapterAccess.invoke(api, meta, "getAllModularHatches", new Class<?>[0], false);
            if (value instanceof Collection<?>) {
                Collection<?> entries = (Collection<?>) value;
                result.addProperty("available", true); result.addProperty("registeredCount", entries.size());
                roles.add(new Role("modular_hatch", entries));
            } else {
                result.addProperty("available", false); result.addProperty("reason", "public_getter_did_not_return_collection");
            }
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("available", false); result.addProperty("reason", "public_getter_failed"); result.add("error", failure(error));
        }
        result.addProperty("coverage", "Current registered modules only; no working/idle/boost filter, no private fields or structure checks.");
        if (!result.get("available").getAsBoolean()) {
            JsonObject missing = new JsonObject(); missing.addProperty("role", "modular_hatch");
            missing.addProperty("reason", result.get("reason").getAsString()); unavailable.add(missing);
        }
        return result;
    }
    private static void addRole(List<Role> roles, JsonArray unavailable, Object meta, String api, String name, boolean method) {
        try {
            Object value = method ? AdapterAccess.call(meta, api) : AdapterAccess.field(meta,
                    "mDataAccessHatches".equals(api) ? meta.getClass().getName() : MULTI, api, false);
            if (value instanceof List<?>) roles.add(new Role(name, (List<?>) value));
            else { JsonObject error = new JsonObject(); error.addProperty("role", name); error.addProperty("reason", "list_unavailable"); unavailable.add(error); }
        } catch (RuntimeException | LinkageError error) { JsonObject entry = failure(error); entry.addProperty("role", name); unavailable.add(entry); }
    }
    private static JsonObject component(World world, Object meta, Object controller) {
        JsonObject result = new JsonObject();
        if (meta == null) { result.addProperty("valid", false); result.addProperty("reason", "null_registered_entry"); return result; }
        result.addProperty("metaClass", meta.getClass().getName());
        moduleData(result, meta, controller);
        try {
            Object base = AdapterAccess.call(meta, "getBaseMetaTileEntity");
            collect(result, meta, "getInputTier", "tier");
            if (AdapterAccess.type(meta, "gregtech.api.metatileentity.implementations.MTEHatch") != null)
                collect(result, meta, "getTierForStructure", "structureTier");
            collect(result, meta, "getOutputTier", "outputTier");
            if (!(base instanceof TileEntity)) { result.addProperty("valid", false); result.addProperty("reason", "base_tile_unavailable"); return result; }
            TileEntity tile = (TileEntity) base;
            World componentWorld = tile.getWorld();
            result.addProperty("x", tile.xCoord); result.addProperty("y", tile.yCoord); result.addProperty("z", tile.zCoord);
            if (componentWorld == null) result.add("dimension", com.google.gson.JsonNull.INSTANCE);
            else result.addProperty("dimension", componentWorld.provider.dimensionId);
            collect(result, base, "getMetaTileID", "metaTileId");
            boolean sameWorld = componentWorld == world && world != null;
            boolean loaded = sameWorld && world.getChunkProvider().chunkExists(tile.xCoord >> 4, tile.zCoord >> 4);
            boolean present = loaded && world.getChunkFromChunkCoords(tile.xCoord >> 4, tile.zCoord >> 4).chunkTileEntityMap
                    .get(new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15)) == tile;
            boolean sameMeta = AdapterAccess.call(base, "getMetaTileEntity") == meta;
            result.addProperty("loaded", loaded); result.addProperty("presentInLoadedChunk", present);
            result.addProperty("invalidTile", tile.isInvalid()); result.addProperty("metaIdentityMatches", sameMeta);
            result.addProperty("valid", sameWorld && present && !tile.isInvalid() && sameMeta);
            result.addProperty("reason", !sameWorld ? "different_or_missing_world" : !loaded ? "unloaded_chunk" : !present ? "stale_registered_tile" : tile.isInvalid() ? "invalid_tile" : !sameMeta ? "replaced_meta_tile" : "valid");
        } catch (RuntimeException | LinkageError error) { result.addProperty("valid", false); result.add("adapterError", failure(error)); }
        return result;
    }

    private static void moduleData(JsonObject result, Object meta, Object controller) {
        Class<?> hatch = publicApi(meta, MODULAR_HATCH);
        if (hatch != null) publicCollect(result, hatch, meta, "getType", "moduleType");
        Class<?> core = publicApi(meta, EXECUTION_CORE);
        if (core == null) return;
        JsonObject data = new JsonObject(); data.addProperty("api", EXECUTION_CORE);
        for (String[] pair : new String[][] {{"isIdle", "idle"}, {"isWorking", "working"}, {"useMainMachinePower", "usesMainMachinePower"},
                {"getEut", "eut"}, {"getNeedProgressingTime", "needProgressingTimeTicks"}})
            publicCollect(data, core, meta, pair[0], pair[1]);
        data.addProperty("eutMeaning", "IExecutionCore.getEut live core value; no recipe EU/t, controller sign convention or efficiency calculation is inferred.");
        data.addProperty("progressSource", "IExecutionCore.getNeedProgressingTime");
        try {
            Object main = AdapterAccess.invoke(core, meta, "getMainMachine", new Class<?>[0], false);
            JsonObject link = new JsonObject(); link.addProperty("available", main != null);
            if (main != null) {
                link.addProperty("metaClass", JsonData.bounded(main.getClass().getName(), 256));
                if (controller != null) link.addProperty("controllerIdentityMatches", main == controller);
                Object base = AdapterAccess.call(main, "getBaseMetaTileEntity");
                if (base instanceof TileEntity) {
                    TileEntity tile = (TileEntity) base;
                    link.addProperty("x", tile.xCoord); link.addProperty("y", tile.yCoord); link.addProperty("z", tile.zCoord);
                    World linkedWorld = tile.getWorld();
                    if (linkedWorld == null) link.add("dimension", com.google.gson.JsonNull.INSTANCE);
                    else link.addProperty("dimension", linkedWorld.provider.dimensionId);
                }
                if (base != null) collect(link, base, "getMetaTileID", "metaTileId");
            } else link.addProperty("reason", "public_getter_returned_null");
            data.add("mainMachine", link);
        } catch (RuntimeException | LinkageError error) { data.add("mainMachine", failure(error)); }
        result.add("executionCore", data);
    }

    private static Class<?> publicApi(Object target, String name) {
        if (target == null) return null;
        try {
            Class<?> api = Class.forName(name, false, target.getClass().getClassLoader());
            return api.isInstance(target) ? api : null;
        } catch (ClassNotFoundException | LinkageError error) { return null; }
    }

    private static void publicCollect(JsonObject result, Class<?> api, Object target, String method, String key) {
        try { result.add(key, GTRecipeAdapter.value(AdapterAccess.invoke(api, target, method, new Class<?>[0], false), 0)); }
        catch (RuntimeException | LinkageError error) { result.add(key, failure(error)); }
    }
    private static JsonObject message(Object target, String getter, boolean recipe) {
        JsonObject result = new JsonObject();
        try {
            Object value = AdapterAccess.call(target, getter);
            result.addProperty("available", value != null);
            if (value != null) {
                collect(result, value, "getID", "id"); collect(result, value, "getDisplayString", "message");
                if (recipe) { collect(result, value, "wasSuccessful", "successful"); collect(result, value, "persistsOnShutdown", "persistsOnShutdown"); }
                else collect(result, value, "wasCritical", "critical");
            }
        } catch (RuntimeException | LinkageError error) { return failure(error); }
        return result;
    }
    private static JsonElement fixedField(Object target, String owner, String name, boolean declared) {
        try { return GTRecipeAdapter.value(AdapterAccess.field(target, owner, name, declared), 0); }
        catch (RuntimeException | LinkageError error) { return failure(error); }
    }
    private static void collect(JsonObject result, Object target, String method, String key) {
        try { result.add(key, GTRecipeAdapter.value(AdapterAccess.call(target, method), 0)); }
        catch (RuntimeException | LinkageError error) { result.add(key, failure(error)); }
    }
    private static JsonObject failure(Throwable error) {
        JsonObject result = new JsonObject(); result.addProperty("available", false);
        result.addProperty("error", JsonData.bounded(error.getMessage(), 512)); return result;
    }
    private static final class Role {
        final String name; final Collection<?> entries;
        Role(String name, Collection<?> entries) { this.name = name; this.entries = entries; }
    }
}
