package com.Goderium.BridgeNH.client.nei;
import codechicken.nei.recipe.GuiRecipe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.aspectrecipeindex.nei.InfusionRecipeHandler;
import com.gtnewhorizons.aspectrecipeindex.nei.ResearchInfo;
import com.gtnewhorizons.aspectrecipeindex.util.ARIConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

/** Plugin visibility, original model continuation and bounded tooltip window regression fixtures. */
public final class NeiSemanticsSelfTest {
    private static int checks;
    private static long generous() { return System.nanoTime() + 60000000000L; }
    public static int run() { checks = 0; verify(); return checks; }
    private static JsonObject args(String target) { JsonObject args = new JsonObject(); args.addProperty("screenId", "semantics-fixture"); args.addProperty("section", "detail"); args.addProperty("target", target); args.addProperty("recipeIndex", 0); return args; }
    private static JsonObject read(GuiRecipe screen, JsonObject args) { return NeiViewAdapter.read(screen, null, args, "semantics-session", generous()); }
    private static void verify() {
        AspectList aspects = new AspectList(); List<Aspect> keys = new ArrayList<Aspect>(); StringBuilder longText = new StringBuilder("A\uD83D\uDE80B"); for (int i = 0; i < 900; i++) longText.append('x');
        for (int i = 0; i < 150; i++) { Aspect aspect = new Aspect("tag-" + i, "name-" + i, i == 0 ? longText.toString() : "desc-" + i); keys.add(aspect); aspects.aspects.put(aspect, i + 1); }
        InfusionRecipeHandler handler = new InfusionRecipeHandler(); handler.fixtureAdd(aspects, 7);
        List<String> lines = new ArrayList<String>(); for (int i = 0; i < 150; i++) lines.add(i == 0 ? longText.toString() : "line-" + i);
        ResearchInfo firstResearch = new ResearchInfo("category", "research-0", lines);
        for (int i = 0; i < 150; i++) handler.fixturePrereqs(0).add(i == 0 ? firstResearch : new ResearchInfo("category", "research-" + i, Collections.singletonList("tip")));
        GuiRecipe screen = new GuiRecipe(handler, Collections.singletonList(0)); JsonObject request = args("metadata"), data = read(screen, request);
        check(data.get("total").getAsInt() == 4 && data.getAsJsonObject("data").getAsJsonArray("entries").get(1).getAsJsonObject().getAsJsonObject("value").get("key").getAsString().equals("aspects"), "Infusion typed semantics are integrated through the unchanged metadata target");
        check(data.getAsJsonObject("data").getAsJsonArray("entries").get(1).getAsJsonObject().getAsJsonObject("value").getAsJsonObject("value").getAsJsonObject("continuation").get("metadataIndex").getAsInt() == 1, "provider continuation exposes the actual existing MCP metadataIndex selector");
        String token = data.get("contextToken").getAsString(); request = args("metadata_value"); request.addProperty("metadataIndex", 1); request.addProperty("limit", 64);
        List<Integer> amounts = new ArrayList<Integer>();
        for (int offset = 0; offset < 150;) { request.addProperty("offset", offset); request.addProperty("contextToken", token); data = read(screen, request); for (com.google.gson.JsonElement entry : data.getAsJsonObject("data").getAsJsonArray("entries")) amounts.add(entry.getAsJsonObject().get("amount").getAsInt()); offset = data.get("nextOffset").getAsInt(); }
        check(amounts.size() == 150 && amounts.get(0) == 1 && amounts.get(149) == 150, "all aspect requirements continue in original order beyond64 without loss or duplicates");
        request = args("metadata_value"); request.addProperty("metadataIndex", 1); JsonArray path = new JsonArray(); path.add(new com.google.gson.JsonPrimitive(0)); path.add(new com.google.gson.JsonPrimitive(2)); request.add("valuePath", path); request.addProperty("offset", 1); request.addProperty("limit", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && data.getAsJsonObject("data").get("offsetUnit").getAsString().equals("unicode_codepoints"), "long aspect text uses existing Unicode character continuation");
        request.remove("offset"); request.remove("limit"); request.remove("contextToken"); data = read(screen, request); check(data.get("complete").getAsBoolean() && data.get("total").getAsInt() == 903, "default metadata_value character limit supports full long semantic text");
        request = args("metadata_value"); request.addProperty("metadataIndex", 3); request.addProperty("limit", 64); path = new JsonArray(); path.add(new com.google.gson.JsonPrimitive(0)); request.add("valuePath", path);
        List<String> produced = new ArrayList<String>();
        for (int offset = 0; offset < 151;) { request.addProperty("offset", offset); request.addProperty("contextToken", token); data = read(screen, request); for (com.google.gson.JsonElement entry : data.getAsJsonObject("data").getAsJsonArray("entries")) produced.add(entry.getAsJsonObject().get("text").getAsString()); offset = data.get("nextOffset").getAsInt(); }
        check(produced.size() == 151 && produced.get(1).isEmpty() && produced.get(64).equals("line-63") && produced.get(150).equals("line-149"), "bounded onHover tooltip windows preserve inserted blank and every 150+ line across pages");
        request.remove("valuePath"); request.addProperty("offset", 128); data = read(screen, request); check(data.get("total").getAsInt() == 150 && data.get("complete").getAsBoolean(), "research prerequisite list continues beyond128");
        String context = NeiRecipeSemantics.context(handler, 0); aspects.aspects.put(keys.get(0), 999); check(!context.equals(NeiRecipeSemantics.context(handler, 0)), "same-size aspect amount changes invalidate semantic fingerprint"); aspects.aspects.put(keys.get(0), 1);
        context = NeiRecipeSemantics.context(handler, 0); firstResearch.fixtureName("changed research"); check(!context.equals(NeiRecipeSemantics.context(handler, 0)), "same-size research field changes invalidate semantic fingerprint"); firstResearch.fixtureName("research-0");
        ARIConfig.showUndiscoveredAspectNames = false; data = NeiRecipeSemantics.metadataValue(handler, 0, 1, new int[0], 0, 1, generous()); JsonObject unknown = data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject(); check(unknown.get("tag").isJsonNull() && !unknown.get("namesVisible").getAsBoolean(), "undiscovered aspect identity remains hidden under plugin settings"); ARIConfig.showUndiscoveredAspectNames = true;
        handler.fixtureShown(0, false); int reads = handler.fixtureReads(0); data = NeiRecipeSemantics.read(handler, 0, 0, 4, generous()); check(handler.fixtureReads(0) == reads && !data.getAsJsonObject("knownVisibility").get("shouldShowRecipe").getAsBoolean(), "hidden recipe semantics do not invoke the aspect getter");
        data = NeiRecipeSemantics.metadataValue(handler, 0, 1, new int[0], 0, 64, generous()); check(data.get("status").getAsString().equals("unsupported") && handler.fixtureReads(0) == reads, "hidden aspect requirements remain unsupported without model access");
        handler.fixtureShown(0, true); ARIConfig.showResearchKey = false; data = NeiRecipeSemantics.metadataValue(handler, 0, 3, new int[0], 0, 64, generous()); check(data.get("status").getAsString().equals("unsupported"), "hidden research keys remain unavailable"); ARIConfig.showResearchKey = true;
        JsonObject recipe = new JsonObject(); recipe.addProperty("screenId", "semantics-fixture"); recipe.addProperty("section", "recipe"); recipe.addProperty("recipeIndex", 0); data = read(screen, recipe); check(data.getAsJsonObject("data").getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("semantics").getAsJsonObject("knownVisibility").get("shouldShowRecipe").getAsBoolean(), "recipe overview exposes plugin semantics summary without a new section");
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
