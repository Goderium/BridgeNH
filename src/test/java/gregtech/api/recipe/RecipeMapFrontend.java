package gregtech.api.recipe;

public class RecipeMapFrontend {
    public final NEIRecipeProperties neiProperties;
    public RecipeMapFrontend(boolean registerNEI) { neiProperties = new NEIRecipeProperties(registerNEI); }
    public NEIRecipeProperties getNEIProperties() { return neiProperties; }
}
