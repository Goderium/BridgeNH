package codechicken.nei;
import java.util.List;
import net.minecraft.item.ItemStack;
public class Widget {
    public int x, y, w = 100, h = 100, z;
    public boolean isFocused() { return false; }
    public ItemStack getStackMouseOver(int x, int y) { return null; }
    public List<String> handleTooltip(int x, int y, List<String> lines) { lines.add("widget tooltip"); return lines; }
    public List<String> handleItemTooltip(ItemStack stack, int x, int y, List<String> lines) { lines.add("widget stack tooltip"); return lines; }
    public void draw(int x, int y) { throw new AssertionError("No widget draw"); }
    public void update() { throw new AssertionError("No widget update"); }
}
