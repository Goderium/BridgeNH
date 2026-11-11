package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.recipe.ResearchDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.StatCollector;

/** Existing AspectRecipeIndex 1.1.5 cached semantics; never loads or constructs recipes. */
final class NeiRecipeSemantics {
    private static final String ROOT = "com.gtnewhorizons.aspectrecipeindex.";
    private static final String HANDLER = ROOT + "nei.TemplateThaumHandler";
    private static final String RECIPE = HANDLER + "$CachedThaumRecipe";
    private static final String INFUSION = ROOT + "nei.InfusionRecipeHandler$InfusionCachedRecipe";
    private static final String RESEARCH = ROOT + "nei.ResearchInfo";
    private static final String CONFIG = ROOT + "util.ARIConfig";
    private static final String UTIL = ROOT + "util.Util";
    private static final String ASPECT = "thaumcraft.api.aspects.Aspect";
    private static final String ASPECT_LIST = "thaumcraft.api.aspects.AspectList";
    private static final String API = "thaumcraft.api.ThaumcraftApiHelper";
    private static final String TEMPLATE = "codechicken.nei.recipe.TemplateRecipeHandler";
    private static final String SOURCE = "AspectRecipeIndex 1.1.5 cached recipe + public Thaumcraft 4.2.3.5 aspect API";
    private static final int BYTE_LIMIT = 112 * 1024;
    private static final int TEXT_PREVIEW = 512;
    private static final int CONTEXT_ENTRIES = 64;
    private static final long SOFT_NANOS = 4000000L;
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHODS = new ConcurrentHashMap<>();

    static JsonObject read(Object handler, int recipeIndex, int offset, int limit, long started) {
        validate(offset, limit);
        JsonObject result = base(handler, recipeIndex, offset, limit);
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return unavailable(result, "not_present", "Current cached handler is not an AspectRecipeIndex Thaum handler.");
            describe(result, model);
            List<JsonObject> entries = descriptors(model);
            return page(result, entries.size(), offset, limit, started, i -> entries.get(i));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return failed(result, error);
        }
    }

    /** Four bounded descriptors; large values remain in their existing model and are read through metadataValue. */
    static List<JsonObject> metadataEntries(Object handler, int recipeIndex) {
        try {
            Model model = model(handler, recipeIndex);
            return model == null ? Collections.emptyList() : descriptors(model);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return Collections.emptyList();
        }
    }

    /** Small direct links available through existing recipe/summary schemas, without title or machine searches. */
    static JsonObject researchLinks(Object handler, int recipeIndex, EntityPlayer player, long started) {
        JsonObject result = new JsonObject(); JsonArray entries = new JsonArray(); result.add("entries", entries);
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return unavailable(result, "not_present", "Current cached handler has no audited research links.");
            if (!model.researchShown) return unavailable(result, "hidden", "showResearchKey=false; current NEI research labels are hidden.");
            int next = 0;
            while (next < model.research.size() && entries.size() < 4) {
                if (entries.size() > 0 && expired(started)) break;
                Object research = researchAt(model, next); JsonObject entry = researchEntry(model, research, next, player, started, true);
                entries.add(entry); next++;
            }
            result.addProperty("status", "ok"); result.addProperty("total", model.research.size());
            result.addProperty("nextOffset", next); result.addProperty("hasMore", next < model.research.size());
            result.addProperty("complete", next >= model.research.size()); result.addProperty("limit", 4);
            result.addProperty("source", "NEI's existing ResearchInfo list; remaining entries use metadata_value research continuation.");
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            result.addProperty("status", "unsupported"); result.addProperty("complete", false);
            result.addProperty("reason", "Audited research links unavailable: " + error.getClass().getSimpleName());
            return result;
        }
    }

    /**
     * Fixed keys: 0 shouldShowRecipe, 1 aspects, 2 instability, 3 research.
     * Aspects [entry, field=0/1/2] reads tag/name/description text.
     * Research [entry] reads produced tooltip lines; [entry,line] reads one complete line in character pages.
     */
    static JsonObject metadataValue(Object handler, int recipeIndex, int metadataIndex, int[] valuePath,
            int offset, int limit, long started) {
        if (offset < 0 || limit < 1 || limit > 8192) throw new BridgeException("invalid_args", "Semantic value offset must be nonnegative and limit from 1 to 8192.");
        if (metadataIndex < 0 || metadataIndex > 3) throw new BridgeException("not_found", "AspectRecipeIndex metadataIndex is absent.");
        int[] path = valuePath == null ? new int[0] : valuePath;
        for (int index : path) if (index < 0) throw new BridgeException("invalid_args", "valuePath indices must be nonnegative.");
        JsonObject result = base(handler, recipeIndex, offset, limit);
        result.addProperty("metadataIndex", metadataIndex);
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return unavailable(result, "not_present", "Current cached handler is not an AspectRecipeIndex Thaum handler.");
            describe(result, model);
            if (metadataIndex == 0) {
                noPath(path);
                return scalar(result, model.shown, offset, started);
            }
            if (metadataIndex == 1) {
                if (!model.shown) return unavailable(result, "unsupported", "shouldShowRecipe=false; hidden aspect requirements are not read.");
                if (model.aspects == null) return unavailable(result, "not_present", "Cached recipe has no existing AspectList.");
                if (path.length == 0) return page(result, model.aspects.size(), offset, limit, started,
                        i -> aspectEntry(model, aspectAt(model, i), i));
                if (path.length != 2 || path[1] > 2) throw new BridgeException("invalid_args", "Aspect text valuePath must be [aspectIndex, fieldIndex], with fieldIndex 0..2.");
                Object aspect = aspectAt(model, path[0]).getKey();
                if (!aspectNamed(model, aspect)) return unavailable(result, "unsupported", "Undiscovered aspect names are hidden by the current plugin settings.");
                return text(result, aspectText(model, aspect, path[1]), offset, limit, started);
            }
            if (metadataIndex == 2) {
                noPath(path);
                if (!model.shown) return unavailable(result, "unsupported", "shouldShowRecipe=false; hidden instability is not read.");
                if (model.instability == null) return unavailable(result, "not_present", "Cached recipe has no infusion instability.");
                result.getAsJsonObject("data").addProperty("valueSource", "Raw cached infusion instability; showInstabilityNumber controls numeric drawing.");
                return scalar(result, model.instability, offset, started);
            }
            if (!model.researchShown) return unavailable(result, "unsupported", "showResearchKey=false; research hints are hidden by the current plugin settings.");
            if (path.length == 0) return page(result, model.research.size(), offset, limit, started,
                    i -> researchEntry(model, researchAt(model, i), i));
            if (path.length > 2) throw new BridgeException("invalid_args", "Research valuePath must be [researchIndex] or [researchIndex, lineIndex].");
            Object research = researchAt(model, path[0]);
            if (path.length == 2) {
                TooltipWindow lines = tooltip(model, research, path[1], 1);
                if (path[1] >= lines.size()) throw new BridgeException("not_found", "Research tooltip lineIndex is absent.");
                return text(result, lines.stored(path[1]), offset, limit, started);
            }
            TooltipWindow lines = tooltip(model, research, offset, Math.min(limit, 64));
            result.addProperty("tooltipProducer", RESEARCH + ".onHover(List<String>)");
            result.addProperty("tooltipCoverage", "Exactly the lines produced by the existing public tooltip callback; it internally catches some prerequisite lookup errors.");
            return page(result, lines.size(), offset, limit, started, i -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("lineIndex", i);
                preview(entry, "text", lines.stored(i));
                entry.add("textContinuation", continuation(3, path[0], i));
                return entry;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return failed(result, error);
        }
    }

    /**
     * Content fingerprint of a bounded existing-model prefix. No newly allocated JsonObject/model identity is used.
     * This is not a revision counter for entries outside that prefix or current player research used inside onHover.
     */
    static String context(Object handler, int recipeIndex) {
        try {
            Model model = model(handler, recipeIndex);
            if (model == null) return "aspectrecipeindex:not_present";
            long hash = 0xcbf29ce484222325L;
            hash = mix(hash, handler.getClass().getName());
            hash = mix(hash, model.cached.getClass().getName());
            hash = mix(hash, recipeIndex);
            hash = mix(hash, model.shown);
            hash = mix(hash, model.researchShown);
            hash = mix(hash, model.showAspectNames);
            hash = mix(hash, model.showInstabilityNumber);
            hash = mix(hash, model.instability);
            hash = mix(hash, model.aspects == null ? -1 : model.aspects.size());
            int seen = 0;
            if (model.aspects != null) for (Map.Entry<?, ?> entry : model.aspects.entrySet()) {
                if (seen++ >= CONTEXT_ENTRIES) break;
                Object aspect = entry.getKey();
                hash = mix(hash, entry.getValue());
                boolean named = aspectNamed(model, aspect);
                hash = mix(hash, named);
                if (named) for (int field = 0; field < 3; field++) hash = mix(hash, aspectText(model, aspect, field));
            }
            hash = mix(hash, model.research.size());
            for (int i = 0; i < Math.min(CONTEXT_ENTRIES, model.research.size()); i++) {
                Object research = researchAt(model, i);
                hash = mix(hash, field(model.loader, RESEARCH, research, "isResearched", false));
                hash = mix(hash, field(model.loader, RESEARCH, research, "category", false));
                hash = mix(hash, field(model.loader, RESEARCH, research, "research", false));
            }
            return "aspectrecipeindex:" + Long.toHexString(hash);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return "aspectrecipeindex:unsupported:" + error.getClass().getSimpleName();
        }
    }

    private static Model model(Object handler, int recipeIndex) throws ReflectiveOperationException {
        if (handler == null || !handler.getClass().getName().startsWith(ROOT + "nei.")) return null;
        ClassLoader loader = handler.getClass().getClassLoader();
        Class<?> handlerApi;
        try { handlerApi = Class.forName(HANDLER, false, loader); }
        catch (ClassNotFoundException missing) { return null; }
        if (!handlerApi.isInstance(handler)) return null;
        Object recipes = field(loader, TEMPLATE, handler, "arecipes", true);
        if (!(recipes instanceof List<?>)) throw new BridgeException("unsupported", "Existing cached arecipes is not a List.");
        List<?> cached = (List<?>) recipes;
        if (recipeIndex < 0 || recipeIndex >= cached.size()) throw new BridgeException("not_found", "Cached recipeIndex is absent.");
        Object recipe = cached.get(recipeIndex);
        if (recipe == null || !Class.forName(RECIPE, false, loader).isInstance(recipe)
                || !recipe.getClass().getName().startsWith(ROOT + "nei.")) {
            throw new BridgeException("unsupported", "Current cached recipe has no supported Thaum semantic model.");
        }
        Model model = new Model();
        model.loader = loader;
        model.cached = recipe;
        model.shown = bool(field(loader, RECIPE, recipe, "shouldShowRecipe", false));
        model.researchShown = bool(field(loader, CONFIG, null, "showResearchKey", true));
        model.showAspectNames = bool(field(loader, CONFIG, null, "showUndiscoveredAspectNames", true));
        model.showInstabilityNumber = bool(field(loader, CONFIG, null, "showInstabilityNumber", true));
        if (model.shown) {
            Object aspects = call(loader, RECIPE, recipe, "getAspects", new Class<?>[0]);
            if (aspects != null) {
                Object values = field(loader, ASPECT_LIST, aspects, "aspects", true);
                if (!(values instanceof Map<?, ?>)) throw new BridgeException("unsupported", "Existing AspectList.aspects is not a Map.");
                model.aspects = (Map<?, ?>) values;
            }
            if (Class.forName(INFUSION, false, loader).isInstance(recipe)) {
                Object instability = field(loader, INFUSION, recipe, "instability", false);
                if (!(instability instanceof Number)) throw new BridgeException("unsupported", "Infusion instability is not numeric.");
                model.instability = (Number) instability;
            }
        }
        if (model.researchShown) {
            Object research = field(loader, RECIPE, recipe, "prereqs", false);
            if (!(research instanceof List<?>)) throw new BridgeException("unsupported", "Existing research prerequisites are not a List.");
            model.research = (List<?>) research;
        }
        return model;
    }

    private static List<JsonObject> descriptors(Model model) {
        List<JsonObject> result = new ArrayList<>(4);
        result.add(descriptor(0, "shouldShowRecipe", primitive(model.shown), "available", RECIPE + ".shouldShowRecipe"));
        result.add(descriptor(1, "aspects", collection(model.shown && model.aspects != null ? model.aspects.size() : 0,
                1, "Existing AspectList map encounter order"), !model.shown ? "hidden" : model.aspects == null ? "not_present" : "available",
                RECIPE + ".getAspects() -> AspectList.aspects"));
        result.add(descriptor(2, "instability", model.instability == null ? JsonNull.INSTANCE : primitive(model.instability),
                !model.shown ? "hidden" : model.instability == null ? "not_present" : "available", INFUSION + ".instability"));
        result.get(2).addProperty("valueKind", "raw_cached_instability");
        result.add(descriptor(3, "research", collection(model.research.size(), 3, "Existing prereqs list order"),
                model.researchShown ? "available" : "hidden", RECIPE + ".prereqs -> existing ResearchInfo"));
        return result;
    }

    private static JsonObject descriptor(int index, String key, JsonElement value, String availability, String source) {
        JsonObject entry = new JsonObject();
        entry.addProperty("semanticMetadataIndex", index);
        entry.addProperty("provider", "AspectRecipeIndex");
        entry.addProperty("providerClass", NeiRecipeSemantics.class.getName());
        entry.addProperty("key", key);
        entry.add("value", value);
        entry.addProperty("source", source);
        entry.addProperty("availability", availability);
        return entry;
    }

    private static JsonObject collection(int total, int metadataIndex, String order) {
        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.addProperty("order", order);
        result.add("continuation", continuation(metadataIndex));
        return result;
    }

    private static JsonObject continuation(int metadataIndex, int... path) {
        JsonObject result = new JsonObject();
        result.addProperty("target", "metadata_value");
        result.addProperty("semanticMetadataIndex", metadataIndex);
        JsonArray values = new JsonArray();
        for (int index : path) values.add(primitive(index));
        result.add("valuePath", values);
        result.addProperty("offset", 0);
        return result;
    }

    private static Map.Entry<?, ?> aspectAt(Model model, int index) {
        if (index < 0 || index >= model.aspects.size()) throw new BridgeException("not_found", "aspectIndex is absent.");
        int current = 0;
        for (Map.Entry<?, ?> entry : model.aspects.entrySet()) if (current++ == index) return entry;
        throw new BridgeException("not_found", "Aspect map changed while being read.");
    }

    private static JsonObject aspectEntry(Model model, Map.Entry<?, ?> pair, int index) throws ReflectiveOperationException {
        JsonObject entry = new JsonObject();
        entry.addProperty("aspectIndex", index);
        entry.addProperty("source", ASPECT_LIST + ".aspects existing entry");
        if (!(pair.getValue() instanceof Number)) throw new BridgeException("unsupported", "Aspect amount is not numeric.");
        entry.addProperty("amount", (Number) pair.getValue());
        Object aspect = pair.getKey();
        boolean discovered = aspectDiscovered(model, aspect);
        boolean named = model.showAspectNames || discovered;
        entry.addProperty("discovered", discovered);
        entry.addProperty("namesVisible", named);
        entry.addProperty("availability", named ? "available" : "identity_hidden");
        if (!named) {
            entry.add("tag", JsonNull.INSTANCE);
            preview(entry, "name", StatCollector.translateToLocal("tc.aspect.unknown"));
            preview(entry, "description", StatCollector.translateToLocal("tc.aspect.unknown"));
            return entry;
        }
        for (int field = 0; field < 3; field++) {
            String key = new String[] {"tag", "name", "description"}[field];
            preview(entry, key, aspectText(model, aspect, field));
            entry.add(key + "Continuation", continuation(1, index, field));
        }
        entry.addProperty("color", (Number) call(model.loader, ASPECT, aspect, "getColor", new Class<?>[0]));
        return entry;
    }

    private static boolean aspectNamed(Model model, Object aspect) throws ReflectiveOperationException {
        return model.showAspectNames || aspectDiscovered(model, aspect);
    }

    private static boolean aspectDiscovered(Model model, Object aspect) throws ReflectiveOperationException {
        if (aspect == null || !Class.forName(ASPECT, false, model.loader).isInstance(aspect)) {
            throw new BridgeException("unsupported", "Cached AspectList contains an unsupported aspect.");
        }
        Object username = call(model.loader, UTIL, null, "getUsername", new Class<?>[0]);
        return bool(call(model.loader, API, null, "hasDiscoveredAspect",
                new Class<?>[] {String.class, Class.forName(ASPECT, false, model.loader)}, username, aspect));
    }

    private static String aspectText(Model model, Object aspect, int field) throws ReflectiveOperationException {
        String method = new String[] {"getTag", "getName", "getLocalizedDescription"}[field];
        Object value = call(model.loader, ASPECT, aspect, method, new Class<?>[0]);
        if (!(value instanceof String)) throw new BridgeException("unsupported", "Aspect text getter did not return text.");
        return (String) value;
    }

    private static Object researchAt(Model model, int index) {
        if (index < 0 || index >= model.research.size()) throw new BridgeException("not_found", "researchIndex is absent.");
        Object research = model.research.get(index);
        if (research == null || !RESEARCH.equals(research.getClass().getName())) {
            throw new BridgeException("unsupported", "Only the known existing ResearchInfo class has a verified text callback.");
        }
        return research;
    }

    private static JsonObject researchEntry(Model model, Object research, int index) throws ReflectiveOperationException {
        return researchEntry(model, research, index, null, System.nanoTime(), false);
    }

    private static JsonObject researchEntry(Model model, Object research, int index, EntityPlayer player, long started, boolean includePreview) throws ReflectiveOperationException {
        JsonObject entry = new JsonObject();
        entry.addProperty("researchIndex", index);
        entry.addProperty("class", RESEARCH);
        entry.addProperty("isResearched", bool(field(model.loader, RESEARCH, research, "isResearched", false)));
        Object category = field(model.loader, RESEARCH, research, "category", false);
        if (category == null) entry.add("category", JsonNull.INSTANCE);
        else preview(entry, "category", string(category));
        preview(entry, "name", string(field(model.loader, RESEARCH, research, "research", false)));
        entry.addProperty("source", "Stored ResearchInfo fields; parent prerequisites are produced by its public onHover callback.");
        entry.addProperty("isResearchedSource", "NEI cached boolean at recipe creation; use link.isResearchComplete for fresh player knowledge.");
        entry.add("link", researchLink(model, research, player, started, includePreview));
        entry.add("tooltipContinuation", continuation(3, index));
        return entry;
    }

    private static JsonObject researchLink(Model model, Object research, EntityPlayer player, long started, boolean preview) {
        try { return ResearchDataAdapter.linked(model.loader, field(model.loader, RESEARCH, research, "researchItem", false), player, started, preview); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            JsonObject result = new JsonObject(); result.addProperty("status", "unresolved");
            result.addProperty("reason", "Existing ResearchInfo has no supported direct ResearchItem link; its title does not identify every prerequisite.");
            return result;
        }
    }

    private static TooltipWindow tooltip(Model model, Object research, int offset, int limit) throws ReflectiveOperationException {
        TooltipWindow result = new TooltipWindow(offset, limit);
        call(model.loader, RESEARCH, research, "onHover", new Class<?>[] {List.class}, result);
        return result;
    }

    private static JsonObject base(Object handler, int recipeIndex, int offset, int limit) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("provider", "AspectRecipeIndex");
        result.addProperty("providerClass", NeiRecipeSemantics.class.getName());
        result.addProperty("source", SOURCE);
        result.addProperty("availability", "available");
        result.addProperty("recipeIndex", recipeIndex);
        if (handler != null) result.addProperty("handlerClass", handler.getClass().getName());
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("total", 0);
        result.addProperty("nextOffset", 0);
        result.addProperty("hasMore", false);
        result.addProperty("complete", false);
        result.addProperty("byteLimited", false);
        result.addProperty("softBudgetPartial", false);
        result.add("data", new JsonObject());
        return result;
    }

    private static void describe(JsonObject result, Model model) {
        result.addProperty("cachedRecipeClass", model.cached.getClass().getName());
        JsonObject visibility = new JsonObject();
        visibility.addProperty("shouldShowRecipe", model.shown);
        visibility.addProperty("showResearchKey", model.researchShown);
        visibility.addProperty("showUndiscoveredAspectNames", model.showAspectNames);
        visibility.addProperty("showInstabilityNumber", model.showInstabilityNumber);
        visibility.addProperty("source", "Cached shouldShowRecipe plus existing ARIConfig public boolean fields; aspect discovery follows the same public API as ItemAspect.");
        result.add("knownVisibility", visibility);
        result.addProperty("nonAtomic", true);
        result.addProperty("contextCoverage", "Visibility, existing counts, instability, first 64 aspect amount/text entries and first 64 stored research fields; text hashed over at most 4096 UTF-16 characters. No revision guarantee for later entries or player prerequisite completion read inside onHover.");
        result.addProperty("budgetSource", "4 ms soft deadline; the first entry and synchronous verified getter/tooltip callback may finish. Only a requested tooltip window is retained.");
    }

    private static JsonObject page(JsonObject result, int total, int offset, int limit, long started, Reader reader)
            throws ReflectiveOperationException {
        limit = Math.min(limit, 64);
        result.addProperty("limit", limit);
        JsonArray entries = new JsonArray();
        int next = Math.min(offset, total);
        boolean truncated = false;
        while (next < total && entries.size() < limit) {
            if (entries.size() > 0 && expired(started)) break;
            JsonObject entry = reader.read(next);
            int bytes = bytes(result) + bytes(entries) + bytes(entry);
            if (bytes > BYTE_LIMIT && entries.size() > 0) {
                result.addProperty("byteLimited", true);
                break;
            }
            entries.add(entry);
            truncated |= hasTruncation(entry);
            next++;
        }
        result.getAsJsonObject("data").add("entries", entries);
        result.addProperty("total", total);
        result.addProperty("nextOffset", next);
        result.addProperty("hasMore", next < total);
        result.addProperty("complete", next >= total && !truncated);
        result.addProperty("softBudgetPartial", next < total && expired(started));
        result.addProperty("softBudgetExceeded", expired(started));
        return result;
    }

    private static JsonObject scalar(JsonObject result, Object value, int offset, long started) {
        if (offset == 0) result.getAsJsonObject("data").add("value", primitive(value));
        result.addProperty("total", 1);
        result.addProperty("nextOffset", 1);
        result.addProperty("complete", true);
        result.addProperty("softBudgetExceeded", expired(started));
        return result;
    }

    private static JsonObject text(JsonObject result, String value, int offset, int limit, long started) {
        int total = value.codePointCount(0, value.length());
        int next = (int) Math.min((long) total, (long) offset + limit);
        int from = Math.min(offset, total);
        JsonObject data = result.getAsJsonObject("data");
        data.addProperty("text", value.substring(value.offsetByCodePoints(0, from), value.offsetByCodePoints(0, next)));
        data.addProperty("offsetUnit", "unicode_codepoints");
        result.addProperty("total", total);
        result.addProperty("nextOffset", next);
        result.addProperty("hasMore", next < total);
        result.addProperty("complete", next >= total);
        result.addProperty("softBudgetExceeded", expired(started));
        return result;
    }

    private static void preview(JsonObject result, String key, String value) {
        int total = value.codePointCount(0, value.length());
        result.addProperty(key, value.substring(0, value.offsetByCodePoints(0, Math.min(total, TEXT_PREVIEW))));
        result.addProperty(key + "Characters", total);
        result.addProperty(key + "Truncated", total > TEXT_PREVIEW);
    }

    private static boolean hasTruncation(JsonObject value) {
        for (Map.Entry<String, JsonElement> field : value.entrySet()) {
            if (field.getKey().endsWith("Truncated") && field.getValue().getAsBoolean()) return true;
        }
        return false;
    }

    private static JsonObject unavailable(JsonObject result, String status, String reason) {
        result.addProperty("status", status);
        result.addProperty("availability", status);
        result.addProperty("reason", reason);
        result.addProperty("total", 0);
        result.addProperty("nextOffset", 0);
        result.addProperty("hasMore", false);
        result.addProperty("complete", false);
        return result;
    }

    private static JsonObject failed(JsonObject result, Throwable error) {
        if (error instanceof BridgeException) {
            BridgeException bridge = (BridgeException) error;
            if ("invalid_args".equals(bridge.code) || "not_found".equals(bridge.code)) throw bridge;
        }
        return unavailable(result, "unsupported", "Known read-only semantic model unavailable: " + error.getClass().getSimpleName());
    }

    private static Object field(ClassLoader loader, String owner, Object target, String name, boolean publicField)
            throws ReflectiveOperationException {
        Class<?> api = Class.forName(owner, false, loader);
        Map<String, Field> cache = FIELDS.computeIfAbsent(api, ignored -> new ConcurrentHashMap<>());
        Field field = cache.get(name);
        if (field == null) {
            field = publicField ? api.getField(name) : api.getDeclaredField(name);
            if (!publicField) field.setAccessible(true);
            cache.put(name, field);
        }
        return field.get(target);
    }

    private static Object call(ClassLoader loader, String owner, Object target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Class<?> api = Class.forName(owner, false, loader);
        Map<String, Method> cache = METHODS.computeIfAbsent(api, ignored -> new ConcurrentHashMap<>());
        Method method = cache.get(name);
        if (method == null) {
            method = api.getMethod(name, types);
            if (RECIPE.equals(owner)) method.setAccessible(true); // Public getter on a protected known cached class.
            cache.put(name, method);
        }
        return method.invoke(target, args);
    }

    private static JsonElement primitive(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean) return new com.google.gson.JsonPrimitive((Boolean) value);
        if (value instanceof Number) return new com.google.gson.JsonPrimitive((Number) value);
        return new com.google.gson.JsonPrimitive(string(value));
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean)) throw new BridgeException("unsupported", "Known semantic visibility value is not boolean.");
        return (Boolean) value;
    }

    private static String string(Object value) {
        if (!(value instanceof String)) throw new BridgeException("unsupported", "Known semantic text value is not a String.");
        return (String) value;
    }

    private static void noPath(int[] path) {
        if (path.length != 0) throw new BridgeException("invalid_args", "Scalar semantic metadata has no valuePath.");
    }

    private static void validate(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 64) throw new BridgeException("invalid_args", "Semantic offset must be nonnegative and limit from 1 to 64.");
    }

    private static boolean expired(long started) { return System.nanoTime() - started > SOFT_NANOS; }
    private static int bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private static long mix(long hash, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        hash = (hash ^ text.length()) * 0x100000001b3L;
        for (int i = 0; i < Math.min(4096, text.length()); i++) hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        return hash;
    }

    private interface Reader { JsonObject read(int index) throws ReflectiveOperationException; }

    private static final class Model {
        ClassLoader loader;
        Object cached;
        boolean shown, researchShown, showAspectNames, showInstabilityNumber;
        Map<?, ?> aspects;
        Number instability;
        List<?> research = Collections.emptyList();
    }

    /**
     * ResearchInfo.onHover only appends strings, inspects size and inserts one blank line at index 1.
     * Retaining one preceding entry handles that insertion without materializing the full tooltip.
     */
    private static final class TooltipWindow extends AbstractList<String> {
        private final int from;
        private final long end;
        private final Map<Integer, String> stored = new TreeMap<>();
        private int total;

        TooltipWindow(int offset, int limit) {
            from = Math.max(0, offset - 1);
            end = Math.min((long) Integer.MAX_VALUE, (long) offset + limit);
        }

        @Override public int size() { return total; }
        @Override public String get(int index) { return stored(index); }
        @Override public boolean add(String text) { add(total, text); return true; }
        @Override public void add(int index, String text) {
            if (index < 0 || index > total) throw new IndexOutOfBoundsException();
            if (index < total) {
                Map<Integer, String> shifted = new TreeMap<>();
                for (Map.Entry<Integer, String> entry : stored.entrySet()) {
                    int key = entry.getKey() >= index ? entry.getKey() + 1 : entry.getKey();
                    if (key >= from && key < end) shifted.put(key, entry.getValue());
                }
                stored.clear();
                stored.putAll(shifted);
            }
            if (index >= from && index < end) stored.put(index, text);
            total++;
        }

        String stored(int index) {
            if (index < 0 || index >= total || !stored.containsKey(index)) throw new IndexOutOfBoundsException();
            return stored.get(index);
        }
    }
}
