package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.client.nei.NeiDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

/** Public GT catalog or finite backend match, on the owning server thread; never executes a recipe. */
public final class RecipeQueryAdapter {
    public static final int CANDIDATE_LIMIT = 512, RESULT_LIMIT = 16, BYTE_LIMIT = 128 * 1024;
    public static final long SOFT_BUDGET_NANOS = 2000000L, CURSOR_TTL_NANOS = 30000000000L;
    static final long BACKEND_PAYLOAD_BUDGET_NANOS = 10000000L;
    static final int SESSION_LIMIT = 8, CATEGORY_LIMIT = 64, MAP_LIMIT = 16, MATCH_LIMIT = 256;
    private static final String MAP = "gregtech.api.recipe.RecipeMap", BACKEND = "gregtech.api.recipe.RecipeMapBackend";
    private static final String CATEGORY = "gregtech.api.recipe.RecipeCategory", WORKABLE = "gregtech.api.interfaces.tileentity.RecipeMapWorkable";
    private static final String BASE_TILE = "gregtech.api.metatileentity.BaseMetaTileEntity", MULTI = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase";
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<String, Session>();
    private final LongSupplier clock;
    private Api api;

    public RecipeQueryAdapter() { this(System::nanoTime); }
    RecipeQueryAdapter(LongSupplier clock) { this.clock = clock; }
    public interface TileLookup { Object at(int x, int y, int z); }

    public synchronized void invalidate() { sessions.clear(); }

    /** Continuations are single use and bind to the actual world, inventory, machine and registry objects. */
    public synchronized JsonObject read(Object world, String sessionId, int dimension, InventoryPlayer inventory,
                                 TileLookup tiles, JsonObject args, long started) {
        if (api == null) api = new Api();
        if (args.has("mode")) {
            String mode = text(args, "mode", 32);
            if ("backend_match".equals(mode)) return backendMatch(dimension, inventory, tiles, args, started);
            if (!"catalog".equals(mode)) throw new BridgeException("invalid_args", "Recipe query mode must be catalog or backend_match.");
        }
        long now = clock.getAsLong();
        Iterator<Session> expired = sessions.values().iterator();
        while (expired.hasNext()) if (now - expired.next().lastAccess >= CURSOR_TTL_NANOS) expired.remove();
        boolean continuation = args.has("cursor");
        keys(args, continuation ? new String[] {"mode", "cursor", "dimension", "limit"}
                : new String[] {"mode", "map", "x", "y", "z", "dimension", "input", "output", "limit"});
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, RESULT_LIMIT) : 4;
        Session query;
        if (continuation) {
            String cursor = text(args, "cursor", 64);
            query = sessions.remove(cursor);
            if (query == null) throw stale("Recipe cursor expired, was consumed, or was invalidated.");
            try { query.validate(world, sessionId, dimension, inventory, tiles); }
            catch (BridgeException unavailable) {
                if ("stale_query".equals(unavailable.code)) throw unavailable;
                throw stale("The recipe cursor binding is no longer available: " + unavailable.code);
            }
        } else {
            boolean coordinates = args.has("x") || args.has("y") || args.has("z");
            if (args.has("map") == coordinates) throw new BridgeException("invalid_args", "Choose a public map name or complete machine x/y/z coordinates.");
            Machine machine = coordinates ? new Machine(tiles, args) : null;
            Object map = machine == null ? api.registry().get(text(args, "map", 256)) : machine.map;
            if (map == null || !api.map.isInstance(map)) throw new BridgeException("not_found", "The actual public recipe map is unavailable.");
            if (api.registry().get(api.mapName(map)) != map) throw new BridgeException("unsupported", "Machine recipe map is not the actual publicly registered map.");
            if (!api.visibleMap(map)) throw new BridgeException("unsupported", "This map has no public NEI recipe registration.");
            query = new Session(world, sessionId, dimension, inventory, map, machine,
                    filter(args, "input", inventory), filter(args, "output", inventory), now);
        }
        query.lastAccess = now;
        try { return page(query, limit, started); }
        catch (ConcurrentModificationException changed) { throw stale("Recipe collections changed while the cursor was being read."); }
    }

    /** One natural public match; an expected output never changes the backend's selection. */
    private JsonObject backendMatch(int dimension, InventoryPlayer inventory, TileLookup tiles, JsonObject args, long started) {
        keys(args, "mode", "x", "y", "z", "dimension", "inputs", "fluidInputs", "output", "fluids", "specialSlot");
        if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != dimension)
            throw new BridgeException("stale_query", "Backend machine dimension does not match the actual queried world.");
        if (args.has("fluids") && (!args.get("fluids").isJsonArray() || args.getAsJsonArray("fluids").size() != 0))
            throw new BridgeException("unsupported", "Legacy fluids must be empty; use finite fluidInputs registry sources.");
        if (args.has("specialSlot") && !args.get("specialSlot").isJsonNull())
            throw new BridgeException("unsupported", "Backend match currently supports an empty special slot only.");
        ItemStack[] inputs = backendInputs(args);
        FluidStack[] fluidInputs = backendFluidInputs(args);
        if (inputs.length == 0 && fluidInputs.length == 0)
            throw new BridgeException("invalid_args", "Backend match requires at least one finite item or fluid input.");
        Filter output = filter(args, "output", inventory);
        if (output != null && output.stack.getMetadata() == OreDictionary.WILDCARD_VALUE)
            throw new BridgeException("unsupported", "Backend output verification requires exact metadata, not a wildcard.");
        Machine machine = new Machine(tiles, args);
        if (!api.map.isInstance(machine.map) || api.registry().get(api.mapName(machine.map)) != machine.map)
            throw new BridgeException("unsupported", "Machine recipe map is not the actual publicly registered map.");
        Object voltageValue = machine.inputVoltage(), amperageValue = AdapterAccess.call(machine.map, "getAmperage");
        if (!(voltageValue instanceof Number) || !(amperageValue instanceof Number))
            throw new BridgeException("unsupported", "Public machine input voltage or recipe-map amperage is unavailable.");
        long voltage = ((Number) voltageValue).longValue(), amperage = ((Number) amperageValue).longValue();
        if (voltage <= 0 || amperage <= 0 || voltage > Long.MAX_VALUE / amperage)
            throw new BridgeException("unsupported", "Public machine voltage and map amperage must form a positive non-overflowing native bound.");
        Object backend = AdapterAccess.call(machine.map, "getBackend");
        Object overwrite = AdapterAccess.callOn(backend, BACKEND, "doesOverwriteFindRecipe", new Class<?>[0]);
        if (!(overwrite instanceof Boolean)) throw new BridgeException("unsupported", "Public backend lookup coverage is unavailable.");
        Object recipe = api.find(machine.map, inputs, fluidInputs, voltage);
        machine.validate(tiles);
        if (api.registry().get(api.mapName(machine.map)) != machine.map || AdapterAccess.call(machine.map, "getBackend") != backend
                || !voltageValue.equals(machine.inputVoltage()) || !amperageValue.equals(AdapterAccess.call(machine.map, "getAmperage")))
            throw stale("The actual machine, public map, backend or input voltage changed during lookup.");
        JsonArray recipes = new JsonArray(); JsonObject validation = new JsonObject();
        boolean payloadPartial = false, candidateValidated = false; Match outputMatch = Match.UNKNOWN;
        long payloadElapsed = 0L;
        if (recipe != null) {
            if (AdapterAccess.type(recipe, GTRecipeAdapter.RECIPE) == null) throw new BridgeException("adapter_error", "Public backend returned a non-GTRecipe candidate.");
            if (!Boolean.FALSE.equals(AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "mHidden", false)))
                throw new BridgeException("unsupported", "The first native backend candidate is hidden or its public visibility is unavailable; no later candidate was selected.");
            Object category = AdapterAccess.callOn(recipe, GTRecipeAdapter.RECIPE, "getRecipeCategory", new Class<?>[0]);
            if (category != null && !api.visibleCategory(category, machine.map))
                throw new BridgeException("unsupported", "The first native backend candidate belongs to a hidden public category; no later candidate was selected.");
            Object equal = AdapterAccess.callOn(recipe, GTRecipeAdapter.RECIPE, "isRecipeInputEqual",
                    new Class<?>[] {boolean.class, boolean.class, FluidStack[].class, ItemStack[].class}, false, false, copy(fluidInputs), copy(inputs));
            if (!(equal instanceof Boolean)) throw new BridgeException("unsupported", "Public non-consuming input comparison is unavailable.");
            boolean enabled = Boolean.TRUE.equals(AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "mEnabled", false));
            boolean notFake = Boolean.FALSE.equals(AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "mFakeRecipe", false));
            Object eutValue = AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "mEUt", false);
            if (!(eutValue instanceof Number)) throw new BridgeException("unsupported", "Public recipe EU/t is unavailable.");
            long eut = ((Number) eutValue).longValue();
            boolean voltageCompatible = eut >= 0 && eut <= voltage * amperage;
            // Cold public API/native find/input validation can exceed a soft budget and cannot be interrupted.
            // Start the single candidate's output check + raw DTO budget only after those calls finish.
            long payloadStarted = System.nanoTime();
            if (output != null) outputMatch = backendOutputMatch(recipe, output.stack, payloadStarted);
            JsonObject data = GTRecipeAdapter.readBackendPublic(recipe, payloadStarted);
            payloadElapsed = Math.max(0L, System.nanoTime() - payloadStarted);
            data.addProperty("candidateSource", "public_backend_find"); data.addProperty("registeredCatalogMembershipValidated", false);
            data.addProperty("candidateOrdinal", 0); data.addProperty("categoryVisibilityVerified", category != null);
            data.add("category", category == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(api.categoryName(category)));
            recipes.add(data); payloadPartial = payloadElapsed >= BACKEND_PAYLOAD_BUDGET_NANOS
                    || data.get("softBudgetPartial").getAsBoolean() || data.has("byteLimited") || data.get("metadataTruncated").getAsBoolean();
            for (String kind : new String[] {"items", "fluids"}) for (String group : new String[] {"inputs", "outputs"})
                payloadPartial |= data.getAsJsonObject(kind).getAsJsonObject(group).get("truncated").getAsBoolean();
            validation.addProperty("inputMatch", (Boolean) equal); validation.addProperty("enabled", enabled); validation.addProperty("notFake", notFake);
            validation.addProperty("voltageCompatible", voltageCompatible); validation.addProperty("singleVoltageCompatible", eut >= 0 && eut <= voltage);
            candidateValidated = (Boolean) equal && enabled && notFake && voltageCompatible && (output == null || outputMatch == Match.YES);
        } else {
            for (String key : new String[] {"inputMatch", "enabled", "notFake", "voltageCompatible", "singleVoltageCompatible"}) validation.add(key, JsonNull.INSTANCE);
        }
        validation.addProperty("outputChecked", output != null);
        validation.add("outputMatch", output == null || outputMatch == Match.UNKNOWN ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(outputMatch == Match.YES));
        validation.addProperty("outputUnknown", output != null && recipe != null && outputMatch == Match.UNKNOWN);
        validation.addProperty("scope", "Public input/count/flags and native voltage x map-amperage bound only; custom machine conditions, output space, energy, overclock and execution remain unvalidated.");
        JsonObject query = new JsonObject(); JsonArray sources = new JsonArray();
        for (ItemStack input : inputs) sources.add(GTRecipeAdapter.value(input, 0));
        JsonArray fluids = new JsonArray();
        for (FluidStack input : fluidInputs) fluids.add(GTRecipeAdapter.value(input, 0));
        query.add("inputs", sources); query.add("fluids", fluids); query.add("specialSlot", JsonNull.INSTANCE);
        if (output != null) query.add("output", GTRecipeAdapter.value(output.stack, 0));
        query.addProperty("inputVoltage", voltage); query.addProperty("mapAmperage", amperage); query.addProperty("nativeVoltageBound", voltage * amperage);
        query.add("inputVoltageEvidence", machine.voltageEvidence(dimension, voltage));
        query.addProperty("caching", false); query.addProperty("ignoreStackSizes", false);
        JsonObject backendData = new JsonObject(); backendData.addProperty("class", JsonData.bounded(backend.getClass().getName(), 256));
        backendData.addProperty("doesOverwriteFindRecipe", (Boolean) overwrite); backendData.addProperty("dynamicPossible", true);
        JsonObject result = new JsonObject(); result.addProperty("mode", "backend_match"); result.addProperty("source", "public_backend_find");
        result.addProperty("lookupSource", "public_backend_find");
        result.add("recipeMap", api.describeMap(machine.map)); machine.description.addProperty("inputVoltage", voltage); result.add("machine", machine.description);
        result.add("backend", backendData); result.add("query", query); result.add("recipes", recipes); result.add("validation", validation);
        result.addProperty("matched", recipe != null); result.addProperty("candidateValidated", candidateValidated);
        result.addProperty("filterIncomplete", output != null && recipe != null && outputMatch == Match.UNKNOWN);
        result.addProperty("lookupComplete", !payloadPartial && (output == null || recipe == null || outputMatch != Match.UNKNOWN));
        result.addProperty("hasMore", false); result.add("nextCursor", JsonNull.INSTANCE); result.addProperty("complete", false); result.addProperty("partial", true);
        result.addProperty("registeredCatalogComplete", false); result.addProperty("nonAtomic", true); result.addProperty("executionValidated", false);
        result.addProperty("stopReason", recipe == null ? "no_backend_match" : "first_backend_match");
        JsonObject coverage = new JsonObject();
        coverage.addProperty("scope", "One public backend-first-match candidate for copied complete item and fluid inputs; dynamic/fallback results need not occur in a registered NEI catalog.");
        coverage.addProperty("selection", "Natural find() order, actual public controller/tile voltage getter and current native map amperage; lookup bound only, not actual processing power or execution. No expected-output predicate or private cached recipe.");
        coverage.addProperty("visibility", "mHidden=false and a non-HIDE public category when present; a null category can represent an unregistered dynamic candidate. registerNEI remains map metadata.");
        coverage.addProperty("ordinal", "candidateOrdinal=0 is this lookup's first native candidate, never a registered categoryRecipeIndex.");
        coverage.addProperty("matching", "Ordered 0..8 untagged item registry templates with positive counts <=64 and 0..8 exact fluid registry templates with positive mB <=2147483647, with at least one total input; empty special slot. Public find and isRecipeInputEqual(false,false) use independent complete copies. Fluid templates are lookup evidence, not actual tank supply or processing proof.");
        coverage.addProperty("metadata", "Existing bounded raw DTO and known public condition keys; unknown/custom conditions remain unvalidated.");
        result.add("coverage", coverage); JsonObject limits = new JsonObject(); limits.addProperty("inputStacks", 8); limits.addProperty("fluidInputs", 8); limits.addProperty("fluidAmountMb", Integer.MAX_VALUE); limits.addProperty("results", 1); limits.addProperty("resultBytes", BYTE_LIMIT); result.add("limits", limits);
        JsonObject budget = new JsonObject(); long elapsed = Math.max(0L, System.nanoTime() - started);
        budget.addProperty("elapsedMicros", elapsed / 1000); budget.addProperty("payloadElapsedMicros", payloadElapsed / 1000);
        budget.addProperty("softBudgetMicros", BACKEND_PAYLOAD_BUDGET_NANOS / 1000);
        budget.addProperty("softBudgetScope", "backend_candidate_output_check_and_raw_dto");
        budget.addProperty("softBudgetExceeded", payloadElapsed >= BACKEND_PAYLOAD_BUDGET_NANOS); budget.addProperty("nativeFindCalls", 1); budget.addProperty("nativeCallInterruptible", false); result.add("budget", budget);
        if (utf8(result) > BYTE_LIMIT) throw new BridgeException("too_large", "Backend recipe match exceeded its bounded result budget.");
        return result;
    }

    private static ItemStack[] backendInputs(JsonObject args) {
        if (!args.has("inputs")) return new ItemStack[0];
        if (!args.get("inputs").isJsonArray() || args.getAsJsonArray("inputs").size() > 8)
            throw new BridgeException("invalid_args", "Backend inputs must contain 0 through 8 ordered registry item sources.");
        ItemStack[] inputs = new ItemStack[args.getAsJsonArray("inputs").size()];
        for (int index = 0; index < inputs.length; index++) {
            JsonElement value = args.getAsJsonArray("inputs").get(index);
            if (!value.isJsonObject()) throw new BridgeException("invalid_args", "Backend input must be a registry item source object.");
            JsonObject source = value.getAsJsonObject(); keys(source, "source", "registryName", "metadata", "count");
            if (!"registry".equals(text(source, "source", 16))) throw new BridgeException("unsupported", "Backend inputs currently support untagged registry templates only.");
            String name = text(source, "registryName", 256); Object item = Item.itemRegistry.getObject(name);
            if (!(item instanceof Item)) throw new BridgeException("not_found", "Backend input registry identity was not found.");
            int metadata = source.has("metadata") ? JsonData.integer(source, "metadata", 0, 32766) : 0;
            inputs[index] = new ItemStack((Item) item, JsonData.integer(source, "count", 1, 64), metadata);
        }
        return inputs;
    }
    private static FluidStack[] backendFluidInputs(JsonObject args) {
        if (!args.has("fluidInputs")) return new FluidStack[0];
        if (!args.get("fluidInputs").isJsonArray() || args.getAsJsonArray("fluidInputs").size() > 8)
            throw new BridgeException("invalid_args", "Backend fluidInputs must contain 0 through 8 ordered fluid registry sources.");
        FluidStack[] inputs = new FluidStack[args.getAsJsonArray("fluidInputs").size()];
        for (int index = 0; index < inputs.length; index++) {
            JsonElement value = args.getAsJsonArray("fluidInputs").get(index);
            if (!value.isJsonObject()) throw new BridgeException("invalid_args", "Backend fluid input must be a fluid registry source object.");
            JsonObject source = value.getAsJsonObject(); keys(source, "fluid", "amount");
            String name = text(source, "fluid", 128);
            int amount = JsonData.integer(source, "amount", 1, Integer.MAX_VALUE);
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid == null || !name.equals(FluidRegistry.getFluidName(fluid)) || FluidRegistry.getFluid(name) != fluid)
                throw new BridgeException("not_found", "Backend fluid input exact registry identity was not found.");
            inputs[index] = new FluidStack(fluid, amount);
        }
        return inputs;
    }
    private static FluidStack[] copy(FluidStack[] stacks) {
        FluidStack[] copies = new FluidStack[stacks.length];
        for (int index = 0; index < copies.length; index++) copies[index] = stacks[index].copy();
        return copies;
    }
    private static ItemStack[] copy(ItemStack[] stacks) {
        ItemStack[] copies = new ItemStack[stacks.length];
        for (int index = 0; index < copies.length; index++) copies[index] = stacks[index].copy();
        return copies;
    }
    private static Match backendOutputMatch(Object recipe, ItemStack expected, long started) {
        Object raw = AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "mOutputs", false);
        if (!(raw instanceof ItemStack[])) return Match.UNKNOWN;
        ItemStack[] outputs = (ItemStack[]) raw;
        for (int index = 0; index < Math.min(outputs.length, GTRecipeAdapter.STACK_LIMIT); index++) {
            if (index > 0 && System.nanoTime() - started >= BACKEND_PAYLOAD_BUDGET_NANOS) return Match.UNKNOWN;
            ItemStack output = outputs[index];
            if (output != null && output.getItem() == expected.getItem() && output.getMetadata() == expected.getMetadata() && ItemStack.areItemStackTagsEqual(output, expected)) return Match.YES;
        }
        return outputs.length > GTRecipeAdapter.STACK_LIMIT ? Match.UNKNOWN : Match.NO;
    }

    private JsonObject page(Session query, int limit, long started) {
        JsonArray recipes = new JsonArray();
        int scanned = 0, bytes = 12288, unknown = 0;
        boolean byteLimited = false, payloadPartial = false;
        String stop = "complete";
        while (scanned < CANDIDATE_LIMIT && recipes.size() < limit) {
            if (scanned > 0 && expired(started)) { stop = "soft_budget"; break; }
            Candidate candidate = query.pending == null ? query.next() : query.pending;
            query.pending = null;
            if (candidate == null) break;
            scanned++;
            if (Boolean.TRUE.equals(AdapterAccess.field(candidate.recipe, GTRecipeAdapter.RECIPE, "mHidden", false))) continue;
            Match input = matches(candidate.recipe, query.input, true, started);
            if (input == Match.NO) continue;
            Match output = matches(candidate.recipe, query.output, false, started);
            if (output == Match.NO) continue;
            if (input == Match.UNKNOWN || output == Match.UNKNOWN) { query.filterIncomplete = true; unknown++; continue; }
            JsonObject recipe = GTRecipeAdapter.readPublic(candidate.recipe, started);
            recipe.addProperty("category", api.categoryName(candidate.category.category));
            recipe.addProperty("categoryRecipeIndex", candidate.index);
            int size = utf8(recipe) + 128;
            if (bytes + size > BYTE_LIMIT - 8192) { query.pending = candidate; byteLimited = true; stop = "byte_limit"; break; }
            bytes += size; payloadPartial |= recipe.get("softBudgetPartial").getAsBoolean() || recipe.has("byteLimited");
            recipes.add(recipe);
        }
        boolean more = query.pending != null || query.hasMore();
        if (more && "complete".equals(stop)) stop = recipes.size() >= limit ? "result_limit" : "candidate_limit";
        String nextCursor = null;
        if (more) {
            while (sessions.size() >= SESSION_LIMIT) sessions.remove(sessions.keySet().iterator().next());
            nextCursor = UUID.randomUUID().toString(); sessions.put(nextCursor, query);
        }
        JsonObject result = new JsonObject();
        result.add("recipeMap", api.describeMap(query.map));
        if (query.machine != null) result.add("machine", query.machine.description);
        JsonObject filter = new JsonObject();
        if (query.input != null) filter.add("input", GTRecipeAdapter.value(query.input.stack, 0));
        if (query.output != null) filter.add("output", GTRecipeAdapter.value(query.output.stack, 0));
        result.add("query", filter); result.add("recipes", recipes);
        result.addProperty("scannedCandidates", scanned); result.addProperty("totalScanned", query.totalScanned);
        result.addProperty("unknownCandidates", unknown); result.addProperty("filterIncomplete", query.filterIncomplete);
        result.addProperty("limit", limit); result.addProperty("hasMore", more);
        result.add("nextCursor", nextCursor == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(nextCursor));
        result.addProperty("partial", more || query.filterIncomplete || payloadPartial);
        result.addProperty("complete", !more && !query.filterIncomplete && !payloadPartial);
        result.addProperty("byteLimited", byteLimited); result.addProperty("stopReason", stop);
        result.addProperty("nonAtomic", true); result.addProperty("executionValidated", false);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("scope", "Current registered recipes of the selected actual GT RecipeMap; dynamic backend-generated recipes and machine execution checks are not queried.");
        coverage.addProperty("visibility", "Public registerNEI map, non-HIDE categories and mHidden=false recipes only; fake/disabled status is retained without promising execution.");
        coverage.addProperty("matching", "Actual Item identity and metadata/wildcard; input NBT follows recipe sensitivity and output tags must match. Input declared alternatives/ore IDs are bounded. Counts are ignored; this is item containment, not a complete-input recipe match.");
        coverage.addProperty("metadata", "Known public GTRecipeConstants condition keys only; metadataUnknown and publicMetadataComplete describe unknown/truncated values.");
        coverage.addProperty("cursor", "Single-use, session/world/inventory/map/filter bound, 30 second TTL; structural collection changes invalidate. Recipe fields and non-structural element replacement remain non-atomic.");
        result.add("coverage", coverage);
        JsonObject limits = new JsonObject();
        limits.addProperty("candidates", CANDIDATE_LIMIT); limits.addProperty("results", RESULT_LIMIT); limits.addProperty("resultBytes", BYTE_LIMIT);
        limits.addProperty("categories", CATEGORY_LIMIT); limits.addProperty("maps", MAP_LIMIT); limits.addProperty("matchStacks", MATCH_LIMIT);
        limits.addProperty("activeCursors", SESSION_LIMIT); limits.addProperty("cursorTtlSeconds", 30);
        result.add("limits", limits);
        JsonObject budget = new JsonObject(); long elapsed = Math.max(0L, System.nanoTime() - started);
        budget.addProperty("elapsedMicros", elapsed / 1000); budget.addProperty("softBudgetMicros", SOFT_BUDGET_NANOS / 1000);
        budget.addProperty("softBudgetExceeded", elapsed >= SOFT_BUDGET_NANOS); result.add("budget", budget);
        if (utf8(result) > BYTE_LIMIT) {
            if (nextCursor != null) sessions.remove(nextCursor);
            throw new BridgeException("too_large", "Recipe query exceeded its bounded result budget.");
        }
        return result;
    }

    private final class Session {
        final Object world, map, backend;
        final String session;
        final int dimension;
        final InventoryPlayer inventory;
        final Machine machine;
        final Filter input, output;
        final List<CategoryState> categories = new ArrayList<CategoryState>();
        int categoryIndex, totalScanned;
        long lastAccess;
        boolean filterIncomplete;
        Candidate pending;
        Session(Object world, String session, int dimension, InventoryPlayer inventory, Object map, Machine machine,
                Filter input, Filter output, long now) {
            this.world = world; this.session = session; this.dimension = dimension; this.inventory = inventory;
            this.map = map; this.machine = machine; this.input = input; this.output = output; lastAccess = now;
            backend = AdapterAccess.call(map, "getBackend");
            Map<?, ?> views = api.categories(backend);
            if (views.size() > CATEGORY_LIMIT) throw new BridgeException("unsupported", "Recipe map exceeds the bounded category limit; no complete coverage is claimed.");
            for (Map.Entry<?, ?> entry : views.entrySet()) categories.add(new CategoryState(entry.getKey(), entry.getValue(), map));
        }
        void validate(Object currentWorld, String currentSession, int currentDimension, InventoryPlayer currentInventory, TileLookup tiles) {
            if (world != currentWorld || !session.equals(currentSession) || dimension != currentDimension || inventory != currentInventory)
                throw stale("Recipe cursor world, session or player binding changed.");
            if (api.registry().get(api.mapName(map)) != map || !api.visibleMap(map) || AdapterAccess.call(map, "getBackend") != backend)
                throw stale("The actual registered recipe map changed or became hidden.");
            if (machine != null) machine.validate(tiles);
            if (input != null) input.validate(inventory);
            if (output != null) output.validate(inventory);
            Map<?, ?> current = api.categories(backend);
            if (current.size() != categories.size()) throw stale("Recipe categories changed.");
            try { for (CategoryState category : categories) category.validate(current, map); }
            catch (ConcurrentModificationException changed) { throw stale("Recipe collections changed."); }
        }
        Candidate next() {
            while (categoryIndex < categories.size()) {
                CategoryState category = categories.get(categoryIndex);
                if (!category.visible || !category.scan.hasNext()) { categoryIndex++; continue; }
                Object recipe = category.scan.next(); int index = category.index++;
                if (AdapterAccess.type(recipe, GTRecipeAdapter.RECIPE) == null)
                    throw new BridgeException("adapter_error", "Public recipe category returned a non-GTRecipe entry.");
                totalScanned++; return new Candidate(recipe, category, index);
            }
            return null;
        }
        boolean hasMore() {
            while (categoryIndex < categories.size()) {
                CategoryState category = categories.get(categoryIndex);
                if (category.visible && category.scan.hasNext()) return true;
                categoryIndex++;
            }
            return false;
        }
    }

    private final class CategoryState {
        final Object category;
        final Collection<?> recipes;
        final int size;
        final boolean visible;
        final Iterator<?> scan;
        Iterator<?> guard;
        int index;
        CategoryState(Object category, Object recipes, Object map) {
            if (!(recipes instanceof Collection<?>)) throw new BridgeException("unsupported", "Public category recipe collection is unavailable.");
            this.category = category; this.recipes = (Collection<?>) recipes; size = this.recipes.size();
            visible = api.visibleCategory(category, map); scan = this.recipes.iterator(); guard = this.recipes.iterator();
        }
        void validate(Map<?, ?> current, Object map) {
            if (current.get(category) != recipes || recipes.size() != size || api.visibleCategory(category, map) != visible)
                throw stale("Recipe collection identity, size or visibility changed.");
            // A separate retained iterator detects same-size structural remove/add changes, even in completed categories.
            try { guard.next(); }
            catch (NoSuchElementException exhausted) { guard = recipes.iterator(); }
        }
    }

    private final class Machine {
        final int x, y, z;
        final Object tile, meta, map;
        final Object voltageOwner;
        final String voltageApiClass, voltageApiMethod, voltageSource;
        final JsonElement mode;
        final JsonObject description = new JsonObject();
        Machine(TileLookup tiles, JsonObject args) {
            x = JsonData.integer(args, "x", -30000000, 30000000); y = JsonData.integer(args, "y", 0, 255); z = JsonData.integer(args, "z", -30000000, 30000000);
            tile = tiles.at(x, y, z);
            if (AdapterAccess.type(tile, BASE_TILE) == null) throw new BridgeException("unsupported", "Target is not a loaded public GT machine.");
            meta = AdapterAccess.call(tile, "getMetaTileEntity");
            if (meta == null || !api.workable.isInstance(meta)) throw new BridgeException("unsupported", "Machine has no public RecipeMapWorkable API.");
            boolean multiblock = AdapterAccess.type(meta, MULTI) != null;
            voltageOwner = multiblock ? meta : tile;
            voltageApiClass = multiblock ? MULTI : BASE_TILE;
            voltageApiMethod = multiblock ? "getMaxInputVoltage" : "getInputVoltage";
            voltageSource = multiblock ? "public_multiblock_controller_api" : "public_base_tile_api";
            map = AdapterAccess.callOn(meta, WORKABLE, "getRecipeMap", new Class<?>[0]);
            if (map == null) throw new BridgeException("unsupported", "Current machine mode exposes no registered RecipeMap.");
            mode = optionalMode(meta, "getMachineMode");
            description.addProperty("x", x); description.addProperty("y", y); description.addProperty("z", z);
            description.addProperty("metaClass", JsonData.bounded(meta.getClass().getName(), 256));
            description.add("metaTileId", GTRecipeAdapter.value(AdapterAccess.call(tile, "getMetaTileID"), 0));
            description.addProperty("currentRecipeMap", api.mapName(map)); description.add("mode", mode);
            description.add("modeName", optionalMode(meta, "getMachineModeName"));
            JsonArray available = new JsonArray();
            Object maps = AdapterAccess.callOn(meta, WORKABLE, "getAvailableRecipeMaps", new Class<?>[0]);
            if (!(maps instanceof Collection<?>)) throw new BridgeException("unsupported", "Machine available recipe maps are unavailable.");
            int count = 0;
            for (Object candidate : (Collection<?>) maps) {
                if (++count > MAP_LIMIT) break;
                if (candidate != null && api.map.isInstance(candidate) && api.registry().get(api.mapName(candidate)) == candidate && api.visibleMap(candidate)) available.add(api.describeMap(candidate));
            }
            description.add("availableRecipeMaps", available); description.addProperty("availableMapsTruncated", ((Collection<?>) maps).size() > MAP_LIMIT);
            description.addProperty("scope", "Public current mode and RecipeMapWorkable maps; structure and recipe execution have not been validated.");
        }
        Object inputVoltage() {
            // Invoke the common public method virtually, preserving each actual controller's native sum/override semantics.
            return AdapterAccess.callOn(voltageOwner, voltageApiClass, voltageApiMethod, new Class<?>[0]);
        }
        JsonObject voltageEvidence(int dimension, long voltage) {
            JsonObject result = new JsonObject(), owner = new JsonObject();
            result.addProperty("schemaVersion", 1); result.addProperty("source", voltageSource);
            result.addProperty("scope", "native_lookup_bound_only");
            result.addProperty("apiClass", voltageApiClass); result.addProperty("apiMethod", voltageApiMethod); result.addProperty("value", voltage);
            for (String key : new String[] {"x", "y", "z", "metaTileId", "metaClass", "currentRecipeMap"}) owner.add(key, description.get(key));
            owner.addProperty("dimension", dimension); result.add("owner", owner);
            result.addProperty("semantics", "Actual virtual public getter result; no hatch enumeration, tier guess or power reconstruction. Custom processing conditions and execution remain unvalidated.");
            return result;
        }
        void validate(TileLookup tiles) {
            if (tiles.at(x, y, z) != tile || AdapterAccess.call(tile, "getMetaTileEntity") != meta
                    || AdapterAccess.callOn(meta, WORKABLE, "getRecipeMap", new Class<?>[0]) != map || !mode.equals(optionalMode(meta, "getMachineMode")))
                throw stale("Machine object or current mode changed before recipe continuation.");
        }
    }

    private static JsonElement optionalMode(Object meta, String name) {
        try { meta.getClass().getMethod(name); }
        catch (NoSuchMethodException absent) { return JsonNull.INSTANCE; }
        return GTRecipeAdapter.value(AdapterAccess.call(meta, name), 0);
    }

    private static final class Candidate {
        final Object recipe;
        final CategoryState category;
        final int index;
        Candidate(Object recipe, CategoryState category, int index) { this.recipe = recipe; this.category = category; this.index = index; }
    }

    private static final class Filter {
        final ItemStack stack;
        final int inventorySlot;
        final String registryName;
        int[] oreIds;
        Filter(ItemStack stack, int inventorySlot, String registryName) { this.stack = stack.copy(); this.inventorySlot = inventorySlot; this.registryName = registryName; }
        void validate(InventoryPlayer inventory) {
            if (inventorySlot >= 0) {
                ItemStack current = inventory.getStackInSlot(inventorySlot);
                if (current == null || current.getItem() != stack.getItem() || current.getMetadata() != stack.getMetadata() || !ItemStack.areItemStackTagsEqual(current, stack))
                    throw stale("The actual inventory query item identity changed.");
            } else if (Item.itemRegistry.getObject(registryName) != stack.getItem()) throw stale("The query item registry binding changed.");
        }
    }

    /** Same public inventory/registry sources as NEI open; no current GUI or caller-provided NBT reconstruction. */
    private static Filter filter(JsonObject args, String key, InventoryPlayer inventory) {
        if (!args.has(key)) return null;
        if (!args.get(key).isJsonObject()) throw new BridgeException("invalid_args", key + " must be an item source object.");
        JsonObject source = args.getAsJsonObject(key); String type = text(source, "source", 16);
        if ("registry".equals(type)) {
            keys(source, "source", "registryName", "metadata"); String name = text(source, "registryName", 256);
            Object item = Item.itemRegistry.getObject(name);
            if (!(item instanceof Item)) throw new BridgeException("not_found", "Query item registry identity was not found.");
            int metadata = source.has("metadata") ? JsonData.integer(source, "metadata", 0, 32767) : 0;
            return new Filter(new ItemStack((Item) item, 1, metadata), -1, name);
        }
        if ("inventory".equals(type)) {
            keys(source, "source", "slot"); int slot = JsonData.integer(source, "slot", 0, 255);
            if (inventory == null || slot >= inventory.getSizeInventory()) throw new BridgeException("not_found", "Query inventory slot is unavailable.");
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null) throw new BridgeException("not_found", "Query inventory slot is empty.");
            return new Filter(stack, slot, null);
        }
        throw new BridgeException("invalid_args", "Item source must be registry or inventory.");
    }

    private enum Match { YES, NO, UNKNOWN }
    private static Match matches(Object recipe, Filter filter, boolean input, long started) {
        if (filter == null) return Match.YES;
        Object raw = AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, input ? "mInputs" : "mOutputs", false);
        ItemStack[] stacks = raw instanceof ItemStack[] ? (ItemStack[]) raw : new ItemStack[0];
        boolean nbt = !input || Boolean.TRUE.equals(AdapterAccess.field(recipe, GTRecipeAdapter.RECIPE, "isNBTSensitive", false));
        boolean incomplete = stacks.length > GTRecipeAdapter.STACK_LIMIT; int visited = 0;
        for (int i = 0; i < Math.min(stacks.length, GTRecipeAdapter.STACK_LIMIT); i++) {
            if (visited++ >= MATCH_LIMIT) return Match.UNKNOWN;
            if (same(stacks[i], filter.stack, nbt)) return Match.YES;
        }
        if (input && AdapterAccess.type(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt") != null) {
            Object rawAlt = AdapterAccess.field(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt", "mOreDictAlt", false);
            ItemStack[][] alternatives = rawAlt instanceof ItemStack[][] ? (ItemStack[][]) rawAlt : new ItemStack[0][];
            incomplete |= alternatives.length > GTRecipeAdapter.STACK_LIMIT;
            for (int i = 0; i < Math.min(alternatives.length, GTRecipeAdapter.STACK_LIMIT); i++) {
                if (alternatives[i] == null) continue;
                incomplete |= alternatives[i].length > NeiDataAdapter.ALTERNATIVE_LIMIT;
                for (int j = 0; j < Math.min(alternatives[i].length, NeiDataAdapter.ALTERNATIVE_LIMIT); j++) {
                    if (visited++ >= MATCH_LIMIT || (visited > 1 && expired(started))) return Match.UNKNOWN;
                    if (same(alternatives[i][j], filter.stack, nbt)) return Match.YES;
                }
            }
            Object rawIds = AdapterAccess.field(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt", "mOreDictIds", false);
            int[] ids = rawIds instanceof int[] ? (int[]) rawIds : new int[0];
            if (ids.length > 0) {
                if (filter.oreIds == null) filter.oreIds = OreDictionary.getOreIDs(filter.stack);
                incomplete |= ids.length > GTRecipeAdapter.STACK_LIMIT || filter.oreIds.length > GTRecipeAdapter.STACK_LIMIT;
                for (int i = 0; i < Math.min(ids.length, GTRecipeAdapter.STACK_LIMIT); i++) if (ids[i] >= 0) {
                    for (int j = 0; j < Math.min(filter.oreIds.length, GTRecipeAdapter.STACK_LIMIT); j++) {
                        if (visited++ >= MATCH_LIMIT || (visited > 1 && expired(started))) return Match.UNKNOWN;
                        if (ids[i] == filter.oreIds[j] && (!nbt || i < stacks.length && sameTags(stacks[i], filter.stack))) return Match.YES;
                    }
                }
            }
        }
        return incomplete ? Match.UNKNOWN : Match.NO;
    }
    private static boolean same(ItemStack recipe, ItemStack query, boolean nbt) {
        return recipe != null && recipe.getItem() == query.getItem()
                && (recipe.getMetadata() == query.getMetadata() || recipe.getMetadata() == OreDictionary.WILDCARD_VALUE || query.getMetadata() == OreDictionary.WILDCARD_VALUE)
                && (!nbt || sameTags(recipe, query));
    }
    private static boolean sameTags(ItemStack recipe, ItemStack query) { return recipe != null && ItemStack.areItemStackTagsEqual(recipe, query); }

    private static final class Api {
        final Class<?> map, workable;
        Api() {
            try {
                ClassLoader loader = RecipeQueryAdapter.class.getClassLoader();
                map = Class.forName(MAP, false, loader); workable = Class.forName(WORKABLE, false, loader);
            } catch (ClassNotFoundException | LinkageError unavailable) { throw new BridgeException("unsupported", "Public GT recipe query APIs are unavailable."); }
        }
        Map<?, ?> registry() {
            try {
                Object registry = map.getField("ALL_RECIPE_MAPS").get(null);
                if (registry instanceof Map<?, ?>) return (Map<?, ?>) registry;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                throw new BridgeException("unsupported", "Public GT recipe-map registry is unavailable.");
            }
            throw new BridgeException("unsupported", "Public GT recipe-map registry has an unsupported shape.");
        }
        String mapName(Object map) { return String.valueOf(AdapterAccess.field(map, MAP, "unlocalizedName", false)); }
        String categoryName(Object category) { return String.valueOf(AdapterAccess.field(category, CATEGORY, "unlocalizedName", false)); }
        boolean visibleMap(Object map) {
            Object frontend = AdapterAccess.call(map, "getFrontend"), properties = AdapterAccess.call(frontend, "getNEIProperties");
            return Boolean.TRUE.equals(AdapterAccess.field(properties, "gregtech.api.recipe.NEIRecipeProperties", "registerNEI", false));
        }
        boolean visibleCategory(Object category, Object map) {
            if (AdapterAccess.field(category, CATEGORY, "recipeMap", false) != map) throw new BridgeException("unsupported", "Recipe category belongs to a different map.");
            Object supplier = AdapterAccess.field(category, CATEGORY, "settingSupplier", false);
            if (!(supplier instanceof Supplier<?>)) throw new BridgeException("unsupported", "Public NEI category setting is unavailable.");
            Object setting = ((Supplier<?>) supplier).get();
            if (!(setting instanceof Enum<?>)) throw new BridgeException("unsupported", "Public NEI category setting has an unsupported shape.");
            String name = ((Enum<?>) setting).name();
            if ("HIDE".equals(name)) return false;
            if (!"ENABLE".equals(name) && !"MERGE".equals(name)) throw new BridgeException("unsupported", "Public NEI category visibility is unknown.");
            return true;
        }
        Map<?, ?> categories(Object backend) {
            Object value = AdapterAccess.callOn(backend, BACKEND, "getRecipeCategoryMap", new Class<?>[0]);
            if (!(value instanceof Map<?, ?>)) throw new BridgeException("unsupported", "Public recipe category views are unavailable.");
            return (Map<?, ?>) value;
        }
        Object find(Object map, ItemStack[] inputs, FluidStack[] fluidInputs, long voltage) {
            String owner = "gregtech.api.recipe.FindRecipeQuery";
            Object query = AdapterAccess.callOn(map, MAP, "findRecipeQuery", new Class<?>[0]);
            if (AdapterAccess.type(query, owner) == null) throw new BridgeException("unsupported", "Public FindRecipeQuery is unavailable.");
            AdapterAccess.callOn(query, owner, "items", new Class<?>[] {ItemStack[].class}, (Object) copy(inputs));
            AdapterAccess.callOn(query, owner, "fluids", new Class<?>[] {FluidStack[].class}, (Object) copy(fluidInputs));
            AdapterAccess.callOn(query, owner, "specialSlot", new Class<?>[] {ItemStack.class}, (Object) null);
            AdapterAccess.callOn(query, owner, "voltage", new Class<?>[] {long.class}, voltage);
            AdapterAccess.callOn(query, owner, "caching", new Class<?>[] {boolean.class}, false);
            return AdapterAccess.callOn(query, owner, "find", new Class<?>[0]);
        }
        JsonObject describeMap(Object map) {
            String name = mapName(map); JsonObject result = new JsonObject();
            result.addProperty("name", JsonData.bounded(name, 256)); result.addProperty("text", JsonData.bounded(StatCollector.translateToLocal(name), 512));
            result.add("amperage", GTRecipeAdapter.value(AdapterAccess.call(map, "getAmperage"), 0)); result.addProperty("registerNEI", visibleMap(map));
            return result;
        }
    }
    private static String text(JsonObject args, String key, int maximum) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()) throw new BridgeException("invalid_args", key + " must be a string.");
        String value = args.get(key).getAsString();
        if (value.isEmpty() || value.length() > maximum) throw new BridgeException("invalid_args", key + " length must be 1.." + maximum + ".");
        return value;
    }
    private static void keys(JsonObject args, String... allowed) {
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            boolean found = false; for (String key : allowed) if (key.equals(entry.getKey())) { found = true; break; }
            if (!found) throw new BridgeException("invalid_args", "Unexpected recipe-query argument: " + entry.getKey());
        }
    }
    private static int utf8(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static boolean expired(long started) { return System.nanoTime() - started >= SOFT_BUDGET_NANOS; }
    private static BridgeException stale(String message) { return new BridgeException("stale_query", message); }
}
