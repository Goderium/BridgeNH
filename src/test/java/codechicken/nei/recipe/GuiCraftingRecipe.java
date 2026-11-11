package codechicken.nei.recipe;

import net.minecraft.item.ItemStack;

/** Semantic opening fixture; no Minecraft or LWJGL GUI is created. */
public class GuiCraftingRecipe {
    public static ItemStack query;
    public static boolean openRecipeGui(String id, Object... results) {
        if (!"item".equals(id) || results.length != 1) throw new AssertionError("Unexpected semantic recipe query");
        query = (ItemStack) results[0]; query.getTagCompound().setString("fixture", "only copied query changed");
        return false;
    }
}
