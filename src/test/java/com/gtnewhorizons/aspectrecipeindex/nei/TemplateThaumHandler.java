package com.gtnewhorizons.aspectrecipeindex.nei;
import codechicken.nei.recipe.TemplateRecipeHandler;
import java.util.ArrayList;
import java.util.List;
import thaumcraft.api.aspects.AspectList;
public class TemplateThaumHandler extends TemplateRecipeHandler {
    protected static class CachedThaumRecipe {
        protected boolean shouldShowRecipe = true;
        protected List<ResearchInfo> prereqs = new ArrayList<ResearchInfo>();
        private final AspectList aspects;
        public int reads;
        protected CachedThaumRecipe(AspectList aspects) { this.aspects = aspects; }
        public AspectList getAspects() { reads++; if (!shouldShowRecipe) throw new AssertionError("Hidden recipe aspects must not be read"); return aspects; }
    }
    public void fixtureShown(int index, boolean shown) { ((CachedThaumRecipe) arecipes.get(index)).shouldShowRecipe = shown; }
    public int fixtureReads(int index) { return ((CachedThaumRecipe) arecipes.get(index)).reads; }
    public List<ResearchInfo> fixturePrereqs(int index) { return ((CachedThaumRecipe) arecipes.get(index)).prereqs; }
}
