package com.github.dcysteine.neicustomdiagram.api.diagram;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import java.util.Collections;
import java.util.List;
public class DiagramGroup implements IRecipeHandler {
    public final List<Diagram> diagrams;
    public final DiagramState diagramState;
    public DiagramGroup(List<Diagram> diagrams, DiagramState state) { this.diagrams = diagrams; diagramState = state; }
    public String getRecipeName() { return "fixture custom diagram"; }
    public int numRecipes() { return diagrams.size(); }
    public List<PositionedStack> getIngredientStacks(int index) { return Collections.emptyList(); }
    public PositionedStack getResultStack(int index) { return null; }
    public List<PositionedStack> getOtherStacks(int index) { return Collections.emptyList(); }
    public void loadDiagrams() { throw new AssertionError("No diagram loading"); }
}
