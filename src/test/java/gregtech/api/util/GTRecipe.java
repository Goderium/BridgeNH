package gregtech.api.util;

import java.util.LinkedHashMap;
import java.util.Map;
import gregtech.api.recipe.RecipeMetadataKey;
import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.metadata.IRecipeMetadataStorage;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

public class GTRecipe {
    public ItemStack[] mInputs = new ItemStack[0], mOutputs = new ItemStack[0];
    public FluidStack[] mFluidInputs = new FluidStack[0], mFluidOutputs = new FluidStack[0];
    public FluidStack[][] mAltFluidInputs;
    public Object mSpecialItems;
    public int mDuration = 80, mEUt = 100, mSpecialValue = 900;
    public boolean mEnabled = true, mHidden = false, mFakeRecipe = false, mCanBeBuffered = true, mNeedsEmptyOutput = false, isNBTSensitive = true;
    public final Map<RecipeMetadataKey<?>, Object> metadata = new LinkedHashMap<>();
    public boolean mutateValidationCopies;
    public FluidStack[] lastValidationFluids;
    public RecipeCategory recipeCategory;
    public RecipeCategory getRecipeCategory() { return recipeCategory; }
    public boolean isRecipeInputEqual(boolean decrease, boolean ignoreSizes, FluidStack[] fluids, ItemStack... items) {
        if (decrease || ignoreSizes) throw new AssertionError("Backend inspection must preserve native quantities and never consume");
        lastValidationFluids = fluids;
        int[] fluidRemaining = new int[fluids.length];
        for (int index = 0; index < fluids.length; index++) fluidRemaining[index] = fluids[index] == null ? 0 : fluids[index].amount;
        for (FluidStack required : mFluidInputs) {
            if (required == null) continue;
            int needed = required.amount; boolean present = false;
            for (int index = 0; index < fluids.length; index++) {
                FluidStack actual = fluids[index];
                if (actual == null || !actual.isFluidEqual(required)) continue;
                present = true; int amount = Math.min(fluidRemaining[index], needed); fluidRemaining[index] -= amount; needed -= amount;
            }
            if (!present || needed > 0) return false;
        }
        int[] remaining = new int[items.length];
        for (int index = 0; index < items.length; index++) remaining[index] = items[index] == null ? 0 : items[index].stackSize;
        for (ItemStack required : mInputs) {
            if (required == null) continue;
            int needed = required.stackSize; boolean present = false;
            for (int index = 0; index < items.length; index++) {
                ItemStack actual = items[index];
                if (actual == null || actual.getItem() != required.getItem()
                        || (required.getMetadata() != 32767 && actual.getMetadata() != required.getMetadata())
                        || (isNBTSensitive && !ItemStack.areItemStackTagsEqual(actual, required))) continue;
                present = true; int amount = Math.min(remaining[index], needed); remaining[index] -= amount; needed -= amount;
            }
            if (!present || needed > 0) return false;
        }
        if (mutateValidationCopies && items.length > 0) items[0].stackSize = 0;
        if (mutateValidationCopies && fluids.length > 0) fluids[0].amount = 0;
        return true;
    }
    public int getInputChance(int index) { return 10000; }
    public int getOutputChance(int index) { return index == 0 ? 2500 : 10000; }
    public int getFluidInputChance(int index) { return 7500; }
    public int getFluidOutputChance(int index) { return 5000; }
    public String[] getNeiDesc() { return new String[] {"fixture condition"}; }
    public IRecipeMetadataStorage getMetadataStorage() { return () -> metadata.entrySet(); }
    public void consumeInput() { throw new AssertionError("Recipe consumption forbidden"); }
    public void calculate() { throw new AssertionError("Recipe simulation forbidden"); }
    public static class GTRecipe_WithAlt extends GTRecipe {
        public ItemStack[][] mOreDictAlt;
        public int[] mOreDictIds;
    }
}
