package codechicken.nei.recipe;
public class RecipeHandlerRef {
    public final IRecipeHandler handler; public final int recipeIndex;
    public boolean fixtureCanTransfer = true;
    public boolean canFillCraftingGrid(net.minecraft.client.gui.inventory.GuiContainer gui) { return fixtureCanTransfer; }
    public RecipeHandlerRef(IRecipeHandler handler, int recipeIndex) { this.handler = handler; this.recipeIndex = recipeIndex; }
}
