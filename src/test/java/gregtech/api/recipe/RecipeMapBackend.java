package gregtech.api.recipe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import gregtech.api.util.GTRecipe;

public class RecipeMapBackend {
    public final Map<RecipeCategory, Collection<GTRecipe>> categories = new LinkedHashMap<>();
    public boolean overwrite, mutateLookupCopies;
    public GTRecipe dynamicRecipe;
    public Runnable afterFind;
    public boolean doesOverwriteFindRecipe() { return overwrite; }
    public Map<RecipeCategory, Collection<GTRecipe>> getRecipeCategoryMap() { return Collections.unmodifiableMap(categories); }
    public Collection<GTRecipe> getRecipesByCategory(RecipeCategory category) { return Collections.unmodifiableCollection(categories.get(category)); }
    public Collection<GTRecipe> getAllRecipes() { throw new AssertionError("Unbounded full-map copy is forbidden"); }
    public void matchRecipeStream() { throw new AssertionError("Recipe execution matching is forbidden"); }
}
