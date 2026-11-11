package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.util.BoundedNbt;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Fixed GTNH 5.09.54 public recipe reader. Never calls recipe matching, consuming or drawing. */
public class GTRecipeAdapter {
    public static final int STACK_LIMIT = 64, METADATA_LIMIT = 64, VALUE_LIMIT = 32;
    static final String RECIPE = "gregtech.api.util.GTRecipe";
    private static volatile PublicMetadataKeys publicKeys;

    public static JsonObject inspectHandler(Object handler, int index, long started) {
        Class<?> api = AdapterAccess.type(handler, "gregtech.nei.GTNEIDefaultHandler");
        if (api == null) return null;
        JsonObject result;
        try {
            Object recipes = AdapterAccess.field(handler, "codechicken.nei.recipe.TemplateRecipeHandler", "arecipes", false);
            if (!(recipes instanceof List<?>) || index < 0 || index >= ((List<?>) recipes).size())
                throw new BridgeException("adapter_error", "GT cached recipe index is unavailable.");
            Object cached = ((List<?>) recipes).get(index);
            Object recipe = AdapterAccess.field(cached, "gregtech.nei.GTNEIDefaultHandler$CachedDefaultRecipe", "mRecipe", false);
            result = read(recipe, started);
            Object map = AdapterAccess.call(handler, "getRecipeMap");
            JsonObject mapData = new JsonObject();
            mapData.add("unlocalizedName", value(AdapterAccess.field(map, "gregtech.api.recipe.RecipeMap", "unlocalizedName", false), 0));
            mapData.add("amperage", value(AdapterAccess.call(map, "getAmperage"), 0));
            result.add("recipeMap", mapData);
            Object calculator = AdapterAccess.field(cached, "gregtech.nei.GTNEIDefaultHandler$CachedDefaultRecipe", "calculator", false);
            JsonObject display = new JsonObject();
            display.addProperty("available", calculator != null);
            display.addProperty("source", "Existing NEI drawDescription calculator; display context, not actual machine processing");
            if (calculator != null) {
                display.add("eut", value(AdapterAccess.call(calculator, "getConsumption"), 0));
                display.add("durationTicks", value(AdapterAccess.call(calculator, "getDuration"), 0));
            }
            result.add("displayOverclock", display);
        } catch (RuntimeException | LinkageError error) {
            result = new JsonObject(); result.addProperty("available", false);
            result.addProperty("error", JsonData.bounded(error.getMessage(), 512));
        }
        return result;
    }

    public static JsonObject read(Object recipe, long started) {
        return read(recipe, started, false);
    }

    /** Public recipe-query path; preserve the existing NEI reader's metadata and timing behavior. */
    static JsonObject readPublic(Object recipe, long started) {
        return read(recipe, started - StructureAdapter.SOFT_BUDGET_NANOS + RecipeQueryAdapter.SOFT_BUDGET_NANOS, true);
    }

    /** One backend candidate has a separate bounded payload budget after native lookup/validation. */
    static JsonObject readBackendPublic(Object recipe, long payloadStarted) {
        return read(recipe, payloadStarted - StructureAdapter.SOFT_BUDGET_NANOS + RecipeQueryAdapter.BACKEND_PAYLOAD_BUDGET_NANOS, true);
    }

    private static JsonObject read(Object recipe, long started, boolean publicMetadata) {
        if (AdapterAccess.type(recipe, RECIPE) == null) throw new BridgeException("unsupported", "Not a supported GTRecipe.");
        JsonObject result = new JsonObject();
        result.addProperty("available", true);
        result.addProperty("source", "GTRecipe raw parameters; no machine overclock, parallel or efficiency applied");
        for (String[] pair : new String[][] {{"mEUt", "rawEUt"}, {"mDuration", "rawDurationTicks"}, {"mSpecialValue", "specialValue"},
                {"mEnabled", "enabled"}, {"mHidden", "hidden"}, {"mFakeRecipe", "fakeRecipe"}, {"mCanBeBuffered", "canBeBuffered"},
                {"mNeedsEmptyOutput", "needsEmptyOutput"}, {"isNBTSensitive", "nbtSensitive"}})
            result.add(pair[1], value(AdapterAccess.field(recipe, RECIPE, pair[0], false), 0));
        result.addProperty("chanceDenominator", 10000);
        result.add("items", stacks(recipe, "mInputs", "mOutputs", "getInputChance", "getOutputChance", started));
        result.add("fluids", stacks(recipe, "mFluidInputs", "mFluidOutputs", "getFluidInputChance", "getFluidOutputChance", started));
        result.add("specialItems", value(AdapterAccess.field(recipe, RECIPE, "mSpecialItems", false), 0));
        result.add("neiDescription", value(AdapterAccess.call(recipe, "getNeiDesc"), 0));
        JsonArray metadata = new JsonArray();
        Object storage = AdapterAccess.call(recipe, "getMetadataStorage");
        Object entries = AdapterAccess.callOn(storage, "gregtech.api.recipe.metadata.IRecipeMetadataStorage", "getEntries", new Class<?>[0]);
        int total = entries instanceof java.util.Set<?> ? ((java.util.Set<?>) entries).size() : 0;
        int visited = 0, unknown = 0;
        Map<Object, String> known = publicMetadata && total > 0 ? publicMetadataKeys(recipe.getClass().getClassLoader()) : null;
        if (entries instanceof Iterable<?>) for (Object entry : (Iterable<?>) entries) {
            if (visited >= METADATA_LIMIT || (visited > 0 && expired(started))) break;
            if (!(entry instanceof Map.Entry<?, ?>)) continue;
            Object key = ((Map.Entry<?, ?>) entry).getKey();
            JsonObject data = new JsonObject();
            data.addProperty("keyClass", key == null ? "unavailable" : key.getClass().getName());
            if (publicMetadata) {
                String name = known == null ? null : known.get(key);
                if (name == null) {
                    unknown++; data.addProperty("available", false);
                    data.addProperty("reason", "unrecognized_public_metadata_key");
                } else {
                    data.addProperty("identifier", name);
                    if ("recycle".equals(name)) data.addProperty("publicKeyIdentity", "gregtech.api.util.GTRecipeConstants.RECYCLE");
                    JsonElement described = value(((Map.Entry<?, ?>) entry).getValue(), 0);
                    data.add("value", described);
                    if (!publicValueComplete(described)) unknown++;
                }
            } else {
                data.add("identifier", value(AdapterAccess.field(key, "gregtech.api.recipe.RecipeMetadataKey", "identifier", true), 0));
                Object type = AdapterAccess.field(key, "gregtech.api.recipe.RecipeMetadataKey", "clazz", true);
                data.addProperty("valueClass", type instanceof Class<?> ? ((Class<?>) type).getName() : "unavailable");
                data.add("value", value(((Map.Entry<?, ?>) entry).getValue(), 0));
            }
            metadata.add(data); visited++;
        }
        result.add("metadata", metadata); result.addProperty("metadataTotal", total);
        result.addProperty("metadataTruncated", visited < total);
        if (publicMetadata) {
            result.addProperty("metadataUnknown", unknown > 0);
            result.addProperty("metadataUnknownCount", unknown);
            result.addProperty("publicMetadataComplete", unknown == 0 && visited == total);
            result.addProperty("executionValidated", false);
            result.addProperty("conditionsCoverage", "Registered raw fields and recognized public GTRecipeConstants only; custom machine/backend checks and unknown metadata are not evaluated.");
            for (JsonElement entry : result.getAsJsonObject("items").getAsJsonObject("inputs").getAsJsonArray("entries")) {
                JsonObject input = entry.getAsJsonObject();
                if (input.get("stack").isJsonObject() && input.getAsJsonObject("stack").has("count")) input.addProperty("nonConsumedCatalyst", input.getAsJsonObject("stack").get("count").getAsInt() == 0);
            }
        }
        if (AdapterAccess.type(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt") != null) {
            result.add("alternativeItemInputs", value(AdapterAccess.field(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt", "mOreDictAlt", false), 0));
            result.add("oreDictionaryIds", value(AdapterAccess.field(recipe, "gregtech.api.util.GTRecipe$GTRecipe_WithAlt", "mOreDictIds", false), 0));
        }
        result.add("alternativeFluidInputs", value(AdapterAccess.field(recipe, RECIPE, "mAltFluidInputs", false), 0));
        result.addProperty("softBudgetPartial", expired(started));
        result.addProperty("stackLimitPerGroup", STACK_LIMIT); result.addProperty("metadataLimit", METADATA_LIMIT);
        result.addProperty("genericValueLimit", VALUE_LIMIT);
        bound(result, 32 * 1024);
        if (publicMetadata && result.has("byteLimited")) result.addProperty("publicMetadataComplete", false);
        return result;
    }

    /** Fixed public condition constants; never inspect private key names or call arbitrary key serializers. */
    private static synchronized Map<Object, String> publicMetadataKeys(ClassLoader loader) {
        if (publicKeys != null && publicKeys.loader == loader) return publicKeys.names;
        Map<Object, String> result = new IdentityHashMap<Object, String>();
        try {
            Class<?> api = Class.forName("gregtech.api.util.GTRecipeConstants", false, loader);
            for (String[] key : new String[][] {{"LOW_GRAVITY", "low_gravity"}, {"CLEANROOM", "cleanroom"},
                    {"COIL_HEAT", "coil_heat"}, {"FUSION_THRESHOLD", "fusion_threshold"}, {"RESEARCH_ITEM", "research_item"},
                    {"SCANNING", "scanning"}, {"COMPRESSION_TIER", "compression_tier"}, {"QFT_CATALYST", "qft_catalyst"},
                    {"GLASS", "GLASS"}, {"SIEVERT", "SIEVERT"}, {"RECYCLE", "recycle"}}) {
                try { result.put(api.getField(key[0]).get(null), key[1]); }
                catch (NoSuchFieldException absent) { /* Version-specific public key remains explicitly unknown. */ }
            }
        } catch (ClassNotFoundException | IllegalAccessException | RuntimeException | LinkageError unavailable) {
            // Preserve raw recipe parameters and report every unresolved key as unknown.
        }
        publicKeys = new PublicMetadataKeys(loader, result);
        return result;
    }

    private static final class PublicMetadataKeys {
        final ClassLoader loader;
        final Map<Object, String> names;
        PublicMetadataKeys(ClassLoader loader, Map<Object, String> names) { this.loader = loader; this.names = names; }
    }

    private static boolean publicValueComplete(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) if (!publicValueComplete(child)) return false;
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("available") && !object.get("available").getAsBoolean()) return false;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if ((entry.getKey().equals("truncated") || entry.getKey().endsWith("Truncated")) && entry.getValue().isJsonPrimitive() && entry.getValue().getAsBoolean()) return false;
                if (!publicValueComplete(entry.getValue())) return false;
            }
        }
        return true;
    }

    private static JsonObject stacks(Object recipe, String inputs, String outputs, String inputChance, String outputChance, long started) {
        JsonObject result = new JsonObject();
        result.add("inputs", stackArray(recipe, inputs, inputChance, started));
        result.add("outputs", stackArray(recipe, outputs, outputChance, started));
        return result;
    }

    private static JsonObject stackArray(Object recipe, String name, String chance, long started) {
        Object array = AdapterAccess.field(recipe, RECIPE, name, false);
        int total = array != null && array.getClass().isArray() ? Array.getLength(array) : 0;
        JsonObject result = new JsonObject(); JsonArray data = new JsonArray();
        for (int i = 0; i < Math.min(total, STACK_LIMIT); i++) {
            if (i > 0 && expired(started)) break;
            JsonObject entry = new JsonObject(); entry.addProperty("index", i);
            entry.add("stack", value(Array.get(array, i), 0));
            entry.add("chance", value(AdapterAccess.call(recipe, chance, new Class<?>[] {int.class}, i), 0));
            data.add(entry);
        }
        result.add("entries", data); result.addProperty("total", total); result.addProperty("truncated", data.size() < total);
        return result;
    }

    /** Bounded typed values. Unknown metadata remains explicit, with no arbitrary toString/serialization. */
    public static JsonElement value(Object object, int depth) {
        return value(object, depth, new int[] {128});
    }
    private static JsonElement value(Object object, int depth, int[] remaining) {
        if (object == null) return JsonNull.INSTANCE;
        if (--remaining[0] < 0) return unavailable(object, "value_node_limit");
        if (object instanceof String) return new JsonPrimitive(JsonData.bounded((String) object, 512));
        if (object instanceof Boolean) return new JsonPrimitive((Boolean) object);
        if (object instanceof Number) {
            double number = ((Number) object).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) return new JsonPrimitive("non_finite");
            return new JsonPrimitive((Number) object);
        }
        if (object instanceof Enum<?>) return new JsonPrimitive(((Enum<?>) object).name());
        if (object instanceof ItemStack) {
            try {
                JsonObject result = JsonData.item((ItemStack) object).getAsJsonObject();
                if (((ItemStack) object).hasTagCompound()) result.add("nbt", BoundedNbt.describe(((ItemStack) object).getTagCompound()));
                return result;
            }
            catch (RuntimeException | LinkageError error) { return unavailable(object, "item_identity_error"); }
        }
        if (object instanceof FluidStack) {
            FluidStack fluid = (FluidStack) object; JsonObject result = new JsonObject();
            result.addProperty("fluid", fluid.getFluid() == null ? "unavailable" : fluid.getFluid().getName());
            result.addProperty("amount", fluid.amount); result.addProperty("hasTag", fluid.tag != null);
            if (fluid.tag != null) result.add("nbt", BoundedNbt.describe(fluid.tag));
            return result;
        }
        if (depth >= 4) return unavailable(object, "depth_limit");
        for (String[] shape : new String[][] {
                {"gregtech.api.util.recipe.Scanning", "time", "voltage"},
                {"gregtech.api.util.recipe.Sievert", "sievert", "isExact"},
                {"gregtech.api.util.recipe.SolarFactoryRecipeData", "minimumWaferTier", "minimumWaferCount", "tierRequired"},
                {"gregtech.api.util.recipe.QuantumComputerRecipeData", "heatConstant", "coolConstant", "computation", "maxHeat", "subZero"},
                {"gregtech.api.enums.Materials", "mName", "mDefaultLocalName", "mCustomID"}}) {
            if (AdapterAccess.type(object, shape[0]) == null) continue;
            JsonObject result = new JsonObject(); result.addProperty("class", shape[0]);
            for (int i = 1; i < shape.length; i++) result.add(shape[i], value(AdapterAccess.field(object, shape[0], shape[i], false), depth + 1, remaining));
            return result;
        }
        if (object.getClass().isArray() || object instanceof List<?>) {
            int total = object instanceof List<?> ? ((List<?>) object).size() : Array.getLength(object);
            JsonObject result = new JsonObject(); JsonArray entries = new JsonArray();
            for (int i = 0; i < Math.min(total, VALUE_LIMIT); i++)
                entries.add(value(object instanceof List<?> ? ((List<?>) object).get(i) : Array.get(object, i), depth + 1, remaining));
            result.add("entries", entries); result.addProperty("total", total); result.addProperty("truncated", total > VALUE_LIMIT);
            return result;
        }
        return unavailable(object, "unsupported_metadata_type");
    }
    /** Reduce bounded arrays as a last output guard; preserve explicit truncation rather than serializing huge trees. */
    public static void bound(JsonObject result, int bytes) {
        for (int attempt = 0; result.toString().getBytes(StandardCharsets.UTF_8).length > bytes && attempt < 128; attempt++) {
            ArrayOwner largest = largest(result, null, "");
            if (largest == null || largest.array.size() == 0) throw new BridgeException("too_large", "Adapter output cannot fit its bounded byte budget.");
            JsonArray smaller = new JsonArray();
            for (int i = 0; i < largest.array.size() / 2; i++) smaller.add(largest.array.get(i));
            largest.owner.add(largest.key, smaller);
            largest.owner.addProperty("entries".equals(largest.key) ? "truncated" : largest.key + "Truncated", true);
            result.addProperty("byteLimited", true);
        }
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > bytes) throw new BridgeException("too_large", "Adapter byte budget exceeded.");
    }
    private static ArrayOwner largest(JsonElement element, JsonObject owner, String key) {
        ArrayOwner result = null;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (owner != null && array.size() > 0) result = new ArrayOwner(owner, key, array);
            for (JsonElement child : array) { ArrayOwner candidate = largest(child, null, "");
                if (candidate != null && (result == null || candidate.array.size() > result.array.size())) result = candidate; }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                ArrayOwner candidate = largest(entry.getValue(), element.getAsJsonObject(), entry.getKey());
                if (candidate != null && (result == null || candidate.array.size() > result.array.size())) result = candidate;
            }
        }
        return result;
    }
    private static final class ArrayOwner {
        final JsonObject owner; final String key; final JsonArray array;
        ArrayOwner(JsonObject owner, String key, JsonArray array) { this.owner = owner; this.key = key; this.array = array; }
    }
    private static JsonObject unavailable(Object object, String reason) {
        JsonObject result = new JsonObject(); result.addProperty("available", false);
        result.addProperty("class", JsonData.bounded(object.getClass().getName(), 256)); result.addProperty("reason", reason); return result;
    }
    public static boolean expired(long started) { return System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS; }
}
