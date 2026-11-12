package blockrenderer6343.integration.nei;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;

/** Existing 1.4.25 model fixture; reads never construct a structure or render a frame. */
public class MultiblockHandler implements IRecipeHandler {
    private final GuiMultiblockHandler guiHandler;
    private final Object[] currentMultiblocks;
    private int oldRecipe;
    private final RecipeCacher recipeCacher = new RecipeCacher();
    public MultiblockHandler(GuiMultiblockHandler gui, Object controller) {
        guiHandler = gui;
        currentMultiblocks = new Object[] { controller };
        gui.fixtureController(controller);
    }
    public static IRecipeHandler getHandlerFromGui(GuiScreen screen) { return ((GuiRecipe) screen).getHandler(); }
    public String getRecipeName() { return "Fixture multiblock"; }
    public int numRecipes() { return 1; }
    public List<PositionedStack> getIngredientStacks(int recipe) { return Collections.emptyList(); }
    public PositionedStack getResultStack(int recipe) { return null; }
    public List<PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
    public static final class RecipeCacher {
        private final List<PositionedStack> positionedResults = Collections.emptyList();
    }
}
