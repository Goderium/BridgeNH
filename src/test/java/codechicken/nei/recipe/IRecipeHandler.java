package codechicken.nei.recipe;

import java.util.List;
import codechicken.nei.PositionedStack;

/** Public getter fixture; mutation/render callbacks are tripwires. */
public interface IRecipeHandler {
    default String getHandlerId() { return "fixture.handler"; }
    String getRecipeName();
    int numRecipes();
    List<PositionedStack> getIngredientStacks(int recipe);
    PositionedStack getResultStack(int recipe);
    List<PositionedStack> getOtherStacks(int recipe);
    default List<String> handleTooltip(GuiRecipe gui, List<String> lines, int recipe) { lines.add("handler tooltip " + recipe); return lines; }
    default List<String> handleItemTooltip(GuiRecipe gui, net.minecraft.item.ItemStack stack, List<String> lines, int recipe) { lines.add("handler item tooltip " + recipe); return lines; }
    default void onUpdate() { throw new AssertionError("NEI update is forbidden"); }
    default void drawBackground(int recipe) { throw new AssertionError("NEI draw is forbidden"); }
}
