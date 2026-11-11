package codechicken.nei.recipe;
public class GuiOverlayButton extends GuiRecipeButton {
    public GuiOverlayButton(RecipeHandlerRef ref) { super(ref); }
    public boolean fixtureMissingMaterials;
    public boolean canFillCraftingGrid() { return !fixtureMissingMaterials; }
    public boolean hasOverlay() { return true; }
    public boolean requireShiftForOverlayRecipe() { return false; }
    public Recipe getRecipe() { return new Recipe(handlerRef); }
}
