package codechicken.nei.recipe;

import java.util.List;
import java.util.ArrayList;

/** No Minecraft client or window is constructed by this fixture. */
public class GuiRecipe extends net.minecraft.client.gui.GuiScreen {
    private IRecipeHandler handler;
    private final List<Integer> refs;
    public List<IRecipeHandler> currenthandlers = new ArrayList<>();
    public int recipetype;
    public int page;
    public Object prevGui;
    private RecipePageManager handlerPages = new RecipePageManager(10);
    private codechicken.nei.WidgetContainer container = new codechicken.nei.WidgetContainer();
    public codechicken.nei.WidgetContainer fixtureContainer() { return container; }
    public GuiRecipe(IRecipeHandler handler, List<Integer> refs) { this.handler = handler; this.refs = refs; currenthandlers.add(handler); }
    public IRecipeHandler getHandler() { return handler; }
    public List<Integer> getRecipeIndices() { return refs; }
    protected void setRecipePage(int index) { recipetype = index; handler = currenthandlers.get(index); handlerPages = new RecipePageManager(10); }
}
