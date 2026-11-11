package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.client.nei.ExpandedGtSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.RecipeCategorySetting;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMetadataKey;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

/** Public live category views and exact stack sources; no GUI, world construction or recipe execution. */
public final class RecipeQuerySelfTest {
    private static int checks;
    private static final Object WORLD = new Object();
    private static final RecipeQueryAdapter.TileLookup NO_TILES = (x, y, z) -> { throw new AssertionError("Named map query must not read a tile"); };
    public static int run() {
        checks = 0; Map<String, RecipeMap> original = new LinkedHashMap<>(RecipeMap.ALL_RECIPE_MAPS);
        try { publicData(); publicAnnotation(); identity(); boundsAndContinuation(); invalidation(); machines(); validation(); byteBudget(); backendMatches(); backendFluids(); backendVoltages(); backendValidation(); }
        finally { RecipeMap.ALL_RECIPE_MAPS.clear(); RecipeMap.ALL_RECIPE_MAPS.putAll(original); }
        return checks;
    }

    private static void publicData() {
        RecipeMap map = new RecipeMap("query.public"); Item item = new FixtureItem();
        GTRecipe real = recipe(item, 7); real.mInputs[0].stackSize = 0;
        real.mFluidInputs = new FluidStack[] {ExpandedGtSelfTest.fixtureFluid(new Fluid("query_fluid"), 2000)};
        real.mFluidOutputs = new FluidStack[] {ExpandedGtSelfTest.fixtureFluid(new Fluid("query_output"), 500)};
        real.metadata.put(GTRecipeConstants.COIL_HEAT, 1800);
        real.metadata.put(GTRecipeConstants.CLEANROOM, true);
        real.metadata.put(new RecipeMetadataKey<Object>(Object.class, "hidden_internal_identifier"), new Object() {
            @Override public String toString() { throw new AssertionError("Unknown condition must not be executed/serialized"); }
        });
        GTRecipe hidden = recipe(item, 1); hidden.mHidden = true;
        GTRecipe fake = recipe(item, 2); fake.mFakeRecipe = true; fake.mEnabled = false;
        map.backend.categories.put(map.defaultCategory, new ArrayList<>(Arrays.asList(real, hidden, fake)));
        RecipeCategory concealed = new RecipeCategory("concealed", map, () -> RecipeCategorySetting.HIDE);
        map.backend.categories.put(concealed, Collections.singletonList(recipe(item, 3)));
        RecipeCategory merged = new RecipeCategory("public.merged", map, () -> RecipeCategorySetting.MERGE);
        map.backend.categories.put(merged, Collections.singletonList(recipe(item, 4)));
        JsonObject result = read(new RecipeQueryAdapter(), mapArgs(map), null);
        check(result.getAsJsonArray("recipes").size() == 3 && result.get("complete").getAsBoolean(), "only NEI-visible default/merged recipes are returned without full-map copying or GUI handlers");
        JsonObject data = result.getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(data.get("rawEUt").getAsInt() == 100 && data.get("rawDurationTicks").getAsInt() == 80 && !data.get("executionValidated").getAsBoolean(), "registered EU/t and duration never promise actual machine execution");
        JsonObject input = first(data, "items", "inputs");
        check(input.getAsJsonObject("stack").get("count").getAsInt() == 0 && input.get("nonConsumedCatalyst").getAsBoolean(), "zero-count catalyst remains zero and is explicitly non-consumed");
        check(input.get("chance").getAsInt() == 10000 && first(data, "items", "outputs").get("chance").getAsInt() == 2500
                && first(data, "fluids", "inputs").get("chance").getAsInt() == 7500 && first(data, "fluids", "outputs").get("chance").getAsInt() == 5000,
                "all four actual item/fluid input/output probabilities retain the 10000 denominator");
        check(data.get("metadataUnknown").getAsBoolean() && !data.get("publicMetadataComplete").getAsBoolean() && !data.toString().contains("hidden_internal_identifier"), "private key identifier and unknown metadata value are withheld with explicit coverage");
        check(data.getAsJsonArray("metadata").get(0).getAsJsonObject().get("identifier").getAsString().equals("coil_heat")
                && data.getAsJsonArray("metadata").get(0).getAsJsonObject().get("value").getAsInt() == 1800, "public metadata constant identity supplies real condition value without private reflection");
        JsonObject fakeData = result.getAsJsonArray("recipes").get(1).getAsJsonObject();
        check(fakeData.get("fakeRecipe").getAsBoolean() && !fakeData.get("enabled").getAsBoolean(), "public fake and disabled recipes retain their actual status");
        RecipeMap secret = new RecipeMap("query.unregistered.nei", false);
        expect("unsupported", () -> read(new RecipeQueryAdapter(), mapArgs(secret), null));
        check(result.getAsJsonObject("recipeMap").get("registerNEI").getAsBoolean() && result.getAsJsonObject("coverage").get("visibility").getAsString().contains("mHidden=false"), "visibility gates are carried in the response");
    }

    private static void publicAnnotation() {
        GTRecipe recipe = recipe(new FixtureItem(), 0); recipe.metadata.put(GTRecipeConstants.RECYCLE, false);
        JsonObject data = GTRecipeAdapter.readPublic(recipe, start());
        JsonObject annotation = data.getAsJsonArray("metadata").get(0).getAsJsonObject();
        check(annotation.get("identifier").getAsString().equals("recycle")
                && annotation.get("publicKeyIdentity").getAsString().equals("gregtech.api.util.GTRecipeConstants.RECYCLE"), "only the actual public RECYCLE constant receives its identity marker");
        check(annotation.getAsJsonPrimitive("value").isBoolean() && !annotation.get("value").getAsBoolean()
                && data.get("metadataTotal").getAsInt() == 1 && data.get("metadataUnknownCount").getAsInt() == 0
                && data.get("publicMetadataComplete").getAsBoolean() && !data.get("executionValidated").getAsBoolean(), "typed false remains a complete public annotation without claiming execution validation");
        recipe.metadata.put(GTRecipeConstants.RECYCLE, true); data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.getAsJsonArray("metadata").get(0).getAsJsonObject().getAsJsonPrimitive("value").isBoolean()
                && data.getAsJsonArray("metadata").get(0).getAsJsonObject().get("value").getAsBoolean(), "reader preserves true for downstream policy rejection");
        recipe.metadata.put(GTRecipeConstants.RECYCLE, "false"); data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.getAsJsonArray("metadata").get(0).getAsJsonObject().getAsJsonPrimitive("value").isString(), "malformed string false is never coerced to Boolean false");
        recipe.metadata.put(GTRecipeConstants.RECYCLE, 0); data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.getAsJsonArray("metadata").get(0).getAsJsonObject().getAsJsonPrimitive("value").isNumber(), "numeric zero retains its distinct raw type");
        recipe.metadata.put(GTRecipeConstants.RECYCLE, null); data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.getAsJsonArray("metadata").get(0).getAsJsonObject().get("value").isJsonNull(), "missing annotation value is not treated as false");
        recipe.metadata.clear(); recipe.metadata.put(new RecipeMetadataKey<Boolean>(Boolean.class, "recycle"), false);
        data = GTRecipeAdapter.readPublic(recipe, start()); annotation = data.getAsJsonArray("metadata").get(0).getAsJsonObject();
        check(!annotation.has("identifier") && !annotation.has("publicKeyIdentity") && !annotation.get("available").getAsBoolean()
                && data.get("metadataUnknownCount").getAsInt() == 1 && !data.get("publicMetadataComplete").getAsBoolean(), "same-name cloned key remains unknown without private identifier or arbitrary serializer access");
        recipe.metadata.put(GTRecipeConstants.RECYCLE, false); data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.get("metadataTotal").getAsInt() == 2 && data.get("metadataUnknown").getAsBoolean()
                && !data.get("publicMetadataComplete").getAsBoolean(), "public annotation never hides an additional unknown metadata key");
        recipe.metadata.clear(); recipe.metadata.put(GTRecipeConstants.RECYCLE, false); recipe.metadata.put(GTRecipeConstants.CLEANROOM, false);
        data = GTRecipeAdapter.readPublic(recipe, start());
        check(data.getAsJsonArray("metadata").size() == 2 && !data.getAsJsonArray("metadata").get(1).getAsJsonObject().has("publicKeyIdentity"), "other recognized false conditions receive no RECYCLE identity marker");
    }

    private static void identity() {
        Item item = new FixtureItem(), other = new FixtureItem(); InventoryPlayer inventory = new InventoryPlayer(null);
        ItemStack query = new ItemStack(item, 3, 7); query.setTagCompound(tag("actual")); inventory.mainInventory[0] = query;
        RecipeMap map = new RecipeMap("query.identity");
        GTRecipe wrongItem = recipe(other, 7), wrongMeta = recipe(item, 8), wrongNbt = recipe(item, 7), exact = recipe(item, 7);
        wrongItem.mInputs[0].setTagCompound(tag("actual")); wrongMeta.mInputs[0].setTagCompound(tag("actual")); wrongNbt.mInputs[0].setTagCompound(tag("other")); exact.mInputs[0].setTagCompound(tag("actual"));
        GTRecipe.GTRecipe_WithAlt alt = new GTRecipe.GTRecipe_WithAlt(); alt.mInputs = new ItemStack[] {new ItemStack(other, 1, 9)}; alt.isNBTSensitive = false;
        alt.mOreDictAlt = new ItemStack[][] {{new ItemStack(item, 1, 7)}};
        GTRecipe wildcard = recipe(item, OreDictionary.WILDCARD_VALUE); wildcard.isNBTSensitive = false;
        map.backend.categories.put(map.defaultCategory, new ArrayList<>(Arrays.asList(wrongItem, wrongMeta, wrongNbt, exact, alt, wildcard)));
        JsonObject args = mapArgs(map); args.add("input", inventoryFilter(0));
        JsonObject result = read(new RecipeQueryAdapter(), args, inventory);
        check(result.getAsJsonArray("recipes").size() == 3, "input matching requires real Item identity and metadata/NBT while accepting declared alternatives and wildcards");
        check(result.getAsJsonObject("query").getAsJsonObject("input").getAsJsonObject("nbt").toString().contains("actual") && query.stackSize == 3, "actual query NBT is copied and inventory counts remain untouched");
        inventory.mainInventory[1] = new ItemStack(other, 1, 7);
        args.add("output", inventoryFilter(1)); result = read(new RecipeQueryAdapter(), args, inventory);
        check(result.getAsJsonArray("recipes").size() == 0 && result.get("complete").getAsBoolean(), "input/output constraints compose and an exhausted complete empty search is distinguished from a partial empty page");
        Item.itemRegistry.addObject(31001, "bridge_query_fixture:item", item);
        JsonObject registry = new JsonObject(); registry.addProperty("source", "registry"); registry.addProperty("registryName", "bridge_query_fixture:item"); registry.addProperty("metadata", 7);
        args = mapArgs(map); args.add("input", registry);
        result = read(new RecipeQueryAdapter(), args, inventory);
        check(result.getAsJsonArray("recipes").size() == 2 && result.getAsJsonObject("query").getAsJsonObject("input").get("metadata").getAsInt() == 7, "public registryName/metadata source resolves the actual registered Item, without fabricating inventory NBT");
        OreDictionary.registerOre("bridgeQueryFixtureOre", new ItemStack(item, 1, 7));
        GTRecipe.GTRecipe_WithAlt ore = new GTRecipe.GTRecipe_WithAlt(); ore.isNBTSensitive = false; ore.mInputs = new ItemStack[] {new ItemStack(other, 1, 9)};
        ore.mOreDictIds = new int[] {OreDictionary.getOreID("bridgeQueryFixtureOre")};
        RecipeMap oreMap = new RecipeMap("query.ore.identity"); oreMap.backend.categories.put(oreMap.defaultCategory, Collections.singletonList(ore));
        JsonObject oreArgs = mapArgs(oreMap); oreArgs.add("input", inventoryFilter(0));
        check(read(new RecipeQueryAdapter(), oreArgs, inventory).getAsJsonArray("recipes").size() == 1, "declared public ore ID matches actual query membership without expanding the whole ore-item catalog");
        RecipeMap truncated = new RecipeMap("query.filter.truncated"); GTRecipe huge = recipe(other, 9); huge.mInputs = new ItemStack[65]; Arrays.fill(huge.mInputs, new ItemStack(other, 1, 9)); huge.mInputs[64] = query;
        truncated.backend.categories.put(truncated.defaultCategory, Collections.singletonList(huge));
        args = mapArgs(truncated); args.add("input", inventoryFilter(0)); result = read(new RecipeQueryAdapter(), args, inventory);
        check(result.getAsJsonArray("recipes").size() == 0 && result.get("filterIncomplete").getAsBoolean() && !result.get("complete").getAsBoolean() && result.get("partial").getAsBoolean(), "hard-bounded identity inspection never converts unchecked inputs into proof of no recipe");
    }

    private static void boundsAndContinuation() {
        Item item = new FixtureItem(), other = new FixtureItem(); InventoryPlayer inventory = new InventoryPlayer(null); inventory.mainInventory[0] = new ItemStack(item, 1, 7);
        RecipeMap map = new RecipeMap("query.candidate.bound"); List<GTRecipe> list = new ArrayList<>();
        for (int i = 0; i < 512; i++) list.add(recipe(other, 0)); GTRecipe last = recipe(item, 7); last.isNBTSensitive = false; list.add(last); map.backend.categories.put(map.defaultCategory, list);
        RecipeQueryAdapter adapter = new RecipeQueryAdapter(); JsonObject args = mapArgs(map); args.add("input", inventoryFilter(0));
        JsonObject page = read(adapter, args, inventory); String cursor = page.get("nextCursor").getAsString();
        check(page.get("scannedCandidates").getAsInt() == 512 && page.getAsJsonArray("recipes").size() == 0 && page.get("hasMore").getAsBoolean() && page.get("partial").getAsBoolean() && !page.get("complete").getAsBoolean(), "512 skipped candidates stop at a partial empty page with continuation");
        JsonObject continuation = cursor(cursor); page = read(adapter, continuation, inventory);
        check(page.get("scannedCandidates").getAsInt() == 1 && page.get("totalScanned").getAsInt() == 513 && page.getAsJsonArray("recipes").size() == 1 && page.get("complete").getAsBoolean(), "continuation preserves iterator position without rescanning the skipped prefix");
        expect("stale_query", () -> read(adapter, continuation, inventory));
        RecipeMap many = new RecipeMap("query.result.bound"); List<GTRecipe> data = new ArrayList<>(); for (int i = 0; i < 17; i++) data.add(recipe(item, i)); many.backend.categories.put(many.defaultCategory, data);
        args = mapArgs(many); args.addProperty("limit", 16); page = read(adapter, args, null);
        check(page.getAsJsonArray("recipes").size() == 16 && page.get("hasMore").getAsBoolean() && page.get("stopReason").getAsString().equals("result_limit"), "hard recipe limit is 16 independently of candidate count");
        JsonObject timed = adapter.read(WORLD, "session", 0, null, NO_TILES, mapArgs(many), System.nanoTime() - RecipeQueryAdapter.SOFT_BUDGET_NANOS * 2);
        check(timed.get("scannedCandidates").getAsInt() == 1 && timed.get("partial").getAsBoolean() && timed.getAsJsonObject("budget").get("softBudgetMicros").getAsInt() == 2000, "expired 2ms soft budget makes one candidate progress and reports partial coverage");
    }

    private static void invalidation() {
        RecipeMap map = new RecipeMap("query.cursor.invalidate"); List<GTRecipe> list = new ArrayList<>(Arrays.asList(new GTRecipe(), new GTRecipe())); map.backend.categories.put(map.defaultCategory, list);
        AtomicLong clock = new AtomicLong(); RecipeQueryAdapter adapter = new RecipeQueryAdapter(clock::get); InventoryPlayer inventory = new InventoryPlayer(null);
        JsonObject args = mapArgs(map); args.addProperty("limit", 1);
        JsonObject token = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        clock.set(RecipeQueryAdapter.CURSOR_TTL_NANOS); expect("stale_query", () -> read(adapter, token, inventory));
        JsonObject worldToken = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        expect("stale_query", () -> adapter.read(new Object(), "session", 0, inventory, NO_TILES, worldToken, start()));
        JsonObject sessionToken = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        expect("stale_query", () -> adapter.read(WORLD, "other", 0, inventory, NO_TILES, sessionToken, start()));
        JsonObject dimensionToken = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        expect("stale_query", () -> adapter.read(WORLD, "session", 1, inventory, NO_TILES, dimensionToken, start()));
        JsonObject structural = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        list.remove(0); list.add(new GTRecipe()); expect("stale_query", () -> read(adapter, structural, inventory));
        RecipeMap priorCategory = new RecipeMap("query.exhausted.category"); List<GTRecipe> completed = new ArrayList<>(Collections.singletonList(new GTRecipe()));
        priorCategory.backend.categories.put(priorCategory.defaultCategory, completed);
        priorCategory.backend.categories.put(new RecipeCategory("remaining", priorCategory, () -> RecipeCategorySetting.ENABLE), new ArrayList<>(Arrays.asList(new GTRecipe(), new GTRecipe())));
        JsonObject priorArgs = mapArgs(priorCategory); priorArgs.addProperty("limit", 1); JsonObject prior = cursor(read(adapter, priorArgs, inventory).get("nextCursor").getAsString());
        completed.clear(); completed.add(new GTRecipe()); expect("stale_query", () -> read(adapter, prior, inventory));
        JsonObject sameSize = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        map.backend.categories.put(map.defaultCategory, new ArrayList<>(list)); expect("stale_query", () -> read(adapter, sameSize, inventory));
        JsonObject registry = cursor(read(adapter, args, inventory).get("nextCursor").getAsString());
        new RecipeMap(map.unlocalizedName); expect("stale_query", () -> read(adapter, registry, inventory)); RecipeMap.ALL_RECIPE_MAPS.put(map.unlocalizedName, map);
        AtomicReference<RecipeCategorySetting> setting = new AtomicReference<>(RecipeCategorySetting.ENABLE);
        RecipeCategory changing = new RecipeCategory("query.changing", map, setting::get); map.backend.categories.clear(); map.backend.categories.put(changing, new ArrayList<>(list));
        JsonObject visibility = cursor(read(adapter, args, inventory).get("nextCursor").getAsString()); setting.set(RecipeCategorySetting.HIDE); expect("stale_query", () -> read(adapter, visibility, inventory)); setting.set(RecipeCategorySetting.ENABLE);
        inventory.mainInventory[0] = new ItemStack(new FixtureItem(), 1, 7); list.clear(); list.add(recipe(inventory.mainInventory[0].getItem(), 7)); list.add(recipe(inventory.mainInventory[0].getItem(), 7)); map.backend.categories.put(changing, list);
        args.add("input", inventoryFilter(0)); JsonObject filter = cursor(read(adapter, args, inventory).get("nextCursor").getAsString()); inventory.mainInventory[0].setTagCompound(tag("changed")); expect("stale_query", () -> read(adapter, filter, inventory)); args.remove("input");
        JsonObject first = null;
        for (int i = 0; i <= RecipeQueryAdapter.SESSION_LIMIT; i++) {
            JsonObject current = cursor(read(adapter, args, inventory).get("nextCursor").getAsString()); if (i == 0) first = current;
        }
        final JsonObject evicted = first; expect("stale_query", () -> read(adapter, evicted, inventory));
        JsonObject invalidated = cursor(read(adapter, args, inventory).get("nextCursor").getAsString()); adapter.invalidate(); expect("stale_query", () -> read(adapter, invalidated, inventory));
    }

    private static void machines() {
        RecipeMap map = new RecipeMap("query.machine"), alternate = new RecipeMap("query.machine.alternate");
        map.backend.categories.put(map.defaultCategory, new ArrayList<>(Arrays.asList(new GTRecipe(), new GTRecipe())));
        FakeMeta meta = new FakeMeta(map, alternate); BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = meta;
        RecipeQueryAdapter adapter = new RecipeQueryAdapter(); JsonObject args = new JsonObject(); args.addProperty("x", 1); args.addProperty("y", 2); args.addProperty("z", 3); args.addProperty("limit", 1);
        RecipeQueryAdapter.TileLookup tiles = (x, y, z) -> { check(x == 1 && y == 2 && z == 3, "machine query uses the supplied exact loaded position"); return tile; };
        JsonObject result = adapter.read(WORLD, "session", 0, null, tiles, args, start());
        check(result.getAsJsonObject("machine").getAsJsonArray("availableRecipeMaps").size() == 2 && result.getAsJsonObject("machine").get("metaTileId").getAsInt() == 19420
                && result.getAsJsonObject("machine").get("modeName").getAsString().equals("mode 0"), "lightweight public current/available maps and mode context avoid another machine inspection");
        JsonObject mode = cursor(result.get("nextCursor").getAsString()); meta.mode = 1;
        expect("stale_query", () -> adapter.read(WORLD, "session", 0, null, tiles, mode, start())); meta.mode = 0;
        JsonObject replaced = cursor(adapter.read(WORLD, "session", 0, null, tiles, args, start()).get("nextCursor").getAsString()); tile.meta = new FakeMeta(map, alternate);
        expect("stale_query", () -> adapter.read(WORLD, "session", 0, null, tiles, replaced, start()));
    }

    private static void validation() {
        RecipeMap map = new RecipeMap("query.validation"); JsonObject args = mapArgs(map); args.addProperty("x", 1);
        expect("invalid_args", () -> read(new RecipeQueryAdapter(), args, null));
        JsonObject incomplete = new JsonObject(); incomplete.addProperty("x", 1); expect("invalid_args", () -> read(new RecipeQueryAdapter(), incomplete, null));
        JsonObject extra = mapArgs(map); extra.addProperty("screenId", "not allowed"); expect("invalid_args", () -> read(new RecipeQueryAdapter(), extra, null));
        JsonObject count = mapArgs(map); count.addProperty("limit", 17); expect("invalid_args", () -> read(new RecipeQueryAdapter(), count, null));
        JsonObject source = mapArgs(map); JsonObject bad = inventoryFilter(0); bad.addProperty("registryName", "wrong source"); source.add("input", bad); expect("invalid_args", () -> read(new RecipeQueryAdapter(), source, new InventoryPlayer(null)));
        JsonObject cursor = cursor("missing"); cursor.add("input", inventoryFilter(0)); expect("invalid_args", () -> read(new RecipeQueryAdapter(), cursor, null));
        RecipeMap large = new RecipeMap("query.categories.bound"); for (int i = 0; i <= RecipeQueryAdapter.CATEGORY_LIMIT; i++) large.backend.categories.put(new RecipeCategory("category" + i, large, () -> RecipeCategorySetting.ENABLE), Collections.emptyList());
        expect("unsupported", () -> read(new RecipeQueryAdapter(), mapArgs(large), null));
    }

    private static void byteBudget() {
        RecipeMap map = new RecipeMap("query.byte.bound"); Item longName = new FixtureItem() {
            @Override public String getItemStackDisplayName(ItemStack stack) { return String.join("", Collections.nCopies(512, "q")); }
        };
        List<GTRecipe> list = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            GTRecipe recipe = recipe(longName, i); recipe.mInputs = new ItemStack[64]; recipe.mOutputs = new ItemStack[64];
            Arrays.fill(recipe.mInputs, new ItemStack(longName, 1, i)); Arrays.fill(recipe.mOutputs, new ItemStack(longName, 1, i)); list.add(recipe);
        }
        map.backend.categories.put(map.defaultCategory, list); RecipeQueryAdapter adapter = new RecipeQueryAdapter(); JsonObject args = mapArgs(map); args.addProperty("limit", 16);
        JsonObject page = read(adapter, args, null); int returned = page.getAsJsonArray("recipes").size();
        check(page.toString().getBytes(StandardCharsets.UTF_8).length <= RecipeQueryAdapter.BYTE_LIMIT && page.get("byteLimited").getAsBoolean() && page.get("hasMore").getAsBoolean(), "actual encoded payload is capped at 128KiB and byte-limited match is retained for continuation");
        while (page.get("hasMore").getAsBoolean()) { page = read(adapter, cursor(page.get("nextCursor").getAsString()), null); returned += page.getAsJsonArray("recipes").size(); }
        check(returned == 16, "byte-budget continuation neither skips nor duplicates matching recipes");
    }

    private static void backendMatches() {
        Item item = new FixtureItem(); Item.itemRegistry.addObject(32120, "bridge_backend_fixture:item", item);
        RecipeMap dynamic = new RecipeMap("query.backend.dynamic", false); dynamic.backend.overwrite = true;
        GTRecipe furnace = recipe(item, 7); furnace.mOutputs[0] = new ItemStack(item, 1, 8); furnace.mEUt = 4; furnace.mDuration = 128;
        dynamic.backend.dynamicRecipe = furnace;
        JsonObject args = backendArgs(7, 1); args.add("output", backendSource(8, null));
        JsonObject result = backendRead(dynamic, args);
        check(result.get("matched").getAsBoolean() && result.get("candidateValidated").getAsBoolean()
                && !result.getAsJsonObject("recipeMap").get("registerNEI").getAsBoolean(), "explicit public backend lookup includes a dynamic candidate without a NEI registration gate");
        check(result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("rawDurationTicks").getAsInt() == 128
                && result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("rawEUt").getAsInt() == 4,
                "dynamic candidate uses the existing actual raw recipe DTO");
        JsonObject nativeCandidate = result.getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(nativeCandidate.get("candidateOrdinal").getAsInt() == 0 && !nativeCandidate.has("categoryRecipeIndex")
                && nativeCandidate.get("category").isJsonNull() && !nativeCandidate.get("categoryVisibilityVerified").getAsBoolean(),
                "a dynamic first-candidate ordinal is distinct from a nonexistent registered category index");
        check(dynamic.lastQuery.finds == 1 && !dynamic.lastQuery.caching && dynamic.lastQuery.items[0].stackSize == 1
                && dynamic.lastQuery.voltage == 512 && dynamic.lastQuery.fluids.length == 0 && dynamic.lastQuery.specialSlot == null,
                "one public find uses exact finite copies, actual machine input voltage, no cache, empty fluids and no special slot");
        check(result.get("lookupComplete").getAsBoolean() && !result.get("complete").getAsBoolean()
                && !result.get("registeredCatalogComplete").getAsBoolean() && !result.get("executionValidated").getAsBoolean()
                && result.getAsJsonObject("backend").get("dynamicPossible").getAsBoolean(), "single native match is never claimed as a complete static catalog or actual execution");
        furnace.mInputs[0].stackSize = 2;
        result = backendRead(dynamic, args);
        check(result.get("matched").getAsBoolean() && !result.getAsJsonObject("validation").get("inputMatch").getAsBoolean()
                && !result.get("candidateValidated").getAsBoolean(), "an overwrite backend's candidate is independently checked with real counts and is retained with a failed validation receipt");
        args = backendArgs(7, 2); args.add("output", backendSource(8, null)); result = backendRead(dynamic, args);
        check(result.get("candidateValidated").getAsBoolean() && result.getAsJsonObject("query").getAsJsonArray("inputs").get(0).getAsJsonObject().get("count").getAsInt() == 2,
                "explicit input count changes the public complete-input match without consuming request templates");
        furnace.mInputs = new ItemStack[] {new ItemStack(item, 2, 7), new ItemStack(item, 1, 8)};
        args.getAsJsonArray("inputs").add(backendSource(8, 1)); result = backendRead(dynamic, args);
        check(result.get("candidateValidated").getAsBoolean() && dynamic.lastQuery.items[0].getMetadata() == 7 && dynamic.lastQuery.items[1].getMetadata() == 8
                && result.getAsJsonObject("query").getAsJsonArray("inputs").size() == 2, "the finite complete input array retains caller order and each explicit count");
        furnace.mInputs = new ItemStack[] {new ItemStack(item, 1, 7)};
        furnace.mInputs[0].stackSize = 1; dynamic.backend.mutateLookupCopies = true; furnace.mutateValidationCopies = true;
        args = backendArgs(7, 1); result = backendRead(dynamic, args);
        check(result.get("candidateValidated").getAsBoolean() && args.getAsJsonArray("inputs").get(0).getAsJsonObject().get("count").getAsInt() == 1
                && result.getAsJsonObject("query").getAsJsonArray("inputs").get(0).getAsJsonObject().get("count").getAsInt() == 1,
                "native lookup and non-consuming validation receive independent copies while exact query evidence remains unchanged");
        furnace.mutateValidationCopies = false; dynamic.backend.mutateLookupCopies = false;
        furnace.mEnabled = false; result = backendRead(dynamic, args);
        check(result.get("matched").getAsBoolean() && !result.get("candidateValidated").getAsBoolean()
                && !result.getAsJsonObject("validation").get("enabled").getAsBoolean(), "dynamic overwrite disabled status is explicitly validated");
        furnace.mEnabled = true; furnace.mFakeRecipe = true; result = backendRead(dynamic, args);
        check(!result.get("candidateValidated").getAsBoolean() && !result.getAsJsonObject("validation").get("notFake").getAsBoolean(), "dynamic overwrite fake status is explicitly validated");
        RecipeMap ordered = new RecipeMap("query.backend.order"); GTRecipe first = recipe(item, 7), desired = recipe(item, 7);
        first.mOutputs[0] = new ItemStack(item, 1, 9); desired.mOutputs[0] = new ItemStack(item, 1, 8);
        ordered.backend.categories.put(ordered.defaultCategory, Arrays.asList(first, desired));
        args = backendArgs(7, 1); args.add("output", backendSource(8, null)); result = backendRead(ordered, args);
        check(result.get("matched").getAsBoolean() && !result.get("candidateValidated").getAsBoolean()
                && !result.getAsJsonObject("validation").get("outputMatch").getAsBoolean()
                && first(result.getAsJsonArray("recipes").get(0).getAsJsonObject(), "items", "outputs").getAsJsonObject("stack").get("metadata").getAsInt() == 9
                && ordered.lastQuery.finds == 1, "expected output verifies only the first natural match and never chooses a later desired output");
        first.mOutputs[0].setTagCompound(tag("different")); args.add("output", backendSource(9, null)); result = backendRead(ordered, args);
        check(!result.getAsJsonObject("validation").get("outputMatch").getAsBoolean(), "backend output identity verification retains actual NBT instead of matching metadata alone");
        RecipeMap multiAmp = new RecipeMap("query.backend.amperage"); GTRecipe multiple = recipe(item, 7); multiple.mEUt = 768;
        multiAmp.backend.categories.put(multiAmp.defaultCategory, Collections.singletonList(multiple)); result = backendRead(multiAmp, backendArgs(7, 1));
        check(result.get("candidateValidated").getAsBoolean() && !result.getAsJsonObject("validation").get("singleVoltageCompatible").getAsBoolean()
                && result.getAsJsonObject("query").get("nativeVoltageBound").getAsLong() == 1024,
                "native map amperage preserves a legitimate multi-amp match above the single input voltage");
        RecipeMap singleAmp = new RecipeMap("query.backend.single_amp") { @Override public int getAmperage() { return 1; } };
        GTRecipe excessive = recipe(item, 7), allowed = recipe(item, 7); excessive.mEUt = 513; allowed.mEUt = 128;
        singleAmp.backend.categories.put(singleAmp.defaultCategory, Arrays.asList(excessive, allowed)); result = backendRead(singleAmp, backendArgs(7, 1));
        check(result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("rawEUt").getAsInt() == 128,
                "single-amp actual input voltage excludes an excessive raw recipe before natural native selection");
        result = backendRead(singleAmp, backendArgs(6, 1));
        check(!result.get("matched").getAsBoolean() && result.getAsJsonArray("recipes").size() == 0 && result.get("lookupComplete").getAsBoolean()
                && !result.get("hasMore").getAsBoolean() && result.get("nextCursor").isJsonNull(), "a null backend match carries bounded lookup evidence without catalog pagination or execution proof");
        desired.metadata.put(new RecipeMetadataKey<Object>(Object.class, "private_dynamic_condition"), new Object());
        ordered.backend.categories.put(ordered.defaultCategory, Collections.singletonList(desired)); result = backendRead(ordered, backendArgs(7, 1));
        check(result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("metadataUnknown").getAsBoolean()
                && !result.toString().contains("private_dynamic_condition") && !result.get("executionValidated").getAsBoolean(), "dynamic unknown conditions retain existing bounded metadata uncertainty");
        furnace.mFakeRecipe = false; furnace.mOutputs = new ItemStack[65]; Arrays.fill(furnace.mOutputs, new ItemStack(item, 1, 9)); furnace.mOutputs[64] = new ItemStack(item, 1, 8);
        args = backendArgs(7, 1); args.add("output", backendSource(8, null)); result = backendRead(dynamic, args);
        check(!result.get("lookupComplete").getAsBoolean() && !result.get("candidateValidated").getAsBoolean()
                && result.getAsJsonObject("validation").get("outputUnknown").getAsBoolean() && result.getAsJsonObject("validation").get("outputMatch").isJsonNull()
                && result.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("items").getAsJsonObject("outputs").get("truncated").getAsBoolean(),
                "bounded partial output inspection keeps identity unknown and cannot authorize material insertion");
        furnace.mOutputs = new ItemStack[] {new ItemStack(item, 1, 8)};
        furnace.mInputs = new ItemStack[] {new ItemStack(item, 1, 7), new ItemStack(item, 0, 9)};
        args = backendArgs(7, 1); args.getAsJsonArray("inputs").add(backendSource(9, 1)); args.add("output", backendSource(8, null));
        dynamic.backend.afterFind = RecipeQuerySelfTest::waitPastPayloadBudget;
        result = backendRead(dynamic, args, System.nanoTime() - RecipeQueryAdapter.SOFT_BUDGET_NANOS * 2);
        check(result.get("lookupComplete").getAsBoolean() && result.get("candidateValidated").getAsBoolean()
                && !result.getAsJsonObject("budget").get("softBudgetExceeded").getAsBoolean()
                && result.getAsJsonObject("budget").get("elapsedMicros").getAsLong() >= RecipeQueryAdapter.BACKEND_PAYLOAD_BUDGET_NANOS / 1000
                && !result.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("items").getAsJsonObject("inputs").get("truncated").getAsBoolean()
                && dynamic.lastQuery.finds == 1, "slow native find and an expired catalog deadline do not consume the first candidate's fresh payload budget or retry lookup");
        check(result.getAsJsonObject("budget").get("softBudgetMicros").getAsInt() == 10000
                && "backend_candidate_output_check_and_raw_dto".equals(result.getAsJsonObject("budget").get("softBudgetScope").getAsString())
                && !result.getAsJsonObject("budget").get("nativeCallInterruptible").getAsBoolean(), "backend reports its separate 10ms output-check/raw-DTO scope without inventing interruptible native lookup");
        dynamic.backend.afterFind = null;
        JsonObject expired = GTRecipeAdapter.readBackendPublic(furnace, System.nanoTime() - RecipeQueryAdapter.BACKEND_PAYLOAD_BUDGET_NANOS * 2);
        check(expired.get("softBudgetPartial").getAsBoolean() && expired.getAsJsonObject("items").getAsJsonObject("inputs").get("truncated").getAsBoolean(),
                "a genuinely expired backend payload still reports partial/truncated raw fields");
        Item slow = new FixtureItem() { @Override public String getItemStackDisplayName(ItemStack stack) { waitPastPayloadBudget(); return "slow fixture"; } };
        Item.itemRegistry.addObject(32121, "bridge_backend_fixture:slow", slow);
        furnace.mInputs = new ItemStack[] {new ItemStack(slow, 1, 7)}; furnace.mOutputs = new ItemStack[] {new ItemStack(item, 1, 8)};
        args = backendArgs(7, 1); args.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("registryName", "bridge_backend_fixture:slow");
        result = backendRead(dynamic, args);
        check(!result.get("lookupComplete").getAsBoolean() && result.getAsJsonObject("budget").get("softBudgetExceeded").getAsBoolean()
                && result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("softBudgetPartial").getAsBoolean()
                && result.get("candidateValidated").getAsBoolean() && dynamic.lastQuery.finds == 1,
                "an actually slow raw payload cannot become complete even when native candidate validation succeeds");
    }

    private static void backendFluids() {
        String firstName = "bridge_backend_fluid", secondName = "bridge_backend_other_fluid";
        FluidRegistry.registerFluid(new Fluid(firstName)); FluidRegistry.registerFluid(new Fluid(secondName));
        Fluid firstFluid = FluidRegistry.getFluid(firstName), secondFluid = FluidRegistry.getFluid(secondName);
        try {
            Item item = (Item) Item.itemRegistry.getObject("bridge_backend_fixture:item");
            RecipeMap map = new RecipeMap("query.backend.fluids"); GTRecipe mixed = recipe(item, 7);
            mixed.mFluidInputs = new FluidStack[] {new FluidStack(firstFluid, 2000), new FluidStack(secondFluid, 37)};
            map.backend.categories.put(map.defaultCategory, Collections.singletonList(mixed));
            JsonObject args = backendArgs(7, 1); JsonArray fluids = new JsonArray();
            fluids.add(backendFluid(firstName, 2000)); fluids.add(backendFluid(secondName, 37)); args.add("fluidInputs", fluids);
            JsonObject result = backendRead(map, args);
            check(result.get("matched").getAsBoolean() && result.get("candidateValidated").getAsBoolean()
                    && map.lastQuery.fluids.length == 2 && map.lastQuery.fluids[0].getFluid() == firstFluid
                    && map.lastQuery.fluids[0].amount == 2000 && map.lastQuery.fluids[1].getFluid() == secondFluid
                    && map.lastQuery.fluids[1].amount == 37 && map.lastQuery.finds == 1,
                    "Mixed finite item/fluid lookup retains exact fluid identities, order and mB through exactly one public find");
            check(result.getAsJsonObject("query").getAsJsonArray("fluids").get(0).getAsJsonObject().get("amount").getAsInt() == 2000
                    && result.getAsJsonObject("limits").get("fluidInputs").getAsInt() == 8
                    && !result.get("executionValidated").getAsBoolean(), "Exact fluid query evidence stays lookup-only, with explicit bounded coverage");
            check(map.lastQuery.fluids != mixed.lastValidationFluids && map.lastQuery.fluids[0] != mixed.lastValidationFluids[0],
                    "Native find and independent non-consuming validation use different complete fluid copies");
            map.backend.mutateLookupCopies = true; mixed.mutateValidationCopies = true; result = backendRead(map, args);
            check(result.get("candidateValidated").getAsBoolean() && fluids.get(0).getAsJsonObject().get("amount").getAsInt() == 2000
                    && result.getAsJsonObject("query").getAsJsonArray("fluids").get(0).getAsJsonObject().get("amount").getAsInt() == 2000
                    && map.lastQuery.fluids[0].amount == 0 && mixed.lastValidationFluids[0].amount == 0,
                    "Mutating both lookup and validation copies cannot corrupt the caller or exact evidence");
            map.backend.mutateLookupCopies = false; mixed.mutateValidationCopies = false;
            fluids.get(0).getAsJsonObject().addProperty("amount", 1999); result = backendRead(map, args);
            check(!result.get("matched").getAsBoolean(), "Insufficient finite fluid quantity produces no natural candidate");
            map.backend.overwrite = true; map.backend.dynamicRecipe = mixed; result = backendRead(map, args);
            check(result.get("matched").getAsBoolean() && !result.get("candidateValidated").getAsBoolean()
                    && !result.getAsJsonObject("validation").get("inputMatch").getAsBoolean() && map.lastQuery.finds == 1,
                    "An overwrite candidate cannot bypass independent fluid-amount validation or trigger a later candidate");
            map.backend.overwrite = false; fluids.get(0).getAsJsonObject().addProperty("amount", 2000);
            fluids.get(1).getAsJsonObject().addProperty("fluid", firstName); result = backendRead(map, args);
            check(!result.get("matched").getAsBoolean(), "Distinct public fluid identities cannot match by quantity alone");
            RecipeMap fluidOnly = new RecipeMap("query.backend.fluid_only"); GTRecipe candidate = new GTRecipe();
            candidate.mFluidInputs = new FluidStack[] {new FluidStack(firstFluid, Integer.MAX_VALUE)};
            fluidOnly.backend.categories.put(fluidOnly.defaultCategory, Collections.singletonList(candidate));
            JsonObject only = backendArgs(7, 1); only.remove("inputs"); JsonArray maximum = new JsonArray();
            maximum.add(backendFluid(firstName, Integer.MAX_VALUE)); only.add("fluidInputs", maximum);
            result = backendRead(fluidOnly, only);
            check(result.get("candidateValidated").getAsBoolean() && fluidOnly.lastQuery.items.length == 0
                    && fluidOnly.lastQuery.fluids[0].amount == Integer.MAX_VALUE
                    && result.getAsJsonObject("query").getAsJsonArray("fluids").get(0).getAsJsonObject().get("amount").getAsInt() == Integer.MAX_VALUE,
                    "Fluid-only inputs with omitted items preserve the largest positive int amount without overflow");
            only.add("inputs", new JsonArray()); only.add("fluids", new JsonArray());
            check(backendRead(fluidOnly, only).get("candidateValidated").getAsBoolean(), "Explicit empty items and legacy empty fluids remain compatible with fluid-only lookup");
            JsonObject all = backendArgs(7, 1); for (int i = 1; i < 8; i++) all.getAsJsonArray("inputs").add(backendSource(7, 1));
            JsonArray eight = new JsonArray(); for (int i = 0; i < 8; i++) eight.add(backendFluid(firstName, i + 1)); all.add("fluidInputs", eight);
            RecipeMap full = new RecipeMap("query.backend.input_bounds"); GTRecipe complete = recipe(item, 7);
            complete.mFluidInputs = new FluidStack[] {new FluidStack(firstFluid, 36)};
            full.backend.categories.put(full.defaultCategory, Collections.singletonList(complete)); result = backendRead(full, all);
            check(result.get("candidateValidated").getAsBoolean() && full.lastQuery.items.length == 8 && full.lastQuery.fluids.length == 8
                    && full.lastQuery.fluids[7].amount == 8, "Both independent eight-entry limits retain all inputs and native aggregate quantities");
            eight.add(backendFluid(firstName, 1)); expect("invalid_args", () -> backendRead(full, all));
            for (Object amount : new Object[] {0, -1, 2147483648L, 1.5, "2", true}) {
                JsonObject invalid = fluidOnlyArgs(firstName, 1); JsonObject input = invalid.getAsJsonArray("fluidInputs").get(0).getAsJsonObject();
                if (amount instanceof Number) input.addProperty("amount", (Number) amount);
                else if (amount instanceof String) input.addProperty("amount", (String) amount); else input.addProperty("amount", (Boolean) amount);
                expect("invalid_args", () -> backendRead(map, invalid));
            }
            for (String field : new String[] {"nbt", "tag", "metadata", "source", "registryName"}) {
                JsonObject invalid = fluidOnlyArgs(firstName, 1); invalid.getAsJsonArray("fluidInputs").get(0).getAsJsonObject().addProperty(field, "unsupported");
                expect("invalid_args", () -> backendRead(map, invalid));
            }
            JsonObject absentAmount = fluidOnlyArgs(firstName, 1); absentAmount.getAsJsonArray("fluidInputs").get(0).getAsJsonObject().remove("amount");
            expect("invalid_args", () -> backendRead(map, absentAmount));
            JsonObject absentName = fluidOnlyArgs(firstName, 1); absentName.getAsJsonArray("fluidInputs").get(0).getAsJsonObject().remove("fluid");
            expect("invalid_args", () -> backendRead(map, absentName));
            for (String name : new String[] {"", String.join("", Collections.nCopies(129, "x"))}) {
                JsonObject invalid = fluidOnlyArgs(name, 1); expect("invalid_args", () -> backendRead(map, invalid));
            }
            for (String name : new String[] {firstName.toUpperCase(java.util.Locale.ROOT), "unknown_backend_fluid", " " + firstName}) {
                JsonObject invalid = fluidOnlyArgs(name, 1); map.lastQuery = null; expect("not_found", () -> backendRead(map, invalid));
                check(map.lastQuery == null, "Unknown or non-exact fluid names fail closed before native lookup");
            }
            for (String field : new String[] {"inputs", "fluidInputs"}) {
                JsonObject invalid = fluidOnlyArgs(firstName, 1); invalid.add(field, com.google.gson.JsonNull.INSTANCE);
                expect("invalid_args", () -> backendRead(map, invalid));
                invalid.addProperty(field, "not an array"); expect("invalid_args", () -> backendRead(map, invalid));
            }
            for (com.google.gson.JsonElement malformed : new com.google.gson.JsonElement[] {com.google.gson.JsonNull.INSTANCE, new JsonArray(), new com.google.gson.JsonPrimitive("fluid")}) {
                JsonObject invalid = fluidOnlyArgs(firstName, 1); JsonArray malformedInputs = new JsonArray();
                malformedInputs.add(malformed); invalid.add("fluidInputs", malformedInputs);
                expect("invalid_args", () -> backendRead(map, invalid));
            }
            JsonObject noInputs = backendArgs(7, 1); noInputs.remove("inputs"); expect("invalid_args", () -> backendRead(map, noInputs));
            noInputs.add("fluidInputs", new JsonArray()); expect("invalid_args", () -> backendRead(map, noInputs));
            JsonObject oldItems = backendArgs(7, 1); oldItems.add("fluidInputs", new JsonArray()); oldItems.add("fluids", new JsonArray());
            RecipeMap oldMap = new RecipeMap("query.backend.old_items"); oldMap.backend.categories.put(oldMap.defaultCategory, Collections.singletonList(recipe(item, 7)));
            check(backendRead(oldMap, oldItems).get("candidateValidated").getAsBoolean(), "Existing pure-item lookup remains compatible with omitted or explicit empty fluid inputs");
        } finally { FluidRegistry.forgetForTest(firstName); FluidRegistry.forgetForTest(secondName); }
    }
    private static JsonObject backendFluid(String name, int amount) {
        JsonObject input = new JsonObject(); input.addProperty("fluid", name); input.addProperty("amount", amount); return input;
    }
    private static JsonObject fluidOnlyArgs(String name, int amount) {
        JsonObject args = backendArgs(7, 1); args.remove("inputs"); JsonArray inputs = new JsonArray(); inputs.add(backendFluid(name, amount)); args.add("fluidInputs", inputs); return args;
    }

    private static void backendVoltages() {
        RecipeMap map = new RecipeMap("query.backend.controller_voltage");
        GTRecipe candidate = recipe((Item) Item.itemRegistry.getObject("bridge_backend_fixture:item"), 7); candidate.mEUt = 768;
        map.backend.categories.put(map.defaultCategory, Collections.singletonList(candidate));
        NativeMulti multi = new NativeMulti(map, 524288); VoltageTile tile = new VoltageTile(0); tile.meta = multi;
        JsonObject actual = backendVoltageRead(tile), evidence = actual.getAsJsonObject("query").getAsJsonObject("inputVoltageEvidence");
        check(actual.get("candidateValidated").getAsBoolean() && map.lastQuery.voltage == 524288
                && actual.getAsJsonObject("query").get("nativeVoltageBound").getAsLong() == 1048576
                && actual.getAsJsonObject("query").get("mapAmperage").getAsInt() == map.getAmperage(), "A zero-voltage base tile uses its typed multiblock controller's actual public voltage and the same map amperage");
        check(multi.voltageReads == 2 && tile.voltageReads == 0 && map.lastQuery.finds == 1,
                "The common public multiblock method dispatches the actual override and rereads that same API after exactly one native find");
        JsonObject owner = evidence.getAsJsonObject("owner");
        check(evidence.get("schemaVersion").getAsInt() == 1 && evidence.get("source").getAsString().equals("public_multiblock_controller_api")
                && evidence.get("scope").getAsString().equals("native_lookup_bound_only")
                && evidence.get("apiClass").getAsString().equals("gregtech.api.metatileentity.implementations.MTEMultiBlockBase")
                && evidence.get("apiMethod").getAsString().equals("getMaxInputVoltage") && evidence.get("value").getAsLong() == 524288,
                "Voltage evidence identifies its fixed public API and native-lookup-only scope rather than claiming processing power");
        check(owner.get("x").getAsInt() == 1 && owner.get("y").getAsInt() == 2 && owner.get("z").getAsInt() == 3
                && owner.get("dimension").getAsInt() == 0 && owner.get("metaTileId").getAsInt() == tile.getMetaTileID()
                && owner.get("metaClass").getAsString().equals(multi.getClass().getName())
                && owner.get("currentRecipeMap").getAsString().equals(map.unlocalizedName), "Voltage is bound to the actual controller identity, current map, dimension and requested loaded coordinates");
        check(!actual.get("executionValidated").getAsBoolean() && !actual.get("registeredCatalogComplete").getAsBoolean(),
                "Controller voltage evidence does not claim actual recipe execution or whole-catalog coverage");

        VoltageTile basicTile = new VoltageTile(512); basicTile.meta = new NativeBasic(map);
        JsonObject basic = backendVoltageRead(basicTile); JsonObject basicEvidence = basic.getAsJsonObject("query").getAsJsonObject("inputVoltageEvidence");
        check(basic.get("candidateValidated").getAsBoolean() && basicTile.voltageReads == 2 && map.lastQuery.voltage == 512
                && basicEvidence.get("source").getAsString().equals("public_base_tile_api")
                && basicEvidence.get("apiClass").getAsString().equals("gregtech.api.metatileentity.BaseMetaTileEntity")
                && basicEvidence.get("apiMethod").getAsString().equals("getInputVoltage"), "Typed BasicMachine retains its existing positive base-tile input-voltage path and stable reread");
        multi.voltage = 512; actual = backendVoltageRead(tile);
        check(actual.getAsJsonObject("query").get("nativeVoltageBound").equals(basic.getAsJsonObject("query").get("nativeVoltageBound"))
                && actual.getAsJsonArray("recipes").get(0).getAsJsonObject().get("rawEUt").equals(basic.getAsJsonArray("recipes").get(0).getAsJsonObject().get("rawEUt"))
                && actual.get("candidateValidated").equals(basic.get("candidateValidated")) && map.lastQuery.finds == 1,
                "Equivalent actual controller and BasicMachine voltage bounds preserve the same first native candidate and validation");

        for (long invalid : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1}) {
            NativeMulti refusedMulti = new NativeMulti(map, invalid); VoltageTile positiveBase = new VoltageTile(512); positiveBase.meta = refusedMulti;
            map.lastQuery = null; expect("unsupported", () -> backendVoltageRead(positiveBase));
            check(map.lastQuery == null && positiveBase.voltageReads == 0,
                    "Unavailable/negative/overflowing native controller voltage is refused before lookup without a positive base-tile fallback");
        }
        multi.voltage = Long.MAX_VALUE / 2; actual = backendVoltageRead(tile);
        check(actual.getAsJsonObject("query").get("nativeVoltageBound").getAsLong() == Long.MAX_VALUE - 1
                && map.lastQuery.voltage == Long.MAX_VALUE / 2, "The largest non-overflowing two-amp controller bound remains exact");
        for (int invalid : new int[] {0, -1}) {
            RecipeMap badAmperage = new RecipeMap("query.backend.bad_map_amp." + invalid) { @Override public int getAmperage() { return invalid; } };
            NativeMulti validMulti = new NativeMulti(badAmperage, 524288); VoltageTile noBase = new VoltageTile(0); noBase.meta = validMulti;
            expect("unsupported", () -> backendVoltageRead(noBase)); check(badAmperage.lastQuery == null, "The actual map's non-positive amperage cannot form a native bound");
        }
        VoltageTile zeroBasic = new VoltageTile(0); zeroBasic.meta = new NativeBasic(map); map.lastQuery = null;
        expect("unsupported", () -> backendVoltageRead(zeroBasic)); check(map.lastQuery == null, "A zero-voltage BasicMachine remains unavailable before native find");
        VoltageTile untyped = new VoltageTile(0); untyped.meta = new FakeMeta(map, map) {
            public long getMaxInputVoltage() { throw new AssertionError("A similar method name cannot establish common-controller membership"); }
        };
        expect("unsupported", () -> backendVoltageRead(untyped));
        multi.voltage = 512; map.backend.afterFind = () -> multi.voltage = 513;
        try { expect("stale_query", () -> backendVoltageRead(tile)); }
        finally { map.backend.afterFind = null; }
    }

    private static JsonObject backendVoltageRead(BaseMetaTileEntity tile) {
        return new RecipeQueryAdapter().read(WORLD, "session", 0, null, (x, y, z) -> tile, backendArgs(7, 1), start());
    }

    public static final class VoltageTile extends BaseMetaTileEntity {
        final long voltage; int voltageReads;
        VoltageTile(long voltage) { this.voltage = voltage; }
        @Override public long getInputVoltage() { voltageReads++; return voltage; }
    }
    public static final class NativeMulti extends MTEMultiBlockBase implements RecipeMapWorkable {
        final RecipeMap map; long voltage; int voltageReads;
        NativeMulti(RecipeMap map, long voltage) { this.map = map; this.voltage = voltage; }
        @Override public long getMaxInputVoltage() { voltageReads++; return voltage; }
        @Override public RecipeMap getRecipeMap() { return map; }
        @Override public Collection<RecipeMap> getAvailableRecipeMaps() { return Collections.singletonList(map); }
        @Override public ItemStack getStackForm(long amount) { throw new AssertionError("Machine item construction is unnecessary"); }
    }
    public static final class NativeBasic extends MTEBasicMachine implements RecipeMapWorkable {
        final RecipeMap map;
        NativeBasic(RecipeMap map) { super(4, 6); this.map = map; }
        @Override public RecipeMap getRecipeMap() { return map; }
        @Override public Collection<RecipeMap> getAvailableRecipeMaps() { return Collections.singletonList(map); }
        @Override public ItemStack getStackForm(long amount) { throw new AssertionError("Machine item construction is unnecessary"); }
    }

    private static void waitPastPayloadBudget() {
        long started = System.nanoTime();
        while (System.nanoTime() - started < RecipeQueryAdapter.BACKEND_PAYLOAD_BUDGET_NANOS * 2) Thread.yield();
    }

    private static void backendValidation() {
        RecipeMap map = new RecipeMap("query.backend.validation");
        JsonObject missingCount = backendArgs(7, 1); missingCount.getAsJsonArray("inputs").get(0).getAsJsonObject().remove("count");
        expect("invalid_args", () -> backendRead(map, missingCount));
        for (int count : new int[] {0, 65}) { JsonObject invalid = backendArgs(7, count); expect("invalid_args", () -> backendRead(map, invalid)); }
        JsonObject empty = backendArgs(7, 1); empty.add("inputs", new JsonArray()); expect("invalid_args", () -> backendRead(map, empty));
        JsonObject excessive = backendArgs(7, 1); for (int index = 1; index < 9; index++) excessive.getAsJsonArray("inputs").add(backendSource(7, 1));
        expect("invalid_args", () -> backendRead(map, excessive));
        JsonObject wildcard = backendArgs(32767, 1); expect("invalid_args", () -> backendRead(map, wildcard));
        JsonObject tagged = backendArgs(7, 1); tagged.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("nbt", "caller supplied");
        expect("invalid_args", () -> backendRead(map, tagged));
        JsonObject inventory = backendArgs(7, 1); inventory.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("source", "inventory");
        expect("unsupported", () -> backendRead(map, inventory));
        JsonObject fluids = backendArgs(7, 1); JsonArray fluidInput = new JsonArray(); fluidInput.add(new JsonObject()); fluids.add("fluids", fluidInput);
        expect("unsupported", () -> backendRead(map, fluids));
        JsonObject special = backendArgs(7, 1); special.add("specialSlot", new JsonObject()); expect("unsupported", () -> backendRead(map, special));
        for (String extra : new String[] {"map", "cursor", "input", "limit", "voltage"}) {
            JsonObject invalid = backendArgs(7, 1); invalid.addProperty(extra, "not allowed"); expect("invalid_args", () -> backendRead(map, invalid));
        }
        JsonObject absent = backendArgs(7, 1); absent.remove("z"); expect("invalid_args", () -> backendRead(map, absent));
        JsonObject dimension = backendArgs(7, 1); dimension.addProperty("dimension", 1); expect("stale_query", () -> backendRead(map, dimension));
        JsonObject unknown = backendArgs(7, 1); unknown.addProperty("mode", "unknown"); expect("invalid_args", () -> backendRead(map, unknown));
        JsonObject named = backendArgs(7, 1); named.remove("x"); named.remove("y"); named.remove("z"); expect("invalid_args", () -> backendRead(map, named));
        JsonObject source = backendArgs(7, 1); source.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("registryName", "bridge_backend_fixture:absent");
        expect("not_found", () -> backendRead(map, source));
        map.backend.afterFind = () -> new RecipeMap(map.unlocalizedName);
        expect("stale_query", () -> backendRead(map, backendArgs(7, 1)));
        RecipeMap changing = new RecipeMap("query.backend.machine_changed"); FakeMeta meta = new FakeMeta(changing, changing);
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = meta; changing.backend.afterFind = () -> meta.mode++;
        expect("stale_query", () -> new RecipeQueryAdapter().read(WORLD, "session", 0, null, (x, y, z) -> tile, backendArgs(7, 1), start()));
        RecipeMap hidden = new RecipeMap("query.backend.hidden", false); hidden.backend.overwrite = true;
        GTRecipe hiddenRecipe = recipe((Item) Item.itemRegistry.getObject("bridge_backend_fixture:item"), 7); hiddenRecipe.mHidden = true;
        hidden.backend.dynamicRecipe = hiddenRecipe; expect("unsupported", () -> backendRead(hidden, backendArgs(7, 1)));
        hiddenRecipe.mHidden = false; hiddenRecipe.recipeCategory = new RecipeCategory("hidden.backend.category", hidden, () -> RecipeCategorySetting.HIDE);
        expect("unsupported", () -> backendRead(hidden, backendArgs(7, 1)));
        RecipeMap ambiguous = new RecipeMap("query.backend.unavailable_api") { @Override public gregtech.api.recipe.FindRecipeQuery findRecipeQuery() { return null; } };
        expect("unsupported", () -> backendRead(ambiguous, backendArgs(7, 1)));
    }

    private static JsonObject backendSource(int metadata, Integer count) {
        JsonObject source = new JsonObject(); source.addProperty("source", "registry"); source.addProperty("registryName", "bridge_backend_fixture:item"); source.addProperty("metadata", metadata);
        if (count != null) source.addProperty("count", count); return source;
    }
    private static JsonObject backendArgs(int metadata, int count) {
        JsonObject args = new JsonObject(); args.addProperty("mode", "backend_match"); args.addProperty("x", 1); args.addProperty("y", 2); args.addProperty("z", 3);
        JsonArray inputs = new JsonArray(); inputs.add(backendSource(metadata, count)); args.add("inputs", inputs); return args;
    }
    private static JsonObject backendRead(RecipeMap map, JsonObject args) { return backendRead(map, args, start()); }
    private static JsonObject backendRead(RecipeMap map, JsonObject args, long started) {
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = new FakeMeta(map, map);
        return new RecipeQueryAdapter().read(WORLD, "session", 0, null, (x, y, z) -> tile, args, started);
    }

    public static class FakeMeta implements RecipeMapWorkable {
        public int mode; final RecipeMap current, alternate;
        FakeMeta(RecipeMap current, RecipeMap alternate) { this.current = current; this.alternate = alternate; }
        @Override public RecipeMap getRecipeMap() { return current; }
        @Override public ItemStack getStackForm(long amount) { throw new AssertionError("Machine item construction is unnecessary"); }
        @Override public Collection<RecipeMap> getAvailableRecipeMaps() { return Arrays.asList(current, alternate); }
        public int getMachineMode() { return mode; }
        public String getMachineModeName() { return "mode " + mode; }
    }
    private static class FixtureItem extends Item { @Override public String getItemStackDisplayName(ItemStack stack) { return "query fixture"; } }
    private static GTRecipe recipe(Item item, int metadata) { GTRecipe result = new GTRecipe(); result.mInputs = new ItemStack[] {new ItemStack(item, 1, metadata)}; result.mOutputs = new ItemStack[] {new ItemStack(item, 1, metadata)}; return result; }
    private static NBTTagCompound tag(String value) { NBTTagCompound tag = new NBTTagCompound(); tag.setString("fixture", value); return tag; }
    private static JsonObject first(JsonObject recipe, String kind, String group) { return recipe.getAsJsonObject(kind).getAsJsonObject(group).getAsJsonArray("entries").get(0).getAsJsonObject(); }
    private static JsonObject mapArgs(RecipeMap map) { JsonObject args = new JsonObject(); args.addProperty("map", map.unlocalizedName); return args; }
    private static JsonObject inventoryFilter(int slot) { JsonObject source = new JsonObject(); source.addProperty("source", "inventory"); source.addProperty("slot", slot); return source; }
    private static JsonObject cursor(String token) { JsonObject args = new JsonObject(); args.addProperty("cursor", token); return args; }
    private static JsonObject read(RecipeQueryAdapter adapter, JsonObject args, InventoryPlayer inventory) { return adapter.read(WORLD, "session", 0, inventory, NO_TILES, args, start()); }
    private static long start() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code + ", actual " + error.code); } }
}
