package codechicken.nei;

import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;

/** Fixed public NEI fields/getters, without constructors that generate permutations. */
public class PositionedStack {
    public int relx, rely, width = 16, height = 16;
    public ItemStack[] items;
    public ItemStack item;
    public PositionedStack(ItemStack[] items, int x, int y) { this.items = items; this.item = items.length == 0 ? null : items[0]; relx = x; rely = y; }
    public int getChance() { return 2500; }
    public String getAcceptsLabel() { return "fixture alternatives"; }
    public List<String> getTooltip() { return Collections.singletonList("position tooltip"); }
    public List<codechicken.nei.recipe.Badge> getBadges() { return Collections.singletonList(new codechicken.nei.recipe.Badge()); }
    public void setPermutationToRender(int index) { throw new AssertionError("Permutation mutation is forbidden"); }
    public void draw(int x, int y) { throw new AssertionError("Rendering is forbidden"); }
}
