package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact identity/ambiguity, bounded cached indexing and fresh player-visible book reads. */
public final class ResearchLinkSelfTest {
    private static int checks;
    private static long generous() { return System.nanoTime() + 60000000000L; }
    public static int run() {
        checks = 0;
        try { verify(); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        return checks;
    }
    public static void main(String[] args) { System.out.println("Research link checks: " + run()); }

    private static JsonObject args(String key, String section) { JsonObject args = new JsonObject(); args.addProperty("researchKey", key); args.addProperty("section", section); return args; }
    private static JsonObject read(ResearchDataAdapter.Api api, JsonObject args) throws ReflectiveOperationException { return ResearchDataAdapter.read(api, null, "player", "en_US", args, generous()); }

    private static void verify() throws ReflectiveOperationException {
        Categories.researchCategories.clear(); Knowledge.completed.clear();
        Category first = new Category(), second = new Category();
        Categories.researchCategories.put("FIRST", first); Categories.researchCategories.put("SECOND", second);
        ResearchDataAdapter.Api api = new ResearchDataAdapter.Api(Categories.class, Category.class, Research.class, Page.class, Knowledge.class);
        Research item = new Research("KEY", "FIRST", "Visible research");
        item.pages = new Page[] {new Page("TEXT", "A\uD83D\uDE80B"), new Page("TEXT_CONCEALED", "secret text"), new Page("TEXT", "last page")};
        item.pages[1].research = "GATE"; first.research.put(item.key, item);
        Research parent = new Research("PARENT", "FIRST", "Parent research"); first.research.put(parent.key, parent);
        item.parents = new String[] {"PARENT", "MISSING"}; item.parentsHidden = new String[] {"DUP"};
        first.research.put("DUP", new Research("DUP", "FIRST", "Duplicate")); second.research.put("DUP", new Research("DUP", "SECOND", "Duplicate"));
        JsonObject request = args("KEY", "pages"), response = read(api, request);
        check(response.get("status").getAsString().equals("locked") && item.pageReads == 0, "Locked research never calls getPages or reads page text.");
        Knowledge.completed.put("KEY", true);
        response = read(api, request);
        check(response.get("total").getAsInt() == 3 && response.getAsJsonArray("entries").get(1).getAsJsonObject().get("availability").getAsString().equals("locked") && item.pages[1].textReads == 0, "Concealed pages retain original index while locked text stays unread.");
        request = args("KEY", "page_text"); request.addProperty("pageIndex", 0); request.addProperty("offset", 1); request.addProperty("limit", 1); response = read(api, request);
        check(response.get("text").getAsString().equals("\uD83D\uDE80") && response.get("total").getAsInt() == 3 && response.get("nextOffset").getAsInt() == 2, "Unicode text continuation preserves surrogate pairs and exact codepoint offsets.");
        request.addProperty("pageIndex", 1); request.addProperty("offset", 0); response = read(api, request);
        check(response.get("status").getAsString().equals("locked") && item.pages[1].textReads == 0, "Selected concealed page also checks live gate completion.");
        Knowledge.completed.put("GATE", true); response = read(api, request);
        check(response.get("text").getAsString().equals("s") && item.pages[1].textReads == 1, "Completion changes are read fresh, without clearing static indexes.");
        request = args("KEY", "dependencies"); request.addProperty("limit", 1); response = read(api, request);
        check(response.get("total").getAsInt() == 3 && response.get("hasMore").getAsBoolean() && response.getAsJsonArray("entries").get(0).getAsJsonObject().get("title").getAsString().equals("Parent research"), "Dependency identity and title come from declared public parent keys.");
        parent.hidden = true; response = read(api, request);
        JsonObject withheld = response.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(withheld.entrySet().size() == 2 && withheld.get("dependencyIndex").getAsInt() == 0 && withheld.get("identityAvailability").getAsString().equals("hidden_incomplete"), "Incomplete hidden dependency exposes only index and visibility; no raw key, title, category, kind, completion or continuation.");
        Knowledge.completed.put("PARENT", true); response = read(api, request);
        check(response.getAsJsonArray("entries").get(0).getAsJsonObject().get("researchKey").getAsString().equals("PARENT") && response.getAsJsonArray("entries").get(0).getAsJsonObject().get("title").getAsString().equals("Parent research"), "Completed hidden research retains its exact publicly readable identity.");
        Knowledge.completed.remove("PARENT"); parent.hidden = false; parent.concealed = true; response = read(api, request);
        withheld = response.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(withheld.entrySet().size() == 2 && withheld.get("identityAvailability").getAsString().equals("hidden_incomplete"), "Incomplete concealed dependency also exposes only index and visibility.");
        parent.concealed = false;
        request.addProperty("offset", 1); response = read(api, request);
        check(response.getAsJsonArray("entries").get(0).getAsJsonObject().get("resolution").getAsString().equals("not_found"), "Missing parent is explicit instead of guessed.");
        request.addProperty("offset", 2); response = read(api, request);
        withheld = response.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(withheld.entrySet().size() == 2 && withheld.get("dependencyIndex").getAsInt() == 2 && withheld.get("identityAvailability").getAsString().equals("ambiguous") && !withheld.toString().contains("DUP"), "Ambiguous hidden dependency exposes no candidate or original key.");
        item.parentsHidden = new String[] {"SECRET_MISSING"}; response = read(api, request);
        withheld = response.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(withheld.entrySet().size() == 2 && withheld.get("identityAvailability").getAsString().equals("unresolved") && !response.toString().contains("SECRET_MISSING"), "Unresolved hidden dependency exposes no reversible key in the entire response.");
        item.parentsHidden = new String[] {"DUP"};
        request = args("DUP", "summary"); response = read(api, request);
        check(response.get("status").getAsString().equals("ambiguous"), "Unqualified duplicate exact key cannot choose an arbitrary research.");
        request.addProperty("category", "SECOND"); response = read(api, request);
        check(response.get("category").getAsString().equals("SECOND") && response.get("resolution").getAsString().equals("exact_key"), "Explicit category resolves exact-key ambiguity.");
        item.pages[2].recipe = new Object(); request = args("KEY", "page_links"); request.addProperty("pageIndex", 2); response = read(api, request);
        check(response.getAsJsonArray("entries").get(0).getAsJsonObject().get("kind").getAsString().equals("unsupported_recipe"), "Unknown recipe model is reported instead of reflecting arbitrary fields.");
        for (int i = 0; i < 600; i++) first.research.put("EXTRA_" + i, new Research("EXTRA_" + i, "FIRST", "Extra " + i));
        JsonObject title = new JsonObject(); title.addProperty("title", "Visible research");
        response = read(api, title); check(response.get("status").getAsString().equals("indexing") && response.get("indexedEntries").getAsInt() <= 256, "Title index builds at most 256 entries per bounded request.");
        for (int i = 0; i < 4 && response.get("status").getAsString().equals("indexing"); i++) response = read(api, title);
        check(response.get("researchKey").getAsString().equals("KEY") && response.get("resolution").getAsString().equals("exact_localized_title"), "Finished localized index resolves an exact displayed title.");
        int reads = Research.nameReads; response = read(api, title);
        check(Research.nameReads - reads < 10, "Warm title lookup reuses the static index instead of rescanning hundreds of entries.");
        title.addProperty("title", "Visible"); response = read(api, title);
        check(response.get("status").getAsString().equals("not_found"), "Partial title cannot silently guess a machine or research identity.");
        title.addProperty("title", "Duplicate"); response = read(api, title);
        check(response.get("status").getAsString().equals("ambiguous"), "Duplicate localized title is not treated as a unique link.");
        title.addProperty("category", "FIRST"); response = read(api, title);
        check(response.get("researchKey").getAsString().equals("DUP"), "Category resolves duplicate localized titles.");
        title.remove("category"); title.addProperty("title", "Visible research");
        first.research.put("KEY", new Research("KEY", "FIRST", "Visible research")); response = read(api, title);
        check(response.get("status").getAsString().equals("indexing") && !response.has("researchKey"), "Same-size object replacement cannot return a stale cached research link.");
        first.research.put("KEY", item);
        response = ResearchDataAdapter.read(api, null, "player", "zh_CN", title, generous());
        check(response.get("status").getAsString().equals("indexing"), "Language changes rebuild the localized-title index.");
        first.research.put("NEW", new Research("NEW", "FIRST", "New title")); response = read(api, title);
        check(response.get("status").getAsString().equals("indexing"), "Registry size changes invalidate the static title index.");
        request = args("KEY", "summary"); int pageReads = item.pageReads;
        response = ResearchDataAdapter.read(api, null, "player", "en_US", request, System.nanoTime() - 1000000000L);
        check(response.get("researchKey").getAsString().equals("KEY") && response.get("previewStatus").getAsString().equals("omitted_soft_budget") && response.has("continuation") && item.pageReads == pageReads, "Expired preview budget retains direct identity and continuation without reading pages.");
        request = args("KEY", "summary"); Knowledge.completed.put("KEY", false); response = read(api, request);
        check(response.get("pagesAvailability").getAsString().equals("locked") && !response.has("pagesPreview"), "Book summary previews never leak pages after completion is removed.");
        item.hidden = true; response = read(api, request);
        check(response.get("status").getAsString().equals("unavailable") && !response.has("title"), "Incomplete hidden entries are not exposed by global exact lookup.");
        item.hidden = false;
        request = args("KEY", "pages"); request.addProperty("title", "Visible research");
        expectInvalid(api, request, "Key plus title is invalid.");
        request = args("KEY", "summary"); request.addProperty("offset", 1);
        expectInvalid(api, request, "Summary pagination cannot pretend to be a second page.");
    }

    private static void expectInvalid(ResearchDataAdapter.Api api, JsonObject args, String label) throws ReflectiveOperationException {
        try { read(api, args); throw new AssertionError(label); }
        catch (BridgeException expected) { check(expected.code.equals("invalid_args"), label); }
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    public static final class Categories {
        public static final Map<String, Category> researchCategories = new LinkedHashMap<>();
        public static String getCategoryName(String key) { return "Category " + key; }
    }
    public static final class Category { public final Map<String, Research> research = new LinkedHashMap<>(); }
    public static final class Knowledge {
        static final Map<String, Boolean> completed = new HashMap<>();
        public static boolean isResearchComplete(String username, String key) { return Boolean.TRUE.equals(completed.get(key)); }
    }
    public static final class Research {
        public final String key, category; public String[] parents, parentsHidden; public Page[] pages;
        final String name; boolean hidden, concealed; int pageReads; static int nameReads;
        Research(String key, String category, String name) { this.key = key; this.category = category; this.name = name; }
        public String getName() { nameReads++; return name; }
        public String getText() { return "Description " + name; }
        public Page[] getPages() { pageReads++; return pages; }
        public boolean isHidden() { return hidden; }
        public boolean isLost() { return false; }
        public boolean isConcealed() { return concealed; }
        public boolean isVirtual() { return false; }
    }
    public static final class Page {
        public final String type; public String research; public Object image, recipe, recipeOutput;
        final String text; int textReads;
        Page(String type, String text) { this.type = type; this.text = text; }
        public String getTranslatedText() { textReads++; return text; }
    }
}
