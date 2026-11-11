package com.gtnewhorizons.aspectrecipeindex.nei;
import thaumcraft.api.aspects.AspectList;
public class InfusionRecipeHandler extends TemplateThaumHandler {
    public static class InfusionCachedRecipe extends CachedThaumRecipe {
        private int instability;
        public InfusionCachedRecipe(AspectList aspects, int instability) { super(aspects); this.instability = instability; }
    }
    public void fixtureAdd(AspectList aspects, int instability) { arecipes.add(new InfusionCachedRecipe(aspects, instability)); }
}
