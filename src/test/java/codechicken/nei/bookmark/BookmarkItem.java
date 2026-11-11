package codechicken.nei.bookmark;
public class BookmarkItem {
    public int groupId; public String recipeId,type; public net.minecraft.item.ItemStack itemStack;
    public long amount;
    public long multiplier = 1, factor = 1, chance = 10000;
    public int fluidCellAmount = 1;
    public java.util.Map<String, net.minecraft.item.ItemStack> permutations = new java.util.LinkedHashMap<>();
    public long getAmount() { return amount; }
    public long getMultiplier() { return multiplier; }
    public long getStackSize() { return itemStack.stackSize; }
}
