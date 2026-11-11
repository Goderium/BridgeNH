package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Original page references and private anonymous handlers, with no native GUI interaction. */
public class NeiDataSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        verify();
        return checks;
    }
    public static void main(String[] args) { System.out.println("NEI data fixtures passed: " + run()); }
    private static void verify() {
        JsonObject args = args();
        check(!NeiDataAdapter.supports(null), "absent GUI is not NEI");
        expect("unsupported", () -> NeiDataAdapter.read(new Object(), null, args, generousStart()));
        StructuredInfoSelfTest.FakeItem item = new StructuredInfoSelfTest.FakeItem();
        ItemStack stack = new ItemStack(item, 3, 7);
        stack.setTagCompound(new NBTTagCompound()); stack.getTagCompound().setString("fixture", "real NEI NBT");
        PositionedStack position = new PositionedStack(new ItemStack[] {stack}, 12, 23) {
            @Override public String getAcceptsLabel() { return "private anonymous position"; }
        };
        List<Integer> requested = new ArrayList<Integer>();
        IRecipeHandler handler = new IRecipeHandler() {
            @Override public String getRecipeName() { return "fixture recipes"; }
            @Override public int numRecipes() { return 100; }
            @Override public List<PositionedStack> getIngredientStacks(int recipe) { requested.add(recipe); return Collections.singletonList(position); }
            @Override public PositionedStack getResultStack(int recipe) { requested.add(recipe); return position; }
            @Override public List<PositionedStack> getOtherStacks(int recipe) { requested.add(recipe); return Collections.emptyList(); }
        };
        GuiRecipe gui = new GuiRecipe(handler, Arrays.asList(17, 93));
        JsonObject result = NeiDataAdapter.read(gui, null, args, generousStart());
        check(result.getAsJsonObject("handler").get("name").getAsString().equals("fixture recipes"), "fixed public recipe API is recognized without GUI initialization");
        check(result.get("recipeCount").getAsInt() == 100 && result.get("visibleRecipeCount").getAsInt() == 2, "total handler count is distinct from visible refs");
        check(result.getAsJsonArray("visibleRecipeIndices").get(0).getAsInt() == 17, "visible ref retains original index");
        check(result.getAsJsonArray("recipes").size() == 1 && result.get("nextOffset").getAsInt() == 1 && result.get("hasMore").getAsBoolean(), "pagination indexes the current visible page only");
        JsonObject recipe = result.getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(recipe.get("recipeIndex").getAsInt() == 17 && requested.equals(Arrays.asList(17, 17, 17)), "all public handler getters receive original ref without remapping");
        JsonObject input = recipe.getAsJsonArray("inputs").get(0).getAsJsonObject();
        check(input.get("relx").getAsInt() == 12 && input.get("rely").getAsInt() == 23 && input.get("chance").getAsInt() == 2500, "public position and chance exported");
        check(input.get("acceptsLabel").getAsString().contains("anonymous"), "public base method dispatch reaches anonymous positioned stack");
        check(input.getAsJsonObject("item").get("count").getAsInt() == 3 && input.getAsJsonObject("item").get("hasTag").getAsBoolean(), "real current stack count and NBT retained");
        check(!item.called && !input.getAsJsonObject("item").has("tooltip"), "expensive item tooltip is opt in");
        args.addProperty("includeTooltips", true); args.addProperty("advanced", true);
        result = NeiDataAdapter.read(gui, null, args, generousStart());
        input = result.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject();
        check(item.called && item.advanced && item.seen == stack, "optional tooltip sees the actual stack, not a reconstructed variant");
        check(input.getAsJsonObject("item").getAsJsonArray("tooltip").toString().contains("real NEI NBT"), "NEI tooltip includes real NBT context");
        args.addProperty("offset", 1); requested.clear();
        result = NeiDataAdapter.read(gui, null, args, generousStart());
        check(result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("recipeIndex").getAsInt() == 93 && requested.get(0) == 93, "second visible ref does not mean handler recipe one");
        expect("adapter_error", () -> NeiDataAdapter.read(new GuiRecipe(handler, Collections.singletonList(101)), null, args(), generousStart()));
        JsonObject invalid = args(); invalid.addProperty("includeTooltips", "true");
        final JsonObject badFlag = invalid;
        expect("invalid_args", () -> NeiDataAdapter.read(gui, null, badFlag, generousStart()));
        invalid = args(); invalid.addProperty("limit", 17);
        final JsonObject badLimit = invalid;
        expect("invalid_args", () -> NeiDataAdapter.read(gui, null, badLimit, generousStart()));
        ItemStack[] alternatives = new ItemStack[40]; Arrays.fill(alternatives, stack);
        List<PositionedStack> many = new ArrayList<PositionedStack>();
        for (int i = 0; i < 80; i++) many.add(new PositionedStack(alternatives, i, 0));
        IRecipeHandler large = new IRecipeHandler() {
            @Override public String getRecipeName() { return "bounded"; }
            @Override public int numRecipes() { return 1; }
            @Override public List<PositionedStack> getIngredientStacks(int recipe) { return many; }
            @Override public PositionedStack getResultStack(int recipe) { return null; }
            @Override public List<PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
        };
        result = NeiDataAdapter.read(new GuiRecipe(large, Collections.singletonList(0)), null, args(), generousStart());
        recipe = result.getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(recipe.get("positionsVisited").getAsInt() <= 64 && recipe.get("positionsTruncated").getAsBoolean(), "per recipe positions are bounded and truncation explicit");
        input = recipe.getAsJsonArray("inputs").get(0).getAsJsonObject();
        check(input.getAsJsonArray("alternatives").size() <= 32 && input.get("alternativesTotal").getAsInt() == 40 && input.get("alternativesTruncated").getAsBoolean(), "alternatives cap does not hide total");
        check(result.toString().getBytes(StandardCharsets.UTF_8).length <= NeiDataAdapter.BYTE_LIMIT, "NEI payload remains within 128 KiB");
        result = NeiDataAdapter.read(gui, null, args(), System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(result.getAsJsonArray("recipes").size() == 1 && result.getAsJsonObject("budget").get("softBudgetExceeded").getAsBoolean(), "soft budget makes one recipe progress and reports overrun");
        check(result.getAsJsonObject("coverage").get("extraDrawnText").getAsString().contains("GT raw recipe"), "GT data adapter is identified while arbitrary drawn text remains unavailable");
    }
    // A future start is deliberate only in fixtures: exercise hard count/byte caps without wall-clock flakiness.
    private static long generousStart() { return System.nanoTime() + 60000000000L; }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "fixture-screen"); args.addProperty("limit", 1); return args; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }
}
