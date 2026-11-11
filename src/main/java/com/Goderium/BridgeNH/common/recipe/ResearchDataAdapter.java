package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.client.nei.NeiPreviewResources;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** Public Thaumcraft 4 research model; never opens a book, completes research or scans recipes. */
public final class ResearchDataAdapter {
    private static final String ROOT = "thaumcraft.api.research.";
    private static final int ENTRY_LIMIT = 32, TEXT_LIMIT = 8192, PREVIEW = 512, BYTE_LIMIT = 96 * 1024;
    private static final int CATEGORY_LIMIT = 128, INDEX_LIMIT = 16384, INDEX_STEP = 256;
    private static final long SOFT_NANOS = 4000000L;
    private static final Map<Class<?>, Api> APIS = new ConcurrentHashMap<>();

    public static JsonObject inspect(Minecraft client, JsonObject args) {
        if (client == null || client.thePlayer == null) throw new BridgeException("not_ready", "A current client player is required.");
        NeiPreviewResources.ensureTracking(client);
        String language = language(client);
        try { return read(api(client.getClass().getClassLoader()), client.thePlayer, client.thePlayer.getCommandSenderName(), language, args, System.nanoTime()); }
        catch (ReflectiveOperationException | LinkageError error) { return unavailable("unsupported", "Public Thaumcraft research API is unavailable: " + error.getClass().getSimpleName()); }
    }

    /** The NEI link supplies the actual object, so no registry/title scan is needed. */
    public static JsonObject linked(ClassLoader loader, Object item, EntityPlayer player, long started, boolean preview) {
        try {
            Api api = api(loader);
            if (item == null || !api.item.isInstance(item)) return unavailable("unresolved", "NEI has no supported ResearchItem link.");
            String username = player == null ? null : player.getCommandSenderName();
            JsonObject result = summary(api, item, player, username, started, preview);
            result.addProperty("resolution", "nei_research_object");
            result.addProperty("linkSource", "AspectRecipeIndex ResearchInfo.researchItem -> public ResearchItem.key/category; title is display text, not a prerequisite list.");
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return unavailable("unsupported", "Existing NEI research link cannot be read: " + error.getClass().getSimpleName());
        }
    }

    static JsonObject read(Api api, EntityPlayer player, String username, String language, JsonObject args, long started)
            throws ReflectiveOperationException {
        validate(args);
        String section = args.has("section") ? JsonData.string(args, "section") : "summary";
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, Integer.MAX_VALUE) : 0;
        int requested = args.has("limit") ? JsonData.integer(args, "limit", 1, TEXT_LIMIT) : "page_text".equals(section) ? 2048 : 16;
        int limit = Math.min(requested, "page_text".equals(section) ? TEXT_LIMIT : ENTRY_LIMIT);
        Resolution resolution = resolve(api, args, language, started);
        if (resolution.item == null) return resolution.result;
        Object item = resolution.item;
        if (!publicIdentity(api, item, username)) return unavailable("unavailable", "The matching research is not a completed public book entry for the current player.");
        JsonObject result = summary(api, item, player, username, started, false);
        result.addProperty("section", section); result.addProperty("resolution", resolution.kind);
        result.addProperty("offset", offset); result.addProperty("limit", limit);
        result.addProperty("requestedLimit", requested); result.addProperty("nonAtomic", true);
        if ("summary".equals(section)) {
            if (offset != 0) throw new BridgeException("invalid_args", "summary requires offset zero.");
            result = summary(api, item, player, username, started, true);
            result.addProperty("section", section); result.addProperty("resolution", resolution.kind);
            result.addProperty("offset", 0); result.addProperty("limit", 1);
            finish(result, 1, 1, false, started);
            return bounded(result);
        }
        JsonArray entries = new JsonArray(); result.add("entries", entries);
        if ("dependencies".equals(section)) {
            dependencyPage(api, item, username, result, entries, offset, limit, started);
            return bounded(result);
        }
        Boolean complete = complete(api, username, string(api.field(api.item, item, "key")));
        if (!Boolean.TRUE.equals(complete)) {
            result.addProperty("status", complete == null ? "unsupported" : "locked");
            result.addProperty("reason", complete == null ? "Live player research completion is unavailable." : "Thaumonomicon pages require completed research.");
            finish(result, 0, 0, true, started);
            return result;
        }
        Object pages = api.call(api.item, item, "getPages");
        int total = pages == null ? 0 : arrayLength(pages);
        if ("pages".equals(section)) {
            int next = Math.min(offset, total);
            while (next < total && entries.size() < limit && (entries.size() == 0 || !expired(started))) {
                JsonObject entry = page(api, Array.get(pages, next), next, player, username, false);
                if (!add(result, entries, entry)) break;
                next++;
            }
            finish(result, total, next, false, started);
        } else {
            int pageIndex = JsonData.integer(args, "pageIndex", 0, Integer.MAX_VALUE);
            if (pageIndex >= total) throw new BridgeException("not_found", "pageIndex is outside the research's original pages.");
            Object selected = Array.get(pages, pageIndex);
            result.addProperty("pageIndex", pageIndex);
            if (selected == null || !api.page.isInstance(selected)) return unavailable("unsupported", "Research page has no supported public model.");
            if (!pageVisible(api, selected, username)) {
                result.addProperty("status", "locked"); result.addProperty("reason", "Concealed page research is incomplete or unavailable.");
                finish(result, 0, 0, true, started);
            } else if ("page_text".equals(section)) {
                String text = string(api.call(api.page, selected, "getTranslatedText"));
                int characters = text.codePointCount(0, text.length());
                int from = Math.min(offset, characters), next = (int) Math.min((long) characters, (long) from + limit);
                result.addProperty("text", text.substring(text.offsetByCodePoints(0, from), text.offsetByCodePoints(0, next)));
                result.addProperty("offsetUnit", "unicode_codepoints"); result.addProperty("textSource", ROOT + "ResearchPage.getTranslatedText()");
                finish(result, characters, next, false, started);
            } else {
                LinkPage window = pageLinks(api, selected, limit + Math.min(offset, 4096));
                List<JsonObject> links = window.entries;
                int next = Math.min(offset, links.size());
                while (next < links.size() && entries.size() < limit && (entries.size() == 0 || !expired(started))) {
                    if (!add(result, entries, links.get(next))) break;
                    next++;
                }
                boolean truncated = window.traversalPartial || window.lowerBound && links.size() >= 4096;
                result.addProperty("linkTraversalLimit", 4096); result.addProperty("linkTraversalPartial", truncated);
                result.addProperty("totalIsLowerBound", window.lowerBound);
                finish(result, links.size(), next, truncated, started);
            }
        }
        return bounded(result);
    }

    private static JsonObject summary(Api api, Object item, EntityPlayer player, String username, long started, boolean includePreview)
            throws ReflectiveOperationException {
        JsonObject result = new JsonObject(); result.addProperty("status", "ok"); describe(result);
        String key = string(api.field(api.item, item, "key")), category = string(api.field(api.item, item, "category"));
        result.addProperty("researchKey", key); result.addProperty("category", category);
        preview(result, "title", string(api.call(api.item, item, "getName")), PREVIEW);
        preview(result, "categoryTitle", string(api.call(api.categories, null, "getCategoryName", new Class<?>[] {String.class}, category)), PREVIEW);
        Boolean complete = complete(api, username, key);
        if (complete == null) result.addProperty("completionStatus", "unavailable"); else result.addProperty("isResearchComplete", complete);
        result.addProperty("knowledgeSource", "Fresh ThaumcraftApiHelper.isResearchComplete(current player, researchKey); never the NEI cached boolean.");
        result.addProperty("source", "Public Thaumcraft 4 ResearchItem; page indices retain original getPages order.");
        JsonObject continuation = new JsonObject(); continuation.addProperty("operation", "research.read");
        continuation.addProperty("researchKey", key); continuation.addProperty("category", category); continuation.addProperty("section", "pages");
        result.add("continuation", continuation);
        result.addProperty("pagesAvailability", Boolean.TRUE.equals(complete) ? "available" : complete == null ? "unsupported" : "locked");
        if (includePreview && expired(started)) {
            result.addProperty("previewStatus", "omitted_soft_budget");
            result.addProperty("previewOmissionReason", "The existing recipe read used its soft budget; direct key/title link is retained. Use continuation for a dedicated book read.");
        } else if (includePreview) {
            try {
                preview(result, "description", string(api.call(api.item, item, "getText")), PREVIEW);
                JsonArray dependencies = new JsonArray(); JsonObject dependencyInfo = new JsonObject();
                dependencyPage(api, item, username, dependencyInfo, dependencies, 0, 4, started);
                result.add("dependenciesPreview", dependencies); result.add("dependenciesPaging", dependencyInfo);
                result.addProperty("previewStatus", "available");
                if (Boolean.TRUE.equals(complete) && !expired(started)) {
                    Object pages = api.call(api.item, item, "getPages");
                    int count = pages == null ? 0 : arrayLength(pages);
                    result.addProperty("pagesTotal", count);
                    JsonArray previews = new JsonArray(); result.add("pagesPreview", previews);
                    for (int i = 0; i < Math.min(count, 2); i++) {
                        if (expired(started)) break;
                        previews.add(page(api, Array.get(pages, i), i, player, username, true));
                    }
                    result.addProperty("pagesPreviewPartial", previews.size() < count);
                } else if (Boolean.TRUE.equals(complete)) {
                    result.addProperty("previewStatus", "partial_soft_budget");
                    result.addProperty("previewOmissionReason", "Page preview deferred after dependency read; use continuation for a dedicated book read.");
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                result.addProperty("previewStatus", "unsupported");
                result.addProperty("previewOmissionReason", "Optional public page/description preview failed: " + error.getClass().getSimpleName() + "; direct research identity is retained.");
            }
        }
        bindContinuations(result, key, category, 0);
        return result;
    }

    private static JsonObject page(Api api, Object page, int index, EntityPlayer player, String username, boolean includeLinks)
            throws ReflectiveOperationException {
        JsonObject result = new JsonObject(); result.addProperty("pageIndex", index);
        if (page == null || !api.page.isInstance(page)) { result.addProperty("availability", "unsupported"); return result; }
        result.addProperty("type", String.valueOf(api.field(api.page, page, "type")));
        boolean visible = pageVisible(api, page, username); result.addProperty("availability", visible ? "available" : "locked");
        if (!visible) return result;
        preview(result, "text", string(api.call(api.page, page, "getTranslatedText")), PREVIEW);
        JsonObject continuation = new JsonObject(); continuation.addProperty("operation", "research.read");
        continuation.addProperty("section", "page_text"); continuation.addProperty("pageIndex", index);
        result.add("textContinuation", continuation);
        Object output = api.field(api.page, page, "recipeOutput");
        if (output instanceof ItemStack) result.add("recipeOutput", JsonData.item((ItemStack) output));
        Object image = api.field(api.page, page, "image"); if (image != null) preview(result, "image", String.valueOf(image), PREVIEW);
        Object recipe = api.field(api.page, page, "recipe");
        result.addProperty("recipeClass", recipe == null ? "" : recipe.getClass().getName());
        if (includeLinks) {
            LinkPage window = pageLinks(api, page, 8); List<JsonObject> links = window.entries; JsonArray array = new JsonArray();
            for (int i = 0; i < Math.min(links.size(), 8); i++) array.add(links.get(i));
            result.add("linksPreview", array); result.addProperty("linksPreviewPartial", links.size() > 8 || window.traversalPartial);
        }
        return result;
    }

    private static boolean pageVisible(Api api, Object page, String username) throws ReflectiveOperationException {
        if (!"TEXT_CONCEALED".equals(String.valueOf(api.field(api.page, page, "type")))) return true;
        Object gate = api.field(api.page, page, "research");
        return gate instanceof String && Boolean.TRUE.equals(complete(api, username, (String) gate));
    }

    private static void dependencyPage(Api api, Object item, String username, JsonObject result, JsonArray entries,
            int offset, int limit, long started) throws ReflectiveOperationException {
        Object parents = api.field(api.item, item, "parents"), hidden = api.field(api.item, item, "parentsHidden");
        int parentCount = parents == null ? 0 : arrayLength(parents), hiddenCount = hidden == null ? 0 : arrayLength(hidden);
        int total = parentCount + hiddenCount, next = Math.min(offset, total);
        while (next < total && entries.size() < limit && (entries.size() == 0 || !expired(started))) {
            String key = string(Array.get(next < parentCount ? parents : hidden, next < parentCount ? next : next - parentCount));
            JsonObject entry = new JsonObject(); entry.addProperty("dependencyIndex", next);
            List<Object> candidates = byKey(api, key, null);
            boolean publicTarget = candidates.size() == 1 && publicIdentity(api, candidates.get(0), username);
            if (candidates.size() == 1 && !publicTarget) {
                entry.addProperty("identityAvailability", "hidden_incomplete");
            } else if (next >= parentCount && !publicTarget) {
                entry.addProperty("identityAvailability", candidates.isEmpty() ? "unresolved" : "ambiguous");
            } else {
                entry.addProperty("kind", next < parentCount ? "parent" : "parent_hidden");
                entry.addProperty("researchKey", key); entry.addProperty("source", "Public ResearchItem.parents/parentsHidden; not inferred from the displayed title.");
                entry.addProperty("resolution", publicTarget ? "resolved" : candidates.isEmpty() ? "not_found" : "ambiguous");
                if (publicTarget) {
                    Object target = candidates.get(0); entry.addProperty("category", string(api.field(api.item, target, "category")));
                    preview(entry, "title", string(api.call(api.item, target, "getName")), PREVIEW);
                }
                Boolean complete = complete(api, username, key); if (complete != null) entry.addProperty("isResearchComplete", complete);
            }
            if (!add(result, entries, entry)) break;
            next++;
        }
        result.addProperty("scope", "Declared parent research only; scan triggers and addon-specific conditions remain in the existing NEI tooltip.");
        finish(result, total, next, false, started);
    }

    private static LinkPage pageLinks(Api api, Object page, int requested) throws ReflectiveOperationException {
        int cap = Math.min(4096, Math.max(9, requested + 1));
        LinkPage window = new LinkPage(); List<JsonObject> result = window.entries;
        itemLink(result, "page_output", api.field(api.page, page, "recipeOutput"), -1);
        Object recipes = api.field(api.page, page, "recipe");
        int count = recipes == null ? 0 : recipes.getClass().isArray() ? Array.getLength(recipes) : recipes instanceof List<?> ? ((List<?>) recipes).size() : 1;
        int visited = 0, variant = 0;
        for (; variant < count && visited < 4096 && result.size() < cap; variant++) {
            int i = variant; visited++;
            Object recipe = recipes.getClass().isArray() ? Array.get(recipes, i) : recipes instanceof List<?> ? ((List<?>) recipes).get(i) : recipes;
            if (recipe instanceof ItemStack) { itemLink(result, "smelting_input", recipe, i); continue; }
            if (recipe == null) continue;
            ClassLoader loader = api.item.getClassLoader();
            Class<?> normal = optionalType(loader, "net.minecraft.item.crafting.IRecipe");
            Class<?> arcane = optionalType(loader, "thaumcraft.api.crafting.IArcaneRecipe");
            Class<?> infusion = optionalType(loader, "thaumcraft.api.crafting.InfusionRecipe");
            Class<?> crucible = optionalType(loader, "thaumcraft.api.crafting.CrucibleRecipe");
            Class<?> owner = arcane != null && arcane.isInstance(recipe) ? arcane : infusion != null && infusion.isInstance(recipe) ? infusion : crucible != null && crucible.isInstance(recipe) ? crucible : normal != null && normal.isInstance(recipe) ? normal : null;
            if (owner == null) {
                JsonObject unsupported = new JsonObject(); unsupported.addProperty("kind", "unsupported_recipe"); unsupported.addProperty("recipeVariantIndex", i);
                unsupported.addProperty("class", recipe.getClass().getName()); result.add(unsupported); continue;
            }
            itemLink(result, "recipe_output", api.call(owner, recipe, "getRecipeOutput"), i);
            if (owner == arcane || owner == infusion) {
                JsonObject link = new JsonObject(); link.addProperty("kind", "recipe_research"); link.addProperty("recipeVariantIndex", i);
                link.addProperty("researchKey", string(api.call(owner, recipe, "getResearch"))); link.addProperty("source", owner.getName() + ".getResearch()"); result.add(link);
            }
            if (owner == infusion) {
                itemLink(result, "infusion_central_input", api.call(owner, recipe, "getRecipeInput"), i);
                Object components = api.call(owner, recipe, "getComponents");
                int n = 0;
                for (; components != null && n < Array.getLength(components) && result.size() < cap && visited < 4096; n++) {
                    visited++;
                    int before = result.size(); itemLink(result, "infusion_component", Array.get(components, n), i);
                    if (result.size() > before) result.get(result.size() - 1).addProperty("componentIndex", n);
                }
                if (components != null && n < Array.getLength(components)) { window.lowerBound = true; window.traversalPartial |= visited >= 4096; }
            } else if (owner == crucible) {
                Object catalyst = api.field(owner, recipe, "catalyst");
                if (catalyst instanceof List<?>) {
                    List<?> values = (List<?>) catalyst; int n = 0;
                    for (; n < values.size() && result.size() < cap && visited < 4096; n++) { visited++; itemLink(result, "crucible_catalyst", values.get(n), i); }
                    if (n < values.size()) { window.lowerBound = true; window.traversalPartial |= visited >= 4096; }
                }
                else itemLink(result, "crucible_catalyst", catalyst, i);
            }
        }
        if (variant < count) { window.lowerBound = true; window.traversalPartial |= visited >= 4096; }
        for (int i = 0; i < result.size(); i++) result.get(i).addProperty("linkIndex", i);
        return window;
    }

    private static void itemLink(List<JsonObject> result, String kind, Object value, int variant) {
        if (!(value instanceof ItemStack)) return;
        JsonObject link = new JsonObject(); link.addProperty("kind", kind); link.addProperty("recipeVariantIndex", variant);
        link.add("item", JsonData.item((ItemStack) value)); link.addProperty("source", "Existing public research page/recipe ItemStack; no item-name lookup or recipe execution."); result.add(link);
    }

    private static Resolution resolve(Api api, JsonObject args, String language, long started) throws ReflectiveOperationException {
        String category = args.has("category") ? JsonData.string(args, "category") : null;
        List<Object> candidates;
        String kind;
        if (args.has("researchKey")) { candidates = byKey(api, JsonData.string(args, "researchKey"), category); kind = "exact_key"; }
        else {
            TitleIndex index = api.index(language); index.advance(api, started);
            if (!index.done) {
                JsonObject result = unavailable("indexing", "Exact localized-title index is still being built; retry the same read. NEI object links and exact keys do not wait for this index.");
                result.addProperty("indexedEntries", index.count); result.addProperty("indexLimit", INDEX_LIMIT); result.addProperty("indexComplete", false);
                return new Resolution(null, result, "exact_title");
            }
            if (index.limited) return new Resolution(null, unavailable("unsupported", "Public research registry exceeds the bounded title index limit; use an exact researchKey and category."), "exact_title");
            List<Object> matches = index.titles.get(JsonData.string(args, "title")); candidates = new ArrayList<>();
            if (matches != null) for (Object match : matches) {
                String actualCategory = string(api.field(api.item, match, "category"));
                if (category != null && !category.equals(actualCategory)) continue;
                String actualKey = string(api.field(api.item, match, "key"));
                Object categoryList = api.categories().get(actualCategory);
                if (categoryList == null || api.research(categoryList).get(actualKey) != match
                        || !JsonData.string(args, "title").equals(api.call(api.item, match, "getName"))) {
                    api.index = null;
                    return new Resolution(null, unavailable("indexing", "Cached title identity changed in the current registry; retry to rebuild the bounded index or use the exact key."), "exact_title");
                }
                candidates.add(match);
            }
            kind = "exact_localized_title";
        }
        if (candidates.size() == 1) return new Resolution(candidates.get(0), null, kind);
        JsonObject result = unavailable(candidates.isEmpty() ? "not_found" : "ambiguous", candidates.isEmpty() ? "No exact research key/title matches in the current public registry." : "Multiple exact matches exist; supply category or the NEI researchKey.");
        result.addProperty("matchesTotal", candidates.size()); JsonArray matches = new JsonArray(); result.add("matches", matches);
        result.addProperty("matchesScope", "Count only; explicit category/key resolves ambiguity without revealing unrelated hidden research identities.");
        return new Resolution(null, result, kind);
    }

    private static List<Object> byKey(Api api, String key, String category) throws ReflectiveOperationException {
        Map<?, ?> categories = api.categories();
        if (categories.size() > CATEGORY_LIMIT) throw new BridgeException("unsupported", "Research category count exceeds the bounded reader limit.");
        List<Object> result = new ArrayList<>();
        if (category != null) {
            Object list = categories.get(category); if (list != null) { Object item = api.research(list).get(key); if (item != null && api.item.isInstance(item)) result.add(item); }
        } else for (Object list : categories.values()) { Object item = api.research(list).get(key); if (item != null && api.item.isInstance(item)) result.add(item); }
        return result;
    }

    private static Boolean complete(Api api, String username, String key) {
        if (username == null || username.isEmpty()) return null;
        try { Object value = api.call(api.helper, null, "isResearchComplete", new Class<?>[] {String.class, String.class}, username, key); return value instanceof Boolean ? (Boolean) value : null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return null; }
    }

    private static boolean publicIdentity(Api api, Object item, String username) throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(complete(api, username, string(api.field(api.item, item, "key"))))) return true;
        for (String flag : new String[] {"isHidden", "isLost", "isConcealed", "isVirtual"}) if (Boolean.TRUE.equals(api.call(api.item, item, flag))) return false;
        return true;
    }

    private static void validate(JsonObject args) {
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!java.util.Arrays.asList("researchKey", "title", "category", "section", "pageIndex", "offset", "limit").contains(entry.getKey())) throw new BridgeException("invalid_args", "Unknown research.read argument: " + entry.getKey());
        if (args.has("researchKey") == args.has("title")) throw new BridgeException("invalid_args", "Supply exactly one researchKey or exact localized title.");
        for (String key : new String[] {"researchKey", "title", "category"}) if (args.has(key)) { String value = JsonData.string(args, key); if (value.isEmpty() || value.length() > ("category".equals(key) ? 128 : 256)) throw new BridgeException("invalid_args", key + " must be nonempty bounded text."); }
        String section = args.has("section") ? JsonData.string(args, "section") : "summary";
        if (!java.util.Arrays.asList("summary", "pages", "page_text", "dependencies", "page_links").contains(section)) throw new BridgeException("invalid_args", "Unknown research section.");
        boolean selected = "page_text".equals(section) || "page_links".equals(section);
        if (selected) JsonData.integer(args, "pageIndex", 0, Integer.MAX_VALUE);
        else if (args.has("pageIndex")) throw new BridgeException("invalid_args", "pageIndex only applies to page_text/page_links.");
    }

    private static Api api(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> categories = Class.forName(ROOT + "ResearchCategories", false, loader);
        Api result = APIS.get(categories);
        if (result == null) { result = new Api(categories, Class.forName(ROOT + "ResearchCategoryList", false, loader), Class.forName(ROOT + "ResearchItem", false, loader), Class.forName(ROOT + "ResearchPage", false, loader), Class.forName("thaumcraft.api.ThaumcraftApiHelper", false, loader)); APIS.put(categories, result); }
        return result;
    }

    static final class Api {
        final Class<?> categories, category, item, page, helper;
        final Map<String, Field> fields = new HashMap<>(); final Map<String, Method> methods = new HashMap<>();
        TitleIndex index;
        Api(Class<?> categories, Class<?> category, Class<?> item, Class<?> page, Class<?> helper) { this.categories = categories; this.category = category; this.item = item; this.page = page; this.helper = helper; }
        Object field(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
            String key = owner.getName() + "/" + name; Field field = fields.get(key); if (field == null) { field = owner.getField(name); fields.put(key, field); } return field.get(target);
        }
        Object call(Class<?> owner, Object target, String name) throws ReflectiveOperationException { return call(owner, target, name, new Class<?>[0]); }
        Object call(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
            String key = owner.getName() + "/" + name; Method method = methods.get(key); if (method == null) { method = owner.getMethod(name, types); methods.put(key, method); } return method.invoke(target, args);
        }
        Map<?, ?> categories() throws ReflectiveOperationException { return map(field(categories, null, "researchCategories")); }
        Map<?, ?> research(Object list) throws ReflectiveOperationException { return map(field(category, list, "research")); }
        TitleIndex index(String language) throws ReflectiveOperationException {
            Map<?, ?> registry = categories(); if (registry.size() > CATEGORY_LIMIT) throw new BridgeException("unsupported", "Research category count exceeds reader limit.");
            List<Object> stamp = new ArrayList<>(); stamp.add(new Identity(registry)); List<Map<?, ?>> maps = new ArrayList<>();
            stamp.add(NeiPreviewResources.context());
            for (Map.Entry<?, ?> entry : registry.entrySet()) { Map<?, ?> research = research(entry.getValue()); stamp.add(entry.getKey()); stamp.add(new Identity(entry.getValue())); stamp.add(new Identity(research)); stamp.add(research.size()); maps.add(research); }
            if (index == null || !index.language.equals(language) || !index.stamp.equals(stamp)) index = new TitleIndex(language, stamp, maps);
            return index;
        }
    }

    private static final class TitleIndex {
        final String language; final List<Object> stamp; final List<Map<?, ?>> maps;
        final Map<String, List<Object>> titles = new HashMap<>(); int category, count; Iterator<?> entries; boolean done, limited;
        TitleIndex(String language, List<Object> stamp, List<Map<?, ?>> maps) { this.language = language; this.stamp = stamp; this.maps = maps; }
        void advance(Api api, long started) throws ReflectiveOperationException {
            for (int step = 0; step < INDEX_STEP && !done && (step == 0 || !expired(started));) {
                if (entries == null || !entries.hasNext()) { if (category >= maps.size()) { done = true; break; } entries = maps.get(category++).values().iterator(); continue; }
                if (count >= INDEX_LIMIT) { done = true; limited = true; break; }
                Object item = entries.next(); count++; step++;
                if (!api.item.isInstance(item)) continue;
                String title = string(api.call(api.item, item, "getName"));
                titles.computeIfAbsent(title, ignored -> new ArrayList<>()).add(item);
            }
        }
    }

    private static final class Resolution {
        final Object item; final JsonObject result; final String kind;
        Resolution(Object item, JsonObject result, String kind) { this.item = item; this.result = result; this.kind = kind; }
    }

    private static final class LinkPage { final List<JsonObject> entries = new ArrayList<>(); boolean lowerBound, traversalPartial; }

    private static final class Identity {
        final Object value;
        Identity(Object value) { this.value = value; }
        @Override public boolean equals(Object other) { return other instanceof Identity && ((Identity) other).value == value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }

    private static String language(Minecraft client) {
        try { Object settings = client.getClass().getField("gameSettings").get(client); return settings == null ? "" : String.valueOf(settings.getClass().getField("language").get(settings)); }
        catch (ReflectiveOperationException | RuntimeException error) { return "unavailable"; }
    }

    private static JsonObject unavailable(String status, String reason) { JsonObject result = new JsonObject(); describe(result); result.addProperty("status", status); result.addProperty("reason", reason); result.addProperty("complete", false); result.addProperty("hasMore", false); return result; }
    private static void finish(JsonObject result, int total, int next, boolean partial, long started) { result.addProperty("total", total); result.addProperty("nextOffset", next); result.addProperty("hasMore", next < total); result.addProperty("complete", next >= total && !partial && !result.has("byteLimited")); result.addProperty("softBudgetPartial", next < total && expired(started)); result.addProperty("nonAtomic", true); }
    private static boolean add(JsonObject result, JsonArray entries, JsonObject entry) { if (bytes(result) + bytes(entries) + bytes(entry) > BYTE_LIMIT) { result.addProperty("byteLimited", true); return false; } entries.add(entry); return true; }
    private static JsonObject bounded(JsonObject result) { if (result.has("researchKey") && result.has("category")) bindContinuations(result, result.get("researchKey").getAsString(), result.get("category").getAsString(), 0); if (bytes(result) > BYTE_LIMIT) throw new BridgeException("too_large", "Research read exceeded bounded output bytes."); return result; }
    private static void bindContinuations(JsonElement value, String key, String category, int depth) { if (depth > 8) return; if (value.isJsonArray()) { for (JsonElement child : value.getAsJsonArray()) bindContinuations(child, key, category, depth + 1); } else if (value.isJsonObject()) { JsonObject object = value.getAsJsonObject(); if (object.has("operation") && "research.read".equals(object.get("operation").getAsString())) { object.addProperty("researchKey", key); object.addProperty("category", category); } for (Map.Entry<String, JsonElement> entry : object.entrySet()) bindContinuations(entry.getValue(), key, category, depth + 1); } }
    private static void describe(JsonObject result) { result.addProperty("schemaVersion", 1); result.addProperty("nonAtomic", true); JsonObject limits = new JsonObject(); limits.addProperty("entries", ENTRY_LIMIT); limits.addProperty("textCodepoints", TEXT_LIMIT); limits.addProperty("resultBytes", BYTE_LIMIT); limits.addProperty("titleIndexEntries", INDEX_LIMIT); limits.addProperty("titleIndexStep", INDEX_STEP); limits.addProperty("categories", CATEGORY_LIMIT); result.add("limits", limits); result.addProperty("indexScope", "Loaded API class, registry/map identities and sizes, language and resource reload epoch; public research registration is static after initialization. Same-size in-place registry edits require resource reload or exact keys."); }
    private static void preview(JsonObject result, String key, String text, int cap) { int size = text.codePointCount(0, text.length()); result.addProperty(key, text.substring(0, text.offsetByCodePoints(0, Math.min(size, cap)))); result.addProperty(key + "Characters", size); result.addProperty(key + "Truncated", size > cap); }
    private static String string(Object value) { if (!(value instanceof String)) throw new BridgeException("unsupported", "Public research text/key is not a String."); return (String) value; }
    private static Map<?, ?> map(Object value) { if (!(value instanceof Map<?, ?>)) throw new BridgeException("unsupported", "Public research registry is not a Map."); return (Map<?, ?>) value; }
    private static int arrayLength(Object value) { if (!value.getClass().isArray()) throw new BridgeException("unsupported", "Public research collection is not an array."); return Array.getLength(value); }
    private static boolean expired(long started) { return System.nanoTime() - started > SOFT_NANOS; }
    private static int bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static Class<?> optionalType(ClassLoader loader, String name) { try { return Class.forName(name, false, loader); } catch (ClassNotFoundException | LinkageError error) { return null; } }
}
