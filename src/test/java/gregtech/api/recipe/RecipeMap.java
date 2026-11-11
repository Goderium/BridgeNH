package gregtech.api.recipe;

import java.util.LinkedHashMap;
import java.util.Map;

public class RecipeMap {
    public static final Map<String, RecipeMap> ALL_RECIPE_MAPS = new LinkedHashMap<>();
    public final String unlocalizedName;
    public final RecipeMapBackend backend = new RecipeMapBackend();
    public final RecipeMapFrontend frontend;
    public final RecipeCategory defaultCategory;
    public FindRecipeQuery lastQuery;
    public RecipeMap() { this("fixture.recipe.map"); }
    public RecipeMap(String name) { this(name, true); }
    public RecipeMap(String name, boolean registerNEI) {
        unlocalizedName = name; frontend = new RecipeMapFrontend(registerNEI);
        defaultCategory = new RecipeCategory(name, this, () -> RecipeCategorySetting.ENABLE);
        ALL_RECIPE_MAPS.put(name, this);
    }
    public int getAmperage() { return 2; }
    public RecipeMapBackend getBackend() { return backend; }
    public RecipeMapFrontend getFrontend() { return frontend; }
    public RecipeCategory getDefaultRecipeCategory() { return defaultCategory; }
    public FindRecipeQuery findRecipeQuery() { lastQuery = new FindRecipeQuery(this); return lastQuery; }
    public java.util.Collection<?> getAllRecipes() { throw new AssertionError("Unbounded full-map copy is forbidden"); }
}
