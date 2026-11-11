package codechicken.nei.recipe;
public class Recipe {
    public final RecipeHandlerRef ref;
    public Recipe(RecipeHandlerRef ref) { this.ref=ref; }
    public String getRecipeId() { return "fixture-recipe-"+ref.recipeIndex; }
}
