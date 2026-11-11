package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BoundedNbt;

import com.Goderium.BridgeNH.client.gui.WailaDataAdapter;
import com.Goderium.BridgeNH.common.machine.MachineDataAdapter;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.PositionedStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMetadataKey;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.recipe.Scanning;
import gregtech.nei.GTNEIDefaultHandler;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import mcp.mobius.waila.api.ITaggedList;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import mcp.mobius.waila.api.IWailaConfigHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/** Raw/cached/actual separation, bounded metadata/NBT, and registered invalid component diagnostics. */
public class ExpandedGtSelfTest {
    private static int checks;
    public static int run() { checks = 0; recipes(); machines(); nbt(); wailaProviders(); return checks; }
    public static void main(String[] args) { System.out.println("Expanded GT fixtures passed: " + run()); }
    private static void recipes() {
        GTRecipe.GTRecipe_WithAlt recipe = new GTRecipe.GTRecipe_WithAlt();
        ItemStack stack = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 3, 7);
        NBTTagCompound tag = new NBTTagCompound(); tag.setString("fixture", "exact matching NBT"); stack.setTagCompound(tag);
        recipe.mInputs = new ItemStack[] {stack}; recipe.mOutputs = new ItemStack[] {stack};
        recipe.mOreDictAlt = new ItemStack[][] {new ItemStack[] {stack}}; recipe.mOreDictIds = new int[] {17};
        Fluid fluid = new Fluid("bridge_fixture_fluid");
        recipe.mFluidInputs = new FluidStack[] {fixtureFluid(fluid, 2000)};
        recipe.mFluidOutputs = new FluidStack[] {fixtureFluid(fluid, 1000)};
        recipe.metadata.put(new RecipeMetadataKey<Integer>(Integer.class, "coil_heat"), 1800);
        recipe.metadata.put(new RecipeMetadataKey<Object>(Object.class, "opaque"), new Object() {
            @Override public String toString() { throw new AssertionError("Opaque metadata must not execute toString"); }
        });
        recipe.metadata.put(new RecipeMetadataKey<Scanning>(Scanning.class, "scanning"), new Scanning(100, Long.MAX_VALUE));
        JsonObject result = GTRecipeAdapter.read(recipe, start());
        check(result.get("rawEUt").getAsInt() == 100 && result.get("rawDurationTicks").getAsInt() == 80, "raw recipe EUt and duration are exact");
        check(result.get("chanceDenominator").getAsInt() == 10000 && itemGroup(result, "items", "outputs").get("chance").getAsInt() == 2500, "output chance denominator and value exported");
        check(itemGroup(result, "fluids", "inputs").getAsJsonObject("stack").get("amount").getAsInt() == 2000, "fluid amount exported independently of display stack");
        check(itemGroup(result, "fluids", "outputs").get("chance").getAsInt() == 5000, "fluid output chance retained");
        check(itemGroup(result, "items", "inputs").getAsJsonObject("stack").getAsJsonObject("nbt").get("snbt").getAsString().contains("exact matching NBT"), "matching item NBT is inspectable");
        check(result.get("nbtSensitive").getAsBoolean() && result.get("specialValue").getAsInt() == 900, "NBT matching and special condition retained");
        check(result.getAsJsonArray("metadata").get(0).getAsJsonObject().get("identifier").getAsString().equals("coil_heat"), "fixed metadata identifier read without toString");
        check(result.getAsJsonArray("metadata").get(1).getAsJsonObject().getAsJsonObject("value").get("available").getAsBoolean() == false, "unknown metadata type explicitly unavailable");
        check(result.getAsJsonArray("metadata").get(2).getAsJsonObject().getAsJsonObject("value").get("voltage").getAsLong() == Long.MAX_VALUE, "known scanning research metadata retains full long voltage");
        check(result.has("alternativeItemInputs") && result.has("oreDictionaryIds"), "GT alternative inputs included");
        GTNEIDefaultHandler handler = new GTNEIDefaultHandler();
        GTNEIDefaultHandler.CachedDefaultRecipe cached = new GTNEIDefaultHandler.CachedDefaultRecipe(recipe); handler.arecipes.add(cached);
        JsonObject gt = GTRecipeAdapter.inspectHandler(handler, 0, start());
        check(!gt.getAsJsonObject("displayOverclock").get("available").getAsBoolean(), "undrawn NEI calculator is unavailable, never recomputed");
        cached.calculator = new GTNEIDefaultHandler.Calculator(); gt = GTRecipeAdapter.inspectHandler(handler, 0, start());
        check(gt.getAsJsonObject("displayOverclock").get("eut").getAsLong() == 400 && gt.get("rawEUt").getAsInt() == 100, "display overclock is separate from raw recipe");
        check(gt.getAsJsonObject("recipeMap").get("amperage").getAsInt() == 2, "map amperage is not conflated with EUt");
        GuiRecipe gui = new GuiRecipe(handler, Collections.singletonList(0));
        JsonObject args = new JsonObject(); args.addProperty("screenId", "fixture"); args.addProperty("limit", 1);
        result = NeiDataAdapter.read(gui, null, args, start());
        check(result.getAsJsonArray("recipes").get(0).getAsJsonObject().has("gregTech"), "GT adapter enriches the current original handler recipe");
        JsonObject navigation = NeiControlAdapter.navigation(gui, 0, 32);
        check(navigation.get("handlerCount").getAsInt() == 1 && navigation.get("pageCount").getAsInt() == 10, "handler/page semantic navigation data exported");
        gui.currenthandlers.add(new GTNEIDefaultHandler());
        navigation = NeiControlAdapter.navigation(gui, 1, 1);
        check(navigation.getAsJsonArray("handlers").get(0).getAsJsonObject().get("index").getAsInt() == 1 && !navigation.get("hasMore").getAsBoolean(), "handler listing offset uses screen handler indices");
        JsonObject action = new JsonObject(); action.addProperty("action", "page"); action.addProperty("index", 4);
        navigation = NeiControlAdapter.navigateCurrent(null, gui, action).getAsJsonObject("navigation");
        check(navigation.get("pageIndex").getAsInt() == 4, "semantic page navigation changes page manager, with no keyboard event");
        action.addProperty("action", "handler"); action.addProperty("index", 1);
        navigation = NeiControlAdapter.navigateCurrent(null, gui, action).getAsJsonObject("navigation");
        check(navigation.get("handlerIndex").getAsInt() == 1 && navigation.get("pageIndex").getAsInt() == 0, "semantic handler navigation calls NEI setRecipePage and resets its pages");
        action.addProperty("index", 2); expect("not_found", () -> NeiControlAdapter.navigateCurrent(null, gui, action));
        action.addProperty("action", "page"); action.addProperty("index", 10); expect("not_found", () -> NeiControlAdapter.navigateCurrent(null, gui, action));
        GTNEIDefaultHandler withItem = new GTNEIDefaultHandler() {
            @Override public java.util.List<PositionedStack> getIngredientStacks(int index) { return Collections.singletonList(new PositionedStack(new ItemStack[] {stack}, 0, 0)); }
        };
        withItem.arecipes.add(cached); GuiRecipe sourceGui = new GuiRecipe(withItem, Collections.singletonList(0));
        JsonObject opening = new JsonObject(); opening.addProperty("mode", "crafting"); opening.addProperty("source", "recipe"); opening.addProperty("recipeIndex", 0);
        JsonObject opened = NeiControlAdapter.openCurrent(null, sourceGui, opening);
        check(!opened.get("opened").getAsBoolean() && GuiCraftingRecipe.query != stack && GuiCraftingRecipe.query.getItem() == stack.getItem(), "semantic open receives a copied actual recipe stack");
        check(stack.getTagCompound().getString("fixture").equals("exact matching NBT") && GuiCraftingRecipe.query.getTagCompound().getString("fixture").equals("only copied query changed"), "NEI querying cannot alter original stack NBT");
        opening.addProperty("registryName", "minecraft:stone"); expect("invalid_args", () -> NeiControlAdapter.openCurrent(null, sourceGui, opening));
        recipe.mInputs = new ItemStack[80]; Arrays.fill(recipe.mInputs, stack);
        result = GTRecipeAdapter.read(recipe, start());
        JsonObject inputs = result.getAsJsonObject("items").getAsJsonObject("inputs");
        check(inputs.get("total").getAsInt() == 80 && inputs.getAsJsonArray("entries").size() <= 64 && inputs.get("truncated").getAsBoolean(), "raw stack count caps preserve total/truncation");
        check(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768, "raw GT recipe output is byte bounded");
        result = GTRecipeAdapter.read(recipe, System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(result.get("softBudgetPartial").getAsBoolean() && result.getAsJsonObject("items").getAsJsonObject("inputs").getAsJsonArray("entries").size() == 1, "expired soft budget reports partial raw stacks while making progress");
    }
    private static JsonObject itemGroup(JsonObject result, String group, String direction) {
        return result.getAsJsonObject(group).getAsJsonObject(direction).getAsJsonArray("entries").get(0).getAsJsonObject();
    }
    // Test-only allocation: Forge's real constructor needs a fully bootstrapped game registry. The reader only uses
    // amount/tag/getFluid(), so provide those exact fields without initializing or modifying any registry.
    public static FluidStack fixtureFluid(Fluid fluid, int amount) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field instance = unsafeClass.getDeclaredField("theUnsafe"); instance.setAccessible(true);
            FluidStack result = (FluidStack) unsafeClass.getMethod("allocateInstance", Class.class).invoke(instance.get(null), FluidStack.class);
            java.lang.reflect.Field delegate = FluidStack.class.getDeclaredField("fluidDelegate"); delegate.setAccessible(true);
            delegate.set(result, new cpw.mods.fml.common.registry.RegistryDelegate.Delegate<Fluid>(fluid, Fluid.class));
            result.amount = amount;
            return result;
        } catch (ReflectiveOperationException error) { throw new AssertionError("Fluid reader fixture could not be initialized", error); }
    }
    private static void machines() {
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); CustomMulti meta = new CustomMulti(); tile.meta = meta;
        meta.mInputHatches.add(new Hatch()); meta.mEnergyHatches.add(null); meta.mEnergyHatches.add(new Hatch());
        JsonObject args = new JsonObject(); args.addProperty("componentLimit", 2);
        JsonObject result = MachineDataAdapter.read(null, tile, args, start());
        check(result.getAsJsonObject("checkRecipeResult").get("id").getAsString().equals("fixture.shutdown"), "check result id/message captured without rechecking");
        check(!result.getAsJsonObject("checkRecipeResult").get("successful").getAsBoolean() && result.getAsJsonObject("shutdownReason").get("critical").getAsBoolean(), "recipe failure and shutdown critical flag remain distinct");
        JsonObject processing = result.getAsJsonObject("processing");
        check(processing.get("signedCalculatedEUt").getAsLong() == -400 && processing.get("actualEnergyUsagePerTick").getAsLong() == 7777, "actual energy uses the controller override, not an invented efficiency calculation");
        check(processing.getAsJsonObject("lastMatchedRecipe").get("rawEUt").getAsInt() == 100 && processing.getAsJsonObject("lastMatchedRecipe").get("scope").getAsString().contains("cache"), "cached raw recipe is not labelled running recipe");
        check(result.getAsJsonObject("modes").get("index").getAsInt() == 2 && result.getAsJsonObject("upgrades").get("generalUpgradeCount").getAsInt() == 2, "machine mode and base upgrades included");
        check(result.getAsJsonObject("upgrades").get("lockUpgrade").getAsBoolean() && result.getAsJsonObject("upgrades").get("otherUpgradeCount").getAsInt() == 1, "exact base upgrade flag/count breakdown included");
        JsonObject components = result.getAsJsonObject("components");
        check(components.get("total").getAsInt() == 3 && components.get("nextOffset").getAsInt() == 2 && components.get("hasMore").getAsBoolean(), "components flatten roles with correct bounded pagination");
        check(components.getAsJsonArray("entries").get(0).getAsJsonObject().get("role").getAsString().equals("fluid_input"), "component role order retained");
        check(components.getAsJsonArray("entries").get(1).getAsJsonObject().get("reason").getAsString().equals("null_registered_entry"), "invalid registered null component is not filtered out");
        args.addProperty("componentOffset", 2);
        result = MachineDataAdapter.read(null, tile, args, start()); components = result.getAsJsonObject("components");
        check(components.getAsJsonArray("entries").size() == 1 && !components.get("hasMore").getAsBoolean(), "next component page does not duplicate first roles");
        check(components.getAsJsonArray("entries").get(0).getAsJsonObject().get("reason").getAsString().equals("base_tile_unavailable"), "missing live component base is explicit invalidity");
        expect("unsupported", () -> MachineDataAdapter.read(null, new Object(), new JsonObject(), start()));
        args.addProperty("componentLimit", 129); expect("invalid_args", () -> MachineDataAdapter.read(null, tile, args, start()));
    }
    private static void nbt() {
        NBTTagCompound tag = new NBTTagCompound(); tag.setInteger("WailaX", 3); tag.setInteger("WailaY", 4); tag.setInteger("WailaZ", 5);
        MovingObjectPosition mop = new MovingObjectPosition(3, 4, 5, 1, Vec3.createVectorHelper(3, 4, 5));
        check(WailaDataAdapter.matchesNbt(tag, mop), "Waila NBT target coords are validated");
        tag.setInteger("WailaX", 2); check(!WailaDataAdapter.matchesNbt(tag, mop), "wrong cached target NBT is rejected"); tag.setInteger("WailaX", 3);
        NBTTagList list = new NBTTagList(); list.appendTag(new NBTTagLong(Long.MAX_VALUE)); tag.setTag("longs", list);
        BoundedNbt.Snapshot copy = BoundedNbt.snapshot(tag);
        check(!copy.truncated && copy.tag != tag && copy.tag.getTagList("longs", 4).getStringTagAt(0).equals(Long.MAX_VALUE + "L"), "bounded NBT copy retains exact long scalar list values");
        copy.tag.setInteger("WailaX", 999); check(tag.getInteger("WailaX") == 3, "provider snapshot cannot mutate source cached NBT");
        tag.setByteArray("many", new byte[100000]); copy = BoundedNbt.snapshot(tag);
        check(copy.truncated && copy.tag.getByteArray("many").length == 32, "large NBT arrays are bounded before copy");
        NBTTagCompound root = new NBTTagCompound(), current = root;
        for (int i = 0; i < 20; i++) { NBTTagCompound child = new NBTTagCompound(); current.setTag("nested", child); current = child; }
        check(BoundedNbt.snapshot(root).truncated, "deep cached NBT is bounded without recursion overflow");
        JsonObject bad = new JsonObject(); bad.addProperty("includeNbt", "true");
        expect("invalid_args", () -> WailaDataAdapter.inspect(null, bad));
    }
    public static class CustomMulti extends MTEMultiBlockBase { @Override protected long getActualEnergyUsage() { return 7777; } }
    public static class Hatch { public Object getBaseMetaTileEntity() { return null; } public long getInputTier() { return 8; } public long getOutputTier() { return 8; } }
    private static void wailaProviders() {
        ItemStack stack = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 1, 0);
        NBTTagCompound source = new NBTTagCompound(); source.setString("fixture", "original"); stack.setTagCompound(source);
        IWailaDataAccessor accessor = () -> (NBTTagCompound) BoundedNbt.snapshot(source).tag.copy();
        IWailaConfigHandler config = new IWailaConfigHandler() { };
        JsonArray reports = new JsonArray(); int[] visits = {0};
        List<String> lines = WailaDataAdapter.invokeProvider(new BodyProvider(), IWailaDataProvider.class, "getWailaBody", stack,
                Collections.emptyList(), accessor, config, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, visits);
        check(lines.size() == 32 && reports.get(0).getAsJsonObject().get("returnedLines").getAsInt() == 100, "public Waila provider text is bounded with original count");
        check(stack.getTagCompound().getString("fixture").equals("original"), "Waila provider receives an isolated item stack copy");
        check(source.getString("fixture").equals("original") && visits[0] == 1, "Waila provider NBT copy does not mutate cached data");
        List<String> original = new ArrayList<>(); original.add("unchanged");
        lines = WailaDataAdapter.invokeProvider(new FailingBodyProvider(), IWailaDataProvider.class, "getWailaBody", stack,
                original, accessor, config, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, visits);
        check(lines.equals(original) && !reports.get(1).getAsJsonObject().get("available").getAsBoolean(), "provider failure is explicit and cannot leak partial mutation into accumulated tooltip");
        lines = WailaDataAdapter.invokeProvider(new TaggedBodyProvider(), IWailaDataProvider.class, "getWailaBody", stack,
                Collections.emptyList(), accessor, config, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, visits);
        check(lines.size() == 1 && reports.get(2).getAsJsonObject().get("available").getAsBoolean(), "Waila provider receives ITaggedList as required by StevesAddons 0.15.5");
        ITaggedList<String, String> tagged = (ITaggedList<String, String>) lines;
        check(tagged.getEntries("label").contains("tagged label"), "bounded provider result retains the original tag");
        List<String> taggedOriginal = lines;
        lines = WailaDataAdapter.invokeProvider(new TaggedBodyProvider(), IWailaDataProvider.class, "getWailaBody", stack,
                lines, accessor, config, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, visits);
        check(lines.size() == 1 && reports.get(3).getAsJsonObject().get("available").getAsBoolean(), "repeated block/tile label provider sees preserved tags and avoids duplicate lines");
        lines = WailaDataAdapter.invokeProvider(new FailingTaggedBodyProvider(), IWailaDataProvider.class, "getWailaBody", stack,
                taggedOriginal, accessor, config, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, visits);
        check(lines == taggedOriginal && !reports.get(4).getAsJsonObject().get("available").getAsBoolean(), "failed tagged provider keeps the original tooltip object");
        check(tagged.getEntries("label").contains("tagged label") && tagged.getEntries("must not leak").isEmpty(), "failed tagged provider cannot mutate accumulated tags");
    }
    public static class BodyProvider implements IWailaDataProvider {
        public List<String> getWailaBody(ItemStack stack, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            stack.getTagCompound().setString("fixture", "modified query"); accessor.getNBTData().setString("fixture", "modified data");
            for (int i = 0; i < 100; i++) lines.add("provider line " + i); return lines;
        }
    }
    public static class FailingBodyProvider implements IWailaDataProvider {
        public List<String> getWailaBody(ItemStack stack, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            lines.clear(); lines.add("must not leak"); throw new IllegalStateException("fixture provider failure");
        }
    }
    public static class TaggedBodyProvider implements IWailaDataProvider {
        @SuppressWarnings("unchecked")
        public List<String> getWailaBody(ItemStack stack, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            ITaggedList<String, String> tagged = (ITaggedList<String, String>) lines;
            if (tagged.getEntries("label").isEmpty()) tagged.add("tagged label", "label");
            return lines;
        }
    }
    public static class FailingTaggedBodyProvider implements IWailaDataProvider {
        @SuppressWarnings("unchecked")
        public List<String> getWailaBody(ItemStack stack, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            ITaggedList<String, String> tagged = (ITaggedList<String, String>) lines;
            tagged.addTag(0, "must not leak"); tagged.removeTag(0, "label");
            throw new IllegalStateException("fixture tagged provider failure");
        }
    }
    private static long start() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }
}
