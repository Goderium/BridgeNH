package codechicken.nei;
import java.util.ArrayList;
import net.minecraft.item.ItemStack;
public class ItemsGrid {
    public static class ItemsGridSlot {
        public final int slotIndex, itemIndex; public final ItemStack item;
        public ItemsGridSlot(int slot, int index, ItemStack stack) { slotIndex = slot; itemIndex = index; item = stack; }
        public ItemStack getItemStack() { return item; }
    }
    public ArrayList<ItemStack> items = new ArrayList<ItemStack>();
    protected boolean[] invalidSlotMap = new boolean[16];
    public int rows = 4, columns = 4;
    public int page = 1;
    public ArrayList<ItemStack> getItems() { return items; }
    public int size() { return items.size(); }
    public int getPage() { return page; }
    public int getNumPages() { return 4; }
    public int getRows() { return rows; }
    public int getColumns() { return columns; }
    public codechicken.lib.vec.Rectangle4i getSlotRect(int slot) { return new codechicken.lib.vec.Rectangle4i(slot % columns * 18, slot / columns * 18, 18, 18); }
    public ItemsGridSlot getSlotMouseOver(int x, int y) { return null; }
    public int getPerPage() { int count = 0; for (boolean invalid : invalidSlotMap) if (!invalid) count++; return count; }
    public void fixtureInvalid(int slot) { invalidSlotMap[slot] = true; }
    public void fixtureMask(java.util.List<ItemsGridSlot> slots) { throw new AssertionError("Fixture subclass must store its existing mask"); }
    public java.util.List<?> getMask() { throw new AssertionError("Lazy mask generation is forbidden"); }
    public void update() { throw new AssertionError("No grid update"); }
}
