package gregtech.api.recipe;

import gregtech.api.util.GTRecipe;
import java.util.Collection;
import java.util.function.Predicate;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Focused public lookup fixture, preserving native order, quantities and voltage x map amperage. */
public final class FindRecipeQuery {
    public final RecipeMap map;
    public ItemStack[] items;
    public FluidStack[] fluids;
    public ItemStack specialSlot;
    public long voltage;
    public boolean caching = true;
    public int finds;
    FindRecipeQuery(RecipeMap map) { this.map = map; }
    public FindRecipeQuery items(ItemStack... value) { items = value; return this; }
    public FindRecipeQuery fluids(FluidStack... value) { fluids = value; return this; }
    public FindRecipeQuery specialSlot(ItemStack value) { specialSlot = value; return this; }
    public FindRecipeQuery voltage(long value) { voltage = value; return this; }
    public FindRecipeQuery caching(boolean value) { caching = value; return this; }
    public FindRecipeQuery filter(Predicate<GTRecipe> value) { throw new AssertionError("Expected output must not change natural native selection"); }
    public FindRecipeQuery cachedRecipe(GTRecipe value) { throw new AssertionError("Private machine recipe cache must not be consulted"); }
    public FindRecipeQuery dontCheckStackSizes(boolean value) { throw new AssertionError("Finite backend counts cannot be ignored"); }
    public GTRecipe find() {
        finds++;
        if (caching || items == null || fluids == null || items.length + fluids.length == 0 || specialSlot != null)
            throw new AssertionError("Finite lookup must explicitly disable caching, supply finite inputs and use no special slot");
        GTRecipe result = null;
        if (map.backend.doesOverwriteFindRecipe()) {
            GTRecipe candidate = map.backend.dynamicRecipe;
            if (candidate != null && candidate.mEUt <= voltage * map.getAmperage()) result = candidate;
        } else {
            outer: for (Collection<GTRecipe> category : map.backend.categories.values()) for (GTRecipe candidate : category)
                if (candidate.mEnabled && !candidate.mFakeRecipe && candidate.mEUt <= voltage * map.getAmperage()
                        && candidate.isRecipeInputEqual(false, false, fluids, items)) { result = candidate; break outer; }
        }
        if (map.backend.mutateLookupCopies && items.length > 0) items[0].stackSize = 0;
        if (map.backend.mutateLookupCopies && fluids.length > 0) fluids[0].amount = 0;
        if (map.backend.afterFind != null) map.backend.afterFind.run();
        return result;
    }
}
