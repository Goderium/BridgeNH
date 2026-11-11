package codechicken.nei.recipe;
public class GuiRecipeButton extends net.minecraft.client.gui.GuiButton {
    public final RecipeHandlerRef handlerRef;
    public GuiRecipeButton(RecipeHandlerRef ref) { super(0,0,0,"+"); handlerRef=ref; }
}
