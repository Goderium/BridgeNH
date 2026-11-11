package codechicken.nei.recipe;
import codechicken.nei.PositionedStack;
import codechicken.nei.Widget;
public class NEIRecipeWidget extends Widget {
    public RecipeHandlerRef ref; public final HandlerInfo info = new HandlerInfo();
    public NEIRecipeWidget(RecipeHandlerRef ref) { this.ref = ref; w = 200; h = 150; }
    public RecipeHandlerRef getRecipeHandlerRef() { return ref; }
    public HandlerInfo getHandlerInfo() { return info; }
    public PositionedStack getPositionedStackMouseOver(int x, int y) {
        for (PositionedStack stack : ref.handler.getIngredientStacks(ref.recipeIndex)) if (hit(stack, x, y)) return stack;
        PositionedStack output = ref.handler.getResultStack(ref.recipeIndex);
        if (output != null && hit(output, x, y)) return output;
        for (PositionedStack stack : ref.handler.getOtherStacks(ref.recipeIndex)) if (hit(stack, x, y)) return stack;
        return null;
    }
    private boolean hit(PositionedStack stack, int mx, int my) { int px = x + stack.relx, py = y + info.yShift + stack.rely; return mx >= px && my >= py && mx < px + stack.width && my < py + stack.height; }
}
