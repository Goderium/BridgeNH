package gregtech.nei;

import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;

public class GTNEIDefaultHandler extends TemplateRecipeHandler {
    public RecipeMap getRecipeMap() { return new RecipeMap(); }
    public static class Calculator {
        public long getConsumption() { return 400; }
        public int getDuration() { return 40; }
        public void calculate() { throw new AssertionError("Existing calculator must not be recomputed"); }
    }
    public static class CachedDefaultRecipe {
        public final GTRecipe mRecipe;
        public Calculator calculator;
        public CachedDefaultRecipe(GTRecipe recipe) { mRecipe = recipe; }
    }
}
