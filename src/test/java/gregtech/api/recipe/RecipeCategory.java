package gregtech.api.recipe;

import java.util.function.Supplier;

public class RecipeCategory {
    public final String unlocalizedName;
    public final RecipeMap recipeMap;
    public final Supplier<RecipeCategorySetting> settingSupplier;
    public RecipeCategory(String name, RecipeMap map, Supplier<RecipeCategorySetting> setting) { unlocalizedName = name; recipeMap = map; settingSupplier = setting; }
}
