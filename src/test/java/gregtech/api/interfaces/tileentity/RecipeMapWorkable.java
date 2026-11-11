package gregtech.api.interfaces.tileentity;

import java.util.Collection;
import java.util.Collections;
import net.minecraft.item.ItemStack;
import gregtech.api.recipe.RecipeMap;

public interface RecipeMapWorkable {
    RecipeMap getRecipeMap();
    ItemStack getStackForm(long amount);
    default Collection<RecipeMap> getAvailableRecipeMaps() { return getRecipeMap() == null ? Collections.emptyList() : Collections.singletonList(getRecipeMap()); }
}
