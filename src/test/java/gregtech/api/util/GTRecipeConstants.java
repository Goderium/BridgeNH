package gregtech.api.util;

import gregtech.api.recipe.RecipeMetadataKey;
import gregtech.api.util.recipe.Scanning;
import net.minecraft.item.ItemStack;

public class GTRecipeConstants {
    public static final RecipeMetadataKey<Boolean> LOW_GRAVITY = new RecipeMetadataKey<>(Boolean.class, "low_gravity");
    public static final RecipeMetadataKey<Boolean> CLEANROOM = new RecipeMetadataKey<>(Boolean.class, "cleanroom");
    public static final RecipeMetadataKey<Integer> COIL_HEAT = new RecipeMetadataKey<>(Integer.class, "coil_heat");
    public static final RecipeMetadataKey<Long> FUSION_THRESHOLD = new RecipeMetadataKey<>(Long.class, "fusion_threshold");
    public static final RecipeMetadataKey<ItemStack> RESEARCH_ITEM = new RecipeMetadataKey<>(ItemStack.class, "research_item");
    public static final RecipeMetadataKey<Scanning> SCANNING = new RecipeMetadataKey<>(Scanning.class, "scanning");
    public static final RecipeMetadataKey<Integer> COMPRESSION_TIER = new RecipeMetadataKey<>(Integer.class, "compression_tier");
    public static final RecipeMetadataKey<ItemStack> QFT_CATALYST = new RecipeMetadataKey<>(ItemStack.class, "qft_catalyst");
    public static final RecipeMetadataKey<Integer> GLASS = new RecipeMetadataKey<>(Integer.class, "GLASS");
    public static final RecipeMetadataKey<Integer> SIEVERT = new RecipeMetadataKey<>(Integer.class, "SIEVERT");
    public static final RecipeMetadataKey<Boolean> RECYCLE = new RecipeMetadataKey<>(Boolean.class, "recycle");
}
