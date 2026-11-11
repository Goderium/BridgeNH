package gregtech.api.recipe.metadata;

import java.util.Map;
import java.util.Set;
import gregtech.api.recipe.RecipeMetadataKey;

public interface IRecipeMetadataStorage {
    Set<Map.Entry<RecipeMetadataKey<?>, Object>> getEntries();
}
