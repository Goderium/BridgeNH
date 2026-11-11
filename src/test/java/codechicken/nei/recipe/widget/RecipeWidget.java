package codechicken.nei.recipe.widget;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.RecipeHandlerRef;
public class RecipeWidget extends codechicken.nei.recipe.NEIRecipeWidget {
    protected RecipeGroup group;
    protected int activeMember;
    public final java.util.List<Object> buttons = new java.util.ArrayList<Object>();
    public RecipeWidget(RecipeHandlerRef ref) { super(ref); group=new RecipeGroup(ref.handler.getIngredientStacks(ref.recipeIndex), ref.handler.getResultStack(ref.recipeIndex)==null ? ref.handler.getOtherStacks(ref.recipeIndex) : java.util.Collections.singletonList(ref.handler.getResultStack(ref.recipeIndex))); }
    @Override public RecipeHandlerRef getRecipeHandlerRef() { return new RecipeHandlerRef(ref.handler, ref.recipeIndex); }
    public java.util.List<Object> getRecipeButtons() { return buttons; }
    @Override public PositionedStack getPositionedStackMouseOver(int mx,int my) {
        java.util.List<PositionedStack> stacks=new java.util.ArrayList<PositionedStack>(group.inputs); stacks.addAll(group.outputs);
        for(PositionedStack s:stacks) if(mx>=x+s.relx && mx<x+s.relx+s.width && my>=y+info.yShift+s.rely && my<y+info.yShift+s.rely+s.height) return s;
        return null;
    }
}
