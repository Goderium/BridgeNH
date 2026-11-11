package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.ItemPanel;
import codechicken.nei.ItemPanels;
import codechicken.nei.LayoutManager;
import codechicken.nei.ItemHistoryPanel;
import codechicken.nei.ItemCraftablesPanel;
import codechicken.nei.ItemsGrid;
import codechicken.nei.Widget;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Original indices, full nested continuations, cache purity and screen/session/token failure paths. */
public final class NeiViewSelfTest {
    private static int checks;
    public static int run() { checks = 0; verify(); return checks; }
    public static void main(String[] args) { System.out.println("NEI view fixtures passed: " + run()); }
    private static long generous() { return System.nanoTime() + 60000000000L; }
    private static JsonObject args(String section) { JsonObject args = new JsonObject(); args.addProperty("screenId", "view-fixture"); args.addProperty("section", section); return args; }
    private static JsonObject detail(String target) { JsonObject args = args("detail"); args.addProperty("target", target); return args; }
    private static JsonObject read(Object screen, JsonObject args) { return NeiViewAdapter.read(screen, null, args, "fixture-session", generous()); }
    private static void verify() {
        StructuredInfoSelfTest.FakeItem item = new StructuredInfoSelfTest.FakeItem(); ItemStack stack = new ItemStack(item, 3, 7);
        stack.setTagCompound(new NBTTagCompound()); stack.getTagCompound().setString("fixture", "real NEI NBT");
        final List<Integer> requested = new ArrayList<Integer>();
        ItemStack[] alternatives = new ItemStack[70]; Arrays.fill(alternatives, stack);
        List<PositionedStack> positions = new ArrayList<PositionedStack>();
        for (int i = 0; i < 75; i++) positions.add(new PositionedStack(alternatives, i, 20));
        final List<String> longLines = new ArrayList<String>(); for (int i = 0; i < 70; i++) longLines.add("line " + i);
        positions.set(0, new PositionedStack(alternatives, 0, 20) { @Override public List<String> getTooltip() { return longLines; } });
        final List<PositionedStack> all = positions;
        IRecipeHandler handler = new IRecipeHandler() {
            public String getRecipeName() { return "current full handler"; }
            public int numRecipes() { return 250000; }
            public List<PositionedStack> getIngredientStacks(int recipe) { requested.add(recipe); return all; }
            public PositionedStack getResultStack(int recipe) { return null; }
            public List<PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
        };
        GuiRecipe screen = new GuiRecipe(handler, Arrays.asList(17, 93));
        JsonObject summary = read(screen, args("summary")); String token = summary.get("contextToken").getAsString();
        check(summary.get("schemaVersion").getAsInt() == 1 && summary.get("complete").getAsBoolean(), "summary is complete with a stable versioned contract");
        check(RequestRegistry.readOnly("nei.view"), "new operation is read only in actual request registry");
        JsonObject request = args("recipes"); request.addProperty("scope", "handler"); request.addProperty("offset", 200000); request.addProperty("limit", 1); request.addProperty("contextToken", token);
        JsonObject data = read(screen, request); check(data.getAsJsonObject("data").getAsJsonArray("recipes").get(0).getAsJsonObject().get("recipeIndex").getAsInt() == 200000 && requested.get(requested.size() - 1) == 200000, "full handler supports int32 offsets without navigation");
        request = detail("positions"); request.addProperty("recipeIndex", 93); request.addProperty("limit", 64);
        data = read(screen, request); check(data.get("total").getAsInt() == 75 && data.get("nextOffset").getAsInt() == 64 && data.get("hasMore").getAsBoolean(), "positions can continue beyond overview cap");
        request.addProperty("offset", 64); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").getAsJsonArray("entries").size() == 11 && data.get("complete").getAsBoolean(), "position tail completes");
        request = detail("alternatives"); request.addProperty("recipeIndex", 17); request.addProperty("positionIndex", 0); request.addProperty("offset", 64); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.get("total").getAsInt() == 70 && data.getAsJsonObject("data").getAsJsonArray("entries").size() == 6, "alternatives beyond32/64 are reachable");
        request = detail("tooltip_lines"); request.addProperty("recipeIndex", 17); request.addProperty("tooltipSource", "position"); request.addProperty("offset", 64); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.get("total").getAsInt() == 70 && data.get("complete").getAsBoolean(), "position tooltip lines beyond32 are reachable");
        longLines.set(0, "A\uD83D\uDE80B"); request = detail("tooltip_text"); request.addProperty("recipeIndex", 17); request.addProperty("tooltipSource", "position"); request.addProperty("lineIndex", 0); request.addProperty("limit", 1); request.addProperty("offset", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && data.get("total").getAsInt() == 3 && data.get("nextOffset").getAsInt() == 2, "one-character page preserves a complete surrogate pair and advances");
        request = detail("tooltip_lines"); request.addProperty("recipeIndex", 17); request.addProperty("advanced", true); data = read(screen, request);
        check(item.called && item.seen == stack && data.getAsJsonObject("data").getAsJsonArray("entries").toString().contains("handler item tooltip 17"), "actual item and handler tooltip callback preserve stack identity");
        request.addProperty("tooltipSource", "handler"); data = read(screen, request); check(data.getAsJsonObject("data").getAsJsonArray("entries").toString().contains("handler tooltip 17"), "handler nonitem tooltip callback is reached");
        request.addProperty("tooltipSource", "badge"); request.addProperty("badgeIndex", 0); data = read(screen, request); check(data.get("total").getAsInt() == 2 && data.getAsJsonObject("data").getAsJsonArray("entries").toString().contains("not consumed"), "actual badge tooltip model is readable");
        request = detail("nbt"); request.addProperty("recipeIndex", 17); data = read(screen, request); check(data.getAsJsonObject("data").get("text").getAsString().contains("real NEI NBT"), "full exact NBT characters are reachable");
        final JsonObject noToken = args("handlers"); noToken.addProperty("offset", 1); expect("invalid_args", () -> read(screen, noToken));
        final JsonObject wrongSelector = args("summary"); wrongSelector.addProperty("recipeIndex", 1); expect("invalid_args", () -> read(screen, wrongSelector));
        final JsonObject stale = args("summary"); stale.addProperty("contextToken", token); screen.page++;
        expect("stale_context", () -> read(screen, stale)); screen.page--;
        expect("stale_context", () -> NeiViewAdapter.read(screen, null, stale, "different-session", generous()));
        expect("stale_context", () -> read(new GuiRecipe(handler, Arrays.asList(17, 93)), stale));
        summary = read(screen, args("summary")); token = summary.get("contextToken").getAsString();
        ItemPanels.itemPanel = new ItemPanel(); for (int i = 0; i < 70; i++) ItemPanels.itemPanel.grid.items.add(stack); ItemPanels.itemPanel.grid.fixtureVisible(stack);
        data = read(screen, args("panel")); check(data.get("total").getAsInt() == 1 && data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("slotIndex").getAsInt() == 3, "actual cached visible mask is read without getMask mutation tripwire");
        token = data.get("contextToken").getAsString(); request = args("panel"); request.addProperty("scope", "all"); request.addProperty("offset", 64); request.addProperty("contextToken", token); data = read(screen, request); check(data.get("complete").getAsBoolean() && data.get("total").getAsInt() == 70, "filtered panel tail continues beyond64");
        final JsonObject changed = args("panel"); changed.addProperty("contextToken", token); ItemPanels.itemPanel.grid.page++;
        expect("stale_context", () -> read(screen, changed));
        ItemPanels.itemPanel = null;
        verifyStablePanelContext(screen, stack);
        verifyGtMetadata();
        request = detail("positions"); request.addProperty("recipeIndex", 17); data = NeiViewAdapter.read(screen, null, request, "fixture-session", System.nanoTime() - 8000000L);
        check(data.get("nextOffset").getAsInt() == 1 && data.get("softBudgetPartial").getAsBoolean(), "expired soft budget still makes bounded first-entry progress");
        JsonObject preview = NeiPreviewProvider.inspect(screen, 0, 1, generous(), false); check(preview.get("status").getAsString().equals("not_present"), "missing preview provider is explicit");
        check(NeiUiAccess.optional(screen.getClass().getClassLoader(), "java.lang.System", null, "out") == null, "fixed read allowlist cannot access arbitrary fields");
    }
    private static void verifyStablePanelContext(GuiRecipe screen, ItemStack stack) {
        ItemPanel panel = new ItemPanel(); ItemPanels.itemPanel = panel;
        for (int i = 0; i < 40; i++) panel.grid.items.add(new ItemStack(stack.getItem(), 1, i));
        panel.grid.page = 2; panel.grid.fixtureInvalid(1); panel.grid.fixtureMask(null);
        panel.historyPanel = new ItemHistoryPanel(); panel.historyPanel.fixtureGrid().items.add(stack);
        panel.craftablesPanel = new ItemCraftablesPanel(); panel.craftablesPanel.fixtureAvailable(stack);
        Widget second = new Widget(); second.x = 10; LayoutManager.fixtureWidgets(panel, LayoutManager.searchField, second);
        JsonObject data = read(screen, args("panel"));
        check(data.get("total").getAsInt() == 15 && data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("itemIndex").getAsInt() == 15, "null mask derives current one-based page and invalid-slot capacity");
        check(data.getAsJsonObject("data").getAsJsonArray("entries").get(1).getAsJsonObject().get("slotIndex").getAsInt() == 2, "derived mask retains invalid-slot holes");
        String token = data.get("contextToken").getAsString(); JsonObject continued = args("panel"); continued.addProperty("scope", "all"); continued.addProperty("offset", 16); continued.addProperty("contextToken", token);
        LayoutManager.fixtureRecreate(); panel.grid.items = new ArrayList<ItemStack>(panel.grid.items); data = read(screen, continued);
        check(data.get("nextOffset").getAsInt() == 32 && data.get("contextToken").getAsString().equals(token), "equal recreated widget Set and copied item list preserve continuation token");
        List<ItemsGrid.ItemsGridSlot> populated = new ArrayList<ItemsGrid.ItemsGridSlot>(); int itemIndex = 15;
        for (int slot = 0; slot < 16; slot++) if (slot != 1) populated.add(new ItemsGrid.ItemsGridSlot(slot, itemIndex, panel.grid.items.get(itemIndex++)));
        panel.grid.fixtureMask(populated); data = read(screen, continued);
        check(data.get("contextToken").getAsString().equals(token), "equivalent derived-to-materialized mask preserves token without generating caches");
        JsonObject request = detail("widgets"); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.get("total").getAsInt() == 3, "actual layout Set enumerates widgets in encounter order");
        request = args("panel"); request.addProperty("panel", "history"); data = read(screen, request);
        check(data.get("status").getAsString().equals("readable") && data.get("total").getAsInt() == 1, "true AbstractSubpanel history inheritance reads existing grid with derived slots");
        request.addProperty("panel", "craftables"); data = read(screen, request);
        check(data.get("status").getAsString().equals("partial") && data.getAsJsonObject("data").getAsJsonObject("panel").get("visibleSlots").isJsonNull(), "unmaterialized craftables are explicit pending rather than empty or unsupported");
        panel.craftablesPanel.fixtureGrid().items.add(stack); panel.craftablesPanel.fixtureGrid().fixtureMask(Collections.singletonList(new ItemsGrid.ItemsGridSlot(0, 0, stack))); data = read(screen, request);
        check(data.get("status").getAsString().equals("readable") && data.get("total").getAsInt() == 1, "true AbstractSubpanel craftables existing mask is readable");
        data = read(screen, args("summary")); token = data.get("contextToken").getAsString(); final JsonObject stale = args("summary"); stale.addProperty("contextToken", token);
        Collections.swap(panel.grid.items, 0, 1); expect("stale_context", () -> read(screen, stale)); Collections.swap(panel.grid.items, 0, 1);
        String originalSearch = LayoutManager.searchField.value; LayoutManager.searchField.value = "changed filter"; expect("stale_context", () -> read(screen, stale)); LayoutManager.searchField.value = originalSearch;
        second.x++; expect("stale_context", () -> read(screen, stale)); second.x--;
        LayoutManager.fixtureWidgets(second, LayoutManager.searchField, panel); expect("stale_context", () -> read(screen, stale));
        ItemPanels.itemPanel = null; LayoutManager.fixtureWidgets();
    }
    private static void verifyGtMetadata() {
        gregtech.nei.GTNEIDefaultHandler handler = new gregtech.nei.GTNEIDefaultHandler(); gregtech.api.util.GTRecipe recipe = new gregtech.api.util.GTRecipe();
        List<gregtech.api.recipe.RecipeMetadataKey<String>> keys = new ArrayList<gregtech.api.recipe.RecipeMetadataKey<String>>();
        for (int i = 0; i < 70; i++) { gregtech.api.recipe.RecipeMetadataKey<String> key = new gregtech.api.recipe.RecipeMetadataKey<String>(String.class, "raw-" + i); keys.add(key); recipe.metadata.put(key, i == 0 ? "A\uD83D\uDE80B" : "value-" + i); }
        handler.arecipes.add(new gregtech.nei.GTNEIDefaultHandler.CachedDefaultRecipe(recipe)); GuiRecipe screen = new GuiRecipe(handler, Collections.singletonList(0));
        JsonObject request = detail("metadata"); request.addProperty("recipeIndex", 0); request.addProperty("limit", 64); JsonObject data = read(screen, request);
        check(data.get("total").getAsInt() == 70 && data.get("nextOffset").getAsInt() == 64, "raw GT metadata retains every original entry with continuation beyond64");
        JsonObject first = data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("value");
        check(first.get("identifier").getAsString().equals("raw-0") && first.get("keyIdentity").getAsString().equals(Integer.toHexString(System.identityHashCode(keys.get(0)))), "GT metadata original key identity and order survive plugin integration");
        String token = data.get("contextToken").getAsString(); request.addProperty("offset", 64); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.get("complete").getAsBoolean() && data.getAsJsonObject("data").getAsJsonArray("entries").size() == 6, "GT metadata new wrapper storage preserves second-page token");
        request = detail("metadata_value"); request.addProperty("recipeIndex", 0); request.addProperty("metadataIndex", 0); request.addProperty("offset", 1); request.addProperty("limit", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80"), "GT metadata character continuation remains Unicode codepoint safe");
        final JsonObject stale = request; recipe.metadata.put(keys.get(0), "changed"); expect("stale_context", () -> read(screen, stale));
        JsonObject recapture = detail("metadata"); recapture.addProperty("recipeIndex", 0); check(read(screen, recapture).get("status").getAsString().equals("readable"), "offset zero without a token recaptures changed recipe metadata");
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
}
