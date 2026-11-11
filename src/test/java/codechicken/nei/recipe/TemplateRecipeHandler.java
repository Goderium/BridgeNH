package codechicken.nei.recipe;

import codechicken.nei.PositionedStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TemplateRecipeHandler implements IRecipeHandler {
    public List<Object> arecipes = new ArrayList<>();
    public String getRecipeName() { return "fixture GT recipes"; }
    public int numRecipes() { return arecipes.size(); }
    public List<PositionedStack> getIngredientStacks(int index) { return Collections.emptyList(); }
    public PositionedStack getResultStack(int index) { return null; }
    public List<PositionedStack> getOtherStacks(int index) { return Collections.emptyList(); }
}
